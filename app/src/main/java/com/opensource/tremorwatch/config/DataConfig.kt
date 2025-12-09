package com.opensource.tremorwatch.config

import android.content.Context
import com.opensource.tremorwatch.data.PreferencesRepository

/**
 * Manages data configuration settings.
 * 
 * This object handles persistent storage of data-related settings such as
 * InfluxDB configuration, WiFi SSID, upload preferences, and local storage settings.
 * 
 * Now uses PreferencesRepository (DataStore) internally for better type safety
 * and coroutine support. The public API remains unchanged for backward compatibility.
 */
object DataConfig {
    private const val CONFIG_FILE_NAME = "config.txt"
    
    private fun getRepository(context: Context): PreferencesRepository {
        return PreferencesRepository(context)
    }

    fun isInfluxEnabled(context: Context): Boolean {
        return getRepository(context).getIsInfluxEnabled()
    }

    fun setInfluxEnabled(context: Context, enabled: Boolean) {
        getRepository(context).setInfluxEnabledSync(enabled)
    }

    fun getInfluxUrl(context: Context): String {
        return getRepository(context).getInfluxUrl()
    }

    fun getInfluxDatabase(context: Context): String {
        return getRepository(context).getInfluxDatabase()
    }

    fun getInfluxUsername(context: Context): String {
        return getRepository(context).getInfluxUsername()
    }

    fun getInfluxPassword(context: Context): String {
        return getRepository(context).getInfluxPassword()
    }

    fun getHomeWifiSsid(context: Context): String {
        return getRepository(context).getHomeWifiSsid()
    }

    /**
     * Get list of allowed home WiFi SSIDs (comma-separated support).
     * Supports both single SSID and multiple SSIDs separated by commas.
     * Example: "NETGEAR46,NETGEAR46-5G"
     */
    fun getHomeWifiSsids(context: Context): List<String> {
        val ssidString = getHomeWifiSsid(context)
        if (ssidString.isEmpty()) {
            return emptyList()
        }
        return ssidString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun isUploadToPhoneEnabled(context: Context): Boolean {
        return getRepository(context).getIsUploadToPhoneEnabled()
    }

    fun setUploadToPhoneEnabled(context: Context, enabled: Boolean) {
        getRepository(context).setUploadToPhoneEnabledSync(enabled)
    }

    fun isLocalStorageEnabled(context: Context): Boolean {
        return getRepository(context).getIsLocalStorageEnabled()
    }

    fun setLocalStorageEnabled(context: Context, enabled: Boolean) {
        getRepository(context).setLocalStorageEnabledSync(enabled)
    }

    fun getLocalStorageRetentionHours(context: Context): Int {
        return getRepository(context).getLocalStorageRetentionHours()
    }

    fun setLocalStorageRetentionHours(context: Context, hours: Int) {
        getRepository(context).setLocalStorageRetentionHoursSync(hours)
    }

    fun isInfluxConfigured(context: Context): Boolean {
        return getRepository(context).getIsInfluxConfigured()
    }

    /**
     * Load configuration from external config file if present.
     * Expected format:
     * influxdb_enabled=true
     * influxdb_url=http://10.0.0.10:8086
     * influxdb_database=homeassistant
     * influxdb_username=
     * influxdb_password=
     * local_storage_enabled=false
     * local_storage_retention_hours=48
     * home_wifi_ssid=YourHomeNetworkName
     *
     * For multiple WiFi networks, use comma-separated SSIDs:
     * home_wifi_ssid=NETGEAR46,NETGEAR46-5G
     *
     * Returns true if config was loaded successfully
     */
    fun loadFromFile(context: Context): Boolean {
        return try {
            val configFile = java.io.File(context.getExternalFilesDir(null), CONFIG_FILE_NAME)
            if (!configFile.exists()) {
                return false
            }

            val lines = configFile.readLines()
            var influxEnabled = false
            var influxUrl = ""
            var influxDatabase = ""
            var influxUsername = ""
            var influxPassword = ""
            var homeWifiSsid = ""
            var localStorageEnabled = false
            var localStorageRetentionHours = 48

            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) return@forEach

                when {
                    trimmed.startsWith("influxdb_enabled=") -> influxEnabled = trimmed.substring(17).trim().toBoolean()
                    trimmed.startsWith("influxdb_url=") -> influxUrl = trimmed.substring(13).trim()
                    trimmed.startsWith("influxdb_database=") -> influxDatabase = trimmed.substring(18).trim()
                    trimmed.startsWith("influxdb_username=") -> influxUsername = trimmed.substring(18).trim()
                    trimmed.startsWith("influxdb_password=") -> influxPassword = trimmed.substring(18).trim()
                    trimmed.startsWith("home_wifi_ssid=") -> homeWifiSsid = trimmed.substring(15).trim()
                    trimmed.startsWith("local_storage_enabled=") -> localStorageEnabled = trimmed.substring(22).trim().toBoolean()
                    trimmed.startsWith("local_storage_retention_hours=") -> localStorageRetentionHours = trimmed.substring(30).trim().toIntOrNull() ?: 48
                }
            }

            val repo = getRepository(context)
            repo.setInfluxEnabledSync(influxEnabled)
            if (influxUrl.isNotEmpty()) repo.setInfluxUrlSync(influxUrl)
            if (influxDatabase.isNotEmpty()) repo.setInfluxDatabaseSync(influxDatabase)
            repo.setInfluxUsernameSync(influxUsername)
            repo.setInfluxPasswordSync(influxPassword)
            if (homeWifiSsid.isNotEmpty()) repo.setHomeWifiSsidSync(homeWifiSsid)
            repo.setLocalStorageEnabledSync(localStorageEnabled)
            repo.setLocalStorageRetentionHoursSync(localStorageRetentionHours)

            true // Always return true if we successfully parsed the file
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getConfigFilePath(context: Context): String {
        return "${context.getExternalFilesDir(null)?.absolutePath}/$CONFIG_FILE_NAME"
    }
}

