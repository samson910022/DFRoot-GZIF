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

    @Test
    public void onlyExplicitComponentSettingsAreVerifiedObservations() {
        // PackageManager integer values: 1 ENABLED, 2 DISABLED, 0 DEFAULT.
        BootUiPolicy.BootComponentState enabled = BootUiPolicy.fromPackageManagerState(1);
        BootUiPolicy.BootComponentState disabled = BootUiPolicy.fromPackageManagerState(2);
        BootUiPolicy.BootComponentState unknown = BootUiPolicy.fromPackageManagerState(0);
        assertEquals(BootUiPolicy.BootComponentState.ENABLED, enabled);
        assertEquals(BootUiPolicy.BootComponentState.DISABLED, disabled);
        assertEquals(BootUiPolicy.BootComponentState.UNKNOWN, unknown);
        assertEquals(BootUiPolicy.BootComponentState.UNKNOWN,
                BootUiPolicy.fromPackageManagerState(99));

        assertTrue(BootUiPolicy.desiredChecked(enabled));
        assertFalse(BootUiPolicy.desiredChecked(disabled));
        assertFalse("UNKNOWN is shown conservatively, not verified OFF",
                BootUiPolicy.desiredChecked(unknown));
        assertTrue(BootUiPolicy.verifiedDisabled(disabled));
        assertFalse(BootUiPolicy.verifiedDisabled(unknown));
        assertFalse(BootUiPolicy.verifiedDisabled(enabled));

        // The user may always try OFF; ON needs a trustworthy, verified state.
        assertTrue(BootUiPolicy.mayChangeTo(false, unknown, true));
        assertTrue(BootUiPolicy.mayChangeTo(false, unknown, false));
        assertFalse(BootUiPolicy.mayChangeTo(true, unknown, false));
        assertFalse(BootUiPolicy.mayChangeTo(true, unknown, true));
        assertTrue(BootUiPolicy.mayChangeTo(true, disabled, false));
        assertFalse(BootUiPolicy.mayChangeTo(true, disabled, true));

        // Framework re-application is accepted only for an observed matching state.
        assertTrue(BootUiPolicy.acceptFrameworkChange(true, enabled));
        assertTrue(BootUiPolicy.acceptFrameworkChange(false, disabled));
        assertFalse(BootUiPolicy.acceptFrameworkChange(true, disabled));
        assertFalse("UNKNOWN rejects every framework write",
                BootUiPolicy.acceptFrameworkChange(false, unknown));
        assertFalse(BootUiPolicy.acceptFrameworkChange(true, unknown));
    }

    @Test
    public void readbackFailureIsNotEvidenceOfDisabledAndNeverAutoEnables() {
        BootUiPolicy.BootComponentState failure = BootUiPolicy.fromReadbackFailure();
        assertEquals(BootUiPolicy.BootComponentState.UNKNOWN, failure);
        assertFalse("a failed observation is not verified OFF",
                BootUiPolicy.verifiedDisabled(failure));
        assertFalse(BootUiPolicy.desiredChecked(failure));
        assertFalse("recovery never auto-enables an unverifiable component",
                BootUiPolicy.onRecovery(failure, true));
        assertTrue("the switch stays usable so the user can try turning it OFF",
                BootUiPolicy.mayChangeTo(false, failure, true));
        assertFalse(BootUiPolicy.acceptFrameworkChange(false, failure));
        assertFalse(BootUiPolicy.acceptFrameworkChange(true, failure));
        // Quarantine disables independently of the observation and marker health.
        assertTrue(BootUiPolicy.disableOnQuarantine(true, true));
        assertTrue(BootUiPolicy.disableOnQuarantine(true, false));
        assertFalse(BootUiPolicy.disableOnQuarantine(false, false));
    }

    @Test
    public void unknownPresentationIsPersistentAndNeverClaimsOff() {
        BootUiPolicy.BootComponentState fromDefault = BootUiPolicy.fromPackageManagerState(0);
        BootUiPolicy.BootComponentState fromUnknownConstant = BootUiPolicy.fromPackageManagerState(99);
        BootUiPolicy.BootComponentState fromFailure = BootUiPolicy.fromReadbackFailure();

        for (BootUiPolicy.BootComponentState unknown : new BootUiPolicy.BootComponentState[]{
                fromDefault, fromUnknownConstant, fromFailure}) {
            assertEquals(BootUiPolicy.BootComponentState.UNKNOWN, unknown);
            assertEquals("DEFAULT/unknown/exception must present as UNKNOWN",
                    BootUiPolicy.BootStatusPresentation.UNKNOWN,
                    BootUiPolicy.presentation(unknown));
            assertTrue("an unverifiable observation must be flagged",
                    BootUiPolicy.isUnverifiable(unknown));
            assertFalse("UNKNOWN is never verified OFF", BootUiPolicy.verifiedDisabled(unknown));
            assertFalse("the thumb is unchecked, but that is not a status claim",
                    BootUiPolicy.desiredChecked(unknown));
        }

        // DISABLED is the only observation that may be presented as verified OFF.
        BootUiPolicy.BootComponentState disabled = BootUiPolicy.fromPackageManagerState(2);
        assertEquals(BootUiPolicy.BootStatusPresentation.OFF_VERIFIED,
                BootUiPolicy.presentation(disabled));
        assertTrue(BootUiPolicy.verifiedDisabled(disabled));
        assertFalse(BootUiPolicy.isUnverifiable(disabled));
        assertFalse(BootUiPolicy.desiredChecked(disabled));

        // ENABLED maps to ON, never to any OFF claim.
        BootUiPolicy.BootComponentState enabled = BootUiPolicy.fromPackageManagerState(1);
        assertEquals(BootUiPolicy.BootStatusPresentation.ON, BootUiPolicy.presentation(enabled));
        assertFalse(BootUiPolicy.verifiedDisabled(enabled));
        assertFalse(BootUiPolicy.isUnverifiable(enabled));
        assertTrue(BootUiPolicy.desiredChecked(enabled));
    }

    @Test
    public void explicitDisableIsAlwaysPermittedEvenWhenUnknownOrQuarantined() {
        BootUiPolicy.BootComponentState unknown = BootUiPolicy.fromReadbackFailure();
        assertTrue("the user may always request an explicit OFF",
                BootUiPolicy.mayRequestDisable());
        assertTrue(BootUiPolicy.mayChangeTo(false, unknown, true));
        assertTrue(BootUiPolicy.mayChangeTo(false, unknown, false));

        // ON requests stay refused under the same uncertain conditions.
        assertFalse(BootUiPolicy.mayChangeTo(true, unknown, true));
        assertFalse(BootUiPolicy.mayChangeTo(true, unknown, false));
        assertFalse(BootUiPolicy.mayChangeTo(true, BootUiPolicy.fromPackageManagerState(2), true));
        assertTrue(BootUiPolicy.mayChangeTo(true, BootUiPolicy.fromPackageManagerState(2), false));
    }

    @Test
    public void failedDisableReadbackNeverConfirmsOffAndNeverAutoEnables() {
        BootUiPolicy.BootComponentState failed = BootUiPolicy.fromReadbackFailure();
        assertEquals(BootUiPolicy.BootStatusPresentation.UNKNOWN,
                BootUiPolicy.presentation(failed));
        assertFalse("a failed read-back is not verified OFF",
                BootUiPolicy.verifiedDisabled(failed));
        assertFalse("recovery never auto-enables an unverifiable component",
                BootUiPolicy.onRecovery(failed, true));
        assertFalse("a previously checked switch is ignored on recovery",
                BootUiPolicy.onRecovery(failed, false));
        assertFalse("framework write to OFF is rejected without evidence",
                BootUiPolicy.acceptFrameworkChange(false, failed));
        assertFalse("framework write to ON is rejected without evidence",
                BootUiPolicy.acceptFrameworkChange(true, failed));

        // Only an explicit DISABLED read-back may be presented/confirmed as OFF.
        BootUiPolicy.BootComponentState disabled = BootUiPolicy.fromPackageManagerState(2);
        assertEquals(BootUiPolicy.BootStatusPresentation.OFF_VERIFIED,
                BootUiPolicy.presentation(disabled));
        assertTrue(BootUiPolicy.acceptFrameworkChange(false, disabled));
        assertFalse(BootUiPolicy.acceptFrameworkChange(true, disabled));
        assertEquals(BootUiPolicy.BootStatusPresentation.UNKNOWN,
                BootUiPolicy.presentation(BootUiPolicy.fromPackageManagerState(0)));

        // Quarantine still disables via the shared gate, independently of the read-back.
        assertTrue(BootUiPolicy.disableOnQuarantine(true, true));
        assertTrue(BootUiPolicy.disableOnQuarantine(true, false));
    }

    private static final class CountingListener implements PreferenceIntegrity.Listener {
        int calls;

        @Override
        public void onIntegrityChanged() {
            calls++;
        }
    }
}
