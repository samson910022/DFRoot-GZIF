// Modified 2026-11-24 (DFRoot GZIF): consume typed detailed run results and abort on marker inspection errors. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): freeze a confirmed snapshot and reject unattended soft reboot. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): await cold-start readiness off-main and transfer a live boot admission to the raw thread with guaranteed cleanup. See NOTICE.
package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;

public class BootReceiver extends BroadcastReceiver implements IReporter {
    private static final String TAG = "dfroot";
    private static final long INITIALIZATION_TIMEOUT_MS = 10_000L;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        dispatch(context, intent, BootReceiverDependencies.forContext(context));
    }

    static void dispatch(Context context, Intent intent, BootReceiverDependencies dependencies) {
        try {
            dependencies.threadStarter.start(
                    () -> admitAndStart(context, intent, dependencies));
        } catch (RuntimeException e) {
            Log.e(TAG, "boot: raw thread start rejected", e);
        }
    }

    private static void admitAndStart(Context context, Intent intent,
                                      BootReceiverDependencies dependencies) {
        ConfigurationRepository repository = dependencies.repository;
        if (repository.awaitInitialization(dependencies.initializationTimeoutMs)
                != ConfigurationRepository.State.VERIFIED) {
            Log.e(TAG, "boot: aborting: configuration initialization did not verify");
            return;
        }
        RunConfig.ExecutionMode mode = RunConfig.ExecutionMode.UNATTENDED_BOOT;
        ConfigurationRepository.RunAdmission admission =
                repository.acquireRunSnapshot(mode);
        if (admission == null) {
            Log.e(TAG, "boot: aborting: " + repository.getRunAdmissionBlockReason(mode));
            return;
        }

        boolean handedToRunner = false;
        BootReceiverDependencies.WakeLock lease = null;
        try {
            RunConfig config = admission.getSnapshot();
            if (!config.isUnattendedBootAllowed()) {
                Log.e(TAG, "boot: soft reboot is enabled; unattended execution is disabled "
                        + "pending hardware validation");
                return;
            }
            try {
                if (dependencies.hookProbe.isPresent()) {
                    Log.i(TAG, "boot: already hooked, skipping");
                    return;
                }
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "boot: hook marker inspection failed; aborting", e);
                return;
            }

            Log.i(TAG, "boot: " + (intent == null ? "null intent" : intent.getAction()));
            Context deContext = dependencies.deContextFactory.create(context);
            BootReceiverDependencies.WakeLockController controller =
                    dependencies.wakeLockFactory.create(deContext);
            BootReceiverDependencies.WakeLock acquiredLease = controller.acquire();
            if (acquiredLease == null) {
                throw new IllegalStateException("wake lock controller returned no lease");
            }
            lease = acquiredLease;
            dependencies.threadStarter.start(() -> runAndRelease(
                    deContext, admission, mode, acquiredLease, dependencies));
            handedToRunner = true;
            lease = null;
        } catch (RuntimeException e) {
            Log.e(TAG, "boot: setup failed; aborting", e);
        } finally {
            if (!handedToRunner) {
                releaseQuietly(lease);
                admission.close();
            }
        }
    }

    private static void runAndRelease(Context deContext,
                                      ConfigurationRepository.RunAdmission admission,
                                      RunConfig.ExecutionMode mode,
                                      BootReceiverDependencies.WakeLock lease,
                                      BootReceiverDependencies dependencies) {
        try {
            RunResult result = dependencies.runner.run(
                    deContext, admission, mode, BootReceiverDependencies.reporter());
            if (result == null) {
                Log.e(TAG, "boot: exploit returned null");
            } else {
                Log.i(TAG, "boot: exploit rc=" + result.getCode()
                        + " type=" + result.getType() + " detail=" + result.getDetail()
                        + " reason=" + result.getReason());
            }
        } catch (Exception e) {
            Log.e(TAG, "boot: exploit exception", e);
        } finally {
            try {
                releaseQuietly(lease);
            } finally {
                admission.close();
            }
        }
    }

    private static void releaseQuietly(BootReceiverDependencies.WakeLock lease) {
        if (lease == null) {
            return;
        }
        try {
            lease.release();
        } catch (RuntimeException e) {
            Log.e(TAG, "boot: wake lock release failed", e);
        }
    }

    /** Package-visible seam for deterministic receiver boundary tests. */
    static final class BootReceiverDependencies {
        interface HookProbe {
            boolean isPresent() throws IOException;
        }

        interface ContextFactory {
            Context create(Context context);
        }

        interface WakeLockController {
            WakeLock acquire();
        }

        interface WakeLockFactory {
            WakeLockController create(Context context);
        }

        interface WakeLock {
            void release();
        }

        interface ThreadStarter {
            void start(Runnable runnable);
        }

        interface Runner {
            RunResult run(Context context, ConfigurationRepository.RunAdmission admission,
                          RunConfig.ExecutionMode mode, IReporter reporter) throws Exception;
        }

        final ConfigurationRepository repository;
        final long initializationTimeoutMs;
        final HookProbe hookProbe;
        final ContextFactory deContextFactory;
        final WakeLockFactory wakeLockFactory;
        final ThreadStarter threadStarter;
        final Runner runner;

        BootReceiverDependencies(ConfigurationRepository repository,
                                 long initializationTimeoutMs, HookProbe hookProbe,
                                 ContextFactory deContextFactory, WakeLockFactory wakeLockFactory,
                                 ThreadStarter threadStarter, Runner runner) {
            this.repository = repository;
            this.initializationTimeoutMs = initializationTimeoutMs;
            this.hookProbe = hookProbe;
            this.deContextFactory = deContextFactory;
            this.wakeLockFactory = wakeLockFactory;
            this.threadStarter = threadStarter;
            this.runner = runner;
        }

        static BootReceiverDependencies forContext(Context context) {
            return new BootReceiverDependencies(
                    ConfigurationRepository.get(context),
                    INITIALIZATION_TIMEOUT_MS,
                    HookMarkerProbe::defaultMarkerPresent,
                    Context::createDeviceProtectedStorageContext,
                    BootReceiverDependencies::powerManagerController,
                    runnable -> new Thread(runnable, "dfroot-boot").start(),
                    (deContext, admission, mode, reporter) -> ExploitRunner.runDetailed(
                            deContext, admission, mode, reporter));
        }

        private static IReporter reporter() {
            return new BootReceiver();
        }

        private static WakeLockController powerManagerController(Context context) {
            PowerManager powerManager =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                throw new IllegalStateException("PowerManager unavailable");
            }
            PowerManager.WakeLock acquired = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
            try {
                acquired.acquire(ExploitRunner.WAKELOCK_TIMEOUT_MS);
            } catch (RuntimeException e) {
                try {
                    acquired.release();
                } catch (RuntimeException ignored) {
                    // Preserve the acquisition failure.
                }
                throw e;
            }
            BootReceiverDependencies.WakeLock lease = acquired::release;
            return () -> lease;
        }
    }
}
