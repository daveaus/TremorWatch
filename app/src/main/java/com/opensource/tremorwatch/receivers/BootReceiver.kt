package com.opensource.tremorwatch.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.opensource.tremorwatch.MainActivity
import com.opensource.tremorwatch.service.TremorService
import com.opensource.tremorwatch.config.MonitoringState

/**
 * Broadcast receiver that handles device boot completion.
 * 
 * Automatically restarts the TremorService if monitoring was active
 * before the device rebooted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only auto-start if monitoring was on before reboot
            if (MonitoringState.isMonitoring(context)) {
                // On Android 12+, starting foreground services from background is restricted
                // Try to start, but if it fails, show notification to prompt user
                try {
                    val serviceIntent = Intent(context, TremorService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    // Failed to start service (Android 12+ restriction)
                    // Show notification to prompt user to open app
                    showRestartNotification(context)
                }
            }
        }
    }

    private fun showRestartNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for boot notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "boot_channel",
                "Boot Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for service restart after boot"
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

        val notification = NotificationCompat.Builder(context, "boot_channel")
            .setContentTitle("Tremor Monitor")
            .setContentText("Tap to restart monitoring")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(999, notification)
    }
}

