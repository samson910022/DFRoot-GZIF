// Modified 2026-11-24 (DFRoot GZIF): consume Gradle-generated loader pins in the canonical manager model. See NOTICE.
package df.root;

/**
 * Supported KernelSU manager/loader pairs.
 *
 * <p>The package name is a compatibility allowlist used for package visibility
 * and {@code ksud late-load --package-name}. It is not signature authentication.
 * Each manager is tied to exactly one bundled loader and its authoritative pin.</p>
 */
public enum SuManager {
    CLASSIC(
            "me.weishu.kernelsu",
            "libksud.so",
            BuildConfig.BUNDLED_LOADER_CLASSIC_SHA256),
    NEXT(
            "com.rifsxd.ksunext",
            "libksudnext.so",
            BuildConfig.BUNDLED_LOADER_NEXT_SHA256);

    private final String packageName;
    private final String loaderName;
    private final String loaderSha256;

    SuManager(String packageName, String loaderName, String loaderSha256) {
        this.packageName = packageName;
        this.loaderName = loaderName;
        this.loaderSha256 = loaderSha256;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getLoaderName() {
        return loaderName;
    }

    public String getLoaderSha256() {
        return loaderSha256;
    }

    /** Returns the canonical manager for an exact package name, or null. */
    public static SuManager fromPackage(String packageName) {
        for (SuManager manager : values()) {
            if (manager.packageName.equals(packageName)) {
                return manager;
            }
        }
        return null;
    }

    /** Package names derived from the model; callers must not mutate the result. */
    public static String[] packageNames() {
        SuManager[] managers = values();
        String[] packages = new String[managers.length];
        for (int i = 0; i < managers.length; i++) {
            packages[i] = managers[i].packageName;
        }
        return packages;
    }
}
