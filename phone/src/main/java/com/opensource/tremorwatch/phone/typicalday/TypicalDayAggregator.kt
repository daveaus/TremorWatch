package com.opensource.tremorwatch.phone.typicalday

import com.opensource.tremorwatch.phone.database.MinuteAggregateRow
import com.opensource.tremorwatch.phone.database.RatingSampleRow
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

object DailyTremorProfileAggregator {

    private data class ObjectivePoint(
        val severity: Double,
        val sampleCount: Int,
        val epochDay: Long
    )

    fun build(
        config: DailyTremorProfileConfig,
        minuteRows: List<MinuteAggregateRow>,
        ratingRows: List<RatingSampleRow>,
        nowMs: Long = System.currentTimeMillis()
    ): DailyTremorProfile {
        require(1440 % config.bucketMinutes == 0) {
            "bucketMinutes must divide 1440"
        }

        val bucketCount = 1440 / config.bucketMinutes
        val objectiveBuckets = MutableList(bucketCount) { mutableListOf<ObjectivePoint>() }

        minuteRows.forEach { row ->
            if (config.excludeCharging && row.anyCharging == 1) return@forEach
            if (config.excludeOffWrist && row.anyOffWrist == 1) return@forEach
            if (config.minConfidence != null && (row.avgConfidence ?: 0.0) < config.minConfidence) return@forEach

            val localDateTime = Instant.ofEpochMilli(row.minuteBucketTimestamp).atZone(config.zoneId)
            val minuteOfDay = localDateTime.hour * 60 + localDateTime.minute
            val bucketIndex = minuteOfDay / config.bucketMinutes

            objectiveBuckets[bucketIndex].add(
                ObjectivePoint(
                    severity = row.avgSeverity,
                    sampleCount = row.sampleCount.coerceAtLeast(1),
                    epochDay = localDateTime.toLocalDate().toEpochDay()
                )
            )
        }

        val subjectiveBuckets = MutableList(bucketCount) { mutableListOf<Double>() }
        val perceptionGaps = mutableListOf<Double>()

        if (config.includeSubjective) {
            ratingRows.forEach { row ->
                val localDateTime = Instant.ofEpochMilli(row.timestamp).atZone(config.zoneId)
                val minuteOfDay = localDateTime.hour * 60 + localDateTime.minute
                val bucketIndex = minuteOfDay / config.bucketMinutes

                val normalizedRating = row.rating.coerceIn(0, 5) * 2.0
                subjectiveBuckets[bucketIndex].add(normalizedRating)

                // Positive means user feels worse than objective model indicates.
                row.detectedSeverity?.let { detected ->
                    perceptionGaps.add(normalizedRating - detected)
                }
            }
        }

        var totalIncludedSamples = 0
        var totalOutlierSamples = 0

        val buckets = (0 until bucketCount).map { index ->
            val startMinute = index * config.bucketMinutes
            val endMinute = startMinute + config.bucketMinutes - 1

            val rawPoints = objectiveBuckets[index]
            val sortedPoints = rawPoints.sortedBy { it.severity }
            val trimmedPoints = trimOutlierPointsIqr(sortedPoints, config.iqrMultiplier)

            val rawSampleCount = sortedPoints.sumOf { it.sampleCount }
            val trimmedSampleCount = trimmedPoints.sumOf { it.sampleCount }
            val outlierSampleCount = (rawSampleCount - trimmedSampleCount).coerceAtLeast(0)

            totalIncludedSamples += rawSampleCount
            totalOutlierSamples += outlierSampleCount

            val distinctDays = rawPoints.map { it.epochDay }.toSet().size
            val lowDayCoverage = rawPoints.isNotEmpty() && distinctDays < config.minDistinctDaysPerBucket
            val statsPoints = if (lowDayCoverage) emptyList() else trimmedPoints
            val statsValues = statsPoints.map { it.severity }

            val subjectiveMean = subjectiveBuckets[index].averageOrNull()
            val objectiveMedian = percentileFromSorted(statsValues, 0.5)
            val mismatch = objectiveMedian != null &&
                subjectiveMean != null &&
                abs(objectiveMedian - subjectiveMean) > config.mismatchThreshold

            DailyTremorProfileBucket(
                bucketIndex = index,
                startMinuteOfDay = startMinute,
                endMinuteOfDayInclusive = endMinute,
                objectiveMedian = objectiveMedian,
                objectiveQ1 = percentileFromSorted(statsValues, 0.25),
                objectiveQ3 = percentileFromSorted(statsValues, 0.75),
                objectiveMean = statsValues.averageOrNull(),
                objectiveRawPointCount = rawPoints.size,
                objectiveTrimmedPointCount = trimmedPoints.size,
                objectiveOutlierPointCount = (sortedPoints.size - trimmedPoints.size).coerceAtLeast(0),
                objectiveRawSampleCount = rawSampleCount,
                objectiveTrimmedSampleCount = trimmedSampleCount,
                objectiveOutlierSampleCount = outlierSampleCount,
                distinctDaysWithData = distinctDays,
                lowDayCoverageFlag = lowDayCoverage,
                subjectiveMean = subjectiveMean,
                subjectiveCount = subjectiveBuckets[index].size,
                mismatchFlag = mismatch
            )
        }

        val objectiveMedianSmoothed = smoothCircular(
            values = buckets.map { it.objectiveMedian },
            radius = config.smoothingRadius
        )
        val objectiveQ1Smoothed = smoothCircular(
            values = buckets.map { it.objectiveQ1 },
            radius = config.smoothingRadius
        )
        val objectiveQ3Smoothed = smoothCircular(
            values = buckets.map { it.objectiveQ3 },
            radius = config.smoothingRadius
        )
        val paired = buckets.mapNotNull { bucket ->
            val objective = bucket.objectiveMedian
            val subjective = bucket.subjectiveMean
            if (objective != null && subjective != null) objective to subjective else null
        }

        val calibrationInfo = computeSubjectiveCalibration(
            config = config,
            pairedObjective = paired.map { it.first },
            pairedSubjective = paired.map { it.second }
        )

        val subjectiveDisplayByBucket = if (!config.includeSubjective) {
            List(bucketCount) { null }
        } else if (
            calibrationInfo.appliedMode == SubjectiveOverlayMode.CALIBRATED_SCALED &&
            calibrationInfo.scale != null
        ) {
            buckets.map { bucket ->
                bucket.subjectiveMean?.times(calibrationInfo.scale)
            }
        } else {
            buckets.map { it.subjectiveMean }
        }

        val subjectiveRawSmoothed = if (config.includeSubjective) {
            smoothCircular(
                values = buckets.map { it.subjectiveMean },
                radius = config.smoothingRadius
            )
        } else {
            List(bucketCount) { null }
        }

        val subjectiveSmoothed = if (config.includeSubjective) {
            smoothCircular(
                values = subjectiveDisplayByBucket,
                radius = config.smoothingRadius
            )
        } else {
            List(bucketCount) { null }
        }

        val correlation = pearsonCorrelationOrNull(
            x = paired.map { it.first },
            y = paired.map { it.second }
        )

        val objectiveCoveragePercent = (buckets.count { it.objectiveMedian != null } * 100) / bucketCount
        val subjectiveCoveragePercent = (buckets.count { it.subjectiveMean != null } * 100) / bucketCount
        val outlierRate = if (totalIncludedSamples > 0) {
            totalOutlierSamples.toDouble() / totalIncludedSamples.toDouble()
        } else {
            0.0
        }

        val insufficientDataForConfidence =
            objectiveCoveragePercent < config.minCoveragePercentForConfidence ||
                totalIncludedSamples < config.minSamplesForConfidence

        val confidenceScore = if (insufficientDataForConfidence) {
            0.0
        } else {
            computeConfidenceScore(
                correlation = correlation,
                objectiveCoveragePercent = objectiveCoveragePercent,
                subjectiveCoveragePercent = subjectiveCoveragePercent,
                objectiveSampleCount = totalIncludedSamples,
                outlierRate = outlierRate,
                includeSubjective = config.includeSubjective
            )
        }

        val confidenceLabel = when {
            insufficientDataForConfidence -> "Insufficient Data"
            confidenceScore >= 0.80 -> "Very High"
            confidenceScore >= 0.60 -> "High"
            confidenceScore >= 0.40 -> "Medium"
            else -> "Low"
        }

        val correlationLabel = when {
            correlation == null -> "Insufficient"
            correlation >= 0.70 -> "Excellent"
            correlation >= 0.50 -> "Good"
            correlation >= 0.30 -> "Fair"
            correlation >= 0.00 -> "Weak"
            else -> "Inverse"
        }

        val bestBucketIndex = objectiveMedianSmoothed.indexOfMinNotNull()
        val worstBucketIndex = objectiveMedianSmoothed.indexOfMaxNotNull()

        val warnings = buildList {
            if (objectiveCoveragePercent < config.minCoveragePercentForConfidence) {
                add("Low objective coverage (${objectiveCoveragePercent}%) for selected window.")
            }
            if (config.includeSubjective && subjectiveCoveragePercent < 15) {
                add("Low subjective coverage; alignment may be unstable.")
            }
            if (config.includeSubjective && perceptionGaps.size < (paired.size / 2).coerceAtLeast(3)) {
                add("Perception-gap metrics are based on limited paired samples.")
            }
            if (outlierRate > 0.25) {
                add("High outlier rate; signal quality may be inconsistent.")
            }
            if (buckets.any { it.lowDayCoverageFlag }) {
                add("Some hours are suppressed due to limited distinct-day coverage.")
            }
            if (minuteRows.isNotEmpty()) {
                add("Assumes user stayed in one time zone during selected window.")
            }
            if (
                config.includeSubjective &&
                calibrationInfo.requestedMode == SubjectiveOverlayMode.CALIBRATED_SCALED &&
                !calibrationInfo.applied
            ) {
                calibrationInfo.fallbackReason?.let {
                    add("Subjective calibration unavailable: $it")
                }
            }
            if (calibrationInfo.wasClamped) {
                add("Subjective calibration scale was clamped for stability.")
            }
        }

        val bestBucketLabel = bestBucketIndex?.let { formatBucketLabel(it, config.bucketMinutes) }
        val worstBucketLabel = worstBucketIndex?.let { formatBucketLabel(it, config.bucketMinutes) }
        val accessibilitySummary = buildAccessibilitySummary(
            bestBucketLabel = bestBucketLabel,
            worstBucketLabel = worstBucketLabel,
            correlationLabel = correlationLabel
        )

        val objectiveValuesFlat = objectiveMedianSmoothed.filterNotNull()
        val subjectiveValuesFlat = subjectiveSmoothed.filterNotNull()

        val metrics = DailyTremorProfileMetrics(
            objectiveOverall = objectiveValuesFlat.averageOrNull(),
            subjectiveOverall = subjectiveValuesFlat.averageOrNull(),
            bestBucketIndex = bestBucketIndex,
            worstBucketIndex = worstBucketIndex,
            bestBucketLabel = bestBucketLabel,
            worstBucketLabel = worstBucketLabel,
            objectiveCoveragePercent = objectiveCoveragePercent,
            subjectiveCoveragePercent = subjectiveCoveragePercent,
            correlation = correlation,
            pairedBucketCount = paired.size,
            correlationLabel = correlationLabel,
            confidenceScore = confidenceScore,
            confidenceLabel = confidenceLabel,
            insufficientDataForConfidence = insufficientDataForConfidence,
            outlierRate = outlierRate,
            avgPerceptionGap = perceptionGaps.averageOrNull(),
            perceptionGapSampleCount = perceptionGaps.size,
            warnings = warnings,
            accessibilitySummary = accessibilitySummary
        )

        return DailyTremorProfile(
            config = config,
            generatedAtMs = nowMs,
            buckets = buckets,
            objectiveMedianSmoothed = objectiveMedianSmoothed,
            objectiveQ1Smoothed = objectiveQ1Smoothed,
            objectiveQ3Smoothed = objectiveQ3Smoothed,
            subjectiveRawSmoothed = subjectiveRawSmoothed,
            subjectiveSmoothed = subjectiveSmoothed,
            subjectiveCalibration = calibrationInfo,
            metrics = metrics
        )
    }

