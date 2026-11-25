# Modified 2026-11-24 (DFRoot GZIF): add a nonprivileged host-only run-config codec test project. See NOTICE.
# Modified 2026-11-25 (DFRoot GZIF): cover strict native argv parsing and mode-honoring fixture roots. See NOTICE.
# Modified 2026-11-25 (DFRoot GZIF): re-probe relocated roots, bound every test, and clean mktemp fixtures. See NOTICE.
# Modified 2026-11-25 (DFRoot GZIF): add nonprivileged terminal marker write/classifier tests. See NOTICE.
# Modified 2026-11-25 (DFRoot GZIF): cover shared progress markers, composed deadlines, exact fault branches, and malformed markers. See NOTICE.
# Modified 2026-11-25 (DFRoot GZIF): add nonprivileged hardened loader-staging race tests with injectable directories. See NOTICE.
# Modified 2026-11-25 (DFRoot GZIF): pin staged inodes by descriptor, add deterministic identity-churn and exec-before-restore cases. See NOTICE.
cmake_minimum_required(VERSION 3.16)
project(dfroot_run_config_host_tests LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)

add_library(run_config_codec STATIC ../run_config_codec.c ../run_status.c)
target_include_directories(run_config_codec PUBLIC ..)
# The test seams are compiled only into this host library; production binaries
# use the same sources without them and contain no mutation/fault-injection seam.
target_compile_definitions(run_config_codec PUBLIC _POSIX_C_SOURCE=200809L
                           RUN_CONFIG_TEST_HOOKS RUN_STATUS_TEST_HOOKS)
target_compile_options(run_config_codec PRIVATE -Wall -Wextra -Werror -pedantic)

add_executable(run_config_codec_test run_config_codec_test.c)
target_link_libraries(run_config_codec_test PRIVATE run_config_codec)
target_compile_options(run_config_codec_test PRIVATE -Wall -Wextra -Werror -pedantic)

add_executable(run_status_test run_status_test.c)
target_link_libraries(run_status_test PRIVATE run_config_codec)
target_compile_options(run_status_test PRIVATE -Wall -Wextra -Werror -pedantic)

# Host-only library: the fault/mutation seams exist only here, never in the
# production bootstrap binary that uses the same loader_stage.c source.
add_library(loader_stage STATIC ../loader_stage.c)
target_include_directories(loader_stage PUBLIC ..)
target_compile_definitions(loader_stage PUBLIC _POSIX_C_SOURCE=200809L
                           LOADER_STAGE_TEST_HOOKS)
target_compile_options(loader_stage PRIVATE -Wall -Wextra -Werror -pedantic)

add_executable(loader_stage_test loader_stage_test.c)
target_link_libraries(loader_stage_test PRIVATE loader_stage)
target_compile_options(loader_stage_test PRIVATE -Wall -Wextra -Werror -pedantic)

enable_testing()

# Fixtures must be 0600: the codec rejects group/other-writable artifacts. Some
# build directories (FUSE mounts) silently ignore chmod, so probe the mode bits
# and relocate fixtures to a mode-honoring temporary directory when needed. The
# relocated root is probed again before it is accepted.
function(dfroot_probe_posix_modes root result_var)
    if(APPLE)
        set(probe_chmod "600")
        set(probe_stat stat -f %Lp)
    else()
        set(probe_chmod "0600")
        set(probe_stat stat -c %a)
    endif()
    set(probe_file "${root}/.mode_probe")
    file(WRITE "${probe_file}" "")
    execute_process(COMMAND chmod ${probe_chmod} "${probe_file}"
                    RESULT_VARIABLE probe_result OUTPUT_QUIET ERROR_QUIET)
    if(probe_result EQUAL 0)
        execute_process(COMMAND ${probe_stat} "${probe_file}"
                        OUTPUT_VARIABLE probe_mode OUTPUT_STRIP_TRAILING_WHITESPACE
                        RESULT_VARIABLE probe_result ERROR_QUIET)
    else()
        set(probe_mode "")
    endif()
    file(REMOVE "${probe_file}")
    if(NOT probe_result EQUAL 0 OR NOT probe_mode STREQUAL "600")
        set(${result_var} FALSE PARENT_SCOPE)
    else()
        set(${result_var} TRUE PARENT_SCOPE)
    endif()
endfunction()

