package com.opensource.tremorwatch.phone.config

import android.content.Context
import android.content.SharedPreferences
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Manages tremor detection configuration profiles.
 * Handles storage, sync to watch, import/export, and error recovery.
 *
 * Features:
 * - Profile management (save/load/delete)
 * - Reliable sync to watch with retry logic
 * - Import/Export with validation
 * - Sync status tracking
 * - Error handling and fallback strategies
 */
class TremorConfigManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tremor_config", Context.MODE_PRIVATE)

    private val dataClient: DataClient = Wearable.getDataClient(context)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Retry configuration
    private val maxRetries = 3
    private val retryDelayMs = 1000L
    private val exponentialBackoffMultiplier = 2.0

    companion object {
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_PROFILES = "saved_profiles"
        private const val KEY_SYNC_STATUS = "sync_status"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val DATA_PATH = "/tremor_detection_config"
    }

    /**
     * Sync status for UI feedback
     */
    enum class SyncStatus {
        SYNCED,      // Successfully synced
        PENDING,     // Not yet synced or sync in progress
        FAILED,      // Sync failed after retries
        NOT_CONNECTED // Watch not connected
    }

    /**
     * Get the currently active configuration.
     * Falls back to default if none exists or parsing fails.
     */
    fun getActiveConfig(): TremorDetectionConfig {
        val json = prefs.getString(KEY_ACTIVE_PROFILE, null)
        return if (json != null) {
            try {
                TremorDetectionConfig.fromJson(json)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse active config, using default")
                TremorDetectionConfig() // Fallback to default
            }
        } else {
            TremorDetectionConfig()
        }
    }

    /**
     * Set and sync the active configuration.
     * Returns SyncStatus indicating success/failure.
     *
     * @param config The configuration to activate
     * @param forceSync If true, sync even if watch is disconnected (will queue for later)
     * @return SyncStatus indicating result
     */
    suspend fun setActiveConfig(
        config: TremorDetectionConfig,
        forceSync: Boolean = true
    ): SyncStatus {
        // Save to local storage first (always succeeds)
        prefs.edit().putString(KEY_ACTIVE_PROFILE, config.toJson()).apply()
        Timber.i("Active config saved: ${config.profileName}")

        // Attempt to sync to watch
        return if (forceSync) {
            val status = syncToWatch(config)
            updateSyncStatus(status)
            status
        } else {
            SyncStatus.PENDING
        }
    }

    /**
     * Get current sync status
     */
    fun getSyncStatus(): SyncStatus {
        val statusName = prefs.getString(KEY_SYNC_STATUS, SyncStatus.PENDING.name)
        return try {
            SyncStatus.valueOf(statusName ?: SyncStatus.PENDING.name)
        } catch (e: IllegalArgumentException) {
            SyncStatus.PENDING
        }
    }

    /**
     * Get last successful sync time
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    /**
     * Force a manual sync of the current active config to watch.
     * Useful when sync previously failed.
     */
    suspend fun forceSyncToWatch(): SyncStatus {
        val config = getActiveConfig()
        val status = syncToWatch(config)
        updateSyncStatus(status)
        return status
    }

    /**
     * Get list of saved profile names
     */
    fun getSavedProfileNames(): List<String> {
        val jsonArray = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        return try {
            val profiles: List<TremorDetectionConfig> = json.decodeFromString(jsonArray)
            profiles.map { it.profileName }
        } catch (e: SerializationException) {
            Timber.e(e, "Failed to parse profile list")
            emptyList()
        }
    }

    /**
     * Save current config as a named profile
     */
    fun saveProfile(config: TremorDetectionConfig) {
        val profiles = loadAllProfiles().toMutableMap()
        profiles[config.profileName] = config
        saveAllProfiles(profiles)
        Timber.i("Profile saved: ${config.profileName}")
    }

    /**
     * Load a saved profile by name.
     * Checks both user-saved profiles and built-in presets.
     */
    fun loadProfile(name: String): TremorDetectionConfig? {
        // Check user profiles first
        val userProfile = loadAllProfiles()[name]
        if (userProfile != null) return userProfile

        // Check built-in presets
        return TremorDetectionConfig.PRESETS[name]
    }

    /**
     * Delete a saved profile.
     * Cannot delete built-in presets.
     */
    fun deleteProfile(name: String): Boolean {
        // Prevent deleting built-in presets
        if (TremorDetectionConfig.PRESETS.containsKey(name)) {
            Timber.w("Cannot delete built-in preset: $name")
            return false
        }

        val profiles = loadAllProfiles().toMutableMap()
        val removed = profiles.remove(name)
        if (removed != null) {
            saveAllProfiles(profiles)
            Timber.i("Profile deleted: $name")
            return true
        }
        return false
    }

    /**
     * Export config to JSON file
     */
    fun exportToFile(config: TremorDetectionConfig, file: File) {
        try {
            file.writeText(config.toJson())
            Timber.i("Config exported to: ${file.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to export config to file")
            throw e
        }
    }

    /**
     * Import config from JSON file with validation
     */
    fun importFromFile(file: File): TremorDetectionConfig {
        try {
            // File size check (max 100KB)
            if (file.length() > 100_000) {
                throw IllegalArgumentException("File too large: ${file.length()} bytes (max 100KB)")
            }

            val jsonString = file.readText()
            val config = TremorDetectionConfig.fromJson(jsonString)
            Timber.i("Config imported from: ${file.absolutePath}")
            return config
        } catch (e: Exception) {
            Timber.e(e, "Failed to import config from file")
            throw e
        }
    }

    /**
     * Export config as shareable JSON string
     */
    fun exportToString(config: TremorDetectionConfig): String {
        return config.toJson()
    }

    /**
     * Import config from JSON string with validation
     */
    fun importFromString(jsonString: String): TremorDetectionConfig {
        return try {
            TremorDetectionConfig.fromJson(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "Failed to import config from string")
            throw IllegalArgumentException("Invalid config format: ${e.message}", e)
        }
    }

    /**
     * Sync configuration to watch via Wearable Data Layer.
     * Includes retry logic with exponential backoff.
     *
     * @param config Configuration to sync
     * @return SyncStatus indicating success/failure
     */
    private suspend fun syncToWatch(config: TremorDetectionConfig): SyncStatus {
        var attempt = 0
        var delay = retryDelayMs

        while (attempt < maxRetries) {
            attempt++

            try {
                Timber.d("Syncing config to watch (attempt $attempt/$maxRetries)...")

                val request = PutDataMapRequest.create(DATA_PATH).apply {
                    dataMap.putString("config_json", config.toJson())
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                    dataMap.putString("profile_name", config.profileName)
                    dataMap.putInt("version", config.version)
                }

                // Synchronous call with Tasks API
                val result = withContext(Dispatchers.IO) {
                    Tasks.await(dataClient.putDataItem(request.asPutDataRequest().setUrgent()))
                }

                Timber.i("Config synced to watch successfully: ${config.profileName}")
                return SyncStatus.SYNCED

            } catch (e: CancellationException) {
                throw e // Don't retry on cancellation
            } catch (e: Exception) {
                Timber.w(e, "Sync attempt $attempt failed: ${e.message}")

                if (attempt < maxRetries) {
                    Timber.d("Retrying in ${delay}ms...")
                    delay(delay)
                    delay = (delay * exponentialBackoffMultiplier).toLong()
                } else {
                    Timber.e("All sync attempts failed for: ${config.profileName}")
                    return SyncStatus.FAILED
                }
            }
        }

        return SyncStatus.FAILED
    }

    /**
     * Update sync status in preferences
     */
    private fun updateSyncStatus(status: SyncStatus) {
        prefs.edit().apply {
            putString(KEY_SYNC_STATUS, status.name)
            if (status == SyncStatus.SYNCED) {
                putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            }
        }.apply()
    }

    /**
     * Load all user-saved profiles from storage
     */
    private fun loadAllProfiles(): Map<String, TremorDetectionConfig> {
        val jsonArray = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        val profiles = mutableMapOf<String, TremorDetectionConfig>()

        try {
            val configList: List<TremorDetectionConfig> = json.decodeFromString(jsonArray)
            configList.forEach { config ->
                profiles[config.profileName] = config
            }
        } catch (e: SerializationException) {
            Timber.e(e, "Failed to parse profiles, returning empty list")
        }

        return profiles
    }

    /**
     * Save all profiles to storage
     */
    private fun saveAllProfiles(profiles: Map<String, TremorDetectionConfig>) {
        val configList = profiles.values.toList()
        val jsonArray = json.encodeToString(configList)
        prefs.edit().putString(KEY_PROFILES, jsonArray).apply()
    }
}
