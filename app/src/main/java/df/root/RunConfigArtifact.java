// Modified 2026-11-24 (DFRoot GZIF): atomically publish the admitted native run-config snapshot. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): reject '=' in text values and require 0600 only after chmod. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): gate publication on the persisted admitted operation ID. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): null-safe persisted identity comparison in the publish gate. See NOTICE.
package df.root;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Publishes one immutable admitted snapshot for the native bootstrap and exploit. */
final class RunConfigArtifact {
    static final String RUN_CONFIG = "run_config";
    static final String HEADER = "DFROOT_RUN_CONFIG 1";
    static final int MAX_ARTIFACT_BYTES = 8192;
    static final int MAX_OPERATION_ID_LENGTH = 128;
    static final int MAX_TEXT_LENGTH = 256;

    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Pattern OPERATION_ID = Pattern.compile(
            "[A-Za-z0-9._:-]{1," + MAX_OPERATION_ID_LENGTH + "}");

    private RunConfigArtifact() {}

    static void publish(Context context, String operationId, RunConfig config,
                        RunPreflight.Plan plan, RunConfig.ExecutionMode mode) throws IOException {
        Objects.requireNonNull(context, "context");
        File root = context.getFilesDir() == null ? null : context.getFilesDir().getParentFile();
        if (root == null) {
            throw new IOException("device-protected app root is unavailable");
        }
        publish(root.toPath(), operationId, config, plan, mode, Build.FINGERPRINT,
                System.getProperty("os.version", "unknown"), FileSystem.DEFAULT);
    }

    /**
     * Production entry point: refuse to publish unless the DE attempt store's
     * unconfirmed record names this exact operation. The record ID, not the
     * caller's ID, is the authority for the native artifact.
     */
    static void publishAuthorized(Context context, String operationId, RunConfig config,
                                  RunPreflight.Plan plan, RunConfig.ExecutionMode mode)
            throws IOException {
        File root = context.getFilesDir() == null ? null : context.getFilesDir().getParentFile();
        if (root == null) {
            throw new IOException("device-protected app root is unavailable");
        }
        publishAuthorized(root.toPath(), operationId, config, plan, mode,
                SharedPreferencesOperationAttemptStore.forDeviceProtectedStorage(context),
                Build.FINGERPRINT, System.getProperty("os.version", "unknown"), FileSystem.DEFAULT);
    }

    /** Deterministic seam for the authorized publish gate. */
    static void publishAuthorized(Path directory, String operationId, RunConfig config,
                                  RunPreflight.Plan plan, RunConfig.ExecutionMode mode,
                                  OperationAttemptStore store, String fingerprint, String kernel,
                                  FileSystem fileSystem) throws IOException {
        OperationAttempt admitted;
        try {
            admitted = Objects.requireNonNull(store, "store").load();
        } catch (OperationAttemptStore.Failure e) {
            throw new IOException("persisted admitted attempt is unreadable", e);
        }
        if (admitted == null || !admitted.isUnconfirmed()
                || !admitted.getOperationId().equals(operationId)) {
            throw new IOException("run_config publish refused: operation " + operationId
                    + " is not the persisted admitted attempt");
        }
        publish(directory, operationId, config, plan, mode, fingerprint, kernel, fileSystem);
    }

    static void publish(Path directory, String operationId, RunConfig config,
                        RunPreflight.Plan plan, RunConfig.ExecutionMode mode,
                        String fingerprint, String kernel, FileSystem fileSystem)
            throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(fileSystem, "fileSystem");
        validateSnapshot(config, plan, mode, operationId, fingerprint, kernel);

