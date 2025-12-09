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

/**
 * Broadcast receiver that handles periodic batch retry alarms.
 * 
 * Periodically checks for pending batches and triggers upload attempts.
 * Reschedules itself to run every 2 minutes while monitoring is active.
 */
class BatchRetryAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BatchRetryAlarm"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "★★★ Batch retry alarm triggered - checking for pending batches ★★★")

        // Check if monitoring is active
        if (!MonitoringState.isMonitoring(context)) {
            Log.d(TAG, "Monitoring is disabled - skipping batch retry")
            return
        }

        try {
            // Check if there are pending batches waiting to be sent
            // Batches are saved in filesDir with names like tremor_batch_*.json
            val pendingFiles = context.filesDir.listFiles { file ->
                file.name.startsWith("tremor_batch_") && file.name.endsWith(".json")
            }?.sortedBy { it.name } ?: emptyList()

            if (pendingFiles.isNotEmpty()) {
                Log.i(TAG, "Found ${pendingFiles.size} pending batch(es) - retrying send")

                // Trigger the service's retryFailedUploads method via broadcast
                // This ensures proper handling and uses the existing upload logic
                val uploadIntent = Intent("com.opensource.tremorwatch.TRIGGER_UPLOAD").apply {
                    putExtra("manual", false) // Automatic retry
                    setPackage(context.packageName)
                }
                context.sendBroadcast(uploadIntent)
                Log.d(TAG, "Sent TRIGGER_UPLOAD broadcast to service")
            } else {
                Log.d(TAG, "No pending batches found")
            }

            // Reschedule next retry alarm
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nextIntent = Intent(context, BatchRetryAlarmReceiver::class.java)
            val nextPendingIntent = PendingIntent.getBroadcast(
                context,
                2,
                nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Schedule next retry in 2 minutes
            val triggerAtMillis = SystemClock.elapsedRealtime() + 2 * 60 * 1000L

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        nextPendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        nextPendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    nextPendingIntent
                )
            }

            Log.d(TAG, "Rescheduled next batch retry alarm in 2 minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing batch retry: ${e.message}", e)
        }
    }
}

