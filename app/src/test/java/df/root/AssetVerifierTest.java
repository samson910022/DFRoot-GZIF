// Modified 2026-11-24 (DFRoot GZIF): test pinned assets and capability-gate hostile POSIX entry types. See NOTICE.
package df.root;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

public class AssetVerifierTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void bothAuthoritativePinsAcceptRepositoryBytesWithoutMutation() throws Exception {
        for (SuManager manager : SuManager.values()) {
            Path loader = temporaryFolder.newFile(manager.getLoaderName()).toPath();
            byte[] repositoryBytes = Files.readAllBytes(TestFixtures.bundledLoader(manager));
            Files.write(loader, repositoryBytes);
            Snapshot before = Snapshot.capture(loader);

            AssetVerifier.verifyPinned(manager, loader.toFile());

            assertEquals(manager.getLoaderSha256(), AssetVerifier.sha256(loader.toFile()));
            assertArrayEquals(repositoryBytes, Files.readAllBytes(loader));
            assertEquals(before, Snapshot.capture(loader));
        }
    }

    @Test
    public void crossManagerPinsAreRejected() throws Exception {
        for (SuManager source : SuManager.values()) {
            for (SuManager target : SuManager.values()) {
                if (source == target) {
                    continue;
                }
                Path loader = temporaryFolder.newFile(source + "-as-" + target.getLoaderName()).toPath();
                Files.copy(TestFixtures.bundledLoader(source), loader,
                        StandardCopyOption.REPLACE_EXISTING);
                Snapshot before = Snapshot.capture(loader);

                IOException error = assertThrows(IOException.class,
                        () -> AssetVerifier.verifyPinned(target, loader.toFile()));

                assertTrue(error.getMessage().contains(target.getLoaderName()));
                assertEquals(before, Snapshot.capture(loader));
            }
        }
    }

    @Test
    public void sameSizeCorruptionNearTheEndIsRejectedWithoutChangingMetadata() throws Exception {
        Path loader = temporaryFolder.newFile("corrupt-loader").toPath();
        byte[] original = Files.readAllBytes(TestFixtures.bundledLoader(SuManager.CLASSIC));
        byte[] corrupted = original.clone();
        corrupted[corrupted.length - 1] ^= 0x01;
        Files.write(loader, corrupted);
        Snapshot before = Snapshot.capture(loader);

        assertThrows(IOException.class,
                () -> AssetVerifier.verifyPinned(SuManager.CLASSIC, loader.toFile()));

        assertEquals(original.length, Files.size(loader));
        assertArrayEquals(java.util.Arrays.copyOf(original, original.length - 1),
                java.util.Arrays.copyOf(Files.readAllBytes(loader), original.length - 1));
        assertEquals(before, Snapshot.capture(loader));
    }

    @Test
    public void symbolicLinkIsRejectedWithoutFollowingIt() throws Exception {
        PosixFileSupport.assumeSymlinksSupported(temporaryFolder.getRoot().toPath());
        Path target = temporaryFolder.newFile("target").toPath();
        Files.copy(TestFixtures.bundledLoader(SuManager.CLASSIC), target,
                StandardCopyOption.REPLACE_EXISTING);
        Path link = temporaryFolder.getRoot().toPath().resolve("loader-link");
        Files.createSymbolicLink(link, target);

        assertThrows(IOException.class, () -> AssetVerifier.requireRegularNonSymlink(link.toFile()));
        assertThrows(IOException.class,
                () -> AssetVerifier.verifyPinned(SuManager.CLASSIC, link.toFile()));
    }

    @Test
    public void directoryIsRejected() throws Exception {
        File directory = temporaryFolder.newFolder("loader-directory");

        assertThrows(IOException.class, () -> AssetVerifier.requireRegularNonSymlink(directory));
    }

    @Test
    public void fifoIsRejectedAsASecondNonRegularType() throws Exception {
        PosixFileSupport.assumeFifosSupported(temporaryFolder.getRoot().toPath());
        Path fifo = temporaryFolder.getRoot().toPath().resolve("loader.fifo");
        Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertEquals(0, mkfifo.waitFor());
        try {
            IOException error = assertThrows(IOException.class,
                    () -> AssetVerifier.requireRegularNonSymlink(fifo.toFile()));
            assertTrue(error.getMessage().contains("not a regular file"));
        } finally {
            Files.deleteIfExists(fifo);
        }
    }

    @Test
    public void missingFileRemainsAnInspectionError() {
        File missing = new File(temporaryFolder.getRoot(), "missing");

        assertThrows(NoSuchFileException.class,
                () -> AssetVerifier.requireRegularNonSymlink(missing));
    }

    private static final class Snapshot {
        final byte[] bytes;
        final long size;
        final long lastModified;
        final boolean regular;
        final boolean symbolicLink;

        Snapshot(byte[] bytes, long size, long lastModified, boolean regular, boolean symbolicLink) {
            this.bytes = bytes;
            this.size = size;
            this.lastModified = lastModified;
            this.regular = regular;
            this.symbolicLink = symbolicLink;
        }

        static Snapshot capture(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            byte[] bytes = attributes.isRegularFile() ? Files.readAllBytes(path) : null;
            return new Snapshot(bytes, attributes.size(), attributes.lastModifiedTime().toMillis(),
                    attributes.isRegularFile(), attributes.isSymbolicLink());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) {
                return false;
            }
            Snapshot snapshot = (Snapshot) other;
            return size == snapshot.size && lastModified == snapshot.lastModified
                    && regular == snapshot.regular && symbolicLink == snapshot.symbolicLink
                    && java.util.Arrays.equals(bytes, snapshot.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(size, lastModified, regular, symbolicLink,
                    java.util.Arrays.hashCode(bytes));
        }
    }
}
