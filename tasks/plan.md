<!-- Modified 2026-11-25 (DFRoot GZIF): add R3.1–R3.7 coverage (R2-12/13/14/16/17/18/19 + R2-04 caveat), the PR #1 rebase/CI contract and current release gates. See NOTICE. -->
<!-- Modified 2026-11-26 (DFRoot GZIF, R5 final): record R5.1–R5.8 phases, four APPROVE final reviews, fresh full verification, scoped OPEN gates and residual optional items. See NOTICE. -->
<!-- Modified 2026-11-26 (DFRoot GZIF, R6 final): record four APPROVE final reviews, the Required terminal-render fix loop, full host verification, and residual optional items. See NOTICE. -->
<!-- Modified 2026-11-26 (DFRoot GZIF, R7.4): record R7.1–R7.3 phases and an UNCHECKED Checkpoint R7; add the PENDING Final 7.1–7.6 gate; scope historical APPROVE statements to their own heads. Documentation/coverage consolidation only, no behavior change. See NOTICE. -->
<!-- Modified 2026-11-27 (DFRoot GZIF, R8) — documentation/coverage only, no behavior change. See NOTICE. -->
# Implementation Plan: PR #2 Complete Correctness and Safety Repair

## Overview

Repair PR #2 (`fix/app-correctness-safety`) from head `5893dfa898f361ee0194a620907c89925c0d9b76` against base `ae627f1799a40f195d4d529d9c3368aaaa391032`. The work addresses the persistent GitHub review findings R2-01 through R2-09, the additional high-risk findings identified by four independent review agents, and the missing build/test gates.

The final branch must be buildable, testable without privileged execution, lifecycle-safe across Activity recreation and boot, durable across Java/native configuration boundaries, and honest about its validated environment. No device or privileged payload execution is part of this plan.

## Confirmed Product Decisions

1. **Operation owner:** use a process-scoped `RunCoordinator` and a Direct Boot-aware foreground service (`specialUse`) shared by manual and boot execution.
2. **Activity lifecycle:** Activity destruction detaches observation only. It must never interrupt or cancel an already-started privileged operation.
3. **Success contract:** preserve the owner's existing SUCCESS semantics based on field experience. `/dev/df` and `/dev/dfm6` continue to map to success, but internal result detail distinguishes `ALREADY_HOOKED` from `LATE_LOAD_COMPLETED`. No root probe is added.
4. **Scope:** include inherited high-risk hardening: duplicate boot suppression, full WakeLock coverage, shell-writable staging race, owner-user gate, firmware gate, loader integrity, and reliable native completion/error reporting.
5. **Dependencies:** PR #2 carries the minimal signing fallback and native result/completion changes needed to be independently buildable and internally consistent. It does not import unrelated PR #1/#3/#5 work wholesale.
6. **Soft reboot:** safe-off migration for legacy enabled values; enabling requires durable modal acknowledgement. Unattended boot keeps soft reboot disabled until separate hardware validation, even when manually acknowledged.

## Architecture Decisions

- `RunConfig` is immutable and contains manager package, selected bundled loader, soft-reboot decision, disable-modules decision, and environment/preflight evidence.
- `ConfigurationRepository` is the only Java writer for critical DE preferences. Synchronous `commit()` runs off the main thread; failure marks configuration unverified and blocks execution.
- `RunCoordinator` atomically admits one operation at a time, rejects duplicates rather than queueing them, freezes configuration, owns WakeLock/process supervision, and exposes observable state/log/result.
- `ExploitService` supplies foreground process lifetime for both manual and boot starts. It returns `START_NOT_STICKY` so indeterminate privileged work is never silently replayed after process death.
- Java publishes an atomic, versioned run-config artifact. Native bootstrap reads only that artifact, never mutable SharedPreferences XML.
- Native uses a terminal completion marker written only after bootstrap work finishes. `dfm6` remains a non-terminal late-load progress marker. rc=4 is emitted only after the soft-reboot command returns success; a failed command gets a distinct error code.
- `/data/local/tmp/.ksud-stage` remains the loader ABI-required path, but root temporarily adds the sticky bit to `/data/local/tmp`, creates the file with `O_EXCL|O_NOFOLLOW`, writes/fsyncs it through the descriptor, and restores the directory mode after late-load. Existing or unsafe paths fail closed.
- Boot attempts are deduplicated durably by `Settings.Global.BOOT_COUNT`; failure to read or persist the boot count aborts unattended execution.
- Unsupported users and firmware fail closed before staging or IpSec setup.

## Authoritative Sources

- SharedPreferences commit result: <https://developer.android.com/reference/android/content/SharedPreferences.Editor#commit()>
- Foreground-service boot exemption: <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- Foreground-service types / `specialUse`: <https://developer.android.com/develop/background-work/services/fgs/service-types>
- Direct Boot storage/components: <https://developer.android.com/privacy-and-security/direct-boot>
- Process ownership and timed wait: <https://developer.android.com/reference/java/lang/Process>
- Boot count: <https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT>
- User identity: <https://developer.android.com/reference/android/os/Process#myUserHandle()>
- Build fingerprint semantics: <https://developer.android.com/reference/android/os/Build#FINGERPRINT>

## Findings Coverage Map

