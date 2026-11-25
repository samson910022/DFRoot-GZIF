// Modified 2026-11-25 (DFRoot GZIF): verify checked commits, CAS clear, duplicate rejection, and fail-closed loads. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Pure-JVM contract tests for the durable attempt barrier. */
public class OperationAttemptStoreTest {
    private static final String ID = "attempt-store-run-1";
    private static final String OTHER = "attempt-store-run-2";

    @Test
    public void publishUsesOneCheckedCommitOfTheDedicatedKeys() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());

        assertTrue(store.publish(attempt(ID, 31L)));

        assertEquals(1, prefs.editCalls);
        assertEquals(1, prefs.commitCalls);
        assertEquals(5, prefs.pendingAtCommit);
        assertEquals(ID, prefs.values.get(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID));
        assertEquals(RunConfig.ExecutionMode.MANUAL.name(),
                prefs.values.get(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE));
        assertEquals(31L, prefs.values.get(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT));
        assertEquals(Boolean.TRUE,
                prefs.values.get(SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED));
        OperationAttempt loaded = store.load();
        assertNotNull(loaded);
        assertEquals(ID, loaded.getOperationId());
        assertEquals(RunConfig.ExecutionMode.MANUAL, loaded.getMode());
        assertEquals(31L, loaded.getBootCount());
        assertTrue(loaded.isUnconfirmed());
        assertEquals("admitted", loaded.getReason());
    }

    @Test
    public void falseCommitDoesNotPersistTheAttempt() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        prefs.commitResult = false;
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());

        assertFalse(store.publish(attempt(ID, 32L)));

        assertEquals(1, prefs.commitCalls);
        assertTrue(prefs.values.isEmpty());
    }

    @Test
    public void thrownCommitIsACheckedFailure() {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        prefs.commitFailure = new IllegalStateException("disk full");
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());

        OperationAttemptStore.Failure failure = assertThrows(OperationAttemptStore.Failure.class,
                () -> store.publish(attempt(ID, 33L)));
        assertEquals("disk full", failure.getCause().getMessage());
        assertEquals(1, prefs.commitCalls);
    }

    @Test
    public void differentUnconfirmedAttemptCannotReplaceTheBarrier() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());
        assertTrue(store.publish(attempt(ID, 34L)));

        assertFalse(store.publish(attempt(OTHER, 34L)));
        assertTrue(store.publish(attempt(ID, 35L)));

        assertEquals(2, prefs.commitCalls);
        assertEquals(ID, store.load().getOperationId());
        assertEquals(35L, store.load().getBootCount());
    }

    @Test
    public void clearIsCompareAndSetById() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());
        assertTrue(store.publish(attempt(ID, 36L)));

        assertFalse(store.clear(OTHER));
        assertFalse(store.clear(null));
        assertEquals(ID, store.load().getOperationId());

        assertTrue(store.clear(ID));
        assertNull(store.load());
        assertTrue(prefs.values.isEmpty());
        assertFalse(store.clear(ID));
    }

    @Test
    public void falseClearRetainsTheDurableAttempt() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());
        assertTrue(store.publish(attempt(ID, 37L)));

        prefs.commitResult = false;
        assertFalse(store.clear(ID));
        assertEquals(ID, prefs.values.get(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID));
        // The failed clear made this instance uncertain: its in-memory view no
        // longer proves durable absence, so it must not be consulted again.
        assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertThrows(OperationAttemptStore.Failure.class, () -> store.clear(ID));

        // Only durable values survive a process restart, where a checked clear
        // can succeed through a fresh store instance.
        SharedPreferencesOperationAttemptStore restarted =
                new SharedPreferencesOperationAttemptStore(
                        new FakePreferences(new HashMap<>(prefs.values)).delegate());
        assertTrue(restarted.clear(ID));
        assertNull(restarted.load());
    }

    @Test
    public void thrownClearIsACheckedFailure() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());
        assertTrue(store.publish(attempt(ID, 38L)));

        prefs.commitFailure = new IllegalStateException("clear failed");
        OperationAttemptStore.Failure failure = assertThrows(OperationAttemptStore.Failure.class,
                () -> store.clear(ID));
        assertEquals("clear failed", failure.getCause().getMessage());
        assertEquals(ID, prefs.values.get(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID));
        assertThrows(OperationAttemptStore.Failure.class, store::load);
    }

    @Test
    public void corruptRecordFailsClosed() {
        Map<String, Object> values = new HashMap<>();
        values.put(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID, ID);
        values.put(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE, "UNSUPPORTED");
        values.put(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT, 39L);
        values.put(SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED, true);
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(new FakePreferences(values).delegate());

        OperationAttemptStore.Failure failure =
                assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is corrupt"));
    }

    @Test
    public void inMemoryStoreKeepsTheSameCasContract() throws Exception {
        OperationAttemptStore store = OperationAttemptStore.inMemory();
        assertNull(store.load());

        assertTrue(store.publish(attempt(ID, 40L)));
        assertFalse(store.publish(attempt(OTHER, 40L)));
        assertFalse(store.clear(OTHER));
        assertEquals(ID, store.load().getOperationId());
        assertTrue(store.clear(ID));
        assertNull(store.load());
    }

    @Test
    public void noAttemptKeyLoadsNoRecord() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());

        assertNull(store.load());
    }

    @Test
    public void missingIdWithOtherKeysFailsClosed() {
        Map<String, Object> values = completeRecord();
        values.remove(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID);
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(new FakePreferences(values).delegate());

        OperationAttemptStore.Failure failure =
                assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is incomplete"));
    }

    @Test
    public void missingModeWithOtherKeysFailsClosed() {
        Map<String, Object> values = completeRecord();
        values.remove(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE);
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(new FakePreferences(values).delegate());

        OperationAttemptStore.Failure failure =
                assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is incomplete"));
    }

    @Test
    public void missingBootCountWithOtherKeysFailsClosed() {
        Map<String, Object> values = completeRecord();
        values.remove(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT);
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(new FakePreferences(values).delegate());

        OperationAttemptStore.Failure failure =
                assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is incomplete"));
    }

    @Test
    public void missingUnconfirmedWithOtherKeysFailsClosed() {
        Map<String, Object> values = completeRecord();
        values.remove(SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED);
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(new FakePreferences(values).delegate());

        OperationAttemptStore.Failure failure =
                assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is incomplete"));
    }

    @Test
    public void orphanReasonKeyFailsClosed() {
        Map<String, Object> values = new HashMap<>();
        values.put(SharedPreferencesOperationAttemptStore.KEY_REASON, "orphan");
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(new FakePreferences(values).delegate());

        OperationAttemptStore.Failure failure =
                assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is incomplete"));
    }

    @Test
    public void negativeBootCountFailsClosed() {
        Map<String, Object> values = completeRecord();
        values.put(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT, -1L);
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(new FakePreferences(values).delegate());

        OperationAttemptStore.Failure failure =
                assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is corrupt"));
    }

    @Test
    public void wrongValueTypesFailClosedThroughTheCheckedReadPath() {
        Map<String, Object> wrongId = completeRecord();
        wrongId.put(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID, 123L);
        Map<String, Object> wrongMode = completeRecord();
        wrongMode.put(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE, Boolean.TRUE);
        Map<String, Object> wrongBootCount = completeRecord();
        wrongBootCount.put(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT, "41");
        Map<String, Object> wrongUnconfirmed = completeRecord();
        wrongUnconfirmed.put(SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED, "true");
        Map<String, Object> wrongReason = completeRecord();
        wrongReason.put(SharedPreferencesOperationAttemptStore.KEY_REASON, 7);

        for (Map<String, Object> values : Arrays.asList(wrongId, wrongMode, wrongBootCount,
                wrongUnconfirmed, wrongReason)) {
            SharedPreferencesOperationAttemptStore store =
                    new SharedPreferencesOperationAttemptStore(
                            new FakePreferences(values).delegate());
            OperationAttemptStore.Failure failure =
                    assertThrows(OperationAttemptStore.Failure.class, store::load);
            assertTrue(failure.getMessage(), failure.getMessage().contains("attempt record is"));
        }
    }

    @Test
    public void falseClearMakesTheSameInstanceFailClosedButDurableRecordSurvives() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());
        assertTrue(store.publish(attempt(ID, 43L)));

        prefs.commitResult = false;
        assertFalse(store.clear(ID));
        assertEquals(ID, prefs.values.get(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID));
        OperationAttemptStore.Failure loadFailure =
                assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertTrue(loadFailure.getMessage(), loadFailure.getMessage().contains("uncertain"));
        assertThrows(OperationAttemptStore.Failure.class, () -> store.publish(attempt(OTHER, 43L)));
        assertThrows(OperationAttemptStore.Failure.class, () -> store.clear(ID));
        // The boolean contract for a null ID stays false even on an uncertain store.
        assertFalse(store.clear(null));

        SharedPreferencesOperationAttemptStore fresh =
                new SharedPreferencesOperationAttemptStore(prefs.freshDelegate());
        OperationAttempt loaded = fresh.load();
        assertNotNull(loaded);
        assertEquals(ID, loaded.getOperationId());
        assertTrue(loaded.isUnconfirmed());
        assertTrue(fresh.clear(ID));
        assertNull(fresh.load());
    }

    @Test
    public void falsePublishMakesTheSameInstanceFailClosed() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        prefs.commitResult = false;
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());

        assertFalse(store.publish(attempt(ID, 44L)));
        assertTrue(prefs.values.isEmpty());
        assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertThrows(OperationAttemptStore.Failure.class, () -> store.publish(attempt(ID, 44L)));
        assertThrows(OperationAttemptStore.Failure.class, () -> store.clear(ID));
    }

    @Test
    public void thrownCommitMakesTheSameInstanceFailClosed() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        prefs.commitFailure = new IllegalStateException("disk full");
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());

        assertThrows(OperationAttemptStore.Failure.class, () -> store.publish(attempt(ID, 45L)));
        assertTrue(prefs.values.isEmpty());
        assertThrows(OperationAttemptStore.Failure.class, store::load);
        assertThrows(OperationAttemptStore.Failure.class, () -> store.clear(ID));
    }

    @Test
    public void successfulClearRemainsIdempotent() throws Exception {
        FakePreferences prefs = new FakePreferences(new HashMap<>());
        SharedPreferencesOperationAttemptStore store =
                new SharedPreferencesOperationAttemptStore(prefs.delegate());

        assertTrue(store.publish(attempt(ID, 46L)));
        assertTrue(store.clear(ID));
        assertNull(store.load());
        assertFalse(store.clear(ID));
        assertEquals(2, prefs.commitCalls);
    }

    private static Map<String, Object> completeRecord() {
        Map<String, Object> values = new HashMap<>();
        values.put(SharedPreferencesOperationAttemptStore.KEY_OPERATION_ID, ID);
        values.put(SharedPreferencesOperationAttemptStore.KEY_EXECUTION_MODE,
                RunConfig.ExecutionMode.MANUAL.name());
        values.put(SharedPreferencesOperationAttemptStore.KEY_BOOT_COUNT, 42L);
        values.put(SharedPreferencesOperationAttemptStore.KEY_UNCONFIRMED, true);
        values.put(SharedPreferencesOperationAttemptStore.KEY_REASON, "admitted");
        return values;
    }

    private static OperationAttempt attempt(String id, long bootCount) {
        return new OperationAttempt(id, RunConfig.ExecutionMode.MANUAL, bootCount, true, "admitted");
    }

    /** Models Android's in-memory edit before disk commit, with durable values separate. */
    private static final class FakePreferences implements InvocationHandler {
        private final Map<String, Object> values;
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
                    (ignored, method, args) -> invokeEditor(method, args));
        }

        android.content.SharedPreferences delegate() {
            return (android.content.SharedPreferences) preferences;
        }

        /** A fresh process-local view over the SAME durable map (a new store instance). */
        android.content.SharedPreferences freshDelegate() {
            return new FakePreferences(values).delegate();
        }

        private Object invokeEditor(Method method, Object[] args) {
            switch (method.getName()) {
                case "putString":
                case "putLong":
                case "putBoolean":
                    pending.put((String) args[0], args[1]);
                    cached.put((String) args[0], args[1]);
                    return editor;
                case "remove":
                    pending.put((String) args[0], REMOVED);
                    cached.remove((String) args[0]);
                    return editor;
                case "commit":
                    commitCalls++;
                    pendingAtCommit = pending.size();
                    if (commitFailure != null) {
                        throw commitFailure;
                    }
                    if (commitResult) {
                        for (Map.Entry<String, Object> entry : pending.entrySet()) {
                            if (entry.getValue() == REMOVED) {
                                values.remove(entry.getKey());
                            } else {
                                values.put(entry.getKey(), entry.getValue());
                            }
                        }
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
                case "getString":
                    return cached.containsKey(args[0]) ? cached.get(args[0]) : args[1];
                case "getLong":
                    return cached.containsKey(args[0]) ? cached.get(args[0]) : args[1];
                case "getBoolean":
                    return cached.containsKey(args[0]) ? cached.get(args[0]) : args[1];
                case "contains":
                    return cached.containsKey(args[0]);
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }

        private static final Object REMOVED = new Object();
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
