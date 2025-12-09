package com.opensource.tremorwatch.communication

import android.content.Context
import android.util.Log
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    }

    private val channelClient: ChannelClient = Wearable.getChannelClient(context)

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

                // Open channel to phone
                channelToken = channelClient
                    .openChannel(phoneNode.id, CHANNEL_PATH_TREMOR_BATCH)
                    .await()

                Log.d(TAG, "Channel opened: ${channelToken.path}")

                // Serialize and compress batch
                val jsonString = batch.toJsonString()
                val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
                val compressedData = compressData(jsonBytes)

                Log.d(TAG, "Batch ${batch.batchId}: ${jsonBytes.size} bytes -> ${compressedData.size} bytes compressed (${(compressedData.size * 100 / jsonBytes.size)}%)")

                // Get output stream and write data
                val outputStream = channelClient.getOutputStream(channelToken!!).await()
                outputStream.use { stream ->
                    // Write length prefix (4 bytes, big-endian)
                    val lengthBytes = ByteArray(4)
                    lengthBytes[0] = (compressedData.size shr 24).toByte()
                    lengthBytes[1] = (compressedData.size shr 16).toByte()
                    lengthBytes[2] = (compressedData.size shr 8).toByte()
                    lengthBytes[3] = compressedData.size.toByte()
                    stream.write(lengthBytes)

                    // Write compressed data
                    stream.write(compressedData)
                    stream.flush()
                }

                Log.i(TAG, "✓ Successfully sent batch ${batch.batchId} via channel")

                // Close channel
                if (channelToken != null) {
                    channelClient.close(channelToken).await()
                }

                true

            } catch (e: IOException) {
                Log.e(TAG, "✗ IO error sending batch ${batch.batchId}: ${e.message}", e)
                // Close channel on error
                try {
                    if (channelToken != null) {
                        channelClient.close(channelToken).await()
                    }
                } catch (closeError: Exception) {
                    Log.w(TAG, "Failed to close channel after error: ${closeError.message}")
                }
                false

            } catch (e: Exception) {
                Log.e(TAG, "✗ Error sending batch ${batch.batchId}: ${e.message}", e)
                // Close channel on error
                try {
                    if (channelToken != null) {
                        channelClient.close(channelToken).await()
                    }
                } catch (closeError: Exception) {
                    Log.w(TAG, "Failed to close channel after error: ${closeError.message}")
                }
                false
            }
        }
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
