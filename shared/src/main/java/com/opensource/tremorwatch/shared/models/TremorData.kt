package com.opensource.tremorwatch.shared.models

import org.json.JSONObject

/**
 * Represents a single tremor data sample.
 * This is shared between watch and phone apps.
 */
data class TremorData(
    val timestamp: Long,           // Unix timestamp in milliseconds
    val severity: Double,          // Tremor severity value
    val tremorCount: Int,          // Number of tremors detected
    val metadata: Map<String, Any> = emptyMap()  // Additional metadata
) {
    /**
     * Convert to JSON for Data Layer transmission
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("severity", severity)
            put("tremor_count", tremorCount)
            if (metadata.isNotEmpty()) {
                put("metadata", JSONObject(metadata))
            }
        }
    }

    companion object {
        /**
         * Parse from JSON received via Data Layer with improved error handling
         */
        fun fromJson(json: JSONObject): TremorData {
            try {
                val timestamp = json.optLong("timestamp", 0L)
                if (timestamp == 0L) {
                    throw IllegalArgumentException("Missing or invalid timestamp in TremorData")
                }

                val severity = json.optDouble("severity", Double.NaN)
                if (severity.isNaN()) {
                    throw IllegalArgumentException("Missing or invalid severity in TremorData")
                }

                val tremorCount = json.optInt("tremor_count", 0)

                val metadata = mutableMapOf<String, Any>()
                if (json.has("metadata")) {
                    try {
                        val metaJson = json.optJSONObject("metadata")
                        if (metaJson != null) {
                            metaJson.keys().forEach { key ->
                                try {
                                    metadata[key] = metaJson.get(key)
                                } catch (e: Exception) {
                                    // Skip invalid metadata keys
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue with empty metadata if parsing fails
                    }
                }

                return TremorData(
                    timestamp = timestamp,
                    severity = severity,
                    tremorCount = tremorCount,
                    metadata = metadata
                )
            } catch (e: Exception) {
                if (e is IllegalArgumentException) {
                    throw e
                }
                throw IllegalArgumentException("Failed to parse TremorData from JSON: ${e.message}. JSON: ${json.toString().take(200)}", e)
            }
        }
    }
}
