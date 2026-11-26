# Changelog — DFRoot GZIF

## Round 12 / P2-16 (2026-11-26) — cleanup-time helper re-apply (device retest passed for the restore scope; rc=2 timeout separate and OPEN)

This section is authoritative for the **literal/current head** of
`fix/native-exploit-robustness`: the documentation commit that adds the
"Device retest" subsection below. Its immediate parent is
`7a855c51c8c48e4882a0c715164898ad0bcfd272` (the round-12 docs commit), and the
**code head** remains `376f5e277713c1008386624f85ef74f200f14f2c` (Phase 1 native
fix `fix(native): re-arm crash_dump64 helper patch during cleanup`); that code
head's own immediate parent is `0f0a2ad4b8546b07b8ec0e82a88e84449d03a7ed`, the
**pre-fix device-run head**. The P2-15 fix code head is `7e18b13`, the Phase B /
P2-14 code head is `0ea022f` and the round-8 reviewed head was `36a11bc`; none
of these is the literal head. The round-11 section below is authoritative for
`7e18b13` and round 8 for the Phase B code head `0ea022f`; all earlier sections
are historical. Round-10 totals (**0 new findings, 0 P2 open, 3 P1 open**) and
round-11 totals are **not retroactively changed**.

### Two S23 device attempts at the pre-fix head (`0f0a2ad`)

Two runs on 2026-11-26 at the pre-fix head `0f0a2ad` reached a full root chain
but **FAILED** the vendor-file restore. Evidence is archived at
`/mnt/240G_SSD/opencode_tmp/s23-final/`; these are **attempts, not validations
of any head**.

| Observation | Run 1 (`logcat.txt`, `root-probes.txt`, `post-reboot.txt`, `vendor-before-reboot.txt`) | Run 2 (`logcat2.txt`, `app-result2.txt`, `root-probes2.txt`, `post-reboot2.txt`, `poll-crashdump.txt`) |
|---|---|---|
| Root chain | Full chain succeeded | Full chain succeeded |
| Oracle | `su -v` = `3.4.2:KernelSU`; `su -c id` = `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`; SELinux **Enforcing**; KSU Next daemon + modules loaded | same root identity (`root-probes2.txt`; `logcat2.txt` `KernelSU Next` ksud + `/data/adb/modules/*/service.sh`); Enforcing observed post-reboot in `post-reboot2.txt` |
| Panic evidence | none | none |
| Vendor restore | `restore FAILED for /vendor/lib64/AIAwbModel_v2.2.tflite`; `read_vendor_splice at 0x0: got 0 bytes, status=0x0, errno=0 (Success): short read` (`logcat.txt:4405-4406`) | identical (`app-result2.txt`; `logcat2.txt:13004`) |
| App result | rc **66** = 2 (unknown) + 64 recovery-failed bit; `timeout waiting for final status` | same (`app-result2.txt`) |
| Reboot | clean reboot, SELinux Enforcing, no `/dev/df`, no `/dev/dffinal`, no `ksud` (`post-reboot.txt`) | clean reboot, same absence checks; vendor file still 529568 B, mtime 2022-01-01 (`post-reboot2.txt`); `poll-crashdump.txt` (Run 2 only) shows only the ELF magic `7f 45 4c 46` in the first 16 bytes |

### Decisive logcat and root-cause conclusion

In `logcat2.txt`, `patch: crash_dump64 <- splicehelper (1408 bytes)` and
`patched 1408 bytes to …/crash_dump64+0x0` at **19:58:38** (lines 2424–2425)
show the splice writes and the capture path worked; the vendor write also
succeeded at 19:58:39 (`patched 13504 bytes to
/vendor/lib64/AIAwbModel_v2.2.tflite+0x0`, line 2532). At **19:58:40** bootstrap
set partitions ro and started the SU daemon (lines 2611–2612). At **19:59:49**
cleanup logged
`F crashdump64: crash_dump.cpp:278] wrong number of args: 3 (expected 6)`
(line 12995) immediately before the 0-byte `read_vendor_splice` (line 13004)
and `cleanup failure: setting recovery-failed bit (64) on rc 2` (line 13038).

**Conclusion:** the page-cache-only splicehelper patch was gone by cleanup
time, so the **original** `crash_dump64` ran during the vendor-backed restore
(it received 3 arguments instead of the 6 the patched helper expects) and the
vendor read returned 0 bytes. **The exact mechanism of that discard is NOT
proven here** — do not claim a specific cause (page eviction, re-exec from
disk, page invalidation, etc.). Run 1 shows the same 0-byte read and `wrong
number of args` signature (`logcat.txt:4399,4405`), so the two attempts are
consistent. The Phase 1 commit message states the discard causally; per the
caveat above that phrasing is **provisional** and is not a proven mechanism.

### Fix (Phase 1, `376f5e2`)

- Added a pure `df_helper_block_matches()` predicate above the
  `DF_HELPER_TEST_ONLY` boundary, with a host test for it.
- `ensure_helper_patch()` is now called at the **start of `cleanup()`**, before
  any helper-backed restore, re-applying the same patched helper with the same
  verification as the initial patch. There is **no second restore record**.
  On failure the outcome is still **fail-closed**: the remaining restores are
  attempted and reported truthfully, but the recovery-failed bit (64) is set
  on the final rc; the restore is not silently reported as successful.
- Helper-size host suite goes **7 → 30 cases**. No pin, workflow,
  `.github/workflows/build.yml`, CMake, marker-count or other native-source
  change.

### Counts (all other counts unchanged)

Policy **92**; six CMake suites **23 / 25 / 3 / 7 / 6 / 16**; native **61**
status cases plus `final_publish`, **13** proc-scan, **28** ELF, **16 + 14 +
10** version, **40** loader-stage; helper-size **30** cases (was 7); JVM **39**;
markers **7/7** host + **3/3** release. No new CI run number is claimed for
`376f5e2`.

### Device retest (pass for P2-16 scope) — S23, 2026-11-26

A retest of an APK built from the **code head `376f5e2`** was run on the S23.
The APK sha256 is
`bd8a17836effcac027b719b61bff0abfea4b309289e47c4d6d9732947e0bcbd9`; it was
installed with a **disposable local test key** — no tag, no publish and no
official signing claim. Evidence is archived at
`/mnt/240G_SSD/opencode_tmp/s23-final/` (`pre3.txt`, `logcat3.txt`,
`logcat3-key.txt`, `root-probes3.txt`, `app-result3.txt`, `post-reboot3.txt`).

**Preconditions (`pre3.txt`, clean):** SELinux **Enforcing**; no `su`; no
`/dev/df`; no `/dev/dffinal`; no `ksud`; `persist.log.tag` empty; vendor file
size **529568** B, mtime **2022-01-01**.

**Run at 15:20:48Z (device-local 23:20:48):** full root chain — `su -v` =
`3.4.2:KernelSU`, `su -c id` =
`uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`, SELinux
**Enforcing** (`root-probes3.txt`, `logcat3.txt`). Decisive `logcat3` lines:

- **23:21:59.947** `cleanup: crash_dump64 helper patch missing; re-applying`
- **23:21:59.956** `cleanup: crash_dump64 helper patch re-established (verified)`
- **23:21:59.958 / .959** `restore OK (verified) for /system/lib64/libbase.so` (both writes)
- **23:22:01.570** `restore OK (verified) for /vendor/lib64/AIAwbModel_v2.2.tflite`
- **23:22:01.578** `restore OK (verified) for /apex/com.android.runtime/bin/crash_dump64`

Counts in `logcat3.txt`: `wrong number of args` **0**, `got 0 bytes` **0**,
`restore FAILED` **0**, `recovery-failed bit` **0**, `helper patch
re-established` **1**, vendor `restore OK` **1**. The final rc therefore has
**no bit 64**. This closes the P2-16 restore defect **on device**: the pre-fix
`got 0 bytes` / `wrong number of args` / rc **66** signature did not recur, and
the vendor and crash_dump64 restores are verified OK.

**Separate, pre-existing OPEN item.** The run **still** ended with
`status: timeout waiting for final status (rc=2)` and
`cleanup: unbound observation; recovery gate stays closed`; `/dev/dffinal`
was absent. This same rc=2 / unbound-observation outcome was present in both
pre-fix attempts, so it is **not caused by P2-16**. It remains a **separate
OPEN item**. Consequently **P1-02** (actual invocation identity) remains OPEN;
**P1-07** restore verification is now device-confirmed, but quiescence and
failure-interruption remain unproven, so **P1-07 stays OPEN**; **P1-08 stays
OPEN**.

**Post-reboot (15:23:08Z, `post-reboot3.txt`):** SELinux Enforcing, uptime
**62.73**, no `su`, all checked `/dev/df*` paths and `ksud` absent, vendor size/mtime
unchanged (529568 B, 2022-01-01); the S23 remained connected. No panic evidence
(pstore empty).

**Counts and CI:** all counts are unchanged — policy **92**, helper-size **30**,
six CMake suites **23 / 25 / 3 / 7 / 6 / 16**, native **61** status +
`final_publish`, 13 proc-scan, 28 ELF, 16+14+10 version, 40 loader-stage, JVM
**39**, markers **7/7** host + **3/3** release. **No new CI run number** is
claimed for `376f5e2`. The APK used a disposable local test key; no tag was
created, nothing was published and the release job remains unexecuted.

### Limits and disposition

- **Device retest passed for the P2-16 restore scope** (code head `376f5e2`,
  S23, subsection above): the restore defect is **closed on device** — no bit
  64, verified vendor/crash_dump64 restores. This is a **scoped** pass only.
