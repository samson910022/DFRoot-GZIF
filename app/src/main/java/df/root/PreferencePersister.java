// Modified 2026-11-25 (DFRoot GZIF round-4 PR #3 phase 3): write-ahead marker
// brackets every commit; a marker fault aborts before touching the editor. See NOTICE.
// Round-5: one transaction lock spans begin -> commit -> finish, and a failed
// marker completion is never reported as a saved preference.
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
 * boolean result of the whole bracketed transaction and report failures through
 * a callback so the UI can revert the corresponding control.
 *
 * commit() can fail AFTER mutating the in-memory map, leaving memory and the
 * on-disk XML divergent. A durable PENDING marker is therefore published
 * BEFORE the commit; if that cannot be persisted, the write is abandoned (no
 * privileged operation, no commit) and boot is disabled synchronously. Only a
 * later successful write of the same key restores consistency. A successful
 * commit whose marker cannot be durably retired/republished is also a failure:
 * the transaction is reported, not claimed as saved.
 */
public final class PreferencePersister {

    private static final String TAG = "dfroot";

    /** Held across the whole begin -> commit -> finish transaction. */
    private static final Object TRANSACTION_LOCK = new Object();

    /** Called on every failed commit (or thrown write), on the calling thread. */
    public interface FailureReporter {
        void onPreferenceWriteFailed(String key, String detail);
    }

    /** One editor mutation, so all three public writers share one transaction. */
    private interface EditorOperation {
        void apply(SharedPreferences.Editor editor);
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
        return write(key, "putString", editor -> editor.putString(key, value));
    }

    public boolean remove(String key) {
        return write(key, "remove", editor -> editor.remove(key));
    }

    public boolean putBoolean(String key, boolean value) {
        return write(key, "putBoolean", editor -> editor.putBoolean(key, value));
    }

    private boolean write(String key, String operation, EditorOperation change) {
        synchronized (TRANSACTION_LOCK) {
            if (PreferenceIntegrity.beginWriteEx(key) != PreferenceIntegrity.TokenResult.OK) {
                // beginWriteEx already latched/gated a real fault. A launch token
                // refusal is not a fault: just fail fast, without markAmbiguous.
                report(key, operation + " refused: write-ahead marker unavailable " +
                        "(launch in progress or integrity quarantined)");
                return false;
            }
            try {
                SharedPreferences.Editor editor = prefs.edit();
                change.apply(editor);
                boolean committed = editor.commit();
                if (!committed) {
                    report(key, operation + " commit() returned false");
                    PreferenceIntegrity.markAmbiguous(key);
                    return false;
                }
                if (!PreferenceIntegrity.finishWriteSuccess(key)) {
                    // The latch/gate were already handled by finishWriteSuccess;
                    // do not add a second ambiguous record or disable call.
                    report(key, operation + " committed but the integrity marker " +
                            "could not be durably completed");
                    return false;
                }
                return true;
            } catch (RuntimeException e) {
                report(key, operation + " threw " + e);
                PreferenceIntegrity.markAmbiguous(key);
                return false;
            }
        }
    }

    private void report(String key, String detail) {
        Log.e(TAG, "Failed to persist preference '" + key + "': " + detail);
        if (reporter != null) reporter.onPreferenceWriteFailed(key, detail);
    }
}
