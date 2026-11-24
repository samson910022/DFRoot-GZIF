# Third-party notices — DFRoot GZIF

Components bundled with, linked against, or built by this project, and their licences.

| Component | Role | Licence | Source |
|---|---|---|---|
| diabl0w/DFRoot @ `7a6513a` | upstream baseline | GPL-2.0-only | https://github.com/diabl0w/DFRoot |
| DFRoot modifications (this fork) | application source | GPL-2.0-only | this repository |
| `libksud.so` — Samsung-patched KernelSU `ksud` 3.3.0 | bundled executable | userspace GPL-3.0-or-later (see `GPL-3.0.txt`); embedded `android13-5.15_kernelsu.ko` GPL-2.0-only | `tiann/KernelSU` v3.3.0 + patch in `samson910022/Root-My-Galaxy-Payloads-Next` |
| `libksudnext.so` — Samsung-patched KernelSU-Next `ksud` 3.4.2 | bundled executable | userspace GPL-3.0-or-later (see `GPL-3.0.txt`); embeds four KMI modules — `android12-5.10_kernelsu.ko`, `android13-5.15_kernelsu.ko`, `android14-6.1_kernelsu.ko`, `android15-6.6_kernelsu.ko` — all GPL-2.0-only; GZIF uses `android13-5.15` | `KernelSU-Next/KernelSU-Next` v3.4.2 + patch in `samson910022/Root-My-Galaxy-Payloads-Next` |
| `dfroot.ko` (not committed; built from `lkm/dfroot.c`) | LKM | GPL-2.0-only (`MODULE_LICENSE("GPL")`) | this repository, `lkm/` |
| splicehelper / `jni/*.c`, `*.S` | payload sources | GPL-2.0-only | this repository (upstream DFRoot + GZIF modifications) |
| libcxx/shellcode headers | payload sources | GPL-2.0-only | this repository |
| Gradle / Android Gradle Plugin / Gradle wrapper | build | Apache-2.0 | gradle.org / Google Maven |
| `GPL-3.0.txt` | licence text conveyed with signed release assets | verbatim GNU GPLv3 (official text) | https://www.gnu.org/licenses/gpl-3.0.txt |
| `aes256.c/h`, `libcxx.h` etc. under `app/src/main/jni/` | payload sources | see file headers (GPL-2.0-only unless marked otherwise) | this repository |

Notes

- The app declares no third-party Java/Kotlin libraries: `dependencies {}` in
  `app/build.gradle.kts` is empty, the settings UI uses the deprecated but
  functional framework `android.preference.*` API, and its themes inherit the
  **platform** `android:Theme.Material` / `android:Theme.Material.Light`
  (`app/src/main/res/values*/themes.xml`). Those are Android framework
  resources, **not** the Material Components library
  (`com.google.android.material`). Consequently the app has **no AndroidX or
  Material Components dependency** and no corresponding Apache-2.0 notice
  applies. `gradle/libs.versions.toml` declares the AGP version
  (`agp = "9.5.0"`) plus the `android-application` plugin alias and is the
  single source of truth for AGP (no root `build.gradle.kts` pin).
- The applicationId (`df.root`) and the app UI credit the upstream authors;
  upstream's credits section in README.md is retained verbatim.
- `libksudnext.so` embeds four KMI modules (`android12-5.10`, `android13-5.15`,
  `android14-6.1`, `android15-6.6`); GZIF uses only `android13-5.15`.
- The Samsung-patched **userspace** loaders are GPL-3.0-or-later: they carry the
  full GPLv3 obligations, including conveying a copy of the licence and the
  complete corresponding source offer in `NOTICE`, **not merely** the
  aggregation statement. The verbatim official licence text is shipped as
  `GPL-3.0.txt` in signed release assets.
- No component in this repository is GPL-2.0-**and-later** vs GPL-2.0-only
  ambiguous: upstream DFRoot is GPL-2.0-**only** (LICENSE §2 text, no "or
  later" grant), and this fork remains so. The GPL-3.0-or-later loaders are
  separate aggregated executables, not a relicense of this repository's own
  GPL-2.0-only source.
- The DFRoot chain does not sublicense any third-party component, and no
  third-party component changes the GPL-2.0-only reading of this repository's
  own source.
