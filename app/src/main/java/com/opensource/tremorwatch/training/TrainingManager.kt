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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class TrainingLogEntry(
    val timestampMs: Long,
    val type: String,
    val label: FeedbackLabel? = null,
    val detail: String
)

data class TrainingStatusSnapshot(
    val modeEnabled: Boolean,
    val engineState: TrainingState,
    val uiState: TrainingState,
    val usableLabelCount: Int,
    val yesLabelCount: Int,
    val noLabelCount: Int,
    val ignoredLabelCount: Int,
    val targetUsableLabelCount: Int,
    val hasEnoughLabels: Boolean,
    val promptsTotal: Int,
    val promptsToday: Int,
    val pendingPromptCount: Int,
    val trainingStartTimeMs: Long?,
    val trainingCompletedTimeMs: Long?,
    val trainingDaysElapsed: Int,
    val targetTrainingDays: Int,
    val lastPromptTimeMs: Long?,
    val lastFeedbackTimeMs: Long?,
    val lastFeedbackLabel: FeedbackLabel?
)

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
        private const val TRAINING_INSIGHT_PREFS = "training_insight"
        private const val KEY_YES_COUNT = "yes_count"
        private const val KEY_NO_COUNT = "no_count"
        private const val KEY_IGNORE_COUNT = "ignore_count"
        private const val KEY_TOTAL_PROMPTS = "total_prompts"
        private const val KEY_PROMPTS_TODAY = "prompts_today"
        private const val KEY_PENDING_PROMPTS = "pending_prompts"
        private const val KEY_LAST_PROMPT_MS = "last_prompt_ms"
        private const val KEY_LAST_FEEDBACK_MS = "last_feedback_ms"
        private const val KEY_LAST_FEEDBACK_LABEL = "last_feedback_label"
        private const val KEY_TRAINING_START_MS = "training_start_ms"
        private const val KEY_TRAINING_COMPLETED_MS = "training_completed_ms"
        private const val KEY_ENGINE_STATE = "engine_state"
        private const val KEY_LOG_JSON = "log_json"
        private const val MAX_LOG_ENTRIES = 32
        private const val STATUS_SYNC_MIN_INTERVAL_MS = 5_000L

        const val PROMPT_COOLDOWN_MS = 10 * 60 * 1000L     // 10 minutes
        const val MAX_PROMPTS_PER_HOUR = 4
        const val MAX_PROMPTS_PER_DAY = 20
        const val PROMPT_TIMEOUT_MS = 30_000L               // 30 seconds
        const val QUIET_HOUR_START = 22                      // 10 PM
        const val QUIET_HOUR_END = 7                         // 7 AM
        const val MIN_LABELS_FOR_ACTIVE = TrainingThresholds.MIN_USABLE_LABELS_FOR_PERSONALIZATION
        const val TRAINING_DURATION_DAYS = 7
        // [F5] Every Nth clearly-non-tremor sample is eligible for a baseline prompt.
        // Gives the optimizer genuine true-negatives from quiet periods, not just
        // ambiguous boundary-zone negatives. Still subject to all prompt guardrails.
        private const val BASELINE_SAMPLE_RATE = 50

        private val statusJson = Json { ignoreUnknownKeys = true }

        fun getPersistedStatusSnapshot(context: Context): TrainingStatusSnapshot {
            val prefs = context.getSharedPreferences(TRAINING_INSIGHT_PREFS, Context.MODE_PRIVATE)
            val yes = prefs.getInt(KEY_YES_COUNT, 0)
            val no = prefs.getInt(KEY_NO_COUNT, 0)
            val ignored = prefs.getInt(KEY_IGNORE_COUNT, 0)
            val usable = yes + no
            val target = MIN_LABELS_FOR_ACTIVE
            val enough = usable >= target
            val promptsTotal = prefs.getInt(KEY_TOTAL_PROMPTS, 0)
            val startMsRaw = prefs.getLong(KEY_TRAINING_START_MS, 0L)
            val startMs = if (startMsRaw > 0L) startMsRaw else null
            val completedMsRaw = prefs.getLong(KEY_TRAINING_COMPLETED_MS, 0L)
            val completedMs = if (completedMsRaw > 0L) completedMsRaw else null
            val now = System.currentTimeMillis()
            val daysElapsed = startMs?.let { ((now - it) / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(0) } ?: 0

            val engineState = parseTrainingState(
                prefs.getString(KEY_ENGINE_STATE, TrainingState.OFF.name),
                TrainingState.OFF
            )

            val uiState = when {
                engineState == TrainingState.OFF -> TrainingState.OFF
                enough -> TrainingState.PERSONALIZED
                promptsTotal == 0 -> TrainingState.WARMUP
                else -> TrainingState.ACTIVE
            }

            val lastPromptRaw = prefs.getLong(KEY_LAST_PROMPT_MS, 0L)
            val lastFeedbackRaw = prefs.getLong(KEY_LAST_FEEDBACK_MS, 0L)
            val lastFeedbackLabel = parseFeedbackLabel(prefs.getString(KEY_LAST_FEEDBACK_LABEL, null))

            return TrainingStatusSnapshot(
                modeEnabled = engineState != TrainingState.OFF,
                engineState = engineState,
                uiState = uiState,
                usableLabelCount = usable,
                yesLabelCount = yes,
                noLabelCount = no,
                ignoredLabelCount = ignored,
                targetUsableLabelCount = target,
                hasEnoughLabels = enough,
                promptsTotal = promptsTotal,
                promptsToday = prefs.getInt(KEY_PROMPTS_TODAY, 0),
                pendingPromptCount = prefs.getInt(KEY_PENDING_PROMPTS, 0),
                trainingStartTimeMs = startMs,
                trainingCompletedTimeMs = completedMs,
                trainingDaysElapsed = daysElapsed,
                targetTrainingDays = TRAINING_DURATION_DAYS,
                lastPromptTimeMs = if (lastPromptRaw > 0L) lastPromptRaw else null,
                lastFeedbackTimeMs = if (lastFeedbackRaw > 0L) lastFeedbackRaw else null,
                lastFeedbackLabel = lastFeedbackLabel
            )
        }

        fun getPersistedRecentLog(context: Context, limit: Int = 8): List<TrainingLogEntry> {
            val prefs = context.getSharedPreferences(TRAINING_INSIGHT_PREFS, Context.MODE_PRIVATE)
            val logJson = prefs.getString(KEY_LOG_JSON, null) ?: return emptyList()
            return try {
                statusJson.decodeFromString<List<TrainingLogEntry>>(logJson)
                    .takeLast(limit.coerceAtLeast(0))
                    .asReversed()
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse persisted training log")
                emptyList()
            }
        }

        private fun parseTrainingState(raw: String?, fallback: TrainingState): TrainingState {
            return try {
                if (raw.isNullOrBlank()) fallback else TrainingState.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                fallback
            }
        }

        private fun parseFeedbackLabel(raw: String?): FeedbackLabel? {
            return try {
                if (raw.isNullOrBlank()) null else FeedbackLabel.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
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
    private val totalPromptCount = AtomicInteger(0)
    private val yesLabelCount = AtomicInteger(0)
    private val noLabelCount = AtomicInteger(0)
    private val ignoredLabelCount = AtomicInteger(0)
    // [F5] Counts every onEngineSample() call (not just prompted ones) to pace
    // sparse baseline negative collection independently of the prompt counters.
    private val baselineSampleCounter = AtomicInteger(0)
    private var currentHour = -1
    private var currentDay = -1
    private var trainingStartTimeMs: Long? = null
    private var trainingCompletedTimeMs: Long? = null
    private var lastFeedbackTimeMs: Long? = null
    private var lastFeedbackLabel: FeedbackLabel? = null
    private var lastStatusSyncFingerprint: String? = null
    private var lastStatusSyncTimeMs: Long = 0L

    // [P3] ConcurrentHashMap — accessed from sensor thread (requestUserFeedback),
    // UI thread (onUserFeedback), and settings thread (stopTraining).
    private val pendingFeedback = java.util.concurrent.ConcurrentHashMap<String, TrainingSample>()

    private val statusPrefs = context.getSharedPreferences(TRAINING_INSIGHT_PREFS, Context.MODE_PRIVATE)
    private val trainingLog = ArrayDeque<TrainingLogEntry>()
    private val statusState = MutableStateFlow(getPersistedStatusSnapshot(context))

    init {
        hydratePersistedInsight()
        refreshStatusSnapshot()
    }

    fun statusUpdates(): StateFlow<TrainingStatusSnapshot> = statusState.asStateFlow()

    fun getStatusSnapshot(): TrainingStatusSnapshot = statusState.value

    fun getRecentLogEntries(limit: Int = 8): List<TrainingLogEntry> {
        val bounded = limit.coerceAtLeast(0)
        return synchronized(trainingLog) {
            trainingLog.takeLast(bounded).asReversed()
        }
    }

    // ──── State Management ────

    fun startTraining(baseConfig: TremorDetectionConfig) {
        state = TrainingState.WARMUP
        experimentalConfig = shadowDetector.createExperimentalConfig(baseConfig)
        promptsToday.set(0)
        promptsThisHour.set(0)
        if (trainingStartTimeMs == null) {
            trainingStartTimeMs = System.currentTimeMillis()
        }

        // [P1] Flush any labels that were orphaned from a previous session
        flushPendingBackups()

        appendLog(type = "TRAINING", detail = "Training mode enabled")
        refreshStatusSnapshot()
        Timber.i("Training started in WARMUP state")
    }

    fun stopTraining() {
        state = TrainingState.OFF
        experimentalConfig = null
        pendingFeedback.clear()
        appendLog(type = "TRAINING", detail = "Training mode disabled")
        refreshStatusSnapshot()
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
        // [H5] Derive relaxed thresholds from the current experimental config so the
        // near-boundary probe zone tracks the optimizer's tuned values. Hardcoding to
        // the default (0.35 / 0.04) meant the probe fired in the wrong zone after
        // personalization and collected off-target labels.
        val activeConfig = experimentalConfig
        val baseConfidence = activeConfig?.confidenceThreshold ?: 0.35f
        val baseBandRatio = if (tremorData.isRestingState)
            activeConfig?.restingMinBandRatio ?: 0.04f
        else
            activeConfig?.activeMinBandRatio ?: 0.04f
        val relaxedConfidenceThreshold = baseConfidence * 0.70f
        val relaxedBandRatio = baseBandRatio * 0.70f
        // [F2] Track minFrequencyHz from the active config so the probe zone stays aligned
        // with whatever the optimizer has tuned. Previously hardcoded to 4.0f, meaning
        // post-personalization probes could fire below the production detection floor.
        val relaxedFrequencyHz = activeConfig?.minFrequencyHz ?: 4.0f
        val nearBoundary = !productionIsTremor &&
            result.confidence >= relaxedConfidenceThreshold &&
            result.bandRatio >= relaxedBandRatio &&
            result.dominantFrequency >= relaxedFrequencyHz

        if (!nearBoundary && !productionIsTremor) {
            // [F5] Sparse baseline negative sampling: every BASELINE_SAMPLE_RATE calls,
            // if the signal is clearly non-tremor (confidence < 25% of threshold), allow
            // the sample through as a genuine true-negative for the optimizer.
            // All normal prompt guardrails (cooldown, rate caps, quiet hours) still apply —
            // this only widens the eligibility gate, it does not bypass any safety limits.
            val callN = baselineSampleCounter.incrementAndGet()
            val isClearlyNonTremor = result.confidence < baseConfidence * 0.25f
            val isDue = callN % BASELINE_SAMPLE_RATE == 0
            if (!isClearlyNonTremor || !isDue) return
            // Fall through: baseline sample eligible — guard rails in requestUserFeedback()
            // will still gate on cooldown, daily cap, quiet hours, etc.
        }

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
            activityConfidence = tremorData.activityConfidence,
            bandPower2to4Hz = result.bandPower2to4Hz,
            bandPower4to6Hz = result.bandPower4to6Hz,
            bandPower6to8Hz = result.bandPower6to8Hz,
            bandPower8to10Hz = result.bandPower8to10Hz,
            bandPower10to12Hz = result.bandPower10to12Hz,
            bandPower12to14Hz = result.bandPower12to14Hz
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
            bandPower2to4Hz = shadowResult.features.bandPower2to4Hz,
            bandPower4to6Hz = shadowResult.features.bandPower4to6Hz,
            bandPower6to8Hz = shadowResult.features.bandPower6to8Hz,
            bandPower8to10Hz = shadowResult.features.bandPower8to10Hz,
            bandPower10to12Hz = shadowResult.features.bandPower10to12Hz,
            bandPower12to14Hz = shadowResult.features.bandPower12to14Hz,
            productionIsTremor = shadowResult.productionIsTremor,
            shadowIsTremor = shadowResult.experimentalIsTremor,
            triggerReason = shadowResult.triggerReason
        )

        pendingFeedback[sample.sampleId] = sample
        lastPromptTime = timestamp
        incrementPromptCounters()
        if (state == TrainingState.WARMUP) {
            state = TrainingState.ACTIVE
        }
        appendLog(
            type = "PROMPT",
            detail = "Prompted: ${sample.triggerReason}",
            timestampMs = timestamp
        )
        refreshStatusSnapshot()

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
        var changed = false
        if (now.hour != currentHour) {
            currentHour = now.hour
            promptsThisHour.set(0)
            changed = true
        }
        val today = LocalDate.now().dayOfYear
        if (today != currentDay) {
            currentDay = today
            promptsToday.set(0)
            changed = true
        }
        if (changed) {
            refreshStatusSnapshot()
        }
    }

    private fun incrementPromptCounters() {
        promptsThisHour.incrementAndGet()
        promptsToday.incrementAndGet()
        totalPromptCount.incrementAndGet()
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

    // [M2] @Synchronized ensures counter increments, SharedPreferences batch writes,
    // and the pendingFeedback.remove() are atomic with respect to requestUserFeedback()
    // on the sensor thread, preventing stale values in any concurrent apply() batch.
    @Synchronized
    fun onUserFeedback(sampleId: String, label: FeedbackLabel) {
        cancelPromptNotification(sampleId)

        val sample = pendingFeedback.remove(sampleId) ?: run {
            Timber.w("Sample $sampleId not found in pending feedback")
            refreshStatusSnapshot()
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

        when (label) {
            FeedbackLabel.YES_TREMOR -> yesLabelCount.incrementAndGet()
            FeedbackLabel.NO_ACTIVE -> noLabelCount.incrementAndGet()
            FeedbackLabel.IGNORE -> ignoredLabelCount.incrementAndGet()
        }
        lastFeedbackTimeMs = now
        lastFeedbackLabel = label
        appendLog(
            type = "LABEL",
            label = label,
            detail = when (label) {
                FeedbackLabel.YES_TREMOR -> "User marked tremor"
                FeedbackLabel.NO_ACTIVE -> "User marked no tremor"
                FeedbackLabel.IGNORE -> "Prompt ignored"
            },
            timestampMs = now
        )
        refreshStatusSnapshot()

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

    private fun hydratePersistedInsight() {
        val persisted = getPersistedStatusSnapshot(context)
        yesLabelCount.set(persisted.yesLabelCount)
        noLabelCount.set(persisted.noLabelCount)
        ignoredLabelCount.set(persisted.ignoredLabelCount)
        totalPromptCount.set(persisted.promptsTotal)
        promptsToday.set(persisted.promptsToday)
        lastPromptTime = persisted.lastPromptTimeMs ?: 0L
        trainingStartTimeMs = persisted.trainingStartTimeMs
        trainingCompletedTimeMs = persisted.trainingCompletedTimeMs
        lastFeedbackTimeMs = persisted.lastFeedbackTimeMs
        lastFeedbackLabel = persisted.lastFeedbackLabel

        val logJson = statusPrefs.getString(KEY_LOG_JSON, null)
        if (!logJson.isNullOrBlank()) {
            try {
                val decoded = statusJson.decodeFromString<List<TrainingLogEntry>>(logJson)
                synchronized(trainingLog) {
                    trainingLog.clear()
                    decoded.takeLast(MAX_LOG_ENTRIES).forEach { trainingLog.addLast(it) }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to hydrate persisted training log")
                synchronized(trainingLog) { trainingLog.clear() }
            }
        }

        // [H4] Restore live state field from persisted value so training survives
        // process death + restart. Without this, isTrainingActive returns false and
        // onEngineSample() is a silent no-op after every service restart.
        // Restore conservatively as WARMUP (re-validates prompt guardrails) rather
        // than directly re-entering ACTIVE.
        val savedState = parseTrainingState(
            statusPrefs.getString(KEY_ENGINE_STATE, TrainingState.OFF.name),
            TrainingState.OFF
        )
        if (savedState == TrainingState.WARMUP || savedState == TrainingState.ACTIVE) {
            state = TrainingState.WARMUP
            Timber.i("[H4] Restored training state to WARMUP from persisted: $savedState")
        }
    }

    private fun appendLog(
        type: String,
        detail: String,
        label: FeedbackLabel? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        synchronized(trainingLog) {
            if (trainingLog.size >= MAX_LOG_ENTRIES) {
                trainingLog.removeFirst()
            }
            trainingLog.addLast(
                TrainingLogEntry(
                    timestampMs = timestampMs,
                    type = type,
                    label = label,
                    detail = detail
                )
            )
            persistLogLocked()
        }
    }

    private fun persistLogLocked() {
        try {
            val encoded = statusJson.encodeToString(trainingLog.toList())
            statusPrefs.edit().putString(KEY_LOG_JSON, encoded).apply()
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist training log")
        }
    }

    private fun buildStatusSnapshot(): TrainingStatusSnapshot {
        // [M1] Pure snapshot builder — no side effects. State mutations (setting
        // trainingCompletedTimeMs, logging) have been moved to refreshStatusSnapshot()
        // so this method can be called safely without unintended writes.
        val yes = yesLabelCount.get()
        val no = noLabelCount.get()
        val ignored = ignoredLabelCount.get()
        val usable = yes + no
        val hasEnough = usable >= MIN_LABELS_FOR_ACTIVE
        val totalPrompts = totalPromptCount.get()
        val startMs = trainingStartTimeMs
        val completedMs = trainingCompletedTimeMs
        val daysElapsed = startMs?.let {
            ((System.currentTimeMillis() - it) / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(0)
        } ?: 0

        val uiState = when {
            state == TrainingState.OFF -> TrainingState.OFF
            hasEnough -> TrainingState.PERSONALIZED
            totalPrompts == 0 -> TrainingState.WARMUP
            else -> TrainingState.ACTIVE
        }

        return TrainingStatusSnapshot(
            modeEnabled = state != TrainingState.OFF,
            engineState = state,
            uiState = uiState,
            usableLabelCount = usable,
            yesLabelCount = yes,
            noLabelCount = no,
            ignoredLabelCount = ignored,
            targetUsableLabelCount = MIN_LABELS_FOR_ACTIVE,
            hasEnoughLabels = hasEnough,
            promptsTotal = totalPrompts,
            promptsToday = promptsToday.get(),
            pendingPromptCount = pendingFeedback.size,
            trainingStartTimeMs = startMs,
            trainingCompletedTimeMs = completedMs,
            trainingDaysElapsed = daysElapsed,
            targetTrainingDays = TRAINING_DURATION_DAYS,
            lastPromptTimeMs = if (lastPromptTime > 0L) lastPromptTime else null,
            lastFeedbackTimeMs = lastFeedbackTimeMs,
            lastFeedbackLabel = lastFeedbackLabel
        )
    }

    private fun refreshStatusSnapshot() {
        // [M1] Threshold-crossing transition logic lives here, not in buildStatusSnapshot(),
        // so the builder is side-effect-free. A brief dip below the label count threshold
        // (e.g. a race with phone-side delete) no longer resets trainingCompletedTimeMs
        // on every snapshot build.
        val usable = yesLabelCount.get() + noLabelCount.get()
        val hasEnough = usable >= MIN_LABELS_FOR_ACTIVE
        if (hasEnough && trainingCompletedTimeMs == null) {
            trainingCompletedTimeMs = System.currentTimeMillis()
            appendLog(type = "TRAINING", detail = "Training threshold reached")
        } else if (!hasEnough) {
            trainingCompletedTimeMs = null
        }
        val snapshot = buildStatusSnapshot()
        statusState.value = snapshot
        statusPrefs.edit()
            .putInt(KEY_YES_COUNT, snapshot.yesLabelCount)
            .putInt(KEY_NO_COUNT, snapshot.noLabelCount)
            .putInt(KEY_IGNORE_COUNT, snapshot.ignoredLabelCount)
            .putInt(KEY_TOTAL_PROMPTS, snapshot.promptsTotal)
            .putInt(KEY_PROMPTS_TODAY, snapshot.promptsToday)
            .putInt(KEY_PENDING_PROMPTS, snapshot.pendingPromptCount)
            .putLong(KEY_LAST_PROMPT_MS, snapshot.lastPromptTimeMs ?: 0L)
            .putLong(KEY_LAST_FEEDBACK_MS, snapshot.lastFeedbackTimeMs ?: 0L)
            .putString(KEY_LAST_FEEDBACK_LABEL, snapshot.lastFeedbackLabel?.name)
            .putLong(KEY_TRAINING_START_MS, snapshot.trainingStartTimeMs ?: 0L)
            .putLong(KEY_TRAINING_COMPLETED_MS, snapshot.trainingCompletedTimeMs ?: 0L)
            .putString(KEY_ENGINE_STATE, snapshot.engineState.name)
            .apply()
        maybeSyncStatusToPhone(snapshot)
    }

    private fun maybeSyncStatusToPhone(snapshot: TrainingStatusSnapshot) {
        val fingerprint = buildStatusFingerprint(snapshot)
        val now = System.currentTimeMillis()
        val isDuplicate = fingerprint == lastStatusSyncFingerprint
        val isRateLimited = now - lastStatusSyncTimeMs < STATUS_SYNC_MIN_INTERVAL_MS
        if (isDuplicate && isRateLimited) return

        dataSender.sendTrainingStateUpdate(
            enabled = snapshot.modeEnabled,
            engineState = snapshot.engineState.name,
            uiState = snapshot.uiState.name,
            usableLabels = snapshot.usableLabelCount,
            targetLabels = snapshot.targetUsableLabelCount,
            yesLabels = snapshot.yesLabelCount,
            noLabels = snapshot.noLabelCount,
            ignoredLabels = snapshot.ignoredLabelCount,
            promptsTotal = snapshot.promptsTotal,
            promptsToday = snapshot.promptsToday,
            hasEnoughLabels = snapshot.hasEnoughLabels,
            trainingStartTimeMs = snapshot.trainingStartTimeMs ?: 0L,
            trainingCompletedTimeMs = snapshot.trainingCompletedTimeMs ?: 0L,
            lastPromptTimeMs = snapshot.lastPromptTimeMs ?: 0L,
            lastFeedbackTimeMs = snapshot.lastFeedbackTimeMs ?: 0L,
            lastFeedbackLabel = snapshot.lastFeedbackLabel?.name ?: ""
        ) { success ->
            if (success) {
                lastStatusSyncFingerprint = fingerprint
                lastStatusSyncTimeMs = now
            }
        }
    }

    private fun buildStatusFingerprint(snapshot: TrainingStatusSnapshot): String {
        return listOf(
            snapshot.modeEnabled,
            snapshot.engineState.name,
            snapshot.uiState.name,
            snapshot.usableLabelCount,
            snapshot.yesLabelCount,
            snapshot.noLabelCount,
            snapshot.ignoredLabelCount,
            snapshot.promptsTotal,
            snapshot.promptsToday,
            snapshot.pendingPromptCount,
            snapshot.hasEnoughLabels,
            snapshot.trainingStartTimeMs ?: 0L,
            snapshot.trainingCompletedTimeMs ?: 0L,
            snapshot.lastPromptTimeMs ?: 0L,
            snapshot.lastFeedbackTimeMs ?: 0L,
            snapshot.lastFeedbackLabel?.name ?: ""
        ).joinToString("|")
    }

    /**
     * [P4] Evict pending feedback entries older than PROMPT_TIMEOUT + 5s.
     * Treats stale entries as IGNORE so they still get sent to the phone.
     * Prevents unbounded memory growth if activities are killed without response.
     *
     * [M5] Only collects stale IDs here (fast ConcurrentHashMap read, safe inside the
     * @Synchronized requestUserFeedback() block). The actual onUserFeedback() calls —
     * which do disk I/O (saveLocalBackup) and IPC (dataSender) — are posted to the
     * main looper so they execute after the sensor-thread lock is released.
     */
    private fun evictStalePending() {
        val cutoff = System.currentTimeMillis() - (PROMPT_TIMEOUT_MS + 5000L)
        val staleIds = pendingFeedback.filter { it.value.timestamp < cutoff }.keys.toList()
        if (staleIds.isEmpty()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            staleIds.forEach { id ->
                Timber.d("Evicting stale pending feedback: $id")
                onUserFeedback(id, FeedbackLabel.IGNORE)
            }
        }
    }
}