- **A separate rc=2 timeout / unbound-observation item remains OPEN** (also
  present in the two pre-fix attempts, not caused by P2-16). No `/dev/dffinal`
  final-status publication was observed.
- **P1-02, P1-07 and P1-08 remain OPEN.** P1-07 restore verification is now
  device-confirmed, but quiescence and failure-interruption remain unproven.
  P2-13/P2-14/P2-15 are unchanged from the round-8/10/11 ledgers.
- **The PR as a whole is not device-verified, not independently merge-ready
  and not release-ready**; no device, merge or release claim for the whole PR.
  No tag was created, nothing was published, and no completed-green CI
  conclusion is claimed for `376f5e2`. **Auto Soft Reboot and Start on Boot
  remain disabled by default and unvalidated.**

## Round 11 / P2-15 (2026-11-26) — release-JNI debug-symbol stripping; packaging fix (host-only)

This section is authoritative for head
`7e18b138090abfd6c0af5cc02cb031938e6ace2a` (Phase 1 fix
`fix(app): preserve reviewed loader bytes in release APK`); the round-12
section above is now authoritative for the **literal/current head**
`376f5e277713c1008386624f85ef74f200f14f2c`. Its immediate
parent is the docs commit `74b12df` (`docs: refresh round-8 validation ledger
and evidence scope`). The **Phase B / P2-14 code head** remains `0ea022f`
(`fix(ci): verify loader bytes inside every distributable APK`), and the
round-8 reviewed head was `36a11bc`; neither is the literal head. The round-8
section below is authoritative for the Phase B code head `0ea022f`; all
earlier sections are historical.

**Round-10 scope (not retroactively changed).** The consolidated round-10
review comment **7126155178** at head `74b12df` reported **0 new findings,
0 P2 open and 3 P1 open (P1-02 / P1-07 / P1-08)**, left the head unchanged and
completed CI run **45710535038** with `success`. **P2-15 was NOT found by that
review**; it was found by an author-local release build. Non-tag CI builds run
the Debug variant only, and the tagged release job remained unexecuted, so CI
never exercised the affected packaging path. Round-10 totals above are not
revised here.

### Defect — bundled loader bytes changed in the release APK

| Artifact | Reviewed / pinned bytes (`jniLibs` and CMake pins) | Bundled in release APK built from `74b12df` (before fix) | Release rebuild at `7e18b13` after fix (local test key) |
|---|---|---|---|
| `libksud.so` | `731c3fa1f83fe698ec0b749413065b25c12d4d433b2aed6c3ba7152fdfbefee4` — 5,127,584 B | `ecac0d2b1e5930bf69d887eaf4d9fbdc630da524457cb3025d1166d9ac37b7f5` — 3,737,888 B | `731c3fa1f83fe698ec0b749413065b25c12d4d433b2aed6c3ba7152fdfbefee4` — 5,127,584 B |
| `libksudnext.so` | `7f28f1fb5d3238f606330945b2694df140fea97527507c865f411db704ec2414` — 4,728,320 B | `ea0505cd25692f83de17fe2c3a2fcab237050128f84dcd0b68cf9f3705284f40` — 4,728,320 B | `7f28f1fb5d3238f606330945b2694df140fea97527507c865f411db704ec2414` — 4,728,320 B |

The second loader is the trap: its defective size is **identical** to the
reviewed size, so only the SHA-256 distinguishes it. **Cause:** AGP 9.5 strips
debug symbols from bundled release JNI libraries by default. The committed
inputs and their build-time loader gate stayed correct, but the bytes packaged
into a release APK did not; only a local release build exposed this because
non-tag CI is Debug-only and the release job was never run.

### Fix (Phase 1, `7e18b13`) and host verification

- `app/build.gradle.kts` `packaging.jniLibs` now sets
  `keepDebugSymbols += "**/libksud.so"` and `keepDebugSymbols += "**/libksudnext.so"`
  so packaged release bytes remain the reviewed bytes.
- A regression helper plus positive and mutation tests were added in
  `tests/workflow/test_policy.py`; the workflow policy suite count goes
  **90 → 92** (it was 90 at rounds 8–10 and 85 earlier). No KO/loader pin,
  `.github/workflows/build.yml`, CMake, native source or marker-count change:
  native 61 status + `final_publish`, 13 proc, 28 ELF, 16+14+10 version,
  40 loader-stage and helper-size; six CMake suites **23 / 25 / 3 / 7 / 6 / 16**;
  JVM 39; **7/7** host and **3/3** release markers are unchanged.
- Host verification (evidence archived at
  `/mnt/240G_SSD/opencode_tmp/p2-15-evidence`): a clean
  `:app:clean :app:assembleRelease` with a **disposable local test key**
  (`/home/samson1357924/dfroot-device-test.jks`; KO sha256
  `94f8be987a2f8bdc13539e42a5c3cb4c0f606024b8b31601496c5d5e5c0025a7`)
  produced `DFRoot_4.8-gzif.apk` sha256
  `33c3742dc777f82a9e8f0b61ca1054b198ba9c75ec22b3503125cd184005fec9`.
  Each loader entry occurs **exactly once** in the APK; extracted bytes are
  `731c3fa1…` / 5,127,584 B and `7f28f1fb…` / 4,728,320 B. The real
  `verify_loader_hashes.cmake` manifest status is `verified`, and the **verbatim**
  P2-14 gate script from `build.yml` (`Verify bundled loader bytes in every
  distributable APK`) passed with the pins file and one index row (APK name +
  APK sha256 + both loader sha256 values). The key was never tagged, uploaded
  or published and is not official signing material.

**Limits.** This is **host-only packaging evidence**: **not a signed release**
(the APK used a disposable local test key, not release signing material), no
tag was created, nothing was uploaded or published, no device validation was
performed, and the release job remains unexecuted. No completed-green CI
conclusion is claimed for the literal head: a run had started but not concluded
when this ledger was written.

### Disposition

- **P2-15 is addressed in Phase 1 (`7e18b13`), pending final comprehensive
  review** — not claimed finally closed here.
- **P1-02, P1-07 and P1-08 remain OPEN** (the three P1 items still open in the
  round-10 review).
- **P2-13 and P2-14 are unchanged** from the round-8/10 ledger (addressed in
  Phase A/B, pending final comprehensive review).
- No device, merge or release claim: this head is **not device-verified, not
  independently merge-ready and not release-ready**; no tag, no publish and no
  release claim.

## Round 8 / P1-08 (2026-11-26) — rebuilt loaders integrated; post-review hardening; gate still OPEN

This section is authoritative for the **Phase B code head** of
`fix/native-exploit-robustness` (`0ea022fd4dac054ad37e1cf0809e56a8604eaaba`,
Phase B), not for the literal head (round 11 above). The round-8 review covered head `36a11bc`; two increments landed
after that review: `0832331` (`fix(cmake): invalidate loader evidence before
argument validation`, Phase A) and `0ea022f` (`fix(ci): verify loader bytes
inside every distributable APK`, Phase B). The producer change `2b59684` and
loader integration `413a5cb` are earlier/mid-increment points, **not** the
head. Every earlier section, including round 6, is **historical** and
describes earlier heads only. All evidence in this section is **host-only**;
there was no device run, no tag and no release.

### Root-owned staging contract and rebuilt loaders

P1-08 was the `/data/local/tmp/.ksud-stage` trust gap: a `shell`-uid process
could replace the staged daemon between the producer's `close()` and the
loader's `rename()`. It is addressed at the **loader/source** layer, not by
documentation or Java-side gating:

- The Samsung-patched loaders were rebuilt from source on payloads branch
  `fix/p1-08-root-owned-staging` (commit `891e897`) to consume a stage in the
  root-owned `/data/adb` directory: `/data/adb/.ksud-stage`. The daemon validates
  that stage **by fd** (owner, mode, non-empty content) before promoting it to
  `/data/adb/ksud`. The old `/data/local/tmp/.ksud-stage` string is no longer
  present in either rebuilt binary; both contain `/data/adb/.ksud-stage`.
- DFRoot's producer side was changed accordingly at `2b59684` (bootstrap stages
  into `/data/adb/.ksud-stage`, 0700, then execs it). That producer change by
  itself is **not** proof the gate is closed: both halves must be re-reviewed
  together and the full chain must be validated on a device.
- A clean-state device attempt exposed an unrelated preflight defect: the
  fixed `helper_orig[1024]` save buffer was smaller than the 1,408 B embedded
  splicehelper, so every build aborted before touching system state. The buffer
  is now dynamically allocated with an inclusive 32..65536 B validator and a
  host boundary test; this is host-proven only and the device run must be
  repeated with the rebuilt APK.
- Source-build evidence (host builds of the patched loaders): CI run
  **45674540939** (classic pair, `android13-5.15`) and CI run **45674545588**
  (Next generic, four KMIs). Run 45674540939 **failed overall**: only its
  successful `pair ksud` job produced the integrated classic artifact; its
  separate generic-ksud job failed because no no-patch-text module was
  available. Run 45674545588 succeeded overall. These runs attest the loader
  rebuilds only; they are not device validation and not certification of this
  head.

Rebuilt artifacts now bundled under `app/src/main/jniLibs/arm64-v8a/`:

