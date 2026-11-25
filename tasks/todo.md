<!-- Modified 2026-11-24 (DFRoot GZIF): record the Phase 3 review-fix round and verification; Checkpoint 3 remains open pending independent review and commit. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): Task 4.1 (Slice 4A) is in progress; Checkpoint 4 remains unchecked. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): record the Slice 4A review-fix round, the native-argv scope deviation, and mutation kill evidence. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): record Slice 4B1 in-progress terminal-marker protocol, bounded wait, and verification evidence; Checkpoint 4 remains unchecked. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): record the Slice 4B1 review-fix round: shared progress/deadline sources, real fault branches, exact diagnostics, and 15/15 mutation kills; Checkpoint 4 remains unchecked. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): record Slice 4B2 hardened loader-staging implementation, harmless host race tests, and mutation evidence; Checkpoint 4 remains unchecked. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): record Phase 5 Task 5.1-5.5 progress, durable BOOT_COUNT claim protocol, verification, and mutation evidence; Checkpoint 5 remains unchecked pending independent review. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): record the Phase 5 review-fix test round, deterministic finish assertions, real DE-store restart coverage, and mutation evidence; Checkpoint 5 remains unchecked. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): record the re-review 1 repair round (R2-10..R2-16), findings closure, verification numbers, and open post-push/hardware gates. Round checkpoint and final comprehensive review remain unchecked. See NOTICE. -->
# PR #2 Repair Task List

## Phase 0: Build and Test Foundation

- [x] Task 0.1: Make release signing optional and validate incomplete signing files clearly.
- [x] Task 0.2: Add pinned JUnit/Robolectric dependencies and Android test resources.
- [x] Task 0.3: Add independent CI unit-test/lint job with report upload and non-empty test gate.
- [x] Task 0.4: Track `app/src/main/res/xml` in top-level Makefile APK dependencies.
- [x] Task 0.5: Add initial contract tests and verify fresh-clone configuration without secrets.
- [x] Checkpoint 0: `testDebugUnitTest` discovers real tests; `lintDebug` passes; implementation review approved; commit created by this change.

## Phase 1: Side-Effect-Free Preflight and Result Contract

- [x] Task 1.1: Add canonical manager/loader plan and exact selection-aware asset preflight.
- [x] Task 1.2: Enforce loader SHA-256 pins before staging and in packaged APKs.
- [x] Task 1.3: Add owner-user and documented firmware/kernel fail-closed gates.
- [x] Task 1.4: Add typed result mapping with broad rc=1 category and collision-free unknown handling.
- [x] Task 1.5: Preserve owner-approved SUCCESS semantics with internal result detail.
- [x] Task 1.6: Add preflight, result, integrity, environment, adapter, staging, orchestration, and UI-message tests.
- [x] Task 1.7: Enforce packaged-loader pins in CI and resolve Phase 1 review findings with Android wiring, copy-boundary verification, fresh non-executable exclusive staging, a shared Gradle pin source, and POSIX capability guards.
- [x] Checkpoint 1: All rejected prechecks are side-effect-free; tests/lint pass; reviews approved; commit created by this change.

## Phase 2: Durable Configuration and Soft-Reboot Consent

- [x] Task 2.1: Add serialized off-main-thread `ConfigurationRepository` with checked persistence.
- [x] Task 2.2: Add saving/verified/error state and fail-closed coordinator admission hook.
- [x] Task 2.3: Add immutable `RunConfig` and versioned soft-reboot acknowledgement.
- [x] Task 2.4: Safe-off migrate legacy enabled soft reboot; block unattended soft reboot.
- [x] Task 2.5: Replace Toast consent with durable modal acknowledgement.
- [x] Task 2.6: Add commit-failure, ordering, exception, and upgrade-matrix tests.
- [x] Task 2.7: Resolve Phase 2 review findings across cold-start readiness, reload recovery, admission capability, cleanup, Activity/Settings races, and deterministic boundary tests.
- [x] Task 2.8: Apply the final Phase 2 review fixes: publish configuration state and diagnostics through one immutable status snapshot, prove ERROR publication through failed writes/reloads, prove enqueue monitor serialization, cover failed safe-off retry and Settings listener cleanup, and test bounded/interrupted initialization waits.
- [x] Task 2.9: Replace API-33 `String.isBlank()` diagnostic validation with an API-32-safe Unicode code-point helper and regression-test empty, ASCII/Unicode whitespace, and NBSP semantics.
- [x] Checkpoint 2: No failed write can enable a run; tests/lint pass; reviews approved; commit created by this change.

## Phase 3: Operation Coordinator, Foreground Service, and Activity Detachment

- [x] Task 3.1: Add process-scoped single-flight `RunCoordinator` with observable state/log/result.
- [x] Task 3.2: Add Direct Boot-aware `ExploitService` with notification and timed WakeLock.
- [x] Task 3.3: Route manual execution through service; make Activity detach-only on destroy.
- [x] Task 3.4: Add supervised child process, async stdout drain, timed wait, and indeterminate timeout policy.
- [x] Task 3.5: Lock manager/settings while operation or configuration write is active.
- [x] Task 3.6: Add fake-process, rotation, duplicate-request, and resource-ownership tests.
- [x] Task 3.7: Resolve Phase 3 review findings with service-failure recovery snapshots, generation-bound manual admission, latest-start-ID shutdown, active listener/consent gating, and production Activity/service regression tests; verify 353 tests (0 failures/errors/skips), 0 lint errors, 99 focused tests forced 3 times, and 12/12 required mutations killed without committing.
- [x] Task 3.8: Gate configuration Retry on ERROR plus an idle coordinator, recheck coordinator idleness before reload, and cover the queued STARTING-to-TERMINAL precheck-failure transition with a deterministic Settings regression test; verify 354 tests (0 failures/errors/skips), 0 lint errors, 20 focused Settings tests forced 3 times, and 2/2 required guard mutations killed without committing.
- [x] Checkpoint 3: Rotation/finish never interrupts work; one operation is observable process-wide; reviews approved; commit created by this change.

