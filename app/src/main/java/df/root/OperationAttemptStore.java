// Modified 2026-11-25 (DFRoot GZIF): define the checked, process-independent attempt barrier boundary. See NOTICE.
package df.root;

/**
 * Durable barrier for one admitted operation. Every operation is synchronous
 * and checked: a false result or a {@link Failure} must fail closed. This
 * boundary never writes configuration, boot-claim, or native artifact state.
 */
interface OperationAttemptStore {
    /** Checked persistence failure; callers must not proceed on this path. */
    final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }

        Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Persisted record, or {@code null} when no attempt has been published. */
    OperationAttempt load() throws Failure;

    /**
     * Publishes an unconfirmed attempt. False when a different unconfirmed
     * attempt already exists or the checked commit did not persist; never
     * overwrites another operation's barrier.
     */
    boolean publish(OperationAttempt attempt) throws Failure;

    /** Compare-and-clear by operation ID. False for a mismatch or failed commit. */
    boolean clear(String operationId) throws Failure;

    /** Side-effect-free store used by pure seams and fail-closed test wiring. */
    static OperationAttemptStore inMemory() {
        return new InMemoryOperationAttemptStore();
    }
}