| Artifact | sha256 | size |
|---|---|---|
| `libksud.so` (classic pair daemon, v3.3.0 / code 32601, `android13-5.15`) | `731c3fa1f83fe698ec0b749413065b25c12d4d433b2aed6c3ba7152fdfbefee4` | 5,127,584 B |
| `libksudnext.so` (Next generic daemon, four KMIs) | `7f28f1fb5d3238f606330945b2694df140fea97527507c865f411db704ec2414` | 4,728,320 B |

### Build-time loader gate

A new fail-closed `verify_loader_hashes.cmake` pins **both** sha256 values and
both exact sizes and is wired as an `ALL` CMake target, so every build checks
the committed loader bytes. It rejects missing files, non-regular/symlinked
paths, wrong sizes and wrong hashes with distinct fatal reasons, removes any
prior verified manifest at entry, and writes a manifest only after both
loaders pass. There is no skip option. The KO pins and KO gates are untouched.
At the reviewed round-8 head (`36a11bc`) the host fixture suite
`tests/cmake/loader_hash_test.cmake` covered 5 cases (reviewed pins pass, wrong
hash, wrong size, missing file, reused output path) and the six CMake suites
stood at **23 / 25 / 3 / 3 / 6 / 5** cases (KO policy / packaged-KO / APK
packaging / APK content / target graph / loader hash). Those are historical
counts for that head: at the Phase B code head the loader-hash suite has **16**
cases and the APK-content suite has **7**, giving **23 / 25 / 3 / 7 / 6 / 16**.
The host-gates marker set stays **7/7** unchanged.

### Post-review increments (Phase A / Phase B)

- **`0832331` (Phase A, `fix(cmake): invalidate loader evidence before argument
  validation`).** Stale verified loader evidence is invalidated before
  required-argument validation, so a failed re-check cannot leave a prior
  success behind. This is a host/CMake evidence-integrity fix, not device
  validation.
- **`0ea022f` (Phase B, `fix(ci): verify loader bytes inside every distributable
  APK`).** A new release gate named `Verify bundled loader bytes in every
  distributable APK` extracts **both** bundled loader entries from each final
  signed APK and checks SHA-256 **and** size against the pins in
  `app/src/main/jni/CMakeLists.txt` through the real verifier. A required
  **per-APK loader index** (nonempty) is asserted at the pre-upload choke
  point. Release markers stay **3/3** and host suite markers stay **7/7**.
  This is a host/CI packaging gate, **not** device validation.
- **P2-13 and P2-14** were addressed across these two post-review increments
  (Phase A / Phase B) after the round-8 review. Both are **addressed in this
  round, pending final comprehensive review**; this ledger does not claim
  final closure before that review.

### Current-increment host test shape and evidence scope

Locally verified at the final increment (`0ea022f`; host-only):

- Native: **61** status cases plus `final_publish` (canonical token /
  permission / atomicity / strict reader / bootfail / bound + unbound
  recovery), **13** proc-scan cases, **28** ELF cases, **16 + 14 + 10**
  device-version cases, **40** loader-stage cases
  (`tests/native/loader_stage_test.c`) and the helper-size suite
  (`tests/native/helper_size_test.c`).
- CMake: six suites at **23 / 25 / 3 / 7 / 6 / 16** cases (KO policy /
  packaged-KO / APK packaging / APK content **7** cases / target graph /
  loader hash **16** cases).
- JVM: **39 tests, 0 failures, 0 errors, 0 skips** (LaunchGate 4,
  ListenerLifecycle 10, PreferenceIntegrity 12, PreferencePersister 13).
- Workflow policy (`tests/workflow/test_policy.py`): **90 tests** (was 85) at
  the Phase B code head; at the round-11 literal head the policy suite is
  **92 tests** (P2-15 regression added), with every other count unchanged.
  **7/7** host markers and **3/3** release markers unchanged.
- CI status re-checked before finalizing this ledger: run **45708004129** at
  the Phase B code head `0ea022f` **completed with conclusion `success`**
  (host-only evidence, **not** device validation or a release claim); its log
  confirms 23/25/3/7/6/16 CMake cases, 39 JVM tests and 7/7 markers. Runs
  **45706111735** (`0832331`), **45702366070** (`36a11bc`) and
  **45697296267** (`bb83849`) are also completed `success`, but are **earlier
  heads**. No exact code-head count is asserted from an earlier log.

### Disposition

- **P1-08 remains OPEN — RELEASE GATE.** The root-owned staging contract is
  implemented in both halves and built from reviewed source, but it is
  **pending re-review and fresh on-device full-chain validation**. No device
  run was performed for these binaries: do not consume or distribute unverified
  privileged content and do not claim the gate is closed from host evidence.
- **No APK from non-tag CI; separate LKM artifact.** Non-tag events upload no
  DFRoot app APK: the only DFRoot upload is the `DFRoot-CI-evidence` bundle
  (`GPL-3.0.txt`, `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`). The
  intermediate `df-lkm-${kmi}` KO artifact **is** uploaded separately by
  `build-lkms` on every event; it is a build intermediate, not a DFRoot app
  APK and not a release claim.
- **APK loader gate is a host/CI gate.** Phase B checks bundled loader bytes
  inside every distributable APK at the release path, with a required
  per-APK loader index at the pre-upload choke; it does not validate device
  behavior and does not close any open safety/release gate.
- **Open gates:** P1-02, P1-07 and P1-08 remain **OPEN** safety/release gates.
  P2-13 and P2-14 were addressed in this round (Phase A/B), **pending final
  comprehensive review** — not claimed finally closed here.
- run6 (classic, 2026-11-17) and run8 (Next, 2026-11-17) — and every earlier
  CI run — are **historical**: they validated the previous loaders and earlier
  implementations, never these rebuilt binaries or this head. No device run
  validates the literal head.
- **Auto Soft Reboot and Start on Boot remain disabled by default and
  unvalidated.** No tag was created and no release was published; this head is
  not device-verified, not independently merge-ready and not release-ready.

## Round-6 repair (2026-11-25) — historical (round-6 head `d72838d`)

> **Historical section.** The round-12 section above is authoritative for the
> literal head (round 11 for `7e18b13`; round 8/P1-08 for the Phase B code head
> `0ea022f`); this section describes the round-6 head only.

This section was authoritative for the round-6 head of
`fix/native-exploit-robustness` (`d72838d`). **Every section below is
historical**; its counts and dispositions describe earlier heads, not this
one. All evidence here is **host-only**: no hardware run validates this head,
a successful build is not a safety certificate, no tag was created and no
release was published. This round changed CI gating, the signing smoke, the
packaged-KO host fixture and the boot-state UI; no exploit payload or reviewed
KO pin was relaxed.

### Stable-ID disposition (round-6)

| ID | Round-6 disposition | Host evidence / remaining boundary |
|---|---|---|
| P1-02 | **Addressed in host-tested scope; device timing UNVALIDATED** | `dfrecover` remains bound to the current run nonce: a stale or foreign `ok` is rejected, so reserved-channel token syntax is **not** invocation proof and absence of evidence is **not** proof that system activity stopped. `dfbootfail` carries the reserved-nonce `BOOTSTRAP_FAILED` token (observable rc=1) only when no normal final token parses. Android publication/access and on-device timing remain unproven; no device certification is derived from host mocks. |
| P1-07 | **Addressed in host-tested scope; UNVALIDATED on device** | The absolute deadline is checked before consulting a positive recovery result, again after it, and after cleanup before any terminal outcome; late/uncertain evidence yields `REBOOT_SKIPPED` and can only disable a reboot. These are **host-proven ordering checks only**: device quiescence and verified restoration remain UNVALIDATED. This is a release restriction, not a safety claim. |
| P1-08 | **OPEN — RELEASE GATE** | The bundled loaders hardcode `/data/local/tmp/.ksud-stage`; a `shell`-uid process can still swap the staged file before the loader renames it. Closing this requires rebuilding the loaders. Documentation is not a fix: keep blocked, do not consume or distribute unverified privileged content, do not claim fixed. |
| P1-11 | **Resolved in round 6 (host-only evidence)** | A new **unconditional** `host-gates` job (`needs: build-lkms`, no `if`, checkout-SHA assertion) runs **all** host suites on **every** event including protected tag pushes: native, all five CMake suites, the real KO CI policy, the JVM 39/0/0/0 assertion and 7/7 markers. Both `build-dfroot` and `build-release` require `host-gates`; `publish` requires `build-release`. A tag release cannot proceed without host evidence of the tested commit. No device claim. |
| P1-12 | **Resolved in round 6 (host-only evidence)** | Non-tag events no longer distribute an APK: the only upload is the `DFRoot-CI-evidence` licence/notice bundle (`GPL-3.0.txt`, `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`). The Debug APK is a compile/content gate only, never uploaded, signed with release material or published. No stale `DFRoot-UNVERIFIED-CI` downloadable APK remains. Distribution is withheld pending P1-08, a reviewed re-pin and device validation. |
| P2-04 | **Addressed for host/distributable gates; real-device release path UNVALIDATED** | Staged-link → verified-copy chain; AGP targets `dfroot_packaged` **and** `bootstrap`; APK-content and target-graph harnesses (named target, `ALL`, raw bypass, cached, poisoned); CI gathers evidence over **every** `app/.cxx` packaged manifest. The byte gate rejects UNKNOWN bytes but **accepts the two known obsolete pins**; release is withheld by **POLICY** (reviewed re-pin, OPEN P1-08, device validation), not by the hash gate alone. A passing gate is not device validation. |
| P2-10 | **Addressed in round 6 (host-tested scope); real UI lifecycle UNVALIDATED** | Boot component state is tri-state with a **persistent** `UNKNOWN` status row plus an explicit "Disable automatic startup" action that attempts only DISABLE and never claims verified `OFF` without a `DISABLED` readback. `UNKNOWN` is never shown as verified `OFF` and never used to accept `OFF`; there is no auto-enable path. Real Activity/Fragment recreation and PackageManager behavior remain UNVALIDATED. |
| P2-11 | **Resolved in the stated narrow scope, not re-litigated** | `objcopy` runs only on an isolated copy with original-artifact immutability proven on success and failure. Round 6 additionally removes any stale verified `.so` at the first step of the verified-copy command, so a failed re-check leaves no verified copy, and runs the mutating-objcopy fixture through a generated 755 launcher with an explicit `python3` interpreter so cases 14a/14b actually execute and pass. Graph case 5 proves wrong-pin failure and correct-pin recovery. Pins/sizes unchanged (`94f8be…` / `9dfb8b…`, 13504 / 13384 B). |
| P2-12 | **Resolved in the stated narrow scope (host-only)** | The signing smoke uses an isolated identity (`df.root.signingsmoke` / `4.8-gzif-signingsmoke` / `SigningSmoke-4.8-gzif-signingsmoke.apk`), explicitly rejects any `DFRoot_*.apk`, and still fails closed with no signing material. It produces no distributable artifact. |

