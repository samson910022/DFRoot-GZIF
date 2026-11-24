# verify_ko_sha256.cmake -- build-time SHA-256 gate for the embedded dfroot.ko.
#
# Invoked by app/src/main/jni/CMakeLists.txt (add_custom_command) as:
#   cmake -DKO_FILE=<.../dfroot-android13-5.15.ko> \
#         "-DKO_EXPECTED_SHA256S=<hash1>;<hash2>;..." \
#         -DKO_STAMP=<build>/dfroot-ko.sha256.verified \
#         [-DDFROOT_SKIP=ON] \
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
# check every build does not force a needless recompile of exp.c.

foreach(required IN ITEMS KO_FILE KO_EXPECTED_SHA256S KO_STAMP)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
        message(FATAL_ERROR "verify_ko_sha256: missing -D${required}=... argument")
    endif()
endforeach()

if(DFROOT_SKIP)
    set(stamp_text "SKIPPED: KO hash check disabled (DFROOT_SKIP_KO_HASH_CHECK=ON)\n")
    message(STATUS "dfroot.ko SHA-256 check SKIPPED (DFROOT_SKIP_KO_HASH_CHECK=ON)")
else()
    if(NOT EXISTS "${KO_FILE}")
        message(FATAL_ERROR
            "dfroot.ko SHA-256 verification FAILED: blob not found:\n"
            "  ${KO_FILE}\n"
            "The LKM is gitignored and must be supplied before the Gradle build "
            "(CI downloads the DDK artifact into app/src/main/jni/ko/). Restore "
            "the blob there. For local experiments only, reconfigure with "
            "-DDFROOT_SKIP_KO_HASH_CHECK=ON; never use that for CI/release builds.")
    endif()
    if(IS_DIRECTORY "${KO_FILE}")
        message(FATAL_ERROR
            "dfroot.ko SHA-256 verification FAILED: path is a directory:\n"
            "  ${KO_FILE}")
    endif()

    file(SHA256 "${KO_FILE}" actual)
    string(TOLOWER "${actual}" actual)

    # Normalize and validate every pin. CMake's MATCHES has no interval
    # quantifier ({64}), so check length and forbidden characters separately.
    set(expected_list "")
    foreach(raw_expected IN LISTS KO_EXPECTED_SHA256S)
        string(STRIP "${raw_expected}" expected)
        string(TOLOWER "${expected}" expected)
        string(LENGTH "${expected}" expected_len)
        if(NOT expected_len EQUAL 64 OR expected MATCHES "[^0-9a-f]")
            message(FATAL_ERROR
                "verify_ko_sha256: KO_EXPECTED_SHA256S contains an invalid SHA-256 "
                "pin (expected exactly 64 hex digits): '${expected}'")
        endif()
        list(APPEND expected_list "${expected}")
    endforeach()

    set(hash_matched FALSE)
    foreach(expected IN LISTS expected_list)
        if(actual STREQUAL expected)
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
            "  actual:   ${actual}\n"
            "  expected (any one of these pins is accepted):\n"
            "  ${expected_display}\n"
            "This .ko is loaded in ring0; do not ship bytes of unknown provenance. "
            "To pin a legitimate new artifact: rebuild from lkm/ with the pinned "
            "DDK image, verify the result out of band, then add its hash to "
            "KO_EXPECTED_SHA256S in app/src/main/jni/CMakeLists.txt and update "
            "README.md / PORTING-GZIF.md in a reviewed commit. Adding a third hash "
            "requires review. Local experiments only: -DDFROOT_SKIP_KO_HASH_CHECK=ON.")
    endif()

    set(stamp_text "verified ${actual}  ${KO_FILE}\n")
    message(STATUS "dfroot.ko SHA-256 verified: ${actual}")
endif()

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
