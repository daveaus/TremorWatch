package com.opensource.tremorwatch.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages file-based calibration data capture to avoid memory overflow.
 * 
 * Streams sensor and FFT data directly to a file instead of buffering in memory.
 * This approach handles long calibration durations (up to 120s at 50Hz = 6000+ samples)
 * without risking OOM errors on memory-constrained watch devices.
 */
class CalibrationCaptureManager(private val context: Context) {
    
    companion object {
        private const val CALIBRATION_DIR = "calibration"
        private const val MAX_DAILY_CALIBRATIONS = 5
        private const val DAILY_COUNT_PREFS = "calibration_prefs"
        private const val KEY_DAILY_COUNT = "daily_count"
        private const val KEY_DAILY_DATE = "daily_date"
    }
    
    private val json = Json { prettyPrint = false }
    
    private var isCapturing = AtomicBoolean(false)
    private var currentRatingId: String? = null
    private var captureEndTime: Long = 0
    private var captureJob: Job? = null
    private var currentWriter: BufferedWriter? = null
    private var currentFile: File? = null
    private val sampleCount = AtomicInteger(0)
    
    // Callbacks for UI updates
    var onCaptureProgress: ((Int, Int, Float) -> Unit)? = null  // (samplesCollected, secondsRemaining, progress)
    var onCaptureComplete: ((File, Int) -> Unit)? = null  // (outputFile, sampleCount)
    var onCaptureError: ((String) -> Unit)? = null
    
