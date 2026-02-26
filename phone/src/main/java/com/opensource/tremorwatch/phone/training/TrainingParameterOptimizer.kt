package com.opensource.tremorwatch.phone.training

import com.opensource.tremorwatch.phone.data.TrainingLabelEntity
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Personalization optimizer driven by user labels.
 *
 * V2 behaviors:
 * - Proportional fixed-bucket reconstruction for band-power simulation.
 * - Sequential tuning phases to reduce coupling instability:
 *   1) bands-only (if eligible) then stop
 *   2) thresholds-only on subsequent run
 * - Capped parameter changes per run.
 */
class TrainingParameterOptimizer {

    companion object {
        const val CROSS_VALIDATION_FOLDS = 5
        const val EMA_ALPHA = 0.3f
        const val MAX_STEP_FRACTION = 0.15f
        const val MAX_PARAM_CHANGES_PER_RUN = 3

        const val MIN_LABELS_CORE = 10
        const val MIN_LABELS_BAND = 30
        const val MIN_LABELS_ADVANCED = 50
        const val MIN_DELTA_ACCEPT = -0.02f
        const val MIN_J_QUALITY = 0.10f
    }

    data class ParamBounds(
        val min: Float,
        val max: Float,
        val maxStepFraction: Float = MAX_STEP_FRACTION
    ) {
        fun clamp(value: Float) = value.coerceIn(min, max)
        fun maxStep() = (max - min) * maxStepFraction
    }

    data class ParamDelta(
        val key: String,
        val before: Float,
        val after: Float
    )

    enum class TunePhase {
        NONE,
        BANDS_ONLY,
        THRESHOLDS_ONLY
    }

    data class OptimizationResult(
        val optimizedConfig: TremorDetectionConfig,
        val beforeJ: Float,
        val afterJ: Float,
        val samplesUsed: Int,
        val applied: Boolean,
        val reason: String,
        val changedParams: List<ParamDelta> = emptyList(),
        val phase: TunePhase = TunePhase.NONE
    )

    private val thresholdBounds = mapOf(
        "confidenceThreshold" to ParamBounds(0.10f, 0.80f),
        "minBandRatio" to ParamBounds(0.01f, 0.20f),
        "restingMinBandRatio" to ParamBounds(0.01f, 0.20f),
        "activeMinBandRatio" to ParamBounds(0.02f, 0.30f),
        "minFrequencyStability" to ParamBounds(0.0f, 1.0f),
        "minHarmonicRatio" to ParamBounds(0.0f, 2.0f),
        "minCrossSensorSupport" to ParamBounds(0.0f, 1.0f),
        "minTremorPower" to ParamBounds(0.0001f, 0.02f)
    )

    private val bandPriorityKeys = listOf(
        "restingBandLowHz",
        "restingBandHighHz",
        "activeBandLowHz",
        "activeBandHighHz",
        "minFrequencyHz"
    )

    private val thresholdPriorityKeys = listOf(
        "confidenceThreshold",
        "minBandRatio",
        "restingMinBandRatio",
        "activeMinBandRatio",
        "minFrequencyStability",
        "minHarmonicRatio",
        "minCrossSensorSupport",
        "minTremorPower"
    )

