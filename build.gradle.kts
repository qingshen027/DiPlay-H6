// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    configurations.all {
        resolutionStrategy {
            // Force Kotlin stdlib version to avoid duplicates
            force("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10")
        }
    }
}