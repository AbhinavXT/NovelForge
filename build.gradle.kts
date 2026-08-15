// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // FIXED: the KSP version was hardcoded here as "2.0.21-1.0.28" while the
    // catalog independently declared the same string under [plugins].ksp.
    // Two sources of truth for a version that MUST track `kotlin` exactly —
    // bump Kotlin and forget this line and you get a cryptic KSP failure.
    alias(libs.plugins.ksp) apply false
}