Findings that **remain resolved in their prior narrow scope** and are not
re-litigated here: **P1-10** (exactly-once cleanup on every exit path),
**P2-08** (durable `PENDING` → same-key commit, sticky fail-closed `WILDCARD`,
launch tokens and whole-transaction lock) and **P2-09** (stale packaged evidence
invalidated before required-argument validation). None of them constitutes
device validation.

### Post-fix CI status (round-6 head; historical — see round-8 section above)

- **The pre-repair head was RED.** At `4a81aa5`, CI run **45558516540** failed
  at `packaged_ko_test` case **14a** with "permission denied": the
  mutating-objcopy fixture is stored with git mode 100644 and was invoked
  directly. That head is **not** a green head. The fix generates a **755
  launcher** that invokes the fixture with an explicit `python3`; cases
  **14a/14b now actually execute** and pass their strict SHA-256 mismatch and
  recovery assertions.
- **Round-6-head host results (local, host-only; historical counts — the
  Phase B code head is tabulated in the round-8 section above):** native **61**
  status cases plus `final_publish` (canonical token / permission / atomicity /
  reader / bootfail / bound + unbound recovery), **13** proc-scan, **26** ELF,
  **16 + 14 + 10** device-version; CMake **23 / 25 / 3 / 3 / 6** cases (KO
  policy / packaged-KO / APK packaging / APK content / target graph); JVM
  **39 tests, 0 failures, 0 errors, 0 skips** (LaunchGate 4,
  ListenerLifecycle 10, PreferenceIntegrity 12, PreferencePersister 13);
  workflow policy **85 tests / 202 subtests**. **7/7** host suite markers and
  **3/3** release markers (plus the nonempty gate index and single upload
  choke point) are asserted. No required gate uses `continue-on-error`.
- **Distribution:** non-tag events upload only `DFRoot-CI-evidence` with the
  GPL-3.0/licence/notice bundle; no APK is downloadable from CI. The signing
  smoke emits only the isolated `SigningSmoke-*.apk` inside a disposable copy
  and is never uploaded.
- **Boundary:** run6/run8 and all earlier CI runs — including 45523079469 and
  45558516540 — are **historical**, not certification of this head. Auto Soft
  Reboot and Start on Boot stay disabled and unvalidated. No tag was created,
  no release was published, no device verification was performed, and this
  head is not independently merge-ready.

## Round-5 repair (2026-11-25) — historical (round-5 head)

> **Historical section.** It was authoritative for the pre-round-6 head; the
> round-11 section at the top is authoritative for the literal head (round 8 for
> the Phase B code head `0ea022f`). Its counts,
> CI shape and dispositions describe the round-5 implementation only.

### Stable-ID disposition (round-5, 9 findings carried forward)

| ID | Round-5 disposition | Host evidence / remaining boundary |
|---|---|---|
| P1-02 | **Addressed in host-tested scope; device timing UNVALIDATED** | `dfrecover` is now bound to the current run nonce: a stale or foreign `ok` is rejected, so syntax alone is not invocation proof. The reserved-nonce `BOOTSTRAP_FAILED` token on the app-owned `dfbootfail` channel is observable rc=1; token syntax is not proof that bootstrapping succeeded. Real-file and mock tests cover stale/foreign evidence; Android publication/access and device timing remain unproven. |
| P1-07 | **Addressed in host-tested scope; asynchronous quiescence UNVALIDATED** | The absolute deadline is enforced before consulting a positive recovery result, again after it, and after cleanup before any terminal outcome. Late or uncertain evidence yields `REBOOT_SKIPPED` (never an authorization); recovery polling is bounded/clamped. Mocks prove ordering, count and fail-closed branches, not device quiescence or that asynchronous privileged activity has finished. |
| P1-08 | **OPEN — RELEASE GATE** | The bundled loaders hardcode `/data/local/tmp/.ksud-stage`; a `shell`-uid process can still swap the staged file before the loader renames it. Closing it requires rebuilding the loaders. Documentation is not a fix: keep blocked, do not claim fixed. |
| P2-04 | **Addressed for host/distributable gates; real-device release path UNVALIDATED** | Staged-link → verified-copy chain; AGP targets `dfroot_packaged` **and** `bootstrap`; APK-content and target-graph harnesses (named target, `ALL`, raw bypass, cached, poisoned); CI gathers evidence over **every** `app/.cxx` packaged manifest before upload. The byte gate rejects unknown bytes but **accepts the two known obsolete pins**; release is withheld by policy — a reviewed re-pin, OPEN P1-08 and device validation — not by the hash gate alone. Passing the gate is not device validation. |
| P2-06 | **Resolved in the stated narrow scope; not re-litigated** | Required identity check on every `dfroot-build-provenance.txt` (git HEAD, release variant, reviewed KO pin, KO manifest and splicehelper binary/source digests, compiler identity) plus complete-set evidence with one row per uploaded APK. Records are audit evidence, not an attestation or independent reproducible-build proof. |
| P2-07 | **Resolved in the stated narrow scope; not re-litigated** | Exact CI shape, markers, counts and validation boundary are recorded here, in the README/NOTICE/PORTING docs and in the PR #3 description; historical run6/run8 remain attached only to the historical implementation. |
| P2-08 | **Addressed in host-tested scope; real PackageManager/fsync UNVALIDATED** | Boolean transaction result, sticky `WILDCARD` restored, launch tokens plus a whole-transaction lock, and no success reported on a failed integrity transaction. The write-ahead `PENDING` marker still requires a validated same-key PENDING→commit cycle. Device-protected storage and real PackageManager/fsync behavior are untested. |
| P2-09 | **Addressed; verified by host fixtures** | Stale packaged evidence is invalidated before required-argument validation, not only afterwards; same-path failure fixtures (success→failure, failure→success, poisoned dump) prove a reused path cannot carry stale success forward. |
| P2-10 | **Addressed in host-tested scope; real UI lifecycle UNVALIDATED** | Boot component state is tri-state; `UNKNOWN` is never shown as verified `OFF` and is never used to accept an `OFF` setting, and nothing auto-enables. The user can always turn boot OFF; a framework write is accepted only on a real state match. Real Activity/Fragment recreation and PackageManager behavior are unvalidated. |

Newly closed in round 5: **P1-10** — `status_flow_run()` performs exactly-once
cleanup on **every** exit path; an `ERR` path uses unbound cleanup, sets the
recovery-failed bit and returns `DF_RC_UNKNOWN`; fault-injection tests cover
these paths. **P2-11** — `objcopy` runs only on an isolated copy, with original
artifact immutability proven on both success and failure; no hash or size pin
was relaxed (pins `94f8be…` / `9dfb8b…`, sizes 13504 / 13384 B, unchanged).

### Post-PR-#1 rebase integration

PR #1 is merged into the base and PR #3 was rebased onto that head. PR #1's
CI/signing/GPLv3-conveyance, Gradle 9.9.2 + AGP 9.5.0 version catalog, NDK/DKK
digest pinning and Make/contract tests are integrated. Kept from PR #1: the
version catalog, release-environment signing writer / `apksigner` / checksum /
conveyance and the `publish` job. Non-tag events at this historical round-5 head
uploaded a Debug-only `DFRoot-UNVERIFIED-CI` artifact with PR #1's licence/notice
bundle and **no release claim**; signed release remains tag-only and gated. At
the current head non-tag events upload no DFRoot APK (only `DFRoot-CI-evidence`).
run6/run8 and every
older CI run (including 45523079469) validate only historical implementations
or pre-rebase heads, never this head. No tag was created by us.

### Final host test shape (round-5 head; historical — see round-6 above)

- **Native** (`make -C tests/native test`): **61** `status_protocol` cases;
  `final_publish` (canonical token / permission / atomicity / reader / bootfail
  / bound + unbound recovery); **13** `proc_scan_policy` cases; **26** ELF
  fixtures; **16 + 14 + 10** kernel / token / release device-version cases.
  Host tests never execute privileged payloads.
