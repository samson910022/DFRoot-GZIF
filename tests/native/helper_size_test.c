/* Host-only unit test for the saved-helper buffer size validator in exp.c.
 *
 * DF_HELPER_TEST_ONLY excludes the privileged payload (.incbin blobs, sockets,
 * crypto, vfork/clone) from the translation unit, leaving only the pure
 * df_helper_buf_ok() validator. The production code and this test therefore
 * exercise the exact same function; no stubs or duplicated logic are used. */
#define DF_HELPER_TEST_ONLY 1
#include "../../app/src/main/jni/exp.c"

#include <stdio.h>

static int failures;

static void check(size_t len, int expected)
{
    int got = df_helper_buf_ok(len);
    if (got != expected) {
        printf("FAIL: df_helper_buf_ok(%zu) = %d, expected %d\n",
               len, got, expected);
        failures++;
    }
}

int main(void)
{
    check(0, 0);
    check(31, 0);      /* just below the lower bound */
    check(32, 1);      /* lower bound (inclusive) */
    check(1408, 1);    /* real stripped splicehelper size */
    check(65536, 1);   /* upper bound (inclusive) */
    check(65537, 0);   /* just above the upper bound */
    check(SIZE_MAX, 0);

    if (failures != 0) {
        printf("helper_size_test: %d failure(s)\n", failures);
        return 1;
    }
    printf("helper_size_test: all cases passed\n");
    return 0;
}
