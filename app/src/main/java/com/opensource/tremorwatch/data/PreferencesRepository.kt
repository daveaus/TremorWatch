package com.opensource.tremorwatch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Unified preferences repository using DataStore.
 * 
 * Provides type-safe, coroutine-based access to all app preferences.
 * Replaces direct SharedPreferences usage for better:
 * - Type safety
 * - Coroutine support
 * - Error handling
 * - Migration support
 * 
 * This is the recommended way to access preferences going forward.
 * The old MonitoringState and DataConfig objects are kept for backward compatibility
 * but should be migrated to use this repository.
 */
class PreferencesRepository(private val context: Context) {
    
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tremor_watch_prefs")
        
        // Monitoring state keys
        private val KEY_IS_MONITORING = booleanPreferencesKey("is_monitoring")
        private val KEY_SEND_RAW_DATA = booleanPreferencesKey("send_raw_data")
        private val KEY_PAUSE_WHEN_NOT_WORN = booleanPreferencesKey("pause_when_not_worn")
        private val KEY_UPLOAD_INTERVAL_MINUTES = intPreferencesKey("upload_interval_minutes")
        private val KEY_IS_MONITORING_PAUSED = booleanPreferencesKey("is_monitoring_paused")
        private val KEY_MONITORING_PAUSE_REASON = stringPreferencesKey("monitoring_pause_reason")
        
        // Watchdog keys
        private val KEY_LAST_RESTART_ATTEMPT = longPreferencesKey("last_restart_attempt")
        private val KEY_RESTART_COUNT = intPreferencesKey("restart_count")
        
