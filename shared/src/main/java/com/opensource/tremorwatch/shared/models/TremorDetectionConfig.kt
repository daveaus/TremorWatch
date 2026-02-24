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
    val highActivityThreshold: Float = 5.0f,

    // === Activity Recognition Filtering ===
    /** Enable activity-based filtering of tremor confidence/severity */
    val activityFilteringEnabled: Boolean = true,
    /** Multipliers applied by detected activity (0-1). */
    val activityStillMultiplier: Float = 1.0f,
    val activityTiltingMultiplier: Float = 0.15f,  // Tightened from 0.5 — tilting is almost always motion artifact
    val activityWalkingMultiplier: Float = 0.3f,
    val activityRunningMultiplier: Float = 0.1f,
    val activityOnBicycleMultiplier: Float = 0.1f,
    val activityInVehicleMultiplier: Float = 0.05f,
    val activityOnFootMultiplier: Float = 0.3f,
    val activityUnknownMultiplier: Float = 0.7f,
    /** Confidence thresholds (0-100) */
    val activityHighConfidenceThreshold: Int = 75,
    val activityMediumConfidenceThreshold: Int = 60,
    val activityLowConfidenceThreshold: Int = 40,
    /** Ignore activity states older than this */
    val activityStaleThresholdMs: Long = 30_000L,

    // === FFT Pipeline Settings ===
    /** FFT window mode: fixed_64, fixed_128, or ab_test */
    val fftWindowMode: String = "fixed_64",
    /** Short FFT window size used by fixed_64/ab_test path */
    val fftWindowSizeShort: Int = 64,
    /** Long FFT window size used by fixed_128/ab_test path */
    val fftWindowSizeLong: Int = 128,
    /** Spectrum analysis mode: classic, welch, hybrid */
    val fftSpectrumMode: String = "classic",
    /** Welch segment size when fftSpectrumMode uses Welch */
    val fftWelchSegmentSize: Int = 64,
    /** Welch overlap fraction [0..0.9] */
    val fftWelchOverlap: Float = 0.5f,
    /** Hybrid mode blend of Welch into classic [0..1] */
    val fftWelchBlend: Float = 0.35f,

    // === Confidence Calibration ===
    /** Confidence calibration mode: none, platt, isotonic */
    val confidenceCalibrationMode: String = "none",
    /** Platt scaling coefficient A */
    val confidencePlattA: Float = 1.0f,
    /** Platt scaling coefficient B */
    val confidencePlattB: Float = 0.0f,
    /** Isotonic X knots (must be sorted ascending in [0..1]) */
    val confidenceIsotonicX: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    /** Isotonic Y knots (must match X size, values in [0..1]) */
    val confidenceIsotonicY: List<Float> = listOf(0f, 0.20f, 0.50f, 0.80f, 1f),

    // === Hybrid Reranker ===
    /** Enables lightweight on-device reranker over rule-based confidence */
    val hybridRerankerEnabled: Boolean = true,
    /** Probability threshold for reranker-supported tremor */
    val hybridRerankerThreshold: Float = 0.45f,
    /** Blend factor: 0 keeps raw confidence, 1 uses reranker only */
    val hybridRerankerBlend: Float = 0.5f,
    /** Logistic reranker intercept */
    val hybridRerankerIntercept: Float = -0.70f,
    val hybridRerankerWConfidence: Float = 2.0f,
    val hybridRerankerWBandRatio: Float = 1.1f,
    val hybridRerankerWEntropy: Float = -2.0f,
    val hybridRerankerWHarmonic: Float = 1.0f,
    val hybridRerankerWCrossSensor: Float = 1.4f,
    val hybridRerankerWFreqStability: Float = 0.8f,
    val hybridRerankerWStepsPerMinute: Float = -0.012f,

    // === Active Learning Parameters (v2.0) ===
    /**
     * Frequency Stability Index (FSI) threshold.
     * Measures how consistently the dominant frequency appears across
     * successive FFT windows. Real tremors show stable frequencies;
     * motion artifacts vary.
     * Range: 0.0 (disabled) to 1.0 (maximum stability required)
     * Recommended training target: 0.25-0.45
     */
    val minFrequencyStability: Float = 0.0f,

    /**
     * Harmonic Energy Ratio (HER) threshold.
     * Measures the ratio of energy at harmonic frequencies (2f, 3f)
     * to the fundamental. Parkinsonian tremor often shows harmonics;
     * random movement noise does not.
     * Range: 0.0 (disabled) to 2.0 (strong harmonics required)
     * Recommended training target: 0.10-0.35
     */
    val minHarmonicRatio: Float = 0.0f,

    /**
     * Cross-Sensor Coherence Score (CSCS) threshold.
     * Measures agreement between gyroscope and accelerometer tremor signals.
     * Real tremors appear in both sensors; single-sensor artifacts do not.
     * Range: 0.0 (disabled) to 1.0 (perfect agreement required)
     * Recommended training target: 0.15-0.40
     */
    val minCrossSensorSupport: Float = 0.0f
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

        // Activity filtering validation
        require(activityStillMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
        require(activityTiltingMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
        require(activityWalkingMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
        require(activityRunningMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
        require(activityOnBicycleMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
        require(activityInVehicleMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
        require(activityOnFootMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
        require(activityUnknownMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
        require(activityHighConfidenceThreshold in 0..100) { "Activity confidence must be in [0, 100]" }
        require(activityMediumConfidenceThreshold in 0..100) { "Activity confidence must be in [0, 100]" }
        require(activityLowConfidenceThreshold in 0..100) { "Activity confidence must be in [0, 100]" }
        require(activityHighConfidenceThreshold >= activityMediumConfidenceThreshold) {
            "Activity high confidence must be >= medium confidence"
        }
        require(activityMediumConfidenceThreshold >= activityLowConfidenceThreshold) {
            "Activity medium confidence must be >= low confidence"
        }
        require(activityStaleThresholdMs >= 0) { "Activity stale threshold must be >= 0" }

        // FFT pipeline validation
        require(fftWindowMode.lowercase() in setOf("fixed_64", "fixed_128", "ab_test")) {
            "fftWindowMode must be one of: fixed_64, fixed_128, ab_test"
        }
        require(fftWindowSizeShort in 32..256 && fftWindowSizeShort % 2 == 0) {
            "fftWindowSizeShort must be even and in [32, 256]"
        }
        require(fftWindowSizeLong in 64..512 && fftWindowSizeLong % 2 == 0) {
            "fftWindowSizeLong must be even and in [64, 512]"
        }
        require(fftWindowSizeLong >= fftWindowSizeShort) {
            "fftWindowSizeLong must be >= fftWindowSizeShort"
        }
        require(fftSpectrumMode.lowercase() in setOf("classic", "welch", "hybrid")) {
            "fftSpectrumMode must be one of: classic, welch, hybrid"
        }
        require(fftWelchSegmentSize in 16..256) {
            "fftWelchSegmentSize must be in [16, 256]"
        }
        require(fftWelchOverlap in 0.0f..0.9f) {
            "fftWelchOverlap must be in [0.0, 0.9]"
        }
        require(fftWelchBlend in 0.0f..1.0f) {
            "fftWelchBlend must be in [0, 1]"
        }

        // Calibration validation
        require(confidenceCalibrationMode.lowercase() in setOf("none", "platt", "isotonic")) {
            "confidenceCalibrationMode must be one of: none, platt, isotonic"
        }
        require(confidenceIsotonicX.size >= 2 && confidenceIsotonicX.size == confidenceIsotonicY.size) {
            "confidence isotonic knots must have matching size >= 2"
        }
        require(confidenceIsotonicX.first() >= 0f && confidenceIsotonicX.last() <= 1f) {
            "confidenceIsotonicX must be within [0, 1]"
        }
        require(confidenceIsotonicY.all { it in 0f..1f }) {
            "confidenceIsotonicY values must be in [0, 1]"
        }
        require(confidenceIsotonicX.zipWithNext().all { (a, b) -> b >= a }) {
            "confidenceIsotonicX must be non-decreasing"
        }
        require(confidenceIsotonicY.zipWithNext().all { (a, b) -> b >= a }) {
            "confidenceIsotonicY must be non-decreasing"
        }

        // Hybrid reranker validation
        require(hybridRerankerThreshold in 0.0f..1.0f) {
            "hybridRerankerThreshold must be in [0, 1]"
        }
        require(hybridRerankerBlend in 0.0f..1.0f) {
            "hybridRerankerBlend must be in [0, 1]"
        }

        // Active Learning validation
        require(minFrequencyStability in 0.0f..1.0f) {
            "minFrequencyStability must be in [0, 1]"
        }
        require(minHarmonicRatio in 0.0f..2.0f) {
            "minHarmonicRatio must be in [0, 2]"
        }
        require(minCrossSensorSupport in 0.0f..1.0f) {
            "minCrossSensorSupport must be in [0, 1]"
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
        const val CURRENT_VERSION = 4

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
