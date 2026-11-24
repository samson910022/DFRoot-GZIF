# Changelog — DFRoot GZIF

<<<<<<< HEAD
## Unreleased — PR #1 hardening (CI build reliability)

Hardening pass over the build, signing and release pipeline; no change to the
exploit chain or to the GZIF validation claims. Documentation was brought in
line with the shipped configuration (Gradle wrapper **9.9.2** with a pinned
distribution SHA-256; AGP **9.5.0** effective through
`gradle/libs.versions.toml` with no root `build.gradle.kts` pin; Kotlin/Java
sources unchanged).

- **Signing writer and fail-closed behavior** (`scripts/WriteSigningProperties.java`,
  `app/build.gradle.kts`): signing material is serialized safely for arbitrary
  passwords/aliases and locales; `signing.properties` is optional at
  configuration time (a fresh checkout no longer fails `assembleDebug`),
  incomplete properties fail with a readable error, and local keystore/property
  files are created with restrictive permissions and verified after writing.
  Without `signing.properties`, **Release is unsigned** (warning only) and
  **Debug is debug-keystore signed**; an installable Release requires signing.
- **Protected release environment** (`.github/workflows/build.yml`): least
  privilege by default; only a push of a `v*` tag runs `build-release` in the
  `release` environment, where the four `KEYSTORE*` secrets are available.
  PR/branch/manual runs build a **debug-keystore-signed Debug + unsigned
  Release** pair with no secrets, with no release signing on those events, and
  never publish a GitHub Release; their unsigned APKs are uploaded as a public,
  downloadable Actions artifact carrying the same notice/licence bundle.
  Ancestry check precedes any secret reference; the signed APK
  is `apksigner`-verified, checksummed and uploaded with 1-day retention; the
  publish job only downloads, verifies and publishes. Action references are
  hardened/pinned to full 40-hex SHAs and permissions are least-privilege; the
  gates and exact step/run-script allowlists remain enforced by contract tests.
- **New release key identity warning**: releases from this hardened pipeline
  are signed with a **new key identity**. Android will not overwrite-update an
  installed application signed by a different key — uninstall any existing
  DFRoot GZIF build before installing a signed release APK (**unsigned CI
  Release artifacts** from PR/branch/manual builds cannot update a signed
  install either).
- **Make/DDK hardening** (`Makefile`, `scripts/ddk-image.sh`): exactly one
  digest-pinned `android13-5.15` DDK image for the single embedded KMI, derived
  from CI instead of a floating tag; Podman uses `--network=none`,
  `--cap-drop=ALL` and `no-new-privileges` with only `lkm/` and the KO directory
  mounted. `SIGN_PASS`/`SIGN_ALIAS` are accepted **only from the environment**;
  command-line assignments are rejected at parse time. CI strips
  `.note.gnu.build-id` from the KO, so the CI/release artifact is 13,384 B with
  sha256 `9dfb8b71b306f5a38fd8b35b283d4dab8b4eb25a08ad97ca0f4ef705b6151c68`,
  while the local `make` build is 13,504 B with sha256 `94f8be98…` (it retains
  the build-id note); they are not byte-identical.
- **GPLv3 licence conveyance**: added verbatim `GPL-3.0.txt` from
  https://www.gnu.org/licenses/gpl-3.0.txt; `NOTICE` and
  `THIRD_PARTY_NOTICES.md` now state the full GPL-3.0-or-later obligations for
  the bundled userspace loaders (correcting the "no other obligations"
  overstatement) while retaining the provenance and complete-source offer.
  Both `GPL-3.0.txt` and the repository's own GPL-2.0 `LICENSE`, plus `NOTICE`
  and `THIRD_PARTY_NOTICES.md`, accompany the signed release assets **and** the
  publicly downloadable unsigned CI APK artifact, with the upload and publish
  allowlists tightened to those exact files.
- Documentation corrections in `README.md`, `PORTING-GZIF.md`, `NOTICE` and
  `THIRD_PARTY_NOTICES.md`: 9.9.2 / 9.5.0-effective, signing/debug behavior,
  CI KO hash, digest-pinned DDK image, environment-only Make credentials,
  single-KMI Make, and the CI event matrix. No AndroidX / Material Components
  dependency (the themes use the framework `android:Theme.Material*`), four KMI
  modules embedded in `libksudnext.so` with GZIF using `android13-5.15`.
  Historical hardware-validation evidence in `PORTING-GZIF.md` is retained.
- Regression coverage: workflow policy/contract tests, make contract tests,
  signing material/permission tests and signed/unsigned release smoke tests all
  pass; new tests assert that the license and notice assets are packaged,
  uploaded and published — including a byte pin for `LICENSE` and
  equality-locked upload/publish allowlists — while still rejecting `secrets/`,
  `.jks`, `signing.properties` and `..` artifact entries.

### Cross-PR integration note

PRs #2–#5 were based on `38eebc3`; before merge they must rebase onto this
hardened head and refresh every shared file they touch. In particular:

- PR #5 changes the LKM bytes, so PR #3's `KO_EXPECTED_SHA256S` and every KO
  hash/size quoted in the docs must be rebuilt and re-pinned against the new
  artifact.
- PR #4 must update its Gradle reference from **9.7.1** to **9.9.2** and refresh
  the referenced PR #1 head to the current hardened commit.

