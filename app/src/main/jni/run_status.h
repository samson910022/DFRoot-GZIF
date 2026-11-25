/* Modified 2026-11-25 (DFRoot GZIF): define the run-scoped terminal marker protocol. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): share the progress-marker table and composed deadlines across both native binaries. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): publish markers atomically and reserve rc=7 for indeterminate outcomes. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): distinguish writer exit rc7 from the reader rc7 channel and document the pre-link timeout path. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): share bounded, non-killing child waits and map every timeout to rc7. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): reader expiry is rc7 and the late-load command/poll share one budget. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): consume the reserved pre-admission failure as an explicit indeterminate reader outcome. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): map a terminal classifier observation failure to rc7 through one shared helper. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): scope the publication guarantee to what is provable across the post-link observation window. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): contradictory terminal markers map to indeterminate rc7, never definitive rc1. See NOTICE. */
#ifndef DFROOT_RUN_STATUS_H
#define DFROOT_RUN_STATUS_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#include "run_config_codec.h"

/* Production marker root. Host tests inject a harmless temporary directory. */
#define RUN_STATUS_ROOT_DEFAULT "/dev"
/* Safe reserved identity for terminal errors raised before the admitted
 * snapshot (and therefore its operation ID) is available. */
#define RUN_STATUS_PRE_ADMISSION_ID "bootstrap-pre-admission"
#define RUN_STATUS_ROOT_MAX 256
#define RUN_STATUS_MARKER_NAME_MAX (RUN_CONFIG_OPERATION_ID_MAX + 64)
#define RUN_STATUS_PATH_MAX (RUN_STATUS_ROOT_MAX + RUN_STATUS_MARKER_NAME_MAX + 4)
#define RUN_STATUS_PAYLOAD_MAX 256
#define RUN_STATUS_DIAGNOSTIC_MAX 192

/* Terminal codes. The Java NativeResultMapper consumes exactly these values.
 * rc=7 is reserved and has two DISTINCT roles that must not be conflated:
 *   (a) the writer's process exit code when a publication fails or cannot be
 *       confirmed (run_status_publication_exit_code), and
 *   (b) a run-scoped rc=7 marker, the reader's dedicated indeterminate channel.
 * A PRE-LINK publication failure leaves NO marker at all, so the reader cannot
 * propagate a writer rc=7 to itself: it surfaces its own read-deadline expiry
 * as rc=7 (RUN_STATUS_TERMINAL_INDETERMINATE), never SUCCESS or a definitive
 * failure, because privileged children may still be alive. rc=2 remains only
 * for setup() failures before any operation starts. A POST-LINK unconfirmed
 * publication publishes the rc=7 marker, which the reader maps to
 * RESULT_INDETERMINATE. Once that rc=7 marker exists, neither channel reports
 * SUCCESS; the unavoidable post-link/pre-rc7 observation window is documented
 * on run_status_write() below. */
enum run_status_code {
    RUN_STATUS_TERMINAL_SUCCESS = 0,
    RUN_STATUS_TERMINAL_ERROR = 1,
    RUN_STATUS_TERMINAL_SOFT_REBOOT_OK = 4,
    RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL = 6,
    RUN_STATUS_TERMINAL_INDETERMINATE = 7
};

/* Shared timing budget (ms). The late-load command wait and the subsequent
 * late_load_running() poll share ONE RUN_STATUS_LATE_LOAD_WAIT_MS budget: the
 * poll receives only the time the command left, never a fresh full limit. The
 * true worst-case writer duration is therefore LATE_LOAD_WAIT_MS +
 * SOFT_REBOOT_WAIT_MS, which the reader budget must cover so it can never give
 * up before the writer has had a chance to publish a run-scoped marker. */
#define RUN_STATUS_LATE_LOAD_WAIT_MS 60000
#define RUN_STATUS_SOFT_REBOOT_WAIT_MS 30000
#define RUN_STATUS_TERMINAL_READ_WAIT_MS 100000

