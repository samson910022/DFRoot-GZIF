# record_build_provenance.cmake -- informational, best-effort build record.
#
# Invoked by app/src/main/jni/CMakeLists.txt (add_custom_command) after both
# splicehelper and the KO hash gate have run. It records what the build actually
# used (toolchain, inputs, hashes, git HEAD, KO gate outcome) so a release can
# be audited. It is NOT an independent reproducible-build proof: it reports
# toolchain identity and hashes, it does not reproduce or compare a second
# build. Nothing here adds or relaxes a hash pin.
#
# Required -D arguments:
#   PROVENANCE_OUTPUT=<file>
#   SPLICEHELPER=<built binary>
#   SPLICEHELPER_SRC=<source file>
#   KO_VERIFY_MANIFEST=<manifest from verify_ko_sha256.cmake>
#   KO_VERIFY_STAMP=<stamp from verify_ko_sha256.cmake>
# Optional: DFROOT_BUILD_TYPE, DFROOT_SOURCE_DIR, CMAKE_C_COMPILER_ID,
#           CMAKE_C_COMPILER_VERSION, CMAKE_C_COMPILER_TARGET, CMAKE_SYSROOT,
#           CMAKE_C_FLAGS, CMAKE_ASM_FLAGS, DFROOT_CMAKE_VERSION.

foreach(required IN ITEMS PROVENANCE_OUTPUT SPLICEHELPER SPLICEHELPER_SRC
                         KO_VERIFY_MANIFEST KO_VERIFY_STAMP)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
        message(FATAL_ERROR "record_build_provenance: missing -D${required}=... argument")
    endif()
endforeach()

get_filename_component(provenance_script "${CMAKE_CURRENT_LIST_FILE}" ABSOLUTE)
if(NOT DEFINED DFROOT_BUILD_TYPE OR "${DFROOT_BUILD_TYPE}" STREQUAL "")
    set(DFROOT_BUILD_TYPE "Unspecified")
endif()
if(NOT DEFINED DFROOT_SOURCE_DIR OR "${DFROOT_SOURCE_DIR}" STREQUAL "")
    get_filename_component(script_dir "${provenance_script}" DIRECTORY)
    get_filename_component(DFROOT_SOURCE_DIR "${script_dir}/../../.." ABSOLUTE)
endif()

function(hash_or_unavailable out_var path)
    if(EXISTS "${path}" AND NOT IS_DIRECTORY "${path}")
        file(SHA256 "${path}" digest)
        string(TOLOWER "${digest}" digest)
        set(${out_var} "${digest}" PARENT_SCOPE)
    else()
        set(${out_var} "unavailable (${path})" PARENT_SCOPE)
    endif()
endfunction()

hash_or_unavailable(splicehelper_hash "${SPLICEHELPER}")
hash_or_unavailable(splicehelper_src_hash "${SPLICEHELPER_SRC}")

# KO status comes from the hash gate's own manifest (preferred) or stamp.
set(ko_status "unknown")
set(ko_hash "unknown")
set(accepted_pins "unknown")
if(EXISTS "${KO_VERIFY_MANIFEST}")
    file(STRINGS "${KO_VERIFY_MANIFEST}" ko_manifest_lines
         REGEX "^status:|^sha256:|^accepted_pins:")
    foreach(line IN LISTS ko_manifest_lines)
        if(line MATCHES "^status: (.*)$")
            set(ko_status "${CMAKE_MATCH_1}")
        elseif(line MATCHES "^sha256: (.*)$")
            set(ko_hash "${CMAKE_MATCH_1}")
        elseif(line MATCHES "^accepted_pins: (.*)$")
            set(accepted_pins "${CMAKE_MATCH_1}")
        endif()
    endforeach()
elseif(EXISTS "${KO_VERIFY_STAMP}")
    file(READ "${KO_VERIFY_STAMP}" stamp_text)
    string(STRIP "${stamp_text}" stamp_stripped)
    if(stamp_stripped MATCHES "^SKIPPED:")
        set(ko_status "skipped")
    elseif(stamp_stripped MATCHES "^verified ")
        set(ko_status "verified")
        string(REGEX REPLACE "^verified ([0-9a-fA-F]+).*" "\\1" ko_hash "${stamp_stripped}")
        string(TOLOWER "${ko_hash}" ko_hash)
    endif()
endif()

set(git_head "unavailable")
find_package(Git QUIET)
if(GIT_FOUND)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" rev-parse HEAD
        WORKING_DIRECTORY "${DFROOT_SOURCE_DIR}"
        OUTPUT_VARIABLE head_output
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
        RESULT_VARIABLE git_result)
    if(git_result EQUAL 0 AND NOT "${head_output}" STREQUAL "")
        set(git_head "${head_output}")
    endif()
endif()

string(TIMESTAMP provenance_timestamp "%Y-%m-%dT%H:%M:%SZ" UTC)

set(report "# DFRoot build provenance (informational, not a reproducible-build proof)\n")
string(APPEND report
    "timestamp_utc: ${provenance_timestamp}\n"
    "git_head: ${git_head}\n"
    "build_type: ${DFROOT_BUILD_TYPE}\n"
    "cmake_version: ${DFROOT_CMAKE_VERSION}\n"
    "splicehelper_sha256: ${splicehelper_hash}\n"
    "splicehelper_source_sha256: ${splicehelper_src_hash}\n"
    "splicehelper_source: ${SPLICEHELPER_SRC}\n"
    "ko_status: ${ko_status}\n"
    "ko_sha256: ${ko_hash}\n"
    "ko_accepted_pins: ${accepted_pins}\n"
    "ko_verify_manifest: ${KO_VERIFY_MANIFEST}\n"
    "ko_verify_stamp: ${KO_VERIFY_STAMP}\n"
    "compiler_id: ${CMAKE_C_COMPILER_ID}\n"
    "compiler_version: ${CMAKE_C_COMPILER_VERSION}\n"
    "compiler_target: ${CMAKE_C_COMPILER_TARGET}\n"
    "compiler_sysroot: ${CMAKE_SYSROOT}\n"
    "compiler_c_flags: ${CMAKE_C_FLAGS}\n"
    "compiler_asm_flags: ${CMAKE_ASM_FLAGS}\n"
    "provenance_script: ${provenance_script}\n")

get_filename_component(output_dir "${PROVENANCE_OUTPUT}" DIRECTORY)
file(MAKE_DIRECTORY "${output_dir}")
file(WRITE "${PROVENANCE_OUTPUT}" "${report}")
message(STATUS "DFRoot build provenance: ${PROVENANCE_OUTPUT}")
