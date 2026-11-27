// Modified 2026-11-24 (DFRoot GZIF): verify Java-only result details and exceptions without messages. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): guard the single authoritative unverified-outcome predicate. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RunResultTest {
    @Test
    public void alreadyHookedKeepsOwnerApprovedSuccessSemantics() {
        RunResult result = RunResult.alreadyHooked();

        assertEquals(ExploitRunner.RESULT_OK, result.getCode());
        assertEquals(RunResult.Type.SUCCESS, result.getType());
        assertEquals(RunResult.Detail.ALREADY_HOOKED, result.getDetail());
        assertNull(result.getReason());
        assertTrue(result.isSuccess());
    }

    @Test
    public void precheckFailureCarriesItsReason() {
        RunResult result = RunResult.precheckFailed("loader missing");

        assertEquals(ExploitRunner.RESULT_PRECHECK_FAILED, result.getCode());
        assertEquals(RunResult.Type.PRECHECK_FAILED, result.getType());
        assertEquals(RunResult.Detail.PREFLIGHT_REJECTION, result.getDetail());
        assertEquals("loader missing", result.getReason());
        assertFalse(result.isSuccess());
    }

    @Test
    public void exceptionWithMessageCarriesTypeAndMessage() {
        RunResult result = RunResult.exception(new IllegalStateException("staging failed"));

        assertEquals(ExploitRunner.RESULT_INDETERMINATE, result.getCode());
        assertEquals(RunResult.Type.INDETERMINATE, result.getType());
        assertEquals(RunResult.Detail.EXCEPTION, result.getDetail());
        assertEquals("IllegalStateException: staging failed", result.getReason());
        assertFalse(result.isSuccess());
    }

    @Test
    public void exceptionWithoutMessageStillHasANonEmptyReason() {
        RunResult nullMessage = RunResult.exception(new IllegalStateException());
        RunResult emptyMessage = RunResult.exception(new IllegalStateException(""));

        assertEquals("IllegalStateException", nullMessage.getReason());
        assertEquals("IllegalStateException", emptyMessage.getReason());
        assertFalse(nullMessage.isSuccess());
        assertFalse(emptyMessage.isSuccess());
    }

    /** Mutation guard: only the three unconfirmed shapes are unverified. */
    @Test
    public void unverifiedPredicateCoversRc7UnknownExitAndExceptionOnly() {
        assertTrue(NativeResultMapper.map(7).isOutcomeUnverified());
        for (int unknown : new int[] {8, 255, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertTrue("native rc=" + unknown + " was not unverified",
                    NativeResultMapper.map(unknown).isOutcomeUnverified());
        }
        assertTrue(RunResult.exception(new IllegalStateException("boom")).isOutcomeUnverified());

        for (int confirmed : new int[] {0, 1, 2, 3, 4, 5, 6}) {
            assertFalse("native rc=" + confirmed + " became unverified",
                    NativeResultMapper.map(confirmed).isOutcomeUnverified());
        }
        assertFalse(RunResult.alreadyHooked().isOutcomeUnverified());
        assertFalse(RunResult.precheckFailed("loader missing").isOutcomeUnverified());
        assertFalse(RunResult.processTimeout().isOutcomeUnverified());
        assertFalse(RunResult.processInterrupted().isOutcomeUnverified());
    }
}
