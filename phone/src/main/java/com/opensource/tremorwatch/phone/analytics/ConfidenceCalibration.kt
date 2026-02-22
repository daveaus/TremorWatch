package com.opensource.tremorwatch.phone.analytics

import kotlin.math.exp
import kotlin.math.ln

sealed interface ConfidenceCalibrationModel {
    data object None : ConfidenceCalibrationModel
    data class Platt(val a: Double, val b: Double) : ConfidenceCalibrationModel
    data class Isotonic(val knotsX: List<Double>, val knotsY: List<Double>) : ConfidenceCalibrationModel
}

object ConfidenceCalibration {

    fun apply(rawConfidence: Double, model: ConfidenceCalibrationModel): Double {
        val x = rawConfidence.coerceIn(0.0, 1.0)
        return when (model) {
            is ConfidenceCalibrationModel.None -> x
            is ConfidenceCalibrationModel.Platt -> {
                val z = model.a * x + model.b
                1.0 / (1.0 + exp(-z))
            }
            is ConfidenceCalibrationModel.Isotonic -> interpolateIsotonic(x, model)
        }.coerceIn(0.0, 1.0)
    }

    /**
     * Fit Platt scaling parameters using simple gradient descent with L2 regularization.
     *
     * @param rawScores Raw confidence scores [0..1]
     * @param labels True=tremor, False=non-tremor
     */
    fun fitPlatt(
        rawScores: List<Double>,
        labels: List<Boolean>,
        iterations: Int = 400,
        learningRate: Double = 0.1,
        l2: Double = 1e-3
    ): ConfidenceCalibrationModel.Platt {
        require(rawScores.size == labels.size) { "rawScores and labels must match in length" }
        if (rawScores.isEmpty()) return ConfidenceCalibrationModel.Platt(a = 1.0, b = 0.0)

        var a = 1.0
        var b = 0.0
        val n = rawScores.size.toDouble()

        repeat(iterations.coerceAtLeast(1)) {
            var gradA = 0.0
            var gradB = 0.0
            for (i in rawScores.indices) {
                val x = rawScores[i].coerceIn(0.0, 1.0)
                val y = if (labels[i]) 1.0 else 0.0
                val p = 1.0 / (1.0 + exp(-(a * x + b)))
                val err = p - y
                gradA += err * x
                gradB += err
            }
            gradA = gradA / n + l2 * a
            gradB /= n
            a -= learningRate * gradA
            b -= learningRate * gradB
        }

        return ConfidenceCalibrationModel.Platt(a = a, b = b)
    }

    /**
     * Fit isotonic regression using Pool Adjacent Violators (PAV).
     * Returns monotonic knot pairs suitable for piecewise-linear interpolation.
     */
    fun fitIsotonic(
        rawScores: List<Double>,
        labels: List<Boolean>,
        minPointsPerBin: Int = 10
    ): ConfidenceCalibrationModel.Isotonic {
        require(rawScores.size == labels.size) { "rawScores and labels must match in length" }
        if (rawScores.isEmpty()) {
            return ConfidenceCalibrationModel.Isotonic(
                knotsX = listOf(0.0, 1.0),
                knotsY = listOf(0.0, 1.0)
            )
        }

        val sorted = rawScores.indices
            .map { i -> rawScores[i].coerceIn(0.0, 1.0) to (if (labels[i]) 1.0 else 0.0) }
            .sortedBy { it.first }

        data class Block(var start: Int, var end: Int, var sum: Double, var count: Int) {
            val mean: Double get() = if (count > 0) sum / count else 0.0
        }

        val blocks = mutableListOf<Block>()
        for (i in sorted.indices) {
            blocks.add(Block(i, i, sorted[i].second, 1))
            while (blocks.size >= 2) {
                val b1 = blocks[blocks.lastIndex - 1]
                val b2 = blocks[blocks.lastIndex]
                if (b1.mean <= b2.mean) break
                b1.end = b2.end
                b1.sum += b2.sum
                b1.count += b2.count
                blocks.removeAt(blocks.lastIndex)
            }
        }

        val x = mutableListOf<Double>()
        val y = mutableListOf<Double>()
        val minBin = minPointsPerBin.coerceAtLeast(1)
        var accCount = 0
        var accWeightedX = 0.0
        var accWeightedY = 0.0

        fun flush() {
            if (accCount <= 0) return
            x += accWeightedX / accCount
            y += accWeightedY / accCount
            accCount = 0
            accWeightedX = 0.0
            accWeightedY = 0.0
        }

        for (block in blocks) {
            val centerX = (sorted[block.start].first + sorted[block.end].first) / 2.0
            val meanY = block.mean.coerceIn(0.0, 1.0)
            accWeightedX += centerX * block.count
            accWeightedY += meanY * block.count
            accCount += block.count
            if (accCount >= minBin) {
                flush()
            }
        }
        flush()

        if (x.isEmpty()) {
            x += listOf(0.0, 1.0)
            y += listOf(0.0, 1.0)
        } else {
            if (x.first() > 0.0) {
                x.add(0, 0.0)
                y.add(0, y.first())
            }
            if (x.last() < 1.0) {
                x += 1.0
                y += y.last()
            }
        }

        return ConfidenceCalibrationModel.Isotonic(knotsX = x, knotsY = y)
    }

