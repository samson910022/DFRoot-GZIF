#include "status_protocol.h"

#include <stdio.h>
#include <string.h>

/* Polling granularity. Kept small so a late refinement inside the grace
 * window is observed promptly, but never used as the deadline itself: all
 * deadlines are absolute, checked, monotonic int64 values. */
#define DF_POLL_STEP_MS 10

#define DF_TOKEN_PREFIX "dfroot-v2:"
#define DF_TOKEN_PREFIX_LEN 10

static const char *const kTokens[] = {
    "none",                    /* DF_OUTCOME_NONE (never published) */
    "root_only",               /* ROOT_ONLY: no reboot requested, terminal */
    "reboot_requested",        /* REBOOT_REQUESTED */
    "reboot_skipped",          /* REBOOT_SKIPPED */
    "reboot_failed",           /* REBOOT_FAILED */
    "error",                   /* ERROR */
    "reboot_pending",          /* REBOOT_PENDING: non-terminal preflight */
    "reboot_attempt_unknown",  /* REBOOT_ATTEMPT_UNKNOWN: terminal */
    "bootstrap_failed"         /* BOOTSTRAP_FAILED: reserved-channel terminal */
};

#define DF_OUTCOME_COUNT ((int)(sizeof(kTokens) / sizeof(kTokens[0])))

static int hex_lower(char c)
{
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
}

int df_nonce_is_valid(const char *nonce)
{
    int i;
    if (!nonce) return 0;
    for (i = 0; i < DF_NONCE_CHARS; i++)
        if (!hex_lower(nonce[i])) return 0;
    return nonce[DF_NONCE_CHARS] == '\0';
}

const char *df_outcome_token(enum df_outcome outcome)
{
    if ((int)outcome < (int)DF_OUTCOME_NONE || (int)outcome >= DF_OUTCOME_COUNT)
        return kTokens[DF_OUTCOME_NONE];
    return kTokens[outcome];
}

enum df_outcome df_outcome_from_token(const char *token)
{
    size_t n;
    int i;

    if (!token) return DF_OUTCOME_NONE;
    while (*token == ' ' || *token == '\t' || *token == '\r' || *token == '\n')
        token++;
    n = strlen(token);
    while (n > 0 && (token[n - 1] == ' ' || token[n - 1] == '\t' ||
                     token[n - 1] == '\r' || token[n - 1] == '\n'))
        n--;
    for (i = (int)DF_OUTCOME_NONE; i < DF_OUTCOME_COUNT; i++) {
        size_t tn = strlen(kTokens[i]);
        if (tn == n && memcmp(token, kTokens[i], n) == 0)
            return (enum df_outcome)i;
    }
    return DF_OUTCOME_NONE;
}

int df_outcome_is_publishable(enum df_outcome outcome)
{
    return (int)outcome > (int)DF_OUTCOME_NONE && (int)outcome < DF_OUTCOME_COUNT;
}

int df_format_final_token(char *buf, size_t cap, const char *nonce,
                          enum df_outcome outcome)
{
    int n;
    if (!buf || cap == 0 || !df_nonce_is_valid(nonce) ||
        !df_outcome_is_publishable(outcome))
        return -1;
    n = snprintf(buf, cap, DF_TOKEN_PREFIX "%s:%s\n", nonce,
                 df_outcome_token(outcome));
    if (n < 0 || (size_t)n >= cap)
        return -1;
    return n;
}

