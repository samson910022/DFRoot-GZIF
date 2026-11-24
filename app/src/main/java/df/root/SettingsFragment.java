// Modified 2026-11-24 (DFRoot GZIF): wire settings to confirmed configuration and require durable modal consent. See NOTICE.
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
    private ConfigurationRepository mConfiguration;
    private SwitchPreference mSoftRebootPreference;
    private SwitchPreference mDisableModulesPreference;
    private Preference mStatusPreference;
    private PreferenceScreen mPreferenceScreen;
    private Preference mRetryPreference;
    private AlertDialog mConfirmationDialog;
    private long mPendingSoftRebootGeneration = -1;

    private final ConfigurationRepository.Listener mConfigurationListener =
            this::renderConfirmedConfiguration;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        mConfiguration = ConfigurationRepository.get(ctx);

        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);
        SwitchPreference bootPref = (SwitchPreference) findPreference("boot_start");
        bootPref.setPersistent(false);
        int state = ctx.getPackageManager().getComponentEnabledSetting(bootReceiver);
        bootPref.setChecked(state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        bootPref.setOnPreferenceChangeListener((pref, value) -> {
            ctx.getPackageManager().setComponentEnabledSetting(bootReceiver,
                    (Boolean) value ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return true;
        });

        mStatusPreference = findPreference("configuration_status");
        mPreferenceScreen = getPreferenceScreen();
        mRetryPreference = findPreference("configuration_retry");
        mPreferenceScreen.removePreference(mRetryPreference);
        mRetryPreference.setOnPreferenceClickListener(preference -> {
            if (!mConfiguration.reload()) {
                renderConfirmedConfiguration();
            }
            return true;
        });
        mSoftRebootPreference = (SwitchPreference) findPreference(
                ConfigurationKeys.PREF_SOFT_REBOOT);
        mSoftRebootPreference.setPersistent(false);
        mSoftRebootPreference.setOnPreferenceChangeListener((pref, value) -> {
            if (!isConfigurationWritable()) return false;
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
            if (!isConfigurationWritable()) return false;
            mConfiguration.setDisableModules((Boolean) value);
            return false;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        mConfiguration.addListener(mConfigurationListener);
        renderConfirmedConfiguration();
    }

    @Override
    public void onPause() {
        mConfiguration.removeListener(mConfigurationListener);
        dismissConfirmation();
        mPendingSoftRebootGeneration = -1;
        super.onPause();
    }

    private boolean isConfigurationWritable() {
        return mConfiguration != null
                && mConfiguration.getState() == ConfigurationRepository.State.VERIFIED
                && !mConfiguration.isWritePending()
                && !mConfiguration.isFrozen();
    }

    private void renderConfirmedConfiguration() {
        if (!isAdded() || mConfiguration == null) return;
        boolean writable = isConfigurationWritable();
        mSoftRebootPreference.setEnabled(writable);
        mDisableModulesPreference.setEnabled(writable);

        RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
        if (confirmed != null) {
            mSoftRebootPreference.setChecked(confirmed.isSoftReboot());
            mDisableModulesPreference.setChecked(confirmed.isDisableModules());
        }

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
        boolean error = mConfiguration.getState() == ConfigurationRepository.State.ERROR;
        if (error && mPreferenceScreen.findPreference("configuration_retry") == null) {
            mPreferenceScreen.addPreference(mRetryPreference);
        } else if (!error && mPreferenceScreen.findPreference("configuration_retry") != null) {
            mPreferenceScreen.removePreference(mRetryPreference);
        }
        mRetryPreference.setEnabled(error);
    }

    private void showSoftRebootAcknowledgement() {
        dismissConfirmation();
        Context ctx = getActivity();
        RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
        if (ctx == null || confirmed == null || !isConfigurationWritable()) {
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
                    if (isConfigurationWritable() && current != null
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
    }
}
