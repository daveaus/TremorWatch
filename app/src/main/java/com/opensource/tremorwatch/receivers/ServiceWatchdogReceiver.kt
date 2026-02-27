package com.opensource.tremorwatch.receivers

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.opensource.tremorwatch.MainActivity
import com.opensource.tremorwatch.data.PreferencesRepository
import com.opensource.tremorwatch.service.TremorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Broadcast receiver that acts as a watchdog to keep the TremorService alive.
 *
 * Periodically checks if the service is running and restarts it if needed.
 * Includes boot loop protection to prevent excessive restart attempts.
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val RESTART_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_RESTARTS_IN_WINDOW = 3
        private const val WATCHDOG_WORK_TIMEOUT_MS = 8_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1000L
        private const val RESTART_RETRY_INTERVAL_MS = 2 * 60 * 1000L
    }

    private fun scheduleNextWatchdog(context: Context, delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextIntent = Intent(context, ServiceWatchdogReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }

        Timber.d("Watchdog re-armed for ${delayMs / 60_000L}m")
    }

    override fun onReceive(context: Context, intent: Intent) {
        // BroadcastReceivers are time-limited; avoid blocking the main thread.
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(WATCHDOG_WORK_TIMEOUT_MS) {
                    val prefsRepo = PreferencesRepository(appContext)

                    // Check if monitoring should be active (async DataStore read).
                    if (!prefsRepo.isMonitoring.first()) {
                        Timber.d("Monitoring is disabled - skipping watchdog")
                        return@withTimeoutOrNull
                    }

                    Timber.d("Watchdog triggered - checking service health")

                    // Check if we are restarting too frequently (boot loop protection).
                    val lastRestart = prefsRepo.lastRestartAttempt.first()
                    val restartCountValue = prefsRepo.restartCount.first()
                    val now = System.currentTimeMillis()

                    // Reset counter if outside the window.
                    val actualRestartCount = if ((now - lastRestart) > RESTART_WINDOW_MS) {
                        0
                    } else {
                        restartCountValue
                    }

                    // Check if we have restarted too many times.
                    if (actualRestartCount >= MAX_RESTARTS_IN_WINDOW) {
                        Timber.e("Too many restart attempts ($actualRestartCount in 5 min) - waiting before retry")
                        showRestartNotification(appContext, "Service restart limit reached - tap to restart manually")
                        scheduleNextWatchdog(appContext, RESTART_RETRY_INTERVAL_MS)
                        return@withTimeoutOrNull
                    }

                    val serviceWasRunning = TremorService.isRunning
                    var nextWatchdogDelayMs = if (serviceWasRunning) {
                        HEALTH_CHECK_INTERVAL_MS
                    } else {
                        RESTART_RETRY_INTERVAL_MS
                    }

                    suspend fun persistRestartAttempt(reason: String) {
                        try {
                            prefsRepo.updateRestartAttempt(now, actualRestartCount + 1)
                            Timber.w("$reason; restart attempt ${actualRestartCount + 1}/$MAX_RESTARTS_IN_WINDOW in current window")
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update watchdog restart tracking: ${e.message}")
                        }
                    }

                    // ES-08: RACE CONDITION FIX: Always ping service to perform health checks
                    // The isRunning flag can't reliably indicate service health (sensors could be frozen)
                    // Always calling startService will trigger onStartCommand which performs health checks
                    // and handles the case where service is already running (just calls onStartCommand again)
                    Timber.d("Watchdog pinging service for health check (serviceRunning=$serviceWasRunning)")

                    // Always try to ping/restart the service to keep it alive (triggers onStartCommand).
                    try {
                        val serviceIntent = Intent(appContext, TremorService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(serviceIntent)
                        } else {
                            appContext.startService(serviceIntent)
                        }
                        Timber.d("Pinged service successfully")

                        if (!serviceWasRunning) {
                            persistRestartAttempt("Watchdog restarted service from stopped state")
                        } else if (actualRestartCount > 0) {
                            // Service is healthy again; clear stale restart-window counters.
                            prefsRepo.updateRestartAttempt(0L, 0)
                            Timber.d("Watchdog restart counter reset after healthy ping")
                        }
                    } catch (e: Exception) {
                        nextWatchdogDelayMs = RESTART_RETRY_INTERVAL_MS
                        // On Android 12+, foreground service start from background might fail.
                        Timber.e(e, "Failed to ping/restart service: ${e.message}")
                        showRestartNotification(appContext, "Monitoring stopped - tap to restart")
                        persistRestartAttempt("Watchdog ping failed")
                    }

                    // Keep watchdog continuity even if service start fails or onStartCommand is delayed.
                    scheduleNextWatchdog(appContext, nextWatchdogDelayMs)
                }

                if (completed == null) {
                    Timber.w("Watchdog work timed out (> ${WATCHDOG_WORK_TIMEOUT_MS}ms); skipping")
                    scheduleNextWatchdog(appContext, RESTART_RETRY_INTERVAL_MS)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showRestartNotification(context: Context, reason: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "watchdog_channel",
                "Service Watchdog",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when service needs restart"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create intent to open the app
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "watchdog_channel")
            .setContentTitle("Tremor Monitor")
            .setContentText("Tap to restart monitoring - $reason")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(998, notification)
    }
}
