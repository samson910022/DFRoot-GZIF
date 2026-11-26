#!/usr/bin/env python3
"""Harmless objcopy wrapper used by the packaged-KO byte-gate tests.

It forwards every argument to the real objcopy after deliberately flipping one
byte in the .dfroot_ko section of the file objcopy was asked to inspect. The
fixture ELF has no executable code, no kernel module and no device relevance.
The flip only changes one payload byte, so the verifier's pin check must fail
closed and the original packaged SO must remain untouched (the verifier hands
objcopy an isolated copy, never the original).

Usage:
  KO_TEST_REAL_OBJCOPY=/path/to/objcopy \
    mutating_objcopy_wrapper.py [objcopy args...]

The real objcopy is taken from KO_TEST_REAL_OBJCOPY so the verifier can pass
this wrapper as -DKO_OBJCOPY=... with no extra options of its own.
"""

import os
import struct
import subprocess
import sys

SECTION = b".dfroot_ko"


def find_section(blob):
    """Return (offset, size) of .dfroot_ko in a little-endian ELF64 file."""
    if blob[:4] != b"\x7fELF" or blob[4] != 2 or blob[5] != 1:
        raise ValueError("fixture is not a little-endian ELF64 file")
    shoff = struct.unpack_from("<Q", blob, 40)[0]
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", blob, 58)
    if not shoff or shentsize < 64 or shstrndx >= shnum:
        raise ValueError("fixture has no usable section header table")
    str_hdr = shoff + shstrndx * shentsize
    str_off, str_size = struct.unpack_from("<QQ", blob, str_hdr + 24)
    names = blob[str_off:str_off + str_size]
    for index in range(shnum):
        hdr = shoff + index * shentsize
        name_off, _typ, _flags, _addr, offset, size = struct.unpack_from(
            "<IIQQQQ", blob, hdr)
        if names[name_off:].split(b"\0", 1)[0] == SECTION:
            return offset, size
    raise ValueError("fixture has no .dfroot_ko section")


def main():
    real = os.environ.get("KO_TEST_REAL_OBJCOPY", "")
    if not real:
        raise SystemExit("KO_TEST_REAL_OBJCOPY must name the real objcopy")
    args = sys.argv[1:]
    if not args:
        raise SystemExit("usage: mutating_objcopy_wrapper.py [objcopy args...]")
    # objcopy's last argument is the input file (the verifier passes its
    # isolated copy here, never the original build product).
    target = args[-1]
    with open(target, "rb") as fh:
        blob = bytearray(fh.read())
    offset, size = find_section(blob)
    if size <= 0:
        raise ValueError("empty .dfroot_ko fixture section")
    pos = offset + size // 2
    blob[pos] ^= 0x01
    with open(target, "wb") as fh:
        fh.write(blob)
    raise SystemExit(subprocess.call([real, *args]))


if __name__ == "__main__":
    main()
