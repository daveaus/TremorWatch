package com.opensource.tremorwatch.communication

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * Sends tremor batches from watch to phone using ChannelClient API.
 *
 * ChannelClient provides:
 * - Reliable bidirectional streams
 * - Automatic flow control and buffering
 * - No manual chunking needed
 * - Better for large data transfers than MessageClient
 *
 * This replaces the complex MessageClient chunking approach with a simpler streaming model.
 */
class WatchChannelSender(private val context: Context) {

    companion object {
        private const val TAG = "WatchChannelSender"
        private const val CHANNEL_PATH_TREMOR_BATCH = "/tremor_batch_channel"
        private const val CHANNEL_PATH_CALIBRATION = "/calibration_file_channel"

        private const val CHANNEL_OPEN_TIMEOUT_MS = 10_000L
        private const val CHANNEL_STREAM_TIMEOUT_MS = 10_000L
        private const val CHANNEL_CLOSE_TIMEOUT_MS = 5_000L
        private const val NODE_CONNECTIVITY_CHECK_TIMEOUT_MS = 2_000L
    }

    private val channelClient: ChannelClient = Wearable.getChannelClient(context)

    /**
     * Best-effort check. Returns:
     * - true: node is currently connected
     * - false: node is not connected
     * - null: unknown (API error/timeout); caller may still proceed and rely on openChannel timeout.
     */
    private suspend fun isNodeConnected(nodeId: String): Boolean? {
        return try {
            withTimeout(NODE_CONNECTIVITY_CHECK_TIMEOUT_MS) {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                nodes.any { it.id == nodeId }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Node connectivity check failed (continuing): ${e.message}")
            null
        }
    }

    /**
     * Send a tremor batch to the phone via ChannelClient.
     *
     * Process:
     * 1. Get connected phone node
     * 2. Open channel to phone
     * 3. Compress batch data
     * 4. Write to channel output stream
     * 5. Close channel
     *
     * @param batch The tremor batch to send
     * @param phoneNode The phone node to send to
     * @return true if sent successfully, false otherwise
     */
    suspend fun sendBatch(batch: TremorBatch, phoneNode: Node): Boolean {
        return withContext(Dispatchers.IO) {
            var channelToken: ChannelClient.Channel? = null

            try {
                Log.i(TAG, "Opening channel to send batch ${batch.batchId} (${batch.samples.size} samples)")

                when (isNodeConnected(phoneNode.id)) {
                    false -> {
                        Log.w(TAG, "Phone node ${phoneNode.id} is not connected; aborting send")
                        return@withContext false
                    }
                    true -> Unit
                    null -> Unit
                }

                // Open channel to phone
                channelToken = withTimeout(CHANNEL_OPEN_TIMEOUT_MS) {
                    channelClient.openChannel(phoneNode.id, CHANNEL_PATH_TREMOR_BATCH).await()
                }

                Log.d(TAG, "Channel opened: ${channelToken.path}")

                // Serialize and compress batch
                val jsonString = batch.toJsonString()
                val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
                val compressedData = compressData(jsonBytes)

                Log.d(TAG, "Batch ${batch.batchId}: ${jsonBytes.size} bytes -> ${compressedData.size} bytes compressed (${(compressedData.size * 100 / jsonBytes.size)}%)")

                // Get output stream and write data
                withTimeout(CHANNEL_STREAM_TIMEOUT_MS) {
                    val outputStream = channelClient.getOutputStream(channelToken).await()
                    outputStream.use { stream ->
                        // Write length prefix (4 bytes, big-endian)
                        writeInt(stream, compressedData.size)

                        // Write compressed data
                        stream.write(compressedData)
                        stream.flush()
                    }
                }

                Log.i(TAG, "✓ Successfully sent batch ${batch.batchId} via channel")
                true

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "✗ IO error sending batch ${batch.batchId}: ${e.message}", e)
                false

            } catch (e: Exception) {
                Log.e(TAG, "✗ Error sending batch ${batch.batchId}: ${e.message}", e)
                false
            } finally {
                // Always attempt to close the channel token to release resources.
                channelToken?.let { token ->
                    withContext(NonCancellable) {
                        try {
                            withTimeout(CHANNEL_CLOSE_TIMEOUT_MS) {
                                channelClient.close(token).await()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Channel close failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Send a calibration file to the phone via ChannelClient.
     *
     * Protocol:
     * 1. Filename length (4 bytes, big-endian)
     * 2. Filename (UTF-8 bytes)
     * 3. Compressed data length (4 bytes, big-endian)
     * 4. GZIP compressed file data
     *
     * @param file The calibration file to send
     * @param phoneNode The phone node to send to
     * @return true if sent successfully, false otherwise
     */
    suspend fun sendCalibrationFile(file: File, phoneNode: Node): Boolean {
        return withContext(Dispatchers.IO) {
            var channelToken: ChannelClient.Channel? = null

            try {
                Log.i(TAG, "Opening calibration channel to send file: ${file.name} (${file.length()} bytes)")

                when (isNodeConnected(phoneNode.id)) {
                    false -> {
                        Log.w(TAG, "Phone node ${phoneNode.id} is not connected; aborting calibration file send")
                        return@withContext false
                    }
                    true -> Unit
                    null -> Unit
                }

                // Open channel to phone
                channelToken = withTimeout(CHANNEL_OPEN_TIMEOUT_MS) {
                    channelClient.openChannel(phoneNode.id, CHANNEL_PATH_CALIBRATION).await()
                }

                Log.d(TAG, "Calibration channel opened: ${channelToken.path}")

                // Read and compress file
                val fileBytes = FileInputStream(file).use { it.readBytes() }
                val compressedData = compressData(fileBytes)

                Log.d(TAG, "Calibration file ${file.name}: ${fileBytes.size} bytes -> ${compressedData.size} bytes compressed")

                // Get output stream and write data with protocol header
                withTimeout(CHANNEL_STREAM_TIMEOUT_MS) {
                    val outputStream = channelClient.getOutputStream(channelToken).await()
                    outputStream.use { stream ->
                        // Write filename length (4 bytes, big-endian)
                        val filenameBytes = file.name.toByteArray(Charsets.UTF_8)
                        writeInt(stream, filenameBytes.size)

                        // Write filename
                        stream.write(filenameBytes)

                        // Write compressed data length (4 bytes, big-endian)
                        writeInt(stream, compressedData.size)

                        // Write compressed data
                        stream.write(compressedData)
                        stream.flush()
                    }
                }

                Log.i(TAG, "✓ Successfully sent calibration file ${file.name} via channel")
                true

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "✗ IO error sending calibration file ${file.name}: ${e.message}", e)
                false

            } catch (e: Exception) {
                Log.e(TAG, "✗ Error sending calibration file ${file.name}: ${e.message}", e)
                false
            } finally {
                channelToken?.let { token ->
                    withContext(NonCancellable) {
                        try {
                            withTimeout(CHANNEL_CLOSE_TIMEOUT_MS) {
                                channelClient.close(token).await()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Channel close failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Write integer as 4 bytes big-endian
     */
    private fun writeInt(stream: java.io.OutputStream, value: Int) {
        stream.write(value shr 24)
        stream.write(value shr 16)
        stream.write(value shr 8)
        stream.write(value)
    }

    /**
     * Compress data using GZIP
     */
    private fun compressData(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(data)
        }
        return outputStream.toByteArray()
    }
}
