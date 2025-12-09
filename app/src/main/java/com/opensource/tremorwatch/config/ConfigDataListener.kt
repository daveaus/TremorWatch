package com.opensource.tremorwatch.config

import android.content.Context
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import com.google.android.gms.wearable.*
import timber.log.Timber

/**
 * Listens for configuration updates from phone via Wearable Data Layer.
 *
 * Thread-safe implementation:
 * - Uses callback pattern with thread-safe config delivery
 * - Caches config in SharedPreferences for persistence
 * - Handles errors gracefully with fallback to cached config
 *
 * @param context Application context
 * @param onConfigChanged Callback invoked when config changes (called on main thread)
 */
class ConfigDataListener(
    private val context: Context,
    private val onConfigChanged: (TremorDetectionConfig) -> Unit
) : DataClient.OnDataChangedListener {

    companion object {
        private const val DATA_PATH = "/tremor_detection_config"
        private const val PREFS_NAME = "tremor_detection_config_cache"
        private const val KEY_CONFIG = "cached_config"
        private const val KEY_LAST_UPDATE = "last_update_time"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dataClient = Wearable.getDataClient(context)

    /**
     * Register this listener and load cached config.
     * Should be called when the service/activity starts.
     */
    fun register() {
        dataClient.addListener(this)
        Timber.i("ConfigDataListener registered")

        // Load and apply cached config on startup
        getCachedConfig()?.let { cachedConfig ->
            Timber.i("Applying cached config on startup: ${cachedConfig.profileName}")
            onConfigChanged(cachedConfig)
        } ?: run {
            // No cached config, use default
            Timber.i("No cached config found, using default")
            val defaultConfig = TremorDetectionConfig()
            cacheConfig(defaultConfig)
            onConfigChanged(defaultConfig)
        }
    }

    /**
     * Unregister this listener.
     * Should be called when the service/activity stops.
     */
    fun unregister() {
        dataClient.removeListener(this)
        Timber.i("ConfigDataListener unregistered")
    }

    /**
     * Called when data changes on the Wearable Data Layer.
     * Processes config updates from phone.
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                processDataChange(event.dataItem)
            }
        }
        dataEvents.release()
    }

    /**
     * Process a single data item change
     */
    private fun processDataChange(dataItem: DataItem) {
        if (dataItem.uri.path != DATA_PATH) {
            return // Not a config update
        }

        try {
            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            val configJson = dataMap.getString("config_json")
            val timestamp = dataMap.getLong("timestamp", 0L)
            val profileName = dataMap.getString("profile_name") ?: "Unknown"
            val version = dataMap.getInt("version", 1)

            if (configJson.isNullOrBlank()) {
                Timber.w("Received empty config JSON, ignoring")
                return
            }

            // Validate and parse config
            val config = try {
                TremorDetectionConfig.fromJson(configJson)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse config, falling back to cached/default")
                // Return cached config or default on parse error
                getCachedConfig() ?: TremorDetectionConfig()
            }

            // Cache the new config for persistence
            cacheConfig(config, timestamp)

            Timber.i(
                "Received new config: '$profileName' (v$version) at ${
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(timestamp))
                }"
            )

            // Notify callback (thread-safe - callback handles thread dispatch)
            onConfigChanged(config)

        } catch (e: Exception) {
            Timber.e(e, "Error processing config data change")
            // On error, continue using current config (no crash)
        }
    }

    /**
     * Get the cached configuration.
     * Returns null if no config is cached or parsing fails.
     */
    fun getCachedConfig(): TremorDetectionConfig? {
        val json = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            TremorDetectionConfig.fromJson(json)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse cached config")
            null
        }
    }

    /**
     * Get the timestamp of the last config update
     */
    fun getLastUpdateTime(): Long {
        return prefs.getLong(KEY_LAST_UPDATE, 0L)
    }

    /**
     * Cache the configuration for persistence across restarts
     */
    private fun cacheConfig(config: TremorDetectionConfig, timestamp: Long = System.currentTimeMillis()) {
        try {
            prefs.edit()
                .putString(KEY_CONFIG, config.toJson())
                .putLong(KEY_LAST_UPDATE, timestamp)
                .apply()
            Timber.d("Config cached: ${config.profileName}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cache config")
            // Non-fatal - config still applied in memory
        }
    }

    /**
     * Manually set a configuration (for testing or fallback)
     */
    fun setConfig(config: TremorDetectionConfig) {
        cacheConfig(config)
        onConfigChanged(config)
        Timber.i("Config manually set: ${config.profileName}")
    }
}
