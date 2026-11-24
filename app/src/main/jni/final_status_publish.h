#ifndef DFROOT_FINAL_STATUS_PUBLISH_H
#define DFROOT_FINAL_STATUS_PUBLISH_H

/* Atomic publisher for the single final-status token.
 *
 * The token carries no secret. It MUST be world-readable: bootstrap runs as
 * uid 0 (kernel UMH) while the consumer (exp.c) runs as the app uid. A 0600
 * temp file whose mode survives rename(2) would make every consumer read fail
 * (rc=2). DF_FINAL_MODE is therefore forced with fchmod() as well as passed to
 * open(), so a restrictive umask cannot strip the read bits. */
#define DF_FINAL_MODE 0644

/* Write `token` to tmp_path, fsync, then atomically rename over final_path.
 * Returns 0 on success, -1 on any failure (previous final_path is untouched;
 * tmp_path is removed). Both paths must be distinct and non-NULL. */
int df_publish_final(const char *tmp_path, const char *final_path,
                     const char *token);

#endif /* DFROOT_FINAL_STATUS_PUBLISH_H */
