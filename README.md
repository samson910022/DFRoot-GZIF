<p align="center">
  <img src=".github/icon.png" width="96" />
</p>

# DFRoot [DirtyFrag (CVE-2026-43284)]

DFRoot is an Android rooting tool that exploits CVE-2026-43284 (DirtyFrag) — a kernel page cache write primitive — to load a custom kernel module without requiring an unlocked bootloader. This fork supports exactly two SU managers (see [Supported managers](#supported-managers)); it is **not** manager-agnostic and does not accept arbitrary KernelSU-compatible managers.

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

## Supported managers

DFRoot GZIF supports exactly two manager packages. Each is bound to one bundled,
SHA-256-pinned loader; there is **no implicit fallback** and no manager-agnostic mode.

| Manager package | Bundled loader (pinned) | Loader lineage |
|---|---|---|
| `me.weishu.kernelsu` (KernelSU) | `libksud.so` (Samsung-patched ksud 3.3.0) | classic KernelSU |
| `com.rifsxd.ksunext` (KernelSU-Next) | `libksudnext.so` (Samsung-patched ksud 3.4.2) | KernelSU-Next |

Both loaders ship under `app/src/main/jniLibs/arm64-v8a/`. Preflight rejects any
other package, a missing/renamed/symlinked loader, or a loader whose SHA-256 does
not match its pin **before** staging, IpSec allocation, or process start. The
manager APK's own `libksud.so` is never used as a loader fallback: the selected
manager only supplies the `--package-name` argument and must be installed and
visible. Adding another manager requires a matching pinned bundled loader plus
device validation; see `PORTING-GZIF.md`.

## Result contract (what "SUCCESS" means)

`/dev/dfm6` remains the owner-visible **late-load SUCCESS progress diagnostic**;
when that legacy marker is observed the app still prints `ksud start: SUCCESS`.
It is progress only. Native returns **only after a run-scoped terminal marker
bound to the admitted operation ID AND its matching publication-confirmation
marker** are observed. An intended final marker on its own is indeterminate and
keeps the reader polling; if the reader deadline expires without a confirmed
marker it returns **rc=7 indeterminate** — never success and never a definitive
failure. A global marker alone can no longer end a run.

| Code | Meaning |
|---|---|
| `0` | normal terminal success: the admitted run published its success marker and a matching confirmation marker |
| `1` | bootstrap/terminal error — run-config, module, staging, or an **observed nonzero late-load exit** (`run_status_late_load_wait_code`); an unobserved exit or timeout is rc=7, not rc=1 |
| `2` | native `setup()` failure before any operation starts (mapped by `NativeResultMapper` to `RESULT_NATIVE_SETUP_FAILED`) |
| `3` | native patch failure |
| `4` | soft-reboot command returned success |
| `6` | soft-reboot command failed |
| `7` | **indeterminate** — completion could not be confirmed: bounded writer wait or reader deadline expiry, unconfirmed/failed terminal publication, contradictory/conflicting terminal markers, or pre-admission uncertainty. Never a success. |

A **marker conflict** is therefore rc=7 indeterminate, never a definitive error
or success: two different terminal observations (confirmed or not) disprove each
other and confirm no completed outcome. An unconfirmed or contradictory
observation also never authorizes another operation — the owner, lease,
configuration freeze, and durable attempt barrier are retained until explicit
recovery. rc=7 itself is authoritative without a confirmation.

A late-load timeout, an unobserved child exit, an unconfirmed publication, and a
reader deadline expiry are all rc=7 indeterminate: privileged children may still
be running, so the app keeps ownership until a trusted caller clears the barrier
(no in-app recovery UI; see the R3.6 section below). **No root probe is added**:
the app does not run `su` or inspect root state to decide a result. Verify root
yourself after each run with the oracle in `PORTING-GZIF.md` §8.3.

**Publication window (acknowledgement protocol).** Terminal publication is
two-phase in the same root: an intended final marker
`dfroot_terminal_<operation_id>_rc<code>`, then a matching confirmation
`dfroot_confirm_<operation_id>_rc<code>` with magic `DFROOT_CONFIRM 1`. Both use
the same checked pipeline (unpredictable same-directory temp, `O_EXCL` /
`O_NOFOLLOW`, regular-file check, full write, `fsync`, `fchmod 0644`, checked
close, atomic `link()`, temp unlink, directory fsync). The writer returns its
intended code **only after both phases are fully published**; a non-7 definitive
code is accepted by the reader **only when a confirmation matching both the
operation ID and the code exists**. An intended marker without its confirmation
yields **no decision** (keep polling) with a "not publication-confirmed;
completion unverified" diagnostic — it is never acted on as success. On **any**
detected publication failure (including a confirmation-step failure), rc7 is
published **before** any final link is retracted, temps are cleaned, and the
writer exits 7; the indeterminate channel needs no confirmation and outranks
success and conflicts. A duplicate/conflicting publication also publishes rc7.

**Residual window — OPEN hardware/OS gate, not proved safe.** `link()` makes the
final pathname visible before temp unlink and directory fsync complete. If the
writer dies abnormally after the confirmation link but before its fsync, an
external reader can still observe a definitive-looking result that was never
durably acknowledged. If it dies between intended-marker publication and
confirmation, the reader sees only an unconfirmed marker and must not decide.
These abnormal-death instants are not closed by host tests; device crash/power-
loss validation of `/dev` marker durability remains required. The safety policy
is unchanged: rc7 precedence and indeterminate ownership still apply.

## Operation ownership, boot, and soft reboot

- A process-scoped `RunCoordinator` owns one privileged operation at a time and rejects duplicates. Destroying the Activity only detaches observation; it never cancels or interrupts a started run. Each UI request carries a click-time ID: the coordinator publishes `ACCEPTED`, `REJECTED` or `CANCELLED` for that ID, and the UI clears its pending state only on a matching outcome (never on an unrelated snapshot). `ExploitService` is a Direct Boot-aware `specialUse` foreground service that supplies process lifetime and a timed partial WakeLock for both manual and boot runs, and returns `START_NOT_STICKY` so indeterminate work is never silently replayed after process death. Service destruction publishes `CANCELLED` **only** for unclaimed service-local work and never replays it; accepted work stays coordinator-owned.
- **Start on Boot** is claim-gated: `BootReceiver` validates the broadcast and durably claims the current `Settings.Global.BOOT_COUNT` in device-protected storage before starting the service. `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` share the claim, so there is **one attempt per boot**; if the boot count is unavailable or the claim cannot be persisted, unattended execution is aborted. The claim re-checks the receiver component at claim time and fails closed on an unreadable/default state. That queued-request recheck is defense in depth only: the **authoritative final gate** is checked inside `RunCoordinator.submit()` under the same lock as the durable attempt publish, immediately before that publish, with another recheck before the engine starts. OFF (or an unreadable setting) therefore suppresses an unattended operation even if the toggle races a blocked publish, initialization or delayed worker, and the checked barrier clear remains allowed for a proven pre-effect rejection. An OFF toggle **after** `engine.run()` has started is **not** revoked — the coordinator keeps ownership of privileged work instead of cancelling it. Foreground-service start eligibility from boot still requires a hardware/OS gate, not a code claim.
- **Start at Boot can be turned OFF independently in every state** — including configuration ERROR and active/indeterminate operations — without touching the current operation's admission, lease, or finish semantics. Turning it ON still requires verified configuration, an idle coordinator, and consent; a rejected component change is surfaced in the UI with the state actually applied.
- **An unconfirmed attempt is a durable admission barrier.** It is persisted in device-protected storage before any privileged work or `run_config` publication. A new process loads it and rejects both manual and boot requests fail-closed until a trusted caller clears the barrier, regardless of boot count. On indeterminate results `RunCoordinator` keeps the lease, the frozen configuration, and single-flight rejection; recovery is a single-claim, trusted-caller API with **no in-app UI** — an ordinary user is intentionally locked out until the barrier is cleared or app data is reset.
- **Unattended soft reboot remains disabled**, even with current manual acknowledgement. **Manual soft reboot is experimental and unvalidated** on GZIF; it has previously caused a full hard reboot on this model. Root is volatile late-load: re-run LAUNCH ROOT after every reboot; no `boot.img` is flashed, and no root probe is added.

## FAQ

**Q: Log says patching failed and that my device isn't vulnerable**

A: Sorry, there is no fix. Either:
  - Your device's kernel is too new
  - Your device manufacturer backported the official mitigation
  - [Accidental mitigation](https://github.com/V4bel/dirtyfrag/issues/23#issuecomment-4405314290) (typically seen on kernel 6.1)

**Q: Log says "SUCCESS" or "ksud exited with error", but I don't have root**

A: `/dev/dfm6` / `ksud start: SUCCESS` is a progress diagnostic, not proof of root — the final result now waits for a run-scoped terminal marker, so an early SUCCESS no longer ends the run by itself. Check the final app result and verify with `su -c id` / `su -v` (`PORTING-GZIF.md` §8.3). If "Auto Soft Reboot" is enabled, disable it: manual soft reboot is experimental and unvalidated on GZIF and has caused a full hard reboot here (https://github.com/diabl0w/DFRoot/discussions/69; `PORTING-GZIF.md` §9.6).

**Q: I installed a module and now I can't launch root without crashing**

A: Toggle "Disable KSU Modules" in DFRoot settings

**Q: I installed a module and have "Start at Boot" enabled and now I am bootlooping**

A: Typically Android will detect bootloops and boot you into Safe Mode, otherwise you can usually hold the Volume Down button during boot to enter safe mode. From there, disable "Start on Boot" and enable "Disable KSU Modules" in DFRoot settings or just uninstall DFRoot until you stabilize.

## Usage

> [!WARNING]
> I am not responsible for any damage to your device.

1. Install [DFRoot](https://github.com/diabl0w/DFRoot/releases/latest)
2. Install **one** of the two supported SU managers:
   - [KernelSU](https://github.com/tiann/KernelSU/releases/latest) — package `me.weishu.kernelsu` (uses bundled `libksud.so`)
   - [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next/releases/latest) — package `com.rifsxd.ksunext` (uses bundled `libksudnext.so`)

> Other managers (including KowSU and unlisted forks) are **not supported** and are rejected by preflight. Adding a manager requires a matching pinned bundled loader and device validation — it is not a spinner-list change.

## Features

- Start on Boot (one attempt per boot via a durable `BOOT_COUNT` claim; boot foreground-service eligibility still needs hardware validation)
- Soft reboot (manual only; experimental and unvalidated on GZIF — unattended boot soft reboot is disabled)
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

6. **bootstrap.c** — reads the atomic run-config artifact, performs the setup/hardened staging, and launches the bundled Samsung-patched loader for the selected manager (not the manager APK's own `libksud.so`) via `late-load`; it then publishes a run-scoped terminal result. See [Supported managers](#supported-managers) and [Result contract](#result-contract-what-success-means).

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

- build it with the top-level `make` (podman + the digest-pinned `android13-5.15`
  DDK image — long), or
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
APK assembly requires the gitignored `app/src/main/jni/ko/dfroot-android13-5.15.ko`;
no KO is committed, and assembling without it would require a privileged DDK
container.

### Signing variants and CI events

- **Debug** is always signed with the standard Android debug keystore
  (`signingConfigs.getByName("debug")`), independently of `signing.properties`,
  and is installable for development.
- **Release** is **unsigned** when `signing.properties` is absent: Gradle only
  warns at configuration time and the build succeeds. An unsigned release APK
  is not a production artifact — Android will not install it over an installed
  signed build. To produce an installable signed Release, add a gitignored root
  `signing.properties` with `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` /
  `KEY_ALIAS` / `KEY_PASSWORD` (or use the top-level `make`, which generates a
  local keystore from the exported `SIGN_PASS` / `SIGN_ALIAS`). **Do not commit
  it.** Incomplete properties fail closed with a readable configuration error.
- **CI event matrix** (`.github/workflows/build.yml`): pull requests, pushes to
  `master`/`main`, and manual `workflow_dispatch` runs build a
  **debug-keystore-signed Debug + unsigned Release** pair of APKs and run all
  fast policy/contract tests **with no secrets available** — there is no release
  signing on those events. Only a **push of a `v*` tag** runs `build-release` in
  the protected `release` environment, where the `KEYSTORE*` secrets exist,
  builds and verifies a **signed Release**, and publishes it (with
  `GPL-3.0.txt`, `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md`) as a GitHub
  Release. Branch/PR builds never see secrets and never publish a GitHub Release.
  Branch/PR/manual unsigned APKs are nevertheless public Actions artifacts (the
  repository is public): the `DFRoot` artifact also carries `GPL-3.0.txt`,
  `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md` alongside the APKs.
- The `KEYSTORE` value — the protected CI secret, or the environment value read
  by `WriteSigningProperties.java ci` locally — must be a **base64-encoded JKS**
  (produce it with `base64 -w0 keystore.jks`). It is decoded and the resulting
  JKS validated before any signing material is published; a raw JKS is rejected.

> [!WARNING]
> The release signing key is new. Android refuses to overwrite-update an
> installed application with an APK signed by a different key: uninstall any
> existing DFRoot GZIF installation before installing a signed release build.
Signing is **optional**: if `signing.properties` exists at the repo root it must
define `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`;
if it is absent, release builds are unsigned and still configure. The file is
gitignored — do **not** commit it. Local `make` requires explicit `SIGN_PASS`/
`SIGN_ALIAS` (no default credentials); CI uses the `KEYSTORE` secrets.

## Third repair round — R3 consolidated summary (2026-11-25)

This is the authoritative record of the **third repair round** on
`fix/app-correctness-safety` (final local HEAD `1252b3d`). It follows the
Re-review 1 round recorded below and a mid-round rebase onto merged **PR #1**
(`origin/master` `9f60e16`). The rebase/CI integration is recorded by `f6faf75`
(preserved the PR #1 release/signing/policy jobs, extended the **closed**
workflow allowlists for app-checks, loader-pin fixtures and packaged-pin
verification, pinned all actions, and moved the unit-test report check out of a
forbidden heredoc) and `770d07c` (restored the JUnit/Robolectric test-only
dependencies dropped by the PR #1 app build script and included `buildSrc` in
the hermetic signing fixture so packaged-loader task definitions resolve).
**All sections below this one are historical**; the counts, finding status and
gates in this section are current.

| Finding | R3 commit | What was closed (host evidence only) |
|---|---|---|
| R2-12 | `6ebf9e3` | Unverified observations no longer masquerade as success: terminal classifier failures map to rc=7, one authoritative unverified-outcome predicate prevents timeout/interruption flags from hiding an unknown exit, and `RunCoordinator` retains ownership and the durable attempt on every unverified result or engine exception. |
| R2-13 | `2a74dd0` | Uncertain persisted attempt records fail closed: incomplete/corrupt records are a barrier, not absence; a store is poisoned after a failed checked commit so its process-local cache cannot prove durable absence until a post-restart store re-reads durable state. |
| R2-14 | `6012c21` | The post-link publication window is documented as provable, not stronger: pre-link failures leave no final marker; once rc=7 is published it outranks success/conflicts; only results acted on **after publication completes** are indeterminate. A deterministic concurrent-reader host test proves an early confirmed observation is possible and cannot be revoked. |
| R2-14 (R4.3) | — | Narrowed in code, not proved safe: a definitive non-7 code requires a matching `dfroot_confirm_` marker; an unconfirmed intended marker yields no decision; any detected publication failure publishes rc7 **before** retraction and the writer exits 7. The early-observation test is fix verification, with three new confirmation-protocol cases. The abnormal-death instant (death after the confirmation link but before its fsync, or between the two phases) remains an OPEN hardware/OS gate — no claim of proved safety. |
| R2-16 / R2-17 | `b87318f` | Queued unattended requests re-read the Start-at-Boot component setting at admission and fail closed on OFF/unreadable without touching an admitted operation; every rejected submission publishes a fresh rejection snapshot so the Activity leaves its pending state and shows the real barrier reason. |
| R2-16 (R4.4) | — | Closed the remaining pending-admission window: the authoritative authorization gate sits inside `submit()` immediately before the durable publish (fail closed when unreadable), with a second recheck immediately before engine start. An OFF toggle during a blocked publish, configuration wait or queued worker now yields a proven pre-effect `PRECHECK_FAILED` with a checked barrier clear and no engine call; OFF after engine start is deliberately not revoked. Hardware/OS FGS eligibility remains a separate gate. |
| R2-18 | `d32006e` | Checked attempt-store work runs on a bounded single-thread admission executor with claim-once/close-once ownership, and the read-only availability diagnostic runs on a separate worker with sequence/generation guards. No safety check is bypassed: Boot OFF, the durable barrier, single-flight and fail-closed diagnostics stay authoritative. |
| R2-18 (R4.6) | — | Narrower, honest guarantee: `ExploitService`'s main-thread failure and cancellation paths now **enqueue** the correlated outcome report on the admission worker instead of calling the coordinator method that takes the monitor held across checked storage; notification/status reads remain volatile-snapshot-only. A background worker may still wait on storage under that durable barrier lock. This is **not** a claim that every path is nonblocking. |
| R2-20 (R4.7) | — | Shutdown is per start, not per latest snapshot: a resolved start is stopped exactly once, and the foreground notification is removed only when every owned start is resolved and no owned operation is active, so an older terminal snapshot cannot cancel a newer pending request. A per-instance owner token is invalidated before destruction cancels local work; the coordinator denies a dead owner under the admission lock and again immediately before engine start (proven pre-effect `PRECHECK_FAILED`, barrier cleared, no engine). Once the engine has started, ownership stays with the coordinator and an unverified outcome keeps lease, freeze and barrier. |
| R2-19 + R2-04 caveat | `1252b3d` | Recovery requires confirmed engine completion (`engineFinished` only after `engine.run()` returns/throws) before an in-process clear; persisted-attempt recovery is a trusted-caller, post-process-death acknowledgement with a checked clear; final-result messages no longer claim a bare `SUCCESS` ("run completed — verify root status yourself" / "already attempted/hooked; root status unverified"). Typed codes, the already-hooked SUCCESS code and no-reentry guard, `/dev/df` and `/dev/dfm6` progress strings, and the no-root-probe policy are unchanged. |

**Final local verification (non-privileged host only, this round):** `:app:testDebugUnitTest`
**554 tests / 45 suites, 0 failures / 0 errors / 0 skipped**; **137 host CTest
cases, 0 failures**; `:app:lintDebug` **0 errors / 15 warnings**; workflow policy
contract **67/67**; signing contract **40/40**; make contract **36/36**;
`:app:tasks --all` resolves and the clean/tampered negative loader-pin fixture
behaves as required. No dependency was added or removed, no bundled loader or
other binary changed, and the documentation/notices/hygiene edits in this
round introduce no runtime behavior change.

**Gates — open, not claims:**

- Direct Boot foreground-service eligibility from boot (cold-boot validation).
- Real soft-reboot safety; the historical full hard reboot on this model still
  stands and unattended soft reboot stays disabled.
- `/dev` marker-root trust (SELinux/on-device validation; host tests inject
  harmless roots only).
- The sticky bit on `/data/local/tmp` does not defend against its owner
  (`shell`); descriptor identity checks mitigate, not remove, that assumption.
- AES key in the payload argv is an accepted residual risk (out of scope for
  redesign); parsing was only hardened/range-checked.
- An owner-facing recovery UI is out of scope; an ordinary user with an
  unconfirmed persisted attempt is intentionally locked out until a trusted
  caller clears the barrier or app data is reset.
- A new `BOOT_COUNT` is diagnostic only and is **not** proof that prior
  privileged effects stopped or that a retry is safe.

**No device, hardware, ADB, payload, privileged-container, cold-boot,
soft-reboot, or APK-assembly validation was performed.** A fresh remote GitHub
Actions run after push — with all PR #1 policy jobs plus the restored app
checks, loader-pin fixtures and packaged-pin verification green — is the
**POST-PUSH gate**; CI green is **not** claimed locally.

## Re-review 1 repair (2026-11-25)

This section records the **new repair round** on top of the first 2026-11-25
repair (commits `6686973` → `a8003c2` on `fix/app-correctness-safety`). It closes
tracking findings **R2-10 through R2-16** and the still-open parts of
**R2-02/03/04/07/09**. The earlier "Validation status" section below remains the
record of the first repair round; the counts in this section are the current
ones. `versionCode`/`versionName` are unchanged (408 / `4.8-gzif`).

- **Gradle pin gate restored (`6686973`).** Loader-pin verification moved from
  the build script into Gradle-supported `buildSrc` tasks, fixing the
  non-static-inner-class failure that broke `:app:tasks --all`. The strict
  two-pin, one-APK, packaged-entry SHA-256 checks are retained, and a
  deterministic harmless synthetic-fixture guard now proves that a clean APK
  passes while a one-byte-tampered `libksud.so` is rejected; it is wired into
  CI (`loader-pin-fixtures`). No real APK path or device is touched.
- **Conservative descriptor-based staging cleanup (`639a9d8`).** The created
  staged inode is pinned by a descriptor and verified with `fstat`/`lstat`
  before any unlink. If the path no longer names that inode, cleanup never
  unlinks the replacement, still restores the directory mode, and reports a
  diagnostic. The `O_WRONLY` pin is converted through `/proc/self/fd` to a
  non-writing pin before `install()` returns, so the loader can be exec'd
  without `ETXTBSY`; an unavailable conversion fails closed.
- **Atomic terminal marker publication (`12a633b`).** The full marker payload is
  written to an unpredictable same-directory temp file (`O_EXCL|O_NOFOLLOW`,
  full write, `fsync`, `fchmod 0644`, checked close) and only then atomically
  linked to the run-scoped final name, with temp unlink and directory fsync.
  A **pre-link** failure leaves no final marker and no debris, so a reader cannot
  observe success from a failed write. A **post-link** failure (temp unlink or
  directory fsync) is unconfirmed: the indeterminate rc=7 channel is published
  **before** retracting the intended marker, and rc=7 outranks success and
  conflicts in the classifier. Because `link()` makes the final pathname visible
  before cleanup and directory fsync can succeed, a reader polling in that brief
  post-link/pre-rc7 window **can** observe the intended marker as a confirmed
  terminal verdict, and that observation cannot be retroactively revoked. We do
  not claim a failed publication is never observable as confirmed; we claim only
  that any result the app acts on **after publication completes** is
  indeterminate (writer exits rc=7, reader's steady-state marker is rc=7).
  **Superseded by the R4.3 two-phase acknowledgement below**: a reader no longer
  acts on the early intended marker; confirmation is required.
- **Confirmation protocol (R4.3, uncommitted).** `run_status_write()` publishes
  the intended final marker and then a matching `dfroot_confirm_` marker before
  returning success; the classifier accepts a non-7 code only when the
  confirmation matches both operation ID and code. An intended marker without
  confirmation yields no decision. Any detected failure publishes rc7 first,
  then retracts links and cleans temps, with the writer exiting 7. Abnormal
  death after the confirmation link but before its fsync, or between the two
  phases, remains an **open hardware gate**, not a proved-safe window.
- **rc=7 indeterminate contract for missing confirmation (`12a633b`, `ce781f8`,
  `7c1f39a`).** Bounded, non-killing native waits map timeouts and unobserved
  exits to rc=7 (late-load and soft-reboot); the reader's own deadline expiry
  is rc=7 ("privileged child may still be running"); a failed/unconfirmed
  publication cannot report success as the app's acted-on result (the observable
  post-link/pre-rc7 window is documented above); and a valid reserved
  pre-admission rc=1 marker with no terminal marker for the admitted operation
  yields rc=7 with
  an explicit "completion unverified" diagnostic. Current-operation markers
  stay authoritative, foreign/stale markers never become success, and rc=2 is
  only a pre-operation `setup()` failure.
- **Durable unconfirmed-attempt barrier (`7b5397c`).** The operation attempt is
  persisted in device-protected storage before privileged work or `run_config`
  publication; a new process rejects manual and boot requests until a trusted
  caller clears the barrier, regardless of boot count. Boot claims are suppressed
  while an attempt is unconfirmed, clearing is compare-and-clear by operation ID,
  and the coordinator keeps ownership on indeterminate results. There is no
  in-app recovery UI (see the R3.6 section below).
- **Independent Start-at-Boot OFF (`54cb000`).** OFF is reachable from ERROR
  configuration and active/indeterminate operations without disturbing the
  current run; ON keeps the verified-config, idle-coordinator, and consent
  gates. The claim re-checks the component state and fails closed when it is
  unreadable.
- **Honest already-hooked wording (`a8003c2`; refined in R3.6 below).** The
  R2 round removed the root-verification claim; R3.6 additionally removes the
  ambiguous bare `SUCCESS` text from the user message. The SUCCESS code and the
  one-shot no-reentry guard are unchanged.
- **Read-only availability diagnostic (`a8003c2`).** The UI shows the
  authoritative `RunPreflight` reason in its own view, from the same environment
  source the runner uses. It never starts work, stages files, or substitutes a
  manager/loader, and unreadable observations are never reported as available.

**Validation status (HISTORICAL — first repair round, non-privileged host only; superseded above):** **484 JVM unit
tests across 44 suites**, **135 host CTest cases**, lint **0 errors / 21
warnings**, `git diff --check` clean, loader SHA-256 pins match `app/build.gradle.kts`
and the bundled `jniLibs` binaries, and no generated/build/secret files are
tracked. **No device, hardware, ADB, payload execution, privileged-container,
cold-boot, soft-reboot, or APK-assembly validation was performed for this
revision** (the KO is gitignored/absent). A fresh GitHub CI green run — including
the negative pin fixture — is a **POST-PUSH gate** that cannot be executed
locally; it is not claimed here. The 2026-11-17 hardware runs remain historical
evidence only.

## Re-review 3.6 repair (2026-11-25) — recovery truth and honest success wording

Third repair round on `fix/app-correctness-safety` (HEAD `d32006e`), fixing
**R2-19** (recovery truth) and the remaining **R2-04** caveat (ambiguous
`SUCCESS` wording). No device/ADB, payload execution, privileged container,
dependency, APK assembly, native code, boot gate, attempt-store format, UI
threading or CI change was made. Uncommitted.

- **Recovery requires proof that supervised work has finished.**
  `recoverIndeterminate(operationId)` still needs the exact in-process ID, the
  single claim and a successful checked compare-and-clear, but it additionally
  refuses while the engine call is still executing. An `engineStarted` flag is
  set immediately before `engine.run()`; an `engineFinished` flag is set only in
  a `finally` after that call returns (or escapes by throwing). A supervision
  timeout/interruption callback publishes INDETERMINATE **without** setting
  `engineFinished`, so a trusted caller with the correct ID is refused until the
  engine actually returns. A proven pre-effect rejection (engine never started)
  remains recoverable; no path retries, kills or starts work. Cleanup stays
  exactly once and the checked clear still precedes any lease/freeze release.
- **Persisted-attempt recovery is a post-process-death escape hatch, not an
  owner feature.** `recoverPersistedAttempt(operationId, ownerAcknowledged)`
  requires an affirmative acknowledgement, the exact ID, an unconfirmed record
  loaded at initialization (never one published by this process), and a checked
  clear. It cannot prove that external effects from a dead process stopped.
  There is **NO in-app recovery UI**: an ordinary user with an unconfirmed
  persisted attempt is intentionally locked out — manual and boot admission are
  rejected — until a trusted caller clears the barrier or app data is reset. A
  new `BOOT_COUNT` is diagnostic only and is **not** proof of safety.
- **Final-result user messages no longer claim bare `SUCCESS`.** Generic success
  now reads `DFRoot: run completed — verify root status yourself`; already-hooked
  reads `DFRoot: already attempted/hooked; root status unverified`. The typed
  result codes/types, the already-hooked SUCCESS code, the no-reentry guard, the
  `/dev/df` and `/dev/dfm6` progress strings, and the absence of a root probe are
  all unchanged. Nothing in the message claims root status was verified.

**Validation (host only):** `:app:testDebugUnitTest` **554 tests across 45
suites, 0 failures / 0 errors / 0 skipped**; `:app:lintDebug` **0 errors / 15
warnings**; host CTest recorded then at **135/135** (superseded by the
consolidated R3 section above: final **137/137**), `git diff --check` clean. No
device, hardware, ADB, payload, privileged-container, cold-boot, soft-reboot or
APK-assembly validation was performed. A fresh GitHub CI green run remains a
POST-PUSH gate and is not claimed here.

## Validation status (2026-11-25 — first repair round; counts superseded above)

Non-privileged host verification only:

- **409** JVM unit tests (`./gradlew :app:testDebugUnitTest`), 0 failures/errors/skips.
- **113** host CTest cases, 0 failures. The base suite is 111; the other 2 are
  cleanup fixtures registered only when the mode probes fail and the fixture
  roots are relocated, so 113 is the count in this environment.
- `./gradlew :app:lintDebug`: **0 errors** (21 warnings present before the Phase 6 docs-only edits; one is a `PrivateApi` warning at `AndroidRunEnvironment.java:93`, a file added by PR #2 — so not all 21 predate PR #2).
- `git diff --check`: clean; loader SHA-256 pins match the bundled `jniLibs`
  binaries; no generated/build outputs, secrets, or KO files are tracked.

**No device, hardware, ADB, payload execution, privileged-container, cold-boot,
soft-reboot, or APK-assembly validation was performed for this revision.** The
2026-11-17 hardware runs (run6/run8) remain historical evidence only; see
`CHANGELOG.md` and `PORTING-GZIF.md`. The gitignored KO above is required before
any APK can be assembled.

## Credits

- Original PoC and various code: https://github.com/lsposed/lspromise
- SELinux Permissive kernel modules and various code: https://github.com/polygraphene/DFReroot
- Unprivileged XFRM socket method: https://github.com/combeng6th/DirtyInit
