/* Modified 2026-11-25 (DFRoot GZIF): define the injectable, hardened loader-staging contract. See NOTICE. */
#ifndef DFROOT_LOADER_STAGE_H
#define DFROOT_LOADER_STAGE_H

#include <stddef.h>
#include <sys/stat.h>
#include <sys/types.h>

/* Strict POSIX_C_SOURCE hides the sticky bit on some libcs; the on-disk bit
 * value is stable and the host tests probe it explicitly. */
#ifndef S_ISVTX
#define S_ISVTX 01000
#endif

/*
 * FZG1 loader ABI: bootstrap copies the bundled ksud to this exact staged
 * entry, runs `late-load` on it, and the loader renames it to /data/adb/ksud.
 * Host tests inject a harmless temporary directory and a different name, but
 * the production defaults below are the only values bootstrap uses.
 */
#define LOADER_STAGE_DIR_DEFAULT "/data/local/tmp"
#define LOADER_STAGE_NAME_DEFAULT ".ksud-stage"
#define LOADER_STAGE_PATH_DEFAULT "/data/local/tmp/.ksud-stage"

#define LOADER_STAGE_PRIVATE_MODE 0600
#define LOADER_STAGE_EXEC_MODE 0755

#define LOADER_STAGE_DIR_MAX 256
#define LOADER_STAGE_NAME_MAX 128
#define LOADER_STAGE_PATH_MAX (LOADER_STAGE_DIR_MAX + LOADER_STAGE_NAME_MAX + 4)
#define LOADER_STAGE_DIAGNOSTIC_MAX 192

/*
 * All state for one staging attempt. The caller keeps this object for the
 * lifetime of the late-load command so loader_stage_restore() can undo the
 * temporary directory protection after that command returns.
 */
struct loader_stage {
    char directory[LOADER_STAGE_DIR_MAX];
    char name[LOADER_STAGE_NAME_MAX];
    char destination[LOADER_STAGE_PATH_MAX];
    mode_t original_mode;   /* staging directory mode before the sticky bit */
    int sticky_applied;     /* install() changed the mode and must restore it */
    int created;            /* install() created this exact destination inode */
    dev_t created_dev;
    ino_t created_ino;
    char error[LOADER_STAGE_DIAGNOSTIC_MAX];
};

/* Bind to a staging directory and destination name. NULL selects the FZG1
 * production defaults. Names must be one safe path component. */
int loader_stage_configure(struct loader_stage *stage, const char *directory,
                           const char *name);

/*
 * Hardened copy of source_path into the configured staging entry.
 *
 * The staging directory is checked with lstat (real directory, never a link),
 * its original mode is saved, and the sticky bit is applied with a checked
 * chmod before any destination is created. An existing destination of ANY
 * type is rejected without being followed or removed. The source is opened
 * O_RDONLY|O_NOFOLLOW|O_CLOEXEC and must be a regular file. The destination is
 * opened O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC with a private mode,
 * verified regular, written with an EINTR-safe full-write loop, fsynced, and
 * only then fchmod'd to LOADER_STAGE_EXEC_MODE, with checked closes.
 *
 * Returns 0 only after the complete verified copy. On every failure the
 * destination inode actually created by this call is removed (dev/ino checked
 * before unlink) and the directory mode is restored.
 */
int loader_stage_install(struct loader_stage *stage, const char *source_path);

/*
 * Undo the staging directory protection and discard the staged entry if it is
 * still our exact inode. Must be called after the late-load command returns,
 * regardless of that command's result. Idempotent: safe to call again after a
 * failed install(). A replaced destination is never unlinked.
 */
int loader_stage_restore(struct loader_stage *stage);

const char *loader_stage_last_error(const struct loader_stage *stage);
const char *loader_stage_destination(const struct loader_stage *stage);

/* Hook points are a stable part of the source contract even though the
 * mutator itself is compiled only into host tests. */
enum loader_stage_hook_point {
    LOADER_STAGE_HOOK_AFTER_DEST_LSTAT = 0,
    LOADER_STAGE_HOOK_BEFORE_FCHMOD = 1
};

#ifdef LOADER_STAGE_TEST_HOOKS
/* Host-only fault injection. Production binaries omit this entirely. */
enum loader_stage_fault {
    LOADER_STAGE_FAULT_NONE = 0,
    LOADER_STAGE_FAULT_WRITE = 1u << 0,
    LOADER_STAGE_FAULT_SHORT_WRITE = 1u << 1,
    LOADER_STAGE_FAULT_FSYNC = 1u << 2,
    LOADER_STAGE_FAULT_CLOSE = 1u << 3,
    LOADER_STAGE_FAULT_RESTORE_CHMOD = 1u << 4
};
void loader_stage_inject_fault(int fault);

typedef void (*loader_stage_test_mutator)(void *context, int point);
void loader_stage_set_test_mutator(loader_stage_test_mutator mutator, void *context);
#endif

#endif
