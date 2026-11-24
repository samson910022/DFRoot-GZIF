// Modified 2026-11-24 (DFRoot GZIF): prove asynchronous child supervision without forced termination. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ChildProcessSupervisorTest {
    private static final long TEST_TIMEOUT_MS = 5_000L;

    @Test
    public void normalExitDrainsMergedOutputAndClosesStdinOnce() throws Exception {
        FakeProcess process = new FakeProcess(
                new ByteArrayInputStream("first\nsecond\n".getBytes("UTF-8")), 7, true);
        RecordingListener listener = new RecordingListener();

        ChildProcessSupervisor.Result result = supervise(process, listener);

        assertEquals(7, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertFalse(result.isInterrupted());
        assertEquals(0, listener.outputComplete.getCount());
        assertEquals(Arrays.asList("first", "second"), listener.output);
        assertEquals(1, process.stdin.closeCount.get());
        assertEquals(0, process.destroyCount.get());
        assertEquals(0, process.destroyForciblyCount.get());
    }

    @Test
    public void blockedInheritedPipeIsClosedAfterBoundedGraceAndReportsOutputError()
            throws Exception {
        BlockingInputStream stdout = new BlockingInputStream();
        FakeProcess process = new FakeProcess(stdout, 0, true);
        RecordingListener listener = new RecordingListener();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChildProcessSupervisor.Result> waiting = executor.submit(() ->
                    new ChildProcessSupervisor(ignored -> process, TEST_TIMEOUT_MS, listener, 25L)
                            .awaitExit(Collections.singletonList("test-child")));

            assertTrue(stdout.readEntered.await(5, TimeUnit.SECONDS));
            ChildProcessSupervisor.Result result = waiting.get(5, TimeUnit.SECONDS);

            assertEquals(0, result.getExitCode());
            assertFalse(result.isTimedOut());
            assertEquals(0, stdout.closed.getCount());
            assertTrue(listener.outputError.await(5, TimeUnit.SECONDS));
            assertTrue(listener.outputFailure instanceof IOException);
            assertTrue(listener.outputFailure.getMessage().contains("stdout"));
            assertEquals(0, process.destroyCount.get());
            assertEquals(0, process.destroyForciblyCount.get());
        } finally {
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void stdoutDrainThatSurvivesCloseIsBoundedAndReportsOutputError() throws Exception {
        CloseResistantInputStream stdout = new CloseResistantInputStream();
        FakeProcess process = new FakeProcess(stdout, 0, true);
        RecordingListener listener = new RecordingListener();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChildProcessSupervisor.Result> waiting = executor.submit(() ->
                    new ChildProcessSupervisor(ignored -> process, TEST_TIMEOUT_MS, listener, 10L)
                            .awaitExit(Collections.singletonList("test-child")));
            ChildProcessSupervisor.Result result = waiting.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertEquals(0, result.getExitCode());
            assertTrue(stdout.readEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertEquals(1, stdout.closeCount.get());
            assertEquals(2, listener.outputErrorCount.get());
            assertTrue(listener.outputFailure.getMessage().contains("after stream close"));
            assertEquals(0, process.destroyCount.get());
            assertEquals(0, process.destroyForciblyCount.get());
        } finally {
            stdout.releaseRead.countDown();
            executor.shutdownNow();
            try {
                assertTrue(stdout.readerFinished.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } finally {
                assertTrue(executor.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    public void blockingStdoutCloseDoesNotBlockSupervision() throws Exception {
        BlockingCloseInputStream stdout = new BlockingCloseInputStream();
        FakeProcess process = new FakeProcess(stdout, 0, true);
        RecordingListener listener = new RecordingListener();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChildProcessSupervisor.Result> waiting = executor.submit(() ->
                    new ChildProcessSupervisor(ignored -> process, TEST_TIMEOUT_MS, listener, 25L)
                            .awaitExit(Collections.singletonList("test-child")));

            assertTrue(stdout.readEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertTrue(stdout.closeEntered.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            ChildProcessSupervisor.Result result = waiting.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertEquals(0, result.getExitCode());
            assertFalse(result.isTimedOut());
            assertFalse(result.isInterrupted());
            assertTrue(stdout.closeThread.isDaemon());
            assertTrue(stdout.closeThread.isAlive());
            assertEquals(1, stdout.closeCount.get());
            assertTrue(listener.outputErrorCount.get() >= 2);
            assertTrue(listener.outputFailure.getMessage().contains("after stream close"));
            assertEquals(0, process.destroyCount.get());
            assertEquals(0, process.destroyForciblyCount.get());
        } finally {
            stdout.releaseClose.countDown();
            stdout.releaseRead.countDown();
            executor.shutdownNow();
            try {
                assertTrue(stdout.closeFinished.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                assertTrue(stdout.readerFinished.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } finally {
                assertTrue(executor.awaitTermination(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    public void eofWhileAliveIsStillRunningUntilTheProcessExits() throws Exception {
        CountDownLatch eof = new CountDownLatch(1);
        FakeProcess process = new FakeProcess(new EofSignallingInputStream(
                new ByteArrayInputStream(new byte[0]), eof), 0, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChildProcessSupervisor.Result> waiting = executor.submit(
                    () -> supervise(process, new RecordingListener()));

            assertTrue(eof.await(5, TimeUnit.SECONDS));
            assertFalse(waiting.isDone());
            assertTrue(process.isAlive());
            assertEquals(0, process.destroyCount.get());

            process.exit(0);
            ChildProcessSupervisor.Result result = waiting.get(5, TimeUnit.SECONDS);
            assertEquals(0, result.getExitCode());
            assertFalse(result.isTimedOut());
        } finally {
            process.exit(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void timeoutPublishesIndeterminateButWaitsForDelayedExitWithoutDestroying() throws Exception {
        FakeProcess process = new FakeProcess(new ByteArrayInputStream(new byte[0]), 0, false);
        process.scriptedTimedWaits.add(false);
        RecordingListener listener = new RecordingListener();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChildProcessSupervisor.Result> waiting = executor.submit(
                    () -> supervise(process, listener));

            assertTrue(listener.timeout.await(5, TimeUnit.SECONDS));
            assertFalse(waiting.isDone());
            assertTrue(process.isAlive());
            assertEquals(0, process.destroyCount.get());
            assertEquals(0, process.destroyForciblyCount.get());
            assertEquals(1, process.stdin.closeCount.get());

            process.exit(0);
            ChildProcessSupervisor.Result result = waiting.get(5, TimeUnit.SECONDS);
            assertEquals(0, result.getExitCode());
            assertTrue(result.isTimedOut());
            assertFalse(result.isInterrupted());
            assertEquals(1, listener.timeoutCount.get());
            assertEquals(0, process.destroyCount.get());
        } finally {
            process.exit(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void interruptionPublishesIndeterminateAndStillWaitsForEventualExit() throws Exception {
        FakeProcess process = new FakeProcess(new ByteArrayInputStream(new byte[0]), 3, false);
        RecordingListener listener = new RecordingListener();
        AtomicReference<Thread> supervisingThread = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChildProcessSupervisor.Result> waiting = executor.submit(() -> {
                supervisingThread.set(Thread.currentThread());
                ChildProcessSupervisor.Result result = supervise(process, listener);
                interruptRestored.set(Thread.currentThread().isInterrupted());
                return result;
            });

            assertTrue(process.firstTimedWait.await(5, TimeUnit.SECONDS));
            supervisingThread.get().interrupt();
            assertTrue(listener.interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(waiting.isDone());
            assertTrue(process.isAlive());
            assertEquals(0, process.destroyCount.get());

            process.exit(3);
            ChildProcessSupervisor.Result result = waiting.get(5, TimeUnit.SECONDS);
            assertEquals(3, result.getExitCode());
            assertFalse(result.isTimedOut());
            assertTrue(result.isInterrupted());
            assertTrue(interruptRestored.get());
            assertEquals(1, listener.interruptedCount.get());
            assertEquals(0, process.destroyForciblyCount.get());
        } finally {
            process.exit(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void stdoutErrorIsObservableAndDoesNotReturnBeforeExit() throws Exception {
        IOException failure = new IOException("stdout unavailable");
        FakeProcess process = new FakeProcess(new FailingInputStream(failure), 0, false);
        RecordingListener listener = new RecordingListener();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChildProcessSupervisor.Result> waiting = executor.submit(
                    () -> supervise(process, listener));

            assertTrue(listener.outputError.await(5, TimeUnit.SECONDS));
            assertSame(failure, listener.outputFailure);
            assertFalse(waiting.isDone());
            assertEquals(0, process.destroyCount.get());

            process.exit(0);
            ChildProcessSupervisor.Result result = waiting.get(5, TimeUnit.SECONDS);
            assertEquals(0, result.getExitCode());
            assertFalse(result.isTimedOut());
            assertFalse(result.isInterrupted());
        } finally {
            process.exit(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void stdinCloseFailureIsObservableAndDoesNotSkipTheExitWait() throws Exception {
        IOException failure = new IOException("stdin close unavailable");
        FakeProcess process = new FakeProcess(new ByteArrayInputStream(new byte[0]), 0, false);
        process.stdin.closeFailure = failure;
        RecordingListener listener = new RecordingListener();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChildProcessSupervisor.Result> waiting = executor.submit(
                    () -> supervise(process, listener));

            assertTrue(listener.stdinError.await(5, TimeUnit.SECONDS));
            assertSame(failure, listener.stdinFailure);
            assertFalse(waiting.isDone());

            process.exit(0);
            ChildProcessSupervisor.Result result = waiting.get(5, TimeUnit.SECONDS);
            assertEquals(0, result.getExitCode());
            assertEquals(1, process.stdin.closeCount.get());
        } finally {
            process.exit(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ChildProcessSupervisor.Result supervise(
            FakeProcess process, RecordingListener listener) throws Exception {
        List<String> command = Collections.singletonList("test-child");
        return new ChildProcessSupervisor(ignored -> process, TEST_TIMEOUT_MS, listener)
                .awaitExit(command);
    }

    private static final class RecordingListener implements ChildProcessSupervisor.Listener {
        final List<String> output = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch outputComplete = new CountDownLatch(1);
        final CountDownLatch timeout = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch outputError = new CountDownLatch(1);
        final CountDownLatch stdinError = new CountDownLatch(1);
        final AtomicInteger timeoutCount = new AtomicInteger();
        final AtomicInteger interruptedCount = new AtomicInteger();
        final AtomicInteger outputErrorCount = new AtomicInteger();
        volatile Throwable outputFailure;
        volatile Throwable stdinFailure;

        @Override
        public void onOutputLine(String line) {
            output.add(line);
            if (output.size() == 2) {
                outputComplete.countDown();
            }
        }

        @Override
        public void onTimeout() {
            timeoutCount.incrementAndGet();
            timeout.countDown();
        }

        @Override
        public void onInterrupted() {
            interruptedCount.incrementAndGet();
            interrupted.countDown();
        }

        @Override
        public void onOutputError(Throwable failure) {
            outputFailure = failure;
            outputErrorCount.incrementAndGet();
            outputError.countDown();
        }

        @Override
        public void onStdinError(Throwable failure) {
            stdinFailure = failure;
            stdinError.countDown();
        }
    }

    private static final class FakeProcess extends Process {
        final InputStream stdout;
        final CloseTrackingOutputStream stdin = new CloseTrackingOutputStream();
        final CountDownLatch exited;
        final CountDownLatch firstTimedWait = new CountDownLatch(1);
        final Deque<Boolean> scriptedTimedWaits = new ArrayDeque<>();
        final AtomicInteger destroyCount = new AtomicInteger();
        final AtomicInteger destroyForciblyCount = new AtomicInteger();
        volatile int exitCode;

        FakeProcess(InputStream stdout, int exitCode, boolean alreadyExited) {
            this.stdout = stdout;
            this.exitCode = exitCode;
            this.exited = new CountDownLatch(alreadyExited ? 0 : 1);
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
            firstTimedWait.countDown();
            Boolean scripted = scriptedTimedWaits.pollFirst();
            if (scripted != null) {
                return scripted;
            }
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
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

    private static final class CloseTrackingOutputStream extends OutputStream {
        final AtomicInteger closeCount = new AtomicInteger();
        volatile IOException closeFailure;

        @Override
        public void write(int value) {
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class CloseResistantInputStream extends InputStream {
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final CountDownLatch readerFinished = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public int read() throws IOException {
            readEntered.countDown();
            try {
                releaseRead.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("stdout reader interrupted", e);
            }
            readerFinished.countDown();
            return -1;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class BlockingCloseInputStream extends InputStream {
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final CountDownLatch readerFinished = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final CountDownLatch closeFinished = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger();
        volatile Thread closeThread;

        @Override
        public int read() throws IOException {
            readEntered.countDown();
            try {
                releaseRead.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("stdout reader interrupted", e);
            }
            readerFinished.countDown();
            return -1;
        }

        @Override
        public void close() throws IOException {
            if (closeCount.incrementAndGet() == 1) {
                closeThread = Thread.currentThread();
                closeEntered.countDown();
            }
            try {
                releaseClose.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("stdout close interrupted", e);
            } finally {
                closeFinished.countDown();
            }
        }
    }

    private static final class BlockingInputStream extends InputStream {
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            readEntered.countDown();
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("stdout reader interrupted", e);
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class EofSignallingInputStream extends FilterInputStream {
        private final CountDownLatch eof;

        EofSignallingInputStream(InputStream delegate, CountDownLatch eof) {
            super(delegate);
            this.eof = eof;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value < 0) {
                eof.countDown();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            if (count < 0) {
                eof.countDown();
            }
            return count;
        }
    }

    private static final class FailingInputStream extends InputStream {
        private final IOException failure;

        FailingInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            throw failure;
        }
    }
}
