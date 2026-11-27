// Modified 2026-11-25 (DFRoot GZIF): deterministic clean/tampered synthetic APK pin-fixture guard. See NOTICE.
package dfroot

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Host-only, deterministic synthetic APK fixtures built with java.util.zip (no external tools). */
object LoaderPinFixture {
    const val TAMPERED_LOADER = "libksud.so"

    private const val APK_NAME = "DFRoot_fixture.apk"
    private const val FIXED_ZIP_TIME = 315532800000L // 1980-01-01T00:00:00Z: stable entry timestamps
    private const val TAMPER_BYTE: Byte = 0x7E
    private val MANIFEST_BYTES = "synthetic-loader-pin-fixture".toByteArray(Charsets.UTF_8)

    /**
     * Writes an APK-shaped ZIP containing both repository loader bytes under
     * lib/arm64-v8a/. When [tamperedLoader] is set, one extra byte is appended
     * to that loader's bytes, so its packaged SHA-256 cannot match the pin.
     */
    fun create(
        destination: File,
        loaderDirectory: File,
        loaderNames: Set<String>,
        tamperedLoader: String? = null,
    ): File {
        check(destination.mkdirs() || destination.isDirectory) { "cannot create fixture directory $destination" }
        val apk = File(destination, APK_NAME)
        ZipOutputStream(apk.outputStream().buffered()).use { zip ->
            addEntry(zip, "AndroidManifest.xml", MANIFEST_BYTES)
            for (loaderName in loaderNames.sorted()) {
                val repositoryBytes = File(loaderDirectory, loaderName).readBytes()
                val packagedBytes = if (loaderName == tamperedLoader) {
                    repositoryBytes + TAMPER_BYTE
                } else {
                    repositoryBytes
                }
                addEntry(zip, "lib/arm64-v8a/$loaderName", packagedBytes)
            }
        }
        return apk
    }

    private fun addEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = FIXED_ZIP_TIME
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }
}

/**
 * Guards the pin check itself: a clean synthetic APK must pass, while a
 * synthetic APK whose libksud.so has one extra byte must be rejected with a
 * packaged-pin mismatch. The authoritative repository loaders are checked
 * separately and must pass in both cases. Never touches the real APK path.
 */
abstract class VerifyLoaderPinFixturesTask : DefaultTask() {
    @get:InputDirectory
    abstract val loaderDirectory: DirectoryProperty

    @get:Input
    abstract val loaderPins: MapProperty<String, String>

    @get:Input
    abstract val requiredLoaderNames: SetProperty<String>

    @get:OutputDirectory
    abstract val fixtureDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val pins = loaderPins.get()
        val expectedNames = requiredLoaderNames.get()
        val repositoryDirectory = loaderDirectory.get().asFile

        LoaderPinVerifier.validatePins(pins, expectedNames)
        LoaderPinVerifier.verifyRepositoryLoaders(repositoryDirectory, pins)
        logger.lifecycle("Verified repository loader pins before running synthetic fixtures")

        val root = fixtureDirectory.get().asFile
        if (root.exists() && !root.deleteRecursively()) {
            throw GradleException("could not clear synthetic fixture directory $root")
        }
        val cleanDirectory = File(root, "clean")
        val tamperedDirectory = File(root, "tampered")
        LoaderPinFixture.create(cleanDirectory, repositoryDirectory, expectedNames)
        LoaderPinFixture.create(tamperedDirectory, repositoryDirectory, expectedNames, LoaderPinFixture.TAMPERED_LOADER)

        LoaderPinVerifier.verifyPackagedLoaders(cleanDirectory, pins, logger)
        logger.lifecycle("Clean synthetic APK fixture passed packaged loader-pin verification")

        val rejection = try {
            LoaderPinVerifier.verifyPackagedLoaders(tamperedDirectory, pins, logger)
            null
        } catch (ex: GradleException) {
            ex
        }
        if (rejection == null) {
            throw GradleException(
                "negative synthetic APK fixture unexpectedly passed packaged verification; " +
                    "refusing to accept a weakened loader-pin check",
            )
        }
        val expectedFailure = "packaged loader lib/arm64-v8a/${LoaderPinFixture.TAMPERED_LOADER} " +
            "does not match its bundled-loader pin"
        if (!rejection.message.orEmpty().contains(expectedFailure)) {
            throw GradleException(
                "negative synthetic APK fixture failed for an unexpected reason: ${rejection.message}",
                rejection,
            )
        }
        logger.lifecycle("Negative synthetic APK fixture was correctly rejected: ${rejection.message}")
    }
}
