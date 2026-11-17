# Changelog — DFRoot GZIF

## 4.8-gzif (2026-11-17)

Fork of `diabl0w/DFRoot @ 7a6513a` for the Samsung Galaxy S23 Ultra
(SM-S9180 `dm3q`), build `S9180ZHU8GZIF` (Android 17 / One UI 9.0 /
SPL 2026-10-05 / kernel `5.15.197-android13-8-34343818-abS9180ZHU8GZIF`).

Local modifications vs upstream (GPL-2.0 §2(a); each modified file also
carries a per-file notice):

- `app/build.gradle.kts` — `compileSdk`/`targetSdk` 37→36 (host SDK tops at 36);
  `versionCode` 407→408; `versionName` `4.7`→`4.8-gzif`.
- `app/src/main/AndroidManifest.xml` — application label `DFRoot FZG1`→`DFRoot GZIF`.
- `app/src/main/java/df/root/ExploitRunner.java` — `stageAssets()` dual-mode:
  prefer the bundled Samsung-patched loaders from `jniLibs` (`libksudnext.so`
  when `com.rifsxd.ksunext` is the selected manager; `libksud.so` otherwise),
  manager APK's `libksud.so` only as fallback; `stageBin()` copies to `<files>/ksud`.
- `app/src/main/jni/CMakeLists.txt` — `KO_FILES` reduced to `android13-5.15` only.
- `app/src/main/jni/exp.c` — `.incbin` reduced to `dfroot-android13-5.15.ko`;
  `KO_TARGET_CANDIDATES` reduced to `/vendor/lib64/AIAwbModel_v2.2.tflite`.
- `app/src/main/jni/bootstrap.c` — creates `/data/adb` and stages
  `/data/local/tmp/.ksud-stage` before `late-load` (FZG1 loader staging
  protocol; the loader renames `.ksud-stage`→`/data/adb/ksud` itself).
- `app/src/main/jni/libcxx.S` — `ko_target` string →
  `/vendor/lib64/AIAwbModel_v2.2.tflite`.
- `gradle/libs.versions.toml` — AGP 9.4.0→9.5.0.
- `Makefile` — no default signing credentials; `SIGN_PASS`/`SIGN_ALIAS` must
  now be set explicitly (no change in what CI does; CI already requires the
  `KEYSTORE` secrets).

New files: `NOTICE`, `THIRD_PARTY_NOTICES.md`, `CHANGELOG.md`,
`PORTING-GZIF.md`, `app/src/main/jniLibs/arm64-v8a/libksud.so` (KernelSU
3.3.0 Samsung-patched) and `libksudnext.so` (KernelSU-Next 3.4.2
Samsung-patched) — see `NOTICE` for provenance and sha256. `lkm/dfroot.ko`
was removed from the tree (duplicate of the make/CI-built
`app/src/main/jni/ko/dfroot-android13-5.15.ko`) and is now gitignored.

Validation (2026-11-17, SM-S9180 / S9180ZHU8GZIF only):

- run6 (16:49): classic Samsung-patched loader (KernelSU 3.3.0) — full chain,
  `su -c id` → `uid=0(root) gid=0(root) … u:r:ksu:s0`; `/dev/dfm6` present.
- run8 (17:53): Next Samsung-patched loader (KernelSU-Next 3.4.2) — full chain,
  same verification, `su -v` → `3.4.2:KernelSU`.
- Vanilla (unpatched) KernelSU/KernelSU-Next modules panic inside
  `init_module()` on this build regardless of manager — do not bundle them.

Root is **volatile** (late-load only); nothing here persists across reboot.
Only S9180ZHU8GZIF is tested. No claim is made for any other build, model,
or One UI version. GZIF is intentionally absent from
`support/targets-v3.json` / any feed in `samson910022/Root-My-Galaxy-Payloads-Next`.

## Upstream

- 2026-11-06: upstream `7a6513a` — "Fall back to libbase when libc++ hook
  selection fails (#185)" (redzrush101). See upstream changelog/releases at
  https://github.com/diabl0w/DFRoot.
