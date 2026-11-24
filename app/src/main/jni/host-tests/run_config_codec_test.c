/* Modified 2026-11-24 (DFRoot GZIF): exercise strict run-config parsing with harmless host files. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): exercise strict native argv parsing with harmless host files. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): cover uppercase hex, empty paths, NULL output, and TOCTOU swaps. See NOTICE. */
#include "run_config_codec.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static const char VALID_CONFIG[] =
    "DFROOT_RUN_CONFIG 1\n"
    "operation_id=9d50d1d4-51af-4de5-8d68-2819df6f1201\n"
    "generation=42\n"
    "mode=manual\n"
    "manager=com.rifsxd.ksunext\n"
    "soft_reboot=1\n"
    "disable_modules=1\n"
    "ack_version=1\n"
    "loader_sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n"
    "fingerprint=vendor/device:17/build:user/release-keys\n"
    "kernel=5.15.197-android13-8-test\n";

static char root[PATH_MAX];
static char config_path[PATH_MAX];
static char source_path[PATH_MAX];

static void die(const char *message)
{
    fprintf(stderr, "%s\n", message);
    exit(1);
}

static void reset_root(const char *case_root)
{
    if (snprintf(root, sizeof(root), "%s", case_root) >= (int)sizeof(root)) {
        die("test root path is too long");
    }
    if (snprintf(config_path, sizeof(config_path), "%s/run_config", root) >= (int)sizeof(config_path)
        || snprintf(source_path, sizeof(source_path), "%s/source", root) >= (int)sizeof(source_path)) {
        die("test file path is too long");
    }
    remove(config_path);
    remove(source_path);
    rmdir(root);
    if (mkdir(root, 0700) != 0) {
        perror("mkdir test root");
        exit(1);
    }
}

static void cleanup_root(void)
{
    remove(config_path);
    remove(source_path);
    rmdir(root);
}

static void write_bytes(const char *path, const void *data, size_t length, mode_t mode)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, mode);
    if (fd < 0) {
        perror("open fixture");
        exit(1);
    }
    const unsigned char *bytes = data;
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(fd, bytes + offset, length - offset);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            perror("write fixture");
            exit(1);
        }
        offset += (size_t)written;
    }
    if (fchmod(fd, mode) != 0 || close(fd) != 0) {
        perror("finalize fixture");
        exit(1);
    }
}

static void write_text(const char *path, const char *text, mode_t mode)
{
    write_bytes(path, text, strlen(text), mode);
}

static char *replace_once(const char *text, const char *needle, const char *replacement)
{
    const char *found = strstr(text, needle);
    if (found == NULL) {
        die("fixture replacement target is missing");
    }
    size_t prefix = (size_t)(found - text);
    size_t needle_length = strlen(needle);
    size_t replacement_length = strlen(replacement);
    size_t result_length = strlen(text) - needle_length + replacement_length;
    char *result = malloc(result_length + 1);
    if (result == NULL) {
        die("fixture allocation failed");
    }
    memcpy(result, text, prefix);
    memcpy(result + prefix, replacement, replacement_length);
    memcpy(result + prefix + replacement_length, found + needle_length,
           strlen(found + needle_length) + 1);
    return result;
}

static void write_valid(void)
{
    write_text(config_path, VALID_CONFIG, 0600);
}

static void expect_reject_impl(const char *expected_reason)
{
    struct run_config parsed;
    memset(&parsed, 0xa5, sizeof(parsed));
    if (run_config_parse_file(config_path, &parsed) == 0) {
        fprintf(stderr, "malformed fixture was accepted: %s\n", config_path);
        exit(1);
    }
    if (run_config_last_error() == NULL || run_config_last_error()[0] == '\0') {
        die("rejection did not provide a diagnostic");
    }
    if (expected_reason != NULL && strstr(run_config_last_error(), expected_reason) == NULL) {
        fprintf(stderr, "wrong rejection reason: expected '%s', got '%s'\n",
                expected_reason, run_config_last_error());
        exit(1);
    }
    if (parsed.operation_id[0] != '\0' || parsed.generation != 0) {
        die("failed parse exposed a partial snapshot");
    }
}

static void expect_reject(void)
{
    expect_reject_impl(NULL);
}