enum run_status_verdict {
    RUN_STATUS_VERDICT_NONE = 0,      /* no terminal marker for this operation */
    RUN_STATUS_VERDICT_TERMINAL = 1,  /* exactly one distinct terminal code */
    RUN_STATUS_VERDICT_CONFLICT = 2   /* more than one distinct terminal code */
};

struct run_status_result {
    enum run_status_verdict verdict;
    int code;                       /* -1 for NONE; single code; 7 (indeterminate) for CONFLICT */
    char diagnostic[RUN_STATUS_DIAGNOSTIC_MAX];
};

/* Reader decision: the admitted operation's classification is authoritative.
 * A reserved pre-admission diagnostic can only raise UNCONFIRMED when no
 * terminal marker exists for the admitted operation; it is never a terminal
 * result (and never a success) for that operation. */
enum run_status_outcome {
    RUN_STATUS_OUTCOME_NONE = 0,         /* no decision yet: keep polling */
    RUN_STATUS_OUTCOME_TERMINAL = 1,     /* admitted marker (or conflict) won */
    RUN_STATUS_OUTCOME_UNCONFIRMED = 2   /* pre-admission failure, no admitted marker */
};

struct run_status_decision {
    enum run_status_outcome outcome;
    int code;                       /* -1 for NONE; admitted code; rc7 if UNCONFIRMED */
    char diagnostic[RUN_STATUS_DIAGNOSTIC_MAX];
};

/*
 * Bind the protocol to one admitted run. The operation ID is validated with
 * the shared run-config codec policy, so an unsafe ID can never reach a path
 * or a payload. Both writer (bootstrap) and reader (exp) call this with the
 * operation_id from the same admitted snapshot.
 */
int run_status_configure(const char *root, const char *operation_id);
const char *run_status_last_error(void);

/* Checked, exclusive terminal marker publication. The complete payload is
 * first written to an unpredictable same-directory temp file (O_EXCL,
 * O_NOFOLLOW, O_CLOEXEC, regular-file type, full EINTR-safe write, fsync,
 * fchmod 0644 while still open, checked close). Only then is it linked
 * atomically to the run-scoped final path, the temp link removed, and the
 * directory fsynced. Returns 0 only when every step succeeded; any PRE-LINK
 * failure leaves no final path and no temp debris.
 *
 * A POST-LINK failure (temp unlink or directory fsync) is unconfirmed. The
 * indeterminate (rc=7) reader channel is published FIRST, then our own intended
 * final link is retracted, and the temp unlink is retried. Publishing rc=7
 * before retraction closes the coexistence window: the classifier scans all
 * markers before deciding, and rc=7 outranks success AND conflicts, so a
 * concurrent reader can never return SUCCESS once the indeterminate channel
 * exists. The caller MUST NOT report the intended code on failure.
 *
 * PROVABLE OBSERVATION CONTRACT: link() makes the complete final marker visible
 * BEFORE temp unlink and directory fsync can succeed. An external reader that
 * polls in the brief post-link/pre-rc7 window can classify the intended marker
 * and observe a confirmed terminal verdict (rc0 success included); that
 * observation cannot be retroactively revoked by the later rc=7 publication and
 * retraction. This protocol therefore does NOT claim that a failed publication
 * is never observable as confirmed. It claims only that any result the app acts
 * on AFTER publication completes is indeterminate: the writer exits rc=7, and
 * the rc=7 marker is the reader's steady-state verdict. The safety policy is
 * unchanged - rc=7 precedence and indeterminate ownership still apply. */
int run_status_write(int code);

/* The single source of truth for the writer's exit mapping: a confirmed
 * publication returns the intended code; any publication failure returns
 * RUN_STATUS_TERMINAL_INDETERMINATE (7), never a success code. */
int run_status_publication_exit_code(int write_result, int intended_code);

/* Classify markers for the configured operation. Foreign operation IDs and
 * malformed payloads are ignored; conflicting codes are indeterminate rc=7
 * (completion unverified) with a stable diagnostic, never a definitive rc0/rc1.
 * The whole directory is scanned before any verdict is
 * chosen, and an rc=7 indeterminate marker takes precedence over every other
 * code (including success and conflicts), independent of readdir order. Thus a
 * reader can never read SUCCESS while the indeterminate channel is present,
 * even before the intended marker is retracted. BEFORE rc=7 is published, a
 * complete intended marker is legitimately classifiable (see run_status_write).
 * Returns 0 when *out is filled, -1 on internal error. */
