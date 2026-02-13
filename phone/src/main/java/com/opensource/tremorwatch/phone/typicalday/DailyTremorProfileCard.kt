package com.opensource.tremorwatch.phone.typicalday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun DailyTremorProfileCard(
    profile: DailyTremorProfile,
    selectedDays: Int,
    selectedBucketMinutes: Int,
    selectedOverlayMode: SubjectiveOverlayMode,
    selectedSeriesMode: DailyProfileSeriesMode,
    onDaysSelected: (Int) -> Unit,
    onBucketMinutesSelected: (Int) -> Unit,
    onOverlayModeSelected: (SubjectiveOverlayMode) -> Unit,
    onSeriesModeSelected: (DailyProfileSeriesMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayOptions = listOf(7, 14, 30, 60)
    val bucketOptions = listOf(30, 60)
    val showObjective = selectedSeriesMode != DailyProfileSeriesMode.SUBJECTIVE_ONLY
    val showSubjective = profile.config.includeSubjective && selectedSeriesMode != DailyProfileSeriesMode.OBJECTIVE_ONLY
    val chartAxis = resolveChartAxis(profile, showObjective = showObjective, showSubjective = showSubjective)

    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var selectedBucketIndex by rememberSaveable { mutableStateOf<Int?>(null) }

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
                text = "Sensor vs self-rating trend comparison",
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
                dayOptions.forEach { days ->
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Show",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    DailyProfileSeriesMode.BOTH to "Both",
                    DailyProfileSeriesMode.OBJECTIVE_ONLY to "Sensor",
                    DailyProfileSeriesMode.SUBJECTIVE_ONLY to "Self"
                ).forEach { (mode, label) ->
                    val enabled = when (mode) {
                        DailyProfileSeriesMode.SUBJECTIVE_ONLY -> profile.config.includeSubjective
                        else -> true
                    }
                    FilterChip(
                        selected = selectedSeriesMode == mode,
                        onClick = {
                            if (enabled) {
                                onSeriesModeSelected(mode)
                            }
                        },
                        enabled = enabled,
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            DailyTremorProfileChart(
                profile = profile,
                axis = chartAxis,
                showObjective = showObjective,
                showSubjective = showSubjective,
                onBucketTapped = { tappedIndex ->
                    selectedBucketIndex = tappedIndex
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (chartAxis.autoScaled) {
                    "Axis auto-scaled for readability"
                } else {
                    "Axis fixed to 0-10"
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

            DailyProfileLegend(
                showObjective = showObjective,
                showSubjective = showSubjective
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildAlignmentSummary(
                        correlation = profile.metrics.correlation,
                        label = profile.metrics.correlationLabel
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
                DataQualityBadge(metrics = profile.metrics)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Sensor uses a Tremor Index (0-10) derived from raw data. Self-ratings are 0-5 shown as 0-10 (×2).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide Details" else "Show Details")
            }

            if (showAdvanced) {
                Text(
                    text = "Raw correlation: ${profile.metrics.correlation?.let { formatValue(it, 2) } ?: "n/a"} (${profile.metrics.correlationLabel})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Confidence: ${"%.0f".format(profile.metrics.confidenceScore * 100)}% ${profile.metrics.confidenceLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Paired buckets: ${profile.metrics.pairedBucketCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (profile.metrics.warnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    profile.metrics.warnings.take(3).forEach { warning ->
                        Text(
                            text = "- $warning",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    selectedBucketIndex?.let { index ->
        if (index in profile.buckets.indices) {
            BucketDetailsDialog(
                profile = profile,
                bucketIndex = index,
                onDismiss = { selectedBucketIndex = null }
            )
        }
    }
}

@Composable
private fun DailyProfileLegend(
    showObjective: Boolean,
    showSubjective: Boolean
) {
    val objectiveColor = Color(0xFF26D9B0)
    val subjectiveColor = Color(0xFFFFA726)
    val bandColor = objectiveColor.copy(alpha = 0.14f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showObjective) {
            LegendDot(label = "Sensor (Index)", color = objectiveColor)
            LegendDot(label = "P25-P75", color = bandColor)
        }
        if (showSubjective) {
            LegendDot(label = "Self", color = subjectiveColor)
        }
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
private fun DataQualityBadge(metrics: DailyTremorProfileMetrics) {
    val (label, color) = when {
        metrics.insufficientDataForConfidence -> "Limited" to MaterialTheme.colorScheme.error
        metrics.confidenceScore >= 0.75 -> "High" to Color(0xFF2E7D32)
        metrics.confidenceScore >= 0.50 -> "Moderate" to Color(0xFFEF6C00)
        else -> "Low" to MaterialTheme.colorScheme.error
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.18f))
    ) {
        Text(
            text = "Data: $label",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun DailyTremorProfileChart(
    profile: DailyTremorProfile,
    axis: ChartAxis,
    showObjective: Boolean,
    showSubjective: Boolean,
    onBucketTapped: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val objectiveColor = Color(0xFF26D9B0)
    val subjectiveColor = Color(0xFFFFA726)
    val objectiveBand = objectiveColor.copy(alpha = 0.14f)
    val gridColor = Color(0xFF4A5A6A)
    val chartBackground = Color(0xFF1E2836)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(chartBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .pointerInput(profile.buckets.size) {
                detectTapGestures { offset ->
                    val count = profile.buckets.size
                    if (count <= 0 || size.width <= 0f) {
                        return@detectTapGestures
                    }
                    val normalized = (offset.x / size.width).coerceIn(0f, 1f)
                    val index = (normalized * (count - 1).toFloat()).roundToInt().coerceIn(0, count - 1)
                    onBucketTapped(index)
                }
            }
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

        if (showObjective) {
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
        }

        if (showSubjective) {
            drawSegmentedLine(
                values = profile.subjectiveSmoothed,
                xFor = ::xFor,
                yFor = ::yFor,
                color = subjectiveColor,
                dashed = true,
                strokeWidth = 3.5f
            )

            profile.subjectiveSmoothed.forEachIndexed { index, value ->
                if (value != null) {
                    val center = Offset(xFor(index), yFor(value))
                    drawCircle(
                        color = chartBackground,
                        radius = 3.6f,
                        center = center
                    )
                    drawCircle(
                        color = subjectiveColor,
                        radius = 3.6f,
                        center = center,
                        style = Stroke(width = 1.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BucketDetailsDialog(
    profile: DailyTremorProfile,
    bucketIndex: Int,
    onDismiss: () -> Unit
) {
    val bucket = profile.buckets[bucketIndex]
    val displaySubjective = bucket.subjectiveMean

    val perceptionGap = if (bucket.objectiveMedian != null && displaySubjective != null) {
        displaySubjective - bucket.objectiveMedian
    } else {
        null
    }

    val perceptionGapLabel = when {
        perceptionGap == null -> "Perception gap: n/a"
        abs(perceptionGap) <= 0.05 -> "Perception gap: high match"
        perceptionGap > 0.0 -> "Perception gap: self-rating above sensor"
        else -> "Perception gap: sensor above self-rating"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text(formatBucketRange(bucket.startMinuteOfDay, bucket.endMinuteOfDayInclusive))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Sensor typical (p50 when present): ${formatValue(bucket.objectiveMedian, 2)} / 10",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Sensor typical range (p25-p75): ${formatValue(bucket.objectiveQ1, 2)} - ${formatValue(bucket.objectiveQ3, 2)} / 10",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Self rating: ${formatValue(bucket.subjectiveMean?.div(2.0), 2)} / 5 (${formatValue(bucket.subjectiveMean, 2)} / 10)",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Days with data: ${bucket.distinctDaysWithData}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Sensor minutes: ${bucket.objectiveRawSampleCount}  |  Self entries: ${bucket.subjectiveCount}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Overall data quality: ${profile.metrics.confidenceLabel}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = perceptionGapLabel,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
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

private fun resolveChartAxis(
    profile: DailyTremorProfile,
    showObjective: Boolean,
    showSubjective: Boolean
): ChartAxis {
    // Both series are shown on a consistent 0-10 scale:
    // - Objective is a derived Tremor Index in 0-10.
    // - Subjective is rating 0-5 displayed as 0-10 (x2).
    return ChartAxis(
        max = 10.0,
        ticks = listOf(0.0, 5.0, 10.0),
        autoScaled = false
    )
}

private fun computeRobustAxisMax(values: List<Double>): Double {
    if (values.isEmpty()) return 0.2

    val sorted = values.sorted()
    val p90 = percentile(sorted, 0.90)
    val maxValue = sorted.last()

    val target = max(
        p90 * 1.25,
        maxValue * 0.35
    ).coerceIn(0.12, 10.0)

    return snapAxisMax(target)
}

private fun snapAxisMax(value: Double): Double {
    val steps = listOf(0.12, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0)
    return steps.firstOrNull { value <= it } ?: 10.0
}

private fun percentile(sortedValues: List<Double>, p: Double): Double {
    if (sortedValues.isEmpty()) return 0.0
    val index = p.coerceIn(0.0, 1.0) * sortedValues.lastIndex
    val lo = index.toInt()
    val hi = (lo + 1).coerceAtMost(sortedValues.lastIndex)
    val weight = index - lo
    return sortedValues[lo] * (1.0 - weight) + sortedValues[hi] * weight
}

private fun buildAlignmentSummary(correlation: Double?, label: String): String {
    return if (correlation == null) {
        "Pattern alignment: Insufficient"
    } else {
        "Pattern alignment: $label (r=${formatValue(correlation, 2)})"
    }
}

private fun formatBucketRange(startMinute: Int, endMinuteInclusive: Int): String {
    val endExclusive = (endMinuteInclusive + 1).coerceAtMost(1440)
    return "${formatMinute(startMinute)}-${formatMinute(endExclusive)}"
}

private fun formatMinute(minuteOfDay: Int): String {
    val safeMinute = ((minuteOfDay % 1440) + 1440) % 1440
    val hour = safeMinute / 60
    val minute = safeMinute % 60
    return String.format(Locale.US, "%02d:%02d", hour, minute)
}

private fun formatValue(value: Double?, decimals: Int): String {
    if (value == null || !value.isFinite()) return "n/a"
    val pattern = when {
        decimals <= 0 -> "%.0f"
        else -> "%.${decimals}f"
    }
    return String.format(Locale.US, pattern, value)
}
