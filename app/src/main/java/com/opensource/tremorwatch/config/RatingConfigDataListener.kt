package com.opensource.tremorwatch.config

import android.content.Context
import android.net.Uri
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.RatingConfig
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Listens for subjective rating configuration updates from phone via Wearable Data Layer.
 */
class RatingConfigDataListener(
    private val context: Context,
    private val onConfigChanged: (RatingConfig) -> Unit
) : DataClient.OnDataChangedListener {

    companion object {
        private const val PREFS_NAME = "rating_config_cache"
        private const val KEY_CONFIG_JSON = "cached_rating_config_json"
        private const val KEY_LAST_UPDATE = "last_update_ms"
    }

    private val dataClient = Wearable.getDataClient(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun register() {
        dataClient.addListener(this)
        Timber.i("RatingConfigDataListener registered")

        // Apply cached config immediately if available, otherwise keep existing
        // watch-side prefs until a fresh config arrives from phone.
        val cached = getCachedConfig()
        if (cached != null) {
            Timber.i(
                "Applying cached rating config on startup " +
                    "(minInterval=${cached.promptFrequencyMinutes}m, maxDaily=${cached.maxPromptsPerDay})"
            )
            onConfigChanged(cached)
        } else {
            Timber.i("No cached rating config found on watch; retaining existing local prompt settings")
        }

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
                    Timber.i("No existing rating config data item found; keeping cached/default config")
                }
            }
            .addOnFailureListener { e ->
                val api = e as? ApiException
                if (api != null && api.statusCode == CommonStatusCodes.ERROR) {
                    // This is common at startup before Data Layer is fully ready.
                    Timber.w("Rating config not available yet on Data Layer (status=13); using cached/default config")
                } else {
                    Timber.e(e, "Failed to read existing rating config data item")
                }
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
            cacheConfig(config)
            Timber.i("Received rating config from phone (minInterval=${config.promptFrequencyMinutes}m, maxDaily=${config.maxPromptsPerDay})")
            onConfigChanged(config)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse rating config from DataMap")
        }
    }

    private fun getCachedConfig(): RatingConfig? {
        val json = prefs.getString(KEY_CONFIG_JSON, null) ?: return null
        return try {
            this.json.decodeFromString(RatingConfig.serializer(), json)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse cached rating config, ignoring cache")
            null
        }
    }

    private fun cacheConfig(config: RatingConfig) {
        try {
            prefs.edit()
                .putString(KEY_CONFIG_JSON, json.encodeToString(RatingConfig.serializer(), config))
                .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Timber.w(e, "Failed to cache rating config")
        }
    }
}
