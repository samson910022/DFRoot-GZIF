#include "status_protocol.h"

#include <string.h>

/* Polling granularity. Kept small so a late refinement inside the grace
 * window is observed promptly, but never used as the deadline itself: all
 * deadlines are absolute, checked, monotonic int64 values. */
#define DF_POLL_STEP_MS 10
#define DF_TOKEN_CAP    128

static const char *const kTokens[] = {
    "none",             /* DF_OUTCOME_NONE (never published) */
    "root_only",        /* ROOT_ONLY */
    "reboot_requested", /* REBOOT_REQUESTED */
    "reboot_skipped",   /* REBOOT_SKIPPED */
    "reboot_failed",    /* REBOOT_FAILED */
    "error"             /* ERROR */
};

const char *df_outcome_token(enum df_outcome outcome)
{
    if ((int)outcome < (int)DF_OUTCOME_NONE || (int)outcome > (int)ERROR)
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
    for (i = (int)DF_OUTCOME_NONE; i <= (int)ERROR; i++) {
        size_t tn = strlen(kTokens[i]);
        if (tn == n && memcmp(token, kTokens[i], n) == 0)
            return (enum df_outcome)i;
    }
    return DF_OUTCOME_NONE;
}

int df_outcome_to_rc(enum df_outcome outcome)
{
    switch (outcome) {
    case ROOT_ONLY:        return 0;
    case ERROR:            return 1;
    case DF_OUTCOME_NONE:  return 2;
    case REBOOT_REQUESTED: return 4;
    case REBOOT_SKIPPED:   return 5;
    case REBOOT_FAILED:    return 6;
    default:               return 2; /* fail closed for out-of-range values */
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
    if (io->publish_final(ctx, df_outcome_token(outcome)) != 0)
        return DF_OUTCOME_NONE;
    return outcome;
}

enum df_outcome producer_decide_and_publish(struct status_io *io, void *ctx,
                                             int late_load_rc, int soft_reboot,
                                             int late_load_wait_ms,
                                             int child_wait_ms)
{
    int64_t start, deadline;
    int child_rc;
    enum df_outcome refined;

    /* Fail closed: without a working clock/sleep/state/publish/wait we must
     * neither claim success nor authorize a reboot. */
    if (!io || !io->now_ms || !io->sleep_ms || !io->late_load_state ||
        !io->publish_final || !io->wait_child)
        return DF_OUTCOME_NONE;

    if (late_load_rc != 0) {
        io_report(io, ctx, "bootstrap: late-load failed; no reboot, publishing ERROR");
        if (io->publish_final(ctx, df_outcome_token(ERROR)) != 0) {
            io_report(io, ctx, "bootstrap: failed to publish final ERROR status");
            return DF_OUTCOME_NONE;
        }
        return ERROR;
    }

    if (!soft_reboot) {
        if (io->publish_final(ctx, df_outcome_token(ROOT_ONLY)) != 0) {
            io_report(io, ctx, "bootstrap: failed to publish final ROOT_ONLY status");
            return DF_OUTCOME_NONE;
        }
        io_report(io, ctx, "bootstrap: root only - late-load exited 0; root not verified");
        return ROOT_ONLY;
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
        int64_t now, remaining, step;

        if (st < 0) {
            io_report(io, ctx, "bootstrap: late-load state unknown; skipping soft reboot");
            return publish_or_none(io, ctx, REBOOT_SKIPPED);
        }
        if (st == 0) break; /* confirmed absent */

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

    /* Confirmed absent. Publish ROOT_ONLY as a preflight: if this fails we
     * refuse to reboot, because the consumer would have no final status. */
    if (io->publish_final(ctx, df_outcome_token(ROOT_ONLY)) != 0) {
        io_report(io, ctx, "bootstrap: preflight publish failed; refusing to reboot");
        return DF_OUTCOME_NONE;
    }

    io_report(io, ctx, "bootstrap: issuing soft-reboot command (not a confirmation)");
    child_rc = io->wait_child(ctx, child_wait_ms);
    refined = (child_rc == 0) ? REBOOT_REQUESTED : REBOOT_FAILED;
    io_report(io, ctx, child_rc == 0
              ? "bootstrap: soft-reboot command exited 0; reboot not confirmed by app"
              : "bootstrap: soft-reboot command failed or timed out");

    /* If refinement cannot be published, the preflight ROOT_ONLY remains the
     * single source of truth; do not falsely report REBOOT_{REQUESTED,FAILED}. */
    if (io->publish_final(ctx, df_outcome_token(refined)) != 0) {
        io_report(io, ctx, "bootstrap: refinement publish failed; final stays ROOT_ONLY");
        return ROOT_ONLY;
    }
    return refined;
}

static const char *final_message(enum df_outcome outcome)
{
    switch (outcome) {
    case ROOT_ONLY:        return "final: ROOT_ONLY - late-load exited 0; root not verified";
    case REBOOT_REQUESTED: return "final: REBOOT_REQUESTED - soft-reboot command exited 0; reboot not confirmed";
    case REBOOT_SKIPPED:   return "final: REBOOT_SKIPPED - soft reboot not attempted (late-load not confirmed absent)";
    case REBOOT_FAILED:    return "final: REBOOT_FAILED - soft-reboot command failed or timed out";
    case ERROR:            return "final: ERROR - bootstrap failed before root";
    default:               return "final: no final status observed";
    }
}

enum df_wait_result consumer_await(struct status_io *io, void *ctx,
                                   int budget_ms, int grace_ms)
{
    int64_t start, budget_deadline, deadline;
    int saw_root_only = 0;
    char buf[DF_TOKEN_CAP];

    if (!io || !io->now_ms || !io->read_final || !io->sleep_ms)
        return ERR;
    io->observed_outcome = DF_OUTCOME_NONE;

    start = io->now_ms(ctx);
    if (start < 0)
        return ERR; /* no clock: cannot bound the wait */
    budget_deadline = deadline_after(start, budget_ms);
    deadline = budget_deadline;

    for (;;) {
        int64_t now = io->now_ms(ctx);
        int64_t remaining, step;
        int rc;

        if (now < 0)
            return ERR;

        /* Checked monotonic deadline: no wraparound, no elapsed counters.
         * ROOT_ONLY is itself terminal: once its grace window has expired
         * without a refinement, accept it rather than reporting a generic
         * timeout (which would misdescribe an observed final status). */
        if (now >= deadline) {
            if (saw_root_only) {
                io->observed_outcome = ROOT_ONLY;
                io_report(io, ctx, final_message(ROOT_ONLY));
                return DONE;
            }
            return TIMEOUT;
        }

        rc = io->read_final(ctx, buf, sizeof(buf));
        if (rc == 0) {
            enum df_outcome o = df_outcome_from_token(buf);
            if (o == ROOT_ONLY && !saw_root_only) {
                saw_root_only = 1;
                if (grace_ms <= 0) {
                    io->observed_outcome = ROOT_ONLY;
                    io_report(io, ctx, final_message(ROOT_ONLY));
                    return DONE;
                } else {
                    int64_t g = deadline_after(now, grace_ms);
                    deadline = (g < budget_deadline) ? g : budget_deadline;
                    io_report(io, ctx,
                              "final: ROOT_ONLY observed - root not verified; allowing bounded time for a reboot outcome");
                }
            } else if (o != DF_OUTCOME_NONE && o != ROOT_ONLY) {
                io->observed_outcome = o;
                io_report(io, ctx, final_message(o));
                return DONE;
            }
            /* DF_OUTCOME_NONE: garbled/absent token - ignore, keep polling. */
        }
        /* rc < 0: read_final unavailable - keep polling until the deadline. */

        now = io->now_ms(ctx);
        if (now < 0)
            return ERR;
        if (now >= deadline) {
            if (saw_root_only) {
                io->observed_outcome = ROOT_ONLY;
                io_report(io, ctx, final_message(ROOT_ONLY));
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
                                    int budget_ms, int grace_ms,
                                    void (*cleanup_cb)(void *ctx),
                                    enum df_outcome *out)
{
    enum df_wait_result r;

    if (out) *out = DF_OUTCOME_NONE;
    r = consumer_await(io, ctx, budget_ms, grace_ms);
    if (out && io) *out = io->observed_outcome;

    /* Exactly once, promptly after a terminal observation or a timeout. The
     * callback may be NULL (e.g. host tests that only check the flow result). */
    if (cleanup_cb) cleanup_cb(ctx);
    return r;
}
