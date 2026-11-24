// Modified 2026-11-24 (DFRoot GZIF): prove side-effect-free orchestration ordering and failure outcomes. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunOrchestratorTest {
    private static final RunPreflight.Plan PLAN = new RunPreflight.Plan(
            SuManager.CLASSIC,
            new File("loader"),
            new File("bootstrap"),
            new File("native"));

    @Test
    public void presentHookReturnsSuccessWithoutPreflightOrExecution() {
        FakeEffects effects = new FakeEffects();
        effects.hookPresent = true;

        RunResult result = RunOrchestrator.run(effects, ignored -> {});

        assertEquals(RunResult.Type.SUCCESS, result.getType());
        assertEquals(RunResult.Detail.ALREADY_HOOKED, result.getDetail());
        assertTrue(result.isSuccess());
        assertEquals(Arrays.asList("hook"), effects.calls);
        assertEquals(0, effects.preflightCalls);
        assertEquals(0, effects.executeCalls);
    }

    @Test
    public void rejectedPreflightNeverExecutes() {
        FakeEffects effects = new FakeEffects();
        effects.preflight = RunPreflight.Result.rejected(
                RunPreflight.Failure.UNSUPPORTED_FIRMWARE, "unsupported firmware");

        RunResult result = RunOrchestrator.run(effects, ignored -> {});

        assertEquals(RunResult.Type.PRECHECK_FAILED, result.getType());
        assertEquals("unsupported firmware", result.getReason());
        assertFalse(result.isSuccess());
        assertEquals(Arrays.asList("hook", "preflight"), effects.calls);
        assertEquals(0, effects.executeCalls);
    }

    @Test
    public void successfulPreflightExecutesExactlyOnceInOrder() {
        FakeEffects effects = new FakeEffects();
        effects.preflight = ready();
        RunResult nativeResult = NativeResultMapper.map(0);
        effects.result = nativeResult;

        RunResult result = RunOrchestrator.run(effects, ignored -> {});

        assertSame(nativeResult, result);
        assertEquals(Arrays.asList("hook", "preflight", "execute"), effects.calls);
        assertEquals(1, effects.preflightCalls);
        assertEquals(1, effects.executeCalls);
        assertSame(PLAN, effects.executedPlan);
    }

    @Test
    public void reporterFailuresDoNotChangeOutcomes() {
        FakeEffects hooked = new FakeEffects();
        hooked.hookPresent = true;
        RunResult hookedResult = RunOrchestrator.run(hooked, message -> {
            throw new IllegalStateException("reporter unavailable");
        });
        assertEquals(RunResult.Detail.ALREADY_HOOKED, hookedResult.getDetail());

        FakeEffects rejected = new FakeEffects();
        rejected.preflight = RunPreflight.Result.rejected(
                RunPreflight.Failure.LOADER_MISSING, "loader missing");
        RunResult rejectedResult = RunOrchestrator.run(rejected, message -> {
            throw new IllegalStateException("reporter unavailable");
        });
        assertEquals(RunResult.Type.PRECHECK_FAILED, rejectedResult.getType());
        assertEquals("loader missing", rejectedResult.getReason());

        FakeEffects successful = new FakeEffects();
        successful.preflight = ready();
        successful.result = NativeResultMapper.map(0);
        RunResult successfulResult = RunOrchestrator.run(successful, message -> {
            throw new IllegalStateException("reporter unavailable");
        });
        assertSame(successful.result, successfulResult);

        FakeEffects failedProbe = new FakeEffects();
        failedProbe.hookFailure = new IOException("marker unreadable");
        RunResult indeterminate = RunOrchestrator.run(failedProbe, message -> {
            throw new IllegalStateException("reporter unavailable");
        });
        assertEquals(RunResult.Type.INDETERMINATE, indeterminate.getType());
        assertTrue(indeterminate.getReason().contains("marker unreadable"));
    }

    @Test
    public void probeFailureIsIndeterminateBeforePreflight() {
        FakeEffects effects = new FakeEffects();
        effects.hookFailure = new IOException("marker unreadable");

        RunResult result = RunOrchestrator.run(effects, ignored -> {});

        assertIndeterminate(result, "marker unreadable");
        assertEquals(Arrays.asList("hook"), effects.calls);
        assertEquals(0, effects.preflightCalls);
        assertEquals(0, effects.executeCalls);
    }

    @Test
    public void probeRuntimeFailureIsAlsoIndeterminateBeforePreflight() {
        FakeEffects effects = new FakeEffects();
        effects.hookRuntimeFailure = new SecurityException("marker access denied");

        RunResult result = RunOrchestrator.run(effects, ignored -> {});

        assertIndeterminate(result, "marker access denied");
        assertEquals(Arrays.asList("hook"), effects.calls);
        assertEquals(0, effects.preflightCalls);
        assertEquals(0, effects.executeCalls);
    }

    @Test
    public void preflightFailureIsIndeterminateBeforeExecution() {
        FakeEffects effects = new FakeEffects();
        effects.preflightFailure = new IllegalStateException("preflight unavailable");

        RunResult result = RunOrchestrator.run(effects, ignored -> {});

        assertIndeterminate(result, "preflight unavailable");
        assertEquals(Arrays.asList("hook", "preflight"), effects.calls);
        assertEquals(0, effects.executeCalls);
    }

    @Test
    public void executionFailureIsIndeterminateAfterOneAttempt() {
        FakeEffects effects = new FakeEffects();
        effects.preflight = ready();
        effects.executeFailure = new IllegalStateException("staging unavailable");

        RunResult result = RunOrchestrator.run(effects, ignored -> {});

        assertIndeterminate(result, "staging unavailable");
        assertEquals(Arrays.asList("hook", "preflight", "execute"), effects.calls);
        assertEquals(1, effects.executeCalls);
    }

    @Test
    public void malformedEffectResultsFailClosed() {
        FakeEffects missingPreflight = new FakeEffects();
        assertIndeterminate(RunOrchestrator.run(missingPreflight, ignored -> {}), "null");

        FakeEffects missingExecution = new FakeEffects();
        missingExecution.preflight = ready();
        assertIndeterminate(RunOrchestrator.run(missingExecution, ignored -> {}), "null");
        assertEquals(1, missingExecution.executeCalls);
    }

    private static RunPreflight.Result ready() {
        return RunPreflight.Result.ready(PLAN);
    }

    private static void assertIndeterminate(RunResult result, String reasonFragment) {
        assertEquals(ExploitRunner.RESULT_INDETERMINATE, result.getCode());
        assertEquals(RunResult.Type.INDETERMINATE, result.getType());
        assertEquals(RunResult.Detail.EXCEPTION, result.getDetail());
        assertFalse(result.isSuccess());
        assertTrue(result.getReason().contains(reasonFragment));
    }

    private static final class FakeEffects implements RunOrchestrator.Effects {
        final List<String> calls = new ArrayList<>();
        boolean hookPresent;
        IOException hookFailure;
        RuntimeException hookRuntimeFailure;
        RunPreflight.Result preflight;
        RuntimeException preflightFailure;
        RunResult result;
        RuntimeException executeFailure;
        int preflightCalls;
        int executeCalls;
        RunPreflight.Plan executedPlan;

        @Override
        public boolean isHookPresent() throws IOException {
            calls.add("hook");
            if (hookFailure != null) {
                throw hookFailure;
            }
            if (hookRuntimeFailure != null) {
                throw hookRuntimeFailure;
            }
            return hookPresent;
        }

        @Override
        public RunPreflight.Result preflight() throws Exception {
            calls.add("preflight");
            preflightCalls++;
            if (preflightFailure != null) {
                throw preflightFailure;
            }
            return preflight;
        }

        @Override
        public RunResult execute(RunPreflight.Plan plan) throws Exception {
            calls.add("execute");
            executeCalls++;
            executedPlan = plan;
            if (executeFailure != null) {
                throw executeFailure;
            }
            return result;
        }
    }
}