- **CMake** (`cmake -P`, host-only): `ko_hash_policy_test.cmake` **23** cases
  (clean environment and simulated `CI` / `GITHUB_ACTIONS` / `DFROOT_RELEASE`);
  `packaged_ko_test.cmake` **25** cases (same-path arg failures, mutating
  `objcopy`, input immutability); `apk_packaging_gate_test.cmake` **3** cases;
  `apk_content_gate_test.cmake` **3** cases (`libbootstrap.so` present / wrong
  ABI / missing); `packaging_target_graph_test.cmake` **6** cases (named target
  `dfroot_packaged` + `bootstrap`, `ALL`, raw bypass, cached, poisoned).
- **JVM** (`:app:testDebugUnitTest`): **36 tests, 0 failures, 0 errors, 0 skips**
  — LaunchGate 4, ListenerLifecycle 7, PreferenceIntegrity 12,
  PreferencePersister 13. CI parses the JUnit XML and asserts exactly 36/0/0/0;
  a green Gradle exit code alone is not accepted.
- **Suite markers on every non-tag CI event (7/7)**: `marker-native`,
  `marker-ko-policy`, `marker-packaged-ko`, `marker-apk-packaging-fixture`,
  `marker-ko-ci-policy`, `marker-jvm`, `marker-junit`. **Publishing-only gates
  (3/3)**: `marker-apk-gate`, `marker-provenance-identity`,
  `marker-release-evidence`, plus the nonempty gate index and a single upload
  choke point. No required gate uses `continue-on-error`.

### Validation boundary

All current verification is host-only. P1-02/P1-07 are proven with host mocks
and real-file adapters; device timing, quiescence, Android publication/access
and privileged-state safety are UNVALIDATED. P2-04/P2-06/P2-09/P2-11 are proven
with host fixtures and CI-shaped checks; the byte gate accepts the two known
obsolete pins, and release is withheld by policy (reviewed re-pin, P1-08 and
device validation), not by the hash gate alone. P2-08/P2-10 are proven with host
JVM tests; real PackageManager/fsync and UI lifecycle are UNVALIDATED. P1-08
remains OPEN. Auto Soft Reboot and Start on Boot remain disabled by default and
unvalidated. run6/run8 and all earlier CI runs — including 45523079469 —
validate only historical implementations and pre-rebase heads. A successful
build is not a safety certificate.

## Historical — PR #1 hardening (CI build reliability), integrated into the rebased head

> **Historical section.** It describes the PR #1 baseline as integrated before
> round 6; the round-11 section at the top is authoritative for the literal head
> (round 8 for the Phase B code head `0ea022f`).

Hardening pass over the build, signing and release pipeline; no change to the
exploit chain or to the GZIF validation claims. Documentation was brought in
line with the shipped configuration (Gradle wrapper **9.9.2** with a pinned
distribution SHA-256; AGP **9.5.0** effective through
`gradle/libs.versions.toml` via the version-catalog alias with no root
`build.gradle.kts` pin; Kotlin/Java sources unchanged).

- **Signing writer and fail-closed behavior** (`scripts/WriteSigningProperties.java`,
  `app/build.gradle.kts`): signing material is serialized safely for arbitrary
  passwords/aliases and locales; `signing.properties` is optional at
  configuration time (a fresh checkout no longer fails `assembleDebug`),
  incomplete properties are reported readably, and local keystore/property
  files are created with restrictive permissions and verified after writing.
  With PR #3's rebased semantics, absent/incomplete signing material installs
  a harmless placeholder and **disables/fails closed on every release task**;
  Debug stays debug-keystore signed. An installable Release requires complete
  signing material.
- **Protected release environment** (`.github/workflows/build.yml`): least
  privilege by default; only a push of a `v*` tag runs `build-release` in the
  `release` environment, where the four `KEYSTORE*` secrets are available.
  Ancestry check precedes any secret reference; the signed APK is
  `apksigner`-verified, checksummed and uploaded with 1-day retention; the
  publish job only downloads, verifies and publishes. Action references are
  pinned to full 40-hex SHAs and permissions are least-privilege.
- **PR/branch/manual CI artifacts, reconciled with PR #3**: those events run
  all host suites as required gates and build a **debug-keystore-signed Debug**
  APK (no signing secrets, no signed Release, no GitHub Release). The APK is
  uploaded as a public, downloadable Actions artifact named
  **`DFRoot-UNVERIFIED-CI`**, carrying `GPL-3.0.txt`, `LICENSE`, `NOTICE` and
  `THIRD_PARTY_NOTICES.md`. It is explicitly UNVERIFIED — no release claim.
  The protected tag path adds PR #3's packaged-byte, provenance-identity and
  release-evidence gates before any signed artifact is uploaded or published.
- **New release key identity warning**: releases from this hardened pipeline
  are signed with a **new key identity**. Android will not overwrite-update an
  installed application signed by a different key — uninstall any existing
  DFRoot GZIF build before installing a signed release APK (**UNVERIFIED CI
  Debug artifacts** cannot update a signed install either).
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
  `THIRD_PARTY_NOTICES.md` state the full GPL-3.0-or-later obligations for the
  bundled userspace loaders (correcting the "no other obligations"
  overstatement) while retaining the provenance and complete-source offer.
  Both `GPL-3.0.txt` and the repository's own GPL-2.0 `LICENSE`, plus `NOTICE`
  and `THIRD_PARTY_NOTICES.md`, accompany the signed release assets **and** the
  publicly downloadable UNVERIFIED CI APK artifact, with the upload and
  publish allowlists tightened to those exact files. The signed bundle is not
  the only unit of conveyance.
- **Documentation corrections** in `README.md`, `PORTING-GZIF.md`, `NOTICE`
  and `THIRD_PARTY_NOTICES.md`: 9.9.2 / 9.5.0-effective, signing/debug behavior,
  CI KO hash, digest-pinned DDK image, environment-only Make credentials,
  single-KMI Make, and the CI event matrix. No AndroidX / Material Components
  dependency (themes use framework `android:Theme.Material*`); four KMI modules
  are embedded in `libksudnext.so` with GZIF using `android13-5.15`.
- **Regression coverage**: workflow policy/contract tests, make contract tests,
  signing material/permission tests and signed/unsigned release smoke tests
  cover the hardened pipeline, including license/notice packaging, a byte pin
  for `LICENSE`, equality-locked upload/publish allowlists, and rejection of
  `secrets/`, `.jks`, `signing.properties` and `..` artifact entries.

### Post-rebase integration

PR #1 is now merged into the base (`origin/master`); PR #3 was rebased onto that
head. The round-11 section at the top is authoritative for the literal head
(round 8 for the Phase B code head `0ea022f`); the
round-5 section above and the host-suite counts, stable-ID disposition table and
validation boundary in the round-4 section below are historical. run6/run8 and
every prior CI run predate
the rebase: they are historical and do not validate this head. No tag was
created by us. Where PR #1's unsigned Release CI
APK met PR #3's fail-closed release variant, the rebased CI path builds Debug
only and labels the artifact UNVERIFIED; signed releases are exclusively on
the gated `v*` tag path.

## Unreleased — round-4 (2026-11-25) — PR #3 repair: truthful docs and lifecycle/failure-path evidence

> **Historical section.** Its counts (49 native status, 28 JVM, 15 packaged-KO)
> describe the round-4 implementation, not the literal head. The round-11
> section at the top is authoritative for the literal head (round 8 for the
> Phase B code head `0ea022f`).

**All evidence in this section is host-only.** No hardware run validates the
current head of `fix/native-exploit-robustness`; a green build does not certify
safe privileged execution. No tag was created and no release artifact was
published. This round-4 repair also changes code, tests, CMake and CI in the
preceding commits; the final documentation commit updates only docs and the PR
description. Historical run6/run8 results stay
attached to the historical implementation described in §4.8-gzif below.

### Stable-ID disposition (consolidated review comment 7126155178, round 4)

| ID | Round-4 disposition | Host evidence / remaining boundary |
|---|---|---|
| P1-02 | **Addressed in host-tested scope; device timing/quiescence UNVALIDATED** | 128-bit nonce generated and bound before any state write; strict `df_parse_final_token()` (exact length, no embedded NUL, exact nonce, no extra bytes) with garbled/foreign tokens ignored; reserved-nonce `BOOTSTRAP_FAILED` published only on the separate app-owned `dfbootfail` channel and consumed as observable rc=1 via a dedicated reader. Real-file and mock tests cover stale state and repeated observations. Android publication/access and on-device freshness remain unproven. |
| P1-07 | **Addressed in host-tested scope; asynchronous safety UNVALIDATED** | Cleanup runs exactly once, after the first valid bound status observation (or once on the shared deadline if none arrives), on the SAME absolute deadline as the poll loop; its time is charged to that deadline and the clock is rechecked. Recovery polling is bounded to 5 s and clamped to the remaining deadline; a NULL/failed `recovery_ok()` or timeout publishes `REBOOT_SKIPPED` and can only disable the reboot. A failed restore adds the rc-64 bit to every code. Mocks prove ordering, count and fail-closed branches, not device quiescence or that asynchronous activity has finished. |
| P1-08 | **OPEN — RELEASE GATE** | The bundled loaders hardcode `/data/local/tmp/.ksud-stage`; a `shell`-uid process can still swap the staged file before the loader renames it. Closing it requires rebuilding the loaders. Documentation is not a fix: keep blocked, do not claim fixed. |
| P2-04 | **Addressed for host/distributable gates; real-device release path UNVALIDATED** | The packaged-byte gate always runs; the real APK gate runs on the protected `v*` tag publishing path before any signed artifact is uploaded or published, over the complete staged APK set, with per-APK manifest + `so_sha256` binding, manifest/dump invalidation at entry and `if-no-files-found: error`. PR/branch/manual runs upload only an explicitly UNVERIFIED Debug APK with no release claim. The byte gate rejects unknown bytes but **accepts the two known obsolete pins**; release is withheld by policy (reviewed re-pin, OPEN P1-08, device validation), not by the hash gate alone. Passing the gate is not device validation. No tag was created by us. |
| P2-06 | **Addressed for identity checks; provenance remains informational** | Required identity check on EVERY `dfroot-build-provenance.txt` (git HEAD == `GITHUB_SHA`, release variant, reviewed KO pin, KO manifest and splicehelper binary/source digests, compiler identity fields), plus complete-set release evidence with one row per uploaded APK. Records are audit evidence, not an attestation or an independent reproducible-build proof. |
| P2-07 | **Addressed in this repair** | Test/lifecycle/failure-path counts and CI markers documented below; the PR description is replaced to match the current head and actual CI without device-verification claims; historical run6/run8 remain attached only to the historical implementation. |
| P2-08 | **Addressed in host-tested scope; real PackageManager/fsync UNVALIDATED** | A durable write-ahead `PENDING` marker is published before the commit (full write, read-back, fsync); recovery requires a fully validated PENDING→commit cycle of the SAME key; unattributable faults latch a sticky fail-closed `WILDCARD`; the boot component is disabled synchronously at the failure boundary. Device-protected storage and real PackageManager/fsync behavior are untested. |
| P2-09 | **Addressed; verified by host fixtures** | The packaged verifier invalidates its manifest and section dump at entry; fixtures reuse a single output path for success→failure, poisoned-dump and failure→success sequences, and the upload path verifies the complete staged set before upload. |
| P2-10 | **Addressed in host-tested scope; real UI lifecycle UNVALIDATED** | Lifecycle-scoped listener registration/removal, component-state-authoritative boot switch, the user can always turn boot OFF, nothing auto-enables, and a framework write is accepted only when actual component state matches. Real Activity/Fragment recreation and PackageManager behavior are unvalidated. |