int df_token_split(const char *buf, size_t len, char *nonce_out,
                   size_t nonce_cap, enum df_outcome *out)
{
    size_t nlen, olen;
    enum df_outcome o;
    int i;

    if (!buf || !out || !nonce_out || nonce_cap <= DF_NONCE_CHARS)
        return -1;
    /* An embedded NUL is never a valid token byte, even if the surrounding
     * bytes happen to spell a well-formed token. */
    if (len <= DF_TOKEN_PREFIX_LEN + DF_NONCE_CHARS + 2 ||
        len > DF_FINAL_TOKEN_CAP - 1 || memchr(buf, '\0', len) != NULL)
        return -1;
    if (memcmp(buf, DF_TOKEN_PREFIX, DF_TOKEN_PREFIX_LEN) != 0)
        return -1;
    for (i = 0; i < DF_NONCE_CHARS; i++)
        if (!hex_lower(buf[DF_TOKEN_PREFIX_LEN + i])) return -1;
    if (buf[DF_TOKEN_PREFIX_LEN + DF_NONCE_CHARS] != ':')
        return -1;
    if (buf[len - 1] != '\n')
        return -1;

    memcpy(nonce_out, buf + DF_TOKEN_PREFIX_LEN, DF_NONCE_CHARS);
    nonce_out[DF_NONCE_CHARS] = '\0';
    olen = len - (DF_TOKEN_PREFIX_LEN + DF_NONCE_CHARS + 1) - 1;
    o = DF_OUTCOME_NONE;
    for (i = (int)DF_OUTCOME_NONE; i < DF_OUTCOME_COUNT; i++) {
        nlen = strlen(kTokens[i]);
        if (nlen == olen && memcmp(buf + DF_TOKEN_PREFIX_LEN + DF_NONCE_CHARS + 1,
                                   kTokens[i], olen) == 0) {
            o = (enum df_outcome)i;
            break;
        }
    }
    if (!df_outcome_is_publishable(o))
        return -1; /* "none" and unknown names are never accepted */
    *out = o;
    return 0;
}

int df_parse_final_token(const char *buf, size_t len, const char *nonce,
                         enum df_outcome *out)
{
    char found[DF_NONCE_CHARS + 1];
    if (!out)
        return -1;
    *out = DF_OUTCOME_NONE; /* fail closed: never leave a stale outcome */
    if (!df_nonce_is_valid(nonce))
        return -1;
    if (df_token_split(buf, len, found, sizeof(found), out) != 0) {
        *out = DF_OUTCOME_NONE;
        return -1;
    }
    if (strcmp(found, nonce) != 0) {
        *out = DF_OUTCOME_NONE;
        return -1; /* stale or foreign publisher */
    }
    return 0;
}

int df_is_reserved_bootstrap_nonce(const char *nonce)
{
    return nonce && strcmp(nonce, DF_RESERVED_BOOTSTRAP_NONCE) == 0;
}

int df_parse_bootstrap_failure_token(const char *buf, size_t len,
                                     enum df_outcome *out)
{
    enum df_outcome o = DF_OUTCOME_NONE;
    if (!out)
        return -1;
    *out = DF_OUTCOME_NONE;
    /* Reuse the unchanged, byte-for-byte strict final-token parser with the
     * reserved identity: a real run nonce or a malformed token cannot pass.
     * Only the dedicated BOOTSTRAP_FAILED outcome is accepted here. */
    if (df_parse_final_token(buf, len, DF_RESERVED_BOOTSTRAP_NONCE, &o) != 0 ||
        o != BOOTSTRAP_FAILED)
        return -1;
    *out = BOOTSTRAP_FAILED;
    return 0;
}

int df_recovery_status_is_ok(const char *buf, size_t len)
{
    return buf && len == 3 && buf[0] == 'o' && buf[1] == 'k' && buf[2] == '\n';
}

int df_outcome_to_rc(enum df_outcome outcome)
{
    switch (outcome) {
    case ROOT_ONLY:              return DF_RC_ROOT_ONLY;
    case ERROR:                  return DF_RC_ERROR;
    case BOOTSTRAP_FAILED:       return DF_RC_ERROR; /* observable rc=1, not rc=2 */
    case DF_OUTCOME_NONE:        return DF_RC_UNKNOWN;
    case REBOOT_REQUESTED:       return DF_RC_REBOOT_REQUESTED;
    case REBOOT_SKIPPED:         return DF_RC_REBOOT_SKIPPED;
    case REBOOT_FAILED:          return DF_RC_REBOOT_FAILED;
    case REBOOT_PENDING:         return DF_RC_ATTEMPT_UNKNOWN;
    case REBOOT_ATTEMPT_UNKNOWN: return DF_RC_ATTEMPT_UNKNOWN;
    default:                     return DF_RC_UNKNOWN; /* fail closed */
    }
}

/* Checked deadline: clamping at INT64_MAX also covers the (unlikely) case of
 * a monotonic clock close to overflow. Never wrap into the past. */
