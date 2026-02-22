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

        // Safety cap to avoid accidental large allocations if analyze() is called with huge arrays.
        private const val MAX_FFT_SIZE = 1024
    }

    private fun nextPowerOfTwo(n: Int): Int {
        if (n <= 0) return 1
        val highest = n.takeHighestOneBit()
        val next = if (n == highest) highest else (highest shl 1)
        return if (next > 0) next else highest // Overflow guard
    }

    /**
     * Result of FFT analysis
     */
    data class FFTResult(
        val dominantFrequency: Float,    // Hz - peak frequency in tremor band
        val tremorBandPower: Float,      // Power spectral density in 4-12 Hz band
        val totalPower: Float,           // Total power across all frequencies
        val maxPower: Float,             // Maximum power in tremor band (for peak prominence)
        val bandRatio: Float,            // Ratio of tremor-band power to total power
        val peakProminence: Float,       // Peak-to-band ratio
        val spectralEntropy: Float,      // Normalized spectral entropy [0..1]
        val harmonicRatio: Float,        // 2f harmonic support ratio [0..1+]
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

    enum class SpectrumMode {
        CLASSIC,
        WELCH,
        HYBRID
    }

    data class AnalysisOptions(
        val spectrumMode: SpectrumMode = SpectrumMode.CLASSIC,
        val welchSegmentSize: Int = 64,
        val welchOverlap: Float = 0.5f,
        val welchBlend: Float = 0.35f
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
        adaptiveThresholds: AdaptiveThresholds? = null,
        options: AnalysisOptions = AnalysisOptions()
    ): FFTResult {
        if (samples.size < 16) {
            return FFTResult(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, false, 0f)
        }

        // Use power-of-two size for FFT efficiency. If not already a power of 2, zero-pad.
        val n = nextPowerOfTwo(samples.size).coerceAtMost(MAX_FFT_SIZE)
        val paddedSamples = samples.copyOf(n)

        val powerSpectrum = computePowerSpectrum(paddedSamples, n, options)
        var totalPower = 0f
        for (i in powerSpectrum.indices) {
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
        var dominantBin = -1

        for (i in 0 until n / 2) {
            val freq = i * freqResolution

            if (freq >= bandLow && freq <= bandHigh) {
                tremorBandPower += powerSpectrum[i]

                if (powerSpectrum[i] > maxPower) {
                    maxPower = powerSpectrum[i]
                    dominantFreq = freq
                    dominantBin = i
                }
            }
        }

        // Calculate confidence based on:
        // Phase 3: Refined weighting based on deep data analysis
        // Uses configurable weights for flexibility
        val bandRatio = if (totalPower > 0) tremorBandPower / totalPower else 0f
        val peakProminence = if (tremorBandPower > 0) maxPower / tremorBandPower else 0f
        val spectralEntropy = calculateNormalizedSpectralEntropy(powerSpectrum, totalPower)
        val harmonicRatio = calculateHarmonicRatio(powerSpectrum, dominantBin)

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

        // Spectral entropy weighting:
        // Low entropy means concentrated oscillatory energy (tremor-like);
        // high entropy means broadband chaotic motion (artifact-like).
        val entropyPenalty = when {
            spectralEntropy <= 0.45f -> 1.0f
            spectralEntropy <= 0.65f -> 0.6f
            else -> 0.2f
        }
        confidence *= entropyPenalty

        // Harmonic support boosts confidence for tremor-like periodic structure.
        confidence = when {
            harmonicRatio >= 0.30f -> (confidence * 1.25f).coerceAtMost(1f)
            harmonicRatio >= 0.15f -> (confidence * 1.10f).coerceAtMost(1f)
            else -> confidence
        }

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
        val entropyCompatible = spectralEntropy <= 0.85f || harmonicRatio >= 0.20f
        
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
                       entropyCompatible &&
                       dominantFreq >= bandLow &&
                       confidence > confidenceThreshold

        return FFTResult(
            dominantFrequency = dominantFreq,
            tremorBandPower = tremorBandPower,
            totalPower = totalPower,
            maxPower = maxPower,
            bandRatio = bandRatio,
            peakProminence = peakProminence,
            spectralEntropy = spectralEntropy,
            harmonicRatio = harmonicRatio,
            isTremor = isTremor,
            confidence = confidence.coerceIn(0f, 1f)
        )
    }

    private fun calculateNormalizedSpectralEntropy(powerSpectrum: FloatArray, totalPower: Float): Float {
        if (powerSpectrum.isEmpty() || totalPower <= 0f) return 1f

        var entropy = 0.0
        powerSpectrum.forEach { power ->
            if (power > 0f) {
                val p = (power / totalPower).toDouble()
                entropy -= p * log2(p)
            }
        }

        val maxEntropy = log2(powerSpectrum.size.toDouble()).coerceAtLeast(1e-6)
        return (entropy / maxEntropy).toFloat().coerceIn(0f, 1f)
    }

    private fun computePowerSpectrum(
        samples: FloatArray,
        n: Int,
        options: AnalysisOptions
    ): FloatArray {
        val classic = computeClassicPowerSpectrum(samples, n)
        return when (options.spectrumMode) {
            SpectrumMode.CLASSIC -> classic
            SpectrumMode.WELCH -> computeWelchPowerSpectrum(samples, n, options) ?: classic
            SpectrumMode.HYBRID -> {
                val welch = computeWelchPowerSpectrum(samples, n, options)
                if (welch == null) {
                    classic
                } else {
                    val blend = options.welchBlend.coerceIn(0f, 1f)
                    FloatArray(classic.size) { i ->
                        ((1f - blend) * classic[i] + blend * welch[i]).coerceAtLeast(0f)
                    }
                }
            }
        }
    }

    private fun computeClassicPowerSpectrum(samples: FloatArray, n: Int): FloatArray {
        val windowed = applyHanningWindow(samples.copyOf(n))
        val (real, imag) = fft(windowed)
        return FloatArray(n / 2) { i ->
            (real[i] * real[i] + imag[i] * imag[i]) / n
        }
    }

    private fun computeWelchPowerSpectrum(
        samples: FloatArray,
        n: Int,
        options: AnalysisOptions
    ): FloatArray? {
        if (n < 32) return null

        val segment = nextPowerOfTwo(options.welchSegmentSize.coerceAtLeast(16)).coerceAtMost(n)
        if (segment < 16) return null

        val overlap = options.welchOverlap.coerceIn(0f, 0.9f)
        val step = (segment * (1f - overlap)).toInt().coerceAtLeast(1)
        if (step <= 0) return null

        val spectrum = FloatArray(n / 2)
        var segmentsUsed = 0
        var start = 0

        while (start + segment <= n) {
            val segmentSamples = samples.copyOfRange(start, start + segment)
            val segmentWindowed = applyHanningWindow(segmentSamples)
            val (real, imag) = fft(segmentWindowed)
            for (i in 0 until n / 2) {
                val src = ((i.toFloat() / (n / 2).toFloat()) * (segment / 2 - 1)).toInt()
                    .coerceIn(0, segment / 2 - 1)
                spectrum[i] += (real[src] * real[src] + imag[src] * imag[src]) / segment
            }
            segmentsUsed++
            start += step
        }

        if (segmentsUsed == 0) return null
        for (i in spectrum.indices) {
            spectrum[i] /= segmentsUsed.toFloat()
        }
        return spectrum
    }

    private fun calculateHarmonicRatio(powerSpectrum: FloatArray, dominantBin: Int): Float {
        if (dominantBin <= 0 || dominantBin >= powerSpectrum.size) return 0f

        val harmonicBin = dominantBin * 2
        if (harmonicBin >= powerSpectrum.size) return 0f

        val fundamentalPower = localBinPower(powerSpectrum, dominantBin)
        val harmonicPower = localBinPower(powerSpectrum, harmonicBin)

        if (fundamentalPower <= 1e-10f) return 0f
        return (harmonicPower / fundamentalPower).coerceIn(0f, 2f)
    }

    private fun localBinPower(powerSpectrum: FloatArray, centerBin: Int): Float {
        val low = (centerBin - 1).coerceAtLeast(0)
        val high = (centerBin + 1).coerceAtMost(powerSpectrum.lastIndex)
        var sum = 0f
        for (i in low..high) {
            sum += powerSpectrum[i]
        }
        return sum
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
