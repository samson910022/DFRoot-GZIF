#define _DEFAULT_SOURCE 1  /* usleep() under strict -std=c11 host builds */
#include "status_poll.h"

#include <unistd.h>

#define POLL_STEP_MS 10

int poll_status_markers(const struct status_marker *markers, size_t count,
                        status_exists_fn exists, status_report_fn report,
                        void *ctx, int max_wait_ms) {
    if (!markers || !exists || !report || count == 0) return 2;

    int seen[count];
    for (size_t i = 0; i < count; i++) seen[i] = 0;

    int rc = 2;
    int terminal_seen = 0;
    int root_active = 0;
    int reboot_outcome_seen = 0;

    for (int elapsed = 0; elapsed < max_wait_ms; elapsed += POLL_STEP_MS) {
        usleep(POLL_STEP_MS * 1000);

        /* Scan all markers each iteration: warnings can be published in the
         * same iteration as a terminal marker, or later while a soft reboot is
         * being prepared. Explicit precedence keeps root-active (rc=4) from
         * being downgraded by a later/stale error marker. */
        for (size_t j = 0; j < count; j++) {
            if (seen[j] || !exists(markers[j].path, ctx)) continue;
            seen[j] = 1;
            report(markers[j].msg, ctx);
            if (markers[j].outcome)
                reboot_outcome_seen = 1;
            if (markers[j].rc < 0) continue;
            if (root_active) continue; /* do not overwrite rc=4 */
            terminal_seen = 1;
            rc = markers[j].rc;
            if (rc == 4) root_active = 1;
        }

        if (terminal_seen && !root_active)
            break; /* success or hard failure: no late reboot outcome expected */
        if (terminal_seen && root_active && reboot_outcome_seen)
            break; /* root + reboot outcome both reported */
    }

    if (!terminal_seen)
        report("timeout: ERROR - check logcat & dmesg", ctx);
    else if (root_active && !reboot_outcome_seen)
        report("warning: soft-reboot outcome not reported within bound (root still active)", ctx);
    return rc;
}
