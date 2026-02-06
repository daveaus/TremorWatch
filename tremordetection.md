**Summary**
- Detection runs on the watch in `TremorMonitoringEngine`, processing gyroscope and linear-acceleration streams, maintaining FFT windows at ~50 Hz while persisting samples at 1 Hz.
- `TremorFFT` computes tremor-band power, dominant frequency, and confidence, then classifies tremor by thresholds on power, frequency, band ratio, and confidence.
- The engine fuses gyro/accel results, applies post-filters (severity floor, dynamic band ratio, frequency floor, high-energy rejection), and uses temporal smoothing to confirm episodes and bridge brief gaps.
- Personalized baseline adaptation (`BaselineManager`) can adjust thresholds and boost confidence; severity is computed by `SeverityCalculator` and tremor type by `TremorClassifier`.
- Detection configuration is defined in `TremorDetectionConfig`, delivered to the watch via `ConfigDataListener`, and uses constants from `MonitoringConstants` (plus `BatteryOptimizedConstants`).
- Service wiring (`TremorService`) registers sensors, drives `TremorMonitoringEngine`, and batches/saves detected samples; UI hooks (`MainActivity`) expose calibration and baseline flow; phone config (`TremorConfigManager`) manages profile sync to watch.

**Included Files**
- `app\src\main\java\com\opensource\tremorwatch\engine\TremorMonitoringEngine.kt`
- `app\src\main\java\com\opensource\tremorwatch\TremorFFT.kt`
- `app\src\main\java\com\opensource\tremorwatch\engine\BaselineManager.kt`
- `app\src\main\java\com\opensource\tremorwatch\engine\SeverityCalculator.kt`
- `app\src\main\java\com\opensource\tremorwatch\engine\TremorClassifier.kt`
- `shared\src\main\java\com\opensource\tremorwatch\shared\models\TremorDetectionConfig.kt`
- `app\src\main\java\com\opensource\tremorwatch\constants\MonitoringConstants.kt`
- `app\src\main\java\com\opensource\tremorwatch\constants\BatteryOptimizedConstants.kt`
- `app\src\main\java\com\opensource\tremorwatch\config\ConfigDataListener.kt`
- `shared\src\main\java\com\opensource\tremorwatch\shared\models\TremorData.kt`
- `app\src\main\java\com\opensource\tremorwatch\service\TremorService.kt`
- `app\src\main\java\com\opensource\tremorwatch\MainActivity.kt`
- `phone\src\main\java\com\opensource\tremorwatch\phone\config\TremorConfigManager.kt`