## Phase 4: Native Snapshot, Completion Protocol, and Staging Hardening

- [x] Task 4.1: Publish atomic versioned run-config artifact and replace native SharedPreferences parsing.

  **Slice 4A review-fix round (uncommitted).** Scope note: this slice *intentionally*
  hardened native argv parsing (strict, range-checked `--encap-port`, `--sender-port`,
  `--spi`, `--aes-key`, and mandatory `--run-config` before any patch work). This is a
  deliberate deviation from the older note that called an "AES-key argv redesign"
  out of scope; no public API, SUCCESS contract, or root-probe policy changed.
  Slice 4B terminal/staging work remains untouched and all 4B tasks stay unchecked.

  Production: Java `validateText` now rejects `=` as well as ISO controls (so a
  fingerprint/kernel cannot create a second `key=value` separator); temp-file safety
  checks a regular non-symlink before chmod and requires 0600 only *after* chmod;
  the native manager buffer is `MAX + 1`; stale native marker strings now say
  run-config instead of preferences; the codec has a compile-time-only host test
  mutation seam. Tests exercise the real `effectsFor` closure through narrow
  package-visible seams (real publish ID and real `--run-config` argv captured),
  and the host suite covers uppercase hex, empty paths, NULL outputs, and a mid-read
  identity/size change. CI uses `--no-tests=error`, every host `add_test` has
  `TIMEOUT 30`, relocated mktemp roots are re-probed and emptied after CTest.

  Mutation evidence (each mutation applied, the named test run, then the source
  restored; "killed" = targeted test failed while the mutation was present):

  - Java `=` acceptance removed (`|| codePoint == '='` → `false`) — killed by
    `RunConfigArtifactTest.rejectsInvalidSnapshotInputsBeforeCreatingFiles`.
  - Native extra-`=` separator check removed — killed by host
    `run_config.malformed_extra_equals` and `run_config.equals_in_value`.
  - Full pre-chmod 0600 check restored before chmod — killed by
    `RunConfigArtifactTest.createTempFilePermissionsAreIgnoredUntilExplicitChmod`.
  - Pre-chmod no-follow/regular-file check dropped entirely — killed by
    `RunConfigArtifactTest.symlinkTemporaryFileIsRejectedBeforeAnyChmod`.
  - `operationId` final hop dropped (`newOperationId()` substituted in
    `effectsFor.execute`) — killed by `ExploitRunnerProductionEffectsTest`.
  - `--run-config` argument pair removed from `runBinary` — killed by
    `ExploitRunnerProductionEffectsTest`.
  - Empty-CTest guard removed — demonstrated by `ctest` on an empty test project:
    without `--no-tests=error` exit 0 ("No tests were found!!!"), with the guard
    exit 8; the CI step now always passes the guard.
  - TOCTOU final recheck removed (dev/ino/size comparison dropped) — killed by
    host `run_config.toctou_identity_mismatch`.
  - Lowercase-only `parse_hex_key` — killed by host `run_config.args_key_uppercase`.
  - Lowercase-only `valid_loader_hash` — killed by host
    `run_config.valid_hash_uppercase`.

  Verification: `git diff --check` clean; 65/65 host tests passed (0 failures);
  368 JVM tests (0 failures/errors/skips); lint 0 errors (20 pre-existing warnings).

- [x] Task 4.2: Make `dfm6` non-terminal and add terminal completion/error protocol.

  **Slice 4B1 progress (uncommitted, 2026-11-25).** Added host-compilable
  `run_status.c/.h`: an injectable marker root plus the admitted operation ID,
  four run-scoped terminal marker codes (rc=0 success, rc=1 generic/bootstrap
  error, rc=4 soft-reboot success, rc=6 soft-reboot failure), checked exclusive
  writes (`O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW`, regular-file/no-follow
  verification, checked write/fsync/close, operation ID in the payload), and a
  classifier that ignores foreign IDs/malformed payloads and reports conflicts
  with a stable diagnostic via the shared codec operation-ID validator.
  `exp.c` still prints the owner-visible `ksud start: SUCCESS` diagnostic at
  `/dev/dfm6` but treats every global marker as progress-only and decides the
  exit solely from the run-scoped classifier (conflict -> rc=1, timeout -> rc=2).
  Host coverage: 17 new CTest cases at that point; 24 run-status CTest cases
  after the review-fix round, with all 89 host tests passing (count corrected
  below). Checkpoint 4 stays unchecked pending independent review.

- [x] Task 4.3: Bound late-load wait and check waitpid/marker errors.

  **Slice 4B1 progress (uncommitted, 2026-11-25).** `bootstrap.c` now uses an
  EINTR-safe `waitpid` loop, a 60 s monotonic bounded late-load wait with a
  pure, host-tested `run_status_wait_continues()` predicate, and terminal rc=1
  on timeout. All terminal outcomes use checked marker writes; earlier
  config/module/staging/late-load failures publish rc=1 and return 1. A
  reserved safe pre-admission ID carries the rc=1 marker when the snapshot
  itself cannot be parsed. Global progress markers (including `/dev/dfm6` and
  their messages/paths) are intact.

