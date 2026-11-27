// Modified 2026-11-25 (DFRoot GZIF): prove the durable attempt barrier survives process death and gates every admission. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): cover idempotent clears and bounded, non-dropping initialization. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): every unverified outcome holds the barrier; only confirmed codes clear it. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin post-process-death-only persisted recovery and checked clears. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin honest unverified causes and no clear/admission from unconfirmed results. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): prove an OFF transition during a blocked publish yields a proven pre-effect precheck failure. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): prove a correlated rejection cannot clear the durable attempt of an active operation. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
    public void correlatedRejectionDuringAnActiveOperationKeepsTheDurableAttempt() throws Exception {
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = new RunCoordinator(repository, queuedWorker, Runnable::run,
                engine, 0L, store, reader, Runnable::run);
        coordinator.initialize();
        FakeLease lease = new FakeLease();
        engine.results.add(NativeResultMapper.map(0));

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease,
                "ui-request-A").isAccepted());
        RunCoordinator.Snapshot admitted = coordinator.getSnapshot();
        OperationAttempt barrier = assertStoreAttempt(admitted.getOperationId());

        coordinator.noteRequestOutcome("ui-request-B",
                RunCoordinator.RequestOutcome.REJECTED, "operation A is active");

        RunCoordinator.Snapshot rejection = coordinator.getSnapshot();
        assertNotSame(admitted, rejection);
        assertEquals("ui-request-B", rejection.getRequestId());
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, rejection.getRequestOutcome());
        assertEquals(admitted.getState(), rejection.getState());
        assertSame(admitted.getResult(), rejection.getResult());
        assertEquals(barrier.getOperationId(), assertStoreAttempt(
                admitted.getOperationId()).getOperationId());
        assertEquals(0, lease.closeCount.get());
        assertEquals(0, engine.calls.get());

        // The admitted operation still runs exactly once to its confirmed result.
        queuedWorker.runAll();
        assertEquals(1, engine.calls.get());
        assertNull(store.load());
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
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

    /**
     * The acknowledged persisted-attempt API is post-process-death only. A
     * coordinator that published its own barrier in this process must not use
     * it to escape that barrier; there is no in-app recovery UI for a user.
     */
    @Test
    public void persistedRecoveryRefusesABarrierPublishedByThisProcess() throws Exception {
        String priorId = "prior-process-run";
        store.seed(new OperationAttempt(priorId, RunConfig.ExecutionMode.MANUAL,
                BOOT_COUNT, true, "unconfirmed before process death"));
        RunCoordinator restarted = initializedCoordinator();

        Field published = RunCoordinator.class.getDeclaredField("attemptPublishedInThisProcess");
        published.setAccessible(true);
        assertFalse(published.getBoolean(restarted));
        published.setBoolean(restarted, true);

        assertFalse(restarted.recoverPersistedAttempt(priorId, true));
        assertNotNull(store.load());
        assertEquals(0, engine.calls.get());
        assertFalse(restarted.getSnapshot().getLog().stream().anyMatch(entry ->
                entry.getMessage().contains("cleared by trusted-caller")));

        published.setBoolean(restarted, false);
        assertTrue(restarted.recoverPersistedAttempt(priorId, true));
        assertNull(store.load());
        assertEquals(0, engine.calls.get());
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
    public void corruptAttemptLoadFailureBlocksCoordinatorAdmission() throws Exception {
        ControllableStore corrupt = new ControllableStore();
        corrupt.loadError =
                new OperationAttemptStore.Failure("attempt record is incomplete");
        RunCoordinator coordinator = new RunCoordinator(repository, Runnable::run, Runnable::run,
                engine, 0L, corrupt, reader, Runnable::run);
        coordinator.initialize();

        RunCoordinator.Submission rejected = coordinator.submit(context(),
                RunConfig.ExecutionMode.MANUAL, new FakeLease());
        assertFalse(rejected.isAccepted());
        assertTrue(rejected.getReason(), rejected.getReason().contains("load failed"));
        assertTrue(rejected.getReason(),
                rejected.getReason().contains("attempt record is incomplete"));
        assertEquals(0, engine.calls.get());
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

    /**
     * The final gate passed before publish, then the owner turned Start at Boot
     * OFF while the harmless durable commit was blocked. A delayed worker must
     * never reach the engine: the pre-engine recheck turns this into a proven
     * pre-effect rejection whose checked clear is allowed.
     */
    @Test
    public void offDuringBlockedPublishNeverStartsEngineAndClearsBarrierAsPreEffect()
            throws Exception {
        BlockingPublishStore blockingStore = new BlockingPublishStore();
        MutableAuthorization authorization = new MutableAuthorization(true);
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = new RunCoordinator(repository, queuedWorker, Runnable::run,
                engine, 0L, blockingStore, reader, Runnable::run, authorization);
        coordinator.initialize();
        engine.results.add(NativeResultMapper.map(0));
        FakeLease lease = new FakeLease();

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<RunCoordinator.Submission> submitted = caller.submit(() -> coordinator.submit(
                    context(), RunConfig.ExecutionMode.UNATTENDED_BOOT, lease));
            assertTrue(blockingStore.publishEntered.await(5L, TimeUnit.SECONDS));
            authorization.enabled = false;
            blockingStore.releasePublish.countDown();
            assertTrue(submitted.get(5L, TimeUnit.SECONDS).isAccepted());

            // Delayed worker queue: the engine must not run at all.
            queuedWorker.runAll();

            RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
            assertEquals(RunCoordinator.State.TERMINAL, terminal.getState());
            assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
            assertEquals(RunCoordinator.UNATTENDED_AUTH_CHANGED, terminal.getReason());
            assertEquals(0, engine.calls.get());
            assertEquals("proven pre-effect clear must run once", 1, blockingStore.clearCalls);
            assertNull(blockingStore.load());
            assertEquals(1, lease.closeCount.get());
            assertFalse(repository.isFrozen());
            assertEquals("no automatic replay", 0, queuedWorker.size());

            // Only a NEW explicit submission may start work after re-authorization.
            authorization.enabled = true;
            engine.results.add(NativeResultMapper.map(0));
            FakeLease second = new FakeLease();
            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                    second).isAccepted());
            queuedWorker.runAll();
            assertEquals(1, second.closeCount.get());
            assertEquals("explicit second submission is the only engine call",
                    1, engine.calls.get());
        } finally {
            blockingStore.releasePublish.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5L, TimeUnit.SECONDS));
        }
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

    /**
     * Fake observation failures (rc7, unknown exits) and an unconfirmed engine
     * exception must all keep the durable barrier, the lease, the config freeze
     * and single-flight ownership, and must never publish a TERMINAL snapshot.
     */
    @Test
    public void everyUnverifiedOutcomeRetainsBarrierLeaseAndFreeze() throws Exception {
        List<RunResult> unverified = Arrays.asList(
                NativeResultMapper.map(7),
                NativeResultMapper.map(255),
                NativeResultMapper.map(-1),
                RunResult.exception(new IllegalStateException("reporter terminated unexpectedly")));
        for (RunResult outcome : unverified) {
            ConfigurationRepository localRepository = new ConfigurationRepository(
                    new Store(), Runnable::run, Runnable::run);
            localRepository.start();
            ControllableStore localStore = new ControllableStore();
            ScriptedEngine localEngine = new ScriptedEngine();
            localEngine.results.add(outcome);
            AtomicReference<ConfigurationRepository.RunAdmission> admitted =
                    new AtomicReference<>();
            localEngine.inspection = call -> admitted.set(call.admission);
            RunCoordinator coordinator = new RunCoordinator(localRepository, Runnable::run,
                    Runnable::run, localEngine, 0L, localStore, new MutableReader(BOOT_COUNT),
                    Runnable::run);
            coordinator.initialize();
            List<RunCoordinator.State> observed = new ArrayList<>();
            coordinator.addListener(snapshot -> observed.add(snapshot.getState()));
            FakeLease lease = new FakeLease();

            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                    lease).isAccepted());
            RunCoordinator.Snapshot held = coordinator.getSnapshot();

            assertEquals("unverified outcome " + outcome.getType(),
                    RunCoordinator.State.INDETERMINATE, held.getState());
            assertTrue(held.getReason().contains("outcome unverified"));
            assertTrue(outcome.isOutcomeUnverified());
            assertFalse(observed.contains(RunCoordinator.State.TERMINAL));
            OperationAttempt persisted = assertStoreAttempt(localStore, held.getOperationId());
            assertTrue(persisted.isUnconfirmed());
            assertEquals(0, lease.closeCount.get());
            assertTrue(admitted.get().isActive());
            assertTrue(localRepository.isFrozen());
            assertFalse(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                    new FakeLease()).isAccepted());

            // The barrier is released only by an explicit, checked recovery.
            assertTrue(coordinator.recoverIndeterminate(held.getOperationId()));
            assertEquals(1, lease.closeCount.get());
            assertFalse(admitted.get().isActive());
            assertFalse(localRepository.isFrozen());
            assertNull(localStore.load());
        }
    }

    /**
     * Fake unconfirmed outcomes only (no privileged payload): each published
     * reason must name exactly the observed cause. An unknown exit, an engine
     * exception, or a conflict-shaped rc7 observation must never clear the
     * durable barrier, close the lease, publish TERMINAL, or admit a second run.
     */
    @Test
    public void unverifiedReasonsNameOnlyTheObservedCauseAndNeverAuthorizeAdmission()
            throws Exception {
        final String rc7 = "outcome unverified: native terminal publication was not confirmed "
                + "(native rc=7); the worker may still be running; explicit recovery required";
        final String unknown = "outcome unverified: native exit code was not recognized; "
                + "completion could not be confirmed; explicit recovery required";
        final String exception = "outcome unverified: engine call failed before a confirmed "
                + "result; external effects remain unproven "
                + "(IllegalStateException: reporter terminated unexpectedly); "
                + "explicit recovery required";
        final String timeout = "child process timed out; outcome remains indeterminate until "
                + "actual exit";
        RunResult conflictShapedRc7 = new RunResult(ExploitRunner.RESULT_INDETERMINATE,
                RunResult.Type.INDETERMINATE, RunResult.Detail.NATIVE_EXIT,
                "conflicting terminal markers; completion unverified; "
                        + "native rc=7 indeterminate publication");
        List<Object[]> cases = Arrays.asList(
                new Object[] {NativeResultMapper.map(7), rc7, false},
                new Object[] {conflictShapedRc7, rc7, false},
                new Object[] {NativeResultMapper.map(73), unknown, false},
                new Object[] {RunResult.exception(
                        new IllegalStateException("reporter terminated unexpectedly")),
                        exception, false},
                new Object[] {NativeResultMapper.map(7),
                        "outcome unverified: " + timeout + "; " + rc7.substring(
                                "outcome unverified: ".length()), true},
                new Object[] {NativeResultMapper.map(73),
                        "outcome unverified: " + timeout + "; " + unknown.substring(
                                "outcome unverified: ".length()), true});
        for (Object[] testCase : cases) {
            RunResult outcome = (RunResult) testCase[0];
            String expectedReason = (String) testCase[1];
            boolean timeoutFirst = (Boolean) testCase[2];

            ConfigurationRepository localRepository = new ConfigurationRepository(
                    new Store(), Runnable::run, Runnable::run);
            localRepository.start();
            ControllableStore localStore = new ControllableStore();
            ScriptedEngine localEngine = new ScriptedEngine();
            localEngine.results.add(outcome);
            AtomicReference<ConfigurationRepository.RunAdmission> admitted =
                    new AtomicReference<>();
            localEngine.inspection = call -> {
                admitted.set(call.admission);
                if (timeoutFirst) {
                    call.events.onTimeout();
                }
            };
            RunCoordinator coordinator = new RunCoordinator(localRepository, Runnable::run,
                    Runnable::run, localEngine, 0L, localStore, new MutableReader(BOOT_COUNT),
                    Runnable::run);
            coordinator.initialize();
            List<RunCoordinator.State> observed = new ArrayList<>();
            coordinator.addListener(snapshot -> observed.add(snapshot.getState()));
            FakeLease lease = new FakeLease();

            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                    lease).isAccepted());
            RunCoordinator.Snapshot held = coordinator.getSnapshot();

            assertEquals(expectedReason, held.getReason());
            assertEquals(RunCoordinator.State.INDETERMINATE, held.getState());
            assertFalse(observed.contains(RunCoordinator.State.TERMINAL));
            assertEquals("unverified result cleared the durable attempt: " + outcome.getType(),
                    0, localStore.clearCalls);
            OperationAttempt persisted = assertStoreAttempt(localStore, held.getOperationId());
            assertTrue(persisted.isUnconfirmed());
            assertEquals("unverified result closed the lease: " + outcome.getType(),
                    0, lease.closeCount.get());
            assertTrue(admitted.get().isActive());
            assertTrue(localRepository.isFrozen());

            FakeLease secondLease = new FakeLease();
            RunCoordinator.Submission second = coordinator.submit(context(),
                    RunConfig.ExecutionMode.MANUAL, secondLease);
            assertFalse("unverified result admitted a second operation", second.isAccepted());
            assertEquals(1, localEngine.calls.get());
            assertEquals(0, secondLease.closeCount.get());

            // Only an explicit recovery with the exact operation ID may close it.
            assertFalse(coordinator.recoverIndeterminate("wrong-id"));
            assertEquals(0, localStore.clearCalls);
            assertTrue(coordinator.recoverIndeterminate(held.getOperationId()));
            assertEquals(1, localStore.clearCalls);
            assertEquals(1, lease.closeCount.get());
            assertFalse(admitted.get().isActive());
            assertFalse(localRepository.isFrozen());
            assertNull(localStore.load());
        }
    }

    /** Confirmed native codes always finish, close owned resources and clear the attempt. */
    @Test
    public void confirmedNativeCodesClearDurableAttemptAndFinish() throws Exception {
        for (int code : new int[] {0, 1, 2, 3, 4, 5, 6}) {
            ConfigurationRepository localRepository = new ConfigurationRepository(
                    new Store(), Runnable::run, Runnable::run);
            localRepository.start();
            ControllableStore localStore = new ControllableStore();
            ScriptedEngine localEngine = new ScriptedEngine();
            localEngine.results.add(NativeResultMapper.map(code));
            AtomicReference<ConfigurationRepository.RunAdmission> admitted =
                    new AtomicReference<>();
            localEngine.inspection = call -> admitted.set(call.admission);
            RunCoordinator coordinator = new RunCoordinator(localRepository, Runnable::run,
                    Runnable::run, localEngine, 0L, localStore, new MutableReader(BOOT_COUNT),
                    Runnable::run);
            coordinator.initialize();
            FakeLease lease = new FakeLease();

            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                    lease).isAccepted());
            RunCoordinator.Snapshot terminal = coordinator.getSnapshot();

            assertEquals("native rc=" + code, RunCoordinator.State.TERMINAL,
                    terminal.getState());
            assertEquals(NativeResultMapper.map(code).getType(), terminal.getResult().getType());
            assertNull(localStore.load());
            assertEquals(1, lease.closeCount.get());
            assertFalse(admitted.get().isActive());
            assertFalse(localRepository.isFrozen());
        }
    }

    private static OperationAttempt assertStoreAttempt(ControllableStore chosenStore,
                                                       String operationId) {
        OperationAttempt attempt;
        try {
            attempt = chosenStore.load();
        } catch (OperationAttemptStore.Failure e) {
            throw new AssertionError(e);
        }
        assertNotNull(attempt);
        assertEquals(operationId, attempt.getOperationId());
        return attempt;
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

    /** Mutable, harmless authorization seam for OFF-transition tests. */
    static final class MutableAuthorization implements RunCoordinator.UnattendedAuthorization {
        volatile boolean enabled;

        MutableAuthorization(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isUnattendedEnabled() {
            return enabled;
        }
    }

    /** Harmless in-memory store whose checked publish can be blocked with latches. */
    private static final class BlockingPublishStore implements OperationAttemptStore {
        private final OperationAttemptStore delegate = OperationAttemptStore.inMemory();
        final CountDownLatch publishEntered = new CountDownLatch(1);
        final CountDownLatch releasePublish = new CountDownLatch(1);
        int clearCalls;

        @Override
        public OperationAttempt load() throws Failure {
            return delegate.load();
        }

        @Override
        public boolean publish(OperationAttempt attempt) throws Failure {
            publishEntered.countDown();
            try {
                releasePublish.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return delegate.publish(attempt);
        }

        @Override
        public boolean clear(String operationId) throws Failure {
            clearCalls++;
            return delegate.clear(operationId);
        }
    }

    /** Delegating store with deterministic false/throw injection on checked operations. */
    private static final class ControllableStore implements OperationAttemptStore {
        private final OperationAttemptStore delegate = OperationAttemptStore.inMemory();
        boolean publishResult = true;
        boolean clearResult = true;
        int loadCalls;
        RuntimeException loadFailure;
        OperationAttemptStore.Failure loadError;
        RuntimeException clearThrows;
        int clearCalls;

        void seed(OperationAttempt attempt) throws Exception {
            delegate.publish(attempt);
        }

        boolean clear(String operationId, boolean ignored) throws Exception {
            return delegate.clear(operationId);
        }

        @Override
        public OperationAttempt load() throws Failure {
            loadCalls++;
            if (loadError != null) {
                throw loadError;
            }
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
            clearCalls++;
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
