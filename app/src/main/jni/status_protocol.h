#ifndef DFROOT_STATUS_PROTOCOL_H
#define DFROOT_STATUS_PROTOCOL_H

/* Pure, host-testable status protocol. This header intentionally depends only
 * on stddef/stdint: no unistd/sys/posix headers, no /dev paths, no globals.
 * All environment interaction is injected through `struct status_io`. */

#include <stddef.h>
#include <stdint.h>

/* A nonce is 128 bits, hex-encoded as exactly 32 lowercase characters. It MUST
 * be generated and persisted by exp.c BEFORE the hook is armed, and read back
 * by bootstrap; only then does a token bound to it identify this invocation.
 * A nonce read from shared persistent state alone does NOT establish identity:
 * exp.c refuses to arm when an existing dfroot.final property token or an
 * app-private state file is stale, tampered or otherwise ambiguous. Clearing shared state
 * at entry (dfinv/dfrecover/dfbootfail) is ONLY hygiene, NEVER proof that a
 * later publication belongs to this invocation: every auxiliary observation
 * must be byte-bound to the current nonce before it can carry authority. */
#define DF_NONCE_BYTES 16
#define DF_NONCE_CHARS 32
/* Exact byte length of a bound recovery-status token: 32 hex + '\n'. */
#define DF_RECOVERY_STATUS_CHARS (DF_NONCE_CHARS + 1)
/* Dedicated reserved identity for bootstrap failures that happen before a run
 * nonce can be established. Never used for a normal dfroot.final publication.
 * The reserved token's SYNTAX is not invocation proof: it identifies a
 * bootstrap failure channel, never the current run's final outcome. */
#define DF_RESERVED_BOOTSTRAP_NONCE "00000000000000000000000000000000"
/* Bounded wait for the app-side recovery gate to flip from its startup
 * "fail\n" to a verified "ok\n". Always clamped to the producer's remaining
 * late-load deadline; it can only DELAY a reboot, never authorize one. */
#define DF_RECOVERY_WAIT_MS 5000
/* "dfroot-v2:" (10) + 32 nonce + ':' + longest outcome + '\n' + NUL. */
#define DF_FINAL_TOKEN_CAP 96

/* App result codes. The recovery bit is OR'ed into EVERY code when a restore
 * verification failed, so a "successful" rc can never hide broken cleanup. */
#define DF_RC_ROOT_ONLY          0
#define DF_RC_ERROR              1
#define DF_RC_UNKNOWN            2
#define DF_RC_PATCH_FAILED       3
#define DF_RC_REBOOT_REQUESTED   4
#define DF_RC_REBOOT_SKIPPED     5
#define DF_RC_REBOOT_FAILED      6
#define DF_RC_ATTEMPT_UNKNOWN    7
#define DF_RC_ALREADY_HOOKED     9
#define DF_RC_RECOVERY_FAILED_BIT 64

/* The single atomically published final outcome. DF_OUTCOME_NONE means "no
 * final status was observed" (unknown); it is never published as a token.
 * ROOT_ONLY means ONLY "no reboot was requested" and is terminal immediately.
 * REBOOT_PENDING is the non-terminal preflight that opens a grace window;
 * REBOOT_ATTEMPT_UNKNOWN is terminal and says the attempt result is unknown.
 * BOOTSTRAP_FAILED is appended as a NEW terminal value and maps to rc=1: it is
 * published on the separate reserved-nonce bootfail channel when bootstrap
 * fails before a run identity exists. Existing values are never renumbered. */
enum df_outcome {
    DF_OUTCOME_NONE,
    ROOT_ONLY,
    REBOOT_REQUESTED,
    REBOOT_SKIPPED,
    REBOOT_FAILED,
    ERROR,
    REBOOT_PENDING,
    REBOOT_ATTEMPT_UNKNOWN,
    BOOTSTRAP_FAILED
};

enum df_wait_result { DONE, TIMEOUT, ERR };

