package com.opensource.tremorwatch.phone

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tracks real-time upload metrics including upload speed, success/failure counts,
 * and upload history for UI display.
 *
 * Uses Flows for reactive UI updates.
 */
object UploadMetrics {

    private const val TAG = "UploadMetrics"
    private const val PREFS_NAME = "upload_metrics"
    private const val KEY_UPLOAD_HISTORY = "upload_history"
    private const val KEY_FAILED_UPLOADS = "failed_uploads"
    private const val HISTORY_MAX_SIZE = 50  // Keep last 50 uploads in history

    // Real-time metrics as StateFlows for reactive UI
    private val _uploadsPerMinute = MutableStateFlow(0.0)
    val uploadsPerMinute: StateFlow<Double> = _uploadsPerMinute

    private val _isCurrentlyUploading = MutableStateFlow(false)
    val isCurrentlyUploading: StateFlow<Boolean> = _isCurrentlyUploading

    private val _lastUploadSpeed = MutableStateFlow("0 batches/min")  // User-friendly format
    val lastUploadSpeed: StateFlow<String> = _lastUploadSpeed

    private val _failedUploadsCount = MutableStateFlow(0)
    val failedUploadsCount: StateFlow<Int> = _failedUploadsCount

    private val _uploadHistory = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())
    val uploadHistory: StateFlow<List<UploadHistoryEntry>> = _uploadHistory

    private val _totalBytesTransferred = MutableStateFlow(0L)
    val totalBytesTransferred: StateFlow<Long> = _totalBytesTransferred

    // Time tracking for speed calculation
    private var uploadStartTime = 0L
    private var batchesUploadedInCurrentSession = 0
    private var speedCalculationWindow = 60000L  // Calculate speed over 60-second windows

    data class UploadHistoryEntry(
        val timestamp: Long,
        val batchCount: Int,
        val bytesSent: Long,
        val success: Boolean,
        val errorMessage: String? = null,
        val durationMs: Long
    )

    fun initialize(context: Context) {
        loadUploadHistory(context)
        loadFailedUploadsCount(context)
    }

    /**
     * Called when an upload starts
     */
    fun recordUploadStart() {
        _isCurrentlyUploading.value = true
        uploadStartTime = System.currentTimeMillis()
        batchesUploadedInCurrentSession = 0
    }

    /**
     * Called when an upload completes successfully
     */
    fun recordUploadSuccess(
        context: Context,
        batchCount: Int,
        bytesSent: Long
    ) {
        _isCurrentlyUploading.value = false
        batchesUploadedInCurrentSession += batchCount
        _totalBytesTransferred.value += bytesSent

        val durationMs = System.currentTimeMillis() - uploadStartTime

        // Calculate speed
        updateUploadSpeed(batchCount, durationMs)

        // Add to history
        val entry = UploadHistoryEntry(
            timestamp = System.currentTimeMillis(),
            batchCount = batchCount,
            bytesSent = bytesSent,
            success = true,
            durationMs = durationMs
        )
        addToUploadHistory(context, entry)

        // Reset failed uploads counter on successful upload
        if (_failedUploadsCount.value > 0) {
            _failedUploadsCount.value = 0
            saveFailedUploadsCount(context, 0)
        }
    }

    /**
     * Called when an upload fails
     */
    fun recordUploadFailure(
        context: Context,
        batchCount: Int,
        errorMessage: String
    ) {
        _isCurrentlyUploading.value = false
        val durationMs = System.currentTimeMillis() - uploadStartTime

        val entry = UploadHistoryEntry(
            timestamp = System.currentTimeMillis(),
            batchCount = batchCount,
            bytesSent = 0,
            success = false,
            errorMessage = errorMessage,
            durationMs = durationMs
        )
        addToUploadHistory(context, entry)

        // Increment failed uploads counter
        val newFailureCount = _failedUploadsCount.value + 1
        _failedUploadsCount.value = newFailureCount
        saveFailedUploadsCount(context, newFailureCount)
    }

    /**
     * Get user-friendly status message
     */
    fun getStatusMessage(context: Context): String {
        return when {
            _isCurrentlyUploading.value -> "Uploading..."
            _failedUploadsCount.value > 0 -> "⚠️ ${_failedUploadsCount.value} failed uploads"
            _uploadHistory.value.isEmpty() -> "No uploads yet"
            else -> {
                val lastUpload = _uploadHistory.value.firstOrNull()
                lastUpload?.let {
                    val timeAgo = formatTimeAgo(System.currentTimeMillis() - it.timestamp)
                    if (it.success) "✓ Last: $timeAgo" else "✗ Last failed $timeAgo"
                } ?: "No uploads yet"
            }
        }
    }

    /**
     * Get upload success rate (percentage)
     */
    fun getSuccessRate(): Int {
        if (_uploadHistory.value.isEmpty()) return 100
        val successful = _uploadHistory.value.count { it.success }
        return (successful * 100) / _uploadHistory.value.size
    }

    /**
     * Get average upload duration
     */
    fun getAverageUploadDuration(): Long {
        if (_uploadHistory.value.isEmpty()) return 0
        return _uploadHistory.value.map { it.durationMs }.average().toLong()
    }

    /**
     * Get total successful batches from history
     */
    fun getTotalSuccessfulBatches(): Int {
        return _uploadHistory.value
            .filter { it.success }
            .sumOf { it.batchCount }
    }

    /**
     * Clear all metrics (for testing or manual reset)
     */
    fun clearAll(context: Context) {
        _uploadsPerMinute.value = 0.0
        _isCurrentlyUploading.value = false
        _lastUploadSpeed.value = "0 batches/min"
        _failedUploadsCount.value = 0
        _uploadHistory.value = emptyList()
        _totalBytesTransferred.value = 0L
        uploadStartTime = 0L
        batchesUploadedInCurrentSession = 0

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // Private helper functions

    private fun updateUploadSpeed(batchCount: Int, durationMs: Long) {
        // Calculate batches per minute
        val batchesPerMinute = if (durationMs > 0) {
            (batchCount * 60000.0) / durationMs
        } else {
            0.0
        }

        _uploadsPerMinute.value = batchesPerMinute
        _lastUploadSpeed.value = String.format("%.1f batches/min", batchesPerMinute)
    }

    private fun addToUploadHistory(context: Context, entry: UploadHistoryEntry) {
        val currentHistory = _uploadHistory.value.toMutableList()
        currentHistory.add(0, entry)  // Add to front

        // Keep only last N entries
        if (currentHistory.size > HISTORY_MAX_SIZE) {
            currentHistory.removeAt(currentHistory.size - 1)
        }

        _uploadHistory.value = currentHistory
        saveUploadHistory(context, currentHistory)
    }

    private fun loadUploadHistory(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_UPLOAD_HISTORY, "[]") ?: "[]"

            // Parse JSON (simple implementation - in production use Gson/Moshi)
            val entries = mutableListOf<UploadHistoryEntry>()
            // For now, we'll initialize empty - can add JSON parsing later
            _uploadHistory.value = entries
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load upload history: ${e.message}", e)
            _uploadHistory.value = emptyList()
        }
    }

    private fun saveUploadHistory(context: Context, history: List<UploadHistoryEntry>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Simple implementation - in production use Gson/Moshi for JSON serialization
            val json = "[]"  // Placeholder - can implement full JSON serialization later
            prefs.edit().putString(KEY_UPLOAD_HISTORY, json).apply()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save upload history: ${e.message}", e)
        }
    }

    private fun loadFailedUploadsCount(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _failedUploadsCount.value = prefs.getInt(KEY_FAILED_UPLOADS, 0)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load failed uploads count: ${e.message}", e)
            _failedUploadsCount.value = 0
        }
    }

    private fun saveFailedUploadsCount(context: Context, count: Int) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_FAILED_UPLOADS, count).apply()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save failed uploads count: ${e.message}", e)
        }
    }

    private fun formatTimeAgo(elapsedMs: Long): String {
        return when {
            elapsedMs < 60 * 1000 -> "now"
            elapsedMs < 60 * 60 * 1000 -> "${elapsedMs / (60 * 1000)}m"
            elapsedMs < 24 * 60 * 60 * 1000 -> "${elapsedMs / (60 * 60 * 1000)}h"
            else -> "${elapsedMs / (24 * 60 * 60 * 1000)}d"
        }
    }
}
