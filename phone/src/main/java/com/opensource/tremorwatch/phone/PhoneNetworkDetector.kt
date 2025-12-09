package com.opensource.tremorwatch.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Network detection utilities for phone companion app.
 */
object PhoneNetworkDetector {

    private const val TAG = "PhoneNetworkDetector"

    /**
     * Check if any network is available (WiFi or cellular).
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Get current WiFi SSID and BSSID using modern Android API (Android 10+).
     * Falls back to deprecated API for older Android versions.
     */
    private fun getCurrentWifiInfo(context: Context): Pair<String, String> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Pair("", "")

        val network = connectivityManager.activeNetwork ?: return Pair("", "")
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return Pair("", "")

        // Use modern API for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transportInfo = capabilities.transportInfo
            if (transportInfo is WifiInfo) {
                val ssid = transportInfo.ssid?.replace("\"", "")?.lowercase() ?: ""
                val bssid = transportInfo.bssid?.lowercase() ?: ""
                if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                    Log.d(TAG, "Got WiFi info from NetworkCapabilities - SSID: $ssid, BSSID: $bssid")
                    return Pair(ssid, bssid)
                }
            }
        }

        // Fallback to deprecated API for older Android versions
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return Pair("", "")

        val wifiInfo = wifiManager.connectionInfo ?: return Pair("", "")
        val ssid = wifiInfo.ssid?.replace("\"", "")?.lowercase() ?: ""
        val bssid = wifiInfo.bssid?.lowercase() ?: ""

        Log.d(TAG, "Got WiFi info from WifiManager (deprecated) - SSID: $ssid, BSSID: $bssid")
        return Pair(ssid, bssid)
    }

    /**
     * Check if connected to one of the configured home WiFi networks.
     * Returns true if on home network, false otherwise.
     * 
     * CRITICAL FIX: Uses BSSID (router MAC) + SSID matching for reliability.
     * Uses modern NetworkCapabilities API on Android 10+ to avoid privacy masking.
     * NEVER treats "any network" as home - requires explicit configuration.
     */
    fun isOnHomeNetwork(context: Context): Boolean {
        val homeWifiSsids = PhoneDataConfig.getHomeWifiSsids(context)
        val homeWifiBssids = PhoneDataConfig.getHomeWifiBssids(context)

        // If no home WiFi is configured, allow uploads on any network
        if (homeWifiSsids.isEmpty() && homeWifiBssids.isEmpty()) {
            Log.i(TAG, "No home WiFi networks configured - allowing upload on any network")
            return true
        }

        Log.d(TAG, "Checking home network - Configured SSIDs: $homeWifiSsids, BSSIDs: $homeWifiBssids")

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: run {
                Log.w(TAG, "ConnectivityManager is null")
                return false
            }

        val network = connectivityManager.activeNetwork ?: run {
            Log.w(TAG, "No active network")
            return false
        }
        
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: run {
            Log.w(TAG, "No network capabilities")
            return false
        }

        // Require WiFi transport
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Log.d(TAG, "Not on WiFi transport → not home network")
            return false
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: run {
                Log.w(TAG, "WifiManager is null")
                return false
            }

        if (!wifiManager.isWifiEnabled) {
            Log.d(TAG, "WiFi is disabled")
            return false
        }

        // Get WiFi info using modern API (Android 10+) or fallback
        val (currentSsid, currentBssid) = getCurrentWifiInfo(context)

        Log.i(TAG, "WiFi check - Current SSID: '$currentSsid', BSSID: '$currentBssid'")
        Log.i(TAG, "Configured home SSIDs: $homeWifiSsids, BSSIDs: $homeWifiBssids")

        if (currentSsid.isEmpty() || currentSsid == "<unknown ssid>") {
            Log.w(TAG, "No WiFi SSID available (may need location permission or location services enabled)")
            return false
        }

        // Prefer BSSID match (more reliable - router MAC address)
        if (homeWifiBssids.isNotEmpty() && currentBssid.isNotEmpty() && currentBssid != "02:00:00:00:00:00") {
            val normalizedBssid = currentBssid.lowercase()
            val matchedBssid = homeWifiBssids.firstOrNull { it.lowercase() == normalizedBssid }
            if (matchedBssid != null) {
                Log.i(TAG, "✓ Matched home network by BSSID: $currentBssid (matched: $matchedBssid)")
                return true
            } else {
                Log.d(TAG, "BSSID mismatch - Current: '$normalizedBssid', Configured: $homeWifiBssids")
            }
        }

        // Fallback to SSID match
        if (homeWifiSsids.isNotEmpty()) {
            val matchedSsid = homeWifiSsids.firstOrNull { homeSSID ->
                currentSsid.equals(homeSSID.lowercase(), ignoreCase = true)
            }
            if (matchedSsid != null) {
                Log.i(TAG, "✓ Matched home network by SSID: '$currentSsid' (matched: '$matchedSsid')")
                return true
            } else {
                Log.d(TAG, "SSID mismatch - Current: '$currentSsid', Configured: $homeWifiSsids")
            }
        }

        Log.w(TAG, "✗ Not on home network. Current SSID: '$currentSsid', BSSID: '$currentBssid'")
        Log.w(TAG, "  Configured SSIDs: $homeWifiSsids, BSSIDs: $homeWifiBssids")
        return false
    }

    /**
     * Check if connected via WiFi (regardless of which network).
     */
    fun isOnWifi(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
