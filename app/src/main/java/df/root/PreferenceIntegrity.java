// Modified 2026-11-25 (DFRoot GZIF round-3 PR #3 phase 3): process-wide
// SharedPreferences integrity latch with a durable device-protected marker. See NOTICE.
package df.root;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Process-wide quarantine latch for SharedPreferences consistency (P2-08).
 *
 * commit() can report failure after changing the in-memory SharedPreferences
 * map: memory and the on-disk XML then disagree, and the native bootstrap reads
 * the XML. A failed write therefore marks its key ambiguous and blocks launch
 * (and boot) until the SAME key is written successfully again. Only success is
 * proof that memory and disk agree again; a different key cannot clear the
 * latch.
 *
 * The latch is mirrored to a small marker file in device-protected storage so
 * it survives process death and can gate BootReceiver on the next boot. A marker
 * found at init() is restored (SENTINEL_RESTORED) and keeps the quarantine
 * active. Any marker read/write/delete failure fails closed: quarantine stays
 * set and the marker is reported inactive, so the UI disables BootReceiver
 * instead of trusting a gate that the app could not persist.
 *
 * This class has no dependency on a live Android runtime: init(File) is used
 * by the host-only JVM tests.
 */
public final class PreferenceIntegrity {

    private static final String TAG = "dfroot";
    private static final String MARKER_NAME = "pref_integrity.marker";

    /** Written when an ambiguous key is recorded. */
    static final String SENTINEL_AMBIGUOUS = "DFROOT_PREF_AMBIGUOUS_V1";
    /** Written when a persisted quarantine is observed after a restart. */
    static final String SENTINEL_RESTORED = "DFROOT_PREF_RESTORED_V1";
    /** Used when quarantine cannot be attributed to a particular key. */
    private static final String WILDCARD = "*";

    /** Notified whenever the quarantine/marker state changes. */
    public interface Listener {
        void onIntegrityChanged();
    }

    private static final Object LOCK = new Object();
    private static final Set<String> sAmbiguousKeys = new HashSet<>();
    private static final List<Listener> sListeners = new ArrayList<>();

    private static boolean sInitialized;
    private static File sMarkerFile;
    private static boolean sQuarantined;
    private static boolean sMarkerActive;
    private static boolean sRestoredAfterRestart;
    private static boolean sFailClosed;

    private PreferenceIntegrity() {}

    /** Initialize from a Context; the marker lives in device-protected storage. */
    public static void init(Context ctx) {
        if (isInitialized()) return; // idempotent
        if (ctx == null) {
            init((File) null);
            return;
        }
        try {
            Context de = ctx.createDeviceProtectedStorageContext();
            File dir = de == null ? null : de.getFilesDir();
            init(dir == null ? null : new File(dir, MARKER_NAME));
        } catch (RuntimeException e) {
            Log.e(TAG, "PreferenceIntegrity: cannot resolve DE storage", e);
            init((File) null);
        }
    }

    /**
     * Initialize from an explicit marker file (host tests / injected storage).
     * Idempotent: a second call does not re-read the file and cannot drop an
     * in-process quarantine. Use resetForTest() to start a fresh state.
     */
    public static void init(File markerFile) {
        synchronized (LOCK) {
            if (sInitialized) return;
            sInitialized = true;
            sMarkerFile = markerFile;
            if (markerFile == null) {
                // No durable storage: fail closed, and the UI will disable BootReceiver.
                sQuarantined = true;
                sFailClosed = true;
                sMarkerActive = false;
                return;
            }
            try {
                if (markerFile.isFile()) {
                    String content = new String(Files.readAllBytes(markerFile.toPath()),
                            StandardCharsets.UTF_8);
                    String[] lines = content.split("\\R", -1);
                    String sentinel = lines.length == 0 ? "" : lines[0].trim();
                    if (!SENTINEL_AMBIGUOUS.equals(sentinel)
                            && !SENTINEL_RESTORED.equals(sentinel)) {
                        // Unrecognized marker: never assume a clean state.
                        sQuarantined = true;
                        sFailClosed = true;
                        sMarkerActive = false;
                        return;
                    }
                    sQuarantined = true;
                    sRestoredAfterRestart = true;
                    sMarkerActive = true;
                    for (int i = 1; i < lines.length; i++) {
                        String key = lines[i].trim();
                        if (!key.isEmpty()) sAmbiguousKeys.add(key);
                    }
                    // Record that this restart observed the persisted quarantine.
                    writeMarkerLocked(SENTINEL_RESTORED);
                }
            } catch (Exception e) {
                Log.e(TAG, "PreferenceIntegrity: cannot restore marker", e);
                sQuarantined = true;
                sFailClosed = true;
                sMarkerActive = false;
            }
        }
    }

