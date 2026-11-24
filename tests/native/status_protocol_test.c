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

    char nonce[DF_NONCE_CHARS + 1];       /* expected nonce for this run */
    char read_nonce[DF_NONCE_CHARS + 1];  /* nonce used to format the mock token */

    char token[DF_FINAL_TOKEN_CAP];
    size_t token_len;
    int token_valid;
    char late_token[DF_FINAL_TOKEN_CAP];
    size_t late_token_len;
    int late_token_valid;
    int token_at_read;          /* switch to late_token at this read count */
    char second_token[DF_FINAL_TOKEN_CAP];
    size_t second_token_len;
    int second_token_valid;
    int second_token_at_read;   /* switch to second_token at this read count */

    int read_calls;
    int publish_calls;
    int publish_fail_on;        /* 1-based publish call that fails, 0 = none */
    char published[32][DF_FINAL_TOKEN_CAP];
    char published_nonce[32][DF_NONCE_CHARS + 1];
    enum df_outcome published_o[32];

    int late_state;
    int late_state_script[16];
    int late_state_script_n;
    int late_state_calls;
    int late_state_advance_ms;  /* simulated time spent in one /proc scan */

    int child_rc;
    int child_calls;
    int child_publish_calls;    /* publish count when wait_child was invoked */
    int child_timeout;

    int cleanup_calls;
    int cleanup_at_read;        /* read count when cleanup ran (must be 0) */
    char report[4096];
};

static void mock_init(struct mock *m)
{
    memset(m, 0, sizeof(*m));
    m->now = 1000;
    m->late_state = 0;          /* confirmed absent by default */
    strcpy(m->nonce, "0123456789abcdef0123456789abcdef");
    strcpy(m->read_nonce, m->nonce);
}

static void format_token(char *dst, size_t *len,
                         const char *nonce, enum df_outcome o)
{
    int n = df_format_final_token(dst, DF_FINAL_TOKEN_CAP, nonce, o);
    assert(n > 0);
    *len = (size_t)n;
}

static void set_token(struct mock *m, enum df_outcome o)
{
    format_token(m->token, &m->token_len, m->read_nonce, o);
    m->token_valid = 1;
    m->late_token_valid = 0;
    m->token_at_read = 0;
}

static void set_late_token(struct mock *m, enum df_outcome o)
{
    format_token(m->late_token, &m->late_token_len, m->read_nonce, o);
    m->late_token_valid = 1;
}

static void set_second_token(struct mock *m, enum df_outcome o)
{
    format_token(m->second_token, &m->second_token_len, m->read_nonce, o);
    m->second_token_valid = 1;
}

/* Mock callbacks (forward-declared for make_io below). */
static int64_t m_now(void *c);
static void m_sleep(void *c, int ms);
static int m_read(void *c, char *buf, size_t cap, size_t *out_len);
static int m_publish(void *c, const char *nonce, enum df_outcome outcome);
static int m_late_state(void *c);
static int m_child(void *c, int timeout_ms);
static void m_report(void *c, const char *msg);

