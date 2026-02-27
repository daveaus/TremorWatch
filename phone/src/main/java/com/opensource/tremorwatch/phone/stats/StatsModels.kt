package com.opensource.tremorwatch.phone.stats

import java.time.LocalDate
import kotlin.math.floor

data class StatsSample(
    val timestamp: Long,
    val severityRaw: Double,
    val tremorCount: Int,
    val dominantFrequency: Double? = null,
    val bandRatio: Double? = null,
    val peakProminence: Double? = null,
    val isWorn: Boolean? = null,
    val isCharging: Boolean? = null,
    val confidence: Double? = null,
    val calibratedConfidence: Double? = null,
    val rerankerProbability: Double? = null,
    val activityType: String? = null,
    val activityConfidence: Double? = null,
    val activityAgeMs: Long? = null,
    val stepsPerMinute: Int? = null,
    val activityAdjustedSeverity: Double? = null,
    val activityAdjustedConfidence: Double? = null,
    val reliabilityScore: Double? = null,
    val tremorTypeConfidence: Double? = null,
    val isRestingState: Boolean? = null,
    val isReliableMeasurement: Boolean? = null,
    val excludeFromAnalysis: Boolean? = null
)

fun StatsSample.activityTypeCanonical(): String =
    (activityType ?: "UNKNOWN").trim().uppercase()

data class StabilityResult(
    val score: Int?,            // null when not enough eligible data to show
    val message: String?,       // user-facing quality messaging
    val wornMinutes: Double,    // Gate 1 wear time (isWorn == true && not charging)
    val eligibleMinutes: Double,// time used in denominator (passes user gatekeepers)
    val dataGaps: Int           // count of skipped long gaps (sampling discontinuities)
)

data class TremorLoadResult(
    // Primary (quality-gated: Confirmed + Probable tiers).
    // Null when not enough worn time to show.
    val boutsPerHour: Double?,
    val tremorMinutesPerHour: Double?,
    val totalBouts: Int,
    val tremorMinutes: Double,
    // Candidate (unfiltered, diagnostic/debug — old behavior).
    val boutsPerHourCandidate: Double?,
    val tremorMinutesPerHourCandidate: Double?,
    val totalBoutsCandidate: Int,
    val tremorMinutesCandidate: Double,
    val wornMinutes: Double,
    val message: String? = null
)

data class DailyStatsResult(
    val date: LocalDate,
    val tremorLoad: TremorLoadResult?,
    val stability: StabilityResult?,
    val stableMinutes: Double,
    val stableThreshold: Double,
    val baselineRestSeverity: Double?
)

// ==================== Frequency Profile ====================

data class FrequencyProfileEntry(
    val frequencyHz: Double,
    val count: Int,
    val percentage: Double,
    val classification: String
)

data class FrequencyProfileResult(
    val entries: List<FrequencyProfileEntry>,
    val totalTremorSamples: Int,
    val message: String?
)

fun classifyFrequency(hz: Double): String = when {
    hz < 3.0  -> "Sub-tremor / artifact"
    hz < 4.0  -> "Low-frequency tremor / artifact"
    hz < 6.0  -> "Classic resting tremor"
    hz < 8.0  -> "Essential / kinetic tremor"
    hz < 12.0 -> "High-frequency / physiological"
    else      -> "Very high / likely artifact"
}

// ==================== Severity Timeline ====================

enum class TimelineGranularity(val label: String, val bucketsToMerge: Int) {
    FIFTEEN_MIN("15 min", 1),
    THIRTY_MIN("30 min", 2),
    ONE_HOUR("1 hr", 4)
}

data class SeverityBucket(
    val bucketTimestamp: Long,
    val avgSeverity: Double,
    val tremorCount: Int,
    val totalCount: Int,
    val minSeverity: Double,
    val maxSeverity: Double,
    val timeLabel: String
)

data class SeverityTimelineResult(
    val buckets: List<SeverityBucket>,
    val peakBucket: SeverityBucket?,
    val troughBucket: SeverityBucket?,
    val message: String?
)

/**
 * Merge 15-min SeverityBuckets into wider buckets by grouping adjacent ones.
 * For THIRTY_MIN: merge pairs. For ONE_HOUR: merge groups of 4.
 * For FIFTEEN_MIN: returns unchanged.
 */
fun List<SeverityBucket>.mergeToGranularity(granularity: TimelineGranularity): List<SeverityBucket> {
    if (granularity == TimelineGranularity.FIFTEEN_MIN || isEmpty()) return this
    val groupSize = granularity.bucketsToMerge
    val bucketDurationMs = groupSize * 900_000L
    return groupBy { it.bucketTimestamp / bucketDurationMs }.values.map { group ->
        val totalSamples = group.sumOf { it.totalCount }
        val totalTremor = group.sumOf { it.tremorCount }
        val weightedSeverity = if (totalSamples > 0) {
            group.sumOf { it.avgSeverity * it.totalCount } / totalSamples
        } else 0.0
        SeverityBucket(
            bucketTimestamp = group.first().bucketTimestamp,
            avgSeverity = weightedSeverity,
            tremorCount = totalTremor,
            totalCount = totalSamples,
            minSeverity = group.minOf { it.minSeverity },
            maxSeverity = group.maxOf { it.maxSeverity },
            timeLabel = group.first().timeLabel
        )
    }
}

// ==================== Utilities ====================

fun formatMinutesAsHoursMinutes(minutes: Double): String {
    if (!minutes.isFinite() || minutes <= 0.0) return "0h 0m"
    val totalMinutes = floor(minutes).toInt().coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return "${h}h ${m}m"
}

fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid]
    } else {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
