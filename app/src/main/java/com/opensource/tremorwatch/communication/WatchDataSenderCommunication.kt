package com.opensource.tremorwatch.communication

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.WatchDataSender
import com.opensource.tremorwatch.shared.models.TremorBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

/**
 * Implementation of WatchPhoneCommunication using WatchDataSender.
 * 
 * This wraps the existing WatchDataSender class to provide a clean interface
 * that abstracts away the underlying implementation details.
 */
class WatchDataSenderCommunication(private val context: Context) : WatchPhoneCommunication {
    
    companion object {
        private const val TAG = "WatchDataSenderComm"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataSender = WatchDataSender(context)
    
    override fun sendBatch(batch: TremorBatch, onComplete: (Boolean) -> Unit) {
        dataSender.sendBatch(batch, onComplete)
    }
    
    override fun sendHeartbeat(serviceUptime: Long, monitoringState: String, isBatteryOptimized: Boolean, onComplete: (Boolean) -> Unit) {
        dataSender.sendHeartbeat(serviceUptime, monitoringState, isBatteryOptimized, onComplete)
    }
    
    override fun sendDiagnosticEvent(eventType: String, eventData: Map<String, Any>, onComplete: (Boolean) -> Unit) {
        dataSender.sendDiagnosticEvent(eventType, eventData, onComplete)
    }
    
    override suspend fun isPhoneConnected(): Boolean {
        return try {
            // Use a simple approach: check if we can get nodes
            // This is a simplified check - in practice, the callback-based approach works fine
            var result = false
            dataSender.isPhoneConnected { connected ->
                result = connected
            }
            // Small delay to allow callback to complete
            kotlinx.coroutines.delay(50)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error checking phone connection: ${e.message}", e)
            false
        }
    }
    
    override fun getPendingBatchCount(): Int {
        return dataSender.getPendingBatchCount()
    }
    
    override fun processPendingBatches(onComplete: (Int, Int) -> Unit) {
        dataSender.sendPendingBatches { successCount, failureCount ->
            onComplete(successCount, failureCount)
        }
    }
    
    override fun shutdown() {
        dataSender.shutdown()
        scope.cancel()
    }
}

