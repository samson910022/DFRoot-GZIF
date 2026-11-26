/* ELF target discovery for the GZIF exploit.
 *
 * Three-value contract (never conflate INVALID with "try the next file"):
 *   ELF_PARSE_OK          - hook/payload offsets were proven inside a validated
 *                           ELF64 LE AARCH64 ET_DYN layout.
 *   ELF_PARSE_UNSUPPORTED - the file is a well-formed ELF but has no usable
 *                           hook target (e.g. the symbol is absent, .dynstr/.
 *                           dynsym are absent, or no placement fits). The
 *                           caller MAY try another candidate.
 *   ELF_PARSE_INVALID     - malformed/hostile/unsupported metadata or failed
 *                           validation. The caller MUST NOT use this as
 *                           permission to fall back to another file.
 */
#ifndef DFROOT_ELF_PARSER_H
#define DFROOT_ELF_PARSER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum elf_parse_rc {
    ELF_PARSE_OK = 0,
    ELF_PARSE_UNSUPPORTED = 1,
    ELF_PARSE_INVALID = -1,
};

/* Pure rc-to-decision rule shared by exp.c preflight and the host tests.
 * Only "well-formed ELF, no usable hook target" permits trying another
 * candidate. OK means stop and use this one; INVALID means abort, never
 * fall back. Keeping the rule here makes it directly testable. */
int elf_parse_rc_try_next(enum elf_parse_rc rc);

/* A claimed file range is ELF metadata that a shellcode write must never
 * overlap: the ELF header, the phdr/shdr tables, any file-backed section and
 * any non-executable segment. */
struct elf_claim {
    uint64_t off;
    uint64_t len;
};

#define ELF_MAX_CLAIMED 256

/* Full validated layout. `complete` is only set on ELF_PARSE_OK; on every
 * other return code all scalar fields and claims are zeroed so callers cannot
 * accidentally act on a partial parse. */
struct elf_layout {
    uint64_t filesize;
    uint64_t exec_off;
    uint64_t exec_filesz;
    uint64_t exec_vaddr;
    uint64_t hook_target;
    uint64_t payload_target;
    uint32_t first_instruction;
    struct elf_claim claimed[ELF_MAX_CLAIMED];
    size_t claimed_count;
    int complete;
};

/* Returns non-zero if [off, off+len) is invalid or overlaps any claimed ELF
 * metadata range. Returns 0 only for a proven-unclaimed, in-file range. */
int placement_range_claimed(const struct elf_layout *layout, uint64_t off,
                            uint64_t len);

/* Populates `layout` as well as the three legacy out-parameters. All out
 * parameters are zeroed on any non-OK return. */
enum elf_parse_rc find_hook_target_layout(const char *libcxx,
                                          const char *symname,
                                          struct elf_layout *layout,
                                          size_t shellcode_size);

/* Backwards-compatible entry point used by exp.c. */
enum elf_parse_rc find_hook_target(const char *libcxx, const char *symname,
                                   uint64_t *hook_target,
                                   uint64_t *payload_target,
                                   uint32_t *first_instruction,
                                   size_t shellcode_size);

#ifdef __cplusplus
}
#endif

#endif /* DFROOT_ELF_PARSER_H */
