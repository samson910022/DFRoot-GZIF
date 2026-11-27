// Modified 2026-11-25 (DFRoot GZIF): add the immutable, process-independent operation attempt record. See NOTICE.
package df.root;

import java.util.Objects;

/**
 * Immutable admission record for one operation. It is deliberately a plain JVM
 * value: no Android state, no resource ownership, and no side effects. The
 * durable form is written only through {@link OperationAttemptStore}.
 *
 * <p>{@code unconfirmed} is the safety bit. A published attempt is unconfirmed
 * until a checked terminal clear succeeds; a confirmed result whose clear
 * failed must never be reported as confirmed.</p>
 */
final class OperationAttempt {
    private final String operationId;
    private final RunConfig.ExecutionMode mode;
    private final long bootCount;
    private final boolean unconfirmed;
    private final String reason;

    OperationAttempt(String operationId, RunConfig.ExecutionMode mode, long bootCount,
                     boolean unconfirmed, String reason) {
        if (operationId == null || operationId.isEmpty()) {
            throw new IllegalArgumentException("operation ID is required");
        }
        this.operationId = operationId;
        this.mode = Objects.requireNonNull(mode, "mode");
        if (bootCount < 0L) {
            throw new IllegalArgumentException("boot count must be non-negative");
        }
        this.bootCount = bootCount;
        this.unconfirmed = unconfirmed;
        this.reason = reason;
    }

    String getOperationId() {
        return operationId;
    }

    RunConfig.ExecutionMode getMode() {
        return mode;
    }

    /** Boot count observed at admission; zero means unavailable, never authority. */
    long getBootCount() {
        return bootCount;
    }

    boolean isUnconfirmed() {
        return unconfirmed;
    }

    /** Human-readable diagnostic only; never used to authorize a run. */
    String getReason() {
        return reason;
    }
}
