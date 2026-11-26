/* Modified 2026-11-17 (DFRoot GZIF): create /data/adb and stage the ksud daemon before late-load. See NOTICE. */
/* Modified 2026-11-24 (PR #3 hardening): secure staging, fail-closed single final-status file and bounded wait handling. */
/* Modified 2026-11-26 (PR #3 Phase C): stage into the root-owned /data/adb/.ksud-stage consumed by the rebuilt loaders. */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#include "status_protocol.h"
#include "final_status_publish.h"
#include "proc_scan_policy.h"
#include "bootstrap_stage.h"

#define BLKROSET   0x125d
/* The rebuilt Samsung-patched loaders are expected to consume /data/adb/.ksud-stage: a stage
 * inside root-owned /data/adb (0700) that they validate by fd (owner, mode,
 * non-empty content) before promoting it to /data/adb/ksud. DFRoot's producer
 * must create exactly that contract. P1-08 stays an OPEN release gate until
 * the new loader binaries are integrated, re-reviewed and validated on a
 * device; this producer change by itself is not proof the gate is closed. */
#define KSUD_STAGE_DIR "/data/adb"
#define KSUD_STAGE_NAME ".ksud-stage"
#define KSUD_STAGE "/data/adb/.ksud-stage"
#define KSUD_STAGE_SRC "/data/user_de/0/df.root/ksud"
#define KSUD       "/data/adb/ksud"
#define PREFS_PATH "/data/user_de/0/df.root/shared_prefs/dfroot.xml"
#define MODULES_DIR "/data/adb/modules"
/* The single authoritative outcome. Written atomically via the .tmp file so
 * the consumer can never observe a partial token. Progress markers stay
 * informational; only /dev/dffinal carries the result. */
#define DF_FINAL_PATH "/dev/dffinal"
#define DF_FINAL_TMP  "/dev/dffinal.tmp"
#define DF_NONCE_PATH "/data/user_de/0/df.root/files/dfinv"
/* App-private channels: bootstrap failures before a run identity exists are
 * published under the RESERVED nonce here, never to /dev/dffinal. The recovery
 * status is written by exp.c after cleanup verification and can only block a
 * reboot (never authorize one). */
#define DF_BOOTFAIL_PATH "/data/user_de/0/df.root/files/dfbootfail"
#define DF_BOOTFAIL_TMP  "/data/user_de/0/df.root/files/dfbootfail.tmp"
#define DF_RECOVER_PATH  "/data/user_de/0/df.root/files/dfrecover"
#define LATE_LOAD_WAIT_MS 60000
#define SOFT_REBOOT_WAIT_MS 5000
/* Secondary deadline for reaping a SIGKILLed child. A stuck child must never
 * turn a bounded wait into an unbounded blocking waitpid(). */
#define SIGKILL_REAP_WAIT_MS 2000
/* The late-load command itself must not hold bootstrap forever either. */
#define RUN_CTX_WAIT_MS 5000

static void kill_and_reap_bounded(pid_t pid);
static int64_t monotonic_ms(void);

static int pref_true(const char *buf, const char *key)
{
    char needle[64];
    snprintf(needle, sizeof(needle), "name=\"%s\"", key);
    const char *p = strstr(buf, needle);
    if (!p) return 0;
    const char *tag_end = strchr(p, '>');
    const char *v = strstr(p, "value=\"true\"");
    return v && tag_end && v < tag_end;
}

static int read_prefs(char *su_manager, size_t su_manager_size, int *soft_reboot,
                      int *disable_modules)
{
    int fd = open(PREFS_PATH, O_RDONLY);
    if (fd < 0) return -1;

    char buf[4096];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';

    char *p = strstr(buf, "name=\"su_manager\">");
    if (!p) return -1;
    p += strlen("name=\"su_manager\">");
    char *end = strchr(p, '<');
    if (!end) return -1;
    size_t len = end - p;
    if (len == 0 || len >= su_manager_size) return -1;
    memcpy(su_manager, p, len);
    su_manager[len] = '\0';

    *soft_reboot = pref_true(buf, "soft_reboot");
    *disable_modules = pref_true(buf, "disable_modules");

    return 0;
}

