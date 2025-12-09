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
        Timber.i("★ Calibration started - user should rest arm for $CALIBRATION_DURATION_SECONDS seconds")
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
        
        Timber.i("★ Calibration complete! Baseline magnitude: %.4f ± %.4f", mean, stdDev)
        
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

