package com.opensource.tremorwatch

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opensource.tremorwatch.data.WatchRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for watch MainActivity.
 *
 * Architecture Pattern: MVVM (Model-View-ViewModel)
 * - Survives configuration changes (screen rotation)
 * - Proper coroutine scope management (auto-cancelled on clear)
 * - Separates UI logic from business logic
 * - Exposes StateFlow for reactive UI updates
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository = WatchRepository(application)

    // Expose StateFlows from repository for reactive UI
    val pendingBatchCount: StateFlow<Int> = repository.pendingBatchCount
    val lastUploadTime: StateFlow<Long> = repository.lastUploadTime
    val batchesSent: StateFlow<Int> = repository.batchesSent
    val batchesFailed: StateFlow<Int> = repository.batchesFailed

    init {
        Log.i(TAG, "MainViewModel initialized")

        // Initialize queue worker on startup
        WatchDataSender.startQueueWorker()

        // Initial data load
        viewModelScope.launch {
            repository.refreshPendingBatchCount()
            Log.d(TAG, "Initial pending batch count: ${repository.pendingBatchCount.value}")
        }
    }

    /**
     * Trigger manual upload of pending batches.
     * Called when user presses the Upload button.
     */
    fun uploadPendingBatches() {
        Log.i(TAG, "uploadPendingBatches() called")
        viewModelScope.launch {
            val result = repository.processPendingBatches()
            result.onSuccess { (success, failed) ->
                Log.i(TAG, "Upload complete: $success sent, $failed failed")
            }.onFailure { e ->
                Log.e(TAG, "Upload error: ${e.message}", e)
            }
        }
    }

    /**
     * Refresh pending batch count.
     * Called periodically or after batch operations.
     */
    fun refreshData() {
        viewModelScope.launch {
            repository.refreshPendingBatchCount()
        }
    }

    /**
     * Reset statistics counters.
     */
    fun resetStatistics() {
        repository.resetStatistics()
        Log.i(TAG, "Statistics reset")
    }

    /**
     * Clean up resources when ViewModel is destroyed.
     * Automatically called when app is closed.
     */
    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "MainViewModel cleared, shutting down repository")
        repository.shutdown()
    }
}
