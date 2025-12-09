package com.opensource.tremorwatch.phone

import android.content.Context
import android.content.SharedPreferences
import com.opensource.tremorwatch.phone.data.SecurePreferencesManager

/**
 * Configuration manager for phone companion app settings.
 * Manages InfluxDB connection, WiFi settings, and local storage.
 *
 * SECURITY: Sensitive credentials (passwords) are stored using EncryptedSharedPreferences.
 * Non-sensitive settings use regular SharedPreferences for performance.
 */
object PhoneDataConfig {

    private const val PREFS_NAME = "tremorwatch_phone_config"

    // InfluxDB settings (v1.x compatible)
    private const val KEY_INFLUXDB_URL = "influxdb_url"
    private const val KEY_INFLUXDB_DATABASE = "influxdb_database"
    private const val KEY_INFLUXDB_USERNAME = "influxdb_username"
    private const val KEY_INFLUXDB_PASSWORD = "influxdb_password"  // Stored in EncryptedSharedPreferences

    // WiFi settings
    private const val KEY_HOME_WIFI_SSIDS = "home_wifi_ssids"
    private const val KEY_HOME_WIFI_BSSIDS = "home_wifi_bssids"
    private const val KEY_ALLOW_MANUAL_ANY_NETWORK = "allow_manual_any_network"

    // Local storage settings
    private const val KEY_LOCAL_STORAGE_ENABLED = "local_storage_enabled"
    private const val KEY_LOCAL_STORAGE_RETENTION_HOURS = "local_storage_retention_hours"

    // Upload statistics
    private const val KEY_LAST_UPLOAD_TIME = "last_upload_time"
    private const val KEY_BATCHES_UPLOADED_TODAY = "batches_uploaded_today"
    private const val KEY_LAST_UPLOAD_DATE = "last_upload_date"
    private const val KEY_TOTAL_BATCHES_UPLOADED = "total_batches_uploaded"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // InfluxDB configuration (v1.x)
    fun getInfluxDbUrl(context: Context): String {
        return getPrefs(context).getString(KEY_INFLUXDB_URL, "") ?: ""
    }

