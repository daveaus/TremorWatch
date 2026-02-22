package com.opensource.tremorwatch.phone.analytics

import com.opensource.tremorwatch.phone.stats.StatsSample
import com.opensource.tremorwatch.phone.stats.activityTypeCanonical
import kotlin.math.max

data class MedicationWindowGateConfig(
    val minPreMinutes: Int = 15,
    val minPostMinutes: Int = 40,
    val minClinicalPreMinutes: Int = 10,
    val minClinicalPostMinutes: Int = 20,
    val maxCoverageImbalanceRatio: Double = 3.0,
    val maxArtifactRatio: Double = 0.5
)

data class MedicationWindowQuality(
    val usableMinutes: Int,
    val clinicalMinutes: Int,
    val artifactRatio: Double
)

data class MedicationWindowEvaluation(
    val inferable: Boolean,
    val reason: String?,
    val pre: MedicationWindowQuality,
    val post: MedicationWindowQuality
)

/**
 * Hard sufficiency gate for medication-response windows.
 *
 * This explicitly prefers "insufficient data" over potentially misleading deltas
 * when pre/post windows are sparse or activity-confounded.
 */
object MedicationWindowQualityGate {

    fun evaluate(
        preWindow: List<StatsSample>,
        postWindow: List<StatsSample>,
        config: MedicationWindowGateConfig = MedicationWindowGateConfig()
    ): MedicationWindowEvaluation {
        val pre = summarize(preWindow)
        val post = summarize(postWindow)

        if (pre.usableMinutes < config.minPreMinutes) {
            return fail("Insufficient pre-window usable coverage", pre, post)
        }
        if (post.usableMinutes < config.minPostMinutes) {
            return fail("Insufficient post-window usable coverage", pre, post)
        }
        if (pre.clinicalMinutes < config.minClinicalPreMinutes) {
            return fail("Insufficient pre-window clinical coverage", pre, post)
        }
        if (post.clinicalMinutes < config.minClinicalPostMinutes) {
            return fail("Insufficient post-window clinical coverage", pre, post)
        }

        // Compare coverage relative to each window's minimum requirement rather than raw minute counts.
        // Pre/post windows have different lengths by design, so raw counts unfairly penalize valid data.
        val preNormalizedCoverage = pre.usableMinutes.toDouble() / max(1, config.minPreMinutes)
        val postNormalizedCoverage = post.usableMinutes.toDouble() / max(1, config.minPostMinutes)
        val imbalance = max(preNormalizedCoverage, postNormalizedCoverage) /
            max(1e-6, minOf(preNormalizedCoverage, postNormalizedCoverage))
        if (imbalance > config.maxCoverageImbalanceRatio) {
            return fail("Pre/post coverage imbalance too high", pre, post)
        }

        if (pre.artifactRatio > config.maxArtifactRatio || post.artifactRatio > config.maxArtifactRatio) {
            return fail("Window confounded by activity artifacts", pre, post)
        }

        return MedicationWindowEvaluation(
            inferable = true,
            reason = null,
            pre = pre,
            post = post
        )
    }

    private fun fail(
        reason: String,
        pre: MedicationWindowQuality,
        post: MedicationWindowQuality
    ) = MedicationWindowEvaluation(
        inferable = false,
        reason = reason,
        pre = pre,
        post = post
    )

    private fun summarize(window: List<StatsSample>): MedicationWindowQuality {
        if (window.isEmpty()) {
            return MedicationWindowQuality(usableMinutes = 0, clinicalMinutes = 0, artifactRatio = 1.0)
        }

        val usableMinutes = window
            .filter { isUsable(it) }
            .map { it.timestamp / 60_000L }
            .distinct()
            .size

        val clinicalMinutes = window
            .filter { isClinical(it) }
            .map { it.timestamp / 60_000L }
            .distinct()
            .size

        val artifactCount = window.count { isArtifactContext(it) }
        val artifactRatio = artifactCount.toDouble() / window.size.toDouble()

        return MedicationWindowQuality(
            usableMinutes = usableMinutes,
            clinicalMinutes = clinicalMinutes,
            artifactRatio = artifactRatio
        )
    }

    private fun isUsable(sample: StatsSample): Boolean {
        val conf = sample.calibratedConfidence ?: sample.confidence ?: 0.0
        return sample.isWorn == true &&
            sample.isCharging != true &&
            conf >= 0.10 &&
            sample.excludeFromAnalysis != true
    }

    private fun isClinical(sample: StatsSample): Boolean {
        val conf = sample.calibratedConfidence ?: sample.confidence ?: 0.0
        return sample.isWorn == true &&
            sample.isCharging != true &&
            conf >= 0.15 &&
            sample.isReliableMeasurement == true &&
            sample.excludeFromAnalysis != true
    }

    private fun isArtifactContext(sample: StatsSample): Boolean {
        val activity = sample.activityTypeCanonical()
        val steps = sample.stepsPerMinute ?: 0
        return sample.excludeFromAnalysis == true ||
            steps > 130 ||
            activity == "RUNNING" ||
            activity == "IN_VEHICLE" ||
            activity == "ON_BICYCLE"
    }
}
