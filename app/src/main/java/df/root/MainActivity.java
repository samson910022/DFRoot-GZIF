// Modified 2026-11-24 (DFRoot GZIF): observe confirmed configuration, gate controls, and preserve off-thread preflight. See NOTICE.
package df.root;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
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
    private Executor mExec = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "dfroot-ui"));
    private PreflightChecker mPreflightChecker;
    private RunLauncher mRunLauncher;
    private ConfigurationRepository mConfiguration;
    private final ConfigurationRepository.Listener mConfigurationListener =
            this::renderConfirmedConfiguration;
    private ConfigurationRepository.State mReportedConfigurationState;
    private boolean mApplyingConfiguration;
    private long mClearedManagerGeneration = -1;
    private String mPrecheckReason = "";
    private int mPrecheckRequestId;
    private boolean mPreflightReady;
    private long mPreflightGeneration = -1;
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
        mPreflightChecker = this::preflightForSnapshot;
        mRunLauncher = ExploitRunner::runDetailed;
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_main);

        getActionBar().setSubtitle("@diabl0w github/xda");

        btnRun = findViewById(R.id.btnRun);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);

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
        mConfiguration = ConfigurationRepository.get(mDeCtx);

        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (mApplyingConfiguration) return;
                SuManagerEntry e = entries.get(pos);
                if (e.packageName == null) return;
                RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
                if (confirmed != null && java.util.Objects.equals(
                        confirmed.getManagerPackage(), e.packageName)) {
                    return;
                }
                if (!isConfigurationWritable()) {
                    renderConfirmedConfiguration();
                    return;
                }
                mConfiguration.setManagerPackage(e.packageName);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        mConfiguration.addListener(mConfigurationListener);
        renderConfirmedConfiguration();

        btnRun.setOnClickListener(v -> {
            if (mDestroyed || !isRunEligible() || !mPreflightReady) return;
            RunConfig snapshot = mConfiguration.getConfirmedSnapshot();
            if (snapshot == null || snapshot.getGeneration() != mPreflightGeneration) return;
            btnRun.setEnabled(false);
            outputView.setText("");
            mExec.execute(this::runExploit);
        });
    }

    @Override
    protected void onDestroy() {
        if (mConfiguration != null) {
            mConfiguration.removeListener(mConfigurationListener);
        }
        // Mark dead before shutting the executor down so any already-queued
        // report()/Toast/UI posts are dropped rather than touching stale views.
        mDestroyed = true;
        if (mExec instanceof ExecutorService) {
            ((ExecutorService) mExec).shutdownNow();
        }
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

    private boolean isConfigurationWritable() {
        return mConfiguration != null
                && mConfiguration.getState() == ConfigurationRepository.State.VERIFIED
                && !mConfiguration.isWritePending()
                && !mConfiguration.isFrozen();
    }

    private boolean isRunEligible() {
        return !mDestroyed && isConfigurationWritable();
    }

    private void renderConfirmedConfiguration() {
        if (mDestroyed || mConfiguration == null) return;
        ConfigurationRepository.State state = mConfiguration.getState();
        spinnerSuManager.setEnabled(isConfigurationWritable());

        RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
        if (confirmed != null) {
            int position = managerPosition(confirmed.getManagerPackage());
            mApplyingConfiguration = true;
            try {
                spinnerSuManager.setSelection(position);
            } finally {
                mApplyingConfiguration = false;
            }
            if (position == 0 && confirmed.getManagerPackage() != null
                    && confirmed.getGeneration() != mClearedManagerGeneration
                    && isConfigurationWritable()) {
                mClearedManagerGeneration = confirmed.getGeneration();
                mConfiguration.clearManager();
            }
        }

        if (state == ConfigurationRepository.State.ERROR
                && state != mReportedConfigurationState) {
            report("Configuration error: " + mConfiguration.getErrorMessage() + "\n");
        } else if (state == ConfigurationRepository.State.SAVING
                && mReportedConfigurationState == ConfigurationRepository.State.VERIFIED) {
            report("Saving configuration...\n");
        }
        mReportedConfigurationState = state;
        updateRunButton();
    }

    private int managerPosition(String packageName) {
        if (packageName != null) {
            for (int i = 1; i < spinnerSuManager.getAdapter().getCount(); i++) {
                SuManagerEntry entry = (SuManagerEntry) spinnerSuManager.getAdapter().getItem(i);
                if (entry.packageName.equals(packageName)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private void updateRunButton() {
        // Hashing the selected loader is file I/O, so keep it off the main thread.
        btnRun.setEnabled(false);
        mPreflightReady = false;
        final int requestId = ++mPrecheckRequestId;
        if (!isRunEligible()) {
            mPrecheckReason = mConfiguration == null
                    ? "configuration is not verified" : mConfiguration.getRunAdmissionBlockReason();
            return;
        }
        final RunConfig snapshot = mConfiguration.getConfirmedSnapshot();
        if (snapshot == null) {
            mPrecheckReason = "configuration is not verified";
            return;
        }
        mExec.execute(() -> {
            boolean alreadyHooked;
            try {
                alreadyHooked = HookMarkerProbe.defaultMarkerPresent();
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Hook marker inspection failed", e);
                postRunUnavailable(requestId, "hook marker could not be inspected: " + e.getMessage());
                return;
            }
            if (alreadyHooked) {
                postRunUnavailable(requestId, "device is already hooked");
                return;
            }

            final RunPreflight.Result preflight;
            try {
                preflight = mPreflightChecker.check(snapshot);
            } catch (Exception e) {
                Log.w(TAG, "Run precheck unavailable", e);
                postRunUnavailable(requestId, "precheck could not be completed");
                return;
            }
            boolean ready = preflight != null && preflight.isReady();
            String reason = ready ? "" : preflight == null
                    ? "precheck could not be completed" : preflight.getReason();
            mMain.post(() -> {
                if (mDestroyed || requestId != mPrecheckRequestId) return;
                RunConfig current = mConfiguration.getConfirmedSnapshot();
                boolean stillEligible = isRunEligible()
                        && current != null && current.getGeneration() == snapshot.getGeneration();
                mPreflightReady = stillEligible && ready;
                mPreflightGeneration = stillEligible ? snapshot.getGeneration() : -1;
                btnRun.setEnabled(mPreflightReady);
                mPrecheckReason = stillEligible ? reason
                        : mConfiguration.getRunAdmissionBlockReason();
                if (!mPrecheckReason.isEmpty()) {
                    report("Run unavailable: " + mPrecheckReason + "\n");
                }
            });
        });
    }

    private void postRunUnavailable(int requestId, String reason) {
        mMain.post(() -> {
            if (mDestroyed || requestId != mPrecheckRequestId) return;
            mPreflightReady = false;
            mPreflightGeneration = -1;
            btnRun.setEnabled(false);
            if (!reason.equals(mPrecheckReason)) {
                mPrecheckReason = reason;
                report("Run unavailable: " + reason + "\n");
            }
        });
    }

    interface PreflightChecker {
        RunPreflight.Result check(RunConfig snapshot) throws Exception;
    }

    interface RunLauncher {
        RunResult launch(Context context, IReporter reporter);
    }

    /** Narrow deterministic seam; production uses the real worker, preflight, and runner. */
    static void installExecutionSeamsForTesting(MainActivity activity, Executor executor,
                                                PreflightChecker preflightChecker,
                                                RunLauncher runLauncher) {
        if (activity.mExec instanceof ExecutorService) {
            ((ExecutorService) activity.mExec).shutdownNow();
        }
        activity.mExec = java.util.Objects.requireNonNull(executor, "executor");
        activity.mPreflightChecker = java.util.Objects.requireNonNull(
                preflightChecker, "preflightChecker");
        activity.mRunLauncher = java.util.Objects.requireNonNull(runLauncher, "runLauncher");
    }

    private RunPreflight.Result preflightForSnapshot(RunConfig snapshot) {
        try {
            return RunPreflight.check(AndroidRunEnvironment.forConfig(mDeCtx, snapshot));
        } catch (Exception e) {
            Log.w(TAG, "Run precheck unavailable", e);
            return null;
        }
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
        RunResult result;
        try {
            // Acquire inside try so an exception here still reaches finally:
            // the lock is released and updateRunButton() is always posted,
            // otherwise the Run button would stay disabled forever.
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:manual");
                wl.acquire(ExploitRunner.WAKELOCK_TIMEOUT_MS);
            }
            result = mRunLauncher.launch(mDeCtx, this);
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
            result = RunResult.exception(e);
        } finally {
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
            mMain.post(() -> {
                if (mDestroyed) return; // btnRun belongs to a destroyed view hierarchy
                updateRunButton();
            });
        }
        String msg = ResultMessages.message(result);
        mMain.post(() -> {
            if (mDestroyed) return; // don't pop a Toast for a dead Activity
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
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