    /**
     * Check if within daily calibration limit
     */
    fun canStartCalibration(): Boolean {
        val prefs = context.getSharedPreferences(DAILY_COUNT_PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storedDate = prefs.getString(KEY_DAILY_DATE, null)
        
        val dailyCount = if (storedDate == today) {
            prefs.getInt(KEY_DAILY_COUNT, 0)
        } else {
            // New day, reset count
            prefs.edit()
                .putString(KEY_DAILY_DATE, today)
                .putInt(KEY_DAILY_COUNT, 0)
                .apply()
            0
        }
        
        return dailyCount < MAX_DAILY_CALIBRATIONS
    }
    
    /**
     * Get remaining calibrations today
     */
    fun getRemainingCalibrationsToday(): Int {
        val prefs = context.getSharedPreferences(DAILY_COUNT_PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storedDate = prefs.getString(KEY_DAILY_DATE, null)
        
        val dailyCount = if (storedDate == today) {
            prefs.getInt(KEY_DAILY_COUNT, 0)
        } else {
            0
        }
        
        return MAX_DAILY_CALIBRATIONS - dailyCount
    }
    
    /**
     * Start calibration data capture for the given rating.
     * 
     * @param ratingId UUID of the associated rating
     * @param durationSeconds How long to capture (30-120s)
     * @return true if capture started, false if already capturing or limit reached
     */
    fun startCapture(ratingId: String, durationSeconds: Int): Boolean {
        if (isCapturing.get()) {
            Timber.w("Already capturing calibration data")
            return false
        }
        
        if (!canStartCalibration()) {
            Timber.w("Daily calibration limit reached")
            onCaptureError?.invoke("Daily calibration limit reached")
            return false
        }
        
        currentRatingId = ratingId
        captureEndTime = System.currentTimeMillis() + (durationSeconds * 1000L)
        sampleCount.set(0)
        
        // Create output file
        val calibrationDir = File(context.filesDir, CALIBRATION_DIR)
        calibrationDir.mkdirs()
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentFile = File(calibrationDir, "calibration_${ratingId}_$timestamp.jsonl")
        
        try {
            currentWriter = BufferedWriter(FileWriter(currentFile))
            
            // Write header with metadata
            val header = CalibrationHeader(
                ratingId = ratingId,
                startTime = System.currentTimeMillis(),
                durationSeconds = durationSeconds,
                schemaVersion = 1
            )
            currentWriter?.write(json.encodeToString(header))
            currentWriter?.newLine()
            
            isCapturing.set(true)
            Timber.i("Started calibration capture for rating $ratingId (${durationSeconds}s)")
            
            // Increment daily count
            incrementDailyCount()
            
            // Start progress monitoring
            startProgressMonitor(durationSeconds)
            
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start calibration capture")
            onCaptureError?.invoke("Failed to start capture: ${e.message}")
            cleanup()
            return false
        }
    }
    
    /**
     * Record a single calibration sample.
     * Call this from the sensor processing loop.
     */
    fun recordSample(sample: CalibrationSample) {
        if (!isCapturing.get()) return
        
        // Check if capture time is complete
        if (System.currentTimeMillis() >= captureEndTime) {
            stopCapture()
            return
        }
        
        try {
            val line = json.encodeToString(sample)
            synchronized(this) {
                currentWriter?.write(line)
                currentWriter?.newLine()
                currentWriter?.flush()  // Flush after each sample for reliability
            }
            sampleCount.incrementAndGet()
        } catch (e: Exception) {
            Timber.e(e, "Failed to write calibration sample")
        }
    }
    
    /**
     * Stop capture early (user cancelled)
     */
    fun cancelCapture() {
        if (!isCapturing.get()) return
        
        Timber.i("Calibration capture cancelled by user")
        cleanup()
        
        // Delete partial file
        currentFile?.delete()
        currentFile = null
    }
    
    /**
     * Stop capture when duration complete
     */
    private fun stopCapture() {
        if (!isCapturing.compareAndSet(true, false)) return
        
        captureJob?.cancel()
        
        val file = currentFile
        val count = sampleCount.get()
        
        try {
            // Write footer with summary
            val footer = CalibrationFooter(
                endTime = System.currentTimeMillis(),
                totalSamples = count
            )
            currentWriter?.write(json.encodeToString(footer))
            currentWriter?.newLine()
            currentWriter?.flush()
            currentWriter?.close()
            
            Timber.i("Calibration capture complete: $count samples saved to ${file?.absolutePath}")
            
            if (file != null && file.exists()) {
                onCaptureComplete?.invoke(file, count)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error finishing calibration capture")
            onCaptureError?.invoke("Error saving calibration data: ${e.message}")
        } finally {
            cleanup()
        }
    }
    
    private fun cleanup() {
        isCapturing.set(false)
        captureJob?.cancel()
        captureJob = null
        try {
            currentWriter?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        currentWriter = null
    }
    
    private fun incrementDailyCount() {
        val prefs = context.getSharedPreferences(DAILY_COUNT_PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storedDate = prefs.getString(KEY_DAILY_DATE, null)
        
        val currentCount = if (storedDate == today) {
            prefs.getInt(KEY_DAILY_COUNT, 0)
        } else {
            0
        }
        
        prefs.edit()
            .putString(KEY_DAILY_DATE, today)
            .putInt(KEY_DAILY_COUNT, currentCount + 1)
            .apply()
    }
    
    private fun startProgressMonitor(totalDurationSeconds: Int) {
        captureJob = CoroutineScope(Dispatchers.Main).launch {
            val startTime = System.currentTimeMillis()
            val totalDurationMs = totalDurationSeconds * 1000L
            
            while (isCapturing.get()) {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = ((totalDurationMs - elapsed) / 1000).toInt().coerceAtLeast(0)
                val progress = (elapsed.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                
                onCaptureProgress?.invoke(sampleCount.get(), remaining, progress)
                
                if (remaining <= 0) {
                    break
                }
                
                delay(500)  // Update every 500ms
            }
        }
    }
    
    /**
     * Check if currently capturing
     */
    fun isCapturing(): Boolean = isCapturing.get()
    
    /**
     * Get all calibration files for a specific rating
     */
    fun getCalibrationFilesForRating(ratingId: String): List<File> {
        val calibrationDir = File(context.filesDir, CALIBRATION_DIR)
        return calibrationDir.listFiles()
            ?.filter { it.name.contains(ratingId) }
            ?: emptyList()
    }
    
    /**
     * Delete old calibration files (older than 7 days)
     */
    fun cleanupOldFiles(maxAgeDays: Int = 7) {
        val calibrationDir = File(context.filesDir, CALIBRATION_DIR)
        val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        
        calibrationDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
                Timber.d("Deleted old calibration file: ${file.name}")
            }
        }
    }
}

/**
 * Header written at start of calibration file
 */
@Serializable
data class CalibrationHeader(
    val type: String = "header",
    val ratingId: String,
    val startTime: Long,
    val durationSeconds: Int,
    val schemaVersion: Int
)

/**
 * Footer written at end of calibration file
 */
@Serializable
data class CalibrationFooter(
    val type: String = "footer",
    val endTime: Long,
    val totalSamples: Int
)

/**
 * Individual calibration sample (sensor + FFT data)
 */
@Serializable
data class CalibrationSample(
    val type: String = "sample",
    val timestamp: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float,
    val dominantFrequency: Float,
    val tremorBandPower: Float,
    val totalPower: Float,
    val bandRatio: Float,
    val peakProminence: Float,
    val confidence: Float,
    val severity: Double,
    val isWorn: Boolean,
    val isCharging: Boolean
)