Findings that **remain resolved in the prior narrow scope**: P1-01, P1-03,
P1-04, P1-05, P1-06, P2-01, P2-02, P2-03, P2-05 and P1-09 (newly resolved in
round 4 through the CI-policy environment isolation). Their earlier evidence is
unchanged; none of them constitutes device validation.

### Final host test shape (round-4 head; historical — see round-5 above)

- **Native** (`make -C tests/native test`): **49** `status_protocol` cases;
  `final_publish_test` against real temp files (0644 under a hostile umask,
  atomic replace, symlink temp sabotage, reserved-nonce bootfail token and
  strict recovery-status reader); **13** `proc_scan_policy` cases; **26** ELF
  fixtures; **16 + 14 + 10** kernel / token / release device-version cases.
  Host tests never execute privileged payloads.
- **CMake** (`cmake -P`): `ko_hash_policy_test.cmake` **23** cases (clean
  environment and simulated `CI` / `GITHUB_ACTIONS` / `DFROOT_RELEASE`, skip
  rejection, variant authority); `packaged_ko_test.cmake` **15** cases
  including reused-path stale manifest/dump evidence;
  `apk_packaging_gate_test.cmake` **3** cases using a harmless deterministic ZIP
  fixture (not a real APK).
- **JVM** (`:app:testDebugUnitTest`): **28 tests, 0 failures, 0 errors,
  0 skips** — `LaunchGate` 4, `ListenerLifecycle` 5, `PreferenceIntegrity` 8,
  `PreferencePersister` 11. CI parses the JUnit XML and asserts exactly
  28/0/0/0; a green Gradle exit code alone is not accepted.
- **Suite markers on every non-tag CI event (7/7)**: `marker-native`, `marker-ko-policy`,
  `marker-packaged-ko`, `marker-apk-packaging-fixture`, `marker-ko-ci-policy`,
  `marker-jvm`, `marker-junit`; one gate fails if any marker is missing.
- **Publishing-only gates (`v*` tag path, before any signed upload/publish)**:
  `marker-apk-gate` (every distributable APK, per-APK manifest + `so_sha256`
  binding), `marker-provenance-identity` (required, every record),
  `marker-release-evidence`, `marker-distributable-evidence`, then a single
  upload choke point on the staged set. PR/branch/manual runs upload only an
  explicitly UNVERIFIED Debug APK (with the licence/notice bundle) and make no
  release claim. Provenance is informational evidence, not attestation or
  reproducible-build proof.

### Validation boundary

P1-02/P1-07 are proven only with host mocks and real-file adapters; device
timing, quiescence and privileged-state safety are UNVALIDATED. P2-04/P2-06/
P2-09 are proven with host fixtures and CI-shaped checks; the byte gate rejects
unknown bytes but **accepts the two known obsolete pins**. Release is withheld
by policy — a reviewed re-pin, the OPEN P1-08 staging gate and device
validation — not by the hash gate alone; passing the gate is not device
validation. No tag was created by us. P2-08/P2-10 are proven with host JVM
tests; real PackageManager/fsync and UI lifecycle behavior are UNVALIDATED.
P1-08 remains OPEN. Auto Soft Reboot and Start on Boot remain disabled by
default and unvalidated. No hardware run validates the rebased head — run6/run8
and all earlier CI runs validate only the historical implementation (old
multi-marker protocol, then-current pins, old preference path). A successful
build does not certify safe privileged execution.

## Unreleased — round-3 PR #3, phase 3 (preference integrity / launch gating)

> Historical section. The round-11 section at the top of this file is
> authoritative for current counts (workflow policy 92), cleanup timing, the
> recovery channel and the literal head (round 8 for the Phase B code head
> `0ea022f`). This section, the round-4 section above it and the phase
> sections below are kept for provenance only.

**All changes in this section are host-tested only and UNVALIDATED on device.**
No hardware run has exercised the quarantine latch, the new rc gates or the
updated UI messages. Documenting a release gate is not closing it.

- **SharedPreferences memory/disk divergence (P2-08).** New
  `PreferenceIntegrity.java` is a process-wide quarantine latch with a durable
  marker file in device-protected storage. `init(Context)` / `init(File)` are
  idempotent; `markAmbiguous(key)` latches quarantine and writes the marker;
  `markConsistent(key)` clears it ONLY for the same key (a different key cannot
  recover another key), and when the last key is consistent the marker is
  removed. A marker found at startup is restored (`SENTINEL_RESTORED`) and keeps
  the quarantine active across process death. Any marker read/write/delete
  failure fails closed (quarantine stays set, `durableMarkerActive()` false).
  `addListener()` lets the UI refresh; `resetForTest()` is test-only.
  `PreferencePersister.java` keeps its public API: a failed/throwing commit is
  reported first and then calls `markAmbiguous(key)`; a successful commit calls
  `markConsistent(key)`. The JVM fake now models separate in-memory and on-disk
  maps, so a failed commit really leaves them divergent.
  **Round-4 final behavior (supersedes the in-process latch above):** a durable
  `PENDING` marker is published *before* the commit with full-write/read-back/
  fsync; recovery needs a fully validated PENDING→commit cycle of the SAME key;
  unattributable faults latch a sticky fail-closed `WILDCARD` that no key can
  clear, and the boot component is disabled synchronously at the failure
  boundary. Real PackageManager/fsync behavior is UNVALIDATED.
- **Launch / boot gating.** `ExploitRunner.run()` initializes
  `PreferenceIntegrity` and evaluates the pure static `earlyExitRc(devDfExists,
  prefsQuarantined)` before touching the device: `/dev/df` now returns rc 9
  (`RC_ALREADY_HOOKED`) with a truthful "already hooked" message instead of rc 0;
  a quarantined preference state returns rc 8 (`RC_PREF_STATE_UNCERTAIN`);
  `/dev/df` takes precedence when both hold. `MainActivity` disables LAUNCH
  ROOT while quarantined, updates the button through a listener, states that a
  failed save blocks launch, and disables the `BootReceiver` component when the
  marker could not be persisted (so boot gating cannot be trusted).
  `SettingsFragment` disables "Start on Boot" while quarantined, refuses to
  enable it, uses the same failure toast/boot-component guard, and refreshes on
  listener callbacks. `BootReceiver` initializes the latch from the DE context
  and skips the boot launch when quarantined.
  Round 4 makes listener registration lifecycle-scoped and the boot switch
  component-state-authoritative (the user can always turn boot OFF; nothing
  auto-enables). See the round-4 section above and `PORTING-GZIF.md` §17.
- **Java rc map is now truthful (phase 1b follow-up).** New `StatusRc.java`
  mirrors `status_protocol.h`: rc 0 root-only, 1 error, 2 unknown, 3 patch
  failure, 4 reboot requested, 5 reboot skipped, 6 reboot failed,
  7 attempt unknown, 8 preference state uncertain, 9 already hooked, plus the
  additive 64 recovery-failed bit. `base()`, `recoveryFailed()` and `name()`
  are pure; `name()` appends a recovery warning for ANY rc with bit 64 and never
  claims "root verified" or a confirmed reboot. `MainActivity.runExploit()`
  uses `StatusRc.name(rc)` instead of its own string ladder.
- **Tests / CI.** `tests/java/PreferencePersisterTest.java` now has 11 JVM cases
  (failed put/remove quarantine, same-key clears, different-key does not, thrown
  write, marker created and restored after a simulated restart). New
  `tests/java/LaunchGateTest.java` has 4 cases (early-exit truth table including
  `/dev/df` precedence, rc constants, recovery bit on every code, never claiming
  verified root/confirmed reboot). The Gradle `test` source dir already points
  at `tests/java`; CI's JUnit XML assertion now expects **28 tests** (four JVM classes; see round-4 section above), 0
  failures/errors/skips. These are host-only JVM tests, not device validation.
