package com.opensource.tremorwatch.phone.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeverityProfileScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember(context) { StatsRepository(context) }
    val zoneId = remember { ZoneId.systemDefault() }
    val today = remember { Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zoneId).toLocalDate() }

    var selectedDate by remember { mutableStateOf(today) }
    val granularityOptions = TimelineGranularity.entries.toList()
    var selectedGranularityIndex by remember { mutableIntStateOf(2) } // Default to ONE_HOUR
    val granularity = granularityOptions[selectedGranularityIndex]

    var timeline by remember { mutableStateOf<SeverityTimelineResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Tooltip state
    var showTooltip by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedDate, granularity) {
        isLoading = true
        error = null
        timeline = try {
            repo.computeSeverityTimelineForDate(selectedDate, granularity, zoneId)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load timeline"
            null
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Severity Profile",
                style = MaterialTheme.typography.headlineMedium
            )
            TextButton(onClick = onNavigateBack) {
                Text("Back")
            }
        }

        // Date navigation
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                    Text("<", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    text = selectedDate.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                IconButton(
                    onClick = { selectedDate = selectedDate.plusDays(1) },
                    enabled = selectedDate < today
                ) {
                    Text(
                        ">",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (selectedDate < today)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // Granularity selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            granularityOptions.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = granularityOptions.size
                    ),
                    onClick = { selectedGranularityIndex = index },
                    selected = index == selectedGranularityIndex
                ) {
                    Text(option.label)
                }
            }
        }

        // Timeline card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when {
                    isLoading -> {
                        Text(
                            text = "Loading...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    error != null -> {
                        Text(text = error ?: "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    timeline == null || timeline!!.buckets.isEmpty() -> {
                        Text(
                            text = timeline?.message ?: "No data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        val tl = timeline!!

                        // Column headers — tappable for tooltips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Time",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .width(40.dp)
                                    .clickable {
                                        showTooltip = if (showTooltip == "time") null else "time"
                                    }
                            )
                            Text(
                                "Sev",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .width(30.dp)
                                    .clickable {
                                        showTooltip = if (showTooltip == "sev") null else "sev"
                                    }
                            )
                            Text(
                                "Tremor",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .width(75.dp)
                                    .clickable {
                                        showTooltip = if (showTooltip == "tremor") null else "tremor"
                                    }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // Tooltip expansion
                        AnimatedVisibility(visible = showTooltip != null) {
                            val tooltipText = when (showTooltip) {
                                "time" -> "Start of ${granularity.label} window"
                                "sev" -> "Average severity (0-10 scale)"
                                "tremor" -> "Tremor detections / total samples"
                                else -> ""
                            }
                            Text(
                                text = tooltipText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        val maxSeverity = tl.buckets.maxOfOrNull { it.avgSeverity }
                            ?.coerceAtLeast(1.0) ?: 10.0

                        tl.buckets.forEach { bucket ->
                            val annotation = when (bucket) {
                                tl.peakBucket -> "PEAK"
                                tl.troughBucket -> "LOW"
                                else -> null
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    bucket.timeLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(40.dp)
                                )
                                Text(
                                    String.format(Locale.US, "%.1f", bucket.avgSeverity),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(30.dp)
                                )
                                Text(
                                    "${bucket.tremorCount}/${bucket.totalCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(75.dp)
                                )
                                // Visual bar with PEAK/LOW inside
                                val fraction = (bucket.avgSeverity / maxSeverity)
                                    .coerceIn(0.0, 1.0).toFloat()
                                Box(
                                    modifier = Modifier
                                        .weight(0.8f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fraction)
                                            .fillMaxHeight()
                                            .background(severityColor(bucket.avgSeverity))
                                    )
                                    if (annotation != null) {
                                        Text(
                                            text = annotation,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (annotation == "PEAK") Color.White
                                                else MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier
                                                .align(Alignment.CenterStart)
                                                .padding(start = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Day Arc Summary
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Day Arc Summary",
                            style = MaterialTheme.typography.titleSmall
                        )

                        tl.peakBucket?.let { peak ->
                            Text(
                                text = "${peak.timeLabel}  Peak: ${String.format(Locale.US, "%.1f", peak.avgSeverity)} avg (${String.format(Locale.US, "%.1f", peak.minSeverity)}-${String.format(Locale.US, "%.1f", peak.maxSeverity)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        tl.troughBucket?.let { trough ->
                            Text(
                                text = "${trough.timeLabel}  Trough: ${String.format(Locale.US, "%.1f", trough.avgSeverity)} avg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
}
