// Modified 2026-11-24 (DFRoot GZIF): observe confirmed configuration, gate controls, and preserve off-thread preflight. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): request service-owned manual work and detach all execution/observation from the Activity lifecycle. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): add a read-only availability diagnostic from the authoritative preflight plan. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): compute the availability diagnostic off the UI thread with a stale-result guard. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): mint a request ID per click and clear pending only on that request's correlated outcome. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.1): reconcile an owned request outcome from the replayable ledger on start; read-only, no replay or privileged effect. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R6.1): add a bounded, lifecycle-scoped read-only outcome watch so a visible Activity reconciles its own outcome without a best-effort notification; unconfirmed stays uncertified. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R7.2): take exactly ONE ledger observation per watch tick so reconciliation and the final-tick unconfirmed decision cannot disagree; host-only deterministic lookup seam, no replay or durable change. See NOTICE.
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Request-and-observe UI only. The foreground service supplies process lifetime
 * and RunCoordinator exclusively owns admission, execution, and resources.
 */
public class MainActivity extends Activity {
    private static final String TAG = "dfroot";
    private static final int MAX_UI_OUTPUT_CHARACTERS = 32 * 1024;
    private static final String PENDING_DIAGNOSTIC_TEXT = "Checking availability...";
    private static final String UNAVAILABLE_DIAGNOSTIC_TEXT =
            "Run unavailable: availability could not be determined from read-only observations";
    // ~5 s of read-only checks; never an authorization, retry or recovery window.
    private static final long OUTCOME_WATCH_INTERVAL_MS = 200L;
    private static final int OUTCOME_WATCH_MAX_TICKS = 25;
    private static final String UNCONFIRMED_OUTCOME_MESSAGE =
            "Operation request outcome unconfirmed: no acknowledgement was received; "
                    + "no automatic retry or recovery is available in this app.\n";
    private static String sLastResultToastOperationId;

    // Read-only diagnostic scheduling only; the Activity never executes operations.
    private static final Executor DEFAULT_DIAGNOSTIC_WORKER = newDiagnosticWorker();
    private static final Executor DEFAULT_DIAGNOSTIC_MAIN = ConfigurationRepository.newMainExecutor();
    private static volatile Executor sDiagnosticWorker = DEFAULT_DIAGNOSTIC_WORKER;
    private static volatile Executor sDiagnosticMain = DEFAULT_DIAGNOSTIC_MAIN;

    /** Test-only seam; unset means each Activity owns a main-looper Handler. */
    private static volatile OutcomeWatchScheduler sOutcomeWatchScheduler;

    /**
     * HOST-ONLY read-only lookup seam; null in production, where the coordinator
     * ledger is read directly. A test can script one result per observation and
     * count lookups; this seam never writes, starts, retries or clears anything.
     */
    private static volatile Function<String, RunCoordinator.RequestOutcomeRecord>
            sRequestOutcomeLookupForTesting;

    private Button btnRun;
    private ScrollView outputScroll;
    private TextView outputView;
    private TextView availabilityView;
    private Spinner spinnerSuManager;
    /** Supplies read-only observations to the same canonical preflight the runner uses. */
    private static AvailabilityEnvironmentFactory sAvailabilityEnvironmentFactory =
            MainActivity::androidAvailabilityEnvironment;
    private Context mDeCtx;
    private ConfigurationRepository mConfiguration;
    private RunCoordinator mCoordinator;
    private ServiceStarter mServiceStarter;
    private final ConfigurationRepository.Listener mConfigurationListener =
            this::renderConfirmedConfiguration;
    private final RunCoordinator.Listener mCoordinatorListener = this::renderRunSnapshot;
    private final StringBuilder mUiMessages = new StringBuilder();
    private ConfigurationRepository.State mReportedConfigurationState;
    private boolean mApplyingConfiguration;
    private long mClearedManagerGeneration = -1;
    private boolean mManualStartPending;
    /** Click-time token; only an outcome for THIS request may clear pending state. */
    private String mPendingRequestId;
    private RunCoordinator.Snapshot mSuppressedSnapshot;
    private boolean mDestroyed;
    private long mDiagnosticSequence;
    private String mCachedDiagnostic = "";
    private OutcomeWatchScheduler mOutcomeWatchScheduler;
    private int mOutcomeWatchTicks;
    private String mOutcomeWatchRequestId;
    private String mUnconfirmedReportedRequestId;
    private boolean mOutcomeWatchVisible;

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
        mConfiguration = ConfigurationRepository.get(mDeCtx);
        mCoordinator = RunCoordinator.get(mDeCtx);
        mServiceStarter = (expectedGeneration, requestId) ->
                ExploitService.start(this, ExploitService.ACTION_MANUAL,
                        expectedGeneration, requestId);
        // A test seam may inject one deterministic scheduler; production keeps a
        // per-Activity main-looper scheduler so one instance cannot cancel another.
        mOutcomeWatchScheduler = sOutcomeWatchScheduler == null
                ? new HandlerOutcomeWatchScheduler() : sOutcomeWatchScheduler;

