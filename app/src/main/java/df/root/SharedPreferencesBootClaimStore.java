// Modified 2026-11-25 (DFRoot GZIF): persist boot claims in a separate DE store with checked commit. See NOTICE.
package df.root;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

/**
 * Device Protected Storage boot-claim store, deliberately isolated from the
 * full-snapshot configuration writer. Only a successful commit claims a boot.
 */
final class SharedPreferencesBootClaimStore implements BootClaimStore {
    static final String PREFS_NAME = "dfroot_boot_claim";
    static final String KEY_LAST_CLAIMED_BOOT_COUNT = "last_claimed_boot_count";

    /** Serializes compare-and-set across short-lived claim workers in this process. */
    private static final Object CLAIM_LOCK = new Object();

    private final SharedPreferences preferences;

    SharedPreferencesBootClaimStore(Context deviceProtectedContext) {
        this(Objects.requireNonNull(deviceProtectedContext, "deviceProtectedContext")
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    SharedPreferencesBootClaimStore(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    static SharedPreferencesBootClaimStore forDeviceProtectedStorage(Context context) {
        Context application = context.getApplicationContext();
        Context deContext = (application != null ? application : context)
                .createDeviceProtectedStorageContext();
        return new SharedPreferencesBootClaimStore(deContext);
    }

    @Override
    public long lastClaimed() {
        synchronized (CLAIM_LOCK) {
            try {
                return preferences.getLong(KEY_LAST_CLAIMED_BOOT_COUNT, 0L);
            } catch (RuntimeException e) {
                return -1L;
            }
        }
    }

    @Override
    public boolean claim(long bootCount) {
        if (bootCount <= 0L) {
            return false;
        }
        synchronized (CLAIM_LOCK) {
            long previous;
            try {
                previous = preferences.getLong(KEY_LAST_CLAIMED_BOOT_COUNT, 0L);
            } catch (RuntimeException e) {
                return false;
            }
            if (bootCount <= previous) {
                return false;
            }
            SharedPreferences.Editor editor = preferences.edit();
            editor.putLong(KEY_LAST_CLAIMED_BOOT_COUNT, bootCount);
            // Checked commit: a false result or thrown exception never admits a run.
            return editor.commit();
        }
    }
}
