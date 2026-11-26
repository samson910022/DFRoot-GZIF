// Modified 2026-11-24 (DFRoot GZIF): verify every native code maps to a complete typed result. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): verify native rc=7 maps only to indeterminate, never success. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): tie every mapped exit to the authoritative unverified predicate. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin the explicit rc=7 cause label while unknown exits keep their honest reason. See NOTICE.
// Modified 2026-11-27 (R9.1): pin rc=5 (reboot skipped) and the additive
// recovery-failed bit against the authoritative status_protocol.h set. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class NativeResultMapperTest {
    private final int nativeCode;
    private final int expectedCode;
    private final RunResult.Type expectedType;
    private final RunResult.Detail expectedDetail;
    private final String expectedReason;

    public NativeResultMapperTest(int nativeCode, int expectedCode, RunResult.Type expectedType,
                                  RunResult.Detail expectedDetail, String expectedReason) {
        this.nativeCode = nativeCode;
        this.expectedCode = expectedCode;
        this.expectedType = expectedType;
        this.expectedDetail = expectedDetail;
        this.expectedReason = expectedReason;
    }

    @Parameterized.Parameters(name = "native {0}")
    public static Collection<Object[]> codes() {
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] {0, ExploitRunner.RESULT_OK, RunResult.Type.SUCCESS,
                RunResult.Detail.LATE_LOAD_COMPLETED, null});
        cases.add(new Object[] {1, ExploitRunner.RESULT_KSUD_ERROR, RunResult.Type.BOOTSTRAP_ERROR,
                RunResult.Detail.NATIVE_EXIT, null});
        cases.add(new Object[] {2, ExploitRunner.RESULT_NATIVE_SETUP_FAILED,
                RunResult.Type.NATIVE_SETUP_FAILED, RunResult.Detail.NATIVE_EXIT, null});
        cases.add(new Object[] {3, ExploitRunner.RESULT_PATCH_FAILED, RunResult.Type.PATCH_FAILED,
                RunResult.Detail.NATIVE_EXIT, null});
        cases.add(new Object[] {4, ExploitRunner.RESULT_SOFT_REBOOT_PENDING,
                RunResult.Type.SOFT_REBOOT_PENDING, RunResult.Detail.NATIVE_EXIT, null});
        // rc=5 is DF_RC_REBOOT_SKIPPED, not an unrecognized exit.
        cases.add(new Object[] {5, ExploitRunner.RESULT_SOFT_REBOOT_PENDING,
                RunResult.Type.SOFT_REBOOT_PENDING, RunResult.Detail.NATIVE_EXIT, SKIPPED_REASON});
        cases.add(new Object[] {6, ExploitRunner.RESULT_SOFT_REBOOT_FAILED,
                RunResult.Type.SOFT_REBOOT_FAILED, RunResult.Detail.NATIVE_EXIT, null});
        cases.add(new Object[] {7, ExploitRunner.RESULT_INDETERMINATE,
                RunResult.Type.INDETERMINATE, RunResult.Detail.NATIVE_EXIT,
                "native rc=7 indeterminate publication"});
        for (int unknown : new int[] {8, 255, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            cases.add(new Object[] {unknown, ExploitRunner.RESULT_UNKNOWN, RunResult.Type.UNKNOWN,
                    RunResult.Detail.NATIVE_EXIT, "native exit code " + unknown});
        }
        // The recovery bit is additive: every base keeps its type while the
        // stable code carries bit 64 so the UI warning stays reachable.
        for (int base : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8}) {
            int withBit = base | StatusRc.RECOVERY_FAILED_BIT;
            cases.add(new Object[] {withBit, baseCode(base) | StatusRc.RECOVERY_FAILED_BIT,
                    baseType(base), baseDetail(base), reasonFor(withBit)});
        }
        return cases;
    }

    private static final String SKIPPED_REASON =
            "native rc=5 soft reboot skipped (late-load not confirmed absent)";

    private static int baseCode(int base) {
        switch (base) {
            case 0: return ExploitRunner.RESULT_OK;
            case 1: return ExploitRunner.RESULT_KSUD_ERROR;
            case 2: return ExploitRunner.RESULT_NATIVE_SETUP_FAILED;
            case 3: return ExploitRunner.RESULT_PATCH_FAILED;
            case 4: return ExploitRunner.RESULT_SOFT_REBOOT_PENDING; // both rc4 and rc5
            case 5: return ExploitRunner.RESULT_SOFT_REBOOT_PENDING;
            case 6: return ExploitRunner.RESULT_SOFT_REBOOT_FAILED;
            case 7: return ExploitRunner.RESULT_INDETERMINATE;
            default: return ExploitRunner.RESULT_UNKNOWN;
        }
    }

    private static RunResult.Type baseType(int base) {
        switch (base) {
            case 0: return RunResult.Type.SUCCESS;
            case 1: return RunResult.Type.BOOTSTRAP_ERROR;
            case 2: return RunResult.Type.NATIVE_SETUP_FAILED;
            case 3: return RunResult.Type.PATCH_FAILED;
            case 4: return RunResult.Type.SOFT_REBOOT_PENDING;
            case 5: return RunResult.Type.SOFT_REBOOT_PENDING;
            case 6: return RunResult.Type.SOFT_REBOOT_FAILED;
            case 7: return RunResult.Type.INDETERMINATE;
            default: return RunResult.Type.UNKNOWN;
        }
    }

    private static RunResult.Detail baseDetail(int base) {
        return base == 0 ? RunResult.Detail.LATE_LOAD_COMPLETED : RunResult.Detail.NATIVE_EXIT;
    }

    private static String reasonFor(int nativeCode) {
        int base = StatusRc.base(nativeCode);
        String baseReason = base == 5 ? SKIPPED_REASON
                : base == 7 ? "native rc=7 indeterminate publication"
                : base > 7 ? "native exit code " + nativeCode : null;
        String bitReason = "native rc=" + nativeCode + " recovery verification failed (bit 64)";
        return baseReason == null ? bitReason : baseReason + "; " + bitReason;
    }

    @Test
    public void mapsCodeTypeDetailReasonAndSuccessTogether() {
        RunResult result = NativeResultMapper.map(nativeCode);

        assertEquals(expectedCode, result.getCode());
        assertEquals(expectedType, result.getType());
        assertEquals(expectedDetail, result.getDetail());
        assertEquals(expectedReason, result.getReason());
        assertEquals(expectedType == RunResult.Type.SUCCESS, result.isSuccess());
    }

    /** Mutation guard: rc7 and only unrecognized exits are unverified outcomes. */
    @Test
    public void mappedExitAgreesWithTheAuthoritativeUnverifiedPredicate() {
        for (Object[] testCase : codes()) {
            int code = (Integer) testCase[0];
            boolean expected = StatusRc.base(code) == 7 || !isConfirmedNativeCode(code);
            assertEquals("native rc=" + code + " disagrees with the unverified predicate",
                    expected, NativeResultMapper.map(code).isOutcomeUnverified());
        }
    }

    private static boolean isConfirmedNativeCode(int code) {
        int base = StatusRc.base(code);
        return base == 0 || base == 1 || base == 2 || base == 3 || base == 4
                || base == 5 || base == 6;
    }

    /** Only a genuine rc=7 may carry the rc7 publication label; unknown exits may not. */
    @Test
    public void unknownExitsNeverClaimNativeRc7Publication() {
        for (int unknown : new int[] {8, 73, 255, -1}) {
            RunResult result = NativeResultMapper.map(unknown);
            assertEquals("native exit code " + unknown, result.getReason());
            assertFalse(result.getReason().contains("rc=7"));
            assertTrue(result.isOutcomeUnverified());
        }
        RunResult rc7 = NativeResultMapper.map(7);
        assertEquals("native rc=7 indeterminate publication", rc7.getReason());
        assertTrue(rc7.getReason().contains("rc=7"));
        assertTrue(rc7.isOutcomeUnverified());
        // rc=5 is a KNOWN skipped outcome; it must never claim rc7 either.
        RunResult skipped = NativeResultMapper.map(5);
        assertTrue(skipped.getReason().contains("rc=5"));
        assertFalse(skipped.getReason().contains("rc=7"));
        assertFalse(skipped.isOutcomeUnverified());
    }

    /** rc=7 owns the indeterminate contract and cannot alias any other native rc. */
    @Test
    public void indeterminateCodeIsCollisionFree() {
        RunResult indeterminate = NativeResultMapper.map(7);
        assertEquals(ExploitRunner.RESULT_INDETERMINATE, indeterminate.getCode());
        assertEquals(RunResult.Type.INDETERMINATE, indeterminate.getType());
        assertFalse(indeterminate.isSuccess());
        // It is the Java indeterminate code (-2), not the raw native value 7.
        assertNotEquals(7, indeterminate.getCode());
        assertEquals(ExploitRunner.RESULT_OK, NativeResultMapper.map(0).getCode());
        assertEquals(ExploitRunner.RESULT_NATIVE_SETUP_FAILED, NativeResultMapper.map(2).getCode());
        for (int code : new int[] {0, 1, 2, 3, 4, 5, 6, 8, 255, -1}) {
            assertNotEquals("native rc=" + code + " collided with indeterminate",
                            indeterminate.getCode(), NativeResultMapper.map(code).getCode());
        }
    }

    /** A failed restore is visible for EVERY base code, never swallowed as UNKNOWN. */
    @Test
    public void recoveryFailedBitSurvivesForEveryBaseCode() {
        for (int base : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8}) {
            int withBit = base | StatusRc.RECOVERY_FAILED_BIT;
            RunResult result = NativeResultMapper.map(withBit);
            RunResult plain = NativeResultMapper.map(base);

            assertTrue("native rc=" + withBit + " lost the recovery bit",
                    StatusRc.recoveryFailed(result.getCode()));
            assertEquals("native rc=" + withBit + " changed the base outcome",
                    plain.getCode(), StatusRc.base(result.getCode()));
            assertEquals(plain.getType(), result.getType());
            assertEquals(plain.getDetail(), result.getDetail());
            assertEquals(plain.isOutcomeUnverified(), result.isOutcomeUnverified());
            assertTrue(result.getReason().contains("bit 64"));
            assertTrue("no mapped result may hide the recovery failure behind UNKNOWN",
                    result.getType() != RunResult.Type.UNKNOWN || base == 8);
        }
    }

    /** rc=5 is a skipped reboot shared with the pending typed family, never "requested". */
    @Test
    public void skippedRebootKeepsItsHonestLabel() {
        RunResult skipped = NativeResultMapper.map(5);
        assertEquals(ExploitRunner.RESULT_SOFT_REBOOT_PENDING, skipped.getCode());
        assertEquals(RunResult.Type.SOFT_REBOOT_PENDING, skipped.getType());
        assertFalse(StatusRc.recoveryFailed(skipped.getCode()));
        assertFalse(ResultMessages.message(skipped).contains("was requested"));
        assertTrue(ResultMessages.message(skipped).contains("skipped"));
    }
}
