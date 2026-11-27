// Modified 2026-11-24 (DFRoot GZIF): wire settings to confirmed configuration and require durable modal consent. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): gate boot and dangerous settings on writable configuration plus an idle coordinator. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): keep Start at Boot OFF reachable in every state and surface component-setting failures. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.6): comment only — the OFF write is a
// PackageManager operation outside the coordinator monitor, not atomic with
// admission. No behavior change. See NOTICE.
// Modified 2026-11-25 (PR #3 round-4/5/6): tri-state component observation,
// UNKNOWN presentation with a persistent status row, an explicit safe OFF
// action, quarantine-gated writes and lifecycle-scoped integrity listener.
// See NOTICE.
package df.root;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.util.Log;
import android.widget.Toast;

import df.root.BootUiPolicy.BootComponentState;
import df.root.BootUiPolicy.BootStatusPresentation;

public class SettingsFragment extends PreferenceFragment {
    private static final String BOOT_UNAVAILABLE =
            "Unavailable - Start at Boot is disabled in this build";
    private static final String SOFT_REBOOT_UNAVAILABLE =
            "Unavailable - experimental Auto Soft Reboot is disabled in this build";
    private static final String BOOT_CHANGE_FAILURE =
            "Could not change Start at Boot - component setting failed: ";

    /**
     * Narrow seam for the separate PackageManager component setting. Tests can
     * inject a deterministic failing/stuck controller; production uses PackageManager.
     */
    interface BootComponentController {
        BootComponentController PACKAGE_MANAGER = new BootComponentController() {
            @Override
            public int read(Context context, ComponentName component) {
                return context.getPackageManager().getComponentEnabledSetting(component);
            }

            @Override
            public int set(Context context, ComponentName component, int newState) {
                PackageManager pm = context.getPackageManager();
                pm.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP);
                // Return the state the system actually applied, never the requested one.
                return pm.getComponentEnabledSetting(component);
            }
        };

