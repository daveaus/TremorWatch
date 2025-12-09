package com.opensource.tremorwatch.phone

import android.content.Intent
import android.util.Log
import com.opensource.tremorwatch.phone.data.TremorDataRepository
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.google.android.gms.wearable.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Service that listens for data from the watch via MessageClient API.
 * Receives tremor batches (potentially chunked) and queues them for upload via WorkManager.
 *
 * Key improvements:
 * - Handles MessageClient messages (not just DataItems)
 * - Reassembles chunked messages
 * - Uses WorkManager for reliable background uploads
 * - Sends ACK to watch for successful receipt
 */
class WatchDataListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchDataListener"
        private const val CHUNK_TIMEOUT_MS = 300000L  // 5 minutes (was 1min) - watch retries with exponential backoff up to 30s
    }

    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Repository for data persistence
    private lateinit var repository: TremorDataRepository

    // Channel client for receiving batches via ChannelClient API
    private lateinit var channelClient: ChannelClient

    // Explicit channel callback (WearableListenerService callbacks don't always work for channels)
    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            Log.i(TAG, "★★★ ChannelCallback.onChannelOpened: ${channel.path}")

            if (channel.path == "/tremor_batch_channel") {
                serviceScope.launch(Dispatchers.IO) {
                    handleChannelBatch(channel)
                }
            }
        }

        override fun onChannelClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
            Log.i(TAG, "Channel closed: ${channel.path}, reason: $closeReason")
        }
    }

    // In-memory storage for partial chunks being assembled
    // Key: batchId, Value: ChunkAssembly
    private val chunkAssemblies = mutableMapOf<String, ChunkAssembly>()

    data class ChunkAssembly(
        val batchId: String,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var lastChunkTime: Long = System.currentTimeMillis(),  // Update on each new chunk (not firstChunkTime)
        var isCompressed: Boolean = false  // Track if data is compressed
    ) {
        fun isComplete(): Boolean = chunks.size == totalChunks

        fun assembleData(): ByteArray? {
            if (!isComplete()) return null

            // Concatenate chunks in order
            val result = mutableListOf<Byte>()
            for (i in 0 until totalChunks) {
                val chunk = chunks[i] ?: return null
                result.addAll(chunk.toList())
            }
            return result.toByteArray()
        }
    }

    /**
     * Decompress GZIP data
     */
    private fun decompressData(data: ByteArray): ByteArray {
        return try {
            ByteArrayInputStream(data).use { bis ->
                GZIPInputStream(bis).use { gzip ->
                    ByteArrayOutputStream().use { bos ->
                        val buffer = ByteArray(1024)
                        var len: Int
                        while (gzip.read(buffer).also { len = it } != -1) {
                            bos.write(buffer, 0, len)
                        }
                        bos.toByteArray()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress data: ${e.message}")
            data // Return original if decompression fails
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WatchDataListenerService created - ready to receive messages from watch")

        // Initialize repository
        repository = TremorDataRepository(this)

        // Initialize channel client
        channelClient = Wearable.getChannelClient(this)

        // CRITICAL: Register explicit channel callback
        // WearableListenerService automatic callbacks don't reliably work for ChannelClient
        channelClient.registerChannelCallback(channelCallback)
        Log.i(TAG, "✓ Registered explicit ChannelCallback")

        // Load persisted chunk assemblies from disk
        loadChunkAssemblies()

        // Start periodic cleanup of stale chunk assemblies
        startChunkCleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "WatchDataListenerService destroyed")

        // Unregister channel callback
        try {
            channelClient.unregisterChannelCallback(channelCallback)
            Log.d(TAG, "Unregistered ChannelCallback")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister channel callback: ${e.message}")
        }

        // Persist chunk assemblies to disk before service dies
        saveChunkAssemblies()

        // Cancel all coroutines
        serviceScope.cancel()
    }

    /**
     * Handle messages from watch (MessageClient)
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "★★★ onMessageReceived! Path: ${messageEvent.path}, Size: ${messageEvent.data.size} bytes, Source: ${messageEvent.sourceNodeId}")

        when {
            messageEvent.path.startsWith(Constants.MESSAGE_PATH_TREMOR_CHUNK) -> {
                Log.d(TAG, "Processing tremor chunk message")
                handleTremorChunk(messageEvent)
            }
            messageEvent.path.startsWith(Constants.PATH_HEARTBEAT) -> {
                Log.d(TAG, "Processing heartbeat message")
                handleHeartbeatMessage(messageEvent.data)
            }
            messageEvent.path.startsWith(Constants.MESSAGE_PATH_DIAGNOSTIC_EVENT) -> {
                Log.d(TAG, "Processing diagnostic event message")
                handleDiagnosticEvent(messageEvent.data)
            }
            messageEvent.path.startsWith(Constants.MESSAGE_PATH_LOG_RESPONSE) -> {
                Log.d(TAG, "Processing log response message")
                handleLogResponse(messageEvent.data)
            }
            else -> {
                Log.w(TAG, "Unknown message path: ${messageEvent.path}")
            }
        }
    }

    /**
     * Handle DataItems - PRIMARY METHOD for reliable data reception
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.i(TAG, "★★★ onDataChanged called! Received ${dataEvents.count} data events")

        dataEvents.forEach { event ->
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val path = event.dataItem.uri.path
                    Log.d(TAG, "Data changed at path: $path")

                    // PRIMARY: DataClient reliable batch reception
                    if (path?.startsWith("/tremor_batch/") == true) {
                        handleReliableTremorBatch(event.dataItem)
                    } else if (path?.startsWith(Constants.PATH_TREMOR_BATCH) == true) {
                        handleTremorBatchDataItem(event.dataItem)
                    } else if (path?.startsWith(Constants.PATH_HEARTBEAT) == true) {
                        handleHeartbeatDataItem(event.dataItem)
                    }
                }
                DataEvent.TYPE_DELETED -> {
                    Log.d(TAG, "Data deleted: ${event.dataItem.uri}")
                }
            }
        }
    }

    /**
     * Handle channel opened from watch - NEW ChannelClient API
     * This is the modern approach that eliminates manual chunking.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        Log.i(TAG, "★★★ Channel opened from watch: ${channel.path}")

        if (channel.path == "/tremor_batch_channel") {
            serviceScope.launch(Dispatchers.IO) {
                handleChannelBatch(channel)
            }
        }
    }

    /**
     * Handle batch data from channel stream.
     * Reads compressed batch data, decompresses, and processes it.
     */
    private suspend fun handleChannelBatch(channel: ChannelClient.Channel) {
        try {
            Log.i(TAG, "Reading batch data from channel: ${channel.path}")

            // Get input stream from channel
            val inputStream = channelClient.getInputStream(channel).await()

            // Read length prefix (4 bytes, big-endian)
            val lengthBytes = ByteArray(4)
            val lengthRead = inputStream.read(lengthBytes)
            if (lengthRead != 4) {
                Log.e(TAG, "Failed to read length prefix from channel")
                inputStream.close()
                return
            }

            val dataLength = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                    ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                    ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                    (lengthBytes[3].toInt() and 0xFF)

            Log.d(TAG, "Reading $dataLength bytes of compressed data from channel")

            // Read compressed data
            val compressedData = ByteArray(dataLength)
            var totalRead = 0
            while (totalRead < dataLength) {
                val bytesRead = inputStream.read(compressedData, totalRead, dataLength - totalRead)
                if (bytesRead == -1) {
                    Log.e(TAG, "Unexpected end of stream while reading channel data")
                    inputStream.close()
                    return
                }
                totalRead += bytesRead
            }

            // Close input stream
            inputStream.close()

            Log.d(TAG, "Read $totalRead bytes from channel, decompressing...")

            // Decompress data
            val jsonBytes = decompressData(compressedData)
            val jsonString = String(jsonBytes, Charsets.UTF_8)

            Log.d(TAG, "Decompressed to ${jsonBytes.size} bytes, parsing batch...")

            // Parse and process batch
            val batch = TremorBatch.fromJsonString(jsonString)
            processBatch(batch)

            Log.i(TAG, "✓ Successfully processed batch ${batch.batchId} from channel (${batch.samples.size} samples)")

            // Close channel
            channelClient.close(channel).await()
            Log.d(TAG, "Channel closed")

        } catch (e: Exception) {
            Log.e(TAG, "Error handling channel batch: ${e.message}", e)
            // Try to close channel on error
            try {
                channelClient.close(channel).await()
            } catch (closeError: Exception) {
                Log.w(TAG, "Failed to close channel after error: ${closeError.message}")
            }
        }
    }

    /**
     * Handle tremor chunk message (MessageClient)
     */
    private fun handleTremorChunk(messageEvent: MessageEvent) {
        try {
            val payload = messageEvent.data

            // Find separator byte (0) between metadata and data
            val separatorIndex = payload.indexOf(0.toByte())
            if (separatorIndex == -1) {
                Log.e(TAG, "Invalid message format: no separator found")
                return
            }

            // Parse metadata
            val metadataBytes = payload.copyOfRange(0, separatorIndex)
            val metadata = JSONObject(String(metadataBytes, Charsets.UTF_8))

            val batchId = metadata.getString(Constants.KEY_BATCH_ID)
            val chunkIndex = metadata.getInt(Constants.KEY_CHUNK_INDEX)
            val totalChunks = metadata.getInt(Constants.KEY_TOTAL_CHUNKS)
            val isCompressed = metadata.optBoolean("compressed", false)

            // Extract chunk data
            val chunkData = payload.copyOfRange(separatorIndex + 1, payload.size)

            Log.d(TAG, "Received chunk $chunkIndex/$totalChunks for batch $batchId (${chunkData.size} bytes, compressed=$isCompressed)")

            // Single chunk message (no assembly needed)
            if (totalChunks == 1) {
                try {
                    val jsonData = if (isCompressed) {
                        Log.d(TAG, "Decompressing single chunk for batch $batchId")
                        decompressData(chunkData)
                    } else {
                        chunkData
                    }
                    val jsonString = String(jsonData, Charsets.UTF_8)
                    Log.d(TAG, "Parsing single chunk batch $batchId (${jsonString.length} chars)")
                    val batch = TremorBatch.fromJsonString(jsonString)
                    processBatch(batch)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process single chunk batch $batchId: ${e.message}", e)
                }
                return
            }

            // Multi-chunk message - assemble
            synchronized(chunkAssemblies) {
                val assembly = chunkAssemblies.getOrPut(batchId) {
                    ChunkAssembly(batchId, totalChunks, isCompressed = isCompressed)
                }

                // Add chunk and update lastChunkTime (prevent stale removal while chunks are arriving)
                assembly.chunks[chunkIndex] = chunkData
                assembly.lastChunkTime = System.currentTimeMillis()
                if (isCompressed) assembly.isCompressed = true

                Log.d(TAG, "Chunk assembly progress for $batchId: ${assembly.chunks.size}/$totalChunks")

                // Check if complete
                if (assembly.isComplete()) {
                    var assembledData = assembly.assembleData()
                    if (assembledData != null) {
                        // Decompress if needed
                        if (assembly.isCompressed) {
                            val originalSize = assembledData.size
                            assembledData = decompressData(assembledData)
                            Log.d(TAG, "Decompressed batch $batchId: $originalSize -> ${assembledData.size} bytes")
                        }

                        val jsonString = String(assembledData, Charsets.UTF_8)
                        Log.d(TAG, "Parsing assembled batch $batchId (${jsonString.length} chars from $totalChunks chunks)")
                        val batch = TremorBatch.fromJsonString(jsonString)
                        processBatch(batch)
                        chunkAssemblies.remove(batchId)
                        Log.i(TAG, "✓ Successfully assembled and processed batch $batchId from $totalChunks chunks")
                    } else {
                        Log.e(TAG, "Failed to assemble data for batch $batchId")
                        chunkAssemblies.remove(batchId)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle tremor chunk: ${e.message}", e)
        }
    }

    /**
     * Handle legacy DataItem batch (backward compatibility)
     */
    private fun handleTremorBatchDataItem(dataItem: DataItem) {
        try {
            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            val jsonString = dataMap.getString(Constants.KEY_BATCH_DATA)

            if (jsonString.isNullOrEmpty()) {
                Log.w(TAG, "Received empty batch data")
                return
            }

            Log.d(TAG, "Received legacy DataItem batch (${jsonString.length} bytes)")

            val batch = TremorBatch.fromJsonString(jsonString)
            processBatch(batch)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle legacy batch DataItem: ${e.message}", e)
        }
    }

    /**
     * Process a complete batch (either single message or assembled from chunks)
     * CRITICAL: Save to local storage IMMEDIATELY to prevent data loss
     * CRITICAL FIX: Stop processing if immediate save fails - don't continue with data that will be lost
     */
    private fun processBatch(batch: TremorBatch) {
        try {
            Log.i(TAG, "✓ Processing batch ${batch.batchId} with ${batch.samples.size} samples (timestamp: ${batch.timestamp})")

            // STEP 1: CRITICAL - Save to local storage IMMEDIATELY (before anything else)
            try {
                saveToLocalStorageImmediate(batch)
                Log.i(TAG, "✓ Saved batch ${batch.batchId} to local storage immediately")
            } catch (e: Exception) {
                Log.e(TAG, "❌ CRITICAL: Failed to save batch ${batch.batchId} to local storage: ${e.message}", e)
                // DO NOT CONTINUE - data will be lost!
                return
            }

            // STEP 2: Record data reception (for UI/notifications)
            NotificationHelper.recordDataReceived(this)

            // Save batch to upload queue for InfluxDB sync
            val saved = saveBatchToQueue(batch)
            if (!saved) {
                Log.e(TAG, "Failed to save batch ${batch.batchId} to queue")
                // Data is already in local storage, so this is not critical
            } else {
                Log.i(TAG, "✓ Saved batch ${batch.batchId} to upload queue")
            }

            // Update notification
            NotificationHelper.updateNotification(
                this,
                ServiceStatus.RECEIVING,
                "${batch.samples.size} samples"
            )

            // Trigger UploadService (only if on home network)
            triggerUploadService()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process batch: ${e.message}", e)
            Log.e(TAG, "Batch details - batchId: ${batch.batchId}, sampleCount: ${batch.samples.size}", e)
        }
    }

    /**
     * Save batch to local storage immediately (before upload queue)
     * This ensures data is never lost even if InfluxDB is unavailable
     * Uses repository for centralized data management with caching
     * 
     * CRITICAL: This must be SYNCHRONOUS to ensure data is saved before function returns!
     * Using runBlocking because we MUST wait for save to complete before continuing.
     */
    private fun saveToLocalStorageImmediate(batch: TremorBatch) {
        // Use runBlocking to ensure data is saved IMMEDIATELY - not async!
        // This is intentional: we must guarantee data is persisted before continuing
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            repository.saveTremorBatch(batch)
                .onSuccess {
                    Log.d(TAG, "Saved batch ${batch.batchId} to local storage via repository")
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to save batch ${batch.batchId} to local storage: ${e.message}", e)
                    throw e  // Re-throw to trigger error handling in caller
                }
        }
    }

    /**
     * Trigger upload worker to process queue using WorkManager.
     * WorkManager provides better battery management and respects system constraints.
     *
     * Note: WorkManager automatically handles network constraints and battery optimization,
     * so we don't need manual checks here. The worker will execute when conditions are met.
     */
    private fun triggerUploadService() {
        try {
            // Enqueue all pending batches with WorkManager
            // WorkManager will intelligently schedule uploads based on:
            // - Network availability (WiFi for auto uploads)
            // - Battery state (not low for auto uploads)
            // - Doze mode and background restrictions
            TremorUploadWorker.enqueueAllPending(this, isManualUpload = false)
            Log.d(TAG, "✓ Enqueued upload work with WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue upload work: ${e.message}", e)
        }
    }

    /**
     * Handle reliable tremor batch via DataClient (PRIMARY METHOD)
     * CRITICAL FIX: Add immediate local storage save (was missing)
     */
    private fun handleReliableTremorBatch(dataItem: DataItem) {
        try {
            Log.i(TAG, "★★★ RELIABLE BATCH RECEIVED via DataClient: ${dataItem.uri.path}")

            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            val batchId = dataMap.getString(Constants.KEY_BATCH_ID) ?: "unknown"
            val timestamp = dataMap.getLong(Constants.KEY_TIMESTAMP, 0L)
            var batchData = dataMap.getByteArray("batch_data")
            val source = dataMap.getString("source", "unknown")
            val isCompressed = dataMap.getBoolean("compressed", false)

            if (batchData != null) {
                // Decompress if needed
                if (isCompressed) {
                    val originalSize = batchData.size
                    batchData = decompressData(batchData)
                    Log.i(TAG, "Decompressed reliable batch $batchId: $originalSize -> ${batchData.size} bytes")
                }

                Log.i(TAG, "Processing reliable batch $batchId from $source (${batchData.size} bytes)")

                // Parse the batch JSON
                val batchJson = String(batchData, Charsets.UTF_8)
                val batch = TremorBatch.fromJsonString(batchJson)

                // CRITICAL: Save to local storage IMMEDIATELY (consistent with other paths)
                try {
                    saveToLocalStorageImmediate(batch)
                    Log.i(TAG, "✓ Saved reliable batch $batchId to local storage immediately (${batch.samples.size} samples)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save reliable batch $batchId to local storage: ${e.message}", e)
                    // Continue - queue may still work, and watch will retry
                }

                // Save batch to queue directory for UploadService
                saveBatchToQueue(batch)

                // Trigger UploadService (only if on home network)
                triggerUploadService()

                Log.i(TAG, "✓ Successfully processed reliable batch $batchId with ${batch.samples.size} samples")
            } else {
                Log.e(TAG, "No batch data found in DataItem for batch $batchId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing reliable tremor batch: ${e.message}", e)
        }
    }

    /**
     * Handle heartbeat message
     */
    private fun handleHeartbeatMessage(data: ByteArray) {
        try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val timestamp = json.getLong(Constants.KEY_TIMESTAMP)
            val serviceUptime = json.getLong(Constants.KEY_SERVICE_UPTIME)
            val monitoringState = json.getString(Constants.KEY_MONITORING_STATE)
            val isBatteryOptimized = json.optBoolean("is_battery_optimized", false)

            Log.i(TAG, "★ Received heartbeat from watch (uptime: ${serviceUptime / 1000}s, state: $monitoringState, battery_opt: $isBatteryOptimized)")

            // Record heartbeat
            val prefs = getSharedPreferences("heartbeat_prefs", MODE_PRIVATE)
            prefs.edit()
                .putLong("last_heartbeat_time", System.currentTimeMillis())
                .putLong("watch_service_uptime", serviceUptime)
                .putString("watch_monitoring_state", monitoringState)
                .putBoolean("watch_battery_optimized", isBatteryOptimized)
                .apply()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle heartbeat message: ${e.message}", e)
        }
    }
    
    /**
     * Handle diagnostic event message from watch.
     * These events track state changes like charging, off-body, monitoring paused/resumed.
     * Events are logged locally and uploaded to InfluxDB for analysis.
     */
    private fun handleDiagnosticEvent(data: ByteArray) {
        try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val eventType = json.optString("event_type", "unknown")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            
            Log.i(TAG, "★ Received diagnostic event: $eventType")
            
            // Log locally
            val prefs = getSharedPreferences("diagnostic_events", MODE_PRIVATE)
            prefs.edit()
                .putLong("last_event_time", System.currentTimeMillis())
                .putString("last_event_type", eventType)
                .apply()
            
            // Queue for InfluxDB upload
            saveDiagnosticEventToQueue(eventType, timestamp, json)
            
            // Trigger upload if on home network
            triggerUploadService()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle diagnostic event: ${e.message}", e)
        }
    }
    
    /**
     * Save diagnostic event to upload queue for InfluxDB
     */
    private fun saveDiagnosticEventToQueue(eventType: String, timestamp: Long, eventData: JSONObject) {
        try {
            val queueDir = File(filesDir, "diagnostic_events_queue")
            if (!queueDir.exists()) {
                queueDir.mkdirs()
            }
            
            // Create event file with timestamp in name for ordering
            val eventFile = File(queueDir, "event_${timestamp}_${System.nanoTime()}.json")
            eventData.put("event_type", eventType)
            eventData.put("timestamp", timestamp)
            eventFile.writeText(eventData.toString())
            
            Log.d(TAG, "Queued diagnostic event: $eventType")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save diagnostic event to queue: ${e.message}", e)
        }
    }

    /**
     * Handle log response from watch.
     * Watch sends collected logs back to phone in response to log request.
     */
    private fun handleLogResponse(data: ByteArray) {
        try {
            val logs = String(data, Charsets.UTF_8)
            Log.i(TAG, "★ Received ${logs.length} bytes of watch logs")
            
            // Store logs in SharedPreferences for MainActivity to retrieve
            val prefs = getSharedPreferences("watch_logs", MODE_PRIVATE)
            prefs.edit()
                .putString("last_logs", logs)
                .putLong("last_logs_time", System.currentTimeMillis())
                .apply()
                
            Log.d(TAG, "Cached watch logs to SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle log response: ${e.message}", e)
        }
    }

    /**
     * Handle legacy heartbeat DataItem
     */
    private fun handleHeartbeatDataItem(dataItem: DataItem) {
        try {
            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            val timestamp = dataMap.getLong(Constants.KEY_TIMESTAMP)
            val serviceUptime = dataMap.getLong(Constants.KEY_SERVICE_UPTIME)
            val monitoringState = dataMap.getString(Constants.KEY_MONITORING_STATE) ?: "unknown"

            Log.i(TAG, "★ Received legacy heartbeat DataItem (uptime: ${serviceUptime / 1000}s, state: $monitoringState)")

            // Record heartbeat
            val prefs = getSharedPreferences("heartbeat_prefs", MODE_PRIVATE)
            prefs.edit()
                .putLong("last_heartbeat_time", System.currentTimeMillis())
                .putLong("watch_service_uptime", serviceUptime)
                .putString("watch_monitoring_state", monitoringState)
                .apply()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle legacy heartbeat: ${e.message}", e)
        }
    }

    /**
     * Save batch to upload queue
     * @return true if saved successfully, false otherwise
     */
    private fun saveBatchToQueue(batch: TremorBatch): Boolean {
        try {
            val queueDir = File(filesDir, "upload_queue")
            if (!queueDir.exists()) {
                val created = queueDir.mkdirs()
                if (!created && !queueDir.exists()) {
                    Log.e(TAG, "Failed to create upload_queue directory")
                    return false
                }
            }

            val batchFile = File(queueDir, "batch_${batch.batchId}.json")
            val jsonString = batch.toJsonString()
            
            if (jsonString.isBlank()) {
                Log.e(TAG, "Generated empty JSON string for batch ${batch.batchId}")
                return false
            }
            
            batchFile.writeText(jsonString)
            
            val fileSize = batchFile.length()
            Log.d(TAG, "Saved batch ${batch.batchId} to upload queue (${fileSize} bytes)")
            
            if (fileSize == 0L) {
                Log.e(TAG, "Warning: Saved file is empty for batch ${batch.batchId}")
                return false
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save batch ${batch.batchId} to queue: ${e.message}", e)
            return false
        }
    }

    /**
     * Get file path for a batch in the queue
     */
    private fun getBatchFilePath(batchId: String): String {
        val queueDir = File(filesDir, "upload_queue")
        return File(queueDir, "batch_${batchId}.json").absolutePath
    }

    /**
     * Clean up stale chunk assemblies (incomplete after timeout)
     */
    private fun startChunkCleanup() {
        // This could be enhanced with a scheduled task, but for now we clean on each new message
        synchronized(chunkAssemblies) {
            val now = System.currentTimeMillis()
            val staleAssemblies = chunkAssemblies.filter { (_, assembly) ->
                now - assembly.lastChunkTime > CHUNK_TIMEOUT_MS  // Use lastChunkTime instead of firstChunkTime
            }

            staleAssemblies.forEach { (batchId, assembly) ->
                Log.w(TAG, "Removing stale chunk assembly for batch $batchId (${assembly.chunks.size}/${assembly.totalChunks} chunks received, last chunk ${(now - assembly.lastChunkTime) / 1000}s ago)")
                chunkAssemblies.remove(batchId)
            }
        }
    }

    /**
     * Save chunk assemblies to disk to survive service restarts
     */
    private fun saveChunkAssemblies() {
        try {
            val chunkDir = File(filesDir, "chunk_assemblies")
            if (!chunkDir.exists()) {
                chunkDir.mkdirs()
            }

            synchronized(chunkAssemblies) {
                chunkAssemblies.forEach { (batchId, assembly) ->
                    val file = File(chunkDir, "assembly_$batchId.dat")
                    file.outputStream().use { fos ->
                        // Write metadata
                        fos.write(assembly.totalChunks)
                        fos.write((assembly.lastChunkTime shr 56).toByte().toInt())
                        fos.write((assembly.lastChunkTime shr 48).toByte().toInt())
                        fos.write((assembly.lastChunkTime shr 40).toByte().toInt())
                        fos.write((assembly.lastChunkTime shr 32).toByte().toInt())
                        fos.write((assembly.lastChunkTime shr 24).toByte().toInt())
                        fos.write((assembly.lastChunkTime shr 16).toByte().toInt())
                        fos.write((assembly.lastChunkTime shr 8).toByte().toInt())
                        fos.write(assembly.lastChunkTime.toByte().toInt())

                        // Write isCompressed flag (1 byte: 0=false, 1=true)
                        fos.write(if (assembly.isCompressed) 1 else 0)

                        // Write each chunk
                        assembly.chunks.forEach { (index, data) ->
                            fos.write(index)
                            fos.write((data.size shr 8).toByte().toInt())
                            fos.write(data.size.toByte().toInt())
                            fos.write(data)
                        }
                    }
                }
                Log.d(TAG, "Saved ${chunkAssemblies.size} chunk assemblies to disk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chunk assemblies: ${e.message}", e)
        }
    }

    /**
     * Load chunk assemblies from disk after service restart
     */
    private fun loadChunkAssemblies() {
        try {
            val chunkDir = File(filesDir, "chunk_assemblies")
            if (!chunkDir.exists()) {
                return
            }

            val files = chunkDir.listFiles { file ->
                file.name.startsWith("assembly_") && file.name.endsWith(".dat")
            } ?: return

            synchronized(chunkAssemblies) {
                files.forEach { file ->
                    try {
                        val batchId = file.name.removePrefix("assembly_").removeSuffix(".dat")
                        file.inputStream().use { fis ->
                            // Read metadata
                            val totalChunks = fis.read()
                            val lastChunkTime = (fis.read().toLong() shl 56) or
                                               (fis.read().toLong() shl 48) or
                                               (fis.read().toLong() shl 40) or
                                               (fis.read().toLong() shl 32) or
                                               (fis.read().toLong() shl 24) or
                                               (fis.read().toLong() shl 16) or
                                               (fis.read().toLong() shl 8) or
                                               fis.read().toLong()

                            // Read isCompressed flag
                            val isCompressed = fis.read() == 1

                            val chunks = mutableMapOf<Int, ByteArray>()

                            // Read chunks until EOF
                            while (fis.available() > 0) {
                                val index = fis.read()
                                if (index == -1) break

                                val size = (fis.read() shl 8) or fis.read()
                                val data = ByteArray(size)
                                fis.read(data)
                                chunks[index] = data
                            }

                            chunkAssemblies[batchId] = ChunkAssembly(
                                batchId = batchId,
                                totalChunks = totalChunks,
                                chunks = chunks,
                                lastChunkTime = lastChunkTime,
                                isCompressed = isCompressed
                            )
                        }
                        // Delete file after loading
                        file.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load chunk assembly from ${file.name}: ${e.message}")
                        file.delete()
                    }
                }
                Log.d(TAG, "Loaded ${chunkAssemblies.size} chunk assemblies from disk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chunk assemblies: ${e.message}", e)
        }
    }
}