set(RUN_CONFIG_TEST_ROOT "${CMAKE_CURRENT_BINARY_DIR}/work")
set(RUN_CONFIG_TEST_RELOCATED FALSE)
if(UNIX)
    dfroot_probe_posix_modes("${CMAKE_CURRENT_BINARY_DIR}" MODE_HONORED)
    if(NOT MODE_HONORED)
        if(APPLE)
            execute_process(COMMAND mktemp -d -t dfroot-run-config-tests
                            OUTPUT_VARIABLE RUN_CONFIG_TEST_ROOT
                            OUTPUT_STRIP_TRAILING_WHITESPACE)
        else()
            execute_process(COMMAND mktemp -d -t dfroot-run-config-tests.XXXXXX
                            OUTPUT_VARIABLE RUN_CONFIG_TEST_ROOT
                            OUTPUT_STRIP_TRAILING_WHITESPACE)
        endif()
        if(NOT EXISTS "${RUN_CONFIG_TEST_ROOT}")
            message(FATAL_ERROR "run-config host tests require a mode-honoring filesystem for fixtures")
        endif()
        # Re-probe the replacement root instead of trusting mktemp blindly.
        dfroot_probe_posix_modes("${RUN_CONFIG_TEST_ROOT}" RELOCATED_MODE_HONORED)
        if(NOT RELOCATED_MODE_HONORED)
            message(FATAL_ERROR "relocated run-config fixture root does not honor POSIX modes: ${RUN_CONFIG_TEST_ROOT}")
        endif()
        set(RUN_CONFIG_TEST_RELOCATED TRUE)
        message(STATUS "run-config fixtures relocated to ${RUN_CONFIG_TEST_ROOT} (build directory ignores POSIX modes)")
    endif()
endif()

file(MAKE_DIRECTORY "${RUN_CONFIG_TEST_ROOT}")
foreach(test_case IN ITEMS
        valid_full
        valid_boot
        valid_hash_uppercase
        missing
        empty
        truncated_header
        wrong_version
        truncated_line
        truncated_no_newline
        oversized
        malformed_line
        malformed_extra_equals
        equals_in_value
        missing_key
        duplicate_key
        unknown_key
        unsupported_manager
        unsupported_mode
        invalid_hash_short
        invalid_hash_nonhex
        invalid_id_empty
        invalid_id_charset
        invalid_id_too_long
        invalid_generation_negative
        invalid_generation_overflow
        invalid_generation_nondecimal
        invalid_ack_overflow
        invalid_ack_negative
        invalid_boolean
        conflicting_boot_soft_reboot
        conflicting_ack_version
        control_cr
        control_nul
        symlink
        group_writable
        other_writable
        nonregular
        fifo
        args_valid
        args_key_uppercase
        args_null_output
        file_null_output
        toctou_identity_mismatch
        args_missing_run_config
        args_missing_port
        args_unknown
        args_duplicate
        args_encap_zero
        args_encap_overflow
        args_encap_nondecimal
        args_encap_hex
        args_encap_negative
        args_encap_leading_zero
        args_encap_empty
        args_sender_overflow
        args_spi_overflow
        args_spi_negative
        args_spi_max
        args_key_short
        args_key_long
        args_key_nonhex
        args_path_control
        args_path_empty
        args_value_too_long)
    add_test(NAME run_config.${test_case}
             COMMAND run_config_codec_test ${test_case}
                     "${RUN_CONFIG_TEST_ROOT}/${test_case}")
    set_tests_properties(run_config.${test_case} PROPERTIES
                         TIMEOUT 30 FIXTURES_REQUIRED run_config_fixtures)
endforeach()

# A relocated mktemp root is emptied after every test that uses it has run. The
# root itself is kept so repeated ctest invocations need no reconfiguration.
if(RUN_CONFIG_TEST_RELOCATED)
    add_test(NAME run_config.cleanup_fixtures
             COMMAND /bin/sh -c
                     "find '${RUN_CONFIG_TEST_ROOT}' -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +")
    set_tests_properties(run_config.cleanup_fixtures PROPERTIES
                         TIMEOUT 30 FIXTURES_CLEANUP run_config_fixtures)
endif()

# Terminal marker tests use only per-case directories under the build tree;
# no privileges and no /dev writes are involved.
set(RUN_STATUS_TEST_ROOT "${CMAKE_CURRENT_BINARY_DIR}/status-work")
file(MAKE_DIRECTORY "${RUN_STATUS_TEST_ROOT}")
foreach(status_case IN ITEMS
        rc0
        rc1
        rc4
        rc6
        missing
        conflict
        foreign_id
        malformed_payload
        empty_marker
        torn_marker
        stale_foreign_marker
        unsafe_id
        write_failure
        no_follow
        fault_write
        fault_fsync
        fault_close
        fault_write_close
        fault_fsync_close
        bounded_wait
        deadline_composition
        shared_budget_wiring
        protocol_helpers
        unconfigured)
    add_test(NAME run_status.${status_case}
             COMMAND run_status_test ${status_case} "${RUN_STATUS_TEST_ROOT}/${status_case}"
                     "${CMAKE_CURRENT_SOURCE_DIR}/..")
    set_tests_properties(run_status.${status_case} PROPERTIES TIMEOUT 30)
