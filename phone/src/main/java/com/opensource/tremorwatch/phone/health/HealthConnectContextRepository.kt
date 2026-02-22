package com.opensource.tremorwatch.phone.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

data class HealthContextSnapshot(
    val sourceAvailable: Boolean,
    val windowHours: Int,
    val steps: Long,
    val avgHeartRateBpm: Double?,
    val restingHeartRateBpm: Double?,
    val avgRmssdMs: Double?,
    val sleepMinutes: Long
)

/**
 * Optional Health Connect context reader.
 *
 * This is used for post-hoc interpretation (activity/sleep/autonomic context),
 * not hard real-time gating of tremor detection.
 */
class HealthConnectContextRepository(
    private val context: Context
) {
    companion object {
        private const val TAG = "HealthConnectContext"
        private const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"
    }

    fun isHealthConnectAvailable(): Boolean {
        return HealthConnectClient.sdkStatus(context, PROVIDER_PACKAGE_NAME) ==
            HealthConnectClient.SDK_AVAILABLE
    }

    fun requiredReadPermissions(): Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    )

    suspend fun hasAllRequiredPermissions(): Boolean {
        if (!isHealthConnectAvailable()) return false
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            requiredReadPermissions().all { it in granted }
        } catch (e: Exception) {
            Log.w(TAG, "Failed checking Health Connect permissions: ${e.message}", e)
            false
        }
    }

    suspend fun readSnapshot(windowHours: Int = 24): HealthContextSnapshot {
        if (!isHealthConnectAvailable()) {
            return HealthContextSnapshot(
                sourceAvailable = false,
                windowHours = windowHours,
                steps = 0L,
                avgHeartRateBpm = null,
                restingHeartRateBpm = null,
                avgRmssdMs = null,
                sleepMinutes = 0L
            )
        }

        val client = HealthConnectClient.getOrCreate(context)
        val end = Instant.now()
        val start = end.minus(windowHours.toLong(), ChronoUnit.HOURS)
        val range = TimeRangeFilter.between(start, end)

        return try {
            val stepRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = range
                )
            ).records
            val steps = stepRecords.sumOf { it.count }

            val heartRateRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = range
                )
            ).records
            val hrSamples = heartRateRecords.flatMap { it.samples }
            val avgHeartRate = hrSamples.map { it.beatsPerMinute.toDouble() }
                .takeIf { it.isNotEmpty() }
                ?.average()

            val restingRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = range
                )
            ).records
            val restingHeartRate = restingRecords
                .map { it.beatsPerMinute.toDouble() }
                .takeIf { it.isNotEmpty() }
                ?.average()

            val rmssdRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateVariabilityRmssdRecord::class,
                    timeRangeFilter = range
                )
            ).records
            val avgRmssd = rmssdRecords
                .map { it.heartRateVariabilityMillis }
                .takeIf { it.isNotEmpty() }
                ?.average()

            val sleepRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = range
                )
            ).records
            val sleepMinutes = sleepRecords.sumOf { record ->
                ChronoUnit.MINUTES.between(record.startTime, record.endTime).coerceAtLeast(0L)
            }

            HealthContextSnapshot(
                sourceAvailable = true,
                windowHours = windowHours,
                steps = steps,
                avgHeartRateBpm = avgHeartRate,
                restingHeartRateBpm = restingHeartRate,
                avgRmssdMs = avgRmssd,
                sleepMinutes = sleepMinutes
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading Health Connect snapshot: ${e.message}", e)
            HealthContextSnapshot(
                sourceAvailable = false,
                windowHours = windowHours,
                steps = 0L,
                avgHeartRateBpm = null,
                restingHeartRateBpm = null,
                avgRmssdMs = null,
                sleepMinutes = 0L
            )
        }
    }
}
