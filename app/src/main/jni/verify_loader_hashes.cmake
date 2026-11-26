# verify_loader_hashes.cmake -- build-time SHA-256 + size gate for the two
# bundled userspace loader blobs (libksud.so, libksudnext.so).
#
# Invoked by app/src/main/jni/CMakeLists.txt (add_custom_command) as:
#   cmake -DLOADER_KSUD_FILE=<.../jniLibs/arm64-v8a/libksud.so> \
#         -DLOADER_KSUDNEXT_FILE=<.../jniLibs/arm64-v8a/libksudnext.so> \
#         -DLOADER_KSUD_SHA256=<64-hex> -DLOADER_KSUD_SIZE=<bytes> \
#         -DLOADER_KSUDNEXT_SHA256=<64-hex> -DLOADER_KSUDNEXT_SIZE=<bytes> \
#         -DLOADER_VERIFY_STAMP=<build>/loader-hashes.verified \
#         -DLOADER_VERIFY_MANIFEST=<build>/loader-hashes.manifest \
#         -P verify_loader_hashes.cmake
#
# This gate is separate from, and does not touch, the KO pin gates. There is
# NO skip option and no environment override: any failure is FATAL. Both output
# paths (manifest and stamp) are INVALIDATED at entry, before ANY validation,
# whenever they are supplied: a previous success at a reused path can never
# survive a later failure as stale "status: verified" evidence. The manifest
# and stamp are written only after a fully successful run (failure is
# fail-closed: no verified evidence, non-zero exit).
#
# Both loaders are committed binaries whose exact bytes were rebuilt from the
# P1-08-patched payloads source and re-reviewed. A size alone can never make an
# unknown hash pass, and a hash alone can never make a wrong-sized blob pass.
# CMake cannot express O_NOFOLLOW directly; as the closest practical equivalent
# the script requires each path to be an existing, non-directory file whose
# fully resolved real path is identical to the path handed to the verifier, so
# a symlink (or a non-canonical alias) is rejected before it is hashed.

cmake_minimum_required(VERSION 3.22)

set(REQUIRED_ARGS
    LOADER_KSUD_FILE LOADER_KSUDNEXT_FILE
    LOADER_KSUD_SHA256 LOADER_KSUD_SIZE
    LOADER_KSUDNEXT_SHA256 LOADER_KSUDNEXT_SIZE
    LOADER_VERIFY_STAMP LOADER_VERIFY_MANIFEST)
# Entry invalidation must happen BEFORE any validation, including the required
# argument check below. Anything at either output path belongs to a prior run.
# Each removal is guarded so an omitted/empty output path fails unambiguously
# via the required-argument check below and never calls file(REMOVE "").
if(DEFINED LOADER_VERIFY_MANIFEST AND NOT "${LOADER_VERIFY_MANIFEST}" STREQUAL "")
    file(REMOVE "${LOADER_VERIFY_MANIFEST}")
endif()
if(DEFINED LOADER_VERIFY_STAMP AND NOT "${LOADER_VERIFY_STAMP}" STREQUAL "")
    file(REMOVE "${LOADER_VERIFY_STAMP}")
endif()

foreach(required IN LISTS REQUIRED_ARGS)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
        message(FATAL_ERROR "verify_loader_hashes: missing -D${required}=... argument")
    endif()
endforeach()

get_filename_component(verify_script "${CMAKE_CURRENT_LIST_FILE}" ABSOLUTE)

# Normalize and validate pins before touching the artifacts. Malformed pins are
# a configuration error, never an excuse to weaken the comparison.
foreach(label IN ITEMS KSUD KSUDNEXT)
    string(TOLOWER "${LOADER_${label}_SHA256}" expected_hash_${label})
    string(LENGTH "${expected_hash_${label}}" expected_hash_len_${label})
    if(NOT expected_hash_len_${label} EQUAL 64 OR
       NOT "${expected_hash_${label}}" MATCHES "^[0-9a-f]+$")
        message(FATAL_ERROR
            "verify_loader_hashes: malformed LOADER_${label}_SHA256 pin "
            "('${LOADER_${label}_SHA256}'); expected exactly 64 lowercase hex digits")
    endif()
    if(NOT "${LOADER_${label}_SIZE}" MATCHES "^[0-9]+$")
        message(FATAL_ERROR
            "verify_loader_hashes: malformed LOADER_${label}_SIZE pin "
            "('${LOADER_${label}_SIZE}'); expected a non-negative byte count")
    endif()
endforeach()

