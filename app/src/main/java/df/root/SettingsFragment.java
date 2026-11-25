// Modified 2026-11-25 (DFRoot GZIF round-4 PR #3 phase 3): actual component
// state is authoritative, quarantine disables via the durable shared gate, and
// the integrity listener is registered/unregistered with the fragment lifecycle.
// See NOTICE.
// Round-5: an unreadable/DEFAULT component state is UNKNOWN (never verified OFF),
// framework writes are rejected on UNKNOWN, and a one-shot notice explains it.
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

import df.root.BootUiPolicy.BootComponentState;

public class SettingsFragment extends PreferenceFragment {

    private static final String TAG = "dfroot";

    private Context mAppContext;
    private Context mDeCtx;
    private ComponentName mBootReceiver;
    private SharedPreferences mDePrefs;
    private PreferencePersister mPersister;
    private SwitchPreference mBootPref;
    private SwitchPreference mSoftRebootPref;
    private SwitchPreference mDisableModulesPref;
    private PreferenceIntegrity.Listener mIntegrityListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    // Last values that actually committed. After a failed commit the in-memory
    // SharedPreferences map may disagree with the XML the native bootstrap
    // reads, so the switches must never be re-read from that map on recovery.
    private boolean mSoftRebootCommitted;
    private boolean mDisableModulesCommitted;
    // One notice per screen: an unverifiable boot state is never claimed as OFF.
    private boolean mUnknownBootStateToastShown;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        mAppContext = ctx.getApplicationContext(); // never retain an Activity in a callback
        mDeCtx = ctx.createDeviceProtectedStorageContext();
        PreferenceIntegrity.init(ctx); // wires the production gate from the application context
        mDePrefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        mPersister = new PreferencePersister(mDePrefs, (key, detail) -> {
            Log.e(TAG, "Preference write failed for " + key + ": " + detail);
            mMain.post(() -> {
                if (!isAdded() || getActivity() == null) return;
                Toast.makeText(mAppContext,
                        "Could not save setting - reverting switch; LAUNCH ROOT is blocked " +
                        "until the same setting saves successfully", Toast.LENGTH_LONG).show();
                syncUi(); // UI-only read-back; boot was disabled at the failure boundary
            });
        });

        mBootReceiver = new ComponentName(mAppContext, BootReceiver.class);
        mBootPref = (SwitchPreference) findPreference("boot_start");
        mBootPref.setPersistent(false);

        mSoftRebootPref = (SwitchPreference) findPreference("soft_reboot");
        mSoftRebootPref.setPersistent(false);
        mSoftRebootCommitted = mDePrefs.getBoolean(ExploitRunner.PREF_SOFT_REBOOT, false);
        mDisableModulesPref = (SwitchPreference) findPreference("disable_modules");
        mDisableModulesPref.setPersistent(false);
        mDisableModulesCommitted = mDePrefs.getBoolean("disable_modules", false);