    /**
     * Public utility used by tests. Accepts unsorted input and sorts internally.
     */
    fun trimOutliersIqr(values: List<Double>, multiplier: Double): List<Double> {
        if (values.size < 4) return values
        val sortedValues = values.sorted()
        val q1 = percentileFromSorted(sortedValues, 0.25) ?: return values
        val q3 = percentileFromSorted(sortedValues, 0.75) ?: return values
        val iqr = q3 - q1
        val lower = q1 - multiplier * iqr
        val upper = q3 + multiplier * iqr
        return sortedValues.filter { it in lower..upper }
    }

    private fun trimOutlierPointsIqr(
        pointsSortedBySeverity: List<ObjectivePoint>,
        multiplier: Double
    ): List<ObjectivePoint> {
        if (pointsSortedBySeverity.size < 4) return pointsSortedBySeverity
        val sortedValues = pointsSortedBySeverity.map { it.severity }
        val q1 = percentileFromSorted(sortedValues, 0.25) ?: return pointsSortedBySeverity
        val q3 = percentileFromSorted(sortedValues, 0.75) ?: return pointsSortedBySeverity
        val iqr = q3 - q1
        val lower = q1 - multiplier * iqr
        val upper = q3 + multiplier * iqr
        return pointsSortedBySeverity.filter { it.severity in lower..upper }
    }

