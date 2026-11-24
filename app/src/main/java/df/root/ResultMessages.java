// Modified 2026-11-24 (DFRoot GZIF): extract pure typed-result user messages from the Activity. See NOTICE.
package df.root;

/** Pure user-facing mapping for typed run outcomes. */
final class ResultMessages {
    private ResultMessages() {}

    static String message(RunResult result) {
        switch (result.getType()) {
            case SUCCESS:
                return result.getDetail() == RunResult.Detail.ALREADY_HOOKED
                        ? "DFRoot: SUCCESS (already hooked)" : "DFRoot: SUCCESS";
            case BOOTSTRAP_ERROR:          return "DFRoot: Error - bootstrap/late-load failed";
            case PRECHECK_FAILED:          return "DFRoot: Error - precheck failed (see output)";
            case PATCH_FAILED:             return "DFRoot: Error - failed to patch files";
            case SOFT_REBOOT_PENDING:      return "DFRoot: root active — device may soft-reboot shortly";
            case NATIVE_SETUP_FAILED:      return "DFRoot: Error - native setup/timeout (see logcat & dmesg)";
            case SOFT_REBOOT_FAILED:       return "DFRoot: Error - soft-reboot command failed";
            case UNKNOWN:                  return "DFRoot: Error - unknown result (" + result.getReason() + ")";
            case INDETERMINATE:            return "DFRoot: Error - outcome indeterminate (see output)";
            default:                       return "DFRoot: Error - unrecognized result";
        }
    }
}
