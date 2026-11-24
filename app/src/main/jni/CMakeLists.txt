# Modified 2026-11-17 (DFRoot GZIF): KO_FILES reduced to android13-5.15 only. See NOTICE.
cmake_minimum_required(VERSION 3.22)
project(dfroot)
enable_language(ASM)

set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR})

set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -I${JNI_DIR}")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${C_FLAGS} -I${JNI_DIR} -Wall -Wextra -fno-stack-protector -fomit-frame-pointer")

set(SPLICEHELPER "${JNI_DIR}/splicehelper")
add_custom_command(
    OUTPUT "${SPLICEHELPER}"
    COMMAND "${CMAKE_C_COMPILER}"
            --target=${CMAKE_C_COMPILER_TARGET}
            --sysroot=${CMAKE_SYSROOT}
            "${JNI_DIR}/splicehelper.c"
            -o "${SPLICEHELPER}"
            -nodefaultlibs -nostartfiles -ffreestanding -static
    COMMAND "${CMAKE_STRIP}" "${SPLICEHELPER}"
    DEPENDS "${JNI_DIR}/splicehelper.c"
    COMMENT "Building splicehelper"
)
add_custom_target(splicehelper_bin DEPENDS "${SPLICEHELPER}")
# splicehelper is compiled here from splicehelper.c, so its bytes are fully
# determined by source that is already reviewed; it needs no external hash pin.

# The .ko below is a gitignored, externally supplied binary embedded by .incbin
# in exp.c and later loaded in ring0. Pin its SHA-256 and verify it at build
# time, before exp.c is compiled (M9 supply-chain hardening).
set(KO_FILE "${JNI_DIR}/ko/dfroot-android13-5.15.ko")
set(KO_FILES
    "${KO_FILE}"
)
# Accepted SHA-256 pins for the embedded dfroot.ko. Both are from the same,
# already-reviewed DDK build and differ only because CI runs an extra
# `llvm-objcopy -R .note.gnu.build-id` after the DDK build, which yields a
# byte-for-byte different (but equally legitimate) blob:
#   94f8be... = local `make` output from lkm/ (WITH .note.gnu.build-id), 13504 B
#   9dfb8b... = CI artifact (build-id stripped), 13384 B; byte-for-byte the KO
#               shipped in the v4.8-gzif release APK.
# Accepting both keeps the gate fail-closed for any *unknown* blob while not
# breaking either the local or the CI build. Adding a third hash must go
# through review and be added deliberately here -- never auto-generated.
set(KO_EXPECTED_SHA256S
    "94f8be987a2f8bdc13539e42a5c3cb4c0f606024b8b31601496c5d5e5c0025a7"
    "9dfb8b71b306f5a38fd8b35b283d4dab8b4eb25a08ad97ca0f4ef705b6151c68"
)
set(KO_VERIFY_SCRIPT "${JNI_DIR}/verify_ko_sha256.cmake")
set(KO_VERIFY_STAMP "${CMAKE_CURRENT_BINARY_DIR}/dfroot-ko.sha256.verified")

option(DFROOT_SKIP_KO_HASH_CHECK "Skip the pinned dfroot.ko SHA-256 check (local experiments only)" OFF)

if(DFROOT_SKIP_KO_HASH_CHECK)
    message(WARNING
        "DFROOT_SKIP_KO_HASH_CHECK=ON: the pinned dfroot.ko SHA-256 check is "
        "DISABLED. The resulting binary may embed an unverified LKM. "
        "Use this only for local experiments; never for CI or release builds.")
endif()

# Always-running target touches a real file that the stamp rule depends on;
# this forces verification on every build even if the blob is replaced with
# its original mtime. verify_ko_sha256.cmake rewrites the stamp only when its
# content changes, so exp.c is not recompiled when the hash is unchanged.
set(KO_FORCE_FILE "${CMAKE_CURRENT_BINARY_DIR}/dfroot-ko.verify.force")
add_custom_target(ko_sha256_force
    COMMAND "${CMAKE_COMMAND}" -E touch "${KO_FORCE_FILE}"
    BYPRODUCTS "${KO_FORCE_FILE}"
    COMMENT "Scheduling dfroot.ko SHA-256 re-check"
)

set(KO_VERIFY_DEPENDS
    "${KO_VERIFY_SCRIPT}"
    "${CMAKE_CURRENT_LIST_FILE}"   # re-verify if the pin itself is edited
    "${KO_FORCE_FILE}"
    ko_sha256_force                # also gives target-level ordering
)
if(NOT DFROOT_SKIP_KO_HASH_CHECK)
    list(APPEND KO_VERIFY_DEPENDS ${KO_FILES})
endif()

add_custom_command(
    OUTPUT "${KO_VERIFY_STAMP}"
    COMMAND "${CMAKE_COMMAND}"
            "-DKO_FILE=${KO_FILE}"
            "-DKO_EXPECTED_SHA256S=${KO_EXPECTED_SHA256S}"
            "-DKO_STAMP=${KO_VERIFY_STAMP}"
            "-DDFROOT_SKIP=${DFROOT_SKIP_KO_HASH_CHECK}"
            -P "${KO_VERIFY_SCRIPT}"
    DEPENDS ${KO_VERIFY_DEPENDS}
    COMMENT "Verifying pinned dfroot.ko SHA-256"
    VERBATIM
)
add_custom_target(ko_sha256_check DEPENDS "${KO_VERIFY_STAMP}")

# AGP only packages lib*.so, so the executable is named libdfroot.so and lands
# in the APK's native lib dir. The app execs it from nativeLibraryDir, which is
# executable (app data dir is not).
add_executable(dfroot exp.c libcxx.S elf_parser.c)
add_dependencies(dfroot splicehelper_bin ko_sha256_check)
set_target_properties(dfroot PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

set_source_files_properties(exp.c PROPERTIES
    OBJECT_DEPENDS "${KO_FILES};${SPLICEHELPER};${KO_VERIFY_STAMP}")

target_link_libraries(dfroot log)
target_link_options(dfroot PRIVATE -Wl,-z,max-page-size=16384)

add_executable(bootstrap bootstrap.c)
set_target_properties(bootstrap PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_link_options(bootstrap PRIVATE -Wl,-z,max-page-size=16384)
