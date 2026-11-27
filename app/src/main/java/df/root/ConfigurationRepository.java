// Modified 2026-11-24 (DFRoot GZIF): serialize checked DE writes and fail closed on unverified configuration. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): add bounded cold-start readiness, persistence-proven reload recovery, and mode-bound live admissions. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): publish configuration state and diagnostics as one immutable snapshot. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): validate ERROR diagnostics with API-32-safe String.isBlank semantics. See NOTICE.
package df.root;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Process-wide owner of durable run configuration.
 *
 * <p>Only a successful store commit publishes a new confirmed generation. Failed
 * writes retain the previous confirmed snapshot, latch ERROR, attempt rollback on
 * the configuration worker, and prohibit run admission. Updates and reloads are
 * admitted under one lock and executed on one background thread.</p>
 */
public final class ConfigurationRepository {
    public enum State {
        SAVING,
        VERIFIED,
        ERROR
    }

    /** Immutable state/diagnostic pair published atomically to every observer. */
    static final class ConfigurationStatus {
        final State state;
        final String errorMessage;

        ConfigurationStatus(State state, String errorMessage) {
            this.state = Objects.requireNonNull(state, "state");
            this.errorMessage = Objects.requireNonNull(errorMessage, "errorMessage");
            if (state == State.ERROR && isBlank(errorMessage)) {
                throw new IllegalArgumentException("ERROR status requires a non-empty errorMessage");
            }
        }

