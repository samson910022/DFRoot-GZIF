// Modified 2026-11-24 (DFRoot GZIF): add no-follow regular-file checks and SHA-256 pin verification. See NOTICE.
package df.root;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Read-only asset verification shared by preflight and staging. */
final class AssetVerifier {
    private AssetVerifier() {}

    static void requireRegularNonSymlink(File file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            throw new IOException("symbolic link is not allowed: " + file);
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("not a regular file: " + file);
        }
    }

    static String sha256(File file) throws IOException {
        requireRegularNonSymlink(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(
                    Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS), digest)) {
                byte[] buffer = new byte[8192];
                while (input.read(buffer) != -1) {
                    // DigestInputStream updates the digest as bytes are consumed.
                }
            }
            // Re-check the path after reading so a symlink or non-regular replacement fails closed.
            requireRegularNonSymlink(file);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static void verifyPinned(SuManager manager, File loader) throws IOException {
        String actual = sha256(loader);
        if (!manager.getLoaderSha256().equalsIgnoreCase(actual)) {
            throw new IOException("SHA-256 mismatch for " + manager.getLoaderName());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }
}
