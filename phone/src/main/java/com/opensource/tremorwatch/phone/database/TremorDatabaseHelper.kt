package com.opensource.tremorwatch.phone.database

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.phone.ChartData
import com.opensource.tremorwatch.shared.models.TremorBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Helper class for database operations on tremor data.
 * Provides methods to save batches and load chart data efficiently.
 */
class TremorDatabaseHelper(private val context: Context) {
    
    private val database = TremorRoomDatabase.getDatabase(context)
    private val dao = database.tremorDao()
    
    companion object {
        private const val TAG = "TremorDatabaseHelper"
    }
    
    /**
     * Save a tremor batch to the database.
     * Converts batch samples to database entities.
     */
    suspend fun saveBatch(batch: TremorBatch): Boolean = withContext(Dispatchers.IO) {
        try {
            val samples = batch.samples.map { sample ->
                // Serialize full metadata to JSON. Previously gated on activityType presence,
                // which silently dropped context for ~20% of rows. Now always attempted with
                // NaN/Infinity sanitization to prevent JSONObject serialization failures.
                val metadataJson = if (sample.metadata.isNotEmpty()) {
                    try {
                        val sanitized = sample.metadata.mapValues { (_, v) ->
                            when {
                                v is Double && (v.isNaN() || v.isInfinite()) -> 0.0
                                v is Float && (v.isNaN() || v.isInfinite()) -> 0.0f
                                else -> v
                            }
                        }
                        JSONObject(sanitized).toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to serialize metadata, storing null: ${e.message}")
                        null
                    }
                } else {
                    null
                }

                // Use (as? Number)?.toDouble() instead of (as? Double) to handle both Float
                // and Double values that arrive from JSON deserialization. Previously ~185k rows
                // stored null because Kotlin's as? Double fails silently on Float values.
                fun Any?.toDoubleOrNull(): Double? = (this as? Number)?.toDouble()

                TremorSample(
                    timestamp = sample.timestamp,
                    severity = sample.severity,
                    tremorCount = sample.tremorCount,
                    x = sample.metadata["x"].toDoubleOrNull(),
                    y = sample.metadata["y"].toDoubleOrNull(),
                    z = sample.metadata["z"].toDoubleOrNull(),
                    magnitude = sample.metadata["magnitude"].toDoubleOrNull(),
                    accelMagnitude = sample.metadata["accelMagnitude"].toDoubleOrNull(),
                    dominantFrequency = sample.metadata["dominantFrequency"].toDoubleOrNull(),
                    tremorBandPower = sample.metadata["tremorBandPower"].toDoubleOrNull(),
                    totalPower = sample.metadata["totalPower"].toDoubleOrNull(),
                    bandRatio = sample.metadata["bandRatio"].toDoubleOrNull(),
                    peakProminence = sample.metadata["peakProminence"].toDoubleOrNull(),
                    isWorn = sample.metadata["isWorn"] as? Boolean,
                    isCharging = sample.metadata["isCharging"] as? Boolean,
                    confidence = sample.metadata["confidence"].toDoubleOrNull(),
                    watchId = sample.metadata["watch_id"] as? String,
                    metadataJson = metadataJson
                )
            }
            
            dao.insertAll(samples)
            Log.d(TAG, "Saved batch with ${samples.size} samples to database")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save batch to database: ${e.message}", e)
            false
        }
    }
    
    /**
     * Load chart data from database for specified time range.
     * Uses SQL aggregation for FAST performance (~1 second vs ~25 seconds).
     * Aggregation is done in SQLite, returning only ~6k buckets instead of 700k+ samples.
     */
    suspend fun loadChartData(hoursBack: Int): List<ChartData> = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
            
            // Use SQL aggregation - MUCH faster than loading raw samples
            val aggregatedData = dao.getAggregatedChartData(cutoffTime)
            
            Log.d(TAG, "Loaded ${aggregatedData.size} pre-aggregated buckets from database (SQL GROUP BY)")
            
            // Convert to ChartData
            val chartData = aggregatedData.map { agg ->
                val metadata = buildMap<String, Any> {
                    agg.lastIsWorn?.let { put("isWorn", it) }
                    agg.lastIsCharging?.let { put("isCharging", it) }
                    agg.avgConfidence?.let { put("confidence", it) }
                    agg.lastWatchId?.let { put("watch_id", it) }
                }
                
                ChartData(
                    timestamp = agg.bucketTimestamp,
                    severity = agg.avgSeverity,
                    tremorCount = agg.totalTremorCount,
                    metadata = metadata
                )
            }
            
