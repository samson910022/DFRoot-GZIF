/* Modified 2026-11-24 (DFRoot GZIF): define the strict shared Java/native run-config contract. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): add strict native argv parsing shared with exp.c host tests. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): size the manager field for its terminator and add a host-only TOCTOU hook. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): export the operation-ID policy for run-scoped markers. See NOTICE. */
#ifndef DFROOT_RUN_CONFIG_CODEC_H
#define DFROOT_RUN_CONFIG_CODEC_H

#include <stddef.h>
#include <stdint.h>

#define RUN_CONFIG_HEADER "DFROOT_RUN_CONFIG 1"
#define RUN_CONFIG_MAX_BYTES 8192
#define RUN_CONFIG_OPERATION_ID_MAX 128
#define RUN_CONFIG_MANAGER_MAX 64
#define RUN_CONFIG_FINGERPRINT_MAX 256
#define RUN_CONFIG_KERNEL_MAX 256
#define RUN_CONFIG_LOADER_SHA256_MAX 64
#define RUN_CONFIG_CURRENT_ACK_VERSION 1
#define RUN_CONFIG_ARGV_VALUE_MAX 256

typedef enum run_config_mode {
    RUN_CONFIG_MODE_MANUAL = 0,
    RUN_CONFIG_MODE_UNATTENDED_BOOT = 1
} run_config_mode_t;

struct run_config {
    char operation_id[RUN_CONFIG_OPERATION_ID_MAX + 1];
    uint64_t generation;
    run_config_mode_t mode;
    char manager[RUN_CONFIG_MANAGER_MAX + 1];
    int soft_reboot;
    int disable_modules;
    uint32_t ack_version;
    char loader_sha256[RUN_CONFIG_LOADER_SHA256_MAX + 1];
    char fingerprint[RUN_CONFIG_FINGERPRINT_MAX + 1];
    char kernel[RUN_CONFIG_KERNEL_MAX + 1];
};

/**
 * Strictly parsed native argv. run_config_path points into argv and remains
 * valid for the lifetime of the process. The remaining fields are stored for
 * the Phase 4B completion protocol.
 */
struct run_config_native_args {
    uint16_t encap_port;
    uint16_t sender_port;
    uint32_t spi;
    uint8_t aes_key[32];
    const char *run_config_path;
};

/* Returns zero only after all file, schema, and policy checks succeed. */
int run_config_parse_file(const char *path, struct run_config *out);

/* Strict, range-checked native argv parser used by exp.c before any patch work. */
int run_config_parse_native_args(int argc, char **argv, struct run_config_native_args *out);

/* Diagnostic for the immediately preceding parse on the calling thread. */
const char *run_config_last_error(void);

/* Shared operation-ID policy, reused by the Phase 4B terminal marker protocol
 * so an unsafe ID is rejected before it reaches a path or a payload. */
int run_config_valid_operation_id(const char *value, size_t length);

/*
 * Host-test-only seam. Compiled in exclusively for the nonprivileged host test
 * target (RUN_CONFIG_TEST_HOOKS); production binaries never contain it and all
 * production file checks remain in force. The hook runs after the bytes are read
 * and before the final fstat, so a test can swap the file to prove the TOCTOU
 * identity recheck actually rejects it.
 */
#ifdef RUN_CONFIG_TEST_HOOKS
typedef void (*run_config_test_mutator)(void *context);
void run_config_set_test_mutator(run_config_test_mutator mutator, void *context);
#endif

#endif
