// Modified 2026-11-24 (DFRoot GZIF): specify the exact documented firmware/kernel gate. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FirmwareGateTest {
    private static final String TARGET_FINGERPRINT =
            "samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys";

    private static FirmwareGate.Inputs supportedInputs() {
        return new FirmwareGate.Inputs(
                "dm3q",
                "SM-S9180",
                "CP2A.260605.016",
                37,
                "2026-10-05",
                TARGET_FINGERPRINT,
                "5.15.197-android13-8-34343818-abS9180ZHU8GZIF");
    }

    @Test
    public void documentedFirmwareIsAccepted() {
        FirmwareGate.Result result = FirmwareGate.validate(supportedInputs());

        assertTrue(result.isSupported());
        assertEquals("supported firmware", result.getReason());
    }

    @Test
    public void fingerprintMustMatchExactly() {
        String[] rejectedFingerprints = {
                "samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys-extra",
                "prefix-samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys",
                "samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:userdebug/release-keys",
                "samsung/dm3qzhx/dm3q:17/CP2A.260605.017/S9180ZHU8GZIF:user/release-keys",
                "samsung/dm3qzhx/dm3q:16/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys",
                "samsung/dm2qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys",
                "prefix/S9180ZHU8GZIF/suffix",
                "S9180ZHU8GZIF",
                null
        };
        for (String fingerprint : rejectedFingerprints) {
            assertRejected(inputsWithFingerprint(fingerprint));
        }
    }

    @Test
    public void differentDeviceIsRejected() {
        assertRejected(inputsWithDevice("dm2q"));
    }

    @Test
    public void differentModelIsRejected() {
        assertRejected(inputsWithModel("SM-S918B"));
    }

    @Test
    public void differentBuildIdIsRejected() {
        assertRejected(inputsWithBuildId("CP2A.260605.017"));
    }

    @Test
    public void differentSdkIsRejected() {
        assertRejected(inputsWithSdk(36));
        assertRejected(inputsWithSdk(38));
    }

    @Test
    public void differentSecurityPatchIsRejected() {
        assertRejected(inputsWithSecurityPatch("2026-09-05"));
    }

    @Test
    public void fingerprintWithoutTheDocumentedTokenIsRejected() {
        assertRejected(inputsWithFingerprint("samsung/dm3qxx/dm3q:16/BP2A.260605.016/OTHER:user/release-keys"));
        assertRejected(inputsWithFingerprint(null));
    }

    @Test
    public void differentKernelIsRejected() {
        assertRejected(inputsWithKernel("5.15.197-android13-8-34343818-abS9180ZHU8GZI1"));
        assertRejected(inputsWithKernel("5.15.197-android13-8"));
        assertRejected(inputsWithKernel(null));
    }

    @Test
    public void missingInputIsRejected() {
        FirmwareGate.Result result = FirmwareGate.validate(null);

        assertFalse(result.isSupported());
        assertEquals("firmware information unavailable", result.getReason());
    }

    private static FirmwareGate.Inputs inputsWithDevice(String value) {
        return new FirmwareGate.Inputs(value, "SM-S9180", "CP2A.260605.016", 37,
                "2026-10-05", supportedInputs().getFingerprint(), supportedInputs().getKernelVersion());
    }

    private static FirmwareGate.Inputs inputsWithModel(String value) {
        return new FirmwareGate.Inputs("dm3q", value, "CP2A.260605.016", 37,
                "2026-10-05", supportedInputs().getFingerprint(), supportedInputs().getKernelVersion());
    }

    private static FirmwareGate.Inputs inputsWithBuildId(String value) {
        return new FirmwareGate.Inputs("dm3q", "SM-S9180", value, 37,
                "2026-10-05", supportedInputs().getFingerprint(), supportedInputs().getKernelVersion());
    }

    private static FirmwareGate.Inputs inputsWithSdk(int value) {
        return new FirmwareGate.Inputs("dm3q", "SM-S9180", "CP2A.260605.016", value,
                "2026-10-05", supportedInputs().getFingerprint(), supportedInputs().getKernelVersion());
    }

    private static FirmwareGate.Inputs inputsWithSecurityPatch(String value) {
        return new FirmwareGate.Inputs("dm3q", "SM-S9180", "CP2A.260605.016", 37,
                value, supportedInputs().getFingerprint(), supportedInputs().getKernelVersion());
    }

    private static FirmwareGate.Inputs inputsWithFingerprint(String value) {
        return new FirmwareGate.Inputs("dm3q", "SM-S9180", "CP2A.260605.016", 37,
                "2026-10-05", value, supportedInputs().getKernelVersion());
    }

    private static FirmwareGate.Inputs inputsWithKernel(String value) {
        return new FirmwareGate.Inputs("dm3q", "SM-S9180", "CP2A.260605.016", 37,
                "2026-10-05", supportedInputs().getFingerprint(), value);
    }

    private static void assertRejected(FirmwareGate.Inputs inputs) {
        FirmwareGate.Result result = FirmwareGate.validate(inputs);

        assertFalse(result.isSupported());
        assertTrue(result.getReason().startsWith("unsupported firmware:"));
    }
}
