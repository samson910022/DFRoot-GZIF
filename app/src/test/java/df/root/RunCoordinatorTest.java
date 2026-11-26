// Modified 2026-11-24 (DFRoot GZIF): prove atomic run ownership, uncertainty retention, cleanup, and bounded observation. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): cover the admitted operation ID passed to the production engine. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): prove an Error from lease close cannot strand cleanup or later submits. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): prove native rc7 keeps ownership until an explicit recovery. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin recovery to real engine completion and single-claim concurrency. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin the final unattended gate, fail-closed reads, production wiring, and no revocation after engine start. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): prove request-correlated outcomes are fresh and never alter active operation ownership. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin the foreground owner-token denial at submit and before engine start, and no revocation after start. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.1): pin bounded outcome-ledger replay, lock isolation and best-effort async delivery. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.2): pin that the async ledger write is lock-free while the posted publication takes the durable lock off-caller. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.3): pin the bounded outcome-reporter queue, its reject policy and the caller-written ledger under overflow. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.5): pin that an owner destroyed during the blocking final authorization read cannot authorize a not-yet-started engine, with fail-closed, honest denial and no revocation of started work. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.6): add a deterministic source-text guard that the removed authorization-atomicity overclaims are absent from the touched Java/README sections and the honest boundary text is present. No behavior change. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R6.2): pin that LRU eviction returns null (UNCONFIRMED) with exactly 32 retained records, monotonic sequences and unchanged ledger/reporter bounds. See NOTICE.
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
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
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

    /**
     * R5.6 doc-level guard: the final OFF check is a lock-scoped re-read, NOT an
     * atomic transition shared with the PackageManager component write. This
     * test reads checked-in source text only; it changes no runtime behavior.
     */
    @Test
    public void authorizationBoundaryDocsRemoveOverclaimsAndStateTheHonestLimit() throws Exception {
        String coordinator = readSource("app/src/main/java/df/root/RunCoordinator.java");
        String service = readSource("app/src/main/java/df/root/ExploitService.java");
        String settings = readSource("app/src/main/java/df/root/SettingsFragment.java");
        String bootClaim = readSource("app/src/main/java/df/root/BootClaimCoordinator.java");
        String readme = readSource("README.md");

        // Overclaim phrases that must no longer appear in the touched sections.
        assertFalse("RunCoordinator must not call the re-read atomic",
                coordinator.contains("Final, atomic recheck"));
        // HEAD wrapped this phrase as "// ... so they cannot" / "// disagree
        // about authority; ...", so match the stable substring that actually
        // existed rather than a whitespace-dependent concatenation.
        assertFalse("ExploitService must not claim the reads cannot disagree",
                service.contains("disagree about authority"));
        assertFalse("ExploitService must not claim a racing OFF is always honored",
                service.contains("even if it races this read"));
        assertFalse("README must not claim a racing OFF is unconditionally suppressed",
                readme.contains("suppresses an unattended operation even if the toggle races"));

        // Honest replacement text must be present.
        assertTrue("final gate must state it is not atomic with engine.run()",
                coordinator.contains("cannot be made atomic with this read or engine.run()"));
        assertTrue("component recheck must be defense-in-depth and read-only",
                service.contains("defense in depth and READ-ONLY"));
        assertTrue("component recheck must not be an atomic transition",
                service.contains("NOT an atomic transition"));
        assertTrue("OFF write must be outside the coordinator monitor",
                settings.contains("OUTSIDE the coordinator monitor"));
        assertTrue("claim-time read must not be an atomic transition",
                bootClaim.contains("NOT an atomic transition"));
        assertTrue("README must state the monitor boundary",
                readme.contains("outside the coordinator monitor"));
        assertTrue("README must state the residual re-read window",
                readme.contains("cannot be made atomic with the gate read or `engine.run()`"));
    }

    private static String readSource(String relativePath) throws Exception {
        return new String(Files.readAllBytes(TestFixtures.repositoryRoot().resolve(relativePath)),
                StandardCharsets.UTF_8);
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
    public void productionAuthorizationIsUnconditionalRefusalEvenWhenComponentForcedOn()
            throws Exception {
        Context context = context();
        ComponentName component = new ComponentName(context, BootReceiver.class);
        setBootComponentState(context, component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        try {
            RunCoordinator production = RunCoordinator.get(context);
            Field field = RunCoordinator.class.getDeclaredField("unattendedAuthorization");
            field.setAccessible(true);
            RunCoordinator.UnattendedAuthorization gate =
                    (RunCoordinator.UnattendedAuthorization) field.get(production);

            assertSame("production must wire the unconditional refusal, not a component reader",
                    RunCoordinator.PRODUCTION_UNATTENDED_REFUSAL, gate);
            assertFalse(gate.isUnattendedEnabled());

            setBootComponentState(context, component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            assertFalse("a forced-on component must not authorize unattended work",
                    gate.isUnattendedEnabled());
        } finally {
            setBootComponentState(context, component,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            RunCoordinator.resetForTesting();
        }
    }

    @Test
    public void productionCoordinatorRejectsForcedOnUnattendedSubmissionBeforeAnyEffect()
            throws Exception {
        ConfigurationRepository.setInstanceForTesting(verifiedRepository());
        Context context = context();
        ComponentName component = new ComponentName(context, BootReceiver.class);
        try {
            setBootComponentState(context, component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            RunCoordinator production = RunCoordinator.get(context);
            // Keep the host test harmless: no real engine, no real durable store.
            ReflectionHelpers.setField(production, "engine",
                    (RunCoordinator.Engine) (ctx, admission, mode, id, events, reporter) -> {
                        throw new AssertionError("unattended engine must not start in production");
                    });
            OperationAttemptStore store = OperationAttemptStore.inMemory();
            ReflectionHelpers.setField(production, "attemptStore", store);
            production.initialize();
            FakeLease lease = new FakeLease();

            RunCoordinator.Submission rejection = production.submit(context,
                    RunConfig.ExecutionMode.UNATTENDED_BOOT, lease, "forced-on-boot");

            assertFalse(rejection.isAccepted());
            assertTrue(rejection.getReason().contains("unattended admission suppressed"));
            assertEquals(RunCoordinator.State.IDLE, production.getSnapshot().getState());
            assertEquals("no lease is owned by the coordinator", 0, lease.closeCount.get());
            RunCoordinator.RequestOutcomeRecord outcome =
                    production.getRequestOutcome("forced-on-boot");
            assertNotNull(outcome);
            assertEquals(RunCoordinator.RequestOutcome.REJECTED, outcome.getOutcome());
            assertNull("no durable attempt may be published", store.load());
        } finally {
            setBootComponentState(context, component,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            RunCoordinator.resetForTesting();
        }
    }

    @Test
    public void deadOwnerTokenAtSubmitRejectsWithoutAttemptEngineOrLeaseOwnership()
            throws OperationAttemptStore.Failure {
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        RunCoordinator coordinator = gatedCoordinator(store, () -> true);
        RunCoordinator.OwnerToken token = new RunCoordinator.OwnerToken();
        token.invalidate();
        FakeLease lease = new FakeLease();

        RunCoordinator.Submission rejected = coordinator.submit(context(),
                RunConfig.ExecutionMode.MANUAL, lease, 3L, "dead-owner-at-submit", token);

        assertFalse(rejected.isAccepted());
        assertTrue(rejected.getReason().contains("foreground owner was destroyed before admission"));
        assertNull("a dead owner must not publish a durable attempt", store.load());
        assertEquals(0, engine.callCount());
        assertEquals("the coordinator never owns a rejected lease", 0, lease.closeCount.get());
        assertEquals(RunCoordinator.State.IDLE, coordinator.getSnapshot().getState());
        assertEquals("dead-owner-at-submit", coordinator.getSnapshot().getRequestId());
        assertEquals(RunCoordinator.RequestOutcome.REJECTED,
                coordinator.getSnapshot().getRequestOutcome());
    }

    @Test
    public void deadOwnerTokenBeforeEngineStartIsProvenPreEffectRejection()
            throws OperationAttemptStore.Failure {
        ConfigurationRepository repository = verifiedRepository();
        QueueExecutor queuedWorker = new QueueExecutor();
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        RunCoordinator coordinator = new RunCoordinator(repository, queuedWorker, Runnable::run,
                engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run);
        coordinator.initialize();
        engine.immediatePlan(NativeResultMapper.map(0));
        RunCoordinator.OwnerToken token = new RunCoordinator.OwnerToken();
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease,
                null, "owner-died-before-engine", token).isAccepted());
        assertEquals(RunCoordinator.State.STARTING, coordinator.getSnapshot().getState());
        assertNotNull("the attempt was published at admission", store.load());
        assertTrue(store.load().isUnconfirmed());

        token.invalidate();
        queuedWorker.runAll();

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.TERMINAL, terminal.getState());
        assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
        assertEquals(RunResult.Detail.PREFLIGHT_REJECTION, terminal.getResult().getDetail());
        assertTrue(terminal.getReason().contains("foreground owner was destroyed before engine start"));
        assertEquals(0, engine.callCount());
        assertEquals(1, lease.closeCount.get());
        assertNull("a proven pre-effect rejection clears the durable attempt", store.load());
        assertFalse(repository.isFrozen());
    }

    @Test
    public void deadOwnerTokenAfterEngineStartNeverCancelsAndUnverifiedOutcomeRetainsOwnership()
            throws Exception {
        ConfigurationRepository repository = verifiedRepository();
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        RunCoordinator coordinator = new RunCoordinator(repository, worker, Runnable::run,
                engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run);
        coordinator.initialize();
        FakeEngine.Plan plan = engine.blockingPlan();
        RunCoordinator.OwnerToken token = new RunCoordinator.OwnerToken();
        FakeLease lease = new FakeLease();
        CountDownLatch indeterminate = stateLatch(coordinator, RunCoordinator.State.INDETERMINATE);

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL, lease,
                null, "owner-died-after-engine", token).isAccepted());
        assertTrue(plan.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        token.invalidate();

        assertEquals(RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
        assertTrue(plan.call.admission.isActive());
        assertEquals("an already started engine is never cancelled", 1, engine.callCount());
        assertEquals(0, lease.closeCount.get());
        assertTrue(repository.isFrozen());
        assertNotNull(store.load());

        plan.finish(NativeResultMapper.map(7)); // unverified: keep ownership and barrier
        await(indeterminate);

        assertEquals(RunCoordinator.State.INDETERMINATE, coordinator.getSnapshot().getState());
        assertEquals(1, engine.callCount());
        assertEquals(0, lease.closeCount.get());
        assertTrue(plan.call.admission.isActive());
        assertTrue(repository.isFrozen());
        assertNotNull(store.load());
    }

    /**
     * R2-20 liveness: onDestroy() can invalidate the owner token independently
     * while the coordinator holds its monitor inside a blocking authorization
     * read. Owner liveness must be sampled AFTER that read returns, so a dead
     * host cannot authorize a not-yet-started engine. No privileged work is
     * started here, so there is nothing to cancel.
     */
    @Test
    public void ownerInvalidatedDuringBlockingFinalAuthorizationIsNotAuthorized()
            throws Exception {
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        // Read #1 is submit's gate, #2 the post-publish recheck, #3 the final gate.
        BlockingFinalAuthorization authorization = new BlockingFinalAuthorization(3, true);
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), worker,
                Runnable::run, engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run,
                authorization);
        coordinator.initialize();
        engine.immediatePlan(NativeResultMapper.map(0));
        RunCoordinator.OwnerToken token = new RunCoordinator.OwnerToken();
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                lease, null, "owner-died-during-final-auth", token).isAccepted());
        assertNotNull("the attempt was published at admission", store.load());

        // The worker blocks inside the third authorization read (the final gate).
        // Assert state only AFTER the latch proves the worker reached that gate;
        // a fast worker may already have moved from STARTING to RUNNING here.
        assertTrue(authorization.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
        token.invalidate(); // independent onDestroy() seam, while the read blocks
        authorization.release.countDown();
        awaitTerminal(coordinator);

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.TERMINAL, terminal.getState());
        assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
        assertEquals(RunResult.Detail.PREFLIGHT_REJECTION, terminal.getResult().getDetail());
        assertNotNull(terminal.getReason());
        assertTrue(terminal.getReason().contains(
                "foreground owner was destroyed before engine start"));
        assertEquals("no engine was authorized to start", 0, engine.callCount());
        assertEquals(1, lease.closeCount.get());
        assertNull("a proven pre-effect rejection clears the durable attempt", store.load());
        assertEquals(3, authorization.reads());
    }

    /**
     * Reverse interleaving: the host dies while the EARLIER post-publish recheck
     * blocks, so the token is already dead when the final authorization read
     * runs. The owner check after that read still denies the start.
     */
    @Test
    public void ownerInvalidatedBeforeFinalAuthorizationIsNotAuthorized() throws Exception {
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        BlockingFinalAuthorization authorization = new BlockingFinalAuthorization(2, true);
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), worker,
                Runnable::run, engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run,
                authorization);
        coordinator.initialize();
        engine.immediatePlan(NativeResultMapper.map(0));
        RunCoordinator.OwnerToken token = new RunCoordinator.OwnerToken();
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                lease, null, "owner-died-before-final-auth", token).isAccepted());

        assertTrue(authorization.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        token.invalidate();
        authorization.release.countDown();
        awaitTerminal(coordinator);

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
        assertNotNull(terminal.getReason());
        assertTrue(terminal.getReason().contains(
                "foreground owner was destroyed before engine start"));
        assertEquals(0, engine.callCount());
        assertEquals(1, lease.closeCount.get());
        assertNull(store.load());
    }

    /** OFF observed at the final recheck denies with an honest, specific reason. */
    @Test
    public void unattendedAuthOffAtFinalAuthorizationIsDeniedWithHonestReason()
            throws Exception {
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        BlockingFinalAuthorization authorization = new BlockingFinalAuthorization(3, false);
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), worker,
                Runnable::run, engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run,
                authorization);
        coordinator.initialize();
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                lease, null, "auth-off-at-final-gate").isAccepted());
        assertTrue(authorization.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        authorization.release.countDown();
        awaitTerminal(coordinator);

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
        assertNotNull(terminal.getReason());
        assertTrue(terminal.getReason().contains(
                "unattended authorization changed before engine start"));
        assertEquals(0, engine.callCount());
        assertEquals(1, lease.closeCount.get());
        assertNull("a proven pre-effect rejection clears the durable attempt", store.load());
    }

    /** An unreadable/throwing gate at the final recheck fails closed. */
    @Test
    public void unattendedAuthThrowingAtFinalAuthorizationFailsClosed() throws Exception {
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        BlockingFinalAuthorization authorization = new BlockingFinalAuthorization(3, true,
                new IllegalStateException("component state unavailable"));
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), worker,
                Runnable::run, engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run,
                authorization);
        coordinator.initialize();
        FakeLease lease = new FakeLease();

        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                lease, null, "auth-unreadable-at-final-gate").isAccepted());
        assertTrue(authorization.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        authorization.release.countDown();
        awaitTerminal(coordinator);

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
        assertNotNull(terminal.getReason());
        assertTrue(terminal.getReason().contains(
                "unattended authorization changed before engine start"));
        assertEquals(0, engine.callCount());
        assertEquals(1, lease.closeCount.get());
        assertNull(store.load());
    }

    /** The ledger is bounded: the least-recently used oldest record is evicted. */
    @Test
    public void outcomeLedgerIsBoundedAndEvictsOldestRecords() throws Exception {
        RunCoordinator coordinator = coordinator(verifiedRepository(), new QueueExecutor(),
                Runnable::run);
        for (int i = 0; i < RunCoordinator.MAX_OUTCOME_RECORDS + 3; i++) {
            coordinator.noteRequestOutcome("req-" + i,
                    RunCoordinator.RequestOutcome.REJECTED, "reason-" + i);
        }

        assertNull(coordinator.getRequestOutcome("req-0"));
        assertNull(coordinator.getRequestOutcome("req-2"));
        RunCoordinator.RequestOutcomeRecord kept = coordinator.getRequestOutcome("req-3");
        assertNotNull(kept);
        assertEquals("reason-3", kept.getReason());
        assertEquals("req-" + (RunCoordinator.MAX_OUTCOME_RECORDS + 2),
                coordinator.getRequestOutcome(
                        "req-" + (RunCoordinator.MAX_OUTCOME_RECORDS + 2)).getRequestId());
        assertTrue(coordinator.getRequestOutcome(
                "req-" + (RunCoordinator.MAX_OUTCOME_RECORDS + 2)).getSequence()
                > kept.getSequence());

        Field records = RunCoordinator.class.getDeclaredField("outcomeRecords");
        records.setAccessible(true);
        assertEquals(RunCoordinator.MAX_OUTCOME_RECORDS, ((Map<?, ?>) records.get(coordinator)).size());
    }

    /**
     * R6.2: eviction is an absence (UNCONFIRMED), never a fabricated rejection
     * or a claim of completion. Exactly the bounded number of records is
     * retained, publication sequences stay monotonic and both bounds and the
     * lock-free read path are unchanged. Harmless fake IDs/outcomes only.
     */
    @Test
    public void evictedOutcomeIsUnconfirmedAndBoundsAreUnchanged() throws Exception {
        assertEquals("ledger bound", 32, RunCoordinator.MAX_OUTCOME_RECORDS);
        assertEquals("reporter bound", 64, RunCoordinator.MAX_OUTCOME_QUEUE_CAPACITY);
        RunCoordinator coordinator = coordinator(verifiedRepository(), new QueueExecutor(),
                Runnable::run);
        coordinator.setOutcomeExecutorForTesting(Runnable::run); // deterministic seam
        assertEquals("reporter capacity unchanged", RunCoordinator.MAX_OUTCOME_QUEUE_CAPACITY,
                coordinator.outcomeQueueCapacityForTesting());

        int written = RunCoordinator.MAX_OUTCOME_RECORDS + 3;
        long[] sequences = new long[written];
        for (int i = 0; i < written; i++) {
            coordinator.noteRequestOutcome("evict-" + i,
                    RunCoordinator.RequestOutcome.REJECTED, "harmless-" + i);
            RunCoordinator.RequestOutcomeRecord justWritten =
                    coordinator.getRequestOutcome("evict-" + i);
            assertNotNull(justWritten);
            sequences[i] = justWritten.getSequence();
            assertTrue("publication sequence must be monotonic",
                    i == 0 || sequences[i] > sequences[i - 1]);
        }

        Field records = RunCoordinator.class.getDeclaredField("outcomeRecords");
        records.setAccessible(true);
        assertEquals("exactly the bounded number of records is retained",
                RunCoordinator.MAX_OUTCOME_RECORDS,
                ((Map<?, ?>) records.get(coordinator)).size());
        assertNull("evicted evidence is absent, not a fabricated rejection",
                coordinator.getRequestOutcome("evict-0"));
        assertNull(coordinator.getRequestOutcome("evict-2"));
        RunCoordinator.RequestOutcomeRecord oldestRetained =
                coordinator.getRequestOutcome("evict-3");
        assertNotNull(oldestRetained);
        assertTrue("retained sequence is newer than the evicted one",
                oldestRetained.getSequence() > sequences[0]);
        RunCoordinator.RequestOutcomeRecord newest =
                coordinator.getRequestOutcome("evict-" + (written - 1));
        assertNotNull(newest);
        assertTrue(newest.getSequence() > oldestRetained.getSequence());
        assertEquals("reporter capacity still unchanged",
                RunCoordinator.MAX_OUTCOME_QUEUE_CAPACITY,
                coordinator.outcomeQueueCapacityForTesting());
        assertEquals(0, coordinator.outcomeQueueSizeForTesting());
    }

    /** A newer single-slot snapshot never removes an older request's recorded outcome. */
    @Test
    public void newerSingleSlotSnapshotDoesNotRemoveOlderRecordedOutcome() {
        RunCoordinator coordinator = coordinator(verifiedRepository(), new QueueExecutor(),
                Runnable::run);
        coordinator.noteRequestOutcome("A", RunCoordinator.RequestOutcome.REJECTED, "reason-A");
        coordinator.noteRequestOutcome("B", RunCoordinator.RequestOutcome.ACCEPTED, null);

        RunCoordinator.Snapshot latest = coordinator.getSnapshot();
        assertEquals("B", latest.getRequestId());
        assertEquals(RunCoordinator.RequestOutcome.ACCEPTED, latest.getRequestOutcome());
        RunCoordinator.RequestOutcomeRecord a = coordinator.getRequestOutcome("A");
        assertNotNull(a);
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, a.getOutcome());
        assertEquals("reason-A", a.getReason());
    }

    /** Null/legacy IDs and non-outcomes are not replayable observations. */
    @Test
    public void nullLegacyAndNoneOutcomesAreNotRecorded() throws Exception {
        RunCoordinator coordinator = coordinator(verifiedRepository(), new QueueExecutor(),
                Runnable::run);
        coordinator.noteRequestOutcome(null, RunCoordinator.RequestOutcome.REJECTED, "null id");
        coordinator.noteRequestOutcome("", RunCoordinator.RequestOutcome.REJECTED, "empty id");
        coordinator.noteRequestOutcome("none-id", RunCoordinator.RequestOutcome.NONE, null);
        coordinator.noteRequestRejected("legacy rejection");
        assertTrue(coordinator.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease()).isAccepted());

        assertNull(coordinator.getRequestOutcome(null));
        assertNull(coordinator.getRequestOutcome(""));
        assertNull(coordinator.getRequestOutcome("none-id"));
        Field records = RunCoordinator.class.getDeclaredField("outcomeRecords");
        records.setAccessible(true);
        assertTrue(((Map<?, ?>) records.get(coordinator)).isEmpty());
    }

    /** Ledger reads and async records take only outcomeLock, not the blocked durable lock. */
    @Test
    public void blockedStoreDoesNotBlockLedgerReadOrAsyncRecord() throws Exception {
        BlockingPublishStore store = new BlockingPublishStore();
        QueueExecutor queuedWorker = new QueueExecutor();
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), queuedWorker,
                Runnable::run, engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run,
                () -> true, new QueueExecutor());
        coordinator.initialize();

        Thread submitter = new Thread(() -> coordinator.submit(context(),
                RunConfig.ExecutionMode.MANUAL, new FakeLease(), "blocked-A"));
        submitter.setDaemon(true);
        submitter.start();
        await(store.publishEntered); // submit now holds `lock` inside the checked publish

        ExecutorService probe = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = probe.submit(
                    () -> assertNull(coordinator.getRequestOutcome("blocked-A")));
            read.get(2L, TimeUnit.SECONDS);
            Future<?> record = probe.submit(() -> coordinator.noteRequestOutcomeAsync(
                    "blocked-A", RunCoordinator.RequestOutcome.REJECTED,
                    "recorded while store blocked"));
            record.get(2L, TimeUnit.SECONDS);

            RunCoordinator.RequestOutcomeRecord observed =
                    coordinator.getRequestOutcome("blocked-A");
            assertNotNull(observed);
            assertEquals(RunCoordinator.RequestOutcome.REJECTED, observed.getOutcome());
            assertEquals("recorded while store blocked", observed.getReason());
        } finally {
            store.releasePublish.countDown();
            probe.shutdownNow();
            submitter.join(TEST_TIMEOUT_MS);
        }
    }

    /**
     * The inline part of noteRequestOutcomeAsync touches only outcomeLock; the
     * posted noteRequestOutcome takes lock -> outcomeLock and therefore cannot
     * publish until the checked store releases its monitor.
     */
    @Test
    public void asyncOutcomeRecordIsLockFreeAndPublishesOffCallerAfterCheckedStorageReleases()
            throws Exception {
        BlockingPublishStore store = new BlockingPublishStore();
        QueueExecutor outcomeExecutor = new QueueExecutor();
        QueueExecutor observers = new QueueExecutor();
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), new QueueExecutor(),
                observers, engine, TEST_TIMEOUT_MS, store, () -> 7L, Runnable::run,
                () -> true, outcomeExecutor);
        coordinator.initialize();

        Thread submitter = new Thread(() -> coordinator.submit(context(),
                RunConfig.ExecutionMode.UNATTENDED_BOOT, new FakeLease(), "boot-holder"));
        submitter.setDaemon(true);
        submitter.start();
        assertTrue("boot publish must hold the durable lock",
                store.publishEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Inline: the ledger record exists and the snapshot is untouched even
        // though the checked-store monitor is held by the boot submitter.
        coordinator.noteRequestOutcomeAsync("ui-failure", RunCoordinator.RequestOutcome.REJECTED,
                "Unable to start operation service: fake denial");
        RunCoordinator.RequestOutcomeRecord recorded =
                coordinator.getRequestOutcome("ui-failure");
        assertNotNull(recorded);
        assertEquals("Unable to start operation service: fake denial", recorded.getReason());
        assertEquals(1, outcomeExecutor.size());
        assertNull("no admission snapshot while the checked publish is blocked",
                coordinator.getSnapshot().getRequestId());

        // Posted: publication runs on another thread and blocks on the lock.
        CountDownLatch publicationDone = new CountDownLatch(1);
        Thread publisher = new Thread(() -> {
            try {
                outcomeExecutor.runAll();
            } finally {
                publicationDone.countDown();
            }
        }, "r5-2-outcome-publisher");
        publisher.setDaemon(true);
        publisher.start();
        assertFalse("posted publication must wait for the checked-store monitor",
                publicationDone.await(150L, TimeUnit.MILLISECONDS));
        assertNull("no snapshot publication while storage is blocked",
                coordinator.getSnapshot().getRequestId());

        store.releasePublish.countDown();
        assertTrue("publication completes once the checked publish returns",
                publicationDone.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        publisher.join(TEST_TIMEOUT_MS);
        submitter.join(TEST_TIMEOUT_MS);
        assertEquals("ui-failure", coordinator.getSnapshot().getRequestId());
        assertEquals(RunCoordinator.RequestOutcome.REJECTED,
                coordinator.getSnapshot().getRequestOutcome());
        assertEquals("Unable to start operation service: fake denial",
                coordinator.getSnapshot().getRequestFailure());
        assertEquals("checked boot admission is still authoritative",
                RunCoordinator.RequestOutcome.ACCEPTED,
                coordinator.getRequestOutcome("boot-holder").getOutcome());
        assertNotNull(store.load());
        assertTrue(store.load().isUnconfirmed());
    }

    /**
     * R5.3: the reporter queue is bounded with an abort policy. Overflow is
     * allowed (the ledger was written on the caller), but the queue never grows
     * past its bound and no retry or extra admission work is created.
     */
    @Test
    public void outcomeReporterQueueIsBoundedAndOverflowKeepsLedgerRecord() throws Exception {
        Method factory = RunCoordinator.class.getDeclaredMethod("newOutcomeExecutor");
        factory.setAccessible(true);
        ExecutorService reporter = (ExecutorService) factory.invoke(null);
        ThreadPoolExecutor reporterPool = (ThreadPoolExecutor) reporter;
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), new QueueExecutor(),
                Runnable::run, engine, TEST_TIMEOUT_MS, reporter);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            reporter.execute(() -> {
                entered.countDown();
                try {
                    release.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue("the reporter worker was not pinned",
                    entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));

            int capacity = coordinator.outcomeQueueCapacityForTesting();
            assertEquals(RunCoordinator.MAX_OUTCOME_QUEUE_CAPACITY, capacity);
            assertEquals(0, reporterPool.getQueue().size());
            for (int i = 0; i < capacity * 2; i++) {
                coordinator.noteRequestOutcomeAsync("bounded-" + i,
                        RunCoordinator.RequestOutcome.REJECTED, "reason-" + i);
            }
            assertEquals("the reporter queue grew past its bound", capacity,
                    coordinator.outcomeQueueSizeForTesting());
            assertEquals(capacity, reporterPool.getQueue().size());
            assertNotNull(coordinator.getRequestOutcome("bounded-" + (capacity * 2 - 1)));
            Field records = RunCoordinator.class.getDeclaredField("outcomeRecords");
            records.setAccessible(true);
            assertEquals("the ledger also stays bounded",
                    RunCoordinator.MAX_OUTCOME_RECORDS,
                    ((Map<?, ?>) records.get(coordinator)).size());
            assertThrows(RejectedExecutionException.class, () -> reporter.execute(() -> { }));
        } finally {
            release.countDown();
            reporter.shutdownNow();
        }
    }

    /** A rejected observer post leaves the record readable; duplicates do not add records. */
    @Test
    public void observerPostFailureLeavesLedgerRecordAndDuplicateIsIdempotent() {
        RunCoordinator coordinator = new RunCoordinator(verifiedRepository(), new QueueExecutor(),
                Runnable::run, engine, TEST_TIMEOUT_MS, new RejectingExecutor());
        RunCoordinator.Snapshot before = coordinator.getSnapshot();

        coordinator.noteRequestOutcomeAsync("A", RunCoordinator.RequestOutcome.REJECTED, "reason-A");
        assertSame(before, coordinator.getSnapshot()); // delivery failed, ledger did not
        RunCoordinator.RequestOutcomeRecord first = coordinator.getRequestOutcome("A");
        assertNotNull(first);
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, first.getOutcome());
        assertEquals("reason-A", first.getReason());

        coordinator.noteRequestOutcomeAsync("A", RunCoordinator.RequestOutcome.REJECTED, "reason-A");
        assertSame(first, coordinator.getRequestOutcome("A"));
        assertEquals(first.getSequence(), coordinator.getRequestOutcome("A").getSequence());
    }

    /** Both correlated submit paths write the ledger without changing ownership. */
    @Test
    public void submitAcceptanceAndFinalGateRejectionAreRecorded() {
        RunCoordinator accepted = coordinator(verifiedRepository(), new QueueExecutor(),
                Runnable::run);
        assertTrue(accepted.submit(context(), RunConfig.ExecutionMode.MANUAL,
                new FakeLease(), "A").isAccepted());
        RunCoordinator.RequestOutcomeRecord acceptance = accepted.getRequestOutcome("A");
        assertNotNull(acceptance);
        assertEquals(RunCoordinator.RequestOutcome.ACCEPTED, acceptance.getOutcome());

        MutableAuthorization authorization = new MutableAuthorization(false);
        RunCoordinator denied = new RunCoordinator(verifiedRepository(), new QueueExecutor(),
                Runnable::run, engine, TEST_TIMEOUT_MS, OperationAttemptStore.inMemory(),
                () -> 7L, Runnable::run, authorization, new RejectingExecutor());
        denied.initialize();
        RunCoordinator.Submission rejection = denied.submit(context(),
                RunConfig.ExecutionMode.UNATTENDED_BOOT, new FakeLease(), "B");
        assertFalse(rejection.isAccepted());
        RunCoordinator.RequestOutcomeRecord denial = denied.getRequestOutcome("B");
        assertNotNull(denial);
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, denial.getOutcome());
        assertEquals(RunCoordinator.UNATTENDED_AUTH_REJECT, denial.getReason());
        assertEquals(RunCoordinator.State.IDLE, denied.getSnapshot().getState());
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

    /** Host-only component-state stand-in: no device, no PackageManager write to a real component. */
    private static void setBootComponentState(Context context, ComponentName component, int state) {
        context.getPackageManager().setComponentEnabledSetting(component, state,
                PackageManager.DONT_KILL_APP);
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

    /**
     * Harmless, deterministic authorization seam: one chosen read signals
     * entry and blocks, then returns a fixed value (or throws). This pins the
     * final-gate read ordering without sleeps, devices or privileged work.
     */
    private static final class BlockingFinalAuthorization
            implements RunCoordinator.UnattendedAuthorization {
        private final AtomicInteger reads = new AtomicInteger();
        private final int targetRead;
        private final boolean resultAtTarget;
        private final RuntimeException failureAtTarget;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        BlockingFinalAuthorization(int targetRead, boolean resultAtTarget) {
            this(targetRead, resultAtTarget, null);
        }

        BlockingFinalAuthorization(int targetRead, boolean resultAtTarget,
                                   RuntimeException failureAtTarget) {
            this.targetRead = targetRead;
            this.resultAtTarget = resultAtTarget;
            this.failureAtTarget = failureAtTarget;
        }

        @Override
        public boolean isUnattendedEnabled() {
            int read = reads.incrementAndGet();
            if (read != targetRead) {
                return true;
            }
            entered.countDown();
            await(release);
            if (failureAtTarget != null) {
                throw failureAtTarget;
            }
            return resultAtTarget;
        }

        int reads() {
            return reads.get();
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

    /** Blocks inside publish() while the coordinator holds its durable lock. */
    private static final class BlockingPublishStore implements OperationAttemptStore {
        private final OperationAttemptStore delegate = OperationAttemptStore.inMemory();
        final CountDownLatch publishEntered = new CountDownLatch(1);
        final CountDownLatch releasePublish = new CountDownLatch(1);

        @Override
        public OperationAttempt load() throws OperationAttemptStore.Failure {
            return delegate.load();
        }

        @Override
        public boolean publish(OperationAttempt attempt) throws OperationAttemptStore.Failure {
            publishEntered.countDown();
            await(releasePublish);
            return delegate.publish(attempt);
        }

        @Override
        public boolean clear(String operationId) throws OperationAttemptStore.Failure {
            return delegate.clear(operationId);
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
