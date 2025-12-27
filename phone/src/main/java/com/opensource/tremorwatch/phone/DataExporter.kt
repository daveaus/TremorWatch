package com.opensource.tremorwatch.phone

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.phone.database.TremorDatabaseHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility for exporting tremor data to CSV format.
 * Queries SQLite database for efficient data retrieval.
 */
object DataExporter {

    private const val TAG = "DataExporter"

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
        return try {
            val dbHelper = TremorDatabaseHelper(context)
            val samples = dbHelper.getAllSamples()

            if (samples.isEmpty()) {
                Log.w(TAG, "No data in database")
                return ExportResult(
                    success = false,
                    message = "No data to export. Start collecting tremor data first."
                )
            }

            // Create CSV file
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val csvFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "tremorwatch_export_$timestamp.csv")

            Log.d(TAG, "Exporting data to ${csvFile.absolutePath}")

            // Build CSV content
            val csvLines = mutableListOf<String>()
            val csvHeader = "Timestamp,Unix Timestamp (ms),Severity,Tremor Count"
            csvLines.add(csvHeader)

            var recordCount = 0

            samples.sortedBy { it.timestamp }.forEach { sample ->
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sample.timestamp))
                val csvLine = "$dateStr,${sample.timestamp},${sample.severity},${sample.tremorCount}"
                csvLines.add(csvLine)
                recordCount++
            }

            // Write CSV file
            try {
                csvFile.writeText(csvLines.joinToString("\n"))
                val fileSize = csvFile.length()

                Log.i(TAG, "Successfully exported $recordCount records to ${csvFile.name}")

                return ExportResult(
                    success = true,
                    message = "Exported $recordCount records to ${csvFile.name}",
                    filePath = csvFile.absolutePath,
                    recordCount = recordCount,
                    fileSize = fileSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error writing CSV file: ${e.message}", e)
                return ExportResult(
                    success = false,
                    message = "Error writing CSV file: ${e.message}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during export: ${e.message}", e)
            return ExportResult(
                success = false,
                message = "Unexpected error: ${e.message}"
            )
        }
    }

    /**
     * Export data within a specific time range from database
     */
    suspend fun exportToCSVByTimeRange(context: Context, startTimeMs: Long, endTimeMs: Long): ExportResult {
        return try {
            val dbHelper = TremorDatabaseHelper(context)
            val samples = dbHelper.getSamplesInRange(startTimeMs, endTimeMs)

            if (samples.isEmpty()) {
                return ExportResult(
                    success = false,
                    message = "No data found in specified time range"
                )
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val csvFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "tremorwatch_export_${timestamp}_filtered.csv")

            val csvLines = mutableListOf<String>()
            val csvHeader = "Timestamp,Unix Timestamp (ms),Severity,Tremor Count"
            csvLines.add(csvHeader)

            var recordCount = 0

            samples.sortedBy { it.timestamp }.forEach { sample ->
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sample.timestamp))
                val csvLine = "$dateStr,${sample.timestamp},${sample.severity},${sample.tremorCount}"
                csvLines.add(csvLine)
                recordCount++
            }

            try {
                csvFile.writeText(csvLines.joinToString("\n"))
                val fileSize = csvFile.length()

                return ExportResult(
                    success = true,
                    message = "Exported $recordCount records to ${csvFile.name}",
                    filePath = csvFile.absolutePath,
                    recordCount = recordCount,
                    fileSize = fileSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error writing CSV file: ${e.message}", e)
                return ExportResult(success = false, message = "Error writing CSV file: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during export: ${e.message}", e)
            return ExportResult(success = false, message = "Unexpected error: ${e.message}")
        }
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
}
