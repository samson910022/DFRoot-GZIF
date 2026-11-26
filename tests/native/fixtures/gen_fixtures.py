#!/usr/bin/env python3
"""Deterministically generate the minimal ELF64/LE/AArch64/ET_DYN fixtures
used by elf_parser_test.c.

No toolchain, random state or wall-clock value is involved: the same script
always emits byte-identical files. The checked-in binaries are the artifacts
of this script and are used as positive controls and negative/malformed
metadata cases. The payload cave is derived from the executable PT_LOAD end
(or from an unclaimed zero run inside it), never from the global file tail.
Regenerate with:

    python3 tests/native/fixtures/gen_fixtures.py
"""

import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))

EHDR_SIZE = 64
PHDR_SIZE = 56
SHDR_SIZE = 64
SYM_SIZE = 24

PH_OFF = 0x40
DYNSTR_OFF = 0x200
DYNSYM_OFF = 0x240
TEXT_OFF = 0x300
DYNAMIC_OFF = 0x400
RELA_OFF = 0x420
DATA_OFF = 0x440
SHSTRTAB_OFF = 0x450
SH_OFF = 0x600
FILESIZE = 0x820

SYMNAME = "target_hook"
HOOK_VADDR = 0x100320

PT_LOAD, PF_X, PF_W, PF_R = 1, 1, 2, 4
SHT_PROGBITS, SHT_STRTAB, SHT_RELA, SHT_DYNAMIC, SHT_DYNSYM = 1, 3, 4, 6, 11
SHT_ANDROID_RELR = 0x6fffff00
SHF_ALLOC, SHF_WRITE, SHF_EXECINSTR = 2, 1, 4
STB_GLOBAL, STT_FUNC = 1, 2


def make_strtab(names):
    data = bytearray(b"\0")
    offsets = {}
    for name in names:
        offsets[name] = len(data)
        data += name.encode("ascii") + b"\0"
    return bytes(data), offsets


def phdr(ptype, flags, off, vaddr, filesz, memsz, align):
    return struct.pack("<IIQQQQQQ", ptype, flags, off, vaddr, 0, filesz, memsz, align)


def shdr(name, stype, flags, addr, off, size, link, info, align, entsize):
    return struct.pack("<IIQQQQIIQQ", name, stype, flags, addr, off, size,
                       link, info, align, entsize)


def sym(name_off, info, shndx, value, size=0x20):
    return struct.pack("<IBBHQQ", name_off, info, 0, shndx, value, size)