        /** API-32-safe blank check matching String.isBlank() semantics; NBSP remains nonblank. */
        private static boolean isBlank(String value) {
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                if (!Character.isWhitespace(codePoint)) {
                    return false;
                }
                offset += Character.charCount(codePoint);
            }
            return true;
        }
    }

    public interface Listener {
        void onConfigurationChanged();
    }

    private static volatile ConfigurationRepository instance;

    private final ConfigurationStore store;
    private final Executor executor;
    private final Executor mainExecutor;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object lock = new Object();

    private volatile ConfigurationStatus status = new ConfigurationStatus(State.SAVING, "");
    private volatile RunConfig confirmedSnapshot;
    private boolean initializationComplete;
    private boolean writePending;
    private boolean reloadPending;
    private boolean frozen;
    private boolean started;
    private RunAdmission activeAdmission;

    ConfigurationRepository(ConfigurationStore store, Executor executor, Executor mainExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
    }

    /** Returns the single process-wide repository, backed by Device Protected Storage. */
    public static ConfigurationRepository get(Context context) {
        ConfigurationRepository current = instance;
        if (current != null) {
            return current;
        }
        synchronized (ConfigurationRepository.class) {
            if (instance == null) {
                // The store brackets every commit with PreferenceIntegrity's
                // write-ahead marker. Initialize the durable latch/gate before
                // the first load or migration can write anything.
                PreferenceIntegrity.init(context);
                instance = create(SharedPreferencesConfigurationStore.forDeviceProtectedStorage(context));
                instance.start();
            }
            return instance;
        }
    }

    /** Production factory using the named worker and main-looper Handler. */
    static ConfigurationRepository create(ConfigurationStore store) {
        return new ConfigurationRepository(store, newWorkerExecutor(), newMainExecutor());
    }

    static ExecutorService newWorkerExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dfroot-config");
            return thread;
        });
    }

    static Executor newMainExecutor() {
        return new HandlerExecutor();
    }

    static synchronized void setInstanceForTesting(ConfigurationRepository repository) {
        instance = repository;
    }

    static synchronized void resetForTesting() {
        if (instance != null && instance.executor instanceof ExecutorService) {
            ((ExecutorService) instance.executor).shutdownNow();
        }
        instance = null;
    }

    /** Starts the asynchronous load and legacy safe-off migration exactly once. */
    public void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
        }
        try {
            executor.execute(this::initialize);
        } catch (RuntimeException e) {
            synchronized (lock) {
                initializationComplete = true;
                status = new ConfigurationStatus(State.ERROR,
                        "configuration loader rejected: " + e.getMessage());
                writePending = false;
                reloadPending = false;
                lock.notifyAll();
            }
            notifyListeners();
        }
    }

    /**
     * Waits at most {@code timeoutMillis} for the initial load/migration result.
     * Returns the current state on timeout or interruption; callers must admit a
     * run only when the result is VERIFIED.
     */
    public State awaitInitialization(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be non-negative");
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long startedAt = System.nanoTime();
        synchronized (lock) {
            while (!initializationComplete) {
                ConfigurationStatus current = status;
                long remaining = timeoutNanos - (System.nanoTime() - startedAt);
                if (remaining <= 0) {
                    return current.state;
                }
                try {
                    lock.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return State.ERROR;
                }
            }
            return status.state;
        }
    }

    public State getState() {
        return status.state;
    }

    /** Last durably confirmed snapshot; ERROR does not replace it with a failed candidate. */
    public RunConfig getConfirmedSnapshot() {
        return confirmedSnapshot;
    }

    public boolean isWritePending() {
        synchronized (lock) {
            return writePending;
        }
    }

    public boolean isFrozen() {
        synchronized (lock) {
            return frozen;
        }
    }

    public String getErrorMessage() {
        return status.errorMessage;
    }

    /** Narrow test seam returning the exact immutable status visible to listeners. */
    ConfigurationStatus statusForTesting() {
        return status;
    }

    public String getRunAdmissionBlockReason() {
        synchronized (lock) {
            return baseAdmissionBlockReason();
        }
    }

    public String getRunAdmissionBlockReason(RunConfig.ExecutionMode mode) {
        return getRunAdmissionBlockReason(mode, null);
    }

    public String getRunAdmissionBlockReason(RunConfig.ExecutionMode mode,
                                             Long expectedGeneration) {
        Objects.requireNonNull(mode, "mode");
        synchronized (lock) {
            String reason = baseAdmissionBlockReason();
            if (!reason.isEmpty()) {
                return reason;
            }
            if (expectedGeneration != null
                    && confirmedSnapshot.getGeneration() != expectedGeneration) {
                return "configuration generation changed (expected " + expectedGeneration
                        + ", confirmed " + confirmedSnapshot.getGeneration() + ")";
            }
            try {
                confirmedSnapshot.validateFor(mode);
                return "";
            } catch (IllegalArgumentException e) {
                return "configuration policy: " + e.getMessage();
            }
        }
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Narrow test seam for verifying lifecycle cleanup without exposing the listener set. */
    boolean hasListener(Listener listener) {
        return listeners.contains(Objects.requireNonNull(listener, "listener"));
    }

    public boolean setManagerPackage(String managerPackage) {
        return enqueue(config -> config.withManagerPackage(managerPackage));
    }

    public boolean clearManager() {
        return setManagerPackage(null);
    }

    /**
     * Experimental soft reboot is unavailable in production. Refuse the ON
     * direction without touching the durable snapshot: no write, no generation
     * bump and no change to the acknowledgement version. OFF remains writable so
     * a previously persisted value can always be cleared.
     */
    public boolean setSoftReboot(boolean enabled) {
        if (enabled) {
            return false;
        }
        return enqueue(config -> config.withSoftReboot(false));
    }

    public boolean setDisableModules(boolean enabled) {
        return enqueue(config -> config.withDisableModules(enabled));
    }

    /** Rejects stale callers as well as frozen, pending, saving, and error states. */
    public boolean update(RunConfig desired, long expectedGeneration) {
        return enqueue(config -> {
            if (desired == null || desired.getGeneration() != expectedGeneration
                    || config.getGeneration() != expectedGeneration) {
                return null;
            }
            return desired;
        });
    }

    /**
     * Re-reads store state after ERROR and repeats checked safe-off migration.
     * A reload is published only after a checked full-snapshot write of a new
     * generation, because SharedPreferences may still hold an uncommitted candidate.
     * Serialized with writes and admissions; a rejected reload is not retried.
     */
    public boolean reload() {
        synchronized (lock) {
            if (status.state != State.ERROR || writePending || reloadPending || frozen
                    || activeAdmission != null) {
                return false;
            }
            reloadPending = true;
            status = new ConfigurationStatus(State.SAVING, "");
            notifyListeners();
        }
        try {
            executor.execute(this::reloadDurableState);
        } catch (RuntimeException e) {
            rejectOperation("configuration reload rejected: " + e.getMessage(), true);
        }
        return true;
    }

    /** Atomically freezes configuration and returns a mode-bound live run capability. */
    public RunAdmission acquireRunSnapshot(RunConfig.ExecutionMode mode) {
        return acquireRunSnapshot(mode, null);
    }

    /** Freezes only the generation confirmed when the caller requested the run. */
    public RunAdmission acquireRunSnapshot(RunConfig.ExecutionMode mode,
                                           Long expectedGeneration) {
        Objects.requireNonNull(mode, "mode");
        synchronized (lock) {
            if (frozen || writePending || status.state != State.VERIFIED || confirmedSnapshot == null) {
                return null;
            }
            if (expectedGeneration != null
                    && confirmedSnapshot.getGeneration() != expectedGeneration) {
                return null;
            }
            try {
                confirmedSnapshot.validateFor(mode);
            } catch (IllegalArgumentException e) {
                return null;
            }
            frozen = true;
            activeAdmission = new RunAdmission(this, confirmedSnapshot, mode);
            notifyListeners();
            return activeAdmission;
        }
    }

    /** Coordinator hook for freezing configuration before later lifecycle work. */
    public void freezeConfiguration() {
        synchronized (lock) {
            if (!frozen) {
                frozen = true;
                notifyListeners();
            }
        }
    }

    /** Releases a coordinator freeze only when no run admission token is active. */
    public void thawConfiguration() {
        synchronized (lock) {
            if (!frozen || activeAdmission != null) {
                return;
            }
            frozen = false;
            notifyListeners();
        }
    }

    void releaseRunSnapshot(RunAdmission admission) {
        synchronized (lock) {
            if (admission == null || admission.closed || admission != activeAdmission) {
                return;
            }
            admission.closed = true;
            activeAdmission = null;
            frozen = false;
            notifyListeners();
        }
    }

    private String baseAdmissionBlockReason() {
        ConfigurationStatus current = status;
        if (frozen) {
            return "configuration is frozen for an active operation";
        }
        if (writePending) {
            return "configuration write is pending";
        }
        if (current.state == State.ERROR) {
            return "configuration error: " + current.errorMessage;
        }
        if (current.state != State.VERIFIED || confirmedSnapshot == null) {
            return "configuration is not verified";
        }
        return "";
    }

    private boolean enqueue(UnaryOperator<RunConfig> transform) {
        RunConfig candidate;
        synchronized (lock) {
            if (frozen || writePending || reloadPending || status.state != State.VERIFIED
                    || confirmedSnapshot == null) {
                return false;
            }
            RunConfig desired;
            try {
                desired = transform.apply(confirmedSnapshot);
            } catch (RuntimeException e) {
                return false;
            }
            if (desired == null || desired.getGeneration() != confirmedSnapshot.getGeneration()) {
                return false;
            }
            // Defense in depth: no caller (setSoftReboot, update or a future
            // writer) may persist soft_reboot=1 in production, even with a
            // current acknowledgement. Refuse before any state change or write.
            if (desired.isSoftReboot()) {
                return false;
            }
            if (desired.isSoftReboot()
                    && desired.getSoftRebootAcknowledgementVersion()
                    != ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION) {
                return false;
            }
            try {
                candidate = desired.nextGeneration();
            } catch (IllegalStateException e) {
                return false;
            }
            writePending = true;
            status = new ConfigurationStatus(State.SAVING, "");
            notifyListeners();
        }

        try {
            executor.execute(() -> persist(candidate, confirmedSnapshot, "configuration write", false));
        } catch (RuntimeException e) {
            // Nothing was persisted, so there is nothing to roll back. Never do
            // store I/O here: this catch runs on the UI/requesting thread.
            rejectOperation("configuration executor rejected: " + e.getMessage(), false);
        }
        return true;
    }

    private void initialize() {
        loadAndPublish(true);
    }

    private void reloadDurableState() {
        loadAndPublish(false);
    }

    private void loadAndPublish(boolean initializing) {
        String operation = initializing ? "configuration load" : "configuration reload";
        try {
            RunConfig loaded = Objects.requireNonNull(store.load(), "store returned null");
            // Soft reboot is unavailable in production. Migrate any persisted
            // soft_reboot=1 to OFF on load/reload regardless of its acknowledgement
            // version: a legacy acknowledged value cannot be cleared through the
            // UI (every change is refused), so leaving it intact would strand
            // manual runs behind the artifact gate. The migration is a checked
            // write of a new generation behind the same write-ahead/quarantine
            // boundary; nothing is published as VERIFIED until that commit lands.
            if (loaded.isSoftReboot()) {
                RunConfig safeOff = loaded.withoutSoftRebootAcknowledgement().nextGeneration();
                RunConfig previous;
                synchronized (lock) {
                    previous = initializing ? null : confirmedSnapshot;
                    writePending = true;
                }
                persist(safeOff, previous,
                        initializing ? "safe-off migration" : operation, initializing);
                return;
            }
            if (!initializing) {
                // load() can observe SharedPreferences' in-memory candidate from a
                // failed commit. Prove a new full snapshot reached disk before VERIFIED.
                RunConfig candidate = loaded.nextGeneration();
                RunConfig previous;
                synchronized (lock) {
                    previous = confirmedSnapshot;
                    writePending = true;
                }
                persist(candidate, previous, operation, false);
                return;
            }
            synchronized (lock) {
                confirmedSnapshot = loaded;
                status = new ConfigurationStatus(State.VERIFIED, "");
                writePending = false;
                reloadPending = false;
                if (initializing) {
                    initializationComplete = true;
                    lock.notifyAll();
                }
            }
            notifyListeners();
        } catch (RuntimeException e) {
            failLoad(operation, e, initializing);
        }
    }

    private void persist(RunConfig candidate, RunConfig previous, String operation,
                         boolean initializing) {
        try {
            if (!store.write(candidate)) {
                persistFailure(candidate, previous, operation, initializing,
                        new IllegalStateException("commit returned false"));
                return;
            }
            synchronized (lock) {
                // The attempted generation becomes confirmed only after checked persistence.
                confirmedSnapshot = candidate;
                status = new ConfigurationStatus(State.VERIFIED, "");
                writePending = false;
                reloadPending = false;
                if (initializing) {
                    initializationComplete = true;
                    lock.notifyAll();
                }
            }
            notifyListeners();
        } catch (RuntimeException e) {
            persistFailure(candidate, previous, operation, initializing, e);
        }
    }

    private void persistFailure(RunConfig candidate, RunConfig previous, String operation,
                                boolean initializing, RuntimeException failure) {
        StringBuilder message = new StringBuilder(operation)
                .append(" failed: ").append(failure.getMessage());
        if (previous != null) {
            try {
                boolean rollback = store.write(previous);
                message.append(rollback ? "; rollback succeeded" : "; rollback failed");
            } catch (RuntimeException rollbackFailure) {
                message.append("; rollback threw: ").append(rollbackFailure.getMessage());
            }
        } else {
            message.append("; rollback unavailable (no confirmed snapshot)");
        }
        rejectOperation(message.toString(), initializing);
    }

    private void failLoad(String operation, RuntimeException failure, boolean initializing) {
        rejectOperation(operation + " failed: " + failure.getMessage(), initializing);
    }

    /** Latches failure without any store access; safe on a requesting thread. */
    private void rejectOperation(String message, boolean initializing) {
        synchronized (lock) {
            // Never publish a candidate or rollback as confirmed on this path.
            status = new ConfigurationStatus(State.ERROR, message);
            writePending = false;
            reloadPending = false;
            if (initializing) {
                initializationComplete = true;
                lock.notifyAll();
            }
        }
        notifyListeners();
    }

    private void notifyListeners() {
        try {
            mainExecutor.execute(() -> {
                for (Listener listener : listeners) {
                    try {
                        listener.onConfigurationChanged();
                    } catch (RuntimeException ignored) {
                        // One broken observer must not suppress notifications to the others.
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // Notification delivery is observational; it must never alter durability.
        }
    }

    /** Scoped, mode-bound admission capability; closing it releases the configuration freeze. */
    public static final class RunAdmission implements AutoCloseable {
        private final ConfigurationRepository owner;
        private final RunConfig snapshot;
        private final RunConfig.ExecutionMode executionMode;
        private boolean closed;

        private RunAdmission(ConfigurationRepository owner, RunConfig snapshot,
                             RunConfig.ExecutionMode executionMode) {
            this.owner = owner;
            this.snapshot = snapshot;
            this.executionMode = executionMode;
        }

        public RunConfig getSnapshot() {
            requireActive();
            return snapshot;
        }

        public RunConfig.ExecutionMode getExecutionMode() {
            requireActive();
            return executionMode;
        }

        public boolean isActive() {
            synchronized (owner.lock) {
                return !closed && owner.activeAdmission == this;
            }
        }

        private void requireActive() {
            synchronized (owner.lock) {
                if (closed || owner.activeAdmission != this) {
                    throw new IllegalStateException("run admission is closed or inactive");
                }
            }
        }

        @Override
        public void close() {
            owner.releaseRunSnapshot(this);
        }
    }

    private static final class HandlerExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }
}
