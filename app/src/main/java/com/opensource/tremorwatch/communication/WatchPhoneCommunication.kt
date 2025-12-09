package com.opensource.tremorwatch.communication

import com.opensource.tremorwatch.shared.models.TremorBatch

/**
 * Unified interface for watch-to-phone communication.
 * 
 * Abstracts away the underlying implementation (MessageClient vs DataClient)
 * to provide a clean API for sending tremor data from watch to phone.
 * 
 * This interface allows for:
 * - Easy switching between communication methods
 * - Better testability
 * - Clear separation of concerns
 * - Future extensibility (e.g., adding Bluetooth direct communication)
 */
interface WatchPhoneCommunication {
    
    /**
     * Send a tremor batch to the phone.
     * 
     * @param batch The tremor batch to send
     * @param onComplete Callback with success status
     */
    fun sendBatch(batch: TremorBatch, onComplete: (Boolean) -> Unit)
    
    /**
     * Send a heartbeat message to indicate the watch service is alive.
     *
     * @param serviceUptime Service uptime in milliseconds
     * @param monitoringState Current monitoring state (e.g., "active", "paused_charging")
     * @param isBatteryOptimized Whether battery optimization is enabled for the watch app
     * @param onComplete Callback with success status
     */
    fun sendHeartbeat(serviceUptime: Long, monitoringState: String, isBatteryOptimized: Boolean = false, onComplete: (Boolean) -> Unit)
    
    /**
     * Send a diagnostic event to the phone for logging.
     * Used to track state changes like charging, off-body detection, etc.
     * 
     * @param eventType Type of event (e.g., "charging", "offbody", "monitoring_paused")
     * @param eventData Map of key-value pairs with event details
     * @param onComplete Callback with success status
     */
    fun sendDiagnosticEvent(eventType: String, eventData: Map<String, Any>, onComplete: (Boolean) -> Unit)
    
    /**
     * Check if phone is currently connected and reachable.
     * 
     * @return true if phone is connected, false otherwise
     */
    suspend fun isPhoneConnected(): Boolean
    
    /**
     * Get count of pending batches waiting to be sent.
     * 
     * @return Number of pending batches
     */
    fun getPendingBatchCount(): Int
    
    /**
     * Process all pending batches and attempt to send them.
     * 
     * @param onComplete Callback with (sentCount, failedCount)
     */
    fun processPendingBatches(onComplete: (Int, Int) -> Unit)
    
    /**
     * Shutdown and clean up resources.
     * Should be called when the communication instance is no longer needed.
     */
    fun shutdown()
}

/**
 * Decision matrix for when to use each communication API:
 * 
 * MessageClient (current implementation):
 * - Best for: Real-time, small to medium payloads (<4KB per message)
 * - Pros: Low latency, reliable delivery, good for streaming
 * - Cons: Requires chunking for large payloads, may have rate limits
 * 
 * DataClient:
 * - Best for: Larger payloads, less time-sensitive data
 * - Pros: Handles larger payloads, automatic sync
 * - Cons: Higher latency, may have sync delays
 * 
 * Future: Direct Bluetooth
 * - Best for: When phone is nearby but not connected via Wear OS Data Layer
 * - Pros: Direct connection, potentially faster
 * - Cons: Requires additional permissions, more complex setup
 */

