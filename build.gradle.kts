plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false

    // Declared here so :core and :desktop resolve the same Kotlin plugin version as
    // the Android app. Gradle rejects a versioned request once the plugin is already
    // on the root classpath, so those modules apply it by id without a version.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.jetbrains.compose) apply false
}
