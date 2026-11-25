/* Modified 2026-11-25 (DFRoot GZIF): exercise hardened loader staging with harmless host directories. See NOTICE. */
#include "loader_stage.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static const char PAYLOAD[] =
    "DFROOT loader-stage harmless host payload\n"
    "0123456789abcdef0123456789abcdef\n";
static char root[PATH_MAX];
static char stage_dir[PATH_MAX];
static char source_file[PATH_MAX];
static char destination[PATH_MAX];
static const char *source_dir;
static struct loader_stage stage;

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

/* Remove a fixture tree without invoking a shell or any privileged helper. */
static void remove_tree(const char *path)
{
    struct stat st;
    if (lstat(path, &st) != 0) {
        return;
    }
    if (S_ISDIR(st.st_mode)) {
        DIR *directory = opendir(path);
        if (directory == NULL) {
            perror("opendir fixture");
            exit(1);
        }
        struct dirent *entry;
        while ((entry = readdir(directory)) != NULL) {
            if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) {
                continue;
            }
            char child[PATH_MAX];
            if (snprintf(child, sizeof(child), "%s/%s", path, entry->d_name) < 0
                || strlen(child) >= sizeof(child)) {
                die("fixture child path is too long");
            }
            remove_tree(child);
        }
        closedir(directory);
        if (rmdir(path) != 0) {
            perror("rmdir fixture");
            exit(1);
        }
        return;
    }
    if (unlink(path) != 0 && errno != ENOENT) {
        perror("unlink fixture");
        exit(1);
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
        remove_tree(path);
    }
    closedir(directory);
    memset(&stage, 0, sizeof(stage));
    loader_stage_inject_fault(LOADER_STAGE_FAULT_NONE);
    loader_stage_set_test_mutator(NULL, NULL);
}

static void write_file(const char *path, const char *text, mode_t mode)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) {
        perror("open fixture file");
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
            perror("write fixture file");
            exit(1);
        }
        if (written == 0) {
            die("fixture write made no progress");
        }
        offset += (size_t)written;
    }
    if (fchmod(fd, mode) != 0 || close(fd) != 0) {
        perror("finish fixture file");
        exit(1);
    }
}

static void setup_stage(void)
{
    make_path(stage_dir, sizeof(stage_dir), "stage");
    make_path(source_file, sizeof(source_file), "source.bin");
    if (mkdir(stage_dir, 0755) != 0) {
        perror("mkdir staging directory");
        exit(1);
    }
    write_file(source_file, PAYLOAD, 0644);
    if (loader_stage_configure(&stage, stage_dir, ".ksud-stage") != 0) {
        fprintf(stderr, "configure failed: %s\n", loader_stage_last_error(&stage));
        exit(1);
    }
    if (strcmp(loader_stage_destination(&stage), stage_dir) <= 0
        || strstr(loader_stage_destination(&stage), ".ksud-stage") == NULL) {
        die("staging destination was not derived from the injected directory");
    }
    snprintf(destination, sizeof(destination), "%s", loader_stage_destination(&stage));
}

static mode_t path_mode(const char *path)
{
    struct stat st;
    if (lstat(path, &st) != 0) {
        perror("lstat fixture");
        exit(1);
    }
    return st.st_mode & 07777;
}

static int path_exists(const char *path)
{
    struct stat st;
    return lstat(path, &st) == 0;
}

static void expect_dir_mode(mode_t expected)
{
    mode_t actual = path_mode(stage_dir);
    if (actual != expected) {
        fprintf(stderr, "staging directory mode: expected %o, got %o\n",
                (unsigned)expected, (unsigned)actual);
        exit(1);
    }
}

static void expect_absent(const char *path)
{
    if (path_exists(path)) {
        fprintf(stderr, "expected no entry at %s\n", path);
        exit(1);
    }
}