    /** True when launch/boot must be blocked; uninitialized also fails closed. */
    public static boolean isQuarantined() {
        synchronized (LOCK) {
            return !sInitialized || sQuarantined;
        }
    }

    /** True only when the quarantine is durably recorded on disk. */
    public static boolean durableMarkerActive() {
        synchronized (LOCK) {
            return sInitialized && sMarkerActive && sMarkerFile != null && sMarkerFile.isFile();
        }
    }

    /** For host tests: a persisted quarantine was restored on this init(). */
    static boolean restoredAfterRestart() {
        synchronized (LOCK) {
            return sRestoredAfterRestart;
        }
    }

    static boolean isInitialized() {
        synchronized (LOCK) {
            return sInitialized;
        }
    }

    /** A commit() failure (or thrown write) makes this key's state ambiguous. */
    public static void markAmbiguous(String key) {
        boolean changed;
        synchronized (LOCK) {
            boolean beforeActive = sMarkerActive;
            if (!sInitialized) {
                sQuarantined = true; // fail closed even before init()
                changed = true;
            } else {
                changed = !sQuarantined;
                sQuarantined = true;
                sAmbiguousKeys.add(key == null || key.isEmpty() ? WILDCARD : key);
                writeMarkerLocked(SENTINEL_AMBIGUOUS);
                changed |= beforeActive != sMarkerActive;
            }
        }
        if (changed) notifyListeners();
    }

    /**
     * A successful write of the SAME key restores consistency. A different key
     * cannot clear another key's ambiguity; I/O failures can only be cleared by
     * resetForTest() (i.e. after app data is cleared / a deliberate recovery).
     */
    public static void markConsistent(String key) {
        boolean changed = false;
        synchronized (LOCK) {
            if (!sInitialized || sFailClosed || key == null || key.isEmpty()) return;
            if (!sAmbiguousKeys.remove(key) && !sAmbiguousKeys.remove(WILDCARD)) return;
            if (sAmbiguousKeys.isEmpty()) {
                if (!deleteMarkerLocked()) return; // fail closed: keep quarantine
                sQuarantined = false;
                sMarkerActive = false;
                sRestoredAfterRestart = false;
            } else if (!writeMarkerLocked(SENTINEL_AMBIGUOUS)) {
                return; // fail closed: marker no longer matches in-memory state
            }
            changed = true;
        }
        if (changed) notifyListeners();
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        synchronized (LOCK) {
            if (!sListeners.contains(listener)) sListeners.add(listener);
        }
    }

    /** Test-only reset; never called from production code. */
    public static void resetForTest() {
        synchronized (LOCK) {
            sInitialized = false;
            sMarkerFile = null;
            sQuarantined = false;
            sMarkerActive = false;
            sRestoredAfterRestart = false;
            sFailClosed = false;
            sAmbiguousKeys.clear();
            sListeners.clear();
        }
    }

    private static void notifyListeners() {
        List<Listener> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(sListeners);
        }
        for (Listener listener : snapshot) {
            try {
                listener.onIntegrityChanged();
            } catch (RuntimeException e) {
                Log.e(TAG, "PreferenceIntegrity: listener failed", e);
            }
        }
    }

    /** Caller holds LOCK. Returns true on success; failures latch fail-closed. */
    private static boolean writeMarkerLocked(String sentinel) {
        if (sMarkerFile == null) {
            sMarkerActive = false;
            sFailClosed = true;
            sQuarantined = true;
            return false;
        }
        try {
            File parent = sMarkerFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            StringBuilder text = new StringBuilder(sentinel).append('\n');
            List<String> keys = new ArrayList<>(sAmbiguousKeys);
            Collections.sort(keys);
            for (String key : keys) text.append(key).append('\n');
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(sMarkerFile.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(ByteBuffer.wrap(bytes));
                ch.force(true); // durable before any launch/boot gate relies on it
            }
            sMarkerActive = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "PreferenceIntegrity: cannot write marker", e);
            sMarkerActive = false;
            sFailClosed = true;
            sQuarantined = true; // fail closed: never trust an unpersisted gate
            return false;
        }
    }

    /** Caller holds LOCK. */
    private static boolean deleteMarkerLocked() {
        if (sMarkerFile == null) return false;
        try {
            Files.deleteIfExists(sMarkerFile.toPath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "PreferenceIntegrity: cannot clear marker", e);
            sMarkerActive = sMarkerFile.isFile();
            sFailClosed = true;
            sQuarantined = true; // fail closed: stale marker must keep gating
            return false;
        }
    }
}
