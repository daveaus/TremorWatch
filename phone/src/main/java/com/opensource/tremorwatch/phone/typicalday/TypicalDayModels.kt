package com.opensource.tremorwatch.phone.typicalday

import java.time.ZoneId

enum class SubjectiveOverlayMode {
    RAW_X2,
    CALIBRATED_SCALED;

    companion object {
        fun fromStorage(value: String?): SubjectiveOverlayMode {
            return values().firstOrNull { it.name == value } ?: CALIBRATED_SCALED
        }
    }
}

data class SubjectiveCalibrationInfo(
    val requestedMode: SubjectiveOverlayMode,
    val appliedMode: SubjectiveOverlayMode,
    val applied: Boolean,
    val scale: Double?,
    val pairedBucketCount: Int,
    val trimmedBucketCount: Int,
    val wasClamped: Boolean,
    val fallbackReason: String?
)

data class DailyTremorProfileConfig(
    val days: Int = 14,
    val bucketMinutes: Int = 60,
    val includeSubjective: Boolean = true,
    val subjectiveOverlayMode: SubjectiveOverlayMode = SubjectiveOverlayMode.CALIBRATED_SCALED,
    val excludeCharging: Boolean = true,
    val excludeOffWrist: Boolean = true,
    val minConfidence: Double? = null,
    val iqrMultiplier: Double = 1.5,
    val smoothingRadius: Int = 1,
    val mismatchThreshold: Double = 3.0,
    val minDistinctDaysPerBucket: Int = 3,
    val calibrationMinPairedBuckets: Int = 6,
    val calibrationTrimFraction: Double = 0.10,
    val calibrationMinSubjectiveMean: Double = 1.0,
    val calibrationScaleMin: Double = 0.005,
    val calibrationScaleMax: Double = 0.20,
    val minCoveragePercentForConfidence: Int = 10,
    val minSamplesForConfidence: Int = 100,
    val zoneId: ZoneId = ZoneId.systemDefault()
)

data class DailyTremorProfileBucket(
    val bucketIndex: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDayInclusive: Int,
    val objectiveMedian: Double?,
    val objectiveQ1: Double?,
    val objectiveQ3: Double?,
    val objectiveMean: Double?,
    val objectiveRawPointCount: Int,
    val objectiveTrimmedPointCount: Int,
    val objectiveOutlierPointCount: Int,
    val objectiveRawSampleCount: Int,
    val objectiveTrimmedSampleCount: Int,
    val objectiveOutlierSampleCount: Int,
    val distinctDaysWithData: Int,
    val lowDayCoverageFlag: Boolean,
    val subjectiveMean: Double?,
    val subjectiveCount: Int,
    val mismatchFlag: Boolean
)

data class DailyTremorProfileMetrics(
    val objectiveOverall: Double?,
    val subjectiveOverall: Double?,
    val bestBucketIndex: Int?,
    val worstBucketIndex: Int?,
    val bestBucketLabel: String?,
    val worstBucketLabel: String?,
    val objectiveCoveragePercent: Int,
    val subjectiveCoveragePercent: Int,
    val correlation: Double?,
    val pairedBucketCount: Int,
    val correlationLabel: String,
    val confidenceScore: Double,
    val confidenceLabel: String,
    val insufficientDataForConfidence: Boolean,
    val outlierRate: Double,
    val avgPerceptionGap: Double?,
    val perceptionGapSampleCount: Int,
    val warnings: List<String>,
    val accessibilitySummary: String
)

data class DailyTremorProfile(
    val config: DailyTremorProfileConfig,
    val generatedAtMs: Long,
    val buckets: List<DailyTremorProfileBucket>,
    val objectiveMedianSmoothed: List<Double?>,
    val objectiveQ1Smoothed: List<Double?>,
    val objectiveQ3Smoothed: List<Double?>,
    val subjectiveRawSmoothed: List<Double?>,
    val subjectiveSmoothed: List<Double?>,
    val subjectiveCalibration: SubjectiveCalibrationInfo,
    val metrics: DailyTremorProfileMetrics
)