Both licence texts, `NOTICE` and `THIRD_PARTY_NOTICES.md` accompany the signed
release assets **and** the publicly downloadable unsigned CI APK artifact: the
repository is public, so Actions artifacts are not private, and the unsigned
APKs are treated as conveyance with the same notices and source offer rather
than as "internal, not third-party" outputs. The signed release bundle is not
the only unit of conveyance.
## 2026-11-25 — PR #2 correctness/safety repair, Phases 0–5 (uncommitted)
=======
## 2026-11-25 — PR #2 correctness/safety repair, Phases 0–5 (locally committed, not yet pushed)
>>>>>>> ffe8056 (fix(app): contain close errors and finalize PR #2 documentation)

Architecture repairs on top of `4.8-gzif`; `versionCode`/`versionName` are
unchanged (408 / `4.8-gzif`). No runtime payload, firmware target, or SUCCESS
product decision was widened. Summary by phase:

- **Phase 0 — build/test foundation.** Release signing made optional (an absent
  `signing.properties` yields an unsigned release that still configures; a
  present but incomplete file fails clearly). Pinned JUnit/Robolectric test
  dependencies and Android unit-test resources added. Independent CI app-checks
  job runs unit tests and lint without secrets, a device, or the gitignored KO;
  `--no-tests=error` prevents an empty suite from passing. `app/src/main/res/xml`
  added to the Makefile APK inputs. Loader SHA-256 pins are owned once by Gradle.
- **Phase 1 — side-effect-free preflight and result contract.** Canonical
  `SuManager` allowlist (`me.weishu.kernelsu` → `libksud.so`,
  `com.rifsxd.ksunext` → `libksudnext.so`) with per-manager pinned bundled
  loaders and **no implicit fallback** to the manager APK. SHA-256 pin, regular
  non-symlink, owner-user, log-buffer, and exact firmware/kernel gates all run
  before staging, IpSec allocation, or process start. Typed `RunResult` mapping
  with collision-free unknown handling; rc=1 is broad bootstrap failure; SUCCESS
  keeps owner semantics but carries `LATE_LOAD_COMPLETED` internal detail. No
  root probe was added.
- **Phase 2 — durable configuration and soft-reboot consent.** Serialized
  off-main-thread `ConfigurationRepository` with checked `commit()` and
  saving/verified/error states; failed or unverified writes block run admission.
  Immutable `RunConfig`; versioned soft-reboot acknowledgement with legacy
  enabled → safe OFF migration and a durable modal; unattended boot soft reboot
  remains disabled.
- **Phase 3 — operation ownership.** Process-scoped single-flight `RunCoordinator`
  rejects duplicates and freezes configuration; Direct Boot-aware `specialUse`
  `ExploitService` owns process lifetime, notification, and a timed partial
  WakeLock and returns `START_NOT_STICKY`. The Activity is detach-only on destroy;
  a supervised child drains stdout asynchronously, uses a timed wait, and keeps
  indeterminate outcomes owned until actual exit (no forced kill, no retry).
  Manager/settings controls are locked while work is active.
- **Phase 4 — native snapshot, completion, staging.** Java publishes an atomic,
  versioned `run_config` artifact; native bootstrap parses only that
  artifact. Run-scoped terminal markers (`rc=0` success, `rc=1` error,
  `rc=4` soft-reboot success, `rc=6` soft-reboot failure) are written only after
  bootstrap work completes; `/dev/dfm6` stays an owner-visible **progress**
  diagnostic. Late-load wait is bounded (60 s; timeout → rc=1), soft-reboot
  command wait is bounded (30 s; failure/timeout → rc=6). `/data/local/tmp/.ksud-stage`
  staging uses sticky-bit protection, `O_EXCL|O_NOFOLLOW`, verified descriptor
  copy/fsync/fchmod, and guaranteed directory-mode restoration.
- **Phase 5 — boot integration.** `BootReceiver` validates the broadcast and
  durably claims `Settings.Global.BOOT_COUNT` in device-protected storage before
  starting the foreground service; locked/unlocked broadcasts coalesce, so at
  most **one attempt per boot** and no silent replay after process death. Boot
  soft reboot stays disabled; boot FGS eligibility is not hardware-validated.

Files/areas changed: `app/src/main/java/df/root/*` (new coordinator, service,
configuration repository/store, run-config artifact, preflight, result mapping,
boot-claim path, narrow Java seams), `app/src/main/jni/*` (`run_config_codec`,
`run_status`, `loader_stage`, `bootstrap.c`, `exp.c`, CMake wiring),
`app/src/main/jni/host-tests/*`, `app/src/test/java/df/root/*`,
`app/src/main/AndroidManifest.xml`, `app/src/main/res/{xml,values,drawable}/*`,
`app/build.gradle.kts`, `.github/workflows/build.yml`, `Makefile`, and
`tasks/{plan,todo}.md`. Every modified GPL-covered source file carries a dated
per-file notice (`NOTICE`).

Validation (2026-11-25, non-privileged host only): **409 JVM tests** (0
failures/errors/skips), **113 host CTest** cases (0 failures; 111 base cases plus
2 cleanup fixtures registered only when the mode probes fail and the fixture
roots are relocated — 113 is the count in this environment), `lintDebug` **0
errors** (21 warnings present before the Phase 6 docs-only edits, one a
`PrivateApi` warning at `AndroidRunEnvironment.java:93` in a file added by PR #2
— not all 21 predate PR #2), `git diff --check` clean, loader pins match
the bundled binaries, zero secret-pattern hits, and no generated/build/KO files
tracked. **No hardware, device, ADB, payload, privileged-container, cold-boot,
soft-reboot, or APK-assembly validation was performed for this revision.** APK
assembly still requires the gitignored `app/src/main/jni/ko/dfroot-android13-5.15.ko`
(built via podman + DDK or fetched from CI). The 2026-11-17 device runs below are
historical evidence only, not validation of this architecture.

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
