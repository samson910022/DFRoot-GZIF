// Modified 2026-11-25 (DFRoot GZIF): prove the durable attempt barrier survives process death and gates every admission. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): cover idempotent clears and bounded, non-dropping initialization. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class RunCoordinatorAttemptTest {
    private static final long BOOT_COUNT = 50L;
    private static final RunConfig CONFIG = new RunConfig(
            SuManager.NEXT.getPackageName(), false, false, 0, 17L);

    private ConfigurationRepository repository;
    private ScriptedEngine engine;
    private ControllableStore store;
    private MutableReader reader;

    @Before
    public void setUp() {
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        repository = new ConfigurationRepository(new Store(), Runnable::run, Runnable::run);
        repository.start();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        engine = new ScriptedEngine();
        store = new ControllableStore();
        reader = new MutableReader(BOOT_COUNT);
    }

    @After
    public void tearDown() {
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
    }

    @Test
    public void attemptIsPublishedBeforeTheEngineAndClearedOnlyAfterConfirmedTerminal() throws Exception {
        engine.inspection = call -> {
            OperationAttempt attempt = assertStoreAttempt(call.operationId);
            assertEquals(call.mode, attempt.getMode());
            assertEquals(BOOT_COUNT, attempt.getBootCount());
            assertTrue(attempt.isUnconfirmed());
        };
        engine.results.add(NativeResultMapper.map(0));

        RunCoordinator coordinator = initializedCoordinator();
        FakeLease lease = new FakeLease();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                lease).isAccepted());

        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertTrue(coordinator.getSnapshot().getResult().isSuccess());
        assertNull(store.load());
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());

        engine.results.add(NativeResultMapper.map(0));
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                new FakeLease()).isAccepted());
        assertEquals(2, engine.calls.get());
    }

    @Test
    public void processRestartWithUnconfirmedAttemptRejectsManualAndBoot() throws Exception {
        engine.results.add(NativeResultMapper.map(7));
        RunCoordinator first = initializedCoordinator();
        assertTrue(first.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        assertEquals(RunCoordinator.State.INDETERMINATE, first.getSnapshot().getState());
        OperationAttempt persisted = assertStoreAttempt(first.getSnapshot().getOperationId());

        // New coordinator process, same durable store and boot count.
        RunCoordinator restarted = initializedCoordinator();
        FakeLease manualLease = new FakeLease();
        RunCoordinator.Submission manual = restarted.submit(context(),
                RunConfig.ExecutionMode.MANUAL, manualLease);
        assertFalse(manual.isAccepted());
        assertTrue(manual.getReason(), manual.getReason().contains("prior process"));
        assertTrue(manual.getReason(), manual.getReason().contains("same boot count " + BOOT_COUNT));

        FakeLease bootLease = new FakeLease();
        RunCoordinator.Submission boot = restarted.submit(context(),
                RunConfig.ExecutionMode.UNATTENDED_BOOT, bootLease);
        assertFalse(boot.isAccepted());
        assertTrue(boot.getReason(), boot.getReason().contains("explicit recovery required"));
        assertEquals(persisted.getOperationId(), boot.getOperationId());
        assertEquals(0, manualLease.closeCount.get());
        assertEquals(0, bootLease.closeCount.get());
        assertEquals(1, engine.calls.get());
        assertEquals(RunCoordinator.State.IDLE, restarted.getSnapshot().getState());
    }

    @Test
    public void higherBootCountNeverAuthorizesReplayOfAPersistedAttempt() throws Exception {
        String priorId = "prior-process-run";
        store.seed(new OperationAttempt(priorId, RunConfig.ExecutionMode.UNATTENDED_BOOT,
                BOOT_COUNT, true, "unconfirmed before process death"));
        reader.value = BOOT_COUNT + 1;

        RunCoordinator restarted = initializedCoordinator();
        FakeLease lease = new FakeLease();
        RunCoordinator.Submission rejected = restarted.submit(context(),
                RunConfig.ExecutionMode.MANUAL, lease);

        assertFalse(rejected.isAccepted());
        assertTrue(rejected.getReason(), rejected.getReason().contains("new boot count "
                + (BOOT_COUNT + 1)));
        assertTrue(rejected.getReason(), rejected.getReason().contains("recorded " + BOOT_COUNT));
        assertTrue(rejected.getReason(), rejected.getReason().contains("explicit recovery required"));
        assertEquals(0, lease.closeCount.get());
        assertEquals(0, engine.calls.get());
    }

    @Test
    public void earlierBootCountIsDiagnosticOnlyAndStillRejects() throws Exception {
        String priorId = "prior-process-run";
        store.seed(new OperationAttempt(priorId, RunConfig.ExecutionMode.MANUAL,
                BOOT_COUNT, true, "unconfirmed before process death"));
        reader.value = BOOT_COUNT - 1;

        RunCoordinator.Submission rejected = initializedCoordinator().submit(context(),
                RunConfig.ExecutionMode.MANUAL, new FakeLease());

        assertFalse(rejected.isAccepted());
        assertTrue(rejected.getReason(), rejected.getReason().contains(
                "earlier boot count " + (BOOT_COUNT - 1)));
        assertTrue(rejected.getReason(), rejected.getReason().contains("recorded " + BOOT_COUNT));
        assertTrue(rejected.getReason(), rejected.getReason().contains(
                "explicit recovery required"));
        assertEquals(0, engine.calls.get());
    }

    @Test
    public void falseCheckedClearKeepsOwnershipIndeterminateWithoutConfirmedTerminal() throws Exception {
        store.clearResult = false;
        engine.results.add(NativeResultMapper.map(0));
        RunCoordinator coordinator = initializedCoordinator();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());

        RunCoordinator.Snapshot held = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.INDETERMINATE, held.getState());
        assertNotNull(held.getResult());
        assertTrue(held.getReason(), held.getReason().contains("terminal confirmation"));
        assertTrue(held.getLog().stream().anyMatch(entry ->
                entry.getMessage().contains("ownership retained")));
        assertNotNull(store.load());
        assertTrue(store.load().isUnconfirmed());
        assertTrue(repository.isFrozen());
        assertFalse(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
    }

    @Test
    public void failedClearThenRecoveryClosesResourcesExactlyOnceAndAllowsNextSubmit() throws Exception {
        store.clearResult = false;
        engine.results.add(NativeResultMapper.map(0));
        RunCoordinator coordinator = initializedCoordinator();
        FakeLease lease = new FakeLease();
        AtomicReference<ConfigurationRepository.RunAdmission> admitted = new AtomicReference<>();
        engine.inspection = call -> admitted.set(call.admission);

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                lease).isAccepted());
        String id = coordinator.getSnapshot().getOperationId();
        assertEquals(RunCoordinator.State.INDETERMINATE, coordinator.getSnapshot().getState());
        assertEquals(0, lease.closeCount.get());
        assertTrue(repository.isFrozen());
        assertTrue(admitted.get().isActive());

        // The later clear succeeds; finish() must not clear a second time or
        // the already-removed CAS would strand lease/admission cleanup.
        store.clearResult = true;
        assertTrue(coordinator.recoverIndeterminate(id));

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.TERMINAL, terminal.getState());
        assertEquals(1, lease.closeCount.get());
        assertFalse(admitted.get().isActive());
        assertFalse(repository.isFrozen());
        assertNull(store.load());

        engine.results.add(NativeResultMapper.map(0));
        FakeLease second = new FakeLease();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                second).isAccepted());
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(1, second.closeCount.get());
        assertEquals(2, engine.calls.get());
    }

    @Test
    public void throwingCheckedClearKeepsOwnershipIndeterminate() throws Exception {
        store.clearThrows = new IllegalStateException("clear disk failure");
        engine.results.add(NativeResultMapper.map(0));
        RunCoordinator coordinator = initializedCoordinator();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());

        assertEquals(RunCoordinator.State.INDETERMINATE, coordinator.getSnapshot().getState());
        assertTrue(coordinator.getSnapshot().getReason().contains("clear disk failure"));
        assertNotNull(store.load());
        assertTrue(repository.isFrozen());
    }

    @Test
    public void indeterminateOutcomeExceptionAndTimeoutNeverClearThePersistedAttempt() throws Exception {
        // Native rc=7 keeps the barrier until explicit recovery.
        engine.results.add(NativeResultMapper.map(7));
        RunCoordinator unverified = initializedCoordinator();
        assertTrue(unverified.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        assertNotNull(store.load());
        assertTrue(store.load().isUnconfirmed());

        // Explicit recovery releases the earlier freeze before another attempt.
        assertTrue(unverified.recoverIndeterminate(unverified.getSnapshot().getOperationId()));
        assertFalse(repository.isFrozen());

        // A supervision timeout with a later exit stays indeterminate in detail.
        ScriptedEngine timeoutEngine = new ScriptedEngine();
        timeoutEngine.inspection = call -> call.events.onTimeout();
        timeoutEngine.results.add(NativeResultMapper.map(0));
        RunCoordinator timedOut = coordinator(timeoutEngine);
        timedOut.initialize();
        assertTrue(timedOut.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        assertEquals(RunResult.Detail.PROCESS_TIMEOUT,
                timedOut.getSnapshot().getResult().getDetail());
        assertNotNull(store.load());

        // An engine exception is also never a confirmed terminal result.
        assertTrue(store.clear(timedOut.getSnapshot().getOperationId(), true));
        ScriptedEngine failing = new ScriptedEngine();
        failing.failure = new IllegalStateException("engine exploded");
        RunCoordinator failed = coordinator(failing);
        failed.initialize();
        assertTrue(failed.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        assertEquals(RunResult.Detail.EXCEPTION, failed.getSnapshot().getResult().getDetail());
        assertNotNull(store.load());
    }

    @Test
    public void recoverIndeterminateNeedsTheCheckedClearBeforeReleasingResources() throws Exception {
        engine.results.add(NativeResultMapper.map(7));
        RunCoordinator coordinator = initializedCoordinator();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        String id = coordinator.getSnapshot().getOperationId();
        assertFalse(coordinator.recoverIndeterminate("wrong-id"));

        store.clearResult = false;
        assertFalse(coordinator.recoverIndeterminate(id));
        assertEquals(RunCoordinator.State.INDETERMINATE, coordinator.getSnapshot().getState());
        assertTrue(repository.isFrozen());
        assertNotNull(store.load());

        store.clearResult = true;
        assertTrue(coordinator.recoverIndeterminate(id));
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertFalse(repository.isFrozen());
        assertNull(store.load());
        assertFalse(coordinator.recoverIndeterminate(id));
    }

    @Test
    public void recoverPersistedAttemptRequiresAcknowledgementAndNeverRetries() throws Exception {
        String priorId = "prior-process-run";
        store.seed(new OperationAttempt(priorId, RunConfig.ExecutionMode.MANUAL,
                BOOT_COUNT, true, "unconfirmed before process death"));
        RunCoordinator restarted = initializedCoordinator();

        assertFalse(restarted.recoverPersistedAttempt(priorId, false));
        assertFalse(restarted.recoverPersistedAttempt("wrong-id", true));
        assertNotNull(store.load());
        assertEquals(0, engine.calls.get());

        store.clearResult = false;
        assertFalse(restarted.recoverPersistedAttempt(priorId, true));
        assertNotNull(store.load());

        store.clearResult = true;
        assertTrue(restarted.recoverPersistedAttempt(priorId, true));
        assertNull(store.load());
        assertEquals(0, engine.calls.get());
        assertEquals(RunCoordinator.State.IDLE, restarted.getSnapshot().getState());
        assertFalse(restarted.recoverPersistedAttempt(priorId, true));

        // Only a new explicit submission may start work after acknowledgement.
        engine.results.add(NativeResultMapper.map(0));
        assertTrue(restarted.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        assertEquals(1, engine.calls.get());
    }

    @Test
    public void submitFailsClosedBeforeInitializationAndAfterFailedLoad() throws Exception {
        RunCoordinator notInitialized = coordinator(engine);
        RunCoordinator.Submission premature = notInitialized.submit(context(),
                RunConfig.ExecutionMode.MANUAL, new FakeLease());
        assertFalse(premature.isAccepted());
        assertTrue(premature.getReason().contains("initialization is not complete"));
        assertEquals(0, engine.calls.get());

        engine.results.add(NativeResultMapper.map(0));
        notInitialized.initialize();
        assertTrue(notInitialized.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());

        ControllableStore broken = new ControllableStore();
        broken.loadFailure = new IllegalStateException("prefs unreadable");
        RunCoordinator failedLoad = new RunCoordinator(repository, Runnable::run, Runnable::run,
                engine, 0L, broken, reader, Runnable::run);
        failedLoad.initialize();
        RunCoordinator.Submission rejected = failedLoad.submit(context(),
                RunConfig.ExecutionMode.MANUAL, new FakeLease());
        assertFalse(rejected.isAccepted());
        assertTrue(rejected.getReason().contains("load failed"));
        assertEquals(1, engine.calls.get());
    }

    @Test
    public void initializationRunsOnTheInjectedExecutorAndAdmissionWaitsForIt() throws Exception {
        QueueExecutor attemptExecutor = new QueueExecutor();
        RunCoordinator coordinator = new RunCoordinator(repository, Runnable::run, Runnable::run,
                engine, 0L, store, reader, attemptExecutor);
        coordinator.initialize();

        assertEquals(0, store.loadCalls);
        assertEquals(1, attemptExecutor.size());
        assertFalse(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        assertEquals(0, engine.calls.get());

        attemptExecutor.runAll();
        assertEquals(1, store.loadCalls);
        engine.results.add(NativeResultMapper.map(0));
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
    }

    @Test
    public void inFlightInitializationDoesNotDropFirstRequestOrWasteBootClaim() throws Exception {
        // A boot claim was already consumed before the service was started.
        BootClaimFakes.MemoryStore claims = new BootClaimFakes.MemoryStore();
        assertTrue(claims.claim(BOOT_COUNT));

        CountDownLatch initEntered = new CountDownLatch(1);
        CountDownLatch releaseInit = new CountDownLatch(1);
        CountDownLatch engineEntered = new CountDownLatch(1);
        Executor deferredInit = command -> {
            Thread thread = new Thread(() -> {
                initEntered.countDown();
                try {
                    releaseInit.await(5L, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                command.run();
            }, "test-attempt-init");
            thread.setDaemon(true);
            thread.start();
        };
        engine.inspection = call -> engineEntered.countDown();
        engine.results.add(NativeResultMapper.map(0));
        RunCoordinator coordinator = new RunCoordinator(repository, Runnable::run, Runnable::run,
                engine, 0L, store, reader, deferredInit);
        coordinator.initialize();
        assertTrue(initEntered.await(5L, TimeUnit.SECONDS));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<RunCoordinator.Submission> pending = caller.submit(() -> coordinator.submit(
                    context(), RunConfig.ExecutionMode.UNATTENDED_BOOT, new FakeLease()));
            // The request waits outside the lock; it is not rejected just
            // because the off-main load is still in flight.
            assertFalse(engineEntered.await(150L, TimeUnit.MILLISECONDS));
            assertFalse(pending.isDone());

            releaseInit.countDown();
            assertTrue(pending.get(5L, TimeUnit.SECONDS).isAccepted());
            assertTrue(engineEntered.await(5L, TimeUnit.SECONDS));
            assertEquals(1, engine.calls.get());
        } finally {
            releaseInit.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5L, TimeUnit.SECONDS));
        }

        // The boot claim is still the single one consumed; no replay or retry.
        assertEquals(1, claims.attempts.size());
        assertEquals(BOOT_COUNT, claims.lastClaimed());
        assertNull(store.load());
    }

    @Test
    public void initializeDispatchRejectionFailsClosedWithoutEngineCall() throws Exception {
        RunCoordinator coordinator = new RunCoordinator(repository, Runnable::run, Runnable::run,
                engine, 0L, store, reader, command -> {
                    throw new RejectedExecutionException("init dispatch rejected");
                });
        coordinator.initialize();

        RunCoordinator.Submission rejected = coordinator.submit(context(),
                RunConfig.ExecutionMode.MANUAL, new FakeLease());
        assertFalse(rejected.isAccepted());
        assertTrue(rejected.getReason(), rejected.getReason().contains("dispatch failed"));
        assertEquals(0, engine.calls.get());
    }

    @Test
    public void staleArtifactIdentityCannotOverwriteTheAdmittedRunConfig() throws Exception {
        Path directory = Files.createTempDirectory("dfroot-attempt-artifact-");
        try {
            RunPreflight.Plan plan = new RunPreflight.Plan(SuManager.NEXT,
                    new File("loader"), new File("bootstrap"), new File("native"));
            store.seed(new OperationAttempt("admitted-id", RunConfig.ExecutionMode.MANUAL,
                    BOOT_COUNT, true, "admitted"));
            publish(directory, "admitted-id");
            String first = artifact(directory);
            assertTrue(first, first.contains("operation_id=admitted-id\n"));

            assertThrows(IOException.class, () -> publish(directory, "other-id"));
            assertThrows(IOException.class, () -> publish(directory, null));
            assertEquals(first, artifact(directory));

            store.clear("admitted-id", true);
            assertThrows(IOException.class, () -> publish(directory, "admitted-id"));
            assertEquals(first, artifact(directory));
        } finally {
            Path artifact = directory.resolve(RunConfigArtifact.RUN_CONFIG);
            Files.deleteIfExists(artifact);
            Files.deleteIfExists(directory);
        }
    }

    private void publish(Path directory, String operationId) throws IOException {
        RunConfigArtifact.publishAuthorized(directory, operationId, CONFIG,
                new RunPreflight.Plan(SuManager.NEXT, new File("loader"),
                        new File("bootstrap"), new File("native")),
                RunConfig.ExecutionMode.MANUAL, store,
                "test-fingerprint", "test-kernel", RunConfigArtifact.FileSystem.DEFAULT);
    }

    private static String artifact(Path directory) throws IOException {
        return new String(Files.readAllBytes(directory.resolve(RunConfigArtifact.RUN_CONFIG)),
                StandardCharsets.UTF_8);
    }

    private OperationAttempt assertStoreAttempt(String operationId) {
        OperationAttempt attempt;
        try {
            attempt = store.load();
        } catch (OperationAttemptStore.Failure e) {
            throw new AssertionError(e);
        }
        assertNotNull(attempt);
        assertEquals(operationId, attempt.getOperationId());
        return attempt;
    }

    private RunCoordinator initializedCoordinator() {
        RunCoordinator coordinator = coordinator(engine);
        coordinator.initialize();
        return coordinator;
    }

    private RunCoordinator coordinator(RunCoordinator.Engine chosenEngine) {
        return new RunCoordinator(repository, Runnable::run, Runnable::run, chosenEngine,
                0L, store, reader, Runnable::run);
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static final class MutableReader implements BootCountReader {
        Long value;

        MutableReader(Long value) {
            this.value = value;
        }

        @Override
        public Long read() {
            return value;
        }
    }

    private static final class QueueExecutor implements java.util.concurrent.Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        int size() {
            return tasks.size();
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.pollFirst()) != null) {
                task.run();
            }
        }
    }

    /** Delegating store with deterministic false/throw injection on checked operations. */
    private static final class ControllableStore implements OperationAttemptStore {
        private final OperationAttemptStore delegate = OperationAttemptStore.inMemory();
        boolean publishResult = true;
        boolean clearResult = true;
        int loadCalls;
        RuntimeException loadFailure;
        RuntimeException clearThrows;

        void seed(OperationAttempt attempt) throws Exception {
            delegate.publish(attempt);
        }

        boolean clear(String operationId, boolean ignored) throws Exception {
            return delegate.clear(operationId);
        }

        @Override
        public OperationAttempt load() throws Failure {
            loadCalls++;
            if (loadFailure != null) {
                throw new Failure("load failed", loadFailure);
            }
            return delegate.load();
        }

        @Override
        public boolean publish(OperationAttempt attempt) throws Failure {
            if (!publishResult) {
                return false;
            }
            return delegate.publish(attempt);
        }

        @Override
        public boolean clear(String operationId) throws Failure {
            if (clearThrows != null) {
                throw new Failure("clear failed", clearThrows);
            }
            if (!clearResult) {
                return false;
            }
            return delegate.clear(operationId);
        }
    }

    private static final class ScriptedEngine implements RunCoordinator.Engine {
        final Deque<RunResult> results = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<String> ids = new ArrayList<>();
        Consumer<Call> inspection;
        RuntimeException failure;

        @Override
        public RunResult run(Context context, ConfigurationRepository.RunAdmission admission,
                             RunConfig.ExecutionMode mode, String operationId,
                             RunEventListener events, IReporter reporter) throws Exception {
            calls.incrementAndGet();
            ids.add(operationId);
            if (inspection != null) {
                inspection.accept(new Call(operationId, mode, admission, events));
            }
            if (failure != null) {
                throw failure;
            }
            RunResult next = results.pollFirst();
            return next != null ? next : NativeResultMapper.map(0);
        }

        static final class Call {
            final String operationId;
            final RunConfig.ExecutionMode mode;
            final ConfigurationRepository.RunAdmission admission;
            final RunEventListener events;

            Call(String operationId, RunConfig.ExecutionMode mode,
                 ConfigurationRepository.RunAdmission admission, RunEventListener events) {
                this.operationId = operationId;
                this.mode = mode;
                this.admission = admission;
                this.events = events;
            }
        }
    }

    private static final class FakeLease implements ResourceLease {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class Store implements ConfigurationStore {
        @Override
        public RunConfig load() {
            return CONFIG;
        }

        @Override
        public boolean write(RunConfig updated) {
            return true;
        }
    }
}
