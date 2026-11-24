// Modified 2026-11-24 (DFRoot GZIF): verify manager controls reconcile to durable configuration and fail closed. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfo;
import android.os.Looper;
import android.widget.Button;
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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class MainActivityTest {
    private ActivityController<MainActivity> activityController;
    private FakeStore store;
    private QueueExecutor configExecutor;
    private ConfigurationRepository repository;

    @Before
    public void installManagersAndResetConfiguration() {
        ConfigurationRepository.resetForTesting();
        installManager(SuManager.CLASSIC.getPackageName());
        installManager(SuManager.NEXT.getPackageName());
    }

    @After
    public void tearDown() {
        if (activityController != null) {
            activityController.pause().stop().destroy();
        }
        ConfigurationRepository.resetForTesting();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void savingConfigurationDisablesManagerAndRunControls() throws Exception {
        launch(RunConfig.defaults(), false);
        MainActivity activity = activityController.get();

        assertFalse(activity.findViewById(R.id.spinnerSuManager).isEnabled());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());

        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        waitForPreflight();

        assertTrue(activity.findViewById(R.id.spinnerSuManager).isEnabled());
        assertFalse(repository.isWritePending());
        assertEquals(0, store.writes.size());
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
    }

    @Test
    public void managerSelectionIsPersistedBeforeTheUiReflectsIt() throws Exception {
        RunConfig initial = RunConfig.defaults()
                .withManagerPackage(SuManager.CLASSIC.getPackageName());
        launch(initial);
        MainActivity activity = activityController.get();
        Spinner spinner = activity.findViewById(R.id.spinnerSuManager);
        waitForPreflight();

        int selectedPosition = selectManager(SuManager.NEXT.getPackageName());
        assertEquals("callback position " + selectedPosition,
                SuManager.NEXT.getPackageName(), ReflectionHelpers.getField(
                        spinner.getAdapter().getItem(selectedPosition), "packageName"));

        assertFalse(spinner.isEnabled());
        assertEquals(0, store.writes.size());
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        waitForPreflight();

        assertEquals(1, store.writes.size());
        assertEquals(SuManager.NEXT.getPackageName(), store.writes.get(0).getManagerPackage());
        assertEquals("position " + spinner.getSelectedItemPosition()
                        + " expected " + managerPosition(SuManager.NEXT.getPackageName()),
                SuManager.NEXT.getPackageName(), selectedPackage(spinner));
    }

    @Test
    public void failedManagerWriteRevertsTheSpinnerToTheConfirmedManager() throws Exception {
        RunConfig initial = RunConfig.defaults()
                .withManagerPackage(SuManager.CLASSIC.getPackageName());
        launch(initial);
        store.results.add(false);
        store.results.add(true);
        MainActivity activity = activityController.get();
        Spinner spinner = activity.findViewById(R.id.spinnerSuManager);
        waitForPreflight();

        int selectedPosition = selectManager(SuManager.NEXT.getPackageName());
        assertEquals("callback position " + selectedPosition,
                SuManager.NEXT.getPackageName(), ReflectionHelpers.getField(
                        spinner.getAdapter().getItem(selectedPosition), "packageName"));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertFalse(spinner.isEnabled());
        assertEquals(SuManager.CLASSIC.getPackageName(), selectedPackage(spinner));
        assertFalse(activity.findViewById(R.id.btnRun).isEnabled());
        assertTrue(((TextView) activity.findViewById(R.id.outputView)).getText().toString()
                .contains("Configuration error"));
        assertEquals(SuManager.CLASSIC.getPackageName(),
                repository.getConfirmedSnapshot().getManagerPackage());
    }

    @Test
    public void unsupportedConfirmedManagerIsClearedThroughTheRepository() throws Exception {
        RunConfig stale = new RunConfig("com.example.unsupported", false, false, 0, 12);
        launch(stale);
        MainActivity activity = activityController.get();
        Spinner spinner = activity.findViewById(R.id.spinnerSuManager);

        assertFalse(spinner.isEnabled());
        assertTrue(repository.isWritePending());
        assertEquals(0, store.writes.size());
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        waitForPreflight();

        assertTrue(spinner.isEnabled());
        assertEquals(null, selectedPackage(spinner));
        assertEquals(null, repository.getConfirmedSnapshot().getManagerPackage());
        assertEquals(13, repository.getConfirmedSnapshot().getGeneration());
    }

    @Test
    public void detachedActivityNoLongerReceivesRepositoryNotifications() throws Exception {
        launch(RunConfig.defaults());
        ConfigurationRepository.Listener listener = ReflectionHelpers.getField(
                activityController.get(), "mConfigurationListener");
        assertTrue(repository.hasListener(listener));

        activityController.pause().stop().destroy();
        activityController = null;

        assertFalse(repository.hasListener(listener));
        repository.setDisableModules(true);

        assertEquals(ConfigurationRepository.State.SAVING, repository.getState());
    }

    @Test
    public void staleGenerationAPreflightCannotEnableRunAfterGenerationB() {
        RunConfig initial = RunConfig.defaults()
                .withManagerPackage(SuManager.CLASSIC.getPackageName());
        launchWithSeams(initial);
        MainActivity activity = activityController.get();
        Button run = activity.findViewById(R.id.btnRun);
        assertFalse(run.isEnabled());
        ConfigurationRepository.Listener listener = ReflectionHelpers.getField(
                activity, "mConfigurationListener");
        repository.removeListener(listener);

        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));
        configExecutor.runNext();
        assertEquals(SuManager.NEXT.getPackageName(),
                repository.getConfirmedSnapshot().getManagerPackage());

        runNextActivityTask();
        ShadowLooper.idleMainLooper();
        assertFalse("stale A result must not enable B", run.isEnabled());
        assertEquals(0, launcher.calls);
    }

    @Test
    public void verifiedReadyButtonBecomesDisabledDuringSavingAndCannotLaunch() {
        launchReadyWithSeams();
        MainActivity activity = activityController.get();
        Button run = activity.findViewById(R.id.btnRun);
        assertTrue(run.isEnabled());

        assertTrue(repository.setDisableModules(true));

        assertFalse(run.isEnabled());
        run.performClick();
        assertEquals(0, launcher.calls);
        assertTrue(activityExecutor.commands.isEmpty());
    }

    @Test
    public void runClickDuringErrorCannotLaunch() {
        launchReadyWithSeams();
        store.results.add(false);
        store.results.add(true);
        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        activityController.get().findViewById(R.id.btnRun).performClick();

        assertEquals(0, launcher.calls);
        assertTrue(activityExecutor.commands.isEmpty());
    }

    @Test
    public void runClickWhileFrozenCannotLaunch() {
        launchReadyWithSeams();
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        ShadowLooper.idleMainLooper();

        activityController.get().findViewById(R.id.btnRun).performClick();

        assertEquals(0, launcher.calls);
        assertTrue(activityExecutor.commands.isEmpty());
        admission.close();
    }

    @Test
    public void runClickWithGenerationMismatchCannotLaunch() {
        launchReadyWithSeams();
        MainActivity activity = activityController.get();
        long currentGeneration = repository.getConfirmedSnapshot().getGeneration();
        ReflectionHelpers.setField(activity, "mPreflightGeneration", currentGeneration - 1);
        assertTrue(activity.findViewById(R.id.btnRun).isEnabled());

        activity.findViewById(R.id.btnRun).performClick();

        assertEquals(0, launcher.calls);
        assertTrue(activityExecutor.commands.isEmpty());
    }

    @Test
    public void eligibleRunClickLaunchesExactlyOnceThroughTheInjectedLauncher() {
        launchReadyWithSeams();

        activityController.get().findViewById(R.id.btnRun).performClick();
        assertEquals(1, activityExecutor.commands.size());
        assertEquals(0, launcher.calls);

        runNextActivityTask();
        ShadowLooper.idleMainLooper();

        assertEquals(1, launcher.calls);
        assertFalse(repository.isFrozen());
    }

    private ControlledExecutor activityExecutor;
    private RecordingLauncher launcher;

    private void launchReadyWithSeams() {
        launchWithSeams(RunConfig.defaults()
                .withManagerPackage(SuManager.NEXT.getPackageName()));
        runNextActivityTask();
        ShadowLooper.idleMainLooper();
        assertTrue(activityController.get().findViewById(R.id.btnRun).isEnabled());
    }

    private void launchWithSeams(RunConfig initial) {
        store = new FakeStore(initial);
        configExecutor = new QueueExecutor();
        repository = new ConfigurationRepository(store, configExecutor, Runnable::run);
        repository.start();
        ConfigurationRepository.setInstanceForTesting(repository);
        activityController = Robolectric.buildActivity(MainActivity.class).setup();
        activityExecutor = new ControlledExecutor();
        launcher = new RecordingLauncher();
        MainActivity.installExecutionSeamsForTesting(
                activityController.get(), activityExecutor,
                snapshot -> RunPreflight.Result.ready(new RunPreflight.Plan(
                        SuManager.NEXT, new java.io.File("loader"),
                        new java.io.File("bootstrap"), new java.io.File("native"))),
                launcher);
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
    }

    private void runNextActivityTask() {
        activityExecutor.runNext();
    }

    private void launch(RunConfig initial) throws Exception {
        launch(initial, true);
        waitForPreflight();
    }

    private void launch(RunConfig initial, boolean completeInitialization) {
        store = new FakeStore(initial);
        configExecutor = new QueueExecutor();
        repository = new ConfigurationRepository(store, configExecutor, Runnable::run);
        repository.start();
        if (completeInitialization) {
            configExecutor.runNext();
        }
        ConfigurationRepository.setInstanceForTesting(repository);
        activityController = Robolectric.buildActivity(MainActivity.class).setup();
    }

    private void waitForPreflight() throws Exception {
        ExecutorService executor = ReflectionHelpers.getField(activityController.get(), "mExec");
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
    }

    private int selectManager(String packageName) {
        Spinner spinner = activityController.get().findViewById(R.id.spinnerSuManager);
        int position = managerPosition(packageName);
        spinner.setSelection(position);
        ShadowLooper.idleMainLooper();
        return position;
    }

    private int managerPosition(String packageName) {
        Spinner spinner = activityController.get().findViewById(R.id.spinnerSuManager);
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

    private void installManager(String packageName) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .installPackage(info);
    }

    private static final class ControlledExecutor implements Executor {
        final Queue<Runnable> commands = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.remove().run();
        }
    }

    private static final class RecordingLauncher implements MainActivity.RunLauncher {
        int calls;

        @Override
        public RunResult launch(android.content.Context context, IReporter reporter) {
            calls++;
            return NativeResultMapper.map(0);
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

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> commands = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.remove().run();
        }
    }
}
