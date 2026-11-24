/* Modified 2026-11-25 (DFRoot GZIF): implement injectable sticky-bit/exclusive descriptor staging. See NOTICE. */
#define _POSIX_C_SOURCE 200809L
#include "loader_stage.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#ifdef LOADER_STAGE_TEST_HOOKS
static int injected_fault;
static loader_stage_test_mutator stage_mutator;
static void *stage_mutator_context;

void loader_stage_inject_fault(int fault)
{
    injected_fault = fault;
}

void loader_stage_set_test_mutator(loader_stage_test_mutator mutator, void *context)
{
    stage_mutator = mutator;
    stage_mutator_context = context;
}

static int fault_active(int mask)
{
    return (injected_fault & mask) != 0;
}

static void run_hook(int point)
{
    if (stage_mutator != NULL) {
        stage_mutator(stage_mutator_context, point);
    }
}
#else
#define fault_active(mask) (0)

static void run_hook(int point)
{
    (void)point;
}
#endif

const char *loader_stage_last_error(const struct loader_stage *stage)
{
    return stage == NULL ? "staging protocol is unavailable" : stage->error;
}

const char *loader_stage_destination(const struct loader_stage *stage)
{
    return stage == NULL ? "" : stage->destination;
}

static int stage_error(struct loader_stage *stage, const char *message)
{
    snprintf(stage->error, sizeof(stage->error), "%s", message);
    return -1;
}

static int stage_error_errno(struct loader_stage *stage, const char *what, int error_number)
{
    snprintf(stage->error, sizeof(stage->error), "%s: %s", what, strerror(error_number));
    return -1;
}

static int remember(char *first, size_t capacity, const char *message)
{
    if (first[0] == '\0') {
        snprintf(first, capacity, "%s", message);
    }
    return -1;
}

/* Single failure exit for install(): close any descriptors, undo the sticky
 * bit and remove only the exact inode this attempt created. The first
 * diagnostic wins; a cleanup failure is appended, never substituted. */
static int stage_abort(struct loader_stage *stage, int src_fd, int dst_fd,
                       const char *message)
{
    char original[LOADER_STAGE_DIAGNOSTIC_MAX];
    char cleanup[LOADER_STAGE_DIAGNOSTIC_MAX];
    snprintf(original, sizeof(original), "%s", message);
    if (dst_fd >= 0) {
        close(dst_fd);
    }
    if (src_fd >= 0) {
        close(src_fd);
    }
    if (loader_stage_restore(stage) != 0) {
        snprintf(cleanup, sizeof(cleanup), "%s", stage->error);
        snprintf(stage->error, sizeof(stage->error),
                 "%.96s (cleanup failed: %.64s)", original, cleanup);
    } else {
        snprintf(stage->error, sizeof(stage->error), "%.160s", original);
    }
    return -1;
}

static int stage_abort_errno(struct loader_stage *stage, int src_fd, int dst_fd,
                             const char *what)
{
    int saved = errno;
    char message[LOADER_STAGE_DIAGNOSTIC_MAX - 32];
    snprintf(message, sizeof(message), "%s: %s", what, strerror(saved));
    return stage_abort(stage, src_fd, dst_fd, message);
}

static int stage_abort_code(struct loader_stage *stage, int src_fd, int dst_fd,
                            const char *what, int error_number)
{
    char message[LOADER_STAGE_DIAGNOSTIC_MAX - 32];
    snprintf(message, sizeof(message), "%s: %s", what, strerror(error_number));
    return stage_abort(stage, src_fd, dst_fd, message);
}

static int valid_component(const char *value)
{
    size_t length = value == NULL ? 0 : strlen(value);
    if (length == 0 || length >= LOADER_STAGE_NAME_MAX
        || !strcmp(value, ".") || !strcmp(value, "..")) {
        return 0;
    }
    for (size_t i = 0; i < length; i++) {
        unsigned char c = (unsigned char)value[i];
        if (c < 0x20 || c == 0x7f || c == '/') {
            return 0;
        }
    }
    return 1;
}

