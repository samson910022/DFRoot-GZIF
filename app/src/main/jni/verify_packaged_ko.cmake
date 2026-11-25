# verify_packaged_ko.cmake -- final-byte gate for the KO embedded in libdfroot.so.
#
# Invoked by app/src/main/jni/CMakeLists.txt (an ALWAYS-RUN custom target so the
# check cannot be skipped on an up-to-date link) and by CI against the .so
# extracted from every distributable release APK (not just tag builds).
# It is also exercised against harmless fixtures by host-only tests:
#
#   cmake -DPACKAGED_SO=<libdfroot.so> \
#         -DKO_OBJCOPY=<path to objcopy / llvm-objcopy> \
#         "-DKO_EXPECTED_SHA256S=<hash1>;<hash2>" \
#         [-DKO_EXPECTED_SIZE=<bytes> or <bytes1>;<bytes2>] \
#         -DKO_MANIFEST=<build>/dfroot-packaged-ko.manifest \
#         [-DDFROOT_SOURCE_DIR=<repo root>] \
#         -P verify_packaged_ko.cmake
#
# It extracts the dedicated `.dfroot_ko` section from the already-linked .so
# with objcopy and hashes the extracted bytes against the SAME reviewed pins
# used by verify_ko_sha256.cmake. Pin validation is shared via ko_pin_policy.cmake;
# nothing here adds, relaxes or generates a pin. There is deliberately NO skip
# option: any skip flag is rejected, so a packaged artifact can never be
# "verified" without comparing its bytes. The output manifest and the temporary
# section dump are INVALIDATED at entry (removed before any work / extraction),
# so a previous success at the same output path can never survive a later
# failure as stale "status: verified" evidence. A manifest is written only on
# success (failure is fail-closed: no manifest, non-zero exit). The size and
# SHA-256 comparisons below are unchanged. The manifest's so_sha256 is captured
# before objcopy runs (some objcopy builds rewrite their input in place). The
# manifest is evidence of what this check observed, not an independent
# reproducibility proof.

foreach(required IN ITEMS PACKAGED_SO KO_OBJCOPY KO_EXPECTED_SHA256S KO_MANIFEST)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
        message(FATAL_ERROR "verify_packaged_ko: missing -D${required}=... argument")
    endif()
endforeach()

# Entry invalidation: any manifest at this output path belongs to a previous
# invocation. Drop it before doing any work so a later failure cannot leave
# stale "status: verified" evidence behind. This never weakens a check.
file(REMOVE "${KO_MANIFEST}")
# The temporary section dump belongs to this output path too; invalidate it at
# entry so an early failure can never leave bytes from an earlier extraction.
set(dump_file "${KO_MANIFEST}.dfroot_ko.dump")
file(REMOVE "${dump_file}")

get_filename_component(verify_script "${CMAKE_CURRENT_LIST_FILE}" ABSOLUTE)
get_filename_component(script_dir "${verify_script}" DIRECTORY)
if(NOT DEFINED DFROOT_SOURCE_DIR OR "${DFROOT_SOURCE_DIR}" STREQUAL "")
    get_filename_component(DFROOT_SOURCE_DIR "${script_dir}/../../.." ABSOLUTE)
endif()

set(KO_SECTION ".dfroot_ko")

# No skip path exists by design. Reject a skip flag instead of silently
# ignoring it, so a caller cannot claim a verified manifest while bypassing
# the byte comparison.
foreach(skip_var IN ITEMS DFROOT_SKIP DFROOT_SKIP_KO_HASH_CHECK)
    if(DEFINED ${skip_var} AND ${skip_var})
        message(FATAL_ERROR
            "verify_packaged_ko: no skip option exists; -D${skip_var}=ON "
            "cannot bypass the packaged-KO byte gate")
    endif()
endforeach()

