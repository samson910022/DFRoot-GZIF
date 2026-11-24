#ifndef DFROOT_STATUS_PROTOCOL_H
#define DFROOT_STATUS_PROTOCOL_H

/* Pure, host-testable status protocol. This header intentionally depends only
 * on stddef/stdint: no unistd/sys/posix headers, no /dev paths, no globals.
 * All environment interaction is injected through `struct status_io`. */

#include <stddef.h>
#include <stdint.h>

/* The single atomically published final outcome. DF_OUTCOME_NONE means "no
 * final status was observed" (unknown); it is never published as a token. */
enum df_outcome {
    DF_OUTCOME_NONE,
    ROOT_ONLY,
    REBOOT_REQUESTED,
    REBOOT_SKIPPED,
    REBOOT_FAILED,
    ERROR
};

enum df_wait_result { DONE, TIMEOUT, ERR };

/* Injected environment. Every function pointer receives the opaque `ctx`
 * supplied by the caller; none of them are optional for the paths that use
 * them (a NULL pointer makes the corresponding operation fail closed).
 *
 * Observed output: consumer_await() writes the outcome it finally accepted
 * into `observed_outcome`; status_flow_run() copies it to its `out` argument.
 */
struct status_io {
    int64_t (*now_ms)(void *ctx);              /* monotonic clock, milliseconds;
                                                * negative = clock failure (both
                                                * producer and consumer fail closed) */
    void    (*sleep_ms)(void *ctx, int ms);    /* bounded sleep */
    int     (*read_final)(void *ctx, char *buf, size_t cap); /* 0=ok (NUL-terminated token), <0=unavailable */
    int     (*publish_final)(void *ctx, const char *token);  /* 0=ok, <0=failure (atomic replace) */
    int     (*late_load_state)(void *ctx);     /* >0 running, 0 confirmed absent, <0 unknown */
    int     (*wait_child)(void *ctx, int timeout_ms); /* 0=exit0, >0=exit code, <0=error/timeout */
    void    (*report)(void *ctx, const char *msg);    /* informational only, never an oracle */
    enum df_outcome observed_outcome;          /* OUTPUT of consumer_await() */
};

/* Token <-> outcome conversion. Unknown/garbled/empty tokens map to
 * DF_OUTCOME_NONE so they can be ignored by the consumer. */
const char       *df_outcome_token(enum df_outcome outcome);
enum df_outcome   df_outcome_from_token(const char *token);

/* App result codes: NONE=2, ROOT_ONLY=0, REBOOT_REQUESTED=4,
 * REBOOT_SKIPPED=5, REBOOT_FAILED=6, ERROR=1 (unknown defaults to 2). */
int df_outcome_to_rc(enum df_outcome outcome);

/* Producer (runs in bootstrap). Decides and atomically publishes the final
 * outcome. Never authorizes a reboot unless a ROOT_ONLY preflight was
 * published successfully. Returns the outcome it believes it published. */
enum df_outcome producer_decide_and_publish(struct status_io *io, void *ctx,
                                             int late_load_rc, int soft_reboot,
                                             int late_load_wait_ms,
                                             int child_wait_ms);

/* Consumer (runs in exp). Polls the final file against a checked monotonic
 * deadline. A first ROOT_ONLY observation rebases a bounded grace window so a
 * late refinement (REBOOT_REQUESTED/FAILED) can be observed; other terminal
 * tokens finish immediately. Garbled tokens and read errors are ignored and
 * polling continues. */
enum df_wait_result consumer_await(struct status_io *io, void *ctx,
                                   int budget_ms, int grace_ms);

/* Consumer entry point: awaits the final outcome, calls cleanup_cb exactly
 * once (promptly on terminal observation or timeout), and writes the observed
 * outcome to *out. */
enum df_wait_result status_flow_run(struct status_io *io, void *ctx,
                                    int budget_ms, int grace_ms,
                                    void (*cleanup_cb)(void *ctx),
                                    enum df_outcome *out);

#endif /* DFROOT_STATUS_PROTOCOL_H */
