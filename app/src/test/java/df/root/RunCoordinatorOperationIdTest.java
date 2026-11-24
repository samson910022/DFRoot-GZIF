// Modified 2026-11-24 (DFRoot GZIF): prove the admitted operation ID crosses the coordinator engine boundary. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class RunCoordinatorOperationIdTest {
    @Test
    public void engineReceivesExactlyTheAdmittedOperationId() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new Store(), Runnable::run, Runnable::run);
        repository.start();
        AtomicReference<String> observed = new AtomicReference<>();
        RunCoordinator.Engine engine = (context, admission, mode, operationId, events, reporter) -> {
            observed.set(operationId);
            return NativeResultMapper.map(0);
        };
        RunCoordinator coordinator = new RunCoordinator(
                repository, Runnable::run, Runnable::run, engine, 0L);

        RunCoordinator.Submission submission = coordinator.submit(
                RuntimeEnvironment.getApplication(), RunConfig.ExecutionMode.MANUAL,
                () -> {});

        assertTrue(submission.isAccepted());
        assertEquals(submission.getOperationId(), observed.get());
        assertEquals(submission.getOperationId(), coordinator.getSnapshot().getOperationId());
        assertNotNull(observed.get());
    }

    private static final class Store implements ConfigurationStore {
        @Override
        public RunConfig load() {
            return RunConfig.defaults();
        }

        @Override
        public boolean write(RunConfig updated) {
            return true;
        }
    }
}