static void test_valid_boot(void)
{
    char *config = replace_once(VALID_CONFIG, "mode=manual\n", "mode=unattended_boot\n");
    config = replace_once(config, "soft_reboot=1\n", "soft_reboot=0\n");
    config = replace_once(config, "manager=com.rifsxd.ksunext\n", "manager=me.weishu.kernelsu\n");
    write_text(config_path, config, 0600);
    free(config);

    struct run_config parsed;
    if (run_config_parse_file(config_path, &parsed) != 0) {
        die("valid boot snapshot was rejected");
    }
    if (parsed.mode != RUN_CONFIG_MODE_UNATTENDED_BOOT || parsed.soft_reboot != 0
        || strcmp(parsed.manager, "me.weishu.kernelsu") != 0) {
        die("valid boot snapshot fields differ");
    }
}

/* Uppercase hex is accepted for both the loader pin and the native AES key. */
static void test_valid_hash_uppercase(void)
{
    char *config = replace_once(VALID_CONFIG,
            "loader_sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n",
            "loader_sha256=0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF\n");
    write_text(config_path, config, 0600);
    free(config);

    struct run_config parsed;
    if (run_config_parse_file(config_path, &parsed) != 0) {
        die("uppercase loader hash fixture was rejected");
    }
    if (strcmp(parsed.loader_sha256,
               "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF") != 0) {
        die("uppercase loader hash was not retained verbatim");
    }
}

static void test_truncated_line(void)
{
    write_bytes(config_path, VALID_CONFIG, sizeof(VALID_CONFIG) - 12, 0600);
    expect_reject();
}

static void test_truncated_no_newline(void)
{
    write_bytes(config_path, VALID_CONFIG, sizeof(VALID_CONFIG) - 2, 0600);
    expect_reject();
}

static void test_oversized(void)
{
    unsigned char *bytes = malloc(RUN_CONFIG_MAX_BYTES + 1);
    if (bytes == NULL) {
        die("oversized fixture allocation failed");
    }
    memset(bytes, 'A', RUN_CONFIG_MAX_BYTES + 1);
    write_bytes(config_path, bytes, RUN_CONFIG_MAX_BYTES + 1, 0600);
    free(bytes);
    expect_reject();
}

static void test_invalid_id_too_long(void)
{
    size_t length = strlen("operation_id=\n") + RUN_CONFIG_OPERATION_ID_MAX + 1;
    char *fixture = malloc(sizeof(VALID_CONFIG) + length);
    if (fixture == NULL) {
        die("long ID fixture allocation failed");
    }
    size_t offset = 0;
    memcpy(fixture + offset, "operation_id=", strlen("operation_id="));
    offset += strlen("operation_id=");
    memset(fixture + offset, 'x', RUN_CONFIG_OPERATION_ID_MAX + 1);
    offset += RUN_CONFIG_OPERATION_ID_MAX + 1;
    fixture[offset++] = '\n';
    memcpy(fixture + offset, VALID_CONFIG + strlen("operation_id=9d50d1d4-51af-4de5-8d68-2819df6f1201\n"),
           sizeof(VALID_CONFIG) - strlen("operation_id=9d50d1d4-51af-4de5-8d68-2819df6f1201\n"));
    write_bytes(config_path, fixture,
                offset + sizeof(VALID_CONFIG) - strlen("operation_id=9d50d1d4-51af-4de5-8d68-2819df6f1201\n"),
                0600);
    free(fixture);
    expect_reject();
}

static void test_control_nul(void)
{
    unsigned char bytes[sizeof(VALID_CONFIG) - 1];
    memcpy(bytes, VALID_CONFIG, sizeof(bytes));
    unsigned char *fingerprint = (unsigned char *)strstr((char *)bytes, "fingerprint=");
    if (fingerprint == NULL) {
        die("fingerprint fixture is missing");
    }
    fingerprint[strlen("fingerprint=")] = '\0';
    write_bytes(config_path, bytes, sizeof(bytes), 0600);
    expect_reject();
}

static void test_symlink(void)
{
    write_valid();
    if (rename(config_path, source_path) != 0) {
        perror("rename symlink source");
        exit(1);
    }
    if (symlink(source_path, config_path) != 0) {
        perror("create symlink fixture");
        exit(1);
    }
    expect_reject();
}

static void test_nonregular(void)
{
    if (mkdir(config_path, 0700) != 0) {
        perror("create directory fixture");
        exit(1);
    }
    expect_reject();
}

#define DEFAULT_ENCAP "40000"
#define DEFAULT_SENDER "50000"
#define DEFAULT_SPI "123"
#define DEFAULT_KEY "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
#define UPPER_KEY "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
#define DEFAULT_PATH "/data/user_de/0/df.root/run_config"
#define ARG_BUFFER_BYTES 320

