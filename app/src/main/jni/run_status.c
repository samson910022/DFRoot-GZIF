/* Modified 2026-11-25 (DFRoot GZIF): implement checked, run-scoped terminal markers and their classifier. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): export the shared progress-marker table and route injected faults through the real write/fsync error branches. See NOTICE. */
#define _POSIX_C_SOURCE 200809L
#include "run_status.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static char status_root[RUN_STATUS_ROOT_MAX];
static char status_operation_id[RUN_CONFIG_OPERATION_ID_MAX + 1];
static int status_configured;
static char status_error[RUN_STATUS_DIAGNOSTIC_MAX];

#ifdef RUN_STATUS_TEST_HOOKS
static int injected_fault;
void run_status_inject_fault(int fault) { injected_fault = fault; }
#endif

static int status_fail(const char *message)
{
    snprintf(status_error, sizeof(status_error), "%s", message);
    return -1;
}

static int status_fail_errno(const char *what)
{
    snprintf(status_error, sizeof(status_error), "%s: %s", what, strerror(errno));
    return -1;
}

const char *run_status_last_error(void)
{
    return status_error;
}

int run_status_configure(const char *root, const char *operation_id)
{
    status_configured = 0;
    status_error[0] = '\0';
    if (root == NULL || root[0] == '\0') {
        return status_fail("marker root is required");
    }
    size_t root_length = strlen(root);
    if (root_length >= sizeof(status_root)) {
        return status_fail("marker root is too long");
    }
    if (operation_id == NULL) {
        return status_fail("operation ID is required");
    }
    size_t id_length = strlen(operation_id);
    if (!run_config_valid_operation_id(operation_id, id_length)) {
        return status_fail("operation ID has an unsafe charset or length");
    }
    memcpy(status_root, root, root_length + 1);
    memcpy(status_operation_id, operation_id, id_length + 1);
    status_configured = 1;
    return 0;
}

static int code_index(int code)
{
    switch (code) {
        case RUN_STATUS_TERMINAL_SUCCESS:       return 0;
        case RUN_STATUS_TERMINAL_ERROR:         return 1;
        case RUN_STATUS_TERMINAL_SOFT_REBOOT_OK: return 2;
        case RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL: return 3;
        default:                                return -1;
    }
}

static const int code_order[] = {
    RUN_STATUS_TERMINAL_SUCCESS,
    RUN_STATUS_TERMINAL_ERROR,
    RUN_STATUS_TERMINAL_SOFT_REBOOT_OK,
    RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL
};

static const char *code_suffix(int code)
{
    switch (code) {
        case RUN_STATUS_TERMINAL_SUCCESS:       return "rc0";
        case RUN_STATUS_TERMINAL_ERROR:         return "rc1";
        case RUN_STATUS_TERMINAL_SOFT_REBOOT_OK: return "rc4";
        case RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL: return "rc6";
        default:                                return NULL;
    }
}

static int marker_path(char *path, size_t capacity, const char *root,
                       const char *operation_id, int code)
{
    const char *suffix = code_suffix(code);
    if (suffix == NULL) {
        return -1;
    }
    int written = snprintf(path, capacity, "%s/dfroot_terminal_%s_%s",
                           root, operation_id, suffix);
    if (written < 0 || (size_t)written >= capacity) {
        return -1;
    }
    return 0;
}

static int make_payload(char *payload, size_t capacity, const char *operation_id, int code)
{
    int written = snprintf(payload, capacity,
                           "DFROOT_TERMINAL 1\noperation_id=%s\ncode=%d\n",
                           operation_id, code);
    if (written < 0 || (size_t)written >= capacity) {
        return -1;
    }
    return 0;
}

/* Parse an in-memory payload. Rejects control characters, foreign or unsafe
 * IDs, unknown codes, and any trailing bytes. */