/* Injected environment. Every function pointer receives the opaque `ctx`
 * supplied by the caller; none of them are optional for the paths that use
 * them (a NULL pointer makes the corresponding operation fail closed).
 *
 * read_final() returns 0 and the EXACT byte count in *out_len (the buffer may
 * contain embedded NULs, which the strict parser rejects); <0 means
 * unavailable. publish_final() is given the nonce and the outcome and must
 * publish the canonical v2 token; it must never invent its own token text.
 * `nonce` is the expected 32-character hex nonce for this run (fail closed if
 * NULL/invalid).
 *
 * read_bootstrap_failure() is a SEPARATE app-owned channel for a token bound to
 * DF_RESERVED_BOOTSTRAP_NONCE; it is polled only when no normal final token was
 * parsed in that iteration (strict parse failure or read unavailable). A NULL
 * callback means "channel unavailable" (fail closed). Reserved-nonce syntax is
 * NOT proof of this invocation's identity, so a bootfail observation must never
 * claim a bound final token.
 * recovery_ok(ctx, nonce) is the cleanup/restore verification gate. It receives
 * the SAME run nonce used to bind final tokens, and MUST return >0 only for a
 * status bound to that nonce. A NULL pointer, a stale/unbound "ok\n" or a
 * result <= 0 MUST disable the reboot path; it can never enable one. The
 * callback must be checked against the deadline immediately before AND after a
 * positive result: late or uncertain evidence never authorizes a reboot.
 *
 * Observed output: consumer_await() writes the outcome it finally accepted
 * into `observed_outcome`; status_flow_run() copies it to its `out` argument
 * and reports a failed cleanup through `cleanup_failed`.
 */
struct status_io {
    int64_t (*now_ms)(void *ctx);              /* monotonic clock, milliseconds;
                                                 * negative = clock failure (both
                                                 * producer and consumer fail closed) */
    void    (*sleep_ms)(void *ctx, int ms);    /* bounded sleep */
    int     (*read_final)(void *ctx, char *buf, size_t cap, size_t *out_len);
    int     (*read_bootstrap_failure)(void *ctx, char *buf, size_t cap,
                                      size_t *out_len); /* reserved channel */
    int     (*publish_final)(void *ctx, const char *nonce, enum df_outcome outcome);
    int     (*late_load_state)(void *ctx);     /* >0 running, 0 confirmed absent, <0 unknown */
    int     (*recovery_ok)(void *ctx, const char *nonce); /* >0 nonce-bound restore,
                                                           * <=0/NULL: no reboot */
    int     (*wait_child)(void *ctx, int timeout_ms); /* 0=exit0, >0=exit code, <0=error/timeout */
    void    (*report)(void *ctx, const char *msg);    /* informational only, never an oracle */
    const char *nonce;                         /* expected run nonce (32 lowercase hex chars) */
    enum df_outcome observed_outcome;          /* OUTPUT of consumer_await() */
};

/* Strict nonce validation: exactly DF_NONCE_CHARS lowercase hex characters. */
int df_nonce_is_valid(const char *nonce);

/* Pure outcome <-> name conversion. Unknown/garbled/empty names map to
 * DF_OUTCOME_NONE so they can be ignored by the consumer. */
const char       *df_outcome_token(enum df_outcome outcome);
enum df_outcome   df_outcome_from_token(const char *token);
int               df_outcome_is_publishable(enum df_outcome outcome);

/* Canonical v2 token: "dfroot-v2:<nonce>:<outcome>\n" (no NUL inside, no extra
 * bytes). df_format_final_token() returns the byte length or -1;
 * df_token_split() validates the shape and extracts the nonce/outcome;
 * df_parse_final_token() additionally requires the expected nonce. */
int df_format_final_token(char *buf, size_t cap, const char *nonce,
                          enum df_outcome outcome);
int df_token_split(const char *buf, size_t len, char *nonce_out,
                   size_t nonce_cap, enum df_outcome *out);
int df_parse_final_token(const char *buf, size_t len, const char *nonce,
                         enum df_outcome *out);

/* Reserved bootstrap-failure channel (pure). The bootfail token must carry
 * EXACTLY the reserved nonce AND the BOOTSTRAP_FAILED outcome: a real run nonce
 * or any other outcome is rejected even if the rest of the bytes are a valid
 * v2 token. df_parse_final_token() itself is unchanged and remains strict. */
int df_is_reserved_bootstrap_nonce(const char *nonce);
int df_parse_bootstrap_failure_token(const char *buf, size_t len,
                                     enum df_outcome *out);

