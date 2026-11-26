/* Host-only tests for strict, provenance-aware version parsing. */
#include <stdio.h>
#include <string.h>

#include "device_version.h"

static int failures;

static void check(int cond, const char *what, const char *input) {
    if (!cond) {
        printf("FAIL %s: %s\n", input, what);
        failures++;
    }
}

struct kcase { const char *in; int major, minor; int ok; };
struct acase { const char *in; int value; int ok; };

static const struct kcase kernel_cases[] = {
    { "5.15.197-android13-8-34343818-abS9180ZHU8GZIF", 5, 15, 1 },
    { "5.15", 5, 15, 1 },
    { "5.15-android13", 5, 15, 1 },
    { "6.1.0", 6, 1, 1 },
    { "4.19-rc1+", 4, 19, 1 },
    { "", 0, 0, 0 },
    { "5", 0, 0, 0 },
    { "5.", 0, 0, 0 },
    { " 5.15", 0, 0, 0 },
    { "+5.15", 0, 0, 0 },
    { "-5.15", 0, 0, 0 },
    { "05.15", 0, 0, 0 },
    { "5.015", 0, 0, 0 },
    { "5.15x", 0, 0, 0 },
    { "5.1000", 0, 0, 0 },
    { "9999.1", 0, 0, 0 },
};

static const struct acase token_cases[] = {
    { "13", 13, 1 },
    { "13-8-34343818", 13, 1 },
    { "14.0", 14, 1 },
    { "10_foo", 10, 1 },
    { "13+bar", 13, 1 },
    { "", 0, 0 },
    { "0", 0, 0 },
    { "013", 0, 0 },
    { "+13", 0, 0 },
    { "-13", 0, 0 },
    { "13x", 0, 0 },
    { "13 14", 0, 0 },
    { "101", 0, 0 },
    { "1a", 0, 0 },
};

static const struct acase release_cases[] = {
    { "5.15.197-android13-8-34343818-abS9180ZHU8GZIF", 13, 1 },
    { "5.15.197-android14", 14, 1 },
    { "5.15-android13-android13-8", 13, 1 },
    { "android10-xyz", 10, 1 },
    { "5.15.197", 0, 0 },
    { "5.15.197-myandroid13", 0, 0 },
    { "5.15.197-android13-android14", 0, 0 },
    { "5.15.197-android013", 0, 0 },
    { "5.15.197-android13x", 0, 0 },
    { "5.15.197-android0", 0, 0 },
};

int main(void) {
    size_t i;

    for (i = 0; i < sizeof(kernel_cases) / sizeof(kernel_cases[0]); i++) {
        const struct kcase *t = &kernel_cases[i];
        int major = -1, minor = -1;
        int rc = parse_kernel_release(t->in, &major, &minor);
        if (t->ok) {
            check(rc == 0 && major == t->major && minor == t->minor,
                  "valid kernel release rejected or misparsed", t->in);
        } else {
            check(rc != 0, "invalid kernel release accepted", t->in);
        }
    }

    for (i = 0; i < sizeof(token_cases) / sizeof(token_cases[0]); i++) {
        const struct acase *t = &token_cases[i];
        int value = -1;
        int rc = parse_android_release_token(t->in, &value);
        if (t->ok) {
            check(rc == 0 && value == t->value,
                  "valid Android token rejected or misparsed", t->in);
        } else {
            check(rc != 0, "invalid Android token accepted", t->in);
        }
    }

    for (i = 0; i < sizeof(release_cases) / sizeof(release_cases[0]); i++) {
        const struct acase *t = &release_cases[i];
        int value = -1;
        int rc = parse_android_release(t->in, &value);
        if (t->ok) {
            check(rc == 0 && value == t->value,
                  "valid uname release rejected or misparsed", t->in);
        } else {
            check(rc != 0, "invalid/ambiguous uname release accepted", t->in);
        }
    }

    /* Provenance is a separate contract: getprop-only must be distinguishable
     * from a uname token so exp.c preflight can reject it. */
    check(VERSION_PROV_UNAME != VERSION_PROV_GETPROP_FALLBACK,
          "provenance values collide", "provenance");
    check(parse_kernel_release(NULL, NULL, NULL) != 0, "NULL accepted", "NULL");
    check(parse_android_release_token("13", NULL) != 0, "NULL out accepted", "NULL");

    if (failures == 0) {
        printf("device_version_test: %zu kernel + %zu token + %zu release cases OK\n",
               sizeof(kernel_cases) / sizeof(kernel_cases[0]),
               sizeof(token_cases) / sizeof(token_cases[0]),
               sizeof(release_cases) / sizeof(release_cases[0]));
        return 0;
    }
    printf("device_version_test: %d failure(s)\n", failures);
    return 1;
}
