package com.opensource.tremorwatch.phone.typicalday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max

@Composable
fun DailyTremorProfileCard(
    profile: DailyTremorProfile,
    selectedDays: Int,
    selectedBucketMinutes: Int,
    selectedOverlayMode: SubjectiveOverlayMode,
    onDaysSelected: (Int) -> Unit,
    onBucketMinutesSelected: (Int) -> Unit,
    onOverlayModeSelected: (SubjectiveOverlayMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val daysOptions = listOf(7, 14, 30, 60)
    val bucketOptions = listOf(30, 60)
    val overlayOptions = listOf(
        SubjectiveOverlayMode.RAW_X2 to "Raw x2",
        SubjectiveOverlayMode.CALIBRATED_SCALED to "Calibrated"
    )
    val chartAxis = resolveChartAxis(profile)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Daily Tremor Profile",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Objective severity (raw) with optional subjective display calibration",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Window",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                daysOptions.forEach { days ->
                    FilterChip(
                        selected = selectedDays == days,
                        onClick = { onDaysSelected(days) },
                        label = { Text("${days}d") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Bucket",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bucketOptions.forEach { minutes ->
                    FilterChip(
                        selected = selectedBucketMinutes == minutes,
                        onClick = { onBucketMinutesSelected(minutes) },
                        label = { Text("${minutes}m") }
                    )
                }
            }

            if (profile.config.includeSubjective) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Subjective Overlay",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    overlayOptions.forEach { (mode, label) ->
                        FilterChip(
                            selected = selectedOverlayMode == mode,
                            onClick = { onOverlayModeSelected(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            DailyTremorProfileChart(
                profile = profile,
                axis = chartAxis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (chartAxis.autoScaled) {
                    "Y-axis: 0-${formatAxisValue(chartAxis.max)} (auto for calibrated overlay)"
                } else {
                    "Y-axis: 0-10 (fixed)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            DailyProfileLegend(includeSubjective = profile.config.includeSubjective)

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Raw Correlation: ${
                        profile.metrics.correlation?.let { "%.2f".format(it) } ?: "n/a"
                    } (${profile.metrics.correlationLabel})",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "Confidence: ${"%.0f".format(profile.metrics.confidenceScore * 100)}% ${profile.metrics.confidenceLabel}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (profile.config.includeSubjective) {
                Spacer(modifier = Modifier.height(6.dp))
                val calibration = profile.subjectiveCalibration
                val calibrationText = when {
                    calibration.appliedMode == SubjectiveOverlayMode.CALIBRATED_SCALED &&
                        calibration.scale != null -> {
                        "Calibrated overlay x${"%.4f".format(calibration.scale)} using ${calibration.trimmedBucketCount}/${calibration.pairedBucketCount} paired buckets."
                    }
                    calibration.requestedMode == SubjectiveOverlayMode.CALIBRATED_SCALED -> {
                        "Calibration unavailable: ${calibration.fallbackReason ?: "insufficient paired data"}. Showing Raw x2."
                    }
                    else -> {
                        "Subjective overlay in Raw x2 mode."
                    }
                }
                val calibrationColor = if (
                    calibration.requestedMode == SubjectiveOverlayMode.CALIBRATED_SCALED &&
                    !calibration.applied
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = calibrationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = calibrationColor
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Best: ${profile.metrics.bestBucketLabel ?: "n/a"}  •  Worst: ${profile.metrics.worstBucketLabel ?: "n/a"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (profile.metrics.warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = profile.metrics.warnings.joinToString(separator = " | "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun DailyProfileLegend(includeSubjective: Boolean) {
    val objectiveColor = Color(0xFF26D9B0)
    val subjectiveColor = Color(0xFFFFA726)
    val mismatchColor = Color(0xFFE53935)
    val bandColor = objectiveColor.copy(alpha = 0.20f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot(label = "Objective", color = objectiveColor)
        if (includeSubjective) {
            LegendDot(label = "Subjective", color = subjectiveColor)
        }
        LegendDot(label = "IQR Band", color = bandColor)
        LegendDot(label = "Mismatch", color = mismatchColor)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .background(color = color, shape = RoundedCornerShape(5.dp))
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DailyTremorProfileChart(
    profile: DailyTremorProfile,
    axis: ChartAxis,
    modifier: Modifier = Modifier
) {
    val objectiveColor = Color(0xFF26D9B0)
    val subjectiveColor = Color(0xFFFFA726)
    val mismatchColor = Color(0xFFE53935)
    val objectiveBand = objectiveColor.copy(alpha = 0.20f)
    val gridColor = Color(0xFF4A5A6A)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFF1E2836), RoundedCornerShape(8.dp))
            .padding(12.dp)
            .semantics {
                contentDescription = profile.metrics.accessibilitySummary
            }
    ) {
        val width = size.width
        val height = size.height
        val count = max(1, profile.buckets.size)

        fun xFor(index: Int): Float = if (count <= 1) {
            width / 2f
        } else {
            (index.toFloat() / (count - 1).toFloat()) * width
        }

        fun yFor(value: Double): Float =
            (height - (value.coerceIn(0.0, axis.max) / axis.max) * height).toFloat()

        axis.ticks.forEach { tick ->
            val y = yFor(tick)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1f
            )
        }

        // Draw IQR band in contiguous valid segments only to avoid gap-bridging artifacts.
        contiguousBandSegments(profile.objectiveQ1Smoothed, profile.objectiveQ3Smoothed).forEach { segment ->
            val path = Path()
            val start = segment.first
            path.moveTo(xFor(start), yFor(profile.objectiveQ3Smoothed[start]!!))

            for (i in (start + 1)..segment.last) {
                path.lineTo(xFor(i), yFor(profile.objectiveQ3Smoothed[i]!!))
            }
            for (i in segment.last downTo segment.first) {
                path.lineTo(xFor(i), yFor(profile.objectiveQ1Smoothed[i]!!))
            }
            path.close()

            drawPath(path = path, color = objectiveBand)
        }

        drawSegmentedLine(
            values = profile.objectiveMedianSmoothed,
            xFor = ::xFor,
            yFor = ::yFor,
            color = objectiveColor,
            dashed = false,
            strokeWidth = 3f
        )

        drawSegmentedLine(
            values = profile.subjectiveSmoothed,
            xFor = ::xFor,
            yFor = ::yFor,
            color = subjectiveColor,
            dashed = true,
            strokeWidth = if (axis.autoScaled) 4f else 3f
        )

        if (axis.autoScaled) {
            profile.subjectiveSmoothed.forEachIndexed { index, value ->
                if (value != null) {
                    drawCircle(
                        color = subjectiveColor,
                        radius = 2.4f,
                        center = Offset(xFor(index), yFor(value))
                    )
                }
            }
        }

        // Draw mismatch markers
        profile.buckets.forEachIndexed { index, bucket ->
            if (bucket.mismatchFlag) {
                val yValue = profile.objectiveMedianSmoothed[index] ?: return@forEachIndexed
                drawCircle(
                    color = mismatchColor,
                    radius = 4f,
                    center = Offset(xFor(index), yFor(yValue))
                )
            }
        }
    }
}

private fun contiguousBandSegments(
    q1: List<Double?>,
    q3: List<Double?>
): List<IntRange> {
    if (q1.isEmpty() || q3.isEmpty() || q1.size != q3.size) return emptyList()

    val segments = mutableListOf<IntRange>()
    var start: Int? = null

    for (index in q1.indices) {
        val valid = q1[index] != null && q3[index] != null
        if (valid && start == null) {
            start = index
        } else if (!valid && start != null) {
            if (index - 1 >= start) {
                segments.add(start..(index - 1))
            }
            start = null
        }
    }
    if (start != null) {
        segments.add(start..q1.lastIndex)
    }
    return segments
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSegmentedLine(
    values: List<Double?>,
    xFor: (Int) -> Float,
    yFor: (Double) -> Float,
    color: Color,
    dashed: Boolean,
    strokeWidth: Float
) {
    var segmentStart = -1

    fun drawSegment(start: Int, end: Int) {
        if (start < 0 || end < start) return
        val path = Path()
        path.moveTo(xFor(start), yFor(values[start]!!))
        for (i in (start + 1)..end) {
            path.lineTo(xFor(i), yFor(values[i]!!))
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) else null
            )
        )
    }

    values.forEachIndexed { index, value ->
        if (value != null && segmentStart < 0) {
            segmentStart = index
        }
        val endOfSegment = value == null || index == values.lastIndex
        if (segmentStart >= 0 && endOfSegment) {
            val segmentEnd = if (value == null) index - 1 else index
            drawSegment(segmentStart, segmentEnd)
            segmentStart = -1
        }
    }
}

private data class ChartAxis(
    val max: Double,
    val ticks: List<Double>,
    val autoScaled: Boolean
)

private fun resolveChartAxis(profile: DailyTremorProfile): ChartAxis {
    val calibratedApplied =
        profile.subjectiveCalibration.appliedMode == SubjectiveOverlayMode.CALIBRATED_SCALED

    if (!calibratedApplied) {
        return ChartAxis(
            max = 10.0,
            ticks = listOf(0.0, 5.0, 10.0),
            autoScaled = false
        )
    }

    val observedMax = buildList {
        addAll(profile.objectiveMedianSmoothed.filterNotNull())
        addAll(profile.objectiveQ3Smoothed.filterNotNull())
        addAll(profile.subjectiveSmoothed.filterNotNull())
    }.maxOrNull() ?: 0.2

    val paddedMax = (observedMax * 1.25).coerceAtLeast(0.2)
    val snappedMax = snapAxisMax(paddedMax).coerceAtMost(10.0)

    return ChartAxis(
        max = snappedMax,
        ticks = listOf(0.0, snappedMax / 2.0, snappedMax),
        autoScaled = true
    )
}

private fun snapAxisMax(value: Double): Double {
    val steps = listOf(0.2, 0.25, 0.3, 0.4, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0)
    return steps.firstOrNull { value <= it } ?: 10.0
}

private fun formatAxisValue(value: Double): String {
    val formatted = if (value >= 1.0) {
        String.format(Locale.US, "%.2f", value)
    } else {
        String.format(Locale.US, "%.3f", value)
    }
    return formatted.trimEnd('0').trimEnd('.')
}
