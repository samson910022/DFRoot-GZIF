<p align="center">
  <img src=".github/icon.png" width="96" />
</p>

# DFRoot [DirtyFrag (CVE-2026-43284)]

DFRoot is an Android rooting tool that exploits CVE-2026-43284 (DirtyFrag) — a kernel page cache write primitive — to load a custom kernel module without requiring an unlocked bootloader. It is intentionally designed to be SU Manager agnostic and works with any KernelSU-compatible SU manager.

> [!IMPORTANT]
> Before hitting that fork button, consider making a pull request instead :)

## DFRoot GZIF — Samsung Galaxy S23 Ultra (SM-S9180 / S9180ZHU8GZIF)

This fork ports the DFRoot chain to the S23 Ultra (`dm3q`) build
**`S9180ZHU8GZIF`** (Android 17 / One UI 9.0 / SPL 2026-10-05 /
kernel `5.15.197-android13-8-34343818-abS9180ZHU8GZIF`). It was
hardware-validated on 2026-11-17 against the **historical** implementation only;
the current head is host-tested and UNVALIDATED on device (boundary below).

- **Validated 2026-11-17 (historical loaders only)** (volatile, late-load root only):
  - KernelSU **3.3.0** Samsung-patched classic loader → full chain; `su -c id` → `uid=0(root) … u:r:ksu:s0` (run6, 16:49)
  - KernelSU-Next **3.4.2** Samsung-patched generic loader → full chain; `su -v` → `3.4.2:KernelSU` (run8, 17:53)
- **Vanilla (unpatched) KernelSU / KernelSU-Next modules panic inside `init_module()` on this build** — do not substitute them. This fork bundles Samsung-patched loaders under `app/src/main/jniLibs/` (provenance + sha256: `NOTICE`).
- Root is **not boot-persistent** (late-load); re-run LAUNCH ROOT after every reboot. No `boot.img` flash is performed.
- **Only `S9180ZHU8GZIF` is tested.** No other build/model/One UI version is claimed. Not registered in any update feed.
- The fork renames the app label to **DFRoot GZIF** (`versionCode` 408, `versionName` `4.8-gzif`). `applicationId` remains `df.root` by design (bootstrap staging depends on it).
- Provenance, per-file change notices, licence split, and the source offer for the bundled loaders: `NOTICE`, `THIRD_PARTY_NOTICES.md`, `CHANGELOG.md`. Build/port notes: `PORTING-GZIF.md`.

