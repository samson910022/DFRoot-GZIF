# Changelog — DFRoot GZIF

## 2026-11-26 — Fourth repair round (R4.1–R4.9) consolidated — PR #2 / branch `fix/app-correctness-safety`

Authoritative record of the **fourth repair round** (final local HEAD
`74d35f5`) on top of the R3 round (`1252b3d`). It closes Re-review 3 findings
**R2-13, R2-12, R2-14 (narrowed), R2-16, R2-17, R2-18, R2-20, R2-21 and R2-22**.
Every phase below was host-verified only by focused runs and independent
reviews; **no device, hardware, ADB, payload, privileged-container, cold-boot,
soft-reboot, APK-assembly, dependency or bundled-binary validation was
performed**, and no full-suite/CI green result is claimed locally. Older notes
below are historical phase records, not current gate claims.

### Phase table — commit, finding, fix loop, verification

| Phase / commit | Finding closed | Fix loops and focused verification evidence |
|---|---|---|
| R4.1 `6348c71` | R2-13 | Replaced the instance-local poison latch with a static process-wide latch keyed by the attempt-preference file, so every store wrapper (coordinator, boot claim, artifact authorization) fails closed after a poisoned commit. Harmless cross-wrapper tests; **59 focused tests, 0 failures**; two independent reviews (correctness/safety, test/contract) APPROVED, no Required/Critical. |
| R4.2 `b8526d8` | R2-12 | `RUN_STATUS_VERDICT_CONFLICT` maps to rc7, never definitive rc1; origin-aware diagnostics never claim native rc=7 for an unknown exit or Java exception; unverified outcomes retain lease, freeze, attempt and single-flight. **137 host CTest + 114 focused Java tests, 0 failures**; two independent reviews (native, Java contract) APPROVED. |
| R4.3 `916a18e` | R2-14 (narrowed, not proved safe) | Two-phase acknowledgement: reader accepts a definitive non-7 code only with a matching `dfroot_confirm_` marker (same operation ID and code); unconfirmed intended marker yields no decision; any detected publication failure publishes rc7 **before** retraction and the writer exits 7. Early-observation test reworked into fix verification + three confirmation cases. **140 host CTest, 0 failures**, `-Wall -Wextra -Werror -pedantic` clean, no production test hooks; three independent reviews APPROVED. Residual abnormal-death window remains an OPEN hardware/OS gate. |
| R4.4 `7e41d27` | R2-16 | Authoritative unattended gate moved inside `submit()` under the durable-publish lock, with a pre-engine recheck; OFF during init/blocked commit yields a proven pre-effect `PRECHECK_FAILED` (no engine, checked clear); OFF after engine start is not revoked. **98 focused tests, 0 failures**; two reviews APPROVED. Optional residual: no isolating test for the second recheck window — recorded, not silently accepted. |
| R4.5 `11ae87d` | R2-17 | Click-time request IDs carry through service to coordinator; correlated `ACCEPTED`/`REJECTED`/`CANCELLED`; unclaimed service-local cancellation published and closed exactly once, never replayed; accepted work stays coordinator-owned. **142 focused tests + 3 deterministic `BootReceiverBoundaryTest` repetitions, 0 failures**; two reviews plus **one Required fix loop** (stale/flaky OFF-at-admission assertion) RESOLVED. Optional residual: benign duplicate correlated rejection on unattended-OFF — recorded. |
| R4.6 `15bb5cc` | R2-18 | Main-thread failure/cancellation reports enqueued on the admission worker instead of taking the durable-lock monitor; status/notification reads stay volatile-snapshot-only. Narrow guarantee: a worker may still wait on storage. **95 focused tests, 0 failures**; correctness review found **one Required issue** (over-broad destruction guard dropped early `REJECTED`), fixed and re-review RESOLVED; test-quality review APPROVED. |
| R4.7 `a51f74e` | R2-20 | Per-start lifetime resolves each start once; foreground removal no longer suppresses `stopSelf` for later-resolved starts; per-service owner token invalidated before destruction cancellation and denied under the admission lock and before engine start; engine-started work never cancelled. **158 focused tests in the touched classes, 0 failures** (156 initially, 158 after the Required fix loop); correctness review found **two Required interleavings** (stranded accepted start, `foregroundRemoved` gating `stopSelf`), both fixed and re-review RESOLVED; test-quality and architecture reviews APPROVED. |
| R4.8 `186fc8d` | R2-21 | Duplicate starts rejected before WakeLock/admission work while another request is `PENDING`/`CLAIMED`, with correlated `REJECTED`; single-thread admission worker shut down gracefully in `onDestroy()` after outcome reports are queued and recreated on `onCreate()`. **74 focused tests + 3 determinism repetitions, 0 failures**; test-quality review found **one Required flake** (ungated `PENDING` assertion), fixed via a per-cycle latch gate and re-review RESOLVED; correctness review APPROVED with optional residuals. |
| R4.9 `74d35f5` | R2-22 | Honest wording/docs only, no functional/control-flow change (message text only): `SOFT_REBOOT_PENDING` no longer says "root active" (now `DFRoot: soft-reboot was requested (root status unverified); verify root status yourself`, code 4 kept); `RunCoordinator` Javadoc says `engineFinished` proves only that the Java call returned/threw and that app-data reset is not evidence effects stopped; final messages ban "root active"/bare `SUCCESS`; README/CHANGELOG/PORTING/plan/todo consolidated. Focused `ResultMessagesTest`: **50 tests, 0 failures / 0 errors / 0 skipped** (10 parameterized cases × 5 methods). No retry/clear control added. |