**File: app\src\main\java\com\opensource\tremorwatch\engine\TremorMonitoringEngine.kt**
```kotlin
package com.opensource.tremorwatch.engine

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import com.opensource.tremorwatch.TremorFFT
import com.opensource.tremorwatch.constants.MonitoringConstants
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

/**
 * Engine responsible for processing sensor data, detecting tremors, and managing data collection.
 * 
 * This class extracts the core monitoring logic from TremorService, making it:
 * - Testable without Android Service dependencies
 * - Reusable in different contexts
 * - Easier to maintain and understand
 * 
 * The engine processes sensor events and produces TremorData samples that can be
 * batched and persisted by the service layer.
 */
class TremorMonitoringEngine(
    private val onBatchReady: (List<TremorData>) -> Unit,
    private val onWearStateChanged: (Boolean) -> Unit
) : SensorEventListener {

    /**
     * Thread-safe configuration.
     * Marked @Volatile to ensure visibility across threads.
     */
    @Volatile
    private var config: TremorDetectionConfig = TremorDetectionConfig()

    /**
     * Update the detection configuration.
     * Propagates config to TremorFFT as well.
     * Thread-safe due to @Volatile and immutable TremorDetectionConfig.
     *
     * @param newConfig The new configuration to apply
     */
    fun setConfig(newConfig: TremorDetectionConfig) {
        config = newConfig
        tremorFFT.setConfig(newConfig)
        Timber.i("TremorMonitoringEngine config updated: ${config.profileName}")
    }

    companion object {
        // TAG removed - Timber uses class name automatically

        // Phase 4: Temporal smoothing constants (opus45 review)
        // Default values - now configurable via TremorDetectionConfig
        const val DEFAULT_MIN_EPISODE_DURATION_SAMPLES = 3  // Require 3+ consecutive samples to start episode
        const val DEFAULT_MAX_GAP_SAMPLES = 2               // Allow 2 non-tremor samples within episode
    }

    // Sensor data state
    private var startTimeSensorNs = 0L // Sensor timestamp (nanoseconds) at engine start
    private var lastSavedTimeSensorNs = 0L // Last saved sample timestamp (sensor nanoseconds)
    private var lastSampleTime = 0L // Wall clock time of last sensor event (for watchdog)
    
    // Accelerometer state
    private var lastAccelMagnitude = 0f
    private val accelWindow = mutableListOf<Float>()
    private val accelMagnitudeWindow = mutableListOf<Float>() // Buffer for accelerometer FFT analysis
    
    // FFT-based tremor analysis
    private val tremorFFT = TremorFFT(MonitoringConstants.FFT_SAMPLE_RATE)
    private val gyroMagnitudeWindow = mutableListOf<Float>() // Buffer for gyroscope FFT analysis
    private var lastGyroFFTResult: TremorFFT.FFTResult? = null
    private var lastAccelFFTResult: TremorFFT.FFTResult? = null
    private var fftProcessingCounter = 0  // Counter for FFT processing interval
    
    // Thread-safe buffer for sensor data
    private val dataBuffer = Collections.synchronizedList(mutableListOf<TremorData>())
    
    // Wear detection state
    private var isWatchWorn = true // Assume worn initially
    private var hasOffBodySensor = false
    private var offBodySensorEventReceived = false
    private var consecutiveOffBodyReadings = 0
    private var lastOffBodyTime = 0L
    
    // Wear state debouncing
    private val wearStateDebounceHandler = Handler(Looper.getMainLooper())
    private var pendingWearStateChange: Runnable? = null
    
    // External state (managed by service)
    private var isPausedDueToWearState = false
    private var isCharging = false
    
    // Phase 4: Temporal smoothing state for episode tracking (opus45 review)
    // Tracks consecutive tremor/non-tremor samples for noise filtering
    private var consecutiveTremorSamples = 0
    private var consecutiveNonTremorSamples = 0
    private var inTremorEpisode = false
    private var currentEpisodeStartTime = 0L
    private var currentEpisodeTremorCount = 0
    
    // Phase 5: Rolling baseline and severity calculation (opus45 review)
    // Note: BaselineManager requires context - not available without context parameter
    private val baselineManager: BaselineManager? = null
    
    /**
     * Data class representing a single tremor data sample.
     */
    data class TremorData(
        val timestamp: Long,
        val datetimeIso: String,
        val timeFormatted: String,
        val x: Float,  // Gyroscope X
        val y: Float,  // Gyroscope Y
        val z: Float,  // Gyroscope Z
        val magnitude: Float,
        val accelMagnitude: Float,
        val isTremor: Boolean,
        val confidence: Float,
        val isWorn: Boolean,
        val isCharging: Boolean,
        val dominantFrequency: Float = 0f,  // Hz - FFT dominant frequency in 4-12 Hz band
        val tremorBandPower: Float = 0f,     // Power spectral density in tremor band
        val totalPower: Float = 0f,          // Total power across all frequencies
        val bandRatio: Float = 0f,           // Ratio of tremor band power to total power
        val peakProminence: Float = 0f,      // Prominence of dominant frequency peak
        val severity: Float = 0f,            // Phase 5: Clinical severity score (0-10 scale)
        val baselineMultiplier: Float = 1f,  // Phase 5: How far above personal baseline
        // Phase 5b: Tremor type classification
        val tremorType: String = "unknown",          // Tremor type (resting, postural, essential, etc.)
        val tremorTypeConfidence: Float = 0f,        // Classification confidence (0-1)
        val isRestingState: Boolean = false          // Whether detected in resting state
    )
    
    /**
     * Update the pause state. When paused, sensor data collection is skipped.
     */
    fun setPaused(paused: Boolean) {
        isPausedDueToWearState = paused
    }
    
    /**
     * Update the charging state. This is included in data samples for context.
     */
    fun setCharging(charging: Boolean) {
        isCharging = charging
    }
    
    /**
     * Set whether the off-body sensor is available.
     */
    fun setOffBodySensorAvailable(available: Boolean) {
        hasOffBodySensor = available
        if (!available) {
            // If no sensor, assume always worn
            isWatchWorn = true
        }
    }
    
    /**
     * Get the last sample time (for watchdog monitoring).
     */
    fun getLastSampleTime(): Long = lastSampleTime
    
    /**
     * Get the current wear state.
     */
    fun isWatchWorn(): Boolean = isWatchWorn
    
    /**
     * Reset the engine state (useful for testing or restart scenarios).
     */
    fun reset() {
        synchronized(dataBuffer) {
            dataBuffer.clear()
        }
        gyroMagnitudeWindow.clear()
        accelWindow.clear()
        accelMagnitudeWindow.clear()
        lastGyroFFTResult = null
        lastAccelFFTResult = null
        startTimeSensorNs = 0L
        lastSavedTimeSensorNs = 0L
        lastSampleTime = 0L
        lastAccelMagnitude = 0f
        // Reset episode tracking state
        consecutiveTremorSamples = 0
        consecutiveNonTremorSamples = 0
        inTremorEpisode = false
        currentEpisodeStartTime = 0L
        currentEpisodeTremorCount = 0
    }
    
    /**
     * Check if currently in a tremor episode.
     * Useful for UI status display.
     */
    fun isInTremorEpisode(): Boolean = inTremorEpisode
    
    /**
     * Get current episode duration in milliseconds.
     * Returns 0 if not in episode.
     */
    fun getCurrentEpisodeDuration(): Long {
        return if (inTremorEpisode && currentEpisodeStartTime > 0) {
            System.currentTimeMillis() - currentEpisodeStartTime
        } else {
            0L
        }
    }
    
    /**
     * Phase 4: Apply temporal smoothing to reduce noise and detect sustained tremors.
     * (opus45 review recommendation)
     * 
     * This function:
     * 1. Requires config.minEpisodeDurationSamples consecutive detections to start an episode
     * 2. Allows config.maxGapSamples brief gaps within an ongoing episode
     * 3. Boosts confidence for sustained episodes
     * 
     * @param rawIsTremor The raw detection result from FFT analysis
     * @param rawConfidence The raw confidence from FFT analysis
     * @return Pair of (smoothedIsTremor, smoothedConfidence)
     */
    private fun applyTemporalSmoothing(rawIsTremor: Boolean, rawConfidence: Float): Pair<Boolean, Float> {
        if (rawIsTremor) {
            consecutiveTremorSamples++
            consecutiveNonTremorSamples = 0
            
            // Start episode after config.minEpisodeDurationSamples consecutive detections
            if (!inTremorEpisode && consecutiveTremorSamples >= config.minEpisodeDurationSamples) {
                inTremorEpisode = true
                currentEpisodeStartTime = System.currentTimeMillis()
                currentEpisodeTremorCount = consecutiveTremorSamples
                Timber.i("â˜… Tremor episode STARTED (${consecutiveTremorSamples} consecutive samples)")
            } else if (inTremorEpisode) {
                currentEpisodeTremorCount++
            }
        } else {
            consecutiveNonTremorSamples++
            
            // End episode only after config.maxGapSamples consecutive non-tremor
            if (inTremorEpisode && consecutiveNonTremorSamples > config.maxGapSamples) {
                val episodeDuration = System.currentTimeMillis() - currentEpisodeStartTime
                Timber.i("â˜… Tremor episode ENDED (duration: ${episodeDuration}ms, ${currentEpisodeTremorCount} tremor samples)")
                inTremorEpisode = false
                consecutiveTremorSamples = 0
                currentEpisodeStartTime = 0L
                currentEpisodeTremorCount = 0
            }
        }
        
        // If in episode, keep reporting tremor even for brief gaps (up to config.maxGapSamples)
        val smoothedIsTremor = if (inTremorEpisode) {
            // Within episode: report as tremor unless gap is too long
            consecutiveNonTremorSamples <= config.maxGapSamples || rawIsTremor
        } else {
            // Not in episode: require raw detection AND minimum consecutive samples
            rawIsTremor && consecutiveTremorSamples >= config.minEpisodeDurationSamples
        }
        
        // Boost confidence for sustained episodes
        val smoothedConfidence = when {
            inTremorEpisode && consecutiveTremorSamples >= config.minEpisodeDurationSamples * 2 -> {
                // Long-running episode - high confidence boost
                (rawConfidence * 1.3f).coerceIn(0f, 1f)
            }
            inTremorEpisode && consecutiveTremorSamples >= config.minEpisodeDurationSamples -> {
                // Confirmed episode - moderate boost
                (rawConfidence * 1.15f).coerceIn(0f, 1f)
            }
            !rawIsTremor && inTremorEpisode && consecutiveNonTremorSamples <= config.maxGapSamples -> {
                // Brief gap within episode - maintain previous confidence level
                rawConfidence
            }
            else -> rawConfidence
        }
        
        return Pair(smoothedIsTremor, smoothedConfidence)
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        
        // Use sensor timestamps (monotonic, not affected by wall clock changes)
        val sensorTimestampNs = event.timestamp
        
        // Track last sensor event time for watchdog freeze detection
        lastSampleTime = System.currentTimeMillis()
        
        // Initialize sensor start time on first event
        if (startTimeSensorNs == 0L) {
            startTimeSensorNs = sensorTimestampNs
        }
        
        when (event.sensor?.type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                handleOffBodySensorEvent(event, sensorTimestampNs)
            }
            
            Sensor.TYPE_GYROSCOPE -> {
                if (!isPausedDueToWearState) {
                    handleGyroscopeEvent(event, sensorTimestampNs)
                }
            }
            
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!isPausedDueToWearState) {
                    handleAccelerometerEvent(event)
                }
            }
        }
    }
    
    private fun handleOffBodySensorEvent(event: SensorEvent, sensorTimestampNs: Long) {
        // Mark that we've received at least one event from the sensor
        if (!offBodySensorEventReceived) {
            offBodySensorEventReceived = true
                Timber.i( "Off-body sensor is working - received first event")
        }
        
        // Off-body sensor interpretation:
        // 0.0 = off body (not worn), 1.0 = on body (worn)
        val sensorValue = event.values[0]
        val sensorSaysWorn = sensorValue.toInt() != 0
        
            Timber.d( "Off-body sensor: RAW=$sensorValue, sensorSaysWorn=$sensorSaysWorn, currentState=$isWatchWorn")
        
        if (sensorSaysWorn && !isWatchWorn) {
            // Watch put back on - respond immediately, cancel any pending pause
            pendingWearStateChange?.let {
                wearStateDebounceHandler.removeCallbacks(it)
                pendingWearStateChange = null
                Timber.d("Cancelled pending not-worn state change")
            }
            consecutiveOffBodyReadings = 0
            isWatchWorn = true
            lastOffBodyTime = 0L
            Timber.w("â˜…â˜…â˜… Wear state changed: WORN (immediate) â˜…â˜…â˜…")
            onWearStateChanged(true)
            
        } else if (!sensorSaysWorn && isWatchWorn) {
            // Watch taken off - require multiple consistent readings before debouncing
            consecutiveOffBodyReadings++
            
            if (consecutiveOffBodyReadings < MonitoringConstants.MIN_OFF_BODY_READINGS) {
                Timber.d("Off-body reading $consecutiveOffBodyReadings/${MonitoringConstants.MIN_OFF_BODY_READINGS} - waiting for consistency")
                return
            }
            
            // We have enough consistent readings, start debounce timer
            if (lastOffBodyTime == 0L) {
                lastOffBodyTime = System.currentTimeMillis()
            }
            
            // Only schedule state change if not already pending
            if (pendingWearStateChange == null) {
                Timber.d("Off-body confirmed ($consecutiveOffBodyReadings readings) - starting ${MonitoringConstants.WEAR_STATE_DEBOUNCE_MS}ms debounce timer")
                pendingWearStateChange = Runnable {
                    if (!isWatchWorn) {
                        // Already updated by a previous callback
                        return@Runnable
                    }
                    // Check if still off-body after debounce period
                    val timeSinceOffBody = System.currentTimeMillis() - lastOffBodyTime
                    if (timeSinceOffBody >= MonitoringConstants.WEAR_STATE_DEBOUNCE_MS - 500) { // Small tolerance
                        isWatchWorn = false
                        Timber.w("â˜…â˜…â˜… Wear state changed: NOT WORN (after ${timeSinceOffBody}ms debounce, $consecutiveOffBodyReadings readings) â˜…â˜…â˜…")
                        onWearStateChanged(false)
                    } else {
                        Timber.d("Debounce timer fired but state already changed back")
                    }
                    pendingWearStateChange = null
                }
                wearStateDebounceHandler.postDelayed(pendingWearStateChange!!, MonitoringConstants.WEAR_STATE_DEBOUNCE_MS)
            }
            
        } else if (sensorSaysWorn && isWatchWorn) {
            // Still worn - reset off-body tracking
            if (consecutiveOffBodyReadings > 0 || lastOffBodyTime > 0L) {
                Timber.d("Sensor confirms worn - resetting off-body tracking (was $consecutiveOffBodyReadings readings)")
                consecutiveOffBodyReadings = 0
                lastOffBodyTime = 0L
                pendingWearStateChange?.let {
                    wearStateDebounceHandler.removeCallbacks(it)
                    pendingWearStateChange = null
                }
            }
        }
    }
    
    private fun handleGyroscopeEvent(event: SensorEvent, sensorTimestampNs: Long) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        
        // Check sample interval using sensor timestamps (more accurate)
        val intervalNs = sensorTimestampNs - lastSavedTimeSensorNs
        val intervalMs = intervalNs / 1_000_000L
        
        // Handle timestamp rollback/reset (negative interval) - reset tracking
        if (intervalMs < 0) {
            Timber.w("Sensor timestamp went backward! Resetting timestamp tracking. (interval=${intervalMs}ms)")
            lastSavedTimeSensorNs = sensorTimestampNs
            startTimeSensorNs = sensorTimestampNs
            return
        }
        
        // CRITICAL: Fill FFT window at sensor rate (~50 Hz) for proper frequency detection
        // Tremor frequencies (4-12 Hz) require sampling at least 24 Hz (Nyquist: 2 * 12 Hz)
        // We use ~50 Hz sensor rate, which allows detection up to 25 Hz (fully covers all tremor ranges)
        // Add to FFT window buffer at sensor rate (every event)
        gyroMagnitudeWindow.add(magnitude)
        if (gyroMagnitudeWindow.size > MonitoringConstants.FFT_WINDOW_SIZE) {
            gyroMagnitudeWindow.removeAt(0)
        }

        // Perform FFT analysis on gyroscope data
        // Also analyze accelerometer if we have enough samples
        fftProcessingCounter++
        if (gyroMagnitudeWindow.size >= MonitoringConstants.FFT_WINDOW_SIZE &&
            fftProcessingCounter >= MonitoringConstants.FFT_PROCESSING_INTERVAL) {
            
            // Determine activity state from previous FFT result's total power
            // If no previous result, default to resting state (more conservative for resting tremor)
            val isResting = lastGyroFFTResult?.let { 
                it.totalPower < TremorFFT.RESTING_POWER_THRESHOLD 
            } ?: true  // Default to resting state if no previous result
            
            // Phase 5: Get adaptive thresholds from BaselineManager (if calibrated)
            val adaptiveThresholds = baselineManager?.let { manager ->
                if (manager.hasCompletedCalibration()) {
                    val thresholds = manager.getAdaptiveThresholds(isResting)
                    TremorFFT.AdaptiveThresholds(
                        severityFloor = thresholds.severityFloor,
                        minBandRatio = thresholds.minBandRatio,
                        confidenceThreshold = if (thresholds.isPersonalized) 0.30f else 0.35f, // Lower threshold for calibrated users
                        isPersonalized = thresholds.isPersonalized
                    )
                } else null
            }
            
            // Analyze gyroscope data with activity-aware frequency band
            // Resting: 4-6 Hz (resting tremor)
            // Active: 4-12 Hz (postural/kinetic tremor)
            // Uses adaptive thresholds if user has calibrated
            lastGyroFFTResult = tremorFFT.analyze(gyroMagnitudeWindow.toFloatArray(), isResting, adaptiveThresholds)
            
            // Analyze accelerometer data if we have enough samples (dual-sensor validation)
            // Use same activity state and adaptive thresholds for consistency
            if (accelMagnitudeWindow.size >= MonitoringConstants.FFT_WINDOW_SIZE) {
                lastAccelFFTResult = tremorFFT.analyze(accelMagnitudeWindow.toFloatArray(), isResting, adaptiveThresholds)
            }

            fftProcessingCounter = 0  // Reset counter
        }

        // Only save samples at configured rate (1 Hz standard)
        if (intervalMs < MonitoringConstants.SAMPLE_INTERVAL_MS) return
        lastSavedTimeSensorNs = sensorTimestampNs
        
        // Use wall clock for absolute timestamps (required for InfluxDB)
        val now = System.currentTimeMillis()
        val timeFormatted = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(now))
        val datetimeIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(now))
        
        // Get FFT-enhanced classification with dual-sensor validation
        val gyroFFTResult = lastGyroFFTResult
        val accelFFTResult = lastAccelFFTResult
        
        // Determine activity state from gyroscope FFT result (more accurate than preliminary estimate)
        val totalPower = gyroFFTResult?.totalPower ?: 0f
        val isResting = totalPower < TremorFFT.RESTING_POWER_THRESHOLD
        
        // Combine gyroscope and accelerometer results for dual-sensor validation
        val classification = when {
            // Both sensors agree on tremor detection - highest confidence
            gyroFFTResult != null && accelFFTResult != null && 
            gyroFFTResult.isTremor && accelFFTResult.isTremor -> {
                // Weighted combination: gyroscope 60% (angular velocity), accelerometer 40% (linear acceleration)
                val combinedConfidence = (gyroFFTResult.confidence * 0.6f + 
                                        accelFFTResult.confidence * 0.4f).coerceIn(0f, 1f)
                Pair(true, combinedConfidence)
            }
            // Only gyroscope detects tremor (accelerometer not available or doesn't agree)
            gyroFFTResult != null && gyroFFTResult.isTremor -> {
                // Use gyroscope result but reduce confidence if accelerometer is available but disagrees
                val confidence = if (accelFFTResult != null && !accelFFTResult.isTremor) {
                    // Accelerometer disagrees - reduce confidence
                    gyroFFTResult.confidence * 0.7f
                } else {
                    // Accelerometer not available - use gyroscope confidence
                    gyroFFTResult.confidence
                }
                Pair(true, confidence.coerceIn(0f, 1f))
            }
            // Fallback to threshold-based classification
            else -> classifyMovement(magnitude, lastAccelMagnitude)
        }
        
        // Calculate derived metrics from gyroscope FFT result (primary sensor)
        val tremorBandPower = gyroFFTResult?.tremorBandPower ?: 0f
        val maxPower = gyroFFTResult?.maxPower ?: 0f
        val bandRatio = if (totalPower > 0f) tremorBandPower / totalPower else 0f
        val peakProminence = if (tremorBandPower > 0f) maxPower / tremorBandPower else 0f
        val dominantFrequency = gyroFFTResult?.dominantFrequency ?: 0f
        
        // Phase 2: Apply dynamic thresholding and post-processing filters (Google's recommendations)
        var finalIsTremor = classification.first
        var finalConfidence = classification.second
        
        // Calculate actual severity from magnitude (for severity floor check)
        val estimatedSeverity = magnitude.coerceAtMost(5f)  // Cap at 5.0
        
        // Phase 2: Severity floor - reject events with severity < 0.1 (clinically insignificant)
        if (estimatedSeverity < TremorFFT.SEVERITY_FLOOR) {
            finalIsTremor = false
            finalConfidence *= 0.1f  // Very low confidence for clinically insignificant events
        }
        
        if (finalIsTremor && gyroFFTResult != null) {
            // Phase 2: Dynamic thresholding based on activity state
            val isResting = totalPower < TremorFFT.RESTING_POWER_THRESHOLD
            val dynamicMinBandRatio = if (isResting) TremorFFT.RESTING_MIN_BAND_RATIO else TremorFFT.ACTIVE_MIN_BAND_RATIO
            
            // 1. Phase 2: Dynamic band ratio threshold based on activity state
            if (bandRatio < dynamicMinBandRatio) {
                finalIsTremor = false
                finalConfidence *= 0.2f  // Severely reduce confidence - not "pure" enough
            }
            
            // 2. Frequency validation: Reject frequency < 3.0 Hz (Phase 1 - keep)
            if (dominantFrequency > 0f && dominantFrequency < TremorFFT.MIN_FREQUENCY) {
                finalIsTremor = false
                finalConfidence *= 0.2f  // Very low confidence for low-frequency movement
            }
            
            // 3. High-energy filter: If severity would be high but band ratio is low, reject (Phase 1 - keep)
            if (estimatedSeverity > TremorFFT.HIGH_ENERGY_SEVERITY_THRESHOLD && 
                bandRatio < TremorFFT.HIGH_ENERGY_BAND_RATIO_THRESHOLD) {
                finalIsTremor = false
                finalConfidence *= 0.1f  // Very low confidence for high-energy, low-band-ratio movement
            }
        }
        
        // Phase 4: Apply temporal smoothing to reduce single-sample noise
        // and detect sustained tremors across brief gaps (opus45 review)
        val (smoothedIsTremor, smoothedConfidence) = applyTemporalSmoothing(finalIsTremor, finalConfidence)
        finalIsTremor = smoothedIsTremor
        finalConfidence = smoothedConfidence
        
        // Phase 5: Evaluate against rolling baseline (opus45 review)
        val baselineEval = baselineManager?.evaluateRelativeToBaseline(magnitude, bandRatio, isResting)
        val baselineMultiplier = baselineEval?.baselineMultiplier ?: 1f
        
        // Boost confidence if significantly above baseline
        if (baselineEval != null && baselineEval.confidenceBoost > 0f && finalIsTremor) {
            finalConfidence = (finalConfidence + baselineEval.confidenceBoost).coerceIn(0f, 1f)
        }
        
        // Update baseline with this sample (only non-tremor samples)
        baselineManager?.updateBaseline(magnitude, bandRatio, totalPower, isResting, finalIsTremor)
        
        // Phase 5: Calculate clinical severity score (opus45 review)
        val episodeDurationSec = if (inTremorEpisode && currentEpisodeStartTime > 0L) {
            (now - currentEpisodeStartTime) / 1000f
        } else 0f
        
        val severity = if (finalIsTremor) {
            SeverityCalculator.calculateSeverity(
                magnitude = magnitude,
                dominantFrequency = dominantFrequency,
                bandRatio = bandRatio,
                confidence = finalConfidence,
                episodeDuration = episodeDurationSec,
                baselineMultiplier = baselineMultiplier
            )
        } else {
            0f
        }
        
        // Phase 5b: Classify tremor type (only when tremor is detected)
        val tremorClassification = if (finalIsTremor && dominantFrequency > 0f) {
            TremorClassifier.classify(
                dominantFrequency = dominantFrequency,
                totalPower = totalPower,
                bandRatio = bandRatio,
                confidence = finalConfidence,
                accelMagnitude = lastAccelMagnitude
            )
        } else null

        val tremorData = TremorData(
            now,  // Use absolute wall clock timestamp
            datetimeIso,
            timeFormatted,
            x, y, z,
            magnitude,
            lastAccelMagnitude,
            finalIsTremor,  // Use filtered classification
            finalConfidence.coerceIn(0f, 1f),  // Use filtered confidence
            isWatchWorn,
            isCharging,
            dominantFrequency,
            tremorBandPower,
            totalPower,
            bandRatio,
            peakProminence,
            severity = severity,
            baselineMultiplier = baselineMultiplier,
            // Tremor type classification
            tremorType = tremorClassification?.primaryType?.name?.lowercase() ?: "none",
            tremorTypeConfidence = tremorClassification?.confidence ?: 0f,
            isRestingState = tremorClassification?.isResting ?: isResting
        )
        
        // Synchronized access to buffer for thread safety
        synchronized(dataBuffer) {
            dataBuffer.add(tremorData)

            // Prevent buffer overflow when offline - drop oldest samples, not all
            if (dataBuffer.size > MonitoringConstants.MAX_BUFFER_SIZE) {
                val droppedCount = dataBuffer.size - MonitoringConstants.BATCH_SIZE
                repeat(droppedCount) {
                    dataBuffer.removeAt(0)
                }
                Timber.w("Buffer overflow - dropped $droppedCount oldest samples, kept ${dataBuffer.size}")
            }

            if (dataBuffer.size >= MonitoringConstants.BATCH_SIZE) {
                // Notify service that a batch is ready
                onBatchReady(dataBuffer.toList())
                dataBuffer.clear()
            }
        }
    }
    
    private fun handleAccelerometerEvent(event: SensorEvent) {
        // Linear acceleration has gravity already removed by the OS
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        lastAccelMagnitude = sqrt(x * x + y * y + z * z)
        
        // Maintain short window for variance calculation (used in classifyMovement)
        accelWindow.add(lastAccelMagnitude)
        if (accelWindow.size > 20) accelWindow.removeAt(0)
        
        // CRITICAL: Fill accelerometer FFT window at sensor rate (~50 Hz) for dual-sensor validation
        // This enables combining accelerometer and gyroscope data for improved tremor detection
        accelMagnitudeWindow.add(lastAccelMagnitude)
        if (accelMagnitudeWindow.size > MonitoringConstants.FFT_WINDOW_SIZE) {
            accelMagnitudeWindow.removeAt(0)
        }
    }
    
    /**
     * Classify movement as tremor or not based on gyroscope and accelerometer data.
     * 
     * @param gyroMag Gyroscope magnitude (rad/s)
     * @param accelMag Accelerometer magnitude (m/sÂ²)
     * @return Pair of (isTremor: Boolean, confidence: Float)
     */
    private fun classifyMovement(gyroMag: Float, accelMag: Float): Pair<Boolean, Float> {
        if (gyroMag < MonitoringConstants.LOW_TREMOR_THRESHOLD) return Pair(false, 0f)
        if (gyroMag > MonitoringConstants.HIGH_ACTIVITY_THRESHOLD) return Pair(false, 0f)
        
        val accelVariance = if (accelWindow.size >= 10) {
            val mean = accelWindow.average().toFloat()
            accelWindow.map { (it - mean) * (it - mean) }.average().toFloat()
        } else 0f
        
        val isStable = accelVariance < 2.0f
        var confidence = 0f
        
        // Adjusted thresholds for realistic hand tremor detection (0.3-1.5 rad/s range)
        if (gyroMag in MonitoringConstants.LOW_TREMOR_THRESHOLD..1.5f) confidence += 0.5f  // Detect tremors up to 1.5 rad/s
        if (isStable) confidence += 0.3f  // Stable position suggests tremor, not intentional movement
        if (gyroMag in 0.3f..1.2f) confidence += 0.2f  // Bonus confidence for typical tremor range
        
        return Pair(confidence > 0.5f, confidence)
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No action needed for accuracy changes
    }
    
    /**
     * Cleanup resources when engine is no longer needed.
     */
    fun shutdown() {
        pendingWearStateChange?.let {
            wearStateDebounceHandler.removeCallbacks(it)
            pendingWearStateChange = null
        }
        reset()
    }
}
```

