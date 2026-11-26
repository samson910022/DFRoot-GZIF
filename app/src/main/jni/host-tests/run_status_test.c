/* Modified 2026-11-25 (DFRoot GZIF): exercise run-scoped terminal markers and the classifier with harmless host files. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): assert the shared progress table, composed deadlines, exact failure branches, and malformed-marker cases. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): assert atomic publication, no final path on failure, the rc=7 channel, and a gated concurrent reader. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): gate the post-link rc7-before-retraction window and verify a transient temp-unlink retry. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): cover all three bounded-wait timeout sites with a harmless live fake child. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): cover the reserved pre-admission diagnostic and the pure reader decision helper. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): inject a classifier observation failure and assert the reader exits rc7 with no confirmed result. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): gate the whole post-link/pre-rc7 observation window with a deterministic concurrent reader. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): assert conflicting terminal observations map to indeterminate rc7, not definitive rc1. See NOTICE. */
/* Modified 2026-11-25 (DFRoot GZIF): rework the post-link case into fix verification: unconfirmed markers yield no decision, and a matching confirmation is required for success. See NOTICE. */
/* Modified 2026-11-26 (DFRoot GZIF): add R2-14 residual-window boundary fixtures that record both observations and a proved/not-proved state-machine table. See NOTICE. */
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
static const char *configured_id;

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
    configured_id = operation_id;
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

static void expect_write_ok(int code);
static void make_terminal_name(char *out, size_t capacity, const char *id, int code);

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

/* Build a well-formed marker payload for an arbitrary identity and code. */
static void make_test_payload(char *out, size_t capacity, const char *id, int code)
{
    if (snprintf(out, capacity,
                 "DFROOT_TERMINAL 1\noperation_id=%s\ncode=%d\n", id, code) < 0
        || strlen(out) >= capacity) {
        die("test payload is too long");
    }
}

static void write_named_marker(const char *name, const char *payload)
{
    make_path(marker_path, sizeof(marker_path), name);
    write_text(marker_path, payload);
}

static void make_confirm_name(char *out, size_t capacity, const char *id, int code)
{
    if (snprintf(out, capacity, "dfroot_confirm_%s_rc%d", id, code) < 0
        || strlen(out) >= capacity) {
        die("confirmation marker name is too long");
    }
}

static void make_confirm_payload(char *out, size_t capacity, const char *id, int code)
{
    if (snprintf(out, capacity,
                 "DFROOT_CONFIRM 1\noperation_id=%s\ncode=%d\n", id, code) < 0
        || strlen(out) >= capacity) {
        die("confirmation payload is too long");
    }
}

/* Harmless hand-built fixtures for the crossed/unconfirmed marker cases. */
static void write_terminal_fixture(const char *id, int code)
{
    char name[RUN_STATUS_MARKER_NAME_MAX];
    char payload[RUN_STATUS_PAYLOAD_MAX];
    make_terminal_name(name, sizeof(name), id, code);
    make_test_payload(payload, sizeof(payload), id, code);
    write_named_marker(name, payload);
}

static void write_confirm_fixture(const char *id, int code)
{
    char name[RUN_STATUS_MARKER_NAME_MAX];
    char payload[RUN_STATUS_PAYLOAD_MAX];
    make_confirm_name(name, sizeof(name), id, code);
    make_confirm_payload(payload, sizeof(payload), id, code);
    write_named_marker(name, payload);
}

static void expect_confirm_present(const char *id, int code)
{
    char name[RUN_STATUS_MARKER_NAME_MAX];
    make_confirm_name(name, sizeof(name), id, code);
    make_path(marker_path, sizeof(marker_path), name);
    struct stat st;
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        fprintf(stderr, "matching confirmation marker is missing: %s\n", name);
        exit(1);
    }
}

static void expect_no_confirm(const char *id, int code)
{
    char name[RUN_STATUS_MARKER_NAME_MAX];
    make_confirm_name(name, sizeof(name), id, code);
    make_path(marker_path, sizeof(marker_path), name);
    struct stat st;
    if (lstat(marker_path, &st) == 0) {
        fprintf(stderr, "confirmation marker was left behind: %s\n", name);
        exit(1);
    }
    if (errno != ENOENT) {
        perror("lstat confirmation marker");
        exit(1);
    }
}

static void expect_none_with_diagnostic(const char *needle)
{
    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_NONE
        || run_status_exit_code(&result) != -1
        || result.publication_confirmed != 0) {
        fprintf(stderr, "expected no decision, got verdict=%d code=%d confirmed=%d\n",
                (int)result.verdict, run_status_exit_code(&result),
                result.publication_confirmed);
        exit(1);
    }
    if (strstr(result.diagnostic, needle) == NULL) {
        fprintf(stderr, "unconfirmed diagnostic '%s' lacks '%s'\n",
                result.diagnostic, needle);
        exit(1);
    }
}

/* The bootstrap side of the protocol: no admitted snapshot, so only the
 * reserved identity publishes the rc=1 diagnostic. */
static void publish_pre_admission_failure(void)
{
    configure(RUN_STATUS_PRE_ADMISSION_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_ERROR);
}

/* Mirror the native reader loop: classify the admitted operation first, scan
 * the reserved diagnostic only when no admitted marker exists, then decide. */
static struct run_status_decision decide_from_reader(
    const struct run_status_result *admitted)
{
    struct run_status_decision decision;
    memset(&decision, 0xa5, sizeof(decision));
    int pre = admitted->verdict == RUN_STATUS_VERDICT_NONE
              && run_status_pre_admission_failure();
    run_status_decide(admitted, pre, &decision);
    return decision;
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
    if (expected_code != RUN_STATUS_TERMINAL_INDETERMINATE
        && !result.publication_confirmed) {
        die("definitive terminal verdict lacked a matching confirmation");
    }
    if (expected_code == RUN_STATUS_TERMINAL_INDETERMINATE
        && result.publication_confirmed != 0) {
        die("indeterminate result was reported as publication-confirmed");
    }
}

/* rc7 steady state: either the authoritative indeterminate channel or a
 * conflict, both of which map to the same non-actionable indeterminate code. */
static void expect_indeterminate(void)
{
    struct run_status_result result = classify();
    if (run_status_exit_code(&result) != RUN_STATUS_TERMINAL_INDETERMINATE
        || result.publication_confirmed != 0) {
        fprintf(stderr, "observation was actionable: verdict=%d code=%d confirmed=%d\n",
                (int)result.verdict, result.code, result.publication_confirmed);
        exit(1);
    }
}

static void expect_no_terminal(void)
{
    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_NONE
        || run_status_exit_code(&result) != -1
        || result.publication_confirmed != 0) {
        fprintf(stderr, "expected no decision, got verdict=%d code=%d confirmed=%d\n",
                (int)result.verdict, result.code, result.publication_confirmed);
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
    /* A successful write publishes BOTH the intended final marker and its
     * matching confirmation, with no debris. */
    expect_confirm_present(configured_id, code);
    expect_no_temp_files();
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
     * final path is exclusive, so an existing marker is never overwritten.
     * ANY publication failure now publishes rc7 first, so even though the
     * original confirmed marker survives, the reader reports indeterminate. */
    expect_write_fail(code, "terminal marker link failed");
    expect_no_temp_files();
    expect_confirm_present(OUR_ID, code);
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
}

