import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: models, parsing, business rules and the repository contracts.
// It has no Android dependency, so the compiler enforces the layering and its
// tests run in seconds on any JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
