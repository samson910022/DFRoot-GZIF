// Modified 2026-11-25 (DFRoot GZIF): add the side-effect-free attempt store for pure seams. See NOTICE.
package df.root;

import java.util.Objects;

/**
 * Process-local attempt store. It implements exactly the same CAS and checked
 * contract as the durable store but writes nothing outside this object. It has
 * no checked commit, so it never becomes uncertain and never claims to observe
 * another process's durable state.
 */
final class InMemoryOperationAttemptStore implements OperationAttemptStore {
    private final Object lock = new Object();
    private OperationAttempt attempt;

    @Override
    public OperationAttempt load() {
        synchronized (lock) {
            return attempt;
        }
    }

    @Override
    public boolean publish(OperationAttempt published) {
        Objects.requireNonNull(published, "attempt");
        synchronized (lock) {
            if (attempt != null && attempt.isUnconfirmed()
                    && !attempt.getOperationId().equals(published.getOperationId())) {
                return false;
            }
            attempt = published;
            return true;
        }
    }

    @Override
    public boolean clear(String operationId) {
        if (operationId == null) {
            return false;
        }
        synchronized (lock) {
            if (attempt == null || !attempt.getOperationId().equals(operationId)) {
                return false;
            }
            attempt = null;
            return true;
        }
    }
}
