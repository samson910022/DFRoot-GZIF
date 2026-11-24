/* Modified 2026-11-24 (DFRoot GZIF): parse the private run-config artifact without following links. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): add strict native argv parsing and always emit a diagnostic. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): add a host-only mid-read mutation seam for the TOCTOU recheck. See NOTICE. */
#include "run_config_codec.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static char parse_error[160];

#ifdef RUN_CONFIG_TEST_HOOKS
static run_config_test_mutator test_mutator;
static void *test_mutator_context;

void run_config_set_test_mutator(run_config_test_mutator mutator, void *context)
{
    test_mutator = mutator;
    test_mutator_context = context;
}
#endif

enum field_index {
    FIELD_OPERATION_ID = 0,
    FIELD_GENERATION,
    FIELD_MODE,
    FIELD_MANAGER,
    FIELD_SOFT_REBOOT,
    FIELD_DISABLE_MODULES,
    FIELD_ACK_VERSION,
    FIELD_LOADER_SHA256,
    FIELD_FINGERPRINT,
    FIELD_KERNEL,
    FIELD_COUNT
};

#define ALL_FIELDS ((1u << FIELD_COUNT) - 1u)

static int fail(const char *message)
{
    snprintf(parse_error, sizeof(parse_error), "%s", message);
    return -1;
}

static int value_equals(const char *value, size_t length, const char *expected)
{
    size_t expected_length = strlen(expected);
    return length == expected_length && memcmp(value, expected, length) == 0;
}

static int copy_value(char *destination, size_t capacity, const char *value, size_t length,
                      const char *field)
{
    if (length == 0 || length >= capacity) {
        return fail(field);
    }
    memcpy(destination, value, length);
    destination[length] = '\0';
    return 0;
}

static int parse_unsigned(const char *value, size_t length, uint64_t maximum, uint64_t *out)
{
    if (length == 0 || (length > 1 && value[0] == '0')) {
        return fail("decimal fields must use canonical digits");
    }
    uint64_t result = 0;
    for (size_t i = 0; i < length; i++) {
        if (value[i] < '0' || value[i] > '9') {
            return fail("decimal fields must contain decimal digits only");
        }
        uint64_t digit = (uint64_t)(value[i] - '0');
        if (result > (maximum - digit) / 10) {
            return fail("decimal field exceeds its range");
        }
        result = result * 10 + digit;
    }
    *out = result;
    return 0;
}

static int hex_digit(unsigned char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int parse_hex_key(const char *value, size_t length, uint8_t *out, size_t out_length)
{
    if (length != out_length * 2) {
        return fail("AES key must be exactly 64 hexadecimal characters");
    }
    for (size_t i = 0; i < out_length; i++) {
        int high = hex_digit((unsigned char)value[i * 2]);
        int low = hex_digit((unsigned char)value[i * 2 + 1]);
        if (high < 0 || low < 0) {
            return fail("AES key must contain only hexadecimal characters");
        }
        out[i] = (uint8_t)((high << 4) | low);
    }
    return 0;
}

static int parse_boolean(const char *value, size_t length, int *out)
{
    if (length == 1 && value[0] == '0') {
        *out = 0;
        return 0;
    }
    if (length == 1 && value[0] == '1') {
        *out = 1;
        return 0;
    }
    return fail("boolean fields must be exactly 0 or 1");
}

static int valid_operation_id(const char *value, size_t length)
{
    if (length == 0 || length > RUN_CONFIG_OPERATION_ID_MAX) {
        return 0;
    }
    for (size_t i = 0; i < length; i++) {
        unsigned char c = (unsigned char)value[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == ':' || c == '-')) {
            return 0;
        }
    }
    return 1;
}

static int valid_loader_hash(const char *value, size_t length)
{
    if (length != RUN_CONFIG_LOADER_SHA256_MAX) {
        return 0;
    }
    for (size_t i = 0; i < length; i++) {
        unsigned char c = (unsigned char)value[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
              || (c >= 'A' && c <= 'F'))) {
            return 0;
        }
    }
    return 1;
}

static int field_index(const char *key, size_t length)
{
    static const char *const names[FIELD_COUNT] = {
        "operation_id", "generation", "mode", "manager", "soft_reboot",
        "disable_modules", "ack_version", "loader_sha256", "fingerprint", "kernel"
    };
    for (int i = 0; i < FIELD_COUNT; i++) {
        if (strlen(names[i]) == length && memcmp(key, names[i], length) == 0) {
            return i;
        }
    }
    return -1;
}

