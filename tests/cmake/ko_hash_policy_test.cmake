# ko_hash_policy_test.cmake -- host-only test of verify_ko_sha256.cmake.
#
# Run from anywhere:
#   cmake -P tests/cmake/ko_hash_policy_test.cmake
#
# 18 cases across 14 numbered groups (some groups have a/b subcases):
# accepted pin 1 / pin 2, mismatch, skip forbidden under CI / GITHUB_ACTIONS /
# DFROOT_RELEASE / Release / no reason, local skip allowed, missing blob, blob
# is a directory, malformed pins, stable stamp, missing required arguments.
# No device, NDK or signing material is needed.

cmake_minimum_required(VERSION 3.22)

get_filename_component(TEST_DIR "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(VERIFY_SCRIPT "${TEST_DIR}/../../app/src/main/jni/verify_ko_sha256.cmake" ABSOLUTE)
get_filename_component(WORK "${TEST_DIR}/.work" ABSOLUTE)
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

function(report_case case_name expect_ok ok stdout stderr)
    if(ok)
        message(STATUS "PASS: ${case_name}")
    else()
        message(FATAL_ERROR
            "FAIL: ${case_name} (expected ${expect_ok})\n"
            "stdout:\n${stdout}\nstderr:\n${stderr}")
    endif()
endfunction()

# expect_case(<label> <TRUE|FALSE> [-D...]...)
# Runs the real verify script with the supplied -D arguments; expects success
# when the second argument is TRUE and a non-zero exit otherwise.
function(expect_case case_name expect_ok)
    execute_process(
        COMMAND "${CMAKE_COMMAND}" "${PINS_ARG}" ${ARGN} -P "${VERIFY_SCRIPT}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(expect_ok)
        if(rc EQUAL 0)
            report_case("${case_name}" "${expect_ok}" TRUE "${out}" "${err}")
        else()
            report_case("${case_name}" "${expect_ok}" FALSE "${out}" "${err}")
        endif()
    else()
        if(NOT rc EQUAL 0)
            report_case("${case_name}" "${expect_ok}" TRUE "${out}" "${err}")
        else()
            report_case("${case_name}" "${expect_ok}" FALSE "${out}" "${err}")
        endif()
    endif()
endfunction()

# expect_case_env(<label> <TRUE|FALSE> <VAR=value> [-D...]...)
# Same as expect_case but injects one environment variable via `cmake -E env`.
function(expect_case_env case_name expect_ok env_assignment)
    execute_process(
        COMMAND "${CMAKE_COMMAND}" -E env "${env_assignment}"
                "${CMAKE_COMMAND}" "${PINS_ARG}" ${ARGN} -P "${VERIFY_SCRIPT}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(expect_ok)
        if(rc EQUAL 0)
            report_case("${case_name}" "${expect_ok}" TRUE "${out}" "${err}")
        else()
            report_case("${case_name}" "${expect_ok}" FALSE "${out}" "${err}")
        endif()
    else()
        if(NOT rc EQUAL 0)
            report_case("${case_name}" "${expect_ok}" TRUE "${out}" "${err}")
        else()
            report_case("${case_name}" "${expect_ok}" FALSE "${out}" "${err}")
        endif()
    endif()
endfunction()

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

# 1. First accepted pin verifies.
standard_args(Debug)
expect_case("01 first pin accepted" TRUE ${ARGS})
file(READ "${MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "sha256: ${H1}" OR
   NOT manifest_text MATCHES "accepted_pins: 2" OR
   NOT manifest_text MATCHES "build_type: Debug" OR
   NOT manifest_text MATCHES "env_CI: unset" OR
   NOT manifest_text MATCHES "verify_script: .*verify_ko_sha256.cmake")
    message(FATAL_ERROR "FAIL: manifest missing expected verified fields:\n${manifest_text}")
endif()

# 2. Second accepted pin verifies.
standard_args(Debug)
expect_case("02 second pin accepted" TRUE ${ARGS} "-DKO_FILE=${KO2}")
file(READ "${MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "sha256: ${H2}")
    message(FATAL_ERROR "FAIL: second pin manifest has wrong hash:\n${manifest_text}")
endif()

# 3. Unknown blob is rejected.
standard_args(Debug)
expect_case("03 unknown blob mismatch" FALSE ${ARGS} "-DKO_FILE=${KO3}")

# 4-6. Skip is forbidden under automation / release markers.
standard_args(Debug)
expect_case_env("04 skip forbidden with CI=true" FALSE "CI=true" ${ARGS} "-DDFROOT_SKIP=ON")
expect_case_env("05 skip forbidden with GITHUB_ACTIONS=true" FALSE "GITHUB_ACTIONS=true" ${ARGS} "-DDFROOT_SKIP=ON")
expect_case_env("06 skip forbidden with DFROOT_RELEASE=1" FALSE "DFROOT_RELEASE=1" ${ARGS} "-DDFROOT_SKIP=ON")

# 7. Skip is forbidden for a Release build type (case-insensitive).
standard_args(Release)
expect_case("07 skip forbidden for Release build" FALSE ${ARGS} "-DDFROOT_SKIP=ON")
standard_args(release)
expect_case("07b skip forbidden for lowercase release build" FALSE ${ARGS} "-DDFROOT_SKIP=ON")

# 8. Skip requires a reason.
standard_args(Debug)
expect_case("08 skip without reason" FALSE ${ARGS} "-DDFROOT_SKIP=ON" "-DDFROOT_SKIP_REASON=")

# 9. Local skip with a reason is allowed and recorded.
standard_args(Debug)
expect_case("09 local skip allowed" TRUE ${ARGS} "-DDFROOT_SKIP=ON")
file(READ "${STAMP}" skip_stamp)
if(NOT skip_stamp MATCHES "^SKIPPED:")
    message(FATAL_ERROR "FAIL: skip stamp does not say SKIPPED: '${skip_stamp}'")
endif()
file(READ "${MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: skipped" OR
   NOT manifest_text MATCHES "skip_reason: local experiment")
    message(FATAL_ERROR "FAIL: skip manifest missing status/reason:\n${manifest_text}")
endif()

# 10-11. The blob must exist and must not be a directory, even when skipping.
standard_args(Debug)
expect_case("10 missing blob" FALSE ${ARGS} "-DKO_FILE=${WORK}/does-not-exist.ko")
expect_case("11 blob is a directory" FALSE ${ARGS} "-DKO_FILE=${BLOB_DIR}")
expect_case("11b blob is a directory even with skip" FALSE ${ARGS} "-DKO_FILE=${BLOB_DIR}" "-DDFROOT_SKIP=ON")

# 12. Malformed pins fail closed.
standard_args(Debug)
expect_case("12 malformed pin" FALSE ${ARGS} "-DKO_EXPECTED_SHA256S=deadbeef")

# 13. Stamp is stable across identical runs (no timestamp or reason in it).
standard_args(Debug)
expect_case("13a stamp stability first run" TRUE ${ARGS} "-DKO_STAMP=${WORK}/stable1.stamp" "-DKO_VERIFY_MANIFEST=${WORK}/stable1.manifest")
expect_case("13b stamp stability second run" TRUE ${ARGS} "-DKO_STAMP=${WORK}/stable2.stamp" "-DKO_VERIFY_MANIFEST=${WORK}/stable2.manifest")
file(READ "${WORK}/stable1.stamp" stamp1)
file(READ "${WORK}/stable2.stamp" stamp2)
if(NOT stamp1 STREQUAL stamp2)
    message(FATAL_ERROR "FAIL: stamp changed between identical runs: '${stamp1}' vs '${stamp2}'")
endif()
if(stamp1 MATCHES "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T")
    message(FATAL_ERROR "FAIL: stamp contains a volatile timestamp: '${stamp1}'")
endif()

# 14. Required arguments are enforced by the script itself.
expect_case("14a missing DFROOT_BUILD_TYPE" FALSE
    "-DKO_FILE=${KO1}"
    "-DKO_STAMP=${STAMP}"
    "-DKO_VERIFY_MANIFEST=${MANIFEST}")
expect_case("14b missing KO_FILE" FALSE
    "-DKO_STAMP=${STAMP}"
    "-DKO_VERIFY_MANIFEST=${MANIFEST}"
    "-DDFROOT_BUILD_TYPE=Debug")

message(STATUS "ko_hash_policy_test: all cases passed")
