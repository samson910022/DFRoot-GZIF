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
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            // Never NPE here: without PowerManager we cannot safely keep the CPU
            // awake for the exploit, so fail with a clear log instead.
            Log.e(TAG, "boot: PowerManager unavailable, aborting exploit");
            return;
        }
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
        // Timed acquire: if the exploit hangs, the lock is released automatically
        // after WAKELOCK_TIMEOUT_MS instead of draining the battery forever.
        wl.acquire(ExploitRunner.WAKELOCK_TIMEOUT_MS);
        new Thread(() -> {
            try {
                int rc = ExploitRunner.run(deCtx, this);
                Log.i(TAG, "boot: exploit rc=" + rc);
            } catch (Exception e) {
                Log.e(TAG, "boot: exploit exception", e);
            } finally {
                if (wl.isHeld()) {
                    wl.release();
                }
            }
        }, "dfroot-boot").start();
    }
}
