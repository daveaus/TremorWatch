package com.opensource.tremorwatch.phone

import android.content.Context
import android.util.Log
import androidx.work.*
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TremorBatch
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * WorkManager worker for uploading tremor batches to InfluxDB.
 *
 * Benefits over ForegroundService:
 * - Survives Doze mode and battery optimization
 * - Automatic retry with exponential backoff
 * - Constraint-aware execution (network, battery, etc.)
 * - No service timeout issues
 * - Respects Android background execution limits
 */
class TremorUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TremorUploadWorker"
        const val KEY_BATCH_FILE_PATH = "batch_file_path"
        const val KEY_IS_MANUAL_UPLOAD = "is_manual_upload"
        const val UNIQUE_WORK_NAME = "tremor_upload"

        /**
         * Enqueue a single batch for upload
         */
        fun enqueueBatch(
            context: Context,
            batchFilePath: String,
            isManualUpload: Boolean = false
        ): Operation {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (isManualUpload) NetworkType.CONNECTED
                    else NetworkType.UNMETERED  // Wait for WiFi for auto uploads
                )
                .setRequiresBatteryNotLow(!isManualUpload)  // Manual uploads work even on low battery
                .build()

            val inputData = workDataOf(
                KEY_BATCH_FILE_PATH to batchFilePath,
                KEY_IS_MANUAL_UPLOAD to isManualUpload
            )

            val request = OneTimeWorkRequestBuilder<TremorUploadWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    Constants.UPLOAD_INITIAL_RETRY_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            return WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * Enqueue all pending batches from queue
         */
        fun enqueueAllPending(context: Context, isManualUpload: Boolean = false) {
            val queueDir = File(context.filesDir, "upload_queue")
            if (!queueDir.exists()) return

            val pendingFiles = queueDir.listFiles { file ->
                file.name.startsWith("batch_") && file.name.endsWith(".json")
            }?.sortedBy { it.name } ?: emptyList()

            if (pendingFiles.isEmpty()) {
                Log.d(TAG, "No pending batches to enqueue")
                return
            }

            Log.i(TAG, "Enqueuing ${pendingFiles.size} pending batch(es) for upload")

            pendingFiles.forEach { file ->
                enqueueBatch(context, file.absolutePath, isManualUpload)
            }
        }

        /**
         * Cancel all pending upload work
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val batchFilePath = inputData.getString(KEY_BATCH_FILE_PATH)
        val isManualUpload = inputData.getBoolean(KEY_IS_MANUAL_UPLOAD, false)

        if (batchFilePath == null) {
            Log.e(TAG, "No batch file path provided")
            return Result.failure()
        }

        val batchFile = File(batchFilePath)
        if (!batchFile.exists()) {
            Log.w(TAG, "Batch file no longer exists: ${batchFile.name}")
            return Result.success()  // File already processed or deleted
        }

        return try {
            // Check configuration
            if (!PhoneDataConfig.isInfluxConfigured(applicationContext)) {
                Log.w(TAG, "InfluxDB not configured - failing work")
                return Result.failure()
            }

            // Additional network check (WorkManager handles constraints but we can check current state)
            if (!isManualUpload && !ConnectivityUtil.isNetworkAvailable(applicationContext)) {
                Log.d(TAG, "Network not available - will retry later")
                return Result.retry()
            }

            // Additional home network check for auto uploads
            if (!isManualUpload && !PhoneNetworkDetector.isOnHomeNetwork(applicationContext)) {
                Log.d(TAG, "Not on home network - will retry later")
                return Result.retry()
            }

            // Read and parse batch
            val json = batchFile.readText()
            val batch = TremorBatch.fromJsonString(json)

            Log.i(TAG, "Uploading batch ${batch.batchId} with ${batch.samples.size} samples (attempt ${runAttemptCount + 1})")

            // Upload with retry logic
            val success = uploadBatchWithRetry(batch)

            if (success) {
                // Delete file after successful upload
                batchFile.delete()
                Log.i(TAG, "Successfully uploaded and deleted batch ${batch.batchId}")

                // Save to local storage if enabled
                if (PhoneDataConfig.isLocalStorageEnabled(applicationContext)) {
                    saveToLocalStorage(batch)
                }

                // Update success statistics
                PhoneDataConfig.recordSuccessfulUpload(applicationContext)

                Result.success()
            } else {
                Log.w(TAG, "Upload failed for batch ${batch.batchId} - will retry")
                Result.retry()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing batch upload: ${e.message}", e)

            // Retry on recoverable errors
            if (e is IOException || e.message?.contains("network", ignoreCase = true) == true) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Upload batch to InfluxDB with internal retry logic
     */
    private suspend fun uploadBatchWithRetry(batch: TremorBatch): Boolean {
        var attemptNumber = 0

        while (attemptNumber <= Constants.UPLOAD_MAX_RETRIES) {
            try {
                if (attemptNumber > 0) {
                    val delayMs = calculateBackoffDelay(attemptNumber)
                    Log.d(TAG, "Retry #$attemptNumber for batch ${batch.batchId} after ${delayMs}ms")
                    delay(delayMs)
                }

                if (uploadToInfluxDB(batch)) {
                    return true
                }

            } catch (e: Exception) {
                Log.w(TAG, "Upload attempt ${attemptNumber + 1} failed: ${e.message}")
                if (attemptNumber >= Constants.UPLOAD_MAX_RETRIES) {
                    return false
                }
            }

            attemptNumber++
        }

        return false
    }

    /**
     * Upload batch to InfluxDB (single attempt)
     */
    private suspend fun uploadToInfluxDB(batch: TremorBatch): Boolean {
        return try {
            val influxUrl = PhoneDataConfig.getInfluxDbUrl(applicationContext)
            val database = PhoneDataConfig.getInfluxDbDatabase(applicationContext)
            val username = PhoneDataConfig.getInfluxDbUsername(applicationContext)
            val password = PhoneDataConfig.getInfluxDbPassword(applicationContext)

            // Build line protocol
            val lineProtocol = buildLineProtocol(batch)

            val requestBody = lineProtocol.toRequestBody("text/plain".toMediaType())

            // Build request
            val requestBuilder = Request.Builder()
                .url("$influxUrl/write?db=$database&precision=ms")
                .post(requestBody)

            // Add authentication if provided
            if (username.isNotEmpty() && password.isNotEmpty()) {
                val credentials = "$username:$password"
                val auth = "Basic " + android.util.Base64.encodeToString(
                    credentials.toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                requestBuilder.addHeader("Authorization", auth)
            }

            val request = requestBuilder.build()

            // Synchronous call (we're in coroutine already)
            val response = okHttpClient.newCall(request).execute()

            val success = response.isSuccessful
            if (success) {
                Log.d(TAG, "Upload successful for batch ${batch.batchId}")
            } else {
                val errorBody = response.body?.string() ?: "No error body"
                Log.e(TAG, "Upload failed: ${response.code} ${response.message}")
                Log.e(TAG, "InfluxDB error: $errorBody")
            }

            response.close()
            success

        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.message}", e)
            false
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
            val watchId = (sample.metadata["watch_id"] as? String) ?: "unknown"
            val timeOfDay = (sample.metadata["time_of_day"] as? String) ?: "unknown"
            val dayOfWeek = (sample.metadata["day_of_week"] as? String) ?: "unknown"
            
            // Build base line protocol
            val baseFields = "severity=${sample.severity},tremor_count=${sample.tremorCount}i,frequency=$dominantFrequency," +
                    "band_power=$tremorBandPower,total_power=$totalPower,band_ratio=$bandRatio," +
                    "peak_prominence=$peakProminence,confidence=$confidence"
            
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
            
            val line = "tremor_data,data_type=sample,watch_id=$watchId,time_of_day=$timeOfDay,day_of_week=$dayOfWeek " +
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
     * Save batch to local storage for archival
     */
    private fun saveToLocalStorage(batch: TremorBatch) {
        try {
            val storageFile = File(applicationContext.filesDir, "consolidated_tremor_data.jsonl")
            storageFile.appendText(batch.toJsonString() + "\n")
            Log.d(TAG, "Saved batch ${batch.batchId} to local storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to local storage: ${e.message}", e)
        }
    }

    /**
     * Calculate exponential backoff delay with jitter
     */
    private fun calculateBackoffDelay(attemptNumber: Int): Long {
        val baseDelay = Constants.UPLOAD_INITIAL_RETRY_DELAY_MS * (1 shl (attemptNumber - 1))
        val jitter = (Math.random() * 0.2 * baseDelay).toLong()
        return min(baseDelay + jitter, Constants.MAX_RETRY_DELAY_MS)
    }
}
