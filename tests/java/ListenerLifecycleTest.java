// Host-only tests for listener lifecycle and the pure boot UI policy (P2-10).
// No Android runtime, device or privileged operation is involved.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class ListenerLifecycleTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File marker;

    @Before
    public void setUp() {
        marker = new File(tmp.getRoot(), "pref_integrity.marker");
        PreferenceIntegrity.resetForTest();
        PreferenceIntegrity.init(marker);
    }

    @After
    public void tearDown() {
        PreferenceIntegrity.resetForTest();
    }

    @Test
    public void removeListenerStopsCallbacksAfterScreenRecreation() {
        CountingListener oldScreen = new CountingListener();
        PreferenceIntegrity.addListener(oldScreen);
        PreferenceIntegrity.markAmbiguous("su_manager");
        assertEquals(1, oldScreen.calls);

        // The old screen is destroyed; a new instance replaces it.
        PreferenceIntegrity.removeListener(oldScreen);
        CountingListener newScreen = new CountingListener();
        PreferenceIntegrity.addListener(newScreen);
        PreferenceIntegrity.markConsistent("su_manager");

        assertEquals("destroyed screen must receive no more callbacks", 1, oldScreen.calls);
        assertEquals(1, newScreen.calls);
    }

    @Test
    public void duplicateRegistrationFiresOnce() {
        CountingListener listener = new CountingListener();
        PreferenceIntegrity.addListener(listener);
        PreferenceIntegrity.addListener(listener); // e.g. onResume twice
        PreferenceIntegrity.markAmbiguous("soft_reboot");
        assertEquals(1, listener.calls);
    }

    @Test
    public void bootPolicyDisablesOnQuarantineEvenWhenMarkerIsHealthy() {
        assertFalse(BootUiPolicy.desiredChecked(false));
        assertTrue(BootUiPolicy.desiredChecked(true));

        // quarantined && markerActive must still disable: marker health is not
        // an oracle that makes an uncertain commit safe.
        assertTrue(BootUiPolicy.disableOnQuarantine(true, true));
        assertTrue(BootUiPolicy.disableOnQuarantine(true, false));
        assertFalse(BootUiPolicy.disableOnQuarantine(false, true));
        assertFalse(BootUiPolicy.disableOnQuarantine(false, false));

        // The user can always turn boot OFF, but never ON while quarantined.
        assertTrue(BootUiPolicy.mayChangeTo(false, true));
        assertFalse(BootUiPolicy.mayChangeTo(true, true));
        assertTrue(BootUiPolicy.mayChangeTo(true, false));
        assertTrue(BootUiPolicy.mayChangeTo(false, false));
    }

    @Test
    public void frameworkChangeIsRejectedWhenActualStateDisagrees() {
        // TwoStatePreference re-applies the requested value after a listener
        // returns true, so a mismatch must be rejected to keep the UI truthful.
        assertTrue(BootUiPolicy.acceptFrameworkChange(true, true));
        assertTrue(BootUiPolicy.acceptFrameworkChange(false, false));
        assertFalse("never show ON when the component is still disabled",
                BootUiPolicy.acceptFrameworkChange(true, false));
        assertFalse("never show OFF when the component is still enabled",
                BootUiPolicy.acceptFrameworkChange(false, true));
    }

    @Test
    public void recoveryDoesNotAutoEnableAndReflectsActualState() {
        // Recovery ignores the previously checked value entirely.
        assertFalse(BootUiPolicy.onRecovery(false, true));
        assertFalse(BootUiPolicy.onRecovery(false, false));
        assertTrue(BootUiPolicy.onRecovery(true, false));
        assertTrue(BootUiPolicy.onRecovery(true, true));

        PreferenceIntegrity.markAmbiguous("su_manager");
        assertTrue(PreferenceIntegrity.isQuarantined());
        PreferenceIntegrity.markConsistent("su_manager");
        assertFalse(PreferenceIntegrity.isQuarantined());
        // Even after the latch clears, the component is still disabled until
        // the user explicitly turns it back on.
        assertFalse(BootUiPolicy.desiredChecked(false));
    }

    private static final class CountingListener implements PreferenceIntegrity.Listener {
        int calls;

        @Override
        public void onIntegrityChanged() {
            calls++;
        }
    }
}