- [x] Task 4.4: Emit rc=4 only after successful soft-reboot command; add distinct failure code.

  **Slice 4B1 progress (uncommitted, 2026-11-25).** `bootstrap.c` maps the
  soft-reboot command result through the host-tested
  `run_status_soft_reboot_code()` helper (success -> rc=4, any failure -> rc=6)
  and returns the matching code. Java already maps rc=4/rc=6; no SUCCESS
  semantics, root probe, or staging changes were made. The earlier "six
  mutations" claim is corrected by the review-fix round below: 15 concrete
  mutations were applied, each observed to fail its named test, then restored
  byte-for-byte.

  **Slice 4B1 review-fix round (uncommitted, 2026-11-25).** No SUCCESS
  semantics, root-probe policy, staging (4B2), dependency, or APK changes.
  Production: the full legacy progress table (all 13 paths and exact messages,
  including `/dev/dfm6` -> `ksud start: SUCCESS`) now lives once in
  `run_status.c` as an exported const table/count; `run_status_progress_outcome()`
  resolves through it; `exp.c` iterates it with no local copy. Shared exported
  deadlines are 60000 ms late-load, 30000 ms soft-reboot command, and 100000 ms
  terminal reader (strictly greater than 60000+30000); `bootstrap.c` uses the
  first two with an EINTR-safe non-blocking `waitpid` loop (soft-reboot timeout
  -> rc=6, no forced kill), and `exp.c` uses the reader deadline with monotonic
  elapsed and `run_status_wait_continues`. Host fault injection now feeds
  simulated EIO into the same write/fsync error branches used in production;
  the close fault stays on `close_result`, and a close failure no longer
  overwrites an earlier write/fsync diagnostic. Host diagnostics are matched
  to exact branch-specific prefixes (open/write/fsync/close). New host cases:
  empty marker, two torn payload shapes, stale foreign-ID marker with our
  filename, write+close/fsync+close diagnostic precedence, deadline
  composition, and normalized cross-file source wiring. The conflict case now
  also asserts `result.code == RUN_STATUS_TERMINAL_ERROR`.

  Mutation evidence (each exact source mutation below was applied, the named
  test run and observed to fail, and the source restored; "killed" = targeted
  test failed while the mutation was present). All seven review-listed
  categories are covered; the previous count of six is corrected to 15/15:

  - `run_status.c` `/dev/dfm6` entry changed from `RUN_STATUS_PROGRESS_ONLY` to
    `RUN_STATUS_TERMINAL_SUCCESS` — killed by `run_status.protocol_helpers`,
    which iterates every exported marker and asserts `progress_outcome < 0`.
  - `run_status.c` `run_status_soft_reboot_code()` forced to rc=4 for every
    command result — killed by `run_status.protocol_helpers`.
  - `run_status.c` conflict classifier changed from
    `out->code = RUN_STATUS_TERMINAL_ERROR` to `out->code = code_order[0]`
    (first-wins) — killed by `run_status.conflict` (`result.code == 1` and
    `run_status_exit_code() == 1`).
  - `run_status.c` classifier foreign-ID `strcmp` guard removed — killed by
    `run_status.foreign_id` and `run_status.stale_foreign_marker`.
  - `run_status.c` `run_status_wait_continues()` changed to
    `return elapsed_ms >= 0 && limit_ms > 0;` (no deadline) — killed by
    `run_status.bounded_wait`.
  - `run_status.h` reader deadline reduced from 100000 to 90000 (equal to the
    writer late+soft sum, no longer strictly greater) — killed by
    `run_status.deadline_composition`.
  - `run_status.c` write-error branch body removed — killed by
    `run_status.fault_write` (exact "terminal marker write failed" prefix).
  - `run_status.c` fsync-error branch removed (`(void)fsync_result;`) — killed
    by `run_status.fault_fsync` (exact "terminal marker fsync failed" prefix).
  - `run_status.c` close-error branch removed (`(void)close_result;`) — killed
    by `run_status.fault_close` (exact "terminal marker close failed" prefix).
  - `run_status.c` close condition widened to `if (close_result != 0)` so a
    close error overwrites the first diagnostic — killed by
    `run_status.fault_write_close`.
  - `run_status.c` empty payload accepted by synthesizing our ID with rc=0 —
    killed by `run_status.empty_marker`.
  - `run_status.c` code parser accepting `"0"` without its newline — killed by
    `run_status.torn_marker`.
  - `bootstrap.c` late-load deadline call argument replaced by literal 600000
    — killed by `run_status.shared_budget_wiring` (normalized source guard).
  - `bootstrap.c` bounded soft-reboot call replaced by unbounded `run_ctx()` —
    killed by `run_status.shared_budget_wiring`.
  - `exp.c` local `/dev/dfm6` SUCCESS progress table reintroduced — killed by
    `run_status.shared_budget_wiring`.

  Verification: `git diff --check` clean; 89/89 host tests pass (0
  failures/errors/skips; 65 run-config + 24 run-status); aarch64 NDK syntax
  checks of `bootstrap.c` and `exp.c` clean; 368 JVM tests (0
  failures/errors/skips); lint 0 errors (20 pre-existing warnings); 15/15
  mutations killed. No device, payload, privileged container, or APK execution.
- [x] Task 4.5: Harden `.ksud-stage` with sticky-bit/exclusive-nofollow descriptor staging and mode restoration.

  **Slice 4B2 progress (uncommitted, 2026-11-25).** Added host-compilable
  `loader_stage.c/.h`. `loader_stage_configure()` accepts an injectable staging
  directory and destination name for harmless host tests; `NULL` selects the
  FZG1 production defaults `/data/local/tmp` and `.ksud-stage`. `bootstrap.c`
  no longer invokes `system("cp ...")`: it configures and calls
  `loader_stage_install(KSUD_STAGE)`, then calls `loader_stage_restore(&stage)`
  after late-load regardless of that command's result. A restore failure is
  logged and maps to terminal rc=1 through the unchanged `terminal()` helper;
  no terminal marker, global marker, SUCCESS semantic, root probe, or process
  signaling changed. The FZG1 ABI is intact: late-load still receives the
  literal `/data/local/tmp/.ksud-stage` and the loader still renames it to
  `/data/adb/ksud`.

  install() lstats the staging directory (real directory, never a symlink),
  saves its mode, adds S_ISVTX with a checked chmod and re-lstat before any
  destination exists, and rejects a pre-existing destination of every type
  without following or unlinking it. The source is opened
  `O_RDONLY|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK` (O_NONBLOCK only makes a FIFO
  reject deterministically) and must be a regular file. The destination is
  opened `O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC` with mode 0600,
  fstat-verified regular, written with an EINTR-safe full-write loop whose
  source identity/size is rechecked, fsynced, fchmod'd to 0755 only after the
  complete verified copy, and close-checked. Every failure removes only the
  exact created inode after a dev/ino comparison and restores the directory
  mode; restore() is idempotent and never unlinks a replaced entry.

