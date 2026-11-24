<!-- Modified 2026-11-24 (DFRoot GZIF): record the Phase 3 review-fix round and verification; Checkpoint 3 remains open pending independent review and commit. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): Task 4.1 (Slice 4A) is in progress; Checkpoint 4 remains unchecked. See NOTICE. -->
<!-- Modified 2026-11-25 (DFRoot GZIF): record the Slice 4A review-fix round, the native-argv scope deviation, and mutation kill evidence. See NOTICE. -->
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

- [ ] Task 4.2: Make `dfm6` non-terminal and add terminal completion/error protocol.
- [ ] Task 4.3: Bound late-load wait and check waitpid/marker errors.
- [ ] Task 4.4: Emit rc=4 only after successful soft-reboot command; add distinct failure code.
- [ ] Task 4.5: Harden `.ksud-stage` with sticky-bit/exclusive-nofollow descriptor staging and mode restoration.
- [ ] Task 4.6: Add host-native parser, marker ordering, and harmless staging-race tests.
- [ ] Checkpoint 4: Native uses one snapshot and reports terminal state after bootstrap completion; reviews approved; commit created.

## Phase 5: Boot Integration and Durable Deduplication

- [ ] Task 5.1: Reduce BootReceiver to BOOT_COUNT claim plus foreground-service start.
- [ ] Task 5.2: Coalesce locked/unlocked broadcasts and fail closed on unavailable/failed boot count.
- [ ] Task 5.3: Route boot through shared coordinator/service; prevent replay after process death.
- [ ] Task 5.4: Keep unattended soft reboot disabled and document behavior.
- [ ] Task 5.5: Add duplicate broadcast, process-restart, overlap, and next-boot tests.
- [ ] Checkpoint 5: Receiver owns no long-running work; at most one attempt per boot; reviews approved; commit created.

## Phase 6: Documentation, Notices, and Integration Cleanup

- [ ] Task 6.1: Update README for supported managers, bundled loaders, SUCCESS contract, environment limits, and boot behavior.
- [ ] Task 6.2: Update CHANGELOG and PORTING-GZIF with architecture and validation status.
- [ ] Task 6.3: Add dated modification notices to all GPL-covered modified files.
- [ ] Task 6.4: Verify loader pins, APK contents, generated-file exclusions, and secret hygiene.
- [ ] Task 6.5: Run full tests, lint, debug assembly, diff checks, and record verification.
- [ ] Checkpoint 6: Documentation matches code; full verification green; reviews approved; commit created.

## Final Comprehensive Review

- [ ] Final 6.1: Correctness/lifecycle independent review.
- [ ] Final 6.2: Security/staging/environment independent review.
- [ ] Final 6.3: Test/build/CI independent review.
- [ ] Final 6.4: Architecture/native integration independent review.
- [ ] Final 6.5: Resolve all Required/Critical findings through fix → review loops.
- [ ] Final 6.6: Run final verification and push approved commit series.
