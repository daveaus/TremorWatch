package com.opensource.tremorwatch.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.opensource.tremorwatch.config.MonitoringState
import com.opensource.tremorwatch.utils.NetworkUtils

/**
 * Broadcast receiver that handles batch retry alarms with exponential backoff.
 *
 * When pending batches exist, retries uploads with increasing delays:
 * 2m -> 5m -> 15m -> 30m -> 60m (cap).
 * Suspends retries when no network is available, resuming on next alarm.
 */
class BatchRetryAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BatchRetryAlarm"
        private const val PREFS_NAME = "batch_retry_prefs"
        private const val KEY_RETRY_COUNT = "retry_count"
        private const val KEY_LAST_RETRY_TIME = "last_retry_time"

        /** Base delay for first retry (2 minutes). */
        private const val BASE_DELAY_MS = 2 * 60 * 1000L
        /** Maximum delay cap (60 minutes). */
        private const val MAX_DELAY_MS = 60 * 60 * 1000L

        /** Backoff schedule: 2m, 5m, 15m, 30m, 60m */
        private val BACKOFF_DELAYS_MS = longArrayOf(
            2 * 60 * 1000L,   // 2 minutes
            5 * 60 * 1000L,   // 5 minutes
            15 * 60 * 1000L,  // 15 minutes
            30 * 60 * 1000L,  // 30 minutes
            60 * 60 * 1000L   // 60 minutes (cap)
        )

        /**
         * Reset the retry counter (call after a successful upload).
         */
        fun resetRetryCount(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RETRY_COUNT, 0)
                .apply()
        }

        private fun getRetryCount(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_RETRY_COUNT, 0)
        }

        private fun incrementRetryCount(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getInt(KEY_RETRY_COUNT, 0)
            prefs.edit()
                .putInt(KEY_RETRY_COUNT, current + 1)
                .putLong(KEY_LAST_RETRY_TIME, System.currentTimeMillis())
                .apply()
        }

        private fun getBackoffDelay(retryCount: Int): Long {
            val index = retryCount.coerceAtMost(BACKOFF_DELAYS_MS.size - 1)
            return BACKOFF_DELAYS_MS[index]
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Batch retry alarm triggered - checking for pending batches")

        // Check if monitoring is active
        if (!MonitoringState.isMonitoring(context)) {
            Log.d(TAG, "Monitoring is disabled - skipping batch retry")
            return
        }

        try {
            // Check if there are pending batches waiting to be sent
            val pendingFiles = context.filesDir.listFiles { file ->
                file.name.startsWith("tremor_batch_") && file.name.endsWith(".json")
            }?.sortedBy { it.name } ?: emptyList()

            if (pendingFiles.isEmpty()) {
                Log.d(TAG, "No pending batches found - resetting retry count")
                resetRetryCount(context)
                // Don't reschedule - TremorService will schedule again when new batches appear
                return
            }

            // Connectivity gate: skip upload attempt if no network, but still reschedule
            if (!NetworkUtils.isNetworkAvailable(context)) {
                Log.i(TAG, "No network available - deferring ${pendingFiles.size} pending batch(es)")
                scheduleNextRetry(context)
                return
            }

            Log.i(TAG, "Found ${pendingFiles.size} pending batch(es) - retrying send (attempt ${getRetryCount(context) + 1})")

            // Trigger the service's retryFailedUploads method via broadcast
            val uploadIntent = Intent("com.opensource.tremorwatch.TRIGGER_UPLOAD").apply {
                putExtra("manual", false)
                setPackage(context.packageName)
            }
            context.sendBroadcast(uploadIntent)

            // Increment retry count for backoff calculation
            incrementRetryCount(context)

            // Reschedule with backoff
            scheduleNextRetry(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing batch retry: ${e.message}", e)
        }
    }

    private fun scheduleNextRetry(context: Context) {
        val retryCount = getRetryCount(context)
        val delay = getBackoffDelay(retryCount)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextIntent = Intent(context, BatchRetryAlarmReceiver::class.java)
        val nextPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = SystemClock.elapsedRealtime() + delay

        // Use inexact alarm - no need for exact wakeups for retries
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                nextPendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                nextPendingIntent
            )
        }

        Log.d(TAG, "Next batch retry in ${delay / 60000}min (attempt ${retryCount + 1}, backoff)")
    }
}
