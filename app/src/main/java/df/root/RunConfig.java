// Modified 2026-11-24 (DFRoot GZIF): add immutable run configuration and fail-closed reboot policy. See NOTICE.
package df.root;

import java.util.Objects;

/** One immutable, generation-stamped configuration snapshot. */
public final class RunConfig {
    public enum ExecutionMode {
        MANUAL,
        UNATTENDED_BOOT
    }

    private final String managerPackage;
    private final boolean softReboot;
    private final boolean disableModules;
    private final int softRebootAcknowledgementVersion;
    private final long generation;

    public RunConfig(String managerPackage, boolean softReboot, boolean disableModules,
                     int softRebootAcknowledgementVersion, long generation) {
        if (softRebootAcknowledgementVersion < 0) {
            throw new IllegalArgumentException("acknowledgement version must be non-negative");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.managerPackage = managerPackage;
        this.softReboot = softReboot;
        this.disableModules = disableModules;
        this.softRebootAcknowledgementVersion = softRebootAcknowledgementVersion;
        this.generation = generation;
    }

    public static RunConfig defaults() {
        return new RunConfig(null, false, false, 0, 0);
    }

    public String getManagerPackage() {
        return managerPackage;
    }

    public boolean isSoftReboot() {
        return softReboot;
    }

    public boolean isDisableModules() {
        return disableModules;
    }

    public int getSoftRebootAcknowledgementVersion() {
        return softRebootAcknowledgementVersion;
    }

    public long getGeneration() {
        return generation;
    }

    RunConfig withManagerPackage(String managerPackage) {
        return new RunConfig(managerPackage, softReboot, disableModules,
                softRebootAcknowledgementVersion, generation);
    }

    RunConfig withSoftReboot(boolean enabled) {
        return new RunConfig(managerPackage, enabled, disableModules,
                enabled ? ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION
                        : softRebootAcknowledgementVersion,
                generation);
    }

    RunConfig withDisableModules(boolean disableModules) {
        return new RunConfig(managerPackage, softReboot, disableModules,
                softRebootAcknowledgementVersion, generation);
    }

    RunConfig withoutSoftRebootAcknowledgement() {
        return new RunConfig(managerPackage, false, disableModules, 0, generation);
    }

    RunConfig nextGeneration() {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("configuration generation exhausted");
        }
        return new RunConfig(managerPackage, softReboot, disableModules,
                softRebootAcknowledgementVersion, generation + 1);
    }

    /** Manual soft reboot requires current consent; unattended boot never allows it. */
    public boolean isSoftRebootAllowedFor(ExecutionMode mode) {
        Objects.requireNonNull(mode, "mode");
        return mode == ExecutionMode.MANUAL
                && softReboot
                && softRebootAcknowledgementVersion == ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION;
    }

    /** Enforces the execution-mode policy before any privileged work can begin. */
    public void validateFor(ExecutionMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode == ExecutionMode.UNATTENDED_BOOT) {
            if (!isUnattendedBootAllowed()) {
                throw new IllegalArgumentException(
                        "unattended boot requires soft reboot to be off");
            }
            return;
        }
        if (mode == ExecutionMode.MANUAL) {
            if (softReboot && softRebootAcknowledgementVersion
                    != ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION) {
                throw new IllegalArgumentException(
                        "manual soft reboot requires current acknowledgement");
            }
            return;
        }
        throw new IllegalArgumentException("unsupported execution mode: " + mode);
    }

    /**
     * Boot may proceed only with soft reboot durably off. Hardware validation is a
     * separate release gate, so even current manual consent does not relax this.
     */
    public boolean isUnattendedBootAllowed() {
        return !softReboot;
    }
}
