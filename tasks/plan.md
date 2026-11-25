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
| R2-14 atomic terminal publication | temp + fsync + `link()` publication; rc=7 indeterminate channel; pre-link failure leaves no final marker, and the brief post-link/pre-rc7 observation window is documented honestly rather than claimed unobservable | Re-review 3 (`12a633b`) |
| R2-12 indeterminate completion keeps ownership (partial R2-03) | bounded non-killing waits, reader rc=7, single-claim recovery API, lease/config freeze retained | Re-review 4 (`ce781f8`) |
| R2-13 unconfirmed attempt not durable (partial R2-02) | DE-persisted operation attempt before work; process-restart barrier; boot claims suppressed until recovery | Re-review 5 (`7b5397c`) |
| R2-15 pre-admission uncertainty + independent boot OFF (partial R2-07/09) | pure reader decision + reserved diagnostic; OFF reachable in every state; claim-time component recheck | Re-review 6/7 (`7c1f39a`, `54cb000`) |
| R2-16 honest hook result and availability (partial R2-04) | "already attempted/hooked; root status unverified"; read-only availability diagnostic from the authoritative preflight plan | Re-review 8 (`a8003c2`) |
| R2-19 recovery truth | engine-finished evidence before in-process recovery; post-process-death-only trusted-caller persisted clear; no UI, no retry, no boot-count proof | R3.6 (`d32006e`) |
| R2-04 caveat (ambiguous final `SUCCESS` wording) | "run completed — verify root status yourself" / "already attempted/hooked; root status unverified"; typed codes/types and no-reentry guard unchanged | R3.6 (`d32006e`) |
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
**R2-19** (recovery truth) and the remaining **R2-04** caveat. Java-only,
uncommitted: no native code, boot gate, attempt-store format, dependency, UI
threading, CI, device/ADB, payload, privileged container, or APK assembly.

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
  intentionally locked out until a trusted caller clears the barrier or app data
  is reset; a new boot count is not proof of safety. Mock tests: with/without
  acknowledgement, failed persistence, same-process refusal.
- [x] R2-04 caveat: bare `SUCCESS` removed from final user messages
  (`DFRoot: run completed — verify root status yourself` /
  `DFRoot: already attempted/hooked; root status unverified`). Typed codes/types,
  already-hooked SUCCESS code/no-reentry guard, `/dev/df` and `/dev/dfm6`
  progress strings, and no root probe strictly unchanged.
- [x] Host verification: `:app:testDebugUnitTest` 554 tests / 45 suites,
  0 failures/errors/skips; `:app:lintDebug` 0 errors / 15 warnings; host CTest
  unchanged 135/135; `git diff --check` clean. No hardware/device/ADB/payload/
  privileged-container/APK-assembly validation is claimed; CI is POST-PUSH.

## Final Gate

After Phase 6:

- Run four independent final reviewers across correctness/lifecycle, security, test/build, and architecture/native integration.
- Any finding enters fix → review loop.
- When all reviewers approve, run final verification and push the approved commit series.
- Keep the PR draft unless the user explicitly asks to mark it ready; pushing does not imply hardware validation or merge approval.

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