        PackageManager pm = getPackageManager();
        List<SuManagerEntry> entries = new ArrayList<>();
        for (String pkg : SuManager.packageNames()) {
            ApplicationInfo ai;
            try {
                ai = pm.getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // this manager is not installed
            }
            CharSequence label = pm.getApplicationLabel(ai);
            if (SuManager.CLASSIC.getPackageName().equals(pkg)) {
                label = label + " (classic; Next recommended)";
            }
            entries.add(new SuManagerEntry(pkg, label, pm.getApplicationIcon(ai)));
        }
        entries.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        entries.add(0, new SuManagerEntry(null, "Select a SU Manager", null));

        spinnerSuManager.setAdapter(new SuManagerAdapter(this, entries));
        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (mApplyingConfiguration) return;
                SuManagerEntry e = entries.get(pos);
                if (e.packageName == null) return;
                RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
                if (confirmed != null && Objects.equals(
                        confirmed.getManagerPackage(), e.packageName)) {
                    return;
                }
                if (!areControlsWritable()) {
                    renderConfirmedConfiguration();
                    return;
                }
                mConfiguration.setManagerPackage(e.packageName);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnRun.setOnClickListener(v -> requestManualRun());

        // Diagnostics get their own view so the operation-output buffer stays untouched.
        availabilityView = new TextView(this);
        availabilityView.setTextSize(12);
        LinearLayout root = findViewById(R.id.rootLayout);
        root.addView(availabilityView, 1);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mConfiguration.addListener(mConfigurationListener);
        mCoordinator.addListener(mCoordinatorListener);
        renderConfirmedConfiguration();
        renderRunSnapshot(mCoordinator.getSnapshot());
        // The listener was removed in onStop(): an outcome delivered while this
        // Activity was stopped may no longer occupy the single snapshot slot.
        mOutcomeWatchVisible = true;
        reconcilePendingRequest();
        armOutcomeWatchIfPending();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mOutcomeWatchVisible = true;
        reconcilePendingRequest();
        armOutcomeWatchIfPending();
    }

    @Override
    protected void onPause() {
        mOutcomeWatchVisible = false;
        super.onPause();
    }

    /**
     * Read-only reconciliation of THIS Activity's own click. The ledger is
     * consulted under its own lock; no record means uncertainty, never proof of
     * admission. Nothing is replayed, retried, cleared or started here.
     */
    private void reconcilePendingRequest() {
        if (mDestroyed || !mManualStartPending || mPendingRequestId == null
                || mCoordinator == null) {
            return;
        }
        // Lifecycle entry performs its own single observation and delegates.
        reconcilePendingRequest(observeRequestOutcome(mPendingRequestId));
    }

    /**
     * Reconcile from ONE already-observed ledger record. This method never reads
     * the ledger again, so the observation used here is also the one the watch
     * uses for its final-tick unconfirmed decision. Nothing is replayed, retried,
     * cleared or started.
     */
    private void reconcilePendingRequest(RunCoordinator.RequestOutcomeRecord record) {
        if (mDestroyed || !mManualStartPending || mPendingRequestId == null
                || mCoordinator == null) {
            return;
        }
        if (record == null) {
            return; // unconfirmed: keep the pending state honest, do not replay
        }
        RunCoordinator.RequestOutcome outcome = record.getOutcome();
        if (outcome != RunCoordinator.RequestOutcome.ACCEPTED
                && outcome != RunCoordinator.RequestOutcome.REJECTED
                && outcome != RunCoordinator.RequestOutcome.CANCELLED) {
            return;
        }
        boolean wasPending = clearPendingRequest();
        if (wasPending && outcome == RunCoordinator.RequestOutcome.REJECTED) {
            appendUiMessage("Operation request rejected: "
                    + requestDetail(record.getReason()) + "\n");
        } else if (wasPending && outcome == RunCoordinator.RequestOutcome.CANCELLED) {
            appendUiMessage("Operation request cancelled: "
                    + requestDetail(record.getReason()) + "\n");
        }
        renderOutput();
        updateRunButton();
    }

    /** One read-only observation; no ledger re-read on this call path. */
    private RunCoordinator.RequestOutcomeRecord observeRequestOutcome(String requestId) {
        Function<String, RunCoordinator.RequestOutcomeRecord> lookup =
                sRequestOutcomeLookupForTesting;
        return lookup != null ? lookup.apply(requestId)
                : mCoordinator.getRequestOutcome(requestId);
    }

    /** One bounded, read-only tick: reconcile only, never act on the operation. */
    private void runOutcomeWatchTick() {
        String requestId = mOutcomeWatchRequestId;
        if (!isPendingRequest(requestId)) {
            return; // lifecycle ended or pending cleared
        }
        mOutcomeWatchTicks++;
        // Exactly ONE observation per tick feeds BOTH decisions below. A late
        // correlated outcome seen by that observation is reconciled here; an
        // absent record is the only basis for the final-tick unconfirmed status.
        RunCoordinator.RequestOutcomeRecord record = observeRequestOutcome(requestId);
        reconcilePendingRequest(record);
        if (!isPendingRequest(requestId) || !requestId.equals(mOutcomeWatchRequestId)) {
            return; // a correlated outcome cleared pending; clearPendingRequest cancelled us
        }
        if (mOutcomeWatchTicks >= OUTCOME_WATCH_MAX_TICKS) {
            if (mCoordinator != null && record == null
                    && !requestId.equals(mUnconfirmedReportedRequestId)) {
                // No observation of a record after the whole window: honest
                // UI-only status. No rejection is fabricated, no attempt/lease/
                // state is cleared and btnRun stays disabled while pending.
                mUnconfirmedReportedRequestId = requestId;
                appendUiMessage(UNCONFIRMED_OUTCOME_MESSAGE);
                updateRunButton();
            }
            return;
        }
        mOutcomeWatchScheduler.schedule(this::runOutcomeWatchTick, OUTCOME_WATCH_INTERVAL_MS);
    }

    /** Arms/re-arms only for a still-pending click in a visible, live Activity. */
    private void armOutcomeWatchIfPending() {
        String requestId = mPendingRequestId;
        if (!isPendingRequest(requestId) || mOutcomeWatchScheduler == null) {
            return;
        }
        if (!requestId.equals(mOutcomeWatchRequestId)) {
            mOutcomeWatchTicks = 0;
            mOutcomeWatchRequestId = requestId;
        }
        if (mOutcomeWatchTicks >= OUTCOME_WATCH_MAX_TICKS) {
            return;
        }
        // At most one outstanding tick per Activity; no unbounded queuing.
        mOutcomeWatchScheduler.cancel();
        mOutcomeWatchScheduler.schedule(this::runOutcomeWatchTick, OUTCOME_WATCH_INTERVAL_MS);
    }

    private boolean isPendingRequest(String requestId) {
        return !mDestroyed && mOutcomeWatchVisible && mManualStartPending
                && requestId != null && requestId.equals(mPendingRequestId);
    }

    private void cancelOutcomeWatch() {
        mOutcomeWatchRequestId = null;
        if (mOutcomeWatchScheduler != null) {
            mOutcomeWatchScheduler.cancel();
        }
    }

    @Override
    protected void onStop() {
        mOutcomeWatchVisible = false;
        cancelOutcomeWatch();
        mConfiguration.removeListener(mConfigurationListener);
        mCoordinator.removeListener(mCoordinatorListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mOutcomeWatchVisible = false;
        cancelOutcomeWatch();
        // Idempotent detachment only; an accepted operation survives Activity destruction.
        mConfiguration.removeListener(mConfigurationListener);
        mCoordinator.removeListener(mCoordinatorListener);
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

    private void requestManualRun() {
        if (mDestroyed || mManualStartPending || !areControlsWritable()) {
            return;
        }
        // Read one immutable snapshot for both eligibility and the generation bind.
        RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
        if (confirmed == null || confirmed.getManagerPackage() == null) {
            return;
        }
        long expectedGeneration = confirmed.getGeneration();
        // One fresh token per click. No snapshot other than an outcome for THIS
        // token (boot, another operation, an older request) may clear pending.
        String requestId = UUID.randomUUID().toString();
        mPendingRequestId = requestId;
        // Optimistically block a second click until this request is acknowledged.
        mManualStartPending = true;
        mSuppressedSnapshot = mCoordinator.getSnapshot();
        mUiMessages.setLength(0);
        renderOutput();
        updateRunButton();
        try {
            mServiceStarter.startManual(expectedGeneration, requestId);
        } catch (RuntimeException e) {
            Log.w(TAG, "manual service start failed", e);
            // No service was started: publish the correlated rejection for this
            // request so the pending state is cleared through the same path as
            // a service-side rejection.
            // Record synchronously (outcomeLock only, never the durable lock)
            // and post observation delivery off the caller. If that post fails,
            // onStart() can still reconcile from the ledger.
            mCoordinator.noteRequestOutcomeAsync(requestId,
                    RunCoordinator.RequestOutcome.REJECTED,
                    "Unable to start operation service: " + e.getMessage());
        }
        // Arm immediately, independently of any listener/reporter delivery, so a
        // continuously visible Activity can reconcile its own recorded outcome
        // (including a synchronous start failure whose report was dropped).
        armOutcomeWatchIfPending();
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

    private boolean isRunEligible() {
        if (mDestroyed || mManualStartPending || !areControlsWritable()) {
            return false;
        }
        RunConfig confirmed = mConfiguration.getConfirmedSnapshot();
        return confirmed != null && confirmed.getManagerPackage() != null;
    }

    private void renderConfirmedConfiguration() {
        if (mDestroyed || mConfiguration == null) {
            return;
        }
        spinnerSuManager.setEnabled(areControlsWritable());

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
                    && areControlsWritable()) {
                mClearedManagerGeneration = confirmed.getGeneration();
                mConfiguration.clearManager();
            }
        }

        // Re-read after clearManager(), which may synchronously publish SAVING.
        ConfigurationRepository.State state = mConfiguration.getState();
        if (state == ConfigurationRepository.State.ERROR
                && state != mReportedConfigurationState) {
            appendUiMessage("Configuration error: " + mConfiguration.getErrorMessage() + "\n");
        } else if (state == ConfigurationRepository.State.SAVING
                && mReportedConfigurationState == ConfigurationRepository.State.VERIFIED) {
            appendUiMessage("Saving configuration...\n");
        }
        mReportedConfigurationState = state;
        renderOutput();
        updateRunButton();
        updateAvailabilityDiagnostic();
    }

    private void renderRunSnapshot(RunCoordinator.Snapshot snapshot) {
        if (mDestroyed || snapshot == null || mCoordinator == null) {
            return;
        }
        // Operation rendering always uses the newest state; a correlated outcome
        // published during another active operation must not regress its view.
        RunCoordinator.Snapshot latest = mCoordinator.getSnapshot();
        if (latest == null) {
            return;
        }
        if (mManualStartPending && mPendingRequestId != null
                && mPendingRequestId.equals(snapshot.getRequestId())) {
            if (snapshot.getRequestOutcome() == RunCoordinator.RequestOutcome.ACCEPTED) {
                clearPendingRequest();
            } else if (snapshot.getRequestOutcome() == RunCoordinator.RequestOutcome.REJECTED) {
                if (clearPendingRequest()) {
                    appendUiMessage("Operation request rejected: "
                            + requestDetail(snapshot) + "\n");
                }
            } else if (snapshot.getRequestOutcome() == RunCoordinator.RequestOutcome.CANCELLED) {
                if (clearPendingRequest()) {
                    appendUiMessage("Operation request cancelled: "
                            + requestDetail(snapshot) + "\n");
                }
            }
            // NONE and any other request ID are unrelated and deliberately ignored.
        }
        renderOutput();
        updateRunButton();
        showTerminalResultOnce(latest);
    }

    /** Idempotent: returns true only for the call that actually clears the pending click. */
    private boolean clearPendingRequest() {
        cancelOutcomeWatch();
        boolean wasPending = mManualStartPending || mPendingRequestId != null
                || mSuppressedSnapshot != null;
        mManualStartPending = false;
        mSuppressedSnapshot = null;
        mPendingRequestId = null;
        return wasPending;
    }

    private static String requestDetail(RunCoordinator.Snapshot snapshot) {
        return requestDetail(snapshot.getRequestFailure());
    }

    private static String requestDetail(String detail) {
        return detail == null || detail.isEmpty() ? "no reason supplied" : detail;
    }

    private void renderOutput() {
        if (mDestroyed || outputView == null) {
            return;
        }
        if (mSuppressedSnapshot != null) {
            // Keep operation output suppressed for a pending click, but let the
            // bounded UI-only unconfirmed status for THIS token be visible;
            // coordinator state is never rendered here.
            outputView.setText(isUnconfirmedStatusVisible() ? mUiMessages.toString() : "");
            return;
        }
        StringBuilder rendered = new StringBuilder();
        if (mCoordinator != null) {
            for (RunCoordinator.LogEntry entry : mCoordinator.getSnapshot().getLog()) {
                rendered.append(entry.getMessage());
            }
        }
        // Configuration diagnostics stay in a bounded UI-only buffer after coordinator output.
        rendered.append(mUiMessages);
        outputView.setText(rendered);
        outputScroll.post(() -> {
            if (!mDestroyed) {
                outputScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private boolean isUnconfirmedStatusVisible() {
        return mUnconfirmedReportedRequestId != null
                && mUnconfirmedReportedRequestId.equals(mPendingRequestId);
    }

    private void appendUiMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        mUiMessages.append(message);
        int overflow = mUiMessages.length() - MAX_UI_OUTPUT_CHARACTERS;
        if (overflow > 0) {
            mUiMessages.delete(0, overflow);
        }
        renderOutput();
    }

    private void showTerminalResultOnce(RunCoordinator.Snapshot snapshot) {
        if (snapshot.getState() != RunCoordinator.State.TERMINAL
                || snapshot.getOperationId() == null || snapshot.getResult() == null
                || Objects.equals(sLastResultToastOperationId, snapshot.getOperationId())) {
            return;
        }
        sLastResultToastOperationId = snapshot.getOperationId();
        Toast.makeText(this, ResultMessages.message(snapshot.getResult()), Toast.LENGTH_LONG).show();
    }

    private void updateRunButton() {
        btnRun.setEnabled(isRunEligible());
    }

    /**
     * Surfaces the runner's own fail-closed precheck result without invoking any of
     * its effects. This is diagnostic only: it never starts, stages, or substitutes
     * a manager/loader, and the runner's precheck remains authoritative.
     */
    private void updateAvailabilityDiagnostic() {
        if (mDestroyed || availabilityView == null) {
            return;
        }
        // Freeze the observation source and the config generation at dispatch time.
        // A later observation can only win if its sequence is the newest and its
        // generation still matches what is currently confirmed.
        final long sequence = ++mDiagnosticSequence;
        RunConfig confirmed = mConfiguration == null
                ? null : mConfiguration.getConfirmedSnapshot();
        final long generation = confirmed == null ? -1L : confirmed.getGeneration();
        final AvailabilityEnvironmentFactory factory = sAvailabilityEnvironmentFactory;
        final Context observationContext = mDeCtx;
        availabilityView.setText(PENDING_DIAGNOSTIC_TEXT);

        Runnable observation = () -> {
            String diagnostic;
            try {
                RunPreflight.Result result = RunPreflight.check(
                        factory.create(observationContext, confirmed));
                diagnostic = result.isReady() ? "" : "Run unavailable: " + result.getReason();
            } catch (RuntimeException e) {
                // Unreadable observations are not proof of availability; never guess.
                diagnostic = UNAVAILABLE_DIAGNOSTIC_TEXT;
            }
            final String computed = diagnostic;
            try {
                sDiagnosticMain.execute(() -> applyDiagnostic(sequence, generation, computed));
            } catch (RuntimeException e) {
                // A broken main-thread seam must not strand the view at
                // "Checking availability..."; publish the fail-closed snapshot.
                Log.w(TAG, "availability diagnostic main dispatch failed", e);
                Runnable publish = () -> applyDiagnostic(
                        sequence, generation, UNAVAILABLE_DIAGNOSTIC_TEXT);
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    publish.run();
                } else {
                    // The injected seam is unusable; fall back to the platform main executor.
                    ConfigurationRepository.newMainExecutor().execute(publish);
                }
            }
        };
        try {
            sDiagnosticWorker.execute(observation);
        } catch (RuntimeException e) {
            // A dispatch failure is an unreadable observation: fail closed on the UI thread.
            applyDiagnostic(sequence, generation, UNAVAILABLE_DIAGNOSTIC_TEXT);
        }
    }

    /** Applies only the newest observation; stale generations/sequences never overwrite. */
    private void applyDiagnostic(long sequence, long generation, String diagnostic) {
        if (mDestroyed || availabilityView == null) {
            return;
        }
        if (sequence != mDiagnosticSequence || generation != currentConfirmedGeneration()) {
            return;
        }
        mCachedDiagnostic = diagnostic;
        availabilityView.setText(diagnostic);
    }

    private long currentConfirmedGeneration() {
        RunConfig confirmed = mConfiguration == null
                ? null : mConfiguration.getConfirmedSnapshot();
        return confirmed == null ? -1L : confirmed.getGeneration();
    }

    private static Executor newDiagnosticWorker() {
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dfroot-availability");
            thread.setDaemon(true);
            return thread;
        });
        return worker;
    }

    /** Read-only Android observations; identical to the runner's environment source. */
    private static RunPreflight.Environment androidAvailabilityEnvironment(
            Context context, RunConfig confirmed) {
        return AndroidRunEnvironment.forConfig(context, confirmed);
    }

    interface AvailabilityEnvironmentFactory {
        RunPreflight.Environment create(Context context, RunConfig confirmed);
    }

    static void installAvailabilityEnvironmentForTesting(AvailabilityEnvironmentFactory factory) {
        sAvailabilityEnvironmentFactory = Objects.requireNonNull(factory, "factory");
    }

    static void resetAvailabilityEnvironmentForTesting() {
        sAvailabilityEnvironmentFactory = MainActivity::androidAvailabilityEnvironment;
    }

    /** Deterministic read-only diagnostic scheduling; no operation execution seam. */
    static void installDiagnosticExecutorsForTesting(Executor worker, Executor main) {
        sDiagnosticWorker = Objects.requireNonNull(worker, "worker");
        sDiagnosticMain = Objects.requireNonNull(main, "main");
    }

    static void resetDiagnosticExecutorsForTesting() {
        sDiagnosticWorker = DEFAULT_DIAGNOSTIC_WORKER;
        sDiagnosticMain = DEFAULT_DIAGNOSTIC_MAIN;
    }

    /** Only a read-only tick is scheduled; no operation execution seam. */
    interface OutcomeWatchScheduler {
        void schedule(Runnable tick, long delayMs);

        void cancel();
    }

    static void installOutcomeWatchSchedulerForTesting(OutcomeWatchScheduler scheduler) {
        sOutcomeWatchScheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    static void resetOutcomeWatchSchedulerForTesting() {
        sOutcomeWatchScheduler = null;
    }

    /** HOST-ONLY: script read-only ledger observations; never installed in production. */
    static void installRequestOutcomeLookupForTesting(
            Function<String, RunCoordinator.RequestOutcomeRecord> lookup) {
        sRequestOutcomeLookupForTesting = Objects.requireNonNull(lookup, "lookup");
    }

    static void resetRequestOutcomeLookupForTesting() {
        sRequestOutcomeLookupForTesting = null;
    }

    static TextView availabilityViewForTesting(MainActivity activity) {
        return activity.availabilityView;
    }

    static String cachedDiagnosticForTesting(MainActivity activity) {
        return activity.mCachedDiagnostic;
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

    /** Production seam: one delayed read-only tick on the main looper, cancelled on stop. */
    private static final class HandlerOutcomeWatchScheduler implements OutcomeWatchScheduler {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void schedule(Runnable tick, long delayMs) {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(tick, delayMs);
        }

        @Override
        public void cancel() {
            handler.removeCallbacksAndMessages(null);
        }
    }

    interface ServiceStarter {
        void startManual(long expectedGeneration, String requestId);
    }

    static void installServiceStarterForTesting(MainActivity activity, ServiceStarter starter) {
        activity.mServiceStarter = Objects.requireNonNull(starter, "starter");
    }

    static void resetResultToastForTesting() {
        sLastResultToastOperationId = null;
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
            ((TextView) v.findViewById(R.id.labelApp)).setText(e.label);
            return v;
        }
    }
}
