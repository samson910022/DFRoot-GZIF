/* Modified 2026-11-25 (DFRoot GZIF): implement checked, run-scoped terminal markers and their classifier. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): export the shared progress-marker table and route injected faults through the real write/fsync error branches. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): publish via temp+link, reserve rc=7, and never confirm an unfsynced publication. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): publish rc7 before retraction, retry temp unlink, and chmod only fully written payloads. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): share bounded, non-killing child waits and map every timeout to rc7. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): map reader expiry to rc7 and expose the shared-budget remainder. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): scan the reserved pre-admission diagnostic and decide through a pure helper. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): add a shared classifier-failure rc7 mapping and a harmless host injection seam. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): gate the post-link/pre-confirmation observation window with a host-only hook. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): map contradictory terminal markers to indeterminate rc7 instead of definitive rc1. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): require a matching confirmation marker for every definitive non-7 code; publish rc7 on any publication failure before retraction. See NOTICE. */
/* Modified 2026-11-26 (DFRoot GZIF): narrow the post-confirm guarantee to what is observed; add a host-only confirmation-link pause. See NOTICE. */
#define _POSIX_C_SOURCE 200809L
#include "run_status.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static char status_root[RUN_STATUS_ROOT_MAX];
static char status_operation_id[RUN_CONFIG_OPERATION_ID_MAX + 1];
static int status_configured;
static char status_error[RUN_STATUS_DIAGNOSTIC_MAX];
static unsigned long status_temp_counter;

/* Two marker namespaces in the same root. Both use the identical checked
 * path/validation/write discipline; only the magic and filename prefix differ. */
enum marker_kind {
    MARKER_TERMINAL = 0,
    MARKER_CONFIRM = 1
};
static const char *marker_word(enum marker_kind kind);

#ifdef RUN_STATUS_TEST_HOOKS
static int injected_fault;
static int injected_classifier_failure;
static int writer_wait_fd = -1;
static int writer_notify_fd = -1;
static int post_link_wait_fd = -1;
static int post_link_notify_fd = -1;
static int confirm_post_link_wait_fd = -1;
static int confirm_post_link_notify_fd = -1;
static int confirm_unlink_fault;
static int retract_wait_fd = -1;
static int retract_notify_fd = -1;
void run_status_inject_fault(int fault) { injected_fault = fault; }
void run_status_inject_classifier_failure(int enabled)
{
    injected_classifier_failure = enabled ? 1 : 0;
}
void run_status_set_writer_pause(int wait_fd, int notify_fd)
{
    writer_wait_fd = wait_fd;
    writer_notify_fd = notify_fd;
}
void run_status_set_post_link_pause(int wait_fd, int notify_fd)
{
    post_link_wait_fd = wait_fd;
    post_link_notify_fd = notify_fd;
}
void run_status_set_confirm_post_link_pause(int wait_fd, int notify_fd)
{
    confirm_post_link_wait_fd = wait_fd;
    confirm_post_link_notify_fd = notify_fd;
}
void run_status_set_confirm_unlink_fault(int enabled)
{
    confirm_unlink_fault = enabled ? 1 : 0;
}
void run_status_set_retract_pause(int wait_fd, int notify_fd)
{
    retract_wait_fd = wait_fd;
    retract_notify_fd = notify_fd;
}
#endif

/* Remove a temp link. The test seam fails one attempt with EIO and clears
 * itself, so the unconfirmed path's retry is deterministic. Production builds
 * have no seam and simply call unlink(). */
static int status_unlink_temp(const char *temp_path)
{
#ifdef RUN_STATUS_TEST_HOOKS
    if ((injected_fault & RUN_STATUS_FAULT_UNLINK_TEMP) != 0) {
        injected_fault &= ~RUN_STATUS_FAULT_UNLINK_TEMP;
        errno = EIO;
        return -1;
    }
#endif
    return unlink(temp_path);
}

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

/* Phase-specific diagnostics retain the exact historical "terminal marker ..."
 * wording for the intended phase and use "confirmation marker ..." for phase 2. */
static int status_fail_phase(enum marker_kind kind, const char *what)
{
    snprintf(status_error, sizeof(status_error), "%s marker %s",
             marker_word(kind), what);
    return -1;
}

static int status_fail_phase_errno(enum marker_kind kind, const char *what)
{
    snprintf(status_error, sizeof(status_error), "%s marker %s: %s",
             marker_word(kind), what, strerror(errno));
    return -1;
}

const char *run_status_last_error(void)
{
    return status_error;
}

