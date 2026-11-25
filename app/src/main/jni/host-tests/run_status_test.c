/* Modified 2026-11-25 (DFRoot GZIF): exercise run-scoped terminal markers and the classifier with harmless host files. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): assert the shared progress table, composed deadlines, exact failure branches, and malformed-marker cases. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): assert atomic publication, no final path on failure, the rc=7 channel, and a gated concurrent reader. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): gate the post-link rc7-before-retraction window and verify a transient temp-unlink retry. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): cover all three bounded-wait timeout sites with a harmless live fake child. See NOTICE. */
#include "run_status.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
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

/* A failed publication must leave neither a final marker nor a temp file that
 * could ever be mistaken for one. The temp prefix is deliberately different
 * from the classifier prefix, but debris is still a protocol violation. */
static void expect_no_final(const char *operation_id, int code)
{
    char name[RUN_STATUS_MARKER_NAME_MAX];
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc%d", operation_id, code) < 0
        || strlen(name) >= sizeof(name)) {
        die("marker name is too long");
    }
    char path[PATH_MAX];
    make_path(path, sizeof(path), name);
    struct stat st;
    if (lstat(path, &st) == 0) {
        fprintf(stderr, "final marker was left behind: %s\n", path);
        exit(1);
    }
    if (errno != ENOENT) {
        perror("lstat final marker");
        exit(1);
    }
}

static void expect_no_temp_files(void)
{
    DIR *directory = opendir(root);
    if (directory == NULL) {
        perror("opendir root");
        exit(1);
    }
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (strstr(entry->d_name, "dfroot_tmp_") != NULL) {
            fprintf(stderr, "temporary publication file was left behind: %s\n",
                    entry->d_name);
            exit(1);
        }
    }
    closedir(directory);
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

    expect_no_temp_files();

    /* A second write of the same code must fail at the atomic link step: the
     * final path is exclusive, so an existing marker is never overwritten. */
    expect_write_fail(code, "terminal marker link failed");
    expect_no_temp_files();
    expect_terminal(code);
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
    /* The symlink is the final path; the temp open is fresh, so the refusal is
     * enforced by the atomic link (EEXIST) and no temp file survives. */
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, "terminal marker link failed");
    expect_no_temp_files();
    expect_no_terminal();
}

static void test_fault(int fault, const char *expected_reason)
{
    configure(OUR_ID);
    run_status_inject_fault(fault);
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, expected_reason);
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
    /* Every injected fault happens before link(): the reader must see no
     * final marker and no complete payload at all. */
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_no_terminal();
}

static void test_first_diagnostic_wins(int fault, const char *expected_reason)
{
    /* Both write/fsync and close fail; the first diagnostic must survive. */
    configure(OUR_ID);
    run_status_inject_fault(fault | RUN_STATUS_FAULT_CLOSE);
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, expected_reason);
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_no_terminal();
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

/* Pure composition check. The late-load command and its poll share ONE
 * late-load budget, so the true worst-case writer duration is that single
 * budget plus the soft-reboot command wait. The reader must cover that sum. */
