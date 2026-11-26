/* Host-only unit test for the saved-helper validators in exp.c.
 *
 * DF_HELPER_TEST_ONLY excludes the privileged payload (.incbin blobs, sockets,
 * crypto, vfork/clone) from the translation unit, leaving only the pure
 * df_helper_buf_ok() and df_helper_block_matches() validators. The production
 * code and this test therefore exercise the exact same functions; no stubs or
 * duplicated logic are used. */
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

static void check_block(const uint8_t observed[16], const char *expected,
                        size_t expected_len, size_t off, int want)
{
    int got = df_helper_block_matches(observed, expected, expected_len, off);
    if (got != want) {
        printf("FAIL: df_helper_block_matches(off=%zu, len=%zu) = %d, expected %d\n",
               off, expected_len, got, want);
        failures++;
    }
}

int main(void)
{
    char expected[1408];
    char cur[32];
    size_t i;

    check(0, 0);
    check(31, 0);      /* just below the lower bound */
    check(32, 1);      /* lower bound (inclusive) */
    check(1408, 1);    /* real stripped splicehelper size */
    check(65536, 1);   /* upper bound (inclusive) */
    check(65537, 0);   /* just above the upper bound */
    check(SIZE_MAX, 0);

    /* Synthetic buffers only: no real helper bytes are needed. `expected` is
     * the saved helper buffer; `cur` stands for the first 32 bytes of the
     * file, with the 16-byte patched window at file offset 16. */
    for (i = 0; i < sizeof(expected); i++)
        expected[i] = (char)((i * 37u + 11u) & 0xffu);
    for (i = 0; i < sizeof(cur); i++)
        cur[i] = (char)(0xa5u ^ ((i * 17u) & 0xffu));
    for (i = 0; i < 16; i++)
        cur[16 + i] = expected[16 + i];

    /* Exact window matches; bytes before it (cur[0..15]) differ. */
    check_block((const uint8_t *)(cur + 16), expected, sizeof(expected), 16, 1);

    /* One byte wrong anywhere in the compared window must be rejected. */
    for (i = 0; i < 16; i++) {
        char saved = cur[16 + i];
        cur[16 + i] = (char)(saved ^ 0x01u);
        check_block((const uint8_t *)(cur + 16), expected, sizeof(expected), 16, 0);
        cur[16 + i] = saved;
    }

    /* Bytes before the window are outside the comparison and cannot matter. */
    for (i = 0; i < 16; i++)
        cur[i] = (char)(cur[i] ^ 0xffu);
    check_block((const uint8_t *)(cur + 16), expected, sizeof(expected), 16, 1);
    for (i = 0; i < 16; i++)
        cur[i] = (char)(cur[i] ^ 0xffu);

    /* Null operands are rejected before any arithmetic or dereference. */
    check_block(NULL, expected, sizeof(expected), 16, 0);
    check_block((const uint8_t *)(cur + 16), NULL, sizeof(expected), 16, 0);

    /* Too-short lengths are rejected by bounds even when the bytes match. */
    check_block((const uint8_t *)(cur + 16), expected, 16, 16, 0);
    check_block((const uint8_t *)(cur + 16), expected, 31, 16, 0);

    /* SIZE_MAX has no arithmetic rejection, so a non-matching block must be
     * rejected by the byte comparison. cur[0..15] holds the other pattern. */
    check_block((const uint8_t *)cur, expected, SIZE_MAX, 16, 0);

    if (failures != 0) {
        printf("helper_size_test: %d failure(s)\n", failures);
        return 1;
    }
    printf("helper_size_test: all cases passed\n");
    return 0;
}