static int adopt_zygote_env(void)
{
    FILE *f = popen("pidof zygote64 zygote", "r");
    if (!f) return -1;
    int pid = 0;
    if (fscanf(f, "%d", &pid) != 1) pid = 0;
    pclose(f);
    if (!pid) return -1;

    char path[32];
    snprintf(path, sizeof(path), "/proc/%d/environ", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    static char buf[16384];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    for (char *p = buf, *end = buf + n; p < end; p += strlen(p) + 1) {
        /* Do not inherit dynamic-loader controls from zygote. */
        if (strncmp(p, "LD_", 3) == 0) continue;
        putenv(p);
    }
    return 0;
}

static int should_ro(const char *name)
{
    size_t len = strlen(name);
    if (!strcmp(name, "super"))  return 1;
    if (!strcmp(name, "misc"))   return 1;
    if (!strcmp(name, "steady")) return 1;
    if (len >= 2 && name[len - 2] == '_' &&
        (name[len - 1] == 'a' || name[len - 1] == 'b'))
        return 1;
    return 0;
}

static int set_partitions_ro(void)
{
    DIR *dir = opendir("/dev/block/by-name");
    if (!dir)
        return -1;

    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (!should_ro(ent->d_name))
            continue;

        char path[128];
        snprintf(path, sizeof(path), "/dev/block/by-name/%s", ent->d_name);

        int fd = open(path, O_RDONLY);
        if (fd < 0)
            continue;

        struct stat st;
        if (fstat(fd, &st) == 0 && S_ISBLK(st.st_mode)) {
            int on = 1;
            ioctl(fd, BLKROSET, &on);
        }
        close(fd);
    }

    closedir(dir);
    return 0;
}

/* Returns 1 when a ksud late-load process exists, 0 when it is confirmed
 * absent, and -1 when the scan is incomplete. Unknown must never be treated as
 * absent: readdir() signals errors only through errno, a truncated cmdline can
 * hide the marker, and an unexpected open/read errno means we did not see
 * everything. df_proc_entry_verdict() is the single place that encodes this
 * tri-state classification. */
static int late_load_running(void)
{
    DIR *d = opendir("/proc");
    if (!d)
        return -1;
    struct dirent *e;
    char path[64], buf[256];
    int found = 0, rc = 0;

    for (;;) {
        char *end;
        long pid;
        int fd, n, open_errno = 0, read_errno = 0, buf_full;
        enum df_proc_verdict verdict;

        /* Reset immediately before every readdir(): open/read/close/strtol
         * in the body can set errno, and readdir() only signals an error by
         * returning NULL with a nonzero errno. */
        errno = 0;
        e = readdir(d);
        if (!e) {
            if (errno != 0)
                rc = -1;
            break;
        }
        /* Strict PID validation: no sign, no trailing junk, no overflow, and
         * PID 0 (or a negative value) is never a process directory. */
        errno = 0;
        pid = strtol(e->d_name, &end, 10);
        if (errno != 0 || end == e->d_name || *end != '\0' ||
            pid <= 0 || pid > INT_MAX)
            continue;
        snprintf(path, sizeof(path), "/proc/%ld/cmdline", pid);
        fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0)
            open_errno = errno;
        n = -1;
        if (fd >= 0) {
            errno = 0;
            n = (int)read(fd, buf, sizeof(buf) - 1);
            if (n < 0)
                read_errno = errno;
            close(fd);
        }
        buf_full = (n == (int)sizeof(buf) - 1);
        if (n > 0) {
            buf[n] = '\0';
            for (int i = 0; i < n; i++)
                if (!buf[i]) buf[i] = ' ';
        }
        verdict = df_proc_entry_verdict(open_errno, read_errno,
                                        n < 0 ? -1 : n, buf_full,
                                        n > 0 &&
                                        strstr(buf, "ksud") != NULL &&
                                        strstr(buf, "late-load") != NULL);
        if (verdict == DF_PROC_MATCH) {
            found = 1;
            break;
        }
        if (verdict == DF_PROC_INCOMPLETE) {
            rc = -1;
            break;
        }
        /* BENIGN: benign per-PID race or a scanned non-matching entry. */
    }
    if (closedir(d) < 0)
        rc = -1;
    return rc ? -1 : found;
}

