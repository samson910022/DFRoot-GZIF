// Modified 2026-11-24 (DFRoot GZIF): verify the production dfroot-config worker and main-looper Handler under Robolectric. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class ConfigurationRepositoryThreadingTest {
    private static final RunConfig INITIAL = RunConfig.defaults()
            .withManagerPackage(SuManager.NEXT.getPackageName());
    private RecordingStore store;
    private ExecutorService worker;
    private ConfigurationRepository repository;

    @Before
    public void setUp() {
        ConfigurationRepository.resetForTesting();
        store = new RecordingStore();
        worker = ConfigurationRepository.newWorkerExecutor();
        repository = new ConfigurationRepository(
                store, worker, ConfigurationRepository.newMainExecutor());
    }

    @After
    public void tearDown() {
        ConfigurationRepository.resetForTesting();
        if (worker != null) {
            worker.shutdownNow();
        }
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void storeIoUsesDfrootConfigAndListenersUseTheMainLooper() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        List<Looper> callbackLoopers = new ArrayList<>();
        repository.addListener(() -> {
            callbacks.incrementAndGet();
            callbackLoopers.add(Looper.myLooper());
        });
        ConfigurationRepository.setInstanceForTesting(repository);

        repository.start();
        assertEquals(ConfigurationRepository.State.VERIFIED,
                repository.awaitInitialization(5_000L));
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();

        assertEquals(1, store.loads);
        assertEquals("dfroot-config", store.loadThread.getName());
        assertTrue(callbacks.get() > 0);
        for (Looper looper : callbackLoopers) {
            assertSame(Looper.getMainLooper(), looper);
        }

        assertTrue(repository.setDisableModules(true));
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();

        assertEquals(1, store.writes);
        assertEquals("dfroot-config", store.writeThreads.get(0).getName());
        assertTrue(repository.getConfirmedSnapshot().isDisableModules());
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
    }

    @Test
    public void secondCallerIsRejectedWhileTheFirstStoreWriteIsPending() throws Exception {
        BlockingStore blockingStore = new BlockingStore();
        repository = new ConfigurationRepository(
                blockingStore, worker, ConfigurationRepository.newMainExecutor());
        repository.start();
        assertEquals(ConfigurationRepository.State.VERIFIED,
                repository.awaitInitialization(5_000L));
        ExecutorService secondCaller = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "dfroot-config-second-caller"));
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        try {
            assertTrue(repository.setManagerPackage(SuManager.CLASSIC.getPackageName()));
            assertTrue(blockingStore.writeEntered.await(5, TimeUnit.SECONDS));

            Future<Boolean> secondUpdate = secondCaller.submit(() -> {
                callerThread.set(Thread.currentThread());
                return repository.setDisableModules(true);
            });

            assertFalse(secondUpdate.get(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), callerThread.get());
            assertTrue(repository.isWritePending());
            assertSame(INITIAL, repository.getConfirmedSnapshot());
            assertEquals(0, blockingStore.writes);
        } finally {
            blockingStore.releaseWrite.countDown();
            secondCaller.shutdownNow();
        }
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();

        RunConfig confirmed = repository.getConfirmedSnapshot();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        assertEquals(INITIAL.getGeneration() + 1, confirmed.getGeneration());
        assertEquals(SuManager.CLASSIC.getPackageName(), confirmed.getManagerPackage());
        assertFalse(confirmed.isDisableModules());
        assertEquals(1, blockingStore.writes);
        assertSame(confirmed, blockingStore.durable);
    }

    @Test
    public void enqueueMonitorSerializesCallersWhileSavingListenerBlocks() throws Exception {
        ConfigurationRepository directRepository = new ConfigurationRepository(
                store, Runnable::run, Runnable::run);
        directRepository.start();
        assertEquals(ConfigurationRepository.State.VERIFIED, directRepository.getState());

        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch secondCallerStarted = new CountDownLatch(1);
        AtomicBoolean blockSaving = new AtomicBoolean(true);
        directRepository.addListener(() -> {
            if (directRepository.getState() == ConfigurationRepository.State.SAVING
                    && blockSaving.compareAndSet(true, false)) {
                listenerEntered.countDown();
                try {
                    if (!releaseListener.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the SAVING listener");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("SAVING listener was interrupted", e);
                }
            }
        });
        ExecutorService firstCaller = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "dfroot-config-first-caller"));
        ExecutorService secondCaller = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "dfroot-config-second-caller"));
        Future<Boolean> firstUpdate;
        Future<Boolean> secondUpdate;
        try {
            firstUpdate = firstCaller.submit(() -> directRepository.setManagerPackage(
                    SuManager.CLASSIC.getPackageName()));
            assertTrue("first caller never entered the SAVING listener",
                    listenerEntered.await(5, TimeUnit.SECONDS));

            secondUpdate = secondCaller.submit(() -> {
                secondCallerStarted.countDown();
                return directRepository.update(
                        INITIAL.withDisableModules(true), INITIAL.getGeneration());
            });
            assertTrue("second caller never started",
                    secondCallerStarted.await(5, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class, () -> secondUpdate.get(1, TimeUnit.SECONDS));
            assertFalse(secondUpdate.isDone());
            assertEquals(ConfigurationRepository.State.SAVING, directRepository.getState());
            assertSame(INITIAL, directRepository.getConfirmedSnapshot());
            assertEquals(0, store.writes);
        } finally {
            releaseListener.countDown();
            firstCaller.shutdown();
            secondCaller.shutdown();
            try {
                assertTrue("first caller did not terminate",
                        firstCaller.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue("second caller did not terminate",
                        secondCaller.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                firstCaller.shutdownNow();
                secondCaller.shutdownNow();
            }
        }

        assertTrue(firstUpdate.get(5, TimeUnit.SECONDS));
        assertFalse(secondUpdate.get(5, TimeUnit.SECONDS));
        RunConfig confirmed = directRepository.getConfirmedSnapshot();
        assertEquals(ConfigurationRepository.State.VERIFIED, directRepository.getState());
        assertEquals(INITIAL.getGeneration() + 1, confirmed.getGeneration());
        assertEquals(SuManager.CLASSIC.getPackageName(), confirmed.getManagerPackage());
        assertFalse(confirmed.isDisableModules());
        assertEquals(1, store.writes);
        assertSame(confirmed, store.cached);
        assertSame(confirmed, store.durable);
    }

    private static class RecordingStore implements ConfigurationStore {
        RunConfig cached = INITIAL;
        RunConfig durable = INITIAL;
        final List<Thread> writeThreads = new ArrayList<>();
        Thread loadThread;
        int loads;
        int writes;

        @Override
        public RunConfig load() {
            loads++;
            loadThread = Thread.currentThread();
            return cached;
        }

        @Override
        public boolean write(RunConfig config) {
            writes++;
            writeThreads.add(Thread.currentThread());
            cached = config;
            durable = config;
            return true;
        }
    }

    private static final class BlockingStore extends RecordingStore {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        private boolean blockNextWrite = true;

        @Override
        public boolean write(RunConfig config) {
            if (blockNextWrite) {
                blockNextWrite = false;
                writeEntered.countDown();
                try {
                    if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release the pending write");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("pending write was interrupted", e);
                }
            }
            return super.write(config);
        }
    }
}
