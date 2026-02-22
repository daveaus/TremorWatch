package com.opensource.tremorwatch.phone.analytics

import com.opensource.tremorwatch.phone.stats.StatsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationStateSpaceModelTest {

    @Test
    fun inferDoseResponse_detects_improvement_on_synthetic_curve() {
        val doseTs = 1_000_000L
        val samples = mutableListOf<StatsSample>()
        for (m in -60..180) {
            val ts = doseTs + m * 60_000L
            val sev = when {
                m < 30 -> 4.0
                m <= 120 -> 2.0
                else -> 4.0
            }
            samples += sample(ts, sev)
        }

        val result = MedicationStateSpaceModel.inferDoseResponse(samples, doseTs)
        assertTrue(result.inferable)
        assertEquals("improved", result.status)
        assertNotNull(result.onsetMinutes)
        assertTrue((result.deltaPercent ?: 0.0) < 0.0)
    }

    @Test
    fun inferDoseResponse_returns_insufficient_when_sparse() {
        val doseTs = 2_000_000L
        val sparse = listOf(
            sample(doseTs - 30 * 60_000L, 3.0),
            sample(doseTs + 45 * 60_000L, 2.5)
        )
        val result = MedicationStateSpaceModel.inferDoseResponse(sparse, doseTs)
        assertEquals(false, result.inferable)
        assertEquals("insufficient", result.status)
    }

    private fun sample(timestamp: Long, severity: Double): StatsSample {
        return StatsSample(
            timestamp = timestamp,
            severityRaw = severity,
            tremorCount = if (severity > 0.5) 1 else 0,
            isWorn = true,
            isCharging = false,
            confidence = 0.9,
            calibratedConfidence = 0.9,
            isReliableMeasurement = true,
            excludeFromAnalysis = false,
            activityType = "still",
            activityAdjustedSeverity = severity
        )
    }
}

