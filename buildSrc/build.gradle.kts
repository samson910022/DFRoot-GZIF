// Gradle-owned build logic for loader-pin verification.
//
// `kotlin-dsl` is Gradle's own build-logic plugin (Gradle API + embedded Kotlin).
plugins {
    `kotlin-dsl`
}

// Compile classpath for buildSrc only (Kotlin stdlib/reflect used by the
// kotlin-dsl plugin). The app's dependency list is unchanged.
repositories {
    mavenCentral()
}
