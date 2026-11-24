// Modified 2026-11-24 (DFRoot GZIF): atomically persist complete run snapshots in DE preferences. See NOTICE.
package df.root;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

/** SharedPreferences adapter. Callers must supply a Device Protected Storage context. */
final class SharedPreferencesConfigurationStore implements ConfigurationStore {
    private final SharedPreferences preferences;

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
        return new RunConfig(
                preferences.getString(ConfigurationKeys.PREF_SU_MANAGER, null),
                preferences.getBoolean(ConfigurationKeys.PREF_SOFT_REBOOT, false),
                preferences.getBoolean(ConfigurationKeys.PREF_DISABLE_MODULES, false),
                preferences.getInt(ConfigurationKeys.PREF_SOFT_REBOOT_ACK_VERSION, 0),
                preferences.getLong(ConfigurationKeys.PREF_GENERATION, 0));
    }

    @Override
    public boolean write(RunConfig config) {
        java.util.Objects.requireNonNull(config, "config");
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
        return editor.commit();
    }
}
