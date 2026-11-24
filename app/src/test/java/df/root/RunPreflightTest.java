// Modified 2026-11-24 (DFRoot GZIF): cover preflight matrices and gate POSIX-only fixtures by capability. See NOTICE.
package df.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@RunWith(Enclosed.class)
public class RunPreflightTest {

    @RunWith(Parameterized.class)
    public static class PresenceMatrixTest {
        @Rule
        public TemporaryFolder temporaryFolder = new TemporaryFolder();

        private final SuManager manager;
        private final boolean classicLoaderPresent;
        private final boolean nextLoaderPresent;
        private final boolean bootstrapPresent;
        private final boolean nativePresent;

        public PresenceMatrixTest(SuManager manager, boolean classicLoaderPresent,
                                  boolean nextLoaderPresent, boolean bootstrapPresent,
                                  boolean nativePresent) {
            this.manager = manager;
            this.classicLoaderPresent = classicLoaderPresent;
            this.nextLoaderPresent = nextLoaderPresent;
            this.bootstrapPresent = bootstrapPresent;
            this.nativePresent = nativePresent;
        }

        @Parameterized.Parameters(name = "{0}: classic={1}, next={2}, bootstrap={3}, native={4}")
        public static Collection<Object[]> combinations() {
            List<Object[]> cases = new ArrayList<>();
            for (SuManager manager : SuManager.values()) {
                for (int mask = 0; mask < 16; mask++) {
                    cases.add(new Object[] {
                            manager,
                            (mask & 1) != 0,
                            (mask & 2) != 0,
                            (mask & 4) != 0,
                            (mask & 8) != 0
                    });
                }
            }
            return cases;
        }

        @Test
        public void onlyTheSelectedManagerWithAllThreeAssetsIsReady() throws Exception {
            File nativeDir = temporaryFolder.newFolder("native");
            if (classicLoaderPresent) {
                Files.copy(bundledLoader(SuManager.CLASSIC),
                        new File(nativeDir, SuManager.CLASSIC.getLoaderName()).toPath());
            }
            if (nextLoaderPresent) {
                Files.copy(bundledLoader(SuManager.NEXT),
                        new File(nativeDir, SuManager.NEXT.getLoaderName()).toPath());
            }
            if (bootstrapPresent) {
                Files.write(new File(nativeDir, "libbootstrap.so").toPath(), new byte[] {1, 2, 3});
            }
            if (nativePresent) {
                Files.write(new File(nativeDir, "libdfroot.so").toPath(), new byte[] {4, 5, 6});
            }

            Map<String, FileSnapshot> before = snapshot(nativeDir);
            RunPreflight.Result result = RunPreflight.check(
                    new FakeEnvironment(manager.getPackageName(), nativeDir));
            boolean selectedLoaderPresent = manager == SuManager.CLASSIC
                    ? classicLoaderPresent : nextLoaderPresent;

            if (selectedLoaderPresent && bootstrapPresent && nativePresent) {
                assertTrue(result.getReason(), result.isReady());
                RunPreflight.Plan plan = result.getPlan();
                assertEquals(manager, plan.getManager());
                assertEquals(new File(nativeDir, manager.getLoaderName()), plan.getLoader());
                assertEquals(new File(nativeDir, "libbootstrap.so"), plan.getBootstrap());
                assertEquals(new File(nativeDir, "libdfroot.so"), plan.getNativeBinary());
            } else {
                assertFalse(result.isReady());
                RunPreflight.Failure expected = !selectedLoaderPresent
                        ? RunPreflight.Failure.LOADER_MISSING
                        : !bootstrapPresent
                                ? RunPreflight.Failure.BOOTSTRAP_MISSING
                                : RunPreflight.Failure.NATIVE_MISSING;
                assertEquals(expected, result.getFailure());
            }

            // A pure precheck must not create staged copies or any other output.
            int expectedAssets = (classicLoaderPresent ? 1 : 0)
                    + (nextLoaderPresent ? 1 : 0) + (bootstrapPresent ? 1 : 0)
                    + (nativePresent ? 1 : 0);
            assertEquals(expectedAssets, nativeDir.list().length);
            assertEquals(before, snapshot(nativeDir));
            assertFalse(new File(nativeDir.getParentFile(), "ksud").exists());
            assertFalse(new File(nativeDir.getParentFile(), "bootstrap").exists());
        }
    }

