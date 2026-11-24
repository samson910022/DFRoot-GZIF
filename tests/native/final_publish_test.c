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

static char g_dir[256];
static char g_tmp[288];
static char g_final[288];
static char g_victim[288];

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
}

static void cleanup_files(void)
{
    unlink(g_tmp);
    unlink(g_final);
    unlink(g_victim);
    rmdir(g_dir);
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

int main(void)
{
    mode_t old_umask;
    char long_token[4097]; /* 4096 chars + NUL: df_publish_final rejects len >= 4096 */

    make_paths();

    /* 1. Publish with a hostile umask: open() alone would yield 0000 here,
     * so the explicit fchmod(DF_FINAL_MODE) is what makes the token readable. */
    old_umask = umask(0777);
    assert(df_publish_final(g_tmp, g_final, "root_only") == 0);
    umask(old_umask);
    assert_final("root_only");

    /* 2. Atomic refinement replaces the token and keeps the same mode. */
    assert(df_publish_final(g_tmp, g_final, "reboot_requested") == 0);
    assert_final("reboot_requested");

    /* 3. Invalid tokens fail without corrupting the published final status. */
    memset(long_token, 'x', sizeof(long_token) - 1);
    long_token[sizeof(long_token) - 1] = '\0';
    assert(df_publish_final(g_tmp, g_final, "") == -1);
    assert(df_publish_final(g_tmp, g_final, NULL) == -1);
    assert(df_publish_final(g_tmp, g_final, long_token) == -1);
    assert_final("reboot_requested");

    /* 4. A pre-planted temp symlink is unlinked, never followed; the target
     * file is untouched and the real final status is still published. */
    {
        int fd = open(g_victim, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        assert(fd >= 0);
        assert(write(fd, "keep", 4) == 4);
        close(fd);
        assert(symlink(g_victim, g_tmp) == 0);
        assert(df_publish_final(g_tmp, g_final, "error") == 0);
        assert_final("error");
        assert_victim("keep");
    }

    cleanup_files();
    puts("final_publish_test: all real-file permission/atomicity checks passed");
    return 0;
}
