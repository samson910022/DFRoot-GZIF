// Modified 2026-11-24 (DFRoot GZIF): verify modal consent and durable settings-state reflection. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): recheck settings observation against operation-ID-bound run admission. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): verify OFF stays reachable in ERROR/active runs and component failures surface. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.preference.Preference;
import android.preference.SwitchPreference;

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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

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
public class SettingsFragmentTest {
    private static final long TEST_TIMEOUT_MS = 5_000L;
    private ActivityController<SettingsActivity> activityController;
    private SettingsFragment fragment;
    private FakeStore store;
    private QueueExecutor configExecutor;
    private ConfigurationRepository repository;
    private RunCoordinator coordinator;
    private QueueExecutor coordinatorWorker;
    private final List<BlockingEngine> blockingEngines = new ArrayList<>();
    private final List<ExecutorService> workers = new ArrayList<>();

    @Before
    public void resetConfiguration() {
        ConfigurationRepository.resetForTesting();
        RunCoordinator.resetForTesting();
        ShadowAlertDialog.reset();
    }

    @After
    public void tearDown() throws Exception {
        if (activityController != null) {
            activityController.pause().stop().destroy();
        }
        for (BlockingEngine engine : blockingEngines) {
            engine.finish();
        }
        if (coordinatorWorker != null) {
            coordinatorWorker.runAll();
        }
        for (ExecutorService worker : workers) {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void softRebootSwitchIsUnavailableAndRejectsEveryEnableAttempt() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);

        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        toggle(softReboot);
        Object accepted = ReflectionHelpers.callInstanceMethod(softReboot, "callChangeListener",
                ReflectionHelpers.ClassParameter.from(Object.class, true));

        assertEquals(Boolean.FALSE, accepted);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        assertEquals(0, store.writes.size());
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
    }

    @Test
    public void softRebootEnableIsRejectedFromStaleGenerationWithoutWrite() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        long generationBefore = repository.getConfirmedSnapshot().getGeneration();
        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        Object accepted = ReflectionHelpers.callInstanceMethod(softReboot, "callChangeListener",
                ReflectionHelpers.ClassParameter.from(Object.class, true));

