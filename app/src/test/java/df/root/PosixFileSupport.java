// Modified 2026-11-24 (DFRoot GZIF): capability-gate POSIX-only filesystem and permission tests. See NOTICE.
package df.root;

import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

/** Capability probes for tests whose fixtures require POSIX filesystem semantics. */
final class PosixFileSupport {
    private PosixFileSupport() {}

    static void assumeSymlinksSupported(Path directory) {
        if (!supportsPosixAttributes(directory, "symbolic links")) {
            return;
        }
        Path target = directory.resolve("dfroot-link-target-" + UUID.randomUUID());
        Path link = directory.resolve("dfroot-link-" + UUID.randomUUID());
        try {
            Files.write(target, new byte[] {1});
            Files.createSymbolicLink(link, target.getFileName());
            assumeTrue("filesystem did not retain the symbolic link", Files.isSymbolicLink(link));
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            assumeTrue("symbolic links are unavailable: " + error, false);
        }
    }

    static void assumePosixPermissionsSupported(Path directory) {
        if (!supportsPosixAttributes(directory, "POSIX permissions")) {
            return;
        }
        Path probe = directory.resolve("dfroot-permissions-" + UUID.randomUUID());
        try {
            Files.write(probe, new byte[] {1});
            Set<PosixFilePermission> expected =
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(probe, expected);
            assumeTrue("filesystem did not retain POSIX permissions",
                    Files.getPosixFilePermissions(probe).equals(expected));
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            assumeTrue("POSIX permissions are unavailable: " + error, false);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // TemporaryFolder owns probe cleanup; a restricted delete must not turn a skip into an error.
            }
        }
    }

    static void assumeFifosSupported(Path directory) {
        if (!supportsPosixAttributes(directory, "FIFOs")) {
            return;
        }
        Path fifo = directory.resolve("dfroot-fifo-" + UUID.randomUUID());
        try {
            Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(mkfifo.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int exitCode = mkfifo.waitFor();
            assumeTrue("mkfifo is unavailable or failed: " + output, exitCode == 0);
            BasicFileAttributes attributes = Files.readAttributes(
                    fifo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            assumeTrue("filesystem did not create a FIFO", attributes.isOther());
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            assumeTrue("FIFOs are unavailable: " + error, false);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            assumeTrue("FIFO capability probe was interrupted", false);
        } finally {
            try {
                Files.deleteIfExists(fifo);
            } catch (IOException ignored) {
                // TemporaryFolder owns probe cleanup; a restricted delete must not turn a skip into an error.
            }
        }
    }

    private static boolean supportsPosixAttributes(Path directory, String capability) {
        try {
            assumeTrue(capability + " require a POSIX filesystem",
                    Files.getFileStore(directory).supportsFileAttributeView("posix"));
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            assumeTrue(capability + " capability is unknown: " + error, false);
            return false;
        }
    }
}