> [!WARNING]
> **Validation boundary.** run6/run8 happened on 2026-11-17 against the
> **historical implementation** (old multi-marker protocol, then-current KO
> pins, old preference path); no hardware run validates the rebased head of
> `fix/native-exploit-robustness`. That head is **host-tested only and
> UNVALIDATED on device**; a successful build does not certify safe privileged
> execution.
> An **unconditional** `host-gates` job runs **all** host suites as required
> gates on **every** event, including protected `v*` tag pushes, with a
> checkout-SHA assertion; both `build-dfroot` and `build-release` require it,
> and `publish` requires `build-release`. Non-tag events upload **no APK**: the
> only upload is the `DFRoot-CI-evidence` bundle (`GPL-3.0.txt`, `LICENSE`,
> `NOTICE`, `THIRD_PARTY_NOTICES.md`). The Debug APK is a compile/content gate
> only. Only a `v*` tag push takes the protected `build-release` path, where the
> signed APK is `apksigner`-verified and checksummed, then every distributable
> APK is packaged-byte-verified with its own manifest and `so_sha256` binding,
> every provenance record gets a required identity check, and release evidence
> is asserted at a single pre-upload choke point before `publish` can run.
> Provenance manifests are informational evidence, **not** an attestation or
> an independent reproducible-build proof.
> Host suites: **61** status + 13 proc-scan + 26 ELF + 16/14/10 device-version
> cases, CMake **23/25/3/3/6/5** cases (KO policy / packaged-KO / APK packaging
> / APK content / target graph / loader hash), JVM **39 tests** (4/10/12/13 in LaunchGate /
> ListenerLifecycle / PreferenceIntegrity / PreferencePersister) with a strict
> **39/0/0/0** XML assertion, **7/7** host suite markers on every event and
> **3/3** release markers (plus the nonempty gate index and single upload choke
> point) on the tag path. Workflow policy: **85 tests / 202 subtests**.
> No required gate uses `continue-on-error`.
> **KO pins:** the byte gate rejects unknown bytes but still accepts the two
> known obsolete pins (`94f8be…` / `9dfb8b…`, 13504 / 13384 B). Release is
> withheld by **policy** — a reviewed re-pin, the OPEN P1-08 staging gate and
> device validation — not by the hash gate alone; passing the gate is not device
> validation. Gradle passes the Android variant (`release` / `debug`) as the
> authoritative native policy signal, and the KO is linked into a dedicated
> `.dfroot_ko` section whose final bytes in `libdfroot.so` are SHA-256-checked
> against the same pins.
> The app uses a single atomically published nonce-bound `/dev/dffinal` outcome
> (truthful rc 0–9 plus the additive 64 recovery bit), exactly-once cleanup on
> every exit, and separate app-owned `dfbootfail` / `dfrecover` channels:
> `dfrecover` is bound to the current run nonce, so a stale or foreign `ok` is
> rejected: token syntax is not invocation proof, and absence of evidence is
> not proof that system activity stopped. Deadline checks are host-proven
> ordering only; device quiescence and verified restoration are UNVALIDATED.
> Preference transactions use a durable write-ahead marker, a sticky
> fail-closed `WILDCARD`, launch tokens and a whole-transaction lock; no
> success is reported for a failed integrity transaction. Boot component state
> is tri-state and now shows a **persistent `UNKNOWN` status row** plus an
> explicit safe disable action; `UNKNOWN` is never shown as verified `OFF` and
> never used to accept `OFF`, and nothing auto-enables. Real UI lifecycle and
> PackageManager behavior are UNVALIDATED. All host-proven only. Auto Soft
> Reboot and Start on Boot remain disabled by default and unvalidated.
> **P1-08 remains OPEN:** the previously bundled loaders consumed a
> `shell`-writable `/data/local/tmp/.ksud-stage`. Both loaders have now been
> rebuilt from the P1-08-patched source to consume a root-owned
> `/data/adb/.ksud-stage` (fd-validated before promotion), and the producer at
> `2b59684` stages to that exact path; the rebuilt bytes are pinned by sha256
> and size at build time. This was a loader-side fix — DFRoot-side changes
> alone could not close it. It remains **OPEN pending re-review and fresh
> on-device full-chain validation**: documentation is not a fix and unverified
> privileged content must not be consumed or distributed. run6/run8 are
> historical and validate only the old loaders. See `CHANGELOG.md` round 8
> (2026-11-26) and `PORTING-GZIF.md` §19 for the test/lifecycle detail.

> [!NOTE]
> **Post-rebase integration.** PR #1 (CI build reliability) is now merged into
> the base and PR #3 was rebased onto that head. run6/run8 and all earlier CI
> runs predate the rebase: they are historical and do not validate the rebased
> head. No tag was created by us.

## FAQ

**Q: Log says patching failed and that my device isn't vulnerable**