static struct status_io make_io(struct mock *m)
{
    struct status_io io;
    memset(&io, 0, sizeof(io));
    io.now_ms          = m_now;
    io.sleep_ms        = m_sleep;
    io.read_final      = m_read;
    io.publish_final   = m_publish;
    io.late_load_state = m_late_state;
    io.wait_child      = m_child;
    io.report          = m_report;
    io.nonce           = m->nonce;
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

static int m_read(void *c, char *buf, size_t cap, size_t *out_len)
{
    struct mock *m = c;
    const char *src = NULL;
    size_t n = 0;
    m->read_calls++;
    if (!buf || cap == 0 || !out_len) return -1;
    *out_len = 0;
    if (m->second_token_at_read && m->read_calls >= m->second_token_at_read &&
        m->second_token_valid) {
        src = m->second_token;
        n = m->second_token_len;
    } else if (m->token_at_read && m->read_calls >= m->token_at_read &&
               m->late_token_valid) {
        src = m->late_token;
        n = m->late_token_len;
    } else if (m->token_valid) {
        src = m->token;
        n = m->token_len;
    }
    if (!src) return -1; /* absent/unreadable: consumer keeps polling */
    if (n > cap) n = cap;
    memcpy(buf, src, n);
    *out_len = n;
    return 0;
}

static int m_publish(void *c, const char *nonce, enum df_outcome outcome)
{
    struct mock *m = c;
    enum df_outcome parsed = DF_OUTCOME_NONE;
    int i = m->publish_calls;
    m->publish_calls++;
    if (m->publish_fail_on && m->publish_calls == m->publish_fail_on)
        return -1;
    assert(i < 32);
    assert(df_format_final_token(m->published[i], DF_FINAL_TOKEN_CAP,
                                 nonce, outcome) > 0);
    /* Every published token must parse strictly under the caller's nonce. */
    assert(df_parse_final_token(m->published[i], strlen(m->published[i]),
                                nonce, &parsed) == 0);
    assert(parsed == outcome);
    snprintf(m->published_nonce[i], sizeof(m->published_nonce[i]), "%s", nonce);
    m->published_o[i] = outcome;
    return 0;
}

static int m_late_state(void *c)
{
    struct mock *m = c;
    int i = m->late_state_calls++;
    int v;
    m->now += m->late_state_advance_ms;
    if (m->late_state_script_n > 0) {
        if (i >= m->late_state_script_n) i = m->late_state_script_n - 1;
        v = m->late_state_script[i];
    } else {
        v = m->late_state;
    }
    return v;
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
    struct mock *m = c;
    m->cleanup_calls++;
    if (m->cleanup_calls == 1) m->cleanup_at_read = m->read_calls;
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
                                        int overall, enum df_outcome *out)
{
    struct status_io io = make_io(m);
    enum df_wait_result r = consumer_await(&io, m, budget, grace, overall);
    if (out) *out = io.observed_outcome;
    return r;
}

static void assert_reject(const char *token, size_t len, const char *nonce)
{
    enum df_outcome o = DF_OUTCOME_NONE;
    assert(df_parse_final_token(token, len, nonce, &o) != 0);
    assert(o == DF_OUTCOME_NONE);
}

int main(void)
{
    /* 1. Token round-trip and the full rc mapping (incl. fail-closed default). */
    {
        const enum df_outcome all[] = {
            DF_OUTCOME_NONE, ROOT_ONLY, REBOOT_REQUESTED, REBOOT_SKIPPED,
            REBOOT_FAILED, ERROR, REBOOT_PENDING, REBOOT_ATTEMPT_UNKNOWN
        };
        const int rcs[] = { 2, 0, 4, 5, 6, 1, 7, 7 };
        size_t i;
        for (i = 0; i < sizeof(all) / sizeof(all[0]); i++) {
            assert(df_outcome_from_token(df_outcome_token(all[i])) == all[i]);
            assert(df_outcome_to_rc(all[i]) == rcs[i]);
        }
        assert(df_outcome_to_rc((enum df_outcome)99) == 2);
        assert(df_nonce_is_valid(NULL) == 0);
        assert(df_nonce_is_valid("0123456789abcdef0123456789abcde") == 0);
        assert(df_nonce_is_valid("0123456789ABCDEF0123456789abcdef") == 0);
    }

    /* 2. Canonical v2 format is exact and round-trips. */
    {
        char buf[DF_FINAL_TOKEN_CAP];
        enum df_outcome o = DF_OUTCOME_NONE;
        int n = df_format_final_token(buf, sizeof(buf),
                                      "0123456789abcdef0123456789abcdef",
                                      ROOT_ONLY);
        assert(n == 53); /* 10 prefix + 32 nonce + 1 colon + 9 name + newline */
        assert(strcmp(buf, "dfroot-v2:0123456789abcdef0123456789abcdef:root_only\n") == 0);
        assert(df_parse_final_token(buf, (size_t)n,
                                    "0123456789abcdef0123456789abcdef", &o) == 0);
        assert(o == ROOT_ONLY);
        assert(df_format_final_token(buf, sizeof(buf), "bad", ROOT_ONLY) == -1);
        assert(df_format_final_token(buf, sizeof(buf),
                                     "0123456789abcdef0123456789abcdef",
                                     DF_OUTCOME_NONE) == -1);
    }

    /* 3. Strict parser: embedded NUL, truncation, extra bytes, stale/foreign
     * nonce, unknown/none outcome, malformed prefix/nonce all rejected. */
    {
        const char *nonce = "0123456789abcdef0123456789abcdef";
        char buf[DF_FINAL_TOKEN_CAP], bad[DF_FINAL_TOKEN_CAP];
        int n = df_format_final_token(buf, sizeof(buf), nonce, REBOOT_FAILED);
        enum df_outcome o;
        assert(n > 0);

        memcpy(bad, buf, (size_t)n);
        bad[12] = '\0'; /* embedded NUL */
        assert_reject(bad, (size_t)n, nonce);

        assert_reject(buf, (size_t)n - 1, nonce);  /* no trailing newline */
        assert_reject(buf, (size_t)n - 10, nonce); /* truncated mid-nonce */
        memcpy(bad, buf, (size_t)n);
        bad[n] = 'x';                              /* extra byte after '\n' */
        assert_reject(bad, (size_t)n + 1, nonce);
        memcpy(bad, buf, (size_t)n);
        bad[n] = '\n';                             /* two trailing newlines */
        assert_reject(bad, (size_t)n + 1, nonce);

        assert_reject(buf, (size_t)n, "fedcba9876543210fedcba9876543210"); /* stale */
        assert_reject(buf, (size_t)n, "0123456789abcdef0123456789abcdea"); /* foreign */

        snprintf(bad, sizeof(bad), "dfroot-v2:%s:nope\n", nonce);
        assert_reject(bad, strlen(bad), nonce);
        snprintf(bad, sizeof(bad), "dfroot-v2:%s:none\n", nonce);
        assert_reject(bad, strlen(bad), nonce);
        snprintf(bad, sizeof(bad), "%s:%s:root_only\n", nonce, nonce);
        assert_reject(bad, strlen(bad), nonce); /* missing prefix */
        snprintf(bad, sizeof(bad), "dfroot-v2:%sroot_only\n", nonce);
        assert_reject(bad, strlen(bad), nonce); /* missing colon */
        snprintf(bad, sizeof(bad), "dfroot-v2:%s:root_only", nonce);
        assert_reject(bad, strlen(bad), nonce); /* missing newline */
        snprintf(bad, sizeof(bad), "dfroot-v2:%s:root_only\n",
                 "0123456789ABCDEF0123456789abcdef");
        assert_reject(bad, strlen(bad), "0123456789ABCDEF0123456789abcdef");
        snprintf(bad, sizeof(bad), "root_only\n"); /* legacy v1 token */
        assert_reject(bad, strlen(bad), nonce);
        o = DF_OUTCOME_NONE;
        assert(df_parse_final_token(NULL, 0, nonce, &o) != 0);
    }

    /* 4. late-load failure -> ERROR published, no reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        o = run_producer(&m, 1, 1);
        assert(o == ERROR);
        assert(last_published(&m) == ERROR);
        assert(m.publish_calls == 1);
        assert(m.child_calls == 0);
    }

    /* 5. soft_reboot off -> ROOT_ONLY ALONE, terminal, no preflight/no reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        o = run_producer(&m, 0, 0);
        assert(o == ROOT_ONLY);
        assert(m.publish_calls == 1);
        assert(m.published_o[0] == ROOT_ONLY);
        assert(m.child_calls == 0);
        assert(strstr(m.report, "no reboot requested") != NULL);
    }

    /* 6. soft_reboot + unknown late-load state -> REBOOT_SKIPPED, no reboot. */
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

    /* 7. soft_reboot + late-load still running at deadline -> REBOOT_SKIPPED. */
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

    /* 8. running then confirmed absent -> REBOOT_PENDING preflight BEFORE the
     * reboot command; ROOT_ONLY is never used as a reboot preflight. */
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
        assert(m.child_publish_calls == 1);
        assert(m.published_o[0] == REBOOT_PENDING);
        assert(m.published_o[1] == REBOOT_REQUESTED);
    }