- [x] Task 4.6: Add host-native parser, marker ordering, and harmless staging-race tests.

  **Slice 4B2 progress (uncommitted, 2026-11-25).** Added 23 host CTest cases
  (plus a relocated-root cleanup fixture) in
  `app/src/main/jni/host-tests/loader_stage_test.c`, all with `TIMEOUT 30` and
  nonprivileged temp-dir fixtures. The CMake mode-honoring root probe was
  extended with a sticky-bit probe and relocates to `mktemp -d` when the build
  directory ignores modes. Coverage: normal stage with sticky bit added and
  restored, pre-existing regular/symlink/FIFO/directory rejection, source
  symlink/FIFO/directory/missing rejection, staging-directory symlink/file
  rejection, injected write/fsync/close failures with full cleanup, short-write
  full-loop proof, simulated loader rename failure and mode restoration,
  restore-failure diagnostic and retry, replacement attempts both after the
  destination lstat and before restore, private/complete mode before fchmod,
  production ABI defaults and configure validation, plus source guards proving
  no shell/process spawn/signal and all critical descriptor flags.

  Mutation evidence (each exact source mutation applied, the named test run
  and observed to fail, then the source restored byte-for-byte; "killed" =
  targeted test failed while the mutation was present):

  - sticky bit dropped from the added directory mode — killed by
    `loader_stage.normal`.
  - destination `O_EXCL` removed — killed by
    `loader_stage.replacement_after_lstat`.
  - source `O_NOFOLLOW` removed — killed by `loader_stage.source_symlink`.
  - destination `O_NOFOLLOW` removed — killed by `loader_stage.wiring`.
  - staging-directory regular check disabled — killed by
    `loader_stage.staging_dir_regular`.
  - source regular-file check disabled — killed by `loader_stage.source_fifo`.
  - full-write loop reduced to a single write — killed by
    `loader_stage.fault_short_write`.
  - fsync call and failure branch removed — killed by `loader_stage.fault_fsync`.
  - directory mode restoration removed — killed by `loader_stage.normal`.
  - pre-existing-entry rejection removed — killed by
    `loader_stage.preexisting_regular`.
  - restore dev/ino verification removed — killed by
    `loader_stage.replacement_before_restore`.

  Verification: `git diff --check` clean; 113/113 host tests pass (0
  failures/errors/skips = 64 run-config + 24 run-status + 23 staging + 2
  conditional cleanup fixtures); aarch64 NDK `-fsyntax-only` check of `bootstrap.c`,
  `loader_stage.c`, and the shared modules is clean; 368 JVM tests (0
  failures/errors/skips); lint 0 errors (20 pre-existing warnings); 11/11
  required mutations killed. No device, payload, privileged container, APK
  assembly, dependency, or commit/push was performed.
- [x] Checkpoint 4: Native uses one snapshot and reports terminal state after bootstrap completion; reviews approved; commit created by this change.

## Phase 5: Boot Integration and Durable Deduplication

- [x] Task 5.1: Reduce BootReceiver to BOOT_COUNT claim plus foreground-service start.

  **Phase 5 progress (uncommitted, 2026-11-25).** Added the narrow Java boot-claim
  boundary: `BootCountReader` (read-only `Settings.Global.getInt(BOOT_COUNT)` seam;
  not-found/non-positive/exception -> null), `BootClaimStore` plus
  `SharedPreferencesBootClaimStore` (separate DE-only preferences
  `dfroot_boot_claim` / `last_claimed_boot_count`, static in-process compare-and-set
  lock, checked `commit()`), and `BootClaimCoordinator` (short-lived daemon
  single-thread claim executor, claim-then-start ordering, injectable reader/store/
  executor/starter/finisher/logger seams). `BootReceiver` is now action validation +
  `goAsync()` + durable claim submission + explicit `ExploitService.start(...)`;
  it owns no WakeLock, configuration admission, runner, probe, or exploit thread.
  Manifest receiver stays disabled by default, Direct Boot-aware, and unexported.

- [x] Task 5.2: Coalesce locked/unlocked broadcasts and fail closed on unavailable/failed boot count.

  **Phase 5 progress (uncommitted, 2026-11-25).** Claim protocol: a positive
  `BOOT_COUNT` is required; the claim succeeds only when it is strictly greater
  than the persisted value and a checked DE commit returns true. Both
  `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED` pass through the same claim, so the
  first broadcast claims the boot and any duplicate (including after process death)
  aborts without starting the service. Missing setting, non-positive count,
  false/throwing commit, and rejected worker submission all abort; the
  `PendingResult` is finished on every path. The system `BOOT_COUNT` is never
  written. A successful claim is not rolled back when the foreground start fails,
  so there is no same-boot retry; a strictly newer count is required next boot.

