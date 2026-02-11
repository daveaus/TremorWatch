package com.opensource.tremorwatch.phone.database

import androidx.room.ColumnInfo

/**
 * Projection row for minute-level aggregation used by Daily Tremor Profile.
 */
data class MinuteAggregateRow(
    @ColumnInfo(name = "minuteBucketTimestamp")
    val minuteBucketTimestamp: Long,
    @ColumnInfo(name = "avgSeverity")
    val avgSeverity: Double,
    @ColumnInfo(name = "sampleCount")
    val sampleCount: Int,
    @ColumnInfo(name = "anyCharging")
    val anyCharging: Int,
    @ColumnInfo(name = "anyOffWrist")
    val anyOffWrist: Int,
    @ColumnInfo(name = "avgConfidence")
    val avgConfidence: Double?
)

/**
 * Projection row for subjective ratings used by Daily Tremor Profile.
 */
data class RatingSampleRow(
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "rating")
    val rating: Int,
    @ColumnInfo(name = "detectedSeverity")
    val detectedSeverity: Double?,
    @ColumnInfo(name = "source")
    val source: String
)
