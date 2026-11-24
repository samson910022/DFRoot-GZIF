package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * JVM-only tests for PreferencePersister using a fake SharedPreferences.
 * No Android runtime, device or native build is involved.
 */
public class PreferencePersisterTest {

    private FakePreferences prefs;
    private RecordingReporter reporter;
    private PreferencePersister persister;

    @Before
    public void setUp() {
        prefs = new FakePreferences();
        reporter = new RecordingReporter();
        persister = new PreferencePersister(prefs, reporter);
    }

    @Test
    public void putStringCommitsAndStoresValue() {
        assertTrue(persister.putString("su_manager", "com.rifsxd.ksunext"));
        assertEquals("com.rifsxd.ksunext", prefs.values.get("su_manager"));
        assertEquals(1, prefs.commitCalls);
        assertEquals(0, reporter.failures.size());
    }

    @Test
    public void putStringFailureIsReturnedAndReported() {
        prefs.commitResult = false;
        assertFalse(persister.putString("su_manager", "com.rifsxd.ksunext"));
        assertEquals(1, reporter.failures.size());
        assertEquals("su_manager", reporter.failures.get(0).key);
    }

    @Test
    public void putStringExceptionIsConvertedToFalse() {
        prefs.throwOnCommit = true;
        assertFalse(persister.putString("su_manager", "com.rifsxd.ksunext"));
        assertEquals(1, reporter.failures.size());
    }

    @Test
    public void removeCommitsAndDeletesValue() {
        prefs.values.put("su_manager", "stale.package");
        assertTrue(persister.remove("su_manager"));
        assertFalse(prefs.values.containsKey("su_manager"));
        assertEquals(0, reporter.failures.size());
    }

    @Test
    public void removeFailureIsReturnedAndReported() {
        prefs.values.put("su_manager", "stale.package");
        prefs.commitResult = false;
        assertFalse(persister.remove("su_manager"));
        assertTrue(prefs.values.containsKey("su_manager"));
        assertEquals(1, reporter.failures.size());
    }

    @Test
    public void putBooleanCommitsTrueAndFalse() {
        assertTrue(persister.putBoolean("soft_reboot", true));
        assertEquals(Boolean.TRUE, prefs.values.get("soft_reboot"));
        assertTrue(persister.putBoolean("soft_reboot", false));
        assertEquals(Boolean.FALSE, prefs.values.get("soft_reboot"));
        assertEquals(0, reporter.failures.size());
    }

    @Test
    public void putBooleanFailureIsReturnedSoSwitchCanRevert() {
        prefs.commitResult = false;
        assertFalse(persister.putBoolean("soft_reboot", true));
        assertFalse(prefs.values.containsKey("soft_reboot"));
        assertEquals(1, reporter.failures.size());
        assertEquals("soft_reboot", reporter.failures.get(0).key);
    }

    @Test
    public void nullReporterDoesNotCrashOnFailure() {
        prefs.commitResult = false;
        PreferencePersister noReporter = new PreferencePersister(prefs);
        assertFalse(noReporter.putBoolean("disable_modules", true));
    }

    /** Minimal in-memory SharedPreferences; commit() can be made to fail. */
    private static final class FakePreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        boolean commitResult = true;
        boolean throwOnCommit;
        int commitCalls;

        @Override
        public Map<String, ?> getAll() { return new HashMap<>(values); }

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = values.get(key);
            return v instanceof Set ? new HashSet<>((Set<String>) v) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = values.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) { return values.containsKey(key); }

        @Override
        public Editor edit() { return new FakeEditor(); }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        private final class FakeEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private boolean cleared;
            private final Set<String> removed = new HashSet<>();

            @Override
            public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override
            public Editor putStringSet(String key, Set<String> value) { pending.put(key, new HashSet<>(value)); return this; }
            @Override
            public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override
            public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override
            public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override
            public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override
            public Editor remove(String key) { removed.add(key); pending.remove(key); return this; }
            @Override
            public Editor clear() { cleared = true; pending.clear(); return this; }

            @Override
            public boolean commit() {
                commitCalls++;
                if (throwOnCommit) throw new IllegalStateException("fake disk failure");
                if (!commitResult) return false;
                if (cleared) values.clear();
                for (String key : removed) values.remove(key);
                values.putAll(pending);
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