static int64_t deadline_after(int64_t now, int ms)
{
    int64_t add;
    if (ms <= 0) return now;
    add = (int64_t)ms;
    if (now > INT64_MAX - add) return INT64_MAX;
    return now + add;
}

static void io_report(struct status_io *io, void *ctx, const char *msg)
{
    if (io && io->report) io->report(ctx, msg);
}

static enum df_outcome publish_or_none(struct status_io *io, void *ctx,
                                       enum df_outcome outcome)
{
    if (io->publish_final(ctx, io->nonce, outcome) != 0)
        return DF_OUTCOME_NONE;
    return outcome;
}

enum df_outcome producer_decide_and_publish(struct status_io *io, void *ctx,
                                              int late_load_rc, int soft_reboot,
                                              int late_load_wait_ms,
                                              int child_wait_ms)
{
    int64_t start, deadline, now;
    int child_rc;
    enum df_outcome refined;

    /* Fail closed: without a working clock/sleep/state/publish/wait/nonce we
     * must neither claim success nor authorize a reboot. The recovery gate is
     * checked later: a NULL pointer or a non-positive result publishes
     * REBOOT_SKIPPED and can only disable the reboot. */
    if (!io || !io->now_ms || !io->sleep_ms || !io->late_load_state ||
        !io->publish_final || !io->wait_child || !df_nonce_is_valid(io->nonce))
        return DF_OUTCOME_NONE;

    if (late_load_rc != 0) {
        io_report(io, ctx, "bootstrap: late-load failed; no reboot, publishing ERROR");
        return publish_or_none(io, ctx, ERROR);
    }

    if (!soft_reboot) {
        enum df_outcome o = publish_or_none(io, ctx, ROOT_ONLY);
        io_report(io, ctx, "bootstrap: root only - no reboot requested; root not verified");
        return o;
    }

    /* soft_reboot requested: bound the wait for late-load to finish using a
     * monotonic deadline (not sleep-iteration counts). Unknown state and
     * deadline expiry are both REBOOT_SKIPPED: never reboot on unknown. */
    start = io->now_ms(ctx);
    if (start < 0) {
        /* The failure is observable under the REAL run nonce as its own rc=1
         * outcome; it must not degrade into a silent/unknown rc=2 timeout. */
        io_report(io, ctx, "bootstrap: clock unavailable; publishing BOOTSTRAP_FAILED");
        return publish_or_none(io, ctx, BOOTSTRAP_FAILED);
    }
    deadline = deadline_after(start, late_load_wait_ms);
    for (;;) {
        int st = io->late_load_state(ctx);
        int64_t remaining, step;

        if (st < 0) {
            io_report(io, ctx, "bootstrap: late-load state unknown; skipping soft reboot");
            return publish_or_none(io, ctx, REBOOT_SKIPPED);
        }
        if (st == 0) {
            /* The proc scan may have taken longer than the deadline. Never
             * accept a late "absent" observation as authorization to reboot. */
            now = io->now_ms(ctx);
            if (now < 0) {
                io_report(io, ctx, "bootstrap: clock failed after late-load scan; skipping soft reboot");
                return publish_or_none(io, ctx, REBOOT_SKIPPED);
            }
            if (now >= deadline) {
                io_report(io, ctx, "bootstrap: late-load absent but deadline expired; skipping soft reboot");
                return publish_or_none(io, ctx, REBOOT_SKIPPED);
            }
            break; /* confirmed absent, inside the deadline */
        }

        now = io->now_ms(ctx);
        if (now < 0) {
            io_report(io, ctx, "bootstrap: clock failed during late-load wait; skipping soft reboot");
            return publish_or_none(io, ctx, REBOOT_SKIPPED);
        }
        if (now >= deadline) {
            io_report(io, ctx, "bootstrap: late-load wait timed out; skipping soft reboot");
            return publish_or_none(io, ctx, REBOOT_SKIPPED);
        }
        remaining = deadline - now;
        step = remaining < DF_POLL_STEP_MS ? remaining : DF_POLL_STEP_MS;
        io->sleep_ms(ctx, (int)step);
    }

    /* Confirmed absent. Publish REBOOT_PENDING as a preflight: it is
     * non-terminal and opens the consumer's bounded grace window. If this
     * fails we refuse to reboot, because the consumer would have no final
     * status. ROOT_ONLY is NEVER used as a reboot preflight: it means "no
     * reboot requested" and is terminal immediately. */
    if (io->publish_final(ctx, io->nonce, REBOOT_PENDING) != 0) {
        io_report(io, ctx, "bootstrap: pending preflight publish failed; refusing to reboot");
        return DF_OUTCOME_NONE;
    }

    /* Recovery gate: the only positive writer (app cleanup after the first
     * bound observation) cannot run until it has SEEN this PENDING token, so
     * one immediate read would always observe the startup "fail\n" and make
     * the success path unreachable. Poll on a bounded absolute deadline,
     * clamped to the remaining late-load deadline. A NULL callback, a clock
     * error or a gate that never turns positive publishes REBOOT_SKIPPED and
     * returns BEFORE any reboot command: this can only DISABLE a reboot. */
    if (!io->recovery_ok) {
        io_report(io, ctx,
                  "bootstrap: no recovery verification callback; skipping soft reboot");
        return publish_or_none(io, ctx, REBOOT_SKIPPED);
    }
    now = io->now_ms(ctx);
    if (now < 0 || now >= deadline) {
        io_report(io, ctx,
                  "bootstrap: clock unavailable for recovery wait; skipping soft reboot");
        return publish_or_none(io, ctx, REBOOT_SKIPPED);
    }
    {
        int64_t window = deadline_after(now, DF_RECOVERY_WAIT_MS);
        int64_t recovery_deadline = (window < deadline) ? window : deadline;

        for (;;) {
            int64_t remaining, step;

            now = io->now_ms(ctx); /* checked every iteration: fail closed */
            if (now < 0) {
                io_report(io, ctx,
                          "bootstrap: clock failed during recovery wait; skipping soft reboot");
                return publish_or_none(io, ctx, REBOOT_SKIPPED);
            }
            if (io->recovery_ok(ctx) > 0)
                break; /* verified restore; command only starts below */
            if (now >= recovery_deadline) {
                io_report(io, ctx,
                          "bootstrap: restore verification not confirmed; skipping soft reboot");
                return publish_or_none(io, ctx, REBOOT_SKIPPED);
            }
            remaining = recovery_deadline - now;
            step = remaining < DF_POLL_STEP_MS ? remaining : DF_POLL_STEP_MS;
            io->sleep_ms(ctx, (int)step);
        }
    }

    io_report(io, ctx, "bootstrap: issuing soft-reboot command (not a confirmation)");
    child_rc = io->wait_child(ctx, child_wait_ms);
    refined = (child_rc == 0) ? REBOOT_REQUESTED : REBOOT_FAILED;
    io_report(io, ctx, child_rc == 0
              ? "bootstrap: soft-reboot command exited 0; reboot not confirmed by app"
              : "bootstrap: soft-reboot command failed or timed out");

    /* If the refinement cannot be published, the attempt outcome really is
     * unknown. Publish REBOOT_ATTEMPT_UNKNOWN; never fall back to ROOT_ONLY
     * (which would falsely say no reboot was requested). */
    if (io->publish_final(ctx, io->nonce, refined) != 0) {
        io_report(io, ctx, "bootstrap: refinement publish failed; reporting attempt unknown");
        return publish_or_none(io, ctx, REBOOT_ATTEMPT_UNKNOWN);
    }
    return refined;
}