    fun brierScore(rawScores: List<Double>, labels: List<Boolean>, model: ConfidenceCalibrationModel): Double {
        if (rawScores.isEmpty() || rawScores.size != labels.size) return Double.NaN
        var sum = 0.0
        for (i in rawScores.indices) {
            val p = apply(rawScores[i], model)
            val y = if (labels[i]) 1.0 else 0.0
            val d = p - y
            sum += d * d
        }
        return sum / rawScores.size.toDouble()
    }

    fun expectedCalibrationError(
        rawScores: List<Double>,
        labels: List<Boolean>,
        model: ConfidenceCalibrationModel,
        bins: Int = 10
    ): Double {
        if (rawScores.isEmpty() || rawScores.size != labels.size) return Double.NaN
        val safeBins = bins.coerceAtLeast(2)
        val counts = IntArray(safeBins)
        val sumPred = DoubleArray(safeBins)
        val sumTrue = DoubleArray(safeBins)

        for (i in rawScores.indices) {
            val p = apply(rawScores[i], model).coerceIn(0.0, 1.0)
            val y = if (labels[i]) 1.0 else 0.0
            val idx = (p * safeBins).toInt().coerceIn(0, safeBins - 1)
            counts[idx]++
            sumPred[idx] += p
            sumTrue[idx] += y
        }

        val n = rawScores.size.toDouble()
        var ece = 0.0
        for (i in 0 until safeBins) {
            if (counts[i] == 0) continue
            val pred = sumPred[i] / counts[i].toDouble()
            val truth = sumTrue[i] / counts[i].toDouble()
            ece += (counts[i] / n) * kotlin.math.abs(pred - truth)
        }
        return ece
    }

    private fun interpolateIsotonic(x: Double, model: ConfidenceCalibrationModel.Isotonic): Double {
        val knotsX = model.knotsX
        val knotsY = model.knotsY
        if (knotsX.size < 2 || knotsX.size != knotsY.size) return x
        if (x <= knotsX.first()) return knotsY.first().coerceIn(0.0, 1.0)
        if (x >= knotsX.last()) return knotsY.last().coerceIn(0.0, 1.0)

        for (i in 0 until knotsX.lastIndex) {
            val x0 = knotsX[i]
            val x1 = knotsX[i + 1]
            if (x in x0..x1) {
                val y0 = knotsY[i]
                val y1 = knotsY[i + 1]
                val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0.0
                return (y0 + (y1 - y0) * t).coerceIn(0.0, 1.0)
            }
        }
        return x
    }

    fun negativeLogLikelihood(rawScores: List<Double>, labels: List<Boolean>, model: ConfidenceCalibrationModel): Double {
        if (rawScores.isEmpty() || rawScores.size != labels.size) return Double.NaN
        val eps = 1e-9
        var nll = 0.0
        for (i in rawScores.indices) {
            val p = apply(rawScores[i], model).coerceIn(eps, 1.0 - eps)
            val y = if (labels[i]) 1.0 else 0.0
            nll += -(y * ln(p) + (1.0 - y) * ln(1.0 - p))
        }
        return nll / rawScores.size.toDouble()
    }
}