**File: app\src\main\java\com\opensource\tremorwatch\TremorFFT.kt**
```kotlin
package com.opensource.tremorwatch

import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import timber.log.Timber
import kotlin.math.*

/**
 * FFT-based tremor frequency analysis for isolating pathological tremor bands.
 *
 * Pathological tremor characteristics:
 * - action tremor: 4-12 Hz (typically 5-8 Hz)
 * - resting tremor: 4-6 Hz (narrower band for resting state)
 * - postural/kinetic tremor: 4-12 Hz (broader band for active state)
 * - Physiological tremor: 8-12 Hz (normal, filtered out by requiring power threshold)
 *
 * Activity-aware frequency band selection:
 * - Resting state (total_power < 10.0): Uses 4-6 Hz band (resting tremor)
 * - Active state (total_power >= 10.0): Uses 4-12 Hz band (postural/kinetic tremor)
 *
 * Can analyze both gyroscope (angular velocity) and accelerometer (linear acceleration) data
 * for dual-sensor validation and improved tremor detection accuracy.
 */
class TremorFFT(private val sampleRate: Float = 20f) {

    /**
     * Thread-safe configuration.
     * Marked @Volatile to ensure visibility across threads (audio processing vs UI).
     */
    @Volatile
    private var config: TremorDetectionConfig = TremorDetectionConfig()

    /**
     * Update the detection configuration.
     * Thread-safe due to @Volatile and immutable TremorDetectionConfig.
     *
     * @param newConfig The new configuration to apply
     */
    fun setConfig(newConfig: TremorDetectionConfig) {
        config = newConfig
        Timber.i("TremorFFT config updated: ${config.profileName}")
    }

    companion object {
        // Tremor frequency bands (Hz)
        // resting tremor (primary - narrow band for resting state)
        const val RESTING_RESTING_BAND_LOW = 4.0f
        const val RESTING_RESTING_BAND_HIGH = 6.0f
        
        // Postural/kinetic tremor (broader band for active state)
        const val POSTURAL_TREMOR_BAND_LOW = 4.0f
        const val POSTURAL_TREMOR_BAND_HIGH = 12.0f
        
        // Legacy tremor frequency band (backward compatibility)
        const val TREMOR_BAND_LOW = 4.0f
        const val TREMOR_BAND_HIGH = 12.0f

        // Minimum power threshold to classify as tremor (filters noise)
        const val MIN_TREMOR_POWER = 0.001f
        
        // Phase 4: Improved tremor detection thresholds (opus45 review)
        // Lowered thresholds based on InfluxDB data analysis showing median severity=0.001, median band_ratio=0.07
        const val MIN_BAND_RATIO = 0.04f           // Minimum band ratio (lowered from 0.06 - was filtering too much)
        const val MIN_FREQUENCY = 4.0f             // Minimum frequency (Hz) - reject < 4Hz as voluntary movement
        const val HIGH_ENERGY_SEVERITY_THRESHOLD = 1.0f  // Severity threshold for high-energy filter
        const val HIGH_ENERGY_BAND_RATIO_THRESHOLD = 0.04f  // Band ratio threshold for high-energy filter
        
        // Phase 4: Dynamic thresholding with improved sensitivity
        const val RESTING_POWER_THRESHOLD = 10.0f  // total_power < 10.0 indicates resting state
        const val RESTING_MIN_BAND_RATIO = 0.05f   // Resting state: lowered from 0.07 to detect subtle tremors
        const val ACTIVE_MIN_BAND_RATIO = 0.10f    // Active state: lowered from 0.30 to allow detection during activity
        const val SEVERITY_FLOOR = 0.005f          // Lowered from 0.03 - was rejecting 70% of valid data
    }

    /**
     * Result of FFT analysis
     */
    data class FFTResult(
        val dominantFrequency: Float,    // Hz - peak frequency in tremor band
        val tremorBandPower: Float,      // Power spectral density in 4-12 Hz band
        val totalPower: Float,           // Total power across all frequencies
        val maxPower: Float,             // Maximum power in tremor band (for peak prominence)
        val isTremor: Boolean,           // Whether tremor was detected
        val confidence: Float            // 0-1 confidence score
    )
    
    /**
     * Adaptive thresholds for personalized tremor detection.
     * These can be provided by BaselineManager based on user's calibration.
     */
    data class AdaptiveThresholds(
        val severityFloor: Float = SEVERITY_FLOOR,
        val minBandRatio: Float = MIN_BAND_RATIO,
        val confidenceThreshold: Float = 0.35f,
        val isPersonalized: Boolean = false
    )

    /**
     * Perform FFT analysis on a window of sensor magnitude samples (gyroscope or accelerometer).
     *
     * @param samples Array of sensor magnitude values
     * @param isResting Whether the arm is in resting state (true) or active state (false)
     *                  - Resting: Use resting tremor band (4-6 Hz)
     *                  - Active: Use postural/kinetic tremor band (4-12 Hz)
     * @param adaptiveThresholds Optional personalized thresholds from calibration
     * @return FFTResult with frequency analysis
     */
    fun analyze(
        samples: FloatArray, 
        isResting: Boolean = false,
        adaptiveThresholds: AdaptiveThresholds? = null
    ): FFTResult {
        if (samples.size < 16) {
            return FFTResult(0f, 0f, 0f, 0f, false, 0f)
        }

        // Use power of 2 for FFT efficiency
        val n = samples.size.takeHighestOneBit()
        val paddedSamples = if (samples.size >= n) {
            samples.copyOf(n)
        } else {
            samples.copyOf(n)
        }

        // Apply Hanning window to reduce spectral leakage
        val windowed = applyHanningWindow(paddedSamples)

        // Compute FFT
        val (real, imag) = fft(windowed)

        // Compute power spectrum (magnitude squared)
        val powerSpectrum = FloatArray(n / 2)
        var totalPower = 0f

        for (i in 0 until n / 2) {
            powerSpectrum[i] = (real[i] * real[i] + imag[i] * imag[i]) / n
            totalPower += powerSpectrum[i]
        }

        // Calculate frequency resolution
        val freqResolution = sampleRate / n

        // Select frequency band based on activity state
        // Resting state: Use resting tremor band (4-6 Hz) - narrower, more specific
        // Active state: Use postural/kinetic tremor band (4-12 Hz) - broader, covers all tremor types
        val bandLow = if (isResting) config.restingBandLowHz else config.activeBandLowHz
        val bandHigh = if (isResting) config.restingBandHighHz else config.activeBandHighHz

        // Find power and dominant frequency in selected tremor band
        var tremorBandPower = 0f
        var maxPower = 0f
        var dominantFreq = 0f

        for (i in 0 until n / 2) {
            val freq = i * freqResolution

            if (freq >= bandLow && freq <= bandHigh) {
                tremorBandPower += powerSpectrum[i]

                if (powerSpectrum[i] > maxPower) {
                    maxPower = powerSpectrum[i]
                    dominantFreq = freq
                }
            }
        }

        // Calculate confidence based on:
        // Phase 3: Refined weighting based on deep data analysis
        // Uses configurable weights for flexibility
        val bandRatio = if (totalPower > 0) tremorBandPower / totalPower else 0f
        val peakProminence = if (tremorBandPower > 0) maxPower / tremorBandPower else 0f

        var confidence = 0f

        // Band ratio: High power in tremor band = likely tremor (configurable weight)
        // Use continuous scaling instead of hard thresholds for better sensitivity
        val bandRatioContribution = (bandRatio * 3f).coerceIn(0f, config.bandRatioWeight)
        confidence += bandRatioContribution

        // Peak prominence: (configurable weight)
        val peakProminenceContribution = (peakProminence * 0.5f).coerceIn(0f, config.peakProminenceWeight)
        confidence += peakProminenceContribution

        // Frequency validation: Within tremor range (configurable weight)
        // resting range (4-6 Hz) gets highest weight
        val maxFreqWeight = config.frequencyValidationWeight
        val freqScore = when {
            dominantFreq in config.restingBandLowHz..config.restingBandHighHz -> maxFreqWeight  // resting tremor range
            dominantFreq in 6.0f..8.0f -> maxFreqWeight * 0.8f  // action tremor range
            dominantFreq in 8.0f..12.0f -> maxFreqWeight * 0.4f // Physiological tremor range
            dominantFreq in 3.0f..config.minFrequencyHz -> maxFreqWeight * 0.2f  // Borderline low frequency
            else -> 0f
        }
        confidence += freqScore

        // Total power: Activity discriminator (remaining weight ~30%)
        val powerWeight = 1.0f - config.bandRatioWeight - config.peakProminenceWeight - config.frequencyValidationWeight
        if (totalPower < config.restingPowerThreshold) {
            confidence += powerWeight  // Resting state - more likely true tremor
        } else if (totalPower < config.restingPowerThreshold * 2) {
            confidence += powerWeight * 0.5f  // Moderate activity
        }
        // High activity gets no bonus

        // Dual-sensor agreement bonus: 10% weight (Phase 4 - new)
        // This gets applied later in TremorMonitoringEngine when both sensors agree

        // Phase 2: Dynamic thresholding based on activity state (Google's recommendations)
        // Note: Phase 2 uses dynamic thresholds but still applies Phase 1 filters as baseline
        // 1. Determine activity state based on total_power
        // 2. Apply dynamic band ratio threshold (resting: 0.09, active: 0.30)
        // 3. Frequency validation: Reject frequency < 3.0 Hz
        // 4. High-energy filter: Reject if severity would be > 1.0 AND band_ratio < 0.04
        val isRestingState = totalPower < config.restingPowerThreshold

        // Use adaptive thresholds if provided (from calibration), otherwise use config values
        val dynamicMinBandRatio = adaptiveThresholds?.minBandRatio
            ?: if (isRestingState) config.restingMinBandRatio else config.activeMinBandRatio
        val confidenceThreshold = adaptiveThresholds?.confidenceThreshold ?: config.confidenceThreshold

        val meetsFrequencyThreshold = dominantFreq >= config.minFrequencyHz
        val hasMinimumPower = tremorBandPower > config.minTremorPower
        
        // High-energy, low-frequency movement filter (Phase 1 - keep)
        // Estimate severity from magnitude (will be refined in TremorMonitoringEngine)
        // For now, use totalPower as proxy - if very high power but low band ratio, likely movement
        val adaptiveSeverityFloor = adaptiveThresholds?.severityFloor ?: config.severityFloor
        val estimatedSeverity = if (totalPower > 50f) {
            // High total power suggests high-energy movement
            (totalPower / 50f).coerceAtMost(5f)  // Cap at 5.0
        } else {
            0f
        }
        val isHighEnergyLowBandRatio = estimatedSeverity > config.highEnergySeverityThreshold &&
                                       bandRatio < config.highEnergyBandRatioThreshold

        // Phase 2: Apply dynamic band ratio threshold based on activity state
        val meetsDynamicBandRatioThreshold = bandRatio >= dynamicMinBandRatio
        
        // Determine if tremor detected with Phase 4 improved filters
        // Use adaptive confidence threshold if personalized, otherwise default 0.35
        val isTremor = hasMinimumPower &&
                       meetsFrequencyThreshold &&
                       !isHighEnergyLowBandRatio &&
                       dominantFreq >= bandLow &&
                       confidence > confidenceThreshold

        return FFTResult(
            dominantFrequency = dominantFreq,
            tremorBandPower = tremorBandPower,
            totalPower = totalPower,
            maxPower = maxPower,
            isTremor = isTremor,
            confidence = confidence.coerceIn(0f, 1f)
        )
    }

    /**
     * Apply Hanning window to reduce spectral leakage
     */
    private fun applyHanningWindow(samples: FloatArray): FloatArray {
        val n = samples.size
        return FloatArray(n) { i ->
            val window = 0.5f * (1 - cos(2 * PI.toFloat() * i / (n - 1)))
            samples[i] * window
        }
    }

    /**
     * Cooley-Tukey FFT algorithm (radix-2, decimation-in-time)
     * Returns (real, imaginary) arrays
     */
    private fun fft(samples: FloatArray): Pair<FloatArray, FloatArray> {
        val n = samples.size

        // Base case
        if (n == 1) {
            return Pair(floatArrayOf(samples[0]), floatArrayOf(0f))
        }

        // Split into even and odd
        val even = FloatArray(n / 2) { samples[2 * it] }
        val odd = FloatArray(n / 2) { samples[2 * it + 1] }

        // Recursive FFT
        val (evenReal, evenImag) = fft(even)
        val (oddReal, oddImag) = fft(odd)

        // Combine
        val real = FloatArray(n)
        val imag = FloatArray(n)

        for (k in 0 until n / 2) {
            val angle = -2 * PI.toFloat() * k / n
            val cos = cos(angle)
            val sin = sin(angle)

            // Twiddle factor multiplication
            val tReal = cos * oddReal[k] - sin * oddImag[k]
            val tImag = sin * oddReal[k] + cos * oddImag[k]

            real[k] = evenReal[k] + tReal
            imag[k] = evenImag[k] + tImag
            real[k + n / 2] = evenReal[k] - tReal
            imag[k + n / 2] = evenImag[k] - tImag
        }

        return Pair(real, imag)
    }
}
```

