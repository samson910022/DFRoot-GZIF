// Modified 2026-11-24 (DFRoot GZIF): verify Activity detachment, service-only requests, coordinator observation, and single-flight UI gating. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.widget.Spinner;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class MainActivityTest {
    private static final long TEST_TIMEOUT_MS = 5_000L;
    private static final int MAX_UI_OUTPUT_CHARACTERS = 32 * 1024;

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

    @Before
    public void setUp() {
        ConfigurationRepository.resetForTesting();
        RunCoordinator.resetForTesting();
        MainActivity.resetResultToastForTesting();
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
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertEquals("", output.getText().toString());
    }

    @Test
    public void synchronousServiceStartFailureIsShownAndRestoresEligibility() {
        MainActivity activity = launchReady();
        FailingStarter starter = new FailingStarter();
        MainActivity.installServiceStarterForTesting(activity, starter);

        activity.findViewById(R.id.btnRun).performClick();

        assertEquals(1, starter.calls);
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(output(activity).contains("Unable to start operation service"));
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
        Shadows.shadowOf(serviceController.get()).setThrowInStartForeground(
                new IllegalStateException("FGS denied by platform"));
        serviceController.withIntent(request).startCommand(0, 73);

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
        serviceController.get().setWakeLockSourceForTesting(ignored -> {
            throw new IllegalStateException("PowerManager unavailable");
        });
        serviceController.startCommand(0, 74);

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
        for (String forbidden : new String[] {"Executor", "PowerManager", "WakeLock",
                "ExploitRunner", "PreflightChecker", "RunLauncher", "shutdownNow", "mExec",
                "implements IReporter"}) {
            assertFalse("Activity must not own execution through " + forbidden,
                    text.contains(forbidden));
        }
        assertTrue(text.contains("ExploitService.ACTION_MANUAL"));
        assertTrue(text.contains("RunCoordinator"));
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
        coordinator = new RunCoordinator(repository, coordinatorWorker, coordinatorObservers,
                immediateEngine, 0L);
        RunCoordinator.setInstanceForTesting(coordinator);
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

    private static final class RecordingStarter implements MainActivity.ServiceStarter {
        final List<Long> requests = new ArrayList<>();

        @Override
        public void startManual(long expectedGeneration) {
            requests.add(expectedGeneration);
        }
    }

    private static final class FailingStarter implements MainActivity.ServiceStarter {
        int calls;

        @Override
        public void startManual(long expectedGeneration) {
            calls++;
            throw new IllegalStateException("service start denied");
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
        public void startManual(long expectedGeneration) {
            requests.add(expectedGeneration);
            FakeLease lease = new FakeLease();
            RunCoordinator.Submission submission = coordinator.submit(
                    RuntimeEnvironment.getApplication(), RunConfig.ExecutionMode.MANUAL, lease,
                    expectedGeneration);
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
