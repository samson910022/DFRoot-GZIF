/* Modified 2026-11-17 (DFRoot GZIF): create /data/adb and stage /data/local/tmp/.ksud-stage before late-load. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): read only the admitted run-config artifact, never SharedPreferences XML. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): publish checked run-scoped terminal markers and bound the late-load wait. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): use the shared composed deadlines and bound the soft-reboot command wait without killing. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): replace shell cp with hardened sticky-bit exclusive staging and unconditional mode restoration. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): never report an unconfirmed publication as its intended success/error code. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): bound every child wait without killing, and publish rc7 on timeout. See NOTICE. */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "loader_stage.h"
#include "run_config_codec.h"
#include "run_status.h"

#define BLKROSET   0x125d
#define KSUD_STAGE "/data/user_de/0/df.root/ksud"
#define KSUD       "/data/adb/ksud"
#define RUN_CONFIG_PATH "/data/user_de/0/df.root/run_config"
#define MODULES_DIR "/data/adb/modules"

static int adopt_zygote_env(void)
{
    FILE *f = popen("pidof zygote64 zygote", "r");
    if (!f) return -1;
    int pid = 0;
    fscanf(f, "%d", &pid);
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
    for (char *p = buf, *end = buf + n; p < end; p += strlen(p) + 1)
        putenv(p);
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

static int late_load_running(void)
{
    DIR *d = opendir("/proc");
    if (!d)
        return 0;
    struct dirent *e;
    char path[64], buf[256];
    int found = 0;
    while ((e = readdir(d)) != NULL) {
        char *end;
        strtol(e->d_name, &end, 10);
        if (*end) continue;
        snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        int n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';
        for (int i = 0; i < n; i++)
            if (!buf[i]) buf[i] = ' ';
        if (strstr(buf, "ksud") && strstr(buf, "late-load")) {
            found = 1;
            break;
        }
    }
    closedir(d);
    return found;
}

static int64_t monotonic_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0)
        return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

/* Publish the run-scoped terminal outcome and exit with that code only when
 * the publication is fully confirmed. A failed or unconfirmed publication is
 * indeterminate (rc=7): the intended code is never returned, so a write/fsync/
 * close/link/directory-fsync failure can never be observed as success. */
static int terminal(int code)
{
    int write_result = run_status_write(code);
    int result = run_status_publication_exit_code(write_result, code);
    if (write_result != 0) {
        fprintf(stderr,
                "bootstrap: terminal marker publication unconfirmed: %s; reporting rc=%d\n",
                run_status_last_error(), result);
    }
    return result;
}

/* Bounded command launch. The wait is delegated to the shared, EINTR-safe,
 * non-blocking, non-killing run_status_wait_child_bounded(): on timeout the
 * child is left alone, *timed_out is set, and the caller maps the unobserved
 * outcome to rc=7 (never to a command success/failure code). A fork() failure
 * is different: no child exists, so *launch_failed is set and the caller maps
 * it to the ordinary local command failure instead of indeterminate. */
static int run_ctx_timed(const char *ctx, char *const argv[], int64_t limit_ms,
                         int *timed_out, int *launch_failed)
{
    pid_t pid = fork();
    if (pid < 0) {
        if (launch_failed != NULL)
            *launch_failed = 1;
        return -1;
    }
    if (pid == 0) {
        if (ctx) {
            int fd = open("/proc/self/attr/exec", O_WRONLY);
            if (fd >= 0) { write(fd, ctx, strlen(ctx)); close(fd); }
        }
        execv(argv[0], argv);
        _exit(127);
    }
    if (launch_failed != NULL)
        *launch_failed = 0;
    if (timed_out != NULL)
        *timed_out = 0;
    int exit_code = -1;
    int observed = run_status_wait_child_bounded(pid, limit_ms, &exit_code);
    if (observed == 1)
        return exit_code;
    if (observed == 0 && timed_out != NULL)
        *timed_out = 1;
    return -1;
}

/* Global /dev markers are progress diagnostics only; terminal outcomes are
 * published exclusively through the run-scoped marker protocol. */
static void touch(const char *path)
{
    int fd = open(path, O_CREAT | O_WRONLY | O_CLOEXEC | O_NOFOLLOW, 0644);
    if (fd >= 0)
        close(fd);
}

/* Mark every installed module disabled before ksud runs. A broken module
 * otherwise loads on the next boot and bootloops the device. */
static int disable_modules(void)
{
    DIR *dir = opendir(MODULES_DIR);
    if (!dir)
        return -1;

    struct dirent *ent;
    while ((ent = readdir(dir))) {
        if (ent->d_name[0] == '.')
            continue;
        char path[256];
        snprintf(path, sizeof(path), MODULES_DIR "/%s/disable", ent->d_name);
        touch(path);
    }
    closedir(dir);
    return 0;
}

