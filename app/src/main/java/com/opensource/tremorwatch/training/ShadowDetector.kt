package com.opensource.tremorwatch.training

import com.opensource.tremorwatch.TremorFFT
import com.opensource.tremorwatch.shared.models.FeedbackFeatureSnapshot
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import timber.log.Timber

/**
 * Shadow Detector — runs a parallel FFT analysis with relaxed thresholds
 * to catch borderline events that the production detector would miss.
 *
 * Purpose: During training mode, we want to ask the user about events
 * that are "almost tremor" — the shadow detector has lower thresholds
 * so it triggers on more events, generating opportunities for user feedback.
 *
 * Safety: The shadow detector never affects production detection results.
 * It only influences whether a training prompt is shown.
 */
class ShadowDetector(private val sampleRate: Float) {

    private val shadowFFT = TremorFFT(sampleRate)

    /**
     * Create an experimental config with relaxed thresholds.
     * Each threshold is reduced by 30-50% from production to capture
     * borderline cases that the user can then confirm or deny.
     */
    fun createExperimentalConfig(baseConfig: TremorDetectionConfig): TremorDetectionConfig {
        return baseConfig.copy(
            minBandRatio = (baseConfig.minBandRatio * 0.6f).coerceAtLeast(0.01f),
            restingMinBandRatio = (baseConfig.restingMinBandRatio * 0.6f).coerceAtLeast(0.01f),
            activeMinBandRatio = (baseConfig.activeMinBandRatio * 0.6f).coerceAtLeast(0.02f),
            confidenceThreshold = (baseConfig.confidenceThreshold * 0.5f).coerceAtLeast(0.10f),
            minTremorPower = (baseConfig.minTremorPower * 0.5f).coerceAtLeast(0.0005f),
            // Relax new Active Learning gates too (if enabled)
            minFrequencyStability = (baseConfig.minFrequencyStability * 0.5f),
            minHarmonicRatio = (baseConfig.minHarmonicRatio * 0.5f),
            minCrossSensorSupport = (baseConfig.minCrossSensorSupport * 0.5f)
        )
    }

    data class ShadowResult(
        val productionIsTremor: Boolean,
        val experimentalIsTremor: Boolean,
        val shouldPromptUser: Boolean,
        val triggerReason: String,
        val features: FeedbackFeatureSnapshot
    )

    /**
     * Run shadow analysis alongside the production result.
     * Returns a ShadowResult indicating whether to prompt the user.
     *
     * Prompt triggers:
     * - Shadow detects but production doesn't (borderline miss)
     * - Both detect but with very different confidence levels
     */
    fun analyze(
        gyroSamples: FloatArray,
        accelSamples: FloatArray?,
        isResting: Boolean,
        productionResult: TremorFFT.FFTResult,
        experimentalConfig: TremorDetectionConfig,
        analysisOptions: TremorFFT.AnalysisOptions,
        crossSensorSupport: Float = 0f,
        frequencyStability: Float = 0f,
        magnitude: Float = 0f,
        accelMagnitude: Float = 0f,
        activityType: String = "unknown",
        activityConfidence: Float = 0f
    ): ShadowResult {
        // Configure the shadow FFT with relaxed thresholds
        shadowFFT.setConfig(experimentalConfig)

        // Run parallel FFT analysis
        val shadowResult = shadowFFT.analyze(
            gyroSamples,
            isResting,
            options = analysisOptions
        )

        val productionIsTremor = productionResult.isTremor
        val shadowIsTremor = shadowResult.isTremor

        // Determine trigger reason
        val triggerReason = when {
            shadowIsTremor && !productionIsTremor -> "shadow_only"      // Borderline miss
            productionIsTremor && !shadowIsTremor -> "production_only"  // Strong artifact filter
            productionIsTremor && shadowIsTremor -> "both_detect"       // Agreement
            else -> "neither"                                          // No detection
        }

        // Should prompt? Only when there's a disagreement (the interesting cases)
        val shouldPrompt = triggerReason == "shadow_only"

        // Capture feature snapshot for this sample
        val features = FeedbackFeatureSnapshot(
            dominantFrequency = shadowResult.dominantFrequency,
            bandRatio = shadowResult.bandRatio,
            confidence = shadowResult.confidence,
            calibratedConfidence = 0f,  // Not available from FFTResult; populated downstream
            totalPower = shadowResult.totalPower,
            tremorBandPower = shadowResult.tremorBandPower,
            spectralEntropy = shadowResult.spectralEntropy,
            harmonicRatio = shadowResult.harmonicRatio,
            peakProminence = shadowResult.peakProminence,
            crossSensorSupport = crossSensorSupport,
            frequencyStability = frequencyStability,
            magnitude = magnitude,
            accelMagnitude = accelMagnitude,
            activityType = activityType,
            activityConfidence = activityConfidence,
            isResting = isResting
        )

        if (shouldPrompt) {
            Timber.d(
                "Shadow trigger: reason=%s prodConf=%.2f shadowConf=%.2f freq=%.1fHz",
                triggerReason, productionResult.confidence,
                shadowResult.confidence, shadowResult.dominantFrequency
            )
        }

        return ShadowResult(
            productionIsTremor = productionIsTremor,
            experimentalIsTremor = shadowIsTremor,
            shouldPromptUser = shouldPrompt,
            triggerReason = triggerReason,
            features = features
        )
    }
}
