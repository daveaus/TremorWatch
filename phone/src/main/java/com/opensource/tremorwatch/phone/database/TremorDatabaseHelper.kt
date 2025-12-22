package com.opensource.tremorwatch.phone.database

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.phone.ChartData
import com.opensource.tremorwatch.shared.models.TremorBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
                TremorSample(
                    timestamp = sample.timestamp,
                    severity = sample.severity,
                    tremorCount = sample.tremorCount,
                    x = sample.metadata["x"] as? Double,
                    y = sample.metadata["y"] as? Double,
                    z = sample.metadata["z"] as? Double,
                    magnitude = sample.metadata["magnitude"] as? Double,
                    accelMagnitude = sample.metadata["accelMagnitude"] as? Double,
                    dominantFrequency = sample.metadata["dominantFrequency"] as? Double,
                    tremorBandPower = sample.metadata["tremorBandPower"] as? Double,
                    totalPower = sample.metadata["totalPower"] as? Double,
                    bandRatio = sample.metadata["bandRatio"] as? Double,
                    peakProminence = sample.metadata["peakProminence"] as? Double,
                    isWorn = sample.metadata["isWorn"] as? Boolean,
                    isCharging = sample.metadata["isCharging"] as? Boolean,
                    confidence = sample.metadata["confidence"] as? Double,
                    watchId = sample.metadata["watch_id"] as? String,
                    metadataJson = null  // Skip JSON for now - all important fields are extracted
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
     * Uses indexed queries for fast performance.
     */
    suspend fun loadChartData(hoursBack: Int): List<ChartData> = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
            val samples = dao.getSamplesAfter(cutoffTime)
            
            Log.d(TAG, "Loaded ${samples.size} samples from database")
            
            // Aggregate into 1-minute buckets
            val buckets = samples.groupBy { it.timestamp / 60000 }
            val chartData = buckets.map { (bucketTime, bucketSamples) ->
                val metadata = bucketSamples.lastOrNull()?.let { sample ->
                    buildMap {
                        sample.isWorn?.let { put("isWorn", it) }
                        sample.isCharging?.let { put("isCharging", it) }
                        sample.confidence?.let { put("confidence", it) }
                        sample.watchId?.let { put("watch_id", it) }
                    }
                } ?: emptyMap()
                
                ChartData(
                    timestamp = bucketTime * 60000,
                    severity = bucketSamples.map { it.severity }.average(),
                    tremorCount = bucketSamples.sumOf { it.tremorCount },
                    metadata = metadata
                )
            }.sortedBy { it.timestamp }
            
            Log.i(TAG, "Aggregated to ${chartData.size} chart data points")
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
}

data class DatabaseStats(
    val totalSamples: Int,
    val earliestTimestamp: Long?,
    val latestTimestamp: Long?
)