static int parse_payload(char *payload, size_t length, char *operation_id_out, int *code_out)
{
    static const char header[] = "DFROOT_TERMINAL 1\n";
    static const char id_prefix[] = "operation_id=";
    static const char code_prefix[] = "code=";

    if (length == 0 || length > RUN_STATUS_PAYLOAD_MAX) {
        return -1;
    }
    for (size_t i = 0; i < length; i++) {
        unsigned char c = (unsigned char)payload[i];
        if (c == '\n') {
            continue;
        }
        if (c < 0x20 || c == 0x7f) {
            return -1;
        }
    }
    payload[length] = '\0';

    if (strncmp(payload, header, sizeof(header) - 1) != 0) {
        return -1;
    }
    const char *id_line = payload + sizeof(header) - 1;
    if (strncmp(id_line, id_prefix, sizeof(id_prefix) - 1) != 0) {
        return -1;
    }
    const char *id_value = id_line + sizeof(id_prefix) - 1;
    const char *id_end = strchr(id_value, '\n');
    if (id_end == NULL) {
        return -1;
    }
    size_t id_length = (size_t)(id_end - id_value);
    if (!run_config_valid_operation_id(id_value, id_length)
        || id_length > RUN_CONFIG_OPERATION_ID_MAX) {
        return -1;
    }

    const char *code_line = id_end + 1;
    if (strncmp(code_line, code_prefix, sizeof(code_prefix) - 1) != 0) {
        return -1;
    }
    const char *code_value = code_line + sizeof(code_prefix) - 1;
    int code = -1;
    if (strcmp(code_value, "0\n") == 0) {
        code = RUN_STATUS_TERMINAL_SUCCESS;
    } else if (strcmp(code_value, "1\n") == 0) {
        code = RUN_STATUS_TERMINAL_ERROR;
    } else if (strcmp(code_value, "4\n") == 0) {
        code = RUN_STATUS_TERMINAL_SOFT_REBOOT_OK;
    } else if (strcmp(code_value, "6\n") == 0) {
        code = RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL;
    } else {
        return -1;
    }

    memcpy(operation_id_out, id_value, id_length);
    operation_id_out[id_length] = '\0';
    *code_out = code;
    return 0;
}

static int read_marker(const char *path, char *operation_id_out, int *code_out)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        return -1;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        close(fd);
        return -1;
    }
    char buffer[RUN_STATUS_PAYLOAD_MAX + 1];
    size_t length = 0;
    for (;;) {
        ssize_t count = read(fd, buffer + length, sizeof(buffer) - 1 - length);
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            close(fd);
            return -1;
        }
        if (count == 0) {
            break;
        }
        length += (size_t)count;
        if (length >= sizeof(buffer) - 1) {
            close(fd);
            return -1;
        }
    }
    close(fd);
    return parse_payload(buffer, length, operation_id_out, code_out);
}

