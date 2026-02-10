package com.opensource.tremorwatch.phone.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for tremor samples.
 * Provides efficient indexed queries for chart data.
 */
@Dao
interface TremorDao {
    
    /**
     * Get all samples after a cutoff timestamp.
     * Uses index on timestamp column for O(log n) performance.
     */
    @Query("SELECT * FROM tremor_samples WHERE timestamp >= :cutoffTime ORDER BY timestamp ASC")
    suspend fun getSamplesAfter(cutoffTime: Long): List<TremorSample>
    
    /**
     * Get samples in a specific time range.
     */
    @Query("SELECT * FROM tremor_samples WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getSamplesInRange(startTime: Long, endTime: Long): List<TremorSample>
    
    /**
     * Delete samples older than cutoff timestamp.
     * Efficient cleanup with indexed delete.
     */
    @Query("DELETE FROM tremor_samples WHERE timestamp < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long): Int
    
    /**
     * Insert multiple samples.
     * REPLACE strategy handles duplicates gracefully.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<TremorSample>)
    
    /**
     * Insert single sample.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: TremorSample)
    
    /**
     * Get total count of samples (for statistics).
     */
    @Query("SELECT COUNT(*) FROM tremor_samples")
    suspend fun getTotalCount(): Int
    
    /**
     * Get earliest timestamp (for statistics).
     */
    @Query("SELECT MIN(timestamp) FROM tremor_samples")
    suspend fun getEarliestTimestamp(): Long?
    
    /**
     * Get latest timestamp (for statistics).
     */
    @Query("SELECT MAX(timestamp) FROM tremor_samples")
    suspend fun getLatestTimestamp(): Long?
    
    /**
     * Get samples after cutoff time with pagination for memory-efficient export.
     * Uses LIMIT/OFFSET to avoid loading all data into memory at once.
     */
    @Query("SELECT * FROM tremor_samples WHERE timestamp >= :cutoffTime ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getSamplesAfterPaged(cutoffTime: Long, limit: Int, offset: Int): List<TremorSample>
    
    /**
     * Get count of samples after cutoff time (for progress tracking).
     */
    @Query("SELECT COUNT(*) FROM tremor_samples WHERE timestamp >= :cutoffTime")
    suspend fun getSamplesCountAfter(cutoffTime: Long): Int
    
    /**
     * Get aggregated chart data in 1-minute buckets.
     * This is MUCH faster than loading all samples and aggregating in Kotlin.
     * Groups by minute (timestamp / 60000) and computes AVG severity, SUM tremor count.
     * Returns last isWorn/isCharging state per bucket for gap detection.
     */
    @Query("""
        SELECT 
            (timestamp / 60000) * 60000 as bucketTimestamp,
            AVG(severity) as avgSeverity,
            SUM(tremorCount) as totalTremorCount,
            MAX(isWorn) as lastIsWorn,
            MAX(isCharging) as lastIsCharging,
            MAX(confidence) as lastConfidence,
            MAX(watchId) as lastWatchId
        FROM tremor_samples 
        WHERE timestamp >= :cutoffTime 
        GROUP BY timestamp / 60000 
        ORDER BY bucketTimestamp ASC
    """)
    suspend fun getAggregatedChartData(cutoffTime: Long): List<AggregatedChartData>
    
    // ==================== Subjective Ratings ====================
    
    /**
     * Insert or update a subjective rating.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRating(rating: SubjectiveRatingEntity)
    
    /**
     * Insert multiple calibration data samples.
     */
    @Insert
    suspend fun insertCalibrationData(data: List<CalibrationDataEntity>)
    
    /**
     * Get ratings after a timestamp.
     */
    @Query("SELECT * FROM subjective_ratings WHERE timestamp >= :startTime ORDER BY timestamp DESC")
    suspend fun getRatingsAfter(startTime: Long): List<SubjectiveRatingEntity>
    
    /**
     * Get all ratings (for export).
     */
    @Query("SELECT * FROM subjective_ratings ORDER BY timestamp DESC")
    suspend fun getAllRatings(): List<SubjectiveRatingEntity>
    
    /**
     * Get calibration data for a specific rating.
     */
    @Query("SELECT * FROM calibration_data WHERE ratingId = :ratingId ORDER BY timestamp ASC")
    suspend fun getCalibrationDataForRating(ratingId: String): List<CalibrationDataEntity>
    
    /**
     * Get ratings that have calibration data available.
     */
    @Query("SELECT * FROM subjective_ratings WHERE calibrationModeEnabled = 1 ORDER BY timestamp DESC")
    suspend fun getRatingsWithCalibrationData(): List<SubjectiveRatingEntity>
    
    /**
     * Delete rating and cascade to calibration data.
     */
    @Query("DELETE FROM subjective_ratings WHERE id = :ratingId")
    suspend fun deleteRating(ratingId: String)
    
    /**
     * Get rating count for today (for daily limit check).
     */
    @Query("SELECT COUNT(*) FROM subjective_ratings WHERE timestamp >= :startOfDay")
    suspend fun getRatingCountSince(startOfDay: Long): Int

    /**
     * Count calibration samples for a specific rating.
     */
    @Query("SELECT COUNT(*) FROM calibration_data WHERE ratingId = :ratingId")
    suspend fun getCalibrationCountForRating(ratingId: String): Int

    /**
     * Mark a rating as having calibration data (set flag to true).
     * Called after calibration file is parsed and inserted into DB.
     */
    @Query("UPDATE subjective_ratings SET calibrationModeEnabled = 1 WHERE id = :ratingId")
    suspend fun markRatingCalibrated(ratingId: String)
}