static void make_named_path(char *out, size_t capacity,
                            const char *directory, const char *name)
{
    int written = snprintf(out, capacity, "%s/%s", directory, name);
    if (written < 0 || (size_t)written >= capacity) {
        die("fixture path is too long");
    }
}

static struct stat stat_path(const char *path)
{
    struct stat st;
    if (lstat(path, &st) != 0) {
        perror("lstat fixture entry");
        exit(1);
    }
    return st;
}

static int count_entries(const char *directory)
{
    DIR *dir = opendir(directory);
    if (dir == NULL) {
        perror("opendir fixture directory");
        exit(1);
    }
    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) {
            count++;
        }
    }
    closedir(dir);
    return count;
}

static void expect_entry_count(int expected)
{
    int actual = count_entries(stage_dir);
    if (actual != expected) {
        fprintf(stderr, "staging directory entries: expected %d, got %d\n",
                expected, actual);
        exit(1);
    }
}

static void expect_same_entry(const char *path, const struct stat *before)
{
    struct stat after = stat_path(path);
    if (after.st_dev != before->st_dev || after.st_ino != before->st_ino
        || after.st_mode != before->st_mode || after.st_size != before->st_size) {
        fprintf(stderr, "unrelated fixture entry was changed: %s\n", path);
        exit(1);
    }
}

static void expect_content(const char *path, const char *expected)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        perror("open staged file");
        exit(1);
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        die("staged entry is not a regular file");
    }
    char buffer[512];
    ssize_t count = read(fd, buffer, sizeof(buffer) - 1);
    close(fd);
    if (count < 0 || (size_t)count != strlen(expected)) {
        die("staged file has the wrong size");
    }
    buffer[count] = '\0';
    if (strcmp(buffer, expected) != 0) {
        die("staged file has the wrong content");
    }
}

static void expect_install_ok(void)
{
    if (loader_stage_install(&stage, source_file) != 0) {
        fprintf(stderr, "unexpected install failure: %s\n",
                loader_stage_last_error(&stage));
        exit(1);
    }
}

/* Match the exact diagnostic prefix so a different failing branch cannot
 * accidentally satisfy a fault test. */
static void expect_install_fail(const char *prefix)
{
    if (loader_stage_install(&stage, source_file) == 0) {
        die("unsafe staging install succeeded");
    }
    const char *actual = loader_stage_last_error(&stage);
    size_t length = strlen(prefix);
    if (actual == NULL || strncmp(actual, prefix, length) != 0
        || (actual[length] != '\0' && actual[length] != ':')) {
        fprintf(stderr, "wrong install failure: expected '%s', got '%s'\n",
                prefix, actual == NULL ? "(null)" : actual);
        exit(1);
    }
}

static void expect_restore_fail(const char *prefix)
{
    if (loader_stage_restore(&stage) == 0) {
        die("restore unexpectedly succeeded");
    }
    const char *actual = loader_stage_last_error(&stage);
    size_t length = strlen(prefix);
    if (actual == NULL || strncmp(actual, prefix, length) != 0
        || (actual[length] != '\0' && actual[length] != ':')) {
        fprintf(stderr, "wrong restore failure: expected '%s', got '%s'\n",
                prefix, actual == NULL ? "(null)" : actual);
        exit(1);
    }
}

static void expect_restore_ok(void)
{
    if (loader_stage_restore(&stage) != 0) {
        fprintf(stderr, "restore failed: %s\n", loader_stage_last_error(&stage));
        exit(1);
    }
}

