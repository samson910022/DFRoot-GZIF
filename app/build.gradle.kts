import java.util.Properties
// Modified 2026-11-17 (DFRoot GZIF): compileSdk/targetSdk 37→36; versionCode 407→408; versionName 4.7→4.8-gzif. See NOTICE.

plugins {
    id("com.android.application")
}

val signingProps = Properties()
val signingPropsFile = rootProject.file("signing.properties")
if (signingPropsFile.exists()) signingProps.load(signingPropsFile.inputStream())

// PR / debug builds must configure with no signing.properties and no secrets.
// When the real keystore is present AND all secret values are set, use it;
// otherwise install a harmless placeholder config so configuration (and
// assembleDebug / unit tests) works. Without real signing material the release
// variant is disabled and every release task is blocked below, so an ordinary
// Gradle release invocation cannot emit an unsigned or dummy-signed APK. This
// is a build-time guard, not a cryptographic guarantee: a deliberately crafted
// build that injects its own signing config is out of scope.
val configuredKeystore = signingProps.getProperty("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
// Same resolution as the original config: relative paths are app-module
// relative (CI writes KEYSTORE_FILE=../keystore.jks).
val realKeystore = configuredKeystore?.let { file(it) }
val hasRealReleaseSigning = realKeystore != null && realKeystore!!.isFile &&
        !signingProps.getProperty("KEYSTORE_PASSWORD").isNullOrBlank() &&
        !signingProps.getProperty("KEY_ALIAS").isNullOrBlank() &&
        !signingProps.getProperty("KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "df.root"
    compileSdk = 36

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
        create("release") {
            if (hasRealReleaseSigning) {
                storeFile = realKeystore
                storePassword = signingProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = signingProps.getProperty("KEY_ALIAS")
                keyPassword = signingProps.getProperty("KEY_PASSWORD")
            } else {
                // Placeholder only: the release variant is disabled and all
                // release tasks are blocked below; debug builds use the
                // standard debug signing config.
                storeFile = rootProject.layout.buildDirectory
                    .file("dummy-release-keystore.jks").get().asFile
                storePassword = "dummy"
                keyAlias = "dummy"
                keyPassword = "dummy"
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
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
        // Local JVM unit tests use android.jar stubs; Log.e() must be a no-op
        // rather than throwing "Stub!" when a failure is reported.
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        // JVM unit tests live outside the app module, under tests/java.
        getByName("test").java.srcDirs(file("../tests/java"))
    }

    externalNativeBuild {
        cmake {
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
    // Strongest guard: with no real signing material, disable the release
    // variant outright. That prevents assembleRelease, bundleRelease,
    // signRelease, publishRelease* and every other release task from even
    // being created, regardless of their exact names. Real release signing is
    // untouched when the keystore and all secrets are present.
    beforeVariants { variantBuilder ->
        if (variantBuilder.buildType == "release" && !hasRealReleaseSigning) {
            variantBuilder.enable = false
        }
    }
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { vn -> "DFRoot_${vn}.apk" })
        }
    }
}

// Defense in depth: also reject any release task that still gets created (for
// example by a plugin path that does not honour beforeVariants). The name
// patterns cover the assemble/bundle/sign/publish families, including
// publishRelease* variants; debug and test tasks are unaffected.
tasks.matching {
    !hasRealReleaseSigning && (
        it.name.startsWith("assembleRelease") ||
        it.name.startsWith("bundleRelease") ||
        it.name.startsWith("signRelease") ||
        it.name.startsWith("publishRelease") ||
        it.name.startsWith("publish") && it.name.endsWith("Release")
    )
}.configureEach {
    doFirst {
        throw GradleException(
            "Release builds require signing.properties with KEYSTORE_FILE, " +
            "KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD. " +
            "PR/debug builds configure without them.")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
