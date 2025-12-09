# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# Hide the original source file name
-renamesourcefileattribute SourceFile

# Keep Compose rules
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# Wear OS Compose rules
-keep class androidx.wear.compose.** { *; }
-dontwarn androidx.wear.compose.**

# OkHttp rules  
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# MQTT Paho rules
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep class org.eclipse.paho.android.service.** { *; }
-dontwarn org.eclipse.paho.**

# Keep data classes for JSON serialization
-keep class com.example.tremorwatch.shared.models.** { *; }
-keep class com.example.tremorwatch.** { *; }

# Keep Timber
-dontwarn com.jakewharton.timber.**

# Keep services
-keep class com.example.tremorwatch.TremorService { *; }

# DataStore
-keep class androidx.datastore.** { *; }