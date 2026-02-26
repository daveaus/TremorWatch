package com.opensource.tremorwatch.phone.training

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max

enum class TrainingTuneOutcome {
    NEVER,
    SKIPPED,
    REJECTED,
    APPLIED,
    APPLY_FAILED,
    ROLLED_BACK
}

data class TrainingTuneSnapshot(
    val trainingModeEnabled: Boolean = false,
    val schedulerEnabled: Boolean = false,
    val lastRunTimeMs: Long = 0L,
    val lastRunReason: String = "",
    val lastOutcome: TrainingTuneOutcome = TrainingTuneOutcome.NEVER,
    val lastMessage: String = "",
    val samplesUsed: Int = 0,
    val beforeJ: Float = 0f,
    val afterJ: Float = 0f,
    val lastAppliedTimeMs: Long = 0L,
    val trainedProfileActive: Boolean = false,
    val syncedToWatch: Boolean = false,
    val hasRollbackSnapshot: Boolean = false,
    val lastImmediateEnqueueMs: Long = 0L,
    val autoApplyBlockedUntilMs: Long = 0L
)

object TrainingTuneStateStore {
    const val PREFS_NAME = "training_tune_state"

    private const val KEY_TRAINING_MODE_ENABLED = "training_mode_enabled"
    private const val KEY_SCHEDULER_ENABLED = "scheduler_enabled"
    private const val KEY_LAST_RUN_TIME_MS = "last_run_time_ms"
    private const val KEY_LAST_RUN_REASON = "last_run_reason"
    private const val KEY_LAST_OUTCOME = "last_outcome"
    private const val KEY_LAST_MESSAGE = "last_message"
    private const val KEY_SAMPLES_USED = "samples_used"
    private const val KEY_BEFORE_J = "before_j"
    private const val KEY_AFTER_J = "after_j"
    private const val KEY_LAST_APPLIED_TIME_MS = "last_applied_time_ms"
    private const val KEY_TRAINED_PROFILE_ACTIVE = "trained_profile_active"
    private const val KEY_SYNCED_TO_WATCH = "synced_to_watch"
    private const val KEY_HAS_ROLLBACK_SNAPSHOT = "has_rollback_snapshot"
    private const val KEY_LAST_IMMEDIATE_ENQUEUE_MS = "last_immediate_enqueue_ms"
    private const val KEY_AUTO_APPLY_BLOCKED_UNTIL_MS = "auto_apply_blocked_until_ms"

    private val lock = Any()

