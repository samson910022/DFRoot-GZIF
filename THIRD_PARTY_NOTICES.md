# Third-party notices — DFRoot GZIF

Components bundled with, linked against, or built by this project, and their licences.

| Component | Role | Licence | Source |
|---|---|---|---|
| diabl0w/DFRoot @ `7a6513a` | upstream baseline | GPL-2.0-only | https://github.com/diabl0w/DFRoot |
| DFRoot modifications (this fork) | application source | GPL-2.0-only | this repository |
| `libksud.so` — Samsung-patched KernelSU `ksud` 3.3.0 | bundled executable | userspace GPL-3.0-or-later; embedded `android13-5.15_kernelsu.ko` GPL-2.0-only | `tiann/KernelSU` v3.3.0 + patch in `samson910022/Root-My-Galaxy-Payloads-Next` |
| `libksudnext.so` — Samsung-patched KernelSU-Next `ksud` 3.4.2 | bundled executable | userspace GPL-3.0-or-later; embedded `android13-5.15_kernelsu.ko` GPL-2.0-only | `KernelSU-Next/KernelSU-Next` v3.4.2 + patch in `samson910022/Root-My-Galaxy-Payloads-Next` |
| `dfroot.ko` (not committed; built from `lkm/dfroot.c`) | LKM | GPL-2.0-only (`MODULE_LICENSE("GPL")`) | this repository, `lkm/` |
| splicehelper / `jni/*.c`, `*.S` | payload sources | GPL-2.0-only | this repository (upstream DFRoot + GZIF modifications) |
| libcxx/shellcode headers | payload sources | GPL-2.0-only | this repository |
| AndroidX (appcompat, material, constraintlayout, preference) | app dependencies | Apache-2.0 | Google Maven (versions in `gradle/libs.versions.toml`) |
| Gradle / Android Gradle Plugin / Gradle wrapper | build | Apache-2.0 | gradle.org / Google Maven |
| `aes256.c/h`, `libcxx.h` etc. under `app/src/main/jni/` | payload sources | see file headers (GPL-2.0-only unless marked otherwise) | this repository |

Notes

- The applicationId (`df.root`) and the app UI credit the upstream authors;
  upstream's credits section in README.md is retained verbatim.
- The Samsung-patched loader binaries carry no other obligations than those
  stated in `NOTICE` (source offer + aggregation statement).
- No component in this repository is GPL-2.0-**and-later** vs GPL-2.0-only
  ambiguous: upstream DFRoot is GPL-2.0-**only** (LICENSE §2 text, no "or
  later" grant), and this fork remains so.
- The DFRoot chain does not sublicense any third-party component, and no
  third-party component changes the GPL-2.0-only reading of this repository's
  own source.
