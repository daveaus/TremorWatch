TremorWatch Activity Recognition Filtering (2026-02-06)

Scope: Integrate Activity Recognition on the watch, apply activity-aware filtering to tremor confidence/severity, and propagate activity context into stored/exported data. This is written for general tremor detection use cases and avoids user-specific assumptions.

**Summary**
- Add Activity Recognition permission + Play Services Location dependency.
- Start/stop activity updates in the watch service and handle updates cheaply.
- Apply activity-aware multipliers in the detection engine and capture activity context in each sample.
- Extend detection config with activity filtering parameters (conservative defaults).
- Persist activity metadata to the phone database and include it in raw CSV export.

**Patch 1: Add Play Services Location dependency**
```diff
*** Begin Patch
*** Update File: gradle/libs.versions.toml
@@
 playServicesWearable = "18.0.0"
+playServicesLocation = "21.0.1"
@@
 play-services-wearable = { group = "com.google.android.gms", name = "play-services-wearable", version.ref = "playServicesWearable" }
+play-services-location = { group = "com.google.android.gms", name = "play-services-location", version.ref = "playServicesLocation" }
*** End Patch
```
```diff
*** Begin Patch
*** Update File: app/build.gradle.kts
@@
     // ---- Core Wear + Compose ----
     implementation(libs.play.services.wearable)
+    implementation(libs.play.services.location)
*** End Patch
```

**Patch 2: Activity Recognition permission + runtime request**
```diff
*** Begin Patch
*** Update File: app/src/main/AndroidManifest.xml
@@
     <uses-permission android:name="android.permission.BODY_SENSORS" />
+    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
*** End Patch
```
```diff
*** Begin Patch
*** Update File: app/src/main/java/MainActivity.kt
@@
         if (ContextCompat.checkSelfPermission(
                 this,
                 Manifest.permission.BODY_SENSORS
             ) != PackageManager.PERMISSION_GRANTED
         ) {
             permissionsToRequest.add(Manifest.permission.BODY_SENSORS)
         }
+
+        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
+            if (ContextCompat.checkSelfPermission(
+                    this,
+                    Manifest.permission.ACTIVITY_RECOGNITION
+                ) != PackageManager.PERMISSION_GRANTED
+            ) {
+                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
+            }
+        }
*** End Patch
```

