// Modified 2026-11-25 (DFRoot GZIF round-4 PR #3 phase 3): pure UI policy for
// truthful boot switches and lifecycle-safe quarantine handling (P2-10). See NOTICE.
package df.root;

/**
 * Host-testable boot UI policy. No Android imports: it only describes what the
 * UI must display or permit, and never enables a feature on its own.
 */
public final class BootUiPolicy {

    private BootUiPolicy() {}

    /** The switch must mirror the actual component state, never a cached hope. */
    public static boolean desiredChecked(boolean actualComponentEnabled) {
        return actualComponentEnabled;
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
     * The user may always turn automatic startup OFF. Turning it ON is refused
     * while preference state is uncertain.
     */
    public static boolean mayChangeTo(boolean value, boolean quarantined) {
        return !quarantined || !value;
    }

    /**
     * Recovery never auto-enables anything: the switch follows the actual
     * component state, even if it used to be checked before the quarantine.
     */
    public static boolean onRecovery(boolean actualComponentEnabled, boolean previouslyChecked) {
        return desiredChecked(actualComponentEnabled);
    }

    /**
     * TwoStatePreference applies the requested value after a listener returns
     * true. Accept that framework write ONLY when the actual component state
     * already equals the request; otherwise return false so our explicit
     * setChecked(actual) is not overwritten and the UI cannot claim OFF while
     * BootReceiver is still ENABLED.
     */
    public static boolean acceptFrameworkChange(boolean requestedValue,
                                                 boolean actualComponentEnabled) {
        return actualComponentEnabled == requestedValue;
    }
}
