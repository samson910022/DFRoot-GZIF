// Modified 2026-11-25 (DFRoot GZIF): add the short off-main durable boot claim before explicit service start. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): suppress boot claims while an unconfirmed attempt survives process death. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): re-check the Start at Boot component at claim time so queued claims honor OFF. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.6): Javadoc only — state that the claim-time component read is read-only and not atomic with the PackageManager OFF write. No behavior change. See NOTICE.
package df.root;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
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

    /**
     * Read-only, claim-time gate for the separate Start at Boot component setting.
     * Production reads PackageManager; pure JVM tests inject ENABLED, DISABLED or
     * UNREADABLE (including a throwing reader) without any Android runtime.
     *
     * <p>This is a single read-only view of the setting, NOT an atomic transition
     * with the SettingsFragment OFF write: PackageManager writes that component
     * outside the coordinator monitor, and no gate here can make that write
     * atomic with claim, admission or engine start. OFF/unreadable at this read
     * suppresses the claim; a later OFF is enforced by the submit/publish gate
     * and pre-engine recheck only if it is observed there. Already-started work
     * is never cancelled.</p>
     */
    interface BootComponentGate {
        enum State { ENABLED, DISABLED, UNREADABLE }

        BootComponentGate PACKAGE_MANAGER = context -> {
            // Pure-JVM seam callers pass a null context; there is no component to
            // inspect and no start to suppress there. Production never does.
            if (context == null) {
                return State.ENABLED;
            }
            try {
                int setting = context.getPackageManager().getComponentEnabledSetting(
                        new ComponentName(context, BootReceiver.class));
                return setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        ? State.ENABLED : State.DISABLED;
            } catch (RuntimeException e) {
                return State.UNREADABLE;
            }
        };

        State read(Context context);
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
    private final BootComponentGate componentGate;
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
        this(reader, store, attemptStore, BootComponentGate.PACKAGE_MANAGER, executor, logger,
                shutdownAfterSubmit);
    }

    BootClaimCoordinator(BootCountReader reader, BootClaimStore store,
                         BootComponentGate componentGate, Executor executor,
                         Logger logger, boolean shutdownAfterSubmit) {
        this(reader, store, OperationAttemptStore.inMemory(), componentGate, executor, logger,
                shutdownAfterSubmit);
    }

    BootClaimCoordinator(BootCountReader reader, BootClaimStore store,
                         OperationAttemptStore attemptStore, BootComponentGate componentGate,
                         Executor executor, Logger logger, boolean shutdownAfterSubmit) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.store = Objects.requireNonNull(store, "store");
        this.attemptStore = Objects.requireNonNull(attemptStore, "attemptStore");
        this.componentGate = Objects.requireNonNull(componentGate, "componentGate");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.shutdownAfterSubmit = shutdownAfterSubmit;
    }

    /**
     * Production claim boundary. This is NOT an authorization of unattended
     * work: even if the component is forced on and a service request is queued,
     * RunCoordinator's production authorization unconditionally refuses
     * UNATTENDED_BOOT admission before any durable attempt or engine call.
     */
    static BootClaimCoordinator production(Context context) {
        Objects.requireNonNull(context, "context");
        return new BootClaimCoordinator(
                new BootCountReader.SettingsGlobal(context.getContentResolver()),
                SharedPreferencesBootClaimStore.forDeviceProtectedStorage(context),
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context),
                BootComponentGate.PACKAGE_MANAGER, newClaimExecutor(), Logger.ANDROID, true);
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
        return inline(reader, store, BootComponentGate.PACKAGE_MANAGER);
    }

    static BootClaimCoordinator inline(BootCountReader reader, BootClaimStore store,
                                        BootComponentGate componentGate) {
        return new BootClaimCoordinator(reader, store, OperationAttemptStore.inMemory(),
                componentGate, Runnable::run, Logger.NO_OP, false);
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
            // Re-read at claim time, not at submit time: an OFF set after a queued
            // broadcast must still suppress this late claim and its service start.
            BootComponentGate.State componentState;
            try {
                componentState = componentGate.read(context);
            } catch (RuntimeException e) {
                logger.error("boot: Start at Boot component state unreadable; no attempt", e);
                return;
            }
            if (componentState == BootComponentGate.State.UNREADABLE) {
                logger.error("boot: Start at Boot component state unreadable; no attempt", null);
                return;
            }
            if (componentState != BootComponentGate.State.ENABLED) {
                logger.info("boot: Start at Boot component disabled; no claim and no service start");
                return;
            }
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
