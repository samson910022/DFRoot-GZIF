/* Strict, host-testable device version parsing for the GZIF exploit.
 *
 * Only decimal digits without signs, leading zeros or overflow are accepted,
 * and every numeric field must end at a token boundary. A release read from a
 * getprop property is marked separately: exp.c preflight rejects it because
 * the kernel tag in uname(2) is the authoritative KMI selector. */
#ifndef DFROOT_DEVICE_VERSION_H
#define DFROOT_DEVICE_VERSION_H

#ifdef __cplusplus
extern "C" {
#endif

enum version_provenance {
    VERSION_PROV_UNSET = 0,
    VERSION_PROV_UNAME = 1,             /* "androidN" token inside uname -r */
    VERSION_PROV_GETPROP_FALLBACK = 2,  /* getprop only: rejected by preflight */
};

/* Parse "<major>.<minor>" at the very start of a kernel release string. The
 * character after <minor> must be a token boundary (NUL, '.', '-', '_', '+'). */
int parse_kernel_release(const char *release, int *major, int *minor);

/* Parse a bare Android release token (e.g. "13" or "13-8-..."). */
int parse_android_release_token(const char *token, int *out);

/* Find and parse an "androidN" token inside a full uname release string.
 * Multiple occurrences with different values are rejected as ambiguous. */
int parse_android_release(const char *release, int *out);

/* Wrapper used by exp.c. Provenance is always set on success. */
int read_device_versions(int *andr, int *major, int *minor,
                         enum version_provenance *prov);

#ifdef __cplusplus
}
#endif

#endif /* DFROOT_DEVICE_VERSION_H */