# Locate objcopy. CMAKE_OBJCOPY from the toolchain is an absolute path; a bare
# command name is also accepted and resolved from PATH.
set(objcopy "${KO_OBJCOPY}")
if(NOT IS_ABSOLUTE "${objcopy}")
    find_program(dfroot_packaged_objcopy NAMES "${objcopy}")
    if(dfroot_packaged_objcopy)
        set(objcopy "${dfroot_packaged_objcopy}")
    endif()
endif()
if("${objcopy}" STREQUAL "" OR NOT EXISTS "${objcopy}" OR IS_DIRECTORY "${objcopy}")
    message(FATAL_ERROR
        "verify_packaged_ko: objcopy unavailable: '${KO_OBJCOPY}'. "
        "Provide -DKO_OBJCOPY=<path> (CMAKE_OBJCOPY from the toolchain, or a "
        "llvm-objcopy/objcopy on PATH). The packaged-KO gate cannot run "
        "without it and is never skipped.")
endif()

if(NOT EXISTS "${PACKAGED_SO}" OR IS_DIRECTORY "${PACKAGED_SO}")
    message(FATAL_ERROR
        "verify_packaged_ko: packaged SO not found or not a file: ${PACKAGED_SO}")
endif()

# Capture the identity of the SO AS PASSED IN before objcopy runs: some objcopy
# implementations rewrite their input in place when --dump-section is used.
# Recording the hash afterwards would describe mutated bytes instead of the
# file CI extracted from the APK and hashed.
file(SHA256 "${PACKAGED_SO}" so_hash_raw)
string(TOLOWER "${so_hash_raw}" so_hash)

# Shared pin policy: normalizes to lowercase, rejects malformed entries, and
# never accepts a new hash. The same pins are used for source and packaged KO.
include("${script_dir}/ko_pin_policy.cmake")
dfroot_validate_ko_pins("${KO_EXPECTED_SHA256S}" "verify_packaged_ko" expected_list)
list(LENGTH expected_list accepted_pins)
if(accepted_pins EQUAL 0)
    message(FATAL_ERROR
        "verify_packaged_ko: KO_EXPECTED_SHA256S is empty; refusing to verify "
        "packaged bytes against an empty pin set")
endif()

# Validate optional exact-size expectations before doing any work. A list is
# accepted so the two reviewed artifacts (with/without build-id) can both be
# legitimate; a size can never make an unknown hash acceptable.
set(expected_sizes "")
if(DEFINED KO_EXPECTED_SIZE AND NOT "${KO_EXPECTED_SIZE}" STREQUAL "")
    foreach(raw_size IN LISTS KO_EXPECTED_SIZE)
        string(STRIP "${raw_size}" size_value)
        if(NOT "${size_value}" MATCHES "^[0-9]+$")
            message(FATAL_ERROR
                "verify_packaged_ko: invalid KO_EXPECTED_SIZE entry "
                "(expected a non-negative integer): '${size_value}'")
        endif()
        list(APPEND expected_sizes "${size_value}")
    endforeach()
endif()

get_filename_component(manifest_dir "${KO_MANIFEST}" DIRECTORY)
file(MAKE_DIRECTORY "${manifest_dir}")
# Extract only the dedicated section. objcopy's --dump-section writes nothing
# useful when the section is absent; treat a failure, a missing file or an
# empty result as "section not found" (distinct from a hash mismatch).
execute_process(
    COMMAND "${objcopy}" "--dump-section" "${KO_SECTION}=${dump_file}" "${PACKAGED_SO}"
    RESULT_VARIABLE dump_result
    OUTPUT_VARIABLE dump_stdout
    ERROR_VARIABLE dump_stderr)
if(NOT dump_result EQUAL 0 OR NOT EXISTS "${dump_file}")
    message(FATAL_ERROR
        "verify_packaged_ko: ${KO_SECTION} section not found in ${PACKAGED_SO}.\n"
        "  objcopy: ${objcopy}\n"
        "  exit: ${dump_result}\n"
        "  stderr: ${dump_stderr}\n"
        "Ensure exp.c emits the KO into a dedicated ${KO_SECTION} section and "
        "that AGP packages libdfroot.so from the verified build.")
