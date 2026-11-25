// Modified 2026-11-25 (DFRoot GZIF): verify one durable claim per boot, coalescing, and fail-closed ordering. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): assert exactly-once finish on every claim path. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): cover claim-time component OFF, unreadable gate, and late queued claims. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/** Pure JVM contract tests for the durable boot-claim protocol. */
public class BootClaimTest {
    private static final String LOCKED = Intent.ACTION_LOCKED_BOOT_COMPLETED;
    private static final String UNLOCKED = Intent.ACTION_BOOT_COMPLETED;

    @Test
    public void lockedThenUnlockedClaimsOnceAndStartsOnce() {
        BootClaimFakes.MutableReader reader = new BootClaimFakes.MutableReader(7L);
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator claims = BootClaimCoordinator.inline(reader, store);

        BootReceiver.dispatchAction(null, LOCKED, claims,
                new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatchAction(null, UNLOCKED, claims,
                new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(7L, store.claimed);
        assertEquals(2, store.attempts.size());
    }

    @Test
    public void unlockedThenLockedClaimsOnceAndStartsOnce() {
        BootClaimFakes.MutableReader reader = new BootClaimFakes.MutableReader(9L);
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator claims = BootClaimCoordinator.inline(reader, store);

        BootReceiver.dispatchAction(null, UNLOCKED, claims,
                new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatchAction(null, LOCKED, claims,
                new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(2, store.attempts.size());
    }

    @Test
    public void duplicateBroadcastWithinBootNeverStartsASecondService() {
        BootClaimFakes.MutableReader reader = new BootClaimFakes.MutableReader(11L);
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator claims = BootClaimCoordinator.inline(reader, store);

        for (int i = 0; i < 5; i++) {
            BootReceiver.dispatchAction(null, i % 2 == 0 ? LOCKED : UNLOCKED, claims,
                    new BootClaimFakes.Finisher(), starter);
        }

        assertEquals(1, starter.calls);
        assertEquals(5, store.attempts.size());
    }

    @Test
    public void processRestartWithPersistedClaimDoesNotStartAgain() {
        BootClaimFakes.MemoryStore firstProcess = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter firstStarter = new BootClaimFakes.Starter();
        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(12L), firstProcess)
                .submit(null,new BootClaimFakes.Finisher(), firstStarter);
        assertEquals(1, firstStarter.calls);

        // New process, new store instance, same durable value and same boot count.
        BootClaimFakes.MemoryStore restarted =
                new BootClaimFakes.MemoryStore(firstProcess.lastClaimed());
        BootClaimFakes.Starter restartStarter = new BootClaimFakes.Starter();
        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(12L), restarted)
                .submit(null,new BootClaimFakes.Finisher(), restartStarter);

        assertEquals(0, restartStarter.calls);
        assertEquals(12L, restarted.lastClaimed());
    }

    @Test
    public void unavailableSettingNotFoundAndNonPositiveCountsFailClosed() {
        for (Long count : new Long[] {null, 0L, -1L, (long) Integer.MIN_VALUE}) {
            BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
            BootClaimFakes.Starter starter = new BootClaimFakes.Starter();

            BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(count), store)
                    .submit(null,new BootClaimFakes.Finisher(), starter);

            assertEquals("count " + count + " must not start", 0, starter.calls);
            assertEquals(0, store.attempts.size());
            assertEquals(0L, store.lastClaimed());
        }
    }

    @Test
    public void failedDurablePersistenceDoesNotStartTheService() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        store.commitFails = true;
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();

        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(13L), store)
                .submit(null,new BootClaimFakes.Finisher(), starter);

