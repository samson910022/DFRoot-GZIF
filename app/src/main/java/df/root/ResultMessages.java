// Modified 2026-11-24 (DFRoot GZIF): extract pure typed-result user messages from the Activity. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): relabel already-hooked success with unverified root status. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): remove ambiguous bare SUCCESS from final user messages; typed contract unchanged. See NOTICE.
// Modified 2026-11-26 (DFRoot GZIF, R4.9): remove the unverified "root active" claim from the soft-reboot message; typed code 4 unchanged. See NOTICE.
package df.root;

/** Pure user-facing mapping for typed run outcomes. */
final class ResultMessages {
    private static final String SKIPPED_REBOOT_MARKER =
            "native rc=5 soft reboot skipped";

    private ResultMessages() {}

    /** The mapper labels a skipped reboot with this exact reason prefix. */
    private static boolean isSkippedReboot(RunResult result) {
        String reason = result.getReason();
        return reason != null && reason.startsWith(SKIPPED_REBOOT_MARKER);
    }

    static String message(RunResult result) {
        switch (result.getType()) {
            case SUCCESS:
                // The typed SUCCESS code is preserved, but the user message never
                // claims root was verified: /dev/df markers are progress only.
                return result.getDetail() == RunResult.Detail.ALREADY_HOOKED
                        ? "DFRoot: already attempted/hooked; root status unverified"
                        : "DFRoot: run completed — verify root status yourself";
            case BOOTSTRAP_ERROR:          return "DFRoot: Error - bootstrap/late-load failed";
            case PRECHECK_FAILED:          return "DFRoot: Error - precheck failed (see output)";
            case PATCH_FAILED:             return "DFRoot: Error - failed to patch files";
            // A soft-reboot command returning is not independent evidence of root state.
            // Native rc=5 shares this typed family but NO command was issued, so
            // its reason must never be rendered as "was requested".
            case SOFT_REBOOT_PENDING:      return isSkippedReboot(result)
                    ? "DFRoot: soft reboot skipped (late-load not confirmed absent; "
                      + "root status unverified); verify root status yourself"
                    : "DFRoot: soft-reboot was requested (root status unverified); verify root status yourself";
            case NATIVE_SETUP_FAILED:      return "DFRoot: Error - native setup/timeout (see logcat & dmesg)";
            case SOFT_REBOOT_FAILED:       return "DFRoot: Error - soft-reboot command failed";
            case UNKNOWN:                  return "DFRoot: Error - unknown result (" + result.getReason() + ")";
            case INDETERMINATE:            return "DFRoot: Error - outcome indeterminate (see output)";
            default:                       return "DFRoot: Error - unrecognized result";
        }
    }
}