static void test_deadline_composition(void)
{
    int64_t writer_worst_case = (int64_t)RUN_STATUS_LATE_LOAD_WAIT_MS
                                + RUN_STATUS_SOFT_REBOOT_WAIT_MS;
    if (RUN_STATUS_LATE_LOAD_WAIT_MS <= 0 || RUN_STATUS_SOFT_REBOOT_WAIT_MS <= 0
        || (int64_t)RUN_STATUS_TERMINAL_READ_WAIT_MS < writer_worst_case) {
        fprintf(stderr, "deadline mismatch: reader=%d writer worst case=%lld "
                        "(late-load shared %d + soft-reboot %d)\n",
                RUN_STATUS_TERMINAL_READ_WAIT_MS, (long long)writer_worst_case,
                RUN_STATUS_LATE_LOAD_WAIT_MS, RUN_STATUS_SOFT_REBOOT_WAIT_MS);
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
    /* Late-load command and poll draw from ONE shared budget: the start is
     * captured before the command and the poll receives only the remainder. */
    expect_contains(b, "run_status_remaining_budget( late_elapsed, RUN_STATUS_LATE_LOAD_WAIT_MS)");
    expect_contains(b, "run_status_wait_continues(now - poll_start, late_poll_budget)");
    if (strstr(bootstrap, "run_status_wait_continues(now - start, RUN_STATUS_LATE_LOAD_WAIT_MS)")
        != NULL) {
        die("late-load poll was given a fresh budget");
    }
    expect_contains(b, "run_ctx_timed(\"u:r:ksu:s0\", (char *[]){ KSUD, \"soft-reboot\", NULL }, "
                       "RUN_STATUS_SOFT_REBOOT_WAIT_MS, &soft_timed_out, &soft_launch_failed)");
    /* The bounded wait itself now lives in the shared, host-tested helper; it
     * polls with WNOHANG and never signals the child. A timeout is rc=7, not a
     * soft-reboot failure. A failed fork() is the ordinary rc=6 failure. */
    expect_contains(b, "run_status_wait_child_bounded(pid, limit_ms, &exit_code)");
    expect_contains(b, "run_status_soft_reboot_launch_code( soft_launch_failed, soft_rc, "
                       "soft_timed_out)");
    expect_contains(e, "run_status_wait_continues( now - start, "
                       "RUN_STATUS_TERMINAL_READ_WAIT_MS)");
    expect_contains(e, "rc = run_status_reader_timeout_code(expired)");
    expect_contains(e, "run_status_progress_markers[");
    /* The writer must route every terminal publication through the shared
     * success/failure mapping; no code may return its intended value after a
     * failed publication. */
    expect_contains(b, "run_status_publication_exit_code(write_result, code)");
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

/* A post-link directory fsync failure is unconfirmed. The writer retracts its
 * own final link and publishes the rc=7 channel, so the reader reports
 * indeterminate rather than the intended success. */
static void test_directory_fsync_failure(void)
{
    configure(OUR_ID);
    run_status_inject_fault(RUN_STATUS_FAULT_FSYNC_DIR);
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS,
                      "terminal marker directory fsync failed");
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();

    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_TERMINAL
        || result.code != RUN_STATUS_TERMINAL_INDETERMINATE
        || run_status_exit_code(&result) != RUN_STATUS_TERMINAL_INDETERMINATE
        || strstr(result.diagnostic, "indeterminate") == NULL) {
        fprintf(stderr, "unconfirmed publication was not indeterminate: verdict=%d code=%d\n",
                (int)result.verdict, result.code);
        exit(1);
    }
    char name[RUN_STATUS_MARKER_NAME_MAX];
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc7", OUR_ID) < 0
        || strlen(name) >= sizeof(name)) {
        die("indeterminate marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    struct stat st;
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("indeterminate reader channel was not published");
    }
}

/* Even a concurrently present success marker cannot override the indeterminate
 * publication-failure channel. */
static void test_indeterminate_precedence(void)
{
    configure(OUR_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_SUCCESS);
    expect_write_ok(RUN_STATUS_TERMINAL_INDETERMINATE);
    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_TERMINAL
        || result.code != RUN_STATUS_TERMINAL_INDETERMINATE
        || run_status_exit_code(&result) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("indeterminate publication channel did not outrank success");
    }
}

/* End-to-end protocol check without executing bootstrap: an injected pre-link
 * failure maps to rc=7 on the writer side and NONE (-1) on the reader side. */
static void test_publication_failure_protocol(void)
{
    configure(OUR_ID);
    run_status_inject_fault(RUN_STATUS_FAULT_WRITE);
    int write_result = run_status_write(RUN_STATUS_TERMINAL_SUCCESS);
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
    int writer_exit = run_status_publication_exit_code(write_result,
                                                       RUN_STATUS_TERMINAL_SUCCESS);
    if (write_result == 0 || writer_exit != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("a failed publication was mapped to its intended success code");
    }
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_no_terminal();

    /* The confirmed path still returns the intended code. */
    expect_write_ok(RUN_STATUS_TERMINAL_SUCCESS);
    if (run_status_publication_exit_code(0, RUN_STATUS_TERMINAL_SUCCESS)
        != RUN_STATUS_TERMINAL_SUCCESS) {
        die("a confirmed publication was not mapped to its intended code");
    }
    expect_terminal(RUN_STATUS_TERMINAL_SUCCESS);
}

/* Deterministic gated fixture: the writer is paused after creating the temp
 * file and before its failing write, while an independent reader polls 16
 * times. The reader must never observe a complete payload. No sleeps and no
 * scheduling luck: every poll is issued and acknowledged over a pipe. */
#define READER_POLLS 8

