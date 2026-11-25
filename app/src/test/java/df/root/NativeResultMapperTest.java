// Modified 2026-11-24 (DFRoot GZIF): verify every native code maps to a complete typed result. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): verify native rc=7 maps only to indeterminate, never success. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

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
        cases.add(new Object[] {6, ExploitRunner.RESULT_SOFT_REBOOT_FAILED,
                RunResult.Type.SOFT_REBOOT_FAILED, RunResult.Detail.NATIVE_EXIT, null});
        cases.add(new Object[] {7, ExploitRunner.RESULT_INDETERMINATE,
                RunResult.Type.INDETERMINATE, RunResult.Detail.NATIVE_EXIT, null});
        for (int unknown : new int[] {5, 8, 255, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            cases.add(new Object[] {unknown, ExploitRunner.RESULT_UNKNOWN, RunResult.Type.UNKNOWN,
                    RunResult.Detail.NATIVE_EXIT, "native exit code " + unknown});
        }
        return cases;
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
}