static void test_normal(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    expect_install_ok();

    mode_t sticky = path_mode(stage_dir);
    if ((sticky & S_ISVTX) == 0
        || sticky != (mode_t)((before & 07777) | S_ISVTX)) {
        fprintf(stderr, "sticky bit was not added: %o -> %o\n",
                (unsigned)before, (unsigned)sticky);
        exit(1);
    }
    if (path_mode(destination) != LOADER_STAGE_EXEC_MODE) {
        die("staged file is not in the loader-required executable mode");
    }
    expect_content(destination, PAYLOAD);

    /* Simulate the FZG1 loader's rename of the staged entry. */
    char renamed[PATH_MAX];
    make_path(renamed, sizeof(renamed), "renamed-ksud");
    if (rename(destination, renamed) != 0) {
        perror("simulate loader rename");
        exit(1);
    }
    expect_restore_ok();
    expect_dir_mode(before);
    expect_content(renamed, PAYLOAD);
    if (path_mode(renamed) != LOADER_STAGE_EXEC_MODE) {
        die("restore changed the renamed loader");
    }
    /* Restore is idempotent and never removes a renamed file. */
    expect_restore_ok();
    expect_content(renamed, PAYLOAD);
}

static void test_preexisting(const char *kind)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    struct stat original;

    if (!strcmp(kind, "regular")) {
        write_file(destination, "attacker", 0644);
    } else if (!strcmp(kind, "symlink")) {
        if (symlink(source_file, destination) != 0) {
            perror("symlink fixture");
            exit(1);
        }
    } else if (!strcmp(kind, "fifo")) {
        if (mkfifo(destination, 0644) != 0) {
            perror("mkfifo fixture");
            exit(1);
        }
    } else if (!strcmp(kind, "directory")) {
        if (mkdir(destination, 0700) != 0) {
            perror("mkdir destination fixture");
            exit(1);
        }
        char child[PATH_MAX];
        int written = snprintf(child, sizeof(child), "%s/keep", destination);
        if (written < 0 || (size_t)written >= sizeof(child)) {
            die("destination child path is too long");
        }
        write_file(child, "attacker", 0600);
    } else {
        die("unknown pre-existing entry kind");
    }
    if (lstat(destination, &original) != 0) {
        die("pre-existing fixture was not created");
    }

    expect_install_fail("staging destination already exists");

    struct stat after;
    if (lstat(destination, &after) != 0 || after.st_dev != original.st_dev
        || after.st_ino != original.st_ino || after.st_mode != original.st_mode) {
        die("a pre-existing destination was modified or removed");
    }
    expect_dir_mode(before);
    expect_content(source_file, PAYLOAD);
}

static void test_source_symlink(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    char target[PATH_MAX];
    make_path(target, sizeof(target), "real-source");
    write_file(target, PAYLOAD, 0644);
    if (unlink(source_file) != 0 || symlink(target, source_file) != 0) {
        perror("source symlink fixture");
        exit(1);
    }

    expect_install_fail("staging source open failed");
    expect_absent(destination);
    expect_dir_mode(before);
}

static void test_source_fifo(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    if (unlink(source_file) != 0 || mkfifo(source_file, 0644) != 0) {
        perror("source FIFO fixture");
        exit(1);
    }

    /* O_NONBLOCK keeps this rejection deterministic: a blocking read open on
     * a FIFO with no writer would hang instead of failing closed. */
    expect_install_fail("staging source is not a regular file");
    expect_absent(destination);
    expect_dir_mode(before);
}

static void test_source_directory(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    if (unlink(source_file) != 0 || mkdir(source_file, 0700) != 0) {
        perror("source directory fixture");
        exit(1);
    }

    expect_install_fail("staging source is not a regular file");
    expect_absent(destination);
    expect_dir_mode(before);
}

static void test_source_missing(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    if (unlink(source_file) != 0) {
        perror("unlink source fixture");
        exit(1);
    }

    expect_install_fail("staging source open failed");
    expect_absent(destination);
    expect_dir_mode(before);
}

static void test_staging_dir_link(void)
{
    setup_stage();
    char link[PATH_MAX];
    make_path(link, sizeof(link), "stage-link");
    if (symlink(stage_dir, link) != 0) {
        perror("staging dir symlink fixture");
        exit(1);
    }
    if (loader_stage_configure(&stage, link, ".ksud-stage") != 0) {
        die("configure rejected a harmless absolute path");
    }

    expect_install_fail("staging directory is not a real directory");
    expect_absent(destination);
}

