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
import com.opensource.tremorwatch.phone.database.TremorDatabaseHelper
import com.opensource.tremorwatch.phone.database.TremorSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

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

            val dbHelper = TremorDatabaseHelper(context)
            val cutoffTime = if (hoursBack == Int.MAX_VALUE) 0L else {
                System.currentTimeMillis() - (hoursBack.toLong() * 60 * 60 * 1000)
            }
            
            // Check count first without loading data into memory
            val totalCount = dbHelper.getSamplesCountAfter(cutoffTime)
            if (totalCount == 0) {
                return@withContext "No data available for export"
            }

            // Create export file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "tremorwatch_${format.lowercase().replace(" ", "_")}_${timestamp}.csv"
            val exportFile = File(context.cacheDir, fileName)

            // Stream data in chunks to avoid OOM
            val recordCount = FileWriter(exportFile).use { writer ->
                when (format) {
                    "Summary" -> writeStreamingSummaryCsv(writer, dbHelper, cutoffTime)
                    "Detailed" -> writeStreamingDetailedCsv(writer, dbHelper, cutoffTime)
                    "Raw Data" -> writeStreamingRawDataCsv(writer, dbHelper, cutoffTime)
                    else -> writeStreamingDetailedCsv(writer, dbHelper, cutoffTime)
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

            "Success! Exported $recordCount records"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

/**
 * Write summary CSV (aggregated by hour)
 */
private fun writeSummaryCsv(writer: FileWriter, samples: List<TremorSample>): Int {
    // Write experimental disclaimer
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    writer.write("Hour,Avg Severity,Max Severity,Tremor Events,Duration Minutes,")
    writer.write("Activity Type,Avg Activity Confidence,Avg Adjusted Severity,Avg Adjusted Confidence,")
    writer.write("Reliable %,Exclude %\n")

    // Group by hour
    val hourMs = 60 * 60 * 1000L
    val grouped = samples.groupBy { it.timestamp / hourMs }
    grouped.entries.sortedBy { it.key }.forEach { (hour, records) ->
        val avgSeverity = records.map { it.severity }.average()
        val maxSeverity = records.maxOfOrNull { it.severity } ?: 0.0
        val tremorEvents = records.sumOf { it.tremorCount }
        val durationMinutes = records.map { it.timestamp / 60000L }.distinct().size
        val activitySummary = summarizeActivity(records)

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:00", Locale.US)
            .format(Date(hour * hourMs))

        val avgActivityConfidence = activitySummary.avgConfidence?.let { String.format("%.3f", it) } ?: ""
        val avgAdjustedSeverity = activitySummary.avgAdjustedSeverity?.let { String.format("%.4f", it) } ?: ""
        val avgAdjustedConfidence = activitySummary.avgAdjustedConfidence?.let { String.format("%.4f", it) } ?: ""
        val reliablePct = activitySummary.reliablePct?.let { String.format("%.1f", it) } ?: ""
        val excludePct = activitySummary.excludePct?.let { String.format("%.1f", it) } ?: ""

        writer.write(
            "$dateStr,${String.format("%.4f", avgSeverity)},${String.format("%.4f", maxSeverity)}," +
                "$tremorEvents,$durationMinutes," +
                "${activitySummary.dominantType},$avgActivityConfidence," +
                "$avgAdjustedSeverity,$avgAdjustedConfidence,$reliablePct,$excludePct\n"
        )
    }
    return grouped.size
}

/**
 * Write detailed CSV with all data points
 */
private fun writeDetailedCsv(writer: FileWriter, samples: List<TremorSample>): Int {
    // Write experimental disclaimer
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    writer.write("Timestamp,DateTime,Severity,Tremor Count,")
    writer.write("Activity Type,Activity Confidence,Activity Age Ms,")
    writer.write("Adjusted Severity,Adjusted Confidence,Is Reliable,Exclude From Analysis\n")

    samples.sortedBy { it.timestamp }.forEach { record ->
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(record.timestamp))

        val metadata = parseMetadata(record.metadataJson)
        writer.write("${record.timestamp},$dateStr,${String.format("%.6f", record.severity)},${record.tremorCount},")
        writer.write("${metaValue(metadata, "activityType")},${metaValue(metadata, "activityConfidence")},")
        writer.write("${metaValue(metadata, "activityAgeMs")},")
        writer.write("${metaValue(metadata, "activityAdjustedSeverity")},${metaValue(metadata, "activityAdjustedConfidence")},")
        writer.write("${metaValue(metadata, "isReliableMeasurement")},${metaValue(metadata, "excludeFromAnalysis")}\n")
    }
    return samples.size
}

/**
 * Write raw data CSV with all available fields from database
 */
private fun writeRawDataCsv(writer: FileWriter, samples: List<TremorSample>): Int {
    // Write experimental disclaimer
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    // Write header with all possible fields
    writer.write("Timestamp,DateTime,Severity,Tremor Count,")
    writer.write("X,Y,Z,Magnitude,Accel Magnitude,Confidence,")
    writer.write("Is Worn,Is Charging,Dominant Freq,Tremor Band Power,")
    writer.write("Total Power,Band Ratio,Peak Prominence,Watch ID,")
    writer.write("Activity Type,Activity Confidence,Activity Age Ms,")
    writer.write("Adjusted Severity,Adjusted Confidence,Is Reliable,Exclude From Analysis\n")

    samples.sortedBy { it.timestamp }.forEach { sample ->
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(sample.timestamp))

        val metadata = parseMetadata(sample.metadataJson)

        writer.write("${sample.timestamp},$dateStr,")
        writer.write("${String.format("%.6f", sample.severity)},${sample.tremorCount},")
        writer.write("${sample.x ?: ""},${sample.y ?: ""},${sample.z ?: ""},")
        writer.write("${sample.magnitude ?: ""},${sample.accelMagnitude ?: ""},${sample.confidence ?: ""},")
        writer.write("${sample.isWorn ?: ""},${sample.isCharging ?: ""},")
        writer.write("${sample.dominantFrequency ?: ""},${sample.tremorBandPower ?: ""},")
        writer.write("${sample.totalPower ?: ""},${sample.bandRatio ?: ""},${sample.peakProminence ?: ""},")
        writer.write("${sample.watchId ?: ""},")
        writer.write("${metaValue(metadata, "activityType")},${metaValue(metadata, "activityConfidence")},${metaValue(metadata, "activityAgeMs")},")
        writer.write("${metaValue(metadata, "activityAdjustedSeverity")},${metaValue(metadata, "activityAdjustedConfidence")},")
        writer.write("${metaValue(metadata, "isReliableMeasurement")},${metaValue(metadata, "excludeFromAnalysis")}\n")
    }
    return samples.size
}

