/* Modified 2026-11-25 (DFRoot GZIF): exercise run-scoped terminal markers and the classifier with harmless host files. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): assert the shared progress table, composed deadlines, exact failure branches, and malformed-marker cases. See NOTICE. */
#include "run_status.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static const char OUR_ID[] = "op-9d50d1d4-51af-4de5-8d68-2819df6f1201";
static const char FOREIGN_ID[] = "op-00000000-0000-0000-0000-000000000000";
static char root[PATH_MAX];
static char marker_path[PATH_MAX];
static const char *source_dir;

static void die(const char *message)
{
    fprintf(stderr, "%s\n", message);
    exit(1);
}

static void make_path(char *out, size_t capacity, const char *name)
{
    if (snprintf(out, capacity, "%s/%s", root, name) < 0
        || strlen(out) >= capacity) {
        die("test path is too long");
    }
}

static void reset_root(const char *case_root)
{
    if (snprintf(root, sizeof(root), "%s", case_root) < 0
        || strlen(root) >= sizeof(root)) {
        die("test root path is too long");
    }
    if (mkdir(root, 0700) != 0 && errno != EEXIST) {
        perror("mkdir test root");
        exit(1);
    }
    DIR *directory = opendir(root);
    if (directory == NULL) {
        perror("opendir test root");
        exit(1);
    }
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) {
            continue;
        }
        char path[PATH_MAX];
        make_path(path, sizeof(path), entry->d_name);
        unlink(path);
    }
    closedir(directory);
}

static void configure(const char *operation_id)
{
    if (run_status_configure(root, operation_id) != 0) {
        fprintf(stderr, "configure failed: %s\n", run_status_last_error());
        exit(1);
    }
}

static void expect_configure_reject(const char *operation_id)
{
    if (run_status_configure(root, operation_id) == 0) {
        die("unsafe operation ID was accepted");
    }
    if (run_status_last_error() == NULL || run_status_last_error()[0] == '\0') {
        die("unsafe operation ID was rejected without a diagnostic");
    }
}

static void write_text(const char *path, const char *text)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) {
        perror("open fixture");
        exit(1);
    }
    size_t length = strlen(text);
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(fd, text + offset, length - offset);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            perror("write fixture");
            exit(1);
        }
        offset += (size_t)written;
    }
    if (close(fd) != 0) {
        perror("close fixture");
        exit(1);
    }
}

static struct run_status_result classify(void)
{
    struct run_status_result result;
    memset(&result, 0xa5, sizeof(result));
    if (run_status_classify(&result) != 0) {
        fprintf(stderr, "classify failed: %s\n", result.diagnostic);
        exit(1);
    }
    return result;
}

static void expect_terminal(int expected_code)
{
    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_TERMINAL || result.code != expected_code
        || run_status_exit_code(&result) != expected_code) {
        fprintf(stderr, "expected terminal rc=%d, got verdict=%d code=%d exit=%d\n",
                expected_code, (int)result.verdict, result.code,
                run_status_exit_code(&result));
        exit(1);
    }
    if (result.diagnostic[0] == '\0') {
        die("terminal verdict did not provide a stable diagnostic");
    }
}

static void expect_no_terminal(void)
{
    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_NONE
        || run_status_exit_code(&result) != -1) {
        fprintf(stderr, "expected no terminal marker, got verdict=%d code=%d\n",
                (int)result.verdict, result.code);
        exit(1);
    }
    if (result.diagnostic[0] == '\0') {
        die("missing-marker verdict did not provide a diagnostic");
    }
}

static void expect_write_ok(int code)
{
    if (run_status_write(code) != 0) {
        fprintf(stderr, "write rc=%d failed: %s\n", code, run_status_last_error());
        exit(1);
    }
}

/* Match an exact branch-specific diagnostic: the fixed prefix must equal one
 * of open/write/fsync/close, optionally followed by ": <errno text>". A loose
 * "failed" match would let a write fault be reported as an fsync fault. */
static void expect_write_fail(int code, const char *expected_reason)
{
    if (run_status_write(code) == 0) {
        die("unsafe marker write succeeded");
    }
    const char *actual = run_status_last_error();
    size_t length = strlen(expected_reason);
    if (actual == NULL || strncmp(actual, expected_reason, length) != 0
        || (actual[length] != '\0' && actual[length] != ':')) {
        fprintf(stderr, "wrong write failure reason: expected '%s', got '%s'\n",
                expected_reason, actual == NULL ? "(null)" : actual);
        exit(1);
    }
}

