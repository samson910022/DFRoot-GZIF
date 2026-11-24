/* Modified 2026-11-17 (DFRoot GZIF): create /data/adb and stage /data/local/tmp/.ksud-stage before late-load. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): read only the admitted run-config artifact, never SharedPreferences XML. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): publish checked run-scoped terminal markers and bound the late-load wait. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): use the shared composed deadlines and bound the soft-reboot command wait without killing. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): replace shell cp with hardened sticky-bit exclusive staging and unconditional mode restoration. See NOTICE. */
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

/* Publish the run-scoped terminal outcome and exit with exactly that code.
 * A marker write failure is reported but never downgrades the outcome. */
static int terminal(int code)
{
    if (run_status_write(code) != 0) {
        fprintf(stderr, "bootstrap: terminal marker write failed: %s\n",
                run_status_last_error());
    }
    return code;
}

static int run_ctx(const char *ctx, char *const argv[])
{
    pid_t pid = fork();
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
    int status = 0;
    pid_t waited;
    do {
        waited = waitpid(pid, &status, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited != pid)
        return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/* Bounded variant for the soft-reboot command. The waitpid poll is EINTR-safe
 * and non-blocking; on timeout the child is left alone (no forced kill) and
 * the caller maps the timeout to rc=6. */
static int run_ctx_timed(const char *ctx, char *const argv[], int64_t limit_ms,
                         int *timed_out)
{
    pid_t pid = fork();
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
    if (timed_out != NULL)
        *timed_out = 0;
    int64_t start = monotonic_ms();
    int status = 0;
    for (;;) {
        pid_t waited;
        do {
            waited = waitpid(pid, &status, WNOHANG);
        } while (waited < 0 && errno == EINTR);
        if (waited == pid)
            return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
        if (waited < 0)
            return -1;
        int64_t now = monotonic_ms();
        if (now < 0 || !run_status_wait_continues(now - start, limit_ms)) {
            if (timed_out != NULL)
                *timed_out = 1;
            return -1;
        }
        usleep(100000);
    }
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
    int late_rc = run_ctx(NULL, (char *[]){ "/data/local/tmp/.ksud-stage", "late-load",
                                           "--package-name", config.manager, NULL });
    /* Restore the staging directory mode whether or not late-load succeeded.
     * A restore failure is a generic bootstrap error (rc=1); the terminal
     * marker protocol and all global progress markers stay unchanged. */
    int restored = loader_stage_restore(&stage);
    if (restored != 0) {
        fprintf(stderr, "bootstrap: staging mode restoration failed: %s\n",
                loader_stage_last_error(&stage));
    }
    if (late_rc != 0) {
        touch("/dev/dfme2");
        return terminal(RUN_STATUS_TERMINAL_ERROR);
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

    int64_t start = monotonic_ms();
    for (;;) {
        if (!late_load_running()) {
            break;
        }
        int64_t now = monotonic_ms();
        if (now < 0 || !run_status_wait_continues(now - start,
                                                  RUN_STATUS_LATE_LOAD_WAIT_MS)) {
            fprintf(stderr, "bootstrap: late-load wait exceeded %d ms\n",
                    RUN_STATUS_LATE_LOAD_WAIT_MS);
            return terminal(RUN_STATUS_TERMINAL_ERROR);
        }
        sleep(1);
    }

    int soft_timed_out = 0;
    int soft_rc = run_ctx_timed("u:r:ksu:s0",
                                (char *[]){ KSUD, "soft-reboot", NULL },
                                RUN_STATUS_SOFT_REBOOT_WAIT_MS, &soft_timed_out);
    if (soft_timed_out) {
        fprintf(stderr, "bootstrap: soft-reboot command wait exceeded %d ms\n",
                RUN_STATUS_SOFT_REBOOT_WAIT_MS);
        return terminal(RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    }
    return terminal(run_status_soft_reboot_code(soft_rc));
}