/* Spawn a helper and wait for it with a bounded WNOHANG loop. A blocking
 * waitpid() here could hang forever (stuck child, no exit); a timeout or a
 * clock failure is fail-closed (-1), never a success. */
static int run_ctx(const char *ctx, char *const argv[])
{
    pid_t pid;
    int64_t start, now;
    int status = 0;

    start = monotonic_ms();
    if (start < 0)
        return -1; /* no clock, no safe deadline */
    pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        if (ctx) {
            int fd = open("/proc/self/attr/exec", O_WRONLY);
            if (fd >= 0) { write(fd, ctx, strlen(ctx)); close(fd); }
        }
        execv(argv[0], argv);
        _exit(127);
    }
    for (;;) {
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid)
            return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
        if (w < 0 && errno != EINTR)
            return -1;
        now = monotonic_ms();
        if (now < 0 || now - start >= RUN_CTX_WAIT_MS) {
            kill_and_reap_bounded(pid);
            return -1;
        }
        usleep(10000);
    }
}

/* ---- Real status_io operations for the producer ---- */

/* Negative means "clock unavailable" and callers MUST fail closed. Returning
 * 0 here would freeze a deadline (infinite wait) or make elapsed time look
 * enormous (premature timeout); neither is safe for a reboot decision. */
static int64_t monotonic_ms(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return -1;
    if (ts.tv_sec < 0) return -1;
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int64_t io_now_ms(void *ctx)
{
    (void)ctx;
    return monotonic_ms();
}

static void io_sleep_ms(void *ctx, int ms)
{
    (void)ctx;
    if (ms > 0) usleep((unsigned int)ms * 1000);
}

static void io_report(void *ctx, const char *msg)
{
    (void)ctx;
    printf("bootstrap: %s\n", msg);
}

/* late_load_running() is already strictly tri-state: 1 running, 0 confirmed
 * absent, -1 /proc unscannable (unknown). Never coerce unknown to absent. */
static int io_late_load_state(void *ctx)
{
    (void)ctx;
    return late_load_running();
}

/* Read the published final token with the strict real adapter: O_NOFOLLOW,
 * regular-file only, bounded size, exact read. A failure (absent/unreadable/
 * symlink/oversize) is simply "not available yet" to the consumer. */
static int io_read_final(void *ctx, char *buf, size_t cap, size_t *out_len)
{
    (void)ctx;
    return df_read_final_file(DF_FINAL_PATH, buf, cap, out_len);
}

/* The app-owned dfinv file's SELinux context is the trusted template for the
 * final token's label. A bounded, well-formed app-data context is required:
 * anything else means no normal final token may be published. */
static int read_app_context(char out[DF_FINAL_LABEL_CAP])
{
    ssize_t n;

    n = getxattr(DF_NONCE_PATH, DF_FINAL_LABEL_ATTR, out, DF_FINAL_LABEL_CAP);
    if (n <= 0 || n >= DF_FINAL_LABEL_CAP)
        return -1;
    out[n] = '\0';
    if (strncmp(out, "u:object_r:", strlen("u:object_r:")) != 0)
        return -1;
    return 0;
}

/* Atomically publish one canonical, nonce-bound final token via
 * df_publish_final_labeled(): O_EXCL|O_NOFOLLOW temp, mode DF_FINAL_MODE
 * (0644, fchmod-pinned against umask), the app-readable SELinux label copied
 * from dfinv, fsync, rename. rename(2) is atomic, so the consumer sees either
 * the old token or the new one, never a partial write. A context that cannot
 * be read means no normal final token is published. Failure leaves the
 * previous final status untouched. */
static int io_publish_final(void *ctx, const char *nonce, enum df_outcome outcome)
{
    char token[DF_FINAL_TOKEN_CAP];
    char label_ctx[DF_FINAL_LABEL_CAP];
    (void)ctx;
    if (df_format_final_token(token, sizeof(token), nonce, outcome) < 0)
        return -1;
    if (read_app_context(label_ctx) != 0) {
        printf("bootstrap: app-readable label unavailable; no normal final token\n");
        return -1;
    }
    if (df_publish_final_labeled(DF_FINAL_TMP, DF_FINAL_PATH, token,
                                 label_ctx) != 0)
        return -1;
    printf("bootstrap: final token published to /dev/dffinal with label copied from dfinv\n");
    return 0;
}

/* SIGKILL the child and reap it with WNOHANG against a short secondary
 * deadline. A blocking waitpid() here could hang forever (e.g. a child stuck
 * in an uninterruptible state); an unreaped child is never a reboot success.
 * On clock failure we cannot bound the loop at all, so we do not block. */
static void kill_and_reap_bounded(pid_t pid)
{
    int64_t start, now;
    int st = 0;

    (void)kill(pid, SIGKILL);
    start = monotonic_ms();
    if (start < 0)
        return;
    for (;;) {
        pid_t w = waitpid(pid, &st, WNOHANG);
        if (w == pid || (w < 0 && errno != EINTR))
            return; /* reaped, or already gone (ECHILD) */
        now = monotonic_ms();
        if (now < 0 || now - start >= SIGKILL_REAP_WAIT_MS)
            return;
        usleep(10000);
    }
}

/* Spawn the soft-reboot command in the KSU context and wait for it with a
 * bounded, non-blocking loop. On timeout kill and reap the child; an
 * unreaped/zombie child must never be mistaken for a successful reboot.
 * Clock failure is fail-closed: return -1 (REBOOT_FAILED) rather than using a
 * sentinel 0 that would freeze or distort the deadline. */
static int wait_child_bounded(void *ctx, int timeout_ms)
{
    pid_t pid;
    int64_t start;
    int st = 0;
    (void)ctx;

    start = monotonic_ms();
    if (start < 0)
        return -1; /* no clock, no safe deadline: do not spawn/reboot */

    pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        int fd = open("/proc/self/attr/exec", O_WRONLY | O_CLOEXEC);
        if (fd >= 0) {
            const char ksu_ctx[] = "u:r:ksu:s0";
            (void)write(fd, ksu_ctx, sizeof(ksu_ctx) - 1);
            close(fd);
        }
        execv(KSUD, (char *[]){ KSUD, "soft-reboot", NULL });
        _exit(127);
    }

    for (;;) {
        pid_t w;
        int64_t now;

        w = waitpid(pid, &st, WNOHANG);
        if (w == pid)
            return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        now = monotonic_ms();
        if (now < 0) {
            kill_and_reap_bounded(pid);
            return -1;
        }
        if (now - start >= timeout_ms) {
            kill_and_reap_bounded(pid);
            return -1;
        }
        usleep(10000);
    }
}

