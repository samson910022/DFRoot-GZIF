#include <linux/init.h>
#include <linux/kernel.h>
#include <linux/kmod.h>
#include <linux/umh.h>
#include <linux/kprobes.h>
#include <linux/module.h>
#include <linux/namei.h>

/* __nocfi is provided by <linux/compiler_types.h> (included transitively via
 * <linux/init.h>) on every supported kernel; it expands to the
 * no_sanitize("cfi") attribute on CFI kernels and to nothing otherwise. */

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("DFRoot LKM");

/* MAINTENANCE NOTE (2026-11-24):
 * The UMH typedef correction is a C prototype/CFI type-identity cleanup.
 * Whether it changes the compiled .ko bytes is NOT established here: every
 * indirect call site (umh_setup, umh_exec, get_addr, mkln, kern_path_fn,
 * invalidate_fn, path_put_fn, insn_patch_text) lives inside functions marked
 * __nocfi (no_sanitize("cfi")), which suppresses CFI check emission, so the
 * type-ID constant may not be emitted at those sites. The claim that the bytes
 * change must be verified by diffing the compiled module before/after on the
 * actual target DDK/compiler; do not assume either outcome.
 * If the bytes do change, the fail-closed KO SHA-256 gate
 * (KO_EXPECTED_SHA256S in app/src/main/jni/CMakeLists.txt) must be reviewed
 * and re-pinned in a separate, reviewed commit before release. That gate is
 * present in merged master (PR #3); this note only records the dependency.
 * The re-pin must never be auto-generated.
 */

typedef unsigned long (*kallsyms_lookup_name_t)(const char *name);
typedef unsigned long (*module_kallsyms_lookup_name_t)(const char *name);
/* CFI type-identity fix: the CFI type hash is derived from the function type, so
 * these typedefs must match the real kernel prototypes in <linux/umh.h>. They
 * now do (verified against the available host UMH header; the target vendor
 * header was NOT checked). Whether this changes
 * generated code is a separate, unverified question -- see the maintenance note
 * above; do not assume the .ko bytes are unchanged or changed.
 * ABI note: on AAPCS64 the arguments of this signature are passed in 64-bit
 * registers; gfp_t is 32-bit and is zero-extended. Correcting pointer types
 * does not by itself imply identical instruction selection, and this comment
 * makes no such claim. */
/* Scope note (H-1, for contrast): this branch fixes ONLY the UMH typedefs'
 * CFI type match. The other resolved function-pointer types -- get_addr /
 * mkln / kern_path_fn / invalidate_fn / path_put_fn / insn_patch_text --
 * appear to match their real kernel prototypes based on manual inspection of
 * the available headers, but that has NOT been verified against the target
 * Android/vendor (5.15-era) headers/compilers. If any vendor prototype
 * differs, that typedef must be corrected too; this scope note is a claim of
 * intent, not of verification. */
typedef struct subprocess_info *(*umh_setup_t)(const char *path, char **argv, char **envp,
                                               gfp_t gfp_mask,
                                               int (*init)(struct subprocess_info *, struct cred *),
                                               void (*cleanup)(struct subprocess_info *),
                                               void *data);
typedef int (*umh_exec_t)(struct subprocess_info *info, int wait);
typedef int  (*kern_path_t)(const char *, unsigned int, struct path *);
typedef int  (*invalidate_t)(struct address_space *);
typedef void (*path_put_t)(const struct path *);
typedef int  (*insn_patch_t)(void *addrs[], u32 insns[], int cnt);

static insn_patch_t insn_patch_text;

/* KNOWN LIMITATION (patch helper, not fixed in this branch): patch_fn_ret_zero()
 * unconditionally rewrites the first 12 bytes of the target function with a
 * BTI landing pad + MOV x0,#0 + RET. It does not check that the function is at
 * least 12 bytes long, nor that the destination honors CFI landing-pad /
 * type-ID rules. The __nocfi attribute on THIS helper only disables CFI
 * instrumentation of the helper itself; it provides NO guarantee about the
 * kernel function being overwritten or about any other caller. On
 * insn_patch_text() failure it only emits pr_warn() and the caller proceeds to
 * the UMH regardless, so the failure mode is not clean. Left as-is here to
 * avoid changing the verified patch logic. */
static __nocfi void patch_fn_ret_zero(void *fn)
{
    void *addrs[3];
    u32 insns[3];

    addrs[0] = fn;
    addrs[1] = (u8 *)fn + 4;
    addrs[2] = (u8 *)fn + 8;

    insns[0] = 0xD503245F;  // BTI C  (valid landing pad, NOP when BTI not enforced)
    insns[1] = 0xD2800000;  // MOV x0, #0
    insns[2] = 0xD65F03C0;  // RET

    /* Pointer output removed from both printk sites (no kernel address is
     * disclosed). Full removal of any future pointer logging must keep this
     * property. */
    if (insn_patch_text(addrs, insns, 3))
        pr_warn("dfroot: patch failed\n");
}

