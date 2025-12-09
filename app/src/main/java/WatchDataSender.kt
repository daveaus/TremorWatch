package com.opensource.tremorwatch

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.communication.WatchChannelSender
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.math.min

/**
 * Handles sending tremor data from watch to phone via MessageClient API.
 *
 * Key improvements for reliability:
 * - Uses MessageClient instead of DataItems (better for streaming)
 * - Chunks large payloads to <4KB to avoid Data Layer saturation
 * - Non-blocking coroutines instead of Thread.sleep()
 * - Persistent queue for failed batches
 * - Connectivity checks before sending
 *
 * This replaces direct InfluxDB uploads to save battery.
 */
class WatchDataSender(private val context: Context) {

    companion object {
        private const val TAG = "WatchDataSender"

        /**
         * Represents a batch queued for sending
         */
        private data class QueuedBatch(
            val batch: TremorBatch,
            val onComplete: (Boolean) -> Unit,
            val context: Context,
            val skipRequeue: Boolean = false  // Don't re-queue if already from a file
        )

        // Shared queue for serializing batch sends to prevent Data Layer congestion
        // This is static so all WatchDataSender instances use the same queue
        private val sendQueue = Channel<QueuedBatch>(capacity = Channel.UNLIMITED)
        private var queueWorkerStarted = false
        private val workerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Node cache for faster lookups
        private var cachedNodes: List<Node>? = null
        private var nodeCacheTime: Long = 0

        /**
         * Start the queue worker coroutines that process batches in parallel.
         * Must be called once when the service is initialized.
         */
        fun startQueueWorker() {
            synchronized(this) {
                if (queueWorkerStarted) return
                queueWorkerStarted = true

                // Launch multiple parallel workers for faster throughput
                val workerCount = Constants.PARALLEL_SEND_WORKERS
                Log.d(TAG, "✓ Starting $workerCount parallel batch send workers")

                repeat(workerCount) { workerId ->
                    workerScope.launch {
                        Log.d(TAG, "✓ Worker $workerId started")
                        try {
                            for (queuedBatch in sendQueue) {
                                try {
                                    val sender = WatchDataSender(queuedBatch.context)
                                    val success = sender.sendBatchInternal(queuedBatch.batch, queuedBatch.skipRequeue)
                                    queuedBatch.onComplete(success)

                                    // Minimal delay between batches - parallel workers handle throughput
                                    delay(50)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Worker $workerId error: ${e.message}", e)
                                    queuedBatch.onComplete(false)
                                }
                            }
                        } catch (e: ClosedReceiveChannelException) {
                            Log.d(TAG, "Worker $workerId shutting down")
                        } catch (e: Exception) {
                            Log.e(TAG, "Worker $workerId error: ${e.message}", e)
                        }
                    }
                }
            }
        }

        /**
         * Get cached connected nodes or fetch fresh ones
         */
        suspend fun getCachedNodes(context: Context): List<Node> {
            val now = System.currentTimeMillis()
            val cached = cachedNodes
            if (cached != null && (now - nodeCacheTime) < Constants.NODE_CACHE_DURATION_MS) {
                return cached
            }
            return emptyList() // Will be populated by individual sender
        }

        /**
         * Update the node cache
         */
        fun updateNodeCache(nodes: List<Node>) {
            cachedNodes = nodes
            nodeCacheTime = System.currentTimeMillis()
        }
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Send a tremor batch to the phone app via MessageClient.
     * Queues the batch to be sent serially to prevent Data Layer congestion.
     * Automatically chunks data and persists if phone is unreachable.
     *
     * @param batch The tremor batch to send
     * @param onComplete Callback with success status
     */
    fun sendBatch(batch: TremorBatch, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                Companion.sendQueue.send(Companion.QueuedBatch(batch, onComplete, context))
                Log.d(TAG, "Queued batch ${batch.batchId} for sending")
            } catch (e: ClosedSendChannelException) {
                Log.e(TAG, "Send queue closed, cannot queue batch ${batch.batchId}")
                onComplete(false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue batch ${batch.batchId}: ${e.message}")
                onComplete(false)
            }
        }
    }

