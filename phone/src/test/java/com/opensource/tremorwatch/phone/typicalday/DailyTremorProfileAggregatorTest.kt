package com.opensource.tremorwatch.phone.typicalday

import com.opensource.tremorwatch.phone.database.MinuteAggregateRow
import com.opensource.tremorwatch.phone.database.RatingSampleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class DailyTremorProfileAggregatorTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun trimOutliersIqr_handlesUnsortedInputSafely() {
        val input = listOf(12.0, 1.0, 1.1, 1.2, 1.3, 1.4)
        val trimmed = DailyTremorProfileAggregator.trimOutliersIqr(input, 1.5)
        assertTrue(trimmed.none { it == 12.0 })
    }

    @Test
    fun build_marksLowCoverageBucket_andPreservesCounts() {
        val minuteRows = listOf(
            MinuteAggregateRow(
                minuteBucketTimestamp = ts(dayOffset = 0, hour = 8),
                avgSeverity = 4.0,
                sampleCount = 60,
                anyCharging = 0,
                anyOffWrist = 0,
                avgConfidence = 0.9
            )
        )

        val profile = DailyTremorProfileAggregator.build(
            config = DailyTremorProfileConfig(
                days = 14,
                bucketMinutes = 60,
                includeSubjective = false,
                minDistinctDaysPerBucket = 3,
                zoneId = zone
            ),
            minuteRows = minuteRows,
            ratingRows = emptyList(),
            nowMs = ts(dayOffset = 2, hour = 12)
        )

        val bucket = profile.buckets[8]
        assertTrue(bucket.lowDayCoverageFlag)
        assertNull(bucket.objectiveMedian)
        assertEquals(60, bucket.objectiveRawSampleCount)
        assertEquals(60, bucket.objectiveTrimmedSampleCount)
    }

    @Test
    fun build_setsMismatchFlag_whenGapExceedsThreshold() {
        val minuteRows = listOf(
            MinuteAggregateRow(ts(0, 9), 2.0, 50, 0, 0, 0.9),
            MinuteAggregateRow(ts(1, 9), 2.2, 50, 0, 0, 0.9),
            MinuteAggregateRow(ts(2, 9), 2.1, 50, 0, 0, 0.9)
        )
        val ratingRows = listOf(
            RatingSampleRow(ts(0, 9), 5, detectedSeverity = 2.0, source = "MANUAL"),
            RatingSampleRow(ts(1, 9), 5, detectedSeverity = 2.1, source = "MANUAL"),
            RatingSampleRow(ts(2, 9), 5, detectedSeverity = 2.2, source = "MANUAL")
        )

        val profile = DailyTremorProfileAggregator.build(
            config = DailyTremorProfileConfig(
                days = 14,
                bucketMinutes = 60,
                includeSubjective = true,
                mismatchThreshold = 2.0,
                minDistinctDaysPerBucket = 1,
                zoneId = zone
            ),
            minuteRows = minuteRows,
            ratingRows = ratingRows,
            nowMs = ts(dayOffset = 3, hour = 12)
        )

        val bucket = profile.buckets[9]
        assertTrue(bucket.mismatchFlag)
    }

    @Test
    fun build_appliesConfidenceFloor_onLowCoverageAndSamples() {
        val minuteRows = listOf(
            MinuteAggregateRow(
                minuteBucketTimestamp = ts(dayOffset = 0, hour = 11),
                avgSeverity = 3.0,
                sampleCount = 20,
                anyCharging = 0,
                anyOffWrist = 0,
                avgConfidence = 0.8
            )
        )

        val profile = DailyTremorProfileAggregator.build(
            config = DailyTremorProfileConfig(
                days = 7,
                bucketMinutes = 60,
                includeSubjective = false,
                minDistinctDaysPerBucket = 1,
                minCoveragePercentForConfidence = 50,
                minSamplesForConfidence = 100,
                zoneId = zone
            ),
            minuteRows = minuteRows,
            ratingRows = emptyList(),
            nowMs = ts(dayOffset = 1, hour = 12)
        )

        assertTrue(profile.metrics.insufficientDataForConfidence)
        assertEquals(0.0, profile.metrics.confidenceScore, 0.0001)
        assertEquals("Insufficient Data", profile.metrics.confidenceLabel)
    }

    @Test
    fun build_appliesDisplayCalibration_whenEnoughPairedBuckets() {
        val minuteRows = (0..7).map { hour ->
            MinuteAggregateRow(
                minuteBucketTimestamp = ts(dayOffset = 0, hour = hour),
                avgSeverity = 0.08 + (hour * 0.01),
                sampleCount = 60,
                anyCharging = 0,
                anyOffWrist = 0,
                avgConfidence = 0.95
            )
        }
        val ratingRows = (0..7).map { hour ->
            RatingSampleRow(
                timestamp = ts(dayOffset = 0, hour = hour),
                rating = 4,
                detectedSeverity = null,
                source = "MANUAL"
            )
        }

        val profile = DailyTremorProfileAggregator.build(
            config = DailyTremorProfileConfig(
                days = 7,
                bucketMinutes = 60,
                includeSubjective = true,
                subjectiveOverlayMode = SubjectiveOverlayMode.CALIBRATED_SCALED,
                minDistinctDaysPerBucket = 1,
                zoneId = zone
            ),
            minuteRows = minuteRows,
            ratingRows = ratingRows,
            nowMs = ts(dayOffset = 1, hour = 12)
        )

        assertTrue(profile.subjectiveCalibration.applied)
        assertEquals(SubjectiveOverlayMode.CALIBRATED_SCALED, profile.subjectiveCalibration.appliedMode)
        assertNotNull(profile.subjectiveCalibration.scale)
        assertTrue((profile.subjectiveCalibration.scale ?: 0.0) > 0.0)
    }

    @Test
    fun build_fallsBackToRaw_whenCalibrationHasTooFewPairs() {
        val minuteRows = (0L..2L).map { day ->
            MinuteAggregateRow(
                minuteBucketTimestamp = ts(dayOffset = day, hour = 10),
                avgSeverity = 0.1 + (day * 0.01),
                sampleCount = 40,
                anyCharging = 0,
                anyOffWrist = 0,
                avgConfidence = 0.9
            )
        }
        val ratingRows = (0L..2L).map { day ->
            RatingSampleRow(
                timestamp = ts(dayOffset = day, hour = 10),
                rating = 3,
                detectedSeverity = null,
                source = "MANUAL"
            )
        }

        val profile = DailyTremorProfileAggregator.build(
            config = DailyTremorProfileConfig(
                days = 7,
                bucketMinutes = 60,
                includeSubjective = true,
                subjectiveOverlayMode = SubjectiveOverlayMode.CALIBRATED_SCALED,
                calibrationMinPairedBuckets = 6,
                minDistinctDaysPerBucket = 1,
                zoneId = zone
            ),
            minuteRows = minuteRows,
            ratingRows = ratingRows,
            nowMs = ts(dayOffset = 3, hour = 12)
        )

        assertFalse(profile.subjectiveCalibration.applied)
        assertEquals(SubjectiveOverlayMode.RAW_X2, profile.subjectiveCalibration.appliedMode)
        assertNotNull(profile.subjectiveCalibration.fallbackReason)
    }

    private fun ts(dayOffset: Long, hour: Int): Long {
        return LocalDate.of(2026, 1, 1)
            .plusDays(dayOffset)
            .atTime(hour, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }
}