    fun optimize(
        currentConfig: TremorDetectionConfig,
        labels: List<TrainingLabelEntity>,
        enableExpandedPowerGate: Boolean = false
    ): OptimizationResult {
        val usable = labels.filter { it.label == "YES_TREMOR" || it.label == "NO_ACTIVE" }
        val positives = usable.filter { it.label == "YES_TREMOR" }
        val negatives = usable.filter { it.label == "NO_ACTIVE" }

        val coreEligible = usable.size >= MIN_LABELS_CORE && positives.size >= 5 && negatives.size >= 5
        if (!coreEligible) {
            return OptimizationResult(
                optimizedConfig = currentConfig,
                beforeJ = 0f,
                afterJ = 0f,
                samplesUsed = usable.size,
                applied = false,
                reason = "Insufficient labels: ${positives.size}Y/${negatives.size}N (need at least 5/5 and $MIN_LABELS_CORE total)"
            )
        }

        val beforeJ = crossValidate(currentConfig, usable, includePowerGate = false)

        val restingCount = usable.count { it.isResting }
        val activeCount = usable.size - restingCount
        val bandEligible =
            usable.size >= MIN_LABELS_BAND &&
                positives.size >= 10 &&
                negatives.size >= 10 &&
                restingCount >= 5 &&
                activeCount >= 5

        // Phase 1: band geometry only. If changed and improved, stop here.
        if (bandEligible) {
            val proposedBands = optimizeBandGeometry(currentConfig, usable)
            val limitedBand = applyLimitedParamChanges(
                baseConfig = currentConfig,
                proposedConfig = proposedBands,
                orderedKeys = bandPriorityKeys,
                maxChanges = MAX_PARAM_CHANGES_PER_RUN
            )
            if (limitedBand.changed.isNotEmpty()) {
                val normalized = normalizeFrequencyBounds(limitedBand.config)
                val afterBandJ = crossValidate(normalized, usable, includePowerGate = false)
                if (afterBandJ > beforeJ + 1e-4f) {
                    val changed = diffByKeys(currentConfig, normalized, bandPriorityKeys)
                        .take(MAX_PARAM_CHANGES_PER_RUN)
                    return OptimizationResult(
                        optimizedConfig = normalized,
                        beforeJ = beforeJ,
                        afterJ = afterBandJ,
                        samplesUsed = usable.size,
                        applied = true,
                        reason = "Applied band boundary updates; threshold tuning deferred to next run",
                        changedParams = changed,
                        phase = TunePhase.BANDS_ONLY
                    )
                }
            }
        }

        // Phase 2: thresholds only
        val advancedEligible = usable.size >= MIN_LABELS_ADVANCED && positives.size >= 15 && negatives.size >= 15
        val allowPowerTuning = enableExpandedPowerGate && advancedEligible

        val proposedThresholds = optimizeThresholds(currentConfig, usable, allowPowerTuning)
        val thresholdKeys =
            if (allowPowerTuning) thresholdPriorityKeys else thresholdPriorityKeys.filter { it != "minTremorPower" }

        val limitedThreshold = applyLimitedParamChanges(
            baseConfig = currentConfig,
            proposedConfig = proposedThresholds,
            orderedKeys = thresholdKeys,
            maxChanges = MAX_PARAM_CHANGES_PER_RUN
        )
        val finalConfig = normalizeFrequencyBounds(limitedThreshold.config)
        val afterJ = crossValidate(finalConfig, usable, includePowerGate = allowPowerTuning)

        if (limitedThreshold.changed.isEmpty()) {
            return OptimizationResult(
                optimizedConfig = currentConfig,
                beforeJ = beforeJ,
                afterJ = beforeJ,
                samplesUsed = usable.size,
                applied = false,
                reason = "No threshold updates improved score"
            )
        }

        return if (afterJ >= beforeJ + MIN_DELTA_ACCEPT) {
            val changed = diffByKeys(currentConfig, finalConfig, thresholdKeys)
                .take(MAX_PARAM_CHANGES_PER_RUN)
            OptimizationResult(
                optimizedConfig = finalConfig,
                beforeJ = beforeJ,
                afterJ = afterJ,
                samplesUsed = usable.size,
                applied = true,
                reason = "Applied threshold updates",
                changedParams = changed,
                phase = TunePhase.THRESHOLDS_ONLY
            )
        } else {
            OptimizationResult(
                optimizedConfig = currentConfig,
                beforeJ = beforeJ,
                afterJ = afterJ,
                samplesUsed = usable.size,
                applied = false,
                reason = "Rejected: J regressed from %.3f to %.3f".format(beforeJ, afterJ),
                changedParams = emptyList(),
                phase = TunePhase.NONE
            )
        }
    }

    private fun optimizeBandGeometry(
        currentConfig: TremorDetectionConfig,
        labels: List<TrainingLabelEntity>
    ): TremorDetectionConfig {
        var bestConfig = currentConfig
        var bestJ = crossValidate(currentConfig, labels, includePowerGate = false)

        val restingLowCandidates = listOf(3f, 4f, 5f, 6f)
        val restingHighCandidates = listOf(5f, 6f, 7f, 8f, 9f)
        val activeLowCandidates = listOf(3f, 4f, 5f, 6f, 7f, 8f)
        val activeHighCandidates = listOf(6f, 8f, 10f, 12f, 14f)

        for (restingLow in restingLowCandidates) {
            for (restingHigh in restingHighCandidates) {
                if (restingHigh <= restingLow || (restingHigh - restingLow) < 1.5f) continue
                for (activeLow in activeLowCandidates) {
                    for (activeHigh in activeHighCandidates) {
                        if (activeHigh <= activeLow || (activeHigh - activeLow) < 2f) continue

                        val minFreq = min(currentConfig.minFrequencyHz, min(restingLow, activeLow))
                        val candidate = normalizeFrequencyBounds(
                            currentConfig.copy(
                                restingBandLowHz = restingLow,
                                restingBandHighHz = restingHigh,
                                activeBandLowHz = activeLow,
                                activeBandHighHz = activeHigh,
                                minFrequencyHz = minFreq
                            )
                        )

                        val score = crossValidate(candidate, labels, includePowerGate = false)
                        if (score > bestJ + 1e-4f) {
                            bestJ = score
                            bestConfig = candidate
                        }
                    }
                }
            }
        }

        return bestConfig
    }

