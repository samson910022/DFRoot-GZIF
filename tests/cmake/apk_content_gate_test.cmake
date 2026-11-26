# apk_content_gate_test.cmake -- host-only APK-content gate for the required
# native libbootstrap.so and for the two bundled loader blobs.
#
# Run from anywhere:
#   cmake -P tests/cmake/apk_content_gate_test.cmake
#
# Cases (7):
#   1  APK with lib/arm64-v8a/libbootstrap.so passes the content gate
#   2  APK without that entry fails the gate
#   3  bootstrap present under the WRONG ABI fails the gate (exact path matters)
#   4  the two loader entries are byte-identical to the pinned harmless blobs
#   5  a same-size tampered loader fails with SHA-256 MISMATCH and no evidence
#   6  a missing loader entry fails the gate
#   7  a loader entry under the WRONG ABI fails the gate
#
# These are harmless deterministic ZIP fixtures, not Android APKs or signed
# distributables: no device, signing material or kernel module is involved, and
# no loader is ever executed. Cases 1-3 still run in the existing APK packaging
# gate fixture CI step and share marker-apk-packaging-fixture (7/7 intact): this
# suite adds cases, not a new suite or marker.

cmake_minimum_required(VERSION 3.22)

get_filename_component(TEST_DIR "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(FIXTURE_GEN
    "${TEST_DIR}/fixtures/make_packaged_ko_fixture.py" ABSOLUTE)
get_filename_component(VERIFY_SCRIPT
    "${TEST_DIR}/../../app/src/main/jni/verify_loader_hashes.cmake" ABSOLUTE)
get_filename_component(WORK "${TEST_DIR}/.work/apk-content" ABSOLUTE)
file(REMOVE_RECURSE "${WORK}")
file(MAKE_DIRECTORY "${WORK}")

find_program(PYTHON3 NAMES python3 python REQUIRED)
message(STATUS "apk_content_gate_test: python=${PYTHON3}")

function(report_case case_name expectation ok)
    if(ok)
        message(STATUS "PASS: ${case_name}")
    else()
        message(FATAL_ERROR "FAIL: ${case_name} (expected ${expectation})")
    endif()
endfunction()