static void test_staging_dir_regular(void)
{
    setup_stage();
    char file[PATH_MAX];
    make_path(file, sizeof(file), "stage-file");
    write_file(file, "not a directory", 0600);
    if (loader_stage_configure(&stage, file, ".ksud-stage") != 0) {
        die("configure rejected a harmless absolute path");
    }

    expect_install_fail("staging directory is not a real directory");
}

static void expect_copy_failure_cleanup(int fault, const char *prefix)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    loader_stage_inject_fault(fault);
    expect_install_fail(prefix);
    expect_absent(destination);
    expect_dir_mode(before);
    expect_content(source_file, PAYLOAD);
    /* Failure cleanup is complete: a later restore is a harmless no-op. */
    expect_restore_ok();
    expect_dir_mode(before);
}

static void test_fault_short_write(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    loader_stage_inject_fault(LOADER_STAGE_FAULT_SHORT_WRITE);
    expect_install_ok();
    if (path_mode(destination) != LOADER_STAGE_EXEC_MODE) {
        die("short-write copy did not reach the executable mode");
    }
    expect_content(destination, PAYLOAD);
    expect_restore_ok();
    expect_dir_mode(before);
    expect_absent(destination);
}

static void test_rename_failure(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    expect_install_ok();
    /* Simulate the late-load command returning failure without renaming: the
     * separate restore function is still called and cleans up our own inode. */
    expect_restore_ok();
    expect_absent(destination);
    expect_dir_mode(before);
}

static void test_restore_failure(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    expect_install_ok();
    loader_stage_inject_fault(LOADER_STAGE_FAULT_RESTORE_CHMOD);
    expect_restore_fail("staging directory mode restore failed");
    if ((path_mode(stage_dir) & S_ISVTX) == 0) {
        die("failed restore lost the sticky-bit failure evidence");
    }
    loader_stage_inject_fault(LOADER_STAGE_FAULT_NONE);
    expect_restore_ok();
    expect_dir_mode(before);
}

struct mutation_context {
    int replace_after_lstat;
    int observe_before_fchmod;
    int observed;
};

static void mutation(void *context, int point)
{
    struct mutation_context *mutation_state = context;
    if (point == LOADER_STAGE_HOOK_AFTER_DEST_LSTAT
        && mutation_state->replace_after_lstat) {
        write_file(destination, "attacker", 0644);
    }
    if (point == LOADER_STAGE_HOOK_BEFORE_FCHMOD
        && mutation_state->observe_before_fchmod) {
        mode_t mode = path_mode(destination);
        struct stat st;
        if (lstat(destination, &st) != 0) {
            die("pre-fchmod hook cannot see the staged entry");
        }
        if (mode != LOADER_STAGE_PRIVATE_MODE || st.st_size != (off_t)strlen(PAYLOAD)) {
            fprintf(stderr, "destination was not private/complete before fchmod: mode=%o size=%lld\n",
                    (unsigned)mode, (long long)st.st_size);
            exit(1);
        }
        mutation_state->observed = 1;
    }
}

static void test_replacement_after_lstat(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    struct mutation_context context;
    memset(&context, 0, sizeof(context));
    context.replace_after_lstat = 1;
    loader_stage_set_test_mutator(mutation, &context);

    expect_install_fail("staging destination open failed");
    expect_content(destination, "attacker");
    expect_dir_mode(before);
    expect_restore_ok();
    expect_dir_mode(before);
    /* The attacker entry must survive both the failure and the restore. */
    expect_content(destination, "attacker");
}

static void test_replacement_before_restore(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    expect_install_ok();
    if (unlink(destination) != 0) {
        perror("unlink staged entry");
        exit(1);
    }
    write_file(destination, "attacker", 0644);
    struct stat replaced;
    if (lstat(destination, &replaced) != 0) {
        die("replacement entry was not created");
    }

    expect_restore_fail("staged entry was replaced before cleanup");
    expect_content(destination, "attacker");
    /* The directory mode is still restored even though cleanup was unsafe. */
    expect_dir_mode(before);
}

