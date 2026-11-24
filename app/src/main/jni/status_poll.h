#ifndef DFROOT_STATUS_POLL_H
#define DFROOT_STATUS_POLL_H

#include <stddef.h>

/* A /dev marker published by bootstrap. `outcome` marks the reboot-outcome
 * warnings (dfmw2/dfmw3); terminal rc wins over them for the App's result. */
struct status_marker {
    const char *path;
    const char *msg;
    int         rc;       /* >=0 terminal result, -1 informational */
    int         outcome;  /* 1 = reboot-outcome warning */
};

typedef int  (*status_exists_fn)(const char *path, void *ctx);
typedef void (*status_report_fn)(const char *msg, void *ctx);

/* Poll markers until a terminal result is published (and, for rc=4, until the
 * reboot outcome is also known) or max_wait_ms elapses. Returns the App rc:
 * 0 success, 1 error, 2 timeout/unknown, 4 root-active/soft-reboot pending. */
int poll_status_markers(const struct status_marker *markers, size_t count,
                        status_exists_fn exists, status_report_fn report,
                        void *ctx, int max_wait_ms);

#endif
