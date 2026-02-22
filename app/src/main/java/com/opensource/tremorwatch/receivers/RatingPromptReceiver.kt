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
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * Broadcast receiver that triggers scheduled rating prompts.
 * 
 * Checks if within active hours and not in "don't ask today" mode
 * before launching the rating screen.
 */
class RatingPromptReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RATING_PROMPT = "com.opensource.tremorwatch.ACTION_RATING_PROMPT"
        private const val PREFS_NAME = "rating_prefs"
        private const val KEY_DONT_ASK_DATE = "dont_ask_date"
        private const val KEY_ACTIVE_HOURS_START = "active_hours_start"
        private const val KEY_ACTIVE_HOURS_END = "active_hours_end"
        private const val KEY_PROMPTS_TODAY = "prompts_today"
        private const val KEY_PROMPTS_TODAY_DATE = "prompts_today_date"
        private const val KEY_MAX_DAILY_PROMPTS = "max_daily_prompts"
        private const val KEY_MIN_INTERVAL_MINUTES = "min_interval_minutes"
        private const val KEY_PROMPTS_ENABLED = "prompts_enabled"
        private const val KEY_NEXT_PROMPT_ELAPSED = "next_prompt_elapsed"
        private const val KEY_PROMPT_VIBRATION_ENABLED = "prompt_vibration_enabled"
        private const val KEY_PROMPT_VIBRATION_STRONG = "prompt_vibration_strong"
        private const val KEY_PROMPT_FOLLOWUP_VIBRATION = "prompt_followup_vibration"
        // Sensor-activity gate: shared with TremorService.KEY_LAST_SIGNIFICANT_DETECTION_MS
        private const val KEY_LAST_SIGNIFICANT_DETECTION_MS = "last_significant_detection_ms"
        private const val DETECTION_STALENESS_GATE_MS = 5 * 60 * 1000L // 5 minutes
        private const val DEFAULT_MAX_DAILY_PROMPTS = 6
        private const val DEFAULT_MIN_INTERVAL_MINUTES = 60
        private const val REQUEST_CODE = 3  // Same as used in TremorService
        private const val RATING_CHANNEL_ID = "rating_prompts"

        /**
         * Schedule the next rating prompt alarm.
         * Called after each prompt fires to schedule the next one.
         */
        fun scheduleNextPrompt(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val promptsEnabled = prefs.getBoolean(KEY_PROMPTS_ENABLED, true)
            if (!promptsEnabled) {
                Timber.i("Rating prompts disabled - not scheduling next prompt")
                cancelPrompt(context)
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RatingPromptReceiver::class.java).apply {
                action = ACTION_RATING_PROMPT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val minIntervalMinutes = prefs.getInt(KEY_MIN_INTERVAL_MINUTES, DEFAULT_MIN_INTERVAL_MINUTES)
            val safeIntervalMinutes = maxOf(15, minIntervalMinutes)
            val intervalMs = safeIntervalMinutes * 60 * 1000L
            val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMs

            // Calculate wall clock time for logging
            val triggerTime = System.currentTimeMillis() + intervalMs
            val triggerTimeFormatted = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(triggerTime))
            val intervalMinutes = intervalMs / (60 * 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    Timber.i("★★★ Next rating prompt scheduled (exact) - in $intervalMinutes minutes at ~$triggerTimeFormatted")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    Timber.w("★★★ Next rating prompt scheduled (inexact) - in ~$intervalMinutes minutes around $triggerTimeFormatted")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Timber.i("★★★ Next rating prompt scheduled (exact) - in $intervalMinutes minutes at ~$triggerTimeFormatted")
            }
        }

        fun cancelPrompt(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RatingPromptReceiver::class.java).apply {
                action = ACTION_RATING_PROMPT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Timber.i("Rating prompt alarm cancelled")
        }

        fun applyConfig(
            context: Context,
            promptsEnabled: Boolean,
            minIntervalMinutes: Int,
            maxDailyPrompts: Int,
            activeStartHour: Int,
            activeEndHour: Int,
            promptVibrationEnabled: Boolean = true,
            promptVibrationStrong: Boolean = false,
            promptFollowupVibration: Boolean = false,
            scheduleAlarm: Boolean = true
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_PROMPTS_ENABLED, promptsEnabled)
                .putInt(KEY_MIN_INTERVAL_MINUTES, minIntervalMinutes)
                .putInt(KEY_MAX_DAILY_PROMPTS, maxDailyPrompts)
                .putInt(KEY_ACTIVE_HOURS_START, activeStartHour)
                .putInt(KEY_ACTIVE_HOURS_END, activeEndHour)
                .putBoolean(KEY_PROMPT_VIBRATION_ENABLED, promptVibrationEnabled)
                .putBoolean(KEY_PROMPT_VIBRATION_STRONG, promptVibrationStrong)
                .putBoolean(KEY_PROMPT_FOLLOWUP_VIBRATION, promptFollowupVibration)
                .apply()

            if (scheduleAlarm) {
                if (promptsEnabled) {
                    scheduleNextPrompt(context)
                } else {
                    cancelPrompt(context)
                }
            }
        }
    }


    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("Rating prompt receiver triggered")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val promptsEnabled = prefs.getBoolean(KEY_PROMPTS_ENABLED, true)
        if (!promptsEnabled) {
            Timber.i("Rating prompt skipped - prompts disabled")
            cancelPrompt(context)
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val nextElapsed = prefs.getLong(KEY_NEXT_PROMPT_ELAPSED, 0L)
        if (nextElapsed > nowElapsed) {
            Timber.d("Rating prompt skipped - next prompt not due yet")
            return
        }

        // Always schedule the next prompt first, regardless of whether we show this one
        scheduleNextPrompt(context)

        val minIntervalMinutes = prefs.getInt(KEY_MIN_INTERVAL_MINUTES, DEFAULT_MIN_INTERVAL_MINUTES)
        val safeIntervalMinutes = maxOf(15, minIntervalMinutes)
        val intervalMs = safeIntervalMinutes * 60 * 1000L
        val nextPromptElapsed = nowElapsed + intervalMs

        // Helper to advance next_prompt_elapsed even when skipping
        fun advanceNextPrompt(reason: String) {
            prefs.edit().putLong(KEY_NEXT_PROMPT_ELAPSED, nextPromptElapsed).apply()
            Timber.d("Rating prompt skipped - $reason. Next check in ${safeIntervalMinutes}m")
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        
        // Check "don't ask today" setting
        val dontAskDate = prefs.getString(KEY_DONT_ASK_DATE, null)
        if (dontAskDate == today) {
            advanceNextPrompt("user set 'don't ask today'")
            return
        }
        
        // Check daily prompt limit
        val promptsTodayDate = prefs.getString(KEY_PROMPTS_TODAY_DATE, null)
        var promptsToday = if (promptsTodayDate == today) {
            prefs.getInt(KEY_PROMPTS_TODAY, 0)
        } else {
            // New day, reset counter
            prefs.edit()
                .putString(KEY_PROMPTS_TODAY_DATE, today)
                .putInt(KEY_PROMPTS_TODAY, 0)
                .apply()
            0
        }
        
        val maxDailyPrompts = prefs.getInt(KEY_MAX_DAILY_PROMPTS, DEFAULT_MAX_DAILY_PROMPTS)
        if (promptsToday >= maxDailyPrompts) {
            // Keep periodic checks so prompts automatically resume after date rollover.
            advanceNextPrompt("daily limit reached ($promptsToday/$maxDailyPrompts)")
            return
        }
        
        // Check active hours
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val startHour = prefs.getInt(KEY_ACTIVE_HOURS_START, 6)
        val endHour = prefs.getInt(KEY_ACTIVE_HOURS_END, 22)

        if (currentHour < startHour || currentHour >= endHour) {
            advanceNextPrompt("outside active hours ($currentHour not in $startHour-$endHour)")
            return
        }

        // Sensor-activity gate: skip prompts if TremorService hasn't recorded any sensor
        // data in the last 5 minutes. This avoids prompting when the watch is off-wrist,
        // monitoring is paused, or the service hasn't started yet after a reboot.
        val lastDetectionMs = prefs.getLong(KEY_LAST_SIGNIFICANT_DETECTION_MS, 0L)
        val timeSinceDetectionMs = System.currentTimeMillis() - lastDetectionMs
        if (lastDetectionMs == 0L || timeSinceDetectionMs > DETECTION_STALENESS_GATE_MS) {
            advanceNextPrompt(
                "no sensor activity in last 5m (last=${timeSinceDetectionMs / 1000}s ago)"
            )
            return
        }

        // Increment prompt count and record next prompt time
        prefs.edit()
            .putInt(KEY_PROMPTS_TODAY, promptsToday + 1)
            .putLong(KEY_NEXT_PROMPT_ELAPSED, nextPromptElapsed)
            .commit()

        // Launch rating activity via full-screen notification
        Timber.i("Showing rating prompt (${promptsToday + 1}/$maxDailyPrompts today)")
        showRatingNotification(context, "PROMPTED")
    }
    
    private fun ensureRatingChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                RATING_CHANNEL_ID,
                "Rating Prompts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Subjective rating prompts"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun showRatingNotification(context: Context, source: String) {
        try {
            ensureRatingChannel(context)
            val activityIntent = Intent().apply {
                setClassName(context.packageName, "com.opensource.tremorwatch.RatingActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra("source", source)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, RATING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Time to rate")
                .setContentText("How are you feeling?")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(REQUEST_CODE, notification)
        } catch (e: Exception) {
            Timber.e(e, "Failed to show rating notification: ${e.message}")
        }
    }
    
    /**
     * Call this when user selects "Don't ask again today"
     */
    fun setDontAskToday(context: Context) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DONT_ASK_DATE, today)
            .apply()
        Timber.i("User set 'don't ask today' for $today")
    }
    
    /**
     * Update active hours configuration (synced from phone)
     */
    fun updateActiveHours(context: Context, startHour: Int, endHour: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACTIVE_HOURS_START, startHour)
            .putInt(KEY_ACTIVE_HOURS_END, endHour)
            .apply()
        Timber.d("Updated active hours: $startHour - $endHour")
    }
    
    /**
     * Update max daily prompts configuration (synced from phone)
     */
    fun updateMaxDailyPrompts(context: Context, maxPrompts: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_DAILY_PROMPTS, maxPrompts)
            .apply()
        Timber.d("Updated max daily prompts: $maxPrompts")
    }
}