**Patch 3: Activity update interval constant**
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/constants/MonitoringConstants.kt
@@
 object MonitoringConstants {
@@
     // ====================== UPLOAD & STORAGE ======================
@@
     const val MAX_PENDING_BATCHES = 144  // 24 hours of 10-minute batches (144 batches/day)
+
+    // ====================== ACTIVITY RECOGNITION ======================
+
+    /**
+     * Activity Recognition update interval in milliseconds.
+     * 5 seconds balances battery impact with responsiveness.
+     */
+    const val ACTIVITY_UPDATE_INTERVAL_MS = 5000L
*** End Patch
```

**Patch 4: Add activity filtering to TremorDetectionConfig**
```diff
*** Begin Patch
*** Update File: shared/src/main/java/com/opensource/tremorwatch/shared/models/TremorDetectionConfig.kt
@@
     /** Power threshold above which movement is classified as high activity (not tremor) */
-    val highActivityThreshold: Float = 5.0f
+    val highActivityThreshold: Float = 5.0f,
+
+    // === Activity Recognition Filtering ===
+    /** Enable activity-based filtering of tremor confidence/severity */
+    val activityFilteringEnabled: Boolean = true,
+    /** Multipliers applied by detected activity (0-1). */
+    val activityStillMultiplier: Float = 1.0f,
+    val activityTiltingMultiplier: Float = 0.5f,
+    val activityWalkingMultiplier: Float = 0.3f,
+    val activityRunningMultiplier: Float = 0.1f,
+    val activityOnBicycleMultiplier: Float = 0.1f,
+    val activityInVehicleMultiplier: Float = 0.05f,
+    val activityOnFootMultiplier: Float = 0.3f,
+    val activityUnknownMultiplier: Float = 0.7f,
+    /** Confidence thresholds (0-100) */
+    val activityHighConfidenceThreshold: Int = 75,
+    val activityMediumConfidenceThreshold: Int = 60,
+    val activityLowConfidenceThreshold: Int = 40,
+    /** Ignore activity states older than this */
+    val activityStaleThresholdMs: Long = 30_000L
 ) {
@@
         require(highActivityThreshold > lowTremorThreshold) {
             "High activity threshold must be greater than low tremor threshold"
         }
+
+        // Activity filtering validation
+        require(activityStillMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
+        require(activityTiltingMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
+        require(activityWalkingMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
+        require(activityRunningMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
+        require(activityOnBicycleMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
+        require(activityInVehicleMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
+        require(activityOnFootMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
+        require(activityUnknownMultiplier in 0.0f..1.0f) { "Activity multipliers must be in [0, 1]" }
+        require(activityHighConfidenceThreshold in 0..100) { "Activity confidence must be in [0, 100]" }
+        require(activityMediumConfidenceThreshold in 0..100) { "Activity confidence must be in [0, 100]" }
+        require(activityLowConfidenceThreshold in 0..100) { "Activity confidence must be in [0, 100]" }
+        require(activityHighConfidenceThreshold >= activityMediumConfidenceThreshold) {
+            "Activity high confidence must be >= medium confidence"
+        }
+        require(activityMediumConfidenceThreshold >= activityLowConfidenceThreshold) {
+            "Activity medium confidence must be >= low confidence"
+        }
+        require(activityStaleThresholdMs >= 0) { "Activity stale threshold must be >= 0" }
     }
@@
-        const val CURRENT_VERSION = 1
+        const val CURRENT_VERSION = 2
*** End Patch
```

**Patch 5: Activity-aware filtering in TremorMonitoringEngine**
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/engine/TremorMonitoringEngine.kt
@@
 import com.opensource.tremorwatch.TremorFFT
 import com.opensource.tremorwatch.constants.MonitoringConstants
 import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
+import com.google.android.gms.location.DetectedActivity
@@
     // Phase 5: Rolling baseline and severity calculation (opus45 review)
     // Note: BaselineManager requires context - not available without context parameter
     private val baselineManager: BaselineManager? = null
+
+    // Activity recognition state (updated by TremorService)
+    private data class ActivityState(
+        val type: Int,
+        val confidence: Int,
+        val updatedAtMs: Long
+    )
+
+    @Volatile
+    private var activityState = ActivityState(DetectedActivity.UNKNOWN, 0, 0L)
@@
     data class TremorData(
@@
         val tremorType: String = "unknown",          // Tremor type (resting, postural, essential, etc.)
         val tremorTypeConfidence: Float = 0f,        // Classification confidence (0-1)
-        val isRestingState: Boolean = false          // Whether detected in resting state
+        val isRestingState: Boolean = false,         // Whether detected in resting state
+        // Activity context (from Activity Recognition)
+        val activityType: String = "unknown",
+        val activityConfidence: Float = 0f,          // 0-1
+        val activityAgeMs: Long = -1L,
+        // Activity-adjusted metrics
+        val activityAdjustedConfidence: Float = 0f,
+        val activityAdjustedSeverity: Float = 0f,
+        val isReliableMeasurement: Boolean = false,
+        val excludeFromAnalysis: Boolean = false
     )
@@
     fun reset() {
@@
         currentEpisodeTremorCount = 0
+        activityState = ActivityState(DetectedActivity.UNKNOWN, 0, 0L)
     }
+
+    /**
+     * Update activity context from Activity Recognition.
+     */
+    fun updateActivity(type: Int, confidence: Int, updatedAtMs: Long = System.currentTimeMillis()) {
+        activityState = ActivityState(type, confidence.coerceIn(0, 100), updatedAtMs)
+    }
+
+    private data class ActivityAdjustment(
+        val adjustedConfidence: Float,
+        val adjustedSeverity: Float,
+        val isReliable: Boolean,
+        val excludeFromAnalysis: Boolean,
+        val activityType: Int,
+        val activityConfidence: Int,
+        val activityAgeMs: Long
+    )
+
+    private fun getActivityName(type: Int): String = when (type) {
+        DetectedActivity.STILL -> "still"
+        DetectedActivity.WALKING -> "walking"
+        DetectedActivity.RUNNING -> "running"
+        DetectedActivity.ON_BICYCLE -> "on_bicycle"
+        DetectedActivity.IN_VEHICLE -> "in_vehicle"
+        DetectedActivity.TILTING -> "tilting"
+        DetectedActivity.ON_FOOT -> "on_foot"
+        else -> "unknown"
+    }
+
+    private fun adjustForActivity(
+        baseConfidence: Float,
+        baseSeverity: Float,
+        nowMs: Long
+    ): ActivityAdjustment {
+        val state = activityState
+        val ageMs = if (state.updatedAtMs > 0L) nowMs - state.updatedAtMs else Long.MAX_VALUE
+        val isStale = ageMs > config.activityStaleThresholdMs
+
+        val activityType = if (isStale) DetectedActivity.UNKNOWN else state.type
+        val activityConfidence = if (isStale) 0 else state.confidence
+
+        val isReliable = !isStale &&
+            activityType == DetectedActivity.STILL &&
+            activityConfidence >= config.activityHighConfidenceThreshold
+
+        val excludeFromAnalysis = !isStale &&
+            activityConfidence >= config.activityHighConfidenceThreshold &&
+            (activityType == DetectedActivity.RUNNING ||
+             activityType == DetectedActivity.ON_BICYCLE ||
+             activityType == DetectedActivity.IN_VEHICLE)
+
+        if (!config.activityFilteringEnabled || isStale) {
+            return ActivityAdjustment(
+                adjustedConfidence = baseConfidence,
+                adjustedSeverity = baseSeverity,
+                isReliable = isReliable,
+                excludeFromAnalysis = excludeFromAnalysis,
+                activityType = activityType,
+                activityConfidence = activityConfidence,
+                activityAgeMs = if (ageMs == Long.MAX_VALUE) -1L else ageMs
+            )
+        }
+
+        val baseMultiplier = when (activityType) {
+            DetectedActivity.STILL -> config.activityStillMultiplier
+            DetectedActivity.TILTING -> config.activityTiltingMultiplier
+            DetectedActivity.WALKING -> config.activityWalkingMultiplier
+            DetectedActivity.RUNNING -> config.activityRunningMultiplier
+            DetectedActivity.ON_BICYCLE -> config.activityOnBicycleMultiplier
+            DetectedActivity.IN_VEHICLE -> config.activityInVehicleMultiplier
+            DetectedActivity.ON_FOOT -> config.activityOnFootMultiplier
+            else -> config.activityUnknownMultiplier
+        }
+
+        // Scale multiplier by activity confidence (0..1): low confidence -> minimal adjustment
+        val confidenceWeight = (activityConfidence / 100f).coerceIn(0f, 1f)
+        val effectiveMultiplier = 1f - (1f - baseMultiplier) * confidenceWeight
+
+        return ActivityAdjustment(
+            adjustedConfidence = (baseConfidence * effectiveMultiplier).coerceIn(0f, 1f),
+            adjustedSeverity = (baseSeverity * effectiveMultiplier).coerceAtLeast(0f),
+            isReliable = isReliable,
+            excludeFromAnalysis = excludeFromAnalysis,
+            activityType = activityType,
+            activityConfidence = activityConfidence,
+            activityAgeMs = if (ageMs == Long.MAX_VALUE) -1L else ageMs
+        )
+    }
@@
         val severity = if (finalIsTremor) {
             SeverityCalculator.calculateSeverity(
                 magnitude = magnitude,
                 dominantFrequency = dominantFrequency,
                 bandRatio = bandRatio,
                 confidence = finalConfidence,
                 episodeDuration = episodeDurationSec,
                 baselineMultiplier = baselineMultiplier
             )
         } else {
             0f
         }
@@
-        val tremorData = TremorData(
+        val activityAdjustment = adjustForActivity(
+            baseConfidence = finalConfidence,
+            baseSeverity = severity,
+            nowMs = now
+        )
+
+        val tremorData = TremorData(
             now,  // Use absolute wall clock timestamp
             datetimeIso,
             timeFormatted,
             x, y, z,
             magnitude,
             lastAccelMagnitude,
             finalIsTremor,  // Use filtered classification
             finalConfidence.coerceIn(0f, 1f),  // Use filtered confidence
             isWatchWorn,
             isCharging,
             dominantFrequency,
             tremorBandPower,
             totalPower,
             bandRatio,
             peakProminence,
             severity = severity,
             baselineMultiplier = baselineMultiplier,
             // Tremor type classification
             tremorType = tremorClassification?.primaryType?.name?.lowercase() ?: "none",
             tremorTypeConfidence = tremorClassification?.confidence ?: 0f,
-            isRestingState = tremorClassification?.isResting ?: isResting
+            isRestingState = tremorClassification?.isResting ?: isResting,
+            activityType = getActivityName(activityAdjustment.activityType),
+            activityConfidence = activityAdjustment.activityConfidence / 100f,
+            activityAgeMs = activityAdjustment.activityAgeMs,
+            activityAdjustedConfidence = activityAdjustment.adjustedConfidence,
+            activityAdjustedSeverity = activityAdjustment.adjustedSeverity,
+            isReliableMeasurement = activityAdjustment.isReliable,
+            excludeFromAnalysis = activityAdjustment.excludeFromAnalysis
         )
*** End Patch
```

**Patch 6: Activity Recognition integration in TremorService**
```diff
*** Begin Patch
*** Update File: app/src/main/java/com/opensource/tremorwatch/service/TremorService.kt
@@
 import android.app.Service
 import android.content.BroadcastReceiver
 import android.content.Context
 import android.content.Intent
 import android.content.IntentFilter
 import android.content.pm.ServiceInfo
+import android.content.pm.PackageManager
+import android.Manifest
@@
 import timber.log.Timber
 import androidx.core.app.NotificationCompat
+import androidx.core.content.ContextCompat
 import androidx.lifecycle.LifecycleService
@@
 import com.opensource.tremorwatch.constants.MonitoringConstants
@@
 import kotlinx.coroutines.launch
 import java.io.File
 import java.text.SimpleDateFormat
 import java.util.*
 import kotlin.math.sqrt
+import com.google.android.gms.location.ActivityRecognition
+import com.google.android.gms.location.ActivityRecognitionClient
+import com.google.android.gms.location.ActivityRecognitionResult
+import com.google.android.gms.location.DetectedActivity
@@
     companion object {
         // TAG removed - Timber uses class name automatically
+        private const val ACTION_ACTIVITY_UPDATE = "com.opensource.tremorwatch.ACTION_ACTIVITY_UPDATE"
+        private const val ACTIVITY_UPDATE_REQUEST_CODE = 4101
     }
@@
     private var wakeLock: PowerManager.WakeLock? = null
+
+    // Activity Recognition
+    private var activityRecognitionClient: ActivityRecognitionClient? = null
+    private var activityUpdatePendingIntent: PendingIntent? = null
+    private var activityUpdatesRegistered = false
@@
     private lateinit var monitoringEngine: TremorMonitoringEngine
@@
     override fun onCreate() {
@@
         monitoringEngine = TremorMonitoringEngine(
@@
         )
+
+        // Start activity recognition with default config (can be toggled by config updates)
+        updateActivityRecognitionState(true)
@@
         configListener = ConfigDataListener(this) { newConfig ->
             Timber.i("Received config update from phone: ${newConfig.profileName}")
             monitoringEngine.setConfig(newConfig)
+            updateActivityRecognitionState(newConfig.activityFilteringEnabled)
         }
@@
     override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
         super.onStartCommand(intent, flags, startId)  // LifecycleService transitions to STARTED state
+
+        if (intent?.action == ACTION_ACTIVITY_UPDATE) {
+            handleActivityUpdate(intent)
+            return START_STICKY
+        }
@@
     override fun onDestroy() {
@@
             // 3. Unregister sensors (fast operation)
             if (::sensorManager.isInitialized && ::monitoringEngine.isInitialized) {
                 sensorManager.unregisterListener(monitoringEngine)
                 Timber.d("Sensors unregistered")
             }
+
+            // 3b. Stop activity recognition updates
+            stopActivityRecognitionUpdates()
@@
     private fun convertToSharedFormat(data: TremorMonitoringEngine.TremorData): com.opensource.tremorwatch.shared.models.TremorData {
@@
         val metadata = mutableMapOf<String, Any>(
@@
             "tremorType" to data.tremorType,
             "tremorTypeConfidence" to data.tremorTypeConfidence,
-            "isRestingState" to data.isRestingState
+            "isRestingState" to data.isRestingState,
+            // Activity context + filtered values
+            "activityType" to data.activityType,
+            "activityConfidence" to data.activityConfidence,
+            "activityAgeMs" to data.activityAgeMs,
+            "activityAdjustedConfidence" to data.activityAdjustedConfidence,
+            "activityAdjustedSeverity" to data.activityAdjustedSeverity,
+            "isReliableMeasurement" to data.isReliableMeasurement,
+            "excludeFromAnalysis" to data.excludeFromAnalysis
         )
@@
     }
+
+    private fun hasActivityRecognitionPermission(): Boolean {
+        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
+            ContextCompat.checkSelfPermission(
+                this,
+                Manifest.permission.ACTIVITY_RECOGNITION
+            ) == PackageManager.PERMISSION_GRANTED
+        } else {
+            true
+        }
+    }
+
+    private fun createActivityPendingIntent(): PendingIntent {
+        val intent = Intent(this, TremorService::class.java).apply {
+            action = ACTION_ACTIVITY_UPDATE
+        }
+        return PendingIntent.getService(
+            this,
+            ACTIVITY_UPDATE_REQUEST_CODE,
+            intent,
+            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
+        )
+    }
+
+    private fun startActivityRecognitionUpdates() {
+        if (activityUpdatesRegistered) return
+        if (!hasActivityRecognitionPermission()) {
+            Timber.w("Activity Recognition permission not granted - skipping activity updates")
+            return
+        }
+
+        if (activityRecognitionClient == null) {
+            activityRecognitionClient = ActivityRecognition.getClient(this)
+        }
+
+        val pendingIntent = createActivityPendingIntent()
+        activityUpdatePendingIntent = pendingIntent
+
+        try {
+            activityRecognitionClient
+                ?.requestActivityUpdates(MonitoringConstants.ACTIVITY_UPDATE_INTERVAL_MS, pendingIntent)
+                ?.addOnSuccessListener {
+                    activityUpdatesRegistered = true
+                    Timber.i("Activity Recognition updates enabled")
+                }
+                ?.addOnFailureListener { e ->
+                    Timber.e("Failed to request activity updates: ${e.message}", e)
+                }
+        } catch (e: SecurityException) {
+            Timber.e("Missing ACTIVITY_RECOGNITION permission: ${e.message}")
+        }
+    }
+
+    private fun stopActivityRecognitionUpdates() {
+        val pendingIntent = activityUpdatePendingIntent ?: return
+        activityRecognitionClient?.removeActivityUpdates(pendingIntent)
+            ?.addOnSuccessListener {
+                Timber.i("Activity Recognition updates removed")
+            }
+        activityUpdatesRegistered = false
+        activityUpdatePendingIntent = null
+    }
+
+    private fun updateActivityRecognitionState(enabled: Boolean) {
+        if (enabled) {
+            startActivityRecognitionUpdates()
+        } else {
+            stopActivityRecognitionUpdates()
+        }
+    }
+
+    private fun handleActivityUpdate(intent: Intent) {
+        if (!ActivityRecognitionResult.hasResult(intent)) return
+        val result = ActivityRecognitionResult.extractResult(intent) ?: return
+        val activity = result.mostProbableActivity
+
+        if (::monitoringEngine.isInitialized) {
+            monitoringEngine.updateActivity(activity.type, activity.confidence, System.currentTimeMillis())
+        }
+
+        Timber.d("Activity update: ${activity.type} (${activity.confidence}%)")
+    }
*** End Patch
```

**Patch 7: Store activity metadata in the phone database**
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/database/TremorDatabaseHelper.kt
@@
 import kotlinx.serialization.encodeToString
 import kotlinx.serialization.json.Json
+import org.json.JSONObject
@@
     suspend fun saveBatch(batch: TremorBatch): Boolean = withContext(Dispatchers.IO) {
         try {
             val samples = batch.samples.map { sample ->
+                val metadataJson = try {
+                    JSONObject(sample.metadata).toString()
+                } catch (e: Exception) {
+                    null
+                }
+
                 TremorSample(
                     timestamp = sample.timestamp,
                     severity = sample.severity,
                     tremorCount = sample.tremorCount,
@@
                     isCharging = sample.metadata["isCharging"] as? Boolean,
                     confidence = sample.metadata["confidence"] as? Double,
                     watchId = sample.metadata["watch_id"] as? String,
-                    metadataJson = null  // Skip JSON for now - all important fields are extracted
+                    metadataJson = metadataJson
                 )
             }
*** End Patch
```

**Patch 8: Include activity fields in raw CSV export**
```diff
*** Begin Patch
*** Update File: phone/src/main/java/com/opensource/tremorwatch/phone/ui/dialogs/ExportDialog.kt
@@
 import java.text.SimpleDateFormat
 import java.util.Date
 import java.util.Locale
 import com.opensource.tremorwatch.phone.ChartData
 import com.opensource.tremorwatch.shared.models.TremorBatch
+import org.json.JSONObject
@@
 private suspend fun writeRawDataCsv(writer: FileWriter, context: Context, hoursBack: Int) {
@@
     writer.write("Timestamp,DateTime,Severity,Tremor Count,")
     writer.write("X,Y,Z,Magnitude,Accel Magnitude,Confidence,")
     writer.write("Is Worn,Is Charging,Dominant Freq,Tremor Band Power,")
-    writer.write("Total Power,Band Ratio,Peak Prominence,Watch ID\n")
+    writer.write("Total Power,Band Ratio,Peak Prominence,Watch ID,")
+    writer.write("Activity Type,Activity Confidence,Activity Age Ms,")
+    writer.write("Adjusted Severity,Adjusted Confidence,Is Reliable,Exclude From Analysis\n")
@@
     samples.sortedBy { it.timestamp }.forEach { sample ->
         val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
             .format(Date(sample.timestamp))
+
+        val metadata = sample.metadataJson?.let {
+            runCatching { JSONObject(it) }.getOrNull()
+        }
+        fun metaValue(key: String): String = metadata?.opt(key)?.toString() ?: ""
@@
         writer.write("${sample.totalPower ?: ""},${sample.bandRatio ?: ""},${sample.peakProminence ?: ""},")
-        writer.write("${sample.watchId ?: ""}\n")
+        writer.write("${sample.watchId ?: ""},")
+        writer.write("${metaValue("activityType")},${metaValue("activityConfidence")},${metaValue("activityAgeMs")},")
+        writer.write("${metaValue("activityAdjustedSeverity")},${metaValue("activityAdjustedConfidence")},")
+        writer.write("${metaValue("isReliableMeasurement")},${metaValue("excludeFromAnalysis")}\n")
     }
 }
*** End Patch
```

**Optional UI follow-up (not required for core detection):**
- Add an Activity Filtering section in `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt` to let users tune multipliers and thresholds for different activities.
- Display current detected activity and confidence in the watch UI if desired.

**Notes**
- The filtering is conservative by default and intended to reduce false positives from voluntary motion while preserving resting tremor detection.
- Activity-based adjustments are applied to confidence and severity only; the raw tremor detection flag remains unchanged for transparency.
- Activity updates are ignored when stale to avoid misclassification during update gaps.

---

## AI Feedback (Full)

### DeepSeek
I'll review your TremorWatch activity-aware enhancement code. Overall, this is a well-structured implementation that adds valuable context to tremor detection. Here are my detailed findings:

## **Critical Issues & Bugs**

### 1. **Memory Leak in ActivityRecognitionClient**
```kotlin
// In TremorService.kt
private var activityRecognitionClient: ActivityRecognitionClient? = null
```
**Issue**: The `ActivityRecognitionClient` is never properly cleaned up. The Play Services client should be released when no longer needed.

**Fix**:
```kotlin
override fun onDestroy() {
    // ... existing cleanup ...
    activityRecognitionClient = null // Add this
}
```

### 2. **Potential Null Pointer in `adjustForActivity`**
```kotlin
val state = activityState
val ageMs = if (state.updatedAtMs > 0L) nowMs - state.updatedAtMs else Long.MAX_VALUE
```
**Issue**: If `activityState` is accessed concurrently while being updated via `@Volatile`, there's a risk of inconsistent state. The `ActivityState` copy is not atomic for its three fields.

**Fix**: Use `@Synchronized` or `AtomicReference`:
```kotlin
private val activityStateRef = AtomicReference(ActivityState(DetectedActivity.UNKNOWN, 0, 0L))

fun updateActivity(type: Int, confidence: Int, updatedAtMs: Long = System.currentTimeMillis()) {
    activityStateRef.set(ActivityState(type, confidence.coerceIn(0, 100), updatedAtMs))
}
```

### 3. **Missing Permission Check in Service**
**Issue**: The service starts activity recognition in `onCreate()` before checking if permission is granted. This could cause `SecurityException` on Android Q+.

**Fix**: Move permission check before starting:
```kotlin
override fun onCreate() {
    // ... existing code ...
    
    // Only start if permission granted
    if (hasActivityRecognitionPermission()) {
        updateActivityRecognitionState(true)
    } else {
        Timber.w("Activity Recognition permission not granted - skipping initialization")
    }
}
```

## **Best Practices & Architecture**

### 1. **Sensor Lifecycle Management**
Your approach of starting/stopping activity recognition based on `activityFilteringEnabled` is good. However, consider:

- **Add debouncing** to avoid rapid toggling if config changes frequently
- **Consider foreground service requirements**: On Android 10+, activity recognition might require foreground service. Add:

```kotlin
private fun ensureForegroundForActivityRecognition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Ensure service is in foreground when activity recognition is active
        if (activityUpdatesRegistered && !isForegroundService) {
            startForeground()
        }
    }
}
```

### 2. **Data Persistence Efficiency**
**Issue**: Storing full JSON metadata for every sample could significantly increase database size.

**Consideration**: For long-term monitoring, this could become problematic. Consider:
- Storing activity data in separate columns instead of JSON
- Using compression for metadata
- Adding retention policy for raw activity data

### 3. **Configuration Version Migration**
**Issue**: You increment `CURRENT_VERSION` from 1 to 2, but there's no migration logic for existing users.

**Recommendation**: Add migration strategy:
```kotlin
const val CURRENT_VERSION = 2
val DEFAULT_V1 = TremorDetectionConfig(...) // old defaults
val DEFAULT_V2 = TremorDetectionConfig(...) // new with activity filtering
```
## **Performance Optimizations**

### 1. **Battery Impact Assessment**
- **5-second update interval**: Reasonable, but consider making it configurable based on battery level
- **No batching**: You're using `requestActivityUpdates()` without batching. On newer devices, consider:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    activityRecognitionClient?.requestActivityUpdates(
        MonitoringConstants.ACTIVITY_UPDATE_INTERVAL_MS,
        pendingIntent
    )
} else {
    // Use deprecated API for older devices
}
```

### 2. **CPU Optimization in `adjustForActivity`**
The current implementation recalculates multipliers for every tremor sample. Consider:

```kotlin
// Cache the multiplier when activity state changes
private var cachedMultiplier: Float = 1.0f
private var cachedActivityType: Int = DetectedActivity.UNKNOWN

private fun updateCachedMultiplier(state: ActivityState, nowMs: Long) {
    // Calculate and cache once
    cachedMultiplier = calculateMultiplier(state, nowMs)
    cachedActivityType = state.type
}
```

### 3. **Memory Usage**
**Issue**: Adding 8 new fields to every `TremorData` object increases memory pressure, especially during episodes.

**Mitigation**: Consider making activity context optional or using smaller data types:
- Use `Byte` for activity type instead of `String`
- Use `Short` for confidence (0-100 scale)

## **Code Quality Improvements**

### 1. **Error Handling Gaps**
```kotlin
// In handleActivityUpdate
val result = ActivityRecognitionResult.extractResult(intent) ?: return
```
**Missing**: Log when result extraction fails. Add:
```kotlin
val result = ActivityRecognitionResult.extractResult(intent)
if (result == null) {
    Timber.w("Failed to extract activity recognition result")
    return
}
```

### 2. **Constants Organization**
**Suggestion**: Group activity-related constants:
```kotlin
// Instead of spreading through MonitoringConstants
object ActivityConstants {
    const val UPDATE_INTERVAL_MS = 5000L
    const val STALE_THRESHOLD_MS = 30_000L
    const val HIGH_CONFIDENCE = 75
    // ...
}
```

### 3. **Type Safety**
**Issue**: Using raw `Int` for activity types throughout. Consider:
```kotlin
enum class ActivityType(val detectedValue: Int) {
    STILL(DetectedActivity.STILL),
    WALKING(DetectedActivity.WALKING),
    // ...
}
```
## **Integration & Data Accuracy**

### 1. **Tremor Detection Improvement Strategy**
Your approach is sound:
- **Conservative defaults**: Good for minimizing false negatives
- **Confidence-weighted adjustments**: Excellent approach
- **Stale data handling**: Critical for reliability

**Potential enhancement**: Consider activity **transitions**. Tremors might increase during activity transitions (sit-to-stand, etc.). You could add:

```kotlin
private var previousActivity: ActivityState? = null
val isTransition = previousActivity?.type != currentActivity.type
// Apply different multiplier during transitions
```

### 2. **Validation of Multiplier Values**
The validation ensures multipliers are 0-1, but consider:
- Document that 0.0 means "completely ignore tremors during this activity"
- 1.0 means "no adjustment"
- Add warning if users set extreme values

### 3. **Cross-Device Consistency**
**Issue**: Activity recognition accuracy varies between devices and wear OS versions.

**Mitigation**: Consider calibrating based on device model or adding device-specific adjustments.

### 4. **Testing Strategy**
**Missing**: Unit tests for activity adjustment logic. Critical test cases:
- Stale activity data
- Edge confidence values (0, 100)
- All activity types
- Transition scenarios

## **Specific Code Fixes Needed**

### 1. **Fix CSV Export Format**
```kotlin
// Missing error handling for missing metadata
fun metaValue(key: String): String = metadata?.opt(key)?.toString() ?: ""
```
Should handle null/empty better:
```kotlin
fun metaValue(key: String): String = 
    metadata?.opt(key)?.takeIf { it != JSONObject.NULL }?.toString() ?: ""
```

### 2. **Improve JSON Serialization**
```kotlin
// In TremorDatabaseHelper.kt
val metadataJson = try {
    JSONObject(sample.metadata).toString()
} catch (e: Exception) {
    null
}
```
Use Kotlinx Serialization consistently instead of mixing with `org.json.JSONObject`.

### 3. **Add Configuration Defaults Validation**
```kotlin
// Ensure multipliers are sorted logically
require(activityStillMultiplier >= activityTiltingMultiplier) {
    "Still activity should have highest confidence"
}
require(activityInVehicleMultiplier <= activityOnBicycleMultiplier) {
    "Vehicle motion should be more filtered than bicycle"
}
```

## **Summary of Priority Changes**

**High Priority (Fix before deployment):**
1. Fix memory leak with `ActivityRecognitionClient`
2. Add atomic reference for `activityState`
3. Add permission check before service initialization

**Medium Priority (Important improvements):**
1. Add migration for config version 2
2. Implement proper error logging
3. Optimize memory usage in `TremorData`
4. Add unit tests for activity adjustment logic

**Low Priority (Enhancements):**
1. Add activity transition detection
2. Make update interval configurable
3. Add device-specific calibration
4. Implement CSV export error handling

This enhancement significantly improves TremorWatch's clinical utility by contextualizing tremor data. The architecture is well thought out, and with these fixes, it should be robust for production use.

### Grok
The code you've shared represents a solid, thoughtful enhancement to make **TremorWatch** activity-contextual. It follows a conservative, safety-first approach (which is appropriate for a medical-adjacent symptom tracking tool), adds useful metadata for later analysis, and avoids over-filtering resting tremor signals.

Below is a structured review covering the requested points.

### 1. Bugs / Logical Errors / Edge Cases / Crash Risks

- **Activity age handling when never received**  
  In `adjustForActivity(...)` ? `ageMs = if (state.updatedAtMs > 0L) nowMs - state.updatedAtMs else Long.MAX_VALUE`  
  ? `isStale = ageMs > config.activityStaleThresholdMs`  
  This works, but when no update has ever arrived, `activityAgeMs` in `TremorData` becomes `-1L` (good), but downstream consumers (Grafana, CSV, ML training later) should treat `activityAgeMs < 0` as "no activity data ever received".  
  ? Consider adding explicit comment or a constant `ACTIVITY_AGE_NEVER = -1L`.

- **Confidence coercion**  
  `activity.confidence.coerceIn(0, 100)` is good, but Google usually delivers 0–100 already. Harmless, but unnecessary.

- **Stale ? UNKNOWN logic** is clean and correct.

- **excludeFromAnalysis** logic only excludes very high-confidence running/bicycle/vehicle.  
  ? Missing: high-confidence **ON_FOOT** can sometimes be very tremor-like in some patients (shuffling gait). Current logic keeps it (multiplier 0.3). This is probably intentional (conservative), but document it clearly as a deliberate choice.

- **PendingIntent flags**  
  `PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE` ? correct for modern Android (especially Wear OS targeting API 33+).

- **SecurityException catch** in `startActivityRecognitionUpdates()` is good, but consider also listening for permission revocation broadcast (rare on Wear, but possible).

- **No obvious NPE risks** — `@Volatile`, late init checks, safe calls on nullable clients.

### 2. Best Practices – Android Wear APIs, Lifecycle, Permissions

**Major concern – Activity Recognition API style (2026 perspective)**

The code still uses the older continuous `requestActivityUpdates(..., detectionIntervalMillis, PendingIntent)` style.

As of ~2022–2024 Google has been pushing the **[Activity Recognition Transition API](https://developers.google.com/location-context/activity-recognition-transition-api)** (`requestActivityTransitionUpdates(ActivityTransitionRequest, PendingIntent)`) as the **preferred** method because:

- It consumes **significantly less power** (batches transitions instead of polling)
- Provides **enter/exit** events rather than periodic most-probable-activity
- Better reflects real user behaviour changes

The classic `requestActivityUpdates` is **not formally deprecated** yet (still present in 2025–2026 docs), but:

- Play Services release notes & best-practice guides repeatedly recommend Transition API for new code
- It has better battery characteristics (important on Wear)
- Wear OS 5/6 + Health Services push even more towards event-driven / goal-based sensing

**Recommendation (strong)**

Migrate to Transition API:

```kotlin
val request = ActivityTransitionRequest.Builder()
    .addActivityTransition(ActivityTransition.Builder()
        .setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        .build())
    // add other interesting transitions: WALKING enter/exit, RUNNING enter, etc.
    .build()

activityRecognitionClient
    .requestActivityTransitionUpdates(request, pendingIntent)
```

You then receive **transitions** and can maintain your own "current probable activity" state machine (most apps do this anyway).

This would be a **significant battery win** on a watch.

**Other points**

- Permission request in `MainActivity` — correct (Q+ check).
- Service lifecycle: `LifecycleService` + `onDestroy()` cleanup ? good.
- `updateActivityRecognitionState()` reacting to config change ? excellent.
- No sticky start flag abuse — returns `START_STICKY` only after handling broadcast (correct).
### 3. Performance / Battery Optimizations

**Good parts**

- 5-second interval is already quite relaxed (many apps use 10–60 s).
- Only most probable activity is used ? cheap.
- `@Volatile` + cheap copy in `adjustForActivity()` ? no lock contention.
- Stale data fallback to UNKNOWN ? prevents stuck bad state draining trust in metrics.

**Improvement opportunities**

- Switch to **Transition API** (see above) — biggest single win.
- Consider making interval **configurable** or adaptive (longer when battery < 20%).
- On Wear OS, activity recognition already uses fused hardware (very efficient), but still: every 5 s wakes cause small-but-cumulative drain.
- No batching of tremor samples with activity — but that's probably intentional (independent rates).

**Potential risk**

If the service restarts very frequently (Doze, app kill, watch reboot), you request updates again ? many duplicate requests.  
? Consider checking `activityUpdatesRegistered` more defensively or use a persisted flag.

### 4. Code Quality / Readability / Maintainability

- Very clean overall — good separation (Engine vs Service).
- Constants centralized.
- Clear validation in config.
- Descriptive names (`activityAdjustedConfidence`, `excludeFromAnalysis`).
- Timber usage consistent.

**Minor suggestions**

- Move `getActivityName()` to a top-level/extension function or companion object — used in two places.
- Consider enum class instead of magic `DetectedActivity.XXX` ints for internal state (but minor).
- `ActivityState` could be `data class` with default `updatedAtMs = 0L`.
- Add KDoc to public `updateActivity(...)` and `adjustForActivity(...)`.

### 5. Integration Concerns

- Clean: activity state decoupled, injected via `updateActivity()`.
- Metadata flows nicely to `TremorData` ? shared model ? phone DB ? CSV.
- Raw tremor flag untouched ? good for auditability ("what the accelerometer saw" vs "what we believe after context").
- `excludeFromAnalysis` flag is very valuable for downstream trend / medication effect analysis.

**One gap**

No handling of multiple probable activities. Current code only uses `mostProbableActivity`.  
In ~30–40% of updates the second/third activities have meaningful confidence (especially TILTING + WALKING).  
? Consider weighted average multiplier or at least log secondary activities for debugging.

### 6. Data Accuracy – Will This Really Improve Tremor Detection?

**Yes — meaningfully — but with caveats**

**Strengths**

- Very conservative defaults (still = 1.0, unknown = 0.7, walking = 0.3, running/vehicle/bike ˜ 0.1).
- Confidence-weighted smoothing (`effectiveMultiplier`) — prevents over-correction on 50–60% conf activities.
- `excludeFromAnalysis` for very confident non-ambulatory high-motion ? strong protection against artifact pollution.
- `isReliableMeasurement` = high-conf STILL ? useful for researchers / clinicians wanting "clean" resting tremor epochs.

**Limitations / realism check**

- Activity Recognition on wrist is **noisy** (especially TILTING vs postural tremor vs light walking).
- Many Parkinson's patients exhibit **shuffling gait** ? classified as WALKING/ON_FOOT with medium confidence ? gets 0.3 multiplier ? may suppress real tremor during gait.
- **Tilting** is very common during many intentional arm movements ? 0.5 multiplier may be too aggressive for postural/action tremor.
- 5-second updates + stale 30 s ? gaps during activity changes can lead to wrong context for several samples.

**Suggested tuning directions (after collecting data)**

- Possibly raise `onFootMultiplier` to 0.5–0.6 if many false negatives during slow walking.
- Consider **not** applying multiplier when activity confidence < ~50–55% (floor the weight).
- Log raw vs adjusted values for a cohort ? compute sensitivity/specificity gains per activity class.

**Overall verdict**

This is a **high-value** first step toward context-aware tremor assessment — better than raw sensor-only in almost all real-world scenarios.

The biggest remaining gains would come from:

1. Migrating to **Activity Transition API** (battery + possibly slightly better change detection)
2. Collecting real patient data to tune multipliers per activity (especially ON_FOOT / TILTING)
3. Possibly fusing with **Health Services** on Wear OS 4+/5+ for better activity labels (step count, exercise state)

Great work — this patch significantly raises the scientific usefulness of the dataset.

Let me know if you want focused suggestions on the Transition API migration code.

### Perplexity
Overall this patch set is solid: the permission and dependency wiring are correct, the activity pipeline is integrated cleanly into the existing engine, and the defaults are conservative enough that you’re unlikely to degrade tremor detection in real use.

Below I’ll go area-by-area with concrete issues and improvements.

Bugs and edge cases
Versioned config compatibility
You bumped TremorDetectionConfig.CURRENT_VERSION to 2 and added required fields with defaults, which is good, but make sure both watch and phone handle older serialized configs gracefully (e.g., v1 from phone, v2 on watch). If you use kotlinx.serialization with default values this is fine; if you use manual JSON, verify missing fields don’t throw.?

Activity state default / age
activityState defaults to updatedAtMs = 0L, and ageMs becomes Long.MAX_VALUE, which you then map to -1L for activityAgeMs. That’s consistent, but you’re also using the stale check to decide isReliable and excludeFromAnalysis. For the initial window you’ll always treat activity as UNKNOWN and non-reliable, which is what you want. Just be aware that any UI showing “activityAgeMs == -1” needs to treat this as “no data yet”, not “very old data”.

ActivityRecognitionResult.hasResult(intent) in service
Using a Service PendingIntent is supported by ActivityRecognitionClient (the docs allow any PendingIntent target), and your pattern of checking ACTION_ACTIVITY_UPDATE in onStartCommand() and then calling handleActivityUpdate() is sound.?
One subtlety: if some other component ever starts TremorService with the same action but without an ActivityRecognitionResult extra, you’ll just return early; that’s harmless but worth keeping in mind.

stopActivityRecognitionUpdates() error handling
You call removeActivityUpdates() and ignore failures. That’s usually okay, but if the client throws a SecurityException because the permission was revoked, you’ll leave activityUpdatesRegistered set to false while Play services still holds the old request. Wrapping the call in a try/catch SecurityException with a log, mirroring startActivityRecognitionUpdates(), would make this more robust.?

Missing client shutdown
You never null out activityRecognitionClient. That’s not a functional bug, but you could set it to null in stopActivityRecognitionUpdates() after the success callback to avoid holding on to a stale client instance if the service is restarted frequently.

Phone DB metadataJson serialization
In TremorDatabaseHelper.saveBatch, you construct metadataJson via JSONObject(sample.metadata).toString(). This assumes sample.metadata only contains JSON-safe primitive values. If anything is non-primitive or nullable in a weird way, you’ll hit the catch and silently drop metadataJson for that sample. That’s okay, but it means CSV export will lose activity fields for those samples. Given you already have a curated set of typed columns, this is probably acceptable; just be aware that “invalid” metadata now causes a silent loss of extended fields.

Best practices (Wear / sensors / lifecycle)
Permission handling
Manifest + runtime request for ACTIVITY_RECOGNITION gated on Q+ is correct, and you fall back to “always allowed” below Q, which matches the platform behavior.?
One addition you may want: if activityFilteringEnabled is on in config but the permission is denied, you currently just log a warning and skip requests. From a user-experience perspective, you might also:

set an internal flag so the engine knows “filtering requested but unavailable”, and

surface that in the phone UI or logs so the user understands why activity columns are empty.

Foreground service / service types
TremorService is already a long-running foreground service. On Android 14+ you must declare the correct foreground service type in the manifest (e.g., android:foregroundServiceType="health" or location|dataSync as appropriate). 

Because you’re using body sensors and activity recognition on a Wear OS 4 device, verify that:

the service is declared with an appropriate type, and

the device’s health/body-sensor restrictions do not require additional “while in use” handling for background operation.

PendingIntent target choice
Using PendingIntent.getService() is okay here because the service is already foreground and running, and the intent just re-enters onStartCommand. If you ever want activity updates when the service is not running, a dedicated BroadcastReceiver + getBroadcast() would be more standard, but for your architecture, using the existing service is simpler and fine.

Lifecycle wiring
You start activity recognition from onCreate() and adjust it when config changes; you stop it in onDestroy(). That’s exactly how Google’s samples recommend doing it: “request updates when the service starts, remove updates when the service is killed.” The only tweak I’d consider is deferring updateActivityRecognitionState(true) until after you’ve loaded initial config, so you don’t briefly start updates when activityFilteringEnabled is false in the current profile.?
Performance and battery
Interval choice
A 5-second ACTIVITY_UPDATE_INTERVAL_MS is quite aggressive but still acceptable on Wear if your use case is medical and the watch is charging nightly. Google notes that the battery cost is directly related to the interval you specify: longer intervals mean fewer sensor wakeups.?
If you want a low-impact default, consider 10–15 seconds and allow profiles to opt into 5 seconds for “research mode”. That still gives you good context for filtering tremor episodes that last tens of seconds.

Using requestActivityUpdates vs transitions
requestActivityUpdates() is the lower-level raw API and requires you to manage the noise and power tradeoffs yourself. For your use case (you want continuous labels for every batch of tremor data), the raw sampling API is appropriate. Transition API is better if you only need “entered walking, exited walking” style events.

Cheap processing path
handleActivityUpdate() does exactly one extraction, one updateActivity(), and a log line. That’s lightweight enough that even 5-second intervals will be negligible compared to your FFT and severity calculations.

Extra fields in TremorData
The added fields in TremorMonitoringEngine.TremorData and JSON metadata increase payload size slightly, but relative to your existing sample batches the overhead is tiny. Your decision to adjust confidence/severity only and not the raw classification flag also keeps the computation simple and avoids additional FFT work.

Code quality and maintainability
Validation in TremorDetectionConfig
The additional require checks for multipliers and thresholds are a good guardrail. You could reduce boilerplate with a helper like private fun Float.requireMultiplier(name: String) = require(this in 0.0f..1.0f) { "$name must be in [0,1]" }, but that’s cosmetic.

Activity naming and metadata consistency
getActivityName() mapping to strings like "still", "walking", "on_bicycle" is clear and stable for CSVs and dashboards. Keeping Unknown as "unknown" is also consistent with your existing use of "none" for tremor type.

Single responsibility
The activity logic is cleanly encapsulated in TremorMonitoringEngine as:

updateActivity(...) for state updates, and

adjustForActivity(...) for applying multipliers.
TremorService remains responsible for binding to Play Services and pushing updates into the engine, which keeps the boundaries clear.

Logging
The logs in startActivityRecognitionUpdates and handleActivityUpdate are sufficiently descriptive. Just be mindful of log volume at 5-second intervals; Timber.d("Activity update: ...") will generate ~720 logs per hour. You might want to log only when the type changes or confidence crosses key thresholds.

Integration with tremor detection and data accuracy
Filter design and multipliers
The core idea — adjust severity and confidence based on activity class + confidence — is sound for reducing motion artifacts:

High-confidence STILL leaves values unchanged (multiplier 1.0).

Walking / ON_FOOT down-weight to 0.3 by default.

Running / ON_BICYCLE / IN_VEHICLE down-weight more aggressively.
Scaling the multiplier via

effectiveMultiplier
=
1
-
(
1
-
baseMultiplier
)
×
confidenceWeight
effectiveMultiplier=1-(1-baseMultiplier)×confidenceWeight
means that low classifier confidence leaves the metrics close to their unfiltered values, which is what you want to avoid over-correcting on noisy activity predictions.

“Reliable” and “excludeFromAnalysis” flags
Marking only high-confidence STILL as isReliableMeasurement is appropriately conservative for resting tremor analysis.
Excluding high-confidence RUNNING / ON_BICYCLE / IN_VEHICLE from analysis aligns with the goal of filtering voluntary gross movement. You can use these flags downstream to:

mask out points from particular dashboards,

or drive Grafana queries (e.g., filter excludeFromAnalysis=false for clinical views).

Keeping raw classification intact
You keep finalIsTremor and the base severity, and export both original and activity-adjusted values, which is excellent for traceability. This lets you:

compare adjusted vs unadjusted series in Grafana,

and change multipliers in future versions without losing the ability to re-interpret old data.

CSV export consistency
The added CSV columns (Activity Type, Activity Confidence, Activity Age Ms, Adjusted Severity, Adjusted Confidence, Is Reliable, Exclude From Analysis) match the metadata keys populated in TremorService. This should make it straightforward to join activity context with tremor severity in external tools.

Targeted improvement suggestions
If you want to tighten this further, I’d suggest:

Make the activity interval configurable via TremorDetectionConfig (with 5–15 seconds as a suggested range) and wire it into requestActivityUpdates, so “research profiles” can be more aggressive than everyday use.?

Add a small bit of logic to reduce log chatter, e.g., only log when activityType changes or when confidence crosses a threshold boundary.

In the phone UI, surface a simple indicator if activityFilteringEnabled is true in config but the watch lacks ACTIVITY_RECOGNITION permission, so users don’t assume activity-aware filtering is active when it isn’t.

Consider exposing isReliableMeasurement as a tag or field in Influx, and default your key clinical dashboards to use it as a filter predicate.

If you paste TremorDetectionConfig’s serialization and where CURRENT_VERSION is used, I can also sanity-check migrations and how v1/v2 configs interact between phone and watch.

### Google
This is a sophisticated and well-structured patch set. You have correctly identified the three pillars of this feature: Permission Management, Service Integration, and Data Propagation.

The logic for "Activity-Adjusted Confidence" is particularly smart—by keeping the raw detection intact and adding an adjusted metric, you preserve data integrity for future analysis while solving the immediate UX problem of false positives.

However, there are two critical performance concerns (battery and memory) regarding the update interval and the JSON serialization in tight loops.

1. Critical Issues & Bugs
A. The "Death by JSON" Performance Trap (Patch 7 & 8)
In TremorDatabaseHelper.kt and ExportDialog.kt, you are instantiating a JSONObject for every single sample.

The Problem: If TremorSample represents high-frequency accelerometer data (e.g., 50Hz or 100Hz), a 10-minute batch contains 30,000 to 60,000 samples. Creating 60k JSONObject instances inside a map loop will trigger massive Garbage Collection (GC) churn, causing dropped frames or blocking the IO thread significantly.

The Fix: Use manual string concatenation for simple JSON export, or Kotlin Serialization, which is much faster. For CSV export, do not re-parse the JSON string back into a JSONObject.

Kotlin
// In TremorDatabaseHelper.kt - Optimizing serialization
val metadataJson = StringBuilder().apply {
    append("{")
    append("\"activityType\":\"${sample.metadata["activityType"]}\",")
    append("\"activityConfidence\":${sample.metadata["activityConfidence"]},")
    // ... add other fields
    append("}")
}.toString()
B. Battery Drain: 5-Second Update Interval (Patch 3)
The Problem: ACTIVITY_UPDATE_INTERVAL_MS = 5000L is extremely aggressive for a background Wear OS service. The Activity Recognition API uses the accelerometer and requires the AP (Application Processor) to wake up to process transitions. A 5-second interval will prevent the watch from entering deep sleep (Doze), significantly impacting battery life.

Recommendation: For context awareness (which doesn't change instantly), 30 to 60 seconds is the standard industry best practice.

Fix: Increase ACTIVITY_UPDATE_INTERVAL_MS to 30000L.
2. Logic & Algorithm Review
A. The Multiplier Logic (Patch 5)
The math in adjustForActivity is sound.

Strength: effectiveMultiplier = 1f - (1f - baseMultiplier) * confidenceWeight is an excellent formula. It ensures that if the Activity Recognition is low confidence (e.g., "Walking 40%"), you don't aggressively penalty-box the tremor data.

Observation: You treat TILTING with a 0.5 multiplier. In Wear OS, TILTING is often triggered by the "raise to wake" gesture. This creates a specific rotational acceleration that mimics tremor. You might want to be even more aggressive here (e.g., 0.3 or 0.0 exclusion) because looking at the watch is a voluntary movement that invalidates resting tremor analysis.

3. Best Practices & Integration
A. Service vs. Receiver (Patch 6)
You are using PendingIntent.getService to deliver activity updates directly to TremorService.

Analysis: While this works because you handle it in onStartCommand, the standard pattern is PendingIntent.getBroadcast pointing to a BroadcastReceiver, which then updates the Service.

Why it matters: If the system kills TremorService to reclaim memory, the getService PendingIntent might attempt to restart the service (depending on flags). If you want the updates to stop when the service dies, this integration is actually fine, provided stopActivityRecognitionUpdates() is reliably called in onDestroy.

B. Permission Handling (Patch 2)
Good: You correctly check Build.VERSION_CODES.Q.

Missing Edge Case: If the user denies the permission permanently ("Don't ask again"), the requestPermissions call does nothing. You should add a check in onRequestPermissionsResult (or your permission launcher callback) to detect permanent denial and show a UI explaining why the feature is disabled.

4. Code Quality Refactoring
Simplify the CSV Export (Patch 8): Currently, you are Parsing JSON ? Extracting String ? Writing to CSV. This is heavy. Since you are already mapping properties in TremorData, just pass the raw map values if available, or parse the metadata once into a helper object before the loop if you only have the JSON string.

Kotlin
// Improved CSV Export Loop
// Avoids creating a JSONObject and helper function 60,000 times
samples.sortedBy { it.timestamp }.forEach { sample ->
    // Assuming you can access the map directly if strictly typed, 
    // OR use a regex if you only have the JSON string to avoid full parsing.
    
    // Fast string search (Hackier but 100x faster for CSV export of known keys)
    val actType = extractJsonValue(sample.metadataJson, "activityType")
    // ... write to CSV
}
5. Summary Recommendation
The architectural approach is solid. If you fix the JSON serialization performance and increase the update interval, this code is production-ready.

Would you like me to write the optimized StringBuilder replacement for the database serialization to ensure you don't hit memory limits?

### Claude
Tremor Detection Code Review
Overview
The tremor detection system is well-architected with clear separation of concerns. The pipeline flows from sensor events ? FFT analysis ? classification ? severity calculation ? batching.

?? BUGS & ISSUES
1. BaselineManager is always null (Critical)
File: TremorMonitoringEngine.kt:106

private val baselineManager: BaselineManager? = null

The BaselineManager is hardcoded to null and never instantiated. All the adaptive threshold logic and baseline evaluation code is dead code - it will never execute.

Fix: Inject Context into TremorMonitoringEngine or pass a BaselineManager instance via constructor.

2. FFT Resolution vs Claimed Resolution Mismatch
File: MonitoringConstants.kt:55, 64

FFT window: 64 samples
Sample rate: 50 Hz
Actual resolution: 50/64 = 0.78 Hz
But the code tries to distinguish 4-6 Hz (resting) from 5-8 Hz (essential) tremors. With 0.78 Hz resolution, frequency bins are: 0, 0.78, 1.56, 2.34, 3.12, 3.9, 4.69, 5.47, 6.25, 7.03, 7.81...

Problem: You can't reliably distinguish 5 Hz from 5.47 Hz or 6 Hz from 6.25 Hz. The granularity is too coarse for the frequency bands defined.

3. Recursive FFT is Inefficient
File: TremorFFT.kt:272-308
The Cooley-Tukey FFT uses recursion which:

Creates many temporary arrays (memory pressure on watch)
Has stack overhead
Runs at ~100ms intervals on constrained hardware
Suggestion: Use iterative FFT or a library like KissFFT.

4. Incorrect isResting Determination on First Sample
File: TremorMonitoringEngine.kt:429-431

val isResting = lastGyroFFTResult?.let { 
    it.totalPower < TremorFFT.RESTING_POWER_THRESHOLD 
} ?: true  // Default to resting state if no previous result

This uses the previous FFT result's power to determine if the current window is resting. There's a one-window lag.

5. Variance Calculation Using Wrong Formula
File: BaselineManager.kt:348-356

val delta = magnitude - oldMagnitude
val delta2 = magnitude - baseline.magnitude

Welford's algorithm should use the old mean for delta and the new mean for delta2. But here oldMagnitude is the old EMA-smoothed mean, not the true old mean of the samples. This will produce incorrect variance.
?? OPTIMIZATION OPPORTUNITIES
1. Pre-allocate FFT Arrays
File: TremorFFT.kt
Every FFT call allocates new arrays. Pre-allocate and reuse:

private val realBuffer = FloatArray(MAX_FFT_SIZE)
private val imagBuffer = FloatArray(MAX_FFT_SIZE)
private val windowBuffer = FloatArray(MAX_FFT_SIZE)

2. Cache Hanning Window Coefficients
File: TremorFFT.kt:260-266
The Hanning window is recalculated every FFT call. Pre-compute once:

private val hanningWindow = FloatArray(64) { i ->
    0.5f * (1 - cos(2 * PI.toFloat() * i / 63))
}

3. Avoid MutableList for FFT Windows
File: TremorMonitoringEngine.kt:73-75

private val gyroMagnitudeWindow = mutableListOf<Float>()

Use a fixed-size circular buffer instead:

private val gyroMagnitudeWindow = FloatArray(FFT_WINDOW_SIZE)
private var windowIndex = 0

4. Reduce Object Allocations in Hot Path
File: TremorMonitoringEngine.kt:596-618
TremorData is created every second. Consider object pooling or a mutable struct pattern.
?? IMPROVEMENT SUGGESTIONS
1. Add Zero-Crossing Rate (ZCR) as Additional Feature
Tremors have characteristic zero-crossing patterns. Add:

fun calculateZeroCrossingRate(samples: FloatArray): Float {
    var crossings = 0
    val mean = samples.average().toFloat()
    for (i in 1 until samples.size) {
        if ((samples[i-1] - mean) * (samples[i] - mean) < 0) crossings++
    }
    return crossings.toFloat() / samples.size
}

Use ZCR > 0.3 as an additional tremor indicator.

2. Add Harmonic Analysis
Parkinsonian tremor often shows harmonics at 2x and 3x the fundamental frequency. Detecting harmonics increases confidence:

fun hasHarmonics(powerSpectrum: FloatArray, fundamentalIdx: Int, freqResolution: Float): Boolean {
    val harmonic2Idx = fundamentalIdx * 2
    val harmonic3Idx = fundamentalIdx * 3
    if (harmonic2Idx >= powerSpectrum.size) return false
    
    val fundamentalPower = powerSpectrum[fundamentalIdx]
    val harmonic2Power = powerSpectrum[harmonic2Idx]
    
    // Harmonic should be 20-50% of fundamental
    return harmonic2Power > fundamentalPower * 0.2f && harmonic2Power < fundamentalPower * 0.5f
}

3. Implement Wavelet Transform for Better Time-Frequency Resolution
FFT gives good frequency resolution but poor time resolution. For transient tremor detection, consider adding Continuous Wavelet Transform (CWT) with Morlet wavelets.

4. Add Tremor Amplitude Modulation Detection
Parkinsonian tremor has characteristic amplitude modulation (~0.1-0.3 Hz). Detecting this "tremor envelope" would improve specificity.

5. Consider Machine Learning Classifier
The rule-based classifier in TremorClassifier.kt could be replaced with a lightweight ML model (TensorFlow Lite) trained on labeled tremor data for better accuracy.

6. Add Medication Timing Correlation
If the user logs medication times, correlate tremor severity with time-since-medication to provide insights.

?? CONFIGURATION RECOMMENDATIONS
Current vs Suggested Values
Parameter	Current	Suggested	Rationale
FFT_WINDOW_SIZE	64	128	Better frequency resolution (0.39 Hz vs 0.78 Hz)
FFT_PROCESSING_INTERVAL	5	3	More responsive detection
TREMOR_THRESHOLD_MULTIPLIER	2.0	1.5	Catch more subtle tremors
MIN_EPISODE_DURATION_SAMPLES	3	5	Reduce false positives
CONFIDENCE_THRESHOLD	0.35	0.30	Increase sensitivity
??? ARCHITECTURAL SUGGESTIONS
1. Inject BaselineManager Properly
class TremorMonitoringEngine(
    private val baselineManager: BaselineManager?,  // Make nullable optional
    private val onBatchReady: (List<TremorData>) -> Unit,
    private val onWearStateChanged: (Boolean) -> Unit
) : SensorEventListener

2. Extract FFT into Separate Thread
FFT is CPU-intensive. Run it on a dedicated thread to avoid blocking sensor callbacks:

private val fftExecutor = Executors.newSingleThreadExecutor()

// In sensor callback:
fftExecutor.submit {
    lastGyroFFTResult = tremorFFT.analyze(...)
}

3. Add Diagnostic Logging Mode
For debugging false positives/negatives, add a verbose logging mode that captures raw FFT data for offline analysis.

Summary
Critical Fixes Needed:

Wire up BaselineManager (it's null and all adaptive code is dead)
Consider increasing FFT window size for better frequency resolution
Quick Wins:

Pre-allocate FFT arrays
Cache Hanning window
Use circular buffer instead of MutableList
Future Improvements:

Add zero-crossing rate
Add harmonic detection
Consider ML-based classification
Would you like me to implement any of these fixes or improvements?

### ChatGPT
Dave, this is a solid piece of work. You’ve clearly thought about **where** activity context should sit in the pipeline, and you’ve mostly put it in the right places. I’ll go through this the way I would in a real code review, straight up, no fluff.

I’ll break it down by the exact areas you asked for.

---

## High-level verdict (before details)

Conceptually sound, mostly well-implemented, but there are a few correctness bugs, some Wear-specific battery risks, and one big architectural concern around Activity Recognition on watches.

Nothing here is a show-stopper, but **as written, this will cost more battery than you think, and activity confidence may be over-trusted**.

---

## 1. Bugs, edge cases, correctness

### 1.1 ActivityRecognition on Wear OS is not guaranteed

This is the biggest risk.

On Wear OS:

* **ActivityRecognition is not always backed by the same ML models as phones**
* Some devices (especially older Samsung watches) return **mostly TILTING / UNKNOWN**
* Some watches silently throttle updates when the screen is off, even with a foreground service

You already handle `UNKNOWN`, but the bug is conceptual:
your filtering assumes *absence of updates == stale == safe fallback*. On Wear, absence of updates can be **normal behaviour**, not failure.

**Impact:**
You’ll see long stretches of `UNKNOWN ? multiplier 0.7`, which biases results downward even at rest.

**Fix recommendation:**

* Treat `UNKNOWN` + worn + low accel variance as “likely still”
* Or gate activity filtering behind `activityConfidence >= mediumThreshold`, not just stale

Right now:

```kotlin
if (!config.activityFilteringEnabled || isStale) {
    return base
}
```

Better:

```kotlin
if (!config.activityFilteringEnabled ||
    isStale ||
    activityConfidence < config.activityLowConfidenceThreshold
) {
    return base
}
```
---

### 1.2 `isReliableMeasurement` logic is too strict

```kotlin
val isReliable = !isStale &&
    activityType == DetectedActivity.STILL &&
    activityConfidence >= config.activityHighConfidenceThreshold
```

That means:

* Sitting at a desk, hands resting, but classified as `ON_FOOT` ? unreliable
* Standing still ? unreliable
* Mild postural tremor while holding phone ? unreliable

This is **too binary** for Parkinson’s data.

Suggestion:

* Make `isReliable` a spectrum or at least include `TILTING` and `ON_FOOT` when accel variance is low
* Or rename it to `isHighConfidenceRestState` so it doesn’t get misused later

Right now the name overpromises.

---

### 1.3 `excludeFromAnalysis` may hide valuable data

```kotlin
activityConfidence >= high &&
(activityType == RUNNING || BICYCLE || IN_VEHICLE)
```

That’s fine for *severity trends*, but:

* Tremor during walking is clinically meaningful
* Dyskinesia can happen while moving

If this flag is ever used downstream to *drop samples*, you’ll lose signal.

Strong recommendation:

* Rename to `excludeFromRestingAnalysis`
* Never hard-exclude from raw storage or exports (you don’t, which is good)

---

### 1.4 Potential timestamp skew bug

```kotlin
val ageMs = nowMs - state.updatedAtMs
```

If system time changes (NTP, reboot, timezone sync), this can go negative.

Rare, but it happens.

Safer:

* Use `elapsedRealtime()` for activity timestamps
* Or clamp `ageMs = max(0, …)`
---

## 2. Android / Wear best practices

### 2.1 Activity Recognition update interval is aggressive

```kotlin
ACTIVITY_UPDATE_INTERVAL_MS = 5000L
```

On a watch, **5 seconds is expensive**.

Reality:

* Activity doesn’t change that often
* Google’s own docs recommend **30s–2min** for wearables unless doing fitness tracking

Better approach:

* 30s when screen off
* 5–10s only during active tremor episodes
* Or dynamically scale based on accel variance

At minimum:

```kotlin
const val ACTIVITY_UPDATE_INTERVAL_MS = 30_000L
```

Your tremor engine already runs at high frequency, activity does not need to.

---

### 2.2 Foreground service + ActivityRecognition is OK, but fragile

You’re doing the right thing by:

* Using a foreground service
* Using a `PendingIntent`
* Avoiding a `BroadcastReceiver`

However:

* Some OEMs require `FOREGROUND_SERVICE_LOCATION` when using AR indirectly
* Samsung is particularly weird here

Recommendation:

* Add a runtime log on first successful update
* If no updates in N minutes, auto-disable activity filtering and log it

Fail noisy, not silently.

---

### 2.3 Permission handling is correct, but UX could improve

You request `ACTIVITY_RECOGNITION` alongside `BODY_SENSORS`.

Good.

But:

* If the user denies AR, filtering silently disables
* No feedback anywhere

Even just a debug notification like:

> “Activity filtering disabled – permission denied”

would save future support pain.
---

## 3. Performance and battery

### 3.1 Threading is fine, volatility is good

This is well done:

```kotlin
@Volatile
private var activityState = ActivityState(...)
```

You’re only reading/writing a single object reference, so this is safe and cheap.

No synchronisation needed.

---

### 3.2 JSON metadata storage is heavier than you think

On the phone side:

```kotlin
JSONObject(sample.metadata).toString()
```

For large batches, this adds:

* CPU
* Memory churn
* GC pressure

Given you already extract key fields:

* Either whitelist only activity fields into JSON
* Or move to structured columns later

For now it’s acceptable, but don’t pretend it’s free.

---

### 3.3 CSV export JSON parsing in a loop

This bit:

```kotlin
runCatching { JSONObject(it) }
```

inside a loop over samples can get slow for big exports.

If users export weeks of data, this will hurt.

Suggestion:

* Parse once per sample is fine
* But don’t do this on main (looks like you’re not, good)

---

## 4. Code quality and maintainability

### What’s good

* Clear naming
* Conservative defaults
* Validation in config init
* No magic numbers buried in logic
* Raw data preserved (huge win)

This is clean, readable code.

---

### What I’d tighten

#### 4.1 `ActivityAdjustment` is doing too much

It mixes:

* Context
* Decisions
* Flags
* Adjusted metrics

Long-term, this will sprawl.

Consider:

* `ActivityContext`
* `ActivityFilterResult`

But not urgent.

---

#### 4.2 `DetectedActivity` mapping should be centralised

You map activity types in:

* `getActivityName`
* `adjustForActivity`
* Reliability logic

This will drift.

Suggestion:

* Single `ActivityProfile` data class
* Map DetectedActivity ? profile once

---
## 5. Integration with tremor detection

This part is actually very well thought out.

You **did the right thing** by:

* Not changing `finalIsTremor`
* Adjusting *confidence* and *severity only*
* Propagating raw + adjusted values
* Preserving transparency

Clinically and analytically, this is exactly what you want.

The engine remains explainable.

---

## 6. Data accuracy and clinical realism

### The good

* Activity-aware severity dampening is valid
* Confidence-weighted multipliers are smart
* Stale handling prevents garbage-in

### The concern

Activity Recognition ? hand motion context.

Examples:

* Walking + resting tremor ? real tremor
* Sitting + fidgeting ? false tremor
* Standing still + postural tremor ? real signal, but classified as ON_FOOT

Your system improves **false positives**, but risks **false negatives** unless tuned carefully.

Strong recommendation:

* Log accel variance + activity side by side
* Consider a hybrid rule:

  * If accel variance is low, down-weight activity penalty
  * If variance is high, trust activity more

You already have the data to do this.

---

## Bottom line

**This is good work.**
It’s production-grade with a few caveats.

If you do nothing else before shipping:

1. Increase activity update interval
2. Don’t over-trust `UNKNOWN`
3. Relax `isReliableMeasurement`
4. Add a safety fallback when AR is flaky

If you want, next step I can:

* Propose a Wear-optimised activity strategy
* Help tune default multipliers for Parkinson’s specifically
* Review how this feeds Grafana so clinicians don’t misinterpret filtered data

Tell me where you want to take it next.
