// Modified 2026-11-24 (DFRoot GZIF): supervise child exit and merged output without forced termination. See NOTICE.
package df.root;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Owns one started child until its actual exit.
 *
 * <p>Timeout and interruption are observations, not cancellation. The process is
 * deliberately retained and waited on after either event so callers keep
 * privileged resources for the full possible effect window.</p>
 *
 * <p>The timed-wait and merged-stream contracts follow Android's {@link Process}
 * documentation: https://developer.android.com/reference/java/lang/Process .
 * The product policy is stricter than the API: this class never terminates a
 * started privileged child.</p>
 *
 * <p>After actual exit, stdout receives an EOF grace, then a bounded asynchronous
 * close, then a final drain grace. Supervision does not wait indefinitely for a
 * broken stream; daemon threads may remain only when it refuses both close and
 * EOF.</p>
 */
public final class ChildProcessSupervisor {
    /**
     * Grace allowed after actual child exit for stdout to reach EOF. If that EOF
     * grace expires, stdout is closed by a bounded daemon closer and the drain
     * receives a final equal grace period. Only a stream that refuses both close
     * and EOF can leave daemon threads behind after supervision returns.
     */
    static final long STDOUT_DRAIN_GRACE_MILLIS = 5_000L;

    public interface ProcessFactory {
        Process start(List<String> command) throws IOException;
    }

    /** Observational callbacks; callback failures never change process supervision. */
    public interface Listener {
        default void onOutputLine(String line) {}
        default void onTimeout() {}
        default void onInterrupted() {}
        default void onOutputError(Throwable failure) {}
        default void onStdinError(Throwable failure) {}
    }

    /** Immutable actual-exit result; uncertainty remains set after a delayed exit. */
    public static final class Result {
        private final int exitCode;
        private final boolean timedOut;
        private final boolean interrupted;

        Result(int exitCode, boolean timedOut, boolean interrupted) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.interrupted = interrupted;
        }

        public int getExitCode() {
            return exitCode;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public boolean isInterrupted() {
            return interrupted;
        }
    }

    private final ProcessFactory processFactory;
    private final long timeoutMillis;
    private final long stdoutDrainGraceMillis;
    private final Listener listener;
    private Process process;
    private InputStream stdout;
    private Thread stdoutThread;

    public ChildProcessSupervisor(ProcessFactory processFactory, long timeoutMillis,
                                  Listener listener) {
        this(processFactory, timeoutMillis, listener, STDOUT_DRAIN_GRACE_MILLIS);
    }