- [x] Task 5.3: Route boot through shared coordinator/service; prevent replay after process death.

  **Phase 5 progress (uncommitted, 2026-11-25).** The claim only starts the
  existing Direct Boot-aware `ExploitService` with `ACTION_UNATTENDED_BOOT`; service
  and `RunCoordinator` ownership, generation-bound configuration admission, and
  `START_NOT_STICKY` are unchanged. Boot/manual overlap continues to be rejected
  atomically by `RunCoordinator`; a dedicated Phase 5 test proves the boot action
  still cannot admit a run while a manual operation is active. No soft-reboot,
  SUCCESS, root-probe, native, or dependency change was made.

- [x] Task 5.4: Keep unattended soft reboot disabled and document behavior.

  **Phase 5 progress (uncommitted, 2026-11-25).** `RunConfig.validateFor` and
  `isUnattendedBootAllowed` are untouched: unattended boot still requires soft
  reboot durably OFF even with current manual acknowledgement. The same policy test
  is used as a mutation guard. Hardware/cold-boot validation remains a separate
  release gate; no device or privileged execution was performed.

- [x] Task 5.5: Add duplicate broadcast, process-restart, overlap, and next-boot tests.

  **Phase 5 progress (uncommitted, 2026-11-25).** Added `BootClaimTest`,
  `BootCountReaderTest`, `SharedPreferencesBootClaimStoreTest`, and `BootClaimFakes`;
  updated `BootReceiverBoundaryTest` (the ownership guard now permits the narrow
  claim executor but still forbids raw exploit threads/WakeLock/admission/probe and
  requires `goAsync()`). Coverage: both broadcast orders, duplicates, persisted-claim
  process restart, unavailable/not-found/non-positive counts, failed/throwing
  persistence, failed service start without retry, stale/regressed counts, next boot,
  claim-before-start ordering, DE isolation from the full-snapshot writer, prompt
  return, boot/manual overlap, and manifest invariants.

  Mutation evidence (each mutation applied, the named targeted test observed to
  fail, then the source restored byte-for-byte; all restores verified by
  `git diff --check`):

  - Duplicate boot count accepted (`bootCount <= previous` check disabled) — killed
    by `SharedPreferencesBootClaimStoreTest.duplicateAndOlderCountsAreNotClaimed`.
  - Service start moved before the durable claim — killed by
    `BootClaimTest.serviceStartOccursOnlyAfterSuccessfulCheckedClaim`.
  - Failed commit ignored (`if (!store.claim(...))` disabled) — killed by
    `BootClaimTest.failedDurablePersistenceDoesNotStartTheService`.
  - `goAsync()` removed from the receiver — killed by
    `BootReceiverBoundaryTest.receiverOwnsNoThreadWakeLockAdmissionProbeOrRunner`.
  - Unattended soft reboot relaxed (`isUnattendedBootAllowed` forced true) — killed
    by `BootConfigurationPolicyTest.currentManualConsentStillRejectsUnattendedExecution`.
  - Raw `new Thread(...)` reintroduced in the receiver — killed by the same
    `BootReceiverBoundaryTest` ownership guard.

  Verification: `git diff --check` clean; 399 JVM tests (0 failures/errors/skips;
  baseline 368 preserved plus 31 Phase 5 tests); lint 0 errors (21 warnings);
  113/113 host CTest cases pass with no native changes. No device, payload, ADB,
  privileged container, APK assembly, dependency, commit, or push was performed.

  Remaining concerns: SharedPreferences cannot provide a cross-process CAS, so the
  claim lock is process-wide (the app remains single-process); a failed commit can
  leave an uncommitted in-memory candidate, so after process death a later same-boot
  broadcast could retry once (a successful claim never retries); foreground-service
  start eligibility from boot still requires separate hardware validation.

  **Phase 5 review-fix round (uncommitted, 2026-11-25).** No production boot-claim
  semantics, native code, dependency, or APK change. The receiver gained only a
  narrow package-visible `submitClaim(context, factory, finisher, starter)` seam so
  tests can observe `finish()` directly; the real path still delegates to it from
  `receive()` with the real service starter. All new/changed assertions are
  deterministic: inline executors, bounded latches, or Robolectric service shadow;
  no `Thread.sleep` remains in the changed tests.

  - `BootClaimTest.everyClaimPathFinishesThePendingResultExactlyOnce` (renamed
    from `everyPathFinishesThePendingResult`) now covers accepted/success,
    unavailable/null and non-positive counts, duplicate claim, failed commit,
    exception from reader, exception from store, and service-start exception, with
    a failed-commit finish assertion. Focused path tests:
    `failedCommitFinishesExactlyOnceWithoutStarting`,
    `throwingReaderFinishesExactlyOnceWithoutStarting`,
    `throwingStoreFinishesExactlyOnceWithoutStarting`,
    `serviceStartExceptionFinishesExactlyOnceWithoutRetry`, and
    `executorRejectionFinishesExactlyOnceAndNeverRunsTheClaim`, backed by new
    `ThrowingReader` and `RejectingExecutor` fakes (no sleeps, no real queues).
  - `BootReceiverBoundaryTest.factoryFailureFinishesExactlyOnceWithoutStarting`
    pins the `factory.create` throwing path through the new seam.
    `onReceiveRunsTheClaimOffMain` was renamed from
    `onReceiveReturnsBeforeTheClaimWorkerRunsAndFinishesAfterwards` so the name no
    longer claims a finish observation that the test does not assert; the unused
    `Thread caller` local was removed from
    `receiverInvokesTheNarrowStarterInlineWithAnInlineExecutor` (renamed from the
    misleading "...AndReturnsPromptly" name for the synchronous starter).
  - `productionBootActionStartsTheExplicitServiceOnce` now waits on a bounded
    5 s `CountDownLatch` finisher instead of `Thread.sleep(50L)`, so the assertion
    that no second service starts is stable while still using the real production
    coordinator and real service starter; `api32BootRequestDoesNotRequireNotificationPermission`
    uses the same latch seam, and the old poll-with-`Thread.sleep` helper was removed.
  - `realStoreRecreatedAfterProcessRestartDoesNotStartTheServiceAgain` performs a
    successful claim through the real DE store, recreates
    `SharedPreferencesBootClaimStore.forDeviceProtectedStorage(context)` to model
    process death, then dispatches the same boot count and asserts no second
    service start plus an unchanged persisted count.
    `realDeviceProtectedStoreCoalescesUnlockedThenLocked` adds the reverse order.
  - `receiverOwnsNoThreadWakeLockAdmissionProbeOrRunner` forbids `Executor` again;
    `BootReceiver` itself contains no `Executor` reference. `BootReceiverDependencies`
    has zero references (verified by repository-wide search) and stays removed.
  - `SharedPreferencesBootClaimStoreTest.FakePreferences` explicitly models
    Android's memory-before-disk commit semantics: `putLong()` updates the cached
    in-memory map before commit, durable values update only on successful commit.
    `failedCommitLeavesMemoryCandidateButNoDurableClaimUntilRestart` characterizes
    that a failed commit leaves an in-memory candidate visible to `lastClaimed()`,
    persists nothing durably, permits no same-process retry of that count, and can
    be reclaimed after restart. The durable-value assertion in
    `falseCommitNeverClaimsTheBoot` is unchanged.

  Mutation evidence (`--rerun-tasks`; each mutation applied, the named targeted
  test observed to fail, then the source restored byte-for-byte):

  - `finisher.finish()` removed from the executor-rejection catch — killed by
    `BootClaimTest.executorRejectionFinishesExactlyOnceAndNeverRunsTheClaim`.
  - `finisher.finish()` removed from the claim `finally` — killed by 4/4 targeted
    tests: `failedCommitFinishesExactlyOnceWithoutStarting`,
    `throwingReaderFinishesExactlyOnceWithoutStarting`,
    `serviceStartExceptionFinishesExactlyOnceWithoutRetry`, and
    `everyClaimPathFinishesThePendingResultExactlyOnce`.
  - `finisher.finish()` removed from the receiver dispatch catch — killed by
    `BootReceiverBoundaryTest.factoryFailureFinishesExactlyOnceWithoutStarting`.
  - Recreated-store dedup compare (`bootCount <= previous`) disabled — killed by
    `BootReceiverBoundaryTest.realStoreRecreatedAfterProcessRestartDoesNotStartTheServiceAgain`.

  Verification: `git diff --check` clean; 409 JVM tests (0 failures/errors/skips;
  previous 399 preserved plus 10 review-fix tests, including the final
  close()-Error lifetime test); lint 0 errors (21 pre-existing
  warnings); host CTest 113/113 (no native changes). No device, payload, ADB,
  privileged container, APK assembly, dependency, commit, or push was performed.

