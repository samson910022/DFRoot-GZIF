// Modified 2026-11-24 (DFRoot GZIF): map native exit codes without Java/native collisions. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): rc=1 now covers run-config/bootstrap rather than preference XML. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): native rc=7 is indeterminate and never a success. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): label rc=7 explicitly; unknown exits keep their honest reason. See NOTICE.
// Modified 2026-11-27 (R9.1): mirror the authoritative status_protocol.h rc set,
// including rc=5 (reboot skipped) and the additive recovery-failed bit. See NOTICE.
package df.root;

/** Pure mapper for the native process exit-code contract. */
public final class NativeResultMapper {
    private NativeResultMapper() {}

    /**
     * Maps one native exit code to the typed Java contract. The native rc set is
     * defined by status_protocol.h / StatusRc; the recovery bit is additive and
     * is folded into the returned code so every base outcome can still surface
     * the "restore verification failed" warning in the UI.
     */
    public static RunResult map(int nativeCode) {
        int base = StatusRc.base(nativeCode);
        // A negative exit is an unrecognized Java-style code, not a native rc
        // with the additive bit (every negative int has bit 64 set in Java).
        boolean recoveryFailed = nativeCode >= 0 && StatusRc.recoveryFailed(nativeCode);

        int code;
        RunResult.Type type;
        RunResult.Detail detail;
        String reason;
        switch (base) {
            case StatusRc.RC_ROOT_ONLY:
                code = ExploitRunner.RESULT_OK;
                type = RunResult.Type.SUCCESS;
                detail = RunResult.Detail.LATE_LOAD_COMPLETED;
                reason = null;
                break;
            case StatusRc.RC_ERROR:
                // Broad by contract: run-config/bootstrap, modules, staging, or late-load failed.
                code = ExploitRunner.RESULT_KSUD_ERROR;
                type = RunResult.Type.BOOTSTRAP_ERROR;
                detail = RunResult.Detail.NATIVE_EXIT;
                reason = null;
                break;
            case 2:
                // Native setup/marker timeout is translated away from Java precheck code 2.
                code = ExploitRunner.RESULT_NATIVE_SETUP_FAILED;
                type = RunResult.Type.NATIVE_SETUP_FAILED;
                detail = RunResult.Detail.NATIVE_EXIT;
                reason = null;
                break;
            case StatusRc.RC_PATCH_FAILED:
                code = ExploitRunner.RESULT_PATCH_FAILED;
                type = RunResult.Type.PATCH_FAILED;
                detail = RunResult.Detail.NATIVE_EXIT;
                reason = null;
                break;
            case StatusRc.RC_REBOOT_REQUESTED:
                code = ExploitRunner.RESULT_SOFT_REBOOT_PENDING;
                type = RunResult.Type.SOFT_REBOOT_PENDING;
                detail = RunResult.Detail.NATIVE_EXIT;
                reason = null;
                break;
            case StatusRc.RC_REBOOT_SKIPPED:
                // DF_RC_REBOOT_SKIPPED is a KNOWN outcome: no reboot was issued
                // because late-load absence could not be confirmed. Use the
                // soft-reboot typed family, but never claim a command ran.
                code = ExploitRunner.RESULT_SOFT_REBOOT_PENDING;
                type = RunResult.Type.SOFT_REBOOT_PENDING;
                detail = RunResult.Detail.NATIVE_EXIT;
                reason = "native rc=5 soft reboot skipped (late-load not confirmed absent)";
                break;
            case StatusRc.RC_REBOOT_FAILED:
                code = ExploitRunner.RESULT_SOFT_REBOOT_FAILED;
                type = RunResult.Type.SOFT_REBOOT_FAILED;
                detail = RunResult.Detail.NATIVE_EXIT;
                reason = null;
                break;
            case StatusRc.RC_ATTEMPT_UNKNOWN:
                // Unconfirmed terminal publication: the effect of the run is unknown.
                // Keep the stable RESULT_INDETERMINATE code; never map to success.
                code = ExploitRunner.RESULT_INDETERMINATE;
                type = RunResult.Type.INDETERMINATE;
                detail = RunResult.Detail.NATIVE_EXIT;
                reason = "native rc=7 indeterminate publication";
                break;
            default:
                code = ExploitRunner.RESULT_UNKNOWN;
                type = RunResult.Type.UNKNOWN;
                detail = RunResult.Detail.NATIVE_EXIT;
                reason = "native exit code " + nativeCode;
                break;
        }

        if (recoveryFailed) {
            // Preserve the additive bit on the stable code so the UI can warn
            // for EVERY base outcome, including success and indeterminate.
            code |= StatusRc.RECOVERY_FAILED_BIT;
            reason = (reason == null ? "" : reason + "; ")
                    + "native rc=" + nativeCode + " recovery verification failed (bit 64)";
        }
        return new RunResult(code, type, detail, reason);
    }
}