/* Deterministic inode-churn/reuse attempt: create and remove many entries
 * immediately before replacing the staged path. The staged inode is pinned by
 * a descriptor, so its number cannot be recycled for the replacement; restore
 * must still detect that the path names a different object. No sleeps and no
 * reliance on which inode number the allocator happens to hand out. */
static void test_inode_churn_reuse(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    expect_install_ok();
    char path[PATH_MAX];
    char name[32];
    for (int i = 0; i < 24; i++) {
        snprintf(name, sizeof(name), "churn-%02d", i);
        make_named_path(path, sizeof(path), stage_dir, name);
        write_file(path, "churn", 0600);
    }
    for (int i = 0; i < 24; i++) {
        snprintf(name, sizeof(name), "churn-%02d", i);
        make_named_path(path, sizeof(path), stage_dir, name);
        if (unlink(path) != 0) {
            perror("unlink inode-churn fixture");
            exit(1);
        }
    }
    if (unlink(destination) != 0) {
        perror("unlink staged entry");
        exit(1);
    }
    write_file(destination, "attacker", 0644);
    struct stat attacker_before = stat_path(destination);

    expect_restore_fail("staged entry was replaced before cleanup");
    expect_content(destination, "attacker");
    expect_same_entry(destination, &attacker_before);
    expect_dir_mode(before);
    expect_entry_count(1);
}

/* The staged entry is renamed away and a different object takes its path.
 * Identity cannot be established from the path, so restore fails conservatively:
 * it creates nothing, removes neither entry, and still restores directory mode.
 * A rename with no replacement is the legitimate loader case and stays covered
 * by test_normal (which requires restore to succeed). */
static void test_renamed_away_before_restore(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    expect_install_ok();
    char moved[PATH_MAX];
    make_named_path(moved, sizeof(moved), stage_dir, "moved-staged");
    if (rename(destination, moved) != 0) {
        perror("rename staged entry");
        exit(1);
    }
    write_file(destination, "attacker", 0644);
    struct stat moved_before = stat_path(moved);
    struct stat attacker_before = stat_path(destination);
    expect_entry_count(2);

    expect_restore_fail("staged entry was replaced before cleanup");
    expect_content(moved, PAYLOAD);
    expect_content(destination, "attacker");
    expect_same_entry(moved, &moved_before);
    expect_same_entry(destination, &attacker_before);
    expect_entry_count(2);
    expect_dir_mode(before);
}

/* Unrelated files and directories in the staging area are never touched by a
 * successful install/restore cycle. */
static void test_unrelated_entries_untouched(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    char keep_file[PATH_MAX];
    char keep_dir[PATH_MAX];
    char keep_child[PATH_MAX];
    make_named_path(keep_file, sizeof(keep_file), stage_dir, "keep-file");
    make_named_path(keep_dir, sizeof(keep_dir), stage_dir, "keep-dir");
    write_file(keep_file, "keep-me", 0640);
    if (mkdir(keep_dir, 0700) != 0) {
        perror("mkdir unrelated fixture directory");
        exit(1);
    }
    make_named_path(keep_child, sizeof(keep_child), keep_dir, "child");
    write_file(keep_child, "keep-child", 0600);
    struct stat file_before = stat_path(keep_file);
    struct stat dir_before = stat_path(keep_dir);
    struct stat child_before = stat_path(keep_child);

    expect_install_ok();
    expect_restore_ok();
    expect_absent(destination);
    expect_content(keep_file, "keep-me");
    expect_content(keep_child, "keep-child");
    expect_same_entry(keep_file, &file_before);
    expect_same_entry(keep_dir, &dir_before);
    expect_same_entry(keep_child, &child_before);
    expect_entry_count(2);
    expect_dir_mode(before);
}

