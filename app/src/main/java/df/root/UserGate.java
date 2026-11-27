// Modified 2026-11-24 (DFRoot GZIF): add a pure owner-Android-user UID check. See NOTICE.
package df.root;

/** Pure UID classification; the Android UserHandle adapter supplies the input. */
public final class UserGate {
    private static final int PER_USER_RANGE = 100_000;

    private UserGate() {}

    public static boolean isOwnerUid(int uid) {
        return uid >= 0 && uid < PER_USER_RANGE;
    }
}