static int parse_buffer(const char *buffer, size_t length, struct run_config *out)
{
    static const char header[] = RUN_CONFIG_HEADER "\n";
    size_t header_length = sizeof(header) - 1;
    if (length < header_length || memcmp(buffer, header, header_length) != 0) {
        return fail("run-config header or version is invalid");
    }

    unsigned int seen = 0;
    size_t position = header_length;
    while (position < length) {
        size_t end = position;
        while (end < length && buffer[end] != '\n') {
            end++;
        }
        if (end == length) {
            return fail("run-config line is truncated");
        }
        size_t line_length = end - position;
        if (line_length == 0) {
            return fail("empty run-config lines are not allowed");
        }
        for (size_t i = position; i < end; i++) {
            unsigned char c = (unsigned char)buffer[i];
            if (c < 0x20 || c == 0x7f) {
                return fail("run-config contains a NUL or control character");
            }
        }

        const char *equals = memchr(buffer + position, '=', line_length);
        if (equals == NULL || equals == buffer + position
            || memchr(equals + 1, '=', (size_t)((buffer + end) - (equals + 1))) != NULL) {
            return fail("run-config line must contain exactly one key=value separator");
        }
        size_t key_length = (size_t)(equals - (buffer + position));
        const char *value = equals + 1;
        size_t value_length = (size_t)((buffer + end) - value);
        int index = field_index(buffer + position, key_length);
        if (index < 0) {
            return fail("run-config contains an unknown key");
        }
        unsigned int bit = 1u << index;
        if ((seen & bit) != 0) {
            return fail("run-config contains a duplicate key");
        }

        uint64_t number = 0;
        switch (index) {
            case FIELD_OPERATION_ID:
                if (!valid_operation_id(value, value_length)) {
                    return fail("operation ID has an unsafe charset or length");
                }
                if (copy_value(out->operation_id, sizeof(out->operation_id), value,
                               value_length, "operation ID is too long") != 0) {
                    return -1;
                }
                break;
            case FIELD_GENERATION:
                if (parse_unsigned(value, value_length, UINT64_MAX, &number) != 0) {
                    return -1;
                }
                out->generation = number;
                break;
            case FIELD_MODE:
                if (value_equals(value, value_length, "manual")) {
                    out->mode = RUN_CONFIG_MODE_MANUAL;
                } else if (value_equals(value, value_length, "unattended_boot")) {
                    out->mode = RUN_CONFIG_MODE_UNATTENDED_BOOT;
                } else {
                    return fail("run-config mode is unsupported");
                }
                break;
            case FIELD_MANAGER:
                if (!value_equals(value, value_length, "me.weishu.kernelsu")
                    && !value_equals(value, value_length, "com.rifsxd.ksunext")) {
                    return fail("run-config manager is unsupported");
                }
                if (copy_value(out->manager, sizeof(out->manager), value, value_length,
                               "run-config manager is too long") != 0) {
                    return -1;
                }
                break;
            case FIELD_SOFT_REBOOT:
                if (parse_boolean(value, value_length, &out->soft_reboot) != 0) {
                    return -1;
                }
                break;
            case FIELD_DISABLE_MODULES:
                if (parse_boolean(value, value_length, &out->disable_modules) != 0) {
                    return -1;
                }
                break;
            case FIELD_ACK_VERSION:
                if (parse_unsigned(value, value_length, UINT32_MAX, &number) != 0) {
                    return -1;
                }
                out->ack_version = (uint32_t)number;
                break;
            case FIELD_LOADER_SHA256:
                if (!valid_loader_hash(value, value_length)) {
                    return fail("loader SHA-256 must be 64 hexadecimal characters");
                }
                if (copy_value(out->loader_sha256, sizeof(out->loader_sha256), value,
                               value_length, "loader SHA-256 is too long") != 0) {
                    return -1;
                }
                break;
            case FIELD_FINGERPRINT:
                if (copy_value(out->fingerprint, sizeof(out->fingerprint), value, value_length,
                               "fingerprint is empty or too long") != 0) {
                    return -1;
                }
                break;
            case FIELD_KERNEL:
                if (copy_value(out->kernel, sizeof(out->kernel), value, value_length,
                               "kernel is empty or too long") != 0) {
                    return -1;
                }
                break;
            default:
                return fail("run-config field index is invalid");
        }
        seen |= bit;
        position = end + 1;
    }

    if (seen != ALL_FIELDS) {
        return fail("run-config is missing required keys");
    }
    if (out->mode == RUN_CONFIG_MODE_UNATTENDED_BOOT && out->soft_reboot) {
        return fail("unattended boot conflicts with soft reboot");
    }
    if (out->soft_reboot && out->ack_version != RUN_CONFIG_CURRENT_ACK_VERSION) {
        return fail("soft reboot requires the current acknowledgement version");
    }
    return 0;
}

const char *run_config_last_error(void)
{
    return parse_error;
}

