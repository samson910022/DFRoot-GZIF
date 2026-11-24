// Modified 2026-11-24 (DFRoot GZIF): consume typed detailed run results and abort on marker inspection errors. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): freeze a confirmed snapshot and reject unattended soft reboot. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): await cold-start readiness off-main and transfer a live boot admission to the raw thread with guaranteed cleanup. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): reduce boot handling to action validation plus an explicit foreground-service request. See NOTICE.
package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Objects;

/** Validation-and-start boundary only; ExploitService and RunCoordinator own all operation work. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "dfroot";

    interface ServiceStarter {
        void startUnattendedBoot(Context context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !isBootAction(intent.getAction())) {
            return;
        }
        dispatch(context, intent, BootReceiver::startUnattendedService);
    }

    static void dispatch(Context context, Intent intent, ServiceStarter starter) {
        if (context == null || intent == null || !isBootAction(intent.getAction())) {
            return;
        }
        try {
            Objects.requireNonNull(starter, "starter").startUnattendedBoot(context);
        } catch (RuntimeException e) {
            // Starting is best-effort at this boundary; never move admission or execution here.
            Log.e(TAG, "boot: foreground service start failed", e);
        }
    }

    private static boolean isBootAction(String action) {
        return Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action);
    }

    private static void startUnattendedService(Context context) {
        // Boot broadcasts are an FGS background-start exemption. The Direct Boot-aware
        // service creates its own DE context and calls startForeground promptly.
        // https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
        ExploitService.start(context, ExploitService.ACTION_UNATTENDED_BOOT);
    }
}
