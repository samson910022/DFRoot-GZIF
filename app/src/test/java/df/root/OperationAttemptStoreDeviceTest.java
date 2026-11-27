// Modified 2026-11-25 (DFRoot GZIF): verify the attempt barrier is isolated in DE storage and survives process restart. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class OperationAttemptStoreDeviceTest {
    private static final String ID = "de-attempt-run-1";

    private Context context;
    private Context deContext;
    private SharedPreferences attemptPrefs;
    private SharedPreferences configPrefs;
    private SharedPreferences bootPrefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        deContext = context.createDeviceProtectedStorageContext();
        attemptPrefs = deContext.getSharedPreferences(
                SharedPreferencesOperationAttemptStore.PREFS_NAME, Context.MODE_PRIVATE);
        configPrefs = deContext.getSharedPreferences(
                ConfigurationKeys.PREFS_NAME, Context.MODE_PRIVATE);
        bootPrefs = deContext.getSharedPreferences(
                SharedPreferencesBootClaimStore.PREFS_NAME, Context.MODE_PRIVATE);
        attemptPrefs.edit().clear().commit();
        context.getSharedPreferences(SharedPreferencesOperationAttemptStore.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
        configPrefs.edit().clear().commit();
        bootPrefs.edit().clear().commit();
    }

    @Test
    public void publishTouchesOnlyTheDedicatedDeviceProtectedFile() throws Exception {
        configPrefs.edit().putBoolean(ConfigurationKeys.PREF_SOFT_REBOOT, true).commit();
        configPrefs.edit().putString(ConfigurationKeys.PREF_SU_MANAGER, "sentinel").commit();
        bootPrefs.edit().putLong(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT,
                91L).commit();

        OperationAttemptStore store =
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context);
        assertTrue(store.publish(new OperationAttempt(ID, RunConfig.ExecutionMode.MANUAL,
                91L, true, "admitted")));

        assertEquals(ID, attemptPrefs.getString(
                SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID, null));
        assertTrue(attemptPrefs.contains(
                SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED));
        assertFalse("config key leaked into attempt file",
                attemptPrefs.contains(ConfigurationKeys.PREF_SOFT_REBOOT));
        assertFalse("boot-claim key leaked into attempt file",
                attemptPrefs.contains(
                        SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
        assertTrue("configuration was modified",
                configPrefs.getBoolean(ConfigurationKeys.PREF_SOFT_REBOOT, false));
        assertEquals("sentinel",
                configPrefs.getString(ConfigurationKeys.PREF_SU_MANAGER, null));
        assertEquals(91L, bootPrefs.getLong(
                SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT, -1L));
        assertFalse("attempt leaked into credential storage",
                context.getSharedPreferences(SharedPreferencesOperationAttemptStore.PREFS_NAME,
                        Context.MODE_PRIVATE).contains(
                                SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID));
    }

    @Test
    public void restartedStoreLoadsAndClearsTheSameDurableRecord() throws Exception {
        OperationAttemptStore first =
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context);
        assertTrue(first.publish(new OperationAttempt(ID, RunConfig.ExecutionMode.UNATTENDED_BOOT,
                92L, true, "admitted")));

        // A fresh store over the same DE file models a new process.
        OperationAttemptStore restarted =
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context);
        OperationAttempt loaded = restarted.load();
        assertNotNull(loaded);
        assertEquals(ID, loaded.getOperationId());
        assertEquals(RunConfig.ExecutionMode.UNATTENDED_BOOT, loaded.getMode());
        assertEquals(92L, loaded.getBootCount());
        assertTrue(loaded.isUnconfirmed());

        assertFalse(restarted.clear("wrong-id"));
        assertTrue(restarted.clear(ID));
        assertNull(SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context).load());
    }

    @Test
    public void emptyAttemptFileLoadsNoRecord() throws Exception {
        OperationAttemptStore store =
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context);

        assertNull(store.load());
    }

    @Test
    public void missingIdWithOtherKeysFailsClosed() {
        attemptPrefs.edit()
                .putString(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE,
                        RunConfig.ExecutionMode.MANUAL.name())
                .putLong(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT, 93L)
                .putBoolean(SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED, true)
                .putString(SharedPreferencesOperationAttemptStore.KEY_REASON, "partial")
                .commit();

        OperationAttemptStore.Failure failure = assertThrows(OperationAttemptStore.Failure.class,
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context)::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is incomplete"));
    }

    @Test
    public void missingUnconfirmedWithOtherKeysFailsClosed() {
        attemptPrefs.edit()
                .putString(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID, ID)
                .putString(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE,
                        RunConfig.ExecutionMode.MANUAL.name())
                .putLong(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT, 94L)
                .putString(SharedPreferencesOperationAttemptStore.KEY_REASON, "partial")
                .commit();

        OperationAttemptStore.Failure failure = assertThrows(OperationAttemptStore.Failure.class,
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context)::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is incomplete"));
    }

    @Test
    public void negativeBootCountFailsClosed() {
        attemptPrefs.edit()
                .putString(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID, ID)
                .putString(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE,
                        RunConfig.ExecutionMode.MANUAL.name())
                .putLong(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT, -1L)
                .putBoolean(SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED, true)
                .commit();

        OperationAttemptStore.Failure failure = assertThrows(OperationAttemptStore.Failure.class,
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context)::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is corrupt"));
    }

    @Test
    public void wrongValueTypeFailsClosedThroughTheCheckedReadPath() {
        attemptPrefs.edit()
                .putString(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID, ID)
                .putString(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE,
                        RunConfig.ExecutionMode.MANUAL.name())
                .putString(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT, "not-a-long")
                .putBoolean(SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED, true)
                .commit();

        OperationAttemptStore.Failure failure = assertThrows(OperationAttemptStore.Failure.class,
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context)::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is unreadable"));
    }
}
