package com.opensource.tremorwatch.phone.data

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.phone.PhoneDataConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository for phone-side data operations.
 * Single source of truth for received batch management and upload statistics.
 *
 * Architecture Pattern: Repository Pattern
 * - Abstracts SharedPreferences and file system access
 * - Provides reactive StateFlow for UI updates
 * - Centralized data access point
 * - Testable business logic
 */
class PhoneRepository(private val context: Context) {

    companion object {
        private const val TAG = "PhoneRepository"
        private const val HEARTBEAT_PREFS = "heartbeat_prefs"
    }

    // StateFlow for reactive UI updates - replaces 2-second polling
    private val _pendingBatchCount = MutableStateFlow(0)
    val pendingBatchCount: StateFlow<Int> = _pendingBatchCount.asStateFlow()

    private val _batchesUploadedToday = MutableStateFlow(0)
    val batchesUploadedToday: StateFlow<Int> = _batchesUploadedToday.asStateFlow()

    private val _lastUploadTime = MutableStateFlow(0L)
    val lastUploadTime: StateFlow<Long> = _lastUploadTime.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    private val _localStorageSize = MutableStateFlow(0L)
    val localStorageSize: StateFlow<Long> = _localStorageSize.asStateFlow()

    private val _heartbeatData = MutableStateFlow(HeartbeatData())
    val heartbeatData: StateFlow<HeartbeatData> = _heartbeatData.asStateFlow()

    /**
     * Get pending batch files awaiting upload to InfluxDB.
     * Files are stored in upload_queue directory with pattern batch_*.json
     */
    suspend fun getPendingBatchFiles(): List<File> = withContext(Dispatchers.IO) {
        try {
            val queueDir = File(context.filesDir, "upload_queue")
            if (!queueDir.exists()) {
                return@withContext emptyList()
            }
            queueDir.listFiles { file ->
                file.name.startsWith("batch_") && file.name.endsWith(".json")
            }?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pending batch files: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Refresh all statistics from SharedPreferences and file system.
     * This is called periodically (every 30s) instead of 2s polling.
     */
    suspend fun refreshStatistics() = withContext(Dispatchers.IO) {
        try {
            // File system stats
            _pendingBatchCount.value = getPendingBatchFiles().size
            _localStorageSize.value = calculateLocalStorageSize()

            // SharedPreferences stats
            _batchesUploadedToday.value = PhoneDataConfig.getBatchesUploadedToday(context)
            _lastUploadTime.value = PhoneDataConfig.getLastUploadTime(context)

            // Heartbeat data
            refreshHeartbeatData()

            Log.d(TAG, "Statistics refreshed: ${_pendingBatchCount.value} pending, ${_batchesUploadedToday.value} uploaded today")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing statistics: ${e.message}", e)
        }
    }

    /**
     * Refresh heartbeat data from watch.
     */
    private fun refreshHeartbeatData() {
        try {
            val prefs = context.getSharedPreferences(HEARTBEAT_PREFS, Context.MODE_PRIVATE)
            _heartbeatData.value = HeartbeatData(
                lastHeartbeatTime = prefs.getLong("last_heartbeat_time", 0),
                watchServiceUptime = prefs.getLong("watch_service_uptime", 0),
                watchMonitoringState = prefs.getString("watch_monitoring_state", "unknown") ?: "unknown"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing heartbeat data: ${e.message}", e)
        }
    }

    /**
     * Calculate total size of local storage files.
     */
    private fun calculateLocalStorageSize(): Long {
        return try {
            val localStorageFile = File(context.filesDir, "tremor_data_local.txt")
            if (localStorageFile.exists()) {
                localStorageFile.length()
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating storage size: ${e.message}")
            0L
        }
    }

    /**
     * Update upload progress (0.0 to 1.0).
     * Called by UploadService during batch processing.
     */
    fun updateUploadProgress(progress: Float) {
        _uploadProgress.value = progress.coerceIn(0f, 1f)
    }

    /**
     * Get configuration values.
     */
    fun getInfluxDbUrl(): String = PhoneDataConfig.getInfluxDbUrl(context)
    fun getInfluxDbDatabase(): String = PhoneDataConfig.getInfluxDbDatabase(context)
    fun getInfluxDbUsername(): String = PhoneDataConfig.getInfluxDbUsername(context)
    fun getInfluxDbPassword(): String = PhoneDataConfig.getInfluxDbPassword(context)
    fun isLocalStorageEnabled(): Boolean = PhoneDataConfig.isLocalStorageEnabled(context)
    fun getLocalStorageRetentionHours(): Int = PhoneDataConfig.getLocalStorageRetentionHours(context)

    /**
     * Update configuration values.
     */
    fun setInfluxDbUrl(url: String) = PhoneDataConfig.setInfluxDbUrl(context, url)
    fun setInfluxDbDatabase(database: String) = PhoneDataConfig.setInfluxDbDatabase(context, database)
    fun setInfluxDbUsername(username: String) = PhoneDataConfig.setInfluxDbUsername(context, username)
    fun setInfluxDbPassword(password: String) = PhoneDataConfig.setInfluxDbPassword(context, password)
    fun setLocalStorageEnabled(enabled: Boolean) = PhoneDataConfig.setLocalStorageEnabled(context, enabled)
    fun setLocalStorageRetentionHours(hours: Int) = PhoneDataConfig.setLocalStorageRetentionHours(context, hours)

    /**
     * Get heartbeat status information.
     */
    fun getHeartbeatStatus(): HeartbeatStatus {
        val data = _heartbeatData.value
        val timeSinceLastHeartbeat = System.currentTimeMillis() - data.lastHeartbeatTime
        val isHealthy = timeSinceLastHeartbeat < 60_000 // Less than 1 minute ago

        return HeartbeatStatus(
            isHealthy = isHealthy,
            timeSinceLastHeartbeat = timeSinceLastHeartbeat,
            watchServiceUptime = data.watchServiceUptime,
            monitoringState = data.watchMonitoringState
        )
    }

    /**
     * Record successful upload to InfluxDB.
     */
    fun recordSuccessfulUpload() {
        PhoneDataConfig.recordSuccessfulUpload(context)
        // Trigger immediate refresh of statistics
        _batchesUploadedToday.value = PhoneDataConfig.getBatchesUploadedToday(context)
        _lastUploadTime.value = PhoneDataConfig.getLastUploadTime(context)
    }
}

/**
 * Data class for watch heartbeat information.
 */
data class HeartbeatData(
    val lastHeartbeatTime: Long = 0,
    val watchServiceUptime: Long = 0,
    val watchMonitoringState: String = "unknown"
)

/**
 * Computed heartbeat status with health check.
 */
data class HeartbeatStatus(
    val isHealthy: Boolean,
    val timeSinceLastHeartbeat: Long,
    val watchServiceUptime: Long,
    val monitoringState: String
)
