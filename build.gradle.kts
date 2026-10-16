plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin's Gradle plugin on the classpath: AGP's built-in Kotlin compiles with this version
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
