<p align="center">
  <img src=".github/icon.png" width="96" />
</p>

# DFRoot [DirtyFrag (CVE-2026-43284)]

DFRoot is an Android rooting tool that exploits CVE-2026-43284 (DirtyFrag) — a kernel page cache write primitive — to load a custom kernel module without requiring an unlocked bootloader. It is intentionally designed to be SU Manager agnostic and works with any KernelSU-compatible SU manager.

> [!IMPORTANT]
> Before hitting that fork button, consider making a pull request instead :)

## DFRoot GZIF — Samsung Galaxy S23 Ultra (SM-S9180 / S9180ZHU8GZIF)

This fork adds hardware-validated support for the S23 Ultra (`dm3q`) build
**`S9180ZHU8GZIF`** (Android 17 / One UI 9.0 / SPL 2026-10-05 /
kernel `5.15.197-android13-8-34343818-abS9180ZHU8GZIF`).

- **Validated 2026-11-17** (volatile, late-load root only):
  - KernelSU **3.3.0** Samsung-patched classic loader → full chain; `su -c id` → `uid=0(root) … u:r:ksu:s0` (run6, 16:49)
  - KernelSU-Next **3.4.2** Samsung-patched generic loader → full chain; `su -v` → `3.4.2:KernelSU` (run8, 17:53)
- **Vanilla (unpatched) KernelSU / KernelSU-Next modules panic inside `init_module()` on this build** — do not substitute them. This fork bundles Samsung-patched loaders under `app/src/main/jniLibs/` (provenance + sha256: `NOTICE`).
- Root is **not boot-persistent** (late-load); re-run LAUNCH ROOT after every reboot. No `boot.img` flash is performed.
- **Only `S9180ZHU8GZIF` is tested.** No other build/model/One UI version is claimed. Not registered in any update feed.
- The fork renames the app label to **DFRoot GZIF** (`versionCode` 408, `versionName` `4.8-gzif`). `applicationId` remains `df.root` by design (bootstrap staging depends on it).
- Provenance, per-file change notices, licence split, and the source offer for the bundled loaders: `NOTICE`, `THIRD_PARTY_NOTICES.md`, `CHANGELOG.md`. Build/port notes: `PORTING-GZIF.md`.

> [!WARNING]
> **Validation boundary.** run6/run8 happened on 2026-11-17 against the
> **historical implementation** (old multi-marker status protocol and the
> then-current `dfroot.ko` pins). The current round-2 PR #3 tree — single
> `/dev/dffinal` outcome, KO hash-skip policy, build provenance and Java
> preference persistence — is **host-tested only and UNVALIDATED on device**.
> Do not publish a release with obsolete KO pins: if a rebuilt DDK artifact no
> longer matches the two reviewed pins, stop and review/update them in a
> reviewed commit; never skip the check for CI or release builds. Gradle now
> passes the Android variant (`release` / `debug`) as the authoritative native
> policy signal, and the KO is linked into a dedicated `.dfroot_ko` section so
> the **final bytes inside `libdfroot.so`** are SHA-256-checked against the same
> pins before a tag is published. That real packaged-byte gate is **blocked
> until the KO is re-pin-reviewed**; passing the host fixture tests is not
> device validation. Provenance manifests are informational evidence, **not**
> an independent reproducible-build proof or attestation. Auto Soft Reboot and
> Start on Boot stay disabled by default and unvalidated. The residual
> `/data/local/tmp/.ksud-stage` trust issue (P1-08) cannot be closed without
> rebuilding the bundled loaders and is a release gate.
> Round-3 PR #3 phase 3 adds a process-wide SharedPreferences quarantine with a
> durable device-protected marker: a failed preference write (memory/disk
> divergence) blocks LAUNCH ROOT (rc 8) and boot-time launching, and a lingering
> `/dev/df` now returns rc 9 ("already hooked") instead of rc 0. The Java rc
> table (including rc 7, 8, 9 and the additive 64 recovery-failed bit) is
> truthful — no toast claims verified root or a confirmed reboot. If the marker
> cannot be persisted, "Start on Boot" is disabled fail-closed. All of this is
> host-only JVM testing (15 tests), **UNVALIDATED on device**; P1-08 remains
> **OPEN** and documenting it is not a fix. Auto Soft Reboot and Start on Boot
> stay disabled by default and unvalidated.

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

### Building this fork (DFRoot GZIF)

The LKM binary is **not** committed (`lkm/dfroot.ko` is gitignored, and so is
`app/src/main/jni/ko/*.ko`). Before `assembleRelease` you must either:

- build it with the top-level `make` (podman + DDK images; chains all eight
  KMIs — long), or
- build only the `android13-5.15` LKM (`cd lkm && make …`, then copy/symlink
  the result to `app/src/main/jni/ko/dfroot-android13-5.15.ko`) — sufficient
  for GZIF, or
- fetch the prebuilt `dfroot-android13-5.15.ko` (sha256 `94f8be98…`) from CI
  artifacts (`.github/workflows/build.yml`) and place it at
  `app/src/main/jni/ko/dfroot-android13-5.15.ko`.

Then: JDK 21 (toolchain-pinned) · SDK 36 · NDK r28c (`28.2.13676358`) ·
CMake 3.22.1 · `./gradlew :app:assembleRelease` →
`app/build/outputs/apk/release/DFRoot_4.8-gzif.apk` →
`adb install -r` → enable adb → open **DFRoot GZIF** → LAUNCH ROOT.

The native build requires `CMAKE_OBJCOPY` (configure fails without it): a
POST_BUILD step dumps the dedicated `.dfroot_ko` section from `libdfroot.so`
and verifies the **packaged bytes** against the same KO pins and expected
sizes. There is no skip option for this gate. Release/tag CI repeats it
against the APK's `lib/arm64-v8a/libdfroot.so` before publishing, so a tag
is blocked until the reviewed KO is re-pinned (see `CHANGELOG.md`).

Signing: `signing.properties` at the repo root (gitignored) with
`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`.
Do **not** commit it. Local `make` now requires explicit `SIGN_PASS`/`SIGN_ALIAS`
(no default credentials); CI uses the `KEYSTORE` secrets.

## Credits

- Original PoC and various code: https://github.com/lsposed/lspromise
- SELinux Permissive kernel modules and various code: https://github.com/polygraphene/DFReroot
- Unprivileged XFRM socket method: https://github.com/combeng6th/DirtyInit
