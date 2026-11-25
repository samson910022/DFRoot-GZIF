import com.android.build.api.artifact.SingleArtifact
import dfroot.VerifyLoaderPinFixturesTask
import dfroot.VerifyPackagedLoaderPinsTask
import java.util.Properties
// Modified 2026-11-17 (DFRoot GZIF): compileSdk/targetSdk 37→36; versionCode 407→408; versionName 4.7→4.8-gzif. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): signing.properties is now optional at configuration time.
// Reason: PR CI / fresh clones have no signing.properties. Reading it unconditionally made
// file(null) throw during Gradle configuration, breaking every task, including assembleDebug.
// Modified 2026-11-25 (DFRoot GZIF): move loader-pin verification into buildSrc and add the harmless negative fixture guard. See NOTICE.

plugins {
    // Modified 2026-11-24 (DFRoot GZIF): use the version catalog alias so the AGP
    // version is defined in exactly one place (gradle/libs.versions.toml).
    alias(libs.plugins.android.application)
}

val bundledLoaderPins = mapOf(
    "libksud.so" to "aab769430467e5a9f723c1bc8a3180e6fb0b0b82c52fba7b99887199407fc940",
    "libksudnext.so" to "679504dd4e104dc1a296ed4ec795b5a5f3e808c2bd345a7ecfa201b40f1dca1d"
)
val requiredBundledLoaders = setOf("libksud.so", "libksudnext.so")
require(bundledLoaderPins.keys == requiredBundledLoaders) {
    "bundledLoaderPins must define exactly $requiredBundledLoaders; found ${bundledLoaderPins.keys}"
}
require(bundledLoaderPins.all { (loader, pin) -> pin.matches(Regex("[0-9a-f]{64}")) }) {
    "bundledLoaderPins must contain lowercase SHA-256 values for $requiredBundledLoaders"
}

// VerifyPackagedLoaderPinsTask and its shared verifier are top-level buildSrc
// classes; a script-local task class would be a non-static inner class and
// Gradle cannot instantiate it (`:app:tasks --all` failed with that error).

val signingProps = Properties()
val signingPropsFile = rootProject.file("signing.properties")
val hasReleaseSigning = signingPropsFile.exists()

if (hasReleaseSigning) {
    signingPropsFile.inputStream().use { signingProps.load(it) }
    // Report a missing/incomplete file as a readable error instead of passing null to file()/AGP.
    val requiredSigningKeys = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    val missingSigningKeys = requiredSigningKeys.filter { key ->
        val value = signingProps.getProperty(key)
        if (key == "KEYSTORE_FILE") value.isNullOrBlank() else value.isNullOrEmpty()
    }
    require(missingSigningKeys.isEmpty()) {
        "signing.properties (${signingPropsFile.absolutePath}) is missing required key(s): " +
            "${missingSigningKeys.joinToString(", ")}. " +
            "Provide all of ${requiredSigningKeys.joinToString(", ")}, " +
            "or remove signing.properties to build an unsigned release."
    }
} else {
    logger.warn(
        "signing.properties not found at ${signingPropsFile.absolutePath}; " +
            "release builds will be unsigned. Add signing.properties to sign release APKs."
    )
}

android {
    namespace = "df.root"
    compileSdk = 36
    // Modified 2026-11-24 (DFRoot GZIF): pin the native toolchain for reproducible builds.
    // README/PORTING-GZIF.md require NDK r28c (28.2.13676358) and CMake 3.22.1.
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "df.root"
        minSdk = 32
        targetSdk = 36
        versionCode = 408
        versionName = "4.8-gzif"
        buildConfigField("String", "BUNDLED_LOADER_CLASSIC_SHA256",
                "\"${bundledLoaderPins.getValue("libksud.so")}\"")
        buildConfigField("String", "BUNDLED_LOADER_NEXT_SHA256",
                "\"${bundledLoaderPins.getValue("libksudnext.so")}\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Only register the release config when signing.properties exists and is complete.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingProps.getProperty("KEYSTORE_FILE")!!)
                storePassword = signingProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = signingProps.getProperty("KEY_ALIAS")
                keyPassword = signingProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Without signing.properties, leave the release variant unsigned (AGP default)
            // instead of throwing at configuration time; debug builds are unaffected.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("dfroot.repoRoot", rootProject.projectDir.absolutePath)
            }
        }
    }

    externalNativeBuild {
        cmake {
            // Pin CMake so AGP uses a fixed version (must satisfy CMakeLists.txt's
            // cmake_minimum_required(VERSION 3.22): 3.22.1 >= 3.22, compatible).
            version = "3.22.1"
            path("src/main/jni/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // AGP strips jniLibs when an NDK is configured. These prebuilt
            // loaders are authoritative pinned artifacts, not debug symbols.
            keepDebugSymbols += listOf("**/libksud.so", "**/libksudnext.so")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { vn -> "DFRoot_${vn}.apk" })
        }
        if (variant.buildType == "debug" || variant.buildType == "release") {
            val variantTaskName = variant.name.replaceFirstChar { it.uppercase() }
            tasks.register<VerifyPackagedLoaderPinsTask>("verify${variantTaskName}PackagedLoaderPins") {
                loaderDirectory.set(layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a"))
                apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                loaderPins.set(bundledLoaderPins)
                requiredLoaderNames.set(requiredBundledLoaders)
                dependsOn("package$variantTaskName")
            }
        }
    }
}

// Host-only guard for the pin check itself: no KO, NDK toolchain, device, or
// real APK path involved. Fixtures live in app/build/loader-pin-fixtures/.
tasks.register<VerifyLoaderPinFixturesTask>("verifyLoaderPinFixtures") {
    loaderDirectory.set(layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a"))
    loaderPins.set(bundledLoaderPins)
    requiredLoaderNames.set(requiredBundledLoaders)
    fixtureDirectory.set(layout.buildDirectory.dir("loader-pin-fixtures"))
    // The verifier classes compiled into buildSrc are not task inputs; never let a
    // local UP-TO-DATE decision skip the self-test after build logic changes.
    outputs.upToDateWhen { false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
}
