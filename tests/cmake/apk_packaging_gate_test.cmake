# apk_packaging_gate_test.cmake -- host-only APK extraction + final-byte gate test.
#
# Run from anywhere:
#   cmake -P tests/cmake/apk_packaging_gate_test.cmake
#
# This exercises the SAME plumbing CI uses for a distributable APK (build a ZIP
# with lib/arm64-v8a/libdfroot.so, extract that entry, run the real
# verify_packaged_ko.cmake against it) using harmless, deterministic fixtures.
# It does NOT verify a real Android APK, signing, NDK toolchain or device
# behaviour, and it does not claim release verification.
#
# Cases (3):
#   1  fixture APK: extracted .so matches the fixture and verifies with a
#      complete manifest, including so_sha256 == the extracted .so sha256
#   2  tampered APK: packaged-byte mismatch fails and invalidates a poisoned
#      stale manifest/dump at the same output path
#   3  section-missing APK: section-not-found fails with no manifest

cmake_minimum_required(VERSION 3.22)

get_filename_component(TEST_DIR "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(VERIFY_SCRIPT
    "${TEST_DIR}/../../app/src/main/jni/verify_packaged_ko.cmake" ABSOLUTE)
get_filename_component(FIXTURE_GEN
    "${TEST_DIR}/fixtures/make_packaged_ko_fixture.py" ABSOLUTE)
get_filename_component(WORK "${TEST_DIR}/.work/apk-packaging" ABSOLUTE)
file(REMOVE_RECURSE "${WORK}")
file(MAKE_DIRECTORY "${WORK}")

find_program(PYTHON3 NAMES python3 python REQUIRED)
find_program(OBJCOPY NAMES llvm-objcopy objcopy REQUIRED)
find_program(UNZIP NAMES unzip)
message(STATUS "apk_packaging_gate_test: python=${PYTHON3}, objcopy=${OBJCOPY}, unzip=${UNZIP}")

set(APK_ENTRY "lib/arm64-v8a/libdfroot.so")
set(RE_MISMATCH "packaged KO SHA-256 MISMATCH")
set(RE_SECTION "section not found")

function(report_case case_name expectation ok stdout stderr)
    if(ok)
        message(STATUS "PASS: ${case_name}")
    else()
        message(FATAL_ERROR
            "FAIL: ${case_name} (expected ${expectation})\n"
            "stdout:\n${stdout}\nstderr:\n${stderr}")
    endif()
endfunction()

# make_apk_fixture(<apk> <payload> <so> [extra generator args]...)
function(make_apk_fixture apk payload so)
    execute_process(
        COMMAND "${PYTHON3}" "${FIXTURE_GEN}"
                --so "${so}" --payload-out "${payload}" --apk "${apk}" ${ARGN}
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR
            "FAIL: APK fixture generation (${apk}) rc=${rc}\n"
            "stdout:\n${out}\nstderr:\n${err}")
    endif()
endfunction()

