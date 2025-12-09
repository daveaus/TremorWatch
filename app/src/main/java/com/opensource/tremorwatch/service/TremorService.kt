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
import timber.log.Timber
import androidx.core.app.NotificationCompat
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
import com.opensource.tremorwatch.receivers.ServiceWatchdogReceiver
import com.opensource.tremorwatch.receivers.UploadAlarmReceiver
import com.opensource.tremorwatch.receivers.BatchRetryAlarmReceiver
import com.opensource.tremorwatch.engine.TremorMonitoringEngine
import com.opensource.tremorwatch.constants.MonitoringConstants
import com.opensource.tremorwatch.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt


/**
 * TremorService - Lifecycle-aware foreground service for continuous tremor monitoring.
 * 
 * Uses LifecycleService to enable lifecycle-aware components and better state management.
 * The service coordinates sensor monitoring, data collection, and communication with the phone.
 */
class TremorService : LifecycleService(), SensorEventListener {

    companion object {
        // TAG removed - Timber uses class name automatically
    }

    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

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

        // Config listener - receives detection algorithm config from phone
        private lateinit var configListener: ConfigDataListener

        // Preferences repository for state management
        private lateinit var preferencesRepository: PreferencesRepository
        private val serviceScope = CoroutineScope(Dispatchers.IO)

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
            batteryOptMonitorHandler.postDelayed(this, 60000L) // Check every 60 seconds
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
    private var pendingBatchCount = 0
    private var isUploadInProgress = false


    // ====================== LOCAL PERSISTENCE ======================