**File: app\src\main\java\com\opensource\tremorwatch\engine\BaselineManager.kt**
```kotlin
package com.opensource.tremorwatch.engine

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Manages rolling baseline statistics for personalized tremor detection.
 * 
 * Tracks rolling averages of key metrics to dynamically adjust detection thresholds
 * based on the user's individual baseline "shakiness" level.
 * 
 * Key features:
 * - Rolling average of magnitude, band ratio, and power metrics
 * - Exponential moving average for smooth updates
 * - Persistence across app restarts
 * - Separate baselines for resting vs active states
 * - Explicit calibration mode for personalized setup
 * - Adaptive thresholding based on individual variance
 */
class BaselineManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "tremorwatch_baseline"
        
        // Rolling average configuration
        const val ROLLING_WINDOW_SIZE = 300  // ~5 minutes at 1 Hz
        const val EMA_ALPHA = 0.02f           // Exponential moving average smoothing (slow adaptation)
        const val MIN_SAMPLES_FOR_BASELINE = 60  // Require 1 minute before baseline is valid
        
        // Calibration mode configuration
        const val CALIBRATION_DURATION_SECONDS = 30  // 30 seconds of resting calibration
        const val CALIBRATION_SAMPLES_REQUIRED = 30  // Samples needed for valid calibration
        
        // Threshold multipliers
        const val TREMOR_THRESHOLD_MULTIPLIER = 2.0f  // Tremor if 2x baseline
        const val SIGNIFICANT_TREMOR_MULTIPLIER = 3.0f  // Significant if 3x baseline
        
        // Adaptive threshold configuration
        const val ADAPTIVE_THRESHOLD_SIGMA = 2.5f  // Standard deviations above mean for adaptive threshold
        const val MIN_ADAPTIVE_THRESHOLD = 0.05f   // Minimum adaptive threshold (prevents too sensitive)
        const val MAX_ADAPTIVE_THRESHOLD = 1.0f    // Maximum adaptive threshold (prevents missing tremors)
        
        // Persistence keys
        private const val KEY_RESTING_MAGNITUDE = "resting_magnitude"
        private const val KEY_RESTING_BAND_RATIO = "resting_band_ratio"
        private const val KEY_RESTING_TOTAL_POWER = "resting_total_power"
        private const val KEY_ACTIVE_MAGNITUDE = "active_magnitude"
        private const val KEY_ACTIVE_BAND_RATIO = "active_band_ratio"
        private const val KEY_ACTIVE_TOTAL_POWER = "active_total_power"
        private const val KEY_SAMPLE_COUNT = "sample_count"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_CALIBRATION_COMPLETE = "calibration_complete"
        private const val KEY_CALIBRATION_TIMESTAMP = "calibration_timestamp"
        
        // Variance tracking
        private const val KEY_RESTING_MAGNITUDE_VAR = "resting_magnitude_var"
        private const val KEY_ACTIVE_MAGNITUDE_VAR = "active_magnitude_var"
    }
    
    // Calibration mode state
    private var isCalibrating = false
    private var calibrationStartTime = 0L
    private var calibrationSamples = mutableListOf<Float>()
    private var calibrationListener: CalibrationListener? = null
    
    /**
     * Listener for calibration progress updates
     */
    interface CalibrationListener {
        fun onCalibrationProgress(progress: Float, samplesCollected: Int, secondsRemaining: Int)
        fun onCalibrationComplete(success: Boolean, baselineMagnitude: Float, baselineVariance: Float)
    }
    
    /**
     * Baseline statistics for a specific activity state
     */
    data class BaselineStats(
        var magnitude: Float = 0.15f,      // Default baseline magnitude
        var bandRatio: Float = 0.05f,      // Default band ratio
        var totalPower: Float = 3.0f,      // Default total power
        var magnitudeVariance: Float = 0.01f, // Variance for adaptive thresholds
        var sampleCount: Int = 0
    ) {
        /**
         * Check if baseline has enough samples to be valid
         */
        fun isValid(): Boolean = sampleCount >= MIN_SAMPLES_FOR_BASELINE
        
        /**
         * Get adaptive threshold based on baseline + variance
         */
        fun getAdaptiveThreshold(): Float {
            val stdDev = sqrt(magnitudeVariance)
            return magnitude + (stdDev * 2f)  // 2 standard deviations above mean
        }
    }
    
    // Rolling baseline statistics
    private var restingBaseline = BaselineStats()
    private var activeBaseline = BaselineStats()
    
    // Running variance calculation using Welford's algorithm
    private var restingM2 = 0.0  // Sum of squared differences
    private var activeM2 = 0.0
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    init {
        loadBaseline()
    }
    
    // ====================== CALIBRATION MODE ======================
    
    /**
     * Start calibration mode.
     * User should rest their arm for 30 seconds to establish personal baseline.
     */
    fun startCalibration(listener: CalibrationListener) {
        isCalibrating = true
        calibrationStartTime = System.currentTimeMillis()
        calibrationSamples.clear()
        calibrationListener = listener
        Timber.i("â˜… Calibration started - user should rest arm for $CALIBRATION_DURATION_SECONDS seconds")
    }
    
    /**
     * Cancel ongoing calibration
     */
    fun cancelCalibration() {
        if (isCalibrating) {
            isCalibrating = false
            calibrationSamples.clear()
            calibrationListener?.onCalibrationComplete(false, 0f, 0f)
            calibrationListener = null
            Timber.i("Calibration cancelled")
        }
    }
    
    /**
     * Process a calibration sample.
     * Called during calibration mode to collect resting baseline data.
     * 
     * @param magnitude Current gyroscope magnitude
     * @return true if calibration is complete
     */
    fun processCalibrationSample(magnitude: Float): Boolean {
        if (!isCalibrating) return false
        
        calibrationSamples.add(magnitude)
        
        val elapsedSeconds = ((System.currentTimeMillis() - calibrationStartTime) / 1000).toInt()
        val secondsRemaining = (CALIBRATION_DURATION_SECONDS - elapsedSeconds).coerceAtLeast(0)
        val progress = (elapsedSeconds.toFloat() / CALIBRATION_DURATION_SECONDS).coerceIn(0f, 1f)
        
        calibrationListener?.onCalibrationProgress(
            progress = progress,
            samplesCollected = calibrationSamples.size,
            secondsRemaining = secondsRemaining
        )
        
        // Check if calibration is complete
        if (elapsedSeconds >= CALIBRATION_DURATION_SECONDS && 
            calibrationSamples.size >= CALIBRATION_SAMPLES_REQUIRED) {
            completeCalibration()
            return true
        }
        
        return false
    }
    
    /**
     * Complete calibration and set personalized baseline
     */
    private fun completeCalibration() {
        if (calibrationSamples.size < CALIBRATION_SAMPLES_REQUIRED) {
            Timber.w("Calibration failed - not enough samples (${calibrationSamples.size}/$CALIBRATION_SAMPLES_REQUIRED)")
            calibrationListener?.onCalibrationComplete(false, 0f, 0f)
            isCalibrating = false
            calibrationListener = null
            return
        }
        
        // Calculate mean
        val mean = calibrationSamples.average().toFloat()
        
        // Calculate variance
        val variance = calibrationSamples.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = sqrt(variance)
        
        // Set personalized resting baseline
        restingBaseline = BaselineStats(
            magnitude = mean,
            bandRatio = 0.05f,  // Default, will be updated during normal operation
            totalPower = 3.0f,
            magnitudeVariance = variance,
            sampleCount = calibrationSamples.size
        )
        restingM2 = (variance * calibrationSamples.size).toDouble()
        
        // Mark calibration complete and save
        prefs.edit().putBoolean(KEY_CALIBRATION_COMPLETE, true)
            .putLong(KEY_CALIBRATION_TIMESTAMP, System.currentTimeMillis())
            .apply()
        saveBaseline()
        
        Timber.i("â˜… Calibration complete! Baseline magnitude: %.4f Â± %.4f", mean, stdDev)
        
        calibrationListener?.onCalibrationComplete(true, mean, variance)
        isCalibrating = false
        calibrationSamples.clear()
        calibrationListener = null
    }
    
    /**
     * Check if calibration mode is active
     */
    fun isInCalibrationMode(): Boolean = isCalibrating
    
    /**
     * Check if user has completed calibration at least once
     */
    fun hasCompletedCalibration(): Boolean {
        return prefs.getBoolean(KEY_CALIBRATION_COMPLETE, false)
    }
    
    /**
     * Get time since last calibration in hours, or -1 if never calibrated
     */
    fun getHoursSinceCalibration(): Int {
        val lastCalibration = prefs.getLong(KEY_CALIBRATION_TIMESTAMP, 0L)
        if (lastCalibration == 0L) return -1
        return ((System.currentTimeMillis() - lastCalibration) / (1000 * 60 * 60)).toInt()
    }
    
    // ====================== ADAPTIVE THRESHOLDING ======================
    
    /**
     * Get adaptive severity threshold based on personal baseline.
     * Returns a personalized threshold that accounts for individual variance.
     */
    fun getAdaptiveSeverityThreshold(isResting: Boolean): Float {
        val baseline = if (isResting) restingBaseline else activeBaseline
        
        if (!baseline.isValid()) {
            // No valid baseline - return default threshold
            return 0.005f  // Default SEVERITY_FLOOR
        }
        
        val stdDev = sqrt(baseline.magnitudeVariance)
        val adaptiveThreshold = baseline.magnitude + (stdDev * ADAPTIVE_THRESHOLD_SIGMA)
        
        // Clamp to reasonable range
        return adaptiveThreshold.coerceIn(MIN_ADAPTIVE_THRESHOLD, MAX_ADAPTIVE_THRESHOLD)
    }
    
    /**
     * Get adaptive band ratio threshold based on personal baseline.
     */
    fun getAdaptiveBandRatioThreshold(isResting: Boolean): Float {
        val baseline = if (isResting) restingBaseline else activeBaseline
        
        if (!baseline.isValid()) {
            // No valid baseline - return default thresholds
            return if (isResting) 0.05f else 0.10f
        }
        
        // Personal band ratio baseline + margin
        val margin = 0.02f
        return (baseline.bandRatio + margin).coerceIn(0.03f, 0.15f)
    }
    
    /**
     * Get all adaptive thresholds as a data class for use in TremorFFT
     */
    fun getAdaptiveThresholds(isResting: Boolean): AdaptiveThresholds {
        val baseline = if (isResting) restingBaseline else activeBaseline
        
        return AdaptiveThresholds(
            severityFloor = getAdaptiveSeverityThreshold(isResting),
            minBandRatio = getAdaptiveBandRatioThreshold(isResting),
            magnitudeThreshold = baseline.getAdaptiveThreshold(),
            isPersonalized = baseline.isValid() && hasCompletedCalibration()
        )
    }
    
    /**
     * Adaptive thresholds data class for tremor detection
     */
    data class AdaptiveThresholds(
        val severityFloor: Float,
        val minBandRatio: Float,
        val magnitudeThreshold: Float,
        val isPersonalized: Boolean  // Whether these are personalized or defaults
    )
    
    // ====================== BASELINE UPDATES ======================
    
    /**
     * Update rolling baseline with a new sample.
     * Uses exponential moving average for smooth adaptation.
     * 
     * @param magnitude Current gyroscope magnitude
     * @param bandRatio Current band ratio from FFT
     * @param totalPower Current total power from FFT
     * @param isResting Whether the user is in resting state
     * @param isTremor Whether this sample was classified as tremor (excluded from baseline)
     */
    fun updateBaseline(
        magnitude: Float,
        bandRatio: Float,
        totalPower: Float,
        isResting: Boolean,
        isTremor: Boolean
    ) {
        // Don't include tremor samples in baseline calculation
        if (isTremor) {
            return
        }
        
        val baseline = if (isResting) restingBaseline else activeBaseline
        
        // Increment sample count
        baseline.sampleCount++
        
        // Use exponential moving average for smooth updates
        val alpha = if (baseline.sampleCount < MIN_SAMPLES_FOR_BASELINE) {
            // Use simple average initially for faster convergence
            1f / baseline.sampleCount
        } else {
            EMA_ALPHA
        }
        
        // Update magnitude with EMA
        val oldMagnitude = baseline.magnitude
        baseline.magnitude = baseline.magnitude * (1 - alpha) + magnitude * alpha
        
        // Update band ratio with EMA
        baseline.bandRatio = baseline.bandRatio * (1 - alpha) + bandRatio * alpha
        
        // Update total power with EMA
        baseline.totalPower = baseline.totalPower * (1 - alpha) + totalPower * alpha
        
        // Update variance using Welford's online algorithm
        val delta = magnitude - oldMagnitude
        val delta2 = magnitude - baseline.magnitude
        if (isResting) {
            restingM2 += delta * delta2
            baseline.magnitudeVariance = (restingM2 / baseline.sampleCount.coerceAtLeast(1)).toFloat()
        } else {
            activeM2 += delta * delta2
            baseline.magnitudeVariance = (activeM2 / baseline.sampleCount.coerceAtLeast(1)).toFloat()
        }
        
        // Persist periodically (every 60 samples = ~1 minute)
        if (baseline.sampleCount % 60 == 0) {
            saveBaseline()
        }
    }
    
    /**
     * Check if a sample represents tremor relative to baseline.
     * Uses adaptive thresholds based on user's individual baseline.
     * 
     * @param magnitude Current magnitude
     * @param bandRatio Current band ratio
     * @param isResting Activity state
     * @return Triple of (isTremorRelativeToBaseline, relativeIntensity, baselineMultiplier)
     */
    fun evaluateRelativeToBaseline(
        magnitude: Float,
        bandRatio: Float,
        isResting: Boolean
    ): BaselineEvaluation {
        val baseline = if (isResting) restingBaseline else activeBaseline
        
        if (!baseline.isValid()) {
            // Not enough data for baseline - return neutral evaluation
            return BaselineEvaluation(
                isAboveBaseline = false,
                relativeIntensity = 1.0f,
                baselineMultiplier = 1.0f,
                confidenceBoost = 0f
            )
        }
        
        // Calculate how far above baseline
        val magnitudeMultiplier = if (baseline.magnitude > 0.01f) {
            magnitude / baseline.magnitude
        } else {
            1f
        }
        
        val bandRatioMultiplier = if (baseline.bandRatio > 0.01f) {
            bandRatio / baseline.bandRatio
        } else {
            1f
        }
        
        // Combined relative intensity
        val relativeIntensity = (magnitudeMultiplier * 0.6f + bandRatioMultiplier * 0.4f)
        
        // Check against adaptive threshold
        val adaptiveThreshold = baseline.getAdaptiveThreshold()
        val isAboveBaseline = magnitude > adaptiveThreshold || 
                              magnitudeMultiplier > TREMOR_THRESHOLD_MULTIPLIER
        
        // Calculate confidence boost for samples well above baseline
        val confidenceBoost = when {
            magnitudeMultiplier >= SIGNIFICANT_TREMOR_MULTIPLIER -> 0.2f  // 3x+ baseline
            magnitudeMultiplier >= TREMOR_THRESHOLD_MULTIPLIER -> 0.1f   // 2x+ baseline
            magnitudeMultiplier >= 1.5f -> 0.05f                         // 1.5x+ baseline
            else -> 0f
        }
        
        return BaselineEvaluation(
            isAboveBaseline = isAboveBaseline,
            relativeIntensity = relativeIntensity,
            baselineMultiplier = magnitudeMultiplier,
            confidenceBoost = confidenceBoost
        )
    }
    
    /**
     * Result of baseline evaluation
     */
    data class BaselineEvaluation(
        val isAboveBaseline: Boolean,
        val relativeIntensity: Float,
        val baselineMultiplier: Float,
        val confidenceBoost: Float  // Additional confidence to add if above baseline
    )
    
    /**
     * Get current baseline statistics for diagnostics
     */
    fun getBaselineStats(isResting: Boolean): BaselineStats {
        return if (isResting) restingBaseline.copy() else activeBaseline.copy()
    }
    
    /**
     * Check if baseline is ready for use
     */
    fun isBaselineReady(isResting: Boolean): Boolean {
        return if (isResting) restingBaseline.isValid() else activeBaseline.isValid()
    }
    
    /**
     * Reset baseline (e.g., for recalibration)
     */
    fun resetBaseline() {
        restingBaseline = BaselineStats()
        activeBaseline = BaselineStats()
        restingM2 = 0.0
        activeM2 = 0.0
        
        prefs.edit().clear().apply()
        Timber.i("Baseline reset - starting fresh calibration")
    }
    
    /**
     * Save baseline to persistent storage
     */
    private fun saveBaseline() {
        prefs.edit().apply {
            // Resting baseline
            putFloat(KEY_RESTING_MAGNITUDE, restingBaseline.magnitude)
            putFloat(KEY_RESTING_BAND_RATIO, restingBaseline.bandRatio)
            putFloat(KEY_RESTING_TOTAL_POWER, restingBaseline.totalPower)
            putFloat(KEY_RESTING_MAGNITUDE_VAR, restingBaseline.magnitudeVariance)
            
            // Active baseline
            putFloat(KEY_ACTIVE_MAGNITUDE, activeBaseline.magnitude)
            putFloat(KEY_ACTIVE_BAND_RATIO, activeBaseline.bandRatio)
            putFloat(KEY_ACTIVE_TOTAL_POWER, activeBaseline.totalPower)
            putFloat(KEY_ACTIVE_MAGNITUDE_VAR, activeBaseline.magnitudeVariance)
            
            // Combined sample count (sum of both)
            putInt(KEY_SAMPLE_COUNT, restingBaseline.sampleCount + activeBaseline.sampleCount)
            putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            
            apply()
        }
        
        Timber.d("Baseline saved - resting: ${restingBaseline.magnitude}, active: ${activeBaseline.magnitude}")
    }
    
    /**
     * Load baseline from persistent storage
     */
    private fun loadBaseline() {
        if (!prefs.contains(KEY_RESTING_MAGNITUDE)) {
            Timber.d("No saved baseline - using defaults")
            return
        }
        
        // Resting baseline
        restingBaseline = BaselineStats(
            magnitude = prefs.getFloat(KEY_RESTING_MAGNITUDE, 0.15f),
            bandRatio = prefs.getFloat(KEY_RESTING_BAND_RATIO, 0.05f),
            totalPower = prefs.getFloat(KEY_RESTING_TOTAL_POWER, 3.0f),
            magnitudeVariance = prefs.getFloat(KEY_RESTING_MAGNITUDE_VAR, 0.01f),
            sampleCount = MIN_SAMPLES_FOR_BASELINE  // Assume valid if saved
        )
        
        // Active baseline
        activeBaseline = BaselineStats(
            magnitude = prefs.getFloat(KEY_ACTIVE_MAGNITUDE, 0.3f),
            bandRatio = prefs.getFloat(KEY_ACTIVE_BAND_RATIO, 0.03f),
            totalPower = prefs.getFloat(KEY_ACTIVE_TOTAL_POWER, 15.0f),
            magnitudeVariance = prefs.getFloat(KEY_ACTIVE_MAGNITUDE_VAR, 0.05f),
            sampleCount = MIN_SAMPLES_FOR_BASELINE
        )
        
        Timber.i("Baseline loaded - resting: ${restingBaseline.magnitude}, active: ${activeBaseline.magnitude}")
    }
}
```

