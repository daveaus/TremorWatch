plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    kotlin("kapt")  // For Room annotation processing
}

android {
    namespace = "com.opensource.tremorwatch.phone"
    compileSdk = 35   // Android 15 (API 35)

    defaultConfig {
        applicationId = "com.opensource.tremorwatch"
        minSdk = 26  // Android 8.0 - most phones support this
        targetSdk = 35
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }
    
    flavorDimensions.clear()

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Shared module
    implementation(project(":shared"))

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Core Android + Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation("androidx.appcompat:appcompat:1.6.1")


    // Material Design 3 for phone
    implementation("androidx.compose.material3:material3:1.3.0")

    // Data Layer API for watch communication
    implementation(libs.play.services.wearable)

    // HTTP Client for InfluxDB
    implementation(libs.okhttp)

    // WorkManager for background uploads
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Logging - Timber (same as watch app for consistency)
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Coroutines for Play Services
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")

    // Accompanist - SwipeRefresh for pull-to-refresh
    implementation("com.google.accompanist:accompanist-swiperefresh:0.34.0")

    // Security - EncryptedSharedPreferences for credential storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room Database for efficient data storage
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Testing / Debug
    androidTestImplementation(platform(libs.compose.bom))
    debugImplementation(libs.ui.tooling)
}