    fun read(context: Context): TrainingTuneSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return read(prefs)
    }

    fun read(prefs: SharedPreferences): TrainingTuneSnapshot {
        val rawOutcome = prefs.getString(KEY_LAST_OUTCOME, TrainingTuneOutcome.NEVER.name)
        val outcome = try {
            TrainingTuneOutcome.valueOf(rawOutcome ?: TrainingTuneOutcome.NEVER.name)
        } catch (_: IllegalArgumentException) {
            TrainingTuneOutcome.NEVER
        }

        return TrainingTuneSnapshot(
            trainingModeEnabled = prefs.getBoolean(KEY_TRAINING_MODE_ENABLED, false),
            schedulerEnabled = prefs.getBoolean(KEY_SCHEDULER_ENABLED, false),
            lastRunTimeMs = prefs.getLong(KEY_LAST_RUN_TIME_MS, 0L),
            lastRunReason = prefs.getString(KEY_LAST_RUN_REASON, "") ?: "",
            lastOutcome = outcome,
            lastMessage = prefs.getString(KEY_LAST_MESSAGE, "") ?: "",
            samplesUsed = prefs.getInt(KEY_SAMPLES_USED, 0),
            beforeJ = prefs.getFloat(KEY_BEFORE_J, 0f),
            afterJ = prefs.getFloat(KEY_AFTER_J, 0f),
            lastAppliedTimeMs = prefs.getLong(KEY_LAST_APPLIED_TIME_MS, 0L),
            trainedProfileActive = prefs.getBoolean(KEY_TRAINED_PROFILE_ACTIVE, false),
            syncedToWatch = prefs.getBoolean(KEY_SYNCED_TO_WATCH, false),
            hasRollbackSnapshot = prefs.getBoolean(KEY_HAS_ROLLBACK_SNAPSHOT, false),
            lastImmediateEnqueueMs = prefs.getLong(KEY_LAST_IMMEDIATE_ENQUEUE_MS, 0L),
            autoApplyBlockedUntilMs = prefs.getLong(KEY_AUTO_APPLY_BLOCKED_UNTIL_MS, 0L)
        )
    }

    private fun write(prefs: SharedPreferences, snapshot: TrainingTuneSnapshot) {
        prefs.edit()
            .putBoolean(KEY_TRAINING_MODE_ENABLED, snapshot.trainingModeEnabled)
            .putBoolean(KEY_SCHEDULER_ENABLED, snapshot.schedulerEnabled)
            .putLong(KEY_LAST_RUN_TIME_MS, snapshot.lastRunTimeMs)
            .putString(KEY_LAST_RUN_REASON, snapshot.lastRunReason)
            .putString(KEY_LAST_OUTCOME, snapshot.lastOutcome.name)
            .putString(KEY_LAST_MESSAGE, snapshot.lastMessage)
            .putInt(KEY_SAMPLES_USED, snapshot.samplesUsed)
            .putFloat(KEY_BEFORE_J, snapshot.beforeJ)
            .putFloat(KEY_AFTER_J, snapshot.afterJ)
            .putLong(KEY_LAST_APPLIED_TIME_MS, snapshot.lastAppliedTimeMs)
            .putBoolean(KEY_TRAINED_PROFILE_ACTIVE, snapshot.trainedProfileActive)
            .putBoolean(KEY_SYNCED_TO_WATCH, snapshot.syncedToWatch)
            .putBoolean(KEY_HAS_ROLLBACK_SNAPSHOT, snapshot.hasRollbackSnapshot)
            .putLong(KEY_LAST_IMMEDIATE_ENQUEUE_MS, snapshot.lastImmediateEnqueueMs)
            .putLong(KEY_AUTO_APPLY_BLOCKED_UNTIL_MS, snapshot.autoApplyBlockedUntilMs)
            .apply()
    }

    fun update(context: Context, transform: (TrainingTuneSnapshot) -> TrainingTuneSnapshot) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(lock) {
            val current = read(prefs)
            write(prefs, transform(current))
        }
    }

    fun markTrainingMode(context: Context, enabled: Boolean) {
        update(context) { it.copy(trainingModeEnabled = enabled) }
    }

    fun markScheduler(context: Context, enabled: Boolean) {
        update(context) { it.copy(schedulerEnabled = enabled) }
    }

    fun markImmediateEnqueue(context: Context, timestampMs: Long = System.currentTimeMillis()) {
        update(context) { it.copy(lastImmediateEnqueueMs = max(0L, timestampMs)) }
    }

    fun setAutoApplyBlockedUntil(context: Context, timestampMs: Long) {
        update(context) { it.copy(autoApplyBlockedUntilMs = max(0L, timestampMs)) }
    }

    fun markRunResult(
        context: Context,
        reason: String,
        outcome: TrainingTuneOutcome,
        message: String,
        samplesUsed: Int,
        beforeJ: Float,
        afterJ: Float,
        trainedProfileActive: Boolean,
        syncedToWatch: Boolean,
        hasRollbackSnapshot: Boolean,
        appliedNow: Boolean,
        runTimestampMs: Long = System.currentTimeMillis()
    ) {
        val safeSamplesUsed = max(0, samplesUsed)
        val safeBeforeJ = if (beforeJ.isFinite()) beforeJ else 0f
        val safeAfterJ = if (afterJ.isFinite()) afterJ else 0f
        val safeRunTimestamp = max(0L, runTimestampMs)

        update(context) {
            it.copy(
                lastRunTimeMs = safeRunTimestamp,
                lastRunReason = reason,
                lastOutcome = outcome,
                lastMessage = message,
                samplesUsed = safeSamplesUsed,
                beforeJ = safeBeforeJ,
                afterJ = safeAfterJ,
                lastAppliedTimeMs = if (appliedNow) safeRunTimestamp else it.lastAppliedTimeMs,
                trainedProfileActive = trainedProfileActive,
                syncedToWatch = syncedToWatch,
                hasRollbackSnapshot = hasRollbackSnapshot
            )
        }
    }

    fun reset(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(lock) {
            write(prefs, TrainingTuneSnapshot())
        }
    }
}
