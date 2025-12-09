package com.opensource.tremorwatch.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.opensource.tremorwatch.config.DataConfig

/**
 * Network detection utilities for determining connectivity and home network status.
 * 
 * Provides methods to check:
 * - General network availability (WiFi or Bluetooth)
 * - Whether the device is connected to a configured home WiFi network
 */
object NetworkDetector {
    private const val TAG = "NetworkDetector"

    /**
     * Check if we're on the home network and should upload to InfluxDB.
     *
     * Strategy:
     * 1. If home WiFi SSID(s) are configured, check if connected to any of them
     * 2. If no SSID configured, assume we should always try (works for Bluetooth tethering)
     * 3. InfluxDB upload will fail gracefully if not reachable
     *
     * Supports multiple SSIDs (comma-separated): "NETGEAR46,NETGEAR46-5G"
     *
     * For Wear OS watches that get internet via Bluetooth from phone:
     * - Cannot detect phone's WiFi SSID
     * - Will attempt upload and fail if not on home network
     * - Batches accumulate and upload when back home
     */
    fun isOnHomeNetwork(context: Context): Boolean {
        val homeWifiSsids = DataConfig.getHomeWifiSsids(context)

        // If no home SSID configured, assume we should try (works for Bluetooth tethering)
        if (homeWifiSsids.isEmpty()) {
            Log.d(TAG, "No home WiFi SSID configured - assuming home network")
            return true
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            Log.d(TAG, "WiFiManager not available - may be using Bluetooth")
            return true // Try anyway - might be on Bluetooth tethering
        }

        val wifiInfo = wifiManager.connectionInfo
        if (wifiInfo == null) {
            Log.d(TAG, "No WiFi connection info - may be using Bluetooth")
            return true // Try anyway - might be on Bluetooth tethering
        }

        // SSID comes with quotes, e.g., "MyNetwork"
        val currentSsid = wifiInfo.ssid.removeSurrounding("\"")

        // Check if current SSID matches any of the configured home SSIDs
        val isHome = homeWifiSsids.any { homeSSID ->
            currentSsid.equals(homeSSID, ignoreCase = true)
        }

        Log.d(TAG, "Current WiFi: $currentSsid, Home SSIDs: ${homeWifiSsids.joinToString()}, Match: $isHome")
        return isHome
    }

    /**
     * Check if any network connectivity is available (WiFi or Bluetooth).
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager == null) {
            Log.w(TAG, "ConnectivityManager not available")
            return false
        }

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

