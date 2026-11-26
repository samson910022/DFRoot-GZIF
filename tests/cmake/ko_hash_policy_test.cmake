# ko_hash_policy_test.cmake -- host-only test of verify_ko_sha256.cmake.
#
# Run from anywhere:
#   cmake -P tests/cmake/ko_hash_policy_test.cmake
#
# 23 cases across 16 numbered groups (some groups have a/b subcases):
# accepted pin 1 / pin 2, mismatch, skip forbidden under CI / GITHUB_ACTIONS /
# DFROOT_RELEASE / Release / Android release variant / no reason, local skip
# allowed (incl. a clean-env variant), missing blob, blob is a directory,
# malformed pins, stable stamp, missing required arguments, a positive verify
# with CI injected, and android_variant manifest recording.
# No device, NDK or signing material is needed.
#
# Every child verifier runs with CI, GITHUB_ACTIONS and DFROOT_RELEASE unset so
# the result does not depend on the parent environment (GitHub Actions exports
# all three). A case that needs a marker re-injects it explicitly *after* the
# unsets. Negative cases assert the specific FATAL reason, not merely a
# non-zero exit code.

cmake_minimum_required(VERSION 3.22)

get_filename_component(TEST_DIR "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(VERIFY_SCRIPT "${TEST_DIR}/../../app/src/main/jni/verify_ko_sha256.cmake" ABSOLUTE)
get_filename_component(WORK "${TEST_DIR}/.work/ko-policy" ABSOLUTE)
file(REMOVE_RECURSE "${WORK}")
file(MAKE_DIRECTORY "${WORK}")

set(KO1 "${WORK}/ko-local.bin")
set(KO2 "${WORK}/ko-ci.bin")
set(KO3 "${WORK}/ko-unknown.bin")
set(BLOB_DIR "${WORK}/blob-is-a-directory")
set(STAMP "${WORK}/verify.stamp")
set(MANIFEST "${WORK}/verify.manifest")
file(WRITE "${KO1}" "known-good local KO artifact (with build-id)\n")
file(WRITE "${KO2}" "known-good CI KO artifact (build-id stripped)\n")
file(WRITE "${KO3}" "unknown blob of unknown provenance\n")
file(SHA256 "${KO1}" H1)
file(SHA256 "${KO2}" H2)
file(MAKE_DIRECTORY "${BLOB_DIR}")

# Prefix for every child `cmake -P` invocation. The unsets must come before
# any re-injected marker so an ambient CI/GH/RELEASE environment cannot make a
# positive case fail (or a marker case accidentally test the wrong marker).
set(CLEAN_ENV "${CMAKE_COMMAND}" -E env
    --unset=CI --unset=GITHUB_ACTIONS --unset=DFROOT_RELEASE)

function(report_case case_name expectation ok stdout stderr)
    if(ok)
        message(STATUS "PASS: ${case_name}")
    else()
        message(FATAL_ERROR
            "FAIL: ${case_name} (expected ${expectation})\n"
            "stdout:\n${stdout}\nstderr:\n${stderr}")
    endif()
endfunction()

# run_case(<label> <env_assignment> <rc_var> <out_var> <err_var> [-D...]...)
# Runs the real verify script with a clean environment and returns rc/out/err
# via the named parent-scope variables. <env_assignment> is an optional
# "VAR=value" argument re-injected after the CLEAN_ENV unsets; pass "" for none.
function(run_case case_name env_assignment rc_var out_var err_var)
    if("${env_assignment}" STREQUAL "")
        execute_process(
            COMMAND ${CLEAN_ENV} "${CMAKE_COMMAND}" "${PINS_ARG}" ${ARGN} -P "${VERIFY_SCRIPT}"
            RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    else()
        execute_process(
            COMMAND ${CLEAN_ENV} "${env_assignment}" "${CMAKE_COMMAND}" "${PINS_ARG}" ${ARGN} -P "${VERIFY_SCRIPT}"
            RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    endif()
    set(${rc_var} "${rc}" PARENT_SCOPE)
    set(${out_var} "${out}" PARENT_SCOPE)
    set(${err_var} "${err}" PARENT_SCOPE)
endfunction()

# expect_case(<label> [-D...]...) -- positive case, clean environment.
function(expect_case case_name)
    run_case("${case_name}" "" rc out err ${ARGN})
    if(rc EQUAL 0)
        report_case("${case_name}" "success" TRUE "${out}" "${err}")
    else()
        report_case("${case_name}" "success" FALSE "${out}" "${err}")
    endif()
endfunction()

# expect_case_env(<label> <VAR=value> [-D...]...) -- positive case with one
# environment marker re-injected after the CLEAN_ENV unsets.
function(expect_case_env case_name env_assignment)
    run_case("${case_name}" "${env_assignment}" rc out err ${ARGN})
    if(rc EQUAL 0)
        report_case("${case_name}" "success" TRUE "${out}" "${err}")
    else()
        report_case("${case_name}" "success" FALSE "${out}" "${err}")
    endif()
endfunction()

# expect_failure(<label> <reason_regex> [ENV <VAR=value>] [-D...]...)
# Negative case: passes only if the child failed AND its combined stdout/stderr
# matches the expected reason. ENV re-injects exactly one marker after the
# unsets (used by the CI/GH/RELEASE skip-policy cases).
function(expect_failure case_name reason_regex)
    set(args ${ARGN})
    set(env_assignment "")
    if(ARGC GREATER 2 AND "${ARGV2}" STREQUAL "ENV")
        list(GET args 0 env_token)
        list(GET args 1 env_assignment)
        list(REMOVE_AT args 0 1)
    endif()
    run_case("${case_name}" "${env_assignment}" rc out err ${args})
    # CMake wraps message() output at a fixed column and indents the wrapped
    # lines, so normalize whitespace before matching a reason phrase.
    set(combined "${out}\n${err}")
    string(REGEX REPLACE "[\r\n]+" " " combined "${combined}")
    string(REGEX REPLACE "[ \t]+" " " combined "${combined}")
    if(NOT rc EQUAL 0 AND combined MATCHES "${reason_regex}")
        report_case("${case_name}" "failure matching /${reason_regex}/" TRUE "${out}" "${err}")
    else()
        report_case("${case_name}" "failure matching /${reason_regex}/" FALSE "${out}" "${err}")
    endif()
endfunction()

# Distinct failure reasons from verify_ko_sha256.cmake. Each negative case
# names one of these so a failure for the wrong reason cannot pass by accident.
set(RE_SHA_MISMATCH "SHA-256 MISMATCH")
set(RE_SKIP_AUTOMATION "skip is forbidden when CI, GITHUB_ACTIONS or DFROOT_RELEASE is set")
set(RE_SKIP_RELEASE "skip is forbidden for DFROOT_BUILD_TYPE=[Rr]elease")
set(RE_SKIP_VARIANT "skip is forbidden for Android release variant")
set(RE_SKIP_REASON "-DDFROOT_SKIP_REASON=<reason> is required")
set(RE_BLOB_MISSING "blob not found")
set(RE_BLOB_DIR "path is a directory")
set(RE_BAD_PIN "contains an invalid SHA-256 pin")
set(RE_MISSING_BUILD_TYPE "missing -DDFROOT_BUILD_TYPE=")
set(RE_MISSING_KO_FILE "missing -DKO_FILE=")

# The pin list is passed as one quoted argument (a semicolon inside a CMake
# list would otherwise split into two process arguments).
set(PINS_ARG "-DKO_EXPECTED_SHA256S=${H1};${H2}")

# Default arguments; later -D values passed to a case override these.
macro(standard_args build_type)
    set(ARGS
        "-DKO_FILE=${KO1}"
        "-DKO_STAMP=${STAMP}"
        "-DKO_VERIFY_MANIFEST=${MANIFEST}"
        "-DDFROOT_BUILD_TYPE=${build_type}"
        "-DDFROOT_SKIP_REASON=local experiment (unit test)")
endmacro()

# 1. First accepted pin verifies. The manifest must also prove that none of
# the automation/release markers leaked in from the parent environment.
standard_args(Debug)
expect_case("01 first pin accepted" ${ARGS})
file(READ "${MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "sha256: ${H1}" OR
   NOT manifest_text MATCHES "accepted_pins: 2" OR
   NOT manifest_text MATCHES "build_type: Debug" OR
   NOT manifest_text MATCHES "android_variant: unspecified" OR
   NOT manifest_text MATCHES "env_CI: unset" OR
   NOT manifest_text MATCHES "env_GITHUB_ACTIONS: unset" OR
   NOT manifest_text MATCHES "env_DFROOT_RELEASE: unset" OR
   NOT manifest_text MATCHES "verify_script: .*verify_ko_sha256.cmake")
    message(FATAL_ERROR "FAIL: manifest missing expected verified fields:\n${manifest_text}")
endif()

# 1b. A positive verify is allowed under CI (only *skip* is forbidden), and
# the manifest records exactly the injected marker.
standard_args(Debug)
expect_case_env("01b verify accepted with CI=true injected" "CI=true" ${ARGS})
file(READ "${MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "sha256: ${H1}" OR
   NOT manifest_text MATCHES "env_CI: set \\(true\\)" OR
   NOT manifest_text MATCHES "env_GITHUB_ACTIONS: unset" OR
   NOT manifest_text MATCHES "env_DFROOT_RELEASE: unset")
    message(FATAL_ERROR "FAIL: CI-injected manifest missing expected fields:\n${manifest_text}")
endif()

# 2. Second accepted pin verifies.
standard_args(Debug)
expect_case("02 second pin accepted" ${ARGS} "-DKO_FILE=${KO2}")
file(READ "${MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "sha256: ${H2}")
    message(FATAL_ERROR "FAIL: second pin manifest has wrong hash:\n${manifest_text}")
endif()

# 3. Unknown blob is rejected for a hash mismatch (not for any other reason).
standard_args(Debug)
expect_failure("03 unknown blob mismatch" "${RE_SHA_MISMATCH}" ${ARGS} "-DKO_FILE=${KO3}")

# 4-6. Skip is forbidden under automation / release markers. Each case injects
# exactly one marker into the otherwise clean child environment.
standard_args(Debug)
expect_failure("04 skip forbidden with CI=true" "${RE_SKIP_AUTOMATION}"
    ENV "CI=true" ${ARGS} "-DDFROOT_SKIP=ON")
expect_failure("05 skip forbidden with GITHUB_ACTIONS=true" "${RE_SKIP_AUTOMATION}"
    ENV "GITHUB_ACTIONS=true" ${ARGS} "-DDFROOT_SKIP=ON")
expect_failure("06 skip forbidden with DFROOT_RELEASE=1" "${RE_SKIP_AUTOMATION}"
    ENV "DFROOT_RELEASE=1" ${ARGS} "-DDFROOT_SKIP=ON")

# 7. Skip is forbidden for a Release build type (case-insensitive).
standard_args(Release)
expect_failure("07 skip forbidden for Release build" "${RE_SKIP_RELEASE}"
    ${ARGS} "-DDFROOT_SKIP=ON")
standard_args(release)
expect_failure("07b skip forbidden for lowercase release build" "${RE_SKIP_RELEASE}"
    ${ARGS} "-DDFROOT_SKIP=ON")

# 8. Skip requires a reason.
standard_args(Debug)
expect_failure("08 skip without reason" "${RE_SKIP_REASON}"
    ${ARGS} "-DDFROOT_SKIP=ON" "-DDFROOT_SKIP_REASON=")

# 9. Local skip with a reason is allowed and recorded.
standard_args(Debug)
expect_case("09 local skip allowed" ${ARGS} "-DDFROOT_SKIP=ON")
file(READ "${STAMP}" skip_stamp)
if(NOT skip_stamp MATCHES "^SKIPPED:")
    message(FATAL_ERROR "FAIL: skip stamp does not say SKIPPED: '${skip_stamp}'")
endif()
file(READ "${MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: skipped" OR
   NOT manifest_text MATCHES "skip_reason: local experiment")
    message(FATAL_ERROR "FAIL: skip manifest missing status/reason:\n${manifest_text}")
endif()

# 9b. The same local skip in a clean environment, checked end to end: stamp,
# status, reason and the explicit absence of all three environment markers.
standard_args(Debug)
set(SKIP_STAMP "${WORK}/skip-clean.stamp")
set(SKIP_MANIFEST "${WORK}/skip-clean.manifest")
expect_case("09b local skip in clean env recorded correctly" ${ARGS}
    "-DDFROOT_SKIP=ON"
    "-DKO_STAMP=${SKIP_STAMP}"
    "-DKO_VERIFY_MANIFEST=${SKIP_MANIFEST}")
file(READ "${SKIP_STAMP}" skip_clean_stamp)
if(NOT skip_clean_stamp MATCHES "^SKIPPED:")
    message(FATAL_ERROR "FAIL: clean-env skip stamp does not say SKIPPED: '${skip_clean_stamp}'")
endif()
file(READ "${SKIP_MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: skipped" OR
   NOT manifest_text MATCHES "skip_reason: local experiment" OR
   NOT manifest_text MATCHES "env_CI: unset" OR
   NOT manifest_text MATCHES "env_GITHUB_ACTIONS: unset" OR
   NOT manifest_text MATCHES "env_DFROOT_RELEASE: unset")
    message(FATAL_ERROR "FAIL: clean-env skip manifest missing fields:\n${manifest_text}")
endif()

# 10-11. The blob must exist and must not be a directory, even when skipping.
standard_args(Debug)
expect_failure("10 missing blob" "${RE_BLOB_MISSING}"
    ${ARGS} "-DKO_FILE=${WORK}/does-not-exist.ko")
expect_failure("11 blob is a directory" "${RE_BLOB_DIR}"
    ${ARGS} "-DKO_FILE=${BLOB_DIR}")
expect_failure("11b blob is a directory even with skip" "${RE_BLOB_DIR}"
    ${ARGS} "-DKO_FILE=${BLOB_DIR}" "-DDFROOT_SKIP=ON")

# 12. Malformed pins fail closed.
standard_args(Debug)
expect_failure("12 malformed pin" "${RE_BAD_PIN}"
    ${ARGS} "-DKO_EXPECTED_SHA256S=deadbeef")

# 13. Stamp is stable across identical runs (no timestamp or reason in it).
standard_args(Debug)
expect_case("13a stamp stability first run" ${ARGS}
    "-DKO_STAMP=${WORK}/stable1.stamp"
    "-DKO_VERIFY_MANIFEST=${WORK}/stable1.manifest")
expect_case("13b stamp stability second run" ${ARGS}
    "-DKO_STAMP=${WORK}/stable2.stamp"
    "-DKO_VERIFY_MANIFEST=${WORK}/stable2.manifest")
file(READ "${WORK}/stable1.stamp" stamp1)
file(READ "${WORK}/stable2.stamp" stamp2)
if(NOT stamp1 STREQUAL stamp2)
    message(FATAL_ERROR "FAIL: stamp changed between identical runs: '${stamp1}' vs '${stamp2}'")
endif()
if(stamp1 MATCHES "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T")
    message(FATAL_ERROR "FAIL: stamp contains a volatile timestamp: '${stamp1}'")
endif()

# 14. Required arguments are enforced by the script itself.
expect_failure("14a missing DFROOT_BUILD_TYPE" "${RE_MISSING_BUILD_TYPE}"
    "-DKO_FILE=${KO1}"
    "-DKO_STAMP=${STAMP}"
    "-DKO_VERIFY_MANIFEST=${MANIFEST}")
expect_failure("14b missing KO_FILE" "${RE_MISSING_KO_FILE}"
    "-DKO_STAMP=${STAMP}"
    "-DKO_VERIFY_MANIFEST=${MANIFEST}"
    "-DDFROOT_BUILD_TYPE=Debug")

# 15. The Android variant is authoritative in the verify script itself:
# a release variant forbids skip even with a stale Debug label and no marker.
# Matching is case-insensitive.
standard_args(Debug)
expect_failure("15 skip forbidden for Android release variant" "${RE_SKIP_VARIANT}"
    ${ARGS} "-DDFROOT_ANDROID_VARIANT=release" "-DDFROOT_SKIP=ON")
expect_failure("15b skip forbidden for uppercase Android release variant" "${RE_SKIP_VARIANT}"
    ${ARGS} "-DDFROOT_ANDROID_VARIANT=RELEASE" "-DDFROOT_SKIP=ON")

# 16. A positive verify records the Android variant in its manifest.
standard_args(Debug)
expect_case("16 Android variant recorded in manifest" ${ARGS}
    "-DDFROOT_ANDROID_VARIANT=Release")
file(READ "${MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "android_variant: Release")
    message(FATAL_ERROR "FAIL: manifest missing android_variant:\n${manifest_text}")
endif()

message(STATUS "ko_hash_policy_test: all cases passed")
