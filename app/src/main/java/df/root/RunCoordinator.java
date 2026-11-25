// Modified 2026-11-24 (DFRoot GZIF): add process-scoped single-flight operation ownership and immutable observation. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): retain application-scoped device-protected context for pre-unlock engine work. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): thread the admitted operation ID through the coordinator engine boundary. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): contain close() Errors so cleanup cannot strand the terminal snapshot. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): an unconfirmed native outcome keeps ownership until explicit recovery. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): recovery is single-claim and trusted-caller-only. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): persist an unconfirmed admission barrier across process death. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): make durable attempt clearing idempotent and await init without dropping requests. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): every unverified outcome keeps the barrier; only proven outcomes clear it. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): recovery needs proof the supervised engine call has actually finished. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): persisted-attempt recovery is a post-process-death, trusted-caller-only boundary. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): unverified diagnostics name only the observed cause; an unknown exit or exception never claims native rc=7. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): gate unattended admission immediately before the durable publish and re-check authorization before engine start. See NOTICE.
package df.root;

import android.content.Context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Process-wide owner of one privileged operation.
 *
 * <p>Admission is claimed atomically before configuration initialization or any
 * engine work. Duplicate manual/boot requests are rejected immediately and are
 * never queued. Configuration and the accepted {@link ResourceLease} remain
 * owned until the engine reports actual completion; timeout/interruption only
 * publish indeterminate state.</p>
 */
public final class RunCoordinator {
    public enum State {
        IDLE,
        STARTING,
        RUNNING,
        INDETERMINATE,
        TERMINAL
    }

    /** Engine implementations return only after actual completion, never merely on timeout. */
    public interface Engine {
        RunResult run(Context context, ConfigurationRepository.RunAdmission admission,
                      RunConfig.ExecutionMode mode, String operationId, RunEventListener events,
                      IReporter reporter) throws Exception;
    }

    public interface Listener {
        void onRunChanged(Snapshot snapshot);
    }

    /**
     * Narrow, read-only authorization seam for unattended runs. Production reads
     * the Start at Boot component setting; an unreadable/uncertain state must
     * return {@code false} so unattended work fails closed. Manual consent never
     * consults this gate.
     */
    interface UnattendedAuthorization {
        boolean isUnattendedEnabled();
    }

    static final int MAX_LOG_ENTRIES = 200;
    static final int MAX_LOG_CHARACTERS = 32 * 1024;
    static final long INITIALIZATION_TIMEOUT_MS = 10_000L;
    /** Short bounded wait so the first service request is not dropped during off-main init. */
    static final long INITIALIZATION_WAIT_MS = 500L;

    /** Explicit, observable reason for a final-gate rejection (nothing was published). */
    static final String UNATTENDED_AUTH_REJECT =
            "unattended admission suppressed: Start at Boot authorization is OFF or unreadable";
    /** Honest proven-pre-effect reason for a post-publish, pre-engine rejection. */
    static final String UNATTENDED_AUTH_CHANGED =
            "unattended authorization changed before engine start";

    /**
     * Honest, origin-aware diagnostic for an unconfirmed outcome. It names ONLY
     * the cause that was actually observed: native rc=7, an unrecognized native
     * exit, or an engine exception. No generic message may claim "native rc=7"
     * for an unknown exit or a Java exception.
     *
     * @param priorDetail the already-published timeout/interruption detail when
     *                    an unverified exit arrives after a supervision
     *                    observation; {@code null} for a first publication.
     */
    private static String unverifiedReason(RunResult result, String priorDetail) {
        String cause;
        if (result != null
                && result.getDetail() == RunResult.Detail.NATIVE_EXIT
                && result.getType() == RunResult.Type.INDETERMINATE) {
            // A genuine native rc=7 (including a conflict-shaped observation).
            cause = "native terminal publication was not confirmed (native rc=7); "
                    + "the worker may still be running";
        } else if (result != null && result.getDetail() == RunResult.Detail.NATIVE_EXIT) {
            // Unknown/unrecognized exit: no rc7 claim, no confirmed completion.
            cause = "native exit code was not recognized; completion could not be confirmed";
        } else if (result != null && result.getDetail() == RunResult.Detail.EXCEPTION) {
            cause = "engine call failed before a confirmed result; external effects remain unproven";
            String detail = result.getReason();
            if (detail != null && !detail.isEmpty()) {
                cause += " (" + detail + ")";
            }
        } else if (result == null) {
            cause = "no result was observed; external effects remain unproven";
        } else {
            // Timeout/interruption detail: use the actual detail, never rc7.
            cause = result.getReason() == null ? result.getDetail().name() : result.getReason();
        }
        String prefix = "outcome unverified: "
                + (priorDetail == null || priorDetail.isEmpty() ? "" : priorDetail + "; ");
        return prefix + cause + "; explicit recovery required";
    }

    private static String unverifiedReason(RunResult result) {
        return unverifiedReason(result, null);
    }

    private static volatile RunCoordinator instance;

    private final ConfigurationRepository repository;
    private final Executor workerExecutor;
    private final Executor mainExecutor;
    private final Engine engine;
    private final long initializationTimeoutMillis;
    private final OperationAttemptStore attemptStore;
    private final BootCountReader bootCountReader;
    private final Executor initializationExecutor;
    private final UnattendedAuthorization unattendedAuthorization;
    private final boolean ownsWorkerExecutor;
    private final boolean ownsInitializationExecutor;
    private final Object lock = new Object();
    private final CountDownLatch initializationComplete = new CountDownLatch(1);
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Queue<LogEntry> log = new ArrayDeque<>();

