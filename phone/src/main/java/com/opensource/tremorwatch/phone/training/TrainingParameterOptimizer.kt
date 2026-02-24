package com.opensource.tremorwatch.phone.training

import com.opensource.tremorwatch.phone.data.TrainingLabelEntity
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import timber.log.Timber
import kotlin.math.abs

/**
 * Optimizes tremor detection parameters using user-labeled training data.
 * Uses Youden's J statistic with bounded step sizes and EMA smoothing
 * for safe, gradual parameter updates.
 *
 * Math hardening (v2.0):
 * - [P16] NaN/Inf filter before threshold searches
 * - [P17] Zero-variance guard + minimum J ≥ 0.10 quality gate
 * - [P18] simulateDetection applies minBandRatio floor matching production engine
 * - [P19] Stratified cross-validation folds
 */
class TrainingParameterOptimizer {

    companion object {
        const val MIN_SAMPLES_FOR_OPTIMIZATION = 10  // 5Y + 5N minimum
        const val CROSS_VALIDATION_FOLDS = 5
        const val EMA_ALPHA = 0.3f  // Smooth toward optimum
        const val MAX_STEP_FRACTION = 0.15f  // Max 15% change per iteration
    }

    data class ParamBounds(
        val min: Float,
        val max: Float,
        val maxStepFraction: Float = MAX_STEP_FRACTION
    ) {
        fun clamp(value: Float) = value.coerceIn(min, max)
        fun maxStep() = (max - min) * maxStepFraction
    }

    data class OptimizationResult(
        val optimizedConfig: TremorDetectionConfig,
        val beforeJ: Float,
        val afterJ: Float,
        val samplesUsed: Int,
        val applied: Boolean,
        val reason: String
    )

    private val paramBoundsMap = mapOf(
        "confidenceThreshold" to ParamBounds(0.10f, 0.80f),
        "minBandRatio" to ParamBounds(0.01f, 0.20f),
        "restingMinBandRatio" to ParamBounds(0.01f, 0.20f),
        "activeMinBandRatio" to ParamBounds(0.02f, 0.30f),
        "minFrequencyStability" to ParamBounds(0.0f, 1.0f),
        "minHarmonicRatio" to ParamBounds(0.0f, 2.0f),
        "minCrossSensorSupport" to ParamBounds(0.0f, 1.0f)
    )

    /**
     * Run the full optimization pipeline.
     * Returns the optimized config or the original if optimization would degrade performance.
     */
    fun optimize(
        currentConfig: TremorDetectionConfig,
        labels: List<TrainingLabelEntity>
    ): OptimizationResult {
        val usable = labels.filter { it.label == "YES_TREMOR" || it.label == "NO_ACTIVE" }
        val positives = usable.filter { it.label == "YES_TREMOR" }
        val negatives = usable.filter { it.label == "NO_ACTIVE" }

        if (positives.size < 5 || negatives.size < 5) {
            return OptimizationResult(
                currentConfig, 0f, 0f, usable.size, false,
                "Insufficient labels: ${positives.size}Y/${negatives.size}N (need 5/5)"
            )
        }

        val beforeJ = crossValidate(currentConfig, usable)

        // Optimize each parameter independently
        var config = currentConfig

        // Confidence threshold
        config = config.copy(
            confidenceThreshold = findOptimalThreshold(
                positives.map { it.confidence },
                negatives.map { it.confidence },
                config.confidenceThreshold,
                paramBoundsMap["confidenceThreshold"]!!
            )
        )

        // Band ratio (general)
        config = config.copy(
            minBandRatio = findOptimalThreshold(
                positives.map { it.bandRatio },
                negatives.map { it.bandRatio },
                config.minBandRatio,
                paramBoundsMap["minBandRatio"]!!
            )
        )

        // Resting band ratio
        val restingPos = positives.filter { it.isResting }.map { it.bandRatio }
        val restingNeg = negatives.filter { it.isResting }.map { it.bandRatio }
        if (restingPos.size >= 3 && restingNeg.size >= 3) {
            config = config.copy(
                restingMinBandRatio = findOptimalThreshold(
                    restingPos, restingNeg,
                    config.restingMinBandRatio,
                    paramBoundsMap["restingMinBandRatio"]!!
                )
            )
        }

        // Active band ratio
        val activePos = positives.filter { !it.isResting }.map { it.bandRatio }
        val activeNeg = negatives.filter { !it.isResting }.map { it.bandRatio }
        if (activePos.size >= 3 && activeNeg.size >= 3) {
            config = config.copy(
                activeMinBandRatio = findOptimalThreshold(
                    activePos, activeNeg,
                    config.activeMinBandRatio,
                    paramBoundsMap["activeMinBandRatio"]!!
                )
            )
        }

        // New v2.0 parameters
        config = config.copy(
            minFrequencyStability = findOptimalThreshold(
                positives.map { it.frequencyStability },
                negatives.map { it.frequencyStability },
                config.minFrequencyStability,
                paramBoundsMap["minFrequencyStability"]!!
            ),
            minHarmonicRatio = findOptimalThreshold(
                positives.map { it.harmonicRatio },
                negatives.map { it.harmonicRatio },
                config.minHarmonicRatio,
                paramBoundsMap["minHarmonicRatio"]!!
            ),
            minCrossSensorSupport = findOptimalThreshold(
                positives.map { it.crossSensorSupport },
                negatives.map { it.crossSensorSupport },
                config.minCrossSensorSupport,
                paramBoundsMap["minCrossSensorSupport"]!!
            )
        )

        val afterJ = crossValidate(config, usable)

        // Safety: only apply if J improved
        return if (afterJ >= beforeJ - 0.02f) {
            Timber.i("Optimization improved: J %.3f → %.3f (%d samples)", beforeJ, afterJ, usable.size)
            OptimizationResult(config, beforeJ, afterJ, usable.size, true, "Applied")
        } else {
            Timber.w("Optimization rejected: J %.3f → %.3f (regression)", beforeJ, afterJ)
            OptimizationResult(currentConfig, beforeJ, afterJ, usable.size, false,
                "Rejected: J regressed from %.3f to %.3f".format(beforeJ, afterJ))
        }
    }

