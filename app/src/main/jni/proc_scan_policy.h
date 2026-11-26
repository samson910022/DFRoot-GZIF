#ifndef DFROOT_PROC_SCAN_POLICY_H
#define DFROOT_PROC_SCAN_POLICY_H

/* Pure /proc scan classification. No syscalls, no globals, no /proc paths:
 * callers pass the observed errno/read result for one directory entry.
 * A single verdict drives the tri-state late_load_running() result. */
enum df_proc_verdict {
    DF_PROC_BENIGN,     /* no match, but the entry was scanned completely */
    DF_PROC_MATCH,      /* late-load cmdline found */
    DF_PROC_INCOMPLETE  /* scan could not be trusted: unknown, never "absent" */
};

/* open_errno/read_errno: 0 on success, otherwise the captured errno.
 * bytes_read: 0 for a benign empty cmdline, >0 for observed bytes.
 * buf_full: nonzero when bytes_read filled the caller's buffer (truncated).
 * matched: nonzero when the bytes contained the late-load marker. */
enum df_proc_verdict df_proc_entry_verdict(int open_errno, int read_errno,
                                            int bytes_read, int buf_full,
                                            int matched);

#endif /* DFROOT_PROC_SCAN_POLICY_H */