int run_status_classify(struct run_status_result *out);

/* Narrowly scoped I/O scan of the RESERVED identity only. Returns 1 solely for
 * a valid, final-path, regular-file, O_NOFOLLOW marker whose payload is a
 * terminal rc=1 for RUN_STATUS_PRE_ADMISSION_ID itself. Foreign/stale/malformed
 * markers and temp files return 0; this is a diagnostic channel, never a
 * terminal result for the admitted operation. Requires configuration. */
int run_status_pre_admission_failure(void);

/* PURE decision helper: no I/O, no globals, no side effects. Takes the
 * admitted operation's classifier result as the only authoritative terminal
 * source and the pre-admission scan flag as a separate diagnostic input.
 *
 *   TERMINAL / CONFLICT admitted result -> TERMINAL with the admitted code
 *       (rc0 success included; CONFLICT is rc7 indeterminate) and its
 *       diagnostic; a stale pre-admission marker MUST NOT downgrade it.
 *   NONE admitted result + pre-admission failure -> UNCONFIRMED, rc7, with an
 *       explicit "pre-admission failure; completion unverified" diagnostic.
 *   NONE admitted result without it -> NONE, code -1 (keep polling).
 *
 * The reserved rc=1 marker is never mapped to a terminal rc=1 here. */
void run_status_decide(const struct run_status_result *admitted,
                       int pre_admission_failure,
                       struct run_status_decision *out);

/* ---- Pure protocol helpers shared by bootstrap and exp (host-tested) ---- */

/* A soft-reboot command that returned 0 maps to rc=4; any OBSERVED failure maps to 6. */
int run_status_soft_reboot_code(int command_exit_code);

/* ---- Bounded, non-killing child waits and their terminal mapping ----
 *
 * Shared by bootstrap and the host tests. The wait polls with waitpid(WNOHANG)
 * and is EINTR-safe; it NEVER signals or kills the child. A child that outlives
 * its deadline is left running, so its effect stays unverified. Only an
 * OBSERVED exit may produce a command success/failure code: timeout and wait
 * failures always map to the indeterminate code rc=7. */
/* Returns 1 when an exit was observed (*exit_code = WEXITSTATUS, or -1 for a
 * signal death), 0 on deadline expiry (child still alive), -1 on wait error. */
int run_status_wait_child_bounded(pid_t child, int64_t limit_ms, int *exit_code);

/* Late-load command: observed 0 -> rc0, observed nonzero -> rc1, timeout or
 * an unobserved exit -> rc7. */
int run_status_late_load_wait_code(int command_exit_code, int timed_out);

/* Soft-reboot command: observed 0 -> rc4, observed nonzero -> rc6, timeout or
 * an unobserved exit -> rc7. */
int run_status_soft_reboot_wait_code(int command_exit_code, int timed_out);

/* Late-load poll deadline: expiry -> rc7; a still-continuing wait -> -1. */
int run_status_bounded_wait_timeout_code(int expired);

/* Reader deadline expiry: indeterminate rc7, never SUCCESS or a definitive
 * failure code. A still-continuing read -> -1 (keep polling). */
int run_status_reader_timeout_code(int expired);

/* Remaining time in one shared budget. Returns the clamped positive remainder,
 * or -1 when the deadline is already exhausted or the inputs are invalid. The
 * caller passes this to the bounded wait instead of granting a fresh limit. */
int64_t run_status_remaining_budget(int64_t elapsed_ms, int64_t limit_ms);

/* A failed fork() starts no child, so it maps to the ordinary local command
 * failure (rc1 late-load / rc6 soft-reboot) as before. Timeout or an
 * unobserved exit still maps to rc7 through the wait-code helpers. */
