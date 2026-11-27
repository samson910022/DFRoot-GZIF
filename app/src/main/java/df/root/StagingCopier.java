// Modified 2026-11-24 (DFRoot GZIF): exclusive no-follow staging into fresh non-executable inodes. See NOTICE.
package df.root;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Copy boundary used by staging; injectable so tests can corrupt bytes after the write. */
interface StagingCopier {
    StagingCopier DEFAULT = (source, destination) -> {
        try (InputStream input = Files.newInputStream(
                source.toPath(), LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Channels.newOutputStream(Files.newByteChannel(
                     destination.toPath(),
                     Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                     PosixFilePermissions.asFileAttribute(Set.of(
                             PosixFilePermission.OWNER_READ,
                             PosixFilePermission.OWNER_WRITE))))) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                output.write(buffer, 0, count);
            }
        }
    };

    void copy(File source, File destination) throws IOException;
}
