package com.opensource.tremorwatch.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.opensource.tremorwatch.config.MonitoringState
import java.text.SimpleDateFormat
import java.util.*

/**
 * Broadcast receiver that handles periodic upload alarm triggers.
 * 
 * When triggered, sends a broadcast to the TremorService to initiate
 * an automatic upload of pending batches.
 */
class UploadAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "UploadAlarm"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "★★★ Upload alarm triggered - starting upload process ★★★")
        Log.w(TAG, "Upload alarm fired at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")

        // Check if monitoring is active
        if (!MonitoringState.isMonitoring(context)) {
            Log.d(TAG, "Monitoring is disabled - skipping upload")
            return
        }

        // Trigger upload by sending broadcast to service
        val uploadIntent = Intent("com.opensource.tremorwatch.TRIGGER_UPLOAD")
        uploadIntent.putExtra("manual", false)  // Explicitly mark as automatic
        context.sendBroadcast(uploadIntent)

        Log.d(TAG, "Upload trigger broadcast sent (automatic)")
    }
}