static int valid_absolute_path(const char *value, size_t limit)
{
    size_t length = value == NULL ? 0 : strlen(value);
    if (length == 0 || length >= limit || value[0] != '/') {
        return 0;
    }
    for (size_t i = 0; i < length; i++) {
        unsigned char c = (unsigned char)value[i];
        if (c < 0x20 || c == 0x7f) {
            return 0;
        }
    }
    for (const char *p = value; (p = strstr(p, "..")) != NULL; ) {
        const char *after = p + 2;
        if ((p == value || p[-1] == '/') && (*after == '\0' || *after == '/')) {
            return 0;
        }
        p = after;
    }
    return 1;
}

int loader_stage_configure(struct loader_stage *stage, const char *directory,
                           const char *name)
{
    if (stage == NULL) {
        return -1;
    }
    memset(stage, 0, sizeof(*stage));
    if (directory == NULL) {
        directory = LOADER_STAGE_DIR_DEFAULT;
    }
    if (name == NULL) {
        name = LOADER_STAGE_NAME_DEFAULT;
    }
    if (!valid_absolute_path(directory, sizeof(stage->directory))) {
        return stage_error(stage, "staging directory path is invalid");
    }
    if (!valid_component(name)) {
        return stage_error(stage, "staging destination name is invalid");
    }
    memcpy(stage->directory, directory, strlen(directory) + 1);
    memcpy(stage->name, name, strlen(name) + 1);
    int written = snprintf(stage->destination, sizeof(stage->destination), "%s/%s",
                           stage->directory, stage->name);
    if (written < 0 || (size_t)written >= sizeof(stage->destination)) {
        return stage_error(stage, "staging destination path is too long");
    }
    return 0;
}

int loader_stage_restore(struct loader_stage *stage)
{
    if (stage == NULL) {
        return -1;
    }
    int result = 0;
    char first[LOADER_STAGE_DIAGNOSTIC_MAX];
    first[0] = '\0';

    if (stage->created) {
        struct stat st;
        if (lstat(stage->destination, &st) == 0) {
            if (st.st_dev != stage->created_dev || st.st_ino != stage->created_ino) {
                result = remember(first, sizeof(first),
                                  "staged entry was replaced before cleanup");
            } else if (unlink(stage->destination) != 0) {
                int saved = errno;
                char message[LOADER_STAGE_DIAGNOSTIC_MAX - 32];
                snprintf(message, sizeof(message), "staged entry unlink failed: %s",
                         strerror(saved));
                result = remember(first, sizeof(first), message);
            }
        } else if (errno != ENOENT) {
            int saved = errno;
            char message[LOADER_STAGE_DIAGNOSTIC_MAX - 32];
            snprintf(message, sizeof(message), "staged entry lstat failed: %s",
                     strerror(saved));
            result = remember(first, sizeof(first), message);
        }
        if (result == 0) {
            stage->created = 0;
            stage->created_dev = 0;
            stage->created_ino = 0;
        }
    }

    if (stage->sticky_applied) {
        int restore_result;
#ifdef LOADER_STAGE_TEST_HOOKS
        if (fault_active(LOADER_STAGE_FAULT_RESTORE_CHMOD)) {
            errno = EIO;
            restore_result = -1;
        } else
#endif
        {
            restore_result = chmod(stage->directory, stage->original_mode);
        }
        if (restore_result != 0) {
            int saved = errno;
            char message[LOADER_STAGE_DIAGNOSTIC_MAX - 32];
            snprintf(message, sizeof(message),
                     "staging directory mode restore failed: %s", strerror(saved));
            result = remember(first, sizeof(first), message);
        } else {
            struct stat directory;
            if (lstat(stage->directory, &directory) != 0
                || !S_ISDIR(directory.st_mode)
                || (directory.st_mode & 07777) != stage->original_mode) {
                result = remember(first, sizeof(first),
                                  "staging directory mode was not restored");
            } else {
                stage->sticky_applied = 0;
            }
        }
    }

    if (result != 0) {
        snprintf(stage->error, sizeof(stage->error), "%s", first);
        return -1;
    }
    stage->error[0] = '\0';
    return 0;
}

