// Modified 2026-11-24 (DFRoot GZIF): verify boot waits for verified cold-start configuration and transfers a live admission. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class BootReceiverBoundaryTest {
    private static final RunConfig CONFIG = RunConfig.defaults()
            .withManagerPackage(SuManager.NEXT.getPackageName());
    private static final long PRODUCTION_INITIALIZATION_TIMEOUT_MS = 10_000L;
    private static final long BOUNDED_LATCH_WAIT_SECONDS = 10L;
    private Context context;

    @Before
    public void setUp() {
        ConfigurationRepository.resetForTesting();
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        ConfigurationRepository.resetForTesting();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void coldProcessWaitsForInitializationThenRunsExactlyOnce() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.dispatch();

        assertEquals(ConfigurationRepository.State.SAVING, fixture.repository.getState());
        assertFalse(fixture.repository.isFrozen());
        assertEquals(0, fixture.runner.calls);
        fixture.initialize();

        fixture.runAdmission();

        assertEquals(1, fixture.runner.calls);
        assertTrue(fixture.runner.sawActiveAdmission);
        assertEquals(RunConfig.ExecutionMode.UNATTENDED_BOOT, fixture.runner.mode);
        assertSame(CONFIG, fixture.runner.snapshot);
        assertEquals(1, fixture.lease.releases);
        assertFalse(fixture.repository.isFrozen());
        assertTrue(fixture.repository.setDisableModules(true));
    }

    @Test
    public void realRawThreadWaitsOffMainAndOwnsCleanup() throws Exception {
        Fixture fixture = new Fixture(CONFIG, PRODUCTION_INITIALIZATION_TIMEOUT_MS);
        CountDownLatch runnerEntered = new CountDownLatch(1);
        CountDownLatch admissionReleased = new CountDownLatch(1);
        AtomicReference<Thread> runnerThread = new AtomicReference<>();
        AtomicBoolean sawActiveAdmission = new AtomicBoolean();
        fixture.repository.addListener(() -> {
            if (runnerThread.get() != null && !fixture.repository.isFrozen()) {
                admissionReleased.countDown();
            }
        });
        BootReceiver.BootReceiverDependencies dependencies = new BootReceiver.BootReceiverDependencies(
                fixture.repository,
                PRODUCTION_INITIALIZATION_TIMEOUT_MS,
                fixture.probe,
                ignored -> context,
                fixture.wakeFactory,
                runnable -> new Thread(runnable, "dfroot-boot-test").start(),
                (deContext, admission, mode, reporter) -> {
                    runnerThread.set(Thread.currentThread());
                    runnerEntered.countDown();
                    sawActiveAdmission.set(admission.isActive());
                    return NativeResultMapper.map(0);
                });

        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED), dependencies);
        assertEquals(ConfigurationRepository.State.SAVING, fixture.repository.getState());
        fixture.initialize();
        assertTrue(runnerEntered.await(BOUNDED_LATCH_WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(admissionReleased.await(BOUNDED_LATCH_WAIT_SECONDS, TimeUnit.SECONDS));

        assertNotSame(Thread.currentThread(), runnerThread.get());
        assertEquals("dfroot-boot-test", runnerThread.get().getName());
        assertTrue(sawActiveAdmission.get());
        assertFalse(fixture.repository.isFrozen());
        assertEquals(1, fixture.lease.releases);
    }

    @Test
    public void initializationTimeoutFailsClosedWithoutRunnerOrFreeze() {
        Fixture fixture = new Fixture(CONFIG, 0L);
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
        assertEquals(ConfigurationRepository.State.SAVING, fixture.repository.getState());
    }

    @Test
    public void initializationErrorFailsClosedWithoutRunnerOrFreeze() {
        Fixture fixture = new Fixture(null, 1_000L);
        fixture.initialize();
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
        assertEquals(ConfigurationRepository.State.ERROR, fixture.repository.getState());
    }

    @Test
    public void savingConfigurationRejectsBootAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        assertTrue(fixture.repository.setDisableModules(true));
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
        assertTrue(fixture.repository.isWritePending());
    }

    @Test
    public void errorConfigurationRejectsBootAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.store.results.add(false);
        fixture.store.results.add(true);
        assertTrue(fixture.repository.setDisableModules(true));
        fixture.configExecutor.runNext();
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
        assertEquals(ConfigurationRepository.State.ERROR, fixture.repository.getState());
    }

    @Test
    public void frozenConfigurationRejectsBootAdmissionWithoutStealingTheFreeze() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        ConfigurationRepository.RunAdmission active = fixture.repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        fixture.dispatch();

        fixture.runAdmission();

        assertEquals(0, fixture.runner.calls);
        assertTrue(fixture.repository.isFrozen());
        active.close();
        fixture.assertRejected();
    }

    @Test
    public void currentManualConsentCannotAdmitUnattendedBoot() {
        RunConfig manuallyAcknowledged = new RunConfig(SuManager.NEXT.getPackageName(), true,
                false, ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 3);
        Fixture fixture = new Fixture(manuallyAcknowledged, 1_000L);
        fixture.initialize();
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
    }

    @Test
    public void presentHookMarkerSkipsRunnerAndReleasesAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.probe.present = true;
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
        assertEquals(1, fixture.probe.calls);
    }

    @Test
    public void hookMarkerIOExceptionSkipsRunnerAndReleasesAdmission() {
        assertProbeFailureRejects(new IOException("marker unreadable"));
    }

    @Test
    public void hookMarkerSecurityExceptionSkipsRunnerAndReleasesAdmission() {
        assertProbeFailureRejects(new SecurityException("marker denied"));
    }

    @Test
    public void deviceProtectedContextFailureReleasesAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.contextFailure = new IllegalStateException("DE context unavailable");
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
    }

    @Test
    public void missingPowerManagerReleasesAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.wakeFactory.failure = new IllegalStateException("PowerManager unavailable");
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
    }

    @Test
    public void newWakeLockFailureReleasesAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.wakeFactory.failure = new IllegalStateException("newWakeLock failed");
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
    }

    @Test
    public void wakeLockAcquireFailureReleasesAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.controller.acquireFailure = new IllegalStateException("acquire failed");
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
        assertEquals(1, fixture.controller.acquireCalls);
    }

    @Test
    public void nullWakeLockLeaseFailsClosed() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.controller.nullLease = true;
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
    }

    @Test
    public void threadStartFailureReleasesWakeLockAndAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.threadStarter.failStartNumber = 2;
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
        assertEquals(1, fixture.lease.releases);
    }

    @Test
    public void threadStartFailureStillClosesAdmissionWhenWakeLockReleaseThrows() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.threadStarter.failStartNumber = 2;
        fixture.lease.releaseFailure = new IllegalStateException("release failed");
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
        assertEquals(1, fixture.lease.releases);
        assertTrue(fixture.repository.setDisableModules(true));
    }

    @Test
    public void runnerReturnReleasesWakeLockAndAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.runner.result = NativeResultMapper.map(0);
        fixture.dispatch();
        fixture.runAdmission();

        assertEquals(1, fixture.runner.calls);
        assertEquals(1, fixture.lease.releases);
        assertFalse(fixture.repository.isFrozen());
    }

    @Test
    public void runnerExceptionReleasesWakeLockAndAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.runner.failure = new IllegalStateException("runner failed");
        fixture.dispatch();
        fixture.runAdmission();

        assertEquals(1, fixture.runner.calls);
        assertEquals(1, fixture.lease.releases);
        assertFalse(fixture.repository.isFrozen());
    }

    @Test
    public void wakeLockReleaseExceptionDoesNotLeakTheAdmission() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.lease.releaseFailure = new IllegalStateException("release failed");
        fixture.runner.result = NativeResultMapper.map(0);
        fixture.dispatch();
        fixture.runAdmission();

        assertEquals(1, fixture.lease.releases);
        assertFalse(fixture.repository.isFrozen());
        assertTrue(fixture.repository.setDisableModules(true));
    }

    @Test
    public void rawAdmissionThreadStartRejectionDoesNoAdmissionWork() {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.threadStarter.failStartNumber = 1;

        fixture.dispatch();

        fixture.assertRejected();
        assertFalse(fixture.repository.isFrozen());
    }

    private void assertProbeFailureRejects(Exception failure) {
        Fixture fixture = new Fixture(CONFIG, 1_000L);
        fixture.initialize();
        fixture.probe.failure = failure;
        fixture.dispatch();

        fixture.runAdmission();

        fixture.assertRejected();
    }

    private final class Fixture {
        final long timeoutMillis;
        final FakeStore store;
        final ConfigExecutor configExecutor;
        final ConfigurationRepository repository;
        final ControlledThreadStarter threadStarter = new ControlledThreadStarter();
        final FakeProbe probe = new FakeProbe();
        final FakeWakeLockFactory wakeFactory = new FakeWakeLockFactory();
        final FakeWakeLockController controller = wakeFactory.controller;
        final FakeWakeLock lease = controller.lease;
        final RecordingRunner runner = new RecordingRunner();
        RuntimeException contextFailure;

        Fixture(RunConfig loaded, long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            store = new FakeStore(loaded);
            configExecutor = new ConfigExecutor();
            repository = new ConfigurationRepository(store, configExecutor, Runnable::run);
            repository.start();
            dependencies();
        }

        private BootReceiver.BootReceiverDependencies dependencies() {
            return new BootReceiver.BootReceiverDependencies(
                    repository,
                    timeoutMillis,
                    probe,
                    ignored -> {
                        if (contextFailure != null) {
                            throw contextFailure;
                        }
                        return context;
                    },
                    wakeFactory,
                    threadStarter,
                    runner);
        }

        void initialize() {
            configExecutor.runNext();
        }

        void dispatch() {
            BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                    dependencies());
        }

        void runAdmission() {
            threadStarter.runNext();
        }

        void assertRejected() {
            assertEquals(0, runner.calls);
            assertFalse(repository.isFrozen());
        }
    }

    private static final class FakeStore implements ConfigurationStore {
        RunConfig cached;
        RunConfig durable;
        final Queue<Boolean> results = new ArrayDeque<>();

        FakeStore(RunConfig loaded) {
            cached = loaded;
            durable = loaded;
        }

        @Override
        public RunConfig load() {
            return cached;
        }

        @Override
        public boolean write(RunConfig config) {
            cached = config;
            boolean result = results.isEmpty() || results.remove();
            if (result) {
                durable = config;
            }
            return result;
        }
    }

    private static final class FakeProbe implements BootReceiver.BootReceiverDependencies.HookProbe {
        boolean present;
        Exception failure;
        int calls;

        @Override
        public boolean isPresent() throws IOException {
            calls++;
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            if (failure instanceof SecurityException) {
                throw (SecurityException) failure;
            }
            return present;
        }
    }

    private static final class FakeWakeLockFactory
            implements BootReceiver.BootReceiverDependencies.WakeLockFactory {
        final FakeWakeLockController controller = new FakeWakeLockController();
        RuntimeException failure;

        @Override
        public BootReceiver.BootReceiverDependencies.WakeLockController create(Context context) {
            if (failure != null) {
                throw failure;
            }
            return controller;
        }
    }

    private static final class FakeWakeLockController
            implements BootReceiver.BootReceiverDependencies.WakeLockController {
        final FakeWakeLock lease = new FakeWakeLock();
        RuntimeException acquireFailure;
        boolean nullLease;
        int acquireCalls;

        @Override
        public BootReceiver.BootReceiverDependencies.WakeLock acquire() {
            acquireCalls++;
            if (acquireFailure != null) {
                throw acquireFailure;
            }
            return nullLease ? null : lease;
        }
    }

    private static final class FakeWakeLock implements BootReceiver.BootReceiverDependencies.WakeLock {
        int releases;
        RuntimeException releaseFailure;

        @Override
        public void release() {
            releases++;
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }
    }

    private static final class RecordingRunner implements BootReceiver.BootReceiverDependencies.Runner {
        int calls;
        boolean sawActiveAdmission;
        RunConfig snapshot;
        RunConfig.ExecutionMode mode;
        RunResult result;
        Exception failure;

        @Override
        public RunResult run(Context context, ConfigurationRepository.RunAdmission admission,
                             RunConfig.ExecutionMode mode, IReporter reporter) throws Exception {
            calls++;
            sawActiveAdmission = admission.isActive();
            snapshot = admission.getSnapshot();
            this.mode = mode;
            assertNotNull(context);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static final class ConfigExecutor implements Executor {
        final Queue<Runnable> commands = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.remove().run();
        }
    }

    private static final class ControlledThreadStarter
            implements BootReceiver.BootReceiverDependencies.ThreadStarter {
        final Queue<Runnable> commands = new ArrayDeque<>();
        int startCalls;
        int failStartNumber;

        @Override
        public void start(Runnable runnable) {
            startCalls++;
            if (startCalls == failStartNumber) {
                throw new IllegalStateException("thread start failed");
            }
            if (startCalls == 1) {
                commands.add(runnable);
            } else {
                runnable.run();
            }
        }

        void runNext() {
            commands.remove().run();
        }
    }
}