static int __nocfi __init dfroot_init(void)
{
    kallsyms_lookup_name_t get_addr;
    kern_path_t  kern_path_fn;
    invalidate_t invalidate_fn;
    path_put_t   path_put_fn;
    struct path  p;
    umh_setup_t umh_setup;
    umh_exec_t  umh_exec;
    bool *selinux_state;
    module_kallsyms_lookup_name_t mkln;
    struct kprobe kln_kp;
    void *avc_fn, *avc_noaudit_fn, *oplus_exe_fn, *oplus_root_fn, *defex_enforce_fn, *defex_umh_fn;
    struct subprocess_info *info;
    int ret;

    // UMH command to run
    static const char sh[] = "/system/bin/sh";
    static const char bootstrap[] = "/data/user_de/0/df.root/bootstrap";
    static char cmd[512];
    static char *envp[] = { "PATH=/system/bin", NULL };
    static char *argv[] = { (char *)sh, "-c", cmd, NULL };
    snprintf(cmd, sizeof(cmd), "touch /dev/dfm0; echo -n 'u:r:init:s0' > /proc/self/attr/exec; exec %s", bootstrap);

    // Symbol finder
    kln_kp = (struct kprobe){ .symbol_name = "kallsyms_lookup_name" };
    if (register_kprobe(&kln_kp) < 0) {
        pr_err("dfroot: kallsyms_lookup_name not found\n");
        return -EINVAL;
    }
    if (!kln_kp.addr) {
        pr_err("dfroot: kallsyms_lookup_name kprobe resolved no address\n");
        unregister_kprobe(&kln_kp);
        return -EINVAL;
    }
    get_addr = (kallsyms_lookup_name_t)kln_kp.addr;
    unregister_kprobe(&kln_kp);

    /* KNOWN LIMITATION (M-6, not fixed in this branch): this symbol lookup
     * depends entirely on register_kprobe() to locate kallsyms_lookup_name.
     * On kernels built with CONFIG_KPROBES=n register_kprobe() fails and the
     * code above returns -EINVAL, so the module cannot load. There is no
     * alternative resolution mechanism here; documented as a known limit. */
    insn_patch_text = (insn_patch_t)get_addr("aarch64_insn_patch_text");
    if (!insn_patch_text) {
        pr_err("dfroot: aarch64_insn_patch_text not found\n");
        return -EINVAL;
    }

    mkln = (module_kallsyms_lookup_name_t)get_addr("module_kallsyms_lookup_name");

    // Invalidate page_cache for crash_dump64
    // NOTE: this can cause issues if a process is currently executing
    //   crash_dump64. We may want to revert to manual restore patching
    // KNOWN LIMITATION (L-3, not fixed in this branch): besides the race with
    //   an in-flight crash_dump64 described above, the return value of
    //   invalidate_inode_pages2() is currently not checked at all, so a partial
    //   or failed invalidation is silently ignored. Left as-is to avoid changing
    //   the verified logic; documented only.
    kern_path_fn  = (kern_path_t) get_addr("kern_path");
    invalidate_fn = (invalidate_t)get_addr("invalidate_inode_pages2");
    path_put_fn   = (path_put_t)  get_addr("path_put");
    if (!kern_path_fn || !invalidate_fn || !path_put_fn) {
        pr_err("dfroot: cache drop symbols missing\n");
    } else if (kern_path_fn("/apex/com.android.runtime/bin/crash_dump64",
                            LOOKUP_FOLLOW, &p)) {
        pr_err("dfroot: kern_path failed for crash_dump64\n");
    } else {
        invalidate_fn(p.dentry->d_inode->i_mapping);
        path_put_fn(&p);
        pr_info("dfroot: cleared page cache for crash_dump64\n");
    }

    // Disable SELinux
    selinux_state = (bool *)get_addr("selinux_state");
    if (!selinux_state) {
        pr_err("dfroot: selinux_state not found\n");
        return -EINVAL;
    }
    WRITE_ONCE(*selinux_state, false);
    pr_info("dfroot: selinux_state permissive\n");

    /* KNOWN LIMITATION / HIGH RISK (M-4, not fixed in this branch): the write
     * above treats selinux_state as a bool* and relies on struct selinux_state
     * having `enforcing` as its first field. On kernels where the first field is
     * `bool disabled`, writing false can CLEAR `disabled` and thereby re-enable
     * SELinux, while the READ_ONCE below then reads false and skips the
     * avc_has_perm / avc_has_perm_noaudit patching -- SELinux stays enforcing
     * and the UMH exec may be denied. This is a real, unfixed correctness/
     * security defect on those targets, not merely a documentation caveat. It
     * is left unfixed here to avoid changing the already-verified SELinux write
     * logic on the validated target; a proper fix (correct field offset, or
     * reading /sys/fs/selinux/enforce as proposed upstream) is required before
     * claiming compatibility with kernels that use the disabled-first layout.
     * Upstream reference: diabl0w/DFRoot PR #182 by hexlo404 (selinuxfs
     * enforcement read), which remains open and is not imported here. */
    // Some kernels ship CONFIG_SECURITY_SELINUX_DEVELOP=n, so the selinux_state
    // write above is a no-op there — patch the SELinux checks themselves instead
    // (both: most denials surface through the noaudit path first).
    /* KNOWN LIMITATION (M-7, not fixed in this branch): this gating uses the
     * very same bool that was just written above as its condition. If that
     * value was misread (see M-4), the avc_has_perm / avc_has_perm_noaudit
     * patching below is skipped entirely. Combined with patch_fn_ret_zero()
     * only pr_warn()-ing on failure and still returning, a failed patch does
     * not block the UMH. This failure mode is not clean and is left unfixed. */
    if (READ_ONCE(*selinux_state)) {
        avc_fn = (void *)get_addr("avc_has_perm");
        if (!avc_fn)
            pr_err("dfroot: avc_has_perm not found, skipping\n");
        else {
            patch_fn_ret_zero(avc_fn);
            pr_info("dfroot: avc_has_perm patched\n");
        }

        avc_noaudit_fn = (void *)get_addr("avc_has_perm_noaudit");
        if (!avc_noaudit_fn)
            pr_err("dfroot: avc_has_perm_noaudit not found, skipping\n");
        else {
            patch_fn_ret_zero(avc_noaudit_fn);
            pr_info("dfroot: avc_has_perm_noaudit patched\n");
        }
    }

    // Oppo/OnePlus
    oplus_exe_fn = (void *)(mkln ? mkln("oplus_exe_block_ret_handler") : 0);
    if (!oplus_exe_fn)
        pr_err("dfroot: oplus_exe_block_ret_handler not found, skipping\n");
    else {
        patch_fn_ret_zero(oplus_exe_fn);
        pr_info("dfroot: oplus_exe_block_ret_handler patched\n");
    }

    oplus_root_fn = (void *)(mkln ? mkln("oplus_root_check_post_handler") : 0);
    if (!oplus_root_fn)
        pr_err("dfroot: oplus_root_check_post_handler not found, skipping\n");
    else {
        patch_fn_ret_zero(oplus_root_fn);
        pr_info("dfroot: oplus_root_check_post_handler patched\n");
    }

    // Samsung
    defex_enforce_fn = (void *)get_addr("task_defex_enforce");
    if (!defex_enforce_fn)
        pr_err("dfroot: task_defex_enforce not found, skipping\n");
    else {
        patch_fn_ret_zero(defex_enforce_fn);
        pr_info("dfroot: task_defex_enforce patched\n");
    }

    defex_umh_fn = (void *)get_addr("task_defex_user_exec");
    if (!defex_umh_fn)
        pr_err("dfroot: task_defex_user_exec not found, skipping\n");
    else {
        patch_fn_ret_zero(defex_umh_fn);
        pr_info("dfroot: task_defex_user_exec patched\n");
    }

    // Run UMH command
    umh_setup = (umh_setup_t)get_addr("call_usermodehelper_setup");
    umh_exec  = (umh_exec_t)get_addr("call_usermodehelper_exec");
    if (!umh_setup || !umh_exec) {
        pr_err("dfroot: usermodehelper symbols missing\n");
        return -EINVAL;
    }

    info = umh_setup(sh, argv, envp, GFP_KERNEL, NULL, NULL, NULL);
    if (!info) {
        pr_err("dfroot: usermodehelper_setup: returned NULL\n");
        return -EINVAL;
    }
    // bypass CONFIG_STATIC_USERMODEHELPER_PATH="" overriding path to ""
    info->path = sh;

    ret = umh_exec(info, UMH_WAIT_EXEC);
    pr_info("dfroot: usermodehelper_exec returned %d\n", ret);
    
    /* intentionally fail init to unload immediately; avoids triggering
     * mrdump module-loaded callbacks on MTK devices */
    return -ENODEV;
}

/* some kernels' cfi_init walks both __cfi_jt_init_module and __cfi_jt_cleanup_module
 * at load time; without a module_exit, the cleanup_module CFI jump table entry is
 * absent and cfi_init crashes in strcmp */
static void __exit dfroot_exit(void) {}

module_init(dfroot_init);
module_exit(dfroot_exit);
