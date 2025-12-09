package com.opensource.tremorwatch.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log

/**
 * Listens for network connectivity changes and triggers automatic uploads
 * when WiFi connects or network becomes available.
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Network receiver triggered with action: $action")
        
        // Handle both deprecated and modern connectivity actions
        if (action == ConnectivityManager.CONNECTIVITY_ACTION || 
            action == "android.net.conn.CONNECTIVITY_CHANGE") {
            Log.i(TAG, "=== Network connectivity changed ===")
            
            val hasNetwork = isNetworkAvailable(context)
            val isOnHomeNetwork = PhoneNetworkDetector.isOnHomeNetwork(context)
            
            Log.i(TAG, "Network available: $hasNetwork")
            Log.i(TAG, "On home network: $isOnHomeNetwork")

            // Check if we're on home network (where InfluxDB is reachable)
            if (isOnHomeNetwork) {
                Log.i(TAG, "✓ Connected to home network - triggering upload")
                triggerUpload(context)
            } else if (hasNetwork) {
                Log.d(TAG, "Network available but not on home network - batches will upload when on home network")
            } else {
                Log.d(TAG, "Network not available for uploading")
            }
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            return connectivityManager != null && connectivityManager.activeNetwork != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check network availability: ${e.message}", e)
            return false
        }
    }

    private fun triggerUpload(context: Context) {
        try {
            // Get number of pending batches
            val pendingCount = getPendingBatchCount(context)
            if (pendingCount == 0) {
                Log.d(TAG, "No pending batches to upload")
                return
            }

            Log.i(TAG, "Starting automatic upload with $pendingCount pending batch(es)")

            // Start upload service
            val uploadIntent = Intent(context, UploadService::class.java)
            uploadIntent.putExtra("process_now", true)  // Flag to process immediately

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(uploadIntent)
            } else {
                context.startService(uploadIntent)
            }

            Log.i(TAG, "Upload service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger upload: ${e.message}", e)
        }
    }

    private fun getPendingBatchCount(context: Context): Int {
        val queueDir = java.io.File(context.filesDir, "upload_queue")
        if (!queueDir.exists()) {
            return 0
        }

        return queueDir.listFiles { file ->
            file.name.startsWith("batch_") && file.name.endsWith(".json")
        }?.size ?: 0
    }
}
