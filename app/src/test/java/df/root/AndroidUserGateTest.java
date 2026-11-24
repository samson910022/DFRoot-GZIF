// Modified 2026-11-24 (DFRoot GZIF): wire real Process identity through the owner gate on API 37. See NOTICE.
package df.root;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Process;
import android.os.UserHandle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class AndroidUserGateTest {
    private static final int OWNER_APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @After
    public void restoreProcessIdentity() {
        ShadowProcess.reset();
    }

    @Test
    public void ownerProcessIdentityIsAccepted() {
        ShadowProcess.setUid(OWNER_APP_UID);

        assertTrue(AndroidUserGate.isOwner());
    }

    @Test
    public void secondaryProcessIdentityIsRejected() {
        ShadowProcess.setUid(10 * 100_000 + OWNER_APP_UID);

        assertFalse(AndroidUserGate.isOwner());
    }

    @Test
    public void ownerUserAndUidAreAccepted() {
        assertTrue(AndroidUserGate.isOwner(Process.FIRST_APPLICATION_UID,
                UserHandle.getUserHandleForUid(Process.FIRST_APPLICATION_UID)));
    }

    @Test
    public void secondaryUserIsRejected() {
        int secondaryUid = 10 * 100_000 + Process.FIRST_APPLICATION_UID;
        assertFalse(AndroidUserGate.isOwner(secondaryUid,
                UserHandle.getUserHandleForUid(secondaryUid)));
    }

    @Test
    public void missingIdentityIsRejected() {
        assertFalse(AndroidUserGate.isOwner(Process.FIRST_APPLICATION_UID, null));
    }

    @Test
    public void inconsistentUidAndUserHandleAreRejected() {
        UserHandle owner = UserHandle.getUserHandleForUid(Process.FIRST_APPLICATION_UID);
        UserHandle secondary = UserHandle.getUserHandleForUid(10 * 100_000);

        assertFalse(AndroidUserGate.isOwner(10 * 100_000, owner));
        assertFalse(AndroidUserGate.isOwner(Process.FIRST_APPLICATION_UID, secondary));
    }
}
