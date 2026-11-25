# apk_content_gate_test.cmake -- host-only APK-content gate for the required
# native libbootstrap.so.
#
# Run from anywhere:
#   cmake -P tests/cmake/apk_content_gate_test.cmake
#
# Cases (3):
#   1  APK with lib/arm64-v8a/libbootstrap.so passes the content gate
#   2  APK without that entry fails the gate
#   3  bootstrap present under the WRONG ABI fails the gate (exact path matters)
#
# These are harmless deterministic ZIP fixtures, not Android APKs or signed
# distributables: no device, signing material or kernel module is involved.
# The gate itself is a host assertion run in the existing APK packaging gate
# fixture CI step, so it adds no new suite marker.

cmake_minimum_required(VERSION 3.22)

get_filename_component(TEST_DIR "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(FIXTURE_GEN
    "${TEST_DIR}/fixtures/make_packaged_ko_fixture.py" ABSOLUTE)
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

# make_apk(<apk> <mode>) -- mode: good | missing | wrong-abi.
function(make_apk apk mode)
    set(py [=[
import sys, zipfile
apk, so, mode = sys.argv[1:4]
data = open(so, 'rb').read()
entries = ['lib/arm64-v8a/libdfroot.so',
           'lib/arm64-v8a/libksud.so',
           'lib/arm64-v8a/libksudnext.so']
if mode == 'good':
    entries.append('lib/arm64-v8a/libbootstrap.so')
elif mode == 'wrong-abi':
    entries.append('lib/x86/libbootstrap.so')
with zipfile.ZipFile(apk, 'w') as zf:
    for name in entries:
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED
        info.external_attr = (0o100644 << 16)
        zf.writestr(info, data)
]=])
    execute_process(
        COMMAND "${PYTHON3}" -c "${py}" "${apk}" "${SO}" "${mode}"
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

make_apk("${WORK}/good.apk" good)
make_apk("${WORK}/missing.apk" missing)
make_apk("${WORK}/wrong-abi.apk" wrong-abi)

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

message(STATUS "apk_content_gate_test: all cases passed")
