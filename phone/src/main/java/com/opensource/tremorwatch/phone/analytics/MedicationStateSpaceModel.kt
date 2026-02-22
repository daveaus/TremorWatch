package com.opensource.tremorwatch.phone.analytics

import com.opensource.tremorwatch.phone.stats.StatsSample
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class MedicationResponseModelConfig(
    val preBaselineMinutes: Int = 60,          // -60..-15
    val preBaselineEndOffsetMin: Int = 15,
    val postStartMinutes: Int = 15,            // +15..
    val postEndMinutes: Int = 240,             // ..+240
    val onsetSearchEndMinutes: Int = 120,      // +120
    val comparePostStartMinutes: Int = 30,     // +30..+90 delta window
    val comparePostEndMinutes: Int = 90,
    val minConsecutiveBins: Int = 2,
    val posteriorOnThreshold: Double = 0.60,
    val posteriorOffThreshold: Double = 0.40,
    val assumedImprovementFraction: Double = 0.25
)

data class MedicationStatePoint(
    val minuteFromDose: Int,
    val timestampMinute: Long,
    val observedSeverity: Double?,
    val smoothedSeverity: Double?,
    val posteriorOn: Double,
    val usable: Boolean
)

data class MedicationDoseResponse(
    val ingestionTimestamp: Long,
    val inferable: Boolean,
    val status: String,                        // improved / no_change / worse / insufficient
    val reason: String?,
    val preMedian: Double?,
    val postMedian: Double?,
    val deltaPercent: Double?,                 // negative => improved
    val onsetMinutes: Int?,
    val durationMinutes: Int?,
    val maxPosteriorOn: Double?,
    val confidenceScore: Double,
    val stateSeries: List<MedicationStatePoint>
)

/**
 * Bayesian state-space dose response model.
 *
 * Uses a lightweight HMM-style posterior update across minute bins to estimate
 * onset/duration while explicitly carrying uncertainty in sparse windows.
 */
object MedicationStateSpaceModel {

