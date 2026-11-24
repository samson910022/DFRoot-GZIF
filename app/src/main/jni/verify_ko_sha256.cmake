# verify_ko_sha256.cmake -- build-time SHA-256 gate for the embedded dfroot.ko.
#
# Invoked by app/src/main/jni/CMakeLists.txt (add_custom_command) as:
#   cmake -DKO_FILE=<.../dfroot-android13-5.15.ko> \
#         "-DKO_EXPECTED_SHA256S=<hash1>;<hash2>;..." \
#         -DKO_STAMP=<build>/dfroot-ko.sha256.verified \
#         -DDFROOT_BUILD_TYPE=<Debug|Release|...>
#         [-DDFROOT_ANDROID_VARIANT=<debug|release>]
#         [-DKO_VERIFY_MANIFEST=<build>/dfroot-ko.sha256.manifest]
#         [-DDFROOT_SKIP=ON -DDFROOT_SKIP_REASON=<local reason>]
#         -P verify_ko_sha256.cmake
#
# KO_EXPECTED_SHA256S is a CMake (semicolon-separated) list of accepted
# SHA-256 pins; the blob passes if its hash matches ANY entry. Two pins are
# kept because the local lkm/Makefile artifact and the CI artifact (CI strips
# .note.gnu.build-id with llvm-objcopy after the DDK build) are byte-for-byte
# different but both are known-good, same-source builds. Unknown blobs are
# still rejected (fail-closed).
#
# Uses file(SHA256) (available since CMake 3.0), so no external tools are
# needed. On success the script writes the stamp that exp.c's OBJECT_DEPENDS
# waits on, i.e. verification always completes before .incbin embeds the blob.
# The stamp is rewritten only when its content changes, so re-running the
# check every build does not force a needless recompile of exp.c. The stamp
# deliberately contains only the outcome and the KO identity -- no timestamp,
# build type, reason or environment data, all of which are volatile.
# The (volatile, informational) build record goes to a separate manifest.

foreach(required IN ITEMS KO_FILE KO_EXPECTED_SHA256S KO_STAMP DFROOT_BUILD_TYPE)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
        message(FATAL_ERROR "verify_ko_sha256: missing -D${required}=... argument")
    endif()
endforeach()

get_filename_component(verify_script "${CMAKE_CURRENT_LIST_FILE}" ABSOLUTE)
get_filename_component(script_dir "${verify_script}" DIRECTORY)
if(NOT DEFINED KO_VERIFY_MANIFEST OR "${KO_VERIFY_MANIFEST}" STREQUAL "")
    set(KO_VERIFY_MANIFEST "${KO_STAMP}.manifest")
endif()
if(NOT DEFINED DFROOT_SOURCE_DIR OR "${DFROOT_SOURCE_DIR}" STREQUAL "")
    get_filename_component(DFROOT_SOURCE_DIR "${script_dir}/../../.." ABSOLUTE)
endif()
if(NOT DEFINED DFROOT_ANDROID_VARIANT OR "${DFROOT_ANDROID_VARIANT}" STREQUAL "")
    set(DFROOT_ANDROID_VARIANT "unspecified")
endif()

# Pin normalization/validation is shared with the packaged-byte verifier so the
# two gates cannot drift apart. It never adds or relaxes a pin.
include("${script_dir}/ko_pin_policy.cmake")

# The blob must exist even when the hash check is skipped: exp.c still .incbin's
# it, so a missing/unreadable KO can never silently produce an artifact. Check
# this before the DFROOT_SKIP branch so skip mode is fail-closed too.
if(NOT EXISTS "${KO_FILE}")
    message(FATAL_ERROR
        "dfroot.ko SHA-256 verification FAILED: blob not found:\n"
        "  ${KO_FILE}\n"
        "The LKM is gitignored and must be supplied before the Gradle build "
        "(CI downloads the DDK artifact into app/src/main/jni/ko/). Restore "
        "the blob there. Even with -DDFROOT_SKIP_KO_HASH_CHECK=ON the blob must "
        "exist; skipping only disables the hash check, never the .incbin input. "
        "Never use skip mode for CI/release builds.")
