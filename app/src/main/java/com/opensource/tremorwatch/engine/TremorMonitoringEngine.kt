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
                Timber.i("★ Tremor episode STARTED (${consecutiveTremorSamples} consecutive samples)")
            } else if (inTremorEpisode) {
                currentEpisodeTremorCount++
            }
        } else {
            consecutiveNonTremorSamples++
            
            // End episode only after config.maxGapSamples consecutive non-tremor
            if (inTremorEpisode && consecutiveNonTremorSamples > config.maxGapSamples) {
                val episodeDuration = System.currentTimeMillis() - currentEpisodeStartTime
                Timber.i("★ Tremor episode ENDED (duration: ${episodeDuration}ms, ${currentEpisodeTremorCount} tremor samples)")
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
            Timber.w("★★★ Wear state changed: WORN (immediate) ★★★")
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
                        Timber.w("★★★ Wear state changed: NOT WORN (after ${timeSinceOffBody}ms debounce, $consecutiveOffBodyReadings readings) ★★★")
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
     * @param accelMag Accelerometer magnitude (m/s²)
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