#ifdef RUN_STATUS_TEST_HOOKS
/* Shared one-shot pipe handshake for both host-only pause sites. */
static int status_gated_pause(int *wait_fd, int *notify_fd,
                              const char *notify_error, const char *wait_error)
{
    if (*wait_fd < 0) {
        return 0;
    }
    int wait = *wait_fd;
    int notify = *notify_fd;
    *wait_fd = -1;
    *notify_fd = -1;
    if (notify >= 0) {
        const char byte = 'p';
        ssize_t sent;
        do {
            sent = write(notify, &byte, 1);
        } while (sent < 0 && errno == EINTR);
        if (sent != 1) {
            return status_fail_errno(notify_error);
        }
    }
    char byte;
    ssize_t received;
    do {
        received = read(wait, &byte, 1);
    } while (received < 0 && errno == EINTR);
    if (received != 1) {
        return status_fail_errno(wait_error);
    }
    return 0;
}
#endif

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
        case RUN_STATUS_TERMINAL_SUCCESS:            return 0;
        case RUN_STATUS_TERMINAL_ERROR:              return 1;
        case RUN_STATUS_TERMINAL_SOFT_REBOOT_OK:     return 2;
        case RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL:   return 3;
        case RUN_STATUS_TERMINAL_INDETERMINATE:      return 4;
        default:                                     return -1;
    }
}

/* Decision order matters only for conflict diagnostics: rc=7 is handled by an
 * explicit precedence check in run_status_classify and is not ranked here. */
static const int code_order[] = {
    RUN_STATUS_TERMINAL_SUCCESS,
    RUN_STATUS_TERMINAL_ERROR,
    RUN_STATUS_TERMINAL_SOFT_REBOOT_OK,
    RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL
};

static const char *code_suffix(int code)
{
    switch (code) {
        case RUN_STATUS_TERMINAL_SUCCESS:            return "rc0";
        case RUN_STATUS_TERMINAL_ERROR:              return "rc1";
        case RUN_STATUS_TERMINAL_SOFT_REBOOT_OK:     return "rc4";
        case RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL:   return "rc6";
        case RUN_STATUS_TERMINAL_INDETERMINATE:      return "rc7";
        default:                                     return NULL;
    }
}

static const char *marker_prefix(enum marker_kind kind)
{
    return kind == MARKER_CONFIRM ? "dfroot_confirm_" : "dfroot_terminal_";
}

static const char *marker_header(enum marker_kind kind)
{
    return kind == MARKER_CONFIRM ? "DFROOT_CONFIRM 1\n" : "DFROOT_TERMINAL 1\n";
}

static const char *marker_word(enum marker_kind kind)
{
    return kind == MARKER_CONFIRM ? "confirmation" : "terminal";
}

static int marker_path_kind(char *path, size_t capacity, const char *root,
                            const char *operation_id, int code, enum marker_kind kind)
{
    const char *suffix = code_suffix(code);
    if (suffix == NULL) {
        return -1;
    }
    int written = snprintf(path, capacity, "%s/%s%s_%s",
                           root, marker_prefix(kind), operation_id, suffix);
    if (written < 0 || (size_t)written >= capacity) {
        return -1;
    }
    return 0;
}

static int make_payload_kind(char *payload, size_t capacity, const char *operation_id,
                             int code, enum marker_kind kind)
{
    int written = snprintf(payload, capacity,
                           "%soperation_id=%s\ncode=%d\n",
                           marker_header(kind), operation_id, code);
    if (written < 0 || (size_t)written >= capacity) {
        return -1;
    }
    return 0;
}

/* Parse an in-memory payload. Rejects control characters, foreign or unsafe
 * IDs, unknown codes, a wrong magic header (so a terminal payload can never
 * confirm itself), and any trailing bytes. */
static int parse_payload_kind(char *payload, size_t length, char *operation_id_out,
                              int *code_out, enum marker_kind kind)
{
    const char *header = marker_header(kind);
    size_t header_length = strlen(header);
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

    if (strncmp(payload, header, header_length) != 0) {
        return -1;
    }
    const char *id_line = payload + header_length;
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
    } else if (strcmp(code_value, "7\n") == 0) {
        code = RUN_STATUS_TERMINAL_INDETERMINATE;
    } else {
        return -1;
    }

    memcpy(operation_id_out, id_value, id_length);
    operation_id_out[id_length] = '\0';
    *code_out = code;
    return 0;
}

