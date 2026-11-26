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

/* Single authoritative outcome channel on Android: a system property named
 * dfroot.final. Bootstrap runs as uid 0 in u:r:ksu:s0 and can set a property
 * with context u:object_r:default_prop:s0; the app (untrusted_app) can read it
 * but AOSP neverallow forbids it from setting properties. The adapters below
 * are therefore one-way (root -> app) and need no file in /dev or /data.
 * Bionic's PROP_VALUE_MAX (including its NUL) is 92; duplicated here so the
 * pure host validator can enforce the same bound without bionic headers. */
#define DF_FINAL_PROPERTY "dfroot.final"
#define DF_PROP_VALUE_MAX 92

/* True only for a complete canonical v2 token: exact prefix, 32 lowercase hex
 * nonce chars, ':', a publishable outcome name and a single trailing '\n'. */
int df_token_is_canonical(const char *token);

/* Pure property-value truth-table predicate (no bionic, no nonce binding).
 * Accepts only a canonical v2 token of a bounded length that fits strictly
 * inside the caller's cap. It deliberately does NOT check any nonce: binding
 * is the strict consumer parser's job (df_parse_final_token()). */
int df_property_value_ok(const char *buf, size_t len, size_t cap);

#ifdef __ANDROID__
/* Publish a canonical token to DF_FINAL_PROPERTY with __system_property_set().
 * Returns 0 on success, -1 on a non-canonical/oversize token or setter error.
 * The length bound guarantees no truncation to PROP_VALUE_MAX bytes. */
int df_publish_property(const char *token);

/* Read DF_FINAL_PROPERTY with __system_property_get(). Returns 0 with the
 * exact byte count in *out_len; -1 when absent, empty or >= cap. No C-string
 * prefix matching and no nonce interpretation is done here. */
int df_read_property(char *buf, size_t cap, size_t *out_len);
#endif /* __ANDROID__ */

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