    public static class ValidationTest {
        @Rule
        public TemporaryFolder temporaryFolder = new TemporaryFolder();

        private File nativeDir;
        private FakeEnvironment environment;

        @Before
        public void setUp() throws Exception {
            nativeDir = temporaryFolder.newFolder("native");
            installAllAssets(SuManager.CLASSIC, nativeDir);
            environment = new FakeEnvironment(SuManager.CLASSIC.getPackageName(), nativeDir);
        }

        @After
        public void tearDown() {
            environment = null;
        }

        @Test
        public void nullManagerIsRejected() {
            environment.selectedPackage = null;

            assertRejected(environment, RunPreflight.Failure.NO_MANAGER_SELECTED);
        }

        @Test
        public void unsupportedManagerIsRejected() {
            environment.selectedPackage = "com.example.unsupported";

            assertRejected(environment, RunPreflight.Failure.UNSUPPORTED_MANAGER);
        }

        @Test
        public void uninstalledOrInvisibleManagerIsRejected() {
            environment.installedPackages.remove(environment.selectedPackage);

            assertRejected(environment, RunPreflight.Failure.MANAGER_NOT_INSTALLED);
        }

        @Test
        public void secondaryUserIsRejected() {
            environment.ownerUser = false;

            assertRejected(environment, RunPreflight.Failure.SECONDARY_USER);
        }

        @Test
        public void unsupportedFirmwareIsRejected() {
            environment.firmware = new FirmwareGate.Inputs(
                    "dm2q", "SM-S9180", "CP2A.260605.016", 37,
                    "2026-10-05", "samsung/dm2qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys",
                    "5.15.197-android13-8-34343818-abS9180ZHU8GZIF");

            assertRejected(environment, RunPreflight.Failure.UNSUPPORTED_FIRMWARE);
        }

        @Test
        public void logBufferOffVariantsAreRejected() {
            String[] offVariants = {"S", "s", "Off", "OFF", "off", " off "};
            for (String logTag : offVariants) {
                environment.logTag = logTag;
                assertRejected(environment, RunPreflight.Failure.LOG_BUFFER_OFF);
            }
        }

        @Test
        public void nonOffLogTagsAreAccepted() {
            String[] enabledTags = {"", "I", "Verbose", "0", "S123", null};
            for (String logTag : enabledTags) {
                environment.logTag = logTag;
                assertTrue(RunPreflight.check(environment).isReady());
            }
        }

        @Test
        public void missingNativeLibraryDirIsRejected() {
            environment.nativeLibraryDir = null;
            assertRejected(environment, RunPreflight.Failure.NATIVE_LIBRARY_DIR_MISSING);

            environment.nativeLibraryDir = "  ";
            assertRejected(environment, RunPreflight.Failure.NATIVE_LIBRARY_DIR_MISSING);
        }

        @Test
        public void loaderHashMismatchIsRejectedWithoutStaging() throws Exception {
            File loader = new File(nativeDir, SuManager.CLASSIC.getLoaderName());
            byte[] original = Files.readAllBytes(loader.toPath());
            byte[] corrupted = original.clone();
            corrupted[corrupted.length - 1] ^= 0x01;
            Files.write(loader.toPath(), corrupted);
            assertEquals(original.length, Files.size(loader.toPath()));
            org.junit.Assert.assertArrayEquals(
                    Arrays.copyOf(original, original.length - 1),
                    Arrays.copyOf(Files.readAllBytes(loader.toPath()), original.length - 1));

            assertRejected(environment, RunPreflight.Failure.LOADER_HASH_MISMATCH);
        }

        @Test
        public void symlinkedLoaderIsRejected() throws Exception {
            assertSymlinkRejected(SuManager.CLASSIC.getLoaderName(),
                    RunPreflight.Failure.LOADER_SYMLINK);
        }

        @Test
        public void symlinkedBootstrapIsRejected() throws Exception {
            assertSymlinkRejected("libbootstrap.so", RunPreflight.Failure.BOOTSTRAP_SYMLINK);
        }

        @Test
        public void symlinkedNativeBinaryIsRejected() throws Exception {
            assertSymlinkRejected("libdfroot.so", RunPreflight.Failure.NATIVE_SYMLINK);
        }

        @Test
        public void nonRegularLoaderIsRejected() throws Exception {
            assertDirectoryRejected(SuManager.CLASSIC.getLoaderName(),
                    RunPreflight.Failure.LOADER_NOT_REGULAR);
        }