/* Harmless, deterministic exec helper: resolve the system's true binary to a
 * regular file (the /bin/true name itself may be a symlink, and the staging
 * source must be a non-symlink regular file). */
static const char *exec_helper_path(void)
{
    static char resolved[PATH_MAX];
    static const char *const candidates[] = { "/bin/true", "/usr/bin/true" };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        struct stat st;
        if (lstat(candidates[i], &st) == 0 && S_ISREG(st.st_mode)
            && (st.st_mode & 0111) != 0) {
            snprintf(resolved, sizeof(resolved), "%s", candidates[i]);
            return resolved;
        }
        /* Commonly /bin/true is a symlink; resolve one level by hand so the
         * staging source stays a regular file under O_NOFOLLOW. */
        char link_target[PATH_MAX];
        ssize_t count = readlink(candidates[i], link_target, sizeof(link_target) - 1);
        if (count <= 0) {
            continue;
        }
        link_target[count] = '\0';
        char target[PATH_MAX];
        if (link_target[0] == '/') {
            snprintf(target, sizeof(target), "%s", link_target);
        } else {
            const char *slash = strrchr(candidates[i], '/');
            size_t directory_length = slash == NULL ? 0 : (size_t)(slash - candidates[i]) + 1;
            int written = snprintf(target, sizeof(target), "%.*s%s",
                                   (int)directory_length, candidates[i], link_target);
            if (written < 0 || (size_t)written >= sizeof(target)) {
                continue;
            }
        }
        if (lstat(target, &st) == 0 && S_ISREG(st.st_mode)
            && (st.st_mode & 0111) != 0) {
            snprintf(resolved, sizeof(resolved), "%s", target);
            return resolved;
        }
    }
    die("no harmless exec helper (/bin/true) is available");
    return NULL;
}

/* Reproduce the real production ordering: bootstrap execv()s the staged entry
 * BEFORE calling restore(). A surviving O_WRONLY pin would make exec fail with
 * ETXTBSY even with O_CLOEXEC, because the write-access check happens when the
 * child opens the executable. Also assert the retained pin is read-only. */
static void test_exec_before_restore(void)
{
    setup_stage();
    mode_t before = path_mode(stage_dir);
    const char *helper = exec_helper_path();
    if (loader_stage_install(&stage, helper) != 0) {
        fprintf(stderr, "unexpected install failure: %s\n",
                loader_stage_last_error(&stage));
        exit(1);
    }
    int pin_flags = fcntl(stage.held_fd, F_GETFL);
    if (pin_flags < 0 || (pin_flags & O_ACCMODE) != O_RDONLY) {
        fprintf(stderr, "retained staged pin is not read-only: flags=%o\n",
                (unsigned)(pin_flags < 0 ? 0 : pin_flags & O_ACCMODE));
        exit(1);
    }

    pid_t child = fork();
    if (child < 0) {
        perror("fork staged exec helper");
        exit(1);
    }
    if (child == 0) {
        char *const child_argv[] = { (char *)destination, NULL };
        execv(destination, child_argv);
        perror("exec staged file");
        _exit(127);
    }
    int status = 0;
    while (waitpid(child, &status, 0) < 0) {
        if (errno == EINTR) {
            continue;
        }
        perror("waitpid staged exec helper");
        exit(1);
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        fprintf(stderr, "staged exec failed before restore: status=%d\n", status);
        exit(1);
    }

    expect_restore_ok();
    expect_absent(destination);
    expect_dir_mode(before);
}

static void test_private_before_exec(void)
{
    setup_stage();
    struct mutation_context context;
    memset(&context, 0, sizeof(context));
    context.observe_before_fchmod = 1;
    loader_stage_set_test_mutator(mutation, &context);
    expect_install_ok();
    if (!context.observed) {
        die("pre-fchmod verification hook was never called");
    }
    if (path_mode(destination) != LOADER_STAGE_EXEC_MODE) {
        die("staged file was not switched to the loader mode after the copy");
    }
    expect_restore_ok();
}

