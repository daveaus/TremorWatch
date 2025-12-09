package com.opensource.tremorwatch.phone

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.shared.models.TremorBatch
import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility for exporting tremor data to CSV format.
 * Reads data from consolidated_tremor_data.jsonl and exports to CSV.
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
     * Export all local storage data to CSV
     */
    fun exportToCSV(context: Context): ExportResult {
        return try {
            val storageFile = File(context.filesDir, "consolidated_tremor_data.jsonl")

            if (!storageFile.exists()) {
                Log.w(TAG, "No local storage file found")
                return ExportResult(
                    success = false,
                    message = "No data to export. Enable local storage and upload some batches first."
                )
            }

            // Create CSV file
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val csvFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "tremorwatch_export_$timestamp.csv")

            Log.d(TAG, "Exporting data to ${csvFile.absolutePath}")

            // Read JSONL and convert to CSV
            val csvLines = mutableListOf<String>()
            val csvHeader = "Timestamp,Unix Timestamp (ms),Severity,Tremor Count,Sample Index"
            csvLines.add(csvHeader)

            var recordCount = 0
            var skipCount = 0

            try {
                storageFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            try {
                                val batch = TremorBatch.fromJsonString(line)

                                // Export each sample in the batch
                                batch.samples.forEachIndexed { index, sample ->
                                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sample.timestamp))
                                    val csvLine = "$dateStr,${sample.timestamp},${sample.severity},${sample.tremorCount},$index"
                                    csvLines.add(csvLine)
                                    recordCount++
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse batch line: ${e.message}")
                                skipCount++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading JSONL file: ${e.message}", e)
                return ExportResult(
                    success = false,
                    message = "Error reading data: ${e.message}"
                )
            }

            // Write CSV file
            try {
                csvFile.writeText(csvLines.joinToString("\n"))
                val fileSize = csvFile.length()

                Log.i(TAG, "Successfully exported $recordCount records to ${csvFile.name}")
                if (skipCount > 0) {
                    Log.w(TAG, "Skipped $skipCount malformed entries")
                }

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
     * Export data within a specific time range
     */
    fun exportToCSVByTimeRange(context: Context, startTimeMs: Long, endTimeMs: Long): ExportResult {
        return try {
            val storageFile = File(context.filesDir, "consolidated_tremor_data.jsonl")

            if (!storageFile.exists()) {
                return ExportResult(
                    success = false,
                    message = "No local storage file found"
                )
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val csvFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "tremorwatch_export_${timestamp}_filtered.csv")

            val csvLines = mutableListOf<String>()
            val csvHeader = "Timestamp,Unix Timestamp (ms),Severity,Tremor Count,Sample Index"
            csvLines.add(csvHeader)

            var recordCount = 0

            try {
                storageFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            try {
                                val batch = TremorBatch.fromJsonString(line)

                                batch.samples.forEachIndexed { index, sample ->
                                    if (sample.timestamp in startTimeMs..endTimeMs) {
                                        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sample.timestamp))
                                        val csvLine = "$dateStr,${sample.timestamp},${sample.severity},${sample.tremorCount},$index"
                                        csvLines.add(csvLine)
                                        recordCount++
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse batch line: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading JSONL file: ${e.message}", e)
                return ExportResult(success = false, message = "Error reading data: ${e.message}")
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
     * Get storage statistics for export
     */
    fun getStorageStats(context: Context): StorageExportStats {
        return try {
            val storageFile = File(context.filesDir, "consolidated_tremor_data.jsonl")

            if (!storageFile.exists()) {
                return StorageExportStats(
                    fileExists = false,
                    batchCount = 0,
                    sampleCount = 0,
                    oldestTimestamp = 0,
                    newestTimestamp = 0,
                    fileSizeKB = 0.0
                )
            }

            var batchCount = 0
            var sampleCount = 0
            var oldestTimestamp = Long.MAX_VALUE
            var newestTimestamp = 0L

            try {
                storageFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            try {
                                val batch = TremorBatch.fromJsonString(line)
                                batchCount++
                                sampleCount += batch.samples.size

                                batch.samples.forEach { sample ->
                                    if (sample.timestamp < oldestTimestamp) {
                                        oldestTimestamp = sample.timestamp
                                    }
                                    if (sample.timestamp > newestTimestamp) {
                                        newestTimestamp = sample.timestamp
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse batch: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading storage file: ${e.message}", e)
            }

            val fileSizeKB = storageFile.length() / 1024.0

            StorageExportStats(
                fileExists = true,
                batchCount = batchCount,
                sampleCount = sampleCount,
                oldestTimestamp = if (oldestTimestamp == Long.MAX_VALUE) 0 else oldestTimestamp,
                newestTimestamp = newestTimestamp,
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
