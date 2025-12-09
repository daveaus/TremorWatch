package com.opensource.tremorwatch.phone.ui.dialogs

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import android.content.Intent
import com.opensource.tremorwatch.phone.loadLocalData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.opensource.tremorwatch.phone.ChartData
import com.opensource.tremorwatch.shared.models.TremorBatch

/**
 * Export Dialog - allows user to select time range and export format
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDialog(
    onDismiss: () -> Unit,
    context: Context
) {
    var selectedTimeRange by remember { mutableStateOf("24h") }
    var selectedFormat by remember { mutableStateOf("Summary") }
    var isExporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val timeRanges = listOf("1h", "6h", "12h", "24h", "48h", "7d", "30d", "All")
    val formats = listOf("Summary", "Detailed", "Raw Data")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Tremor Data") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Time Range Selection
                Text(
                    text = "Select Time Range:",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                var expandedTimeRange by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expandedTimeRange,
                    onExpandedChange = { expandedTimeRange = it }
                ) {
                    OutlinedTextField(
                        value = selectedTimeRange,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time Range") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTimeRange) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedTimeRange,
                        onDismissRequest = { expandedTimeRange = false }
                    ) {
                        timeRanges.forEach { range ->
                            DropdownMenuItem(
                                text = { Text(range) },
                                onClick = {
                                    selectedTimeRange = range
                                    expandedTimeRange = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Format Selection
                Text(
                    text = "Select Format:",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                formats.forEach { format ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(format)
                            Text(
                                text = when (format) {
                                    "Summary" -> "Aggregated by hour, includes stats"
                                    "Detailed" -> "All data points with metadata"
                                    "Raw Data" -> "Complete sensor data dump"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (exportStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = exportStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exportStatus.contains("Success"))
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isExporting = true
                        exportStatus = "Exporting..."
                        val result = exportData(context, selectedTimeRange, selectedFormat)
                        exportStatus = result
                        isExporting = false
                    }
                },
                enabled = !isExporting
            ) {
                Text(if (isExporting) "Exporting..." else "Export & Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Export data to CSV and share via Android share sheet
 */
suspend fun exportData(context: Context, timeRange: String, format: String): String {
    return withContext(Dispatchers.IO) {
        try {
            // Calculate time range in hours
            val hoursBack = when (timeRange) {
                "1h" -> 1
                "6h" -> 6
                "12h" -> 12
                "24h" -> 24
                "48h" -> 48
                "7d" -> 168
                "30d" -> 720
                "All" -> Int.MAX_VALUE
                else -> 24
            }

            // Load data
            val data = loadLocalData(context, hoursBack)

            if (data.isEmpty()) {
                return@withContext "No data available for export"
            }

            // Create export file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "tremorwatch_${format.lowercase().replace(" ", "_")}_${timestamp}.csv"
            val exportFile = File(context.cacheDir, fileName)

            // Write CSV
            FileWriter(exportFile).use { writer ->
                when (format) {
                    "Summary" -> writeSummaryCsv(writer, data)
                    "Detailed" -> writeDetailedCsv(writer, data)
                    "Raw Data" -> writeRawDataCsv(writer, context, hoursBack)
                    else -> writeDetailedCsv(writer, data)
                }
            }

            // Share file
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                exportFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(shareIntent, "Share Tremor Data"))
            }

            "Success! Exported ${data.size} records"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

/**
 * Write summary CSV (aggregated by hour)
 */
private fun writeSummaryCsv(writer: FileWriter, data: List<ChartData>) {
    // Write experimental disclaimer
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    writer.write("Hour,Avg Severity,Max Severity,Tremor Events,Duration Minutes\n")

    // Group by hour
    val grouped = data.groupBy { it.timestamp / (60 * 60 * 1000) }
    grouped.entries.sortedBy { it.key }.forEach { (hour, records) ->
        val avgSeverity = records.map { it.severity }.average()
        val maxSeverity = records.maxOfOrNull { it.severity } ?: 0.0
        val tremorEvents = records.sumOf { it.tremorCount }
        val durationMinutes = records.size

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:00", Locale.US)
            .format(Date(hour * 60 * 60 * 1000))

        writer.write("$dateStr,${String.format("%.4f", avgSeverity)},${String.format("%.4f", maxSeverity)},$tremorEvents,$durationMinutes\n")
    }
}

/**
 * Write detailed CSV with all data points
 */
private fun writeDetailedCsv(writer: FileWriter, data: List<ChartData>) {
    // Write experimental disclaimer
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    writer.write("Timestamp,DateTime,Severity,Tremor Count\n")

    data.sortedBy { it.timestamp }.forEach { record ->
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(record.timestamp))
        writer.write("${record.timestamp},$dateStr,${String.format("%.6f", record.severity)},${record.tremorCount}\n")
    }
}

/**
 * Write raw data CSV with all available fields from stored batches
 */
private fun writeRawDataCsv(writer: FileWriter, context: Context, hoursBack: Int) {
    // Write experimental disclaimer
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    // Write header with all possible fields
    writer.write("Timestamp,DateTime,Severity,Tremor Count,")
    writer.write("X,Y,Z,Magnitude,Confidence,")
    writer.write("Is Worn,Is Charging,Dominant Freq,Tremor Band Power,")
    writer.write("Tremor Type,Tremor Type Confidence,Is Resting State\n")

    val storageFile = File(context.filesDir, "consolidated_tremor_data.jsonl")
    if (!storageFile.exists()) return

    val cutoffTime = if (hoursBack == Int.MAX_VALUE) 0L else System.currentTimeMillis() - (hoursBack.toLong() * 60 * 60 * 1000)

    storageFile.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            try {
                val batch = TremorBatch.fromJsonString(line)
                batch.samples
                    .filter { it.timestamp >= cutoffTime }
                    .forEach { sample ->
                        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                            .format(Date(sample.timestamp))
                        val metadata = sample.metadata

                        writer.write("${sample.timestamp},$dateStr,")
                        writer.write("${String.format("%.6f", sample.severity)},${sample.tremorCount},")
                        writer.write("${metadata["x"] ?: ""},${metadata["y"] ?: ""},${metadata["z"] ?: ""},")
                        writer.write("${metadata["magnitude"] ?: ""},${metadata["confidence"] ?: ""},")
                        writer.write("${metadata["isWorn"] ?: ""},${metadata["isCharging"] ?: ""},")
                        writer.write("${metadata["dominantFrequency"] ?: ""},${metadata["tremorBandPower"] ?: ""},")
                        writer.write("${metadata["tremorType"] ?: ""},${metadata["tremorTypeConfidence"] ?: ""},")
                        writer.write("${metadata["isRestingState"] ?: ""}\n")
                    }
            } catch (e: Exception) {
                // Skip invalid lines
            }
        }
    }
}