        byte[] bytes = serialize(config, plan, mode, operationId, fingerprint, kernel)
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("run-config artifact exceeds maximum size");
        }
        requireDirectory(directory, fileSystem);

        Path target = directory.resolve(RUN_CONFIG);
        Path temporary = null;
        try {
            temporary = fileSystem.createTempFile(directory, ".run_config.", ".tmp",
                    PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
            // Symlink/type defense comes first: never chmod a path an attacker
            // swapped for a link. The 0600 requirement is checked only after the
            // explicit chmod, because some filesystems ignore createTempFile attrs.
            requireRegularNonSymlink(temporary, fileSystem);
            fileSystem.setPosixFilePermissions(temporary, PRIVATE_PERMISSIONS);
            requirePrivateRegularFile(temporary, fileSystem);

            try (FileChannel channel = fileSystem.open(temporary, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                fileSystem.write(channel, bytes);
                fileSystem.force(channel);
            }
            requirePrivateRegularFile(temporary, fileSystem);

            fileSystem.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            fileSystem.syncDirectory(directory);
            requirePrivateRegularFile(target, fileSystem);
        } catch (IOException failure) {
            cleanTemp(fileSystem, temporary, failure);
            throw failure;
        } catch (RuntimeException failure) {
            cleanTemp(fileSystem, temporary, failure);
            throw failure;
        }
    }

    static boolean isValidOperationId(String operationId) {
        return operationId != null && OPERATION_ID.matcher(operationId).matches();
    }

    private static void validateSnapshot(RunConfig config, RunPreflight.Plan plan,
                                         RunConfig.ExecutionMode mode, String operationId,
                                         String fingerprint, String kernel) {
        if (!isValidOperationId(operationId)) {
            throw new IllegalArgumentException("operation ID has an unsafe charset or length");
        }
        SuManager manager = SuManager.fromPackage(config.getManagerPackage());
        if (manager == null) {
            throw new IllegalArgumentException("run config has an unsupported manager");
        }
        if (plan.getManager() != manager) {
            throw new IllegalArgumentException("run config manager conflicts with preflight plan");
        }
        config.validateFor(mode);
        validateText("fingerprint", fingerprint);
        validateText("kernel", kernel);
    }

    private static void validateText(String field, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > MAX_TEXT_LENGTH
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint) || codePoint == '=') {
                throw new IllegalArgumentException(field + " contains a control character or '='");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static String serialize(RunConfig config, RunPreflight.Plan plan,
                                    RunConfig.ExecutionMode mode, String operationId,
                                    String fingerprint, String kernel) {
        return HEADER + "\n"
                + "operation_id=" + operationId + "\n"
                + "generation=" + config.getGeneration() + "\n"
                + "mode=" + modeValue(mode) + "\n"
                + "manager=" + plan.getManager().getPackageName() + "\n"
                + "soft_reboot=" + (config.isSoftReboot() ? "1" : "0") + "\n"
                + "disable_modules=" + (config.isDisableModules() ? "1" : "0") + "\n"
                + "ack_version=" + config.getSoftRebootAcknowledgementVersion() + "\n"
                + "loader_sha256=" + plan.getManager().getLoaderSha256() + "\n"
                + "fingerprint=" + fingerprint + "\n"
                + "kernel=" + kernel + "\n";
    }

    private static String modeValue(RunConfig.ExecutionMode mode) {
        if (mode == RunConfig.ExecutionMode.MANUAL) {
            return "manual";
        }
        if (mode == RunConfig.ExecutionMode.UNATTENDED_BOOT) {
            return "unattended_boot";
        }
        throw new IllegalArgumentException("unsupported execution mode: " + mode);
    }

    private static void requireDirectory(Path directory, FileSystem fileSystem)
            throws IOException {
        BasicFileAttributes attributes = fileSystem.readAttributes(directory);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("run-config root must be a real directory: " + directory);
        }
    }

    private static void requirePrivateRegularFile(Path path, FileSystem fileSystem)
            throws IOException {
        requireRegularNonSymlink(path, fileSystem);
        if (!PRIVATE_PERMISSIONS.equals(fileSystem.getPosixPermissions(path))) {
            throw new IOException("run-config artifact permissions must be 0600: " + path);
        }
    }

    /** Type/link check used before any chmod so a symlink target is never touched. */
    private static void requireRegularNonSymlink(Path path, FileSystem fileSystem)
            throws IOException {
        BasicFileAttributes attributes = fileSystem.readAttributes(path);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("run-config artifact must be a regular non-symlink: " + path);
        }
    }

    private static void cleanTemp(FileSystem fileSystem, Path temporary, Throwable failure) {
        if (temporary == null) {
            return;
        }
        try {
            fileSystem.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /** Narrow filesystem seam for deterministic failure and directory-fsync tests. */
    abstract static class FileSystem {
        static final FileSystem DEFAULT = new FileSystem() {};

        Path createTempFile(Path directory, String prefix, String suffix,
                            FileAttribute<?>... attributes) throws IOException {
            return Files.createTempFile(directory, prefix, suffix, attributes);
        }

        FileChannel open(Path path, OpenOption... options) throws IOException {
            return FileChannel.open(path, options);
        }

        void write(FileChannel channel, byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        void force(FileChannel channel) throws IOException {
            channel.force(true);
        }

        void move(Path source, Path target, StandardCopyOption... options) throws IOException {
            Files.move(source, target, options);
        }

        void syncDirectory(Path directory) throws IOException {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
        }

        BasicFileAttributes readAttributes(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        Set<PosixFilePermission> getPosixPermissions(Path path) throws IOException {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        }

        void setPosixFilePermissions(Path path, Set<PosixFilePermission> permissions)
                throws IOException {
            Files.setPosixFilePermissions(path, permissions);
        }

        void deleteIfExists(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