static void test_conflict(void)
{
    /* reset_root() rewrites the global root buffer, so keep a stable copy for
     * the second, independent conflict pair below. */
    char case_root[PATH_MAX];
    if (snprintf(case_root, sizeof(case_root), "%s", root) < 0
        || strlen(case_root) >= sizeof(case_root)) {
        die("case root path is too long");
    }
    configure(OUR_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_SUCCESS);
    expect_write_ok(RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_CONFLICT
        || result.code != RUN_STATUS_TERMINAL_INDETERMINATE
        || run_status_exit_code(&result) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("conflicting markers were not classified as indeterminate rc=7");
    }
    if (strstr(result.diagnostic, OUR_ID) == NULL
        || strstr(result.diagnostic, "conflicting") == NULL
        || strstr(result.diagnostic, "completion unverified") == NULL
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

    /* A different pair of CONFIRMED codes also disagrees: rc=1 and rc=4 must
     * still be an indeterminate conflict, never a definitive rc=1 error. */
    reset_root(case_root);
    configure(OUR_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_ERROR);
    expect_write_ok(RUN_STATUS_TERMINAL_SOFT_REBOOT_OK);
    struct run_status_result second = classify();
    if (second.verdict != RUN_STATUS_VERDICT_CONFLICT
        || second.code != RUN_STATUS_TERMINAL_INDETERMINATE
        || run_status_exit_code(&second) != RUN_STATUS_TERMINAL_INDETERMINATE
        || strstr(second.diagnostic, "completion unverified") == NULL
        || strstr(second.diagnostic, "rc=1") == NULL
        || strstr(second.diagnostic, "rc=4") == NULL) {
        fprintf(stderr, "confirmed-code conflict was definitive: code=%d diagnostic='%s'\n",
                run_status_exit_code(&second), second.diagnostic);
        exit(1);
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
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    /* The pre-existing symlink is ignored and the failed publication published
     * the indeterminate channel, never success. */
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
}

static void test_fault(int fault, const char *expected_reason)
{
    configure(OUR_ID);
    run_status_inject_fault(fault);
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, expected_reason);
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
    /* Every injected fault here happens before link(): no intended marker and
     * no confirmation is left, but rc7 is published first so the reader sees
     * indeterminate rather than a clean absence or a definitive result. */
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
}

static void test_first_diagnostic_wins(int fault, const char *expected_reason)
{
    /* Both write/fsync and close fail; the first diagnostic must survive. */
    configure(OUR_ID);
    run_status_inject_fault(fault | RUN_STATUS_FAULT_CLOSE);
    expect_write_fail(RUN_STATUS_TERMINAL_SUCCESS, expected_reason);
    run_status_inject_fault(RUN_STATUS_FAULT_NONE);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
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

/* Cross-file structural guard. Under the owner's single-protocol decision,
 * bootstrap and exp consume ONE protocol (status_protocol.c) and ONE set of
 * bounded deadline constants. No private outcome channel, no private deadline
 * copy, and no terminal publication that bypasses the shared mapping. */
static void test_shared_budget_wiring(void)
{
    if (source_dir == NULL) die("native source directory is required");
    char *bootstrap = read_native_source("bootstrap.c");
    char *exp = read_native_source("exp.c");
    char *protocol = read_native_source("status_protocol.c");
    char *b = normalize_whitespace(bootstrap);
    char *e = normalize_whitespace(exp);
    char *p = normalize_whitespace(protocol);

    /* One bounded late-load budget in the producer, one bounded overall wait
     * in the consumer; both are passed into the shared protocol. */
    expect_contains(b, "#define LATE_LOAD_WAIT_MS 60000");
    expect_contains(b, "#define SOFT_REBOOT_WAIT_MS 5000");
    expect_contains(e, "#define DF_WAIT_BUDGET_MS 70000");
    expect_contains(e, "#define DF_WAIT_GRACE_MS 10000");
    expect_contains(e, "#define DF_WAIT_OVERALL_MS 85000");
    expect_contains(b, "return producer_decide_and_publish(&io, NULL, late_load_rc, /* soft_reboot */ 0, LATE_LOAD_WAIT_MS, SOFT_REBOOT_WAIT_MS);");
    expect_contains(e, "wait = status_flow_run(&io, pf, DF_WAIT_BUDGET_MS, DF_WAIT_GRACE_MS,");

    /* The single publication path and single rc mapping live in the master
     * protocol: an indeterminate attempt is rc7, never a definitive fallback. */
    expect_contains(p, "static enum df_outcome publish_or_none(");
    expect_contains(p, "case REBOOT_ATTEMPT_UNKNOWN: return DF_RC_ATTEMPT_UNKNOWN;");
    expect_contains(p, "case REBOOT_PENDING: return DF_RC_ATTEMPT_UNKNOWN;");
    expect_contains(e, "rc = df_outcome_to_rc(outcome);");
    expect_contains(b, "df_publish_property(token)");
    expect_contains(e, "df_read_property(buf, cap, out_len)");
    /* Progress markers stay informational; no /dev outcome channel or private
     * late-load deadline may be reintroduced. Master's kill/reap is bounded. */
    expect_contains(e, "kProgressMarkers[]");
    expect_contains(b, "#define SIGKILL_REAP_WAIT_MS 2000");
    expect_contains(b, "kill_and_reap_bounded(pid);");
    /* Match code, not the comments that explicitly say /dev/dffinal is unused. */
    if (strstr(bootstrap, "open(\"/dev/dffinal\"") != NULL
        || strstr(exp, "open(\"/dev/dffinal\"") != NULL
        || strstr(b, "LATE_LOAD_WAIT_LIMIT_MS") != NULL) {
        die("a private outcome channel or deadline was reintroduced");
    }
    free(bootstrap);
    free(exp);
    free(protocol);
    free(b);
    free(e);
    free(p);
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
    if (run_status_exit_code(&conflict) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("conflict did not map to indeterminate rc=7");
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
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
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
 * failure maps to rc=7 on the writer side and to the reader's authoritative
 * indeterminate channel (never success, never a clean absence). */
static void test_publication_failure_protocol(void)
{
    char case_root[PATH_MAX];
    if (snprintf(case_root, sizeof(case_root), "%s", root) < 0
        || strlen(case_root) >= sizeof(case_root)) {
        die("case root path is too long");
    }
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
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);

    /* The confirmed path still returns the intended code. */
    reset_root(case_root);
    configure(OUR_ID);
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
            /* Only a definitive non-7 code is actionable. rc7 is the honest
             * indeterminate outcome after the detected publication failure. */
            if (run_status_classify(&result) == 0
                && result.verdict != RUN_STATUS_VERDICT_NONE
                && result.code != RUN_STATUS_TERMINAL_INDETERMINATE) {
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
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
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
    /* The intended phase failed, so no confirmation phase ran: rc7 must win
     * without any confirmation. */
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
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
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
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
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
}

/* FIX VERIFICATION (was a reproduction of the R2-14 race). The writer pauses
 * immediately after the intended rc0 link(): the final marker is visible, but
 * there is NO matching confirmation and NO rc7. A gated reader MUST report no
 * decision (N), never success. A one-shot temp-unlink failure is then injected;
 * rc7 is published BEFORE retraction, and the reader reports indeterminate (R)
 * in the coexistence window and after retraction. Pipe handshakes only: no
 * sleeps, no scheduling luck, no privileged payload. */
static void test_post_link_early_observation(void)
{
    configure(OUR_ID);
    int gate[2], observed[2];
    int post_release[2], post_paused[2];
    int retract_release[2], retract_paused[2];
    int diagnostic[2];
    if (pipe(gate) != 0 || pipe(observed) != 0
        || pipe(post_release) != 0 || pipe(post_paused) != 0
        || pipe(retract_release) != 0 || pipe(retract_paused) != 0
        || pipe(diagnostic) != 0) {
        die("post-link early-observation pipes failed");
    }
    run_status_set_post_link_pause(post_release[0], post_paused[1]);
    run_status_set_retract_pause(retract_release[0], retract_paused[1]);

    pid_t writer = fork();
    if (writer < 0) die("post-link early-observation writer fork failed");
    if (writer == 0) {
        close(gate[0]); close(gate[1]);
        close(observed[0]); close(observed[1]);
        close(post_release[1]); close(post_paused[0]);
        close(retract_release[1]); close(retract_paused[0]);
        close(diagnostic[0]);
        /* One-shot EIO on the post-link temp unlink: a genuine post-link
         * failure whose first diagnostic must survive the rc=7 fallback. */
        run_status_inject_fault(RUN_STATUS_FAULT_UNLINK_TEMP);
        int rc = run_status_write(RUN_STATUS_TERMINAL_SUCCESS);
        const char *message = run_status_last_error();
        size_t length = message == NULL ? 0 : strlen(message);
        if (length > RUN_STATUS_DIAGNOSTIC_MAX) length = RUN_STATUS_DIAGNOSTIC_MAX;
        if (length > 0 && write(diagnostic[1], message, length) != (ssize_t)length) {
            _exit(2);
        }
        close(diagnostic[1]);
        _exit(rc == 0 ? 0 : RUN_STATUS_TERMINAL_INDETERMINATE);
    }

    pid_t reader = fork();
    if (reader < 0) die("post-link early-observation reader fork failed");
    if (reader == 0) {
        close(gate[1]);
        close(observed[0]);
        close(post_release[0]); close(post_release[1]);
        close(post_paused[0]); close(post_paused[1]);
        close(retract_release[0]); close(retract_release[1]);
        close(retract_paused[0]); close(retract_paused[1]);
        close(diagnostic[0]); close(diagnostic[1]);
        char command;
        for (;;) {
            ssize_t count = read(gate[0], &command, 1);
            if (count == 0) break;
            if (count != 1) _exit(2);
            struct run_status_result result;
            /* N = no decision (keep polling), S = confirmed success,
             * R = indeterminate. An unconfirmed intended marker must be N. */
            char verdict = 'N';
            if (run_status_classify(&result) == 0
                && result.verdict == RUN_STATUS_VERDICT_TERMINAL) {
                if (result.code == RUN_STATUS_TERMINAL_SUCCESS) verdict = 'S';
                if (result.code == RUN_STATUS_TERMINAL_INDETERMINATE) verdict = 'R';
            }
            if (write(observed[1], &verdict, 1) != 1) _exit(3);
        }
        _exit(0);
    }

    close(gate[0]); close(observed[1]);
    close(post_release[0]); close(post_paused[1]);
    close(retract_release[0]); close(retract_paused[1]);
    close(diagnostic[1]);

    char byte;
    if (read(post_paused[0], &byte, 1) != 1 || byte != 'p') {
        die("writer never reached the post-link pause");
    }

    /* Prove the gate has the intended marker only: rc7 and the matching
     * confirmation do not exist yet, and the temp link is still present. */
    char name[RUN_STATUS_MARKER_NAME_MAX];
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc0", OUR_ID) < 0
        || strlen(name) >= sizeof(name)) {
        die("intended marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    struct stat st;
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("intended rc0 marker was not visible at the post-link gate");
    }
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc7", OUR_ID) < 0
        || strlen(name) >= sizeof(name)) {
        die("indeterminate marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    if (lstat(marker_path, &st) == 0) {
        die("rc7 was published before the post-link gate");
    }
    if (errno != ENOENT) {
        perror("lstat early rc7 marker");
        exit(1);
    }
    int temp_count = 0;
    DIR *directory = opendir(root);
    if (directory == NULL) die("opendir at the post-link gate failed");
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (strstr(entry->d_name, "dfroot_tmp_") != NULL) temp_count++;
    }
    closedir(directory);
    if (temp_count != 1) die("post-link gate was not before temp unlink");
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);

    /* The visible intended marker is unconfirmed: no decision, no exit code,
     * and an honest diagnostic. This is the exact race R2-14 reported, now
     * handled conservatively instead of acted on as success. */
    struct run_status_result at_gate = classify();
    if (at_gate.verdict != RUN_STATUS_VERDICT_NONE
        || run_status_exit_code(&at_gate) != -1
        || at_gate.publication_confirmed != 0
        || strstr(at_gate.diagnostic, "not publication-confirmed") == NULL
        || strstr(at_gate.diagnostic, "completion unverified") == NULL) {
        fprintf(stderr, "unconfirmed post-link marker was acted on: verdict=%d code=%d "
                        "confirmed=%d diagnostic='%s'\n",
                (int)at_gate.verdict, at_gate.code, at_gate.publication_confirmed,
                at_gate.diagnostic);
        exit(1);
    }

    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "p", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("early-window reader handshake failed");
        }
        if (byte != 'N') {
            die("reader acted on an unconfirmed post-link marker");
        }
    }

    /* Release the gate: the injected temp-unlink EIO is detected, rc=7 is
     * published, and the writer pauses before retracting the rc0 link. */
    if (write(post_release[1], "g", 1) != 1) die("post-link release failed");
    if (read(retract_paused[0], &byte, 1) != 1 || byte != 'p') {
        die("writer never reached the post-failure retract pause");
    }
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc0", OUR_ID) < 0
        || strlen(name) >= sizeof(name)) {
        die("intended marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("intended rc0 marker was retracted before rc7 was published");
    }
    if (snprintf(name, sizeof(name), "dfroot_terminal_%s_rc7", OUR_ID) < 0
        || strlen(name) >= sizeof(name)) {
        die("indeterminate marker name is too long");
    }
    make_path(marker_path, sizeof(marker_path), name);
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("rc7 channel was not published before retraction");
    }
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    at_gate = classify();
    if (at_gate.verdict != RUN_STATUS_VERDICT_TERMINAL
        || at_gate.code != RUN_STATUS_TERMINAL_INDETERMINATE) {
        fprintf(stderr, "rc7 did not outrank coexisting rc0: verdict=%d code=%d\n",
                (int)at_gate.verdict, at_gate.code);
        exit(1);
    }
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "c", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("coexistence-window reader handshake failed");
        }
        if (byte != 'R') die("reader reported SUCCESS after rc7 was published");
    }

    if (write(retract_release[1], "g", 1) != 1) die("retract release failed");
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "a", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("post-retraction reader handshake failed");
        }
        if (byte != 'R') die("reader reported SUCCESS after retraction");
    }

    /* The first (unlink) diagnostic must survive the rc=7 fallback. */
    char message[RUN_STATUS_DIAGNOSTIC_MAX + 1];
    size_t length = 0;
    for (;;) {
        ssize_t count = read(diagnostic[0], message + length, sizeof(message) - 1 - length);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            perror("read writer diagnostic");
            exit(1);
        }
        if (count == 0) break;
        length += (size_t)count;
        if (length == sizeof(message) - 1) break;
    }
    message[length] = '\0';
    const char expected_error[] = "terminal marker unlink failed";
    if (strncmp(message, expected_error, sizeof(expected_error) - 1) != 0) {
        fprintf(stderr, "first post-link diagnostic was lost: '%s'\n", message);
        exit(1);
    }

    int status = 0;
    if (waitpid(writer, &status, 0) != writer || !WIFEXITED(status)
        || WEXITSTATUS(status) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("post-link early-observation writer did not exit rc=7");
    }
    close(gate[1]);
    if (waitpid(reader, &status, 0) != reader || !WIFEXITED(status)
        || WEXITSTATUS(status) != 0) {
        die("post-link early-observation reader fixture failed");
    }
    close(observed[0]);
    close(post_release[1]); close(post_paused[0]);
    close(retract_release[1]); close(retract_paused[0]);
    close(diagnostic[0]);
    run_status_set_post_link_pause(-1, -1);
    run_status_set_retract_pause(-1, -1);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
}

