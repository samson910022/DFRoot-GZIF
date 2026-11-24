/* Modified 2026-11-25 (DFRoot GZIF): define the run-scoped terminal marker protocol. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): share the progress-marker table and composed deadlines across both native binaries. See NOTICE. */
#ifndef DFROOT_RUN_STATUS_H
#define DFROOT_RUN_STATUS_H

#include <stddef.h>
#include <stdint.h>

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

/* Terminal codes. The Java NativeResultMapper consumes exactly these values. */
enum run_status_code {
    RUN_STATUS_TERMINAL_SUCCESS = 0,
    RUN_STATUS_TERMINAL_ERROR = 1,
    RUN_STATUS_TERMINAL_SOFT_REBOOT_OK = 4,
    RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL = 6
};

/* Shared timing budget (ms). The terminal reader must outlive the writer's
 * late-load poll and soft-reboot command wait combined, so it can never give
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
    int code;                       /* -1 for NONE; single code; 1 for CONFLICT */
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

/* Checked, exclusive terminal marker write. Returns 0 only on a fully
 * verified write (type, no-follow, write, fsync, close). */
int run_status_write(int code);

/* Classify markers for the configured operation. Foreign operation IDs and
 * malformed payloads are ignored; conflicting codes are a terminal error with
 * a stable diagnostic. Returns 0 when *out is filled, -1 on internal error. */
int run_status_classify(struct run_status_result *out);

/* ---- Pure protocol helpers shared by bootstrap and exp (host-tested) ---- */

/* A soft-reboot command that returned 0 maps to rc=4; any failure maps to 6. */
int run_status_soft_reboot_code(int command_exit_code);

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

/* Map a classifier verdict to an exit code. CONFLICT -> 1, TERMINAL -> its
 * code, NONE -> -1 (keep polling). */
int run_status_exit_code(const struct run_status_result *result);

#ifdef RUN_STATUS_TEST_HOOKS
/* Host-only fault injection; production binaries omit this entirely. */
enum run_status_fault {
    RUN_STATUS_FAULT_NONE = 0,
    RUN_STATUS_FAULT_WRITE = 1 << 0,
    RUN_STATUS_FAULT_FSYNC = 1 << 1,
    RUN_STATUS_FAULT_CLOSE = 1 << 2
};
void run_status_inject_fault(int fault);
#endif

#endif
