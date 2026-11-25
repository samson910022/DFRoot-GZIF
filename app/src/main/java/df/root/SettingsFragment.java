// Modified 2026-11-24 (DFRoot GZIF): wire settings to confirmed configuration and require durable modal consent. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): gate boot and dangerous settings on writable configuration plus an idle coordinator. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): keep Start at Boot OFF reachable in every state and surface component-setting failures. See NOTICE.
package df.root;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

public class SettingsFragment extends PreferenceFragment {
    private static final String BOOT_SUMMARY = "Automatically run DFRoot on device boot";
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
    private SwitchPreference mSoftRebootPreference;
    private SwitchPreference mDisableModulesPreference;
    private Preference mStatusPreference;
    private PreferenceScreen mPreferenceScreen;
    private Preference mRetryPreference;
    private AlertDialog mConfirmationDialog;
    private String mBootChangeError;
    private long mPendingSoftRebootGeneration = -1;

    private final ConfigurationRepository.Listener mConfigurationListener =
            this::renderConfirmedConfiguration;
    private final RunCoordinator.Listener mCoordinatorListener = snapshot -> renderConfirmedConfiguration();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        mConfiguration = ConfigurationRepository.get(ctx);
        mCoordinator = RunCoordinator.get(ctx);

        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);
        mBootStartPreference = (SwitchPreference) findPreference("boot_start");
        mBootStartPreference.setPersistent(false);
        int state = mBootComponents.read(ctx, bootReceiver);
        mBootStartPreference.setChecked(state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        mBootStartPreference.setOnPreferenceChangeListener((pref, value) -> {
            boolean enabling = (Boolean) value;
            // Enabling still requires verified configuration and an idle
            // coordinator. Disabling never does: the owner must always be able
            // to stop future unattended attempts, even mid-operation or in ERROR.
            if (enabling && !areControlsWritable()) {
                renderConfirmedConfiguration();
                return false;
            }
            int desired = enabling ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                   : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            int actual;
            try {
                // No coordinator call on this path: an active operation keeps its
                // admission, lease and finish semantics untouched.
                actual = mBootComponents.set(ctx, bootReceiver, desired);
            } catch (RuntimeException e) {
                showBootChangeFailure(ctx, bootReceiver, describe(e));
                return false;
            }
            if (actual != desired) {
                showBootChangeFailure(ctx, bootReceiver,
                        "component remained in state " + actual);
                return false;
            }
            mBootChangeError = null;
            renderStatusSummary();
            mBootStartPreference.setSummary(BOOT_SUMMARY);
            return true;
        });

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
        mSoftRebootPreference.setOnPreferenceChangeListener((pref, value) -> {
            if (!areControlsWritable()) return false;
            if ((Boolean) value) {
                showSoftRebootAcknowledgement();
            } else {
                mConfiguration.setSoftReboot(false);
            }
            // The repository observer, not the click, reflects durable state.
            return false;
        });

        mDisableModulesPreference = (SwitchPreference) findPreference(
                ConfigurationKeys.PREF_DISABLE_MODULES);
        mDisableModulesPreference.setPersistent(false);
        mDisableModulesPreference.setOnPreferenceChangeListener((pref, value) -> {
            if (!areControlsWritable()) return false;
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
    }

    @Override
    public void onPause() {
        mConfiguration.removeListener(mConfigurationListener);
        mCoordinator.removeListener(mCoordinatorListener);
        dismissConfirmation();
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
        return isConfigurationWritable() && isCoordinatorIdle();
    }

    private void renderConfirmedConfiguration() {
        if (!isAdded() || mConfiguration == null) return;
        if (!isCoordinatorIdle()) {
            dismissConfirmation();
        }
        boolean writable = areControlsWritable();
        // The switch stays reachable so OFF is available during ERROR and while
        // STARTING/RUNNING/INDETERMINATE. Only the ON direction is gated in the
        // change listener; dangerous switches keep the full writable gate.
        mBootStartPreference.setEnabled(true);
        mSoftRebootPreference.setEnabled(writable);
        mDisableModulesPreference.setEnabled(writable);

        RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
        if (confirmed != null) {
            mSoftRebootPreference.setChecked(confirmed.isSoftReboot());
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

    /** Surface a rejected component write and restore the state actually applied. */
    private void showBootChangeFailure(Context ctx, ComponentName bootReceiver, String detail) {
        mBootChangeError = detail;
        mBootStartPreference.setSummary(BOOT_CHANGE_FAILURE + detail);
        renderStatusSummary();
        try {
            int actual = mBootComponents.read(ctx, bootReceiver);
            mBootStartPreference.setChecked(actual == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        } catch (RuntimeException ignored) {
            // The listener rejected the attempted change, so the previously shown
            // state is still the best known applied state.
        }
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private void showSoftRebootAcknowledgement() {
        dismissConfirmation();
        Context ctx = getActivity();
        RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
        if (ctx == null || confirmed == null || !areControlsWritable()) {
            renderConfirmedConfiguration();
            return;
        }
        mPendingSoftRebootGeneration = confirmed.getGeneration();
        mConfirmationDialog = new AlertDialog.Builder(ctx)
                .setTitle("Enable Auto Soft Reboot?")
                .setMessage("EXPERIMENTAL: on this firmware a soft reboot has caused a full hard reboot. "
                        + "This setting applies to manual runs only. Start at Boot will not run "
                        + "while it is enabled, pending separate hardware validation.")
                .setCancelable(false)
                .setNegativeButton("Cancel", (dialog, which) -> {
                    mPendingSoftRebootGeneration = -1;
                    dialog.dismiss();
                })
                .setPositiveButton("Enable anyway", (dialog, which) -> {
                    dialog.dismiss();
                    long expectedGeneration = mPendingSoftRebootGeneration;
                    mPendingSoftRebootGeneration = -1;
                    RunConfig current = mConfiguration.getConfirmedSnapshot();
                    if (areControlsWritable() && current != null
                            && current.getGeneration() == expectedGeneration) {
                        mConfiguration.setSoftReboot(true);
                    } else {
                        renderConfirmedConfiguration();
                    }
                })
                .show();
    }

    private void dismissConfirmation() {
        if (mConfirmationDialog != null) {
            mConfirmationDialog.dismiss();
            mConfirmationDialog = null;
        }
        mPendingSoftRebootGeneration = -1;
    }
}
