<!-- Modified 2026-11-24 (DFRoot GZIF): record the final Phase 1 permission fix; Checkpoint 1 remains open pending review and commit. See NOTICE. -->
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

- [ ] Task 2.1: Add serialized off-main-thread `ConfigurationRepository` with checked persistence.
- [ ] Task 2.2: Add saving/verified/error state and fail-closed coordinator admission hook.
- [ ] Task 2.3: Add immutable `RunConfig` and versioned soft-reboot acknowledgement.
- [ ] Task 2.4: Safe-off migrate legacy enabled soft reboot; block unattended soft reboot.
- [ ] Task 2.5: Replace Toast consent with durable modal acknowledgement.
- [ ] Task 2.6: Add commit-failure, ordering, exception, and upgrade-matrix tests.
- [ ] Checkpoint 2: No failed write can enable a run; tests/lint pass; reviews approved; commit created.

## Phase 3: Operation Coordinator, Foreground Service, and Activity Detachment

- [ ] Task 3.1: Add process-scoped single-flight `RunCoordinator` with observable state/log/result.
- [ ] Task 3.2: Add Direct Boot-aware `ExploitService` with notification and timed WakeLock.
- [ ] Task 3.3: Route manual execution through service; make Activity detach-only on destroy.
- [ ] Task 3.4: Add supervised child process, async stdout drain, timed wait, and indeterminate timeout policy.
- [ ] Task 3.5: Lock manager/settings while operation or configuration write is active.
- [ ] Task 3.6: Add fake-process, rotation, duplicate-request, and resource-ownership tests.
- [ ] Checkpoint 3: Rotation/finish never interrupts work; one operation is observable process-wide; reviews approved; commit created.

## Phase 4: Native Snapshot, Completion Protocol, and Staging Hardening

- [ ] Task 4.1: Publish atomic versioned run-config artifact and replace native SharedPreferences parsing.
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
