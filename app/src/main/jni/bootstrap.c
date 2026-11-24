/* Modified 2026-11-17 (DFRoot GZIF): create /data/adb and stage /data/local/tmp/.ksud-stage before late-load. See NOTICE. */
/* Modified 2026-11-24 (PR #3 hardening): secure staging, fail-closed markers and wait handling. */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define BLKROSET   0x125d
/* The bundled Samsung-patched loaders have /data/local/tmp/.ksud-stage baked
 * into their self-staging constant, so the path must stay. Security is
 * preserved by stage_file() (unlink + O_EXCL|O_NOFOLLOW + 0700 + fsync). */
#define KSUD_STAGE "/data/local/tmp/.ksud-stage"
#define KSUD_STAGE_SRC "/data/user_de/0/df.root/ksud"
#define KSUD       "/data/adb/ksud"
#define PREFS_PATH "/data/user_de/0/df.root/shared_prefs/dfroot.xml"
#define MODULES_DIR "/data/adb/modules"

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

/* Returns 1 when a ksud late-load process exists, 0 when it is absent, and
 * -1 when /proc cannot be scanned. Unknown must never be treated as absent. */
static int late_load_running(void)
{
    DIR *d = opendir("/proc");
    if (!d)
        return -1;
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
    if (TEMP_FAILURE_RETRY(waitpid(pid, &status, 0)) < 0)
        return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
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

/* Copy src into dst. O_EXCL|O_NOFOLLOW + 0700 close the classic symlink race
 * at creation time. The destination path itself is fixed by the bundled
 * loaders' baked-in staging constant (/data/local/tmp/.ksud-stage), so a
 * shell-uid process could still replace the file between close() and the
 * loader's rename(); that residual risk is documented in PORTING-GZIF.md and
 * can only be removed by rebuilding the loaders. */
static int stage_file(const char *src, const char *dst)
{
    int sfd = open(src, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (sfd < 0) {
        printf("bootstrap: open %s failed: %s\n", src, strerror(errno));
        return -1;
    }
    unlink(dst);
    int dfd = open(dst, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0700);
    if (dfd < 0) {
        printf("bootstrap: create %s failed: %s\n", dst, strerror(errno));
        close(sfd);
        return -1;
    }

    char buf[65536];
    for (;;) {
        ssize_t r = read(sfd, buf, sizeof(buf));
        if (r == 0) break;
        if (r < 0) {
            printf("bootstrap: read %s failed: %s\n", src, strerror(errno));
            goto fail;
        }
        size_t off = 0;
        while (off < (size_t)r) {
            ssize_t w = write(dfd, buf + off, (size_t)r - off);
            if (w <= 0) {
                printf("bootstrap: write %s failed: %s\n", dst, strerror(errno));
                goto fail;
            }
            off += (size_t)w;
        }
    }
    if (fchmod(dfd, 0700) < 0 || fsync(dfd) < 0) {
        printf("bootstrap: finalize %s failed: %s\n", dst, strerror(errno));
        goto fail;
    }
    close(sfd);
    close(dfd);
    return 0;

fail:
    close(sfd);
    close(dfd);
    unlink(dst);
    return -1;
}

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

static long elapsed_ms(const struct timespec *start, const struct timespec *now)
{
    return (now->tv_sec - start->tv_sec) * 1000L +
           (now->tv_nsec - start->tv_nsec) / 1000000L;
}

int main(void)
{
    touch("/dev/dfm1");
    char su_manager[256];
    int soft_reboot, disable_mods;
    if (read_prefs(su_manager, sizeof(su_manager), &soft_reboot, &disable_mods) != 0) {
        touch("/dev/dfme0");
        return 1;
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
            return 1;
        }
    }

    touch("/dev/dfm5");
    mkdir("/data/adb", 0700);
    if (stage_file(KSUD_STAGE_SRC, KSUD_STAGE) != 0) {
        touch("/dev/dfme2");
        return 1;
    }
    if (run_ctx(NULL, (char *[]){ KSUD_STAGE, "late-load", "--package-name", su_manager, NULL }) == 0) {
        /* Publish root-active (dfmsr) BEFORE success (dfm6). If publishing
         * dfmsr fails, do not publish dfm6: a false rc=0 is worse than a
         * timeout that asks the user to check the log. */
        int dfmsr_ok = 1;
        if (soft_reboot)
            dfmsr_ok = touch("/dev/dfmsr");
        if (!soft_reboot || dfmsr_ok) {
            if (touch("/dev/dfm6") != 0) {
                touch("/dev/dfme2");
                return 1;
            }
        } else {
            return 1;
        }

        if (soft_reboot) {
            /* Bound the wait with a monotonic clock, not sleep iterations. */
            struct timespec start, now;
            clock_gettime(CLOCK_MONOTONIC, &start);
            int lr = late_load_running();
            while (lr > 0) {
                clock_gettime(CLOCK_MONOTONIC, &now);
                if (elapsed_ms(&start, &now) >= 60000)
                    break;
                sleep(1);
                lr = late_load_running();
            }
            if (lr != 0) {
                /* Still running or unscannable: never authorize a reboot on
                 * unknown state. Root is already active (dfmsr -> rc=4). */
                touch("/dev/dfmw2");
            } else {
                int sr_rc = run_ctx("u:r:ksu:s0", (char *[]){ KSUD, "soft-reboot", NULL });
                if (sr_rc != 0) {
                    /* soft-reboot failed. Do NOT mark root as failed: dfmsr
                     * already told exp.c that root is active. */
                    touch("/dev/dfmw3");
                }
            }
        }
    } else {
        /* late-load failed: deliberately do NOT touch dfmsr, so exp.c reports
         * the genuine failure (dfme2 -> rc=1). */
        touch("/dev/dfme2");
    }

    return 0;
}
