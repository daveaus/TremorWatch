package com.opensource.tremorwatch.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import timber.log.Timber
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.opensource.tremorwatch.MainActivity
import com.opensource.tremorwatch.WatchDataSender
import com.opensource.tremorwatch.communication.WatchPhoneCommunication
import com.opensource.tremorwatch.communication.WatchDataSenderCommunication
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.opensource.tremorwatch.config.MonitoringState
import com.opensource.tremorwatch.config.DataConfig
import com.opensource.tremorwatch.config.ConfigDataListener
import com.opensource.tremorwatch.config.RatingConfigDataListener
import com.opensource.tremorwatch.receivers.ServiceWatchdogReceiver
import com.opensource.tremorwatch.receivers.UploadAlarmReceiver
import com.opensource.tremorwatch.receivers.BatchRetryAlarmReceiver
import com.opensource.tremorwatch.receivers.RatingPromptReceiver
import com.opensource.tremorwatch.engine.BaselineManager
import com.opensource.tremorwatch.engine.TremorMonitoringEngine
import com.opensource.tremorwatch.constants.MonitoringConstants
import com.opensource.tremorwatch.data.PreferencesRepository
import com.opensource.tremorwatch.data.CalibrationCaptureManager
import com.opensource.tremorwatch.data.CalibrationSample
import com.opensource.tremorwatch.shared.models.RatingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import kotlin.coroutines.resume


/**
 * TremorService - Lifecycle-aware foreground service for continuous tremor monitoring.
 * 
 * Uses LifecycleService to enable lifecycle-aware components and better state management.
 * The service coordinates sensor monitoring, data collection, and communication with the phone.
 */
class TremorService : LifecycleService(), SensorEventListener {

    companion object {
        // TAG removed - Timber uses class name automatically
        private const val ACTION_ACTIVITY_UPDATE = "com.opensource.tremorwatch.ACTION_ACTIVITY_UPDATE"
        const val ACTION_START_CALIBRATION = "com.opensource.tremorwatch.ACTION_START_CALIBRATION"
        const val EXTRA_RATING_ID = "extra_rating_id"
        const val EXTRA_CALIBRATION_DURATION = "extra_calibration_duration"
        private const val ACTIVITY_UPDATE_REQUEST_CODE = 4101
        private const val RATING_PREFS_NAME = "rating_prefs"
        private const val KEY_PROMPTS_ENABLED = "prompts_enabled"
        private const val KEY_MIN_INTERVAL_MINUTES = "min_interval_minutes"
        private const val KEY_MAX_DAILY_PROMPTS = "max_daily_prompts"
        private const val KEY_PROMPTS_TODAY = "prompts_today"
        private const val KEY_PROMPTS_TODAY_DATE = "prompts_today_date"
        private const val KEY_DONT_ASK_DATE = "dont_ask_date"
        private const val KEY_ACTIVE_HOURS_START = "active_hours_start"
        private const val KEY_ACTIVE_HOURS_END = "active_hours_end"
        private const val KEY_NEXT_PROMPT_ELAPSED = "next_prompt_elapsed"
        private const val KEY_PROMPT_VIBRATION_ENABLED = "prompt_vibration_enabled"
        private const val KEY_PROMPT_VIBRATION_STRONG = "prompt_vibration_strong"
        private const val KEY_PROMPT_FOLLOWUP_VIBRATION = "prompt_followup_vibration"
        private const val KEY_PROMPT_FOLLOWUP_PENDING = "prompt_followup_pending"
        private const val KEY_PROMPT_LAST_SHOWN_ELAPSED = "prompt_last_shown_elapsed"
        private const val RATING_CHANNEL_ID = "rating_prompts"
        private const val PROMPT_FOLLOWUP_DELAY_MS = 5 * 60 * 1000L

        /** WakeLock auto-release timeout. Monitor renews every 10 min, so 15 min gives safety margin. */
        private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
    }

    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** True when gyroscope is running at reduced rate due to STILL activity. */
    private var isGyroInLowPowerMode = false

    // Activity Recognition
    private var activityRecognitionClient: ActivityRecognitionClient? = null
    private var activityUpdatePendingIntent: PendingIntent? = null
    private var activityUpdatesRegistered = false
    private var activityFilteringEnabled = true
    private var lastLoggedActivityType = DetectedActivity.UNKNOWN
    private var lastLoggedActivityConfidenceBucket = -1
    private val ratingPromptHandler = Handler(Looper.getMainLooper())
    private val ratingFollowupHandler = Handler(Looper.getMainLooper())
    private val ratingFollowupRunnable = object : Runnable {
        override fun run() {
            maybeRunFollowupVibration()
        }
    }

    // Wear detection and charging state (managed by service, synchronized with engine)
    private var isWatchWorn = true  // Assume worn initially
    private var isCharging = false
    private var isPausedDueToWearState = false
    private var hasOffBodySensor = false  // Track if off-body sensor is available
    private var chargingReceiver: BroadcastReceiver? = null
    private var settingsReceiver: BroadcastReceiver? = null

    // Battery optimization tracking for immediate notification on change
    private var lastBatteryOptimizationState: Boolean? = null

    // Watch-to-phone communication
    private lateinit var phoneCommunication: WatchPhoneCommunication

    // Monitoring engine - handles sensor processing and data collection
    private lateinit var monitoringEngine: TremorMonitoringEngine
    private lateinit var baselineManager: BaselineManager

    // Config listener - receives detection algorithm config from phone
    private lateinit var configListener: ConfigDataListener

    // Rating config listener - receives subjective rating settings from phone
    private lateinit var ratingConfigListener: RatingConfigDataListener

    // Preferences repository for state management
    private lateinit var preferencesRepository: PreferencesRepository
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Calibration capture manager for subjective rating data collection
    private lateinit var calibrationCaptureManager: CalibrationCaptureManager

    // Periodic status update handler
    private val statusUpdateHandler = Handler(Looper.getMainLooper())

