package com.opensource.tremorwatch.phone

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.opensource.tremorwatch.phone.data.TremorDataRepository
import com.opensource.tremorwatch.shared.models.TremorBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service that handles uploading tremor batches to InfluxDB.
 * Processes queue of batches received from watch.
 *
 * Runs as foreground service with persistent notification showing upload status.
 */
class UploadService : Service() {

    companion object {
        private const val TAG = "UploadService"
        private const val UPLOAD_DELAY_MS = 100L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 60000L
        private const val QUEUE_CHECK_INTERVAL_MS = 5000L
        private const val MIN_NOTIFICATION_UPDATE_INTERVAL_MS = 3000L
        private const val MAX_BATCHES_PER_CHUNK = 50 // Increased from 10 to 50
        private const val MAX_FAILURES = 3 // Max failures before moving a batch to failed queue
    }

    /**
     * Upload result types to distinguish retryable errors from fatal errors.
     * Only FATAL errors should count toward MAX_FAILURES.
     */
    enum class UploadResult {
        SUCCESS,    // Upload succeeded
        RETRYABLE,  // Connection errors, not on home network - DON'T count as failure
        FATAL       // Bad request, parse errors - DO count as failure
    }

    private var isRunningAsForeground = false
    private var isProcessingUpload = false
    private var lastNotificationUpdateTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val failureCounts = mutableMapOf<String, Int>()

    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Repository for data persistence
    private lateinit var repository: TremorDataRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Upload service created - starting persistent foreground service")

        // Initialize repository
        repository = TremorDataRepository(this)

        UploadMetrics.initialize(this)
        startForegroundService()
        startPeriodicNotificationUpdates()
        startPeriodicQueueChecks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Upload service onStartCommand")

        if (!isRunningAsForeground) {
            startForegroundService()
        }

