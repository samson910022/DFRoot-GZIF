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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class BootConfigurationPolicyTest {
    private Context context;
    private Context deContext;
    private ComponentName bootReceiver;

    @Before
    public void setUp() {
        ConfigurationRepository.resetForTesting();
        context = RuntimeEnvironment.getApplication();
        deContext = context.createDeviceProtectedStorageContext();
        bootReceiver = new ComponentName(context, BootReceiver.class);
        deContext.getSharedPreferences(ConfigurationKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getPackageManager().setComponentEnabledSetting(bootReceiver,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        ConfigurationRepository.resetForTesting();
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