**File: app\src\main\java\com\opensource\tremorwatch\engine\SeverityCalculator.kt**
```kotlin
package com.opensource.tremorwatch.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Calculates clinically meaningful tremor severity scores.
 * 
 * The severity calculation is based on multiple factors:
 * 1. Magnitude-based raw severity (primary input)
 * 2. Frequency weighting (peak at 4-6 Hz for resting tremor)
 * 3. Band ratio quality factor (higher ratio = purer tremor signal)
 * 4. Duration factor (sustained tremors are more clinically significant)
 * 5. Confidence integration
 * 
 * Output is a 0-10 scale where:
 * - 0-1: Minimal/no tremor
 * - 1-3: Mild tremor
 * - 3-5: Moderate tremor
 * - 5-7: Moderate-severe tremor
 * - 7-10: Severe tremor
 */
object SeverityCalculator {
    
    // Frequency weighting parameters
    private const val OPTIMAL_FREQUENCY_LOW = 4.0f   // Start of optimal range
    private const val OPTIMAL_FREQUENCY_PEAK = 5.0f  // Peak weighting frequency (resting)
    private const val OPTIMAL_FREQUENCY_HIGH = 6.0f  // End of optimal range
    private const val ESSENTIAL_TREMOR_PEAK = 7.0f   // action tremor frequency
    
    // Severity scaling parameters
    private const val MAGNITUDE_SCALE_FACTOR = 2.0f  // Scale magnitude to severity
    private const val MAX_SEVERITY = 10.0f           // Maximum severity score
    
    // Band ratio quality thresholds
    private const val HIGH_QUALITY_BAND_RATIO = 0.15f
    private const val MEDIUM_QUALITY_BAND_RATIO = 0.08f
    
    // Duration thresholds (in seconds)
    private const val SUSTAINED_TREMOR_DURATION = 10f  // 10+ seconds
    private const val MODERATE_DURATION = 5f            // 5+ seconds
    private const val BRIEF_DURATION = 3f               // 3+ seconds
    
    /**
     * Calculate comprehensive tremor severity score.
     * 
     * @param magnitude Gyroscope magnitude (rad/s)
     * @param dominantFrequency Dominant frequency from FFT (Hz)
     * @param bandRatio Power ratio in tremor band
     * @param confidence Detection confidence (0-1)
     * @param episodeDuration Current episode duration in seconds (0 if not in episode)
     * @param baselineMultiplier How much above baseline (1.0 = at baseline)
     * @return Severity score from 0.0 to 10.0
     */
    fun calculateSeverity(
        magnitude: Float,
        dominantFrequency: Float,
        bandRatio: Float,
        confidence: Float,
        episodeDuration: Float = 0f,
        baselineMultiplier: Float = 1.0f
    ): Float {
        // 1. Base severity from magnitude
        // Use logarithmic scaling to compress the range
        val baseSeverity = calculateBaseSeverity(magnitude)
        
        // 2. Frequency weighting
        val frequencyWeight = calculateFrequencyWeight(dominantFrequency)
        
        // 3. Band ratio quality factor
        val qualityFactor = calculateQualityFactor(bandRatio)
        
        // 4. Duration factor
        val durationFactor = calculateDurationFactor(episodeDuration)
        
        // 5. Baseline-relative boost
        val baselineBoost = calculateBaselineBoost(baselineMultiplier)
        
        // Combine factors
        val rawSeverity = baseSeverity * frequencyWeight * qualityFactor * durationFactor * baselineBoost
        
        // Apply confidence as a gate (low confidence reduces final severity)
        val confidenceGate = (confidence * 1.5f).coerceIn(0.3f, 1.0f)
        
        return (rawSeverity * confidenceGate).coerceIn(0f, MAX_SEVERITY)
    }
    
    /**
     * Calculate base severity from magnitude using logarithmic scaling.
     * 
     * Logarithmic scaling provides:
     * - Good sensitivity at low magnitudes
     * - Compression at high magnitudes (prevents runaway values)
     * - Approximate mapping: 0.1 rad/s -> ~1.0 severity, 1.0 rad/s -> ~5.0 severity
     */
    private fun calculateBaseSeverity(magnitude: Float): Float {
        if (magnitude <= 0.01f) return 0f
        
        // Use log scale: severity = scale * ln(1 + magnitude * factor)
        // Tuned so that typical tremor magnitudes (0.05-0.5) map to 0.5-5.0 severity
        val logSeverity = MAGNITUDE_SCALE_FACTOR * ln(1f + magnitude * 10f)
        
        return logSeverity.coerceIn(0f, 8f)  // Cap before other factors applied
    }
    
    /**
     * Calculate frequency weighting factor.
     * 
     * Peak weight at 4-6 Hz (resting tremor range)
     * Secondary peak at 5-8 Hz (action tremor range)
     * Lower weight for physiological tremor (8-12 Hz)
     */
    private fun calculateFrequencyWeight(frequency: Float): Float {
        return when {
            // resting tremor range (highest clinical significance)
            frequency in OPTIMAL_FREQUENCY_LOW..OPTIMAL_FREQUENCY_HIGH -> {
                // Gaussian peak centered at 5 Hz
                val deviation = frequency - OPTIMAL_FREQUENCY_PEAK
                1.0f + 0.5f * exp(-deviation * deviation / 2f).toFloat()
            }
            // action tremor range
            frequency in 6.0f..8.0f -> {
                val deviation = frequency - ESSENTIAL_TREMOR_PEAK
                1.0f + 0.3f * exp(-deviation * deviation / 2f).toFloat()
            }
            // Physiological tremor (lower significance)
            frequency in 8.0f..12.0f -> 0.8f
            // Below tremor range (very low significance)
            frequency in 2.0f..4.0f -> 0.5f
            // Out of range
            else -> 0.3f
        }
    }
    
    /**
     * Calculate quality factor based on band ratio.
     * 
     * Higher band ratio indicates "purer" tremor signal
     * (more power concentrated in tremor frequency band)
     */
    private fun calculateQualityFactor(bandRatio: Float): Float {
        return when {
            bandRatio >= HIGH_QUALITY_BAND_RATIO -> {
                // High quality signal - boost severity
                1.0f + (bandRatio * 3f).coerceAtMost(0.5f)
            }
            bandRatio >= MEDIUM_QUALITY_BAND_RATIO -> {
                // Medium quality
                0.8f + (bandRatio - MEDIUM_QUALITY_BAND_RATIO) * 5f
            }
            bandRatio >= 0.04f -> {
                // Low quality but still valid
                0.6f + (bandRatio - 0.04f) * 10f
            }
            else -> {
                // Very low quality - reduce severity
                0.5f
            }
        }
    }
    
    /**
     * Calculate duration factor for sustained tremors.
     * 
     * Sustained tremors are more clinically significant than brief episodes.
     */
    private fun calculateDurationFactor(durationSeconds: Float): Float {
        return when {
            durationSeconds >= SUSTAINED_TREMOR_DURATION -> 1.5f   // 10+ seconds: significant boost
            durationSeconds >= MODERATE_DURATION -> 1.2f           // 5+ seconds: moderate boost
            durationSeconds >= BRIEF_DURATION -> 1.0f              // 3+ seconds: no change
            durationSeconds > 0f -> 0.8f                           // Brief: slight reduction
            else -> 1.0f                                           // Not in episode: no change
        }
    }
    
    /**
     * Calculate boost factor for being above personal baseline.
     * 
     * Tremors significantly above baseline are more clinically relevant.
     */
    private fun calculateBaselineBoost(baselineMultiplier: Float): Float {
        return when {
            baselineMultiplier >= 3.0f -> 1.3f   // 3x+ baseline: significant boost
            baselineMultiplier >= 2.0f -> 1.15f  // 2x+ baseline: moderate boost
            baselineMultiplier >= 1.5f -> 1.05f  // 1.5x+ baseline: slight boost
            else -> 1.0f                          // At or below baseline: no boost
        }
    }
    
    /**
     * Classify severity into clinical categories.
     */
    fun classifySeverity(severity: Float): SeverityLevel {
        return when {
            severity < 0.5f -> SeverityLevel.NONE
            severity < 1.5f -> SeverityLevel.MINIMAL
            severity < 3.0f -> SeverityLevel.MILD
            severity < 5.0f -> SeverityLevel.MODERATE
            severity < 7.0f -> SeverityLevel.MODERATE_SEVERE
            else -> SeverityLevel.SEVERE
        }
    }
    
    /**
     * Clinical severity levels
     */
    enum class SeverityLevel(val displayName: String, val minValue: Float, val maxValue: Float) {
        NONE("None", 0f, 0.5f),
        MINIMAL("Minimal", 0.5f, 1.5f),
        MILD("Mild", 1.5f, 3.0f),
        MODERATE("Moderate", 3.0f, 5.0f),
        MODERATE_SEVERE("Moderate-Severe", 5.0f, 7.0f),
        SEVERE("Severe", 7.0f, 10.0f)
    }
    
    /**
     * Calculate a normalized severity (0-1) for display purposes.
     */
    fun normalizedSeverity(severity: Float): Float {
        return (severity / MAX_SEVERITY).coerceIn(0f, 1f)
    }
}
```

