# packaging_target_graph_test.cmake -- host-only test of the packaged-KO
# two-stage CMake artifact chain used by AGP's named native build.
#
# Run from anywhere:
#   cmake -P tests/cmake/packaging_target_graph_test.cmake
#
# Cases (6):
#   1 named target: AGP's named target set builds BOTH the verified copy (with
#     an authoritative manifest) and the separately linked bootstrap library
#   2 default ALL: a plain `cmake --build` reaches both required artifacts
#   3 raw target bypass: building `dfroot_raw` emits only the UNVERIFIED
#     staged .so; no package-path artifact/manifest/bootstrap appears
#   4 cached build: a second named build stays verified (force re-check)
#   5 cached + poisoned: poisoning the cached package .so AND its manifest is
#     repaired by a named rebuild, with no stale evidence left behind
#   6 production invariant: Gradle names BOTH targets and the raw link stays
#     in a staging dir, so dropping targets() cannot make AGP package the raw
#     unverified/libdfroot.so instead of the verified copy
#
# The fixture project (fixtures/packaging_graph/CMakeLists.txt) mirrors the
# production chain and invokes the REAL verify_packaged_ko.cmake. All inputs
# are harmless deterministic ELF fixtures: no Android build, NDK, device,
# kernel module or signing material is involved.

cmake_minimum_required(VERSION 3.22)

get_filename_component(TEST_DIR "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(FIXTURE_GEN
    "${TEST_DIR}/fixtures/make_packaged_ko_fixture.py" ABSOLUTE)
get_filename_component(FIXTURE_PROJECT
    "${TEST_DIR}/fixtures/packaging_graph" ABSOLUTE)
get_filename_component(APP_DIR "${TEST_DIR}/../../app" ABSOLUTE)
get_filename_component(JNI_DIR "${APP_DIR}/src/main/jni" ABSOLUTE)
get_filename_component(VERIFY_SCRIPT
    "${TEST_DIR}/../../app/src/main/jni/verify_packaged_ko.cmake" ABSOLUTE)
get_filename_component(WORK "${TEST_DIR}/.work/packaging-graph" ABSOLUTE)
file(REMOVE_RECURSE "${WORK}")
file(MAKE_DIRECTORY "${WORK}")

find_program(PYTHON3 NAMES python3 python REQUIRED)
find_program(OBJCOPY NAMES llvm-objcopy objcopy REQUIRED)
message(STATUS "packaging_target_graph_test: python=${PYTHON3}, objcopy=${OBJCOPY}")

function(report_case case_name expectation ok stdout stderr)
    if(ok)
        message(STATUS "PASS: ${case_name}")
    else()
        message(FATAL_ERROR
            "FAIL: ${case_name} (expected ${expectation})\n"
            "stdout:\n${stdout}\nstderr:\n${stderr}")
    endif()
endfunction()

# Harmless deterministic ELF fixtures with a .dfroot_ko data section.
set(GOOD_SO "${WORK}/good.so")
set(GOOD_PAYLOAD "${WORK}/good.bin")
set(CORRUPT_SO "${WORK}/corrupt.so")
set(CORRUPT_PAYLOAD "${WORK}/corrupt.bin")
function(make_fixture so_path payload_path)
    execute_process(
        COMMAND "${PYTHON3}" "${FIXTURE_GEN}"
                --so "${so_path}" --payload-out "${payload_path}" ${ARGN}
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR "FAIL: fixture generation (${so_path}) rc=${rc}\n${out}\n${err}")
    endif()
endfunction()
make_fixture("${GOOD_SO}" "${GOOD_PAYLOAD}")
make_fixture("${CORRUPT_SO}" "${CORRUPT_PAYLOAD}" --corrupt)
file(SHA256 "${GOOD_PAYLOAD}" H_GOOD)
file(SHA256 "${GOOD_SO}" H_GOOD_SO)

function(configure_graph build_dir out_var err_var)
    file(MAKE_DIRECTORY "${build_dir}")
    execute_process(
        COMMAND "${CMAKE_COMMAND}" -S "${FIXTURE_PROJECT}" -B "${build_dir}"
                "-DRAW_SO=${GOOD_SO}"
                "-DPACKAGE_DIR=${build_dir}/package"
                "-DVERIFY_SCRIPT=${VERIFY_SCRIPT}"
                "-DKO_OBJCOPY=${OBJCOPY}"
                "-DKO_PINS=${H_GOOD}"
                "-DKO_SIZES=256"
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    set(${out_var} "${out}" PARENT_SCOPE)
    set(${err_var} "${err}" PARENT_SCOPE)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR "FAIL: packaging graph configure (${build_dir})\n${out}\n${err}")
    endif()
