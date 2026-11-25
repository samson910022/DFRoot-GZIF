# packaged_ko_test.cmake -- host-only tests for verify_packaged_ko.cmake.
#
# Run from anywhere:
#   cmake -P tests/cmake/packaged_ko_test.cmake
#
# Cases (12):
#   1  valid pin verifies and writes a complete manifest
#   2  ANY pin: a two-pin list verifies when the second pin matches
#   3  mismatch against a different pin fails (SHA-256 MISMATCH reason)
#   4  corrupted byte in the packaged section fails
#   5  missing .dfroot_ko section fails (section-not-found reason)
#   6  wrong section size fails (size-mismatch reason)
#   7  unavailable objcopy fails (objcopy-unavailable reason)
#   8  skip flags cannot bypass the gate (no skip option exists)
#   9  failures are fail-closed: no manifest is written
#   10 ONE reused output path: success then corruption failure invalidates the
#      previously verified manifest (no stale "status: verified" survives)
#   11 poisoned stale section dump at that reused path is ignored/cleared
#   12 failure then success at that path regenerates a fresh verified manifest
#
# Fixtures are produced by tests/cmake/fixtures/make_packaged_ko_fixture.py:
# deterministic, harmless ELF64 objects with a .dfroot_ko data section. No
# device, NDK, KO blob or signing material is needed. Each negative case checks
# the specific FATAL reason, not just a non-zero exit code.

cmake_minimum_required(VERSION 3.22)

get_filename_component(TEST_DIR "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(VERIFY_SCRIPT
    "${TEST_DIR}/../../app/src/main/jni/verify_packaged_ko.cmake" ABSOLUTE)
get_filename_component(FIXTURE_GEN
    "${TEST_DIR}/fixtures/make_packaged_ko_fixture.py" ABSOLUTE)
get_filename_component(WORK "${TEST_DIR}/.work/packaged" ABSOLUTE)
file(REMOVE_RECURSE "${WORK}")
file(MAKE_DIRECTORY "${WORK}")

find_program(PYTHON3 NAMES python3 python REQUIRED)
find_program(OBJCOPY NAMES llvm-objcopy objcopy REQUIRED)
message(STATUS "packaged_ko_test: python=${PYTHON3}, objcopy=${OBJCOPY}")

function(report_case case_name expectation ok stdout stderr)
    if(ok)
        message(STATUS "PASS: ${case_name}")
    else()
        message(FATAL_ERROR
            "FAIL: ${case_name} (expected ${expectation})\n"
            "stdout:\n${stdout}\nstderr:\n${stderr}")
    endif()
endfunction()

# Generate one fixture and assert the generator succeeded.
function(make_fixture so_path payload_path)
    execute_process(
        COMMAND "${PYTHON3}" "${FIXTURE_GEN}"
                --so "${so_path}" --payload-out "${payload_path}" ${ARGN}
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR
            "FAIL: fixture generation (${so_path}) rc=${rc}\n"
            "stdout:\n${out}\nstderr:\n${err}")
    endif()
endfunction()

set(GOOD_SO "${WORK}/good.so")
set(GOOD_PAYLOAD "${WORK}/good.bin")
set(CORRUPT_SO "${WORK}/corrupt.so")
set(CORRUPT_PAYLOAD "${WORK}/corrupt.bin")
set(NOSECTION_SO "${WORK}/nosection.so")
set(NOSECTION_PAYLOAD "${WORK}/nosection.bin")
set(SMALL_SO "${WORK}/small.so")
set(SMALL_PAYLOAD "${WORK}/small.bin")

make_fixture("${GOOD_SO}" "${GOOD_PAYLOAD}")
make_fixture("${CORRUPT_SO}" "${CORRUPT_PAYLOAD}" --corrupt)
make_fixture("${NOSECTION_SO}" "${NOSECTION_PAYLOAD}" --no-section)
make_fixture("${SMALL_SO}" "${SMALL_PAYLOAD}" --size 8)

file(SHA256 "${GOOD_PAYLOAD}" H_GOOD)
# A different, well-formed pin that does not match the fixture.
set(OTHER_PAYLOAD "${WORK}/other.bin")
file(WRITE "${OTHER_PAYLOAD}" "a different reviewed pin (test only)")
file(SHA256 "${OTHER_PAYLOAD}" H_OTHER)
set(PINS_ONE "${H_GOOD}")
set(PINS_TWO "${H_OTHER};${H_GOOD}")

