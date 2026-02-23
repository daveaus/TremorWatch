package com.opensource.tremorwatch.phone

import android.content.Intent
import android.util.Log
import com.opensource.tremorwatch.phone.data.TremorDataRepository
import com.opensource.tremorwatch.phone.database.CalibrationDataEntity
import com.opensource.tremorwatch.phone.database.MedicationIngestionEntity
import com.opensource.tremorwatch.phone.database.SubjectiveRatingEntity
import com.opensource.tremorwatch.phone.database.TremorDao
import com.opensource.tremorwatch.phone.database.TremorRoomDatabase
import androidx.room.withTransaction
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.google.android.gms.wearable.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.math.abs

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
        private const val CHANNEL_PATH_TREMOR_BATCH = "/tremor_batch_channel"
        private const val CHANNEL_PATH_CALIBRATION = "/calibration_file_channel"

        private const val CHANNEL_READ_TIMEOUT_MS = 30_000L

        // Compressed payload guardrails (defense in depth against corrupted framing / OOM).
        private const val MAX_TREMOR_BATCH_COMPRESSED_BYTES = 1 * 1024 * 1024 // 1MB
        private const val MAX_CALIBRATION_COMPRESSED_BYTES = 10 * 1024 * 1024 // 10MB

        // Decompression output guardrails (protect against "zip bombs" / corrupt payloads).
        private const val MAX_TREMOR_BATCH_DECOMPRESSED_BYTES = 5 * 1024 * 1024 // 5MB
        private const val MAX_CALIBRATION_DECOMPRESSED_BYTES = 25 * 1024 * 1024 // 25MB

        // Phone-side diagnostic event retention (events are best-effort telemetry, not user data).
        private const val DIAG_MAX_EVENTS = 100
        private const val DIAG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        // Calibration reparse is a one-time migration; throttle to once per 12 hours
        // to avoid filesystem/DB work on every service restart.
        private const val CAL_REPARSE_COOLDOWN_MS = 12L * 60 * 60 * 1000
        private const val CAL_REPARSE_PREFS = "cal_reparse_prefs"
        private const val CAL_REPARSE_LAST_RUN_KEY = "last_reparse_run_ms"
    }

    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Repository for data persistence
    private lateinit var repository: TremorDataRepository

    // Channel client for receiving batches via ChannelClient API
    private lateinit var channelClient: ChannelClient

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

            // Concatenate chunks in order using ByteArrayOutputStream
            // (avoids per-byte boxing overhead of mutableListOf<Byte>)
            val bos = ByteArrayOutputStream()
            for (i in 0 until totalChunks) {
                val chunk = chunks[i] ?: return null
                bos.write(chunk)
            }
            return bos.toByteArray()
        }
    }

    private data class DetectedMetrics(
        val severity: Double?,
        val confidence: Float?,
        val frequencyHz: Float?
    )

    /**
     * Decompress GZIP data
     */
    private fun decompressData(data: ByteArray): ByteArray? {
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
            Log.e(TAG, "Failed to decompress data (${data.size} bytes): ${e.message}", e)
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WatchDataListenerService created - ready to receive messages from watch")

        // Initialize repository
        repository = TremorDataRepository(this)

        // Initialize channel client
        channelClient = Wearable.getChannelClient(this)

        // One-time migration: reparse orphaned calibration files (opus46 Issue 1).
        // Throttled to once per 12 hours — this is idempotent but still does FS+DB
        // work, and running it on every service restart generates spurious warnings
        // for rating IDs that simply have no calibration data.
        val reparsePrefs = getSharedPreferences(CAL_REPARSE_PREFS, MODE_PRIVATE)
        val lastReparse = reparsePrefs.getLong(CAL_REPARSE_LAST_RUN_KEY, 0L)
        if (System.currentTimeMillis() - lastReparse >= CAL_REPARSE_COOLDOWN_MS) {
            serviceScope.launch {
                reparseOrphanedCalibrationFiles()
                reparsePrefs.edit().putLong(CAL_REPARSE_LAST_RUN_KEY, System.currentTimeMillis()).apply()
            }
        } else {
            Log.d(TAG, "Skipping calibration reparse (cooldown active)")
        }

        // Load persisted chunk assemblies from disk
        loadChunkAssemblies()

        // Start periodic cleanup of stale chunk assemblies
        startChunkCleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "WatchDataListenerService destroyed")

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
            messageEvent.path.startsWith(Constants.MESSAGE_PATH_RATING) -> {
                Log.d(TAG, "Processing subjective rating message")
                handleSubjectiveRating(messageEvent.data)
            }
            messageEvent.path.startsWith(Constants.MESSAGE_PATH_CALIBRATION_DATA) -> {
                Log.d(TAG, "Processing calibration data message")
                handleCalibrationData(messageEvent.data)
            }
            // IG-04: Silently ignore WearOS notification bridge paths (no processing needed)
            messageEvent.path.startsWith("/notification") -> {
                // No-op: WearOS notification bridge message, not TremorWatch data
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
        Log.i(TAG, "onChannelOpened: ${channel.path}")

        when (channel.path) {
            CHANNEL_PATH_TREMOR_BATCH -> {
                serviceScope.launch(Dispatchers.IO) {
                    handleChannelBatch(channel)
                }
            }
            CHANNEL_PATH_CALIBRATION -> {
                serviceScope.launch(Dispatchers.IO) {
                    handleCalibrationChannel(channel)
                }
            }
            else -> {
                Log.w(TAG, "Unknown channel path: ${channel.path} (closing)")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        channelClient.close(channel).await()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read == -1) {
                throw EOFException("Unexpected EOF (needed ${buffer.size} bytes, got $offset)")
            }
            offset += read
        }
    }

    private fun decompressDataWithLimit(data: ByteArray, maxOutputBytes: Int): ByteArray? {
        return try {
            ByteArrayInputStream(data).use { bis ->
                GZIPInputStream(bis).use { gzip ->
                    ByteArrayOutputStream().use { bos ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val len = gzip.read(buffer)
                            if (len == -1) break
                            if (bos.size() + len > maxOutputBytes) {
                                throw IllegalStateException(
                                    "Decompressed payload exceeded limit: ${bos.size() + len} > $maxOutputBytes"
                                )
                            }
                            bos.write(buffer, 0, len)
                        }
                        bos.toByteArray()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress data (${data.size} bytes): ${e.message}", e)
            null
        }
    }

    /**
     * Handle batch data from channel stream.
     * Reads compressed batch data, decompresses, and processes it.
     */
    private suspend fun handleChannelBatch(channel: ChannelClient.Channel) {
        var compressedData: ByteArray? = null
        try {
            Log.i(TAG, "Reading batch data from channel: ${channel.path}")

            compressedData = withTimeout(CHANNEL_READ_TIMEOUT_MS) {
                channelClient.getInputStream(channel).await().use { input ->
                    // Read length prefix (4 bytes, big-endian)
                    val lengthBytes = ByteArray(4)
                    input.readFully(lengthBytes)
                    val dataLength = readInt(lengthBytes)

                    if (dataLength <= 0 || dataLength > MAX_TREMOR_BATCH_COMPRESSED_BYTES) {
                        Log.e(TAG, "Invalid tremor batch length prefix: $dataLength bytes")
                        return@use null
                    }

                    val payload = ByteArray(dataLength)
                    input.readFully(payload)
                    payload
                }
            } ?: return
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Service lifecycle cancellation — expected, not an error.
            Log.i(TAG, "Channel batch read cancelled (service lifecycle)")
            throw e  // Always rethrow CancellationException in coroutines
        } catch (e: Exception) {
            Log.e(TAG, "Error reading channel batch: ${e.message}", e)
            return
        } finally {
            // Close channel even on failure (double-close is fine; ignore errors).
            try {
                channelClient.close(channel).await()
            } catch (_: Exception) {}
        }

        try {
            val jsonBytes = decompressDataWithLimit(compressedData, MAX_TREMOR_BATCH_DECOMPRESSED_BYTES)
            if (jsonBytes == null) {
                Log.e(TAG, "Channel batch decompression failed, discarding payload")
                return
            }
            val jsonString = String(jsonBytes, Charsets.UTF_8)
            val batch = TremorBatch.fromJsonString(jsonString)
            processBatch(batch)
            Log.i(TAG, "✓ Successfully processed batch ${batch.batchId} from channel (${batch.samples.size} samples)")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing channel batch payload: ${e.message}", e)
        }
    }

    /**
     * Handle calibration file received from watch via ChannelClient.
     *
     * Protocol:
     * 1. Filename length (4 bytes, big-endian)
     * 2. Filename (UTF-8 bytes)
     * 3. Compressed data length (4 bytes, big-endian)
     * 4. GZIP compressed file data
     */
    private suspend fun handleCalibrationChannel(channel: ChannelClient.Channel) {
        data class CalibrationPayload(
            val filename: String,
            val compressedData: ByteArray
        )

        var payload: CalibrationPayload? = null
        try {
            Log.i(TAG, "★ Receiving calibration file via channel: ${channel.path}")

            payload = withTimeout(CHANNEL_READ_TIMEOUT_MS) {
                channelClient.getInputStream(channel).await().use { input ->
                    // Read filename length (4 bytes, big-endian)
                    val filenameLenBytes = ByteArray(4)
                    input.readFully(filenameLenBytes)
                    val filenameLen = readInt(filenameLenBytes)
                    if (filenameLen <= 0 || filenameLen > 512) {
                        Log.e(TAG, "Invalid calibration filename length: $filenameLen")
                        return@use null
                    }

                    // Read filename
                    val filenameBytes = ByteArray(filenameLen)
                    input.readFully(filenameBytes)
                    val filename = String(filenameBytes, Charsets.UTF_8)
                    Log.d(TAG, "Calibration filename: $filename")

                    // Read compressed data length (4 bytes, big-endian)
                    val dataLenBytes = ByteArray(4)
                    input.readFully(dataLenBytes)
                    val compressedLen = readInt(dataLenBytes)
                    if (compressedLen <= 0 || compressedLen > MAX_CALIBRATION_COMPRESSED_BYTES) {
                        Log.e(TAG, "Invalid calibration payload length: $compressedLen bytes")
                        return@use null
                    }

                    val compressedData = ByteArray(compressedLen)
                    input.readFully(compressedData)

                    CalibrationPayload(filename, compressedData)
                }
            } ?: return
        } catch (e: Exception) {
            Log.e(TAG, "Error reading calibration channel: ${e.message}", e)
            return
        } finally {
            try {
                channelClient.close(channel).await()
            } catch (_: Exception) {}
        }

        try {
            val decompressed = decompressDataWithLimit(payload.compressedData, MAX_CALIBRATION_DECOMPRESSED_BYTES)
            if (decompressed == null) {
                Log.e(TAG, "Calibration decompression failed for ${payload.filename}, discarding")
                return
            }
            val calibrationJson = String(decompressed, Charsets.UTF_8)
            Log.i(TAG, "✓ Received calibration file: ${payload.filename} (${decompressed.size} bytes decompressed)")

            // Save to local calibration directory
            val calibrationDir = java.io.File(applicationContext.filesDir, "calibration")
            if (!calibrationDir.exists()) {
                calibrationDir.mkdirs()
            }
            val calibrationFile = java.io.File(calibrationDir, payload.filename)
            calibrationFile.writeText(calibrationJson)
            Log.i(TAG, "✓ Saved calibration file to: ${calibrationFile.absolutePath}")

            // Parse JSONL file and insert calibration data into database
            parseCalibrationFileIntoDB(calibrationJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing calibration payload: ${e.message}", e)
        }
    }

    /**
     * Parse a JSONL calibration file and insert samples into the database.
     * Format: one JSON object per line - header, then samples, then footer.
     * Links calibration data to the SubjectiveRatingEntity via ratingId.
     */
    private suspend fun parseCalibrationFileIntoDB(jsonlContent: String) {
        try {
            val lines = jsonlContent.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                Log.w(TAG, "Empty calibration file, skipping DB insert")
                return
            }

            var ratingId: String? = null
            val calibrationEntities = mutableListOf<CalibrationDataEntity>()

            for (line in lines) {
                try {
                    val obj = JSONObject(line)
                    // Schema v2 files may lack "type" field due to encodeDefaults=false bug
                    // Infer type from field presence if "type" is missing
                    val lineType = obj.optString("type", "").ifEmpty {
                        when {
                            obj.has("ratingId") && obj.has("schemaVersion") -> "header"
                            obj.has("timestamp") && obj.has("x") -> "sample"
                            obj.has("totalSamples") && obj.has("endTime") -> "footer"
                            else -> "unknown"
                        }
                    }

                    when (lineType) {
                        "header" -> {
                            ratingId = obj.getString("ratingId")
                            Log.d(TAG, "Calibration header: ratingId=$ratingId")
                        }
                        "sample" -> {
                            val rid = ratingId ?: continue

                            // Build metadata JSON with extended fields
                            val metadata = JSONObject()
                            if (obj.has("accelMagnitude")) metadata.put("accelMagnitude", obj.getDouble("accelMagnitude"))
                            if (obj.has("baselineMultiplier")) metadata.put("baselineMultiplier", obj.getDouble("baselineMultiplier"))
                            if (obj.has("tremorType")) metadata.put("tremorType", obj.getString("tremorType"))
                            if (obj.has("tremorTypeConfidence")) metadata.put("tremorTypeConfidence", obj.getDouble("tremorTypeConfidence"))
                            if (obj.has("isRestingState")) metadata.put("isRestingState", obj.getBoolean("isRestingState"))
                            if (obj.has("activityType")) metadata.put("activityType", obj.getString("activityType"))
                            if (obj.has("activityConfidence")) metadata.put("activityConfidence", obj.getDouble("activityConfidence"))
                            if (obj.has("activityAgeMs")) metadata.put("activityAgeMs", obj.getLong("activityAgeMs"))
                            if (obj.has("activityAdjustedConfidence")) metadata.put("activityAdjustedConfidence", obj.getDouble("activityAdjustedConfidence"))
                            if (obj.has("activityAdjustedSeverity")) metadata.put("activityAdjustedSeverity", obj.getDouble("activityAdjustedSeverity"))
                            if (obj.has("isReliableMeasurement")) metadata.put("isReliableMeasurement", obj.getBoolean("isReliableMeasurement"))
                            if (obj.has("excludeFromAnalysis")) metadata.put("excludeFromAnalysis", obj.getBoolean("excludeFromAnalysis"))

                            calibrationEntities.add(
                                CalibrationDataEntity(
                                    ratingId = rid,
                                    timestamp = obj.getLong("timestamp"),
                                    x = obj.getDouble("x").toFloat(),
                                    y = obj.getDouble("y").toFloat(),
                                    z = obj.getDouble("z").toFloat(),
                                    magnitude = obj.getDouble("magnitude").toFloat(),
                                    dominantFrequency = obj.optDouble("dominantFrequency", 0.0).toFloat(),
                                    tremorBandPower = obj.optDouble("tremorBandPower", 0.0).toFloat(),
                                    totalPower = obj.optDouble("totalPower", 0.0).toFloat(),
                                    bandRatio = obj.optDouble("bandRatio", 0.0).toFloat(),
                                    peakProminence = obj.optDouble("peakProminence", 0.0).toFloat(),
                                    confidence = obj.optDouble("confidence", 0.0).toFloat(),
                                    severity = obj.optDouble("severity", 0.0),
                                    isWorn = obj.optBoolean("isWorn", true),
                                    isCharging = obj.optBoolean("isCharging", false),
                                    metadataJson = if (metadata.length() > 0) metadata.toString() else null
                                )
                            )
                        }
                        "footer" -> {
                            Log.d(TAG, "Calibration footer: totalSamples=${obj.optInt("totalSamples")}")
                        }
                        else -> {
                            Log.w(TAG, "Unrecognized calibration line type, skipping: ${line.take(80)}")
                        }
                    }
                } catch (lineError: Exception) {
                    Log.w(TAG, "Skipping malformed calibration line: ${lineError.message}")
                }
            }

            if (calibrationEntities.isNotEmpty() && ratingId != null) {
                val db = TremorRoomDatabase.getDatabase(this@WatchDataListenerService)
                val dao = db.tremorDao()
                val targetRatingId = ratingId

                // Wrap in transaction to prevent partial insertion on crash
                db.withTransaction {
                    dao.insertCalibrationData(calibrationEntities)
                    dao.markRatingCalibrated(targetRatingId)
                }

                // Update metrics outside transaction (non-critical)
                updateDetectedMetricsFromCalibration(dao, targetRatingId, calibrationEntities)

                Log.i(TAG, "✓ Inserted ${calibrationEntities.size} calibration samples for rating $ratingId into DB")

                // Update prefs for UI
                val prefs = getSharedPreferences("calibration_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putLong("last_calibration_time", System.currentTimeMillis())
                    .putString("last_calibration_rating_id", targetRatingId)
                    .putInt("last_calibration_sample_count", calibrationEntities.size)
                    .apply()
            } else {
                Log.w(TAG, "No calibration samples parsed (ratingId=$ratingId, samples=${calibrationEntities.size})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse calibration file into DB: ${e.message}", e)
        }
    }

    /**
     * Idempotent migration: reparse calibration JSONL files that were saved to disk
     * but never ingested due to the encodeDefaults bug.
     *
     * Keyed by ratingId -- skips files whose ratingId already has calibration rows in DB.
     * Safe to run multiple times even if DB is partially populated.
     */
    private suspend fun reparseOrphanedCalibrationFiles() {
        val calibDir = File(filesDir, "calibration")
        if (!calibDir.exists()) return

        val db = TremorRoomDatabase.getDatabase(this)
        val dao = db.tremorDao()

        val files = calibDir.listFiles { f -> f.extension == "jsonl" } ?: return
        if (files.isEmpty()) return

        var reparsed = 0
        var skipped = 0

        for (file in files) {
            try {
                // Extract ratingId from first line to check if already ingested
                val firstLine = file.bufferedReader().use { it.readLine() } ?: continue
                val headerObj = JSONObject(firstLine)
                val ratingId = headerObj.optString("ratingId", "").ifEmpty {
                    // Try to find ratingId in any line (header without "type" field)
                    if (headerObj.has("schemaVersion")) headerObj.optString("ratingId", "") else ""
                }

                if (ratingId.isBlank()) {
                    Log.w(TAG, "Skipping ${file.name}: no ratingId found in header")
                    continue
                }

                // Check if this ratingId already has calibration data
                val existingCount = dao.getCalibrationCountForRating(ratingId)
                if (existingCount > 0) {
                    skipped++
                    continue
                }

                // Parse and ingest
                val content = file.readText()
                parseCalibrationFileIntoDB(content)
                reparsed++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reparse ${file.name}: ${e.message}")
            }
        }

        Log.i(TAG, "Calibration reparse complete: $reparsed ingested, $skipped already in DB, ${files.size} total files")
    }

    /**
     * Read 4 bytes as big-endian integer
     */
    private fun readInt(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
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
                serviceScope.launch {
                    try {
                        val jsonData = if (isCompressed) {
                            Log.d(TAG, "Decompressing single chunk for batch $batchId")
                            val decompressed = decompressData(chunkData)
                            if (decompressed == null) {
                                Log.e(TAG, "Decompression failed for single-chunk batch $batchId, discarding")
                                return@launch
                            }
                            decompressed
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
                            val decompressed = decompressData(assembledData)
                            if (decompressed == null) {
                                Log.e(TAG, "Decompression failed for assembled batch $batchId, discarding")
                                chunkAssemblies.remove(batchId)
                                return@synchronized
                            }
                            assembledData = decompressed
                            Log.d(TAG, "Decompressed batch $batchId: $originalSize -> ${assembledData.size} bytes")
                        }

                        val jsonString = String(assembledData, Charsets.UTF_8)
                        Log.d(TAG, "Parsing assembled batch $batchId (${jsonString.length} chars from $totalChunks chunks)")
                        val batch = TremorBatch.fromJsonString(jsonString)
                        val batchCopy = batch
                        val assemblyBatchId = batchId
                        chunkAssemblies.remove(batchId)
                        serviceScope.launch {
                            processBatch(batchCopy)
                            Log.i(TAG, "✓ Successfully assembled and processed batch $assemblyBatchId from $totalChunks chunks")
                        }
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
            serviceScope.launch {
                processBatch(batch)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle legacy batch DataItem: ${e.message}", e)
        }
    }

    /**
     * Process a complete batch (either single message or assembled from chunks)
     * CRITICAL: Save to local storage IMMEDIATELY to prevent data loss
     * CRITICAL FIX: Stop processing if immediate save fails - don't continue with data that will be lost
     */
    private suspend fun processBatch(batch: TremorBatch) {
        try {
            Log.i(TAG, "✓ Processing batch ${batch.batchId} with ${batch.samples.size} samples (timestamp: ${batch.timestamp})")

            // STEP 1: CRITICAL - Save to local storage IMMEDIATELY (before anything else)
            // insertAll uses OnConflictStrategy.REPLACE, so re-sent batches are inherently deduped
            try {
                saveToLocalStorageImmediate(batch)
                Log.i(TAG, "✓ Saved batch ${batch.batchId} to local storage immediately")
            } catch (e: Exception) {
                Log.e(TAG, "❌ CRITICAL: Failed to save batch ${batch.batchId} to local storage: ${e.message}", e)
                // DO NOT CONTINUE - data will be lost!
                return
            }

            // STEP 2: Send persistence ACK to watch so it can safely delete its local copy
            sendPersistenceAck(batch.batchId)

            // STEP 3: Record data reception (for UI/notifications)
            NotificationHelper.recordDataReceived(this)

            // Only queue for InfluxDB upload if upload is enabled
            if (PhoneDataConfig.isInfluxDbEnabled(this)) {
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
            } else {
                Log.d(TAG, "InfluxDB upload disabled — batch ${batch.batchId} saved to local DB only")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process batch: ${e.message}", e)
            Log.e(TAG, "Batch details - batchId: ${batch.batchId}, sampleCount: ${batch.samples.size}", e)
        }
    }

    /**
     * Send a persistence ACK to the watch after a batch has been durably saved to the phone DB.
     * The watch should only delete its local copy of the batch upon receiving this ACK.
     * Uses Constants.MESSAGE_PATH_BATCH_ACK which already exists in the shared module.
     *
     * Best-effort: failure to send ACK is logged but does not block processing.
     * The watch will simply retry the batch on the next send cycle.
     */
    private fun sendPersistenceAck(batchId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val messageClient = Wearable.getMessageClient(this@WatchDataListenerService)
                val nodeClient = Wearable.getNodeClient(this@WatchDataListenerService)
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected nodes to send ACK for batch $batchId")
                    return@launch
                }
                val ackPayload = JSONObject().apply {
                    put(Constants.KEY_BATCH_ID, batchId)
                    put(Constants.KEY_TIMESTAMP, System.currentTimeMillis())
                }.toString().toByteArray(Charsets.UTF_8)

                for (node in nodes) {
                    messageClient.sendMessage(
                        node.id,
                        Constants.MESSAGE_PATH_BATCH_ACK,
                        ackPayload
                    ).await()
                    Log.d(TAG, "✓ Sent persistence ACK for batch $batchId to node ${node.id}")
                }
            } catch (e: Exception) {
                // Best-effort: watch will retry the batch if ACK is lost
                Log.w(TAG, "Failed to send persistence ACK for batch $batchId: ${e.message}")
            }
        }
    }

    /**
     * Save batch to local storage immediately (before upload queue)
     * This ensures data is never lost even if InfluxDB is unavailable
     * Uses repository for centralized data management with caching
     * 
     * CRITICAL: Callers must be in a coroutine scope (serviceScope.launch).
     * Uses withContext(IO) instead of runBlocking to avoid blocking the service thread.
     */
    private suspend fun saveToLocalStorageImmediate(batch: TremorBatch) {
        // Use withContext(IO) instead of runBlocking — callers are already in a coroutine
        // scope (serviceScope.launch), so this preserves the sequential guarantee without
        // blocking the service thread.
        withContext(Dispatchers.IO) {
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
                    val decompressed = decompressData(batchData)
                    if (decompressed == null) {
                        Log.e(TAG, "Decompression failed for reliable batch $batchId, discarding")
                        return
                    }
                    batchData = decompressed
                    Log.i(TAG, "Decompressed reliable batch $batchId: $originalSize -> ${batchData.size} bytes")
                }

                Log.i(TAG, "Processing reliable batch $batchId from $source (${batchData.size} bytes)")

                // Parse the batch JSON
                val batchJson = String(batchData, Charsets.UTF_8)
                val batch = TremorBatch.fromJsonString(batchJson)

                // Route through processBatch for consistent save + ACK + queue handling
                serviceScope.launch {
                    processBatch(batch)
                }

                Log.i(TAG, "✓ Dispatched reliable batch $batchId with ${batch.samples.size} samples for processing")
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

            if (eventType == "medication_ingestion") {
                persistMedicationIngestionEvent(json, timestamp)
            }
            
            // Trigger upload if on home network
            triggerUploadService()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle diagnostic event: ${e.message}", e)
        }
    }

    private fun persistMedicationIngestionEvent(event: JSONObject, timestamp: Long) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = TremorRoomDatabase.getDatabase(this@WatchDataListenerService)
                val dao = db.tremorDao()
                val id = event.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val source = event.optString("source").ifBlank { "WATCH_TAKEN_NOW" }
                val watchId = event.optNullableString("watchId")
                val notes = event.optNullableString("notes")

                dao.insertMedicationIngestion(
                    MedicationIngestionEntity(
                        id = id,
                        timestamp = timestamp,
                        source = source,
                        watchId = watchId,
                        notes = notes,
                        payloadJson = event.toString()
                    )
                )

                getSharedPreferences("medication_ingestion_prefs", MODE_PRIVATE)
                    .edit()
                    .putLong("last_medication_ingestion_time", timestamp)
                    .putString("last_medication_ingestion_source", source)
                    .apply()

                Log.i(TAG, "✓ Saved medication ingestion event $id ($source)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist medication ingestion event: ${e.message}", e)
            }
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

            // Always enforce retention so the queue can't grow unbounded when InfluxDB is
            // unconfigured or the device is away from home network for long periods.
            trimDiagnosticEventQueue(queueDir, maxEvents = DIAG_MAX_EVENTS, maxAgeMs = DIAG_MAX_AGE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save diagnostic event to queue: ${e.message}", e)
        }
    }

    private fun trimDiagnosticEventQueue(dir: File, maxEvents: Int, maxAgeMs: Long) {
        try {
            val now = System.currentTimeMillis()
            val files = dir.listFiles { f ->
                f.name.startsWith("event_") && f.name.endsWith(".json")
            }?.toList() ?: return

            // Delete by age first.
            val remaining = ArrayList<File>(files.size)
            for (f in files) {
                val ageMs = now - f.lastModified()
                if (ageMs > maxAgeMs) {
                    f.delete()
                } else {
                    remaining.add(f)
                }
            }

            // Enforce count by filename sort (timestamp prefix provides chronological order).
            val excess = remaining.size - maxEvents
            if (excess > 0) {
                remaining
                    .sortedBy { it.name }
                    .take(excess)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trim diagnostic event queue: ${e.message}")
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

    private suspend fun updateDetectedMetricsFromCalibration(
        dao: TremorDao,
        ratingId: String,
        calibrationEntities: List<CalibrationDataEntity>
    ) {
        val fromCalibration = computeDetectedMetricsFromCalibration(calibrationEntities)
        val finalMetrics = fromCalibration ?: run {
            val rating = dao.getRatingById(ratingId)
            if (rating == null) {
                null
            } else {
                computeDetectedMetricsFromObjectiveWindow(
                    dao = dao,
                    ratingTimestamp = rating.timestamp,
                    calibrationDurationSeconds = rating.calibrationDurationSeconds
                )
            }
        }

        if (finalMetrics == null) return
        if (finalMetrics.severity == null && finalMetrics.confidence == null && finalMetrics.frequencyHz == null) return

        dao.updateDetectedMetricsForRating(
            ratingId = ratingId,
            detectedSeverity = finalMetrics.severity,
            detectedConfidence = finalMetrics.confidence,
            detectedFrequency = finalMetrics.frequencyHz
        )
    }

    private fun computeDetectedMetricsFromCalibration(
        calibrationEntities: List<CalibrationDataEntity>
    ): DetectedMetrics? {
        if (calibrationEntities.isEmpty()) return null

        val preferred = calibrationEntities.filter { it.isWorn && !it.isCharging }
        val samples = if (preferred.isNotEmpty()) preferred else calibrationEntities
        if (samples.isEmpty()) return null

        val severity = samples.map { it.severity }.averageOrNull()
        val confidence = samples.map { it.confidence.toDouble() }.averageOrNull()?.toFloat()
        val frequency = samples
            .map { it.dominantFrequency.toDouble() }
            .filter { it > 0.0 }
            .averageOrNull()
            ?.toFloat()

        return DetectedMetrics(
            severity = severity,
            confidence = confidence,
            frequencyHz = frequency
        )
    }

    private suspend fun computeDetectedMetricsFromObjectiveWindow(
        dao: TremorDao,
        ratingTimestamp: Long,
        calibrationDurationSeconds: Int
    ): DetectedMetrics? {
        val durationMs = calibrationDurationSeconds.coerceIn(5, 300) * 1000L
        val windowEnd = (ratingTimestamp - 500L).coerceAtLeast(0L)
        val windowStart = (windowEnd - durationMs).coerceAtLeast(0L)
        val samplesInWindow = dao.getSamplesInRange(windowStart, windowEnd)
        if (samplesInWindow.isEmpty()) return null

        val preferred = samplesInWindow.filter { it.isWorn != false && it.isCharging != true }
        val samples = if (preferred.isNotEmpty()) preferred else samplesInWindow
        if (samples.isEmpty()) return null

        val severity = samples.map { it.severity }.averageOrNull()
        val confidence = samples.mapNotNull { it.confidence }.averageOrNull()?.toFloat()
        val frequency = samples
            .mapNotNull { it.dominantFrequency }
            .filter { it > 0.0 }
            .averageOrNull()
            ?.toFloat()

        return DetectedMetrics(
            severity = severity,
            confidence = confidence,
            frequencyHz = frequency
        )
    }

    private fun percentileFromSorted(sortedValues: List<Double>, p: Double): Double? {
        if (sortedValues.isEmpty()) return null
        val index = p.coerceIn(0.0, 1.0) * sortedValues.lastIndex
        val lo = index.toInt()
        val hi = (lo + 1).coerceAtMost(sortedValues.lastIndex)
        val weight = index - lo
        return sortedValues[lo] * (1.0 - weight) + sortedValues[hi] * weight
    }

    private suspend fun computeObjectiveLookbackContextJson(
        dao: TremorDao,
        ratingTimestamp: Long
    ): String? {
        // Capture objective context in multiple lookback windows so we can later analyze
        // which window best matches subjective rating behavior.
        val windowsSeconds = listOf(10, 60, 300, 900)
        val windowEnd = (ratingTimestamp - 500L).coerceAtLeast(0L)

        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("ratingTimestamp", ratingTimestamp)
        root.put("generatedAtMs", System.currentTimeMillis())

        val windows = JSONArray()
        for (seconds in windowsSeconds) {
            val windowStart = (windowEnd - seconds * 1000L).coerceAtLeast(0L)
            val allSamples = dao.getSamplesInRange(windowStart, windowEnd)

            val anyCharging = allSamples.any { it.isCharging == true }
            val anyOffWrist = allSamples.any { it.isWorn == false }

            // Prefer on-wrist + not-charging samples when available.
            val preferred = allSamples.filter { it.isWorn != false && it.isCharging != true }
            val samples = if (preferred.isNotEmpty()) preferred else allSamples

            val severities = samples.map { it.severity }.filter { it.isFinite() }
            val confidences = samples.mapNotNull { it.confidence }.filter { it.isFinite() }

            val windowObj = JSONObject()
            windowObj.put("seconds", seconds)
            windowObj.put("startMs", windowStart)
            windowObj.put("endMs", windowEnd)
            windowObj.put("anyCharging", anyCharging)
            windowObj.put("anyOffWrist", anyOffWrist)
            windowObj.put("sampleCount", severities.size)

            confidences.averageOrNull()?.let { windowObj.put("avgConfidence", it) }

            if (severities.isNotEmpty()) {
                val sorted = severities.sorted()
                windowObj.put("mean", severities.average())
                percentileFromSorted(sorted, 0.50)?.let { windowObj.put("p50", it) }
                percentileFromSorted(sorted, 0.90)?.let { windowObj.put("p90", it) }
                windowObj.put("max", sorted.last())
                val nonZeroFraction = severities.count { it > 0.0 }.toDouble() / severities.size.toDouble()
                windowObj.put("nonZeroFraction", nonZeroFraction)
            }

            windows.put(windowObj)
        }

        root.put("objectiveLookbackWindows", windows)
        return root.toString()
    }

    private fun List<Double>.averageOrNull(): Double? {
        if (isEmpty()) return null
        return average()
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() }
    }

    /**
     * Handle subjective rating from watch.
     * Parse the rating JSON and save to local database immediately.
     */
    private fun handleSubjectiveRating(data: ByteArray) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject(String(data, Charsets.UTF_8))
                val db = TremorRoomDatabase.getDatabase(this@WatchDataListenerService)
                val dao = db.tremorDao()
                Log.i(TAG, "★ Received subjective rating: rating=${json.optInt("rating", 0)}")
                
                val ratingTimestamp = json.getLong("timestamp")
                val calibrationDurationSeconds = json.optInt("calibrationDurationSeconds", 60)
                val payloadMetrics = DetectedMetrics(
                    severity = json.optNullableDouble("detectedSeverity"),
                    confidence = json.optNullableDouble("detectedConfidence")?.toFloat(),
                    frequencyHz = json.optNullableDouble("detectedFrequency")?.toFloat()
                )
                val hasPayloadMetrics = payloadMetrics.severity != null ||
                    payloadMetrics.confidence != null ||
                    payloadMetrics.frequencyHz != null
                val inferredMetrics = if (!hasPayloadMetrics) {
                    computeDetectedMetricsFromObjectiveWindow(
                        dao = dao,
                        ratingTimestamp = ratingTimestamp,
                        calibrationDurationSeconds = calibrationDurationSeconds
                    )
                } else {
                    null
                }

                // opus46 Issue 3b: Improved exception handling with diagnostic JSON
                val phoneContext = try {
                    computeObjectiveLookbackContextJson(
                        dao = dao,
                        ratingTimestamp = ratingTimestamp
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to compute objective lookback context for rating " +
                        "at ${ratingTimestamp}: ${e.message}", e)
                    // Store diagnostic marker to distinguish "no data" from "computation failed"
                    JSONObject().apply {
                        put("error", e.message ?: "unknown")
                        put("errorType", e.javaClass.simpleName)
                        put("ratingTimestamp", ratingTimestamp)
                    }.toString()
                }

                // opus46 Issue 3e: Merge watch-side context with phone-side context
                val watchContext = json.optJSONObject("watchObjectiveContext")
                val mergedContext = when {
                    watchContext != null && phoneContext != null -> {
                        // Include both -- phone has historical depth, watch has freshness
                        val merged = JSONObject()
                        merged.put("watchContext", watchContext)
                        merged.put("phoneContext", JSONObject(phoneContext))
                        merged.toString()
                    }
                    watchContext != null -> {
                        val wrapper = JSONObject()
                        wrapper.put("watchContext", watchContext)
                        wrapper.toString()
                    }
                    phoneContext != null -> phoneContext
                    else -> null
                }

                // Parse rating from JSON
                val ratingEntity = SubjectiveRatingEntity(
                    id = json.getString("id"),
                    timestamp = ratingTimestamp,
                    rating = json.getInt("rating"),
                    source = json.getString("source"),
                    watchId = json.optNullableString("watchId"),
                    detectedSeverity = payloadMetrics.severity ?: inferredMetrics?.severity,
                    detectedConfidence = payloadMetrics.confidence ?: inferredMetrics?.confidence,
                    detectedFrequency = payloadMetrics.frequencyHz ?: inferredMetrics?.frequencyHz,
                    objectiveContextJson = mergedContext,
                    calibrationModeEnabled = json.optBoolean("calibrationModeEnabled", false),
                    calibrationDurationSeconds = calibrationDurationSeconds,
                    notes = json.optNullableString("notes"),
                    schemaVersion = json.optInt("schemaVersion", 1)
                )

                // Save to database immediately
                dao.insertRating(ratingEntity)

                if (!hasPayloadMetrics && inferredMetrics != null) {
                    Log.d(
                        TAG,
                        "Backfilled detected metrics for rating ${ratingEntity.id} " +
                            "(severity=${inferredMetrics.severity}, confidence=${inferredMetrics.confidence}, " +
                            "frequency=${inferredMetrics.frequencyHz})"
                    )
                }

                Log.i(TAG, "✓ Saved subjective rating ${ratingEntity.id} (rating: ${ratingEntity.rating}, source: ${ratingEntity.source})")
                
                // Notify UI of new rating
                val prefs = getSharedPreferences("rating_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putLong("last_rating_time", System.currentTimeMillis())
                    .putInt("last_rating_value", ratingEntity.rating)
                    .apply()
                    
                NotificationHelper.recordDataReceived(this@WatchDataListenerService)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle subjective rating: ${e.message}", e)
            }
        }
    }

    /**
     * Handle calibration data from watch.
     * Calibration data is streamed as chunks, each containing multiple sensor samples.
     * Must be parsed and linked to an existing SubjectiveRatingEntity.
     */
    private fun handleCalibrationData(data: ByteArray) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject(String(data, Charsets.UTF_8))
                val ratingId = json.getString("ratingId")
                val samplesArray = json.getJSONArray("samples")
                
                Log.i(TAG, "★ Received calibration data: ratingId=$ratingId, samples=${samplesArray.length()}")
                
                // Parse calibration samples
                val calibrationEntities = mutableListOf<CalibrationDataEntity>()
                for (i in 0 until samplesArray.length()) {
                    val sample = samplesArray.getJSONObject(i)
                    calibrationEntities.add(
                        CalibrationDataEntity(
                            ratingId = ratingId,
                            timestamp = sample.getLong("timestamp"),
                            x = sample.getDouble("x").toFloat(),
                            y = sample.getDouble("y").toFloat(),
                            z = sample.getDouble("z").toFloat(),
                            magnitude = sample.getDouble("magnitude").toFloat(),
                            dominantFrequency = sample.getDouble("dominantFrequency").toFloat(),
                            tremorBandPower = sample.getDouble("tremorBandPower").toFloat(),
                            totalPower = sample.getDouble("totalPower").toFloat(),
                            bandRatio = sample.getDouble("bandRatio").toFloat(),
                            peakProminence = sample.getDouble("peakProminence").toFloat(),
                            confidence = sample.getDouble("confidence").toFloat(),
                            severity = sample.getDouble("severity"),
                            isWorn = sample.optBoolean("isWorn", true),
                            isCharging = sample.optBoolean("isCharging", false)
                        )
                    )
                }
                
                if (calibrationEntities.isNotEmpty()) {
                    // Save to database in batch
                    val db = TremorRoomDatabase.getDatabase(this@WatchDataListenerService)
                    val dao = db.tremorDao()
                    dao.insertCalibrationData(calibrationEntities)
                    dao.markRatingCalibrated(ratingId)
                    updateDetectedMetricsFromCalibration(dao, ratingId, calibrationEntities)
                } else {
                    Log.w(TAG, "Received empty calibration sample set for rating $ratingId")
                }
                
                Log.i(TAG, "✓ Saved ${calibrationEntities.size} calibration samples for rating $ratingId")
                
                // Update status for UI
                val prefs = getSharedPreferences("calibration_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putLong("last_calibration_time", System.currentTimeMillis())
                    .putString("last_calibration_rating_id", ratingId)
                    .putInt("last_calibration_sample_count", calibrationEntities.size)
                    .apply()
                    
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle calibration data: ${e.message}", e)
            }
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
