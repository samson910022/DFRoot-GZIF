// Modified 2026-11-24 (DFRoot GZIF): add process-scoped single-flight operation ownership and immutable observation. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): retain application-scoped device-protected context for pre-unlock engine work. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): thread the admitted operation ID through the coordinator engine boundary. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): contain close() Errors so cleanup cannot strand the terminal snapshot. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): an unconfirmed native outcome keeps ownership until explicit recovery. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): recovery is single-claim and trusted-caller-only. See NOTICE.
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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    static final int MAX_LOG_ENTRIES = 200;
    static final int MAX_LOG_CHARACTERS = 32 * 1024;
    static final long INITIALIZATION_TIMEOUT_MS = 10_000L;
    private static final String UNVERIFIED_REASON =
            "outcome unverified: native rc=7 terminal publication was not confirmed; "
            + "the worker may still be running and explicit recovery is required";

    private static volatile RunCoordinator instance;

    private final ConfigurationRepository repository;
    private final Executor workerExecutor;
    private final Executor mainExecutor;
    private final Engine engine;
    private final long initializationTimeoutMillis;
    private final boolean ownsWorkerExecutor;
    private final Object lock = new Object();
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
    private volatile Snapshot snapshot;

    RunCoordinator(ConfigurationRepository repository, Executor workerExecutor,
                   Executor mainExecutor, Engine engine, long initializationTimeoutMillis) {
        this(repository, workerExecutor, mainExecutor, engine,
                initializationTimeoutMillis, false);
    }

    private RunCoordinator(ConfigurationRepository repository, Executor workerExecutor,
                           Executor mainExecutor, Engine engine,
                           long initializationTimeoutMillis, boolean ownsWorkerExecutor) {
        if (initializationTimeoutMillis < 0) {
            throw new IllegalArgumentException("initializationTimeoutMillis must be non-negative");
        }
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.initializationTimeoutMillis = initializationTimeoutMillis;
        this.ownsWorkerExecutor = ownsWorkerExecutor;
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
                instance = new RunCoordinator(repository, worker,
                        ConfigurationRepository.newMainExecutor(), RunCoordinator::runProductionEngine,
                        INITIALIZATION_TIMEOUT_MS, true);
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
        if (current != null && current.ownsWorkerExecutor
                && current.workerExecutor instanceof ExecutorService) {
            ((ExecutorService) current.workerExecutor).shutdownNow();
        }
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

        Operation operation;
        Snapshot admitted;
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

            operation = new Operation(UUID.randomUUID().toString(), requestedMode, lease,
                    operationContext, expectedGeneration);
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
        notifyListeners(admitted);

        try {
            workerExecutor.execute(() -> start(operation));
        } catch (RuntimeException e) {
            RunResult failure = RunResult.exception(e);
            finish(operation, failure, failure.getReason());
        }
        return Submission.accepted(operation.id);
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
        if (requestFailure == null || requestFailure.isEmpty()) {
            return;
        }
        Snapshot rejected;
        synchronized (lock) {
            if (cleanupPending || activeOperation != null || isActiveStateLocked()) {
                return;
            }
            this.requestFailure = requestFailure;
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
            RunResult completed = engine.run(operation.context, admission, operation.mode,
                    operation.id, events, reporter);
            if (completed == null) {
                throw new IllegalStateException("engine returned null");
            }
            if (isUnverifiedNativeResult(completed)) {
                // Native rc=7 means completion was never confirmed. A timeout or
                // a direct child exit must never be reported as the whole
                // operation being done: keep the lease, the frozen config and
                // single-flight admission until an explicit recovery.
                markOutcomeUnverified(operation, completed);
            } else {
                finish(operation, completed, completed.getReason());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            RunResult failure = RunResult.exception(e);
            finish(operation, failure, failure.getReason());
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
     * Native rc=7 is mapped to this exact typed result. It is the only engine
     * result that means "completion was never confirmed" rather than an
     * observed exit, a supervision timeout/interruption, or an exception.
     */
    private static boolean isUnverifiedNativeResult(RunResult result) {
        return result != null
                && result.getType() == RunResult.Type.INDETERMINATE
                && result.getDetail() == RunResult.Detail.NATIVE_EXIT
                && result.getCode() == ExploitRunner.RESULT_INDETERMINATE;
    }

    /**
     * Keeps ownership of an operation whose native outcome was not confirmed.
     * No finish(), no kill(), no retry: the admission and lease stay open, the
     * config stays frozen, and new submissions stay rejected. The only exit is
     * an explicit {@link #recoverIndeterminate(String)} call by a trusted
     * caller; a later phase will persist and authorize that boundary.
     */
    private void markOutcomeUnverified(Operation operation, RunResult completed) {
        Snapshot changed;
        synchronized (lock) {
            if (!isCurrentLocked(operation) || operation.terminal) {
                return;
            }
            if (result == null || state != State.INDETERMINATE) {
                result = completed;
                reason = UNVERIFIED_REASON;
                state = State.INDETERMINATE;
                appendLogLocked("indeterminate: outcome unverified; retaining lease and "
                        + "configuration freeze until explicit recovery");
            } else {
                // A supervision timeout/interruption already published the
                // unverified state. Keep its result and ownership intact, but
                // state plainly that the combined outcome is unverified.
                appendLogLocked("indeterminate: native rc=7 also unconfirmed; ownership retained");
                reason = "outcome unverified: " + (reason == null ? "native rc=7" : reason)
                        + "; explicit recovery required";
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
     * operation ID. A later phase will persist and authorize this boundary.</p>
     *
     * <p>Trusted-caller boundary: this method has no exported component, intent
     * action, broadcast, or binder path. It is a direct in-process API for a
     * trusted caller (tests exercise it here); the service/UI never wire it.</p>
     *
     * @return {@code true} only for the caller that actually claims this
     *         recovery; {@code false} if no such operation was current or
     *         another caller already claimed it.
     */
    public boolean recoverIndeterminate(String operationId) {
        Operation operation;
        RunResult currentResult;
        String currentReason;
        synchronized (lock) {
            if (state != State.INDETERMINATE || cleanupPending || activeOperation == null
                    || activeOperation.recoveryClaimed
                    || !Objects.equals(operationId, this.operationId)) {
                return false;
            }
            // Claim under the same lock as the check: concurrent callers with
            // the correct ID cannot both observe an unclaimed operation.
            activeOperation.recoveryClaimed = true;
            operation = activeOperation;
            currentResult = result;
            currentReason = reason;
        }
        // finish() keeps the indeterminate result, closes the admission and
        // lease exactly once, and publishes TERMINAL only after both closes.
        finish(operation, currentResult, currentReason);
        return true;
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

    /** Closes resources once, then publishes TERMINAL only after cleanup has finished. */
    private void finish(Operation operation, RunResult completed, String completedReason) {
        RunResult terminalResult;
        String terminalReason;
        String admissionFailure = null;
        String leaseFailure = null;
        synchronized (lock) {
            if (!isCurrentLocked(operation) || operation.terminal) {
                return;
            }
            operation.terminal = true;
            cleanupPending = true;
            if (state == State.INDETERMINATE && result != null) {
                terminalResult = result;
                terminalReason = reason;
            } else {
                terminalResult = completed;
                terminalReason = completedReason == null ? completed.getReason() : completedReason;
            }
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
