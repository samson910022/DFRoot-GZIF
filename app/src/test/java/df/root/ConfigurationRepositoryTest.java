// Modified 2026-11-24 (DFRoot GZIF): specify checked, serialized configuration persistence and admission. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): verify atomic state/diagnostic publication through ordinary write and reload failures. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): pin ERROR diagnostic whitespace semantics, including nonbreaking space. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ConfigurationRepositoryTest {
    private static final RunConfig INITIAL = new RunConfig(
            SuManager.CLASSIC.getPackageName(), false, false, 0, 4);

    @Test
    public void loadFailureLatchesErrorAndBlocksAdmission() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new ConfigurationStore() {
                    @Override
                    public RunConfig load() {
                        throw new IllegalStateException("preferences unreadable");
                    }

                    @Override
                    public boolean write(RunConfig config) {
                        return true;
                    }
                }, Runnable::run, Runnable::run);

        repository.start();

        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertNull(repository.getConfirmedSnapshot());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertTrue(repository.getErrorMessage().contains("preferences unreadable"));
    }

    @Test
    public void configurationStatusRejectsBlankDiagnosticsUsingStringBlankSemantics() {
        assertThrows(NullPointerException.class,
                () -> new ConfigurationRepository.ConfigurationStatus(null, ""));
        assertThrows(NullPointerException.class,
                () -> new ConfigurationRepository.ConfigurationStatus(
                        ConfigurationRepository.State.VERIFIED, null));
        assertThrows(NullPointerException.class,
                () -> new ConfigurationRepository.ConfigurationStatus(
                        ConfigurationRepository.State.ERROR, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigurationRepository.ConfigurationStatus(
                        ConfigurationRepository.State.ERROR, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigurationRepository.ConfigurationStatus(
                        ConfigurationRepository.State.ERROR, " \t\n"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigurationRepository.ConfigurationStatus(
                        ConfigurationRepository.State.ERROR, "\u2003"));

        // String.isBlank() uses Character.isWhitespace(), which excludes NBSP.
        assertEquals("\u00A0", new ConfigurationRepository.ConfigurationStatus(
                ConfigurationRepository.State.ERROR, "\u00A0").errorMessage);
        assertEquals("", new ConfigurationRepository.ConfigurationStatus(
                ConfigurationRepository.State.SAVING, "").errorMessage);
        assertEquals("", new ConfigurationRepository.ConfigurationStatus(
                ConfigurationRepository.State.VERIFIED, "").errorMessage);
        assertEquals("write failed", new ConfigurationRepository.ConfigurationStatus(
                ConfigurationRepository.State.ERROR, "write failed").errorMessage);
    }

    @Test
    public void failedCommitPublishesErrorAndRollbackDiagnosticAsOneStatus() {
        FakeStore store = new FakeStore(INITIAL);
        store.results.add(false);
        store.results.add(true);
        String expectedError = "configuration write failed: commit returned false; rollback succeeded";
        List<ConfigurationRepository.ConfigurationStatus> published = new ArrayList<>();
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.addListener(() -> {
            ConfigurationRepository.ConfigurationStatus status = repository.statusForTesting();
            published.add(status);
            if (status.state == ConfigurationRepository.State.ERROR) {
                assertEquals(expectedError, status.errorMessage);
                assertFalse(status.errorMessage.isBlank());
                assertEquals(status.state, repository.getState());
                assertEquals(status.errorMessage, repository.getErrorMessage());
                assertSame(status, repository.statusForTesting());
            }
        });
        repository.start();

        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));

        ConfigurationRepository.ConfigurationStatus finalStatus = repository.statusForTesting();
        assertSame(finalStatus, published.get(published.size() - 1));
        assertEquals(ConfigurationRepository.State.ERROR, finalStatus.state);
        assertEquals(expectedError, finalStatus.errorMessage);
        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertEquals(2, store.writes.size());
        assertSame(INITIAL, store.writes.get(1));
    }

    @Test
    public void commitExceptionPublishesErrorAndRollbackDiagnosticAsOneStatus() {
        FakeStore store = new FakeStore(INITIAL);
        store.failures.add(new IllegalStateException("disk unavailable"));
        store.results.add(true);
        String expectedError = "configuration write failed: disk unavailable; rollback succeeded";
        List<ConfigurationRepository.ConfigurationStatus> published = new ArrayList<>();
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.addListener(() -> {
            ConfigurationRepository.ConfigurationStatus status = repository.statusForTesting();
            published.add(status);
            if (status.state == ConfigurationRepository.State.ERROR) {
                assertEquals(expectedError, status.errorMessage);
                assertFalse(status.errorMessage.isBlank());
                assertEquals(status.state, repository.getState());
                assertEquals(status.errorMessage, repository.getErrorMessage());
                assertSame(status, repository.statusForTesting());
            }
        });
        repository.start();

        assertTrue(repository.setDisableModules(true));

        ConfigurationRepository.ConfigurationStatus finalStatus = repository.statusForTesting();
        assertSame(finalStatus, published.get(published.size() - 1));
        assertEquals(ConfigurationRepository.State.ERROR, finalStatus.state);
        assertEquals(expectedError, finalStatus.errorMessage);
        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertEquals(2, store.writes.size());
        assertSame(INITIAL, store.writes.get(1));
    }

    @Test
    public void failedRollbackPublishesErrorDiagnosticAsOneStatus() {
        FakeStore store = new FakeStore(INITIAL);
        store.results.add(false);
        store.results.add(false);
        String expectedError = "configuration write failed: commit returned false; rollback failed";
        List<ConfigurationRepository.ConfigurationStatus> published = new ArrayList<>();
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.addListener(() -> {
            ConfigurationRepository.ConfigurationStatus status = repository.statusForTesting();
            published.add(status);
            if (status.state == ConfigurationRepository.State.ERROR) {
                assertEquals(expectedError, status.errorMessage);
                assertFalse(status.errorMessage.isBlank());
                assertEquals(status.state, repository.getState());
                assertEquals(status.errorMessage, repository.getErrorMessage());
                assertSame(status, repository.statusForTesting());
            }
        });
        repository.start();

        assertTrue(repository.setSoftReboot(false));

        ConfigurationRepository.ConfigurationStatus finalStatus = repository.statusForTesting();
        assertSame(finalStatus, published.get(published.size() - 1));
        assertEquals(ConfigurationRepository.State.ERROR, finalStatus.state);
        assertEquals(expectedError, finalStatus.errorMessage);
        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertEquals(2, store.writes.size());
        assertSame(INITIAL, store.writes.get(1));
    }

    @Test
    public void generationExhaustionRejectsFurtherWrites() {
        RunConfig exhausted = new RunConfig(null, false, false, 0, Long.MAX_VALUE);
        ConfigurationRepository repository = repository(
                new FakeStore(exhausted), new DirectExecutor());
        repository.start();

        assertFalse(repository.setDisableModules(true));
        assertFalse(repository.isWritePending());
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
    }

    @Test
    public void rollbackExceptionIsReportedWithoutPublishingTheCandidate() {
        FakeStore store = new FakeStore(INITIAL);
        store.failures.add(new IllegalStateException("write failed"));
        store.failures.add(new IllegalStateException("rollback failed"));
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();

        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));

        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertTrue(repository.getErrorMessage().contains("rollback threw"));
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
    }

    @Test
    public void staleAdmissionCloseCannotReleaseANewerOperation() {
        ConfigurationRepository repository = repository(
                new FakeStore(INITIAL), new DirectExecutor());
        repository.start();
        ConfigurationRepository.RunAdmission first = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        first.close();
        ConfigurationRepository.RunAdmission second = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);

        first.close();
        assertThrows(IllegalStateException.class, first::getSnapshot);
        assertThrows(IllegalStateException.class, first::getExecutionMode);

        assertTrue(repository.isFrozen());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        second.close();
        assertFalse(repository.isFrozen());
    }

    @Test
    public void freshInstallLoadsSafeDefaultsWithoutWriting() {
        FakeStore store = new FakeStore(RunConfig.defaults());
        ConfigurationRepository repository = repository(store, new DirectExecutor());

        repository.start();

        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertSame(store.cached, repository.getConfirmedSnapshot());
        assertEquals(0, store.writes.size());
        assertFalse(repository.isWritePending());
        assertFalse(repository.isFrozen());
    }

    @Test
    public void successfulCommitPublishesTheNextGeneration() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();

        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));

        RunConfig confirmed = repository.getConfirmedSnapshot();
        assertEquals(5, confirmed.getGeneration());
        assertEquals(SuManager.NEXT.getPackageName(), confirmed.getManagerPackage());
        assertSame(confirmed, store.writes.get(0));
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertFalse(repository.isWritePending());
        assertEquals("", repository.getErrorMessage());
    }

    @Test
    public void ignoredCommitKeepsPreviousSnapshotAndBlocksAdmissionEvenAfterRollback() {
        FakeStore store = new FakeStore(INITIAL);
        store.results.add(false);
        store.results.add(true);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();

        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));

        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertFalse(repository.isWritePending());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertEquals(2, store.writes.size());
        assertSame(INITIAL, store.writes.get(1));
        assertTrue(repository.getErrorMessage().contains("commit returned false"));
        assertTrue(repository.getErrorMessage().contains("rollback succeeded"));
    }

    @Test
    public void commitExceptionKeepsPreviousSnapshotAndAttemptsRollback() {
        FakeStore store = new FakeStore(INITIAL);
        store.failures.add(new IllegalStateException("disk unavailable"));
        store.results.add(true);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();

        assertTrue(repository.setDisableModules(true));

        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertTrue(repository.getErrorMessage().contains("disk unavailable"));
        assertTrue(repository.getErrorMessage().contains("rollback succeeded"));
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
    }

    @Test
    public void failedRollbackIsReportedAndNeverBecomesConfirmed() {
        FakeStore store = new FakeStore(INITIAL);
        store.results.add(false);
        store.results.add(false);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();

        assertTrue(repository.setSoftReboot(false));

        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertTrue(repository.getErrorMessage().contains("rollback failed"));
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
    }

    @Test
    public void everyCriticalPreferenceFailureFailsClosed() {
        assertCriticalWriteFails(repository -> repository.setManagerPackage(
                SuManager.NEXT.getPackageName()));
        assertCriticalWriteFails(ConfigurationRepository::clearManager);
        assertCriticalWriteFails(repository -> repository.setSoftReboot(true));
        assertCriticalWriteFails(repository -> repository.setSoftReboot(false));
        assertCriticalWriteFails(repository -> repository.setDisableModules(true));
        assertCriticalWriteFails(repository -> repository.setDisableModules(false));
    }

    @Test
    public void pendingWriteRejectsAnotherUpdateInsteadOfReorderingIt() {
        FakeStore store = new FakeStore(INITIAL);
        QueueExecutor executor = new QueueExecutor();
        ConfigurationRepository repository = repository(store, executor);
        repository.start();
        executor.runNext();

        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));
        assertTrue(repository.isWritePending());
        assertFalse(repository.setDisableModules(true));
        assertEquals(0, store.writes.size());

        executor.runNext();

        assertEquals(1, store.writes.size());
        assertEquals(SuManager.NEXT.getPackageName(),
                repository.getConfirmedSnapshot().getManagerPackage());
        assertFalse(repository.getConfirmedSnapshot().isDisableModules());
        assertTrue(repository.setDisableModules(true));
        executor.runNext();
        assertTrue(repository.getConfirmedSnapshot().isDisableModules());
        assertEquals(6, repository.getConfirmedSnapshot().getGeneration());
    }

    @Test
    public void staleGenerationUpdateIsRejected() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));

        RunConfig stale = INITIAL.withDisableModules(true);
        assertFalse(repository.update(stale, INITIAL.getGeneration()));
        assertEquals(1, store.writes.size());
        assertEquals(5, repository.getConfirmedSnapshot().getGeneration());
    }

    @Test
    public void softRebootCannotBePersistedWithoutCurrentAcknowledgement() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        RunConfig unacknowledged = new RunConfig(INITIAL.getManagerPackage(),
                true, false, 0, INITIAL.getGeneration());

        assertFalse(repository.update(unacknowledged, INITIAL.getGeneration()));
        assertEquals(0, store.writes.size());
        assertFalse(repository.isWritePending());
    }

    @Test
    public void enablingSoftRebootWritesValueAndAcknowledgementInOneSnapshot() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();

        assertTrue(repository.setSoftReboot(true));

        RunConfig durable = store.writes.get(0);
        assertTrue(durable.isSoftReboot());
        assertEquals(ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION,
                durable.getSoftRebootAcknowledgementVersion());
        assertSame(durable, repository.getConfirmedSnapshot());
    }

    @Test
    public void acquiredRunFreezesUpdatesUntilItsSnapshotIsReleased() {
        ConfigurationRepository repository = repository(
                new FakeStore(INITIAL), new DirectExecutor());
        repository.start();

        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL);

        assertSame(INITIAL, admission.getSnapshot());
        assertTrue(repository.isFrozen());
        assertFalse(repository.setManagerPackage(SuManager.NEXT.getPackageName()));
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        admission.close();
        assertFalse(repository.isFrozen());
        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));
    }

    @Test
    public void expectedGenerationAdmissionRejectsNewerConfigurationWithoutFreezing() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        assertTrue(repository.setDisableModules(true));
        RunConfig confirmed = repository.getConfirmedSnapshot();

        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL,
                INITIAL.getGeneration()));
        assertFalse(repository.isFrozen());
        assertTrue(repository.getRunAdmissionBlockReason(RunConfig.ExecutionMode.MANUAL,
                INITIAL.getGeneration()).contains("generation changed"));
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL, confirmed.getGeneration());
        assertSame(confirmed, admission.getSnapshot());
        assertTrue(repository.isFrozen());
        admission.close();

        ConfigurationRepository.RunAdmission legacy = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        assertSame(confirmed, legacy.getSnapshot());
        legacy.close();
        assertFalse(repository.isFrozen());
    }

    @Test
    public void savingAndErrorStatesNeverAdmitRuns() {
        FakeStore savingStore = new FakeStore(INITIAL);
        QueueExecutor savingExecutor = new QueueExecutor();
        ConfigurationRepository saving = repository(savingStore, savingExecutor);
        saving.start();
        assertNull(saving.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        savingExecutor.runNext();

        FakeStore errorStore = new FakeStore(INITIAL);
        errorStore.results.add(false);
        ConfigurationRepository error = repository(errorStore, new DirectExecutor());
        error.start();
        assertTrue(error.setDisableModules(true));
        assertNull(error.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
    }

    @Test
    public void listenerDeliveryFailureNeverChangesDurableWriteOutcome() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = new ConfigurationRepository(
                store, Runnable::run, command -> {
                    throw new IllegalStateException("main looper unavailable");
                });

        repository.start();
        assertTrue(repository.setDisableModules(true));

        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertTrue(repository.getConfirmedSnapshot().isDisableModules());
        assertFalse(repository.isWritePending());
        assertEquals(1, store.writes.size());
    }

    @Test
    public void listenerNotificationsArePostedToTheMainExecutor() {
        FakeStore store = new FakeStore(INITIAL);
        QueueExecutor main = new QueueExecutor();
        ConfigurationRepository repository = new ConfigurationRepository(
                store, new DirectExecutor(), main::execute);
        List<ConfigurationRepository.State> observed = new ArrayList<>();
        repository.addListener(() -> observed.add(repository.getState()));
        repository.start();
        assertTrue(observed.isEmpty());

        main.runNext();
        assertEquals(ConfigurationRepository.State.VERIFIED, observed.get(0));
        repository.setDisableModules(true);
        assertEquals(1, observed.size());
        main.runNext();
        main.runNext();
        // Delivered callbacks reconcile the latest state; they never expose a
        // transient SAVING state after the durable write has already completed.
        assertEquals(3, observed.size());
        assertEquals(ConfigurationRepository.State.VERIFIED, observed.get(1));
        assertEquals(ConfigurationRepository.State.VERIFIED, observed.get(2));
    }

    @Test
    public void legacyEnabledSoftRebootIsMigratedOffWithCheckedWrite() {
        RunConfig legacy = new RunConfig(SuManager.NEXT.getPackageName(), true, true, 0, 11);
        FakeStore store = new FakeStore(legacy);
        ConfigurationRepository repository = repository(store, new DirectExecutor());

        repository.start();

        RunConfig confirmed = repository.getConfirmedSnapshot();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertFalse(confirmed.isSoftReboot());
        assertEquals(0, confirmed.getSoftRebootAcknowledgementVersion());
        assertTrue(confirmed.isDisableModules());
        assertEquals(12, confirmed.getGeneration());
        assertEquals(1, store.writes.size());
        assertSame(confirmed, store.writes.get(0));
    }

    @Test
    public void failedSafeOffMigrationLeavesErrorAndBlocksEveryRun() {
        RunConfig legacy = new RunConfig(SuManager.NEXT.getPackageName(), true, false, 0, 2);
        FakeStore store = new FakeStore(legacy);
        store.results.add(false);
        ConfigurationRepository repository = repository(store, new DirectExecutor());

        repository.start();

        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertNull(repository.getConfirmedSnapshot());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertTrue(repository.getErrorMessage().contains("safe-off migration"));
    }

    @Test
    public void currentAcknowledgementAndSafeFalseValuesNeedNoMigration() {
        RunConfig acknowledged = new RunConfig(null, true, false,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 8);
        FakeStore acknowledgedStore = new FakeStore(acknowledged);
        ConfigurationRepository acknowledgedRepository = repository(
                acknowledgedStore, new DirectExecutor());
        acknowledgedRepository.start();
        assertSame(acknowledged, acknowledgedRepository.getConfirmedSnapshot());
        assertEquals(0, acknowledgedStore.writes.size());

        RunConfig safeFalse = new RunConfig(null, false, true, 0, 9);
        FakeStore safeStore = new FakeStore(safeFalse);
        ConfigurationRepository safeRepository = repository(safeStore, new DirectExecutor());
        safeRepository.start();
        assertSame(safeFalse, safeRepository.getConfirmedSnapshot());
        assertEquals(0, safeStore.writes.size());
    }

    @Test
    public void errorStateRemainsLatchedUntilConfigurationIsReloaded() {
        FakeStore store = new FakeStore(INITIAL);
        store.results.add(false);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        assertTrue(repository.setDisableModules(true));
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());

        assertFalse(repository.setDisableModules(false));
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
    }

    @Test
    public void boundedInitializationWaitTimesOutWhileSavingAndUnblocksAfterLoad() {
        FakeStore store = new FakeStore(INITIAL);
        QueueExecutor executor = new QueueExecutor();
        ConfigurationRepository repository = repository(store, executor);
        repository.start();

        assertEquals(ConfigurationRepository.State.SAVING, repository.awaitInitialization(0));
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));

        executor.runNext();

        assertEquals(ConfigurationRepository.State.VERIFIED, repository.awaitInitialization(0));
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        assertSame(INITIAL, admission.getSnapshot());
        admission.close();
    }

    @Test
    public void boundedInitializationWaitReturnsErrorAfterLoadFailure() {
        ConfigurationRepository repository = repository(
                new FakeStore(null), new DirectExecutor());
        repository.start();

        assertEquals(ConfigurationRepository.State.ERROR, repository.awaitInitialization(0));
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
    }

    @Test
    public void negativeInitializationTimeoutIsRejectedWithoutAdmission() {
        QueueExecutor executor = new QueueExecutor();
        ConfigurationRepository repository = repository(new FakeStore(INITIAL), executor);
        repository.start();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> repository.awaitInitialization(-1));

        assertEquals("timeoutMillis must be non-negative", failure.getMessage());
        assertEquals(ConfigurationRepository.State.SAVING, repository.getState());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
    }

    @Test
    public void interruptedInitializationWaitFailsClosedAndRestoresInterruptStatus() {
        QueueExecutor executor = new QueueExecutor();
        ConfigurationRepository repository = repository(new FakeStore(INITIAL), executor);
        repository.start();

        Thread.currentThread().interrupt();
        try {
            assertEquals(ConfigurationRepository.State.ERROR,
                    repository.awaitInitialization(5_000L));
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        } finally {
            Thread.interrupted();
        }

        executor.runNext();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.awaitInitialization(0));
    }

    @Test
    public void successfulReloadPersistsExactlyOnceAndAdvancesGeneration() {
        FakeStore store = new FakeStore(INITIAL);
        QueueExecutor executor = new QueueExecutor();
        ConfigurationRepository repository = repository(store, executor);
        repository.start();
        executor.runNext();
        store.results.add(false);
        store.results.add(true);
        assertTrue(repository.setDisableModules(true));
        executor.runNext();
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        int writesBeforeReload = store.writes.size();

        assertTrue(repository.reload());

        assertEquals(ConfigurationRepository.State.SAVING, repository.getState());
        assertEquals(writesBeforeReload, store.writes.size());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        executor.runNext();

        RunConfig confirmed = repository.getConfirmedSnapshot();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertEquals(INITIAL.getGeneration() + 1, confirmed.getGeneration());
        assertNotSame(INITIAL, confirmed);
        assertEquals(writesBeforeReload + 1, store.writes.size());
        assertSame(confirmed, store.writes.get(writesBeforeReload));
        assertSame(confirmed, store.cached);
        assertSame(confirmed, store.durable);
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        assertSame(confirmed, admission.getSnapshot());
        admission.close();
    }

    @Test
    public void failedInitialSafeOffMigrationOnlyVerifiesAfterRetryCheckedWrite() {
        RunConfig legacy = new RunConfig(SuManager.NEXT.getPackageName(), true, false, 0, 2);
        FakeStore store = new FakeStore(legacy);
        store.results.add(false);
        QueueExecutor executor = new QueueExecutor();
        ConfigurationRepository repository = repository(store, executor);
        repository.start();
        executor.runNext();

        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertNull(repository.getConfirmedSnapshot());
        assertEquals(1, store.writes.size());
        assertFalse(store.cached.isSoftReboot());
        assertEquals(3, store.cached.getGeneration());
        assertSame(legacy, store.durable);

        assertTrue(repository.reload());

        assertEquals(ConfigurationRepository.State.SAVING, repository.getState());
        assertEquals(1, store.writes.size());
        assertNull(repository.getConfirmedSnapshot());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        executor.runNext();

        RunConfig confirmed = repository.getConfirmedSnapshot();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertEquals(4, confirmed.getGeneration());
        assertEquals(2, store.writes.size());
        assertSame(confirmed, store.writes.get(1));
        assertSame(confirmed, store.cached);
        assertSame(confirmed, store.durable);
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        assertSame(confirmed, admission.getSnapshot());
        admission.close();
    }

    @Test
    public void failedWriteAndRollbackOnlyVerifyAfterReloadCheckedWrite() {
        FakeStore store = new FakeStore(INITIAL);
        store.results.add(false);
        store.results.add(false);
        QueueExecutor executor = new QueueExecutor();
        ConfigurationRepository repository = repository(store, executor);
        repository.start();
        executor.runNext();

        assertTrue(repository.setManagerPackage(SuManager.NEXT.getPackageName()));
        executor.runNext();

        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertEquals(2, store.writes.size());
        assertSame(INITIAL, store.cached);
        assertSame(INITIAL, store.durable);

        assertTrue(repository.reload());

        assertEquals(ConfigurationRepository.State.SAVING, repository.getState());
        assertEquals(2, store.writes.size());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        executor.runNext();

        RunConfig confirmed = repository.getConfirmedSnapshot();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertEquals(INITIAL.getManagerPackage(), confirmed.getManagerPackage());
        assertEquals(INITIAL.getGeneration() + 1, confirmed.getGeneration());
        assertEquals(3, store.writes.size());
        assertSame(confirmed, store.writes.get(2));
        assertSame(confirmed, store.cached);
        assertSame(confirmed, store.durable);
    }

    @Test
    public void failedReloadCheckedWriteRemainsErrorAndBlocksAdmission() {
        FakeStore store = new FakeStore(INITIAL);
        store.results.add(false);
        store.results.add(true);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        assertTrue(repository.setDisableModules(true));
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());

        String expectedError = "configuration reload failed: commit returned false; rollback succeeded";
        List<ConfigurationRepository.ConfigurationStatus> published = new ArrayList<>();
        repository.addListener(() -> {
            ConfigurationRepository.ConfigurationStatus status = repository.statusForTesting();
            published.add(status);
            if (status.state == ConfigurationRepository.State.ERROR) {
                assertEquals(expectedError, status.errorMessage);
                assertFalse(status.errorMessage.isBlank());
                assertEquals(status.state, repository.getState());
                assertEquals(status.errorMessage, repository.getErrorMessage());
                assertSame(status, repository.statusForTesting());
            }
        });
        store.results.add(false);
        store.results.add(true);
        assertTrue(repository.reload());

        ConfigurationRepository.ConfigurationStatus finalStatus = repository.statusForTesting();
        assertSame(finalStatus, published.get(published.size() - 1));
        assertEquals(ConfigurationRepository.State.ERROR, finalStatus.state);
        assertEquals(expectedError, finalStatus.errorMessage);
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertSame(INITIAL, store.cached);
        assertSame(INITIAL, store.durable);
        assertEquals(4, store.writes.size());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertFalse(repository.isWritePending());
    }

    @Test
    public void reloadRejectsPendingWriteAndFreeze() {
        FakeStore store = new FakeStore(INITIAL);
        QueueExecutor executor = new QueueExecutor();
        ConfigurationRepository repository = repository(store, executor);
        repository.start();
        executor.runNext();
        assertFalse(repository.reload());

        store.results.add(false);
        store.results.add(true);
        assertTrue(repository.setDisableModules(true));
        assertFalse(repository.reload());
        executor.runNext();
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());

        repository.freezeConfiguration();
        assertFalse(repository.reload());
        repository.thawConfiguration();
        assertTrue(repository.reload());
        executor.runNext();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
    }

    @Test
    public void reloadRejectsAFrozenErrorState() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        store.results.add(false);
        store.results.add(true);
        assertTrue(repository.setDisableModules(true));
        repository.freezeConfiguration();

        assertFalse(repository.reload());
        assertTrue(repository.isFrozen());
        repository.thawConfiguration();
        assertTrue(repository.reload());
        assertFalse(repository.isFrozen());
    }

    @Test
    public void failedReloadKeepsErrorAndThePreviouslyConfirmedSnapshot() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        store.results.add(false);
        store.results.add(true);
        assertTrue(repository.setDisableModules(true));
        store.loadFailures.add(new IllegalStateException("durable state unreadable"));

        assertTrue(repository.reload());

        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertTrue(repository.getErrorMessage().contains("configuration reload failed"));
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
        assertFalse(repository.isWritePending());
    }

    @Test
    public void reloadRepeatsCheckedSafeOffMigration() {
        RunConfig durable = new RunConfig(SuManager.NEXT.getPackageName(), true, false, 0, 9);
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        store.results.add(false);
        store.results.add(true);
        assertTrue(repository.setDisableModules(true));
        store.replaceState(durable);

        assertTrue(repository.reload());

        RunConfig confirmed = repository.getConfirmedSnapshot();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertFalse(confirmed.isSoftReboot());
        assertEquals(10, confirmed.getGeneration());
        assertSame(confirmed, store.writes.get(store.writes.size() - 1));
    }

    @Test
    public void executorRejectionsLatchErrorWithoutStoreIoOnTheCaller() {
        RejectingExecutor startExecutor = new RejectingExecutor();
        startExecutor.reject = true;
        FakeStore startStore = new FakeStore(INITIAL);
        ConfigurationRepository startRejected = repository(startStore, startExecutor);
        startRejected.start();
        assertEquals(ConfigurationRepository.State.ERROR, startRejected.getState());
        assertEquals(0, startStore.loads);
        assertEquals(0, startStore.writes.size());

        RejectingExecutor writeExecutor = new RejectingExecutor();
        FakeStore writeStore = new FakeStore(INITIAL);
        ConfigurationRepository writeRejected = repository(writeStore, writeExecutor);
        writeRejected.start();
        writeExecutor.reject = true;
        Thread caller = Thread.currentThread();
        writeStore.callerThread = caller;

        assertTrue(writeRejected.setDisableModules(true));

        assertEquals(ConfigurationRepository.State.ERROR, writeRejected.getState());
        assertFalse(writeRejected.isWritePending());
        assertSame(INITIAL, writeRejected.getConfirmedSnapshot());
        assertEquals(0, writeStore.writes.size());
        assertSame(caller, writeStore.lastIoThread);

        RejectingExecutor reloadExecutor = new RejectingExecutor();
        FakeStore reloadStore = new FakeStore(INITIAL);
        ConfigurationRepository reloadRejected = repository(reloadStore, reloadExecutor);
        reloadRejected.start();
        reloadStore.results.add(false);
        reloadStore.results.add(true);
        assertTrue(reloadRejected.setDisableModules(true));
        assertEquals(ConfigurationRepository.State.ERROR, reloadRejected.getState());
        int loadsBeforeReload = reloadStore.loads;
        int writesBeforeReload = reloadStore.writes.size();
        reloadExecutor.reject = true;

        assertTrue(reloadRejected.reload());

        assertEquals(ConfigurationRepository.State.ERROR, reloadRejected.getState());
        assertFalse(reloadRejected.isWritePending());
        assertEquals(loadsBeforeReload, reloadStore.loads);
        assertEquals(writesBeforeReload, reloadStore.writes.size());
    }

    @Test
    public void removingListenerActuallyStopsFutureNotifications() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        AtomicInteger notifications = new AtomicInteger();
        ConfigurationRepository.Listener listener = notifications::incrementAndGet;
        repository.addListener(listener);
        repository.start();
        assertEquals(1, notifications.get());

        repository.removeListener(listener);
        assertTrue(repository.setDisableModules(true));

        assertEquals(1, notifications.get());
    }

    @Test
    public void nullLoadAndMigrationFailuresLatchError() {
        FakeStore nullStore = new FakeStore(null);
        ConfigurationRepository nullRepository = repository(nullStore, new DirectExecutor());
        nullRepository.start();
        assertEquals(ConfigurationRepository.State.ERROR, nullRepository.getState());
        assertTrue(nullRepository.getErrorMessage().contains("null"));
        assertNull(nullRepository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));

        RunConfig legacy = new RunConfig(null, true, false, 0, 3);
        FakeStore throwingStore = new FakeStore(legacy);
        throwingStore.failures.add(new IllegalStateException("migration unavailable"));
        ConfigurationRepository throwing = repository(throwingStore, new DirectExecutor());
        throwing.start();
        assertEquals(ConfigurationRepository.State.ERROR, throwing.getState());
        assertTrue(throwing.getErrorMessage().contains("safe-off migration"));
        assertNull(throwing.getConfirmedSnapshot());

        FakeStore exhaustedStore = new FakeStore(
                new RunConfig(null, true, false, 0, Long.MAX_VALUE));
        ConfigurationRepository exhausted = repository(exhaustedStore, new DirectExecutor());
        exhausted.start();
        assertEquals(ConfigurationRepository.State.ERROR, exhausted.getState());
        assertTrue(exhausted.getErrorMessage().contains("generation exhausted"));
        assertEquals(0, exhaustedStore.writes.size());
    }

    @Test
    public void ordinarySuccessfulUpdateWritesExactlyOnce() {
        FakeStore store = new FakeStore(INITIAL);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();

        assertTrue(repository.setDisableModules(true));

        assertEquals(1, store.writes.size());
        assertSame(repository.getConfirmedSnapshot(), store.writes.get(0));
    }

    @Test
    public void admissionIsModeBoundAndRejectsBootSoftRebootBeforeFreezing() {
        RunConfig manual = new RunConfig(SuManager.NEXT.getPackageName(), true, false,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 2);
        ConfigurationRepository repository = repository(
                new FakeStore(manual), new DirectExecutor());
        repository.start();

        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.UNATTENDED_BOOT));
        assertFalse(repository.isFrozen());
        assertTrue(repository.getRunAdmissionBlockReason(
                RunConfig.ExecutionMode.UNATTENDED_BOOT).contains("soft reboot"));
        ConfigurationRepository.RunAdmission admission = repository.acquireRunSnapshot(
                RunConfig.ExecutionMode.MANUAL);
        assertEquals(RunConfig.ExecutionMode.MANUAL, admission.getExecutionMode());
        assertTrue(admission.isActive());
        admission.close();
        assertFalse(admission.isActive());
        assertThrows(IllegalStateException.class, admission::getSnapshot);
        assertThrows(IllegalStateException.class, admission::getExecutionMode);
    }

    private static void assertCriticalWriteFails(java.util.function.Consumer<ConfigurationRepository> write) {
        FakeStore store = new FakeStore(INITIAL);
        store.results.add(false);
        ConfigurationRepository repository = repository(store, new DirectExecutor());
        repository.start();
        write.accept(repository);
        assertSame(INITIAL, repository.getConfirmedSnapshot());
        assertEquals(ConfigurationRepository.State.ERROR, repository.getState());
        assertNull(repository.acquireRunSnapshot(RunConfig.ExecutionMode.MANUAL));
    }

    private static ConfigurationRepository repository(FakeStore store, Executor executor) {
        return new ConfigurationRepository(store, executor, Runnable::run);
    }

    private static final class FakeStore implements ConfigurationStore {
        RunConfig cached;
        RunConfig durable;
        final List<RunConfig> writes = new ArrayList<>();
        final Queue<Boolean> results = new ArrayDeque<>();
        final Queue<RuntimeException> failures = new ArrayDeque<>();
        final Queue<RuntimeException> loadFailures = new ArrayDeque<>();
        int loads;
        Thread callerThread;
        Thread lastIoThread;

        FakeStore(RunConfig loaded) {
            replaceState(loaded);
        }

        void replaceState(RunConfig config) {
            cached = config;
            durable = config;
        }

        @Override
        public RunConfig load() {
            loads++;
            lastIoThread = Thread.currentThread();
            if (callerThread != null) {
                assertNotSame("store load ran on the calling thread", callerThread, lastIoThread);
            }
            if (!loadFailures.isEmpty()) {
                throw loadFailures.remove();
            }
            return cached;
        }

        @Override
        public boolean write(RunConfig config) {
            writes.add(config);
            lastIoThread = Thread.currentThread();
            if (callerThread != null) {
                assertNotSame("store write ran on the calling thread", callerThread, lastIoThread);
            }
            // SharedPreferences updates its in-memory map before the checked disk
            // commit returns. A false result or exception must not change durable state.
            cached = config;
            if (!failures.isEmpty()) {
                throw failures.remove();
            }
            boolean result = results.isEmpty() || results.remove();
            if (result) {
                durable = config;
            }
            return result;
        }
    }

    private static final class DirectExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> commands = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.remove().run();
        }
    }

    private static final class RejectingExecutor implements Executor {
        boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("executor unavailable");
            }
            command.run();
        }
    }
}
