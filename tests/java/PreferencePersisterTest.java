package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM-only tests for PreferencePersister using a fake SharedPreferences that
 * models the real memory/disk divergence: an Editor updates the in-memory map
 * immediately, while commit() alone copies the change to the XML on disk.
 * A false/throwing commit leaves memory changed but disk stale, so the key
 * must be quarantined until the SAME key is written successfully.
 *
 * No Android runtime, device or native build is involved.
 */
public class PreferencePersisterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File markerFile;
    private FakePreferences prefs;
    private RecordingReporter reporter;
    private PreferencePersister persister;

    @Before
    public void setUp() throws Exception {
        markerFile = new File(tmp.getRoot(), "pref_integrity.marker");
        PreferenceIntegrity.init(markerFile);
        prefs = new FakePreferences();
        reporter = new RecordingReporter();
        persister = new PreferencePersister(prefs, reporter);
    }

    @After
    public void tearDown() {
        PreferenceIntegrity.resetForTest();
    }

    @Test
    public void putStringCommitsAndStoresValueOnDisk() {
        assertTrue(persister.putString("su_manager", "com.rifsxd.ksunext"));
        assertEquals("com.rifsxd.ksunext", prefs.memory.get("su_manager"));
        assertEquals("com.rifsxd.ksunext", prefs.disk.get("su_manager"));
        assertEquals(1, prefs.commitCalls);
        assertEquals(0, reporter.failures.size());
        assertFalse(PreferenceIntegrity.isQuarantined());
        assertFalse(markerFile.exists());
    }

    @Test
    public void putStringFailureQuarantinesMemoryDiskDivergence() {
        prefs.commitResult = false;
        assertFalse(persister.putString("su_manager", "com.rifsxd.ksunext"));
        assertEquals(1, reporter.failures.size());
        assertEquals("su_manager", reporter.failures.get(0).key);
        // Memory changed, disk did not: the bootstrap would read the old value.
        assertEquals("com.rifsxd.ksunext", prefs.memory.get("su_manager"));
        assertFalse(prefs.disk.containsKey("su_manager"));
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        assertTrue(markerFile.exists());
    }

    @Test
    public void putStringExceptionIsConvertedToFalseAndQuarantines() {
        prefs.throwOnCommit = true;
        assertFalse(persister.putString("su_manager", "com.rifsxd.ksunext"));
        assertEquals(1, reporter.failures.size());
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(markerFile.exists());
    }

    @Test
    public void removeCommitsAndDeletesValueOnDisk() {
        prefs.memory.put("su_manager", "stale.package");
        prefs.disk.put("su_manager", "stale.package");
        assertTrue(persister.remove("su_manager"));
        assertFalse(prefs.memory.containsKey("su_manager"));
        assertFalse(prefs.disk.containsKey("su_manager"));
        assertEquals(0, reporter.failures.size());
        assertFalse(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void removeFailureQuarantinesStaleDiskValue() {
        prefs.memory.put("su_manager", "stale.package");
        prefs.disk.put("su_manager", "stale.package");
        prefs.commitResult = false;
        assertFalse(persister.remove("su_manager"));
        // The removal landed in memory only; disk still holds the stale name.
        assertFalse(prefs.memory.containsKey("su_manager"));
        assertTrue(prefs.disk.containsKey("su_manager"));
        assertEquals(1, reporter.failures.size());
        assertTrue(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void putBooleanCommitsTrueAndFalse() {
        assertTrue(persister.putBoolean("soft_reboot", true));
        assertEquals(Boolean.TRUE, prefs.disk.get("soft_reboot"));
        assertTrue(persister.putBoolean("soft_reboot", false));
        assertEquals(Boolean.FALSE, prefs.disk.get("soft_reboot"));
        assertEquals(0, reporter.failures.size());
        assertFalse(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void putBooleanFailureIsReturnedSoSwitchCanRevert() {
        prefs.commitResult = false;
        assertFalse(persister.putBoolean("soft_reboot", true));
        assertFalse(prefs.disk.containsKey("soft_reboot"));
        assertEquals(1, reporter.failures.size());
        assertEquals("soft_reboot", reporter.failures.get(0).key);
        assertTrue(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void nullReporterDoesNotCrashOnFailure() {
        prefs.commitResult = false;
        PreferencePersister noReporter = new PreferencePersister(prefs);
        assertFalse(noReporter.putBoolean("disable_modules", true));
        assertTrue(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void successfulSameKeyWriteClearsQuarantineAndMarker() {
        prefs.commitResult = false;
        assertFalse(persister.putString("su_manager", "first.package"));
        assertTrue(PreferenceIntegrity.isQuarantined());
        prefs.commitResult = true;
        assertTrue(persister.putString("su_manager", "second.package"));
        assertFalse(PreferenceIntegrity.isQuarantined());
        assertFalse(PreferenceIntegrity.durableMarkerActive());
        assertFalse(markerFile.exists());
    }

    @Test
    public void successfulDifferentKeyWriteDoesNotClearQuarantine() {
        prefs.commitResult = false;
        assertFalse(persister.putString("su_manager", "first.package"));
        prefs.commitResult = true;
        assertTrue(persister.putBoolean("soft_reboot", true));
        // Only the same key can prove su_manager's memory/disk state consistent.
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        assertTrue(markerFile.exists());
    }

    @Test
    public void markerSurvivesRestartAndSameKeyRecovers() throws Exception {
        prefs.commitResult = false;
        assertFalse(persister.putString("su_manager", "first.package"));
        assertTrue(markerFile.exists());
        String before = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(before.contains(PreferenceIntegrity.SENTINEL_AMBIGUOUS));

        // Simulate process death: the latch is re-read from the durable marker.
        PreferenceIntegrity.resetForTest();
        PreferenceIntegrity.init(markerFile);
        assertTrue(PreferenceIntegrity.isQuarantined());
        assertTrue(PreferenceIntegrity.restoredAfterRestart());
        assertTrue(PreferenceIntegrity.durableMarkerActive());
        String restored = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(restored.contains(PreferenceIntegrity.SENTINEL_RESTORED));
        assertTrue(restored.contains("su_manager"));

        PreferenceIntegrity.markConsistent("another.key");
        assertTrue(PreferenceIntegrity.isQuarantined());
        PreferenceIntegrity.markConsistent("su_manager");
        assertFalse(PreferenceIntegrity.isQuarantined());
        assertFalse(markerFile.exists());
    }

    @Test
    public void markerCompletionFailureIsReportedAndNeverReturnedAsSuccess() throws Exception {
        PreferenceIntegrity.MarkerIo failDelete = new PreferenceIntegrity.MarkerIo() {
            @Override
            public boolean publish(File m, byte[] bytes) {
                try {
                    Files.write(m.toPath(), bytes);
                    return true;
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public boolean delete(File m) {
                return false; // retirement not durably recorded
            }
        };
        PreferenceIntegrity.resetForTest();
        PreferenceIntegrity.setMarkerIoForTest(failDelete);
        PreferenceIntegrity.init(markerFile);
        PreferencePersister integrityPersister = new PreferencePersister(prefs, reporter);

        assertFalse("failed marker completion must not be reported as saved",
                integrityPersister.putBoolean("soft_reboot", true));
        assertEquals(1, reporter.failures.size());
        assertTrue(reporter.failures.get(0).detail.contains("integrity marker"));
        assertEquals("commit did run, but the transaction is still uncertain",
                1, prefs.commitCalls);
        assertTrue(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void overlappingWritesAreSerializedAcrossBeginCommitAndFinish() throws Exception {
        CountDownLatch firstCommitEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        AtomicInteger hooks = new AtomicInteger();
        // Block the FIRST commit inside the transaction; no sleeps or device APIs.
        prefs.commitHook = () -> {
            if (hooks.getAndIncrement() == 0) {
                firstCommitEntered.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        boolean[] firstResult = new boolean[1];
        boolean[] secondResult = new boolean[1];
        Thread first = new Thread(() -> firstResult[0] =
                persister.putString("su_manager", "pkg.one"));
        Thread second = new Thread(() -> {
            secondAttempted.countDown();
            secondResult[0] = persister.putBoolean("soft_reboot", true);
        });

        first.start();
        assertTrue(firstCommitEntered.await(5, TimeUnit.SECONDS));
        second.start();
        assertTrue(secondAttempted.await(5, TimeUnit.SECONDS));
        // The second writer cannot reach begin/commit while the first owns the
        // transaction lock, so its decision cannot overlap the first one.
        assertEquals(1, prefs.commitCalls);
        assertTrue(new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8)
                .contains("su_manager"));

        releaseFirst.countDown();
        first.join(5000);
        second.join(5000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertTrue(firstResult[0]);
        assertTrue(secondResult[0]);
        assertEquals(2, prefs.commitCalls);
        assertEquals(0, reporter.failures.size());
        assertFalse(PreferenceIntegrity.isQuarantined());
        assertFalse(markerFile.exists());
    }

    /** SharedPreferences fake with separate in-memory and on-disk maps. */
    private static final class FakePreferences implements SharedPreferences {
        final Map<String, Object> memory = new HashMap<>();
        final Map<String, Object> disk = new HashMap<>();
        boolean commitResult = true;
        boolean throwOnCommit;
        Runnable commitHook;
        int commitCalls;

        @Override
        public Map<String, ?> getAll() { return new HashMap<>(memory); }

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
        public boolean contains(String key) { return memory.containsKey(key); }

        @Override
        public Editor edit() { return new FakeEditor(); }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        private final class FakeEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removed = new HashSet<>();
            private boolean cleared;

            private Editor remember(String key, Object value) {
                // SharedPreferences editors update memory before/independently
                // of the disk write; a failed commit can leave them divergent.
                removed.remove(key);
                pending.put(key, value);
                memory.put(key, value);
                return this;
            }

            @Override
            public Editor putString(String key, String value) { return remember(key, value); }
            @Override
            public Editor putStringSet(String key, Set<String> value) { return remember(key, new HashSet<>(value)); }
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
            public Editor clear() { cleared = true; pending.clear(); memory.clear(); return this; }

            @Override
            public boolean commit() {
                commitCalls++;
                if (commitHook != null) commitHook.run();
                if (throwOnCommit) throw new IllegalStateException("fake disk failure");
                if (!commitResult) return false; // memory stays changed, disk does not
                if (cleared) disk.clear();
                for (String key : removed) disk.remove(key);
                disk.putAll(pending);
                return true;
            }

            @Override
            public void apply() { commit(); }
        }
    }

    private static final class RecordingReporter implements PreferencePersister.FailureReporter {
        final java.util.List<Failure> failures = new java.util.ArrayList<>();

        @Override
        public void onPreferenceWriteFailed(String key, String detail) {
            failures.add(new Failure(key, detail));
        }
    }

    private static final class Failure {
        final String key;
        final String detail;

        Failure(String key, String detail) {
            this.key = key;
            this.detail = detail;
        }
    }
}
