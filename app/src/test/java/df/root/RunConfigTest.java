// Modified 2026-11-24 (DFRoot GZIF): lock down immutable configuration and boot policy. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Modifier;

public class RunConfigTest {
    @Test
    public void everySnapshotFieldIsFinalAndHasNoMutator() {
        for (java.lang.reflect.Field field : RunConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
        }
        for (java.lang.reflect.Method method : RunConfig.class.getDeclaredMethods()) {
            assertFalse(method.getName() + " is a mutator",
                    method.getName().startsWith("set") && method.getParameterCount() == 1);
        }
    }

    @Test
    public void derivedSnapshotsDoNotChangeTheOriginal() {
        RunConfig original = new RunConfig(SuManager.CLASSIC.getPackageName(),
                false, false, 0, 7);

        RunConfig managerChanged = original.withManagerPackage(SuManager.NEXT.getPackageName());
        RunConfig modulesChanged = original.withDisableModules(true);
        RunConfig rebootChanged = original.withSoftReboot(true);

        assertEquals(SuManager.CLASSIC.getPackageName(), original.getManagerPackage());
        assertFalse(original.isSoftReboot());
        assertFalse(original.isDisableModules());
        assertEquals(0, original.getSoftRebootAcknowledgementVersion());
        assertEquals(7, original.getGeneration());
        assertEquals(SuManager.NEXT.getPackageName(), managerChanged.getManagerPackage());
        assertTrue(modulesChanged.isDisableModules());
        assertTrue(rebootChanged.isSoftReboot());
        assertEquals(ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION,
                rebootChanged.getSoftRebootAcknowledgementVersion());
    }

    @Test
    public void disablingSoftRebootPreservesTheRecordedConsentVersion() {
        RunConfig enabled = new RunConfig(null, true, false,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 3);

        RunConfig disabled = enabled.withSoftReboot(false);

        assertFalse(disabled.isSoftReboot());
        assertEquals(ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION,
                disabled.getSoftRebootAcknowledgementVersion());
        assertTrue(enabled.isSoftReboot());
    }

    @Test
    public void freshInstallDefaultsAreSafeOff() {
        RunConfig defaults = RunConfig.defaults();

        assertEquals(null, defaults.getManagerPackage());
        assertFalse(defaults.isSoftReboot());
        assertFalse(defaults.isDisableModules());
        assertEquals(0, defaults.getSoftRebootAcknowledgementVersion());
        assertEquals(0, defaults.getGeneration());
    }

    @Test
    public void onlyCurrentVersionedConsentAllowsManualSoftReboot() {
        RunConfig current = new RunConfig(null, true, false,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 1);
        RunConfig legacy = new RunConfig(null, true, false, 0, 1);

        assertTrue(current.isSoftRebootAllowedFor(RunConfig.ExecutionMode.MANUAL));
        assertFalse(legacy.isSoftRebootAllowedFor(RunConfig.ExecutionMode.MANUAL));
        assertFalse(current.isSoftRebootAllowedFor(RunConfig.ExecutionMode.UNATTENDED_BOOT));
        assertFalse(legacy.isSoftRebootAllowedFor(RunConfig.ExecutionMode.UNATTENDED_BOOT));
    }

    @Test
    public void unattendedBootRejectsAnySoftRebootConfiguration() {
        RunConfig safe = RunConfig.defaults().withManagerPackage(SuManager.NEXT.getPackageName());
        RunConfig manuallyAcknowledged = safe.withSoftReboot(true);

        assertTrue(safe.isUnattendedBootAllowed());
        assertFalse(manuallyAcknowledged.isUnattendedBootAllowed());
    }

    @Test
    public void executionPolicyValidationRejectsInvalidModeAndLegacyManualConsent() {
        RunConfig safe = RunConfig.defaults();
        RunConfig legacyManual = new RunConfig(null, true, false, 0, 1);
        RunConfig currentManual = legacyManual.withSoftReboot(true);

        safe.validateFor(RunConfig.ExecutionMode.MANUAL);
        safe.validateFor(RunConfig.ExecutionMode.UNATTENDED_BOOT);
        currentManual.validateFor(RunConfig.ExecutionMode.MANUAL);
        assertThrows(IllegalArgumentException.class,
                () -> legacyManual.validateFor(RunConfig.ExecutionMode.MANUAL));
        assertThrows(IllegalArgumentException.class,
                () -> currentManual.validateFor(RunConfig.ExecutionMode.UNATTENDED_BOOT));
        assertThrows(NullPointerException.class, () -> safe.validateFor(null));
    }

    @Test
    public void invalidGenerationsAndAcknowledgementVersionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunConfig(null, false, false, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new RunConfig(null, false, false, -1, 0));
    }
}
