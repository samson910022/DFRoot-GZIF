// Host-only tests for the write-ahead marker and the synchronous boot gate.
// No Android runtime, device, privileged operation or /dev access is involved.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Exercises the injected MarkerIo/BootGate seams and a FakePreferences whose
 * commit hook observes the marker at the exact moment of the preference write.
 */
public class PreferenceIntegrityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File marker;
    private RecordingGate gate;
    private FakePreferences prefs;
    private PreferencePersister persister;

    @Before
    public void setUp() throws Exception {
        marker = new File(tmp.getRoot(), "pref_integrity.marker");
        reinitWith(null); // default (real filesystem) IO, injected recording gate
        prefs = new FakePreferences(marker);
        persister = new PreferencePersister(prefs);
    }

    @After
    public void tearDown() {
        PreferenceIntegrity.resetForTest();
    }

    private void reinitWith(PreferenceIntegrity.MarkerIo io) {
        PreferenceIntegrity.resetForTest();
        gate = new RecordingGate();
        PreferenceIntegrity.setBootGateForTest(gate);
        PreferenceIntegrity.setMarkerIoForTest(io);
        PreferenceIntegrity.init(marker);
    }

    private String markerContent() throws IOException {
        return new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
    }

    private void writeMarker(String text) throws IOException {
        Files.write(marker.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void pendingMarkerIsPublishedBeforeCommitAndClearedOnSuccess() throws Exception {
        assertTrue(persister.putString("su_manager", "com.rifsxd.ksunext"));
        assertEquals(1, prefs.commitCalls);
        assertNotNull("marker must exist at commit time", prefs.markerAtCommit);
        assertTrue(prefs.markerAtCommit.startsWith(PreferenceIntegrity.SENTINEL_PENDING));
        assertTrue(prefs.markerAtCommit.contains("su_manager"));
        // No failure: no boot-disable side effect, no lingering marker.
        assertEquals(0, gate.disableCalls);
        assertFalse(PreferenceIntegrity.isQuarantined());
        assertFalse(marker.exists());
    }

    @Test
    public void failedCommitQuarantinesAndDisablesBootSynchronouslyAtBoundary() throws Exception {
        prefs.commitResult = false;
        assertFalse(persister.putString("su_manager", "pkg"));
        // The gate ran inside the call, before any UI callback could be posted.
        assertEquals(1, gate.disableCalls);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        String content = markerContent();
        assertTrue(content.startsWith(PreferenceIntegrity.SENTINEL_AMBIGUOUS));
        assertTrue(content.contains("su_manager"));
    }

    @Test
    public void partialMarkerWriteFailsClosedWithGateAndInitStaysQuarantined() throws Exception {
        PreferenceIntegrity.MarkerIo partial = new PreferenceIntegrity.MarkerIo() {
            @Override
            public boolean publish(File m, byte[] bytes) {
                try {
                    // Simulate an interrupted/short write: truncated bytes on disk.
                    Files.write(m.toPath(), Arrays.copyOf(bytes, Math.min(8, bytes.length)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return false; // the seam reports the fault
            }

            @Override
            public boolean delete(File m) {
                return true;
            }
        };
        reinitWith(partial);
        assertFalse(persister.putString("su_manager", "pkg"));
        assertEquals("no commit may follow a marker fault", 0, prefs.commitCalls);
        assertTrue(gate.disableCalls >= 1);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertFalse(PreferenceIntegrity.durableMarkerActive());

        // Restart: the truncated marker is not trusted as a gate, but the
        // regular file is repaired to a valid WILDCARD marker. The sticky
        // fault means no key can clear the quarantine afterwards.
        reinitWith(null);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        assertFalse(PreferenceIntegrity.beginWrite("unrelated.key"));
        assertTrue(gate.disableCalls >= 1);
    }

    @Test
    public void storageFaultOnMarkerWriteAbortsBeforeCommit() {
        PreferenceIntegrity.MarkerIo fault = new PreferenceIntegrity.MarkerIo() {
            @Override
            public boolean publish(File m, byte[] bytes) {
                throw new RuntimeException("simulated storage fault");
            }

            @Override
            public boolean delete(File m) {
                return false;
            }
        };
        reinitWith(fault);
        assertFalse(persister.putBoolean("soft_reboot", true));
        assertEquals(0, prefs.commitCalls);
        // Repeated idempotent disables at the failure boundary are harmless;
        // what matters is that the gate ran synchronously, before any commit.
        assertTrue(gate.disableCalls >= 1);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertFalse(marker.exists());
    }

    @Test
    public void crashBeforeCommitRestoresQuarantineFromPendingMarker() throws Exception {
        PreferenceIntegrity.MarkerIo durable = new PreferenceIntegrity.MarkerIo() {
            @Override
            public boolean publish(File m, byte[] bytes) {
                try {
                    Files.write(m.toPath(), bytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return true;
            }

            @Override
            public boolean delete(File m) {
                try {
                    Files.deleteIfExists(m.toPath());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return true;
            }
        };
        reinitWith(durable);
        // Write-ahead published, crash before the preference write happens.
        assertTrue(PreferenceIntegrity.beginWrite("su_manager"));
        assertTrue(markerContent().startsWith(PreferenceIntegrity.SENTINEL_PENDING));

        PreferenceIntegrity.resetForTest();
        PreferenceIntegrity.init(marker); // simulate process death
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.restoredAfterRestart());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        String restored = markerContent();
        assertTrue(restored.startsWith(PreferenceIntegrity.SENTINEL_RESTORED));
        assertTrue(restored.contains("su_manager"));
        // Recovery requires the same key, exactly as for a failed commit.
        PreferenceIntegrity.markConsistent("another.key");
        assertTrue(PreferenceIntegrity.isQuarantined());
        PreferenceIntegrity.markConsistent("su_manager");
        assertFalse(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void nonRegularAndSymlinkMarkerPathsFailClosed() throws Exception {
        Files.createDirectory(marker.toPath());
        reinitWith(null);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertFalse(PreferenceIntegrity.durableMarkerActive());
        assertTrue(gate.disableCalls >= 1);

        Files.delete(marker.toPath());
        Path target = new File(tmp.getRoot(), "real.marker").toPath();
        Files.write(target, (PreferenceIntegrity.SENTINEL_AMBIGUOUS + "\nsu_manager\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(marker.toPath(), target);
        reinitWith(null);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertFalse(PreferenceIntegrity.durableMarkerActive());
        assertTrue(gate.disableCalls >= 1);
    }

    @Test
    public void unrecognizedTruncatedAndStrayTempFailClosed() throws Exception {
        writeMarker("DFROOT_PREF_SOMETHING_ELSE_V1\n");
        reinitWith(null);
        assertTrue(PreferenceIntegrity.isQuarantined());
        // A repairable regular file is republished as a valid WILDCARD marker,
        // but the sticky fault still cannot be cleared by any key.
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        assertTrue(gate.disableCalls >= 1);
        assertTrue(markerContent().contains("*"));

        writeMarker(PreferenceIntegrity.SENTINEL_AMBIGUOUS); // no trailing newline
        reinitWith(null);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        assertTrue(gate.disableCalls >= 1);

        writeMarker(PreferenceIntegrity.SENTINEL_RESTORED + "\nsu_manager\n");
        Files.write(new File(tmp.getRoot(), marker.getName() + ".tmp").toPath(),
                new byte[] { 1, 2, 3 });
        reinitWith(null);
        assertTrue(PreferenceIntegrity.isQuarantined());
        // Stray temp files are never repaired or trusted.
        assertFalse(PreferenceIntegrity.durableMarkerActive());
        assertTrue(gate.disableCalls >= 1);
    }

    @Test
    public void failClosedInitCannotBeClearedByUnrelatedKey() throws Exception {
        // An invalid marker is an unattributable fault: WILDCARD must stick.
        writeMarker("DFROOT_PREF_UNKNOWN_V1\n");
        reinitWith(null);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        assertTrue(markerContent().contains("*"));

        // Write-ahead is refused outright while fail-closed: no commit follows,
        // and the gate is called again at this refusal boundary.
        int callsBefore = gate.disableCalls;
        assertFalse(PreferenceIntegrity.beginWrite("unrelated.key"));
        assertTrue(gate.disableCalls > callsBefore);
        assertTrue(PreferenceIntegrity.isQuarantined());

        // Even a caller that reports a successful commit cannot clear the latch.
        PreferenceIntegrity.finishWriteSuccess("unrelated.key");
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(marker.exists());
        assertTrue(markerContent().contains("*"));

        // The wildcard attribution cannot be cleared by a different key either.
        PreferenceIntegrity.markConsistent("unrelated.key");
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(marker.exists());
    }

    @Test
    public void failedMarkerCompletionNeverConfirmsTheCommitAndStaysQuarantined() {
        PreferenceIntegrity.MarkerIo failDelete = new PreferenceIntegrity.MarkerIo() {
            @Override
            public boolean publish(File m, byte[] bytes) {
                try {
                    Files.write(m.toPath(), bytes);
                    return true;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public boolean delete(File m) {
                return false; // marker cannot be durably retired
            }
        };
        reinitWith(failDelete);
        assertTrue(PreferenceIntegrity.beginWrite("su_manager"));
        assertFalse("an incomplete marker must not confirm a successful commit",
                PreferenceIntegrity.finishWriteSuccess("su_manager"));
        assertEquals(0, PreferenceIntegrity.pendingForTest("su_manager")); // no leak
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(marker.exists());
        assertFalse(PreferenceIntegrity.durableMarkerActive()); // fail-closed, not trusted
        assertTrue(gate.disableCalls >= 1);
        // Sticky fault: neither a new transaction nor an unrelated key can clear it.
        assertFalse(PreferenceIntegrity.beginWrite("other.key"));
        assertFalse(PreferenceIntegrity.finishWriteSuccess("other.key"));
        assertEquals(0, PreferenceIntegrity.pendingForTest("other.key"));
        assertTrue(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void restoredWildcardStaysStickyAndNoKeyCanClearIt() throws Exception {
        writeMarker(PreferenceIntegrity.SENTINEL_AMBIGUOUS + "\n*\n");
        reinitWith(null);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.restoredAfterRestart());
        // The restored marker remains authoritative; only the sticky fault is added.
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        String restored = markerContent();
        assertTrue(restored.contains(PreferenceIntegrity.SENTINEL_RESTORED));
        assertTrue(restored.contains("*"));
        assertTrue(gate.disableCalls >= 1); // gate still ran for the restored marker

        assertFalse(PreferenceIntegrity.beginWrite("su_manager"));
        assertFalse(PreferenceIntegrity.finishWriteSuccess("su_manager"));
        assertFalse(PreferenceIntegrity.finishWriteSuccess("*"));
        PreferenceIntegrity.markConsistent("su_manager");
        PreferenceIntegrity.markConsistent("*");
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        assertTrue(markerContent().contains("*"));
    }

    @Test
    public void launchTokenExcludesWritesAndRefusesConcurrentLaunch() {
        assertEquals(PreferenceIntegrity.TokenResult.OK, PreferenceIntegrity.tryBeginLaunch());
        assertTrue(PreferenceIntegrity.launchHeldForTest());
        // Writes fail fast while a launch owns the decision window: no marker,
        // no quarantine latch, no boot-disable side effect.
        assertEquals(PreferenceIntegrity.TokenResult.LAUNCH_BUSY,
                PreferenceIntegrity.beginWriteEx("su_manager"));
        assertFalse(PreferenceIntegrity.beginWrite("su_manager"));
        assertFalse(marker.exists());
        assertFalse(PreferenceIntegrity.isQuarantined());
        assertEquals(0, gate.disableCalls);
        assertEquals(PreferenceIntegrity.TokenResult.LAUNCH_BUSY,
                PreferenceIntegrity.tryBeginLaunch());
        PreferenceIntegrity.releaseLaunch();
        assertFalse(PreferenceIntegrity.launchHeldForTest());

        // An in-flight write-ahead latches quarantine, so a launch fails closed
        // until that transaction is resolved.
        assertEquals(PreferenceIntegrity.TokenResult.OK,
                PreferenceIntegrity.beginWriteEx("su_manager"));
        assertEquals(PreferenceIntegrity.TokenResult.FAIL_CLOSED,
                PreferenceIntegrity.tryBeginLaunch());
        assertFalse(PreferenceIntegrity.launchHeldForTest());
        assertTrue(PreferenceIntegrity.finishWriteSuccess("su_manager"));
        assertEquals(PreferenceIntegrity.TokenResult.OK, PreferenceIntegrity.tryBeginLaunch());
        PreferenceIntegrity.releaseLaunch();

        // Quarantine (and uninitialized state) refuse a launch outright.
        PreferenceIntegrity.markAmbiguous("su_manager");
        assertEquals(PreferenceIntegrity.TokenResult.FAIL_CLOSED,
                PreferenceIntegrity.tryBeginLaunch());
        assertFalse(PreferenceIntegrity.launchHeldForTest());
        PreferenceIntegrity.resetForTest();
        assertEquals(PreferenceIntegrity.TokenResult.FAIL_CLOSED,
                PreferenceIntegrity.tryBeginLaunch());
        assertFalse(PreferenceIntegrity.launchHeldForTest());
    }

    @Test
    public void finishWithoutValidatedWriteAheadFailsClosedWithoutPendingLeak() {
        assertFalse(PreferenceIntegrity.finishWriteSuccess("su_manager"));
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(gate.disableCalls >= 1);
        assertEquals(0, PreferenceIntegrity.pendingForTest("su_manager"));
        assertFalse(PreferenceIntegrity.beginWrite("su_manager"));
        assertEquals(0, PreferenceIntegrity.pendingForTest("su_manager"));

        // Overlapping same-key transactions also resolve every pending count.
        reinitWith(null);
        assertTrue(PreferenceIntegrity.beginWrite("su_manager"));
        assertTrue(PreferenceIntegrity.beginWrite("su_manager"));
        assertEquals(2, PreferenceIntegrity.pendingForTest("su_manager"));
        assertTrue(PreferenceIntegrity.finishWriteSuccess("su_manager"));
        assertFalse(PreferenceIntegrity.finishWriteSuccess("su_manager"));
        assertEquals(0, PreferenceIntegrity.pendingForTest("su_manager"));
        assertTrue(PreferenceIntegrity.isQuarantined());
    }

    private static final class RecordingGate implements PreferenceIntegrity.BootGate {
        int disableCalls;

        @Override
        public void disableBootLaunch() {
            disableCalls++; // never touches PackageManager or any privileged API
        }
    }

    /** Minimal SharedPreferences with a commit hook that samples the marker. */
    private static final class FakePreferences implements SharedPreferences {
        final Map<String, Object> memory = new HashMap<>();
        final File marker;
        boolean commitResult = true;
        boolean throwOnCommit;
        int commitCalls;
        String markerAtCommit;

        FakePreferences(File marker) {
            this.marker = marker;
        }

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(memory);
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = memory.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = memory.get(key);
            return v instanceof Set ? new HashSet<>((Set<String>) v) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = memory.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = memory.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = memory.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = memory.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return memory.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        private final class FakeEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removed = new HashSet<>();

            private Editor remember(String key, Object value) {
                removed.remove(key);
                pending.put(key, value);
                memory.put(key, value);
                return this;
            }

            @Override
            public Editor putString(String key, String value) { return remember(key, value); }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                return remember(key, new HashSet<>(value));
            }

            @Override
            public Editor putInt(String key, int value) { return remember(key, value); }

            @Override
            public Editor putLong(String key, long value) { return remember(key, value); }

            @Override
            public Editor putFloat(String key, float value) { return remember(key, value); }

            @Override
            public Editor putBoolean(String key, boolean value) { return remember(key, value); }

            @Override
            public Editor remove(String key) {
                pending.remove(key);
                removed.add(key);
                memory.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                pending.clear();
                memory.clear();
                return this;
            }

            @Override
            public boolean commit() {
                commitCalls++;
                try {
                    markerAtCommit = new String(Files.readAllBytes(marker.toPath()),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    markerAtCommit = null;
                }
                if (throwOnCommit) throw new IllegalStateException("fake disk failure");
                if (!commitResult) return false;
                for (String key : removed) memory.remove(key);
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