/* A normal publication is definitive only after BOTH markers are present.
 * No gate, no fault injection: the writer completes and the reader confirms. */
static void test_confirmed_publication_yields_success(void)
{
    configure(OUR_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_SUCCESS);

    char name[RUN_STATUS_MARKER_NAME_MAX];
    make_terminal_name(name, sizeof(name), OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    make_path(marker_path, sizeof(marker_path), name);
    struct stat st;
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("intended success marker was missing after a confirmed write");
    }
    expect_confirm_present(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);

    struct run_status_result result = classify();
    if (result.verdict != RUN_STATUS_VERDICT_TERMINAL
        || result.code != RUN_STATUS_TERMINAL_SUCCESS
        || result.publication_confirmed != 1
        || run_status_exit_code(&result) != RUN_STATUS_TERMINAL_SUCCESS) {
        fprintf(stderr, "confirmed success was not definitive: verdict=%d code=%d "
                        "confirmed=%d\n", (int)result.verdict, result.code,
                result.publication_confirmed);
        exit(1);
    }
    struct run_status_decision decision = decide_from_reader(&result);
    if (decision.outcome != RUN_STATUS_OUTCOME_TERMINAL
        || decision.code != RUN_STATUS_TERMINAL_SUCCESS) {
        die("confirmed success was not reported as a terminal decision");
    }
    if (run_status_publication_exit_code(0, RUN_STATUS_TERMINAL_SUCCESS)
        != RUN_STATUS_TERMINAL_SUCCESS) {
        die("confirmed writer did not exit with its intended rc0");
    }
    expect_no_temp_files();
}

/* A fault confined to the confirmation phase: the intended marker is linked
 * but no matching confirmation can ever appear. Every gated poll must show
 * no decision before rc7 is published and indeterminate afterwards. */
static void test_confirmation_failure_is_indeterminate(void)
{
    configure(OUR_ID);
    int gate[2], observed[2];
    int post_release[2], post_paused[2];
    int retract_release[2], retract_paused[2];
    int diagnostic[2];
    if (pipe(gate) != 0 || pipe(observed) != 0
        || pipe(post_release) != 0 || pipe(post_paused) != 0
        || pipe(retract_release) != 0 || pipe(retract_paused) != 0
        || pipe(diagnostic) != 0) {
        die("confirmation-failure pipes failed");
    }
    run_status_set_post_link_pause(post_release[0], post_paused[1]);
    run_status_set_retract_pause(retract_release[0], retract_paused[1]);

    pid_t writer = fork();
    if (writer < 0) die("confirmation-failure writer fork failed");
    if (writer == 0) {
        close(gate[0]); close(gate[1]);
        close(observed[0]); close(observed[1]);
        close(post_release[1]); close(post_paused[0]);
        close(retract_release[1]); close(retract_paused[0]);
        close(diagnostic[0]);
        run_status_inject_fault(RUN_STATUS_FAULT_CONFIRM);
        int rc = run_status_write(RUN_STATUS_TERMINAL_SUCCESS);
        const char *message = run_status_last_error();
        size_t length = message == NULL ? 0 : strlen(message);
        if (length > RUN_STATUS_DIAGNOSTIC_MAX) length = RUN_STATUS_DIAGNOSTIC_MAX;
        if (length > 0 && write(diagnostic[1], message, length) != (ssize_t)length) {
            _exit(2);
        }
        close(diagnostic[1]);
        _exit(rc == 0 ? 0 : RUN_STATUS_TERMINAL_INDETERMINATE);
    }

    pid_t reader = fork();
    if (reader < 0) die("confirmation-failure reader fork failed");
    if (reader == 0) {
        close(gate[1]);
        close(observed[0]);
        close(post_release[0]); close(post_release[1]);
        close(post_paused[0]); close(post_paused[1]);
        close(retract_release[0]); close(retract_release[1]);
        close(retract_paused[0]); close(retract_paused[1]);
        close(diagnostic[0]); close(diagnostic[1]);
        char command;
        for (;;) {
            ssize_t count = read(gate[0], &command, 1);
            if (count == 0) break;
            if (count != 1) _exit(2);
            struct run_status_result result;
            char verdict = 'N';
            if (run_status_classify(&result) == 0
                && result.verdict == RUN_STATUS_VERDICT_TERMINAL) {
                if (result.code == RUN_STATUS_TERMINAL_SUCCESS) verdict = 'S';
                if (result.code == RUN_STATUS_TERMINAL_INDETERMINATE) verdict = 'R';
            }
            if (write(observed[1], &verdict, 1) != 1) _exit(3);
        }
        _exit(0);
    }

    close(gate[0]); close(observed[1]);
    close(post_release[0]); close(post_paused[1]);
    close(retract_release[0]); close(retract_paused[1]);
    close(diagnostic[1]);

    char byte;
    if (read(post_paused[0], &byte, 1) != 1 || byte != 'p') {
        die("confirmation-failure writer never reached the post-link pause");
    }
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_none_with_diagnostic("not publication-confirmed");
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "p", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("confirmation-failure early reader handshake failed");
        }
        if (byte != 'N') die("reader reported success before confirmation was published");
    }

    if (write(post_release[1], "g", 1) != 1) die("post-link release failed");
    if (read(retract_paused[0], &byte, 1) != 1 || byte != 'p') {
        die("confirmation-failure writer never reached the retract pause");
    }
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "c", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("confirmation-failure coexistence reader handshake failed");
        }
        if (byte != 'R') die("reader reported success after confirmation failure");
    }
    if (write(retract_release[1], "g", 1) != 1) die("retract release failed");
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "a", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("confirmation-failure steady-state reader handshake failed");
        }
        if (byte != 'R') die("reader reported success in the rc7 steady state");
    }

    char message[RUN_STATUS_DIAGNOSTIC_MAX + 1];
    size_t length = 0;
    for (;;) {
        ssize_t count = read(diagnostic[0], message + length, sizeof(message) - 1 - length);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            perror("read confirmation-failure diagnostic");
            exit(1);
        }
        if (count == 0) break;
        length += (size_t)count;
        if (length == sizeof(message) - 1) break;
    }
    message[length] = '\0';
    const char expected_error[] = "confirmation marker write failed";
    if (strncmp(message, expected_error, sizeof(expected_error) - 1) != 0) {
        fprintf(stderr, "confirmation failure diagnostic was lost: '%s'\n", message);
        exit(1);
    }

    int status = 0;
    if (waitpid(writer, &status, 0) != writer || !WIFEXITED(status)
        || WEXITSTATUS(status) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("confirmation-failure writer did not exit rc=7");
    }
    close(gate[1]);
    if (waitpid(reader, &status, 0) != reader || !WIFEXITED(status)
        || WEXITSTATUS(status) != 0) {
        die("confirmation-failure reader fixture failed");
    }
    close(observed[0]);
    close(post_release[1]); close(post_paused[0]);
    close(retract_release[1]); close(retract_paused[0]);
    close(diagnostic[0]);
    run_status_set_post_link_pause(-1, -1);
    run_status_set_retract_pause(-1, -1);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
}

