// Modified 2026-11-25 (DFRoot GZIF round-4 PR #3 phase 3): write-ahead marker
// published before commit, synchronous durable boot gate, strict marker
// validation and lifecycle-safe listeners (P2-08/P2-10). See NOTICE.
// Round-4 follow-up: unattributable faults latch WILDCARD, fail-closed is
// sticky across beginWrite/finishWriteSuccess, and valid restores gate at boot.
package df.root;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process-wide quarantine latch for SharedPreferences consistency (P2-08).
 *
 * commit() can report failure after changing the in-memory SharedPreferences
 * map: memory and the on-disk XML then disagree, and the native bootstrap reads
 * the XML. A write is therefore bracketed by a durable write-ahead transaction:
 * {@link #beginWrite(String)} publishes a PENDING marker (with the key) BEFORE
 * the commit, and {@link #finishWriteSuccess(String)} clears it only after the
 * commit reports success. If anything is uncertain -- interrupted thread,
 * storage fault, half-written marker, unreadable/invalid marker -- the latch
 * stays quarantined and the boot component is disabled synchronously at the
 * failure boundary. A later successful write of the SAME key is the only proof
 * that memory and disk agree again; a different key cannot clear the latch.
 *
 * The gate is an injected seam. Production wires {@code AndroidBootGate} from
 * {@link #init(Context)} only; {@link #init(File)} is pure Java so host-only JVM
 * tests never load Android classes through the durability path.
 */
public final class PreferenceIntegrity {

    private static final String TAG = "dfroot";
    private static final String MARKER_NAME = "pref_integrity.marker";
    private static final String TMP_SUFFIX = ".tmp";

    /** Published before a commit; a restart must treat this as uncertain. */
    static final String SENTINEL_PENDING = "DFROOT_PREF_PENDING_V1";
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

    /** Durable side effect invoked at the failure boundary (never auto-enables). */
    interface BootGate {
        void disableBootLaunch();

        /**
         * UI-driven variant. The default is synchronous (and is what host fakes
         * and the failure boundary use); the production gate may move this call
         * off the UI thread. The caller's latch stays fail-closed either way.
         */
        default void disableBootLaunchAsync(Runnable onDone) {
            disableBootLaunch();
            if (onDone != null) onDone.run();
        }
    }

    /** Storage seam: atomically publish bytes, or durably remove the marker. */
    interface MarkerIo {
        boolean publish(File marker, byte[] bytes);
        boolean delete(File marker);
    }

    private static final Object LOCK = new Object();
    private static final MarkerIo FILE_IO = new FileMarkerIo();

    private static final Set<String> sAmbiguousKeys = new HashSet<>();
    /** Write-ahead transactions currently in flight, per normalized key. */
    private static final Map<String, Integer> sPending = new HashMap<>();
    /** Keys whose PENDING marker was actually published and validated. */
    private static final Set<String> sPendingValidated = new HashSet<>();
    private static final List<Listener> sListeners = new ArrayList<>();

    private static BootGate sBootGate;          // null until init(Context) or a test injects one
    private static MarkerIo sMarkerIo = FILE_IO;
    private static boolean sInitialized;
    private static File sMarkerFile;
    private static boolean sQuarantined;
    private static boolean sMarkerActive;
    private static boolean sMarkerValid;        // set only after strict read-back validation
    private static boolean sRestoredAfterRestart;
    private static boolean sFailClosed;

    private PreferenceIntegrity() {}

    /** Initialize from a Context; the marker lives in device-protected storage. */
    public static void init(Context ctx) {
        if (isInitialized()) return; // idempotent
        // The production gate is wired here and ONLY here, so init(File) and all
        // host-only tests stay independent of android.content.pm classes.
        sBootGate = ctx == null ? null : new AndroidBootGate(ctx.getApplicationContext());
        if (ctx == null) {
            init((File) null);
            return;
        }
        File marker;
        try {
            Context de = ctx.createDeviceProtectedStorageContext();
            File dir = de == null ? null : de.getFilesDir();
            marker = dir == null ? null : new File(dir, MARKER_NAME);
        } catch (RuntimeException e) {
            Log.e(TAG, "PreferenceIntegrity: cannot resolve DE storage", e);
            marker = null;
        }
        init(marker);
    }

    /**
     * Initialize from an explicit marker file (host tests / injected storage).
     * Idempotent: a second call does not re-read the file and cannot drop an
     * in-process quarantine. Use resetForTest() to start a fresh state.
     */
    public static void init(File markerFile) {
        boolean restoredValid = false;
        synchronized (LOCK) {
            if (sInitialized) return;
            sInitialized = true;
            sMarkerFile = markerFile;
            if (markerFile == null) {
                // No durable storage: fail closed, and the gate disables boot.
                failClosedLocked();
                return;
            }
            try {
                if (Files.exists(tempFile(markerFile).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    // A leftover temp file can be a half-published marker.
                    failClosedLocked();
                    return;
                }
                MarkerData data;
                try {
                    data = readValidatedMarker(markerFile);
                } catch (NoSuchFileException missing) {
                    data = null; // no marker: clean start
                }
                if (data == null) {
                    if (Files.exists(markerFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        // Symlink, directory, stray bytes, bad UTF-8, missing
                        // newline or bad key lines: never assume a clean state.
                        failClosedLocked();
                        // When the path is a repairable regular file, republish
                        // a valid WILDCARD marker so the gate survives another
                        // restart. Symlinks, directories and stray temp files
                        // are NOT repaired and stay fail-closed.
                        if (Files.isRegularFile(markerFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            writeMarkerLocked(SENTINEL_AMBIGUOUS);
                        }
                    }
                    return;
                }
                sQuarantined = true;
                sRestoredAfterRestart = true;
                sMarkerActive = true;
                sMarkerValid = true;
                sAmbiguousKeys.addAll(data.keys.isEmpty()
                        ? Collections.singleton(WILDCARD) : data.keys);
                // Record that this restart observed the persisted quarantine.
                if (!writeMarkerLocked(SENTINEL_RESTORED)) failClosedLocked();
                else restoredValid = true;
            } catch (Exception e) {
                // Kept free of Android logging on purpose: this path is pure Java.
                failClosedLocked();
            }
        }
        // A restored marker is authoritative from process start: make the
        // component state durable before any screen can display it.
        if (restoredValid) disableBootLaunchNow();
    }

    /** True when launch/boot must be blocked; uninitialized also fails closed. */
    public static boolean isQuarantined() {
        synchronized (LOCK) {
            return !sInitialized || sQuarantined;
        }
    }

    /**
     * True only when the quarantine is durably recorded: a validated regular
     * file with a recognized sentinel and no leftover temp file. A flag alone
     * (or a bare isFile() check) would trust an incomplete key set.
     */
    public static boolean durableMarkerActive() {
        synchronized (LOCK) {
            if (!sInitialized || !sMarkerActive || !sMarkerValid || sMarkerFile == null) {
                return false;
            }
            try {
                if (Files.exists(tempFile(sMarkerFile).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                return readValidatedMarker(sMarkerFile) != null;
            } catch (Exception e) {
                return false;
            }
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

    /**
     * Write-ahead step: durably publish a PENDING marker for this key BEFORE the
     * caller commits. Any fault latches quarantine and disables boot
     * synchronously, and false means the caller MUST NOT attempt the commit.
     */
    public static boolean beginWrite(String key) {
        synchronized (LOCK) {
            String k = keyFor(key);
            if (!sInitialized) {
                sAmbiguousKeys.add(k);
                failClosedLocked();
                return false;
            }
            if (sFailClosed) {
                // Sticky fail-closed: an unattributable fault can never be
                // cleared by starting a new, unrelated transaction.
                sAmbiguousKeys.add(WILDCARD);
                disableBootSafelyLocked(); // synchronous gate call, no commit
                return false;
            }
            sAmbiguousKeys.add(k);
            sPending.put(k, sPending.getOrDefault(k, 0) + 1);
            sQuarantined = true;
            if (!writeMarkerLocked(SENTINEL_PENDING)) {
                // No commit will follow: resolve this write-ahead as ambiguous.
                decrementPendingLocked(k);
                sPendingValidated.remove(k);
                return false;
            }
            sPendingValidated.add(k);
            return true;
        }
    }

    /**
     * Commit succeeded: drop this key from the in-flight set and republish.
     * The latch is cleared only for a fully validated PENDING -> commit cycle
     * of the SAME key, and never while a sticky fail-closed fault is latched.
     */
    public static void finishWriteSuccess(String key) {
        boolean changed = false;
        synchronized (LOCK) {
            String k = keyFor(key);
            if (!sInitialized) {
                boolean before = sQuarantined;
                failClosedLocked();
                changed = !before;
            } else if (sFailClosed) {
                // Unattributable faults stay quarantined for every key, even if
                // a caller reports a successful commit for an unrelated one.
                sAmbiguousKeys.add(WILDCARD);
                sPending.remove(k);
                sPendingValidated.remove(k);
                disableBootSafelyLocked();
            } else if (!sPendingValidated.contains(k)) {
                // No validated write-ahead for this key: success cannot prove
                // that memory and the XML agree, so fail closed.
                boolean before = sQuarantined;
                failClosedLocked();
                changed = !before;
            } else {
                boolean before = sMarkerActive && sMarkerValid;
                decrementPendingLocked(k);
                sPendingValidated.remove(k);
                sAmbiguousKeys.remove(k);
                boolean ok;
                if (totalPendingLocked() > 0) {
                    ok = writeMarkerLocked(SENTINEL_PENDING);
                } else if (sAmbiguousKeys.isEmpty()) {
                    ok = deleteMarkerLocked();
                } else {
                    ok = writeMarkerLocked(SENTINEL_AMBIGUOUS);
                }
                if (ok && totalPendingLocked() == 0 && sAmbiguousKeys.isEmpty()) {
                    sQuarantined = false;
                    sMarkerActive = false;
                    sMarkerValid = false;
                    sRestoredAfterRestart = false;
                }
                changed |= before != (sMarkerActive && sMarkerValid);
                if (ok && totalPendingLocked() == 0 && sAmbiguousKeys.isEmpty()) changed = true;
            }
        }
        if (changed) notifyListeners();
    }

    /**
     * A commit() failure (or thrown write) makes this key's state ambiguous.
     * The boot gate runs synchronously at this failure boundary, before the
     * marker is (re)written, so a marker fault can never defer the disable to a
     * later UI callback. An in-flight write-ahead for this key is resolved here.
     */
    public static void markAmbiguous(String key) {
        boolean changed;
        synchronized (LOCK) {
            String k = keyFor(key);
            boolean before = sInitialized && sMarkerActive && sMarkerValid;
            changed = !sQuarantined;
            sQuarantined = true;
            sAmbiguousKeys.add(k);
            if (sPending.containsKey(k)) decrementPendingLocked(k);
            sPendingValidated.remove(k); // this transaction did not prove consistency
            if (!sInitialized) {
                // No marker could exist yet; still disable boot if a gate is wired.
                failClosedLocked();
            } else {
                disableBootSafelyLocked();
                writeMarkerLocked(SENTINEL_AMBIGUOUS);
            }
            changed |= before != (sMarkerActive && sMarkerValid);
        }
        if (changed) notifyListeners();
    }

    /**
     * A successful write of the SAME key restores consistency. A different key
     * cannot clear another key's ambiguity, and a wildcard is only cleared by
     * an explicitly attributed write-ahead for the wildcard itself. Nothing
     * here ever enables the boot component.
     */
    public static void markConsistent(String key) {
        boolean changed = false;
        synchronized (LOCK) {
            if (!sInitialized || sFailClosed || key == null || key.isEmpty()) return;
            String k = keyFor(key);
            if (sPending.containsKey(k) || !sAmbiguousKeys.remove(k)) return;
            if (sAmbiguousKeys.isEmpty()) {
                if (!deleteMarkerLocked()) return; // fail closed: keep quarantine
                sQuarantined = false;
                sMarkerActive = false;
                sMarkerValid = false;
                sRestoredAfterRestart = false;
            } else if (!writeMarkerLocked(SENTINEL_AMBIGUOUS)) {
                return; // marker no longer matches in-memory state
            }
            changed = true;
        }
        if (changed) notifyListeners();
    }

    /** UI-only helper: apply the shared gate on quarantine, regardless of marker health. */
    public static void disableBootLaunchNow() {
        synchronized (LOCK) {
            disableBootSafelyLocked();
        }
    }

    /**
     * UI-driven, non-blocking variant: the production gate may run its
     * PackageManager call on a background thread and invokes onDone afterwards.
     * The quarantine latch is left untouched (fail-closed), so this never
     * weakens the synchronous failure-boundary gate used by PreferenceIntegrity.
     */
    public static void disableBootLaunchFromUi(Runnable onDone) {
        BootGate gate;
        synchronized (LOCK) {
            gate = sBootGate;
        }
        if (gate == null) {
            if (onDone != null) onDone.run();
            return;
        }
        try {
            gate.disableBootLaunchAsync(onDone);
        } catch (RuntimeException e) {
            if (onDone != null) onDone.run();
        }
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        synchronized (LOCK) {
            if (!containsListenerLocked(listener)) sListeners.add(listener);
        }
    }

    /** Identity removal: a new screen instance never removes the old one by accident. */
    public static void removeListener(Listener listener) {
        if (listener == null) return;
        synchronized (LOCK) {
            for (int i = sListeners.size() - 1; i >= 0; i--) {
                if (sListeners.get(i) == listener) sListeners.remove(i);
            }
        }
    }

    /** Test-only reset; never called from production code. */
    public static void resetForTest() {
        synchronized (LOCK) {
            sInitialized = false;
            sMarkerFile = null;
            sQuarantined = false;
            sMarkerActive = false;
            sMarkerValid = false;
            sRestoredAfterRestart = false;
            sFailClosed = false;
            sAmbiguousKeys.clear();
            sPending.clear();
            sPendingValidated.clear();
            sBootGate = null;
            sMarkerIo = FILE_IO;
            sListeners.clear();
        }
    }

    /** Test-only seam injection; resetForTest() restores production defaults. */
    static void setBootGateForTest(BootGate gate) {
        synchronized (LOCK) {
            sBootGate = gate;
        }
    }

    /** Test-only seam injection; resetForTest() restores production defaults. */
    static void setMarkerIoForTest(MarkerIo io) {
        synchronized (LOCK) {
            sMarkerIo = io == null ? FILE_IO : io;
        }
    }

    private static boolean containsListenerLocked(Listener listener) {
        for (Listener registered : sListeners) {
            if (registered == listener) return true;
        }
        return false;
    }

    private static void notifyListeners() {
        List<Listener> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(sListeners);
        }
        for (Listener listener : snapshot) {
            // A listener removed while the snapshot was being delivered is
            // skipped: a recreated screen must not receive stale callbacks.
            synchronized (LOCK) {
                if (!containsListenerLocked(listener)) continue;
            }
            try {
                listener.onIntegrityChanged();
            } catch (RuntimeException e) {
                // One bad listener must not break the latch or other listeners.
            }
        }
    }

    private static void disableBootSafelyLocked() {
        BootGate gate = sBootGate;
        if (gate == null) return;
        try {
            gate.disableBootLaunch();
        } catch (RuntimeException e) {
            // Gate failed: the component may still be enabled, so the fault is
            // unattributable and no later key may clear the quarantine.
            noteFailClosedLocked();
        }
    }

    /** Caller holds LOCK. Unattributable faults must never be key-clearable. */
    private static void noteFailClosedLocked() {
        sFailClosed = true;
        sAmbiguousKeys.add(WILDCARD);
    }

    /** Caller holds LOCK. Always the conservative state; no Android logging here. */
    private static void failClosedLocked() {
        sQuarantined = true;
        noteFailClosedLocked();
        sMarkerActive = false;
        sMarkerValid = false;
        disableBootSafelyLocked();
    }

    private static String keyFor(String key) {
        if (key == null || key.isEmpty() || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            return WILDCARD;
        }
        return key;
    }

    private static int totalPendingLocked() {
        int total = 0;
        for (int count : sPending.values()) total += count;
        return total;
    }

    private static void decrementPendingLocked(String key) {
        Integer count = sPending.get(key);
        if (count == null) return;
        if (count <= 1) sPending.remove(key);
        else sPending.put(key, count - 1);
    }

    private static Set<String> expectedKeysLocked() {
        return sAmbiguousKeys.isEmpty()
                ? Collections.singleton(WILDCARD) : new HashSet<>(sAmbiguousKeys);
    }

    private static byte[] markerBytes(String sentinel) {
        StringBuilder text = new StringBuilder(sentinel).append('\n');
        List<String> keys = new ArrayList<>(expectedKeysLocked());
        Collections.sort(keys);
        for (String key : keys) text.append(key).append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Caller holds LOCK. Durable publish + strict read-back; fail-closed on any fault. */
    private static boolean writeMarkerLocked(String sentinel) {
        if (sMarkerFile == null || Thread.currentThread().isInterrupted()) {
            failClosedLocked();
            return false;
        }
        try {
            byte[] bytes = markerBytes(sentinel);
            if (!sMarkerIo.publish(sMarkerFile, bytes)
                    || Thread.currentThread().isInterrupted()) {
                failClosedLocked();
                return false;
            }
            MarkerData check = readValidatedMarker(sMarkerFile);
            if (check == null || !check.sentinel.equals(sentinel)
                    || !check.keys.equals(expectedKeysLocked())) {
                failClosedLocked();
                return false;
            }
            sMarkerActive = true;
            sMarkerValid = true;
            return true;
        } catch (Exception e) {
            failClosedLocked();
            return false;
        }
    }

    /** Caller holds LOCK. A failed clear keeps gating rather than trusting the file. */
    private static boolean deleteMarkerLocked() {
        if (sMarkerFile == null || Thread.currentThread().isInterrupted()) {
            failClosedLocked();
            return false;
        }
        try {
            if (!sMarkerIo.delete(sMarkerFile) || Thread.currentThread().isInterrupted()) {
                failClosedLocked();
                return false;
            }
            sMarkerActive = false;
            sMarkerValid = false;
            return true;
        } catch (Exception e) {
            failClosedLocked();
            return false;
        }
    }

    private static File tempFile(File marker) {
        File parent = marker.getParentFile();
        return parent == null ? new File(marker.getName() + TMP_SUFFIX)
                : new File(parent, marker.getName() + TMP_SUFFIX);
    }

    /** Strict, read-only validation; returns null for ANY unexpected marker state. */
    private static MarkerData readValidatedMarker(File marker) throws IOException {
        Path path = marker.toPath();
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) return null;
        String text = decodeStrict(readAllNoFollow(path));
        if (!text.endsWith("\n")) return null; // truncated write
        String[] lines = text.split("\n", -1);
        String sentinel = lines[0];
        if (!SENTINEL_PENDING.equals(sentinel)
                && !SENTINEL_AMBIGUOUS.equals(sentinel)
                && !SENTINEL_RESTORED.equals(sentinel)) {
            return null;
        }
        Set<String> keys = new HashSet<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i];
            if (line.isEmpty() || !line.equals(line.trim())) return null;
            keys.add(line);
        }
        return new MarkerData(sentinel, keys);
    }

    private static String decodeStrict(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("marker is not strict UTF-8", e);
        }
    }

    private static byte[] readAllNoFollow(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(4096);
            int zeroReads = 0;
            while (true) {
                buf.clear();
                int n = ch.read(buf);
                if (n < 0) break;
                if (n == 0) {
                    if (++zeroReads > 64) throw new IOException("stalled marker read");
                    continue;
                }
                zeroReads = 0;
                out.write(buf.array(), 0, n);
            }
            return out.toByteArray();
        }
    }

    private static final class MarkerData {
        final String sentinel;
        final Set<String> keys;

        MarkerData(String sentinel, Set<String> keys) {
            this.sentinel = sentinel;
            this.keys = keys;
        }
    }

    /**
     * Production storage: publish a temp sibling with CREATE_NEW, full-write
     * loop, force(true), byte-for-byte read-back, atomic rename (validated
     * REPLACE_EXISTING fallback) and parent-directory fsync. Interruption is a
     * fault: it must never leave a marker that looks complete but is not.
     */
    private static final class FileMarkerIo implements MarkerIo {
        @Override
        public boolean publish(File marker, byte[] bytes) {
            Path target = marker.toPath();
            Path tmp = tempFile(marker).toPath();
            if (Thread.currentThread().isInterrupted()) return false;
            try {
                Path parent = target.getParent();
                if (parent != null && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(parent);
                }
                if (Files.exists(tmp, LinkOption.NOFOLLOW_LINKS)) return false;
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.readAttributes(target, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                    return false;
                }
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    ByteBuffer buf = ByteBuffer.wrap(bytes);
                    while (buf.hasRemaining()) {
                        int n = ch.write(buf);
                        if (n <= 0) throw new IOException("short marker write");
                    }
                    ch.force(true);
                }
                if (Thread.currentThread().isInterrupted()) {
                    deleteQuietly(tmp);
                    return false;
                }
                if (!Arrays.equals(bytes, readAllNoFollow(tmp))) {
                    deleteQuietly(tmp);
                    return false;
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                // Validate the fallback (and the atomic path) before trusting it.
                if (!Files.readAttributes(target, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isRegularFile()
                        || !Arrays.equals(bytes, readAllNoFollow(target))
                        || !fsyncParent(parent)
                        || Thread.currentThread().isInterrupted()) {
                    return false;
                }
                return true;
            } catch (Exception e) {
                deleteQuietly(tmp);
                return false;
            }
        }

        @Override
        public boolean delete(File marker) {
            Path target = marker.toPath();
            if (Thread.currentThread().isInterrupted()) return false;
            try {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.deleteIfExists(target)) {
                    return false;
                }
                if (Thread.currentThread().isInterrupted()) return false;
                return fsyncParent(target.getParent());
            } catch (Exception e) {
                return false;
            }
        }

        private static void deleteQuietly(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
                // Best effort cleanup of a temp sibling; the marker stays absent.
            }
        }

        private static boolean fsyncParent(Path parent) {
            if (parent == null) return false;
            try (FileChannel ch = FileChannel.open(parent, StandardOpenOption.READ)) {
                ch.force(true);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