/* Returns 0 on success. access(path, F_OK) only needs search permission on
 * /dev, so markers stay 0600; only root can create entries in /dev anyway. */
static int touch(const char *path)
{
    int fd = open(path, O_CREAT | O_WRONLY, 0600);
    if (fd < 0) {
        printf("bootstrap: touch %s failed: %s\n", path, strerror(errno));
        return -1;
    }
    close(fd);
    return 0;
}

/* Staging now lives in bootstrap_stage.c: df_stage_file() anchors every lookup
 * to an fd for /data/adb, validates its owner/mode, replaces only a regular
 * destination, creates with O_EXCL|O_NOFOLLOW/0700, and removes only the entry
 * it created on failure. The strict root-owner policy is selected with NULL
 * overrides; the host test exercises the same code with injected policy/IO. */

/* Mark every installed module disabled before ksud runs. A broken module
 * otherwise loads on the next boot and bootloops the device. */
static int disable_modules(void)
{
    DIR *dir = opendir(MODULES_DIR);
    if (!dir)
        return -1;

    int rc = 0;
    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (ent->d_name[0] == '.')
            continue;
        char path[256];
        snprintf(path, sizeof(path), MODULES_DIR "/%s/disable", ent->d_name);
        if (touch(path) != 0) rc = -1;
    }
    closedir(dir);
    return rc;
}

