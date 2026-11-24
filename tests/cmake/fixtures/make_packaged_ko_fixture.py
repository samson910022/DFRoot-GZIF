#!/usr/bin/env python3
"""Generate a deterministic, harmless ELF64 fixture with a .dfroot_ko section.

This is a host-only test fixture for tests/cmake/packaged_ko_test.cmake. The
section contains pseudo-random bytes derived from a fixed label: no executable
code, no kernel module, no device relevance. It lets the tests exercise the
real objcopy-based packaged-byte verifier without an Android/NDK build.

Usage:
  make_packaged_ko_fixture.py --so OUT.so --payload-out OUT.bin
                              [--size N] [--no-section] [--corrupt]

  --size N       override the payload length (default 256)
  --no-section   omit the .dfroot_ko section (section-not-found case)
  --corrupt      flip one payload byte after writing --payload-out-equivalent
                 bytes, so the .so hash differs from the clean payload hash
"""

import argparse
import hashlib
import struct

SECTION_NAME = ".dfroot_ko"
SHSTRTAB_NAME = ".shstrtab"
LABEL = b"DFRoot packaged-KO fixture (harmless deterministic test bytes)"


def payload_bytes(size):
    """Deterministic expansion of LABEL via SHA-256 counter blocks."""
    out = bytearray()
    counter = 0
    while len(out) < size:
        out += hashlib.sha256(LABEL + struct.pack("<Q", counter)).digest()
        counter += 1
    return bytes(out[:size])


def section_header(name_off, typ, flags, offset, size, addralign):
    # Elf64_Shdr: name, type, flags, addr, offset, size, link, info,
    #             addralign, entsize
    return struct.pack("<IIQQQQIIQQ", name_off, typ, flags, 0, offset, size,
                       0, 0, addralign, 0)


def build_elf(payload, with_section):
    """Build a minimal ELF64 LE / ET_REL / EM_X86_64 object with a shstrtab.

    EM_X86_64 (62) is used deliberately so host binutils objcopy can read the
    fixture; the real gate uses llvm-objcopy against the AArch64 libdfroot.so.
    The section payload is data, never executed.
    """
    names = bytearray(b"\0")
    name_offsets = {}

    wanted = ([SECTION_NAME] if with_section else []) + [SHSTRTAB_NAME]
    for name in wanted:
        name_offsets[name] = len(names)
        names += name.encode("ascii") + b"\0"
    names = bytes(names)

    ehsize = 64
    shentsize = 64
    shnum = 3 if with_section else 2
    shstrndx = 2 if with_section else 1

    payload_off = ehsize
    shstr_off = payload_off + (len(payload) if with_section else 0)
    shoff = shstr_off + len(names)
    shoff += (-shoff) % 8  # 8-byte align the section header table

    # EI_MAG, EI_CLASS=2 (ELF64), EI_DATA=1 (LE), EI_VERSION=1, EI_OSABI=0,
    # EI_ABIVERSION=0, then seven reserved zero bytes.
    ident = b"\x7fELF" + bytes([2, 1, 1, 0, 0]) + bytes(7)
    ehdr = ident + struct.pack(
        "<HHIQQQIHHHHHH",
        1,       # e_type = ET_REL
        62,      # e_machine = EM_X86_64 (host-readable fixture)
        1,       # e_version
        0,       # e_entry
        0,       # e_phoff (no program headers)
        shoff,   # e_shoff
        0,       # e_flags
        ehsize,  # e_ehsize
        0, 0,    # e_phentsize, e_phnum
        shentsize, shnum, shstrndx,
    )
    assert len(ehdr) == ehsize

    body = bytearray(ehdr)
    if with_section:
        body += payload
    body += names
    body += b"\0" * (shoff - len(body))
    assert len(body) == shoff

    shdrs = bytearray()
    shdrs += section_header(0, 0, 0, 0, 0, 0)  # SHT_NULL
    if with_section:
        shdrs += section_header(name_offsets[SECTION_NAME], 1, 0x2,
                                payload_off, len(payload), 16)  # PROGBITS/ALLOC
    shdrs += section_header(name_offsets[SHSTRTAB_NAME], 3, 0,
                            shstr_off, len(names), 1)  # SHT_STRTAB
    assert len(shdrs) == shnum * shentsize
    return bytes(body + shdrs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--so", required=True, help="output .so fixture path")
    parser.add_argument("--payload-out", required=True,
                        help="path for the clean (uncorrupted) payload bytes")
    parser.add_argument("--size", type=int, default=256,
                        help="payload length in bytes (default: 256)")
    parser.add_argument("--no-section", action="store_true",
                        help="omit the .dfroot_ko section")
    parser.add_argument("--corrupt", action="store_true",
                        help="flip one byte in the embedded section")
    args = parser.parse_args()

    if args.size <= 0:
        parser.error("--size must be positive")
    clean = payload_bytes(args.size)
    with open(args.payload_out, "wb") as fh:
        fh.write(clean)

    embedded = bytearray(clean)
    if args.corrupt:
        embedded[len(embedded) // 2] ^= 0x01

    with open(args.so, "wb") as fh:
        fh.write(build_elf(bytes(embedded), not args.no_section))


if __name__ == "__main__":
    main()
