package df.root;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.util.Log;
import android.widget.Toast;

public class SettingsFragment extends PreferenceFragment {

    private static final String TAG = "dfroot";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        SharedPreferences dePrefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        PreferencePersister persister = new PreferencePersister(dePrefs, (key, detail) -> {
            Log.e(TAG, "Preference write failed for " + key + ": " + detail);
            Toast.makeText(ctx, "Could not save setting - reverting switch",
                    Toast.LENGTH_LONG).show();
        });

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

        SwitchPreference softRebootPref = (SwitchPreference) findPreference("soft_reboot");
        softRebootPref.setPersistent(false);
        softRebootPref.setChecked(dePrefs.getBoolean("soft_reboot", false));
        softRebootPref.setOnPreferenceChangeListener((pref, value) -> {
            // Returning false on commit failure keeps the switch at its old
            // position (the preference is non-persistent), so the UI never
            // claims a setting was saved when the native bootstrap cannot read
            // it back from disk.
            return persister.putBoolean("soft_reboot", (Boolean) value);
        });

        SwitchPreference disableModulesPref = (SwitchPreference) findPreference("disable_modules");
        disableModulesPref.setPersistent(false);
        disableModulesPref.setChecked(dePrefs.getBoolean("disable_modules", false));
        disableModulesPref.setOnPreferenceChangeListener((pref, value) ->
                persister.putBoolean("disable_modules", (Boolean) value));
    }
}
