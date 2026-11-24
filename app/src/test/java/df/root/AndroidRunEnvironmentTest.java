// Modified 2026-11-24 (DFRoot GZIF): wire Process/package observations and fail-closed property reads on API 37. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): verify environment selection comes only from confirmed snapshots. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): pass explicit confirmed snapshots after removing the repository-coupled factory. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowProcess;
import org.robolectric.shadows.ShadowSystemProperties;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class AndroidRunEnvironmentTest {
    private static final String TARGET_FINGERPRINT =
            "samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys";
    private static final String TARGET_KERNEL =
            "5.15.197-android13-8-34343818-abS9180ZHU8GZIF";
    private static final int OWNER_APP_UID = Process.FIRST_APPLICATION_UID + 42;

    private final Context context = RuntimeEnvironment.getApplication();
    private String previousKernelVersion;

    @After
    public void restoreKernelVersion() {
        ConfigurationRepository.resetForTesting();
        ShadowProcess.reset();
        if (previousKernelVersion == null) {
            System.clearProperty("os.version");
        } else {
            System.setProperty("os.version", previousKernelVersion);
        }
        ShadowSystemProperties.reset();
    }

    @Test
    public void confirmedRepositorySelectionTracksTheConfirmedPackage() {
        installConfiguration(RunConfig.defaults());
        assertNull(AndroidRunEnvironment.forConfig(context,
                ConfigurationRepository.get(context).getConfirmedSnapshot()).getSelectedManagerPackage());

        installConfiguration(new RunConfig(SuManager.CLASSIC.getPackageName(),
                false, false, 0, 1));
        assertEquals(SuManager.CLASSIC.getPackageName(),
                AndroidRunEnvironment.forConfig(context,
                        ConfigurationRepository.get(context).getConfirmedSnapshot())
                        .getSelectedManagerPackage());

        installConfiguration(RunConfig.defaults());
        assertNull(AndroidRunEnvironment.forConfig(context,
                ConfigurationRepository.get(context).getConfirmedSnapshot()).getSelectedManagerPackage());
    }

    @Test
    public void explicitSelectionAndConfigFactoriesDoNotDependOnPreferences() {
        installConfiguration(new RunConfig(SuManager.CLASSIC.getPackageName(),
                false, false, 0, 1));
        RunConfig next = new RunConfig(SuManager.NEXT.getPackageName(), false, false, 0, 2);

        AndroidRunEnvironment environment = AndroidRunEnvironment.forSelection(
                context, SuManager.NEXT.getPackageName());
        AndroidRunEnvironment configured = AndroidRunEnvironment.forConfig(context, next);

        assertEquals(SuManager.NEXT.getPackageName(), environment.getSelectedManagerPackage());
        assertEquals(SuManager.NEXT.getPackageName(), configured.getSelectedManagerPackage());
        assertEquals(SuManager.CLASSIC.getPackageName(),
                AndroidRunEnvironment.forConfig(context,
                        ConfigurationRepository.get(context).getConfirmedSnapshot())
                        .getSelectedManagerPackage());
    }

    @Test
    public void credentialProtectedPreferencesCannotOverrideConfirmedDeviceProtectedConfig() {
        installConfiguration(new RunConfig(SuManager.NEXT.getPackageName(),
                false, false, 0, 3));
        context.getSharedPreferences(ConfigurationKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(ConfigurationKeys.PREF_SU_MANAGER,
                        SuManager.CLASSIC.getPackageName()).commit();

        assertEquals(SuManager.NEXT.getPackageName(),
                AndroidRunEnvironment.forConfig(context,
                        ConfigurationRepository.get(context).getConfirmedSnapshot())
                        .getSelectedManagerPackage());
    }

    @Test
    public void ownerProcessIdentityIsAcceptedByTheEnvironment() {
        ShadowProcess.setUid(OWNER_APP_UID);

        assertTrue(AndroidRunEnvironment.forSelection(context,
                SuManager.CLASSIC.getPackageName()).isOwnerUser());
    }

    @Test
    public void secondaryProcessIdentityIsRejectedByTheEnvironment() {
        ShadowProcess.setUid(10 * 100_000 + OWNER_APP_UID);

        assertFalse(AndroidRunEnvironment.forSelection(context,
                SuManager.CLASSIC.getPackageName()).isOwnerUser());
    }

    @Test
    public void managerInstallationQueriesTheExactSuppliedPackage() {
        installPackage(SuManager.CLASSIC.getPackageName());
        AndroidRunEnvironment environment = AndroidRunEnvironment.forSelection(
                context, SuManager.NEXT.getPackageName());

        assertTrue(environment.isManagerInstalled(SuManager.CLASSIC.getPackageName()));
        assertFalse(environment.isManagerInstalled(SuManager.NEXT.getPackageName()));
        assertFalse(environment.isManagerInstalled("me.weishu.kernelsu.extra"));
        assertFalse(environment.isManagerInstalled(null));
    }

    @Test
    public void absentManagerIsNotInstalled() {
        AndroidRunEnvironment environment = AndroidRunEnvironment.forSelection(
                context, SuManager.CLASSIC.getPackageName());

        assertFalse(environment.isManagerInstalled(SuManager.CLASSIC.getPackageName()));
    }

    @Test
    public void hiddenManagerIsNotInstalled() {
        String hiddenPackage = SuManager.NEXT.getPackageName();
        installPackage(hiddenPackage);
        ShadowPackageManager packageManager = Shadows.shadowOf(context.getPackageManager());
        assertTrue((Boolean) ReflectionHelpers.callInstanceMethod(packageManager,
                "setApplicationHiddenSettingAsUser",
                ClassParameter.from(String.class, hiddenPackage),
                ClassParameter.from(boolean.class, true),
                ClassParameter.from(UserHandle.class,
                        UserHandle.getUserHandleForUid(Process.myUid()))));

        AndroidRunEnvironment environment = AndroidRunEnvironment.forSelection(context, hiddenPackage);

        assertFalse(environment.isManagerInstalled(hiddenPackage));
    }

    @Test
    public void everyFirmwareInputComesFromTheMappedAndroidBuildField() {
        ShadowBuild.setDevice("dm3q");
        ShadowBuild.setModel("SM-S9180");
        ShadowBuild.setId("CP2A.260605.016");
        ShadowBuild.setVersionSecurityPatch("2026-10-05");
        ShadowBuild.setFingerprint(TARGET_FINGERPRINT);
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 37);
        previousKernelVersion = System.getProperty("os.version");
        System.setProperty("os.version", TARGET_KERNEL);

        FirmwareGate.Inputs firmware = AndroidRunEnvironment.forSelection(
                context, SuManager.CLASSIC.getPackageName()).getFirmware();

        assertEquals("dm3q", firmware.getDevice());
        assertEquals("SM-S9180", firmware.getModel());
        assertEquals("CP2A.260605.016", firmware.getBuildId());
        assertEquals(37, firmware.getSdkInt());
        assertEquals("2026-10-05", firmware.getSecurityPatch());
        assertEquals(TARGET_FINGERPRINT, firmware.getFingerprint());
        assertEquals(TARGET_KERNEL, firmware.getKernelVersion());
        assertTrue(FirmwareGate.validate(firmware).isSupported());
    }

    @Test
    public void systemPropertiesOffEmptyAndNormalValuesAreMappedExactly() {
        AndroidRunEnvironment environment = AndroidRunEnvironment.forSelection(context, null);
        String[] values = {"S", "off", "", "I"};
        for (String value : values) {
            ShadowSystemProperties.override("persist.log.tag", value);
            assertEquals(value, environment.getLogTag());
        }
    }

    @Test
    public void propertyReaderFailureFailsClosed() {
        AndroidRunEnvironment environment = new AndroidRunEnvironment(context, null,
                (key, defaultValue) -> {
                    throw new SecurityException("property read denied");
                });

        SecurityException error = assertThrows(SecurityException.class, environment::getLogTag);
        assertEquals("property read denied", error.getMessage());

        AndroidRunEnvironment nullReader = new AndroidRunEnvironment(context, null,
                (key, defaultValue) -> null);
        assertThrows(IllegalStateException.class, nullReader::getLogTag);
    }

    private void installPackage(String packageName) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        Shadows.shadowOf(context.getPackageManager()).installPackage(packageInfo);
    }

    private void installConfiguration(RunConfig config) {
        ConfigurationRepository repository = new ConfigurationRepository(
                new ConfigurationStore() {
                    private RunConfig cached = config;
                    private RunConfig durable = config;

                    @Override
                    public RunConfig load() {
                        return cached;
                    }

                    @Override
                    public boolean write(RunConfig updated) {
                        cached = updated;
                        durable = updated;
                        return true;
                    }
                }, Runnable::run, Runnable::run);
        repository.start();
        ConfigurationRepository.setInstanceForTesting(repository);
    }

    @Test
    public void authoritativeFirmwareFixtureRejectsANearMatch() {
        FirmwareGate.Inputs nearMatch = new FirmwareGate.Inputs(
                "dm3q", "SM-S9180", "CP2A.260605.016", 37, "2026-10-05",
                TARGET_FINGERPRINT + "x", TARGET_KERNEL);

        assertFalse(FirmwareGate.validate(nearMatch).isSupported());
    }
}
