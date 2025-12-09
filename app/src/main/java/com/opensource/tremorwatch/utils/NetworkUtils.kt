package com.opensource.tremorwatch.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import timber.log.Timber

/**
 * Network utility functions to reduce code duplication.
 * 
 * Provides consistent network state checking and WiFi SSID detection.
 */
object NetworkUtils {
    
    /**
     * Check if network is available.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo?.isConnected == true
        }
    }
    
    /**
     * Get current WiFi SSID.
     * 
     * @return SSID string (without quotes), or null if not on WiFi
     */
    fun getCurrentWifiSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ requires location permission and different API
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }
            
            // Try to get SSID from network capabilities (may not be available)
            val transportInfo = capabilities.transportInfo as? android.net.wifi.WifiInfo
            return transportInfo?.ssid?.removeSurrounding("\"")
        } else {
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            return wifiInfo?.ssid?.removeSurrounding("\"")
        }
    }
    
    /**
     * Check if device is connected to a specific WiFi network (or any of multiple SSIDs).
     * 
     * @param allowedSsids List of allowed SSID strings (case-sensitive)
     * @return true if connected to one of the allowed networks
     */
    fun isOnWifiNetwork(context: Context, allowedSsids: List<String>): Boolean {
        if (allowedSsids.isEmpty()) {
            return false
        }
        
        val currentSsid = getCurrentWifiSsid(context) ?: return false
        
        return allowedSsids.any { it.equals(currentSsid, ignoreCase = false) }
    }
    
    /**
     * Check if device is on home WiFi network.
     * 
     * @param homeSsid The home WiFi SSID (supports comma-separated multiple SSIDs)
     * @return true if connected to home network
     */
    fun isOnHomeNetwork(context: Context, homeSsid: String): Boolean {
        if (homeSsid.isEmpty()) {
            return false
        }
        
        // Support comma-separated SSIDs
        val allowedSsids = homeSsid.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return isOnWifiNetwork(context, allowedSsids)
    }
}