endforeach()

# Staging tests add and restore a sticky bit, so their fixture root must honor
# POSIX mode bits (including the 01000 bit) just like the run-config root. Probe
# that explicitly instead of assuming a FUSE build directory behaves like /tmp.
function(dfroot_probe_sticky_modes root result_var)
    if(APPLE)
        set(probe_stat stat -f %Lp)
    else()
        set(probe_stat stat -c %a)
    endif()
    set(probe_dir "${root}/.sticky_probe")
    file(MAKE_DIRECTORY "${probe_dir}")
    execute_process(COMMAND chmod 1700 "${probe_dir}"
                    RESULT_VARIABLE probe_result OUTPUT_QUIET ERROR_QUIET)
    if(probe_result EQUAL 0)
        execute_process(COMMAND ${probe_stat} "${probe_dir}"
                        OUTPUT_VARIABLE probe_mode OUTPUT_STRIP_TRAILING_WHITESPACE
                        RESULT_VARIABLE probe_result ERROR_QUIET)
    else()
        set(probe_mode "")
    endif()
    file(REMOVE_RECURSE "${probe_dir}")
    if(NOT probe_result EQUAL 0 OR NOT probe_mode STREQUAL "1700")
        set(${result_var} FALSE PARENT_SCOPE)
    else()
        set(${result_var} TRUE PARENT_SCOPE)
    endif()
endfunction()

set(LOADER_STAGE_TEST_ROOT "${CMAKE_CURRENT_BINARY_DIR}/loader-work")
set(LOADER_STAGE_TEST_RELOCATED FALSE)
if(UNIX)
    dfroot_probe_sticky_modes("${CMAKE_CURRENT_BINARY_DIR}" STICKY_HONORED)
    if(NOT STICKY_HONORED)
        if(APPLE)
            execute_process(COMMAND mktemp -d -t dfroot-loader-stage-tests
                            OUTPUT_VARIABLE LOADER_STAGE_TEST_ROOT
                            OUTPUT_STRIP_TRAILING_WHITESPACE)
        else()
            execute_process(COMMAND mktemp -d -t dfroot-loader-stage-tests.XXXXXX
                            OUTPUT_VARIABLE LOADER_STAGE_TEST_ROOT
                            OUTPUT_STRIP_TRAILING_WHITESPACE)
        endif()
        if(NOT EXISTS "${LOADER_STAGE_TEST_ROOT}")
            message(FATAL_ERROR "loader-stage host tests require a mode-honoring filesystem for fixtures")
        endif()
        dfroot_probe_sticky_modes("${LOADER_STAGE_TEST_ROOT}" RELOCATED_STICKY_HONORED)
        if(NOT RELOCATED_STICKY_HONORED)
            message(FATAL_ERROR "relocated loader-stage fixture root does not honor sticky modes: ${LOADER_STAGE_TEST_ROOT}")
        endif()
        set(LOADER_STAGE_TEST_RELOCATED TRUE)
        message(STATUS "loader-stage fixtures relocated to ${LOADER_STAGE_TEST_ROOT} (build directory ignores sticky modes)")
    endif()
endif()

file(MAKE_DIRECTORY "${LOADER_STAGE_TEST_ROOT}")
foreach(stage_case IN ITEMS
        normal
        preexisting_regular
        preexisting_symlink
        preexisting_fifo
        preexisting_directory
        source_symlink
        source_fifo
        source_directory
        source_missing
        staging_dir_symlink
        staging_dir_regular
        fault_write
        fault_short_write
        fault_fsync
        fault_close
        rename_failure
        restore_failure
        replacement_after_lstat
        replacement_before_restore
        inode_churn_reuse
        renamed_away_before_restore
        unrelated_entries_untouched
        exec_before_restore
        private_before_exec
        configure_defaults
        no_shell
        wiring)
    add_test(NAME loader_stage.${stage_case}
             COMMAND loader_stage_test ${stage_case} "${LOADER_STAGE_TEST_ROOT}/${stage_case}"
                     "${CMAKE_CURRENT_SOURCE_DIR}/..")
    set_tests_properties(loader_stage.${stage_case} PROPERTIES
                         TIMEOUT 30 FIXTURES_REQUIRED loader_stage_fixtures)
endforeach()

if(LOADER_STAGE_TEST_RELOCATED)
    add_test(NAME loader_stage.cleanup_fixtures
             COMMAND /bin/sh -c
                     "find '${LOADER_STAGE_TEST_ROOT}' -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +")
    set_tests_properties(loader_stage.cleanup_fixtures PROPERTIES
                         TIMEOUT 30 FIXTURES_CLEANUP loader_stage_fixtures)
endif()
