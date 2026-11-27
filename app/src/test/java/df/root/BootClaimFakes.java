// Modified 2026-11-25 (DFRoot GZIF): provide narrow fakes for durable boot-claim contract tests. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): add deterministic throwing-reader and rejecting-executor seams. See NOTICE.
package df.root;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Pure JVM fakes for the boot-claim seams; no Android runtime or privileged effects. */
final class BootClaimFakes {
    private BootClaimFakes() {}

    static final class MutableReader implements BootCountReader {
        Long value;

        MutableReader(Long value) {
            this.value = value;
        }

        @Override
        public Long read() {
            return value;
        }
    }

    static final class MemoryStore implements BootClaimStore {
        final List<Long> attempts = new ArrayList<>();
        long claimed;
        boolean commitFails;
        boolean commitThrows;

        MemoryStore() {
            this(0L);
        }

        MemoryStore(long claimed) {
            this.claimed = claimed;
        }

        @Override
        public long lastClaimed() {
            return claimed;
        }

        @Override
        public boolean claim(long bootCount) {
            attempts.add(bootCount);
            if (commitThrows) {
                throw new IllegalStateException("durable commit threw");
            }
            if (commitFails || bootCount <= claimed) {
                return false;
            }
            claimed = bootCount;
            return true;
        }
    }

    static final class Starter implements BootReceiver.ServiceStarter {
        final List<Context> contexts = new ArrayList<>();
        int calls;
        boolean fail;

        @Override
        public void startUnattendedBoot(Context context) {
            calls++;
            contexts.add(context);
            if (fail) {
                throw new IllegalStateException("foreground service start denied");
            }
        }
    }

    /** Reader that fails before any store access; models a broken Settings read. */
    static final class ThrowingReader implements BootCountReader {
        @Override
        public Long read() {
            throw new IllegalStateException("boot count read failed");
        }
    }

    /** Deterministic executor rejection without threads, sleeps, or real queues. */
    static final class RejectingExecutor implements Executor {
        int calls;

        @Override
        public void execute(Runnable command) {
            calls++;
            throw new RejectedExecutionException("claim worker rejected");
        }
    }

    static final class Finisher implements BootClaimCoordinator.Finisher {
        int calls;

        @Override
        public void finish() {
            calls++;
        }
    }
}