private data class ActivitySummary(
    val dominantType: String,
    val avgConfidence: Double?,
    val avgAdjustedSeverity: Double?,
    val avgAdjustedConfidence: Double?,
    val reliablePct: Double?,
    val excludePct: Double?
)

private fun summarizeActivity(records: List<TremorSample>): ActivitySummary {
    val activityCounts = mutableMapOf<String, Int>()
    var confidenceSum = 0.0
    var confidenceCount = 0
    var adjustedSeveritySum = 0.0
    var adjustedSeverityCount = 0
    var adjustedConfidenceSum = 0.0
    var adjustedConfidenceCount = 0
    var reliableCount = 0
    var reliableSamples = 0
    var excludeCount = 0
    var excludeSamples = 0

    records.forEach { sample ->
        val metadata = parseMetadata(sample.metadataJson)
        val activityType = metaValue(metadata, "activityType")
        if (activityType.isNotBlank()) {
            activityCounts[activityType] = (activityCounts[activityType] ?: 0) + 1
        }

        optDouble(metadata, "activityConfidence")?.let {
            confidenceSum += it
            confidenceCount++
        }
        optDouble(metadata, "activityAdjustedSeverity")?.let {
            adjustedSeveritySum += it
            adjustedSeverityCount++
        }
        optDouble(metadata, "activityAdjustedConfidence")?.let {
            adjustedConfidenceSum += it
            adjustedConfidenceCount++
        }
        optBoolean(metadata, "isReliableMeasurement")?.let { reliable ->
            reliableSamples++
            if (reliable) reliableCount++
        }
        optBoolean(metadata, "excludeFromAnalysis")?.let { exclude ->
            excludeSamples++
            if (exclude) excludeCount++
        }
    }

    val dominantType = activityCounts.maxByOrNull { it.value }?.key ?: ""
    val avgConfidence = if (confidenceCount > 0) confidenceSum / confidenceCount else null
    val avgAdjustedSeverity = if (adjustedSeverityCount > 0) adjustedSeveritySum / adjustedSeverityCount else null
    val avgAdjustedConfidence = if (adjustedConfidenceCount > 0) adjustedConfidenceSum / adjustedConfidenceCount else null
    val reliablePct = if (reliableSamples > 0) (reliableCount.toDouble() / reliableSamples) * 100.0 else null
    val excludePct = if (excludeSamples > 0) (excludeCount.toDouble() / excludeSamples) * 100.0 else null

    return ActivitySummary(
        dominantType = dominantType,
        avgConfidence = avgConfidence,
        avgAdjustedSeverity = avgAdjustedSeverity,
        avgAdjustedConfidence = avgAdjustedConfidence,
        reliablePct = reliablePct,
        excludePct = excludePct
    )
}