    fun setInfluxDbUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_INFLUXDB_URL, url).apply()
    }

    fun getInfluxDbDatabase(context: Context): String {
        return getPrefs(context).getString(KEY_INFLUXDB_DATABASE, "") ?: ""
    }

    fun setInfluxDbDatabase(context: Context, database: String) {
        getPrefs(context).edit().putString(KEY_INFLUXDB_DATABASE, database).apply()
    }

    fun getInfluxDbUsername(context: Context): String {
        return getPrefs(context).getString(KEY_INFLUXDB_USERNAME, "") ?: ""
    }

    fun setInfluxDbUsername(context: Context, username: String) {
        getPrefs(context).edit().putString(KEY_INFLUXDB_USERNAME, username).apply()
    }

    fun getInfluxDbPassword(context: Context): String {
        // Auto-migrate from plain storage to encrypted storage if needed
        migratePasswordToEncryptedStorage(context)

        // Retrieve from encrypted storage
        return SecurePreferencesManager.getString(context, KEY_INFLUXDB_PASSWORD, "")
    }

    fun setInfluxDbPassword(context: Context, password: String) {
        // Store in encrypted storage
        SecurePreferencesManager.putString(context, KEY_INFLUXDB_PASSWORD, password)
    }

    /**
     * One-time migration from plain SharedPreferences to EncryptedSharedPreferences.
     * This safely moves the password if it exists in plain storage.
     */
    private fun migratePasswordToEncryptedStorage(context: Context) {
        val plainPrefs = getPrefs(context)

        // Only migrate if password exists in plain storage and not in encrypted storage
        if (plainPrefs.contains(KEY_INFLUXDB_PASSWORD) &&
            !SecurePreferencesManager.contains(context, KEY_INFLUXDB_PASSWORD)) {
            SecurePreferencesManager.migrateFromPlainPrefs(context, plainPrefs, KEY_INFLUXDB_PASSWORD)
        }
    }

    fun isInfluxConfigured(context: Context): Boolean {
        val url = getInfluxDbUrl(context)
        val database = getInfluxDbDatabase(context)
        // Username/password are optional for InfluxDB v1.x
        return url.isNotEmpty() && database.isNotEmpty()
    }

    // InfluxDB upload enabled toggle - separate from configuration
    fun isInfluxDbEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean("influxdb_enabled", false) // Default disabled
    }

    fun setInfluxDbEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("influxdb_enabled", enabled).apply()
    }

    // WiFi settings
    fun getHomeWifiSsids(context: Context): List<String> {
        val ssidString = getPrefs(context).getString(KEY_HOME_WIFI_SSIDS, "") ?: ""
        if (ssidString.isEmpty()) {
            return emptyList()
        }
        return ssidString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setHomeWifiSsids(context: Context, ssids: List<String>) {
        val ssidString = ssids.joinToString(",")
        getPrefs(context).edit().putString(KEY_HOME_WIFI_SSIDS, ssidString).apply()
    }

    fun getHomeWifiBssids(context: Context): List<String> {
        val bssidString = getPrefs(context).getString(KEY_HOME_WIFI_BSSIDS, "") ?: ""
        if (bssidString.isEmpty()) {
            return emptyList()
        }
        return bssidString.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    fun setHomeWifiBssids(context: Context, bssids: List<String>) {
        val bssidString = bssids.joinToString(",")
        getPrefs(context).edit().putString(KEY_HOME_WIFI_BSSIDS, bssidString).apply()
    }

    fun getAllowManualAnyNetwork(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ALLOW_MANUAL_ANY_NETWORK, true)
    }

    fun setAllowManualAnyNetwork(context: Context, allow: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALLOW_MANUAL_ANY_NETWORK, allow).apply()
    }

    // Local storage settings
    // IMPORTANT: Local storage is enabled by default to prevent data loss
    // The app should function independently of InfluxDB
    fun isLocalStorageEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LOCAL_STORAGE_ENABLED, true)
    }

    fun setLocalStorageEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOCAL_STORAGE_ENABLED, enabled).apply()
    }

    fun getLocalStorageRetentionHours(context: Context): Int {
        return getPrefs(context).getInt(KEY_LOCAL_STORAGE_RETENTION_HOURS, 168) // Default 7 days
    }

    fun setLocalStorageRetentionHours(context: Context, hours: Int) {
        getPrefs(context).edit().putInt(KEY_LOCAL_STORAGE_RETENTION_HOURS, hours).apply()
    }

    // Upload statistics
    fun recordSuccessfulUpload(context: Context) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val lastDate = prefs.getString(KEY_LAST_UPLOAD_DATE, "") ?: ""

        val editor = prefs.edit()
        editor.putLong(KEY_LAST_UPLOAD_TIME, now)

        // Reset daily counter if it's a new day
        if (today != lastDate) {
            editor.putString(KEY_LAST_UPLOAD_DATE, today)
            editor.putInt(KEY_BATCHES_UPLOADED_TODAY, 1)
        } else {
            val currentCount = prefs.getInt(KEY_BATCHES_UPLOADED_TODAY, 0)
            editor.putInt(KEY_BATCHES_UPLOADED_TODAY, currentCount + 1)
        }

        // Increment total counter
        val totalCount = prefs.getInt(KEY_TOTAL_BATCHES_UPLOADED, 0)
        editor.putInt(KEY_TOTAL_BATCHES_UPLOADED, totalCount + 1)

        editor.apply()
    }

    fun getLastUploadTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_UPLOAD_TIME, 0)
    }

    fun getBatchesUploadedToday(context: Context): Int {
        val prefs = getPrefs(context)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val lastDate = prefs.getString(KEY_LAST_UPLOAD_DATE, "") ?: ""

        // Return 0 if it's a different day
        return if (today == lastDate) {
            prefs.getInt(KEY_BATCHES_UPLOADED_TODAY, 0)
        } else {
            0
        }
    }

    fun getTotalBatchesUploaded(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_BATCHES_UPLOADED, 0)
    }
}