# check_loader(<label> <display-name> <file> <expected-hash> <expected-size>
#              <hash-out> <size-out>)
# Each rejection has a distinct reason so a host test (and a human) can tell a
# missing file from a symlink/directory, a size mismatch and a hash mismatch.
function(check_loader label display file expected_hash expected_size hash_out size_out)
    if(NOT EXISTS "${file}")
        message(FATAL_ERROR
            "verify_loader_hashes: ${display} FAILED: file not found:\n"
            "  ${file}\n"
            "The loader blob must be present at its pinned jniLibs path; "
            "this gate has no skip option.")
    endif()
    if(IS_DIRECTORY "${file}")
        message(FATAL_ERROR
            "verify_loader_hashes: ${display} FAILED: not a regular file "
            "(directory at loader path):\n  ${file}")
    endif()

    # O_NOFOLLOW-ish: canonicalize the parent directory and the final component
    # separately. The final component must not be a symlink/alias to another
    # file; the gate hashes the exact pinned name, never a redirected blob.
    get_filename_component(file_name "${file}" NAME)
    get_filename_component(file_dir "${file}" DIRECTORY)
    file(REAL_PATH "${file_dir}" file_dir_real)
    set(file_canonical "${file_dir_real}/${file_name}")
    file(REAL_PATH "${file}" file_real)
    if(NOT "${file_real}" STREQUAL "${file_canonical}")
        message(FATAL_ERROR
            "verify_loader_hashes: ${display} FAILED: not a regular file "
            "(symlink or non-canonical alias; real path is '${file_real}'):\n"
            "  ${file}\n"
            "The gate hashes the exact pinned path, never a redirected blob.")
    endif()

    file(SIZE "${file}" actual_size)
    if(NOT actual_size EQUAL expected_size)
        message(FATAL_ERROR
            "verify_loader_hashes: ${display} FAILED: size mismatch:\n"
            "  file:     ${file}\n"
            "  actual:   ${actual_size} bytes\n"
            "  expected: ${expected_size} bytes\n"
            "Do not relax a pin. Rebuild from the reviewed, patched loader "
            "source, verify the result out of band, then update both pins and "
            "the provenance docs in a reviewed commit.")
    endif()

    file(SHA256 "${file}" actual_hash)
    string(TOLOWER "${actual_hash}" actual_hash)
    if(NOT actual_hash STREQUAL expected_hash)
        message(FATAL_ERROR
            "verify_loader_hashes: ${display} FAILED: SHA-256 MISMATCH:\n"
            "  file:     ${file}\n"
            "  actual:   ${actual_hash}\n"
            "  expected: ${expected_hash}\n"
            "This executable is staged and run as root; do not ship bytes of "
            "unknown provenance. Rebuild from the reviewed, patched loader "
            "source and re-pin deliberately -- never skip this gate.")
    endif()

    set(${hash_out} "${actual_hash}" PARENT_SCOPE)
    set(${size_out} "${actual_size}" PARENT_SCOPE)
    message(STATUS "${display} verified: sha256=${actual_hash} size=${actual_size}")
endfunction()

check_loader("KSUD" "libksud.so" "${LOADER_KSUD_FILE}"
    "${expected_hash_KSUD}" "${LOADER_KSUD_SIZE}"
    ksud_hash ksud_size)
check_loader("KSUDNEXT" "libksudnext.so" "${LOADER_KSUDNEXT_FILE}"
    "${expected_hash_KSUDNEXT}" "${LOADER_KSUDNEXT_SIZE}"
    ksudnext_hash ksudnext_size)

# Stable stamp: identical verified inputs produce byte-identical content so an
# always-running force target does not needlessly invalidate downstream work.
# Written unconditionally on success: entry invalidation above has already
# removed this invocation's stamp, so there is no stale file to compare against.
set(stamp_text
    "verified ${ksud_hash} ${ksud_size} ${ksudnext_hash} ${ksudnext_size}\n")
get_filename_component(stamp_dir "${LOADER_VERIFY_STAMP}" DIRECTORY)
file(MAKE_DIRECTORY "${stamp_dir}")
file(WRITE "${LOADER_VERIFY_STAMP}" "${stamp_text}")

# The manifest exists ONLY after both artifacts passed. It is informational
# evidence of this check, not an independent reproducible-build proof.
string(TIMESTAMP manifest_timestamp "%Y-%m-%dT%H:%M:%SZ" UTC)
set(manifest_text "# bundled loader SHA-256/size verification record\n")
string(APPEND manifest_text
    "status: verified\n"
    "ksud_sha256: ${ksud_hash}\n"
    "ksud_size: ${ksud_size}\n"
    "ksud_file: ${LOADER_KSUD_FILE}\n"
    "ksudnext_sha256: ${ksudnext_hash}\n"
    "ksudnext_size: ${ksudnext_size}\n"
    "ksudnext_file: ${LOADER_KSUDNEXT_FILE}\n"
    "verify_script: ${verify_script}\n"
    "stamp: ${LOADER_VERIFY_STAMP}\n"
    "timestamp_utc: ${manifest_timestamp}\n")

set(git_head "unavailable")
find_package(Git QUIET)
if(GIT_FOUND)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" rev-parse HEAD
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        OUTPUT_VARIABLE head_output
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
        RESULT_VARIABLE git_result)
    if(git_result EQUAL 0 AND NOT "${head_output}" STREQUAL "")
        set(git_head "${head_output}")
    endif()
endif()
string(APPEND manifest_text "git_head: ${git_head}\n")

get_filename_component(manifest_dir "${LOADER_VERIFY_MANIFEST}" DIRECTORY)
file(MAKE_DIRECTORY "${manifest_dir}")
file(WRITE "${LOADER_VERIFY_MANIFEST}" "${manifest_text}")
message(STATUS "loader verification manifest: ${LOADER_VERIFY_MANIFEST}")
