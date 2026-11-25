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
    int cleanup_at_read;        /* read count when cleanup ran */
    int cleanup_at_ms;          /* mock clock when cleanup ran */
    int cleanup_consumes_ms;    /* simulated restore/verification time */
    int cleanup_result;         /* 0 verified, <0 failed */
    int cleanup_failed_out;     /* status_flow_run out-param */
    int allow_cleanup_without_status; /* deadline cleanup when no status arrived */

    int recovery_ok;            /* 1 = verified restore, 0/-1 = gate closed */
    int recovery_null;          /* when set, io.recovery_ok is NULL */
    int recovery_calls;

    /* Integration handoff: the only positive writer of recovery_ok is the
     * consumer cleanup step, which cannot run until PENDING was observed. */
    int handoff_mode;           /* simulate the producer/consumer file handoff */
    int (*registered_cleanup)(void *c);
    void (*sleep_hook)(void *c, int ms); /* concurrent step during producer wait */
    int sleep_calls;
    int handoff_cleanup_calls;

    int child_requires_status;  /* lifecycle tests: child only after an observation */
    int async_in_flight;        /* mock model: reboot command in flight */
    int async_clear_at_read;    /* read count at which the async command settles */
    int wait_child_calls;
    int first_status_seen;      /* mock's independent view of a bound observation */

    char bootfail_token[DF_FINAL_TOKEN_CAP];
    size_t bootfail_token_len;
    int bootfail_valid;
    int bootfail_read_calls;

    char report[4096];
};