        int read(Context context, ComponentName component);
        int set(Context context, ComponentName component, int newState);
    }

    private BootComponentController mBootComponents = BootComponentController.PACKAGE_MANAGER;
    private ConfigurationRepository mConfiguration;
    private RunCoordinator mCoordinator;
    private SwitchPreference mBootStartPreference;
    private Preference mBootStatusPreference;   // master: persistent UNKNOWN/OFF status row
    private Preference mBootDisablePreference;  // master: explicit safe OFF action
    private Context mAppContext;
    private PreferenceIntegrity.Listener mIntegrityListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private SwitchPreference mSoftRebootPreference;
    private SwitchPreference mDisableModulesPreference;
    private Preference mStatusPreference;
    private PreferenceScreen mPreferenceScreen;
    private Preference mRetryPreference;
    private String mBootChangeError;

    private final ConfigurationRepository.Listener mConfigurationListener =
            this::renderConfirmedConfiguration;
    private final RunCoordinator.Listener mCoordinatorListener = snapshot -> renderConfirmedConfiguration();

    private static final String TAG = "dfroot";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        mAppContext = ctx.getApplicationContext(); // never retain an Activity in a callback
        // Initialize the durable write-ahead boundary before the repository can
        // start its load/migration; ConfigurationRepository is the only writer.
        PreferenceIntegrity.init(ctx); // durable quarantine latch; SKIP-only for launches
        mConfiguration = ConfigurationRepository.get(ctx);
        mCoordinator = RunCoordinator.get(ctx);

        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);
        mBootStartPreference = (SwitchPreference) findPreference("boot_start");
        mBootStartPreference.setPersistent(false);
        // Unattended start is unavailable at code level. There is no enable
        // branch here (and no COMPONENT_ENABLED_STATE_ENABLED write anywhere in
        // production); every attempted change is rejected defensively. The
        // explicit safe OFF action below remains available in every state.
        mBootStartPreference.setOnPreferenceChangeListener((pref, value) -> false);
        // Master: authoritative tri-state observation; DEFAULT/failure is UNKNOWN.
        mBootStatusPreference = findPreference("boot_status");
        mBootDisablePreference = findPreference("boot_disable");
        if (mBootDisablePreference != null) {
            mBootDisablePreference.setOnPreferenceClickListener(preference -> {
                requestDisableBootLaunch();
                return true;
            });
        }
        applyBootPresentation(readBootComponentState(ctx, bootReceiver));

        mStatusPreference = findPreference("configuration_status");
        mPreferenceScreen = getPreferenceScreen();
        mRetryPreference = findPreference("configuration_retry");
        mPreferenceScreen.removePreference(mRetryPreference);
        mRetryPreference.setOnPreferenceClickListener(preference -> {
            if (!isCoordinatorIdle()) {
                return true;
            }
            if (!mConfiguration.reload()) {
                renderConfirmedConfiguration();
            }
            return true;
        });
        mSoftRebootPreference = (SwitchPreference) findPreference(
                ConfigurationKeys.PREF_SOFT_REBOOT);
        mSoftRebootPreference.setPersistent(false);
        mSoftRebootPreference.setSummary(SOFT_REBOOT_UNAVAILABLE);
        // Experimental soft reboot is unavailable: no consent dialog and no
        // enable write exist. Every attempted change is rejected defensively.
        mSoftRebootPreference.setOnPreferenceChangeListener((pref, value) -> false);

        mDisableModulesPreference = (SwitchPreference) findPreference(
                ConfigurationKeys.PREF_DISABLE_MODULES);
        mDisableModulesPreference.setPersistent(false);
        mDisableModulesPreference.setOnPreferenceChangeListener((pref, value) -> {
            if (!areControlsWritable() || PreferenceIntegrity.isQuarantined()) return false;
            mConfiguration.setDisableModules((Boolean) value);
            return false;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        mConfiguration.addListener(mConfigurationListener);
        mCoordinator.addListener(mCoordinatorListener);
        renderConfirmedConfiguration();
        // Master: identity-deduplicated, lifecycle-scoped integrity listener;
        // UI-only re-read, never an authorization oracle.
        if (mIntegrityListener == null) mIntegrityListener = this::renderBootPresentationFromState;
        PreferenceIntegrity.addListener(mIntegrityListener);
    }

    @Override
    public void onPause() {
        mConfiguration.removeListener(mConfigurationListener);
        mCoordinator.removeListener(mCoordinatorListener);
        // Master: a recreated screen must not accumulate or receive stale callbacks.
        if (mIntegrityListener != null) {
            PreferenceIntegrity.removeListener(mIntegrityListener);
            mIntegrityListener = null;
        }
        super.onPause();
    }

    private boolean isConfigurationWritable() {
        return mConfiguration != null
                && mConfiguration.getState() == ConfigurationRepository.State.VERIFIED
                && !mConfiguration.isWritePending()
                && !mConfiguration.isFrozen();
    }

    private boolean isCoordinatorIdle() {
        if (mCoordinator == null) {
            return false;
        }
        RunCoordinator.State state = mCoordinator.getSnapshot().getState();
        return state == RunCoordinator.State.IDLE || state == RunCoordinator.State.TERMINAL;
    }

    private boolean areControlsWritable() {
        return isConfigurationWritable() && isCoordinatorIdle()
                && !PreferenceIntegrity.isQuarantined();
    }

    private void renderConfirmedConfiguration() {
        if (!isAdded() || mConfiguration == null) return;
        boolean writable = areControlsWritable();
        // Both unavailable features stay hard-disabled and unchecked in every
        // state. The explicit "boot_disable" action (not the boot_start switch)
        // is the only OFF path and remains available even in ERROR/active runs.
        mBootStartPreference.setEnabled(false);
        mBootStartPreference.setChecked(false);
        mSoftRebootPreference.setEnabled(false);
        mSoftRebootPreference.setChecked(false);
        mDisableModulesPreference.setEnabled(writable);

        RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
        if (confirmed != null) {
            mDisableModulesPreference.setChecked(confirmed.isDisableModules());
        }

        renderStatusSummary();
        boolean error = mConfiguration.getState() == ConfigurationRepository.State.ERROR;
        if (error && mPreferenceScreen.findPreference("configuration_retry") == null) {
            mPreferenceScreen.addPreference(mRetryPreference);
        } else if (!error && mPreferenceScreen.findPreference("configuration_retry") != null) {
            mPreferenceScreen.removePreference(mRetryPreference);
        }
        mRetryPreference.setEnabled(error && isCoordinatorIdle());
    }

    private void renderStatusSummary() {
        switch (mConfiguration.getState()) {
            case SAVING:
                mStatusPreference.setSummary("Saving configuration...");
                break;
            case ERROR:
                mStatusPreference.setSummary("Configuration error: "
                        + mConfiguration.getErrorMessage());
                break;
            case VERIFIED:
                mStatusPreference.setSummary("Configuration verified");
                break;
            default:
                throw new IllegalStateException("unknown configuration state");
        }
        if (mBootChangeError != null) {
            mStatusPreference.setSummary(mStatusPreference.getSummary() + "\n"
                    + BOOT_CHANGE_FAILURE + mBootChangeError);
        }
    }

    /** Surface a rejected OFF component write; never imply an ON state. */
    private void showBootChangeFailure(String detail) {
        mBootChangeError = detail;
        mBootStartPreference.setSummary(BOOT_CHANGE_FAILURE + detail);
        mBootStartPreference.setChecked(false);
        renderStatusSummary();
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    /** Master's tri-state bootstrap component observation via the PR#2 controller seam. */
    private BootComponentState readBootComponentState(Context ctx, ComponentName component) {
        if (ctx == null || component == null) return BootUiPolicy.fromReadbackFailure();
        try {
            return BootUiPolicy.fromPackageManagerState(mBootComponents.read(ctx, component));
        } catch (RuntimeException e) {
            return BootUiPolicy.fromReadbackFailure();
        }
    }

    /** One choke point: the switch is unavailable; the persistent status row stays truthful. */
    private void applyBootPresentation(BootComponentState actual) {
        if (mBootStartPreference == null) return;
        // Hard-disabled and unchecked in every state: this switch can never be
        // presented (or used) as an ON control. The separate boot_disable row is
        // the explicit OFF action and stays available.
        mBootStartPreference.setChecked(false);
        mBootStartPreference.setEnabled(false);
        BootStatusPresentation presentation = BootUiPolicy.presentation(actual);
        String summary;
        switch (presentation) {
            case ON:
                // A forced-on component is a fact to report, never permission.
                summary = "Status unavailable - automatic startup may still be enabled; "
                        + "Start at Boot is unavailable in this build";
                break;
            case OFF_VERIFIED:
                summary = "Off - verified disabled";
                break;
            case UNKNOWN:
            default:
                summary = "Status unavailable - not confirmed disabled";
                if (PreferenceIntegrity.isQuarantined()) {
                    summary += "; preference state is quarantined, automatic startup may " +
                            "still be enabled";
                }
                break;
        }
        mBootStartPreference.setSummary(BOOT_UNAVAILABLE + "\n" + summary);
        if (mBootStatusPreference != null) mBootStatusPreference.setSummary(summary);
    }

    private void renderBootPresentationFromState() {
        Context ctx = getActivity();
        if (ctx == null || !isAdded()) return;
        mMain.post(() -> {
            if (!isAdded() || getActivity() == null) return;
            applyBootPresentation(readBootComponentState(ctx,
                    new ComponentName(ctx, BootReceiver.class)));
        });
    }

    /** Explicit user-initiated OFF; never enables anything and never claims OFF without read-back. */
    private void requestDisableBootLaunch() {
        Context ctx = getActivity();
        if (ctx == null || !isAdded() || !BootUiPolicy.mayRequestDisable()) return;
        ComponentName receiver = new ComponentName(ctx, BootReceiver.class);
        try {
            mBootComponents.set(ctx, receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        } catch (RuntimeException e) {
            Log.e("dfroot", "Could not disable boot component setting", e);
            showBootChangeFailure(describe(e));
            return;
        }
        BootComponentState actual = readBootComponentState(ctx, receiver);
        applyBootPresentation(actual);
        if (actual == BootComponentState.DISABLED) {
            mBootChangeError = null;
            renderStatusSummary();
        } else {
            showBootChangeFailure("component remained in state " + actual);
        }
        final BootComponentState observed = actual;
        mMain.post(() -> {
            if (!isAdded() || getActivity() == null) return;
            Toast.makeText(ctx,
                    observed == BootComponentState.DISABLED
                            ? "Automatic startup disabled (verified)"
                            : "Could not confirm automatic startup is disabled; " +
                              "status unavailable - not confirmed disabled",
                    Toast.LENGTH_LONG).show();
        });
    }
}
