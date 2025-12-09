package com.opensource.tremorwatch.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.content.ClipData
import android.content.ClipboardManager

/**
 * Helper class for managing TremorWatch notifications
 */
object NotificationHelper {

    const val CHANNEL_ID = "tremorwatch_service"
    const val NOTIFICATION_ID = 1001

    private const val PREFS_NAME = "tremorwatch_prefs"
    private const val KEY_LAST_RECEIVED = "last_received_timestamp"

    /**
     * Create notification channel (required for Android 8.0+)
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "TremorWatch Service"
            val descriptionText = "Shows data reception and upload status"
            val importance = NotificationManager.IMPORTANCE_LOW  // Low importance = no sound
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Build notification for the service
     */
    fun buildServiceNotification(
        context: Context,
        status: ServiceStatus,
        additionalInfo: String? = null
    ): Notification {
        createNotificationChannel(context)

        // Create intent to open MainActivity when notification is tapped
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (status) {
            ServiceStatus.RECEIVING -> "📥 Receiving Data"
            ServiceStatus.UPLOADING -> "📤 Uploading Data"
            ServiceStatus.IDLE -> "✓ TremorWatch Active"
            ServiceStatus.WAITING -> "⏳ Waiting for Home Network"
        }

        val contentText = buildContentText(context, status, additionalInfo)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // You can replace with custom icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // Cannot be dismissed by user
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Build content text showing last received time and additional info
     */
    private fun buildContentText(context: Context, status: ServiceStatus, additionalInfo: String?): String {
        val lastReceived = getLastReceivedTime(context)
        val timeAgo = if (lastReceived > 0) {
            formatTimeAgo(System.currentTimeMillis() - lastReceived)
        } else {
            "Never"
        }

        val parts = mutableListOf<String>()

        when (status) {
            ServiceStatus.RECEIVING -> {
                parts.add("Receiving from watch...")
            }
            ServiceStatus.UPLOADING -> {
                parts.add(additionalInfo ?: "Uploading to InfluxDB...")
            }
            ServiceStatus.WAITING -> {
                // Use additionalInfo if provided, otherwise check actual network status
                if (additionalInfo != null) {
                    parts.add(additionalInfo)
                } else {
                    // Check actual network status
                    val isOnHomeNetwork = PhoneNetworkDetector.isOnHomeNetwork(context)
                    val hasNetwork = PhoneNetworkDetector.isNetworkAvailable(context)
                    when {
                        !hasNetwork -> parts.add("No network available")
                        !isOnHomeNetwork -> parts.add("Not on home network")
                        else -> parts.add("Waiting...")
                    }
                }
            }
            ServiceStatus.IDLE -> {
                parts.add("Ready to receive")
            }
        }

        parts.add("Last received: $timeAgo")

        return parts.joinToString(" • ")
    }

    /**
     * Update the notification with new status
     */
    fun updateNotification(context: Context, status: ServiceStatus, additionalInfo: String? = null) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildServiceNotification(context, status, additionalInfo)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Save timestamp when data was last received
     */
    fun recordDataReceived(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_RECEIVED, System.currentTimeMillis()).apply()
    }

    /**
     * Get timestamp when data was last received
     */
    fun getLastReceivedTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_RECEIVED, 0)
    }

    /**
     * Format milliseconds into human-readable "time ago" string
     */
    private fun formatTimeAgo(millisAgo: Long): String {
        return when {
            millisAgo < 0 -> "Just now"
            millisAgo < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            millisAgo < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(millisAgo)
                "${minutes}m ago"
            }
            millisAgo < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(millisAgo)
                "${hours}h ago"
            }
            else -> {
                val days = TimeUnit.MILLISECONDS.toDays(millisAgo)
                "${days}d ago"
            }
        }
    }

    /**
     * Cancel the service notification
     */
    fun cancelNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}

/**
 * Service status enum
 */
enum class ServiceStatus {
    RECEIVING,   // Receiving data from watch
    UPLOADING,   // Uploading to InfluxDB
    WAITING,     // Waiting for home network
    IDLE         // Ready but not active
}
