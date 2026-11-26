#include "device_version.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/utsname.h>

#define MAX_VERSION_NUMBER 999
#define MAX_ANDROID_RELEASE 100

/* Bounded unsigned decimal: no sign, no leading zeros, no overflow. */
static int parse_bounded_uint(const char **pp, int max, int *out) {
    const char *p = *pp;
    unsigned long v = 0;
    if (p == NULL || !isdigit((unsigned char)*p)) return -1;
    if (*p == '0' && isdigit((unsigned char)p[1])) return -1; /* leading zeros */
    while (isdigit((unsigned char)*p)) {
        unsigned digit = (unsigned)(*p - '0');
        if (v > ((unsigned long)max - digit) / 10UL) return -1;
        v = v * 10UL + digit;
        p++;
    }
    if (v > (unsigned long)max) return -1;
    *out = (int)v;
    *pp = p;
    return 0;
}

static int token_boundary(char c) {
    return c == '\0' || c == '.' || c == '-' || c == '_' || c == '+';
}

int parse_kernel_release(const char *release, int *major, int *minor) {
    const char *p;
    int ma, mi;
    if (release == NULL || major == NULL || minor == NULL) return -1;
    p = release;
    if (parse_bounded_uint(&p, MAX_VERSION_NUMBER, &ma) != 0 || *p != '.') return -1;
    p++;
    if (parse_bounded_uint(&p, MAX_VERSION_NUMBER, &mi) != 0) return -1;
    if (!token_boundary(*p)) return -1; /* reject "5.15x" */
    *major = ma;
    *minor = mi;
    return 0;
}

int parse_android_release_token(const char *token, int *out) {
    const char *p;
    int v;
    if (token == NULL || out == NULL) return -1;
    p = token;
    if (parse_bounded_uint(&p, MAX_ANDROID_RELEASE, &v) != 0) return -1;
    if (v <= 0 || !token_boundary(*p)) return -1; /* reject "13x", "013", "0" */
    *out = v;
    return 0;
}

int parse_android_release(const char *release, int *out) {
    const char *p;
    int found = 0, value = 0;
    if (release == NULL || out == NULL) return -1;
    for (p = release; *p != '\0'; p++) {
        const char *digits;
        int v;
        if (strncmp(p, "android", 7) != 0) continue;
        /* Token boundary before "android": no embedded words like "myandroid13". */
        if (p != release && !token_boundary(p[-1])) continue;
        digits = p + 7;
        if (!isdigit((unsigned char)*digits)) continue;
        if (parse_android_release_token(digits, &v) != 0) return -1;
        if (found && v != value) {
            printf("device_version: ambiguous Android release tokens (%d vs %d)\n",
                   value, v);
            return -1;
        }
        value = v;
        found = 1;
        p += 6; /* loop increment moves past the final 'd' */
    }
    if (!found) return -1;
    *out = value;
    return 0;
}

/* Populate Android release from getprop. Disagreement between properties is
 * treated as ambiguous and rejected rather than silently choosing one. */
static int android_release_from_getprop(int *out) {
    static const char *const props[] = {
        "ro.vendor.build.version.release",
        "ro.build.version.release",
    };
    int found = 0, value = 0;
    size_t i;
    for (i = 0; i < sizeof(props) / sizeof(props[0]); i++) {
        char cmd[96];
        char line[32];
        FILE *f;
        int v = 0, parsed = 0;
        snprintf(cmd, sizeof(cmd), "getprop %s", props[i]);
        f = popen(cmd, "r");
        if (f == NULL) continue;
        if (fgets(line, sizeof(line), f) != NULL) {
            line[strcspn(line, "\r\n")] = '\0';
            parsed = (parse_android_release_token(line, &v) == 0);
        }
        if (pclose(f) != 0 || !parsed) continue;
        if (found && v != value) {
            printf("device_version: ambiguous getprop Android release (%d vs %d)\n",
                   value, v);
            return -1;
        }
        value = v;
        found = 1;
    }
    if (!found) return -1;
    *out = value;
    return 0;
}

int read_device_versions(int *andr, int *major, int *minor,
                         enum version_provenance *prov) {
    struct utsname u;
    int andr_value;
    if (andr == NULL || major == NULL || minor == NULL || prov == NULL) return -1;
    *prov = VERSION_PROV_UNSET;
    if (uname(&u) != 0) return -1;
    if (parse_kernel_release(u.release, major, minor) != 0) {
        printf("device_version: cannot parse kernel release '%s'\n", u.release);
        return -1;
    }
    if (parse_android_release(u.release, &andr_value) == 0) {
        *andr = andr_value;
        *prov = VERSION_PROV_UNAME;
        return 0;
    }
    printf("device_version: uname release has no Android token; trying getprop\n");
    if (android_release_from_getprop(&andr_value) != 0) {
        printf("device_version: getprop Android release unavailable/ambiguous\n");
        return -1;
    }
    *andr = andr_value;
    *prov = VERSION_PROV_GETPROP_FALLBACK;
    return 0;
}
