package com.opensource.tremorwatch.config

import android.content.Context
import android.net.Uri
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.RatingConfig
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import timber.log.Timber

/**
 * Listens for subjective rating configuration updates from phone via Wearable Data Layer.
 */
class RatingConfigDataListener(
    private val context: Context,
    private val onConfigChanged: (RatingConfig) -> Unit
) : DataClient.OnDataChangedListener {

    private val dataClient = Wearable.getDataClient(context)

    fun register() {
        dataClient.addListener(this)
        Timber.i("RatingConfigDataListener registered")

        // Fetch latest config if it already exists on the Data Layer
        val uri = Uri.Builder()
            .scheme("wear")
            .authority("*")
            .path(Constants.MESSAGE_PATH_RATING_CONFIG)
            .build()
        dataClient.getDataItem(uri)
            .addOnSuccessListener { dataItem ->
                if (dataItem != null) {
                    Timber.i("Loaded existing rating config data item")
                    handleDataItem(dataItem)
                } else {
                    Timber.i("No existing rating config data item found")
                }
            }
            .addOnFailureListener { e ->
                Timber.e(e, "Failed to read existing rating config data item")
            }
    }

    fun unregister() {
        dataClient.removeListener(this)
        Timber.i("RatingConfigDataListener unregistered")
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                if (dataItem.uri.path == Constants.MESSAGE_PATH_RATING_CONFIG) {
                    handleDataItem(dataItem)
                }
            }
        }
        dataEvents.release()
    }

    private fun handleDataItem(dataItem: com.google.android.gms.wearable.DataItem) {
        try {
            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            val config = RatingConfig(
                promptsEnabled = dataMap.getBoolean("prompts_enabled", true),
                promptFrequencyMinutes = dataMap.getInt(
                    "min_interval_minutes",
                    RatingConfig().promptFrequencyMinutes
                ),
                smartTriggerEnabled = dataMap.getBoolean("smart_triggers_enabled", false),
                activeHoursStart = dataMap.getInt("active_start_hour", RatingConfig().activeHoursStart),
                activeHoursEnd = dataMap.getInt("active_end_hour", RatingConfig().activeHoursEnd),
                maxPromptsPerDay = dataMap.getInt("daily_max_prompts", RatingConfig().maxPromptsPerDay),
                promptVibrationEnabled = dataMap.getBoolean("prompt_vibration_enabled", true),
                promptVibrationStrong = dataMap.getBoolean("prompt_vibration_strong", false),
                promptFollowupVibration = dataMap.getBoolean("prompt_followup_vibration", false),
                calibrationModeEnabled = dataMap.getBoolean("calibration_enabled", false),
                calibrationDurationSeconds = dataMap.getInt(
                    "calibration_duration_seconds",
                    RatingConfig().calibrationDurationSeconds
                ),
                showRatingsOnChart = dataMap.getBoolean("show_ratings_on_graph", true),
                schemaVersion = dataMap.getInt("schema_version", RatingConfig.CURRENT_SCHEMA_VERSION)
            )
            Timber.i("Received rating config from phone (minInterval=${config.promptFrequencyMinutes}m, maxDaily=${config.maxPromptsPerDay})")
            onConfigChanged(config)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse rating config from DataMap")
        }
    }
}