/* R2-14 residual-window boundary fixture — NOT a fix proof, NOT device safety.
 * Two observations are recorded under pipe gates (no sleeps and no luck):
 *  (1) while the writer is paused immediately AFTER the confirmation link()
 *      and BEFORE its temp unlink/fsync, a concurrent reader classifies a
 *      definitive-looking rc0 with publication_confirmed == 1. No failure has
 *      been detected and no rc7 marker exists yet.
 *  (2) after release, the transient post-confirm unlink fault is detected and
 *      rc7 is published before retraction; ONCE published AND observed, rc7
 *      outranks the earlier rc0 and any conflict.
 * Boundary: rc7 in (2) was produced only AFTER detection and CANNOT revoke the
 * result already consumed in (1). The R2-14 gate stays OPEN. */
static void test_r2_14_residual_confirm_window_boundary(void)
{
    configure(OUR_ID);
    int gate[2], observed[2];
    int confirm_release[2], confirm_paused[2];
    int retract_release[2], retract_paused[2];
    int diagnostic[2];
    if (pipe(gate) != 0 || pipe(observed) != 0
        || pipe(confirm_release) != 0 || pipe(confirm_paused) != 0
        || pipe(retract_release) != 0 || pipe(retract_paused) != 0
        || pipe(diagnostic) != 0) {
        die("residual-window boundary pipes failed");
    }
    run_status_set_confirm_post_link_pause(confirm_release[0], confirm_paused[1]);
    run_status_set_retract_pause(retract_release[0], retract_paused[1]);
    /* The existing transient unlink fault is armed for the confirmation phase
     * only; it is applied when the gate is released, after the first observation. */
    run_status_set_confirm_unlink_fault(1);

    pid_t writer = fork();
    if (writer < 0) die("residual-window boundary writer fork failed");
    if (writer == 0) {
        close(gate[0]); close(gate[1]);
        close(observed[0]); close(observed[1]);
        close(confirm_release[1]); close(confirm_paused[0]);
        close(retract_release[1]); close(retract_paused[0]);
        close(diagnostic[0]);
        /* The host-only gate arms a one-shot EIO on the post-confirm temp
         * unlink; no fault is active during phase 1 and none is active until
         * the residual observation has been recorded at the confirmation gate. */
        int rc = run_status_write(RUN_STATUS_TERMINAL_SUCCESS);
        const char *message = run_status_last_error();
        size_t length = message == NULL ? 0 : strlen(message);
        if (length > RUN_STATUS_DIAGNOSTIC_MAX) length = RUN_STATUS_DIAGNOSTIC_MAX;
        if (length > 0 && write(diagnostic[1], message, length) != (ssize_t)length) {
            _exit(2);
        }
        close(diagnostic[1]);
        _exit(rc == 0 ? 0 : RUN_STATUS_TERMINAL_INDETERMINATE);
    }

    pid_t reader = fork();
    if (reader < 0) die("residual-window boundary reader fork failed");
    if (reader == 0) {
        close(gate[1]);
        close(observed[0]);
        close(confirm_release[0]); close(confirm_release[1]);
        close(confirm_paused[0]); close(confirm_paused[1]);
        close(retract_release[0]); close(retract_release[1]);
        close(retract_paused[0]); close(retract_paused[1]);
        close(diagnostic[0]); close(diagnostic[1]);
        char command;
        for (;;) {
            ssize_t count = read(gate[0], &command, 1);
            if (count == 0) break;
            if (count != 1) _exit(2);
            struct run_status_result result;
            /* N = no decision, C = definitive non-7 with
             * publication_confirmed == 1, R = observed rc7, X = any other
             * classification (conflict, unconfirmed, or a wrong code). */
            char verdict = 'X';
            if (run_status_classify(&result) == 0) {
                if (result.verdict == RUN_STATUS_VERDICT_NONE) {
                    verdict = 'N';
                } else if (result.verdict == RUN_STATUS_VERDICT_TERMINAL) {
                    if (result.code == RUN_STATUS_TERMINAL_SUCCESS
                        && result.publication_confirmed == 1) {
                        verdict = 'C';
                    } else if (result.code == RUN_STATUS_TERMINAL_INDETERMINATE) {
                        verdict = 'R';
                    }
                }
            }
            if (write(observed[1], &verdict, 1) != 1) _exit(3);
        }
        _exit(0);
    }

    close(gate[0]); close(observed[1]);
    close(confirm_release[0]); close(confirm_paused[1]);
    close(retract_release[0]); close(retract_paused[1]);
    close(diagnostic[1]);

    char byte;
    if (read(confirm_paused[0], &byte, 1) != 1 || byte != 'p') {
        die("writer never reached the confirmation post-link pause");
    }

    /* RECORD 1: the confirmation link is visible but its temp unlink and
     * directory fsync have not run. Both markers exist, so classification is
     * definitive even though the publication has not been durability-checked. */
    expect_confirm_present(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    char name[RUN_STATUS_MARKER_NAME_MAX];
    make_terminal_name(name, sizeof(name), OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    make_path(marker_path, sizeof(marker_path), name);
    struct stat st;
    if (lstat(marker_path, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("intended marker missing at the confirmation post-link gate");
    }
    /* No failure detected yet: the corrective rc7 does not exist, so this
     * definitive-looking observation precedes any correction by construction. */
    make_terminal_name(name, sizeof(name), OUR_ID, RUN_STATUS_TERMINAL_INDETERMINATE);
    make_path(marker_path, sizeof(marker_path), name);
    if (lstat(marker_path, &st) == 0) {
        die("rc7 was published before the residual observation was consumed");
    }
    if (errno != ENOENT) {
        perror("lstat residual-window early rc7");
        exit(1);
    }
    struct run_status_result first = classify();
    if (first.verdict != RUN_STATUS_VERDICT_TERMINAL
        || first.code != RUN_STATUS_TERMINAL_SUCCESS
        || first.publication_confirmed != 1
        || run_status_exit_code(&first) != RUN_STATUS_TERMINAL_SUCCESS) {
        fprintf(stderr, "residual observation was not definitive-looking: "
                        "verdict=%d code=%d confirmed=%d\n",
                (int)first.verdict, first.code, first.publication_confirmed);
        exit(1);
    }
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "1", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("residual-window first-observation handshake failed");
        }
        if (byte != 'C') {
            fprintf(stderr, "reader did not record the definitive residual "
                            "observation: '%c'\n", byte);
            exit(1);
        }
    }

    /* RECORD 2: release the writer. The injected post-confirm unlink EIO is now
     * detected, rc7 is published first, and the writer pauses before retracting
     * either final link, so the rc7-over-rc0 precedence is deterministic. */
    if (write(confirm_release[1], "g", 1) != 1) {
        die("confirmation post-link release failed");
    }
    if (read(retract_paused[0], &byte, 1) != 1 || byte != 'p') {
        die("writer never reached the retract pause after the detected failure");
    }
    expect_confirm_present(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "2", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("residual-window correction handshake failed");
        }
        if (byte != 'R') die("observed rc7 did not outrank the earlier rc0");
    }

    if (write(retract_release[1], "g", 1) != 1) die("retract release failed");
    for (int i = 0; i < READER_POLLS; i++) {
        if (write(gate[1], "3", 1) != 1 || read(observed[0], &byte, 1) != 1) {
            die("residual-window steady-state handshake failed");
        }
        if (byte != 'R') die("reader did not keep rc7 in the steady state");
    }

    /* Both observations were recorded above. The first was consumed before
     * detection and cannot be revoked by the later corrective rc7. */
    char message[RUN_STATUS_DIAGNOSTIC_MAX + 1];
    size_t length = 0;
    for (;;) {
        ssize_t count = read(diagnostic[0], message + length, sizeof(message) - 1 - length);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            perror("read residual-window writer diagnostic");
            exit(1);
        }
        if (count == 0) break;
        length += (size_t)count;
        if (length == sizeof(message) - 1) break;
    }
    message[length] = '\0';
    const char expected_error[] = "confirmation marker unlink failed";
    if (strncmp(message, expected_error, sizeof(expected_error) - 1) != 0) {
        fprintf(stderr, "residual-window first diagnostic was lost: '%s'\n", message);
        exit(1);
    }

    int status = 0;
    if (waitpid(writer, &status, 0) != writer || !WIFEXITED(status)
        || WEXITSTATUS(status) != RUN_STATUS_TERMINAL_INDETERMINATE) {
        die("residual-window boundary writer did not exit rc=7");
    }
    close(gate[1]);
    if (waitpid(reader, &status, 0) != reader || !WIFEXITED(status)
        || WEXITSTATUS(status) != 0) {
        die("residual-window boundary reader fixture failed");
    }
    close(observed[0]);
    close(confirm_release[1]); close(confirm_paused[0]);
    close(retract_release[1]); close(retract_paused[0]);
    close(diagnostic[0]);
    run_status_set_confirm_post_link_pause(-1, -1);
    run_status_set_retract_pause(-1, -1);
    run_status_set_confirm_unlink_fault(0);
    expect_no_final(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_temp_files();
    expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
}