**File: app\src\main\java\com\opensource\tremorwatch\engine\TremorClassifier.kt**
```kotlin
package com.opensource.tremorwatch.engine

import timber.log.Timber

/**
 * Classifies detected tremors into clinical categories based on frequency,
 * activity state, and signal characteristics.
 * 
 * Tremor Types:
 * - RESTING: Present at rest, diminishes with movement (4-6 Hz, classic resting)
 * - POSTURAL: Present when holding position against gravity (4-12 Hz)
 * - KINETIC: Present during voluntary movement (variable frequency)
 * - ESSENTIAL: Action tremor, often postural/kinetic (4-12 Hz, typically 5-8 Hz)
 * - PHYSIOLOGICAL: Normal tremor, usually 8-12 Hz, low amplitude
 * - UNKNOWN: Cannot determine type from available data
 * 
 * Clinical significance:
 * - resting tremor: 4-6 Hz "pill-rolling", asymmetric, improves with action
 * - action tremor: 4-12 Hz, bilateral, worsens with action, family history common
 * - Physiological tremor: 8-12 Hz, enhanced by anxiety/caffeine/fatigue
 */
object TremorClassifier {
    
    /**
     * Tremor type classification result
     */
    enum class TremorType(val displayName: String, val description: String) {
        RESTING("Resting", "Present at rest, typical of resting tremor (4-6 Hz)"),
        POSTURAL("Postural", "Present when holding position (4-12 Hz)"),
        KINETIC("Kinetic", "Present during movement"),
        ESSENTIAL("Essential", "Action tremor, often bilateral (5-8 Hz)"),
        PHYSIOLOGICAL("Physiological", "Normal enhanced tremor (8-12 Hz)"),
        MIXED("Mixed", "Features of multiple tremor types"),
        UNKNOWN("Unknown", "Insufficient data for classification")
    }
    
    /**
     * Detailed classification result with confidence and reasoning
     */
    data class ClassificationResult(
        val primaryType: TremorType,
        val confidence: Float,           // 0-1 confidence in classification
        val secondaryType: TremorType?,  // Possible alternative classification
        val frequencyHz: Float,
        val isResting: Boolean,
        val reasoning: String            // Human-readable explanation
    )
    
    // Frequency band definitions (Hz)
    private const val resting_LOW = 4.0f
    private const val resting_HIGH = 6.0f
    private const val ESSENTIAL_TYPICAL_LOW = 5.0f
    private const val ESSENTIAL_TYPICAL_HIGH = 8.0f
    private const val POSTURAL_LOW = 4.0f
    private const val POSTURAL_HIGH = 12.0f
    private const val PHYSIOLOGICAL_LOW = 8.0f
    private const val PHYSIOLOGICAL_HIGH = 12.0f
    
    // Activity state thresholds
    private const val RESTING_POWER_THRESHOLD = 10.0f
    private const val HIGH_ACTIVITY_THRESHOLD = 50.0f
    
    // Classification thresholds
    private const val HIGH_CONFIDENCE_THRESHOLD = 0.7f
    private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.5f
    
    /**
     * Classify a detected tremor based on frequency and activity characteristics.
     * 
     * @param dominantFrequency Dominant frequency from FFT analysis (Hz)
     * @param totalPower Total power from FFT (indicates activity level)
     * @param bandRatio Ratio of tremor band power to total power
     * @param confidence Detection confidence from TremorFFT
     * @param accelMagnitude Accelerometer magnitude (for activity detection)
     * @return ClassificationResult with type and confidence
     */
    fun classify(
        dominantFrequency: Float,
        totalPower: Float,
        bandRatio: Float,
        confidence: Float,
        accelMagnitude: Float = 0f
    ): ClassificationResult {
        
        // Determine activity state
        val isResting = totalPower < RESTING_POWER_THRESHOLD
        val isHighActivity = totalPower > HIGH_ACTIVITY_THRESHOLD || accelMagnitude > 2.0f
        
        // Invalid frequency - can't classify
        if (dominantFrequency < 3.0f || dominantFrequency > 15.0f) {
            return ClassificationResult(
                primaryType = TremorType.UNKNOWN,
                confidence = 0f,
                secondaryType = null,
                frequencyHz = dominantFrequency,
                isResting = isResting,
                reasoning = "Frequency ${dominantFrequency}Hz outside tremor range (3-15 Hz)"
            )
        }
        
        // Classify based on frequency and activity state
        return when {
            // Classic resting tremor pattern
            isResting && dominantFrequency in resting_LOW..resting_HIGH -> {
                val RESTINGsConfidence = calculaterestingConfidence(
                    dominantFrequency, bandRatio, isResting, confidence
                )
                ClassificationResult(
                    primaryType = TremorType.RESTING,
                    confidence = RESTINGsConfidence,
                    secondaryType = if (RESTINGsConfidence < HIGH_CONFIDENCE_THRESHOLD) TremorType.ESSENTIAL else null,
                    frequencyHz = dominantFrequency,
                    isResting = true,
                    reasoning = "Resting tremor at ${String.format("%.1f", dominantFrequency)}Hz - consistent with resting pattern"
                )
            }
            
            // Physiological tremor (high frequency, often during activity)
            dominantFrequency in PHYSIOLOGICAL_LOW..PHYSIOLOGICAL_HIGH && bandRatio < 0.15f -> {
                ClassificationResult(
                    primaryType = TremorType.PHYSIOLOGICAL,
                    confidence = 0.6f,
                    secondaryType = TremorType.ESSENTIAL,
                    frequencyHz = dominantFrequency,
                    isResting = isResting,
                    reasoning = "High frequency (${String.format("%.1f", dominantFrequency)}Hz) with low band ratio - likely physiological"
                )
            }
            
            // action tremor pattern (action tremor, 5-8 Hz typical)
            !isResting && dominantFrequency in ESSENTIAL_TYPICAL_LOW..ESSENTIAL_TYPICAL_HIGH -> {
                val essentialConfidence = calculateEssentialConfidence(
                    dominantFrequency, bandRatio, isResting, confidence
                )
                ClassificationResult(
                    primaryType = TremorType.ESSENTIAL,
                    confidence = essentialConfidence,
                    secondaryType = if (dominantFrequency <= 6f) TremorType.POSTURAL else null,
                    frequencyHz = dominantFrequency,
                    isResting = false,
                    reasoning = "Action tremor at ${String.format("%.1f", dominantFrequency)}Hz - consistent with action tremor"
                )
            }
            
            // Postural tremor (holding position, broad frequency range)
            !isResting && !isHighActivity && dominantFrequency in POSTURAL_LOW..POSTURAL_HIGH -> {
                ClassificationResult(
                    primaryType = TremorType.POSTURAL,
                    confidence = 0.5f + (bandRatio * 0.3f),
                    secondaryType = TremorType.ESSENTIAL,
                    frequencyHz = dominantFrequency,
                    isResting = false,
                    reasoning = "Tremor during postural hold at ${String.format("%.1f", dominantFrequency)}Hz"
                )
            }
            
            // Kinetic tremor (during high activity/movement)
            isHighActivity && dominantFrequency in 3f..12f -> {
                ClassificationResult(
                    primaryType = TremorType.KINETIC,
                    confidence = 0.4f + (confidence * 0.3f),
                    secondaryType = TremorType.ESSENTIAL,
                    frequencyHz = dominantFrequency,
                    isResting = false,
                    reasoning = "Tremor during active movement at ${String.format("%.1f", dominantFrequency)}Hz"
                )
            }
            
            // Resting but higher frequency - could be mixed or essential at rest
            isResting && dominantFrequency in 6f..12f -> {
                ClassificationResult(
                    primaryType = TremorType.MIXED,
                    confidence = 0.4f,
                    secondaryType = TremorType.ESSENTIAL,
                    frequencyHz = dominantFrequency,
                    isResting = true,
                    reasoning = "Resting tremor at ${String.format("%.1f", dominantFrequency)}Hz - atypical frequency for pure resting"
                )
            }
            
            // Default case
            else -> {
                ClassificationResult(
                    primaryType = TremorType.UNKNOWN,
                    confidence = 0.2f,
                    secondaryType = null,
                    frequencyHz = dominantFrequency,
                    isResting = isResting,
                    reasoning = "Unable to classify: freq=${String.format("%.1f", dominantFrequency)}Hz, resting=$isResting"
                )
            }
        }
    }
    
    /**
     * Calculate confidence for resting tremor classification
     */
    private fun calculaterestingConfidence(
        frequency: Float,
        bandRatio: Float,
        isResting: Boolean,
        detectionConfidence: Float
    ): Float {
        var confidence = 0f
        
        // Frequency: 4-6 Hz is classic resting, 4.5-5.5 Hz is ideal
        confidence += when {
            frequency in 4.5f..5.5f -> 0.35f  // Ideal range
            frequency in 4.0f..6.0f -> 0.25f  // Acceptable range
            else -> 0.1f
        }
        
        // Resting state is essential for resting tremor
        if (isResting) confidence += 0.25f
        
        // High band ratio suggests clean tremor signal
        confidence += (bandRatio * 0.2f).coerceAtMost(0.2f)
        
        // Factor in detection confidence
        confidence += (detectionConfidence * 0.2f)
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Calculate confidence for action tremor classification
     */
    private fun calculateEssentialConfidence(
        frequency: Float,
        bandRatio: Float,
        isResting: Boolean,
        detectionConfidence: Float
    ): Float {
        var confidence = 0f
        
        // Frequency: 5-8 Hz is typical action tremor
        confidence += when {
            frequency in 5.0f..8.0f -> 0.3f   // Typical range
            frequency in 4.0f..12.0f -> 0.2f  // Broader acceptable range
            else -> 0.1f
        }
        
        // action tremor is typically action tremor (NOT at rest)
        if (!isResting) confidence += 0.25f
        
        // Band ratio contribution
        confidence += (bandRatio * 0.25f).coerceAtMost(0.25f)
        
        // Detection confidence
        confidence += (detectionConfidence * 0.2f)
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Get a simple string label for the tremor type (for UI display)
     */
    fun getTypeLabel(result: ClassificationResult): String {
        return if (result.confidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
            result.primaryType.displayName
        } else {
            "${result.primaryType.displayName}?"
        }
    }
    
    /**
     * Get clinical interpretation text
     */
    fun getClinicalInterpretation(result: ClassificationResult): String {
        return when (result.primaryType) {
            TremorType.RESTING -> 
                "Resting tremor at ${String.format("%.1f", result.frequencyHz)}Hz. " +
                "This pattern is characteristic of resting tremor. " +
                "Track if it diminishes during voluntary movement."
            
            TremorType.ESSENTIAL ->
                "Action tremor at ${String.format("%.1f", result.frequencyHz)}Hz. " +
                "This pattern is consistent with action tremor. " +
                "Often bilateral and may worsen with stress or caffeine."
            
            TremorType.POSTURAL ->
                "Postural tremor detected while holding position. " +
                "Common in action tremor and enhanced physiological tremor."
            
            TremorType.KINETIC ->
                "Tremor detected during movement. " +
                "May indicate cerebellar involvement or action tremor component."
            
            TremorType.PHYSIOLOGICAL ->
                "High-frequency tremor (${String.format("%.1f", result.frequencyHz)}Hz) with low intensity. " +
                "Likely enhanced physiological tremor. Often related to fatigue, anxiety, or caffeine."
            
            TremorType.MIXED ->
                "Tremor with mixed characteristics. " +
                "Pattern doesn't fit a single tremor type clearly."
            
            TremorType.UNKNOWN ->
                "Unable to classify this tremor pattern. " +
                "May need more data or clearer signal."
        }
    }
}
```

**File: shared\src\main\java\com\opensource\tremorwatch\shared\models\TremorDetectionConfig.kt**
```kotlin
package com.opensource.tremorwatch.shared.models

import kotlinx.serialization.*
import kotlinx.serialization.json.Json

/**
 * Configuration for tremor detection algorithm.
 * Synced from phone to watch via Wearable Data Layer.
 *
 * Thread-safe when accessed with @Volatile wrapper.
 * Immutable data class ensures safe concurrent access.
 *
 * @property profileName Name of this configuration profile
 * @property profileDescription User-friendly description
 * @property version Schema version for migration support (current: 1)
 */
@Serializable
data class TremorDetectionConfig(
    // === Profile Metadata ===
    val profileName: String = "Default",
    val profileDescription: String = "Standard tremor detection settings",
    val version: Int = CURRENT_VERSION,

    // === Frequency Detection ===
    /** Lower bound of resting tremor frequency band (Hz). Typical Resting Tremor: 4-6Hz */
    val restingBandLowHz: Float = 4.0f,
    /** Upper bound of resting tremor frequency band (Hz) */
    val restingBandHighHz: Float = 6.0f,
    /** Lower bound of active/action tremor frequency band (Hz) */
    val activeBandLowHz: Float = 4.0f,
    /** Upper bound of active/action tremor frequency band (Hz). Extends to ~12Hz for action tremors */
    val activeBandHighHz: Float = 12.0f,
    /** Minimum frequency to consider as potential tremor (filters out drift) */
    val minFrequencyHz: Float = 4.0f,

    // === Power Thresholds ===
    /** Minimum spectral power to register as tremor (filters noise) */
    val minTremorPower: Float = 0.001f,
    /** Power threshold specific to resting tremor detection */
    val restingPowerThreshold: Float = 10.0f,

    // === Band Ratio Thresholds ===
    /** Minimum ratio of tremor-band power to total power (general threshold) */
    val minBandRatio: Float = 0.04f,
    /** Band ratio threshold for resting tremor (can be more sensitive) */
    val restingMinBandRatio: Float = 0.05f,
    /** Band ratio threshold for active tremor (typically needs higher ratio) */
    val activeMinBandRatio: Float = 0.10f,

    // === Severity Settings ===
    /** Floor value for severity calculation (prevents zero/negative severity) */
    val severityFloor: Float = 0.005f,
    /** Severity threshold above which tremor is considered high-energy */
    val highEnergySeverityThreshold: Float = 1.0f,
    /** Band ratio threshold for high-energy tremor classification */
    val highEnergyBandRatioThreshold: Float = 0.04f,

    // === Temporal Smoothing ===
    /** Minimum consecutive samples needed to confirm tremor episode (reduces false positives) */
    val minEpisodeDurationSamples: Int = 3,
    /** Maximum gap in samples that can be bridged in a tremor episode (temporal coherence) */
    val maxGapSamples: Int = 2,

    // === Confidence Calculation ===
    /** Minimum confidence score required to register detection (0-1 range) */
    val confidenceThreshold: Float = 0.35f,
    /** Weight of band ratio in confidence calculation */
    val bandRatioWeight: Float = 0.30f,
    /** Weight of peak prominence in confidence calculation */
    val peakProminenceWeight: Float = 0.15f,
    /** Weight of frequency validation in confidence calculation */
    val frequencyValidationWeight: Float = 0.25f,

    // === Movement Classification ===
    /** Power threshold below which tremor is classified as "low" */
    val lowTremorThreshold: Float = 0.3f,
    /** Power threshold above which movement is classified as high activity (not tremor) */
    val highActivityThreshold: Float = 5.0f
) {
    init {
        // Validate logical consistency of parameters
        require(profileName.isNotBlank()) { "Profile name cannot be blank" }
        require(version > 0) { "Version must be positive" }

        // Frequency validation
        require(restingBandLowHz > 0) { "Resting band low Hz must be positive" }
        require(restingBandLowHz < restingBandHighHz) {
            "Resting band low ($restingBandLowHz Hz) must be less than high ($restingBandHighHz Hz)"
        }
        require(activeBandLowHz > 0) { "Active band low Hz must be positive" }
        require(activeBandLowHz < activeBandHighHz) {
            "Active band low ($activeBandLowHz Hz) must be less than high ($activeBandHighHz Hz)"
        }
        require(minFrequencyHz > 0) { "Minimum frequency must be positive" }
        require(restingBandHighHz <= 20) { "Frequency bands should be <= 20 Hz (Nyquist limit ~25Hz)" }
        require(activeBandHighHz <= 20) { "Frequency bands should be <= 20 Hz (Nyquist limit ~25Hz)" }

        // Power validation
        require(minTremorPower >= 0) { "Minimum tremor power must be non-negative" }
        require(restingPowerThreshold >= 0) { "Resting power threshold must be non-negative" }

        // Ratio validation
        require(minBandRatio in 0.0f..1.0f) { "Band ratio must be in range [0, 1]" }
        require(restingMinBandRatio in 0.0f..1.0f) { "Resting band ratio must be in range [0, 1]" }
        require(activeMinBandRatio in 0.0f..1.0f) { "Active band ratio must be in range [0, 1]" }

        // Severity validation
        require(severityFloor >= 0) { "Severity floor must be non-negative" }
        require(highEnergySeverityThreshold > 0) { "High energy threshold must be positive" }
        require(highEnergyBandRatioThreshold in 0.0f..1.0f) {
            "High energy band ratio threshold must be in range [0, 1]"
        }

        // Temporal validation
        require(minEpisodeDurationSamples > 0) { "Minimum episode duration must be positive" }
        require(maxGapSamples >= 0) { "Max gap samples must be non-negative" }

        // Confidence validation
        require(confidenceThreshold in 0.0f..1.0f) { "Confidence threshold must be in range [0, 1]" }
        require(bandRatioWeight >= 0) { "Band ratio weight must be non-negative" }
        require(peakProminenceWeight >= 0) { "Peak prominence weight must be non-negative" }
        require(frequencyValidationWeight >= 0) { "Frequency validation weight must be non-negative" }

        // Classification validation
        require(lowTremorThreshold >= 0) { "Low tremor threshold must be non-negative" }
        require(highActivityThreshold > lowTremorThreshold) {
            "High activity threshold must be greater than low tremor threshold"
        }
    }

    /**
     * Serialize to JSON string for export/storage.
     * Uses kotlinx.serialization for type safety.
     */
    fun toJson(): String {
        return json.encodeToString(serializer(), this)
    }

    companion object {
        /** Current schema version - increment when adding/removing fields */
        const val CURRENT_VERSION = 1

        /** JSON serializer with pretty printing */
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true // Allow parsing old versions
        }

        /**
         * Parse from JSON string with input sanitization.
         * Validates file size and handles migration from older versions.
         *
         * @param jsonString JSON configuration (max 100KB)
         * @throws IllegalArgumentException if JSON is invalid or too large
         * @throws SerializationException if JSON format is incorrect
         */
        fun fromJson(jsonString: String): TremorDetectionConfig {
            // Input sanitization: limit file size
            require(jsonString.length < 100_000) {
                "Config JSON too large (${jsonString.length} bytes). Max 100KB."
            }

            require(jsonString.isNotBlank()) { "Config JSON cannot be blank" }

            try {
                val config = json.decodeFromString<TremorDetectionConfig>(jsonString)

                // Version migration logic
                return when (config.version) {
                    CURRENT_VERSION -> config
                    // Future: handle older versions
                    // 0 -> migrateFromV0(config)
                    else -> {
                        // Unknown version - accept but warn (logged by caller)
                        config.copy(version = CURRENT_VERSION)
                    }
                }
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Invalid config JSON format: ${e.message}", e)
            }
        }

        /**
         * Validates a JSON string without fully deserializing.
         * Useful for pre-validation before import.
         */
        fun validateJson(jsonString: String): Boolean {
            return try {
                fromJson(jsonString)
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Built-in presets optimized for different tremor types.
         * These serve as starting points for customization.
         */
        val PRESETS = mapOf(
            "Default" to TremorDetectionConfig(),

            "Custom" to TremorDetectionConfig(
                profileName = "Custom",
                profileDescription = "Starting point for creating your own custom settings"
            ),

            "Sensitive" to TremorDetectionConfig(
                profileName = "Sensitive",
                profileDescription = "Lower thresholds for detecting subtle tremors. May increase false positives.",
                minBandRatio = 0.02f,
                severityFloor = 0.002f,
                confidenceThreshold = 0.25f,
                minEpisodeDurationSamples = 2
            ),

            "Strict" to TremorDetectionConfig(
                profileName = "Strict",
                profileDescription = "Higher thresholds to reduce false positives. May miss subtle tremors.",
                minBandRatio = 0.08f,
                severityFloor = 0.01f,
                confidenceThreshold = 0.50f,
                minEpisodeDurationSamples = 5
            ),

            "Resting Tremor" to TremorDetectionConfig(
                profileName = "Resting Tremor",
                profileDescription = "Optimized for 4-6Hz resting tremor typical of resting tremor conditions",
                restingBandHighHz = 6.5f,
                activeBandHighHz = 8.0f,
                restingMinBandRatio = 0.04f
            ),

            "Action Tremor" to TremorDetectionConfig(
                profileName = "Action Tremor",
                profileDescription = "Optimized for 5-8Hz action tremor typical of Action Tremor",
                restingBandLowHz = 5.0f,
                restingBandHighHz = 8.0f,
                activeBandLowHz = 5.0f,
                activeBandHighHz = 10.0f
            )
        )
    }
}
```