- [x] Checkpoint 5: Receiver owns no long-running work; at most one attempt per boot; reviews approved; commit created by this change.

<!-- Modified 2026-11-25 (DFRoot GZIF): record Phase 6 documentation/notices/hygiene completion and final non-privileged verification; Checkpoint 6 remains unchecked pending independent review. See NOTICE. -->

## Phase 6: Documentation, Notices, and Integration Cleanup

- [x] Task 6.1: Update README for supported managers, bundled loaders, SUCCESS contract, environment limits, and boot behavior.

  `README.md`: removed the manager-agnostic / "any KernelSU-compatible manager"
  claim. Added a **Supported managers** table naming exactly
  `me.weishu.kernelsu` → pinned bundled `libksud.so` (Samsung-patched 3.3.0) and
  `com.rifsxd.ksunext` → pinned bundled `libksudnext.so` (Samsung-patched 3.4.2),
  with no implicit fallback (manager APK `libksud.so` is never a loader). Added a
  truthful **Result contract**: `/dev/dfm6` remains the owner-visible late-load
  SUCCESS progress diagnostic; native returns only after a run-scoped terminal
  marker; rc=0 normal, rc=1 bootstrap/terminal error including late-load timeout,
  rc=2 native setup/marker timeout, rc=3 patch failure, rc=4 soft-reboot command
  success, rc=6 soft-reboot command failure; no root probe is added. Added
  **Operation ownership, boot, and soft reboot**: coordinator/FGS ownership and
  detach-only Activity, Direct Boot `BOOT_COUNT` one-attempt-per-boot claim,
  unattended soft reboot disabled, manual soft reboot experimental/unvalidated
  (prior hard-reboot risk). Corrected signing to **optional**
  `signing.properties`, added the gitignored-KO APK prerequisite, and added a
  **Validation status (2026-11-25)** section (409 JVM / 113 CTest / 0 lint
  errors; no device/hardware/privileged/APK validation).

- [x] Task 6.2: Update CHANGELOG and PORTING-GZIF with architecture and validation status.

  `CHANGELOG.md`: added the dated **2026-11-25** section summarizing Phases 0–5,
  files/areas changed, exact test counts, and honest validation limits (no
  hardware claim; historical 2026-11-17 runs labelled historical).
  `PORTING-GZIF.md`: corrected stale chain/marker/boot/soft-reboot sections (§4,
  §6, §8.1, §8.4, §9.2, §9.3, §9.6, §10, §14), marked §5/§11/§12/§14 as the
  **historical** 2026-11-17 run ledger, and added **§15 Current architecture**
  (immutable configuration, RunCoordinator/FGS, atomic run-config artifact,
  run-scoped terminal markers, hardened staging, durable boot claim) plus
  residual risks (sticky bit does not protect against the `/data/local/tmp`
  owner (`shell`); FGS boot eligibility needs hardware validation; soft reboot
  unvalidated/hard-reboot risk; AES-key argv redesign and broad native patch
  cleanup out of scope).

