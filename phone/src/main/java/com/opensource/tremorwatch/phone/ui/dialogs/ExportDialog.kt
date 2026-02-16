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
import com.opensource.tremorwatch.phone.database.TremorRoomDatabase
import com.opensource.tremorwatch.phone.database.TremorSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    val formats = listOf("Summary", "Detailed", "Raw Data", "Subjective Ratings")

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
                                    "Summary" -> "Daily clinician-friendly summary"
                                    "Detailed" -> "All data points with metadata"
                                    "Raw Data" -> "Complete sensor data dump"
                                    "Subjective Ratings" -> "User ratings with calibration data"
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
                
                // EMERGENCY IMPORT BUTTON (Hidden feature for recovery)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            exportStatus = "Importing data..."
                            val result = importRecoveryData(context)
                            exportStatus = result
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import Recovery Data (CSV)")
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

private const val EXPORT_SCHEMA_VERSION = 2
private const val NULL_TOKEN = "NA"
private const val RELIABILITY_THRESHOLD = 0.40

private data class ExportContext(
    val exportId: String,
    val formatName: String,
    val requestedTimeRange: String,
    val generatedAtMs: Long,
    val cutoffTime: Long,
    val localTimezoneId: String,
    val localTimezoneOffsetMinutes: Int,
    val watchAliases: MutableMap<String, String> = mutableMapOf()
)

private data class InferredMetrics(
    val severity: Double?,
    val confidence: Double?,
    val frequencyHz: Double?
)

/**
 * Export data to CSV and share via Android share sheet
 */
