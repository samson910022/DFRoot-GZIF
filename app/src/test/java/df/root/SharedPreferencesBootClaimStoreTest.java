// Modified 2026-11-25 (DFRoot GZIF): verify checked boot-claim commits in an isolated DE store. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): model Android in-memory-before-disk commit semantics exactly. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Checked-commit and compare-and-set tests for the isolated boot-claim store. */
public class SharedPreferencesBootClaimStoreTest {
    @Test
    public void claimUsesOneCheckedCommitOfTheDedicatedKey() {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesBootClaimStore store = new SharedPreferencesBootClaimStore(prefs.delegate());

        assertTrue(store.claim(31L));

        assertEquals(1, prefs.editCalls);
        assertEquals(1, prefs.commitCalls);
        assertEquals(1, prefs.pendingAtCommit);
        assertEquals(31L, prefs.values.get(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
        assertEquals(31L, store.lastClaimed());
    }

    @Test
    public void duplicateAndOlderCountsAreNotClaimed() {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesBootClaimStore store = new SharedPreferencesBootClaimStore(prefs.delegate());

        assertTrue(store.claim(32L));
        assertFalse(store.claim(32L));
        assertFalse(store.claim(31L));

        assertEquals(1, prefs.commitCalls);
        assertEquals(32L, store.lastClaimed());
    }

    @Test
    public void nonPositiveCountsAreRejectedBeforeAnyStoreAccess() {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesBootClaimStore store = new SharedPreferencesBootClaimStore(prefs.delegate());

        assertFalse(store.claim(0L));
        assertFalse(store.claim(-1L));

        assertEquals(0, prefs.editCalls);
        assertEquals(0, prefs.commitCalls);
    }

    @Test
    public void falseCommitNeverClaimsTheBoot() {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        prefs.commitResult = false;
        SharedPreferencesBootClaimStore store = new SharedPreferencesBootClaimStore(prefs.delegate());

        assertFalse(store.claim(33L));

        assertEquals(1, prefs.commitCalls);
        assertFalse(prefs.values.containsKey(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
    }

    @Test
    public void failedCommitLeavesMemoryCandidateButNoDurableClaimUntilRestart() {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        prefs.commitResult = false;
        SharedPreferencesBootClaimStore store = new SharedPreferencesBootClaimStore(prefs.delegate());

        assertFalse(store.claim(37L));
        // Android applies the edit to the in-memory map before the disk write, so a
        // failed commit is still visible to getLong() in this process.
        assertEquals(37L, store.lastClaimed());
        // Nothing was durably persisted: the key is absent from the on-disk values.
        assertFalse(prefs.values.containsKey(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
        assertEquals(1, prefs.commitCalls);

        // The stale in-memory candidate suppresses a same-process retry of this count.
        assertFalse(store.claim(37L));
        assertEquals(1, prefs.commitCalls);

        // After process death only durable values survive, so the same count can be
        // claimed by a later successful commit.
        FakePreferences restartedPrefs = new FakePreferences(new HashMap<>(prefs.values));
        SharedPreferencesBootClaimStore restarted =
                new SharedPreferencesBootClaimStore(restartedPrefs.delegate());
        assertTrue(restarted.claim(37L));
        assertEquals(37L, restartedPrefs.values.get(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
    }

    @Test
    public void thrownCommitPropagatesToTheCoordinator() {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        prefs.commitFailure = new IllegalStateException("disk full");
        SharedPreferencesBootClaimStore store = new SharedPreferencesBootClaimStore(prefs.delegate());

        assertThrows(IllegalStateException.class, () -> store.claim(34L));
        assertEquals(1, prefs.commitCalls);
    }

    @Test
    public void corruptedStoredValueFailsClosed() {
        Map<String, Object> values = new HashMap<>();
        values.put(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT, "not-a-long");
        SharedPreferencesBootClaimStore store =
                new SharedPreferencesBootClaimStore(new FakePreferences(values).delegate());

        assertEquals(-1L, store.lastClaimed());
        assertFalse(store.claim(35L));
    }

    @Test
    public void concurrentStoreInstancesStillCompareAndSetAtomically() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesBootClaimStore a = new SharedPreferencesBootClaimStore(prefs.delegate());
        SharedPreferencesBootClaimStore b = new SharedPreferencesBootClaimStore(prefs.delegate());
        int[] wins = new int[1];
        Thread first = new Thread(() -> { if (a.claim(36L)) { wins[0]++; } });
        Thread second = new Thread(() -> { if (b.claim(36L)) { wins[0]++; } });
        first.start();
        second.start();
        first.join();
        second.join();

        assertEquals(1, wins[0]);
        assertEquals(36L, a.lastClaimed());
    }

    private static final class FakePreferences implements InvocationHandler {
        /** Durable, on-disk values: updated only by a successful commit. */
        private final Map<String, Object> values;
        /** In-memory map: putLong() updates it before commit, even if commit fails. */
        private final Map<String, Object> cached;
        private final Object preferences;
        private final Object editor;
        private final Map<String, Object> pending = new HashMap<>();
        private int editCalls;
        private int commitCalls;
        private int pendingAtCommit;
        private boolean commitResult = true;
        private RuntimeException commitFailure;

        FakePreferences(Map<String, Object> values) {
            this.values = values;
            this.cached = new HashMap<>(values);
            this.preferences = proxy(android.content.SharedPreferences.class, this);
            this.editor = proxy(android.content.SharedPreferences.Editor.class,
                    (proxy, method, args) -> invokeEditor(method, args));
        }

        android.content.SharedPreferences delegate() {
            return (android.content.SharedPreferences) preferences;
        }

        private Object invokeEditor(Method method, Object[] args) {
            switch (method.getName()) {
                case "putLong":
                    pending.put((String) args[0], args[1]);
                    cached.put((String) args[0], args[1]);
                    return editor;
                case "commit":
                    commitCalls++;
                    pendingAtCommit = pending.size();
                    if (commitFailure != null) {
                        throw commitFailure;
                    }
                    if (commitResult) {
                        values.putAll(pending);
                    }
                    pending.clear();
                    return commitResult;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "edit":
                    editCalls++;
                    pending.clear();
                    return editor;
                case "getLong":
                    Object value = cached.get((String) args[0]);
                    if (value == null) {
                        return args[1];
                    }
                    if (!(value instanceof Long)) {
                        throw new ClassCastException("stored value is not a long");
                    }
                    return value;
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