# run_case(<rc_var> <out_var> <err_var> <so> <manifest> <pins> [extra -D...]...)
# The pins are quoted at command construction (exactly as in
# ko_hash_policy_test.cmake) so a multi-pin list stays ONE process argument
# with a plain ';' separator; forwarding a pre-built list through ARGN would
# split it into two arguments.
function(run_case rc_var out_var err_var so manifest pins)
    execute_process(
        COMMAND "${CMAKE_COMMAND}"
                "-DPACKAGED_SO=${so}"
                "-DKO_OBJCOPY=${OBJCOPY}"
                "-DKO_EXPECTED_SHA256S=${pins}"
                "-DKO_MANIFEST=${manifest}"
                "-DDFROOT_SOURCE_DIR=${TEST_DIR}/../.."
                ${ARGN} -P "${VERIFY_SCRIPT}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    set(${rc_var} "${rc}" PARENT_SCOPE)
    set(${out_var} "${out}" PARENT_SCOPE)
    set(${err_var} "${err}" PARENT_SCOPE)
endfunction()

# expect_case(<label> <so> <manifest> <pins> [extra -D...]...)
function(expect_case case_name so manifest pins)
    run_case(rc out err "${so}" "${manifest}" "${pins}" ${ARGN})
    if(rc EQUAL 0)
        report_case("${case_name}" "success" TRUE "${out}" "${err}")
    else()
        report_case("${case_name}" "success" FALSE "${out}" "${err}")
    endif()
endfunction()

# expect_failure(<label> <reason_regex> <so> <manifest> <pins> [extra -D...]...)
function(expect_failure case_name reason_regex so manifest pins)
    run_case(rc out err "${so}" "${manifest}" "${pins}" ${ARGN})
    set(combined "${out}\n${err}")
    string(REGEX REPLACE "[\r\n]+" " " combined "${combined}")
    string(REGEX REPLACE "[ \t]+" " " combined "${combined}")
    if(NOT rc EQUAL 0 AND combined MATCHES "${reason_regex}")
        report_case("${case_name}" "failure matching /${reason_regex}/"
                    TRUE "${out}" "${err}")
    else()
        report_case("${case_name}" "failure matching /${reason_regex}/"
                    FALSE "${out}" "${err}")
    endif()
endfunction()

set(RE_MISMATCH "packaged KO SHA-256 MISMATCH")
set(RE_SECTION "section not found")
set(RE_SIZE "packaged KO size mismatch")
set(RE_OBJCOPY "objcopy unavailable")
set(RE_SKIP "no skip option exists")

# 1. Valid pin: success plus a complete manifest with the required fields.
set(MANIFEST1 "${WORK}/case1.manifest")
expect_case("01 valid pin verified" "${GOOD_SO}" "${MANIFEST1}" "${PINS_ONE}")
file(READ "${MANIFEST1}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "sha256: ${H_GOOD}" OR
   NOT manifest_text MATCHES "bytes: 256" OR
   NOT manifest_text MATCHES "section: [.]dfroot_ko" OR
   NOT manifest_text MATCHES "so_sha256: [0-9a-f][0-9a-f]*" OR
   NOT manifest_text MATCHES "accepted_pins: 1" OR
   NOT manifest_text MATCHES "pins: ${H_GOOD}" OR
   NOT manifest_text MATCHES "timestamp_utc: [0-9]" OR
   NOT manifest_text MATCHES "git_head: [0-9a-f][0-9a-f]*")
    message(FATAL_ERROR "FAIL: case 1 manifest missing fields:\n${manifest_text}")
endif()

# 2. ANY pin: two accepted pins, the second one matches the packaged bytes.
set(MANIFEST2 "${WORK}/case2.manifest")
expect_case("02 ANY pin verified (second pin matches)"
    "${GOOD_SO}" "${MANIFEST2}" "${PINS_TWO}")
file(READ "${MANIFEST2}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "accepted_pins: 2" OR
   NOT manifest_text MATCHES "sha256: ${H_GOOD}")
    message(FATAL_ERROR "FAIL: case 2 manifest missing fields:\n${manifest_text}")
endif()

# 3. Mismatch against a different (well-formed) pin.
set(MANIFEST3 "${WORK}/case3.manifest")
expect_failure("03 wrong pin rejected" "${RE_MISMATCH}"
    "${GOOD_SO}" "${MANIFEST3}" "${H_OTHER}")

# 4. One corrupted byte in the packaged section.
set(MANIFEST4 "${WORK}/case4.manifest")
expect_failure("04 corrupted byte rejected" "${RE_MISMATCH}"
    "${CORRUPT_SO}" "${MANIFEST4}" "${PINS_ONE}")

# 5. Section missing entirely.
set(MANIFEST5 "${WORK}/case5.manifest")
expect_failure("05 missing section rejected" "${RE_SECTION}"
    "${NOSECTION_SO}" "${MANIFEST5}" "${PINS_ONE}")

# 6. Wrong section size (hash would match; the size gate must still fire).
set(MANIFEST6 "${WORK}/case6.manifest")
expect_failure("06 wrong size rejected" "${RE_SIZE}"
    "${SMALL_SO}" "${MANIFEST6}" "${PINS_ONE}" "-DKO_EXPECTED_SIZE=256")

# 7. objcopy unavailable (override the discovered path with a missing one).
set(MANIFEST7 "${WORK}/case7.manifest")
expect_failure("07 unavailable objcopy rejected" "${RE_OBJCOPY}"
    "${GOOD_SO}" "${MANIFEST7}" "${PINS_ONE}"
    "-DKO_OBJCOPY=${WORK}/does-not-exist-objcopy")

# 8. Skip flags cannot bypass: the verifier has no skip option at all.
set(MANIFEST8 "${WORK}/case8.manifest")
expect_failure("08a DFROOT_SKIP cannot bypass" "${RE_SKIP}"
    "${GOOD_SO}" "${MANIFEST8}" "${PINS_ONE}" "-DDFROOT_SKIP=ON")
expect_failure("08b DFROOT_SKIP_KO_HASH_CHECK cannot bypass" "${RE_SKIP}"
    "${GOOD_SO}" "${MANIFEST8}" "${PINS_ONE}" "-DDFROOT_SKIP_KO_HASH_CHECK=ON")

# 9. Fail-closed: a rejected verification never leaves a "verified" manifest.
foreach(manifest IN ITEMS "${MANIFEST3}" "${MANIFEST4}" "${MANIFEST5}" "${MANIFEST6}")
    if(EXISTS "${manifest}")
        message(FATAL_ERROR "FAIL: failed case wrote a manifest: ${manifest}")
    endif()
endforeach()
message(STATUS "PASS: 09 failures are fail-closed (no manifest written)")

# Cases 10-12 reuse a SINGLE output path to prove stale evidence cannot survive.
set(REUSE_MANIFEST "${WORK}/reuse.manifest")
set(REUSE_DUMP "${REUSE_MANIFEST}.dfroot_ko.dump")

# 10a. A success at the reused path writes verified evidence. The fixture .so
# hash is read immediately before each run: objcopy --dump-section can rewrite
# its input in place, and the manifest records the SO identity at entry.
file(SHA256 "${GOOD_SO}" H_GOOD_SO)
expect_case("10a success writes manifest at reused path"
    "${GOOD_SO}" "${REUSE_MANIFEST}" "${PINS_ONE}")
if(NOT EXISTS "${REUSE_MANIFEST}")
    message(FATAL_ERROR "FAIL: case 10a did not write a manifest")
endif()
file(READ "${REUSE_MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "so_sha256: ${H_GOOD_SO}")
    message(FATAL_ERROR "FAIL: case 10a manifest missing fields:\n${manifest_text}")
endif()

# 10b. A later failure at the SAME path must remove that manifest (no stale
# "status: verified" left behind), and must still fail for the hash reason.
expect_failure("10b corruption invalidates stale verified manifest" "${RE_MISMATCH}"
    "${CORRUPT_SO}" "${REUSE_MANIFEST}" "${PINS_ONE}")
if(EXISTS "${REUSE_MANIFEST}")
    file(READ "${REUSE_MANIFEST}" stale_text)
    message(FATAL_ERROR "FAIL: case 10b left stale verified manifest:\n${stale_text}")
endif()

# 11. Poison the dump at the reused path with well-formed but wrong bytes. A
# success at the same path must ignore/remove it and write fresh evidence.
file(READ "${OTHER_PAYLOAD}" poison_bytes)
file(WRITE "${REUSE_DUMP}" "${poison_bytes}")
file(SHA256 "${GOOD_SO}" H_GOOD_SO)
expect_case("11 poisoned stale dump ignored at reused path"
    "${GOOD_SO}" "${REUSE_MANIFEST}" "${PINS_ONE}")
if(EXISTS "${REUSE_DUMP}")
    message(FATAL_ERROR "FAIL: case 11 left a stale section dump behind")
endif()
file(READ "${REUSE_MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "sha256: ${H_GOOD}" OR
   NOT manifest_text MATCHES "so_sha256: ${H_GOOD_SO}")
    message(FATAL_ERROR "FAIL: case 11 manifest not freshly verified:\n${manifest_text}")
endif()

# 12. Failure then success at the same path regenerates a complete manifest.
expect_failure("12a section-missing failure at reused path" "${RE_SECTION}"
    "${NOSECTION_SO}" "${REUSE_MANIFEST}" "${PINS_ONE}")
if(EXISTS "${REUSE_MANIFEST}")
    message(FATAL_ERROR "FAIL: case 12a left a manifest after a failure")
endif()
file(SHA256 "${GOOD_SO}" H_GOOD_SO)
expect_case("12b success regenerates fresh manifest at reused path"
    "${GOOD_SO}" "${REUSE_MANIFEST}" "${PINS_ONE}")
file(READ "${REUSE_MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "sha256: ${H_GOOD}" OR
   NOT manifest_text MATCHES "bytes: 256" OR
   NOT manifest_text MATCHES "so_sha256: ${H_GOOD_SO}" OR
   NOT manifest_text MATCHES "accepted_pins: 1" OR
   NOT manifest_text MATCHES "timestamp_utc: [0-9]")
    message(FATAL_ERROR "FAIL: case 12b fresh manifest missing fields:\n${manifest_text}")
endif()

message(STATUS "packaged_ko_test: all cases passed")