    ChildProcessSupervisor(ProcessFactory processFactory, long timeoutMillis,
                           Listener listener, long stdoutDrainGraceMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be non-negative");
        }
        if (stdoutDrainGraceMillis <= 0) {
            throw new IllegalArgumentException("stdoutDrainGraceMillis must be positive");
        }
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
        this.timeoutMillis = timeoutMillis;
        this.stdoutDrainGraceMillis = stdoutDrainGraceMillis;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Starts the command, closes stdin, and drains merged stdout asynchronously.
     * Returns only after the child has actually exited and the stdout drain has
     * either reached EOF or exhausted its bounded close-and-wait fallback. The
     * caller's interrupt flag is restored only after that handoff.
     */
    public Result awaitExit(List<String> command) throws IOException {
        process = Objects.requireNonNull(processFactory.start(
                Objects.requireNonNull(command, "command")), "processFactory returned null");
        startStdoutDrain();
        closeStdin();

        boolean timedOut = false;
        boolean interrupted = false;
        for (;;) {
            try {
                // Avoid a hot loop if a zero timeout was explicitly injected.
                long waitMillis = (timedOut || interrupted)
                        ? Math.max(1L, timeoutMillis) : timeoutMillis;
                if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                    int exitCode = process.exitValue();
                    interrupted = awaitStdoutDrain(interrupted);
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return new Result(exitCode, timedOut, interrupted);
                }
                if (!timedOut) {
                    timedOut = true;
                    notifyTimeout();
                }
            } catch (InterruptedException e) {
                if (!interrupted) {
                    interrupted = true;
                    notifyInterrupted();
                }
                // Do not restore yet: an interrupt must not turn the remaining
                // actual-exit wait into a tight interruption loop.
            }
        }
    }

    private boolean awaitStdoutDrain(boolean interrupted) {
        Thread drain = stdoutThread;
        if (drain == null) {
            return interrupted;
        }
        interrupted = joinUntilDeadline(drain,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stdoutDrainGraceMillis),
                interrupted);
        if (!drain.isAlive()) {
            return interrupted;
        }

        // An inherited pipe can outlive its writer. Close only the retained stdout
        // stream, asynchronously: a broken InputStream.close must never hold the
        // coordinator or worker. The already-exited child is never destroyed.
        notifyOutputError(new IOException("child stdout did not reach EOF within "
                + stdoutDrainGraceMillis + " ms after exit; closing stream asynchronously"));
        Thread closer = new Thread(this::closeStdout, "dfroot-child-stdout-close");
        closer.setDaemon(true);
        try {
            closer.start();
        } catch (RuntimeException e) {
            notifyOutputError(e);
        }
        interrupted = joinUntilDeadline(closer,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stdoutDrainGraceMillis),
                interrupted);
        if (closer.isAlive()) {
            notifyOutputError(new IOException("child stdout close did not finish within "
                    + stdoutDrainGraceMillis + " ms"));
        }

        interrupted = joinUntilDeadline(drain,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stdoutDrainGraceMillis),
                interrupted);
        if (drain.isAlive()) {
            notifyOutputError(new IOException("child stdout drain did not finish within "
                    + stdoutDrainGraceMillis + " ms after stream close"));
        }
        return interrupted;
    }

    /**
     * Waits until {@code thread} finishes or the monotonic deadline expires.
     * Interruption is observed once but does not shorten the full grace period.
     */
    private boolean joinUntilDeadline(Thread thread, long deadlineNanos, boolean interrupted) {
        while (thread.isAlive()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            try {
                thread.join(TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                        (int) TimeUnit.NANOSECONDS.toNanos(remainingNanos % 1_000_000L));
            } catch (InterruptedException e) {
                if (!interrupted) {
                    interrupted = true;
                    notifyInterrupted();
                }
                // Keep joining with the original deadline. An interrupt observes
                // uncertainty; it must not close stdout early or skip remaining grace.
            }
        }
        return interrupted;
    }

    private void closeStdout() {
        try {
            stdout.close();
        } catch (IOException | RuntimeException e) {
            notifyOutputError(e);
        }
    }

    private void startStdoutDrain() {
        try {
            InputStream stream = process.getInputStream();
            if (stream == null) {
                throw new IOException("child stdout is null");
            }
            stdout = stream;
            stdoutThread = new Thread(() -> drainStdout(stream), "dfroot-child-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();
        } catch (IOException | RuntimeException e) {
            notifyOutputError(e);
        }
    }

    private void drainStdout(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            for (String line; (line = reader.readLine()) != null; ) {
                try {
                    listener.onOutputLine(line);
                } catch (RuntimeException ignored) {
                    // Output is observational; keep draining for pipe hygiene.
                }
            }
        } catch (IOException | RuntimeException e) {
            notifyOutputError(e);
        }
    }

    private void closeStdin() {
        try {
            OutputStream stdin = process.getOutputStream();
            if (stdin == null) {
                throw new IOException("child stdin is null");
            }
            stdin.close();
        } catch (IOException | RuntimeException e) {
            try {
                listener.onStdinError(e);
            } catch (RuntimeException ignored) {
                // Stdin closure failure is observable but not a cancellation signal.
            }
        }
    }

    private void notifyTimeout() {
        try {
            listener.onTimeout();
        } catch (RuntimeException ignored) {
            // Observers cannot change the no-forced-termination policy.
        }
    }

    private void notifyInterrupted() {
        try {
            listener.onInterrupted();
        } catch (RuntimeException ignored) {
            // Observers cannot change the actual-exit ownership boundary.
        }
    }

    private void notifyOutputError(Throwable failure) {
        try {
            listener.onOutputError(failure);
        } catch (RuntimeException ignored) {
            // Reporting is observational.
        }
    }
}