static void test_valid_code(int code)
{
    configure(OUR_ID);
    expect_write_ok(code);
    expect_terminal(code);

    char name[RUN_STATUS_MARKER_NAME_MAX];
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc%d", OUR_ID, code) < 0) {
        die("marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    int fd = open(marker_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        perror("open marker");
        exit(1);
    }
    char payload[RUN_STATUS_PAYLOAD_MAX + 1];
    ssize_t length = read(fd, payload, sizeof(payload) - 1);
    close(fd);
    if (length <= 0) {
        die("marker payload is empty");
    }
    payload[length] = '\0';
    if (strstr(payload, OUR_ID) == NULL) {
        die("marker payload does not include the operation ID");
    }

    /* A second write of the same code must fail: O_EXCL is enforced. */
    expect_write_fail(code, "terminal marker open failed");
}

static void test_conflict(void)
{
    configure(OUR_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_SUCCESS);
    expect_write_ok(RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_CONFLICT
        || result.code != RUN_STATUS_TERMINAL_ERROR
        || run_status_exit_code(&result) != RUN_STATUS_TERMINAL_ERROR) {
        die("conflicting markers were not classified as a terminal error");
    }
    if (strstr(result.diagnostic, OUR_ID) == NULL
        || strstr(result.diagnostic, "conflicting") == NULL
        || strstr(result.diagnostic, "rc=0") == NULL
        || strstr(result.diagnostic, "rc=6") == NULL) {
        fprintf(stderr, "unstable conflict diagnostic: '%s'\n", result.diagnostic);
        exit(1);
    }
    /* Repeated classification must produce the identical diagnostic. */
    struct run_status_result again = classify();
    if (strcmp(result.diagnostic, again.diagnostic) != 0) {
        die("conflict diagnostic is not stable");
    }
}

static void test_foreign_id(void)
{
    configure(FOREIGN_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_SUCCESS);
    configure(OUR_ID);
    expect_no_terminal();
}

