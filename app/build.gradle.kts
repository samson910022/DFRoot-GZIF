import java.util.Properties
// Modified 2026-11-17 (DFRoot GZIF): compileSdk/targetSdk 37→36; versionCode 407→408; versionName 4.7→4.8-gzif. See NOTICE.
// Modified 2026-11-24 (DFRoot GZIF): signing.properties is now optional at configuration time.
// Reason: PR CI / fresh clones have no signing.properties. Reading it unconditionally made
// file(null) throw during Gradle configuration, breaking every task, including assembleDebug.
// Post-rebase integration (PR #1 + PR #3): keep the version-catalog alias and the NDK pin;
// with no real signing material install a harmless placeholder and disable/fail closed on
// every release task, so debug builds and JVM tests still configure on a fresh checkout.

plugins {
    // Modified 2026-11-24 (DFRoot GZIF): use the version catalog alias so the AGP
    // version is defined in exactly one place (gradle/libs.versions.toml).
    alias(libs.plugins.android.application)
}

// SIGNING-SMOKE ONLY: tests/signing/smoke_signed_release.sh exercises signing
// material and apksigner without the reviewed KO/NDK bytes. It opts out of the
// native build with an explicit property; a distributable APK can never use it
// because the release byte gate requires a verified libdfroot.so. The smoke
// build is isolated from production identity (applicationId, versionName and
// output file name) so it can never be mistaken for a DFRoot_*.apk release.
val signingSmokeSkipNative = providers.gradleProperty("dfroot.signingSmokeSkipNative").orNull == "true"

val signingProps = Properties()
val signingPropsFile = rootProject.file("signing.properties")
val hasSigningProperties = signingPropsFile.exists()

if (hasSigningProperties) {
    signingPropsFile.inputStream().use { signingProps.load(it) }
    // Optional validation: a missing/incomplete file is reported readably but
    // does not break configuration. Real release signing is enabled only when
    // the keystore AND all four values are present; otherwise the release
    // variant and every release task are disabled/fail closed below.
    val requiredSigningKeys = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    val missingSigningKeys = requiredSigningKeys.filter { key ->
        val value = signingProps.getProperty(key)
        if (key == "KEYSTORE_FILE") value.isNullOrBlank() else value.isNullOrEmpty()
    }
    if (missingSigningKeys.isNotEmpty()) {
        logger.warn(
            "signing.properties (${signingPropsFile.absolutePath}) is missing required key(s): " +
                "${missingSigningKeys.joinToString(", ")}. " +
                "Provide all of ${requiredSigningKeys.joinToString(", ")} " +
                "or remove signing.properties; release builds will be unsigned and unavailable."
        )
    }
} else {
    logger.warn(
        "signing.properties not found at ${signingPropsFile.absolutePath}; " +
            "release builds will be unsigned and unavailable. " +
            "Add signing.properties to sign release APKs."
    )
}

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
    // Modified 2026-11-24 (DFRoot GZIF): pin the native toolchain for reproducible builds.
    // README/PORTING-GZIF.md require NDK r28c (28.2.13676358) and CMake 3.22.1.
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Distinct identity for the signing smoke: even if its APK escaped the
        // test harness, it could never be confused with a production DFRoot
        // release by package name, version string or file name.
        applicationId = if (signingSmokeSkipNative) "df.root.signingsmoke" else "df.root"
        minSdk = 32
        targetSdk = 36
        versionCode = 408
        versionName = if (signingSmokeSkipNative) "4.8-gzif-signingsmoke" else "4.8-gzif"

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
            if (!signingSmokeSkipNative) externalNativeBuild {
                cmake {
                    // Authoritative variant label for the native KO policy:
                    // a cached CMAKE_BUILD_TYPE of Debug must never weaken a
                    // release build. Debug may use the local skip policy only
                    // with an explicit reason and no automation marker.
                    arguments += "-DDFROOT_ANDROID_VARIANT=debug"
                    // AGP must build the VERIFIED artifact chain, not merely
                    // the raw linker output in its staging directory. This
                    // CMake target walks raw link -> verify_packaged_ko.cmake
                    // -> verified copy at the package path, so a green named
                    // build proves the byte gate ran (ALL membership alone
                    // could not). `bootstrap` is a SEPARATE required native
                    // artifact staged by ExploitRunner at runtime; it is not
                    // in dfroot_packaged's dependency graph, so AGP's exact
                    // named-target list must include it explicitly. Never
                    // drop either name: a bare ALL/default build would let
                    // AGP package the raw unverified/libdfroot.so instead.
                    targets("dfroot_packaged", "bootstrap")
                }
            }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!signingSmokeSkipNative) externalNativeBuild {
                cmake {
                    // Release is authoritative: the skip guard in CMakeLists
                    // and verify_ko_sha256.cmake rejects it unconditionally.
                    arguments += "-DDFROOT_ANDROID_VARIANT=release"
                    // Same named verified-artifact set as Debug (see above):
                    // the verified dfroot copy AND the separately linked
                    // libbootstrap.so required at runtime.
                    targets("dfroot_packaged", "bootstrap")
                }
            }
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

    if (!signingSmokeSkipNative) externalNativeBuild {
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
            output.outputFileName.set(output.versionName.map { vn ->
                if (signingSmokeSkipNative) "SigningSmoke-${vn}.apk" else "DFRoot_${vn}.apk"
            })
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