static void test_concurrent_failed_publish(void)
{
    configure(OUR_ID);
    int gate[2], observed[2], release[2], paused[2];
    if (pipe(gate) != 0 || pipe(observed) != 0
        || pipe(release) != 0 || pipe(paused) != 0) {
        die("gated reader pipe failed");
    }
    run_status_set_writer_pause(release[0], paused[1]);

    pid_t writer = fork();
    if (writer < 0) die("gated writer fork failed");
    if (writer == 0) {
        close(gate[0]); close(gate[1]);
        close(observed[0]); close(observed[1]);
        close(release[1]);
        close(paused[0]);
        run_status_inject_fault(RUN_STATUS_FAULT_WRITE);
        int rc = run_status_write(RUN_STATUS_TERMINAL_SUCCESS);
        _exit(rc == 0 ? 0 : RUN_STATUS_TERMINAL_INDETERMINATE);
    }

    pid_t reader = fork();
    if (reader < 0) die("gated reader fork failed");
    if (reader == 0) {
        close(gate[1]);
        close(observed[0]);
        close(release[0]); close(release[1]);
        close(paused[0]); close(paused[1]);
        char command;
        char saw = 'G';
        for (int i = 0; i < 2 * READER_POLLS; i++) {
            ssize_t count = read(gate[0], &command, 1);
            if (count != 1) _exit(2);
            struct run_status_result result;
            if (run_status_classify(&result) == 0
                && result.verdict != RUN_STATUS_VERDICT_NONE) {
                saw = 'B';
            }
            if (write(observed[1], &saw, 1) != 1) _exit(3);
        }
        if (write(observed[1], &saw, 1) != 1) _exit(4);
        _exit(0);
    }

    close(gate[0]); close(observed[1]); close(release[0]); close(paused[1]);

    char byte;
    if (read(paused[0], &byte, 1) != 1 || byte != 'p') {
        die("writer never reached its checked pause point");
    }
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "p", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("gated reader handshake failed while writer was paused");
        }
        if (byte == 'B') die("reader saw a marker before the payload was written");
    }
    if (write(release[1], "g", 1) != 1) die("writer release failed");
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "p", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("gated reader handshake failed after the failing write");
        }
        if (byte == 'B') die("reader saw a complete payload from a failed publication");
    }
    if (read(observed[0], &byte, 1) != 1 || byte == 'B') {
        die("concurrent reader observed a terminal marker");
    }

    int status = 0;
    if (waitpid(writer, &status, 0) != writer || !WIFEXITED(status)
        || WEXITSTATUS(status) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("gated writer did not exit with indeterminate rc=7");
    }
    if (waitpid(reader, &status, 0) != reader || !WIFEXITED(status)
        || WEXITSTATUS(status) != 0) {
        die("gated reader fixture failed");
    }
    close(gate[1]); close(observed[0]); close(release[1]); close(paused[0]);
    run_status_set_writer_pause(-1, -1);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_no_terminal();
}

/* Post-link failure with a gated concurrent reader. The writer is paused by
 * the retract hook, which fires AFTER the rc=7 channel is published but BEFORE
 * the intended rc=0 marker is retracted. At that point both markers coexist;
 * the classifier scans them all before choosing a verdict and rc=7 outranks
 * success, so the reader must never report SUCCESS. After release the steady
 * state is rc=7 only. A second phase injects a one-shot temp-unlink failure so
 * the unconfirmed path's retry is exercised deterministically. */