    /**
     * Convert engine TremorData to shared TremorData format for transmission to phone.
     */
    private fun convertToSharedFormat(data: TremorMonitoringEngine.TremorData): com.opensource.tremorwatch.shared.models.TremorData {
        // Phase 5: Use clinically calculated severity from engine (opus45)
        // Falls back to simple calculation if severity not set
        val severity = if (data.severity > 0f) {
            data.severity
        } else {
            data.magnitude * data.confidence  // Legacy fallback
        }

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
            "isRestingState" to data.isRestingState
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
            if (pendingBatchCount >= MonitoringConstants.MAX_PENDING_BATCHES) {
                // Only scan directory when we need to delete old files
                val pendingFiles = getPendingBatchFiles()
                Timber.w("Max pending batches reached (${pendingFiles.size}), deleting oldest")
                if (pendingFiles.isNotEmpty()) {
                    pendingFiles.first().delete()
                    pendingBatchCount = (pendingFiles.size - 1).coerceAtLeast(0)
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
            pendingBatchCount++ // Increment cached count instead of rescanning directory
            Timber.i("Saved batch locally: $filename (${batch.size} samples, $pendingBatchCount pending)")
            Timber.d("TremorWatch: Saved batch locally - $pendingBatchCount pending batches")

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

    private fun retryFailedUploads(forceUpload: Boolean = false) {
        // Check if upload to phone is enabled
        if (!DataConfig.isUploadToPhoneEnabled(this)) {
            Timber.d("Upload to phone is disabled - skipping upload")
            return
        }

        // Prevent concurrent upload operations
        if (isUploadInProgress) {
            Timber.d("Upload already in progress - skipping duplicate request")
            return
        }

        // Run file scan in background thread to avoid blocking UI
        Thread {
            val pendingFiles = getPendingBatchFiles()
            if (pendingFiles.isEmpty()) {
                Timber.d("No pending batches to send")
                return@Thread
            }

            if (forceUpload) {
                Timber.i("Manual upload: Found ${pendingFiles.size} pending batch(es) to send to phone")
            } else {
                Timber.i("Automatic upload: Found ${pendingFiles.size} pending batch(es) to send to phone")
            }

            // Mark upload as in progress
            isUploadInProgress = true
            var completedBatches = 0
            val totalBatches = pendingFiles.size

            // Send batches to phone via Data Layer API using the queue worker
            // Fixed: Now properly uses sendBatch() which feeds the queue worker instead of bypassing it
                // Process in smaller chunks to avoid blocking and ANRs
                val filesToProcess = pendingFiles.take(MonitoringConstants.MAX_BATCHES_PER_UPLOAD)
                val remainingBatches = maxOf(0, pendingFiles.size - MonitoringConstants.MAX_BATCHES_PER_UPLOAD)

            filesToProcess.forEachIndexed { index, file ->
                try {
                    // Add small delay between file reads to avoid overwhelming the system
                    if (index > 0 && index % 5 == 0) {
                        Thread.sleep(50) // Small delay every 5 files
                    }
                    
                    // Read and parse the batch from file
                    // Use bufferedReader for better performance on large files
                    val jsonContent = try {
                        file.bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        Timber.e("Failed to read file ${file.name}: ${e.message}", e)
                        completedBatches++
                        batchesFailed++
                        if (completedBatches >= filesToProcess.size) {
                            isUploadInProgress = false
                        }
                        return@forEachIndexed
                    }
                    val batch = TremorBatch.fromJsonString(jsonContent)

                    // Use sendBatch() instead of sendBatchFromFile() to properly utilize queue worker
                    phoneCommunication.sendBatch(batch) { success ->
                        if (success) {
                            // Delete file after successful transmission to phone
                            if (file.exists()) {
                                file.delete()
                                pendingBatchCount-- // Decrement cached count instead of rescanning
                                batchesSent++
                                lastSuccessfulUploadTime = System.currentTimeMillis()
                                Timber.i("SUCCESS: Sent batch ${file.name} via queue worker, deleted. $pendingBatchCount pending")
                            }
                        } else {
                            batchesFailed++
                            Timber.w("FAILED: Queue worker failed to send batch ${file.name} - will retry later. $pendingBatchCount pending")
                        }

                        // Track completion and clear flag when all batches processed
                        completedBatches++
                        if (completedBatches >= filesToProcess.size) {
                            isUploadInProgress = false
                                if (remainingBatches > 0) {
                                    Timber.i("BATCH COMPLETE: Processed ${MonitoringConstants.MAX_BATCHES_PER_UPLOAD} of $totalBatches. Scheduling next cycle for remaining $remainingBatches batches...")
                                    // Schedule next upload cycle after a short delay to allow data layer to catch up
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        retryFailedUploads(forceUpload = forceUpload)
                                    }, 2000)
                                } else {
                                    Timber.i("COMPLETE: Upload batch processing finished: $completedBatches/$totalBatches processed")
                                }
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    // CRITICAL: OutOfMemoryError - stop immediately and log
                    Timber.e("CRITICAL: OutOfMemoryError during batch upload - stopping immediately. Already processed: $completedBatches/$filesToProcess.size", e)
                    isUploadInProgress = false
                    batchesFailed++
                    return@Thread
                } catch (e: Exception) {
                    Timber.e("ERROR: Failed to read/parse batch file ${file.name}: ${e.message}", e)
                    // Skip this file and continue with others
                    completedBatches++
                    batchesFailed++
                    if (completedBatches >= filesToProcess.size) {
                        isUploadInProgress = false
                            if (remainingBatches > 0) {
                                Timber.i("BATCH COMPLETE: Processed ${MonitoringConstants.MAX_BATCHES_PER_UPLOAD} of $totalBatches. Scheduling next cycle for remaining $remainingBatches batches...")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    retryFailedUploads(forceUpload = forceUpload)
                                }, 2000)
                            } else {
                                Timber.i("COMPLETE: Upload batch processing finished: $completedBatches/$totalBatches processed")
                            }
                    }
                }
            }
        }.start()
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
            Timber.i("★★★ Starting battery optimization monitor - checking every 60s")
            batteryOptMonitorHandler.post(batteryOptMonitorRunnable)
        }

    /**
     * Verify wakelock is still held and re-acquire if Samsung FreecessController disabled it.
     * Battery-optimized: Only refresh if actually disrupted, not on every check.
     * This fights Samsung's aggressive battery optimization that bypasses standard Android protections.
     * Also detects if app has been re-added to Samsung's sleeping apps list.
     */
    private fun verifyAndRenewWakeLock() {
        val now = System.currentTimeMillis()

        wakeLock?.let { wl ->
            val isHeld = wl.isHeld
            if (!isHeld) {
                // Samsung FreecessController disabled our wakelock!
                Timber.e("★★★ CRITICAL: WakeLock was DISABLED by system (Samsung FreecessController) - RE-ACQUIRING NOW!")

                // Track disruption for sleeping apps detection
                trackWakeLockDisruption(now)

                try {
                    wl.acquire()
                    Timber.w("★★★ WakeLock successfully RE-ACQUIRED - fighting Samsung freeze")
                } catch (e: Exception) {
                    Timber.e("★★★ FAILED to re-acquire wakelock: ${e.message}", e)
                }
            } else {
                // Wakelock still held - no action needed (battery optimized)
                // Only refresh if we detect it's about to expire or has been held too long
                // Since we use PARTIAL_WAKE_LOCK without timeout, it should stay held
                Timber.v("WakeLock still held - no refresh needed (battery optimized)")
            }
        } ?: run {
            Timber.e("★★★ CRITICAL: WakeLock is NULL - this should never happen!")
            // Re-acquire if null (shouldn't happen but safety check)
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "TremorWatch::SensorWakeLock"
                ).apply {
                    acquire()
                    Timber.w("WakeLock recreated and acquired")
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

            // Initialize preferences repository
            preferencesRepository = PreferencesRepository(this)
            
            // Initialize watch-to-phone communication
            phoneCommunication = WatchDataSenderCommunication(this)
        
        // Start the batch send queue worker to serialize sends and prevent Data Layer congestion
        Timber.i("INIT: TremorService v3.3.0 - About to call WatchDataSender.startQueueWorker()")
        WatchDataSender.startQueueWorker()
        Timber.i("COMPLETE: TremorService v3.3.0 - Batch send queue worker initialization completed")

        // Initialize monitoring engine
        monitoringEngine = TremorMonitoringEngine(
            onBatchReady = { batch ->
                // Called when engine has collected a full batch
                saveBatchLocally(batch)
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
        )

        // Initialize config listener to receive detection algorithm updates from phone
        configListener = ConfigDataListener(this) { newConfig ->
            Timber.i("Received config update from phone: ${newConfig.profileName}")
            monitoringEngine.setConfig(newConfig)
        }
        configListener.register()

        // Clean up old local storage files based on retention period (run in background to avoid blocking onCreate)
        Thread {
            cleanupOldLocalStorage()
        }.start()

        // Acquire wake lock to keep sensors active (CRITICAL for Samsung devices)
        // Samsung FreecessController tries to freeze the app even with foreground service
        // Use wakelock strategy: PARTIAL_WAKE_LOCK + ON_AFTER_RELEASE
        // Battery-optimized: Acquire without timeout, only refresh if disrupted by system
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "TremorWatch::SensorWakeLock"
        ).apply {
            // Acquire WITHOUT timeout - will stay held until manually released
            // Battery-optimized: Only refresh if system disrupts it (Samsung FreecessController)
            acquire()
            Timber.w("★★★ WakeLock acquired (PARTIAL_WAKE_LOCK | ON_AFTER_RELEASE) - battery optimized")
        }

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
        gyroscope?.let {
            try {
                // SENSOR_DELAY_GAME (~50Hz) for FFT analysis, samples saved at 1Hz
                val sensorDelay = SensorManager.SENSOR_DELAY_GAME
                val result = sensorManager.registerListener(monitoringEngine, it, sensorDelay)
                Timber.d("Gyroscope registration result: $result")
                Timber.d("TremorWatch: Gyroscope registered at GAME (~50Hz) rate")
            } catch (e: Exception) {
                Timber.e("ERROR: Failed to register gyroscope: ${e.message}", e)
                Timber.e("TremorWatch: ERROR: Failed to register gyroscope: ${e.message}")
                e.printStackTrace()
            }
        }
            accelerometer?.let {
                try {
                    // Use SENSOR_DELAY_NORMAL (~10Hz) for battery optimization
                    val result = sensorManager.registerListener(monitoringEngine, it, SensorManager.SENSOR_DELAY_NORMAL)
                    Timber.d("Linear acceleration sensor registration result: $result")
                    Timber.d("TremorWatch: Linear acceleration sensor registered at NORMAL rate (battery optimized)")
                } catch (e: Exception) {
                    Timber.e("ERROR: Failed to register linear acceleration sensor: ${e.message}", e)
                    Timber.e("TremorWatch: ERROR: Failed to register linear acceleration sensor: ${e.message}")
                    e.printStackTrace()
                }
            }
            offBodySensor?.let {
                try {
                    val result = sensorManager.registerListener(monitoringEngine, it, SensorManager.SENSOR_DELAY_NORMAL)
                    Timber.d("Off-body sensor registration result: $result")
                    Timber.d("TremorWatch: Off-body sensor registered for wear detection")
                } catch (e: Exception) {
                    Timber.e("ERROR: Failed to register off-body sensor: ${e.message}", e)
                    Timber.e("TremorWatch: ERROR: Failed to register off-body sensor: ${e.message}")
                    e.printStackTrace()
                }
            }

        // Update initial pending batch count and trigger send of pending batches on startup
        // CRITICAL: This clears the queue of accumulated batches when the app/service restarts
        Thread {
            val fileCount = getPendingBatchFiles().size
            pendingBatchCount = fileCount

            if (pendingBatchCount > 0) {
                Timber.i("Service started with $pendingBatchCount pending batch(es) - SENDING NOW via retryFailedUploads")
                
                // Immediately send all pending batches using retryFailedUploads
                // This uses the correct directory (filesDir with tremor_batch_*.json files)
                // and properly processes them through the queue worker
                retryFailedUploads(forceUpload = false)
                // Note: retryFailedUploads handles success/failure tracking internally
                return@Thread
            }
            
            // Old code block removed - keeping for reference
            if (false) {
                val dataSender = WatchDataSender(this@TremorService)
                dataSender.sendPendingBatches { successCount, failureCount ->
                    Timber.i("Pending batch send complete: $successCount sent, $failureCount failed")
                    if (successCount > 0) {
                        pendingBatchCount -= successCount
                        batchesSent += successCount
                        lastSuccessfulUploadTime = System.currentTimeMillis()
                    }
                    if (failureCount > 0) {
                        batchesFailed += failureCount
                    }
                }
            }
        }.start()

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
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, BatchRetryAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            2,  // Different request code from watchdog and upload
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule batch retry every hour (same as upload interval) - battery optimized
        // Only retry during upload window to avoid unnecessary wake-ups
        val intervalMinutes = MonitoringState.getUploadIntervalMinutes(this)
        val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMinutes * 60 * 1000L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
        Timber.d("Batch retry alarm scheduled (retry every $intervalMinutes minutes - battery optimized)")
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
                        // Run upload in background thread to avoid blocking broadcast receiver
                        Thread {
                            retryFailedUploads(forceUpload = isManual)
                            cleanupOldLocalStorage()
                        }.start()
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
        val reason = when {
            !isWatchWorn && isCharging -> "not worn and charging"
            !isWatchWorn -> "not worn"
            isCharging -> "charging"
            else -> "unknown"
        }
        Timber.w("★★★ MONITORING PAUSED: $reason ★★★")
        Timber.w("TremorWatch: ★★★ Monitoring paused: $reason (data collection stopped)")
        
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
        Timber.i("★★★ MONITORING RESUMED: worn=${isWatchWorn}, charging=${isCharging} ★★★")
        Timber.i("TremorWatch: ★★★ Monitoring resumed: worn=${isWatchWorn}, charging=${isCharging} (data collection active)")
        
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

                // Re-register gyroscope
                gyroscope?.let {
                    try {
                        val sensorDelay = SensorManager.SENSOR_DELAY_GAME  // ~50 Hz for standard mode
                        val result = sensorManager.registerListener(monitoringEngine, it, sensorDelay)
                        Timber.d("Gyroscope re-registered: $result")
                        Timber.d("TremorWatch: Gyroscope re-registered after freeze")
                    } catch (e: Exception) {
                        Timber.e("Failed to re-register gyroscope: ${e.message}", e)
                        Timber.e("TremorWatch: ERROR: Failed to re-register gyroscope: ${e.message}")
                    }
                }

                // Re-register accelerometer
                accelerometer?.let {
                    try {
                        val result = sensorManager.registerListener(monitoringEngine, it, SensorManager.SENSOR_DELAY_NORMAL)
                        Timber.d("Linear acceleration re-registered: $result")
                        Timber.d("TremorWatch: Linear acceleration re-registered after freeze at NORMAL rate (battery optimized)")
                    } catch (e: Exception) {
                        Timber.e("Failed to re-register accelerometer: ${e.message}", e)
                        Timber.e("TremorWatch: ERROR: Failed to re-register accelerometer: ${e.message}")
                    }
                }

                // Re-register off-body sensor
                offBodySensor?.let {
                    try {
                        val result = sensorManager.registerListener(monitoringEngine, it, SensorManager.SENSOR_DELAY_NORMAL)
                        Timber.d("Off-body sensor re-registered: $result")
                        Timber.d("TremorWatch: Off-body sensor re-registered after freeze")
                    } catch (e: Exception) {
                        Timber.e("Failed to re-register off-body sensor: ${e.message}", e)
                        Timber.e("TremorWatch: ERROR: Failed to re-register off-body sensor: ${e.message}")
                    }
                }
        }

        // Renew wake lock (backup to the 10-minute wakelock monitor)
        // Battery optimized: Less frequent renewal (watchdog now runs every 30 minutes)
        wakeLock?.let {
            if (!it.isHeld) {
                // Only re-acquire if it was released (battery optimization)
                it.acquire()
                Timber.d("Wake lock re-acquired by watchdog")
            } else {
                Timber.d("Wake lock still held - no renewal needed (battery optimized)")
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

            // 2. Cancel watchdog alarm immediately
            cancelWatchdogAlarm()

            // 2b. Cancel upload alarm
            cancelUploadAlarm()

            // 2c. Cancel batch retry alarm
            cancelBatchRetryAlarm()

            // 3. Unregister sensors (fast operation)
            if (::sensorManager.isInitialized && ::monitoringEngine.isInitialized) {
                sensorManager.unregisterListener(monitoringEngine)
                Timber.d("Sensors unregistered")
            }
            
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

            // 4. Cancel periodic status updates, heartbeats, and wakelock monitor
            statusUpdateHandler.removeCallbacks(statusUpdateRunnable)
            heartbeatHandler.removeCallbacks(heartbeatRunnable)
            wakeLockMonitorHandler.removeCallbacks(wakeLockMonitorRunnable)
            batteryOptMonitorHandler.removeCallbacks(batteryOptMonitorRunnable)
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
    
    // Note: onBind() is not overridden - LifecycleService provides default implementation
    // This is a started service, not a bound service
}
