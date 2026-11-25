// Modified 2026-11-25 (DFRoot GZIF): Gradle-supported packaged loader-pin verification task. See NOTICE.
package dfroot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Top-level task class: Gradle cannot instantiate a task class declared inside
 * a Kotlin build script because that class is a non-static inner class.
 */
abstract class VerifyPackagedLoaderPinsTask : DefaultTask() {
    @get:InputDirectory
    abstract val loaderDirectory: DirectoryProperty

    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val loaderPins: MapProperty<String, String>

    @get:Input
    abstract val requiredLoaderNames: SetProperty<String>

    @TaskAction
    fun verify() {
        LoaderPinVerifier.verify(
            loaderDirectory = loaderDirectory.get().asFile,
            apkDirectory = apkDirectory.get().asFile,
            loaderPins = loaderPins.get(),
            expectedLoaderNames = requiredLoaderNames.get(),
            logger = logger,
        )
    }
}
