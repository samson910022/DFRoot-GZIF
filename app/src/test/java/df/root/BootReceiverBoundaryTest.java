// Modified 2026-11-24 (DFRoot GZIF): verify boot action validation, explicit service requests, prompt return, and absence of receiver-owned work. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): verify goAsync claim gating, coalescing, prompt return, and narrow receiver ownership. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): make finish observation deterministic and add real-store restart coverage. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): model the Start at Boot component state in production-path tests. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): drive OFF-at-admission work on a controllable executor and assert the correlated REJECTED outcome. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): confirm the main-thread boundary still queues exactly one admission task before driving it. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF): confirm a duplicate boot start is rejected before a second WakeLock while its request is queued. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R5.3): wire the coordinator's reporter to the deterministic queue that already counts one boot work plus one report. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        // Start at Boot is a separate component setting. Model the owner having
        // enabled it before exercising normal boot paths.
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
    }

    @After
    public void tearDown() {
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        ShadowLooper.idleMainLooper();
    }

    private void setBootComponent(int state) {
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, BootReceiver.class), state,
                PackageManager.DONT_KILL_APP);
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
    public void productionBootRequestAfterOffDoesNotStartTheService() throws Exception {
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        CountDownLatch finished = new CountDownLatch(1);
        BootReceiver.submitClaim(context, BootClaimCoordinator::production,
                finished::countDown, BootReceiver::startUnattendedService);

        assertTrue("claim worker did not finish", finished.await(5L, TimeUnit.SECONDS));
        assertNull(shadowApplication.getNextStartedService());
    }

    @Test
    public void freshInstallDefaultComponentStateDoesNotClaimOrStartService() throws Exception {
        // Fresh install leaves the component at DEFAULT (the manifest declares it
        // disabled). The production gate must fail closed on that real state.
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        CountDownLatch finished = new CountDownLatch(1);
        BootReceiver.submitClaim(context, BootClaimCoordinator::production,
                finished::countDown, BootReceiver::startUnattendedService);

        assertTrue("claim worker did not finish", finished.await(5L, TimeUnit.SECONDS));
        assertNull(shadowApplication.getNextStartedService());
        SharedPreferences bootPrefs = deContext.getSharedPreferences(
                SharedPreferencesBootClaimStore.PREFS_NAME, Context.MODE_PRIVATE);
        assertFalse(bootPrefs.contains(
                SharedPreferencesBootClaimStore.KEY_LAST_CLAIMED_BOOT_COUNT));
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
    public void componentOffBetweenClaimAndQueuedServiceDeliverySuppressesAdmission() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new StaticConfigStore(new RunConfig(SuManager.NEXT.getPackageName(),
                        false, false, 0, 3)), Runnable::run, Runnable::run);
        repository.start();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        ConfigurationRepository.setInstanceForTesting(repository);
        AtomicInteger workerTasks = new AtomicInteger();
        RunCoordinator coordinator = new RunCoordinator(repository, command -> workerTasks.incrementAndGet(),
                Runnable::run, (ctx, admission, mode, id, events, reporter) -> null, 0L);
        RunCoordinator.setInstanceForTesting(coordinator);

        BootClaimCoordinator claims = inlineClaims(BOOT_COUNT, new BootClaimFakes.MemoryStore());
        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), BootReceiver::startUnattendedService);
        Intent request = shadowApplication.getNextStartedService();
        assertNotNull(request);
        assertEquals(ExploitService.ACTION_UNATTENDED_BOOT, request.getAction());

        // The owner turns Start at Boot OFF while the already-claimed FGS start
        // is still queued for delivery to the service.
        setBootComponent(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        ServiceController<ExploitService> controller = Robolectric.buildService(ExploitService.class)
                .create().withIntent(request);
        // Hold the blocking admission work on a controllable executor instead of
        // the real single-thread daemon worker. onStartCommand only queues it;
        // driving it explicitly removes the race against onDestroy() so the
        // correlated OFF-at-admission outcome is deterministic.
        CapturingExecutor admission = new CapturingExecutor();
        controller.get().setAdmissionExecutorForTesting(admission);
        try {
            assertEquals(Service.START_NOT_STICKY,
                    controller.get().onStartCommand(request, 0, 91));
            // The request is queued, not admitted inline by onStartCommand.
            assertEquals(1, admission.tasks.size());
            assertEquals(RunCoordinator.State.IDLE, coordinator.getSnapshot().getState());
            assertEquals(RunCoordinator.RequestOutcome.NONE,
                    coordinator.getSnapshot().getRequestOutcome());
            assertEquals(0, workerTasks.get());

            // Drive the queued worker explicitly. It re-checks the component,
            // observes OFF, and publishes this request's REJECTED outcome before
            // destroy() runs, so CANCELLED cannot win the claim.
            admission.runAll();
            assertEquals(0, admission.tasks.size());
            RunCoordinator.Snapshot rejection = coordinator.getSnapshot();
            assertEquals(RunCoordinator.State.IDLE, rejection.getState());
            assertEquals(RunCoordinator.RequestOutcome.REJECTED, rejection.getRequestOutcome());
            assertNotNull(rejection.getRequestFailure());
            assertTrue(rejection.getRequestFailure().contains("DISABLED"));
            assertEquals(0, workerTasks.get());
            assertNull(shadowApplication.getNextStartedService());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void duplicateBootStartWhileAdmissionIsQueuedDoesNotAcquireAnotherLease() {
        ConfigurationRepository repository = new ConfigurationRepository(
                new StaticConfigStore(new RunConfig(SuManager.NEXT.getPackageName(),
                        false, false, 0, 3)), Runnable::run, Runnable::run);
        repository.start();
        assertEquals(ConfigurationRepository.State.VERIFIED, repository.getState());
        RunCoordinator coordinator = new RunCoordinator(repository, new QueueExecutor(),
                Runnable::run, (ctx, admission, mode, id, events, reporter) -> null, 0L);
        RunCoordinator.setInstanceForTesting(coordinator);

        BootClaimCoordinator claims = inlineClaims(BOOT_COUNT, new BootClaimFakes.MemoryStore());
        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED),
                claims, new BootClaimFakes.Finisher(), BootReceiver::startUnattendedService);
        Intent request = shadowApplication.getNextStartedService();
        assertNotNull(request);

        ServiceController<ExploitService> controller = Robolectric.buildService(ExploitService.class)
                .create();
        CapturingExecutor admission = new CapturingExecutor();
        controller.get().setAdmissionExecutorForTesting(admission);
        // R5.3: the duplicate's outcome report now goes through the
        // coordinator's bounded reporter; keep it in the same deterministic
        // queue so "one work + one report" remains observable.
        coordinator.setOutcomeExecutorForTesting(admission);
        List<FakeLease> leases = new ArrayList<>();
        controller.get().setWakeLockSourceForTesting(ignored -> {
            FakeLease lease = new FakeLease();
            leases.add(lease);
            return lease;
        });
        try {
            assertEquals(Service.START_NOT_STICKY,
                    controller.get().onStartCommand(request, 0, 93));
            assertEquals(Service.START_NOT_STICKY,
                    controller.get().onStartCommand(request, 0, 94));

            // The boot request is queued, not admitted yet. Its duplicate is
            // rejected on the caller: no second WakeLock, no second work item.
            assertEquals(1, leases.size());
            assertEquals(1, controller.get().pendingAdmissionCountForTesting());
            assertEquals(2, admission.tasks.size()); // one work + one report
            assertEquals(ExploitService.AdmissionState.PENDING,
                    controller.get().getAdmissionStateForTesting());
            assertEquals(RunCoordinator.State.IDLE, coordinator.getSnapshot().getState());
        } finally {
            controller.destroy();
        }
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