        assertEquals(Boolean.FALSE, accepted);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(1, store.writes.size());
        assertFalse(store.writes.get(0).isSoftReboot());
        assertEquals(generationBefore + 1L, repository.getConfirmedSnapshot().getGeneration());
    }

    @Test
    public void legacyAcknowledgedSoftRebootIsNeverOfferedAsEnabled() {
        launch(RunConfig.defaults().withSoftReboot(true));
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);

        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(0, store.writes.size());
    }

    @Test
    public void failedWriteShowsErrorAndKeepsBootOffSwitchAvailable() {
        launch(RunConfig.defaults());
        store.results.add(false);
        store.results.add(true);
        SwitchPreference disableModules = preference(ConfigurationKeys.PREF_DISABLE_MODULES);
        Preference boot = preference("boot_start");

        toggle(disableModules);
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertFalse(disableModules.isChecked());
        assertFalse(disableModules.isEnabled());
        assertFalse(preference(ConfigurationKeys.PREF_SOFT_REBOOT).isEnabled());
        // ERROR must not hide the explicit OFF action; the boot switch itself
        // is unavailable in every state.
        assertFalse(boot.isEnabled());
        assertTrue(preference("boot_disable").isEnabled());
        assertTrue(preference("configuration_status").getSummary().toString()
                .contains("Configuration error"));
        assertFalse(repository.getConfirmedSnapshot().isDisableModules());
    }

    @Test
    public void safeOffMigrationIsReflectedOnlyAfterTheCheckedWrite() {
        RunConfig legacy = new RunConfig(SuManager.NEXT.getPackageName(), true, false, 0, 6);
        launch(legacy, false);
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        Preference status = preference("configuration_status");

        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        assertEquals("Saving configuration...", status.getSummary());
        assertEquals(0, store.writes.size());

        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertFalse(softReboot.isChecked());
        assertFalse("unavailable switches are never re-enabled", softReboot.isEnabled());
        assertEquals("Configuration verified", status.getSummary());
        assertFalse(store.writes.get(0).isSoftReboot());
    }

    @Test
    public void failedInitialSafeOffMigrationRetriesWithoutPrematurePublication() {
        RunConfig legacy = new RunConfig(SuManager.NEXT.getPackageName(), true, false,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION - 1, 6);
        launch(legacy, false);
        store.results.add(false);
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        Preference status = preference("configuration_status");
        Preference retry = retryPreference();

        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertNull(repository.getConfirmedSnapshot());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.UNATTENDED_BOOT));
        assertNotNull(retry);
        assertTrue(isVisible(retry));
        assertTrue(retry.isEnabled());
        assertTrue(status.getSummary().toString().contains("Configuration error"));
        assertTrue(status.getSummary().toString().contains("safe-off migration"));
        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        assertEquals(1, store.writes.size());
        RunConfig cachedOnly = store.writes.get(0);
        assertFalse(cachedOnly.isSoftReboot());
        assertEquals(legacy.getGeneration() + 1, cachedOnly.getGeneration());
        assertSame(cachedOnly, store.cached);
        assertSame(legacy, store.durable);

        assertTrue(Shadows.shadowOf(retry).click());
        ShadowLooper.idleMainLooper();

        assertEquals(ConfigurationRepository.State.SAVING, repository.getState());
        assertNull(repository.getConfirmedSnapshot());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertFalse(isVisible(retry));
        assertEquals("Saving configuration...", status.getSummary());
        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        assertEquals(1, store.writes.size());
        assertSame(cachedOnly, store.cached);
        assertSame(legacy, store.durable);

        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        RunConfig confirmed = repository.getConfirmedSnapshot();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertFalse(confirmed.isSoftReboot());
        assertEquals(legacy.getGeneration() + 2, confirmed.getGeneration());
        assertEquals(2, store.writes.size());
        assertSame(confirmed, store.writes.get(1));
        assertSame(confirmed, store.cached);
        assertSame(confirmed, store.durable);
        assertFalse(isVisible(retry));
        assertEquals("Configuration verified", status.getSummary());
        assertFalse(softReboot.isChecked());
        assertFalse("unavailable switches are never re-enabled", softReboot.isEnabled());
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        assertSame(confirmed, admission.getSnapshot());
        admission.close();
    }

    @Test
    public void configurationAndCoordinatorListenersAreRemovedOnPauseAndDestroy() {
        launch(RunConfig.defaults());
        ConfigurationRepository.Listener configurationListener = ReflectionHelpers.getField(
                fragment, "mConfigurationListener");
        RunCoordinator.Listener coordinatorListener = ReflectionHelpers.getField(
                fragment, "mCoordinatorListener");
        Set<?> coordinatorListeners = ReflectionHelpers.getField(coordinator, "listeners");
        assertTrue(repository.hasListener(configurationListener));
        assertTrue(coordinatorListeners.contains(coordinatorListener));

        activityController.pause();
        assertFalse(repository.hasListener(configurationListener));
        assertFalse(coordinatorListeners.contains(coordinatorListener));

        activityController.resume();
        assertTrue(repository.hasListener(configurationListener));
        assertTrue(coordinatorListeners.contains(coordinatorListener));

        activityController.stop().destroy();
        activityController = null;
        assertFalse(repository.hasListener(configurationListener));
        assertFalse(coordinatorListeners.contains(coordinatorListener));
    }

    @Test
    public void frozenConfigurationKeepsExplicitBootOffAvailableButDisablesAllSwitches() {
        launch(RunConfig.defaults());
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        ShadowLooper.idleMainLooper();

        assertFalse(preference(ConfigurationKeys.PREF_SOFT_REBOOT).isEnabled());
        assertFalse(preference(ConfigurationKeys.PREF_DISABLE_MODULES).isEnabled());
        assertFalse(preference("boot_start").isEnabled());
        assertTrue(preference("boot_disable").isEnabled());

        admission.close();
        ShadowLooper.idleMainLooper();
        assertFalse(preference(ConfigurationKeys.PREF_SOFT_REBOOT).isEnabled());
        assertTrue(preference(ConfigurationKeys.PREF_DISABLE_MODULES).isEnabled());
        assertFalse(preference("boot_start").isEnabled());
        assertTrue(preference("boot_disable").isEnabled());
    }

    @Test
    public void startingCoordinatorKeepsBootOffAvailableAndDisablesDangerousSwitches() {
        launch(RunConfig.defaults());
        FakeLease lease = new FakeLease();
        assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        ShadowLooper.idleMainLooper();

        assertDangerousControlsDisabled();
        assertActiveChangeListenersRejected();
        assertEquals(0, store.writes.size());

        coordinatorWorker.runAll();
        ShadowLooper.idleMainLooper();
        assertDangerousControlsEnabled();
    }

    @Test
    public void activeCoordinatorNeverOpensSoftRebootConsent() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        toggle(softReboot);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());

        FakeLease lease = new FakeLease();
        assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        ShadowLooper.idleMainLooper();

        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertFalse(softReboot.isChecked());
        assertDangerousControlsDisabled();
        assertEquals(0, store.writes.size());
        coordinatorWorker.runAll();
        ShadowLooper.idleMainLooper();
        assertDangerousControlsEnabled();
    }

    @Test
    public void runningCoordinatorKeepsBootOffAvailableAndDisablesDangerousSwitches() throws Exception {
        assertActiveCoordinatorDisablesDangerousControls(false);
    }

    @Test
    public void indeterminateCoordinatorKeepsBootOffAvailableAndDisablesDangerousSwitches()
            throws Exception {
        assertActiveCoordinatorDisablesDangerousControls(true);
    }

    private void assertActiveCoordinatorDisablesDangerousControls(boolean indeterminate)
            throws Exception {
        store = new FakeStore(RunConfig.defaults());
        configExecutor = new QueueExecutor();
        repository = new ConfigurationRepository(store, configExecutor, Runnable::run);
        repository.start();
        configExecutor.runNext();
        ConfigurationRepository.setInstanceForTesting(repository);
        BlockingEngine engine = installBlockingCoordinator(indeterminate);
        launchWithExistingConfiguration();

        assertEquals(indeterminate ? RunCoordinator.State.INDETERMINATE
                : RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
        assertDangerousControlsDisabled();
        assertActiveChangeListenersRejected();
        assertEquals(0, store.writes.size());
        engine.finish();
    }

    @Test
    public void disableFromErrorConfigurationTurnsOffBootWithoutCoordinatorChanges() {
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        launch(RunConfig.defaults());
        SwitchPreference boot = preference("boot_start");
        assertFalse(boot.isChecked());
        assertFalse(boot.isEnabled());

        store.results.add(false);
        store.results.add(true);
        toggle(preference(ConfigurationKeys.PREF_DISABLE_MODULES));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, bootComponentState());
        RunCoordinator.Snapshot before = coordinator.getSnapshot();
        int writesBefore = store.writes.size();

        clickBootDisable();

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, bootComponentState());
        assertFalse(boot.isChecked());
        assertFalse(boot.isEnabled());
        assertTrue(preference("boot_disable").isEnabled());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertSame(before, coordinator.getSnapshot());
        assertEquals(writesBefore, store.writes.size());
    }

    @Test
    public void enablingFromErrorConfigurationIsStillRejected() {
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        launch(RunConfig.defaults());
        store.results.add(false);
        store.results.add(true);
        toggle(preference(ConfigurationKeys.PREF_DISABLE_MODULES));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());

        Object accepted = ReflectionHelpers.callInstanceMethod(preference("boot_start"),
                "callChangeListener", ReflectionHelpers.ClassParameter.from(Object.class, true));

        assertEquals(Boolean.FALSE, accepted);
        assertFalse(preference("boot_start").isEnabled());
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, bootComponentState());
    }

    @Test
    public void disableDuringActiveSnapshotsTurnsOffBootAndStillRejectsEnabling() {
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        launch(RunConfig.defaults());
        SwitchPreference boot = preference("boot_start");

        for (RunCoordinator.State state : new RunCoordinator.State[] {
                RunCoordinator.State.STARTING, RunCoordinator.State.RUNNING,
                RunCoordinator.State.INDETERMINATE}) {
            RunCoordinator.Snapshot snapshot = new RunCoordinator.Snapshot(state,
                    "op-" + state, RunConfig.ExecutionMode.MANUAL, 0L, null, null, null,
                    new ArrayList<>());
            ReflectionHelpers.setField(coordinator, "snapshot", snapshot);
            ReflectionHelpers.callInstanceMethod(fragment, "renderConfirmedConfiguration");
            setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

            assertFalse("boot switch is unavailable in every state", boot.isEnabled());
            assertTrue("explicit OFF stays available", preference("boot_disable").isEnabled());
            Object enableAccepted = ReflectionHelpers.callInstanceMethod(boot,
                    "callChangeListener",
                    ReflectionHelpers.ClassParameter.from(Object.class, true));
            assertEquals(state + " must reject ON", Boolean.FALSE, enableAccepted);
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, bootComponentState());

            clickBootDisable();

            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, bootComponentState());
            assertFalse(boot.isChecked());
            assertSame(snapshot, coordinator.getSnapshot());
        }
    }

    @Test
    public void disableDoesNotCancelOrCloseAnActiveOperation() throws Exception {
        store = new FakeStore(RunConfig.defaults());
        configExecutor = new QueueExecutor();
        repository = new ConfigurationRepository(store, configExecutor, Runnable::run);
        repository.start();
        configExecutor.runNext();
        ConfigurationRepository.setInstanceForTesting(repository);
        FakeLease lease = new FakeLease();
        BlockingEngine engine = installBlockingCoordinator(false, lease);
        launchWithExistingConfiguration();
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        SwitchPreference boot = preference("boot_start");
        assertFalse(boot.isChecked());
        RunCoordinator.Snapshot before = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.RUNNING, before.getState());

        clickBootDisable();

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, bootComponentState());
        assertSame(before, coordinator.getSnapshot());
        assertEquals(0, lease.closeCount.get());
        engine.finish();
    }

    @Test
    public void failedComponentSettingSurfacesErrorAndKeepsPreviouslyAppliedState() {
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        launch(RunConfig.defaults());
        SwitchPreference boot = preference("boot_start");
        assertFalse(boot.isChecked());
        FakeBootComponents controller = new FakeBootComponents(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        controller.failure = new IllegalStateException("package manager rejected");
        ReflectionHelpers.setField(fragment, "mBootComponents", controller);

        clickBootDisable();

        assertFalse(boot.isChecked());
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, bootComponentState());
        String summary = boot.getSummary().toString();
        assertTrue(summary, summary.contains("Could not change Start at Boot"));
        assertTrue(summary, summary.contains("failed"));
        assertTrue(summary, summary.contains("package manager rejected"));
        assertTrue(preference("configuration_status").getSummary().toString()
                .contains("package manager rejected"));
        assertEquals(1, controller.setCalls);
    }

    @Test
    public void componentThatIgnoresDisableIsNotReportedAsApplied() {
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        launch(RunConfig.defaults());
        SwitchPreference boot = preference("boot_start");
        assertFalse(boot.isChecked());
        FakeBootComponents controller = new FakeBootComponents(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        controller.stuck = true;
        ReflectionHelpers.setField(fragment, "mBootComponents", controller);

        clickBootDisable();

        assertFalse(boot.isChecked());
        assertTrue(boot.getSummary().toString().contains("Could not change Start at Boot"));
        assertEquals(1, controller.setCalls);
    }

    @Test
    public void currentAcknowledgedConfigurationIsNeverShownAsEnabled() {
        launch(RunConfig.defaults().withSoftReboot(true));
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);

        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(0, store.writes.size());
    }

    @Test
    @Config(sdk = 32)
    public void minimumApiSettingsNeverOffersSoftReboot() {
        launch(RunConfig.defaults().withSoftReboot(true));
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);

        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        assertEquals(0, store.writes.size());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void softRebootEnableIsRejectedWithoutWriteWhenStoreWouldFail() {
        assertSoftRebootEnableRefused(false);
    }

    @Test
    public void softRebootEnableIsRejectedWithoutWriteWhenStoreWouldThrow() {
        assertSoftRebootEnableRefused(true);
    }

    @Test
    public void frozenConfigurationCannotEnableSoftReboot() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        ShadowLooper.idleMainLooper();

        Object accepted = ReflectionHelpers.callInstanceMethod(softReboot, "callChangeListener",
                ReflectionHelpers.ClassParameter.from(Object.class, true));

        assertEquals(Boolean.FALSE, accepted);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(0, store.writes.size());
        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        admission.close();
        ShadowLooper.idleMainLooper();
        assertFalse(softReboot.isEnabled());
    }

    @Test
    public void staleSoftRebootEnableCannotWriteAfterInterveningUpdate() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        Object accepted = ReflectionHelpers.callInstanceMethod(softReboot, "callChangeListener",
                ReflectionHelpers.ClassParameter.from(Object.class, true));

        assertEquals(Boolean.FALSE, accepted);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(1, store.writes.size());
        assertTrue(store.writes.get(0).isDisableModules());
        assertFalse(store.writes.get(0).isSoftReboot());
        assertFalse(softReboot.isChecked());
    }

    @Test
    public void retryIsVisibleOnlyDuringErrorAndReloadsDurableConfiguration() {
        launch(RunConfig.defaults());
        Preference retry = retryPreference();
        assertNotNull(retry);
        assertFalse(isVisible(retry));

        store.results.add(false);
        store.results.add(true);
        toggle(preference(ConfigurationKeys.PREF_DISABLE_MODULES));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        assertTrue(isVisible(retry));
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        RunConfig confirmed = repository.getConfirmedSnapshot();
        int writesBeforeReload = store.writes.size();

        assertTrue(Shadows.shadowOf(retry).click());
        ShadowLooper.idleMainLooper();

        assertEquals(ConfigurationRepository.State.SAVING, repository.getState());
        assertFalse(isVisible(retry));
        assertEquals("Saving configuration...",
                preference("configuration_status").getSummary());
        assertEquals(writesBeforeReload, store.writes.size());
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertFalse(isVisible(retry));
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        RunConfig reloaded = repository.getConfirmedSnapshot();
        assertNotSame(confirmed, reloaded);
        assertEquals(confirmed.getGeneration() + 1, reloaded.getGeneration());
        assertEquals(writesBeforeReload + 1, store.writes.size());
        assertSame(reloaded, store.writes.get(writesBeforeReload));
        assertSame(reloaded, store.cached);
        assertSame(reloaded, store.durable);
        assertTrue(preference(ConfigurationKeys.PREF_DISABLE_MODULES).isEnabled());
    }

    @Test
    public void startingCoordinatorKeepsErrorRetryVisibleButDisablesReloadUntilTerminal() {
        RunConfig initial = RunConfig.defaults();
        store = new FakeStore(initial);
        configExecutor = new QueueExecutor();
        repository = new ConfigurationRepository(store, configExecutor, Runnable::run);
        repository.start();
        configExecutor.runNext();
        ConfigurationRepository.setInstanceForTesting(repository);
        installIdleCoordinator();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertSame(initial, repository.getConfirmedSnapshot());

        store.results.add(false);
        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertSame(initial, repository.getConfirmedSnapshot());
        assertSame(initial, store.durable);
        int writesAfterFailedWrite = store.writes.size();

        FakeLease lease = new FakeLease();
        assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        assertEquals(RunCoordinator.State.STARTING, coordinator.getSnapshot().getState());
        assertFalse(repository.isFrozen());
        assertFalse(repository.isWritePending());
        launchWithExistingConfiguration();
        Preference retry = retryPreference();

        assertTrue(isVisible(retry));
        assertFalse(retry.isEnabled());
        assertNotNull(retry.getOnPreferenceClickListener());
        Shadows.shadowOf(retry).click();
        assertTrue(retry.getOnPreferenceClickListener().onPreferenceClick(retry));
        ShadowLooper.idleMainLooper();

        assertEquals(RunCoordinator.State.STARTING, coordinator.getSnapshot().getState());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertSame(initial, repository.getConfirmedSnapshot());
        assertSame(initial, store.durable);
        assertEquals(writesAfterFailedWrite, store.writes.size());
        assertEquals(0, lease.closeCount.get());

        coordinatorWorker.runAll();
        ShadowLooper.idleMainLooper();

        RunCoordinator.Snapshot terminal = coordinator.getSnapshot();
        assertEquals(RunCoordinator.State.TERMINAL, terminal.getState());
        assertEquals(RunResult.Type.PRECHECK_FAILED, terminal.getResult().getType());
        assertTrue(terminal.getReason().contains("configuration write failed"));
        assertEquals(1, lease.closeCount.get());
        assertFalse(repository.isFrozen());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertSame(initial, repository.getConfirmedSnapshot());
        assertSame(initial, store.durable);
        assertEquals(writesAfterFailedWrite, store.writes.size());
        assertTrue(isVisible(retry));
        assertTrue(retry.isEnabled());
    }

    private void assertSoftRebootEnableRefused(boolean throwFailure) {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        RunConfig confirmed = repository.getConfirmedSnapshot();
        if (throwFailure) {
            store.failures.add(new IllegalStateException("disk unavailable"));
        } else {
            store.results.add(false);
            store.results.add(true);
        }

        Object accepted = ReflectionHelpers.callInstanceMethod(softReboot, "callChangeListener",
                ReflectionHelpers.ClassParameter.from(Object.class, true));

        assertEquals(Boolean.FALSE, accepted);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(0, store.writes.size());
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertSame(confirmed, repository.getConfirmedSnapshot());
        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
    }

    private void launch(RunConfig initial) {
        launch(initial, true);
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
        installIdleCoordinator();
        activityController = Robolectric.buildActivity(SettingsActivity.class).setup();
        fragment = (SettingsFragment) activityController.get().getFragmentManager()
                .findFragmentById(R.id.settingsContainer);
    }

    private void installIdleCoordinator() {
        coordinatorWorker = new QueueExecutor();
        coordinator = new RunCoordinator(repository, coordinatorWorker, Runnable::run,
                (context, admission, mode, operationId, events, reporter) -> NativeResultMapper.map(0), 0L);
        RunCoordinator.setInstanceForTesting(coordinator);
    }

    private void launchWithExistingConfiguration() {
        activityController = Robolectric.buildActivity(SettingsActivity.class).setup();
        fragment = (SettingsFragment) activityController.get().getFragmentManager()
                .findFragmentById(R.id.settingsContainer);
    }

    private BlockingEngine installBlockingCoordinator(boolean indeterminate) throws Exception {
        return installBlockingCoordinator(indeterminate, new FakeLease());
    }

    private BlockingEngine installBlockingCoordinator(boolean indeterminate, FakeLease lease)
            throws Exception {
        BlockingEngine engine = new BlockingEngine(indeterminate);
        blockingEngines.add(engine);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        workers.add(worker);
        coordinator = new RunCoordinator(repository, worker,
                ConfigurationRepository.newMainExecutor(), engine, 0L);
        RunCoordinator.setInstanceForTesting(coordinator);
        assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
        assertTrue(engine.entered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        ShadowLooper.idleMainLooper();
        return engine;
    }

    private void assertDangerousControlsDisabled() {
        // The boot_start switch is unavailable even while another run is active;
        // only the separate explicit OFF action stays reachable.
        assertFalse(preference("boot_start").isEnabled());
        assertTrue(preference("boot_disable").isEnabled());
        assertFalse(preference(ConfigurationKeys.PREF_SOFT_REBOOT).isEnabled());
        assertFalse(preference(ConfigurationKeys.PREF_DISABLE_MODULES).isEnabled());
    }

    private void assertActiveChangeListenersRejected() {
        int bootStateBefore = fragment.getActivity().getPackageManager()
                .getComponentEnabledSetting(new ComponentName(fragment.getActivity(), BootReceiver.class));
        Object bootAccepted = ReflectionHelpers.callInstanceMethod(preference("boot_start"),
                "callChangeListener", ReflectionHelpers.ClassParameter.from(Object.class, true));
        Object softRebootAccepted = ReflectionHelpers.callInstanceMethod(
                preference(ConfigurationKeys.PREF_SOFT_REBOOT), "callChangeListener",
                ReflectionHelpers.ClassParameter.from(Object.class, true));
        Object disableModulesAccepted = ReflectionHelpers.callInstanceMethod(
                preference(ConfigurationKeys.PREF_DISABLE_MODULES), "callChangeListener",
                ReflectionHelpers.ClassParameter.from(Object.class, true));

        assertEquals(Boolean.FALSE, bootAccepted);
        assertEquals(Boolean.FALSE, softRebootAccepted);
        assertEquals(Boolean.FALSE, disableModulesAccepted);
        assertEquals(bootStateBefore, fragment.getActivity().getPackageManager()
                .getComponentEnabledSetting(new ComponentName(fragment.getActivity(), BootReceiver.class)));
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(0, store.writes.size());
        assertFalse(repository.isWritePending());
    }

    private void assertDangerousControlsEnabled() {
        assertFalse(preference("boot_start").isEnabled());
        assertTrue(preference("boot_disable").isEnabled());
        assertFalse(preference(ConfigurationKeys.PREF_SOFT_REBOOT).isEnabled());
        assertTrue(preference(ConfigurationKeys.PREF_DISABLE_MODULES).isEnabled());
    }

    @SuppressWarnings("unchecked")
    private <T extends Preference> T preference(String key) {
        return (T) fragment.findPreference(key);
    }

    private void toggle(SwitchPreference preference) {
        ReflectionHelpers.callInstanceMethod(preference, "onClick");
    }

    /** The only boot component action left in the UI: explicit safe OFF. */
    private void clickBootDisable() {
        assertTrue(Shadows.shadowOf(preference("boot_disable")).click());
    }

    private Preference retryPreference() {
        return ReflectionHelpers.getField(fragment, "mRetryPreference");
    }

    private boolean isVisible(Preference preference) {
        return preference != null
                && fragment.getPreferenceScreen().findPreference(preference.getKey()) != null;
    }

    private static void setBootComponent(int state) {
        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getPackageManager().setComponentEnabledSetting(
                new ComponentName(ctx, BootReceiver.class), state, PackageManager.DONT_KILL_APP);
    }

    private static int bootComponentState() {
        Context ctx = RuntimeEnvironment.getApplication();
        return ctx.getPackageManager().getComponentEnabledSetting(
                new ComponentName(ctx, BootReceiver.class));
    }

    /** Injectable failing/stuck PackageManager seam for the boot toggle. */
    private static final class FakeBootComponents
            implements SettingsFragment.BootComponentController {
        int state;
        boolean stuck;
        RuntimeException failure;
        int setCalls;

        FakeBootComponents(int state) {
            this.state = state;
        }

        @Override
        public int read(Context context, ComponentName component) {
            return state;
        }

        @Override
        public int set(Context context, ComponentName component, int newState) {
            setCalls++;
            if (failure != null) {
                throw failure;
            }
            if (!stuck) {
                state = newState;
            }
            return state;
        }
    }

    private static final class FakeLease implements ResourceLease {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class BlockingEngine implements RunCoordinator.Engine {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean indeterminate;

        BlockingEngine(boolean indeterminate) {
            this.indeterminate = indeterminate;
        }

        @Override
        public RunResult run(Context context, ConfigurationRepository.RunAdmission admission,
                             RunConfig.ExecutionMode mode, String operationId,
                             RunEventListener events, IReporter reporter) {
            if (indeterminate) {
                events.onTimeout();
            }
            entered.countDown();
            try {
                release.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NativeResultMapper.map(0);
        }

        void finish() {
            release.countDown();
        }
    }

    private static final class FakeStore implements ConfigurationStore {
        RunConfig cached;
        RunConfig durable;
        final List<RunConfig> writes = new ArrayList<>();
        final Queue<Boolean> results = new ArrayDeque<>();
        final Queue<RuntimeException> failures = new ArrayDeque<>();

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
            if (!failures.isEmpty()) {
                throw failures.remove();
            }
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

        void runAll() {
            while (!commands.isEmpty()) {
                runNext();
            }
        }
    }
}
