package com.opensource.tremorwatch.phone.scoring

import kotlin.math.log10

/**
 * Maps raw objective severity (nominally 0..10, often near 0 due to zero-inflation)
 * into a user-facing 0..10 "Tremor Index" for charting and comparison to subjective ratings.
 *
 * Notes:
 * - Raw objective values are still stored/exported unchanged.
 * - This mapping is intentionally global/stable (no rolling per-user auto-gain),
 *   so historical trends do not silently change week-to-week.
 */
object TremorIndexMapper {
    private const val RAW_MAX = 10.0

    // Controls the curve "bow". Higher values lift small raw values more.
    // Chosen so that ~0.5-0.6 raw maps to around mid/high single digits.
    private const val CURVE_C = 100.0

    // Precompute denominator: log10(1 + C*RAW_MAX)
    private val denom = log10(1.0 + CURVE_C * RAW_MAX)

    /**
     * @param rawSeverity raw objective severity (expected >= 0, nominally <= 10)
     * @return tremor index in [0, 10]
     */
    fun rawToIndex0to10(rawSeverity: Double): Double {
        if (!rawSeverity.isFinite() || rawSeverity <= 0.0) return 0.0

        val clamped = rawSeverity.coerceIn(0.0, RAW_MAX)
        val numerator = log10(1.0 + CURVE_C * clamped)
        val ratio = if (denom > 0.0) numerator / denom else 0.0
        return (10.0 * ratio).coerceIn(0.0, 10.0)
    }
}

