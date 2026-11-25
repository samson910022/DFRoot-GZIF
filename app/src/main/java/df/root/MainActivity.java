// Modified 2026-11-25 (DFRoot GZIF round-4 PR #3 phase 3): durable gate owns
// boot-disable; the activity listener is UI-only and lifecycle-scoped. See NOTICE.
package df.root;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements IReporter {

    private static final String TAG = "dfroot";

    private Button btnRun;
    private ScrollView outputScroll;
    private TextView outputView;
    private Spinner spinnerSuManager;
    private Context mDeCtx;
    private Context mAppContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private int mValidSuManagerPos = 0;
    private PreferencePersister mPrefsPersister;
    // Held in a field so onDestroy() can remove it: a static/anonymous listener
    // would retain this Activity across every screen recreation.
    private PreferenceIntegrity.Listener mIntegrityListener;
    // A programmatic setSelection() fires onItemSelected again; suppress the
    // write (and any further revert) while restoring the previous choice.
    private boolean mRestoringSpinnerSelection;
    // Keeps the CPU awake across the up-to-85s overall status wait in
    // ExploitRunner.run() (70 s budget + bounded grace); the timeout bounds it
    // in case the native binary hangs.
    private PowerManager.WakeLock mWakeLock;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            if (isFinishing() || isDestroyed() || outputView == null || outputScroll == null) return;
            outputView.append(msg);
            outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAppContext = getApplicationContext();
        mDeCtx = createDeviceProtectedStorageContext();
        PreferenceIntegrity.init(mDeCtx);
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:manual");
        setContentView(R.layout.activity_main);

        getActionBar().setSubtitle("@diabl0w github/xda");

        btnRun = findViewById(R.id.btnRun);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);

        PackageManager pm = getPackageManager();
        List<SuManagerEntry> entries = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (!ExploitRunner.hasKsud(ai)) continue;
            entries.add(new SuManagerEntry(ai.packageName, pm.getApplicationLabel(ai), pm.getApplicationIcon(ai)));
        }
        entries.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        entries.add(0, new SuManagerEntry(null, "Select a SU Manager", null));

        spinnerSuManager.setAdapter(new SuManagerAdapter(this, entries));

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        final Context app = mAppContext;
        mPrefsPersister = new PreferencePersister(prefs, (key, detail) -> {
            Log.e(TAG, "Preference write failed for " + key + ": " + detail);
            // The durable gate already disabled boot at the failure boundary;
            // this callback is only a user-facing revert/toast.
            mMain.post(() -> {
                if (isFinishing() || isDestroyed() || outputView == null) return;
                Toast.makeText(app,
                        "Could not save selection - reverting; LAUNCH ROOT is blocked " +
                        "until the same setting saves successfully", Toast.LENGTH_LONG).show();
            });
        });
        String saved = prefs.getString(ExploitRunner.PREF_SU_MANAGER, null);
        boolean savedFound = false;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).packageName.equals(saved)) {
                spinnerSuManager.setSelection(i);
                mValidSuManagerPos = i;
                savedFound = true;
                break;
            }
        }
        if (!savedFound && saved != null) { // Saved manager was uninstalled
            // commit(): the native bootstrap reads the prefs XML from disk.
            if (!mPrefsPersister.remove(ExploitRunner.PREF_SU_MANAGER)) {
                // commit() failed: the stale package name MAY REMAIN ON DISK,
                // and BootReceiver (if enabled) could still read it through
                // ExploitRunner.run() on a later boot. The UI avoids offering
                // a selection the bootstrap would act on, but this is a
                // residual risk (see CHANGELOG.md / PORTING-GZIF.md).
                revertSpinnerSelection(0);
            }
        }

        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (mRestoringSpinnerSelection) return; // programmatic revert
                SuManagerEntry e = entries.get(pos);
                if (e.packageName == null) return;
                int previous = mValidSuManagerPos;
                mValidSuManagerPos = pos;
                // commit(): the native bootstrap reads the prefs XML from disk.
                if (!mPrefsPersister.putString(ExploitRunner.PREF_SU_MANAGER, e.packageName)) {
                    // Guard prevents this setSelection() from writing the old
                    // value back or triggering another revert.
                    revertSpinnerSelection(previous);
                }
                updateRunButton();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // UI-only: reflect the latch on the next frame. Boot-disable is a
        // durable operation owned by PreferenceIntegrity at the failure boundary.
        mIntegrityListener = () -> mMain.post(() -> {
            if (isFinishing() || isDestroyed() || btnRun == null) return;
            updateRunButton();
        });
        PreferenceIntegrity.addListener(mIntegrityListener);
        updateRunButton();

        btnRun.setOnClickListener(v -> {
            btnRun.setEnabled(false);
            outputView.setText("");
            mExec.execute(this::runExploit);
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        // No callback may outlive the screen and retain it after recreation.
        if (mIntegrityListener != null) {
            PreferenceIntegrity.removeListener(mIntegrityListener);
            mIntegrityListener = null;
        }
        super.onDestroy();
    }

    private void updateRunButton() {
        if (btnRun == null || isFinishing() || isDestroyed()) return;
        btnRun.setEnabled(mValidSuManagerPos >= 1
                && !new File("/dev/df").exists()
                && !PreferenceIntegrity.isQuarantined());
    }

    /** Revert the spinner without re-persisting the old selection. */
    private void revertSpinnerSelection(int pos) {
        mRestoringSpinnerSelection = true;
        spinnerSuManager.setSelection(pos, false);
        // AdapterView normally dispatches onItemSelected synchronously, but a
        // queued callback must also see the guard: our post is FIFO behind it.
        mMain.post(() -> mRestoringSpinnerSelection = false);
        mValidSuManagerPos = pos;
        updateRunButton();
    }

    private void runExploit() {
        // Bound the lock so a hung native binary cannot keep the device awake
        // forever; 120s comfortably covers the 85s overall status budget.
        mWakeLock.acquire(120000);
        try {
            int rc = ExploitRunner.run(mDeCtx, this);
            /* rc comes from either a Java-side early gate or the single
             * atomically published final outcome. StatusRc.name() never claims
             * root was verified or a reboot was confirmed: only an on-device
             * oracle can prove either. */
            String msg = StatusRc.name(rc);
            mMain.post(() -> {
                if (mAppContext != null && !isFinishing() && !isDestroyed()) {
                    Toast.makeText(mAppContext, msg, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            if (mWakeLock.isHeld()) mWakeLock.release();
            mMain.post(this::updateRunButton);
        }
    }

    private static class SuManagerEntry {
        final String packageName;
        final CharSequence label;
        final Drawable icon;

        SuManagerEntry(String pkg, CharSequence label, Drawable icon) {
            this.packageName = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private static class SuManagerAdapter extends ArrayAdapter<SuManagerEntry> {
        SuManagerAdapter(Context ctx, List<SuManagerEntry> items) {
            super(ctx, R.layout.item_su_manager, items);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.FALSE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager_closed, parent, false);
            v.setTag(Boolean.FALSE);
            return bindClosedView(pos, v);
        }

        @Override
        public View getDropDownView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.TRUE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager, parent, false);
            v.setTag(Boolean.TRUE);
            return bindView(pos, v);
        }

        @Override
        public boolean isEnabled(int pos) {
            return getItem(pos).packageName != null;
        }

        private View bindClosedView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            ImageView icon = v.findViewById(R.id.iconApp);
            TextView label = v.findViewById(R.id.labelApp);
            if (e.packageName == null) {
                icon.setVisibility(View.GONE);
                label.setVisibility(View.VISIBLE);
                label.setText(e.label);
            } else {
                icon.setVisibility(View.VISIBLE);
                label.setVisibility(View.GONE);
                icon.setImageDrawable(e.icon);
            }
            return v;
        }

        private View bindView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            ((ImageView) v.findViewById(R.id.iconApp)).setImageDrawable(e.icon);
            ((TextView)  v.findViewById(R.id.labelApp)).setText(e.label);
            return v;
        }
    }
}
