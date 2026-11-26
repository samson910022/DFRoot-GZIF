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
The R2-18 `startsLock` contention is documented, not fixed. Earlier R5/R6
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

## Operation ownership, boot, and soft reboot

- A process-scoped `RunCoordinator` owns one privileged operation at a time and rejects duplicates. Destroying the Activity only detaches observation; it never cancels or interrupts a started run. Each UI request carries a click-time ID: the coordinator publishes `ACCEPTED`, `REJECTED` or `CANCELLED` for that ID, and the UI clears its pending state only on a matching outcome (never on an unrelated snapshot). `ExploitService` is a Direct Boot-aware `specialUse` foreground service that supplies process lifetime and a timed partial WakeLock for both manual and boot runs, and returns `START_NOT_STICKY` so indeterminate work is never silently replayed after process death. Service destruction publishes `CANCELLED` **only** for unclaimed service-local work and never replays it; accepted work stays coordinator-owned.
- **Start on Boot** is claim-gated: `BootReceiver` validates the broadcast and durably claims the current `Settings.Global.BOOT_COUNT` in device-protected storage before starting the service. `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` share the claim, so there is **one attempt per boot**; if the boot count is unavailable or the claim cannot be persisted, unattended execution is aborted. The claim re-checks the receiver component at claim time and fails closed on an unreadable/default state. That queued-request recheck is defense in depth and READ-ONLY: the **authoritative final gate** is checked inside `RunCoordinator.submit()` under the same lock as the durable attempt publish, immediately before that publish, with another recheck before the engine starts, and it fails closed on OFF/unreadable. OFF (or an unreadable setting) observed at those reads blocks an unattended operation even when the toggle races a blocked publish, initialization or delayed worker, and the checked barrier clear remains allowed for a proven pre-effect rejection. **Honest boundary:** the OFF write is a `PackageManager` component-setting operation **outside the coordinator monitor**; it cannot be made atomic with the gate read or `engine.run()`. An OFF written after the final pre-engine re-read therefore cannot be observed before the engine starts — this is a residual window, not a claimed guarantee, and no device safety is implied. An OFF toggle **after** `engine.run()` has started is **not** revoked — the coordinator keeps ownership of privileged work instead of cancelling it. Foreground-service start eligibility from boot still requires a hardware/OS gate, not a code claim.
- **Start at Boot can be turned OFF independently in every state** — including configuration ERROR and active/indeterminate operations — without touching the current operation's admission, lease, or finish semantics. Turning it ON still requires verified configuration, an idle coordinator, and consent; a rejected component change is surfaced in the UI with the state actually applied.
- **An unconfirmed attempt is a durable admission barrier.** It is persisted in device-protected storage before any privileged work or `run_config` publication. A new process loads it and rejects both manual and boot requests fail-closed until a trusted caller clears the barrier, regardless of boot count. On indeterminate results `RunCoordinator` keeps the lease, the frozen configuration, and single-flight rejection; recovery is a single-claim, trusted-caller API with **no in-app UI** — an ordinary user is intentionally locked out until a trusted caller clears the barrier. **Resetting app data is NOT safe recovery**: it only discards the barrier and is not evidence that privileged effects stopped, so it must not be presented as a way out of the lockout. `engineFinished` only proves the Java engine call returned/threw, not that externally owned effects ceased; no in-app retry/clear control is exposed from it.
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
  Before that upload, the `Verify packaged loader pins` step runs **both**
  `verifyDebugPackagedLoaderPins` and `verifyReleasePackagedLoaderPins` under
  `set -euo pipefail`, with no event-name guard: on every event that builds the
  pair, a failed pin check stops the job before `Verify APK counts` and
  `Upload DFRoot APKs`, so unverified APKs are withheld from the artifact.
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

## Fifth repair round (R5.1–R5.8) — consolidated summary (2026-11-26)

The fifth repair round addresses Re-review 4 on `fix/app-correctness-safety`
(final local HEAD `33980e2`, on top of R4 `74d35f5`). It closes **R2-17, R2-18,
R2-20, R2-21 and R2-23 in reviewed scope**; **R2-14 and R2-16 remain OPEN
gates — scoped and documented honestly, NOT closed and NOT proved safe.**
Every phase was verified by focused host runs and independent static reviews
only. No device, hardware, ADB, payload, privileged-container, cold-boot,
soft-reboot, crash/power-loss or APK-assembly validation was performed, and no
fresh full-suite or CI-green result is claimed here. The R4 counts of **623 JVM
tests / 45 suites** and **140 host CTest**, and the Re-review 4 CI counts of
**138/138 host-native tests and 623 Java tests across 45 reports**, are
**prior-round baselines**, not an R5 result.

