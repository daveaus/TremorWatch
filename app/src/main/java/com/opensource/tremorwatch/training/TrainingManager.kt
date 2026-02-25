package com.opensource.tremorwatch.training

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.opensource.tremorwatch.TremorFFT
import com.opensource.tremorwatch.WatchDataSender
import com.opensource.tremorwatch.shared.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates the Active Learning training process on the watch side.
 *
 * Responsibilities:
 * - Manages training state machine (OFF → WARMUP → ACTIVE → PERSONALIZED)
 * - Runs shadow detection via ShadowDetector
 * - Enforces prompt guardrails (cooldown, quiet hours, daily cap)
 * - Handles user feedback and sends labeled samples to phone
 * - Manages local backup queue with retry-on-reconnect [P1]
 *
 * Thread safety:
 * - pendingFeedback uses ConcurrentHashMap [P3]
 * - experimentalConfig uses @Volatile [P13]
 * - requestUserFeedback is @Synchronized [P14]
 */
class TrainingManager(
    private val context: Context,
    private val dataSender: WatchDataSender,
    private val sampleRate: Float
) {
    companion object {
        private const val TRAINING_PROMPT_CHANNEL_ID = "training_prompt_channel"
        private const val TRAINING_PROMPT_CHANNEL_NAME = "Training Prompts"
        const val PROMPT_COOLDOWN_MS = 10 * 60 * 1000L     // 10 minutes
        const val MAX_PROMPTS_PER_HOUR = 4
        const val MAX_PROMPTS_PER_DAY = 20
        const val PROMPT_TIMEOUT_MS = 30_000L               // 30 seconds
        const val QUIET_HOUR_START = 22                      // 10 PM
        const val QUIET_HOUR_END = 7                         // 7 AM
        const val MIN_LABELS_FOR_ACTIVE = 10                 // 5Y + 5N minimum
        const val TRAINING_DURATION_DAYS = 7
    }

    private val shadowDetector = ShadowDetector(sampleRate)

    // [P13] @Volatile — experimentalConfig is written by config update (any thread)
    // and read by runShadowDetection (sensor thread). Reference assignment is atomic
    // on JVM, but @Volatile ensures visibility across threads.
    @Volatile
    private var experimentalConfig: TremorDetectionConfig? = null

    var state: TrainingState = TrainingState.OFF
        private set

    val isTrainingActive: Boolean
        get() = state == TrainingState.WARMUP || state == TrainingState.ACTIVE

    private var lastPromptTime = 0L
    private val promptsThisHour = AtomicInteger(0)
    private val promptsToday = AtomicInteger(0)
    private var currentHour = -1
    private var currentDay = -1

    // [P3] ConcurrentHashMap — accessed from sensor thread (requestUserFeedback),
    // UI thread (onUserFeedback), and settings thread (stopTraining).
    private val pendingFeedback = java.util.concurrent.ConcurrentHashMap<String, TrainingSample>()

    // ──── State Management ────

    fun startTraining(baseConfig: TremorDetectionConfig) {
        state = TrainingState.WARMUP
        experimentalConfig = shadowDetector.createExperimentalConfig(baseConfig)
        promptsToday.set(0)
        promptsThisHour.set(0)

        // [P1] Flush any labels that were orphaned from a previous session
        flushPendingBackups()

        Timber.i("Training started in WARMUP state")
    }

    fun stopTraining() {
        state = TrainingState.OFF
        experimentalConfig = null
        pendingFeedback.clear()
        Timber.i("Training stopped")
    }

    fun updateConfig(newBaseConfig: TremorDetectionConfig) {
        experimentalConfig = shadowDetector.createExperimentalConfig(newBaseConfig)
    }

    // ──── Shadow Detection Bridge ────

    fun runShadowDetection(
        gyroSamples: FloatArray,
        accelSamples: FloatArray?,
        isResting: Boolean,
        productionResult: TremorFFT.FFTResult,
        currentConfig: TremorDetectionConfig,
        analysisOptions: TremorFFT.AnalysisOptions,
        crossSensorSupport: Float = 0f,
        frequencyStability: Float = 0f,
        magnitude: Float = 0f,
        accelMagnitude: Float = 0f,
        activityType: String = "unknown",
        activityConfidence: Float = 0f
    ): ShadowDetector.ShadowResult {
        val expConfig = experimentalConfig
            ?: shadowDetector.createExperimentalConfig(currentConfig).also {
                experimentalConfig = it
            }

        return shadowDetector.analyze(
            gyroSamples, accelSamples, isResting,
            productionResult, expConfig, analysisOptions,
            crossSensorSupport, frequencyStability,
            magnitude, accelMagnitude, activityType, activityConfidence
        )
    }

    /**
     * Bridge method for the engine's onTrainingSample callback.
     *
     * This is the entry point called from TremorService's lambda.
     * Uses already-computed features from [TremorData] + [FFTResult] to
     * detect borderline cases (production said no, but relaxed thresholds
     * would say maybe) and trigger user feedback.
     *
     * We avoid re-running FFT because the raw gyro buffer isn't
     * available through the callback — only the processed result is.
     */
    fun onEngineSample(
        tremorData: com.opensource.tremorwatch.engine.TremorMonitoringEngine.TremorData,
        fftResult: TremorFFT.FFTResult?
    ) {
        if (!isTrainingActive) return
        val result = fftResult ?: return

        // Borderline detection: production said NO, but the signal is close to the threshold.
        // We relax confidence by 30% and check if that would flip the decision.
        val productionIsTremor = result.isTremor
        val relaxedConfidenceThreshold = 0.35f * 0.70f   // 30% lower than default
        val relaxedBandRatio = 0.04f * 0.70f
        val nearBoundary = !productionIsTremor &&
            result.confidence >= relaxedConfidenceThreshold &&
            result.bandRatio >= relaxedBandRatio &&
            result.dominantFrequency >= 4.0f

        if (!nearBoundary && !productionIsTremor) return

        // Build a shadow result from the already-computed features
        val features = FeedbackFeatureSnapshot(
            dominantFrequency = result.dominantFrequency,
            bandRatio = result.bandRatio,
            totalPower = result.totalPower,
            tremorBandPower = result.tremorBandPower,
            spectralEntropy = result.spectralEntropy,
            harmonicRatio = result.harmonicRatio,
            peakProminence = result.peakProminence,
            confidence = result.confidence,
            isResting = tremorData.isRestingState,
            crossSensorSupport = 0f,
            frequencyStability = 0f,
            magnitude = tremorData.magnitude,
            accelMagnitude = tremorData.accelMagnitude,
            activityType = tremorData.activityType,
            activityConfidence = tremorData.activityConfidence
        )

        val shadowResult = ShadowDetector.ShadowResult(
            productionIsTremor = productionIsTremor,
            experimentalIsTremor = nearBoundary || productionIsTremor,
            shouldPromptUser = true,
            triggerReason = if (nearBoundary) "near_boundary" else "production_positive",
            features = features
        )

        requestUserFeedback(shadowResult, result, tremorData.timestamp)
    }

    // ──── Prompt Guardrails ────

    // [P14] @Synchronized — The check-and-increment pattern across canPrompt() +
    // incrementPromptCounters() must be atomic. Without this, two rapid shadow
    // detections could both pass canPrompt() before either increments.
    @Synchronized
    fun requestUserFeedback(
        shadowResult: ShadowDetector.ShadowResult,
        productionResult: TremorFFT.FFTResult,
        timestamp: Long
    ) {
        // [P4] Evict stale pending entries before checking capacity
        evictStalePending()

        val promptDecision = evaluatePromptEligibility(timestamp)
        if (!promptDecision.allowed) {
            Timber.d("Prompt suppressed by guardrails: ${promptDecision.reason}")
            return
        }

        val sample = TrainingSample(
            timestamp = timestamp,
            dominantFrequency = shadowResult.features.dominantFrequency,
            bandRatio = shadowResult.features.bandRatio,
            totalPower = shadowResult.features.totalPower,
            tremorBandPower = shadowResult.features.tremorBandPower,
            spectralEntropy = shadowResult.features.spectralEntropy,
            harmonicRatio = shadowResult.features.harmonicRatio,
            peakProminence = shadowResult.features.peakProminence,
            confidence = shadowResult.features.confidence,
            isResting = shadowResult.features.isResting,
            crossSensorSupport = shadowResult.features.crossSensorSupport,
            frequencyStability = shadowResult.features.frequencyStability,
            magnitude = shadowResult.features.magnitude,
            accelMagnitude = shadowResult.features.accelMagnitude,
            activityType = shadowResult.features.activityType,
            activityConfidence = shadowResult.features.activityConfidence,
            productionIsTremor = shadowResult.productionIsTremor,
            shadowIsTremor = shadowResult.experimentalIsTremor,
            triggerReason = shadowResult.triggerReason
        )

        pendingFeedback[sample.sampleId] = sample
        lastPromptTime = timestamp
        incrementPromptCounters()

        // Launch the prompt activity
        launchPromptActivity(sample.sampleId, sample.timestamp)
    }

    private data class PromptDecision(
        val allowed: Boolean,
        val reason: String
    )

    private fun evaluatePromptEligibility(now: Long): PromptDecision {
        // Cooldown check
        val elapsedSinceLastPrompt = now - lastPromptTime
        if (elapsedSinceLastPrompt < PROMPT_COOLDOWN_MS) {
            val remainingMs = PROMPT_COOLDOWN_MS - elapsedSinceLastPrompt
            return PromptDecision(
                allowed = false,
                reason = "cooldown_active remaining_ms=$remainingMs"
            )
        }

        // Quiet hours check
        val hour = LocalTime.now().hour
        if (hour >= QUIET_HOUR_START || hour < QUIET_HOUR_END) {
            return PromptDecision(
                allowed = false,
                reason = "quiet_hours hour=$hour window=$QUIET_HOUR_START-$QUIET_HOUR_END"
            )
        }

        // Rate limiting
        resetCountersIfNeeded()
        val currentHourPrompts = promptsThisHour.get()
        if (currentHourPrompts >= MAX_PROMPTS_PER_HOUR) {
            return PromptDecision(
                allowed = false,
                reason = "hourly_cap reached=$currentHourPrompts max=$MAX_PROMPTS_PER_HOUR"
            )
        }
        val currentDayPrompts = promptsToday.get()
        if (currentDayPrompts >= MAX_PROMPTS_PER_DAY) {
            return PromptDecision(
                allowed = false,
                reason = "daily_cap reached=$currentDayPrompts max=$MAX_PROMPTS_PER_DAY"
            )
        }

        return PromptDecision(allowed = true, reason = "allowed")
    }

    private fun resetCountersIfNeeded() {
        val now = LocalTime.now()
        if (now.hour != currentHour) {
            currentHour = now.hour
            promptsThisHour.set(0)
        }
        val today = java.time.LocalDate.now().dayOfYear
        if (today != currentDay) {
            currentDay = today
            promptsToday.set(0)
        }
    }

    private fun incrementPromptCounters() {
        promptsThisHour.incrementAndGet()
        promptsToday.incrementAndGet()
    }

    private fun launchPromptActivity(sampleId: String, eventTimestampMs: Long) {
        try {
            val eventTimeText = formatMovementTime(eventTimestampMs)
            val intent = Intent(context, TrainingPromptActivity::class.java).apply {
                putExtra(TrainingPromptActivity.EXTRA_SAMPLE_ID, sampleId)
                putExtra(TrainingPromptActivity.EXTRA_EVENT_TIMESTAMP_MS, eventTimestampMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            val notificationId = notificationIdForSample(sampleId)
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensurePromptChannel(notificationManager)

            val notification = NotificationCompat.Builder(context, TRAINING_PROMPT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Tremor Check")
                .setContentText("Detected movement at $eventTimeText. Tap to answer.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOngoing(true)
                .setTimeoutAfter(PROMPT_TIMEOUT_MS + 5_000L)
                .build()

            notificationManager.notify(notificationId, notification)
            Timber.i("Training prompt notification posted for sample $sampleId at $eventTimeText")
        } catch (e: Exception) {
            Timber.e(e, "Failed to post training prompt notification")
        }
    }

    private fun formatMovementTime(timestampMs: Long): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))
    }

    private fun ensurePromptChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(TRAINING_PROMPT_CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            TRAINING_PROMPT_CHANNEL_ID,
            TRAINING_PROMPT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Active learning prompts for tremor confirmation"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun notificationIdForSample(sampleId: String): Int {
        val stableHash = sampleId.hashCode() and 0x7fffffff
        return 0x20000000 or (stableHash and 0x0fffffff)
    }

    private fun cancelPromptNotification(sampleId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationIdForSample(sampleId))
    }

    // ──── Feedback Handling ────

    fun onUserFeedback(sampleId: String, label: FeedbackLabel) {
        cancelPromptNotification(sampleId)

        val sample = pendingFeedback.remove(sampleId) ?: run {
            Timber.w("Sample $sampleId not found in pending feedback")
            return
        }

        val now = System.currentTimeMillis()
        val labeled = sample.copy(
            feedback = label,
            feedbackTimestamp = now,
            responseLatencyMs = now - sample.timestamp
        )

        // 1. Save locally as backup (always persisted before send attempt)
        saveLocalBackup(labeled)

        // 2. [P1] Send to phone — delete backup only on confirmed delivery
        dataSender.sendTrainingSample(labeled) {
            clearLocalBackup(labeled.sampleId)
        }

        Timber.i("Feedback recorded: $label for sample $sampleId " +
                 "(latency=${labeled.responseLatencyMs}ms)")
    }

    fun onPromptTimeout(sampleId: String) {
        onUserFeedback(sampleId, FeedbackLabel.IGNORE)
    }

    // ──── Local Backup & Retry Queue [P1] ────

    private fun saveLocalBackup(sample: TrainingSample) {
        try {
            val dir = File(context.filesDir, "training_labels")
            dir.mkdirs()
            val file = File(dir, "${sample.sampleId}.json")
            file.writeText(Json.encodeToString(sample))
        } catch (e: Exception) {
            Timber.e(e, "Failed to save local training label backup")
        }
    }

    /**
     * Delete a local backup after the phone has confirmed receipt.
     */
    private fun clearLocalBackup(sampleId: String) {
        try {
            File(context.filesDir, "training_labels/$sampleId.json").delete()
        } catch (e: Exception) {
            Timber.w(e, "Failed to clear local backup for $sampleId")
        }
    }

    /**
     * [P1] Scan local backup directory and re-send any samples that never
     * reached the phone. Call this:
     *   (1) on startTraining() — flush leftovers from previous sessions
     *   (2) on NodeClient connectivity change events
     */
    fun flushPendingBackups() {
        val dir = File(context.filesDir, "training_labels")
        if (!dir.exists()) return
        val backups = dir.listFiles { f -> f.extension == "json" } ?: return
        if (backups.isEmpty()) return

        Timber.i("Flushing ${backups.size} pending training label backups")
        for (file in backups) {
            try {
                val sample = Json.decodeFromString<TrainingSample>(file.readText())
                dataSender.sendTrainingSample(sample) {
                    file.delete()
                    Timber.d("Flushed backup: ${sample.sampleId}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Corrupt or unreadable backup: ${file.name}")
                file.delete()  // Remove corrupt files to prevent infinite retry
            }
        }
    }

    /**
     * [P4] Evict pending feedback entries older than PROMPT_TIMEOUT + 5s.
     * Treats stale entries as IGNORE so they still get sent to the phone.
     * Prevents unbounded memory growth if activities are killed without response.
     */
    private fun evictStalePending() {
        val cutoff = System.currentTimeMillis() - (PROMPT_TIMEOUT_MS + 5000L)
        val stale = pendingFeedback.filter { it.value.timestamp < cutoff }
        for ((id, _) in stale) {
            Timber.d("Evicting stale pending feedback: $id")
            onUserFeedback(id, FeedbackLabel.IGNORE)
        }
    }
}
