# Third-party notices — DFRoot GZIF

Components bundled with, linked against, or built by this project, and their licences.

Modified 2026-11-25 (DFRoot GZIF): corrected the dependency inventory — the app
module declares **only** JUnit 4 and Robolectric as (test-only) dependencies; the
AndroidX aliases in `gradle/libs.versions.toml` are not applied by the app module
and are not shipped. Loader provenance and SHA-256 pins were re-verified against
`app/build.gradle.kts` and the bundled `jniLibs` binaries (see `NOTICE`). No
bundled loader or other third-party component was modified by the 2026-11-25
PR #2 work; only this repository's GPL-2.0-only application/native sources were
changed, each with a dated per-file notice.

| Component | Role | Licence | Source |
|---|---|---|---|
| diabl0w/DFRoot @ `7a6513a` | upstream baseline | GPL-2.0-only | https://github.com/diabl0w/DFRoot |
| DFRoot modifications (this fork) | application source | GPL-2.0-only | this repository |
| `libksud.so` — Samsung-patched KernelSU `ksud` 3.3.0 | bundled executable | userspace GPL-3.0-or-later (see `GPL-3.0.txt`); embedded `android13-5.15_kernelsu.ko` GPL-2.0-only | `tiann/KernelSU` v3.3.0 + patch in `samson910022/Root-My-Galaxy-Payloads-Next` |
| `libksudnext.so` — Samsung-patched KernelSU-Next `ksud` 3.4.2 | bundled executable | userspace GPL-3.0-or-later (see `GPL-3.0.txt`); embeds four KMI modules — `android12-5.10_kernelsu.ko`, `android13-5.15_kernelsu.ko`, `android14-6.1_kernelsu.ko`, `android15-6.6_kernelsu.ko` — all GPL-2.0-only; GZIF uses `android13-5.15` | `KernelSU-Next/KernelSU-Next` v3.4.2 + patch in `samson910022/Root-My-Galaxy-Payloads-Next` |
| `dfroot.ko` (not committed; built from `lkm/dfroot.c`) | LKM | GPL-2.0-only (`MODULE_LICENSE("GPL")`) | this repository, `lkm/` |
| splicehelper / `jni/*.c`, `*.S` | payload sources | GPL-2.0-only | this repository (upstream DFRoot + GZIF modifications) |
| libcxx/shellcode headers (app/src/main/jni/include.inc) | payload sources | BSD-3-Clause (Odzhan, Copyright (c) 2018 Odzhan) for include.inc; GPL-2.0-only for the rest | this repository + Odzhan |
| AndroidX (appcompat, material, constraintlayout, preference) | **not used by the app** — catalog aliases only in `gradle/libs.versions.toml`; no runtime dependency is declared in `app/build.gradle.kts` | Apache-2.0 | Google Maven (versions in `gradle/libs.versions.toml`) |
| JUnit 4.13.2 | **test only** (`testImplementation`) | EPL-1.0 | https://junit.org/junit4/ |
| Robolectric 4.18.1 | **test only** (`testImplementation`) | Apache-2.0 | https://robolectric.org/ |
| Gradle / Android Gradle Plugin / Gradle wrapper | build | Apache-2.0 | gradle.org / Google Maven |
| `GPL-3.0.txt` | licence text conveyed with signed release assets and unsigned CI artifacts | verbatim GNU GPLv3 (official text) | https://www.gnu.org/licenses/gpl-3.0.txt |
| `LICENSE` | repository's own GPL-2.0-only licence, conveyed with signed release assets and unsigned CI artifacts | verbatim GNU GPLv2 | this repository |
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
  `GPL-3.0.txt` in both signed release assets and unsigned CI artifacts.
- No component in this repository is GPL-2.0-**and-later** vs GPL-2.0-only
  ambiguous: upstream DFRoot is GPL-2.0-**only** (LICENSE §2 text, no "or
  later" grant), and this fork remains so. The GPL-3.0-or-later loaders are
  separate aggregated executables, not a relicense of this repository's own
  GPL-2.0-only source.
- The DFRoot chain does not sublicense any third-party component, and no
  third-party component changes the GPL-2.0-only reading of this repository's
  own source.

### `app/src/main/jni/include.inc` — BSD-3-Clause notice (verbatim)

`app/src/main/jni/include.inc` is a shellcode header by Odzhan, licensed
BSD-3-Clause (not GPL-2.0-only). Because this `THIRD_PARTY_NOTICES.md` is
conveyed as a release/CI artifact alongside the APK, the BSD clause-2 notice
must be reproduced here verbatim. The notice below is copied exactly from
`app/src/main/jni/include.inc` lines 2–28 (including the leading indentation and
the closing `*/`):

```
  Copyright © 2018 Odzhan. All Rights Reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are
  met:

  1. Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.

  2. Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.

  3. The name of the author may not be used to endorse or promote products
  derived from this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY AUTHORS "AS IS" AND ANY EXPRESS OR
  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
  INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
  HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
  STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
  ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
  POSSIBILITY OF SUCH DAMAGE. */
```
