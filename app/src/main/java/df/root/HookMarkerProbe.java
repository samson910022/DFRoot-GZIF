// Modified 2026-11-24 (DFRoot GZIF): centralize fail-closed no-follow hook-marker inspection. See NOTICE.
package df.root;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

/** Read-only hook-marker inspection shared by manual, boot, and run orchestration. */
final class HookMarkerProbe {
    static final Path HOOK_MARKER = Paths.get("/dev/df");

    private final Path path;

    HookMarkerProbe(Path path) {
        this.path = path;
    }

    static boolean defaultMarkerPresent() throws IOException {
        return new HookMarkerProbe(HOOK_MARKER).isPresent();
    }

    /** Any existing directory entry counts as present; only absence is negative. */
    boolean isPresent() throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }
}
