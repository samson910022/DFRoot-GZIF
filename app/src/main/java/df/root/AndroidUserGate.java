// Modified 2026-11-24 (DFRoot GZIF): adapt Android user identity to the pure owner-user gate with an injectable seam. See NOTICE.
package df.root;

import android.os.Process;
import android.os.UserHandle;

/** Android adapter for owner-user validation using public UserHandle APIs. */
final class AndroidUserGate {
    private AndroidUserGate() {}

    static boolean isOwner() {
        return isOwner(Process.myUid(), Process.myUserHandle());
    }

    static boolean isOwner(int uid, UserHandle user) {
        UserHandle owner = UserHandle.getUserHandleForUid(Process.FIRST_APPLICATION_UID);
        return user != null && user.equals(owner) && UserGate.isOwnerUid(uid);
    }
}