**File: app\src\main\java\com\opensource\tremorwatch\constants\MonitoringConstants.kt**
```kotlin
package com.opensource.tremorwatch.constants

/**
 * Constants for tremor monitoring and data collection.
 *
 * These constants control sensor sampling, data batching, and processing behavior.
 * Grouped by functionality for better organization and maintainability.
 */
object MonitoringConstants {
    
    // ====================== SENSOR PROCESSING ======================
    
    /**
     * Sample interval in milliseconds.
     * Battery-optimized: 1 Hz (1000ms) instead of 20 Hz (50ms) for 95% battery savings.
     * Still sufficient for tremor detection (tremors are 4-12 Hz).
     */
    const val SAMPLE_INTERVAL_MS = 1000L  // 1 Hz - battery optimized
    
    /**
     * Number of samples per batch.
     * Battery-optimized: 600 samples = 10 minutes at 1 Hz sampling.
     * Creates batches every 10 minutes for reasonable data visibility while maintaining battery efficiency.
     */
    const val BATCH_SIZE = 600  // 10 minutes of data at 1 Hz (was 3600 = 1 hour, too long for user feedback)
    
    /**
     * Maximum buffer size before overflow protection kicks in.
     * When buffer exceeds this size, oldest samples are dropped.
     */
    const val MAX_BUFFER_SIZE = BATCH_SIZE * 2
    
    // ====================== TREMOR DETECTION ======================
    
    /**
     * Minimum gyroscope magnitude threshold for tremor detection (rad/s).
     * Values below this are considered noise and ignored.
     */
    const val LOW_TREMOR_THRESHOLD = 0.02f
    
    /**
     * Maximum gyroscope magnitude threshold for tremor detection (rad/s).
     * Values above this are considered intentional movement, not tremor.
     */
    const val HIGH_ACTIVITY_THRESHOLD = 2.0f
    
    // ====================== FFT ANALYSIS ======================
    
    /**
     * FFT window size (number of samples).
     * Phase 4: Increased to 64 for better frequency resolution (opus45 review).
     * 64 samples at 50 Hz = 1.28 seconds, giving 0.78 Hz resolution.
     * This allows distinguishing 4 Hz vs 5 Hz tremors (resting vs Essential).
     */
    const val FFT_WINDOW_SIZE = 64  // Increased from 32 for better frequency resolution
    
    /**
     * FFT sample rate in Hz.
     * Uses actual sensor rate (~50 Hz from SENSOR_DELAY_GAME) for FFT window.
     * This allows detection of tremor frequencies (4-12 Hz) - Nyquist limit at 50 Hz is 25 Hz,
     * which fully covers all tremor ranges (4-6 Hz resting, 5-8 Hz Essential, 8-12 Hz Physiological).
     * Note: We save samples at 1 Hz for battery, but FFT window fills at sensor rate (~50 Hz).
     */
    const val FFT_SAMPLE_RATE = 50f  // ~50 Hz - matches SENSOR_DELAY_GAME rate
    
    /**
     * Only run FFT every N samples to reduce CPU usage.
     * Phase 4: Reduced to 5 for more responsive tremor detection (opus45 review).
     * At 50 Hz sensor rate, this means FFT every 100ms instead of every 200ms.
     */
    const val FFT_PROCESSING_INTERVAL = 5  // Reduced from 10 for better responsiveness
    
    // ====================== WEAR DETECTION ======================
    
    /**
     * Debounce time for wear state changes (milliseconds).
     * Requires sustained off-body detection for this duration before pausing.
     */
    const val WEAR_STATE_DEBOUNCE_MS = 5000L
    
    /**
     * Minimum number of consecutive off-body readings required before starting debounce timer.
     * Prevents false triggers from noisy sensor readings.
     */
    const val MIN_OFF_BODY_READINGS = 3
    
    // ====================== UPLOAD & STORAGE ======================
    
    /**
     * Maximum number of batches to process per upload operation.
     * Prevents ANR (Application Not Responding) errors during large uploads.
     */
    const val MAX_BATCHES_PER_UPLOAD = 20
    
    /**
     * Maximum number of pending batches to store offline.
     * Battery-optimized: 144 batches = 24 hours of 10-minute batches.
     */
    const val MAX_PENDING_BATCHES = 144  // 24 hours of 10-minute batches (144 batches/day)
    
    // ====================== SERVICE LIFECYCLE ======================
    
    /**
     * Interval for periodic status updates (milliseconds).
     * Battery-optimized: Reduced frequency (30 minutes).
     */
    const val STATUS_UPDATE_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes - battery optimized
    
    /**
     * Interval for wake lock monitoring (milliseconds).
     * Battery-optimized: Less frequent checks (10 minutes) - only check if disrupted.
     */
    const val WAKELOCK_CHECK_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes - battery optimized
    
    /**
     * Window for tracking wake lock disruptions (milliseconds).
     * Used to detect if app is on Samsung's sleeping apps list.
     */
    const val DISRUPTION_CHECK_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    
    /**
     * Threshold for wake lock disruption count.
     * If wakelock is disabled this many times in the check window, app is likely on sleeping list.
     */
    const val DISRUPTION_THRESHOLD = 5
}
```

**File: app\src\main\java\com\opensource\tremorwatch\constants\BatteryOptimizedConstants.kt**
```kotlin
package com.opensource.tremorwatch.constants

/**
 * Battery-optimized constants for hourly summary mode.
 * 
 * Designed for users who don't need live data but want hourly tremor summaries.
 * Reduces battery usage by 80-90% compared to high-frequency monitoring.
 */
object BatteryOptimizedConstants {
    
    // ====================== SENSOR PROCESSING (BATTERY OPTIMIZED) ======================
    
    /**
     * Reduced sample interval for battery efficiency.
     * 1 Hz (1 sample per second) is sufficient for tremor detection.
     * Tremors are typically 4-12 Hz, so 1 Hz sampling can still detect them.
     */
    const val SAMPLE_INTERVAL_MS = 1000L  // 1 Hz instead of 20 Hz (95% reduction)
    
    /**
     * Smaller batch size for hourly aggregation.
     * With 1 Hz sampling, 3600 samples = 1 hour of data.
     */
    const val BATCH_SIZE = 3600  // 1 hour of samples at 1 Hz
    
    /**
     * Maximum buffer size - enough for 2 hours of data.
     */
    const val MAX_BUFFER_SIZE = BATCH_SIZE * 2
    
    // ====================== TREMOR DETECTION (UNCHANGED) ======================
    
    const val LOW_TREMOR_THRESHOLD = 0.02f
    const val HIGH_ACTIVITY_THRESHOLD = 2.0f
    
    // ====================== FFT ANALYSIS (OPTIMIZED) ======================
    
    /**
     * Reduced FFT window size for lower CPU usage.
     * Still sufficient for tremor detection (4-12 Hz range).
     */
    const val FFT_WINDOW_SIZE = 32  // Reduced from 64 (50% reduction)
    
    /**
     * Reduced FFT sample rate to match new sampling frequency.
     */
    const val FFT_SAMPLE_RATE = 1f  // 1 Hz instead of 20 Hz
    
    /**
     * Only run FFT every N samples to reduce CPU usage.
     */
    const val FFT_PROCESSING_INTERVAL = 10  // Process FFT every 10 samples instead of every sample
    
    // ====================== WEAR DETECTION (UNCHANGED) ======================
    
    const val WEAR_STATE_DEBOUNCE_MS = 5000L
    const val MIN_OFF_BODY_READINGS = 3
    
    // ====================== UPLOAD & STORAGE (HOURLY MODE) ======================
    
    /**
     * Upload interval for hourly summaries.
     */
    const val UPLOAD_INTERVAL_MINUTES = 60  // Once per hour
    
    /**
     * Maximum batches to store (24 hours worth).
     */
    const val MAX_PENDING_BATCHES = 24  // 24 hourly summaries
    
    /**
     * Maximum batches per upload (should be 1 for hourly mode).
     */
    const val MAX_BATCHES_PER_UPLOAD = 1
    
    // ====================== SERVICE LIFECYCLE (OPTIMIZED) ======================
    
    /**
     * Reduced status update frequency.
     */
    const val STATUS_UPDATE_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes instead of 5
    
    /**
     * Reduced wake lock check frequency.
     */
    const val WAKELOCK_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes instead of 30 seconds
    
    /**
     * Heartbeat interval - only when transmitting or hourly.
     */
    const val HEARTBEAT_INTERVAL_MS = 60 * 60 * 1000L // 1 hour instead of 15 minutes
    
    /**
     * Watchdog interval - less frequent checks.
     */
    const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes instead of 2 minutes
    
    /**
     * Batch retry interval - only during upload window.
     */
    const val BATCH_RETRY_INTERVAL_MS = 60 * 60 * 1000L // 1 hour (same as upload)
}
```

**File: app\src\main\java\com\opensource\tremorwatch\config\ConfigDataListener.kt**
```kotlin
package com.opensource.tremorwatch.config

import android.content.Context
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import com.google.android.gms.wearable.*
import timber.log.Timber

/**
 * Listens for configuration updates from phone via Wearable Data Layer.
 *
 * Thread-safe implementation:
 * - Uses callback pattern with thread-safe config delivery
 * - Caches config in SharedPreferences for persistence
 * - Handles errors gracefully with fallback to cached config
 *
 * @param context Application context
 * @param onConfigChanged Callback invoked when config changes (called on main thread)
 */
class ConfigDataListener(
    private val context: Context,
    private val onConfigChanged: (TremorDetectionConfig) -> Unit
) : DataClient.OnDataChangedListener {

    companion object {
        private const val DATA_PATH = "/tremor_detection_config"
        private const val PREFS_NAME = "tremor_detection_config_cache"
        private const val KEY_CONFIG = "cached_config"
        private const val KEY_LAST_UPDATE = "last_update_time"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dataClient = Wearable.getDataClient(context)

    /**
     * Register this listener and load cached config.
     * Should be called when the service/activity starts.
     */
    fun register() {
        dataClient.addListener(this)
        Timber.i("ConfigDataListener registered")

        // Load and apply cached config on startup
        getCachedConfig()?.let { cachedConfig ->
            Timber.i("Applying cached config on startup: ${cachedConfig.profileName}")
            onConfigChanged(cachedConfig)
        } ?: run {
            // No cached config, use default
            Timber.i("No cached config found, using default")
            val defaultConfig = TremorDetectionConfig()
            cacheConfig(defaultConfig)
            onConfigChanged(defaultConfig)
        }
    }

    /**
     * Unregister this listener.
     * Should be called when the service/activity stops.
     */
    fun unregister() {
        dataClient.removeListener(this)
        Timber.i("ConfigDataListener unregistered")
    }

    /**
     * Called when data changes on the Wearable Data Layer.
     * Processes config updates from phone.
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                processDataChange(event.dataItem)
            }
        }
        dataEvents.release()
    }

    /**
     * Process a single data item change
     */
    private fun processDataChange(dataItem: DataItem) {
        if (dataItem.uri.path != DATA_PATH) {
            return // Not a config update
        }

        try {
            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            val configJson = dataMap.getString("config_json")
            val timestamp = dataMap.getLong("timestamp", 0L)
            val profileName = dataMap.getString("profile_name") ?: "Unknown"
            val version = dataMap.getInt("version", 1)

            if (configJson.isNullOrBlank()) {
                Timber.w("Received empty config JSON, ignoring")
                return
            }

            // Validate and parse config
            val config = try {
                TremorDetectionConfig.fromJson(configJson)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse config, falling back to cached/default")
                // Return cached config or default on parse error
                getCachedConfig() ?: TremorDetectionConfig()
            }

            // Cache the new config for persistence
            cacheConfig(config, timestamp)

            Timber.i(
                "Received new config: '$profileName' (v$version) at ${
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(timestamp))
                }"
            )

            // Notify callback (thread-safe - callback handles thread dispatch)
            onConfigChanged(config)

        } catch (e: Exception) {
            Timber.e(e, "Error processing config data change")
            // On error, continue using current config (no crash)
        }
    }

    /**
     * Get the cached configuration.
     * Returns null if no config is cached or parsing fails.
     */
    fun getCachedConfig(): TremorDetectionConfig? {
        val json = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            TremorDetectionConfig.fromJson(json)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse cached config")
            null
        }
    }

    /**
     * Get the timestamp of the last config update
     */
    fun getLastUpdateTime(): Long {
        return prefs.getLong(KEY_LAST_UPDATE, 0L)
    }

    /**
     * Cache the configuration for persistence across restarts
     */
    private fun cacheConfig(config: TremorDetectionConfig, timestamp: Long = System.currentTimeMillis()) {
        try {
            prefs.edit()
                .putString(KEY_CONFIG, config.toJson())
                .putLong(KEY_LAST_UPDATE, timestamp)
                .apply()
            Timber.d("Config cached: ${config.profileName}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cache config")
            // Non-fatal - config still applied in memory
        }
    }

    /**
     * Manually set a configuration (for testing or fallback)
     */
    fun setConfig(config: TremorDetectionConfig) {
        cacheConfig(config)
        onConfigChanged(config)
        Timber.i("Config manually set: ${config.profileName}")
    }
}
```

**File: shared\src\main\java\com\opensource\tremorwatch\shared\models\TremorData.kt**
```kotlin
package com.opensource.tremorwatch.shared.models

import org.json.JSONObject

/**
 * Represents a single tremor data sample.
 * This is shared between watch and phone apps.
 */
data class TremorData(
    val timestamp: Long,           // Unix timestamp in milliseconds
    val severity: Double,          // Tremor severity value
    val tremorCount: Int,          // Number of tremors detected
    val metadata: Map<String, Any> = emptyMap()  // Additional metadata
) {
    /**
     * Convert to JSON for Data Layer transmission
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("severity", severity)
            put("tremor_count", tremorCount)
            if (metadata.isNotEmpty()) {
                put("metadata", JSONObject(metadata))
            }
        }
    }

    companion object {
        /**
         * Parse from JSON received via Data Layer with improved error handling
         */
        fun fromJson(json: JSONObject): TremorData {
            try {
                val timestamp = json.optLong("timestamp", 0L)
                if (timestamp == 0L) {
                    throw IllegalArgumentException("Missing or invalid timestamp in TremorData")
                }

                val severity = json.optDouble("severity", Double.NaN)
                if (severity.isNaN()) {
                    throw IllegalArgumentException("Missing or invalid severity in TremorData")
                }

                val tremorCount = json.optInt("tremor_count", 0)

                val metadata = mutableMapOf<String, Any>()
                if (json.has("metadata")) {
                    try {
                        val metaJson = json.optJSONObject("metadata")
                        if (metaJson != null) {
                            metaJson.keys().forEach { key ->
                                try {
                                    metadata[key] = metaJson.get(key)
                                } catch (e: Exception) {
                                    // Skip invalid metadata keys
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue with empty metadata if parsing fails
                    }
                }

                return TremorData(
                    timestamp = timestamp,
                    severity = severity,
                    tremorCount = tremorCount,
                    metadata = metadata
                )
            } catch (e: Exception) {
                if (e is IllegalArgumentException) {
                    throw e
                }
                throw IllegalArgumentException("Failed to parse TremorData from JSON: ${e.message}. JSON: ${json.toString().take(200)}", e)
            }
        }
    }
}
```

