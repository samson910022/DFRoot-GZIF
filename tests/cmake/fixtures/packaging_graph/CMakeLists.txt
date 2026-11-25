# Host-only mirror of app/src/main/jni/CMakeLists.txt's two-stage packaged-KO
# artifact chain. It uses a harmless fixture ELF instead of a real linked .so,
# and the REAL verify_packaged_ko.cmake, so the target graph can be exercised
# without an Android/NDK build. No device, signing material or kernel module.
cmake_minimum_required(VERSION 3.22)
project(dfroot_packaging_graph_fixture NONE)

foreach(required IN ITEMS RAW_SO PACKAGE_DIR VERIFY_SCRIPT KO_OBJCOPY KO_PINS KO_SIZES)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
        message(FATAL_ERROR "packaging graph fixture: missing -D${required}=...")
    endif()
endforeach()

set(STAGING_DIR "${CMAKE_CURRENT_BINARY_DIR}/staging")
set(STAGED_SO "${STAGING_DIR}/libdfroot.so")
set(PACKAGE_SO "${PACKAGE_DIR}/libdfroot.so")
set(BOOTSTRAP_SO "${PACKAGE_DIR}/libbootstrap.so")
# Manifest lives in the CMake build dir (under app/.cxx in production) and
# records the package-path verified .so for the CI evidence gate.
set(MANIFEST "${CMAKE_CURRENT_BINARY_DIR}/dfroot-packaged-ko.manifest")
set(FORCE_FILE "${CMAKE_CURRENT_BINARY_DIR}/dfroot-packaged-ko.verify.force")

# Stage 1: the "raw link" target emits an UNVERIFIED .so in a staging path.
# It is not part of ALL, so building it directly is a visible bypass that NEVER
# writes the package-path artifact or its verified manifest.
add_custom_command(
    OUTPUT "${STAGED_SO}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${STAGING_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${RAW_SO}" "${STAGED_SO}"
    DEPENDS "${RAW_SO}"
    COMMENT "Emitting unverified staged .so (packaging-graph fixture)"
    VERBATIM
)
add_custom_target(dfroot_raw DEPENDS "${STAGED_SO}")

# Second required native artifact, mirroring production's `bootstrap` target.
# It is NOT in dfroot_packaged's staging/verify chain, so AGP's named-target
# list must name it (and production also adds an explicit dependency).
add_custom_command(
    OUTPUT "${BOOTSTRAP_SO}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${PACKAGE_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${RAW_SO}" "${BOOTSTRAP_SO}"
    DEPENDS "${RAW_SO}"
    COMMENT "Building required bootstrap library (packaging-graph fixture)"
    VERBATIM
)
add_custom_target(dfroot_bootstrap ALL DEPENDS "${BOOTSTRAP_SO}")

# An always-running force target guarantees the verification command re-runs
# on cached builds too. The verified command depends on the raw target but not
# vice versa, so the graph has no cycle.
add_custom_target(packaged_ko_force
    COMMAND "${CMAKE_COMMAND}" -E touch "${FORCE_FILE}"
    BYPRODUCTS "${FORCE_FILE}"
    COMMENT "Scheduling packaged-KO byte re-check (fixture)"
)
add_custom_command(
    OUTPUT "${PACKAGE_SO}"
    COMMAND "${CMAKE_COMMAND}"
            "-DPACKAGED_SO=${STAGED_SO}"
            "-DKO_OBJCOPY=${KO_OBJCOPY}"
            "-DKO_EXPECTED_SHA256S=${KO_PINS}"
            "-DKO_EXPECTED_SIZE=${KO_SIZES}"
            "-DKO_MANIFEST=${MANIFEST}"
            "-DPACKAGED_VERIFIED_SO=${PACKAGE_SO}"
            "-DDFROOT_SOURCE_DIR=${CMAKE_CURRENT_SOURCE_DIR}/../../.."
            -P "${VERIFY_SCRIPT}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${PACKAGE_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${STAGED_SO}" "${PACKAGE_SO}"
    DEPENDS dfroot_raw
            "${VERIFY_SCRIPT}"
            "${CMAKE_CURRENT_LIST_FILE}"
            "${FORCE_FILE}"
            packaged_ko_force
    BYPRODUCTS "${MANIFEST}"
    COMMENT "Verifying and staging the packaged .so (fixture)"
    VERBATIM
)

# Same named ALL target as production; this is what AGP's `targets(...)` asks
# CMake to build, together with `dfroot_bootstrap` (the second name in AGP's
# target list). The explicit dependency mirrors CMakeLists.txt's belt-and-
# braces add_dependencies(dfroot_packaged bootstrap).
add_custom_target(dfroot_packaged ALL DEPENDS "${PACKAGE_SO}")
add_dependencies(dfroot_packaged dfroot_bootstrap)