| Phase / commit | Finding | Evidence (host-only) |
|---|---|---|
| R5.1 `f9e55a9` | R2-17 (stopped-Activity half) | Bounded 32-entry LRU outcome ledger under a separate `outcomeLock` (strict `lock -> outcomeLock`); record before best-effort post; `onStart()` reads only its own ID; absence stays uncertainty. 90 focused tests, 0 failures; two reviews APPROVED. |
| R5.2 `3a221ab` | R2-18 | No production change: the synchronous start-failure catch already uses the off-lock `noteRequestOutcomeAsync`. Blocked-store/throwing-starter fixtures; 93 focused tests, 0 failures; two reviews APPROVED. |
| R5.3 `05ff93a` | R2-21 + R2-17 (dispatch half) | 64-slot abort-on-full reporter with authoritative ledger; active starts and resolved history bounded at 64 each; eldest resolved ID retired with its own `stopSelf`. 172 then 112 focused tests, 0 failures; Required eviction/bound issues fixed and re-reviewed RESOLVED. |
| R5.4 `48bf31b` | R2-20 (foreground race) | `startsLock` spans the foreground attach; flush epoch recheck and single `stopForeground(REMOVE)` under that lock. 62 focused tests, 0 failures; Required attach-side gap fixed and re-reviewed RESOLVED. |
| R5.5 `513af88` | R2-20 (liveness half) | Pre-engine authorization read precedes owner-token liveness under the `engineStarted` lock; an `onDestroy` during the read is denied pre-effect. 74 focused tests, 0 failures; two reviews APPROVED. |
| R5.6 `3ef725c` | R2-16 — SCOPED, not closed | Documentation/boundary only, zero executable production change: removed the atomic-with-Settings overclaim; final gate is a re-read, fail-closed on OFF/unreadable, no post-start revocation. 75 then 59 focused tests, 0 failures; Required guard assertion fixed and re-reviewed RESOLVED. |
| R5.7 `e9b12c6` | R2-14 — SCOPED, gate OPEN | Narrowed the "never sees actionable success on ANY detected failure" overclaim; host-only pause seam plus two deterministic fixtures and a proved/not-proved table; 142 host CTest, 0 failures; two reviews APPROVED. |
| R5.8 `33980e2` | R2-23 | `build-dfroot` verifies **both** debug and unsigned-release packaged loader pins in one fail-closed step before APK counts/upload, on every trigger. Policy 73/73, make 36/36, pins matched; two reviews APPROVED. |

