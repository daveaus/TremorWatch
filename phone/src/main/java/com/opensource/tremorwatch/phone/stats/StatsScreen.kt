package com.opensource.tremorwatch.phone.stats

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opensource.tremorwatch.phone.StatBox
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSeverityProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { StatsRepository(context) }
    val zoneId = remember { ZoneId.systemDefault() }

    val recentDaysToShow = 7

    var history by remember { mutableStateOf<List<DailyStatsResult>>(emptyList()) }
    var medicationResponses by remember { mutableStateOf<List<StatsRepository.MedicationDoseResponseRecord>>(emptyList()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var medicationLoading by remember { mutableStateOf(true) }
    var medicationError by remember { mutableStateOf<String?>(null) }
    val ingestionTimeFormatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }

    // Frequency profile state (date-reactive)
    var frequencyProfile by remember { mutableStateOf<FrequencyProfileResult?>(null) }
    var frequencyLoading by remember { mutableStateOf(false) }
    var frequencyError by remember { mutableStateOf<String?>(null) }

    // Compact severity timeline state (always last 6 hours, date-independent)
    var compactTimeline by remember { mutableStateOf<SeverityTimelineResult?>(null) }
    var compactLoading by remember { mutableStateOf(false) }
    var compactError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        error = null
        history = try {
            repo.computeRecentDailyStats(days = recentDaysToShow, zoneId = zoneId)
        } catch (e: Exception) {
            error = e.message ?: "Failed to compute stats"
            emptyList()
        } finally {
            isLoading = false
        }

        medicationLoading = true
        medicationError = null
        medicationResponses = try {
            repo.computeMedicationResponsesSince(hoursBack = recentDaysToShow * 24)
        } catch (e: Exception) {
            medicationError = e.message ?: "Failed to compute medication responses"
            emptyList()
        } finally {
            medicationLoading = false
        }

        if (selectedDate == null) {
            selectedDate = history.firstOrNull()?.date
        }
    }

    // Load frequency profile when selected date changes
    LaunchedEffect(selectedDate) {
        val date = selectedDate ?: return@LaunchedEffect
        frequencyLoading = true
        frequencyError = null
        frequencyProfile = try {
            repo.computeFrequencyProfile(date, zoneId)
        } catch (e: Exception) {
            frequencyError = e.message ?: "Failed to load frequency data"
            null
        } finally {
            frequencyLoading = false
        }
    }

    // Load compact severity timeline (last 6 hours, independent of date)
    LaunchedEffect(Unit) {
        compactLoading = true
        compactError = null
        compactTimeline = try {
            repo.computeCompactSeverityTimeline(hoursBack = 6, zoneId = zoneId)
        } catch (e: Exception) {
            compactError = e.message ?: "Failed to load timeline"
            null
        } finally {
            compactLoading = false
        }
    }

    val selectedStats = history.firstOrNull { it.date == selectedDate }
        ?: history.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Stats",
                style = MaterialTheme.typography.headlineMedium
            )
            TextButton(onClick = onNavigateBack) {
                Text("Back")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = selectedStats?.date?.toString() ?: "Selected Day",
                    style = MaterialTheme.typography.titleMedium
                )

                when {
                    isLoading -> {
                        Text(
                            text = "Computing stats...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    error != null -> {
                        Text(
                            text = error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    selectedStats == null -> {
                        Text(
                            text = "No data available yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        val load = selectedStats.tremorLoad
                        val boutsStr = load?.boutsPerHour
                            ?.takeIf { it.isFinite() }
                            ?.let { String.format(Locale.US, "%.1f", it) }
                            ?: "--"
                        val tremorHrStr = load?.tremorMinutesPerHour
                            ?.takeIf { it.isFinite() }
                            ?.let { String.format(Locale.US, "%.1f", it) }
                            ?: "--"
                        val wornStr = formatMinutesAsHoursMinutes(load?.wornMinutes ?: 0.0)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatBox(label = "Bouts/hr", value = boutsStr)
                            StatBox(label = "Tremor/hr", value = if (tremorHrStr == "--") "--" else "${tremorHrStr}m")
                            StatBox(label = "Worn", value = wornStr)
                        }

                        load?.message?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val tremorTotal = load?.tremorMinutes ?: 0.0
                        val bouts = load?.totalBouts ?: 0
                        val gaps = selectedStats.stability?.dataGaps ?: 0
                        Text(
                            text = "Tremor: ${formatMinutesAsHoursMinutes(tremorTotal)} total  |  Bouts: $bouts  |  Data gaps: $gaps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Candidate (unfiltered) debug metrics
                        val candTremorHrStr = load?.tremorMinutesPerHourCandidate
                            ?.takeIf { it.isFinite() }
                            ?.let { String.format(Locale.US, "%.1f", it) }
                            ?: "--"
                        val candBoutsStr = load?.boutsPerHourCandidate
                            ?.takeIf { it.isFinite() }
                            ?.let { String.format(Locale.US, "%.1f", it) }
                            ?: "--"
                        Text(
                            text = "Candidate (unfiltered): ${candTremorHrStr}m/hr  |  ${candBoutsStr} bouts/hr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Advanced",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val stability = selectedStats.stability
                        val scoreStr = stability?.score?.toString() ?: "--"
                        val stableStr = formatMinutesAsHoursMinutes(selectedStats.stableMinutes)
                        val eligibleStr = formatMinutesAsHoursMinutes(stability?.eligibleMinutes ?: 0.0)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatBox(label = "Stability", value = scoreStr)
                            StatBox(label = "Stable", value = stableStr)
                            StatBox(label = "Eligible", value = eligibleStr)
                        }

                        stability?.message?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = "Stable threshold: ${String.format(Locale.US, "%.1f", selectedStats.stableThreshold)} / 10",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val baseline = selectedStats.baselineRestSeverity
                        Text(
                            text = if (baseline != null) {
                                "Rest baseline (median): ${String.format(Locale.US, "%.1f", baseline)} / 10"
                            } else {
                                "Rest baseline: not enough data"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ==================== Frequency Profile Card ====================
        FrequencyProfileCard(
            profile = frequencyProfile,
            isLoading = frequencyLoading,
            error = frequencyError
        )

        // ==================== Compact Severity Timeline Card ====================
        CompactSeverityCard(
            timeline = compactTimeline,
            isLoading = compactLoading,
            error = compactError,
            onViewFullTimeline = onNavigateToSeverityProfile
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Medication Response (${recentDaysToShow}d)",
                    style = MaterialTheme.typography.titleMedium
                )

                when {
                    medicationLoading -> {
                        Text(
                            text = "Computing ingestion-anchored responses...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    medicationError != null -> {
                        Text(
                            text = medicationError ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    medicationResponses.isEmpty() -> {
                        Text(
                            text = "No ingestion logs yet. Use \"Taken Now\" on the watch to anchor dose analytics.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        val counts = medicationResponses.groupingBy { it.response.status }.eachCount()
                        val improvedCount = counts["improved"] ?: 0
                        val noChangeCount = counts["no_change"] ?: 0
                        val worseCount = counts["worse"] ?: 0
                        val insufficientCount = counts["insufficient"] ?: 0

                        Text(
                            text = "Improved $improvedCount | No change $noChangeCount | Worse $worseCount | Insufficient $insufficientCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val improved = medicationResponses
                            .filter { it.response.status == "improved" }
                            .map { it.response }
                        val onsetValues = improved.mapNotNull { it.onsetMinutes?.toDouble() }
                        val durationValues = improved.mapNotNull { it.durationMinutes?.toDouble() }
                        val onsetAvg = if (onsetValues.isNotEmpty()) onsetValues.average() else null
                        val durationAvg = if (durationValues.isNotEmpty()) durationValues.average() else null
                        if (onsetAvg != null && durationAvg != null) {
                            Text(
                                text = "Improved-dose averages: onset ${String.format(Locale.US, "%.1f", onsetAvg)} min, duration ${String.format(Locale.US, "%.1f", durationAvg)} min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        medicationResponses.take(5).forEachIndexed { idx, rec ->
                            val ts = Instant.ofEpochMilli(rec.ingestionTimestamp).atZone(zoneId)
                            val status = rec.response.status.replace('_', ' ')
                            val delta = rec.response.deltaPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"
                            val onset = rec.response.onsetMinutes?.let { "${it}m" } ?: "--"
                            val duration = rec.response.durationMinutes?.let { "${it}m" } ?: "--"
                            val conf = String.format(Locale.US, "%.2f", rec.response.confidenceScore)

                            Text(
                                text = "${ingestionTimeFormatter.format(ts)}  ${status.uppercase(Locale.US)}  Δ $delta  onset $onset  duration $duration  conf $conf",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (rec.response.status == "insufficient" && rec.response.reason != null) {
                                Text(
                                    text = "Reason: ${rec.response.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (idx != medicationResponses.take(5).lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Recent Days",
                    style = MaterialTheme.typography.titleMedium
                )

                if (!isLoading && error == null && history.isEmpty()) {
                    Text(
                        text = "No history yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    history.forEachIndexed { idx, day ->
                        val isSelected = day.date == (selectedStats?.date)
                        val load = day.tremorLoad
                        val boutsStr = load?.boutsPerHour
                            ?.takeIf { it.isFinite() }
                            ?.let { String.format(Locale.US, "%.1f", it) }
                            ?: "--"
                        val tremorHrStr = load?.tremorMinutesPerHour
                            ?.takeIf { it.isFinite() }
                            ?.let { String.format(Locale.US, "%.1f", it) }
                            ?: "--"
                        val wornStr = formatMinutesAsHoursMinutes(load?.wornMinutes ?: 0.0)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedDate = day.date }
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = day.date.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Bouts ${boutsStr}/hr  |  Tremor ${if (tremorHrStr == "--") "--" else tremorHrStr + "m"}/hr  |  Worn $wornStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val candHrStr = load?.tremorMinutesPerHourCandidate
                                ?.takeIf { it.isFinite() }
                                ?.let { String.format(Locale.US, "%.1f", it) }
                                ?: "--"
                            Text(
                                text = "Candidate: ${if (candHrStr == "--") "--" else candHrStr + "m"}/hr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        if (idx != history.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "About These Numbers",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Bouts/hr and Tremor/hr are quality-gated: only Confirmed and Probable tremor detections are counted (filtered by confidence, reliability, and exclusion flags). 'Candidate' shows the old unfiltered count for comparison. Stability is computed from eligible samples and may stay near 100 when tremor is sparse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            // Recompute on demand.
                            isLoading = true
                            error = null
                            val newHistory = try {
                                repo.computeRecentDailyStats(days = recentDaysToShow, zoneId = zoneId)
                            } catch (e: Exception) {
                                error = e.message ?: "Failed to compute stats"
                                emptyList()
                            } finally {
                                isLoading = false
                            }

                            history = newHistory
                            selectedDate = selectedDate?.takeIf { sel -> newHistory.any { it.date == sel } }
                                ?: newHistory.firstOrNull()?.date

                            medicationLoading = true
                            medicationError = null
                            medicationResponses = try {
                                repo.computeMedicationResponsesSince(hoursBack = recentDaysToShow * 24)
                            } catch (e: Exception) {
                                medicationError = e.message ?: "Failed to compute medication responses"
                                emptyList()
                            } finally {
                                medicationLoading = false
                            }

                            // Recompute frequency profile & compact severity timeline
                            val recomputeDate = selectedDate ?: Instant.ofEpochMilli(System.currentTimeMillis())
                                .atZone(zoneId).toLocalDate()
                            frequencyLoading = true
                            frequencyError = null
                            frequencyProfile = try {
                                repo.computeFrequencyProfile(recomputeDate, zoneId)
                            } catch (e: Exception) {
                                frequencyError = e.message
                                null
                            } finally {
                                frequencyLoading = false
                            }

                            compactLoading = true
                            compactError = null
                            compactTimeline = try {
                                repo.computeCompactSeverityTimeline(hoursBack = 6, zoneId = zoneId)
                            } catch (e: Exception) {
                                compactError = e.message
                                null
                            } finally {
                                compactLoading = false
                            }
                        }
                    }
                ) {
                    Text("Recompute")
                }
            }
        }
    }
}

// ==================== Frequency Profile Card ====================

@Composable
private fun FrequencyProfileCard(
    profile: FrequencyProfileResult?,
    isLoading: Boolean,
    error: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Frequency Profile",
                style = MaterialTheme.typography.titleMedium
            )

            when {
                isLoading -> {
                    Text(
                        text = "Loading frequency data...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                error != null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                profile == null || profile.entries.isEmpty() -> {
                    Text(
                        text = profile?.message ?: "No frequency data available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    // Header row
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Frequency", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Text("Count", Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall)
                        Text("Pct", Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall)
                        Text("Classification", Modifier.weight(1.5f), style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    profile.entries.take(10).forEachIndexed { idx, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                String.format(Locale.US, "%.2f Hz", entry.frequencyHz),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "%,d".format(entry.count),
                                Modifier.weight(0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                String.format(Locale.US, "%.1f%%", entry.percentage),
                                Modifier.weight(0.5f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                entry.classification,
                                Modifier.weight(1.5f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (idx != profile.entries.take(10).lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Total tremor samples: %,d".format(profile.totalTremorSamples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ==================== Compact Severity Card ====================

@Composable
private fun CompactSeverityCard(
    timeline: SeverityTimelineResult?,
    isLoading: Boolean,
    error: String?,
    onViewFullTimeline: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Severity (Last 6h)",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onViewFullTimeline) {
                    Text("Full Timeline")
                }
            }

            when {
                isLoading -> {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                error != null -> {
                    Text(text = error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                timeline == null || timeline.buckets.isEmpty() -> {
                    Text(
                        text = timeline?.message ?: "No recent data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    // Column headers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Time", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                        Text("Sev", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(30.dp))
                        Text("Tremor", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(55.dp))
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    val maxSeverity = timeline.buckets.maxOfOrNull { it.avgSeverity }
                        ?.coerceAtLeast(1.0) ?: 10.0

                    timeline.buckets.forEach { bucket ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(bucket.timeLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                            Text(
                                String.format(Locale.US, "%.1f", bucket.avgSeverity),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(30.dp)
                            )
                            Text(
                                "${bucket.tremorCount}/${bucket.totalCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(55.dp)
                            )
                            val fraction = (bucket.avgSeverity / maxSeverity)
                                .coerceIn(0.0, 1.0).toFloat()
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(severityColor(bucket.avgSeverity))
                                )
                            }
                        }
                    }

                    // Peak/trough summary
                    timeline.peakBucket?.let { peak ->
                        Text(
                            text = "${peak.timeLabel} Peak: ${String.format(Locale.US, "%.1f", peak.avgSeverity)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun severityColor(severity: Double): Color {
    return when {
        severity >= 7.0 -> MaterialTheme.colorScheme.error
        severity >= 4.0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
}
