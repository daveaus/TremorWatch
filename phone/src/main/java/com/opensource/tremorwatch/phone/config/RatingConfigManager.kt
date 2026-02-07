package com.opensource.tremorwatch.phone.config

import android.content.Context
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.RatingConfig
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Syncs subjective rating configuration to the watch.
 */
class RatingConfigManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("rating_config", Context.MODE_PRIVATE)
    private val dataClient: DataClient = Wearable.getDataClient(context)

    enum class SyncStatus {
        SYNCED,
        FAILED
    }

    private fun buildConfigFromPrefs(): RatingConfig {
        return RatingConfig(
            promptsEnabled = prefs.getBoolean("prompts_enabled", true),
            promptFrequencyMinutes = prefs.getInt("min_interval_minutes", 60),
            smartTriggerEnabled = prefs.getBoolean("smart_triggers_enabled", true),
            activeHoursStart = prefs.getInt("active_start_hour", 8),
            activeHoursEnd = prefs.getInt("active_end_hour", 22),
            maxPromptsPerDay = prefs.getInt("daily_max_prompts", 5),
            calibrationModeEnabled = prefs.getBoolean("calibration_enabled", false),
            calibrationDurationSeconds = prefs.getInt("calibration_duration_seconds", 60),
            showRatingsOnChart = prefs.getBoolean("show_ratings_on_graph", true),
            schemaVersion = RatingConfig.CURRENT_SCHEMA_VERSION
        )
    }

    suspend fun syncToWatch(): SyncStatus {
        return try {
            val config = buildConfigFromPrefs()
            val request = PutDataMapRequest.create(Constants.MESSAGE_PATH_RATING_CONFIG).apply {
                dataMap.putBoolean("prompts_enabled", config.promptsEnabled)
                dataMap.putInt("min_interval_minutes", config.promptFrequencyMinutes)
                dataMap.putBoolean("smart_triggers_enabled", config.smartTriggerEnabled)
                dataMap.putInt("active_start_hour", config.activeHoursStart)
                dataMap.putInt("active_end_hour", config.activeHoursEnd)
                dataMap.putInt("daily_max_prompts", config.maxPromptsPerDay)
                dataMap.putBoolean("calibration_enabled", config.calibrationModeEnabled)
                dataMap.putInt("calibration_duration_seconds", config.calibrationDurationSeconds)
                dataMap.putBoolean("show_ratings_on_graph", config.showRatingsOnChart)
                dataMap.putInt("schema_version", config.schemaVersion)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }

            withContext(Dispatchers.IO) {
                Tasks.await(dataClient.putDataItem(request.asPutDataRequest().setUrgent()))
            }

            Timber.i("Rating config synced to watch (interval=${config.promptFrequencyMinutes}m, maxDaily=${config.maxPromptsPerDay})")
            SyncStatus.SYNCED
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync rating config to watch")
            SyncStatus.FAILED
        }
    }
}
