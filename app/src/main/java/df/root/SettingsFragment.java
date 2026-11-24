// Modified 2026-11-25 (DFRoot GZIF round-3 PR #3 phase 3): preference-integrity
// gating of Start on Boot, marker-failure boot gate, failure toast. See NOTICE.
package df.root;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
        Context deCtx = ctx.createDeviceProtectedStorageContext();
        PreferenceIntegrity.init(deCtx);
        SharedPreferences dePrefs = deCtx.getSharedPreferences(
                ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        PreferencePersister persister = new PreferencePersister(dePrefs, (key, detail) -> {
            Log.e(TAG, "Preference write failed for " + key + ": " + detail);
            // PreferencePersister marks the key ambiguous after this callback;
            // post so the gate state is settled before we refresh the UI.
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(ctx,
                        "Could not save setting - reverting switch; LAUNCH ROOT is blocked " +
                        "until the same setting saves successfully", Toast.LENGTH_LONG).show();
                disableBootReceiverForFailedMarker(ctx);
            });
        });

        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);
        SwitchPreference bootPref = (SwitchPreference) findPreference("boot_start");
        bootPref.setPersistent(false);
        int state = ctx.getPackageManager().getComponentEnabledSetting(bootReceiver);
        bootPref.setChecked(state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        bootPref.setEnabled(!PreferenceIntegrity.isQuarantined());
        bootPref.setOnPreferenceChangeListener((pref, value) -> {
            if (Boolean.TRUE.equals(value) && PreferenceIntegrity.isQuarantined()) {
                // Never arm a boot-time launch while memory and the on-disk
                // preferences may disagree.
                Toast.makeText(ctx,
                        "Start on Boot is blocked while preference state is uncertain",
                        Toast.LENGTH_LONG).show();
                bootPref.setChecked(false);
                return false;
            }
            ctx.getPackageManager().setComponentEnabledSetting(bootReceiver,
                    (Boolean) value ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return true;
        });

        // A later successful save of the same key clears the quarantine.
        PreferenceIntegrity.addListener(() -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                boolean quarantined = PreferenceIntegrity.isQuarantined();
                bootPref.setEnabled(!quarantined);
                if (quarantined && bootPref.isChecked()) bootPref.setChecked(false);
                disableBootReceiverForFailedMarker(ctx);
            });
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

    /** If the gate could not be persisted, disable BootReceiver fail-closed. */
    private static void disableBootReceiverForFailedMarker(Context ctx) {
        if (!PreferenceIntegrity.isQuarantined()
                || PreferenceIntegrity.durableMarkerActive()) return;
        ComponentName receiver = new ComponentName(ctx, BootReceiver.class);
        if (ctx.getPackageManager().getComponentEnabledSetting(receiver)
                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            ctx.getPackageManager().setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }
}
