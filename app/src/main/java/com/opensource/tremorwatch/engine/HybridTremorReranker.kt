package com.opensource.tremorwatch.engine

import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import kotlin.math.exp

data class HybridRerankerInput(
    val baseConfidence: Float,
    val bandRatio: Float,
    val spectralEntropy: Float,
    val harmonicRatio: Float,
    val crossSensorSupport: Float,
    val frequencyStability: Float,
    val stepsPerMinute: Int,
    val isRestingState: Boolean
)

data class HybridRerankerResult(
    val probability: Float,
    val blendedConfidence: Float,
    val supportsTremor: Boolean
)

/**
 * Lightweight logistic reranker that sits on top of rule-based detection.
 *
 * The reranker does not replace hard safety rules (off-body/charging/activity gates).
 * It only adjusts borderline tremor confidence.
 */
object HybridTremorReranker {

    fun rerank(input: HybridRerankerInput, config: TremorDetectionConfig): HybridRerankerResult {
        val base = input.baseConfidence.coerceIn(0f, 1f)
        if (!config.hybridRerankerEnabled) {
            return HybridRerankerResult(
                probability = base,
                blendedConfidence = base,
                supportsTremor = base >= config.hybridRerankerThreshold
            )
        }

        val z = config.hybridRerankerIntercept +
            config.hybridRerankerWConfidence * base +
            config.hybridRerankerWBandRatio * input.bandRatio.coerceIn(0f, 1f) +
            config.hybridRerankerWEntropy * input.spectralEntropy.coerceIn(0f, 1f) +
            config.hybridRerankerWHarmonic * input.harmonicRatio.coerceIn(0f, 2f) +
            config.hybridRerankerWCrossSensor * input.crossSensorSupport.coerceIn(0f, 1f) +
            config.hybridRerankerWFreqStability * input.frequencyStability.coerceIn(0f, 1f) +
            config.hybridRerankerWStepsPerMinute * input.stepsPerMinute.toFloat() +
            if (input.isRestingState) 0.15f else 0f

        val probability = sigmoid(z).coerceIn(0f, 1f)
        val blend = config.hybridRerankerBlend.coerceIn(0f, 1f)
        val blendedConfidence = ((1f - blend) * base + blend * probability).coerceIn(0f, 1f)
        val supportsTremor = probability >= config.hybridRerankerThreshold

        return HybridRerankerResult(
            probability = probability,
            blendedConfidence = blendedConfidence,
            supportsTremor = supportsTremor
        )
    }

    private fun sigmoid(x: Float): Float {
        val d = x.toDouble()
        return (1.0 / (1.0 + exp(-d))).toFloat()
    }
}

