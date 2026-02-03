package com.opensource.tremorwatch.shared.models

import kotlinx.serialization.Serializable

/**
 * Represents a user's subjective assessment of their tremor severity.
 * Links to calibration data when Manual Calibration Mode is enabled.
 */
@Serializable
data class SubjectiveRating(
    val id: String,                           // UUID
    val timestamp: Long,                      // When rating was entered
    val rating: Int,                          // 0-5 scale (0 = no tremor)
    val source: RatingSource,                 // How the rating was triggered
    val watchId: String? = null,              // Device identifier
    
    // Algorithm state at rating time (for disagreement analysis)
    val detectedSeverity: Double? = null,     // Sensor-calculated severity
    val detectedConfidence: Float? = null,    // Algorithm confidence 0-1
    val detectedFrequency: Float? = null,     // Dominant tremor frequency Hz
    
    // Calibration mode settings
    val calibrationModeEnabled: Boolean = false,
    val calibrationDurationSeconds: Int = 60,
    
    // Metadata
    val notes: String? = null,
    val schemaVersion: Int = 1
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        
        // Functional rating labels for consistent user understanding
        val RATING_LABELS = mapOf(
            0 to "No tremor",
            1 to "Minimal (not bothering me)",
            2 to "Mild (noticeable but not annoying)",
            3 to "Moderate (annoying but manageable)",
            4 to "Strong (interfering with tasks)",
            5 to "Severe (hard to use this hand)"
        )

        val RATING_EMOJIS = mapOf(
            0 to "🎆",
            1 to "😊",
            2 to "🙂",
            3 to "😐",
            4 to "😕",
            5 to "😣"
        )
    }
}

/**
 * Source of a subjective rating.
 */
@Serializable
enum class RatingSource {
    MANUAL,        // User initiated from watch menu
    PROMPTED,      // Scheduled prompt notification
    TREMOR_CHANGE  // Smart trigger from detected tremor change
}