/* Strictly read the 16-byte nonce written by exp.c before createOrphanProcess.
 * The file must be a regular file of exactly DF_NONCE_CHARS lowercase hex
 * bytes; anything else is unknown and no reboot may be authorized. */
static int read_nonce(char out[DF_NONCE_CHARS + 1])
{
    struct stat st;
    size_t off = 0;
    int fd;

    fd = open(DF_NONCE_PATH, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0)
        return -1;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size != DF_NONCE_CHARS) {
        close(fd);
        return -1;
    }
    while (off < (size_t)st.st_size) {
        ssize_t n = read(fd, out + off, (size_t)st.st_size - off);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            close(fd);
            return -1;
        }
        off += (size_t)n;
    }
    close(fd);
    out[DF_NONCE_CHARS] = '\0';
    return df_nonce_is_valid(out) ? 0 : -1;
}

/* Strict read of the app-private recovery status written by exp.c. ONLY the
 * exact bytes "<nonce>\n" in a regular file count as verified restore for
 * THIS invocation: the nonce is the one strictly read from dfinv and bound to
 * every final token. A plain stale/foreign "ok\n" is UNBOUND and insufficient
 * authorization; so are an absent file, "fail\n", a partial/oversize file, a
 * symlink or any malformed nonce. All of them fail closed and disable the
 * reboot path. Shared-state clearing and channel syntax are NOT invocation
 * proof; only the byte-equal nonce binding is. */
static int read_recovery_ok(void *ctx, const char *nonce)
{
    struct stat st;
    char buf[DF_RECOVERY_STATUS_CHARS];
    size_t off = 0;
    int fd;

    (void)ctx;
    fd = open(DF_RECOVER_PATH, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0)
        return -1;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) ||
        st.st_size != (off_t)sizeof(buf)) {
        close(fd);
        return -1;
    }
    while (off < sizeof(buf)) {
        ssize_t n = read(fd, buf + off, sizeof(buf) - off);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            close(fd);
            return -1;
        }
        off += (size_t)n;
    }
    close(fd);
    return df_recovery_status_is_bound_ok(buf, sizeof(buf), nonce) ? 1 : -1;
}

/* Publish a bootstrap failure that occurred before a run nonce could be
 * established. The canonical token carries the RESERVED identity and is
 * atomically written to an app-owned file, never to /dev/dffinal with a
 * reserved/forged identity. Returns 0 on success, -1 if publication failed
 * (the caller still reports the failure as rc=1). */
static int publish_bootstrap_failure(void)
{
    char token[DF_FINAL_TOKEN_CAP];

    if (df_format_final_token(token, sizeof(token), DF_RESERVED_BOOTSTRAP_NONCE,
                              BOOTSTRAP_FAILED) < 0)
        return -1;
    return df_publish_final(DF_BOOTFAIL_TMP, DF_BOOTFAIL_PATH, token);
}

/* A transient marker-write fault must not leave the consumer waiting out its
 * whole budget for a status that will never arrive. Retry a small bounded
 * number of times; if all attempts fail, say so explicitly (the consumer then
 * reports rc=2/unknown instead of this bootstrap failure). */
static int publish_bootstrap_failure_bounded(void)
{
    int attempt, rc = -1;

    for (attempt = 1; attempt <= 3; attempt++) {
        rc = publish_bootstrap_failure();
        if (rc == 0)
            return 0;
        if (attempt < 3)
            usleep(10000);
    }
    printf("bootstrap: bootfail publication FAILED after 3 attempts; "
           "consumer cannot observe this bootstrap failure (may report rc=2)\n");
    return rc;
}

