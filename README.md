<p align="center">
  <img src=".github/icon.png" width="96" />
</p>

# DFRoot [DirtyFrag (CVE-2026-43284)]

DFRoot is an Android rooting tool that exploits CVE-2026-43284 (DirtyFrag) — a kernel page cache write primitive — to load a custom kernel module without requiring an unlocked bootloader. This fork supports exactly two SU managers (see [Supported managers](#supported-managers)); it is **not** manager-agnostic and does not accept arbitrary KernelSU-compatible managers.

> [!IMPORTANT]
> Before hitting that fork button, consider making a pull request instead :)

## DFRoot GZIF — Samsung Galaxy S23 Ultra (SM-S9180 / S9180ZHU8GZIF)

This fork ports the DFRoot chain to the S23 Ultra (`dm3q`) build
**`S9180ZHU8GZIF`** (Android 17 / One UI 9.0 / SPL 2026-10-05 /
kernel `5.15.197-android13-8-34343818-abS9180ZHU8GZIF`). It was
hardware-validated on 2026-11-17 against the **historical** implementation only;
the current code base is host-tested with device retests for the **P2-16 restore
scope** and, under D2, the **P2-17 final-status-observability scope only**; the
PR as a whole is not device-verified (boundary below).

- **Validated 2026-11-17 (historical loaders only)** (volatile, late-load root only):
  - KernelSU **3.3.0** Samsung-patched classic loader → full chain; `su -c id` → `uid=0(root) … u:r:ksu:s0` (run6, 16:49)
  - KernelSU-Next **3.4.2** Samsung-patched generic loader → full chain; `su -v` → `3.4.2:KernelSU` (run8, 17:53)
- **Vanilla (unpatched) KernelSU / KernelSU-Next modules panic inside `init_module()` on this build** — do not substitute them. This fork bundles Samsung-patched loaders under `app/src/main/jniLibs/` (provenance + sha256: `NOTICE`).
- Root is **not boot-persistent** (late-load); re-run LAUNCH ROOT after every reboot. No `boot.img` flash is performed.
- **Only `S9180ZHU8GZIF` is tested.** No other build/model/One UI version is claimed. Not registered in any update feed.
- The fork renames the app label to **DFRoot GZIF** (`versionCode` 408, `versionName` `4.8-gzif`). `applicationId` remains `df.root` by design (bootstrap staging depends on it).
- Provenance, per-file change notices, licence split, and the source offer for the bundled loaders: `NOTICE`, `THIRD_PARTY_NOTICES.md`, `CHANGELOG.md`. Build/port notes: `PORTING-GZIF.md`.
- **P2-16 fix (`376f5e2`; device retest passed for the restore scope; rc=2 timeout / unbound observation separate and OPEN):** the two pre-fix S23 attempts showed a full root chain but a failed `/vendor/lib64/AIAwbModel_v2.2.tflite` restore (`read_vendor_splice at 0x0: got 0 bytes, status=0x0, errno=0`, rc 66). The decisive log shows the page-cache-only splicehelper patch was gone by cleanup, so the original `crash_dump64` ran (`wrong number of args: 3 (expected 6)`); the exact discard mechanism is **not proven**. The fix adds a pure `df_helper_block_matches()` predicate and calls `ensure_helper_patch()` at the start of `cleanup()` so the same patched helper is re-applied with the same verification before helper-backed restores, fail-closed and with no second restore record (helper-size suite **7 → 30** cases). On the retest S23 run the helper patch was re-established (verified) at cleanup and the vendor / `crash_dump64` restores were `restore OK (verified)` with **no recovery-failed bit** — P2-16's restore defect is **closed on device** for that scope. The run **still** ended `status: timeout waiting for final status (rc=2)` / `cleanup: unbound observation; recovery gate stays closed` with `/dev/dffinal` absent — a **separate, pre-existing OPEN item** (also present in the two pre-fix attempts); `/dev/dffinal` was the historical channel and **is no longer produced** under D2. P1-02 / P1-07 / P1-08 remain OPEN; no tag, no publish (disposable local test key). See `CHANGELOG.md` round 12 and round 13.
- **P2-17 fix (D2, committed at literal head `e823fb4`; parent `d39a278` historical):** the final status was published on time with the correct nonce-bound token, but `/dev/dffinal` carried `u:object_r:device:s0`, which the app domain could not read, so every run ended rc=2 unbound observation. A relabel attempt (`0f9bf69`) was denied and reverted at `d39a278`; a zero-code pre-flight eliminated creating a file in the `/data` root (root got `Permission denied`). D2 makes the **non-persistent Android property `dfroot.final`** the single authoritative final channel: root/bootstrap sets it, the app reads it, its context is `u:object_r:default_prop:s0`, unprivileged `setprop` is denied, and the canonical token / nonce binding / strict parse / fail-closed publication are unchanged. **`/dev/dffinal` is no longer produced.** Device retest PASSED for the **final-status-observability scope only** (APK sha256 `a7f6e02ebb44cb39f583ca3c32d1b818ad046be3019ce540ee4bf9be2ec001af`, disposable local test key, no tag/publish): `final: ROOT_ONLY - no reboot requested; root not verified` then `status: final status observed (rc=0)`, property `dfroot.final` = `dfroot-v2:3fec15b2d9ad766ff5173d857f75bc38:root_only`, root via `3.4.2:KernelSU` / `u:r:ksu:s0`, Enforcing, all restores `restore OK (verified)` with no bit 64 (P2-16 scope still green), no unbound observation. The rc=0 is **scoped to final-status observability only**; P1-02 / P1-07 / P1-08 remain OPEN and the PR as a whole is not device-verified, not merge-ready and not release-ready. Evidence for that earlier **pre-commit author-local run**: `/mnt/240G_SSD/opencode_tmp/s23-final/` (`app-result8b.txt`, `root-probes8b.txt`, `logcat8.txt`, `post-reboot8.txt`, `channel-probe7*.txt`); `post-reboot8.txt` records the device not reconnecting after reboot, so no post-reboot state claim. **A second author-local full run at the committed head `e823fb4`** (APK sha256 `f866b4388f9f4c9e365dbf2936a9b076eab14b6ad2f2b4e9ea3e642080758807`, disposable local test key, no tag/publish) is **SCOPED AUTHOR-LOCAL EVIDENCE, NOT independent validation**: `pre9.txt` clean after reconnection (Enforcing, no `su`, no `/dev/df`, no `/dev/dffinal`, no `ksud`, `dfroot.final` empty, vendor 529568 B mtime 2022-01-01); `app-result9.txt` helper patch re-established, four restores `restore OK (verified)`, no bit 64, `final: ROOT_ONLY - no reboot requested; root not verified` then `status: final status observed (rc=0)`; `root-probes9.txt` nonce `fdeb49b6704ae77bdd4648bbabfb9d69`, `3.4.2:KernelSU` / `u:r:ksu:s0`, Enforcing; `root-stability9.txt` after 30 s root/property unchanged, `ksud`=0, pstore empty. No reboot was requested, so there is no post-reboot evidence; this does not supersede the `post-reboot8.txt` caveat. Counts unchanged (policy 92; helper-size 30 author-reported; six CMake 23/25/3/7/6/16; native 61 + `final_publish`, proc 13, ELF 28, version 16+14+10, loader-stage 40; JVM 39; markers 7/7 + 3/3). Exact-head CI for `e823fb4` is run **45864085866** (host-only; see the boundary below). See `CHANGELOG.md` round 13.

> [!WARNING]
> **Validation boundary.** The **literal head is the committed `e823fb4`**
> (`fix(native): publish final status via app-readable property`, D2 native
> property channel + round-13 docs in ONE commit); its immediate parent
> `d39a278` is historical. PR #3 base is
> `9f60e16251f5856ee1ae720eaea84b41d7992920`, CI merge SHA
> `69054f5be1a278d88c19164444a7590e3c5bc2c0`. Round-12 heads
> (`39a376f` docs, `376f5e2` code), the historical relabel attempt `0f9bf69`
> and its revert `d39a278` are historical; the P2-15 fix code head is
> `7e18b13`, the Phase B / P2-14 code head is `0ea022f` (`fix(ci): verify
> loader bytes inside every distributable APK`) and the round-8 reviewed head
> is `36a11bc` — none of these is the literal head. The producer change
> `2b59684` and loader integration `413a5cb` are earlier/mid-increment points.
> **Independent-review accounting (R11, comment 7126155178): 29 IDs, 23
> closed, 4 open (3 P1 + 1 P2) and 2 author-reported addressed / pending
> independent validation.** The author changelog rounds 11–13 are **not**
> independent review rounds R1–R11; P2-15/P2-16/P2-17 are author-reported, not
> discovered by R10. **Round-10 scope (not retroactively
> changed):** consolidated review comment **7126155178** at `74b12df` reported
> **0 new findings, 0 P2 open and 3 P1 open (P1-02 / P1-07 / P1-08)**, left the
> head unchanged and completed CI run **45710535038** with `success`. **P2-15
> was NOT found by that review**, nor were P2-16 and P2-17 — all three are
> author-reported and pending independent validation; an author-local release
> build found P2-15 because non-tag CI builds Debug only and the tagged release
> job was never executed. CI status re-checked earlier: run **45708004129** at
> `0ea022f` **completed with conclusion `success`** (host-only, not device
> validation); its log confirms the counts below. Runs **45706111735**
> (`0832331`), **45702366070** (`36a11bc`) and **45697296267** (`bb83849`) are
> also completed `success` but are **earlier heads**. **Exact-head CI (committed
> head `e823fb4`): run 45864085866** — `pull_request` event, head `e823fb4`,
> **completed `success`**, **host-only**: Fast CI (92 policy, 36 Make, 40
> signing), Host evidence gates (61 status, 13 proc, 28 ELF, 40 loader-stage,
> six CMake 23/25/3/7/6/16, 7/7 markers), CI DFRoot evidence gates, LKM 1/1,
> JUnit 39/0/0/0 — all pass; helper-size **30** is **author-reported** (CI
> prints pass, not a count). **Signed release APK and Publish GitHub Release
> jobs SKIPPED; no DFRoot APK uploaded.** CI does **not** verify any device
> result. No completed-green CI conclusion is claimed for the earlier heads
> `7e18b13` or `376f5e2`.
> run6/run8 happened on 2026-11-17 against the
> **historical implementation** (old multi-marker protocol, then-current KO
> pins, old preference path). **Two S23 attempts on 2026-11-26 at the pre-fix
> head `0f0a2ad` reached a full root chain but FAILED the vendor restore (app
> rc 66 = 2 + recovery bit 64); they are attempts, not validations** (evidence:
> `/mnt/240G_SSD/opencode_tmp/s23-final/`). A later S23 retest of the APK built
> from code head `376f5e2` **passed for the P2-16 restore scope** (`cleanup:
> crash_dump64 helper patch re-established (verified)`, vendor and
> `crash_dump64` `restore OK (verified)`, no bit 64), but **still** ended
> `status: timeout waiting for final status (rc=2)` / `cleanup: unbound
> observation; recovery gate stays closed` with `/dev/dffinal` absent — a
> **separate pre-existing OPEN item**, not caused by P2-16 (`/dev/dffinal` was
> the historical channel and **is no longer produced** under D2). A later D2
> device retest **passed for the P2-17 final-status-observability scope only**:
> `final: ROOT_ONLY - no reboot requested; root not verified` then
> `status: final status observed (rc=0)`, with `dfroot.final` =
> `dfroot-v2:3fec15b2d9ad766ff5173d857f75bc38:root_only`, all restores verified
> and no bit 64 (APK sha256 `a7f6e02ebb44cb39f583ca3c32d1b818ad046be3019ce540ee4bf9be2ec001af`,
> disposable local test key, no tag/publish). That rc=0 is **scoped**: it does
> not validate the chain, quiescence, loaders or release readiness. The PR as a
> whole is **not device-verified, not independently merge-ready and not
> release-ready**; a successful build does not certify safe privileged
> execution.
> An **unconditional** `host-gates` job runs **all** host suites as required
> gates on **every** event, including protected `v*` tag pushes, with a
> checkout-SHA assertion; both `build-dfroot` and `build-release` require it,
> and `publish` requires `build-release`. Non-tag events upload **no DFRoot app
> APK**: the only DFRoot upload is the `DFRoot-CI-evidence` bundle
> (`GPL-3.0.txt`, `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`). The
> intermediate `df-lkm-${kmi}` KO artifact **is** uploaded separately by
> `build-lkms` on every event; it is a build intermediate, not a DFRoot app APK
> and not a release claim. The Debug APK is a compile/content gate only. Only a
> `v*` tag push takes the protected `build-release` path, where the signed APK
> is `apksigner`-verified and checksummed, then every distributable APK is
> packaged-byte-verified with its own manifest and `so_sha256` binding **and
> has both bundled loader entries extracted and checked for SHA-256 + size
> against the CMakeLists.txt pins by the real verifier** (Phase B gate
> `Verify bundled loader bytes in every distributable APK`); every provenance
> record gets a required identity check, a nonempty per-APK loader index is
> required at the pre-upload choke point, and release evidence is asserted at
> a single pre-upload choke point before `publish` can run. This is a host/CI
> packaging gate, **not** device validation; release markers stay 3/3 and host
> markers stay 7/7.
> **Loader-byte preservation (P2-15, Phase 1 `7e18b13`):** the Phase B gate
> checks bytes inside distributable APKs, but AGP 9.5 strips debug symbols
> from bundled release JNI libraries by default. A local release build from
> `74b12df` packaged different loader bytes even though the committed inputs
> and build-time pins were correct. `app/build.gradle.kts` now sets
> `packaging.jniLibs` `keepDebugSymbols += "**/libksud.so"` and
> `keepDebugSymbols += "**/libksudnext.so"` so both entries keep their reviewed
> bytes in release APKs. Host-only local-key evidence, no release/device claim
> (see `CHANGELOG.md` round 11).
> Provenance manifests are informational evidence, **not** an attestation or
> an independent reproducible-build proof.
> Host suites at the Phase B code head (`0ea022f`, local verification; at the
> literal head only the workflow policy count below and the helper-size count
> change): **61**
> status + `final_publish` + 13 proc-scan + **28 ELF** + 16/14/10 device-version
> + **40 loader-stage** cases (`tests/native/loader_stage_test.c`) and the
> helper-size suite (`tests/native/helper_size_test.c`, **30 cases at `376f5e2`,
> was 7**), CMake **23/25/3/7/6/16**
> cases (KO policy / packaged-KO / APK packaging / APK content **7** cases /
> target graph / loader hash **16** cases), JVM **39 tests** (4/10/12/13 in
> LaunchGate / ListenerLifecycle / PreferenceIntegrity / PreferencePersister)
> with a strict **39/0/0/0** XML assertion, **7/7** host suite markers on every
> event and **3/3** release markers (plus the nonempty gate index, the required
> per-APK loader index and single upload choke point) on the tag path. Workflow
> policy (`tests/workflow/test_policy.py`): **92 tests** (was 90 at rounds 8–10
> and 85 earlier; P2-15 regression added at `7e18b13`).
> No required gate uses `continue-on-error`.
> **KO pins:** the byte gate rejects unknown bytes but still accepts the two
> known obsolete pins (`94f8be…` / `9dfb8b…`, 13504 / 13384 B). Release is
> withheld by **policy** — a reviewed re-pin, the OPEN P1-08 staging gate and
> device validation — not by the hash gate alone; passing the gate is not device
> validation. Gradle passes the Android variant (`release` / `debug`) as the
> authoritative native policy signal, and the KO is linked into a dedicated
> `.dfroot_ko` section whose final bytes in `libdfroot.so` are SHA-256-checked
> against the same pins.
> The app reads the single authoritative nonce-bound final outcome from the
> **non-persistent Android property `dfroot.final`** (context
> `u:object_r:default_prop:s0`; root/bootstrap sets it, the app reads it;
> unprivileged `setprop` is denied). **`/dev/dffinal` is no longer produced.**
> The token and rc mapping are unchanged (truthful rc 0–9 plus the additive 64
> recovery bit), with exactly-once cleanup on
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
> historical and validate only the old loaders. **P1-02 and P1-07 also remain
> OPEN safety/release gates**; the two pre-fix S23 attempts returned rc 66,
> but **P1-07 remains OPEN** even though restore verification is now
> device-confirmed on the retest runs — quiescence and failure-interruption
> remain unproven. The pre-D2 rc=2 unbound-observation timeout was a separate
> OPEN item; the D2 retest observed rc=0 through `dfroot.final`, which confirms
> final-status observability only and does not close any P1 gate.
> P2-13 and P2-14 are unchanged (Phase A/B, pending final comprehensive
> review). **P2-15 was addressed in Phase 1 (`7e18b13`) and is pending final
> comprehensive review** — not claimed finally closed. **P2-16's restore defect
> was addressed in `376f5e2` and is device-confirmed closed on S23 for the
> restore scope** (retest passed: helper patch re-established and verified
> restores of vendor file and `crash_dump64`, no bit 64); the separate rc=2
> timeout / unbound-observation item was the pre-D2 finding. **P2-17 (D2)
> moved the authoritative final channel to the non-persistent property
> `dfroot.final`; `/dev/dffinal` is no longer produced**, and both scoped
> author-local S23 retests passed for the **final-status-observability scope
> only** (rc=0, correct nonce-bound token, all restores verified, no bit 64;
> the second at committed head `e823fb4`, no reboot requested). **P1-02, P1-07
> and P1-08 remain OPEN**; those scoped rc=0 results are not a full-chain,
> quiescence, loader or release validation and are **not independent
> validation**. Auto Soft Reboot and Start on Boot remain disabled and
> unvalidated; no independent device run validates the PR as a whole, no tag
> was created and no release was published, and this PR is **not independently
> device-verified, not merge-ready and not release-ready**. Both new local APKs
> used a **disposable local test key**, are not official signing material and
> were never tagged, uploaded or published; exact-head CI run 45864085866
> skipped the signed release APK and GitHub Release publish jobs, and the
> release job remains unexecuted. See `CHANGELOG.md` round 13 (current), round 12, round 11
> and round 8 (2026-11-26) and `PORTING-GZIF.md` §19 for the test/lifecycle
> detail.

> [!NOTE]
> **Post-rebase integration.** PR #1 (CI build reliability) is now merged into
> the base and PR #3 was rebased onto that head. run6/run8 and all earlier CI
> runs predate the rebase: they are historical and do not validate the rebased
> head. No tag was created by us.

## Sixth repair round (R6.1) — short note (2026-11-26)

Short contract note (R6.5 consolidates): a continuously visible Activity now
runs a bounded, lifecycle-scoped **read-only** outcome watch (25 ticks at 200 ms,
cancelled on stop/destroy). Each tick only calls the existing ledger-only
reconciliation for that Activity's own request; if no record exists after the
window, the UI states once that the outcome is unconfirmed and that no automatic
retry or recovery exists in this app. Nothing is authorized, replayed, retried,
cancelled or cleared, and the run control stays disabled while unconfirmed. This
is an honest UI status, **not** evidence about the privileged operation; R2-14
and R2-16 remain OPEN safety gates. No device/ADB/payload/privileged work was
performed.

### R6.2 (2026-11-26) — eviction/absence contract — short note (R6.5 consolidates)
A `null` ledger lookup — whether the request was never recorded or its record
was evicted from the bounded 32-entry LRU during a sufficiently long absence —
means **UNCONFIRMED** only. It is never an outcome and never evidence of
admission, rejection or completion: neither the watch nor a later `onStart()`
fabricates a reason, replays/retries the request, clears durable state or
enables the run control. The ledger bound (32), reporter capacity (64), the
separate `outcomeLock` and the strict `lock -> outcomeLock` order are unchanged;
this phase adds Javadoc and tests only, with no executable production change.
R2-14 and R2-16 remain OPEN gates.

## Seventh repair round (R7.1–R7.3) — consolidated summary (2026-11-26)

Final local HEAD `57df54c` (R7.1 `985fa71`, R7.2 `36076de`, R7.3 `57df54c`)
on top of the R6 close sequence (`92572ea` → `528568d` → `f954cbb`; R6 code head `9e1aff7`). This round addresses
Re-review 6. The three dispositions are:

- **R2-24 — FIXED in the reviewed host scope (R7.1, `985fa71`).** Notification
  construction now fails closed inside its own `RuntimeException` boundary,
  outside `startsLock`: one request-correlated `REJECTED` with an honest
  "notification setup failed" reason and exactly one `stopSelf` for that start,
  with no WakeLock, admission, barrier, owner-token or foreground claim.
  **83 focused host tests, 0 failures**; two independent reviews APPROVED.
- **R2-17 — FIXED in the reviewed host scope (R7.2, `36076de`).** The bounded
  outcome watch takes exactly **one** read-only ledger observation per tick; the
  same record drives reconciliation and the final-tick unconfirmed decision, so
  a late outcome with a lost observer post can no longer leave the pending UI
  blank/disabled. A host-only lookup seam, inert and writes-nothing in
  production, makes a deterministic **red-green** discriminator: red under the
  old two-read algorithm, green after the fix. **52 focused `MainActivityTest`
  cases, 0 failures.** Test quality first found the initial tests
  non-discriminating → replaced with the discriminator and re-reviewed RESOLVED;
  correctness APPROVE; both reviews APPROVE otherwise.
- **R2-18 — SCOPED / DOCUMENTED, NOT fixed or closed (R7.3, `57df54c`).** Zero
  production behavior change: an honest scope note plus harmless host fixtures.
  The main-thread start checks `flushInProgress` once, so a duplicate arriving
  while the express worker holds `startsLock` in foreground attach (with **no**
  active flush) can still briefly contend on that monitor; the
  refused-express fallback and `onDestroy()` bookkeeping also take short
  caller-side `startsLock` sections that a slow platform call can delay.
  **85 focused host tests, 0 failures**; two independent reviews APPROVED. This
  is bounded host-only evidence of the residual and the safety gate, **not a
  device ANR proof and not a foreground-service-grace-timing proof**.

**Evidence framing.** All of the above is focused host-only verification plus
independent static review: no device, ADB, payload, privileged-container,
crash/power-loss or APK work, no fresh full-suite run, and no device-ANR or
foreground-service-grace proof. A red/green discriminator built on an inert
host-only seam proves the host algorithm change, **not** device behavior.
**Green host CI is not device safety and is not release approval.**

**Still-open gates and residuals (unchanged unless noted).** **R2-14**
(publication/acknowledgement observation window and abnormal-death window) and
**R2-16** (non-atomic OFF authorization) remain **SCOPED / OPEN** — neither is
closed or proved safe. **R2-02 and R2-07 remain linked to those gates only.**
The R2-18 `startsLock` contention is documented, not fixed; the R9.4 section
below adds only finite host-only responsiveness evidence and does not close it.
Earlier R5/R6
residuals remain: per-session 25-tick watch budget, no explicit `onPause()`
cancel, one-shot post-winner sweep, four-slot terminal replay drops, and rare
short caller-side `startsLock` sections. Also unchanged: signed-release job
packaged-pin gate inherited from PR #1 policy, no in-app recovery UI,
`BOOT_COUNT` diagnostic only, `/dev` marker-root trust, and the OPEN
abnormal-death publication window. R8's 2026-11-27 real-device observations are
supporting only and do NOT change any listed gate state.

**Final comprehensive reviews all APPROVE at the R7 final head `020175b`** (R7 code head `57df54c`):
the main agent runs four fresh independent reviews after this consolidation.
Historical APPROVE outcomes (including R6 Final 6.1–6.6 at `92572ea`) are
scoped to their own heads and are **not** an approval of the current HEAD.
Keep PR #2 draft/open; `tasks/plan.md` and `tasks/todo.md` keep Checkpoint R7
and Final 7.1–7.6 UNCHECKED.

### R7.1 (2026-11-26) — notification setup failure boundary — short note (R7.4 consolidates)

Notification construction in the foreground host now runs inside the same fail-closed
setup boundary as a foreground-start failure. A `RuntimeException` from
`PendingIntent`/`Notification.Builder` produces one request-correlated `REJECTED`
with an honest "notification setup failed" reason and resolves that start's own
lifetime with exactly one `stopSelf`. It stays outside `startsLock`, so R5.4 attach
serialization is unchanged; no WakeLock, admission command, barrier change,
owner-token change, foreground attach/removal claim or second `stopForeground`
occurs on this path, and `START_NOT_STICKY` is unchanged. Two deterministic
host-only fixtures cover the boundary and an unrelated request's outcome.
R2-14 and R2-16 remain OPEN safety gates; no device/ADB/payload/privileged work
was performed.

### R7.2 (2026-11-26) — one ledger observation per watch tick — short note (R7.4 consolidates)

The bounded UI watch now takes exactly **one** read-only ledger observation per
tick. That same record feeds both reconciliation and the final-tick unconfirmed
decision, so a correlated outcome arriving between the old two reads can no
longer leave the pending UI blank/disabled: the observed record is reconciled,
and only an observed absence produces the one-per-token honest unconfirmed
status. A `null` or host-only scripted observation still means UNCONFIRMED —
never admission, rejection or completion. A host-only deterministic lookup seam
is inert in production (no seam installed). Watch bound (25 × 200 ms), ledger
32, reporter 64, no replay/retry/cancel/authorize/durable clear, and the
positive/unchanged run-button semantics are preserved. R2-14/R2-16 remain OPEN;
R2-18 and R2-24 are untouched. No device/ADB/payload/privileged work was done.

### R7.3 (2026-11-26) — residual `startsLock` contention — SCOPED / DOCUMENTED, short note (R7.4 consolidates)

Re-review 6 finding **R2-18 is SCOPED / DOCUMENTED here, NOT fixed or closed**:
no production behavior changed in this phase (tests/docs only). The residual is
stated explicitly. The main-thread start branch checks `flushInProgress` once;
a duplicate start arriving while the express worker is inside foreground attach
under `startsLock` **without an active flush** can still enter
`registerStart`/duplicate checking and briefly contend on that monitor. The
refused-express fallback and `onDestroy()` pending-work bookkeeping likewise
take short caller-side `startsLock` sections; a slow platform call under that
monitor can delay them. Harmless host-only fixtures pin the fail-closed
outcome of those interleavings (one correlated `REJECTED`/`CANCELLED`, one
lease close and lifetime resolution, no second lease, no submit/engine, no
barrier, no duplicate `stopForeground`, `START_NOT_STICKY`) and the graceful
drain of the owned express worker. This is **bounded HOST-ONLY evidence of the
residual and the safety gate, NOT a device ANR proof and NOT a foreground-service
grace-timing proof**. Treat delayed foreground setup and UI-thread blocking as
ordinary Android lifecycle/ANR risks: the safety gate is retained, authorization
is never weakened, and uncertainty about foreground ownership never leads to
continued work. R2-14 and R2-16 remain **OPEN** safety gates; R2-24 is untouched.
No device/ADB/payload/privileged work was done.

## Eighth documentation round (R8) — supporting device evidence and honest limitations (2026-11-27)

**Verdict unchanged: REQUEST CHANGES / keep PR #2 draft/open.** Re-review 8 is a
status recheck of the identical reviewed head `be9201c`, not a fresh line-by-line
review and not new verification or a freshly executed test suite. **19/24 findings
addressed in the reviewed scope; five open/linked, corresponding to three
substantive OPEN gates: R2-14, R2-16 and R2-18.** R2-02 and R2-07 remain linked
to the relevant existing gates only; R2-17 and R2-24 remain closed in the
previously reviewed scope. No new finding IDs were assigned. The reviewer's
instruction stands: do not expand the implementation merely to generate more
review iterations, and a re-review of an identical revision is not new
verification.

The non-blocking housekeeping item was applied on the GitHub side to the PR
description: the stale `signing.properties` / "base CI fails" claim was removed
and replaced with the actual outstanding limitations. No code, head change or
repository file was involved in that correction.

### Real-device observations (2026-11-27) — SUPPORTING ONLY

Every item below is a real-device observation on the exact reviewed build and is
explicitly **SUPPORTING ONLY**; none closes a gate, proves device safety, or
endorses merge or release.

- **SUPPORTING ONLY — baseline:** full exploit chain on S23 Ultra SM-S9180 /
  `S9180ZHU8GZIF` at the exact reviewed build — preflight fallback, `hook armed
  (/dev/df)`, module bootstrap/SU daemon, and verified restore of all four
  patched files. `su -c id` → `uid=0(root)`, `su -v` → `3.4.2:KernelSU`; honest
  final `ROOT_ONLY — no reboot requested; root not verified` with rc=0; no
  FATAL, no ANR. **Non-claim:** this is not device safety and not release
  approval.
- **SUPPORTING ONLY — R2-14 abnormal death:** force-stop mid-run (during
  patching) produced no fabricated success on relaunch — the output view was
  empty and the button stayed disabled. **Non-claim:** the precise
  rc7-publish↔acknowledgement race was not instrumented or proved; the gate
  stays OPEN.
- **SUPPORTING ONLY — R2-16 OFF authorization:** disabling the manager made the
  `LAUNCH ROOT` button disabled; Start-at-Boot status read
  `Status unavailable - not confirmed disabled` and failed closed.
  **Non-claim:** the precise non-atomic OFF window during the final
  authorization read was NOT hit; the gate stays OPEN.
- **SUPPORTING ONLY — R2-18 duplicates/traffic:** three rapid taps produced one
  `hook armed` (count 1) — no second admitted run — with no FATAL/ANR and the
  device responsive. **Non-claim:** this is no ANR proof and no
  foreground-service-deadline proof; the gate stays OPEN.
- **SUPPORTING ONLY — soft reboot:** with Auto Soft Reboot enabled the native
  result went `REBOOT_PENDING` → `REBOOT_SKIPPED (late-load not confirmed
  absent)` at rc=5; uptime rose continuously and never reset, so no soft or
  hard reboot occurred. **Non-claim:** the experimental hard-reboot risk is NOT
  removed; one observed fail-closed skip is not exhaustive over every
  interleaving.

Also **SUPPORTING ONLY:** reboot after the interrupted run left `su` absent and
`/dev/df` absent, confirming volatile root — not a claim about any gate state or
device safety.

**Narrow constraint.** Earlier rounds had no device/ADB work allowed; the owner
explicitly authorized FULL device testing (including soft-reboot/crash) for
evidence in R8 only. There was no payload rebuild, APK assembly, privileged
container, or PCK/guard change; the installed APK was unchanged. This round is
documentation only: no code changes and no gate closure. Green host CI run
`45787525701` is host-only evidence and is not device evidence. Keep PR #2
draft/open.

## Sixth repair round (R6.1–R6.4) — consolidated summary (2026-11-26)

Final local HEAD `9e1aff7` (R6.1 `13ee65f`, R6.2 `731e88c`, R6.3 `cdf76e7`,
R6.4 `9e1aff7`) on top of the last pre-R6 commit `fd1ccd8` (R5 code `33980e2` / docs `689f378`). This
round addresses Re-review 5. It closes the remaining **R2-17** residuals
(visible-Activity and eviction/absence halves) and reduces **R2-18** to bounded
**host-only** responsiveness evidence. **R2-14 and R2-16 remain SCOPED / OPEN
gates — not closed, not proved safe.** Focused host verification only: 46, 108,
115, then 124 and 127 focused tests across R6.1–R6.4 (post-fix re-runs green),
0 failures, with two independent reviews per phase and every Required fix loop
resolved. No fresh full-suite or CI-green result, no device-ANR proof and no
device/ADB/payload/privileged-container/APK work is claimed.

The two bounded mechanisms and their supporting bounds:

1. **Read-only outcome watch (R2-17).** A continuously visible Activity runs a
   per-Activity main-looper watch armed after each manual start request: at most
   **25 ticks, 200 ms apart**, each calling only the unchanged ledger-only
   reconciliation for that Activity's own request. Exhausting the budget with no
   record shows one explicit "outcome unconfirmed" status; `null` (including
   eviction from the bounded 32-entry ledger) is UNCONFIRMED, never an outcome.
   The watch is cancelled on correlated clearing, stop and destroy, and nothing
   is replayed, retried, cancelled, cleared or authorized from an unconfirmed UI
   state — the run control stays disabled.
2. **Bounded flush responsiveness (R2-18).** A losing foreground flush waits at
   most a **200 ms monotonic deadline**; a main-thread contender never waits and
   instead queues one bounded host re-decision. Re-decisions are capped at
   **three** per lifetime, singly queued and destroy-aware, and a **post-winner
   sweep** retires a start ID resolved during the winner's guarded phase after
   the cap is spent, without spending that budget or interrupting the winner.

Supporting bounds: foreground attach and main-thread start handling run on an
owned **express** executor (16-slot queue, AbortPolicy) separate from the owned
general **host** executor (64-slot queue) that carries observer renders and the
whole winning flush phase; the flush guard is released in the posted task's
`finally`. A refused express post fails closed with a correlated `REJECTED` and
exactly one `stopSelf`, and a refused TERMINAL render is remembered in a
**four-slot terminal-only replay queue** drained by both the deferred
re-decision and the post-winner sweep (non-terminal refusals and overflow are
dropped with honest logs). The refusal itself also queues the **uncapped
single-flight sweep**, so a terminal render is guaranteed that sweep
independently of the three-deferral budget and of any later flush winner; the
sweep is host-only, destroy-aware and spends no loser deferral.

**What this does NOT prove:** this is bounded **host-only** evidence of
responsive returns, **not a device ANR proof**. Caller-side bookkeeping in rare
refusal paths still takes short `startsLock` sections, up to 16 express tasks
can precede an attach, and device foreground-service-grace timing remains an
OS/hardware gate that host fixtures cannot prove. R2-14 (publication/acknowledgement
window) and R2-16 (non-atomic OFF authorization) remain OPEN gates; no green
host CI result implies device safety. See the residual list in the R5
consolidated section below for the honest, still-open limitations.

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
writer exits 7. A duplicate/conflicting publication also publishes rc7. **Exact
guarantee:** ONCE rc7 has been published AND OBSERVED it outranks success and
conflicts and needs no confirmation. This is a correction, not a revocation:
rc7 is produced only AFTER detection, so a reader that consumed a
definitive-looking result earlier cannot have that result taken back.

**Residual window — OPEN hardware/OS gate, not proved safe.** `link()` makes the
final pathname visible before temp unlink and directory fsync complete. If the
writer dies abnormally after the confirmation link but before its fsync, an
external reader can still observe a definitive-looking result that was never
durably acknowledged. If it dies between intended-marker publication and
confirmation, the reader sees only an unconfirmed marker and must not decide.
The same observation window exists on an **ordinary detected failure**: after
the acknowledgement is visible but before a failure is detected and its
corrective rc7 published, a concurrent reader may classify a definitive-looking
result; the later rc7 cannot revoke what was already consumed. Deterministic
host fixtures record both observations; they are boundary fixtures, not a fix
proof and not device safety. These instants are not closed by host tests;
device crash/power-loss validation of `/dev` marker durability remains required.
The safety policy is unchanged: rc7 precedence and indeterminate ownership
still apply, no device safety is implied, and R2-14 remains OPEN.

### R2-14 fail-closed policy for ambiguous outcomes (R9.3, 2026-11-27) — SCOPED / OPEN

The two residual items handled in R9 — unattended Start at Boot and experimental
soft reboot, both made unavailable in production — are recorded in
[issue #6](https://github.com/samson910022/DFRoot-GZIF/issues/6) with explicit
owner acceptance. That issue does not close, weaken or reclassify R2-14, R2-16
or R2-18.

The finite policy is: **an unconfirmed completion is never a confirmed outcome**.
Native rc=7 (writer/reader deadline, unconfirmed or contradictory terminal
markers), an unrecognized exit, an engine exception, a supervision
timeout/interruption, and an absent or evicted ledger record resolve only to
**INDETERMINATE / UNCONFIRMED**. From that state the app makes no success claim
(no success toast, no terminal-success log line, no fabricated rejection),
starts no replay, retry or further potentially unsafe action, keeps the run and
manager controls disabled, and retains the owner token, resource lease,
configuration freeze and durable attempt barrier. A `null` ledger observation
is uncertainty, never evidence of admission or completion. Only an explicit,
checked recovery by a trusted caller outside the UI can release the barrier;
nothing in the UI, service or a timer authorizes it automatically.

This is demonstrated with harmless host-only mocked outcome/failure stand-ins
across the mapper, the coordinator/barrier and the coordinator→result→UI
boundary. The privileged mechanism is neither improved nor exercised; no device
safety is implied. **R2-14 remains SCOPED / OPEN — not closed, not proved safe,
and not a merge, release or reviewer approval.**

### R2-18 bounded ordinary app lifecycle (R9.4, 2026-11-27) — SCOPED / OPEN

**Finite requirement (host-scope behavior contract, not a device guarantee).**
On the main-thread start path, when a foreground flush already holds the guard,
the entire start handling is posted as **one** task to the owned express worker
and `onStartCommand` returns `START_NOT_STICKY` without entering `startsLock` or
waiting on any platform call. Foreground attach and flush platform calls run on
owned workers; a main-thread flush contender never waits on the flush monitor
and instead queues one bounded host re-decision, while an off-main loser waits
only to a finite **200 ms monotonic deadline** and then defers without claiming
a result. Under a simulated **slow** platform call the offloaded caller returns
within a finite **1 s** bounded wait and the owned worker drains within the
fixture's 5 s `TEST_TIMEOUT_MS`; under a simulated **throwing** attach the only
effect is exactly one correlated `REJECTED` and one lifetime retirement. In
every case there is **one** admission decision, **at most one** owned WakeLock
lease, **no second** start/engine run, **no new** durable barrier, **no freeze**
of manager configuration, **no duplicate** `stopForeground`, and **no unbounded
caller-side wait**. This is a finite, defined responsiveness requirement; it is
**not** a claim of no ANR on every device, **not** a foreground-service-grace
timing proof and **not** device safety. Absolute proof of no ANR on every device
is not offered.

**Evidence scope.** R9.4 adds host-only fixtures that reuse the existing
attach/flush gates and executor seams: a slow attach pins `startsLock` while a
main-thread start is offloaded and rejected as a single claim; a throwing
`startForeground` fails closed on the owned express worker; and a throwing
`stopForeground` still retires every start ID in the immutable snapshot. They
assert no second lease/admission/engine, no barrier or manager-config change,
no duplicate foreground claim, and graceful owned-worker shutdown. The
offloaded caller is asserted with a 1 s bounded `Future.get`; owned-worker
drain uses the 5 s `TEST_TIMEOUT_MS`. R9.4 also makes ONE narrow production
change: the guarded `stopForeground` call is wrapped in a `RuntimeException`
catch with one honest `Log.w`, so the immutable `stopSelf` loop always runs and
no start ID is stranded when that platform call throws. A host-only, null-in-
production `stopForegroundFailureForTesting` seam injects that failure; it is
reset in the service lifetime paths and has no production caller. The documented
R6.4 deadline/offload constants and the R7.3 `startsLock` contention residual
are unchanged. No device/ADB/payload/privileged work was done. **R2-18 stays
SCOPED / OPEN — not closed, not device-proved, and not a merge, release or
reviewer approval.** If the affected feature is to be distributed, its
device-specific behavior remains an explicit release gate.

## Operation ownership, boot, and soft reboot

**R9 availability boundary:** unattended **Start at Boot** and **manual soft
reboot** are both made **unavailable in production** (unconditional refusal,
refused writes/artifact, native hard `0`; see issue #6). The paragraphs below
describe the retained safety mechanisms and historical behavior for audit; they
do **not** describe an enabled feature. "Turning it ON" and "Auto Soft Reboot"
are no longer reachable from the shipped UI.

- A process-scoped `RunCoordinator` owns one privileged operation at a time and rejects duplicates. Destroying the Activity only detaches observation; it never cancels or interrupts a started run. Each UI request carries a click-time ID: the coordinator publishes `ACCEPTED`, `REJECTED` or `CANCELLED` for that ID, and the UI clears its pending state only on a matching outcome (never on an unrelated snapshot). `ExploitService` is a Direct Boot-aware `specialUse` foreground service that supplies process lifetime and a timed partial WakeLock for both manual and boot runs, and returns `START_NOT_STICKY` so indeterminate work is never silently replayed after process death. Service destruction publishes `CANCELLED` **only** for unclaimed service-local work and never replays it; accepted work stays coordinator-owned.
- **Start on Boot** is claim-gated: `BootReceiver` validates the broadcast and durably claims the current `Settings.Global.BOOT_COUNT` in device-protected storage before starting the service. `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` share the claim, so there is **one attempt per boot**; if the boot count is unavailable or the claim cannot be persisted, unattended execution is aborted. The claim re-checks the receiver component at claim time and fails closed on an unreadable/default state. That queued-request recheck is defense in depth and READ-ONLY: the **authoritative final gate** is checked inside `RunCoordinator.submit()` under the same lock as the durable attempt publish, immediately before that publish, with another recheck before the engine starts, and it fails closed on OFF/unreadable. OFF (or an unreadable setting) observed at those reads blocks an unattended operation even when the toggle races a blocked publish, initialization or delayed worker, and the checked barrier clear remains allowed for a proven pre-effect rejection. **Honest boundary:** the OFF write is a `PackageManager` component-setting operation **outside the coordinator monitor**; it cannot be made atomic with the gate read or `engine.run()`. An OFF written after the final pre-engine re-read therefore cannot be observed before the engine starts — this is a residual window, not a claimed guarantee, and no device safety is implied. An OFF toggle **after** `engine.run()` has started is **not** revoked — the coordinator keeps ownership of privileged work instead of cancelling it. Foreground-service start eligibility from boot still requires a hardware/OS gate, not a code claim.
- **Start at Boot can be turned OFF independently in every state** — including configuration ERROR and active/indeterminate operations — without touching the current operation's admission, lease, or finish semantics. In R9 the production authorization is an unconditional refusal; the switch is hard-disabled and presented as unavailable, so there is no ON path in the shipped UI. A rejected component change is surfaced in the UI with the state actually applied.
- **An unconfirmed attempt is a durable admission barrier.** It is persisted in device-protected storage before any privileged work or `run_config` publication. A new process loads it and rejects both manual and boot requests fail-closed until a trusted caller clears the barrier, regardless of boot count. On indeterminate results `RunCoordinator` keeps the lease, the frozen configuration, and single-flight rejection; recovery is a single-claim, trusted-caller API with **no in-app UI** — an ordinary user is intentionally locked out until a trusted caller clears the barrier. **Resetting app data is NOT safe recovery**: it only discards the barrier and is not evidence that privileged effects stopped, so it must not be presented as a way out of the lockout. `engineFinished` only proves the Java engine call returned/threw, not that externally owned effects ceased; no in-app retry/clear control is exposed from it.
- **Soft reboot is unavailable in production** (R9): unattended remains disabled and manual is refused at write, artifact and native decision with a hard `0`; legacy acknowledged configs are migrated OFF on load. It was experimental and unvalidated on GZIF and has previously caused a full hard reboot on this model; no completed soft reboot was observed, so the risk is retained (issue #6). Root is volatile late-load: re-run LAUNCH ROOT after every reboot; no `boot.img` is flashed, and no root probe is added.

## FAQ

**Q: Log says patching failed and that my device isn't vulnerable**

A: Sorry, there is no fix. Either:
  - Your device's kernel is too new
  - Your device manufacturer backported the official mitigation
  - [Accidental mitigation](https://github.com/V4bel/dirtyfrag/issues/23#issuecomment-4405314290) (typically seen on kernel 6.1)

**Q: Log says "SUCCESS" or "ksud exited with error", but I don't have root**

A: `/dev/dfm6` / `ksud start: SUCCESS` is a progress diagnostic, not proof of root — the final result now waits for a run-scoped terminal marker, so an early SUCCESS no longer ends the run by itself. Check the final app result and verify with `su -c id` / `su -v` (`PORTING-GZIF.md` §8.3). Manual soft reboot is unavailable in the shipped build (R9) and was experimental and unvalidated on GZIF; it has caused a full hard reboot here (https://github.com/diabl0w/DFRoot/discussions/69; `PORTING-GZIF.md` §9.6). There is no "Auto Soft Reboot" toggle to disable in R9.

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

- Start on Boot — **unavailable in production (R9)**: production authorization is an unconditional refusal; the retained claim/dedup mechanism remains for audit (issue #6)
- Soft reboot — **unavailable in production (R9)**: refused at write, artifact and native decision; manual soft reboot was experimental/unvalidated on GZIF (issue #6)
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
APK assembly requires the gitignored `app/src/main/jni/ko/dfroot-android13-5.15.ko`;
no KO is committed, and assembling without it would require a privileged DDK
container.

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
- **PR #2 loader-pin gate (integration addendum):** the Debug APK built in a
  non-tag event is checked with `:app:verifyDebugPackagedLoaderPins`; the
  signed release APK on the protected `v*` tag path is checked with
  `:app:verifyReleasePackagedLoaderPins`. Both steps are `set -euo pipefail`,
  not skippable, and a failure stops the job before any artifact handling.
  This adds PR #2's integrity check to master's distribution policy; it does
  **not** make non-tag APKs distributable.

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

## PR #2 / R8 supporting evidence (integration addendum — no gates closed)

PR #2's R8 documentation round recorded author-reported supporting device
evidence and the disposition of R2-14/R2-16/R2-18. It is preserved here as a
supporting record only. **No gate is closed by this section**; R2-14 (honest
ambiguous-outcome policy), R2-16 (unattended authorization contract), R2-18
(bounded ordinary lifecycle) and the experimental soft-reboot limitation remain
OPEN with an owner and a release hold. The PR #3 ledger in `CHANGELOG.md` and
this file remains authoritative for protocol, staging, signing and distribution.
No new device, ADB, payload, privileged or reboot experiment was performed for
this integration.

## Credits

- Original PoC and various code: https://github.com/lsposed/lspromise
- SELinux Permissive kernel modules and various code: https://github.com/polygraphene/DFReroot
- Unprivileged XFRM socket method: https://github.com/combeng6th/DirtyInit
