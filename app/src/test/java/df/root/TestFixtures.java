// Modified 2026-11-24 (DFRoot GZIF): resolve repository fixtures from an explicit Gradle-provided root. See NOTICE.
package df.root;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stable, non-privileged access to checked-in binary test fixtures. */
final class TestFixtures {
    private TestFixtures() {}

    static Path repositoryRoot() {
        String configured = System.getProperty("dfroot.repoRoot");
        if (configured == null || configured.trim().isEmpty()) {
            throw new AssertionError("dfroot.repoRoot test system property is required");
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new AssertionError("dfroot.repoRoot is not a directory: " + root);
        }
        return root;
    }

    static Path bundledLoader(SuManager manager) throws IOException {
        Path loader = repositoryRoot().resolve("app/src/main/jniLibs/arm64-v8a")
                .resolve(manager.getLoaderName());
        if (!Files.isRegularFile(loader)) {
            throw new AssertionError("bundled loader fixture not found: " + loader);
        }
        return loader;
    }
}
