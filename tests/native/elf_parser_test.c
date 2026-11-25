/* Host-only tests for the strict ELF parser contract and checked-in fixtures. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "elf_parser.h"

#define SYMBOL "target_hook"

struct test_case {
    const char *file;
    size_t shellcode_size;
    enum elf_parse_rc expect;
    uint64_t expect_hook;
    uint64_t expect_payload;
    /* When nonzero, the placement must end below this file offset. */
    uint64_t payload_end_max;
};

static const struct test_case cases[] = {
    /* Positive controls: exec-segment-derived tail and unclaimed cave. */
    { "valid-exec-tail.elf",       16, ELF_PARSE_OK,          0x320, 0x400, 0 },
    { "valid-basic.elf",           16, ELF_PARSE_OK,          0x320, 0x340, 0 },
    /* A legit Android OS-specific section type must not be rejected. */
    { "valid-android-relr.elf",    16, ELF_PARSE_OK,          0x320, 0x340, 0 },
    { "valid-tail-unclaimed.elf",  16, ELF_PARSE_OK,          0x320, 0x340, 0 },
    /* Section headers are on another page: no global-EOF tail placement. */
    { "shdrs-other-page.elf",      16, ELF_PARSE_OK,          0x320, 0x340, 0x1000 },
    /* A valid ELF without the symbol or .dynsym is UNSUPPORTED (may retry). */
    { "missing-symbol.elf",        16, ELF_PARSE_UNSUPPORTED, 0, 0, 0 },
    { "no-dynsym.elf",             16, ELF_PARSE_UNSUPPORTED, 0, 0, 0 },
    /* Malformed/hostile metadata is INVALID: NEVER fallback permission. */
    { "invalid-class.elf",         16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-data.elf",          16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-machine.elf",       16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-type.elf",          16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-phentsize.elf",     16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-phoff.elf",         16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-shentsize.elf",     16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-shstrndx.elf",      16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-dynsym-type.elf",   16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-entsize.elf",       16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-size.elf",          16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-link.elf",          16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-info.elf",          16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-shdr-range.elf",    16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "invalid-shname.elf",        16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "sym-undef.elf",             16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "sym-outofrange.elf",        16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "sym-misaligned.elf",        16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "ambiguous-symbol.elf",      16, ELF_PARSE_INVALID, 0, 0, 0 },
    { "multi-exec.elf",            16, ELF_PARSE_INVALID, 0, 0, 0 },
};

static int failures;

static void check(int cond, const char *what, const char *file) {
    if (!cond) {
        printf("FAIL %s: %s\n", file, what);
        failures++;
    }
}

int main(void) {
    size_t i;
    /* Exercise the real rc-to-decision rule used by preflight, rather than
     * restating the enum values: only UNSUPPORTED grants fallback. */
    check(elf_parse_rc_try_next(ELF_PARSE_UNSUPPORTED) == 1,
          "UNSUPPORTED does not try the next candidate", "candidate selection");
    check(elf_parse_rc_try_next(ELF_PARSE_INVALID) == 0,
          "INVALID grants fallback permission", "candidate selection");
    check(elf_parse_rc_try_next(ELF_PARSE_OK) == 0,
          "OK grants fallback permission", "candidate selection");
    check(ELF_PARSE_INVALID != ELF_PARSE_UNSUPPORTED, "INVALID == UNSUPPORTED", "contract");
    check(ELF_PARSE_OK == 0, "OK is not zero", "contract");

    for (i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        const struct test_case *t = &cases[i];
        char path[256];
        struct elf_layout layout;
        uint64_t hook = 0, payload = 0;
        uint32_t first = 0;
        enum elf_parse_rc rc, legacy_rc;

        snprintf(path, sizeof(path), "fixtures/%s", t->file);
        memset(&layout, 0xAA, sizeof(layout));
        rc = find_hook_target_layout(path, SYMBOL, &layout, t->shellcode_size);
        check(rc == t->expect, "unexpected parse result", t->file);

        if (rc == ELF_PARSE_OK) {
            check(layout.complete == 1, "layout not marked complete", t->file);
            check(layout.hook_target == t->expect_hook, "wrong hook offset", t->file);
            check(layout.payload_target == t->expect_payload, "wrong payload offset", t->file);
            if (t->payload_end_max != 0) {
                check(layout.payload_target + t->shellcode_size <= t->payload_end_max,
                      "placement escaped the executable page (global EOF tail?)",
                      t->file);
            }
            check(layout.first_instruction == 0xD503201F, "wrong first instruction", t->file);
            check(placement_range_claimed(&layout, layout.payload_target,
                                          t->shellcode_size) == 0,
                  "placement overlaps ELF metadata", t->file);
            check(placement_range_claimed(&layout, layout.exec_off, 4) != 0,
                  "ELF header not claimed", t->file);
        } else {
            /* Every failure path must zero every out-parameter. */
            size_t k;
            const unsigned char *raw = (const unsigned char *)&layout;
            for (k = 0; k < sizeof(layout); k++) {
                if (raw[k] != 0) break;
            }
            check(k == sizeof(layout), "layout not zeroed on failure", t->file);
        }

        hook = 0xDEADBEEF;
        payload = 0xDEADBEEF;
        first = 0xDEADBEEF;
        legacy_rc = find_hook_target(path, SYMBOL, &hook, &payload, &first,
                                     t->shellcode_size);
        check(legacy_rc == rc, "layout/legacy rc mismatch", t->file);
        if (legacy_rc == ELF_PARSE_OK) {
            check(hook == t->expect_hook && payload == t->expect_payload &&
                  first == 0xD503201F, "legacy out-params wrong", t->file);
        } else {
            check(hook == 0 && payload == 0 && first == 0,
                  "legacy out-params not zeroed on failure", t->file);
        }
    }

    if (failures == 0) {
        printf("elf_parser_test: %zu cases OK\n", sizeof(cases) / sizeof(cases[0]));
        return 0;
    }
    printf("elf_parser_test: %d failure(s)\n", failures);
    return 1;
}
