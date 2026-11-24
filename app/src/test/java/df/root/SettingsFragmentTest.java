// Modified 2026-11-24 (DFRoot GZIF): verify modal consent and durable settings-state reflection. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.os.Looper;
import android.preference.Preference;
import android.preference.SwitchPreference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
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
import java.util.concurrent.Executor;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class SettingsFragmentTest {
    private ActivityController<SettingsActivity> activityController;
    private SettingsFragment fragment;
    private FakeStore store;
    private QueueExecutor configExecutor;
    private ConfigurationRepository repository;

    @Before
    public void resetConfiguration() {
        ConfigurationRepository.resetForTesting();
        ShadowAlertDialog.reset();
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
    public void enablingSoftRebootRequiresBlockingModalBeforeAnyWrite() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);

        toggle(softReboot);

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertFalse(Shadows.shadowOf(dialog).isCancelable());
        assertTrue(Shadows.shadowOf(dialog).getMessage().toString().contains("manual runs only"));
        assertFalse(softReboot.isChecked());
        assertEquals(0, store.writes.size());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertFalse(softReboot.isEnabled());
        assertEquals("Saving configuration...",
                preference("configuration_status").getSummary());
        assertEquals(0, store.writes.size());
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertTrue(softReboot.isChecked());
        assertTrue(softReboot.isEnabled());
        RunConfig durable = store.writes.get(0);
        assertTrue(durable.isSoftReboot());
        assertEquals(ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION,
                durable.getSoftRebootAcknowledgementVersion());
    }

    @Test
    public void cancellingAcknowledgementDoesNotPersistOrToggleTheSwitch() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);

        toggle(softReboot);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertFalse(softReboot.isChecked());
        assertTrue(softReboot.isEnabled());
        assertEquals(0, store.writes.size());
    }

    @Test
    public void disablingSoftRebootWaitsForDurableConfirmation() {
        RunConfig enabled = RunConfig.defaults().withSoftReboot(true);
        launch(enabled);
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        assertTrue(softReboot.isChecked());

        toggle(softReboot);
        assertTrue(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertFalse(softReboot.isChecked());
        assertFalse(store.writes.get(0).isSoftReboot());
    }

    @Test
    public void failedWriteShowsErrorDisablesDangerousSwitchesAndKeepsBootToggleSeparate() {
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
        assertTrue(boot.isEnabled());
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
        assertTrue(softReboot.isEnabled());
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
        assertTrue(softReboot.isEnabled());
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        assertSame(confirmed, admission.getSnapshot());
        admission.close();
    }

    @Test
    public void configurationListenerIsRemovedOnPauseAndDestroy() {
        launch(RunConfig.defaults());
        ConfigurationRepository.Listener listener = ReflectionHelpers.getField(
                fragment, "mConfigurationListener");
        assertTrue(repository.hasListener(listener));

        activityController.pause();
        assertFalse(repository.hasListener(listener));

        activityController.resume();
        assertTrue(repository.hasListener(listener));

        activityController.stop().destroy();
        activityController = null;
        assertFalse(repository.hasListener(listener));
    }

    @Test
    public void frozenConfigurationDisablesDangerousSwitchesUntilReleased() {
        launch(RunConfig.defaults());
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        ShadowLooper.idleMainLooper();

        assertFalse(preference(ConfigurationKeys.PREF_SOFT_REBOOT).isEnabled());
        assertFalse(preference(ConfigurationKeys.PREF_DISABLE_MODULES).isEnabled());
        assertTrue(preference("boot_start").isEnabled());

        admission.close();
        ShadowLooper.idleMainLooper();
        assertTrue(preference(ConfigurationKeys.PREF_SOFT_REBOOT).isEnabled());
        assertTrue(preference(ConfigurationKeys.PREF_DISABLE_MODULES).isEnabled());
    }

    @Test
    public void currentAcknowledgedConfigurationIsShownWithoutAnotherConsentDialog() {
        launch(RunConfig.defaults().withSoftReboot(true));

        assertTrue(((SwitchPreference) preference(ConfigurationKeys.PREF_SOFT_REBOOT)).isChecked());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(0, store.writes.size());
    }

    @Test
    @Config(sdk = 32)
    public void minimumApiSettingsAdapterReflectsAcknowledgedConfigurationWithoutWriting() {
        launch(RunConfig.defaults().withSoftReboot(true));

        assertTrue(((SwitchPreference) preference(ConfigurationKeys.PREF_SOFT_REBOOT)).isChecked());
        assertTrue(preference(ConfigurationKeys.PREF_SOFT_REBOOT).isEnabled());
        assertEquals(0, store.writes.size());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void failedSoftRebootEnableLeavesUncheckedDisabledAndError() {
        assertSoftRebootEnableFailure(false);
    }

    @Test
    public void throwingSoftRebootEnableLeavesUncheckedDisabledAndError() {
        assertSoftRebootEnableFailure(true);
    }

    @Test
    public void confirmingConsentAfterFreezePerformsNoWrite() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        toggle(softReboot);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        ShadowLooper.idleMainLooper();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(0, store.writes.size());
        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        admission.close();
        ShadowLooper.idleMainLooper();
        assertTrue(softReboot.isEnabled());
    }

    @Test
    public void confirmingConsentAfterInterveningWritePerformsNoSecondWrite() {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        toggle(softReboot);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(repository.setDisableModules(true));
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

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

    private void assertSoftRebootEnableFailure(boolean throwFailure) {
        launch(RunConfig.defaults());
        SwitchPreference softReboot = preference(ConfigurationKeys.PREF_SOFT_REBOOT);
        RunConfig confirmed = repository.getConfirmedSnapshot();
        if (throwFailure) {
            store.failures.add(new IllegalStateException("disk unavailable"));
        } else {
            store.results.add(false);
            store.results.add(true);
        }
        toggle(softReboot);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        configExecutor.runNext();
        ShadowLooper.idleMainLooper();

        assertFalse(softReboot.isChecked());
        assertFalse(softReboot.isEnabled());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertSame(confirmed, repository.getConfirmedSnapshot());
        assertTrue(isVisible(retryPreference()));
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
        activityController = Robolectric.buildActivity(SettingsActivity.class).setup();
        fragment = (SettingsFragment) activityController.get().getFragmentManager()
                .findFragmentById(R.id.settingsContainer);
    }

    @SuppressWarnings("unchecked")
    private <T extends Preference> T preference(String key) {
        return (T) fragment.findPreference(key);
    }

    private void toggle(SwitchPreference preference) {
        ReflectionHelpers.callInstanceMethod(preference, "onClick");
    }

    private Preference retryPreference() {
        return ReflectionHelpers.getField(fragment, "mRetryPreference");
    }

    private boolean isVisible(Preference preference) {
        return preference != null
                && fragment.getPreferenceScreen().findPreference(preference.getKey()) != null;
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
    }
}