endif()
if(IS_DIRECTORY "${KO_FILE}")
    message(FATAL_ERROR
        "dfroot.ko SHA-256 verification FAILED: path is a directory:\n"
        "  ${KO_FILE}")
endif()

# Normalize and validate every pin up front, including in skip mode: the
# manifest records the accepted-pin count and a malformed pin list is always a
# configuration error.
dfroot_validate_ko_pins("${KO_EXPECTED_SHA256S}" "verify_ko_sha256" expected_list)
list(LENGTH expected_list accepted_pins)

if(DFROOT_SKIP)
    # Skip policy is enforced here (not only in CMakeLists.txt) so a direct
    # `cmake -P` invocation cannot bypass it. Presence of any automation /
    # release environment marker, a Release build type, an Android release
    # variant (DFROOT_ANDROID_VARIANT=release), or an absent reason is always
    # fatal. The env markers (CI / GITHUB_ACTIONS / DFROOT_RELEASE) are
    # authoritative: DFROOT_BUILD_TYPE comes from the caller/CMake cache and
    # can be stale (e.g. a cached Debug config reused for a release build), so
    # it is only a secondary signal. CI exports DFROOT_RELEASE=1 for releases.
    if(DEFINED ENV{CI} OR DEFINED ENV{GITHUB_ACTIONS} OR DEFINED ENV{DFROOT_RELEASE})
        message(FATAL_ERROR
            "verify_ko_sha256: KO hash-check skip is forbidden when CI, "
            "GITHUB_ACTIONS or DFROOT_RELEASE is set. Refusing to build with "
            "an unverified ring0 module.")
    endif()
    string(TOUPPER "${DFROOT_BUILD_TYPE}" build_type_upper)
    if(build_type_upper STREQUAL "RELEASE")
        message(FATAL_ERROR
            "verify_ko_sha256: KO hash-check skip is forbidden for "
            "DFROOT_BUILD_TYPE=${DFROOT_BUILD_TYPE} (Release).")
    endif()
    # The Gradle-supplied Android variant is authoritative: a stale/cached
    # CMAKE_BUILD_TYPE of Debug must never weaken a release variant.
    string(TOLOWER "${DFROOT_ANDROID_VARIANT}" android_variant_lower)
    if(android_variant_lower STREQUAL "release")
        message(FATAL_ERROR
            "verify_ko_sha256: KO hash-check skip is forbidden for Android "
            "release variant (DFROOT_ANDROID_VARIANT=${DFROOT_ANDROID_VARIANT}).")
    endif()
    if(NOT DEFINED DFROOT_SKIP_REASON OR "${DFROOT_SKIP_REASON}" STREQUAL "")
        message(FATAL_ERROR
            "verify_ko_sha256: -DDFROOT_SKIP_REASON=<reason> is required when "
            "DFROOT_SKIP is enabled (local experiments only).")
    endif()

    set(skip_status "skipped")
    set(actual_hash "not-computed (skip enabled)")
    set(stamp_text "SKIPPED: KO hash check disabled (DFROOT_SKIP_KO_HASH_CHECK=ON) for ${KO_FILE}\n")
    message(STATUS
        "dfroot.ko SHA-256 check SKIPPED (DFROOT_SKIP_KO_HASH_CHECK=ON) for "
        "${KO_FILE}; reason: ${DFROOT_SKIP_REASON}")