/* Crossed/missing/malformed confirmations must never turn any observation into
 * success. All fixtures are harmless hand-written files with no privileged
 * operation involved. */
static void test_unconfirmed_or_crossed_markers_never_confirm(void)
{
    char case_root[PATH_MAX];
    if (snprintf(case_root, sizeof(case_root), "%s", root) < 0
        || strlen(case_root) >= sizeof(case_root)) {
        die("case root path is too long");
    }

    /* 1. Intended rc0 with no confirmation: no decision at all. */
    reset_root(case_root);
    configure(OUR_ID);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_none_with_diagnostic("not publication-confirmed");

    /* 2. Confirmation with no intended marker: still no decision. */
    reset_root(case_root);
    configure(OUR_ID);
    write_confirm_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_none_with_diagnostic("has no terminal marker");

    /* 3. Two different confirmed codes: rc7, never definitive rc0/rc1. */
    reset_root(case_root);
    configure(OUR_ID);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    write_confirm_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    write_confirm_fixture(OUR_ID, RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    {
        struct run_status_result result = classify();
        if (result.verdict != RUN_STATUS_VERDICT_CONFLICT
            || result.code != RUN_STATUS_TERMINAL_INDETERMINATE
            || result.publication_confirmed != 0
            || run_status_exit_code(&result) != RUN_STATUS_TERMINAL_INDETERMINATE
            || strstr(result.diagnostic, "rc=0") == NULL
            || strstr(result.diagnostic, "rc=6") == NULL) {
            fprintf(stderr, "two confirmed codes did not map to rc7: verdict=%d code=%d\n",
                    (int)result.verdict, result.code);
            exit(1);
        }
    }

    /* 4. rc7 coexists with a confirmed rc0: rc7 remains authoritative. */
    reset_root(case_root);
    configure(OUR_ID);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_INDETERMINATE);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    write_confirm_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_indeterminate();

    /* 5. Crossed codes: a confirmed rc6 plus a stray rc0 confirmation, and
     *    an unconfirmed rc0 plus a confirmed rc6, are both indeterminate. */
    reset_root(case_root);
    configure(OUR_ID);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    write_confirm_fixture(OUR_ID, RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    write_confirm_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    expect_indeterminate();

    reset_root(case_root);
    configure(OUR_ID);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    write_confirm_fixture(OUR_ID, RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    expect_indeterminate();

    /* 6. A confirmation whose payload names another run, or whose magic is a
     *    terminal payload, cannot confirm our intended marker. */
    reset_root(case_root);
    configure(OUR_ID);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    {
        char foreign_payload[RUN_STATUS_PAYLOAD_MAX];
        make_confirm_payload(foreign_payload, sizeof(foreign_payload), FOREIGN_ID,
                             RUN_STATUS_TERMINAL_SUCCESS);
        char name[RUN_STATUS_MARKER_NAME_MAX];
        make_confirm_name(name, sizeof(name), OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
        write_named_marker(name, foreign_payload);
    }
    expect_none_with_diagnostic("not publication-confirmed");

    reset_root(case_root);
    configure(OUR_ID);
    write_terminal_fixture(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
    {
        char terminal_payload[RUN_STATUS_PAYLOAD_MAX];
        make_test_payload(terminal_payload, sizeof(terminal_payload), OUR_ID,
                          RUN_STATUS_TERMINAL_SUCCESS);
        char name[RUN_STATUS_MARKER_NAME_MAX];
        make_confirm_name(name, sizeof(name), OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
        write_named_marker(name, terminal_payload);
    }
    expect_none_with_diagnostic("not publication-confirmed");
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
    char *protocol = read_native_source("status_protocol.c");
    char *exp = read_native_source("exp.c");
    char *b = normalize_whitespace(bootstrap);
    char *p = normalize_whitespace(protocol);
    char *e = normalize_whitespace(exp);

    /* The producer's poll is bounded by the deadline bootstrap passes into
     * the single protocol; the reader charges cleanup to the SAME absolute
     * deadline. */
    expect_contains(b, "#define LATE_LOAD_WAIT_MS 60000");
    expect_contains(b, "return producer_decide_and_publish(&io, NULL, late_load_rc, /* soft_reboot */ 0, LATE_LOAD_WAIT_MS, SOFT_REBOOT_WAIT_MS);");
    expect_contains(p, "deadline = deadline_after(start, late_load_wait_ms);");
    expect_contains(p, "if (now >= deadline) {");
    expect_contains(e, "wait = status_flow_run(&io, pf, DF_WAIT_BUDGET_MS, DF_WAIT_GRACE_MS,");
    /* Expiry of a pending attempt is indeterminate rc7; a continuing wait
     * publishes no terminal outcome. No phase may get a fresh full budget. */
    expect_contains(p, "io->observed_outcome = REBOOT_ATTEMPT_UNKNOWN;");
    expect_contains(p, "case REBOOT_ATTEMPT_UNKNOWN: return DF_RC_ATTEMPT_UNKNOWN;");
    if (strstr(p, "deadline = deadline_after(now, late_load_wait_ms);") != NULL) {
        die("late-load poll reintroduced a fresh full budget");
    }
    free(bootstrap);
    free(protocol);
    free(exp);
    free(b);
    free(p);
    free(e);
}

/* Cross-file guard for the timeout sites under master's single protocol: the
 * late-load command is bounded, an unobserved exit is indeterminate before any
 * definitive retract, and every timeout exits rc7 through the shared mapping. */
static void test_timeout_indeterminate_wiring(void)
{
    if (source_dir == NULL) die("native source directory is required");
    char *bootstrap = read_native_source("bootstrap.c");
    char *protocol = read_native_source("status_protocol.c");
    char *exp = read_native_source("exp.c");
    char *b = normalize_whitespace(bootstrap);
    char *p = normalize_whitespace(protocol);
    char *e = normalize_whitespace(exp);

    /* The late-load command polls WNOHANG against a bounded deadline; a clock
     * failure or expiry marks the exit unobserved so bootstrap publishes rc7. */
    expect_contains(b, "if (now < 0 || now - start >= RUN_CTX_WAIT_MS) {");
    expect_contains(b, "if (unobserved != NULL) *unobserved = 1;");
    expect_contains(b, "return publish_indeterminate_before_fallback(nonce);");
    /* Every producer decision goes through ONE protocol. Tri-state late-load
     * state and an expired deadline can only SKIP the reboot (fail closed). */
    expect_contains(b, "return producer_decide_and_publish(&io, NULL, late_load_rc, /* soft_reboot */ 0, LATE_LOAD_WAIT_MS, SOFT_REBOOT_WAIT_MS);");
    expect_contains(b, "io.late_load_state = io_late_load_state;");
    expect_contains(b, "return late_load_running();");
    expect_contains(p, "deadline = deadline_after(start, late_load_wait_ms);");
    expect_contains(p, "if (st < 0) {");
    expect_contains(p, "return publish_or_none(io, ctx, REBOOT_SKIPPED);");
    /* The bounded SIGKILL/reap loop never blocks forever on a stuck child. */
    expect_contains(b, "#define SIGKILL_REAP_WAIT_MS 2000");
    expect_contains(b, "kill_and_reap_bounded(pid);");
    /* All timeout exits map to indeterminate rc7 through the shared mapping. */
    expect_contains(p, "io->observed_outcome = REBOOT_ATTEMPT_UNKNOWN;");
    expect_contains(p, "case REBOOT_ATTEMPT_UNKNOWN: return DF_RC_ATTEMPT_UNKNOWN;");
    expect_contains(e, "if (wait == ERR) {");
    expect_contains(e, "rc = DF_RC_ATTEMPT_UNKNOWN;");
    expect_contains(e, "if (outcome == DF_OUTCOME_NONE) {");
    free(bootstrap);
    free(protocol);
    free(exp);
    free(b);
    free(p);
    free(e);
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
    char *protocol = read_native_source("status_protocol.c");
    char *e = normalize_whitespace(exp);
    char *p = normalize_whitespace(protocol);

    /* The reader's bounded wait runs in the master protocol. A pending
     * preflight that never refines is REBOOT_ATTEMPT_UNKNOWN (rc7); an ERR or
     * a missing bound observation is also rc7. Only setup() failures are rc2. */
    expect_contains(e, "wait = status_flow_run(&io, pf, DF_WAIT_BUDGET_MS, DF_WAIT_GRACE_MS,");
    expect_contains(e, "if (wait == ERR) {");
    expect_contains(e, "printf(\"status: indeterminate rc=%d (recovery UNKNOWN); no reboot authorized\\n\", rc);");
    expect_contains(e, "if (outcome == DF_OUTCOME_NONE) {");
    expect_contains(e, "printf(\"status: no bound final observation; indeterminate rc=%d\\n\", rc);");
    expect_contains(e, "if (setup(argc, argv) != 0) return 2;");
    expect_contains(p, "io->observed_outcome = REBOOT_ATTEMPT_UNKNOWN;");
    expect_contains(p, "case REBOOT_ATTEMPT_UNKNOWN: return DF_RC_ATTEMPT_UNKNOWN;");
    if (strstr(exp, "rc = DF_RC_ERROR;") != NULL
        || strstr(exp, "timeout: ERROR - check logcat & dmesg") != NULL) {
        die("reader timeout still reports a definitive failure");
    }
    free(exp);
    free(protocol);
    free(e);
    free(p);
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
    char *protocol = read_native_source("status_protocol.c");
    char *b = normalize_whitespace(bootstrap);
    char *p = normalize_whitespace(protocol);

    /* ONE bounded deadline for the late-load scan; the recovery-gate window
     * is clamped to its remainder, and the consumer's grace window is clamped
     * to the overall deadline. No phase receives a fresh full budget. */
    expect_contains(p, "deadline = deadline_after(start, late_load_wait_ms);");
    expect_contains(p, "int64_t window = deadline_after(now, DF_RECOVERY_WAIT_MS);");
    expect_contains(p, "int64_t recovery_deadline = (window < deadline) ? window : deadline;");
    expect_contains(p, "overall_deadline = deadline_after(start, overall_ms);");
    expect_contains(p, "deadline = (g < overall_deadline) ? g : overall_deadline;");
    expect_contains(b, "return producer_decide_and_publish(&io, NULL, late_load_rc, /* soft_reboot */ 0, LATE_LOAD_WAIT_MS, SOFT_REBOOT_WAIT_MS);");
    if (strstr(p, "deadline = deadline_after(now, late_load_wait_ms);") != NULL) {
        die("late-load wait reintroduced a fresh full budget");
    }
    free(bootstrap);
    free(protocol);
    free(b);
    free(p);
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
    char *protocol = read_native_source("status_protocol.c");
    char *b = normalize_whitespace(bootstrap);
    char *p = normalize_whitespace(protocol);

    /* A failed fork() starts no child: both launchers return -1 WITHOUT
     * marking the exit unobserved, so an ordinary ERROR/REBOOT_FAILED is
     * published. Only a timeout or unobserved exit can be indeterminate. */
    expect_contains(b, "if (unobserved != NULL) *unobserved = 0;");
    expect_contains(b, "pid = fork(); if (pid < 0) return -1;");
    expect_contains(p, "if (late_load_rc != 0) {");
    expect_contains(p, "return publish_or_none(io, ctx, ERROR);");
    expect_contains(p, "child_rc = io->wait_child(ctx, child_wait_ms);");
    expect_contains(p, "refined = (child_rc == 0) ? REBOOT_REQUESTED : REBOOT_FAILED;");
    expect_contains(p, "case ERROR: return DF_RC_ERROR;");
    expect_contains(p, "case REBOOT_FAILED: return DF_RC_REBOOT_FAILED;");
    free(bootstrap);
    free(protocol);
    free(b);
    free(p);
}

/* End-to-end protocol case: bootstrap publishes its reserved rc=1 diagnostic
 * before an admitted snapshot exists; a reader for a DIFFERENT operation ID
 * must report explicit uncertainty (rc7), never rc1 or rc0. */
static void test_pre_admission_unconfirmed(void)
{
    publish_pre_admission_failure();
    if (!run_status_pre_admission_failure()) {
        die("valid reserved pre-admission marker was not detected");
    }
    struct run_status_result reserved = classify();
    if (reserved.verdict != RUN_STATUS_VERDICT_TERMINAL
        || reserved.code != RUN_STATUS_TERMINAL_ERROR) {
        die("reserved diagnostic was not a valid marker for the reserved identity");
    }

    /* A different operation is now admitted: the reserved marker is foreign. */
    configure(OUR_ID);
    struct run_status_result admitted = classify();
    if (admitted.verdict != RUN_STATUS_VERDICT_NONE
        || run_status_exit_code(&admitted) != -1) {
        die("reserved marker was accepted as a terminal result for the admitted operation");
    }
    struct run_status_decision decision = decide_from_reader(&admitted);
    if (decision.outcome != RUN_STATUS_OUTCOME_UNCONFIRMED
        || decision.code != RUN_STATUS_TERMINAL_INDETERMINATE
        || strcmp(decision.diagnostic,
                  "pre-admission failure; completion unverified") != 0) {
        fprintf(stderr,
                "pre-admission diagnostic did not yield explicit indeterminate: "
                "outcome=%d code=%d diagnostic='%s'\n",
                (int)decision.outcome, decision.code, decision.diagnostic);
        exit(1);
    }
    if (decision.code == RUN_STATUS_TERMINAL_SUCCESS
        || decision.code == RUN_STATUS_TERMINAL_ERROR) {
        die("reserved rc=1 was mapped to a terminal current-operation code");
    }

    /* The pure helper alone, given the same flag, must produce the same result. */
    struct run_status_decision direct;
    run_status_decide(&admitted, 1, &direct);
    if (direct.outcome != decision.outcome || direct.code != decision.code
        || strcmp(direct.diagnostic, decision.diagnostic) != 0) {
        die("pure decision differed from the reader path");
    }

    /* Cross-file wiring under the single master protocol: a failure before a
     * run identity exists is published on the RESERVED-nonce channel, which
     * the reader accepts only as an UNBOUND observation. With no bound final
     * token the result stays indeterminate rc7, never rc0/rc1 of this run. */
    if (source_dir == NULL) die("native source directory is required");
    char *bootstrap = read_native_source("bootstrap.c");
    char *protocol = read_native_source("status_protocol.c");
    char *exp = read_native_source("exp.c");
    char *b = normalize_whitespace(bootstrap);
    char *p = normalize_whitespace(protocol);
    char *e = normalize_whitespace(exp);
    expect_contains(b, "if (read_nonce(nonce) != 0) {");
    expect_contains(b, "df_format_final_token(token, sizeof(token), DF_RESERVED_BOOTSTRAP_NONCE, BOOTSTRAP_FAILED)");
    expect_contains(b, "publish_bootstrap_failure_bounded()");
    expect_contains(b, "if (io_publish_final(NULL, nonce, REBOOT_ATTEMPT_UNKNOWN) != 0) {");
    expect_contains(b, "return DF_RC_ATTEMPT_UNKNOWN;");
    expect_contains(p, "int df_parse_bootstrap_failure_token(");
    expect_contains(p, "if (!final_parsed && io->read_bootstrap_failure) {");
    expect_contains(p, "cleanup_and_recheck(io, ctx, cleanup_cb, 0, cleanup_done, cleanup_failed);");
    expect_contains(e, "io.read_bootstrap_failure = exp_read_bootstrap_failure;");
    expect_contains(e, "status = (bound_observation == 1 && !pf->cleanup_failed) ? \"ok\\n\" : \"fail\\n\";");
    expect_contains(e, "if (outcome == DF_OUTCOME_NONE) {");
    expect_contains(e, "rc = DF_RC_ATTEMPT_UNKNOWN;");
    free(bootstrap);
    free(protocol);
    free(exp);
    free(b);
    free(p);
    free(e);
}

/* Any valid terminal marker for the admitted operation wins over the stale
 * reserved diagnostic, including rc0 success and CONFLICT (rc7 indeterminate). */
static void test_pre_admission_terminal_wins(void)
{
    /* Save the case root before any reset: reset_root() rewrites the global. */
    char case_root[PATH_MAX];
    if (snprintf(case_root, sizeof(case_root), "%s", root) < 0
        || strlen(case_root) >= sizeof(case_root)) {
        die("case root path is too long");
    }
    const int codes[] = {
        RUN_STATUS_TERMINAL_SUCCESS,
        RUN_STATUS_TERMINAL_ERROR,
        RUN_STATUS_TERMINAL_SOFT_REBOOT_OK,
        RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL,
        RUN_STATUS_TERMINAL_INDETERMINATE
    };
    for (size_t i = 0; i < sizeof(codes) / sizeof(codes[0]); i++) {
        reset_root(case_root);
        publish_pre_admission_failure();
        configure(OUR_ID);
        expect_write_ok(codes[i]);

        struct run_status_result admitted = classify();
        if (admitted.verdict != RUN_STATUS_VERDICT_TERMINAL
            || admitted.code != codes[i]) {
            fprintf(stderr, "admitted rc=%d was not authoritative\n", codes[i]);
            exit(1);
        }
        if (!run_status_pre_admission_failure()) {
            die("the stale reserved diagnostic disappeared");
        }
        struct run_status_decision decision = decide_from_reader(&admitted);
        if (decision.outcome != RUN_STATUS_OUTCOME_TERMINAL
            || decision.code != codes[i]
            || strcmp(decision.diagnostic, admitted.diagnostic) != 0) {
            fprintf(stderr,
                    "stale pre-admission rc=1 downgraded admitted rc=%d: "
                    "outcome=%d code=%d diagnostic='%s'\n",
                    codes[i], (int)decision.outcome, decision.code,
                    decision.diagnostic);
            exit(1);
        }
    }

    /* A conflict for the admitted operation is indeterminate rc=7 from the
     * conflict itself, and must keep its conflict diagnostic. */
    reset_root(case_root);
    publish_pre_admission_failure();
    configure(OUR_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_SUCCESS);
    expect_write_ok(RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL);
    struct run_status_result admitted = classify();
    if (admitted.verdict != RUN_STATUS_VERDICT_CONFLICT) {
        die("admitted conflict fixture was not classified as a conflict");
    }
    struct run_status_decision decision = decide_from_reader(&admitted);
    if (decision.outcome != RUN_STATUS_OUTCOME_TERMINAL
        || decision.code != RUN_STATUS_TERMINAL_INDETERMINATE
        || strstr(decision.diagnostic, "conflicting") == NULL) {
        die("stale pre-admission marker replaced an admitted conflict result");
    }
}

/* Whatever the reserved identity's marker looks like, unless it is a valid
 * final-path regular-file rc=1 payload for the reserved identity itself, it
 * is ignored: no pre-admission flag, no non-NONE decision. */
static void expect_pre_admission_ignored(void)
{
    configure(OUR_ID);
    struct run_status_result admitted = classify();
    if (admitted.verdict != RUN_STATUS_VERDICT_NONE) {
        die("ignored fixture became a terminal marker for the admitted operation");
    }
    if (run_status_pre_admission_failure()) {
        die("invalid reserved marker was accepted as a pre-admission failure");
    }
    struct run_status_decision decision = decide_from_reader(&admitted);
    if (decision.outcome != RUN_STATUS_OUTCOME_NONE || decision.code != -1) {
        fprintf(stderr, "ignored fixture produced a decision: outcome=%d code=%d\n",
                (int)decision.outcome, decision.code);
        exit(1);
    }
}

static void test_pre_admission_ignored_markers(void)
{
    /* reset_root() rewrites the global root buffer, so keep a stable copy. */
    char case_root[PATH_MAX];
    if (snprintf(case_root, sizeof(case_root), "%s", root) < 0
        || strlen(case_root) >= sizeof(case_root)) {
        die("case root path is too long");
    }
    const char *final_name = "dfroot_terminal_bootstrap-pre-admission_rc1";
    char payload[RUN_STATUS_PAYLOAD_MAX];
    char path[PATH_MAX];

    /* Well-formed payload for the reserved identity, reused below. */
    make_test_payload(payload, sizeof(payload), RUN_STATUS_PRE_ADMISSION_ID,
                      RUN_STATUS_TERMINAL_ERROR);

    reset_root(case_root);
    write_named_marker(final_name, "not a terminal marker\n");
    expect_pre_admission_ignored();

    reset_root(case_root);
    write_named_marker(final_name, "");
    expect_pre_admission_ignored();

    reset_root(case_root);
    write_named_marker(final_name,
                       "DFROOT_TERMINAL 1\noperation_id=bootstrap-pre-admission\ncode=0\n");
    expect_pre_admission_ignored();

    reset_root(case_root);
    make_test_payload(payload, sizeof(payload), FOREIGN_ID,
                      RUN_STATUS_TERMINAL_ERROR);
    write_named_marker(final_name, payload);
    expect_pre_admission_ignored();

    reset_root(case_root);
    make_test_payload(payload, sizeof(payload), RUN_STATUS_PRE_ADMISSION_ID,
                      RUN_STATUS_TERMINAL_ERROR);
    write_named_marker("dfroot_tmp_bootstrap-pre-admission_rc1_deadbeef", payload);
    expect_pre_admission_ignored();

    reset_root(case_root);
    write_named_marker("dfroot_terminal_bootstrap-pre-admission-old_rc1", payload);
    expect_pre_admission_ignored();

    /* symlink at the exact final path: O_NOFOLLOW must reject it */
    reset_root(case_root);
    make_path(path, sizeof(path), "target");
    write_text(path, payload);
    make_path(path, sizeof(path), final_name);
    if (symlink("target", path) != 0) die("pre-admission symlink fixture failed");
    expect_pre_admission_ignored();

    /* FIFO at the exact final path: non-regular, and must not block the reader */
    reset_root(case_root);
    make_path(path, sizeof(path), final_name);
    if (mkfifo(path, 0600) != 0) die("pre-admission FIFO fixture failed");
    expect_pre_admission_ignored();
}

/* Pure helper branch coverage: no fixtures, no filesystem state, no sleeps. */
static void test_pre_admission_decide_pure(void)
{
    struct run_status_result admitted;
    struct run_status_decision decision;
    memset(&admitted, 0, sizeof(admitted));
    admitted.verdict = RUN_STATUS_VERDICT_NONE;
    admitted.code = -1;
    snprintf(admitted.diagnostic, sizeof(admitted.diagnostic),
             "no terminal marker for operation %s", OUR_ID);

    run_status_decide(&admitted, 0, &decision);
    if (decision.outcome != RUN_STATUS_OUTCOME_NONE || decision.code != -1
        || decision.diagnostic[0] == '\0') {
        die("NONE without a pre-admission diagnostic was not NONE");
    }
    struct run_status_decision repeat;
    run_status_decide(&admitted, 0, &repeat);
    if (repeat.outcome != decision.outcome || repeat.code != decision.code
        || strcmp(repeat.diagnostic, decision.diagnostic) != 0) {
        die("pure decision was not repeatable");
    }

    run_status_decide(&admitted, 1, &decision);
    if (decision.outcome != RUN_STATUS_OUTCOME_UNCONFIRMED
        || decision.code != RUN_STATUS_TERMINAL_INDETERMINATE
        || strcmp(decision.diagnostic,
                  "pre-admission failure; completion unverified") != 0) {
        die("NONE with a pre-admission diagnostic was not explicit indeterminate");
    }

    /* Purity: changing the configured global identity cannot change a result
     * computed from the same explicit inputs. */
    configure(FOREIGN_ID);
    struct run_status_decision unrelated;
    run_status_decide(&admitted, 1, &unrelated);
    if (unrelated.outcome != decision.outcome || unrelated.code != decision.code
        || strcmp(unrelated.diagnostic, decision.diagnostic) != 0) {
        die("pure decision depended on the configured identity");
    }

    const int codes[] = {
        RUN_STATUS_TERMINAL_SUCCESS,
        RUN_STATUS_TERMINAL_ERROR,
        RUN_STATUS_TERMINAL_SOFT_REBOOT_OK,
        RUN_STATUS_TERMINAL_SOFT_REBOOT_FAIL,
        RUN_STATUS_TERMINAL_INDETERMINATE
    };
    for (size_t i = 0; i < sizeof(codes) / sizeof(codes[0]); i++) {
        admitted.verdict = RUN_STATUS_VERDICT_TERMINAL;
        admitted.code = codes[i];
        admitted.publication_confirmed =
            codes[i] == RUN_STATUS_TERMINAL_INDETERMINATE ? 0 : 1;
        snprintf(admitted.diagnostic, sizeof(admitted.diagnostic),
                 "terminal rc=%d for operation %s", codes[i], OUR_ID);
        run_status_decide(&admitted, 1, &decision);
        if (decision.outcome != RUN_STATUS_OUTCOME_TERMINAL
            || decision.code != codes[i]
            || strcmp(decision.diagnostic, admitted.diagnostic) != 0) {
            die("admitted terminal code lost to the pre-admission diagnostic");
        }
        run_status_decide(&admitted, 0, &repeat);
        if (repeat.outcome != decision.outcome || repeat.code != decision.code
            || strcmp(repeat.diagnostic, decision.diagnostic) != 0) {
            die("pre-admission flag changed an admitted terminal decision");
        }
    }

    admitted.verdict = RUN_STATUS_VERDICT_CONFLICT;
    admitted.code = RUN_STATUS_TERMINAL_INDETERMINATE;
    snprintf(admitted.diagnostic, sizeof(admitted.diagnostic),
             "conflicting terminal markers; completion unverified for operation %s: rc=0 rc=6",
             OUR_ID);
    run_status_decide(&admitted, 1, &decision);
    if (decision.outcome != RUN_STATUS_OUTCOME_TERMINAL
        || decision.code != RUN_STATUS_TERMINAL_INDETERMINATE
        || strcmp(decision.diagnostic, admitted.diagnostic) != 0) {
        die("admitted conflict was not preserved as indeterminate rc=7");
    }

    run_status_decide(NULL, 1, &decision);
    if (decision.outcome != RUN_STATUS_OUTCOME_NONE || decision.code != -1
        || decision.diagnostic[0] == '\0') {
        die("missing admitted classification was not handled safely");
    }
    run_status_decide(&admitted, 1, NULL);  /* NULL output must not crash */
}

/* An observation failure is not evidence of a safe terminal state. The host
 * seam makes classification fail even though a confirmed rc0 marker exists;
 * the shared failure mapping must still be rc7 with no confirmed result. */
static void test_classifier_failure_indeterminate(void)
{
    configure(OUR_ID);
    expect_write_ok(RUN_STATUS_TERMINAL_SUCCESS);

    run_status_inject_classifier_failure(1);
    struct run_status_result result;
    memset(&result, 0xa5, sizeof(result));
    if (run_status_classify(&result) == 0) {
        die("injected classifier failure was reported as a successful observation");
    }
    if (result.verdict != RUN_STATUS_VERDICT_NONE || result.code != -1
        || run_status_exit_code(&result) != -1) {
        fprintf(stderr, "classifier failure produced a confirmed result: verdict=%d code=%d\n",
                (int)result.verdict, result.code);
        exit(1);
    }
    if (strstr(result.diagnostic, "completion unverified") == NULL) {
        fprintf(stderr, "classifier failure diagnostic lacked 'completion unverified': '%s'\n",
                result.diagnostic);
        exit(1);
    }
    int exit_code = run_status_classifier_error_code();
    if (exit_code != RUN_STATUS_TERMINAL_INDETERMINATE
        || exit_code == RUN_STATUS_TERMINAL_SUCCESS
        || exit_code == RUN_STATUS_TERMINAL_ERROR) {
        fprintf(stderr, "classifier failure mapped to definitive rc=%d\n", exit_code);
        exit(1);
    }
    run_status_inject_classifier_failure(0);

    /* With the fault removed the unchanged marker is still the only confirmed
     * result: the injected failure never published or retracted anything. */
    expect_terminal(RUN_STATUS_TERMINAL_SUCCESS);

    if (source_dir == NULL) die("native source directory is required");
    char *exp = read_native_source("exp.c");
    char *protocol = read_native_source("status_protocol.c");
    char *e = normalize_whitespace(exp);
    char *p = normalize_whitespace(protocol);

    /* A failed observation in master's single protocol (ERR / strict parse
     * failure) leaves NO bound outcome and forces an UNBOUND cleanup. The
     * reader reports indeterminate rc7 instead of substituting rc0/rc1. */
    expect_contains(p, "if (r == ERR) {");
    expect_contains(p, "cleanup_cb(ctx, 0);");
    expect_contains(p, "if (cleanup_failed) *cleanup_failed = 1;");
    expect_contains(p, "if (out) *out = DF_OUTCOME_NONE;");
    expect_contains(p, "case REBOOT_ATTEMPT_UNKNOWN: return DF_RC_ATTEMPT_UNKNOWN;");
    expect_contains(e, "wait = status_flow_run(&io, pf, DF_WAIT_BUDGET_MS, DF_WAIT_GRACE_MS,");
    expect_contains(e, "if (wait == ERR) {");
    expect_contains(e, "printf(\"status: indeterminate rc=%d (recovery UNKNOWN); no reboot authorized\\n\", rc);");
    expect_contains(e, "rc = DF_RC_ATTEMPT_UNKNOWN;");
    expect_contains(e, "if (cleanup(pf) < 0) {");
    expect_contains(e, "status = (bound_observation == 1 && !pf->cleanup_failed) ? \"ok\\n\" : \"fail\\n\";");
    if (strstr(exp, "rc = DF_RC_ERROR;") != NULL
        || strstr(exp, "rc = DF_RC_SUCCESS;") != NULL) {
        die("an observed failure still exits a definitive code");
    }
    free(exp);
    free(protocol);
    free(e);
    free(p);
}

/* Pure, deterministic state-machine table for the R2-14 boundary. Harmless
 * hand-written marker fixtures only: no writer hooks, no fault injection, no
 * device. The safety column is explicit so this table cannot be misread as
 * closing the OPEN gate. */
struct publication_state_row {
    const char *state;
    int intended_code;      /* -1 = no intended marker */
    int confirm_code;       /* -1 = no confirmation marker */
    enum run_status_verdict expected_verdict;
    int expected_code;      /* -1 for NONE */
    int expected_confirmed;
    const char *safety;     /* exactly "proved" or "not proved" */
};

static void test_publication_state_machine_boundary(void)
{
    static const struct publication_state_row rows[] = {
        { "unconfirmed", RUN_STATUS_TERMINAL_SUCCESS, -1,
          RUN_STATUS_VERDICT_NONE, -1, 0, "proved" },
        { "confirmed", RUN_STATUS_TERMINAL_SUCCESS, RUN_STATUS_TERMINAL_SUCCESS,
          RUN_STATUS_VERDICT_TERMINAL, RUN_STATUS_TERMINAL_SUCCESS, 1, "proved" },
        { "detected-failure", RUN_STATUS_TERMINAL_INDETERMINATE, -1,
          RUN_STATUS_VERDICT_TERMINAL, RUN_STATUS_TERMINAL_INDETERMINATE,
          0, "proved" },
        /* Abnormal death after the confirmation link() but before its temp
         * unlink/fsync presents exactly the files of a confirmed run. The
         * reader cannot tell them apart, so this state is NOT proved safe. */
        { "abnormal-death", RUN_STATUS_TERMINAL_SUCCESS, RUN_STATUS_TERMINAL_SUCCESS,
          RUN_STATUS_VERDICT_TERMINAL, RUN_STATUS_TERMINAL_SUCCESS, 1,
          "not proved" }
    };
    configure(OUR_ID);
    /* reset_root writes back into its argument, so pass a copy of root rather
     * than aliasing the global buffer it reads from. */
    char case_root[PATH_MAX];
    if (snprintf(case_root, sizeof(case_root), "%s", root) < 0
        || strlen(case_root) >= sizeof(case_root)) {
        die("state-machine fixture root is too long");
    }
    int not_proved_rows = 0;
    for (size_t i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        reset_root(case_root);
        if (rows[i].intended_code >= 0) {
            write_terminal_fixture(OUR_ID, rows[i].intended_code);
        }
        if (rows[i].confirm_code >= 0) {
            write_confirm_fixture(OUR_ID, rows[i].confirm_code);
        }
        struct run_status_result result = classify();
        if (result.verdict != rows[i].expected_verdict
            || result.code != rows[i].expected_code
            || result.publication_confirmed != rows[i].expected_confirmed) {
            fprintf(stderr, "state '%s' observation mismatch: verdict=%d code=%d "
                            "confirmed=%d (expected %d/%d/%d)\n",
                    rows[i].state, (int)result.verdict, result.code,
                    result.publication_confirmed, (int)rows[i].expected_verdict,
                    rows[i].expected_code, rows[i].expected_confirmed);
            exit(1);
        }
        int expected_exit = rows[i].expected_verdict == RUN_STATUS_VERDICT_NONE
                            ? -1 : rows[i].expected_code;
        if (run_status_exit_code(&result) != expected_exit) {
            fprintf(stderr, "state '%s' mapped to the wrong exit code\n", rows[i].state);
            exit(1);
        }
        if (!strcmp(rows[i].safety, "not proved")) {
            not_proved_rows++;
            if (strcmp(rows[i].state, "abnormal-death") != 0) {
                die("only abnormal-death may be marked not proved");
            }
        } else if (strcmp(rows[i].safety, "proved")) {
            die("state-machine safety column must be explicit");
        }
    }
    if (not_proved_rows != 1) {
        die("state-machine table must record exactly one not-proved state");
    }
    /* A confirmed run and an abnormal death after the confirmation link are
     * observationally identical; that is precisely why classification cannot
     * close the residual window and why R2-14 stays OPEN. */
    if (rows[1].expected_verdict != rows[3].expected_verdict
        || rows[1].expected_code != rows[3].expected_code
        || rows[1].expected_confirmed != rows[3].expected_confirmed) {
        die("abnormal-death must be indistinguishable from a confirmed result");
    }
    reset_root(case_root);
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
        expect_no_confirm(OUR_ID, RUN_STATUS_TERMINAL_SUCCESS);
        /* The link failure publishes rc7; the pre-existing garbage marker is
         * ignored and never becomes a definitive result. */
        expect_terminal(RUN_STATUS_TERMINAL_INDETERMINATE);
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
    } else if (!strcmp(name, "post_link_early_observation")) {
        test_post_link_early_observation();
    } else if (!strcmp(name, "confirmed_publication_yields_success")) {
        test_confirmed_publication_yields_success();
    } else if (!strcmp(name, "confirmation_failure_is_indeterminate")) {
        test_confirmation_failure_is_indeterminate();
    } else if (!strcmp(name, "r2_14_residual_confirm_window_boundary")) {
        test_r2_14_residual_confirm_window_boundary();
    } else if (!strcmp(name, "publication_state_machine_boundary")) {
        test_publication_state_machine_boundary();
    } else if (!strcmp(name, "unconfirmed_or_crossed_markers_never_confirm")) {
        test_unconfirmed_or_crossed_markers_never_confirm();
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
    } else if (!strcmp(name, "pre_admission_unconfirmed")) {
        test_pre_admission_unconfirmed();
    } else if (!strcmp(name, "pre_admission_terminal_wins")) {
        test_pre_admission_terminal_wins();
    } else if (!strcmp(name, "pre_admission_ignored_markers")) {
        test_pre_admission_ignored_markers();
    } else if (!strcmp(name, "pre_admission_decide_pure")) {
        test_pre_admission_decide_pure();
    } else if (!strcmp(name, "classifier_failure_indeterminate")) {
        test_classifier_failure_indeterminate();
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