static int read_marker_kind(const char *path, char *operation_id_out,
                            int *code_out, enum marker_kind kind)
{
    /* O_NONBLOCK keeps a malicious FIFO at a marker path from stalling the
     * reader: the fstat() regular-file check below rejects it immediately.
     * O_NOFOLLOW rejects symlinks. It has no effect on regular marker files. */
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
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
    return parse_payload_kind(buffer, length, operation_id_out, code_out, kind);
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
#ifdef RUN_STATUS_TEST_HOOKS
    /* Host-only observation failure: no verdict, no code, no marker. The
     * reader's failure branch maps this to rc7 without confirming anything. */
    if (injected_classifier_failure) {
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "injected terminal classifier failure; completion unverified");
        return status_fail("terminal classifier observation failed");
    }
#endif

    DIR *directory = opendir(status_root);
    if (directory == NULL) {
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "cannot open marker root: %s", strerror(errno));
        return -1;
    }

    unsigned int terminal_codes = 0;
    unsigned int confirm_codes = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        enum marker_kind kind;
        size_t terminal_prefix_length = strlen(marker_prefix(MARKER_TERMINAL));
        size_t confirm_prefix_length = strlen(marker_prefix(MARKER_CONFIRM));
        if (strncmp(entry->d_name, marker_prefix(MARKER_TERMINAL),
                    terminal_prefix_length) == 0) {
            kind = MARKER_TERMINAL;
        } else if (strncmp(entry->d_name, marker_prefix(MARKER_CONFIRM),
                           confirm_prefix_length) == 0) {
            kind = MARKER_CONFIRM;
        } else {
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
         * all ignored: only valid markers for the admitted operation count.
         * A confirmation additionally needs its own magic header, so a
         * terminal payload can never confirm itself. Temp files use the
         * dfroot_tmp_ prefix and never count. */
        if (read_marker_kind(path, marker_operation_id, &code, kind) != 0
            || strcmp(marker_operation_id, status_operation_id) != 0) {
            continue;
        }
        int index = code_index(code);
        if (index >= 0) {
            if (kind == MARKER_CONFIRM) {
                confirm_codes |= 1u << index;
            } else {
                terminal_codes |= 1u << index;
            }
        }
    }
    closedir(directory);

    /* The indeterminate publication-failure channel outranks every other
     * code, including success and a conflict. rc7 never needs a confirmation:
     * it is the reader's authoritative uncertainty channel. */
    if ((terminal_codes & (1u << code_index(RUN_STATUS_TERMINAL_INDETERMINATE))) != 0) {
        out->verdict = RUN_STATUS_VERDICT_TERMINAL;
        out->code = RUN_STATUS_TERMINAL_INDETERMINATE;
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "indeterminate terminal publication for operation %s", status_operation_id);
        return 0;
    }

    unsigned int observed = terminal_codes | confirm_codes;
    int count = 0;
    for (size_t i = 0; i < sizeof(code_order) / sizeof(code_order[0]); i++) {
        if ((observed & (1u << code_index(code_order[i]))) != 0) {
            count++;
        }
    }

    if (count == 0) {
        out->verdict = RUN_STATUS_VERDICT_NONE;
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "no terminal marker for operation %s", status_operation_id);
        return 0;
    }

    if (count > 1) {
        /* Two different observed codes disprove each other, whether or not
         * either has a confirmation. Never a definitive rc0/rc1. */
        out->verdict = RUN_STATUS_VERDICT_CONFLICT;
        out->code = RUN_STATUS_TERMINAL_INDETERMINATE;
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "conflicting markers; completion unverified for operation %s:",
                 status_operation_id);
        size_t used = strlen(out->diagnostic);
        for (size_t i = 0; i < sizeof(code_order) / sizeof(code_order[0]); i++) {
            if ((observed & (1u << code_index(code_order[i]))) == 0) {
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

    /* Exactly one observed non-7 code. It is definitive ONLY if both the
     * intended final marker and a matching confirmation (same operation ID,
     * same code) are present and valid. */
    int only_code = -1;
    for (size_t i = 0; i < sizeof(code_order) / sizeof(code_order[0]); i++) {
        if ((observed & (1u << code_index(code_order[i]))) != 0) {
            only_code = code_order[i];
            break;
        }
    }
    int intended = (terminal_codes & (1u << code_index(only_code))) != 0;
    int confirmed = (confirm_codes & (1u << code_index(only_code))) != 0;
    if (intended && confirmed) {
        out->verdict = RUN_STATUS_VERDICT_TERMINAL;
        out->code = only_code;
        out->publication_confirmed = 1;
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "publication-confirmed terminal rc=%d for operation %s",
                 only_code, status_operation_id);
        return 0;
    }

    out->verdict = RUN_STATUS_VERDICT_NONE;
    if (intended) {
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "terminal marker rc=%d for operation %.64s is not publication-confirmed; "
                 "completion unverified", only_code, status_operation_id);
    } else {
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "confirmation marker rc=%d for operation %.64s has no terminal marker; "
                 "completion unverified", only_code, status_operation_id);
    }
    return 0;
}

