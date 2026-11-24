/* Modified 2026-11-25 (PR #3 review): world-readable final token, extracted
 * from bootstrap.c so the real file permissions can be exercised by a host
 * test. See NOTICE. */
#include "final_status_publish.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int df_publish_final(const char *tmp_path, const char *final_path,
                     const char *token)
{
    size_t len, off;
    int fd;

    if (!tmp_path || !final_path || !token)
        return -1;
    len = strlen(token);
    if (len == 0 || len >= 4096)
        return -1;

    /* Unlink first, then O_EXCL|O_NOFOLLOW: no symlink/TOCTOU race at creation.
     * A leftover temp from an earlier crash never becomes the published file. */
    unlink(tmp_path);
    fd = open(tmp_path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
              DF_FINAL_MODE);
    if (fd < 0) {
        printf("bootstrap: create %s failed: %s\n", tmp_path, strerror(errno));
        return -1;
    }

    /* open() applies the umask (UMH/app environments may use 077); fchmod()
     * pins the exact mode on the inode that rename(2) will publish. */
    if (fchmod(fd, DF_FINAL_MODE) < 0) {
        printf("bootstrap: chmod %s failed: %s\n", tmp_path, strerror(errno));
        close(fd);
        unlink(tmp_path);
        return -1;
    }

    for (off = 0; off < len;) {
        ssize_t w = write(fd, token + off, len - off);
        if (w < 0 && errno == EINTR)
            continue;
        if (w <= 0) {
            printf("bootstrap: write %s failed: %s\n", tmp_path, strerror(errno));
            close(fd);
            unlink(tmp_path);
            return -1;
        }
        off += (size_t)w;
    }
    if (fsync(fd) < 0) {
        printf("bootstrap: fsync %s failed: %s\n", tmp_path, strerror(errno));
        close(fd);
        unlink(tmp_path);
        return -1;
    }
    if (close(fd) < 0) {
        unlink(tmp_path);
        return -1;
    }
    if (rename(tmp_path, final_path) < 0) {
        printf("bootstrap: rename %s -> %s failed: %s\n",
               tmp_path, final_path, strerror(errno));
        unlink(tmp_path);
        return -1;
    }
    return 0;
}