    fun pearsonCorrelationOrNull(x: List<Double>, y: List<Double>): Double? {
        require(x.size == y.size)
        if (x.size < 3) return null

        val meanX = x.average()
        val meanY = y.average()

        var numerator = 0.0
        var sumSqX = 0.0
        var sumSqY = 0.0

        for (i in x.indices) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            numerator += dx * dy
            sumSqX += dx * dx
            sumSqY += dy * dy
        }

        val denominator = sqrt(sumSqX * sumSqY)
        if (denominator == 0.0) return null
        return numerator / denominator
    }

    fun smoothCircular(values: List<Double?>, radius: Int): List<Double?> {
        if (values.isEmpty()) return values
        if (radius <= 0) return values

        val n = values.size
        return values.indices.map { index ->
            var weightedSum = 0.0
            var weightTotal = 0.0

            for (offset in -radius..radius) {
                val wrappedIndex = ((index + offset) % n + n) % n
                val value = values[wrappedIndex] ?: continue
                val weight = (radius + 1 - abs(offset)).toDouble()
                weightedSum += value * weight
                weightTotal += weight
            }

            if (weightTotal > 0.0) weightedSum / weightTotal else null
        }
    }

    private fun percentileFromSorted(sortedValues: List<Double>, p: Double): Double? {
        if (sortedValues.isEmpty()) return null
        val index = p.coerceIn(0.0, 1.0) * sortedValues.lastIndex
        val lo = index.toInt()
        val hi = (lo + 1).coerceAtMost(sortedValues.lastIndex)
        val weight = index - lo
        return sortedValues[lo] * (1.0 - weight) + sortedValues[hi] * weight
    }

    private fun computeSubjectiveCalibration(
        config: DailyTremorProfileConfig,
        pairedObjective: List<Double>,
        pairedSubjective: List<Double>
    ): SubjectiveCalibrationInfo {
        val requestedMode = if (config.includeSubjective) {
            config.subjectiveOverlayMode
        } else {
            SubjectiveOverlayMode.RAW_X2
        }

        if (!config.includeSubjective) {
            return SubjectiveCalibrationInfo(
                requestedMode = requestedMode,
                appliedMode = SubjectiveOverlayMode.RAW_X2,
                applied = false,
                scale = null,
                pairedBucketCount = 0,
                trimmedBucketCount = 0,
                wasClamped = false,
                fallbackReason = "Subjective overlay disabled."
            )
        }

        if (requestedMode == SubjectiveOverlayMode.RAW_X2) {
            return SubjectiveCalibrationInfo(
                requestedMode = requestedMode,
                appliedMode = SubjectiveOverlayMode.RAW_X2,
                applied = false,
                scale = null,
                pairedBucketCount = pairedObjective.size.coerceAtMost(pairedSubjective.size),
                trimmedBucketCount = pairedObjective.size.coerceAtMost(pairedSubjective.size),
                wasClamped = false,
                fallbackReason = null
            )
        }

        val pairCount = pairedObjective.size.coerceAtMost(pairedSubjective.size)
        if (pairCount < config.calibrationMinPairedBuckets) {
            return SubjectiveCalibrationInfo(
                requestedMode = requestedMode,
                appliedMode = SubjectiveOverlayMode.RAW_X2,
                applied = false,
                scale = null,
                pairedBucketCount = pairCount,
                trimmedBucketCount = 0,
                wasClamped = false,
                fallbackReason = "Need at least ${config.calibrationMinPairedBuckets} paired buckets."
            )
        }

        val finitePairs = pairedObjective.zip(pairedSubjective)
            .filter { (objective, subjective) ->
                objective.isFinite() && subjective.isFinite() && subjective > 0.0
            }

        if (finitePairs.size < config.calibrationMinPairedBuckets) {
            return SubjectiveCalibrationInfo(
                requestedMode = requestedMode,
                appliedMode = SubjectiveOverlayMode.RAW_X2,
                applied = false,
                scale = null,
                pairedBucketCount = finitePairs.size,
                trimmedBucketCount = 0,
                wasClamped = false,
                fallbackReason = "Insufficient valid paired buckets after filtering."
            )
        }

        val trimmedPairs = trimPairedByQuantiles(
            pairs = finitePairs,
            trimFraction = config.calibrationTrimFraction
        )

        if (trimmedPairs.size < config.calibrationMinPairedBuckets) {
            return SubjectiveCalibrationInfo(
                requestedMode = requestedMode,
                appliedMode = SubjectiveOverlayMode.RAW_X2,
                applied = false,
                scale = null,
                pairedBucketCount = finitePairs.size,
                trimmedBucketCount = trimmedPairs.size,
                wasClamped = false,
                fallbackReason = "Calibration became unstable after outlier trimming."
            )
        }

        val meanObjective = trimmedPairs.map { it.first }.average()
        val meanSubjective = trimmedPairs.map { it.second }.average()

        if (!meanObjective.isFinite() || !meanSubjective.isFinite() || meanSubjective <= 0.0) {
            return SubjectiveCalibrationInfo(
                requestedMode = requestedMode,
                appliedMode = SubjectiveOverlayMode.RAW_X2,
                applied = false,
                scale = null,
                pairedBucketCount = finitePairs.size,
                trimmedBucketCount = trimmedPairs.size,
                wasClamped = false,
                fallbackReason = "Cannot compute a stable calibration scale."
            )
        }

        if (meanSubjective < config.calibrationMinSubjectiveMean) {
            return SubjectiveCalibrationInfo(
                requestedMode = requestedMode,
                appliedMode = SubjectiveOverlayMode.RAW_X2,
                applied = false,
                scale = null,
                pairedBucketCount = finitePairs.size,
                trimmedBucketCount = trimmedPairs.size,
                wasClamped = false,
                fallbackReason = "Average subjective score too low for reliable calibration."
            )
        }

        val rawScale = meanObjective / meanSubjective
        if (!rawScale.isFinite() || rawScale <= 0.0) {
            return SubjectiveCalibrationInfo(
                requestedMode = requestedMode,
                appliedMode = SubjectiveOverlayMode.RAW_X2,
                applied = false,
                scale = null,
                pairedBucketCount = finitePairs.size,
                trimmedBucketCount = trimmedPairs.size,
                wasClamped = false,
                fallbackReason = "Calibration scale is not valid."
            )
        }

        val clampedScale = rawScale.coerceIn(config.calibrationScaleMin, config.calibrationScaleMax)

        return SubjectiveCalibrationInfo(
            requestedMode = requestedMode,
            appliedMode = SubjectiveOverlayMode.CALIBRATED_SCALED,
            applied = true,
            scale = clampedScale,
            pairedBucketCount = finitePairs.size,
            trimmedBucketCount = trimmedPairs.size,
            wasClamped = clampedScale != rawScale,
            fallbackReason = null
        )
    }

    private fun trimPairedByQuantiles(
        pairs: List<Pair<Double, Double>>,
        trimFraction: Double
    ): List<Pair<Double, Double>> {
        if (pairs.size < 4 || trimFraction <= 0.0) return pairs

        val clampedTrim = trimFraction.coerceIn(0.0, 0.25)
        val lowerP = clampedTrim
        val upperP = 1.0 - clampedTrim

        val objectiveSorted = pairs.map { it.first }.sorted()
        val subjectiveSorted = pairs.map { it.second }.sorted()

        val objectiveLower = percentileFromSorted(objectiveSorted, lowerP) ?: return pairs
        val objectiveUpper = percentileFromSorted(objectiveSorted, upperP) ?: return pairs
        val subjectiveLower = percentileFromSorted(subjectiveSorted, lowerP) ?: return pairs
        val subjectiveUpper = percentileFromSorted(subjectiveSorted, upperP) ?: return pairs

        return pairs.filter { (objective, subjective) ->
            objective in objectiveLower..objectiveUpper &&
                subjective in subjectiveLower..subjectiveUpper
        }
    }

    private fun computeConfidenceScore(
        correlation: Double?,
        objectiveCoveragePercent: Int,
        subjectiveCoveragePercent: Int,
        objectiveSampleCount: Int,
        outlierRate: Double,
        includeSubjective: Boolean
    ): Double {
        var score = 0.0

        val corrComponent = if (correlation == null) {
            0.0
        } else {
            correlation.coerceIn(0.0, 1.0) * 0.45
        }
        score += corrComponent

        score += (objectiveCoveragePercent.coerceIn(0, 100) / 100.0) * 0.25
        score += if (includeSubjective) {
            (subjectiveCoveragePercent.coerceIn(0, 100) / 100.0) * 0.15
        } else {
            0.15
        }

        score += when {
            objectiveSampleCount >= 1500 -> 0.10
            objectiveSampleCount >= 700 -> 0.06
            objectiveSampleCount >= 250 -> 0.03
            else -> 0.0
        }

        score += (1.0 - outlierRate.coerceIn(0.0, 1.0)) * 0.05
        return score.coerceIn(0.0, 1.0)
    }

    private fun buildAccessibilitySummary(
        bestBucketLabel: String?,
        worstBucketLabel: String?,
        correlationLabel: String
    ): String {
        val best = bestBucketLabel ?: "unavailable"
        val worst = worstBucketLabel ?: "unavailable"
        return "Daily tremor profile. Lowest objective tremor around $best, highest around $worst. " +
            "Subjective alignment is $correlationLabel."
    }

    private fun formatBucketLabel(bucketIndex: Int, bucketMinutes: Int): String {
        val startMinute = bucketIndex * bucketMinutes
        val endMinute = (startMinute + bucketMinutes).coerceAtMost(1440)

        fun formatMinute(minuteOfDay: Int): String {
            val safeMinute = minuteOfDay % 1440
            val hour24 = safeMinute / 60
            val minute = safeMinute % 60
            return String.format("%02d:%02d", hour24, minute)
        }

        return "${formatMinute(startMinute)}-${formatMinute(endMinute)}"
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun List<Double?>.indexOfMinNotNull(): Int? {
        var bestIndex: Int? = null
        var bestValue: Double? = null

        forEachIndexed { index, value ->
            if (value != null && (bestValue == null || value < bestValue!!)) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun List<Double?>.indexOfMaxNotNull(): Int? {
        var bestIndex: Int? = null
        var bestValue: Double? = null

        forEachIndexed { index, value ->
            if (value != null && (bestValue == null || value > bestValue!!)) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex
    }
}