/* Reserved-identity diagnostic scan. It deliberately does NOT reuse the
 * general classification loop: only the exact final path for the reserved
 * identity and rc=1 is read, and read_marker_kind() already enforces a regular,
 * O_NOFOLLOW final file plus a well-formed payload. Both the filename and the
 * payload must name the reserved operation and carry rc=1, so a stale marker
 * that merely borrows the identity, a marker for another code, a temp file,
 * and any malformed or foreign payload are all ignored. */
int run_status_pre_admission_failure(void)
{
    if (!status_configured) {
        return 0;
    }
    char path[RUN_STATUS_PATH_MAX];
    if (marker_path_kind(path, sizeof(path), status_root, RUN_STATUS_PRE_ADMISSION_ID,
                         RUN_STATUS_TERMINAL_ERROR, MARKER_TERMINAL) != 0) {
        return 0;
    }
    char marker_operation_id[RUN_CONFIG_OPERATION_ID_MAX + 1];
    int code = -1;
    if (read_marker_kind(path, marker_operation_id, &code, MARKER_TERMINAL) != 0
        || strcmp(marker_operation_id, RUN_STATUS_PRE_ADMISSION_ID) != 0
        || code != RUN_STATUS_TERMINAL_ERROR) {
        return 0;
    }
    return 1;
}

/* Pure decision step. No filesystem access, no configuration state, no
 * mutation of the inputs; repeated calls with equal inputs give equal output.
 * The admitted classifier result is the sole authority for terminal outcomes,
 * so rc0 success and every other admitted terminal code survive a stale
 * pre-admission diagnostic unchanged. Only the absence of ANY admitted
 * terminal marker lets the diagnostic become an explicit indeterminate. */
void run_status_decide(const struct run_status_result *admitted,
                       int pre_admission_failure,
                       struct run_status_decision *out)
{
    if (out == NULL) {
        return;
    }
    memset(out, 0, sizeof(*out));
    out->code = -1;

    if (admitted == NULL) {
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "no admitted classification to decide");
        return;
    }

    int admitted_code = run_status_exit_code(admitted);
    /* Defense in depth: a non-7 code without its matching confirmation is not
     * actionable even if a caller hands us a hand-built classifier result.
     * rc7 (including CONFLICT) is authoritative without confirmation. */
    int actionable = admitted_code >= 0
                     && !(admitted->verdict == RUN_STATUS_VERDICT_TERMINAL
                          && admitted_code != RUN_STATUS_TERMINAL_INDETERMINATE
                          && !admitted->publication_confirmed);
    if (actionable) {
        /* TERMINAL or CONFLICT: both end the admitted operation's observation,
         * but CONFLICT maps to rc=7 indeterminate (never a definitive rc=1).
         * Preserve its diagnostic. */
        out->outcome = RUN_STATUS_OUTCOME_TERMINAL;
        out->code = admitted_code;
        snprintf(out->diagnostic, sizeof(out->diagnostic), "%s",
                 admitted->diagnostic[0] != '\0'
                 ? admitted->diagnostic
                 : "terminal marker for admitted operation");
        return;
    }

    if (pre_admission_failure) {
        out->outcome = RUN_STATUS_OUTCOME_UNCONFIRMED;
        out->code = RUN_STATUS_TERMINAL_INDETERMINATE;
        snprintf(out->diagnostic, sizeof(out->diagnostic),
                 "pre-admission failure; completion unverified");
        return;
    }

    out->outcome = RUN_STATUS_OUTCOME_NONE;
    snprintf(out->diagnostic, sizeof(out->diagnostic), "%s",
             admitted->diagnostic[0] != '\0'
             ? admitted->diagnostic
             : "no terminal marker for admitted operation");
}

/* Fill the unpredictable part of a temp-file name from /dev/urandom. If the
 * kernel source cannot be read, fall back to clock/PID/counter mixing; O_EXCL
 * retries still prevent any collision from becoming an overwrite. */
