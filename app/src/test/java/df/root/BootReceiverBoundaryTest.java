// Modified 2026-11-24 (DFRoot GZIF): verify boot action validation, explicit service requests, prompt return, and absence of receiver-owned work. See NOTICE.
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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class BootReceiverBoundaryTest {
    private Context context;
    private ShadowApplication shadowApplication;

    @Before
    public void setUp() {
        RunCoordinator.resetForTesting();
        ConfigurationRepository.resetForTesting();
        context = RuntimeEnvironment.getApplication();
        shadowApplication = Shadows.shadowOf((Application) context);
        shadowApplication.clearStartedServices();
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
        BootReceiver.dispatch(null, new Intent(Intent.ACTION_BOOT_COMPLETED), ignored -> {
            throw new AssertionError("null context must not start the service");
        });
    }

    @Test
    public void bothBootActionsRequestExplicitUnattendedServiceWork() {
        BootReceiver receiver = new BootReceiver();

        receiver.onReceive(context, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        Intent locked = shadowApplication.getNextStartedService();
        assertNotNull(locked);
        assertEquals(new ComponentName(context, ExploitService.class), locked.getComponent());
        assertEquals(ExploitService.ACTION_UNATTENDED_BOOT, locked.getAction());

        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent unlocked = shadowApplication.getNextStartedService();
        assertNotNull(unlocked);
        assertEquals(new ComponentName(context, ExploitService.class), unlocked.getComponent());
        assertEquals(ExploitService.ACTION_UNATTENDED_BOOT, unlocked.getAction());
    }

    @Test
    @Config(sdk = 32)
    public void api32BootRequestDoesNotRequireNotificationPermission() {
        shadowApplication.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        Intent request = shadowApplication.getNextStartedService();
        assertNotNull(request);
        assertEquals(ExploitService.ACTION_UNATTENDED_BOOT, request.getAction());
    }

    @Test
    public void receiverInvokesTheNarrowStarterInlineAndReturnsPromptly() {
        RecordingStarter starter = new RecordingStarter();
        Thread caller = Thread.currentThread();

        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED), starter);

        assertEquals(1, starter.calls.get());
        assertSame(context, starter.context);
        assertSame(caller, starter.thread);
    }

    @Test
    public void rejectedActionsNeverReachTheStarter() {
        RecordingStarter starter = new RecordingStarter();

        BootReceiver.dispatch(context, null, starter);
        BootReceiver.dispatch(context, new Intent("unknown"), starter);

        assertEquals(0, starter.calls.get());
        assertNull(starter.context);
    }

    @Test
    public void serviceStartFailureIsContainedByTheReceiver() {
        BootReceiver.dispatch(context, new Intent(Intent.ACTION_BOOT_COMPLETED), ignored -> {
            throw new IllegalStateException("foreground start denied");
        });
    }

    @Test
    public void receiverOwnsNoThreadWakeLockAdmissionProbeOrRunner() throws Exception {
        File source = TestFixtures.repositoryRoot().resolve("app/src/main/java/df/root/BootReceiver.java")
                .toFile();
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        String code = text.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
        for (String forbidden : new String[] {"new Thread", "Thread", "PowerManager", "WakeLock",
                "RunCoordinator", "RunAdmission", "acquireRunSnapshot", "ExploitRunner",
                "HookMarkerProbe", "BootReceiverDependencies", "Executor", "IReporter"}) {
            assertFalse("BootReceiver must not own " + forbidden, code.contains(forbidden));
        }
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

    private static final class RecordingStarter implements BootReceiver.ServiceStarter {
        final AtomicInteger calls = new AtomicInteger();
        Context context;
        Thread thread;

        @Override
        public void startUnattendedBoot(Context context) {
            calls.incrementAndGet();
            this.context = context;
            this.thread = Thread.currentThread();
        }
    }
}
