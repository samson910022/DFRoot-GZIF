// Modified 2026-11-24 (DFRoot GZIF): add the side-effect-free canonical run preflight boundary. See NOTICE.
package df.root;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Read-only validation performed before staging, IpSec allocation, or process start.
 *
 * <p>The environment boundary exposes only observations. It cannot stage files,
 * allocate IpSec resources, open sockets, or start processes, so a rejected
 * preflight is side-effect-free by construction.</p>
 */
public final class RunPreflight {
    public static final String BOOTSTRAP_BIN = "libbootstrap.so";
    public static final String NATIVE_BIN = "libdfroot.so";

    private RunPreflight() {}

    public interface Environment {
        String getSelectedManagerPackage();
        boolean isManagerInstalled(String packageName);
        boolean isOwnerUser();
        String getNativeLibraryDir();
        String getLogTag();
        FirmwareGate.Inputs getFirmware();
    }

    public enum Failure {
        NO_MANAGER_SELECTED("select a supported SU manager"),
        UNSUPPORTED_MANAGER("selected SU manager is not supported"),
        MANAGER_NOT_INSTALLED("selected SU manager is not installed or visible"),
        SECONDARY_USER("runs are supported only for the owner Android user"),
        UNSUPPORTED_FIRMWARE("device firmware does not match the documented target"),
        LOG_BUFFER_OFF("Developer Options log buffer size is Off"),
        NATIVE_LIBRARY_DIR_MISSING("nativeLibraryDir is unavailable"),
        LOADER_MISSING("selected bundled loader is missing"),
        LOADER_SYMLINK("selected bundled loader must not be a symlink"),
        LOADER_NOT_REGULAR("selected bundled loader must be a regular file"),
        LOADER_HASH_MISMATCH("selected bundled loader failed its SHA-256 pin"),
        BOOTSTRAP_MISSING("libbootstrap.so is missing"),
        BOOTSTRAP_SYMLINK("libbootstrap.so must not be a symlink"),
        BOOTSTRAP_NOT_REGULAR("libbootstrap.so must be a regular file"),
        NATIVE_MISSING("libdfroot.so is missing"),
        NATIVE_SYMLINK("libdfroot.so must not be a symlink"),
        NATIVE_NOT_REGULAR("libdfroot.so must be a regular file"),
        ASSET_INSPECTION_FAILED("bundled assets could not be inspected safely");

        private final String message;

        Failure(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    /** Validated, immutable inputs that later stages may trust. */
    public static final class Plan {
        private final SuManager manager;
        private final File loader;
        private final File bootstrap;
        private final File nativeBinary;

        Plan(SuManager manager, File loader, File bootstrap, File nativeBinary) {
            this.manager = manager;
            this.loader = loader;
            this.bootstrap = bootstrap;
            this.nativeBinary = nativeBinary;
        }

        public SuManager getManager() { return manager; }
        public File getLoader() { return loader; }
        public File getBootstrap() { return bootstrap; }
        public File getNativeBinary() { return nativeBinary; }
    }

    public static final class Result {
        private final Plan plan;
        private final Failure failure;
        private final String reason;

        private Result(Plan plan, Failure failure, String reason) {
            this.plan = plan;
            this.failure = failure;
            this.reason = reason;
        }

        public boolean isReady() {
            return plan != null;
        }

        public Plan getPlan() {
            if (plan == null) {
                throw new IllegalStateException("rejected preflight has no execution plan");
            }
            return plan;
        }

        public Failure getFailure() {
            if (failure == null) {
                throw new IllegalStateException("successful preflight has no failure");
            }
            return failure;
        }

        public String getReason() {
            return reason;
        }

        static Result ready(Plan plan) {
            return new Result(Objects.requireNonNull(plan, "plan"), null, "preflight ready");
        }

        static Result rejected(Failure failure, String reason) {
            return new Result(null, failure, reason);
        }
    }

    public static Result check(Environment environment) {
        String selectedPackage = environment.getSelectedManagerPackage();
        if (selectedPackage == null) {
            return rejected(Failure.NO_MANAGER_SELECTED);
        }
        SuManager manager = SuManager.fromPackage(selectedPackage);
        if (manager == null) {
            return rejected(Failure.UNSUPPORTED_MANAGER);
        }
        if (!environment.isManagerInstalled(selectedPackage)) {
            return rejected(Failure.MANAGER_NOT_INSTALLED);
        }
        if (!environment.isOwnerUser()) {
            return rejected(Failure.SECONDARY_USER);
        }
        FirmwareGate.Result firmware = FirmwareGate.validate(environment.getFirmware());
        if (!firmware.isSupported()) {
            return new Result(null, Failure.UNSUPPORTED_FIRMWARE, firmware.getReason());
        }
        if (isLogBufferOff(environment.getLogTag())) {
            return rejected(Failure.LOG_BUFFER_OFF);
        }
        String nativeLibraryDir = environment.getNativeLibraryDir();
        if (nativeLibraryDir == null || nativeLibraryDir.trim().isEmpty()) {
            return rejected(Failure.NATIVE_LIBRARY_DIR_MISSING);
        }

        File loader = new File(nativeLibraryDir, manager.getLoaderName());
        Result rejection = checkAsset(loader, Failure.LOADER_MISSING,
                Failure.LOADER_SYMLINK, Failure.LOADER_NOT_REGULAR);
        if (rejection != null) {
            return rejection;
        }
        try {
            String actualHash = AssetVerifier.sha256(loader);
            if (!manager.getLoaderSha256().equalsIgnoreCase(actualHash)) {
                return rejected(Failure.LOADER_HASH_MISMATCH);
            }
        } catch (IOException e) {
            return inspectionFailed(e);
        }

        File bootstrap = new File(nativeLibraryDir, BOOTSTRAP_BIN);
        rejection = checkAsset(bootstrap, Failure.BOOTSTRAP_MISSING,
                Failure.BOOTSTRAP_SYMLINK, Failure.BOOTSTRAP_NOT_REGULAR);
        if (rejection != null) {
            return rejection;
        }

        File nativeBinary = new File(nativeLibraryDir, NATIVE_BIN);
        rejection = checkAsset(nativeBinary, Failure.NATIVE_MISSING,
                Failure.NATIVE_SYMLINK, Failure.NATIVE_NOT_REGULAR);
        if (rejection != null) {
            return rejection;
        }

        return new Result(new Plan(manager, loader, bootstrap, nativeBinary),
                null, "preflight ready");
    }

    private static Result checkAsset(File file, Failure missing, Failure symlink,
                                     Failure notRegular) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                return rejected(symlink);
            }
            if (!attributes.isRegularFile()) {
                return rejected(notRegular);
            }
            return null;
        } catch (NoSuchFileException e) {
            return rejected(missing);
        } catch (IOException e) {
            return inspectionFailed(e);
        }
    }

    private static boolean isLogBufferOff(String logTag) {
        if (logTag == null) {
            return false;
        }
        String value = logTag.trim();
        if (value.isEmpty()) {
            return false;
        }
        return value.equalsIgnoreCase("s") || value.equalsIgnoreCase("off");
    }

    private static Result rejected(Failure failure) {
        return new Result(null, failure, failure.getMessage());
    }

    private static Result inspectionFailed(IOException error) {
        return new Result(null, Failure.ASSET_INSPECTION_FAILED,
                Failure.ASSET_INSPECTION_FAILED.getMessage() + ": " + error.getMessage());
    }
}