- **Release gates unchanged.** P1-08 (`/data/local/tmp/.ksud-stage` swapped by a
  `shell`-uid process before the loader renames it) remains **OPEN**: this phase
  only documents the Java-side gating and does not rebuild the bundled loaders.
  Auto Soft Reboot and Start on Boot stay disabled by default and unvalidated;
  the packaged-KO real gate is still blocked until the reviewed KO is re-pinned.

## Unreleased — round-3 PR #3, phase 2 (build / packaging gates)

**All changes in this section are host-tested only and UNVALIDATED on device.**
No hardware run has exercised these gates; a tag build that fails them must
not be published.

- **Android variant is authoritative (P2-04).** `app/build.gradle.kts` passes
  `-DDFROOT_ANDROID_VARIANT=release` / `=debug` through each build type's
  `externalNativeBuild.cmake.arguments`. `CMakeLists.txt` maps `release` →
  effective `Release` (and `debug` → `Debug`) ahead of the cache's
  `CMAKE_BUILD_TYPE`, so a stale cached Debug label can no longer weaken the
  skip guard. `verify_ko_sha256.cmake` independently rejects
  `DFROOT_SKIP=ON` with `DFROOT_ANDROID_VARIANT=release` (case-insensitive),
  even with no CI marker and a stale Debug label, and records
  `android_variant` in its manifest. Signing guards are unchanged.
- **Final packaged bytes are verified (P2-04).** `exp.c` now emits the KO into
  a dedicated `.dfroot_ko,"a",@progbits` section (`.balign 16`, same symbol
  names and `.previous`; no C changes). A new POST_BUILD step runs
  `verify_packaged_ko.cmake`, which extracts that section from the linked
  `libdfroot.so` with `CMAKE_OBJCOPY` and compares its SHA-256 against the
  **same unchanged pins** plus the reviewed artifact sizes (13504 / 13384 B).
  Configure fails if `CMAKE_OBJCOPY` is unavailable. The verifier has **no
  skip option** (a skip flag is a hard error), fails closed with distinct
  reasons (objcopy unavailable / section not found / size mismatch / packaged
  KO SHA-256 MISMATCH) and writes a manifest with status, sha256, bytes,
  section, so_sha256, pins, timestamp and git HEAD. No pin was added or
  relaxed. `ko_pin_policy.cmake` shares pin validation between both verifiers.
- **Real APK gate (P2-04).** On the protected `v*` tag publishing path the
  workflow unzips `lib/arm64-v8a/libdfroot.so` from EACH distributable APK,
  runs the same verifier with the NDK's llvm-objcopy and asserts
  `status: verified` plus a matching manifest `so_sha256` **before** any signed
  upload or GitHub Release, with no `continue-on-error`. PR/branch/manual runs
  upload only an explicitly UNVERIFIED Debug APK and make no release claim.
  **The byte gate rejects unknown bytes but accepts the two known obsolete
  pins**; release is withheld by policy (reviewed re-pin, OPEN P1-08 and device
  validation), not by the hash gate alone. A passing host fixture run is not
  device validation.
- **Provenance is evidence, never stale (P2-06).**
  `record_build_provenance.cmake` now fails closed if the KO manifest is
  missing, malformed or disagrees with the stable stamp (status and hash), and
  records `ko_manifest_sha256` and `android_variant`. An always-run force
  target plus `KO_VERIFY_MANIFEST`, `CMakeLists.txt` and the force file in
  `DEPENDS` refresh the record when inputs change. It remains an informational
  audit record, **not an independent reproducible-build proof or attestation**.
- **All suites reached (P2-07).** New host-only
  `tests/cmake/packaged_ko_test.cmake` (deterministic harmless ELF64 fixtures
  from `make_packaged_ko_fixture.py`) covers valid pin, ANY-pin, mismatch,
  corrupted byte, missing section, wrong size, bad objcopy, skip-cannot-bypass
  and fail-closed manifest behaviour. CI asserts a marker from every native /
  CMake / JVM suite (the round-4 final shape asserts 7/7 markers) and parses the
  JUnit XML for **28 tests, 0 failures/errors/skips** across four JVM classes, so
  one failure cannot hide another. Required gates
  have no `continue-on-error`; the informational provenance uploads are also
  required in the round-4 shape (`if-no-files-found: error`), not best-effort.
  A release-evidence step checks the git SHA, skip OFF in every
  CMakeCache, a verified KO manifest whose hash is in the pins, a verified
  packaged manifest with the same hash, and the APK hash.
- **Unchanged gates / boundary.** P1-08 (`/data/local/tmp/.ksud-stage`
  race-at-creation fixed, but a `shell`-uid process could still swap the file
  before the loader renames it) remains a release gate requiring a loader
  rebuild. run6/run8 validated the historical implementation only; everything
  above is host-tested only and UNVALIDATED on device. Auto Soft Reboot and
  Start on Boot stay disabled by default and unvalidated.

## Unreleased — PR #3 hardening pass (round 2)

**All changes in this section are host-tested only and UNVALIDATED on device.**

- Native: bounds/overflow-safe ELF parsing (ELF64/LE/AArch64/ET_DYN only,
  bounded string tables, segment-contained hook/payload offsets), strict
  numeric/version parsing, checked `waitpid`, secure `ksud` staging.
- P1-04 preflight before any write: `exp.c` now builds a `struct preflight`
  containing exact KMI, padded helper/KO payloads, pre-read originals and a
  fully validated hook plan. Every version, stat, length, ELF, alignment,
  branch-range, overlap and pread check happens before the first
  `patch_file_cbc()` call; an INVALID ELF aborts with **no fallback**, while
  only UNSUPPORTED selects the next hook candidate. Vendor originals are
  captured after the helper patch but before the KO write, so the restore iv
  cannot depend on our own payload.
- P1-05 ELF parser contract: `elf_parser.{c,h}` returns
  `ELF_PARSE_OK=0` / `ELF_PARSE_UNSUPPORTED=1` / `ELF_PARSE_INVALID=-1`; out
  parameters are zeroed on every failure. Metadata validation covers
  EI_CLASS/DATA/VERSION, ET_DYN/EM_AARCH64, header sizes, PN_XNUM/SHN_XINDEX
  rejection, table ranges, phdr/shdr type/entsize/link/info rules, bounded
  NUL names, symbol section/vaddr/range checks and ambiguous-name rejection.
  A `struct elf_layout` claim list proves that tail/zero-run placement never
  overlaps the ELF header, phdr/shdr tables, file-backed sections or other
  file-backed segments, and zero-run starts must be 4-byte aligned.
  26 deterministic fixtures (`tests/native/fixtures/`, regenerated by
  `gen_fixtures.py`) and `elf_parser_test.c` cover all of the above.
- P2-01 strict versions: `device_version.{c,h}` parses kernel major/minor and
  Android release with bounded digits (no signs, leading zeros or overflow)
  and token boundaries, rejects ambiguous releases, and records provenance.
  Preflight requires uname-token provenance and an exact android/major/minor
  KMI match; getprop-only values and fuzzy KO pairings are rejected.
- Cleanup truthfulness: every started write is recorded and restored in
  reverse order with an explicit per-target `restore OK/FAILED` log;
  `cleanup()` returns -1 if any restore fails, a status-flow cleanup failure
  downgrades a success rc 0 to 1, and `main()` reports rc 3 when preflight
  fails before any file is touched.
- Status protocol (replaces the multi-marker scheme): a **single** final
  outcome is atomically published to `/dev/dffinal` (world-readable 0644 temp,
  mode pinned with `fchmod()` against umask, `+ fsync + rename`; the token has
  no secrets and must be readable by the app uid). The old terminal markers
  `/dev/dfmsr`, `/dev/dfm6`, `/dev/dfmw2`,
  `/dev/dfmw3` and `/dev/dfme2` are no longer published or polled; `/dev/dfm*`
  files remain informational progress only. The inverted `dfmsr` publication
  check (which made a successful publication report failure) is removed.
- Pure, host-testable logic module `status_protocol.{c,h}` owns all decisions
  with injected callbacks: fail-closed producer (late-load failure → ERROR,
  no reboot; reboot only after a confirmed-absent late-load state and a
  successful ROOT_ONLY preflight; bounded WNOHANG child wait; refinement
  publish failure leaves ROOT_ONLY) and consumer (checked monotonic deadline,
  ROOT_ONLY grace rebase, garbled tokens ignored, read errors keep polling,
  cleanup exactly once). Clock failure fails closed everywhere; `/proc` scan
  errors are never treated as "late-load absent"; a SIGKILLed child is reaped
  with WNOHANG against a 2 s secondary deadline instead of a blocking wait.
  `status_poll.{c,h}` is deleted.
- App rc mapping: 0 late-load exited 0 / root not verified, 1 error,
  2 unknown, 3 local patch failure, 4 soft-reboot command issued / not
  confirmed, 5 soft reboot skipped, 6 soft-reboot command failed. No toast
  claims root was verified or a reboot is guaranteed.
- Build/tests: `status_protocol.c` is linked into both native targets;
  `tests/native/status_protocol_test.c` replaces the marker test with 28
  mock-based cases at that time (**49 in the round-4 final suite**) and touches
  no real `/dev`. `final_status_publish.c` is
  linked into `bootstrap` and exercised by `tests/native/final_publish_test.c`
  against real temp files (0644 under a hostile umask, atomic replace, symlink
  temp sabotage).