    private State state = State.IDLE;
    private Operation activeOperation;
    private boolean cleanupPending;
    private String operationId;
    private RunConfig.ExecutionMode mode;
    private long configGeneration = -1L;
    private RunResult result;
    private String reason;
    private String requestFailure;
    private long logCharacters;
    private long logSequence;
    private boolean initializationDone;
    private boolean initializationSucceeded;
    private String initializationFailure;
    private OperationAttempt persistedAttempt;
    /** True once THIS process published a barrier; blocks post-death recovery here. */
    private boolean attemptPublishedInThisProcess;
    private volatile Snapshot snapshot;

    /**
     * Safe test/default seam: no durable barrier exists and initialization is
     * already complete. The authorization seam is permissive here only so
     * existing pure-contract tests keep exercising explicit manual/boot paths;
     * production wires the real component gate in {@link #get(Context)} and
     * fails closed. Production never uses these constructors.
     */
    RunCoordinator(ConfigurationRepository repository, Executor workerExecutor,
                   Executor mainExecutor, Engine engine, long initializationTimeoutMillis) {
        this(repository, workerExecutor, mainExecutor, engine, initializationTimeoutMillis,
                OperationAttemptStore.inMemory(), () -> null, Runnable::run,
                () -> true, true, false, false);
    }

    /** Explicit seam for deterministic initialization and process-restart tests; test-only permissive authorization. */
    RunCoordinator(ConfigurationRepository repository, Executor workerExecutor,
                   Executor mainExecutor, Engine engine, long initializationTimeoutMillis,
                   OperationAttemptStore attemptStore, BootCountReader bootCountReader,
                   Executor initializationExecutor) {
        this(repository, workerExecutor, mainExecutor, engine, initializationTimeoutMillis,
                attemptStore, bootCountReader, initializationExecutor,
                () -> true, false, false, false);
    }

    /** Authorization-aware seam for deterministic OFF-transition tests. */
    RunCoordinator(ConfigurationRepository repository, Executor workerExecutor,
                   Executor mainExecutor, Engine engine, long initializationTimeoutMillis,
                   OperationAttemptStore attemptStore, BootCountReader bootCountReader,
                   Executor initializationExecutor,
                   UnattendedAuthorization unattendedAuthorization) {
        this(repository, workerExecutor, mainExecutor, engine, initializationTimeoutMillis,
                attemptStore, bootCountReader, initializationExecutor,
                unattendedAuthorization, false, false, false);
    }

    private RunCoordinator(ConfigurationRepository repository, Executor workerExecutor,
                           Executor mainExecutor, Engine engine,
                           long initializationTimeoutMillis, OperationAttemptStore attemptStore,
                           BootCountReader bootCountReader, Executor initializationExecutor,
                           UnattendedAuthorization unattendedAuthorization,
                           boolean preInitialized, boolean ownsWorkerExecutor,
                           boolean ownsInitializationExecutor) {
        if (initializationTimeoutMillis < 0) {
            throw new IllegalArgumentException("initializationTimeoutMillis must be non-negative");
        }
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.initializationTimeoutMillis = initializationTimeoutMillis;
        this.attemptStore = Objects.requireNonNull(attemptStore, "attemptStore");
        this.bootCountReader = Objects.requireNonNull(bootCountReader, "bootCountReader");
        this.initializationExecutor = Objects.requireNonNull(initializationExecutor,
                "initializationExecutor");
        this.unattendedAuthorization = Objects.requireNonNull(unattendedAuthorization,
                "unattendedAuthorization");
        this.ownsWorkerExecutor = ownsWorkerExecutor;
        this.ownsInitializationExecutor = ownsInitializationExecutor;
        this.initializationDone = preInitialized;
        this.initializationSucceeded = preInitialized;
        if (preInitialized) {
            initializationComplete.countDown();
        }
        this.snapshot = buildSnapshotLocked();
    }

