// Modified 2026-11-24 (DFRoot GZIF): verify boot action validation, explicit service requests, prompt return, and absence of receiver-owned work. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): verify goAsync claim gating, coalescing, prompt return, and narrow receiver ownership. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): make finish observation deterministic and add real-store restart coverage. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class BootReceiverBoundaryTest {
    private static final long BOOT_COUNT = 71L;

    private Context context;
    private Context deContext;
    private ShadowApplication shadowApplication;

    @Before
    public void setUp() {
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        context = RuntimeEnvironment.getApplication();
        deContext = context.createDeviceProtectedStorageContext();
        shadowApplication = Shadows.shadowOf((Application) context);
        shadowApplication.clearStartedServices();
        deContext.getSharedPreferences(SharedPreferencesBootClaimStore.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT,
                (int) BOOT_COUNT);
    }

    @After
    public void tearDown() {
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void nullAndUnknownActionsDoNothing() {
        BootReceiver receiver = new BootReceiver();

        receiver.onReceive(context, null);
        receiver.onReceive(context, new Intent());
        receiver.onReceive(context, new Intent("df.root.action.MANUAL"));
        receiver.onReceive(context, new Intent(Intent.ACTION_MAIN));
        receiver.onReceive(context, new Intent("android.intent.action.BOOT_COMPLETED.extra"));

        assertNull(shadowApplication.getNextStartedService());
    }

    @Test
    public void nullContextDoesNothingEvenForAWhitelistedAction() {
        new BootReceiver().receive(null, new Intent(Intent.ACTION_BOOT_COMPLETED), ignored -> {
            throw new AssertionError("null context must not create a claim coordinator");
        });
        assertNull(shadowApplication.getNextStartedService());
    }

    @Test
    public void bothBootActionsRequestExplicitUnattendedServiceWork() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimCoordinator claims = inlineClaims(BOOT_COUNT, store);

        BootReceiver.dispatch(context, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), starter);

        // Duplicate unlocked broadcast is coalesced by the durable claim.
        assertEquals(1, starter.calls);
        assertSame(context, starter.contexts.get(0));
    }

    @Test
    public void productionBootActionStartsTheExplicitServiceOnce() throws Exception {
        // The production coordinator and the real service starter are kept; only the
        // finisher is a latch so the duplicate worker's completion is observed
        // deterministically instead of by sleeping.
        CountDownLatch firstFinished = new CountDownLatch(1);
        BootReceiver.submitClaim(context, BootClaimCoordinator::production,
                firstFinished::countDown, BootReceiver::startUnattendedService);
        assertTrue("first claim worker did not finish", firstFinished.await(5L, TimeUnit.SECONDS));

        Intent request = shadowApplication.getNextStartedService();
        assertNotNull(request);
        assertEquals(new ComponentName(context, ExploitService.class), request.getComponent());
        assertEquals(ExploitService.ACTION_UNATTENDED_BOOT, request.getAction());

        CountDownLatch duplicateFinished = new CountDownLatch(1);
        BootReceiver.submitClaim(context, BootClaimCoordinator::production,
                duplicateFinished::countDown, BootReceiver::startUnattendedService);
        assertTrue("duplicate claim worker did not finish",
                duplicateFinished.await(5L, TimeUnit.SECONDS));

        // The latch proves the duplicate worker is done, so this is a stable assertion.
        assertNull(shadowApplication.getNextStartedService());
    }

    @Test
    @Config(sdk = 32)
    public void api32BootRequestDoesNotRequireNotificationPermission() throws Exception {
        shadowApplication.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);

        CountDownLatch finished = new CountDownLatch(1);
        BootReceiver.submitClaim(context, BootClaimCoordinator::production,
                finished::countDown, BootReceiver::startUnattendedService);
        assertTrue("claim worker did not finish", finished.await(5L, TimeUnit.SECONDS));

        Intent request = shadowApplication.getNextStartedService();
        assertNotNull(request);
        assertEquals(ExploitService.ACTION_UNATTENDED_BOOT, request.getAction());
    }

    @Test
    public void receiverInvokesTheNarrowStarterInlineWithAnInlineExecutor() {
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();

        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                inlineClaims(BOOT_COUNT, new BootClaimFakes.MemoryStore()), finisher, starter);

        assertEquals(1, starter.calls);
        assertSame(context, starter.contexts.get(0));
        assertEquals(1, finisher.calls);
    }

    @Test
    public void factoryFailureFinishesExactlyOnceWithoutStarting() {
        BootClaimFakes.Finisher finisher = new BootClaimFakes.Finisher();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootReceiver.ClaimFactory throwing =
                ignored -> { throw new IllegalStateException("claim factory unavailable"); };

        BootReceiver.submitClaim(context, throwing, finisher, starter);

        assertEquals(1, finisher.calls);
        assertEquals(0, starter.calls);
        assertNull(shadowApplication.getNextStartedService());
    }

    @Test
    public void onReceiveRunsTheClaimOffMain() {
        CapturingExecutor executor = new CapturingExecutor();
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimCoordinator claims = new BootClaimCoordinator(
                new BootClaimFakes.MutableReader(BOOT_COUNT), store, executor,
                BootClaimCoordinator.Logger.NO_OP, false);

        new BootReceiver().receive(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                ignored -> claims);

        assertEquals(0, store.attempts.size());
        assertNull(shadowApplication.getNextStartedService());
        assertEquals(1, executor.tasks.size());

        executor.runAll();

        assertEquals(1, store.attempts.size());
        assertNotNull(shadowApplication.getNextStartedService());
    }

    @Test
    public void rejectedActionsNeverReachTheStarter() {
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimCoordinator claims = inlineClaims(BOOT_COUNT, store);

        BootReceiver.dispatch(context, null, claims, new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatch(context, new Intent("unknown"), claims,
                new BootClaimFakes.Finisher(), starter);

        assertEquals(0, starter.calls);
        assertEquals(0, store.attempts.size());
    }

    @Test
    public void serviceStartFailureIsContainedByTheReceiver() {
        BootClaimFakes.MemoryStore store = new BootClaimFakes.MemoryStore();
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();
        starter.fail = true;
        BootClaimCoordinator claims = inlineClaims(BOOT_COUNT, store);
        BootClaimFakes.Finisher first = new BootClaimFakes.Finisher();

        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                claims, first, starter);

        starter.fail = false;
        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(BOOT_COUNT, store.lastClaimed());
        assertEquals(1, first.calls);
    }

    @Test
    public void realDeviceProtectedStoreCoalescesBothBootBroadcasts() {
        BootClaimStore store = SharedPreferencesBootClaimStore.forDeviceProtectedStorage(context);
        BootClaimCoordinator claims = new BootClaimCoordinator(
                new BootClaimFakes.MutableReader(72L), store, Runnable::run,
                BootClaimCoordinator.Logger.NO_OP, false);
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();

        BootReceiver.dispatch(context, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(72L, store.lastClaimed());
    }

    @Test
    public void realDeviceProtectedStoreCoalescesUnlockedThenLocked() {
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 75);
        BootClaimStore store = SharedPreferencesBootClaimStore.forDeviceProtectedStorage(context);
        BootClaimCoordinator claims = realStoreClaims(context, store);
        BootClaimFakes.Starter starter = new BootClaimFakes.Starter();

        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), starter);
        BootReceiver.dispatch(context, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), starter);

        assertEquals(1, starter.calls);
        assertEquals(75L, store.lastClaimed());
    }

    @Test
    public void realStoreRecreatedAfterProcessRestartDoesNotStartTheServiceAgain() {
        BootClaimStore firstStore = SharedPreferencesBootClaimStore.forDeviceProtectedStorage(context);
        BootReceiver.submitClaim(context, ignored -> realStoreClaims(context, firstStore),
                new BootClaimFakes.Finisher(), BootReceiver::startUnattendedService);

        assertNotNull(shadowApplication.getNextStartedService());
        long persisted = firstStore.lastClaimed();
        assertEquals(BOOT_COUNT, persisted);

        // A fresh store instance over the same DE preferences models process death.
        BootClaimStore restartedStore =
                SharedPreferencesBootClaimStore.forDeviceProtectedStorage(context);
        BootReceiver.submitClaim(context, ignored -> realStoreClaims(context, restartedStore),
                new BootClaimFakes.Finisher(), BootReceiver::startUnattendedService);

        assertNull(shadowApplication.getNextStartedService());
        assertEquals(persisted, restartedStore.lastClaimed());
    }

    @Test
    public void claimIsPersistedInDeviceProtectedStorageWithoutTouchingConfigurationKeys()
            throws InterruptedException {
        SharedPreferences bootPrefs = deContext.getSharedPreferences(
                SharedPreferencesBootClaimStore.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences configPrefs = deContext.getSharedPreferences(
                ConfigurationKeys.PREFS_NAME, Context.MODE_PRIVATE);
        assertFalse(bootPrefs.contains(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));

        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        BootClaimCoordinator.production(context).submit(context, finished::countDown,
                new BootClaimFakes.Starter());
        assertTrue("claim worker did not finish",
                finished.await(5L, TimeUnit.SECONDS));

        assertTrue(bootPrefs.contains(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
        assertEquals(BOOT_COUNT,
                bootPrefs.getLong(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT, -1L));
        assertFalse(configPrefs.contains(SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
        assertFalse(context.getSharedPreferences(SharedPreferencesBootClaimStore.PREFS_NAME,
                Context.MODE_PRIVATE).contains(
                        SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
    }

    @Test
    public void bootAndManualOverlapRemainsRejectedByTheSharedCoordinator() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new StaticConfigStore(new RunConfig(SuManager.NEXT.getPackageName(),
                        false, false, 0, 3)), Runnable::run, Runnable::run);
        repository.start();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        RunCoordinator coordinator = new RunCoordinator(repository, new QueueExecutor(),
                Runnable::run, (ctx, admission, mode, id, events, reporter) -> null, 0L);
        FakeLease manualLease = new FakeLease();
        assertTrue(coordinator.submit(context, RunConfig.ExecutionMode.MANUAL,
                manualLease).isAccepted());

        Intent bootIntent = ExploitService.requestIntent(context,
                ExploitService.ACTION_UNATTENDED_BOOT);
        assertEquals(ExploitService.ACTION_UNATTENDED_BOOT, bootIntent.getAction());
        FakeLease bootLease = new FakeLease();
        assertFalse(coordinator.submit(context, RunConfig.ExecutionMode.UNATTENDED_BOOT,
                bootLease).isAccepted());
        assertEquals(0, bootLease.closeCount.get());
        assertEquals(0, manualLease.closeCount.get());
    }

    @Test
    public void receiverOwnsNoThreadWakeLockAdmissionProbeOrRunner() throws Exception {
        File source = TestFixtures.repositoryRoot().resolve("app/src/main/java/df/root/BootReceiver.java")
                .toFile();
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        String code = text.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
        for (String forbidden : new String[] {"new Thread", "Thread", "Executor", "PowerManager",
                "WakeLock", "RunCoordinator", "RunAdmission", "acquireRunSnapshot", "ExploitRunner",
                "HookMarkerProbe", "IReporter"}) {
            assertFalse("BootReceiver must not own " + forbidden, code.contains(forbidden));
        }
        // The receiver delegates the short claim to BootClaimCoordinator; it owns no
        // executor, thread, wake lock, runner, probe, or admission path of its own.
        assertTrue(code.contains("goAsync()"));
        assertTrue(code.contains("BootClaimCoordinator::production"));
        assertTrue(text.contains("Intent.ACTION_LOCKED_BOOT_COMPLETED"));
        assertTrue(text.contains("Intent.ACTION_BOOT_COMPLETED"));
        assertTrue(text.contains("ExploitService.ACTION_UNATTENDED_BOOT"));
    }

    @Test
    public void receiverRemainsDirectBootAwareDisabledAndNotExported() throws Exception {
        File manifest = TestFixtures.repositoryRoot().resolve("app/src/main/AndroidManifest.xml").toFile();
        NodeList receivers = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(manifest).getElementsByTagName("receiver");
        Element receiver = null;
        for (int i = 0; i < receivers.getLength(); i++) {
            Element candidate = (Element) receivers.item(i);
            if (".BootReceiver".equals(candidate.getAttribute("android:name"))) {
                receiver = candidate;
                break;
            }
        }
        assertNotNull(receiver);
        assertEquals("true", receiver.getAttribute("android:directBootAware"));
        assertEquals("false", receiver.getAttribute("android:enabled"));
        assertEquals("false", receiver.getAttribute("android:exported"));
        NodeList actions = receiver.getElementsByTagName("action");
        Set<String> declaredActions = new HashSet<>();
        for (int i = 0; i < actions.getLength(); i++) {
            declaredActions.add(((Element) actions.item(i)).getAttribute("android:name"));
        }
        assertEquals(Set.of(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED),
                declaredActions);
    }

    private static BootClaimCoordinator inlineClaims(long bootCount, BootClaimStore store) {
        return BootClaimCoordinator.inline(new BootClaimFakes.MutableReader(bootCount), store);
    }

    private static BootClaimCoordinator realStoreClaims(Context context, BootClaimStore store) {
        return new BootClaimCoordinator(
                new BootCountReader.SettingsGlobal(context.getContentResolver()), store,
                Runnable::run, BootClaimCoordinator.Logger.NO_OP, false);
    }

    private static final class CapturingExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }

    private static final class StaticConfigStore implements ConfigurationStore {
        private final RunConfig config;

        StaticConfigStore(RunConfig config) {
            this.config = config;
        }

        @Override
        public RunConfig load() {
            return config;
        }

        @Override
        public boolean write(RunConfig next) {
            return true;
        }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }

    private static final class FakeLease implements ResourceLease {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
