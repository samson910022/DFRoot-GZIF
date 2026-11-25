// Modified 2026-11-25 (DFRoot GZIF): add the short off-main durable boot claim before explicit service start. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): suppress boot claims while an unconfirmed attempt survives process death. See NOTICE.
package df.root;

import android.content.Context;
import android.util.Log;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Short-lived boot-claim worker boundary.
 *
 * <p>Reads a positive boot count, durably claims it, and only then asks the
 * explicit foreground service to start. It never admits configuration, touches
 * the WakeLock, or executes exploit work. The claim is the single gate for both
 * boot broadcasts and for broadcasts delivered after process death.</p>
 */
final class BootClaimCoordinator {
    private static final String TAG = "dfroot";

    /** Narrow seam for the receiver's goAsync() PendingResult. */
    interface Finisher {
        void finish();
    }

    /** Diagnostics seam so pure contract tests never touch android.util.Log. */
    interface Logger {
        Logger NO_OP = new Logger() {
            @Override
            public void info(String message) { }

            @Override
            public void error(String message, Throwable cause) { }
        };
        Logger ANDROID = new Logger() {
            @Override
            public void info(String message) {
                Log.i(TAG, message);
            }

            @Override
            public void error(String message, Throwable cause) {
                Log.e(TAG, message, cause);
            }
        };

        void info(String message);
        void error(String message, Throwable cause);
    }

    private final BootCountReader reader;
    private final BootClaimStore store;
    private final OperationAttemptStore attemptStore;
    private final Executor executor;
    private final Logger logger;
    private final boolean shutdownAfterSubmit;

    BootClaimCoordinator(BootCountReader reader, BootClaimStore store, Executor executor,
                         Logger logger, boolean shutdownAfterSubmit) {
        this(reader, store, OperationAttemptStore.inMemory(), executor, logger,
                shutdownAfterSubmit);
    }

    BootClaimCoordinator(BootCountReader reader, BootClaimStore store,
                         OperationAttemptStore attemptStore, Executor executor,
                         Logger logger, boolean shutdownAfterSubmit) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.store = Objects.requireNonNull(store, "store");
        this.attemptStore = Objects.requireNonNull(attemptStore, "attemptStore");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.shutdownAfterSubmit = shutdownAfterSubmit;
    }

    static BootClaimCoordinator production(Context context) {
        Objects.requireNonNull(context, "context");
        return new BootClaimCoordinator(
                new BootCountReader.SettingsGlobal(context.getContentResolver()),
                SharedPreferencesBootClaimStore.forDeviceProtectedStorage(context),
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context),
                newClaimExecutor(), Logger.ANDROID, true);
    }

    /** Daemon worker used only for the short durable claim; shut down after submit. */
    static ExecutorService newClaimExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dfroot-boot-claim");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Pure in-process seam with an inline executor for deterministic contract tests. */
    static BootClaimCoordinator inline(BootCountReader reader, BootClaimStore store) {
        return new BootClaimCoordinator(reader, store, Runnable::run, Logger.NO_OP, false);
    }

    void submit(Context context, Finisher finisher, BootReceiver.ServiceStarter starter) {
        // Context validity is checked at the receiver boundary; pure contract tests
        // may supply a null context through narrow seams without Android runtime.
        Objects.requireNonNull(finisher, "finisher");
        Objects.requireNonNull(starter, "starter");
        try {
            executor.execute(() -> runClaim(context, finisher, starter));
        } catch (RuntimeException e) {
            logger.error("boot: claim worker rejected", e);
            finisher.finish();
        } finally {
            if (shutdownAfterSubmit && executor instanceof ExecutorService) {
                ((ExecutorService) executor).shutdown();
            }
        }
    }

    private void runClaim(Context context, Finisher finisher, BootReceiver.ServiceStarter starter) {
        try {
            Long bootCount = reader.read();
            if (bootCount == null || bootCount <= 0L) {
                logger.info("boot: BOOT_COUNT unavailable or non-positive; no attempt");
                return;
            }
            // A persisted unconfirmed attempt outlives the process. No boot
            // claim, no service start, and no automatic replay: only explicit
            // owner recovery may remove that barrier.
            OperationAttempt persisted;
            try {
                persisted = attemptStore.load();
            } catch (OperationAttemptStore.Failure e) {
                logger.error("boot: persisted attempt unreadable; no attempt", e);
                return;
            }
            if (persisted != null && persisted.isUnconfirmed()) {
                logger.info("boot: unconfirmed operation " + persisted.getOperationId()
                        + " from a prior process blocks boot start; explicit recovery required");
                return;
            }
            if (!store.claim(bootCount)) {
                logger.info("boot: boot already claimed or persistence failed; no attempt");
                return;
            }
            try {
                starter.startUnattendedBoot(context);
            } catch (RuntimeException e) {
                // The claim is already durable: a later same-boot broadcast must not retry.
                logger.error("boot: foreground service start failed after claim; no retry this boot", e);
            }
        } catch (RuntimeException e) {
            logger.error("boot: durable claim failed; no attempt", e);
        } finally {
            finisher.finish();
        }
    }
}
