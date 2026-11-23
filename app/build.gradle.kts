import java.util.Properties
// Modified 2026-11-17 (DFRoot GZIF): compileSdk/targetSdk 37→36; versionCode 407→408; versionName 4.7→4.8-gzif. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): signing.properties is now optional at configuration time.
// Reason: PR CI / fresh clones have no signing.properties. Reading it unconditionally made
// file(null) throw during Gradle configuration, breaking every task, including assembleDebug.

plugins {
    // Modified 2026-11-24 (DFRoot GZIF): use the version catalog alias so the AGP
    // version is defined in exactly one place (gradle/libs.versions.toml).
    alias(libs.plugins.android.application)
}

val signingProps = Properties()
val signingPropsFile = rootProject.file("signing.properties")
val hasReleaseSigning = signingPropsFile.exists()

if (hasReleaseSigning) {
    signingPropsFile.inputStream().use { signingProps.load(it) }
    // Report a missing/incomplete file as a readable error instead of passing null to file()/AGP.
    val requiredSigningKeys = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    val missingSigningKeys = requiredSigningKeys.filter { signingProps.getProperty(it).isNullOrBlank() }
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
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { vn -> "DFRoot_${vn}.apk" })
        }
    }
}

dependencies {
}
