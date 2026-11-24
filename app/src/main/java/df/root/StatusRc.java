// Modified 2026-11-25 (DFRoot GZIF round-3 PR #3 phase 3): pure Java mirror of
// the native app result codes, including the additive recovery-failed bit. See NOTICE.
package df.root;

/**
 * Truthful, host-testable mapping from app result codes to UI messages.
 *
 * The base codes mirror status_protocol.h. The recovery bit is additive and is
 * reported for EVERY code: a failed restore must never be hidden by an
 * otherwise "successful" outcome. No message claims that root was verified or
 * that a reboot was confirmed -- only an on-device oracle can prove either.
 */
public final class StatusRc {

    public static final int RC_ROOT_ONLY = 0;
    public static final int RC_ERROR = 1;
    public static final int RC_UNKNOWN = 2;
    public static final int RC_PATCH_FAILED = 3;
    public static final int RC_REBOOT_REQUESTED = 4;
    public static final int RC_REBOOT_SKIPPED = 5;
    public static final int RC_REBOOT_FAILED = 6;
    public static final int RC_ATTEMPT_UNKNOWN = 7;
    /** Java-side gate: SharedPreferences memory/disk state is uncertain (P2-08). */
    public static final int RC_PREF_STATE_UNCERTAIN = 8;
    /** Java-side gate: /dev/df already present, so this boot is already hooked. */
    public static final int RC_ALREADY_HOOKED = 9;
    /** OR'ed into any rc when restore verification failed. */
    public static final int RECOVERY_FAILED_BIT = 64;

    private static final String RECOVERY_WARNING =
            " WARNING: restore/recovery verification failed - do not trust this result";

    private StatusRc() {}

    /** Strip the additive recovery bit to get the canonical base code. */
    public static int base(int rc) {
        return rc & ~RECOVERY_FAILED_BIT;
    }

    public static boolean recoveryFailed(int rc) {
        return (rc & RECOVERY_FAILED_BIT) != 0;
    }

    /** Always append the recovery warning when the bit is set, for any rc. */
    public static String name(int rc) {
        String msg;
        switch (base(rc)) {
            case RC_ROOT_ONLY:
                msg = "DFRoot: late-load exited 0; root not verified";
                break;
            case RC_ERROR:
                msg = "DFRoot: Error - bootstrap failed";
                break;
            case RC_UNKNOWN:
                msg = "DFRoot: unknown result - check logcat & dmesg";
                break;
            case RC_PATCH_FAILED:
                msg = "DFRoot: Error - failed to patch files";
                break;
            case RC_REBOOT_REQUESTED:
                msg = "DFRoot: soft-reboot command issued; not confirmed";
                break;
            case RC_REBOOT_SKIPPED:
                msg = "DFRoot: soft reboot skipped (late-load not confirmed absent)";
                break;
            case RC_REBOOT_FAILED:
                msg = "DFRoot: soft-reboot command failed";
                break;
            case RC_ATTEMPT_UNKNOWN:
                msg = "DFRoot: soft-reboot attempt result unknown (pending/unknown final status)";
                break;
            case RC_PREF_STATE_UNCERTAIN:
                msg = "DFRoot: launch blocked - preference state uncertain; " +
                      "save the setting again or clear app data";
                break;
            case RC_ALREADY_HOOKED:
                msg = "DFRoot: already hooked (/dev/df present); launch skipped";
                break;
            default:
                msg = "DFRoot: Error - unknown result";
                break;
        }
        if (recoveryFailed(rc)) msg += RECOVERY_WARNING;
        return msg;
    }
}
