// Modified 2026-11-24 (DFRoot GZIF): specify pure user-facing messages for every typed run outcome. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): pin the honest already-attempted wording without changing result semantics. See NOTICE.
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
public class ResultMessagesTest {
    static final String ALREADY_HOOKED_MESSAGE =
            "DFRoot: SUCCESS (already attempted/hooked; root status unverified)";

    private final RunResult.Type type;
    private final RunResult.Detail detail;
    private final String reason;
    private final String expected;

    public ResultMessagesTest(RunResult.Type type, RunResult.Detail detail,
                              String reason, String expected) {
        this.type = type;
        this.detail = detail;
        this.reason = reason;
        this.expected = expected;
    }

    @Parameterized.Parameters(name = "{0}/{1}")
    public static Collection<Object[]> messages() {
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] {RunResult.Type.SUCCESS, RunResult.Detail.LATE_LOAD_COMPLETED,
                null, "DFRoot: SUCCESS"});
        cases.add(new Object[] {RunResult.Type.SUCCESS, RunResult.Detail.ALREADY_HOOKED,
                null, ALREADY_HOOKED_MESSAGE});
        cases.add(new Object[] {RunResult.Type.BOOTSTRAP_ERROR, RunResult.Detail.NATIVE_EXIT,
                null, "DFRoot: Error - bootstrap/late-load failed"});
        cases.add(new Object[] {RunResult.Type.PRECHECK_FAILED, RunResult.Detail.PREFLIGHT_REJECTION,
                "loader missing", "DFRoot: Error - precheck failed (see output)"});
        cases.add(new Object[] {RunResult.Type.PATCH_FAILED, RunResult.Detail.NATIVE_EXIT,
                null, "DFRoot: Error - failed to patch files"});
        cases.add(new Object[] {RunResult.Type.SOFT_REBOOT_PENDING, RunResult.Detail.NATIVE_EXIT,
                null, "DFRoot: root active — device may soft-reboot shortly"});
        cases.add(new Object[] {RunResult.Type.NATIVE_SETUP_FAILED, RunResult.Detail.NATIVE_EXIT,
                null, "DFRoot: Error - native setup/timeout (see logcat & dmesg)"});
        cases.add(new Object[] {RunResult.Type.SOFT_REBOOT_FAILED, RunResult.Detail.NATIVE_EXIT,
                null, "DFRoot: Error - soft-reboot command failed"});
        cases.add(new Object[] {RunResult.Type.UNKNOWN, RunResult.Detail.NATIVE_EXIT,
                "native exit code 73", "DFRoot: Error - unknown result (native exit code 73)"});
        cases.add(new Object[] {RunResult.Type.INDETERMINATE, RunResult.Detail.EXCEPTION,
                "IOException: marker unreadable", "DFRoot: Error - outcome indeterminate (see output)"});
        return cases;
    }

    @Test
    public void mapsTheTypedOutcomeWithoutLosingUnknownRawDiagnostics() {
        int code = codeFor(type);
        RunResult result = new RunResult(code, type, detail, reason);

        assertEquals(expected, ResultMessages.message(result));
        // Rendering a message is observational: the typed contract is left untouched.
        assertEquals(code, result.getCode());
        assertEquals(type, result.getType());
        assertEquals(detail, result.getDetail());
        assertEquals(reason, result.getReason());
    }

    @Test
    public void successAndFailureMessagesRemainDistinct() {
        RunResult result = new RunResult(codeFor(type), type, detail, reason);
        String message = ResultMessages.message(result);

        if (type == RunResult.Type.SUCCESS) {
            assertFalse(message.startsWith("DFRoot: Error"));
        } else {
            assertNotEquals("DFRoot: SUCCESS", message);
            assertNotEquals(ALREADY_HOOKED_MESSAGE, message);
        }
    }

    @Test
    public void alreadyHookedMessageIsHonestAndKeepsOwnerApprovedSemantics() {
        RunResult result = RunResult.alreadyHooked();

        assertEquals(ExploitRunner.RESULT_OK, result.getCode());
        assertEquals(RunResult.Type.SUCCESS, result.getType());
        assertEquals(RunResult.Detail.ALREADY_HOOKED, result.getDetail());
        assertTrue(result.isSuccess());
        assertEquals(ALREADY_HOOKED_MESSAGE, ResultMessages.message(result));
        assertTrue(ResultMessages.message(result).contains("unverified"));
        assertFalse("must not claim root was verified",
                ResultMessages.message(result).contains("root verified"));
    }

    private static int codeFor(RunResult.Type type) {
        switch (type) {
            case SUCCESS: return ExploitRunner.RESULT_OK;
            case BOOTSTRAP_ERROR: return ExploitRunner.RESULT_KSUD_ERROR;
            case PRECHECK_FAILED: return ExploitRunner.RESULT_PRECHECK_FAILED;
            case PATCH_FAILED: return ExploitRunner.RESULT_PATCH_FAILED;
            case SOFT_REBOOT_PENDING: return ExploitRunner.RESULT_SOFT_REBOOT_PENDING;
            case NATIVE_SETUP_FAILED: return ExploitRunner.RESULT_NATIVE_SETUP_FAILED;
            case SOFT_REBOOT_FAILED: return ExploitRunner.RESULT_SOFT_REBOOT_FAILED;
            case UNKNOWN: return ExploitRunner.RESULT_UNKNOWN;
            case INDETERMINATE: return ExploitRunner.RESULT_INDETERMINATE;
            default: throw new AssertionError(type);
        }
    }
}
