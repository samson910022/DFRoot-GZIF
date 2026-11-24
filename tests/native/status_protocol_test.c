/* Host-side test for the pure status protocol. No /dev files are touched:
 * every operation is an injected mock callback. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../../app/src/main/jni/status_protocol.h"

struct mock {
    int64_t now;
    int now_calls;
    int now_fail_from;          /* 1-based now_ms call at which the clock fails, 0=never */

    char token[128];
    int token_valid;
    char late_token[128];
    int late_token_valid;
    int token_at_read;          /* switch to late_token at this read count */

    int read_calls;
    int publish_calls;
    int publish_fail_on;        /* 1-based publish call that fails, 0 = none */
    char published[32][128];
    enum df_outcome published_o[32];

    int late_state;
    int late_state_script[16];
    int late_state_script_n;
    int late_state_calls;

    int child_rc;
    int child_calls;
    int child_publish_calls;    /* publish count when wait_child was invoked */
    int child_timeout;

    int cleanup_calls;
    char report[4096];
};

static void mock_init(struct mock *m)
{
    memset(m, 0, sizeof(*m));
    m->now = 1000;
    m->late_state = 0;          /* confirmed absent by default */
}

static void set_token(struct mock *m, enum df_outcome o)
{
    snprintf(m->token, sizeof(m->token), "%s", df_outcome_token(o));
    m->token_valid = 1;
    m->late_token_valid = 0;
    m->token_at_read = 0;
}

/* Mock callbacks (forward-declared for make_io below). */
static int64_t m_now(void *c);
static void m_sleep(void *c, int ms);
static int m_read(void *c, char *buf, size_t cap);
static int m_publish(void *c, const char *token);
static int m_late_state(void *c);
static int m_child(void *c, int timeout_ms);
static void m_report(void *c, const char *msg);

static struct status_io make_io(struct mock *m)
{
    struct status_io io;
    (void)m;
    memset(&io, 0, sizeof(io));
    io.now_ms          = m_now;
    io.sleep_ms        = m_sleep;
    io.read_final      = m_read;
    io.publish_final   = m_publish;
    io.late_load_state = m_late_state;
    io.wait_child      = m_child;
    io.report          = m_report;
    return io;
}

static int64_t m_now(void *c)
{
    struct mock *m = c;
    m->now_calls++;
    if (m->now_fail_from && m->now_calls >= m->now_fail_from)
        return -1; /* simulated clock failure: callers must fail closed */
    return m->now;
}

static void m_sleep(void *c, int ms)
{
    struct mock *m = c;
    if (ms > 0) m->now += ms;
}

static int m_read(void *c, char *buf, size_t cap)
{
    struct mock *m = c;
    const char *src = NULL;
    m->read_calls++;
    if (m->token_at_read && m->read_calls >= m->token_at_read &&
        m->late_token_valid)
        src = m->late_token;
    else if (m->token_valid)
        src = m->token;
    if (!src) return -1;        /* absent/unreadable: consumer keeps polling */
    snprintf(buf, cap, "%s", src);
    return 0;
}

static int m_publish(void *c, const char *token)
{
    struct mock *m = c;
    enum df_outcome o = df_outcome_from_token(token);
    m->publish_calls++;
    if (m->publish_fail_on && m->publish_calls == m->publish_fail_on)
        return -1;
    snprintf(m->published[m->publish_calls - 1],
             sizeof(m->published[0]), "%s", token);
    m->published_o[m->publish_calls - 1] = o;
    return 0;
}

static int m_late_state(void *c)
{
    struct mock *m = c;
    int i = m->late_state_calls++;
    if (m->late_state_script_n > 0) {
        if (i >= m->late_state_script_n) i = m->late_state_script_n - 1;
        return m->late_state_script[i];
    }
    return m->late_state;
}

static int m_child(void *c, int timeout_ms)
{
    struct mock *m = c;
    m->child_calls++;
    m->child_publish_calls = m->publish_calls;
    m->child_timeout = timeout_ms;
    return m->child_rc;
}

