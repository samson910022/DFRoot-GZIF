// Modified 2026-11-24 (DFRoot GZIF): prove atomic run ownership, uncertainty retention, cleanup, and bounded observation. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): cover the admitted operation ID passed to the production engine. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): prove an Error from lease close cannot strand cleanup or later submits. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): prove native rc7 keeps ownership until an explicit recovery. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin recovery to real engine completion and single-claim concurrency. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin the final unattended gate, fail-closed reads, production wiring, and no revocation after engine start. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): prove request-correlated outcomes are fresh and never alter active operation ownership. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
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
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class RunCoordinatorTest {
    private static final RunConfig CONFIG = new RunConfig(
            SuManager.NEXT.getPackageName(), false, false, 0, 17);
    private static final long TEST_TIMEOUT_MS = 5_000L;

    private ExecutorService worker;
    private FakeEngine engine;

    @Before
    public void setUp() {
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        worker = Executors.newSingleThreadExecutor();
        engine = new FakeEngine();
    }

    @After
    public void tearDown() throws Exception {
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        if (worker != null) {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void requestFailurePublishesNewSnapshotsAndAcceptedSubmitClearsItAtomically() {
        QueueExecutor queuedWorker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), queuedWorker, main);
        List<RunCoordinator.Snapshot> observed = new ArrayList<>();
        coordinator.addListener(observed::add);
        RunCoordinator.Snapshot idle = coordinator.getSnapshot();

        coordinator.noteRequestRejected("foreground service start failed: FGS denied");
        RunCoordinator.Snapshot foregroundFailure = coordinator.getSnapshot();
        main.runAll();
        coordinator.noteRequestRejected("operation service setup failed: no WakeLock");
        RunCoordinator.Snapshot setupFailure = coordinator.getSnapshot();

        assertNotSame(idle, foregroundFailure);
        assertNotSame(foregroundFailure, setupFailure);
        assertNull(idle.getRequestFailure());
        assertEquals("foreground service start failed: FGS denied",
                foregroundFailure.getRequestFailure());
        assertEquals("operation service setup failed: no WakeLock",
                setupFailure.getRequestFailure());
        assertEquals(RunCoordinator.State.IDLE, setupFailure.getState());
        assertNull(setupFailure.getResult());
        assertNull(setupFailure.getReason());
        assertEquals(Collections.singletonList(foregroundFailure), observed);
        main.runAll();
        assertEquals(Arrays.asList(foregroundFailure, setupFailure), observed);

        FakeLease lease = new FakeLease();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        RunCoordinator.Snapshot starting = coordinator.getSnapshot();
        assertNull(starting.getRequestFailure());
        coordinator.noteRequestRejected("late duplicate failure");
        assertSame(starting, coordinator.getSnapshot());
        assertEquals(1, queuedWorker.size());
        assertEquals(0, lease.closeCount.get());
    }

    @Test
    public void submitWithRequestIdPublishesAcceptedCorrelationAtomically() {
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), queuedWorker,
                Runnable::run);
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease,
                "ui-request-A").isAccepted());

        RunCoordinator.Snapshot admitted = coordinator.getSnapshot();
        assertEquals("ui-request-A", admitted.getRequestId());
        assertEquals(RunCoordinator.RequestOutcome.ACCEPTED, admitted.getRequestOutcome());
        assertNull(admitted.getRequestFailure());
        assertEquals(RunCoordinator.State.STARTING, admitted.getState());
        assertEquals(0, lease.closeCount.get());
        assertEquals(1, queuedWorker.size());
    }

    @Test
    public void correlatedOutcomeDuringAnActiveOperationIsFreshAndLeavesOwnershipIntact()
            throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeEngine.Plan plan = engine.blockingPlan();
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease,
                "ui-request-A").isAccepted());
        assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        RunCoordinator.Snapshot admitted = coordinator.getSnapshot();
        String operationId = admitted.getOperationId();
        int logSize = admitted.getLog().size();

        coordinator.noteRequestOutcome("ui-request-B",
                RunCoordinator.RequestOutcome.REJECTED, "operation " + operationId + " is active");

        RunCoordinator.Snapshot rejection = coordinator.getSnapshot();
        assertNotSame(admitted, rejection);
        assertEquals("ui-request-B", rejection.getRequestId());
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, rejection.getRequestOutcome());
        assertTrue(rejection.getRequestFailure().contains("active"));
        assertEquals(admitted.getState(), rejection.getState());
        assertSame(admitted.getResult(), rejection.getResult());
        assertEquals(operationId, rejection.getOperationId());
        assertEquals(admitted.getMode(), rejection.getMode());
        assertEquals(admitted.getConfigGeneration(), rejection.getConfigGeneration());
        assertEquals(logSize, rejection.getLog().size());

        assertEquals(RunCoordinator.State.RUNNING, rejection.getState());
        assertEquals(0, lease.closeCount.get());
        assertTrue(plan.call.admission.isActive());
        assertTrue(repository.isFrozen());
        assertEquals(1, engine.callCount());

        RunResult observed = NativeResultMapper.map(0);
        plan.finish(observed);
        awaitTerminal(coordinator);
        assertSame(observed, coordinator.getSnapshot().getResult());
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
    }

    @Test
    public void correlatedOutcomeDuringCleanupIsPublishedWhileLegacyRejectionIsSuppressed()
            throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        engine.immediatePlan(NativeResultMapper.map(0));
        BlockingCloseLease lease = new BlockingCloseLease();
        try {
            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease,
                    "ui-request-A").isAccepted());
            await(lease.closeEntered);
            RunCoordinator.Snapshot duringCleanup = coordinator.getSnapshot();

            coordinator.noteRequestRejected("legacy signal must be suppressed");
            assertSame(duringCleanup, coordinator.getSnapshot());

            coordinator.noteRequestOutcome("ui-request-C",
                    RunCoordinator.RequestOutcome.CANCELLED, "service destroyed before admission");
            RunCoordinator.Snapshot cancelled = coordinator.getSnapshot();
            assertNotSame(duringCleanup, cancelled);
            assertEquals("ui-request-C", cancelled.getRequestId());
            assertEquals(RunCoordinator.RequestOutcome.CANCELLED, cancelled.getRequestOutcome());
            assertEquals(duringCleanup.getState(), cancelled.getState());
            assertSame(duringCleanup.getResult(), cancelled.getResult());
            assertEquals(duringCleanup.getOperationId(), cancelled.getOperationId());
            assertEquals(1, lease.closeCount.get());
        } finally {
            lease.releaseClose.countDown();
        }
        awaitTerminal(coordinator);
        assertFalse(repository.isFrozen());
    }

    @Test
    public void emptyRejectionReasonsStillPublishFreshObservableSnapshots() {
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), new QueueExecutor(), main);
        RunCoordinator.Snapshot idle = coordinator.getSnapshot();

        coordinator.noteRequestRejected(null);
        RunCoordinator.Snapshot nullReason = coordinator.getSnapshot();
        coordinator.noteRequestRejected("");
        RunCoordinator.Snapshot emptyReason = coordinator.getSnapshot();

        assertNotSame(idle, nullReason);
        assertNotSame(nullReason, emptyReason);
        assertNotNull(nullReason.getRequestFailure());
        assertNotNull(emptyReason.getRequestFailure());
        assertEquals(2, main.size());
    }

    @Test
    public void requestFailureIsIgnoredWhileOperationCleanupIsPending() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        engine.immediatePlan(NativeResultMapper.map(0));
        BlockingCloseLease lease = new BlockingCloseLease();
        try {
            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
            await(lease.closeEntered);
            RunCoordinator.Snapshot duringCleanup = coordinator.getSnapshot();

            coordinator.noteRequestRejected("must not replace cleanup");

            assertSame(duringCleanup, coordinator.getSnapshot());
            assertNull(duringCleanup.getRequestFailure());
            assertEquals(1, lease.closeCount.get());
        } finally {
            lease.releaseClose.countDown();
        }
        awaitTerminal(coordinator);
        assertFalse(repository.isFrozen());
    }

    @Test
    public void expectedGenerationMatchRunsTheConfirmedSnapshot() {
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), queuedWorker, Runnable::run);
        FakeLease lease = new FakeLease();
        engine.immediatePlan(NativeResultMapper.map(0));

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease,
                CONFIG.getGeneration()).isAccepted());
        queuedWorker.runAll();

        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertTrue(coordinator.getSnapshot().getResult().isSuccess());
        assertEquals(CONFIG.getGeneration(), coordinator.getSnapshot().getConfigGeneration());
        assertEquals(1, engine.callCount());
        assertEquals(1, lease.closeCount.get());
    }

    @Test
    public void expectedGenerationMismatchTerminatesBeforeExecutionAndReleasesResources() {
        QueueExecutor queuedWorker = new QueueExecutor();
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, queuedWorker, Runnable::run);
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease,
                CONFIG.getGeneration() - 1).isAccepted());
        queuedWorker.runAll();

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.TERMINAL, terminal.getState());
        assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
        assertTrue(terminal.getReason().contains("configuration generation changed"));
        assertTrue(terminal.getReason().contains("expected " + (CONFIG.getGeneration() - 1)));
        assertNull(terminal.getRequestFailure());
        assertEquals(0, engine.callCount());
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
    }

    @Test
    public void doubleClickRejectsWithoutQueueingOrTakingTheDuplicateLease() {
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), queuedWorker, Runnable::run);
        FakeLease firstLease = new FakeLease();
        FakeLease duplicateLease = new FakeLease();

        RunCoordinator.Submission first = coordinator.submit(
                context(), RunConfig.ExecutionMode.MANUAL, firstLease);
        RunCoordinator.Submission duplicate = coordinator.submit(
                context(), RunConfig.ExecutionMode.MANUAL, duplicateLease);

        assertTrue(first.isAccepted());
        assertFalse(duplicate.isAccepted());
        assertEquals(first.getOperationId(), duplicate.getOperationId());
        assertTrue(duplicate.getReason().contains("active"));
        assertEquals(RunCoordinator.State.STARTING, coordinator.getSnapshot().getState());
        assertEquals(1, queuedWorker.size());
        assertEquals(0, firstLease.closeCount.get());
        assertEquals(0, duplicateLease.closeCount.get());
    }

    @Test
    public void twoConcurrentCallersAdmitExactlyOneAndNeverBlockTheRejectedCaller() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeEngine.Plan plan = engine.blockingPlan();
        FakeLease firstLease = new FakeLease();
        FakeLease secondLease = new FakeLease();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<RunCoordinator.Submission> first = callers.submit(() -> {
                await(start);
                return coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, firstLease);
            });
            Future<RunCoordinator.Submission> second = callers.submit(() -> {
                await(start);
                return coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, secondLease);
            });
            start.countDown();

            RunCoordinator.Submission firstSubmission = first.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            RunCoordinator.Submission secondSubmission = second.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue(firstSubmission.isAccepted() ^ secondSubmission.isAccepted());
            assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(1, engine.callCount());
            assertEquals(0, firstLease.closeCount.get() + secondLease.closeCount.get());

            plan.finish(NativeResultMapper.map(0));
            awaitTerminal(coordinator);
            if (firstSubmission.isAccepted()) {
                assertEquals(1, firstLease.closeCount.get());
                assertEquals(0, secondLease.closeCount.get());
            } else {
                assertEquals(0, firstLease.closeCount.get());
                assertEquals(1, secondLease.closeCount.get());
            }
        } finally {
            plan.finish(NativeResultMapper.map(0));
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void multipleActivityEquivalentRequestsAllRejectTheLiveOperation() throws Exception {
        RunCoordinator coordinator = coordinator(verifiedRepository(), worker, Runnable::run);
        FakeEngine.Plan plan = engine.blockingPlan();
        FakeLease owner = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, owner).isAccepted());
        assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        for (int activity = 0; activity < 4; activity++) {
            FakeLease recreatedActivityLease = new FakeLease();
            RunCoordinator.Submission duplicate = coordinator.submit(
                    context(), RunConfig.ExecutionMode.MANUAL, recreatedActivityLease);
            assertFalse(duplicate.isAccepted());
            assertEquals(0, recreatedActivityLease.closeCount.get());
        }

        assertEquals(1, engine.callCount());
        assertEquals(0, owner.closeCount.get());
        plan.finish(NativeResultMapper.map(0));
        awaitTerminal(coordinator);
        assertEquals(1, owner.closeCount.get());
    }

    @Test
    public void bootAndManualOverlapRejectsImmediatelyRegardlessOfRequestOrder() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeEngine.Plan boot = engine.blockingPlan();
        FakeLease bootLease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                bootLease).isAccepted());
        assertTrue(boot.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        FakeLease manualLease = new FakeLease();
        assertFalse(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                manualLease).isAccepted());
        assertEquals(RunConfig.ExecutionMode.UNATTENDED_BOOT,
                coordinator.getSnapshot().getMode());
        assertEquals(0, manualLease.closeCount.get());
        assertEquals(1, engine.callCount());

        boot.finish(NativeResultMapper.map(0));
        awaitTerminal(coordinator);
        assertEquals(1, bootLease.closeCount.get());

        FakeEngine.Plan manual = engine.blockingPlan();
        FakeLease manualOwner = new FakeLease();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                manualOwner).isAccepted());
        assertTrue(manual.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        FakeLease duplicateBootLease = new FakeLease();
        assertFalse(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                duplicateBootLease).isAccepted());
        assertEquals(0, duplicateBootLease.closeCount.get());
        assertEquals(2, engine.callCount());

        manual.finish(NativeResultMapper.map(0));
        awaitTerminal(coordinator);
        assertEquals(1, manualOwner.closeCount.get());
    }

    @Test
    public void coldStartTimeoutTerminatesSetupFailureAndClosesTheAcceptedLease() {
        QueueExecutor configurationWorker = new QueueExecutor();
        ConfigurationRepository repository = new ConfigurationRepository(
                new FakeStore(CONFIG), configurationWorker, Runnable::run);
        repository.start();
        RunCoordinator coordinator = new RunCoordinator(repository, worker, Runnable::run,
                engine, 0L);
        FakeLease lease = new FakeLease();
        CountDownLatch terminal = terminalLatch(coordinator);

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        await(terminal);

        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(RunResult.Type.PRECHECK_FAILED, coordinator.getSnapshot().getResult().getType());
        assertTrue(coordinator.getSnapshot().getReason().contains("initialization"));
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
        assertEquals(1, configurationWorker.size());
    }

    @Test
    public void coldStartFailureTerminatesWithoutAcquiringOrLeakingResources() {
        FakeStore store = new FakeStore(CONFIG);
        store.loadFailure = new IllegalStateException("store unavailable");
        ConfigurationRepository repository = new ConfigurationRepository(
                store, Runnable::run, Runnable::run);
        repository.start();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeLease lease = new FakeLease();
        CountDownLatch terminal = terminalLatch(coordinator);

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        await(terminal);

        assertEquals(RunResult.Type.PRECHECK_FAILED, coordinator.getSnapshot().getResult().getType());
        assertTrue(coordinator.getSnapshot().getReason().contains("store unavailable"));
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
        assertEquals(0, engine.callCount());
    }

    @Test
    public void initializationWaitsOffMainAndDuplicateDoesNotQueueBehindIt() throws Exception {
        ExecutorService configurationWorker = Executors.newSingleThreadExecutor();
        BlockingStore store = new BlockingStore();
        ConfigurationRepository repository = new ConfigurationRepository(
                store, configurationWorker, Runnable::run);
        repository.start();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeEngine.Plan plan = engine.blockingPlan();
        FakeLease lease = new FakeLease();
        try {
            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
            assertTrue(store.loadEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(RunCoordinator.State.STARTING, coordinator.getSnapshot().getState());
            assertNotEquals(Thread.currentThread(), store.loadThread);
            FakeLease duplicateLease = new FakeLease();
            assertFalse(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                    duplicateLease).isAccepted());
            assertEquals(0, duplicateLease.closeCount.get());

            store.releaseLoad.countDown();
            assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertSame(CONFIG, plan.call.admission.getSnapshot());
            assertTrue(repository.isFrozen());
            assertEquals(CONFIG.getGeneration(), coordinator.getSnapshot().getConfigGeneration());
            plan.finish(NativeResultMapper.map(0));
            awaitTerminal(coordinator);
        } finally {
            store.releaseLoad.countDown();
            configurationWorker.shutdownNow();
            assertTrue(configurationWorker.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void staleTimeoutAndInterruptionCannotChangeANewerOperation() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeEngine.Plan first = engine.immediatePlan(RunResult.alreadyHooked());
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        awaitTerminal(coordinator);
        RunCoordinator.Snapshot firstSnapshot = coordinator.getSnapshot();

        FakeEngine.Plan second = engine.blockingPlan();
        FakeLease secondLease = new FakeLease();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                secondLease).isAccepted());
        assertTrue(second.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        String secondOperationId = coordinator.getSnapshot().getOperationId();

        first.call.events.onTimeout();
        first.call.events.onInterrupted();
        int secondLogSize = coordinator.getSnapshot().getLog().size();
        first.call.reporter.report("stale child output\n");
        assertEquals(secondOperationId, coordinator.getSnapshot().getOperationId());
        assertEquals(RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
        assertNull(coordinator.getSnapshot().getResult());
        assertEquals(secondLogSize, coordinator.getSnapshot().getLog().size());
        assertTrue(second.call.admission.isActive());
        assertEquals(0, secondLease.closeCount.get());
        assertNotEquals(firstSnapshot.getOperationId(), secondOperationId);

        RunResult secondResult = NativeResultMapper.map(0);
        second.finish(secondResult);
        awaitTerminal(coordinator);
        first.call.events.onTimeout();
        first.call.events.onInterrupted();
        assertSame(secondResult, coordinator.getSnapshot().getResult());
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(1, secondLease.closeCount.get());
    }

    @Test
    public void staleCompletionCallbackCannotClearANewerOperation() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeEngine.Plan first = engine.blockingPlan();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        assertTrue(first.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        Field activeField = RunCoordinator.class.getDeclaredField("activeOperation");
        activeField.setAccessible(true);
        Object staleOperation = activeField.get(coordinator);
        Method finish = null;
        for (Method method : RunCoordinator.class.getDeclaredMethods()) {
            if (method.getName().equals("finish") && method.getParameterCount() == 3) {
                finish = method;
                break;
            }
        }
        assertNotNull(finish);
        finish.setAccessible(true);

        first.finish(RunResult.alreadyHooked());
        awaitTerminal(coordinator);
        FakeEngine.Plan second = engine.blockingPlan();
        FakeLease secondLease = new FakeLease();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                secondLease).isAccepted());
        assertTrue(second.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        String secondOperationId = coordinator.getSnapshot().getOperationId();

        finish.invoke(coordinator, staleOperation, RunResult.alreadyHooked(), null);

        assertEquals(secondOperationId, coordinator.getSnapshot().getOperationId());
        assertEquals(RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
        assertNull(coordinator.getSnapshot().getResult());
        assertTrue(second.call.admission.isActive());
        assertEquals(0, secondLease.closeCount.get());
        second.finish(NativeResultMapper.map(0));
        awaitTerminal(coordinator);
    }

    @Test
    public void staleRunningNotificationCannotRegressAfterTerminal() throws Exception {
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), worker, main);
        FakeEngine.Plan plan = engine.blockingPlan();
        List<RunCoordinator.Snapshot> observed = new ArrayList<>();
        coordinator.addListener(observed::add);
        try {
            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                    new FakeLease()).isAccepted());
            assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            RunCoordinator.Snapshot running = coordinator.getSnapshot();

            plan.finish(RunResult.alreadyHooked());
            worker.submit(() -> {}).get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
            assertEquals(RunCoordinator.State.TERMINAL, terminal.getState());
            main.runAll();
            observed.clear();

            notifyListeners(coordinator, terminal);
            notifyListeners(coordinator, running);
            main.runAll();

            assertEquals(Collections.singletonList(terminal), observed);
            assertSame(terminal, coordinator.getSnapshot());
        } finally {
            plan.finish(RunResult.alreadyHooked());
        }
    }

    @Test
    public void staleTerminalNotificationCannotReplaceANewerStartingSnapshot() throws Exception {
        QueueExecutor main = new QueueExecutor();
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), queuedWorker, main);
        List<RunCoordinator.Snapshot> observed = new ArrayList<>();
        coordinator.addListener(observed::add);
        engine.immediatePlan(RunResult.alreadyHooked());
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        queuedWorker.runAll();
        RunCoordinator.Snapshot previousTerminal = coordinator.getSnapshot();
        main.runAll();
        observed.clear();

        FakeEngine.Plan next = engine.blockingPlan();
        FakeLease nextLease = new FakeLease();
        try {
            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                    nextLease).isAccepted());
            RunCoordinator.Snapshot starting = coordinator.getSnapshot();
            assertEquals(RunCoordinator.State.STARTING, starting.getState());

            notifyListeners(coordinator, previousTerminal);
            main.runAll();

            assertEquals(Collections.singletonList(starting), observed);
            assertSame(starting, coordinator.getSnapshot());
            assertEquals(0, nextLease.closeCount.get());
        } finally {
            next.finish(RunResult.alreadyHooked());
            queuedWorker.runAll();
            main.runAll();
        }
    }

    @Test
    public void logEntryCapDropsOldestShortMessagesWithoutHittingCharacterCap() throws Exception {
        RunCoordinator coordinator = coordinator(verifiedRepository(), worker, Runnable::run);
        RunCoordinator.Snapshot before = coordinator.getSnapshot();
        FakeEngine.Plan plan = engine.immediatePlan(NativeResultMapper.map(0));
        plan.action = call -> {
            for (int entry = 0; entry < RunCoordinator.MAX_LOG_ENTRIES + 50; entry++) {
                call.reporter.report("entry-" + entry + "\n");
            }
        };

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        awaitTerminal(coordinator);
        RunCoordinator.Snapshot after = coordinator.getSnapshot();

        assertEquals(RunCoordinator.MAX_LOG_ENTRIES, after.getLog().size());
        assertTrue(logCharacters(after) < RunCoordinator.MAX_LOG_CHARACTERS);
        assertEquals("entry-" + (RunCoordinator.MAX_LOG_ENTRIES + 49) + "\n",
                after.getLog().get(after.getLog().size() - 2).getMessage());
        assertTrue(after.getLog().get(after.getLog().size() - 1).getMessage()
                .startsWith("terminal:"));
        assertTrue(before.getLog().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> after.getLog().add(new RunCoordinator.LogEntry(-1, "mutated")));
    }

    @Test
    public void oversizedLogMessageRetainsOnlyItsTailAndEnforcesCharacterCap() throws Exception {
        RunCoordinator coordinator = coordinator(verifiedRepository(), worker, Runnable::run);
        RunCoordinator.Snapshot before = coordinator.getSnapshot();
        AtomicReference<RunCoordinator.Snapshot> oversized = new AtomicReference<>();
        AtomicReference<RunCoordinator.Snapshot> afterOversized = new AtomicReference<>();
        FakeEngine.Plan plan = engine.immediatePlan(NativeResultMapper.map(0));
        plan.action = call -> {
            String message = "DISCARD-" + "x".repeat(RunCoordinator.MAX_LOG_CHARACTERS) + "-TAIL\n";
            call.reporter.report(message);
            oversized.set(coordinator.getSnapshot());
            call.reporter.report("after-oversized\n");
            afterOversized.set(coordinator.getSnapshot());
        };

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        awaitTerminal(coordinator);

        assertNotNull(oversized.get());
        assertEquals(1, oversized.get().getLog().size());
        assertEquals(RunCoordinator.MAX_LOG_CHARACTERS,
                oversized.get().getLog().get(0).getMessage().length());
        assertEquals(RunCoordinator.MAX_LOG_CHARACTERS, logCharacters(oversized.get()));
        assertTrue(oversized.get().getLog().get(0).getMessage().endsWith("-TAIL\n"));
        assertFalse(oversized.get().getLog().get(0).getMessage().startsWith("DISCARD-"));
        assertNotNull(afterOversized.get());
        assertEquals(Collections.singletonList("after-oversized\n"),
                afterOversized.get().getLog().stream().map(RunCoordinator.LogEntry::getMessage)
                        .collect(Collectors.toList()));
        assertTrue(logCharacters(coordinator.getSnapshot()) <= RunCoordinator.MAX_LOG_CHARACTERS);
        assertTrue(before.getLog().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> coordinator.getSnapshot().getLog()
                        .add(new RunCoordinator.LogEntry(-1, "mutated")));
    }

    @Test
    public void timeoutPublishesIndeterminateButRetainsResourcesUntilActualCompletion() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeEngine.Plan plan = engine.blockingPlan();
        FakeLease lease = new FakeLease();
        CountDownLatch indeterminate = stateLatch(coordinator, RunCoordinator.State.INDETERMINATE);
        CountDownLatch terminal = terminalLatch(coordinator);

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        plan.call.events.onTimeout();
        await(indeterminate);

        assertEquals(RunCoordinator.State.INDETERMINATE, coordinator.getSnapshot().getState());
        assertEquals(RunResult.Type.INDETERMINATE, coordinator.getSnapshot().getResult().getType());
        assertEquals(RunResult.Detail.PROCESS_TIMEOUT,
                coordinator.getSnapshot().getResult().getDetail());
        assertTrue(plan.call.admission.isActive());
        assertTrue(repository.isFrozen());
        assertEquals(0, lease.closeCount.get());

        plan.finish(NativeResultMapper.map(0));
        await(terminal);
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(RunResult.Detail.PROCESS_TIMEOUT,
                coordinator.getSnapshot().getResult().getDetail());
        assertEquals(ExploitRunner.RESULT_INDETERMINATE,
                coordinator.getSnapshot().getResult().getCode());
        assertFalse(plan.call.admission.isActive());
        assertFalse(repository.isFrozen());
        assertEquals(1, lease.closeCount.get());
    }

    @Test
    public void interruptionPublishesIndeterminateAndLateSuccessCannotReplaceIt() throws Exception {
        RunCoordinator coordinator = coordinator(verifiedRepository(), worker, Runnable::run);
        FakeEngine.Plan plan = engine.blockingPlan();
        FakeLease lease = new FakeLease();
        CountDownLatch indeterminate = stateLatch(coordinator, RunCoordinator.State.INDETERMINATE);
        CountDownLatch terminal = terminalLatch(coordinator);

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        plan.call.events.onInterrupted();
        await(indeterminate);
        assertEquals(RunResult.Detail.PROCESS_INTERRUPTION,
                coordinator.getSnapshot().getResult().getDetail());
        assertEquals(0, lease.closeCount.get());

        plan.finish(RunResult.alreadyHooked());
        await(terminal);
        assertEquals(RunResult.Detail.PROCESS_INTERRUPTION,
                coordinator.getSnapshot().getResult().getDetail());
        assertFalse(coordinator.getSnapshot().getResult().isSuccess());
        assertEquals(1, lease.closeCount.get());
    }

    @Test
    public void nativeRc7RetainsLeaseFreezeAndAdmissionUntilExplicitRecovery() {
        ConfigurationRepository repository = verifiedRepository();
        QueueExecutor queuedWorker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(repository, queuedWorker, main);
        FakeEngine.Plan plan = engine.immediatePlan(NativeResultMapper.map(7));
        plan.action = call -> call.reporter.report("reporter deadline passed; rc=7 published\n");
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                lease).isAccepted());
        queuedWorker.runAll();
        main.runAll();

        RunCoordinator.Snapshot unverified = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.INDETERMINATE, unverified.getState());
        assertEquals(RunResult.Type.INDETERMINATE, unverified.getResult().getType());
        assertEquals(RunResult.Detail.NATIVE_EXIT, unverified.getResult().getDetail());
        assertEquals(ExploitRunner.RESULT_INDETERMINATE, unverified.getResult().getCode());
        assertFalse(unverified.getResult().isSuccess());
        assertNotNull(unverified.getReason());
        assertTrue(unverified.getReason().contains("outcome unverified"));
        assertTrue(unverified.getLog().stream().anyMatch(entry ->
                entry.getMessage().contains("outcome unverified")));
        assertTrue(unverified.getLog().stream().anyMatch(entry ->
                entry.getMessage().contains("reporter deadline passed")));
        assertEquals(0, lease.closeCount.get());
        assertTrue(plan.call.admission.isActive());
        assertTrue(repository.isFrozen());
        assertEquals(1, engine.callCount());

        // Single-flight admission stays locked: every new submit is rejected.
        FakeLease rejectedLease = new FakeLease();
        assertFalse(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                rejectedLease).isAccepted());
        assertFalse(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                rejectedLease).isAccepted());
        assertEquals(0, rejectedLease.closeCount.get());

        // Only an explicit caller with the exact operation ID may close it.
        assertFalse(coordinator.recoverIndeterminate("wrong-operation-id"));
        assertEquals(0, lease.closeCount.get());
        assertTrue(repository.isFrozen());
        assertSame(unverified, coordinator.getSnapshot());

        assertTrue(coordinator.recoverIndeterminate(unverified.getOperationId()));
        assertEquals(1, lease.closeCount.get());
        assertFalse(plan.call.admission.isActive());
        assertFalse(repository.isFrozen());
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(RunResult.Detail.NATIVE_EXIT,
                coordinator.getSnapshot().getResult().getDetail());
        assertEquals(1, engine.callCount());

        // Recovery, not an automatic retry, admits the next operation.
        engine.immediatePlan(NativeResultMapper.map(0));
        FakeLease secondLease = new FakeLease();
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                secondLease).isAccepted());
        queuedWorker.runAll();
        main.runAll();
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertTrue(coordinator.getSnapshot().getResult().isSuccess());
        assertEquals(1, secondLease.closeCount.get());
        assertEquals(2, engine.callCount());
    }

    @Test
    public void nativeRc7AfterSupervisionTimeoutKeepsTimeoutResultAndOwnership() {
        ConfigurationRepository repository = verifiedRepository();
        QueueExecutor queuedWorker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(repository, queuedWorker, main);
        FakeEngine.Plan plan = engine.immediatePlan(NativeResultMapper.map(7));
        plan.action = call -> call.events.onTimeout();
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                lease).isAccepted());
        queuedWorker.runAll();
        main.runAll();

        RunCoordinator.Snapshot unverified = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.INDETERMINATE, unverified.getState());
        assertEquals(RunResult.Detail.PROCESS_TIMEOUT, unverified.getResult().getDetail());
        assertEquals(ExploitRunner.RESULT_INDETERMINATE, unverified.getResult().getCode());
        assertEquals(0, lease.closeCount.get());
        assertTrue(plan.call.admission.isActive());
        assertTrue(repository.isFrozen());
        assertEquals(1, engine.callCount());

        assertTrue(coordinator.recoverIndeterminate(unverified.getOperationId()));
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
    }

    @Test
    public void recoveryClaimIsTakenByOnlyOneCaller() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        QueueExecutor queuedWorker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(repository, queuedWorker, main);
        FakeEngine.Plan plan = engine.immediatePlan(NativeResultMapper.map(7));
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                lease).isAccepted());
        queuedWorker.runAll();
        main.runAll();
        RunCoordinator.Snapshot unverified = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.INDETERMINATE, unverified.getState());

        // Deterministically model another correct-ID caller that already took
        // the claim: a second caller must not clean up or report success too.
        Field activeOperation = RunCoordinator.class.getDeclaredField("activeOperation");
        activeOperation.setAccessible(true);
        Object operation = activeOperation.get(coordinator);
        Field recoveryClaimed = operation.getClass().getDeclaredField("recoveryClaimed");
        recoveryClaimed.setAccessible(true);
        assertFalse(recoveryClaimed.getBoolean(operation));
        recoveryClaimed.setBoolean(operation, true);

        assertFalse(coordinator.recoverIndeterminate(unverified.getOperationId()));
        assertEquals(0, lease.closeCount.get());
        assertTrue(plan.call.admission.isActive());
        assertTrue(repository.isFrozen());
        assertEquals(1, engine.callCount());

        // With the claim free, one caller closes everything exactly once.
        recoveryClaimed.setBoolean(operation, false);
        assertTrue(coordinator.recoverIndeterminate(unverified.getOperationId()));
        assertEquals(1, lease.closeCount.get());
        assertFalse(plan.call.admission.isActive());
        assertFalse(repository.isFrozen());
        assertFalse(coordinator.recoverIndeterminate(unverified.getOperationId()));
        assertEquals(1, lease.closeCount.get());
    }

    /** INDETERMINATE from a supervisor timeout is not proof that work stopped. */
    @Test
    public void supervisedTimeoutWhileEngineIsBlockedRefusesRecoveryUntilEngineReturns()
            throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        FakeEngine.Plan plan = engine.blockingPlan();
        FakeLease lease = new FakeLease();
        AtomicReference<ConfigurationRepository.RunAdmission> admitted = new AtomicReference<>();
        plan.action = call -> admitted.set(call.admission);
        CountDownLatch indeterminate = stateLatch(coordinator, RunCoordinator.State.INDETERMINATE);

        try {
            assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                    lease).isAccepted());
            assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            plan.call.events.onTimeout();
            await(indeterminate);

            String id = coordinator.getSnapshot().getOperationId();
            RunCoordinator.Snapshot held = coordinator.getSnapshot();
            assertEquals(RunCoordinator.State.INDETERMINATE, held.getState());
            assertFalse("a blocked engine is still active work",
                    coordinator.recoverIndeterminate(id));
            assertEquals(0, lease.closeCount.get());
            assertTrue(admitted.get().isActive());
            assertTrue(repository.isFrozen());
            assertSame(held, coordinator.getSnapshot());

            // Only after engine.run() actually returns does recovery become legal.
            plan.finish(NativeResultMapper.map(7));
            worker.submit(() -> {}).get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue(coordinator.recoverIndeterminate(id));
            assertEquals(1, lease.closeCount.get());
            assertFalse(admitted.get().isActive());
            assertFalse(repository.isFrozen());
            assertFalse(coordinator.recoverIndeterminate(id));
            assertEquals(1, lease.closeCount.get());
        } finally {
            plan.finish(NativeResultMapper.map(7));
        }
    }

    @Test
    public void concurrentCorrectIdRecoveriesClaimOnceAndCloseExactlyOnce() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        QueueExecutor queuedWorker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(repository, queuedWorker, main);
        FakeEngine.Plan plan = engine.immediatePlan(NativeResultMapper.map(7));
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                lease).isAccepted());
        queuedWorker.runAll();
        main.runAll();
        String id = coordinator.getSnapshot().getOperationId();
        assertEquals(RunCoordinator.State.INDETERMINATE, coordinator.getSnapshot().getState());

        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = callers.submit(() -> {
                await(start);
                return coordinator.recoverIndeterminate(id);
            });
            Future<Boolean> second = callers.submit(() -> {
                await(start);
                return coordinator.recoverIndeterminate(id);
            });
            start.countDown();

            boolean firstWon = first.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            boolean secondWon = second.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertTrue("exactly one correct-ID caller wins", firstWon ^ secondWon);
            assertEquals(1, lease.closeCount.get());
            assertFalse(plan.call.admission.isActive());
            assertFalse(repository.isFrozen());
            assertFalse(coordinator.recoverIndeterminate(id));
            assertEquals(1, lease.closeCount.get());
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void engineExceptionKeepsOwnershipUntilExplicitRecoveryThenClosesOnce() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        QueueExecutor queuedWorker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(repository, queuedWorker, main);
        FakeEngine.Plan plan = engine.immediatePlan(null);
        plan.failure = new IllegalStateException("engine failed");
        FakeLease lease = new FakeLease();
        List<RunCoordinator.State> observed = new ArrayList<>();
        coordinator.addListener(snapshot -> observed.add(snapshot.getState()));

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        queuedWorker.runAll();
        main.runAll();

        RunCoordinator.Snapshot unverified = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.INDETERMINATE, unverified.getState());
        assertEquals(RunResult.Type.INDETERMINATE, unverified.getResult().getType());
        assertEquals(RunResult.Detail.EXCEPTION, unverified.getResult().getDetail());
        assertTrue(unverified.getResult().getReason().contains("engine failed"));
        assertTrue(unverified.getReason().contains("outcome unverified"));
        assertFalse(observed.contains(RunCoordinator.State.TERMINAL));
        assertEquals(0, lease.closeCount.get());
        assertTrue(plan.call.admission.isActive());
        assertTrue(repository.isFrozen());

        assertTrue(coordinator.recoverIndeterminate(unverified.getOperationId()));
        assertEquals(1, lease.closeCount.get());
        assertFalse(plan.call.admission.isActive());
        assertFalse(repository.isFrozen());
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(RunResult.Detail.EXCEPTION, coordinator.getSnapshot().getResult().getDetail());
    }

    /** An unrecognized exit code is just as unverified as native rc7. */
    @Test
    public void unknownNativeExitKeepsLeaseFreezeAndBarrier() {
        ConfigurationRepository repository = verifiedRepository();
        QueueExecutor queuedWorker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(repository, queuedWorker, main);
        FakeEngine.Plan plan = engine.immediatePlan(NativeResultMapper.map(255));
        FakeLease lease = new FakeLease();
        List<RunCoordinator.State> observed = new ArrayList<>();
        coordinator.addListener(snapshot -> observed.add(snapshot.getState()));

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        queuedWorker.runAll();
        main.runAll();

        RunCoordinator.Snapshot unverified = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.INDETERMINATE, unverified.getState());
        assertEquals(RunResult.Type.UNKNOWN, unverified.getResult().getType());
        assertEquals(RunResult.Detail.NATIVE_EXIT, unverified.getResult().getDetail());
        assertEquals(ExploitRunner.RESULT_UNKNOWN, unverified.getResult().getCode());
        assertTrue(unverified.getReason().contains("outcome unverified"));
        assertFalse(observed.contains(RunCoordinator.State.TERMINAL));
        assertEquals(0, lease.closeCount.get());
        assertTrue(plan.call.admission.isActive());
        assertTrue(repository.isFrozen());

        assertTrue(coordinator.recoverIndeterminate(unverified.getOperationId()));
        assertEquals(1, lease.closeCount.get());
        assertFalse(plan.call.admission.isActive());
        assertFalse(repository.isFrozen());
    }

    @Test
    public void executorRejectionAfterAcceptanceStillClosesTheOwnedLease() {
        RunCoordinator coordinator = coordinator(verifiedRepository(), new RejectingExecutor(),
                Runnable::run);
        FakeLease lease = new FakeLease();

        RunCoordinator.Submission submission = coordinator.submit(
                context(), RunConfig.ExecutionMode.MANUAL, lease);

        assertTrue(submission.isAccepted());
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(RunResult.Type.INDETERMINATE, coordinator.getSnapshot().getResult().getType());
        assertTrue(coordinator.getSnapshot().getReason().contains("RejectedExecutionException"));
        assertEquals(1, lease.closeCount.get());
    }

    @Test
    public void leaseCloseExceptionDoesNotPreventAdmissionCloseOrReplaceTheResult() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, Runnable::run);
        RunResult result = RunResult.alreadyHooked();
        engine.immediatePlan(result);
        FakeLease lease = new FakeLease();
        lease.closeFailure = new IllegalStateException("lease close failed");
        CountDownLatch terminal = terminalLatch(coordinator);

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        await(terminal);

        assertSame(result, coordinator.getSnapshot().getResult());
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
        assertTrue(coordinator.getSnapshot().getLog().stream().anyMatch(
                entry -> entry.getMessage().contains("lease close failed")));
    }

    @Test
    public void leaseCloseErrorCannotStrandCleanupOrBlockLaterSubmissions() throws Exception {
        QueueExecutor queuedWorker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), queuedWorker, main);
        engine.immediatePlan(RunResult.alreadyHooked());
        ErrorCloseLease lease = new ErrorCloseLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        queuedWorker.runAll();
        main.runAll();

        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(1, lease.closeCount.get());
        assertTrue(coordinator.getSnapshot().getLog().stream().anyMatch(entry ->
                entry.getMessage().contains("lease close failed: AssertionError: lease close Error")));
        Field cleanupPending = RunCoordinator.class.getDeclaredField("cleanupPending");
        cleanupPending.setAccessible(true);
        assertFalse(cleanupPending.getBoolean(coordinator));

        engine.immediatePlan(RunResult.alreadyHooked());
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
    }

    @Test
    public void mainExecutorRejectionDoesNotAffectTerminalStateOrCleanup() throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        RunCoordinator coordinator = coordinator(repository, worker, new RejectingExecutor());
        engine.immediatePlan(RunResult.alreadyHooked());
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        worker.submit(() -> {}).get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
    }

    @Test
    public void listenersArePostedOnlyToTheInjectedMainExecutor() {
        QueueExecutor main = new QueueExecutor();
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = coordinator(verifiedRepository(), queuedWorker, main);
        AtomicInteger callbacks = new AtomicInteger();
        coordinator.addListener(snapshot -> callbacks.incrementAndGet());
        engine.immediatePlan(RunResult.alreadyHooked());

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        assertEquals(0, callbacks.get());
        assertTrue(main.size() > 0);
        main.runAll();
        assertTrue(callbacks.get() > 0);

        queuedWorker.runAll();
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        main.runAll();
        assertEquals(RunCoordinator.State.TERMINAL,
                coordinator.getSnapshot().getState());
    }

    @Test
    public void productionMainExecutorDeliversListenersOnTheMainLooper() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new FakeStore(CONFIG), Runnable::run, ConfigurationRepository.newMainExecutor());
        repository.start();
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = new RunCoordinator(repository, queuedWorker,
                ConfigurationRepository.newMainExecutor(), engine, TEST_TIMEOUT_MS);
        List<Looper> callbackLoopers = new ArrayList<>();
        coordinator.addListener(snapshot -> callbackLoopers.add(Looper.myLooper()));
        engine.immediatePlan(RunResult.alreadyHooked());

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        queuedWorker.runAll();
        ShadowLooper.idleMainLooper();

        assertFalse(callbackLoopers.isEmpty());
        for (Looper looper : callbackLoopers) {
            assertSame(Looper.getMainLooper(), looper);
        }
        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
    }

    @Test
    public void terminalResultIsRetainedAndOperationIdsAreUnique() throws Exception {
        RunCoordinator coordinator = coordinator(verifiedRepository(), worker, Runnable::run);
        RunResult firstResult = RunResult.alreadyHooked();
        engine.immediatePlan(firstResult);
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());
        awaitTerminal(coordinator);
        RunCoordinator.Snapshot first = coordinator.getSnapshot();
        assertSame(first, coordinator.getSnapshot());
        assertSame(firstResult, first.getResult());
        assertEquals(CONFIG.getGeneration(), first.getConfigGeneration());
        assertEquals(RunConfig.ExecutionMode.MANUAL, first.getMode());

        RunResult secondResult = NativeResultMapper.map(0);
        engine.immediatePlan(secondResult);
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                new FakeLease()).isAccepted());
        awaitTerminal(coordinator);
        RunCoordinator.Snapshot second = coordinator.getSnapshot();
        assertNotEquals(first.getOperationId(), second.getOperationId());
        assertSame(secondResult, second.getResult());
        assertEquals(RunConfig.ExecutionMode.UNATTENDED_BOOT, second.getMode());
    }

    @Test
    public void processSingletonGetAndResetUseOneInstance() {
        ConfigurationRepository repository = verifiedRepository();
        ConfigurationRepository.setInstanceForTesting(repository);

        RunCoordinator first = RunCoordinator.get(context());
        RunCoordinator second = RunCoordinator.get(context());

        assertSame(first, second);
        assertEquals(RunCoordinator.State.IDLE, first.getSnapshot().getState());
        RunCoordinator.resetForTesting();
    }

    @Test
    public void unattendedFinalGateRejectsWithoutPublishEngineOrLeaseAndPublishesFreshReason()
            throws Exception {
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        RunCoordinator coordinator = gatedCoordinator(store, () -> false);
        RunCoordinator.Snapshot idle = coordinator.getSnapshot();
        FakeLease lease = new FakeLease();

        RunCoordinator.Submission rejected = coordinator.submit(context(),
                RunConfig.ExecutionMode.UNATTENDED_BOOT, lease);

        assertFalse(rejected.isAccepted());
        assertNotNull(rejected.getReason());
        assertTrue(rejected.getReason().contains("unattended admission suppressed"));
        assertNull("final-gate rejection must not publish a durable attempt", store.load());
        assertEquals(0, engine.callCount());
        assertEquals("coordinator must not own (or close) the rejected lease",
                0, lease.closeCount.get());
        assertEquals(RunCoordinator.State.IDLE, coordinator.getSnapshot().getState());
        assertNotSame(idle, coordinator.getSnapshot());
        assertEquals(rejected.getReason(), coordinator.getSnapshot().getRequestFailure());
    }

    @Test
    public void unattendedFinalGateFailsClosedWhenAuthorizationIsUnreadable() throws Exception {
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        RunCoordinator.UnattendedAuthorization unreadable = () -> {
            throw new IllegalStateException("component state unavailable");
        };
        RunCoordinator coordinator = gatedCoordinator(store, unreadable);
        RunCoordinator.Snapshot idle = coordinator.getSnapshot();
        FakeLease lease = new FakeLease();

        RunCoordinator.Submission rejected = coordinator.submit(context(),
                RunConfig.ExecutionMode.UNATTENDED_BOOT, lease);

        assertFalse(rejected.isAccepted());
        assertTrue(rejected.getReason().contains("unattended admission suppressed"));
        assertNull(store.load());
        assertEquals(0, engine.callCount());
        assertEquals(0, lease.closeCount.get());
        assertNotSame(idle, coordinator.getSnapshot());
        assertEquals(rejected.getReason(), coordinator.getSnapshot().getRequestFailure());
    }

    @Test
    public void manualSubmissionIgnoresTheUnattendedGate() {
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), queuedWorker,
                Runnable::run, engine, TEST_TIMEOUT_MS, OperationAttemptStore.inMemory(),
                () -> 7L, Runnable::run, () -> false);
        coordinator.initialize();
        engine.immediatePlan(NativeResultMapper.map(0));
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                lease).isAccepted());
        assertEquals(1, queuedWorker.size());
        queuedWorker.runAll();

        assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
        assertTrue(coordinator.getSnapshot().getResult().isSuccess());
        assertEquals(1, engine.callCount());
        assertEquals(1, lease.closeCount.get());
    }

    @Test
    public void offAfterEngineStartDoesNotRevokeOwnershipOrCancelWork() throws Exception {
        MutableAuthorization authorization = new MutableAuthorization(true);
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), worker,
                Runnable::run, engine, TEST_TIMEOUT_MS, OperationAttemptStore.inMemory(),
                () -> 7L, Runnable::run, authorization);
        coordinator.initialize();
        FakeEngine.Plan plan = engine.blockingPlan();
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                lease).isAccepted());
        assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        authorization.enabled = false;

        assertEquals(RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
        assertTrue(plan.call.admission.isActive());
        assertEquals(0, lease.closeCount.get());
        assertEquals("the one admitted engine call stays owned", 1, engine.callCount());

        RunResult observed = NativeResultMapper.map(0);
        plan.finish(observed);
        awaitTerminal(coordinator);
        assertSame(observed, coordinator.getSnapshot().getResult());
        assertEquals(1, lease.closeCount.get());
    }

    @Test
    public void productionGetWiresTheGateToTheRealComponentSetting() throws Exception {
        Context context = context();
        ComponentName component = new ComponentName(context, BootReceiver.class);
        context.getPackageManager().setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        try {
            RunCoordinator production = RunCoordinator.get(context);
            Field field = RunCoordinator.class.getDeclaredField("unattendedAuthorization");
            field.setAccessible(true);
            RunCoordinator.UnattendedAuthorization gate =
                    (RunCoordinator.UnattendedAuthorization) field.get(production);

            assertFalse("production gate must read the real Start at Boot component",
                    gate.isUnattendedEnabled());
            context.getPackageManager().setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            assertTrue("production gate must observe an owner re-enabling the component",
                    gate.isUnattendedEnabled());
        } finally {
            context.getPackageManager().setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
            RunCoordinator.resetForTesting();
        }
    }

    private RunCoordinator gatedCoordinator(OperationAttemptStore store,
                                            RunCoordinator.UnattendedAuthorization authorization) {
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), worker,
                Runnable::run, engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run,
                authorization);
        coordinator.initialize();
        return coordinator;
    }

    private RunCoordinator coordinator(ConfigurationRepository repository, Executor workerExecutor,
                                       Executor mainExecutor) {
        return new RunCoordinator(repository, workerExecutor, mainExecutor,
                engine, TEST_TIMEOUT_MS);
    }

    private static void notifyListeners(RunCoordinator coordinator, RunCoordinator.Snapshot snapshot)
            throws Exception {
        Method notify = RunCoordinator.class.getDeclaredMethod(
                "notifyListeners", RunCoordinator.Snapshot.class);
        notify.setAccessible(true);
        notify.invoke(coordinator, snapshot);
    }

    private static ConfigurationRepository verifiedRepository() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new FakeStore(CONFIG), Runnable::run, Runnable::run);
        repository.start();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        return repository;
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static CountDownLatch terminalLatch(RunCoordinator coordinator) {
        CountDownLatch latch = new CountDownLatch(1);
        coordinator.addListener(snapshot -> {
            if (snapshot.getState() == RunCoordinator.State.TERMINAL) {
                latch.countDown();
            }
        });
        return latch;
    }

    private static CountDownLatch stateLatch(RunCoordinator coordinator, RunCoordinator.State state) {
        CountDownLatch latch = new CountDownLatch(1);
        coordinator.addListener(snapshot -> {
            if (snapshot.getState() == state) {
                latch.countDown();
            }
        });
        return latch;
    }

    private static long logCharacters(RunCoordinator.Snapshot snapshot) {
        return snapshot.getLog().stream().mapToLong(entry -> entry.getMessage().length()).sum();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("timed out waiting for test latch", latch.await(TEST_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitTerminal(RunCoordinator coordinator) {
        if (coordinator.getSnapshot().getState() == RunCoordinator.State.TERMINAL) {
            return;
        }
        CountDownLatch terminal = terminalLatch(coordinator);
        if (coordinator.getSnapshot().getState() == RunCoordinator.State.TERMINAL) {
            terminal.countDown();
        }
        await(terminal);
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

    private static final class FakeEngine implements RunCoordinator.Engine {
        private final Queue<Plan> plans = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        FakeEngine.Plan immediatePlan(RunResult result) {
            FakeEngine.Plan plan = new FakeEngine.Plan();
            plan.result = result;
            plans.add(plan);
            return plan;
        }

        FakeEngine.Plan blockingPlan() {
            FakeEngine.Plan plan = new FakeEngine.Plan();
            plan.block = true;
            plans.add(plan);
            return plan;
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public RunResult run(Context context, ConfigurationRepository.RunAdmission admission,
                             RunConfig.ExecutionMode mode, String operationId,
                             RunEventListener events, IReporter reporter) throws Exception {
            calls.incrementAndGet();
            Plan plan;
            synchronized (plans) {
                plan = plans.remove();
            }
            Call call = new Call(admission, mode, events, reporter);
            plan.call = call;
            plan.entered.countDown();
            if (plan.action != null) {
                plan.action.accept(call);
            }
            if (plan.block) {
                await(plan.release);
            }
            if (plan.failure != null) {
                throw plan.failure;
            }
            return plan.result;
        }

        static final class Plan {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            RunResult result = NativeResultMapper.map(0);
            Exception failure;
            boolean block;
            Consumer<Call> action;
            volatile Call call;

            void finish(RunResult result) {
                this.result = result;
                release.countDown();
            }
        }

        static final class Call {
            final ConfigurationRepository.RunAdmission admission;
            final RunConfig.ExecutionMode mode;
            final RunEventListener events;
            final IReporter reporter;

            Call(ConfigurationRepository.RunAdmission admission, RunConfig.ExecutionMode mode,
                 RunEventListener events, IReporter reporter) {
                this.admission = admission;
                this.mode = mode;
                this.events = events;
                this.reporter = reporter;
            }
        }
    }

    private static final class BlockingCloseLease implements ResourceLease {
        final AtomicInteger closeCount = new AtomicInteger();
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public void close() {
            closeCount.incrementAndGet();
            closeEntered.countDown();
            await(releaseClose);
        }
    }

    private static final class FakeLease implements ResourceLease {
        final AtomicInteger closeCount = new AtomicInteger();
        RuntimeException closeFailure;

        @Override
        public void close() {
            closeCount.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class ErrorCloseLease implements ResourceLease {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
            throw new AssertionError("lease close Error");
        }
    }

    private static class FakeStore implements ConfigurationStore {
        final RunConfig config;
        RuntimeException loadFailure;

        FakeStore(RunConfig config) {
            this.config = config;
        }

        @Override
        public RunConfig load() {
            if (loadFailure != null) {
                throw loadFailure;
            }
            return config;
        }

        @Override
        public boolean write(RunConfig updated) {
            return true;
        }
    }

    private static final class BlockingStore extends FakeStore {
        final CountDownLatch loadEntered = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        volatile Thread loadThread;

        BlockingStore() {
            super(CONFIG);
        }

        @Override
        public RunConfig load() {
            loadThread = Thread.currentThread();
            loadEntered.countDown();
            await(releaseLoad);
            return super.load();
        }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> commands = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        int size() {
            return commands.size();
        }

        void runAll() {
            Runnable command;
            while ((command = commands.poll()) != null) {
                command.run();
            }
        }
    }

    private static final class RejectingExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("injected rejection");
        }
    }
}