private fun parseMetadata(metadataJson: String?): JSONObject? {
    return metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }
}

private fun metaValue(metadata: JSONObject?, key: String): String {
    if (metadata == null || !metadata.has(key) || metadata.isNull(key)) {
        return ""
    }
    return metadata.opt(key)?.toString() ?: ""
}

private fun optDouble(metadata: JSONObject?, key: String): Double? {
    if (metadata == null || !metadata.has(key) || metadata.isNull(key)) {
        return null
    }
    val raw = metadata.opt(key)
    return when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

private fun optBoolean(metadata: JSONObject?, key: String): Boolean? {
    if (metadata == null || !metadata.has(key) || metadata.isNull(key)) {
        return null
    }
    val raw = metadata.opt(key)
    return when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> raw.equals("true", ignoreCase = true)
        else -> null
    }
}

// ============== STREAMING EXPORT FUNCTIONS (memory-efficient) ==============

private const val CHUNK_SIZE = 5000

/**
 * Write detailed CSV with streaming - fetches data in chunks to avoid OOM.
 */
private suspend fun writeStreamingDetailedCsv(
    writer: FileWriter, 
    dbHelper: TremorDatabaseHelper, 
    cutoffTime: Long
): Int {
    // Write header
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    writer.write("Timestamp,DateTime,Severity,Tremor Count,")
    writer.write("Activity Type,Activity Confidence,Activity Age Ms,")
    writer.write("Adjusted Severity,Adjusted Confidence,Is Reliable,Exclude From Analysis\n")

    var offset = 0
    var totalRecords = 0
    
    while (true) {
        val chunk = dbHelper.getSamplesAfterPaged(cutoffTime, CHUNK_SIZE, offset)
        if (chunk.isEmpty()) break
        
        chunk.sortedBy { it.timestamp }.forEach { record ->
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date(record.timestamp))

            val metadata = parseMetadata(record.metadataJson)
            writer.write("${record.timestamp},$dateStr,${String.format("%.6f", record.severity)},${record.tremorCount},")
            writer.write("${metaValue(metadata, "activityType")},${metaValue(metadata, "activityConfidence")},")
            writer.write("${metaValue(metadata, "activityAgeMs")},")
            writer.write("${metaValue(metadata, "activityAdjustedSeverity")},${metaValue(metadata, "activityAdjustedConfidence")},")
            writer.write("${metaValue(metadata, "isReliableMeasurement")},${metaValue(metadata, "excludeFromAnalysis")}\n")
        }
        
        totalRecords += chunk.size
        offset += CHUNK_SIZE
        
        // Flush periodically to free memory
        if (offset % (CHUNK_SIZE * 5) == 0) {
            writer.flush()
        }
    }
    
    return totalRecords
}

/**
 * Write raw data CSV with streaming - fetches data in chunks to avoid OOM.
 */
