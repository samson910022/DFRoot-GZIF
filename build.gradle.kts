plugins {
    // Modified 2026-11-24 (DFRoot GZIF): single source of truth for AGP version.
    // Previously hardcoded 9.4.0 here while the version catalog declared 9.5.0 (inert).
    alias(libs.plugins.android.application) apply false
}