    /* 9. preflight publish failure -> NONE and no reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.publish_fail_on = 1;
        o = run_producer(&m, 0, 1);
        assert(o == DF_OUTCOME_NONE);
        assert(m.child_calls == 0);
    }

    /* 10. child exit 0 -> REBOOT_REQUESTED (issued, not confirmed). */
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

    /* 11. child nonzero exit -> REBOOT_FAILED. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.child_rc = 7;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_FAILED);
        assert(last_published(&m) == REBOOT_FAILED);
    }

    /* 12. child wait error/timeout -> REBOOT_FAILED. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.child_rc = -1;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_FAILED);
        assert(last_published(&m) == REBOOT_FAILED);
    }

    /* 13. refinement publish failure -> REBOOT_ATTEMPT_UNKNOWN (NEVER a
     * fallback to ROOT_ONLY, which would deny that a reboot was requested). */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.child_rc = 0;
        m.publish_fail_on = 2;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_ATTEMPT_UNKNOWN);
        assert(m.publish_calls == 3);
        assert(m.published_o[0] == REBOOT_PENDING);
        assert(m.published_o[2] == REBOOT_ATTEMPT_UNKNOWN);
        assert(m.child_calls == 1);
        assert(strstr(m.report, "attempt unknown") != NULL);
    }

    /* 14. ERROR publish failure -> NONE (fail closed, no reboot). */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.publish_fail_on = 1;
        o = run_producer(&m, 9, 1);
        assert(o == DF_OUTCOME_NONE);
        assert(m.child_calls == 0);
    }

    /* 15. a "confirmed absent" observation arriving AFTER the deadline is
     * never accepted as authorization for a reboot. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.late_state_script[0] = 0;
        m.late_state_script_n = 1;
        m.late_state_advance_ms = 300; /* /proc scan outlives the 200 ms wait */
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_SKIPPED);
        assert(m.child_calls == 0);
        assert(strstr(m.report, "deadline expired") != NULL);
    }

    /* 16. ROOT_ONLY is terminal immediately: it means no reboot was requested,
     * so even a large grace is ignored. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        assert(run_consumer(&m, 1000, 5000, 10000, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(m.read_calls == 1);
        assert(strstr(m.report, "no reboot requested") != NULL);
    }

    /* 17. REBOOT_PENDING then REBOOT_REQUESTED inside grace -> refined. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        set_late_token(&m, REBOOT_REQUESTED);
        m.token_at_read = 3;
        assert(run_consumer(&m, 1000, 200, 2000, &o) == DONE);
        assert(o == REBOOT_REQUESTED);
        assert(m.read_calls >= 3);
    }

    /* 18. REBOOT_PENDING then REBOOT_FAILED inside grace -> refined. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        set_late_token(&m, REBOOT_FAILED);
        m.token_at_read = 2;
        assert(run_consumer(&m, 1000, 200, 2000, &o) == DONE);
        assert(o == REBOOT_FAILED);
    }

    /* 19. pending timeout -> terminal REBOOT_ATTEMPT_UNKNOWN. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        assert(run_consumer(&m, 1000, 50, 2000, &o) == DONE);
        assert(o == REBOOT_ATTEMPT_UNKNOWN);
        assert(df_outcome_to_rc(o) == 7);
        assert(m.now >= 1050);
        assert(strstr(m.report, "ATTEMPT_UNKNOWN") != NULL);
    }

    /* 20. grace is NOT clamped to the budget deadline: a pending observed at
     * t=1090 with a 100 ms budget still gets its 10 s grace past t=1100. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        m.token_valid = 0; /* no preflight before read #10 */
        set_late_token(&m, REBOOT_PENDING);
        m.token_at_read = 10; /* t=1090 */
        set_second_token(&m, REBOOT_REQUESTED);
        m.second_token_at_read = 13; /* t=1120, past the 1100 ms budget */
        assert(run_consumer(&m, 100, 50, 1000, &o) == DONE);
        assert(o == REBOOT_REQUESTED);
        assert(m.read_calls == 13);
        assert(m.now == 1120);

        mock_init(&m);
        m.token_valid = 0;
        set_late_token(&m, REBOOT_PENDING);
        m.token_at_read = 10;
        assert(run_consumer(&m, 100, 50, 1000, &o) == DONE);
        assert(o == REBOOT_ATTEMPT_UNKNOWN);
        assert(m.now >= 1140); /* past the 1100 ms budget deadline */

        /* A refinement at t=1120 is past the budget but inside the grace. */
        mock_init(&m);
        m.token_valid = 0;
        set_late_token(&m, REBOOT_PENDING);
        m.token_at_read = 10;
        set_second_token(&m, REBOOT_FAILED);
        m.second_token_at_read = 13;
        assert(run_consumer(&m, 100, 50, 1000, &o) == DONE);
        assert(o == REBOOT_FAILED);
    }

    /* 21. grace IS clamped to the overall deadline (85 s in exp.c). */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        m.token_valid = 0;
        set_late_token(&m, REBOOT_PENDING);
        m.token_at_read = 10; /* t=1090 */
        assert(run_consumer(&m, 100, 50, 120, &o) == DONE);
        assert(o == REBOOT_ATTEMPT_UNKNOWN);
        assert(m.now == 1120); /* min(1140, start+120) */
    }

    /* 22. REBOOT_SKIPPED / REBOOT_FAILED / REBOOT_ATTEMPT_UNKNOWN are terminal
     * immediately, with no grace window. */
    {
        const enum df_outcome terminals[] = {
            REBOOT_SKIPPED, REBOOT_FAILED, REBOOT_ATTEMPT_UNKNOWN
        };
        size_t i;
        for (i = 0; i < sizeof(terminals) / sizeof(terminals[0]); i++) {
            struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
            mock_init(&m);
            set_token(&m, terminals[i]);
            assert(run_consumer(&m, 10000, 5000, 20000, &o) == DONE);
            assert(o == terminals[i]);
            assert(m.read_calls == 1);
        }
    }

    /* 23. ERROR is terminal immediately and maps to rc=1. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ERROR);
        assert(run_consumer(&m, 10000, 5000, 20000, &o) == DONE);
        assert(o == ERROR);
        assert(df_outcome_to_rc(o) == 1);
        assert(strstr(m.report, "root not") == NULL);
    }

    /* 24. garbled tokens are ignored; a later valid pending wins. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        strcpy(m.token, "dfroot-v2:bad:root_only\n");
        m.token_len = strlen(m.token);
        m.token_valid = 1;
        set_late_token(&m, ERROR);
        m.token_at_read = 4;
        assert(run_consumer(&m, 1000, 0, 2000, &o) == DONE);
        assert(o == ERROR);
        assert(m.read_calls >= 4);
    }

    /* 25. a stale/foreign nonce token is ignored, never accepted as final. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        strcpy(m.read_nonce, "fedcba9876543210fedcba9876543210");
        set_token(&m, ERROR);
        assert(run_consumer(&m, 50, 20, 100, &o) == TIMEOUT);
        assert(o == DF_OUTCOME_NONE);
        assert(m.read_calls > 1);
    }

    /* 26. read_final errors keep polling; checked deadline never wraps. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        m.now = INT64_MAX - 20;
        assert(run_consumer(&m, 100, 50, 200, &o) == TIMEOUT);
        assert(o == DF_OUTCOME_NONE);
        assert(m.read_calls > 1);
        assert(m.now >= INT64_MAX - 20 && m.now <= INT64_MAX);
    }

    /* 27. status_flow_run calls cleanup exactly once BEFORE the first read,
     * on DONE and on TIMEOUT. */
    {
        struct mock m, n;
        struct status_io io;
        enum df_outcome o;
        mock_init(&m);
        set_token(&m, ERROR);
        io = make_io(&m);
        assert(status_flow_run(&io, &m, 1000, 100, 2000, m_cleanup, &o) == DONE);
        assert(o == ERROR);
        assert(m.cleanup_calls == 1);
        assert(m.cleanup_at_read == 0); /* cleanup happened before any read */
        assert(m.read_calls == 1);

        mock_init(&n); /* no final file: poll until timeout */
        io = make_io(&n);
        assert(status_flow_run(&io, &n, 80, 20, 160, m_cleanup, &o) == TIMEOUT);
        assert(o == DF_OUTCOME_NONE);
        assert(n.cleanup_calls == 1);
        assert(n.cleanup_at_read == 0);
        assert(n.read_calls > 0);
    }

    /* 28. clock failure at producer start: no deadline can be enforced, so
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

    /* 29. clock failure during the late-load wait: skip reboot, publish a
     * truthful REBOOT_SKIPPED rather than freezing on a sentinel clock. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.late_state = 1;
        m.now_fail_from = 2;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_SKIPPED);
        assert(last_published(&m) == REBOOT_SKIPPED);
        assert(m.child_calls == 0);
    }

    /* 30. clock failure after confirmed absence: no reboot authorization. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.late_state_script[0] = 0;
        m.late_state_script_n = 1;
        m.now_fail_from = 2; /* start ok, post-scan check fails */
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_SKIPPED);
        assert(m.child_calls == 0);
    }

    /* 31. consumer clock failure before the first read -> ERR, no outcome. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        m.now_fail_from = 1;
        assert(run_consumer(&m, 1000, 10000, 20000, &o) == ERR);
        assert(o == DF_OUTCOME_NONE);
        assert(m.read_calls == 0);
    }

    /* 32. consumer clock failure after a read -> ERR, never accept a pending
     * observation on an unchecked (frozen) deadline. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        m.now_fail_from = 3;
        assert(run_consumer(&m, 1000, 10000, 20000, &o) == ERR);
        assert(o == DF_OUTCOME_NONE);
        assert(m.read_calls == 1);
    }

    /* 33. boundary: a refinement at the last observable read before the grace
     * deadline is still accepted (10 ms granularity, checked deadline). */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        set_late_token(&m, REBOOT_FAILED);
        m.token_at_read = 1000; /* t=10990; grace deadline t=11000 */
        assert(run_consumer(&m, 70000, 10000, 85000, &o) == DONE);
        assert(o == REBOOT_FAILED);
        assert(m.read_calls == 1000);
    }

    /* 34. boundary: a refinement exactly at the grace deadline is NOT
     * accepted; the pending attempt is reported as unknown. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        set_late_token(&m, REBOOT_FAILED);
        m.token_at_read = 1001; /* t=11000 == deadline, never read */
        assert(run_consumer(&m, 70000, 10000, 85000, &o) == DONE);
        assert(o == REBOOT_ATTEMPT_UNKNOWN);
        assert(m.read_calls == 1000);
    }

    puts("status_protocol_test: all 34 cases passed");
    return 0;
}