static void test_post_link_gated_reader(void)
{
    char original_root[PATH_MAX];
    if (snprintf(original_root, sizeof(original_root), "%s", root) < 0
        || strlen(original_root) >= sizeof(original_root)) {
        die("case root path is too long");
    }

    /* Phase A: injected directory-fsync failure plus a gated reader. */
    configure(OUR_ID);
    int gate[2], observed[2], release[2], paused[2];
    if (pipe(gate) != 0 || pipe(observed) != 0
        || pipe(release) != 0 || pipe(paused) != 0) {
        die("post-link reader pipe failed");
    }
    run_status_set_retract_pause(release[0], paused[1]);

    pid_t writer = fork();
    if (writer < 0) die("post-link writer fork failed");
    if (writer == 0) {
        close(gate[0]); close(gate[1]);
        close(observed[0]); close(observed[1]);
        close(release[1]);
        close(paused[0]);
        run_status_inject_fault(RUN_STATUS_FAULT_FSYNC_DIR);
        int rc = run_status_write(RUN_STATUS_TERMINAL_SUCCESS);
        _exit(rc == 0 ? 0 : RUN_STATUS_TERMINAL_INDETERMINATE);
    }

    pid_t reader = fork();
    if (reader < 0) die("post-link reader fork failed");
    if (reader == 0) {
        close(gate[1]);
        close(observed[0]);
        close(release[0]); close(release[1]);
        close(paused[0]); close(paused[1]);
        char command;
        char saw_success = 'G';
        char saw_indeterminate = 'N';
        for (int i = 0; i < 2 * READER_POLLS; i++) {
            ssize_t count = read(gate[0], &command, 1);
            if (count != 1) _exit(2);
            struct run_status_result result;
            if (run_status_classify(&result) == 0
                && result.verdict == RUN_STATUS_VERDICT_TERMINAL) {
                if (result.code == RUN_STATUS_TERMINAL_SUCCESS) saw_success = 'B';
                if (result.code == RUN_STATUS_TERMINAL_INDETERMINATE) {
                    saw_indeterminate = 'R';
                }
            }
            if (write(observed[1], &saw_success, 1) != 1) _exit(3);
        }
        if (write(observed[1], &saw_indeterminate, 1) != 1) _exit(4);
        _exit(0);
    }

    close(gate[0]); close(observed[1]); close(release[0]); close(paused[1]);

    char byte;
    if (read(paused[0], &byte, 1) != 1 || byte != 'p') {
        die("post-link writer never reached the retract pause");
    }
    /* Prove the gate really is the coexistence window: both the intended rc=0
     * marker and the rc=7 channel are present, yet classification is rc=7. */
    char name[RUN_STATUS_MARKER_NAME_MAX];
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc0", OUR_ID) < 0
        || strlen(name) >= sizeof(name)) {
        die("marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    struct stat st;
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("intended rc=0 marker was missing before retraction");
    }
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc7", OUR_ID) < 0
        || strlen(name) >= sizeof(name)) {
        die("marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("rc=7 channel was not published before retraction");
    }
    struct run_status_result at_gate = classify();
    if (at_gate.verdict != RUN_STATUS_VERDICT_TERMINAL
        || at_gate.code != RUN_STATUS_TERMINAL_INDETERMINATE) {
        fprintf(stderr, "rc7 did not outrank coexisting rc0: verdict=%d code=%d\n",
                (int)at_gate.verdict, at_gate.code);
        exit(1);
    }

    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "p", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("post-link reader handshake failed in coexistence window");
        }
        if (byte == 'B') die("reader reported SUCCESS while rc7 was published");
    }
    if (write(release[1], "g", 1) != 1) die("post-link writer release failed");
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "p", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("post-link reader handshake failed after retraction");
        }
        if (byte == 'B') die("reader reported SUCCESS after retraction");
    }
    if (read(observed[0], &byte, 1) != 1 || byte != 'R') {
        die("post-link reader never observed the rc7 indeterminate channel");
    }

    int status = 0;
    if (waitpid(writer, &status, 0) != writer || !WIFEXITED(status)
        || WEXITSTATUS(status) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("post-link writer did not exit with indeterminate rc=7");
    }
    if (waitpid(reader, &status, 0) != reader || !WIFEXITED(status)
        || WEXITSTATUS(status) != 0) {
        die("post-link reader fixture failed");
    }
    close(gate[1]); close(observed[0]); close(release[1]); close(paused[0]);
    run_status_set_retract_pause(-1, -1);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);

    /* Phase B: a transient temp-unlink failure must be retried, leaving no
     * debris and preserving the first diagnostic. */
    reset_root(original_root);
    configure(OUR_ID);
    run_status_inject_fault(RUN_STATUS_FAULT_UNLINK_TEMP);
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, "terminal marker unlink failed");
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
}

/* A harmless fake child: it blocks reading a pipe and never executes a
 * payload. A bounded wait cannot observe an exit, so the writer must not claim
 * completion and must not signal the child. */
static pid_t spawn_live_fake_child(int *release_fd)
{
    int gate[2];
    if (pipe(gate) != 0) die("fake child pipe failed");
    pid_t child = fork();
    if (child < 0) die("fake child fork failed");
    if (child == 0) {
        close(gate[1]);
        char byte;
        ssize_t count;
        do {
            count = read(gate[0], &byte, 1);
        } while (count < 0 && errno == EINTR);
        _exit(0);
    }
    close(gate[0]);
    *release_fd = gate[1];
    return child;
}

