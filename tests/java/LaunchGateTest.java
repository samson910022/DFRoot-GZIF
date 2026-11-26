// Modified 2026-11-25 (DFRoot GZIF round-3 PR #3 phase 3): host-only truth
// table for the early launch gate and StatusRc message truthfulness. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM-only tests: no Android runtime, device or native build is involved.
 * Covers the early-exit truth table (/dev/df precedence), the additive recovery
 * bit, and the promise that no toast claims root was verified or a reboot was
 * confirmed.
 */
public class LaunchGateTest {

    @Test
    public void earlyExitTruthTable() {
        // Neither gate: proceed into the exploit (no early exit).
        assertEquals(-1, ExploitRunner.earlyExitRc(false, false));
        // Quarantined preferences: rc 8, launch blocked.
        assertEquals(StatusRc.RC_PREF_STATE_UNCERTAIN,
                ExploitRunner.earlyExitRc(false, true));
        // /dev/df present: rc 9, even if preferences are also quarantined.
        assertEquals(StatusRc.RC_ALREADY_HOOKED,
                ExploitRunner.earlyExitRc(true, false));
        assertEquals(StatusRc.RC_ALREADY_HOOKED,
                ExploitRunner.earlyExitRc(true, true));
        // Numeric contract with status_protocol.h / StatusRc.
        assertEquals(8, StatusRc.RC_PREF_STATE_UNCERTAIN);
        assertEquals(9, StatusRc.RC_ALREADY_HOOKED);
        // A launch reservation is part of the same early gate: only OK proceeds.
        assertEquals(-1, ExploitRunner.launchTokenRc(PreferenceIntegrity.TokenResult.OK));
        assertEquals(StatusRc.RC_PREF_STATE_UNCERTAIN,
                ExploitRunner.launchTokenRc(PreferenceIntegrity.TokenResult.LAUNCH_BUSY));
        assertEquals(StatusRc.RC_PREF_STATE_UNCERTAIN,
                ExploitRunner.launchTokenRc(PreferenceIntegrity.TokenResult.FAIL_CLOSED));
    }

    @Test
    public void earlyGateNumbersMatchNativeResultCodes() {
        assertEquals(0, StatusRc.RC_ROOT_ONLY);
        assertEquals(1, StatusRc.RC_ERROR);
        assertEquals(2, StatusRc.RC_UNKNOWN);
        assertEquals(3, StatusRc.RC_PATCH_FAILED);
        assertEquals(4, StatusRc.RC_REBOOT_REQUESTED);
        assertEquals(5, StatusRc.RC_REBOOT_SKIPPED);
        assertEquals(6, StatusRc.RC_REBOOT_FAILED);
        assertEquals(7, StatusRc.RC_ATTEMPT_UNKNOWN);
        assertEquals(64, StatusRc.RECOVERY_FAILED_BIT);
    }

    @Test
    public void recoveryBitStripsFromAnyCodeAndAddsWarning() {
        int[] codes = { StatusRc.RC_ROOT_ONLY, StatusRc.RC_ERROR, StatusRc.RC_UNKNOWN,
                StatusRc.RC_PATCH_FAILED, StatusRc.RC_REBOOT_REQUESTED,
                StatusRc.RC_REBOOT_SKIPPED, StatusRc.RC_REBOOT_FAILED,
                StatusRc.RC_ATTEMPT_UNKNOWN, StatusRc.RC_PREF_STATE_UNCERTAIN,
                StatusRc.RC_ALREADY_HOOKED, 42 };
        for (int code : codes) {
            assertFalse(StatusRc.recoveryFailed(code));
            assertFalse(StatusRc.name(code).contains("recovery verification failed"));
            int withBit = code | StatusRc.RECOVERY_FAILED_BIT;
            assertTrue(StatusRc.recoveryFailed(withBit));
            assertEquals(code, StatusRc.base(withBit));
            assertTrue("rc " + withBit + " must surface the recovery warning",
                    StatusRc.name(withBit).contains("recovery verification failed"));
        }
    }

    @Test
    public void messagesNeverClaimVerifiedRootOrConfirmedReboot() {
        int[] codes = { StatusRc.RC_ROOT_ONLY, StatusRc.RC_ERROR, StatusRc.RC_UNKNOWN,
                StatusRc.RC_PATCH_FAILED, StatusRc.RC_REBOOT_REQUESTED,
                StatusRc.RC_REBOOT_SKIPPED, StatusRc.RC_REBOOT_FAILED,
                StatusRc.RC_ATTEMPT_UNKNOWN, StatusRc.RC_PREF_STATE_UNCERTAIN,
                StatusRc.RC_ALREADY_HOOKED, 42 };
        for (int code : codes) {
            for (int rc : new int[] { code, code | StatusRc.RECOVERY_FAILED_BIT }) {
                String msg = StatusRc.name(rc).toLowerCase(java.util.Locale.ROOT);
                assertFalse("rc " + rc + " falsely claims verified root: " + msg,
                        msg.contains("root verified"));
                assertFalse("rc " + rc + " falsely claims a confirmed reboot: " + msg,
                        msg.contains("confirmed reboot") || msg.contains("reboot confirmed"));
            }
        }
        assertTrue(StatusRc.name(StatusRc.RC_ALREADY_HOOKED).contains("already hooked"));
        assertTrue(StatusRc.name(StatusRc.RC_PREF_STATE_UNCERTAIN).contains("launch blocked"));
        assertTrue(StatusRc.name(StatusRc.RC_ATTEMPT_UNKNOWN).contains("unknown"));
        assertTrue(StatusRc.name(StatusRc.RC_ROOT_ONLY).contains("root not verified"));
    }
}