static void status_temp_entropy(unsigned char *out, size_t length)
{
    size_t filled = 0;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        while (filled < length) {
            ssize_t count = read(fd, out + filled, length - filled);
            if (count < 0) {
                if (errno == EINTR) {
                    continue;
                }
                break;
            }
            if (count == 0) {
                break;
            }
            filled += (size_t)count;
        }
        close(fd);
    }
    if (filled == length) {
        return;
    }
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        now.tv_sec = 0;
        now.tv_nsec = 0;
    }
    uint64_t seed = (uint64_t)now.tv_sec * 1000000000ull + (uint64_t)now.tv_nsec;
    seed ^= (uint64_t)getpid() << 32;
    seed ^= (uint64_t)status_temp_counter++;
    for (size_t i = filled; i < length; i++) {
        seed = seed * 6364136223846793005ull + 1442695040888963407ull;
        out[i] = (unsigned char)(seed >> 33);
    }
}

/* Write the complete payload to an unpredictable same-directory temp file.
 * On any failure the temp link is removed, so the reader sees no terminal
 * marker at all. On success the caller receives the temp pathname to link. */
static int status_write_temp(const char *payload, char *temp_path, size_t capacity,
                             int code, enum marker_kind kind)
{
    unsigned char entropy[16];
    status_temp_entropy(entropy, sizeof(entropy));
    uint64_t nonce = 0;
    for (size_t i = 0; i < 8; i++) {
        nonce = (nonce << 8) | entropy[i];
    }

    int fd = -1;
    for (int attempt = 0; attempt < 8; attempt++) {
        int written = snprintf(temp_path, capacity,
                               "%s/dfroot_tmp_%s_%cc_rc%d_%016llx_%ld_%d",
                               status_root, status_operation_id,
                               kind == MARKER_CONFIRM ? 'c' : 't', code,
                               (unsigned long long)nonce, (long)getpid(), attempt);
        if (written < 0 || (size_t)written >= capacity) {
            return status_fail("terminal temp path is too long");
        }
        fd = open(temp_path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
        if (fd >= 0) {
            break;
        }
        if (errno == EEXIST) {
            nonce = nonce * 6364136223846793005ull + 1442695040888963407ull;
            continue;
        }
        return status_fail_errno("terminal marker open failed");
    }
    if (fd < 0) {
        return status_fail("could not allocate a unique terminal temp file");
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
    /* The temp name is unpredictable and carries a non-terminal prefix, so it
     * is ignored by the classifier while the payload is still being written.
     * Mode bits are set to 0644 only after the content is fully written
     * and fsynced (below), never before. Some filesystems ignore mode bits;
     * regular-file type and no-follow are the enforced preconditions. */
#ifdef RUN_STATUS_TEST_HOOKS
    if (status_gated_pause(&writer_wait_fd, &writer_notify_fd,
                           "writer pause notification failed",
                           "writer pause ended unexpectedly") != 0) {
        result = -1;
        goto done;
    }
#endif

    size_t length = strlen(payload);
    size_t offset = 0;
    while (offset < length) {
        ssize_t written;
#ifdef RUN_STATUS_TEST_HOOKS
        /* Inject EIO through the same write/error branch production uses.
         * FAULT_CONFIRM is scoped to the confirmation phase only; the rc7
         * fallback clears every injected fault before it writes. */
        if ((kind == MARKER_CONFIRM && (injected_fault & RUN_STATUS_FAULT_CONFIRM) != 0)
            || (injected_fault & RUN_STATUS_FAULT_WRITE) != 0) {
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
            result = status_fail_phase_errno(kind, "write failed");
            goto done;
        }
        if (written == 0) {
            result = status_fail_phase(kind, "write made no progress");
            goto done;
        }
        offset += (size_t)written;
    }

    int fsync_result;
#ifdef RUN_STATUS_TEST_HOOKS
    if ((injected_fault & RUN_STATUS_FAULT_FSYNC) != 0) {
        errno = EIO;
        fsync_result = -1;
    } else
#endif
    {
        fsync_result = fsync(fd);
    }
    if (fsync_result != 0 && errno != EINVAL && errno != ENOTSUP) {
        result = status_fail_phase_errno(kind, "fsync failed");
        goto done;
    }
    /* Only a complete, fsynced payload becomes world-readable. The descriptor
     * is still open here, so no pathname-based chmod race is introduced. */
    if (fchmod(fd, 0644) != 0) {
        result = status_fail_phase_errno(kind, "fchmod failed");
        goto done;
    }

done:
    {
        int close_result = close(fd);
#ifdef RUN_STATUS_TEST_HOOKS
        if ((injected_fault & RUN_STATUS_FAULT_CLOSE) != 0 && close_result == 0) {
            close_result = -1;
            errno = EIO;
        }
#endif
        if (close_result != 0 && result == 0) {
            /* Preserve the first diagnostic: a close failure must not
             * overwrite a write or fsync failure that already happened. */
            result = status_fail_phase_errno(kind, "close failed");
        }
    }
    if (result != 0) {
        /* A temp file never carries a terminal name, but remove it anyway so a
         * failed publication leaves no debris and no complete payload behind. */
        (void)status_unlink_temp(temp_path);
    }
    return result;
}

/* Directory durability for the final link. EINVAL/ENOTSUP mean that this
 * filesystem does not implement directory fsync; like file fsync, that is a
 * no-op rather than a failed publication. All other errors are unconfirmed. */
static int status_fsync_directory(void)
{
    int fd = open(status_root, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) {
        return status_fail_errno("terminal marker directory open failed");
    }
    int fsync_result;
#ifdef RUN_STATUS_TEST_HOOKS
    if ((injected_fault & RUN_STATUS_FAULT_FSYNC_DIR) != 0) {
        errno = EIO;
        fsync_result = -1;
    } else
#endif
    {
        fsync_result = fsync(fd);
    }
    int saved_errno = errno;
    close(fd);
    if (fsync_result != 0) {
        errno = saved_errno;
        if (errno == EINVAL || errno == ENOTSUP) {
            return 0;
        }
        return status_fail_errno("terminal marker directory fsync failed");
    }
    return 0;
}

/* Best-effort dedicated reader channel for an unconfirmed publication. This
 * uses the same checked pipeline but deliberately ignores injected faults: the
 * fallback is a separate operation and must succeed when the real filesystem
 * is healthy. It publishes NO confirmation: rc7 is authoritative by itself. */
static void status_publish_indeterminate(void)
{
    char final_path[RUN_STATUS_PATH_MAX];
    char temp_path[RUN_STATUS_PATH_MAX];
    char payload[RUN_STATUS_PAYLOAD_MAX];
    if (marker_path_kind(final_path, sizeof(final_path), status_root, status_operation_id,
                         RUN_STATUS_TERMINAL_INDETERMINATE, MARKER_TERMINAL) != 0
        || make_payload_kind(payload, sizeof(payload), status_operation_id,
                             RUN_STATUS_TERMINAL_INDETERMINATE, MARKER_TERMINAL) != 0) {
        return;
    }
#ifdef RUN_STATUS_TEST_HOOKS
    int saved_fault = injected_fault;
    injected_fault = RUN_STATUS_FAULT_NONE;
#endif
    if (status_write_temp(payload, temp_path, sizeof(temp_path),
                          RUN_STATUS_TERMINAL_INDETERMINATE, MARKER_TERMINAL) == 0) {
        if (link(temp_path, final_path) == 0) {
            (void)unlink(temp_path);
            (void)status_fsync_directory();
        } else {
            (void)unlink(temp_path);
        }
    }
#ifdef RUN_STATUS_TEST_HOOKS
    injected_fault = saved_fault;
#endif
}

/* One fully checked publication phase. Returns 0 only after the final link is
 * present, its temp is unlinked and the directory is fsynced. *linked records
 * whether the atomic link happened, so the caller can retract exactly what it
 * published. link() fails with EEXIST for a pre-existing final path, so a
 * foreign/stale marker is never replaced. */
static int publish_marker(int code, enum marker_kind kind,
                          char *final_path, size_t final_capacity,
                          char *temp_path, size_t temp_capacity, int *linked)
{
    char payload[RUN_STATUS_PAYLOAD_MAX];
    *linked = 0;
    if (marker_path_kind(final_path, final_capacity, status_root, status_operation_id,
                         code, kind) != 0
        || make_payload_kind(payload, sizeof(payload), status_operation_id, code, kind) != 0) {
        return status_fail("terminal/confirmation marker path or payload is too long");
    }
    if (status_write_temp(payload, temp_path, temp_capacity, code, kind) != 0) {
        return -1;
    }
    if (link(temp_path, final_path) != 0) {
        int result = status_fail_phase_errno(kind, "link failed");
        (void)status_unlink_temp(temp_path);
        return result;
    }
    *linked = 1;
#ifdef RUN_STATUS_TEST_HOOKS
    /* EARLIEST observable instant for the intended phase: the complete final
     * marker is linked, but its temp unlink, directory fsync and the entire
     * confirmation phase have not happened, and rc=7 does not exist. A reader
     * MUST report no decision here. A broken handshake fails closed like any
     * other post-link failure. */
    if (kind == MARKER_TERMINAL
        && status_gated_pause(&post_link_wait_fd, &post_link_notify_fd,
                              "post-link pause notification failed",
                              "post-link pause ended unexpectedly") != 0) {
        return -1;
    }
    /* R2-14 residual boundary, host only: pause immediately AFTER the
     * confirmation link() and BEFORE its temp unlink/directory fsync. At this
     * instant both the intended marker and a matching confirmation are visible,
     * so a concurrent reader can record a definitive-looking result with
     * publication_confirmed == 1. No failure is detected and no rc7 exists yet;
     * any later rc7 correction cannot revoke that already consumed result. */
    if (kind == MARKER_CONFIRM) {
        if (status_gated_pause(&confirm_post_link_wait_fd,
                               &confirm_post_link_notify_fd,
                               "confirmation post-link pause notification failed",
                               "confirmation post-link pause ended unexpectedly") != 0) {
            return -1;
        }
        /* Arm the existing transient unlink fault only AFTER phase 1 is fully
         * published and the confirmation link is visible, so the detected
         * failure is strictly post-confirm and consumes no phase-1 retry. */
        if (confirm_unlink_fault) {
            injected_fault |= RUN_STATUS_FAULT_UNLINK_TEMP;
            confirm_unlink_fault = 0;
        }
    }
#endif
    if (status_unlink_temp(temp_path) != 0) {
        (void)status_fail_phase_errno(kind, "unlink failed");
        return -1;
    }
    if (status_fsync_directory() != 0) {
        return -1;
    }
    return 0;
}

/* A transient temp-unlink fault clears itself on the first attempt; retry so
 * a failed publication never leaves debris. ENOENT means already cleaned. */
static void status_cleanup_temp(const char *temp_path)
{
    if (temp_path[0] == '\0') {
        return;
    }
    for (int attempt = 0; attempt < 2; attempt++) {
        if (status_unlink_temp(temp_path) == 0 || errno == ENOENT) {
            break;
        }
    }
}

int run_status_write(int code)
{
    if (code_index(code) < 0) {
        return status_fail("terminal code is unsupported");
    }
    if (!status_configured) {
        return status_fail("marker protocol is not configured");
    }
    char terminal_path[RUN_STATUS_PATH_MAX] = {0};
    char terminal_temp[RUN_STATUS_PATH_MAX] = {0};
    char confirm_path[RUN_STATUS_PATH_MAX] = {0};
    char confirm_temp[RUN_STATUS_PATH_MAX] = {0};
    int terminal_linked = 0;
    int confirm_linked = 0;

    /* Phase 1: the intended final marker. Phase 2: its matching confirmation.
     * Only completion of BOTH phases counts as a confirmed publication. */
    if (publish_marker(code, MARKER_TERMINAL, terminal_path, sizeof(terminal_path),
                       terminal_temp, sizeof(terminal_temp), &terminal_linked) != 0) {
        goto unconfirmed;
    }
    if (publish_marker(code, MARKER_CONFIRM, confirm_path, sizeof(confirm_path),
                       confirm_temp, sizeof(confirm_temp), &confirm_linked) != 0) {
        goto unconfirmed;
    }
    return 0;

unconfirmed:
    {
        /* The indeterminate reader channel is published FIRST, before ANY
         * final link is retracted. ONCE rc7 has actually been published AND
         * OBSERVED it outranks success and conflicts, and needs no
         * confirmation. This is a correction, not a revocation: rc7 is produced
         * only AFTER a failure is detected, so a reader that already consumed a
         * definitive-looking confirmation in the window between the confirmation
         * link() and its temp unlink/directory fsync cannot have that result
         * revoked. For an rc=7 intended code the channel already exists and is
         * deliberately kept. The first diagnostic is restored for the caller:
         * bootstrap maps -1 to rc=7, never to the intended code. */
        char first_error[RUN_STATUS_DIAGNOSTIC_MAX];
        snprintf(first_error, sizeof(first_error), "%s", status_error);

        if (!(code == RUN_STATUS_TERMINAL_INDETERMINATE && terminal_linked)) {
            status_publish_indeterminate();
        }
#ifdef RUN_STATUS_TEST_HOOKS
        /* Both the intended marker and the rc=7 channel are visible here when
         * a post-link failure happened. The gated reader test classifies in
         * exactly this coexistence window. */
        (void)status_gated_pause(&retract_wait_fd, &retract_notify_fd,
                                 "retract pause notification failed",
                                 "retract pause ended unexpectedly");
#endif
        if (code != RUN_STATUS_TERMINAL_INDETERMINATE) {
            if (confirm_linked) {
                (void)unlink(confirm_path);
            }
            if (terminal_linked) {
                (void)unlink(terminal_path);
            }
        } else if (confirm_linked) {
            (void)unlink(confirm_path);
        }
        status_cleanup_temp(terminal_temp);
        status_cleanup_temp(confirm_temp);
        snprintf(status_error, sizeof(status_error), "%s", first_error);
        return -1;
    }
}

int run_status_publication_exit_code(int write_result, int intended_code)
{
    return write_result == 0 ? intended_code : RUN_STATUS_TERMINAL_INDETERMINATE;
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

static int64_t status_monotonic_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return -1;
    }
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void status_wait_sleep_slice(void)
{
    struct timespec slice = { 0, 100000000L };
    while (nanosleep(&slice, &slice) < 0 && errno == EINTR) {
    }
}

int run_status_wait_child_bounded(pid_t child, int64_t limit_ms, int *exit_code)
{
    if (child <= 0 || limit_ms <= 0) {
        return -1;
    }
    int64_t start = status_monotonic_ms();
    if (start < 0) {
        return -1;
    }
    for (;;) {
        int status = 0;
        pid_t waited;
        do {
            waited = waitpid(child, &status, WNOHANG);
        } while (waited < 0 && errno == EINTR);
        if (waited == child) {
            if (exit_code != NULL) {
                *exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
            }
            return 1;
        }
        if (waited < 0) {
            return -1;
        }
        int64_t now = status_monotonic_ms();
        if (now < 0 || !run_status_wait_continues(now - start, limit_ms)) {
            /* Deadline expired: the child is deliberately left running. No
             * kill(), no signal, no forced reap: its effect stays unverified. */
            return 0;
        }
        status_wait_sleep_slice();
    }
}

int run_status_late_load_wait_code(int command_exit_code, int timed_out)
{
    if (timed_out || command_exit_code < 0) {
        return RUN_STATUS_TERMINAL_INDETERMINATE;
    }
    return command_exit_code == 0
           ? RUN_STATUS_TERMINAL_SUCCESS
           : RUN_STATUS_TERMINAL_ERROR;
}

int run_status_soft_reboot_wait_code(int command_exit_code, int timed_out)
{
    if (timed_out || command_exit_code < 0) {
        return RUN_STATUS_TERMINAL_INDETERMINATE;
    }
    return run_status_soft_reboot_code(command_exit_code);
}

int run_status_bounded_wait_timeout_code(int expired)
{
    return expired ? RUN_STATUS_TERMINAL_INDETERMINATE : -1;
}

int run_status_reader_timeout_code(int expired)
{
    return run_status_bounded_wait_timeout_code(expired);
}

int64_t run_status_remaining_budget(int64_t elapsed_ms, int64_t limit_ms)
{
    if (limit_ms <= 0 || elapsed_ms < 0 || elapsed_ms >= limit_ms) {
        return -1;
    }
    return limit_ms - elapsed_ms;
}

int run_status_late_load_launch_code(int launch_failed, int command_exit_code, int timed_out)
{
    if (launch_failed) {
        /* No child was started: this is a definite local failure, not a
         * timeout with a live child and not an indeterminate outcome. */
        return RUN_STATUS_TERMINAL_ERROR;
    }
    return run_status_late_load_wait_code(command_exit_code, timed_out);
}

int run_status_soft_reboot_launch_code(int launch_failed, int command_exit_code, int timed_out)
{
    if (launch_failed) {
        return RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL;
    }
    return run_status_soft_reboot_wait_code(command_exit_code, timed_out);
}

int run_status_exit_code(const struct run_status_result *result)
{
    if (result == NULL || result->verdict == RUN_STATUS_VERDICT_NONE) {
        return -1;
    }
    if (result->verdict == RUN_STATUS_VERDICT_CONFLICT) {
        /* Two different observed codes disagree, so neither is proven. */
        return RUN_STATUS_TERMINAL_INDETERMINATE;
    }
    /* A definitive non-7 code is actionable only with a matching
     * confirmation. rc7 stays authoritative without one. */
    if (result->code != RUN_STATUS_TERMINAL_INDETERMINATE
        && !result->publication_confirmed) {
        return -1;
    }
    return result->code;
}

int run_status_classifier_error_code(void)
{
    /* A classification failure is an observation failure, not a terminal
     * verdict. Privileged work may still be running: rc7 only. */
    return RUN_STATUS_TERMINAL_INDETERMINATE;
}
