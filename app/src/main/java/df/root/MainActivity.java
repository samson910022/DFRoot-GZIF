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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements IReporter {

    private static final String TAG = "dfroot";

    private Button btnRun;
    private ScrollView outputScroll;
    private TextView outputView;
    private Spinner spinnerSuManager;
    private Context mDeCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mExec = Executors.newSingleThreadExecutor();
    private int mValidSuManagerPos = 0;
    // Set on the main thread in onDestroy(); read from the background executor
    // and from posted UI runnables. Guards against a still-running exploit
    // updating the destroyed Activity's views or showing stray Toasts after a
    // rotation / Activity recreation.
    private volatile boolean mDestroyed = false;

    @Override
    public void report(String msg) {
        // Always log, even after destruction: the logcat record of a run that
        // outlives this Activity is still useful.
        Log.i(TAG, msg.trim());
        if (mDestroyed) return;
        mMain.post(() -> {
            // Re-check on the main thread: onDestroy() may have run between the
            // post() and this runnable being dispatched.
            if (mDestroyed) return;
            outputView.append(msg);
            outputScroll.post(() -> {
                if (!mDestroyed) outputScroll.fullScroll(View.FOCUS_DOWN);
            });
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_main);

        getActionBar().setSubtitle("@diabl0w github/xda");

        btnRun = findViewById(R.id.btnRun);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);

        if (!ExploitRunner.hasBundledLoader(mDeCtx)) {
            report("WARNING: no bundled Samsung-patched ksud loader found in this APK.\n" +
                   "Reinstall DFRoot GZIF before trying to install root.\n");
        }

        PackageManager pm = getPackageManager();
        List<SuManagerEntry> entries = new ArrayList<>();
        for (String pkg : ExploitRunner.KSU_MANAGER_PACKAGES) {
            ApplicationInfo ai;
            try {
                ai = pm.getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // this manager is not installed
            }
            CharSequence label = pm.getApplicationLabel(ai);
            if (ExploitRunner.MANAGER_CLASSIC.equals(pkg)) {
                label = label + " (classic; Next recommended)";
            }
            entries.add(new SuManagerEntry(pkg, label, pm.getApplicationIcon(ai)));
        }
        entries.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        entries.add(0, new SuManagerEntry(null, "Select a SU Manager", null));

        spinnerSuManager.setAdapter(new SuManagerAdapter(this, entries));

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
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
        if (!savedFound && saved != null) { // Saved manager unavailable: uninstalled or no longer in the supported list
            // Java/native contract: bootstrap.c reads DE shared_prefs/dfroot.xml
            // directly during the exploit, so use commit() (synchronous disk write)
            // rather than apply() for every write of this key.
            prefs.edit().remove(ExploitRunner.PREF_SU_MANAGER).commit();
        }

        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                SuManagerEntry e = entries.get(pos);
                if (e.packageName == null) return;
                mValidSuManagerPos = pos;
                // commit(), not apply(): the selected manager is read by native
                // bootstrap.c straight from the DE prefs XML during a run, and
                // apply()'s asynchronous disk flush can expose a stale value.
                prefs.edit().putString(ExploitRunner.PREF_SU_MANAGER, e.packageName).commit();
                updateRunButton();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateRunButton();

        btnRun.setOnClickListener(v -> {
            if (mDestroyed) return;
            btnRun.setEnabled(false);
            outputView.setText("");
            mExec.execute(this::runExploit);
        });
    }

    @Override
    protected void onDestroy() {
        // Mark dead before shutting the executor down so any already-queued
        // report()/Toast/UI posts are dropped rather than touching stale views.
        mDestroyed = true;
        mExec.shutdownNow();
        super.onDestroy();
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

    private void updateRunButton() {
        btnRun.setEnabled(mValidSuManagerPos >= 1
                && ExploitRunner.hasBundledLoader(mDeCtx)
                && !new File("/dev/df").exists());
    }

    private void runExploit() {
        // Short-lived partial wake lock for the manual path, mirroring BootReceiver.
        // The timed acquire bounds a hung native run so the device can still sleep;
        // if PowerManager is unavailable we simply run without a wake lock.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = null;
        if (pm == null) {
            Log.w(TAG, "PowerManager unavailable; running without wake lock");
        }
        try {
            // Acquire inside try so an exception here still reaches finally:
            // the lock is released and updateRunButton() is always posted,
            // otherwise the Run button would stay disabled forever.
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:manual");
                wl.acquire(ExploitRunner.WAKELOCK_TIMEOUT_MS);
            }
            int rc = ExploitRunner.run(mDeCtx, this);
            String msg = resultMessage(rc);
            mMain.post(() -> {
                if (mDestroyed) return; // don't pop a Toast for a dead Activity
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
            mMain.post(() -> {
                if (mDestroyed) return; // btnRun belongs to a destroyed view hierarchy
                updateRunButton();
            });
        }
    }

    /** User-facing text for every ExploitRunner.RESULT_* code; keep in sync with its contract. */
    private static String resultMessage(int rc) {
        switch (rc) {
            case ExploitRunner.RESULT_OK:                  return "DFRoot: SUCCESS";
            case ExploitRunner.RESULT_KSUD_ERROR:          return "DFRoot: Error - ksud nonzero exit";
            case ExploitRunner.RESULT_PRECHECK_FAILED:     return "DFRoot: Error - precheck failed (see output)";
            case ExploitRunner.RESULT_PATCH_FAILED:        return "DFRoot: Error - failed to patch files";
            case ExploitRunner.RESULT_SOFT_REBOOT_PENDING: return "DFRoot: root active — device may soft-reboot shortly";
            case ExploitRunner.RESULT_NATIVE_SETUP_FAILED: return "DFRoot: Error - native setup/timeout (see logcat & dmesg)";
            default:                                       return "DFRoot: Error - unknown result (" + rc + ")";
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
