package com.opensource.tremorwatch.phone.analytics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists confidence calibration model parameters for phone-side analytics.
 */
class ConfidenceCalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("confidence_calibration", Context.MODE_PRIVATE)

    fun save(model: ConfidenceCalibrationModel) {
        val payload = when (model) {
            is ConfidenceCalibrationModel.None -> JSONObject().apply {
                put("type", "none")
            }
            is ConfidenceCalibrationModel.Platt -> JSONObject().apply {
                put("type", "platt")
                put("a", model.a)
                put("b", model.b)
            }
            is ConfidenceCalibrationModel.Isotonic -> JSONObject().apply {
                put("type", "isotonic")
                put("x", JSONArray(model.knotsX))
                put("y", JSONArray(model.knotsY))
            }
        }
        prefs.edit().putString("model_json", payload.toString()).apply()
    }

    fun load(): ConfidenceCalibrationModel {
        val raw = prefs.getString("model_json", null) ?: return ConfidenceCalibrationModel.None
        return try {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "platt" -> ConfidenceCalibrationModel.Platt(
                    a = json.optDouble("a", 1.0),
                    b = json.optDouble("b", 0.0)
                )
                "isotonic" -> {
                    val x = json.optJSONArray("x")?.toDoubleList()
                    val y = json.optJSONArray("y")?.toDoubleList()
                    if (x == null || y == null || x.size < 2 || x.size != y.size) {
                        ConfidenceCalibrationModel.None
                    } else {
                        ConfidenceCalibrationModel.Isotonic(x, y)
                    }
                }
                else -> ConfidenceCalibrationModel.None
            }
        } catch (_: Exception) {
            ConfidenceCalibrationModel.None
        }
    }

    private fun JSONArray.toDoubleList(): List<Double> {
        val out = ArrayList<Double>(length())
        for (i in 0 until length()) {
            out.add(optDouble(i, 0.0))
        }
        return out
    }
}

