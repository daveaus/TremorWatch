package com.opensource.tremorwatch.config

import android.content.Context
import android.content.Intent
import com.opensource.tremorwatch.data.PreferencesRepository

/**
 * Manages monitoring state configuration.
 * 
 * This object handles persistent storage of monitoring-related settings
 * such as whether monitoring is active, upload intervals, and pause behavior.
 * 
 * Now uses PreferencesRepository (DataStore) internally for better type safety
 * and coroutine support. The public API remains unchanged for backward compatibility.
 */
object MonitoringState {
    private fun getRepository(context: Context): PreferencesRepository {
        return PreferencesRepository(context)
    }

    fun isMonitoring(context: Context): Boolean {
        return getRepository(context).getIsMonitoring()
    }

    fun setMonitoring(context: Context, isMonitoring: Boolean) {
        getRepository(context).setMonitoringSync(isMonitoring)
    }

    fun isSendRawData(context: Context): Boolean {
        return getRepository(context).getIsSendRawData()
    }

    fun setSendRawData(context: Context, sendRawData: Boolean) {
        getRepository(context).setSendRawDataSync(sendRawData)
    }

    fun isPauseWhenNotWorn(context: Context): Boolean {
        return getRepository(context).getIsPauseWhenNotWorn()
    }

    fun setPauseWhenNotWorn(context: Context, pauseWhenNotWorn: Boolean) {
        getRepository(context).setPauseWhenNotWornSync(pauseWhenNotWorn)

        // Notify the service that settings have changed
        val intent = Intent("com.opensource.tremorwatch.SETTINGS_CHANGED")
        context.sendBroadcast(intent)
    }

    fun getUploadIntervalMinutes(context: Context): Int {
        return getRepository(context).getUploadIntervalMinutes()
    }

    fun setUploadIntervalMinutes(context: Context, minutes: Int) {
        getRepository(context).setUploadIntervalMinutesSync(minutes)

        // Notify the service to reschedule upload alarm
        val intent = Intent("com.opensource.tremorwatch.UPLOAD_INTERVAL_CHANGED")
        context.sendBroadcast(intent)
    }
}

