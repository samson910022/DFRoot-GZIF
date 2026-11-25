# PORTING-GZIF.md — DFRoot (CVE-2026-43284) port to Galaxy S23 Ultra SM-S9180 / S9180ZHU8GZIF

**Date written:** 2026-11-17 · **Author:** subagent for the GZIF port effort · **Scope:** documentation only — this file is the single artifact it produces. No code, scripts, logs, or other files are created or modified by this document.
**Repository:** published at `samson910022/DFRoot-GZIF` — a fork of [diabl0w/DFRoot](https://github.com/diabl0w/DFRoot) @ `7a6513a` ("Fall back to libbase when libc++ hook selection fails (#185)", 2026-11-06), branch `master`.
**Campaign record (device-validation doc):** `samson910022/Root-My-Galaxy-Payloads-Next` → `docs/SM-S9180-S9180ZHU8GZIF.md`
**Log archive (host-side, never committed):** `GZIF-port/` (run logs, dropbox dumps, forensic reports — see §11 "Evidence locator").

> [!IMPORTANT]
> This document describes an **experimental, volatile, per-boot root exploit** for a shipping Samsung firmware. It is for the device owner's own research. It is not a distribution, not stable, and not persistent across reboot. Read §10 (Safety & limitations) before using anything in it.
> "It is not a distribution" refers to this technique note, not to the built APKs: built APKs, including unsigned CI ones, are publicly downloadable and carry the notice/licence bundle (`GPL-3.0.txt`, `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md`).

> [!NOTE]
> **Document status (2026-11-25).** §5, §11, §12 and §14 are the original
> **2026-11-17 historical hardware-validation ledger**. They are preserved for
> provenance and describe the device runs that motivated the port; they are
> **not** validation of the current PR #2 architecture. §8.3 is the original
> **2026-11-17 install/run recipe**, retained with its run6/run8 root oracle;
> where it described the old manager-APK `libksud.so` spinner check and the
> `/dev/dfm6` marker as verification, those steps have been corrected below to
> match the current code. §4/§6/§8.4/§9/§10 have been updated to match the
> current code, **§15** documents the first 2026-11-25 repair architecture and
> residual risks, and **§16** documents the re-review 1 repair round and the
> hardware gates that remain open. **§17** records the R2-19/R2-04 recovery and
> wording phase; **§18** is the authoritative consolidated record of the third
> repair round (rebase onto PR #1, CI integration, R2-12/13/14/16/17/18/19 and
> the R2-04 caveat, with current counts and gates); **§19** records the fourth
> repair round and **§20** is a short dated note on the sixth repair round's
> host-side bounded UI work. No device or privileged validation was done for
> any 2026-11-25/2026-11-26 repair revision.

---

## 1. TL;DR

- The working root on this firmware is **DirtyFrag (CVE-2026-43284) + the DFRoot chain**, with a **Samsung-patched, no-patch-text, kretprobe-fallback KernelSU / KernelSU-Next** module late-loaded at runtime.
- **CVE-2026-43499 is patched** in GZIF (binary-verified) — do not port it. **CVE-2026-98260 is present but unported** (no 5.15/dm3q work exists). **CVE-2026-43284 is open** and is the live route.
- Stock vanilla KernelSU classic v3.3.0 (`me.weishu.kernelsu`) and stock vanilla KernelSU-Next v3.4.2 (`com.rifsxd.ksunext`) **kernel-panicked this device** inside/around `init_module()` on 2026-11-17 (two hardware runs). The panics were the stock-vs-Samsung RKP/KDP/DEFEX class of failure.
- The port works because DFRoot-fzg1 now **bundles and prefers two Samsung-patched Samsung loaders** from the companion payloads repo instead of stock ones:
  - `app/src/main/jniLibs/arm64-v8a/libksud.so` — classic FZG1-validated Samsung-patched ksud **3.3.0** (sha256 `aab76943…`),
  - `app/src/main/jniLibs/arm64-v8a/libksudnext.so` — Next **3.4.2** generic Samsung-patched ksud (sha256 `679504dd…`).
- End-to-end hardware validation was achieved **twice** on the same firmware build on 2026-11-17: **run6** (16:49, classic loader, `ksud start: SUCCESS`, `su -c id` → `uid=0(root) … context=u:r:ksu:s0`, SELinux **Enforcing**, `su -v` = `3.3.0:KernelSU`) and **run8** (17:53, Next loader, identical verification with `su -v` = `3.4.2:KernelSU`).
- Root is **volatile** (RAM-only). Every reboot wipes it. **Start on Boot / Auto Soft Reboot are not validated — do not enable them on this model.**

---

## 2. Target identity (verified)

| Field | Value |
|---|---|
| Model | **SM-S9180** (`dm3q`, `dm3qzhx`; SoC Snapdragon 8 Gen 2, platform `kalama`, board=kalama) |
| Four-part PDA | `S9180ZHU8GZIF/S9180OZS8GZIF/S9180ZCU8GZIF/S9180ZHU8GZIF` |
| Display build | `CP2A.260605.016.S9180ZHU8GZIF` (build-ID family matches `S9210ZHU6EZIF`) |
| `ro.build.changelist` | `34344539` (SYSTEM/build.prop) — **but the kernel banner/common changelist is `34343818`**; the value "34343818" that matters for KMI/module work is the kernel.common changelist, which is also what "changelist 34343818" in the campaign notes refers to |
| Android / SDK | **17 / SDK 37** (`ro.build.version.oneui=90000`, `sep=180000` → One UI **9.0**) |
| Security patch level | **2026-10-05** |
| Fingerprint | `samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys` |
| Kernel release | **`5.15.197-android13-8-34343818-abS9180ZHU8GZIF`** (`#1 SMP PREEMPT Mon Sep 28 06:37:13 UTC 2026`) |
| Kernel compiler | Android clang **14.0.7** (r450784e, LLD 14.0.7) |
| Kernel size | `0x2cb0a00` (46,860,800 B) — byte-identical size to the FZF5/FZG1 images |
| Boot image | header v4, kernel at `0x1000`; boot.img 100,663,296 B; SHA-256 `6df858ed…` |
| `boot.img.lz4` SHA-256 / kernel SHA-256 | `c3590ac3…` / `d8eb9556…` |
| KMI / module deps | `android13-5.15` (2-part KMI); `CONFIG_INET_ESP=y`, `CONFIG_INET6_ESP=y` (ESP built-in), `# CONFIG_USER_NS is not set`, `CONFIG_XFRM_USER=y`, `CONFIG_IKCONFIG_PROC=y` |
| Bootloader / Radio | `S9180ZHU8GZIF` / `S9180ZCU8GZIF` (Radio shown in KP-flagged boot dumps as `S9180ZCU8GZIF,S9180ZCU8GZIF`) |
| Vendor image | Android 13 (`TP1A.220624.014/S9180ZHU8GZIF`, SDK 33) |
| Device | Galaxy S23 Ultra (Taiwan/BRI four-part build; `OZS` multi-CSC; TGY Hong Kong shares the same PDA) |

Host-observed running kernel (device-attached checks on 2026-11-17): `uname -a` = `5.15.197-android13-8-34343818-abS9180ZHU8GZIF #1 SMP PREEMPT Mon Sep 28 06:37:13 UTC 2026`; SELinux **Enforcing**; shell `u:r:shell:s0`; `/proc/config.gz` **byte-identical** to the config extracted from the kernel Image (`cmp` clean).

---

## 3. CVE disposition (binary-verified against this exact kernel)

All three candidate CVEs were triaged against the extracted GZIF kernel Image (see campaign doc §3 for raw addresses):

| CVE | Bug | Verdict on GZIF | Notes |
|---|---|---|---|
| **CVE-2026-43499** | "GhostLock" rtmutex/futex-PI UAF (`remove_waiter` sign-gate) | **PATCHED** | Both upstream fix hunks binary-confirmed present (`remove_waiter()` @ `0xffffffc0096f7474`; `rt_mutex_start_proxy_lock()` @ `0xffffffc0096f9464`; Samsung integrated `3bfdc63936dd` + `40a25d59e85b` by GZIF). Listed **High** in SMR-Oct-2026. Do not port. |
| **CVE-2026-98260** | POSIX-CPU-timer exec-path UAF ("timer payload") | **OPEN (bug present)** | `begin_new_exec()` @ `0xffffffc0084cabd4` decodes to the unpatched 5.15.197 ordering (`posix_cpu_timers_exit()` NOT moved before `unshare_files`/`exec_mmap`). `fs/exec.c` identical between v5.15.197 and v5.15.212; fix only reached 6.1.171/6.6.158 (2026-10-03) — **no 5.15 fix exists anywhere**. **Never ported to dm3q/5.15** (pa3q/6.6-only); listed in **neither** SMR-Oct-2026 nor SMR-Nov-2026. |
| **CVE-2026-43284** | "DirtyFrag" — xfrm ESP in-place CBC decrypt page-cache write | **OPEN (route-gated) — the winning route** | `esp6_input()` @ `0xffffffc00954c888` **and** `esp_input()` (esp4) @ `0xffffffc009489c20` both implement the unpatched fast path verbatim (no `skb_has_shared_frag()`/`SKBFL_SHARED_FRAG` test before `skip_cow`). `CONFIG_INET_ESP=y`/`CONFIG_INET6_ESP=y` built-in; **`# CONFIG_USER_NS is not set`** (desktop userns route dead at kernel level); app-reachable route = **Android `IpSecManager` transport-mode ESP-in-UDP** (netd installs the SA as root; untrusted apps need no permission). Listed **High** in SMR-Nov-2026 (published 2026-11-03) — expect it to close in a later SMR. |

**Sanity facts from the campaign** (`docs/SM-S9180-S9180ZHU8GZIF.md` §6.5): every gate for the IpSecManager route was cleared on the real unit — `C1-SPI-OK`, `C2-ENCAP-OK`, `C3-TRANSFORM-OK`, `C4-APPLY-OK`. The xfrm/SELinux model checks out; the esp4 path is fully app-reachable.

---

## 4. The full chain — as ported in `DFRoot-fzg1`

Notation: **(stage N)** = pipeline position. "App" = `df.root` (DFRoot GZIF APK). Sources below are the upstream DFRoot files with the local port applied.

**(0) Side-effect-free preflight, then owner-owned staging.** The app requests a run through `ExploitService`/`RunCoordinator` (§15); the Activity only observes. Before any staging, IpSec allocation, or process start, `RunPreflight.check()` validates an exact `SuManager` allowlist (`me.weishu.kernelsu` → bundled `libksud.so`; `com.rifsxd.ksunext` → bundled `libksudnext.so`), that the manager is installed and visible, that the caller is the owner user, the exact firmware/kernel gate, the log-buffer setting, and the presence/regularity/symlink-free **SHA-256 pin** of the selected bundled loader plus `libbootstrap.so` and `libdfroot.so`. Any rejection is side-effect-free. An already-present `/dev/df` probe still reports "already hooked" (`HookMarkerProbe`, no root probe), and the app prints device info. Only after admission does the runner copy the pinned bundled loader and `libbootstrap.so` into DE storage and publish the immutable run-config artifact for native consumption. There is no implicit fallback to the manager APK's `libksud.so`.

**(1) IpSecManager ESP transform.** The app opens a `UdpEncapsulationSocket` + `SecurityParameterIndex` via `android.net.IpSecManager`, builds an **AES-CBC / HMAC-SHA256 transport-mode `IpSecTransform`** (`ExploitRunner.java:77-98`). No app permission needed; netd (root, SELinux-allowed) installs the IPv4 ESP-in-UDP SA. The app then execs `libdfroot.so` (the native payload, disguised as a JNI lib — AGP only packages `lib*.so` — and exec'd from `nativeLibraryDir`, which is executable; `runBinary()` `:137`, `ProcessBuilder` + `redirectErrorStream(true)`).

**(2) crash_dump64 splicehelper bridge.** `crash_dump64` (`/apex/com.android.runtime/bin/crash_dump64`) is a privileged binary that runs in the `u:r:crash_dump` SELinux domain via exec-label transition — a domain `untrusted_app` cannot reach directly. The exploit patches it **in the page cache** with `splicehelper` (a freestanding static binary, built on the fly by CMake; see `app/src/main/jni/CMakeLists.txt`). The patch mechanism: AES-CBC in-place decrypt — `IV = AES_ECB_DEC(key, current_content) ⊕ desired_content` — so the kernel's ESP decrypt rewrites the page-cache bytes to the desired shellcode (`exp.c:34-53`). splicehelper has two modes: **splice mode** (pipe a 16-byte page chunk out to the parent, used for writing the `.ko` and patching `crash_dump64`/`libc++.so`) and **read mode** (`argv[3]="r"`, dumps 16 bytes of file content to a pipe for IV computation, `exp.c:189-206`). The vendor file bridge exists because `crash_dump` can open `vendor_file`-labeled files but app-untrusted_app cannot.

**(3) dfroot.ko → vendor file.** The **android13-5.15** LKM `app/src/main/jni/ko/dfroot-android13-5.15.ko` (DDK-built; two distinct fingerprints — the **CI/release artifact is 13,384 B**, sha256 `9dfb8b71b306f5a38fd8b35b283d4dab8b4eb25a08ad97ca0f4ef705b6151c68`, because CI strips `.note.gnu.build-id` with `llvm-objcopy --strip-unneeded`, while the **local `make` build is 13,504 B**, sha256 `94f8be98…`, retaining `.note.gnu.build-id`). The two are therefore **not byte-identical** — same source, different note sections; vermagic `5.15.202-android13-5.15.202_r00-dirty`, modversions satisfied via KMI CRC) is written via the crash_dump bridge (splicehelper splice mode) into **`/vendor/lib64/AIAwbModel_v2.2.tflite`** — a vendor asset that is never executed by the system. File presence is proven by the splice patch; size and `vendor_file` label are unverified (no retained `stat`/`ls -Z` evidence). (Original upstream candidates `libbinderdebug.so` / `libstagefrighthw.so` / `libstagefright_aidl_bufferpool2.so` are **absent** on this build; the local port hard-codes the tflite in `exp.c:443` and `libcxx.S:22`.) The exploit targets GZIF's `KMI=android13-5.15` (`Using KMI: android13-5.15`).

**(4) libc++.so sentry hook → init tid=1.** The exploit patches `/system/lib64/libc++.so` at `std::ostream::sentry::sentry()` (symbol `_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentryC1ERS3_`): **trampoline @ `+0xb4ae0`**, **shellcode @ `+0x10faa8`**, hook site `0xb4ae8`; a BTI/PAC preamble at the hook site was detected and the landing pad advanced (+4) — all printed in-run (`run8_logcat.txt:7777-7782`). The `createOrphanProcess()` double-fork makes the grandchild an orphan; **init (PID 1) reaps it**, and init's main thread (**tid=1**) then calls through the hooked function. The shellcode (`libcxx.S`):
- verifies `getuid()==0 && gettid()==1`, returns otherwise;
- creates **`/dev/df`** (`O_CREAT|O_EXCL`) as a one-shot per-boot mutex — **this is the `/dev/df` the app polls**;
- clones a worker child; the worker clones a grandchild;
- grandchild writes `u:r:vendor_modprobe:s0` to `/proc/self/attr/exec`, then `execve("/vendor/bin/insmod", ["insmod", "/vendor/lib64/AIAwbModel_v2.2.tflite", NULL])`. (`/vendor/bin/insmod` is a toybox applet → `vendor_toolbox_exec` → transitions into the **`vendor_modprobe`** domain.)

**(5) dfroot.ko init (vendor_modprobe, uid=0) — `lkm/dfroot.c`.** On `init_module()`:
- resolves `kallsyms_lookup_name` via a kprobe; resolves `aarch64_insn_patch_text`, `kern_path`, `invalidate_inode_pages2`, `path_put`, `avc_has_perm`, `avc_has_perm_noaudit`, `task_defex_enforce`, `task_defex_user_exec` (and Oppo/OnePlus `oplus_*` when present);
- **invalidates the page cache of `crash_dump64`** (`invalidate_inode_pages2`, `dfroot.c:84-96`) so the next exec sees the patched file;
- **`WRITE_ONCE(selinux_state, false)`** — flips the global SELinux state to Permissive (`dfroot.c:98-104`); if the flag didn't take (kernels with `SECURITY_SELINUX_DEVELOP=n`), it instead patches `avc_has_perm[_noaudit]` to `return 0` (`BTI C` + `MOV x0,#0` + `RET`, `dfroot.c:106-126`);
- **Samsung DEFEX neutralization**: patches `task_defex_enforce` and `task_defex_user_exec` to return 0 the same way (`dfroot.c:144-161`) — this is the DEFEX half; the RKP/KDP halves for the **KSU module's own behavior** live in the Samsung-patched `kernelsu.ko`, not in dfroot.ko;
- runs **`call_usermodehelper_setup`/`exec`** (`UMH_WAIT_EXEC`) for `/system/bin/sh -c 'touch /dev/dfm0; echo -n u:r:init:s0 > /proc/self/attr/exec; exec /data/user_de/0/df.root/bootstrap'` (`dfroot.c:52-61,162-178`);
- **returns `-ENODEV` on purpose** ("intentionally fail init to unload immediately; avoids triggering mrdump module-loaded callbacks on MTK devices", `dfroot.c:186-188`).

**(6) bootstrap.c — `app/src/main/jni/bootstrap.c`.** Kernel UMH runs the shell: `/dev/dfm0` touched → exec context set to `u:r:init:s0` → `exec /data/user_de/0/df.root/bootstrap`. The kernel runs this with **stdio → /dev/null** (umh setup stdio pointers are NULL), so diagnostics go to the `/dev/dfm*` marker files and to run-scoped terminal markers, not to the app. Bootstrap now:
- parses the **atomic versioned `run_config` artifact** published by Java (strict, fail-closed; no SharedPreferences XML parsing); on parse failure a reserved safe pre-admission ID carries rc=1;
- `adopt_zygote_env()` — clones zygote64's `environ` (SELinux/label propagation for later children);
- `set_partitions_ro()` — `BLKROSET=1` on `super`, `misc`, `steady`, and any `*_a`/`*_b` block device under `/dev/block/by-name`;
- if `disable_modules` is set: creates `/data/adb/modules/*/disable` for every installed module (bootloop recovery);
- **hardened staging (§15)**: `mkdir("/data/adb", 0755)` then `loader_stage_install()` copies the staged daemon to `/data/local/tmp/.ksud-stage` with sticky-bit protection, `O_EXCL|O_NOFOLLOW`, a verified descriptor copy and fsync; execs **`/data/local/tmp/.ksud-stage late-load --package-name <pkg>`** as uid 0, `u:r:init:s0`, Seccomp: 0; the staging directory mode is restored whether or not late-load succeeds;
- on **exit 0** → `touch /dev/dfm6` (progress only), then a **run-scoped terminal marker** rc=0 (or, when enabled, a bounded soft-reboot command → rc=4/rc=6); on any **plain error** → a run-scoped rc=1 terminal marker, but on a **late-load wait timeout (60 s) or unobserved child exit** → **rc=7 indeterminate** (§16; no definitive failure and no forced kill), with the marker atomically published. `dfme2` is retained as a legacy progress marker.

**(7) Samsung-patched ksud late-load.** The bundled loader (classic FZG1 v3.3.0 — log tag **`KernelSU`**; or Next generic 3.4.2 — log tag **`KernelSU Next`**):
- logs `ksud::cli: command: LateLoad {… package_name: "…"}` (`ksud::late_load: late-load command triggered!` … `Detected KMI: android13-5.15` — **2-part KMI always**, verified);
- **stages itself before the module load** — `utils::stage_daemon_from("/data/local/tmp/.ksud-stage")`: `ensure_dir_exists(/data/adb)` + `fs::rename("/data/local/tmp/.ksud-stage", "/data/adb/ksud")` + `chown root:root` + `chmod 0755` (**this is why `/data/adb` and `.ksud-stage` must exist — patch 2 feeds exactly this contract**);
- `ksuinit::has_kernelsu()` → false on fresh boot; `ksuinit::load_module()` binds undefined symbols from `/proc/kallsyms` (transient `kptr_restrict=1` write), reads `/dev/kmsg` to the end (`ksuinit: Reading kernel log from /dev/kmsg`), **`Replaced module vermagic with kernel-required value`** (vermagic mismatch rewrite → retry → `init_module(&buffer, params)`);
- **The module is the Samsung-patched, no-patch-text, kretprobe-fallback KSU build** (`CONFIG_KSU_SAMSUNG_KDP/RKP/DEFEX=y`, `CONFIG_KSU_SAMSUNG_NO_PATCH_TEXT=y`): it **never writes the syscall table** (dispatcher slot detected protected → kprobe/IP-redirection hooks on `execve`/`execveat`/`newfstatat`/`faccessat`, plus a **kretprobe on `setresuid`**), `ksu_patch_text()` returns `-EOPNOTSUPP` (fail-closed — log line `"live patching disabled on Samsung EL2"`), SELinux-hide goes through kprobes, **KDP** creds are installed via a workqueue → `prepare_ro_creds(SAMSUNG_KDP_COPY_CREDS=0)` + `kdp_assign_pgd` (kdp usecounts via `kdp_usecount_dec_and_test`), **DEFEX** cred shadow is re-synced after every escape, and `task_defex_enforce` is kprobe-zeroed for KSU-domain root tasks. Lineage: `kernelsu/patches/KernelSU-Next-v3.4.2-samsung-kdp-rkp-defex.patch` / `KernelSU-v3.3.0-samsung-kdp-rkp-defex.patch` in the payloads repo (identical strategy to diabl0w's `samsung-v2.2` compat tree — `kernel/compat/{kdp,rkp,defex}.c`). See `samsung-lateload-forensics.md` and `next-loader-codepath.md` for the reverse-engineering;
- loads `android13-5.15_kernelsu.ko` (embedded asset — see §7) → **`kernelsu.ko loaded successfully!`**, context now `u:r:ksu:s0`; creates/uses the KSU driver fd via the `reboot(2)` supercall doorbell (arm64 syscall **142**, `x0=0xDEADBEEF x1=0xCAFEBABE`); applies SELinux/sulog/kernel_umount feature configs from `/data/adb` (sepolicy rules for pre-installed KSU modules get loaded here too);
- su apps can then use `su` → shell/`u:r:ksu:s0`, uid 0.

**(8) Cleanup.** The app restores the `libc++.so` trampoline/shellcode after the hook fires (`=== cleanup ===` in every successful log) and closes the IpSec SA/sockets.

**Marker map** (exp.c:540–552 ↔ bootstrap.c touch points):

| File | Meaning |
|---|---|
| `/dev/df` | hook armed (libc++ shellcode ran, one-shot per boot) |
| `/dev/dfm0` | dfroot.ko ran; UMH launched bootstrap |
| `/dev/dfm1` | `bootstrap: loading run-config artifact` (progress) |
| `/dev/dfm2` | zygote env cloned |
| `/dev/dfm3` | partitions set ro |
| `/dev/dfm4` | module-disable pass (only if `disable_modules` pref) |
| `/dev/dfm5` | bootstrap reached "starting SU daemon" |
| **`/dev/dfm6`** | **owner-visible late-load SUCCESS *progress* diagnostic** — `.ksud-stage late-load` exited 0 → DFRoot prints `ksud start: SUCCESS`; not terminal |
| `/dev/dfme0` | legacy prefs/config read failed (progress) |
| `/dev/dfme1` | disable-modules failed (progress) |
| `/dev/dfme2` | legacy `.ksud-stage`/ksud failure marker (progress) |
| `/dev/dfmw0`/`dfmw1` | zygote-env / partitions-ro warnings (non-fatal progress) |
| **run-scoped `/dev/dfroot_terminal_<op>_rc<code>`** | **terminal marker** bound to the admitted operation ID (e.g. `/dev/dfroot_terminal_<op>_rc0`), published atomically via a same-directory temp + `link()`: `rc0` success, `rc1` bootstrap/terminal error, `rc4` soft-reboot command success, `rc6` soft-reboot command failure, `rc7` indeterminate channel (unconfirmed publication; outranks success and conflicts). A late-load wait timeout is indeterminate, not `rc1`. |

> [!NOTE]
> **All global `/dev/*` markers, including `/dev/dfm6` and its `ksud start: SUCCESS`
> message, are progress-only.** Native returns only after a run-scoped terminal
> marker is observed; if the reader deadline expires first it returns **rc=7
> indeterminate** (privileged children may still be running), never SUCCESS and
> never a definitive failure. An early/global SUCCESS can no longer terminate a
> run. Historically, SUCCESS only meant `.ksud-stage late-load`
> exited 0 and did **not** prove `kernelsu.ko` finished loading (runs 2 and 3
> printed SUCCESS *before* `init_module()` and then panicked). No root probe was
> added: verify root with `su -c id` / `su -v` / `getenforce` via adb or
> `cat /proc/modules | grep kernelsu`. The current architecture has **not** been
> hardware-validated.

---

## 5. Proven on hardware — run ledger (2026-11-17)

All times local. "KP" = kernel panic (abnormal reset; dumped as XBL/ABL-only `SYSTEM_LAST_KMSG_*_KP` in dropbox; **no kernel text / panic string is ever captured on this device** — full stops).

| Run | Time | Manager selected | Loader actually staged (bundled) | Outcome | Evidence |
|---|---|---|---|---|---|
| run1 | ~15:23 | `me.weishu.kernelsu` | stock classic (manager APK's libksud.so, pre-bundling) | **Kernel panic** (KP191; unlogged, unattributed) | `manager-compat-analysis.md:59`; dropbox KP-flagged resets |
| run2 | 15:34 | `me.weishu.kernelsu` | stock KernelSU classic v3.3.0 (vanilla) | **panic inside/around `init_module()`** (KP194). DFRoot printed SUCCESS at 15:34:23.150 (`run2_logcat.txt:1679`); `Loading kernelsu.ko …` first appears only afterwards (`:1688`); device machine-stopped ~199 ms later. **Succeeds at nothing.** | `run2_logcat.txt:1673–1851`; `manager-compat-analysis.md` §6–7; `kmsg_195.txt` (sibling KP195) |
| run3 | 15:49 | `com.rifsxd.ksunext` | stock KernelSU-Next v3.4.2 (vanilla) | **panic inside/around `init_module()`** (KP195) — identical locus; manager exonerated as cause | `run3_logcat.txt:1529–1705`; `manager-compat-analysis.md` §6.3 (manager-independence table) |
| run4 | 16:29 | `com.rifsxd.ksunext` | bundled classic FZG1 loader (`aab76943…`) | **`Error: Failed to stage ksud`** — `Failed to rename /data/local/tmp/.ksud-stage to /data/adb/ksud: No such file or directory`; DFRoot printed `ksud start: ERROR - ksud nonzero exit` (`run4_logcat.txt:30782-30788`). No panic (device stayed up). Root cause: bootstrap never created `/data/adb` nor staged `.ksud-stage` → the Samsung loader's mandatory staging step failed. **Fixed by local port patch 2.** | `run4_logcat.txt:30303–30793` |
| run5 | 16:32 | — | — | no exploit run (app launched only; zero exploit lines in capture) | `run5_logcat.txt` |
| **run6** | **16:49** | `com.rifsxd.ksunext` | bundled classic **FZG1 Samsung-patched** loader | **FULL ROOT SUCCESS.** `kernelsu.ko loaded successfully!` 16:49:50.856 (`run6_logcat.txt:26590`); auto-load of KSU modules (sepolicy) at :26694–:26978; `ksud start: SUCCESS` 16:49:51.175 (:26980). On-device: `su -c id` → `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`; `su -v` → `3.3.0:KernelSU`; `getenforce` → **Enforcing** (`docs/SM-S9180-S9180ZHU8GZIF.md` §6.7). `.ksud-stage` renamed to `/data/adb/ksud`. | `run6_logcat.txt:25747–26980`, `:45621` (shell marker probe); campaign doc §6.7 |
| 17:00 run | 17:00 | `com.rifsxd.ksunext` | bundled **Next FZF5-pair** loader 3.4.1 (`f23ede9b…`) — the "shipped-broken" artifact | **`ksud nonzero exit` in ~52 ms, no panic, no module load.** Markers: `/dev/dfm5` yes, `/dev/dfme2` yes, `/dev/dfm6` −; device state `SELinux Permissive` (exploit ran). Root cause: the FZF5 pair embeds **only** `android13-5.15.189_kernelsu.ko`; the runtime KMI key is **`android13-5.15`** → asset lookup miss → loader exits 1 before any kernel interaction. A *packaging* defect, not a firmware rejection. | `next-failure-evidence.txt:3-16,19-58`; `next-loader-codepath.md`; `next-failure-analysis.md` |
| **run8** | **17:53** | `com.rifsxd.ksunext` | bundled **generic Next 3.4.2 Samsung-patched** loader (`679504dd…`) — swapped in at 17:50 | **FULL ROOT SUCCESS.** `ksud::late_load: kernelsu.ko loaded successfully!` 17:53:23.051 (`run8_logcat.txt:9739`); `u:r:ksu:s0`; modules' sepolicy loaded (`:9747–:9871`); `ksud start: SUCCESS` 17:53:23.342 (:9881); device alive (logcat continues); cleanup restored libc++ (:9883–:9887). On-device: `su -c id` → uid 0; `su -v` → **`3.4.2:KernelSU`**; `getenforce` → **Enforcing** (campaign doc §6.8). One non-fatal feature error: `Failed to set feature 5: ksuctl failed: Operation not supported on transport endpoint (os error 95)` (`:9850`). | `run8_logcat.txt:3509–3515,3715–3830,7772–9887`; campaign doc §6.8 |

### 5.1 What "two independent runs, both managers" means — precisely

- Two manager APKs are installed on the unit and both are selectable in DFRoot's spinner (each ships `nativeLibraryDir/libksud.so`, satisfying `hasKsud()`): **`me.weishu.kernelsu`** (classic) and **`com.rifsxd.ksunext`** (KernelSU-Next).
- The hardware-validated end-to-end chains were both run with **`com.rifsxd.ksunext` selected** (run6 = bundled classic loader; run8 = bundled generic-Next loader). Selecting `me.weishu.kernelsu` would stage the **same** bundled classic loader (`stageAssets()` prefers the bundled one when present — patch 1), differing only in the `--package-name` argument; the classic manager itself is userspace-broken on this build regardless (see §9.5), and that path has **not** been separately exercised.
- Two KSU **lineages** have now been validated end-to-end on this firmware: the Samsung-patched **classic KernelSU 3.3.0** (FZG1-validated build, run6) and the Samsung-patched **KernelSU-Next 3.4.2** (generic build embeds four 2-part KMIs incl. `android13-5.15`, run8).
- Failure runs run2/run3 prove the **stock vanilla** modules of *both* managers panic in the same place — i.e., the manager ABI is not the variable; the module's Samsung accommodation is.

### 5.2 What failed before (and why)

1. **Stock vanilla KernelSU classic v3.3.0 (`me.weishu.kernelsu`)** — panic at/around `init_module()` for the stock `android13-5.15_kernelsu.ko` (run2, KP194). Best-fit mechanism (never proven — panic text unrecoverable): the stock module's syscall-table `stop_machine` fixmap write and/or `selinux_write_op[]` functable patches vs **RKP** (hypervisor rodata protection), plus KDP-creds/DEFEX mismatch. See `manager-compat-analysis.md` §6.1/§6.3/§6.6, `samsung-lateload-forensics.md` §6.1.
2. **Stock vanilla KernelSU-Next v3.4.2 (`com.rifsxd.ksunext`)** — identical panic, same window (run3, KP195). Established manager-independence: both runs died *after* `Loading kernelsu.ko …` / `ksuinit: Reading kernel log from /dev/kmsg`, ~199–211 ms after SUCCESS, mid-Toast animation, machine-stop, no watchdog, no dump text (`kmsg_195.txt:8,403,1047-1069` — `PonReason.HARD_RESET=1`, `Failed to get KlogOffset, Not Found`, footer `Last boot reason: reboot`).
3. **Repo's pre-packaged Next FZF5 daemon mismatch** (the port's first shipped `libksudnext.so`, replaced at 17:50 by `ksud-next-generic-kdp`): embeds **only** asset `android13-5.15.189_kernelsu.ko`; the runtime KMI key on this firmware is `android13-5.15` (2-part). `ksud late-load` died ~52 ms after spawn with the marker error documented in `next-failure-evidence.txt:52` and parsed in `next-failure-analysis.md` §3–4 and `next-loader-codepath.md` §1/§3/§6. **No kernel panic — it never even passed `init_module()`.** The capture of the actual `Error:` line is missing (the capture filtered exact tag `KernelSU`, and Next logs under `KernelSU Next`); the cause is provable statically from the binary (asset table) and from the shared-stage evidence `/dev/dfme2`.
4. **A plausible-but-wrong suspect**: the run2 panic was first suspected to implicate the classic *manager* app (its userspace crashes are real — Writable-dex SIGABRT on `me.weishu.kernelsu:root:0`, SIGSYS seccomp `syscall 142` probes — but those are app-side, deterministic, build-independent, and **not** the panic). run3 (Next manager) died at the identical locus, exonerating the manager ABI (`manager-compat-analysis.md` §3–5).
5. **run4 — Samsung-patched loader, wrong staging**: before patch 2, bootstrap never created `/data/adb` nor staged `.ksud-stage`; the loader's own first act (`rename(.ksud-stage → /data/adb/ksud)`) then failed (`run4_logcat.txt:30782-30788`). Fixed by patch 2; validated by run6.

> [!WARNING]
> The DFRoot-on-GZIF app originally shipped **with the broken FZF5 Next pair as `libksudnext.so`**; if you ever repopulate `app/src/main/jniLibs/arm64-v8a/libksudnext.so` from repo artifacts, verify the embedded assets (§7) before depending on it. The shipped file now embeds `android13-5.15_kernelsu.ko` and works (run8).

### 5.3 What was *not* fixed by the port

- **CVE-2026-43284 itself** — still open in the kernel (SMR-Nov-2026 lists it High). The port depends on it; a Samsung patch could close the exploit underneath this APK entirely.
- **Samsung's EL2 stack (RKP/KDP/DEFEX)** — the Samsung-patched module survives it on this build (run6/run8); *why* is understood (no syscall-table write, no live text patch, KDP-workqueue creds, DEFEX cred re-sync) but **never measured on 34343818**; treat each cold boot as a fresh experiment.
- **Persistence** — none. Root evaporates on reboot by design (and on purpose — this is volatile root on a locked bootloader).

---

## 6. The two local patches relative to upstream `diabl0w/DFRoot`

Upstream baseline: `diabl0w/DFRoot @ 7a6513a69ce4c1a90b68d857c26347bf166ffe10` ("Fall back to libbase when libc++ hook selection fails (#185)", 2026-11-06), branch `master`. The port was committed to `samson910022/DFRoot-GZIF` on 2026-11-17 (see CHANGELOG.md / NOTICE).

### Patch 1 — bundled Samsung-patched loaders, keyed off the selected manager package (historical patch, current code below)

Upstream staged the *manager APK's* `libksud.so`, always:

```java
private static void stageAssets(Context ctx, String suManagerPackage) throws Exception {
    var app = ctx.getPackageManager().getApplicationInfo(suManagerPackage, 0);
    stageBin(ctx, new File(app.nativeLibraryDir, "libksud.so"), "ksud");
    stageBin(ctx, new File(ctx.getApplicationInfo().nativeLibraryDir, "libbootstrap.so"), "bootstrap");
}
```

The 2026-11-17 port staged from the app's own `nativeLibraryDir` when available,
with an upstream fallback. That fallback has since been **removed**: current code
is shown below (Phase 1, §15).

```java
// Current: SuManager is a canonical allowlist; each entry owns exactly one
// pinned bundled loader, and RunPreflight rejects anything else before side effects.
CLASSIC("me.weishu.kernelsu", "libksud.so",     BuildConfig.BUNDLED_LOADER_CLASSIC_SHA256),
NEXT   ("com.rifsxd.ksunext", "libksudnext.so", BuildConfig.BUNDLED_LOADER_NEXT_SHA256);
File loader = new File(nativeLibraryDir, manager.getLoaderName());
// regular-file + no-symlink + SHA-256 pin checks ... then stage that loader.
```

Semantics and consequences:
- `me.weishu.kernelsu` selected → **bundled classic** `libksud.so`; the selected manager only supplies the `--package-name` argument.
- `com.rifsxd.ksunext` selected → **bundled Next** `libksudnext.so`; same.
- **No implicit fallback.** A missing, symlinked, renamed, or hash-mismatched
  bundled loader aborts the run during side-effect-free preflight. The manager
  APK's `libksud.so` is never used as a loader; only its presence is an
  eligibility/installation check.
- The staged loader is executed **not** by the app or by bootstrap directly:
  bootstrap execs it as `/data/local/tmp/.ksud-stage`; the two bundled loaders
  were built for exactly this (their embedded path constant is shared).

Why this is the correct change: the stock managers' embedded modules **panic** on this firmware (run2/run3), and the upstream flow staged exactly those modules. The Samsung-patched loaders survive (run6/run8). This patch swaps module provenance without touching the exploit.

### Patch 2 — `app/src/main/jni/bootstrap.c`: create `/data/adb`, stage `/data/local/tmp/.ksud-stage`, then exec it

Upstream's bootstrap did, on the success path:

```c
touch("/dev/dfm5");
if (run_ctx(NULL, (char *[]){ KSUD_STAGE, "late-load", "--package-name", su_manager, NULL }) == 0) {…}
```

where `KSUD_STAGE` = `/data/user_de/0/df.root/ksud` — the Samsung-patched loaders **first** perform `utils::stage_daemon_from("/data/local/tmp/.ksud-stage")` (rename `.ksud-stage` → `/data/adb/ksud`, or fail). With no `/data/adb` present and no `.ksud-stage` present, that step failed → run4 (`Error: Failed to stage ksud`, `Failed to rename /data/local/tmp/.ksud-stage to /data/adb/ksud: No such file or directory`).

The 2026-11-17 port used a `system("cp …")` copy; that has been replaced by
hardened in-process staging (Phase 4, §15). Current code:

```c
/* Excerpt from app/src/main/jni/bootstrap.c:272-307 (function-level indentation
 * stripped); the soft-reboot tail is elided and marked below. */
touch("/dev/dfm5");
mkdir("/data/adb", 0755);
/* Stage bundled ksud to the FZG1 staging path; that loader renames the
 * staged entry to /data/adb/ksud. No shell and no process signaling. */
struct loader_stage stage;
if (loader_stage_configure(&stage, NULL, NULL) != 0
    || loader_stage_install(&stage, KSUD_STAGE) != 0) {
    fprintf(stderr, "bootstrap: loader staging failed: %s\n",
            loader_stage_last_error(&stage));
    touch("/dev/dfme2");
    return terminal(RUN_STATUS_TERMINAL_ERROR);
}
int late_rc = run_ctx(NULL, (char *[]){ "/data/local/tmp/.ksud-stage", "late-load",
                                       "--package-name", config.manager, NULL });
/* Restore the staging directory mode whether or not late-load succeeded.
 * A restore failure is a generic bootstrap error (rc=1); the terminal
 * marker protocol and all global progress markers stay unchanged. */
int restored = loader_stage_restore(&stage);
if (restored != 0) {
    fprintf(stderr, "bootstrap: staging mode restoration failed: %s\n",
            loader_stage_last_error(&stage));
}
if (late_rc != 0) {
    touch("/dev/dfme2");
    return terminal(RUN_STATUS_TERMINAL_ERROR);
}
if (restored != 0) {
    return terminal(RUN_STATUS_TERMINAL_ERROR);
}

/* /dev/dfm6 remains the owner-visible late-load progress marker. The
 * terminal outcome is the run-scoped marker written below. */
touch("/dev/dfm6");
if (!config.soft_reboot) {
    return terminal(RUN_STATUS_TERMINAL_SUCCESS);
}
/* ... bounded soft-reboot command (only when configured), then terminal rc=0/4/6 */
```

This still matches the documented FZG1 loader contract (`kernelsu/README.md:154-156`
in the payloads repo; both bundled binaries contain the strings
`/data/local/tmp/.ksud-stage`, `Failed to stage ksud`, `/data/adb/ksud`): the
loader renames `.ksud-stage` → `/data/adb/ksud` itself. The FZG1 ABI path is
unchanged, but `system()`/shell `cp` is gone: the copy uses `O_EXCL|O_NOFOLLOW`,
a sticky-bit-protected directory, a verified descriptor copy with fsync/fchmod,
and guaranteed mode restoration. `.ksud-stage` and `/data/adb/ksud` remain on the
same `/data` filesystem so `rename(2)` does not cross devices. Failures publish a
run-scoped rc=1 rather than relying on `/dev/dfme2`. See §15 for residual risks.

> [!NOTE]
> The same failure marker (`/dev/dfme2`) covers both "cp failed" and "ksud exited nonzero"; distinguishing them requires either reading the app log (the app prints `bootstrap: starting SU daemon` then waits for `/dev/dfm6`) or capturing logcat with **both** tags (`KernelSU` **and** `KernelSU Next`) — the 17:00 evidence was blind to the latter and had to be diagnosed from the binary (see §9.2).

### Other local port deltas vs upstream (for completeness — these are *not* one of the two named patches)

`git diff` in `DFRoot-fzg1/` additionally shows:

- `app/build.gradle.kts` — `compileSdk`/`targetSdk` 37→36 (host SDK tops at 36);
- `gradle/libs.versions.toml` — AGP 9.4.0→9.5.0. This is now the **single source of truth**: the root `build.gradle.kts` no longer pins `version "9.4.0" apply false` (it applies `alias(libs.plugins.android.application) apply false`) and `app/build.gradle.kts` applies the same alias, so the **effective AGP is 9.5.0**. The old root pin made the catalog bump inert; that pin is gone;
- `app/src/main/AndroidManifest.xml` — label "DFRoot" → **"DFRoot GZIF"**;
- `app/src/main/jni/CMakeLists.txt` — keeps only `ko/dfroot-android13-5.15.ko` (drops the other 7 KMI entries);
- `app/src/main/jni/exp.c` — embedded KO assets trimmed to `dfroot_ko_13_5_15`; `detect_ko_target()` now hard-codes `/vendor/lib64/AIAwbModel_v2.2.tflite`; `KO_TARGET` selection comment updated for libbase fallback (hook selection via `patch_hook` → `/system/lib64/libc++.so`, falling back to `libbase.so`);
- `app/src/main/jni/libcxx.S` — `ko_target` string changed to `/vendor/lib64/AIAwbModel_v2.2.tflite`;
- `app/src/main/jni/exp.c` / `.S`-side ko_target padding kept at 64 B (`.skip 32, 0`);
- `app/src/main/jni/exp.c` hook site: libc++ sentry symbol unchanged; runtime-detected BTI/PAC preamble advance (+4) logged each run.

None of these change the exploit mathematics. All are required to make the chain run on this exact firmware (missing vendor files, missing KMI images, compileSdk).

---

## 7. In-repo artifacts that matter

In `DFRoot-fzg1/` (all paths relative to its root):

| Path | Size / fingerprint | Role |
|---|---|---|
| `app/src/main/jni/ko/dfroot-android13-5.15.ko` | **CI/release artifact: 13,384 B · sha256 `9dfb8b71b306f5a38fd8b35b283d4dab8b4eb25a08ad97ca0f4ef705b6151c68`**; **local `make` build: 13,504 B · sha256 `94f8be98…`** (retains `.note.gnu.build-id`) | The android13-5.15 LKM. **Not committed** (gitignored; rebuilt by the single-KMI `make` or CI). Built against the digest-pinned DDK GKI image `ghcr.io/ylarod/ddk-min:android13-5.15-20260828@sha256:9e68cd9fcab748584755d31ba88ef134efc938dbde17f258c2652315f6cd81cd` (top-level `make` resolves this exact reference through `scripts/ddk-image.sh`). CI runs `llvm-objcopy --strip-unneeded -R .note.gnu.build-id …`, so its KO (13,384 B) differs from the local build's (13,504 B, `94f8be98…`, with the build-id note retained); both compile the same source and load on GZIF. Modversions satisfied via KMI CRC; intentionally `module_init → return -ENODEV` (unload-immediately; avoids MTK mrdump callbacks). `lkm/dfroot.ko` was removed from the tree during public release hygiene (it was a duplicate of this file). |
| `app/src/main/jniLibs/arm64-v8a/libksud.so` | 5,101,488 B · sha256 **`aab769430467e5a9f723c1bc8a3180e6fb0b0b82c52fba7b99887199407fc940`** · md5 `3d8e7d5a3bf315832867bf6074f71ecc` | **Samsung-patched classic KernelSU 3.3.0** loader (v3.3.0, uapi 2, code 32601), `ksud-dm3q-S9180ZHS8FZG1-kdp` copied verbatim from the payloads repo (pre-strip; APK ships the stripped variant). FZG1-validated on 5.15.189; loads 5.15.197-GZIF (vermagic rewrite via ksuinit). Embedded asset: **`android13-5.15_kernelsu.ko`** (2-part/family name — correct for this firmware). Log tag `KernelSU`. **Device-validated on GZIF (run6).** |
| `app/src/main/jniLibs/arm64-v8a/libksudnext.so` | 4,717,248 B · sha256 **`679504dd4e104dc1a296ed4ec795b5a5f3e808c2bd345a7ecfa201b40f1dca1d`** | **Samsung-patched KernelSU-Next 3.4.2** loader (uapi 5, code 33355), `ksud-next-generic-kdp` verbatim from `support/kernelsu-generic.json` (`sha256 679504dd…`, "version 3.4.2", four KMIs). Embedded assets: **`android12-5.10_kernelsu.ko`, `android13-5.15_kernelsu.ko`, `android14-6.1_kernelsu.ko`, `android15-6.6_kernelsu.ko`** (all 2-part/family names). Log tag `KernelSU Next`. **Device-validated on GZIF (run8).** Do **not** substitute `ksud-next-dm3q-S9180ZHS8FZF5-kdp` (`f23ede9b…`, 3.4.1) — it embeds only `android13-5.15.189_kernelsu.ko` and provably fails pre-load on this build (17:00 evidence). |
| `app/src/main/jni/libcxx.S` | source | tid=1 shellcode + `ko_target` patch point + `/dev/df` mutex + `vendor_modprobe` exec. |
| `app/src/main/jni/exp.c` | source | the whole exploit (IpSec SA, crash_dump bridge, patching, hook, marker poll). |
| `app/src/main/jni/bootstrap.c` | source | UMH → `/data/user_de/0/df.root/bootstrap` (Samsung FZG1 staging contract). |
| `app/src/main/jni/splicehelper(.c)` | source + freestanding binary (built) | splice/read bridge for `crash_dump64`. |
| `app/src/main/java/df/root/*.java` | source | app UI, spinner, `BootReceiver` (Start on Boot — disabled by default; `android:enabled="false"` in manifest). |
| `app/build/outputs/apk/release/DFRoot_4.8-gzif.apk` | 5,884,467 B | **Dual-mode** (legacy KernelSU-classic + KernelSU-Next 3.4.2) — built 2026-11-17 17:50 from the tree above, versionName 4.8-gzif / versionCode 408, applicationId `df.root`, label "DFRoot GZIF". |

Also load-bearing, in the payloads repo `samson910022/Root-My-Galaxy-Payloads-Next`:

- `kernelsu/ksud-dm3q-S9180ZHS8FZG1-kdp` (the classic artifact copied 1:1 into `libksud.so`),
- `kernelsu/ksud-next-generic-kdp` (the Next artifact copied 1:1 into `libksudnext.so`),
- `kernelsu/ksud-next-dm3q-S9180ZHS8FZF5-kdp` (the broken FZF5 pair — kept for reference; **do not bundle**),
- `kernelsu/patches/KernelSU-Next-v3.4.2-samsung-kdp-rkp-defex.patch` / `KernelSU-v3.3.0-samsung-kdp-rkp-defex.patch` (the Samsung deltas; **NO_PATCH_TEXT + kretprobe fallback**, KDP via `prepare_ro_creds`/`kdp_assign_pgd` workqueue, DEFEX cred re-sync, SELinux-hide kprobes),
- `kernelsu/README.md` (Samsung build records; FZG1 loader "preferred/proven loader" per line ~471),
- `support/kernelsu-generic.json` (feed row for `ksud-next-generic-kdp`),
- `docs/SM-S9180-S9180ZHU8GZIF.md` (this port's campaign record, updated through run8).

The APK is currently **dual-mode**: both loaders are inside one APK; the spinner + patch 1 selects which one runs.

---

## 8. Build & run — reproduction recipe

### 8.1 Host environment (as built on this host, 2026-11-17)

- Linux x86_64; Android SDK at **`/usr/lib/android-sdk`** was used for the on-host build (per the generated `app/.cxx/.../android_gradle_build.json`, which cites `/usr/lib/android-sdk/{cmake/3.22.1,ndk/28.2.13676358}`), with:
  - `platforms/android-36`, `build-tools/36.0.0`,
  - **NDK `28.2.13676358`** (i.e. **r28c**) — cited by the build metadata; AGP's default SDK at `~/Android/Sdk` (also present on this host, with `platforms/android-37.0`, NDKs r28c and r29, cmake 3.22.1) would equally resolve. `ANDROID_HOME` was unset in the shell; if reproducing elsewhere, pin `sdk.dir` in `local.properties` or export `ANDROID_HOME` to wherever android-36 lives.
  - CMake **3.22.1** + ninja at `…/cmake/3.22.1`,
  - JDK: toolchain **21** pinned in `gradle/gradle-daemon-jvm.properties` (auto-provisioned via foojay; host JDK 25 also present but the Gradle daemon JVM is 21); CI also uses temurin 21 (`.github/workflows/build.yml`),
 HEAD
  - Gradle wrapper **9.9.2** (`gradle/wrapper/gradle-wrapper.properties`, distribution SHA-256 pinned), AGP **9.5.0** declared in `gradle/libs.versions.toml` and consumed via the version-catalog alias — no root pin, so the catalog value is effective (§6 note).
- `signing.properties` at repo root with `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` (**do not commit; it is gitignored**) is **optional at configuration time**. When it is absent, `assembleRelease` produces an **unsigned** Release (Gradle only warns; it no longer throws during configuration); such an APK builds but cannot be installed over an already installed signed build. When present but incomplete, it fails closed with a readable configuration error. **Debug** is always signed with the standard Android debug keystore via `signingConfigs.getByName("debug")` — it does **not** read `signing.properties`. For local `make`, `SIGN_PASS` / `SIGN_ALIAS` must be **exported in the environment**; command-line assignments (`make SIGN_PASS=…`) are rejected. In CI the four `KEYSTORE*` secrets are confined to the protected `release` environment and referenced only on `v*` tag pushes; PR/branch/manual builds use no secrets and produce a **debug-keystore-signed Debug + unsigned Release** (no release signing on those events). The `KEYSTORE` secret/environment value must be a **base64-encoded JKS** (e.g. `base64 -w0 keystore.jks`): the CI writer base64-decodes it and validates the JKS before publishing signing material.
- NDK sysroot builds: `splicehelper` is compiled freestanding at CMake time (`-nodefaultlibs -nostartfiles -ffreestanding -static`).
- LKM rebuild (**required** — the `.ko` is not committed): `cd lkm && make KDIR=<kdir> LLVM=1` (or CI workflow `.github/workflows/build.yml`, which uses digest-pinned `ghcr.io/ylarod/ddk-min:<kmi>-20260828@sha256:…` containers). Top-level `Makefile` runs exactly **one** podman DDK build for `android13-5.15` (no eight-KMI loop); the image reference is derived from CI by `scripts/ddk-image.sh` and is digest-pinned, and the container runs with `--network=none`, `--cap-drop=ALL` and `no-new-privileges`, then `:app:assembleRelease`.
- `lkm/dfroot.ko` is intentionally GPL with `module_init` returning `-ENODEV`; §4 step (5).

### 8.2 Build

```sh
git clone https://github.com/samson910022/DFRoot-GZIF.git && cd DFRoot-GZIF
./gradlew :app:assembleRelease        # or the top-level Makefile (needs podman + keystore)
# → app/build/outputs/apk/release/DFRoot_4.8-gzif.apk
```

### 8.3 Install & run (device)

1. Enable **Developer options → USB debugging** (needed for adb verification; not required for the exploit itself). Connect the unit; authorize the host's RSA key.
2. `adb install -r app/build/outputs/apk/release/DFRoot_4.8-gzif.apk`
3. Open **DFRoot GZIF** (launcher). Confirm the spinner shows your installed SU manager (e.g. KernelSU-Next — the only one hardware-validated here). The spinner is built from **installed supported packages** (`MainActivity.java:77-88`); if it refuses "Select a SU Manager", no supported manager package is installed. Preflight checks the app's **own pinned bundled loader** by SHA-256 (`RunPreflight.java:150-162`) — the manager APK's `nativeLibraryDir` is not consulted.
4. Press **LAUNCH ROOT**.
5. Verify root (uid 2000 shell via adb):
   ```sh
   su -c id          # expect: uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
   su -v             # 3.3.0:KernelSU   (run6)   |   3.4.2:KernelSU   (run8)
   getenforce        # Enforcing
   cat /proc/modules | grep kernelsu   # kernelsu ... Live
   ```
   `/dev/dfm6` (`ksud start: SUCCESS`) is only a **progress** diagnostic, not a
   root or success verification; native returns only after the run-scoped
   terminal marker (see the "Result contract" section of `README.md`).
6. **After every reboot: re-run LAUNCH ROOT.** There is no persistence. Do **not** enable "Start on Boot" or "Auto Soft Reboot" until you have tested them (see §9.6 / §10).

### 8.4 What the app does each run (compressed)

Fresh boot → press LAUNCH ROOT → (a) side-effect-free preflight validates the
exact manager, pinned bundled loader, owner user, firmware, and assets; the
coordinator admits one run and the Direct Boot-aware foreground service owns
process lifetime, (b) the immutable, versioned run-config artifact is published
and `libdfroot.so` is exec'd with the IpSec SPI/key args plus `--run-config`,
(c) `crash_dump64` + vendor tflite + `libc++.so` get patched in page cache,
(d) `createOrphanProcess()` arms the init-tid-1 hook, (e) kernel UMH spawns
`bootstrap`, which reads only the run-config artifact, (f) `bootstrap` hardens
`/data/local/tmp` and stages `.ksud-stage`, (g) Samsung-patched ksud renames it
to `/data/adb/ksud` and `init_module()`s its embedded
`android13-5.15_kernelsu.ko`, (h) `/dev/dfm6` prints the owner-visible
**progress** line **`ksud start: SUCCESS`**, and (i) native returns only after the
**run-scoped terminal marker** (`0`/`1`/`4`/`6`/`7`) is observed — a reader
deadline expiry is `7`, indeterminate. Activity rotation or destruction at any
point detaches observation but does not cancel the run.

---

## 9. Troubleshooting

### 9.1 LAUNCH ROOT button is disabled
Current code: the button is enabled only by `MainActivity.isRunEligible()`
(`MainActivity.java:219`) — a confirmed `RunConfig` with a selected SU
manager, controls writable, and an idle/terminal `RunCoordinator`.

Separately, if `/dev/df` exists (`HookMarkerProbe.java:14`) the exploit was
already attempted on **this** boot: `RunOrchestrator.run()` short-circuits to
`RunResult.alreadyHooked()` before preflight (`RunOrchestrator.java:17-23`, via
`ExploitRunner.RunSeams.isHookPresent()` at `ExploitRunner.java:222-224`). `/dev`
is devtmpfs and is wiped at every boot, so this cannot be residue from a previous
one. Reboot to re-arm. Since R3.6 the message reads
`DFRoot: already attempted/hooked; root status unverified`: the SUCCESS code
and no-reentry guard are unchanged, and the wording neither claims `SUCCESS`
nor implies that root was verified. A separate read-only availability view now
surfaces the authoritative preflight reason ("Run unavailable: …") without
starting, staging, or substituting anything.

### 9.2 Toast "DFRoot: Error - ksud nonzero exit" / log `ksud start: ERROR - ksud nonzero exit`
Historical field semantics (2026-11-17 run ledger): `/dev/dfme2` exists,
`/dev/dfm6` does not (the old `run_ctx` branch). In current code all global
markers — including `dfme2` and `dfm6` — are **progress only**; the authoritative
outcome is the run-scoped terminal marker mapped to rc=0/1/4/6/7. Plain
bootstrap failures publish rc=1, but a late-load wait timeout, an unobserved
child exit, an unconfirmed publication, or reader deadline expiry is **rc=7
indeterminate** (trusted-caller recovery required; no in-app UI, §17), never a
definitive success/failure.
This section's
causes are retained from the historical hardware runs and have **not** been
re-validated against the current architecture. Causes seen in the field, in
probability order:

1. **Bundled Next loader with wrong embedded KMI asset name** (the 17:00 class; `libksudnext.so` embeds `android13-5.15.189_kernelsu.ko` only). Detect: `strings app/src/main/jniLibs/arm64-v8a/libksudnext.so | grep _kernelsu.ko` from host. **Fix:** keep `libksudnext.so` = `ksud-next-generic-kdp` (embeds `android13-5.15_kernelsu.ko`).
2. **`/data/adb` missing / `.ksud-stage` not created / rename failed** (run4 class). Detect on device: `ls -l /data/local/tmp/.ksud-stage /data/adb/ksud` — `.ksud-stage` gone + `/data/adb/ksud` present = staging step succeeded and the failure was later; both absent = `cp` failed (e.g. cross-device? `/data` full?); both present = ksud never consumed the stage (should not happen on this code path — re-check with full-tag logcat).
3. **Samsung-patched module rejected by the kernel after all** (unproven on this firmware for the *class* of builds in this APK, but the FZG1 classic did load — run6). If you see this with a *rebuilt* module, capture unfiltered logcat and `dropbox` for a new `SYSTEM_LAST_KMSG_*_KP`.
4. **ksud logs only under the other tag.** The capture must not filter to exact `KernelSU` — Next logs under **`KernelSU Next`** (with a space — requires quoting in shell). If the evidence shows a device-alive boot with *no* `KernelSU Next` lines at all and `dfme2` set, suspect either the tag filter or a death before `android_logger::init_once`; both are consistent with "asset miss".
5. If the failure is **silent in logcat**, the ksud stderr went to `/dev/null` by construction (kernel UMH stdio). The only surviving ksud diagnostics are in logcat. Capture with:
   ```sh
   adb logcat -d | grep -E 'KernelSU Next|KernelSU|dfroot'
   ```

### 9.3 SELinux Permissive after a run
`getenforce` → `Permissive` means the **exploit executed** (dfroot.ko flipped `selinux_state`) but does **not** mean root was obtained. In the 17:00 failure (`next-failure-evidence.txt:3`) the device was Permissive and KSU was **not** resident. Permissive persists until reboot. Historically, `/dev/dfm6` was the late-load progress check; in current code it is progress only, so use the final result and `su -c id` / `su -v` as the oracle. No root probe was added to the app.

### 9.4 Manager shows "Not installed"
The KernelSU / KernelSU-Next manager UI reads the KSU driver fd; no module resident ⇒ "Not installed". This follows DFRoot "SUCCESS" automatically on stock-panic runs (runs 2/3 — no root, manager says not installed) and on the 17:00 asset-name failure. It is a *symptom*, not a diagnosis; use §9.2.

### 9.5 Classic manager (`me.weishu.kernelsu`) is unusable on this build — expected
Two deterministic **app-side** defects, independent of root:
- **SIGABRT** — `Check failed: system_class_loader != nullptr … java.lang.SecurityException: Writable dex file '/data/user_de/0/me.weishu.kernelsu/cache/main.jar' is not allowed.'` (Android 14+ ReadOnlyJavaDcl W^X for dex; uid 10052, `app_process64`, `/system/bin/app_process64`; dropbox `SYSTEM_TOMBSTONE` entries ~15:33:09, and identical on FZG1 — `manager-compat-analysis.md` §3).
- **SIGSYS (`SYS_SECCOMP`, arm64 syscall 142 = `reboot(2)`)** — the manager's `libksud.so feature check` probes die: `x0=0xdeadbeef x1=0xcafebabe`. Unlike the init-context daemon (which runs with `Seccomp: 0`), the probes run under the app's zygote seccomp-bpf filter and are killed before any KSU hook can answer on fresh boots (`manager-compat-analysis.md` §4).
The classic manager therefore cannot manage root on this firmware **even on a fully working day**. Use KernelSU-Next as the UI, or ignore the manager UI entirely and use `adb shell su -c id` as the oracle. (The *classic* loader binaries bundled in the APK are unaffected — they were never executed as an app.)

### 9.6 Auto Soft Reboot / Start on Boot
- **Soft reboot is experimental and unvalidated** on GZIF. The historical
  Auto Soft Reboot path triggered a **full hard reboot once** on this model
  (bootstrap's soft-reboot command → `ksud soft-reboot`), documented in the
  campaign record §6.6 ("a full kernel reboot — /proc/uptime resets, Enforcing
  remains"); upstream FAQ points the same way (diabl0w Discussion #69).
  Current code still issues the command but bounds its wait (30 s), reports
  rc=4 only on an **observed** command success and rc=6 on an observed failure,
  maps a timeout/unobserved exit to **rc=7 indeterminate**, and **never
  force-kills** the child. Unattended boot soft reboot is **disabled** regardless
  of manual acknowledgement, and no soft-reboot hardware validation was
  performed for either 2026-11-25 revision. Do not enable it on this device.
- **Start on Boot** re-runs the whole chain at most **once per boot** via a durable
  `Settings.Global.BOOT_COUNT` claim in device-protected storage; missing/failed
  claims abort, and the foreground service returns `START_NOT_STICKY` so a
  process death never replays indeterminate work. An unconfirmed attempt is a
  separate durable barrier: it blocks manual **and** boot admission in a new
  process until a trusted caller clears the barrier (§17), regardless of boot
  count; boot claims are suppressed while it is unconfirmed. **OFF is reachable in every state**
  (including configuration ERROR and active/indeterminate operations) without
  touching the current operation; turning **ON** still requires verified
  configuration, an idle coordinator, and consent. The claim re-checks the
  `BootReceiver` component state at claim time and fails closed on
  unreadable/default state; a rejected component change is shown in the UI with
  the state actually applied. Risks: (a) a broken KSU module can bootloop —
  mitigate with Safe Mode (hold Vol-Down), DFRoot Settings → "Disable KSU
  Modules", or uninstalling DFRoot; (b) **foreground-service start eligibility
  from boot requires separate hardware validation** and was not validated here;
  (c) treating `BOOT_COUNT` as a new-boot boundary still requires owner
  acknowledgement — see §16. Leave off unless you have a specific reason and
  recovery plan.
- `BootReceiver` ships disabled by default (manifest `android:enabled="false"`)
  and is toggled by `SettingsFragment` through the PackageManager component
  setting; soft reboot uses the versioned, acknowledged `soft_reboot` setting in
  `SettingsFragment`.

### 9.7 Other failure modes & logcat tags
- Tags to capture: **`dfroot`** (exploit + app; line markers `* app version`, `=== setup ===`, `=== exploit ===`, `=== init ===`, `=== cleanup ===`), **`KernelSU`** (classic ksud), **`KernelSU Next`** (Next ksud), and (for manager UX) the manager's own tags. Always capture both KSU tags or none (`next-failure-evidence.txt` is a negative example).
- "DFRoot: Error - native setup/timeout (see logcat & dmesg)" (rc=2) → native
  `setup()` (argv/run-config/patch-input validation) failed **before any
  operation started**. It is no longer used for reader timeout: the reader polls
  in 10 ms steps up to the shared 100 000 ms budget and reports **rc=7
  indeterminate** ("outcome indeterminate (see output)") if no confirmed marker
  appears; privileged children may still be running. Look at `dfroot` lines;
  `dmesg` requires root (unavailable exactly when you need it).
- Log buffer size Off → DFRoot refuses ("Log buffer size is set to Off") — set it to anything else in Developer Options.
- `/dev/df` lingering after a failed run: press the button again is refused — **reboot is the only re-arm**.
- Intermittent: `Failed to set feature 5: ksuctl failed: Operation not supported on transport endpoint (os error 95)` (seen run8) — non-fatal; KSU continued and loaded 5 other features. Do not chase it.
- `KernelSU version: 0` lines are expected on fresh boots (nothing loaded when the probe runs) — not an error.
- If modules fail to load/bootloop after a successful root: DFRoot Settings → **Disable KSU Modules** (bootstrap honors it via `/data/adb/modules/*/disable` before ksud runs), or boot into Safe Mode, or uninstall the offending module, or remove DFRoot.

### 9.8 Forensic triage after a fatal run (KP)
The device never captures panic text (ABL: `Failed to get KlogOffset, Not Found` → `SamsungLogFlush KlogOffset:0x0`; `[[TRUNCATED]]`; `PonReason.HARD_RESET=1`; `Bootloader: S9180ZHU8GZIF`). Post-mortem sources:
- `dropbox` entries `SYSTEM_LAST_KMSG_<n>_YYYYMMDD_HHMMSS_KP` — only XBL/ABL text; `PonReason.HARD_RESET=1` confirms panic vs watchdog;
- DFRoot app log (`dfroot` tag) for exactly how far the chain got;
- `adb shell cat /proc/last_kmsg` — empty/absent on Samsung KP boots (do not expect it).

---

## 10. Safety & limitations

- **Volatile root.** Nothing persists across reboot — not the KSU module, not the SELinux flip, not `su`, not DFRoot's `/dev/df` mutex. Reboot = clean slate (Enforcing). To re-root: open the app, LAUNCH ROOT, verify.
- **Not persistent, not boot-image.** No bootloader unlock, no boot image/vbmeta/`dtbo` touch, no Knox counter increment **by this port** (but: any crash dump flagging, warranty, or OTA integrity consequences of running an exploit are the user's own responsibility; Knox `Warranty Void` is not set by this process, but Samsung may classify unexpected behavior per its own terms — do not take this as legal advice).
- **Kernel panic risk is real on this build.** Stock vanilla KSU/KSUN modules panic on 34343818 (two runs, two managers). The Samsung-patched loaders in this APK survived twice — but **kernel exploits and LKM-based root are inherently unstable**: no uptime guarantee, no module-signature chain to lean on, and each cold boot re-exposes the same attack surface.
- **One-time per boot.** `/dev/df` is armed once per boot and the app refuses
  re-runs; unattended boot additionally claims `BOOT_COUNT` durably so at most
  one boot attempt occurs. A hung or panicked attempt costs a hard reset.
- **Incompatibility:** DFRoot is known on other builds to break after certain updates; modules (Lygisk/LSPosed-class) can themselves cause crashes/bootloops — use "Disable KSU Modules" liberally. **Auto Soft Reboot caused one full hard reboot on this model — treat it as a live risk, not a convenience.**
- **CVE-2026-43284 could be patched at any time** (it is listed High in SMR-Nov-2026) — after that, this chain is dead. Keep OEM/firmware changelog hygiene in mind before updating.
- **This port is not upstream.** It is an uncommitted local working tree on top of `diabl0w/DFRoot @ 7a6513a`. Pulling upstream or re-cloning loses the two patches and the bundled loaders. Do not push to `origin` (which points at `github.com/diabl0w/DFRoot`) — fork if you need to publish.
- **Legality/ethics:** only on your own device; the exploit patches page-cache contents of files the system executes. It does not unlock anything, and it violates no signature checks *because it does not need to* — but Samsung's warranty/Knox terms are your affair.
- **Verification hygiene:** never trust the on-screen toast. Verify with the §8.3 oracle each time, on every boot.

---

## 11. Evidence locator (host paths, line refs — logs are *not* inlined)

Logs live host-side under `GZIF-port/`. They are large (up to 142k lines) and contain private identifiers (BLE device addresses, package usage, network details) — **reference line ranges, never copy wholesale**, and do not commit them anywhere.

| File | What it records | Key lines/ranges |
|---|---|---|
| `run2_logcat.txt` | classic-KSU run (KP194) | identity `:1178-1183`; LateLoad `:1673`; late-load start `:1675`; Detected KMI `:1678`; **SUCCESS printed before module line** `:1679`; `Loading kernelsu.ko` `:1688`; last line `:1851` |
| `run3_logcat.txt` | Next-KSU run (KP195) | identity `:1289-1294`; tags `KernelSU Next` `:1529-1705`; SUCCESS `:1535`; `Loading kernelsu.ko` `:1544`; `Reading kernel log` `:1641`; last `:1705` |
| `run4_logcat.txt` | Samsung classic loader, pre-staging-patch (non-fatal) | patch steps `:30363-30532`; LateLoad `:30779`; **`Failed to stage ksud`** `:30782-30785`; ERROR `:30788` |
| `run5_logcat.txt` | app launch only; no exploit | — |
| `run6_logcat.txt` | **classic loader FULL SUCCESS** | `LateLoad … package_name: "com.rifsxd.ksunext"` `:25747`; triggered `:25748`; Detected KMI `:25753`; Loading `:25767`; kmsg `:25899`; **loaded successfully!** `:26590`; after load_module `:26592`; **SUCCESS** `:26980`; `adbd` marker probe `:45621` |
| `run8_logcat.txt` | **Next loader FULL SUCCESS** | identity `:3509-3515`; setup/patch `:3715-3830`; dfroot.ko write `:7772`; libc++ patch `:7775-7782`; init/hook trigger `:8310-8860`; bootstrap `:8911-8948`; **LateLoad `:8968`** (`"com.rifsxd.ksunext"`, `KernelSU Next` tag); **loaded successfully! `:9739`**; `after load_module … u:r:ksu:s0` `:9740`; sepolicy loads `:9747-9871`; feature-5 error `:9850`; **SUCCESS `:9881`**; cleanup `:9883-9887`; post-run manager probes `:15152+` |
| `next-failure-evidence.txt` | 17:00 Next FZF5-pair failure (markers + dfroot log) | device state (Permissive) `:3`; markers `:5-16` (`dfme2: yes`, `dfm6: -`); dfroot log `:18-58`; **ERROR `:52`**; tag-filtered capture `:59-86` (0 `KernelSU Next` hits) |
| `next-failure-analysis.md` | static root-cause of the 17:00 failure | markers map §1.1; binary asset table §1.3; staging contract §3.2; **KMI asset-name mismatch §3.3-3.4**; verdict (b-pre-load) §4; fix options (i)-(v) §5 |
| `next-loader-codepath.md` | loader-for-loader comparison (classic vs Next FZF5) | binary fingerprint table §1; FZG1 staging contract §2; TIMELINE §5; verdict + candidate swap (a/b) §6; evidence ledger §7 |
| `manager-compat-analysis.md` | panic locus + manager-independence (runs 2/3) | crash signatures §3-5; flow §6; **manager-independence table §6.3**; FZF5-vs-FZG1 §9; recommendation §11 |
| `samsung-lateload-forensics.md` | why stock modules panic; what Samsung builds do | Samsung commit anatomy §1.1; diabl0w fork `samsung-v2.2` §2.1; **mechanism §6.1**; snothin SCS/KCFI lesson §5; verdict §6 |
| `oneui9-rooting-landscape.md` | ecosystem survey (post-426 GENs; S25U DDZJB; sibling AGZIF success) | §2 matrix; §4 panic-class consensus; §5 candidates; §6 SMR notes (43499 High Oct; 43284 High Nov); §8 bottom line |
| `rmgnext-feasibility.md` | why Root-My-Galaxy-Next won't help here | §2 chain identical to DFRoot; §3 no GZIF catalog rows; §5 issues (incl. #55 twin build); §7 verdict |
| `crash-analysis-run2.md` | co-located forensic author for runs 2/3 | cross-check only; every load-bearing fact re-verified here against raw files |
| `dropbox_all.txt` | all dropbox entries (18987 lines) | KP189/191-195 KP-flagged resets; classic-manager SIGABRT `:15085-15154`; SIGSYS battery `:15433-17517` |
| `kmsg_195.txt` | run3's KP boot dump (XBL-only) | dropbox header `:1-15`; `PonReason.HARD_RESET = 1` `:403`; `Failed to get KlogOffset` `:1047-1049`; `Last boot reason: reboot` `:1069`; `Hyp version: 1` (present) `:702` |
| `dfroot_screen.png` | screenshot of the app output at failure (not inlined) | — |
| `DFRoot-fzg1/` | the ported tool (working tree, uncommitted) | `app/`, `lkm/`, `gradle/` |
| Payloads repo `docs/SM-S9180-S9180ZHU8GZIF.md` | **campaign record** — start here | §1 identity; §3 CVE matrix; §6.5 dry-run; §6.6 (first "SUCCESS" then hard reboot); **§6.7 FULL ROOT ACHIEVED 16:49**; **§6.8 FULL ROOT ACHIEVED 17:53 (Next)** |
| Payloads repo `kernelsu/README.md` | Samsung KSU build records + FZG1 loader provenance | v3.4.2 generic row (line ~12); FZG1 loader row (line ~471) |
| Payloads repo `support/kernelsu-generic.json` | Next generic feed row (daemon 3.4.2, `679504dd…`) | lines ~36-46 |

---

## 12. Reproduction confidence — what is device-validated vs unverified

| # | Claim | Status | Evidence |
|---|---|---|---|
| 1 | Exploit chain runs end-to-end on this firmware up through UMH/bootstrap (all `/dev/dfm*` markers) | **Device-validated** (5+ runs; both KP and non-KP) | `run2:1178`, `run3`, `run4:30303-30793`, `run6:25747-26980`, `run8:3509-9887` |
| 2 | Samsung-patched classic loader (FZG1-validated) loads `kernelsu.ko`; root granted; SELinux stays Enforcing | **Device-validated** — 1 run (run6) | `run6_logcat.txt:25747-26980`; `docs/SM-S9180-S9180ZHU8GZIF.md` §6.7 (`su -c id`, `su -v`, `getenforce`, `/proc/modules`) |
| 3 | Samsung-patched generic Next 3.4.2 loader loads `kernelsu.ko`; root granted; SELinux stays Enforcing | **Device-validated** — 1 run (run8) | `run8_logcat.txt:8968-9887`; campaign doc §6.8 |
| 4 | Stock vanilla classic v3.3.0 **panics** inside/around `init_module()` | **Device-validated** — 1 run (run2, KP194) | `run2_logcat.txt:1673-1851`; `kmsg_195.txt` (sibling); `manager-compat-analysis.md` §6-7 |
| 5 | Stock vanilla Next v3.4.2 **panics** at the identical locus | **Device-validated** — 1 run (run3, KP195) | `run3_logcat.txt:1529-1705`; `kmsg_195.txt:8,403,1047-1069` |
| 6 | FZF5-pair Next 3.4.1 loader fails pre-load on KMI `android13-5.15` asset miss (no panic) | **Device-validated** — 1 run (17:00) + static binary proof | `next-failure-evidence.txt:3-86`; `next-failure-analysis.md` §3.3-4, §6 |
| 7 | run4 failure (`Failed to stage ksud`) → fixed by patch 2 → success (run6) | **Device-validated** | `run4_logcat.txt:30782-30788`; `bootstrap.c:222-237`; run6 |
| 8 | CVE-2026-43499 patched; CVE-2026-98260 present-unported; CVE-2026-43284 open | **Binary-verified** (kernel Image triage, static) | `docs/SM-S9180-S9180ZHU8GZIF.md` §3, §6.5 |
| 9 | IpSecManager dry-run (SPI/encap/transform/apply/remove) all OK | **Device-validated** (read-only probe APK; not root) | campaign doc §6.5 (`C1-…C4-REMOVE-OK…DRYRUN-COMPLETE`) |
| 10 | DFRoot "SUCCESS" ≠ root obtained (su Oracle required) | **Observed, mechanism understood** | run2 `:1679` (SUCCESS) vs `:1688` (Loading…); run3 `:1535` vs `:1544`; campaign doc §6.6 note |
| 11 | End-to-end oracle `su -c id` → uid 0 / `su -v` / `getenforce` | **Device-validated** — both loaders (run6, run8) | campaign doc §6.7, §6.8 (UID 0; Enforcing; `3.3.0`/`3.4.2` stamps) |
| 12 | `me.weishu.kernelsu` manager path end-to-end | **Not separately validated** (and manager app is userspace-broken; §9.5) | inference from patch 1 only — **unverified** |
| 13 | Auto Soft Reboot behavior on this model | **Unverified/unstable** — one full hard reboot observed | campaign doc §6.6; `README.md` FAQ; §10 |
| 14 | Start on Boot stability | **Unverified** — never run long-term | — |
| 15 | Multi-boot / multi-run reliability (7-day soak) | **Unverified** — exactly 2 successful cold runs to date | — |
| 16 | Exact panic mechanism for runs 2/3 (EL2? KDP? DEFEX? KCFI/SCS mismatch?) | **UNVERIFIED — panic text absent from every artifact** | `manager-compat-analysis.md` §7, §10; `kmsg_195.txt` (XBL-only, `KlogOffset:0x0`) |
| 17 | KDP/RKP/DEFEX enforcement semantics on 34343818 (vs FZG1's 33413713) | **Unmeasured** | campaign doc §5 ("unmeasured"); `samsung-lateload-forensics.md` §6.5 |
| 18 | sepolicy/module interop over long periods (feature 5 error, LSPosed/Zygisk family) | **Partially observed** (one non-fatal feature error; modules' sepolicy loaded) | `run8_logcat.txt:9747-9871` |
| 19 | CVE-2026-98260-dm3q port | **Not attempted** | — |
| 20 | "Both managers" success (classic manager UI + Next UI) | **Half-validated**: both manager APKs present and exercised as *failures*; both **loader lineages** validated as successes (run6 = classic 3.3.0 lineage; run8 = Next 3.4.2 lineage). `me.weishu.kernelsu`-selected success run: **not performed** | §5.1 |

Confidence rollup: **the chain works on this firmware today; it has survived exactly two cold-boot runs with the Samsung-patched loaders; every other CVE/module/persistence aspect remains unverified.**

---

## 13. Open items / next steps

1. **Soak test**: ≥10 cold boots of the run6/run8 procedure; record `/dev/df*` markers, `su -v`, `getenforce` each time. Needed before any "reliable root" claim.
2. **Start on Boot**: if desired, test one boot with DFRoot Settings → Start on Boot enabled, with `Disable KSU Modules` pre-armed, and Keep a recovery (download mode + stock images) at hand. Do not test Auto Soft Reboot without the same precautions.
3. **Exact-source GZIF KSU build** (optional, high effort): `KernelSU-Next-v3.4.2-samsung-kdp-rkp-defex.patch` applied to a v3.4.2 source tree, built for `android13-5.15` with vermagic `5.15.197-…34343818-abS9180ZHU8GZIF` and/or relying on ksuinit's vermagic rewrite; confirms vs disproves "exact-source-ness" as the differentiator vs the generic build (the generic already works — the exact build would only add margin).
4. **CVE-2026-98260 dm3q port** (parked): bug present, unported; largest potential follow-up if 43284 is patched in a future SMR.
5. **Track SMR-December-2026** (43284 listed High in SMR-Nov; Samsung may ship the fix in the next round — after which this chain dies, per §3).
6. **Publishability**: the working tree is uncommitted; decide (a) commit DFRoot-fzg1 locally (never push to diabl0w's upstream), or (b) vendor it into a fork, before the knowledge walks away.
7. **The classic manager UI**: if the user ever wants `me.weishu.kernelsu` as the *app* again, the Writable-dex defect must be fixed upstream (or a patched classic APK shipped); not a DFRoot problem.
8. **(Optional) capture `KernelSU Next` logs properly** in future evidence files: `adb logcat -d | grep -E 'KernelSU Next|KernelSU|dfroot'` — unfiltered-or-dual-tag always (see §9.2.4).

---

## 14. Summary of this document (15 lines)

1. SM-S9180 `S9180ZHU8GZIF` (dm3q, kalama), Android 17 / One UI 9.0, SPL 2026-10-05, kernel `5.15.197-android13-8-34343818-abS9180ZHU8GZIF`, `CP2A.260605.016`, changelist 34343818, KMI `android13-5.15`, SDK 37 (target built SDK 36).
2. CVE-2026-43499 is **patched** (binary-verified); CVE-2026-98260 present but **unported**; CVE-2026-43284 **open** and is the working route; listed High in SMR-Nov-2026.
3. The chain: IpSecManager ESP transform → `crash_dump64` splicehelper bridge (page-cache CBC write) → `dfroot.ko` staged into `/vendor/lib64/AIAwbModel_v2.2.tflite` → `libc++.so` sentry hook → init tid=1 → `vendor_modprobe` insmod → `selinux_state=false` + DEFEX/AVC neutralized → UMH → `bootstrap` → `.ksud-stage`.
4. The Samsung-patched ksud renames `.ksud-stage`→`/data/adb/ksud` itself, then `init_module()`s its embedded `android13-5.15_kernelsu.ko` (ksuinit vermagic rewrite) — a no-patch-text, kretprobe-fallback module (KDP workqueue creds, DEFEX cred re-sync, RKP never touched).
5. Stock vanilla classic v3.3.0 (run2, KP194) and stock vanilla Next v3.4.2 (run3, KP195) both **panicked** at module load; manager-independence proven; the classic manager is additionally userspace-broken (Writable-dex SIGABRT, seccomp SIGSYS).
6. The pre-packaged Next FZF5 daemon shipped as `libksudnext.so` could never load (embeds `android13-5.15.189_kernelsu.ko`; runtime KMI is `android13-5.15`) — 52 ms exit, no panic; fixed by shipping `ksud-next-generic-kdp` (3.4.2, generic KMIs) instead (17:00 evidence → swapped 17:50 → run8 success).
7. run4 exposed the missing staging step; patch 2 (`mkdir /data/adb` + stage `/data/local/tmp/.ksud-stage`) fixed it; run6 validated end-to-end (`su -v` = `3.3.0:KernelSU`, Enforcing).
8. run8 validated the dual-mode APK with KernelSU-Next selected (`su -v` = `3.4.2:KernelSU`, Enforcing). Both KSU lineages now hardware-proven; both manager APKs present on the unit.

9. Patch 1 (historical) preferred bundled Samsung loaders by selected package; **current code has no fallback**: `me.weishu.kernelsu`→pinned `libksud.so`, `com.rifsxd.ksunext`→pinned `libksudnext.so`, anything else rejected by side-effect-free preflight (§15).
10. Patch 2 (historical) used `cp` to stage `/data/local/tmp/.ksud-stage`; **current code** uses hardened in-process staging (sticky bit, `O_EXCL|O_NOFOLLOW`, descriptor copy/fsync, mode restoration) and publishes run-scoped terminal markers.
11. Load-bearing artifacts: `app/src/main/jni/ko/dfroot-android13-5.15.ko` (13,504 B, DDK `android13-5.15`; built, not committed), `app/src/main/jniLibs/arm64-v8a/libksud.so` and `libksudnext.so` (aab76943…/679504dd…), and the APK `DFRoot_4.8-gzif.apk` (5.88 MB, `df.root`, "DFRoot GZIF").
12. Build: JDK 21 (toolchain-pinned), SDK 36, NDK **r28c** (`28.2.13676358`), CMake 3.22.1, Gradle wrapper **9.9.2** with AGP **9.5.0** via the version catalog → `./gradlew :app:assembleRelease`, `adb install -r`, LAUNCH ROOT — root is **volatile**, gone on reboot; re-run per boot.
13. Troubleshooting (historical markers, §9): `/dev/df` ⇒ button disabled; Permissive ⇒ exploit ran, KSU missing; `/dev/dfme2` ⇒ legacy ksud failure; Not installed ⇒ module never resident. **Current code:** all `/dev/dfm*` markers are progress only, including `/dev/dfm6` → `ksud start: SUCCESS`; the authoritative result is the run-scoped terminal marker (`0`/`1`/`4`/`6`/`7`), where `7` is indeterminate and requires trusted-caller recovery (§17). Dual-tag logcat capture is mandatory: `KernelSU` **and** `KernelSU Next`.
14. Limitations: volatile only; untested Auto Soft Reboot (hard reboot once) and Start on Boot; exploit could be closed by a Samsung patch at any time; no exact panic text exists for the two stock panics (XBL-only dumps).
15. Confidence: 2/2 recent cold runs fully successful (run6, run8); stock modules 2/2 panics; everything else (98260 port, long-term stability, `me.weishu.kernelsu`-selected run, soak testing) unverified — treat this file as the starting map, not the final word.

---

## 15. Current architecture (2026-11-25, PR #2) and residual risks

This section describes the **current locally committed tree on
`fix/app-correctness-safety` (not yet pushed)** and supersedes the stale
implementation details in §4/§6/§8.4/§9
described above. It is documentation only: no device, payload,
privileged-container, or APK-assembly validation was performed for this revision
(host verification only: 409 JVM tests, 113 host CTest cases, lint 0 errors,
`git diff --check` clean — see `CHANGELOG.md`).

### 15.1 Immutable configuration and durable writes
- `ConfigurationRepository` is the single writer of critical DE preferences.
  Writes run off the main thread and use checked `commit()`; a failed or
  unverified write moves the configuration into an ERROR/saving state and
  **blocks run admission** instead of launching with stale settings.
- `RunConfig` is an immutable snapshot: manager package, selected pinned
  bundled loader, soft-reboot decision, disable-modules decision, and
  environment/preflight evidence. UI, coordinator, and native all consume the
  same confirmed generation.
- Soft reboot uses a versioned durable acknowledgement: legacy enabled values
  are migrated to safe OFF, consent is a blocking modal, and unattended boot
  keeps soft reboot OFF even when manual acknowledgement is current.

### 15.2 RunCoordinator and Direct Boot-aware foreground service
- A process-scoped `RunCoordinator` atomically admits **one** operation, rejects
  duplicates rather than queueing, freezes the immutable `RunConfig`, and owns
  observation state/log/result. Destroying an Activity only detaches the
  observer; it never cancels or interrupts a started privileged operation.
- `ExploitService` is a Direct Boot-aware `specialUse` foreground service used
  for **both** manual and boot runs. It owns process lifetime, a notification
  channel, and a timed partial WakeLock held for the full session, and returns
  `START_NOT_STICKY` so indeterminate work is never silently replayed after
  process death.
- The child process is supervised with asynchronous stdout draining and a timed
  wait. On timeout/interruption the outcome is reported as indeterminate; a
  started privileged child is **not** forcibly killed, resources remain owned
  until actual exit, and there is no automatic retry.

### 15.3 Atomic run-config artifact
- Java publishes a versioned `run_config` artifact atomically (temp file,
  checked mode/rename, bounded size, strict key=value codec). Native bootstrap
  reads **only** this artifact; it no longer parses mutable SharedPreferences
  XML. Truncated, oversized, malformed, identity-changing, or unsupported-
  manager artifacts fail closed before any patch/staging work.
- The native payload receives the artifact path as a mandatory `--run-config`
  argument; host CTest covers the strict codec and native argv parsing.

### 15.4 Run-scoped terminal markers and SUCCESS
- Terminal markers are written only after bootstrap work finishes and are bound
  to the admitted operation ID (checked exclusive write, no-follow, regular
  file, `write`/`fsync`/`close` verified). Foreign IDs and malformed payloads
  are ignored; conflicting codes resolve to terminal error.
- Codes in the first repair round: `0` normal success, `1` bootstrap/terminal
  error (then including the 60 s late-load timeout and marker conflicts), `4`
  soft-reboot command success, `6` soft-reboot command failure. **Superseded by
  §16:** a late-load wait timeout is now rc=7 indeterminate, and rc=7 is a
  dedicated indeterminate channel; rc=2 is only a pre-operation `setup()`
  failure. The Java `NativeResultMapper` consumes these values with
  collision-free unknown handling.
- All legacy global `/dev/df*` markers — **including `/dev/dfm6` and its owner-
  visible `ksud start: SUCCESS` message** — are progress-only diagnostics.
  Native returns only after a run-scoped terminal marker; **the reader deadline
  (100 000 ms) now expires as rc=7 indeterminate**, not a definitive failure.
  No root probe is added to decide a result.

### 15.5 Hardened staging
- `loader_stage.c` replaces the old `system("cp …")`: it lstats and mode-saves
  `/data/local/tmp`, adds `S_ISVTX` with a checked chmod and re-lstat, rejects
  any pre-existing destination without following/unlinking it, opens the source
  `O_RDONLY|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK` (regular file only), opens the
  destination `O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC` with 0600, uses an
  EINTR-safe full-write loop with source identity/size rechecks, fsyncs,
  fchmods to 0755 only after the verified copy, and restores the directory mode
  after late-load regardless of outcome. Harmless host CTest fixtures cover
  symlink/FIFO/directory/pre-created/replacement cases.

### 15.6 Boot claim
- `BootReceiver` validates the action, calls `goAsync()`, and durably claims
  `Settings.Global.BOOT_COUNT` in separate DE preferences before starting the
  service. `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` share the claim; the
  first claims the boot and duplicates (including after process death) abort.
  Missing/non-positive counts, failed/throwing commits, and rejected worker
  submission abort and always `finish()` the `PendingResult`. The system
  `BOOT_COUNT` is never written, and a successful claim is not rolled back on
  foreground-start failure (no same-boot retry).

### 15.7 Residual risks and explicitly out of scope
- **Sticky bit does not protect against the `/data/local/tmp` owner (`shell`).**
  POSIX sticky-bit semantics still allow the directory owner to rename/delete
  entries, so the staging hardening is not a security boundary against a
  hostile shell user; `O_EXCL`/`O_NOFOLLOW`, descriptor verification, and
  dev/ino rechecks mitigate races but do not eliminate this trust assumption.
- **Foreground-service boot eligibility requires hardware validation.** The
  Direct Boot-aware `specialUse` service and its notification path are covered
  by JVM tests only; no cold-boot run was performed for this revision.
- **Soft reboot remains unvalidated and carries a hard-reboot risk.** The
  historical hard-reboot observation stands; even though the command is now
  bounded and rc=4/rc=6 are distinct, no device validation was done.
- **AES-key argv redesign remains out of scope.** Phase 1 hardened argv parsing
  (strict, range-checked `--encap-port`/`--sender-port`/`--spi`/`--aes-key` and
  mandatory `--run-config`), but the key's presence in argv was not redesigned.
- **Broad native patch-cleanup redesign remains out of scope.** The historical
  page-cache patch/cleanup flow is unchanged apart from the run-config, marker,
  and staging work described above.
- No APK was assembled because the gitignored `app/src/main/jni/ko/*.ko` files
  are absent and assembling them would require a privileged DDK container.

---

## 16. Re-review 1 repair round (2026-11-25) — architecture and open gates

This section covers the **new** repair round on `fix/app-correctness-safety`,
commits `6686973` → `a8003c2` (HEAD `a8003c2`), closing tracking findings
**R2-10..R2-16** plus the remaining parts of R2-02/03/04/07/09. It is
documentation analysis only: no device, payload, ADB, privileged container, or
APK assembly was performed. Host verification for this round: **484 JVM tests
(44 suites), 135 host CTest cases, lint 0 errors / 21 warnings**,
`git diff --check` clean, loader pins match, no generated/secret files tracked.
A fresh GitHub CI green run (including the negative pin fixture) is a
**POST-PUSH gate** and is not claimed. Where §15 described the first repair
round's contracts, §16 supersedes them as noted below.

### 16.1 Architecture changes in this round

- **Packaged-loader pin gate (R2-10, `6686973`).** Verification moved from a
  Gradle build script into supported `buildSrc` tasks so task configuration no
  longer fails on a non-static inner class; strict two-pin/one-APK checks remain
  and a deterministic clean/tampered synthetic ZIP fixture proves the check
  itself (a one-byte-tampered `libksud.so` must be rejected). CI gained the
  fixture job and a `:app:tasks --all` regression step.
- **Staged-entry identity (R2-11, `639a9d8`).** The created staged inode is
  pinned by a descriptor; cleanup verifies `fstat`/`lstat` identity before
  unlink and is conservative if the path was replaced: it never unlinks a
  replacement, still restores the directory mode, and reports a diagnostic.
  The write pin is converted through `/proc/self/fd/<n>` to a non-writing pin
  before `install()` returns so the loader is exec-able without `ETXTBSY`;
  failure to convert fails closed.
- **Atomic terminal publication (R2-14, `12a633b`).** A complete marker payload
  is written to an unpredictable same-directory temp (`O_EXCL|O_NOFOLLOW`, full
  EINTR-safe write, fsync, fchmod 0644, checked close) and atomically linked to
  the run-scoped final path, then the temp is unlinked and the directory
  fsynced. A **pre-link** failure leaves no final marker and no temp debris. On
  an unconfirmed **post-link** failure (temp unlink or directory fsync) the
  indeterminate channel is published **first**, then the intended marker is
  retracted, and rc=7 outranks success and conflicts in the classifier: no reader
  classifying after rc=7 exists can return SUCCESS. Because `link()` exposes the
  complete final path before cleanup/directory fsync can succeed, a reader
  polling in the brief post-link/pre-rc7 window **can** observe the intended
  marker as a confirmed verdict, and that observation cannot be retroactively
  revoked. We do **not** claim a failed publication is never observable as
  confirmed; we claim only that any result acted on after publication completes
  is indeterminate (writer rc=7, reader rc=7). rc=7 precedence and indeterminate
  ownership are unchanged.
- **Bounded, non-killing waits (R2-12 / partial R2-03, `ce781f8`).** Late-load
  and soft-reboot command waits share one 60 s late-load budget and never signal
  or kill a child. Observed exits map to rc=0/1 and rc=4/6; timeouts and
  unobserved exits map to rc=7. The reader's 100 000 ms budget covers writer
  worst case; its expiry is rc=7, not a definitive failure.
- **Durable attempt barrier (R2-13 / partial R2-02, `7b5397c`).** An
  `OperationAttempt` is persisted in device-protected storage before privileged
  work or `run_config` publication. A new process rejects manual and boot
  requests fail-closed until a trusted caller clears the barrier (§17),
  regardless of `BOOT_COUNT`; boot claims are suppressed while unconfirmed;
  clearing is compare-and-clear by operation ID.
- **Pre-admission uncertainty (R2-15 / partial R2-07, `7c1f39a`).** A pure
  decision helper plus a narrowly scoped reserved-identity scan turns a valid
  pre-admission rc=1 marker into rc=7 "completion unverified" **only** when no
  terminal marker exists for the admitted operation. Admitted markers (rc=0
  included) remain authoritative; stale/foreign markers can never become
  success.
- **Independent boot toggle and honesty (R2-15/16, `54cb000`, `a8003c2`).**
  Start-at-Boot OFF is reachable in ERROR and active/indeterminate states
  without touching the current operation; ON keeps its gates; the receiver
  component is re-checked at claim time and unreadable state fails closed.
  The already-hooked message dropped its root-verification claim (refined again
  in §17 to remove the bare `SUCCESS` wording), and a read-only availability
  diagnostic reuses the authoritative preflight plan in its own view.

### 16.2 Indeterminate / recovery policy (supersedes §15.4)

rc=7 means **completion was not confirmed**, not "the operation failed". It is
produced by: a writer timeout or unobserved child exit (both waits bounded and
non-killing), a failed or unconfirmed terminal publication, pre-admission
uncertainty with no admitted marker, and reader deadline expiry while
privileged children may still be alive. Java maps rc=7 only to
`RESULT_INDETERMINATE`; never to SUCCESS. `RunCoordinator` retains the resource
lease, frozen configuration, and single-flight rejection, and features are
locked while indeterminate. The **only** in-process exit is an explicit
single-claim trusted-caller recovery that also clears the durable barrier; as
of R3.6 (§17) it additionally refuses while `engine.run()` is still executing.
Persisted-attempt recovery is a post-process-death escape hatch, not an owner
feature, and there is no in-app recovery UI (§17). rc=2 is now only a
pre-operation `setup()` failure; a late-load timeout is **not** rc=1 anymore.

### 16.3 Hardware and release gates that remain open

None of the following was validated for either 2026-11-25 revision; they are
gates, not claims:

1. **Direct Boot foreground-service eligibility.** The `specialUse`
   Direct Boot-aware service, notification channel, and boot-start exemption are
   covered by JVM tests only; no cold boot was performed.
2. **Real soft-reboot safety.** The historical full hard reboot on this model
   still stands. Bounded waits and distinct rc=4/6/7 are code-level facts, not
   device evidence; do not enable soft reboot on GZIF.
3. **Sticky-bit owner limitation.** The sticky bit on `/data/local/tmp` does not
   defend against the directory owner (`shell`); descriptor identity checks
   mitigate but do not remove that trust assumption.
4. **Marker-root trust.** Terminal markers live under `/dev` (`RUN_STATUS_ROOT_DEFAULT`);
   the reader trusts that path/root for admitted operation IDs. Host tests inject
   harmless roots; no on-device marker-root trust/SELinux validation was done.
5. **AES key in argv.** Parsing was hardened and is range-checked, but the
   key's presence in the payload argv was not redesigned; it remains an
   accepted residual risk.
6. **Recovery UI delivery is out of scope.** The single-claim recovery API and
   durable barrier exist, but shipping an owner-facing recovery screen (and
   validating it on hardware) is not part of this round.
7. **`BOOT_COUNT` as a new-boot boundary needs owner acknowledgement.** The
   boot claim treats a strictly greater count as a new boot, but the hardware
   meaning of that boundary (and the owner's acceptance of it as a safe retry
   point) has not been acknowledged/validated on the device.
8. **Fresh GitHub CI green run, including the negative synthetic fixture, is a
   POST-PUSH gate.** It cannot be executed locally and is not claimed here.

The **2026-11-17 hardware ledger in §5/§11/§12 remains historical evidence only**
(two successful cold runs, run6/run8). It does not validate the §15 or §16
architecture, and no new device run was performed for either 2026-11-25 round.

---

## 17. Re-review 3.6 repair (2026-11-25) — recovery truth and honest success wording

Historical phase on `fix/app-correctness-safety` (HEAD `d32006e`), closing
**R2-19** and the remaining **R2-04** caveat; later committed as `1252b3d`.
Java-only: no native code, boot gate, attempt-store format, dependency,
device/ADB, payload, privileged container, UI threading, CI change, or APK
assembly.

### 17.1 Recovery truth

- `recoverIndeterminate(operationId)` remains a narrow trusted-caller API: no
  blind retry, no UI/intent/broadcast path, and no barrier clear based only on
  ID/state/acknowledgement. It still requires the exact in-process ID, a single
  claim, and a successful checked compare-and-clear before any lease/freeze
  release. Cleanup is exactly once (the attempt is marked cleared before
  `finish()` so no second clear strands it).
- INDETERMINATE is not proof that supervised work stopped. The coordinator now
  tracks `engineStarted` (set immediately before `engine.run()`) and
  `engineFinished` (set only in a `finally` after that call returns or throws).
  A supervision timeout/interruption callback publishes INDETERMINATE **without**
  setting `engineFinished`; a trusted caller with the correct ID is therefore
  **refused** while the engine is still blocked. A proven pre-effect rejection
  (engine never started) is still recoverable. Deterministic tests cover the
  blocked engine, post-return success, failed persistence, and two concurrent
  correct-ID callers where exactly one wins.
- `recoverPersistedAttempt(operationId, ownerAcknowledged)` is strictly
  post-process-death: it clears only an unconfirmed record loaded at
  initialization and never one published by this process. It keeps the
  acknowledgement, exact-ID and checked-clear gates, and cannot prove that
  external effects from a dead process actually stopped. It is a trusted-caller
  escape hatch, **not** an owner feature.
- **There is NO in-app recovery UI.** An ordinary user with an unconfirmed
  persisted attempt is intentionally locked out: manual and boot admission stay
  rejected until a trusted caller clears the barrier. Resetting app data only
  discards the durable barrier and is **NOT** evidence that privileged effects
  stopped — it is not a safe recovery procedure. A new `BOOT_COUNT` is
  diagnostic only, **not** proof of safety, and never authorizes clearing on
  its own.

### 17.2 Final-result wording

Bare `SUCCESS` is removed from final-result user messages: generic success is
`DFRoot: run completed — verify root status yourself` and already-hooked is
`DFRoot: already attempted/hooked; root status unverified`. Typed result
codes/types, the already-hooked SUCCESS code and no-reentry guard, the
`/dev/df` and `/dev/dfm6` progress strings, and the no-root-probe policy are
unchanged. Nothing in these messages claims root status was verified.

### 17.3 Host verification

`:app:testDebugUnitTest`: **554 tests / 45 suites, 0 failures / 0 errors /
0 skipped** (historical prior-round baseline). `:app:lintDebug`: **0 errors /
15 warnings**. Host CTest recorded then at **135/135** (superseded by §18:
**137/137**, then R4.3 in §19: **140/140**). `git diff --check`
clean. No hardware, device, ADB, payload,
privileged-container, cold-boot, soft-reboot or APK-assembly validation was
performed; a fresh GitHub CI green run is a POST-PUSH gate and is not claimed.

---

## 18. Third repair round (R3, 2026-11-25) — consolidated findings, verification and gates

This is the authoritative current record of the third repair round on
`fix/app-correctness-safety` (final local HEAD `1252b3d`). **§5/§11/§12/§14
remain the 2026-11-17 historical hardware ledger; §15–§17 remain historical
repair-phase records.** The branch was rebased mid-round onto merged **PR #1**
(`origin/master` `9f60e16`) and the PR #1 release/signing/policy CI contract was
retained and extended:

- `f6faf75` — preserved the PR #1 release/signing/policy jobs, extended the
  **closed** workflow allowlists for app-checks, loader-pin fixtures and
  packaged-pin verification, pinned all actions, moved the report check out of
  a forbidden heredoc and kept fail-closed uploads.
- `770d07c` — restored the JUnit/Robolectric test-only dependencies dropped by
  the PR #1 app build script and included `buildSrc` in the hermetic signing
  fixture so packaged-loader task definitions resolve.

### 18.1 Findings closed in this round (host evidence only)

| Finding | Commit | Resolution |
|---|---|---|
| R2-12 | `6ebf9e3` | Terminal classifier failures map to rc=7; one authoritative unverified-outcome predicate prevents timeout/interruption flags from hiding an unobserved exit; `RunCoordinator` retains ownership, frozen config, lease and the durable attempt on every unverified result or engine exception. |
| R2-13 | `2a74dd0` | Incomplete or corrupt persisted attempts are a barrier, not absence; a store poisoned by a failed checked commit cannot prove durable absence until a post-restart store re-reads durable state. |
| R2-14 | `6012c21` | The post-link publication window is documented honestly: a pre-link failure leaves no final marker, rc=7 outranks success/conflicts once published, and only results acted on after publication completes are indeterminate. A deterministic concurrent-reader host test proves an early confirmed observation is possible and cannot be revoked. |
| R2-16 / R2-17 | `b87318f` | Queued unattended requests re-read the Start-at-Boot component setting at admission and fail closed on OFF/unreadable without disturbing admitted work; each rejection publishes a fresh snapshot so the Activity shows the real barrier reason. |
| R2-18 | `d32006e` | Checked attempt-store work runs on a bounded single-thread admission executor (claim-once/close-once); availability diagnostics run on a separate worker with sequence/generation guards. Boot OFF, durable barrier, single-flight and fail-closed diagnostics remain authoritative. |
| R2-19 + R2-04 caveat | `1252b3d` | In-process recovery requires confirmed engine completion; persisted-attempt recovery is a trusted post-process-death acknowledgement with a checked clear; final-result messages drop bare `SUCCESS`. Typed codes, the already-hooked SUCCESS code/no-reentry guard, `/dev` progress strings and the no-root-probe policy are unchanged. Narrowed in R4.9: `engineFinished` proves only the Java call ended — see §R4. |

### 18.2 Final local verification (non-privileged host only)

`:app:testDebugUnitTest` **554 tests / 45 suites, 0 failures / 0 errors /
0 skipped** (R3 prior-round baseline, not a fresh R4 full-suite result);
**137 host CTest cases, 0 failures** then (**140** after R4.3); `:app:lintDebug` **0 errors /
15 warnings**; workflow policy contract **67/67**; signing contract **40/40**;
make contract **36/36**; `:app:tasks --all` resolves and the clean/tampered
negative loader-pin fixture is green. Loader SHA-256 pins still match
`app/build.gradle.kts` and `app/src/main/jniLibs`; `git diff --check` clean.
No dependency was added or removed, no bundled loader/binary changed, and the
documentation/notices/hygiene edits introduce no runtime behavior change.

### 18.3 Hardware and release gates — still open, not claims

1. **Direct Boot FGS eligibility.** The `specialUse` boot-start service and
   notification path are covered by JVM tests only; no cold boot was performed.
2. **Real soft-reboot safety.** The historical full hard reboot on this model
   still stands; rc=4/6/7 are code-level facts, not device evidence. Unattended
   soft reboot stays disabled.
3. **`/dev` marker-root trust.** Marker files live under `/dev` and the reader
   trusts that root for admitted IDs; host tests inject harmless roots only.
4. **Sticky-bit owner limitation.** The sticky bit on `/data/local/tmp` does not
   defend against its owner (`shell`); descriptor identity checks mitigate but
   do not remove that trust assumption.
5. **AES key in argv — out of scope.** Parsing is hardened/range-checked, but
   the key's presence in the payload argv was not redesigned.
6. **Recovery UI — out of scope.** There is no in-app recovery control; an
   ordinary user with an unconfirmed persisted attempt is intentionally locked
   out until a trusted caller clears the barrier. App-data reset only discards
   the barrier and is NOT evidence that privileged effects stopped — not a safe
   recovery procedure.
7. **`BOOT_COUNT` is not proof.** A new boot count is diagnostic only and never
   authorizes a clear or a retry by itself.
8. **Fresh remote CI run after push.** A green GitHub Actions run with all PR #1
   policy jobs plus restored app checks, loader-pin fixtures and packaged-pin
   verification is a **POST-PUSH gate**; CI green is not claimed locally. No
   device, hardware, ADB, payload, privileged-container, cold-boot, soft-reboot
   or APK-assembly validation was performed for this round.

---

## 19. Fourth repair round (R4, 2026-11-26) — consolidated record

This section records the fourth repair round on `fix/app-correctness-safety`
(final local HEAD `74d35f5`). **§5/§11/§12/§14 remain the 2026-11-17 historical hardware ledger;
§15–§18 remain prior repair-round records.** R4 closes Re-review 3 findings
**R2-13, R2-12, R2-14 (narrowed), R2-16, R2-17, R2-18, R2-20, R2-21 and R2-22**.
**Current status (R5, 689f378): R2-14 and R2-16 are SCOPED / OPEN gates,
not closed and not proved safe — see README.md and CHANGELOG.md R5 sections.**
Every phase was host-verified only; no device/ADB, payload, privileged
container, cold boot, soft reboot, APK assembly, dependency or binary change.

| Phase / commit | Finding | Host evidence / fix loop |
|---|---|---|
| R4.1 `6348c71` | R2-13 | Process-wide failed-commit uncertainty latch; 59 focused tests, 0 failures; two reviews APPROVED. |
| R4.2 `b8526d8` | R2-12 | Conflicting terminal markers → rc7 with origin-aware diagnostics; 137 host CTest + 114 focused Java tests; two reviews APPROVED. |
| R4.3 `916a18e` | R2-14 narrowed | Matching `dfroot_confirm_` required for definitive non-7; rc7 published before retraction; 140 host CTest; three reviews APPROVED. Residual abnormal-death window is an OPEN hardware/OS gate, NOT proved safe. |
| R4.4 `7e41d27` | R2-16 | Authoritative OFF gate in `submit()` plus pre-engine recheck; no revocation after engine start; 98 focused tests; two reviews APPROVED. |
| R4.5 `11ae87d` | R2-17 | Correlated request outcomes, no cancellation replay; 142 focused tests + 3 repetitions; one Required fix loop RESOLVED. |
| R4.6 `15bb5cc` | R2-18 | Main-thread outcome reports enqueued off the durable lock; 95 focused tests; one Required fix loop RESOLVED. |
| R4.7 `a51f74e` | R2-20 | Per-start lifetime and owner token; 158 focused tests; two Required interleavings fixed and re-review RESOLVED. |
| R4.8 `186fc8d` | R2-21 | Duplicate starts rejected before WakeLock/work; admission worker lifetime defined; 74 focused tests + 3 repetitions; one Required flake fixed. |
| R4.9 `74d35f5` | R2-22 | Honest soft-reboot wording, `engineFinished`/app-data-reset Javadoc limits, docs consolidated; 50 focused `ResultMessagesTest` tests, 0 failures/errors/skips. No behavior change or retry/clear control. |

Gates still open (not claims): Direct Boot FGS eligibility, real soft-reboot
safety (unattended OFF; manual experimental/unvalidated), `/dev` marker-root
trust, sticky-bit owner (`shell`) limitation, AES key in argv out of scope, no
owner-facing recovery UI (app-data reset is NOT safe recovery), `BOOT_COUNT`
diagnostic only, and the R4.4/R4.5/R4.8 optional residuals recorded in
`CHANGELOG.md`. A fresh full suite/CI green run and the four final
comprehensive reviews are the main agent's gates.

---

## 20. Sixth repair round (R6.1–R6.4, 2026-11-26) — host-side bounded UI note

Short dated note only; **§5, §11, §12 and §14 remain the 2026-11-17 historical
hardware-validation ledger and are unchanged by this round.** Final local HEAD
`9e1aff7` (R6.1 `13ee65f`, R6.2 `731e88c`, R6.3 `cdf76e7`, R6.4 `9e1aff7`) on
top of R5 code head `33980e2` / docs head `689f378`, addressing Re-review 5.
R6 closes the remaining **R2-17** residuals in reviewed host scope and reduces
**R2-18** to bounded host-only responsiveness evidence: a 25-tick / 200 ms
read-only outcome watch, a 200 ms monotonic flush deadline with a three-deferral
cap and a post-winner sweep, owned express 16 / general host 64 bounded
executors, and a four-slot terminal-only render replay queue. **R2-14 and R2-16
remain SCOPED / OPEN gates — not closed and not proved safe.** Device
foreground-service-grace timing remains an OS/hardware gate; the host evidence
is **not** a device ANR proof and no green host CI result implies device safety.
Phase commits, focused verification counts, fix loops and the honest residual
list are in `README.md` and `CHANGELOG.md`; the final comprehensive reviews at
this head are the main agent's unchecked gate. No device, ADB, payload,
privileged-container, crash/power-loss or APK work was done for this round.

---

*Original document produced read-only for the 2026-11-17 GZIF port (no adb or
git operations). The 2026-11-25 updates above (status note, §4/§6/§8/§9/§10/§14,
§15, §16 and the Java-only §17) were documentation/hygiene or host Java changes
only; no device, payload, ADB, privileged, or APK operation was performed. Log
contents are referenced by line range only.
**Do not commit host logs or this device's raw dumps anywhere public.***
