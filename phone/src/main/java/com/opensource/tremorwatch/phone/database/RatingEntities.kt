package com.opensource.tremorwatch.phone.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for storing subjective tremor ratings from the watch.
 */
@Entity(
    tableName = "subjective_ratings",
    indices = [Index(value = ["timestamp"])]
)
data class SubjectiveRatingEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val rating: Int,                          // 1-5
    val source: String,                       // MANUAL, PROMPTED, TREMOR_CHANGE
    val watchId: String?,
    
    // Algorithm state at rating time
    val detectedSeverity: Double?,
    val detectedConfidence: Float?,
    val detectedFrequency: Float?,

    // Objective lookback context captured at rating time (JSON blob).
    // This avoids schema churn while we iterate on which windows/features correlate best with subjective ratings.
    val objectiveContextJson: String?,
    
    // Calibration settings
    val calibrationModeEnabled: Boolean,
    val calibrationDurationSeconds: Int,
    
    val notes: String?,
    val schemaVersion: Int = 1
)

/**
 * Room entity for storing raw calibration data captured during Manual Calibration Mode.
 * Each record represents one sensor sample (50Hz = 50 per second).
 */
@Entity(
    tableName = "calibration_data",
    foreignKeys = [
        ForeignKey(
            entity = SubjectiveRatingEntity::class,
            parentColumns = ["id"],
            childColumns = ["ratingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["ratingId"]), Index(value = ["timestamp"])]
)
data class CalibrationDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ratingId: String,                     // Links to SubjectiveRatingEntity
    val timestamp: Long,

    // Raw gyroscope values
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float,

    // FFT analysis results
    val dominantFrequency: Float,
    val tremorBandPower: Float,
    val totalPower: Float,
    val bandRatio: Float,
    val peakProminence: Float,
    val confidence: Float,
    val severity: Double,

    // Context
    val isWorn: Boolean,
    val isCharging: Boolean,

    // Extended metadata JSON (tremor type, activity context, accelerometer, etc.)
    // Avoids schema churn for new fields - stored as JSON blob
    val metadataJson: String? = null
)

/**
 * User-confirmed medication ingestion event.
 *
 * Stored separately from subjective ratings so dose-response analytics can be
 * anchored to real ingestion timestamps instead of schedule assumptions.
 */
@Entity(
    tableName = "medication_ingestions",
    indices = [Index(value = ["timestamp"]), Index(value = ["source"])]
)
data class MedicationIngestionEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val source: String,          // e.g. WATCH_TAKEN_NOW
    val watchId: String?,
    val notes: String? = null,
    val payloadJson: String? = null
)