static char arg_encap[ARG_BUFFER_BYTES];
static char arg_sender[ARG_BUFFER_BYTES];
static char arg_spi[ARG_BUFFER_BYTES];
static char arg_key[ARG_BUFFER_BYTES];
static char arg_path[ARG_BUFFER_BYTES];
static char *arg_argv[14];

static char **build_args(const char *encap, const char *sender, const char *spi,
                         const char *key, const char *path)
{
    snprintf(arg_encap, sizeof(arg_encap), "%s", encap);
    snprintf(arg_sender, sizeof(arg_sender), "%s", sender);
    snprintf(arg_spi, sizeof(arg_spi), "%s", spi);
    snprintf(arg_key, sizeof(arg_key), "%s", key);
    snprintf(arg_path, sizeof(arg_path), "%s", path);
    arg_argv[0] = "exp";
    arg_argv[1] = "--encap-port";
    arg_argv[2] = arg_encap;
    arg_argv[3] = "--sender-port";
    arg_argv[4] = arg_sender;
    arg_argv[5] = "--spi";
    arg_argv[6] = arg_spi;
    arg_argv[7] = "--aes-key";
    arg_argv[8] = arg_key;
    arg_argv[9] = "--run-config";
    arg_argv[10] = arg_path;
    arg_argv[11] = NULL;
    return arg_argv;
}

/* A FIFO carrying a valid snapshot is still not a regular file: the codec must
 * reject it at the descriptor check instead of parsing the streamed content. */
