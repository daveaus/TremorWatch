package com.opensource.tremorwatch.phone.data

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.phone.ChartData
import com.opensource.tremorwatch.phone.GapEvent
import com.opensource.tremorwatch.phone.GapType
import com.opensource.tremorwatch.shared.models.TremorBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository for tremor data operations with built-in caching.
 * 
 * This repository centralizes all tremor data file I/O and provides:
 * - Caching with time-based invalidation
 * - Thread-safe data access
 * - Gap detection for chart visualization
 * - Memory-efficient loading with 1-minute bucketing
 *
 * Architecture: Repository Pattern with caching layer
 */
class TremorDataRepository(private val context: Context) {

    companion object {
        private const val TAG = "TremorDataRepository"
        private const val CONSOLIDATED_FILE = "consolidated_tremor_data.jsonl"
        private const val CACHE_VALIDITY_MS = 60_000L // Cache valid for 1 minute
        private const val BUCKET_SIZE_MS = 60_000L // 1-minute aggregation buckets
        private const val MAX_BATCHES_TO_PROCESS = 5000
        private const val MAX_RECENT_BATCHES = 200
    }

    // Cache for loaded data
    private var cachedData: List<ChartData>? = null
    private var cachedGaps: List<GapEvent>? = null
    private var lastLoadTime: Long = 0
    private var lastHoursBack: Int = 0
    private val cacheMutex = Mutex()

    // Loading state for UI
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _dataPointCount = MutableStateFlow(0)
    val dataPointCount: StateFlow<Int> = _dataPointCount.asStateFlow()

