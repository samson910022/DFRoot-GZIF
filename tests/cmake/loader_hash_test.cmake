# loader_hash_test.cmake -- host-only test of the bundled-loader hash/size gate.
#
# Run from anywhere:
#   cmake -P tests/cmake/loader_hash_test.cmake
#
# It exercises the REAL verify_loader_hashes.cmake with harmless fixture files
# plus copies of the committed loader blobs. It does not execute any loader,
# needs no Android toolchain or device, and makes no release claim.
#
# Cases (5):
#   1  real pins from CMakeLists.txt verify copies of both bundled loaders
#   2  wrong SHA-256 pin fails with a hash-mismatch reason, no manifest
#   3  wrong size pin fails with a size-mismatch reason, no manifest
#   4  missing loader file fails with a not-found reason, no manifest
#   5  reused output path: success writes a verified manifest; a following
#      failure leaves neither that manifest nor a verified stamp behind

cmake_minimum_required(VERSION 3.22)

get_filename_component(TEST_DIR "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(VERIFY_SCRIPT
    "${TEST_DIR}/../../app/src/main/jni/verify_loader_hashes.cmake" ABSOLUTE)
get_filename_component(CMAKE_LIST
    "${TEST_DIR}/../../app/src/main/jni/CMakeLists.txt" ABSOLUTE)
get_filename_component(LOADER_DIR
    "${TEST_DIR}/../../app/src/main/jniLibs/arm64-v8a" ABSOLUTE)
set(WORK "${TEST_DIR}/.work/loader-hash")
file(REMOVE_RECURSE "${WORK}")
file(MAKE_DIRECTORY "${WORK}")

set(ZERO_HASH "0000000000000000000000000000000000000000000000000000000000000000")

function(report_case case_name expectation ok stdout stderr)
    if(ok)
        message(STATUS "PASS: ${case_name}")
    else()
        message(FATAL_ERROR
            "FAIL: ${case_name} (expected ${expectation})\n"
            "stdout:\n${stdout}\nstderr:\n${stderr}")
    endif()
endfunction()

# Read a pin straight from CMakeLists.txt so case 1 tests the ACTUAL reviewed
# values used by the build, never a copy maintained by this test.
function(pin_value out name pattern)
    file(STRINGS "${CMAKE_LIST}" matched REGEX "^set\\(${name} ")
    if(NOT matched)
        message(FATAL_ERROR "FAIL: CMakeLists.txt has no set(${name} ...) pin")
    endif()
    list(GET matched 0 line)
    string(REGEX MATCH "${pattern}" token "${line}")
    if(NOT token)
        message(FATAL_ERROR "FAIL: cannot parse ${name} pin from: ${line}")
    endif()
    string(REGEX REPLACE "^\"|\"$" "" value "${token}")
    set(${out} "${value}" PARENT_SCOPE)
endfunction()

pin_value(PIN_KSUD_HASH LOADER_KSUD_SHA256 "\"[0-9a-f]+\"")
pin_value(PIN_KSUD_SIZE LOADER_KSUD_SIZE "[0-9]+")
pin_value(PIN_KSUDNEXT_HASH LOADER_KSUDNEXT_SHA256 "\"[0-9a-f]+\"")
pin_value(PIN_KSUDNEXT_SIZE LOADER_KSUDNEXT_SIZE "[0-9]+")