static void test_fifo(void)
{
    if (mkfifo(config_path, 0600) != 0) {
        perror("create fifo fixture");
        exit(1);
    }
    pid_t child = fork();
    if (child < 0) {
        perror("fork fifo writer");
        exit(1);
    }
    if (child == 0) {
        int fd = -1;
        struct timespec delay = {0, 10000000};
        for (int attempt = 0; attempt < 200 && fd < 0; attempt++) {
            fd = open(config_path, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
            if (fd < 0) nanosleep(&delay, NULL);
        }
        if (fd >= 0) {
            const char *bytes = VALID_CONFIG;
            size_t left = sizeof(VALID_CONFIG) - 1;
            while (left > 0) {
                ssize_t written = write(fd, bytes, left);
                if (written <= 0) break;
                bytes += written;
                left -= (size_t)written;
            }
            close(fd);
        }
        _exit(0);
    }
    expect_reject_impl("run-config must be a regular file");
    int status;
    waitpid(child, &status, 0);
}

static int argv_length(char *const values[])
{
    int length = 0;
    while (values[length] != NULL) length++;
    return length;
}

static void expect_args_reject(char *values[])
{
    struct run_config_native_args parsed;
    memset(&parsed, 0xa5, sizeof(parsed));
    if (run_config_parse_native_args(argv_length(values), values, &parsed) == 0) {
        die("invalid native argv was accepted");
    }
    if (run_config_last_error() == NULL || run_config_last_error()[0] == '\0') {
        die("argv rejection did not provide a diagnostic");
    }
}

static void test_args_valid(void)
{
    struct run_config_native_args parsed;
    char **values = build_args(DEFAULT_ENCAP, DEFAULT_SENDER, "4294967295",
                               DEFAULT_KEY, DEFAULT_PATH);
    if (run_config_parse_native_args(argv_length(values), values, &parsed) != 0) {
        die("valid native argv was rejected");
    }
    static const uint8_t expected_key[32] = {
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
    };
    if (parsed.encap_port != 40000 || parsed.sender_port != 50000
        || parsed.spi != UINT32_MAX
        || memcmp(parsed.aes_key, expected_key, sizeof(expected_key)) != 0
        || strcmp(parsed.run_config_path, DEFAULT_PATH) != 0) {
        die("valid native argv fields differ");
    }
}

static void test_args_key_uppercase(void)
{
    struct run_config_native_args parsed;
    char **values = build_args(DEFAULT_ENCAP, DEFAULT_SENDER, DEFAULT_SPI,
                               UPPER_KEY, DEFAULT_PATH);
    if (run_config_parse_native_args(argv_length(values), values, &parsed) != 0) {
        die("uppercase AES key argv was rejected");
    }
    static const uint8_t expected_key[32] = {
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
    };
    if (memcmp(parsed.aes_key, expected_key, sizeof(expected_key)) != 0) {
        die("uppercase AES key decoded differently");
    }
}

static void test_null_outputs(void)
{
    char **values = build_args(DEFAULT_ENCAP, DEFAULT_SENDER, DEFAULT_SPI,
                               DEFAULT_KEY, DEFAULT_PATH);
    if (run_config_parse_native_args(argv_length(values), values, NULL) == 0) {
        die("NULL native argv output was accepted");
    }
    if (run_config_last_error() == NULL || run_config_last_error()[0] == '\0') {
        die("NULL native argv output had no diagnostic");
    }
    write_valid();
    if (run_config_parse_file(config_path, NULL) == 0) {
        die("NULL file output was accepted");
    }
    if (run_config_last_error() == NULL || run_config_last_error()[0] == '\0') {
        die("NULL file output had no diagnostic");
    }
}

/* The descriptor keeps its inode across a path rename, so the observable
 * mid-read change is an in-place size/permission change on that same inode. */
static void mutate_file_in_place(void *context)
{
    (void)context;
    if (truncate(config_path, 0) != 0) {
        die("TOCTOU fixture mutation failed");
    }
}

static void test_toctou_identity_mismatch(void)
{
    write_valid();
    run_config_set_test_mutator(mutate_file_in_place, NULL);
    expect_reject_impl("run-config changed while it was read");
    run_config_set_test_mutator(NULL, NULL);
}

static void test_args_missing(void)
{
    char *missing_run_config[] = { "exp", "--encap-port", DEFAULT_ENCAP, NULL };
    char *missing_sender[] = {
        "exp", "--encap-port", DEFAULT_ENCAP, "--spi", DEFAULT_SPI,
        "--aes-key", DEFAULT_KEY, "--run-config", DEFAULT_PATH, NULL
    };
    expect_args_reject(missing_run_config);
    expect_args_reject(missing_sender);
}

static void test_args_unknown_and_duplicate(void)
{
    char *unknown[] = {
        "exp", "--encap-port", DEFAULT_ENCAP, "--sender-port", DEFAULT_SENDER,
        "--spi", DEFAULT_SPI, "--aes-key", DEFAULT_KEY, "--run-config", DEFAULT_PATH,
        "--bogus", "1", NULL
    };
    char *duplicate[] = {
        "exp", "--encap-port", DEFAULT_ENCAP, "--sender-port", DEFAULT_SENDER,
        "--spi", DEFAULT_SPI, "--spi", "9", "--aes-key", DEFAULT_KEY,
        "--run-config", DEFAULT_PATH, NULL
    };
    expect_args_reject(unknown);
    expect_args_reject(duplicate);
}

static void run_case(const char *name)
{
    if (strcmp(name, "valid_full") == 0) {
        write_valid();
        struct run_config parsed;
        if (run_config_parse_file(config_path, &parsed) != 0) {
            die("valid full snapshot was rejected");
        }
        if (strcmp(parsed.operation_id, "9d50d1d4-51af-4de5-8d68-2819df6f1201") != 0
            || parsed.generation != 42 || parsed.mode != RUN_CONFIG_MODE_MANUAL
            || strcmp(parsed.manager, "com.rifsxd.ksunext") != 0 || parsed.soft_reboot != 1
            || parsed.disable_modules != 1 || parsed.ack_version != 1
            || strcmp(parsed.loader_sha256,
                      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef") != 0
            || strcmp(parsed.fingerprint, "vendor/device:17/build:user/release-keys") != 0
            || strcmp(parsed.kernel, "5.15.197-android13-8-test") != 0) {
            die("valid full snapshot fields differ");
        }
        return;
    }
    if (strcmp(name, "valid_boot") == 0) {
        test_valid_boot();
        return;
    }
    if (strcmp(name, "valid_hash_uppercase") == 0) {
        test_valid_hash_uppercase();
        return;
    }
    if (strcmp(name, "missing") == 0) {
        expect_reject();
        return;
    }
    if (strcmp(name, "empty") == 0) {
        write_bytes(config_path, "", 0, 0600);
        expect_reject();
        return;
    }
    if (strcmp(name, "truncated_header") == 0) {
        write_text(config_path, "DFROOT_RUN_CONFIG 1\n", 0600);
        expect_reject();
        return;
    }
    if (strcmp(name, "wrong_version") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "DFROOT_RUN_CONFIG 1\n",
                                     "DFROOT_RUN_CONFIG 2\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "truncated_line") == 0) {
        test_truncated_line();
        return;
    }
    if (strcmp(name, "truncated_no_newline") == 0) {
        test_truncated_no_newline();
        return;
    }
    if (strcmp(name, "oversized") == 0) {
        test_oversized();
        return;
    }
    if (strcmp(name, "malformed_line") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "generation=42\n", "generation 42\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "malformed_extra_equals") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "fingerprint=vendor/device:17/build:user/release-keys\n",
                                     "fingerprint=vendor=device\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "equals_in_value") == 0) {
        char *fixture = replace_once(VALID_CONFIG,
                                     "kernel=5.15.197-android13-8-test\n",
                                     "kernel=5.15=android13-8-test\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject_impl("exactly one key=value separator");
        return;
    }
    if (strcmp(name, "missing_key") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "kernel=5.15.197-android13-8-test\n", "");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "duplicate_key") == 0) {
        size_t length = sizeof(VALID_CONFIG) + strlen("generation=43\n");
        char *fixture = malloc(length);
        if (fixture == NULL) {
            die("duplicate fixture allocation failed");
        }
        snprintf(fixture, length, "%sgeneration=43\n", VALID_CONFIG);
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "unknown_key") == 0) {
        size_t length = sizeof(VALID_CONFIG) + strlen("unknown=value\n");
        char *fixture = malloc(length);
        if (fixture == NULL) {
            die("unknown fixture allocation failed");
        }
        snprintf(fixture, length, "%sunknown=value\n", VALID_CONFIG);
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "unsupported_manager") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "manager=com.rifsxd.ksunext\n",
                                     "manager=example.manager\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "unsupported_mode") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "mode=manual\n", "mode=automatic\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_hash_short") == 0) {
        char *fixture = replace_once(VALID_CONFIG,
                                     "loader_sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n",
                                     "loader_sha256=abc\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_hash_nonhex") == 0) {
        char *fixture = replace_once(VALID_CONFIG,
                                     "loader_sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n",
                                     "loader_sha256=z123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_id_empty") == 0) {
        char *fixture = replace_once(VALID_CONFIG,
                                     "operation_id=9d50d1d4-51af-4de5-8d68-2819df6f1201\n",
                                     "operation_id=\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_id_charset") == 0) {
        char *fixture = replace_once(VALID_CONFIG,
                                     "operation_id=9d50d1d4-51af-4de5-8d68-2819df6f1201\n",
                                     "operation_id=bad id\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_id_too_long") == 0) {
        test_invalid_id_too_long();
        return;
    }
    if (strcmp(name, "invalid_generation_negative") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "generation=42\n", "generation=-1\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_generation_overflow") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "generation=42\n",
                                     "generation=18446744073709551616\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_generation_nondecimal") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "generation=42\n", "generation=0x2a\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_ack_overflow") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "ack_version=1\n", "ack_version=4294967296\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_ack_negative") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "ack_version=1\n", "ack_version=-1\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "invalid_boolean") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "disable_modules=1\n", "disable_modules=true\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "conflicting_boot_soft_reboot") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "mode=manual\n", "mode=unattended_boot\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "conflicting_ack_version") == 0) {
        char *fixture = replace_once(VALID_CONFIG, "ack_version=1\n", "ack_version=0\n");
        write_text(config_path, fixture, 0600);
        free(fixture);
        expect_reject();
        return;
    }
    if (strcmp(name, "control_cr") == 0) {
        unsigned char bytes[sizeof(VALID_CONFIG) - 1];
        memcpy(bytes, VALID_CONFIG, sizeof(bytes));
        unsigned char *fingerprint = (unsigned char *)strstr((char *)bytes, "fingerprint=");
        if (fingerprint == NULL) {
            die("fingerprint fixture is missing");
        }
        fingerprint[strlen("fingerprint=")] = '\r';
        write_bytes(config_path, bytes, sizeof(bytes), 0600);
        expect_reject();
        return;
    }
    if (strcmp(name, "control_nul") == 0) {
        test_control_nul();
        return;
    }
    if (strcmp(name, "symlink") == 0) {
        test_symlink();
        return;
    }
    if (strcmp(name, "group_writable") == 0) {
        write_valid();
        if (chmod(config_path, 0660) != 0) {
            perror("chmod group writable");
            exit(1);
        }
        expect_reject();
        return;
    }
    if (strcmp(name, "other_writable") == 0) {
        write_valid();
        if (chmod(config_path, 0602) != 0) {
            perror("chmod other writable");
            exit(1);
        }
        expect_reject();
        return;
    }
    if (strcmp(name, "nonregular") == 0) {
        test_nonregular();
        return;
    }
    if (strcmp(name, "fifo") == 0) {
        test_fifo();
        return;
    }
    if (strcmp(name, "args_valid") == 0) {
        test_args_valid();
        return;
    }
    if (strcmp(name, "args_key_uppercase") == 0) {
        test_args_key_uppercase();
        return;
    }
    if (strcmp(name, "args_null_output") == 0 || strcmp(name, "file_null_output") == 0) {
        test_null_outputs();
        return;
    }
    if (strcmp(name, "toctou_identity_mismatch") == 0) {
        test_toctou_identity_mismatch();
        return;
    }
    if (strcmp(name, "args_missing_run_config") == 0
        || strcmp(name, "args_missing_port") == 0) {
        test_args_missing();
        return;
    }
    if (strcmp(name, "args_unknown") == 0 || strcmp(name, "args_duplicate") == 0) {
        test_args_unknown_and_duplicate();
        return;
    }
    if (strcmp(name, "args_encap_zero") == 0) {
        expect_args_reject(build_args("0", DEFAULT_SENDER, DEFAULT_SPI, DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_encap_overflow") == 0) {
        expect_args_reject(build_args("65536", DEFAULT_SENDER, DEFAULT_SPI, DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_encap_nondecimal") == 0) {
        expect_args_reject(build_args("80abc", DEFAULT_SENDER, DEFAULT_SPI, DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_encap_hex") == 0) {
        expect_args_reject(build_args("0x50", DEFAULT_SENDER, DEFAULT_SPI, DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_encap_negative") == 0) {
        expect_args_reject(build_args("-1", DEFAULT_SENDER, DEFAULT_SPI, DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_encap_leading_zero") == 0) {
        expect_args_reject(build_args("080", DEFAULT_SENDER, DEFAULT_SPI, DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_encap_empty") == 0) {
        expect_args_reject(build_args("", DEFAULT_SENDER, DEFAULT_SPI, DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_sender_overflow") == 0) {
        expect_args_reject(build_args(DEFAULT_ENCAP, "65536", DEFAULT_SPI, DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_spi_overflow") == 0) {
        expect_args_reject(build_args(DEFAULT_ENCAP, DEFAULT_SENDER,
                                      "4294967296", DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_spi_negative") == 0) {
        expect_args_reject(build_args(DEFAULT_ENCAP, DEFAULT_SENDER, "-1", DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_spi_max") == 0) {
        test_args_valid();
        return;
    }
    if (strcmp(name, "args_key_short") == 0) {
        expect_args_reject(build_args(DEFAULT_ENCAP, DEFAULT_SENDER, DEFAULT_SPI,
                                      "0123456789abcdef", DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_key_long") == 0) {
        expect_args_reject(build_args(DEFAULT_ENCAP, DEFAULT_SENDER, DEFAULT_SPI,
                                      DEFAULT_KEY "00", DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_key_nonhex") == 0) {
        expect_args_reject(build_args(DEFAULT_ENCAP, DEFAULT_SENDER, DEFAULT_SPI,
                                      "z123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                                      DEFAULT_PATH));
        return;
    }
    if (strcmp(name, "args_path_control") == 0) {
        expect_args_reject(build_args(DEFAULT_ENCAP, DEFAULT_SENDER, DEFAULT_SPI,
                                      DEFAULT_KEY, "/data/user_de/0/df.root/run\nconfig"));
        return;
    }
    if (strcmp(name, "args_path_empty") == 0) {
        expect_args_reject(build_args(DEFAULT_ENCAP, DEFAULT_SENDER, DEFAULT_SPI,
                                      DEFAULT_KEY, ""));
        return;
    }
    if (strcmp(name, "args_value_too_long") == 0) {
        char long_value[ARG_BUFFER_BYTES + 1];
        memset(long_value, '7', sizeof(long_value) - 1);
        long_value[sizeof(long_value) - 1] = '\0';
        expect_args_reject(build_args(long_value, DEFAULT_SENDER, DEFAULT_SPI,
                                      DEFAULT_KEY, DEFAULT_PATH));
        return;
    }
    die("unknown test case");
}

int main(int argc, char **argv)
{
    if (argc != 3) {
        die("usage: run_config_codec_test CASE ROOT");
    }
    reset_root(argv[2]);
    run_case(argv[1]);
    cleanup_root();
    return 0;
}