        @Test
        public void nonRegularBootstrapIsRejected() throws Exception {
            assertDirectoryRejected("libbootstrap.so", RunPreflight.Failure.BOOTSTRAP_NOT_REGULAR);
        }

        @Test
        public void nonRegularNativeBinaryIsRejected() throws Exception {
            assertDirectoryRejected("libdfroot.so", RunPreflight.Failure.NATIVE_NOT_REGULAR);
        }

        @Test
        public void fifoLoaderIsRejected() throws Exception {
            assertFifoRejected(SuManager.CLASSIC.getLoaderName(),
                    RunPreflight.Failure.LOADER_NOT_REGULAR);
        }

        @Test
        public void fifoBootstrapIsRejected() throws Exception {
            assertFifoRejected("libbootstrap.so", RunPreflight.Failure.BOOTSTRAP_NOT_REGULAR);
        }

        @Test
        public void fifoNativeBinaryIsRejected() throws Exception {
            assertFifoRejected("libdfroot.so", RunPreflight.Failure.NATIVE_NOT_REGULAR);
        }

        @Test
        public void nextNeverFallsBackToClassicLoader() throws Exception {
            environment.selectedPackage = SuManager.NEXT.getPackageName();

            assertRejected(environment, RunPreflight.Failure.LOADER_MISSING);
        }

        @Test
        public void classicNeverFallsBackToNextLoader() throws Exception {
            Files.delete(new File(nativeDir, SuManager.CLASSIC.getLoaderName()).toPath());
            installLoader(SuManager.NEXT, nativeDir);

            assertRejected(environment, RunPreflight.Failure.LOADER_MISSING);
        }

        @Test
        public void assetInspectionFailureIsARejectedPrecheck() throws Exception {
            environment.nativeLibraryDir = temporaryFolder.newFile("not-a-directory").getAbsolutePath();

            RunPreflight.Result result = RunPreflight.check(environment);

            assertFalse(result.isReady());
            assertEquals(RunPreflight.Failure.ASSET_INSPECTION_FAILED, result.getFailure());
        }

        @Test
        public void rejectedResultDoesNotExposeAPlan() {
            environment.selectedPackage = null;

            RunPreflight.Result result = RunPreflight.check(environment);

            try {
                result.getPlan();
                fail("rejected preflight must not expose a plan");
            } catch (IllegalStateException expected) {
                // Expected: callers cannot accidentally execute a rejected plan.
            }
        }

        private void assertSymlinkRejected(String name, RunPreflight.Failure expected) throws Exception {
            PosixFileSupport.assumeSymlinksSupported(temporaryFolder.getRoot().toPath());
            File target = temporaryFolder.newFile("target-" + name);
            Files.write(target.toPath(), new byte[] {1});
            Files.delete(new File(nativeDir, name).toPath());
            Files.createSymbolicLink(new File(nativeDir, name).toPath(), target.toPath());

            assertRejected(environment, expected);
        }

        private void assertDirectoryRejected(String name, RunPreflight.Failure expected) throws Exception {
            Files.delete(new File(nativeDir, name).toPath());
            Files.createDirectory(new File(nativeDir, name).toPath());

            assertRejected(environment, expected);
        }

        private void assertFifoRejected(String name, RunPreflight.Failure expected) throws Exception {
            PosixFileSupport.assumeFifosSupported(temporaryFolder.getRoot().toPath());
            Path fifo = new File(nativeDir, name).toPath();
            Files.delete(fifo);
            Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
            assertEquals(0, mkfifo.waitFor());

            assertRejected(environment, expected);
        }

        private static void installAllAssets(SuManager manager, File nativeDir) throws Exception {
            installLoader(manager, nativeDir);
            Files.write(new File(nativeDir, "libbootstrap.so").toPath(), new byte[] {1, 2, 3});
            Files.write(new File(nativeDir, "libdfroot.so").toPath(), new byte[] {4, 5, 6});
        }

        private static void installLoader(SuManager manager, File nativeDir) throws IOException {
            Files.copy(bundledLoader(manager), new File(nativeDir, manager.getLoaderName()).toPath());
        }
    }

    private static Path bundledLoader(SuManager manager) throws IOException {
        return TestFixtures.bundledLoader(manager);
    }