        assertEquals(0, starter.calls);
        assertEquals(0L, store.lastClaimed());
    }

    @Test
    public void thrownDurablePersistenceDoesNotStartTheService() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        store.commitThrows = true;
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();

        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(14L), store)
                .submit(null,finisher, starter);

        assertEquals(0, starter.calls);
        assertEquals(1, finisher.calls);
    }

    @Test
    public void serviceStartFailureAfterClaimIsNotRetriedThisBoot() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        starter.fail = true;
        BootClaimCoordinator claims =
                BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(15L), store);

        claims.submit(null,new BootClaimFakes.Finisher(), starter);
        starter.fail = false;
        claims.submit(null,new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(15L, store.lastClaimed());
    }

    @Test
    public void nextBootIsAcceptedAfterAnEarlierClaim() {
        BootClaimFakes.MutableReader reader = new BootClaimFakes.MutableReader(16L);
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator claims = BootClaimCoordinator.inline(reader, store);

        claims.submit(null,new BootClaimFakes.Finisher(), starter);
        reader.value = 17L;
        claims.submit(null,new BootClaimFakes.Finisher(), starter);

        assertEquals(2, starter.calls);
        assertEquals(17L, store.lastClaimed());
    }

    @Test
    public void staleOrRegressedBootCountIsNotClaimed() {
        BootClaimFakes.MutableReader reader = new BootClaimFakes.MutableReader(20L);
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator claims = BootClaimCoordinator.inline(reader, store);

        claims.submit(null,new BootClaimFakes.Finisher(), starter);
        reader.value = 19L;
        claims.submit(null,new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(20L, store.lastClaimed());
    }

    @Test
    public void serviceStartOccursOnlyAfterSuccessfulCheckedClaim() {
        List<String> order = new ArrayList<>();
        BootClaimStore store = new BootClaimStore() {
            @Override
            public long lastClaimed() {
                return 0L;
            }

            @Override
            public boolean claim(long bootCount) {
                order.add("claim");
                return true;
            }
        };
        BootReceiver.ServiceStarter starter = context -> order.add("start");

        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(21L), store)
                .submit(null,new BootClaimFakes.Finisher(), starter);

        assertEquals(2, order.size());
        assertEquals("claim", order.get(0));
        assertEquals("start", order.get(1));
    }

    @Test
    public void noServiceStartWhenDurableClaimRejectsEvenIfStarterWouldSucceed() {
        List<String> order = new ArrayList<>();
        BootClaimStore store = new BootClaimStore() {
            @Override
            public long lastClaimed() {
                return 21L;
            }

            @Override
            public boolean claim(long bootCount) {
                order.add("claim-rejected");
                return false;
            }
        };
        BootReceiver.ServiceStarter starter = context -> order.add("start");

        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(21L), store)
                .submit(null,new BootClaimFakes.Finisher(), starter);

        assertEquals(1, order.size());
        assertEquals("claim-rejected", order.get(0));
    }

    @Test
    public void everyClaimPathFinishesThePendingResultExactlyOnce() {
        BootClaimFakes.MutableReader reader = new BootClaimFakes.MutableReader(22L);
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator claims = BootClaimCoordinator.inline(reader, store);

        assertFinishesOnce("accepted/success", claims, starter);
        assertEquals(1, starter.calls);

        reader.value = null;
        assertFinishesOnce("unavailable (null)", claims, starter);
        reader.value = 0L;
        assertFinishesOnce("non-positive (zero)", claims, starter);
        reader.value = -4L;
        assertFinishesOnce("non-positive (negative)", claims, starter);

        // Same count again: the durable claim rejects a duplicate.
        reader.value = 22L;
        assertFinishesOnce("duplicate claim", claims, starter);
        assertEquals(2, store.attempts.size());

        BootClaimFakes.MemoryStore failedCommit = new BootClaimFakes.MemoryStore();
        failedCommit.commitFails = true;
        assertFinishesOnce("failed commit",
                BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(23L), failedCommit),
                starter);

        BootClaimFakes.MemoryStore throwingStore = new BootClaimFakes.MemoryStore();
        throwingStore.commitThrows = true;
        assertFinishesOnce("exception from store",
                BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(24L), throwingStore),
                starter);

        assertFinishesOnce("exception from reader",
                BootClaimCoordinator.inline(new BootClaimFakes.ThrowingReader(),
                        new BootClaimFakes.MemoryStore()), starter);

        BootClaimFakes.Starter failingStarter = new BootClaimFakes.Starter();
        failingStarter.fail = true;
        assertFinishesOnce("service-start exception", BootClaimCoordinator.inline(
                new BootClaimFakes.MutableReader(25L), new BootClaimFakes.MemoryStore()), failingStarter);

        BootClaimFakes.RejectingExecutor rejecting = new BootClaimFakes.RejectingExecutor();
        assertFinishesOnce("executor rejection", new BootClaimCoordinator(
                new BootClaimFakes.MutableReader(26L), new BootClaimFakes.MemoryStore(), rejecting,
                BootClaimCoordinator.Logger.NO_OP, false), starter);
        assertEquals(1, rejecting.calls);

        // Only the accepted path reached the starter; failingStarter counted its own call.
        assertEquals(1, starter.calls);
        assertEquals(1, failingStarter.calls);
        assertEquals(22L, store.lastClaimed());
    }

    @Test
    public void failedCommitFinishesExactlyOnceWithoutStarting() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        store.commitFails = true;
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();

        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(27L), store)
                .submit(null, finisher, starter);

        assertEquals(1, finisher.calls);
        assertEquals(0, starter.calls);
        assertEquals(0L, store.lastClaimed());
    }

    @Test
    public void throwingReaderFinishesExactlyOnceWithoutStarting() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();

        BootClaimCoordinator.inline(new BootClaimFakes.ThrowingReader(), store)
                .submit(null, finisher, starter);

        assertEquals(1, finisher.calls);
        assertEquals(0, starter.calls);
        assertEquals(0, store.attempts.size());
    }

    @Test
    public void throwingStoreFinishesExactlyOnceWithoutStarting() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        store.commitThrows = true;
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();

        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(28L), store)
                .submit(null, finisher, starter);

        assertEquals(1, finisher.calls);
        assertEquals(0, starter.calls);
        assertEquals(1, store.attempts.size());
    }

    @Test
    public void serviceStartExceptionFinishesExactlyOnceWithoutRetry() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        starter.fail = true;
        BootClaimFakes.Finisher first = new BootClaimFakes.Finisher();
        BootClaimCoordinator claims =
                BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(29L), store);

        claims.submit(null, first, starter);
        starter.fail = false;
        BootClaimFakes.Finisher second = new BootClaimFakes.Finisher();
        claims.submit(null, second, starter);

        assertEquals(1, first.calls);
        assertEquals(1, second.calls);
        assertEquals(1, starter.calls);
        assertEquals(29L, store.lastClaimed());
    }

    @Test
    public void executorRejectionFinishesExactlyOnceAndNeverRunsTheClaim() {
        BootClaimFakes.RejectingExecutor executor = new BootClaimFakes.RejectingExecutor();
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();

        new BootClaimCoordinator(new BootClaimFakes.MutableReader(30L), store, executor,
                BootClaimCoordinator.Logger.NO_OP, false).submit(null, finisher, starter);

        assertEquals(1, finisher.calls);
        assertEquals(1, executor.calls);
        assertEquals(0, store.attempts.size());
        assertEquals(0, starter.calls);
    }

    private static void assertFinishesOnce(String path, BootClaimCoordinator claims,
                                           BootReceiver.ServiceStarter starter) {
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        claims.submit(null, finisher, starter);
        assertEquals(path + " must finish the pending result exactly once", 1, finisher.calls);
    }

    @Test
    public void disabledOrUnreadableComponentNeverClaimsOrStartsButFinishes() {
        for (BootClaimCoordinator.BootComponentGate.State state : new BootClaimCoordinator.BootComponentGate.State[] {
                BootClaimCoordinator.BootComponentGate.State.DISABLED, BootClaimCoordinator.BootComponentGate.State.UNREADABLE}) {
            BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
            BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
            BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();

            BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(31L), store,
                    new MutableGate(state)).submit(null, finisher, starter);

            assertEquals(state + " must not claim", 0, store.attempts.size());
            assertEquals(state + " must not start", 0, starter.calls);
            assertEquals(state + " must still finish", 1, finisher.calls);
        }

        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        MutableGate throwing = new MutableGate(BootClaimCoordinator.BootComponentGate.State.ENABLED);
        throwing.throwsOnRead = true;

        BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(32L), store, throwing)
                .submit(null, finisher, starter);

        assertEquals(0, store.attempts.size());
        assertEquals(0, starter.calls);
        assertEquals(1, finisher.calls);
    }

    @Test
    public void enabledComponentStillClaimsAndStartsExactlyOnce() {
        BootClaimFakes.MutableReader reader = new BootClaimFakes.MutableReader(33L);
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        MutableGate gate = new MutableGate(BootClaimCoordinator.BootComponentGate.State.ENABLED);
        BootClaimCoordinator claims = BootClaimCoordinator.inline(reader, store, gate);

        BootReceiver.dispatchAction(null, LOCKED, claims, new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatchAction(null, UNLOCKED, claims, new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(33L, store.lastClaimed());
        assertEquals(2, gate.readCalls);
    }

    @Test
    public void queuedBootRequestAfterOffDoesNotClaimOrStart() {
        MutableGate gate = new MutableGate(BootClaimCoordinator.BootComponentGate.State.ENABLED);
        CapturingExecutor executor = new CapturingExecutor();
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        BootClaimCoordinator claims = new BootClaimCoordinator(
                new BootClaimFakes.MutableReader(34L), store, gate, executor,
                BootClaimCoordinator.Logger.NO_OP, false);

        claims.submit(null, finisher, starter);

        assertEquals(1, executor.tasks.size());
        assertEquals("gate must be checked at claim time, not submit time", 0, gate.readCalls);

        // The owner turns Start at Boot OFF while the broadcast is still queued.
        gate.state = BootClaimCoordinator.BootComponentGate.State.DISABLED;
        executor.runAll();

        assertEquals(1, gate.readCalls);
        assertEquals(0, store.attempts.size());
        assertEquals(0L, store.lastClaimed());
        assertEquals(0, starter.calls);
        assertEquals(1, finisher.calls);
    }

    @Test
    public void queuedBootRequestWithUnreadableComponentFailsClosed() {
        MutableGate gate = new MutableGate(BootClaimCoordinator.BootComponentGate.State.ENABLED);
        CapturingExecutor executor = new CapturingExecutor();
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        new BootClaimCoordinator(new BootClaimFakes.MutableReader(35L), store, gate, executor,
                BootClaimCoordinator.Logger.NO_OP, false).submit(null, finisher, starter);

        gate.throwsOnRead = true;
        executor.runAll();

        assertEquals(0, store.attempts.size());
        assertEquals(0, starter.calls);
        assertEquals(1, finisher.calls);
    }

    @Test
    public void rejectedActionsNeverReachTheClaim() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator claims = BootClaimCoordinator.inline(
                new BootClaimFakes.MutableReader(23L), store);

        BootReceiver.dispatchAction(null, null, claims, new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatchAction(null, "", claims, new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatchAction(null, "df.root.action.MANUAL", claims,
                new BootClaimFakes.Finisher(), starter);

        assertEquals(0, store.attempts.size());
        assertEquals(0, starter.calls);
    }

    /** Deterministic claim-time component state; can also simulate an unreadable setting. */
    private static final class MutableGate implements BootClaimCoordinator.BootComponentGate {
        State state;
        boolean throwsOnRead;
        int readCalls;

        MutableGate(State state) {
            this.state = state;
        }

        @Override
        public State read(Context context) {
            readCalls++;
            if (throwsOnRead) {
                throw new IllegalStateException("component setting unreadable");
            }
            return state;
        }
    }

    /** Holds the claim task so OFF can be set after submit but before the claim runs. */
    private static final class CapturingExecutor implements java.util.concurrent.Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}