| Finding | Planned resolution | Phase |
|---|---|---|
| R2-01 ignored commit failures / main-thread I/O | checked off-thread repository, saving/error state, fail-closed run admission | 2 |
| R2-02 no single-flight / immutable config | coordinator plus atomic native run-config artifact | 3, 4 |
| R2-03 Activity destruction interrupts child | service/coordinator ownership; detach-only Activity; supervised process | 3 |
| R2-04 success semantics | preserve owner-approved SUCCESS contract; internal result detail; terminal marker precedence | 1, 4 |
| R2-05 allowlist only in spinner | canonical preflight at common entry point and native defense in depth | 1, 4 |
| R2-06 boot process lifetime | Direct Boot-aware foreground service; no raw receiver thread | 3, 5 |
| R2-07 rc=4 absent / rc=1 too narrow | integrated native terminal protocol and complete result mapping | 1, 4 |
| R2-08 legacy soft reboot / consent | versioned safe-off migration and modal durable acknowledgement | 2 |
| R2-09 loader precheck / fallback | exact selection-aware asset preflight; no implicit fallback | 1 |
| R2-10 packaged loader pin gate broken / no negative fixture | buildSrc pin tasks + deterministic clean/tampered synthetic fixture in CI | Re-review 1 (`6686973`) |
| R2-11 staged-entry cleanup identity / exec pin | descriptor-pinned fstat/lstat identity, conservative no-unlink of replacements, non-writing pin via `/proc/self/fd` | Re-review 2 (`639a9d8`) |
| R2-14 atomic terminal publication | temp + fsync + `link()` publication; rc=7 indeterminate channel; pre-link failure leaves no final marker, and the brief post-link/pre-rc7 observation window is documented honestly rather than claimed unobservable | R1 (`12a633b`); re-reviewed R3 (`6012c21`) — deterministic post-link concurrent-reader proof |
| R2-12 indeterminate completion keeps ownership (partial R2-03) | bounded non-killing waits, reader rc=7, single-claim recovery API, lease/config freeze retained | R1 (`ce781f8`); re-reviewed R3 (`6ebf9e3`) — unverified-outcome predicate, no flag masking unknown exits |
| R2-13 unconfirmed attempt not durable (partial R2-02) | DE-persisted operation attempt before work; process-restart barrier; boot claims suppressed until recovery | R1 (`7b5397c`); re-reviewed R3 (`2a74dd0`) — corrupt/uncertain records and poisoned stores fail closed |
| R2-15 pre-admission uncertainty + independent boot OFF (partial R2-07/09) | pure reader decision + reserved diagnostic; OFF reachable in every state; claim-time component recheck | R1 (`7c1f39a`, `54cb000`); boot-OFF re-checked at admission in R3 (`b87318f`) |
| R2-16 honest hook result and availability (partial R2-04) | "already attempted/hooked; root status unverified"; read-only availability diagnostic from the authoritative preflight plan | R1 (`a8003c2`); wording refined in R3 (`1252b3d`) |
| R2-17 queued boot request must re-check OFF and show rejections | admission-time component re-read (OFF/unreadable fails closed, admitted work untouched); fresh rejection snapshot so the Activity leaves its pending state | R3.4 (`b87318f`) |
| R2-18 blocking admission/diagnostics off the main thread | bounded single-thread admission executor with claim-once/close-once ownership; separate worker for the read-only availability diagnostic with sequence/generation guards; no safety check bypassed | R3.5 (`d32006e`) |
| R2-19 recovery truth | engine-finished evidence before in-process recovery; post-process-death-only trusted-caller persisted clear; no UI, no retry, no boot-count proof | R3.6 (`1252b3d`) |
| R2-04 caveat (ambiguous final `SUCCESS` wording) | "run completed — verify root status yourself" / "already attempted/hooked; root status unverified"; typed codes/types and no-reentry guard unchanged | R3.6 (`1252b3d`) |
| Post-rebase CI contract (PR #1 merge) | preserve PR #1 release/signing/policy jobs and closed allowlists; restore JUnit/Robolectric test-only deps and hermetic `buildSrc` signing fixture; app-checks, loader-pin fixtures and packaged-pin verification green | R3.7 (`f6faf75`, `770d07c`) |
| Duplicate boot broadcasts | durable BOOT_COUNT claim before service start | 5 |
| WakeLock released before bootstrap completes | terminal completion marker; service owns lock for full session | 3, 4 |
| Shell-writable root staging race | sticky-bit + exclusive nofollow descriptor staging; restore mode | 4 |
| Secondary-user path mismatch | reject non-owner user before side effects | 1 |
| Unsupported firmware | exact documented build/kernel gate before side effects | 1 |
| Loader integrity not enforced | SHA-256 pin verification before staging; CI artifact check | 1, 6 |
| No tests / CI no test or lint | JUnit/Robolectric suite and independent app-checks job | 0, all |
| Signing configuration breaks fresh clones | optional release signing config | 0 |
| Documentation/notices stale | README/CHANGELOG/PORTING and dated GPL notices | 6 |

## Phase Plan

### Phase 0: Build and Test Foundation

**Goal:** make every later phase verifiable on a fresh clone without secrets or privileged artifacts.

Tasks:
- Make release signing optional at Gradle configuration time; incomplete `signing.properties` remains a readable error.
- Add pinned JUnit and Robolectric test dependencies plus Android unit-test resource support.
- Add an independent CI app-checks job running unit tests and lint without depending on LKM builds.
- Add `app/src/main/res/xml` to top-level Makefile APK dependencies.
- Establish test report upload and ensure `NO-SOURCE` cannot count as a green test suite.

Acceptance:
- `./gradlew :app:testDebugUnitTest :app:lintDebug` configures and runs without `signing.properties`.
- At least one real contract test is discovered and executed.
- CI app-checks does not require signing secrets, a device, or the gitignored KO merely to configure/run JVM tests.

### Phase 1: Side-Effect-Free Preflight and Result Contract

**Goal:** reject unsupported/incomplete environments before any staging, IpSec allocation, or child process.

Tasks:
- Introduce canonical manager enum/allowlist and selection-specific loader plan.
- Verify exact loader, `libbootstrap.so`, and `libdfroot.so`; remove implicit Next-to-classic fallback.
- Enforce bundled-loader SHA-256 pins before staging.
- Add owner-user and exact documented firmware/kernel gates.
- Replace fragile integer pass-through with a typed result mapper; native rc=1 becomes broad bootstrap error; unknown native values cannot collide with Java-only codes.
- Preserve owner-approved SUCCESS mapping while carrying internal result detail.

Acceptance:
- Parameterized tests cover all loader presence combinations, missing bootstrap/native binary, unsupported packages, hash mismatch, secondary users, and unsupported firmware.
- Result tests cover every documented code, unknown values, exceptions, and internal success detail.
- No rejected precheck performs staging, opens sockets, or starts a process.

### Phase 2: Durable Configuration and Soft-Reboot Consent

**Goal:** UI, coordinator, and native consume one confirmed configuration generation.

Tasks:
- Add `ConfigurationRepository` with serialized off-main-thread writes and checked `commit()` results.
- Add saving/verified/error state; failed writes reconcile controls and block operation admission.
- Add immutable `RunConfig` and a versioned soft-reboot acknowledgement.
- Migrate legacy `soft_reboot=true` without current acknowledgement to safe OFF.
- Replace Toast consent with a blocking modal; persist acknowledgement and value atomically.
- Keep unattended-boot soft reboot disabled pending hardware validation.

Acceptance:
- Failure-injection tests cover manager set/remove, soft-reboot ON/OFF, disable-modules, exceptions, and reordered writes.
- Tests prove UI never claims durable success after failure and no run is admitted while configuration is unverified.
- Upgrade matrix tests prove legacy enabled + boot enabled fails closed.

### Phase 3: Operation Coordinator, Foreground Service, and Activity Detachment

**Goal:** one authoritative operation survives rotation and owns all dangerous resources.

Tasks:
- Add process-scoped `RunCoordinator` with atomic single-flight admission, immutable snapshot, bounded log, observable state/result, and duplicate rejection.
- Add Direct Boot-aware `ExploitService` using `specialUse`, a notification channel, timed partial WakeLock, and `START_NOT_STICKY`.
- Move manual execution into the service; Activity only requests work and observes state.
- Remove Activity executor/shutdown behavior; `onDestroy()` only unregisters observer.
- Supervise child stdout asynchronously, retain `Process`, use timed wait, and define timeout policy: report indeterminate, do not forcibly kill a started privileged child, retain resources until exit, and never auto-retry.
- Disable manager/settings changes while an operation or configuration write is active.

Acceptance:
- Fake-process tests cover blocked stdout, EOF while alive, interruption, timeout, normal exit, and resource cleanup after actual child exit.
- Coordinator tests prove double-click, rotation, multiple Activity instances, boot/manual overlap, and stale completion cannot start/clear the wrong operation.
- Recreated Activity receives existing state/log and cannot re-enable Run.
- Foreground service starts from both visible Activity and boot exemption paths; no raw long-running receiver thread remains.

### Phase 4: Native Snapshot, Completion Protocol, and Staging Hardening

**Goal:** native consumes the admitted snapshot and reports only after all bootstrap work completes.

Tasks:
- Publish `run_config` atomically from Java; replace bootstrap SharedPreferences XML parsing with strict versioned parsing and manager validation.
- Make `dfm6` non-terminal; add terminal completion/error markers after late-load and soft-reboot work.
- Bound late-load waiting; handle `waitpid` failures; check marker write failures.
- Emit rc=4 only after soft-reboot command success; add a distinct soft-reboot-failed code; keep rc=1 broad.
- Harden `/data/local/tmp/.ksud-stage` with sticky-bit protection, `O_EXCL|O_NOFOLLOW`, descriptor copy, fsync/fchmod, and guaranteed directory-mode restoration.
- Keep Java WakeLock/IpSec resources until the native terminal marker/exit.

Acceptance:
- Host-native parser tests cover missing/truncated/oversized/malformed config and unsupported manager.
- Marker classifier tests cover progress/terminal ordering, conflicting markers, write failures, timeout, soft-reboot success/failure/skip, and unknown codes.
- Staging tests with harmless temporary files cover symlink, FIFO, pre-created destination, and replacement attempts.
- No success/pending result is emitted before terminal completion.

### Phase 5: Boot Integration and Durable Deduplication

**Goal:** boot uses a supported lifetime and attempts at most once per boot.

Tasks:
- Reduce `BootReceiver` to validation plus durable BOOT_COUNT claim and service start.
- Abort boot if BOOT_COUNT is unavailable, unchanged for this boot, or cannot be persisted.
- Coalesce `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`; never queue a second exploit after a first failure.
- Route boot through the same coordinator/service and snapshot as manual execution.
- Ensure process death does not silently replay indeterminate work; retry requires a new boot or explicit manual action.
- Keep boot soft reboot disabled and document the limitation.

Acceptance:
- Tests cover both broadcast orders, duplicates, process restart, unavailable boot count, failed persistence, boot/manual overlap, and next-boot behavior.
- Receiver returns promptly and owns no thread/process/WakeLock.
- Exactly one operation can be admitted per boot and per process.

### Phase 6: Documentation, Notices, and Integration Cleanup

**Goal:** user-facing contract and source notices match the final implementation.

Tasks:
- Update README manager list, bundled-loader model, SUCCESS contract, firmware/user restrictions, foreground-service boot behavior, and soft-reboot limitations.
- Update CHANGELOG and PORTING-GZIF with the new architecture and validation status.
- Add dated modification notices to every GPL-covered modified file.
- Verify loader pins and final APK contents; ensure no generated/secrets files are tracked.
- Run the complete repository verification suite and record commands/results.

Acceptance:
- Documentation contains no manager-agnostic or unsupported-manager claim.
- All modified covered files have current dated notices.
- Full tests, lint, debug assembly, diff checks, and secret scans pass.
- No hardware-validation claim is made.

## Per-Phase Execution Contract

For every phase:

1. Assign one implementation subagent with only that phase's scope.
2. Run focused and full non-privileged verification.
3. Assign at least two independent review subagents (correctness/architecture and security/test quality).
4. If any Required/Critical finding remains, assign a fix subagent and repeat review.
5. Commit only after reviewers approve and verification is green.
6. Update `tasks/todo.md` before moving to the next phase.

## Re-review 1 repair round — release gates (2026-11-25)

Second repair round on `fix/app-correctness-safety`, commits `6686973`..`a8003c2`
(HEAD `a8003c2`), closing R2-10..R2-16 and the remaining parts of
R2-02/03/04/07/09. Documentation/notices/hygiene only for the final commit set;
no runtime behavior change is introduced by the documentation work itself.

Completed local gates for this round:

- [x] R2-10 Gradle pin gate: `buildSrc` verifier tasks resolve `:app:tasks --all`;
  clean and tampered synthetic fixtures behave correctly; CI fixture job added.
- [x] R2-11 staging cleanup: descriptor identity pin, conservative replacement
  handling, non-writing exec pin; 117/117 local and 115/115 CI-like CTest cases.
- [x] R2-14 atomic publication: 123/123 CTest, 26 mapper tests; rc=7 only maps
  to `RESULT_INDETERMINATE`.
- [x] R2-12 indeterminate ownership: 131/131 CTest, 428 JVM tests, lint 0 errors.
- [x] R2-13 durable attempt barrier: 459 JVM tests, 131 CTest, lint 0 errors.
- [x] R2-15 pre-admission uncertainty (135/135 CTest) and independent boot OFF
  (471 JVM tests, 135 CTest, lint 0 errors).
- [x] R2-16 honest hook result and read-only availability diagnostic:
  484 JVM tests across 44 suites, 135 host CTest cases, lint 0 errors / 21 warnings.
- [x] Hygiene: loader pins match `app/build.gradle.kts` and `jniLibs`;
  `.gitignore` covers generated/KO/secrets; no generated/secret/build files
  tracked; secret scan has only benign `printf '%s'` placeholder hits;
  `git diff --check` clean; per-file dated notices added only where missing.

Remaining release gates (not satisfied locally, not claimed as done):

- [ ] **POST-PUSH CI gate:** a fresh full GitHub Actions run must be green,
  including `loader-pin-fixtures` (negative fixture must be rejected),
  `:app:tasks --all`, unit tests, lint, and the packaged-loader pin checks.
  This cannot be executed locally and is not claimed.
- [ ] **Hardware gate — Direct Boot FGS eligibility:** boot-start foreground
  service/notification behavior on a real locked device.
- [ ] **Hardware gate — real soft-reboot safety:** the historical full hard
  reboot on this model is still unresolved; rc=4/6/7 are code-level only.
- [ ] **Security gate — sticky-bit owner limitation:** `/data/local/tmp` owner
  (`shell`) can still rename/delete entries; descriptor identity checks mitigate
  but are not a security boundary.
- [ ] **Security gate — marker-root trust:** `/dev` marker root and admitted-ID
  path trust need on-device/SELinux validation; host tests inject harmless roots.
- [ ] **Residual risk — AES key in argv:** parsing hardened, key presence in argv
  not redesigned; owner acknowledgement required.
- [ ] **Out of scope — recovery UI delivery:** the recovery API and durable
  barrier exist, but an owner-facing recovery screen is not part of this round.
- [ ] **Owner acknowledgement — `BOOT_COUNT` as new-boot boundary:** treating a
  strictly greater boot count as a safe retry point needs explicit owner
  acknowledgement/validation.
- [ ] APK assembly remains blocked by the gitignored, absent `ko/*.ko` and would
  require a privileged DDK container; no device/ADB/payload validation in this round.

## Re-review 3.6 repair round — release gates (2026-11-25)

Third repair round on `fix/app-correctness-safety` (HEAD `d32006e`), fixing
**R2-19** (recovery truth) and the remaining **R2-04** caveat. Java-only, later
committed as `1252b3d`: no native code, boot gate, attempt-store format,
dependency, UI threading, CI, device/ADB, payload, privileged container, or APK
assembly.

- [x] R2-19 in-process recovery requires evidence: `engineStarted` before
  `engine.run()` and `engineFinished` only after that call returns/throws, in
  addition to exact ID, single claim and checked compare-and-clear. A supervisor
  timeout while the engine is blocked is refused; exactly-once cleanup and the
  checked clear are preserved. Deterministic tests: blocked engine refused,
  post-return success, failed persistence refused, concurrent correct-ID callers
  (one wins).
- [x] R2-19 persisted recovery is post-process-death only: acknowledgement +
  exact ID + checked clear + a record loaded at initialization (never one
  published by this process). No in-app recovery UI: an ordinary user is
  intentionally locked out until a trusted caller clears the barrier. App-data
  reset only discards the barrier and is NOT evidence that privileged effects
  stopped — not safe recovery; a new boot count is not proof of safety. Mock
  tests: with/without acknowledgement, failed persistence, same-process refusal.
- [x] R2-04 caveat: bare `SUCCESS` removed from final user messages
  (`DFRoot: run completed — verify root status yourself` /
  `DFRoot: already attempted/hooked; root status unverified`). Typed codes/types,
  already-hooked SUCCESS code/no-reentry guard, `/dev/df` and `/dev/dfm6`
  progress strings, and no root probe strictly unchanged.
- [x] Host verification at that phase: `:app:testDebugUnitTest` 554 tests / 45
  suites, 0 failures/errors/skips; `:app:lintDebug` 0 errors / 15 warnings;
  host CTest 135/135 as recorded then. **Superseded by the R3 consolidated
  section below: final host CTest is 137/137** (post-link fixture added in
  `6012c21`); `git diff --check` clean. No hardware/device/ADB/payload/
  privileged-container/APK-assembly validation is claimed; CI is POST-PUSH.

## Fourth repair round (R4.1–R4.9) — phases, gates and residuals (2026-11-26)

Committed local HEAD `74d35f5` (R4.1–R4.9). R4 closes Re-review 3 findings
**R2-13, R2-12, R2-14 (narrowed), R2-16, R2-17, R2-18, R2-20, R2-21 and R2-22**
on top of the R3 round recorded below. All phase evidence below is host-only;
no device/ADB/payload/privileged-container/cold-boot/soft-reboot/APK-assembly
validation, no full-suite/CI green claim, and no dependency or binary change.

- [x] R4.1 (`6348c71`, R2-13): process-wide failed-commit uncertainty latch;
  59 focused tests, 0 failures; two reviews APPROVED.
- [x] R4.2 (`b8526d8`, R2-12): conflicting terminal markers → rc7, origin-aware
  diagnostics, unverified outcomes keep ownership; 137 host CTest + 114 focused
  Java tests, 0 failures; two reviews APPROVED.
- [x] R4.3 (`916a18e`, R2-14 narrowed, not proved safe): matching
  `dfroot_confirm_` required for definitive non-7; rc7 published before
  retraction; 140 host CTest, warnings-clean; three reviews APPROVED. Abnormal
  death window is an OPEN hardware/OS gate.
- [x] R4.4 (`7e41d27`, R2-16): authoritative unattended gate in `submit()` plus
  pre-engine recheck, no revocation after engine start; 98 focused tests;
  two reviews APPROVED. Optional residual: no isolating second-recheck test.
- [x] R4.5 (`11ae87d`, R2-17): request-correlated ACCEPTED/REJECTED/CANCELLED,
  no replay of cancelled work; 142 focused tests + 3 repetitions; one Required
  fix loop RESOLVED. Optional residual: benign duplicate correlated rejection.
- [x] R4.6 (`15bb5cc`, R2-18): main-thread outcome reports enqueued off the
  durable lock; 95 focused tests; one Required destruction-guard issue fixed
  and re-review RESOLVED. Narrow guarantee: a worker may still block on storage.
- [x] R4.7 (`a51f74e`, R2-20): per-start lifetime + owner token, dead owner
  denied pre-engine, engine-started work never cancelled; 158 focused tests;
  two Required interleavings fixed and re-review RESOLVED.
- [x] R4.8 (`186fc8d`, R2-21): duplicate starts rejected before WakeLock/work;
  admission worker gets a defined lifetime; 74 focused tests + 3 repetitions;
  one Required flake fixed and re-review RESOLVED. Optional residuals: idle
  worker thread and benign correlated duplicates.
- [x] R4.9 (`74d35f5`, docs phase; R2-22):
  honest soft-reboot wording (`SOFT_REBOOT_PENDING` no longer claims "root
  active"), `RunCoordinator` Javadoc limits for `engineFinished` and app-data
  reset, pinned message tests (50 focused tests, 0 failures/errors/skips), and
  consolidated README/CHANGELOG/PORTING/plan/todo. No behavior change, no
  retry/clear control, no root probe, no kill/signal.
- [x] Checkpoint R4 (historical): completed at `74d35f5`; four final reviews then
  ran and their Required item was resolved. Open
  gates: Direct Boot FGS eligibility; real soft-reboot safety; `/dev`
  marker-root trust; sticky-bit `/data/local/tmp` owner (`shell`); AES key in
  argv out of scope; no recovery UI (users intentionally locked out — app-data
  reset only discards the barrier, NOT evidence effects stopped); `BOOT_COUNT`
  diagnostic only; R4.4/R4.5/R4.8 optional residuals recorded, not accepted.

## Third repair round (R3) — coverage and release gates (2026-11-25)

Authoritative current gate record for `fix/app-correctness-safety` (final local
HEAD `1252b3d`). Earlier round sections above remain historical. The branch was
rebased onto merged PR #1 (`origin/master` `9f60e16`); the PR #1 release/signing/
policy CI contract is preserved and extended by `f6faf75` (closed allowlists for
app-checks, loader-pin fixtures and packaged-pin verification; all actions
pinned; report check moved out of the forbidden heredoc; fail-closed uploads)
and `770d07c` (JUnit/Robolectric test-only deps restored; `buildSrc` included in
the hermetic signing fixture).

Completed local gates (host only):

- [x] R2-12 (`6ebf9e3`): classifier-failure rc=7, one unverified-outcome
  predicate, ownership retained on every unverified result/exception.
- [x] R2-13 (`2a74dd0`): uncertain/corrupt attempt records fail closed; poisoned
  store cache cannot assert durable absence before restart.
- [x] R2-14 (`6012c21`): post-link observation window proven and documented;
  pre-link leaves no marker, rc=7 wins after publication, only post-publication
  results are indeterminate; host CTest now 137.
- [x] R2-16 / R2-17 (`b87318f`): Boot OFF re-checked at admission for queued
  requests with a fresh rejection snapshot; admitted operations untouched.
- [x] R2-18 (`d32006e`): bounded single-thread admission executor and separate
  diagnostic worker; Boot OFF, durable barrier, single-flight and fail-closed
  diagnostics still authoritative.
- [x] R2-19 / R2-04 caveat (`1252b3d`): recovery requires confirmed engine
  completion; persisted recovery is trusted post-process-death only; final
  messages drop bare `SUCCESS`; no UI/retry/boot-count proof.
- [x] Post-rebase CI contract (`f6faf75`, `770d07c`): workflow policy 67/67,
  signing 40/40, make 36/36, `:app:tasks --all` resolves and the negative
  loader-pin fixture is green.
- [x] R3 historical final verification at HEAD `1252b3d`: **554 JVM tests / 45
  suites, 0 failures / 0 errors / 0 skipped**; **137 host CTest cases**; lint
  **0 errors / 15 warnings**;
  loader pins match `app/build.gradle.kts` and `app/src/main/jniLibs`; no
  generated/secret files tracked; secret scan benign-only; `git diff --check`
  clean. No dependency or binary change; documentation edits add no runtime
  behavior.

Remaining release gates (not satisfied locally, not claimed as done):

- [ ] **POST-PUSH CI gate:** a fresh remote GitHub Actions run after push with
  all PR #1 policy jobs plus app-checks, loader-pin fixtures and packaged-pin
  verification green. Local contract suites are not a CI run.
- [ ] **Hardware gate — Direct Boot FGS eligibility:** boot-start foreground
  service/notification behavior on a real locked device.
- [ ] **Hardware gate — real soft-reboot safety:** historical full hard reboot
  on this model is unresolved; rc=4/6/7 are code-level only, unattended soft
  reboot stays disabled.
- [ ] **Security gate — `/dev` marker-root trust:** admitted-ID marker root and
  SELinux trust need on-device validation; host tests inject harmless roots.
- [ ] **Security gate — sticky-bit owner limitation:** `/data/local/tmp` owner
  (`shell`) can rename/delete entries; descriptor identity checks mitigate,
  not remove, this assumption.
- [ ] **Residual risk — AES key in argv:** out of scope for redesign; owner
  acknowledgement required.
- [ ] **Out of scope — recovery UI delivery:** no in-app recovery control; an
  ordinary user with an unconfirmed persisted attempt stays locked out until a
  trusted caller clears the barrier. App-data reset only discards the barrier
  and is NOT evidence that privileged effects stopped — not safe recovery.
- [ ] **Owner acknowledgement — `BOOT_COUNT`:** diagnostic only, not proof that
  prior privileged effects stopped or that a retry is safe.
- [ ] APK assembly remains blocked by the gitignored, absent `ko/*.ko` and would
  require a privileged DDK container; no device/ADB/payload validation in R3.

## Fifth repair round (R5.1–R5.8) — phases, gates and residuals (2026-11-26)

Committed local HEAD `33980e2` (R5.1–R5.8), addressing Re-review 4. **R2-17,
R2-18, R2-20, R2-21 and R2-23 are code-fixed in reviewed scope. R2-14 and R2-16
are SCOPED / OPEN gates — documented honestly, NOT closed and NOT proved safe.**
All evidence is focused host-only verification plus independent static reviews;
no device, hardware, ADB, payload, privileged container, crash/power-loss or APK
assembly was performed. Prior-round baselines: 623 JVM tests / 45 suites, 140
host CTest (R4 final gate) and 138/138 + 623/45 in Re-review 4 — none is a fresh
R5 full-suite result.

- [x] R5.1 (`f9e55a9`, R2-17 stopped-Activity half): bounded 32-entry outcome
  ledger and onStart reconciliation; 90 focused tests, 0 failures; two reviews APPROVE.
- [x] R5.2 (`3a221ab`, R2-18): no production change; blocked-store/throwing-starter
  pins off-lock synchronous failure reporting; 93 focused tests, 0 failures; two reviews APPROVE.
- [x] R5.3 (`05ff93a`, R2-21 + R2-17 dispatch half): ledger-before-enqueue, 64-slot
  reporter, 64+64 bounded start records, eviction-time `stopSelf`; 172 then 112
  focused tests; Required eviction/bound issues fixed and re-reviewed RESOLVED.
- [x] R5.4 (`48bf31b`, R2-20 foreground half): attach under `startsLock`, epoch
  recheck + single `stopForeground`; Required attach-side gap fixed with in-flight
  latch test and mutation check, re-reviewed RESOLVED; 62 focused tests.
- [x] R5.5 (`513af88`, R2-20 liveness half): blocking authorization read before
  owner-token sample under the `engineStarted` lock; 74 focused tests, 0 failures;
  a racy assertion was latch-gated and re-verified.
- [x] R5.6 (`3ef725c`, R2-16 SCOPED): docs/comments only, zero executable
  production change; honest OFF boundary; 75 then 59 focused tests; vacuous
  guard assertion fixed and re-reviewed RESOLVED.
- [x] R5.7 (`e9b12c6`, R2-14 SCOPED, gate OPEN): narrowed publication overclaims,
  host-only pause seam, two boundary fixtures and proved/not-proved table;
  142 host CTest, 0 failures; hooks absent from production.
- [x] R5.8 (`33980e2`, R2-23): both APK variants pin-gated before upload on every
  trigger; policy 73/73, make 36/36; two reviews APPROVE.
- [x] Checkpoint R5 (historical, code head `33980e2` / docs head `689f378`):
  phases complete, four final reviews APPROVED at `689f378`, fresh full host
  verification green. Previously pending items then: fresh full Java + native
  suite, full verification and four final comprehensive reviews. That APPROVE
  is historical and is **not** an approval of the current R6 head. Open gates:
  R2-14 publication observation window, R2-16 OFF atomicity, Direct Boot FGS
  eligibility, real soft-reboot safety, `/dev` marker-root trust, sticky-bit
  owner, AES key in argv, no recovery UI, `BOOT_COUNT` diagnostic only. R5
  optional residuals recorded, not silently accepted: reporter-overflow UX until
  `onStart()` (R5.3), `stopForeground`-under-`startsLock` ANR risk and interrupted
  waiting flush (R5.4), and a future `if: always()` upload bypass guard (R5.8,
  currently absent).

## Historical final gate (R5, HEAD `689f378` — not an approval of the current head)

Four independent comprehensive reviews ran at historical R5 docs HEAD
`689f378`: correctness/lifecycle APPROVE, security/honest-claims APPROVE,
architecture/native APPROVE, test/build/CI APPROVE — no Required/Critical
findings. Fresh non-privileged host verification then: **653 JVM tests / 45
suites, 142 host CTest, lint 0 errors / 15 warnings, policy 73/73, signing
40/40, make 36/36, pins matched, hygiene clean**. The approved commit series was
pushed; fresh remote CI green remained a POST-PUSH gate, not device validation.
Similarly, the R3/R4 APPROVE statements in the sections above are scoped to
their own heads (`1252b3d` / `74d35f5`). None of these historical outcomes is an
approval of the current R6 head `9e1aff7`.

## Sixth repair round (R6.1–R6.4) — phases, gates and residuals (2026-11-26)

Final local HEAD `9e1aff7` (R6.1 `13ee65f`, R6.2 `731e88c`, R6.3 `cdf76e7`,
R6.4 `9e1aff7`) on top of the last pre-R6 commit `fd1ccd8` (R5 code `33980e2` / docs `689f378`),
addressing Re-review 5. It closes the remaining **R2-17** residuals and reduces
**R2-18** to bounded host-only responsiveness evidence; **R2-14 and R2-16 remain
SCOPED / OPEN gates — not closed and not proved safe.** Host-only focused
verification and independent static reviews only; no fresh full-suite/CI-green,
device, ADB, payload, privileged-container or APK work, and no device-ANR proof.

- [x] R6.1 (`13ee65f`, R2-17 visible Activity): per-Activity read-only outcome
  watch, 25 ticks 200 ms apart, explicit once-only unconfirmed status, no
  replay/retry/cancel/authorization; 46 focused tests, 0 failures; two reviews
  APPROVE. Residuals: per-session budget, `onPause` does not explicitly cancel.
- [x] R6.2 (`731e88c`, R2-17 eviction): null/evicted ledger record is explicitly
  UNCONFIRMED, no executable production change (32/64 bounds and lock order
  unchanged); 108 focused tests, 0 failures; two reviews APPROVE, one optional
  vacuous replay assertion strengthened and re-reviewed RESOLVED.
- [x] R6.3 (`cdf76e7`, R2-18): 200 ms monotonic flush deadline, main-thread
  no-wait re-decision, three-deferral cap and post-winner sweep; 115 focused
  tests, 0 failures (68 after the fix); one Required stranding gap fixed with
  the sweep and re-reviewed RESOLVED; test-quality APPROVE with source guards.
- [x] R6.4 (`9e1aff7`, R2-18(b)): owned express 16-slot / general host 64-slot
  bounded executors, offloaded attach/render/winning flush, fail-closed refusals
  and a four-slot terminal-only render replay queue; 124 then 127 focused tests,
  0 failures; three Required architecture items fixed, then a guard-refusal
  start-ID leak and render-slot residual fixed again, re-reviewed RESOLVED.
- [x] Checkpoint R6: phases complete, four final reviews APPROVED, full host
  verification green. Previously pending items: fresh full Java + native suite
  and CI green after push, then the four final comprehensive reviews below. Open gates: R2-14 publication window, R2-16 OFF atomicity, Direct
  Boot FGS eligibility, real soft-reboot safety, `/dev` marker-root trust,
  sticky-bit owner, AES key in argv, no recovery UI, `BOOT_COUNT` diagnostic.
  R5.3 reporter-overflow UX and R5.4 interrupted-wait/ANR residuals are
  superseded by R6 work; NEW R6 residuals recorded, not silently accepted:
  per-session 25-tick budget, `onPause()` does not explicitly cancel the watch
  (ticks are no-ops while paused), the post-winner sweep is a bounded one-shot
  backstop that does not re-queue after a no-op sweep, the four-slot render
  replay drops overflow/non-terminal refusals with honest logs, rare refusal
  paths still take short caller-side `startsLock` sections, and the R6.4-added
  timing-sensitive `hostExecutorIsOwnedBoundedAndShutdownGracefully` test remains
  green in full runs. No device ANR proof and no CI-green claim.

## Historical final comprehensive review — R6 (completed at `92572ea`)

Four independent reviews ran at the historical R6 final head (code `9e1aff7`,
docs and final fix loop `92572ea`); all APPROVE. The APPROVE outcomes above
belong to earlier heads only. This historical outcome is scoped to `92572ea`
and is **not** an approval of the current R7 HEAD `020175b` (R7 code `57df54c`); the final
reviews for that head are in the R7 section below. Keep the PR draft/open
unless the user explicitly asks to mark it ready; pushing or green host CI
never implies hardware validation or merge approval.

Final gate completed at HEAD `92572ea` (R6 code `9e1aff7` + final fix loop):
four fresh independent comprehensive reviews all APPROVE (correctness/lifecycle,
security/honest-claims, architecture/native, test/build/CI). The final correctness
review found one Required terminal-render liveness gap (refused TERMINAL after the
deferral cap with no later winner); fixed with an uncapped single-flight sweep at
refusal and re-reviewed RESOLVED by the same reviewer. Final host verification by
the main agent: **679 JVM tests / 45 suites, 0 failures/errors/skips**, **142 host
CTest**, lint 0 errors / 15 warnings, workflow policy 73/73, signing 40/40,
make 36/36, pins matched, `git diff --check` clean. The approved commit series is
pushed; a fresh remote CI green run after push is the POST-PUSH gate, not device
validation.

- [x] Final 6.1: correctness/lifecycle independent review — APPROVE.
- [x] Final 6.2: security/staging/environment independent review — APPROVE.
- [x] Final 6.3: test/build/CI independent review — APPROVE.
- [x] Final 6.4: architecture/native integration independent review — APPROVE.
- [x] Final 6.5: No Required/Critical findings; final REQUEST CHANGES resolved
  through fix → re-review; residuals and OPEN gates recorded.
- [x] Final 6.6: Fresh full verification complete; approved commit series pushed.

## Seventh repair round (R7.1–R7.3) — phases, gates and residuals (2026-11-26)

Final local HEAD `57df54c` (R7.1 `985fa71`, R7.2 `36076de`, R7.3 `57df54c`)
on top of the R6 close sequence (`92572ea` → `528568d` → `f954cbb`; R6 code head `9e1aff7`). Addresses Re-review
6. **R2-24 and R2-17 fixed in the reviewed host scope; R2-18 SCOPED /
DOCUMENTED (not fixed/closed); R2-14 and R2-16 remain SCOPED / OPEN gates;
R2-02 and R2-07 linked only.** Focused host-only verification and independent
static reviews only: no fresh full-suite/CI-green, device, ADB, payload,
privileged-container, crash/power-loss or APK work; no device-ANR proof and no
FGS-grace proof.

- [x] R7.1 (`985fa71`, R2-24): notification construction moved inside its own
  `RuntimeException` boundary, outside `startsLock`; correlated honest
  `REJECTED` + one `stopSelf`, no admission/barrier/owner/foreground claim;
  host-only throwing seam null in production. **83 focused tests, 0 failures**;
  two reviews APPROVED, no Required/Critical.
- [x] R7.2 (`36076de`, R2-17): exactly one read-only ledger observation per
  watch tick drives both reconciliation and the final-tick unconfirmed decision;
  host-only lookup seam inert in production. **52 focused `MainActivityTest`
  cases, 0 failures.** Required fix loop: test quality first REQUEST CHANGES —
  initial tests non-discriminating → replaced with a red-green discriminator
  (red under the old two-read algorithm, green after the fix) and one-lookup
  assertions, re-reviewed RESOLVED. Correctness APPROVE; both reviews APPROVE
  otherwise.
- [x] R7.3 (`57df54c`, R2-18): **SCOPED / DOCUMENTED, not fixed or closed**;
  zero production behavior change (docs + harmless host fixtures). Residual
  recorded explicitly: a single `flushInProgress` check lets a duplicate start
  briefly contend under `startsLock` held by express foreground attach with no
  active flush; refusal/`onDestroy()` take short caller-side sections. **85
  focused tests, 0 failures**; two reviews APPROVED (scope honesty, test
  quality). Not a device ANR proof, not an FGS-grace proof.
- [x] Checkpoint R7: phases complete, four final reviews APPROVED, fresh full
  host verification green. Previously pending items: fresh full verification
  and the four final comprehensive reviews. Open gates: R2-14 publication/abnormal-death window, R2-16 OFF
  atomicity, R2-18 documented `startsLock` contention, Direct Boot FGS
  eligibility, real soft-reboot safety, `/dev` marker-root trust, sticky-bit
  owner, AES key in argv, no recovery UI, `BOOT_COUNT` diagnostic. Earlier R5/R6
  residuals remain (per-session watch budget, no explicit `onPause()` cancel,
  one-shot sweep, four-slot terminal replay drops, rare caller-side `startsLock`
  sections); signed-release packaged-pin gate inherited from PR #1 policy;
  abnormal-death publication window OPEN. No device ANR proof and no CI-green
  claim.

Historical APPROVE outcomes (R3/R4/R5/R6, including Final 6.1–6.6 at `92572ea`)
are scoped to their own heads and are **not** an approval of the current R7 HEAD
`57df54c`. R2-02 and R2-07 remain linked to R2-16/R2-14 only.

## Final Comprehensive Review — R7 final

Final gate completed at HEAD `020175b` (R7 code head `57df54c`): four fresh
independent comprehensive reviews all APPROVE (correctness/lifecycle,
security/honest-claims, architecture/native, test/build/CI), with no
Required/Critical findings. The R7.2 test-quality REQUEST CHANGES (initial tests
non-discriminating) was fixed with a red-green disagreeing-observation fixture
and re-reviewed RESOLVED. Final host verification by the main agent: **687 JVM
tests / 45 suites, 0 failures/errors/skips**, **142 host CTest**, lint 0 errors /
15 warnings, workflow policy 73/73, signing 40/40, make 36/36, pins matched,
`git diff --check` clean. The approved commit series is pushed; a fresh remote CI
green run after push is the POST-PUSH gate, not device or release approval.

Keep PR #2 draft/open unless the user explicitly asks to mark it ready; pushing
or green host CI never implies hardware validation, device safety or
merge/release approval.

- [x] Final 7.1: correctness/lifecycle independent review — APPROVE.
- [x] Final 7.2: security/staging/environment independent review — APPROVE.
- [x] Final 7.3: test/build/CI independent review — APPROVE.
- [x] Final 7.4: architecture/native integration independent review — APPROVE.
- [x] Final 7.5: No Required/Critical findings; R7.2 REQUEST CHANGES fixed and
  re-reviewed RESOLVED; residuals and OPEN gates recorded.
- [x] Final 7.6: Fresh full verification complete; approved commit series pushed.

## Eighth repair round (R8.1–R8.4) — documentation/coverage only (2026-11-27)

Current local HEAD `55f2c62` (R8.2 `fde6b73`, R8.3 `4002b39`, R8.4 `55f2c62`) on top of the
unchanged PR head `be9201c`. Addresses Re-review 8 (a status recheck of the
identical head `be9201c`, **not** a fresh verification) and the supporting-only
2026-11-27 device evidence. **No production code change and no gate closure:**
R2-14 / R2-16 / R2-18 remain SCOPED / OPEN (not closed, not proved safe);
R2-02 and R2-07 remain linked only; R2-17 and R2-24 remain closed only in the
previously reviewed scope; no new finding IDs. Verdict REQUEST CHANGES / keep
draft; PR #2 stays draft/open.

- [x] R8.1 (PR description correction — GitHub-side only): replace the stale
  claim that base CI fails without `signing.properties` with the actual
  outstanding limitations. GitHub-side operation, no git commit and no code
  change.
- [x] R8.2 (`fde6b73`, README): record R8 supporting device evidence and the
  still-open gates; documentation only.
- [x] R8.3 (`4002b39`, CHANGELOG): record the 2026-11-27 R8 documentation
  round; documentation only.
- [x] R8.4 (`55f2c62`, plan/todo — this phase): mirror the R7 structure for R8.1–R8.4 and
  leave Checkpoint R8 UNCHECKED; the Final 8.1–8.4 documentation-accuracy reviews were completed below; no behavior change.

All four documentation implementation phases involve no builds, no test-suite
runs and no device runs. The 2026-11-27 device observations were collected
separately under the owner's explicit R8 authorization (including soft-reboot /
crash): no payload rebuild, no APK assembly, no privileged container or guard
change, and the installed APK was unchanged. Those observations are SUPPORTING
ONLY — not gate closure, not device-safety, merge or release approval. CI
`45787525701` is host-only evidence and is not device evidence.

- [ ] Checkpoint R8: **documentation-accuracy reviews only; does NOT change any
  gate disposition.** To be checked by the main agent after the final
  comprehensive reviews pass. Open gates (identical to R7): R2-14
  publication/abnormal-death window, R2-16 OFF atomicity, R2-18 documented
  `startsLock` contention, Direct Boot FGS eligibility, real soft-reboot safety,
  `/dev` marker-root trust, sticky-bit owner, AES key in argv, no recovery UI,
  `BOOT_COUNT` diagnostic. Earlier R5/R6 residuals remain (per-session watch
  budget, no explicit `onPause()` cancel, one-shot sweep, four-slot terminal
  replay drops, rare caller-side `startsLock` sections); signed-release
  packaged-pin gate inherited from PR #1 policy; abnormal-death publication
  window OPEN. No device ANR proof and no CI-green claim. Supporting device
  evidence (2026-11-27) is fail-closed/observational only and closes none of
  R2-14 / R2-16 / R2-18; no new finding IDs; verdict REQUEST CHANGES / keep
  draft and PR #2 draft/open.

Historical APPROVE outcomes (R3/R4/R5/R6/R7, including Final 7.1–7.6 at
`020175b`) are scoped to their own heads and are **not** an approval of the
current HEAD `55f2c62`. R2-02 and R2-07 remain linked to R2-16/R2-14 only.

## Final Comprehensive Review — R8 final

Final gate pending at current HEAD `55f2c62` (PR head `be9201c`). These are
**documentation-accuracy reviews only** and do NOT change any gate disposition:
Re-review 8 is a status recheck of identical head `be9201c`, not fresh
verification, and the 2026-11-27 device observations are supporting evidence
only (not device-safety, merge or release approval). Keep PR #2 draft/open. To
be checked by the main agent after the final comprehensive reviews pass.

- [x] Final 8.1: correctness/lifecycle documentation-accuracy review — APPROVE (documentation accuracy only; no gate disposition change).
- [x] Final 8.2: security/staging/environment documentation-accuracy review — APPROVE (documentation accuracy only; no gate disposition change).
- [x] Final 8.3: test/build/CI documentation-accuracy review — APPROVE (documentation accuracy only; no gate disposition change).
- [x] Final 8.4: architecture/native integration documentation-accuracy review — APPROVE (documentation accuracy only; no gate disposition change).

## Ninth repair round (R9.1–R9.6, 2026-11-27) — release-merge preparation

Owner chose path C (ordinary merge to master with distribution) and accepted
the residual risk of the two unavailable features, recorded in issue #6.
The reviewed head `be9201c` plus master PR #3 were integrated by a merge
(`cec9268`) preserving both safety layers and using master's single native
status protocol. R9.2 made unattended Start at Boot and manual soft reboot
unavailable in production (unconditional refusal, refused writes/artifact,
native hard `0`, legacy OFF migration). R9.3 added the joined-path ambiguous-
outcome fail-closed test and finite R2-14 policy. R9.4 added the finite R2-18
responsiveness requirement, host stall/throw fixtures and a narrow catch so a
throwing `stopForeground` cannot strand start IDs. R9.6 re-points obsolete
PR#2-protocol source-wiring checks to master's single protocol (no case removed,
no coverage weakened) and restores the AES key stack wipe.