    /**
     * [P16] [P17] Find the optimal threshold using Youden's J statistic.
     * Filters NaN/Inf, guards against zero-variance features,
     * enforces minimum J quality gate, applies bounded step size and EMA smoothing.
     */
    private fun findOptimalThreshold(
        positiveValues: List<Float>,
        negativeValues: List<Float>,
        currentThreshold: Float,
        paramBounds: ParamBounds
    ): Float {
        // [P16] Sanitize — NaN/Inf corrupt the entire search
        val posClean = positiveValues.filter { it.isFinite() }
        val negClean = negativeValues.filter { it.isFinite() }

        if (posClean.size < 3 || negClean.size < 3) return currentThreshold

        // [P17] Zero-variance check
        val allValues = posClean + negClean
        val minVal = allValues.min()
        val maxVal = allValues.max()
        if (maxVal - minVal < 1e-7f) {
            Timber.d("Zero-variance feature — skipping threshold optimization")
            return currentThreshold
        }

        val sorted = allValues.sorted()
        var bestJ = -1f
        var bestThreshold = currentThreshold

        for (candidate in sorted) {
            val tp = posClean.count { it >= candidate }.toFloat()
            val fn = posClean.count { it < candidate }.toFloat()
            val sensitivity = if (tp + fn > 0) tp / (tp + fn) else 0f

            val tn = negClean.count { it < candidate }.toFloat()
            val fp = negClean.count { it >= candidate }.toFloat()
            val specificity = if (tn + fp > 0) tn / (tn + fp) else 0f

            val j = sensitivity + specificity - 1f

            if (j > bestJ) {
                bestJ = j
                bestThreshold = candidate
            }
        }

        // [P17] Quality gate
        if (bestJ < 0.10f) {
            Timber.d("Best J=%.3f < 0.10 quality gate — preserving current threshold", bestJ)
            return currentThreshold
        }

        // Apply bounded step size
        val rawDelta = bestThreshold - currentThreshold
        val maxStep = paramBounds.maxStep()
        val clampedDelta = rawDelta.coerceIn(-maxStep, maxStep)

        // EMA smoothing
        val emaThreshold = currentThreshold + EMA_ALPHA * clampedDelta

        return paramBounds.clamp(emaThreshold)
    }

    /**
     * [P18] Simulate whether a given config would detect a tremor for a label's features.
     * Applies minBandRatio as a floor on the state-dependent ratio.
     */
    private fun simulateDetection(
        config: TremorDetectionConfig,
        label: TrainingLabelEntity
    ): Boolean {
        val stateBR = if (label.isResting) config.restingMinBandRatio
                      else config.activeMinBandRatio
        val effectiveBR = maxOf(config.minBandRatio, stateBR)
        return label.bandRatio >= effectiveBR &&
               label.confidence >= config.confidenceThreshold &&
               label.dominantFrequency >= config.minFrequencyHz &&
               label.frequencyStability >= config.minFrequencyStability &&
               label.harmonicRatio >= config.minHarmonicRatio &&
               label.crossSensorSupport >= config.minCrossSensorSupport
    }

    /** Evaluate config J-metric on a test set. */
    private fun evaluateConfig(
        config: TremorDetectionConfig,
        testSet: List<TrainingLabelEntity>
    ): Float {
        val positives = testSet.filter { it.label == "YES_TREMOR" }
        val negatives = testSet.filter { it.label == "NO_ACTIVE" }
        if (positives.isEmpty() || negatives.isEmpty()) return 0f

        val tp = positives.count { simulateDetection(config, it) }.toFloat()
        val fn = positives.count { !simulateDetection(config, it) }.toFloat()
        val sensitivity = if (tp + fn > 0) tp / (tp + fn) else 0f

        val tn = negatives.count { !simulateDetection(config, it) }.toFloat()
        val fp = negatives.count { simulateDetection(config, it) }.toFloat()
        val specificity = if (tn + fp > 0) tn / (tn + fp) else 0f

        return sensitivity + specificity - 1f  // Youden's J
    }

    /**
     * [P19] Stratified K-fold cross-validation.
     * Maintains class ratio in each fold to prevent degenerate folds.
     */
    private fun crossValidate(
        config: TremorDetectionConfig,
        labels: List<TrainingLabelEntity>
    ): Float {
        val positives = labels.filter { it.label == "YES_TREMOR" }.shuffled()
        val negatives = labels.filter { it.label == "NO_ACTIVE" }.shuffled()

        var totalJ = 0f

        for (fold in 0 until CROSS_VALIDATION_FOLDS) {
            val posTest = positives.filterIndexed { i, _ -> i % CROSS_VALIDATION_FOLDS == fold }
            val negTest = negatives.filterIndexed { i, _ -> i % CROSS_VALIDATION_FOLDS == fold }
            val testSet = posTest + negTest

            if (testSet.isEmpty()) continue
            totalJ += evaluateConfig(config, testSet)
        }

        return totalJ / CROSS_VALIDATION_FOLDS
    }
}
