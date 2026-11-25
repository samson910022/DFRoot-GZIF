// Modified 2026-11-25 (DFRoot GZIF round-4 PR #3 phase 3): pure UI policy for
// truthful boot switches and lifecycle-safe quarantine handling (P2-10). See NOTICE.
// Round-5: state observation is tri-state; a failed/DEFAULT readback is UNKNOWN,
// never evidence that automatic startup was disabled. See NOTICE.
// Round-6 (PR #3): UNKNOWN has a distinct, persistent presentation and an
// always-available explicit OFF request; the unchecked thumb is ONLY a position,
// never a status claim. See NOTICE.
package df.root;

/**
 * Host-testable boot UI policy. No Android imports: it only describes what the
 * UI must display or permit, and never enables a feature on its own.
 *
 * PackageManager's integer state is mapped at the boundary by
 * {@link #fromPackageManagerState(int)} so this class stays usable in JVM tests.
 */
public final class BootUiPolicy {

    private BootUiPolicy() {}

    /** Observation of the component setting, not a cached hope. */
    public enum BootComponentState {
        ENABLED, DISABLED, UNKNOWN
    }

    /**
     * Map PackageManager's COMPONENT_ENABLED_STATE_* integer. Only an explicit
     * ENABLED or DISABLED is evidence of anything: DEFAULT, unknown constants
     * and failed observation are all UNKNOWN (never "OFF").
     */
    public static BootComponentState fromPackageManagerState(int pmState) {
        // 1 = COMPONENT_ENABLED_STATE_ENABLED, 2 = ..._DISABLED, 0 = ..._DEFAULT.
        if (pmState == 1) return BootComponentState.ENABLED;
        if (pmState == 2) return BootComponentState.DISABLED;
        return BootComponentState.UNKNOWN;
    }

    /** A PackageManager exception or missing context: state is unavailable. */
    public static BootComponentState fromReadbackFailure() {
        return BootComponentState.UNKNOWN;
    }

    /**
     * How the boot state must be presented to the user. This is a display
     * category derived from an observation: UNKNOWN is never a claim that
     * automatic startup was disabled.
     */
    public enum BootStatusPresentation {
        ON, OFF_VERIFIED, UNKNOWN
    }

    /** Map an observation to its honest presentation category. */
    public static BootStatusPresentation presentation(BootComponentState state) {
        if (state == BootComponentState.ENABLED) return BootStatusPresentation.ON;
        if (state == BootComponentState.DISABLED) return BootStatusPresentation.OFF_VERIFIED;
        return BootStatusPresentation.UNKNOWN;
    }

    /** True only when the component setting could not be observed at all. */
    public static boolean isUnverifiable(BootComponentState state) {
        return state == BootComponentState.UNKNOWN;
    }

    /**
     * An explicit OFF is always permitted. Requesting it never auto-enables
     * anything and never depends on a prior observation having succeeded.
     */
    public static boolean mayRequestDisable() {
        return true;
    }

    /**
     * ONLY the switch thumb position: UNKNOWN is shown as an unchecked thumb so
     * it never implies ON, but this is NOT a status claim that automatic startup
     * is disabled. Use {@link #presentation(BootComponentState)} together with
     * the persistent status row for the honest status text.
     */
    public static boolean desiredChecked(BootComponentState state) {
        return state == BootComponentState.ENABLED;
    }

    /** Convenience for callers that already reduced the observation to a boolean. */
    public static boolean desiredChecked(boolean actualComponentEnabled) {
        return actualComponentEnabled;
    }

    /**
     * True only for an actually observed DISABLED component. An unverifiable
     * readback must never be displayed or recorded as "auto-startup disabled".
     */
    public static boolean verifiedDisabled(BootComponentState state) {
        return state == BootComponentState.DISABLED;
    }

    /**
     * On quarantine the component is disabled through the shared durable gate
     * regardless of marker health. A healthy marker is not an oracle that makes
     * an uncertain commit safe.
     */
    public static boolean disableOnQuarantine(boolean quarantined, boolean markerActive) {
        return quarantined;
    }

    /**
     * The user may always try to turn automatic startup OFF, even when the
     * current state is UNKNOWN (the switch stays enabled). Turning it ON is
     * refused while preferences are quarantined or the state is unverifiable.
     */
    public static boolean mayChangeTo(boolean value, BootComponentState state,
                                      boolean quarantined) {
        if (!value) return true;
        if (state == BootComponentState.UNKNOWN) return false;
        return !quarantined;
    }

    /** Boolean convenience for pure quarantine-only checks. */
    public static boolean mayChangeTo(boolean value, boolean quarantined) {
        return !quarantined || !value;
    }

    /**
     * Recovery never auto-enables anything: the switch follows the actual
     * component state, even if it used to be checked before the quarantine.
     */
    public static boolean onRecovery(BootComponentState state, boolean previouslyChecked) {
        return desiredChecked(state);
    }

    public static boolean onRecovery(boolean actualComponentEnabled, boolean previouslyChecked) {
        return desiredChecked(actualComponentEnabled);
    }

    /**
     * TwoStatePreference applies the requested value after a listener returns
     * true. Accept that framework write ONLY when the actual component state
     * was observed and already equals the request. UNKNOWN rejects every
     * framework write so the switch cannot claim OFF (or ON) without evidence.
     */
    public static boolean acceptFrameworkChange(boolean requestedValue,
                                                 BootComponentState actual) {
        if (actual == BootComponentState.UNKNOWN) return false;
        return desiredChecked(actual) == requestedValue;
    }

    public static boolean acceptFrameworkChange(boolean requestedValue,
                                                 boolean actualComponentEnabled) {
        return actualComponentEnabled == requestedValue;
    }
}
