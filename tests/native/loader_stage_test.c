/* Host-only tests for the producer-side staging contract used by the rebuilt
 * Samsung loaders. No root, no /dev access, no fork/exec/system, no privileged
 * payload: every temporary file lives in a mkdtemp directory. Production uses
 * the strict root-owner policy; the host substitutes a policy that ignores uid
 * (it cannot create root-owned files) while keeping type/mode/size checks. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "../../app/src/main/jni/bootstrap_stage.h"

static int g_cases;
static int g_failures;

static void expect(const char *name, int ok)
{
    g_cases++;
    if (!ok) {
        g_failures++;
        printf("FAIL %s\n", name);
    } else {
        printf("ok   %s\n", name);
    }
}

static char workdir[512];
static char stage_dir[600];
static char src_path[600];
static char dst_path[600];
static char aux_path[600];

static int host_dir_ok(uid_t uid, mode_t mode)
{
    (void)uid; /* host is not root; uid is exercised by the synthetic rows */
    return S_ISDIR(mode) && (mode & (mode_t)0022) == 0;
}

static int host_file_ok(uid_t uid, mode_t mode, off_t size)
{
    (void)uid;
    return S_ISREG(mode) && (mode & (mode_t)0777) == (mode_t)0700 && size > 0;
}

static const struct df_stage_policy host_policy = { host_dir_ok, host_file_ok };

static int write_file(const char *path, const void *data, size_t len)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0700);
    size_t off = 0;
    if (fd < 0)
        return -1;
    if (fchmod(fd, (mode_t)0700) != 0) {
        close(fd);
        return -1;
    }
    while (off < len) {
        ssize_t w = write(fd, (const char *)data + off, len - off);
        if (w <= 0) {
            close(fd);
            return -1;
        }
        off += (size_t)w;
    }
    return close(fd);
}

static int read_file(const char *path, void *buf, size_t cap, size_t *out_len)
{
    struct stat st;
    size_t off = 0;
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0)
        return -1;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 0 ||
        (size_t)st.st_size > cap) {
        close(fd);
        return -1;
    }
    while (off < (size_t)st.st_size) {
        ssize_t n = read(fd, (char *)buf + off, (size_t)st.st_size - off);
        if (n <= 0) {
            close(fd);
            return -1;
        }
        off += (size_t)n;
    }
    close(fd);
    *out_len = off;
    return 0;
}

static void reset_dest(void)
{
    (void)unlink(dst_path);
}

/* Some scratch mounts (FUSE/NTFS-style) ignore chmod and always report 0777;
 * they cannot exercise a 0700 staging contract. Probe the created directory
 * and fall back to another base rather than silently weakening the test. */
static int try_setup(const char *base)
{
    struct stat st;
    char tmpl[512];

    snprintf(tmpl, sizeof(tmpl), "%s/dfroot-loader-stage-XXXXXX", base);
    if (!mkdtemp(tmpl))
        return -1;
    snprintf(workdir, sizeof(workdir), "%s", tmpl);
    snprintf(stage_dir, sizeof(stage_dir), "%s/stage", tmpl);
    snprintf(src_path, sizeof(src_path), "%s/ksud", tmpl);
    snprintf(dst_path, sizeof(dst_path), "%s/stage/.ksud-stage", tmpl);
    snprintf(aux_path, sizeof(aux_path), "%s/other", tmpl);
    if (mkdir(stage_dir, 0700) == 0 &&
        lstat(stage_dir, &st) == 0 &&
        S_ISDIR(st.st_mode) && (st.st_mode & (mode_t)0777) == (mode_t)0700)
        return 0;
    (void)rmdir(stage_dir);
    (void)rmdir(workdir);
    return -1;
}

static int setup(void)
{
    const char *env_base = getenv("TMPDIR");

    if (env_base && *env_base && try_setup(env_base) == 0)
        return 0;
    if (try_setup("/mnt/240G_SSD/opencode_tmp") == 0)
        return 0;
    return try_setup("/tmp");
}

static void teardown(void)
{
    (void)unlink(dst_path);
    (void)unlink(src_path);
    (void)unlink(aux_path);
    (void)rmdir(stage_dir);
    (void)rmdir(workdir);
}

/* ---- Pure policy rows: synthetic uid/mode/size, no files involved ---- */

