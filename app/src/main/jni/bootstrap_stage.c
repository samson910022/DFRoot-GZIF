/* Modified 2026-11-26 (PR #3, Phase C): fd-anchored producer-side staging for
 * the rebuilt Samsung loaders. See NOTICE. */
#include "bootstrap_stage.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int df_stage_dir_ok(uid_t uid, mode_t mode)
{
    /* /data/adb must be root-owned, a real directory (O_NOFOLLOW is already
     * required at open time), and not writable by group or other. */
    return uid == 0 && S_ISDIR(mode) && (mode & (mode_t)0022) == 0;
}

int df_stage_file_ok(uid_t uid, mode_t mode, off_t size)
{
    /* The fd we wrote must still name a root-owned, non-empty regular file
     * with exactly the mode the loader requires before it promotes it. */
    return uid == 0 && S_ISREG(mode) &&
           (mode & (mode_t)07777) == (mode_t)0700 && size > 0;
}

static ssize_t real_read(void *ctx, int fd, void *buf, size_t n)
{
    (void)ctx;
    return read(fd, buf, n);
}

static ssize_t real_write(void *ctx, int fd, const void *buf, size_t n)
{
    (void)ctx;
    return write(fd, buf, n);
}

static int real_set_mode(void *ctx, int fd, mode_t mode)
{
    (void)ctx;
    return fchmod(fd, mode);
}

static int real_sync(void *ctx, int fd)
{
    (void)ctx;
    return fsync(fd);
}

static const struct df_stage_copy_io real_copy_io = {
    NULL, real_read, real_write, real_set_mode, real_sync
};

int df_stage_copy_fd(int src_fd, int dst_fd, const struct df_stage_copy_io *io)
{
    char buf[65536];
    size_t total = 0;

    if (!io || !io->read || !io->write || !io->set_mode || !io->sync)
        return -1;

    for (;;) {
        ssize_t r = io->read(io->ctx, src_fd, buf, sizeof(buf));
        if (r == 0)
            break;
        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        /* Never trust an I/O adapter that claims more bytes than the buffer. */
        if ((size_t)r > sizeof(buf))
            return -1;
        size_t off = 0;
        while (off < (size_t)r) {
            ssize_t w = io->write(io->ctx, dst_fd, buf + off, (size_t)r - off);
            if (w < 0) {
                if (errno == EINTR)
                    continue;
                return -1;
            }
            /* A zero-byte write is not progress; never spin on a broken sink. */
            if (w == 0) {
                errno = EIO;
                return -1;
            }
            off += (size_t)w;
        }
        total += (size_t)r;
    }
    /* An empty stage is never a valid hand-off to the loader. */
    if (total == 0)
        return -1;
    if (io->set_mode(io->ctx, dst_fd, (mode_t)0700) != 0)
        return -1;
    if (io->sync(io->ctx, dst_fd) != 0)
        return -1;
    return 0;
}

int df_stage_file(const char *src, const char *dir, const char *name,
                  const struct df_stage_policy *policy,
                  const struct df_stage_copy_io *io)
{
    const struct df_stage_copy_io *copy_io = io ? io : &real_copy_io;
    int (*dir_ok)(uid_t, mode_t) =
        (policy && policy->dir_ok) ? policy->dir_ok : df_stage_dir_ok;
    int (*file_ok)(uid_t, mode_t, off_t) =
        (policy && policy->file_ok) ? policy->file_ok : df_stage_file_ok;
    struct stat st;
    int dfd = -1, sfd = -1, nfd = -1;
    int created = 0, rc = -1;

    if (!copy_io->read || !copy_io->write || !copy_io->set_mode || !copy_io->sync)
        return -1;

    /* Anchor every following lookup to this fd. O_NOFOLLOW rejects a
     * directory replaced by a symlink; O_DIRECTORY rejects non-directories. */
    dfd = open(dir, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dfd < 0) {
        printf("bootstrap: open stage dir %s failed: %s\n", dir, strerror(errno));
        goto done;
    }
    if (fstat(dfd, &st) != 0 || !dir_ok(st.st_uid, st.st_mode)) {
        printf("bootstrap: stage dir %s failed owner/mode policy\n", dir);
        goto done;
    }

    /* The source is app-private; require a readable non-empty regular file but
     * do NOT uid-check it here. */
    sfd = open(src, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (sfd < 0) {
        printf("bootstrap: open %s failed: %s\n", src, strerror(errno));
        goto done;
    }
    if (fstat(sfd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
        printf("bootstrap: source %s is not a non-empty regular file\n", src);
        goto done;
    }

    /* Replace only a regular-file destination. A symlink (or any other entry
     * type) already sitting at the name is hostile: reject it untouched. */
    if (fstatat(dfd, name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
        if (!S_ISREG(st.st_mode)) {
            printf("bootstrap: refusing non-regular stage entry %s/%s\n", dir, name);
            goto done;
        }
        if (unlinkat(dfd, name, 0) != 0) {
            printf("bootstrap: unlink old stage %s/%s failed: %s\n",
                   dir, name, strerror(errno));
            goto done;
        }
    } else if (errno != ENOENT) {
        printf("bootstrap: stat old stage %s/%s failed: %s\n",
               dir, name, strerror(errno));
        goto done;
    }

    /* O_EXCL|O_NOFOLLOW closes the creation-time symlink race; the directory
     * fd pins the parent even if the path is later renamed. */
    nfd = openat(dfd, name,
                 O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
                 (mode_t)0700);
    if (nfd < 0) {
        printf("bootstrap: create %s/%s failed: %s\n", dir, name, strerror(errno));
        goto done;
    }
    created = 1;

    if (df_stage_copy_fd(sfd, nfd, copy_io) != 0) {
        printf("bootstrap: copy to %s/%s failed: %s\n", dir, name, strerror(errno));
        goto done;
    }
    if (fstat(nfd, &st) != 0 || !file_ok(st.st_uid, st.st_mode, st.st_size)) {
        printf("bootstrap: created stage %s/%s failed owner/mode/content policy\n",
               dir, name);
        goto done;
    }
    if (close(nfd) != 0) {
        nfd = -1;
        printf("bootstrap: close %s/%s failed: %s\n", dir, name, strerror(errno));
        goto done;
    }
    nfd = -1;
    if (fsync(dfd) != 0) {
        printf("bootstrap: fsync stage dir %s failed: %s\n", dir, strerror(errno));
        goto done;
    }
    rc = 0;

done:
    if (nfd >= 0)
        close(nfd);
    /* Only ever remove the entry we created, and only on our own failure. */
    if (rc != 0 && created && dfd >= 0)
        (void)unlinkat(dfd, name, 0);
    if (sfd >= 0)
        close(sfd);
    if (dfd >= 0)
        close(dfd);
    return rc;
}
