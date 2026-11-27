// Modified 2026-11-24 (DFRoot GZIF): verify no-follow marker inspection and capability-gate the symlink case. See NOTICE.
package df.root;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class HookMarkerProbeTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void regularFileMeansPresent() throws Exception {
        Path marker = temporaryFolder.newFile("marker").toPath();

        assertTrue(new HookMarkerProbe(marker).isPresent());
    }

    @Test
    public void directoryMeansPresent() throws Exception {
        Path marker = temporaryFolder.newFolder("marker").toPath();

        assertTrue(new HookMarkerProbe(marker).isPresent());
    }

    @Test
    public void evenDanglingSymlinkMeansPresent() throws Exception {
        PosixFileSupport.assumeSymlinksSupported(temporaryFolder.getRoot().toPath());
        Path marker = temporaryFolder.getRoot().toPath().resolve("marker");
        Files.createSymbolicLink(marker, temporaryFolder.getRoot().toPath().resolve("missing"));

        assertTrue(new HookMarkerProbe(marker).isPresent());
    }

    @Test
    public void onlyNoSuchFileMeansAbsent() throws Exception {
        Path marker = temporaryFolder.getRoot().toPath().resolve("missing");

        assertFalse(new HookMarkerProbe(marker).isPresent());
    }

    @Test
    public void inspectionErrorsPropagateInsteadOfMeaningAbsent() throws Exception {
        Path notDirectory = temporaryFolder.newFile("not-directory").toPath();
        Path marker = notDirectory.resolve("marker");

        assertThrows(IOException.class, () -> new HookMarkerProbe(marker).isPresent());
    }
}