### R4 final gate state and residual optional items

- **Result contract:** marker conflict → rc7 (never rc1); definitive non-7
  results need a matching `dfroot_confirm_` marker; an early intended marker is
  indeterminate; rc7 is published before retraction; the residual
  abnormal-death window is an **OPEN hardware/OS gate, NOT proved safe**.
- **OFF / ownership:** authoritative gate in `submit()` with pre-engine
  recheck; no revocation after engine start; boot FGS eligibility is still a
  hardware gate. Unattended soft reboot stays OFF; manual soft reboot remains
  experimental/unvalidated on GZIF (historical full hard reboot on this model).
- **Recovery:** no in-app UI; ordinary users intentionally locked out behind
  the durable barrier; app-data reset only discards the barrier and is NOT
  safe recovery; `engineFinished` is a narrow trusted-caller precondition, not
  a retry/clear control; a new `BOOT_COUNT` is diagnostic only.
- **Residual optional items recorded, not silently accepted:** R4.4 missing
  isolating second-recheck test, R4.5 duplicate correlated rejection, R4.8
  idle worker thread/benign duplicates, signed-release job not running
  packaged-pin verification itself (inherited PR #1 policy), AES key in argv out
  of scope, `/dev` marker-root trust, sticky-bit `/data/local/tmp` owner
  (`shell`), recovery UI out of scope. Final comprehensive reviews additionally
  recorded (non-blocking): permissive null-context branch in the production
  `BootComponentGate` constant (all production callers pass a context),
  fatal-`Error` during checked commit can leave a service-local WakeLock until
  timeout, and a synchronous locking coordinator call in the Activity's
  service-start failure catch (responsiveness only).
- **Final gate:** four independent comprehensive reviews run at HEAD
  `74d35f5`: correctness/lifecycle APPROVE, security APPROVE, architecture/native
  APPROVE, test/build/CI REQUEST CHANGES solely for stale R4.9 "uncommitted"/HEAD
  tags — corrected here and re-reviewed APPROVE. Fresh host verification by the
  main agent: **623 JVM tests / 45 suites, 0 failures/errors/skips**, **140 host
  CTest**, lint 0 errors, workflow policy 67/67, signing 40/40, make 36/36, pins
  matched, `git diff --check` clean. A fresh remote CI green run after push remains
  a **POST-PUSH gate**; it is not claimed locally.

## 2026-11-26 — R4.8: bounded service-local admission lifetime (R2-21)

Short note (R4.9 consolidates docs): `ExploitService` now admits at most one
undecided service-local request. Any further start while an owned request is
`PENDING`/`CLAIMED` is correlated-`REJECTED` on the caller with
"another operation request is pending; duplicate rejected" **before** a timed
WakeLock is acquired or work is enqueued; early null-intent and foreground-start
failures use that same rejection while a request is pending. The lazily created
single-thread admission worker is shut down gracefully in `onDestroy()` only
after cancellation/outcome reports have been enqueued, and a fresh worker (and
owner token) is used on the next `onCreate()`. Already-queued reports still run;
no safety check, lease transfer, success/progress semantic, root probe,
kill/signal or replay contract changes.

## 2026-11-25 — R4.7: per-start foreground lifetime and owner-token gate (R2-20)

Short note (R4.9 consolidates docs): `ExploitService` now tracks every start as
`PENDING → CLAIMED → ACCEPTED/REJECTED/CANCELLED → TERMINAL`, issues `stopSelf`
once per resolved start only when no owned start is unresolved and no owned
operation is active, and removes the foreground notification only at that point.
A terminal snapshot for one operation cannot cancel or stop a newer pending
request. Each service instance mints a one-way `RunCoordinator.OwnerToken`,
invalidated before pending work is cancelled on destruction; the coordinator
rejects a dead owner under the admission lock and rechecks before `engine.run()`.
A proven pre-engine owner loss clears the durable attempt as `PRECHECK_FAILED`
without an engine call; an engine that already started is never cancelled and an
unverified outcome keeps lease, freeze and barrier. Legacy callers without a
token keep the previous contract. No root probe, kill/signal or replay.

## 2026-11-25 — R4.6: main-thread outcome reporting off the storage lock (R2-18)

Short note (R4.9 consolidates docs): `ExploitService` now enqueues every
main-thread null/unknown-intent, foreground-start, wake-lock, admission-dispatch
and destruction-cancellation outcome report on the admission worker instead of
calling the coordinator's lock-taking `noteRequestOutcome` from
`onStartCommand`/`onDestroy`. The queued report is a strict no-op if the service
was destroyed after that report was requested. Status/notification reads still
use only the volatile snapshot. The durable barrier lock scope is unchanged: a
background worker may still block on storage, so this is not a claim that all
paths are nonblocking. Harness tests use blocked harmless storage to verify
main-thread responsiveness and off-main publication.

## 2026-11-25 — R4.3: publication confirmation narrows R2-14

Fourth repair round on `fix/app-correctness-safety` (after `6348c71` / `b8526d8`).
Scope: R2-14 only. No device/ADB, payload, privileged container, APK assembly,
Java suite, loader pin, boot claim, preflight, kill/signal, progress-marker or
rc-contract change. The reproduced early-observation race is closed in code at
the detected-failure level; the abnormal-death instant remains an open
hardware/OS gate and is NOT claimed proved safe (see residual window below).

- **Code fix, not documentation or a passing reproduction.** `run_status_write()`
  now publishes two checked markers in the same root: the intended
  `dfroot_terminal_<operation_id>_rc<code>` and a matching
  `dfroot_confirm_<operation_id>_rc<code>` (magic `DFROOT_CONFIRM 1`, validated
  operation ID and code, unpredictable temp name, `O_EXCL|O_NOFOLLOW`,
  regular-file `fstat`, full write, `fsync`, `fchmod 0644`, checked close, atomic
  `link()`, temp unlink, directory fsync). It returns 0 / its intended exit code
  only when **both** phases complete. Any failure in either phase publishes the
  rc7 channel **before** retracting either final link, cleans temps, restores the
  first diagnostic and returns -1 (writer exits 7).
- **Reader.** `struct run_status_result.publication_confirmed` is set only for a
  definitive non-7 verdict with a matching confirmation (same ID and code).
  An intended marker without confirmation yields NO decision with an explicit
  "not publication-confirmed; completion unverified" diagnostic; rc7 stays
  authoritative without confirmation; two different observed codes (confirmed
  or not) are rc7; foreign/malformed/wrong-magic confirmations cannot confirm a
  run. `run_status_exit_code()`, `run_status_decide()` and the native reader
  (`exp.c`) all refuse to act on an unacknowledged non-7 code and keep polling.
- **Host-only seams.** `RUN_STATUS_FAULT_CONFIRM` fails only the confirmation
  write; all hooks remain behind `RUN_STATUS_TEST_HOOKS`, absent from the
  production `dfroot`/`bootstrap` targets.
- **Tests.** `post_link_early_observation` is now fix verification (N at the
  unconfirmed gate, R after rc7 publication, writer 7). Added
  `confirmed_publication_yields_success`, `confirmation_failure_is_indeterminate`
  and `unconfirmed_or_crossed_markers_never_confirm`. Existing tests were
  strengthened where any publication failure now correctly yields rc7 instead
  of a clean absence; none was deleted.
- **Verification:** `cmake -S app/src/main/jni/host-tests -B
  /mnt/240G_SSD/opencode_tmp/dfroot-hosttests-r4 && cmake --build ... -j && ctest
  --test-dir ... --output-on-failure --no-tests=error` → **140/140 passed, 0
  failed** (was 137), with `-Wall -Wextra -Werror -pedantic` clean. Production
  `app/src/main/jni/CMakeLists.txt` defines no `RUN_STATUS_TEST_HOOKS`.
- **Residual window (OPEN hardware/OS gate, NOT proved safe):** abnormal death
  after the confirmation link but before its fsync, or between intended-marker
  publication and confirmation, can still expose an early observation to an
  external reader. Device crash/power-loss validation of `/dev` marker
  durability remains required. No full Java suite or CI run was performed here.

## 2026-11-25 — Result-contract wording (R4.2, R2-12)

- A **marker conflict** is rc=7 **indeterminate**, no longer rc=1: contradictory
  terminal observations confirm no completed outcome, so a conflicted or
  otherwise unconfirmed result is neither a definitive error nor a success and
  never authorizes another operation. The rc contract values (0/1/2/3/4/6/7),
  `/dev/df` and `/dev/dfm6` progress-only SUCCESS semantics, and the no-root-probe
  policy are unchanged.

## 2026-11-25 — Third repair round (R3) consolidated summary — PR #1 rebase, CI integration, R2-12/13/14/16/17/18/19 + R2-04 caveat

Authoritative record of the third repair round on `fix/app-correctness-safety`
(final local HEAD `1252b3d`). The branch was rebased mid-round onto merged
**PR #1** (`origin/master` `9f60e16`) and then repaired in six phase commits;
all earlier sections below are historical.

**Rebase / CI integration (PR #1 contract retained):**

- `f6faf75` — integrated PR #2 app verification with the merged PR #1 CI
  policy: preserved the release/signing/policy jobs, extended the **closed**
  allowlists for app-checks, loader-pin-fixtures and packaged-pin verification,
  pinned every action, moved the unit-test report check out of the forbidden
  heredoc, and kept fail-closed upload settings.
- `770d07c` — restored the JUnit/Robolectric test-only dependencies dropped by
  the PR #1 app build script and added `buildSrc` to the hermetic signing
  fixture so packaged-loader task definitions resolve.

**Phase commits and finding closure (host evidence only):**

| Finding | Commit | Closure |
|---|---|---|
| R2-12 | `6ebf9e3` | Terminal classifier failures map to rc=7; a single authoritative unverified-outcome predicate stops timeout/interruption flags masking an unknown exit; `RunCoordinator` retains lease/freeze/attempt on every unverified result or engine exception. |
| R2-13 | `2a74dd0` | Incomplete/corrupt persisted attempts fail closed; a store poisoned by a failed checked commit cannot prove durable absence until a post-restart store re-reads durable state. |
| R2-14 | `6012c21` | Post-link publication window stated as provable: pre-link failure leaves no final marker; rc=7 wins once published; only results acted on after publication completes are indeterminate. Deterministic concurrent-reader host test proves an early, unrevocable confirmed observation is possible. **Superseded by the R4.3 confirmation protocol above.** |
| R2-16 / R2-17 | `b87318f` | Boot-OFF is re-read at admission for queued unattended requests (OFF/unreadable fails closed, admitted work untouched); every rejected coordinator submission publishes a fresh snapshot so the Activity shows the real barrier reason and leaves its pending state (pre-submission Boot-OFF suppression is intentionally silent). |
| R2-18 | `d32006e` | Checked attempt-store work moved to a bounded single-thread admission executor (claim-once/close-once); read-only availability diagnostic computed on a separate worker with sequence/generation guards. Boot OFF, durable barrier, single-flight and fail-closed diagnostics remain authoritative. |
| R2-19 + R2-04 caveat | `1252b3d` | In-process recovery requires confirmed engine completion, not just timeout publication; persisted-attempt recovery is a trusted post-process-death acknowledgement with a checked clear and no UI/retry/boot-count proof; final-result messages drop bare `SUCCESS` ("run completed — verify root status yourself" / "already attempted/hooked; root status unverified"). Typed codes, the already-hooked SUCCESS code/no-reentry guard, `/dev/df` and `/dev/dfm6` progress strings, and no-root-probe policy unchanged. Narrowed in R4.9: `engineFinished` proves only the Java call ended — see §R4. |

**Final local verification (non-privileged host only, R3 prior-round
baseline — not a fresh R4 full-suite result):** **554 JVM tests / 45
suites, 0 failures / 0 errors / 0 skipped** at that round; **137 host CTest
cases, 0 failures** then (**140** after R4.3); lint **0 errors / 15 warnings**; workflow policy **67/67**; signing
**40/40**; make **36/36**; `:app:tasks --all` resolves and the clean/tampered
negative loader-pin fixture is green. Pins still match `app/build.gradle.kts`
and `app/src/main/jniLibs`; `git diff --check` clean. No dependency was added or
removed and no bundled loader/binary changed; the documentation, notices and
hygiene edits introduce no runtime behavior change.

**Open release/hardware gates (not claims):** Direct Boot FGS eligibility from
boot; real soft-reboot safety (historical full hard reboot still stands); `/dev`
marker-root trust; sticky-bit `/data/local/tmp` owner (`shell`) limitation; AES
key in argv out of scope; owner-facing recovery UI out of scope (ordinary users
stay locked out behind an unconfirmed persisted attempt); a new `BOOT_COUNT` is
diagnostic only, **not** proof of safe recovery. No device, hardware, ADB,
payload, privileged-container, cold-boot, soft-reboot or APK-assembly validation
was performed. A fresh remote GitHub Actions run after push, with all PR #1
policy jobs plus restored app checks, loader-pin fixtures and packaged-pin
verification green, is the **POST-PUSH gate**; CI green is not claimed locally.

## Unreleased — PR #1 hardening (CI build reliability)

Hardening pass over the build, signing and release pipeline; no change to the
exploit chain or to the GZIF validation claims. Documentation was brought in
line with the shipped configuration (Gradle wrapper **9.9.2** with a pinned
distribution SHA-256; AGP **9.5.0** effective through
`gradle/libs.versions.toml` with no root `build.gradle.kts` pin; Kotlin/Java
sources unchanged).

- **Signing writer and fail-closed behavior** (`scripts/WriteSigningProperties.java`,
  `app/build.gradle.kts`): signing material is serialized safely for arbitrary
  passwords/aliases and locales; `signing.properties` is optional at
  configuration time (a fresh checkout no longer fails `assembleDebug`),
  incomplete properties fail with a readable error, and local keystore/property
  files are created with restrictive permissions and verified after writing.
  Without `signing.properties`, **Release is unsigned** (warning only) and
  **Debug is debug-keystore signed**; an installable Release requires signing.
- **Protected release environment** (`.github/workflows/build.yml`): least
  privilege by default; only a push of a `v*` tag runs `build-release` in the
  `release` environment, where the four `KEYSTORE*` secrets are available.
  PR/branch/manual runs build a **debug-keystore-signed Debug + unsigned
  Release** pair with no secrets, with no release signing on those events, and
  never publish a GitHub Release; their unsigned APKs are uploaded as a public,
  downloadable Actions artifact carrying the same notice/licence bundle.
  Ancestry check precedes any secret reference; the signed APK
  is `apksigner`-verified, checksummed and uploaded with 1-day retention; the
  publish job only downloads, verifies and publishes. Action references are
  hardened/pinned to full 40-hex SHAs and permissions are least-privilege; the
  gates and exact step/run-script allowlists remain enforced by contract tests.
- **New release key identity warning**: releases from this hardened pipeline
  are signed with a **new key identity**. Android will not overwrite-update an
  installed application signed by a different key — uninstall any existing
  DFRoot GZIF build before installing a signed release APK (**unsigned CI
  Release artifacts** from PR/branch/manual builds cannot update a signed
  install either).
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
  `THIRD_PARTY_NOTICES.md` now state the full GPL-3.0-or-later obligations for
  the bundled userspace loaders (correcting the "no other obligations"
  overstatement) while retaining the provenance and complete-source offer.
  Both `GPL-3.0.txt` and the repository's own GPL-2.0 `LICENSE`, plus `NOTICE`
  and `THIRD_PARTY_NOTICES.md`, accompany the signed release assets **and** the
  publicly downloadable unsigned CI APK artifact, with the upload and publish
  allowlists tightened to those exact files.
- Documentation corrections in `README.md`, `PORTING-GZIF.md`, `NOTICE` and
  `THIRD_PARTY_NOTICES.md`: 9.9.2 / 9.5.0-effective, signing/debug behavior,
  CI KO hash, digest-pinned DDK image, environment-only Make credentials,
  single-KMI Make, and the CI event matrix. No AndroidX / Material Components
  dependency (the themes use the framework `android:Theme.Material*`), four KMI
  modules embedded in `libksudnext.so` with GZIF using `android13-5.15`.
  Historical hardware-validation evidence in `PORTING-GZIF.md` is retained.
- Regression coverage: workflow policy/contract tests, make contract tests,
  signing material/permission tests and signed/unsigned release smoke tests all
  pass; new tests assert that the license and notice assets are packaged,
  uploaded and published — including a byte pin for `LICENSE` and
  equality-locked upload/publish allowlists — while still rejecting `secrets/`,
  `.jks`, `signing.properties` and `..` artifact entries.

### Cross-PR integration note

PRs #2–#5 were based on `38eebc3`; before merge they must rebase onto this
hardened head and refresh every shared file they touch. In particular:

- PR #5 changes the LKM bytes, so PR #3's `KO_EXPECTED_SHA256S` and every KO
  hash/size quoted in the docs must be rebuilt and re-pinned against the new
  artifact.
- PR #4 must update its Gradle reference from **9.7.1** to **9.9.2** and refresh
  the referenced PR #1 head to the current hardened commit.

Both licence texts, `NOTICE` and `THIRD_PARTY_NOTICES.md` accompany the signed
release assets **and** the publicly downloadable unsigned CI APK artifact: the
repository is public, so Actions artifacts are not private, and the unsigned
APKs are treated as conveyance with the same notices and source offer rather
than as "internal, not third-party" outputs. The signed release bundle is not
the only unit of conveyance.

## 2026-11-25 — Re-review 3.6 fix (R2-19 recovery truth; R2-04 wording; later committed as `1252b3d`)

Historical phase note on `fix/app-correctness-safety` (HEAD `d32006e`), later
committed as `1252b3d`. Scope: **R2-19** (recovery truth) and the remaining
**R2-04** caveat (ambiguous `SUCCESS` final-result wording). No payload,
device/adb, privileged container, APK assembly, dependency, native code, boot
gate, attempt-store format, UI threading or CI change.

- **R2-19 — recovery is narrow, evidence-based and trusted-caller-only.** There
  is still no blind retry, no UI/intent/broadcast path and no barrier clear based
  on ID/state/acknowledgement alone. `recoverIndeterminate(operationId)` now
  refuses while supervised work may still be running: `engineStarted` is set
  immediately before `engine.run()`, and `engineFinished` is set only in a
  `finally` after that call returns (or throws). A supervisor
  timeout/interruption publishes INDETERMINATE without setting that flag, so a
  correct-ID caller is refused until the engine call actually returns; a proven
  pre-effect rejection (engine never started) remains recoverable. Exact ID,
  single-claim, and checked compare-and-clear still apply, cleanup remains
  exactly once, and the checked clear precedes every release. Deterministic
  tests cover blocked-engine refusal, post-return success, failed persistence,
  and two concurrent correct-ID callers (exactly one wins).
- **R2-19 — persisted recovery is post-process-death only.** The acknowledged
  `recoverPersistedAttempt` API clears only an unconfirmed record loaded at
  initialization, never one published by the current process. It cannot prove
  external effects stopped and is a trusted-caller escape hatch, **not** an
  owner feature. There is **NO in-app recovery UI**: an ordinary user with an
  unconfirmed persisted attempt is intentionally locked out until a trusted
  caller clears the barrier. Resetting app data only discards the barrier and is
  NOT evidence that privileged effects stopped — not safe recovery. A new boot
  count is diagnostic only, not proof of safety. Mock tests cover with/without acknowledgement and
  failed persistence, plus same-process refusal.
- **R2-04 caveat — honest final-result wording.** Generic success is now
  `DFRoot: run completed — verify root status yourself`; already-hooked is
  `DFRoot: already attempted/hooked; root status unverified`. The bare `SUCCESS`
  claim is gone from final-result user messages. Typed result codes/types, the
  already-hooked SUCCESS code, the no-reentry guard, `/dev/df` and `/dev/dfm6`
  progress strings, and the no-root-probe policy are strictly unchanged.

Fresh local verification at that time (historical prior-round baseline):
**554 JVM unit tests across 45 suites** (`:app:testDebugUnitTest`,
0 failures/errors/skips), `:app:lintDebug` **0 errors / 15 warnings**, host CTest
recorded then at **135/135** (superseded by the consolidated R3 section above:
**137/137**, then R4.3: **140/140**), and `git diff --check` clean. No
hardware/device/ADB/payload/privileged-container/APK-assembly validation was
performed; CI remains a POST-PUSH gate. This phase was later committed as
`1252b3d`.

## 2026-11-25 — Re-review 2 fix (R2-14 post-link observation window; historical, superseded by R4.3 `916a18e`)

**Historical within this branch:** this round only documented the post-link
window; the R4.3 section at the top of this changelog supersedes it with a code
fix (matching confirmation required before any definitive result).

Historical phase on `fix/app-correctness-safety` (then HEAD `2a74dd0`), later
superseded in code by R4.3 (`916a18e`). Scope: **R2-14 only** — the post-link observation
window. No payload, device/adb, privileged-container, APK assembly, dependency,
attempt-store, Java, boot or UI changes; JVM tests are untouched. The temp
write / fsync / close / `link()` publication protocol and atomic conflict
detection are unchanged, and the safety policy (rc=7 precedence, indeterminate
ownership) is not weakened.

- **Aligned guarantee (was stronger than provable).** A **pre-link** failure
  leaves no final marker and no debris; once the rc=7 channel is published it
  **outranks** success and conflicts in the classifier. But `link()` makes the
  complete final pathname visible before temp unlink and directory fsync can
  succeed, so an external reader polling in the brief post-link/pre-rc7 window
  can observe the intended marker as a confirmed terminal verdict, and that
  observation cannot be retroactively revoked by the later rc=7 publication and
  retraction. The app therefore does **not** claim that a failed publication is
  never observable as confirmed; it claims only that any result it acts on
  **after publication completes** is indeterminate (writer rc=7, reader rc=7).
  Stated in `run_status.h`, `README.md`, `PORTING-GZIF.md` and this changelog.
- **Host-only hook.** `run_status_set_post_link_pause(wait_fd, notify_fd)` under
  `RUN_STATUS_TEST_HOOKS` gates the writer immediately after `link()` succeeds
  and before temp unlink, directory fsync and failure detection — the earliest
  instant the final path is observable. All seam state is compiled out of the
  production `dfroot`/`bootstrap`/`exp` binaries.
- **Deterministic reader test.** New `run_status.post_link_early_observation`
  case proves (over explicit pipe handshakes, with no sleeps or allocation luck)
  that a concurrent reader classifies the visible intended rc0 marker as a
  confirmed verdict in the early window; then a one-shot temp-unlink failure is
  injected, rc7 publication is observed before retraction while rc7 outranks
  rc0, the reader sees rc7 only afterwards, the writer exits rc7, no temp debris
  remains, and the first unlink diagnostic survives. The existing
  `post_link_gated_reader` coexistence test and every prior assertion/test are
  retained unchanged. Fresh CTest count: **137/137** (136 + 1).

## 2026-11-25 — Re-review 1 repair (R2-10..R2-16; locally committed, not pushed)

Second 2026-11-25 repair round on `fix/app-correctness-safety`, commits
`6686973` → `a8003c2` (HEAD `a8003c2`), on top of the first repair round
recorded below. `versionCode`/`versionName` remain 408 / `4.8-gzif`. No runtime
payload, firmware target, bundled binary, dependency, or SUCCESS/root-probe
policy was widened. The tracking-comment IDs below are mapped by subject area to
the repair commits (the authoritative finding text lives on the GitHub PR).

| Finding | Subject closed by this round | Commit |
|---|---|---|
| R2-10 | packaged loader pin gate broken / no negative fixture | `6686973` |
| R2-11 | staged-entry cleanup identity and exec pin | `639a9d8` |
| R2-14 | atomic terminal publication and indeterminate channel | `12a633b` |
| R2-12 | indeterminate completion must keep ownership (partial R2-03) | `ce781f8` |
| R2-13 | unconfirmed attempt not durable across process death (partial R2-02) | `7b5397c` |
| R2-15 | pre-admission uncertainty + independently disableable Start at Boot (partial R2-07/09) | `7c1f39a`, `54cb000` |
| R2-16 | already-hooked wording and availability honesty (partial R2-04) | `a8003c2` |

Phase-by-phase (each phase is one commit, with the **exact locally recorded
counts** at that point — these are intermediate, not final totals):

- **Re-review Phase 1 — Gradle pin gate (`6686973`, R2-10).** Moved pin
  verification out of the build script into Gradle-supported `buildSrc` tasks
  (`LoaderPinVerifier`, `VerifyPackagedLoaderPinsTask`,
  `VerifyLoaderPinFixturesTask`), fixing the non-static-inner-class failure that
  broke `:app:tasks --all`. Strict two-pin, one-APK, packaged-entry SHA-256
  checks retained; deterministic clean/tampered synthetic ZIP fixtures guarded
  the verifier itself; CI gained the `loader-pin-fixtures` job and a
  `:app:tasks --all` regression step. Locally: `:app:tasks --all` resolves,
  debug/release pin tasks configure, clean fixture passes and tampered fixture
  is rejected. No full-suite count was recorded in this commit; a fresh GitHub
  CI green run is a **POST-PUSH gate** that cannot be executed locally and is
  **not** claimed here.
- **Re-review Phase 2 — staging cleanup identity (`639a9d8`, R2-11).** Pinned
  the created staged inode with a descriptor; `fstat`/`lstat` identity recheck
  before unlink, conservative no-unlink of replacements, directory-mode restore
  and diagnostic retained. Converted the `O_WRONLY` pin through
  `/proc/self/fd/<n>` to an `O_RDONLY` pin before `install()` returns so the
  loader can be exec'd without `ETXTBSY`; unavailable conversion fails closed.
  Added exec/replacement/churn/unrelated-entry fixtures. **117/117 local CTest
  cases and 115/115 CI-like cases**, 0 failures.
- **Re-review Phase 3 — atomic terminal publication (`12a633b`, R2-14).** Full
  payload written to an unpredictable same-directory temp (`O_EXCL|O_NOFOLLOW`,
  EINTR-safe full write, fsync, fchmod 0644, checked close) then atomically
  linked to the run-scoped final path, temp unlinked and directory fsynced; a
  **pre-link** failure cannot leave a final marker (the post-link observation
  window is scoped by the Re-review 2 fix above). Added writer rc=7
  for unconfirmed publication and Java `NativeResultMapper` → `RESULT_INDETERMINATE`
  only. Conflict detection, operation-ID binding, progress-only `/dev/dfm*`
  markers, and soft-reboot rc=4/6 preserved. **123/123 host CTest cases** and
  **26 `NativeResultMapperTest` cases**.
- **Re-review Phase 4 — ownership when unconfirmed (`ce781f8`, R2-12; partial
  R2-03).** Bounded late-load/soft-reboot waits without killing children or
  lengthening deadlines; timeout or unobserved exit is rc=7. Reader deadline
  expiry is rc=7 within the single shared late-load budget.
  `RunCoordinator` retains lease, frozen configuration, and single-flight
  rejection on indeterminate results and exits only via a single-claim explicit
  recovery API. **131/131 CTest cases, 428 JVM tests, lint 0 errors.**
- **Re-review Phase 5 — durable attempt barrier (`7b5397c`, R2-13; partial
  R2-02).** New `OperationAttempt` / `OperationAttemptStore` /
  `SharedPreferencesOperationAttemptStore` boundary persists an unconfirmed
  attempt in device-protected storage before privileged work or `run_config`
  publication; a new process rejects manual and boot requests fail-closed until
  explicit recovery regardless of boot count, and boot claims are suppressed
  while unconfirmed. Clearing is compare-and-clear by operation ID. **459 JVM
  tests, 131 CTest cases, lint 0 errors.**
- **Re-review Phase 6 — pre-admission uncertainty (`7c1f39a`, R2-15; partial
  R2-07).** Pure reader decision helper plus narrowly scoped reserved-identity
  diagnostic scan: a valid pre-admission rc=1 marker with no admitted terminal
  marker yields rc=7 with an explicit "completion unverified" diagnostic;
  admitted markers (rc=0 success included) remain authoritative and no stale or
  foreign marker can become success. **135/135 CTest cases**; Java result
  mapping and all prior SUCCESS/soft-reboot semantics unchanged.
- **Re-review Phase 7 — independent Start-at-Boot OFF (`54cb000`, R2-15;
  partial R2-09).** OFF is reachable from ERROR configuration and
  active/indeterminate operations without disturbing the current operation;
  ON keeps the verified-config, idle-coordinator, and consent gates. The
  receiver component is re-checked at claim time (unreadable/default fails
  closed), and component-setting failures surface in the UI. **471 JVM tests,
  135 CTest cases, lint 0 errors.**
- **Re-review Phase 8 — honest hook result and availability (`a8003c2`, R2-16;
  partial R2-04; wording refined in R3.6 above).** Already-hooked success was
  relabelled to drop its root-verification claim, with the SUCCESS code and
  no-reentry guard preserved; a read-only availability diagnostic is
  built from the same authoritative preflight plan in its own view and never
  starts, stages, or substitutes anything. **484 JVM tests across 44 suites,
  135 fresh host CTest cases, lint 0 errors (21 warnings).**

Final local verification for this round: **484 JVM tests (44 suites), 135 host
CTest cases**, lint **0 errors / 21 warnings**, `git diff --check` clean, loader
SHA-256 pins match `app/build.gradle.kts` and `app/src/main/jniLibs`, zero
secret-pattern hits (benign `printf '%s'` placeholder in `Makefile` only), and
no generated/build/KO/secret files tracked. **No hardware, device, ADB, payload,
privileged-container, cold-boot, soft-reboot, or APK-assembly validation was
performed.** A fresh GitHub CI green run, including the negative pin fixture,
is a **POST-PUSH gate** that cannot be executed locally and is **not** claimed
here. Per-file dated notices were added only where missing; no existing notice
was removed. The documentation/notices/hygiene-only changes of this historical
phase were later committed as `01f120c` (docs: record the re-review repair round
and release gates) and `13162d4` (chore: finalize R2 re-review repair round)
(see R3 consolidated note).

## 2026-11-25 — PR #2 correctness/safety repair, Phases 0–5 (locally committed, not yet pushed)

Architecture repairs on top of `4.8-gzif`; `versionCode`/`versionName` are
unchanged (408 / `4.8-gzif`). No runtime payload, firmware target, or SUCCESS
product decision was widened. Summary by phase:

- **Phase 0 — build/test foundation.** Release signing made optional (an absent
  `signing.properties` yields an unsigned release that still configures; a
  present but incomplete file fails clearly). Pinned JUnit/Robolectric test
  dependencies and Android unit-test resources added. Independent CI app-checks
  job runs unit tests and lint without secrets, a device, or the gitignored KO;
  `--no-tests=error` prevents an empty suite from passing. `app/src/main/res/xml`
  added to the Makefile APK inputs. Loader SHA-256 pins are owned once by Gradle.
- **Phase 1 — side-effect-free preflight and result contract.** Canonical
  `SuManager` allowlist (`me.weishu.kernelsu` → `libksud.so`,
  `com.rifsxd.ksunext` → `libksudnext.so`) with per-manager pinned bundled
  loaders and **no implicit fallback** to the manager APK. SHA-256 pin, regular
  non-symlink, owner-user, log-buffer, and exact firmware/kernel gates all run
  before staging, IpSec allocation, or process start. Typed `RunResult` mapping
  with collision-free unknown handling; rc=1 is broad bootstrap failure; SUCCESS
  keeps owner semantics but carries `LATE_LOAD_COMPLETED` internal detail. No
  root probe was added.
- **Phase 2 — durable configuration and soft-reboot consent.** Serialized
  off-main-thread `ConfigurationRepository` with checked `commit()` and
  saving/verified/error states; failed or unverified writes block run admission.
  Immutable `RunConfig`; versioned soft-reboot acknowledgement with legacy
  enabled → safe OFF migration and a durable modal; unattended boot soft reboot
  remains disabled.
- **Phase 3 — operation ownership.** Process-scoped single-flight `RunCoordinator`
  rejects duplicates and freezes configuration; Direct Boot-aware `specialUse`
  `ExploitService` owns process lifetime, notification, and a timed partial
  WakeLock and returns `START_NOT_STICKY`. The Activity is detach-only on destroy;
  a supervised child drains stdout asynchronously, uses a timed wait, and keeps
  indeterminate outcomes owned until actual exit (no forced kill, no retry).
  Manager/settings controls are locked while work is active.
- **Phase 4 — native snapshot, completion, staging.** Java publishes an atomic,
  versioned `run_config` artifact; native bootstrap parses only that
  artifact. Run-scoped terminal markers (`rc=0` success, `rc=1` error,
  `rc=4` soft-reboot success, `rc=6` soft-reboot failure) are written only after
  bootstrap work completes; `/dev/dfm6` stays an owner-visible **progress**
  diagnostic. Late-load wait is bounded (60 s; timeout → rc=1), soft-reboot
  command wait is bounded (30 s; failure/timeout → rc=6). `/data/local/tmp/.ksud-stage`
  staging uses sticky-bit protection, `O_EXCL|O_NOFOLLOW`, verified descriptor
  copy/fsync/fchmod, and guaranteed directory-mode restoration.
- **Phase 5 — boot integration.** `BootReceiver` validates the broadcast and
  durably claims `Settings.Global.BOOT_COUNT` in device-protected storage before
  starting the foreground service; locked/unlocked broadcasts coalesce, so at
  most **one attempt per boot** and no silent replay after process death. Boot
  soft reboot stays disabled; boot FGS eligibility is not hardware-validated.

Files/areas changed: `app/src/main/java/df/root/*` (new coordinator, service,
configuration repository/store, run-config artifact, preflight, result mapping,
boot-claim path, narrow Java seams), `app/src/main/jni/*` (`run_config_codec`,
`run_status`, `loader_stage`, `bootstrap.c`, `exp.c`, CMake wiring),
`app/src/main/jni/host-tests/*`, `app/src/test/java/df/root/*`,
`app/src/main/AndroidManifest.xml`, `app/src/main/res/{xml,values,drawable}/*`,
`app/build.gradle.kts`, `.github/workflows/build.yml`, `Makefile`, and
`tasks/{plan,todo}.md`. Every modified GPL-covered source file carries a dated
per-file notice (`NOTICE`).

Validation (2026-11-25, non-privileged host only): **409 JVM tests** (0
failures/errors/skips), **113 host CTest** cases (0 failures; 111 base cases plus
2 cleanup fixtures registered only when the mode probes fail and the fixture
roots are relocated — 113 is the count in this environment), `lintDebug` **0
errors** (21 warnings present before the Phase 6 docs-only edits, one a
`PrivateApi` warning at `AndroidRunEnvironment.java:93` in a file added by PR #2
— not all 21 predate PR #2), `git diff --check` clean, loader pins match
the bundled binaries, zero secret-pattern hits, and no generated/build/KO files
tracked. **No hardware, device, ADB, payload, privileged-container, cold-boot,
soft-reboot, or APK-assembly validation was performed for this revision.** APK
assembly still requires the gitignored `app/src/main/jni/ko/dfroot-android13-5.15.ko`
(built via podman + DDK or fetched from CI). The 2026-11-17 device runs below are
historical evidence only, not validation of this architecture.

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
