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
}
