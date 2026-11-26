# PORTING-GZIF.md — DFRoot (CVE-2026-43284) port to Galaxy S23 Ultra SM-S9180 / S9180ZHU8GZIF

**Date written:** 2026-11-17 · **Author:** subagent for the GZIF port effort · **Scope:** documentation only — this file is the single artifact it produces. No code, scripts, logs, or other files are created or modified by this document.
**Repository:** published at `samson910022/DFRoot-GZIF` — a fork of [diabl0w/DFRoot](https://github.com/diabl0w/DFRoot) @ `7a6513a` ("Fall back to libbase when libc++ hook selection fails (#185)", 2026-11-06), branch `master`.
**Campaign record (device-validation doc):** `samson910022/Root-My-Galaxy-Payloads-Next` → `docs/SM-S9180-S9180ZHU8GZIF.md`
**Log archive (host-side, never committed):** `GZIF-port/` (run logs, dropbox dumps, forensic reports — see §11 "Evidence locator").

> [!IMPORTANT]
> This document describes an **experimental, volatile, per-boot root exploit** for a shipping Samsung firmware. It is for the device owner's own research. It is not a distribution, not stable, and not persistent across reboot. Read §10 (Safety & limitations) before using anything in it.
> "It is not a distribution" refers to this technique note. Non-tag CI uploads
> **no APK**: the only public upload is the `DFRoot-CI-evidence` notice/licence
> bundle (`GPL-3.0.txt`, `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md`). A
> locally built APK is still subject to the release gates below; the signing
> smoke emits only an isolated `SigningSmoke-*.apk` that is never distributed.

> [!WARNING]
> **Literal head is not independently device-verified as a whole (scoped
> author-local restore- and final-status-observability retests passed; P1 gates
> OPEN).** The **literal head is the committed `e823fb4`**
> (`fix(native): publish final status via app-readable property`, D2 native
> property channel + round-13 docs in ONE commit); its immediate parent
> `d39a278` is historical. PR #3 base is
> `9f60e16251f5856ee1ae720eaea84b41d7992920`, CI merge SHA
> `69054f5be1a278d88c19164444a7590e3c5bc2c0`. Round-12 heads (`39a376f` docs, `376f5e2` code), the reverted relabel
> attempt `0f9bf69` and its revert `d39a278` are historical; the P2-15 fix code
> head is `7e18b13`, the Phase B / P2-14 code head is `0ea022f` and the
> round-8 reviewed head is `36a11bc` — none of these is the literal head. **Round-10
> scope (not retroactively changed):** consolidated review comment
> **7126155178** at `74b12df` reported **0 new findings, 0 P2 open and 3 P1
> open (P1-02 / P1-07 / P1-08)**, left the head unchanged and completed CI run
> **45710535038** with `success`. **P2-15 was NOT found by that review**, nor
> were P2-16 and P2-17 (all author-reported, pending independent validation);
> P2-15 was found by an author-local release build (non-tag CI builds Debug only
> and the tagged release job was unexecuted). **Independent-review accounting
> (R11, comment 7126155178): 29 IDs, 23 closed, 4 open (3 P1 + 1 P2) and 2
> author-reported addressed / pending independent validation.** Author changelog
> rounds 11–13 are **not** independent review rounds R1–R11. The code-head increments were Phase A
> `0832331` (`fix(cmake): invalidate loader evidence before argument
> validation`) and Phase B `0ea022f` (`fix(ci): verify loader bytes inside
> every distributable APK`). Producer `2b59684` and loader integration
> `413a5cb` are earlier/mid-increment points. The D2 code base —
> single nonce-bound final outcome in the **non-persistent Android property
> `dfroot.final`** (root/bootstrap sets, app reads; `u:object_r:default_prop:s0`;
> unprivileged `setprop` denied; **`/dev/dffinal` is no longer produced**),
> app-owned bootfail/recovery channels, write-ahead preference transaction,
> lifecycle-safe listeners, persistent `UNKNOWN` boot-state UI, KO hash policy,
> build provenance and packaging gates — is **host-tested except for the scoped
> author-local device retests** recorded in §12 rows 31–33 and the
> round-12/round-13 notes above; the PR as a whole is not independently
> device-verified. CI status re-checked before finalizing this ledger: run
> **45708004129** at `0ea022f` **completed with conclusion `success`**
> (host-only, not device validation); runs **45706111735** (`0832331`),
> **45702366070** (`36a11bc`) and **45697296267** (`bb83849`) also completed
> `success` but are **earlier heads**. **Exact-head CI for committed `e823fb4`
> is run 45864085866** (`pull_request` event, head `e823fb4`, **completed
> `success`**), host-only: Fast CI (92 policy, 36 Make, 40 signing), Host
> evidence gates (61 status, 13 proc, 28 ELF, 40 loader-stage, six CMake
> 23/25/3/7/6/16, 7/7 markers), CI DFRoot evidence gates, LKM 1/1, JUnit
> 39/0/0/0 — all pass; helper-size 30 is author-reported (CI prints pass, not
> a count). **Signed release APK and Publish GitHub Release jobs SKIPPED; no
> DFRoot APK uploaded.** CI does **not** verify any device result. No
> completed-green CI conclusion is claimed for the earlier heads `7e18b13` or
> `376f5e2`. Counts below are confirmed by the Phase B code-head log; no exact
> literal-head count is asserted from an earlier log. The hardware
> results in §5 and §12 (run6/run8, 2026-11-17) and all earlier CI runs were
> obtained with the **historical implementation** — the old multi-marker
> protocol, then-current KO pins and the old preference-write path — and must
> not be read as validation of the current head. An
> **unconditional** `host-gates` job (no `if`, checkout-SHA assertion) runs
> **all** host suites as required gates on **every** event, including protected
> `v*` tag pushes; both `build-dfroot` and `build-release` require it and
> `publish` requires `build-release`. Non-tag events upload **no APK** — only
> the `DFRoot-CI-evidence` licence/notice bundle; the Debug APK is a
> compile/content gate. On the protected `v*` tag path only, before any signed
> upload or publish, every distributable APK is packaged-byte-verified with a
> per-APK manifest and `so_sha256` binding, every provenance record is
> identity-checked, and release/distributable evidence is asserted at a single
> upload choke point. Provenance is informational evidence, **not** an
> attestation or independent reproducible-build proof. **The byte gate rejects
> UNKNOWN bytes but accepts the two known obsolete pins**; release is withheld
> by **POLICY** (reviewed re-pin, OPEN P1-08, device validation), not by the
> hash gate alone — passing the gate is not device validation. No tag was
> created by us. Gradle passes the Android variant (`release` / `debug`) as the
> authoritative native policy signal, and the KO is linked into a dedicated
> `.dfroot_ko` section whose final bytes in `libdfroot.so` are SHA-256-checked
> against the same pins (no skip option); a failed re-check removes the stale
> verified copy. CI asserts 7/7 host suite markers on every event and a strict
> JUnit XML assertion of **39 tests**, 0 failures/errors/skips, over 61 status
> cases, **28 ELF**, **40 loader-stage** cases and the helper-size suite, six
> CMake suites at **23/25/3/7/6/16** cases (KO policy / packaged-KO / APK
> packaging / APK content **7** / target graph / loader hash **16**), JVM 39
> tests (4/10/12/13) and a workflow-policy suite of **92 tests** (was 90 at
> rounds 8–10 and 85 earlier; P2-15 regression added at `7e18b13`).
> Every distributable APK on the tag path also has both bundled loader entries
> extracted and checked for SHA-256 + size against the CMakeLists.txt pins by
> the real verifier (Phase B gate), with a required nonempty per-APK loader
> index at the pre-upload choke; release markers stay **3/3** and host markers
> **7/7**. This is a host/CI gate, **not** device validation. Non-tag CI
> uploads no DFRoot app APK — only the `DFRoot-CI-evidence` bundle; the
> intermediate `df-lkm-${kmi}` KO artifact **is** uploaded separately by
> `build-lkms` on every event and is not a DFRoot app APK or a release claim.
> `dfrecover` is
> bound to the current run nonce (a stale or foreign `ok` is rejected);
> bootfail token syntax is not invocation proof, and absence of evidence is not
> proof that system activity stopped. Deadline checks are host-proven ordering
> only; device quiescence and verified restoration are UNVALIDATED. Auto Soft
> Reboot and Start on Boot remain disabled by default and unvalidated. P1-08
> remains **OPEN**: both loaders were rebuilt from the patched source to consume
> a root-owned `/data/adb/.ksud-stage` and the DFRoot producer at `2b59684`
> stages to that exact path, but this is pending re-review and fresh on-device
> full-chain validation (see round 8 in `CHANGELOG.md`). **P1-02 and P1-07
> also remain OPEN** safety/release gates; **P2-13 and P2-14 were addressed in
> this round (Phase A/B) after the round-8 review** and are **pending final
> comprehensive review**, not claimed finally closed. Staging descriptions
> below that mention `/data/local/tmp/.ksud-stage` describe the **historical**
> run4/run6/run8 loaders, not the rebuilt binaries now bundled. A successful
> build does not certify safe privileged execution. No device run validates the
> current head; no tag was created and no release was published. See §19 for
> the test/lifecycle/failure-path shape.

> [!NOTE]
> **Post-rebase integration.** PR #1 (CI build reliability) is now merged into
> the base and PR #3 was rebased onto it; run6/run8 and all prior CI runs remain
> historical and do not validate the rebased head.

> [!NOTE]
> **Round 8 / P1-08 integration (2026-11-26).** The bundled loaders are no
> longer the verbatim payloads-repository artifacts. Both were rebuilt from the
> patched source on payloads branch `fix/p1-08-root-owned-staging` (commit
> `891e897`) to consume a root-owned stage at `/data/adb/.ksud-stage` (fd
> validated for owner/mode/non-empty content before promotion to
> `/data/adb/ksud`); DFRoot's producer at `2b59684` stages to that exact path.
> Host source-build evidence: CI run **45674540939** (classic pair,
> `android13-5.15`) and **45674545588** (Next generic, four KMIs). Run
> 45674540939 **failed overall** — only its successful `pair ksud` job produced
> the integrated classic artifact; its separate generic-ksud job failed for a
> missing no-patch-text module. Run 45674545588 succeeded overall. Current
> bundled bytes, enforced by `verify_loader_hashes.cmake` (sha256 **and**
> size, with no skip option):
>
> | Artifact | sha256 | size |
> |---|---|---|
> | `libksud.so` | `731c3fa1f83fe698ec0b749413065b25c12d4d433b2aed6c3ba7152fdfbefee4` (`731c3fa1…`) | 5,127,584 B |
> | `libksudnext.so` | `7f28f1fb5d3238f606330945b2694df140fea97527507c865f411db704ec2414` (`7f28f1fb…`) | 4,728,320 B |
>
> **P1-08 remains OPEN pending re-review and fresh on-device full-chain
> validation** — these rebuilds are host-attested only, not device-verified.
> run6/run8 and all older runs are historical and validate only the old
> loaders. Auto Soft Reboot and Start on Boot stay disabled and unvalidated.

> [!NOTE]
> **Round 11 / P2-15 (2026-11-26) — release-JNI byte preservation (host-only).**
> The round-11 head was `7e18b13`; its immediate parent was `74b12df`. The
> round-10 consolidated review (comment **7126155178**, head `74b12df`) had
> **0 new findings, 0 P2 open, 3 P1 open** and CI **45710535038** `success`;
> it did **not** find P2-15 (author-local release build did; non-tag CI is
> Debug-only and the release job was unexecuted). Round-10 totals are unchanged.
>
> **Defect:** AGP 9.5 strips debug symbols from bundled release JNI libraries
> by default.
>
> | Artifact | Reviewed / pinned | Release APK from `74b12df` (before) | Release rebuild at `7e18b13` (after, local test key) |
> |---|---|---|---|
> | `libksud.so` | `731c3fa1…` — 5,127,584 B | `ecac0d2b1e5930bf69d887eaf4d9fbdc630da524457cb3025d1166d9ac37b7f5` — 3,737,888 B | `731c3fa1f83fe698ec0b749413065b25c12d4d433b2aed6c3ba7152fdfbefee4` — 5,127,584 B |
> | `libksudnext.so` | `7f28f1fb…` — 4,728,320 B | `ea0505cd25692f83de17fe2c3a2fcab237050128f84dcd0b68cf9f3705284f40` — 4,728,320 B | `7f28f1fb5d3238f606330945b2694df140fea97527507c865f411db704ec2414` — 4,728,320 B |
>
> The second loader's defective size is **identical** to the reviewed size;
> only the SHA-256 catches it. **Fix (Phase 1, `7e18b13`):**
> `app/build.gradle.kts` `packaging.jniLibs` sets
> `keepDebugSymbols += "**/libksud.so"` and
> `keepDebugSymbols += "**/libksudnext.so"`; a regression helper with positive
> and mutation tests was added in `tests/workflow/test_policy.py` (policy
> **90 → 92**). No pin/workflow/CMake/native/marker-count change.
>
> **Host verification** (evidence at
> `/mnt/240G_SSD/opencode_tmp/p2-15-evidence`): clean
> `:app:clean :app:assembleRelease` with a **disposable local test key**
> (`/home/samson1357924/dfroot-device-test.jks`; KO sha256
> `94f8be987a2f8bdc13539e42a5c3cb4c0f606024b8b31601496c5d5e5c0025a7`)
> produced `DFRoot_4.8-gzif.apk` sha256
> `33c3742dc777f82a9e8f0b61ca1054b198ba9c75ec22b3503125cd184005fec9`;
> both loader entries occur exactly once and extracted bytes are
> `731c3fa1…` / 5,127,584 B and `7f28f1fb…` / 4,728,320 B. The real
> `verify_loader_hashes.cmake` manifest status is `verified`, and the verbatim
> P2-14 gate script from `build.yml` (`Verify bundled loader bytes in every
> distributable APK`) passed with the pins file and one index row (APK name +
> APK sha + both loader shas). **Limits:** host-only packaging evidence —
> **not a signed release** (local test key, not official signing material), no
> tag, no upload, no publish, no device validation; the release job remains
> unexecuted and no completed-green CI conclusion is claimed for `7e18b13`.
> P2-15 is **addressed pending final comprehensive review**; P1-02 / P1-07 /
> P1-08 stay OPEN and P2-13 / P2-14 are unchanged.

> [!NOTE]
> **Round 12 / P2-16 (2026-11-26) — cleanup-time helper re-apply (device retest
> passed for the restore scope; rc=2 timeout / unbound observation separate and
> OPEN).** The current code head is
> `376f5e277713c1008386624f85ef74f200f14f2c` (the literal head is this
> documentation update; immediate parent
> `7a855c51c8c48e4882a0c715164898ad0bcfd272`); the code head's immediate parent
> is `0f0a2ad` (the pre-fix device-run head), with P2-15 fix head `7e18b13`,
> Phase B / P2-14 head `0ea022f` and round-8 reviewed head `36a11bc`. Round-10
> totals (0 new / 0 P2 / 3 P1) and round-11 totals are **not retroactively
> changed**.
>
> Two S23 attempts on 2026-11-26 at the pre-fix head `0f0a2ad` (evidence at
> `/mnt/240G_SSD/opencode_tmp/s23-final/`) are **attempts, not validations**:
>
> | Observation | Run 1 (`logcat.txt`, `root-probes.txt`, `post-reboot.txt`) | Run 2 (`logcat2.txt`, `app-result2.txt`, `root-probes2.txt`, `post-reboot2.txt`) |
> |---|---|---|
> | Root chain | full chain succeeded | full chain succeeded |
> | Oracle | `su -v` = `3.4.2:KernelSU`; `su -c id` = `uid=0(root) … context=u:r:ksu:s0`; SELinux **Enforcing**; KSU Next daemon + modules loaded | same |
> | Panic evidence | none | none |
> | Vendor restore | `read_vendor_splice at 0x0: got 0 bytes, status=0x0, errno=0`; `restore FAILED for /vendor/lib64/AIAwbModel_v2.2.tflite` | identical |
> | App result / reboot | rc **66** = 2 + recovery bit 64; clean reboot | rc 66; clean reboot |
>
> Decisive timeline (`logcat2.txt`): **19:58:38** helper patch applied
> (`patch: crash_dump64 <- splicehelper (1408 bytes)`, `patched 1408 bytes …`;
> vendor write also succeeded at 19:58:39) — writes/capture worked; **19:58:40**
> bootstrap set partitions ro / started the SU daemon; **19:59:49** cleanup
> logged `F crashdump64: crash_dump.cpp:278] wrong number of args: 3 (expected
> 6)` immediately before the 0-byte read, then `cleanup failure: setting
> recovery-failed bit (64) on rc 2`. **Conclusion:** the page-cache-only
> splicehelper patch was gone by cleanup so the **original** `crash_dump64`
> ran. The **exact discard mechanism is NOT proven** — do not overclaim a
> specific cause.
>
> **Fix (`376f5e2`): host-tested implementation, device-retested for the restore scope.** A pure `df_helper_block_matches()` predicate
> above `DF_HELPER_TEST_ONLY`, and `ensure_helper_patch()` called at the start
> of `cleanup()` before helper-backed restores — re-applying the same patched
> helper with the same verification, no second restore record, fail-closed;
> helper-size host suite **7 → 30 cases**, all other counts unchanged (policy
> 92; CMake 23/25/3/7/6/16; native 61 + `final_publish`, proc 13, ELF 28,
> version 16+14+10, loader-stage 40; JVM 39; markers 7/7 + 3/3). **Device
> retest (S23, 2026-11-26, pass for the P2-16 restore scope):** APK sha256
> `bd8a17836effcac027b719b61bff0abfea4b309289e47c4d6d9732947e0bcbd9`, installed
> with a **disposable local test key** (no tag/publish); clean preconditions
> (`pre3.txt`: Enforcing, no `su`/`/dev/df`/`/dev/dffinal`/`ksud`, empty
> `persist.log.tag`, vendor 529568 B mtime 2022-01-01); 15:20:48Z run with full
> root chain (`3.4.2:KernelSU`, `uid=0(root) … u:r:ksu:s0`, Enforcing).
> `logcat3.txt`: 23:21:59.947 `cleanup: crash_dump64 helper patch missing;
> re-applying`, 23:21:59.956 `cleanup: crash_dump64 helper patch re-established
> (verified)`, 23:21:59.958/.959 libbase `restore OK (verified)`, 23:22:01.570
> `restore OK (verified) for /vendor/lib64/AIAwbModel_v2.2.tflite`, 23:22:01.578
> `restore OK (verified) for /apex/com.android.runtime/bin/crash_dump64`.
> Counts: `wrong number of args` 0, `got 0 bytes` 0, `restore FAILED` 0,
> `recovery-failed bit` 0, `helper patch re-established` 1, vendor `restore OK`
> 1 — **no bit 64**, so P2-16's restore defect is **closed on device** for this
> scope. **Separate and still OPEN (also in the two pre-fix attempts, not
> caused by P2-16):** `status: timeout waiting for final status (rc=2)` and
> `cleanup: unbound observation; recovery gate stays closed`, `/dev/dffinal`
> (historical channel, **no longer produced** under D2) absent. Post-reboot 15:23:08Z (`post-reboot3.txt`): Enforcing, uptime 62.73,
> no `su`, all checked `/dev/df*` + `ksud` absent, vendor size/mtime unchanged; no panic
> evidence (pstore empty). **Limits:** the PR as a whole is **not
> device-verified, not merge-ready and not release-ready**; no tag or publish.
> P1-02 / P1-07 / P1-08 remain OPEN — P1-07 restore verification is now
> device-confirmed but quiescence and failure-interruption remain unproven. See
> `CHANGELOG.md` round 12.

> [!NOTE]
> **Round 13 / P2-17 (2026-11-27) — final status moved to `dfroot.final`
> (scoped device retest PASSED).** The final token was published on time with
> the correct nonce but `/dev/dffinal` carried `u:object_r:device:s0`, so the
> app domain could not observe it and runs ended rc=2 unbound observation
> (`selinux-diag5.txt`, `poll-root-final5.txt`, `app-result6.txt`). A
> `fsetxattr` relabel attempt (`0f9bf69`, copying `dfinv`'s `app_data_file`
> label) was denied, never created `/dev/dffinal`, and its bootfail fallback
> also failed (`label-diag6.txt`, `faildiag6.txt`); it was reverted at
> `d39a278`. A zero-code pre-flight ruled out a file in the `/data` root
> (`p2-17-preflight/d1-probe.txt`: root `Permission denied`). **D2 (committed at
> literal head `e823fb4`, parent `d39a278` historical):** the single
> authoritative final status is the **non-persistent Android property
> `dfroot.final`** — root/bootstrap sets it, the app reads it, context
> `u:object_r:default_prop:s0`, unprivileged `setprop` denied
> (`channel-probe7*.txt`); canonical token, nonce binding, strict parse and
> fail-closed publication are unchanged. **`/dev/dffinal` is no longer
> produced**; device diagnostics use `dfroot.final`.
>
> **Earlier pre-commit author-local device retest (S23, scoped PASS; working
> tree on `d39a278`):** APK sha256
> `a7f6e02ebb44cb39f583ca3c32d1b818ad046be3019ce540ee4bf9be2ec001af`,
> **disposable local test key** (no tag/publish): `app-result8b.txt` shows
> `final: ROOT_ONLY - no reboot requested; root not verified` then
> `status: final status observed (rc=0)`, no unbound observation, all four
> restores `restore OK (verified)` with no bit 64 (P2-16 scope still green);
> `root-probes8b.txt` shows `prop=[dfroot-v2:3fec15b2d9ad766ff5173d857f75bc38:root_only]`,
> `3.4.2:KernelSU`, `uid=0(root) … u:r:ksu:s0`, `selinux=Enforcing`. Evidence:
> `/mnt/240G_SSD/opencode_tmp/s23-final/` (`app-result8b.txt`,
> `root-probes8b.txt`, `logcat8.txt`, `post-reboot8.txt`, `channel-probe7*.txt`).
> `post-reboot8.txt` records the device not reconnecting after reboot, so no
> post-reboot claim is made.
>
> **Second author-local full run at committed head `e823fb4` (after the R11
> snapshot):** APK sha256
> `f866b4388f9f4c9e365dbf2936a9b076eab14b6ad2f2b4e9ea3e642080758807`
> (**disposable local test key**, no tag/publish); evidence
> `/mnt/240G_SSD/opencode_tmp/s23-final/` (`pre9.txt`, `app-result9.txt`,
> `root-probes9.txt`, `root-stability9.txt`, `logcat9.txt`, `apk9-sha256.txt`):
> `pre9.txt` clean after reconnection (Enforcing, no `su`, no `/dev/df`, no
> `/dev/dffinal`, no `ksud`, `dfroot.final` empty, vendor 529568 B mtime
> 2022-01-01); `app-result9.txt` helper patch re-established, four restores
> `restore OK (verified)`, no bit 64, `final: ROOT_ONLY - no reboot requested;
> root not verified` then `status: final status observed (rc=0)`;
> `root-probes9.txt` nonce `fdeb49b6704ae77bdd4648bbabfb9d69`,
> `3.4.2:KernelSU` / `u:r:ksu:s0`, Enforcing; `root-stability9.txt` after 30 s
> root/property unchanged, `ksud`=0, pstore empty. **SCOPED AUTHOR-LOCAL
> EVIDENCE, NOT independent validation**; no reboot was requested, so there is
> no post-reboot evidence, and this does not supersede the `post-reboot8.txt`
> caveat above.
>
> Counts unchanged (policy 92; helper-size 30 author-reported; CMake
> 23/25/3/7/6/16; native 61 + `final_publish`, proc 13, ELF 28, version
> 16+14+10, loader-stage 40; JVM 39; markers 7/7 + 3/3); `final_publish` gains
> an unnumbered pure truth-table test. **Exact-head CI for `e823fb4` is run
> 45864085866** (completed `success`, host-only, release/publish SKIPPED, no
> DFRoot APK uploaded). **Limits:** the rc=0 results confirm **final-status
> observability only** — not the full chain, quiescence, loaders or release
> readiness, and not independent validation. **P1-02 / P1-07 / P1-08 remain
> OPEN**; the PR as a whole is **not independently device-verified, not
> merge-ready and not release-ready**; no tag/publish, disposable local test
> key. See `CHANGELOG.md` round 13 and §12 row 33.

> [!NOTE]
> **Document status (2026-11-25).** §5, §11, §12 and §14 are the original
> **2026-11-17 historical hardware-validation ledger**. They are preserved for
> provenance and describe the device runs that motivated the port; they are
> **not** validation of the current PR #2 architecture. §8.3 is the original
> **2026-11-17 install/run recipe**, retained with its run6/run8 root oracle;
> where it described the old manager-APK `libksud.so` spinner check and the
> `/dev/dfm6` marker as verification, those steps have been corrected below to
> match the current code. §4/§6/§8.4/§9/§10 have been updated to match the
> current code, **§15** documents the first 2026-11-25 repair architecture and
> residual risks, and **§16** documents the re-review 1 repair round and the
> hardware gates that remain open. **§17** records the R2-19/R2-04 recovery and
> wording phase; **§18** is the authoritative consolidated record of the third
> repair round (rebase onto PR #1, CI integration, R2-12/13/14/16/17/18/19 and
> the R2-04 caveat, with current counts and gates); **§19** records the fourth
> repair round and **§20** is a short dated note on the sixth repair round's
> host-side bounded UI work; **§21** is a short dated note on the seventh repair
> round's host-only app correctness work. No device or privileged validation was
> done for any 2026-11-25/2026-11-26 repair revision.

---

## 1. TL;DR

- The working root on this firmware is **DirtyFrag (CVE-2026-43284) + the DFRoot chain**, with a **Samsung-patched, no-patch-text, kretprobe-fallback KernelSU / KernelSU-Next** module late-loaded at runtime.
- **CVE-2026-43499 is patched** in GZIF (binary-verified) — do not port it. **CVE-2026-98260 is present but unported** (no 5.15/dm3q work exists). **CVE-2026-43284 is open** and is the live route.
- Stock vanilla KernelSU classic v3.3.0 (`me.weishu.kernelsu`) and stock vanilla KernelSU-Next v3.4.2 (`com.rifsxd.ksunext`) **kernel-panicked this device** inside/around `init_module()` on 2026-11-17 (two hardware runs). The panics were the stock-vs-Samsung RKP/KDP/DEFEX class of failure.
- The port works because DFRoot-fzg1 now **bundles and prefers two Samsung-patched Samsung loaders** from the companion payloads repo instead of stock ones:
  - `app/src/main/jniLibs/arm64-v8a/libksud.so` — classic FZG1-validated Samsung-patched ksud **3.3.0**, P1-08 rebuilt (sha256 `731c3fa1…`, 5,127,584 B),
  - `app/src/main/jniLibs/arm64-v8a/libksudnext.so` — Next **3.4.2** generic Samsung-patched ksud, P1-08 rebuilt (sha256 `7f28f1fb…`, 4,728,320 B).
- End-to-end hardware validation was achieved **twice** on the same firmware build on 2026-11-17: **run6** (16:49, classic loader, `ksud start: SUCCESS`, `su -c id` → `uid=0(root) … context=u:r:ksu:s0`, SELinux **Enforcing**, `su -v` = `3.3.0:KernelSU`) and **run8** (17:53, Next loader, identical verification with `su -v` = `3.4.2:KernelSU`).
- Root is **volatile** (RAM-only). Every reboot wipes it. **Start on Boot / Auto Soft Reboot are not validated — do not enable them on this model.**

---

## 2. Target identity (verified)

| Field | Value |
|---|---|
| Model | **SM-S9180** (`dm3q`, `dm3qzhx`; SoC Snapdragon 8 Gen 2, platform `kalama`, board=kalama) |
| Four-part PDA | `S9180ZHU8GZIF/S9180OZS8GZIF/S9180ZCU8GZIF/S9180ZHU8GZIF` |
| Display build | `CP2A.260605.016.S9180ZHU8GZIF` (build-ID family matches `S9210ZHU6EZIF`) |
| `ro.build.changelist` | `34344539` (SYSTEM/build.prop) — **but the kernel banner/common changelist is `34343818`**; the value "34343818" that matters for KMI/module work is the kernel.common changelist, which is also what "changelist 34343818" in the campaign notes refers to |
| Android / SDK | **17 / SDK 37** (`ro.build.version.oneui=90000`, `sep=180000` → One UI **9.0**) |
| Security patch level | **2026-10-05** |
| Fingerprint | `samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys` |
| Kernel release | **`5.15.197-android13-8-34343818-abS9180ZHU8GZIF`** (`#1 SMP PREEMPT Mon Sep 28 06:37:13 UTC 2026`) |
| Kernel compiler | Android clang **14.0.7** (r450784e, LLD 14.0.7) |
| Kernel size | `0x2cb0a00` (46,860,800 B) — byte-identical size to the FZF5/FZG1 images |
| Boot image | header v4, kernel at `0x1000`; boot.img 100,663,296 B; SHA-256 `6df858ed…` |
| `boot.img.lz4` SHA-256 / kernel SHA-256 | `c3590ac3…` / `d8eb9556…` |
| KMI / module deps | `android13-5.15` (2-part KMI); `CONFIG_INET_ESP=y`, `CONFIG_INET6_ESP=y` (ESP built-in), `# CONFIG_USER_NS is not set`, `CONFIG_XFRM_USER=y`, `CONFIG_IKCONFIG_PROC=y` |
| Bootloader / Radio | `S9180ZHU8GZIF` / `S9180ZCU8GZIF` (Radio shown in KP-flagged boot dumps as `S9180ZCU8GZIF,S9180ZCU8GZIF`) |
| Vendor image | Android 13 (`TP1A.220624.014/S9180ZHU8GZIF`, SDK 33) |
| Device | Galaxy S23 Ultra (Taiwan/BRI four-part build; `OZS` multi-CSC; TGY Hong Kong shares the same PDA) |

Host-observed running kernel (device-attached checks on 2026-11-17): `uname -a` = `5.15.197-android13-8-34343818-abS9180ZHU8GZIF #1 SMP PREEMPT Mon Sep 28 06:37:13 UTC 2026`; SELinux **Enforcing**; shell `u:r:shell:s0`; `/proc/config.gz` **byte-identical** to the config extracted from the kernel Image (`cmp` clean).

---

## 3. CVE disposition (binary-verified against this exact kernel)

All three candidate CVEs were triaged against the extracted GZIF kernel Image (see campaign doc §3 for raw addresses):

| CVE | Bug | Verdict on GZIF | Notes |
|---|---|---|---|
| **CVE-2026-43499** | "GhostLock" rtmutex/futex-PI UAF (`remove_waiter` sign-gate) | **PATCHED** | Both upstream fix hunks binary-confirmed present (`remove_waiter()` @ `0xffffffc0096f7474`; `rt_mutex_start_proxy_lock()` @ `0xffffffc0096f9464`; Samsung integrated `3bfdc63936dd` + `40a25d59e85b` by GZIF). Listed **High** in SMR-Oct-2026. Do not port. |
| **CVE-2026-98260** | POSIX-CPU-timer exec-path UAF ("timer payload") | **OPEN (bug present)** | `begin_new_exec()` @ `0xffffffc0084cabd4` decodes to the unpatched 5.15.197 ordering (`posix_cpu_timers_exit()` NOT moved before `unshare_files`/`exec_mmap`). `fs/exec.c` identical between v5.15.197 and v5.15.212; fix only reached 6.1.171/6.6.158 (2026-10-03) — **no 5.15 fix exists anywhere**. **Never ported to dm3q/5.15** (pa3q/6.6-only); listed in **neither** SMR-Oct-2026 nor SMR-Nov-2026. |
| **CVE-2026-43284** | "DirtyFrag" — xfrm ESP in-place CBC decrypt page-cache write | **OPEN (route-gated) — the winning route** | `esp6_input()` @ `0xffffffc00954c888` **and** `esp_input()` (esp4) @ `0xffffffc009489c20` both implement the unpatched fast path verbatim (no `skb_has_shared_frag()`/`SKBFL_SHARED_FRAG` test before `skip_cow`). `CONFIG_INET_ESP=y`/`CONFIG_INET6_ESP=y` built-in; **`# CONFIG_USER_NS is not set`** (desktop userns route dead at kernel level); app-reachable route = **Android `IpSecManager` transport-mode ESP-in-UDP** (netd installs the SA as root; untrusted apps need no permission). Listed **High** in SMR-Nov-2026 (published 2026-11-03) — expect it to close in a later SMR. |

**Sanity facts from the campaign** (`docs/SM-S9180-S9180ZHU8GZIF.md` §6.5): every gate for the IpSecManager route was cleared on the real unit — `C1-SPI-OK`, `C2-ENCAP-OK`, `C3-TRANSFORM-OK`, `C4-APPLY-OK`. The xfrm/SELinux model checks out; the esp4 path is fully app-reachable.

---

## 4. The full chain — as ported in `DFRoot-fzg1`

Notation: **(stage N)** = pipeline position. "App" = `df.root` (DFRoot GZIF APK). Sources below are the upstream DFRoot files with the local port applied.

**(0) Preconditions / staging.** App is launched → user presses **LAUNCH ROOT**. `ExploitRunner.run()` now first calls `PreferenceIntegrity.init(ctx)` and the pure `earlyExitRc(devDfExists, prefsQuarantined)`: `/dev/df` existing yields **rc 9** (`DF_RC_ALREADY_HOOKED`, "already hooked ... launch skipped" — never rc 0), and a quarantined SharedPreferences state yields **rc 8** (`RC_PREF_STATE_UNCERTAIN`, launch blocked); `/dev/df` wins when both hold (phase 3, host-tested only). It then prints device info (Model/Manufacturer/Android/SPL/Kernel) and refuses if Developer Options → "Log buffer size" is Off. The SU manager selected in the spinner is validated (`hasKsud()` `:40-43` — the *manager APK* must ship `nativeLibraryDir/libksud.so`; this is only an eligibility gate, not the loader that runs — see §6), then `stageAssets()` (`:123-135`) copies the **bundled Samsung-patched loader** (see §6, patch 1) to `/data/user_de/0/df.root/ksud` and `libbootstrap.so` to `/data/user_de/0/df.root/bootstrap`.

**(1) IpSecManager ESP transform.** The app opens a `UdpEncapsulationSocket` + `SecurityParameterIndex` via `android.net.IpSecManager`, builds an **AES-CBC / HMAC-SHA256 transport-mode `IpSecTransform`** (`ExploitRunner.java:77-98`). No app permission needed; netd (root, SELinux-allowed) installs the IPv4 ESP-in-UDP SA. The app then execs `libdfroot.so` (the native payload, disguised as a JNI lib — AGP only packages `lib*.so` — and exec'd from `nativeLibraryDir`, which is executable; `runBinary()` `:137`, `ProcessBuilder` + `redirectErrorStream(true)`).

**(2) crash_dump64 splicehelper bridge.** `crash_dump64` (`/apex/com.android.runtime/bin/crash_dump64`) is a privileged binary that runs in the `u:r:crash_dump` SELinux domain via exec-label transition — a domain `untrusted_app` cannot reach directly. The exploit patches it **in the page cache** with `splicehelper` (a freestanding static binary, built on the fly by CMake; see `app/src/main/jni/CMakeLists.txt`). The patch mechanism: AES-CBC in-place decrypt — `IV = AES_ECB_DEC(key, current_content) ⊕ desired_content` — so the kernel's ESP decrypt rewrites the page-cache bytes to the desired shellcode (`exp.c:34-53`). splicehelper has two modes: **splice mode** (pipe a 16-byte page chunk out to the parent, used for writing the `.ko` and patching `crash_dump64`/`libc++.so`) and **read mode** (`argv[3]="r"`, dumps 16 bytes of file content to a pipe for IV computation, `exp.c:189-206`). The vendor file bridge exists because `crash_dump` can open `vendor_file`-labeled files but app-untrusted_app cannot.

**(3) dfroot.ko → vendor file.** The **android13-5.15** LKM `app/src/main/jni/ko/dfroot-android13-5.15.ko` (DDK-built; two distinct fingerprints — the **CI/release artifact is 13,384 B**, sha256 `9dfb8b71b306f5a38fd8b35b283d4dab8b4eb25a08ad97ca0f4ef705b6151c68`, because CI strips `.note.gnu.build-id` with `llvm-objcopy --strip-unneeded`, while the **local `make` build is 13,504 B**, sha256 `94f8be98…`, retaining `.note.gnu.build-id`; they are not byte-identical — vermagic `5.15.202-android13-5.15.202_r00-dirty`, modversions satisfied via KMI CRC) is written via the crash_dump bridge (splicehelper splice mode) into **`/vendor/lib64/AIAwbModel_v2.2.tflite`** — a vendor asset that is never executed by the system. File presence is proven by the splice patch; size and `vendor_file` label are unverified (no retained `stat`/`ls -Z` evidence). (Original upstream candidates `libbinderdebug.so` / `libstagefrighthw.so` / `libstagefright_aidl_bufferpool2.so` are **absent** on this build; the local port hard-codes the tflite in `exp.c:443` and `libcxx.S:22`.) The exploit targets GZIF's `KMI=android13-5.15` (`Using KMI: android13-5.15`).

**(4) libc++.so sentry hook → init tid=1.** The exploit patches `/system/lib64/libc++.so` at `std::ostream::sentry::sentry()` (symbol `_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentryC1ERS3_`): **trampoline @ `+0xb4ae0`**, **shellcode @ `+0x10faa8`**, hook site `0xb4ae8`; a BTI/PAC preamble at the hook site was detected and the landing pad advanced (+4) — all printed in-run (`run8_logcat.txt:7777-7782`). The `createOrphanProcess()` double-fork makes the grandchild an orphan; **init (PID 1) reaps it**, and init's main thread (**tid=1**) then calls through the hooked function. The shellcode (`libcxx.S`):
- verifies `getuid()==0 && gettid()==1`, returns otherwise;
- creates **`/dev/df`** (`O_CREAT|O_EXCL`) as a one-shot per-boot mutex — **this is the `/dev/df` the app polls**;
- clones a worker child; the worker clones a grandchild;
- grandchild writes `u:r:vendor_modprobe:s0` to `/proc/self/attr/exec`, then `execve("/vendor/bin/insmod", ["insmod", "/vendor/lib64/AIAwbModel_v2.2.tflite", NULL])`. (`/vendor/bin/insmod` is a toybox applet → `vendor_toolbox_exec` → transitions into the **`vendor_modprobe`** domain.)

**(5) dfroot.ko init (vendor_modprobe, uid=0) — `lkm/dfroot.c`.** On `init_module()`:
- resolves `kallsyms_lookup_name` via a kprobe; resolves `aarch64_insn_patch_text`, `kern_path`, `invalidate_inode_pages2`, `path_put`, `avc_has_perm`, `avc_has_perm_noaudit`, `task_defex_enforce`, `task_defex_user_exec` (and Oppo/OnePlus `oplus_*` when present);
- **invalidates the page cache of `crash_dump64`** (`invalidate_inode_pages2`, `dfroot.c:84-96`) so the next exec sees the patched file;
- **`WRITE_ONCE(selinux_state, false)`** — flips the global SELinux state to Permissive (`dfroot.c:98-104`); if the flag didn't take (kernels with `SECURITY_SELINUX_DEVELOP=n`), it instead patches `avc_has_perm[_noaudit]` to `return 0` (`BTI C` + `MOV x0,#0` + `RET`, `dfroot.c:106-126`);
- **Samsung DEFEX neutralization**: patches `task_defex_enforce` and `task_defex_user_exec` to return 0 the same way (`dfroot.c:144-161`) — this is the DEFEX half; the RKP/KDP halves for the **KSU module's own behavior** live in the Samsung-patched `kernelsu.ko`, not in dfroot.ko;
- runs **`call_usermodehelper_setup`/`exec`** (`UMH_WAIT_EXEC`) for `/system/bin/sh -c 'touch /dev/dfm0; echo -n u:r:init:s0 > /proc/self/attr/exec; exec /data/user_de/0/df.root/bootstrap'` (`dfroot.c:52-61,162-178`);
- **returns `-ENODEV` on purpose** ("intentionally fail init to unload immediately; avoids triggering mrdump module-loaded callbacks on MTK devices", `dfroot.c:186-188`).

**(6) bootstrap.c — `app/src/main/jni/bootstrap.c`.** Kernel UMH runs the shell: `/dev/dfm0` touched → exec context set to `u:r:init:s0` → `exec /data/user_de/0/df.root/bootstrap`. The kernel runs this with **stdio → /dev/null** (umh setup stdio pointers are NULL), so all diagnostics go to the `/dev/dfm*` marker files, not to the app. Bootstrap:
- `touch /dev/dfm1`; reads prefs `/data/user_de/0/df.root/shared_prefs/dfroot.xml` (`su_manager`, `soft_reboot`, `disable_modules`);
- **`adopt_zygote_env()`** — clones zygote64's `environ` (SELinux/label propagation for later children);
- **`set_partitions_ro()`** — `BLKROSET=1` on `super`, `misc`, `steady`, and any `*_a`/`*_b` block device under `/dev/block/by-name`;
- if `disable_modules` pref set: creates `/data/adb/modules/*/disable` for every installed module (bootloop recovery);
- **(local port patch 2, see §6) `mkdir("/data/adb", 0700)` and securely stages `ksud`** with `unlink` + `open(O_CREAT|O_EXCL|O_NOFOLLOW, 0700)` + full-write loop + `fchmod`/`fsync` (replaces the old `system("cp ...")`, blocking symlink/TOCTOU races). **Historical (run4/run6/run8) loaders:** the stage was `/data/local/tmp/.ksud-stage`, baked into those binaries' self-staging constant. **Phase B code head (`0ea022f`; producer `2b59684`, loader integration `413a5cb`):** the rebuilt loaders consume `/data/adb/.ksud-stage` inside root-owned `/data/adb` (0700) and validate it by fd before promotion; bootstrap stages to that exact path. Then execs the staged daemon's **`late-load --package-name <pkg>`** as uid 0, `u:r:init:s0`, Seccomp: 0;
- `exp.c` first writes a fresh 128-bit run nonce to
  `/data/user_de/0/df.root/files/dfinv` (atomic 0644 replace + fsync); bootstrap
  strictly reads it at startup. A missing/malformed nonce is a bootstrap
  failure before any run identity exists: it publishes `BOOTSTRAP_FAILED` with
  the RESERVED zero nonce on the separate app-owned `dfbootfail` channel
  (observable rc=1, not rc=2), and skips late-load and any reboot. Every normal
  final token is bound to the run nonce:
  `dfroot-v2:<32-hex nonce>:<outcome>\n`.
- on **exit 0** → the pure producer publishes one atomically replaced final
  token in the non-persistent Android property `dfroot.final` (root/bootstrap
  sets it, the app reads it; **`/dev/dffinal` is no longer produced**):
  `ROOT_ONLY` when Auto Soft Reboot is off (terminal
  immediately: it means ONLY "no reboot requested"); with it on, a 60 s
  monotonic-clock wait for `late-load` to disappear (unknown, timeout or an
  absence confirmed after the deadline → `REBOOT_SKIPPED`, **no reboot**), a
  successful `REBOOT_PENDING` preflight publication, then a bounded
  `WNOHANG`/kill/reap child wait for `ksud soft-reboot` refining the token to
  `REBOOT_REQUESTED` (exit 0) or `REBOOT_FAILED` (otherwise). A failed
  refinement publication publishes `REBOOT_ATTEMPT_UNKNOWN` — never a fallback
  to `ROOT_ONLY`. Any nonzero `late-load` exit → `ERROR`, no reboot.
  A bootstrap failure that happens before a run identity exists is published
  on the separate **app-owned** `dfbootfail` channel (§17) with exactly the
  reserved zero nonce and the `BOOTSTRAP_FAILED` outcome — observable rc=1,
  never rc=2. The consumer reads it only when no normal final token parsed.
  The app-owned `dfrecover` status is strict (`ok\n` only) **and bound to the
  current run nonce**: a stale or foreign `ok` is rejected, so token syntax is
  not invocation proof. A reboot requires a confirmed-absent late-load, a
  successful `REBOOT_PENDING` preflight AND a positive recovery gate, polled at
  most 5 s and clamped to the remaining deadline. The deadline is enforced
  before and after a positive result and after cleanup before any terminal
  outcome; a NULL/failed gate, a timeout or late evidence publishes
  `REBOOT_SKIPPED`.

  `exp.c` awaits this single file with a **70 s budget**; a `REBOOT_PENDING`
  observation opens a 10 s grace window clamped ONLY to the **85 s overall
  deadline** (`min(observation + 10 s, start + 85 s)`), so a refinement can
  arrive after the budget. An unrefined pending is reported as
  `REBOOT_ATTEMPT_UNKNOWN`. The patched files are released exactly once,
  after the first valid bound status observation (or once on the shared deadline
   if none arrives), charged to that SAME deadline with the clock rechecked;
   a failed restore sets the additive 64 recovery-failed
  bit on every app rc. Device timing/quiescence remain UNVALIDATED.

**(7) Samsung-patched ksud late-load.** The bundled loader (classic FZG1 v3.3.0 — log tag **`KernelSU`**; or Next generic 3.4.2 — log tag **`KernelSU Next`**):
- logs `ksud::cli: command: LateLoad {… package_name: "…"}` (`ksud::late_load: late-load command triggered!` … `Detected KMI: android13-5.15` — **2-part KMI always**, verified);
- **stages itself before the module load** — historical loaders (run4/run6/run8) called `utils::stage_daemon_from("/data/local/tmp/.ksud-stage")`: `ensure_dir_exists(/data/adb)` + `fs::rename(...)` + `chown root:root` + `chmod 0755`. **Rebuilt loaders (current head)** stage from the root-owned `/data/adb/.ksud-stage`, fd-validating owner/mode/non-empty content before promoting to `/data/adb/ksud`; the DFRoot producer feeds exactly this contract, but it is not device-validated;
- `ksuinit::has_kernelsu()` → false on fresh boot; `ksuinit::load_module()` binds undefined symbols from `/proc/kallsyms` (transient `kptr_restrict=1` write), reads `/dev/kmsg` to the end (`ksuinit: Reading kernel log from /dev/kmsg`), **`Replaced module vermagic with kernel-required value`** (vermagic mismatch rewrite → retry → `init_module(&buffer, params)`);
- **The module is the Samsung-patched, no-patch-text, kretprobe-fallback KSU build** (`CONFIG_KSU_SAMSUNG_KDP/RKP/DEFEX=y`, `CONFIG_KSU_SAMSUNG_NO_PATCH_TEXT=y`): it **never writes the syscall table** (dispatcher slot detected protected → kprobe/IP-redirection hooks on `execve`/`execveat`/`newfstatat`/`faccessat`, plus a **kretprobe on `setresuid`**), `ksu_patch_text()` returns `-EOPNOTSUPP` (fail-closed — log line `"live patching disabled on Samsung EL2"`), SELinux-hide goes through kprobes, **KDP** creds are installed via a workqueue → `prepare_ro_creds(SAMSUNG_KDP_COPY_CREDS=0)` + `kdp_assign_pgd` (kdp usecounts via `kdp_usecount_dec_and_test`), **DEFEX** cred shadow is re-synced after every escape, and `task_defex_enforce` is kprobe-zeroed for KSU-domain root tasks. Lineage: `kernelsu/patches/KernelSU-Next-v3.4.2-samsung-kdp-rkp-defex.patch` / `KernelSU-v3.3.0-samsung-kdp-rkp-defex.patch` in the payloads repo (identical strategy to diabl0w's `samsung-v2.2` compat tree — `kernel/compat/{kdp,rkp,defex}.c`). See `samsung-lateload-forensics.md` and `next-loader-codepath.md` for the reverse-engineering;
- loads `android13-5.15_kernelsu.ko` (embedded asset — see §7) → **`kernelsu.ko loaded successfully!`**, context now `u:r:ksu:s0`; creates/uses the KSU driver fd via the `reboot(2)` supercall doorbell (arm64 syscall **142**, `x0=0xDEADBEEF x1=0xCAFEBABE`); applies SELinux/sulog/kernel_umount feature configs from `/data/adb` (sepolicy rules for pre-installed KSU modules get loaded here too);
- su apps can then use `su` → shell/`u:r:ksu:s0`, uid 0.

**(8) Cleanup.** The app restores the `libc++.so` trampoline/shellcode after the hook fires (`=== cleanup ===` in every successful log) and closes the IpSec SA/sockets.

**Final-status map** (single atomically published file, replaced by the new
protocol — `status_protocol.{c,h}` + `bootstrap.c` / `exp.c`):

All tokens have the form `dfroot-v2:<32-hex nonce>:<outcome>\n`. A token with a
stale/foreign nonce, an unknown outcome, embedded NUL or extra bytes is ignored
by the consumer. A reserved-nonce `BOOTSTRAP_FAILED` token on the separate
app-owned `dfbootfail` channel is read only when no normal token parsed and maps
to rc=1; `dfrecover` is a strict, nonce-bound app-owned recovery status (stale
or foreign `ok` rejected). App rc 9
(`DF_RC_ALREADY_HOOKED`) is the Java-side `/dev/df` gate (the current head
returns 9 instead of 0); rc 8 (`RC_PREF_STATE_UNCERTAIN`) is the Java-side
SharedPreferences quarantine gate; rc 7 covers `reboot_pending` /
`reboot_attempt_unknown`. 64 is a **recovery-failed bit** OR'ed into any rc below
when a restore could not be verified; the Java `StatusRc.name()` surfaces that
warning for every code and never claims verified root or a confirmed reboot.

| `dfroot.final` property token (root sets, app reads; `/dev/dffinal` no longer produced) | App rc | Meaning |
|---|---|---|
| `root_only` | 0 | late-load exited 0, no reboot requested; **root not verified** (terminal) |
| `error` | 1 | bootstrap failed at/after late-load or secure staging; no reboot |
| `bootstrap_failed` on `dfbootfail` (reserved zero nonce) | 1 | bootstrap failed before a run identity existed (e.g. missing/malformed nonce); separate app-owned channel, **not** `dfroot.final`; no reboot |
| *(absent / no valid token)* | 2 | unknown — check logcat & dmesg |
| `reboot_requested` | 4 | soft-reboot command exited 0; **reboot not confirmed** |
| `reboot_skipped` | 5 | soft reboot not attempted (late-load state unknown or deadline expired) |
| `reboot_failed` | 6 | soft-reboot command failed or timed out |
| `reboot_pending` | 7 | soft reboot requested; non-terminal preflight (unknown until refined) |
| `reboot_attempt_unknown` | 7 | attempt result unknown (refinement publish failed or grace expired) |

Progress markers (`/dev/df`, `/dev/dfm0`–`dfm5`, `/dev/dfmw0`/`dfmw1`,
`/dev/dfme0`/`dfme1`) are still touched for diagnostics. `exp.c` observes
`/dev/df`–`dfm5` and `dfmw0`/`dfmw1` **informationally only**; `dfme0`/`dfme1`
are bootstrap-only failure diagnostics, and the authoritative result is always
the final token. They are never an oracle: the old terminal
markers `/dev/dfmsr`, `/dev/dfm6`, `/dev/dfmw2`, `/dev/dfmw3` and `/dev/dfme2`
are no longer published or polled. The old `dfmsr_ok = touch(...)` check was
inverted (`touch()` returns 0 on success), which made a *successful* root
publication report failure; it is removed entirely.

> [!NOTE]
> **"root_only" only means `.ksud-stage late-load` exited 0. It does NOT prove
> `kernelsu.ko` finished loading or that root is usable.** In runs 2 and 3 the
> app printed SUCCESS *before* the module's `init_module()` line even appeared
> in logcat, and the kernel died immediately afterwards. The only trustworthy
> oracles are `su -c id` / `su -v` / `getenforce` via adb, or
> `cat /proc/modules | grep kernelsu`.

---

## 5. Proven on hardware — run ledger (2026-11-17)

> [!NOTE]
> This ledger is historical: it describes the implementation and KO pins that
> existed on 2026-11-17, not the current head. Nothing below validates the
> round-4 changes on `fix/native-exploit-robustness`; those are host-tested only.

All times local. "KP" = kernel panic (abnormal reset; dumped as XBL/ABL-only `SYSTEM_LAST_KMSG_*_KP` in dropbox; **no kernel text / panic string is ever captured on this device** — full stops).

| Run | Time | Manager selected | Loader actually staged (bundled) | Outcome | Evidence |
|---|---|---|---|---|---|
| run1 | ~15:23 | `me.weishu.kernelsu` | stock classic (manager APK's libksud.so, pre-bundling) | **Kernel panic** (KP191; unlogged, unattributed) | `manager-compat-analysis.md:59`; dropbox KP-flagged resets |
| run2 | 15:34 | `me.weishu.kernelsu` | stock KernelSU classic v3.3.0 (vanilla) | **panic inside/around `init_module()`** (KP194). DFRoot printed SUCCESS at 15:34:23.150 (`run2_logcat.txt:1679`); `Loading kernelsu.ko …` first appears only afterwards (`:1688`); device machine-stopped ~199 ms later. **Succeeds at nothing.** | `run2_logcat.txt:1673–1851`; `manager-compat-analysis.md` §6–7; `kmsg_195.txt` (sibling KP195) |
| run3 | 15:49 | `com.rifsxd.ksunext` | stock KernelSU-Next v3.4.2 (vanilla) | **panic inside/around `init_module()`** (KP195) — identical locus; manager exonerated as cause | `run3_logcat.txt:1529–1705`; `manager-compat-analysis.md` §6.3 (manager-independence table) |
| run4 | 16:29 | `com.rifsxd.ksunext` | bundled classic FZG1 loader (`aab76943…`) | **`Error: Failed to stage ksud`** — `Failed to rename /data/local/tmp/.ksud-stage to /data/adb/ksud: No such file or directory`; DFRoot printed `ksud start: ERROR - ksud nonzero exit` (`run4_logcat.txt:30782-30788`). No panic (device stayed up). Root cause: bootstrap never created `/data/adb` nor staged `.ksud-stage` → the Samsung loader's mandatory staging step failed. **Fixed by local port patch 2.** | `run4_logcat.txt:30303–30793` |
| run5 | 16:32 | — | — | no exploit run (app launched only; zero exploit lines in capture) | `run5_logcat.txt` |
| **run6** | **16:49** | `com.rifsxd.ksunext` | bundled classic **FZG1 Samsung-patched** loader | **FULL ROOT SUCCESS.** `kernelsu.ko loaded successfully!` 16:49:50.856 (`run6_logcat.txt:26590`); auto-load of KSU modules (sepolicy) at :26694–:26978; `ksud start: SUCCESS` 16:49:51.175 (:26980). On-device: `su -c id` → `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`; `su -v` → `3.3.0:KernelSU`; `getenforce` → **Enforcing** (`docs/SM-S9180-S9180ZHU8GZIF.md` §6.7). `.ksud-stage` renamed to `/data/adb/ksud`. | `run6_logcat.txt:25747–26980`, `:45621` (shell marker probe); campaign doc §6.7 |
| 17:00 run | 17:00 | `com.rifsxd.ksunext` | bundled **Next FZF5-pair** loader 3.4.1 (`f23ede9b…`) — the "shipped-broken" artifact | **`ksud nonzero exit` in ~52 ms, no panic, no module load.** Markers: `/dev/dfm5` yes, `/dev/dfme2` yes, `/dev/dfm6` −; device state `SELinux Permissive` (exploit ran). Root cause: the FZF5 pair embeds **only** `android13-5.15.189_kernelsu.ko`; the runtime KMI key is **`android13-5.15`** → asset lookup miss → loader exits 1 before any kernel interaction. A *packaging* defect, not a firmware rejection. | `next-failure-evidence.txt:3-16,19-58`; `next-loader-codepath.md`; `next-failure-analysis.md` |
| **run8** | **17:53** | `com.rifsxd.ksunext` | bundled **generic Next 3.4.2 Samsung-patched** loader (`679504dd…`) — swapped in at 17:50 | **FULL ROOT SUCCESS.** `ksud::late_load: kernelsu.ko loaded successfully!` 17:53:23.051 (`run8_logcat.txt:9739`); `u:r:ksu:s0`; modules' sepolicy loaded (`:9747–:9871`); `ksud start: SUCCESS` 17:53:23.342 (:9881); device alive (logcat continues); cleanup restored libc++ (:9883–:9887). On-device: `su -c id` → uid 0; `su -v` → **`3.4.2:KernelSU`**; `getenforce` → **Enforcing** (campaign doc §6.8). One non-fatal feature error: `Failed to set feature 5: ksuctl failed: Operation not supported on transport endpoint (os error 95)` (`:9850`). | `run8_logcat.txt:3509–3515,3715–3830,7772–9887`; campaign doc §6.8 |

### 5.1 What "two independent runs, both managers" means — precisely

- Two manager APKs are installed on the unit and both are selectable in DFRoot's spinner (each ships `nativeLibraryDir/libksud.so`, satisfying `hasKsud()`): **`me.weishu.kernelsu`** (classic) and **`com.rifsxd.ksunext`** (KernelSU-Next).
- The hardware-validated end-to-end chains were both run with **`com.rifsxd.ksunext` selected** (run6 = bundled classic loader; run8 = bundled generic-Next loader). Selecting `me.weishu.kernelsu` would stage the **same** bundled classic loader (`stageAssets()` prefers the bundled one when present — patch 1), differing only in the `--package-name` argument; the classic manager itself is userspace-broken on this build regardless (see §9.5), and that path has **not** been separately exercised.
- Two KSU **lineages** have now been validated end-to-end on this firmware: the Samsung-patched **classic KernelSU 3.3.0** (FZG1-validated build, run6) and the Samsung-patched **KernelSU-Next 3.4.2** (generic build embeds four 2-part KMIs incl. `android13-5.15`, run8).
- Failure runs run2/run3 prove the **stock vanilla** modules of *both* managers panic in the same place — i.e., the manager ABI is not the variable; the module's Samsung accommodation is.

### 5.2 What failed before (and why)

1. **Stock vanilla KernelSU classic v3.3.0 (`me.weishu.kernelsu`)** — panic at/around `init_module()` for the stock `android13-5.15_kernelsu.ko` (run2, KP194). Best-fit mechanism (never proven — panic text unrecoverable): the stock module's syscall-table `stop_machine` fixmap write and/or `selinux_write_op[]` functable patches vs **RKP** (hypervisor rodata protection), plus KDP-creds/DEFEX mismatch. See `manager-compat-analysis.md` §6.1/§6.3/§6.6, `samsung-lateload-forensics.md` §6.1.
2. **Stock vanilla KernelSU-Next v3.4.2 (`com.rifsxd.ksunext`)** — identical panic, same window (run3, KP195). Established manager-independence: both runs died *after* `Loading kernelsu.ko …` / `ksuinit: Reading kernel log from /dev/kmsg`, ~199–211 ms after SUCCESS, mid-Toast animation, machine-stop, no watchdog, no dump text (`kmsg_195.txt:8,403,1047-1069` — `PonReason.HARD_RESET=1`, `Failed to get KlogOffset, Not Found`, footer `Last boot reason: reboot`).
3. **Repo's pre-packaged Next FZF5 daemon mismatch** (the port's first shipped `libksudnext.so`, replaced at 17:50 by `ksud-next-generic-kdp`): embeds **only** asset `android13-5.15.189_kernelsu.ko`; the runtime KMI key on this firmware is `android13-5.15` (2-part). `ksud late-load` died ~52 ms after spawn with the marker error documented in `next-failure-evidence.txt:52` and parsed in `next-failure-analysis.md` §3–4 and `next-loader-codepath.md` §1/§3/§6. **No kernel panic — it never even passed `init_module()`.** The capture of the actual `Error:` line is missing (the capture filtered exact tag `KernelSU`, and Next logs under `KernelSU Next`); the cause is provable statically from the binary (asset table) and from the shared-stage evidence `/dev/dfme2`.
4. **A plausible-but-wrong suspect**: the run2 panic was first suspected to implicate the classic *manager* app (its userspace crashes are real — Writable-dex SIGABRT on `me.weishu.kernelsu:root:0`, SIGSYS seccomp `syscall 142` probes — but those are app-side, deterministic, build-independent, and **not** the panic). run3 (Next manager) died at the identical locus, exonerating the manager ABI (`manager-compat-analysis.md` §3–5).
5. **run4 — Samsung-patched loader, wrong staging**: before patch 2, bootstrap never created `/data/adb` nor staged `.ksud-stage`; the loader's own first act (`rename(.ksud-stage → /data/adb/ksud)`) then failed (`run4_logcat.txt:30782-30788`). Fixed by patch 2; validated by run6.

> [!WARNING]
> The DFRoot-on-GZIF app originally shipped **with the broken FZF5 Next pair as `libksudnext.so`**; if you ever repopulate `app/src/main/jniLibs/arm64-v8a/libksudnext.so` from repo artifacts, verify the embedded assets (§7) before depending on it. The shipped file now embeds `android13-5.15_kernelsu.ko` and works (run8).

### 5.3 What was *not* fixed by the port

- **CVE-2026-43284 itself** — still open in the kernel (SMR-Nov-2026 lists it High). The port depends on it; a Samsung patch could close the exploit underneath this APK entirely.
- **Samsung's EL2 stack (RKP/KDP/DEFEX)** — the Samsung-patched module survives it on this build (run6/run8); *why* is understood (no syscall-table write, no live text patch, KDP-workqueue creds, DEFEX cred re-sync) but **never measured on 34343818**; treat each cold boot as a fresh experiment.
- **Persistence** — none. Root evaporates on reboot by design (and on purpose — this is volatile root on a locked bootloader).

---

## 6. The two local patches relative to upstream `diabl0w/DFRoot`

Upstream baseline: `diabl0w/DFRoot @ 7a6513a69ce4c1a90b68d857c26347bf166ffe10` ("Fall back to libbase when libc++ hook selection fails (#185)", 2026-11-06), branch `master`. The port was committed to `samson910022/DFRoot-GZIF` on 2026-11-17 (see CHANGELOG.md / NOTICE).

### Patch 1 — bundled Samsung-patched loaders, keyed off the selected manager package (historical patch, current code below)

Upstream staged the *manager APK's* `libksud.so`, always:

```java
private static void stageAssets(Context ctx, String suManagerPackage) throws Exception {
    var app = ctx.getPackageManager().getApplicationInfo(suManagerPackage, 0);
    stageBin(ctx, new File(app.nativeLibraryDir, "libksud.so"), "ksud");
    stageBin(ctx, new File(ctx.getApplicationInfo().nativeLibraryDir, "libbootstrap.so"), "bootstrap");
}
```

The 2026-11-17 port staged from the app's own `nativeLibraryDir` when available,
with an upstream fallback. That fallback has since been **removed**: current code
is shown below (Phase 1, §15).

```java
// Current: SuManager is a canonical allowlist; each entry owns exactly one
// pinned bundled loader, and RunPreflight rejects anything else before side effects.
CLASSIC("me.weishu.kernelsu", "libksud.so",     BuildConfig.BUNDLED_LOADER_CLASSIC_SHA256),
NEXT   ("com.rifsxd.ksunext", "libksudnext.so", BuildConfig.BUNDLED_LOADER_NEXT_SHA256);
File loader = new File(nativeLibraryDir, manager.getLoaderName());
// regular-file + no-symlink + SHA-256 pin checks ... then stage that loader.
```

Semantics and consequences:
- `me.weishu.kernelsu` selected → **bundled classic** `libksud.so`; the selected manager only supplies the `--package-name` argument.
- `com.rifsxd.ksunext` selected → **bundled Next** `libksudnext.so`; same.
- **No implicit fallback.** A missing, symlinked, renamed, or hash-mismatched
  bundled loader aborts the run during side-effect-free preflight. The manager
  APK's `libksud.so` is never used as a loader; only its presence is an
  eligibility/installation check.
- The staged loader is executed **not** by the app or by bootstrap directly:
  bootstrap execs it as `/data/local/tmp/.ksud-stage`; the two bundled loaders
  were built for exactly this (their embedded path constant is shared).

Why this is the correct change: the stock managers' embedded modules **panic** on this firmware (run2/run3), and the upstream flow staged exactly those modules. The Samsung-patched loaders survive (run6/run8). This patch swaps module provenance without touching the exploit.

### Patch 2 — `app/src/main/jni/bootstrap.c`: create `/data/adb`, stage `/data/local/tmp/.ksud-stage`, then exec it

Upstream's bootstrap did, on the success path:

```c
touch("/dev/dfm5");
if (run_ctx(NULL, (char *[]){ KSUD_STAGE, "late-load", "--package-name", su_manager, NULL }) == 0) {…}
```

where upstream `KSUD_STAGE` = `/data/user_de/0/df.root/ksud` — the Samsung-patched loaders **first** perform `utils::stage_daemon_from("/data/local/tmp/.ksud-stage")` (rename `.ksud-stage` → `/data/adb/ksud`, or fail). With no `/data/adb` present and no `.ksud-stage` present, that step failed → run4 (`Error: Failed to stage ksud`, `Failed to rename /data/local/tmp/.ksud-stage to /data/adb/ksud: No such file or directory`). In the current code `KSUD_STAGE_SRC` is the app-staged source (`/data/user_de/0/df.root/ksud`) and `KSUD_STAGE` remains `/data/local/tmp/.ksud-stage` because the bundled loaders have that literal path baked in.

The port's staging portion is unchanged (`mkdir /data/adb` + `stage_file()`),
but the old port snippet containing the **inverted `dfmsr` check** has been
removed. The `touch()` helper returns 0 on success, so the old
`dfmsr_ok = touch("/dev/dfmsr")` followed by `if (!soft_reboot || dfmsr_ok)`
actually rejected the success case. The
current code delegates the entire terminal decision to the pure producer and
publishes exactly one final file:

```c
/* Excerpt from app/src/main/jni/bootstrap.c:272-307 (function-level indentation
 * stripped); the soft-reboot tail is elided and marked below. */
touch("/dev/dfm5");
mkdir("/data/adb", 0700);                       /* FZG1 staging contract */
/* stage_file(): unlink + O_CREAT|O_EXCL|O_NOFOLLOW, 0700, full-write loop,
 * fchmod/fsync, unlink on failure. No system("cp ...") and no symlink race. */
if (stage_file(KSUD_STAGE_SRC, KSUD_STAGE) != 0)
    return df_outcome_to_rc(decide_and_publish(-1, 0));

late_load_rc = run_ctx(NULL, (char *[]){ KSUD_STAGE, "late-load",
                                        "--package-name", su_manager, NULL });
return df_outcome_to_rc(decide_and_publish(late_load_rc, soft_reboot));

/* decide_and_publish() wires status_io to real ops and calls:
 *   producer_decide_and_publish(io, ctx, late_load_rc, soft_reboot,
 *                               60000, 5000);
 * publish_final() now sets the non-persistent Android property
 * dfroot.final (context u:object_r:default_prop:s0) from root/bootstrap;
 * the app reads that property. The old /dev/dffinal temp-file publication
 * is gone: /dev/dffinal is no longer produced. */
```

This matches the documented FZG1 loader contract of the **historical (run4/run6/run8) binaries** (`kernelsu/README.md:154-156` in the payloads repo; `next-failure-analysis.md` §3.2/§6; those binaries contained the strings `/data/local/tmp/.ksud-stage`, `Failed to stage ksud`, `/data/adb/ksud`). The rebuilt loaders now bundled here contain `/data/adb/.ksud-stage` instead. `.ksud-stage` and `/data/adb/ksud` are on the same `/data` filesystem so the loader's `rename(2)` never crosses devices (`EXDEV` avoided). A secure-staging failure or a nonzero `late-load` exit now both publish the single `error` token in the `dfroot.final` property (rc=1; **`/dev/dffinal` is no longer produced**); distinguishing the sub-step requires logcat with **both** KSU tags.

> [!NOTE]
> The single `error` token in `dfroot.final` (`/dev/dffinal` is no longer produced) covers both "secure staging failed" and "ksud exited nonzero"; distinguishing them requires either reading the app log (the app prints `progress: bootstrap starting SU daemon` then awaits the final status) or capturing logcat with **both** tags (`KernelSU` **and** `KernelSU Next`) — the 17:00 evidence was blind to the latter and had to be diagnosed from the binary (see §9.2).

> [!WARNING]
> **Residual staging risk (P1-08, release gate).** Historically (run4/run6/run8 loaders), `/data/local/tmp` is `0771 shell:shell` on stock Android, not a root-owned directory; `stage_file()` prevented symlink attacks and races at creation (`O_EXCL|O_NOFOLLOW`, 0700), but a `shell`-uid process could still replace the file after `close()` and before the loader's `rename()`. **That risk could not be closed by DFRoot-side changes alone**: it required rebuilding the loaders. Those rebuilds are now integrated — the bundled loaders consume a root-owned `/data/adb/.ksud-stage` validated by fd before promotion — but **P1-08 stays OPEN pending re-review and fresh on-device full-chain validation**. Do not treat this PR as a complete fix for that threat model.

### Other local port deltas vs upstream (for completeness — these are *not* one of the two named patches)

`git diff` in `DFRoot-fzg1/` additionally shows:

- `app/build.gradle.kts` — `compileSdk`/`targetSdk` 37→36 (host SDK tops at 36);
- `gradle/libs.versions.toml` — AGP 9.4.0→9.5.0. This is now the **single source of truth**: the root `build.gradle.kts` no longer pins `version "9.4.0" apply false` (it applies `alias(libs.plugins.android.application) apply false`) and `app/build.gradle.kts` applies the same alias, so the **effective AGP is 9.5.0**. The old root pin made the catalog bump inert; that pin is gone;
- `app/src/main/AndroidManifest.xml` — label "DFRoot" → **"DFRoot GZIF"**;
- `app/src/main/jni/CMakeLists.txt` — keeps only `ko/dfroot-android13-5.15.ko` (drops the other 7 KMI entries);
- `app/src/main/jni/exp.c` — embedded KO assets trimmed to `dfroot_ko_13_5_15`; `detect_ko_target()` now hard-codes `/vendor/lib64/AIAwbModel_v2.2.tflite`; `KO_TARGET` selection comment updated for libbase fallback (hook selection via `patch_hook` → `/system/lib64/libc++.so`, falling back to `libbase.so`);
- `app/src/main/jni/libcxx.S` — `ko_target` string changed to `/vendor/lib64/AIAwbModel_v2.2.tflite`;
- `app/src/main/jni/exp.c` / `.S`-side ko_target padding kept at 64 B (`.skip 32, 0`);
- `app/src/main/jni/exp.c` hook site: libc++ sentry symbol unchanged; runtime-detected BTI/PAC preamble advance (+4) logged each run.

None of these change the exploit mathematics. All are required to make the chain run on this exact firmware (missing vendor files, missing KMI images, compileSdk).

---

## 7. In-repo artifacts that matter

In `DFRoot-fzg1/` (all paths relative to its root):

| Path | Size / fingerprint | Role |
|---|---|---|
| `app/src/main/jni/ko/dfroot-android13-5.15.ko` | **CI/release artifact: 13,384 B · sha256 `9dfb8b71b306f5a38fd8b35b283d4dab8b4eb25a08ad97ca0f4ef705b6151c68`**; **local `make` build: 13,504 B · sha256 `94f8be98…`** (retains `.note.gnu.build-id`) | The android13-5.15 LKM. **Not committed** (gitignored; rebuilt by the single-KMI `make` or CI). Built against the digest-pinned DDK GKI image `ghcr.io/ylarod/ddk-min:android13-5.15-20260828@sha256:9e68cd9fcab748584755d31ba88ef134efc938dbde17f258c2652315f6cd81cd` (top-level `make` resolves this exact reference through `scripts/ddk-image.sh`). CI runs `llvm-objcopy --strip-unneeded -R .note.gnu.build-id …`, so its KO (13,384 B) differs from the local build's (13,504 B, `94f8be98…`); both compile the same source and load on GZIF. Modversions satisfied via KMI CRC; intentionally `module_init → return -ENODEV` (unload-immediately; avoids MTK mrdump callbacks). `lkm/dfroot.ko` was removed from the tree during public release hygiene (it was a duplicate of this file). |
| `app/src/main/jniLibs/arm64-v8a/libksud.so` | 5,127,584 B · sha256 **`731c3fa1f83fe698ec0b749413065b25c12d4d433b2aed6c3ba7152fdfbefee4`** (`731c3fa1…`) | **Samsung-patched classic KernelSU 3.3.0** loader (v3.3.0, uapi 2, code 32601), `ksud-dm3q-S9180ZHS8FZG1-kdp` **rebuilt from the P1-08-patched source** on `fix/p1-08-root-owned-staging` (`891e897`), not copied verbatim from the payloads artifacts (CI source-build run 45674540939). It consumes `/data/adb/.ksud-stage` (no old `/data/local/tmp/.ksud-stage` string). Loads 5.15.197-GZIF (vermagic rewrite via ksuinit). Embedded asset: **`android13-5.15_kernelsu.ko`** (2-part/family name — correct for this firmware). Log tag `KernelSU`. **run6 device evidence is historical and applies to the previous loader, not these bytes.** |
| `app/src/main/jniLibs/arm64-v8a/libksudnext.so` | 4,728,320 B · sha256 **`7f28f1fb5d3238f606330945b2694df140fea97527507c865f411db704ec2414`** (`7f28f1fb…`) | **Samsung-patched KernelSU-Next 3.4.2** loader (uapi 5, code 33355), `ksud-next-generic-kdp` **rebuilt from the same patched source** (CI source-build run 45674545588), not the old verbatim `support/kernelsu-generic.json` artifact. It consumes `/data/adb/.ksud-stage`. Embedded assets: **`android12-5.10_kernelsu.ko`, `android13-5.15_kernelsu.ko`, `android14-6.1_kernelsu.ko`, `android15-6.6_kernelsu.ko`** (all 2-part/family names). Log tag `KernelSU Next`. **run8 device evidence is historical and applies to the previous loader, not these bytes.** Do **not** substitute `ksud-next-dm3q-S9180ZHS8FZF5-kdp` (`f23ede9b…`, 3.4.1) — it embeds only `android13-5.15.189_kernelsu.ko` and provably fails pre-load on this build (17:00 evidence). |
| `app/src/main/jni/libcxx.S` | source | tid=1 shellcode + `ko_target` patch point + `/dev/df` mutex + `vendor_modprobe` exec. |
| `app/src/main/jni/exp.c` | source | the whole exploit (IpSec SA, crash_dump bridge, patching, hook, single final-status consumer). |
| `app/src/main/jni/bootstrap.c` | source | UMH → `/data/user_de/0/df.root/bootstrap` (Samsung FZG1 staging contract). |
| `app/src/main/jni/splicehelper(.c)` | source + freestanding binary (built) | splice/read bridge for `crash_dump64`. |
| `app/src/main/java/df/root/*.java` | source | app UI, spinner, `BootReceiver` (Start on Boot — disabled by default; `android:enabled="false"` in manifest). |
| `app/build/outputs/apk/release/DFRoot_4.8-gzif.apk` | 5,884,467 B | **Dual-mode** (legacy KernelSU-classic + KernelSU-Next 3.4.2) — built 2026-11-17 17:50 from the tree above, versionName 4.8-gzif / versionCode 408, applicationId `df.root`, label "DFRoot GZIF". |

Also load-bearing, in the payloads repo `samson910022/Root-My-Galaxy-Payloads-Next`:

- `kernelsu/ksud-dm3q-S9180ZHS8FZG1-kdp` (the classic artifact copied 1:1 into `libksud.so`),
- `kernelsu/ksud-next-generic-kdp` (the Next artifact copied 1:1 into `libksudnext.so`),
- `kernelsu/ksud-next-dm3q-S9180ZHS8FZF5-kdp` (the broken FZF5 pair — kept for reference; **do not bundle**),
- `kernelsu/patches/KernelSU-Next-v3.4.2-samsung-kdp-rkp-defex.patch` / `KernelSU-v3.3.0-samsung-kdp-rkp-defex.patch` (the Samsung deltas; **NO_PATCH_TEXT + kretprobe fallback**, KDP via `prepare_ro_creds`/`kdp_assign_pgd` workqueue, DEFEX cred re-sync, SELinux-hide kprobes),
- `kernelsu/README.md` (Samsung build records; FZG1 loader "preferred/proven loader" per line ~471),
- `support/kernelsu-generic.json` (feed row for `ksud-next-generic-kdp`),
- `docs/SM-S9180-S9180ZHU8GZIF.md` (this port's campaign record, updated through run8).

The APK is currently **dual-mode**: both loaders are inside one APK; the spinner + patch 1 selects which one runs.

---

## 8. Build & run — reproduction recipe

### 8.1 Host environment (as built on this host, 2026-11-17)

- Linux x86_64; Android SDK at **`/usr/lib/android-sdk`** was used for the on-host build (per the generated `app/.cxx/.../android_gradle_build.json`, which cites `/usr/lib/android-sdk/{cmake/3.22.1,ndk/28.2.13676358}`), with:
  - `platforms/android-36`, `build-tools/36.0.0`,
  - **NDK `28.2.13676358`** (i.e. **r28c**) — cited by the build metadata; AGP's default SDK at `~/Android/Sdk` (also present on this host, with `platforms/android-37.0`, NDKs r28c and r29, cmake 3.22.1) would equally resolve. `ANDROID_HOME` was unset in the shell; if reproducing elsewhere, pin `sdk.dir` in `local.properties` or export `ANDROID_HOME` to wherever android-36 lives.
  - CMake **3.22.1** + ninja at `…/cmake/3.22.1`,
  - JDK: toolchain **21** pinned in `gradle/gradle-daemon-jvm.properties` (auto-provisioned via foojay; host JDK 25 also present but the Gradle daemon JVM is 21); CI also uses temurin 21 (`.github/workflows/build.yml`),
 HEAD
  - Gradle wrapper **9.9.2** (`gradle/wrapper/gradle-wrapper.properties`, distribution SHA-256 pinned), AGP **9.5.0** declared in `gradle/libs.versions.toml` and consumed via the version-catalog alias — no root pin, so the catalog value is effective (§6 note).
- `signing.properties` at repo root with `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` (**do not commit; it is gitignored**) is **optional at configuration time**. When it is absent or incomplete, a harmless placeholder is installed and the release variant and every release task are disabled/fail closed, while debug builds and JVM tests still configure; no unsigned or dummy-signed Release APK is emitted. **Debug** is always signed with the standard Android debug keystore via `signingConfigs.getByName("debug")`. For local `make`, `SIGN_PASS` / `SIGN_ALIAS` must be **exported in the environment**; command-line assignments (`make SIGN_PASS=…`) are rejected. In CI the four `KEYSTORE*` secrets exist only in the protected `release` environment and are referenced only on `v*` tag pushes; PR/branch/manual builds use no secrets, build the Debug APK only as a compile/content gate, and upload **no APK** — only the `DFRoot-CI-evidence` licence/notice bundle (no signed release, no GitHub Release). The unconditional `host-gates` job gates every event including tags; see §19. The `KEYSTORE` secret/environment value must be a **base64-encoded JKS** (e.g. `base64 -w0 keystore.jks`); the CI writer decodes and validates it before publishing signing material. Signed releases use a **new key identity** — uninstall an existing installation before installing one.
- NDK sysroot builds: `splicehelper` is compiled freestanding at CMake time (`-nodefaultlibs -nostartfiles -ffreestanding -static`).
- LKM rebuild (**required** — the `.ko` is not committed): `cd lkm && make KDIR=<kdir> LLVM=1` (or CI workflow `.github/workflows/build.yml`, which uses digest-pinned `ghcr.io/ylarod/ddk-min:<kmi>-20260828@sha256:…` containers). Top-level `Makefile` runs exactly **one** podman DDK build for `android13-5.15` (no eight-KMI loop); the image reference is derived from CI by `scripts/ddk-image.sh` and is digest-pinned, and the container runs with `--network=none`, `--cap-drop=ALL` and `no-new-privileges`, then `:app:assembleRelease`.
- `lkm/dfroot.ko` is intentionally GPL with `module_init` returning `-ENODEV`; §4 step (5).

### 8.2 Build

```sh
git clone https://github.com/samson910022/DFRoot-GZIF.git && cd DFRoot-GZIF
./gradlew :app:assembleRelease        # or the top-level Makefile (needs podman + keystore)
# → app/build/outputs/apk/release/DFRoot_4.8-gzif.apk
```

The release variant passes `-DDFROOT_ANDROID_VARIANT=release` to CMake (the
authoritative policy signal; a cached Debug label cannot weaken it). CMake
requires `CMAKE_OBJCOPY` and verifies the final `.dfroot_ko` bytes in
`libdfroot.so` after linking, with no skip path; tag CI repeats the check on
the APK's native library before upload. The byte gate rejects unknown bytes but
**accepts the two known obsolete pins**; no hashes were added or relaxed. A
release is withheld by policy — a reviewed re-pin, OPEN P1-08 and device
validation — not by the hash gate alone, and passing the gate is not device
validation.

### 8.3 Install & run (device)

1. Enable **Developer options → USB debugging** (needed for adb verification; not required for the exploit itself). Connect the unit; authorize the host's RSA key.
2. `adb install -r app/build/outputs/apk/release/DFRoot_4.8-gzif.apk`
3. Open **DFRoot GZIF** (launcher). Confirm the spinner shows your installed SU manager (e.g. KernelSU-Next — the only one hardware-validated here). The spinner is built from **installed supported packages** (`MainActivity.java:77-88`); if it refuses "Select a SU Manager", no supported manager package is installed. Preflight checks the app's **own pinned bundled loader** by SHA-256 (`RunPreflight.java:150-162`) — the manager APK's `nativeLibraryDir` is not consulted.
4. Press **LAUNCH ROOT**.
5. Verify root (uid 2000 shell via adb):
   ```sh
   su -c id          # expect: uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
   su -v             # 3.3.0:KernelSU   (run6)   |   3.4.2:KernelSU   (run8)
   getenforce        # Enforcing
   cat /proc/modules | grep kernelsu   # kernelsu ... Live
   getprop dfroot.final  # dfroot-v2:<nonce>:<root_only | reboot_pending |
                         #   reboot_requested | reboot_skipped | reboot_failed |
                         #   reboot_attempt_unknown | error>
   # /dev/dffinal is no longer produced; use the property for final-status diagnostics.
   ```
   `/dev/dfm6` (`ksud start: SUCCESS`) is only a **progress** diagnostic, not a
   root or success verification; native returns only after the run-scoped
   terminal marker (see the "Result contract" section of `README.md`).
6. **After every reboot: re-run LAUNCH ROOT.** There is no persistence. Do **not** enable "Start on Boot" or "Auto Soft Reboot" until you have tested them (see §9.6 / §10).

### 8.4 What the app does each run (compressed)

Fresh boot → press LAUNCH ROOT → (a) app stages bundled `libksudnext.so`/`libksud.so` + `libbootstrap.so` to its protected storage, (b) execs `libdfroot.so` with the IpSec SPI/key args, (c) `crash_dump64` + vendor tflite + `libc++.so` get patched in page cache, (d) `createOrphanProcess()` arms the init-tid-1 hook, (e) kernel UMH spawns `bootstrap`, (f) `bootstrap` stages `.ksud-stage`, (g) Samsung-patched ksud renames it to `/data/adb/ksud` and `init_module()`s its embedded `android13-5.15_kernelsu.ko`, (h) `bootstrap` publishes one token in the non-persistent property `dfroot.final` (`/dev/dffinal` is no longer produced) and the app reports the corresponding rc — never "root verified" and never a guaranteed reboot.

---

## 9. Troubleshooting

### 9.1 LAUNCH ROOT button is disabled
Current code: the button is enabled only by `MainActivity.isRunEligible()`
(`MainActivity.java:219`) — a confirmed `RunConfig` with a selected SU
manager, controls writable, and an idle/terminal `RunCoordinator`.

Separately, if `/dev/df` exists (`HookMarkerProbe.java:14`) the exploit was
already attempted on **this** boot: `RunOrchestrator.run()` short-circuits to
`RunResult.alreadyHooked()` before preflight (`RunOrchestrator.java:17-23`, via
`ExploitRunner.RunSeams.isHookPresent()` at `ExploitRunner.java:222-224`). `/dev`
is devtmpfs and is wiped at every boot, so this cannot be residue from a previous
one. Reboot to re-arm. Since R3.6 the message reads
`DFRoot: already attempted/hooked; root status unverified`: the SUCCESS code
and no-reentry guard are unchanged, and the wording neither claims `SUCCESS`
nor implies that root was verified. A separate read-only availability view now
surfaces the authoritative preflight reason ("Run unavailable: …") without
starting, staging, or substituting anything.

### 9.2 Toast "DFRoot: Error - bootstrap failed" / final token `error` (rc=1)
Semantics (current code): the `dfroot.final` property contains `error` (rc=1) after a nonzero `late-load`/secure-staging failure (**`/dev/dffinal` is no longer produced**); the app log line is `final: ERROR - bootstrap failed before root`. Under the **old marker protocol** this was `/dev/dfme2` present and `/dev/dfm6` absent; the historical run evidence below uses that old scheme and is **not** a validation of the current flow. Causes seen in the field, in probability order:

1. **Bundled Next loader with wrong embedded KMI asset name** (the 17:00 class; `libksudnext.so` embeds `android13-5.15.189_kernelsu.ko` only). Detect: `strings app/src/main/jniLibs/arm64-v8a/libksudnext.so | grep _kernelsu.ko` from host. **Fix:** keep `libksudnext.so` = `ksud-next-generic-kdp` (embeds `android13-5.15_kernelsu.ko`).
2. **`/data/adb` missing / `.ksud-stage` not created / rename failed** (run4 class). Detect on device: `ls -l /data/local/tmp/.ksud-stage /data/adb/ksud` — `.ksud-stage` gone + `/data/adb/ksud` present = staging step succeeded and the failure was later; both absent = secure staging failed (e.g. `/data` full?); both present = ksud never consumed the stage (should not happen on this code path — re-check with full-tag logcat).
3. **Samsung-patched module rejected by the kernel after all** (unproven on this firmware for the *class* of builds in this APK, but the FZG1 classic did load — run6). If you see this with a *rebuilt* module, capture unfiltered logcat and `dropbox` for a new `SYSTEM_LAST_KMSG_*_KP`.
4. **ksud logs only under the other tag.** The capture must not filter to exact `KernelSU` — Next logs under **`KernelSU Next`** (with a space — requires quoting in shell). If the evidence shows a device-alive boot with *no* `KernelSU Next` lines at all and an `error` final status (`dfme2` under the old protocol), suspect either the tag filter or a death before `android_logger::init_once`; both are consistent with "asset miss".
5. If the failure is **silent in logcat**, the ksud stderr went to `/dev/null` by construction (kernel UMH stdio). The only surviving ksud diagnostics are in logcat. Capture with:
   ```sh
   adb logcat -d | grep -E 'KernelSU Next|KernelSU|dfroot'
   ```

### 9.3 SELinux Permissive after a run
`getenforce` → `Permissive` means the **exploit executed** (dfroot.ko flipped `selinux_state`) but does **not** mean root was obtained. In the 17:00 failure (`next-failure-evidence.txt:3`) the device was Permissive and KSU was **not** resident. Permissive persists until reboot. Check `getprop dfroot.final` (or the old `/dev/dfm6` in historical logs; **`/dev/dffinal` is no longer produced**) and `su -c id` to know which side of the line you are on.

### 9.4 Manager shows "Not installed"
The KernelSU / KernelSU-Next manager UI reads the KSU driver fd; no module resident ⇒ "Not installed". This follows DFRoot "SUCCESS" automatically on stock-panic runs (runs 2/3 — no root, manager says not installed) and on the 17:00 asset-name failure. It is a *symptom*, not a diagnosis; use §9.2.

### 9.5 Classic manager (`me.weishu.kernelsu`) is unusable on this build — expected
Two deterministic **app-side** defects, independent of root:
- **SIGABRT** — `Check failed: system_class_loader != nullptr … java.lang.SecurityException: Writable dex file '/data/user_de/0/me.weishu.kernelsu/cache/main.jar' is not allowed.'` (Android 14+ ReadOnlyJavaDcl W^X for dex; uid 10052, `app_process64`, `/system/bin/app_process64`; dropbox `SYSTEM_TOMBSTONE` entries ~15:33:09, and identical on FZG1 — `manager-compat-analysis.md` §3).
- **SIGSYS (`SYS_SECCOMP`, arm64 syscall 142 = `reboot(2)`)** — the manager's `libksud.so feature check` probes die: `x0=0xdeadbeef x1=0xcafebabe`. Unlike the init-context daemon (which runs with `Seccomp: 0`), the probes run under the app's zygote seccomp-bpf filter and are killed before any KSU hook can answer on fresh boots (`manager-compat-analysis.md` §4).
The classic manager therefore cannot manage root on this firmware **even on a fully working day**. Use KernelSU-Next as the UI, or ignore the manager UI entirely and use `adb shell su -c id` as the oracle. (The *classic* loader binaries bundled in the APK are unaffected — they were never executed as an app.)

### 9.6 Auto Soft Reboot / Start on Boot
- **Auto Soft Reboot** (DFRoot setting) triggered a **full hard reboot once** on this model (bootstrap's soft-reboot path → `ksud soft-reboot`), documented in the campaign record §6.6 ("which on this build is a full kernel reboot — /proc/uptime resets, Enforcing remains"). Upstream DFRoot FAQ points the same way (diabl0w Discussion #69: "try disabling Auto Soft Reboot"). **Do not enable it on this device until you have tested it.**
- **Start on Boot** re-runs the whole chain per boot. Risks: (a) if a KSU module is broken, bootloop; mitigation = boot to Safe Mode (hold Vol-Down), or DFRoot Settings → "Disable KSU Modules", or uninstall DFRoot; (b) unverified for stability on this firmware — not validated on this unit. Leave off unless you have a specific reason and a recovery plan.
- Both are controlled by the `BootReceiver` (blocked unless enabled; manifest has `android:enabled="false"` for the receiver) and the `soft_reboot` pref in `SettingsFragment`.
- Round 4 (host-only, UNVALIDATED on device) adds fail-closed gating: a durable
  write-ahead `PENDING` marker is published BEFORE the preference commit, and a
  failed or interrupted write quarantines its key in `PreferenceIntegrity`;
  while quarantined, `BootReceiver` skips the boot launch, `SettingsFragment`
  disables "Start on Boot" and refuses to enable it, and `MainActivity`
  disables LAUNCH ROOT. If the marker cannot be persisted, the `BootReceiver`
  component is disabled synchronously at the failure boundary rather than
  trusting an unrecorded gate. Unattributable faults latch a sticky `WILDCARD`;
  recovery needs a fully validated PENDING→commit cycle of the SAME key (or
  clearing app data), and a different key cannot clear the latch. The boot
  switch mirrors actual component state and the user can always turn boot OFF;
  nothing auto-enables. Real PackageManager/fsync behavior is UNVALIDATED.
- Round 5 (host-only, UNVALIDATED on device) adds launch tokens and a
  whole-transaction lock so no success is reported on a failed integrity
  transaction, and makes the boot component state tri-state: `UNKNOWN` is
  never shown as verified OFF and never used to accept OFF. Round 6 (also
  host-only/UNVALIDATED on device) makes the `UNKNOWN` indication persistent in
  the Settings status row and adds a separate, explicit safe disable action;
  see §19 for the current-head disposition.

### 9.7 Other failure modes & logcat tags
- Tags to capture: **`dfroot`** (exploit + app; line markers `* app version`, `=== setup ===`, `=== exploit ===`, `=== init ===`, `=== cleanup ===`), **`KernelSU`** (classic ksud), **`KernelSU Next`** (Next ksud), and (for manager UX) the manager's own tags. Always capture both KSU tags or none (`next-failure-evidence.txt` is a negative example).
- "DFRoot: unknown result - check logcat & dmesg" (rc=2) → either IpSec setup failed or no valid final token appeared before the checked 70 s budget (85 s overall cap when a `reboot_pending` preflight opened the grace window); look at `dfroot` lines; `dmesg` requires root (unavailable exactly when you need it). A final rc ≥ 64 means a restore failed (recovery bit).
- Log buffer size Off → DFRoot refuses ("Log buffer size is set to Off") — set it to anything else in Developer Options.
- `/dev/df` lingering after a failed run: press the button again is refused with rc 9 ("already hooked", not rc 0) — **reboot is the only re-arm**. A quarantined preference state blocks launch with rc 8; rc ≥ 64 appends "recovery verification failed" to ANY message.
- Intermittent: `Failed to set feature 5: ksuctl failed: Operation not supported on transport endpoint (os error 95)` (seen run8) — non-fatal; KSU continued and loaded 5 other features. Do not chase it.
- `KernelSU version: 0` lines are expected on fresh boots (nothing loaded when the probe runs) — not an error.
- If modules fail to load/bootloop after a successful root: DFRoot Settings → **Disable KSU Modules** (bootstrap honors it via `/data/adb/modules/*/disable` before ksud runs), or boot into Safe Mode, or uninstall the offending module, or remove DFRoot.

### 9.8 Forensic triage after a fatal run (KP)
The device never captures panic text (ABL: `Failed to get KlogOffset, Not Found` → `SamsungLogFlush KlogOffset:0x0`; `[[TRUNCATED]]`; `PonReason.HARD_RESET=1`; `Bootloader: S9180ZHU8GZIF`). Post-mortem sources:
- `dropbox` entries `SYSTEM_LAST_KMSG_<n>_YYYYMMDD_HHMMSS_KP` — only XBL/ABL text; `PonReason.HARD_RESET=1` confirms panic vs watchdog;
- DFRoot app log (`dfroot` tag) for exactly how far the chain got;
- `adb shell cat /proc/last_kmsg` — empty/absent on Samsung KP boots (do not expect it).

---

## 10. Safety & limitations

- **Volatile root.** Nothing persists across reboot — not the KSU module, not the SELinux flip, not `su`, not DFRoot's `/dev/df` mutex. Reboot = clean slate (Enforcing). To re-root: open the app, LAUNCH ROOT, verify.
- **Not persistent, not boot-image.** No bootloader unlock, no boot image/vbmeta/`dtbo` touch, no Knox counter increment **by this port** (but: any crash dump flagging, warranty, or OTA integrity consequences of running an exploit are the user's own responsibility; Knox `Warranty Void` is not set by this process, but Samsung may classify unexpected behavior per its own terms — do not take this as legal advice).
- **Kernel panic risk is real on this build.** Stock vanilla KSU/KSUN modules panic on 34343818 (two runs, two managers). The Samsung-patched loaders in this APK survived twice — but **kernel exploits and LKM-based root are inherently unstable**: no uptime guarantee, no module-signature chain to lean on, and each cold boot re-exposes the same attack surface.
- **One-time per boot.** `/dev/df` is armed once per boot and the app refuses
  re-runs; unattended boot additionally claims `BOOT_COUNT` durably so at most
  one boot attempt occurs. A hung or panicked attempt costs a hard reset.
- **Incompatibility:** DFRoot is known on other builds to break after certain updates; modules (Lygisk/LSPosed-class) can themselves cause crashes/bootloops — use "Disable KSU Modules" liberally. **Auto Soft Reboot caused one full hard reboot on this model — treat it as a live risk, not a convenience.**
- **CVE-2026-43284 could be patched at any time** (it is listed High in SMR-Nov-2026) — after that, this chain is dead. Keep OEM/firmware changelog hygiene in mind before updating.
- **This port is not upstream.** The older local port folder (`DFRoot-fzg1/`) remains an uncommitted working tree on top of `diabl0w/DFRoot @ 7a6513a`; the `fix/native-exploit-robustness` PR branch is separately committed at `e823fb4`. Pulling upstream or re-cloning the older folder loses the two patches and the bundled loaders. **Never push to `origin`** (which points at `github.com/diabl0w/DFRoot`) — fork if you need to publish.
- **Legality/ethics:** only on your own device; the exploit patches page-cache contents of files the system executes. It does not unlock anything, and it violates no signature checks *because it does not need to* — but Samsung's warranty/Knox terms are your affair.
- **Verification hygiene:** never trust the on-screen toast. Verify with the §8.3 oracle each time, on every boot.
- **Residual preference risk:** if the SU-manager `SharedPreferences` write/removal fails, a stale package name may remain on disk. Round-4 quarantine blocks manual launch (rc 8) and makes `BootReceiver` skip while the key is ambiguous, so a stale value is not acted on; the failed write still does not erase the stale bytes (fail-closed gating, not a repair). Save the same setting again or clear app data to recover; this path is host-tested only and UNVALIDATED on device.

---

## 11. Evidence locator (host paths, line refs — logs are *not* inlined)

Logs live host-side under `GZIF-port/`. They are large (up to 142k lines) and contain private identifiers (BLE device addresses, package usage, network details) — **reference line ranges, never copy wholesale**, and do not commit them anywhere.

| File | What it records | Key lines/ranges |
|---|---|---|
| `run2_logcat.txt` | classic-KSU run (KP194) | identity `:1178-1183`; LateLoad `:1673`; late-load start `:1675`; Detected KMI `:1678`; **SUCCESS printed before module line** `:1679`; `Loading kernelsu.ko` `:1688`; last line `:1851` |
| `run3_logcat.txt` | Next-KSU run (KP195) | identity `:1289-1294`; tags `KernelSU Next` `:1529-1705`; SUCCESS `:1535`; `Loading kernelsu.ko` `:1544`; `Reading kernel log` `:1641`; last `:1705` |
| `run4_logcat.txt` | Samsung classic loader, pre-staging-patch (non-fatal) | patch steps `:30363-30532`; LateLoad `:30779`; **`Failed to stage ksud`** `:30782-30785`; ERROR `:30788` |
| `run5_logcat.txt` | app launch only; no exploit | — |
| `run6_logcat.txt` | **classic loader FULL SUCCESS** | `LateLoad … package_name: "com.rifsxd.ksunext"` `:25747`; triggered `:25748`; Detected KMI `:25753`; Loading `:25767`; kmsg `:25899`; **loaded successfully!** `:26590`; after load_module `:26592`; **SUCCESS** `:26980`; `adbd` marker probe `:45621` |
| `run8_logcat.txt` | **Next loader FULL SUCCESS** | identity `:3509-3515`; setup/patch `:3715-3830`; dfroot.ko write `:7772`; libc++ patch `:7775-7782`; init/hook trigger `:8310-8860`; bootstrap `:8911-8948`; **LateLoad `:8968`** (`"com.rifsxd.ksunext"`, `KernelSU Next` tag); **loaded successfully! `:9739`**; `after load_module … u:r:ksu:s0` `:9740`; sepolicy loads `:9747-9871`; feature-5 error `:9850`; **SUCCESS `:9881`**; cleanup `:9883-9887`; post-run manager probes `:15152+` |
| `next-failure-evidence.txt` | 17:00 Next FZF5-pair failure (markers + dfroot log) | device state (Permissive) `:3`; markers `:5-16` (`dfme2: yes`, `dfm6: -`); dfroot log `:18-58`; **ERROR `:52`**; tag-filtered capture `:59-86` (0 `KernelSU Next` hits) |
| `next-failure-analysis.md` | static root-cause of the 17:00 failure | markers map §1.1; binary asset table §1.3; staging contract §3.2; **KMI asset-name mismatch §3.3-3.4**; verdict (b-pre-load) §4; fix options (i)-(v) §5 |
| `next-loader-codepath.md` | loader-for-loader comparison (classic vs Next FZF5) | binary fingerprint table §1; FZG1 staging contract §2; TIMELINE §5; verdict + candidate swap (a/b) §6; evidence ledger §7 |
| `manager-compat-analysis.md` | panic locus + manager-independence (runs 2/3) | crash signatures §3-5; flow §6; **manager-independence table §6.3**; FZF5-vs-FZG1 §9; recommendation §11 |
| `samsung-lateload-forensics.md` | why stock modules panic; what Samsung builds do | Samsung commit anatomy §1.1; diabl0w fork `samsung-v2.2` §2.1; **mechanism §6.1**; snothin SCS/KCFI lesson §5; verdict §6 |
| `oneui9-rooting-landscape.md` | ecosystem survey (post-426 GENs; S25U DDZJB; sibling AGZIF success) | §2 matrix; §4 panic-class consensus; §5 candidates; §6 SMR notes (43499 High Oct; 43284 High Nov); §8 bottom line |
| `rmgnext-feasibility.md` | why Root-My-Galaxy-Next won't help here | §2 chain identical to DFRoot; §3 no GZIF catalog rows; §5 issues (incl. #55 twin build); §7 verdict |
| `crash-analysis-run2.md` | co-located forensic author for runs 2/3 | cross-check only; every load-bearing fact re-verified here against raw files |
| `dropbox_all.txt` | all dropbox entries (18987 lines) | KP189/191-195 KP-flagged resets; classic-manager SIGABRT `:15085-15154`; SIGSYS battery `:15433-17517` |
| `kmsg_195.txt` | run3's KP boot dump (XBL-only) | dropbox header `:1-15`; `PonReason.HARD_RESET = 1` `:403`; `Failed to get KlogOffset` `:1047-1049`; `Last boot reason: reboot` `:1069`; `Hyp version: 1` (present) `:702` |
| `dfroot_screen.png` | screenshot of the app output at failure (not inlined) | — |
| `DFRoot-fzg1/` | the ported tool (working tree, uncommitted) | `app/`, `lkm/`, `gradle/` |
| Payloads repo `docs/SM-S9180-S9180ZHU8GZIF.md` | **campaign record** — start here | §1 identity; §3 CVE matrix; §6.5 dry-run; §6.6 (first "SUCCESS" then hard reboot); **§6.7 FULL ROOT ACHIEVED 16:49**; **§6.8 FULL ROOT ACHIEVED 17:53 (Next)** |
| Payloads repo `kernelsu/README.md` | Samsung KSU build records + FZG1 loader provenance | v3.4.2 generic row (line ~12); FZG1 loader row (line ~471) |
| Payloads repo `support/kernelsu-generic.json` | Next generic feed row (daemon 3.4.2, `679504dd…`) | lines ~36-46 |

---

## 12. Reproduction confidence — what is device-validated vs unverified

Rows 1–20 are evidence for the **historical implementation** (old marker
protocol, historical KO pins, old preference write path). run6/run8 and all
earlier CI runs predate the post-PR #1 rebase and do not validate the current
code base of `fix/native-exploit-robustness` (D2, committed head `e823fb4`);
it is host-tested by exact-head CI run 45864085866 except for the scoped
author-local P2-16 restore retest in row 31 and the two P2-17
final-status-observability retests in rows 32–33. The P2-15 fix head is `7e18b13` and the Phase B code
head is `0ea022f`. Rows 21–28 describe that
host evidence explicitly, with code-head counts locally verified at the final
Phase B increment and tabulated in `CHANGELOG.md` round 8
(current-increment host test shape; at the code head the workflow policy
count is 92 and the helper-size count is 30, was 7). CI run 45708004129 at
`0ea022f` completed with conclusion `success` at re-check (host-only evidence,
not device validation); no completed-green CI conclusion is claimed for the
earlier heads `7e18b13` or `376f5e2`. Exact-head CI for committed `e823fb4` is
run 45864085866 (completed `success`, host-only; release/publish SKIPPED, no
DFRoot APK uploaded; helper-size 30 author-reported; no device result). Rows
29–30 are the historical failed S23 attempts at the pre-fix head and the fix;
**row 31 records the round-12 device retest (P2-16 restore scope passed; rc=2
timeout / unbound observation was a separate OPEN item in that pre-D2 run),
row 32 records the earlier pre-commit author-local D2 retest (rc=0 via
`dfroot.final`, scoped to final-status observability only), and row 33 records
the second author-local run at committed head `e823fb4`** — both are scoped
author-local evidence, not independent validation. A green
build does not certify safe privileged execution.
Provenance manifests are informational, not independent
reproducible-build proofs or attestations. **The byte gate rejects unknown
bytes but accepts the two known obsolete pins**: release is withheld by policy
— a reviewed re-pin, unvalidated Auto Soft Reboot / Start on Boot, the **OPEN
P1-08** gate and device validation — not by the hash gate alone. The old
`/data/local/tmp/.ksud-stage` trust gap described in the historical rows
required a loader rebuild; that rebuild is now integrated (root-owned
`/data/adb/.ksud-stage`) but is pending re-review and device validation.
Passing the gate is not device validation.

| # | Claim | Status | Evidence |
|---|---|---|---|
| 1 | Exploit chain runs end-to-end on this firmware up through UMH/bootstrap (old `/dev/dfm*` markers) | **Device-validated under the OLD marker protocol** (5+ runs; both KP and non-KP) | `run2:1178`, `run3`, `run4:30303-30793`, `run6:25747-26980`, `run8:3509-9887` |
| 2 | Samsung-patched classic loader (FZG1-validated) loads `kernelsu.ko`; root granted; SELinux stays Enforcing | **Device-validated** — 1 run (run6) | `run6_logcat.txt:25747-26980`; `docs/SM-S9180-S9180ZHU8GZIF.md` §6.7 (`su -c id`, `su -v`, `getenforce`, `/proc/modules`) |
| 3 | Samsung-patched generic Next 3.4.2 loader loads `kernelsu.ko`; root granted; SELinux stays Enforcing | **Device-validated** — 1 run (run8) | `run8_logcat.txt:8968-9887`; campaign doc §6.8 |
| 4 | Stock vanilla classic v3.3.0 **panics** inside/around `init_module()` | **Device-validated** — 1 run (run2, KP194) | `run2_logcat.txt:1673-1851`; `kmsg_195.txt` (sibling); `manager-compat-analysis.md` §6-7 |
| 5 | Stock vanilla Next v3.4.2 **panics** at the identical locus | **Device-validated** — 1 run (run3, KP195) | `run3_logcat.txt:1529-1705`; `kmsg_195.txt:8,403,1047-1069` |
| 6 | FZF5-pair Next 3.4.1 loader fails pre-load on KMI `android13-5.15` asset miss (no panic) | **Device-validated** — 1 run (17:00) + static binary proof | `next-failure-evidence.txt:3-86`; `next-failure-analysis.md` §3.3-4, §6 |
| 7 | run4 failure (`Failed to stage ksud`) → fixed by patch 2 → success (run6) | **Device-validated** | `run4_logcat.txt:30782-30788`; `bootstrap.c:301-334`; run6 |
| 8 | CVE-2026-43499 patched; CVE-2026-98260 present-unported; CVE-2026-43284 open | **Binary-verified** (kernel Image triage, static) | `docs/SM-S9180-S9180ZHU8GZIF.md` §3, §6.5 |
| 9 | IpSecManager dry-run (SPI/encap/transform/apply/remove) all OK | **Device-validated** (read-only probe APK; not root) | campaign doc §6.5 (`C1-…C4-REMOVE-OK…DRYRUN-COMPLETE`) |
| 10 | DFRoot "SUCCESS" ≠ root obtained (su Oracle required) | **Observed, mechanism understood** | run2 `:1679` (SUCCESS) vs `:1688` (Loading…); run3 `:1535` vs `:1544`; campaign doc §6.6 note |
| 11 | End-to-end oracle `su -c id` → uid 0 / `su -v` / `getenforce` | **Device-validated** — both loaders (run6, run8) | campaign doc §6.7, §6.8 (UID 0; Enforcing; `3.3.0`/`3.4.2` stamps) |
| 12 | `me.weishu.kernelsu` manager path end-to-end | **Not separately validated** (and manager app is userspace-broken; §9.5) | inference from patch 1 only — **unverified** |
| 13 | Auto Soft Reboot behavior on this model | **Unverified/unstable** — one full hard reboot observed | campaign doc §6.6; `README.md` FAQ; §10 |
| 14 | Start on Boot stability | **Unverified** — never run long-term | — |
| 15 | Multi-boot / multi-run reliability (7-day soak) | **Unverified** — exactly 2 successful cold runs to date | — |
| 16 | Exact panic mechanism for runs 2/3 (EL2? KDP? DEFEX? KCFI/SCS mismatch?) | **UNVERIFIED — panic text absent from every artifact** | `manager-compat-analysis.md` §7, §10; `kmsg_195.txt` (XBL-only, `KlogOffset:0x0`) |
| 17 | KDP/RKP/DEFEX enforcement semantics on 34343818 (vs FZG1's 33413713) | **Unmeasured** | campaign doc §5 ("unmeasured"); `samsung-lateload-forensics.md` §6.5 |
| 18 | sepolicy/module interop over long periods (feature 5 error, LSPosed/Zygisk family) | **Partially observed** (one non-fatal feature error; modules' sepolicy loaded) | `run8_logcat.txt:9747-9871` |
| 19 | CVE-2026-98260-dm3q port | **Not attempted** | — |
| 20 | "Both managers" success (classic manager UI + Next UI) | **Half-validated**: both manager APKs present and exercised as *failures*; both **loader lineages** validated as successes (run6 = classic 3.3.0 lineage; run8 = Next 3.4.2 lineage). `me.weishu.kernelsu`-selected success run: **not performed** | §5.1 |
| 21 | Single authoritative nonce-bound final outcome in the non-persistent property `dfroot.final` (root sets, app reads) + truthful rc 0–9 and additive 64; **`/dev/dffinal` is no longer produced** | **UNVALIDATED on device** — host-only: 61 `status_protocol` cases + real-file canonical/permission/atomicity/reader/bootfail/bound+unbound recovery checks; scoped D2 device confirmation of observability only in row 32 | `tests/native/status_protocol_test.c`, `tests/native/final_publish_test.c`; `CHANGELOG.md` round 6 and round 13 |
| 22 | Nonce bound before state write, reserved-nonce `BOOTSTRAP_FAILED` on app-owned `dfbootfail`, nonce-bound `dfrecover` (stale/foreign `ok` rejected), deadline enforced before/after positive recovery and after cleanup before terminal outcomes, `REBOOT_SKIPPED` on NULL/failure/timeout/late evidence, exactly-once cleanup on every exit, `/proc` scan verdict | **UNVALIDATED on device** — host-only at the current head: 61 status cases + `final_publish`, 13 proc-scan cases, **28 ELF** cases, 16+14+10 device-version cases, real-file bootfail/recovery tests; async quiescence and device timing unvalidated | `tests/native/status_protocol_test.c`, `tests/native/proc_scan_policy_test.c`, `tests/native/elf_parser_test.c`, `tests/native/device_version_test.c`, `tests/native/final_publish_test.c`; `CHANGELOG.md` round 8 (historical round-6 count was 26 ELF) |
| 23 | Write-ahead durable `PENDING` preference marker, same-key recovery, sticky fail-closed `WILDCARD`, launch tokens + whole-transaction lock (no success on failed transaction), lifecycle-scoped listeners, tri-state boot UI with persistent `UNKNOWN` row and explicit safe disable (never verified OFF, no auto-enable), rc 9 `/dev/df` gate, rc 8 uncertain-prefs gate, truthful `StatusRc` | **UNVALIDATED on device** — host-only JVM: 39 tests, 0 failures/errors/skips (LaunchGate 4, ListenerLifecycle 10, PreferenceIntegrity 12, PreferencePersister 13); strict CI XML assertion; real UI lifecycle/PackageManager unvalidated | `tests/java/*.java`; `CHANGELOG.md` round 6; §19 |
| 24 | CMake KO policy / packaged bytes / APK packaging / APK content / target-graph / loader-hash suites | **UNVALIDATED on device** — host-only at the current head: **23 / 25 / 3 / 7 / 6 / 16** cases (KO policy / packaged-KO / APK packaging / APK content **7** / target graph / loader hash **16**); 25 packaged-KO cases include same-path arg failures, mutating objcopy via 755 launcher so cases 14a/14b actually execute, input immutability; 6 target-graph cases (case 5 proves wrong-pin failure and correct-pin recovery); stale verified `.so` and stale loader evidence removed before re-check (Phase A) | `tests/cmake/ko_hash_policy_test.cmake`, `tests/cmake/packaged_ko_test.cmake`, `tests/cmake/apk_packaging_gate_test.cmake`, `tests/cmake/apk_content_gate_test.cmake`, `tests/cmake/packaging_target_graph_test.cmake`, `tests/cmake/loader_hash_test.cmake`; `CHANGELOG.md` round 8 (historical round-6/round-8-reviewed counts were 23/25/3/3/6/5 and 23/25/3/3/6) |
| 25 | Suite markers, unconditional host gating and publishing-only gates | **UNVALIDATED on device** — `host-gates` runs all suites with a checkout-SHA assertion on **every** event incl tags; both `build-dfroot` and `build-release` require it, `publish` requires `build-release`. Non-tag runs upload **no APK** (only the `DFRoot-CI-evidence` licence/notice bundle); 7/7 host markers asserted on every event; 3/3 release gates (`apk-gate`, `provenance-identity`, `release-evidence`) plus `distributable-evidence` and a single upload choke point on the `v*` tag path. Signing smoke uses an isolated identity and emits no distributable artifact | `.github/workflows/build.yml`; `CHANGELOG.md` round 6 |
| 26 | Real release APK path with unchanged obsolete pre-CFI pins (`94f8be…` / `9dfb8b…`, 13504 / 13384 B) | **NOT VALIDATED on device / release withheld by policy** — no tag created by us; the byte gate rejects unknown bytes but accepts the two known obsolete pins; reviewed re-pin, P1-08 and device validation are required before a release; provenance is informational, not attestation | `app/src/main/jni/CMakeLists.txt`; `NOTICE`; `CHANGELOG.md` round 6 |
| 27 | Current-increment loader-stage / helper-size host suites and per-APK loader-byte gate (Phase A/B) | **UNVALIDATED on device** — host-only: **40** loader-stage cases (`tests/native/loader_stage_test.c`) plus the helper-size suite (`tests/native/helper_size_test.c`); Phase B extracts both bundled loaders from every final signed APK and checks SHA-256 + size against the CMakeLists.txt pins via the real verifier, with a required nonempty per-APK loader index at the pre-upload choke (release markers 3/3, host markers 7/7). No device run validates the current head | `tests/native/loader_stage_test.c`, `tests/native/helper_size_test.c`, `.github/workflows/build.yml`; `CHANGELOG.md` round 8 |
| 28 | Release APK preserves the reviewed bundled loader bytes (P2-15, Phase 1 `7e18b13`) | **UNVALIDATED on device** — host-only packaging evidence: AGP 9.5 stripped debug symbols from bundled release JNI libs by default, so a release build from `74b12df` packaged `ecac0d2b…` (3,737,888 B) and `ea0505cd…` (4,728,320 B, same size, SHA-only difference) instead of the pins. `packaging.jniLibs` `keepDebugSymbols` for both loaders plus policy regression tests (90 → 92); local release rebuild with a **disposable local test key** produced `DFRoot_4.8-gzif.apk` (`33c3742d…`) with both loader entries exactly once, extracted bytes matching `731c3fa1…` / `7f28f1fb…` and sizes 5,127,584 / 4,728,320 B, real verifier manifest `verified`, and the verbatim P2-14 gate script passing with the pins file and one index row. **Not a signed release**, no tag/publish, no device validation, release job unexecuted; addressed pending final comprehensive review | `/mnt/240G_SSD/opencode_tmp/p2-15-evidence`; `app/build.gradle.kts`; `tests/workflow/test_policy.py`; `CHANGELOG.md` round 11 |

| 29 | Two S23 attempts (2026-11-26) at the pre-fix head `0f0a2ad` reached full root but failed the vendor restore | **Device-observed attempt, NOT a validation of any head** — `su -v` = `3.4.2:KernelSU`, `su -c id` = `uid=0(root) … context=u:r:ksu:s0`, SELinux Enforcing, KSU Next daemon + modules loaded, no panic evidence; `/vendor/lib64/AIAwbModel_v2.2.tflite` restore FAILED (`read_vendor_splice at 0x0: got 0 bytes, status=0x0, errno=0`), app rc **66** = 2 + 64 recovery bit; clean reboot both times. Decisive `logcat2.txt`: helper patch applied 19:58:38, bootstrap ro / SU daemon 19:58:40, cleanup 19:59:49 `F crashdump64: crash_dump.cpp:278] wrong number of args: 3 (expected 6)` then the 0-byte read — page-cache-only patch gone by cleanup so the original `crash_dump64` ran; **exact discard mechanism NOT proven** | `/mnt/240G_SSD/opencode_tmp/s23-final/` (`logcat.txt`, `logcat2.txt`, `app-result2.txt`, `root-probes.txt`, `root-probes2.txt`, `post-reboot.txt`, `post-reboot2.txt`); `CHANGELOG.md` round 12 |
| 30 | P2-16 cleanup-time helper re-apply at the code head `376f5e2` (implementation) | **Host-tested implementation; device retest passed for the restore scope in row 31** — pure `df_helper_block_matches()` predicate above `DF_HELPER_TEST_ONLY`; `ensure_helper_patch()` called at start of `cleanup()` before helper-backed restores, re-applying the same patched helper with the same verification, no second restore record, fail-closed; helper-size suite **7 → 30** cases, all other counts unchanged. **PR as a whole not device-verified, not merge-ready, not release-ready**; no tag/publish. P1-02/P1-07/P1-08 remain OPEN | `CHANGELOG.md` round 12; `NOTICE` P2-16 bullet |
| 31 | Device retest at the code head `376f5e2` (S23, 2026-11-26) — P2-16 restore scope | **DEVICE PASS for the P2-16 restore scope; rc=2 timeout / unbound observation separate and OPEN** — APK sha256 `bd8a17836effcac027b719b61bff0abfea4b309289e47c4d6d9732947e0bcbd9`, **disposable local test key** (no tag/publish); clean `pre3.txt` (Enforcing, no `su`/`/dev/df`/`/dev/dffinal`/`ksud`, empty `persist.log.tag`, vendor 529568 B mtime 2022-01-01); 15:20:48Z full root chain (`su -v` `3.4.2:KernelSU`, `uid=0(root) … u:r:ksu:s0`, Enforcing). `logcat3.txt`: 23:21:59.947 `cleanup: crash_dump64 helper patch missing; re-applying`; 23:21:59.956 `cleanup: crash_dump64 helper patch re-established (verified)`; 23:21:59.958/.959 libbase `restore OK (verified)`; 23:22:01.570 `restore OK (verified) for /vendor/lib64/AIAwbModel_v2.2.tflite`; 23:22:01.578 `restore OK (verified) for /apex/com.android.runtime/bin/crash_dump64`; counts `wrong number of args` 0, `got 0 bytes` 0, `restore FAILED` 0, `recovery-failed bit` 0, `helper patch re-established` 1, vendor `restore OK` 1 → **no bit 64**, restore defect closed on device. **Still `status: timeout waiting for final status (rc=2)` + `cleanup: unbound observation; recovery gate stays closed`, `/dev/dffinal` absent — pre-existing in both pre-fix attempts, not caused by P2-16.** Post-reboot 15:23:08Z (`post-reboot3.txt`): Enforcing, uptime 62.73, no `su`, all checked `/dev/df*` + `ksud` absent, vendor size/mtime unchanged, no panic evidence (pstore empty). P1-02/P1-07/P1-08 remain OPEN (P1-07 restore verification device-confirmed but quiescence/failure-interruption unproven) | `/mnt/240G_SSD/opencode_tmp/s23-final/` (`pre3.txt`, `logcat3.txt`, `logcat3-key.txt`, `root-probes3.txt`, `app-result3.txt`, `post-reboot3.txt`); `CHANGELOG.md` round 12 |
| 32 | Earlier **pre-commit author-local** D2 retest (S23, 2026-11-27; working tree on `d39a278`) — P2-17 final-status observability only | **DEVICE PASS for the final-status-observability scope ONLY** — APK sha256 `a7f6e02ebb44cb39f583ca3c32d1b818ad046be3019ce540ee4bf9be2ec001af`, **disposable local test key** (no tag/publish, no official signing claim). `app-result8b.txt`: `final: ROOT_ONLY - no reboot requested; root not verified` then `status: final status observed (rc=0)`; no unbound observation, no rc=2 timeout. `root-probes8b.txt`: `prop=[dfroot-v2:3fec15b2d9ad766ff5173d857f75bc38:root_only]`, `3.4.2:KernelSU`, `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`, `selinux=Enforcing`. All four restores `restore OK (verified)` (both libbase writes, vendor tflite, `crash_dump64`), helper patch re-established (verified), **no bit 64** — P2-16 restore scope still green. `channel-probe7*.txt`: property context `u:object_r:default_prop:s0`, unprivileged `setprop` denied (`rc=1`), unprivileged shell could read a root-set property; **`/dev/dffinal` is no longer produced**. `post-reboot8.txt`: device not found after reboot (`adb: device <device serial redacted> not found`) — no post-reboot state claim. Counts unchanged (policy 92; helper-size 30; CMake 23/25/3/7/6/16; native 61 + `final_publish`, proc 13, ELF 28, version 16+14+10, loader-stage 40; JVM 39; markers 7/7 + 3/3), `final_publish` gains an unnumbered pure truth-table test; no CI run number is claimed for that earlier pre-commit head — exact-head CI for committed `e823fb4` is 45864085866 (host-only, release/publish SKIPPED, no DFRoot APK uploaded). **Limits:** scoped rc=0 only — not full-chain, quiescence, loader or release validation; author-local evidence, not independent validation; P1-02/P1-07/P1-08 remain OPEN; PR not independently device-verified/merge-ready/release-ready | `/mnt/240G_SSD/opencode_tmp/s23-final/` (`app-result8b.txt`, `root-probes8b.txt`, `logcat8.txt`, `post-reboot8.txt`, `channel-probe7.txt`, `channel-probe7b.txt`, `channel-probe7c.txt`); `CHANGELOG.md` round 13 |
| 33 | Second author-local full run at **committed head `e823fb4`** (S23, 2026-11-27, after the R11 snapshot) — P2-17 final-status observability only | **SCOPED AUTHOR-LOCAL EVIDENCE, NOT independent validation** — APK sha256 `f866b4388f9f4c9e365dbf2936a9b076eab14b6ad2f2b4e9ea3e642080758807`, **disposable local test key** (no tag/publish). `pre9.txt`: clean state **after reconnection** (Enforcing, no `su`, no `/dev/df`, no `/dev/dffinal`, no `ksud`, `dfroot.final` empty, vendor 529568 B mtime 2022-01-01). `app-result9.txt`: helper patch re-established, all four restores `restore OK (verified)` (both libbase writes, vendor tflite, `crash_dump64`), **no bit 64**, `final: ROOT_ONLY - no reboot requested; root not verified` then `status: final status observed (rc=0)`. `root-probes9.txt`: nonce `fdeb49b6704ae77bdd4648bbabfb9d69`, `3.4.2:KernelSU` / `u:r:ksu:s0`, Enforcing. `root-stability9.txt`: after 30 s root/property unchanged, `ksud`=0, pstore empty. **No reboot was requested → no post-reboot evidence; does not supersede the `post-reboot8.txt` caveat in row 32.** Counts unchanged (policy 92; helper-size 30 author-reported; CMake 23/25/3/7/6/16; native 61 + `final_publish`, proc 13, ELF 28, version 16+14+10, loader-stage 40; JVM 39; markers 7/7 + 3/3); exact-head CI for `e823fb4` is run 45864085866 (completed `success`, host-only, release/publish SKIPPED, no DFRoot APK uploaded, no device result). **Limits:** scoped rc=0 only — not full-chain, quiescence, loader or release validation, not independent validation; P1-02/P1-07/P1-08 remain OPEN; PR not independently device-verified/merge-ready/release-ready | `/mnt/240G_SSD/opencode_tmp/s23-final/` (`pre9.txt`, `app-result9.txt`, `root-probes9.txt`, `root-stability9.txt`, `logcat9.txt`, `apk9-sha256.txt`); `CHANGELOG.md` round 13 |

Confidence rollup: **the historical chain worked on this firmware in exactly two cold-boot runs with the Samsung-patched loaders; the two 2026-11-26 pre-fix S23 attempts reached a full root chain but failed the vendor restore (rc 66). The 2026-11-26 retest at code head `376f5e2` passed for the P2-16 restore scope (verified restores, no bit 64), but still ended in rc=2 timeout / unbound observation with `/dev/dffinal` absent — a separate pre-D2 OPEN item. Two **author-local** D2 retests (row 32, pre-commit working tree on `d39a278`; row 33, committed head `e823fb4`) **passed for the P2-17 final-status-observability scope only**: rc=0 via the non-persistent `dfroot.final` property, correct nonce-bound token, all restores verified, no bit 64; **`/dev/dffinal` is no longer produced**. Both are scoped author-local evidence, **not independent validation**; row 33 requested no reboot and supplies no post-reboot evidence, and neither supersedes the `post-reboot8.txt` caveat. Exact-head CI for `e823fb4` is run 45864085866 (completed `success`, host-only, release/publish SKIPPED, no DFRoot APK uploaded, no device result). Those scoped rc=0 results are not a full-chain validation; the PR as a whole is **not independently device-verified, not merge-ready and not release-ready**, and P1-02/P1-07/P1-08 remain OPEN. Every other CVE/module/persistence aspect remains unverified.** A successful host build does not certify safe privileged execution.

---

## 13. Open items / next steps

1. **Soak test**: ≥10 cold boots of the run6/run8 procedure; record `/dev/df*` markers, `su -v`, `getenforce` each time. Needed before any "reliable root" claim.
2. **Start on Boot**: if desired, test one boot with DFRoot Settings → Start on Boot enabled, with `Disable KSU Modules` pre-armed, and Keep a recovery (download mode + stock images) at hand. Do not test Auto Soft Reboot without the same precautions.
3. **Exact-source GZIF KSU build** (optional, high effort): `KernelSU-Next-v3.4.2-samsung-kdp-rkp-defex.patch` applied to a v3.4.2 source tree, built for `android13-5.15` with vermagic `5.15.197-…34343818-abS9180ZHU8GZIF` and/or relying on ksuinit's vermagic rewrite; confirms vs disproves "exact-source-ness" as the differentiator vs the generic build (the generic already works — the exact build would only add margin).
4. **CVE-2026-98260 dm3q port** (parked): bug present, unported; largest potential follow-up if 43284 is patched in a future SMR.
5. **Track SMR-December-2026** (43284 listed High in SMR-Nov; Samsung may ship the fix in the next round — after which this chain dies, per §3).
6. **Publishability**: the `fix/native-exploit-robustness` PR branch is **committed at `e823fb4`**; the older `DFRoot-fzg1/` local port folder remains **uncommitted relative to upstream `diabl0w/DFRoot @ 7a6513a`**. Do not push that older local tree to `origin` (diabl0w's upstream); keep it local or vendor it into a fork before the knowledge walks away.
7. **The classic manager UI**: if the user ever wants `me.weishu.kernelsu` as the *app* again, the Writable-dex defect must be fixed upstream (or a patched classic APK shipped); not a DFRoot problem.
8. **(Optional) capture `KernelSU Next` logs properly** in future evidence files: `adb logcat -d | grep -E 'KernelSU Next|KernelSU|dfroot'` — unfiltered-or-dual-tag always (see §9.2.4).
9. **P1-08 remains OPEN (release gate).** As written against the historical
   loaders, `/data/local/tmp/.ksud-stage` could be swapped by a `shell`-uid
   process before the loader renamed it; a write-ahead preference transaction
   and host-proven launch/boot gating were not a fix — closing it needed a
   **loader rebuild**, not just DFRoot-side edits. Those rebuilt loaders are
   now integrated (root-owned `/data/adb/.ksud-stage`, fd-validated), but the
   gate stays OPEN pending re-review and fresh on-device full-chain validation.
   P1-02 and P1-07 also remain OPEN. P2-13 and P2-14 were addressed in this
   round (Phase A `0832331` / Phase B `0ea022f`) after the round-8 review and
   are **pending final comprehensive review**, not claimed finally closed.
   **P2-15 (release-JNI debug-symbol stripping) was addressed in Phase 1
   (`7e18b13`) via `keepDebugSymbols` for both loaders plus policy regression
   tests (90 → 92), with host-only local-test-key release evidence, and is
   pending final comprehensive review** — not claimed finally closed; no
   device/merge/release claim, no tag and no publish.
   Auto Soft Reboot / Start on Boot also stay disabled by default and
   unvalidated. No device run validates the PR as a whole; no tag was created
   and no release was published.
10. **P2-16: device retest passed for the restore scope; rc=2 timeout /
    unbound observation is a separate OPEN item.** The two 2026-11-26 S23
    attempts at the pre-fix head `0f0a2ad` reached a full root chain but failed
    the `/vendor/lib64/AIAwbModel_v2.2.tflite` restore (rc 66); on the retest of
    the APK from code head `376f5e2` (sha256
    `bd8a17836effcac027b719b61bff0abfea4b309289e47c4d6d9732947e0bcbd9`,
    **disposable local test key**, no tag/publish) the helper patch was
    re-established (verified) at 23:21:59.947/.956 and the vendor (23:22:01.570)
    and `crash_dump64` (23:22:01.578) restores were `restore OK (verified)` with
    no bit 64 — the restore defect is **closed on device** for that scope. The
    run **still** ended `status: timeout waiting for final status (rc=2)` /
    `cleanup: unbound observation; recovery gate stays closed` with
    `/dev/dffinal` absent — pre-existing in both pre-fix attempts, not caused
    by P2-16, and OPEN. The exact page-cache patch-discard mechanism remains
    unproven. P1-02, P1-07 and P1-08 stay OPEN (P1-07 restore verification is
    now device-confirmed but quiescence/failure-interruption remain unproven).
    The PR as a whole is **not device-verified, not merge-ready and not
    release-ready**; no tag, no publish. See `CHANGELOG.md` round 12 and the
    round-12 note at the top of this file.

---

## 14. Summary of this document (15 lines)

1. SM-S9180 `S9180ZHU8GZIF` (dm3q, kalama), Android 17 / One UI 9.0, SPL 2026-10-05, kernel `5.15.197-android13-8-34343818-abS9180ZHU8GZIF`, `CP2A.260605.016`, changelist 34343818, KMI `android13-5.15`, SDK 37 (target built SDK 36).
2. CVE-2026-43499 is **patched** (binary-verified); CVE-2026-98260 present but **unported**; CVE-2026-43284 **open** and is the working route; listed High in SMR-Nov-2026.
3. The chain: IpSecManager ESP transform → `crash_dump64` splicehelper bridge (page-cache CBC write) → `dfroot.ko` staged into `/vendor/lib64/AIAwbModel_v2.2.tflite` → `libc++.so` sentry hook → init tid=1 → `vendor_modprobe` insmod → `selinux_state=false` + DEFEX/AVC neutralized → UMH → `bootstrap` → `.ksud-stage`.
4. The Samsung-patched ksud renames `.ksud-stage`→`/data/adb/ksud` itself, then `init_module()`s its embedded `android13-5.15_kernelsu.ko` (ksuinit vermagic rewrite) — a no-patch-text, kretprobe-fallback module (KDP workqueue creds, DEFEX cred re-sync, RKP never touched).
5. Stock vanilla classic v3.3.0 (run2, KP194) and stock vanilla Next v3.4.2 (run3, KP195) both **panicked** at module load; manager-independence proven; the classic manager is additionally userspace-broken (Writable-dex SIGABRT, seccomp SIGSYS).
6. The pre-packaged Next FZF5 daemon shipped as `libksudnext.so` could never load (embeds `android13-5.15.189_kernelsu.ko`; runtime KMI is `android13-5.15`) — 52 ms exit, no panic; fixed by shipping `ksud-next-generic-kdp` (3.4.2, generic KMIs) instead (17:00 evidence → swapped 17:50 → run8 success).
7. run4 exposed the missing staging step; patch 2 (`mkdir /data/adb` + securely stage `/data/local/tmp/.ksud-stage`, now with `O_EXCL|O_NOFOLLOW`) fixed it; run6 validated end-to-end (`su -v` = `3.3.0:KernelSU`, Enforcing).
8. run8 validated the dual-mode APK with KernelSU-Next selected (`su -v` = `3.4.2:KernelSU`, Enforcing). Both KSU lineages now hardware-proven; both manager APKs present on the unit.
9. Patch 1 (`ExploitRunner.stageAssets()`) prefers bundled Samsung loaders by selected package (`com.rifsxd.ksunext`→`libksudnext.so`; else bundled classic `libksud.so`; else upstream manager APK fallback).
10. Patch 2 (`bootstrap.c`) implements the FZG1 staging contract: create `/data/adb`, securely stage the daemon to `/data/local/tmp/.ksud-stage` (no symlink/TOCTOU), exec it, then publish root/reboot markers.
11. Load-bearing artifacts (historical run6/run8 chain): `app/src/main/jni/ko/dfroot-android13-5.15.ko` (13,504 B, DDK `android13-5.15`; built, not committed), `app/src/main/jniLibs/arm64-v8a/libksud.so` and `libksudnext.so` (then `aab76943…`/`679504dd…`; current bundles are the P1-08 rebuilds `731c3fa1…`/`7f28f1fb…` — see §6 and the round-8 note), and the APK `DFRoot_4.8-gzif.apk` (5.88 MB, `df.root`, "DFRoot GZIF").
12. Build: JDK 21 (toolchain-pinned), SDK 36, NDK **r28c** (`28.2.13676358`), CMake 3.22.1, Gradle **9.9.2** (distribution SHA-256 pinned) with AGP **9.5.0** via the version catalog → `./gradlew :app:assembleRelease` (release needs complete signing material; otherwise the variant is disabled) → `adb install -r`, LAUNCH ROOT — root is **volatile**, gone on reboot; re-run per boot.
13. Troubleshooting: `/dev/df` ⇒ button disabled (rc 9 "already hooked", not rc 0); quarantined preferences ⇒ launch blocked (rc 8); Permissive ⇒ exploit ran, KSU missing; **`getprop dfroot.final`** (the non-persistent property root sets and the app reads; **`/dev/dffinal` is no longer produced**) ⇒ single nonce-bound v2 token (`error` = ksud/staging/nonce failed, `root_only` = no reboot requested/root not verified, `reboot_pending` = attempt awaiting bounded outcome, `reboot_attempt_unknown` = attempt result unknown/rc 7, other `reboot_*` = reboot states); rc ≥ 64 ⇒ "recovery verification failed" appended to any message; dual-tag logcat capture is mandatory (`KernelSU` **and** `KernelSU Next`); Not installed ⇒ module never resident; `/dev/dfm*` are informational progress markers only. **This final-status and Java-gate flow is host-tested but UNVALIDATED on device.**
14. Limitations: volatile only; untested Auto Soft Reboot (hard reboot once) and Start on Boot; exploit could be closed by a Samsung patch at any time; no exact panic text exists for the two stock panics (XBL-only dumps).
15. Confidence: 2/2 recent cold runs fully successful (run6, run8); stock modules 2/2 panics; everything else (98260 port, long-term stability, `me.weishu.kernelsu`-selected run, soak testing) unverified — treat this file as the starting map, not the final word.

---

## 15. Round-2 PR #3 native hardening (host-tested only, device-unvalidated)

**Status: none of this section has been exercised on the S23U.** It is backed
by host unit tests and compile checks only; the hardware ledger in §5 predates
these changes. Do not read a passing host test as a device success claim.

- **Preflight / P1-04.** `exp.c` builds a `struct preflight` before entering
  the patch phase. It performs every read-only check — strict version parsing,
  helper `stat`/`pread` of originals, KO-target length and padding, ELF target
  selection, all hook alignment/branch/first-instruction-copy/overlap checks,
  and all `pread` saves — before the first `patch_file_cbc()`. `exploit(pf)`
  then only consumes precomputed buffers and offsets. Vendor originals are
  captured after the `crash_dump64` helper patch and before the KO write, so
  restore IVs are derived from the pre-write state. If preflight fails, **no
  file has been written** and native returns rc 3.
- **ELF contract / P1-05.** `elf_parser.h` defines
  `ELF_PARSE_OK=0`, `ELF_PARSE_UNSUPPORTED=1`, `ELF_PARSE_INVALID=-1`.
  INVALID is malformed/hostile metadata and is **never** permission to try a
  second candidate; only UNSUPPORTED (e.g. missing symbol, absent `.dynsym`/
  `.dynstr`, or no fitting placement) advances to `libbase.so`. The pure
  `elf_parse_rc_try_next()` rule is the single place that encodes this
  decision and is asserted directly by the host test. All out-parameters are
  zeroed on failure. The parser validates ELF64/LE/AArch64/ET_DYN metadata, rejects
  PN_XNUM/SHN_XINDEX and extended table counts, checks table and section
  ranges, section-type whitelist, `entsize`/`link`/`info` consistency for
  DYNSYM/DYNAMIC/RELA/REL, bounded NUL-terminated names, symbol section and
  vaddr containment, duplicate-name ambiguity and BTI/PAC advance bounds.
- **Placement proof.** `struct elf_layout` records every claimed metadata
  extent (ELF header, phdr/shdr tables, file-backed sections, file-backed
  segments other than the executable PT_LOAD which is the only legal cave
  region). `placement_range_claimed()` must return 0 for the chosen tail or
  zero-run placement, and zero-run candidates must be 4-byte aligned. The
  tail candidate is **derived from the executable PT_LOAD's file end**, never
  from the global EOF after the section-header table: it must be 4-aligned,
  unclaimed, in-file and wholly on that segment's final page, otherwise the
  parser falls back to an unclaimed zero run inside the PT_LOAD. On the host,
  `valid-exec-tail.elf` proves the tail branch, `valid-basic.elf` and
  `valid-tail-unclaimed.elf` prove the cave branch (the latter's `.claim`
  section covers the exec-segment end), and `shdrs-other-page.elf` puts the
  section headers on a different page with 0x200 of trailing global slack to
  prove no out-of-segment EOF tail is ever returned.
- **Strict versions / P2-01.** `device_version.c` accepts only bounded decimal
  digits without signs/leading zeros/overflow and requires token boundaries;
  ambiguous `androidN` tokens are rejected. `read_device_versions()` tags the
  value as `VERSION_PROV_UNAME` or `VERSION_PROV_GETPROP_FALLBACK`; preflight
  rejects the latter and requires an exact `android13`/`5`/`15` KMI match —
  the old fuzzy major/minor KO pairing is gone.
- **Truthful cleanup.** Every started write is recorded with its original
  bytes and restored in reverse order, with an explicit `restore OK/FAILED`
  line per target. `cleanup()` returns -1 if any restore fails; the status-flow
  cleanup callback turns that into `pf.cleanup_failed`, and `main()` downgrades
  a success rc 0 to error rc 1. A partially failed write is still restored.
- **Host verification only.** `make -C tests/native test` runs
  `elf_parser_test` (26 fixtures — historical round-2 count; the current head
  has **28**) and `device_version_test` plus the two
  existing status-protocol tests; `git diff --check` and standalone compiles
  of `elf_parser.c`, `device_version.c` and `exp.c` are clean. Device
  re-validation of the whole chain is still required before any reliability
  or success claim is made.

---

## 16. Historical — round-3 phase 1b native protocol hardening (superseded by §17)

**Status: none of this has run on the S23U.** The evidence below is host unit
tests and compile checks only; the hardware ledger in §5 predates these
changes. This section records the phase's history; §17 is authoritative for the
round-4 cleanup timing, reserved bootfail channel and final test counts.

- **Nonce / v2 token.** `exp.c` generates 128 random bits (`getrandom`, with a
  `/dev/urandom` fallback) and atomically writes 32 lowercase hex characters to
  `/data/user_de/0/df.root/files/dfinv` (0644, `fchmod`, `fsync`, `rename`)
  before arming the hook. `bootstrap.c` strictly validates the file (regular
  file, exactly 32 lowercase hex bytes) at startup. At this phase a
  missing/malformed nonce published `ERROR` with the reserved zero nonce; the
  **round-4 final behavior** instead publishes `BOOTSTRAP_FAILED` under the
  reserved nonce on the separate app-owned `dfbootfail` channel (rc=1), with no
  late-load and no reboot. Final tokens are
  `dfroot-v2:<nonce>:<outcome>\n`; the parser rejects embedded NUL, truncation,
  extra bytes, unknown/`none` outcomes and stale/foreign nonces.
- **Outcomes / rc.** `ROOT_ONLY` now means ONLY "no reboot requested" and is
  terminal immediately. A soft-reboot attempt publishes the non-terminal
  `REBOOT_PENDING` preflight; if the refinement publish fails, it publishes
  `REBOOT_ATTEMPT_UNKNOWN` instead of silently falling back to `ROOT_ONLY`.
  New codes: rc 7 for pending/unknown, rc 9 reserved for the Java "already
  hooked" gate (`DF_RC_ALREADY_HOOKED`), and the additive bit 64 for a failed
  recovery. `df_outcome_to_rc()` maps pending and unknown to 7; `exp.c` ORs 64
  into **every** rc when cleanup failed instead of only downgrading rc 0.
- **Wait semantics (cleanup timing superseded in round 4).** At this phase
  `status_flow_run()` invoked `cleanup_cb` exactly once **before**
  `consumer_await()`; round 4 moved it to exactly once after the first valid
  bound observation (or once at the shared deadline), with recovery polling
  bounded to 5 s and clamped to the remaining deadline. `consumer_await()` uses
  a 70 s budget, a 10 s grace opened only
  by `REBOOT_PENDING`, and an 85 s overall deadline; the grace deadline is
  `min(observation + 10 s, start + 85 s)` and is deliberately **not** clamped to
  the 70 s budget. Timing out while pending yields
  `REBOOT_ATTEMPT_UNKNOWN`. All deadlines are checked monotonic `int64_t`
  values; clock failure fails closed on both sides.
- **Strict scanning.** The pure `proc_scan_policy.{c,h}` classifier maps each
  `/proc/<pid>/cmdline` observation to benign (ENOENT/ESRCH/zero bytes), match
  or incomplete (other errno, or a full buffer with no match). A match wins;
  incomplete prevents "absent" from ever authorizing a reboot. PIDs are
  validated with strict `strtol`. `run_ctx()` and `wait_child_bounded()` use
  bounded `WNOHANG` waits (5 s each) with a clock-checked kill/reap; timeout or
  clock failure returns -1. The producer re-checks the deadline before
  accepting a confirmed-absent late-load.
- **Truthful restore.** `cleanup()` reads every restored region back, compares
  it with the pre-write originals and logs `VERIFICATION FAILED` on any
  mismatch, setting the recovery-failed bit rather than reporting success.
- **Host verification only.** At this phase `make -C tests/native test` ran 34
  status protocol cases; the round-4 final suite runs **49** status cases, 13
  proc-scan verdict cases, real-file canonical publisher/bootfail/recovery
  tests, 26 ELF cases (historical count for that head; the current head has
  **28**) and 16+14+10 device-version cases; standalone `-D_GNU_SOURCE` compiles
  of `bootstrap.c`, `exp.c` (with a stub `DFROOT_SPLICEHELPER_PATH`) and the new
  pure modules, and `git diff --check`, are clean. Device re-validation of the
  whole chain — including the nonce path, the 85 s wait and the v2 token — is
  still required before any reliability or success claim.

---

## 17. Historical — round-4 (2026-11-25) — final host test, lifecycle and failure-path shape

> **Historical section.** Its counts (49 status, 28 JVM, 15 packaged-KO) describe
> the round-4 implementation, not the literal head. The round-13 note at the
> top is authoritative for the committed head `e823fb4` and the current
> validation boundary; §19 below was authoritative for the round-6 head and §18
> is historical.

This section described the round-4 head of `fix/native-exploit-robustness`.
Everything here is **host-proven only**: no host test executes a privileged
payload, and no hardware run validates that head.

### 17.1 App-owned status channels

- `/data/user_de/0/df.root/files/dfinv` holds the fresh 128-bit (32 lowercase
  hex) nonce, written atomically (0644, `fchmod`, `fsync`, `rename`) **before
  the hook is armed or any state write**. `exp.c` refuses ambiguous/stale state.
- `dfbootfail` (with `dfbootfail.tmp`) is an **app-owned** channel, not a
  `/dev/df*` marker. It may carry exactly one canonical token:
  `dfroot-v2:000…0:bootstrap_failed\n` (reserved zero nonce). The consumer
  reads it only when no normal final token parsed; a real-run nonce or any
  other outcome is rejected. The observable result is rc=1 (`ERROR`),
  deliberately **not** rc=2/unknown. Publication is retried a bounded number
  of times; if all attempts fail the consumer reports rc=2 rather than
  inventing success. A stale bootfail token is cleared at invocation entry.
- `dfrecover` (with `dfrecover.tmp`) is the app-owned recovery status. Only the
  exact bytes `ok\n` are accepted: empty, `fail\n`, extra bytes and embedded
  NULs are all rejected.
- A reboot requires ALL of: a confirmed-absent late-load, a successful
  `REBOOT_PENDING` preflight, and a positive `recovery_ok()` gate. A NULL or
  failed gate, or a timeout, publishes `REBOOT_SKIPPED` and can only **disable**
  the reboot. Recovery polling is bounded to 5 s and clamped to the producer's
  remaining deadline: it can delay, never authorize, a reboot.
- Cleanup runs **exactly once**, after the first valid bound status observation
  (or once at the shared deadline if none arrived), on the SAME absolute
  deadline as the poll loop; its time is charged to that deadline and the clock
  is rechecked afterwards. A failed restore sets the additive bit 64 on every
  rc for any outcome. Device timing/quiescence, Android publication and access
  behavior remain UNVALIDATED.

### 17.2 Durable preference transaction and boot UI

- `PreferenceIntegrity.beginWrite(key)` durably publishes a write-ahead
  `PENDING` marker (key + sentinel) **before** the preference commit, using a
  full-write loop with read-back and fsync; `finishWriteSuccess(key)` clears it
  only after a fully validated PENDING→commit cycle of the SAME key.
- An unattributable fault (invalid/unreadable marker, non-regular path, storage
  fault, interrupted thread) latches a sticky fail-closed `WILDCARD` that no
  key can clear. Any marker I/O failure keeps quarantine set; a healthy marker
  is never an oracle that an uncertain commit is safe.
- The boot component is disabled **synchronously at the failure boundary**;
  the UI path may offload the PackageManager call, but the latch stays
  fail-closed either way. The boot switch mirrors actual component state, the
  user can always turn boot OFF, and nothing auto-enables. A framework write is
  accepted only when the actual component state already matches. Listeners are
  lifecycle-scoped (registered/removed with the screen). Real
  PackageManager/fsync and Activity/Fragment recreation are UNVALIDATED.

### 17.3 Final host evidence (round-4 head; historical — see §18)

| Suite | Final count / assertion |
|---|---|
| Native `status_protocol_test` | **49** cases: strict v2 parsing, lifecycle ordering/count, reserved bootfail token, strict recovery status, deadline boundaries |
| Native `final_publish_test` | real temp files: 0644 under a hostile umask, atomic replace, symlink temp sabotage, reserved-nonce bootfail token, strict recovery-status reader |
| Native `proc_scan_policy_test` | **13** cases |
| Native `elf_parser_test` | **26** fixture cases (historical round-4 count; current head has **28**) |
| Native `device_version_test` | **16 kernel + 14 token + 10 release** cases |
| CMake `ko_hash_policy_test` | **23** cases (clean env; simulated `CI` / `GITHUB_ACTIONS` / `DFROOT_RELEASE`; skip rejection; variant authority) |
| CMake `packaged_ko_test` | **15** cases, including reused-path stale manifest/dump invalidation |
| CMake `apk_packaging_gate_test` | **3** cases with a harmless deterministic ZIP fixture (not a real APK) |
| JVM `:app:testDebugUnitTest` | **28 tests, 0 failures/errors/skips** — LaunchGate 4, ListenerLifecycle 5, PreferenceIntegrity 8, PreferencePersister 11; strict CI XML assertion |
| Suite markers (every non-tag CI event) | **7/7**: `marker-native`, `marker-ko-policy`, `marker-packaged-ko`, `marker-apk-packaging-fixture`, `marker-ko-ci-policy`, `marker-jvm`, `marker-junit` |
| Publish gates (`v*` tag path, pre-upload) | `marker-apk-gate` (every APK, per-APK manifest + `so_sha256` binding), `marker-provenance-identity` (required, every record), `marker-release-evidence`, `marker-distributable-evidence`, single upload choke point |

PR/branch/manual runs upload only an explicitly UNVERIFIED Debug APK (with the
licence/notice bundle) and make no release claim. Provenance is informational
evidence, **not** an attestation or an independent reproducible-build proof.
The byte gate rejects unknown bytes but **accepts the two known obsolete pins**;
release is withheld by policy (reviewed re-pin, P1-08, device validation), not
by the hash gate alone, and passing the gate is not device validation. No tag
was created by us. **P1-08 remains OPEN**, and Auto Soft Reboot / Start on Boot
remain disabled by default and unvalidated. run6/run8 and all earlier CI runs
validate only the historical implementation. **A successful build does not
certify safe privileged execution.**

---

## 18. Historical — round-5 (2026-11-25) — post-PR-#1 rebase integration and host shape

> **Historical section.** It superseded §17 for the pre-round-6 head; §19 below
> and the round-6 section at the top of `CHANGELOG.md` were authoritative for
> the then-current head; the round-11 note at the top is authoritative for the
> literal head. Its counts and CI shape describe the round-5 implementation
> only.

This section superseded §17 for the round-5 head of
`fix/native-exploit-robustness`. For the stable-ID dispositions of the nine
carried-forward findings (P1-02, P1-07, P1-08, P2-04, P2-06, P2-07, P2-08,
P2-09, P2-10) see the historical round-5 table in `CHANGELOG.md`. Everything
here is **host-only**: no hardware run validates that head, and a successful
build is not a safety certificate.

**Post-PR-#1 rebase integration.** PR #1 (CI build reliability) is merged into
the base and PR #3 was rebased onto it. PR #1's CI/signing/GPLv3 conveyance,
Gradle 9.9.2 + AGP 9.5.0 version catalog, NDK/DKK digest pinning and Make and
contract tests are integrated. Non-tag events upload a Debug-only
`DFRoot-UNVERIFIED-CI` artifact with the licence/notice bundle and no release
claim; signed release stays tag-only and gated. run6/run8 and all earlier CI
runs (including 45523079469) validate only historical implementations or
pre-rebase heads, never this head. No tag was created by us.

### 18.1 Round-5 host evidence (historical — see §19 for the current head)

| Suite | Final count / assertion |
|---|---|
| Native `status_protocol_test` | **61** cases: strict v2 parsing, lifecycle ordering/count, reserved bootfail token, nonce-bound recovery status, deadline boundaries |
| Native `final_publish_test` | real temp files: canonical token / 0644 under a hostile umask / atomic replace / symlink temp sabotage / strict reader / bootfail / **bound + unbound recovery** checks |
| Native `proc_scan_policy_test` | **13** cases |
| Native `elf_parser_test` | **26** fixture cases (historical round-5 count; current head has **28**) |
| Native `device_version_test` | **16 kernel + 14 token + 10 release** cases |
| CMake `ko_hash_policy_test` | **23** cases (clean env; simulated `CI` / `GITHUB_ACTIONS` / `DFROOT_RELEASE`; skip rejection; variant authority) |
| CMake `packaged_ko_test` | **25** cases (same-path argument failures, mutating `objcopy`, original-input immutability) |
| CMake `apk_packaging_gate_test` | **3** cases with a harmless deterministic ZIP fixture (not a real APK) |
| CMake `apk_content_gate_test` | **3** cases (`libbootstrap.so` present / wrong ABI / missing) — historical round-5 count; current head has **7** |
| CMake `packaging_target_graph_test` | **6** cases (named target `dfroot_packaged` + `bootstrap`, `ALL`, raw bypass, cached, poisoned) |
| JVM `:app:testDebugUnitTest` | **36 tests, 0 failures/errors/skips** — LaunchGate 4, ListenerLifecycle 7, PreferenceIntegrity 12, PreferencePersister 13; strict CI XML assertion of 36/0/0/0 |
| Suite markers (every non-tag CI event) | **7/7**: `marker-native`, `marker-ko-policy`, `marker-packaged-ko`, `marker-apk-packaging-fixture`, `marker-ko-ci-policy`, `marker-jvm`, `marker-junit` |
| Publish gates (`v*` tag path, pre-upload) | **3/3**: `marker-apk-gate` (every APK, per-APK manifest + `so_sha256` binding), `marker-provenance-identity` (required, every record), `marker-release-evidence`, plus nonempty gate index, `marker-distributable-evidence` and a single upload choke point. No required gate uses `continue-on-error`. |

### 18.2 Round-5 hardening detail

- **Recovery channel (P1-02/P1-07).** `dfrecover` is bound to the current run
  nonce: a stale or foreign `ok` is rejected, so syntax is not invocation
  proof. The absolute deadline is enforced before consulting a positive
  recovery result, again after it, and after cleanup before any terminal
  outcome; late or uncertain evidence is always `REBOOT_SKIPPED`. Async
  quiescence and device timing remain UNVALIDATED.
- **Cleanup on every exit (P1-10).** `status_flow_run()` runs cleanup exactly
  once on every path; an `ERR` path uses unbound cleanup, sets the
  recovery-failed bit and returns `DF_RC_UNKNOWN`; fault-injection tests cover
  these paths.
- **Staged build chain (P2-04/P2-09/P2-11).** AGP targets both `dfroot_packaged`
  and `bootstrap`; APK-content gates require `libbootstrap.so` at the exact
  ABI path; evidence is gathered over every `app/.cxx` packaged manifest;
  stale evidence is invalidated before required-argument validation; `objcopy`
  runs only on an isolated copy and the original artifact's immutability is
  proven on success and failure. Pins and sizes are unchanged
  (`94f8be…` / `9dfb8b…`, 13504 / 13384 B); no hash was relaxed.
- **Preference and UI (P2-08/P2-10).** A sticky fail-closed `WILDCARD`, launch
  tokens and a whole-transaction lock mean no success is reported on a failed
  integrity transaction; boot component state is tri-state, with `UNKNOWN`
  never shown as verified `OFF` and never used to accept `OFF`, and nothing
  auto-enables.

### 18.3 Release gates and validation boundary

**P1-08 remains OPEN** (this §18 text is historical; see the round-8 note at
the top of this file): the historical loaders' `/data/local/tmp/.ksud-stage`
could be swapped by a `shell`-uid process before the loader renamed it; closing
it required a loader rebuild, and documentation was not a fix. Those rebuilt
loaders are now integrated but are pending re-review and device validation.
The byte gate rejects unknown bytes
but accepts the two known obsolete pins; release is withheld by policy pending
a reviewed re-pin, P1-08 and device validation. Auto Soft Reboot and Start on
Boot remain disabled by default and unvalidated. All current verification is
host-only; run6/run8 and all earlier CI runs (including 45523079469) validate
only historical implementations or pre-rebase heads. **A successful build does
not certify safe privileged execution.**

---

## 19. Round-6 repair (2026-11-25) — historical round-6 head (`d72838d`)

> **Historical section.** It superseded §18 for the round-6 head only. The
> then-current docs head (`0ea022f`; producer `2b59684`, loader integration
> `413a5cb`, post-review Phase A/B increments `0832331` / `0ea022f`) is covered
> by the round-8/P1-08 note at the top of this file and by `CHANGELOG.md`
> round 8; the current code base is the D2 **committed head `e823fb4`** (parent
> `d39a278` historical; round 12 for `376f5e2`, round 11 for `7e18b13`).
> Counts in §19.1 below are historical counts for the round-6 head, **not**
> literal-head counts.

This section superseded §18 for the round-6 head of
`fix/native-exploit-robustness` (`d72838d`). Everything is **host-only**; no
hardware run validates that head, no tag was created and no release was
published. For the stable-ID disposition table see the round-6 section at the
top of `CHANGELOG.md`.

### 19.1 Post-fix CI status

- **The pre-repair head was RED**, not green. At `4a81aa5`, CI run
  **45558516540** failed at `packaged_ko_test` case **14a** with "permission
  denied": the mutating-objcopy fixture is git mode 100644 and was invoked
  directly. The fix generates a **755 launcher** that calls it with an explicit
  `python3`; cases **14a/14b now actually execute** and pass their strict
  SHA-256 mismatch and recovery assertions.
- Historical round-6-head local host results (**not** current-head counts; see
  the top warning and `CHANGELOG.md` round 8 for `0ea022f`): native **61**
  status cases plus `final_publish` (canonical / permission / atomicity / reader
  / bootfail / bound + unbound recovery), **13** proc-scan, **26** ELF (Phase B
  code head and literal head: **28**), **16 + 14 + 10** device-version; CMake
  **23 / 25 / 3 / 3 / 6**
  (Phase B code head and literal head: **23 / 25 / 3 / 7 / 6 / 16**); JVM
  **39 tests, 0 failures,
  0 errors, 0 skips** (LaunchGate 4, ListenerLifecycle 10,
  PreferenceIntegrity 12, PreferencePersister 13); workflow policy **85 tests /
  202 subtests** (Phase B code head: **90 tests**; literal head: **92 tests**).
  **7/7** host markers and **3/3**
  release markers (plus the nonempty gate index and single upload choke point)
  are asserted. No required gate uses `continue-on-error`.
- All CI runs listed here, including old run 45523079469 and the RED pre-repair
  run 45558516540, are **historical**, not certification of this head. At the
  Phase B code head `0ea022f`, run 45708004129 completed with conclusion
  `success` at re-check — host-only evidence, **not** device validation or
  certification; earlier completed-success runs 45706111735 (`0832331`),
  45702366070 (`36a11bc`) and 45697296267 (`bb83849`) are earlier heads, not
  literal-head certification.

### 19.2 CI graph, distribution and smoke identity

- A new **unconditional** `host-gates` job (`needs: build-lkms`, no `if`,
  checkout-SHA assertion) runs all host suites, the real KO CI policy and the
  JUnit 39/0/0/0 assertion on **every** event including protected tag pushes.
  Both `build-dfroot` and `build-release` require `host-gates`; `publish`
  requires `build-release`. Tag releases cannot proceed without host evidence
  attributable to `GITHUB_SHA`.
- Non-tag events download **no APK**: the Debug build is a compile/content gate
  only. The sole upload is `DFRoot-CI-evidence` (`GPL-3.0.txt`, `LICENSE`,
  `NOTICE`, `THIRD_PARTY_NOTICES.md`). There is no downloadable
  `DFRoot-UNVERIFIED-CI` artifact at this head.
- The signing smoke uses an isolated identity (`df.root.signingsmoke` /
  `4.8-gzif-signingsmoke` / `SigningSmoke-4.8-gzif-signingsmoke.apk`),
  explicitly rejects `DFRoot_*.apk`, still fails closed with no signing
  material, and produces no distributable artifact.
- The verified-copy command removes any stale verified `.so` as its first step;
  a failed re-check leaves no verified copy. Target-graph case 5 proves the
  wrong-pin failure and correct-pin recovery. Pins/sizes are unchanged
  (`94f8be…` / `9dfb8b…`, 13504 / 13384 B).

### 19.3 Release gates and validation boundary

- **P1-08 is OPEN** (round-6 head; historical): the then-bundled loader's
  hardcoded `/data/local/tmp/.ksud-stage` could be swapped by a `shell`-uid
  process; closing it required a loader rebuild, not DFRoot-side edits alone.
  Documentation was not a fix. On the Phase B code head the rebuilt loaders are
  integrated (root-owned `/data/adb/.ksud-stage`) but remain OPEN pending
  re-review and device validation; do not consume or distribute unverified
  privileged content.
- The byte gate rejects UNKNOWN bytes but **accepts the two known obsolete
  pins**; release is withheld by **POLICY** (a reviewed re-pin, P1-08 and device
  validation), not by the hash gate alone. Passing the gate is not device
  validation. Provenance is informational, not an attestation or
  reproducible-build proof.
- **P1-02:** reserved-channel token syntax is not invocation proof; absence of
  evidence is not proof that system activity stopped. **P1-07:** deadline
  checks are host-proven ordering only; device quiescence and verified
  restoration are UNVALIDATED — a release restriction, not a safety claim.
  **P2-10:** persistent `UNKNOWN` row and an explicit safe DISABLE action, no
  auto-enable, real UI lifecycle/PackageManager unvalidated.
- Auto Soft Reboot and Start on Boot remain disabled by default and unvalidated.
  No device verification was performed; this head is not independently
  merge-ready and not release-ready. **A successful build does not certify safe
  privileged execution.**

---

*Original port document produced read-only; §17 (round-4) added 2026-11-25 as a
documentation-only update, §18 (round-5) added 2026-11-25 to match the rebased
head, and §19 (round-6) added 2026-11-25 to match the then-current head and PR #3.
The current-head ledger above was updated to `0ea022f` (post-review Phase A/B),
then to `7e18b13` (round 11 / P2-15), then to the code head `376f5e2` (round 12 /
P2-16) on 2026-11-26, and then to the **committed head `e823fb4`** (parent
`d39a278` historical); the P2-16 restore scope passed its S23 device retest
while the rc=2 timeout / unbound observation was a separate pre-D2 OPEN item.
Round 13 / P2-17 records the D2 design at committed head `e823fb4`, where
`dfroot.final` replaces `/dev/dffinal` (no longer produced): two scoped
**author-local** S23 retests passed for the final-status-observability scope
only (row 32 on the earlier pre-commit tree, row 33 at committed head `e823fb4`);
exact-head CI is run 45864085866 (host-only, release/publish SKIPPED, no device
result). Sections §16–§19 are historical. This documentation-only update added
no code changes and made no new adb run; D2 and the round-13 docs are committed
at `e823fb4`. Log contents referenced by line range only. **Do not commit
host logs or this device's raw dumps anywhere public.***


---

## 22. PR #2 / R8 supporting evidence (integration addendum — no gates closed)

This section preserves PR #2's R8 supporting evidence in the integrated tree.
The PR #3 ledger (§§15–19 above) is authoritative for the native status
protocol (`status_protocol.c` + `final_status_publish.c` as the single
protocol), fd-anchored staging, ELF/KO/loader gates and release policy. PR #2's
run-scoped `run_status.c` / `loader_stage.c` are **not linked** into either
native binary; their honest indeterminate-before-retract rule is expressed in
the integrated code by publishing the indeterminate `REBOOT_ATTEMPT_UNKNOWN`
channel before any definitive fallback when an exit or publication was not
observed.

PR #2's R8 record is author-reported supporting evidence only: one successful
normal-path observation on the reported device/build, a duplicate-click UI
observation, and an interrupted run that showed no success while external
effects reportedly persisted. It does not establish failure-path safety,
artifact provenance, or an authenticated final-status observation. **R2-14,
R2-16, R2-18 and the experimental soft-reboot limitation remain OPEN** with an
owner and a release hold. No device, ADB, payload, privileged-container,
cold-boot or reboot experiment was performed for this integration, and no gate
is claimed closed or reviewer-approved here.