static void test_dir_policy(void)
{
    expect("dir root 0700 accepted", df_stage_dir_ok(0, S_IFDIR | 0700) == 1);
    expect("dir root 0755 accepted", df_stage_dir_ok(0, S_IFDIR | 0755) == 1);
    expect("dir shell-owned rejected", df_stage_dir_ok(1000, S_IFDIR | 0700) == 0);
    expect("dir group-writable rejected", df_stage_dir_ok(0, S_IFDIR | 0770) == 0);
    expect("dir world-writable rejected", df_stage_dir_ok(0, S_IFDIR | 0777) == 0);
    expect("dir non-directory rejected", df_stage_dir_ok(0, S_IFREG | 0700) == 0);
    expect("dir symlink rejected", df_stage_dir_ok(0, S_IFLNK | 0777) == 0);
}

static void test_file_policy(void)
{
    expect("file root regular 0700 accepted",
           df_stage_file_ok(0, S_IFREG | 0700, 16) == 1);
    expect("file shell-owned rejected",
           df_stage_file_ok(1000, S_IFREG | 0700, 16) == 0);
    expect("file non-regular rejected",
           df_stage_file_ok(0, S_IFDIR | 0700, 16) == 0);
    expect("file symlink rejected",
           df_stage_file_ok(0, S_IFLNK | 0700, 16) == 0);
    expect("file zero size rejected",
           df_stage_file_ok(0, S_IFREG | 0700, 0) == 0);
    expect("file wrong mode rejected",
           df_stage_file_ok(0, S_IFREG | 0644, 16) == 0);
    expect("file world-writable rejected",
           df_stage_file_ok(0, S_IFREG | 0777, 16) == 0);
}

/* ---- Real-filesystem staging with injected policy (uid ignored) ---- */

static const unsigned char payload[300] = {
    /* Includes NUL bytes: the copy loop must be binary-safe. */
    0x64, 0x00, 0x66, 0x72, 0x6f, 0x6f, 0x74, 0x00, 0x73, 0x74, 0x61, 0x67,
    0x65, 0x2d, 0x63, 0x6f, 0x6e, 0x74, 0x72, 0x61, 0x63, 0x74, 0x00, 0xff,
    0xfe, 0x01, 0x02, 0x03, 0x00, 0x7f, 0x80, 0x00
};

static int payload_equal(const char *name)
{
    unsigned char got[sizeof(payload) + 64];
    size_t len = 0;
    struct stat st;
    int ok;

    ok = read_file(dst_path, got, sizeof(got), &len) == 0 &&
         len == sizeof(payload) && memcmp(got, payload, sizeof(payload)) == 0;
    ok = ok && lstat(dst_path, &st) == 0 && S_ISREG(st.st_mode) &&
         (st.st_mode & (mode_t)0777) == (mode_t)0700;
    expect(name, ok);
    return ok;
}

static void test_valid_source(void)
{
    reset_dest();
    expect("write valid source", write_file(src_path, payload, sizeof(payload)) == 0);
    expect("valid source stages successfully",
           df_stage_file(src_path, stage_dir, ".ksud-stage",
                         &host_policy, NULL) == 0);
    (void)payload_equal("valid stage is 0700 regular with identical bytes");
}

static void test_replace_destination(void)
{
    const char stale[] = "stale-stage-content";

    reset_dest();
    expect("write valid source for replacement",
           write_file(src_path, payload, sizeof(payload)) == 0);
    expect("write stale destination", write_file(dst_path, stale, sizeof(stale) - 1) == 0);
    expect("pre-existing destination replaced",
           df_stage_file(src_path, stage_dir, ".ksud-stage",
                         &host_policy, NULL) == 0);
    (void)payload_equal("replacement has new bytes and 0700 mode");
}

static void test_symlink_destination(void)
{
    const char keep[] = "untouched";
    char buf[64];
    size_t len = 0;
    struct stat st;

    reset_dest();
    expect("write valid source for symlink case",
           write_file(src_path, payload, sizeof(payload)) == 0);
    expect("write symlink target", write_file(aux_path, keep, sizeof(keep) - 1) == 0);
    expect("create destination symlink", symlink(aux_path, dst_path) == 0);
    expect("destination symlink rejected",
           df_stage_file(src_path, stage_dir, ".ksud-stage",
                         &host_policy, NULL) == -1);
    expect("hostile symlink left untouched",
           lstat(dst_path, &st) == 0 && S_ISLNK(st.st_mode));
    expect("symlink target not clobbered",
           read_file(aux_path, buf, sizeof(buf), &len) == 0 &&
           len == sizeof(keep) - 1 && memcmp(buf, keep, len) == 0);
}

