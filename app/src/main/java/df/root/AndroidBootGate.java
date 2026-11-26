// Modified 2026-11-25 (DFRoot GZIF round-4 PR #3 phase 3): production-only
// durable boot-disable gate. See NOTICE.
package df.root;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production implementation of {@link PreferenceIntegrity.BootGate}.
 *
 * Referenced ONLY from {@code PreferenceIntegrity.init(Context)}, so the pure
 * {@code init(File)} path and host-only JVM tests never require Android's
 * PackageManager. This gate only ever DISABLES the receiver: nothing here can
 * auto-enable automatic startup, and the user can still turn it off in Settings.
 */
final class AndroidBootGate implements PreferenceIntegrity.BootGate {

    private final Context appContext;
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dfroot-boot-gate");
        t.setDaemon(true);
        return t;
    });

    AndroidBootGate(Context appContext) {
        this.appContext = appContext;
    }

    @Override
    public void disableBootLaunch() {
        // Always resolve from the application context, never from an Activity
        // or Fragment that could be recycled while the gate is running.
        Context app = appContext == null ? null : appContext.getApplicationContext();
        if (app == null) {
            // No stable context: the caller must treat this as a fault and stay
            // quarantined instead of assuming the component was disabled.
            throw new IllegalStateException("no application context for boot gate");
        }
        PackageManager pm = app.getPackageManager();
        ComponentName receiver = new ComponentName(app, BootReceiver.class);
        if (pm.getComponentEnabledSetting(receiver)
                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * UI-driven disable: the PackageManager call runs on a background thread so
     * a Settings screen never blocks on it. The synchronous disableBootLaunch()
     * above is reserved for PreferenceIntegrity's failure boundary, where the
     * disable must complete before the write is reported as uncertain.
     */
    @Override
    public void disableBootLaunchAsync(Runnable onDone) {
        BACKGROUND.execute(() -> {
            try {
                disableBootLaunch();
            } catch (RuntimeException e) {
                // The quarantine latch is already fail-closed; a rejected
                // component change cannot make an uncertain state safe.
            } finally {
                if (onDone != null) onDone.run();
            }
        });
    }
}