# Harmless deterministic ELF fixture bytes for every native library entry.
set(SO "${WORK}/libdfroot.so")
set(PAYLOAD "${WORK}/libdfroot.bin")
execute_process(
    COMMAND "${PYTHON3}" "${FIXTURE_GEN}" --so "${SO}" --payload-out "${PAYLOAD}"
    RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
if(NOT rc EQUAL 0)
    message(FATAL_ERROR "FAIL: fixture generation rc=${rc}\n${out}\n${err}")
endif()

# Two DISTINCT harmless blobs for the loader entries, plus a same-size tampered
# variant of the KSUD blob (only its bytes change, never its size).
set(KSUD_BLOB "${WORK}/libksud.so.fixture")
set(NEXT_BLOB "${WORK}/libksudnext.so.fixture")
set(TAMPERED_KSUD "${WORK}/libksud.so.tampered")
file(WRITE "${KSUD_BLOB}" "harmless libksud.so fixture bytes A\n")
file(WRITE "${NEXT_BLOB}" "harmless libksudnext.so fixture bytes B\n")
file(READ "${KSUD_BLOB}" ksud_text)
string(SUBSTRING "${ksud_text}" 1 -1 ksud_rest)
file(WRITE "${TAMPERED_KSUD}" "X${ksud_rest}")

# Pins are computed from the harmless blobs, exactly as the build-time gate
# reads them from CMakeLists.txt; no privileged or real loader bytes are used.
file(SHA256 "${KSUD_BLOB}" KSUD_HASH)
file(SIZE "${KSUD_BLOB}" KSUD_SIZE)
file(SHA256 "${NEXT_BLOB}" NEXT_HASH)
file(SIZE "${NEXT_BLOB}" NEXT_SIZE)

# make_apk(<apk> <mode>) -- mode: good | missing | wrong-abi | loader-good |
# loader-tampered | loader-missing | loader-wrong-abi.
function(make_apk apk mode)
    set(py [=[
import sys, zipfile
apk, so, ksud, nextso, tampered, mode = sys.argv[1:7]
data = open(so, 'rb').read()
ksud_data = open(ksud, 'rb').read()
next_data = open(nextso, 'rb').read()
if mode == 'loader-tampered':
    ksud_data = open(tampered, 'rb').read()
entries = [('lib/arm64-v8a/libdfroot.so', data),
           ('lib/arm64-v8a/libksudnext.so', next_data)]
if mode == 'loader-missing':
    pass
elif mode == 'loader-wrong-abi':
    entries.append(('lib/x86/libksud.so', ksud_data))
else:
    entries.append(('lib/arm64-v8a/libksud.so', ksud_data))
if mode in ('good', 'loader-good'):
    entries.append(('lib/arm64-v8a/libbootstrap.so', b'harmless bootstrap fixture\n'))
elif mode == 'wrong-abi':
    entries.append(('lib/x86/libbootstrap.so', b'harmless bootstrap fixture\n'))
with zipfile.ZipFile(apk, 'w') as zf:
    for name, blob in entries:
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED
        info.external_attr = (0o100644 << 16)
        zf.writestr(info, blob)
]=])
    execute_process(
        COMMAND "${PYTHON3}" -c "${py}" "${apk}" "${SO}" "${KSUD_BLOB}"
                "${NEXT_BLOB}" "${TAMPERED_KSUD}" "${mode}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR "FAIL: APK fixture (${mode}) rc=${rc}\n${out}\n${err}")
    endif()
endfunction()

# The gate asserted by CI against a distributable APK: the bootstrap library
# must exist at the EXACT native-library path and must be non-empty.
function(apk_has_bootstrap apk result_var)
    set(py [=[
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
name = 'lib/arm64-v8a/libbootstrap.so'
ok = name in z.namelist() and z.getinfo(name).file_size > 0
print('bootstrap entry: ' + name + (' present' if ok else ' ABSENT'))
sys.exit(0 if ok else 1)
]=])
    execute_process(
        COMMAND "${PYTHON3}" -c "${py}" "${apk}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(rc EQUAL 0)
        set(${result_var} TRUE PARENT_SCOPE)
    else()
        set(${result_var} FALSE PARENT_SCOPE)
    endif()
endfunction()

# Extract the two exact loader entries with Python zipfile into a case dir,
# then run the REAL build-time verifier on them with the computed pins. This is
# the same gate the release workflow runs on the uploaded APK entries.
function(check_apk_loader_bytes apk case_dir result_var output_var)
    set(extract [=[
import sys, zipfile
apk, dest = sys.argv[1:3]
with zipfile.ZipFile(apk) as zf:
    zf.extract('lib/arm64-v8a/libksud.so', dest)
    zf.extract('lib/arm64-v8a/libksudnext.so', dest)
]=])
    file(MAKE_DIRECTORY "${case_dir}")
    execute_process(
        COMMAND "${PYTHON3}" -c "${extract}" "${apk}" "${case_dir}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(NOT rc EQUAL 0)
        set(${result_var} FALSE PARENT_SCOPE)
        set(${output_var} "${out}\n${err}" PARENT_SCOPE)
        return()
    endif()

    set(stamp "${case_dir}/loader-hashes.verified")
    set(manifest "${case_dir}/loader-hashes.manifest")
    # Defence in depth: the verifier invalidates these at entry, but a reused
    # case dir must never carry a prior verified record into this check.
    file(REMOVE "${stamp}" "${manifest}")
    execute_process(
        COMMAND "${CMAKE_COMMAND}"
                "-DLOADER_KSUD_FILE=${case_dir}/lib/arm64-v8a/libksud.so"
                "-DLOADER_KSUDNEXT_FILE=${case_dir}/lib/arm64-v8a/libksudnext.so"
                "-DLOADER_KSUD_SHA256=${KSUD_HASH}" "-DLOADER_KSUD_SIZE=${KSUD_SIZE}"
                "-DLOADER_KSUDNEXT_SHA256=${NEXT_HASH}" "-DLOADER_KSUDNEXT_SIZE=${NEXT_SIZE}"
                "-DLOADER_VERIFY_STAMP=${stamp}"
                "-DLOADER_VERIFY_MANIFEST=${manifest}"
                -P "${VERIFY_SCRIPT}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    set(${output_var} "${out}\n${err}" PARENT_SCOPE)
    if(NOT rc EQUAL 0 OR NOT EXISTS "${manifest}" OR NOT EXISTS "${stamp}")
        set(${result_var} FALSE PARENT_SCOPE)
        return()
    endif()
    file(READ "${manifest}" manifest_text)
    if(NOT manifest_text MATCHES "status: verified" OR
       NOT manifest_text MATCHES "ksud_sha256: ${KSUD_HASH}" OR
       NOT manifest_text MATCHES "ksud_size: ${KSUD_SIZE}" OR
       NOT manifest_text MATCHES "ksudnext_sha256: ${NEXT_HASH}" OR
       NOT manifest_text MATCHES "ksudnext_size: ${NEXT_SIZE}")
        set(${result_var} FALSE PARENT_SCOPE)
        return()
    endif()
    set(${result_var} TRUE PARENT_SCOPE)
endfunction()

make_apk("${WORK}/good.apk" good)
make_apk("${WORK}/missing.apk" missing)
make_apk("${WORK}/wrong-abi.apk" wrong-abi)
make_apk("${WORK}/loader-good.apk" loader-good)
make_apk("${WORK}/loader-tampered.apk" loader-tampered)
make_apk("${WORK}/loader-missing.apk" loader-missing)
make_apk("${WORK}/loader-wrong-abi.apk" loader-wrong-abi)

apk_has_bootstrap("${WORK}/good.apk" ok)
report_case("01 APK with arm64-v8a/libbootstrap.so passes" "pass" ${ok})

apk_has_bootstrap("${WORK}/missing.apk" ok)
if(ok)
    report_case("02 APK without libbootstrap.so is rejected" "reject" FALSE)
else()
    report_case("02 APK without libbootstrap.so is rejected" "reject" TRUE)
endif()

apk_has_bootstrap("${WORK}/wrong-abi.apk" ok)
if(ok)
    report_case("03 bootstrap under wrong ABI is rejected" "reject" FALSE)
else()
    report_case("03 bootstrap under wrong ABI is rejected" "reject" TRUE)
endif()

# --- Cases 4-7: real verifier over extracted entries from the ZIP fixtures. ---
set(CASE4 "${WORK}/case4")
check_apk_loader_bytes("${WORK}/loader-good.apk" "${CASE4}" ok4 out4)
report_case("04 bundled loader bytes match pinned harmless blobs" "pass" ${ok4})

set(CASE5 "${WORK}/case5")
check_apk_loader_bytes("${WORK}/loader-tampered.apk" "${CASE5}" ok5 out5)
if(ok5)
    report_case("05 same-size tampered loader is rejected" "reject" FALSE)
else()
    report_case("05 same-size tampered loader is rejected" "reject" TRUE)
endif()
if(NOT out5 MATCHES "SHA-256 MISMATCH")
    message(FATAL_ERROR "FAIL: case 5 did not report a SHA-256 mismatch:\n${out5}")
endif()
if(EXISTS "${CASE5}/loader-hashes.manifest" OR EXISTS "${CASE5}/loader-hashes.verified")
    message(FATAL_ERROR "FAIL: case 5 left verified manifest/stamp after a hash mismatch")
endif()

set(CASE6 "${WORK}/case6")
check_apk_loader_bytes("${WORK}/loader-missing.apk" "${CASE6}" ok6 out6)
if(ok6)
    report_case("06 missing loader entry is rejected" "reject" FALSE)
else()
    report_case("06 missing loader entry is rejected" "reject" TRUE)
endif()

set(CASE7 "${WORK}/case7")
check_apk_loader_bytes("${WORK}/loader-wrong-abi.apk" "${CASE7}" ok7 out7)
if(ok7)
    report_case("07 loader under wrong ABI is rejected" "reject" FALSE)
else()
    report_case("07 loader under wrong ABI is rejected" "reject" TRUE)
endif()

message(STATUS "apk_content_gate_test: all 7 cases passed")
