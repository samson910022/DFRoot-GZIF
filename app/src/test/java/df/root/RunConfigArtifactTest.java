// Modified 2026-11-24 (DFRoot GZIF): specify atomic private run-config publication and failure cleanup. See NOTICE.
// Modified 2026-11-25 (DFRoot GZIF): cover '=' rejection and the no-follow-before-chmod ordering. See NOTICE.
package df.root;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class RunConfigArtifactTest {
    private static final String OPERATION_ID = "7d9f2d8e-42e4-4df3-a3dd-45cf5db3f501";
    private static final RunConfig CONFIG = new RunConfig(
            SuManager.NEXT.getPackageName(), false, true, 0, 23L);
    private static final String FINGERPRINT =
            "samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys";
    private static final String KERNEL = "5.15.197-android13-8-34343818-abS9180ZHU8GZIF";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path directory;
    private RunPreflight.Plan plan;

    @Before
    public void setUp() throws Exception {
        PosixFileSupport.assumePosixPermissionsSupported(temporaryFolder.getRoot().toPath());
        directory = temporaryFolder.newFolder("de-root").toPath();
        plan = new RunPreflight.Plan(SuManager.NEXT,
                new File(directory.toFile(), "loader"),
                new File(directory.toFile(), "bootstrap"),
                new File(directory.toFile(), "native"));
    }

    @Test
    public void publishesExactSchemaAsPrivateRegularFileAndReplacesStaleContent() throws Exception {
        Files.write(directory.resolve(RunConfigArtifact.RUN_CONFIG), new byte[] {9, 9, 9});
        RecordingFileSystem fileSystem = new RecordingFileSystem();

        publish(fileSystem);

        byte[] expected = ("DFROOT_RUN_CONFIG 1\n"
                + "operation_id=" + OPERATION_ID + "\n"
                + "generation=23\n"
                + "mode=manual\n"
                + "manager=com.rifsxd.ksunext\n"
                + "soft_reboot=0\n"
                + "disable_modules=1\n"
                + "ack_version=0\n"
                + "loader_sha256=" + SuManager.NEXT.getLoaderSha256() + "\n"
                + "fingerprint=" + FINGERPRINT + "\n"
                + "kernel=" + KERNEL + "\n").getBytes("UTF-8");
        assertArrayEquals(expected, Files.readAllBytes(directory.resolve(RunConfigArtifact.RUN_CONFIG)));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(directory.resolve(RunConfigArtifact.RUN_CONFIG)));
        BasicFileAttributes attributes = Files.readAttributes(
                directory.resolve(RunConfigArtifact.RUN_CONFIG), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        assertTrue(attributes.isRegularFile());
        assertFalse(attributes.isSymbolicLink());
        assertTrue(fileSystem.events.contains("move:[ATOMIC_MOVE, REPLACE_EXISTING]"));
        assertTrue(fileSystem.events.contains("sync-directory"));
        assertNoTemporaryFiles(directory);
    }

    @Test
    public void atomicMoveReplacesTargetSymlinkWithoutWritingItsVictim() throws Exception {
        PosixFileSupport.assumeSymlinksSupported(directory);
        Path victim = directory.resolve("victim");
        byte[] victimBytes = new byte[] {4, 5, 6};
        Files.write(victim, victimBytes);
        Files.createSymbolicLink(directory.resolve(RunConfigArtifact.RUN_CONFIG), victim.getFileName());

        publish(new RecordingFileSystem());

        assertArrayEquals(victimBytes, Files.readAllBytes(victim));
        assertFalse(Files.isSymbolicLink(directory.resolve(RunConfigArtifact.RUN_CONFIG)));
        assertTrue(Files.isRegularFile(directory.resolve(RunConfigArtifact.RUN_CONFIG),
                LinkOption.NOFOLLOW_LINKS));
        assertNoTemporaryFiles(directory);
    }

    @Test
    public void writeFileSyncAndMoveFailuresPreserveOldTargetAndDeleteTemp() throws Exception {
        for (String failure : List.of("write", "force", "move")) {
            Path caseDirectory = Files.createDirectory(directory.resolve("failure-" + failure));
            Path target = caseDirectory.resolve(RunConfigArtifact.RUN_CONFIG);
            byte[] oldBytes = new byte[] {1, 2, 3};
            Files.write(target, oldBytes);
            RecordingFileSystem fileSystem = new RecordingFileSystem();
            fileSystem.failure = failure;

            IOException error = assertThrows(IOException.class,
                    () -> publish(caseDirectory, fileSystem));

            assertTrue(error.getMessage().contains("injected " + failure));
            assertArrayEquals(oldBytes, Files.readAllBytes(target));
            assertNoTemporaryFiles(caseDirectory);
        }
    }

    @Test
    public void directorySyncFailureDeletesTempAndDoesNotReportSuccess() throws Exception {
        Path target = directory.resolve(RunConfigArtifact.RUN_CONFIG);
        Files.write(target, new byte[] {8});
        RecordingFileSystem fileSystem = new RecordingFileSystem();
        fileSystem.failure = "sync-directory";

        IOException error = assertThrows(IOException.class, () -> publish(fileSystem));

        assertTrue(error.getMessage().contains("injected sync-directory"));
        assertTrue(Files.exists(target));
        assertNoTemporaryFiles(directory);
    }

    @Test
    public void verifiesPublishedTargetIsRegularAndNotSymlink() throws Exception {
        RecordingFileSystem fileSystem = new RecordingFileSystem() {
            @Override
            void move(Path source, Path target, StandardCopyOption... options) throws IOException {
                super.move(source, target, options);
                Files.delete(target);
                Files.createSymbolicLink(target, source.getFileName());
            }
        };

        assertThrows(IOException.class, () -> publish(fileSystem));

        assertNoTemporaryFiles(directory);
    }

    @Test
    public void rejectsInvalidSnapshotInputsBeforeCreatingFiles() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> RunConfigArtifact.publish(directory, null, CONFIG, plan,
                        RunConfig.ExecutionMode.MANUAL, FINGERPRINT, KERNEL,
                        new RecordingFileSystem()));
        for (String invalidId : List.of("", "bad id", "bad/id", "x".repeat(129), "bad\nid")) {
            assertThrows(IllegalArgumentException.class,
                    () -> publish(new RecordingFileSystem(), invalidId));
        }
        RunConfig unsupported = new RunConfig("example.manager", false, false, 0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> RunConfigArtifact.publish(directory, OPERATION_ID, unsupported, plan,
                        RunConfig.ExecutionMode.MANUAL, FINGERPRINT, KERNEL,
                        new RecordingFileSystem()));
        RunConfig mismatched = new RunConfig(SuManager.CLASSIC.getPackageName(), false, false, 0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> RunConfigArtifact.publish(directory, OPERATION_ID, mismatched, plan,
                        RunConfig.ExecutionMode.MANUAL, FINGERPRINT, KERNEL,
                        new RecordingFileSystem()));
        RunConfig bootSoftReboot = new RunConfig(SuManager.NEXT.getPackageName(), true, true,
                ConfigurationKeys.CURRENT_SOFT_REBOOT_ACK_VERSION, 23L);
        assertThrows(IllegalArgumentException.class,
                () -> RunConfigArtifact.publish(directory, OPERATION_ID, bootSoftReboot, plan,
                        RunConfig.ExecutionMode.UNATTENDED_BOOT, FINGERPRINT, KERNEL,
                        new RecordingFileSystem()));
        for (String invalidText : List.of("", "bad\nvalue", "bad\rvalue", "bad\u0000value",
                "fingerprint=vendor", "kernel=5.15=android13", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class,
                    () -> RunConfigArtifact.publish(directory, OPERATION_ID, CONFIG, plan,
                            RunConfig.ExecutionMode.MANUAL, invalidText, KERNEL,
                            new RecordingFileSystem()));
            assertThrows(IllegalArgumentException.class,
                    () -> RunConfigArtifact.publish(directory, OPERATION_ID, CONFIG, plan,
                            RunConfig.ExecutionMode.MANUAL, FINGERPRINT, invalidText,
                            new RecordingFileSystem()));
        }
        assertFalse(Files.exists(directory.resolve(RunConfigArtifact.RUN_CONFIG)));
    }

    @Test
    public void serializesBootModeAndCurrentAcknowledgementExactly() throws Exception {
        RunConfig boot = new RunConfig(SuManager.CLASSIC.getPackageName(), false, false, 0, 0);
        RunPreflight.Plan classicPlan = new RunPreflight.Plan(SuManager.CLASSIC,
                plan.getLoader(), plan.getBootstrap(), plan.getNativeBinary());

        RunConfigArtifact.publish(directory, OPERATION_ID, boot, classicPlan,
                RunConfig.ExecutionMode.UNATTENDED_BOOT, FINGERPRINT, KERNEL,
                new RecordingFileSystem());

        String content = new String(Files.readAllBytes(
                directory.resolve(RunConfigArtifact.RUN_CONFIG)), "UTF-8");
        assertTrue(content.contains("generation=0\nmode=unattended_boot\nmanager=me.weishu.kernelsu\n"));
        assertTrue(content.contains("soft_reboot=0\ndisable_modules=0\nack_version=0\n"));
    }

    @Test
    public void createTempFilePermissionsAreIgnoredUntilExplicitChmod() throws Exception {
        RunConfigArtifact.FileSystem fileSystem = new RecordingFileSystem() {
            @Override
            Path createTempFile(Path parent, String prefix, String suffix,
                                FileAttribute<?>... attributes) throws IOException {
                Path path = Files.createTempFile(parent, prefix, suffix);
                // Some filesystems silently ignore the 0600 create attributes.
                Files.setPosixFilePermissions(path,
                        PosixFilePermissions.fromString("rw-r--r--"));
                return path;
            }
        };

        publish(fileSystem);

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(directory.resolve(RunConfigArtifact.RUN_CONFIG)));
        assertNoTemporaryFiles(directory);
    }

    @Test
    public void symlinkTemporaryFileIsRejectedBeforeAnyChmod() throws Exception {
        PosixFileSupport.assumeSymlinksSupported(directory);
        Path victim = directory.resolve("temp-victim");
        byte[] victimBytes = new byte[] {1, 2, 3};
        Files.write(victim, victimBytes);
        List<String> chmodCalls = new ArrayList<>();
        RunConfigArtifact.FileSystem fileSystem = new RecordingFileSystem() {
            @Override
            Path createTempFile(Path parent, String prefix, String suffix,
                                FileAttribute<?>... attributes) throws IOException {
                Path link = parent.resolve(".run_config.linked.tmp");
                Files.createSymbolicLink(link, victim.getFileName());
                return link;
            }

            @Override
            void setPosixFilePermissions(Path path, Set<PosixFilePermission> permissions)
                    throws IOException {
                chmodCalls.add(path.toString());
                super.setPosixFilePermissions(path, permissions);
            }
        };

        IOException error = assertThrows(IOException.class, () -> publish(fileSystem));

        assertTrue(error.getMessage().contains("regular non-symlink"));
        assertTrue(chmodCalls.isEmpty());
        assertArrayEquals(victimBytes, Files.readAllBytes(victim));
        assertFalse(Files.exists(directory.resolve(RunConfigArtifact.RUN_CONFIG)));
        assertNoTemporaryFiles(directory);
    }

    private void publish(RunConfigArtifact.FileSystem fileSystem) throws IOException {
        publish(directory, fileSystem);
    }

    private void publish(Path targetDirectory, RunConfigArtifact.FileSystem fileSystem)
            throws IOException {
        RunConfigArtifact.publish(targetDirectory, OPERATION_ID, CONFIG, plan,
                RunConfig.ExecutionMode.MANUAL, FINGERPRINT, KERNEL, fileSystem);
    }

    private void publish(RunConfigArtifact.FileSystem fileSystem, String operationId)
            throws IOException {
        RunConfigArtifact.publish(directory, operationId, CONFIG, plan,
                RunConfig.ExecutionMode.MANUAL, FINGERPRINT, KERNEL, fileSystem);
    }

    private static void assertNoTemporaryFiles(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".run_config.")));
        }
    }

    private static class RecordingFileSystem extends RunConfigArtifact.FileSystem {
        final List<String> events = new ArrayList<>();
        String failure;

        @Override
        Path createTempFile(Path directory, String prefix, String suffix,
                            FileAttribute<?>... attributes) throws IOException {
            events.add("create-temp");
            return super.createTempFile(directory, prefix, suffix, attributes);
        }

        @Override
        void write(FileChannel channel, byte[] bytes) throws IOException {
            events.add("write");
            if ("write".equals(failure)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes, 0, Math.min(7, bytes.length)));
                throw new IOException("injected write failure");
            }
            super.write(channel, bytes);
        }

        @Override
        void force(FileChannel channel) throws IOException {
            events.add("force");
            if ("force".equals(failure)) {
                throw new IOException("injected force failure");
            }
            super.force(channel);
        }

        @Override
        void move(Path source, Path target, StandardCopyOption... options) throws IOException {
            events.add("move:" + Arrays.toString(options));
            if ("move".equals(failure)) {
                throw new IOException("injected move failure");
            }
            super.move(source, target, options);
        }

        @Override
        void syncDirectory(Path directory) throws IOException {
            events.add("sync-directory");
            if ("sync-directory".equals(failure)) {
                throw new IOException("injected sync-directory failure");
            }
            super.syncDirectory(directory);
        }
    }
}