- KO hash-skip policy (P2-04): `verify_ko_sha256.cmake` now requires
  `DFROOT_BUILD_TYPE`, validates every pin up front, and enforces the skip
  policy in the script itself (FATAL when `DFROOT_SKIP` is combined with
  `CI`/`GITHUB_ACTIONS`/`DFROOT_RELEASE`, a Release build type, or an empty
  `DFROOT_SKIP_REASON`). Blob existence/directory checks still run first. The
  verified stamp stays stable (no timestamps or reasons in it); a separate
  `dfroot-ko.sha256.manifest` records status, hash, accepted-pin count, build
  type, environment flags, UTC timestamp, git HEAD and script path. CI parses
  the pins from `CMakeLists.txt`, runs the script directly under `CI=true`
  (must pass), asserts skip fails, and greps the CMakeCache for
  `DFROOT_SKIP_KO_HASH_CHECK:BOOL=OFF`.
- Build provenance (P2-06): new `record_build_provenance.cmake` and
  `dfroot_build_provenance` target record splicehelper binary/source SHA-256,
  compiler id/version/target/sysroot/flags, CMake version, git HEAD, build type
  and KO gate status. A release-only, best-effort workflow step hashes the APK,
  KO, splicehelper and bundled loaders and uploads an informational manifest.
  No new pins are introduced. Provenance is evidence only; it is **not** an
  independent reproducible-build proof, and splicehelper is not claimed to be
  bit-for-bit deterministic across toolchains.
- Preference persistence (P2-08): new `PreferencePersister.java` uses
  synchronous `commit()` and returns failure to callers. `MainActivity` reverts
  the SU-manager spinner (with a recursion guard) and `SettingsFragment`
  returns the listener result so switches revert on a failed write; both show
  a Toast and log. New `tests/java/PreferencePersisterTest.java` (fake
  `SharedPreferences`, 8 JVM cases at introduction; 11 in the round-4 final
  suite) and a `:app:testDebugUnitTest` CI step.
  PR builds configure without `signing.properties`: the release signing
  config is a harmless placeholder unless the real keystore and all secrets
  are present, and `assembleRelease` fails closed otherwise. This resolves the
  earlier CI signing configuration issue; it is unrelated to the P1-08
  staging gate.
- Test/docs truthfulness (P2-07): 20 cases at introduction (14 numbered groups,
  some with a/b subcases; **23 cases** in the round-4 final shape) in host-only
  `tests/cmake/ko_hash_policy_test.cmake`, wired into CI alongside the existing
  `make -C tests/native test` step; docs below state the validation boundary
  explicitly.
- Round-3 docs correction: `PORTING-GZIF.md` §4's "70 s plus a 10 s grace" was
  inaccurate — the wait is capped at 70 s total, with the grace
  `min(observation + 10 s, start + 70 s)` and cleanup once on observation or
  timeout. Documentation-only; `status_protocol.c` is unchanged in that phase.
  The native phase below subsequently raises the grace cap to the 85 s overall
  deadline and retires the ROOT_ONLY grace entirely.

## Unreleased — round-3 PR #3, phase 1b (native only, Java mapping later)

**Host-tested only and UNVALIDATED on device.** No hardware run has exercised
this protocol. Java result-code strings/map were deliberately untouched *at
this phase*; the phase 3 section above now adds the Java `StatusRc` mapping.

- **Nonce-bound v2 token.** `exp.c` generates a 128-bit nonce with `getrandom`
  (`/dev/urandom` fallback), writes it atomically (0644, `fchmod`, `fsync`,
  `rename`) to `/data/user_de/0/df.root/files/dfinv` **before**
  `createOrphanProcess()`, and uses it for the consumer. `bootstrap.c` strictly
  reads/validates the same 32-hex-char nonce at startup; at this phase a
  missing/malformed nonce published ERROR with the reserved zero nonce and
  skipped reboot/late-load. Round 4 changed this to `BOOTSTRAP_FAILED` under
  the reserved nonce on the separate app-owned `dfbootfail` channel (rc=1) and
  binds the nonce before any state write. The single final token is now
  `dfroot-v2:<nonce>:<outcome>\n`; `df_format_final_token()` /
  `df_parse_final_token()` reject embedded NUL, truncated or extra bytes, a
  stale/foreign nonce and unknown/`none` outcomes. `df_publish_final()`
  validates canonical shape before any write, keeping 0644 + `O_EXCL|O_NOFOLLOW`
  + `fsync` + `rename` + unlink-on-failure; both real read adapters use
  `O_NOFOLLOW` + `fstat(S_ISREG)` + size bound + exact read and pass an explicit
  byte count to the strict parser (no C-string prefix acceptance).
- **New outcomes and truthful preflight.** `ROOT_ONLY` now means ONLY "no
  reboot requested" and is terminal immediately (no grace). A soft-reboot
  attempt publishes `REBOOT_PENDING` as its non-terminal preflight; a failed
  refinement publishes `REBOOT_ATTEMPT_UNKNOWN` — never a fallback to
  `ROOT_ONLY`. `REBOOT_PENDING` opens the grace window, which is clamped to the
  **overall** deadline (85 s), not the 70 s budget; a pending observation with
  no refinement becomes `REBOOT_ATTEMPT_UNKNOWN`. New rc constants:
  `DF_RC_ATTEMPT_UNKNOWN=7`, `DF_RC_ALREADY_HOOKED=9`,
  `DF_RC_RECOVERY_FAILED_BIT=64`; both pending and unknown map to rc 7.
- **Cleanup timing (superseded in round 4).** At this phase `status_flow_run()`
  called `cleanup_cb` exactly once **before** `consumer_await()`; the round-4
  lifecycle fix moved it to exactly once **after the first valid bound status
  observation** (or once at the end of the shared deadline), charged to that
  same deadline, with a bounded/clamped recovery poll and `REBOOT_SKIPPED` on a
  NULL/failed `recovery_ok()` or timeout. Producer-side confirms absence only
  if the checked deadline has not expired. `/proc` scanning uses the new pure
  `proc_scan_policy.{c,h}` verdict (benign ENOENT/ESRCH/zero bytes, incomplete
  on other errno or a full buffer without a match, match wins), strict PID
  `strtol` validation and a tri-state -1/0/1 result. `run_ctx()` and
  `wait_child_bounded()` use bounded `WNOHANG` waits with a 5 s/5 s cap and
  `kill_and_reap_bounded()`; timeout/clock failure is -1.
- **Recovery bit.** Every final rc from `exp.c` is OR'ed with 64 when cleanup
  failed (instead of downgrading only rc 0 to 1). `cleanup()` now reads each
  restored region back, `memcmp`s it with the saved original and logs
  `VERIFICATION FAILED` + marks failure on any mismatch.
- **Tests:** `status_protocol_test.c` had 34 mock cases at this phase (**49 in
  the round-4 final suite**, adding lifecycle, reserved-bootfail and strict
  recovery-status paths: strict v2 format/parse, new outcomes, pending preflight,
  unknown on refinement failure, grace past budget and clamped by overall,
  cleanup timing, pending timeout, clock/deadline boundaries); new
  `proc_scan_policy_test.c` has 13 table cases; `final_publish_test.c` exercises canonical tokens and rejects
  malformed ones with the prior final untouched. `make -C tests/native test`,
  host compiles and `git diff --check` are clean.

Residual risks / **release gates** (not closed by this PR):

- run6 and run8 (2026-11-17) and every earlier CI run validated the
  **historical implementation** — the old multi-marker status protocol and the
  then-current KO pins — only; they predate the post-PR #1 rebase. Every change
  in this "Unreleased" section is **host-tested only and UNVALIDATED on
  device**; no new hardware run has exercised the single-`/dev/dffinal` flow,
  the new build policy, or the new Java persistence paths.
- **No release may ship with obsolete KO pins.** The byte gate rejects unknown
  bytes but still accepts the two known obsolete pins; if a DDK rebuild
  produces a hash outside them, stop and verify out of band. Withhold release
  by policy until a reviewed re-pin, P1-08 is closed and the device path is
  validated. Never skip the check for CI/release builds; passing it is not
  device validation.
- Provenance manifests are informational and are **not** an independent
  reproducible-build proof.
- Auto Soft Reboot and Start on Boot remain disabled by default and
  **unvalidated** on S9180ZHU8GZIF.
- P1-08: the bundled loaders hardcode `/data/local/tmp/.ksud-stage`; the
  symlink/race-at-creation is fixed, but a `shell`-uid process could still swap
  the staged file before the loader renames it. **This cannot be closed without
  rebuilding the bundled loaders and is a release gate** (documented in
  `PORTING-GZIF.md` §6).
- Stale SU-manager preference: when `PreferencePersister.remove()` fails in
  `MainActivity`, the old package name may remain in the prefs XML on disk. The
  phase 3 quarantine now blocks manual launch (rc 8) and `BootReceiver` skips
  booting while the key is ambiguous, so a stale value is not acted on; the
  stale disk bytes are still not erased by a failed write (fail-closed gating,
  not a repair). Recovery requires a successful save of the same key or clearing
  app data. This path is host-tested only and UNVALIDATED on device.
- The 32-byte AES key is still passed to the native binary on `argv`
  (`/proc/<pid>/cmdline` exposure). Both in-process copies are wiped on all
  paths, but fully removing this residual needs a Java→native fd/pipe contract
  change; the CLI format is preserved to avoid a cross-branch conflict.

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