    /**
     * Internal method that performs the actual batch send operation.
     * Called by the queue worker to process queued batches in parallel.
     * @param skipRequeue If true, don't re-queue on failure (batch is already from a file)
     */
    private suspend fun sendBatchInternal(batch: TremorBatch, skipRequeue: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // getConnectedNodes now handles Bluetooth check and caching
                val connectedNodes = getConnectedNodes()
                if (connectedNodes.isEmpty()) {
                    Log.w(TAG, "✗ No phone connected - ${if (skipRequeue) "skipping requeue" else "queueing batch ${batch.batchId} for later"}")
                    if (!skipRequeue) queueBatchForLater(batch)
                    return@withContext false
                }

                // Send to first available node (phone)
                val phoneNode = connectedNodes.first()
                Log.i(TAG, "Attempting to send batch ${batch.batchId} to ${phoneNode.displayName}")

                val success = sendBatchToNode(batch, phoneNode)

                if (success) {
                    Log.i(TAG, "✓ Successfully sent batch ${batch.batchId}")
                    return@withContext true
                } else {
                    Log.w(TAG, "✗ Failed to send batch ${batch.batchId} - ${if (skipRequeue) "skipping requeue" else "queueing for retry"}")
                    if (!skipRequeue) queueBatchForLater(batch)
                    return@withContext false
                }

            } catch (e: Exception) {
                Log.e(TAG, "✗ Error sending batch ${batch.batchId}: ${e.javaClass.simpleName}: ${e.message}")
                if (!skipRequeue) queueBatchForLater(batch)
                return@withContext false
            }
        }
    }

    /**
     * Compress data using GZIP
     */
    private fun compressData(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(data)
        }
        return outputStream.toByteArray()
    }

    /**
     * Send batch to a specific phone node using ChannelClient for streaming.
     * ChannelClient eliminates manual chunking and provides reliable streaming.
     */
    private suspend fun sendBatchToNode(batch: TremorBatch, node: Node): Boolean {
        return withContext(Dispatchers.IO) {
            var attemptNumber = 0
            var lastError: Exception? = null

            while (attemptNumber <= Constants.MAX_SEND_RETRIES) {
                try {
                    if (attemptNumber > 0) {
                        val delayMs = calculateBackoffDelay(attemptNumber)
                        Log.d(TAG, "Retry #$attemptNumber for batch ${batch.batchId} after ${delayMs}ms")
                        delay(delayMs)
                    }

                    // Use ChannelClient for all batch sizes
                    // Eliminates manual chunking and provides better flow control
                    val channelSender = WatchChannelSender(context)
                    val success = channelSender.sendBatch(batch, node)

                    if (success) {
                        return@withContext true
                    }

                } catch (e: Exception) {
                    lastError = e
                    if (attemptNumber < Constants.MAX_SEND_RETRIES) {
                        Log.w(TAG, "Attempt ${attemptNumber + 1} failed for batch ${batch.batchId}: ${e.message}")
                    }
                    attemptNumber++
                }
            }

            Log.e(TAG, "Failed to send batch ${batch.batchId} after ${Constants.MAX_SEND_RETRIES + 1} attempts", lastError)
            false
        }
    }

    /**
     * Send batch using DataClient for reliable persistence (proven pattern)
     */
    private suspend fun sendSingleMessage(node: Node, batchId: String, data: ByteArray, isCompressed: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Sending batch $batchId using reliable DataClient (${data.size} bytes, compressed=$isCompressed)")

                // Use DataClient for reliable, persistent transmission
                val dataMap = PutDataMapRequest.create("/tremor_batch/$batchId").apply {
                    dataMap.putString(Constants.KEY_BATCH_ID, batchId)
                    dataMap.putLong(Constants.KEY_TIMESTAMP, System.currentTimeMillis())
                    dataMap.putByteArray("batch_data", data)
                    dataMap.putString("source", "watch")
                    dataMap.putBoolean("compressed", isCompressed)
                }.asPutDataRequest().setUrgent()

                // DataClient provides guaranteed delivery with persistence
                val dataItemTask = dataClient.putDataItem(dataMap)
                val startTime = System.currentTimeMillis()
                Tasks.await(dataItemTask, 10, TimeUnit.SECONDS)
                val sendTimeMs = System.currentTimeMillis() - startTime

                // Log performance metrics
                when {
                    sendTimeMs > 5000 -> Log.w(TAG, "⚠ SLOW batch send $batchId: ${sendTimeMs}ms - Data Layer congested")
                    sendTimeMs > 2000 -> Log.i(TAG, "✓ Sent batch $batchId via DataClient (${sendTimeMs}ms - moderate)")
                    else -> Log.i(TAG, "✓ Sent batch $batchId via DataClient (${sendTimeMs}ms)")
                }

                // Optional: Send lightweight notification via MessageClient
                try {
                    val notification = JSONObject().apply {
                        put("event", "batch_ready")
                        put("batch_id", batchId)
                        put("timestamp", System.currentTimeMillis())
                    }.toString().toByteArray()

                    messageClient.sendMessage(
                        node.id,
                        "/notification",
                        notification
                    )
                    Log.d(TAG, "Notification sent for batch $batchId")
                } catch (e: Exception) {
                    Log.w(TAG, "Notification failed for batch $batchId: ${e.message}")
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to send batch $batchId via DataClient: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    /**
     * Send batch as multiple chunked messages
     */
    private suspend fun sendChunkedBatch(node: Node, batchId: String, data: ByteArray, isCompressed: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val chunks = data.toList().chunked(Constants.MAX_CHUNK_SIZE_BYTES)
                val totalChunks = chunks.size

                Log.i(TAG, "Sending batch $batchId to ${node.displayName} in $totalChunks chunks (${data.size} bytes total, compressed=$isCompressed)")

                chunks.forEachIndexed { index, chunk ->
                    // Create chunk payload with metadata
                    val metadata = JSONObject().apply {
                        put(Constants.KEY_BATCH_ID, batchId)
                        put(Constants.KEY_CHUNK_INDEX, index)
                        put(Constants.KEY_TOTAL_CHUNKS, totalChunks)
                        put(Constants.KEY_TIMESTAMP, System.currentTimeMillis())
                        put("compressed", isCompressed)
                    }.toString()

                    val payload = metadata.toByteArray() + byteArrayOf(0) + chunk.toByteArray()

                    // Fire-and-forget: send without waiting for completion
                    // This prevents timeout when Data Layer is slow
                    messageClient.sendMessage(
                        node.id,
                        Constants.MESSAGE_PATH_TREMOR_CHUNK,
                        payload
                    )

                    Log.d(TAG, "✓ Sent chunk ${index + 1}/$totalChunks for batch $batchId (${chunk.size} bytes)")

                    // Small delay between chunks to avoid overwhelming Data Layer
                    if (index < chunks.size - 1) {
                        delay(Constants.CHUNK_SEND_DELAY_MS)
                    }
                }

                Log.i(TAG, "✓ Successfully sent all $totalChunks chunks for batch $batchId")
                true

            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to queue chunked batch $batchId: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    /**
     * Calculate exponential backoff delay with jitter
     */
    private fun calculateBackoffDelay(attemptNumber: Int): Long {
        val baseDelay = Constants.INITIAL_RETRY_DELAY_MS * (1 shl (attemptNumber - 1))
        val jitter = (Math.random() * 0.2 * baseDelay).toLong()
        return min(baseDelay + jitter, Constants.MAX_RETRY_DELAY_MS)
    }

    /**
     * Get list of connected phone nodes using capability-based discovery
     * Uses caching to avoid repeated lookups during bulk transfers
     */
    private suspend fun getConnectedNodes(): List<Node> {
        return withContext(Dispatchers.IO) {
            try {
                // Check cache first
                val now = System.currentTimeMillis()
                val cached = Companion.cachedNodes
                if (cached != null && cached.isNotEmpty() && (now - Companion.nodeCacheTime) < Constants.NODE_CACHE_DURATION_MS) {
                    return@withContext cached
                }

                // Check Bluetooth first - skip network calls if disabled
                if (!ConnectivityUtil.isBluetoothEnabled()) {
                    Log.d(TAG, "Bluetooth disabled - skipping node lookup")
                    return@withContext emptyList()
                }

                // PRIMARY: Use capability to find phones with TremorWatch receiver app
                val capabilityClient = Wearable.getCapabilityClient(context)
                val capabilityTask = capabilityClient.getCapability(
                    Constants.CAPABILITY_TREMOR_RECEIVER,
                    CapabilityClient.FILTER_REACHABLE
                )
                val capabilityInfo = Tasks.await(capabilityTask, 15, TimeUnit.SECONDS)
                val capableNodes = capabilityInfo.nodes.toList()

                if (capableNodes.isNotEmpty()) {
                    Log.i(TAG, "Found ${capableNodes.size} capable node(s) via capability: ${capableNodes.joinToString { "${it.displayName} (${it.id.take(8)}...)" }}")
                    Companion.updateNodeCache(capableNodes)
                    return@withContext capableNodes
                }

                // FALLBACK: If no capable nodes, use generic connected nodes
                Log.w(TAG, "No capable nodes found, falling back to generic connected nodes")
                val nodeClient = Wearable.getNodeClient(context)
                val nodesTask = nodeClient.connectedNodes
                val nodes = Tasks.await(nodesTask, 15, TimeUnit.SECONDS)

                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected nodes found - phone may be disconnected or doesn't have TremorWatch installed")
                } else {
                    Log.i(TAG, "Found ${nodes.size} generic connected node(s): ${nodes.joinToString { "${it.displayName} (${it.id.take(8)}...)" }}")
                    Companion.updateNodeCache(nodes)
                }
                nodes
            } catch (e: java.util.concurrent.TimeoutException) {
                Log.e(TAG, "Timeout getting connected nodes - Wear OS Data Layer may be unresponsive", e)
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get connected nodes: ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * Queue a batch to disk for retry later.
     * Prevents data loss when phone is unreachable.
     * Checks if batch already exists to prevent duplicates.
     * Uses atomic write (temp file + rename) to prevent corruption.
     */
    private fun queueBatchForLater(batch: TremorBatch) {
        try {
            val queueDir = File(context.filesDir, "pending_batches")
            if (!queueDir.exists()) {
                queueDir.mkdirs()
            }

            val batchFile = File(queueDir, "batch_${batch.batchId}.json")

            // Check if batch is already queued to prevent duplicates
            if (batchFile.exists()) {
                Log.d(TAG, "Batch ${batch.batchId} already queued - skipping duplicate")
                return
            }

            // Use atomic write: write to temp file first, then rename
            // This prevents corruption if the write is interrupted
            val tempFile = File(queueDir, "batch_${batch.batchId}.json.tmp")
            tempFile.writeText(batch.toJsonString())

            // Atomic rename
            if (!tempFile.renameTo(batchFile)) {
                Log.e(TAG, "Failed to move temp file to final location for batch ${batch.batchId}")
                tempFile.delete()
                return
            }

            Log.d(TAG, "Queued batch ${batch.batchId} to pending directory")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue batch ${batch.batchId}: ${e.message}", e)
        }
    }

    /**
     * Send all pending batches from queue.
     * Called periodically or when connectivity is restored.
     */
    fun sendPendingBatches(onComplete: (Int, Int) -> Unit) {
        scope.launch {
            try {
                val queueDir = File(context.filesDir, "pending_batches")
                if (!queueDir.exists()) {
                    onComplete(0, 0)
                    return@launch
                }

                val pendingFiles = queueDir.listFiles { file ->
                    file.name.startsWith("batch_") && file.name.endsWith(".json")
                }?.sortedBy { it.name } ?: emptyList()

                if (pendingFiles.isEmpty()) {
                    onComplete(0, 0)
                    return@launch
                }

                Log.i(TAG, "Processing ${pendingFiles.size} pending batch(es)")
                var successCount = 0
                var failureCount = 0

                // Get connected nodes once
                val connectedNodes = getConnectedNodes()
                if (connectedNodes.isEmpty()) {
                    Log.w(TAG, "No phone connected - cannot send pending batches")
                    onComplete(0, pendingFiles.size)
                    return@launch
                }

                val phoneNode = connectedNodes.first()

                pendingFiles.forEach { file ->
                    try {
                        val jsonContent = file.readText()
                        val batch = TremorBatch.fromJsonString(jsonContent)
                        val success = sendBatchToNode(batch, phoneNode)

                        if (success) {
                            file.delete()
                            successCount++
                            Log.d(TAG, "Sent pending batch ${batch.batchId}")
                        } else {
                            failureCount++
                        }

                        // Small delay between batches
                        delay(500)

                    } catch (e: org.json.JSONException) {
                        // JSON parse error indicates file corruption - delete it to prevent infinite retry
                        Log.e(TAG, "Corrupted JSON in file ${file.name}, deleting: ${e.message}")
                        file.delete()
                        failureCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process pending file ${file.name}: ${e.message}", e)
                        failureCount++
                    }
                }

                Log.i(TAG, "Pending batch processing complete: $successCount sent, $failureCount failed")
                onComplete(successCount, failureCount)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing pending batches: ${e.message}", e)
                onComplete(0, 0)
            }
        }
    }

    /**
     * Get count of pending batches in queue
     */
    fun getPendingBatchCount(): Int {
        return try {
            val queueDir = File(context.filesDir, "pending_batches")
            if (!queueDir.exists()) return 0

            queueDir.listFiles { file ->
                file.name.startsWith("batch_") && file.name.endsWith(".json")
            }?.size ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pending batch count: ${e.message}", e)
            0
        }
    }

    /**
     * Send a batch from a JSON file to the phone.
     * Uses the serial queue to prevent Data Layer congestion.
     * Does NOT re-queue on failure since the file itself is the queue.
     * @param file The file containing the batch JSON
     * @param onComplete Callback with success status
     */
    fun sendBatchFromFile(file: File, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                val json = file.readText()
                val batch = TremorBatch.fromJsonString(json)

                // Use the serial queue to prevent overwhelming Data Layer
                // Skip re-queueing on failure since the file is already the queue
                Companion.sendQueue.send(Companion.QueuedBatch(batch, onComplete, context, skipRequeue = true))
                Log.d(TAG, "Queued batch ${batch.batchId} from file for sending")
            } catch (e: ClosedSendChannelException) {
                Log.e(TAG, "Send queue closed, cannot queue batch from file ${file.name}")
                onComplete(false)
            } catch (e: org.json.JSONException) {
                // JSON parse error indicates file corruption - delete it
                Log.e(TAG, "Corrupted JSON in file ${file.name}, deleting: ${e.message}")
                file.delete()
                onComplete(false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read batch from file ${file.name}: ${e.message}", e)
                onComplete(false)
            }
        }
    }

    /**
     * Send a batch directly without queueing on failure.
     * Used when sending from existing queue files.
     */
    private fun sendBatchDirect(batch: TremorBatch, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                // Check Bluetooth connectivity first
                if (!ConnectivityUtil.isBluetoothEnabled()) {
                    Log.w(TAG, "Bluetooth disabled - cannot send batch ${batch.batchId}")
                    onComplete(false)
                    return@launch
                }

                // Check if phone is connected
                val connectedNodes = getConnectedNodes()
                if (connectedNodes.isEmpty()) {
                    Log.w(TAG, "No phone connected - cannot send batch ${batch.batchId}")
                    onComplete(false)
                    return@launch
                }

                // Send to first available node (phone)
                val success = sendBatchToNode(batch, connectedNodes.first())

                if (success) {
                    Log.i(TAG, "✓ Successfully sent batch ${batch.batchId}")
                    onComplete(true)
                } else {
                    Log.w(TAG, "✗ Failed to send batch ${batch.batchId}")
                    onComplete(false)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending batch ${batch.batchId}: ${e.message}", e)
                onComplete(false)
            }
        }
    }

    /**
     * Check if the phone companion app is connected and reachable.
     */
    fun isPhoneConnected(onResult: (Boolean) -> Unit) {
        scope.launch {
            val nodes = getConnectedNodes()
            val isConnected = nodes.isNotEmpty() && ConnectivityUtil.isBluetoothEnabled()
            Log.d(TAG, "Phone connection status: ${if (isConnected) "connected" else "disconnected"}")
            onResult(isConnected)
        }
    }

    /**
     * Send a diagnostic event to the phone for logging to InfluxDB.
     * Used to track state changes like charging, off-body detection, etc.
     *
     * @param eventType Type of event (e.g., "charging", "offbody", "monitoring_paused")
     * @param eventData Map of key-value pairs with event details
     * @param onComplete Callback with success status
     */
    fun sendDiagnosticEvent(eventType: String, eventData: Map<String, Any>, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                val nodes = getConnectedNodes()
                if (nodes.isEmpty()) {
                    Log.w(TAG, "✗ No phone connected - cannot send diagnostic event")
                    onComplete(false)
                    return@launch
                }

                val eventJson = JSONObject().apply {
                    put("event_type", eventType)
                    put("timestamp", System.currentTimeMillis())
                    eventData.forEach { (key, value) ->
                        put(key, value)
                    }
                }.toString().toByteArray()

                // Fire-and-forget: send without blocking
                messageClient.sendMessage(
                    nodes.first().id,
                    Constants.MESSAGE_PATH_DIAGNOSTIC_EVENT,
                    eventJson
                )

                Log.i(TAG, "✓ Diagnostic event sent: $eventType")
                onComplete(true)

            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to send diagnostic event: ${e.javaClass.simpleName}: ${e.message}")
                onComplete(false)
            }
        }
    }

    /**
     * Send a heartbeat ping to the phone to indicate the watch service is alive.
     * Uses MessageClient for lightweight status updates.
     *
     * @param serviceUptime Service uptime in milliseconds
     * @param monitoringState Current monitoring state
     * @param isBatteryOptimized Whether battery optimization is enabled for the watch app
     * @param onComplete Callback with success status
     */
    fun sendHeartbeat(serviceUptime: Long, monitoringState: String, isBatteryOptimized: Boolean = false, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                val nodes = getConnectedNodes()
                if (nodes.isEmpty()) {
                    Log.w(TAG, "✗ No phone connected - cannot send heartbeat")
                    onComplete(false)
                    return@launch
                }

                val heartbeatData = JSONObject().apply {
                    put(Constants.KEY_TIMESTAMP, System.currentTimeMillis())
                    put(Constants.KEY_SERVICE_UPTIME, serviceUptime)
                    put(Constants.KEY_MONITORING_STATE, monitoringState)
                    put("is_battery_optimized", isBatteryOptimized)
                }.toString().toByteArray()

                // Fire-and-forget: send without waiting for completion to prevent blocking
                messageClient.sendMessage(
                    nodes.first().id,
                    Constants.PATH_HEARTBEAT,
                    heartbeatData
                )

                Log.i(TAG, "✓ Heartbeat sent (uptime: ${serviceUptime / 1000}s, state: $monitoringState)")
                onComplete(true)

            } catch (e: java.util.concurrent.TimeoutException) {
                Log.e(TAG, "✗ Timeout sending heartbeat after ${Constants.MESSAGE_TIMEOUT_MS}ms - Data Layer may be congested")
                onComplete(false)
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to send heartbeat: ${e.javaClass.simpleName}: ${e.message}")
                onComplete(false)
            }
        }
    }

    /**
     * Clean up resources when sender is no longer needed
     */
    fun shutdown() {
        scope.cancel()
    }
}
