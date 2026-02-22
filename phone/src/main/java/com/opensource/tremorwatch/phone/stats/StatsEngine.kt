package com.opensource.tremorwatch.phone.stats

import kotlin.math.pow
import kotlin.math.roundToInt

object StatsEngine {
    const val MAX_SAMPLE_GAP_SEC_DEFAULT = 120.0

    private const val USER_CONF_MIN = 0.5
    private const val USER_ADJ_CONF_MIN = 0.4
    private const val USER_RELIABILITY_MIN = 0.35

    private const val CLINICAL_CONF_MIN = 0.7
    private const val CLINICAL_ADJ_CONF_MIN = 0.6
    private const val CLINICAL_RELIABILITY_MIN = 0.55

    private const val VEHICLE_ACTIVITY_CONF_MIN = 0.7
    private const val WALKING_ACTIVITY_CONF_MIN = 0.7
    private const val WALKING_CONF_MIN = 0.85
    private const val WALKING_TREMOR_TYPE_CONF_MIN = 0.8

    private const val ACTIVITY_STALE_MS_MAX = 30_000L

    private const val MIN_ELIGIBLE_MIN_TO_SHOW = 30.0
    private const val LIMITED_DATA_MINUTES = 120.0

    private const val BASELINE_MIN_SAMPLES = 10

    data class StableTimeResult(
        val stableMinutes: Double,
        val stableThreshold: Double,
        val baselineRestSeverity: Double?
    )

    fun isValidForUserStats(s: StatsSample): Boolean {
        val conf = s.calibratedConfidence ?: s.confidence ?: 0.0
        val adjConf = s.activityAdjustedConfidence ?: 0.0
        val tremorTypeConf = s.tremorTypeConfidence ?: 0.0
        val reliability = s.reliabilityScore

        val activityType = s.activityTypeCanonical()
        val activityConf = s.activityConfidence ?: 0.0
        val activityAgeMs = s.activityAgeMs ?: Long.MAX_VALUE
        val stepsPerMinute = s.stepsPerMinute ?: 0
        val activityAgeOk = activityAgeMs <= ACTIVITY_STALE_MS_MAX

        // In the current pipeline, `confidence` is frequently low/zero during stable/no-tremor periods
        // (it is closer to "tremor detection confidence" than "measurement quality"). If the monitoring
        // layer explicitly marked the sample as reliable, treat it as eligible for user stats even when
        // the confidence fields are low so we can still compute stability during proven-still intervals.
        val isExplicitlyReliable = s.isReliableMeasurement == true
        val confidenceOk = isExplicitlyReliable || (conf >= USER_CONF_MIN && adjConf >= USER_ADJ_CONF_MIN)
        val reliabilityOk = reliability == null || reliability >= USER_RELIABILITY_MIN

        val vehicleOrBikePoison = (activityType == "IN_VEHICLE" || activityType == "ON_BICYCLE") &&
            activityConf >= VEHICLE_ACTIVITY_CONF_MIN

        val walkingStrict = activityType == "WALKING" && activityConf >= WALKING_ACTIVITY_CONF_MIN
        val walkingOk = !walkingStrict || (conf >= WALKING_CONF_MIN && tremorTypeConf >= WALKING_TREMOR_TYPE_CONF_MIN)

        return s.isWorn == true &&
            s.isCharging != true &&
            // Legacy-safe defaults:
            // - exclude only when explicitly excluded
            // - treat reliability=null as "unknown" for user stats (do not drop historical data)
            s.excludeFromAnalysis != true &&
            s.isReliableMeasurement != false &&
            confidenceOk &&
            reliabilityOk &&
            stepsPerMinute <= 130 &&
            !vehicleOrBikePoison &&
            activityType != "RUNNING" &&
            activityAgeOk &&
            walkingOk
    }

    fun isValidForClinicalStats(s: StatsSample): Boolean {
        val conf = s.calibratedConfidence ?: s.confidence ?: 0.0
        val adjConf = s.activityAdjustedConfidence ?: 0.0
        val tremorTypeConf = s.tremorTypeConfidence ?: 0.0
        val reliability = s.reliabilityScore

        val activityType = s.activityTypeCanonical()
        val activityConf = s.activityConfidence ?: 0.0
        val activityAgeMs = s.activityAgeMs ?: Long.MAX_VALUE
        val stepsPerMinute = s.stepsPerMinute ?: 0
        val activityAgeOk = activityAgeMs <= ACTIVITY_STALE_MS_MAX

        val vehicleOrBikePoison = (activityType == "IN_VEHICLE" || activityType == "ON_BICYCLE") &&
            activityConf >= VEHICLE_ACTIVITY_CONF_MIN

        val walkingStrict = activityType == "WALKING" && activityConf >= WALKING_ACTIVITY_CONF_MIN
        val walkingOk = !walkingStrict || (conf >= WALKING_CONF_MIN && tremorTypeConf >= WALKING_TREMOR_TYPE_CONF_MIN)

        return s.isWorn == true &&
            s.isCharging != true &&
            s.excludeFromAnalysis != true &&
            // Clinical stats require explicit reliability.
            s.isReliableMeasurement == true &&
            conf >= CLINICAL_CONF_MIN &&
            adjConf >= CLINICAL_ADJ_CONF_MIN &&
            (reliability == null || reliability >= CLINICAL_RELIABILITY_MIN) &&
            stepsPerMinute <= 110 &&
            !vehicleOrBikePoison &&
            activityType != "RUNNING" &&
            activityAgeOk &&
            walkingOk
    }