        // Data config keys
        private val KEY_INFLUX_ENABLED = booleanPreferencesKey("influx_enabled")
        private val KEY_INFLUX_URL = stringPreferencesKey("influx_url")
        private val KEY_INFLUX_DATABASE = stringPreferencesKey("influx_database")
        private val KEY_INFLUX_USERNAME = stringPreferencesKey("influx_username")
        private val KEY_INFLUX_PASSWORD = stringPreferencesKey("influx_password")
        private val KEY_HOME_WIFI_SSID = stringPreferencesKey("home_wifi_ssid")
        private val KEY_UPLOAD_TO_PHONE_ENABLED = booleanPreferencesKey("upload_to_phone_enabled")
        private val KEY_LOCAL_STORAGE_ENABLED = booleanPreferencesKey("local_storage_enabled")
        private val KEY_LOCAL_STORAGE_RETENTION_HOURS = intPreferencesKey("local_storage_retention_hours")
    }
    
    private val dataStore = context.dataStore
    
    // Monitoring state flows
    val isMonitoring: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_IS_MONITORING] ?: false
        }
    
    val isSendRawData: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_SEND_RAW_DATA] ?: false
        }
    
    val isPauseWhenNotWorn: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_PAUSE_WHEN_NOT_WORN] ?: true  // Default to TRUE for battery life
        }
    
    val uploadIntervalMinutes: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_UPLOAD_INTERVAL_MINUTES] ?: 60  // Default: 60 minutes (hourly) for battery optimization
        }
    
    val isMonitoringPaused: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_IS_MONITORING_PAUSED] ?: false
        }
    
    val monitoringPauseReason: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_MONITORING_PAUSE_REASON] ?: ""
        }
    
    val lastRestartAttempt: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_LAST_RESTART_ATTEMPT] ?: 0L
        }
    
    val restartCount: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_RESTART_COUNT] ?: 0
        }
    
    // Data config flows
    val isInfluxEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_INFLUX_ENABLED] ?: false
        }
    
    val influxUrl: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_INFLUX_URL] ?: ""
        }
    
    val influxDatabase: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_INFLUX_DATABASE] ?: ""
        }
    
    val influxUsername: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_INFLUX_USERNAME] ?: ""
        }
    
    val influxPassword: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_INFLUX_PASSWORD] ?: ""
        }
    
    val homeWifiSsid: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_HOME_WIFI_SSID] ?: ""
        }
    
    val isUploadToPhoneEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_UPLOAD_TO_PHONE_ENABLED] ?: true
        }
    
    val isLocalStorageEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_LOCAL_STORAGE_ENABLED] ?: false
        }
    
    val localStorageRetentionHours: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_LOCAL_STORAGE_RETENTION_HOURS] ?: 48
        }
    
    // Monitoring state setters
    suspend fun setMonitoring(isMonitoring: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_IS_MONITORING] = isMonitoring
        }
    }
    
    suspend fun setSendRawData(sendRawData: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_SEND_RAW_DATA] = sendRawData
        }
    }
    
    suspend fun setPauseWhenNotWorn(pauseWhenNotWorn: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_PAUSE_WHEN_NOT_WORN] = pauseWhenNotWorn
        }
    }
    
    suspend fun setUploadIntervalMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_UPLOAD_INTERVAL_MINUTES] = minutes
        }
    }
    
    suspend fun setMonitoringPaused(isPaused: Boolean, reason: String = "") {
        dataStore.edit { preferences ->
            preferences[KEY_IS_MONITORING_PAUSED] = isPaused
            preferences[KEY_MONITORING_PAUSE_REASON] = reason
        }
    }
    
    suspend fun updateRestartAttempt(timestamp: Long, count: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_RESTART_ATTEMPT] = timestamp
            preferences[KEY_RESTART_COUNT] = count
        }
    }
    
    // Data config setters
    suspend fun setInfluxEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_INFLUX_ENABLED] = enabled
        }
    }
    
    suspend fun setInfluxUrl(url: String) {
        dataStore.edit { preferences ->
            preferences[KEY_INFLUX_URL] = url
        }
    }
    
    suspend fun setInfluxDatabase(database: String) {
        dataStore.edit { preferences ->
            preferences[KEY_INFLUX_DATABASE] = database
        }
    }
    
    suspend fun setInfluxUsername(username: String) {
        dataStore.edit { preferences ->
            preferences[KEY_INFLUX_USERNAME] = username
        }
    }
    
    suspend fun setInfluxPassword(password: String) {
        dataStore.edit { preferences ->
            preferences[KEY_INFLUX_PASSWORD] = password
        }
    }
    
    suspend fun setHomeWifiSsid(ssid: String) {
        dataStore.edit { preferences ->
            preferences[KEY_HOME_WIFI_SSID] = ssid
        }
    }
    
    suspend fun setUploadToPhoneEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_UPLOAD_TO_PHONE_ENABLED] = enabled
        }
    }
    
    suspend fun setLocalStorageEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_LOCAL_STORAGE_ENABLED] = enabled
        }
    }
    
    suspend fun setLocalStorageRetentionHours(hours: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_LOCAL_STORAGE_RETENTION_HOURS] = hours
        }
    }
    
    // Helper to check if InfluxDB is configured
    suspend fun isInfluxConfigured(): Boolean {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                val enabled = preferences[KEY_INFLUX_ENABLED] ?: false
                val url = preferences[KEY_INFLUX_URL] ?: ""
                val database = preferences[KEY_INFLUX_DATABASE] ?: ""
                enabled && url.isNotEmpty() && database.isNotEmpty()
            }
            .first()
    }
    
    // ====================== SYNCHRONOUS HELPERS (for backward compatibility) ======================
    // These methods use runBlocking to provide synchronous access for legacy code.
    // New code should use the Flow-based APIs above.
    
    fun getIsMonitoring(): Boolean = runBlocking { isMonitoring.first() }
    fun getIsSendRawData(): Boolean = runBlocking { isSendRawData.first() }
    fun getIsPauseWhenNotWorn(): Boolean = runBlocking { isPauseWhenNotWorn.first() }
    fun getUploadIntervalMinutes(): Int = runBlocking { uploadIntervalMinutes.first() }
    
    fun getIsInfluxEnabled(): Boolean = runBlocking { isInfluxEnabled.first() }
    fun getInfluxUrl(): String = runBlocking { influxUrl.first() }
    fun getInfluxDatabase(): String = runBlocking { influxDatabase.first() }
    fun getInfluxUsername(): String = runBlocking { influxUsername.first() }
    fun getInfluxPassword(): String = runBlocking { influxPassword.first() }
    fun getHomeWifiSsid(): String = runBlocking { homeWifiSsid.first() }
    fun getIsUploadToPhoneEnabled(): Boolean = runBlocking { isUploadToPhoneEnabled.first() }
    fun getIsLocalStorageEnabled(): Boolean = runBlocking { isLocalStorageEnabled.first() }
    fun getLocalStorageRetentionHours(): Int = runBlocking { localStorageRetentionHours.first() }
    fun getIsInfluxConfigured(): Boolean = runBlocking { isInfluxConfigured() }
    fun getIsMonitoringPaused(): Boolean = runBlocking { isMonitoringPaused.first() }
    fun getMonitoringPauseReason(): String = runBlocking { monitoringPauseReason.first() }
    fun getLastRestartAttempt(): Long = runBlocking { lastRestartAttempt.first() }
    fun getRestartCount(): Int = runBlocking { restartCount.first() }
    
    fun setMonitoringSync(isMonitoring: Boolean) = runBlocking { setMonitoring(isMonitoring) }
    fun setSendRawDataSync(sendRawData: Boolean) = runBlocking { setSendRawData(sendRawData) }
    fun setPauseWhenNotWornSync(pauseWhenNotWorn: Boolean) = runBlocking { setPauseWhenNotWorn(pauseWhenNotWorn) }
    fun setUploadIntervalMinutesSync(minutes: Int) = runBlocking { setUploadIntervalMinutes(minutes) }
    
    fun setInfluxEnabledSync(enabled: Boolean) = runBlocking { setInfluxEnabled(enabled) }
    fun setInfluxUrlSync(url: String) = runBlocking { setInfluxUrl(url) }
    fun setInfluxDatabaseSync(database: String) = runBlocking { setInfluxDatabase(database) }
    fun setInfluxUsernameSync(username: String) = runBlocking { setInfluxUsername(username) }
    fun setInfluxPasswordSync(password: String) = runBlocking { setInfluxPassword(password) }
    fun setHomeWifiSsidSync(ssid: String) = runBlocking { setHomeWifiSsid(ssid) }
    fun setUploadToPhoneEnabledSync(enabled: Boolean) = runBlocking { setUploadToPhoneEnabled(enabled) }
    fun setLocalStorageEnabledSync(enabled: Boolean) = runBlocking { setLocalStorageEnabled(enabled) }
    fun setLocalStorageRetentionHoursSync(hours: Int) = runBlocking { setLocalStorageRetentionHours(hours) }
    fun setMonitoringPausedSync(isPaused: Boolean, reason: String = "") = runBlocking { setMonitoringPaused(isPaused, reason) }
    fun updateRestartAttemptSync(timestamp: Long, count: Int) = runBlocking { updateRestartAttempt(timestamp, count) }
}