        val shouldProcessNow = intent?.getBooleanExtra("process_now", false) ?: false
        if (shouldProcessNow && !isProcessingUpload) {
            processUploadQueue()
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        if (isRunningAsForeground) return

        try {
            val notification = NotificationHelper.buildServiceNotification(
                this,
                ServiceStatus.IDLE,
                "Service active"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }

            isRunningAsForeground = true
            Log.i(TAG, "Started as persistent foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
        }
    }

    private fun startPeriodicNotificationUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                if (!isProcessingUpload) {
                    updateIdleNotification()
                }
                handler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL_MS)
            }
        })
    }

    private fun startPeriodicQueueChecks() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isProcessingUpload) {
                    checkAndProcessQueue()
                }
                handler.postDelayed(this, QUEUE_CHECK_INTERVAL_MS)
            }
        }, QUEUE_CHECK_INTERVAL_MS)
    }

    private fun updateIdleNotification() {
        NotificationHelper.updateNotification(this, ServiceStatus.IDLE, "Monitoring for data")
    }

    private fun checkAndProcessQueue() {
        Log.d(TAG, "Periodic queue check")
        processUploadQueue()
    }

    private fun updateNotificationThrottled(status: ServiceStatus, info: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateTime >= MIN_NOTIFICATION_UPDATE_INTERVAL_MS) {
            NotificationHelper.updateNotification(this, status, info)
            lastNotificationUpdateTime = now
        }
    }

    /**
     * Process the upload queue with proper home network detection.
     * CRITICAL FIX: Returns early when not on home network to prevent false failures.
     */
    private fun processUploadQueue() {
        if (isProcessingUpload) {
            Log.d(TAG, "Already processing upload queue, skipping")
            return
        }

        val isConfigured = PhoneDataConfig.isInfluxConfigured(this)
        val hasNetwork = PhoneNetworkDetector.isNetworkAvailable(this)
        val isOnHomeNetwork = PhoneNetworkDetector.isOnHomeNetwork(this)

        Log.i(TAG, "=== Queue processing state ===")
        Log.i(TAG, "InfluxDB configured: $isConfigured")
        Log.i(TAG, "Network available: $hasNetwork")
        Log.i(TAG, "On home network: $isOnHomeNetwork")

        // Get pending files first
        val queueDir = File(filesDir, "upload_queue")
        if (!queueDir.exists()) {
            return
        }

        val pendingFiles = queueDir.listFiles { file ->
            file.name.startsWith("batch_") && file.name.endsWith(".json")
        }?.sortedBy { it.name } ?: emptyList()

        if (pendingFiles.isEmpty()) {
            return
        }

        // CRITICAL: Return early if conditions not met - don't process queue when away
        when {
            !isConfigured -> {
                Log.i(TAG, "InfluxDB not configured - processing ${pendingFiles.size} batches for local storage only")
                updateNotificationThrottled(ServiceStatus.IDLE, "Saving to local storage (InfluxDB not configured)")
                processBatchesForLocalStorageOnly(pendingFiles)
                return
            }
            !hasNetwork -> {
                Log.i(TAG, "No network available - ${pendingFiles.size} batches queued for upload when network available")
                updateNotificationThrottled(ServiceStatus.WAITING, "${pendingFiles.size} batches queued (no network)")
                return // STOP HERE - don't process queue
            }
            !isOnHomeNetwork -> {
                Log.i(TAG, "Not on home network - ${pendingFiles.size} batches queued for upload when on home network")
                updateNotificationThrottled(ServiceStatus.WAITING, "${pendingFiles.size} batches queued (away from home)")
                return // STOP HERE - don't process queue
            }
        }

        // Only reach here if on home network and configured - proceed with upload
        Log.i(TAG, "On home network - processing ${pendingFiles.size} batches for upload")
        processBatchesForUpload(pendingFiles)
        
        // Also process diagnostic events
        processDiagnosticEvents()
    }

    /**
     * Process batches for local storage only (InfluxDB not configured)
     */
    private fun processBatchesForLocalStorageOnly(files: List<File>) {
        isProcessingUpload = true

        backgroundExecutor.execute {
            var successCount = 0
            var errorCount = 0

            files.forEach { file ->
                try {
                    if (!file.exists()) {
                        Log.w(TAG, "Batch file ${file.name} no longer exists")
                        return@forEach
                    }

                    val json = file.readText()
                    if (json.isBlank()) {
                        Log.e(TAG, "Batch file ${file.name} is empty")
                        file.delete()
                        errorCount++
                        return@forEach
                    }

                    val batch = TremorBatch.fromJsonString(json)

                    // Ensure data is in local storage (should already be there from WatchDataListenerService)
                    try {
                        saveToLocalStorage(batch)
                        Log.d(TAG, "✓ Verified batch ${batch.batchId} in local storage")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save batch ${batch.batchId} to local storage: ${e.message}")
                        // Continue anyway - data may already be there
                    }

                    // Remove from queue since we don't need InfluxDB
                    file.delete()
                    successCount++
                    Log.i(TAG, "✓ Processed batch ${batch.batchId} for local storage only")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process batch ${file.name} for local storage: ${e.message}")
                    errorCount++
                }
            }

            handler.post {
                isProcessingUpload = false
                Log.i(TAG, "Local storage processing complete: $successCount successful, $errorCount errors")
                updateNotificationThrottled(ServiceStatus.IDLE, "Local storage updated")
            }
        }
    }

    /**
     * Process batches for upload (on home network)
     */
    private fun processBatchesForUpload(files: List<File>) {
        isProcessingUpload = true

        val totalBatches = files.size
        val batchesToProcess = files.take(MAX_BATCHES_PER_CHUNK)

        if (totalBatches > MAX_BATCHES_PER_CHUNK) {
            Log.i(TAG, "Large backlog detected ($totalBatches batches) - processing first $MAX_BATCHES_PER_CHUNK")
            updateNotificationThrottled(ServiceStatus.UPLOADING, "Processing ${MAX_BATCHES_PER_CHUNK}/$totalBatches batches")
        } else {
            updateNotificationThrottled(ServiceStatus.UPLOADING, "Uploading $totalBatches batch(es)")
        }

        batchesToProcess.forEachIndexed { index, file ->
            handler.postDelayed({
                val currentBatch = index + 1
                updateNotificationThrottled(
                    ServiceStatus.UPLOADING,
                    "Uploading $currentBatch/${batchesToProcess.size}"
                )

                uploadBatchFile(file)

                if (index == batchesToProcess.size - 1) {
                    handler.postDelayed({
                        isProcessingUpload = false
                        val remainingBatches = totalBatches - batchesToProcess.size
                        if (remainingBatches > 0) {
                            Log.i(TAG, "Chunk complete - $remainingBatches batches remaining")
                            updateNotificationThrottled(ServiceStatus.IDLE, "$remainingBatches batch(es) pending")
                            handler.postDelayed({ processUploadQueue() }, 5000)
                        } else {
                            Log.i(TAG, "Completed processing all batches")
                            updateNotificationThrottled(ServiceStatus.IDLE, "Upload complete")
                        }
                    }, 1000)
                }
            }, index * UPLOAD_DELAY_MS)
        }
    }

    /**
     * Upload a batch file - SIMPLIFIED VERSION
     * Assumes we're only called when we should attempt upload (on home network)
     * CRITICAL FIX: Uses UploadResult enum to distinguish retryable from fatal errors
     */
    private fun uploadBatchFile(file: File) {
        backgroundExecutor.execute {
            if (!file.exists()) {
                Log.w(TAG, "Batch file ${file.name} no longer exists, skipping")
                return@execute
            }

            try {
                val json = file.readText()
                if (json.isBlank()) {
                    Log.e(TAG, "Batch file ${file.name} is empty, deleting")
                    file.delete()
                    return@execute
                }

                val batch = try {
                    TremorBatch.fromJsonString(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse batch file ${file.name}: ${e.message}", e)
                    moveBatchToFailedQueue(file)
                    return@execute
                }

                if (batch.samples.isEmpty()) {
                    Log.w(TAG, "Batch ${batch.batchId} has no samples, deleting file")
                    file.delete()
                    return@execute
                }

                Log.d(TAG, "Processing batch ${batch.batchId} with ${batch.samples.size} samples")

                // CRITICAL: Data should already be in local storage from WatchDataListenerService
                // Just verify it's there (don't re-save to avoid redundancy)
                Log.d(TAG, "✓ Batch ${batch.batchId} is already secured in local storage")

                // Double-check we're still on home network before uploading
                if (!PhoneNetworkDetector.isOnHomeNetwork(this@UploadService)) {
                    Log.w(TAG, "No longer on home network - keeping batch ${batch.batchId} in queue")
                    // DON'T count as failure - we'll retry later
                    failureCounts.remove(file.name)
                    return@execute
                }

                // Attempt InfluxDB upload
                uploadToInfluxDB(batch) { result ->
                    when (result) {
                        UploadResult.SUCCESS -> {
                            // Upload succeeded - safe to delete from queue
                            if (file.exists()) {
                                file.delete()
                                Log.i(TAG, "✓ Successfully uploaded and deleted batch ${batch.batchId} from queue")
                            }
                            failureCounts.remove(file.name)
                            UploadMetrics.recordUploadSuccess(
                                this@UploadService,
                                batchCount = 1,
                                bytesSent = json.length.toLong()
                            )
                        }
                        UploadResult.RETRYABLE -> {
                            // Retryable error (connection, not on home network) - DON'T increment failure count
                            Log.w(TAG, "Retryable upload error for batch ${batch.batchId} - keeping in queue, no failure count")
                            failureCounts.remove(file.name) // Reset if exists
                        }
                        UploadResult.FATAL -> {
                            // Fatal error (bad request, parse error) - DO increment failure count
                            Log.e(TAG, "Fatal upload error for batch ${batch.batchId} - counting as failure")
                            val failures = (failureCounts[file.name] ?: 0) + 1
                            failureCounts[file.name] = failures

                            if (failures >= MAX_FAILURES) {
                                Log.e(TAG, "Batch ${batch.batchId} failed $failures times. Moving to failed queue.")
                                Log.i(TAG, "NOTE: Data is preserved in local storage, so no data loss occurred")
                                moveBatchToFailedQueue(file)
                                failureCounts.remove(file.name)
                            }

                            UploadMetrics.recordUploadFailure(
                                this@UploadService,
                                batchCount = 1,
                                errorMessage = "HTTP request failed (fatal)"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process batch file ${file.name}: ${e.message}", e)
                val failures = (failureCounts[file.name] ?: 0) + 1
                failureCounts[file.name] = failures

                if (failures >= MAX_FAILURES) {
                    Log.e(TAG, "Batch ${file.name} failed $failures times. Moving to failed queue.")
                    moveBatchToFailedQueue(file)
                    failureCounts.remove(file.name)
                }

                UploadMetrics.recordUploadFailure(
                    this@UploadService,
                    batchCount = 1,
                    errorMessage = e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun moveBatchToFailedQueue(file: File) {
        val failedQueueDir = File(filesDir, "failed_queue")
        if (!failedQueueDir.exists()) {
            failedQueueDir.mkdirs()
        }
        try {
            val newFile = File(failedQueueDir, file.name)
            file.renameTo(newFile)
            Log.i(TAG, "Moved batch ${file.name} to failed queue.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move batch ${file.name} to failed queue: ${e.message}", e)
        }
    }

    /**
     * Upload batch to InfluxDB with proper error classification.
     * CRITICAL FIX: Returns UploadResult enum to distinguish retryable from fatal errors.
     */
    private fun uploadToInfluxDB(batch: TremorBatch, onComplete: (UploadResult) -> Unit) {
        try {
            val influxUrl = PhoneDataConfig.getInfluxDbUrl(this)
            val isOnHomeNetwork = PhoneNetworkDetector.isOnHomeNetwork(this)

            Log.d(TAG, "Attempting upload - URL: $influxUrl, On home network: $isOnHomeNetwork")

            val database = PhoneDataConfig.getInfluxDbDatabase(this)
            val username = PhoneDataConfig.getInfluxDbUsername(this)
            val password = PhoneDataConfig.getInfluxDbPassword(this)

            val lineProtocol = buildLineProtocol(batch)

            val previewLines = lineProtocol.lines().take(3)
            Log.d(TAG, "Line protocol preview (first 3 lines):")
            previewLines.forEach { Log.d(TAG, "  $it") }
            Log.d(TAG, "Total lines: ${lineProtocol.lines().size}, Total size: ${lineProtocol.length} bytes")

            val requestBody = lineProtocol.toRequestBody("text/plain".toMediaType())

            val requestBuilder = Request.Builder()
                .url("$influxUrl/write?db=$database&precision=ms")
                .post(requestBody)

            if (username.isNotEmpty() && password.isNotEmpty()) {
                val credentials = "$username:$password"
                val auth = "Basic " + android.util.Base64.encodeToString(
                    credentials.toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                requestBuilder.addHeader("Authorization", auth)
            }

            val request = requestBuilder.build()

            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Check if this is a connection/timeout error (server unreachable)
                    val isConnectionError = e.message?.contains("timeout", ignoreCase = true) == true ||
                                           e.message?.contains("connection", ignoreCase = true) == true ||
                                           e.message?.contains("unreachable", ignoreCase = true) == true ||
                                           e.message?.contains("failed to connect", ignoreCase = true) == true

                    if (isConnectionError) {
                        // Check if we're still not on home network
                        val isOnHomeNetwork = PhoneNetworkDetector.isOnHomeNetwork(this@UploadService)
                        if (!isOnHomeNetwork) {
                            Log.w(TAG, "Upload failed for batch ${batch.batchId}: InfluxDB unreachable (not on home network)")
                            // Away from home: treat as RETRYABLE, but do NOT count toward MAX_FAILURES
                            onComplete(UploadResult.RETRYABLE)
                            return
                        }
                        // Even on home, treat connection errors as transient (e.g., brief WiFi drop)
                        Log.w(TAG, "Transient connection error for batch ${batch.batchId} on home network: ${e.message}")
                        onComplete(UploadResult.RETRYABLE)
                        return
                    }

                    Log.e(TAG, "Non-transient failure for batch ${batch.batchId}: ${e.message}", e)
                    onComplete(UploadResult.RETRYABLE) // Default to retryable for unknown errors
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.i(TAG, "Upload successful for batch ${batch.batchId}")
                        PhoneDataConfig.recordSuccessfulUpload(this@UploadService)
                        onComplete(UploadResult.SUCCESS)
                    } else {
                        val errorBody = response.body?.string() ?: "No error body"
                        Log.e(TAG, "Upload failed for batch ${batch.batchId}: ${response.code} ${response.message}")
                        Log.e(TAG, "InfluxDB error response: $errorBody")
                        Log.e(TAG, "Request URL: ${response.request.url}")

                        // If it's a 4xx, treat as FATAL; 5xx as RETRYABLE
                        val result = if (response.code in 400..499) {
                            UploadResult.FATAL // Bad request - count as failure
                        } else {
                            UploadResult.RETRYABLE // Server error - don't count
                        }
                        onComplete(result)
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading batch ${batch.batchId}: ${e.message}", e)
            onComplete(UploadResult.RETRYABLE) // Default to retryable
        }
    }

    /**
     * Build InfluxDB line protocol from batch.
     * 
     * Structure:
     * - All data points use the 'tremor_data' measurement
     * - Individual samples use tag: data_type=sample
     * - Aggregated ratings use tag: data_type=rating
     * 
     * This creates a hierarchical structure in InfluxDB where rating data
     * appears under tremor_data when filtered by data_type=rating.
     */
    private fun buildLineProtocol(batch: TremorBatch): String {
        val lines = mutableListOf<String>()

        // Individual samples with data_type=sample tag
        batch.samples.forEach { sample ->
            val dominantFrequency = (sample.metadata["dominantFrequency"] as? Number)?.toFloat() ?: 0f
            val tremorBandPower = (sample.metadata["tremorBandPower"] as? Number)?.toFloat() ?: 0f
            val totalPower = (sample.metadata["totalPower"] as? Number)?.toFloat() ?: 0f
            val bandRatio = (sample.metadata["bandRatio"] as? Number)?.toFloat() ?: 0f
            val peakProminence = (sample.metadata["peakProminence"] as? Number)?.toFloat() ?: 0f
            val confidence = (sample.metadata["confidence"] as? Number)?.toFloat() ?: 0f
            val baselineMultiplier = (sample.metadata["baselineMultiplier"] as? Number)?.toFloat() ?: 1f
            val tremorType = (sample.metadata["tremorType"] as? String) ?: "none"
            val tremorTypeConfidence = (sample.metadata["tremorTypeConfidence"] as? Number)?.toFloat() ?: 0f
            val isRestingState = (sample.metadata["isRestingState"] as? Boolean) ?: false
            val watchId = (sample.metadata["watch_id"] as? String) ?: "unknown"
            val timeOfDay = (sample.metadata["time_of_day"] as? String) ?: "unknown"
            val dayOfWeek = (sample.metadata["day_of_week"] as? String) ?: "unknown"
            
            // Build base line protocol (includes baseline_multiplier for Phase 5, tremor_type for Phase 5b)
            val baseFields = "severity=${sample.severity},tremor_count=${sample.tremorCount}i,frequency=$dominantFrequency," +
                    "band_power=$tremorBandPower,total_power=$totalPower,band_ratio=$bandRatio," +
                    "peak_prominence=$peakProminence,confidence=$confidence,baseline_multiplier=$baselineMultiplier," +
                    "tremor_type_confidence=$tremorTypeConfidence,is_resting=$isRestingState"
            
            // Add intense mode sensor data if available
            val intenseFields = mutableListOf<String>()
            sample.metadata["accelX"]?.let { intenseFields.add("accel_x=$it") }
            sample.metadata["accelY"]?.let { intenseFields.add("accel_y=$it") }
            sample.metadata["accelZ"]?.let { intenseFields.add("accel_z=$it") }
            sample.metadata["magnetX"]?.let { intenseFields.add("magnet_x=$it") }
            sample.metadata["magnetY"]?.let { intenseFields.add("magnet_y=$it") }
            sample.metadata["magnetZ"]?.let { intenseFields.add("magnet_z=$it") }
            sample.metadata["ambientLight"]?.let { intenseFields.add("ambient_light=$it") }
            sample.metadata["barometricPressure"]?.let { intenseFields.add("barometric_pressure=$it") }
            sample.metadata["stepCount"]?.let { intenseFields.add("step_count=${it}i") }
            sample.metadata["tiltX"]?.let { intenseFields.add("tilt_x=$it") }
            sample.metadata["tiltY"]?.let { intenseFields.add("tilt_y=$it") }
            sample.metadata["tiltZ"]?.let { intenseFields.add("tilt_z=$it") }
            
            val allFields = if (intenseFields.isNotEmpty()) {
                "$baseFields,${intenseFields.joinToString(",")}"
            } else {
                baseFields
            }
            
            val line = "tremor_data,data_type=sample,watch_id=$watchId,time_of_day=$timeOfDay,day_of_week=$dayOfWeek,tremor_type=$tremorType " +
                    "$allFields ${sample.timestamp}"
            lines.add(line)
        }

        // Aggregated rating with data_type=rating tag (appears under tremor_data)
        if (batch.samples.isNotEmpty()) {
            val avgSeverity = batch.samples.map { it.severity }.average()
            val totalTremorCount = batch.samples.sumOf { it.tremorCount }
            val sampleCount = batch.samples.size
            val tremorSampleCount = batch.samples.count { it.tremorCount > 0 }
            val tremorPercentage = if (sampleCount > 0) (tremorSampleCount.toDouble() / sampleCount) * 100.0 else 0.0

            val samplesWithFrequency = batch.samples.filter {
                val freq = (it.metadata["dominantFrequency"] as? Number)?.toFloat() ?: 0f
                freq > 0f
            }
            val avgFrequency = if (samplesWithFrequency.isNotEmpty()) {
                samplesWithFrequency.map {
                    (it.metadata["dominantFrequency"] as? Number)?.toFloat() ?: 0f
                }.average()
            } else 0.0

            val avgBandPower = batch.samples.map {
                (it.metadata["tremorBandPower"] as? Number)?.toFloat() ?: 0f
            }.average()
            
            val avgTotalPower = batch.samples.map {
                (it.metadata["totalPower"] as? Number)?.toFloat() ?: 0f
            }.average()
            
            val avgBandRatio = batch.samples.map {
                (it.metadata["bandRatio"] as? Number)?.toFloat() ?: 0f
            }.average()
            
            val avgPeakProminence = batch.samples.map {
                (it.metadata["peakProminence"] as? Number)?.toFloat() ?: 0f
            }.average()
            
            val avgConfidence = batch.samples.map {
                (it.metadata["confidence"] as? Number)?.toFloat() ?: 0f
            }.average()
            
            val maxSeverity = batch.samples.maxOfOrNull { it.severity } ?: 0.0
            val minSeverity = batch.samples.minOfOrNull { it.severity } ?: 0.0
            
            // Calculate standard deviation
            val severityValues = batch.samples.map { it.severity }
            val severityMean = severityValues.average()
            val severityVariance = severityValues.map { (it - severityMean) * (it - severityMean) }.average()
            val severityStddev = kotlin.math.sqrt(severityVariance)
            
            // Get tags from first sample (should be consistent within batch)
            val watchId = (batch.samples.first().metadata["watch_id"] as? String) ?: "unknown"
            val timeOfDay = (batch.samples.first().metadata["time_of_day"] as? String) ?: "unknown"
            val dayOfWeek = (batch.samples.first().metadata["day_of_week"] as? String) ?: "unknown"

            // Rating data now uses tremor_data measurement with data_type=rating tag
            val ratingLine = "tremor_data,data_type=rating,watch_id=$watchId,time_of_day=$timeOfDay,day_of_week=$dayOfWeek " +
                    "avg_severity=$avgSeverity,max_severity=$maxSeverity,min_severity=$minSeverity,severity_stddev=$severityStddev," +
                    "tremor_count=${totalTremorCount}i,sample_count=${sampleCount}i,tremor_percentage=$tremorPercentage," +
                    "avg_frequency=$avgFrequency,avg_band_power=$avgBandPower,avg_total_power=$avgTotalPower," +
                    "avg_band_ratio=$avgBandRatio,avg_peak_prominence=$avgPeakProminence,avg_confidence=$avgConfidence ${batch.timestamp}"
            lines.add(ratingLine)
        }

        return lines.joinToString("\n")
    }
    
    /**
     * Process diagnostic events and upload to InfluxDB.
     * Events include charging state, off-body detection, monitoring paused/resumed.
     */
    private fun processDiagnosticEvents() {
        backgroundExecutor.execute {
            try {
                val eventsDir = File(filesDir, "diagnostic_events_queue")
                if (!eventsDir.exists()) {
                    return@execute
                }
                
                val eventFiles = eventsDir.listFiles { file ->
                    file.name.startsWith("event_") && file.name.endsWith(".json")
                }?.sortedBy { it.name } ?: emptyList()
                
                if (eventFiles.isEmpty()) {
                    return@execute
                }
                
                Log.i(TAG, "Processing ${eventFiles.size} diagnostic event(s)")
                
                eventFiles.forEach { file ->
                    try {
                        val json = JSONObject(file.readText())
                        val success = uploadDiagnosticEvent(json)
                        
                        if (success) {
                            file.delete()
                            Log.d(TAG, "✓ Uploaded diagnostic event: ${json.optString("event_type")}")
                        } else {
                            Log.w(TAG, "Failed to upload diagnostic event, will retry later")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing diagnostic event ${file.name}: ${e.message}")
                        // Delete corrupted files
                        if (e is org.json.JSONException) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing diagnostic events: ${e.message}", e)
            }
        }
    }
    
    /**
     * Upload a diagnostic event to InfluxDB
     */
    private fun uploadDiagnosticEvent(eventData: JSONObject): Boolean {
        val eventType = eventData.optString("event_type", "unknown")
        val timestamp = eventData.optLong("timestamp", System.currentTimeMillis())
        
        // Build InfluxDB line protocol
        val watchId = "tremorwatch" // Fixed identifier for diagnostic events
        val batteryLevel = eventData.optInt("battery_level", -1)
        val isCharging = eventData.optBoolean("is_charging", false)
        val isWorn = eventData.optBoolean("is_worn", true)
        val reason = eventData.optString("reason", "")
        val isPaused = eventData.optBoolean("is_paused", false)
        
        // Convert to nanoseconds for InfluxDB
        val timestampNs = timestamp * 1_000_000L
        
        val lineProtocol = StringBuilder()
        lineProtocol.append("tremor_data,data_type=event,event_type=$eventType,watch_id=$watchId ")
        lineProtocol.append("battery_level=${batteryLevel}i,")
        lineProtocol.append("is_charging=$isCharging,")
        lineProtocol.append("is_worn=$isWorn")
        
        if (reason.isNotEmpty()) {
            lineProtocol.append(",reason=\"${reason.replace("\"", "\\\"")}\"")
        }
        if (eventType.contains("monitoring")) {
            lineProtocol.append(",is_paused=$isPaused")
        }
        
        lineProtocol.append(" $timestampNs")
        
        // Upload to InfluxDB
        val influxUrl = PhoneDataConfig.getInfluxDbUrl(this)
        val database = PhoneDataConfig.getInfluxDbDatabase(this)
        
        if (influxUrl.isEmpty() || database.isEmpty()) {
            Log.w(TAG, "InfluxDB not configured - cannot upload diagnostic event")
            return true // Return true to delete the event (nothing we can do)
        }
        
        val writeUrl = "$influxUrl/write?db=$database&precision=ns"
        
        return try {
            val request = Request.Builder()
                .url(writeUrl)
                .post(lineProtocol.toString().toRequestBody("text/plain".toMediaType()))
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            val success = response.isSuccessful
            val responseCode = response.code
            response.close()
            
            if (success) {
                Log.i(TAG, "✓ Uploaded diagnostic event: $eventType")
            } else {
                Log.w(TAG, "Failed to upload diagnostic event: $responseCode")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading diagnostic event: ${e.message}")
            false
        }
    }

    private fun saveToLocalStorage(batch: TremorBatch) {
        serviceScope.launch(Dispatchers.IO) {
            repository.saveTremorBatch(batch)
                .onSuccess {
                    Log.d(TAG, "Saved batch ${batch.batchId} to local storage via repository")

                    // Only cleanup every 100 batches to reduce overhead
                    val prefs = getSharedPreferences("upload_service_prefs", MODE_PRIVATE)
                    val batchesSinceCleanup = prefs.getInt("batches_since_cleanup", 0) + 1
                    if (batchesSinceCleanup >= 100) {
                        cleanupOldLocalStorage()
                        prefs.edit().putInt("batches_since_cleanup", 0).apply()
                    } else {
                        prefs.edit().putInt("batches_since_cleanup", batchesSinceCleanup).apply()
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to save to local storage: ${e.message}", e)
                }
        }
    }

    private fun cleanupOldLocalStorage() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val retentionHours = PhoneDataConfig.getLocalStorageRetentionHours(this@UploadService)
                val retentionDays = (retentionHours / 24.0).toInt().coerceAtLeast(1)

                repository.cleanupOldData(retentionDays)
                    .onSuccess { removedCount ->
                        if (removedCount > 0) {
                            Log.i(TAG, "Cleaned up $removedCount old entries from local storage via repository")
                        }
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Failed to cleanup local storage via repository: ${e.message}", e)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup local storage: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Upload service onDestroy called")

        // Stop all handlers immediately to prevent timeout
        handler.removeCallbacksAndMessages(null)

        // Cancel all coroutines
        serviceScope.cancel()

        // Shutdown executor gracefully
        try {
            backgroundExecutor.shutdown()
            if (!backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down executor: ${e.message}")
        }

        // Stop foreground service immediately to prevent timeout exception
        if (isRunningAsForeground) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isRunningAsForeground = false
                Log.i(TAG, "Foreground service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping foreground service: ${e.message}")
            }
        }

        Log.i(TAG, "Upload service destroyed - service will restart automatically via START_STICKY")
    }
}
