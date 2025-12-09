plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.opensource.tremorwatch"
    compileSdk = 35   // Android 15 (API 35)

    defaultConfig {
        applicationId = "com.opensource.tremorwatch"
        minSdk = 30
        targetSdk = 35
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

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
        // Recommended for Android 15+
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
    // ---- Shared Module ----
    implementation(project(":shared"))

    // ---- Kotlinx Serialization ----
    implementation(libs.kotlinx.serialization.json)

    // ---- Core Wear + Compose ----
    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)

    // Material 2 for Wear OS (Material 3 not yet stable for Wear)
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)

    // ---- Architecture Components ----
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")  // For LifecycleService
    
    // ---- DataStore (Preferences) ----
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    
    // ---- Logging ----
    implementation("com.jakewharton.timber:timber:5.0.1")

    // ---- Coroutines for Wear OS ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")

    // ---- HTTP Client for Home Assistant ----
    implementation(libs.okhttp)

    // ---- MQTT Client ----
    implementation(libs.paho.mqtt)
    implementation(libs.paho.mqtt.service)

    // ---- Testing / Debug ----
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
