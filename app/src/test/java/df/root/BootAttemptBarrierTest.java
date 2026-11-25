// Modified 2026-11-25 (DFRoot GZIF): prove a persisted unconfirmed attempt suppresses boot claim and service start. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): assert the readiness check precedes claim and service start. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;

import android.content.Intent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure JVM boot-path tests for the process-independent admission barrier. */
public class BootAttemptBarrierTest {
    @Test
    public void unconfirmedAttemptFromPriorProcessSuppressesClaimAndStart() throws Exception {
        OperationAttemptStore attempts = OperationAttemptStore.inMemory();
        attempts.publish(new OperationAttempt("prior-boot-run",
                RunConfig.ExecutionMode.UNATTENDED_BOOT, 60L, true, "unconfirmed"));
        BootClaimFakes.MutableReader reader = new BootClaimFakes.MutableReader(61L);
        BootClaimFakes.MemoryStore claims = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator coordinator = new BootClaimCoordinator(reader, claims, attempts,
                Runnable::run, BootClaimCoordinator.Logger.NO_OP, false);

        BootReceiver.dispatchAction(null, Intent.ACTION_LOCKED_BOOT_COMPLETED, coordinator,
                new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatchAction(null, Intent.ACTION_BOOT_COMPLETED, coordinator,
                new BootClaimFakes.Finisher(), starter);

        assertEquals(0, claims.attempts.size());
        assertEquals(0L, claims.lastClaimed());
        assertEquals(0, starter.calls);
    }

    @Test
    public void ownerAcknowledgedClearAllowsExactlyOneLaterBootAttempt() throws Exception {
        OperationAttemptStore attempts = OperationAttemptStore.inMemory();
        attempts.publish(new OperationAttempt("prior-boot-run",
                RunConfig.ExecutionMode.UNATTENDED_BOOT, 60L, true, "unconfirmed"));
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimFakes.MemoryStore claims = new BootClaimFakes.MemoryStore();
        BootClaimCoordinator coordinator = new BootClaimCoordinator(
                new BootClaimFakes.MutableReader(60L), claims, attempts,
                Runnable::run, BootClaimCoordinator.Logger.NO_OP, false);

        BootReceiver.dispatchAction(null, Intent.ACTION_BOOT_COMPLETED, coordinator,
                new BootClaimFakes.Finisher(), starter);
        assertEquals(0, starter.calls);

        // Explicit owner recovery is the only way to remove the barrier.
        assertEquals("prior-boot-run", attempts.load().getOperationId());
        assertEquals(true, attempts.clear("prior-boot-run"));

        BootReceiver.dispatchAction(null, Intent.ACTION_LOCKED_BOOT_COMPLETED, coordinator,
                new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatchAction(null, Intent.ACTION_BOOT_COMPLETED, coordinator,
                new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(2, claims.attempts.size());
        assertEquals(60L, claims.lastClaimed());
    }

    @Test
    public void admissionReadinessIsCheckedBeforeClaimAndServiceStart() {
        List<String> order = new ArrayList<>();
        OperationAttemptStore attempts = new OperationAttemptStore() {
            @Override
            public OperationAttempt load() {
                order.add("readiness");
                return null;
            }

            @Override
            public boolean publish(OperationAttempt attempt) {
                return false;
            }

            @Override
            public boolean clear(String operationId) {
                return false;
            }
        };
        BootClaimStore claims = new BootClaimStore() {
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

        new BootClaimCoordinator(new BootClaimFakes.MutableReader(63L), claims, attempts,
                Runnable::run, BootClaimCoordinator.Logger.NO_OP, false)
                .submit(null, new BootClaimFakes.Finisher(), starter);

        assertEquals(Arrays.asList("readiness", "claim", "start"), order);
    }

    @Test
    public void unreadablePersistedAttemptFailsClosedWithoutClaimOrStart() throws Exception {
        OperationAttemptStore broken = new OperationAttemptStore() {
            @Override
            public OperationAttempt load() throws Failure {
                throw new Failure("attempt file unreadable");
            }

            @Override
            public boolean publish(OperationAttempt attempt) {
                return false;
            }

            @Override
            public boolean clear(String operationId) {
                return false;
            }
        };
        BootClaimFakes.MemoryStore claims = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator coordinator = new BootClaimCoordinator(
                new BootClaimFakes.MutableReader(62L), claims, broken,
                Runnable::run, BootClaimCoordinator.Logger.NO_OP, false);

        BootReceiver.dispatchAction(null, Intent.ACTION_BOOT_COMPLETED, coordinator,
                new BootClaimFakes.Finisher(), starter);

        assertEquals(0, claims.attempts.size());
        assertEquals(0, starter.calls);
    }
}
