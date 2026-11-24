// Modified 2026-11-24 (DFRoot GZIF): add an immutable typed run result with explicit origin and process-uncertainty detail. See NOTICE.
package df.root;

/** Immutable result contract shared by native mapping, preflight, and callers. */
public final class RunResult {
    public enum Type {
        SUCCESS,
        BOOTSTRAP_ERROR,
        PRECHECK_FAILED,
        PATCH_FAILED,
        SOFT_REBOOT_PENDING,
        NATIVE_SETUP_FAILED,
        SOFT_REBOOT_FAILED,
        UNKNOWN,
        INDETERMINATE
    }

    public enum Detail {
        LATE_LOAD_COMPLETED,
        ALREADY_HOOKED,
        NATIVE_EXIT,
        PREFLIGHT_REJECTION,
        EXCEPTION,
        PROCESS_TIMEOUT,
        PROCESS_INTERRUPTION
    }

    private final int code;
    private final Type type;
    private final Detail detail;
    private final String reason;

    RunResult(int code, Type type, Detail detail, String reason) {
        this.code = code;
        this.type = type;
        this.detail = detail;
        this.reason = reason;
    }

    /** Stable integer code retained for existing callers. */
    public int getCode() {
        return code;
    }

    public Type getType() {
        return type;
    }

    public Detail getDetail() {
        return detail;
    }

    /** Human-readable diagnostic, or null when no diagnostic is needed. */
    public String getReason() {
        return reason;
    }

    public boolean isSuccess() {
        return type == Type.SUCCESS;
    }

    public static RunResult alreadyHooked() {
        return new RunResult(ExploitRunner.RESULT_OK, Type.SUCCESS,
                Detail.ALREADY_HOOKED, null);
    }

    public static RunResult precheckFailed(String reason) {
        return new RunResult(ExploitRunner.RESULT_PRECHECK_FAILED, Type.PRECHECK_FAILED,
                Detail.PREFLIGHT_REJECTION, reason);
    }

    public static RunResult exception(Throwable error) {
        String message = error.getMessage();
        String reason = error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
        return new RunResult(ExploitRunner.RESULT_INDETERMINATE, Type.INDETERMINATE,
                Detail.EXCEPTION, reason);
    }

    /** A started child exceeded its observation bound; its effect remains unknown until exit. */
    public static RunResult processTimeout() {
        return new RunResult(ExploitRunner.RESULT_INDETERMINATE, Type.INDETERMINATE,
                Detail.PROCESS_TIMEOUT,
                "child process timed out; outcome remains indeterminate until actual exit");
    }

    /** The supervising thread was interrupted; the child continues and remains owned until exit. */
    public static RunResult processInterrupted() {
        return new RunResult(ExploitRunner.RESULT_INDETERMINATE, Type.INDETERMINATE,
                Detail.PROCESS_INTERRUPTION,
                "child supervision was interrupted; outcome remains indeterminate until actual exit");
    }
}