static void mock_init(struct mock *m)
{
    memset(m, 0, sizeof(*m));
    m->now = 1000;
    m->late_state = 0;          /* confirmed absent by default */
    m->recovery_ok = 1;         /* default: restore verified; gate can only disable */
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

/* Reserved bootfail channel: only the reserved nonce is a valid identity. */
static void set_bootfail(struct mock *m, enum df_outcome o)
{
    int n = df_format_final_token(m->bootfail_token, sizeof(m->bootfail_token),
                                  DF_RESERVED_BOOTSTRAP_NONCE, o);
    assert(n > 0);
    m->bootfail_token_len = (size_t)n;
    m->bootfail_valid = 1;
}

/* Mock callbacks (forward-declared for make_io below). */
static int64_t m_now(void *c);
static void m_sleep(void *c, int ms);
static int m_read(void *c, char *buf, size_t cap, size_t *out_len);
static int m_bootfail(void *c, char *buf, size_t cap, size_t *out_len);
static int m_publish(void *c, const char *nonce, enum df_outcome outcome);
static int m_late_state(void *c);
static int m_recovery_ok(void *c);
static int m_child(void *c, int timeout_ms);
static void m_report(void *c, const char *msg);
static int m_cleanup(void *c);

static struct status_io make_io(struct mock *m)
{
    struct status_io io;
    memset(&io, 0, sizeof(io));
    io.now_ms          = m_now;
    io.sleep_ms        = m_sleep;
    io.read_final      = m_read;
    io.read_bootstrap_failure = m_bootfail;
    io.publish_final   = m_publish;
    io.late_load_state = m_late_state;
    io.recovery_ok     = m->recovery_null ? NULL : m_recovery_ok;
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
    m->sleep_calls++;
    /* A simulated concurrent consumer step can run while the producer polls
     * the recovery gate; it observes the same mock clock. */
    if (m->sleep_hook) m->sleep_hook(c, ms);
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
    /* Independent mock view: a valid bound status really was observed. */
    {
        enum df_outcome o = DF_OUTCOME_NONE;
        if (df_parse_final_token(buf, n, m->nonce, &o) == 0)
            m->first_status_seen = 1;
    }
    if (m->async_clear_at_read && m->read_calls >= m->async_clear_at_read)
        m->async_in_flight = 0; /* async command quiesced */
    return 0;
}

static int m_bootfail(void *c, char *buf, size_t cap, size_t *out_len)
{
    struct mock *m = c;
    enum df_outcome o = DF_OUTCOME_NONE;

    m->bootfail_read_calls++;
    if (!buf || !cap || !out_len) return -1;
    *out_len = 0;
    if (!m->bootfail_valid || m->bootfail_token_len > cap) return -1;
    memcpy(buf, m->bootfail_token, m->bootfail_token_len);
    *out_len = m->bootfail_token_len;
    if (df_parse_bootstrap_failure_token(buf, m->bootfail_token_len, &o) == 0)
        m->first_status_seen = 1;
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
    if (m->handoff_mode && outcome == REBOOT_PENDING) {
        /* Simulated consumer reads the freshly published preflight token:
         * only NOW can its cleanup callback run and write recovery "ok\n". */
        enum df_outcome seen = DF_OUTCOME_NONE;
        assert(df_parse_final_token(m->published[i], strlen(m->published[i]),
                                    nonce, &seen) == 0);
        assert(seen == REBOOT_PENDING);
        m->first_status_seen = 1;
        m->read_calls++;
    }
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

static int m_recovery_ok(void *c)
{
    struct mock *m = c;
    m->recovery_calls++;
    return m->recovery_ok;
}

static int m_child(void *c, int timeout_ms)
{
    struct mock *m = c;
    /* Lifecycle mocks assert the safety invariant: no command before a bound
     * status is observed and the recovery gate is positively verified. */
    if (m->child_requires_status) {
        assert(m->first_status_seen);
        assert(m->recovery_ok == 1);
    }
    m->child_calls++;
    m->wait_child_calls++;
    m->child_publish_calls = m->publish_calls;
    m->child_timeout = timeout_ms;
    m->async_in_flight = 1; /* model the command as in flight */
    if (!m->async_clear_at_read)
        m->async_in_flight = 0; /* synchronous mock completion */
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

static int m_cleanup(void *c)
{
    struct mock *m = c;
    /* Cleanup is never allowed before a bound status; the only exception is
     * the deadline path, where no status ever arrived. It must also not run
     * while the modeled reboot command is still in flight. */
    assert(m->first_status_seen || m->allow_cleanup_without_status);
    assert(m->async_in_flight == 0);
    m->cleanup_calls++;
    if (m->cleanup_calls == 1) {
        m->cleanup_at_read = m->read_calls;
        m->cleanup_at_ms = m->now;
    }
    m->now += m->cleanup_consumes_ms;
    return m->cleanup_result;
}

/* Simulated consumer handoff: the registered cleanup callback can only run
 * after the PENDING preflight was observed, and it is the sole positive writer
 * of the recovery status ("ok\n"). Invoked from the producer's sleep step. */
static void handoff_sleep_hook(void *c, int ms)
{
    struct mock *m = c;
    (void)ms;
    if (!m->first_status_seen || m->recovery_ok > 0 ||
        m->handoff_cleanup_calls != 0)
        return;
    m->handoff_cleanup_calls++;
    assert(m->registered_cleanup != NULL);
    assert(m->registered_cleanup(c) == 0);
    m->recovery_ok = 1; /* dfrecover now contains exactly "ok\n" */
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

static enum df_wait_result run_flow(struct mock *m, int budget, int grace,
                                    int overall, enum df_outcome *out)
{
    struct status_io io = make_io(m);
    return status_flow_run(&io, m, budget, grace, overall, m_cleanup, out,
                           &m->cleanup_failed_out);
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
            REBOOT_FAILED, ERROR, REBOOT_PENDING, REBOOT_ATTEMPT_UNKNOWN,
            BOOTSTRAP_FAILED
        };
        const int rcs[] = { 2, 0, 4, 5, 6, 1, 7, 7, 1 };
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

    /* 27. status_flow_run calls cleanup exactly once AFTER the first bound
     * observation on DONE, and at the deadline when no status ever arrives. */
    {
        struct mock m, n;
        enum df_outcome o;
        mock_init(&m);
        set_token(&m, ERROR);
        m.async_clear_at_read = 1; /* async quiesced before cleanup */
        m.child_requires_status = 1;
        assert(run_flow(&m, 1000, 100, 2000, &o) == DONE);
        assert(o == ERROR);
        assert(m.cleanup_calls == 1);
        assert(m.first_status_seen);
        assert(m.cleanup_at_read == 1); /* AFTER the first read, never before */
        assert(m.read_calls == 1);

        mock_init(&n); /* no final file: poll until timeout */
        n.allow_cleanup_without_status = 1; /* deadline path, no observation */
        assert(run_flow(&n, 80, 20, 160, &o) == TIMEOUT);
        assert(o == DF_OUTCOME_NONE);
        assert(n.cleanup_calls == 1);
        assert(n.cleanup_at_read == n.read_calls);
        assert(n.read_calls > 0);
    }

    /* 28. clock failure at producer start: no deadline can be enforced, so no
     * reboot is authorized and the failure is published as BOOTSTRAP_FAILED
     * under the REAL nonce (rc=1), never as a silent/unknown rc=2 timeout. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.now_fail_from = 1;
        o = run_producer(&m, 0, 1);
        assert(o == BOOTSTRAP_FAILED);
        assert(df_outcome_to_rc(o) == 1);
        assert(m.publish_calls == 1);
        assert(m.published_o[0] == BOOTSTRAP_FAILED);
        assert(strcmp(m.published_nonce[0], m.nonce) == 0);
        assert(m.child_calls == 0);
        assert(strstr(m.report, "BOOTSTRAP_FAILED") != NULL);
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

    /* 35. Reserved-channel parser strictness: EXACTLY the reserved nonce AND
     * BOOTSTRAP_FAILED are accepted; the final parser itself stays strict. */
    {
        const char *nonce = "0123456789abcdef0123456789abcdef";
        char reserved[DF_FINAL_TOKEN_CAP], wrong[DF_FINAL_TOKEN_CAP];
        enum df_outcome o = ROOT_ONLY;
        int n = df_format_final_token(reserved, sizeof(reserved),
                                      DF_RESERVED_BOOTSTRAP_NONCE, BOOTSTRAP_FAILED);
        assert(n > 0);
        assert(df_is_reserved_bootstrap_nonce(DF_RESERVED_BOOTSTRAP_NONCE));
        assert(!df_is_reserved_bootstrap_nonce(nonce));
        assert(!df_is_reserved_bootstrap_nonce("0000000000000000000000000000000"));
        assert(df_parse_bootstrap_failure_token(reserved, (size_t)n, &o) == 0);
        assert(o == BOOTSTRAP_FAILED);
        /* The reserved token is NOT a valid ordinary final token for a run. */
        assert(df_parse_final_token(reserved, (size_t)n, nonce, &o) != 0);

        o = ROOT_ONLY;
        n = df_format_final_token(wrong, sizeof(wrong),
                                  DF_RESERVED_BOOTSTRAP_NONCE, ERROR);
        assert(n > 0);
        assert(df_parse_bootstrap_failure_token(wrong, (size_t)n, &o) != 0);
        assert(o == DF_OUTCOME_NONE); /* reserved nonce + wrong token */

        o = ROOT_ONLY;
        n = df_format_final_token(wrong, sizeof(wrong), nonce, BOOTSTRAP_FAILED);
        assert(n > 0);
        assert(df_parse_bootstrap_failure_token(wrong, (size_t)n, &o) != 0);
        assert(o == DF_OUTCOME_NONE); /* real nonce + bootfail token */

        o = ROOT_ONLY;
        assert(df_parse_bootstrap_failure_token(reserved, (size_t)n - 1, &o) != 0);
        assert(o == DF_OUTCOME_NONE);
    }

    /* 36. A reserved bootfail token is consumed as rc=1, NOT an rc=2 timeout. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_bootfail(&m, BOOTSTRAP_FAILED);
        assert(run_consumer(&m, 1000, 100, 2000, &o) == DONE);
        assert(o == BOOTSTRAP_FAILED);
        assert(df_outcome_to_rc(o) == 1);
        assert(m.bootfail_read_calls > 0);
        assert(strstr(m.report, "BOOTSTRAP_FAILED") != NULL);
        assert(strstr(m.report, "root verified") == NULL);
        assert(strstr(m.report, "reboot confirmed") == NULL);
    }

    /* 37/38. recovery_ok = 0 / -1: preflight is published, but the reboot
     * command is never invoked. */
    {
        const int gates[] = { 0, -1 };
        size_t i;
        for (i = 0; i < sizeof(gates) / sizeof(gates[0]); i++) {
            struct mock m; enum df_outcome o;
            mock_init(&m);
            m.recovery_ok = gates[i];
            o = run_producer(&m, 0, 1);
            assert(o == REBOOT_SKIPPED);
            assert(last_published(&m) == REBOOT_SKIPPED);
            assert(m.published_o[0] == REBOOT_PENDING);
            assert(m.child_calls == 0);
            assert(m.wait_child_calls == 0);
        }
    }

    /* 39. recovery_ok callback NULL: fail closed as REBOOT_SKIPPED, no child. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.recovery_null = 1;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_SKIPPED);
        assert(last_published(&m) == REBOOT_SKIPPED);
        assert(m.child_calls == 0);
        assert(m.wait_child_calls == 0);
        assert(m.recovery_calls == 0);
    }

    /* 40. Cleanup runs exactly once AFTER the first valid bound status; the
     * mock aborts if it is called before that observation. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        m.token_valid = 0;
        set_late_token(&m, ROOT_ONLY);
        m.token_at_read = 4;
        m.async_clear_at_read = 4;
        assert(run_flow(&m, 1000, 100, 2000, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(m.first_status_seen);
        assert(m.cleanup_calls == 1);
        assert(m.cleanup_at_read == 4);
    }

    /* 41. No cleanup while the modeled reboot command is still in flight. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        m.token_valid = 0;
        set_late_token(&m, ROOT_ONLY);
        m.token_at_read = 5;
        m.async_in_flight = 1;
        m.async_clear_at_read = 5; /* quiesced at the first bound observation */
        m.child_requires_status = 1;
        assert(run_flow(&m, 1000, 100, 2000, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(m.first_status_seen);
        assert(m.cleanup_calls == 1);
        assert(m.cleanup_at_read == 5);
    }

    /* 42. Cleanup duration is charged to the SAME shared deadline: a restore
     * that outlives the overall cap cannot extend the grace window. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        m.cleanup_consumes_ms = 1200; /* > overall_deadline start+1100 */
        assert(run_flow(&m, 1000, 200, 1100, &o) == DONE);
        assert(o == REBOOT_ATTEMPT_UNKNOWN);
        assert(m.cleanup_calls == 1);
        assert(m.cleanup_at_ms == 1000);
        assert(m.now == 2200);
        assert(strstr(m.report, "ATTEMPT_UNKNOWN") != NULL);
    }

    /* 43. A failed cleanup is visible through the out-param on DONE. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, ROOT_ONLY);
        m.cleanup_result = -1;
        assert(run_flow(&m, 1000, 100, 2000, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(m.cleanup_calls == 1);
        assert(m.cleanup_failed_out == 1);
    }

    /* 44. Failed recovery blocks the reboot and is still observable to the
     * consumer: REBOOT_SKIPPED with the recovery bit set by the caller. */
    {
        struct mock m; enum df_outcome produced, observed = DF_OUTCOME_NONE;
        mock_init(&m);
        m.recovery_ok = 0;
        produced = run_producer(&m, 0, 1);
        assert(produced == REBOOT_SKIPPED);
        assert(m.child_calls == 0);
        assert(m.wait_child_calls == 0);
        set_token(&m, REBOOT_SKIPPED);
        m.cleanup_result = -1;
        assert(run_flow(&m, 1000, 100, 2000, &observed) == DONE);
        assert(observed == REBOOT_SKIPPED);
        assert(df_outcome_to_rc(observed) == 5);
        assert(m.cleanup_failed_out == 1);
        assert(m.child_calls == 0);
    }

    /* 45. Stale-token isolation: a foreign-nonce final token is ignored and
     * cannot suppress a genuine reserved-channel bootstrap failure. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        strcpy(m.read_nonce, "fedcba9876543210fedcba9876543210");
        set_token(&m, ERROR); /* stale/foreign publisher */
        set_bootfail(&m, BOOTSTRAP_FAILED);
        assert(run_consumer(&m, 100, 20, 200, &o) == DONE);
        assert(o == BOOTSTRAP_FAILED);
        assert(df_outcome_to_rc(o) == 1);
        assert(m.bootfail_read_calls > 0);
    }

    /* 46. Interruption with no refinement: the pending preflight becomes a
     * terminal REBOOT_ATTEMPT_UNKNOWN after exactly one cleanup. */
    {
        struct mock m; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&m);
        set_token(&m, REBOOT_PENDING);
        assert(run_flow(&m, 100, 50, 1000, &o) == DONE);
        assert(o == REBOOT_ATTEMPT_UNKNOWN);
        assert(df_outcome_to_rc(o) == 7);
        assert(m.first_status_seen);
        assert(m.cleanup_calls == 1);
    }

    /* 47. Two sequential invocations with different nonces: the second run's
     * cleared bootfail channel cannot inherit the first run's outcome. */
    {
        struct mock a, b; enum df_outcome o = DF_OUTCOME_NONE;
        mock_init(&a);
        set_bootfail(&a, BOOTSTRAP_FAILED);
        assert(run_consumer(&a, 100, 20, 200, &o) == DONE);
        assert(o == BOOTSTRAP_FAILED);

        mock_init(&b);
        strcpy(b.nonce, "fedcba9876543210fedcba9876543210");
        strcpy(b.read_nonce, b.nonce);
        b.bootfail_valid = 0; /* stale bootfail cleared before run 2 */
        set_token(&b, ROOT_ONLY);
        o = DF_OUTCOME_NONE;
        assert(run_consumer(&b, 100, 20, 200, &o) == DONE);
        assert(o == ROOT_ONLY);
        assert(df_outcome_to_rc(o) == 0);
        assert(b.bootfail_read_calls == 0); /* valid final wins, bootfail absent */
        assert(strcmp(a.nonce, b.nonce) != 0);
    }

    /* 48. Integration handoff: recovery starts at the startup "fail\n", the
     * simulated consumer cleanup runs DURING the producer's bounded recovery
     * polling, flips the gate to "ok\n", and only then may wait_child run. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.handoff_mode = 1;
        m.recovery_ok = 0; /* startup "fail\n" */
        m.child_requires_status = 1;
        m.registered_cleanup = m_cleanup;
        m.sleep_hook = handoff_sleep_hook;
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_REQUESTED);
        assert(m.published_o[0] == REBOOT_PENDING);
        assert(last_published(&m) == REBOOT_REQUESTED);
        assert(m.first_status_seen);
        assert(m.handoff_cleanup_calls == 1);
        assert(m.cleanup_calls == 1);
        assert(m.recovery_calls > 1); /* polled, not read exactly once */
        assert(m.sleep_calls > 0);
        assert(m.child_calls == 1 && m.wait_child_calls == 1);
        assert(m.child_publish_calls == 1); /* pending preflight before command */
        assert(m.now <= 1000 + 200); /* clamped to the remaining late-load deadline */
    }

    /* 49. Companion: the recovery gate never flips. REBOOT_SKIPPED is
     * published inside the bounded/clamped deadline with zero child calls. */
    {
        struct mock m; enum df_outcome o;
        mock_init(&m);
        m.handoff_mode = 1;
        m.recovery_ok = 0;
        m.registered_cleanup = m_cleanup; /* registered but never invoked */
        o = run_producer(&m, 0, 1);
        assert(o == REBOOT_SKIPPED);
        assert(last_published(&m) == REBOOT_SKIPPED);
        assert(m.child_calls == 0 && m.wait_child_calls == 0);
        assert(m.handoff_cleanup_calls == 0);
        assert(m.cleanup_calls == 0);
        assert(m.sleep_calls > 0);
        assert(m.now <= 1000 + 200);
        assert(m.now < 1000 + DF_RECOVERY_WAIT_MS);
        assert(strstr(m.report, "restore verification not confirmed") != NULL);
    }

    puts("status_protocol_test: all 49 cases passed");
    return 0;
}