static void expect_child_still_running(pid_t child)
{
    int status = 0;
    if (waitpid(child, &status, WNOHANG) != 0) {
        die("fake child was killed or reaped by a bounded wait");
    }
}

static void release_fake_child(pid_t child, int release_fd)
{
    int status = 0;
    if (write(release_fd, "g", 1) != 1) die("fake child release failed");
    close(release_fd);
    if (waitpid(child, &status, 0) != child || !WIFEXITED(status)
        || WEXITSTATUS(status) != 0) {
        die("fake child did not exit cleanly after release");
    }
}

/* Both command-timeout sites: a live child outlives the deadline, the wait
 * returns "not observed", no exit code is invented, no signal is sent, and the
 * terminal code is rc=7. Publishing rc=7 gives the reader an honest outcome. */
static void test_live_child_timeout(int late_load)
{
    configure(OUR_ID);
    int release_fd = -1;
    pid_t child = spawn_live_fake_child(&release_fd);
    int exit_code = 12345;
    int observed = run_status_wait_child_bounded(child, 20, &exit_code);
    if (observed != 0) die("bounded wait did not time out on a live child");
    if (exit_code != 12345) die("bounded wait invented an exit code for a live child");
    expect_child_still_running(child);
    int terminal = late_load
                   ? run_status_late_load_wait_code(exit_code, 1)
                   : run_status_soft_reboot_wait_code(exit_code, 1);
    if (terminal != RUN_STATUS_TERMINAL_INDETERMINATE) {
        fprintf(stderr, "command timeout mapped to rc=%d, expected rc=7\n", terminal);
        exit(1);
    }
    expect_write_ok(RUN_STATUS_TERMINAL_INDETERMINATE);
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
    release_fake_child(child, release_fd);
}

/* rc=4/6 (and rc=0/1) require a genuinely observed exit; a timeout or a
 * wait/signal failure is always indeterminate. */
static void test_observed_exit_mapping(void)
{
    configure(OUR_ID);
    pid_t child = fork();
    if (child < 0) die("observed-exit child fork failed");
    if (child == 0) _exit(0);
    int exit_code = -1;
    int observed = run_status_wait_child_bounded(child, 2000, &exit_code);
    if (observed != 1 || exit_code != 0) die("bounded wait missed an observed exit");
    if (run_status_late_load_wait_code(0, 0) != RUN_STATUS_TERMINAL_SUCCESS
        || run_status_late_load_wait_code(9, 0) != RUN_STATUS_TERMINAL_ERROR
        || run_status_late_load_wait_code(0, 1) != RUN_STATUS_TERMINAL_INDETERMINATE
        || run_status_late_load_wait_code(-1, 0) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("late-load exit mapping was not limited to observed exits");
    }
    if (run_status_soft_reboot_wait_code(0, 0) != RUN_STATUS_TERMINAL_SOFT_REBOOT_OK
        || run_status_soft_reboot_wait_code(9, 0) != RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL
        || run_status_soft_reboot_wait_code(0, 1) != RUN_STATUS_TERMINAL_INDETERMINATE
        || run_status_soft_reboot_wait_code(-1, 0) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("soft-reboot rc4/rc6 was not limited to observed exits");
    }
}

/* The poll deadline keeps the unchanged shared constant; expiry maps to rc=7
 * while a still-continuing wait stays non-terminal (-1). */
static void test_poll_deadline_timeout(void)
{
    if (RUN_STATUS_LATE_LOAD_WAIT_MS != 60000
        || RUN_STATUS_SOFT_REBOOT_WAIT_MS != 30000
        || RUN_STATUS_TERMINAL_READ_WAIT_MS != 100000) {
        die("shared deadlines changed");
    }
    int64_t limit = RUN_STATUS_LATE_LOAD_WAIT_MS;
    if (!run_status_wait_continues(limit - 1, limit)
        || run_status_wait_continues(limit, limit)
        || run_status_wait_continues(-1, limit)) {
        die("poll deadline boundary changed");
    }
    if (run_status_bounded_wait_timeout_code(0) != -1
        || run_status_bounded_wait_timeout_code(1) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("poll expiry did not map to rc=7");
    }
    if (source_dir == NULL) die("native source directory is required");
    char *bootstrap = read_native_source("bootstrap.c");
    char *b = normalize_whitespace(bootstrap);
    expect_contains(b, "run_status_remaining_budget( late_elapsed, RUN_STATUS_LATE_LOAD_WAIT_MS)");
    expect_contains(b, "run_status_wait_continues(now - poll_start, late_poll_budget)");
    expect_contains(b, "return terminal(run_status_bounded_wait_timeout_code(expired));");
    free(bootstrap);
    free(b);
}

