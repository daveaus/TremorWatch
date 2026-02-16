package com.opensource.tremorwatch.phone.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opensource.tremorwatch.phone.StatBox
import java.util.Locale

@Composable
fun QuickStatsCard(
    stats: DailyStatsResult?,
    isLoading: Boolean,
    statusText: String?,
    statusTextIsError: Boolean = false,
    onOpenStats: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenStats() },
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
                    text = "Today's Stats",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onOpenStats) {
                    Text("View")
                }
            }

            when {
                isLoading -> {
                    Text(
                        text = "Computing stats...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                statusText != null -> {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusTextIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                stats == null -> {
                    Text(
                        text = "No data yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    val load = stats.tremorLoad
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
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
