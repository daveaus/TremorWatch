package com.opensource.tremorwatch.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber
import androidx.core.app.NotificationCompat
import com.opensource.tremorwatch.MainActivity
import com.opensource.tremorwatch.service.TremorService
import com.opensource.tremorwatch.config.MonitoringState
import com.opensource.tremorwatch.data.PreferencesRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

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
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Check if monitoring should be active
        if (MonitoringState.isMonitoring(context)) {
            Timber.d("Watchdog triggered - checking service health")

            // Check if we're restarting too frequently (boot loop protection)
            val prefsRepo = PreferencesRepository(context)
            val lastRestart = runBlocking { prefsRepo.lastRestartAttempt.first() }
            val restartCountValue = runBlocking { prefsRepo.restartCount.first() }
            val now = System.currentTimeMillis()

            // Reset counter if outside the window
            val actualRestartCount = if ((now - lastRestart) > RESTART_WINDOW_MS) {
                0
            } else {
                restartCountValue
            }

            // Check if we've restarted too many times
            if (actualRestartCount >= MAX_RESTARTS_IN_WINDOW) {
                Timber.e("Too many restart attempts ($actualRestartCount in 5 min) - waiting before retry")
                showRestartNotification(context, "Service restart limit reached - tap to restart manually")
                return
            }

            // Always try to ping/restart the service to keep it alive (triggers onStartCommand)
            try {
                val serviceIntent = Intent(context, TremorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Timber.d("Pinged service successfully")

                // Update restart tracking using PreferencesRepository
                runBlocking {
                    prefsRepo.updateRestartAttempt(now, actualRestartCount + 1)
                }

            } catch (e: Exception) {
                // On Android 12+, foreground service start from background might fail
                Timber.e(e, "Failed to ping/restart service: ${e.message}")

                // Increment failure counter using PreferencesRepository
                runBlocking {
                    prefsRepo.updateRestartAttempt(now, actualRestartCount + 1)
                }

                // Show notification to prompt user
                showRestartNotification(context, "Monitoring stopped - tap to restart")
            }
        } else {
            Timber.d("Monitoring is disabled - skipping watchdog")
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

