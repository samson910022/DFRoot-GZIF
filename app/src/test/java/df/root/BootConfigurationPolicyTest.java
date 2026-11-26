// Modified 2026-11-24 (DFRoot GZIF): verify legacy upgrades and boot-enabled combinations fail closed. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class BootConfigurationPolicyTest {
    private Context context;
    private Context deContext;
    private ComponentName bootReceiver;
    private Path marker;

    @Before
    public void setUp() throws Exception {
        marker = Files.createTempFile("dfroot-boot-integrity", ".marker");
        Files.deleteIfExists(marker);
        PreferenceIntegrity.resetForTest();
        PreferenceIntegrity.init(marker.toFile());
        ConfigurationRepository.resetForTesting();
        context = RuntimeEnvironment.getApplication();
        deContext = context.createDeviceProtectedStorageContext();
        bootReceiver = new ComponentName(context, BootReceiver.class);
        deContext.getSharedPreferences(ConfigurationKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After
    public void tearDown() throws Exception {
        context.getPackageManager().setComponentEnabledSetting(bootReceiver,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        ConfigurationRepository.resetForTesting();
        PreferenceIntegrity.resetForTest();
        Files.deleteIfExists(marker);
    }

    @Test
    public void legacyEnabledSoftRebootWithBootEnabledMigratesOffBeforeBootCouldRun() {
        enableBoot();
        seed(new RunConfig(SuManager.NEXT.getPackageName(), true, false, 0, 4));

        ConfigurationRepository repository = loadSynchronously();
        RunConfig confirmed = repository.getConfirmedSnapshot();

        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertFalse(confirmed.isSoftReboot());
        assertTrue(confirmed.isUnattendedBootAllowed());
        assertTrue(enabledBootComponent());
        assertEquals(5, confirmed.getGeneration());
    }

    @Test
    public void currentManualConsentStillRejectsUnattendedExecution() {
        enableBoot();
        seed(new RunConfig(SuManager.NEXT.getPackageName(), true, false,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 8));

        RunConfig confirmed = loadSynchronously().getConfirmedSnapshot();

        assertTrue(confirmed.isSoftRebootAllowedFor(RunConfig.ExecutionMode.MANUAL));
        assertFalse(confirmed.isSoftRebootAllowedFor(RunConfig.ExecutionMode.UNATTENDED_BOOT));
        assertFalse(confirmed.isUnattendedBootAllowed());
    }

    @Test
    public void safeOffConfigurationAllowsUnattendedExecutionPolicy() {
        enableBoot();
        seed(RunConfig.defaults().withManagerPackage(SuManager.NEXT.getPackageName()));

        RunConfig confirmed = loadSynchronously().getConfirmedSnapshot();

        assertTrue(confirmed.isUnattendedBootAllowed());
        assertFalse(confirmed.isSoftRebootAllowedFor(RunConfig.ExecutionMode.UNATTENDED_BOOT));
    }

    /**
     * Code-level availability guard: no production writer may enable the boot
     * component and the coordinator's production gate must not derive authority
     * from the component setting. Fails if an enable path is reintroduced.
     */
    @Test
    public void productionSourcesContainNoBootEnablePath() throws Exception {
        for (String name : new String[] {"SettingsFragment.java", "AndroidBootGate.java"}) {
            File source = TestFixtures.repositoryRoot()
                    .resolve("app/src/main/java/df/root/" + name).toFile();
            String code = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/|//[^\n]*", "");
            assertFalse(name + " must never write COMPONENT_ENABLED_STATE_ENABLED",
                    code.contains("COMPONENT_ENABLED_STATE_ENABLED"));
        }
        File coordinator = TestFixtures.repositoryRoot()
                .resolve("app/src/main/java/df/root/RunCoordinator.java").toFile();
        String code = new String(Files.readAllBytes(coordinator.toPath()), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/|//[^\n]*", "");
        assertFalse("production authorization must not read the component setting",
                code.contains("getComponentEnabledSetting"));
        assertTrue("production authorization must be an unconditional refusal",
                code.contains("PRODUCTION_UNATTENDED_REFUSAL = () -> false"));
    }

    private void seed(RunConfig config) {
        assertTrue(new SharedPreferencesConfigurationStore(deContext).write(config));
    }

    private ConfigurationRepository loadSynchronously() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new SharedPreferencesConfigurationStore(deContext), Runnable::run, Runnable::run);
        repository.start();
        return repository;
    }

    private void enableBoot() {
        context.getPackageManager().setComponentEnabledSetting(bootReceiver,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }

    private boolean enabledBootComponent() {
        return context.getPackageManager().getComponentEnabledSetting(bootReceiver)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }
}