    private fun optimizeThresholds(
        currentConfig: TremorDetectionConfig,
        labels: List<TrainingLabelEntity>,
        allowPowerTuning: Boolean
    ): TremorDetectionConfig {
        val positives = labels.filter { it.label == "YES_TREMOR" }
        val negatives = labels.filter { it.label == "NO_ACTIVE" }
        var config = currentConfig

        val generalPosBandRatio = positives.map { bandRatioForConfig(it, currentConfig) }
        val generalNegBandRatio = negatives.map { bandRatioForConfig(it, currentConfig) }
        val restingPosBandRatio = positives.filter { it.isResting }.map { bandRatioForConfig(it, currentConfig) }
        val restingNegBandRatio = negatives.filter { it.isResting }.map { bandRatioForConfig(it, currentConfig) }
        val activePosBandRatio = positives.filter { !it.isResting }.map { bandRatioForConfig(it, currentConfig) }
        val activeNegBandRatio = negatives.filter { !it.isResting }.map { bandRatioForConfig(it, currentConfig) }

        config = config.copy(
            confidenceThreshold = findOptimalThreshold(
                positives.map { it.confidence },
                negatives.map { it.confidence },
                config.confidenceThreshold,
                thresholdBounds.getValue("confidenceThreshold")
            ),
            minBandRatio = findOptimalThreshold(
                generalPosBandRatio,
                generalNegBandRatio,
                config.minBandRatio,
                thresholdBounds.getValue("minBandRatio")
            ),
            minFrequencyStability = findOptimalThreshold(
                positives.map { it.frequencyStability },
                negatives.map { it.frequencyStability },
                config.minFrequencyStability,
                thresholdBounds.getValue("minFrequencyStability")
            ),
            minHarmonicRatio = findOptimalThreshold(
                positives.map { it.harmonicRatio },
                negatives.map { it.harmonicRatio },
                config.minHarmonicRatio,
                thresholdBounds.getValue("minHarmonicRatio")
            ),
            minCrossSensorSupport = findOptimalThreshold(
                positives.map { it.crossSensorSupport },
                negatives.map { it.crossSensorSupport },
                config.minCrossSensorSupport,
                thresholdBounds.getValue("minCrossSensorSupport")
            )
        )

        if (restingPosBandRatio.size >= 3 && restingNegBandRatio.size >= 3) {
            config = config.copy(
                restingMinBandRatio = findOptimalThreshold(
                    restingPosBandRatio,
                    restingNegBandRatio,
                    config.restingMinBandRatio,
                    thresholdBounds.getValue("restingMinBandRatio")
                )
            )
        }

        if (activePosBandRatio.size >= 3 && activeNegBandRatio.size >= 3) {
            config = config.copy(
                activeMinBandRatio = findOptimalThreshold(
                    activePosBandRatio,
                    activeNegBandRatio,
                    config.activeMinBandRatio,
                    thresholdBounds.getValue("activeMinBandRatio")
                )
            )
        }

        if (allowPowerTuning) {
            config = config.copy(
                minTremorPower = findOptimalThreshold(
                    positives.map { bandPowerForConfig(it, currentConfig) },
                    negatives.map { bandPowerForConfig(it, currentConfig) },
                    config.minTremorPower,
                    thresholdBounds.getValue("minTremorPower")
                )
            )
        }

        return normalizeFrequencyBounds(config)
    }

