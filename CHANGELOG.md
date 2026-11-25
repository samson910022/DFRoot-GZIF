# Changelog — DFRoot GZIF

## Unreleased — round-4 (2026-11-25) — PR #3 repair: truthful docs and lifecycle/failure-path evidence

**All evidence in this section is host-only.** No hardware run validates the
current head of `fix/native-exploit-robustness`; a green build does not certify
safe privileged execution. No tag was created and no release artifact was
published. This repair updates documentation (and the PR description) only; it
does not change code, tests, CMake or CI. Historical run6/run8 results stay
attached to the historical implementation described in §4.8-gzif below.

### Stable-ID disposition (consolidated review comment 7126155178, round 4)

| ID | Round-4 disposition | Host evidence / remaining boundary |
|---|---|---|
| P1-02 | **Addressed in host-tested scope; device timing/quiescence UNVALIDATED** | 128-bit nonce generated and bound before any state write; strict `df_parse_final_token()` (exact length, no embedded NUL, exact nonce, no extra bytes) with garbled/foreign tokens ignored; reserved-nonce `BOOTSTRAP_FAILED` published only on the separate app-owned `dfbootfail` channel and consumed as observable rc=1 via a dedicated reader. Real-file and mock tests cover stale state and repeated observations. Android publication/access and on-device freshness remain unproven. |
| P1-07 | **Addressed in host-tested scope; asynchronous safety UNVALIDATED** | Cleanup runs exactly once, after the first valid bound status observation (or once on the shared deadline if none arrives), on the SAME absolute deadline as the poll loop; its time is charged to that deadline and the clock is rechecked. Recovery polling is bounded to 5 s and clamped to the remaining deadline; a NULL/failed `recovery_ok()` or timeout publishes `REBOOT_SKIPPED` and can only disable the reboot. A failed restore adds the rc-64 bit to every code. Mocks prove ordering, count and fail-closed branches, not device quiescence or that asynchronous activity has finished. |
| P1-08 | **OPEN — RELEASE GATE** | The bundled loaders hardcode `/data/local/tmp/.ksud-stage`; a `shell`-uid process can still swap the staged file before the loader renames it. Closing it requires rebuilding the loaders. Documentation is not a fix: keep blocked, do not claim fixed. |
| P2-04 | **Addressed for host/distributable gates; real-device release path UNVALIDATED** | The packaged-byte gate always runs; the real APK gate runs on every non-PR (publishing) event before any upload, over the complete staged APK set, with per-APK manifest + `so_sha256` binding, manifest/dump invalidation at entry and `if-no-files-found: error`. PR runs are non-publishing (host fixtures only, no APK uploaded, no release claim). Pins are unchanged and the gate is fail-closed pending a reviewed re-pin; no tag was created. |
| P2-06 | **Addressed for identity checks; provenance remains informational** | Required identity check on EVERY `dfroot-build-provenance.txt` (git HEAD == `GITHUB_SHA`, release variant, reviewed KO pin, KO manifest and splicehelper binary/source digests, compiler identity fields), plus complete-set release evidence with one row per uploaded APK. Records are audit evidence, not an attestation or an independent reproducible-build proof. |
| P2-07 | **Addressed in this repair** | Test/lifecycle/failure-path counts and CI markers documented below; the PR description is replaced to match the current head and actual CI without device-verification claims; historical run6/run8 remain attached only to the historical implementation. |
| P2-08 | **Addressed in host-tested scope; real PackageManager/fsync UNVALIDATED** | A durable write-ahead `PENDING` marker is published before the commit (full write, read-back, fsync); recovery requires a fully validated PENDING→commit cycle of the SAME key; unattributable faults latch a sticky fail-closed `WILDCARD`; the boot component is disabled synchronously at the failure boundary. Device-protected storage and real PackageManager/fsync behavior are untested. |
| P2-09 | **Addressed; verified by host fixtures** | The packaged verifier invalidates its manifest and section dump at entry; fixtures reuse a single output path for success→failure, poisoned-dump and failure→success sequences, and the upload path verifies the complete staged set before upload. |
| P2-10 | **Addressed in host-tested scope; real UI lifecycle UNVALIDATED** | Lifecycle-scoped listener registration/removal, component-state-authoritative boot switch, the user can always turn boot OFF, nothing auto-enables, and a framework write is accepted only when actual component state matches. Real Activity/Fragment recreation and PackageManager behavior are unvalidated. |

