// Modified 2026-11-25 (DFRoot GZIF round-3 PR #3 phase 3): failed writes now
// quarantine memory/disk divergence through PreferenceIntegrity. See NOTICE.
package df.root;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * Synchronous SharedPreferences writer.
 *
 * The native bootstrap reads the preferences XML from disk, so every write
 * must use commit() (apply() only updates memory first). commit() can fail --
 * disk full, another process holding the file, etc. -- and a failed write must
 * never be presented to the user as a saved choice. All methods return the
 * boolean result of commit() and report failures through a callback so the UI
 * can revert the corresponding control.
 *
 * commit() can fail AFTER mutating the in-memory map, leaving memory and the
 * on-disk XML divergent. Every failure therefore marks its key ambiguous in
 * PreferenceIntegrity (blocking launch/boot); only a later successful write of
 * the same key restores consistency. The public API is unchanged.
 */
public final class PreferencePersister {

    private static final String TAG = "dfroot";

    /** Called on every failed commit (or thrown write), on the calling thread. */
    public interface FailureReporter {
        void onPreferenceWriteFailed(String key, String detail);
    }

    private final SharedPreferences prefs;
    private final FailureReporter reporter;

    public PreferencePersister(SharedPreferences prefs, FailureReporter reporter) {
        this.prefs = prefs;
        this.reporter = reporter;
    }

    /** Log-only variant for callers that do not have UI to revert. */
    public PreferencePersister(SharedPreferences prefs) {
        this(prefs, null);
    }

    public boolean putString(String key, String value) {
        try {
            boolean committed = prefs.edit().putString(key, value).commit();
            if (committed) PreferenceIntegrity.markConsistent(key);
            else {
                report(key, "putString commit() returned false");
                PreferenceIntegrity.markAmbiguous(key);
            }
            return committed;
        } catch (RuntimeException e) {
            report(key, "putString threw " + e);
            PreferenceIntegrity.markAmbiguous(key);
            return false;
        }
    }

    public boolean remove(String key) {
        try {
            boolean committed = prefs.edit().remove(key).commit();
            if (committed) PreferenceIntegrity.markConsistent(key);
            else {
                report(key, "remove commit() returned false");
                PreferenceIntegrity.markAmbiguous(key);
            }
            return committed;
        } catch (RuntimeException e) {
            report(key, "remove threw " + e);
            PreferenceIntegrity.markAmbiguous(key);
            return false;
        }
    }

    public boolean putBoolean(String key, boolean value) {
        try {
            boolean committed = prefs.edit().putBoolean(key, value).commit();
            if (committed) PreferenceIntegrity.markConsistent(key);
            else {
                report(key, "putBoolean commit() returned false");
                PreferenceIntegrity.markAmbiguous(key);
            }
            return committed;
        } catch (RuntimeException e) {
            report(key, "putBoolean threw " + e);
            PreferenceIntegrity.markAmbiguous(key);
            return false;
        }
    }

    private void report(String key, String detail) {
        Log.e(TAG, "Failed to persist preference '" + key + "': " + detail);
        if (reporter != null) reporter.onPreferenceWriteFailed(key, detail);
    }
}