    fun calculateDailyStabilityScore(
        samples: List<StatsSample>,
        maxGapSec: Double = MAX_SAMPLE_GAP_SEC_DEFAULT,
        gamma: Double = 1.0
    ): StabilityResult? {
        val s = samples.sortedBy { it.timestamp }
        if (s.size < 2) return null

        var wornTimeSec = 0.0
        var eligibleTimeSec = 0.0
        var severityTimeSum = 0.0
        var gaps = 0

        for (i in 0 until s.size - 1) {
            val cur = s[i]
            val next = s[i + 1]

            val dtSec = (next.timestamp - cur.timestamp) / 1000.0
            if (dtSec <= 0.0) continue
            if (dtSec > maxGapSec) {
                gaps++
                continue
            }

            if (cur.isWorn == true && cur.isCharging != true &&
                next.isWorn == true && next.isCharging != true) {
                wornTimeSec += dtSec
            }

            val curEligible = isValidForUserStats(cur)
            if (!curEligible) continue

            val nextEligible = isValidForUserStats(next)

            val sev0 = (cur.activityAdjustedSeverity ?: cur.severityRaw).coerceIn(0.0, 10.0)
            val avgSeverity = if (nextEligible) {
                val sev1 = (next.activityAdjustedSeverity ?: next.severityRaw).coerceIn(0.0, 10.0)
                (sev0 + sev1) / 2.0
            } else {
                sev0
            }

            severityTimeSum += avgSeverity * dtSec
            eligibleTimeSec += dtSec
        }

        val wornMinutes = wornTimeSec / 60.0
        val eligibleMinutes = eligibleTimeSec / 60.0

        if (eligibleMinutes < MIN_ELIGIBLE_MIN_TO_SHOW) {
            return StabilityResult(
                score = null,
                message = "Not enough data yet",
                wornMinutes = wornMinutes,
                eligibleMinutes = eligibleMinutes,
                dataGaps = gaps
            )
        }

        val burden = (severityTimeSum / (eligibleTimeSec * 10.0))
            .coerceIn(0.0, 1.0)

        val rawScore = 100.0 * (1.0 - burden.pow(gamma.coerceIn(1.0, 1.5)))
        val finalScore = rawScore.coerceIn(0.0, 100.0).roundToInt()

        val message = when {
            eligibleMinutes < LIMITED_DATA_MINUTES -> "Limited data - wear longer for accuracy"
            gaps > 0 -> "Data gaps detected - score may be less accurate"
            else -> null
        }

        return StabilityResult(
            score = finalScore,
            message = message,
            wornMinutes = wornMinutes,
            eligibleMinutes = eligibleMinutes,
            dataGaps = gaps
        )
    }

    fun estimateRestingBaselineSeverity(samples: List<StatsSample>): Double? {
        val baselineValues = samples
            .asSequence()
            .filter { isValidForClinicalStats(it) }
            .filter { it.activityTypeCanonical() == "STILL" && it.isRestingState == true }
            .map { (it.activityAdjustedSeverity ?: it.severityRaw).coerceIn(0.0, 10.0) }
            .toList()

        if (baselineValues.size < BASELINE_MIN_SAMPLES) return null
        return baselineValues.medianOrNull()
    }

    fun calculateStableTime(
        samples: List<StatsSample>,
        maxGapSec: Double = MAX_SAMPLE_GAP_SEC_DEFAULT,
        stableThreshold: Double
    ): Double {
        val s = samples.sortedBy { it.timestamp }
        if (s.size < 2) return 0.0

        var stableTimeSec = 0.0

        for (i in 0 until s.size - 1) {
            val cur = s[i]
            val next = s[i + 1]

            val dtSec = (next.timestamp - cur.timestamp) / 1000.0
            if (dtSec <= 0.0) continue
            if (dtSec > maxGapSec) continue

            val curEligible = isValidForUserStats(cur)
            if (!curEligible) continue

            val nextEligible = isValidForUserStats(next)

            val sev0 = (cur.activityAdjustedSeverity ?: cur.severityRaw).coerceIn(0.0, 10.0)
            val avgSeverity = if (nextEligible) {
                val sev1 = (next.activityAdjustedSeverity ?: next.severityRaw).coerceIn(0.0, 10.0)
                (sev0 + sev1) / 2.0
            } else {
                sev0
            }

            if (avgSeverity < stableThreshold) {
                stableTimeSec += dtSec
            }
        }

        return stableTimeSec / 60.0
    }

    fun computeStableTimeForDay(samples: List<StatsSample>): StableTimeResult {
        val baseline = estimateRestingBaselineSeverity(samples)
        val stableThreshold = when {
            baseline != null && baseline.isFinite() && baseline > 0.0 ->
                maxOf(2.0, baseline * 1.2)
            else -> 2.0
        }

        val stableMinutes = calculateStableTime(samples, stableThreshold = stableThreshold)

        return StableTimeResult(
            stableMinutes = stableMinutes,
            stableThreshold = stableThreshold,
            baselineRestSeverity = baseline
        )
    }
}
