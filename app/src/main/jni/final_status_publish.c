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

#ifdef __ANDROID__
#include <sys/system_properties.h>
/* The header's duplicated bound must stay equal to bionic's: a mismatch
 * would silently permit a truncating __system_property_set() call. */
_Static_assert(PROP_VALUE_MAX == DF_PROP_VALUE_MAX,
               "DF_PROP_VALUE_MAX must match bionic PROP_VALUE_MAX");
#endif

int df_token_is_canonical(const char *token)
{
    char nonce[DF_NONCE_CHARS + 1];
    enum df_outcome outcome;
    if (!token) return 0;
    return df_token_split(token, strlen(token), nonce, sizeof(nonce),
                          &outcome) == 0;
}

int df_property_value_ok(const char *buf, size_t len, size_t cap)
{
    char nonce[DF_NONCE_CHARS + 1];
    enum df_outcome outcome;

    if (!buf || cap == 0 || len == 0 || len >= cap ||
        len >= DF_FINAL_TOKEN_CAP || len >= DF_PROP_VALUE_MAX)
        return 0;
    /* df_token_split() rejects embedded NULs, a wrong prefix, malformed hex,
     * an unknown/none outcome and a missing/duplicated trailing newline. It
     * extracts but does not compare the nonce, which is intentional here. */
    return df_token_split(buf, len, nonce, sizeof(nonce), &outcome) == 0;
}

#ifdef __ANDROID__
int df_publish_property(const char *token)
{
    size_t len;

    if (!token || !df_token_is_canonical(token))
        return -1;
    len = strlen(token);
    /* __system_property_set() silently truncates at PROP_VALUE_MAX-1 bytes.
     * Never hand it a value that could be truncated. */
    if (len == 0 || len >= DF_FINAL_TOKEN_CAP || len >= DF_PROP_VALUE_MAX)
        return -1;
    return __system_property_set(DF_FINAL_PROPERTY, token) < 0 ? -1 : 0;
}

int df_read_property(char *buf, size_t cap, size_t *out_len)
{
    int n;

    if (!buf || !out_len || cap < DF_PROP_VALUE_MAX)
        return -1; /* bionic may write PROP_VALUE_MAX bytes before length check */
    *out_len = 0;
    n = __system_property_get(DF_FINAL_PROPERTY, buf);
    /* 0 means absent (or an empty value); bionic writes a NUL at buf[n], so
     * the caller's buffer must be strictly larger than the value length. */
    if (n <= 0 || (size_t)n >= cap)
        return -1;
    *out_len = (size_t)n;
    return 0;
}
#endif /* __ANDROID__ */

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
