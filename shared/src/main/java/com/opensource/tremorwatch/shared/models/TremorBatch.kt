package com.opensource.tremorwatch.shared.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a batch of tremor samples.
 * Batching reduces Data Layer API calls and improves efficiency.
 */
data class TremorBatch(
    val batchId: String,              // Unique batch identifier
    val timestamp: Long,              // Batch creation timestamp
    val samples: List<TremorData>,    // Individual samples in this batch
    val watchId: String? = null       // Optional watch identifier
) {
    /**
     * Convert to JSON for Data Layer transmission
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("batch_id", batchId)
            put("timestamp", timestamp)
            put("sample_count", samples.size)
            watchId?.let { put("watch_id", it) }

            val samplesArray = JSONArray()
            samples.forEach { sample ->
                samplesArray.put(sample.toJson())
            }
            put("samples", samplesArray)
        }
    }

    /**
     * Convert to JSON string
     */
    fun toJsonString(): String {
        return toJson().toString()
    }

    companion object {
        /**
         * Parse from JSON received via Data Layer
         */
        fun fromJson(json: JSONObject): TremorBatch {
            try {
                val batchId = json.optString("batch_id", "")
                if (batchId.isEmpty()) {
                    throw IllegalArgumentException("Missing required field: batch_id")
                }

                val timestamp = json.optLong("timestamp", 0L)
                if (timestamp == 0L) {
                    throw IllegalArgumentException("Missing or invalid timestamp")
                }

                val samplesArray = json.optJSONArray("samples")
                val samples = mutableListOf<TremorData>()

                if (samplesArray != null) {
                    for (i in 0 until samplesArray.length()) {
                        try {
                            val sampleJson = samplesArray.optJSONObject(i)
                            if (sampleJson != null) {
                                samples.add(TremorData.fromJson(sampleJson))
                            }
                            // Silently skip invalid samples to allow partial batch recovery
                        } catch (e: Exception) {
                            // Continue parsing other samples - log will be done by caller
                            throw IllegalArgumentException("Failed to parse sample at index $i in batch $batchId: ${e.message}", e)
                        }
                    }
                }

                return TremorBatch(
                    batchId = batchId,
                    timestamp = timestamp,
                    samples = samples,
                    watchId = if (json.has("watch_id")) json.optString("watch_id", null) else null
                )
            } catch (e: Exception) {
                // Re-throw with more context
                if (e is IllegalArgumentException) {
                    throw e
                }
                throw IllegalArgumentException("Failed to parse TremorBatch from JSON: ${e.message}. JSON preview: ${json.toString().take(500)}", e)
            }
        }

        /**
         * Parse from JSON string with improved error handling
         */
        fun fromJsonString(jsonString: String): TremorBatch {
            if (jsonString.isBlank()) {
                throw IllegalArgumentException("Cannot parse empty JSON string")
            }

            try {
                val json = JSONObject(jsonString)
                return fromJson(json)
            } catch (e: org.json.JSONException) {
                throw IllegalArgumentException("Invalid JSON format: ${e.message}. JSON preview: ${jsonString.take(500)}", e)
            } catch (e: Exception) {
                if (e is IllegalArgumentException) {
                    throw e
                }
                throw IllegalArgumentException("Unexpected error parsing JSON: ${e.message}", e)
            }
        }
    }
}