int main(void)
{
    /* Pre-admission terminal channel: if the snapshot cannot be parsed there
     * is no admitted operation ID, so a reserved safe ID carries rc=1. */
    if (run_status_configure(RUN_STATUS_ROOT_DEFAULT, RUN_STATUS_PRE_ADMISSION_ID) != 0) {
        fprintf(stderr, "bootstrap: terminal protocol unavailable: %s\n",
                run_status_last_error());
        return RUN_STATUS_TERMINAL_ERROR;
    }

    touch("/dev/dfm1");
    struct run_config config;
    if (run_config_parse_file(RUN_CONFIG_PATH, &config) != 0) {
        touch("/dev/dfme0");
        return terminal(RUN_STATUS_TERMINAL_ERROR);
    }
    if (run_status_configure(RUN_STATUS_ROOT_DEFAULT, config.operation_id) != 0) {
        fprintf(stderr, "bootstrap: terminal protocol rejected: %s\n",
                run_status_last_error());
        return terminal(RUN_STATUS_TERMINAL_ERROR);
    }

    touch("/dev/dfm2");
    if (adopt_zygote_env())
        touch("/dev/dfmw0");

    touch("/dev/dfm3");
    if (set_partitions_ro())
        touch("/dev/dfmw1");

    if (config.disable_modules) {
        touch("/dev/dfm4");
        if (disable_modules()) {
            touch("/dev/dfme1");
            return terminal(RUN_STATUS_TERMINAL_ERROR);
        }
    }

    touch("/dev/dfm5");
    mkdir("/data/adb", 0755);
    /* Stage bundled ksud to the FZG1 staging path; that loader renames the
     * staged entry to /data/adb/ksud. No shell and no process signaling. */
    struct loader_stage stage;
    if (loader_stage_configure(&stage, NULL, NULL) != 0
        || loader_stage_install(&stage, KSUD_STAGE) != 0) {
        fprintf(stderr, "bootstrap: loader staging failed: %s\n",
                loader_stage_last_error(&stage));
        touch("/dev/dfme2");
        return terminal(RUN_STATUS_TERMINAL_ERROR);
    }
    /* One shared budget covers the late-load command AND the poll below.
     * Capture its start before the command wait so the poll receives only the
     * remaining time, never a fresh full limit. */
    int64_t late_start = monotonic_ms();
    int late_timed_out = 0;
    int late_launch_failed = 0;
    int late_rc = run_ctx_timed(NULL, (char *[]){ "/data/local/tmp/.ksud-stage", "late-load",
                                                "--package-name", config.manager, NULL },
                                RUN_STATUS_LATE_LOAD_WAIT_MS, &late_timed_out,
                                &late_launch_failed);
    /* Restore the staging directory mode whether or not late-load succeeded.
     * A restore failure is a generic bootstrap error (rc=1); the terminal
     * marker protocol and all global progress markers stay unchanged. */
    int restored = loader_stage_restore(&stage);
    if (restored != 0) {
        fprintf(stderr, "bootstrap: staging mode restoration failed: %s\n",
                loader_stage_last_error(&stage));
    }
    /* A deadline expiry is NOT a late-load failure: the child is still alive
     * and its effect is unknown, so the only honest terminal code is rc=7.
     * A fork() failure started no child and stays the ordinary rc=1 failure. */
    int late_terminal = run_status_late_load_launch_code(
            late_launch_failed, late_rc, late_timed_out);
    if (late_timed_out) {
        fprintf(stderr, "bootstrap: late-load command wait exceeded %d ms\n",
                RUN_STATUS_LATE_LOAD_WAIT_MS);
    }
    if (late_terminal != RUN_STATUS_TERMINAL_SUCCESS) {
        touch("/dev/dfme2");
        return terminal(late_terminal);
    }
    if (restored != 0) {
        return terminal(RUN_STATUS_TERMINAL_ERROR);
    }

    /* /dev/dfm6 remains the owner-visible late-load progress marker. The
     * terminal outcome is the run-scoped marker written below. */
    touch("/dev/dfm6");
    if (!config.soft_reboot) {
        return terminal(RUN_STATUS_TERMINAL_SUCCESS);
    }

    /* Reuse the shared late-load deadline: the poll gets only what the command
     * wait left, so the whole late-load phase cannot consume two full budgets. */
    int64_t late_now = monotonic_ms();
    int64_t late_elapsed = (late_start < 0 || late_now < 0) ? -1 : late_now - late_start;
    int64_t late_poll_budget = run_status_remaining_budget(
            late_elapsed, RUN_STATUS_LATE_LOAD_WAIT_MS);
    int64_t poll_start = monotonic_ms();
    for (;;) {
        if (!late_load_running()) {
            break;
        }
        int64_t now = monotonic_ms();
        int expired = poll_start < 0 || now < 0
                      || !run_status_wait_continues(now - poll_start, late_poll_budget);
        if (expired) {
            fprintf(stderr, "bootstrap: late-load wait exceeded the shared %d ms budget\n",
                    RUN_STATUS_LATE_LOAD_WAIT_MS);
            /* The late-load process is still alive and unobserved. Do not
             * kill it and do not claim an error: rc=7 is indeterminate. */
            return terminal(run_status_bounded_wait_timeout_code(expired));
        }
        sleep(1);
    }

    int soft_timed_out = 0;
    int soft_launch_failed = 0;
    int soft_rc = run_ctx_timed("u:r:ksu:s0",
                                (char *[]){ KSUD, "soft-reboot", NULL },
                                RUN_STATUS_SOFT_REBOOT_WAIT_MS, &soft_timed_out,
                                &soft_launch_failed);
    if (soft_timed_out) {
        fprintf(stderr, "bootstrap: soft-reboot command wait exceeded %d ms\n",
                RUN_STATUS_SOFT_REBOOT_WAIT_MS);
    }
    /* rc=4/6 is published only for a genuinely observed command exit. A
     * deadline expiry (or any unobserved exit) leaves the soft-reboot child
     * alive, so its effect is indeterminate: rc=7, never an early failure.
     * A fork() failure started no child and stays the ordinary rc=6 failure. */
    return terminal(run_status_soft_reboot_launch_code(
            soft_launch_failed, soft_rc, soft_timed_out));
}