            Log.i(TAG, "Returned ${chartData.size} chart data points")
            chartData
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chart data from database: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Clean up old data beyond retention period.
     * Uses indexed DELETE for fast cleanup.
     */
    suspend fun cleanup(retentionHours: Int): Int = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (retentionHours * 60 * 60 * 1000L)
            val deletedCount = dao.deleteOlderThan(cutoffTime)
            Log.i(TAG, "Cleanup deleted $deletedCount samples older than $retentionHours hours")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}", e)
            0
        }
    }
    
    /**
     * Get database statistics.
     */
    suspend fun getStats(): DatabaseStats = withContext(Dispatchers.IO) {
        try {
            DatabaseStats(
                totalSamples = dao.getTotalCount(),
                earliestTimestamp = dao.getEarliestTimestamp(),
                latestTimestamp = dao.getLatestTimestamp()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stats: ${e.message}", e)
            DatabaseStats(0, null, null)
        }
    }
    
    /**
     * Get all samples for export (no time limit).
     * Use with caution on large datasets.
     */
    suspend fun getAllSamples(): List<TremorSample> = withContext(Dispatchers.IO) {
        try {
            dao.getSamplesAfter(0L)  // Get all samples
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all samples: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get samples after specified timestamp.
     */
    suspend fun getSamplesAfter(cutoffTime: Long): List<TremorSample> = withContext(Dispatchers.IO) {
        try {
            dao.getSamplesAfter(cutoffTime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get samples after $cutoffTime: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get samples in specific time range for export.
     */
    suspend fun getSamplesInRange(startTime: Long, endTime: Long): List<TremorSample> = withContext(Dispatchers.IO) {
        try {
            dao.getSamplesInRange(startTime, endTime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get samples in range: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get samples in specific time range for export with pagination.
     */
    suspend fun getSamplesInRangePaged(
        startTime: Long,
        endTime: Long,
        limit: Int,
        offset: Int
    ): List<TremorSample> = withContext(Dispatchers.IO) {
        try {
            dao.getSamplesInRangePaged(startTime, endTime, limit, offset)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get paged samples in range: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get samples after cutoff with pagination for memory-efficient export.
     * Call repeatedly with increasing offset until empty list returned.
     */
    suspend fun getSamplesAfterPaged(cutoffTime: Long, limit: Int, offset: Int): List<TremorSample> = withContext(Dispatchers.IO) {
        try {
            dao.getSamplesAfterPaged(cutoffTime, limit, offset)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get paged samples: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get count of samples after cutoff time.
     */
    suspend fun getSamplesCountAfter(cutoffTime: Long): Int = withContext(Dispatchers.IO) {
        try {
            dao.getSamplesCountAfter(cutoffTime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get sample count: ${e.message}", e)
            0
        }
    }

    /**
     * Get count of samples in a specific time range (for export empty check / progress).
     */
    suspend fun getSamplesCountInRange(startTime: Long, endTime: Long): Int = withContext(Dispatchers.IO) {
        try {
            dao.getSamplesCountInRange(startTime, endTime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get sample count in range: ${e.message}", e)
            0
        }
    }
    
    /**
     * Load subjective ratings for chart display.
     */
    suspend fun getRatingsAfter(cutoffTime: Long): List<SubjectiveRatingEntity> = withContext(Dispatchers.IO) {
        try {
            dao.getRatingsAfter(cutoffTime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ratings: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Load minute-level aggregates for Daily Tremor Profile calculations.
     */
    suspend fun getMinuteAggregatesInRange(
        startTime: Long,
        endTime: Long
    ): List<MinuteAggregateRow> = withContext(Dispatchers.IO) {
        try {
            dao.getMinuteAggregatesInRange(startTime, endTime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load minute aggregates: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Load subjective ratings in range for Daily Tremor Profile calculations.
     */
    suspend fun getRatingsInRange(
        startTime: Long,
        endTime: Long
    ): List<RatingSampleRow> = withContext(Dispatchers.IO) {
        try {
            dao.getRatingsInRange(startTime, endTime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ratings in range: ${e.message}", e)
            emptyList()
        }
    }
}

data class DatabaseStats(
    val totalSamples: Int,
    val earliestTimestamp: Long?,
    val latestTimestamp: Long?
)