**Result contract (unchanged).** Marker conflict → rc7, never rc1; a definitive
non-7 result requires a matching `dfroot_confirm_` marker; `/dev/df` and
`/dev/dfm6` remain owner-visible **progress-only** SUCCESS diagnostics, never a
terminal result. **Publication boundary — OPEN:** once rc7 is published AND
observed it outranks success/conflicts, but a definitive-looking confirmation
can be consumed between its `link()` and temp unlink/directory fsync, a detected
failure produces corrective rc7 only after detection, and a later correction
cannot revoke an already consumed result. Abnormal death and ordinary detected
failures in that window are **not proved safe**; device crash/power-loss
validation of `/dev` marker durability remains required (see the publication
window note in [Result contract](#result-contract-what-success-means)). R2-14
stays OPEN.

**OFF / authorization boundary — OPEN.** The last authorization check is a
re-read of unattended authorization under the coordinator lock immediately
before `engineStarted`; OFF/unreadable at that read blocks a not-yet-started
action, and work already started is never revoked. The `PackageManager`
component-setting write is **outside** that lock and cannot be made atomic with
the read or `engine.run()`: an OFF written after the re-read is not observed
before the engine starts. This is a documented residual window, not a claimed
guarantee or device safety (see [Operation ownership](#operation-ownership-boot-and-soft-reboot)).
R2-16 stays OPEN.

**Bounded reporting and lifetime.** Correlated outcomes are recorded on the
caller before best-effort posting to a bounded reporter, so an overflow or
rejected enqueue cannot lose the ledger record; an unreported outcome remains
uncertainty for the Activity, never proof of admission. Active starts and
resolved history are bounded and resolved start IDs are retired with their own
`stopSelf`, so a long-lived indeterminate host cannot accumulate unbounded
scan sets. Single-flight admission, the durable attempt barrier and all OFF
gates are unchanged; no operation is replayed, retried, cancelled or
transferred.

**CI variant verification.** On PR/branch/manual runs the APK job now runs both
`verifyDebugPackagedLoaderPins` and `verifyReleasePackagedLoaderPins` in one
fail-closed step before `Verify APK counts` and `Upload DFRoot APKs`, so a
failure withholds unverified artifacts. The signed-release/publish job,
digest pins, action SHAs, permissions, allowlists and manifest are untouched;
the signed-release job packaged-pin gate remains inherited PR #1 policy.

**Lockout / reset honesty.** There is still no in-app recovery UI: an ordinary
user with an unconfirmed persisted attempt is intentionally locked out until a
trusted caller clears the barrier. Resetting app data only discards the barrier
and is **NOT** evidence that privileged effects stopped, so it is not safe
recovery; `engineFinished` only proves the Java engine call returned/threw; a
new `BOOT_COUNT` is diagnostic only. Unattended soft reboot stays OFF and
manual soft reboot remains experimental/unvalidated on GZIF.

**Final comprehensive reviews all APPROVE at the R6 final head (`92572ea`, R6
code `9e1aff7`)** (this R5 section was written when the pending head was
`33980e2`; R6 has since added four phase commits and a final fix loop). Four
fresh reviews ran: correctness/lifecycle, security/honest-claims,
architecture/native and test/build/CI, all APPROVE; the one Required final
finding was fixed and re-reviewed RESOLVED. The APPROVE outcomes recorded for
R4 were at `74d35f5` and the R5 final reviews at
`689f378`; both are historical and neither is an approval of the current HEAD.
`tasks/plan.md` and `tasks/todo.md` keep the final 6.1–6.6 checkboxes
UNCHECKED. Residual optional items recorded, not silently accepted: R5.3
reporter-overflow UX until `onStart()` and R5.4 interrupted-waiting /
`stopForeground`-under-`startsLock` ANR risk are **superseded by R6.1/R6.3/R6.4
work** (R6 bounded the waiting flush, offloaded foreground platform calls and
made read-only reconciliation independent of observer delivery; the winner's
platform call still holds `startsLock`), but the NEW R6 residuals remain: the
25-tick budget is per visible arming session, `onPause()` does not explicitly
cancel the watch (ticks are no-ops while paused), the post-winner sweep is a
bounded one-shot backstop that does not re-queue after a no-op sweep, the
four-slot render replay still drops overflow beyond four and all non-terminal
refusals with honest logs (and is a strict no-op after `onDestroy()`), rare
refusal paths still take short caller-side `startsLock` sections,
and the R6.4-added timing-sensitive
`hostExecutorIsOwnedBoundedAndShutdownGracefully` test remains green in full runs. Also
recorded: R5.8 optional `if: always()` upload-bypass guard, benign duplicate
correlated rejection, the signed-release packaged-pin gate inherited from PR
#1, recovery UI out of scope, `/dev` marker-root trust, AES key in argv,
`BOOT_COUNT` diagnostic only, and the abnormal-death publication window (OPEN).

### R5.1 detail (2026-11-26) — replayable request-outcome ledger

Short contract note (R5.9 consolidates): `RunCoordinator` keeps a bounded (32
entries) least-recently-used ledger of correlated request outcomes under a
separate `outcomeLock`, with a strict `lock -> outcomeLock` order (never the
reverse). `getRequestOutcome(id)` and the synchronous ledger write of
`noteRequestOutcomeAsync` take **only** `outcomeLock`, so checked storage under
the durable `lock` cannot block them; observer delivery is best effort and a
rejected post leaves the record readable. On `onStart()` the Activity reads
**only its own** recorded outcome and clears pending state; a missing record is
uncertainty, never proof of admission. No operation is replayed, retried,
cancelled or transferred, and the single-snapshot slot and identity guard are
unchanged.

### R5.2 note (2026-11-26) — synchronous start failure stays off the durable lock

Short contract note (R5.9 consolidates): the Activity's synchronous
`startForegroundService` exception catch calls only
`noteRequestOutcomeAsync`, which writes the correlated `REJECTED` record under
`outcomeLock` and posts snapshot publication to the outcome executor; it never
takes the checked-storage `lock` on the UI caller. Host tests pin this with a
harmless store that blocks `publish()` while a separate boot admission holds
that monitor: the click returns with its honest reason already in the ledger,
and publication happens off-caller after the store releases. Checked persistence,
fail-closed admission, the durable barrier, single-flight and ownership are
unchanged. No device, ADB, payload or APK work was performed.

### R5.3 note (2026-11-26) — total request bookkeeping bounds

Short contract note (R5.9 consolidates): outcome reports now go through
`RunCoordinator.noteRequestOutcomeAsync`, whose caller-written ledger record
survives a rejected post to the dedicated bounded (64-entry, abort-on-full)
observer reporter; nothing is retried or admitted to save memory. The service
also bounds retained start records: active and resolved-history maps hold at
most 64 entries each. When the history is full, the eldest resolved ID receives
its own `stopSelf` at eviction (outside the bookkeeping lock, retiring only
that start ID); deferred stops still occur at the final flush. No start ID is
lost to bounded pruning, and a long-lived indeterminate host retains no
unbounded scan set. An unreported outcome is still uncertainty for the Activity,
never proof of admission. Admission, ownership, the durable barrier and all OFF
gates are unchanged. No device, ADB, payload or APK work was performed.

### R5.4 note (2026-11-26) — foreground attach and removal serialized

Short contract note (R5.9 consolidates): `ExploitService` holds `startsLock`
across the whole foreground attach sequence (`startForeground`, the attached
flag, `foregroundRemoved = false` and the monotonic `foregroundEpoch` bump),
and the flush's epoch re-check with its single `stopForeground(REMOVE)` runs
under that same lock. Attach and removal are therefore mutually exclusive and
the epoch bump is atomic with the attach: a stale flush that already decided
cannot remove a notification attached by a newer start. Its immutable per-ID
`stopSelf` snapshot is still retired exactly once and cannot stop a newer
start. Simultaneous flushes serialize through one guard, with no duplicate
removal or stop. Per-start lifetime, bounded eviction retirement,
single-flight admission, owner token, OFF gates, the durable barrier and
operation ownership are unchanged. Host-only gate seams; no device, ADB,
payload or APK work was performed. This is host-only bookkeeping evidence, not
device safety proof; no R2-14 or R2-16 closure is claimed here.

### R6.3 note (2026-11-26) — bounded foreground-flush wait (R2-18 residual)

Short contract note (R6.5 consolidates): a losing foreground flush no longer
waits without bound. A main-thread contender does not wait at all — it queues
one bounded off-thread host re-decision and returns. Other contenders wait only
to a 200 ms monotonic deadline; expiry or interruption is logged honestly and
deferred with **no** `stopForeground`/`stopSelf` completion claim. Re-decisions
are capped at three per lifetime, at most one is queued, the task only re-runs
host bookkeeping, and the owned daemon worker is shut down on destroy.
Interruption is re-asserted, never treated as proof that cleanup completed.
The R5.4 `startsLock` serialization, epoch recheck, per-ID stop-once, 64/64
bounds and eviction retirement are unchanged — including the residual exposure
of the winner's platform call while it holds that lock. Admission gates, owner
token, privileged engine and the OPEN R2-14/R2-16 gates are untouched. Host-only
fixtures; no device/ADB/payload/APK work.

### R6.4 note (2026-11-26) — owned host workers for foreground UI latency (R2-18 residual)

Short contract note (R6.5 consolidates): for a main-thread caller, the foreground
attach (and the rest of that request's owned setup) runs on a small dedicated
owned single daemon-thread **express** worker (`dfroot-express`, bounded 16-slot
queue, AbortPolicy), separate from the general owned single daemon-thread host
worker (`dfroot-host`, bounded 64-slot queue) that carries observer render posts
and the whole winning guarded-flush side-effect phase. Off-main paths remain
inline. The flush guard is won before posting and released only in the posted
task's `finally`, so a main-thread contender does not wait on platform calls.
Host-only evidence, not a device guarantee:

- A main-thread start arriving while a flush holds the guard offloads the
  **entire** start handling (validation, `registerStart`, duplicate gate, setup)
  as one express task and returns `START_NOT_STICKY`, so the caller never waits
  behind the flush for the whole handling. If that express post is REFUSED, the
  caller registers and resolves this start ID's own lifetime (correlated
  `REJECTED`, then exactly one `stopSelf` via the existing bounded deferral /
  post-winner sweep) instead of leaking the platform start ID. That refusal path
  takes only the short `registerStart`/`resolveStart` bookkeeping sections; those
  can still wait behind another contender's guarded platform call, so no "UI
  latency can never depend on foreground/notification timing" claim is made.
  Registering/resolving here is **host-only bookkeeping evidence, not device
  proof**: no refusal path admits work, acquires a lease, starts the engine or
  claims a completed operation.
- A bounded render-post refusal is no longer silent when it carries a TERMINAL
  outcome: only terminal snapshots enter a small bounded (four-slot) replay queue
  that BOTH the bounded deferred re-decision and the post-winner sweep re-apply.
  The refusal itself now also queues the **uncapped single-flight sweep**, so a
  terminal render refused after the three-deferral budget is spent is still
  resolved and retired exactly once **without needing any later flush winner**;
  the sweep never spends the loser budget. Non-terminal refusals carry nothing to
  replay and are dropped with an honest log; the queue is cleared on destroy,
  never unbounded, and all sweep/replay work is a strict no-op after destruction.
  This is host-only bookkeeping evidence, **not a device ANR proof**.
- `shutdown()` in `onDestroy()` gracefully drains already-submitted host and
  express work without interrupting a winner and refuses new work.
- Host-only fixtures pin bounded main-thread returns, one `stopForeground`, one
  `stopSelf` per ID, the unchanged R5.4 epoch/removal serialization, and
  admission staying off both host workers.

Residual, stated honestly: `startsLock` is still held across the single
`stopForeground` call, so attach and removal remain mutually exclusive, and
`onDestroy()` still performs its short bookkeeping on the destroying caller.
Deferral is capped (three re-decisions per lifetime) with a post-winner sweep
backstop, not an unbounded guarantee. Up to 16 express tasks can precede an
attach, and a device FGS-grace deadline remains an OS/hardware gate that host
tests cannot prove. Admission gates, owner token, privileged engine, 64/64
bounds, R2-14/R2-16 OPEN gates and `START_NOT_STICKY` are unchanged. No
device/ADB/payload/APK work; this is host-only evidence, not device safety proof.

### R6.4 second fix loop (2026-11-26) — guard refusal lifetime + bounded render replay

Both reviewers of PR #2 required one more fix. (1) When a main-thread start
arrives while the flush guard is held and its express post is refused, the caller
now registers and resolves a **host-only** lifetime for that start ID (correlated
`REJECTED` plus exactly one `stopSelf` after the flush is released and deferrals /
sweeps drain). No lease, admission, engine call or completion claim is made; the
R6.3 deferral cap and post-winner sweep still retire the ID exactly once. (2) A
single render-snapshot slot became a four-slot bounded queue of TERMINAL-only
snapshots, replayed by both the bounded deferred re-decision and the post-winner
sweep so a render refused after the three-deferral cap cannot strand an accepted
start. Overflow and non-terminal refusals are dropped with honest logs; the queue
is cleared on destroy. R5.4 epoch serialization, R6.3 deadline/deferral/sweep,
all admission gates, 64/64 bounds, owner token and `START_NOT_STICKY` are
unchanged; no new lock is taken while posting to any executor. Three deterministic
fake-host fixtures pin these boundaries. No device/ADB/payload/APK work: this is
host-only evidence, not device safety proof, and no refusal path admits or
completes work.

**Final correctness-review fix (same day):** the prior guarantee still depended
on a later flush winner, because a refused render only called the capped deferral
(which returns immediately once the three-deferral budget is spent) and the
post-winner sweep was scheduled only in a winner's `finally`. Both the refused
TERMINAL render and the refused offloaded-flush post now also queue the
**uncapped, single-flight, destroy-aware sweep** directly at refusal time,
outside `deferredRendersLock`/`flushMonitor`. A queued terminal is therefore
guaranteed a sweep independently of the deferral cap and of any later winner;
the sweep stays host-only, single-claim, and never an admission, engine, retry or
completion-claim action. The four-slot terminal-only cap, overflow/non-terminal
drops and destroy-time no-op remain honest residuals, and R2-14/R2-16 remain
OPEN. Two deterministic fixtures pin the no-winner path and the offloaded-flush
refusal path (no duplicate `stopSelf`/`stopForeground`, no engine/admission/lease
work); this is host-only evidence, **not a device ANR proof**.

### R5.5 note (2026-11-26) — final pre-engine gate ordering

Short contract note (R5.9 consolidates): the final pre-engine gate in
`RunCoordinator.start()` now reads unattended authorization **before** owner
liveness, both inside the same lock that sets `engineStarted`. The authorization
read may block while holding that lock; an `onDestroy()` invalidating the owner
token during that read is therefore still observed and the not-yet-started
request is denied (honest `FOREGROUND_OWNER_GONE_BEFORE_ENGINE` /
`UNATTENDED_AUTH_CHANGED` reason, checked attempt clear, no engine call). The
boundary is honest: an invalidation after the recheck but before `engine.run()`
is intentionally not revoked — started work is never cancelled. Deterministic
latch/fake tests only; no other gate, rc, `/dev`, recovery or hardware claim
changes. No device, ADB, payload or APK work was performed; this is host-only
evidence, not device safety proof, and no R2-14/R2-16 closure is claimed.

### R5.6 note (2026-11-26) — honest final-authorization boundary (R2-16 scoped docs)

Documentation/boundary phase only (no executable or control-flow change).
Removed the overclaim that the final OFF check was atomic with the Settings
control or that no OFF transition could slip between the re-read and engine
start. The final gate is a re-read of unattended authorization under the
coordinator lock immediately before `engineStarted`: OFF/unreadable at that read
blocks a not-yet-started action, while the external `PackageManager`
component-setting write is outside that lock and cannot be made atomic with the
read or `engine.run()`. An OFF written after the re-read is not observed before
the engine starts; work already started is never cancelled. The boot-component
admission recheck is defense-in-depth and READ-ONLY, not an atomic transition;
`SettingsFragment`/`BootClaimCoordinator` state the OFF write's monitor boundary.
Fail-closed on OFF/unreadable, the authoritative `submit()` gate plus pre-engine
recheck, no revocation after engine start, unattended soft-reboot OFF and the
hardware/OS FGS eligibility gate are all unchanged. A deterministic source-text
guard test pins the removed phrases out and the honest text in. No device, ADB,
payload, privileged-container, APK-assembly or native work was performed; no
device safety is implied and R2-16 is scoped, not claimed closed.

### R5.7 note (2026-11-26) — R2-14 residual publication-window boundary (scoped docs + host fixtures)

Documentation and harmless host fixtures only; R2-14 remains OPEN and the
privileged publication mechanism is NOT redesigned. Narrowed the overclaim in
`run_status.h`/`run_status.c` that a concurrent reader never sees actionable
success on ANY detected failure. The provable statement is now explicit: ONCE
rc7 has been published AND OBSERVED it outranks success/conflicts; BEFORE that,
a reader may have consumed a definitive-looking confirmation in the window
between the confirmation `link()` and its temp unlink/directory fsync; a
detected failure produces corrective rc7 only AFTER detection; later correction
cannot revoke an already consumed result. Added a host-only `RUN_STATUS_TEST_HOOKS`
pause immediately after the confirmation `link()`, plus two deterministic
fixtures: `run_status.r2_14_residual_confirm_window_boundary` (pipe-gated reader
records the early definitive observation, then the later rc7 correction) and
`run_status.publication_state_machine_boundary` (pure table over unconfirmed /
confirmed / detected-failure / abnormal-death with a proved / not-proved column;
abnormal-death is observationally identical to confirmed and is marked NOT
proved safe). Production `jni/CMakeLists.txt` does not define
`RUN_STATUS_TEST_HOOKS`, and no production control flow, rc contract, `/dev/df`
or `/dev/dfm6` progress-only SUCCESS semantics, or confirmation protocol
behavior changed. Full host suite: 142 tests, 0 failures, warnings-clean. No
device, ADB, payload, privileged-container, APK-assembly or root work was done;
no device safety is implied and no R2-14 closure is claimed.

## Fourth repair round — R4 consolidated summary (2026-11-26)

Authoritative record of the **fourth repair round** on `fix/app-correctness-safety`
(final local HEAD `74d35f5`). It closes the Re-review 3 findings **R2-12,
R2-13, R2-14 (narrowed), R2-16, R2-17, R2-18, R2-20, R2-21 and R2-22** on top of
the R3 round recorded below. All R4 work was host-verified only; no device,
hardware, ADB, payload, privileged-container, cold-boot, soft-reboot or
APK-assembly validation was performed, and no dependency or bundled binary
changed. The older sections below remain historical; the R4 counts and gate
states here are prior-round baselines, superseded by the R5 consolidated
section above.

| Phase / commit | Finding | What changed (host evidence only) |
|---|---|---|
| R4.1 `6348c71` | R2-13 | Failed-commit uncertainty is process-wide: a static latch keyed by the attempt-preference file makes every `SharedPreferencesOperationAttemptStore` wrapper fail closed even when newly constructed after a poisoned commit. Harmless cross-wrapper tests; 59 focused tests, 0 failures; two reviews APPROVED. |
| R4.2 `b8526d8` | R2-12 | Conflicting terminal markers map to rc7 (never definitive rc1); origin-aware diagnostics never claim native rc=7 for an unknown exit or Java exception; unverified outcomes keep lease/freeze/attempt and never authorize a second operation. 137 host CTest + 114 focused Java tests, 0 failures; two reviews APPROVED. |
| R4.3 `916a18e` | R2-14 (narrowed, not proved safe) | Definitive non-7 results require a matching `dfroot_confirm_` marker (same operation ID and code); an intended marker alone is indeterminate; any detected publication failure publishes rc7 **before** retraction. 140 host CTest, warnings-clean; three reviews APPROVED. The abnormal-death window stays an OPEN hardware/OS gate. |
| R4.4 `7e41d27` | R2-16 | The authoritative unattended gate is inside `submit()` immediately before the durable publish, with a pre-engine recheck; OFF during initialization or a blocked commit yields a proven pre-effect `PRECHECK_FAILED` with no engine call, and OFF after engine start is not revoked. 98 focused tests, 0 failures; two reviews APPROVED. Optional residual: no isolating test for the second recheck window (recorded, not silently accepted). |
| R4.5 `11ae87d` | R2-17 | Click-time request IDs produce correlated `ACCEPTED`/`REJECTED`/`CANCELLED`; cancellation of unclaimed service-local work is published and closed once and never replayed; accepted privileged work stays coordinator-owned. 142 focused tests + 3 deterministic repetitions, 0 failures; two reviews plus one Required fix loop RESOLVED. Optional residual: duplicate benign correlated rejection on unattended-OFF (recorded). |
| R4.6 `15bb5cc` | R2-18 | Main-thread failure/cancellation outcome reports are enqueued on the admission worker instead of taking the coordinator's durable-lock monitor; notification/status reads stay volatile-snapshot-only. Narrow guarantee only — a worker may still wait on storage. 95 focused tests, 0 failures; one Required destruction-guard issue fixed and re-review RESOLVED. |
| R4.7 `a51f74e` | R2-20 | Per-start lifetime resolves each start exactly once; foreground removal cannot suppress `stopSelf` for later-resolved starts. A per-service owner token is invalidated before destruction cancels local work and denied under the admission lock and before engine start; engine-started work is never cancelled. 158 focused tests in the touched classes, 0 failures; two Required interleavings fixed and re-review RESOLVED. |
| R4.8 `186fc8d` | R2-21 | Duplicate starts are rejected before any WakeLock or admission work while a request is `PENDING`/`CLAIMED`, with a correlated `REJECTED`; the single-thread admission worker is shut down in `onDestroy()` after outcome reports are queued and recreated on the next `onCreate()`. 74 focused tests + 3 determinism repetitions, 0 failures; one Required flake fixed and re-review RESOLVED. Optional residuals: idle worker thread between starts and benign correlated duplicates (recorded). |
| R4.9 `74d35f5` | R2-22 | Honest messaging/docs only: `SOFT_REBOOT_PENDING` no longer claims "root active"; `RunCoordinator` Javadoc states that `engineFinished` only proves the Java call ended and that app-data reset is not safe recovery; no functional/control-flow change (message text only), no retry/clear control. Focused `ResultMessagesTest`: 50 tests, 0 failures/errors/skips. |

The result contract above is authoritative: marker conflict → **rc7** (never
rc1); publication acknowledgement requires a matching `dfroot_confirm_`
marker; early unconfirmed markers are indeterminate; rc7 is published before
retraction; the residual abnormal-death window is an **OPEN hardware/OS gate,
NOT proved safe**. The OFF gate is enforced in `submit()` with a pre-engine
recheck and fails closed on OFF/unreadable at each read; it is not revoked after
engine start, and foreground-service eligibility from boot still needs hardware
validation. The corresponding authorization boundary is read-only and honest:
the `PackageManager` OFF write is outside the coordinator monitor and cannot be
made atomic with the gate read or `engine.run()`; an OFF written after the final
re-read is not observed before the engine starts. No device safety is implied.
Unattended soft reboot stays OFF and manual soft reboot remains
experimental/unvalidated on GZIF. Final user
messages never claim verified root or a bare `SUCCESS` (the `/dev/df` and
`/dev/dfm6` strings remain owner-approved progress diagnostics only).

Open gates and residual optional items recorded, not silently accepted:
Direct Boot FGS eligibility; real soft-reboot safety; the final pre-engine
authorization re-read (R2-16) is read-only and not atomic with the PackageManager
OFF write, so an OFF after that re-read is a residual window; `/dev` marker-root trust;
sticky-bit `/data/local/tmp` owner (`shell`); AES key in argv out of scope;
no owner-facing recovery UI (ordinary users stay intentionally locked out and
app-data reset is not safe recovery); `BOOT_COUNT` is diagnostic only; the
R4.4 second-recheck test gap, R4.5 duplicate correlated rejection, and R4.8
idle worker thread and benign duplicates. At historical R4 HEAD `74d35f5` the
four comprehensive reviews were APPROVE (one REQUEST CHANGES for stale docs tags
only, corrected); that outcome is **not** an approval of the current head. A
fresh remote CI green run after push remains the POST-PUSH gate — CI green is
not claimed locally.

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
| R2-14 (R4.3) | `916a18e` | Narrowed in code, not proved safe: a definitive non-7 code requires a matching `dfroot_confirm_` marker; an unconfirmed intended marker yields no decision; any detected publication failure publishes rc7 **before** retraction and the writer exits 7. The early-observation test is fix verification for the previously reproduced unconfirmed-marker case; the broader "ANY detected failure" claim was narrowed by R5.7. The abnormal-death instant (death after the confirmation link but before its fsync, or between the two phases) remains an OPEN hardware/OS gate — no claim of proved safety. |
| R2-16 / R2-17 | `b87318f` | Queued unattended requests re-read the Start-at-Boot component setting at admission and fail closed on OFF/unreadable without touching an admitted operation; every rejected submission publishes a fresh rejection snapshot so the Activity leaves its pending state and shows the real barrier reason. |
| R2-16 (R4.4) | `7e41d27` | Closed the remaining pending-admission window: the authoritative authorization gate sits inside `submit()` immediately before the durable publish (fail closed when unreadable), with a second recheck immediately before engine start. An OFF toggle during a blocked publish, configuration wait or queued worker now yields a proven pre-effect `PRECHECK_FAILED` with a checked barrier clear and no engine call; OFF after engine start is deliberately not revoked. Hardware/OS FGS eligibility remains a separate gate. The final atomicity with the Settings write is still **open** — see the R5.6 honest boundary above. |
| R2-18 | `d32006e` | Checked attempt-store work runs on a bounded single-thread admission executor with claim-once/close-once ownership, and the read-only availability diagnostic runs on a separate worker with sequence/generation guards. No safety check is bypassed: Boot OFF, the durable barrier, single-flight and fail-closed diagnostics stay authoritative. |
| R2-18 (R4.6) | `15bb5cc` | Narrower, honest guarantee: `ExploitService`'s main-thread failure and cancellation paths now **enqueue** the correlated outcome report on the admission worker instead of calling the coordinator method that takes the monitor held across checked storage; notification/status reads remain volatile-snapshot-only. A background worker may still wait on storage under that durable barrier lock. This is **not** a claim that every path is nonblocking. |
| R2-20 (R4.7) | `a51f74e` | Shutdown is per start, not per latest snapshot: a resolved start is stopped exactly once, and the foreground notification is removed only when every owned start is resolved and no owned operation is active, so an older terminal snapshot cannot cancel a newer pending request. A per-instance owner token is invalidated before destruction cancels local work; the coordinator denies a dead owner under the admission lock and again immediately before engine start (proven pre-effect `PRECHECK_FAILED`, barrier cleared, no engine). Once the engine has started, ownership stays with the coordinator and an unverified outcome keeps lease, freeze and barrier. |
| R2-21 (R4.8) | `186fc8d` | `ExploitService` owns at most one `PENDING`/`CLAIMED` request: further starts (including null/foreground-failure starts) get a correlated `REJECTED` **before** any WakeLock or `ADMISSION` work, so bursts cannot accumulate leases or admission work (each duplicate still enqueues one cheap outcome-report task on the single-thread worker). The lazily created single-thread admission worker is shut down gracefully in `onDestroy()` after outcome reports are queued and recreated on the next `onCreate()`; graceful shutdown drains already-queued outcome reports, while an enqueue attempt after shutdown is logged and dropped under the main-thread-only precondition. |
| R2-19 + R2-04 caveat | `1252b3d` | Recovery requires confirmed engine completion (`engineFinished` only after `engine.run()` returns/throws) before an in-process clear; persisted-attempt recovery is a trusted-caller, post-process-death acknowledgement with a checked clear; final-result messages no longer claim a bare `SUCCESS` ("run completed — verify root status yourself" / "already attempted/hooked; root status unverified"). Typed codes, the already-hooked SUCCESS code and no-reentry guard, `/dev/df` and `/dev/dfm6` progress strings, and the no-root-probe policy are unchanged. Narrowed in R4.9: `engineFinished` proves only the Java call ended — see §R4. |

**Final local verification (non-privileged host only, R3 prior-round
baseline — not a fresh R4 full-suite result):** `:app:testDebugUnitTest`
**554 tests / 45 suites, 0 failures / 0 errors / 0 skipped** at that round;
**137 host CTest cases, 0 failures** then (**140** after R4.3 added
confirmation-protocol cases); `:app:lintDebug` **0 errors / 15 warnings**;
workflow policy contract **67/67**; signing contract **40/40**; make contract
**36/36**;
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
  caller clears the barrier. Resetting app data only discards the barrier and
  is NOT evidence that privileged effects stopped — it is not safe recovery.
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
- **Confirmation protocol (R4.3, `916a18e`, committed).** `run_status_write()` publishes
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
threading or CI change was made; this historical phase was committed as `1252b3d`.

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
  *Narrowed in R4.9: `engineFinished` proves only the Java call ended — see §R4.*
- **Persisted-attempt recovery is a post-process-death escape hatch, not an
  owner feature.** `recoverPersistedAttempt(operationId, ownerAcknowledged)`
  requires an affirmative acknowledgement, the exact ID, an unconfirmed record
  loaded at initialization (never one published by this process), and a checked
  clear. It cannot prove that external effects from a dead process stopped.
  There is **NO in-app recovery UI**: an ordinary user with an unconfirmed
  persisted attempt is intentionally locked out — manual and boot admission are
  rejected — until a trusted caller clears the barrier. Resetting app data only
  discards the barrier and is **NOT** evidence that privileged effects stopped;
  it is not safe recovery. A new `BOOT_COUNT` is diagnostic only and is **not**
  proof of safety.
- **Final-result user messages no longer claim bare `SUCCESS`.** Generic success
  now reads `DFRoot: run completed — verify root status yourself`; already-hooked
  reads `DFRoot: already attempted/hooked; root status unverified`. The typed
  result codes/types, the already-hooked SUCCESS code, the no-reentry guard, the
  `/dev/df` and `/dev/dfm6` progress strings, and the absence of a root probe are
  all unchanged. Nothing in the message claims root status was verified.

**Validation (host only, historical prior-round baseline):** `:app:testDebugUnitTest`
**554 tests across 45 suites, 0 failures / 0 errors / 0 skipped**; `:app:lintDebug`
**0 errors / 15 warnings**; host CTest recorded then at **135/135** (superseded
by the consolidated R3 section: **137/137**, and R4.3: **140/140**), `git diff --check` clean. No
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