    /** Returns the single process-wide coordinator. */
    public static RunCoordinator get(Context context) {
        RunCoordinator current = instance;
        if (current != null) {
            return current;
        }
        synchronized (RunCoordinator.class) {
            if (instance == null) {
                ConfigurationRepository repository = ConfigurationRepository.get(context);
                ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "dfroot-run");
                    thread.setDaemon(true);
                    return thread;
                });
                ExecutorService attemptInitialization = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "dfroot-attempt-init");
                    thread.setDaemon(true);
                    return thread;
                });
                Context app = context.getApplicationContext();
                Context source = app != null ? app : context;
                Context deContext = source.isDeviceProtectedStorage()
                        ? source : source.createDeviceProtectedStorageContext();
                // The START component is enabled/disabled in the application
                // context; the same read-only gate is used by the boot claim and
                // service workers. Any failure is treated as OFF (fail closed).
                UnattendedAuthorization authorization = () -> {
                    try {
                        return BootClaimCoordinator.BootComponentGate.PACKAGE_MANAGER.read(source)
                                == BootClaimCoordinator.BootComponentGate.State.ENABLED;
                    } catch (RuntimeException e) {
                        return false;
                    }
                };
                instance = new RunCoordinator(repository, worker,
                        ConfigurationRepository.newMainExecutor(), RunCoordinator::runProductionEngine,
                        INITIALIZATION_TIMEOUT_MS,
                        SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(deContext),
                        new BootCountReader.SettingsGlobal(source.getContentResolver()),
                        attemptInitialization, authorization, false, true, true);
            }
            return instance;
        }
    }

    static synchronized void setInstanceForTesting(RunCoordinator coordinator) {
        instance = coordinator;
    }

    static synchronized void resetForTesting() {
        RunCoordinator current = instance;
        instance = null;
        if (current != null) {
            if (current.ownsWorkerExecutor
                    && current.workerExecutor instanceof ExecutorService) {
                ((ExecutorService) current.workerExecutor).shutdownNow();
            }
            if (current.ownsInitializationExecutor
                    && current.initializationExecutor instanceof ExecutorService) {
                ((ExecutorService) current.initializationExecutor).shutdownNow();
            }
        }
    }

    /**
     * Loads the persisted attempt BEFORE any admission. Called once from
     * {@link ExploitService#onCreate()} and dispatched off the main thread via
     * the injected executor. Submit fails closed until this completes, and a
     * failed load permanently blocks admission in this process.
     */
    public void initialize() {
        Runnable load = () -> {
            OperationAttempt loaded;
            try {
                loaded = attemptStore.load();
            } catch (Exception e) {
                markInitializationFailed("attempt store load failed: " + e);
                return;
            }
            synchronized (lock) {
                if (initializationDone) {
                    return;
                }
                persistedAttempt = loaded;
                initializationSucceeded = true;
                initializationDone = true;
                appendLogLocked("attempt store initialized: "
                        + (loaded == null ? "no persisted attempt"
                        : "unconfirmed " + loaded.getOperationId()));
                Snapshot changed = buildSnapshotLocked();
                snapshot = changed;
                initializationComplete.countDown();
            }
        };
        try {
            initializationExecutor.execute(load);
        } catch (RuntimeException e) {
            markInitializationFailed("attempt store initialization dispatch failed: " + e);
        }
    }

    private void markInitializationFailed(String detail) {
        synchronized (lock) {
            if (initializationDone) {
                return;
            }
            initializationFailure = detail;
            initializationSucceeded = false;
            initializationDone = true;
            appendLogLocked(detail);
            Snapshot changed = buildSnapshotLocked();
            snapshot = changed;
            initializationComplete.countDown();
        }
    }

    private boolean isInitializedLocked() {
        return initializationDone && initializationSucceeded;
    }

    private long currentBootCount() {
        Long value;
        try {
            value = bootCountReader.read();
        } catch (RuntimeException e) {
            return 0L;
        }
        return value != null && value > 0L ? value : 0L;
    }

    /** Read-only authorization read; an unreadable gate is always OFF. */
    private boolean unattendedAuthorized() {
        try {
            return unattendedAuthorization.isUnattendedEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Authorization can be lost after the final submit check: during the attempt
     * publish/configuration wait or while the worker is queued. This re-check is
     * called under {@link #lock} so a proven pre-engine rejection cannot race
     * {@code engineStarted}. Once the engine has started, ownership stays here —
     * privileged work is never cancelled by an OFF toggle.
     */
    private boolean unattendedAuthorizationLostLocked(Operation operation) {
        if (operation.mode != RunConfig.ExecutionMode.UNATTENDED_BOOT
                || operation.engineStarted || operation.terminal
                || !isCurrentLocked(operation)) {
            return false;
        }
        return !unattendedAuthorized();
    }

    private static String failureDetail(Exception failure) {
        String detail = failure.getMessage();
        Throwable cause = failure.getCause();
        if (cause != null && cause.getMessage() != null) {
            detail = (detail == null || detail.isEmpty() ? "" : detail + ": ") + cause.getMessage();
        }
        return detail == null || detail.isEmpty() ? failure.getClass().getSimpleName() : detail;
    }

    /**
     * Boot count is diagnostic only: a later boot never authorizes replay of an
     * unconfirmed attempt from a previous process.
     */
    private static String priorAttemptReason(OperationAttempt prior, long currentBootCount) {
        String when;
        if (prior.getBootCount() == 0L || currentBootCount == 0L) {
            when = "BOOT_COUNT unavailable";
        } else if (currentBootCount == prior.getBootCount()) {
            when = "same boot count " + currentBootCount;
        } else if (currentBootCount < prior.getBootCount()) {
            when = "earlier boot count " + currentBootCount
                    + " (recorded " + prior.getBootCount() + ")";
        } else {
            when = "new boot count " + currentBootCount
                    + " (recorded " + prior.getBootCount() + ")";
        }
        return "unconfirmed operation " + prior.getOperationId()
                + " from a prior process (" + when + ") blocks admission; explicit recovery required";
    }

    /**
     * Atomically accepts a new operation or rejects the request without taking
     * ownership of {@code lease}. Accepted work is scheduled exactly once.
     */
    public Submission submit(Context context, RunConfig.ExecutionMode requestedMode,
                             ResourceLease lease) {
        return submit(context, requestedMode, lease, null);
    }

    /** Generation-bound manual admission; boot and legacy callers may pass {@code null}. */
    public Submission submit(Context context, RunConfig.ExecutionMode requestedMode,
                             ResourceLease lease, Long expectedGeneration) {
        if (context == null) {
            return Submission.rejected(null, "context is required");
        }
        if (requestedMode == null) {
            return Submission.rejected(null, "execution mode is required");
        }
        if (lease == null) {
            return Submission.rejected(null, "resource lease is required");
        }

        // An in-flight off-main load is not a rejection: wait briefly outside
        // the admission lock, then re-check state under that lock. Failure and
        // timeout still fail closed; no request is retried automatically.
        if (initializationComplete.getCount() != 0L) {
            try {
                initializationComplete.await(INITIALIZATION_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Submission.rejected(null,
                        "interrupted while waiting for attempt store initialization");
            }
        }

        long bootCount = currentBootCount();
        Operation operation = null;
        OperationAttempt attempt;
        Snapshot admitted = null;
        Snapshot rejectedSnapshot = null;
        String unauthorizedDetail = null;
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }
        // Never retain an Activity/Service wrapper, but do not discard Direct Boot's
        // DE storage context when normalizing to the application-scoped context.
        Context operationContext = applicationContext.isDeviceProtectedStorage()
                ? applicationContext : applicationContext.createDeviceProtectedStorageContext();
        synchronized (lock) {
            if (cleanupPending) {
                return Submission.rejected(operationId, "previous operation cleanup is pending");
            }
            if (state == State.STARTING || state == State.RUNNING
                    || state == State.INDETERMINATE) {
                return Submission.rejected(operationId,
                        "operation " + operationId + " is active (" + state + ")");
            }
            if (!isInitializedLocked()) {
                return Submission.rejected(operationId, initializationFailure != null
                        ? initializationFailure
                        : "attempt store initialization is not complete");
            }
            if (persistedAttempt != null && persistedAttempt.isUnconfirmed()) {
                return Submission.rejected(persistedAttempt.getOperationId(),
                        priorAttemptReason(persistedAttempt, bootCount));
            }

            // FINAL authorization gate: checked under the SAME lock that
            // publishes the durable attempt, immediately before that publish.
            // Manual consent is never gated. OFF or an unreadable/throwing gate
            // means NO publish, NO engine, NO lease owned by the coordinator.
            if (requestedMode == RunConfig.ExecutionMode.UNATTENDED_BOOT
                    && !unattendedAuthorized()) {
                unauthorizedDetail = UNATTENDED_AUTH_REJECT;
                // Publish the fresh rejection snapshot under the SAME lock as
                // the authorization decision; noteRequestRejected() below only
                // notifies observers, so a new submit cannot interleave here.
                requestFailure = unauthorizedDetail;
                rejectedSnapshot = buildSnapshotLocked();
                snapshot = rejectedSnapshot;
            }
            if (unauthorizedDetail == null) {
                String id = UUID.randomUUID().toString();
                attempt = new OperationAttempt(id, requestedMode, bootCount, true,
                        "admitted " + requestedMode + " operation " + id);
                boolean published;
                try {
                    // Checked, durable barrier: it exists before the engine can
                    // publish run_config or start privileged work.
                    published = attemptStore.publish(attempt);
                } catch (OperationAttemptStore.Failure e) {
                    return Submission.rejected(operationId, "attempt store publish failed: " + e);
                }
                if (!published) {
                    return Submission.rejected(operationId,
                            "attempt store refused admission: another unconfirmed operation is pending");
                }

                operation = new Operation(id, requestedMode, lease, operationContext,
                        expectedGeneration);
                persistedAttempt = attempt;
                attemptPublishedInThisProcess = true;
                activeOperation = operation;
                operationId = operation.id;
                mode = requestedMode;
                configGeneration = -1L;
                result = null;
                reason = null;
                requestFailure = null;
                state = State.STARTING;
                log.clear();
                logCharacters = 0L;
                appendLogLocked("admitted " + mode + " operation " + operationId);
                admitted = buildSnapshotLocked();
                snapshot = admitted;
            }
        }
        if (unauthorizedDetail != null) {
            // Fresh, observable rejection with an explicit reason. The caller
            // keeps its own lease; no barrier and no worker were created.
            notifyListeners(rejectedSnapshot);
            return Submission.rejected(null, unauthorizedDetail);
        }
        notifyListeners(admitted);

        final Operation acceptedOperation = operation;
        try {
            workerExecutor.execute(() -> start(acceptedOperation));
        } catch (RuntimeException e) {
            // Dispatch itself failed: the engine was never started, so this is
            // a proven PRE-EFFECT failure. It is represented as an exception-
            // shaped unverified result (INDETERMINATE/EXCEPTION), though, so
            // finish() skips the durable clear and the barrier is retained
            // fail-closed until trusted recovery. Engine results and engine
            // exceptions are routed by complete().
            RunResult failure = RunResult.exception(e);
            finish(acceptedOperation, failure, failure.getReason());
        }
        return Submission.accepted(acceptedOperation.id);
    }

    /** Latest immutable state; previously returned snapshots never change. */
    public Snapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Publishes a service-side request rejection without changing operation result
     * semantics. Active work and cleanup always take precedence over this signal.
     */
    void noteRequestRejected(String requestFailure) {
        Snapshot rejected;
        synchronized (lock) {
            if (cleanupPending || activeOperation != null || isActiveStateLocked()) {
                return;
            }
            // Even a reason-less rejection is an observable outcome: a FRESH
            // snapshot is required so identity-based UI pending state clears.
            String detail = requestFailure == null || requestFailure.isEmpty()
                    ? "request rejected" : requestFailure;
            this.requestFailure = detail;
            rejected = buildSnapshotLocked();
            snapshot = rejected;
        }
        notifyListeners(rejected);
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    private void start(Operation operation) {
        try {
            // Post-publish, pre-engine recheck. OFF can be set after submit()
            // published the barrier: during the configuration wait or while this
            // worker was queued. No privileged work has started yet, so this is
            // a proven pre-effect rejection and the checked clear is allowed.
            boolean authorizationLost;
            synchronized (lock) {
                authorizationLost = unattendedAuthorizationLostLocked(operation);
            }
            if (authorizationLost) {
                finish(operation, RunResult.precheckFailed(UNATTENDED_AUTH_CHANGED),
                        UNATTENDED_AUTH_CHANGED);
                return;
            }

            ConfigurationRepository.State initialized = repository.awaitInitialization(
                    initializationTimeoutMillis);
            if (initialized != ConfigurationRepository.State.VERIFIED) {
                String detail = initialized == ConfigurationRepository.State.SAVING
                        ? "configuration initialization timed out after "
                        + initializationTimeoutMillis + " ms"
                        : "configuration initialization failed: " + repository.getErrorMessage();
                finish(operation, RunResult.precheckFailed(detail), detail);
                return;
            }

            ConfigurationRepository.RunAdmission admission =
                    repository.acquireRunSnapshot(operation.mode, operation.expectedGeneration);
            if (admission == null) {
                String detail = repository.getRunAdmissionBlockReason(
                        operation.mode, operation.expectedGeneration);
                if (detail == null || detail.isEmpty()) {
                    detail = "configuration admission was rejected";
                }
                finish(operation, RunResult.precheckFailed(detail), detail);
                return;
            }

            RunConfig config;
            synchronized (lock) {
                operation.admission = admission;
            }
            config = admission.getSnapshot();
            Snapshot running;
            synchronized (lock) {
                if (!isCurrentLocked(operation)) {
                    running = null;
                } else {
                    configGeneration = config.getGeneration();
                    state = State.RUNNING;
                    appendLogLocked("configuration generation " + configGeneration
                            + " frozen; engine starting");
                    running = buildSnapshotLocked();
                    snapshot = running;
                }
            }
            if (running == null) {
                closeAdmissionOnce(operation);
                closeLeaseOnce(operation);
                return;
            }
            notifyListeners(running);

            RunEventListener events = new RunEventListener() {
                @Override
                public void onTimeout() {
                    markIndeterminate(operation, RunResult.Detail.PROCESS_TIMEOUT);
                }

                @Override
                public void onInterrupted() {
                    markIndeterminate(operation, RunResult.Detail.PROCESS_INTERRUPTION);
                }
            };
            IReporter reporter = message -> appendOperationLog(operation, message);
            boolean authLostBeforeEngine;
            synchronized (lock) {
                // Second, atomic recheck: the gate read and engineStarted share
                // the same lock, so an OFF toggle cannot slip between the read
                // and engine.run(). An already-started engine keeps ownership.
                authLostBeforeEngine = unattendedAuthorizationLostLocked(operation);
                if (!authLostBeforeEngine) {
                    operation.engineStarted = true;
                }
            }
            if (authLostBeforeEngine) {
                // engine.run() was never called; finish() clears the durable
                // attempt as a proven pre-effect rejection.
                finish(operation, RunResult.precheckFailed(UNATTENDED_AUTH_CHANGED),
                        UNATTENDED_AUTH_CHANGED);
                return;
            }
            RunResult completed;
            try {
                completed = engine.run(operation.context, admission, operation.mode,
                        operation.id, events, reporter);
            } finally {
                synchronized (lock) {
                    // Evidence that supervised work is no longer executing. Set
                    // only when engine.run() actually returns (or escapes); a
                    // supervision timeout/interruption callback while the engine
                    // is still blocked must NOT set it.
                    operation.engineFinished = true;
                }
            }
            if (completed == null) {
                throw new IllegalStateException("engine returned null");
            }
            complete(operation, completed, completed.getReason());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // The engine escaped without a confirmed result: privileged work may
            // still be running, so this is unverified too. Route it through the
            // SAME decision as a returned result, never straight to finish().
            RunResult failure = RunResult.exception(e);
            complete(operation, failure, failure.getReason());
        }
    }

    /**
     * The single routing decision for engine results and engine exceptions.
     * Every unverified outcome keeps ownership; only confirmed/proven results
     * may reach {@link #finish()}.
     */
    private void complete(Operation operation, RunResult result, String reason) {
        if (result != null && result.isOutcomeUnverified()) {
            markOutcomeUnverified(operation, result);
        } else {
            finish(operation, result, reason);
        }
    }

    private void markIndeterminate(Operation operation, RunResult.Detail detail) {
        Snapshot changed;
        synchronized (lock) {
            if (!isCurrentLocked(operation) || operation.terminal
                    || state == State.INDETERMINATE) {
                return;
            }
            if (detail == RunResult.Detail.PROCESS_TIMEOUT) {
                result = RunResult.processTimeout();
            } else if (detail == RunResult.Detail.PROCESS_INTERRUPTION) {
                result = RunResult.processInterrupted();
            } else {
                throw new IllegalArgumentException("unsupported indeterminate detail: " + detail);
            }
            reason = result.getReason();
            state = State.INDETERMINATE;
            appendLogLocked("indeterminate: " + detail + "; retaining resources until actual exit");
            changed = buildSnapshotLocked();
            snapshot = changed;
        }
        notifyListeners(changed);
    }

    /**
     * Keeps ownership of an operation whose native outcome was not confirmed.
     * No finish(), no kill(), no retry: the admission and lease stay open, the
     * config stays frozen, and new submissions stay rejected. The only exit is
     * an explicit {@link #recoverIndeterminate(String)} call by a trusted
     * caller. The persisted attempt stays unconfirmed across process death.
     * The published reason is derived from the observed cause only, so an
     * unrecognized exit or an engine exception is never reported as native rc7.
     */
    private void markOutcomeUnverified(Operation operation, RunResult completed) {
        Snapshot changed;
        synchronized (lock) {
            if (!isCurrentLocked(operation) || operation.terminal) {
                return;
            }
            if (result == null || state != State.INDETERMINATE) {
                result = completed;
                reason = unverifiedReason(completed);
                state = State.INDETERMINATE;
                appendLogLocked("indeterminate: outcome unverified; retaining lease and "
                        + "configuration freeze until explicit recovery");
            } else {
                // A supervision timeout/interruption already published the
                // unverified state. Keep its result and ownership intact, but
                // build the combined reason from the actual observation: an
                // unknown exit or exception must never be reported as rc=7.
                String priorDetail = reason != null ? reason : result.getReason();
                reason = unverifiedReason(completed, priorDetail);
                appendLogLocked("indeterminate: unconfirmed exit also observed; "
                        + "ownership retained");
            }
            changed = buildSnapshotLocked();
            snapshot = changed;
        }
        notifyListeners(changed);
    }

    /**
     * Explicit recovery from an unverified (INDETERMINATE) operation.
     *
     * <p>This is the ONLY way to close that operation's retained admission and
     * lease and to release single-flight admission. No timer, retry, observer,
     * or lifecycle callback calls it: the caller must present the exact
     * operation ID. The checked attempt-store clear must succeed before any
     * lease/freeze release or new admission is possible.</p>
     *
     * <p>INDETERMINATE alone is not evidence that supervised work stopped: a
     * supervisor timeout or interruption can publish it while engine.run() is
     * still blocked. Recovery therefore also requires proof that the engine
     * call has finished (or that it was never started, i.e. a proven
     * pre-effect rejection). A still-running engine is refused until it
     * returns, so cleanup cannot race a worker that may still be acting.</p>
     *
     * <p>Trusted-caller boundary: this method has no exported component, intent
     * action, broadcast, or binder path, and there is NO in-app recovery UI.
     * It is a direct in-process API for a trusted caller (tests exercise it
     * here); the service/UI never wire it.</p>
     *
     * @return {@code true} only for the caller that actually claims this
     *         recovery, has proof no supervised work is still executing, and
     *         durably cleared the attempt; {@code false} otherwise.
     */
    public boolean recoverIndeterminate(String operationId) {
        Operation operation;
        RunResult currentResult;
        String currentReason;
        synchronized (lock) {
            if (state != State.INDETERMINATE || cleanupPending || activeOperation == null
                    || activeOperation.recoveryClaimed
                    || !Objects.equals(operationId, this.operationId)
                    // A timeout/interruption observation can be published while
                    // the engine is still blocked. Releasing the lease, freeze
                    // or barrier then would race live privileged work.
                    || (activeOperation.engineStarted && !activeOperation.engineFinished)) {
                return false;
            }
            // Claim under the same lock as the check: concurrent callers with
            // the correct ID cannot both observe an unclaimed operation.
            activeOperation.recoveryClaimed = true;
            operation = activeOperation;
            currentResult = result;
            currentReason = reason;
        }
        boolean cleared;
        String failure = null;
        try {
            cleared = attemptStore.clear(operation.id);
        } catch (OperationAttemptStore.Failure e) {
            cleared = false;
            failure = failureDetail(e);
        }
        if (!cleared) {
            synchronized (lock) {
                if (activeOperation == operation && !operation.terminal) {
                    operation.recoveryClaimed = false;
                    appendLogLocked("indeterminate recovery refused: persisted attempt clear failed"
                            + (failure == null ? "" : " (" + failure + ")"));
                    Snapshot changed = buildSnapshotLocked();
                    snapshot = changed;
                }
            }
            return false;
        }
        synchronized (lock) {
            if (activeOperation == operation) {
                // The durable barrier is already gone. finish() must not try
                // to clear it a second time and strand cleanup on a false CAS.
                operation.attemptCleared = true;
                persistedAttempt = null;
            }
        }
        // finish() keeps the indeterminate result, closes the admission and
        // lease exactly once, and publishes TERMINAL only after both closes.
        finish(operation, currentResult, currentReason);
        return true;
    }

    /**
     * Explicit recovery of a persisted unconfirmed attempt AFTER process death.
     *
     * <p>Requires an affirmative owner acknowledgement and a checked store
     * clear. It only removes the barrier: it never retries, starts a service,
     * acquires resources, or admits an operation. A subsequent operation needs
     * its own explicit submission after initialization. This call cannot prove
     * that external effects from the dead process actually stopped; it is a
     * trusted-caller escape hatch, NOT an owner feature.</p>
     *
     * <p>There is NO in-app recovery UI. An ordinary user whose attempt is
     * unconfirmed is intentionally locked out — every manual and boot admission
     * is rejected — until a trusted caller clears the barrier here or the user
     * resets the app data. A new {@code BOOT_COUNT} is diagnostic only; it is
     * NOT proof of safety and never authorizes clearing on its own.</p>
     *
     * <p>Trusted-caller boundary: there is no exported component, intent action,
     * broadcast, or binder path in this phase. This is a direct in-process API
     * for a trusted caller; no UI or recovery intent is wired to it. The barrier
     * must be one loaded at initialization, not one published by this process.</p>
     *
     * @return {@code true} only when the acknowledged, matching, unconfirmed
     *         attempt loaded after process death was durably cleared.
     */
    public boolean recoverPersistedAttempt(String operationId, boolean ownerAcknowledged) {
        if (!ownerAcknowledged || operationId == null || operationId.isEmpty()) {
            return false;
        }
        OperationAttempt loaded;
        try {
            loaded = attemptStore.load();
        } catch (OperationAttemptStore.Failure e) {
            return false;
        }
        synchronized (lock) {
            if (!isInitializedLocked() || cleanupPending || isActiveStateLocked()
                    || attemptPublishedInThisProcess
                    || persistedAttempt == null || !persistedAttempt.isUnconfirmed()
                    || loaded == null || !loaded.isUnconfirmed()
                    || !loaded.getOperationId().equals(persistedAttempt.getOperationId())
                    || !loaded.getOperationId().equals(operationId)) {
                return false;
            }
            boolean cleared;
            try {
                cleared = attemptStore.clear(operationId);
            } catch (OperationAttemptStore.Failure e) {
                cleared = false;
            }
            if (!cleared) {
                return false;
            }
            if (activeOperation != null) {
                activeOperation.attemptCleared = true;
            }
            if (persistedAttempt != null
                    && persistedAttempt.getOperationId().equals(operationId)) {
                persistedAttempt = null;
            }
            appendLogLocked("persisted attempt " + operationId
                    + " cleared by trusted-caller acknowledgement after process death; "
                    + "external effects remain unproven; no work started");
            Snapshot changed = buildSnapshotLocked();
            snapshot = changed;
            return true;
        }
    }

    private void appendOperationLog(Operation operation, String message) {
        Snapshot changed;
        synchronized (lock) {
            if (!isCurrentLocked(operation) || operation.terminal) {
                return;
            }
            appendLogLocked(message);
            changed = buildSnapshotLocked();
            snapshot = changed;
        }
        notifyListeners(changed);
    }

    /**
     * Closes resources once, then publishes TERMINAL only after cleanup has
     * finished. The durable attempt is cleared ONLY for a proven pre-effect
     * rejection or a confirmed completion: an unverified outcome (native rc7,
     * unknown exit, engine exception) and an observation-only
     * timeout/interruption keep the barrier. A candidate that needs a clear
     * also needs it to succeed: a false or throwing clear keeps ownership as
     * INDETERMINATE and never publishes a confirmed TERMINAL.
     * {@link Operation#attemptCleared} makes a later clear after explicit
     * recovery idempotent, so cleanup cannot be stranded.
     */
    private void finish(Operation operation, RunResult completed, String completedReason) {
        RunResult terminalResult = null;
        String terminalReason = null;
        String admissionFailure = null;
        String leaseFailure = null;
        Snapshot held = null;
        synchronized (lock) {
            if (!isCurrentLocked(operation) || operation.terminal) {
                return;
            }
            RunResult candidate;
            String candidateReason;
            if (state == State.INDETERMINATE && result != null) {
                candidate = result;
                candidateReason = reason;
            } else {
                candidate = completed;
                candidateReason = completedReason;
            }
            if (candidate == null) {
                candidate = RunResult.exception(
                        new IllegalStateException("engine returned no result"));
                candidateReason = candidate.getReason();
            }
            if (candidate.getType() != RunResult.Type.INDETERMINATE
                    && !candidate.isOutcomeUnverified()
                    && !operation.attemptCleared) {
                boolean cleared;
                String failure = null;
                try {
                    cleared = attemptStore.clear(operation.id);
                } catch (OperationAttemptStore.Failure e) {
                    cleared = false;
                    failure = failureDetail(e);
                }
                if (!cleared) {
                    String detail = "terminal confirmation could not be persisted: "
                            + (failure == null ? "checked clear returned false" : failure);
                    RunResult unconfirmed = RunResult.exception(new IllegalStateException(
                            detail + "; original result " + candidate.getType()));
                    result = unconfirmed;
                    reason = unconfirmed.getReason() + "; explicit recovery required";
                    state = State.INDETERMINATE;
                    appendLogLocked("indeterminate: " + detail
                            + "; lease, configuration freeze and ownership retained");
                    held = buildSnapshotLocked();
                    snapshot = held;
                } else {
                    operation.attemptCleared = true;
                    persistedAttempt = null;
                }
            }
            if (held == null) {
                operation.terminal = true;
                cleanupPending = true;
                terminalResult = candidate;
                terminalReason = candidateReason == null ? candidate.getReason() : candidateReason;
            }
        }
        if (held != null) {
            notifyListeners(held);
            return;
        }

        try {
            admissionFailure = closeAdmissionOnce(operation);
        } finally {
            leaseFailure = closeLeaseOnce(operation);
        }

        Snapshot terminal;
        synchronized (lock) {
            // No newer operation can be admitted while cleanupPending is true.
            result = terminalResult;
            reason = terminalReason;
            state = State.TERMINAL;
            cleanupPending = false;
            activeOperation = null;
            appendLogLocked("terminal: " + terminalResult.getType() + "/" + terminalResult.getDetail()
                    + " code=" + terminalResult.getCode());
            if (admissionFailure != null) {
                appendLogLocked("admission close failed: " + admissionFailure);
            }
            if (leaseFailure != null) {
                appendLogLocked("lease close failed: " + leaseFailure);
            }
            terminal = buildSnapshotLocked();
            snapshot = terminal;
        }
        notifyListeners(terminal);
    }

    private String closeAdmissionOnce(Operation operation) {
        synchronized (lock) {
            if (operation.admissionClosed) {
                return null;
            }
            operation.admissionClosed = true;
        }
        if (operation.admission == null) {
            return null;
        }
        try {
            operation.admission.close();
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String closeLeaseOnce(Operation operation) {
        synchronized (lock) {
            if (operation.leaseClosed) {
                return null;
            }
            operation.leaseClosed = true;
        }
        try {
            operation.lease.close();
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private boolean isCurrentLocked(Operation operation) {
        return activeOperation == operation && !operation.terminal;
    }

    private boolean isActiveStateLocked() {
        return state == State.STARTING || state == State.RUNNING || state == State.INDETERMINATE;
    }

    private void appendLogLocked(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String retained = message.length() > MAX_LOG_CHARACTERS
                ? message.substring(message.length() - MAX_LOG_CHARACTERS) : message;
        while (!log.isEmpty() && (log.size() >= MAX_LOG_ENTRIES
                || logCharacters + retained.length() > MAX_LOG_CHARACTERS)) {
            LogEntry removed = log.remove();
            logCharacters -= removed.message.length();
        }
        log.add(new LogEntry(++logSequence, retained));
        logCharacters += retained.length();
    }

    private Snapshot buildSnapshotLocked() {
        return new Snapshot(state, operationId, mode, configGeneration, result, reason,
                requestFailure, Collections.unmodifiableList(new ArrayList<>(log)));
    }

    private void notifyListeners(Snapshot changed) {
        try {
            mainExecutor.execute(() -> {
                // Posted callbacks can be delivered after a newer state or operation.
                if (changed != snapshot) {
                    return;
                }
                for (Listener listener : listeners) {
                    try {
                        listener.onRunChanged(changed);
                    } catch (RuntimeException ignored) {
                        // One broken observer must not suppress the others.
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // Observation delivery cannot change operation ownership or cleanup.
        }
    }

    private static RunResult runProductionEngine(
            Context context, ConfigurationRepository.RunAdmission admission,
            RunConfig.ExecutionMode mode, String operationId, RunEventListener events,
            IReporter reporter) {
        return ExploitRunner.runDetailed(context, admission, mode, operationId, reporter, events);
    }

    public static final class Submission {
        private final boolean accepted;
        private final String operationId;
        private final String reason;

        private Submission(boolean accepted, String operationId, String reason) {
            this.accepted = accepted;
            this.operationId = operationId;
            this.reason = reason;
        }

        static Submission accepted(String operationId) {
            return new Submission(true, operationId, null);
        }

        static Submission rejected(String operationId, String reason) {
            return new Submission(false, operationId, reason);
        }

        public boolean isAccepted() {
            return accepted;
        }

        /** Active operation ID for a duplicate, or the new ID for an acceptance. */
        public String getOperationId() {
            return operationId;
        }

        public String getReason() {
            return reason;
        }
    }

    /** Immutable observable state and bounded operation log. */
    public static final class Snapshot {
        private final State state;
        private final String operationId;
        private final RunConfig.ExecutionMode mode;
        private final long configGeneration;
        private final RunResult result;
        private final String reason;
        private final String requestFailure;
        private final List<LogEntry> log;

        Snapshot(State state, String operationId, RunConfig.ExecutionMode mode,
                 long configGeneration, RunResult result, String reason,
                 String requestFailure, List<LogEntry> log) {
            this.state = state;
            this.operationId = operationId;
            this.mode = mode;
            this.configGeneration = configGeneration;
            this.result = result;
            this.reason = reason;
            this.requestFailure = requestFailure;
            this.log = log;
        }

        public State getState() {
            return state;
        }

        public String getOperationId() {
            return operationId;
        }

        public RunConfig.ExecutionMode getMode() {
            return mode;
        }

        public long getConfigGeneration() {
            return configGeneration;
        }

        public RunResult getResult() {
            return result;
        }

        public String getReason() {
            return reason;
        }

        /** Last service-side request rejection, cleared atomically on accepted admission. */
        public String getRequestFailure() {
            return requestFailure;
        }

        public List<LogEntry> getLog() {
            return log;
        }
    }

    public static final class LogEntry {
        private final long sequence;
        private final String message;

        LogEntry(long sequence, String message) {
            this.sequence = sequence;
            this.message = message;
        }

        public long getSequence() {
            return sequence;
        }

        public String getMessage() {
            return message;
        }
    }

    private static final class Operation {
        final String id;
        final RunConfig.ExecutionMode mode;
        final ResourceLease lease;
        final Context context;
        final Long expectedGeneration;
        ConfigurationRepository.RunAdmission admission;
        boolean terminal;
        boolean admissionClosed;
        boolean leaseClosed;
        boolean recoveryClaimed;
        boolean attemptCleared;
        /** Set immediately before engine.run(); never by timeout/interruption callbacks. */
        boolean engineStarted;
        /** Set only when engine.run() has actually returned (or escaped by throwing). */
        boolean engineFinished;

        Operation(String id, RunConfig.ExecutionMode mode, ResourceLease lease, Context context,
                  Long expectedGeneration) {
            this.id = id;
            this.mode = mode;
            this.lease = lease;
            this.context = context;
            this.expectedGeneration = expectedGeneration;
        }
    }
}