int run_status_classify(struct run_status_result *out)
{
    if (out == NULL) {
        return status_fail("classifier output is required");
    }
    memset(out, 0, sizeof(*out));
    out->code = -1;
    if (!status_configured) {
        return status_fail("marker protocol is not configured");
    }

    DIR *directory = opendir(status_root);
    if (directory == NULL) {
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "cannot open marker root: %s", strerror(errno));
        return -1;
    }

    static const char prefix[] = "dfroot_terminal_";
    unsigned int found = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (strncmp(entry->d_name, prefix, sizeof(prefix) - 1) != 0) {
            continue;
        }
        char path[RUN_STATUS_PATH_MAX];
        int written = snprintf(path, sizeof(path), "%s/%s", status_root, entry->d_name);
        if (written < 0 || (size_t)written >= sizeof(path)) {
            continue;
        }
        char marker_operation_id[RUN_CONFIG_OPERATION_ID_MAX + 1];
        int code = -1;
        /* Malformed payloads, foreign IDs, links, and non-regular entries are
         * all ignored: only valid markers for the admitted operation count. */
        if (read_marker(path, marker_operation_id, &code) != 0
            || strcmp(marker_operation_id, status_operation_id) != 0) {
            continue;
        }
        int index = code_index(code);
        if (index >= 0) {
            found |= 1u << index;
        }
    }
    closedir(directory);

    int count = 0;
    for (size_t i = 0; i < sizeof(code_order) / sizeof(code_order[0]); i++) {
        if ((found & (1u << code_index(code_order[i]))) != 0) {
            count++;
        }
    }

    if (count == 0) {
        out->verdict = RUN_STATUS_VERDICT_NONE;
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "no terminal marker for operation %s", status_operation_id);
        return 0;
    }
    if (count == 1) {
        out->verdict = RUN_STATUS_VERDICT_TERMINAL;
        for (size_t i = 0; i < sizeof(code_order) / sizeof(code_order[0]); i++) {
            if ((found & (1u << code_index(code_order[i]))) != 0) {
                out->code = code_order[i];
                break;
            }
        }
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "terminal rc=%d for operation %s", out->code, status_operation_id);
        return 0;
    }

    out->verdict = RUN_STATUS_VERDICT_CONFLICT;
    out->code = RUN_STATUS_TERMINAL_ERROR;
    snprintf(out->diagnostic, sizeof(out->diagnostic),
             "conflicting terminal markers for operation %s:", status_operation_id);
    size_t used = strlen(out->diagnostic);
    for (size_t i = 0; i < sizeof(code_order) / sizeof(code_order[0]); i++) {
        if ((found & (1u << code_index(code_order[i]))) == 0) {
            continue;
        }
        int written = snprintf(out->diagnostic + used, sizeof(out->diagnostic) - used,
                               " rc=%d", code_order[i]);
        if (written < 0 || (size_t)written >= sizeof(out->diagnostic) - used) {
            break;
        }
        used += (size_t)written;
    }
    return 0;
}

int run_status_write(int code)
{
    if (code_index(code) < 0) {
        return status_fail("terminal code is unsupported");
    }
    if (!status_configured) {
        return status_fail("marker protocol is not configured");
    }
    char path[RUN_STATUS_PATH_MAX];
    char payload[RUN_STATUS_PAYLOAD_MAX];
    if (marker_path(path, sizeof(path), status_root, status_operation_id, code) != 0
        || make_payload(payload, sizeof(payload), status_operation_id, code) != 0) {
        return status_fail("terminal marker path or payload is too long");
    }

    /* O_EXCL + O_NOFOLLOW reject a pre-existing file or symlink without ever
     * following it; the descriptor is then verified to be a regular file. The
     * world-readable 0644 mode keeps markers observable to the unprivileged
     * native reader; some filesystems ignore mode bits, so only type and
     * no-follow are enforced as write preconditions. */
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0644);
    if (fd < 0) {
        return status_fail_errno("terminal marker open failed");
    }
    int result = 0;

    struct stat st;
    if (fstat(fd, &st) != 0) {
        result = status_fail_errno("terminal marker fstat failed");
        goto done;
    }
    if (!S_ISREG(st.st_mode)) {
        result = status_fail("terminal marker is not a regular file");
        goto done;
    }

    size_t length = strlen(payload);
    size_t offset = 0;
    while (offset < length) {
        ssize_t written;
#ifdef RUN_STATUS_TEST_HOOKS
        /* Inject EIO through the same write/error branch production uses;
         * never short-circuit to a test-only error goto. */
        if ((injected_fault & RUN_STATUS_FAULT_WRITE) != 0) {
            errno = EIO;
            written = -1;
        } else
#endif
        {
            written = write(fd, payload + offset, length - offset);
        }
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            result = status_fail_errno("terminal marker write failed");
            goto done;
        }
        if (written == 0) {
            result = status_fail("terminal marker write made no progress");
            goto done;
        }
        offset += (size_t)written;
    }

    int fsync_result;
