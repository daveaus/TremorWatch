package com.opensource.tremorwatch.shared.models

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Active Learning Training Module — shared data models.
 * Used by both watch (app) and phone modules for training data exchange.
 */

// ── Enums ──

/** User feedback label for a training sample. */
@Serializable
enum class FeedbackLabel {
    /** User confirmed this was a real tremor */
    YES_TREMOR,
    /** User denied — this was a false positive (motion artifact) */
    NO_ACTIVE,
    /** User skipped or prompt timed out */
    IGNORE
}

/** Training state machine phases. */
@Serializable
enum class TrainingState {
    /** Training not active */
    OFF,
    /** Collecting initial data before first prompt */
    WARMUP,
    /** Actively prompting and collecting labels */
    ACTIVE,
    /** Minimum labels reached; awaiting user decision to finalize */
    READY_TO_FINALIZE,
    /** Training complete; personalized parameters active */
    PERSONALIZED
}

// ── Data Classes ──

/**
 * Snapshot of detection features at the moment a training prompt was triggered.
 * Captures everything needed to replay the detection decision offline.
 */
@Serializable
data class FeedbackFeatureSnapshot(
    // Core FFT features
    val dominantFrequency: Float,
    val bandRatio: Float,
    val confidence: Float,
    val calibratedConfidence: Float = 0f,
    val totalPower: Float,
    val tremorBandPower: Float,
    // Extended features
    val spectralEntropy: Float = 0f,
    val harmonicRatio: Float = 0f,
    val peakProminence: Float = 0f,
    // Engine-level features
    val crossSensorSupport: Float = 0f,
    val frequencyStability: Float = 0f,
    val magnitude: Float = 0f,
    val accelMagnitude: Float = 0f,
    // Context
    val activityType: String = "unknown",
    val activityConfidence: Float = 0f,
    val isResting: Boolean = true
)

/**
 * A complete training sample: feature snapshot + user label + metadata.
 * This is the unit of data exchanged between watch and phone.
 */
@Serializable
data class TrainingSample(
    val sampleId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val watchId: String? = null,
    // User feedback
    val feedback: FeedbackLabel = FeedbackLabel.IGNORE,
    val feedbackTimestamp: Long? = null,
    val responseLatencyMs: Long? = null,
    // Core FFT features
    val dominantFrequency: Float = 0f,
    val bandRatio: Float = 0f,
    val confidence: Float = 0f,
    val calibratedConfidence: Float = 0f,
    val totalPower: Float = 0f,
    val tremorBandPower: Float = 0f,
    // Extended features
    val spectralEntropy: Float = 0f,
    val harmonicRatio: Float = 0f,
    val peakProminence: Float = 0f,
    // Engine-level features
    val crossSensorSupport: Float = 0f,
    val frequencyStability: Float = 0f,
    val magnitude: Float = 0f,
    val accelMagnitude: Float = 0f,
    // Context
    val activityType: String = "unknown",
    val activityConfidence: Float = 0f,
    val isResting: Boolean = true,
    // Detection outcomes at prompt time
    val productionIsTremor: Boolean = false,
    val shadowIsTremor: Boolean = false,
    val triggerReason: String = ""
)

/**
 * Shared training thresholds used across watch + phone to keep behavior aligned.
 */
object TrainingThresholds {
    const val MIN_USABLE_LABELS_FOR_PERSONALIZATION = 10
}