        mBootPref.setOnPreferenceChangeListener((pref, value) -> {
            boolean turningOn = Boolean.TRUE.equals(value);
            BootComponentState before = readBootComponentState();
            if (!BootUiPolicy.mayChangeTo(turningOn, before,
                    PreferenceIntegrity.isQuarantined())) {
                // Never arm a boot-time launch while memory and the on-disk
                // preferences may disagree, or while the component state could
                // not be verified. Disable through the shared gate and re-read
                // the real state: an unverifiable readback is UNKNOWN, not OFF.
                Toast.makeText(mAppContext,
                        "Start on Boot is blocked while preference state is uncertain",
                        Toast.LENGTH_LONG).show();
                PreferenceIntegrity.disableBootLaunchNow();
                mBootPref.setChecked(BootUiPolicy.desiredChecked(readBootComponentState()));
                return false; // framework must not re-apply the rejected value
            }
            // A user-initiated OFF request is always attempted, even if the
            // previous state was UNKNOWN. Nothing here ever auto-enables boot.
            try {
                mAppContext.getPackageManager().setComponentEnabledSetting(mBootReceiver,
                        turningOn ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                  : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (RuntimeException e) {
                // Rejected change: fail closed and show the real state instead
                // of letting the framework mark the switch as requested.
                mBootPref.setChecked(BootUiPolicy.desiredChecked(readBootComponentState()));
                return false;
            }
            // The observed component state, not the requested value, is truth.
            BootComponentState actual = readBootComponentState();
            mBootPref.setChecked(BootUiPolicy.desiredChecked(actual));
            if (actual == BootComponentState.UNKNOWN) showUnknownBootStateToastOnce();
            // Returning true makes TwoStatePreference setChecked(newValue)
            // again, which could show OFF while the receiver stays ENABLED.
            return BootUiPolicy.acceptFrameworkChange(turningOn, actual);
        });

        mSoftRebootPref.setOnPreferenceChangeListener((pref, value) -> {
            boolean turningOn = Boolean.TRUE.equals(value);
            // Auto Soft Reboot stays off by default and unvalidated; in
            // particular it cannot be switched on while state is uncertain.
            if (!BootUiPolicy.mayChangeTo(turningOn, PreferenceIntegrity.isQuarantined())) {
                Toast.makeText(mAppContext,
                        "Auto Soft Reboot cannot be turned on while preference state is uncertain",
                        Toast.LENGTH_LONG).show();
                mSoftRebootPref.setChecked(mSoftRebootCommitted);
                return false;
            }
            boolean ok = mPersister.putBoolean(ExploitRunner.PREF_SOFT_REBOOT, turningOn);
            if (ok) mSoftRebootCommitted = turningOn;
            else mMain.post(this::syncUi);
            return ok; // false keeps the switch at its old position on failure
        });

        mDisableModulesPref.setOnPreferenceChangeListener((pref, value) -> {
            boolean enabled = Boolean.TRUE.equals(value);
            boolean ok = mPersister.putBoolean("disable_modules", enabled);
            if (ok) mDisableModulesCommitted = enabled;
            else mMain.post(this::syncUi);
            return ok;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mIntegrityListener == null) mIntegrityListener = () -> mMain.post(this::syncUi);
        PreferenceIntegrity.addListener(mIntegrityListener); // identity de-duplicated
        syncUi(); // EVERY resume re-reads the real component and pref state
    }

    @Override
    public void onPause() {
        // A recreated screen must not accumulate callbacks or receive any after
        // it leaves the foreground.
        if (mIntegrityListener != null) {
            PreferenceIntegrity.removeListener(mIntegrityListener);
            mIntegrityListener = null;
        }
        super.onPause();
    }

    /** UI-only synchronization; never auto-enables a feature on recovery. */
    private void syncUi() {
        syncUi(true);
    }

    private void syncUi(boolean applyGate) {
        if (!isAdded() || getActivity() == null || mBootPref == null) return;
        boolean quarantined = PreferenceIntegrity.isQuarantined();
        if (applyGate && BootUiPolicy.disableOnQuarantine(quarantined,
                PreferenceIntegrity.durableMarkerActive())) {
            // Shared gate, not a marker-health-dependent UI callback: even a
            // healthy marker cannot make an uncertain commit safe. The gate's
            // PackageManager call is off the UI thread; the one-shot callback
            // re-reads the real state once it has completed (no loop).
            PreferenceIntegrity.disableBootLaunchFromUi(
                    () -> mMain.post(() -> syncUi(false)));
            return;
        }
        BootComponentState actual = readBootComponentState(); // read AFTER any gate action
        mBootPref.setChecked(BootUiPolicy.desiredChecked(actual));
        mBootPref.setEnabled(true); // the user must always be able to turn boot OFF
        if (actual == BootComponentState.UNKNOWN) showUnknownBootStateToastOnce();
        mSoftRebootPref.setChecked(mSoftRebootCommitted);
        mDisableModulesPref.setChecked(mDisableModulesCommitted);
    }

    /**
     * Observe the real component state. A missing context, DEFAULT or any
     * PackageManager exception is UNKNOWN -- a failed observation is NOT
     * evidence that automatic startup was disabled.
     */
    private BootComponentState readBootComponentState() {
        if (mAppContext == null || mBootReceiver == null) {
            return BootUiPolicy.fromReadbackFailure();
        }
        try {
            return BootUiPolicy.fromPackageManagerState(
                    mAppContext.getPackageManager().getComponentEnabledSetting(mBootReceiver));
        } catch (RuntimeException e) {
            return BootUiPolicy.fromReadbackFailure();
        }
    }

    /** One-shot, lifecycle-safe notice: UNKNOWN is shown off but not verified off. */
    private void showUnknownBootStateToastOnce() {
        if (mUnknownBootStateToastShown) return;
        mUnknownBootStateToastShown = true;
        mMain.post(() -> {
            if (!isAdded() || getActivity() == null) return;
            Toast.makeText(mAppContext,
                    "Start on Boot could not be read and is not confirmed OFF; " +
                    "you can still try to turn it off", Toast.LENGTH_LONG).show();
        });
    }
}