def build_base(claim_tail=False, ambiguous=False, symbol_name=SYMNAME,
               tail_free=False, shoff_override=None, filesize_override=None):
    """Return (bytearray, section-header count, shstrndx, shoff, filesize).

    tail_free moves the non-executable metadata away from the executable
    segment's file end so that end is an unclaimed, in-page placement.
    shoff_override/filesize_override put section headers on another page.
    """
    section_names = [".dynstr", ".dynsym", ".text", ".dynamic", ".rela.dyn",
                     ".data", ".claim", ".shstrtab"]
    names = section_names if claim_tail else [n for n in section_names if n != ".claim"]
    shstr, name_offsets = make_strtab(names)

    dynstr, sym_offsets = make_strtab([symbol_name])
    sym_name_off = sym_offsets[symbol_name]
    assert len(dynstr) <= 0x40
    assert len(shstr) <= 0x80

    # With tail_free the metadata segments start at 0x500, leaving the exec
    # segment end (0x400) unclaimed but still on the exec segment's page.
    dynamic_off = 0x500 if tail_free else DYNAMIC_OFF
    rela_off = 0x520 if tail_free else RELA_OFF
    data_off = 0x540 if tail_free else DATA_OFF
    shstrtab_off = 0x550 if tail_free else SHSTRTAB_OFF
    shoff = shoff_override if shoff_override is not None else (
        0x820 if claim_tail else SH_OFF)
    filesize = filesize_override if filesize_override is not None else (
        0xA20 if claim_tail else FILESIZE)
    shnum = 9 if claim_tail else 8
    shstrndx = shnum - 1  # .shstrtab is the final section in both variants

    img = bytearray(filesize)

    # ELF header.
    ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + b"\0" * 8
    ehdr = ident + struct.pack(
        "<HHIQQQIHHHHHH",
        3,                 # ET_DYN
        183,               # EM_AARCH64
        1,                 # EV_CURRENT
        HOOK_VADDR,        # e_entry
        PH_OFF,
        shoff,
        0,                 # e_flags
        EHDR_SIZE,
        PHDR_SIZE,
        3,                 # e_phnum
        SHDR_SIZE,
        shnum,
        shstrndx,
    )
    assert len(ehdr) == EHDR_SIZE
    img[:EHDR_SIZE] = ehdr

    # Three PT_LOADs: metadata (R), executable (.text), trailing data (RW).
    phdrs = b"".join([
        phdr(PT_LOAD, PF_R, 0, 0, 0x300, 0x300, 0x1000),
        phdr(PT_LOAD, PF_R | PF_X, TEXT_OFF, 0x100300, 0x100, 0x100, 0x1000),
        phdr(PT_LOAD, PF_R | PF_W, dynamic_off, 0x200000 + dynamic_off,
             0xD0, 0xD0, 0x1000),
    ])
    assert len(phdrs) == 3 * PHDR_SIZE
    img[PH_OFF:PH_OFF + len(phdrs)] = phdrs

    img[DYNSTR_OFF:DYNSTR_OFF + len(dynstr)] = dynstr
    symbols = bytearray(sym(0, 0, 0, 0))
    target = sym(sym_name_off, (STB_GLOBAL << 4) | STT_FUNC, 3, HOOK_VADDR)
    symbols += target
    if ambiguous:
        symbols += target
    assert len(symbols) <= 0x60
    img[DYNSYM_OFF:DYNSYM_OFF + len(symbols)] = symbols

    # A NOP at the hook site; the four bytes after it are zero padding.
    struct.pack_into("<I", img, 0x320, 0xD503201F)

    dyn = b"".join([
        struct.pack("<qQ", 0, 0),                 # DT_NULL
        struct.pack("<qQ", 5, DYNSTR_OFF),        # DT_STRTAB
    ])
    img[dynamic_off:dynamic_off + len(dyn)] = dyn
    img[rela_off:rela_off + 24] = struct.pack("<QQq", 0, 0, 0)
    img[shstrtab_off:shstrtab_off + len(shstr)] = shstr

    def sn(name):
        return name_offsets[name]

    sections = [
        shdr(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        shdr(sn(".dynstr"), SHT_STRTAB, SHF_ALLOC, DYNSTR_OFF, DYNSTR_OFF,
             0x40, 0, 0, 8, 0),
        shdr(sn(".dynsym"), SHT_DYNSYM, SHF_ALLOC, DYNSYM_OFF, DYNSYM_OFF,
             0x48 if ambiguous else 0x30, 1, 1, 8, SYM_SIZE),
        shdr(sn(".text"), SHT_PROGBITS, SHF_ALLOC | SHF_EXECINSTR, 0x100300,
             TEXT_OFF, 0x40, 0, 0, 16, 0),
        shdr(sn(".dynamic"), SHT_DYNAMIC, SHF_ALLOC | SHF_WRITE,
             0x200000 + dynamic_off, dynamic_off, 0x20, 1, 0, 8, 16),
        shdr(sn(".rela.dyn"), SHT_RELA, SHF_ALLOC, 0x200000 + rela_off,
             rela_off, 0x18, 2, 3, 8, 24),
        shdr(sn(".data"), SHT_PROGBITS, SHF_ALLOC | SHF_WRITE,
             0x200000 + data_off, data_off, 0x10, 0, 0, 8, 0),
    ]
    if claim_tail:
        sections.append(shdr(sn(".claim"), SHT_PROGBITS, SHF_ALLOC, 0x800,
                             0x800, 0x10, 0, 0, 8, 0))
    sections.append(shdr(sn(".shstrtab"), SHT_STRTAB, 0, 0, shstrtab_off,
                         len(shstr), 0, 0, 1, 0))
    assert len(sections) == shnum
    table = b"".join(sections)
    img[shoff:shoff + len(table)] = table
    return img, shnum, shstrndx, shoff, filesize


def mutate(base, kind):
    """Apply one targeted corruption to a fresh copy of the valid base."""
    img = bytearray(base)
    shoff = struct.unpack_from("<Q", img, 40)[0]

    def shdr_at(i):
        return shoff + i * SHDR_SIZE

    def sym_at(i):
        return DYNSYM_OFF + i * SYM_SIZE

    if kind == "missing-symbol":
        # Same length as target_hook so every offset stays valid.
        img[DYNSTR_OFF + 1:DYNSTR_OFF + 1 + len("other_hook")] = b"other_hook"
    elif kind == "no-dynsym":
        # Rename .dynsym to the empty string: the table is well-formed but
        # there is no dynamic symbol table, so the rc must be UNSUPPORTED.
        struct.pack_into("<I", img, shdr_at(2), 0)
    elif kind == "invalid-class":
        img[4] = 1                                # ELFCLASS32
    elif kind == "invalid-data":
        img[5] = 2                                # ELFDATA2MSB
    elif kind == "invalid-machine":
        struct.pack_into("<H", img, 18, 62)       # EM_X86_64
    elif kind == "invalid-type":
        struct.pack_into("<H", img, 16, 2)        # ET_EXEC
    elif kind == "invalid-phentsize":
        struct.pack_into("<H", img, 54, 55)
    elif kind == "invalid-phoff":
        struct.pack_into("<Q", img, 32, 0x7000)   # past EOF
    elif kind == "invalid-shentsize":
        struct.pack_into("<H", img, 58, 63)
    elif kind == "invalid-shstrndx":
        struct.pack_into("<H", img, 62, 0xFFFF)   # SHN_XINDEX
    elif kind == "invalid-dynsym-type":
        # Give the section named .dynsym a non-DYNSYM type. The parser checks
        # the type at name-identification time, so this trips the .dynsym type
        # rule rather than the RELA sh_link check at a later section.
        struct.pack_into("<I", img, shdr_at(2) + 4, SHT_PROGBITS)
    elif kind == "invalid-entsize":
        struct.pack_into("<Q", img, shdr_at(2) + 56, 23)
    elif kind == "invalid-size":
        struct.pack_into("<Q", img, shdr_at(2) + 32, 0x31)  # not a multiple of 24
    elif kind == "invalid-link":
        struct.pack_into("<I", img, shdr_at(2) + 40, 99)
    elif kind == "invalid-info":
        struct.pack_into("<I", img, shdr_at(2) + 44, 9)     # > symbol count
    elif kind == "invalid-shdr-range":
        struct.pack_into("<Q", img, 40, FILESIZE - 8)       # table truncated
    elif kind == "invalid-shname":
        struct.pack_into("<I", img, shdr_at(1), 0xFFFF)     # outside .shstrtab
    elif kind == "sym-undef":
        struct.pack_into("<H", img, sym_at(1) + 6, 0)       # SHN_UNDEF
    elif kind == "sym-outofrange":
        struct.pack_into("<Q", img, sym_at(1) + 8, 0x400000)
    elif kind == "sym-misaligned":
        struct.pack_into("<Q", img, sym_at(1) + 8, HOOK_VADDR + 1)
    elif kind == "ambiguous-symbol":
        img = bytearray(build_base(ambiguous=True)[0])
        # The third symbol repeats target_hook; the parser must reject the
        # ambiguity instead of silently choosing the first match.
    elif kind == "multi-exec":
        struct.pack_into("<I", img, PH_OFF + 2 * PHDR_SIZE + 4, PF_R | PF_X)
    else:
        raise ValueError(kind)
    return bytes(img)


def with_android_relr(base):
    """Set one ordinary section's type to SHT_ANDROID_RELR (0x6fffff00).

    Device evidence: libc++.so on a clean S23 has SHT_ANDROID_RELR sections
    (logged on-device as decimal 1879047936), which the strict allowlist used
    to reject. Only that section's sh_type changes; every offset, size,
    address, flag, link, info, entsize and name stays byte-identical to
    valid-basic, so the expected layout is unchanged.
    """
    img = bytearray(base)
    shoff = struct.unpack_from("<Q", img, 40)[0]

    # shdr[6] was .data (SHT_PROGBITS, no RELA-only fields): turn it into an
    # Android RELR section. No sh_link/sh_info/sh_entsize cleanup is needed.
    struct.pack_into("<I", img, shoff + 6 * SHDR_SIZE + 4, SHT_ANDROID_RELR)
    return bytes(img)


def with_misaligned_file_offset(base):
    """Give shdr[6] (.data) a file offset that is not a multiple of its
    sh_addralign while keeping its sh_addr aligned.

    Device evidence: libc++.so on a clean S23 has sections such as shdr[28]
    whose sh_offset is not a multiple of sh_addralign, which the old parser
    rejected even though ELF only requires sh_addr alignment. Only sh_offset
    and sh_size change: 0x444 % 8 == 4 (misaligned), while
    sh_addr 0x200440 % 8 == 0 (aligned). The size is reduced from 0x10 to
    0xC so the extent 0x444..0x450 stays inside the file and does not
    overlap .shstrtab at 0x450. Every other field - including sh_addr,
    sh_type, flags, link, info, entsize, name and the hook/payload bytes -
    stays byte-identical to valid-basic.
    """
    img = bytearray(base)
    shoff = struct.unpack_from("<Q", img, 40)[0]

    struct.pack_into("<Q", img, shoff + 6 * SHDR_SIZE + 24, 0x444)  # sh_offset
    struct.pack_into("<Q", img, shoff + 6 * SHDR_SIZE + 32, 0xC)    # sh_size
    return bytes(img)


def main():
    os.makedirs(HERE, exist_ok=True)
    base, _shnum, _shstrndx, _shoff, _size = build_base()
    fixtures = {
        # Base layout: the exec segment end is claimed by .dynamic, so a cave
        # in the executable PT_LOAD is selected.
        "valid-basic.elf": bytes(base),
        # Same layout as valid-basic, but with an SHT_ANDROID_RELR section
        # (0x6fffff00 / decimal 1879047936) like the target device's libc++.so.
        "valid-android-relr.elf": with_android_relr(base),
        # shdr[6] has a file offset that is not a multiple of sh_addralign,
        # while its sh_addr remains aligned: must still parse as valid.
        "valid-misaligned-file-offset.elf": with_misaligned_file_offset(base),
        # The exec segment end is unclaimed and on the exec page: exercises
        # the exec-segment-derived tail branch.
        "valid-exec-tail.elf": bytes(build_base(tail_free=True)[0]),
        # .claim covers the exec segment end, so the exec-derived tail is
        # unusable and placement falls back to an unclaimed zero-run cave.
        # (This fixture never exercises a global-EOF tail: that is no longer
        # a legal placement candidate.)
        "valid-tail-unclaimed.elf": bytes(build_base(claim_tail=True)[0]),
        # Section headers end at 0x1200 on page 1 (file ends at 0x1400,
        # leaving 0x200 of global-tail slack) while the exec segment lives
        # on page 0. The old global-EOF tail would be outside every exec
        # mapping; the parser must return a placement inside the exec page.
        "shdrs-other-page.elf": bytes(build_base(
            shoff_override=0x1000, filesize_override=0x1400)[0]),
    }
    negatives = [
        "missing-symbol",
        "no-dynsym",
        "invalid-class", "invalid-data", "invalid-machine", "invalid-type",
        "invalid-phentsize", "invalid-phoff", "invalid-shentsize",
        "invalid-shstrndx", "invalid-dynsym-type", "invalid-entsize",
        "invalid-size", "invalid-link", "invalid-info", "invalid-shdr-range",
        "invalid-shname", "sym-undef", "sym-outofrange", "sym-misaligned",
        "ambiguous-symbol", "multi-exec",
    ]
    for kind in negatives:
        fixtures[kind + ".elf"] = mutate(base, kind)

    for name, data in sorted(fixtures.items()):
        with open(os.path.join(HERE, name), "wb") as f:
            f.write(data)
        print("%-28s %5d bytes" % (name, len(data)))


if __name__ == "__main__":
    main()