static const char *final_message(enum df_outcome outcome)
{
    switch (outcome) {
    case ROOT_ONLY:              return "final: ROOT_ONLY - no reboot requested; root not verified";
    case REBOOT_PENDING:         return "final: REBOOT_PENDING - soft reboot requested; awaiting bounded outcome";
    case REBOOT_REQUESTED:       return "final: REBOOT_REQUESTED - soft-reboot command exited 0; reboot not confirmed";
    case REBOOT_SKIPPED:         return "final: REBOOT_SKIPPED - soft reboot not attempted (late-load not confirmed absent)";
    case REBOOT_FAILED:          return "final: REBOOT_FAILED - soft-reboot command failed or timed out";
    case REBOOT_ATTEMPT_UNKNOWN: return "final: REBOOT_ATTEMPT_UNKNOWN - reboot attempt result unknown";
    case ERROR:                  return "final: ERROR - bootstrap failed before root";
    case BOOTSTRAP_FAILED:       return "final: BOOTSTRAP_FAILED - bootstrap failed before a run identity was available; no reboot attempted";
    default:                     return "final: no final status observed";
    }
}

/* Terminal outcomes finish immediately. REBOOT_PENDING is the sole outcome
 * that starts (or continues) a grace window. */
static int terminal_outcome(enum df_outcome o)
{
    return o != DF_OUTCOME_NONE && o != REBOOT_PENDING;
}

