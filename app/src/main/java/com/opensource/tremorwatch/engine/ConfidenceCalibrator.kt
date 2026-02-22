package com.opensource.tremorwatch.engine

import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import kotlin.math.exp

/**
 * Applies optional confidence calibration to map raw detector confidence into a
 * better-calibrated probability-like score.
 */
object ConfidenceCalibrator {

    fun calibrate(rawConfidence: Float, config: TremorDetectionConfig): Float {
        val raw = rawConfidence.coerceIn(0f, 1f)
        return when (config.confidenceCalibrationMode.lowercase()) {
            "platt" -> platt(raw, config.confidencePlattA, config.confidencePlattB)
            "isotonic" -> isotonic(raw, config.confidenceIsotonicX, config.confidenceIsotonicY)
            else -> raw
        }.coerceIn(0f, 1f)
    }

    private fun platt(x: Float, a: Float, b: Float): Float {
        val z = (a * x + b).toDouble()
        return (1.0 / (1.0 + exp(-z))).toFloat()
    }

    private fun isotonic(x: Float, knotsX: List<Float>, knotsY: List<Float>): Float {
        if (knotsX.size < 2 || knotsX.size != knotsY.size) return x
        if (x <= knotsX.first()) return knotsY.first().coerceIn(0f, 1f)
        if (x >= knotsX.last()) return knotsY.last().coerceIn(0f, 1f)

        for (i in 0 until knotsX.lastIndex) {
            val x0 = knotsX[i]
            val x1 = knotsX[i + 1]
            if (x in x0..x1) {
                val y0 = knotsY[i]
                val y1 = knotsY[i + 1]
                val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0f
                return (y0 + t * (y1 - y0)).coerceIn(0f, 1f)
            }
        }
        return x
    }
}

