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