/* Run the cleanup/restore callback at most once and recheck the clock: the
 * time it spends MUST count toward the SAME absolute deadline as the poll
 * loop. A failed cleanup is reported (not silently swallowed) and also closes
 * the producer's recovery gate via the app-private status file. */
static int64_t cleanup_and_recheck(struct status_io *io, void *ctx,
                                   int (*cleanup_cb)(void *ctx),
                                   int *cleanup_done, int *cleanup_failed)
{
    if (cleanup_cb && !*cleanup_done) {
        *cleanup_done = 1;
        if (cleanup_cb(ctx) < 0 && cleanup_failed)
            *cleanup_failed = 1;
    }
    return io->now_ms(ctx);
}

/* Deadline exit shared by both deadline checks in the loop. When no bound
 * status was observed, cleanup still runs exactly once here (at the end of the
 * wait); the clock is checked again after it before deciding the result. */
static enum df_wait_result deadline_result(struct status_io *io, void *ctx,
                                           int saw_pending,
                                           int (*cleanup_cb)(void *ctx),
                                           int *cleanup_done, int *cleanup_failed)
{
    if (cleanup_and_recheck(io, ctx, cleanup_cb, cleanup_done, cleanup_failed) < 0)
        return ERR;
    if (saw_pending) {
        io->observed_outcome = REBOOT_ATTEMPT_UNKNOWN;
        io_report(io, ctx, final_message(REBOOT_ATTEMPT_UNKNOWN));
        return DONE;
    }
    return TIMEOUT;
}

/* Shared polling loop. `start` and `overall_deadline` are computed ONCE by the
 * caller; both consumer_await() and status_flow_run() use this same absolute
 * deadline clock, including the time consumed by cleanup. `first_seen` records
 * whether a valid, nonce-bound status observation has happened (the only point
 * at which cleanup may run early). */
