package com.opensource.tremorwatch.phone.analytics

import com.opensource.tremorwatch.phone.stats.StatsSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationWindowQualityGateTest {

    @Test
    fun evaluate_fails_when_pre_window_sparse() {
        val pre = buildWindow(startMs = 0L, minutes = 5, confidence = 0.2)
        val post = buildWindow(startMs = 60_000L, minutes = 120, confidence = 0.2)

        val result = MedicationWindowQualityGate.evaluate(pre, post)
        assertFalse(result.inferable)
        assertTrue(result.reason?.contains("pre-window", ignoreCase = true) == true)
    }

    @Test
    fun evaluate_passes_when_both_windows_sufficient() {
        val pre = buildWindow(startMs = 0L, minutes = 45, confidence = 0.2, reliable = true, activityType = "still")
        val post = buildWindow(startMs = 60_000L, minutes = 120, confidence = 0.2, reliable = true, activityType = "still")

        val result = MedicationWindowQualityGate.evaluate(pre, post)
        assertTrue(result.inferable)
    }

    private fun buildWindow(
        startMs: Long,
        minutes: Int,
        confidence: Double,
        reliable: Boolean = true,
        activityType: String = "still"
    ): List<StatsSample> {
        return (0 until minutes).map { idx ->
            StatsSample(
                timestamp = startMs + idx * 60_000L,
                severityRaw = 1.0,
                tremorCount = 1,
                isWorn = true,
                isCharging = false,
                confidence = confidence,
                activityType = activityType,
                isReliableMeasurement = reliable,
                excludeFromAnalysis = false
            )
        }
    }
}