    fun inferDoseResponse(
        allSamples: List<StatsSample>,
        ingestionTimestamp: Long,
        modelConfig: MedicationResponseModelConfig = MedicationResponseModelConfig(),
        gateConfig: MedicationWindowGateConfig = MedicationWindowGateConfig()
    ): MedicationDoseResponse {
        val preStart = ingestionTimestamp - modelConfig.preBaselineMinutes * 60_000L
        val preEnd = ingestionTimestamp - modelConfig.preBaselineEndOffsetMin * 60_000L
        val postStart = ingestionTimestamp + modelConfig.postStartMinutes * 60_000L
        val postEnd = ingestionTimestamp + modelConfig.postEndMinutes * 60_000L

        val preWindow = allSamples.filter { it.timestamp in preStart..preEnd }
        val postWindow = allSamples.filter { it.timestamp in postStart..postEnd }
        val gate = MedicationWindowQualityGate.evaluate(preWindow, postWindow, gateConfig)
        if (!gate.inferable) {
            return MedicationDoseResponse(
                ingestionTimestamp = ingestionTimestamp,
                inferable = false,
                status = "insufficient",
                reason = gate.reason,
                preMedian = null,
                postMedian = null,
                deltaPercent = null,
                onsetMinutes = null,
                durationMinutes = null,
                maxPosteriorOn = null,
                confidenceScore = 0.0,
                stateSeries = emptyList()
            )
        }

        val minuteStart = ingestionTimestamp - modelConfig.preBaselineMinutes * 60_000L
        val minuteEnd = ingestionTimestamp + modelConfig.postEndMinutes * 60_000L
        val minuteBins = bucketByMinute(
            samples = allSamples.filter { it.timestamp in minuteStart..minuteEnd },
            ingestionTimestamp = ingestionTimestamp
        )

        val baselineValues = collectUsableSeverities(minuteBins, ingestionTimestamp, -modelConfig.preBaselineMinutes, -modelConfig.preBaselineEndOffsetMin)
        val preMedian = baselineValues.medianOrNull()
        if (preMedian == null || !preMedian.isFinite()) {
            return MedicationDoseResponse(
                ingestionTimestamp = ingestionTimestamp,
                inferable = false,
                status = "insufficient",
                reason = "Unable to estimate baseline from pre-window",
                preMedian = null,
                postMedian = null,
                deltaPercent = null,
                onsetMinutes = null,
                durationMinutes = null,
                maxPosteriorOn = null,
                confidenceScore = 0.0,
                stateSeries = emptyList()
            )
        }

        val sigma = max(0.20, baselineValues.stdDevOrNull() ?: 0.35)
        val onMean = max(0.0, preMedian * (1.0 - modelConfig.assumedImprovementFraction))

        val points = mutableListOf<MedicationStatePoint>()
        var posteriorOn = 0.10
        var smoothed: Double? = null
        val alpha = 0.35
        var maxPosterior = posteriorOn

        for ((minuteFromDose, minuteTs, samplesInMinute) in minuteBins) {
            val usableMinute = samplesInMinute.any { isUsableForModel(it) }
            val observed = samplesInMinute
                .filter { isUsableForModel(it) }
                .map { severityForModel(it) }
                .medianOrNull()

            smoothed = if (observed == null) {
                smoothed
            } else if (smoothed == null) {
                observed
            } else {
                (1.0 - alpha) * smoothed + alpha * observed
            }

            val pOnTransition = transitionOnProbability(minuteFromDose)
            val pOffTransition = transitionOffProbability(minuteFromDose)
            val predictedOn = posteriorOn * (1.0 - pOffTransition) + (1.0 - posteriorOn) * pOnTransition

            posteriorOn = if (smoothed == null || !smoothed.isFinite()) {
                predictedOn.coerceIn(0.0, 1.0)
            } else {
                val likeOn = gaussianPdf(smoothed, onMean, sigma)
                val likeOff = gaussianPdf(smoothed, preMedian, sigma)
                normalizePosterior(predictedOn, likeOn, likeOff)
            }

            maxPosterior = max(maxPosterior, posteriorOn)
            points += MedicationStatePoint(
                minuteFromDose = minuteFromDose,
                timestampMinute = minuteTs,
                observedSeverity = observed,
                smoothedSeverity = smoothed,
                posteriorOn = posteriorOn,
                usable = usableMinute
            )
        }

        val onset = detectOnset(points, modelConfig)
        val duration = detectDuration(points, onset, modelConfig)

        val postCompareValues = collectUsableSeverities(
            minuteBins = minuteBins,
            ingestionTimestamp = ingestionTimestamp,
            fromMinute = modelConfig.comparePostStartMinutes,
            toMinute = modelConfig.comparePostEndMinutes
        )
        val postMedian = postCompareValues.medianOrNull()
        val deltaPercent = if (postMedian != null && preMedian > 1e-6) {
            ((postMedian - preMedian) / preMedian) * 100.0
        } else {
            null
        }

        val status = when {
            deltaPercent == null -> "insufficient"
            deltaPercent <= -10.0 -> "improved"
            deltaPercent >= 10.0 -> "worse"
            else -> "no_change"
        }

        val confidenceScore = computeConfidenceScore(
            gate = gate,
            maxPosterior = maxPosterior,
            onsetMinutes = onset,
            durationMinutes = duration
        )

        return MedicationDoseResponse(
            ingestionTimestamp = ingestionTimestamp,
            inferable = true,
            status = status,
            reason = null,
            preMedian = preMedian,
            postMedian = postMedian,
            deltaPercent = deltaPercent,
            onsetMinutes = onset,
            durationMinutes = duration,
            maxPosteriorOn = maxPosterior,
            confidenceScore = confidenceScore,
            stateSeries = points
        )
    }

