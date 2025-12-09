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