static void test_configure_defaults(void)
{
    struct loader_stage defaults;
    memset(&defaults, 0, sizeof(defaults));
    if (loader_stage_configure(&defaults, NULL, NULL) != 0) {
        fprintf(stderr, "default configure failed: %s\n",
                loader_stage_last_error(&defaults));
        exit(1);
    }
    if (strcmp(loader_stage_destination(&defaults), LOADER_STAGE_PATH_DEFAULT) != 0
        || strcmp(loader_stage_destination(&defaults),
                  "/data/local/tmp/.ksud-stage") != 0) {
        die("production staging ABI path changed");
    }
    if (loader_stage_configure(&defaults, "/tmp", "../escape") == 0) {
        die("unsafe destination name was accepted");
    }
    if (loader_stage_configure(&defaults, "relative/path", ".ksud-stage") == 0) {
        die("relative staging directory was accepted");
    }
    if (loader_stage_configure(&defaults, "/tmp", "") == 0) {
        die("empty destination name was accepted");
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
    if (buffer == NULL) {
        die("native source buffer allocation failed");
    }
    size_t offset = 0;
    while (offset < (size_t)st.st_size) {
        ssize_t count = read(fd, buffer + offset, (size_t)st.st_size - offset);
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            perror("read native source");
            exit(1);
        }
        if (count == 0) {
            break;
        }
        offset += (size_t)count;
    }
    close(fd);
    buffer[offset] = '\0';
    return buffer;
}

static char *normalize_whitespace(const char *text)
{
    char *out = malloc(strlen(text) + 1);
    if (out == NULL) {
        die("normalized source allocation failed");
    }
    size_t j = 0;
    int in_space = 0;
    for (size_t i = 0; text[i] != '\0'; i++) {
        unsigned char c = (unsigned char)text[i];
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            if (!in_space) {
                out[j++] = ' ';
            }
            in_space = 1;
        } else {
            out[j++] = (char)c;
            in_space = 0;
        }
    }
    out[j] = '\0';
    return out;
}

static void expect_contains(const char *text, const char *needle)
{
    if (strstr(text, needle) == NULL) {
        fprintf(stderr, "native source wiring missing: %s\n", needle);
        exit(1);
    }
}

static void test_no_shell(void)
{
    if (source_dir == NULL) {
        die("native source directory is required");
    }
    char *bootstrap = read_native_source("bootstrap.c");
    char *stage_source = read_native_source("loader_stage.c");
    if (strstr(bootstrap, "system(") != NULL
        || strstr(stage_source, "system(") != NULL
        || strstr(stage_source, "popen(") != NULL
        || strstr(stage_source, "fork(") != NULL
        || strstr(stage_source, "execv(") != NULL
        || strstr(stage_source, "execl(") != NULL
        || strstr(stage_source, "kill(") != NULL
        || strstr(stage_source, "raise(") != NULL) {
        die("staging reintroduced a shell, process spawn, or signal");
    }
    expect_contains(bootstrap, "loader_stage_install(");
    expect_contains(bootstrap, "loader_stage_restore(");
    free(bootstrap);
    free(stage_source);
}

