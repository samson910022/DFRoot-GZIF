// Modified 2026-11-24 (DFRoot GZIF): map native exit codes without Java/native collisions. See NOTICE.
package df.root;

/** Pure mapper for the native process exit-code contract. */
public final class NativeResultMapper {
    private NativeResultMapper() {}

    public static RunResult map(int nativeCode) {
        switch (nativeCode) {
            case 0:
                return new RunResult(ExploitRunner.RESULT_OK, RunResult.Type.SUCCESS,
                        RunResult.Detail.LATE_LOAD_COMPLETED, null);
            case 1:
                // Broad by contract: preferences, modules, staging, or late-load failed.
                return new RunResult(ExploitRunner.RESULT_KSUD_ERROR,
                        RunResult.Type.BOOTSTRAP_ERROR, RunResult.Detail.NATIVE_EXIT, null);
            case 2:
                // Native setup/marker timeout is translated away from Java precheck code 2.
                return new RunResult(ExploitRunner.RESULT_NATIVE_SETUP_FAILED,
                        RunResult.Type.NATIVE_SETUP_FAILED, RunResult.Detail.NATIVE_EXIT, null);
            case 3:
                return new RunResult(ExploitRunner.RESULT_PATCH_FAILED,
                        RunResult.Type.PATCH_FAILED, RunResult.Detail.NATIVE_EXIT, null);
            case 4:
                return new RunResult(ExploitRunner.RESULT_SOFT_REBOOT_PENDING,
                        RunResult.Type.SOFT_REBOOT_PENDING, RunResult.Detail.NATIVE_EXIT, null);
            case 6:
                return new RunResult(ExploitRunner.RESULT_SOFT_REBOOT_FAILED,
                        RunResult.Type.SOFT_REBOOT_FAILED, RunResult.Detail.NATIVE_EXIT, null);
            default:
                return new RunResult(ExploitRunner.RESULT_UNKNOWN, RunResult.Type.UNKNOWN,
                        RunResult.Detail.NATIVE_EXIT, "native exit code " + nativeCode);
        }
    }
}
