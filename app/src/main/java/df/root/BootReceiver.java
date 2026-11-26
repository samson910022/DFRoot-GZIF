// Modified 2026-11-25 (DFRoot GZIF round-4 PR #3 phase 3): defense-in-depth
// quarantine check; the component is expected to be durably disabled already.
// See NOTICE.
package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

public class BootReceiver extends BroadcastReceiver implements IReporter {
    private static final String TAG = "dfroot";

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (new File("/dev/df").exists()) {
            Log.i(TAG, "boot: already hooked, skipping");
            return;
        }
        Log.i(TAG, "boot: " + intent.getAction());
        final Context deCtx = context.createDeviceProtectedStorageContext();
        PreferenceIntegrity.init(deCtx);
        if (PreferenceIntegrity.isQuarantined()) {
            // Defense in depth only. On a healthy failure path the component
            // was already durably disabled by the shared gate at the failure
            // boundary; if this receiver still runs, that is an anomaly. This
            // check is NEVER an oracle that the state is safe -- it can only
            // skip a launch, never authorize one.
            Log.i(TAG, "boot: preferences quarantined, skipping launch");
            return;
        }
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
        wl.acquire();
        new Thread(() -> {
            try {
                int rc = ExploitRunner.run(deCtx, this);
                Log.i(TAG, "boot: exploit rc=" + rc);
            } catch (Exception e) {
                Log.e(TAG, "boot: exploit exception", e);
            } finally {
                wl.release();
            }
        }, "dfroot-boot").start();
    }
}
