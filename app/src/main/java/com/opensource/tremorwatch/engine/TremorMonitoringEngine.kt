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
import com.google.android.gms.location.DetectedActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
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
    private val baselineManager: BaselineManager? = null,
    private val onBatchReady: (List<TremorData>) -> Unit,
    private val onWearStateChanged: (Boolean) -> Unit,
    private val onSampleReady: ((TremorData) -> Unit)? = null
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

        /** Maximum severity for a measurement to be considered reliable.
         *  High-severity outliers during "still" are likely sensor artifacts. */
        const val MAX_RELIABLE_SEVERITY = 5.0f

        /** Accelerometer variance threshold for sensor-based "still" inference. */
        const val FALLBACK_STILL_VARIANCE_THRESHOLD = 2.0f

        /** Confidence assigned to sensor-based fallback activity (lower than API). */
        const val FALLBACK_CONFIDENCE = 50

        /** Motion artifact detection thresholds for suppressing false positives. */
        const val MOTION_ARTIFACT_ACCEL_THRESHOLD = 12.0f
        const val MOTION_ARTIFACT_SEVERITY_THRESHOLD = 1.5f
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
    private val gyroXWindow = mutableListOf<Float>()
    private val gyroYWindow = mutableListOf<Float>()
    private val gyroZWindow = mutableListOf<Float>()
    private val gyroMagnitudeWindow = mutableListOf<Float>() // Buffer for gyroscope FFT analysis
    private var lastGyroFFTResult: TremorFFT.FFTResult? = null
    private var lastAccelFFTResult: TremorFFT.FFTResult? = null
    private var fftProcessingCounter = 0  // Counter for FFT processing interval
    private var lastDominantFrequencyHz = 0f
    private var lastPrincipalAxisLabel = "magnitude"
    private var lastPrincipalAxisVariance = 0f
    private var lastSelectedFftWindowSize = MonitoringConstants.FFT_WINDOW_SIZE
    private var lastFftSpectrumMode = "classic"
    private var lastRerankerProbability = 0f
    private var lastCalibratedConfidence = 0f
    private val recentStepTimestampsMs = ArrayDeque<Long>(180)
    
    // Thread-safe buffer for sensor data
    private val dataBuffer = Collections.synchronizedList(mutableListOf<TremorData>())

    // Ring buffer for recent samples (opus46 Issue 3c)
    // Holds 600 entries = 10 minutes at 1Hz for watch-side objective context computation
    private val recentSamplesBuffer = ArrayDeque<TremorData>(600)
    
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
    // Provided by the service (constructed with applicationContext) to keep engine testable.

    // Activity recognition state (updated by TremorService)
    private data class ActivityState(
        val type: Int,
        val confidence: Int,
        val updatedAtMs: Long
    )

    @Volatile
    private var activityState = ActivityState(DetectedActivity.UNKNOWN, 0, 0L)

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
        val fftWindowSize: Int = MonitoringConstants.FFT_WINDOW_SIZE,
        val fftSpectrumMode: String = "classic",
        val principalAxis: String = "magnitude",
        val principalAxisVariance: Float = 0f,
        val bandRatio: Float = 0f,           // Ratio of tremor band power to total power
        val peakProminence: Float = 0f,      // Prominence of dominant frequency peak
        val spectralEntropy: Float = 1f,     // Normalized spectral entropy [0..1]
        val harmonicRatio: Float = 0f,       // Harmonic support ratio
        val crossSensorSupport: Float = 0f,  // Gyro/accel agreement score [0..1]
        val frequencyStability: Float = 0f,  // Dominant frequency continuity [0..1]
        val rerankerProbability: Float = 0f, // Hybrid reranker output probability
        val calibratedConfidence: Float = 0f, // Confidence after calibration mode
        val severity: Float = 0f,            // Phase 5: Clinical severity score (0-10 scale)
        val baselineMultiplier: Float = 1f,  // Phase 5: How far above personal baseline
        // Phase 5b: Tremor type classification
        val tremorType: String = "unknown",          // Tremor type (resting, postural, essential, etc.)
        val tremorTypeConfidence: Float = 0f,        // Classification confidence (0-1)
        val isRestingState: Boolean = false,         // Whether detected in resting state
        // Activity context (from Activity Recognition)
        val activityType: String = "unknown",
        val activityConfidence: Float = 0f,          // 0-1
        val activityAgeMs: Long = -1L,
        val stepsPerMinute: Int = 0,
        // Activity-adjusted metrics
        val activityAdjustedConfidence: Float = 0f,
        val activityAdjustedSeverity: Float = 0f,
        val reliabilityScore: Float = 0f,
        val isReliableMeasurement: Boolean = false,
        val excludeFromAnalysis: Boolean = false
    )
    
    /**
     * Update the pause state. When paused, sensor data collection is skipped.
     */
    fun setPaused(paused: Boolean) {
        isPausedDueToWearState = paused
        if (paused) {
            recentStepTimestampsMs.clear()
        }
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
        synchronized(recentSamplesBuffer) {
            recentSamplesBuffer.clear()
        }
        gyroXWindow.clear()
        gyroYWindow.clear()
        gyroZWindow.clear()
        gyroMagnitudeWindow.clear()
        accelWindow.clear()
        accelMagnitudeWindow.clear()
        lastGyroFFTResult = null
        lastAccelFFTResult = null
        lastDominantFrequencyHz = 0f
        lastPrincipalAxisLabel = "magnitude"
        lastPrincipalAxisVariance = 0f
        lastSelectedFftWindowSize = MonitoringConstants.FFT_WINDOW_SIZE
        lastFftSpectrumMode = "classic"
        lastRerankerProbability = 0f
        lastCalibratedConfidence = 0f
        recentStepTimestampsMs.clear()
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
        activityState = ActivityState(DetectedActivity.UNKNOWN, 0, 0L)
    }

    /**
     * Update activity context from Activity Recognition.
     */
    fun updateActivity(type: Int, confidence: Int, updatedAtMs: Long = System.currentTimeMillis()) {
        activityState = ActivityState(type, confidence.coerceIn(0, 100), updatedAtMs)
    }

    private data class ActivityAdjustment(
        val adjustedConfidence: Float,
        val adjustedSeverity: Float,
        val reliabilityScore: Float,
        val isReliable: Boolean,
        val excludeFromAnalysis: Boolean,
        val activityType: Int,
        val activityConfidence: Int,
        val activityAgeMs: Long,
        val stepsPerMinute: Int
    )

    private fun getActivityName(type: Int): String = when (type) {
        DetectedActivity.STILL -> "still"
        DetectedActivity.WALKING -> "walking"
        DetectedActivity.RUNNING -> "running"
        DetectedActivity.ON_BICYCLE -> "on_bicycle"
        DetectedActivity.IN_VEHICLE -> "in_vehicle"
        DetectedActivity.TILTING -> "tilting"
        DetectedActivity.ON_FOOT -> "on_foot"
        else -> "unknown"
    }

    /**
     * Sensor-based activity fallback when Activity Recognition API data is stale.
     * Graduated classification: STILL (high/med), WALKING, ON_FOOT, TILTING.
     * (opus46 Issue 2: expanded from binary STILL/UNKNOWN to reduce 87.7% unknown rate)
     */
    private fun inferActivityFromSensors(): Pair<Int, Int> {
        if (accelWindow.size < 10) return Pair(DetectedActivity.UNKNOWN, 0)

        val magnitudes = accelWindow.toList()
        val mean = magnitudes.average().toFloat()
        val variance = magnitudes.map { (it - mean) * (it - mean) }.average().toFloat()
        val meanMag = mean

        return when {
            // High-confidence still: very low variance + very low gyro
            variance < 0.5f && lastAccelMagnitude < 0.3f ->
                Pair(DetectedActivity.STILL, 70)
            // Medium-confidence still: moderate variance + low gyro (e.g., typing, minor fidgeting)
            variance < FALLBACK_STILL_VARIANCE_THRESHOLD && lastAccelMagnitude < 0.5f ->
                Pair(DetectedActivity.STILL, 50)
            // Walking-like: rhythmic moderate variance, gravity-ish mean magnitude
            variance in 2.0f..15.0f && meanMag in 9.0f..14.0f ->
                Pair(DetectedActivity.WALKING, 40)
            // High-motion: running, vehicle, or vigorous arm movement
            variance > 15.0f || meanMag > 18.0f ->
                Pair(DetectedActivity.ON_FOOT, 30)
            // Default: some motion but doesn't match walking/running pattern
            else -> Pair(DetectedActivity.TILTING, 25)
        }
    }

    /**
     * Estimate gyro/accel agreement for tremor-like motion.
     *
     * This is a low-cost proxy for cross-sensor coherence:
     * - close dominant frequencies => better support
     * - similar spectral shape (entropy) => better support
     * - both detectors agreeing => better support
     */
    private fun calculateCrossSensorSupport(
        gyroResult: TremorFFT.FFTResult?,
        accelResult: TremorFFT.FFTResult?
    ): Float {
        val gyro = gyroResult ?: return 0f
        val accel = accelResult ?: return 0.75f

        val freqAgreement = if (gyro.dominantFrequency > 0f && accel.dominantFrequency > 0f) {
            when {
                abs(gyro.dominantFrequency - accel.dominantFrequency) <= 0.5f -> 1.0f
                abs(gyro.dominantFrequency - accel.dominantFrequency) <= 1.0f -> 0.75f
                abs(gyro.dominantFrequency - accel.dominantFrequency) <= 2.0f -> 0.45f
                else -> 0.2f
            }
        } else {
            0.35f
        }

        val entropyAgreement = (1f - abs(gyro.spectralEntropy - accel.spectralEntropy)).coerceIn(0f, 1f)

        val tremorAgreement = when {
            gyro.isTremor && accel.isTremor -> 1.0f
            gyro.isTremor || accel.isTremor -> 0.55f
            else -> 0.25f
        }

        return (
            freqAgreement * 0.50f +
            entropyAgreement * 0.25f +
            tremorAgreement * 0.25f
        ).coerceIn(0.1f, 1.0f)
    }

    /**
     * Score how stable dominant frequency is over time.
     * 1.0 = stable, 0.0 = highly unstable.
     */
    private fun calculateFrequencyStability(currentDominantFrequency: Float): Float {
        if (currentDominantFrequency <= 0f) return 0f
        if (lastDominantFrequencyHz <= 0f) {
            lastDominantFrequencyHz = currentDominantFrequency
            return 1.0f
        }

        val deltaHz = abs(currentDominantFrequency - lastDominantFrequencyHz)
        lastDominantFrequencyHz = currentDominantFrequency

        return when {
            deltaHz <= 0.25f -> 1.0f
            deltaHz <= 0.75f -> 0.8f
            deltaHz <= 1.5f -> 0.5f
            else -> 0.2f
        }
    }

    private fun adjustForActivity(
        baseConfidence: Float,
        baseSeverity: Float,
        spectralEntropy: Float,
        harmonicRatio: Float,
        crossSensorSupport: Float,
        frequencyStability: Float,
        stepsPerMinute: Int,
        nowMs: Long
    ): ActivityAdjustment {
        val state = activityState
        val ageMs = if (state.updatedAtMs > 0L) {
            kotlin.math.max(0L, nowMs - state.updatedAtMs)
        } else {
            Long.MAX_VALUE
        }
        val isStale = ageMs > config.activityStaleThresholdMs

        // When Activity Recognition is stale, try sensor-based fallback
        val (activityType, activityConfidence) = if (isStale) {
            inferActivityFromSensors()
        } else {
            Pair(state.type, state.confidence)
        }

        // Motion artifact detection: suppress high-severity readings caused by
        // walking arm-swing, tilting, or unknown-context gross movement.
        val likelyMotionArtifact = when {
            activityType == DetectedActivity.RUNNING ||
                activityType == DetectedActivity.ON_BICYCLE ||
                activityType == DetectedActivity.IN_VEHICLE -> true
            stepsPerMinute > 130 -> true
            activityType == DetectedActivity.WALKING &&
                activityConfidence >= config.activityMediumConfidenceThreshold &&
                baseSeverity >= MOTION_ARTIFACT_SEVERITY_THRESHOLD -> true
            activityType == DetectedActivity.TILTING &&
                baseSeverity >= MOTION_ARTIFACT_SEVERITY_THRESHOLD -> true
            activityType == DetectedActivity.UNKNOWN &&
                baseSeverity >= MOTION_ARTIFACT_SEVERITY_THRESHOLD &&
                lastAccelMagnitude >= MOTION_ARTIFACT_ACCEL_THRESHOLD -> true
            baseSeverity >= MOTION_ARTIFACT_SEVERITY_THRESHOLD &&
                lastAccelMagnitude >= 20.0f -> true
            else -> false
        }

        val hasActivityData = activityConfidence > 0

        var excludeFromAnalysis = hasActivityData &&
            activityConfidence >= config.activityHighConfidenceThreshold &&
            (activityType == DetectedActivity.RUNNING ||
             activityType == DetectedActivity.ON_BICYCLE ||
             activityType == DetectedActivity.IN_VEHICLE)
        if (stepsPerMinute > 140) {
            excludeFromAnalysis = true
        }
        if (likelyMotionArtifact) {
            excludeFromAnalysis = true
        }

        val artifactPenalty = if (likelyMotionArtifact) 0.25f else 1f
        val freshnessScore = when {
            !isStale -> 1.0f
            ageMs <= (config.activityStaleThresholdMs * 2) -> 0.7f
            else -> 0.4f
        }

        val activityScore = when (activityType) {
            DetectedActivity.STILL -> 1.0f
            DetectedActivity.TILTING -> 0.55f
            DetectedActivity.UNKNOWN -> 0.45f
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> 0.30f
            DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE -> 0.10f
            else -> 0.35f
        }

        val spectralScore = when {
            spectralEntropy <= 0.45f -> 1.0f
            spectralEntropy <= 0.65f -> 0.65f
            else -> 0.20f
        }

        val harmonicScore = when {
            harmonicRatio >= 0.30f -> 1.0f
            harmonicRatio >= 0.15f -> 0.75f
            harmonicRatio >= 0.05f -> 0.50f
            else -> 0.30f
        }

        val confidenceFactor = 0.4f + 0.6f * baseConfidence.coerceIn(0f, 1f)
        val stepPenalty = when {
            stepsPerMinute <= 0 -> 1.0f
            stepsPerMinute <= 60 -> 0.85f
            stepsPerMinute <= 110 -> 0.60f
            else -> 0.35f
        }
        val reliabilityScore = (
            0.25f * activityScore +
            0.25f * spectralScore +
            0.20f * crossSensorSupport.coerceIn(0f, 1f) +
            0.15f * harmonicScore +
            0.10f * freshnessScore +
            0.05f * frequencyStability.coerceIn(0f, 1f)
        ).coerceIn(0f, 1f) * confidenceFactor * artifactPenalty * stepPenalty

        // Reliability is no longer hardcoded to STILL only.
        // We allow tremor-like tilting/unknown contexts if signal quality is strong.
        val isContextEligible = activityType == DetectedActivity.STILL ||
            activityType == DetectedActivity.TILTING ||
            activityType == DetectedActivity.UNKNOWN
        val isReliable = isContextEligible &&
            reliabilityScore >= 0.55f &&
            baseSeverity <= MAX_RELIABLE_SEVERITY &&
            !likelyMotionArtifact &&
            stepsPerMinute <= 110

        if (!config.activityFilteringEnabled ||
            !hasActivityData ||
            activityConfidence < config.activityLowConfidenceThreshold) {
            return ActivityAdjustment(
                adjustedConfidence = (baseConfidence * artifactPenalty).coerceIn(0f, 1f),
                adjustedSeverity = (baseSeverity * artifactPenalty).coerceAtLeast(0f),
                reliabilityScore = reliabilityScore.coerceIn(0f, 1f),
                isReliable = isReliable,
                excludeFromAnalysis = excludeFromAnalysis,
                activityType = activityType,
                activityConfidence = activityConfidence,
                activityAgeMs = if (ageMs == Long.MAX_VALUE) -1L else ageMs,
                stepsPerMinute = stepsPerMinute
            )
        }

        val baseMultiplier = when (activityType) {
            DetectedActivity.STILL -> config.activityStillMultiplier
            DetectedActivity.TILTING -> config.activityTiltingMultiplier
            DetectedActivity.WALKING -> config.activityWalkingMultiplier
            DetectedActivity.RUNNING -> config.activityRunningMultiplier
            DetectedActivity.ON_BICYCLE -> config.activityOnBicycleMultiplier
            DetectedActivity.IN_VEHICLE -> config.activityInVehicleMultiplier
            DetectedActivity.ON_FOOT -> config.activityOnFootMultiplier
            else -> config.activityUnknownMultiplier
        }

        // Scale multiplier by activity confidence (0..1): low confidence -> minimal adjustment
        val confidenceWeight = (activityConfidence / 100f).coerceIn(0f, 1f)
        val effectiveMultiplier = 1f - (1f - baseMultiplier) * confidenceWeight

        return ActivityAdjustment(
            adjustedConfidence = (baseConfidence * effectiveMultiplier * artifactPenalty).coerceIn(0f, 1f),
            adjustedSeverity = (baseSeverity * effectiveMultiplier * artifactPenalty).coerceAtLeast(0f),
            reliabilityScore = reliabilityScore.coerceIn(0f, 1f),
            isReliable = isReliable,
            excludeFromAnalysis = excludeFromAnalysis,
            activityType = activityType,
            activityConfidence = activityConfidence,
            activityAgeMs = if (ageMs == Long.MAX_VALUE) -1L else ageMs,
            stepsPerMinute = stepsPerMinute
        )
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
     * Returns recent TremorData samples from the in-memory ring buffer.
     * Used by rating prompts to attach objective context without waiting for batch upload.
     * (opus46 Issue 3d)
     */
    fun getRecentTremorData(windowSeconds: Int): List<TremorData> {
        val cutoff = System.currentTimeMillis() - (windowSeconds * 1000L)
        return synchronized(recentSamplesBuffer) {
            recentSamplesBuffer.filter { it.timestamp >= cutoff }.toList()
        }
    }

    /**
     * Classify whether a sample is likely a movement artifact rather than tremor.
     * This is a METADATA-ONLY annotation for downstream analysis -- it does NOT
     * modify confidence or severity (those are handled by existing artifact filters
     * in the detection pipeline and temporal smoothing).
     * (opus46 Issue 4a)
     */
    fun classifyArtifactForMetadata(data: TremorData): String? {
        // High-frequency rapid movements (typing, tapping) -- above physiological tremor range
        if (data.severity > 2.0 && data.dominantFrequency > 9.0f && data.accelMagnitude > 15.0f) {
            return "high_freq_motion"
        }

        // Very high acceleration suggests voluntary gross movement, not tremor
        if (data.accelMagnitude > 20.0f && data.severity > 1.0) {
            return "gross_movement"
        }

        // Non-resting, non-still state with elevated severity
        if (!data.isRestingState && data.severity > 3.0 && data.activityType != "still") {
            return "activity_artifact"
        }

        // Energy not concentrated in tremor band despite high overall magnitude
        if (data.severity > 2.0 && data.bandRatio < 0.02f) {
            return "low_band_ratio"
        }

        // Unknown context with elevated severity and high acceleration — likely movement
        if (data.activityType == "unknown" && data.severity > 1.5f && data.accelMagnitude > 12.0f) {
            return "unknown_motion_context"
        }

        // Tilting with elevated severity — wrist orientation change
        if (data.activityType == "tilting" && data.severity > 1.0f) {
            return "tilting_context"
        }

        // Walking with elevated severity — arm swing artifact
        if (data.activityType == "walking" && data.severity > 1.0f && data.accelMagnitude > 10.0f) {
            return "walking_motion_context"
        }

        return null  // Not classified as artifact
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
        
        // Initialize sensor start time on first event
        if (startTimeSensorNs == 0L) {
            startTimeSensorNs = sensorTimestampNs
        }
        
        when (event.sensor?.type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                lastSampleTime = System.currentTimeMillis()
                handleOffBodySensorEvent(event, sensorTimestampNs)
            }
            
            Sensor.TYPE_GYROSCOPE -> {
                if (!isPausedDueToWearState) {
                    lastSampleTime = System.currentTimeMillis()
                    handleGyroscopeEvent(event, sensorTimestampNs)
                }
            }
            
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!isPausedDueToWearState) {
                    lastSampleTime = System.currentTimeMillis()
                    handleAccelerometerEvent(event)
                }
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                if (!isPausedDueToWearState) {
                    handleStepDetectorEvent()
                }
            }
        }
    }

    private fun handleStepDetectorEvent() {
        val nowMs = System.currentTimeMillis()
        recentStepTimestampsMs.addLast(nowMs)
        val cutoff = nowMs - 60_000L
        while (recentStepTimestampsMs.isNotEmpty() && recentStepTimestampsMs.first() < cutoff) {
            recentStepTimestampsMs.removeFirst()
        }
    }

    private fun getStepsPerMinute(nowMs: Long): Int {
        val cutoff = nowMs - 60_000L
        while (recentStepTimestampsMs.isNotEmpty() && recentStepTimestampsMs.first() < cutoff) {
            recentStepTimestampsMs.removeFirst()
        }
        return recentStepTimestampsMs.size
    }

    private data class AxisSelection(
        val label: String,
        val samples: FloatArray,
        val variance: Float
    )

    private fun getPrimaryFftWindowSize(nowMs: Long): Int {
        return when (config.fftWindowMode.lowercase()) {
            "fixed_128" -> config.fftWindowSizeLong
            "ab_test" -> {
                val minuteBucket = nowMs / 60_000L
                if (minuteBucket % 2L == 0L) config.fftWindowSizeShort else config.fftWindowSizeLong
            }
            else -> config.fftWindowSizeShort
        }
    }

    private fun getSecondaryFftWindowSize(nowMs: Long): Int? {
        if (config.fftWindowMode.lowercase() != "ab_test") return null
        val primary = getPrimaryFftWindowSize(nowMs)
        return if (primary == config.fftWindowSizeShort) config.fftWindowSizeLong else config.fftWindowSizeShort
    }

    private fun getBufferRetentionSize(): Int {
        val shortSize = config.fftWindowSizeShort.coerceAtLeast(32)
        val longSize = config.fftWindowSizeLong.coerceAtLeast(shortSize)
        return maxOf(shortSize, longSize)
    }

    private fun trimWindow(window: MutableList<Float>, maxSize: Int) {
        while (window.size > maxSize) {
            window.removeAt(0)
        }
    }

    private fun selectPrincipalGyroAxis(windowSize: Int): AxisSelection? {
        if (windowSize <= 0) return null
        if (gyroXWindow.size < windowSize || gyroYWindow.size < windowSize || gyroZWindow.size < windowSize) {
            return null
        }

        val xSlice = gyroXWindow.takeLast(windowSize).toFloatArray()
        val ySlice = gyroYWindow.takeLast(windowSize).toFloatArray()
        val zSlice = gyroZWindow.takeLast(windowSize).toFloatArray()

        val vx = variance(xSlice)
        val vy = variance(ySlice)
        val vz = variance(zSlice)

        return when {
            vx >= vy && vx >= vz -> AxisSelection("x", xSlice, vx)
            vy >= vx && vy >= vz -> AxisSelection("y", ySlice, vy)
            else -> AxisSelection("z", zSlice, vz)
        }
    }

    private fun variance(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var mean = 0.0
        values.forEach { mean += it.toDouble() }
        mean /= values.size.toDouble()
        var sum = 0.0
        values.forEach {
            val d = it.toDouble() - mean
            sum += d * d
        }
        return (sum / values.size.toDouble()).toFloat()
    }

    private fun spectrumModeFromConfig(): TremorFFT.SpectrumMode {
        return when (config.fftSpectrumMode.lowercase()) {
            "welch" -> TremorFFT.SpectrumMode.WELCH
            "hybrid" -> TremorFFT.SpectrumMode.HYBRID
            else -> TremorFFT.SpectrumMode.CLASSIC
        }
    }

    private fun analysisOptionsFromConfig(): TremorFFT.AnalysisOptions {
        return TremorFFT.AnalysisOptions(
            spectrumMode = spectrumModeFromConfig(),
            welchSegmentSize = config.fftWelchSegmentSize,
            welchOverlap = config.fftWelchOverlap,
            welchBlend = config.fftWelchBlend
        )
    }

    private fun selectBetterFftResult(
        primary: TremorFFT.FFTResult?,
        secondary: TremorFFT.FFTResult?
    ): TremorFFT.FFTResult? {
        if (primary == null) return secondary
        if (secondary == null) return primary

        val primaryScore = (if (primary.isTremor) 0.5f else 0f) + primary.confidence + primary.bandRatio * 0.5f
        val secondaryScore = (if (secondary.isTremor) 0.5f else 0f) + secondary.confidence + secondary.bandRatio * 0.5f
        return if (secondaryScore > primaryScore) secondary else primary
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
        
        // Fill FFT windows at sensor rate for frequency-domain features.
        val retentionSize = getBufferRetentionSize()
        gyroXWindow.add(x)
        gyroYWindow.add(y)
        gyroZWindow.add(z)
        gyroMagnitudeWindow.add(magnitude)
        trimWindow(gyroXWindow, retentionSize)
        trimWindow(gyroYWindow, retentionSize)
        trimWindow(gyroZWindow, retentionSize)
        trimWindow(gyroMagnitudeWindow, retentionSize)

        // Perform FFT analysis on gyroscope/accelerometer when enough buffered samples exist.
        fftProcessingCounter++
        val nowWall = System.currentTimeMillis()
        val primaryWindowSize = getPrimaryFftWindowSize(nowWall)
        if (gyroMagnitudeWindow.size >= primaryWindowSize &&
            fftProcessingCounter >= MonitoringConstants.FFT_PROCESSING_INTERVAL) {

            // Determine activity state from previous FFT result's total power.
            val isResting = lastGyroFFTResult?.let {
                it.totalPower < TremorFFT.RESTING_POWER_THRESHOLD
            } ?: true

            // Get adaptive thresholds from BaselineManager (if calibrated).
            val adaptiveThresholds = baselineManager?.let { manager ->
                if (manager.hasCompletedCalibration()) {
                    val thresholds = manager.getAdaptiveThresholds(isResting)
                    TremorFFT.AdaptiveThresholds(
                        severityFloor = thresholds.severityFloor,
                        minBandRatio = thresholds.minBandRatio,
                        confidenceThreshold = if (thresholds.isPersonalized) 0.30f else 0.35f,
                        isPersonalized = thresholds.isPersonalized
                    )
                } else null
            }

            val analysisOptions = analysisOptionsFromConfig()
            lastFftSpectrumMode = config.fftSpectrumMode.lowercase()

            // Principal-axis gyro FFT (higher SNR than magnitude-only FFT).
            val primaryAxis = selectPrincipalGyroAxis(primaryWindowSize)
            val primaryGyroSamples = primaryAxis?.samples
                ?: gyroMagnitudeWindow.takeLast(primaryWindowSize).toFloatArray()
            val primaryGyroResult = tremorFFT.analyze(
                samples = primaryGyroSamples,
                isResting = isResting,
                adaptiveThresholds = adaptiveThresholds,
                options = analysisOptions
            )
            var chosenResult = primaryGyroResult
            var chosenWindowSize = primaryWindowSize
            var chosenAxisLabel = primaryAxis?.label ?: "magnitude"
            var chosenAxisVariance = primaryAxis?.variance ?: 0f

            // Optional A/B path: evaluate alternate window size and keep stronger result.
            val secondaryWindowSize = getSecondaryFftWindowSize(nowWall)
            if (secondaryWindowSize != null && gyroMagnitudeWindow.size >= secondaryWindowSize) {
                val secondaryAxis = selectPrincipalGyroAxis(secondaryWindowSize)
                val secondarySamples = secondaryAxis?.samples
                    ?: gyroMagnitudeWindow.takeLast(secondaryWindowSize).toFloatArray()
                val secondaryResult = tremorFFT.analyze(
                    samples = secondarySamples,
                    isResting = isResting,
                    adaptiveThresholds = adaptiveThresholds,
                    options = analysisOptions
                )
                val selected = selectBetterFftResult(primaryGyroResult, secondaryResult)
                if (selected === secondaryResult) {
                    chosenResult = secondaryResult
                    chosenWindowSize = secondaryWindowSize
                    chosenAxisLabel = secondaryAxis?.label ?: "magnitude"
                    chosenAxisVariance = secondaryAxis?.variance ?: 0f
                }
            }

            lastGyroFFTResult = chosenResult
            lastSelectedFftWindowSize = chosenWindowSize
            lastPrincipalAxisLabel = chosenAxisLabel
            lastPrincipalAxisVariance = chosenAxisVariance

            // Accelerometer analysis uses matched window size for cross-sensor agreement.
            if (accelMagnitudeWindow.size >= chosenWindowSize) {
                lastAccelFFTResult = tremorFFT.analyze(
                    samples = accelMagnitudeWindow.takeLast(chosenWindowSize).toFloatArray(),
                    isResting = isResting,
                    adaptiveThresholds = adaptiveThresholds,
                    options = analysisOptions
                )
            }

            fftProcessingCounter = 0
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
        val crossSensorSupport = calculateCrossSensorSupport(gyroFFTResult, accelFFTResult)
        
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
                Pair(true, (combinedConfidence * crossSensorSupport).coerceIn(0f, 1f))
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
                Pair(true, (confidence * crossSensorSupport).coerceIn(0f, 1f))
            }
            // Fallback to threshold-based classification
            else -> classifyMovement(magnitude, lastAccelMagnitude)
        }

        // Calculate derived metrics from gyroscope FFT result (primary sensor)
        val tremorBandPower = gyroFFTResult?.tremorBandPower ?: 0f
        val maxPower = gyroFFTResult?.maxPower ?: 0f
        val bandRatio = gyroFFTResult?.bandRatio ?: if (totalPower > 0f) tremorBandPower / totalPower else 0f
        val peakProminence = gyroFFTResult?.peakProminence ?: if (tremorBandPower > 0f) maxPower / tremorBandPower else 0f
        val dominantFrequency = gyroFFTResult?.dominantFrequency ?: 0f
        val spectralEntropy = gyroFFTResult?.spectralEntropy ?: 1f
        val harmonicRatio = gyroFFTResult?.harmonicRatio ?: 0f
        val frequencyStability = calculateFrequencyStability(dominantFrequency)
        val stepsPerMinute = getStepsPerMinute(now)
        
        // Phase 2: Apply dynamic thresholding and post-processing filters (Google's recommendations)
        var finalIsTremor = classification.first
        var finalConfidence = classification.second
        finalConfidence *= (0.7f + 0.3f * frequencyStability)
        if (frequencyStability < 0.2f && finalIsTremor) {
            finalConfidence *= 0.4f
        }
        
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

        // Hybrid reranker for borderline cases (rules remain hard safety rails).
        val rerankerInput = HybridRerankerInput(
            baseConfidence = finalConfidence,
            bandRatio = bandRatio,
            spectralEntropy = spectralEntropy,
            harmonicRatio = harmonicRatio,
            crossSensorSupport = crossSensorSupport,
            frequencyStability = frequencyStability,
            stepsPerMinute = stepsPerMinute,
            isRestingState = isResting
        )
        val rerankerResult = HybridTremorReranker.rerank(rerankerInput, config)
        lastRerankerProbability = rerankerResult.probability
        finalConfidence = rerankerResult.blendedConfidence
        if (!finalIsTremor && rerankerResult.supportsTremor && finalConfidence >= config.confidenceThreshold) {
            finalIsTremor = true
        } else if (finalIsTremor && !rerankerResult.supportsTremor && rerankerResult.probability < 0.20f) {
            finalIsTremor = false
        }

        // Optional confidence calibration (none/platt/isotonic).
        finalConfidence = ConfidenceCalibrator.calibrate(finalConfidence, config)
        lastCalibratedConfidence = finalConfidence
        
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

        val activityAdjustment = adjustForActivity(
            baseConfidence = finalConfidence,
            baseSeverity = severity,
            spectralEntropy = spectralEntropy,
            harmonicRatio = harmonicRatio,
            crossSensorSupport = crossSensorSupport,
            frequencyStability = frequencyStability,
            stepsPerMinute = stepsPerMinute,
            nowMs = now
        )

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
            timestamp = now,
            datetimeIso = datetimeIso,
            timeFormatted = timeFormatted,
            x = x,
            y = y,
            z = z,
            magnitude = magnitude,
            accelMagnitude = lastAccelMagnitude,
            isTremor = finalIsTremor,
            confidence = finalConfidence.coerceIn(0f, 1f),
            isWorn = isWatchWorn,
            isCharging = isCharging,
            dominantFrequency = dominantFrequency,
            tremorBandPower = tremorBandPower,
            totalPower = totalPower,
            fftWindowSize = lastSelectedFftWindowSize,
            fftSpectrumMode = lastFftSpectrumMode,
            principalAxis = lastPrincipalAxisLabel,
            principalAxisVariance = lastPrincipalAxisVariance,
            bandRatio = bandRatio,
            peakProminence = peakProminence,
            spectralEntropy = spectralEntropy,
            harmonicRatio = harmonicRatio,
            crossSensorSupport = crossSensorSupport,
            frequencyStability = frequencyStability,
            rerankerProbability = lastRerankerProbability,
            calibratedConfidence = lastCalibratedConfidence,
            severity = severity,
            baselineMultiplier = baselineMultiplier,
            tremorType = tremorClassification?.primaryType?.name?.lowercase() ?: "none",
            tremorTypeConfidence = tremorClassification?.confidence ?: 0f,
            isRestingState = tremorClassification?.isResting ?: isResting,
            activityType = getActivityName(activityAdjustment.activityType),
            activityConfidence = activityAdjustment.activityConfidence / 100f,
            activityAgeMs = activityAdjustment.activityAgeMs,
            stepsPerMinute = activityAdjustment.stepsPerMinute,
            activityAdjustedConfidence = activityAdjustment.adjustedConfidence,
            activityAdjustedSeverity = activityAdjustment.adjustedSeverity,
            reliabilityScore = activityAdjustment.reliabilityScore,
            isReliableMeasurement = activityAdjustment.isReliable,
            excludeFromAnalysis = activityAdjustment.excludeFromAnalysis
        )

        // Provide the persisted 1 Hz sample to the service layer (e.g., calibration capture).
        // This must not affect core monitoring behavior if the callback misbehaves.
        try {
            onSampleReady?.invoke(tremorData)
        } catch (e: Exception) {
            Timber.w(e, "onSampleReady callback failed")
        }

        // Add to ring buffer for watch-side objective context (opus46 Issue 3c)
        synchronized(recentSamplesBuffer) {
            recentSamplesBuffer.add(tremorData)
            // Keep buffer size at 600 entries (10 minutes at 1Hz)
            while (recentSamplesBuffer.size > 600) {
                recentSamplesBuffer.removeFirst()
            }
        }

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
        trimWindow(accelMagnitudeWindow, getBufferRetentionSize())
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

