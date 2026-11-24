// Modified 2026-11-24 (DFRoot GZIF): prove coordinator ownership spans child exit and final stdout EOF. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class RunCoordinatorProcessIntegrationTest {
    private static final long TEST_TIMEOUT_MS = 5_000L;
    private ExecutorService worker;

    @Before
    public void setUp() {
        worker = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws Exception {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void timedOutChildRetainsLeaseAndConfigurationUntilDelayedExit() throws Exception {
        ConfigurationRepository repository = repository();
        FakeProcess process = new FakeProcess();
        process.scriptedTimedWaits.add(false);
        try {
            AtomicReference<ConfigurationRepository.RunAdmission> admitted =
                    new AtomicReference<>();
            RunCoordinator.Engine engine = (context, admission, mode, operationId, events, reporter) -> {
                admitted.set(admission);
                ChildProcessSupervisor.Result result = new ChildProcessSupervisor(
                        ignored -> process, TEST_TIMEOUT_MS,
                        ExploitRunner.childListener(reporter, events))
                        .awaitExit(Collections.singletonList("fake-child"));
                return ExploitRunner.mapProcessResult(result);
            };
            RunCoordinator coordinator = new RunCoordinator(repository, worker, Runnable::run,
                    engine, TEST_TIMEOUT_MS);
            FakeLease lease = new FakeLease();
            CountDownLatch indeterminate = new CountDownLatch(1);
            CountDownLatch terminal = new CountDownLatch(1);
            coordinator.addListener(snapshot -> {
                if (snapshot.getState() == RunCoordinator.State.INDETERMINATE) {
                    indeterminate.countDown();
                }
                if (snapshot.getState() == RunCoordinator.State.TERMINAL) {
                    terminal.countDown();
                }
            });

            assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                    RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
            assertTrue(indeterminate.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(RunResult.Detail.PROCESS_TIMEOUT,
                    coordinator.getSnapshot().getResult().getDetail());
            assertTrue(process.isAlive());
            assertEquals(0, process.destroyCount.get());
            assertEquals(0, lease.closeCount.get());
            assertTrue(admitted.get().isActive());
            assertTrue(repository.isFrozen());

            process.exit(0);
            assertTrue(terminal.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(RunCoordinator.State.TERMINAL, coordinator.getSnapshot().getState());
            assertEquals(RunResult.Detail.PROCESS_TIMEOUT,
                    coordinator.getSnapshot().getResult().getDetail());
            assertEquals(ExploitRunner.RESULT_INDETERMINATE,
                    coordinator.getSnapshot().getResult().getCode());
            assertEquals(1, lease.closeCount.get());
            assertFalse(admitted.get().isActive());
            assertFalse(repository.isFrozen());
            assertEquals(0, process.destroyCount.get());
            assertEquals(0, process.destroyForciblyCount.get());
            assertEquals(1, process.stdinCloseCount.get());
        } finally {
            process.exit(0);
        }
    }

    @Test
    public void finalStdoutLineIsDrainedBeforeTerminalPublication() throws Exception {
        ConfigurationRepository repository = repository();
        FinalLineThenBlockingEofInputStream stdout = new FinalLineThenBlockingEofInputStream(
                "final child line\n");
        FakeProcess process = new FakeProcess(stdout);
        try {
            CountDownLatch finalLineReported = new CountDownLatch(1);
            AtomicInteger outputErrors = new AtomicInteger();
            AtomicReference<ConfigurationRepository.RunAdmission> admitted =
                    new AtomicReference<>();
            RunCoordinator.Engine engine = (context, admission, mode, operationId, events, reporter) -> {
                admitted.set(admission);
                IReporter observedReporter = message -> {
                    reporter.report(message);
                    if (message.equals("final child line\n")) {
                        finalLineReported.countDown();
                    }
                    if (message.startsWith("ERROR: child stdout failure:")) {
                        outputErrors.incrementAndGet();
                    }
                };
                ChildProcessSupervisor.Result result = new ChildProcessSupervisor(
                        ignored -> process, TEST_TIMEOUT_MS,
                        ExploitRunner.childListener(observedReporter, events))
                        .awaitExit(Collections.singletonList("fake-child"));
                return ExploitRunner.mapProcessResult(result);
            };
            RunCoordinator coordinator = new RunCoordinator(repository, worker, Runnable::run,
                    engine, TEST_TIMEOUT_MS);
            FakeLease lease = new FakeLease();
            CountDownLatch terminal = new CountDownLatch(1);
            coordinator.addListener(snapshot -> {
                if (snapshot.getState() == RunCoordinator.State.TERMINAL) {
                    terminal.countDown();
                }
            });

            assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                    RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
            assertTrue(finalLineReported.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertTrue(stdout.eofEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));

            process.exit(0);
            assertTrue(process.exitValueCalled.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertFalse("terminal published before stdout EOF",
                    terminal.await(250, TimeUnit.MILLISECONDS));
            assertEquals(RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
            assertEquals(0, lease.closeCount.get());
            assertTrue(admitted.get().isActive());
            assertTrue(repository.isFrozen());

            stdout.releaseEof.countDown();
            assertTrue(terminal.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            RunCoordinator.Snapshot snapshot = coordinator.getSnapshot();
            assertEquals(RunCoordinator.State.TERMINAL, snapshot.getState());
            assertTrue(snapshot.getResult().isSuccess());
            assertEquals(RunResult.Detail.LATE_LOAD_COMPLETED, snapshot.getResult().getDetail());
            assertTrue(snapshot.getLog().stream().anyMatch(
                    entry -> entry.getMessage().equals("final child line\n")));
            assertEquals(0, outputErrors.get());
            assertEquals(1, lease.closeCount.get());
            assertFalse(admitted.get().isActive());
            assertFalse(repository.isFrozen());
            assertEquals(0, process.destroyCount.get());
            assertEquals(0, process.destroyForciblyCount.get());
        } finally {
            stdout.releaseEof.countDown();
            stdout.close();
            process.exit(0);
        }
    }

    @Test
    public void interruptionDuringPostExitDrainKeepsFinalLineAndRestoresWorkerInterrupt()
            throws Exception {
        ConfigurationRepository repository = repository();
        GatedBeforeFinalLineInputStream stdout = new GatedBeforeFinalLineInputStream(
                "final line after interruption\n");
        FakeProcess process = new FakeProcess(stdout);
        try {
            CountDownLatch indeterminate = new CountDownLatch(1);
            CountDownLatch terminal = new CountDownLatch(1);
            AtomicInteger outputErrors = new AtomicInteger();
            AtomicInteger interruptionNotifications = new AtomicInteger();
            AtomicInteger admissionReleaseNotifications = new AtomicInteger();
            AtomicReference<ConfigurationRepository.RunAdmission> admitted =
                    new AtomicReference<>();
            AtomicReference<Thread> coordinatorWorker = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            RunCoordinator.Engine engine = (context, admission, mode, operationId, events, reporter) -> {
                coordinatorWorker.set(Thread.currentThread());
                admitted.set(admission);
                // Acquisition has already notified. Only the actual release notifies;
                // a duplicate admission close is a no-op and cannot inflate this count.
                repository.addListener(admissionReleaseNotifications::incrementAndGet);
                IReporter observedReporter = message -> {
                    reporter.report(message);
                    if (message.startsWith("ERROR: child stdout failure:")) {
                        outputErrors.incrementAndGet();
                    }
                };
                RunEventListener observedEvents = new RunEventListener() {
                    @Override
                    public void onTimeout() {
                        events.onTimeout();
                    }

                    @Override
                    public void onInterrupted() {
                        interruptionNotifications.incrementAndGet();
                        events.onInterrupted();
                    }
                };
                ChildProcessSupervisor.Result result = new ChildProcessSupervisor(
                        ignored -> process, TEST_TIMEOUT_MS,
                        ExploitRunner.childListener(observedReporter, observedEvents), 2_000L)
                        .awaitExit(Collections.singletonList("fake-child"));
                interruptRestored.set(Thread.currentThread().isInterrupted());
                return ExploitRunner.mapProcessResult(result);
            };
            RunCoordinator coordinator = new RunCoordinator(repository, worker, Runnable::run,
                    engine, TEST_TIMEOUT_MS);
            FakeLease lease = new FakeLease();
            coordinator.addListener(snapshot -> {
                if (snapshot.getState() == RunCoordinator.State.INDETERMINATE) {
                    indeterminate.countDown();
                }
                if (snapshot.getState() == RunCoordinator.State.TERMINAL) {
                    terminal.countDown();
                }
            });

            assertTrue(coordinator.submit(RuntimeEnvironment.getApplication(),
                    RunConfig.ExecutionMode.MANUAL, lease).isAccepted());
            assertTrue(stdout.finalLineGateEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(0, lease.closeCount.get());

            process.exit(0);
            assertTrue(process.exitValueCalled.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(RunCoordinator.State.RUNNING, coordinator.getSnapshot().getState());
            assertEquals(0, lease.closeCount.get());
            assertTrue(admitted.get().isActive());
            assertTrue(repository.isFrozen());

            coordinatorWorker.get().interrupt();
            assertTrue(indeterminate.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(RunResult.Detail.PROCESS_INTERRUPTION,
                    coordinator.getSnapshot().getResult().getDetail());
            assertEquals(0, lease.closeCount.get());
            assertTrue(admitted.get().isActive());
            assertTrue(repository.isFrozen());

            // A regression that closes stdout immediately on interruption loses the
            // gated final line. Give that close a bounded opportunity to happen before
            // releasing the bytes; the fixed supervisor keeps its full EOF grace.
            stdout.closeEntered.await(250, TimeUnit.MILLISECONDS);
            stdout.releaseFinalLine.countDown();

            assertTrue(terminal.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            RunCoordinator.Snapshot snapshot = coordinator.getSnapshot();
            assertEquals(RunCoordinator.State.TERMINAL, snapshot.getState());
            assertEquals(RunResult.Detail.PROCESS_INTERRUPTION,
                    snapshot.getResult().getDetail());
            assertEquals(ExploitRunner.RESULT_INDETERMINATE, snapshot.getResult().getCode());
            assertTrue(snapshot.getLog().stream().anyMatch(entry ->
                    entry.getMessage().equals("final line after interruption\n")));
            assertEquals(0, outputErrors.get());
            assertEquals(1, interruptionNotifications.get());
            assertTrue(interruptRestored.get());
            assertEquals(1, lease.closeCount.get());
            assertEquals(1, admissionReleaseNotifications.get());
            assertFalse(admitted.get().isActive());
            assertFalse(repository.isFrozen());
            assertEquals(0, process.destroyCount.get());
            assertEquals(0, process.destroyForciblyCount.get());
        } finally {
            stdout.releaseFinalLine.countDown();
            stdout.close();
            process.exit(0);
        }
    }

    private static ConfigurationRepository repository() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new Store(), Runnable::run, Runnable::run);
        repository.start();
        return repository;
    }

    private static final class FakeLease implements ResourceLease {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
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

    private static final class FakeProcess extends Process {
        final InputStream stdout;
        final AtomicInteger stdinCloseCount = new AtomicInteger();
        final OutputStream stdin = new OutputStream() {
            @Override
            public void write(int value) {
            }

            @Override
            public void close() {
                stdinCloseCount.incrementAndGet();
            }
        };
        final Deque<Boolean> scriptedTimedWaits = new ArrayDeque<>();
        final CountDownLatch exited = new CountDownLatch(1);
        final CountDownLatch exitValueCalled = new CountDownLatch(1);
        final AtomicInteger destroyCount = new AtomicInteger();
        final AtomicInteger destroyForciblyCount = new AtomicInteger();
        volatile int exitCode;

        FakeProcess() {
            this(new ByteArrayInputStream(new byte[0]));
        }

        FakeProcess(InputStream stdout) {
            this.stdout = stdout;
        }

        void exit(int code) {
            exitCode = code;
            exited.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            Boolean scripted = scriptedTimedWaits.pollFirst();
            return scripted != null ? scripted : exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            exitValueCalled.countDown();
            if (exited.getCount() != 0) {
                throw new IllegalThreadStateException("still running");
            }
            return exitCode;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() != 0;
        }

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
            exit(1);
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCount.incrementAndGet();
            destroy();
            return this;
        }
    }

    private static final class GatedBeforeFinalLineInputStream extends InputStream {
        private final byte[] line;
        private final CountDownLatch finalLineGateEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFinalLine = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private int offset;
        private volatile boolean closed;

        GatedBeforeFinalLineInputStream(String line) throws IOException {
            this.line = line.getBytes("UTF-8");
        }

        @Override
        public int read() throws IOException {
            if (closed) {
                return -1;
            }
            awaitFinalLineRelease();
            if (closed || offset >= line.length) {
                return -1;
            }
            return line[offset++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int bufferOffset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (closed) {
                return -1;
            }
            awaitFinalLineRelease();
            if (closed || offset >= line.length) {
                return -1;
            }
            int count = Math.min(length, line.length - offset);
            System.arraycopy(line, offset, buffer, bufferOffset, count);
            offset += count;
            return count;
        }

        private void awaitFinalLineRelease() throws IOException {
            finalLineGateEntered.countDown();
            try {
                releaseFinalLine.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("stdout final-line gate interrupted", e);
            }
        }

        @Override
        public void close() {
            closed = true;
            closeEntered.countDown();
        }
    }

    private static final class FinalLineThenBlockingEofInputStream extends InputStream {
        private final byte[] line;
        private final CountDownLatch eofEntered = new CountDownLatch(1);
        private final CountDownLatch releaseEof = new CountDownLatch(1);
        private int offset;

        FinalLineThenBlockingEofInputStream(String line) throws IOException {
            this.line = line.getBytes("UTF-8");
        }

        @Override
        public int read() throws IOException {
            if (offset < line.length) {
                return line[offset++] & 0xff;
            }
            awaitEofRelease();
            return -1;
        }

        @Override
        public int read(byte[] buffer, int bufferOffset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (offset < line.length) {
                int count = Math.min(length, line.length - offset);
                System.arraycopy(line, offset, buffer, bufferOffset, count);
                offset += count;
                return count;
            }
            awaitEofRelease();
            return -1;
        }

        private void awaitEofRelease() throws IOException {
            eofEntered.countDown();
            try {
                releaseEof.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("stdout EOF gate interrupted", e);
            }
        }

        @Override
        public void close() {
            releaseEof.countDown();
        }
    }
}
