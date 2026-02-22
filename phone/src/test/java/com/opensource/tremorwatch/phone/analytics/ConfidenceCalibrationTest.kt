package com.opensource.tremorwatch.phone.analytics

import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceCalibrationTest {

    @Test
    fun platt_fit_improves_brier_on_simple_dataset() {
        val scores = listOf(0.1, 0.2, 0.3, 0.7, 0.8, 0.9)
        val labels = listOf(false, false, false, true, true, true)

        val identity = ConfidenceCalibrationModel.None
        val platt = ConfidenceCalibration.fitPlatt(scores, labels, iterations = 300, learningRate = 0.2)

        val brierIdentity = ConfidenceCalibration.brierScore(scores, labels, identity)
        val brierPlatt = ConfidenceCalibration.brierScore(scores, labels, platt)

        assertTrue("Platt calibration should not worsen this separable dataset", brierPlatt <= brierIdentity)
    }

    @Test
    fun isotonic_apply_is_monotonic() {
        val model = ConfidenceCalibrationModel.Isotonic(
            knotsX = listOf(0.0, 0.3, 0.6, 1.0),
            knotsY = listOf(0.0, 0.4, 0.7, 1.0)
        )
        val p1 = ConfidenceCalibration.apply(0.2, model)
        val p2 = ConfidenceCalibration.apply(0.5, model)
        val p3 = ConfidenceCalibration.apply(0.9, model)
        assertTrue(p1 <= p2 && p2 <= p3)
    }
}

