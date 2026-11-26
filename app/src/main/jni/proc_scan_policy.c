#include "proc_scan_policy.h"

#include <errno.h>

static int benign_errno(int e)
{
    return e == 0 || e == ENOENT || e == ESRCH;
}

enum df_proc_verdict df_proc_entry_verdict(int open_errno, int read_errno,
                                            int bytes_read, int buf_full,
                                            int matched)
{
    /* An observed match is authoritative even for a per-PID race: the process
     * existed at the moment we read it, so a reboot must not be authorized. */
    if (matched)
        return DF_PROC_MATCH;

    /* Any unexpected failure makes the scan incomplete: unknown must never be
     * reported as "late-load absent" (which would authorize a reboot). */
    if (!benign_errno(open_errno) || !benign_errno(read_errno))
        return DF_PROC_INCOMPLETE;

    /* ENOENT/ESRCH with no match and a zero-byte cmdline are benign per-PID
     * races: the entry was scanned as far as it existed. */
    if (bytes_read == 0 || open_errno != 0 || read_errno != 0)
        return DF_PROC_BENIGN;

    /* A full buffer with no match may have hidden the marker in the tail;
     * a negative read without an errno is untrustworthy too. */
    if (bytes_read < 0 || buf_full)
        return DF_PROC_INCOMPLETE;

    return DF_PROC_BENIGN;
}
