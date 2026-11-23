package df.root;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.widget.Toast;

public class SettingsFragment extends PreferenceFragment {

    /** The hard-reboot warning is shown at most once per visit to Settings. */
    private boolean mSoftRebootWarningShown;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        SharedPreferences dePrefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);

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

        SwitchPreference softRebootPref = (SwitchPreference) findPreference(ExploitRunner.PREF_SOFT_REBOOT);
        softRebootPref.setPersistent(false);
        softRebootPref.setChecked(dePrefs.getBoolean(ExploitRunner.PREF_SOFT_REBOOT, false));
        softRebootPref.setOnPreferenceChangeListener((pref, value) -> {
            boolean enabled = (Boolean) value;
            if (enabled && !mSoftRebootWarningShown) {
                mSoftRebootWarningShown = true;
                Toast.makeText(ctx, "Warning: may trigger a full reboot on this firmware",
                        Toast.LENGTH_LONG).show();
            }
            // Java/native contract: bootstrap.c reads the DE shared_prefs/dfroot.xml
            // file directly with open()/read() during the exploit. SharedPreferences
            // apply() only writes to memory first and flushes to disk asynchronously,
            // so native could read the previous value. commit() makes the on-disk
            // file authoritative before the user can start a run.
            dePrefs.edit().putBoolean(ExploitRunner.PREF_SOFT_REBOOT, enabled).commit();
            return true; // never block the toggle: the user explicitly accepts the risk
        });

        SwitchPreference disableModulesPref = (SwitchPreference) findPreference("disable_modules");
        disableModulesPref.setPersistent(false);
        disableModulesPref.setChecked(dePrefs.getBoolean("disable_modules", false));
        disableModulesPref.setOnPreferenceChangeListener((pref, value) -> {
            // Same Java/native commit() contract as soft_reboot above: native
            // bootstrap.c reads this flag straight from the DE prefs XML.
            dePrefs.edit().putBoolean("disable_modules", (Boolean) value).commit();
            return true;
        });
    }
}
