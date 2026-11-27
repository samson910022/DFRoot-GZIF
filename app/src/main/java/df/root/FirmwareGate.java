// Modified 2026-11-24 (DFRoot GZIF): add the exact documented firmware/kernel fail-closed gate. See NOTICE.
package df.root;

/** Pure-Java gate for the only firmware/kernel combination documented for this fork. */
public final class FirmwareGate {
    public static final String TARGET_DEVICE = "dm3q";
    public static final String TARGET_MODEL = "SM-S9180";
    public static final String TARGET_BUILD_ID = "CP2A.260605.016";
    public static final int TARGET_SDK = 37;
    public static final String TARGET_SECURITY_PATCH = "2026-10-05";
    public static final String TARGET_FINGERPRINT =
            "samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys";
    public static final String TARGET_KERNEL =
            "5.15.197-android13-8-34343818-abS9180ZHU8GZIF";

    private FirmwareGate() {}

    /** Explicit inputs keep this gate independent of Android runtime state. */
    public static final class Inputs {
        private final String device;
        private final String model;
        private final String buildId;
        private final int sdkInt;
        private final String securityPatch;
        private final String fingerprint;
        private final String kernelVersion;

        public Inputs(String device, String model, String buildId, int sdkInt,
                      String securityPatch, String fingerprint, String kernelVersion) {
            this.device = device;
            this.model = model;
            this.buildId = buildId;
            this.sdkInt = sdkInt;
            this.securityPatch = securityPatch;
            this.fingerprint = fingerprint;
            this.kernelVersion = kernelVersion;
        }

        public String getDevice() { return device; }
        public String getModel() { return model; }
        public String getBuildId() { return buildId; }
        public int getSdkInt() { return sdkInt; }
        public String getSecurityPatch() { return securityPatch; }
        public String getFingerprint() { return fingerprint; }
        public String getKernelVersion() { return kernelVersion; }
    }

    public static final class Result {
        private final boolean supported;
        private final String reason;

        private Result(boolean supported, String reason) {
            this.supported = supported;
            this.reason = reason;
        }

        public boolean isSupported() { return supported; }
        public String getReason() { return reason; }
    }

    private static final Result SUPPORTED = new Result(true, "supported firmware");

    public static Result validate(Inputs inputs) {
        if (inputs == null) {
            return new Result(false, "firmware information unavailable");
        }
        if (!TARGET_DEVICE.equals(inputs.device)) {
            return mismatch("device");
        }
        if (!TARGET_MODEL.equals(inputs.model)) {
            return mismatch("model");
        }
        if (!TARGET_BUILD_ID.equals(inputs.buildId)) {
            return mismatch("Build.ID");
        }
        if (inputs.sdkInt != TARGET_SDK) {
            return mismatch("SDK");
        }
        if (!TARGET_SECURITY_PATCH.equals(inputs.securityPatch)) {
            return mismatch("security patch level");
        }
        // Fingerprint is deliberately treated as opaque. Only the complete
        // documented value is accepted; a shared token is not sufficient.
        if (!TARGET_FINGERPRINT.equals(inputs.fingerprint)) {
            return new Result(false,
                    "unsupported firmware: fingerprint does not match documented target");
        }
        if (!TARGET_KERNEL.equals(inputs.kernelVersion)) {
            return mismatch("kernel");
        }
        return SUPPORTED;
    }

    private static Result mismatch(String field) {
        return new Result(false, "unsupported firmware: " + field + " does not match documented target");
    }
}