#ifdef RUN_STATUS_TEST_HOOKS
    /* Likewise, feed the injected EIO into the real fsync error branch. */
    if ((injected_fault & RUN_STATUS_FAULT_FSYNC) != 0) {
        errno = EIO;
        fsync_result = -1;
    } else
#endif
    {
        fsync_result = fsync(fd);
    }
    if (fsync_result != 0 && errno != EINVAL && errno != ENOTSUP) {
        result = status_fail_errno("terminal marker fsync failed");
        goto done;
    }

done:
    {
        int close_result = close(fd);
#ifdef RUN_STATUS_TEST_HOOKS
        /* The close fault stays on the real close_result; it never invents a
         * separate branch either. */
        if ((injected_fault & RUN_STATUS_FAULT_CLOSE) != 0 && close_result == 0) {
            close_result = -1;
            errno = EIO;
        }
#endif
        if (close_result != 0 && result == 0) {
            /* Preserve the first diagnostic: a close failure must not
             * overwrite a write or fsync failure that already happened. */
            result = status_fail_errno("terminal marker close failed");
        }
    }
    return result;
}

int run_status_soft_reboot_code(int command_exit_code)
{
    return command_exit_code == 0
           ? RUN_STATUS_TERMINAL_SOFT_REBOOT_OK
           : RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL;
}

/* The one and only legacy progress-marker table. /dev/dfm6 still prints the
 * owner-visible "ksud start: SUCCESS" diagnostic, but its outcome is strictly
 * progress-only: no global marker may terminate the run. */
const struct run_status_progress_marker run_status_progress_markers[] = {
    { "/dev/df",    "libc++: loading custom module",                  RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfm0",  "kernel module: launching bootstrap",             RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfm1",  "bootstrap: loading run-config artifact",         RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfme0", "bootstrap: ERROR - reading run-config failed",   RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfm2",  "bootstrap: cloning zygote env",                  RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfmw0", "bootstrap: WARNING - clone zygote env failed",   RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfm3",  "bootstrap: setting partitions ro",               RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfmw1", "bootstrap: WARNING - set partitions ro failed",  RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfm4",  "bootstrap: disabling ksu modules",               RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfme1", "bootstrap: ERROR - disable modules failed",      RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfm5",  "bootstrap: starting SU daemon",                  RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfm6",  "ksud start: SUCCESS",                            RUN_STATUS_PROGRESS_ONLY },
    { "/dev/dfme2", "ksud start: ERROR - ksud nonzero exit",          RUN_STATUS_PROGRESS_ONLY }
};
_Static_assert(sizeof(run_status_progress_markers)
                   / sizeof(run_status_progress_markers[0])
                   == RUN_STATUS_PROGRESS_MARKER_COUNT,
               "progress marker table and exported count disagree");
const size_t run_status_progress_marker_count = RUN_STATUS_PROGRESS_MARKER_COUNT;

int run_status_progress_outcome(const char *marker_path)
{
    if (marker_path == NULL) {
        return RUN_STATUS_PROGRESS_ONLY;
    }
    for (size_t i = 0; i < run_status_progress_marker_count; i++) {
        if (strcmp(marker_path, run_status_progress_markers[i].path) == 0) {
            return run_status_progress_markers[i].outcome;
        }
    }
    /* Unknown global markers are progress-only as well. */
    return RUN_STATUS_PROGRESS_ONLY;
}

int run_status_wait_continues(int64_t elapsed_ms, int64_t limit_ms)
{
    return elapsed_ms >= 0 && limit_ms > 0 && elapsed_ms < limit_ms;
}

int run_status_exit_code(const struct run_status_result *result)
{
    if (result == NULL || result->verdict == RUN_STATUS_VERDICT_NONE) {
        return -1;
    }
    if (result->verdict == RUN_STATUS_VERDICT_CONFLICT) {
        return RUN_STATUS_TERMINAL_ERROR;
    }
    return result->code;
}
