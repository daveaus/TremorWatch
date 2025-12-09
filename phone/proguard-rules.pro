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

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep data classes for JSON serialization
-keep class com.example.tremorwatch.shared.models.** { *; }
-keep class com.example.tremorwatch.phone.data.** { *; }

# Keep Timber
-dontwarn com.jakewharton.timber.**

# WorkManager rules
-keep class androidx.work.** { *; }

# Keep services
-keep class com.example.tremorwatch.phone.UploadService { *; }
-keep class com.example.tremorwatch.phone.WatchDataListenerService { *; }
-keep class com.example.tremorwatch.phone.NetworkChangeReceiver { *; }
-keep class com.example.tremorwatch.phone.BatchRetryAlarmReceiver { *; }
