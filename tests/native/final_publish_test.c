/* Host-side test for the REAL final-status publisher (bootstrap.c's
 * io_publish_final delegates here). Uses real files in a private temp dir:
 * a 0600 regression or a umask-stripped mode is caught on every run.
 * No /dev files are touched. */
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "final_status_publish.h"
#include "status_protocol.h"

#define NONCE "0123456789abcdef0123456789abcdef"
#define TOK_ROOT          "dfroot-v2:" NONCE ":root_only\n"
#define TOK_REBOOT_REQ    "dfroot-v2:" NONCE ":reboot_requested\n"
#define TOK_ERROR         "dfroot-v2:" NONCE ":error\n"
#define TOK_BOOTFAIL      "dfroot-v2:" DF_RESERVED_BOOTSTRAP_NONCE ":bootstrap_failed\n"
#define TOK_BOOTFAIL_WRONG "dfroot-v2:" DF_RESERVED_BOOTSTRAP_NONCE ":error\n"
#define TOK_BOOTFAIL_REAL  "dfroot-v2:" NONCE ":bootstrap_failed\n"
#define RECOVERY_OK       "ok\n"
#define RECOVERY_FAIL     "fail\n"
#define GUARD_BYTE        0x5a /* sentinel placed one byte past the read cap */

static char g_dir[256];
static char g_tmp[288];
static char g_final[288];
static char g_victim[288];
static char g_read[288];
static char g_bootfail_tmp[288];
static char g_bootfail[288];

static void make_paths(void)
{
    const char *base = getenv("TMPDIR");
    char tmpl[256];

    if (!base || !*base)
        base = "/tmp";
    snprintf(tmpl, sizeof(tmpl), "%s/dffinal-publish-test-XXXXXX", base);
    if (!mkdtemp(tmpl)) {
        perror("mkdtemp");
        exit(1);
    }
    snprintf(g_dir, sizeof(g_dir), "%s", tmpl);
    snprintf(g_tmp, sizeof(g_tmp), "%s/dffinal.tmp", g_dir);
    snprintf(g_final, sizeof(g_final), "%s/dffinal", g_dir);
    snprintf(g_victim, sizeof(g_victim), "%s/victim", g_dir);
    snprintf(g_read, sizeof(g_read), "%s/readtoken", g_dir);
    snprintf(g_bootfail_tmp, sizeof(g_bootfail_tmp), "%s/dfbootfail.tmp", g_dir);
    snprintf(g_bootfail, sizeof(g_bootfail), "%s/dfbootfail", g_dir);
}

static void cleanup_files(void)
{
    unlink(g_tmp);
    unlink(g_final);
    unlink(g_victim);
    unlink(g_read);
    unlink(g_bootfail_tmp);
    unlink(g_bootfail);
    rmdir(g_dir);
}

/* A read buffer with sentinel bytes at [cap] and [cap + 1] so any write past
 * the caller's capacity is detected instead of silently corrupting the stack. */
static char *guarded_buf(void)
{
    char *buf = malloc(DF_FINAL_READ_CAP + 2);
    assert(buf != NULL);
    memset(buf, GUARD_BYTE, DF_FINAL_READ_CAP + 2);
    return buf;
}

static void assert_guard_intact(const char *buf)
{
    assert((unsigned char)buf[DF_FINAL_READ_CAP] == GUARD_BYTE);
    assert((unsigned char)buf[DF_FINAL_READ_CAP + 1] == GUARD_BYTE);
}