    private static void assertRejected(FakeEnvironment environment, RunPreflight.Failure expected) {
        File nativeDirectory = environment.nativeLibraryDir == null
                ? null : new File(environment.nativeLibraryDir);
        Map<String, FileSnapshot> before = snapshot(nativeDirectory);
        RunPreflight.Result result = RunPreflight.check(environment);

        assertFalse(result.getReason(), result.isReady());
        assertEquals(expected, result.getFailure());
        assertNotNull(result.getReason());
        assertFalse(result.getReason().isEmpty());
        assertEquals("rejected preflight mutated fixture bytes or metadata",
                before, snapshot(nativeDirectory));
        if (nativeDirectory != null) {
            assertFalse(new File(nativeDirectory.getParentFile(), "ksud").exists());
            assertFalse(new File(nativeDirectory.getParentFile(), "bootstrap").exists());
        }
    }

    private static Map<String, FileSnapshot> snapshot(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File[] files = directory.listFiles();
        assertNotNull(files);
        Map<String, FileSnapshot> snapshot = new TreeMap<>();
        for (File file : files) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file.toPath(),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                byte[] bytes = attributes.isRegularFile() ? Files.readAllBytes(file.toPath()) : null;
                String linkTarget = attributes.isSymbolicLink()
                        ? Files.readSymbolicLink(file.toPath()).toString() : null;
                snapshot.put(file.getName(), new FileSnapshot(bytes, attributes.size(),
                        attributes.lastModifiedTime().toMillis(), attributes.isRegularFile(),
                        attributes.isDirectory(), attributes.isSymbolicLink(), linkTarget));
            } catch (IOException e) {
                throw new AssertionError("could not snapshot fixture " + file, e);
            }
        }
        return snapshot;
    }

    private static final class FileSnapshot {
        final byte[] bytes;
        final long size;
        final long lastModified;
        final boolean regular;
        final boolean directory;
        final boolean symbolicLink;
        final String linkTarget;

        FileSnapshot(byte[] bytes, long size, long lastModified, boolean regular,
                     boolean directory, boolean symbolicLink, String linkTarget) {
            this.bytes = bytes;
            this.size = size;
            this.lastModified = lastModified;
            this.regular = regular;
            this.directory = directory;
            this.symbolicLink = symbolicLink;
            this.linkTarget = linkTarget;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FileSnapshot)) {
                return false;
            }
            FileSnapshot snapshot = (FileSnapshot) other;
            return size == snapshot.size && lastModified == snapshot.lastModified
                    && regular == snapshot.regular && directory == snapshot.directory
                    && symbolicLink == snapshot.symbolicLink
                    && java.util.Objects.equals(linkTarget, snapshot.linkTarget)
                    && Arrays.equals(bytes, snapshot.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(size, lastModified, regular, directory, symbolicLink,
                    linkTarget, Arrays.hashCode(bytes));
        }
    }

    private static final class FakeEnvironment implements RunPreflight.Environment {
        private String selectedPackage;
        private final Set<String> installedPackages = new HashSet<>(Arrays.asList(
                SuManager.CLASSIC.getPackageName(), SuManager.NEXT.getPackageName()));
        private boolean ownerUser = true;
        private String nativeLibraryDir;
        private String logTag = "";
        private FirmwareGate.Inputs firmware = supportedFirmware();

        private FakeEnvironment(String selectedPackage, File nativeDir) {
            this.selectedPackage = selectedPackage;
            this.nativeLibraryDir = nativeDir.getAbsolutePath();
        }

        @Override
        public String getSelectedManagerPackage() {
            return selectedPackage;
        }

        @Override
        public boolean isManagerInstalled(String packageName) {
            assertEquals("preflight must query the exact selected package",
                    selectedPackage, packageName);
            return installedPackages.contains(packageName);
        }

        @Override
        public boolean isOwnerUser() {
            return ownerUser;
        }

        @Override
        public String getNativeLibraryDir() {
            return nativeLibraryDir;
        }

        @Override
        public String getLogTag() {
            return logTag;
        }

        @Override
        public FirmwareGate.Inputs getFirmware() {
            return firmware;
        }

        private static FirmwareGate.Inputs supportedFirmware() {
            return new FirmwareGate.Inputs(
                    "dm3q", "SM-S9180", "CP2A.260605.016", 37, "2026-10-05",
                    "samsung/dm3qzhx/dm3q:17/CP2A.260605.016/S9180ZHU8GZIF:user/release-keys",
                    "5.15.197-android13-8-34343818-abS9180ZHU8GZIF");
        }
    }
}