    private fun bucketByMinute(
        samples: List<StatsSample>,
        ingestionTimestamp: Long
    ): List<Triple<Int, Long, List<StatsSample>>> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.timestamp }
        val grouped = sorted.groupBy { (it.timestamp / 60_000L) * 60_000L }
        return grouped.keys.sorted().map { minuteTs ->
            val minuteFromDose = ((minuteTs - ingestionTimestamp) / 60_000L).toInt()
            Triple(minuteFromDose, minuteTs, grouped[minuteTs].orEmpty())
        }
    }

    private fun collectUsableSeverities(
        minuteBins: List<Triple<Int, Long, List<StatsSample>>>,
        ingestionTimestamp: Long,
        fromMinute: Int,
        toMinute: Int
    ): List<Double> {
        val startMs = ingestionTimestamp + fromMinute * 60_000L
        val endMs = ingestionTimestamp + toMinute * 60_000L
        return minuteBins.asSequence()
            .filter { (_, ts, _) -> ts in startMs..endMs }
            .flatMap { (_, _, minuteSamples) -> minuteSamples.asSequence() }
            .filter { isUsableForModel(it) }
            .map { severityForModel(it) }
            .toList()
    }

    private fun isUsableForModel(sample: StatsSample): Boolean {
        val conf = sample.calibratedConfidence ?: sample.confidence ?: 0.0
        return sample.isWorn == true &&
            sample.isCharging != true &&
            sample.excludeFromAnalysis != true &&
            sample.isReliableMeasurement == true &&
            conf >= 0.15
    }

    private fun severityForModel(sample: StatsSample): Double {
        return (sample.activityAdjustedSeverity ?: sample.severityRaw).coerceIn(0.0, 10.0)
    }

    private fun transitionOnProbability(minuteFromDose: Int): Double {
        return when {
            minuteFromDose < 0 -> 0.01
            minuteFromDose < 15 -> 0.04
            minuteFromDose <= 90 -> 0.18
            minuteFromDose <= 150 -> 0.08
            else -> 0.03
        }
    }

    private fun transitionOffProbability(minuteFromDose: Int): Double {
        return when {
            minuteFromDose < 90 -> 0.02
            minuteFromDose < 150 -> 0.06
            minuteFromDose < 240 -> 0.14
            else -> 0.22
        }
    }

    private fun gaussianPdf(x: Double, mean: Double, sigma: Double): Double {
        val s = sigma.coerceAtLeast(1e-3)
        val z = (x - mean) / s
        val coef = 1.0 / (sqrt(2.0 * Math.PI) * s)
        return coef * exp(-0.5 * z * z)
    }

    private fun normalizePosterior(predictedOn: Double, likeOn: Double, likeOff: Double): Double {
        val numerator = predictedOn * likeOn
        val denominator = numerator + (1.0 - predictedOn) * likeOff
        if (denominator <= 1e-12) return predictedOn.coerceIn(0.0, 1.0)
        return (numerator / denominator).coerceIn(0.0, 1.0)
    }

    private fun detectOnset(
        points: List<MedicationStatePoint>,
        config: MedicationResponseModelConfig
    ): Int? {
        val candidates = points.filter { it.minuteFromDose in config.postStartMinutes..config.onsetSearchEndMinutes }
        var consecutive = 0
        for (p in candidates) {
            if (p.posteriorOn >= config.posteriorOnThreshold && p.usable) {
                consecutive++
                if (consecutive >= config.minConsecutiveBins) {
                    return p.minuteFromDose - (config.minConsecutiveBins - 1)
                }
            } else {
                consecutive = 0
            }
        }
        return null
    }

    private fun detectDuration(
        points: List<MedicationStatePoint>,
        onsetMinutes: Int?,
        config: MedicationResponseModelConfig
    ): Int? {
        val onset = onsetMinutes ?: return null
        val afterOnset = points.filter { it.minuteFromDose >= onset }
        var consecutiveOff = 0
        for (p in afterOnset) {
            if (p.posteriorOn <= config.posteriorOffThreshold || !p.usable) {
                consecutiveOff++
                if (consecutiveOff >= config.minConsecutiveBins) {
                    val wearOff = p.minuteFromDose - (config.minConsecutiveBins - 1)
                    return (wearOff - onset).coerceAtLeast(0)
                }
            } else {
                consecutiveOff = 0
            }
        }
        return (config.postEndMinutes - onset).coerceAtLeast(0)
    }

    private fun computeConfidenceScore(
        gate: MedicationWindowEvaluation,
        maxPosterior: Double,
        onsetMinutes: Int?,
        durationMinutes: Int?
    ): Double {
        if (!gate.inferable) return 0.0
        val coverage = (
            gate.pre.clinicalMinutes.coerceAtLeast(0) +
                gate.post.clinicalMinutes.coerceAtLeast(0)
            ).toDouble() / 180.0
        val onsetScore = if (onsetMinutes != null) 1.0 else 0.6
        val durationScore = if (durationMinutes != null) 1.0 else 0.7
        return (0.45 * coverage.coerceIn(0.0, 1.0) +
            0.35 * maxPosterior.coerceIn(0.0, 1.0) +
            0.10 * onsetScore +
            0.10 * durationScore).coerceIn(0.0, 1.0)
    }

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun List<Double>.stdDevOrNull(): Double? {
        if (size < 2) return null
        val m = average()
        val v = map { (it - m).pow(2.0) }.average()
        return sqrt(v)
    }
}