static void m_report(void *c, const char *msg)
{
    struct mock *m = c;
    size_t used = strlen(m->report);
    strncat(m->report, msg, sizeof(m->report) - used - 1);
    used = strlen(m->report);
    strncat(m->report, "\n", sizeof(m->report) - used - 1);
}

static void m_cleanup(void *c)
{
    ((struct mock *)c)->cleanup_calls++;
}

static enum df_outcome last_published(struct mock *m)
{
    if (m->publish_calls == 0) return DF_OUTCOME_NONE;
    return m->published_o[m->publish_calls - 1];
}

static enum df_outcome run_producer(struct mock *m, int ll_rc, int soft)
{
    struct status_io io = make_io(m);
    return producer_decide_and_publish(&io, m, ll_rc, soft, 200, 100);
}

static enum df_wait_result run_consumer(struct mock *m, int budget, int grace,
                                        enum df_outcome *out)
{
    struct status_io io = make_io(m);
    enum df_wait_result r = consumer_await(&io, m, budget, grace);
    if (out) *out = io.observed_outcome;
    return r;
}

int main(void)
{
    /* 1. Token round-trip and the full rc mapping (incl. fail-closed default). */
    {
        const enum df_outcome all[] = {
            DF_OUTCOME_NONE, ROOT_ONLY, REBOOT_REQUESTED,
            REBOOT_SKIPPED, REBOOT_FAILED, ERROR
        };
        const int rcs[] = { 2, 0, 4, 5, 6, 1 };
        size_t i;
        for (i = 0; i < sizeof(all) / sizeof(all[0]); i++) {
            assert(df_outcome_from_token(df_outcome_token(all[i])) == all[i]);
            assert(df_outcome_to_rc(all[i]) == rcs[i]);
        }
        assert(df_outcome_to_rc((enum df_outcome)99) == 2);
    }

    /* 2. Garbled / whitespace / empty tokens map to NONE, never a false rc. */
    {
        assert(df_outcome_from_token(NULL) == DF_OUTCOME_NONE);
        assert(df_outcome_from_token("") == DF_OUTCOME_NONE);
        assert(df_outcome_from_token("garbage") == DF_OUTCOME_NONE);
        assert(df_outcome_from_token("root_only extra") == DF_OUTCOME_NONE);
        assert(df_outcome_from_token("  reboot_requested\n") == REBOOT_REQUESTED);
    }

    /* 3. late-load failure -> ERROR published, no reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        o = run_producer(&m, 1, 1);
        assert(o == ERROR);
        assert(last_published(&m) == ERROR);
        assert(m.publish_calls == 1);
        assert(m.child_calls == 0);
    }

    /* 4. soft_reboot off -> ROOT_ONLY, no reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        o = run_producer(&m, 0, 0);
        assert(o == ROOT_ONLY);
        assert(last_published(&m) == ROOT_ONLY);
        assert(m.child_calls == 0);
    }

    /* 5. soft_reboot + unknown late-load state -> REBOOT_SKIPPED, no reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.late_state = -1;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_SKIPPED);
        assert(last_published(&m) == REBOOT_SKIPPED);
        assert(m.child_calls == 0);
        assert(strstr(m.report, "unknown") != NULL);
    }

    /* 6. soft_reboot + late-load still running at deadline -> REBOOT_SKIPPED. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.late_state = 1;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_SKIPPED);
        assert(last_published(&m) == REBOOT_SKIPPED);
        assert(m.child_calls == 0);
        assert(m.late_state_calls > 1);
        assert(strstr(m.report, "timed out") != NULL);
    }

    /* 7. running then confirmed absent -> ROOT_ONLY preflight BEFORE reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.late_state_script[0] = 1;
        m.late_state_script[1] = 1;
        m.late_state_script[2] = 0;
        m.late_state_script_n = 3;
        m.child_rc = 0;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_REQUESTED);
        assert(m.child_calls == 1);
        assert(m.child_publish_calls == 1); /* ROOT_ONLY was published first */
        assert(m.published_o[0] == ROOT_ONLY);
        assert(m.published_o[1] == REBOOT_REQUESTED);
    }

    /* 8. preflight publish failure -> NONE and no reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.publish_fail_on = 1;
        o = run_producer(&m, 0, 1);
        assert(o == DF_OUTCOME_NONE);
        assert(m.child_calls == 0);
    }

    /* 9. child exit 0 -> REBOOT_REQUESTED (issued, not confirmed). */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.child_rc = 0;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_REQUESTED);
        assert(last_published(&m) == REBOOT_REQUESTED);
        assert(m.child_timeout == 100);
        assert(strstr(m.report, "not confirmed") != NULL);
    }

    /* 10. child nonzero exit -> REBOOT_FAILED. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.child_rc = 7;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_FAILED);
        assert(last_published(&m) == REBOOT_FAILED);
    }

    /* 11. child wait error/timeout -> REBOOT_FAILED. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.child_rc = -1;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_FAILED);
        assert(last_published(&m) == REBOOT_FAILED);
    }

    /* 12. refinement publish failure leaves ROOT_ONLY as the final status. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.child_rc = 0;
        m.publish_fail_on = 2;
        o = run_producer(&m, 0, 1);
        assert(o == ROOT_ONLY);
        assert(m.publish_calls == 2);
        assert(m.published_o[0] == ROOT_ONLY);
        assert(m.child_calls == 1);
        assert(strstr(m.report, "stays ROOT_ONLY") != NULL);
    }

    /* 13. ERROR publish failure -> NONE (fail closed, no reboot). */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.publish_fail_on = 1;
        o = run_producer(&m, 9, 1);
        assert(o == DF_OUTCOME_NONE);
        assert(m.child_calls == 0);
    }

    /* 14. consumer: ROOT_ONLY is terminal - immediately (grace 0) or after
     * its bounded grace window expires with no refinement. */
    {
        struct mock m, n; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        assert(run_consumer(&m, 1000, 0, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(strstr(m.report, "not verified") != NULL);

        mock_init(&n);
        set_token(&n, ROOT_ONLY);
        assert(run_consumer(&n, 1000, 50, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(n.now >= 1050); /* the rebased grace window actually elapsed */
    }

    /* 15. consumer: ROOT_ONLY then REBOOT_REQUESTED inside grace -> refined. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        snprintf(m.late_token, sizeof(m.late_token), "%s",
                 df_outcome_token(REBOOT_REQUESTED));
        m.late_token_valid = 1;
        m.token_at_read = 3;
        assert(run_consumer(&m, 1000, 200, &o) == DONE);
        assert(o == REBOOT_REQUESTED);
        assert(m.read_calls >= 3);
    }

    /* 16. consumer: ROOT_ONLY then REBOOT_FAILED inside grace -> refined. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        snprintf(m.late_token, sizeof(m.late_token), "%s",
                 df_outcome_token(REBOOT_FAILED));
        m.late_token_valid = 1;
        m.token_at_read = 2;
        assert(run_consumer(&m, 1000, 200, &o) == DONE);
        assert(o == REBOOT_FAILED);
    }

    /* 17. consumer: REBOOT_SKIPPED is terminal immediately (no grace wait). */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_SKIPPED);
        assert(run_consumer(&m, 10000, 5000, &o) == DONE);
        assert(o == REBOOT_SKIPPED);
        assert(m.read_calls == 1);
    }

    /* 18. consumer: ERROR is terminal immediately and maps to rc=1. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ERROR);
        assert(run_consumer(&m, 10000, 5000, &o) == DONE);
        assert(o == ERROR);
        assert(df_outcome_to_rc(o) == 1);
        assert(strstr(m.report, "root not") == NULL);
    }

    /* 19. consumer: garbled tokens are ignored; a later valid token wins. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        snprintf(m.token, sizeof(m.token), "garbage");
        m.token_valid = 1;
        snprintf(m.late_token, sizeof(m.late_token), "%s",
                 df_outcome_token(ROOT_ONLY));
        m.late_token_valid = 1;
        m.token_at_read = 4;
        assert(run_consumer(&m, 1000, 0, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(m.read_calls >= 4);
    }

    /* 20. read_final errors keep polling; checked deadline never wraps. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        m.now = INT64_MAX - 20;
        assert(run_consumer(&m, 100, 50, &o) == TIMEOUT);
        assert(o == DF_OUTCOME_NONE);
        assert(m.read_calls > 1);
        assert(m.now >= INT64_MAX - 20 && m.now <= INT64_MAX);
    }

    /* 21. status_flow_run calls cleanup exactly once on DONE and on TIMEOUT. */
    {
        struct mock m, n;
        struct status_io io;
        enum df_outcome o;
        mock_init(&m);
        set_token(&m, ERROR);
        io = make_io(&m);
        assert(status_flow_run(&io, &m, 1000, 100, m_cleanup, &o) == DONE);
        assert(o == ERROR);
        assert(m.cleanup_calls == 1);

        mock_init(&n); /* no final file: poll until timeout */
        io = make_io(&n);
        assert(status_flow_run(&io, &n, 80, 20, m_cleanup, &o) == TIMEOUT);
        assert(o == DF_OUTCOME_NONE);
        assert(n.cleanup_calls == 1);
    }

    /* 22. clock failure at producer start: no deadline can be enforced, so
     * no reboot is authorized and no outcome is published (rc=2/unknown). */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.now_fail_from = 1;
        o = run_producer(&m, 0, 1);
        assert(o == DF_OUTCOME_NONE);
        assert(m.child_calls == 0);
        assert(m.publish_calls == 0);
        assert(strstr(m.report, "clock unavailable") != NULL);
    }

    /* 23. clock failure during the late-load wait: skip reboot, publish a
     * truthful REBOOT_SKIPPED rather than freezing on a sentinel clock. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.late_state = 1;
        m.now_fail_from = 2; /* start succeeds, first in-loop check fails */
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_SKIPPED);
        assert(last_published(&m) == REBOOT_SKIPPED);
        assert(m.child_calls == 0);
    }

    /* 24. consumer clock failure before the first read -> ERR, no outcome. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        m.now_fail_from = 1;
        assert(run_consumer(&m, 1000, 10000, &o) == ERR);
        assert(o == DF_OUTCOME_NONE);
        assert(m.read_calls == 0);
    }

    /* 25. consumer clock failure after a read -> ERR, never accept ROOT_ONLY
     * on an unchecked (frozen) deadline. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        m.now_fail_from = 3; /* budget, loop top, then post-read check */
        assert(run_consumer(&m, 1000, 10000, &o) == ERR);
        assert(o == DF_OUTCOME_NONE);
        assert(m.read_calls == 1);
    }

    /* 26. boundary: refinement arrives 5010 ms after ROOT_ONLY was first
     * observed - just PAST the old 5 s grace (and the 5 s child wait), still
     * inside the new 10 s grace. The old value would time out as ROOT_ONLY. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        snprintf(m.late_token, sizeof(m.late_token), "%s",
                 df_outcome_token(REBOOT_REQUESTED));
        m.late_token_valid = 1;
        m.token_at_read = 502; /* read #1 at t=1000; #502 at t=6010 */
        assert(run_consumer(&m, 70000, 10000, &o) == DONE);
        assert(o == REBOOT_REQUESTED);
        assert(m.read_calls == 502);
    }

    /* 27. boundary: a refinement at the last observable read before the grace
     * deadline is still accepted (10 ms granularity, checked deadline). */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        snprintf(m.late_token, sizeof(m.late_token), "%s",
                 df_outcome_token(REBOOT_FAILED));
        m.late_token_valid = 1;
        m.token_at_read = 1000; /* t=10990; deadline t=11000 */
        assert(run_consumer(&m, 70000, 10000, &o) == DONE);
        assert(o == REBOOT_FAILED);
        assert(m.read_calls == 1000);
    }

    /* 28. boundary: a token arriving exactly at the grace deadline is NOT
     * accepted; the deadline check wins with no extra unbounded wait. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        snprintf(m.late_token, sizeof(m.late_token), "%s",
                 df_outcome_token(REBOOT_FAILED));
        m.late_token_valid = 1;
        m.token_at_read = 1001; /* t=11000 == deadline, never read */
        assert(run_consumer(&m, 70000, 10000, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(m.read_calls == 1000);
    }

    puts("status_protocol_test: all 28 cases passed");
    return 0;
}
