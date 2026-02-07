package com.opensource.tremorwatch.shared.models

import kotlinx.serialization.Serializable

/**
 * Configuration for subjective rating prompts and calibration mode.
 * Synced from phone to watch.
 */
@Serializable
data class RatingConfig(
    // Prompt settings
    val promptsEnabled: Boolean = false,
    val promptFrequencyMinutes: Int = 60,        // How often to prompt
    val smartTriggerEnabled: Boolean = false,    // Prompt on tremor change
    val activeHoursStart: Int = 6,               // 6 AM
    val activeHoursEnd: Int = 22,                // 10 PM
    val maxPromptsPerDay: Int = 8,               // Daily limit
    val promptVibrationEnabled: Boolean = true,
    val promptVibrationStrong: Boolean = false,
    val promptFollowupVibration: Boolean = false,
    
    // Manual Calibration Mode (OFF by default)
    val calibrationModeEnabled: Boolean = false,
    val calibrationDurationSeconds: Int = 60,    // 30-120 range
    
    // Display settings (phone app)
    val showRatingsOnChart: Boolean = true,
    
    val schemaVersion: Int = 2
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val MIN_CALIBRATION_SECONDS = 30
        const val MAX_CALIBRATION_SECONDS = 120
        const val SMART_TRIGGER_COOLDOWN_MINUTES = 30
    }
}
