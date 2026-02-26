package com.opensource.tremorwatch.phone.config

import android.content.Context
import android.content.SharedPreferences
import com.opensource.tremorwatch.phone.data.WatchTrainingStatePrefs
import com.opensource.tremorwatch.phone.training.ApplyConfigResult
import com.opensource.tremorwatch.phone.training.NightlyAutoTuner
import com.opensource.tremorwatch.phone.training.TrainingTuneOutcome
import com.opensource.tremorwatch.phone.training.TrainingTuneSnapshot
import com.opensource.tremorwatch.phone.training.TrainingTuneStateStore
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Retry configuration
    private val maxRetries = 3
    private val retryDelayMs = 1000L
    private val exponentialBackoffMultiplier = 2.0
    private val configMutationMutex = Mutex()

    companion object {
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_PROFILES = "saved_profiles"
        private const val KEY_SYNC_STATUS = "sync_status"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_TRAINING_MODE_ENABLED = "training_mode_enabled"
        private const val KEY_AUTO_TUNE_PREVIOUS_CONFIG = "auto_tune_previous_config"
        private const val KEY_AUTO_TUNE_LAST_APPLIED_CONFIG = "auto_tune_last_applied_config"
        private const val ROLLBACK_AUTO_APPLY_BLOCK_MS = 7L * 24L * 60L * 60L * 1000L
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
        val trainedActive = config.profileName.equals("Trained", ignoreCase = true)
        TrainingTuneStateStore.update(context) {
            it.copy(trainedProfileActive = trainedActive)
        }

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

    fun isTrainingModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_TRAINING_MODE_ENABLED, false)
    }

    suspend fun setTrainingModeEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(KEY_TRAINING_MODE_ENABLED, enabled).apply()
        TrainingTuneStateStore.markTrainingMode(context, enabled)
        if (enabled) {
            NightlyAutoTuner.schedulePeriodic(context)
            // If watch already reached threshold before phone-side mode was enabled,
            // trigger a one-time immediate run so personalization is not delayed to nightly.
            val watchStatePrefs = context.getSharedPreferences(
                WatchTrainingStatePrefs.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val watchState = WatchTrainingStatePrefs.read(watchStatePrefs)
            if (watchState.hasData && watchState.hasEnoughLabels) {
                val enqueued = NightlyAutoTuner.enqueueImmediate(
                    context,
                    reason = "phone_training_enabled_threshold_ready"
                )
                Timber.i("Immediate auto-tune enqueue on phone enable: $enqueued")
            }
        } else {
            NightlyAutoTuner.cancel(context)
        }
        return sendTrainingModeToWatch(enabled)
    }

    fun getTrainingTuneSnapshot(): TrainingTuneSnapshot {
        return TrainingTuneStateStore.read(context)
    }

    fun runTrainingAutoTuneNow(reason: String = "manual_run"): Boolean {
        return NightlyAutoTuner.enqueueImmediate(context, reason)
    }

    /**
     * Request a fresh runtime training status snapshot from connected watch nodes.
     * Watch responds on MESSAGE_PATH_TRAINING_STATE.
     */
    suspend fun requestTrainingStateFromWatch(): Boolean {
        return try {
            val nodes = withContext(Dispatchers.IO) {
                Tasks.await(nodeClient.connectedNodes)
            }
            if (nodes.isEmpty()) {
                Timber.w("No connected watch nodes for training status request")
                return false
            }

            val payload = """{"timestamp":${System.currentTimeMillis()}}"""
                .toByteArray(Charsets.UTF_8)

            var sentToAnyNode = false
            for (node in nodes) {
                try {
                    withContext(Dispatchers.IO) {
                        Tasks.await(
                            messageClient.sendMessage(
                                node.id,
                                Constants.MESSAGE_PATH_TRAINING_STATE_REQUEST,
                                payload
                            )
                        )
                    }
                    sentToAnyNode = true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to request training state from node ${node.displayName}")
                }
            }
            sentToAnyNode
        } catch (e: Exception) {
            Timber.w(e, "Unable to request training state from watch")
            false
        }
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

    suspend fun applyAutoTunedConfig(config: TremorDetectionConfig): ApplyConfigResult {
        return configMutationMutex.withLock {
            try {
                val previous = getActiveConfig()
                recordAutoTuneSnapshots(previous, config)
                saveProfile(config)
                val syncStatus = setActiveConfig(config, forceSync = true)
                val synced = syncStatus == SyncStatus.SYNCED
                ApplyConfigResult(
                    appliedLocally = true,
                    syncedToWatch = synced,
                    hasRollbackSnapshot = hasAutoTuneRollbackSnapshot(),
                    detail = "Applied locally; sync status=${syncStatus.name}",
                    retriableFailure = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply auto-tuned config")
                ApplyConfigResult(
                    appliedLocally = false,
                    syncedToWatch = false,
                    hasRollbackSnapshot = hasAutoTuneRollbackSnapshot(),
                    detail = "Apply failed: ${e.message}",
                    retriableFailure = e is java.io.IOException
                )
            }
        }
    }

    fun recordAutoTuneSnapshots(
        previousConfig: TremorDetectionConfig,
        appliedConfig: TremorDetectionConfig
    ) {
        // Keep first pre-trained baseline unless there is no snapshot yet.
        val existingPrevious = prefs.getString(KEY_AUTO_TUNE_PREVIOUS_CONFIG, null)
        val shouldRefreshPrevious = existingPrevious == null || previousConfig.profileName != "Trained"
        prefs.edit().apply {
            if (shouldRefreshPrevious) {
                putString(KEY_AUTO_TUNE_PREVIOUS_CONFIG, previousConfig.toJson())
            }
            putString(KEY_AUTO_TUNE_LAST_APPLIED_CONFIG, appliedConfig.toJson())
        }.apply()
        TrainingTuneStateStore.update(context) { snapshot ->
            snapshot.copy(hasRollbackSnapshot = true)
        }
    }

    fun hasAutoTuneRollbackSnapshot(): Boolean {
        return prefs.getString(KEY_AUTO_TUNE_PREVIOUS_CONFIG, null) != null
    }

    suspend fun rollbackAutoTunedConfig(disableTrainingMode: Boolean = false): SyncStatus {
        val previousJson = prefs.getString(KEY_AUTO_TUNE_PREVIOUS_CONFIG, null)
        if (previousJson.isNullOrBlank()) {
            Timber.w("Rollback requested but no snapshot exists")
            return SyncStatus.FAILED
        }

        val previousConfig = try {
            TremorDetectionConfig.fromJson(previousJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse rollback snapshot")
            prefs.edit().remove(KEY_AUTO_TUNE_PREVIOUS_CONFIG).apply()
            TrainingTuneStateStore.update(context) { it.copy(hasRollbackSnapshot = false) }
            return SyncStatus.FAILED
        }

        val syncStatus = try {
            setActiveConfig(previousConfig, forceSync = true)
        } catch (e: Exception) {
            Timber.e(e, "Rollback apply failed")
            return SyncStatus.FAILED
        }

        if (disableTrainingMode) {
            setTrainingModeEnabled(false)
        }

        val now = System.currentTimeMillis()
        TrainingTuneStateStore.setAutoApplyBlockedUntil(context, now + ROLLBACK_AUTO_APPLY_BLOCK_MS)
        TrainingTuneStateStore.markRunResult(
            context = context,
            reason = "rollback",
            outcome = TrainingTuneOutcome.ROLLED_BACK,
            message = "Rollback applied; sync status=${syncStatus.name}",
            samplesUsed = 0,
            beforeJ = 0f,
            afterJ = 0f,
            trainedProfileActive = false,
            syncedToWatch = syncStatus == SyncStatus.SYNCED,
            hasRollbackSnapshot = hasAutoTuneRollbackSnapshot(),
            appliedNow = false,
            runTimestampMs = now
        )

        return syncStatus
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

    private suspend fun sendTrainingModeToWatch(enabled: Boolean): Boolean {
        return try {
            val nodes = withContext(Dispatchers.IO) {
                Tasks.await(nodeClient.connectedNodes)
            }
            if (nodes.isEmpty()) {
                Timber.w("No connected watch nodes for training mode sync")
                return false
            }

            val payload = """{"enabled":$enabled,"timestamp":${System.currentTimeMillis()}}"""
                .toByteArray(Charsets.UTF_8)

            var sentToAnyNode = false
            for (node in nodes) {
                var sentToNode = false
                try {
                    withContext(Dispatchers.IO) {
                        Tasks.await(
                            messageClient.sendMessage(
                                node.id,
                                Constants.MESSAGE_PATH_TRAINING_CONFIG_UPDATE,
                                payload
                            )
                        )
                    }
                    sentToNode = true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to send training mode update (new path) to node ${node.displayName}")
                }

                // Backward-compatibility path for older watch builds.
                try {
                    withContext(Dispatchers.IO) {
                        Tasks.await(
                            messageClient.sendMessage(
                                node.id,
                                Constants.MESSAGE_PATH_TRAINING_STATE,
                                payload
                            )
                        )
                    }
                    sentToNode = true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to send training mode update (legacy path) to node ${node.displayName}")
                }

                if (sentToNode) {
                    sentToAnyNode = true
                }
            }

            if (!sentToAnyNode) {
                Timber.w("Training mode update failed for all connected nodes")
            }
            sentToAnyNode
        } catch (e: Exception) {
            Timber.w(e, "Unable to sync training mode to watch")
            false
        }
    }
}
