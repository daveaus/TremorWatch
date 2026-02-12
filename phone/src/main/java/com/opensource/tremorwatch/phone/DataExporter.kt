package com.opensource.tremorwatch.phone

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.phone.database.TremorDatabaseHelper
import com.opensource.tremorwatch.phone.database.TremorSample
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility for exporting tremor data to CSV format.
 * Queries SQLite database for efficient data retrieval.
 */
object DataExporter {

    private const val TAG = "DataExporter"
    private const val PAGE_SIZE = 5_000
    private const val FLUSH_EVERY_N_ROWS = 1_000

    private const val CSV_HEADER = "Timestamp,Unix Timestamp (ms),Severity,Tremor Count," +
        "Activity Type,Activity Confidence,Activity Age Ms," +
        "Adjusted Severity,Adjusted Confidence,Is Reliable,Exclude From Analysis"

    data class ExportResult(
        val success: Boolean,
        val message: String,
        val filePath: String? = null,
        val recordCount: Int = 0,
        val fileSize: Long = 0
    )

    /**
     * Export all local storage data to CSV from database
     */
    suspend fun exportToCSV(context: Context): ExportResult {
        val appContext = context.applicationContext
        val dbHelper = TremorDatabaseHelper(appContext)

        val totalCount = dbHelper.getSamplesCountAfter(0L)
        if (totalCount == 0) {
            Log.w(TAG, "No data in database")
            return ExportResult(
                success = false,
                message = "No data to export. Start collecting tremor data first."
            )
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val fileName = "tremorwatch_export_$timestamp.csv"

        return exportPaged(
            context = appContext,
            fileName = fileName,
            pageProvider = { limit, offset -> dbHelper.getSamplesAfterPaged(0L, limit, offset) }
        )
    }

    /**
     * Export data within a specific time range from database
     */
    suspend fun exportToCSVByTimeRange(context: Context, startTimeMs: Long, endTimeMs: Long): ExportResult {
        val appContext = context.applicationContext
        val dbHelper = TremorDatabaseHelper(appContext)

        val totalCount = dbHelper.getSamplesCountInRange(startTimeMs, endTimeMs)
        if (totalCount == 0) {
            return ExportResult(
                success = false,
                message = "No data found in specified time range"
            )
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val fileName = "tremorwatch_export_${timestamp}_filtered.csv"

        return exportPaged(
            context = appContext,
            fileName = fileName,
            pageProvider = { limit, offset -> dbHelper.getSamplesInRangePaged(startTimeMs, endTimeMs, limit, offset) }
        )
    }

    private suspend fun exportPaged(
        context: Context,
        fileName: String,
        pageProvider: suspend (limit: Int, offset: Int) -> List<TremorSample>
    ): ExportResult {
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val csvFile = File(outDir, fileName)
        val tempFile = File(csvFile.parentFile, "${csvFile.name}.tmp")

        Log.d(TAG, "Exporting data to ${csvFile.absolutePath}")

        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        var recordCount = 0

        return try {
            tempFile.bufferedWriter().use { writer ->
                writer.appendLine(CSV_HEADER)

                var offset = 0
                while (true) {
                    val page = pageProvider(PAGE_SIZE, offset)
                    if (page.isEmpty()) break

                    for (sample in page) {
                        writeCsvRow(writer, sample, dateFormatter)
                        recordCount++

                        if (recordCount % FLUSH_EVERY_N_ROWS == 0) {
                            writer.flush()
                        }
                    }

                    offset += page.size
                }
            }

            // Finalize atomically (best-effort; fallback to copy if rename fails).
            if (!tempFile.renameTo(csvFile)) {
                try {
                    tempFile.copyTo(csvFile, overwrite = true)
                } finally {
                    tempFile.delete()
                }
            }

            val fileSize = csvFile.length()
            Log.i(TAG, "Successfully exported $recordCount records to ${csvFile.name}")

            ExportResult(
                success = true,
                message = "Exported $recordCount records to ${csvFile.name}",
                filePath = csvFile.absolutePath,
                recordCount = recordCount,
                fileSize = fileSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during export: ${e.message}", e)
            tempFile.delete()
            ExportResult(
                success = false,
                message = "Unexpected error: ${e.message}"
            )
        }
    }

    private fun writeCsvRow(writer: BufferedWriter, sample: TremorSample, dateFormatter: SimpleDateFormat) {
        val dateStr = dateFormatter.format(Date(sample.timestamp))
        val metadata = parseMetadata(sample.metadataJson)

        writer.append(csvEscape(dateStr)).append(',')
            .append(sample.timestamp.toString()).append(',')
            .append(sample.severity.toString()).append(',')
            .append(sample.tremorCount.toString()).append(',')
            .append(csvEscape(metaValue(metadata, "activityType"))).append(',')
            .append(csvEscape(metaValue(metadata, "activityConfidence"))).append(',')
            .append(csvEscape(metaValue(metadata, "activityAgeMs"))).append(',')
            .append(csvEscape(metaValue(metadata, "activityAdjustedSeverity"))).append(',')
            .append(csvEscape(metaValue(metadata, "activityAdjustedConfidence"))).append(',')
            .append(csvEscape(metaValue(metadata, "isReliableMeasurement"))).append(',')
            .appendLine(csvEscape(metaValue(metadata, "excludeFromAnalysis")))
    }

    private fun csvEscape(value: String): String {
        val needsQuotes = value.indexOfAny(charArrayOf(',', '"', '\n', '\r')) >= 0
        if (!needsQuotes) return value

        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /**
     * Get storage statistics from database
     */
    suspend fun getStorageStats(context: Context): StorageExportStats {
        return try {
            val dbHelper = TremorDatabaseHelper(context)
            val stats = dbHelper.getStats()

            if (stats.totalSamples == 0) {
                return StorageExportStats(
                    fileExists = false,
                    batchCount = 0,
                    sampleCount = 0,
                    oldestTimestamp = 0,
                    newestTimestamp = 0,
                    fileSizeKB = 0.0
                )
            }

            // Estimate database size (can't get exact size easily on Android)
            val dbFile = context.getDatabasePath("tremor_data.db")
            val fileSizeKB = if (dbFile.exists()) dbFile.length() / 1024.0 else 0.0

            StorageExportStats(
                fileExists = true,
                batchCount = 0,  // Not tracked in new schema
                sampleCount = stats.totalSamples,
                oldestTimestamp = stats.earliestTimestamp ?: 0,
                newestTimestamp = stats.latestTimestamp ?: 0,
                fileSizeKB = fileSizeKB
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage stats: ${e.message}", e)
            StorageExportStats(
                fileExists = false,
                batchCount = 0,
                sampleCount = 0,
                oldestTimestamp = 0,
                newestTimestamp = 0,
                fileSizeKB = 0.0
            )
        }
    }

    data class StorageExportStats(
        val fileExists: Boolean,
        val batchCount: Int,
        val sampleCount: Int,
        val oldestTimestamp: Long,
        val newestTimestamp: Long,
        val fileSizeKB: Double
    )

    private fun parseMetadata(metadataJson: String?): JSONObject? {
        return metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun metaValue(metadata: JSONObject?, key: String): String {
        if (metadata == null || !metadata.has(key) || metadata.isNull(key)) {
            return ""
        }
        return metadata.opt(key)?.toString() ?: ""
    }
}