# extract_so(<apk> <out_so>) -- extract exactly the native lib entry CI checks.
function(extract_so apk out_so)
    get_filename_component(dest_dir "${out_so}" DIRECTORY)
    file(MAKE_DIRECTORY "${dest_dir}")
    if(UNZIP)
        execute_process(
            COMMAND "${UNZIP}" -o -q "${apk}" "${APK_ENTRY}" -d "${dest_dir}"
            RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
        if(NOT rc EQUAL 0)
            message(FATAL_ERROR
                "FAIL: unzip ${APK_ENTRY} from ${apk} rc=${rc}\n"
                "stdout:\n${out}\nstderr:\n${err}")
        endif()
        file(RENAME "${dest_dir}/${APK_ENTRY}" "${out_so}")
    else()
        execute_process(
            COMMAND "${PYTHON3}" -c
                "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); open(sys.argv[2],'wb').write(z.read(sys.argv[3]))"
                "${apk}" "${out_so}" "${APK_ENTRY}"
            RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
        if(NOT rc EQUAL 0)
            message(FATAL_ERROR
                "FAIL: python zip extraction ${APK_ENTRY} from ${apk} rc=${rc}\n"
                "stdout:\n${out}\nstderr:\n${err}")
        endif()
    endif()
    if(NOT EXISTS "${out_so}" OR IS_DIRECTORY "${out_so}")
        message(FATAL_ERROR "FAIL: extracted SO missing: ${out_so}")
    endif()
endfunction()

# run_verifier(<rc_var> <out_var> <err_var> <so> <manifest> <pins> <size>)
function(run_verifier rc_var out_var err_var so manifest pins size)
    execute_process(
        COMMAND "${CMAKE_COMMAND}"
                "-DPACKAGED_SO=${so}"
                "-DKO_OBJCOPY=${OBJCOPY}"
                "-DKO_EXPECTED_SHA256S=${pins}"
                "-DKO_EXPECTED_SIZE=${size}"
                "-DKO_MANIFEST=${manifest}"
                "-DDFROOT_SOURCE_DIR=${TEST_DIR}/../.."
                -P "${VERIFY_SCRIPT}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    set(${rc_var} "${rc}" PARENT_SCOPE)
    set(${out_var} "${out}" PARENT_SCOPE)
    set(${err_var} "${err}" PARENT_SCOPE)
endfunction()

function(expect_failure case_name reason_regex so manifest pins size)
    run_verifier(rc out err "${so}" "${manifest}" "${pins}" "${size}")
    set(combined "${out}\n${err}")
    string(REGEX REPLACE "[\r\n]+" " " combined "${combined}")
    string(REGEX REPLACE "[ \t]+" " " combined "${combined}")
    if(NOT rc EQUAL 0 AND combined MATCHES "${reason_regex}")
        report_case("${case_name}" "failure matching /${reason_regex}/" TRUE "${out}" "${err}")
    else()
        report_case("${case_name}" "failure matching /${reason_regex}/" FALSE "${out}" "${err}")
    endif()
endfunction()

# --- Fixtures: clean, tampered and section-missing APK-like archives. ---
set(GOOD_APK "${WORK}/good.apk")
set(GOOD_PAYLOAD "${WORK}/good.bin")
set(GOOD_FIXTURE_SO "${WORK}/good-fixture.so")
set(TAMPER_APK "${WORK}/tampered.apk")
set(TAMPER_PAYLOAD "${WORK}/tampered.bin")
set(TAMPER_FIXTURE_SO "${WORK}/tampered-fixture.so")
set(NOSEC_APK "${WORK}/nosection.apk")
set(NOSEC_PAYLOAD "${WORK}/nosection.bin")
set(NOSEC_FIXTURE_SO "${WORK}/nosection-fixture.so")

make_apk_fixture("${GOOD_APK}" "${GOOD_PAYLOAD}" "${GOOD_FIXTURE_SO}")
make_apk_fixture("${TAMPER_APK}" "${TAMPER_PAYLOAD}" "${TAMPER_FIXTURE_SO}" --corrupt)
make_apk_fixture("${NOSEC_APK}" "${NOSEC_PAYLOAD}" "${NOSEC_FIXTURE_SO}" --no-section)

file(SHA256 "${GOOD_PAYLOAD}" H_GOOD)
file(SHA256 "${GOOD_FIXTURE_SO}" H_FIXTURE_SO)

# 1. Extract from the fixture APK and verify the extracted bytes end to end.
set(EXTRACTED_GOOD "${WORK}/extracted-good/lib/arm64-v8a/libdfroot.so")
extract_so("${GOOD_APK}" "${EXTRACTED_GOOD}")
file(SHA256 "${EXTRACTED_GOOD}" H_EXTRACTED)
if(NOT H_EXTRACTED STREQUAL H_FIXTURE_SO)
    message(FATAL_ERROR
        "FAIL: APK extraction changed the fixture ELF: ${H_EXTRACTED} != ${H_FIXTURE_SO}")
endif()

set(GOOD_MANIFEST "${WORK}/good.manifest")
run_verifier(rc out err "${EXTRACTED_GOOD}" "${GOOD_MANIFEST}" "${H_GOOD}" 256)
if(rc EQUAL 0)
    report_case("01 fixture APK verifies" "success" TRUE "${out}" "${err}")
else()
    report_case("01 fixture APK verifies" "success" FALSE "${out}" "${err}")
endif()
file(READ "${GOOD_MANIFEST}" manifest_text)
if(NOT manifest_text MATCHES "status: verified" OR
   NOT manifest_text MATCHES "sha256: ${H_GOOD}" OR
   NOT manifest_text MATCHES "bytes: 256" OR
   NOT manifest_text MATCHES "section: [.]dfroot_ko" OR
   NOT manifest_text MATCHES "so_sha256: ${H_EXTRACTED}" OR
   NOT manifest_text MATCHES "so_file: ${EXTRACTED_GOOD}" OR
   NOT manifest_text MATCHES "accepted_pins: 1")
    message(FATAL_ERROR "FAIL: case 1 manifest missing fields:\n${manifest_text}")
endif()

# 2. Tampered APK: poisoned stale evidence at the same output path must be
#    invalidated, and the mismatching bytes must fail.
set(EXTRACTED_TAMPER "${WORK}/extracted-tampered/lib/arm64-v8a/libdfroot.so")
extract_so("${TAMPER_APK}" "${EXTRACTED_TAMPER}")
set(TAMPER_MANIFEST "${WORK}/tampered.manifest")
file(WRITE "${TAMPER_MANIFEST}" "status: verified\nsha256: ${H_GOOD}\n")
file(WRITE "${TAMPER_MANIFEST}.dfroot_ko.dump" "poisoned stale section dump")
expect_failure("02 tampered APK mismatch rejected" "${RE_MISMATCH}"
    "${EXTRACTED_TAMPER}" "${TAMPER_MANIFEST}" "${H_GOOD}" 256)
if(EXISTS "${TAMPER_MANIFEST}")
    message(FATAL_ERROR "FAIL: case 2 left a verified manifest after a mismatch")
endif()
# The poisoned dump was invalidated at entry; any dump left by the failed run
# must be freshly extracted bytes (256-byte fixture section), never the stale
# poisoned content.
if(EXISTS "${TAMPER_MANIFEST}.dfroot_ko.dump")
    file(READ "${TAMPER_MANIFEST}.dfroot_ko.dump" fail_dump)
    file(SIZE "${TAMPER_MANIFEST}.dfroot_ko.dump" fail_dump_size)
    if(fail_dump STREQUAL "poisoned stale section dump" OR NOT fail_dump_size EQUAL 256)
        message(FATAL_ERROR "FAIL: case 2 left a stale section dump at the reused path")
    endif()
endif()

# 3. Section-missing APK: no section, no manifest.
set(EXTRACTED_NOSEC "${WORK}/extracted-nosection/lib/arm64-v8a/libdfroot.so")
extract_so("${NOSEC_APK}" "${EXTRACTED_NOSEC}")
set(NOSEC_MANIFEST "${WORK}/nosection.manifest")
expect_failure("03 section-missing APK rejected" "${RE_SECTION}"
    "${EXTRACTED_NOSEC}" "${NOSEC_MANIFEST}" "${H_GOOD}" 256)
if(EXISTS "${NOSEC_MANIFEST}")
    message(FATAL_ERROR "FAIL: case 3 wrote a manifest after section-not-found")
endif()

message(STATUS "apk_packaging_gate_test: all cases passed")
