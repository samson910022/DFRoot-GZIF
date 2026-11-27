// Modified 2026-11-24 (DFRoot GZIF): verify owner-user UID classification without Android runtime calls. See NOTICE.
package df.root;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UserGateTest {

    @Test
    public void ownerUserUidsAreAccepted() {
        assertTrue(UserGate.isOwnerUid(0));
        assertTrue(UserGate.isOwnerUid(10_000));
        assertTrue(UserGate.isOwnerUid(99_999));
    }

    @Test
    public void secondaryUsersAndInvalidUidsAreRejected() {
        assertFalse(UserGate.isOwnerUid(-1));
        assertFalse(UserGate.isOwnerUid(100_000));
        assertFalse(UserGate.isOwnerUid(110_000));
    }
}