    /**
     * Load tremor data with caching.
     * Returns cached data if still valid, otherwise loads from file.
     * 
     * @param hoursBack Number of hours of data to load
     * @param forceRefresh If true, bypasses cache and reloads from file
     * @return Pair of (chart data, gap events)
     */
    suspend fun loadTremorData(
        hoursBack: Int,
        forceRefresh: Boolean = false
    ): Pair<List<ChartData>, List<GapEvent>> = cacheMutex.withLock {
        val now = System.currentTimeMillis()
        val cacheAge = now - lastLoadTime
        
        // Return cached data if valid
        if (!forceRefresh && 
            cachedData != null && 
            cachedGaps != null &&
            cacheAge < CACHE_VALIDITY_MS &&
            lastHoursBack == hoursBack
        ) {
            Log.d(TAG, "Returning cached data (${cachedData?.size} points, age: ${cacheAge}ms)")
            return@withLock Pair(cachedData!!, cachedGaps!!)
        }

        // Load fresh data
        _isLoading.value = true
        try {
            val (data, gaps) = loadFromFile(hoursBack)
            cachedData = data
            cachedGaps = gaps
            lastLoadTime = now
            lastHoursBack = hoursBack
            _dataPointCount.value = data.size
            
            Log.i(TAG, "Loaded ${data.size} data points, ${gaps.size} gaps (cache updated)")
            return@withLock Pair(data, gaps)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Invalidate the cache, forcing next load to read from file.
     * Call this when new data is received.
     */
    fun invalidateCache() {
        lastLoadTime = 0
        Log.d(TAG, "Cache invalidated")
    }

    /**
     * Save a tremor batch to consolidated storage.
     * Thread-safe and invalidates cache automatically.
     *
     * @param batch The tremor batch to save
     * @return Result.success if saved, Result.failure if error
     */
    suspend fun saveTremorBatch(batch: TremorBatch): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val storageFile = File(context.filesDir, CONSOLIDATED_FILE)
            storageFile.appendText(batch.toJsonString() + "\n")

            // Invalidate cache so next load picks up new data
            invalidateCache()

            Log.d(TAG, "Saved batch ${batch.batchId} to consolidated storage")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save batch ${batch.batchId}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Clean up old data beyond a certain age.
     * This is an expensive operation and should be called infrequently.
     *
     * @param daysToKeep Number of days of data to retain
     */
    suspend fun cleanupOldData(daysToKeep: Int = 30): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val storageFile = File(context.filesDir, CONSOLIDATED_FILE)
            if (!storageFile.exists()) {
                return@withContext Result.success(0)
            }

            val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
            val tempFile = File(context.filesDir, "$CONSOLIDATED_FILE.tmp")
            var keptLines = 0
            var removedLines = 0

            tempFile.bufferedWriter().use { writer ->
                storageFile.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            try {
                                val batch = TremorBatch.fromJsonString(line)
                                // Keep batch if ANY sample is within retention period
                                if (batch.samples.any { it.timestamp >= cutoffTime }) {
                                    writer.write(line)
                                    writer.newLine()
                                    keptLines++
                                } else {
                                    removedLines++
                                }
                            } catch (e: Exception) {
                                // Keep malformed lines to avoid data loss
                                writer.write(line)
                                writer.newLine()
                                keptLines++
                            }
                        }
                    }
                }
            }

            // Replace original file with cleaned version
            if (tempFile.renameTo(storageFile)) {
                invalidateCache()
                Log.i(TAG, "Cleanup complete: kept $keptLines lines, removed $removedLines lines")
                Result.success(removedLines)
            } else {
                tempFile.delete()
                Log.e(TAG, "Failed to replace storage file after cleanup")
                Result.failure(Exception("Failed to replace file"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Get storage statistics.
     */
    suspend fun getStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, CONSOLIDATED_FILE)
            if (!file.exists()) {
                return@withContext StorageStats(
                    exists = false,
                    sizeBytes = 0,
                    lineCount = 0,
                    oldestTimestamp = 0,
                    newestTimestamp = 0
                )
            }

            var lineCount = 0
            var oldestTimestamp = Long.MAX_VALUE
            var newestTimestamp = 0L

            file.bufferedReader().useLines { lines ->
                lines.take(100).forEach { line ->
                    lineCount++
                    try {
                        val batch = TremorBatch.fromJsonString(line)
                        batch.samples.forEach { sample ->
                            if (sample.timestamp < oldestTimestamp) oldestTimestamp = sample.timestamp
                            if (sample.timestamp > newestTimestamp) newestTimestamp = sample.timestamp
                        }
                    } catch (e: Exception) {
                        // Skip invalid lines
                    }
                }
            }

            StorageStats(
                exists = true,
                sizeBytes = file.length(),
                lineCount = lineCount,
                oldestTimestamp = if (oldestTimestamp == Long.MAX_VALUE) 0 else oldestTimestamp,
                newestTimestamp = newestTimestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage stats: ${e.message}")
            StorageStats(exists = false, sizeBytes = 0, lineCount = 0, oldestTimestamp = 0, newestTimestamp = 0)
        }
    }

    /**
     * Load data from consolidated JSONL file.
     * Aggregates into 1-minute buckets to manage memory.
     */
    private suspend fun loadFromFile(hoursBack: Int): Pair<List<ChartData>, List<GapEvent>> = 
        withContext(Dispatchers.IO) {
            val storageFile = File(context.filesDir, CONSOLIDATED_FILE)
            if (!storageFile.exists()) {
                Log.d(TAG, "Storage file does not exist")
                return@withContext Pair(emptyList(), emptyList())
            }

            val cutoffTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
            val dataBuckets = mutableMapOf<Long, MutableList<ChartData>>()

            try {
                var batchesProcessed = 0
                var recentBatchesFound = 0

                storageFile.bufferedReader().use { reader ->
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        if (line.isNullOrBlank()) continue

                        batchesProcessed++

                        // Stop conditions for efficiency
                        if (recentBatchesFound > MAX_RECENT_BATCHES && batchesProcessed > 1000) {
                            Log.d(TAG, "Found $recentBatchesFound recent batches, stopping scan")
                            break
                        }

                        if (batchesProcessed > MAX_BATCHES_TO_PROCESS) {
                            Log.w(TAG, "Reached max batches limit ($MAX_BATCHES_TO_PROCESS)")
                            break
                        }

                        try {
                            val batch = TremorBatch.fromJsonString(line!!)

                            val hasRecentData = batch.samples.any { it.timestamp >= cutoffTime }
                            if (hasRecentData) {
                                recentBatchesFound++
                            }

                            batch.samples.forEach { sample ->
                                if (sample.timestamp >= cutoffTime) {
                                    val bucketKey = (sample.timestamp / BUCKET_SIZE_MS) * BUCKET_SIZE_MS
                                    val bucket = dataBuckets.getOrPut(bucketKey) { mutableListOf() }
                                    bucket.add(
                                        ChartData(
                                            timestamp = sample.timestamp,
                                            severity = sample.severity,
                                            tremorCount = sample.tremorCount,
                                            metadata = sample.metadata
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            // Skip invalid lines silently
                        }
                    }
                }

                // Aggregate buckets
                val aggregatedData = dataBuckets.map { (bucketTime, samples) ->
                    ChartData(
                        timestamp = bucketTime,
                        severity = samples.map { it.severity }.average(),
                        tremorCount = samples.sumOf { it.tremorCount },
                        metadata = samples.lastOrNull()?.metadata ?: emptyMap()
                    )
                }.sortedBy { it.timestamp }

                // Detect gaps in data
                val gaps = detectGaps(aggregatedData)

                val totalSamples = dataBuckets.values.sumOf { it.size }
                Log.i(TAG, "Loaded ${aggregatedData.size} aggregated points from $totalSamples samples ($batchesProcessed batches)")

                // GC hint for large datasets
                if (totalSamples > 1000) System.gc()

                Pair(aggregatedData, gaps)

            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory loading data: ${e.message}")
                Pair(emptyList(), emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load data: ${e.message}", e)
                Pair(emptyList(), emptyList())
            }
        }

    /**
     * Detect gaps in data for chart visualization.
     * A gap is defined as >2 minutes between data points.
     */
    private fun detectGaps(data: List<ChartData>): List<GapEvent> {
        if (data.size < 2) return emptyList()

        val gaps = mutableListOf<GapEvent>()
        val sorted = data.sortedBy { it.timestamp }

        for (i in 1 until sorted.size) {
            val timeDiff = sorted[i].timestamp - sorted[i - 1].timestamp

            // Only consider it a gap if > 2 minutes
            if (timeDiff > 2 * 60 * 1000) {
                val prevPoint = sorted[i - 1]

                // Helper to safely get boolean from metadata
                fun getBooleanMeta(chartData: ChartData, key: String): Boolean? {
                    return when (val value = chartData.metadata[key]) {
                        is Boolean -> value
                        is String -> value.toBoolean()
                        is Number -> value.toInt() != 0
                        else -> null
                    }
                }

                val wasWorn = getBooleanMeta(prevPoint, "isWorn") ?: true
                val wasCharging = getBooleanMeta(prevPoint, "isCharging") ?: false

                val gapType = when {
                    wasCharging -> GapType.CHARGING
                    !wasWorn -> GapType.OFF_WRIST
                    else -> GapType.NO_DATA
                }

                gaps.add(
                    GapEvent(
                        startTime = sorted[i - 1].timestamp,
                        endTime = sorted[i].timestamp,
                        type = gapType
                    )
                )
            }
        }

        return gaps
    }
}

/**
 * Storage statistics for the consolidated tremor data file.
 */
data class StorageStats(
    val exists: Boolean,
    val sizeBytes: Long,
    val lineCount: Int,
    val oldestTimestamp: Long,
    val newestTimestamp: Long
) {
    val sizeMB: Double get() = sizeBytes / (1024.0 * 1024.0)
    
    val ageHours: Double get() {
        if (newestTimestamp == 0L) return 0.0
        return (System.currentTimeMillis() - newestTimestamp) / (60.0 * 60.0 * 1000.0)
    }
}
