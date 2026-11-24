/* Host-side test for the marker status protocol. No privileged files are
 * touched: existence and reporting are injected callbacks. */
#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "../../app/src/main/jni/status_poll.h"

static const struct status_marker kMarkers[] = {
    { "/dev/df",    "df",        -1, 0 },
    { "/dev/dfm0",  "dfm0",      -1, 0 },
    { "/dev/dfm1",  "dfm1",      -1, 0 },
    { "/dev/dfme0", "dfme0",      1, 0 },
    { "/dev/dfm2",  "dfm2",      -1, 0 },
    { "/dev/dfmw0", "dfmw0",     -1, 0 },
    { "/dev/dfm3",  "dfm3",      -1, 0 },
    { "/dev/dfmw1", "dfmw1",     -1, 0 },
    { "/dev/dfm4",  "dfm4",      -1, 0 },
    { "/dev/dfme1", "dfme1",      1, 0 },
    { "/dev/dfm5",  "dfm5",      -1, 0 },
    { "/dev/dfmsr", "dfmsr",      4, 0 },
    { "/dev/dfm6",  "dfm6",       0, 0 },
    { "/dev/dfmw2", "dfmw2",     -1, 1 },
    { "/dev/dfmw3", "dfmw3",     -1, 1 },
    { "/dev/dfme2", "dfme2",      1, 0 },
};
#define NMARKERS (sizeof(kMarkers) / sizeof(kMarkers[0]))

struct script {
    int calls;
    const char *present[8];
    int present_n;
    const char *late;   /* appears after 80 exists() calls */
    int late_at;
};

static int script_exists(const char *path, void *ctx) {
    struct script *s = ctx;
    s->calls++;
    for (int i = 0; i < s->present_n; i++)
        if (!strcmp(path, s->present[i])) return 1;
    if (s->late && s->calls >= s->late_at && !strcmp(path, s->late)) return 1;
    return 0;
}

static char g_report[2048];

static void collect_report(const char *msg, void *ctx) {
    (void)ctx;
    strncat(g_report, msg, sizeof(g_report) - strlen(g_report) - 1);
    strncat(g_report, "\n", sizeof(g_report) - strlen(g_report) - 1);
}

static int run(struct script *s) {
    g_report[0] = '\0';
    return poll_status_markers(kMarkers, NMARKERS, script_exists,
                               collect_report, s, 200);
}

int main(void) {
    char *report = g_report;

    { /* success */
        struct script s = {.present = {"/dev/dfm6"}, .present_n = 1};
        assert(run(&s) == 0);
        assert(strstr(report, "dfm6") != NULL);
    }
    { /* headline invariant: dfmsr + dfm6 both present must stay rc=4 */
        struct script s = {.present = {"/dev/dfmsr", "/dev/dfm6"}, .present_n = 2};
        assert(run(&s) == 4);
        assert(strstr(report, "dfmsr") != NULL);
        assert(strstr(report, "outcome not reported") != NULL);
    }
    { /* root active, no outcome marker -> generic warning, rc stays 4 */
        struct script s = {.present = {"/dev/dfmsr"}, .present_n = 1};
        assert(run(&s) == 4);
        assert(strstr(report, "outcome not reported") != NULL);
    }
    { /* root active + outcome delivered in same iteration */
        struct script s = {.present = {"/dev/dfmsr", "/dev/dfmw2"}, .present_n = 2};
        assert(run(&s) == 4);
        assert(strstr(report, "dfmw2") != NULL);
        assert(strstr(report, "outcome not reported") == NULL);
    }
    { /* late warning arrives after several polls: must still be delivered */
        struct script s = {
            .present = {"/dev/dfmsr"}, .present_n = 1,
            .late = "/dev/dfmw3", .late_at = 80,
        };
        assert(run(&s) == 4);
        assert(strstr(report, "dfmw3") != NULL);
        assert(strstr(report, "outcome not reported") == NULL);
    }
    { /* stale error marker must not downgrade rc=4 */
        struct script s = {
            .present = {"/dev/dfmsr", "/dev/dfme2"}, .present_n = 2,
        };
        assert(run(&s) == 4);
        assert(strstr(report, "dfmsr") != NULL);
    }
    { /* hard failure */
        struct script s = {.present = {"/dev/dfme0"}, .present_n = 1};
        assert(run(&s) == 1);
    }
    { /* no markers -> timeout */
        struct script s = {0};
        assert(run(&s) == 2);
        assert(strstr(report, "timeout") != NULL);
    }

    { /* invalid input fails closed */
        assert(poll_status_markers(NULL, 0, NULL, NULL, NULL, 200) == 2);
    }

    puts("status_poll_test: all cases passed");
    return 0;
}