    private fun findOptimalThreshold(
        positiveValues: List<Float>,
        negativeValues: List<Float>,
        currentThreshold: Float,
        paramBounds: ParamBounds
    ): Float {
        val posClean = positiveValues.filter { it.isFinite() }
        val negClean = negativeValues.filter { it.isFinite() }
        if (posClean.size < 3 || negClean.size < 3) return currentThreshold

        val allValues = posClean + negClean
        val minVal = allValues.min()
        val maxVal = allValues.max()
        if (maxVal - minVal < 1e-7f) {
            Timber.d("Zero-variance feature; skipping threshold update")
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

        if (bestJ < MIN_J_QUALITY) {
            return currentThreshold
        }

        val rawDelta = bestThreshold - currentThreshold
        val clampedDelta = rawDelta.coerceIn(-paramBounds.maxStep(), paramBounds.maxStep())
        val smoothed = currentThreshold + EMA_ALPHA * clampedDelta
        return paramBounds.clamp(smoothed)
    }

    private fun simulateDetection(
        config: TremorDetectionConfig,
        label: TrainingLabelEntity,
        includePowerGate: Boolean
    ): Boolean {
        val dynamicBandRatio = bandRatioForConfig(label, config)
        val dynamicBandPower = bandPowerForConfig(label, config)
        val stateBR = if (label.isResting) config.restingMinBandRatio else config.activeMinBandRatio
        val effectiveBR = max(config.minBandRatio, stateBR)

        val meetsPowerGate = !includePowerGate || dynamicBandPower >= config.minTremorPower
        return dynamicBandRatio >= effectiveBR &&
            label.confidence >= config.confidenceThreshold &&
            label.dominantFrequency >= config.minFrequencyHz &&
            label.frequencyStability >= config.minFrequencyStability &&
            label.harmonicRatio >= config.minHarmonicRatio &&
            label.crossSensorSupport >= config.minCrossSensorSupport &&
            meetsPowerGate
    }

    private fun bandRatioForConfig(label: TrainingLabelEntity, config: TremorDetectionConfig): Float {
        val power = bandPowerForConfig(label, config)
        if (label.totalPower > 0f) {
            return (power / label.totalPower).coerceAtLeast(0f)
        }
        return label.bandRatio.coerceAtLeast(0f)
    }

    private fun bandPowerForConfig(label: TrainingLabelEntity, config: TremorDetectionConfig): Float {
        val low = if (label.isResting) config.restingBandLowHz else config.activeBandLowHz
        val high = if (label.isResting) config.restingBandHighHz else config.activeBandHighHz
        val hasBuckets =
            label.bandPower2to4Hz > 0f ||
                label.bandPower4to6Hz > 0f ||
                label.bandPower6to8Hz > 0f ||
                label.bandPower8to10Hz > 0f ||
                label.bandPower10to12Hz > 0f ||
                label.bandPower12to14Hz > 0f

        if (!hasBuckets) {
            return if (label.tremorBandPower > 0f) label.tremorBandPower
            else (label.bandRatio * label.totalPower).coerceAtLeast(0f)
        }
        return sumBucketedPower(label, low, high)
    }

    private fun sumBucketedPower(label: TrainingLabelEntity, lowHz: Float, highHz: Float): Float {
        val buckets = arrayOf(
            Triple(2f, 4f, label.bandPower2to4Hz),
            Triple(4f, 6f, label.bandPower4to6Hz),
            Triple(6f, 8f, label.bandPower6to8Hz),
            Triple(8f, 10f, label.bandPower8to10Hz),
            Triple(10f, 12f, label.bandPower10to12Hz),
            Triple(12f, 14f, label.bandPower12to14Hz)
        )
        var sum = 0f
        for ((bucketLow, bucketHigh, power) in buckets) {
            val overlapLow = max(lowHz, bucketLow)
            val overlapHigh = min(highHz, bucketHigh)
            if (overlapHigh > overlapLow) {
                val fraction = (overlapHigh - overlapLow) / (bucketHigh - bucketLow)
                sum += power * fraction
            }
        }
        return sum
    }

    private fun evaluateConfig(
        config: TremorDetectionConfig,
        testSet: List<TrainingLabelEntity>,
        includePowerGate: Boolean
    ): Float {
        val positives = testSet.filter { it.label == "YES_TREMOR" }
        val negatives = testSet.filter { it.label == "NO_ACTIVE" }
        if (positives.isEmpty() || negatives.isEmpty()) return 0f

        val tp = positives.count { simulateDetection(config, it, includePowerGate) }.toFloat()
        val fn = positives.count { !simulateDetection(config, it, includePowerGate) }.toFloat()
        val sensitivity = if (tp + fn > 0f) tp / (tp + fn) else 0f

        val tn = negatives.count { !simulateDetection(config, it, includePowerGate) }.toFloat()
        val fp = negatives.count { simulateDetection(config, it, includePowerGate) }.toFloat()
        val specificity = if (tn + fp > 0f) tn / (tn + fp) else 0f

        return sensitivity + specificity - 1f
    }

    private fun crossValidate(
        config: TremorDetectionConfig,
        labels: List<TrainingLabelEntity>,
        includePowerGate: Boolean
    ): Float {
        val positives = labels.filter { it.label == "YES_TREMOR" }.shuffled()
        val negatives = labels.filter { it.label == "NO_ACTIVE" }.shuffled()

        var totalJ = 0f
        var foldsWithData = 0
        for (fold in 0 until CROSS_VALIDATION_FOLDS) {
            val posTest = positives.filterIndexed { index, _ -> index % CROSS_VALIDATION_FOLDS == fold }
            val negTest = negatives.filterIndexed { index, _ -> index % CROSS_VALIDATION_FOLDS == fold }
            val testSet = posTest + negTest
            if (testSet.isEmpty()) continue
            totalJ += evaluateConfig(config, testSet, includePowerGate)
            foldsWithData++
        }
        return if (foldsWithData > 0) totalJ / foldsWithData else 0f
    }

    private data class LimitedChangeResult(
        val config: TremorDetectionConfig,
        val changed: List<ParamDelta>
    )

    private fun applyLimitedParamChanges(
        baseConfig: TremorDetectionConfig,
        proposedConfig: TremorDetectionConfig,
        orderedKeys: List<String>,
        maxChanges: Int
    ): LimitedChangeResult {
        var updated = baseConfig
        val changed = mutableListOf<ParamDelta>()
        for (key in orderedKeys) {
            if (changed.size >= maxChanges) break
            val before = getFloatValue(baseConfig, key)
            val after = getFloatValue(proposedConfig, key)
            if (abs(before - after) <= 1e-6f) continue
            updated = setFloatValue(updated, key, after)
            changed += ParamDelta(key = key, before = before, after = after)
        }
        return LimitedChangeResult(updated, changed)
    }

    private fun diffByKeys(
        before: TremorDetectionConfig,
        after: TremorDetectionConfig,
        keys: List<String>
    ): List<ParamDelta> {
        return keys.mapNotNull { key ->
            val b = getFloatValue(before, key)
            val a = getFloatValue(after, key)
            if (abs(b - a) > 1e-6f) ParamDelta(key, b, a) else null
        }
    }

    private fun normalizeFrequencyBounds(config: TremorDetectionConfig): TremorDetectionConfig {
        val restingLow = config.restingBandLowHz.coerceIn(3f, 7f)
        val restingHigh = config.restingBandHighHz.coerceIn(restingLow + 0.5f, 9f)
        val activeLow = config.activeBandLowHz.coerceIn(3f, 8f)
        val activeHigh = config.activeBandHighHz.coerceIn(activeLow + 1f, 14f)
        val minFreq = config.minFrequencyHz.coerceIn(3f, min(restingLow, activeLow))
        return config.copy(
            restingBandLowHz = restingLow,
            restingBandHighHz = restingHigh,
            activeBandLowHz = activeLow,
            activeBandHighHz = activeHigh,
            minFrequencyHz = minFreq
        )
    }

    private fun getFloatValue(config: TremorDetectionConfig, key: String): Float {
        return when (key) {
            "confidenceThreshold" -> config.confidenceThreshold
            "minBandRatio" -> config.minBandRatio
            "restingMinBandRatio" -> config.restingMinBandRatio
            "activeMinBandRatio" -> config.activeMinBandRatio
            "minFrequencyStability" -> config.minFrequencyStability
            "minHarmonicRatio" -> config.minHarmonicRatio
            "minCrossSensorSupport" -> config.minCrossSensorSupport
            "restingBandLowHz" -> config.restingBandLowHz
            "restingBandHighHz" -> config.restingBandHighHz
            "activeBandLowHz" -> config.activeBandLowHz
            "activeBandHighHz" -> config.activeBandHighHz
            "minFrequencyHz" -> config.minFrequencyHz
            "minTremorPower" -> config.minTremorPower
            else -> 0f
        }
    }

    private fun setFloatValue(config: TremorDetectionConfig, key: String, value: Float): TremorDetectionConfig {
        return when (key) {
            "confidenceThreshold" -> config.copy(confidenceThreshold = value)
            "minBandRatio" -> config.copy(minBandRatio = value)
            "restingMinBandRatio" -> config.copy(restingMinBandRatio = value)
            "activeMinBandRatio" -> config.copy(activeMinBandRatio = value)
            "minFrequencyStability" -> config.copy(minFrequencyStability = value)
            "minHarmonicRatio" -> config.copy(minHarmonicRatio = value)
            "minCrossSensorSupport" -> config.copy(minCrossSensorSupport = value)
            "restingBandLowHz" -> config.copy(restingBandLowHz = value)
            "restingBandHighHz" -> config.copy(restingBandHighHz = value)
            "activeBandLowHz" -> config.copy(activeBandLowHz = value)
            "activeBandHighHz" -> config.copy(activeBandHighHz = value)
            "minFrequencyHz" -> config.copy(minFrequencyHz = value)
            "minTremorPower" -> config.copy(minTremorPower = value)
            else -> config
        }
    }
}
