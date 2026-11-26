/* Host-side table test for the pure /proc scan verdict. No syscalls, no
 * /proc entries: every errno/read result is supplied by the table. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>

#include "../../app/src/main/jni/proc_scan_policy.h"

struct row {
    const char *name;
    int open_errno;
    int read_errno;
    int bytes_read;
    int buf_full;
    int matched;
    enum df_proc_verdict want;
};

int main(void)
{
    const struct row rows[] = {
        {"clean non-match",        0,     0,     32, 0, 0, DF_PROC_BENIGN},
        {"empty cmdline",          0,     0,      0, 0, 0, DF_PROC_BENIGN},
        {"gone at open",       ENOENT,     0,     -1, 0, 0, DF_PROC_BENIGN},
        {"gone at read (ESRCH)",   0, ESRCH,     -1, 0, 0, DF_PROC_BENIGN},
        {"gone at read (ENOENT)",  0, ENOENT,     0, 0, 0, DF_PROC_BENIGN},
        {"match",                  0,     0,     64, 0, 1, DF_PROC_MATCH},
        {"match in full buffer",   0,     0,    255, 1, 1, DF_PROC_MATCH},
        {"match despite race", ENOENT,     0,     -1, 0, 1, DF_PROC_MATCH},
        {"open EACCES",       EACCES,     0,     -1, 0, 0, DF_PROC_INCOMPLETE},
        {"read EIO",               0,    EIO,     -1, 0, 0, DF_PROC_INCOMPLETE},
        {"negative read, no errno",0,     0,     -1, 0, 0, DF_PROC_INCOMPLETE},
        {"full buffer, no match",  0,     0,    255, 1, 0, DF_PROC_INCOMPLETE},
        {"open ENOENT + read EIO", ENOENT, EIO,   -1, 0, 0, DF_PROC_INCOMPLETE},
    };
    size_t i;

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        enum df_proc_verdict got = df_proc_entry_verdict(
            rows[i].open_errno, rows[i].read_errno, rows[i].bytes_read,
            rows[i].buf_full, rows[i].matched);
        if (got != rows[i].want) {
            printf("FAIL %s: got %d want %d\n", rows[i].name,
                   (int)got, (int)rows[i].want);
            return 1;
        }
    }
    printf("proc_scan_policy_test: all %zu cases passed\n",
           sizeof(rows) / sizeof(rows[0]));
    return 0;
}
