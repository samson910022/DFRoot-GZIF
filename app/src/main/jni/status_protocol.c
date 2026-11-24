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
    "reboot_attempt_unknown"   /* REBOOT_ATTEMPT_UNKNOWN: terminal */
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

int df_outcome_to_rc(enum df_outcome outcome)
{
    switch (outcome) {
    case ROOT_ONLY:              return DF_RC_ROOT_ONLY;
    case ERROR:                  return DF_RC_ERROR;
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
     * must neither claim success nor authorize a reboot. */
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
        io_report(io, ctx, "bootstrap: clock unavailable; refusing soft reboot");
        return DF_OUTCOME_NONE; /* cannot enforce any deadline */
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
    default:                     return "final: no final status observed";
    }
}

/* Terminal outcomes finish immediately. REBOOT_PENDING is the sole outcome
 * that starts (or continues) a grace window. */
static int terminal_outcome(enum df_outcome o)
{
    return o != DF_OUTCOME_NONE && o != REBOOT_PENDING;
}

enum df_wait_result consumer_await(struct status_io *io, void *ctx,
                                    int budget_ms, int grace_ms, int overall_ms)
{
    int64_t start, budget_deadline, overall_deadline, deadline;
    int saw_pending = 0;
    char buf[DF_FINAL_TOKEN_CAP];

    if (!io || !io->now_ms || !io->read_final || !io->sleep_ms ||
        !df_nonce_is_valid(io->nonce))
        return ERR;
    io->observed_outcome = DF_OUTCOME_NONE;

    start = io->now_ms(ctx);
    if (start < 0)
        return ERR; /* no clock: cannot bound the wait */
    budget_deadline = deadline_after(start, budget_ms);
    overall_deadline = deadline_after(start, overall_ms);
    if (overall_deadline < budget_deadline)
        overall_deadline = budget_deadline;
    deadline = budget_deadline;

    for (;;) {
        int64_t now = io->now_ms(ctx);
        int64_t remaining, step;
        size_t len = 0;
        int rc;

        if (now < 0)
            return ERR;

        /* Checked monotonic deadline: no wraparound, no elapsed counters.
         * A pending preflight that never refines is reported as an unknown
         * attempt rather than as a generic timeout. */
        if (now >= deadline) {
            if (saw_pending) {
                io->observed_outcome = REBOOT_ATTEMPT_UNKNOWN;
                io_report(io, ctx, final_message(REBOOT_ATTEMPT_UNKNOWN));
                return DONE;
            }
            return TIMEOUT;
        }

        rc = io->read_final(ctx, buf, sizeof(buf), &len);
        if (rc == 0) {
            enum df_outcome o = DF_OUTCOME_NONE;
            if (df_parse_final_token(buf, len, io->nonce, &o) == 0) {
                if (o == REBOOT_PENDING && !saw_pending) {
                    int64_t g;
                    saw_pending = 1;
                    g = deadline_after(now, grace_ms);
                    /* Grace is clamped to the OVERALL deadline, not to the
                     * budget deadline: a refinement arriving just after the
                     * budget still has to be observable. */
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
        /* rc < 0: read_final unavailable - keep polling until the deadline. */

        now = io->now_ms(ctx);
        if (now < 0)
            return ERR;
        if (now >= deadline) {
            if (saw_pending) {
                io->observed_outcome = REBOOT_ATTEMPT_UNKNOWN;
                io_report(io, ctx, final_message(REBOOT_ATTEMPT_UNKNOWN));
                return DONE;
            }
            return TIMEOUT;
        }
        remaining = deadline - now;
        step = remaining < DF_POLL_STEP_MS ? remaining : DF_POLL_STEP_MS;
        if (step > 0) io->sleep_ms(ctx, (int)step);
    }
}

enum df_wait_result status_flow_run(struct status_io *io, void *ctx,
                                     int budget_ms, int grace_ms, int overall_ms,
                                     void (*cleanup_cb)(void *ctx),
                                     enum df_outcome *out)
{
    enum df_wait_result r;

    if (out) *out = DF_OUTCOME_NONE;

    /* Release the patched files exactly once, BEFORE the extended wait. The
     * soft-reboot command was already issued after the pending preflight, so
     * cleanup must not be delayed until a terminal outcome arrives. */
    if (cleanup_cb) cleanup_cb(ctx);

    r = consumer_await(io, ctx, budget_ms, grace_ms, overall_ms);
    if (out && io) *out = io->observed_outcome;
    return r;
}
