/* Modified 2026-11-25 (PR #3 review): world-readable final token, extracted
 * from bootstrap.c so the real file permissions can be exercised by a host
 * test. See NOTICE. */
#include "final_status_publish.h"

#include "status_protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int df_token_is_canonical(const char *token)
{
    char nonce[DF_NONCE_CHARS + 1];
    enum df_outcome outcome;
    if (!token) return 0;
    return df_token_split(token, strlen(token), nonce, sizeof(nonce),
                          &outcome) == 0;
}

int df_publish_final(const char *tmp_path, const char *final_path,
                     const char *token)
{
    size_t len, off;
    int fd;

    if (!tmp_path || !final_path || !token)
        return -1;
    /* Validate the full canonical token (prefix, bound nonce, outcome, single
     * trailing newline) before creating anything on disk. */
    if (!df_token_is_canonical(token))
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

int df_read_final_file(const char *path, char *buf, size_t cap, size_t *out_len)
{
    struct stat st;
    size_t off = 0;
    int fd;

    if (!path || !buf || cap == 0 || !out_len)
        return -1;
    *out_len = 0;
    fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0)
        return -1; /* absent/unreadable/symlink: caller keeps polling */
    /* Reject st_size >= cap: the caller's buffer must never be filled to the
     * brim, even though this reader no longer writes a NUL sentinel. */
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 0 ||
        (uint64_t)st.st_size >= cap ||
        (uint64_t)st.st_size > DF_FINAL_READ_CAP) {
        close(fd);
        return -1;
    }
    while (off < (size_t)st.st_size) {
        ssize_t n = read(fd, buf + off, (size_t)st.st_size - off);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            close(fd);
            return -1; /* short read: never accept a partial token */
        }
        off += (size_t)n;
    }
    /* Reject a file that grew past the stat'ed size (oversize token). */
    {
        char extra;
        ssize_t n = read(fd, &extra, 1);
        if (n != 0) {
            close(fd);
            return -1;
        }
    }
    close(fd);
    /* Do NOT write a NUL sentinel: a file of exactly cap bytes would write at
     * buf[cap], one byte past the caller's buffer. Every consumer must use the
     * exact byte count in *out_len (embedded NULs are rejected by the strict
     * parser, not by C-string truncation). */
    *out_len = off;
    return 0;
}