suspend fun exportData(context: Context, timeRange: String, format: String): String {
    return withContext(Dispatchers.IO) {
        try {
            val nowMs = System.currentTimeMillis()

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
                nowMs - (hoursBack.toLong() * 60 * 60 * 1000)
            }

            // Create export file
            val exportTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
            val timeZone = TimeZone.getDefault()
            val exportContext = ExportContext(
                exportId = exportTimestamp,
                formatName = format,
                requestedTimeRange = timeRange,
                generatedAtMs = nowMs,
                cutoffTime = cutoffTime,
                localTimezoneId = timeZone.id,
                localTimezoneOffsetMinutes = timeZone.getOffset(nowMs) / 60000
            )

            val fileName = "tremorwatch_${format.lowercase().replace(" ", "_")}_${exportTimestamp}.csv"
            val exportFile = File(context.cacheDir, fileName)

            // Stream data in chunks to avoid OOM
            val recordCount = FileWriter(exportFile).use { writer ->
                when (format) {
                    "Summary" -> writeStreamingSummaryCsv(writer, dbHelper, cutoffTime, exportContext)
                    "Detailed" -> writeStreamingDetailedCsv(writer, dbHelper, cutoffTime, exportContext)
                    "Raw Data" -> writeStreamingRawDataCsv(writer, dbHelper, cutoffTime, exportContext)
                    "Subjective Ratings" -> writeSubjectiveRatingsCsv(writer, context, cutoffTime, exportContext)
                    else -> writeStreamingDetailedCsv(writer, dbHelper, cutoffTime, exportContext)
                }
            }

            if (recordCount == 0) {
                exportFile.delete()
                return@withContext "No data available for export"
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

/**
 * Compute confidence-weighted severity for post-hoc analysis (opus46 Issue 7).
 * Downweights low-confidence and high-frequency artifact-prone samples.
 * Applied at export time to ALL samples (including historical ones without new metadata).
 */
private fun confidenceWeightedSeverity(
    severity: Double,
    confidence: Float,
    dominantFrequency: Float,
    accelMagnitude: Float
): Double {
    var weight = 1.0

    // Penalize low confidence
    if (confidence < 0.3f) {
        weight *= (confidence / 0.3).toDouble()  // Linear ramp from 0 to 1
    }

    // Penalize frequencies outside physiological tremor range
    if (dominantFrequency > 9.0f) {
        weight *= 0.7
    }

    // Penalize high acceleration (likely voluntary movement)
    if (accelMagnitude > 15.0f) {
        weight *= 0.5
    }

    // Bonus for classic resting tremor frequency range (4-7 Hz)
    if (dominantFrequency in 4.0f..7.0f) {
        weight *= 1.1
    }

    return (severity * weight).coerceAtMost(10.0)
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

private fun formatOffsetMinutes(offsetMinutes: Int): String {
    val sign = if (offsetMinutes >= 0) "+" else "-"
    val absMinutes = kotlin.math.abs(offsetMinutes)
    val hours = absMinutes / 60
    val minutes = absMinutes % 60
    return String.format(Locale.US, "%s%02d:%02d", sign, hours, minutes)
}

private fun isoUtc(timestamp: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(timestamp))
}

private fun isoLocal(timestamp: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    format.timeZone = TimeZone.getDefault()
    return format.format(Date(timestamp))
}

private fun writeCommonExportHeader(
    writer: FileWriter,
    exportContext: ExportContext,
    extraLines: List<String>
) {
    writer.write("# TremorWatch Export\n")
    writer.write("# Format: ${exportContext.formatName}\n")
    writer.write("# SchemaVersion: $EXPORT_SCHEMA_VERSION\n")
    writer.write("# ExportId: ${exportContext.exportId}\n")
    writer.write("# GeneratedAtUTC: ${isoUtc(exportContext.generatedAtMs)}\n")
    writer.write("# GeneratedAtLocal: ${isoLocal(exportContext.generatedAtMs)}\n")
    writer.write("# RequestedTimeRange: ${exportContext.requestedTimeRange}\n")
    writer.write("# CutoffTimestampUnixMsUTC: ${exportContext.cutoffTime}\n")
    writer.write("# LocalTimezone: ${exportContext.localTimezoneId} (${formatOffsetMinutes(exportContext.localTimezoneOffsetMinutes)})\n")
    writer.write("# NullToken: $NULL_TOKEN\n")
    writer.write("# TimestampUnixMsUTC: Unix epoch milliseconds in UTC\n")
    writer.write("# DateTimeUTC: ISO 8601 UTC timestamp\n")
    writer.write("# DateTimeLocal: ISO 8601 local timestamp with UTC offset\n")
    writer.write("# SeverityRaw_0to10: Internal TremorWatch severity index (0-10), not a diagnostic clinical scale\n")
    writer.write("# ReliabilityScore_0to1: Confidence estimate, higher is more reliable\n")
    writer.write("# IncludeInAnalysis: true when ExcludeFromAnalysis != true and (IsReliable == true or ReliabilityScore_0to1 >= $RELIABILITY_THRESHOLD)\n")
    extraLines.forEach { writer.write("# $it\n") }
    writer.write("#\n")
}

private fun csvValue(value: Any?): String {
    val text = when (value) {
        null -> NULL_TOKEN
        is Double -> if (value.isNaN() || value.isInfinite()) NULL_TOKEN else value.toString()
        is Float -> if (value.isNaN() || value.isInfinite()) NULL_TOKEN else value.toString()
        else -> value.toString()
    }
    return if (text.contains(',') || text.contains('"') || text.contains('\n') || text.contains('\r')) {
        "\"${text.replace("\"", "\"\"")}\""
    } else {
        text
    }
}

private fun csvRow(vararg values: Any?): String {
    return values.joinToString(",") { csvValue(it) } + "\n"
}

private fun String?.nullIfBlank(): String? {
    if (this == null) return null
    return if (isBlank()) null else this
}

private fun formatDouble(value: Double?, decimals: Int): String? {
    if (value == null || value.isNaN() || value.isInfinite()) {
        return null
    }
    return String.format(Locale.US, "%.${decimals}f", value)
}

private fun reliabilityScore(
    sampleConfidence: Double?,
    adjustedConfidence: Double?
): Double? {
    return adjustedConfidence ?: sampleConfidence
}

private fun includeInAnalysis(
    isReliable: Boolean?,
    excludeFromAnalysis: Boolean?,
    reliabilityScore: Double?
): Boolean? {
    if (excludeFromAnalysis == true) {
        return false
    }
    if (isReliable != null) {
        return isReliable
    }
    if (reliabilityScore != null) {
        return reliabilityScore >= RELIABILITY_THRESHOLD
    }
    return null
}

private fun startOfLocalDayMillis(timestamp: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestamp
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun percentile(values: List<Double>, percentile: Double): Double? {
    if (values.isEmpty()) {
        return null
    }
    val sorted = values.sorted()
    if (sorted.size == 1) {
        return sorted.first()
    }
    val bounded = percentile.coerceIn(0.0, 1.0)
    val position = bounded * (sorted.size - 1)
    val lower = kotlin.math.floor(position).toInt()
    val upper = kotlin.math.ceil(position).toInt()
    if (lower == upper) {
        return sorted[lower]
    }
    val weight = position - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
}

private fun pseudonymizedWatchId(
    rawWatchId: String?,
    exportContext: ExportContext
): String? {
    val source = rawWatchId?.trim()
    if (source.isNullOrEmpty()) {
        return null
    }
    return exportContext.watchAliases.getOrPut(source) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${exportContext.exportId}:$source".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        "DEV_${digest.take(8)}"
    }
}

private fun inferMetricsFromTremorSamples(samples: List<TremorSample>): InferredMetrics? {
    if (samples.isEmpty()) {
        return null
    }
    val preferred = samples.filter { it.isWorn != false && it.isCharging != true }
    val source = if (preferred.isNotEmpty()) preferred else samples
    if (source.isEmpty()) {
        return null
    }

    return InferredMetrics(
        severity = source.map { it.severity }.average(),
        confidence = source.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average(),
        frequencyHz = source.mapNotNull { it.dominantFrequency }.filter { it > 0.0 }
            .takeIf { it.isNotEmpty() }?.average()
    )
}

// ============== STREAMING EXPORT FUNCTIONS (memory-efficient) ==============

private const val CHUNK_SIZE = 5000

/**
 * Write detailed CSV with streaming - fetches data in chunks to avoid OOM.
 */
private suspend fun writeStreamingDetailedCsv(
    writer: FileWriter,
    dbHelper: TremorDatabaseHelper,
    cutoffTime: Long,
    exportContext: ExportContext
): Int {
    writeCommonExportHeader(
        writer = writer,
        exportContext = exportContext.copy(formatName = "Detailed"),
        extraLines = listOf(
            "Purpose: Per-sample analysis export",
            "All missing values are encoded as $NULL_TOKEN"
        )
    )
    writer.write(
        "TimestampUnixMsUTC,DateTimeUTC,DateTimeLocal,SeverityRaw_0to10,SeverityNorm_0to1," +
            "TremorCount,ActivityType,ActivityConfidence_0to1,ActivityAgeMs," +
            "AdjustedSeverity_0to10,AdjustedConfidence_0to1,ReliabilityScore_0to1," +
            "IsReliable,ExcludeFromAnalysis,IncludeInAnalysis," +
            "IsLikelyArtifact,ArtifactType,ReliabilityTier,InTremorEpisode,EpisodeDurationMs,ActivitySource,ConfidenceWeightedSeverity_0to10\n"
    )

    var offset = 0
    var totalRecords = 0

    while (true) {
        val chunk = dbHelper.getSamplesAfterPaged(cutoffTime, CHUNK_SIZE, offset)
        if (chunk.isEmpty()) break

        chunk.sortedBy { it.timestamp }.forEach { record ->
            val metadata = parseMetadata(record.metadataJson)
            val activityType = metaValue(metadata, "activityType").nullIfBlank()
            val activityConfidence = optDouble(metadata, "activityConfidence")
            val activityAgeMs = metaValue(metadata, "activityAgeMs").nullIfBlank()
            val adjustedSeverity = optDouble(metadata, "activityAdjustedSeverity")
            val adjustedConfidence = optDouble(metadata, "activityAdjustedConfidence")
            val isReliable = optBoolean(metadata, "isReliableMeasurement")
            val excludeFromAnalysis = optBoolean(metadata, "excludeFromAnalysis")
            val reliability = reliabilityScore(record.confidence, adjustedConfidence)
            val include = includeInAnalysis(isReliable, excludeFromAnalysis, reliability)
            val severityNorm = (record.severity / 10.0).coerceIn(0.0, 1.0)

            // opus46 Phase 3 (P2): Extract artifact and reliability metadata
            val isLikelyArtifact = optBoolean(metadata, "isLikelyArtifact")
            val artifactType = metaValue(metadata, "artifactType").nullIfBlank()
            val reliabilityTier = metaValue(metadata, "reliabilityTier").nullIfBlank()
            val inTremorEpisode = optBoolean(metadata, "inTremorEpisode")
            val episodeDurationMs = metaValue(metadata, "episodeDurationMs").nullIfBlank()
            val activitySource = metaValue(metadata, "activitySource").nullIfBlank()

            // opus46 Issue 7: Confidence-weighted severity (requires full sample fields)
            val cwSeverity = confidenceWeightedSeverity(
                record.severity,
                record.confidence?.toFloat() ?: 0f,
                record.dominantFrequency?.toFloat() ?: 0f,
                record.accelMagnitude?.toFloat() ?: 0f
            )

            writer.write(
                csvRow(
                    record.timestamp,
                    isoUtc(record.timestamp),
                    isoLocal(record.timestamp),
                    formatDouble(record.severity, 6),
                    formatDouble(severityNorm, 6),
                    record.tremorCount,
                    activityType,
                    formatDouble(activityConfidence, 4),
                    activityAgeMs,
                    formatDouble(adjustedSeverity, 6),
                    formatDouble(adjustedConfidence, 6),
                    formatDouble(reliability, 6),
                    isReliable,
                    excludeFromAnalysis,
                    include,
                    isLikelyArtifact,
                    artifactType,
                    reliabilityTier,
                    inTremorEpisode,
                    episodeDurationMs,
                    activitySource,
                    formatDouble(cwSeverity, 6)
                )
            )
            totalRecords++
        }

        offset += CHUNK_SIZE

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
    cutoffTime: Long,
    exportContext: ExportContext
): Int {
    writeCommonExportHeader(
        writer = writer,
        exportContext = exportContext.copy(formatName = "Raw Data"),
        extraLines = listOf(
            "Purpose: Raw/debug export with sensor + spectral fields",
            "Watch IDs are pseudonymized to export-local aliases"
        )
    )
    writer.write(
        "TimestampUnixMsUTC,DateTimeUTC,DateTimeLocal,SeverityRaw_0to10,SeverityNorm_0to1," +
            "TremorCount,AccelX_g,AccelY_g,AccelZ_g,VectorMagnitude_g,AccelMagnitude_g,Confidence_0to1," +
            "IsWorn,IsCharging,DominantFreq_Hz,TremorBandPower,TotalPower,BandRatio,PeakProminence,WatchIdAlias," +
            "ActivityType,ActivityConfidence_0to1,ActivityAgeMs,AdjustedSeverity_0to10,AdjustedConfidence_0to1," +
            "ReliabilityScore_0to1,IsReliable,ExcludeFromAnalysis,IncludeInAnalysis," +
            "IsLikelyArtifact,ArtifactType,ReliabilityTier,InTremorEpisode,EpisodeDurationMs,ActivitySource,ConfidenceWeightedSeverity_0to10\n"
    )

    var offset = 0
    var totalRecords = 0

    while (true) {
        val chunk = dbHelper.getSamplesAfterPaged(cutoffTime, CHUNK_SIZE, offset)
        if (chunk.isEmpty()) break

        chunk.sortedBy { it.timestamp }.forEach { sample ->
            val metadata = parseMetadata(sample.metadataJson)
            val activityType = metaValue(metadata, "activityType").nullIfBlank()
            val activityConfidence = optDouble(metadata, "activityConfidence")
            val activityAgeMs = metaValue(metadata, "activityAgeMs").nullIfBlank()
            val adjustedSeverity = optDouble(metadata, "activityAdjustedSeverity")
            val adjustedConfidence = optDouble(metadata, "activityAdjustedConfidence")
            val isReliable = optBoolean(metadata, "isReliableMeasurement")
            val excludeFromAnalysis = optBoolean(metadata, "excludeFromAnalysis")
            val reliability = reliabilityScore(sample.confidence, adjustedConfidence)
            val include = includeInAnalysis(isReliable, excludeFromAnalysis, reliability)
            val severityNorm = (sample.severity / 10.0).coerceIn(0.0, 1.0)
            val watchIdAlias = pseudonymizedWatchId(sample.watchId, exportContext)

            // opus46 Phase 3 (P2): Extract artifact and reliability metadata
            val isLikelyArtifact = optBoolean(metadata, "isLikelyArtifact")
            val artifactType = metaValue(metadata, "artifactType").nullIfBlank()
            val reliabilityTier = metaValue(metadata, "reliabilityTier").nullIfBlank()
            val inTremorEpisode = optBoolean(metadata, "inTremorEpisode")
            val episodeDurationMs = metaValue(metadata, "episodeDurationMs").nullIfBlank()
            val activitySource = metaValue(metadata, "activitySource").nullIfBlank()

            // opus46 Issue 7: Confidence-weighted severity for post-hoc analysis
            val cwSeverity = confidenceWeightedSeverity(
                sample.severity,
                sample.confidence?.toFloat() ?: 0f,
                sample.dominantFrequency?.toFloat() ?: 0f,
                sample.accelMagnitude?.toFloat() ?: 0f
            )

            writer.write(
                csvRow(
                    sample.timestamp,
                    isoUtc(sample.timestamp),
                    isoLocal(sample.timestamp),
                    formatDouble(sample.severity, 6),
                    formatDouble(severityNorm, 6),
                    sample.tremorCount,
                    formatDouble(sample.x, 6),
                    formatDouble(sample.y, 6),
                    formatDouble(sample.z, 6),
                    formatDouble(sample.magnitude, 6),
                    formatDouble(sample.accelMagnitude, 6),
                    formatDouble(sample.confidence, 6),
                    sample.isWorn,
                    sample.isCharging,
                    formatDouble(sample.dominantFrequency, 4),
                    formatDouble(sample.tremorBandPower, 6),
                    formatDouble(sample.totalPower, 6),
                    formatDouble(sample.bandRatio, 6),
                    formatDouble(sample.peakProminence, 6),
                    watchIdAlias,
                    activityType,
                    formatDouble(activityConfidence, 4),
                    activityAgeMs,
                    formatDouble(adjustedSeverity, 6),
                    formatDouble(adjustedConfidence, 6),
                    formatDouble(reliability, 6),
                    isReliable,
                    excludeFromAnalysis,
                    include,
                    isLikelyArtifact,
                    artifactType,
                    reliabilityTier,
                    inTremorEpisode,
                    episodeDurationMs,
                    activitySource,
                    formatDouble(cwSeverity, 6)
                )
            )
            totalRecords++
        }

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
    cutoffTime: Long,
    exportContext: ExportContext
): Int {
    writeCommonExportHeader(
        writer = writer,
        exportContext = exportContext.copy(formatName = "Summary"),
        extraLines = listOf(
            "Purpose: Daily clinician-facing summary",
            "Summary rows are grouped by local calendar day"
        )
    )
    writer.write(
        "DateLocal,DayStartUnixMsUTC,DayStartUTC,DayStartLocal,SampleCount,TremorPositiveSamples," +
            "SeverityMedian_0to10,SeverityP25_0to10,SeverityP75_0to10,SeverityMax_0to10," +
            "WearMinutes,ReliableMinutes,ReliabilityFraction_0to1,ExcludedFraction_0to1," +
            "SubjectiveRatingCount,SubjectiveRatingAvg_0to5\n"
    )

    val dailyData = mutableMapOf<Long, MutableList<TremorSample>>()
    var offset = 0

    while (true) {
        val chunk = dbHelper.getSamplesAfterPaged(cutoffTime, CHUNK_SIZE, offset)
        if (chunk.isEmpty()) break

        chunk.forEach { sample ->
            val dayStart = startOfLocalDayMillis(sample.timestamp)
            dailyData.getOrPut(dayStart) { mutableListOf() }.add(sample)
        }

        offset += CHUNK_SIZE
    }

    val ratingsByDay = dbHelper.getRatingsAfter(cutoffTime).groupBy { rating ->
        startOfLocalDayMillis(rating.timestamp)
    }

    dailyData.entries.sortedBy { it.key }.forEach { (dayStart, records) ->
        val severityValues = records.map { it.severity }
        val sampleCount = records.size
        val tremorPositive = records.count { it.tremorCount > 0 }
        val median = percentile(severityValues, 0.5)
        val p25 = percentile(severityValues, 0.25)
        val p75 = percentile(severityValues, 0.75)
        val maxSeverity = records.maxOfOrNull { it.severity } ?: 0.0
        val wearMinutes = records
            .filter { it.isWorn == true }
            .map { it.timestamp / 60000L }
            .distinct()
            .size

        val reliableSamples = records.mapNotNull {
            optBoolean(parseMetadata(it.metadataJson), "isReliableMeasurement")
        }
        val reliableMinutes = records
            .filter { optBoolean(parseMetadata(it.metadataJson), "isReliableMeasurement") == true }
            .map { it.timestamp / 60000L }
            .distinct()
            .size
        val reliableFraction = if (reliableSamples.isNotEmpty()) {
            reliableSamples.count { it }.toDouble() / reliableSamples.size.toDouble()
        } else {
            null
        }

        val excludedSamples = records.mapNotNull {
            optBoolean(parseMetadata(it.metadataJson), "excludeFromAnalysis")
        }
        val excludedFraction = if (excludedSamples.isNotEmpty()) {
            excludedSamples.count { it }.toDouble() / excludedSamples.size.toDouble()
        } else {
            null
        }

        val dayRatings = ratingsByDay[dayStart].orEmpty()
        val ratingAverage = if (dayRatings.isNotEmpty()) {
            dayRatings.map { it.rating.toDouble() }.average()
        } else {
            null
        }

        val localDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayStart))
        writer.write(
            csvRow(
                localDate,
                dayStart,
                isoUtc(dayStart),
                isoLocal(dayStart),
                sampleCount,
                tremorPositive,
                formatDouble(median, 6),
                formatDouble(p25, 6),
                formatDouble(p75, 6),
                formatDouble(maxSeverity, 6),
                wearMinutes,
                reliableMinutes,
                formatDouble(reliableFraction, 6),
                formatDouble(excludedFraction, 6),
                dayRatings.size,
                formatDouble(ratingAverage, 4)
            )
        )
    }

    return dailyData.size
}

/**
 * Write subjective ratings to CSV including calibration data if available.
 * Exports from the subjective_ratings table with linked calibration_data.
 */
private suspend fun writeSubjectiveRatingsCsv(
    writer: FileWriter,
    context: Context,
    cutoffTime: Long,
    exportContext: ExportContext
): Int {
    val db = TremorRoomDatabase.getDatabase(context)
    val dao = db.tremorDao()

    writeCommonExportHeader(
        writer = writer,
        exportContext = exportContext.copy(formatName = "Subjective Ratings"),
        extraLines = listOf(
            "Purpose: Subjective ratings with linked objective context window",
            "Detected* fields are backfilled from calibration samples or objective window when missing"
        )
    )
    writer.write(
        "RatingId,TimestampUnixMsUTC,DateTimeUTC,DateTimeLocal,Rating_0to5,Source,WatchIdAlias," +
            "DetectedSeverity_0to10,DetectedConfidence_0to1,DetectedFrequency_Hz," +
            "CalibrationEnabled,CalibrationDurationSec,CalibrationSampleCount," +
            "LinkedWindowStartUnixMsUTC,LinkedWindowEndUnixMsUTC,LinkedWindowStartUTC,LinkedWindowEndUTC," +
            "CalibrationDataStatus,ReconciliationStatus,Notes\n"
    )

    val ratings = dao.getRatingsAfter(cutoffTime).sortedBy { it.timestamp }
    if (ratings.isEmpty()) {
        return 0
    }

    var totalRecords = 0
    for (rating in ratings) {
        val calibrationCount = dao.getCalibrationCountForRating(rating.id)
        val hasCalibration = calibrationCount > 0
        val durationMs = rating.calibrationDurationSeconds.coerceIn(5, 300) * 1000L
        val windowEnd = (rating.timestamp - 500L).coerceAtLeast(0L)
        val windowStart = (windowEnd - durationMs).coerceAtLeast(0L)

        val calibrationData = if (hasCalibration) dao.getCalibrationDataForRating(rating.id) else emptyList()
        val inferredFromCalibration = if (calibrationData.isNotEmpty()) {
            InferredMetrics(
                severity = calibrationData.map { it.severity }.average(),
                confidence = calibrationData.map { it.confidence.toDouble() }.average(),
                frequencyHz = calibrationData.map { it.dominantFrequency.toDouble() }
                    .filter { it > 0.0 }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
            )
        } else {
            null
        }
        val inferredFromWindow = if (inferredFromCalibration == null) {
            inferMetricsFromTremorSamples(dao.getSamplesInRange(windowStart, windowEnd))
        } else {
            null
        }

        val detectedSeverity = rating.detectedSeverity
            ?: inferredFromCalibration?.severity
            ?: inferredFromWindow?.severity
        val detectedConfidence = rating.detectedConfidence?.toDouble()
            ?: inferredFromCalibration?.confidence
            ?: inferredFromWindow?.confidence
        val detectedFrequency = rating.detectedFrequency?.toDouble()
            ?: inferredFromCalibration?.frequencyHz
            ?: inferredFromWindow?.frequencyHz

        val status = when {
            hasCalibration -> "HAS_CALIBRATION_DATA"
            inferredFromWindow != null -> "WINDOW_ONLY"
            else -> "NO_OBJECTIVE_DATA"
        }

        // opus46 Issue 6b: Reconciliation status comparing subjective rating vs detected severity
        val reconciliation = try {
            val objectiveContext = rating.objectiveContextJson?.let { JSONObject(it) }
            val watchCtx = objectiveContext?.optJSONObject("watchContext")
            val phoneCtx = objectiveContext?.optJSONObject("phoneContext")
            val window10s = watchCtx?.optJSONObject("window_10s")
                ?: phoneCtx?.let { JSONObject(it.toString()).optJSONObject("window_10s") }
            val avgSeverity = window10s?.optDouble("meanSeverity", -1.0) ?: -1.0

            when {
                avgSeverity < 0 -> "NO_CONTEXT"
                rating.rating >= 3 && avgSeverity < 0.5 -> "USER_HIGH_SENSOR_LOW"
                rating.rating <= 1 && avgSeverity > 1.5 -> "USER_LOW_SENSOR_HIGH"
                else -> "CONCORDANT"
            }
        } catch (e: Exception) {
            "PARSE_ERROR"
        }

        writer.write(
            csvRow(
                rating.id,
                rating.timestamp,
                isoUtc(rating.timestamp),
                isoLocal(rating.timestamp),
                rating.rating,
                rating.source,
                pseudonymizedWatchId(rating.watchId, exportContext),
                formatDouble(detectedSeverity, 6),
                formatDouble(detectedConfidence, 6),
                formatDouble(detectedFrequency, 4),
                rating.calibrationModeEnabled || hasCalibration,
                rating.calibrationDurationSeconds,
                calibrationCount,
                windowStart,
                windowEnd,
                isoUtc(windowStart),
                isoUtc(windowEnd),
                status,
                reconciliation,
                rating.notes?.replace("\n", " ")
            )
        )
        totalRecords++
    }

    return totalRecords
}

/**
 * Import data from recovery CSV file.
 * Handles timestamp deduplication by adding milliseconds to corrupted/rounded timestamps.
 */
private suspend fun importRecoveryData(context: Context): String {
    return withContext(Dispatchers.IO) {
        try {
            val db = TremorRoomDatabase.getDatabase(context)
            val dao = db.tremorDao()
            
            // Check possible locations
            val externalFile = File(context.getExternalFilesDir(null), "recovery.csv")
            val cacheFile = File(context.cacheDir, "recovery.csv")
            val importFile = if (externalFile.exists()) externalFile else if (cacheFile.exists()) cacheFile else null
            
            if (importFile == null || !importFile.exists()) {
                return@withContext "Recovery file not found"
            }
            
            var importedCount = 0
            val batchSize = 500
            val batch = mutableListOf<TremorSample>()
            
            // Track timestamp to prevent duplicates (since CSV timestamp might be rounded)
            var lastTimestamp = 0L
            
            importFile.useLines { lines ->
                lines.forEach { line ->
                    // Skip headers
                    if (line.startsWith("#") || line.startsWith("Timestamp")) return@forEach
                    
                    try {
                        val parts = line.split(",")
                        if (parts.size >= 4) {
                            // Part 0: Timestamp (might be scientific notation 1.77E12, useless)
                            // Part 1: DateTime (d/MM/yyyy H:mm or similar)
                            val dateStr = parts[1]
                            val severity = parts[2].toDoubleOrNull() ?: 0.0
                            val tremorCount = parts[3].toIntOrNull() ?: 0
                            
                            // Parse timestamp from DateTime string
                            var timestamp: Long = 0
                            val formats = listOf(
                                SimpleDateFormat("d/MM/yyyy H:mm", Locale.US),
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
                                SimpleDateFormat("M/d/yyyy H:mm", Locale.US)
                            )
                            
                            for (fmt in formats) {
                                try {
                                    timestamp = fmt.parse(dateStr)?.time ?: 0
                                    if (timestamp > 0) break
                                } catch (e: Exception) { continue }
                            }
                            
                            // If timestamp parsing failed or is scientific, rely on synthesis
                            if (timestamp == 0L) {
                                // Try parsing column 0 as fallback
                                timestamp = parts[0].toDoubleOrNull()?.toLong() ?: System.currentTimeMillis()
                            }
                            
                            // Ensure uniqueness by incrementing if <= lastTimestamp
                            if (timestamp <= lastTimestamp) {
                                timestamp = lastTimestamp + 1
                            }
                            lastTimestamp = timestamp
                            
                            // Reconstruct metadata
                            val metadata = JSONObject()
                            if (parts.size > 4 && parts[4].isNotEmpty()) metadata.put("activityType", parts[4])
                            if (parts.size > 5 && parts[5].isNotEmpty()) metadata.put("activityConfidence", parts[5].toDoubleOrNull())
                            if (parts.size > 6 && parts[6].isNotEmpty()) metadata.put("activityAgeMs", parts[6].toIntOrNull())
                            if (parts.size > 7 && parts[7].isNotEmpty()) metadata.put("activityAdjustedSeverity", parts[7].toDoubleOrNull())
                            if (parts.size > 8 && parts[8].isNotEmpty()) metadata.put("activityAdjustedConfidence", parts[8].toDoubleOrNull())
                            if (parts.size > 9 && parts[9].isNotEmpty()) metadata.put("isReliableMeasurement", parts[9].toBoolean())
                            if (parts.size > 10 && parts[10].isNotEmpty()) metadata.put("excludeFromAnalysis", parts[10].toBoolean())
                            
                            val sample = TremorSample(
                                timestamp = timestamp,
                                severity = severity,
                                tremorCount = tremorCount,
                                isWorn = severity > 0, // Infer
                                metadataJson = metadata.toString()
                            )
                            
                            batch.add(sample)
                            
                            if (batch.size >= batchSize) {
                                dao.insertAll(batch)
                                importedCount += batch.size
                                batch.clear()
                            }
                        }
                    } catch (e: Exception) {
                        // Skip malformed line
                    }
                }
            }
            
            // Insert remaining
            if (batch.isNotEmpty()) {
                dao.insertAll(batch)
                importedCount += batch.size
            }
            
            "Success: Imported $importedCount samples"
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        }
    }
}
