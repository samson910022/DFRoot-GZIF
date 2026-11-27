// Modified 2026-11-24 (DFRoot GZIF): extract side-effect ordering from Android and native execution. See NOTICE.
package df.root;

import java.io.IOException;
import java.util.Objects;

/** Pure orchestration boundary for hook detection, preflight, and execution. */
public final class RunOrchestrator {
    public interface Effects {
        boolean isHookPresent() throws IOException;
        RunPreflight.Result preflight() throws Exception;
        RunResult execute(RunPreflight.Plan plan) throws Exception;
    }

    private RunOrchestrator() {}

    public static RunResult run(Effects effects, IReporter reporter) {
        try {
            Objects.requireNonNull(effects, "effects");
            if (effects.isHookPresent()) {
                reportQuietly(reporter, "already hooked, skipping\n");
                return RunResult.alreadyHooked();
            }

            RunPreflight.Result preflight = Objects.requireNonNull(
                    effects.preflight(), "preflight returned null");
            if (!preflight.isReady()) {
                reportQuietly(reporter, "ERROR: " + preflight.getReason() + "\n");
                return RunResult.precheckFailed(preflight.getReason());
            }

            RunResult result = Objects.requireNonNull(
                    effects.execute(preflight.getPlan()), "execute returned null");
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            RunResult result = RunResult.exception(e);
            reportQuietly(reporter, "ERROR: indeterminate run failure: "
                    + result.getReason() + "\n");
            return result;
        }
    }

    static void reportQuietly(IReporter reporter, String message) {
        try {
            reporter.report(message);
        } catch (Exception ignored) {
            // Reporting is observational and must never change the run outcome.
        }
    }
}
