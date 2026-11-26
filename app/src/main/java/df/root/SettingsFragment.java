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

import android.app.AlertDialog;
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
    private AlertDialog mConfirmationDialog;
    private String mBootChangeError;
    private long mPendingSoftRebootGeneration = -1;

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
        mBootStartPreference.setOnPreferenceChangeListener((pref, value) -> {
            boolean enabling = (Boolean) value;
            // Enabling still requires verified configuration and an idle
            // coordinator. Disabling never does: the owner must always be able
            // to stop future unattended attempts, even mid-operation or in ERROR.
            if (enabling && (!areControlsWritable()
                    || PreferenceIntegrity.isQuarantined()
                    || !BootUiPolicy.mayChangeTo(true, readBootComponentState(ctx, bootReceiver),
                            PreferenceIntegrity.isQuarantined()))) {
                renderConfirmedConfiguration();
                applyBootPresentation(readBootComponentState(ctx, bootReceiver));
                return false;
            }
            int desired = enabling ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                   : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            int actual;
            try {
                // No coordinator call on this path: an active operation keeps its
                // admission, lease and finish semantics untouched. The OFF write
                // is a PackageManager operation OUTSIDE the coordinator monitor;
                // it is not atomic with admission or with the final authorization
                // re-read, so a not-yet-started action may still observe the old
                // value at that re-read. OFF always suppresses FUTURE unattended
                // attempts that observe it; already-started work is never cancelled.
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
            // Master: the observed state, never the requested value, is truth.
            applyBootPresentation(readBootComponentState(ctx, bootReceiver));
            return BootUiPolicy.acceptFrameworkChange(enabling,
                    BootComponentState.fromPackageManagerState(actual));
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
            if (!areControlsWritable() || PreferenceIntegrity.isQuarantined()) return false;
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
        return isConfigurationWritable() && isCoordinatorIdle()
                && !PreferenceIntegrity.isQuarantined();
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

    /** Master's tri-state bootstrap component observation via the PR#2 controller seam. */
    private BootComponentState readBootComponentState(Context ctx, ComponentName component) {
        if (ctx == null || component == null) return BootUiPolicy.fromReadbackFailure();
        try {
            return BootUiPolicy.fromPackageManagerState(mBootComponents.read(ctx, component));
        } catch (RuntimeException e) {
            return BootUiPolicy.fromReadbackFailure();
        }
    }

    /** One choke point for thumb position, switch summary and persistent status row. */
    private void applyBootPresentation(BootComponentState actual) {
        if (mBootStartPreference == null) return;
        mBootStartPreference.setChecked(BootUiPolicy.desiredChecked(actual)); // position only
        mBootStartPreference.setEnabled(true); // OFF must always be reachable
        BootStatusPresentation presentation = BootUiPolicy.presentation(actual);
        String summary;
        switch (presentation) {
            case ON:
                summary = "On - automatic startup enable setting is present";
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
        mBootStartPreference.setSummary(summary);
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
        }
        BootComponentState actual = readBootComponentState(ctx, receiver);
        applyBootPresentation(actual);
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
