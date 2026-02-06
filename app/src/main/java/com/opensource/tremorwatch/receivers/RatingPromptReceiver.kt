package com.opensource.tremorwatch.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
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
        private const val DEFAULT_MAX_DAILY_PROMPTS = 6
        private const val REQUEST_CODE = 3  // Same as used in TremorService

        /**
         * Schedule the next rating prompt alarm.
         * Called after each prompt fires to schedule the next one.
         */
        fun scheduleNextPrompt(context: Context) {
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

            // Random interval between 2-3 hours for natural prompting
            val baseIntervalMs = 2 * 60 * 60 * 1000L  // 2 hours
            val randomExtra = (0..60).random() * 60 * 1000L  // 0-60 minutes extra
            val intervalMs = baseIntervalMs + randomExtra
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
    }


    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("Rating prompt receiver triggered")
        
        // Always schedule the next prompt first, regardless of whether we show this one
        scheduleNextPrompt(context)
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        
        // Check "don't ask today" setting
        val dontAskDate = prefs.getString(KEY_DONT_ASK_DATE, null)
        if (dontAskDate == today) {
            Timber.d("Rating prompt skipped - user set 'don't ask today'")
            return
        }
        
        // Check daily prompt limit
        val promptsTodayDate = prefs.getString(KEY_PROMPTS_TODAY_DATE, null)
        var promptsToday = if (promptsTodayDate == today) {
            prefs.getInt(KEY_PROMPTS_TODAY, 0)
        } else {
            // New day, reset counter
            prefs.edit().putString(KEY_PROMPTS_TODAY_DATE, today).apply()
            0
        }
        
        val maxDailyPrompts = prefs.getInt(KEY_MAX_DAILY_PROMPTS, DEFAULT_MAX_DAILY_PROMPTS)
        if (promptsToday >= maxDailyPrompts) {
            Timber.d("Rating prompt skipped - daily limit reached ($promptsToday/$maxDailyPrompts)")
            return
        }
        
        // Check active hours
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val startHour = prefs.getInt(KEY_ACTIVE_HOURS_START, 6)
        val endHour = prefs.getInt(KEY_ACTIVE_HOURS_END, 22)
        
        if (currentHour < startHour || currentHour >= endHour) {
            Timber.d("Rating prompt skipped - outside active hours ($currentHour not in $startHour-$endHour)")
            return
        }
        
        // Increment prompt count
        prefs.edit().putInt(KEY_PROMPTS_TODAY, promptsToday + 1).apply()
        
        // Launch rating activity
        Timber.i("Showing rating prompt (${promptsToday + 1}/$maxDailyPrompts today)")
        launchRatingScreen(context, "PROMPTED")
    }
    
    private fun launchRatingScreen(context: Context, source: String) {
        try {
            val activityIntent = Intent().apply {
                setClassName(context.packageName, "com.opensource.tremorwatch.RatingActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("source", source)
            }
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch rating screen: ${e.message}")
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
