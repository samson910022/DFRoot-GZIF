// Modified 2026-11-24 (DFRoot GZIF): atomically persist complete run snapshots in DE preferences. See NOTICE.
// Modified 2026-11-27 (R9.1): restore the P2-08 write-ahead boundary: marker
// before commit, read-back after commit, fail-closed quarantine on any fault,
// and no promotion of a divergent in-memory candidate on reload. See NOTICE.
package df.root;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

/** SharedPreferences adapter. Callers must supply a Device Protected Storage context. */
final class SharedPreferencesConfigurationStore implements ConfigurationStore {
    /**
     * Marker identity for the whole snapshot transaction. Every field is
     * committed in ONE editor commit; the write-ahead marker therefore belongs
     * to the snapshot, not to an individual preference key.
     */
    private static final String WRITE_KEY = ConfigurationKeys.PREFS_NAME + ".configuration.snapshot";

    /** Serializes begin -> commit -> read-back -> finish across store instances. */
    private static final Object WRITE_LOCK = new Object();

    private final SharedPreferences preferences;
    /** Last snapshot proven durable; never replaced by an uncommitted candidate. */
    private volatile RunConfig durableSnapshot;

    SharedPreferencesConfigurationStore(Context deviceProtectedContext) {
        this.preferences = Objects.requireNonNull(deviceProtectedContext, "deviceProtectedContext")
                .getSharedPreferences(ConfigurationKeys.PREFS_NAME, Context.MODE_PRIVATE);
    }

    SharedPreferencesConfigurationStore(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    static SharedPreferencesConfigurationStore forDeviceProtectedStorage(Context context) {
        Context application = context.getApplicationContext();
        Context deContext = (application != null ? application : context)
                .createDeviceProtectedStorageContext();
        return new SharedPreferencesConfigurationStore(deContext);
    }

    @Override
    public RunConfig load() {
        synchronized (WRITE_LOCK) {
            if (PreferenceIntegrity.isQuarantined()) {
                // commit() can fail AFTER mutating the in-memory SharedPreferences
                // map. Never promote that candidate: it never reached the XML the
                // privileged bootstrap reads. Return the last proven snapshot, or
                // the safe empty configuration before any commit was observed.
                RunConfig proven = durableSnapshot;
                return proven == null ? RunConfig.defaults() : proven;
            }
            RunConfig loaded = readFromPreferences();
            durableSnapshot = loaded;
            return loaded;
        }
    }

    @Override
    public boolean write(RunConfig config) {
        Objects.requireNonNull(config, "config");
        synchronized (WRITE_LOCK) {
            if (PreferenceIntegrity.beginWriteEx(WRITE_KEY)
                    != PreferenceIntegrity.TokenResult.OK) {
                // No marker, no commit: the quarantine latch/gate has already
                // been applied by beginWriteEx. Nothing can be promoted.
                return false;
            }
            try {
                SharedPreferences.Editor editor = preferences.edit();
                if (config.getManagerPackage() == null) {
                    editor.remove(ConfigurationKeys.PREF_SU_MANAGER);
                } else {
                    editor.putString(ConfigurationKeys.PREF_SU_MANAGER, config.getManagerPackage());
                }
                editor.putBoolean(ConfigurationKeys.PREF_SOFT_REBOOT, config.isSoftReboot());
                editor.putBoolean(ConfigurationKeys.PREF_DISABLE_MODULES, config.isDisableModules());
                editor.putInt(ConfigurationKeys.PREF_SOFT_REBOOT_ACK_VERSION,
                        config.getSoftRebootAcknowledgementVersion());
                editor.putLong(ConfigurationKeys.PREF_GENERATION, config.getGeneration());

                if (!editor.commit()) {
                    PreferenceIntegrity.markAmbiguous(WRITE_KEY);
                    return false;
                }
                // Read back the complete snapshot. A commit that reports success
                // without publishing every field is just as uncertain as a false
                // commit and must fail closed at the same boundary.
                if (!sameSnapshot(config, readFromPreferences())) {
                    PreferenceIntegrity.markAmbiguous(WRITE_KEY);
                    return false;
                }
                if (!PreferenceIntegrity.finishWriteSuccess(WRITE_KEY)) {
                    return false; // marker retirement failed; latch already handled
                }
                durableSnapshot = config;
                return true;
            } catch (RuntimeException e) {
                PreferenceIntegrity.markAmbiguous(WRITE_KEY);
                throw e; // preserve the repository's exception contract
            }
        }
    }

    /** Direct, unlatched observation used only inside a bracketed transaction. */
    private RunConfig readFromPreferences() {
        return new RunConfig(
                preferences.getString(ConfigurationKeys.PREF_SU_MANAGER, null),
                preferences.getBoolean(ConfigurationKeys.PREF_SOFT_REBOOT, false),
                preferences.getBoolean(ConfigurationKeys.PREF_DISABLE_MODULES, false),
                preferences.getInt(ConfigurationKeys.PREF_SOFT_REBOOT_ACK_VERSION, 0),
                preferences.getLong(ConfigurationKeys.PREF_GENERATION, 0));
    }

    private static boolean sameSnapshot(RunConfig expected, RunConfig observed) {
        return Objects.equals(expected.getManagerPackage(), observed.getManagerPackage())
                && expected.isSoftReboot() == observed.isSoftReboot()
                && expected.isDisableModules() == observed.isDisableModules()
                && expected.getSoftRebootAcknowledgementVersion()
                        == observed.getSoftRebootAcknowledgementVersion()
                && expected.getGeneration() == observed.getGeneration();
    }
}