- [x] R9.1 — merge `origin/master` into the branch; both safety layers preserved.
- [x] R9.2 — production-unavailable unattended and soft reboot.
- [x] R9.3 — R2-14 fail-closed policy and joined-path mock test.
- [x] R9.4 — R2-18 bounded lifecycle and stranded-ID fix.
- [x] R9.5 — issue #6 opened with explicit owner acceptance.
- [ ] Checkpoint R9 — **documentation-accuracy / CI verification only; does NOT change any gate disposition.** R2-14 / R2-16 / R2-18 remain SCOPED / OPEN; PR stays draft until the reviewer approves the final head. Any "independent reviews APPROVE / RESOLVED" wording in R9 commit messages refers to the project's internal documentation-accuracy subagents, **not** to the external PR reviewer; no reviewer approval of any head is claimed.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Foreground service behavior differs before first unlock | Boot operation may not start | Direct Boot-aware service, DE storage, fail-closed boot count, documented hardware validation gate |
| Privileged child hangs after hook | Indeterminate operation retains resources | No forced kill after start; visible indeterminate state; no retry; service retains ownership |
| Sticky-bit staging change conflicts with loader rename protocol | Late-load fails | Keep ABI-required path, protect directory entry, restore mode, fail closed on pre-existing path |
| Firmware gate rejects a legitimate target variant | Feature unavailable | Use documented exact identifiers; require explicit validation record before widening |
| Robolectric/AGP compatibility | Tests cannot run | Pin published version, validate in Phase 0, prefer pure JVM seams for core logic |
| Native status changes diverge from Java | Misreporting | Typed mapping plus host-native classifier tests and integrated review |
| User-approved SUCCESS semantics conflict with static review | Review remains contested | Record owner decision; review implementation consistency and diagnostic detail, not the accepted product contract |

## Out of Scope / Separate Release Gates

- Actual device installation, exploit execution, cold-boot testing, soft-reboot testing, and CFI hardware validation.
- PR #5 LKM rebuild/re-pin unless required to assemble the final APK.
- AES-key argv redesign; document as residual risk.
- Broad native patch-cleanup redesign; document as residual risk.
- Marking the PR ready or merging it.
