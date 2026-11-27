// Modified 2026-11-25 (DFRoot GZIF): shared strict loader SHA-256 pin verification for repository and packaged loaders. See NOTICE.
package dfroot

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Single source of truth for bundled-loader pin verification.
 *
 * Lives in buildSrc (not in a build script) so it is a top-level class that
 * Gradle can instantiate, and so both the real APK task and the deterministic
 * synthetic-fixture guard call exactly the same checks.
 */
object LoaderPinVerifier {
    private const val EXPECTED_PIN_COUNT = 2
    private const val LOADER_ENTRY_PREFIX = "lib/arm64-v8a/"

    fun verify(
        loaderDirectory: File,
        apkDirectory: File,
        loaderPins: Map<String, String>,
        expectedLoaderNames: Set<String>,
        logger: Logger,
    ) {
        validatePins(loaderPins, expectedLoaderNames)
        verifyRepositoryLoaders(loaderDirectory, loaderPins)
        verifyPackagedLoaders(apkDirectory, loaderPins, logger)
    }

    fun validatePins(loaderPins: Map<String, String>, expectedLoaderNames: Set<String>) {
        if (loaderPins.size != EXPECTED_PIN_COUNT) {
            throw GradleException(
                "bundledLoaderPins must define exactly $EXPECTED_PIN_COUNT loaders; " +
                    "found ${loaderPins.size}: ${loaderPins.keys}",
            )
        }
        if (loaderPins.keys != expectedLoaderNames) {
            throw GradleException(
                "bundledLoaderPins must define exactly $expectedLoaderNames; found ${loaderPins.keys}",
            )
        }
    }

    fun verifyRepositoryLoaders(loaderDirectory: File, loaderPins: Map<String, String>) {
        for ((loaderName, expectedPin) in loaderPins) {
            val repositoryLoader = File(loaderDirectory, loaderName)
            if (!repositoryLoader.isFile) {
                throw GradleException("authoritative repository loader is missing: $repositoryLoader")
            }
            val repositoryPin = sha256(repositoryLoader.readBytes())
            if (repositoryPin != expectedPin) {
                throw GradleException(
                    "repository loader $loaderName does not match its bundled-loader pin: " +
                        "expected $expectedPin, got $repositoryPin",
                )
            }
        }
    }

    fun verifyPackagedLoaders(apkDirectory: File, loaderPins: Map<String, String>, logger: Logger) {
        // Mirrors fileTree(apkDirectory).matching { include("**/*.apk") }.files
        // (directories count too, so they cannot weaken the exactly-one check),
        // with sorted traversal so the failure mode is deterministic.
        val apks = apkDirectory.walkTopDown()
            .filter { it.name.endsWith(".apk") }
            .sortedBy { it.path }
            .toList()
        if (apks.size != 1) {
            throw GradleException(
                "expected exactly one packaged APK in $apkDirectory, found ${apks.size}; " +
                    "refusing to verify a stale or ambiguous artifact",
            )
        }
        val apkFile = apks.single()
        if (!apkFile.isFile) {
            throw GradleException("expected exactly one packaged APK in $apkDirectory, but ${apkFile.path} is not a file")
        }
        ZipFile(apkFile).use { apk ->
            for ((loaderName, expectedPin) in loaderPins) {
                val entryName = "$LOADER_ENTRY_PREFIX$loaderName"
                val entry = apk.getEntry(entryName)
                    ?: throw GradleException("packaged APK is missing $entryName; loader pins cannot be verified")
                if (entry.isDirectory) {
                    throw GradleException("packaged APK entry $entryName is a directory")
                }
                val actualPin = apk.getInputStream(entry).use { sha256(it.readBytes()) }
                if (actualPin != expectedPin) {
                    throw GradleException(
                        "packaged loader $entryName does not match its bundled-loader pin: " +
                            "expected $expectedPin, got $actualPin",
                    )
                }
                logger.lifecycle("Verified $entryName against bundled-loader pin $expectedPin")
            }
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