**File: app\src\main\java\com\opensource\tremorwatch\service\TremorService.kt**
```kotlin
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
import com.opensource.tremorwatch.receivers.RatingPromptReceiver
import com.opensource.tremorwatch.engine.TremorMonitoringEngine
import com.opensource.tremorwatch.constants.MonitoringConstants
import com.opensource.tremorwatch.data.PreferencesRepository
import com.opensource.tremorwatch.data.CalibrationCaptureManager
import com.opensource.tremorwatch.data.CalibrationSample
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
                Timber.i("â˜… Heartbeat sent to phone (uptime: ${uptime / 1000}s, state: $monitoringState, battery_opt: $watchBatteryOptimized)")
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
                Timber.i("â˜…â˜… Battery optimization state changed: $lastBatteryOptimizationState -> $watchBatteryOptimized - sending immediate heartbeat")
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
            Timber.w("â˜…â˜…â˜… Starting WakeLock monitor - checking every ${MonitoringConstants.WAKELOCK_CHECK_INTERVAL_MS / 1000}s")
            wakeLockMonitorHandler.post(wakeLockMonitorRunnable)
        }

        /**
         * Start periodic battery optimization monitoring.
         * Checks every 60 seconds for changes and sends immediate heartbeat on change.
         */
        private fun startBatteryOptMonitor() {
            Timber.i("â˜…â˜…â˜… Starting battery optimization monitor - checking every 60s")
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
                Timber.e("â˜…â˜…â˜… CRITICAL: WakeLock was DISABLED by system (Samsung FreecessController) - RE-ACQUIRING NOW!")

                // Track disruption for sleeping apps detection
                trackWakeLockDisruption(now)

                try {
                    wl.acquire()
                    Timber.w("â˜…â˜…â˜… WakeLock successfully RE-ACQUIRED - fighting Samsung freeze")
                } catch (e: Exception) {
                    Timber.e("â˜…â˜…â˜… FAILED to re-acquire wakelock: ${e.message}", e)
                }
            } else {
                // Wakelock still held - no action needed (battery optimized)
                // Only refresh if we detect it's about to expire or has been held too long
                // Since we use PARTIAL_WAKE_LOCK without timeout, it should stay held
                Timber.v("WakeLock still held - no refresh needed (battery optimized)")
            }
        } ?: run {
            Timber.e("â˜…â˜…â˜… CRITICAL: WakeLock is NULL - this should never happen!")
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
                Timber.e("â˜…â˜…â˜… WARNING: TremorWatch may be on Samsung's SLEEPING APPS list!")
                Timber.e("â˜…â˜…â˜… Wakelock disabled $wakeLockDisruptionCount times in ${MonitoringConstants.DISRUPTION_CHECK_WINDOW_MS / 60000} minutes")
            Timber.e("â˜…â˜…â˜… ACTION REQUIRED: Remove TremorWatch from Samsung's sleeping apps list on your PHONE")

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
                title = "âš ï¸ TremorWatch - ACTION REQUIRED",
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

        // Set up rating prompt alarm for periodic subjective rating prompts
        scheduleRatingPromptAlarm()

            // Initialize preferences repository
            preferencesRepository = PreferencesRepository(this)

            // Initialize calibration capture manager for subjective rating data collection
            calibrationCaptureManager = CalibrationCaptureManager(this)
            
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
                
                // If calibration is active, record samples for subjective rating calibration
                if (::calibrationCaptureManager.isInitialized && calibrationCaptureManager.isCapturing()) {
                    for (data in batch) {
                        val sample = CalibrationSample(
                            timestamp = data.timestamp,
                            x = data.x,
                            y = data.y,
                            z = data.z,
                            magnitude = data.magnitude,
                            dominantFrequency = data.dominantFrequency,
                            tremorBandPower = data.tremorBandPower,
                            totalPower = data.totalPower,
                            bandRatio = data.bandRatio,
                            peakProminence = data.peakProminence,
                            confidence = data.confidence,
                            severity = data.severity.toDouble(),
                            isWorn = data.isWorn,
                            isCharging = data.isCharging
                        )
                        calibrationCaptureManager.recordSample(sample)
                    }
                }
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
            Timber.w("â˜…â˜…â˜… WakeLock acquired (PARTIAL_WAKE_LOCK | ON_AFTER_RELEASE) - battery optimized")
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

        Timber.i("â˜…â˜…â˜… TremorService CREATED at ${System.currentTimeMillis()} - starting monitoring â˜…â˜…â˜…")
        
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
                Timber.i("â˜…â˜…â˜… Upload alarm scheduled (exact) - next upload in $intervalMinutes minutes at ~$triggerTimeFormatted")
                Timber.d("TremorWatch: Upload alarm scheduled - next upload in $intervalMinutes minutes")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Timber.w("â˜…â˜…â˜… Upload alarm scheduled (inexact) - next upload in ~$intervalMinutes minutes around $triggerTimeFormatted")
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Timber.i("â˜…â˜…â˜… Upload alarm scheduled (exact) - next upload in $intervalMinutes minutes at ~$triggerTimeFormatted")
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

    /**
     * Schedule periodic rating prompts.
     * 
     * Uses a 2-3 hour random interval to avoid predictable prompt timing.
     * The RatingPromptReceiver handles additional checks (active hours, daily limits, etc.).
     */
    private fun scheduleRatingPromptAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RatingPromptReceiver::class.java).apply {
            action = RatingPromptReceiver.ACTION_RATING_PROMPT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            3,  // Different request code from watchdog(0), upload(1), and batch retry(2)
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Random interval between 2-3 hours for natural prompting
        val baseIntervalMs = 2 * 60 * 60 * 1000L  // 2 hours
        val randomExtra = (0..60).random() * 60 * 1000L  // 0-60 minutes extra
        val intervalMs = baseIntervalMs + randomExtra
        val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMs

        // Calculate wall clock time for logging
        val triggerTime = System.currentTimeMillis() + intervalMs
        val triggerTimeFormatted = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(triggerTime))
        val intervalMinutes = intervalMs / (60 * 1000)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Timber.i("â˜…â˜…â˜… Rating prompt alarm scheduled (exact) - next prompt in $intervalMinutes minutes at ~$triggerTimeFormatted")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Timber.w("â˜…â˜…â˜… Rating prompt alarm scheduled (inexact) - next prompt in ~$intervalMinutes minutes around $triggerTimeFormatted")
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Timber.i("â˜…â˜…â˜… Rating prompt alarm scheduled (exact) - next prompt in $intervalMinutes minutes at ~$triggerTimeFormatted")
        }
    }

    private fun cancelRatingPromptAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RatingPromptReceiver::class.java).apply {
            action = RatingPromptReceiver.ACTION_RATING_PROMPT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Timber.d("Rating prompt alarm cancelled")
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
        Timber.w("â˜…â˜…â˜… MONITORING PAUSED: $reason â˜…â˜…â˜…")
        Timber.w("TremorWatch: â˜…â˜…â˜… Monitoring paused: $reason (data collection stopped)")
        
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
        Timber.i("â˜…â˜…â˜… MONITORING RESUMED: worn=${isWatchWorn}, charging=${isCharging} â˜…â˜…â˜…")
        Timber.i("TremorWatch: â˜…â˜…â˜… Monitoring resumed: worn=${isWatchWorn}, charging=${isCharging} (data collection active)")
        
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
        Timber.w("â˜…â˜…â˜… Service keepalive ping - last sample ${timeSinceLastPing / 1000}s ago")

        // Check battery optimization status
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isOptimized = !powerManager.isIgnoringBatteryOptimizations(packageName)
            if (isOptimized) {
                Timber.e("â˜…â˜…â˜… CRITICAL: Battery optimization ENABLED - expect data gaps!")
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
        Timber.i("â˜…â˜…â˜… TremorService DESTROYED at ${System.currentTimeMillis()} - uptime was ${uptime / 1000}s â˜…â˜…â˜…")

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
        val notificationText = text ?: "Uptime: $uptimeText â€¢ Batches sent: $batchesSent"

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
```

**File: app\src\main\java\com\opensource\tremorwatch\MainActivity.kt**
_MISSING FILE_

**File: phone\src\main\java\com\opensource\tremorwatch\phone\config\TremorConfigManager.kt**
```kotlin
package com.opensource.tremorwatch.phone.config

import android.content.Context
import android.content.SharedPreferences
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Manages tremor detection configuration profiles.
 * Handles storage, sync to watch, import/export, and error recovery.
 *
 * Features:
 * - Profile management (save/load/delete)
 * - Reliable sync to watch with retry logic
 * - Import/Export with validation
 * - Sync status tracking
 * - Error handling and fallback strategies
 */
class TremorConfigManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tremor_config", Context.MODE_PRIVATE)

    private val dataClient: DataClient = Wearable.getDataClient(context)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Retry configuration
    private val maxRetries = 3
    private val retryDelayMs = 1000L
    private val exponentialBackoffMultiplier = 2.0

    companion object {
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_PROFILES = "saved_profiles"
        private const val KEY_SYNC_STATUS = "sync_status"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val DATA_PATH = "/tremor_detection_config"
    }

    /**
     * Sync status for UI feedback
     */
    enum class SyncStatus {
        SYNCED,      // Successfully synced
        PENDING,     // Not yet synced or sync in progress
        FAILED,      // Sync failed after retries
        NOT_CONNECTED // Watch not connected
    }

    /**
     * Get the currently active configuration.
     * Falls back to default if none exists or parsing fails.
     */
    fun getActiveConfig(): TremorDetectionConfig {
        val json = prefs.getString(KEY_ACTIVE_PROFILE, null)
        return if (json != null) {
            try {
                TremorDetectionConfig.fromJson(json)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse active config, using default")
                TremorDetectionConfig() // Fallback to default
            }
        } else {
            TremorDetectionConfig()
        }
    }

    /**
     * Set and sync the active configuration.
     * Returns SyncStatus indicating success/failure.
     *
     * @param config The configuration to activate
     * @param forceSync If true, sync even if watch is disconnected (will queue for later)
     * @return SyncStatus indicating result
     */
    suspend fun setActiveConfig(
        config: TremorDetectionConfig,
        forceSync: Boolean = true
    ): SyncStatus {
        // Save to local storage first (always succeeds)
        prefs.edit().putString(KEY_ACTIVE_PROFILE, config.toJson()).apply()
        Timber.i("Active config saved: ${config.profileName}")

        // Attempt to sync to watch
        return if (forceSync) {
            val status = syncToWatch(config)
            updateSyncStatus(status)
            status
        } else {
            SyncStatus.PENDING
        }
    }

    /**
     * Get current sync status
     */
    fun getSyncStatus(): SyncStatus {
        val statusName = prefs.getString(KEY_SYNC_STATUS, SyncStatus.PENDING.name)
        return try {
            SyncStatus.valueOf(statusName ?: SyncStatus.PENDING.name)
        } catch (e: IllegalArgumentException) {
            SyncStatus.PENDING
        }
    }

    /**
     * Get last successful sync time
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    /**
     * Force a manual sync of the current active config to watch.
     * Useful when sync previously failed.
     */
    suspend fun forceSyncToWatch(): SyncStatus {
        val config = getActiveConfig()
        val status = syncToWatch(config)
        updateSyncStatus(status)
        return status
    }

    /**
     * Get list of saved profile names
     */
    fun getSavedProfileNames(): List<String> {
        val jsonArray = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        return try {
            val profiles: List<TremorDetectionConfig> = json.decodeFromString(jsonArray)
            profiles.map { it.profileName }
        } catch (e: SerializationException) {
            Timber.e(e, "Failed to parse profile list")
            emptyList()
        }
    }

    /**
     * Save current config as a named profile
     */
    fun saveProfile(config: TremorDetectionConfig) {
        val profiles = loadAllProfiles().toMutableMap()
        profiles[config.profileName] = config
        saveAllProfiles(profiles)
        Timber.i("Profile saved: ${config.profileName}")
    }

    /**
     * Load a saved profile by name.
     * Checks both user-saved profiles and built-in presets.
     */
    fun loadProfile(name: String): TremorDetectionConfig? {
        // Check user profiles first
        val userProfile = loadAllProfiles()[name]
        if (userProfile != null) return userProfile

        // Check built-in presets
        return TremorDetectionConfig.PRESETS[name]
    }

    /**
     * Delete a saved profile.
     * Cannot delete built-in presets.
     */
    fun deleteProfile(name: String): Boolean {
        // Prevent deleting built-in presets
        if (TremorDetectionConfig.PRESETS.containsKey(name)) {
            Timber.w("Cannot delete built-in preset: $name")
            return false
        }

        val profiles = loadAllProfiles().toMutableMap()
        val removed = profiles.remove(name)
        if (removed != null) {
            saveAllProfiles(profiles)
            Timber.i("Profile deleted: $name")
            return true
        }
        return false
    }

    /**
     * Export config to JSON file
     */
    fun exportToFile(config: TremorDetectionConfig, file: File) {
        try {
            file.writeText(config.toJson())
            Timber.i("Config exported to: ${file.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to export config to file")
            throw e
        }
    }

    /**
     * Import config from JSON file with validation
     */
    fun importFromFile(file: File): TremorDetectionConfig {
        try {
            // File size check (max 100KB)
            if (file.length() > 100_000) {
                throw IllegalArgumentException("File too large: ${file.length()} bytes (max 100KB)")
            }

            val jsonString = file.readText()
            val config = TremorDetectionConfig.fromJson(jsonString)
            Timber.i("Config imported from: ${file.absolutePath}")
            return config
        } catch (e: Exception) {
            Timber.e(e, "Failed to import config from file")
            throw e
        }
    }

    /**
     * Export config as shareable JSON string
     */
    fun exportToString(config: TremorDetectionConfig): String {
        return config.toJson()
    }

    /**
     * Import config from JSON string with validation
     */
    fun importFromString(jsonString: String): TremorDetectionConfig {
        return try {
            TremorDetectionConfig.fromJson(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "Failed to import config from string")
            throw IllegalArgumentException("Invalid config format: ${e.message}", e)
        }
    }

    /**
     * Sync configuration to watch via Wearable Data Layer.
     * Includes retry logic with exponential backoff.
     *
     * @param config Configuration to sync
     * @return SyncStatus indicating success/failure
     */
    private suspend fun syncToWatch(config: TremorDetectionConfig): SyncStatus {
        var attempt = 0
        var delay = retryDelayMs

        while (attempt < maxRetries) {
            attempt++

            try {
                Timber.d("Syncing config to watch (attempt $attempt/$maxRetries)...")

                val request = PutDataMapRequest.create(DATA_PATH).apply {
                    dataMap.putString("config_json", config.toJson())
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                    dataMap.putString("profile_name", config.profileName)
                    dataMap.putInt("version", config.version)
                }

                // Synchronous call with Tasks API
                val result = withContext(Dispatchers.IO) {
                    Tasks.await(dataClient.putDataItem(request.asPutDataRequest().setUrgent()))
                }

                Timber.i("Config synced to watch successfully: ${config.profileName}")
                return SyncStatus.SYNCED

            } catch (e: CancellationException) {
                throw e // Don't retry on cancellation
            } catch (e: Exception) {
                Timber.w(e, "Sync attempt $attempt failed: ${e.message}")

                if (attempt < maxRetries) {
                    Timber.d("Retrying in ${delay}ms...")
                    delay(delay)
                    delay = (delay * exponentialBackoffMultiplier).toLong()
                } else {
                    Timber.e("All sync attempts failed for: ${config.profileName}")
                    return SyncStatus.FAILED
                }
            }
        }

        return SyncStatus.FAILED
    }

    /**
     * Update sync status in preferences
     */
    private fun updateSyncStatus(status: SyncStatus) {
        prefs.edit().apply {
            putString(KEY_SYNC_STATUS, status.name)
            if (status == SyncStatus.SYNCED) {
                putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            }
        }.apply()
    }

    /**
     * Load all user-saved profiles from storage
     */
    private fun loadAllProfiles(): Map<String, TremorDetectionConfig> {
        val jsonArray = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        val profiles = mutableMapOf<String, TremorDetectionConfig>()

        try {
            val configList: List<TremorDetectionConfig> = json.decodeFromString(jsonArray)
            configList.forEach { config ->
                profiles[config.profileName] = config
            }
        } catch (e: SerializationException) {
            Timber.e(e, "Failed to parse profiles, returning empty list")
        }

        return profiles
    }

    /**
     * Save all profiles to storage
     */
    private fun saveAllProfiles(profiles: Map<String, TremorDetectionConfig>) {
        val configList = profiles.values.toList()
        val jsonArray = json.encodeToString(configList)
        prefs.edit().putString(KEY_PROFILES, jsonArray).apply()
    }
}
```


