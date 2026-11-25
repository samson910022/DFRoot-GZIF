// Modified 2026-11-25 (DFRoot GZIF): define the checked, process-independent attempt barrier boundary. See NOTICE.
package df.root;

/**
 * Durable barrier for one admitted operation. Every operation is synchronous
 * and checked: a false result or a {@link Failure} must fail closed. This
 * boundary never writes configuration, boot-claim, or native artifact state.
 *
 * <p><b>Fail-closed read contract.</b> A durable implementation may return
 * {@code null} from {@link #load()} only when it can prove that NO attempt key
 * exists in its store. If any attempt key exists but the record is incomplete,
 * corrupt, or unparseable, {@code load()} must throw {@link Failure}: a partial
 * record is never permission to start. Absence must be established by checking
 * key presence, never by trusting a default value.</p>
 *
 * <p><b>Uncertain commit contract.</b> After any checked commit in
 * {@link #publish(OperationAttempt)} or {@link #clear(String)} returns false or
 * throws, that store instance is UNCERTAIN: a process-local preference cache may
 * already have been mutated even though durable state was not. That instance
 * must then fail closed on every subsequent {@code load()}, {@code publish()},
 * and {@code clear()} (throw Failure, or return false where the boolean contract
 * requires) instead of treating its cache as proof of durable absence. Only a
 * store created after a PROCESS restart re-reads durable state; a new wrapper
 * in the same process shares the cached SharedPreferences view.</p>
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

    /**
     * Persisted record, or {@code null} only when no attempt key exists at all.
     * Throws {@link Failure} for an existing-but-incomplete/corrupt record, a
     * corrupt value type, or an instance left uncertain by a failed commit.
     */
    OperationAttempt load() throws Failure;

    /**
     * Publishes an unconfirmed attempt. False when a different unconfirmed
     * attempt already exists or the checked commit did not persist; never
     * overwrites another operation's barrier. Throws {@link Failure} if this
     * instance is uncertain after an earlier failed commit.
     */
    boolean publish(OperationAttempt attempt) throws Failure;

    /**
     * Compare-and-clear by operation ID. False for a mismatch, a null ID, or a
     * failed commit. A null ID returns false before the certainty check. Throws
     * {@link Failure} if this instance is uncertain after an earlier failed
     * commit.
     */
    boolean clear(String operationId) throws Failure;

    /** Side-effect-free store used by pure seams and fail-closed test wiring. */
    static OperationAttemptStore inMemory() {
        return new InMemoryOperationAttemptStore();
    }
}
