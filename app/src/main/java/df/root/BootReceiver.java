// Modified 2026-11-24 (DFRoot GZIF): consume typed detailed run results and abort on marker inspection errors. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): freeze a confirmed snapshot and reject unattended soft reboot. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): await cold-start readiness off-main and transfer a live boot admission to the raw thread with guaranteed cleanup. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): reduce boot handling to action validation plus an explicit foreground-service request. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): gate boot handling on a short durable BOOT_COUNT claim with goAsync. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): expose a narrow dispatch/finish seam for deterministic boundary tests. See NOTICE.
// Modified 2026-11-25 (PR #3 round-4): defense-in-depth preference quarantine
// SKIP check. It can only skip a launch, never authorize one. See NOTICE.
package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Validation-and-claim boundary only. ExploitService and RunCoordinator own all
 * operation work; this receiver never admits a run or holds dangerous resources.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "dfroot";

    interface ServiceStarter {
        void startUnattendedBoot(Context context);
    }

    interface ClaimFactory {
        BootClaimCoordinator create(Context context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        receive(context, intent, BootClaimCoordinator::production);
    }

    /** goAsync() keeps the receiver prompt while the checked claim runs off-main. */
    void receive(Context context, Intent intent, ClaimFactory factory) {
        if (context == null || intent == null || !isBootAction(intent.getAction())) {
            return;
        }
        // Master's P2-08/P2-10 defense-in-depth quarantine. This is NEVER an
        // authorization oracle: the only action it can take is to SKIP a boot
        // launch. The durable BootClaimCoordinator remains the sole admission
        // authority, and the component is expected to be disabled already.
        PreferenceIntegrity.init(context);
        if (PreferenceIntegrity.isQuarantined()) {
            Log.i(TAG, "boot: preferences quarantined, skipping launch");
            return;
        }
        PendingResult pending = goAsync();
        BootClaimCoordinator.Finisher finisher = pending == null ? () -> { } : pending::finish;
        submitClaim(context, factory, finisher, BootReceiver::startUnattendedService);
    }

    /**
     * Narrow package seam for the dispatch-and-finish boundary. Production keeps the
     * real service starter; deterministic tests can observe the finisher directly
     * instead of needing a Robolectric PendingResult shadow.
     */
    static void submitClaim(Context context, ClaimFactory factory,
                            BootClaimCoordinator.Finisher finisher, ServiceStarter starter) {
        try {
            factory.create(context).submit(context, finisher, starter);
        } catch (RuntimeException e) {
            Log.e(TAG, "boot: claim dispatch failed", e);
            finisher.finish();
        }
    }

    /** Narrow seam for deterministic tests; all paths still pass through the durable claim. */
    static void dispatch(Context context, Intent intent, BootClaimCoordinator claims,
                         BootClaimCoordinator.Finisher finisher, ServiceStarter starter) {
        dispatchAction(context, intent == null ? null : intent.getAction(), claims, finisher, starter);
    }

    /** Action-only seam for pure JVM contract tests; no Android Intent runtime needed. */
    static void dispatchAction(Context context, String action, BootClaimCoordinator claims,
                               BootClaimCoordinator.Finisher finisher, ServiceStarter starter) {
        if (!isBootAction(action)) {
            return;
        }
        claims.submit(context, finisher, starter);
    }

    static boolean isBootAction(String action) {
        return Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action);
    }

    static void startUnattendedService(Context context) {
        // Boot broadcasts are an FGS background-start exemption. The Direct Boot-aware
        // service creates its own DE context and calls startForeground promptly.
        // https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
        ExploitService.start(context, ExploitService.ACTION_UNATTENDED_BOOT);
    }
}
