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
    val activityType: String? = null,
    val activityConfidence: Double? = null,
    val activityAgeMs: Long? = null,
    val activityAdjustedSeverity: Double? = null,
    val activityAdjustedConfidence: Double? = null,
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
    // Primary: tremor bouts per hour of worn time (higher = worse).
    // Null when not enough worn time to show.
    val boutsPerHour: Double?,
    val tremorMinutesPerHour: Double?,
    val totalBouts: Int,
    val tremorMinutes: Double,
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