- [x] Task 6.3: Add dated modification notices to all GPL-covered modified files.

  Scanned the 96 committed PR files vs base `ae627f17` plus the 6 Phase 6
  doc-only files (102 files vs base in total), all locally committed on
  `fix/app-correctness-safety`.
  Every modified
  GPL-covered source file already carried a dated notice except those touched
  on 2026-11-25 without one; added `Modified 2026-11-25 … See NOTICE.` notices
  to `RunCoordinator.java` and eight tests
  (`ExploitRunnerConfigurationAdmissionTest`, `ExploitRunnerOperationIdTest`,
  `ExploitServiceTest`, `MainActivityTest`, `RunCoordinatorOperationIdTest`,
  `RunCoordinatorProcessIntegrationTest`, `RunCoordinatorTest`,
  `SettingsFragmentTest`) — **no existing notice was removed**. `NOTICE` gained
  a 2026-11-25 modification/verification note (explicitly stating bundled
  loaders and third-party components were **not** modified) and `THIRD_PARTY_NOTICES.md`
  gained a 2026-11-25 note; its stale AndroidX runtime-dependency row was
  corrected (catalog aliases only, not shipped) and JUnit 4 / Robolectric were
  recorded as test-only dependencies.

- [x] Task 6.4: Verify loader pins, APK contents, generated-file exclusions, and secret hygiene.

  - Pins: `sha256sum` of both `app/src/main/jniLibs/arm64-v8a/*.so` matches
    `app/build.gradle.kts` exactly — `libksud.so`
    `aab769430467e5a9f723c1bc8a3180e6fb0b0b82c52fba7b99887199407fc940`,
    `libksudnext.so`
    `679504dd4e104dc1a296ed4ec795b5a5f3e808c2bd345a7ecfa201b40f1dca1d`.
  - `.gitignore` covers build outputs (`build/`, `app/build/`, `app/.cxx/`,
    `out/`), secrets (`signing.properties`, `keystore.jks`, `local.properties`),
    generated binaries (`splicehelper`) and all generated KO paths; added
    `lkm/*.ko` (previously only `lkm/dfroot.ko`) to close the last KO gap.
  - Secret-pattern scan over all tracked files: **zero** secret hits (the sole
    `KEYSTORE_PASSWORD=` token is a `printf '%s'` placeholder in `Makefile`,
    not a value; no private-key/token patterns found).
  - Tracked-file audit: **0** generated/build/KO/class/secret files tracked
    (140 tracked files total); no KO files exist on disk.
  - APK assembly intentionally **not** performed: `ko/*.ko` is absent and
    assembling requires the privileged podman/DDK container; no device, payload,
    ADB, or privileged operation was run.