A: Sorry, there is no fix. Either:
  - Your device's kernel is too new
  - Your device manufacturer backported the official mitigation
  - [Accidental mitigation](https://github.com/V4bel/dirtyfrag/issues/23#issuecomment-4405314290) (typically seen on kernel 6.1)

**Q: Log says "SUCCESS" or "ksud exited with error", but I don't have root**

A: First, try disabling "Auto Soft Reboot" in DFRoot settings if you have it enabled, otherwise https://github.com/diabl0w/DFRoot/discussions/69

**Q: I installed a module and now I can't launch root without crashing**

A: Toggle "Disable KSU Modules" in DFRoot settings

**Q: I installed a module and have "Start at Boot" enabled and now I am bootlooping**

A: Typically Android will detect bootloops and boot you into Safe Mode, otherwise you can usually hold the Volume Down button during boot to enter safe mode. From there, disable "Start on Boot" and enable "Disable KSU Modules" in DFRoot settings or just uninstall DFRoot until you stabilize.

## Usage

> [!WARNING]
> I am not responsible for any damage to your device.

1. Install [DFRoot](https://github.com/diabl0w/DFRoot/releases/latest)
2. Install a SU Manager from below:
   - Samsung:
     - [diabl0w's KernelSU](https://github.com/diabl0w/KernelSU/releases/latest)
   - Other:
     - [KernelSU](https://github.com/tiann/KernelSU/releases/latest)
     - [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next/releases/latest)
     - [KowSU](https://github.com/KOWX712/KernelSU/releases/latest)

> If you have a custom KernelSU fork for a specific manufacturer, please make a pull request to add it here.

## Features

- Start on Boot
- Automatic soft reboot (if `late-load --soft-reboot` supported by your SU Manager)
- RO Partition Protection
- Disable all KernelSU modules, to recover from a broken one
- Shizuku not needed — regain root without WiFi!

## How it works

The Android kernel decrypts AES-CBC ESP packets directly into the page cache of files open for `splice()`. By crafting `IV = AES_ECB_DEC(key, current_content) ⊕ desired_content`, any 16-byte-aligned block in a mapped shared library can be overwritten without write permission and without copy-on-write.

The exploit uses this primitive to patch shellcode into `libc++.so` in the kernel's page cache. The next privileged call to the hooked function runs the shellcode, which loads our custom kernel module via `insmod`.

### Exploit chain

1. **IpSec transform** — App allocates a `UdpEncapsulationSocket` + SPI and builds an AES-CBC/HMAC-SHA256 ESP transform via `IpSecManager`.

2. **splicehelper → crash_dump64** — The splicehelper binary is spliced into `crash_dump64` via the CBC primitive. `crash_dump64` runs in the `crash_dump` SELinux domain (via exec label transition), which can open `vendor_file` labeled files (untrusted_app context cannot read these files so we need this bridge). The splicehelper serves two modes: splice mode (pipe a 16-byte page chunk out to the parent for write) and read mode (`argv[3]="r"`, write 16 bytes of file content to a pipe fd for IV computation).

3. **dfroot.ko → vendor_file** — The kernel module is written via the crash_dump bridge (splicehelper splice mode) into a `vendor_file`-labeled file.

4. **libc++ hook** (fires in init, uid=0, tid=1) — Shellcode is patched into `libc++.so` at `std::ostream::sentry::sentry()` (`_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentryC1ERS3_`). Triggered by: `createOrphanProcess()` double-forks so the grandchild is adopted by PID 1 (init); when init reaps the orphan its main thread (tid=1) calls through the hooked function. The shellcode:
   - Checks `getuid()==0` and `gettid()==1`; returns immediately otherwise
   - Creates `/dev/df` as a one-shot mutex (O_CREAT|O_EXCL) to prevent re-entry
   - Clones a worker child (parent returns to init immediately)
   - Worker forks a grandchild; grandchild writes `u:r:vendor_modprobe:s0` to `/proc/self/attr/exec` then execs `/vendor/bin/insmod <ko_target>`

5. **dfroot.ko init** (runs as `vendor_modprobe`, uid=0) — The KO is loaded by `insmod` in the `vendor_modprobe` SELinux domain:
   - Writes `false` to `selinux_state` (global permissive)
   - Bypasses DEFEX via kprobes (if applicable)
   - Calls `call_usermodehelper` to run our custom bootstrap code

6. **bootstrap.c** — performs various functions and finally launches the su daemon from your installed SU Manager.

## Building

```sh
make
```

The top-level `make` performs **one** DDK build for the single KMI this fork
ships (`android13-5.15`). It uses the exact digest-pinned image that CI uses,
read from `.github/workflows/build.yml` by `scripts/ddk-image.sh` (no floating
tags, no caller-supplied image). Podman runs it with `--network=none`,
`--cap-drop=ALL` and `no-new-privileges`, mounting only `lkm/` and the KO output
directory. `SIGN_PASS` and `SIGN_ALIAS` must be **exported in the environment**;
the Makefile rejects them as `make` command-line assignments (which would leak
into shell history, process listings and echoed output).

Toolchain: Gradle wrapper **9.9.2** (distribution SHA-256 pinned in
`gradle/wrapper/gradle-wrapper.properties`), AGP **9.5.0** declared once in
`gradle/libs.versions.toml` and consumed through the version-catalog alias
(there is no root `build.gradle.kts` AGP version pin), JDK 21 (toolchain-pinned),
SDK 36, NDK r28c (`28.2.13676358`), CMake 3.22.1.

### Building this fork (DFRoot GZIF)

The LKM binary is **not** committed (`lkm/dfroot.ko` is gitignored, and so is
`app/src/main/jni/ko/*.ko`). Before `assembleRelease` you must either:

- build it with the top-level `make` (one podman build, digest-pinned
  `android13-5.15` DDK image — long), or
- build only the `android13-5.15` LKM (`cd lkm && make …`, then copy/symlink
  the result to `app/src/main/jni/ko/dfroot-android13-5.15.ko`) — sufficient
  for GZIF, or
- fetch the CI-built `dfroot-android13-5.15.ko` from the
  `df-lkm-android13-5.15` artifact of `.github/workflows/build.yml`; its
  sha256 is
  `9dfb8b71b306f5a38fd8b35b283d4dab8b4eb25a08ad97ca0f4ef705b6151c68`.
  Place it at `app/src/main/jni/ko/dfroot-android13-5.15.ko`.

The CI/release artifact is 13,384 B and is **not byte-identical** to the locally
`make`-built KO (13,504 B, sha256 `94f8be98…`): CI strips `.note.gnu.build-id`
(and other notes/sections) with `llvm-objcopy --strip-unneeded`, so the size,
bytes and hash differ even though both are built from the same source. The full
hash above identifies the CI/release artifact.

Then: `./gradlew :app:assembleRelease` →
`app/build/outputs/apk/release/DFRoot_4.8-gzif.apk` →
`adb install -r` → enable adb → open **DFRoot GZIF** → LAUNCH ROOT.

The native build requires `CMAKE_OBJCOPY` (configure fails without it): a
POST_BUILD step dumps the dedicated `.dfroot_ko` section from `libdfroot.so`
and verifies the **packaged bytes** against the same KO pins and expected
sizes. There is no skip option for this gate. Tag CI repeats it against the
APK's `lib/arm64-v8a/libdfroot.so` before publishing. The gate rejects unknown
bytes but **accepts the two known obsolete pins**: a release is withheld by
policy (reviewed re-pin, OPEN P1-08 and device validation), not by the hash
gate alone; passing it is not device validation (see `CHANGELOG.md`).

### Signing variants and CI events

- **Debug** is always signed with the standard Android debug keystore
  (`signingConfigs.getByName("debug")`), independently of `signing.properties`,
  and is installable for development.
- **Release** requires a gitignored root `signing.properties` with
  `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` (or the
  top-level `make`, which generates a local keystore from the exported
  `SIGN_PASS` / `SIGN_ALIAS`). Without complete, real signing material the
  release variant and every release task are **disabled/fail closed**; a
  harmless placeholder keeps configuration, `assembleDebug` and unit tests
  working, and no unsigned or dummy-signed release APK is emitted. **Do not
  commit** `signing.properties`.
- **CI event matrix** (`.github/workflows/build.yml`): an unconditional
  `host-gates` job (`needs: build-lkms`, no `if`, checkout-SHA assertion) runs
  all native/CMake/JVM suites, the real KO CI policy and the 7/7 marker gate on
  **every** event including protected `v*` tag pushes. Pull requests, pushes to
  `master`/`main` and manual `workflow_dispatch` runs then build a
  debug-keystore-signed Debug APK strictly as a compile/content gate with **no
  secrets**, and upload **no APK** — only the `DFRoot-CI-evidence` bundle with
  `GPL-3.0.txt`, `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md`. No signed
  APK, no signing secrets, no GitHub Release and no release claim. Only a push
  of a `v*` tag runs `build-release` in the protected `release` environment
  (also requiring `host-gates`), where the four `KEYSTORE*` secrets exist: the
  signed APK is `apksigner`-verified, checksummed, packaged-byte- and
  provenance-evidence-gated before upload, then `publish` (requiring
  `build-release`) emits the GitHub Release with the same licence/notice
  bundle. The signing smoke uses an isolated identity
  (`df.root.signingsmoke` / `4.8-gzif-signingsmoke` /
  `SigningSmoke-4.8-gzif-signingsmoke.apk`), rejects `DFRoot_*.apk` and emits no
  distributable artifact.
- The `KEYSTORE` value — the protected CI secret, or the environment value read
  by `WriteSigningProperties.java ci` locally — must be a **base64-encoded JKS**
  (produce it with `base64 -w0 keystore.jks`); it is decoded and the JKS
  validated before any signing material is published. A raw JKS is rejected.

> [!WARNING]
> **The release signing key is new.** Android refuses to overwrite-update an
> installed application with an APK signed by a different key: uninstall any
> existing DFRoot GZIF installation before installing a signed release build.
> No APK is distributed from non-tag CI; the signing-smoke APK uses an isolated
> identity and is never a distributable artifact.

## Credits

- Original PoC and various code: https://github.com/lsposed/lspromise
- SELinux Permissive kernel modules and various code: https://github.com/polygraphene/DFReroot
- Unprivileged XFRM socket method: https://github.com/combeng6th/DirtyInit