private suspend fun writeStreamingRawDataCsv(
    writer: FileWriter, 
    dbHelper: TremorDatabaseHelper, 
    cutoffTime: Long
): Int {
    // Write header
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    writer.write("Timestamp,DateTime,Severity,Tremor Count,")
    writer.write("X,Y,Z,Magnitude,Accel Magnitude,Confidence,")
    writer.write("Is Worn,Is Charging,Dominant Freq,Tremor Band Power,")
    writer.write("Total Power,Band Ratio,Peak Prominence,Watch ID,")
    writer.write("Activity Type,Activity Confidence,Activity Age Ms,")
    writer.write("Adjusted Severity,Adjusted Confidence,Is Reliable,Exclude From Analysis\n")

    var offset = 0
    var totalRecords = 0
    
    while (true) {
        val chunk = dbHelper.getSamplesAfterPaged(cutoffTime, CHUNK_SIZE, offset)
        if (chunk.isEmpty()) break
        
        chunk.sortedBy { it.timestamp }.forEach { sample ->
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date(sample.timestamp))

            val metadata = parseMetadata(sample.metadataJson)

            writer.write("${sample.timestamp},$dateStr,")
            writer.write("${String.format("%.6f", sample.severity)},${sample.tremorCount},")
            writer.write("${sample.x ?: ""},${sample.y ?: ""},${sample.z ?: ""},")
            writer.write("${sample.magnitude ?: ""},${sample.accelMagnitude ?: ""},${sample.confidence ?: ""},")
            writer.write("${sample.isWorn ?: ""},${sample.isCharging ?: ""},")
            writer.write("${sample.dominantFrequency ?: ""},${sample.tremorBandPower ?: ""},")
            writer.write("${sample.totalPower ?: ""},${sample.bandRatio ?: ""},${sample.peakProminence ?: ""},")
            writer.write("${sample.watchId ?: ""},")
            writer.write("${metaValue(metadata, "activityType")},${metaValue(metadata, "activityConfidence")},${metaValue(metadata, "activityAgeMs")},")
            writer.write("${metaValue(metadata, "activityAdjustedSeverity")},${metaValue(metadata, "activityAdjustedConfidence")},")
            writer.write("${metaValue(metadata, "isReliableMeasurement")},${metaValue(metadata, "excludeFromAnalysis")}\n")
        }
        
        totalRecords += chunk.size
        offset += CHUNK_SIZE
        
        if (offset % (CHUNK_SIZE * 5) == 0) {
            writer.flush()
        }
    }
    
    return totalRecords
}

/**
 * Write summary CSV with streaming - aggregates by hour.
 * Note: This still needs to collect hour buckets in memory, but only stores aggregated data
 * which is much smaller than raw samples.
 */
private suspend fun writeStreamingSummaryCsv(
    writer: FileWriter, 
    dbHelper: TremorDatabaseHelper, 
    cutoffTime: Long
): Int {
    // Write header
    writer.write("# EXPERIMENTAL DATA - NOT FOR MEDICAL USE\n")
    writer.write("# This data is from experimental software and should not be used for diagnosis or treatment\n")
    writer.write("#\n")
    writer.write("Hour,Avg Severity,Max Severity,Tremor Events,Duration Minutes,")
    writer.write("Activity Type,Avg Activity Confidence,Avg Adjusted Severity,Avg Adjusted Confidence,")
    writer.write("Reliable %,Exclude %\n")

    // Collect hourly aggregates in memory (much smaller than raw samples)
    val hourMs = 60 * 60 * 1000L
    val hourlyData = mutableMapOf<Long, MutableList<TremorSample>>()
    
    var offset = 0
    
    while (true) {
        val chunk = dbHelper.getSamplesAfterPaged(cutoffTime, CHUNK_SIZE, offset)
        if (chunk.isEmpty()) break
        
        chunk.forEach { sample ->
            val hourKey = sample.timestamp / hourMs
            hourlyData.getOrPut(hourKey) { mutableListOf() }.add(sample)
        }
        
        offset += CHUNK_SIZE
    }

    // Write aggregated data
    hourlyData.entries.sortedBy { it.key }.forEach { (hour, records) ->
        val avgSeverity = records.map { it.severity }.average()
        val maxSeverity = records.maxOfOrNull { it.severity } ?: 0.0
        val tremorEvents = records.sumOf { it.tremorCount }
        val durationMinutes = records.map { it.timestamp / 60000L }.distinct().size
        val activitySummary = summarizeActivity(records)

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:00", Locale.US)
            .format(Date(hour * hourMs))

        val avgActivityConfidence = activitySummary.avgConfidence?.let { String.format("%.3f", it) } ?: ""
        val avgAdjustedSeverity = activitySummary.avgAdjustedSeverity?.let { String.format("%.4f", it) } ?: ""
        val avgAdjustedConfidence = activitySummary.avgAdjustedConfidence?.let { String.format("%.4f", it) } ?: ""
        val reliablePct = activitySummary.reliablePct?.let { String.format("%.1f", it) } ?: ""
        val excludePct = activitySummary.excludePct?.let { String.format("%.1f", it) } ?: ""

        writer.write(
            "$dateStr,${String.format("%.4f", avgSeverity)},${String.format("%.4f", maxSeverity)}," +
                "$tremorEvents,$durationMinutes," +
                "${activitySummary.dominantType},$avgActivityConfidence," +
                "$avgAdjustedSeverity,$avgAdjustedConfidence,$reliablePct,$excludePct\n"
        )
    }
    
    return hourlyData.size
}