endif()
file(SIZE "${dump_file}" packaged_bytes)
if(packaged_bytes EQUAL 0)
    message(FATAL_ERROR
        "verify_packaged_ko: ${KO_SECTION} section not found in ${PACKAGED_SO} "
        "(the section is present but empty; ${packaged_bytes} bytes)")
endif()

set(size_ok TRUE)
if(expected_sizes)
    set(size_ok FALSE)
    foreach(expected_size IN LISTS expected_sizes)
        if(packaged_bytes EQUAL expected_size)
            set(size_ok TRUE)
            break()
        endif()
    endforeach()
    if(NOT size_ok)
        string(REPLACE ";" ", " sizes_display "${expected_sizes}")
        message(FATAL_ERROR
            "verify_packaged_ko: packaged KO size mismatch.\n"
            "  section: ${KO_SECTION}\n"
            "  actual bytes: ${packaged_bytes}\n"
            "  expected bytes (any one): ${sizes_display}\n"
            "Refusing to treat a differently sized section as the reviewed "
            "artifact. Review a new artifact out of band before changing the "
            "expected size or pins in a reviewed commit.")
    endif()
endif()

file(SHA256 "${dump_file}" packaged_hash_raw)
string(TOLOWER "${packaged_hash_raw}" packaged_hash)
set(hash_matched FALSE)
foreach(expected IN LISTS expected_list)
    if(packaged_hash STREQUAL expected)
        set(hash_matched TRUE)
        break()
    endif()
endforeach()
if(NOT hash_matched)
    string(REPLACE ";" "\n  " expected_display "${expected_list}")
    message(FATAL_ERROR
        "verify_packaged_ko: packaged KO SHA-256 MISMATCH -- refusing to release.\n"
        "  so:       ${PACKAGED_SO}\n"
        "  section:  ${KO_SECTION}\n"
        "  bytes:    ${packaged_bytes}\n"
        "  actual:   ${packaged_hash}\n"
        "  expected (any one of these reviewed pins is accepted):\n"
        "  ${expected_display}\n"
        "These are the final bytes embedded in libdfroot.so and loaded in "
        "ring0. Do not relax this gate: verify the artifact out of band, then "
        "add its hash deliberately to KO_EXPECTED_SHA256S in "
        "app/src/main/jni/CMakeLists.txt with a reviewed commit and docs.")
endif()

string(REPLACE ";" ";" pins_display "${expected_list}")
if(expected_sizes)
    string(REPLACE ";" ";" size_display "${expected_sizes}")
else()
    set(size_display "n/a (no exact size configured)")
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

string(TIMESTAMP manifest_timestamp "%Y-%m-%dT%H:%M:%SZ" UTC)
set(manifest_text "# packaged dfroot.ko byte verification record (informational)\n")
string(APPEND manifest_text
    "status: verified\n"
    "sha256: ${packaged_hash}\n"
    "bytes: ${packaged_bytes}\n"
    "section: ${KO_SECTION}\n"
    "so_sha256: ${so_hash}\n"
    "so_file: ${PACKAGED_SO}\n"
    "accepted_pins: ${accepted_pins}\n"
    "pins: ${pins_display}\n"
    "expected_size: ${size_display}\n"
    "objcopy: ${objcopy}\n"
    "timestamp_utc: ${manifest_timestamp}\n"
    "git_head: ${git_head}\n"
    "verify_script: ${verify_script}\n")

file(WRITE "${KO_MANIFEST}" "${manifest_text}")
file(REMOVE "${dump_file}")
message(STATUS "verify_packaged_ko: ${KO_SECTION} verified (${packaged_bytes} bytes, ${packaged_hash})")
message(STATUS "verify_packaged_ko manifest: ${KO_MANIFEST}")
