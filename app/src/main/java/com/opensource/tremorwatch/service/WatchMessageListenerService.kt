package com.opensource.tremorwatch.service

import android.util.Log
import com.opensource.tremorwatch.training.TrainingAwareApplication
import com.opensource.tremorwatch.training.TrainingManager
import com.opensource.tremorwatch.config.MonitoringState
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TrainingState
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
            Constants.MESSAGE_PATH_TRAINING_CONFIG_UPDATE,
            Constants.MESSAGE_PATH_TRAINING_STATE -> {
                handleTrainingStateUpdate(messageEvent.data)
            }
            Constants.MESSAGE_PATH_TRAINING_STATE_REQUEST -> {
                handleTrainingStateRequest(messageEvent.sourceNodeId)
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

    private fun handleTrainingStateRequest(nodeId: String) {
        scope.launch {
            try {
                val app = application as? TrainingAwareApplication
                val runtimeSnapshot = app?.trainingManager?.getStatusSnapshot()
                val persistedSnapshot = TrainingManager.getPersistedStatusSnapshot(applicationContext)
                val trainingModeEnabled = MonitoringState.isTrainingMode(applicationContext)
                val snapshot = when {
                    runtimeSnapshot != null -> runtimeSnapshot
                    trainingModeEnabled && persistedSnapshot.engineState == TrainingState.OFF -> {
                        val inferredState = when {
                            persistedSnapshot.hasEnoughLabels -> TrainingState.PERSONALIZED
                            persistedSnapshot.promptsTotal == 0 -> TrainingState.WARMUP
                            else -> TrainingState.ACTIVE
                        }
                        persistedSnapshot.copy(
                            modeEnabled = true,
                            engineState = inferredState,
                            uiState = inferredState
                        )
                    }
                    else -> persistedSnapshot
                }

                val payload = JSONObject().apply {
                    put("enabled", snapshot.modeEnabled)
                    put("engineState", snapshot.engineState.name)
                    put("uiState", snapshot.uiState.name)
                    put("usableLabels", snapshot.usableLabelCount)
                    put("targetLabels", snapshot.targetUsableLabelCount)
                    put("yesLabels", snapshot.yesLabelCount)
                    put("noLabels", snapshot.noLabelCount)
                    put("ignoredLabels", snapshot.ignoredLabelCount)
                    put("promptsTotal", snapshot.promptsTotal)
                    put("promptsToday", snapshot.promptsToday)
                    put("hasEnoughLabels", snapshot.hasEnoughLabels)
                    put("trainingStartTimeMs", snapshot.trainingStartTimeMs ?: 0L)
                    put("trainingCompletedTimeMs", snapshot.trainingCompletedTimeMs ?: 0L)
                    put("lastPromptTimeMs", snapshot.lastPromptTimeMs ?: 0L)
                    put("lastFeedbackTimeMs", snapshot.lastFeedbackTimeMs ?: 0L)
                    put("lastFeedbackLabel", snapshot.lastFeedbackLabel?.name ?: "")
                    put("timestamp", System.currentTimeMillis())
                }.toString().toByteArray(Charsets.UTF_8)

                suspendCancellableCoroutine<Int> { cont ->
                    messageClient.sendMessage(
                        nodeId,
                        Constants.MESSAGE_PATH_TRAINING_STATE,
                        payload
                    ).addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }

                Log.i(
                    TAG,
                    "Training state snapshot sent to phone: " +
                        "state=${snapshot.uiState} usable=${snapshot.usableLabelCount}/${snapshot.targetUsableLabelCount}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send training state snapshot: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Scope will be cancelled automatically when service is destroyed
    }
}
