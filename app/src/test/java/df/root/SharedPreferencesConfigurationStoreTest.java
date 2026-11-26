// Modified 2026-11-24 (DFRoot GZIF): verify full-snapshot checked commits and Android memory-before-disk failure semantics. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SharedPreferencesConfigurationStoreTest {
    private Path marker;

    @Before
    public void initWriteAheadBoundary() throws Exception {
        marker = Files.createTempFile("dfroot-config-integrity", ".marker");
        Files.deleteIfExists(marker);
        PreferenceIntegrity.resetForTest();
        PreferenceIntegrity.init(marker.toFile());
    }

    @After
    public void resetWriteAheadBoundary() throws Exception {
        PreferenceIntegrity.resetForTest();
        Files.deleteIfExists(marker);
    }

    @Test
    public void loadReadsTheCompleteSnapshotAndSafeDefaults() {
        Map<String, Object> values = new HashMap<>();
        values.put(ConfigurationKeys.PREF_SU_MANAGER, SuManager.NEXT.getPackageName());
        values.put(ConfigurationKeys.PREF_SOFT_REBOOT, true);
        values.put(ConfigurationKeys.PREF_DISABLE_MODULES, true);
        values.put(ConfigurationKeys.PREF_SOFT_REBOOT_ACK_VERSION,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION);
        values.put(ConfigurationKeys.PREF_GENERATION, 19L);
        FakePreferences preferences = new FakePreferences(values);

        RunConfig loaded = new SharedPreferencesConfigurationStore(preferences.delegate()).load();

        assertEquals(SuManager.NEXT.getPackageName(), loaded.getManagerPackage());
        assertTrue(loaded.isSoftReboot());
        assertTrue(loaded.isDisableModules());
        assertEquals(ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION,
                loaded.getSoftRebootAcknowledgementVersion());
        assertEquals(19, loaded.getGeneration());

        RunConfig defaults = new SharedPreferencesConfigurationStore(
                new FakePreferences(new HashMap<>()).delegate()).load();
        assertNull(defaults.getManagerPackage());
        assertFalse(defaults.isSoftReboot());
        assertFalse(defaults.isDisableModules());
        assertEquals(0, defaults.getSoftRebootAcknowledgementVersion());
        assertEquals(0, defaults.getGeneration());
    }

    @Test
    public void writePublishesEveryFieldWithOneEditorCommit() {
        FakePreferences preferences = new FakePreferences(new HashMap<>());
        RunConfig config = new RunConfig(SuManager.NEXT.getPackageName(), true, true,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 23);

        assertTrue(new SharedPreferencesConfigurationStore(preferences.delegate()).write(config));

        assertEquals(1, preferences.editCalls);
        assertEquals(1, preferences.commitCalls);
        assertEquals(5, preferences.pendingAtCommit);
        assertEquals(SuManager.NEXT.getPackageName(),
                preferences.values.get(ConfigurationKeys.PREF_SU_MANAGER));
        assertEquals(true, preferences.values.get(ConfigurationKeys.PREF_SOFT_REBOOT));
        assertEquals(true, preferences.values.get(ConfigurationKeys.PREF_DISABLE_MODULES));
        assertEquals(ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION,
                preferences.values.get(ConfigurationKeys.PREF_SOFT_REBOOT_ACK_VERSION));
        assertEquals(23L, preferences.values.get(ConfigurationKeys.PREF_GENERATION));
    }

    @Test
    public void nullManagerIsRemovedInTheSameAtomicCommit() {
        FakePreferences preferences = new FakePreferences(new HashMap<>());
        preferences.seed(ConfigurationKeys.PREF_SU_MANAGER, SuManager.CLASSIC.getPackageName());

        assertTrue(new SharedPreferencesConfigurationStore(preferences.delegate()).write(
                RunConfig.defaults()));

        assertEquals(1, preferences.commitCalls);
        assertFalse(preferences.values.containsKey(ConfigurationKeys.PREF_SU_MANAGER));
    }

    @Test
    public void commitFalseIsReturnedRatherThanIgnoredAndFailCloses() {
        FakePreferences preferences = new FakePreferences(new HashMap<>());
        preferences.commitResult = false;

        assertFalse(new SharedPreferencesConfigurationStore(preferences.delegate()).write(
                RunConfig.defaults()));
        assertEquals(1, preferences.commitCalls);
        assertTrue(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void commitExceptionPropagatesToTheRepository() {
        FakePreferences preferences = new FakePreferences(new HashMap<>());
        preferences.commitFailure = new IllegalStateException("commit failed");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new SharedPreferencesConfigurationStore(preferences.delegate()).write(
                        RunConfig.defaults()));
        assertSame(preferences.commitFailure, error);
    }

    @Test
    public void falseCommitFailClosesAndNeverPromotesTheCandidateOnReload() {
        FakePreferences preferences = new FakePreferences(new HashMap<>());
        preferences.commitResult = false;
        SharedPreferencesConfigurationStore store = new SharedPreferencesConfigurationStore(
                preferences.delegate());
        RunConfig candidate = new RunConfig(SuManager.NEXT.getPackageName(), true, true,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 23);

        assertFalse(store.write(candidate));
        assertTrue(PreferenceIntegrity.isQuarantined());

        // The in-memory map holds the candidate, but a quarantined store must
        // never promote it as durable configuration.
        RunConfig reloaded = store.load();
        assertNull(reloaded.getManagerPackage());
        assertFalse(reloaded.isSoftReboot());
        assertEquals(0, reloaded.getGeneration());
        assertFalse(preferences.values.containsKey(ConfigurationKeys.PREF_SU_MANAGER));
    }

    @Test
    public void commitExceptionFailClosesAndNeverPromotesTheCandidateOnReload() {
        FakePreferences preferences = new FakePreferences(new HashMap<>());
        preferences.commitFailure = new IllegalStateException("commit failed");
        SharedPreferencesConfigurationStore store = new SharedPreferencesConfigurationStore(
                preferences.delegate());
        RunConfig candidate = new RunConfig(SuManager.NEXT.getPackageName(), false, true, 0, 24);

        assertThrows(IllegalStateException.class, () -> store.write(candidate));
        assertTrue(PreferenceIntegrity.isQuarantined());

        RunConfig reloaded = store.load();
        assertNull(reloaded.getManagerPackage());
        assertFalse(reloaded.isDisableModules());
        assertEquals(0, reloaded.getGeneration());
        assertFalse(preferences.values.containsKey(ConfigurationKeys.PREF_SU_MANAGER));
    }

    @Test
    public void failedCommitDisablesBootLaunchAtTheFailureBoundary() {
        FakePreferences preferences = new FakePreferences(new HashMap<>());
        preferences.commitResult = false;
        boolean[] bootDisabled = {false};
        PreferenceIntegrity.setBootGateForTest(new PreferenceIntegrity.BootGate() {
            @Override
            public void disableBootLaunch() {
                bootDisabled[0] = true;
            }
        });

        assertFalse(new SharedPreferencesConfigurationStore(preferences.delegate())
                .write(RunConfig.defaults()));

        assertTrue(bootDisabled[0]);
        assertTrue(PreferenceIntegrity.isQuarantined());
    }

    @Test
    public void aSuccessfulWriteOfTheSameSnapshotRestoresConsistency() {
        FakePreferences preferences = new FakePreferences(new HashMap<>());
        SharedPreferencesConfigurationStore store = new SharedPreferencesConfigurationStore(
                preferences.delegate());
        RunConfig candidate = new RunConfig(SuManager.NEXT.getPackageName(), true, true,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 25);
        preferences.commitResult = false;

        assertFalse(store.write(candidate));
        assertTrue(PreferenceIntegrity.isQuarantined());

        preferences.commitResult = true;
        assertTrue(store.write(candidate));
        assertFalse(PreferenceIntegrity.isQuarantined());
        assertEquals(candidate.getGeneration(), store.load().getGeneration());
    }

    private static final class FakePreferences implements InvocationHandler {
        private final Map<String, Object> values;
        private final Map<String, Object> cached;
        private final Object preferences;
        private final Object editor;
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removed = new HashSet<>();
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

        void seed(String key, Object value) {
            values.put(key, value);
            cached.put(key, value);
        }

        android.content.SharedPreferences delegate() {
            return (android.content.SharedPreferences) preferences;
        }

        private Object invokeEditor(Method method, Object[] args) {
            switch (method.getName()) {
                case "putString":
                    pending.put((String) args[0], args[1]);
                    cached.put((String) args[0], args[1]);
                    return editor;
                case "putBoolean":
                    pending.put((String) args[0], args[1]);
                    cached.put((String) args[0], args[1]);
                    return editor;
                case "putInt":
                    pending.put((String) args[0], args[1]);
                    cached.put((String) args[0], args[1]);
                    return editor;
                case "putLong":
                    pending.put((String) args[0], args[1]);
                    cached.put((String) args[0], args[1]);
                    return editor;
                case "remove":
                    pending.remove((String) args[0]);
                    removed.add((String) args[0]);
                    cached.remove((String) args[0]);
                    return editor;
                case "commit":
                    commitCalls++;
                    pendingAtCommit = pending.size() + removed.size();
                    if (commitFailure != null) {
                        throw commitFailure;
                    }
                    if (commitResult) {
                        values.putAll(pending);
                        values.keySet().removeAll(removed);
                    }
                    pending.clear();
                    removed.clear();
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
                    removed.clear();
                    return editor;
                case "getString":
                    return cached.get((String) args[0]);
                case "getBoolean":
                    return cached.getOrDefault((String) args[0], (Boolean) args[1]);
                case "getInt":
                    return cached.getOrDefault((String) args[0], (Integer) args[1]);
                case "getLong":
                    return cached.getOrDefault((String) args[0], (Long) args[1]);
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