static void test_bad_sources(void)
{
    struct stat st;

    reset_dest();
    (void)unlink(aux_path); /* earlier case may have used this path as a file */
    expect("make directory source", mkdir(aux_path, 0700) == 0);
    expect("non-regular source rejected",
           df_stage_file(aux_path, stage_dir, ".ksud-stage",
                         &host_policy, NULL) == -1);
    expect("no destination after non-regular source", lstat(dst_path, &st) != 0);
    (void)rmdir(aux_path);

    expect("write empty source", write_file(src_path, "", 0) == 0);
    expect("zero-size source rejected",
           df_stage_file(src_path, stage_dir, ".ksud-stage",
                         &host_policy, NULL) == -1);
    expect("no destination after zero-size source", lstat(dst_path, &st) != 0);
}

/* ---- Fault injection into the copy loop: cleanup must fail closed ---- */

struct fault_ctx {
    int write_calls;
    int eintr_read_used;
    int eintr_write_used;
};

static ssize_t real_read_io(void *ctx, int fd, void *buf, size_t n)
{
    (void)ctx;
    return read(fd, buf, n);
}

static ssize_t real_write_io(void *ctx, int fd, const void *buf, size_t n)
{
    (void)ctx;
    return write(fd, buf, n);
}

static int real_mode_io(void *ctx, int fd, mode_t mode)
{
    (void)ctx;
    return fchmod(fd, mode);
}

static int real_sync_io(void *ctx, int fd)
{
    (void)ctx;
    return fsync(fd);
}

static ssize_t short_write_io(void *ctx, int fd, const void *buf, size_t n)
{
    struct fault_ctx *f = (struct fault_ctx *)ctx;
    f->write_calls++;
    if (f->write_calls == 1 && n > 3)
        return write(fd, buf, 3); /* progress, then hard failure */
    errno = EIO;
    return -1;
}

static int failing_sync_io(void *ctx, int fd)
{
    (void)ctx;
    (void)fd;
    errno = EIO;
    return -1;
}

static ssize_t eintr_read_io(void *ctx, int fd, void *buf, size_t n)
{
    struct fault_ctx *f = (struct fault_ctx *)ctx;
    if (!f->eintr_read_used) {
        f->eintr_read_used = 1;
        errno = EINTR;
        return -1;
    }
    return read(fd, buf, n);
}

static ssize_t eintr_write_io(void *ctx, int fd, const void *buf, size_t n)
{
    struct fault_ctx *f = (struct fault_ctx *)ctx;
    if (!f->eintr_write_used) {
        f->eintr_write_used = 1;
        errno = EINTR;
        return -1;
    }
    return write(fd, buf, n);
}

static void test_fault_injection(void)
{
    struct stat st;
    struct fault_ctx ctx;
    struct df_stage_copy_io short_io;
    struct df_stage_copy_io sync_io;
    struct df_stage_copy_io eintr_io;

    expect("write valid source for fault cases",
           write_file(src_path, payload, sizeof(payload)) == 0);

    reset_dest();
    memset(&ctx, 0, sizeof(ctx));
    short_io.ctx = &ctx;
    short_io.read = real_read_io;
    short_io.write = short_write_io;
    short_io.set_mode = real_mode_io;
    short_io.sync = real_sync_io;
    expect("short write fails closed",
           df_stage_file(src_path, stage_dir, ".ksud-stage",
                         &host_policy, &short_io) == -1);
    expect("short write removes our destination", lstat(dst_path, &st) != 0);

    reset_dest();
    memset(&ctx, 0, sizeof(ctx));
    sync_io.ctx = &ctx;
    sync_io.read = real_read_io;
    sync_io.write = real_write_io;
    sync_io.set_mode = real_mode_io;
    sync_io.sync = failing_sync_io;
    expect("fsync failure fails closed",
           df_stage_file(src_path, stage_dir, ".ksud-stage",
                         &host_policy, &sync_io) == -1);
    expect("fsync failure removes our destination", lstat(dst_path, &st) != 0);

    reset_dest();
    memset(&ctx, 0, sizeof(ctx));
    eintr_io.ctx = &ctx;
    eintr_io.read = eintr_read_io;
    eintr_io.write = eintr_write_io;
    eintr_io.set_mode = real_mode_io;
    eintr_io.sync = real_sync_io;
    expect("EINTR read/write retried successfully",
           df_stage_file(src_path, stage_dir, ".ksud-stage",
                         &host_policy, &eintr_io) == 0);
    (void)payload_equal("EINTR retry keeps identical bytes and 0700 mode");
}

int main(void)
{
    if (setup() != 0) {
        printf("loader_stage_test: mkdtemp/mkdir failed: %s\n", strerror(errno));
        return 1;
    }

    test_dir_policy();
    test_file_policy();
    test_valid_source();
    test_replace_destination();
    test_symlink_destination();
    test_bad_sources();
    test_fault_injection();

    teardown();
    printf("loader_stage_test: %d cases, %d failures\n", g_cases, g_failures);
    return g_failures ? 1 : 0;
}