int run_config_parse_native_args(int argc, char **argv, struct run_config_native_args *out)
{
    if (out == NULL) {
        return fail("native argument output is required");
    }
    memset(out, 0, sizeof(*out));
    if (argv == NULL || argv[0] == NULL) {
        return fail("native argv is required");
    }

    uint64_t encap_port = 0;
    uint64_t sender_port = 0;
    uint64_t spi = 0;
    int have_encap = 0;
    int have_sender = 0;
    int have_spi = 0;
    int have_key = 0;
    int have_path = 0;

    for (int i = 1; i < argc; i++) {
        const char *name = argv[i];
        if (name == NULL || i + 1 >= argc) {
            return fail("native argument is missing its value");
        }
        const char *value = argv[++i];
        if (value == NULL || value[0] == '\0') {
            return fail("native argument value is empty");
        }
        size_t length = strlen(value);
        if (length > RUN_CONFIG_ARGV_VALUE_MAX) {
            return fail("native argument value is too long");
        }

        if (strcmp(name, "--encap-port") == 0) {
            if (have_encap) return fail("duplicate native argument --encap-port");
            if (parse_unsigned(value, length, UINT16_MAX, &encap_port) != 0) return -1;
            if (encap_port == 0) return fail("encap port must be in 1..65535");
            have_encap = 1;
        } else if (strcmp(name, "--sender-port") == 0) {
            if (have_sender) return fail("duplicate native argument --sender-port");
            if (parse_unsigned(value, length, UINT16_MAX, &sender_port) != 0) return -1;
            if (sender_port == 0) return fail("sender port must be in 1..65535");
            have_sender = 1;
        } else if (strcmp(name, "--spi") == 0) {
            if (have_spi) return fail("duplicate native argument --spi");
            if (parse_unsigned(value, length, UINT32_MAX, &spi) != 0) return -1;
            if (spi == 0) return fail("SPI must be in 1..4294967295");
            have_spi = 1;
        } else if (strcmp(name, "--aes-key") == 0) {
            if (have_key) return fail("duplicate native argument --aes-key");
            if (parse_hex_key(value, length, out->aes_key, sizeof(out->aes_key)) != 0) return -1;
            have_key = 1;
        } else if (strcmp(name, "--run-config") == 0) {
            if (have_path) return fail("duplicate native argument --run-config");
            for (size_t j = 0; j < length; j++) {
                unsigned char c = (unsigned char)value[j];
                if (c < 0x20 || c == 0x7f) {
                    return fail("run-config path contains a control character");
                }
            }
            out->run_config_path = value;
            have_path = 1;
        } else {
            return fail("unknown native argument");
        }
    }

    if (!have_encap || !have_sender || !have_spi || !have_key || !have_path) {
        return fail("native arguments are incomplete: --run-config is required");
    }
    out->encap_port = (uint16_t)encap_port;
    out->sender_port = (uint16_t)sender_port;
    out->spi = (uint32_t)spi;
    return 0;
}

int run_config_parse_file(const char *path, struct run_config *out)
{
    if (out == NULL) {
        return fail("run-config output is required");
    }
    memset(out, 0, sizeof(*out));
    if (path == NULL || path[0] == '\0') {
        return fail("run-config path is required");
    }

    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        return fail("run-config open failed or target is a symlink");
    }
    struct stat before;
    if (fstat(fd, &before) != 0) {
        close(fd);
        return fail("run-config fstat failed");
    }
    if (!S_ISREG(before.st_mode)) {
        close(fd);
        return fail("run-config must be a regular file");
    }
    if ((before.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        close(fd);
        return fail("run-config must not be group or other writable");
    }
    if (before.st_size < 0 || before.st_size > RUN_CONFIG_MAX_BYTES) {
        close(fd);
        return fail("run-config exceeds the maximum size");
    }

    char buffer[RUN_CONFIG_MAX_BYTES + 1];
    size_t length = 0;
    for (;;) {
        ssize_t count = read(fd, buffer + length, sizeof(buffer) - length);
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            close(fd);
            return fail("run-config read failed");
        }
        if (count == 0) {
            break;
        }
        length += (size_t)count;
        if (length > RUN_CONFIG_MAX_BYTES) {
            close(fd);
            return fail("run-config exceeds the maximum size");
        }
    }

#ifdef RUN_CONFIG_TEST_HOOKS
    /* Deterministic mid-read identity change; production builds omit this. */
    if (test_mutator != NULL) {
        test_mutator(test_mutator_context);
    }
#endif

    struct stat after;
    if (fstat(fd, &after) != 0) {
        close(fd);
        return fail("run-config final fstat failed");
    }
    close(fd);
    if (!S_ISREG(after.st_mode) || (after.st_mode & (S_IWGRP | S_IWOTH)) != 0
        || before.st_dev != after.st_dev || before.st_ino != after.st_ino
        || after.st_size < 0 || (uint64_t)after.st_size != (uint64_t)length) {
        return fail("run-config changed while it was read");
    }
    if (length == 0 || buffer[length - 1] != '\n') {
        return fail("run-config is empty or truncated");
    }

    int result = parse_buffer(buffer, length, out);
    if (result != 0) {
        memset(out, 0, sizeof(*out));
    }
    return result;
}