Findings that **remain resolved in the prior narrow scope**: P1-01, P1-03,
P1-04, P1-05, P1-06, P2-01, P2-02, P2-03, P2-05 and P1-09 (newly resolved in
round 4 through the CI-policy environment isolation). Their earlier evidence is
unchanged; none of them constitutes device validation.

### Final host test shape (current head)

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
- **All-events suite markers (7/7)**: `marker-native`, `marker-ko-policy`,
  `marker-packaged-ko`, `marker-apk-packaging-fixture`, `marker-ko-ci-policy`,
  `marker-jvm`, `marker-junit`; one gate fails if any marker is missing.
- **Publishing-only gates (non-PR events, before upload)**: `marker-apk-gate`
  (every distributable APK, per-APK manifest + `so_sha256` binding),
  `marker-provenance-identity` (required, every record),
  `marker-release-evidence`, `marker-distributable-evidence`, then a single
  upload choke point on the staged set. PR runs are **non-publishing**: host
  fixtures only, no APK uploaded, no release claim. Provenance is informational
  evidence, not attestation or reproducible-build proof.

### Validation boundary

P1-02/P1-07 are proven only with host mocks and real-file adapters; device
timing, quiescence and privileged-state safety are UNVALIDATED. P2-04/P2-06/
P2-09 are proven with host fixtures and CI-shaped checks; the real release APK
path stays fail-closed with the unchanged obsolete pre-CFI pins until a
reviewed re-pin, and no tag was created. P2-08/P2-10 are proven with host JVM
tests; real PackageManager/fsync and UI lifecycle behavior are UNVALIDATED.
P1-08 remains OPEN. Auto Soft Reboot and Start on Boot remain disabled by
default and unvalidated. No hardware run validates this head — run6/run8
validate only the historical implementation (old multi-marker protocol,
then-current pins, old preference path). A successful build does not certify
safe privileged execution.

## Unreleased — round-3 PR #3, phase 3 (preference integrity / launch gating)

> Historical section. The round-4 section above is authoritative for current
> counts, cleanup timing and the reserved bootfail channel; this section and
> the two phase sections below are kept for provenance only.

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
- **Real APK gate (P2-04).** On every non-PR (publishing) event the workflow
  unzips `lib/arm64-v8a/libdfroot.so` from EACH release APK, runs the same
  verifier with the NDK's llvm-objcopy and asserts `status: verified` plus a
  matching manifest `so_sha256` **before** any upload or GitHub Release, with
  no `continue-on-error`. PR runs are non-publishing: host fixtures only, no
  APK uploaded, no release claim. **The real gate is blocked until KO re-pin**:
  pins are unchanged and the final bytes must be reviewed and deliberately
  re-pinned before a tag can ship; an unknown blob fails closed rather than
  being accepted by a skip flag. Do not treat a passing host fixture run as
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
  have no `continue-on-error`; only the informational provenance uploads stay
  best-effort. A release-evidence step checks the git SHA, skip OFF in every
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

- run6 and run8 (2026-11-17) validated the **historical implementation** — the
  old multi-marker status protocol and the then-current KO pins — only. Every
  change in this "Unreleased" section is **host-tested only and UNVALIDATED on
  device**; no new hardware run has exercised the single-`/dev/dffinal` flow,
  the new build policy, or the new Java persistence paths.
- **No release may ship with obsolete KO pins.** If a DDK rebuild produces a
  hash outside the two reviewed pins, stop: verify the artifact out of band,
  update the pins in a reviewed commit and docs, or do not release. Never
  skip the check for CI/release builds.
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