/* Cross-file guard for all three timeout sites: no unbounded late-load wait,
 * no kill, and each timeout routed to rc=7 through the shared mapping. */
static void test_timeout_indeterminate_wiring(void)
{
    if (source_dir == NULL) die("native source directory is required");
    char *bootstrap = read_native_source("bootstrap.c");
    char *b = normalize_whitespace(bootstrap);
    if (strstr(bootstrap, "run_ctx(NULL") != NULL) {
        die("unbounded late-load wait was reintroduced");
    }
    if (strstr(bootstrap, "kill(") != NULL) {
        die("a kill was reintroduced into bootstrap");
    }
    expect_contains(b, "run_ctx_timed(NULL, (char *[]){ \"/data/local/tmp/.ksud-stage\", "
                       "\"late-load\", \"--package-name\", config.manager, NULL }, "
                       "RUN_STATUS_LATE_LOAD_WAIT_MS, &late_timed_out, &late_launch_failed)");
    expect_contains(b, "run_status_late_load_launch_code( late_launch_failed, late_rc, "
                       "late_timed_out)");
    expect_contains(b, "run_status_wait_child_bounded(pid, limit_ms, &exit_code)");
    expect_contains(b, "run_status_remaining_budget( late_elapsed, RUN_STATUS_LATE_LOAD_WAIT_MS)");
    expect_contains(b, "return terminal(run_status_bounded_wait_timeout_code(expired));");
    expect_contains(b, "run_ctx_timed(\"u:r:ksu:s0\", (char *[]){ KSUD, \"soft-reboot\", NULL }, "
                       "RUN_STATUS_SOFT_REBOOT_WAIT_MS, &soft_timed_out, &soft_launch_failed)");
    expect_contains(b, "return terminal(run_status_soft_reboot_launch_code( soft_launch_failed, "
                       "soft_rc, soft_timed_out))");
    free(bootstrap);
    free(b);
}

/* The native reader's own deadline expiry must never be reported as SUCCESS
 * or as a definitive failure: privileged children may still be alive. Only
 * setup() failures (before any operation) keep rc=2. */
static void test_reader_timeout_indeterminate(void)
{
    int code = run_status_reader_timeout_code(1);
    if (code != RUN_STATUS_TERMINAL_INDETERMINATE
        || code == RUN_STATUS_TERMINAL_SUCCESS
        || code == RUN_STATUS_TERMINAL_ERROR
        || code == RUN_STATUS_TERMINAL_SOFT_REBOOT_OK
        || code == RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL) {
        fprintf(stderr, "reader timeout mapped to definitive rc=%d\n", code);
        exit(1);
    }
    if (run_status_reader_timeout_code(0) != -1) {
        die("a continuing reader wait was mapped to a terminal code");
    }
    if (source_dir == NULL) die("native source directory is required");
    char *exp = read_native_source("exp.c");
    char *e = normalize_whitespace(exp);
    expect_contains(e, "rc = run_status_reader_timeout_code(expired)");
    expect_contains(e, "if (setup(argc, argv) != 0) return 2;");
    if (strstr(e, "timeout: ERROR - check logcat & dmesg") != NULL) {
        die("reader timeout still reports a definitive failure");
    }
    free(exp);
    free(e);
}

/* Pure shared-budget arithmetic: the poll receives only the time the late-load
 * command left in the same deadline, never a fresh full limit. */
static void test_shared_late_load_budget(void)
{
    int64_t limit = RUN_STATUS_LATE_LOAD_WAIT_MS;
    if (run_status_remaining_budget(0, limit) != limit
        || run_status_remaining_budget(1000, limit) != limit - 1000
        || run_status_remaining_budget(limit - 1, limit) != 1
        || run_status_remaining_budget(limit, limit) != -1
        || run_status_remaining_budget(limit + 1, limit) != -1
        || run_status_remaining_budget(-1, limit) != -1
        || run_status_remaining_budget(0, 0) != -1) {
        die("shared-budget remainder was not clamped to the shared deadline");
    }
    if (source_dir == NULL) die("native source directory is required");
    char *bootstrap = read_native_source("bootstrap.c");
    char *b = normalize_whitespace(bootstrap);
    expect_contains(b, "int64_t late_start = monotonic_ms();");
    expect_contains(b, "run_status_remaining_budget( late_elapsed, RUN_STATUS_LATE_LOAD_WAIT_MS)");
    expect_contains(b, "run_status_wait_continues(now - poll_start, late_poll_budget)");
    if (strstr(bootstrap, "run_status_wait_continues(now - start, RUN_STATUS_LATE_LOAD_WAIT_MS)")
        != NULL) {
        die("late-load poll reintroduced a fresh full budget");
    }
    free(bootstrap);
    free(b);
}