function(run_verifier rc_var out_var err_var ksud ksudnext h1 s1 h2 s2 stamp manifest)
    execute_process(
        COMMAND "${CMAKE_COMMAND}"
                "-DLOADER_KSUD_FILE=${ksud}"
                "-DLOADER_KSUDNEXT_FILE=${ksudnext}"
                "-DLOADER_KSUD_SHA256=${h1}"
                "-DLOADER_KSUD_SIZE=${s1}"
                "-DLOADER_KSUDNEXT_SHA256=${h2}"
                "-DLOADER_KSUDNEXT_SIZE=${s2}"
                "-DLOADER_VERIFY_STAMP=${stamp}"
                "-DLOADER_VERIFY_MANIFEST=${manifest}"
                -P "${VERIFY_SCRIPT}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    set(${rc_var} "${rc}" PARENT_SCOPE)
    set(${out_var} "${out}" PARENT_SCOPE)
    set(${err_var} "${err}" PARENT_SCOPE)
endfunction()

function(expect_failure case_name reason_regex ksud ksudnext h1 s1 h2 s2 stamp manifest)
    run_verifier(rc out err "${ksud}" "${ksudnext}" "${h1}" "${s1}" "${h2}" "${s2}" "${stamp}" "${manifest}")
    set(combined "${out}\n${err}")
    string(REGEX REPLACE "[\r\n]+" " " combined "${combined}")
    string(REGEX REPLACE "[ \t]+" " " combined "${combined}")
    if(NOT rc EQUAL 0 AND combined MATCHES "${reason_regex}")
        report_case("${case_name}" "failure matching /${reason_regex}/" TRUE "${out}" "${err}")
    else()
        report_case("${case_name}" "failure matching /${reason_regex}/" FALSE "${out}" "${err}")
    endif()
endfunction()

# --- Case 1: real, reviewed pins verify copies of the committed loaders. ---
set(CASE1_DIR "${WORK}/case1")
file(MAKE_DIRECTORY "${CASE1_DIR}")
set(C1_KSUD "${CASE1_DIR}/libksud.so")
set(C1_NEXT "${CASE1_DIR}/libksudnext.so")
file(COPY "${LOADER_DIR}/libksud.so" DESTINATION "${CASE1_DIR}")
file(COPY "${LOADER_DIR}/libksudnext.so" DESTINATION "${CASE1_DIR}")
file(SHA256 "${C1_KSUD}" copy_ksud_hash)
file(SHA256 "${C1_NEXT}" copy_next_hash)
file(SIZE "${C1_KSUD}" copy_ksud_size)
file(SIZE "${C1_NEXT}" copy_next_size)
if(NOT copy_ksud_hash STREQUAL PIN_KSUD_HASH OR
   NOT copy_next_hash STREQUAL PIN_KSUDNEXT_HASH OR
   NOT copy_ksud_size EQUAL PIN_KSUD_SIZE OR
   NOT copy_next_size EQUAL PIN_KSUDNEXT_SIZE)
    message(FATAL_ERROR
        "FAIL: bundled loader bytes do not match the reviewed pins in "
        "CMakeLists.txt: ksud ${copy_ksud_hash}/${copy_ksud_size} vs "
        "${PIN_KSUD_HASH}/${PIN_KSUD_SIZE}; next ${copy_next_hash}/"
        "${copy_next_size} vs ${PIN_KSUDNEXT_HASH}/${PIN_KSUDNEXT_SIZE}")
endif()
set(C1_STAMP "${CASE1_DIR}/loader-hashes.verified")
set(C1_MANIFEST "${CASE1_DIR}/loader-hashes.manifest")
run_verifier(rc out err "${C1_KSUD}" "${C1_NEXT}" "${PIN_KSUD_HASH}" "${PIN_KSUD_SIZE}" "${PIN_KSUDNEXT_HASH}" "${PIN_KSUDNEXT_SIZE}" "${C1_STAMP}" "${C1_MANIFEST}")
if(rc EQUAL 0)
    report_case("01 reviewed pins verify bundled loaders" "success" TRUE "${out}" "${err}")
else()
    report_case("01 reviewed pins verify bundled loaders" "success" FALSE "${out}" "${err}")
endif()
file(READ "${C1_MANIFEST}" c1_manifest)
if(NOT c1_manifest MATCHES "status: verified" OR
   NOT c1_manifest MATCHES "ksud_sha256: ${PIN_KSUD_HASH}" OR
   NOT c1_manifest MATCHES "ksud_size: ${PIN_KSUD_SIZE}" OR
   NOT c1_manifest MATCHES "ksudnext_sha256: ${PIN_KSUDNEXT_HASH}" OR
   NOT c1_manifest MATCHES "ksudnext_size: ${PIN_KSUDNEXT_SIZE}")
    message(FATAL_ERROR "FAIL: case 1 manifest missing fields:\n${c1_manifest}")
endif()

# Harmless deterministic fixtures for the negative cases: no loader is run.
set(CASE2_DIR "${WORK}/case2")
file(MAKE_DIRECTORY "${CASE2_DIR}")
set(FIX_A "${CASE2_DIR}/fixture-a.bin")
set(FIX_B "${CASE2_DIR}/fixture-b.bin")
file(WRITE "${FIX_A}" "harmless loader-hash fixture A\n")
file(WRITE "${FIX_B}" "harmless loader-hash fixture B\n")
file(SHA256 "${FIX_A}" H_FIX_A)
file(SHA256 "${FIX_B}" H_FIX_B)
file(SIZE "${FIX_A}" S_FIX_A)
file(SIZE "${FIX_B}" S_FIX_B)

# --- Case 2: wrong hash pin fails with a hash-mismatch reason. ---
expect_failure("02 wrong hash rejected" "libksud[.]so FAILED: SHA-256 MISMATCH"
    "${FIX_A}" "${FIX_B}" "${ZERO_HASH}" "${S_FIX_A}" "${H_FIX_B}" "${S_FIX_B}"
    "${CASE2_DIR}/wrong-hash.verified" "${CASE2_DIR}/wrong-hash.manifest")
if(EXISTS "${CASE2_DIR}/wrong-hash.manifest")
    message(FATAL_ERROR "FAIL: case 2 left a verified manifest after a hash mismatch")
endif()

# --- Case 3: wrong size pin fails with a size-mismatch reason. ---
math(EXPR WRONG_SIZE "${S_FIX_A} + 1")
expect_failure("03 wrong size rejected" "libksud[.]so FAILED: size mismatch"
    "${FIX_A}" "${FIX_B}" "${H_FIX_A}" "${WRONG_SIZE}" "${H_FIX_B}" "${S_FIX_B}"
    "${CASE2_DIR}/wrong-size.verified" "${CASE2_DIR}/wrong-size.manifest")
if(EXISTS "${CASE2_DIR}/wrong-size.manifest")
    message(FATAL_ERROR "FAIL: case 3 left a verified manifest after a size mismatch")
endif()

# --- Case 4: missing file fails with a not-found reason. ---
expect_failure("04 missing loader rejected" "libksud[.]so FAILED: file not found"
    "${CASE2_DIR}/missing.so" "${FIX_B}" "${H_FIX_A}" "${S_FIX_A}" "${H_FIX_B}" "${S_FIX_B}"
    "${CASE2_DIR}/missing.verified" "${CASE2_DIR}/missing.manifest")
if(EXISTS "${CASE2_DIR}/missing.manifest")
    message(FATAL_ERROR "FAIL: case 4 left a verified manifest after a missing file")
endif()

# --- Case 5: a reused output path cannot carry an old success forward. ---
set(CASE5_DIR "${WORK}/case5")
file(MAKE_DIRECTORY "${CASE5_DIR}")
set(R_KSUD "${CASE5_DIR}/reused-a.bin")
set(R_NEXT "${CASE5_DIR}/reused-b.bin")
set(R_KSUD_TEXT "harmless reusable fixture A\n")
file(WRITE "${R_KSUD}" "${R_KSUD_TEXT}")
file(WRITE "${R_NEXT}" "harmless reusable fixture B\n")
file(SHA256 "${R_KSUD}" H_R_KSUD)
file(SHA256 "${R_NEXT}" H_R_NEXT)
file(SIZE "${R_KSUD}" S_R_KSUD)
file(SIZE "${R_NEXT}" S_R_NEXT)
set(REUSED_STAMP "${CASE5_DIR}/reused.verified")
set(REUSED_MANIFEST "${CASE5_DIR}/reused.manifest")
run_verifier(rc out err "${R_KSUD}" "${R_NEXT}" "${H_R_KSUD}" "${S_R_KSUD}" "${H_R_NEXT}" "${S_R_NEXT}" "${REUSED_STAMP}" "${REUSED_MANIFEST}")
if(NOT rc EQUAL 0 OR NOT EXISTS "${REUSED_MANIFEST}")
    message(FATAL_ERROR
        "FAIL: case 5 initial valid run did not write a manifest\n"
        "stdout:\n${out}\nstderr:\n${err}")
endif()
file(READ "${REUSED_MANIFEST}" reused_success)
if(NOT reused_success MATCHES "status: verified")
    message(FATAL_ERROR "FAIL: case 5 initial manifest is not a verified record")
endif()
# Same size, different bytes: a hash mismatch (not a size mismatch) must be
# what invalidates the reused path here.
string(SUBSTRING "${R_KSUD_TEXT}" 1 -1 r_ksud_rest)
file(WRITE "${R_KSUD}" "X${r_ksud_rest}")
expect_failure("05 reused path invalidates stale success" "libksud[.]so FAILED: SHA-256 MISMATCH"
    "${R_KSUD}" "${R_NEXT}" "${H_R_KSUD}" "${S_R_KSUD}" "${H_R_NEXT}" "${S_R_NEXT}" "${REUSED_STAMP}" "${REUSED_MANIFEST}")
if(EXISTS "${REUSED_MANIFEST}")
    message(FATAL_ERROR "FAIL: case 5 left a verified manifest at the reused path after failure")
endif()
if(EXISTS "${REUSED_STAMP}")
    message(FATAL_ERROR "FAIL: case 5 left a verified stamp at the reused path after failure")
endif()

message(STATUS "loader_hash_test: all 5 cases passed")