int loader_stage_install(struct loader_stage *stage, const char *source_path)
{
    if (stage == NULL) {
        return -1;
    }
    stage->error[0] = '\0';
    stage->created = 0;
    stage->sticky_applied = 0;
    if (stage->name[0] == '\0') {
        return stage_error(stage, "staging protocol is not configured");
    }
    if (!valid_absolute_path(source_path, sizeof(stage->destination))) {
        return stage_error(stage, "staging source path is invalid");
    }

    /* Protect the directory before a destination entry can exist. lstat (not
     * stat) proves the staging path itself is a real directory and never a
     * link into somewhere the caller did not choose. */
    struct stat dir_before;
    if (lstat(stage->directory, &dir_before) != 0) {
        return stage_error_errno(stage, "staging directory lstat failed", errno);
    }
    if (!S_ISDIR(dir_before.st_mode)) {
        return stage_error(stage, "staging directory is not a real directory");
    }
    stage->original_mode = dir_before.st_mode & 07777;
    dev_t dir_dev = dir_before.st_dev;
    ino_t dir_ino = dir_before.st_ino;
    mode_t new_mode = (mode_t)((stage->original_mode | S_ISVTX) & 07777);
    if (chmod(stage->directory, (mode_t)new_mode) != 0) {
        return stage_error_errno(stage, "staging directory sticky chmod failed", errno);
    }
    stage->sticky_applied = 1;

    struct stat dir_after;
    if (lstat(stage->directory, &dir_after) != 0) {
        return stage_abort_errno(stage, -1, -1, "staging directory sticky recheck failed");
    }
    if (dir_after.st_dev != dir_dev || dir_after.st_ino != dir_ino
        || !S_ISDIR(dir_after.st_mode)
        || (dir_after.st_mode & 07777) != new_mode
        || (dir_after.st_mode & S_ISVTX) == 0) {
        return stage_abort(stage, -1, -1, "staging directory sticky bit was not applied");
    }

    /* Fail closed for every pre-existing entry type: regular, symlink, FIFO,
     * directory, or anything else. Never follow it and never unlink it. */
    struct stat existing;
    if (lstat(stage->destination, &existing) == 0) {
        return stage_abort(stage, -1, -1, "staging destination already exists");
    }
    if (errno != ENOENT) {
        return stage_abort_errno(stage, -1, -1, "staging destination lstat failed");
    }

    run_hook(LOADER_STAGE_HOOK_AFTER_DEST_LSTAT);

    struct stat dir_recheck;
    if (lstat(stage->directory, &dir_recheck) != 0) {
        return stage_abort_errno(stage, -1, -1, "staging directory recheck failed");
    }
    if (dir_recheck.st_dev != dir_dev || dir_recheck.st_ino != dir_ino
        || !S_ISDIR(dir_recheck.st_mode)
        || (dir_recheck.st_mode & 07777) != new_mode) {
        return stage_abort(stage, -1, -1, "staging directory changed before staging");
    }

    int src_fd = open(source_path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (src_fd < 0) {
        return stage_abort_errno(stage, -1, -1, "staging source open failed");
    }
    struct stat src_before;
    if (fstat(src_fd, &src_before) != 0) {
        int saved = errno;
        close(src_fd);
        return stage_abort_code(stage, -1, -1, "staging source fstat failed", saved);
    }
    if (!S_ISREG(src_before.st_mode)) {
        close(src_fd);
        return stage_abort(stage, -1, -1, "staging source is not a regular file");
    }

    int dst_fd = open(stage->destination,
                      O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
                      LOADER_STAGE_PRIVATE_MODE);
    if (dst_fd < 0) {
        close(src_fd);
        return stage_abort_errno(stage, -1, -1, "staging destination open failed");
    }
    struct stat dst_st;
    if (fstat(dst_fd, &dst_st) != 0) {
        int saved = errno;
        close(dst_fd);
        close(src_fd);
        /* O_EXCL proves this entry is ours; dev/ino are unavailable because
         * fstat failed, so remove it here rather than risk unlinking a swap. */
        (void)unlink(stage->destination);
        return stage_abort_code(stage, -1, -1, "staging destination fstat failed", saved);
    }
    if (!S_ISREG(dst_st.st_mode)) {
        close(dst_fd);
        close(src_fd);
        (void)unlink(stage->destination);
        return stage_abort(stage, -1, -1, "staging destination is not a regular file");
    }
    stage->created = 1;
    stage->created_dev = dst_st.st_dev;
    stage->created_ino = dst_st.st_ino;

    char buffer[16384];
    for (;;) {
        ssize_t got = read(src_fd, buffer, sizeof(buffer));
        if (got < 0) {
            int saved = errno;
            if (saved == EINTR) {
                continue;
            }
            close(dst_fd);
            close(src_fd);
            return stage_abort_code(stage, -1, -1, "staging source read failed", saved);
        }
        if (got == 0) {
            break;
        }
        size_t length = (size_t)got;
        size_t offset = 0;
        while (offset < length) {
            size_t request = length - offset;
            ssize_t written;
#ifdef LOADER_STAGE_TEST_HOOKS
            /* Host-only short writes exercise the full-write loop itself; a
             * single write() would copy only one byte and fail the test. */
            if (fault_active(LOADER_STAGE_FAULT_SHORT_WRITE) && request > 1) {
                request = 1;
            }
            if (fault_active(LOADER_STAGE_FAULT_WRITE)) {
                errno = EIO;
                written = -1;
            } else
#endif
            {
                written = write(dst_fd, buffer + offset, request);
            }
            if (written < 0) {
                int saved = errno;
                if (saved == EINTR) {
                    continue;
                }
                close(dst_fd);
                close(src_fd);
                return stage_abort_code(stage, -1, -1, "staged copy write failed", saved);
            }
            if (written == 0) {
                close(dst_fd);
                close(src_fd);
                return stage_abort(stage, -1, -1, "staged copy write made no progress");
            }
            offset += (size_t)written;
        }
    }

    struct stat src_after;
    if (fstat(src_fd, &src_after) != 0) {
        int saved = errno;
        close(dst_fd);
        close(src_fd);
        return stage_abort_code(stage, -1, -1, "staging source final fstat failed", saved);
    }
    if (src_after.st_dev != src_before.st_dev || src_after.st_ino != src_before.st_ino
        || src_after.st_size != src_before.st_size) {
        close(dst_fd);
        close(src_fd);
        return stage_abort(stage, -1, -1, "staging source changed during copy");
    }
    if (close(src_fd) != 0) {
        int saved = errno;
        close(dst_fd);
        return stage_abort_code(stage, -1, -1, "staging source close failed", saved);
    }

    int fsync_result;
#ifdef LOADER_STAGE_TEST_HOOKS
    if (fault_active(LOADER_STAGE_FAULT_FSYNC)) {
        errno = EIO;
        fsync_result = -1;
    } else
#endif
    {
        fsync_result = fsync(dst_fd);
    }
    if (fsync_result != 0) {
        int saved = errno;
        close(dst_fd);
        return stage_abort_code(stage, -1, -1, "staged copy fsync failed", saved);
    }

    run_hook(LOADER_STAGE_HOOK_BEFORE_FCHMOD);
    if (fchmod(dst_fd, LOADER_STAGE_EXEC_MODE) != 0) {
        int saved = errno;
        close(dst_fd);
        return stage_abort_code(stage, -1, -1, "staged copy fchmod failed", saved);
    }

    int close_result = close(dst_fd);
#ifdef LOADER_STAGE_TEST_HOOKS
    if (fault_active(LOADER_STAGE_FAULT_CLOSE) && close_result == 0) {
        close_result = -1;
        errno = EIO;
    }
#endif
    if (close_result != 0) {
        int saved = errno;
        return stage_abort_code(stage, -1, -1, "staged copy close failed", saved);
    }

    struct stat final;
    if (lstat(stage->destination, &final) != 0) {
        int saved = errno;
        return stage_abort_code(stage, -1, -1, "staged copy final lstat failed", saved);
    }
    if (!S_ISREG(final.st_mode)
        || (final.st_mode & 07777) != LOADER_STAGE_EXEC_MODE
        || final.st_size != src_before.st_size
        || final.st_dev != stage->created_dev
        || final.st_ino != stage->created_ino) {
        return stage_abort(stage, -1, -1, "staged copy verification failed");
    }
    stage->error[0] = '\0';
    return 0;
}
