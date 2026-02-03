// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Shared version properties across all modules
// Shared version properties across all modules
extra["appVersionName"] = "0.1.3"  // Subjective rating prompts & manual entry
extra["appVersionCode"] = 4