int run_status_late_load_launch_code(int launch_failed, int command_exit_code, int timed_out);
int run_status_soft_reboot_launch_code(int launch_failed, int command_exit_code, int timed_out);

/* Legacy global /dev markers are progress-only, including /dev/dfm6; they
 * never produce a terminal exit code. This is the single source of truth for
 * their paths, exact owner-visible messages, and their (always negative)
 * outcome. Both the native reader and the host tests iterate this table. */
#define RUN_STATUS_PROGRESS_ONLY (-1)

struct run_status_progress_marker {
    const char *path;
    const char *message;
    int outcome;                    /* always RUN_STATUS_PROGRESS_ONLY */
};

/* Compile-time count for fixed-size reader state; the exported const count is
 * asserted equal to it in run_status.c. */
#define RUN_STATUS_PROGRESS_MARKER_COUNT 13

extern const struct run_status_progress_marker run_status_progress_markers[];
extern const size_t run_status_progress_marker_count;

/* Resolve the outcome of a legacy global marker; known and unknown global
 * markers alike are progress-only, so this always returns a negative value. */
int run_status_progress_outcome(const char *marker_path);

/* Bounded-wait predicate: true only while a non-negative elapsed time is
 * strictly below the limit. Used for the late-load wait deadline. */
int run_status_wait_continues(int64_t elapsed_ms, int64_t limit_ms);

/* Map a classifier verdict to an exit code. CONFLICT -> 7 (indeterminate:
 * contradictory observations prove nothing), TERMINAL -> its code, NONE -> -1
 * (keep polling). */
int run_status_exit_code(const struct run_status_result *result);

/* A failed terminal CLASSIFICATION (as opposed to a terminal marker verdict)
 * observes no completed outcome. Privileged children may still be running, so
 * the single shared mapping is the indeterminate code rc7 - it can never be a
 * definitive rc0/rc1, and it never confirms any marker that happens to exist.
 * The native reader uses this helper for its classifier-error exit. */
int run_status_classifier_error_code(void);

#ifdef RUN_STATUS_TEST_HOOKS
/* Host-only fault injection; production binaries omit this entirely. */
enum run_status_fault {
    RUN_STATUS_FAULT_NONE = 0,
    RUN_STATUS_FAULT_WRITE = 1 << 0,
    RUN_STATUS_FAULT_FSYNC = 1 << 1,
    RUN_STATUS_FAULT_CLOSE = 1 << 2,
    RUN_STATUS_FAULT_FSYNC_DIR = 1 << 3,
    /* Fail one temp unlink (the post-link one) with EIO, then auto-clear so the
     * unconfirmed path's retry can succeed. Models a transient unlink failure. */
    RUN_STATUS_FAULT_UNLINK_TEMP = 1 << 4
};
void run_status_inject_fault(int fault);

/* Host-only: make run_status_classify() fail as if the terminal observation
 * could not be completed. No marker is produced or confirmed by this seam. */
void run_status_inject_classifier_failure(int enabled);

/* Deterministic reader/writer handshakes for host tests. The writer announces
 * on notify_fd that it is paused, then blocks reading one byte from wait_fd.
 * Both fds may be -1 to disable a seam.
 *   - set_writer_pause: just before the first payload write (temp exists).
 *   - set_post_link_pause: immediately AFTER link() succeeds and BEFORE temp
 *     unlink, directory fsync and failure detection - the earliest instant
 *     the final pathname is observable. The intended marker is complete here
 *     and rc=7 does not exist yet. A reader that classifies here observes the
 *     intended terminal verdict; the later rc=7 publication cannot revoke it.
 *   - set_retract_pause: in the unconfirmed path, AFTER the rc=7 channel is
 *     published but BEFORE the intended final marker is retracted. Both
 *     markers are visible at this point, so a reader that classifies here
 *     exercises the rc=7-over-success precedence directly. */
void run_status_set_writer_pause(int wait_fd, int notify_fd);
void run_status_set_post_link_pause(int wait_fd, int notify_fd);
void run_status_set_retract_pause(int wait_fd, int notify_fd);
#endif

#endif
