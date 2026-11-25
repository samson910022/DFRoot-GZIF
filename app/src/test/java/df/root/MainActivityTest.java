// Modified 2026-11-24 (DFRoot GZIF): verify Activity detachment, service-only requests, coordinator observation, and single-flight UI gating. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): recheck service request wiring against operation-ID publication. See NOTICE.
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
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

    @Before
    public void setUp() {
        ConfigurationRepository.resetForTesting();
        RunCoordinator.resetForTesting();
        MainActivity.resetResultToastForTesting();
        MainActivity.resetAvailabilityEnvironmentForTesting();
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
