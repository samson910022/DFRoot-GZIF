#ifndef DFROOT_FINAL_STATUS_PUBLISH_H
#define DFROOT_FINAL_STATUS_PUBLISH_H

#include <stddef.h>

/* Atomic publisher + strict reader for the single final-status token.
 *
 * The token carries no secret. It MUST be world-readable: bootstrap runs as
 * uid 0 (kernel UMH) while the consumer (exp.c) runs as the app uid. A 0600
 * temp file whose mode survives rename(2) would make every consumer read fail
 * (rc=2). DF_FINAL_MODE is therefore forced with fchmod() as well as passed to
 * open(), so a restrictive umask cannot strip the read bits. */
#define DF_FINAL_MODE 0644
#define DF_FINAL_READ_CAP 96 /* >= DF_FINAL_TOKEN_CAP, bounded for real reads */

/* True only for a complete canonical v2 token: exact prefix, 32 lowercase hex
 * nonce chars, ':', a publishable outcome name and a single trailing '\n'. */
int df_token_is_canonical(const char *token);

/* Write `token` to tmp_path, fsync, then atomically rename over final_path.
 * Returns 0 on success, -1 on any failure (previous final_path is untouched;
 * tmp_path is removed). Both paths must be distinct and non-NULL. Non-canonical
 * tokens are rejected before any file is created. */
int df_publish_final(const char *tmp_path, const char *final_path,
                     const char *token);

/* Strict real-file read adapter for both producer and consumer. Opens with
 * O_NOFOLLOW, requires a regular file, bounds the size and reads EXACTLY the
 * stat'ed byte count. Returns 0 with *out_len set (may be 0), -1 on any
 * failure; no C-string prefix matching is performed. */
int df_read_final_file(const char *path, char *buf, size_t cap, size_t *out_len);

#endif /* DFROOT_FINAL_STATUS_PUBLISH_H */