endfunction()

function(build_graph build_dir target rc_var out_var err_var)
    set(args "${CMAKE_COMMAND}" --build "${build_dir}")
    if(NOT "${target}" STREQUAL "")
        list(APPEND args --target "${target}")
    endif()
    execute_process(
        COMMAND ${args}
        RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
    set(${rc_var} "${rc}" PARENT_SCOPE)
    set(${out_var} "${out}" PARENT_SCOPE)
    set(${err_var} "${err}" PARENT_SCOPE)
endfunction()

function(assert_verified build_dir label)
    set(pkg "${build_dir}/package/libdfroot.so")
    set(man "${build_dir}/dfroot-packaged-ko.manifest")
    set(staged "${build_dir}/staging/libdfroot.so")
    set(bootstrap "${build_dir}/package/libbootstrap.so")
    if(NOT EXISTS "${pkg}" OR NOT EXISTS "${man}" OR NOT EXISTS "${staged}" OR
       NOT EXISTS "${bootstrap}")
        message(FATAL_ERROR
            "FAIL: ${label}: verified artifact/manifest/staging .so or required bootstrap .so missing")
    endif()
    if(EXISTS "${man}.verify-work")
        message(FATAL_ERROR "FAIL: ${label}: verifier left its work directory behind")
    endif()
    file(SHA256 "${pkg}" pkg_hash)
    file(SHA256 "${staged}" staged_hash)
    file(SHA256 "${bootstrap}" bootstrap_hash)
    if(NOT pkg_hash STREQUAL H_GOOD_SO OR NOT staged_hash STREQUAL H_GOOD_SO OR
       NOT bootstrap_hash STREQUAL H_GOOD_SO)
        message(FATAL_ERROR
            "FAIL: ${label}: package/staged/bootstrap .so bytes are not the verified fixture")
    endif()
    file(READ "${man}" manifest_text)
    if(NOT manifest_text MATCHES "status: verified" OR
       NOT manifest_text MATCHES "sha256: ${H_GOOD}" OR
       NOT manifest_text MATCHES "so_sha256: ${H_GOOD_SO}" OR
       NOT manifest_text MATCHES "verified_so: .*/package/libdfroot[.]so" OR
       NOT manifest_text MATCHES "bytes: 256")
        message(FATAL_ERROR "FAIL: ${label}: manifest is not fresh verified evidence:\n${manifest_text}")
    endif()
endfunction()

# Production textual invariant: AGP's named list must include BOTH required
# native artifacts, and the raw link must remain in the staging directory.
# Without targets() (or with only dfroot_packaged), AGP can package the raw
# unverified/libdfroot.so or omit the bootstrap library needed at runtime.
function(assert_production_target_invariant)
    file(READ "${APP_DIR}/build.gradle.kts" gradle_text)
    file(READ "${JNI_DIR}/CMakeLists.txt" cmake_text)
    string(REGEX MATCHALL "targets\\(\"dfroot_packaged\", \"bootstrap\"\\)"
           named_target_lines "${gradle_text}")
    list(LENGTH named_target_lines named_count)
    if(NOT named_count EQUAL 2)
        message(FATAL_ERROR
            "FAIL: production Gradle must name both native targets in Debug "
            "and Release (found ${named_count})")
    endif()
    string(FIND "${gradle_text}" [[targets("dfroot_packaged")]] bare_pos)
    if(NOT bare_pos EQUAL -1)
        message(FATAL_ERROR "FAIL: production Gradle names dfroot_packaged without bootstrap")
    endif()
    set(required_fragments
        [[RUNTIME_OUTPUT_DIRECTORY "${PACKAGED_KO_STAGING_DIR}"]]
        [[set(PACKAGED_KO_RAW_SO "${PACKAGED_KO_STAGING_DIR}/libdfroot.so")]]
        [[set(PACKAGED_KO_VERIFIED_SO "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libdfroot.so")]]
        [[add_custom_target(dfroot_packaged ALL DEPENDS "${PACKAGED_KO_VERIFIED_SO}")]]
        [[add_dependencies(dfroot_packaged bootstrap)]]
    )
    foreach(fragment IN LISTS required_fragments)
        string(FIND "${cmake_text}" "${fragment}" found_pos)
        if(found_pos EQUAL -1)
            message(FATAL_ERROR
                "FAIL: production CMake is missing raw/staging invariant: ${fragment}")
        endif()
    endforeach()
    string(FIND "${cmake_text}" [[$<TARGET_FILE:dfroot>]] raw_bypass_pos)
    if(NOT raw_bypass_pos EQUAL -1)
        message(FATAL_ERROR
            "FAIL: production verifier still receives the raw linked artifact directly")
    endif()
endfunction()

# 1. AGP-style named target.
set(B1 "${WORK}/named-target")
configure_graph("${B1}" conf_out conf_err)
build_graph("${B1}" "dfroot_packaged" rc out err)
if(rc EQUAL 0)
    report_case("01 named target builds verified artifact" "success" TRUE "${out}" "${err}")
else()
    report_case("01 named target builds verified artifact" "success" FALSE "${out}" "${err}")
endif()
assert_verified("${B1}" "01")
execute_process(
    COMMAND "${CMAKE_COMMAND}" --build "${B1}" --target help
    OUTPUT_VARIABLE help_out ERROR_VARIABLE help_err)
if(NOT help_out MATCHES "dfroot_packaged" OR NOT help_out MATCHES "dfroot_raw" OR
   NOT help_out MATCHES "dfroot_bootstrap")
    message(FATAL_ERROR "FAIL: 01 target graph does not expose named/raw/bootstrap targets:\n${help_out}\n${help_err}")
endif()

# 2. Default ALL must traverse the same chain on a fresh build directory.
set(B2 "${WORK}/default-all")
configure_graph("${B2}" conf_out conf_err)
build_graph("${B2}" "" rc out err)
if(rc EQUAL 0)
    report_case("02 default ALL builds verified artifact" "success" TRUE "${out}" "${err}")
else()
    report_case("02 default ALL builds verified artifact" "success" FALSE "${out}" "${err}")
endif()
assert_verified("${B2}" "02")

# 3. Building the raw link target alone is a bypass: it emits an unverified
#    staged .so but must NEVER produce a package-path artifact or manifest.
set(B3 "${WORK}/raw-bypass")
configure_graph("${B3}" conf_out conf_err)
build_graph("${B3}" "dfroot_raw" rc out err)
if(rc EQUAL 0)
    report_case("03 raw target emits only staged .so" "success" TRUE "${out}" "${err}")
else()
    report_case("03 raw target emits only staged .so" "success" FALSE "${out}" "${err}")
endif()
if(NOT EXISTS "${B3}/staging/libdfroot.so")
    message(FATAL_ERROR "FAIL: 03 raw target did not emit a staged .so")
endif()
if(EXISTS "${B3}/package/libdfroot.so" OR
   EXISTS "${B3}/package/libbootstrap.so" OR
   EXISTS "${B3}/dfroot-packaged-ko.manifest")
    message(FATAL_ERROR "FAIL: 03 raw bypass produced a package artifact or manifest")
endif()

# 4. Cached build: the force file re-runs verification and keeps the artifact.
build_graph("${B1}" "dfroot_packaged" rc out err)
if(rc EQUAL 0)
    report_case("04 cached named build stays verified" "success" TRUE "${out}" "${err}")
else()
    report_case("04 cached named build stays verified" "success" FALSE "${out}" "${err}")
endif()
assert_verified("${B1}" "04")

# 5. Cached + poisoned: replace the package .so with corrupt bytes and write a
#    stale "verified" manifest. The named rebuild must restore both.
configure_file("${CORRUPT_SO}" "${B1}/package/libdfroot.so" COPYONLY)
file(WRITE "${B1}/dfroot-packaged-ko.manifest"
    "status: verified\npoisoned-stale-evidence\n")
build_graph("${B1}" "dfroot_packaged" rc out err)
if(rc EQUAL 0)
    report_case("05 cached+poisoned rebuild repairs evidence" "success" TRUE "${out}" "${err}")
else()
    report_case("05 cached+poisoned rebuild repairs evidence" "success" FALSE "${out}" "${err}")
endif()
assert_verified("${B1}" "05")
file(READ "${B1}/dfroot-packaged-ko.manifest" manifest_text)
if(manifest_text MATCHES "poisoned-stale-evidence")
    message(FATAL_ERROR "FAIL: 05 stale manifest survived the rebuild")
endif()

# 6. Production source invariant (no CMake build needed).
assert_production_target_invariant()
report_case("06 production named targets keep raw .so out of the APK"
    "both AGP target names + staged raw link" TRUE "" "")

message(STATUS "packaging_target_graph_test: all cases passed")