/* A failed fork() starts no child: keep the ordinary rc=1 / rc=6 failure.
 * Timeout and unobserved exits still map to rc=7. */
static void test_launch_failure_mapping(void)
{
    if (run_status_late_load_launch_code(1, 0, 0) != RUN_STATUS_TERMINAL_ERROR
        || run_status_late_load_launch_code(1, 0, 1) != RUN_STATUS_TERMINAL_ERROR
        || run_status_late_load_launch_code(0, 0, 0) != RUN_STATUS_TERMINAL_SUCCESS
        || run_status_late_load_launch_code(0, 9, 0) != RUN_STATUS_TERMINAL_ERROR
        || run_status_late_load_launch_code(0, 0, 1) != RUN_STATUS_TERMINAL_INDETERMINATE
        || run_status_soft_reboot_launch_code(1, 0, 0) != RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL
        || run_status_soft_reboot_launch_code(1, 0, 1) != RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL
        || run_status_soft_reboot_launch_code(0, 0, 0) != RUN_STATUS_TERMINAL_SOFT_REBOOT_OK
        || run_status_soft_reboot_launch_code(0, 9, 0) != RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL
        || run_status_soft_reboot_launch_code(0, 0, 1) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("fork launch failure was not mapped to the ordinary command failure");
    }
    if (source_dir == NULL) die("native source directory is required");
    char *bootstrap = read_native_source("bootstrap.c");
    char *b = normalize_whitespace(bootstrap);
    expect_contains(b, "if (pid < 0) { if (launch_failed != NULL) *launch_failed = 1; return -1; }");
    free(bootstrap);
    free(b);
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
    } else if (!strcmp(name, "rc7")) {
        test_valid_code(RUN_STATUS_TERMINAL_INDETERMINATE);
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
        /* The temp file is fresh; the conflict is detected atomically at link(). */
        expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, "terminal marker link failed");
        expect_no_temp_files();
        char after[64];
        int fd = open(marker_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) {
            perror("open pre-existing marker");
            exit(1);
        }
        ssize_t length = read(fd, after, sizeof(after) - 1);
        close(fd);
        if (length < 0 || (size_t)length != strlen("pre-existing\n")
            || memcmp(after, "pre-existing\n", strlen("pre-existing\n")) != 0) {
            die("a failed publication overwrote the pre-existing final path");
        }
        expect_no_terminal();
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
    } else if (!strcmp(name, "fault_dir_fsync")) {
        test_directory_fsync_failure();
    } else if (!strcmp(name, "indeterminate_precedence")) {
        test_indeterminate_precedence();
    } else if (!strcmp(name, "publication_failure_protocol")) {
        test_publication_failure_protocol();
    } else if (!strcmp(name, "concurrent_failed_publish")) {
        test_concurrent_failed_publish();
    } else if (!strcmp(name, "post_link_gated_reader")) {
        test_post_link_gated_reader();
    } else if (!strcmp(name, "late_load_command_timeout")) {
        test_live_child_timeout(1);
    } else if (!strcmp(name, "soft_reboot_command_timeout")) {
        test_live_child_timeout(0);
    } else if (!strcmp(name, "observed_exit_mapping")) {
        test_observed_exit_mapping();
    } else if (!strcmp(name, "late_load_poll_timeout")) {
        test_poll_deadline_timeout();
    } else if (!strcmp(name, "timeout_indeterminate_wiring")) {
        test_timeout_indeterminate_wiring();
    } else if (!strcmp(name, "reader_timeout_indeterminate")) {
        test_reader_timeout_indeterminate();
    } else if (!strcmp(name, "shared_late_load_budget")) {
        test_shared_late_load_budget();
    } else if (!strcmp(name, "launch_failure_mapping")) {
        test_launch_failure_mapping();
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