static void test_malformed_payload(void)
{
    configure(OUR_ID);
    char name[RUN_STATUS_MARKER_NAME_MAX];
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc0", OUR_ID) < 0) {
        die("marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    write_text(marker_path, "not a terminal marker\n");

    char second[RUN_STATUS_MARKER_NAME_MAX];
    if (snprintf(second, sizeof(second), "dfroot_terminal_%s_rc1", OUR_ID) < 0) {
        die("marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), second);
    write_text(marker_path,
               "DFROOT_TERMINAL 1\noperation_id=op-9d50d1d4-51af-4de5-8d68-2819df6f1201\n"
               "code=5\n");
    expect_no_terminal();
}

static void make_terminal_name(char *out, size_t capacity, const char *id, int code)
{
    if (snprintf(out, capacity, "dfroot_terminal_%s_rc%d", id, code) < 0
        || strlen(out) >= capacity) {
        die("marker name is too long");
    }
}

/* An empty marker file carries no protocol payload at all: ignored. */
static void test_empty_marker(void)
{
    configure(OUR_ID);
    char name[RUN_STATUS_MARKER_NAME_MAX];
    make_terminal_name(name, sizeof(name), OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    make_path(marker_path, sizeof(marker_path), name);
    write_text(marker_path, "");
    expect_no_terminal();
}

/* Torn writes (partial ID line or missing code terminator) are ignored. */
static void test_torn_marker(void)
{
    configure(OUR_ID);
    char name[RUN_STATUS_MARKER_NAME_MAX];
    make_terminal_name(name, sizeof(name), OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    make_path(marker_path, sizeof(marker_path), name);
    write_text(marker_path, "DFROOT_TERMINAL 1\noperation_id=op-9d50d1d4-51a");

    char second[RUN_STATUS_MARKER_NAME_MAX];
    make_terminal_name(second, sizeof(second), OUR_ID, RUN_STATUS_TERMINAL_ERROR);
    make_path(marker_path, sizeof(marker_path), second);
    char torn_code[RUN_STATUS_PAYLOAD_MAX];
    if (snprintf(torn_code, sizeof(torn_code),
                 "DFROOT_TERMINAL 1\noperation_id=%s\ncode=0", OUR_ID) < 0) {
        die("torn payload is too long");
    }
    write_text(marker_path, torn_code);
    expect_no_terminal();
}

/* A stale marker whose filename borrows our ID but whose payload belongs to a
 * different run must never be accepted as our terminal outcome. */
static void test_stale_foreign_marker(void)
{
    configure(OUR_ID);
    char name[RUN_STATUS_MARKER_NAME_MAX];
    make_terminal_name(name, sizeof(name), OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    make_path(marker_path, sizeof(marker_path), name);
    char payload[RUN_STATUS_PAYLOAD_MAX];
    if (snprintf(payload, sizeof(payload),
                 "DFROOT_TERMINAL 1\noperation_id=%s\ncode=0\n", FOREIGN_ID) < 0) {
        die("foreign payload is too long");
    }
    write_text(marker_path, payload);
    expect_no_terminal();
}

static void test_no_follow(void)
{
    configure(OUR_ID);
    char name[RUN_STATUS_MARKER_NAME_MAX];
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc0", OUR_ID) < 0) {
        die("marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    char target[PATH_MAX];
    make_path(target, sizeof(target), "target");
    write_text(target, "harmless\n");
    if (symlink("target", marker_path) != 0) {
        perror("symlink fixture");
        exit(1);
    }
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, "terminal marker open failed");
    expect_no_terminal();
}

static void test_fault(int fault, const char *expected_reason)
{
    configure(OUR_ID);
    run_status_inject_fault(fault);
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, expected_reason);
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
}

static void test_first_diagnostic_wins(int fault, const char *expected_reason)
{
    /* Both write/fsync and close fail; the first diagnostic must survive. */
    configure(OUR_ID);
    run_status_inject_fault(fault | RUN_STATUS_FAULT_CLOSE);
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, expected_reason);
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
}

static void test_bounded_wait(void)
{
    int64_t limit = RUN_STATUS_LATE_LOAD_WAIT_MS;
    if (!run_status_wait_continues(0, limit)
        || !run_status_wait_continues(limit - 1, limit)) {
        die("bounded wait rejected an in-deadline elapsed time");
    }
    if (run_status_wait_continues(limit, limit)
        || run_status_wait_continues(limit + 1, limit)
        || run_status_wait_continues(-1, limit)
        || run_status_wait_continues(0, 0)) {
        die("bounded wait accepted an expired or invalid deadline");
    }
}

/* Pure composition check: the reader must wait longer than the writer's
 * late-load poll plus its soft-reboot command wait combined. */
static void test_deadline_composition(void)
{
    if (RUN_STATUS_LATE_LOAD_WAIT_MS <= 0 || RUN_STATUS_SOFT_REBOOT_WAIT_MS <= 0
        || RUN_STATUS_TERMINAL_READ_WAIT_MS <= RUN_STATUS_LATE_LOAD_WAIT_MS
                                               + RUN_STATUS_SOFT_REBOOT_WAIT_MS) {
        fprintf(stderr, "deadline mismatch: reader=%d writer=%d+%d\n",
                RUN_STATUS_TERMINAL_READ_WAIT_MS, RUN_STATUS_LATE_LOAD_WAIT_MS,
                RUN_STATUS_SOFT_REBOOT_WAIT_MS);
        exit(1);
    }
}

static char *read_native_source(const char *name)
{
    char path[PATH_MAX];
    int written = snprintf(path, sizeof(path), "%s/%s", source_dir, name);
    if (written < 0 || (size_t)written >= sizeof(path)) {
        die("native source path is too long");
    }
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        perror("open native source");
        exit(1);
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 0
        || st.st_size > 200000) {
        die("native source is not a reasonably sized regular file");
    }
    char *buffer = malloc((size_t)st.st_size + 1);
    if (buffer == NULL) die("native source buffer allocation failed");
    size_t offset = 0;
    while (offset < (size_t)st.st_size) {
        ssize_t count = read(fd, buffer + offset, (size_t)st.st_size - offset);
        if (count < 0) {
            if (errno == EINTR) continue;
            perror("read native source");
            exit(1);
        }
        if (count == 0) break;
        offset += (size_t)count;
    }
    close(fd);
    buffer[offset] = '\0';
    return buffer;
}

static void expect_contains(const char *text, const char *needle)
{
    if (strstr(text, needle) == NULL) {
        fprintf(stderr, "native source wiring missing: %s\n", needle);
        exit(1);
    }
}

/* Collapse formatting so the wiring assertions survive harmless reflowing. */
static char *normalize_whitespace(const char *text)
{
    char *out = malloc(strlen(text) + 1);
    if (out == NULL) die("normalized source allocation failed");
    size_t j = 0;
    int in_space = 0;
    for (size_t i = 0; text[i] != '\0'; i++) {
        unsigned char c = (unsigned char)text[i];
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            if (!in_space) out[j++] = ' ';
            in_space = 1;
        } else {
            out[j++] = (char)c;
            in_space = 0;
        }
    }
    out[j] = '\0';
    return out;
}

/* Cross-file structural guard: bootstrap and exp must consume the shared
 * constants and the shared progress table, with no private copy or kill. */
static void test_shared_budget_wiring(void)
{
    if (source_dir == NULL) die("native source directory is required");
    char *bootstrap = read_native_source("bootstrap.c");
    char *exp = read_native_source("exp.c");
    char *b = normalize_whitespace(bootstrap);
    char *e = normalize_whitespace(exp);
    expect_contains(b, "run_status_wait_continues(now - start, RUN_STATUS_LATE_LOAD_WAIT_MS)");
    expect_contains(b, "run_ctx_timed(\"u:r:ksu:s0\", (char *[]){ KSUD, \"soft-reboot\", NULL }, "
                       "RUN_STATUS_SOFT_REBOOT_WAIT_MS, &soft_timed_out)");
    expect_contains(b, "waitpid(pid, &status, WNOHANG)");
    expect_contains(b, "return terminal(RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);");
    expect_contains(e, "run_status_wait_continues( now - start, "
                       "RUN_STATUS_TERMINAL_READ_WAIT_MS)");
    expect_contains(e, "run_status_progress_markers[");
    if (strstr(bootstrap, "kill(") != NULL
        || strstr(bootstrap, "LATE_LOAD_WAIT_LIMIT_MS") != NULL
        || strstr(exp, "progress_markers[]") != NULL
        || strstr(exp, "\"ksud start: SUCCESS\"") != NULL) {
        die("a deadline or progress marker was reintroduced locally");
    }
    free(bootstrap);
    free(exp);
    free(b);
    free(e);
}

static void test_protocol_helpers(void)
{
    if (run_status_soft_reboot_code(0) != RUN_STATUS_TERMINAL_SOFT_REBOOT_OK
        || run_status_soft_reboot_code(1) != RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL
        || run_status_soft_reboot_code(-1) != RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL
        || run_status_soft_reboot_code(42) != RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL) {
        die("soft-reboot command result mapped incorrectly");
    }
    /* Iterate the exported single source of truth: adding an rc back to any
     * legacy marker (notably /dev/dfm6) fails this case. */
    if (run_status_progress_marker_count < 13
        || run_status_progress_outcome(NULL) >= 0) {
        die("shared progress marker table is incomplete or unsafe");
    }
    int saw_dfm6_success = 0;
    for (size_t i = 0; i < run_status_progress_marker_count; i++) {
        const struct run_status_progress_marker *marker =
            &run_status_progress_markers[i];
        if (marker->path == NULL || marker->message == NULL || marker->path[0] != '/'
            || marker->outcome >= 0
            || run_status_progress_outcome(marker->path) >= 0) {
            fprintf(stderr, "legacy marker %s became terminal\n",
                    marker->path == NULL ? "(null)" : marker->path);
            exit(1);
        }
        if (!strcmp(marker->path, "/dev/dfm6")
            && !strcmp(marker->message, "ksud start: SUCCESS")) {
            saw_dfm6_success = 1;
        }
    }
    if (!saw_dfm6_success) {
        die("shared progress table lost the /dev/dfm6 SUCCESS diagnostic");
    }
    struct run_status_result conflict;
    memset(&conflict, 0, sizeof(conflict));
    conflict.verdict = RUN_STATUS_VERDICT_CONFLICT;
    conflict.code = RUN_STATUS_TERMINAL_SUCCESS;
    if (run_status_exit_code(&conflict) != RUN_STATUS_TERMINAL_ERROR) {
        die("conflict did not map to rc=1");
    }
    if (run_status_exit_code(NULL) != -1) {
        die("NULL classifier result did not map to keep-waiting");
    }
}

static void test_unconfigured(void)
{
    struct run_status_result result;
    memset(&result, 0, sizeof(result));
    if (run_status_classify(&result) == 0) {
        die("classifier succeeded before configuration");
    }
    if (run_status_write(RUN_STATUS_TERMINAL_SUCCESS) == 0) {
        die("write succeeded before configuration");
    }
}

static void run_case(const char *name)
{
    if (!strcmp(name, "rc0")) {
        test_valid_code(RUN_STATUS_TERMINAL_SUCCESS);
    } else if (!strcmp(name, "rc1")) {
        test_valid_code(RUN_STATUS_TERMINAL_ERROR);
    } else if (!strcmp(name, "rc4")) {
        test_valid_code(RUN_STATUS_TERMINAL_SOFT_REBOOT_OK);
    } else if (!strcmp(name, "rc6")) {
        test_valid_code(RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    } else if (!strcmp(name, "missing")) {
        configure(OUR_ID);
        expect_no_terminal();
    } else if (!strcmp(name, "conflict")) {
        test_conflict();
    } else if (!strcmp(name, "foreign_id")) {
        test_foreign_id();
    } else if (!strcmp(name, "malformed_payload")) {
        test_malformed_payload();
    } else if (!strcmp(name, "empty_marker")) {
        test_empty_marker();
    } else if (!strcmp(name, "torn_marker")) {
        test_torn_marker();
    } else if (!strcmp(name, "stale_foreign_marker")) {
        test_stale_foreign_marker();
    } else if (!strcmp(name, "unsafe_id")) {
        expect_configure_reject("");
        expect_configure_reject("../escape");
        expect_configure_reject("bad/id");
        expect_configure_reject(NULL);
    } else if (!strcmp(name, "write_failure")) {
        configure(OUR_ID);
        char name_buffer[RUN_STATUS_MARKER_NAME_MAX];
        if (snprintf(name_buffer, sizeof(name_buffer), "dfroot_terminal_%s_rc0", OUR_ID) < 0) {
            die("marker name is too long");
        }
        make_path(marker_path, sizeof(marker_path), name_buffer);
        write_text(marker_path, "pre-existing\n");
        expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, "terminal marker open failed");
    } else if (!strcmp(name, "no_follow")) {
        test_no_follow();
    } else if (!strcmp(name, "fault_write")) {
        test_fault(RUN_STATUS_FAULT_WRITE, "terminal marker write failed");
    } else if (!strcmp(name, "fault_fsync")) {
        test_fault(RUN_STATUS_FAULT_FSYNC, "terminal marker fsync failed");
    } else if (!strcmp(name, "fault_close")) {
        test_fault(RUN_STATUS_FAULT_CLOSE, "terminal marker close failed");
    } else if (!strcmp(name, "fault_write_close")) {
        test_first_diagnostic_wins(RUN_STATUS_FAULT_WRITE,
                                   "terminal marker write failed");
    } else if (!strcmp(name, "fault_fsync_close")) {
        test_first_diagnostic_wins(RUN_STATUS_FAULT_FSYNC,
                                   "terminal marker fsync failed");
    } else if (!strcmp(name, "bounded_wait")) {
        test_bounded_wait();
    } else if (!strcmp(name, "deadline_composition")) {
        test_deadline_composition();
    } else if (!strcmp(name, "shared_budget_wiring")) {
        test_shared_budget_wiring();
    } else if (!strcmp(name, "protocol_helpers")) {
        test_protocol_helpers();
    } else if (!strcmp(name, "unconfigured")) {
        test_unconfigured();
    } else {
        die("unknown test case");
    }
}

int main(int argc, char **argv)
{
    if (argc != 3 && argc != 4) {
        die("usage: run_status_test CASE ROOT [SOURCE_DIR]");
    }
    source_dir = argc == 4 ? argv[3] : NULL;
    reset_root(argv[2]);
    run_case(argv[1]);
    return 0;
}