- [x] Task 6.5: Run full tests, lint, diff checks, and record verification.

  - `git diff --check`: **0** issues (clean).
  - `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ANDROID_HOME=/usr/lib/android-sdk ./gradlew :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain`: exit 0;
    **409 JVM tests, 0 failures / 0 errors / 0 skipped** (40 XML reports);
    lint **0 errors**, 21 warnings present before the Phase 6 docs-only edits
    (one is a `PrivateApi` warning at `AndroidRunEnvironment.java:93`, a file
    added by PR #2 — not all 21 predate PR #2).
  - Host CTest: no configured build dir existed, so a fresh non-privileged
    configure/build was made from `app/src/main/jni/host-tests` into
    `/mnt/240G_SSD/opencode_tmp/dfroot-hosttests`; `ctest --output-on-failure
    --no-tests=error`: **113/113 passed, 0 failed**.
  - No device/hardware/ADB/payload/privileged-container/cold-boot/soft-reboot/
    APK-assembly validation and no commit or push.

- [x] Checkpoint 6: Documentation matches code; non-privileged verification green; independent reviews approved; commit created by this change.

<!-- Modified 2026-11-25 (DFRoot GZIF): re-review 1 repair round committed locally (6686973..a8003c2); documentation/hygiene only; Checkpoint R1 and final comprehensive review remain unchecked. See NOTICE. -->

## Re-review 1 repair round (2026-11-25, R2-10..R2-16)

New repair round on `fix/app-correctness-safety` after the first repair round.
Commits: `6686973` (pin gate), `639a9d8` (staging identity), `12a633b` (atomic
publication + rc7), `ce781f8` (indeterminate ownership), `7b5397c` (durable
attempt barrier), `7c1f39a` (pre-admission uncertainty), `54cb000` (independent
Boot OFF), `a8003c2` (honest hook result + availability diagnostic). HEAD
`a8003c2`. Findings closed: **R2-10..R2-16**, plus the remaining parts of
**R2-02/03/04/07/09**. The final documentation/notices/hygiene commit set is
left uncommitted.

- [x] R1.1 Pin gate (`6686973`, R2-10): buildSrc loader-pin verifier tasks,
  clean/tampered synthetic fixture guard, CI fixture job and `:app:tasks --all`
  regression. Locally verified: tasks resolve, fixtures pass/reject as intended.
- [x] R1.2 Staging identity (`639a9d8`, R2-11): descriptor-pinned fstat/lstat
  identity, conservative no-unlink of replacements, non-writing pin via
  `/proc/self/fd` to avoid `ETXTBSY`. **117/117 local CTest, 115/115 CI-like.**
- [x] R1.3 Atomic publication (`12a633b`, R2-14): temp + fsync + `link()`
  publication, rc=7 indeterminate channel, `NativeResultMapper` maps only to
  `RESULT_INDETERMINATE`. **123/123 CTest, 26 mapper tests.**
- [x] R1.4 Indeterminate ownership (`ce781f8`, R2-12 / partial R2-03): bounded
  non-killing waits, reader rc=7, lease/config/single-flight retained until
  single-claim owner recovery. **131/131 CTest, 428 JVM tests, lint 0 errors.**
- [x] R1.5 Durable barrier (`7b5397c`, R2-13 / partial R2-02): DE-persisted
  operation attempt before work, process-restart admission rejection, boot
  claims suppressed until recovery, compare-and-clear. **459 JVM, 131 CTest,
  lint 0 errors.**
- [x] R1.6 Pre-admission uncertainty (`7c1f39a`, R2-15 / partial R2-07): pure
  decision helper + reserved diagnostic, admitted markers authoritative.
  **135/135 CTest**; Java mapping unchanged.
- [x] R1.7 Independent Boot OFF (`54cb000`, R2-15 / partial R2-09): OFF reachable
  in ERROR and active/indeterminate states, ON gates retained, claim-time
  component recheck, UI surfaces failures. **471 JVM, 135 CTest, lint 0 errors.**
- [x] R1.8 Honest hook result + availability (`a8003c2`, R2-16 / partial R2-04):
  dropped the root-verification claim with unchanged code/no-reentry guard,
  read-only availability diagnostic from the authoritative preflight plan.
  Bare `SUCCESS` wording is further refined in R3.6 below. **484 JVM tests
  (44 suites), 135 host CTest cases, lint 0 errors / 21 warnings.**
- [x] R1.9 Documentation/notices/hygiene: README re-review section, CHANGELOG
  phase table, PORTING §16 + stale-contract corrections, NOTICE and
  THIRD_PARTY_NOTICES dated notes, plan/todo updates; per-file 2026-11-25 notices
  added where missing (none removed); pins re-verified; `.gitignore` and tracked
  files audited; secret scan benign-hit-only; `git diff --check` clean.
- [x] Checkpoint R1: comprehensive review approval and final verification are
  complete — all four comprehensive reviews APPROVED and final verification is
  green. Final commit and push are performed by the main agent. No CI green
  result is claimed locally.

Open gates recorded for the main agent: fresh GitHub CI green run including the
negative fixture is a **POST-PUSH gate**; hardware gates remain Direct Boot FGS
eligibility and real soft-reboot safety; security limitations remain the
sticky-bit `/data/local/tmp` owner and `/dev` marker-root trust; AES-key argv
redesign and recovery-UI delivery are out of scope; `BOOT_COUNT` as a new-boot
boundary still needs owner acknowledgement. No device/hardware/ADB/payload/
privileged-container/APK-assembly validation was performed.

<!-- Modified 2026-11-25 (DFRoot GZIF): R3.6 recovery-truth and honest-wording round; Java/docs only, left uncommitted. See NOTICE. -->

## Re-review 3.6 repair round (2026-11-25, R2-19 + R2-04 caveat)

Third repair round on `fix/app-correctness-safety` (HEAD `d32006e`), left
uncommitted. Java/docs only: no native code, boot gate, attempt-store format,
dependency, UI threading, CI, device/ADB, payload, privileged container, or APK
assembly.

- [x] R3.6.1 Recovery truth (`RunCoordinator.java`): `recoverIndeterminate` keeps
  exact ID + single claim + checked compare-and-clear and adds engine-completion
  evidence (`engineStarted` before `engine.run()`, `engineFinished` only in a
  `finally` after the call returns/throws). A supervision timeout while the
  engine is blocked is refused; a proven pre-effect rejection remains
  recoverable; exactly-once cleanup preserved. Tests: blocked engine refused,
  post-return success, failed persistence refused, concurrent correct-ID callers
  one winner (`RunCoordinatorTest`, `RunCoordinatorAttemptTest`,
  `RunCoordinatorProcessIntegrationTest`).
- [x] R3.6.2 Persisted attempt (`RunCoordinator.java`): post-process-death only;
  requires acknowledgement + exact ID + checked clear + a record loaded at
  initialization (never one published by this process). No in-app recovery UI:
  an ordinary user with an unconfirmed persisted attempt is intentionally locked
  out until a trusted caller clears the barrier or app data is reset; a new boot
  count is not proof of safety. Mock tests cover both acknowledgement states,
  failed persistence and same-process refusal.
- [x] R3.6.3 Honest wording (`ResultMessages.java`, `ResultMessagesTest.java`):
  generic success `DFRoot: run completed — verify root status yourself`,
  already-hooked `DFRoot: already attempted/hooked; root status unverified`.
  Exact new wording pinned; typed codes/types, already-hooked SUCCESS code and
  no-reentry guard, `/dev/df` and `/dev/dfm6` progress strings, and no root probe
  strictly unchanged.
- [x] R3.6.4 Docs: README/CHANGELOG/PORTING §17/plan/todo describe recovery
  truth, intentional lockout and honest wording; stale R2 hashes remapped by
  subject after the PR #1 rebase (`6686973`, `639a9d8`, `12a633b`, `ce781f8`,
  `7b5397c`, `7c1f39a`, `54cb000`, `a8003c2`); R3 hashes left as current.
- [x] R3.6.5 Verification: `:app:testDebugUnitTest` **554 tests / 45 suites,
  0 failures / 0 errors / 0 skipped**; `:app:lintDebug` **0 errors / 15 warnings**;
  host CTest unchanged **135/135**; `git diff --check` clean. No device/hardware/
  ADB/payload/privileged-container/APK-assembly validation and no commit/push;
  CI green remains a POST-PUSH gate.

## Final Comprehensive Review

All four comprehensive reviews APPROVED and final verification is green. Final
commit and push are performed by the main agent.

- [x] Final 6.1: Correctness/lifecycle independent review.
- [x] Final 6.2: Security/staging/environment independent review.
- [x] Final 6.3: Test/build/CI independent review.
- [x] Final 6.4: Architecture/native integration independent review.
- [x] Final 6.5: Resolve all Required/Critical findings through fix → review loops.
- [x] Final 6.6: Final verification complete; approved commit series is committed
  and pushed by the main agent.
