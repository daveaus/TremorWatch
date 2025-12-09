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