/* Strict validation of the legacy, UNBOUND recovery-status bytes ("ok\n"
 * only). Empty, "fail\n", extra bytes and embedded NULs are all rejected, but
 * accepting "ok\n" here is NOT sufficient authorization: the byte sequence has
 * no invocation identity and may be stale or foreign. Production gates MUST use
 * df_recovery_status_is_bound_ok() below. These pure validators only prove
 * parser strictness on the HOST; they do not validate the Android trust
 * boundary or on-device freshness. */
int df_recovery_status_is_ok(const char *buf, size_t len);

/* Authoritative recovery gate: EXACTLY 32 lowercase hex characters (byte-equal
 * to `nonce`) followed by a single '\n'. A plain "ok\n", a foreign/stale nonce,
 * uppercase hex, extra bytes or an invalid nonce all fail closed. Only a token
 * produced by this invocation's verified cleanup can satisfy this check. */
int df_recovery_status_is_bound_ok(const char *buf, size_t len,
                                    const char *nonce);

/* App result codes: 0/1/2/3/4/5/6/7/9 plus the 64 recovery-failed bit. */
int df_outcome_to_rc(enum df_outcome outcome);

/* Producer (runs in bootstrap). Decides and atomically publishes the final
 * outcome. Never authorizes a reboot unless a confirmed-absent late-load, a
 * successful REBOOT_PENDING preflight and a POSITIVE, NONCE-BOUND recovery_ok()
 * gate were observed INSIDE the deadline. recovery_ok() is checked only AFTER
 * the pending preflight and BEFORE wait_child, with the deadline enforced both
 * immediately before the call and immediately after a positive result: a
 * NULL/failed/unbound/late gate publishes REBOOT_SKIPPED with ZERO child calls
 * and can only disable the reboot. A clock failure before any deadline can be
 * enforced publishes BOOTSTRAP_FAILED under the real nonce. Returns the outcome
 * it believes it published. */
enum df_outcome producer_decide_and_publish(struct status_io *io, void *ctx,
                                              int late_load_rc, int soft_reboot,
                                              int late_load_wait_ms,
                                              int child_wait_ms);

/* Consumer (runs in exp). Polls the final file against checked monotonic
 * absolute deadlines. budget_ms bounds a wait with no preflight; a
 * REBOOT_PENDING observation rebases a grace window clamped ONLY to the
 * overall_ms deadline (never to the budget deadline), so a late refinement can
 * extend the wait past the budget up to the overall cap. Garbled tokens and
 * read errors are ignored and polling continues; a valid reserved-nonce
 * bootfail token on the separate channel is accepted as BOOTSTRAP_FAILED.
 * Timing out while pending yields REBOOT_ATTEMPT_UNKNOWN. */
enum df_wait_result consumer_await(struct status_io *io, void *ctx,
                                    int budget_ms, int grace_ms, int overall_ms);

/* Consumer entry point. cleanup_cb runs EXACTLY ONCE ON EVERY EXIT (guard
 * failure, clock failure, ERR, TIMEOUT and DONE), after the first valid
 * nonce-bound final token (or at the end of the wait if none arrives, or in the
 * fail-closed epilogue for ERR). Its `bound_observation` argument is 1 ONLY for
 * a normal bound final token; it is 0 for a reserved-channel bootfail, a
 * deadline with no observation, and every ERR/fallback path. A 0 observation
 * MUST keep the producer's recovery gate closed. cleanup_cb returns 0 when
 * restore verification succeeded and <0 on failure; any failure is reported
 * through `*cleanup_failed`. Every ERR exit sets `*cleanup_failed` (recovery is
 * UNKNOWN, never a normal completion) and leaves `*out` as DF_OUTCOME_NONE.
 * Cleanup runs on the SAME absolute deadline as the poll loop, its time is
 * charged to that deadline, and the clock is rechecked before any terminal
 * DONE: a cleanup that overruns the deadline routes to TIMEOUT /
 * REBOOT_ATTEMPT_UNKNOWN instead. */
enum df_wait_result status_flow_run(struct status_io *io, void *ctx,
                                     int budget_ms, int grace_ms, int overall_ms,
                                     int (*cleanup_cb)(void *ctx,
                                                       int bound_observation),
                                     enum df_outcome *out,
                                     int *cleanup_failed);

#endif /* DFROOT_STATUS_PROTOCOL_H */
