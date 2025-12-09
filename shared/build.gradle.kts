plugins {
    id("java-library")
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Explicitly set Kotlin JVM target to match Java
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // org.json (for TremorBatch and TremorData)
    implementation("org.json:json:20231013")
}