/* A producer that reached no outcome published nothing on the normal channel.
 * Publish the reserved-nonce bootstrap failure once so the consumer does not
 * wait out its budget for a token that will never arrive, then report rc=2.
 * DF_OUTCOME_NONE is never itself published as a normal token. */
static int finish(enum df_outcome o)
{
    if (o == DF_OUTCOME_NONE)
        (void)publish_bootstrap_failure_bounded();
    return df_outcome_to_rc(o);
}

/* Build the injected IO and let the pure protocol decide + publish the one
 * final outcome. The strictly-read nonce is passed to the recovery gate, so a
 * stale "ok\n" cannot satisfy it. All progress markers are informational;
 * nothing here reads a marker to derive a result. */
static enum df_outcome decide_and_publish(int late_load_rc, int soft_reboot,
                                          const char *nonce)
{
    struct status_io io;
    memset(&io, 0, sizeof(io));
    io.now_ms          = io_now_ms;
    io.sleep_ms        = io_sleep_ms;
    io.read_final      = io_read_final;
    io.publish_final   = io_publish_final;
    io.late_load_state = io_late_load_state;
    io.recovery_ok     = read_recovery_ok;
    io.wait_child      = wait_child_bounded;
    io.report          = io_report;
    io.nonce           = nonce;
    return producer_decide_and_publish(&io, NULL, late_load_rc, soft_reboot,
                                       LATE_LOAD_WAIT_MS, SOFT_REBOOT_WAIT_MS);
}

int main(void)
{
    char su_manager[256];
    char nonce[DF_NONCE_CHARS + 1];
    int soft_reboot = 0, disable_mods = 0;
    int late_load_rc;

    /* Establish run identity BEFORE touching /dev or staging. A missing or
     * malformed nonce means bootstrap failed before any bound outcome can
     * exist: publish BOOTSTRAP_FAILED on the separate reserved-nonce channel
     * (rc=1, NOT an unknown rc=2), disable late-load and reboot, and leave
     * /dev and staging completely untouched. */
    if (read_nonce(nonce) != 0) {
        (void)publish_bootstrap_failure_bounded();
        printf("bootstrap: nonce missing/malformed; bootfail handling done, no reboot\n");
        return DF_RC_ERROR;
    }

    touch("/dev/dfm1");
    if (read_prefs(su_manager, sizeof(su_manager), &soft_reboot, &disable_mods) != 0) {
        touch("/dev/dfme0");
        return finish(decide_and_publish(-1, 0, nonce));
    }

    touch("/dev/dfm2");
    if (adopt_zygote_env())
        touch("/dev/dfmw0");

    touch("/dev/dfm3");
    if (set_partitions_ro())
        touch("/dev/dfmw1");

    if (disable_mods) {
        touch("/dev/dfm4");
        if (disable_modules()) {
            touch("/dev/dfme1");
            return finish(decide_and_publish(-1, 0, nonce));
        }
    }

    touch("/dev/dfm5");
    if (mkdir(KSUD_STAGE_DIR, 0700) != 0 && errno != EEXIST)
        printf("bootstrap: mkdir %s failed: %s\n", KSUD_STAGE_DIR, strerror(errno));
    /* df_stage_file() fails closed if the directory is not root-owned/0700-
     * style, so a failed mkdir here needs no recovery action of its own. */
    if (df_stage_file(KSUD_STAGE_SRC, KSUD_STAGE_DIR, KSUD_STAGE_NAME,
                      NULL, NULL) != 0)
        return finish(decide_and_publish(-1, 0, nonce));

    late_load_rc = run_ctx(NULL, (char *[]){ KSUD_STAGE, "late-load",
                                            "--package-name", su_manager, NULL });
    return finish(decide_and_publish(late_load_rc, soft_reboot, nonce));
}