else()
    file(SHA256 "${KO_FILE}" actual)
    string(TOLOWER "${actual}" actual_hash)

    set(hash_matched FALSE)
    foreach(expected IN LISTS expected_list)
        if(actual_hash STREQUAL expected)
            set(hash_matched TRUE)
            break()
        endif()
    endforeach()

    if(NOT hash_matched)
        # Render every accepted pin on its own line for an actionable error.
        string(REPLACE ";" "\n  " expected_display "${expected_list}")
        message(FATAL_ERROR
            "dfroot.ko SHA-256 MISMATCH -- build aborted before the blob is embedded.\n"
            "  file:     ${KO_FILE}\n"
            "  actual:   ${actual_hash}\n"
            "  expected (any one of these pins is accepted):\n"
            "  ${expected_display}\n"
            "This .ko is loaded in ring0; do not ship bytes of unknown provenance. "
            "To pin a legitimate new artifact: rebuild from lkm/ with the pinned "
            "DDK image, verify the result out of band, then add its hash to "
            "KO_EXPECTED_SHA256S in app/src/main/jni/CMakeLists.txt and update "
            "README.md / PORTING-GZIF.md in a reviewed commit. Adding a third hash "
            "requires review. Local experiments only: -DDFROOT_SKIP_KO_HASH_CHECK=ON.")
    endif()

    set(skip_status "verified")
    set(stamp_text "verified ${actual_hash}  ${KO_FILE}\n")
    message(STATUS "dfroot.ko SHA-256 verified: ${actual_hash}")
endif()

# Stable stamp: identical inputs (and identical verification status) always
# produce byte-identical content, so ninja/make does not rebuild exp.c needlessly.
get_filename_component(stamp_dir "${KO_STAMP}" DIRECTORY)
file(MAKE_DIRECTORY "${stamp_dir}")
set(write_stamp TRUE)
if(EXISTS "${KO_STAMP}")
    file(READ "${KO_STAMP}" old_stamp)
    if(old_stamp STREQUAL stamp_text)
        set(write_stamp FALSE)
    endif()
endif()
if(write_stamp)
    file(WRITE "${KO_STAMP}" "${stamp_text}")
endif()

# Separate, informational manifest. Unlike the stamp it is rewritten on every
# verification because it records the time and environment. It is evidence of
# what this build check saw, NOT an independent proof of reproducibility.
set(manifest_text "# dfroot.ko SHA-256 verification record (informational)\n")
string(APPEND manifest_text
    "status: ${skip_status}\n"
    "sha256: ${actual_hash}\n"
    "ko_file: ${KO_FILE}\n"
    "accepted_pins: ${accepted_pins}\n")
if(DFROOT_SKIP)
    string(APPEND manifest_text "skip_reason: ${DFROOT_SKIP_REASON}\n")
else()
    string(APPEND manifest_text "skip_reason: n/a (hash check enforced)\n")
endif()
string(APPEND manifest_text
    "build_type: ${DFROOT_BUILD_TYPE}\n"
    "android_variant: ${DFROOT_ANDROID_VARIANT}\n"
    "timestamp_utc: ")
string(TIMESTAMP manifest_timestamp "%Y-%m-%dT%H:%M:%SZ" UTC)
string(APPEND manifest_text "${manifest_timestamp}\n")

if(DEFINED ENV{CI})
    string(APPEND manifest_text "env_CI: set ($ENV{CI})\n")
else()
    string(APPEND manifest_text "env_CI: unset\n")
endif()
if(DEFINED ENV{GITHUB_ACTIONS})
    string(APPEND manifest_text "env_GITHUB_ACTIONS: set ($ENV{GITHUB_ACTIONS})\n")
else()
    string(APPEND manifest_text "env_GITHUB_ACTIONS: unset\n")
endif()
if(DEFINED ENV{DFROOT_RELEASE})
    string(APPEND manifest_text "env_DFROOT_RELEASE: set ($ENV{DFROOT_RELEASE})\n")
else()
    string(APPEND manifest_text "env_DFROOT_RELEASE: unset\n")
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

string(APPEND manifest_text
    "git_head: ${git_head}\n"
    "verify_script: ${verify_script}\n"
    "stamp: ${KO_STAMP}\n")

get_filename_component(manifest_dir "${KO_VERIFY_MANIFEST}" DIRECTORY)
file(MAKE_DIRECTORY "${manifest_dir}")
file(WRITE "${KO_VERIFY_MANIFEST}" "${manifest_text}")
message(STATUS "dfroot.ko verification manifest: ${KO_VERIFY_MANIFEST}")
