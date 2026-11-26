/* Modified 2026-11-26 (PR #3, Phase C): fd-anchored producer-side staging for
 * the rebuilt Samsung loaders. See NOTICE. */
#ifndef DFROOT_BOOTSTRAP_STAGE_H
#define DFROOT_BOOTSTRAP_STAGE_H

#include <stddef.h>
#include <sys/types.h>

/* Pure policy helpers. No syscalls, no globals, no fixed paths: callers pass
 * synthetic uid/mode/size values in tests and the fstat() of an already-open
 * fd in production.
 *
 * The staging directory must be root-owned, a directory, and not
 * group/world-writable. The staged entry must be root-owned, a regular file
 * with the exact 0700 mode and non-empty. The rebuilt loaders are required to
 * perform the same fd-anchored owner/mode/content validation before promoting the stage
 * to /data/adb/ksud; the producer must never hand them anything less. */
int df_stage_dir_ok(uid_t uid, mode_t mode);
int df_stage_file_ok(uid_t uid, mode_t mode, off_t size);

/* Injectable I/O for the copy loop. ctx is opaque to the writer and lets host
 * tests inject EINTR, short/zero-progress writes and fsync failures without
 * privileges or real fault devices. Production uses the real
 * read/write/fchmod/fsync adapter implemented in bootstrap_stage.c. */
struct df_stage_copy_io {
    void *ctx;
    ssize_t (*read)(void *ctx, int fd, void *buf, size_t n);
    ssize_t (*write)(void *ctx, int fd, const void *buf, size_t n);
    int (*set_mode)(void *ctx, int fd, mode_t mode);
    int (*sync)(void *ctx, int fd);
};

/* EINTR-safe full copy. Returns 0 only after every source byte was written,
 * the destination mode was pinned to 0700 and the file was fsynced. A
 * zero-byte source, a zero-progress write or any other error returns -1.
 * This function never unlinks anything: cleanup belongs to df_stage_file(). */
int df_stage_copy_fd(int src_fd, int dst_fd, const struct df_stage_copy_io *io);

/* Test-only policy overrides. Production MUST pass NULL for both pointers so
 * the strict root-owner policies above are used. A host test passes
 * substitutes that ignore uid (the host cannot create root-owned files) but
 * keep the type/mode/size checks. */
struct df_stage_policy {
    int (*dir_ok)(uid_t uid, mode_t mode);
    int (*file_ok)(uid_t uid, mode_t mode, off_t size);
};

/* Real-filesystem staging: validate the opened directory fd, open and stat
 * the app-private source (regular + non-empty; no uid check because the
 * source lives in app-private storage), replace only a pre-existing regular
 * destination (a symlink or other entry type is rejected untouched), create
 * with openat(O_EXCL|O_NOFOLLOW, 0700), copy, re-check the created fd with
 * file_ok, fsync the file, close it and fsync the directory. Any failure
 * closes all fds, removes ONLY the entry we created, and returns -1. */
int df_stage_file(const char *src, const char *dir, const char *name,
                  const struct df_stage_policy *policy,
                  const struct df_stage_copy_io *io);

#endif /* DFROOT_BOOTSTRAP_STAGE_H */
