package com.opensource.tremorwatch.phone.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for efficient tremor data storage.
 * Replaces slow JSONL file reading with indexed SQL queries.
 */
@Entity(
    tableName = "tremor_samples",
    indices = [
        Index(value = ["timestamp"]),  // For fast time-range queries
        Index(value = ["severity"])  // For severity-based queries
    ]
)
data class TremorSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Core data
    val timestamp: Long,
    val severity: Double,
    val tremorCount: Int,
    
    // Accelerometer data
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val magnitude: Double? = null,
    val accelMagnitude: Double? = null,
    
    // Tremor analysis
    val dominantFrequency: Double? = null,
    val tremorBandPower: Double? = null,
    val totalPower: Double? = null,
    val bandRatio: Double? = null,
    val peakProminence: Double? = null,
    
    // Context
    val isWorn: Boolean? = null,
    val isCharging: Boolean? = null,
    val confidence: Double? = null,
    val watchId: String? = null,
    
    // Extensibility: Store remaining metadata as JSON
    val metadataJson: String? = null
)

/**
 * Data class for aggregated chart data from SQL GROUP BY query.
 * This is returned by getAggregatedChartData() for fast chart loading.
 */
data class AggregatedChartData(
    val bucketTimestamp: Long,
    val avgSeverity: Double,
    val totalTremorCount: Int,
    val lastIsWorn: Boolean?,
    val lastIsCharging: Boolean?,
    val lastConfidence: Double?,
    val lastWatchId: String?
)
