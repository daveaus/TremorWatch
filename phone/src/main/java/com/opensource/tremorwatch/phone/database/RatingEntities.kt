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
    
    // Raw accelerometer values
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
    val isCharging: Boolean
)