/* Publish raw bytes (including embedded NULs) as a fresh regular file. */
static void write_read_file(const char *data, size_t len)
{
    int fd = open(g_read, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
    size_t off = 0;
    assert(fd >= 0);
    while (off < len) {
        ssize_t w = write(fd, data + off, len - off);
        assert(w > 0);
        off += (size_t)w;
    }
    close(fd);
}

/* Reader regression: a file that is EXACTLY the caller's cap must be rejected.
 * The old `st_size > cap` guard admitted it and then wrote buf[cap] = '\0' OOB. */
static void test_read_exact_cap(void)
{
    char payload[DF_FINAL_READ_CAP];
    char *buf = guarded_buf();
    size_t len = 12345;

    memset(payload, 'A', sizeof(payload));
    write_read_file(payload, sizeof(payload)); /* st_size == cap */
    assert(df_read_final_file(g_read, buf, DF_FINAL_READ_CAP, &len) == -1);
    assert(len == 0);          /* out_len cleared even on rejection */
    assert_guard_intact(buf);  /* no 1-byte OOB stack/heap write */
    free(buf);
}

/* Oversize files are rejected both by the absolute cap and by a smaller
 * caller-supplied cap, without touching the guard bytes. */
static void test_read_oversize(void)
{
    char payload[DF_FINAL_READ_CAP + 2];
    char *buf = guarded_buf();
    size_t len = 12345;

    memset(payload, 'B', sizeof(payload));
    write_read_file(payload, sizeof(payload)); /* > DF_FINAL_READ_CAP */
    assert(df_read_final_file(g_read, buf, DF_FINAL_READ_CAP, &len) == -1);
    assert(len == 0);
    assert_guard_intact(buf);

    write_read_file("123456789", 9); /* <= DF_FINAL_READ_CAP, but > cap=8 */
    assert(df_read_final_file(g_read, buf, 8, &len) == -1);
    assert(len == 0);
    assert((unsigned char)buf[8] == GUARD_BYTE); /* caller cap respected */
    free(buf);
}

/* The reader preserves embedded NULs with an exact byte count; it is the
 * strict parser that rejects them (never C-string truncation). */
static void test_read_embedded_nul(void)
{
    char token[128];
    char *buf = guarded_buf();
    size_t len = 0, nul_at = strlen("dfroot-v2:") + 4; /* inside the nonce */
    enum df_outcome o = ROOT_ONLY; /* sentinel: must be reset on rejection */
    int n = snprintf(token, sizeof(token), TOK_ROOT);

    assert(n > 0 && (size_t)n < sizeof(token));
    token[nul_at] = '\0';
    write_read_file(token, (size_t)n); /* NUL is part of the byte stream */
    assert(df_read_final_file(g_read, buf, DF_FINAL_READ_CAP, &len) == 0);
    assert(len == (size_t)n);
    assert(buf[nul_at] == '\0');
    assert(df_parse_final_token(buf, len, NONCE, &o) != 0);
    assert(o == DF_OUTCOME_NONE); /* fail closed */
    assert_guard_intact(buf);
    free(buf);
}

/* A normal canonical token round-trips with its exact length, and no NUL
 * convenience byte is written past that length. */
static void test_read_canonical_token(void)
{
    char *buf = guarded_buf();
    size_t len = 0;
    enum df_outcome o = DF_OUTCOME_NONE;

    write_read_file(TOK_REBOOT_REQ, strlen(TOK_REBOOT_REQ));
    assert(df_read_final_file(g_read, buf, DF_FINAL_READ_CAP, &len) == 0);
    assert(len == strlen(TOK_REBOOT_REQ));
    assert(memcmp(buf, TOK_REBOOT_REQ, len) == 0);
    assert((unsigned char)buf[len] == GUARD_BYTE); /* no buf[len] = '\0' */
    assert(df_parse_final_token(buf, len, NONCE, &o) == 0);
    assert(o == REBOOT_REQUESTED);
    assert_guard_intact(buf);
    free(buf);
}

static void assert_final(const char *expected)
{
    struct stat st;
    char buf[128];
    int fd;
    ssize_t n;

    /* The token is not secret, but the app uid (exp.c) must be able to read
     * what uid 0 (bootstrap) published. */
    assert((DF_FINAL_MODE & 0444) == 0444);
    assert(stat(g_final, &st) == 0);
    assert(S_ISREG(st.st_mode));
    assert((st.st_mode & 0777) == DF_FINAL_MODE);
    assert(access(g_tmp, F_OK) != 0); /* temp consumed by rename(2) */

    fd = open(g_final, O_RDONLY | O_NOFOLLOW);
    assert(fd >= 0);
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    assert(n >= 0 && n < (ssize_t)sizeof(buf));
    buf[n] = '\0';
    assert(strcmp(buf, expected) == 0);
    assert(df_token_is_canonical(buf));
}

static void assert_victim(const char *expected)
{
    char buf[32];
    int fd = open(g_victim, O_RDONLY | O_NOFOLLOW);
    ssize_t n;
    assert(fd >= 0);
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    assert(n >= 0 && n < (ssize_t)sizeof(buf));
    buf[n] = '\0';
    assert(strcmp(buf, expected) == 0);
}

static void assert_rejected(const char *token)
{
    assert(df_publish_final(g_tmp, g_final, token) == -1);
    assert(access(g_tmp, F_OK) != 0); /* no temp file left behind */
    assert_final(TOK_REBOOT_REQ);     /* prior final status untouched */
}

static void assert_bootfail(const char *expected)
{
    struct stat st;
    char buf[128];
    int fd;
    ssize_t n;

    /* The reserved-channel token is also world-readable: the app uid consumes
     * what uid 0 published in bootstrap's nonce-failure path. */
    assert((DF_FINAL_MODE & 0444) == 0444);
    assert(stat(g_bootfail, &st) == 0);
    assert(S_ISREG(st.st_mode));
    assert((st.st_mode & 0777) == DF_FINAL_MODE);
    assert(access(g_bootfail_tmp, F_OK) != 0); /* temp consumed by rename(2) */

    fd = open(g_bootfail, O_RDONLY | O_NOFOLLOW);
    assert(fd >= 0);
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    assert(n >= 0 && n < (ssize_t)sizeof(buf));
    buf[n] = '\0';
    assert(strcmp(buf, expected) == 0);
    assert(df_token_is_canonical(buf));
}

/* Reserved bootstrap-failure channel: publication, permission, canonicality,
 * temp consumption, strict reader and strict channel parser. */
static void test_bootfail_channel(void)
{
    mode_t old_umask;
    char *buf = guarded_buf();
    size_t len = 12345;
    enum df_outcome o = ROOT_ONLY;

    old_umask = umask(0777);
    assert(df_publish_final(g_bootfail_tmp, g_bootfail, TOK_BOOTFAIL) == 0);
    umask(old_umask);
    assert_bootfail(TOK_BOOTFAIL);

    assert(df_read_final_file(g_bootfail, buf, DF_FINAL_READ_CAP, &len) == 0);
    assert(len == strlen(TOK_BOOTFAIL));
    assert(memcmp(buf, TOK_BOOTFAIL, len) == 0);
    assert_guard_intact(buf);
    assert(df_parse_bootstrap_failure_token(buf, len, &o) == 0);
    assert(o == BOOTSTRAP_FAILED);
    assert(df_outcome_to_rc(o) == 1);
    o = ROOT_ONLY;
    assert(df_parse_final_token(buf, len, NONCE, &o) != 0);
    assert(o == DF_OUTCOME_NONE);

    /* Both look-alike tokens are canonical v2 and can be published, but the
     * strict bootfail channel parser rejects them: reserved nonce + wrong
     * outcome, or real run nonce + BOOTSTRAP_FAILED. */
    assert(df_publish_final(g_bootfail_tmp, g_bootfail, TOK_BOOTFAIL_WRONG) == 0);
    assert_bootfail(TOK_BOOTFAIL_WRONG);
    assert(df_read_final_file(g_bootfail, buf, DF_FINAL_READ_CAP, &len) == 0);
    assert(df_parse_bootstrap_failure_token(buf, len, &o) != 0);
    assert(o == DF_OUTCOME_NONE);

    assert(df_publish_final(g_bootfail_tmp, g_bootfail, TOK_BOOTFAIL_REAL) == 0);
    assert_bootfail(TOK_BOOTFAIL_REAL);
    assert(df_read_final_file(g_bootfail, buf, DF_FINAL_READ_CAP, &len) == 0);
    assert(df_parse_bootstrap_failure_token(buf, len, &o) != 0);
    assert(o == DF_OUTCOME_NONE);

    /* Non-canonical bytes are rejected before any file is created and the
     * previous bootfail token is left untouched. */
    assert(df_publish_final(g_bootfail_tmp, g_bootfail, "root_only") == -1);
    assert(access(g_bootfail_tmp, F_OK) != 0);
    assert_bootfail(TOK_BOOTFAIL_REAL);
    free(buf);
}

/* App-private recovery status: only the exact bytes "ok\n" open a reboot gate.
 * The real-file adapter preserves exact bytes; the pure validator rejects the
 * fail token, empty/partial files and any extra bytes. */
static void test_recovery_status(void)
{
    char *buf = guarded_buf();
    size_t len = 12345;
    const char *const bad[] = { RECOVERY_FAIL, "", "ok", "ok\n\n", "ok\nX" };
    size_t i;

    write_read_file(RECOVERY_OK, strlen(RECOVERY_OK));
    assert(df_read_final_file(g_read, buf, DF_FINAL_READ_CAP, &len) == 0);
    assert(len == 3 && memcmp(buf, RECOVERY_OK, 3) == 0);
    assert(df_recovery_status_is_ok(buf, len) == 1);
    assert_guard_intact(buf);

    for (i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        write_read_file(bad[i], strlen(bad[i]));
        assert(df_read_final_file(g_read, buf, DF_FINAL_READ_CAP, &len) == 0);
        assert(len == strlen(bad[i]));
        assert(df_recovery_status_is_ok(buf, len) == 0);
        assert_guard_intact(buf);
    }
    /* No NUL convenience byte is written past the exact length. */
    memset(buf, GUARD_BYTE, DF_FINAL_READ_CAP + 2);
    write_read_file(RECOVERY_OK, 3);
    assert(df_read_final_file(g_read, buf, DF_FINAL_READ_CAP, &len) == 0);
    assert((unsigned char)buf[len] == GUARD_BYTE);
    free(buf);
}

int main(void)
{
    mode_t old_umask;
    char long_token[4097]; /* 4096 chars + NUL: df_publish_final rejects len >= 4096 */
    char malformed[160];

    make_paths();

    /* 1. Publish a canonical v2 token with a hostile umask: open() alone would
     * yield 0000 here, so the explicit fchmod(DF_FINAL_MODE) is what makes the
     * token readable. */
    assert(df_token_is_canonical(TOK_ROOT));
    assert(!df_token_is_canonical("root_only"));
    old_umask = umask(0777);
    assert(df_publish_final(g_tmp, g_final, TOK_ROOT) == 0);
    umask(old_umask);
    assert_final(TOK_ROOT);

    /* 2. Atomic refinement replaces the token and keeps the same mode. */
    assert(df_publish_final(g_tmp, g_final, TOK_REBOOT_REQ) == 0);
    assert_final(TOK_REBOOT_REQ);

    /* 3. Invalid tokens fail BEFORE any file is created: the previous final
     * status is untouched. Legacy v1, truncated/extra bytes, stale/foreign
     * nonce, unknown/none outcome and oversize are all rejected. */
    memset(long_token, 'x', sizeof(long_token) - 1);
    long_token[sizeof(long_token) - 1] = '\0';
    assert_rejected("");
    assert_rejected(NULL);
    assert_rejected(long_token);
    assert_rejected("root_only");
    snprintf(malformed, sizeof(malformed), "dfroot-v2:%s:root_only", NONCE);
    assert_rejected(malformed);
    snprintf(malformed, sizeof(malformed), "dfroot-v2:%s:root_only\n\n", NONCE);
    assert_rejected(malformed);
    /* Embedded NUL inside the nonce: the C-string view is truncated. */
    snprintf(malformed, sizeof(malformed), TOK_ROOT);
    malformed[15] = '\0';
    assert_rejected(malformed);
    /* A well-formed token with a DIFFERENT nonce is canonical in shape (the
     * publisher binds the expected nonce itself); strict nonce checking is the
     * consumer's job via df_parse_final_token(), covered in status_protocol_test.
     */
    snprintf(malformed, sizeof(malformed), "dfroot-v2:%s:nope\n", NONCE);
    assert_rejected(malformed);
    snprintf(malformed, sizeof(malformed), "dfroot-v2:%s:none\n", NONCE);
    assert_rejected(malformed);

    /* 4. A pre-planted temp symlink is unlinked, never followed; the target
     * file is untouched and the real final status is still published. */
    {
        int fd = open(g_victim, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        assert(fd >= 0);
        assert(write(fd, "keep", 4) == 4);
        close(fd);
        assert(symlink(g_victim, g_tmp) == 0);
        assert(df_publish_final(g_tmp, g_final, TOK_ERROR) == 0);
        assert_final(TOK_ERROR);
        assert_victim("keep");
    }

    /* 5. Real-file reader: exact-cap/oversize rejected with no OOB write,
     * embedded NUL left for the strict parser, canonical token round-trips. */
    test_read_exact_cap();
    test_read_oversize();
    test_read_embedded_nul();
    test_read_canonical_token();

    /* 6. Reserved bootfail token publication/reader (host-only, no /dev). */
    test_bootfail_channel();
    /* 7. Strict recovery-status bytes (host-only, no /dev). */
    test_recovery_status();

    cleanup_files();
    puts("final_publish_test: canonical-token / permission / atomicity / reader / bootfail / recovery checks passed");
    return 0;
}