    // Heartbeat handler for service alive pings to phone
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeatToPhone()
            heartbeatHandler.postDelayed(this, Constants.HEARTBEAT_INTERVAL_MS)
        }
    }

    // WakeLock monitor to fight Samsung FreecessController
    private val wakeLockMonitorHandler = Handler(Looper.getMainLooper())
    private val wakeLockMonitorRunnable = object : Runnable {
        override fun run() {
            verifyAndRenewWakeLock()
            wakeLockMonitorHandler.postDelayed(this, MonitoringConstants.WAKELOCK_CHECK_INTERVAL_MS)
        }
    }

    // Battery optimization monitor - checks frequently for changes
    private val batteryOptMonitorHandler = Handler(Looper.getMainLooper())
    private val batteryOptMonitorRunnable = object : Runnable {
        override fun run() {
            checkBatteryOptimizationStatus()
            batteryOptMonitorHandler.postDelayed(this, MonitoringConstants.BATTERY_OPT_CHECK_INTERVAL_MS)
        }
    }

    private val ratingPromptRunnable = object : Runnable {
        override fun run() {
            ensureRatingPromptScheduled()
        }
    }

    // Samsung sleeping apps detection - track wakelock disruptions
    private var wakeLockDisruptionCount = 0
    private var lastDisruptionCheckTime = 0L
    private var hasNotifiedSleepingApps = false

    // Service state
    private var startTime = 0L
    private var batchesSent = 0
    private var batchesFailed = 0
    private var lastSuccessfulUploadTime = 0L
    private val pendingBatchCount = AtomicInteger(0)
    private val uploadLock = AtomicBoolean(false)


    // ====================== LOCAL PERSISTENCE ======================

    /**
     * Convert engine TremorData to shared TremorData format for transmission to phone.
     */
    private fun convertToSharedFormat(data: TremorMonitoringEngine.TremorData): com.opensource.tremorwatch.shared.models.TremorData {
        // Phase 5: Use clinically calculated severity from engine (opus45)
        // Non-tremor samples correctly have severity 0
        val severity = data.severity

        // Count as tremor if flagged as tremor
        val tremorCount = if (data.isTremor) 1 else 0

        // Calculate time-based tags
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = data.timestamp
        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        
        val timeOfDay = when (hourOfDay) {
            in 5..11 -> "morning"
            in 12..17 -> "afternoon"
            in 18..22 -> "evening"
            else -> "night"
        }
        
        val dayOfWeekStr = when (dayOfWeek) {
            Calendar.SUNDAY -> "sunday"
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            Calendar.SATURDAY -> "saturday"
            else -> "unknown"
        }
        
        // Get watch ID (device serial or Android ID)
        val watchId = try {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        
        // Pack extra fields into metadata
        val metadata = mutableMapOf<String, Any>(
            "x" to data.x,
            "y" to data.y,
            "z" to data.z,
            "magnitude" to data.magnitude,
            "accelMagnitude" to data.accelMagnitude,
            "confidence" to data.confidence,
            "isWorn" to data.isWorn,
            "isCharging" to data.isCharging,
            "datetimeIso" to data.datetimeIso,
            "timeFormatted" to data.timeFormatted,
            "dominantFrequency" to data.dominantFrequency,
            "tremorBandPower" to data.tremorBandPower,
            "totalPower" to data.totalPower,
            "bandRatio" to data.bandRatio,
            "peakProminence" to data.peakProminence,
            "watch_id" to watchId,
            "time_of_day" to timeOfDay,
            "day_of_week" to dayOfWeekStr,
            // Phase 5: Include baseline-relative data (opus45)
            "clinicalSeverity" to data.severity,
            "baselineMultiplier" to data.baselineMultiplier,
            // Phase 5b: Tremor type classification
            "tremorType" to data.tremorType,
            "tremorTypeConfidence" to data.tremorTypeConfidence,
            "isRestingState" to data.isRestingState,
            // Activity context + filtered values
            "activityType" to data.activityType,
            "activityConfidence" to data.activityConfidence,
            "activityAgeMs" to data.activityAgeMs,
            "activityAdjustedConfidence" to data.activityAdjustedConfidence,
            "activityAdjustedSeverity" to data.activityAdjustedSeverity,
            "isReliableMeasurement" to data.isReliableMeasurement,
            "excludeFromAnalysis" to data.excludeFromAnalysis
        )

        return com.opensource.tremorwatch.shared.models.TremorData(
            timestamp = data.timestamp,
            severity = severity.toDouble(),
            tremorCount = tremorCount,
            metadata = metadata
        )
    }

    private fun saveBatchLocally(batch: List<TremorMonitoringEngine.TremorData>) {
        try {
            // Check if we've hit the max pending batches limit (use cached count first)
            if (pendingBatchCount.get() >= MonitoringConstants.MAX_PENDING_BATCHES) {
                // Only scan directory when we need to delete old files
                val pendingFiles = getPendingBatchFiles()
                Timber.w("Max pending batches reached (${pendingFiles.size}), deleting oldest")
                if (pendingFiles.isNotEmpty()) {
                    pendingFiles.first().delete()
                    pendingBatchCount.set((pendingFiles.size - 1).coerceAtLeast(0))
                }
            }

            // Convert to shared format for transmission
            val sharedSamples = batch.map { convertToSharedFormat(it) }
            val batchId = "${System.currentTimeMillis()}"
            val sharedBatch = com.opensource.tremorwatch.shared.models.TremorBatch(
                batchId = batchId,
                timestamp = System.currentTimeMillis(),
                samples = sharedSamples,
                watchId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            )

            val filename = "tremor_batch_${batchId}.json"
            val file = java.io.File(filesDir, filename)

            // Save using shared format
            file.writeText(sharedBatch.toJsonString())
            val newPendingCount = pendingBatchCount.incrementAndGet() // Cached count to avoid rescanning directory
            Timber.i("Saved batch locally: $filename (${batch.size} samples, $newPendingCount pending)")
            Timber.d("TremorWatch: Saved batch locally - $newPendingCount pending batches")

            // If local storage enabled, also append to consolidated storage file
            if (DataConfig.isLocalStorageEnabled(this)) {
                appendToConsolidatedStorage(sharedBatch.toJsonString())
            }
        } catch (e: Exception) {
            Timber.e("Failed to save batch locally: ${e.message}", e)
        }
    }

    /**
     * Append batch data to consolidated local storage file for later export.
     * This runs in the background and doesn't affect upload/pending count.
     */
    private fun appendToConsolidatedStorage(batchJson: String) {
        try {
            val storageFile = java.io.File(filesDir, "consolidated_tremor_data.jsonl")

            // Append as JSON Lines format (one JSON object per line)
            storageFile.appendText(batchJson + "\n")

            Timber.d("Appended batch to consolidated storage (${storageFile.length() / 1024}KB)")
        } catch (e: Exception) {
            Timber.e("Failed to append to consolidated storage: ${e.message}")
        }
    }

    /**
     * Get upload metadata for tracking which files have been uploaded to which destinations.
     * Returns a map of filename to upload status.
     */
    private fun getUploadMetadata(): MutableMap<String, MutableMap<String, Boolean>> {
        val metadataFile = java.io.File(filesDir, "upload_metadata.json")
        if (!metadataFile.exists()) {
            return mutableMapOf()
        }

        try {
            val json = metadataFile.readText()
            val metadata = mutableMapOf<String, MutableMap<String, Boolean>>()

            // Parse simple JSON structure: { "filename": { "influxdb": true, "homeassistant": true } }
            val lines = json.lines().filter { it.contains(":") }
            var currentFile: String? = null

            for (line in lines) {
                when {
                    line.contains("\"") && line.contains("{") -> {
                        // File entry: "filename": {
                        currentFile = line.substringAfter("\"").substringBefore("\"")
                        metadata[currentFile] = mutableMapOf()
                    }
                    currentFile != null && line.contains("influxdb") -> {
                        metadata[currentFile]!!["influxdb"] = line.contains("true")
                    }
                    currentFile != null && line.contains("homeassistant") -> {
                        metadata[currentFile]!!["homeassistant"] = line.contains("true")
                    }
                }
            }

            return metadata
        } catch (e: Exception) {
            Timber.e("Failed to read upload metadata: ${e.message}")
            return mutableMapOf()
        }
    }

    /**
     * Save upload metadata to disk.
     */
    private fun saveUploadMetadata(metadata: Map<String, Map<String, Boolean>>) {
        val metadataFile = java.io.File(filesDir, "upload_metadata.json")
        try {
            val json = buildString {
                append("{\n")
                metadata.entries.forEachIndexed { index, (filename, destinations) ->
                    append("  \"$filename\": {\n")
                    append("    \"influxdb\": ${destinations["influxdb"] ?: false},\n")
                    append("    \"homeassistant\": ${destinations["homeassistant"] ?: false}\n")
                    append("  }")
                    if (index < metadata.size - 1) append(",")
                    append("\n")
                }
                append("}\n")
            }
            metadataFile.writeText(json)
        } catch (e: Exception) {
            Timber.e("Failed to save upload metadata: ${e.message}")
        }
    }

    private fun getPendingBatchFiles(): List<java.io.File> {
        return filesDir.listFiles { file ->
            file.name.startsWith("tremor_batch_") && file.name.endsWith(".json")
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun decrementPendingBatchCount() {
        // pendingBatchCount is a cache; keep it from drifting negative under concurrent updates.
        while (true) {
            val current = pendingBatchCount.get()
            if (current <= 0) return
            if (pendingBatchCount.compareAndSet(current, current - 1)) return
        }
    }

    private suspend fun sendBatchAwait(batch: TremorBatch): Boolean {
        if (!::phoneCommunication.isInitialized) return false

        return suspendCancellableCoroutine { cont ->
            phoneCommunication.sendBatch(batch) { success ->
                if (cont.isActive) cont.resume(success)
            }
        }
    }

    private fun retryFailedUploads(forceUpload: Boolean = false) {
        // Check if upload to phone is enabled
        if (!DataConfig.isUploadToPhoneEnabled(this)) {
            Timber.d("Upload to phone is disabled - skipping upload")
            return
        }

        // Prevent concurrent upload operations (atomic compare-and-set avoids races).
        if (!uploadLock.compareAndSet(false, true)) {
            Timber.d("Upload already in progress - skipping duplicate request")
            return
        }

        // Use serviceScope (coroutine) instead of raw Thread; cancelled in onDestroy.
        serviceScope.launch {
            try {
                while (true) {
                    val pendingFiles = getPendingBatchFiles()
                    if (pendingFiles.isEmpty()) {
                        Timber.d("No pending batches to send")
                        return@launch
                    }

                    val totalBatches = pendingFiles.size
                    val filesToProcess = pendingFiles.take(MonitoringConstants.MAX_BATCHES_PER_UPLOAD)
                    val remainingBatches = (totalBatches - filesToProcess.size).coerceAtLeast(0)

                    if (forceUpload) {
                        Timber.i("Manual upload: Found $totalBatches pending batch(es) to send to phone")
                    } else {
                        Timber.i("Automatic upload: Found $totalBatches pending batch(es) to send to phone")
                    }

                    var completedThisCycle = 0

                    for ((index, file) in filesToProcess.withIndex()) {
                        try {
                            // Add small delay between file reads to avoid overwhelming the system.
                            if (index > 0 && index % 5 == 0) {
                                delay(50)
                            }

                            val jsonContent = try {
                                file.bufferedReader().use { it.readText() }
                            } catch (e: Exception) {
                                Timber.e("Failed to read file ${file.name}: ${e.message}", e)
                                batchesFailed++
                                completedThisCycle++
                                continue
                            }

                            val batch = try {
                                TremorBatch.fromJsonString(jsonContent)
                            } catch (e: Exception) {
                                Timber.e("ERROR: Failed to parse batch file ${file.name}: ${e.message}", e)
                                batchesFailed++
                                completedThisCycle++
                                continue
                            }

                            val success = try {
                                sendBatchAwait(batch)
                            } catch (e: Exception) {
                                Timber.e("ERROR: Failed to send batch ${file.name}: ${e.message}", e)
                                false
                            }

                            if (success) {
                                if (file.exists()) {
                                    val deleted = file.delete()
                                    if (deleted) {
                                        decrementPendingBatchCount()
                                        batchesSent++
                                        lastSuccessfulUploadTime = System.currentTimeMillis()
                                        BatchRetryAlarmReceiver.resetRetryCount(this@TremorService)
                                        Timber.i("SUCCESS: Sent batch ${file.name}, deleted. ${pendingBatchCount.get()} pending")
                                    } else {
                                        Timber.w("SUCCESS: Sent batch ${file.name}, but failed to delete local file (will retry)")
                                    }
                                }
                            } else {
                                batchesFailed++
                                Timber.w("FAILED: Failed to send batch ${file.name} - will retry later. ${pendingBatchCount.get()} pending")
                            }

                            completedThisCycle++
                        } catch (e: OutOfMemoryError) {
                            Timber.e("CRITICAL: OutOfMemoryError during batch upload - stopping immediately. Already processed: $completedThisCycle/${filesToProcess.size}", e)
                            batchesFailed++
                            return@launch
                        }
                    }

                    if (remainingBatches > 0) {
                        Timber.i("BATCH COMPLETE: Processed ${filesToProcess.size} of $totalBatches. Scheduling next cycle for remaining $remainingBatches batches...")
                        delay(2_000)
                        continue
                    }

                    Timber.i("COMPLETE: Upload batch processing finished: $completedThisCycle/$totalBatches processed")
                    return@launch
                }
            } finally {
                uploadLock.set(false)
            }
        }
    }

    /**
     * Send heartbeat ping to phone to indicate the watch service is alive.
     * Includes service uptime and monitoring state for diagnostics.
     * CRITICAL FIX: Refresh charging state before sending heartbeat to ensure accuracy.
     */
    private fun sendHeartbeatToPhone() {
        // Refresh charging state before sending heartbeat (ACTION_POWER_DISCONNECTED can be suppressed in doze)
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentlyCharging = batteryManager.isCharging
        if (currentlyCharging != isCharging) {
            val oldState = isCharging
            isCharging = currentlyCharging
            Timber.i("HEARTBEAT: Charging state changed: $oldState -> $isCharging")
            if (::monitoringEngine.isInitialized) {
                monitoringEngine.setCharging(isCharging)
            }
            updateMonitoringState()
        }

        val uptime = System.currentTimeMillis() - startTime
        val monitoringState = when {
            isCharging -> "paused_charging"  // Check charging first
            isPausedDueToWearState -> "paused_not_worn"  // Then check if paused due to not being worn
            else -> "active"
        }

        // Get current battery optimization status (tracked by separate monitor)
        var watchBatteryOptimized = lastBatteryOptimizationState ?: false
        if (lastBatteryOptimizationState == null) {
            // First heartbeat - check status
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                watchBatteryOptimized = pm.isIgnoringBatteryOptimizations(packageName).not()
                lastBatteryOptimizationState = watchBatteryOptimized
            }
        }

        phoneCommunication.sendHeartbeat(uptime, monitoringState, watchBatteryOptimized) { success ->
            if (success) {
                Timber.i("★ Heartbeat sent to phone (uptime: ${uptime / 1000}s, state: $monitoringState, battery_opt: $watchBatteryOptimized)")
            } else {
                Timber.w("Failed to send heartbeat to phone")
            }
        }
    }

        /**
         * Check battery optimization status and send immediate notification if changed.
         * This runs more frequently than the heartbeat to catch changes quickly.
         */
        private fun checkBatteryOptimizationStatus() {
            var watchBatteryOptimized = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                watchBatteryOptimized = pm.isIgnoringBatteryOptimizations(packageName).not()
            }

            // If state changed, send immediate heartbeat
            if (lastBatteryOptimizationState != null && lastBatteryOptimizationState != watchBatteryOptimized) {
                Timber.i("★★ Battery optimization state changed: $lastBatteryOptimizationState -> $watchBatteryOptimized - sending immediate heartbeat")
                lastBatteryOptimizationState = watchBatteryOptimized
                // Send immediate heartbeat to update phone
                sendHeartbeatToPhone()
            } else if (lastBatteryOptimizationState == null) {
                // First check - just initialize the state
                lastBatteryOptimizationState = watchBatteryOptimized
            }
        }

        /**
         * Start periodic wakelock monitoring to fight Samsung FreecessController.
         * Checks periodically if wakelock is still held and re-acquires if Samsung disabled it.
         */
        private fun startWakeLockMonitor() {
            Timber.w("★★★ Starting WakeLock monitor - checking every ${MonitoringConstants.WAKELOCK_CHECK_INTERVAL_MS / 1000}s")
            wakeLockMonitorHandler.post(wakeLockMonitorRunnable)
        }

        /**
         * Start periodic battery optimization monitoring.
         * Checks every 60 seconds for changes and sends immediate heartbeat on change.
         */
        private fun startBatteryOptMonitor() {
            Timber.i("★★★ Starting battery optimization monitor - checking every ${MonitoringConstants.BATTERY_OPT_CHECK_INTERVAL_MS / 1000}s")
            batteryOptMonitorHandler.post(batteryOptMonitorRunnable)
        }

    /**
     * Verify wakelock is still held and re-acquire if Samsung FreecessController disabled it.
     * Battery-optimized: Only refresh if actually disrupted, not on every check.
     * This fights Samsung's aggressive battery optimization that bypasses standard Android protections.
     * Also detects if app has been re-added to Samsung's sleeping apps list.
     */
    private fun verifyAndRenewWakeLock() {
        // BATTERY FIX: Don't re-acquire wake lock while monitoring is paused.
        // The wake lock is intentionally released during pause to save battery.
        if (isPausedDueToWearState) {
            Timber.v("WakeLock monitor: skipping - monitoring is paused")
            return
        }

        val now = System.currentTimeMillis()

        wakeLock?.let { wl ->
            if (!wl.isHeld) {
                // Wakelock expired or was disabled by Samsung FreecessController
                Timber.e("★★★ CRITICAL: WakeLock not held (timeout expired or Samsung FreecessController) - RE-ACQUIRING NOW!")

                // Track disruption for sleeping apps detection
                trackWakeLockDisruption(now)

                try {
                    wl.acquire(WAKELOCK_TIMEOUT_MS)
                    Timber.w("★★★ WakeLock RE-ACQUIRED with ${WAKELOCK_TIMEOUT_MS / 60000}min timeout")
                } catch (e: Exception) {
                    Timber.e("★★★ FAILED to re-acquire wakelock: ${e.message}", e)
                }
            } else {
                // Wakelock still held - renew the timeout so it doesn't expire between checks
                try {
                    wl.release()
                    wl.acquire(WAKELOCK_TIMEOUT_MS)
                    Timber.v("WakeLock timeout renewed for ${WAKELOCK_TIMEOUT_MS / 60000}min")
                } catch (e: Exception) {
                    Timber.e("Failed to renew wakelock timeout: ${e.message}", e)
                }
            }
        } ?: run {
            Timber.e("★★★ CRITICAL: WakeLock is NULL - this should never happen!")
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "TremorWatch::SensorWakeLock"
                ).apply {
                    acquire(WAKELOCK_TIMEOUT_MS)
                    Timber.w("WakeLock recreated with ${WAKELOCK_TIMEOUT_MS / 60000}min timeout")
                }
            } catch (e: Exception) {
                Timber.e("Failed to recreate wakelock: ${e.message}", e)
            }
        }
    }

    /**
     * Track wakelock disruptions to detect if app has been added to Samsung's sleeping apps list.
     * If wakelock is disabled frequently (5+ times in 5 minutes), notify user.
     */
    private fun trackWakeLockDisruption(now: Long) {
            // Reset counter if we're outside the check window
            if (now - lastDisruptionCheckTime > MonitoringConstants.DISRUPTION_CHECK_WINDOW_MS) {
                wakeLockDisruptionCount = 0
                lastDisruptionCheckTime = now
            }

            wakeLockDisruptionCount++
            Timber.w("WakeLock disruption count: $wakeLockDisruptionCount in last ${(now - lastDisruptionCheckTime) / 1000}s")

            // If disruptions exceed threshold, likely on Samsung sleeping apps list
            if (wakeLockDisruptionCount >= MonitoringConstants.DISRUPTION_THRESHOLD && !hasNotifiedSleepingApps) {
                Timber.e("★★★ WARNING: TremorWatch may be on Samsung's SLEEPING APPS list!")
                Timber.e("★★★ Wakelock disabled $wakeLockDisruptionCount times in ${MonitoringConstants.DISRUPTION_CHECK_WINDOW_MS / 60000} minutes")
            Timber.e("★★★ ACTION REQUIRED: Remove TremorWatch from Samsung's sleeping apps list on your PHONE")

            // Update notification to alert user
            updateNotificationWithSleepingAppsWarning()

            hasNotifiedSleepingApps = true
        }
    }

    /**
     * Update the foreground service notification to warn about Samsung sleeping apps.
     */
    private fun updateNotificationWithSleepingAppsWarning() {
        try {
            val notification = buildNotification(
                title = "⚠️ TremorWatch - ACTION REQUIRED",
                text = "App on Samsung sleeping list! Remove it on PHONE to fix data gaps."
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, notification)
        } catch (e: Exception) {
            Timber.e("Failed to update notification with sleeping apps warning: ${e.message}")
        }
    }

    /**
     * Clean up old data from consolidated local storage based on retention period.
     * Removes entries older than the configured retention hours.
     */
    private fun cleanupOldLocalStorage() {
        if (!DataConfig.isLocalStorageEnabled(this)) {
            return // No cleanup needed if local storage is disabled
        }

        try {
            val storageFile = java.io.File(filesDir, "consolidated_tremor_data.jsonl")
            if (!storageFile.exists()) {
                return
            }

            val retentionHours = DataConfig.getLocalStorageRetentionHours(this)
            val retentionMillis = retentionHours * 60 * 60 * 1000L
            val cutoffTime = System.currentTimeMillis() - retentionMillis

            // Use streaming to avoid loading entire file into memory
            val tempFile = java.io.File(filesDir, "consolidated_tremor_data.jsonl.tmp")
            var removedCount = 0
            var keptCount = 0

            storageFile.bufferedReader().use { reader ->
                tempFile.bufferedWriter().use { writer ->
                    reader.lineSequence().forEach { line ->
                        if (line.isNotBlank()) {
                            // Extract timestamp from batch JSON
                            val timestampMatch = Regex("\"timestamp\":(\\d+)").find(line)
                            if (timestampMatch != null) {
                                val timestamp = timestampMatch.groupValues[1].toLongOrNull() ?: 0L
                                if (timestamp >= cutoffTime) {
                                    writer.write(line)
                                    writer.newLine()
                                    keptCount++
                                } else {
                                    removedCount++
                                }
                            } else {
                                // Keep lines without timestamps (shouldn't happen, but be safe)
                                writer.write(line)
                                writer.newLine()
                                keptCount++
                            }
                        }
                    }
                }
            }

            if (removedCount > 0) {
                // Replace original file with cleaned version
                if (tempFile.renameTo(storageFile)) {
                    val sizeMB = storageFile.length() / (1024.0 * 1024.0)
                    Timber.i("Cleanup: removed $removedCount old batches from consolidated storage (kept $keptCount, now ${String.format("%.2f", sizeMB)}MB)")
                } else {
                    Timber.e("Failed to rename temp file after cleanup")
                    tempFile.delete()
                }
            } else {
                // No cleanup needed, delete temp file
                tempFile.delete()
            }
        } catch (e: Exception) {
            Timber.e("Failed to cleanup consolidated storage: ${e.message}", e)
            // Clean up temp file if it exists
            try {
                java.io.File(filesDir, "consolidated_tremor_data.jsonl.tmp").delete()
            } catch (cleanupEx: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    override fun onCreate() {
        super.onCreate()  // LifecycleService.onCreate() transitions to CREATED state

        Timber.i("STARTUP: TremorService v3.3.0 onCreate() - Service starting up!")
        Timber.d("Lifecycle state: ${lifecycle.currentState}")

        // CRITICAL: Call startForeground() IMMEDIATELY to prevent Android from killing the service
        // Must be called within 5 seconds on Android 12+ or service will be killed
        // Create notification channel first (fast operation)
        createNotificationChannel()

        // Start foreground IMMEDIATELY - no delays, no complex logic
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires service type
                startForeground(
                    1,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(1, buildNotification())
            }
            Timber.d("TremorService: Foreground service started successfully")
        } catch (e: Exception) {
            Timber.e("CRITICAL: Failed to start foreground: ${e.message}", e)
            // Service will likely be killed by system - log and continue anyway
        }

        // Set up a watchdog alarm to ensure service stays running
        scheduleWatchdogAlarm()

        // Set up upload alarm for periodic batch uploads
        scheduleUploadAlarm()

        // Set up batch retry alarm to ensure pending batches get sent even if process is killed
        scheduleBatchRetryAlarm()

        // Disable legacy alarm-based prompts; service scheduler handles prompts on Wear OS
        cancelRatingPromptAlarm()
        scheduleNextRatingPromptTick()

            // Initialize preferences repository
            preferencesRepository = PreferencesRepository(this)

            // Initialize calibration capture manager for subjective rating data collection
            calibrationCaptureManager = CalibrationCaptureManager(this)
            
            // Initialize watch-to-phone communication
            phoneCommunication = WatchDataSenderCommunication(this)

        // Baseline manager (personalized thresholds) - uses app context for persistence safety.
        baselineManager = BaselineManager(applicationContext)
         
        // Start the batch send queue worker to serialize sends and prevent Data Layer congestion
        Timber.i("INIT: TremorService v3.3.0 - About to call WatchDataSender.startQueueWorker()")
        WatchDataSender.startQueueWorker()
        Timber.i("COMPLETE: TremorService v3.3.0 - Batch send queue worker initialization completed")

        // Initialize monitoring engine
        monitoringEngine = TremorMonitoringEngine(
            baselineManager = baselineManager,
            onBatchReady = { batch ->
                // Called when engine has collected a full batch
                saveBatchLocally(batch)

                // Immediately attempt to send pending batches to phone
                // This avoids waiting for the next upload alarm (which could be 60 min away)
                retryFailedUploads(forceUpload = false)
            },
            onWearStateChanged = { isWorn ->
                // Called when wear state changes
                isWatchWorn = isWorn
                updateMonitoringState()

                // Send diagnostic event for off-body/on-body state change
                if (::phoneCommunication.isInitialized) {
                    val eventData = mapOf(
                        "is_worn" to isWorn,
                        "battery_level" to getBatteryLevel(),
                        "is_charging" to isCharging
                    )
                    phoneCommunication.sendDiagnosticEvent(
                        if (isWorn) "watch_worn" else "watch_offbody",
                        eventData
                    ) { success ->
                        if (success) {
                            Timber.d("Diagnostic event sent: ${if (isWorn) "watch_worn" else "watch_offbody"}")
                        }
                    }
                }
            }
            ,
            onSampleReady = { data ->
                // Calibration capture is time-bounded and must not depend on batch boundaries (10 min).
                if (::calibrationCaptureManager.isInitialized && calibrationCaptureManager.isCapturing()) {
                    val sample = CalibrationSample(
                        timestamp = data.timestamp,
                        x = data.x,
                        y = data.y,
                        z = data.z,
                        magnitude = data.magnitude,
                        accelMagnitude = data.accelMagnitude,
                        dominantFrequency = data.dominantFrequency,
                        tremorBandPower = data.tremorBandPower,
                        totalPower = data.totalPower,
                        bandRatio = data.bandRatio,
                        peakProminence = data.peakProminence,
                        confidence = data.confidence,
                        severity = data.severity.toDouble(),
                        baselineMultiplier = data.baselineMultiplier,
                        tremorType = data.tremorType,
                        tremorTypeConfidence = data.tremorTypeConfidence,
                        isRestingState = data.isRestingState,
                        activityType = data.activityType,
                        activityConfidence = data.activityConfidence,
                        activityAgeMs = data.activityAgeMs,
                        activityAdjustedConfidence = data.activityAdjustedConfidence,
                        activityAdjustedSeverity = data.activityAdjustedSeverity,
                        isReliableMeasurement = data.isReliableMeasurement,
                        excludeFromAnalysis = data.excludeFromAnalysis,
                        isWorn = data.isWorn,
                        isCharging = data.isCharging
                    )
                    calibrationCaptureManager.recordSample(sample)
                }
            }
        )

        // Initialize config listener to receive detection algorithm updates from phone
        configListener = ConfigDataListener(this) { newConfig ->
            Timber.i("Received config update from phone: ${newConfig.profileName}")
            monitoringEngine.setConfig(newConfig)
            activityFilteringEnabled = newConfig.activityFilteringEnabled
            updateActivityRecognitionState(activityFilteringEnabled)
        }
        configListener.register()

        ratingConfigListener = RatingConfigDataListener(this) { ratingConfig ->
            Timber.i("Received rating config update from phone")
            applyRatingConfig(ratingConfig)
        }
        ratingConfigListener.register()

        // Clean up old local storage files based on retention period (run in background to avoid blocking onCreate)
        serviceScope.launch {
            cleanupOldLocalStorage()
        }

        // Create wake lock for sensor monitoring (CRITICAL for Samsung devices)
        // BATTERY FIX: Create but don't acquire yet - will be acquired only if not paused.
        // updateMonitoringState() (called below via updateChargingState) will decide.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "TremorWatch::SensorWakeLock"
        )

        // Start periodic wakelock verification to fight Samsung FreecessController
        startWakeLockMonitor()

        // Start periodic battery optimization monitoring
        startBatteryOptMonitor()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)

        if (gyroscope == null) {
            Timber.e("TremorWatch: ERROR: No gyroscope sensor found!")
        }
        if (accelerometer == null) {
            Timber.e("TremorWatch: ERROR: No linear acceleration sensor found!")
        }
        if (offBodySensor == null) {
            Timber.w("No off-body detection sensor available - defaulting to always worn")
            Timber.w("TremorWatch: Warning: No off-body sensor - assuming watch is always worn")
            hasOffBodySensor = false
            isWatchWorn = true  // Always assume worn if no sensor
        } else {
            hasOffBodySensor = true
            Timber.i("Off-body sensor available")
        }
        
        // Configure engine with sensor availability
        monitoringEngine.setOffBodySensorAvailable(hasOffBodySensor)

        // Initialize charging state
        updateChargingState()

        // Update engine with initial charging state (already done in updateChargingState, but ensure it's set)
        monitoringEngine.setCharging(isCharging)

        // Update engine with initial wear state
        isWatchWorn = monitoringEngine.isWatchWorn()

        // Register broadcast receiver for charging state changes
        registerChargingReceiver()

        // Register broadcast receiver for settings changes
        registerSettingsReceiver()

        startTime = System.currentTimeMillis()

        Timber.d("TremorService started - checking data destinations...")
        Timber.d("InfluxDB enabled: ${DataConfig.isInfluxEnabled(this)}, configured: ${DataConfig.isInfluxConfigured(this)}")
        Timber.d("Local storage enabled: ${DataConfig.isLocalStorageEnabled(this)}")

        // Register sensors with the monitoring engine as listener
        // BATTERY FIX: Only register gyro + accel and acquire wake lock if not already paused.
        // Off-body sensor always registered so we detect when watch is put back on.
        if (!isPausedDueToWearState) {
            // Acquire wake lock for active monitoring
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            Timber.w("★★★ WakeLock acquired with ${WAKELOCK_TIMEOUT_MS / 60000}min timeout (active monitoring)")

            gyroscope?.let {
                try {
                    val result = sensorManager.registerListener(
                        monitoringEngine, it,
                        MonitoringConstants.GYRO_SAMPLE_INTERVAL_US,
                        MonitoringConstants.GYRO_MAX_REPORT_LATENCY_US
                    )
                    Timber.d("Gyroscope registration result: $result")
                    Timber.d("TremorWatch: Gyroscope registered at ${1_000_000 / MonitoringConstants.GYRO_SAMPLE_INTERVAL_US}Hz with ${MonitoringConstants.GYRO_MAX_REPORT_LATENCY_US / 1_000_000}s batching")
                    isGyroInLowPowerMode = false
                } catch (e: Exception) {
                    Timber.e("ERROR: Failed to register gyroscope: ${e.message}", e)
                    e.printStackTrace()
                }
            }
            accelerometer?.let {
                try {
                    val result = sensorManager.registerListener(
                        monitoringEngine, it,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        MonitoringConstants.ACCEL_MAX_REPORT_LATENCY_US
                    )
                    Timber.d("Linear acceleration sensor registration result: $result")
                    Timber.d("TremorWatch: Linear acceleration registered at NORMAL rate with ${MonitoringConstants.ACCEL_MAX_REPORT_LATENCY_US / 1_000_000}s batching")
                } catch (e: Exception) {
                    Timber.e("ERROR: Failed to register linear acceleration sensor: ${e.message}", e)
                    e.printStackTrace()
                }
            }
        } else {
            Timber.i("Skipping gyro + accel registration - monitoring is paused (charging=$isCharging, worn=$isWatchWorn)")
        }
        // Start Activity Recognition updates on boot (not just when config arrives from phone)
        if (!isPausedDueToWearState) {
            updateActivityRecognitionState(activityFilteringEnabled)
        }

        // Off-body sensor: always register so we detect when watch is put back on
        offBodySensor?.let {
            try {
                val result = sensorManager.registerListener(monitoringEngine, it, SensorManager.SENSOR_DELAY_NORMAL)
                Timber.d("Off-body sensor registration result: $result")
                Timber.d("TremorWatch: Off-body sensor registered for wear detection")
            } catch (e: Exception) {
                Timber.e("ERROR: Failed to register off-body sensor: ${e.message}", e)
                e.printStackTrace()
            }
        }

        // Update initial pending batch count and trigger send of pending batches on startup
        // DATA GAP FIX: Use serviceScope instead of raw Thread for lifecycle safety
        serviceScope.launch {
            val fileCount = getPendingBatchFiles().size
            pendingBatchCount.set(fileCount)

            if (pendingBatchCount.get() > 0) {
                Timber.i("Service started with ${pendingBatchCount.get()} pending batch(es) - SENDING NOW via retryFailedUploads")
                retryFailedUploads(forceUpload = false)
            }
        }

        // Start periodic status updates
        schedulePeriodicStatusUpdate()

        // Start periodic heartbeat pings to phone
        scheduleHeartbeat()

        Timber.i("★★★ TremorService CREATED at ${System.currentTimeMillis()} - starting monitoring ★★★")
        
        // Check initial state and update (this will set the correct paused state)
        // Also sync stored state with actual state on startup
        serviceScope.launch {
            val storedPaused = preferencesRepository.isMonitoringPaused.first()
            val storedReason = preferencesRepository.monitoringPauseReason.first()
            Timber.d("Startup: Stored state - paused=$storedPaused, reason=$storedReason")
            Timber.d("Startup: Actual state - isCharging=$isCharging, isWorn=$isWatchWorn, hasOffBodySensor=$hasOffBodySensor")
            
            // If stored state says paused but we're not actually charging/worn, clear it immediately
            if (storedPaused && !isCharging && isWatchWorn) {
                Timber.w("Startup: Stored state says paused (reason=$storedReason) but actually not charging and worn - clearing stored state NOW")
                preferencesRepository.setMonitoringPaused(false, "")
                // Also reset the in-memory paused state to ensure consistency
                isPausedDueToWearState = false
                if (::monitoringEngine.isInitialized) {
                    monitoringEngine.setPaused(false)
                }
            }
            
            // Force update to sync stored state with actual state
            updateMonitoringState()
        }
    }

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            // If paused, send a zero-tremor update to keep the main sensors alive
            if (isPausedDueToWearState) {
                sendPausedStatusUpdate()
            }

            // Schedule next update
            statusUpdateHandler.postDelayed(this, MonitoringConstants.STATUS_UPDATE_INTERVAL_MS)
        }
    }

    private fun schedulePeriodicStatusUpdate() {
        statusUpdateHandler.postDelayed(statusUpdateRunnable, MonitoringConstants.STATUS_UPDATE_INTERVAL_MS)
    }

    private fun sendPausedStatusUpdate() {
        // Legacy function - no longer sends to Home Assistant
        Timber.d("Monitoring paused - isWorn: $isWatchWorn, isCharging: $isCharging")
    }

    private fun scheduleHeartbeat() {
        // Send first heartbeat after a short delay, then every HEARTBEAT_INTERVAL_MS
        heartbeatHandler.postDelayed(heartbeatRunnable, 10000L) // First heartbeat after 10 seconds
        Timber.d("Heartbeat scheduled - will send every ${Constants.HEARTBEAT_INTERVAL_MS / 1000}s")
    }

    private fun scheduleWatchdogAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ServiceWatchdogReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

            // Schedule alarm - 30 minutes standard
            val watchdogInterval = 30 * 60 * 1000L
            val triggerAtMillis = SystemClock.elapsedRealtime() + watchdogInterval

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ - check if we can schedule exact alarms
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Timber.d("Watchdog alarm scheduled (exact) for 30 minutes from now (battery optimized)")
            } else {
                // Fall back to inexact alarm
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Timber.w("Watchdog alarm scheduled (inexact) - exact alarm permission not granted")
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Timber.d("Watchdog alarm scheduled (exact) for 2 minutes from now")
        }
    }

    private fun scheduleUploadAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, UploadAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1,  // Different request code from watchdog
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Get upload interval from settings
        val intervalMinutes = MonitoringState.getUploadIntervalMinutes(this)
        val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMinutes * 60 * 1000L

        // Calculate wall clock time for logging
        val triggerTime = System.currentTimeMillis() + intervalMinutes * 60 * 1000L
        val triggerTimeFormatted = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(triggerTime))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Timber.i("★★★ Upload alarm scheduled (exact) - next upload in $intervalMinutes minutes at ~$triggerTimeFormatted")
                Timber.d("TremorWatch: Upload alarm scheduled - next upload in $intervalMinutes minutes")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Timber.w("★★★ Upload alarm scheduled (inexact) - next upload in ~$intervalMinutes minutes around $triggerTimeFormatted")
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Timber.i("★★★ Upload alarm scheduled (exact) - next upload in $intervalMinutes minutes at ~$triggerTimeFormatted")
            Timber.d("TremorWatch: Upload alarm scheduled - next upload in $intervalMinutes minutes")
        }
    }

    private fun cancelUploadAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, UploadAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Timber.d("Upload alarm cancelled")
    }

    private fun scheduleBatchRetryAlarm() {
        // Reset backoff counter when freshly scheduling
        BatchRetryAlarmReceiver.resetRetryCount(this)

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, BatchRetryAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            2,  // Different request code from watchdog and upload
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Initial retry after 2 minutes; receiver handles backoff for subsequent retries
        val triggerAtMillis = SystemClock.elapsedRealtime() + 2 * 60 * 1000L

        // Use inexact alarm - retries don't need exact timing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
        Timber.d("Batch retry alarm scheduled with exponential backoff (initial: 2min)")
    }

    private fun cancelBatchRetryAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, BatchRetryAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Timber.d("Batch retry alarm cancelled")
    }

    /**
     * Schedule periodic rating prompts.
     * 
     * Uses the configured minimum interval from rating prefs.
     * The RatingPromptReceiver handles additional checks (active hours, daily limits, etc.).
     */
    private fun scheduleRatingPromptAlarm() {
        RatingPromptReceiver.scheduleNextPrompt(this)
    }

    private fun cancelRatingPromptAlarm() {
        RatingPromptReceiver.cancelPrompt(this)
    }

    private fun applyRatingConfig(config: RatingConfig) {
        val prefs = getSharedPreferences(RATING_PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check if we should trigger a prompt immediately after config change
        // This handles the case where prompts were stopped due to daily limit
        // but the phone has now increased the limit
        val oldMaxDaily = prefs.getInt(KEY_MAX_DAILY_PROMPTS, 6)
        val promptsToday = prefs.getInt(KEY_PROMPTS_TODAY, 0)
        val wasAtLimit = promptsToday >= oldMaxDaily
        val nowHasRoom = promptsToday < config.maxPromptsPerDay
        
        // Store calibration config so RatingActivity can read it
        prefs.edit()
            .putBoolean("calibration_enabled", config.calibrationModeEnabled)
            .putInt("calibration_duration_seconds", config.calibrationDurationSeconds)
            .apply()
        Timber.i("Calibration config stored: enabled=${config.calibrationModeEnabled}, duration=${config.calibrationDurationSeconds}s")

        RatingPromptReceiver.applyConfig(
            context = this,
            promptsEnabled = config.promptsEnabled,
            minIntervalMinutes = config.promptFrequencyMinutes,
            maxDailyPrompts = config.maxPromptsPerDay,
            activeStartHour = config.activeHoursStart,
            activeEndHour = config.activeHoursEnd,
            promptVibrationEnabled = config.promptVibrationEnabled,
            promptVibrationStrong = config.promptVibrationStrong,
            promptFollowupVibration = config.promptFollowupVibration,
            scheduleAlarm = false
        )
        
        if (!config.promptsEnabled || !config.promptFollowupVibration) {
            prefs.edit().putBoolean(KEY_PROMPT_FOLLOWUP_PENDING, false).apply()
            ratingFollowupHandler.removeCallbacks(ratingFollowupRunnable)
        }
        
        cancelRatingPromptAlarm()
        
        // If we were at the daily limit but now have room (limit increased),
        // reset next_prompt_elapsed to trigger an immediate check
        if (wasAtLimit && nowHasRoom && config.promptsEnabled) {
            Timber.i("Daily limit increased from $oldMaxDaily to ${config.maxPromptsPerDay} (currently at $promptsToday). Triggering immediate prompt check.")
            prefs.edit().putLong(KEY_NEXT_PROMPT_ELAPSED, 0L).apply()
        }
        
        scheduleNextRatingPromptTick()
    }

    /**
     * Start calibration data capture for the given rating.
     * Called via intent from RatingActivity/MainActivity after a rating is submitted.
     * Wires the onCaptureComplete callback to send the file to the phone.
     */
    private fun startCalibrationCapture(ratingId: String, durationSeconds: Int) {
        if (!::calibrationCaptureManager.isInitialized) {
            Timber.w("CalibrationCaptureManager not initialized, cannot start capture")
            return
        }

        val watchId = try {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) { "unknown" }

        // Wire the completion callback to send calibration file to phone
        calibrationCaptureManager.onCaptureComplete = { file, count ->
            Timber.i("Calibration capture complete: $count samples in ${file.name}")
            serviceScope.launch {
                try {
                    val channelSender = com.opensource.tremorwatch.communication.WatchChannelSender(this@TremorService)
                    val nodes = com.google.android.gms.wearable.Wearable.getNodeClient(this@TremorService).connectedNodes
                    val connectedNodes = com.google.android.gms.tasks.Tasks.await(nodes, 10, java.util.concurrent.TimeUnit.SECONDS)
                    if (connectedNodes.isNotEmpty()) {
                        val success = channelSender.sendCalibrationFile(file, connectedNodes.first())
                        Timber.i("Calibration file send ${if (success) "succeeded" else "failed"}: ${file.name}")
                    } else {
                        Timber.w("No phone connected - calibration file not sent (saved locally)")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to send calibration file: ${file.name}")
                }
            }
        }

        val started = calibrationCaptureManager.startCapture(ratingId, durationSeconds, watchId)
        if (started) {
            Timber.i("Calibration capture started: ratingId=$ratingId, duration=${durationSeconds}s")
        } else {
            Timber.w("Failed to start calibration capture (already capturing or limit reached)")
        }
    }

    private fun scheduleNextRatingPromptTick() {
        ratingPromptHandler.removeCallbacks(ratingPromptRunnable)
        val prefs = getSharedPreferences(RATING_PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PROMPTS_ENABLED, true)) {
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val minIntervalMinutes = prefs.getInt(KEY_MIN_INTERVAL_MINUTES, 60)
        val safeIntervalMinutes = maxOf(15, minIntervalMinutes)
        val intervalMs = safeIntervalMinutes * 60 * 1000L
        var nextElapsed = prefs.getLong(KEY_NEXT_PROMPT_ELAPSED, 0L)

        // Reboot/stale detection: if stored nextElapsed is far in the future, reset.
        val maxReasonableDelay = intervalMs * 2
        if (nextElapsed > nowElapsed + maxReasonableDelay) {
            Timber.w("Detected stale next_prompt_elapsed ($nextElapsed) during schedule tick (now=$nowElapsed). Resetting.")
            nextElapsed = nowElapsed + 60_000L // Trigger in 1 minute
            prefs.edit().putLong(KEY_NEXT_PROMPT_ELAPSED, nextElapsed).apply()
        }
        val delay = if (nextElapsed > nowElapsed) {
            nextElapsed - nowElapsed
        } else {
            1000L
        }
        ratingPromptHandler.postDelayed(ratingPromptRunnable, delay)
    }

    private fun ensureRatingPromptScheduled() {
        val prefs = getSharedPreferences(RATING_PREFS_NAME, Context.MODE_PRIVATE)
        val promptsEnabled = prefs.getBoolean(KEY_PROMPTS_ENABLED, true)
        if (!promptsEnabled) {
            return
        }

        val minIntervalMinutes = prefs.getInt(KEY_MIN_INTERVAL_MINUTES, 60)
        val safeIntervalMinutes = maxOf(15, minIntervalMinutes)
        val intervalMs = safeIntervalMinutes * 60 * 1000L

        val nowElapsed = SystemClock.elapsedRealtime()
        var nextElapsed = prefs.getLong(KEY_NEXT_PROMPT_ELAPSED, 0L)
        
        // Reboot detection: If nextElapsed is more than 2 intervals in the future,
        // the device likely rebooted and the stored value is stale (SystemClock.elapsedRealtime
        // resets on reboot but SharedPreferences persists). Reset to trigger prompt soon.
        val maxReasonableDelay = intervalMs * 2
        if (nextElapsed > nowElapsed + maxReasonableDelay) {
            Timber.w("Detected stale next_prompt_elapsed ($nextElapsed) - device likely rebooted (now=$nowElapsed). Resetting.")
            nextElapsed = nowElapsed + 60_000L // Trigger in 1 minute
            prefs.edit().putLong(KEY_NEXT_PROMPT_ELAPSED, nextElapsed).apply()
        }
        
        if (nextElapsed <= nowElapsed) {
            maybeShowRatingPrompt(prefs)
            val newNextElapsed = nowElapsed + intervalMs
            prefs.edit().putLong(KEY_NEXT_PROMPT_ELAPSED, newNextElapsed).commit()
            ratingPromptHandler.postDelayed(ratingPromptRunnable, intervalMs)
        } else {
            ratingPromptHandler.postDelayed(ratingPromptRunnable, nextElapsed - nowElapsed)
        }
    }

    /**
     * Check conditions and show a rating prompt if appropriate.
     * 
     * CRITICAL: This function now always advances the next prompt time, even when
     * skipping. Previously, early returns without updating next_prompt_elapsed
     * caused permanent suppression when conditions weren't met.
     */
    private fun maybeShowRatingPrompt(prefs: android.content.SharedPreferences) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val minIntervalMinutes = prefs.getInt(KEY_MIN_INTERVAL_MINUTES, 60)
        val safeIntervalMinutes = maxOf(15, minIntervalMinutes)
        val intervalMs = safeIntervalMinutes * 60 * 1000L
        val nextPromptElapsed = nowElapsed + intervalMs

        // Helper to advance the next prompt time and schedule the next tick
        fun advanceAndSchedule(reason: String) {
            prefs.edit().putLong(KEY_NEXT_PROMPT_ELAPSED, nextPromptElapsed).apply()
            Timber.d("Rating prompt skipped - $reason. Next check in ${safeIntervalMinutes}m")
            ratingPromptHandler.removeCallbacks(ratingPromptRunnable)
            ratingPromptHandler.postDelayed(ratingPromptRunnable, intervalMs)
        }

        if (isCharging) {
            advanceAndSchedule("watch is charging")
            return
        }
        if (hasOffBodySensor && !isWatchWorn) {
            advanceAndSchedule("watch not worn")
            return
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dontAskDate = prefs.getString(KEY_DONT_ASK_DATE, null)
        if (dontAskDate == today) {
            advanceAndSchedule("user set 'don't ask today'")
            return
        }

        val promptsTodayDate = prefs.getString(KEY_PROMPTS_TODAY_DATE, null)
        var promptsToday = if (promptsTodayDate == today) {
            prefs.getInt(KEY_PROMPTS_TODAY, 0)
        } else {
            // Day rollover: reset both the date marker and daily prompt counter.
            prefs.edit()
                .putString(KEY_PROMPTS_TODAY_DATE, today)
                .putInt(KEY_PROMPTS_TODAY, 0)
                .commit()
            0
        }

        val maxDailyPrompts = prefs.getInt(KEY_MAX_DAILY_PROMPTS, 6)
        if (promptsToday >= maxDailyPrompts) {
            // Keep periodic checks so prompts automatically resume after date rollover.
            advanceAndSchedule("daily limit reached ($promptsToday/$maxDailyPrompts)")
            return
        }

        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val startHour = prefs.getInt(KEY_ACTIVE_HOURS_START, 6)
        val endHour = prefs.getInt(KEY_ACTIVE_HOURS_END, 22)
        if (currentHour < startHour || currentHour >= endHour) {
            advanceAndSchedule("outside active hours ($currentHour not in $startHour-$endHour)")
            return
        }

        // All checks passed - show the prompt
        val followupEnabled = prefs.getBoolean(KEY_PROMPT_FOLLOWUP_VIBRATION, false)
        if (prefs.edit()
                .putInt(KEY_PROMPTS_TODAY, promptsToday + 1)
                .putLong(KEY_PROMPT_LAST_SHOWN_ELAPSED, nowElapsed)
                .putLong(KEY_NEXT_PROMPT_ELAPSED, nextPromptElapsed)
                .putBoolean(KEY_PROMPT_FOLLOWUP_PENDING, followupEnabled)
                .commit()
        ) {
            Timber.i("Showing rating prompt (${promptsToday + 1}/$maxDailyPrompts today) via service")
            showRatingPromptNotification("PROMPTED")
            triggerPromptVibration(followup = false)
            if (followupEnabled) {
                scheduleFollowupVibration()
            }
            // Schedule the next prompt
            ratingPromptHandler.removeCallbacks(ratingPromptRunnable)
            ratingPromptHandler.postDelayed(ratingPromptRunnable, intervalMs)
        }
    }

    private fun ensureRatingPromptChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                RATING_CHANNEL_ID,
                "Rating Prompts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Subjective rating prompts"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun showRatingPromptNotification(source: String) {
        try {
            ensureRatingPromptChannel()
            val activityIntent = Intent().apply {
                setClassName(packageName, "com.opensource.tremorwatch.RatingActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra("source", source)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                3,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, RATING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Time to rate")
                .setContentText("How are you feeling?")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(3, notification)
        } catch (e: Exception) {
            Timber.e(e, "Failed to show rating prompt notification: ${e.message}")
        }
    }

    private fun triggerPromptVibration(followup: Boolean) {
        val prefs = getSharedPreferences(RATING_PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PROMPT_VIBRATION_ENABLED, true)) {
            return
        }
        val strong = prefs.getBoolean(KEY_PROMPT_VIBRATION_STRONG, false)
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) {
            return
        }

        val effect = if (strong) {
            val pattern = if (followup) {
                longArrayOf(0, 200, 100, 200)
            } else {
                longArrayOf(0, 250, 120, 250, 120, 250)
            }
            VibrationEffect.createWaveform(pattern, -1)
        } else {
            val duration = if (followup) 120L else 220L
            VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    private fun scheduleFollowupVibration() {
        ratingFollowupHandler.removeCallbacks(ratingFollowupRunnable)
        ratingFollowupHandler.postDelayed(ratingFollowupRunnable, PROMPT_FOLLOWUP_DELAY_MS)
    }

    private fun maybeRunFollowupVibration() {
        val prefs = getSharedPreferences(RATING_PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PROMPT_FOLLOWUP_VIBRATION, false)) {
            return
        }
        if (!prefs.getBoolean(KEY_PROMPT_FOLLOWUP_PENDING, false)) {
            return
        }

        val lastShown = prefs.getLong(KEY_PROMPT_LAST_SHOWN_ELAPSED, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastShown == 0L || nowElapsed - lastShown < PROMPT_FOLLOWUP_DELAY_MS) {
            val delay = (PROMPT_FOLLOWUP_DELAY_MS - (nowElapsed - lastShown)).coerceAtLeast(1000L)
            ratingFollowupHandler.postDelayed(ratingFollowupRunnable, delay)
            return
        }

        if (isCharging || (hasOffBodySensor && !isWatchWorn)) {
            Timber.d("Follow-up vibration skipped - watch is charging or not worn")
            prefs.edit().putBoolean(KEY_PROMPT_FOLLOWUP_PENDING, false).apply()
            return
        }

        Timber.d("Triggering follow-up vibration for missed prompt")
        triggerPromptVibration(followup = true)
        prefs.edit().putBoolean(KEY_PROMPT_FOLLOWUP_PENDING, false).apply()
    }

    // ====================== WEAR DETECTION & CHARGING MANAGEMENT ======================

    private fun updateChargingState() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        isCharging = batteryManager.isCharging
        Timber.d("Charging state: $isCharging")
        if (::monitoringEngine.isInitialized) {
            monitoringEngine.setCharging(isCharging)
        }
        updateMonitoringState()
    }

    private fun registerChargingReceiver() {
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                        Intent.ACTION_POWER_CONNECTED -> {
                            isCharging = true
                            Timber.i("Power connected - watch is charging")
                            Timber.d("TremorWatch: Watch connected to charger")
                            if (::monitoringEngine.isInitialized) {
                                monitoringEngine.setCharging(true)
                            }
                            updateMonitoringState()
                            
                            // Send diagnostic event for charging started
                            sendChargingDiagnosticEvent(true)
                        }
                        Intent.ACTION_POWER_DISCONNECTED -> {
                            isCharging = false
                            Timber.i("Power disconnected - watch unplugged")
                            Timber.d("TremorWatch: Watch disconnected from charger")
                            if (::monitoringEngine.isInitialized) {
                                monitoringEngine.setCharging(false)
                            }
                            updateMonitoringState()
                            
                            // Send diagnostic event for charging stopped
                            sendChargingDiagnosticEvent(false)
                        }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        // Android 13+ requires explicit export flag for registerReceiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chargingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(chargingReceiver, filter)
        }
    }

    private fun unregisterChargingReceiver() {
        chargingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Timber.e("Error unregistering charging receiver: ${e.message}")
            }
        }
        chargingReceiver = null
    }
    
    /**
     * Get current battery level (0-100)
     */
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    /**
     * Send a diagnostic event for charging state changes
     */
    private fun sendChargingDiagnosticEvent(charging: Boolean) {
        if (::phoneCommunication.isInitialized) {
            val eventData = mapOf(
                "is_charging" to charging,
                "battery_level" to getBatteryLevel(),
                "is_worn" to isWatchWorn
            )
            phoneCommunication.sendDiagnosticEvent(
                if (charging) "charging_started" else "charging_stopped",
                eventData
            ) { success ->
                if (success) {
                    Timber.d("Diagnostic event sent: ${if (charging) "charging_started" else "charging_stopped"}")
                }
            }
        }
    }
    
    /**
     * Send a diagnostic event for monitoring state changes
     */
    private fun sendMonitoringStateDiagnosticEvent(paused: Boolean, reason: String) {
        if (::phoneCommunication.isInitialized) {
            val eventData = mapOf(
                "is_paused" to paused,
                "reason" to reason,
                "battery_level" to getBatteryLevel(),
                "is_charging" to isCharging,
                "is_worn" to isWatchWorn
            )
            phoneCommunication.sendDiagnosticEvent(
                if (paused) "monitoring_paused" else "monitoring_resumed",
                eventData
            ) { success ->
                if (success) {
                    Timber.d("Diagnostic event sent: ${if (paused) "monitoring_paused" else "monitoring_resumed"}")
                }
            }
        }
    }

    private fun registerSettingsReceiver() {
        settingsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.opensource.tremorwatch.SETTINGS_CHANGED" -> {
                        Timber.i("Settings changed - re-evaluating monitoring state")
                        // Refresh charging state when settings change
                        updateChargingState()
                        updateMonitoringState()
                    }
                    "com.opensource.tremorwatch.REFRESH_CHARGING_STATE" -> {
                        Timber.i("Manual charging state refresh requested")
                        updateChargingState()
                    }
                    "com.opensource.tremorwatch.UPLOAD_INTERVAL_CHANGED" -> {
                        Timber.i("Upload interval changed - rescheduling upload alarm")
                        cancelUploadAlarm()
                        scheduleUploadAlarm()
                    }
                    "com.opensource.tremorwatch.TRIGGER_UPLOAD" -> {
                        val isManual = intent?.getBooleanExtra("manual", false) ?: false
                        if (isManual) {
                            Timber.i("Manual upload triggered - uploading pending batches")
                            Timber.d("TremorWatch: Manual upload - uploading pending batches")
                        } else {
                            Timber.i("Automatic upload triggered - uploading pending batches")
                            Timber.d("TremorWatch: Upload alarm triggered - uploading pending batches")
                            // Reschedule next upload alarm for automatic uploads only
                            scheduleUploadAlarm()
                        }
                        // Run upload in background coroutine to avoid blocking broadcast receiver
                        serviceScope.launch {
                            retryFailedUploads(forceUpload = isManual)
                            cleanupOldLocalStorage()
                        }
                    }
                    "com.opensource.tremorwatch.EMERGENCY_CLEAR" -> {
                        Timber.w("EMERGENCY CLEAR triggered - deleting all pending batches")
                        emergencyClearAllBatches()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.opensource.tremorwatch.SETTINGS_CHANGED")
            addAction("com.opensource.tremorwatch.UPLOAD_INTERVAL_CHANGED")
            addAction("com.opensource.tremorwatch.TRIGGER_UPLOAD")
            addAction("com.opensource.tremorwatch.EMERGENCY_CLEAR")
            addAction("com.opensource.tremorwatch.REFRESH_CHARGING_STATE")
        }

        // Android 13+ requires explicit export flag for registerReceiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }
    }

    private fun unregisterSettingsReceiver() {
        settingsReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Timber.e("Error unregistering settings receiver: ${e.message}")
            }
        }
        settingsReceiver = null
    }
    

    /**
     * Update monitoring state based on wear detection and charging state.
     * Pauses sensor data collection when:
     * - Watch is not worn AND pause setting is enabled (only if off-body sensor is available)
     * - Watch is charging AND pause setting is enabled
     */
    private fun updateMonitoringState() {
        if (!MonitoringState.isPauseWhenNotWorn(this)) {
            // Feature disabled - always monitor
            if (isPausedDueToWearState) {
                resumeMonitoring()
            }
            return
        }

        // Only check wear state if the sensor is available
        // Get current wear state from engine
        val currentWearState = if (::monitoringEngine.isInitialized) {
            monitoringEngine.isWatchWorn()
        } else {
            true  // Default to worn if engine not initialized
        }
        isWatchWorn = currentWearState  // Sync service state with engine state
        
        val shouldPauseForWear = hasOffBodySensor && !currentWearState
        val shouldPause = shouldPauseForWear || isCharging

        Timber.d("updateMonitoringState: shouldPause=$shouldPause (wear=$shouldPauseForWear, charging=$isCharging), isPausedDueToWearState=$isPausedDueToWearState, isWorn=$isWatchWorn")

        if (shouldPause && !isPausedDueToWearState) {
            pauseMonitoring()
        } else if (!shouldPause && isPausedDueToWearState) {
            resumeMonitoring()
        } else if (!shouldPause && !isPausedDueToWearState) {
            // Already active, but ensure stored state is correct (clear any stale paused state)
            serviceScope.launch {
                val currentStoredPaused = preferencesRepository.isMonitoringPaused.first()
                if (currentStoredPaused) {
                    Timber.w("updateMonitoringState: Clearing stale stored paused state")
                    preferencesRepository.setMonitoringPaused(false, "")
                }
            }
        }
    }

    private fun pauseMonitoring() {
        isPausedDueToWearState = true
        if (::monitoringEngine.isInitialized) {
            monitoringEngine.setPaused(true)
        }
        stopActivityRecognitionUpdates()

        // BATTERY FIX: Unregister gyroscope and accelerometer to stop 50Hz sensor wake-ups.
        // Keep off-body sensor registered so we detect when the watch is put back on.
        if (::sensorManager.isInitialized && ::monitoringEngine.isInitialized) {
            gyroscope?.let { sensorManager.unregisterListener(monitoringEngine, it) }
            accelerometer?.let { sensorManager.unregisterListener(monitoringEngine, it) }
            Timber.i("Sensors unregistered (gyro + accel) to save battery while paused")
        }

        // BATTERY FIX: Release wake lock so the device can enter doze mode.
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.i("WakeLock released for pause - device can doze")
            }
        }

        val reason = when {
            !isWatchWorn && isCharging -> "not worn and charging"
            !isWatchWorn -> "not worn"
            isCharging -> "charging"
            else -> "unknown"
        }
        Timber.w("★★★ MONITORING PAUSED: $reason (sensors off, wakelock released) ★★★")

        // Store monitoring state for UI using PreferencesRepository
        serviceScope.launch {
            preferencesRepository.setMonitoringPaused(true, reason)
            Timber.d("Stored monitoring state: paused=true, reason=$reason")
        }

        // Send diagnostic event
        sendMonitoringStateDiagnosticEvent(true, reason)
    }

    private fun resumeMonitoring() {
        isPausedDueToWearState = false
        if (::monitoringEngine.isInitialized) {
            monitoringEngine.setPaused(false)
        }
        updateActivityRecognitionState(activityFilteringEnabled)

        // BATTERY FIX: Re-acquire wake lock before re-registering sensors.
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(WAKELOCK_TIMEOUT_MS)
                Timber.i("WakeLock re-acquired for resume with ${WAKELOCK_TIMEOUT_MS / 60000}min timeout")
            }
        }

        // BATTERY FIX: Re-register gyroscope and accelerometer that were unregistered during pause.
        if (::sensorManager.isInitialized && ::monitoringEngine.isInitialized) {
            gyroscope?.let {
                try {
                    val gyroRate = if (isGyroInLowPowerMode) {
                        MonitoringConstants.GYRO_STILL_SAMPLE_INTERVAL_US
                    } else {
                        MonitoringConstants.GYRO_SAMPLE_INTERVAL_US
                    }
                    sensorManager.registerListener(
                        monitoringEngine, it,
                        gyroRate,
                        MonitoringConstants.GYRO_MAX_REPORT_LATENCY_US
                    )
                    Timber.i("Gyroscope re-registered on resume (lowPower=$isGyroInLowPowerMode)")
                } catch (e: Exception) {
                    Timber.e("Failed to re-register gyroscope on resume: ${e.message}", e)
                }
            }
            accelerometer?.let {
                try {
                    sensorManager.registerListener(
                        monitoringEngine, it,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        MonitoringConstants.ACCEL_MAX_REPORT_LATENCY_US
                    )
                    Timber.i("Accelerometer re-registered on resume")
                } catch (e: Exception) {
                    Timber.e("Failed to re-register accelerometer on resume: ${e.message}", e)
                }
            }
        }

        Timber.i("★★★ MONITORING RESUMED: worn=${isWatchWorn}, charging=${isCharging} (sensors on, wakelock held) ★★★")

        // Store monitoring state for UI using PreferencesRepository
        serviceScope.launch {
            preferencesRepository.setMonitoringPaused(false, "")
            Timber.d("Stored monitoring state: paused=false")
        }

        // Send diagnostic event
        sendMonitoringStateDiagnosticEvent(false, "")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Service should continue running even if app is swiped away
        Timber.i("Task removed - service continuing in background")

        // Restart the service to ensure it stays running
        val restartServiceIntent = Intent(applicationContext, TremorService::class.java)
        val pendingIntent = PendingIntent.getService(
            this,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            pendingIntent
        )

        Timber.d("TremorWatch: App swiped away - service auto-restarting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)  // LifecycleService transitions to STARTED state

        if (intent?.action == ACTION_ACTIVITY_UPDATE) {
            Timber.i("★ AR intent received. extras=${intent.extras}")
            Timber.i("★ AR hasResult=${ActivityRecognitionResult.hasResult(intent)}")
            handleActivityUpdate(intent)
            return START_STICKY
        }

        if (intent?.action == ACTION_START_CALIBRATION) {
            val ratingId = intent.getStringExtra(EXTRA_RATING_ID) ?: return START_STICKY
            val duration = intent.getIntExtra(EXTRA_CALIBRATION_DURATION, 60)
            startCalibrationCapture(ratingId, duration)
            return START_STICKY
        }

        val now = System.currentTimeMillis()
        val lastSampleTime = if (::monitoringEngine.isInitialized) {
            monitoringEngine.getLastSampleTime()
        } else {
            0L
        }
        val timeSinceLastPing = now - lastSampleTime
        Timber.w("★★★ Service keepalive ping - last sample ${timeSinceLastPing / 1000}s ago")

        // Check battery optimization status
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isOptimized = !powerManager.isIgnoringBatteryOptimizations(packageName)
            if (isOptimized) {
                Timber.e("★★★ CRITICAL: Battery optimization ENABLED - expect data gaps!")
            }
        }

        // Reschedule watchdog alarm to keep service alive
        scheduleWatchdogAlarm()

        // CRITICAL: Check charging state every watchdog ping
        // ACTION_POWER_DISCONNECTED can be suppressed in doze mode, so we must poll
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentlyCharging = batteryManager.isCharging
        if (currentlyCharging != isCharging) {
            val oldState = isCharging
            isCharging = currentlyCharging
            Timber.i("WATCHDOG: Charging state changed: $oldState -> $isCharging")
            Timber.d("TremorWatch: WATCHDOG: Charging changed from $oldState to $isCharging")
            // Update engine's charging state
            if (::monitoringEngine.isInitialized) {
                monitoringEngine.setCharging(isCharging)
            }
            updateMonitoringState()
        }

        // Check for sensor freeze - if no samples received in 1 minute (instead of 2), re-register
        // This helps catch sensor stalls faster
        // Skip check if monitoring is paused (charging or not worn)
        val lastSampleTimeForFreezeCheck = if (::monitoringEngine.isInitialized) {
            monitoringEngine.getLastSampleTime()
        } else {
            0L
        }
        val timeSinceLastSample = now - lastSampleTimeForFreezeCheck

        if (!isPausedDueToWearState && lastSampleTimeForFreezeCheck > 0 && timeSinceLastSample > 2 * 60 * 1000L) {
            Timber.e("WATCHDOG: Sensors appear frozen! No data for ${timeSinceLastSample / 1000}s. Re-registering...")
            Timber.w("TremorWatch: WATCHDOG: Sensors frozen (${timeSinceLastSample / 1000}s), re-registering")

            // Unregister all sensors
            sensorManager.unregisterListener(this)

                // Re-register gyroscope with batching
                gyroscope?.let {
                    try {
                        val gyroRate = if (isGyroInLowPowerMode) {
                            MonitoringConstants.GYRO_STILL_SAMPLE_INTERVAL_US
                        } else {
                            MonitoringConstants.GYRO_SAMPLE_INTERVAL_US
                        }
                        val result = sensorManager.registerListener(
                            monitoringEngine, it,
                            gyroRate,
                            MonitoringConstants.GYRO_MAX_REPORT_LATENCY_US
                        )
                        Timber.d("Gyroscope re-registered: $result (lowPower=$isGyroInLowPowerMode)")
                    } catch (e: Exception) {
                        Timber.e("Failed to re-register gyroscope: ${e.message}", e)
                    }
                }

                // Re-register accelerometer with batching
                accelerometer?.let {
                    try {
                        val result = sensorManager.registerListener(
                            monitoringEngine, it,
                            SensorManager.SENSOR_DELAY_NORMAL,
                            MonitoringConstants.ACCEL_MAX_REPORT_LATENCY_US
                        )
                        Timber.d("Linear acceleration re-registered: $result (batched)")
                    } catch (e: Exception) {
                        Timber.e("Failed to re-register accelerometer: ${e.message}", e)
                    }
                }

                // Re-register off-body sensor (no batching)
                offBodySensor?.let {
                    try {
                        val result = sensorManager.registerListener(monitoringEngine, it, SensorManager.SENSOR_DELAY_NORMAL)
                        Timber.d("Off-body sensor re-registered: $result")
                    } catch (e: Exception) {
                        Timber.e("Failed to re-register off-body sensor: ${e.message}", e)
                    }
                }
        }

        // Renew wake lock (backup to the 10-minute wakelock monitor)
        // BATTERY FIX: Only renew when actively monitoring - not when paused
        if (!isPausedDueToWearState) {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(WAKELOCK_TIMEOUT_MS)
                    Timber.d("Wake lock re-acquired by watchdog with ${WAKELOCK_TIMEOUT_MS / 60000}min timeout")
                } else {
                    Timber.d("Wake lock still held - skipping (monitor will renew)")
                }
            }
        }

        // Log heartbeat to Home Assistant for monitoring
        val lastSampleTimeForLog = if (::monitoringEngine.isInitialized) {
            monitoringEngine.getLastSampleTime()
        } else {
            0L
        }
        val timeSinceLastSampleSeconds = if (lastSampleTimeForLog > 0) (now - lastSampleTimeForLog) / 1000 else 0
        Timber.d("TremorWatch: Watchdog ping: sensors ${if (timeSinceLastSampleSeconds < 60) "active" else "idle ${timeSinceLastSampleSeconds}s"}")

        // Update notification to show current uptime and status
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, buildNotification())
        } catch (e: Exception) {
            Timber.w("Failed to update notification: ${e.message}")
        }

        // Return START_STICKY to ensure Android restarts the service if killed
        return START_STICKY
    }

    // Sensor events are now handled by TremorMonitoringEngine
    // The service no longer implements SensorEventListener directly
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        // Delegate gyroscope, linear accelerometer, and off-body sensor to monitoring engine
        if (::monitoringEngine.isInitialized) {
            monitoringEngine.onSensorChanged(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Delegate to monitoring engine
        if (::monitoringEngine.isInitialized) {
            monitoringEngine.onAccuracyChanged(sensor, accuracy)
        }
    }

    override fun onDestroy() {
        Timber.d("onDestroy() called - cleaning up service")
        Timber.d("Lifecycle state before destroy: ${lifecycle.currentState}")
        
        super.onDestroy()  // LifecycleService.onDestroy() transitions to DESTROYED state

        val uptime = System.currentTimeMillis() - startTime
        Timber.i("★★★ TremorService DESTROYED at ${System.currentTimeMillis()} - uptime was ${uptime / 1000}s ★★★")

        // CRITICAL: Do all cleanup synchronously and quickly to avoid timeout
        try {
            // 1. Release wake lock FIRST (most critical)
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Timber.d("Wake lock released")
                }
            }

            // Cancel any in-flight coroutines (uploads, cleanup jobs, etc).
            serviceJob.cancel()

            // 2. Cancel watchdog alarm immediately
            cancelWatchdogAlarm()

            // 2b. Cancel upload alarm
            cancelUploadAlarm()

            // 2c. Cancel batch retry alarm
            cancelBatchRetryAlarm()

            // 3. Unregister sensors (fast operation)
            if (::sensorManager.isInitialized) {
                try {
                    if (::monitoringEngine.isInitialized) {
                        sensorManager.unregisterListener(monitoringEngine)
                    }
                    sensorManager.unregisterListener(this)
                    Timber.d("All sensor listeners unregistered")
                } catch (e: Exception) {
                    Timber.e("Error unregistering sensors: ${e.message}", e)
                }
            }

            // 3b. Stop activity recognition updates
            stopActivityRecognitionUpdates()

            // 4. Shutdown monitoring engine
            if (::monitoringEngine.isInitialized) {
                monitoringEngine.shutdown()
                Timber.d("Monitoring engine shut down")
            }

            // 5. Unregister config listener
            if (::configListener.isInitialized) {
                configListener.unregister()
                Timber.d("Config listener unregistered")
            }
            if (::ratingConfigListener.isInitialized) {
                ratingConfigListener.unregister()
                Timber.d("Rating config listener unregistered")
            }

            // 4. Cancel periodic status updates, heartbeats, and wakelock monitor
            statusUpdateHandler.removeCallbacks(statusUpdateRunnable)
            heartbeatHandler.removeCallbacks(heartbeatRunnable)
            wakeLockMonitorHandler.removeCallbacks(wakeLockMonitorRunnable)
            batteryOptMonitorHandler.removeCallbacks(batteryOptMonitorRunnable)
            ratingPromptHandler.removeCallbacks(ratingPromptRunnable)
            ratingFollowupHandler.removeCallbacks(ratingFollowupRunnable)
            Timber.d("Periodic handlers stopped (status, heartbeat, wakelock monitor, battery opt monitor)")

            // 5. Unregister broadcast receivers
            unregisterChargingReceiver()
            unregisterSettingsReceiver()

            // 6. Shutdown phone communication
            if (::phoneCommunication.isInitialized) {
                phoneCommunication.shutdown()
                Timber.d("Phone communication shut down")
            }

            Timber.d("Service cleanup completed successfully")

            // 7. Try to upload final data (non-blocking, best effort)
            // This may fail if service is being killed aggressively, but that's OK
            try {
                Timber.d("TremorWatch: Service stopped - batches sent: $batchesSent")
            } catch (e: Exception) {
                // Ignore errors during final upload - service is shutting down
                Timber.w("Final upload failed during shutdown: ${e.message}")
            }

        } catch (e: Exception) {
            // Catch any errors during cleanup to ensure service stops cleanly
            Timber.e("Error during service cleanup: ${e.message}", e)
        }
    }

    private fun cancelWatchdogAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ServiceWatchdogReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tremor_channel",
                "Tremor Monitor",
                NotificationManager.IMPORTANCE_HIGH  // HIGH to prevent Doze killing
            ).apply {
                description = "Keeps tremor monitoring active in background"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)  // Silent notification
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String? = null, text: String? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // Calculate service uptime
        val uptime = if (startTime > 0) {
            System.currentTimeMillis() - startTime
        } else {
            0L
        }
        val uptimeText = formatUptime(uptime)

        // Determine monitoring state
        val stateText = when {
            isPausedDueToWearState -> "Paused (not worn)"
            isCharging -> "Paused (charging)"
            else -> "Active"
        }

        // Use custom title/text if provided, otherwise use default
        val notificationTitle = title ?: "Tremor Monitor - $stateText"
        val notificationText = text ?: "Uptime: $uptimeText • Batches sent: $batchesSent"

        return NotificationCompat.Builder(this, "tremor_channel")
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)  // Cannot be swiped away
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // HIGH priority to prevent killing
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun formatUptime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    private fun emergencyClearAllBatches() {
        Timber.w("EMERGENCY: Clearing all pending batches")
        try {
            val pendingDir = File(filesDir, "pending_batches")
            if (pendingDir.exists()) {
                val deleted = pendingDir.listFiles()?.size ?: 0
                pendingDir.deleteRecursively()
                pendingDir.mkdirs()
                Timber.w("EMERGENCY: Deleted $deleted pending batch files")
            }
        } catch (e: Exception) {
            Timber.e("EMERGENCY: Failed to clear batches: ${e.message}")
        }
    }

    // ====================== ACTIVITY RECOGNITION ======================

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun createActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, TremorService::class.java).apply {
            action = ACTION_ACTIVITY_UPDATE
        }
        // Use FLAG_MUTABLE on Android 12+ so Google Play Services can add ActivityRecognitionResult extras
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getService(
            this,
            ACTIVITY_UPDATE_REQUEST_CODE,
            intent,
            flags
        )
    }

    private fun startActivityRecognitionUpdates() {
        if (activityUpdatesRegistered) {
            Timber.d("AR: Already registered, skipping")
            return
        }
        if (!hasActivityRecognitionPermission()) {
            Timber.e("AR: ACTIVITY_RECOGNITION permission NOT granted - cannot start updates")
            return
        }

        if (activityRecognitionClient == null) {
            activityRecognitionClient = ActivityRecognition.getClient(this)
        }

        val pendingIntent = createActivityPendingIntent()
        activityUpdatePendingIntent = pendingIntent

        Timber.i("AR: Requesting activity updates with interval=${MonitoringConstants.ACTIVITY_UPDATE_INTERVAL_MS}ms")
        try {
            activityRecognitionClient
                ?.requestActivityUpdates(MonitoringConstants.ACTIVITY_UPDATE_INTERVAL_MS, pendingIntent)
                ?.addOnSuccessListener {
                    activityUpdatesRegistered = true
                    Timber.i("AR: Activity Recognition updates ENABLED successfully")
                }
                ?.addOnFailureListener { e ->
                    Timber.e("AR: FAILED to request activity updates: ${e.message}", e)
                }
        } catch (e: SecurityException) {
            Timber.e("AR: SecurityException - missing ACTIVITY_RECOGNITION permission: ${e.message}")
        }
    }

    private fun stopActivityRecognitionUpdates() {
        val pendingIntent = activityUpdatePendingIntent ?: return
        activityRecognitionClient?.removeActivityUpdates(pendingIntent)
            ?.addOnSuccessListener {
                Timber.i("Activity Recognition updates removed")
                activityRecognitionClient = null
            }
        activityUpdatesRegistered = false
        activityUpdatePendingIntent = null
    }

    private fun updateActivityRecognitionState(enabled: Boolean) {
        val shouldRun = enabled && !isPausedDueToWearState
        if (shouldRun) {
            startActivityRecognitionUpdates()
        } else {
            if (enabled && isPausedDueToWearState) {
                Timber.i("Activity Recognition updates suppressed (monitoring paused)")
            }
            stopActivityRecognitionUpdates()
        }
    }

    private fun handleActivityUpdate(intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val activity = result.mostProbableActivity

        if (::monitoringEngine.isInitialized) {
            monitoringEngine.updateActivity(activity.type, activity.confidence, System.currentTimeMillis())
        }

        val confidenceBucket = getConfidenceBucket(activity.confidence)
        if (activity.type != lastLoggedActivityType || confidenceBucket != lastLoggedActivityConfidenceBucket) {
            Timber.d("Activity update: ${getActivityName(activity.type)} (${activity.confidence}%)")
            lastLoggedActivityType = activity.type
            lastLoggedActivityConfidenceBucket = confidenceBucket
        }

        // Duty-cycle gyroscope based on activity: downgrade to 10Hz when STILL (high confidence)
        updateGyroDutyCycle(activity.type, activity.confidence)
    }

    /**
     * Adjust gyroscope sampling rate based on detected activity.
     * When user is STILL with high confidence, switch to 10Hz to save power.
     * When movement resumes, switch back to 50Hz for accurate FFT analysis.
     */
    private fun updateGyroDutyCycle(activityType: Int, confidence: Int) {
        val shouldUseLowPower = activityType == DetectedActivity.STILL &&
            confidence >= MonitoringConstants.SENSOR_DOWNGRADE_CONFIDENCE_THRESHOLD

        if (shouldUseLowPower == isGyroInLowPowerMode) return // No change needed
        if (isPausedDueToWearState) return // Sensors are paused; don't re-register

        val gyro = gyroscope ?: return
        if (!::sensorManager.isInitialized) return

        try {
            // Unregister and re-register gyroscope at new rate
            sensorManager.unregisterListener(monitoringEngine, gyro)

            val newRate = if (shouldUseLowPower) {
                MonitoringConstants.GYRO_STILL_SAMPLE_INTERVAL_US
            } else {
                MonitoringConstants.GYRO_SAMPLE_INTERVAL_US
            }

            val result = sensorManager.registerListener(
                monitoringEngine, gyro,
                newRate,
                MonitoringConstants.GYRO_MAX_REPORT_LATENCY_US
            )

            isGyroInLowPowerMode = shouldUseLowPower
            val rateHz = 1_000_000 / newRate
            Timber.i("Gyroscope duty-cycle: ${if (shouldUseLowPower) "LOW POWER" else "FULL"} (${rateHz}Hz, registered=$result)")
        } catch (e: Exception) {
            Timber.e("Failed to update gyro duty cycle: ${e.message}", e)
        }
    }

    private fun getConfidenceBucket(confidence: Int): Int = when {
        confidence >= 80 -> 3
        confidence >= 60 -> 2
        confidence >= 40 -> 1
        else -> 0
    }

    private fun getActivityName(type: Int): String = when (type) {
        DetectedActivity.STILL -> "still"
        DetectedActivity.WALKING -> "walking"
        DetectedActivity.RUNNING -> "running"
        DetectedActivity.ON_BICYCLE -> "on_bicycle"
        DetectedActivity.IN_VEHICLE -> "in_vehicle"
        DetectedActivity.TILTING -> "tilting"
        DetectedActivity.ON_FOOT -> "on_foot"
        DetectedActivity.UNKNOWN -> "unknown"
        else -> "unknown"
    }

    // Note: onBind() is not overridden - LifecycleService provides default implementation
    // This is a started service, not a bound service
}
