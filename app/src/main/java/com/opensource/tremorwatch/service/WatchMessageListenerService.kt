package com.opensource.tremorwatch.service

import android.util.Log
import com.opensource.tremorwatch.config.MonitoringState
import com.opensource.tremorwatch.shared.Constants
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject

/**
 * Listens for messages from the phone app.
 * Currently handles log requests - phone can request watch logs remotely.
 */
class WatchMessageListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchMsgListener"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageClient by lazy { Wearable.getMessageClient(this) }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "Message received: ${messageEvent.path}")

        when (messageEvent.path) {
            Constants.MESSAGE_PATH_LOG_REQUEST -> {
                handleLogRequest(messageEvent.sourceNodeId)
            }
            Constants.MESSAGE_PATH_TRAINING_STATE -> {
                handleTrainingStateUpdate(messageEvent.data)
            }
            else -> {
                Log.w(TAG, "Unknown message path: ${messageEvent.path}")
            }
        }
    }

    /**
     * Handle log request from phone - collect recent logs and send back.
     */
    private fun handleLogRequest(nodeId: String) {
        scope.launch {
            try {
                Log.i(TAG, "Log request received from phone, collecting logs...")

                // Get recent logs (last 500 lines)
                val logs = getWatchLogs()

                Log.d(TAG, "Collected ${logs.length} bytes of logs, sending to phone")

                // Send logs back to phone
                suspendCancellableCoroutine<Int> { cont ->
                    messageClient.sendMessage(
                        nodeId,
                        Constants.MESSAGE_PATH_LOG_RESPONSE,
                        logs.toByteArray()
                    ).addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }

                Log.i(TAG, "Logs sent successfully to phone")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send logs to phone: ${e.message}", e)
            }
        }
    }

    /**
     * Get watch logs using logcat.
     */
    private fun getWatchLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "logcat",
                "-d",
                "-v", "threadtime",
                "-t", "500",  // Last 500 lines
                "*:*"
            ))

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val logs = reader.readText()
            reader.close()

            if (logs.isEmpty()) {
                "No logs available"
            } else {
                logs
            }
        } catch (e: Exception) {
            "Failed to get logs: ${e.message}"
        }
    }

    private fun handleTrainingStateUpdate(data: ByteArray) {
        scope.launch {
            try {
                val payload = JSONObject(String(data, Charsets.UTF_8))
                val enabled = payload.optBoolean("enabled", false)
                MonitoringState.setTrainingMode(applicationContext, enabled)
                Log.i(TAG, "Training mode updated from phone: enabled=$enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply training state update: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Scope will be cancelled automatically when service is destroyed
    }
}