static enum df_wait_result await_loop(struct status_io *io, void *ctx,
                                      int64_t start, int64_t overall_deadline,
                                      int budget_ms, int grace_ms,
                                      int *first_seen,
                                      int (*cleanup_cb)(void *ctx),
                                      int *cleanup_failed)
{
    int64_t deadline, budget_deadline;
    int saw_pending = 0, cleanup_done = 0;
    char buf[DF_FINAL_TOKEN_CAP];
    char boot_buf[DF_FINAL_TOKEN_CAP];

    budget_deadline = deadline_after(start, budget_ms);
    if (overall_deadline < budget_deadline)
        overall_deadline = budget_deadline;
    deadline = budget_deadline;

    for (;;) {
        int64_t now = io->now_ms(ctx);
        int64_t remaining, step;
        size_t len = 0, boot_len = 0;
        int rc, final_parsed = 0;

        if (now < 0)
            return ERR;

        /* Checked monotonic deadline: no wraparound, no elapsed counters.
         * A pending preflight that never refines is reported as an unknown
         * attempt rather than as a generic timeout. */
        if (now >= deadline)
            return deadline_result(io, ctx, saw_pending, cleanup_cb,
                                   &cleanup_done, cleanup_failed);

        rc = io->read_final(ctx, buf, sizeof(buf), &len);
        if (rc == 0) {
            enum df_outcome o = DF_OUTCOME_NONE;
            if (df_parse_final_token(buf, len, io->nonce, &o) == 0) {
                final_parsed = 1;
                if (!*first_seen) {
                    *first_seen = 1;
                    /* Restore only after a bound status exists: cleanup is
                     * never allowed to run before the first observation. */
                    now = cleanup_and_recheck(io, ctx, cleanup_cb,
                                              &cleanup_done, cleanup_failed);
                    if (now < 0) return ERR;
                }
                if (o == REBOOT_PENDING && !saw_pending) {
                    int64_t g;
                    saw_pending = 1;
                    /* Grace starts AFTER cleanup so the restore time cannot
                     * eat the window in which a reboot result must arrive.
                     * It is clamped to the OVERALL deadline, never to the
                     * shorter budget deadline. */
                    g = deadline_after(now, grace_ms);
                    deadline = (g < overall_deadline) ? g : overall_deadline;
                    io_report(io, ctx,
                              "final: REBOOT_PENDING observed - allowing bounded time for a reboot outcome");
                } else if (terminal_outcome(o)) {
                    io->observed_outcome = o;
                    io_report(io, ctx, final_message(o));
                    return DONE;
                }
                /* Any other token (including a second PENDING, a stale/foreign
                 * nonce or a garbled token) is ignored; keep polling. */
            }
        }
        /* rc < 0 (unavailable) or a strict parse failure: poll the SEPARATE
         * reserved-identity channel. A valid normal token (even PENDING) wins
         * for this iteration and never consults the bootfail channel. */
        if (!final_parsed && io->read_bootstrap_failure) {
            boot_len = 0;
            if (io->read_bootstrap_failure(ctx, boot_buf, sizeof(boot_buf),
                                           &boot_len) == 0) {
                enum df_outcome bo = DF_OUTCOME_NONE;
                if (df_parse_bootstrap_failure_token(boot_buf, boot_len,
                                                     &bo) == 0) {
                    if (!*first_seen) {
                        *first_seen = 1;
                        now = cleanup_and_recheck(io, ctx, cleanup_cb,
                                                  &cleanup_done, cleanup_failed);
                        if (now < 0) return ERR;
                    }
                    io->observed_outcome = BOOTSTRAP_FAILED;
                    io_report(io, ctx, final_message(BOOTSTRAP_FAILED));
                    return DONE;
                }
            }
        }

        now = io->now_ms(ctx);
        if (now < 0)
            return ERR;
        if (now >= deadline)
            return deadline_result(io, ctx, saw_pending, cleanup_cb,
                                   &cleanup_done, cleanup_failed);
        remaining = deadline - now;
        step = remaining < DF_POLL_STEP_MS ? remaining : DF_POLL_STEP_MS;
        if (step > 0) io->sleep_ms(ctx, (int)step);
    }
}

enum df_wait_result consumer_await(struct status_io *io, void *ctx,
                                    int budget_ms, int grace_ms, int overall_ms)
{
    int64_t start, overall_deadline;
    int first_seen = 0;

    if (!io || !io->now_ms || !io->read_final || !io->sleep_ms ||
        !df_nonce_is_valid(io->nonce))
        return ERR;
    io->observed_outcome = DF_OUTCOME_NONE;

    start = io->now_ms(ctx);
    if (start < 0)
        return ERR; /* no clock: cannot bound the wait */
    overall_deadline = deadline_after(start, overall_ms);
    return await_loop(io, ctx, start, overall_deadline, budget_ms, grace_ms,
                      &first_seen, NULL, NULL);
}

enum df_wait_result status_flow_run(struct status_io *io, void *ctx,
                                     int budget_ms, int grace_ms, int overall_ms,
                                     int (*cleanup_cb)(void *ctx),
                                     enum df_outcome *out, int *cleanup_failed)
{
    enum df_wait_result r;
    int64_t start, overall_deadline;
    int first_seen = 0;

    if (out) *out = DF_OUTCOME_NONE;
    if (cleanup_failed) *cleanup_failed = 0;
    if (!io || !io->now_ms || !io->read_final || !io->sleep_ms ||
        !df_nonce_is_valid(io->nonce))
        return ERR;
    /* An ERR before any observation must not expose a stale outcome from a
     * previous run through io->observed_outcome. */
    io->observed_outcome = DF_OUTCOME_NONE;

    start = io->now_ms(ctx);
    if (start < 0)
        return ERR;
    overall_deadline = deadline_after(start, overall_ms);
    /* No cleanup before the first bound observation: await_loop() owns the
     * exact-once callback on the same absolute deadline as the polling. */
    r = await_loop(io, ctx, start, overall_deadline, budget_ms, grace_ms,
                   &first_seen, cleanup_cb, cleanup_failed);
    if (out) *out = io->observed_outcome;
    return r;
}