static void test_wiring(void)
{
    if (source_dir == NULL) {
        die("native source directory is required");
    }
    char *stage_source = read_native_source("loader_stage.c");
    char *bootstrap = read_native_source("bootstrap.c");
    char *stage = normalize_whitespace(stage_source);
    char *boot = normalize_whitespace(bootstrap);

    expect_contains(stage, "O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK");
    expect_contains(stage, "O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC");
    expect_contains(stage, "S_ISVTX");
    expect_contains(stage, "chmod(stage->directory, (mode_t)new_mode)");
    expect_contains(stage, "st.st_dev != stage->created_dev || st.st_ino != stage->created_ino");
    expect_contains(stage, "fchmod(dst_fd, LOADER_STAGE_EXEC_MODE)");
    expect_contains(stage, "fsync(dst_fd)");
    expect_contains(stage, "while (offset < length)");
    expect_contains(boot, "loader_stage_restore(&stage)");
    expect_contains(boot, "if (restored != 0)");
    expect_contains(boot, "\"late-load\"");
    expect_contains(boot, "\"/data/local/tmp/.ksud-stage\"");
    if (strstr(bootstrap, "kill(") != NULL || strstr(bootstrap, "raise(") != NULL) {
        die("bootstrap reintroduced process signaling");
    }
    free(stage_source);
    free(bootstrap);
    free(stage);
    free(boot);
}

static void run_case(const char *name)
{
    if (!strcmp(name, "normal")) {
        test_normal();
    } else if (!strcmp(name, "preexisting_regular")) {
        test_preexisting("regular");
    } else if (!strcmp(name, "preexisting_symlink")) {
        test_preexisting("symlink");
    } else if (!strcmp(name, "preexisting_fifo")) {
        test_preexisting("fifo");
    } else if (!strcmp(name, "preexisting_directory")) {
        test_preexisting("directory");
    } else if (!strcmp(name, "source_symlink")) {
        test_source_symlink();
    } else if (!strcmp(name, "source_fifo")) {
        test_source_fifo();
    } else if (!strcmp(name, "source_directory")) {
        test_source_directory();
    } else if (!strcmp(name, "source_missing")) {
        test_source_missing();
    } else if (!strcmp(name, "staging_dir_symlink")) {
        test_staging_dir_link();
    } else if (!strcmp(name, "staging_dir_regular")) {
        test_staging_dir_regular();
    } else if (!strcmp(name, "fault_write")) {
        expect_copy_failure_cleanup(LOADER_STAGE_FAULT_WRITE,
                                    "staged copy write failed");
    } else if (!strcmp(name, "fault_short_write")) {
        test_fault_short_write();
    } else if (!strcmp(name, "fault_fsync")) {
        expect_copy_failure_cleanup(LOADER_STAGE_FAULT_FSYNC,
                                    "staged copy fsync failed");
    } else if (!strcmp(name, "fault_close")) {
        expect_copy_failure_cleanup(LOADER_STAGE_FAULT_CLOSE,
                                    "staged copy close failed");
    } else if (!strcmp(name, "rename_failure")) {
        test_rename_failure();
    } else if (!strcmp(name, "restore_failure")) {
        test_restore_failure();
    } else if (!strcmp(name, "replacement_after_lstat")) {
        test_replacement_after_lstat();
    } else if (!strcmp(name, "replacement_before_restore")) {
        test_replacement_before_restore();
    } else if (!strcmp(name, "inode_churn_reuse")) {
        test_inode_churn_reuse();
    } else if (!strcmp(name, "renamed_away_before_restore")) {
        test_renamed_away_before_restore();
    } else if (!strcmp(name, "unrelated_entries_untouched")) {
        test_unrelated_entries_untouched();
    } else if (!strcmp(name, "exec_before_restore")) {
        test_exec_before_restore();
    } else if (!strcmp(name, "private_before_exec")) {
        test_private_before_exec();
    } else if (!strcmp(name, "configure_defaults")) {
        test_configure_defaults();
    } else if (!strcmp(name, "no_shell")) {
        test_no_shell();
    } else if (!strcmp(name, "wiring")) {
        test_wiring();
    } else {
        die("unknown test case");
    }
}

int main(int argc, char **argv)
{
    if (argc != 3 && argc != 4) {
        die("usage: loader_stage_test CASE ROOT [SOURCE_DIR]");
    }
    source_dir = argc == 4 ? argv[3] : NULL;
    reset_root(argv[2]);
    run_case(argv[1]);
    return 0;
}
