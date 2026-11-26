// Modified 2026-11-24 (DFRoot GZIF): verify Activity detachment, service-only requests, coordinator observation, and single-flight UI gating. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): recheck service request wiring against operation-ID publication. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): cover request-correlated acknowledgement, overlapping rejections and cancelled service-local work. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): drive the outcome-report executor explicitly in failure/cancellation tests. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.1): cover stopped-Activity outcome reconciliation from the bounded ledger without replay. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.2): pin the synchronous start-failure path to the lock-free ledger write with blocked checked storage and a throwing starter. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.3): keep failure reports observable on the existing queue seam and pin Activity reconciliation when both dispatchers reject. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R6.1): cover the bounded, lifecycle-scoped read-only outcome watch on a continuously visible Activity. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R6.2): pin that an evicted/absent ledger record stays explicitly unconfirmed on the watch and a later start, with no replay, fabricated reason or durable mutation. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R6.4): install the deterministic inline foreground-host seam at every service creation so existing synchronous Activity assertions stay valid. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R7.2): pin one ledger observation per watch tick with a deterministic host-only lookup seam, including a late final-tick outcome and the unconfirmed case. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R7.2 repair): add a disagreeing-observation regression test so the final tick cannot use a second ledger read that leaves the UI blank. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class MainActivityTest {
    private static final long TEST_TIMEOUT_MS = 5_000L;
    private static final int MAX_UI_OUTPUT_CHARACTERS = 32 * 1024;

    @Rule
    public TemporaryFolder assetFolder = new TemporaryFolder();

    private final List<ActivityController<MainActivity>> activityControllers = new ArrayList<>();
    private final List<ServiceController<ExploitService>> serviceControllers = new ArrayList<>();
    private final List<BlockingEngine> blockingEngines = new ArrayList<>();
    private final List<ExecutorService> workers = new ArrayList<>();
    private FakeStore store;
    private QueueExecutor configExecutor;
    private ConfigurationRepository repository;
    private RunCoordinator coordinator;
    private QueueExecutor coordinatorWorker;
    private QueueExecutor coordinatorObservers;
    private ImmediateEngine immediateEngine;
    private ManualOutcomeWatchScheduler outcomeWatch;

    @Before
    public void setUp() {
        ConfigurationRepository.resetForTesting();
        RunCoordinator.resetForTesting();
        MainActivity.resetResultToastForTesting();
        MainActivity.resetAvailabilityEnvironmentForTesting();
        MainActivity.resetRequestOutcomeLookupForTesting();
        // Deterministic seam: the diagnostic runs inline on the test caller so
        // existing synchronous assertions are unchanged; async behavior is
        // covered separately with controlled executors.
        MainActivity.installDiagnosticExecutorsForTesting(Runnable::run, Runnable::run);
        // Deterministic read-only watch: no real delayed task ever exists.
        outcomeWatch = new ManualOutcomeWatchScheduler();
        MainActivity.installOutcomeWatchSchedulerForTesting(outcomeWatch);
        ShadowToast.reset();
        installManager(SuManager.CLASSIC.getPackageName());
        installManager(SuManager.NEXT.getPackageName());
    }

    @After
    public void tearDown() throws Exception {
        for (ServiceController<ExploitService> controller : serviceControllers) {
            controller.destroy();
        }
        for (ActivityController<MainActivity> controller : activityControllers) {
            controller.pause().stop().destroy();
        }
        for (BlockingEngine engine : blockingEngines) {
            engine.finish();
        }
        if (coordinatorWorker != null) {
            coordinatorWorker.runAll();
        }
        if (coordinatorObservers != null) {
            coordinatorObservers.runAll();
        }
        for (ExecutorService worker : workers) {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        MainActivity.resetAvailabilityEnvironmentForTesting();
        MainActivity.resetDiagnosticExecutorsForTesting();
        MainActivity.resetOutcomeWatchSchedulerForTesting();
        MainActivity.resetRequestOutcomeLookupForTesting();
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        ShadowLooper.idleMainLooper();
        ShadowPowerManager.clearWakeLocks();
        ShadowToast.reset();
    }

    @Test
    public void savingConfigurationDisablesManagerAndRunControls() {
        installConfiguration(RunConfig.defaults(), false);
        installIdleCoordinator();
        MainActivity activity = launch();

        assertFalse(activity.findViewById(R.id.spinnerSuManager).isEnabled());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());

        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertTrue(activity.findViewById(R.id.spinnerSuManager).isEnabled());
        assertFalse(repository.isWritePending());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
    }

    @Test
    public void managerSelectionIsPersistedBeforeTheUiReflectsIt() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.CLASSIC.getPackageName()), true);
        installIdleCoordinator();
        MainActivity activity = launch();
        Spinner spinner = activity.findViewById(R.id.spinnerSuManager);

        int selectedPosition = selectManager(activity, SuManager.NEXT.getPackageName());
        assertEquals("callback position " + selectedPosition,
                SuManager.NEXT.getPackageName(), ReflectionHelpers.getField(
                        spinner.getAdapter().getItem(selectedPosition), "packageName"));
        assertFalse(spinner.isEnabled());
        assertEquals(0, store.writes.size());

        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(1, store.writes.size());
        assertEquals(SuManager.NEXT.getPackageName(), store.writes.get(0).getManagerPackage());
        assertEquals(SuManager.NEXT.getPackageName(), selectedPackage(spinner));
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
    }

    @Test
    public void failedManagerWriteRevertsTheSpinnerAndAppendsConfigurationError() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.CLASSIC.getPackageName()), true);
        installIdleCoordinator();
        MainActivity activity = launch();
        store.results.add(false);
        store.results.add(true);

        selectManager(activity, SuManager.NEXT.getPackageName());
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        Spinner spinner = activity.findViewById(R.id.spinnerSuManager);
        assertFalse(spinner.isEnabled());
        assertEquals(SuManager.CLASSIC.getPackageName(), selectedPackage(spinner));
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Configuration error"));
        assertEquals(SuManager.CLASSIC.getPackageName(),
                repository.getConfirmedSnapshot().getManagerPackage());
    }

    @Test
    public void unsupportedConfirmedManagerIsClearedThroughTheRepository() {
        installConfiguration(new RunConfig("com.example.unsupported", false, false, 0, 12), true);
        installIdleCoordinator();
        MainActivity activity = launch();
        Spinner spinner = activity.findViewById(R.id.spinnerSuManager);

        assertFalse(spinner.isEnabled());
        assertTrue(repository.isWritePending());
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertTrue(spinner.isEnabled());
        assertNull(selectedPackage(spinner));
        assertNull(repository.getConfirmedSnapshot().getManagerPackage());
        assertEquals(13, repository.getConfirmedSnapshot().getGeneration());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
    }

    @Test
    public void selectedManagerIsRequiredBeforeRunCanBeRequested() {
        installConfiguration(RunConfig.defaults(), true);
        installIdleCoordinator();
        MainActivity activity = launch();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        activity.findViewById(R.id.btnRun).performClick();

        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(starter.requests.isEmpty());
    }

    @Test
    public void observersAreRegisteredOnStartRemovedOnStopAndRemovedAgainOnDestroy() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        ActivityController<MainActivity> controller = launchController();
        MainActivity activity = controller.get();
        ConfigurationRepository.Listener configurationListener = ReflectionHelpers.getField(
                activity, "mConfigurationListener");
        RunCoordinator.Listener coordinatorListener = ReflectionHelpers.getField(
                activity, "mCoordinatorListener");
        Set<?> coordinatorListeners = ReflectionHelpers.getField(coordinator, "listeners");

        assertTrue(repository.hasListener(configurationListener));
        assertTrue(coordinatorListeners.contains(coordinatorListener));

        controller.stop();
        assertFalse(repository.hasListener(configurationListener));
        assertFalse(coordinatorListeners.contains(coordinatorListener));

        controller.start();
        assertTrue(repository.hasListener(configurationListener));
        assertTrue(coordinatorListeners.contains(coordinatorListener));

        controller.stop().destroy();
        activityControllers.remove(controller);
        assertFalse(repository.hasListener(configurationListener));
        assertFalse(coordinatorListeners.contains(coordinatorListener));
    }

    @Test
    public void eligibleClickRequestsExactlyOneManualStartAndClearsOutputOptimistically() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 41), true);
        installIdleCoordinator();
        MainActivity activity = launch();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);
        TextView output = activity.findViewById(R.id.outputView);
        output.setText("previous operation output");

        activity.findViewById(R.id.btnRun).performClick();
        activity.findViewById(R.id.btnRun).performClick();

        assertEquals(1, starter.requests.size());
        assertEquals(Long.valueOf(41), starter.requests.get(0));
        assertNotNull(starter.requestIds.get(0));
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals("", output.getText().toString());
    }

    /** An unrelated acceptance must not be mistaken for this Activity's acknowledgement. */
    @Test
    public void unrelatedAcceptedSnapshotDoesNotClearAnotherRequestsPending() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 42), true);
        installIdleCoordinator();
        MainActivity first = launch();
        MainActivity second = launch();

        first.findViewById(R.id.btnRun).performClick();
        second.findViewById(R.id.btnRun).performClick();
        Intent firstRequest = nextServiceRequest();
        String firstRequestId = firstRequest.getStringExtra(ExploitService.EXTRA_REQUEST_ID);
        assertNotNull(firstRequestId);
        assertNotNull(nextServiceRequest().getStringExtra(ExploitService.EXTRA_REQUEST_ID));

        ServiceController<ExploitService> controller = Robolectric.buildService(
                ExploitService.class).create().withIntent(firstRequest);
        serviceControllers.add(controller);
        controller.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        controller.get().setAdmissionExecutorForTesting(Runnable::run);
        controller.startCommand(0, 201);
        coordinatorObservers.runAll();

        RunCoordinator.Snapshot admitted = coordinator.getSnapshot();
        assertEquals(RunCoordinator.RequestOutcome.ACCEPTED, admitted.getRequestOutcome());
        assertEquals(firstRequestId, admitted.getRequestId());
        assertFalse(ReflectionHelpers.getField(first, "mManualStartPending"));
        assertTrue("a different request's acceptance must not clear B's pending state",
                ReflectionHelpers.getField(second, "mManualStartPending"));
        assertEquals("", output(second));
        assertFalse(second.findViewById(R.id.btnRun).isEnabled());
        assertEquals(0, immediateEngine.calls.get());
    }

    /** A rejection for B while A is active must reach B only, without changing A. */
    @Test
    public void correlatedRejectionWhileAnotherOperationIsActiveClearsOnlyItsOwnPending() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 43), true);
        installIdleCoordinator();
        MainActivity first = launch();
        MainActivity second = launch();

        first.findViewById(R.id.btnRun).performClick();
        second.findViewById(R.id.btnRun).performClick();
        Intent firstRequest = nextServiceRequest();
        Intent secondRequest = nextServiceRequest();
        String secondRequestId = secondRequest.getStringExtra(ExploitService.EXTRA_REQUEST_ID);
        assertNotNull(secondRequestId);

        ServiceController<ExploitService> acceptedController = Robolectric.buildService(
                ExploitService.class).create().withIntent(firstRequest);
        serviceControllers.add(acceptedController);
        acceptedController.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        acceptedController.get().setAdmissionExecutorForTesting(Runnable::run);
        acceptedController.startCommand(0, 202);
        coordinatorObservers.runAll();
        RunCoordinator.Snapshot admitted = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.STARTING, admitted.getState());

        ServiceController<ExploitService> rejectedController = Robolectric.buildService(
                ExploitService.class).create().withIntent(secondRequest);
        serviceControllers.add(rejectedController);
        rejectedController.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        rejectedController.get().setAdmissionExecutorForTesting(Runnable::run);
        rejectedController.startCommand(0, 203);
        coordinatorObservers.runAll();

        RunCoordinator.Snapshot rejection = coordinator.getSnapshot();
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, rejection.getRequestOutcome());
        assertEquals(secondRequestId, rejection.getRequestId());
        assertNotNull(rejection.getRequestFailure());
        assertTrue(rejection.getRequestFailure().contains("active"));
        assertEquals(admitted.getState(), rejection.getState());
        assertSame(admitted.getResult(), rejection.getResult());
        assertEquals(admitted.getOperationId(), rejection.getOperationId());
        assertEquals(admitted.getConfigGeneration(), rejection.getConfigGeneration());
        assertEquals(admitted.getLog(), rejection.getLog());

        assertFalse(ReflectionHelpers.getField(second, "mManualStartPending"));
        assertTrue(output(second).contains("Operation request rejected"));
        assertTrue(output(second).contains("active"));
        assertFalse("B's rejection must not be attributed to A",
                output(first).contains("Operation request rejected"));
        assertFalse(ReflectionHelpers.getField(first, "mManualStartPending"));
        assertEquals(0, immediateEngine.calls.get());
        assertFalse("active work must keep the foreground host alive",
                Shadows.shadowOf(rejectedController.get()).isStoppedBySelf());
    }

    /**
     * R2-17/R5.3: when even the reporter queue rejects, no snapshot publication
     * reaches the Activity — but the caller-written ledger still lets the
     * returned Activity reconcile its own request on onStart(), without replay.
     */
    @Test
    public void dispatchFailureOutcomeIsReconciledByTheActivityOnStart() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 61), true);
        coordinatorWorker = new QueueExecutor();
        coordinatorObservers = new QueueExecutor();
        immediateEngine = new ImmediateEngine();
        coordinator = new RunCoordinator(repository, coordinatorWorker, coordinatorObservers,
                immediateEngine, 0L, command -> {
                    throw new RejectedExecutionException("reporter full");
                });
        RunCoordinator.setInstanceForTesting(coordinator);
        ActivityController<MainActivity> activity = launchController();
        activity.get().findViewById(R.id.btnRun).performClick();
        Intent request = nextServiceRequest();
        String requestId = request.getStringExtra(ExploitService.EXTRA_REQUEST_ID);
        assertNotNull(requestId);
        assertTrue(ReflectionHelpers.getField(activity.get(), "mManualStartPending"));
        RunCoordinator.Snapshot before = coordinator.getSnapshot();

        ServiceController<ExploitService> serviceController = Robolectric.buildService(
                ExploitService.class).create().withIntent(request);
        serviceControllers.add(serviceController);
        serviceController.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        serviceController.get().setAdmissionExecutorForTesting(command -> {
            throw new RejectedExecutionException("admission full");
        });
        serviceController.startCommand(0, 810);

        assertSame("no observer publication happened", before, coordinator.getSnapshot());
        RunCoordinator.RequestOutcomeRecord recorded = coordinator.getRequestOutcome(requestId);
        assertNotNull(recorded);
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, recorded.getOutcome());
        assertTrue(recorded.getReason().contains("admission dispatch failed"));
        assertTrue(ReflectionHelpers.getField(activity.get(), "mManualStartPending"));

        activity.stop();
        activity.start();

        assertFalse("the Activity must reconcile from its own ledger record",
                ReflectionHelpers.getField(activity.get(), "mManualStartPending"));
        assertTrue(output(activity.get()).contains("Operation request rejected"));
        assertTrue(output(activity.get()).contains("admission dispatch failed"));
        assertEquals(0, immediateEngine.calls.get());
        assertEquals(0, coordinatorWorker.commands.size());
        assertSame("reconciliation is read-only", before, coordinator.getSnapshot());
    }

    /** R6.1: the SAME visible Activity reconciles its dropped reporter rejection via the watch. */
    @Test
    public void visibleActivityReconcilesDroppedReporterRejectionWithoutLifecycleRestart() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 71), true);
        coordinatorWorker = new QueueExecutor();
        coordinatorObservers = new QueueExecutor();
        immediateEngine = new ImmediateEngine();
        coordinator = new RunCoordinator(repository, coordinatorWorker, coordinatorObservers,
                immediateEngine, 0L, command -> {
                    throw new RejectedExecutionException("reporter full");
                });
        RunCoordinator.setInstanceForTesting(coordinator);
        ActivityController<MainActivity> controller = launchController();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.btnRun).performClick();
        assertTrue("a click arms exactly one watch tick immediately", outcomeWatch.hasPending());
        Intent request = nextServiceRequest();
        String requestId = request.getStringExtra(ExploitService.EXTRA_REQUEST_ID);
        assertNotNull(requestId);

        ServiceController<ExploitService> serviceController = Robolectric.buildService(
                ExploitService.class).create().withIntent(request);
        serviceControllers.add(serviceController);
        serviceController.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        serviceController.get().setAdmissionExecutorForTesting(command -> {
            throw new RejectedExecutionException("admission full");
        });
        serviceController.startCommand(0, 811);

        RunCoordinator.Snapshot before = coordinator.getSnapshot();
        assertSame("no observer publication happened", before, coordinator.getSnapshot());
        RunCoordinator.RequestOutcomeRecord recorded = coordinator.getRequestOutcome(requestId);
        assertNotNull(recorded);
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, recorded.getOutcome());
        assertTrue(recorded.getReason().contains("admission dispatch failed"));
        assertTrue("still pending while no notification is delivered",
                ReflectionHelpers.getField(activity, "mManualStartPending"));

        outcomeWatch.runAll(); // read-only ticks only; no stop/start of the Activity

        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("admission dispatch failed"));
        assertSame("read-only reconciliation never changed the snapshot", before,
                coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("no replayed service request",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
        assertFalse("no further tick was armed", outcomeWatch.hasPending());
    }

    /** R6.1: a synchronous start failure is reconciled while visible, even with no observer. */
    @Test
    public void synchronousStartFailureWithDroppedReporterIsReconciledWhileVisible() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 72), true);
        coordinatorWorker = new QueueExecutor();
        coordinatorObservers = new QueueExecutor();
        immediateEngine = new ImmediateEngine();
        coordinator = new RunCoordinator(repository, coordinatorWorker, coordinatorObservers,
                immediateEngine, 0L, command -> {
                    throw new RejectedExecutionException("reporter full");
                });
        RunCoordinator.setInstanceForTesting(coordinator);
        ActivityController<MainActivity> controller = launchController();
        MainActivity activity = controller.get();
        FailingStarter starter = new FailingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);
        RunCoordinator.Snapshot before = coordinator.getSnapshot();

        activity.findViewById(R.id.btnRun).performClick();

        assertEquals(1, starter.calls);
        assertTrue("the watch is armed after both success and synchronous failure",
                outcomeWatch.hasPending());
        assertTrue(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertEquals(0, coordinatorObservers.commands.size());
        assertSame(before, coordinator.getSnapshot());
        String requestId = (String) ReflectionHelpers.getField(activity, "mPendingRequestId");
        RunCoordinator.RequestOutcomeRecord recorded = coordinator.getRequestOutcome(requestId);
        assertNotNull(recorded);
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, recorded.getOutcome());
        assertEquals("Unable to start operation service: service start denied",
                recorded.getReason());

        outcomeWatch.runAll();

        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("Unable to start operation service"));
        assertTrue(output(activity).contains("service start denied"));
        assertFalse(output(activity).contains("outcome unconfirmed"));
        assertSame(before, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("the failed starter started no service and the watch replays nothing",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
    }

    /** R6.1: ordinary correlated observer delivery clears pending and cancels the watch. */
    @Test
    public void correlatedObserverDeliveryCancelsTheWatch() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 73), true);
        installIdleCoordinator();
        ActivityController<MainActivity> controller = launchController();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.btnRun).performClick();
        Intent request = nextServiceRequest();
        String requestId = request.getStringExtra(ExploitService.EXTRA_REQUEST_ID);
        assertNotNull(requestId);
        assertTrue(outcomeWatch.hasPending());
        int scheduledBefore = outcomeWatch.schedules;

        coordinator.noteRequestOutcome(requestId, RunCoordinator.RequestOutcome.REJECTED,
                "rejection delivered to the observer");
        coordinatorObservers.runAll();

        assertFalse("correlated delivery cancels the watch", outcomeWatch.hasPending());
        assertEquals("no further tick was scheduled", scheduledBefore, outcomeWatch.schedules);
        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertEquals(1, occurrences(output(activity),
                "rejection delivered to the observer"));
        assertFalse(output(activity).contains("outcome unconfirmed"));
        assertEquals(0, immediateEngine.calls.get());

        outcomeWatch.runAll(); // no pending tick can run
        assertEquals(scheduledBefore, outcomeWatch.schedules);
        assertEquals(1, occurrences(output(activity), "rejection delivered to the observer"));
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());
    }

    /** R6.1: the 25-tick window is exact, and stop/destroy leave no pending tick. */
    @Test
    public void outcomeWatchIsBoundedAndCancelledOnStopAndDestroy() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 74), true);
        installIdleCoordinator();
        int cap = (Integer) ReflectionHelpers.getStaticField(
                MainActivity.class, "OUTCOME_WATCH_MAX_TICKS");
        assertEquals(25, cap);

        MainActivity capped = launch();
        MainActivity.installServiceStarterForTesting(capped, new RecordingStarter());
        capped.findViewById(R.id.btnRun).performClick();
        assertTrue(outcomeWatch.hasPending());

        outcomeWatch.runAll();

        assertEquals("exactly one tick per armed interval", cap, outcomeWatch.schedules);
        assertEquals("the last tick ran, but nothing was re-armed", cap, outcomeWatch.ticks);
        assertFalse(outcomeWatch.hasPending());

        ActivityController<MainActivity> lifecycle = launchController();
        MainActivity activity = lifecycle.get();
        MainActivity.installServiceStarterForTesting(activity, new RecordingStarter());
        assertFalse(outcomeWatch.hasPending());
        activity.findViewById(R.id.btnRun).performClick();
        assertTrue(outcomeWatch.hasPending());

        lifecycle.stop();
        assertFalse("stop cancels the pending tick", outcomeWatch.hasPending());
        lifecycle.start();
        assertTrue("a still-pending request re-arms on start", outcomeWatch.hasPending());
        outcomeWatch.runNext();
        assertTrue(outcomeWatch.hasPending());

        lifecycle.destroy();
        activityControllers.remove(lifecycle);
        assertFalse("destroy cancels the pending tick", outcomeWatch.hasPending());
        int ticksAfterDestroy = outcomeWatch.ticks;
        outcomeWatch.runAll();
        assertEquals("no tick can run after destruction", ticksAfterDestroy, outcomeWatch.ticks);
        assertEquals(0, immediateEngine.calls.get());
    }

    /** R6.1: no ledger record means honest uncertainty — one status, still disabled, no replay. */
    @Test
    public void unconfirmedStatusIsShownOnceAndNeverClearsOrReplays()
            throws OperationAttemptStore.Failure {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 75), true);
        CountingAttemptStore attemptStore = new CountingAttemptStore();
        installAttemptCoordinator(attemptStore, () -> 47L, Runnable::run);
        MainActivity activity = launch();
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        MainActivity.installServiceStarterForTesting(activity, new RecordingStarter());

        activity.findViewById(R.id.btnRun).performClick();
        String requestId = (String) ReflectionHelpers.getField(activity, "mPendingRequestId");
        assertNotNull(requestId);
        OperationAttempt beforeAttempt = attemptStore.load();
        RunCoordinator.Snapshot beforeSnapshot = coordinator.getSnapshot();

        outcomeWatch.runAll();

        String unconfirmed = "Operation request outcome unconfirmed: no acknowledgement "
                + "was received; no automatic retry or recovery is available in this app.";
        assertEquals("the explicit status is shown once", 1,
                occurrences(output(activity), unconfirmed));
        assertFalse(output(activity).contains("Operation request rejected"));
        assertFalse(output(activity).contains("Operation request cancelled"));
        assertFalse(output(activity).contains("no reason supplied"));
        assertTrue("the unconfirmed click stays pending",
                ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertEquals(requestId, ReflectionHelpers.getField(activity, "mPendingRequestId"));
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals(0, attemptStore.clears);
        assertEquals(beforeAttempt, attemptStore.load());
        assertSame(beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("no replayed service request",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
        assertFalse(outcomeWatch.hasPending());

        // A forced extra tick stays harmless and silent for the same token.
        ReflectionHelpers.callInstanceMethod(activity, "runOutcomeWatchTick");
        assertEquals(1, occurrences(output(activity), unconfirmed));
        assertTrue(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals(0, attemptStore.clears);
        assertEquals(beforeAttempt, attemptStore.load());
        assertSame(beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());
    }

    /**
     * R6.2 (evicted-record half of R2-17): after this Activity's own ledger
     * record is evicted during an absence, the watch and onStart find null and
     * keep the request explicitly unconfirmed. Absence is uncertainty, never an
     * outcome and never evidence of admission, rejection or completion.
     */
    @Test
    public void evictedOwnOutcomeStaysUnconfirmedOnWatchAndOnStart()
            throws OperationAttemptStore.Failure {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 76), true);
        CountingAttemptStore attemptStore = new CountingAttemptStore();
        installAttemptCoordinator(attemptStore, () -> 48L, Runnable::run);
        ActivityController<MainActivity> controller = launchController();
        MainActivity activity = controller.get();
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        activity.findViewById(R.id.btnRun).performClick();
        String requestId = (String) ReflectionHelpers.getField(activity, "mPendingRequestId");
        assertNotNull(requestId);
        assertEquals(1, starter.requests.size());
        assertEquals(requestId, starter.requestIds.get(0));
        OperationAttempt beforeAttempt = attemptStore.load();

        // Go absent so publishing cannot clear pending via the snapshot listener.
        controller.stop();
        // First write the Activity's own harmless observation, then publish enough
        // OTHER harmless observations to evict it from the bounded 32-entry LRU.
        coordinator.noteRequestOutcome(requestId, RunCoordinator.RequestOutcome.ACCEPTED,
                "fake acceptance before eviction");
        assertNotNull(coordinator.getRequestOutcome(requestId));
        int fillerCount = RunCoordinator.MAX_OUTCOME_RECORDS + 2;
        for (int i = 0; i < fillerCount; i++) {
            coordinator.noteRequestOutcome("eviction-filler-" + i,
                    RunCoordinator.RequestOutcome.REJECTED, "harmless filler-" + i);
        }
        assertNull("the Activity's own record was evicted from the bounded ledger",
                coordinator.getRequestOutcome(requestId));
        RunCoordinator.Snapshot beforeSnapshot = coordinator.getSnapshot();

        controller.start(); // onStart finds null and deliberately keeps pending

        assertTrue("absence after eviction stays pending",
                ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertEquals(requestId, ReflectionHelpers.getField(activity, "mPendingRequestId"));
        assertFalse("the run control stays disabled while unconfirmed",
                activity.findViewById(R.id.btnRun).isEnabled());
        assertSame("onStart reconciliation is read-only", beforeSnapshot,
                coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("no replayed service request on start",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());

        outcomeWatch.runAll(); // bounded read-only ticks; no lifecycle restart here

        String unconfirmed = "Operation request outcome unconfirmed: no acknowledgement "
                + "was received; no automatic retry or recovery is available in this app.";
        assertEquals("one explicit unconfirmed status", 1,
                occurrences(output(activity), unconfirmed));
        assertFalse("no fabricated rejection", output(activity).contains("Operation request rejected"));
        assertFalse("no fabricated cancellation", output(activity).contains("Operation request cancelled"));
        assertFalse("no fabricated reason", output(activity).contains("no reason supplied"));
        assertTrue("the unconfirmed click stays pending",
                ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals(0, attemptStore.clears);
        assertEquals(beforeAttempt, attemptStore.load());
        assertSame("watch reconciliation is read-only", beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("no replayed service request",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
        assertFalse(outcomeWatch.hasPending());
        assertEquals("no replayed click through the retained starter", 1, starter.requests.size());
    }

    /**
     * R6.2: a later start still re-reads the ledger read-only after an
     * unconfirmed status. No replay, no duplicate status for the same token and
     * no durable mutation; when a harmless record is later published for that
     * token the re-read is proven by a real, correlated reconciliation.
     */
    @Test
    public void laterStartRereadsLedgerWithoutReplayOrDuplicateUnconfirmedStatus()
            throws OperationAttemptStore.Failure {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 77), true);
        CountingAttemptStore attemptStore = new CountingAttemptStore();
        installAttemptCoordinator(attemptStore, () -> 49L, Runnable::run);
        ActivityController<MainActivity> controller = launchController();
        MainActivity activity = controller.get();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        activity.findViewById(R.id.btnRun).performClick();
        String requestId = (String) ReflectionHelpers.getField(activity, "mPendingRequestId");
        assertNotNull(requestId);
        assertEquals(1, starter.requests.size());
        assertEquals(requestId, starter.requestIds.get(0));
        OperationAttempt beforeAttempt = attemptStore.load();

        outcomeWatch.runAll(); // no record at all -> explicit unconfirmed once

        String unconfirmed = "Operation request outcome unconfirmed: no acknowledgement "
                + "was received; no automatic retry or recovery is available in this app.";
        assertEquals(1, occurrences(output(activity), unconfirmed));
        RunCoordinator.Snapshot beforeSnapshot = coordinator.getSnapshot();

        controller.stop();
        controller.start(); // later start: re-read ledger, still absent
        assertTrue("absence stays pending", ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertSame("re-read is read-only", beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, attemptStore.clears);
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());
        assertEquals("no duplicate unconfirmed status on the later start", 1,
                occurrences(output(activity), unconfirmed));

        // A freshly armed bounded window still cannot duplicate the status.
        outcomeWatch.runAll();
        assertEquals(1, occurrences(output(activity), unconfirmed));
        assertTrue(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertSame(beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());

        // Prove the later start really re-reads the ledger: publish a harmless
        // correlated outcome while stopped, then start once more.
        controller.stop();
        coordinator.noteRequestOutcome(requestId, RunCoordinator.RequestOutcome.REJECTED,
                "harmless late ledger record");
        RunCoordinator.Snapshot published = coordinator.getSnapshot();

        controller.start();

        assertFalse("the re-read clears pending from the ledger record",
                ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertSame("reconciliation stays read-only", published, coordinator.getSnapshot());
        assertEquals(1, occurrences(output(activity), "harmless late ledger record"));
        assertEquals("the unconfirmed status is still shown only once", 1,
                occurrences(output(activity), unconfirmed));
        assertEquals(0, attemptStore.clears);
        assertEquals(beforeAttempt, attemptStore.load());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("no replayed service request",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
        assertEquals("no replayed click through the retained starter", 1, starter.requests.size());
    }

    /**
     * R7.2 (final-tick half of R2-17): a correlated outcome that lands between
     * the old two reads is now seen by the SINGLE observation that also decides
     * the final tick. The watch reconciles that honest, evidence-backed record
     * and emits no unconfirmed status. Nothing is replayed, authorized or
     * durably cleared.
     */
    @Test
    public void finalTickSingleObservationReconcilesLateOutcome()
            throws OperationAttemptStore.Failure {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 78), true);
        CountingAttemptStore attemptStore = new CountingAttemptStore();
        installAttemptCoordinator(attemptStore, () -> 50L, Runnable::run);
        ActivityController<MainActivity> controller = launchController();
        MainActivity activity = controller.get();
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        activity.findViewById(R.id.btnRun).performClick();
        String requestId = (String) ReflectionHelpers.getField(activity, "mPendingRequestId");
        assertNotNull(requestId);
        OperationAttempt beforeAttempt = attemptStore.load();
        RunCoordinator.Snapshot beforeSnapshot = coordinator.getSnapshot();

        // 24 absent observations, then the late REJECTED outcome on the 25th.
        ScriptedOutcomeLookup lookup = new ScriptedOutcomeLookup();
        for (int i = 0; i < 24; i++) {
            lookup.script.add(null);
        }
        lookup.script.add(new RunCoordinator.RequestOutcomeRecord(requestId,
                RunCoordinator.RequestOutcome.REJECTED, "late correlated rejection", 99L));
        MainActivity.installRequestOutcomeLookupForTesting(lookup);

        outcomeWatch.runAll();

        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("late correlated rejection"));
        assertFalse("the single late observation is reconciled, not called unconfirmed",
                output(activity).contains("outcome unconfirmed"));
        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertNull(ReflectionHelpers.getField(activity, "mPendingRequestId"));
        assertTrue("the cleared, rejected request no longer blocks the UI control",
                activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals("one observation per tick", 25, lookup.calls);
        assertEquals(25, outcomeWatch.ticks);
        for (String observedId : lookup.requestIds) {
            assertEquals(requestId, observedId);
        }
        assertNull("the scripted observation is read-only: no ledger record was written",
                coordinator.getRequestOutcome(requestId));
        assertEquals(0, attemptStore.clears);
        assertEquals(beforeAttempt, attemptStore.load());
        assertSame("read-only reconciliation leaves the snapshot identity unchanged",
                beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("no replayed service request",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
        assertEquals("no replayed click through the retained starter", 1, starter.requests.size());
        assertFalse("the correlated outcome cancelled the watch", outcomeWatch.hasPending());
    }

    /**
     * R7.2 (null half of R2-17): if the SINGLE final-tick observation still sees
     * no record, the request stays pending and the one-per-token unconfirmed
     * status is shown. A forced extra tick stays silent and inert.
     */
    @Test
    public void finalTickSingleObservationNullReportsUnconfirmedOnce()
            throws OperationAttemptStore.Failure {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 79), true);
        CountingAttemptStore attemptStore = new CountingAttemptStore();
        installAttemptCoordinator(attemptStore, () -> 51L, Runnable::run);
        MainActivity activity = launch();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        activity.findViewById(R.id.btnRun).performClick();
        String requestId = (String) ReflectionHelpers.getField(activity, "mPendingRequestId");
        assertNotNull(requestId);
        OperationAttempt beforeAttempt = attemptStore.load();
        RunCoordinator.Snapshot beforeSnapshot = coordinator.getSnapshot();

        // 25 window observations plus one for the forced extra tick below.
        ScriptedOutcomeLookup lookup = new ScriptedOutcomeLookup();
        for (int i = 0; i < 26; i++) {
            lookup.script.add(null);
        }
        MainActivity.installRequestOutcomeLookupForTesting(lookup);
        outcomeWatch.runAll();

        String unconfirmed = "Operation request outcome unconfirmed: no acknowledgement "
                + "was received; no automatic retry or recovery is available in this app.";
        assertEquals("one honest unconfirmed status for the whole window", 1,
                occurrences(output(activity), unconfirmed));
        assertFalse(output(activity).contains("Operation request rejected"));
        assertFalse(output(activity).contains("Operation request cancelled"));
        assertFalse(output(activity).contains("no reason supplied"));
        assertTrue("absence keeps the click pending",
                ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertEquals(requestId, ReflectionHelpers.getField(activity, "mPendingRequestId"));
        assertFalse("the unconfirmed run control stays disabled",
                activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals("one observation per tick", 25, lookup.calls);
        assertEquals(25, outcomeWatch.ticks);
        assertFalse(outcomeWatch.hasPending());
        assertEquals(0, attemptStore.clears);
        assertEquals(beforeAttempt, attemptStore.load());
        assertSame(beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());

        // A forced extra tick re-observes once but cannot duplicate the status.
        ReflectionHelpers.callInstanceMethod(activity, "runOutcomeWatchTick");
        assertEquals(26, lookup.calls);
        assertEquals(1, occurrences(output(activity), unconfirmed));
        assertTrue(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals(0, attemptStore.clears);
        assertEquals(beforeAttempt, attemptStore.load());
        assertSame(beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());
        assertEquals("no replayed click through the retained starter", 1, starter.requests.size());
    }

    /** R7.2: lookups equal ticks across the bounded window, at the unchanged interval. */
    @Test
    public void oneLedgerObservationPerTick() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 80), true);
        installIdleCoordinator();
        MainActivity activity = launch();
        MainActivity.installServiceStarterForTesting(activity, new RecordingStarter());

        ScriptedOutcomeLookup lookup = new ScriptedOutcomeLookup();
        int cap = (Integer) ReflectionHelpers.getStaticField(
                MainActivity.class, "OUTCOME_WATCH_MAX_TICKS");
        long intervalMs = (Long) ReflectionHelpers.getStaticField(
                MainActivity.class, "OUTCOME_WATCH_INTERVAL_MS");
        assertEquals(25, cap);
        assertEquals(200L, intervalMs);
        for (int i = 0; i < cap; i++) {
            lookup.script.add(null);
        }
        MainActivity.installRequestOutcomeLookupForTesting(lookup);

        activity.findViewById(R.id.btnRun).performClick();
        assertEquals("a click only arms the watch; it does not read the ledger", 0, lookup.calls);

        outcomeWatch.runAll();

        assertEquals("exactly one lookup per tick", cap, lookup.calls);
        assertEquals(cap, outcomeWatch.ticks);
        assertEquals(cap, outcomeWatch.schedules);
        assertEquals("the window stays bounded at the same interval", cap, outcomeWatch.delays.size());
        for (Long delay : outcomeWatch.delays) {
            assertEquals(Long.valueOf(intervalMs), delay);
        }
        assertFalse(outcomeWatch.hasPending());
        assertEquals(0, immediateEngine.calls.get());
    }

    /**
     * R7.2 repair (R2-17): the final tick must reuse the SAME single observation
     * that reconciliation used. The scripted lookup deliberately disagrees on its
     * first and second result: first null, then a fabricated REJECTED record.
     * The fixed code observes only null on tick 25, so it emits the honest
     * per-token unconfirmed status and keeps the request pending. The old
     * two-observation algorithm sees null in reconcilePendingRequest() and then a
     * non-null record in the expiry branch's independent read, so NEITHER status
     * is emitted and the UI stays blank; this test fails there.
     */
    @Test
    public void finalTickDisagreeingObservationsNeverLeaveBlankUi()
            throws OperationAttemptStore.Failure {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 81), true);
        CountingAttemptStore attemptStore = new CountingAttemptStore();
        installAttemptCoordinator(attemptStore, () -> 52L, Runnable::run);
        MainActivity activity = launch();
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        activity.findViewById(R.id.btnRun).performClick();
        String requestId = (String) ReflectionHelpers.getField(activity, "mPendingRequestId");
        assertNotNull(requestId);
        OperationAttempt beforeAttempt = attemptStore.load();
        RunCoordinator.Snapshot beforeSnapshot = coordinator.getSnapshot();

        // 24 absent observations, then two DISAGREEING observations available at
        // the final tick. Only a one-observation implementation can consume 25 of
        // these 26 entries and still report the honest null-based status.
        ScriptedOutcomeLookup lookup = new ScriptedOutcomeLookup();
        for (int i = 0; i < 24; i++) {
            lookup.script.add(null);
        }
        lookup.script.add(null); // final tick's single, honest observation
        String fabricatedReason = "disagreeing second observation must not be reported";
        lookup.script.add(new RunCoordinator.RequestOutcomeRecord(requestId,
                RunCoordinator.RequestOutcome.REJECTED, fabricatedReason, 99L));
        MainActivity.installRequestOutcomeLookupForTesting(lookup);

        outcomeWatch.runAll();

        String unconfirmed = "Operation request outcome unconfirmed: no acknowledgement "
                + "was received; no automatic retry or recovery is available in this app.";
        assertEquals("the single null final observation still yields an honest status", 1,
                occurrences(output(activity), unconfirmed));
        assertFalse("the disagreeing second read must not fabricate a rejection",
                output(activity).contains("Operation request rejected"));
        assertFalse("the unobserved record's reason must never reach the UI",
                output(activity).contains(fabricatedReason));
        assertFalse(output(activity).contains("Operation request cancelled"));
        assertFalse(output(activity).contains("no reason supplied"));
        assertTrue("an unconfirmed request stays pending",
                ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertEquals(requestId, ReflectionHelpers.getField(activity, "mPendingRequestId"));
        assertFalse("the run control stays disabled while unconfirmed",
                activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals("one observation per tick: the disagreeing second entry is unread",
                25, lookup.calls);
        assertEquals("the second, disagreeing observation remains unobserved", 1,
                lookup.script.size());
        assertEquals(25, outcomeWatch.ticks);
        for (String observedId : lookup.requestIds) {
            assertEquals(requestId, observedId);
        }
        assertFalse(outcomeWatch.hasPending());
        assertNull("the scripted record was never written to the ledger",
                coordinator.getRequestOutcome(requestId));
        assertEquals(0, attemptStore.clears);
        assertEquals(beforeAttempt, attemptStore.load());
        assertSame("read-only reconciliation leaves the snapshot identity unchanged",
                beforeSnapshot, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("no replayed service request",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
        assertEquals("no replayed click through the retained starter", 1, starter.requests.size());
    }

    /** Destruction before admission cancels only service-local work, never an engine. */
    @Test
    public void destroyedBeforeAdmissionPublishesCancelledAndClearsPendingWithoutEngine() {
        MainActivity activity = launchReady();

        activity.findViewById(R.id.btnRun).performClick();
        Intent request = nextServiceRequest();
        String requestId = request.getStringExtra(ExploitService.EXTRA_REQUEST_ID);
        assertNotNull(requestId);

        ServiceController<ExploitService> controller = Robolectric.buildService(
                ExploitService.class).create().withIntent(request);
        QueueExecutor admission = new QueueExecutor();
        controller.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        controller.get().setAdmissionExecutorForTesting(admission);
        coordinator.setOutcomeExecutorForTesting(admission);
        controller.startCommand(0, 204);
        assertTrue(admission.hasNext());
        assertTrue(ReflectionHelpers.getField(activity, "mManualStartPending"));

        controller.destroy();
        // The cancellation is now enqueued off the destroying thread; drive the
        // deterministic seam before asserting the correlated outcome.
        admission.runAll();
        coordinatorObservers.runAll();

        RunCoordinator.Snapshot cancelled = coordinator.getSnapshot();
        assertEquals(RunCoordinator.RequestOutcome.CANCELLED, cancelled.getRequestOutcome());
        assertEquals(requestId, cancelled.getRequestId());
        assertTrue(cancelled.getRequestFailure().contains("cancel"));
        assertEquals(RunCoordinator.State.IDLE, cancelled.getState());
        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Operation request cancelled"));
        assertEquals(0, immediateEngine.calls.get());
        assertFalse(coordinatorWorker.hasNext());

        // A task that is later dequeued must be a strict no-op.
        assertFalse(admission.hasNext());
        admission.runAll();
        assertSame(cancelled, coordinator.getSnapshot());
        assertEquals(0, immediateEngine.calls.get());
        assertFalse(coordinatorWorker.hasNext());
    }

    /** R5.1: the stopped Activity reconciles A's own outcome even when B takes the single slot. */
    @Test
    public void stoppedActivityReconcilesItsOwnRejectionAfterUnrelatedSlotOverwrite() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 60), true);
        installIdleCoordinator();
        ActivityController<MainActivity> controller = launchController();
        String requestA = clickAndReadRequestId(controller);
        assertTrue(ReflectionHelpers.getField(controller.get(), "mManualStartPending"));

        controller.stop();
        coordinator.noteRequestOutcome(requestA, RunCoordinator.RequestOutcome.REJECTED,
                "rejection for A while stopped");
        coordinator.noteRequestOutcome("unrelated-B", RunCoordinator.RequestOutcome.ACCEPTED, null);
        coordinatorObservers.runAll();
        RunCoordinator.Snapshot latest = coordinator.getSnapshot();
        assertEquals("unrelated-B", latest.getRequestId());
        assertEquals(RunCoordinator.RequestOutcome.ACCEPTED, latest.getRequestOutcome());
        assertTrue("the stopped observer must not have cleared pending",
                ReflectionHelpers.getField(controller.get(), "mManualStartPending"));

        controller.start();

        assertFalse(ReflectionHelpers.getField(controller.get(), "mManualStartPending"));
        assertNull(ReflectionHelpers.getField(controller.get(), "mPendingRequestId"));
        assertTrue(output(controller.get()).contains("Operation request rejected"));
        assertTrue(output(controller.get()).contains("rejection for A while stopped"));
        assertSame("B's operation state is untouched by A's reconciliation",
                latest, coordinator.getSnapshot());
        assertEquals(RunCoordinator.State.IDLE, latest.getState());
        assertTrue(controller.get().findViewById(R.id.btnRun).isEnabled());
        assertEquals(0, immediateEngine.calls.get());

        // A later listener callback for the same ID must not report twice.
        coordinator.noteRequestOutcome(requestA, RunCoordinator.RequestOutcome.REJECTED,
                "rejection for A while stopped");
        coordinatorObservers.runAll();
        assertEquals(1, occurrences(output(controller.get()), "rejection for A while stopped"));
    }

    @Test
    public void stoppedActivityReconcilesItsOwnCancellationAfterUnrelatedSlotOverwrite() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 61), true);
        installIdleCoordinator();
        ActivityController<MainActivity> controller = launchController();
        String requestA = clickAndReadRequestId(controller);

        controller.stop();
        coordinator.noteRequestOutcome(requestA, RunCoordinator.RequestOutcome.CANCELLED,
                "cancellation for A while stopped");
        coordinator.noteRequestOutcome("unrelated-B", RunCoordinator.RequestOutcome.ACCEPTED, null);
        coordinatorObservers.runAll();
        RunCoordinator.Snapshot latest = coordinator.getSnapshot();
        assertEquals("unrelated-B", latest.getRequestId());

        controller.start();

        assertFalse(ReflectionHelpers.getField(controller.get(), "mManualStartPending"));
        assertTrue(output(controller.get()).contains("Operation request cancelled"));
        assertTrue(output(controller.get()).contains("cancellation for A while stopped"));
        assertSame(latest, coordinator.getSnapshot());
        assertEquals(RunCoordinator.State.IDLE, latest.getState());
        assertTrue(controller.get().findViewById(R.id.btnRun).isEnabled());
        assertEquals(0, immediateEngine.calls.get());
    }

    @Test
    public void stoppedActivityReconcilesItsOwnAcceptanceWithoutReportingAReasonOrStartingWork() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 62), true);
        installIdleCoordinator();
        ActivityController<MainActivity> controller = launchController();
        String requestA = clickAndReadRequestId(controller);

        controller.stop();
        coordinator.noteRequestOutcome(requestA, RunCoordinator.RequestOutcome.ACCEPTED, null);
        coordinator.noteRequestOutcome("unrelated-B", RunCoordinator.RequestOutcome.ACCEPTED, null);
        coordinatorObservers.runAll();
        RunCoordinator.Snapshot latest = coordinator.getSnapshot();
        assertEquals("unrelated-B", latest.getRequestId());

        controller.start();

        assertFalse(ReflectionHelpers.getField(controller.get(), "mManualStartPending"));
        assertFalse(output(controller.get()).contains("Operation request rejected"));
        assertFalse(output(controller.get()).contains("Operation request cancelled"));
        assertSame(latest, coordinator.getSnapshot());
        assertEquals(RunCoordinator.State.IDLE, latest.getState());
        assertTrue(controller.get().findViewById(R.id.btnRun).isEnabled());
        assertEquals("reconciliation never replays a click", 0, immediateEngine.calls.get());
    }

    /** Absence of a record is uncertainty: pending stays and no request is replayed. */
    @Test
    public void stoppedActivityWithNoRecordKeepsPendingAndNeverReplaysItsClick() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 63), true);
        installIdleCoordinator();
        ActivityController<MainActivity> controller = launchController();
        String requestA = clickAndReadRequestId(controller);

        controller.stop();
        coordinator.noteRequestOutcome("unrelated-B", RunCoordinator.RequestOutcome.ACCEPTED, null);
        coordinatorObservers.runAll();
        assertNull(coordinator.getRequestOutcome(requestA));

        controller.start();

        assertTrue("an unconfirmed request stays pending",
                ReflectionHelpers.getField(controller.get(), "mManualStartPending"));
        assertEquals(requestA, ReflectionHelpers.getField(controller.get(), "mPendingRequestId"));
        assertFalse(controller.get().findViewById(R.id.btnRun).isEnabled());
        assertEquals(0, immediateEngine.calls.get());
        assertNull("no replayed service request",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
    }

    @Test
    public void synchronousServiceStartFailureIsShownAndRestoresEligibility() {
        MainActivity activity = launchReady();
        FailingStarter starter = new FailingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        activity.findViewById(R.id.btnRun).performClick();
        coordinatorObservers.runAll();

        assertEquals(1, starter.calls);
        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertEquals(RunCoordinator.RequestOutcome.REJECTED,
                coordinator.getSnapshot().getRequestOutcome());
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Unable to start operation service"));
    }

    /**
     * R2-18 / R5.2: the synchronous start-failure handler must write the bounded
     * ledger under outcomeLock only and post observation work off the caller. A
     * separate boot admission holds the checked-storage coordinator monitor for
     * the whole click; no privileged payload or device state is involved.
     */
    @Test
    public void synchronousStartFailureNeverTakesCheckedStorageMonitorOnUiCaller() throws Exception {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        BlockingPublishStore store = new BlockingPublishStore();
        QueueExecutor outcomeQueue = new QueueExecutor();
        installBlockingAttemptCoordinator(store, outcomeQueue);
        // Observer callbacks arrive only via the posted publication, never from
        // the click's synchronous ledger write.
        List<String> observedThreads = Collections.synchronizedList(new ArrayList<>());
        coordinator.addListener(snapshot -> observedThreads.add(
                Thread.currentThread().getName() + "|" + snapshot.getRequestId()));
        MainActivity activity = launch();
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        CapturingFailingStarter starter =
                new CapturingFailingStarter("service start denied while storage blocked");
        MainActivity.installServiceStarterForTesting(activity, starter);

        Thread bootSubmitter = new Thread(() -> coordinator.submit(
                RuntimeEnvironment.getApplication(), RunConfig.ExecutionMode.UNATTENDED_BOOT,
                new FakeLease(), "boot-holder"));
        bootSubmitter.setDaemon(true);
        bootSubmitter.start();
        assertTrue("boot publish must hold the coordinator monitor",
                store.publishEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        CountDownLatch clickReturned = new CountDownLatch(1);
        AtomicReference<Throwable> clickFailure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                // The click needs a Looper for Robolectric's view animation;
                // it is still a separate caller thread from the test/main one.
                Looper.prepare();
                activity.findViewById(R.id.btnRun).performClick();
            } catch (Throwable t) {
                clickFailure.set(t);
            } finally {
                if (Looper.myLooper() != null) {
                    Looper.myLooper().quit();
                }
                clickReturned.countDown();
            }
        }, "r5-2-click-caller");
        caller.setDaemon(true);
        caller.start();
        // Any direct noteRequestOutcome / submit / finish call on this caller
        // would take the monitor held by bootSubmitter and miss this latch.
        assertTrue("click handler blocked behind checked storage",
                clickReturned.await(2L, TimeUnit.SECONDS));
        assertNull(clickFailure.get());
        caller.join(TEST_TIMEOUT_MS);
        assertFalse("the click caller finished while storage was blocked", caller.isAlive());
        assertEquals(1L, store.releasePublish.getCount()); // storage still blocked
        assertEquals(1, starter.calls);
        assertNotNull(starter.requestId);
        assertNull("the failing starter started no service and admitted no operation",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());

        RunCoordinator.RequestOutcomeRecord recorded =
                coordinator.getRequestOutcome(starter.requestId);
        assertNotNull("the correlated record existed before the click returned", recorded);
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, recorded.getOutcome());
        assertEquals("Unable to start operation service: "
                + "service start denied while storage blocked", recorded.getReason());

        store.releasePublish.countDown();
        bootSubmitter.join(TEST_TIMEOUT_MS);
        assertFalse("boot admission finished after the checked publish released",
                bootSubmitter.isAlive());
        OperationAttempt published = store.load();
        assertNotNull(published);
        assertEquals(RunConfig.ExecutionMode.UNATTENDED_BOOT, published.getMode());
        assertTrue(published.isUnconfirmed());
        assertEquals("the checked boot result is still authoritative",
                RunCoordinator.RequestOutcome.ACCEPTED,
                coordinator.getRequestOutcome("boot-holder").getOutcome());

        // Let the harmless boot engine finish: the checked barrier is still
        // cleared only on this real result, never by the failed UI click.
        coordinatorWorker.runAll();
        assertNull("the checked clear still follows the completed boot operation",
                store.load());

        outcomeQueue.runAll(); // noteRequestOutcome runs here, off the click caller
        coordinatorObservers.runAll(); // ordinary observer delivery to the Activity
        assertFalse("no correlated outcome was observed on the click caller",
                observedThreads.contains("r5-2-click-caller|" + starter.requestId));
        assertTrue("the posted publication was observed on the draining caller: "
                        + observedThreads,
                observedThreads.contains(Thread.currentThread().getName() + "|"
                        + starter.requestId));
        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("service start denied while storage blocked"));
        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
    }

    /** Two synchronous failures keep two distinct ledger records and both honest reasons. */
    @Test
    public void twoRapidSynchronousStartFailuresKeepDistinctCorrelatedLedgerRecords() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        MainActivity first = launch();
        MainActivity second = launch();
        assertTrue(first.findViewById(R.id.btnRun).isEnabled());
        assertTrue(second.findViewById(R.id.btnRun).isEnabled());
        CapturingFailingStarter firstStarter =
                new CapturingFailingStarter("first service start failure");
        CapturingFailingStarter secondStarter =
                new CapturingFailingStarter("second service start failure");
        MainActivity.installServiceStarterForTesting(first, firstStarter);
        MainActivity.installServiceStarterForTesting(second, secondStarter);

        first.findViewById(R.id.btnRun).performClick();
        second.findViewById(R.id.btnRun).performClick();

        assertEquals(1, firstStarter.calls);
        assertEquals(1, secondStarter.calls);
        assertNotNull(firstStarter.requestId);
        assertNotNull(secondStarter.requestId);
        assertFalse("each click got its own request ID",
                firstStarter.requestId.equals(secondStarter.requestId));
        RunCoordinator.RequestOutcomeRecord firstRecord =
                coordinator.getRequestOutcome(firstStarter.requestId);
        RunCoordinator.RequestOutcomeRecord secondRecord =
                coordinator.getRequestOutcome(secondStarter.requestId);
        assertNotNull(firstRecord);
        assertNotNull(secondRecord);
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, firstRecord.getOutcome());
        assertEquals(RunCoordinator.RequestOutcome.REJECTED, secondRecord.getOutcome());
        assertEquals("Unable to start operation service: first service start failure",
                firstRecord.getReason());
        assertEquals("Unable to start operation service: second service start failure",
                secondRecord.getReason());
        assertTrue("distinct records have distinct sequence numbers",
                firstRecord.getSequence() != secondRecord.getSequence());

        coordinatorObservers.runAll();
        assertTrue(output(first).contains("first service start failure"));
        assertTrue(output(second).contains("second service start failure"));
        assertFalse(output(first).contains("second service start failure"));
        assertFalse(output(second).contains("first service start failure"));
        assertFalse(ReflectionHelpers.getField(first, "mManualStartPending"));
        assertFalse(ReflectionHelpers.getField(second, "mManualStartPending"));
        assertTrue(first.findViewById(R.id.btnRun).isEnabled());
        assertTrue(second.findViewById(R.id.btnRun).isEnabled());
    }

    @Test
    public void queuedServiceStartRejectsAnInterveningSettingsGenerationBeforeExecution() {
        MainActivity activity = launchReady();
        long clickedGeneration = repository.getConfirmedSnapshot().getGeneration();

        activity.findViewById(R.id.btnRun).performClick();
        Intent request = nextServiceRequest();
        assertEquals(clickedGeneration,
                request.getLongExtra(ExploitService.EXTRA_EXPECTED_GENERATION, -1L));
        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(clickedGeneration + 1, repository.getConfirmedSnapshot().getGeneration());
        assertEquals(1, store.writes.size());

        ServiceController<ExploitService> serviceController = Robolectric.buildService(
                ExploitService.class).create().withIntent(request);
        serviceControllers.add(serviceController);
        // Admission is normally off-main; run it inline for these deterministic tests.
        serviceController.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        serviceController.get().setAdmissionExecutorForTesting(Runnable::run);
        serviceController.startCommand(0, 75);
        assertEquals(RunCoordinator.State.STARTING, coordinator.getSnapshot().getState());
        coordinatorWorker.runNext();
        coordinatorObservers.runAll();

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.TERMINAL, terminal.getState());
        assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
        assertTrue(terminal.getReason().contains("configuration generation changed"));
        assertTrue(terminal.getReason().contains("expected " + clickedGeneration));
        assertEquals(0, immediateEngine.calls.get());
        assertFalse(repository.isFrozen());
        assertTrue(repository.getConfirmedSnapshot().isDisableModules());
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(Shadows.shadowOf(serviceController.get()).isStoppedBySelf());
    }

    @Test
    public void productionForegroundStartFailureRestoresRunEligibilityWithoutRecreation() {
        MainActivity activity = launchReady();
        RunCoordinator.Snapshot beforeClick = coordinator.getSnapshot();

        activity.findViewById(R.id.btnRun).performClick();

        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        Intent request = nextServiceRequest();
        ServiceController<ExploitService> serviceController = Robolectric.buildService(
                ExploitService.class).create();
        serviceControllers.add(serviceController);
        // Admission and failure reports are normally off-main; drive the queued
        // work explicitly instead of relying on inline delivery.
        QueueExecutor admission = new QueueExecutor();
        serviceController.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        serviceController.get().setAdmissionExecutorForTesting(admission);
        coordinator.setOutcomeExecutorForTesting(admission);
        Shadows.shadowOf(serviceController.get()).setThrowInStartForeground(
                new IllegalStateException("FGS denied by platform"));
        serviceController.withIntent(request).startCommand(0, 73);
        assertEquals(1, admission.commands.size());
        assertEquals(RunCoordinator.State.IDLE, coordinator.getSnapshot().getState());
        admission.runAll();

        RunCoordinator.Snapshot failure = coordinator.getSnapshot();
        assertNotSame(beforeClick, failure);
        assertTrue(failure.getRequestFailure().contains("foreground service start failed"));
        assertEquals(RunCoordinator.State.IDLE, failure.getState());
        assertFalse(coordinatorWorker.hasNext());
        coordinatorObservers.runAll();

        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("FGS denied by platform"));
        assertTrue(Shadows.shadowOf(serviceController.get()).isStoppedBySelf());
    }

    @Test
    public void productionWakeLockFailureRestoresRunEligibilityWithoutRecreation() {
        MainActivity activity = launchReady();
        RunCoordinator.Snapshot beforeClick = coordinator.getSnapshot();

        activity.findViewById(R.id.btnRun).performClick();

        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        Intent request = nextServiceRequest();
        ServiceController<ExploitService> serviceController = Robolectric.buildService(
                ExploitService.class).create().withIntent(request);
        serviceControllers.add(serviceController);
        // Admission and failure reports are normally off-main; drive the queued
        // work explicitly instead of relying on inline delivery.
        QueueExecutor admission = new QueueExecutor();
        serviceController.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        serviceController.get().setAdmissionExecutorForTesting(admission);
        coordinator.setOutcomeExecutorForTesting(admission);
        serviceController.get().setWakeLockSourceForTesting(ignored -> {
            throw new IllegalStateException("PowerManager unavailable");
        });
        serviceController.startCommand(0, 74);
        assertEquals(1, admission.commands.size());
        assertEquals(RunCoordinator.State.IDLE, coordinator.getSnapshot().getState());
        admission.runAll();

        RunCoordinator.Snapshot failure = coordinator.getSnapshot();
        assertNotSame(beforeClick, failure);
        assertTrue(failure.getRequestFailure().contains("operation service setup failed"));
        assertTrue(failure.getRequestFailure().contains("PowerManager unavailable"));
        assertEquals(RunCoordinator.State.IDLE, failure.getState());
        assertFalse(coordinatorWorker.hasNext());
        coordinatorObservers.runAll();

        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("PowerManager unavailable"));
        assertTrue(Shadows.shadowOf(serviceController.get()).isStoppedBySelf());
    }

    @Test
    public void persistedUnconfirmedAttemptRejectionClearsPendingAndShowsReasonAtServiceBoundary()
            throws OperationAttemptStore.Failure {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        OperationAttemptStore attemptStore = OperationAttemptStore.inMemory();
        assertTrue(attemptStore.publish(new OperationAttempt("prior-op",
                RunConfig.ExecutionMode.UNATTENDED_BOOT, 44L, true, "prior admission")));
        installAttemptCoordinator(attemptStore, () -> 44L, Runnable::run);
        MainActivity activity = launch();
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());

        activity.findViewById(R.id.btnRun).performClick();
        ServiceController<ExploitService> serviceController = deliverServiceRequest(91);
        coordinatorObservers.runAll();

        RunCoordinator.Snapshot rejection = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.IDLE, rejection.getState());
        assertTrue(rejection.getRequestFailure().contains("prior-op"));
        assertTrue(rejection.getRequestFailure().contains("blocks admission"));
        assertFalse(coordinatorWorker.hasNext());
        assertEquals(0, immediateEngine.calls.get());
        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("prior-op"));
        assertTrue(Shadows.shadowOf(serviceController.get()).isStoppedBySelf());
    }

    @Test
    public void attemptStorePublishFailureRejectionClearsPendingAndShowsReasonAtServiceBoundary() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        OperationAttemptStore failingStore = new OperationAttemptStore() {
            @Override
            public OperationAttempt load() {
                return null;
            }

            @Override
            public boolean publish(OperationAttempt attempt) throws Failure {
                throw new Failure("attempt disk full");
            }

            @Override
            public boolean clear(String operationId) {
                return false;
            }
        };
        installAttemptCoordinator(failingStore, () -> 45L, Runnable::run);
        MainActivity activity = launch();

        activity.findViewById(R.id.btnRun).performClick();
        ServiceController<ExploitService> serviceController = deliverServiceRequest(92);
        coordinatorObservers.runAll();

        RunCoordinator.Snapshot rejection = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.IDLE, rejection.getState());
        assertTrue(rejection.getRequestFailure().contains("attempt store publish failed"));
        assertTrue(rejection.getRequestFailure().contains("attempt disk full"));
        assertFalse(coordinatorWorker.hasNext());
        assertEquals(0, immediateEngine.calls.get());
        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("attempt disk full"));
        assertTrue(Shadows.shadowOf(serviceController.get()).isStoppedBySelf());
    }

    @Test
    public void incompleteInitializationRejectionClearsPendingAndShowsReasonAtServiceBoundary() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        QueueExecutor initialization = new QueueExecutor();
        installAttemptCoordinator(OperationAttemptStore.inMemory(), () -> 46L, initialization);
        MainActivity activity = launch();

        activity.findViewById(R.id.btnRun).performClick();
        ServiceController<ExploitService> serviceController = deliverServiceRequest(93);
        assertTrue("init load must still be queued", initialization.hasNext());
        coordinatorObservers.runAll();

        RunCoordinator.Snapshot rejection = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.IDLE, rejection.getState());
        assertTrue(rejection.getRequestFailure().contains("attempt store initialization is not complete"));
        assertFalse(coordinatorWorker.hasNext());
        assertEquals(0, immediateEngine.calls.get());
        assertFalse(ReflectionHelpers.getField(activity, "mManualStartPending"));
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Operation request rejected"));
        assertTrue(output(activity).contains("attempt store initialization is not complete"));
        assertTrue(Shadows.shadowOf(serviceController.get()).isStoppedBySelf());
    }

    @Test
    public void startingOperationDisablesRunAndManagerWithoutARequest() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        FakeLease lease = new FakeLease();
        assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        MainActivity activity = launch();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        assertEquals(RunCoordinator.State.STARTING, coordinator.getSnapshot().getState());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertFalse(activity.findViewById(R.id.spinnerSuManager).isEnabled());
        assertTrue(output(activity).contains("admitted MANUAL operation"));
        selectManager(activity, SuManager.CLASSIC.getPackageName());
        assertEquals(SuManager.NEXT.getPackageName(), selectedPackage(
                activity.findViewById(R.id.spinnerSuManager)));
        assertEquals(0, store.writes.size());
        assertFalse(repository.isWritePending());
        activity.findViewById(R.id.btnRun).performClick();
        assertTrue(starter.requests.isEmpty());
    }

    @Test
    public void runningOperationDisablesRunAndManager() throws Exception {
        assertActiveOperationDisablesControls(false);
    }

    @Test
    public void indeterminateOperationDisablesRunAndManager() throws Exception {
        assertActiveOperationDisablesControls(true);
    }

    @Test
    public void recreationRestoresRunningStateAndCoordinatorLog() throws Exception {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        BlockingEngine engine = installBlockingCoordinator(false, "line before rotation\n");
        ActivityController<MainActivity> controller = launchController();
        MainActivity first = controller.get();

        assertFalse(first.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(first).contains("line before rotation"));

        controller.recreate();
        MainActivity recreated = controller.get();

        assertEquals(RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
        assertFalse(recreated.findViewById(R.id.btnRun).isEnabled());
        assertFalse(recreated.findViewById(R.id.spinnerSuManager).isEnabled());
        assertTrue(output(recreated).contains("line before rotation"));
    }

    @Test
    public void recreatedTerminalActivityRendersResultAndShowsToastOnlyOncePerOperation() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        FakeLease lease = new FakeLease();
        assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        coordinatorWorker.runAll();
        coordinatorObservers.runAll();
        String operationId = coordinator.getSnapshot().getOperationId();

        ActivityController<MainActivity> controller = launchController();
        MainActivity first = controller.get();
        assertTrue(output(first).contains("terminal: SUCCESS/LATE_LOAD_COMPLETED"));
        assertTrue(first.findViewById(R.id.btnRun).isEnabled());
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals(operationId, ReflectionHelpers.getStaticField(MainActivity.class,
                "sLastResultToastOperationId"));

        controller.recreate();
        MainActivity recreated = controller.get();
        assertTrue(output(recreated).contains("terminal: SUCCESS/LATE_LOAD_COMPLETED"));
        assertEquals(1, ShadowToast.shownToastCount());
    }

    @Test
    public void multipleActivityInstancesCannotStartOverlappingWork() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        MainActivity first = launch();
        MainActivity second = launch();
        CoordinatingStarter starter = new CoordinatingStarter(coordinator);
        MainActivity.installServiceStarterForTesting(first, starter);
        MainActivity.installServiceStarterForTesting(second, starter);

        first.findViewById(R.id.btnRun).performClick();
        second.findViewById(R.id.btnRun).performClick();

        assertEquals(1, starter.requests.size());
        assertEquals(1, starter.acceptedLeases.size());
        assertEquals(0, starter.rejectedLeases.size());
        assertEquals(RunCoordinator.State.STARTING, coordinator.getSnapshot().getState());
        assertEquals(1, coordinatorWorker.commands.size());
        coordinatorObservers.runAll();
        assertFalse(first.findViewById(R.id.btnRun).isEnabled());
        assertFalse(second.findViewById(R.id.btnRun).isEnabled());
    }

    @Test
    public void savingConfigurationCannotStartRun() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        MainActivity activity = launchReady();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        assertTrue(repository.setDisableModules(true));
        activity.findViewById(R.id.btnRun).performClick();

        assertTrue(starter.requests.isEmpty());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
    }

    @Test
    public void errorConfigurationCannotStartRun() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        MainActivity activity = launchReady();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);
        store.results.add(false);
        store.results.add(true);

        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.btnRun).performClick();

        assertTrue(starter.requests.isEmpty());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
    }

    @Test
    public void frozenConfigurationCannotStartRun() {
        MainActivity activity = launchReady();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        ShadowLooper.idleMainLooper();

        activity.findViewById(R.id.btnRun).performClick();

        assertTrue(starter.requests.isEmpty());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        admission.close();
    }

    @Test
    public void configurationMessagesFollowCoordinatorLogAndRemainBounded() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        FakeLease lease = new FakeLease();
        assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        coordinatorWorker.runAll();
        coordinatorObservers.runAll();
        MainActivity activity = launch();
        store.results.add(false);
        store.results.add(true);

        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        String rendered = output(activity);
        assertTrue(rendered.indexOf("terminal: SUCCESS") < rendered.indexOf("Configuration error"));
        ReflectionHelpers.callInstanceMethod(activity, "appendUiMessage",
                ReflectionHelpers.ClassParameter.from(String.class, new String(new char[40_000])
                        .replace('\0', 'x') + "tail"));
        assertTrue(output(activity).length() <= MAX_UI_OUTPUT_CHARACTERS
                + RunCoordinator.MAX_LOG_CHARACTERS);
        StringBuilder uiBuffer = ReflectionHelpers.getField(activity, "mUiMessages");
        assertTrue(uiBuffer.length() <= MAX_UI_OUTPUT_CHARACTERS);
        assertTrue(output(activity).endsWith("tail"));
    }

    @Test
    public void missingNotificationPermissionDoesNotBlockManualServiceRequest() {
        MainActivity activity = launchReady();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS);

        activity.findViewById(R.id.btnRun).performClick();

        assertEquals(1, starter.requests.size());
    }

    @Test
    public void activityHasNoExecutorRunnerPreflightOrWakeLockSeams() throws Exception {
        File source = TestFixtures.repositoryRoot().resolve("app/src/main/java/df/root/MainActivity.java")
                .toFile();
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        // The Activity owns only a READ-ONLY diagnostic scheduler, never an
        // operation executor. Strip the scheduler imports and its single bounded
        // worker helper before checking operation paths; the two scheduler fields
        // are intentionally allowed. Every start still goes to the service.
        String operationSource = text
                .replace("import java.util.concurrent.Executors;", "")
                .replace("import java.util.concurrent.ExecutorService;", "")
                .replaceFirst(
                        "private static Executor newDiagnosticWorker\\(\\) \\{[\\s\\S]*?\\n    \\}",
                        "");
        for (String forbidden : new String[] {"PowerManager", "WakeLock",
                "ExploitRunner", "PreflightChecker", "RunLauncher", "shutdownNow",
                "implements IReporter", "coordinator.submit", "submit(",
                "newSingleThreadExecutor", "Executors."}) {
            assertFalse("Activity must not own execution through " + forbidden,
                    operationSource.contains(forbidden));
        }
        assertTrue(text.contains("sDiagnosticWorker"));
        assertTrue(text.contains("sDiagnosticMain"));
        assertTrue(text.contains("ExploitService.ACTION_MANUAL"));
        assertTrue(text.contains("RunCoordinator"));
    }

    @Test
    public void availabilityDiagnosticReportsEveryPreflightRejectionWithoutEffects() throws Exception {
        boolean symlinksSupported = symlinksSupported(assetFolder.getRoot());
        List<AssetCase> cases = new ArrayList<>(Arrays.asList(AssetCase.values()));
        if (!symlinksSupported) {
            cases.remove(AssetCase.LOADER_SYMLINK);
            cases.remove(AssetCase.BOOTSTRAP_SYMLINK);
            cases.remove(AssetCase.NATIVE_SYMLINK);
        }

        for (AssetCase assetCase : cases) {
            SuManager manager = SuManager.CLASSIC;
            File nativeDir = assetFolder.newFolder("native-" + assetCase.name());
            installReadyAssets(manager, nativeDir);
            FakeAvailabilityEnvironment environment = new FakeAvailabilityEnvironment(
                    manager.getPackageName(), nativeDir.getAbsolutePath());
            String configPackage = manager.getPackageName();
            switch (assetCase) {
                case READY:
                    break;
                case NO_MANAGER_SELECTED:
                    configPackage = null;
                    environment.selectedPackage = null;
                    break;
                case UNSUPPORTED_MANAGER:
                    configPackage = "com.example.unsupported";
                    environment.selectedPackage = configPackage;
                    break;
                case MANAGER_NOT_INSTALLED:
                    environment.installedPackages.remove(environment.selectedPackage);
                    break;
                case SECONDARY_USER:
                    environment.ownerUser = false;
                    break;
                case UNSUPPORTED_FIRMWARE:
                    environment.firmware = new FirmwareGate.Inputs("dm2q", "SM-S9180",
                            "CP2A.260605.016", 37, "2026-10-05",
                            "samsung/dm2qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys",
                            "5.15.197-android13-8-34343818-abS9180ZHU8GZIF");
                    break;
                case LOG_BUFFER_OFF:
                    environment.logTag = "S";
                    break;
                case NATIVE_LIBRARY_DIR_MISSING:
                    environment.nativeLibraryDir = null;
                    break;
                case LOADER_MISSING:
                    Files.delete(new File(nativeDir, manager.getLoaderName()).toPath());
                    break;
                case LOADER_SYMLINK:
                    replaceWithSymlink(nativeDir, manager.getLoaderName());
                    break;
                case LOADER_NOT_REGULAR:
                    replaceWithDirectory(nativeDir, manager.getLoaderName());
                    break;
                case LOADER_HASH_MISMATCH:
                    corruptLastByte(nativeDir, manager.getLoaderName());
                    break;
                case BOOTSTRAP_MISSING:
                    Files.delete(new File(nativeDir, RunPreflight.BOOTSTRAP_BIN).toPath());
                    break;
                case BOOTSTRAP_SYMLINK:
                    replaceWithSymlink(nativeDir, RunPreflight.BOOTSTRAP_BIN);
                    break;
                case BOOTSTRAP_NOT_REGULAR:
                    replaceWithDirectory(nativeDir, RunPreflight.BOOTSTRAP_BIN);
                    break;
                case NATIVE_MISSING:
                    Files.delete(new File(nativeDir, RunPreflight.NATIVE_BIN).toPath());
                    break;
                case NATIVE_SYMLINK:
                    replaceWithSymlink(nativeDir, RunPreflight.NATIVE_BIN);
                    break;
                case NATIVE_NOT_REGULAR:
                    replaceWithDirectory(nativeDir, RunPreflight.NATIVE_BIN);
                    break;
                case ASSET_INSPECTION_FAILED:
                    environment.nativeLibraryDir =
                            assetFolder.newFile("not-a-directory-" + assetCase.name()).getAbsolutePath();
                    break;
                case NEXT_WITH_ONLY_CLASSIC_LOADER:
                    configPackage = SuManager.NEXT.getPackageName();
                    environment.selectedPackage = configPackage;
                    break;
                case CLASSIC_WITH_ONLY_NEXT_LOADER:
                    Files.delete(new File(nativeDir, SuManager.CLASSIC.getLoaderName()).toPath());
                    Files.copy(TestFixtures.bundledLoader(SuManager.NEXT),
                            new File(nativeDir, SuManager.NEXT.getLoaderName()).toPath());
                    break;
                default:
                    throw new AssertionError(assetCase);
            }

            Map<String, String> before = snapshot(nativeDir);
            final String expectedConfigPackage = configPackage;
            MainActivity.installAvailabilityEnvironmentForTesting((context, confirmed) -> {
                assertEquals("diagnostic must be built from the confirmed selection",
                        expectedConfigPackage,
                        confirmed == null ? null : confirmed.getManagerPackage());
                return environment;
            });
            installConfiguration(new RunConfig(configPackage, false, false, 0, 12), true);
            installIdleCoordinator();
            MainActivity activity = launch();

            TextView diagnosticView = MainActivity.availabilityViewForTesting(activity);
            assertEquals(assetCase.name(), View.VISIBLE, diagnosticView.getVisibility());
            String diagnostic = diagnosticView.getText().toString();
            if (assetCase == AssetCase.READY) {
                assertTrue("expected no diagnostic, got: " + diagnostic, diagnostic.isEmpty());
            } else {
                assertTrue(assetCase.name() + ": " + diagnostic,
                        diagnostic.startsWith("Run unavailable: "));
                assertTrue(assetCase.name() + ": " + diagnostic,
                        diagnostic.contains(assetCase.expectedReason));
            }

            // A read-only diagnostic must not stage, execute, fall back, or write anything.
            assertEquals(assetCase.name() + " mutated bundled assets", before, snapshot(nativeDir));
            assertNull(assetCase.name() + " started an operation",
                    Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                            .getNextStartedService());
            assertTrue(assetCase.name() + " wrote configuration", store.writes.isEmpty());
            if (environment.queriedManagerPackage != null) {
                assertEquals(assetCase.name() + " queried a different manager",
                        environment.selectedPackage, environment.queriedManagerPackage);
            }
            assertEquals(assetCase.name() + " changed the manager spinner", 3,
                    ((android.widget.Spinner) activity.findViewById(R.id.spinnerSuManager))
                            .getAdapter().getCount());
        }
    }

    @Test
    public void availabilityDiagnosticUsesRealReadOnlyEnvironmentByDefault() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();

        MainActivity activity = launch();

        String diagnostic = MainActivity.availabilityViewForTesting(activity).getText().toString();
        assertTrue(diagnostic, diagnostic.startsWith("Run unavailable: "));
        assertTrue(diagnostic, diagnostic.contains("unsupported firmware"));
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());
    }

    @Test
    public void unreadableAvailabilityObservationsFailClosedWithoutEffects() {
        MainActivity.installAvailabilityEnvironmentForTesting((context, confirmed) -> {
            throw new IllegalStateException("observation denied");
        });
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();

        MainActivity activity = launch();

        String diagnostic = MainActivity.availabilityViewForTesting(activity).getText().toString();
        assertTrue(diagnostic, diagnostic.contains("availability could not be determined"));
        assertFalse(diagnostic, diagnostic.contains("SUCCESS"));
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());
    }

    @Test
    public void availabilityDiagnosticRunsOffUiThreadWithBoundedPendingStateAndFailsClosed() throws Exception {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        BlockingDiagnosticExecutor worker = new BlockingDiagnosticExecutor();
        MainActivity.installDiagnosticExecutorsForTesting(worker, Runnable::run);
        BlockingFactory factory = new BlockingFactory();
        MainActivity.installAvailabilityEnvironmentForTesting(factory);

        MainActivity activity = launch();

        assertTrue("observation must start on the diagnostic worker",
                factory.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNotNull(factory.thread);
        assertFalse("diagnostic ran on the caller", Thread.currentThread().equals(factory.thread));
        TextView diagnosticView = MainActivity.availabilityViewForTesting(activity);
        assertEquals("Checking availability...", diagnosticView.getText().toString());
        assertTrue("pending diagnostic changed button eligibility",
                activity.findViewById(R.id.btnRun).isEnabled());
        assertNull("diagnostic started an operation",
                Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                        .getNextStartedService());
        assertTrue("diagnostic wrote configuration", store.writes.isEmpty());

        factory.release.countDown();
        assertTrue(worker.finished.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        String diagnostic = diagnosticView.getText().toString();
        assertTrue(diagnostic, diagnostic.contains("availability could not be determined"));
        assertFalse(diagnostic, diagnostic.contains("SUCCESS"));
        assertEquals(diagnostic, MainActivity.cachedDiagnosticForTesting(activity));
    }

    @Test
    public void staleAvailabilityDiagnosticCannotOverwriteCurrentConfig() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        ManualDiagnosticExecutor worker = new ManualDiagnosticExecutor();
        MainActivity.installDiagnosticExecutorsForTesting(worker, Runnable::run);
        long clickedGeneration = repository.getConfirmedSnapshot().getGeneration();

        File nativeDir = assetFolder.getRoot();
        FakeAvailabilityEnvironment stale = new FakeAvailabilityEnvironment(
                SuManager.NEXT.getPackageName(), nativeDir.getAbsolutePath());
        stale.ownerUser = false;
        MainActivity.installAvailabilityEnvironmentForTesting((context, confirmed) -> stale);
        MainActivity activity = launch();
        assertTrue(worker.size() >= 1);
        int observationsBeforeChange = worker.size();

        FakeAvailabilityEnvironment current = new FakeAvailabilityEnvironment(
                SuManager.NEXT.getPackageName(), nativeDir.getAbsolutePath());
        current.firmware = new FirmwareGate.Inputs("dm2q", "SM-S9180",
                "CP2A.260605.016", 37, "2026-10-05",
                "samsung/dm2qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys",
                "5.15.197-android13-8-34343818-abS9180ZHU8GZIF");
        MainActivity.installAvailabilityEnvironmentForTesting((context, confirmed) -> current);
        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(clickedGeneration + 1, repository.getConfirmedSnapshot().getGeneration());
        assertTrue("config change must dispatch a fresh observation",
                worker.size() > observationsBeforeChange);

        // Apply the newest generation first, then the stale one: the stale
        // observation must not overwrite what the current config published.
        worker.runLast();
        TextView diagnosticView = MainActivity.availabilityViewForTesting(activity);
        String currentDiagnostic = diagnosticView.getText().toString();
        assertTrue(currentDiagnostic, currentDiagnostic.contains("unsupported firmware"));
        assertFalse(currentDiagnostic, currentDiagnostic.contains("owner Android user"));

        worker.runNext();
        assertEquals(currentDiagnostic, diagnosticView.getText().toString());
        assertEquals(currentDiagnostic, MainActivity.cachedDiagnosticForTesting(activity));
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertNull(Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService());
    }

    private static void installReadyAssets(SuManager manager, File nativeDir) throws Exception {
        Files.copy(TestFixtures.bundledLoader(manager),
                new File(nativeDir, manager.getLoaderName()).toPath());
        Files.write(new File(nativeDir, RunPreflight.BOOTSTRAP_BIN).toPath(),
                new byte[] {1, 2, 3});
        Files.write(new File(nativeDir, RunPreflight.NATIVE_BIN).toPath(),
                new byte[] {4, 5, 6});
    }

    private static void replaceWithSymlink(File nativeDir, String name) throws Exception {
        File target = new File(nativeDir, "link-target-" + name);
        Files.write(target.toPath(), new byte[] {9});
        Files.delete(new File(nativeDir, name).toPath());
        Files.createSymbolicLink(new File(nativeDir, name).toPath(), target.toPath());
    }

    private static void replaceWithDirectory(File nativeDir, String name) throws Exception {
        Files.delete(new File(nativeDir, name).toPath());
        Files.createDirectory(new File(nativeDir, name).toPath());
    }

    private static void corruptLastByte(File nativeDir, String name) throws Exception {
        File file = new File(nativeDir, name);
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file.toPath(), bytes);
    }

    private static boolean symlinksSupported(File directory) {
        try {
            File target = new File(directory, "probe-target");
            File link = new File(directory, "probe-link");
            Files.write(target.toPath(), new byte[] {1});
            Files.createSymbolicLink(link.toPath(), target.toPath());
            boolean supported = Files.isSymbolicLink(link.toPath());
            Files.deleteIfExists(link.toPath());
            Files.deleteIfExists(target.toPath());
            return supported;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    private static Map<String, String> snapshot(File directory) {
        File[] files = directory.listFiles();
        assertNotNull(files);
        Map<String, String> result = new TreeMap<>();
        for (File file : files) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file.toPath(),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String kind = attributes.isSymbolicLink() ? "link"
                        : attributes.isRegularFile() ? "regular"
                        : attributes.isDirectory() ? "dir" : "other";
                String content = attributes.isRegularFile()
                        ? Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())) : "";
                result.put(file.getName(), kind + ":" + attributes.size() + ":" + content);
            } catch (IOException e) {
                throw new AssertionError("could not snapshot " + file, e);
            }
        }
        return result;
    }

    private enum AssetCase {
        READY(""),
        NO_MANAGER_SELECTED("select a supported SU manager"),
        UNSUPPORTED_MANAGER("selected SU manager is not supported"),
        MANAGER_NOT_INSTALLED("selected SU manager is not installed or visible"),
        SECONDARY_USER("runs are supported only for the owner Android user"),
        UNSUPPORTED_FIRMWARE("unsupported firmware: device does not match documented target"),
        LOG_BUFFER_OFF("Developer Options log buffer size is Off"),
        NATIVE_LIBRARY_DIR_MISSING("nativeLibraryDir is unavailable"),
        LOADER_MISSING("selected bundled loader is missing"),
        LOADER_SYMLINK("selected bundled loader must not be a symlink"),
        LOADER_NOT_REGULAR("selected bundled loader must be a regular file"),
        LOADER_HASH_MISMATCH("selected bundled loader failed its SHA-256 pin"),
        BOOTSTRAP_MISSING("libbootstrap.so is missing"),
        BOOTSTRAP_SYMLINK("libbootstrap.so must not be a symlink"),
        BOOTSTRAP_NOT_REGULAR("libbootstrap.so must be a regular file"),
        NATIVE_MISSING("libdfroot.so is missing"),
        NATIVE_SYMLINK("libdfroot.so must not be a symlink"),
        NATIVE_NOT_REGULAR("libdfroot.so must be a regular file"),
        ASSET_INSPECTION_FAILED("bundled assets could not be inspected safely"),
        NEXT_WITH_ONLY_CLASSIC_LOADER("selected bundled loader is missing"),
        CLASSIC_WITH_ONLY_NEXT_LOADER("selected bundled loader is missing");

        final String expectedReason;

        AssetCase(String expectedReason) {
            this.expectedReason = expectedReason;
        }
    }

    private static final class FakeAvailabilityEnvironment implements RunPreflight.Environment {
        String selectedPackage;
        final Set<String> installedPackages = new HashSet<>(Arrays.asList(
                SuManager.CLASSIC.getPackageName(), SuManager.NEXT.getPackageName()));
        boolean ownerUser = true;
        String nativeLibraryDir;
        String logTag = "";
        FirmwareGate.Inputs firmware = supportedFirmware();
        String queriedManagerPackage;

        FakeAvailabilityEnvironment(String selectedPackage, String nativeLibraryDir) {
            this.selectedPackage = selectedPackage;
            this.nativeLibraryDir = nativeLibraryDir;
        }

        @Override
        public String getSelectedManagerPackage() {
            return selectedPackage;
        }

        @Override
        public boolean isManagerInstalled(String packageName) {
            queriedManagerPackage = packageName;
            return installedPackages.contains(packageName);
        }

        @Override
        public boolean isOwnerUser() {
            return ownerUser;
        }

        @Override
        public String getNativeLibraryDir() {
            return nativeLibraryDir;
        }

        @Override
        public String getLogTag() {
            return logTag;
        }

        @Override
        public FirmwareGate.Inputs getFirmware() {
            return firmware;
        }

        private static FirmwareGate.Inputs supportedFirmware() {
            return new FirmwareGate.Inputs("dm3q", "SM-S9180", "CP2A.260605.016", 37,
                    "2026-10-05",
                    "samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys",
                    "5.15.197-android13-8-34343818-abS9180ZHU8GZIF");
        }
    }

    private void assertActiveOperationDisablesControls(boolean indeterminate) throws Exception {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        BlockingEngine engine = installBlockingCoordinator(indeterminate, "active line\n");
        MainActivity activity = launch();
        RecordingStarter starter = new RecordingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        assertEquals(indeterminate ? RunCoordinator.State.INDETERMINATE
                : RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertFalse(activity.findViewById(R.id.spinnerSuManager).isEnabled());
        selectManager(activity, SuManager.CLASSIC.getPackageName());
        assertEquals(SuManager.NEXT.getPackageName(), selectedPackage(
                activity.findViewById(R.id.spinnerSuManager)));
        assertEquals(0, store.writes.size());
        assertFalse(repository.isWritePending());
        activity.findViewById(R.id.btnRun).performClick();
        assertTrue(starter.requests.isEmpty());
        engine.finish();
    }

    private BlockingEngine installBlockingCoordinator(boolean indeterminate, String line)
            throws Exception {
        BlockingEngine engine = new BlockingEngine(indeterminate, line);
        blockingEngines.add(engine);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        workers.add(worker);
        coordinator = new RunCoordinator(repository, worker,
                ConfigurationRepository.newMainExecutor(), engine, 0L);
        RunCoordinator.setInstanceForTesting(coordinator);
        assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                RunConfig.ExecutionMode.MANUAL, new FakeLease()).isAccepted());
        assertTrue(engine.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        ShadowLooper.idleMainLooper();
        return engine;
    }

    private MainActivity launchReady() {
        installConfiguration(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()), true);
        installIdleCoordinator();
        MainActivity activity = launch();
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        return activity;
    }

    private void installConfiguration(RunConfig initial, boolean completeInitialization) {
        store = new FakeStore(initial);
        configExecutor = new QueueExecutor();
        repository = new ConfigurationRepository(store, configExecutor, Runnable::run);
        repository.start();
        if (completeInitialization) {
            configExecutor.runNext();
        }
        ConfigurationRepository.setInstanceForTesting(repository);
    }

    private void installIdleCoordinator() {
        coordinatorWorker = new QueueExecutor();
        coordinatorObservers = new QueueExecutor();
        immediateEngine = new ImmediateEngine();
        // The dedicated outcome executor is injected here so tests drain it with
        // the same deterministic queue as ordinary observer publications.
        coordinator = new RunCoordinator(repository, coordinatorWorker, coordinatorObservers,
                immediateEngine, 0L, coordinatorObservers);
        RunCoordinator.setInstanceForTesting(coordinator);
    }

    private void installAttemptCoordinator(OperationAttemptStore attemptStore,
                                           BootCountReader bootCountReader,
                                           Executor initializationExecutor) {
        coordinatorWorker = new QueueExecutor();
        coordinatorObservers = new QueueExecutor();
        immediateEngine = new ImmediateEngine();
        coordinator = new RunCoordinator(repository, coordinatorWorker, coordinatorObservers,
                immediateEngine, 0L, attemptStore, bootCountReader, initializationExecutor);
        RunCoordinator.setInstanceForTesting(coordinator);
    }

    /** Initializes a coordinator whose checked publish can be held deterministically. */
    private void installBlockingAttemptCoordinator(OperationAttemptStore attemptStore,
                                                   Executor outcomeExecutor) {
        coordinatorWorker = new QueueExecutor();
        coordinatorObservers = new QueueExecutor();
        immediateEngine = new ImmediateEngine();
        coordinator = new RunCoordinator(repository, coordinatorWorker, coordinatorObservers,
                immediateEngine, 0L, attemptStore, () -> 44L, Runnable::run, () -> true,
                outcomeExecutor);
        RunCoordinator.setInstanceForTesting(coordinator);
        coordinator.initialize();
    }

    private ServiceController<ExploitService> deliverServiceRequest(int startId) {
        Intent request = nextServiceRequest();
        ServiceController<ExploitService> serviceController = Robolectric.buildService(
                ExploitService.class).create().withIntent(request);
        serviceControllers.add(serviceController);
        // Admission is normally off-main; run it inline for these deterministic tests.
        serviceController.get().setHostExecutorForTesting(Runnable::run); // R6.4 inline host seam
        serviceController.get().setAdmissionExecutorForTesting(Runnable::run);
        serviceController.startCommand(0, startId);
        return serviceController;
    }

    private MainActivity launch() {
        return launchController().get();
    }

    private Intent nextServiceRequest() {
        Intent request = Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedService();
        assertNotNull("Activity click must enqueue a real foreground-service request", request);
        return request;
    }

    private String clickAndReadRequestId(ActivityController<MainActivity> controller) {
        controller.get().findViewById(R.id.btnRun).performClick();
        String requestId = nextServiceRequest().getStringExtra(ExploitService.EXTRA_REQUEST_ID);
        assertNotNull(requestId);
        return requestId;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private ActivityController<MainActivity> launchController() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        activityControllers.add(controller);
        return controller;
    }

    private int selectManager(MainActivity activity, String packageName) {
        Spinner spinner = activity.findViewById(R.id.spinnerSuManager);
        int position = managerPosition(spinner, packageName);
        spinner.setSelection(position);
        ShadowLooper.idleMainLooper();
        return position;
    }

    private int managerPosition(Spinner spinner, String packageName) {
        for (int i = 1; i < spinner.getAdapter().getCount(); i++) {
            if (packageName.equals(ReflectionHelpers.getField(
                    spinner.getAdapter().getItem(i), "packageName"))) {
                return i;
            }
        }
        throw new AssertionError("manager not offered: " + packageName);
    }

    private String selectedPackage(Spinner spinner) {
        Object selected = spinner.getSelectedItem();
        return selected == null ? null : ReflectionHelpers.getField(selected, "packageName");
    }

    private String output(MainActivity activity) {
        return ((TextView) activity.findViewById(R.id.outputView)).getText().toString();
    }

    private void installManager(String packageName) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .installPackage(info);
    }

    /** Holds the single read-only tick; cancels and delayed posts are deterministic. */
    private static final class ManualOutcomeWatchScheduler
            implements MainActivity.OutcomeWatchScheduler {
        private Runnable pending;
        final List<Long> delays = new ArrayList<>();
        int schedules;
        int cancels;
        int ticks;

        @Override
        public void schedule(Runnable tick, long delayMs) {
            pending = tick;
            delays.add(delayMs);
            schedules++;
        }

        @Override
        public void cancel() {
            pending = null;
            cancels++;
        }

        boolean hasPending() {
            return pending != null;
        }

        void runNext() {
            Runnable next = pending;
            pending = null;
            assertNotNull("no watch tick was pending", next);
            ticks++;
            next.run();
        }

        void runAll() {
            int guard = 0;
            while (hasPending()) {
                runNext();
                assertTrue("watch tick cap was exceeded", ++guard <= 100);
            }
        }
    }

    /**
     * R7.2 HOST-ONLY read-only lookup: one scripted observation per call. An
     * empty script fails loudly, so an accidental extra ledger read is a test
     * failure rather than a silently passing assertion.
     */
    private static final class ScriptedOutcomeLookup
            implements Function<String, RunCoordinator.RequestOutcomeRecord> {
        final Queue<RunCoordinator.RequestOutcomeRecord> script = new LinkedList<>();
        final List<String> requestIds = new ArrayList<>();
        int calls;

        @Override
        public RunCoordinator.RequestOutcomeRecord apply(String requestId) {
            calls++;
            requestIds.add(requestId);
            return script.remove();
        }
    }

    /** Counts attempt-store clears so unconfirmed UI status cannot mutate durable state. */
    private static final class CountingAttemptStore implements OperationAttemptStore {
        private final OperationAttemptStore delegate = OperationAttemptStore.inMemory();
        int clears;

        @Override
        public OperationAttempt load() throws Failure {
            return delegate.load();
        }

        @Override
        public boolean publish(OperationAttempt attempt) throws Failure {
            return delegate.publish(attempt);
        }

        @Override
        public boolean clear(String operationId) throws Failure {
            clears++;
            return delegate.clear(operationId);
        }
    }

    private static final class RecordingStarter implements MainActivity.ServiceStarter {
        final List<Long> requests = new ArrayList<>();
        final List<String> requestIds = new ArrayList<>();

        @Override
        public void startManual(long expectedGeneration, String requestId) {
            requests.add(expectedGeneration);
            requestIds.add(requestId);
        }
    }

    private static final class FailingStarter implements MainActivity.ServiceStarter {
        int calls;

        @Override
        public void startManual(long expectedGeneration, String requestId) {
            calls++;
            throw new IllegalStateException("service start denied");
        }
    }

    /** Captures the click-time correlation ID before throwing, harmlessly. */
    private static final class CapturingFailingStarter implements MainActivity.ServiceStarter {
        private final String message;
        int calls;
        String requestId;

        CapturingFailingStarter(String message) {
            this.message = message;
        }

        @Override
        public void startManual(long expectedGeneration, String requestId) {
            calls++;
            this.requestId = requestId;
            throw new IllegalStateException(message);
        }
    }

    /** Blocks inside publish while the coordinator holds the durable lock. */
    private static final class BlockingPublishStore implements OperationAttemptStore {
        private final OperationAttemptStore delegate = OperationAttemptStore.inMemory();
        final CountDownLatch publishEntered = new CountDownLatch(1);
        final CountDownLatch releasePublish = new CountDownLatch(1);

        @Override
        public OperationAttempt load() throws Failure {
            return delegate.load();
        }

        @Override
        public boolean publish(OperationAttempt attempt) throws Failure {
            publishEntered.countDown();
            try {
                assertTrue("blocked-store release latch timed out",
                        releasePublish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return delegate.publish(attempt);
        }

        @Override
        public boolean clear(String operationId) throws Failure {
            return delegate.clear(operationId);
        }
    }

    private static final class CoordinatingStarter implements MainActivity.ServiceStarter {
        final RunCoordinator coordinator;
        final List<Long> requests = new ArrayList<>();
        final List<FakeLease> acceptedLeases = new ArrayList<>();
        final List<FakeLease> rejectedLeases = new ArrayList<>();

        CoordinatingStarter(RunCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public void startManual(long expectedGeneration, String requestId) {
            requests.add(expectedGeneration);
            FakeLease lease = new FakeLease();
            RunCoordinator.Submission submission = coordinator.submit(
                    RuntimeEnvironment.getApplication(), RunConfig.ExecutionMode.MANUAL, lease,
                    expectedGeneration, requestId);
            (submission.isAccepted() ? acceptedLeases : rejectedLeases).add(lease);
        }
    }

    private static final class FakeLease implements ResourceLease {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class ImmediateEngine implements RunCoordinator.Engine {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public RunResult run(Context context, ConfigurationRepository.RunAdmission admission,
                             RunConfig.ExecutionMode mode, String operationId,
                             RunEventListener events, IReporter reporter) {
            calls.incrementAndGet();
            reporter.report("coordinator log line\n");
            return NativeResultMapper.map(0);
        }
    }

    private static final class BlockingEngine implements RunCoordinator.Engine {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final boolean indeterminate;
        final String line;

        BlockingEngine(boolean indeterminate, String line) {
            this.indeterminate = indeterminate;
            this.line = line;
        }

        @Override
        public RunResult run(Context context, ConfigurationRepository.RunAdmission admission,
                             RunConfig.ExecutionMode mode, String operationId,
                             RunEventListener events, IReporter reporter) {
            reporter.report(line);
            if (indeterminate) {
                events.onTimeout();
            }
            entered.countDown();
            try {
                release.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
            return NativeResultMapper.map(0);
        }

        void finish() {
            release.countDown();
        }
    }

    private static final class QueueExecutor implements Executor {
        final Queue<Runnable> commands = new ArrayDeque<>();

        boolean hasNext() {
            return !commands.isEmpty();
        }

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runAll() {
            while (!commands.isEmpty()) {
                runNext();
            }
        }

        void runNext() {
            commands.remove().run();
        }
    }

    /** Runs observation work on a controlled off-caller thread. */
    private static final class BlockingDiagnosticExecutor implements Executor {
        final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            Thread thread = new Thread(() -> {
                try {
                    command.run();
                } finally {
                    finished.countDown();
                }
            }, "test-availability");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Observation source that blocks so pending state can be observed deterministically. */
    private static final class BlockingFactory implements MainActivity.AvailabilityEnvironmentFactory {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread thread;

        @Override
        public RunPreflight.Environment create(Context context, RunConfig confirmed) {
            thread = Thread.currentThread();
            entered.countDown();
            try {
                assertTrue(release.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("observation denied");
        }
    }

    /** Holds diagnostic observations so newest-first completion can be forced. */
    private static final class ManualDiagnosticExecutor implements Executor {
        private final java.util.Deque<Runnable> commands = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        int size() {
            return commands.size();
        }

        void runNext() {
            commands.removeFirst().run();
        }

        void runLast() {
            commands.removeLast().run();
        }
    }

    private static final class FakeStore implements ConfigurationStore {
        RunConfig cached;
        RunConfig durable;
        final List<RunConfig> writes = new ArrayList<>();
        final Queue<Boolean> results = new ArrayDeque<>();

        FakeStore(RunConfig loaded) {
            cached = loaded;
            durable = loaded;
        }

        @Override
        public RunConfig load() {
            return cached;
        }

        @Override
        public boolean write(RunConfig config) {
            writes.add(config);
            cached = config;
            boolean result = results.isEmpty() || results.remove();
            if (result) {
                durable = config;
            }
            return result;
        }
    }
}
