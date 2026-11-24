// Modified 2026-11-24 (DFRoot GZIF): verify configuration lives in Device Protected Storage, not CE preferences. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class DeviceProtectedConfigurationStoreTest {
    private Context context;
    private Context deContext;
    private SharedPreferences cePreferences;
    private SharedPreferences dePreferences;

    @Before
    public void clearPreferences() {
        context = RuntimeEnvironment.getApplication();
        deContext = context.createDeviceProtectedStorageContext();
        cePreferences = context.getSharedPreferences(ConfigurationKeys.PREFS_NAME, Context.MODE_PRIVATE);
        dePreferences = deContext.getSharedPreferences(ConfigurationKeys.PREFS_NAME, Context.MODE_PRIVATE);
        cePreferences.edit().clear().commit();
        dePreferences.edit().clear().commit();
        ConfigurationRepository.resetForTesting();
    }

    @After
    public void resetRepository() {
        ConfigurationRepository.resetForTesting();
    }

    @Test
    public void credentialProtectedLegacyValuesAreInvisibleToTheDeStore() {
        cePreferences.edit()
                .putString(ConfigurationKeys.PREF_SU_MANAGER, SuManager.CLASSIC.getPackageName())
                .putBoolean(ConfigurationKeys.PREF_SOFT_REBOOT, true)
                .commit();

        RunConfig loaded = new SharedPreferencesConfigurationStore(deContext).load();

        assertNull(loaded.getManagerPackage());
        assertFalse(loaded.isSoftReboot());
        assertEquals(0, loaded.getGeneration());
    }

    @Test
    public void completeSnapshotRoundTripsThroughDePreferencesOnly() {
        RunConfig config = new RunConfig(SuManager.NEXT.getPackageName(), true, true,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 31);
        SharedPreferencesConfigurationStore store = new SharedPreferencesConfigurationStore(deContext);

        assertTrue(store.write(config));
        RunConfig loaded = store.load();

        assertEquals(config.getManagerPackage(), loaded.getManagerPackage());
        assertEquals(config.isSoftReboot(), loaded.isSoftReboot());
        assertEquals(config.isDisableModules(), loaded.isDisableModules());
        assertEquals(config.getSoftRebootAcknowledgementVersion(),
                loaded.getSoftRebootAcknowledgementVersion());
        assertEquals(config.getGeneration(), loaded.getGeneration());
        assertFalse(cePreferences.getBoolean(ConfigurationKeys.PREF_SOFT_REBOOT, false));
        assertNull(cePreferences.getString(ConfigurationKeys.PREF_SU_MANAGER, null));
    }

    @Test
    public void repositoryIsProcessWide() {
        assertSame(ConfigurationRepository.get(context), ConfigurationRepository.get(context));
    }

    @Test
    public void processFactoryAlwaysCreatesDeviceProtectedStorage() {
        dePreferences.edit().putString(ConfigurationKeys.PREF_SU_MANAGER,
                SuManager.NEXT.getPackageName()).commit();
        cePreferences.edit().putString(ConfigurationKeys.PREF_SU_MANAGER,
                SuManager.CLASSIC.getPackageName()).commit();

        RunConfig loaded = SharedPreferencesConfigurationStore
                .forDeviceProtectedStorage(context).load();

        assertEquals(SuManager.NEXT.getPackageName(), loaded.getManagerPackage());
    }
}
