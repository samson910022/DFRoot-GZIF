import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Writes Android signing material without putting credentials in arguments or logs.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code ci}: validate KEYSTORE and publish a new keystore/properties pair.</li>
 *   <li>{@code local}: verify an existing private keystore and publish new properties.</li>
 *   <li>{@code verify-local}: verify the existing keystore without writing properties.</li>
 *   <li>{@code create-local}: atomically create and publish a private JKS.</li>
 * </ul>
 *
 * <p>The optional second argument is the project root; it defaults to the current directory.
 * The canonical ancestor chain is validated before staging. Same-user concurrent
 * modification after validation is out of scope; foreign users cannot substitute entries.
 */
public class WriteSigningProperties {
    private static final Set<PosixFilePermission> PRIVATE_MODE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_MODE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final String TEMPORARY_ALIAS = "dfroot-signing-material";

    private WriteSigningProperties() {}

    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2) {
                throw new IllegalArgumentException(
                        "Usage: java scripts/WriteSigningProperties.java " +
                                "{ci|local|verify-local|create-local} [project-root]");
            }

            Path root = (args.length == 2 ? Path.of(args[1]) : Path.of(""))
                    .toAbsolutePath()
                    .normalize()
                    .toRealPath();
            validateRoot(root);

            switch (args[0]) {
                case "ci" -> writeCiMaterial(root);
                case "local" -> writeLocalProperties(root);
                case "verify-local" -> verifyLocalKeystore(root, true);
                case "create-local" -> createLocalKeystore(root);
                default -> throw new IllegalArgumentException(
                        "Unknown mode '" + args[0] + "'; expected ci, local, verify-local, or create-local");
            }
        } catch (Exception error) {
            String message = error.getMessage();
            System.err.println("ERROR: " + (message == null ? error.getClass().getSimpleName() : message));
            System.exit(1);
        }
    }

    private static void writeCiMaterial(Path root) throws Exception {
        Map<String, String> environment = readEnvironment();
        String encodedKeystore = requireNonempty(environment, "KEYSTORE");
        String storePassword = requireNonempty(environment, "KEYSTORE_PASSWORD");
        String alias = requireNonempty(environment, "KEY_ALIAS");
        String keyPassword = requireNonempty(environment, "KEY_PASSWORD");

        byte[] keystore;
        try {
            // Permit conventional CR/LF line wrapping, but no ignored non-base64 characters.
            String normalized = encodedKeystore.replace("\r", "").replace("\n", "");
            keystore = Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("KEYSTORE must be valid base64", error);
        }
        if (keystore.length == 0) {
            throw new IllegalArgumentException("KEYSTORE must decode to a nonempty keystore");
        }
        validateKeystore(
                keystore, storePassword, alias, keyPassword,
                "KEYSTORE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD");

        Properties properties = signingProperties(storePassword, alias, keyPassword);
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        // Properties.store(OutputStream) emits ISO-8859-1 with UTF-16 surrogate escapes,
        // matching Gradle's Properties.load(InputStream) without locale-dependent Bash.
        properties.store(serialized, "Generated signing configuration; do not commit.");

        requireAbsent(root, "keystore.jks");
        requireAbsent(root, "signing.properties");

        StagedFile stagedKeystore = null;
        StagedFile stagedProperties = null;
        try {
            // Stage and verify both files before publishing either one.
            stagedKeystore = stageFile(root, "keystore.jks", ".keystore.jks-", keystore);
            stagedProperties = stageFile(root, "signing.properties", ".signing.properties-", serialized.toByteArray());
            stagedKeystore.publishWithoutReplacement();
            stagedProperties.publishWithoutReplacement();
        } catch (Exception error) {
            rollBack(stagedProperties, error);
            rollBack(stagedKeystore, error);
            throw error;
        } finally {
            cleanUp(stagedProperties);
            cleanUp(stagedKeystore);
        }
        System.out.println("Wrote keystore.jks and signing.properties with verified POSIX mode 0600.");
    }

    private static void writeLocalProperties(Path root) throws Exception {
        verifyLocalKeystore(root, false);
        requireAbsent(root, "signing.properties");

        Map<String, String> environment = readEnvironment();
        String password = requireNonempty(environment, "SIGN_PASS");
        String alias = requireNonempty(environment, "SIGN_ALIAS");
        Properties properties = signingProperties(password, alias, password);
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        properties.store(serialized, "Generated signing configuration; do not commit.");

        StagedFile staged = null;
        try {
            staged = stageFile(root, "signing.properties", ".signing.properties-", serialized.toByteArray());
            staged.publishWithoutReplacement();
        } catch (Exception error) {
            rollBack(staged, error);
            throw error;
        } finally {
            cleanUp(staged);
        }
        System.out.println("Wrote signing.properties with verified POSIX mode 0600.");
    }

    private static void verifyLocalKeystore(Path root, boolean report) throws Exception {
        Map<String, String> environment = readEnvironment();
        String password = requireNonempty(environment, "SIGN_PASS");
        String alias = requireNonempty(environment, "SIGN_ALIAS");

        Path keystore = root.resolve("keystore.jks");
        BasicFileAttributes attributes = Files.readAttributes(
                keystore, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("keystore.jks must be a regular file, not a symlink or directory");
        }
        if (attributes.size() == 0) {
            throw new IOException("keystore.jks is empty");
        }
        verifyPrivateFile(keystore, "existing keystore.jks");

        byte[] content;
        try (InputStream input = Files.newInputStream(keystore, LinkOption.NOFOLLOW_LINKS)) {
            content = input.readAllBytes();
        }
        validateKeystore(
                content, password, alias, password,
                "keystore.jks", "SIGN_PASS", "SIGN_ALIAS", "SIGN_PASS");
        boolean hasProperties = verifyLocalPropertiesIfPresent(root, password, alias);
        if (report) {
            System.out.println("Verified existing keystore.jks and its private-key entry" +
                    (hasProperties ? " and signing.properties." : "."));
        }
    }

    private static boolean verifyLocalPropertiesIfPresent(Path root, String password, String alias)
            throws IOException {
        Path propertiesPath = root.resolve("signing.properties");
        if (!Files.exists(propertiesPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            verifyPrivateFile(propertiesPath, "existing signing.properties");
            Properties actual = new Properties();
            try (InputStream input = Files.newInputStream(propertiesPath, LinkOption.NOFOLLOW_LINKS)) {
                actual.load(input);
            }
            Properties expected = signingProperties(password, alias, password);
            if (!expected.equals(actual)) {
                throw new IOException("signing.properties values do not match SIGN_PASS/SIGN_ALIAS");
            }
            return true;
        } catch (Exception error) {
            throw new IOException(
                    "signing.properties is invalid or stale; remove it and regenerate it with " +
                            "make signing.properties before signing",
                    error);
        }
    }

    private static void createLocalKeystore(Path root) throws Exception {
        Map<String, String> environment = readEnvironment();
        String password = requireNonempty(environment, "SIGN_PASS");
        String alias = requireNonempty(environment, "SIGN_ALIAS");

        // ProcessBuilder encodes environment values using the JVM's native encoding.
        // Make invokes this mode under C.UTF-8 so non-BMP credentials reach keytool unchanged.
        if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(System.getProperty("native.encoding"))) {
            throw new IOException(
                    "create-local requires a UTF-8 locale so keytool receives credentials unchanged; " +
                            "invoke it with LC_ALL=C.UTF-8");
        }

        requireAbsent(root, "keystore.jks");

        // keytool refuses to overwrite an existing empty file. Let it create the JKS
        // inside a verified 0700 directory, then serialize and stage the completed JKS.
        Path temporaryDirectory = createPrivateTemporaryDirectory(root);
        Path keytoolKeystore = temporaryDirectory.resolve("keystore.jks");
        StagedFile staged = null;
        try {
            generateWithKeytool(keytoolKeystore, password);
            verifyPrivateDirectory(temporaryDirectory);
            Files.setPosixFilePermissions(keytoolKeystore, PRIVATE_MODE);
            verifyPrivateFile(keytoolKeystore, "keytool-created temporary keystore");
            byte[] generated = readPrivateFile(keytoolKeystore);
            byte[] renamed = renameKeystoreAlias(generated, password, alias);
            validateKeystore(
                    renamed, password, alias, password,
                    "generated keystore.jks", "SIGN_PASS", "SIGN_ALIAS", "SIGN_PASS");

            staged = stageFile(root, "keystore.jks", ".keystore.jks-", renamed);
            staged.publishWithoutReplacement();
        } catch (Exception error) {
            rollBack(staged, error);
            throw error;
        } finally {
            cleanUp(staged);
            deleteQuietly(keytoolKeystore);
            deleteQuietly(temporaryDirectory);
        }
        System.out.println("Created keystore.jks with verified POSIX mode 0600.");
    }

    private static void generateWithKeytool(Path keystore, String password) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        ProcessBuilder builder = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-noprompt",
                "-keystore", keystore.toString(),
                "-storetype", "JKS",
                "-storepass:env", "SIGN_PASS",
                "-keypass:env", "SIGN_PASS",
                "-alias", TEMPORARY_ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-dname", "CN=DFRoot");
        builder.environment().put("SIGN_PASS", password);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process = builder.start();
        process.getOutputStream().close();
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException(
                            "keytool timed out while creating keystore.jks and did not exit after destroyForcibly");
                }
                throw new IOException("keytool timed out while creating keystore.jks");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException(
                            "keytool was interrupted and did not exit after destroyForcibly", error);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("keytool was interrupted while creating keystore.jks", error);
        }
        if (process.exitValue() != 0) {
            throw new IOException(
                    "keytool failed to create keystore.jks; check that SIGN_PASS meets keytool " +
                            "password requirements and that the JDK keytool is available");
        }
    }

    private static byte[] renameKeystoreAlias(byte[] content, String password, String alias) throws Exception {
        char[] passwordCharacters = password.toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("JKS");
            try (InputStream input = new ByteArrayInputStream(content)) {
                store.load(input, passwordCharacters);
            }
            if (!store.containsAlias(TEMPORARY_ALIAS)) {
                throw new IOException("keytool did not create the expected private-key entry");
            }
            Key key = store.getKey(TEMPORARY_ALIAS, passwordCharacters);
            Certificate[] chain = store.getCertificateChain(TEMPORARY_ALIAS);
            if (!(key instanceof PrivateKey privateKey) || chain == null || chain.length == 0) {
                throw new IOException("keytool did not create a usable private-key entry");
            }

            store.deleteEntry(TEMPORARY_ALIAS);
            store.setEntry(
                    alias,
                    new KeyStore.PrivateKeyEntry(privateKey, chain),
                    new KeyStore.PasswordProtection(passwordCharacters));
            ByteArrayOutputStream renamed = new ByteArrayOutputStream();
            store.store(renamed, passwordCharacters);
            return renamed.toByteArray();
        } finally {
            java.util.Arrays.fill(passwordCharacters, '\0');
        }
    }

    private static void validateKeystore(
            byte[] content,
            String storePassword,
            String alias,
            String keyPassword,
            String keystoreName,
            String storePasswordName,
            String aliasName,
            String keyPasswordName) throws GeneralSecurityException, IOException {
        char[] storeCharacters = storePassword.toCharArray();
        char[] keyCharacters = keyPassword.toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("JKS");
            try (InputStream input = new ByteArrayInputStream(content)) {
                store.load(input, storeCharacters);
            }
            if (!store.containsAlias(alias)) {
                throw new IOException(aliasName + " does not identify an entry in " + keystoreName);
            }
            if (!store.isKeyEntry(alias)) {
                throw new IOException(aliasName + " in " + keystoreName + " must refer to a private-key entry");
            }

            final Key key;
            try {
                key = store.getKey(alias, keyCharacters);
            } catch (UnrecoverableKeyException error) {
                throw new IOException(keyPasswordName + " could not unlock the private key for " + aliasName, error);
            }
            Certificate[] chain = store.getCertificateChain(alias);
            if (!(key instanceof PrivateKey) || chain == null || chain.length == 0) {
                throw new IOException(aliasName + " in " + keystoreName + " has no usable private-key entry");
            }
        } catch (IOException error) {
            if (error.getMessage() != null &&
                    (error.getMessage().contains(aliasName) || error.getMessage().contains(keyPasswordName))) {
                throw error;
            }
            throw new IOException(keystoreName + " could not be loaded as JKS with " + storePasswordName, error);
        } finally {
            java.util.Arrays.fill(storeCharacters, '\0');
            java.util.Arrays.fill(keyCharacters, '\0');
        }
    }

    private static Properties signingProperties(String storePassword, String alias, String keyPassword) {
        Properties properties = new Properties();
        properties.setProperty("KEYSTORE_FILE", "../keystore.jks");
        properties.setProperty("KEYSTORE_PASSWORD", storePassword);
        properties.setProperty("KEY_ALIAS", alias);
        properties.setProperty("KEY_PASSWORD", keyPassword);
        return properties;
    }

    private static StagedFile stageFile(Path root, String targetName, String prefix, byte[] content) throws IOException {
        Path temporary = root.resolve(prefix + UUID.randomUUID() + ".tmp");
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileChannel channel = FileChannel.open(
                temporary, options, PosixFilePermissions.asFileAttribute(PRIVATE_MODE));
        StagedFile staged = new StagedFile(temporary, root.resolve(targetName), channel);
        try {
            verifyPrivateFile(temporary, "new signing-material temporary file");

            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            verifyPrivateFile(temporary, "staged signing-material file");
            return staged;
        } catch (Exception error) {
            staged.cleanUp();
            if (error instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not stage signing material through an open descriptor", error);
        }
    }

    private static Path createPrivateTemporaryDirectory(Path root) throws IOException {
        Path temporary = Files.createTempDirectory(
                root, ".signing-keystore-", PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_MODE));
        try {
            verifyPrivateDirectory(temporary);
            return temporary;
        } catch (Exception error) {
            deleteQuietly(temporary);
            if (error instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not create a POSIX 0700 signing-material temporary directory", error);
        }
    }

    private static void verifyPrivateDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException("Temporary signing path is not a directory");
        }
        requireCurrentOwner(directory, "temporary signing directory");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        if (!permissions.equals(PRIVATE_DIRECTORY_MODE)) {
            throw new IOException(
                    "Temporary signing directory must have POSIX mode 0700; reported " +
                            PosixFilePermissions.toString(permissions) +
                            ". This filesystem cannot enforce/report private signing material");
        }
    }

    private static byte[] readPrivateFile(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return input.readAllBytes();
        }
    }

    private static void validateRoot(Path root) throws IOException {
        if (!Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class)) {
            throw new IOException(
                    "Filesystem for " + root + " cannot enforce/report POSIX permissions; " +
                            "refusing to write signing material");
        }
        // root is canonical (toRealPath), so every parent is checked through /.
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
            validateAncestor(ancestor);
        }
    }

    private static void validateAncestor(Path ancestor) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException("Unsafe ancestor " + ancestor + " is not a directory");
        }
        boolean rootOwned = isRootOwned(ancestor);
        if (!rootOwned) {
            requireCurrentOwner(ancestor, "ancestor " + ancestor);
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(ancestor);
        if ((permissions.contains(PosixFilePermission.GROUP_WRITE) ||
                permissions.contains(PosixFilePermission.OTHERS_WRITE)) &&
                !(rootOwned && hasStickyBit(ancestor))) {
            throw new IOException(
                    "Unsafe ancestor " + ancestor + " is writable by group/other without " +
                            "root ownership and the sticky bit; refusing to stage signing material");
        }
    }

    private static boolean isRootOwned(Path path) throws IOException {
        Object uid = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        return uid instanceof Number number && number.intValue() == 0;
    }

    private static boolean hasStickyBit(Path path) throws IOException {
        Object mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        return mode instanceof Number number && (number.intValue() & 01000) != 0;
    }

    private static void requireAbsent(Path root, String fileName) throws IOException {
        if (Files.exists(root.resolve(fileName), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(fileName + " already exists; refusing to overwrite signing material");
        }
    }

    private static void verifyPrivateFile(Path path, String description) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException(description + " is not a regular file");
        }
        requireCurrentOwner(path, description);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                path, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.equals(PRIVATE_MODE)) {
            throw new IOException(
                    description + " must have POSIX mode 0600; reported " +
                            PosixFilePermissions.toString(permissions) +
                            ". This filesystem cannot enforce/report private signing material");
        }
    }

    private static void requireCurrentOwner(Path path, String description) throws IOException {
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        String currentUser = ProcessHandle.current().info().user()
                .orElseThrow(() -> new IOException("Could not determine the current user"));
        if (!owner.getName().equals(currentUser)) {
            throw new IOException(
                    description + " must be owned by the current user (" + currentUser + "); reported " +
                            owner.getName());
        }
    }

    private static void rollBack(StagedFile staged, Exception original) {
        if (staged == null) {
            return;
        }
        try {
            staged.rollBackPublishedFile();
        } catch (IOException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void cleanUp(StagedFile staged) {
        if (staged != null) {
            staged.cleanUp();
        }
    }

    private static Map<String, String> readEnvironment() throws IOException {
        Path processEnvironment = Path.of("/proc/self/environ");
        if (!Files.isRegularFile(processEnvironment)) {
            return System.getenv();
        }

        // System.getenv() uses the JVM's locale-dependent native encoding. Reading the
        // Linux environment as UTF-8 bytes preserves CI credentials even under LC_ALL=C.
        Map<String, String> environment = new HashMap<>();
        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(processEnvironment)))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Process environment must be valid UTF-8", error);
        }
        for (String entry : decoded.split("\0")) {
            int separator = entry.indexOf('=');
            if (separator > 0) {
                environment.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        return environment;
    }

    private static String requireNonempty(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be set and nonempty (whitespace is valid)");
        }
        return value;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the original, more useful failure.
        }
    }

    private static final class StagedFile {
        private final Path temporary;
        private final Path target;
        private final FileChannel channel;
        private boolean published;

        private StagedFile(Path temporary, Path target, FileChannel channel) {
            this.temporary = temporary;
            this.target = target;
            this.channel = channel;
        }

        private void publishWithoutReplacement() throws IOException {
            try {
                // link() is atomic and fails if target exists; it never replaces a file,
                // directory, or symlink created concurrently.
                Files.createLink(target, temporary);
                published = true;
            } catch (FileAlreadyExistsException error) {
                throw new IOException(target.getFileName() + " already exists; refusing to overwrite", error);
            } catch (IOException error) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(target.getFileName() + " already exists; refusing to overwrite", error);
                }
                throw new IOException(
                        "Filesystem for " + target + " cannot atomically publish without replacement; " +
                                "refusing to fall back to a non-atomic or replacing move",
                        error);
            }
            try {
                if (!Files.isSameFile(temporary, target)) {
                    throw new IOException("Published signing file no longer refers to the staged file");
                }
                verifyPrivateFile(target, target.getFileName().toString());
            } catch (Exception error) {
                try {
                    rollBackPublishedFile();
                } catch (IOException rollbackFailure) {
                    error.addSuppressed(rollbackFailure);
                }
                if (error instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Could not verify final signing-file permissions", error);
            }
        }

        private void rollBackPublishedFile() throws IOException {
            if (!published) {
                return;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(temporary, target)) {
                Files.delete(target);
            }
            published = false;
        }

        private void cleanUp() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The publication result has already been determined.
            }
            deleteQuietly(temporary);
        }
    }
}
