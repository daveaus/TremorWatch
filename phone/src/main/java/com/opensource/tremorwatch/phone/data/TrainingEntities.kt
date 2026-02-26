package com.opensource.tremorwatch.phone.data

import android.content.SharedPreferences
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for storing training labels received from the watch.
 * Each row represents one user-labeled training sample with its FFT features.
 */
@Entity(
    tableName = "training_labels",
    indices = [
        Index("timestamp"),
        Index("label")
    ]
)
data class TrainingLabelEntity(
    @PrimaryKey
    val sampleId: String,
    val timestamp: Long,
    val feedback: String,              // YES_TREMOR, NO_ACTIVE, IGNORE
    val feedbackTimestamp: Long? = null,
    val responseLatencyMs: Long? = null,

    // Core FFT features (used by optimizer)
    val dominantFrequency: Float = 0f,
    val bandRatio: Float = 0f,
    val confidence: Float = 0f,
    val calibratedConfidence: Float = 0f,
    val totalPower: Float = 0f,
    val tremorBandPower: Float = 0f,

    // Extended features
    val spectralEntropy: Float = 0f,
    val harmonicRatio: Float = 0f,
    val peakProminence: Float = 0f,

    // Engine-level features
    val crossSensorSupport: Float = 0f,
    val frequencyStability: Float = 0f,
    val magnitude: Float = 0f,
    val accelMagnitude: Float = 0f,

    // Context
    val activityType: String = "unknown",
    val activityConfidence: Float = 0f,
    val isResting: Boolean = true,

    // Fixed-band powers used for offline boundary simulation
    @ColumnInfo(defaultValue = "0.0")
    val bandPower2to4Hz: Float = 0f,
    @ColumnInfo(defaultValue = "0.0")
    val bandPower4to6Hz: Float = 0f,
    @ColumnInfo(defaultValue = "0.0")
    val bandPower6to8Hz: Float = 0f,
    @ColumnInfo(defaultValue = "0.0")
    val bandPower8to10Hz: Float = 0f,
    @ColumnInfo(defaultValue = "0.0")
    val bandPower10to12Hz: Float = 0f,
    @ColumnInfo(defaultValue = "0.0")
    val bandPower12to14Hz: Float = 0f,

    // Detection outcomes at prompt time
    val productionIsTremor: Boolean = false,
    val shadowIsTremor: Boolean = false,
    val triggerReason: String = "",

    // Metadata
    val label: String = feedback   // Denormalized for optimizer queries
)

/**
 * Snapshot of watch-reported training runtime state for phone UI.
 * Stored in SharedPreferences via WatchDataListenerService.
 */
data class WatchTrainingStateSnapshot(
    val hasData: Boolean = false,
    val enabled: Boolean = false,
    val engineState: String = "OFF",
    val uiState: String = "OFF",
    val usableLabels: Int = 0,
    val targetLabels: Int = 10,
    val yesLabels: Int = 0,
    val noLabels: Int = 0,
    val ignoredLabels: Int = 0,
    val promptsTotal: Int = 0,
    val promptsToday: Int = 0,
    val hasEnoughLabels: Boolean = false,
    val timestampMs: Long = 0L,
    val trainingStartTimeMs: Long = 0L,
    val trainingCompletedTimeMs: Long = 0L,
    val lastPromptTimeMs: Long = 0L,
    val lastFeedbackTimeMs: Long = 0L,
    val lastFeedbackLabel: String = ""
)

/**
 * Shared preference contract for watch -> phone training state echo.
 */
object WatchTrainingStatePrefs {
    const val PREFS_NAME = "training_state"
    private const val KEY_ENABLED = "watch_enabled"
    private const val KEY_ENGINE_STATE = "watch_engine_state"
    private const val KEY_UI_STATE = "watch_ui_state"
    private const val KEY_USABLE_LABELS = "watch_usable_labels"
    private const val KEY_TARGET_LABELS = "watch_target_labels"
    private const val KEY_YES_LABELS = "watch_yes_labels"
    private const val KEY_NO_LABELS = "watch_no_labels"
    private const val KEY_IGNORED_LABELS = "watch_ignored_labels"
    private const val KEY_PROMPTS_TOTAL = "watch_prompts_total"
    private const val KEY_PROMPTS_TODAY = "watch_prompts_today"
    private const val KEY_HAS_ENOUGH = "watch_has_enough_labels"
    private const val KEY_TIMESTAMP_MS = "watch_timestamp_ms"
    private const val KEY_TRAINING_START_MS = "watch_training_start_ms"
    private const val KEY_TRAINING_COMPLETED_MS = "watch_training_completed_ms"
    private const val KEY_LAST_PROMPT_MS = "watch_last_prompt_ms"
    private const val KEY_LAST_FEEDBACK_MS = "watch_last_feedback_ms"
    private const val KEY_LAST_FEEDBACK_LABEL = "watch_last_feedback_label"

    fun read(prefs: SharedPreferences): WatchTrainingStateSnapshot {
        val timestampMs = prefs.getLong(KEY_TIMESTAMP_MS, 0L)
        return WatchTrainingStateSnapshot(
            hasData = timestampMs > 0L,
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            engineState = prefs.getString(KEY_ENGINE_STATE, "OFF") ?: "OFF",
            uiState = prefs.getString(KEY_UI_STATE, "OFF") ?: "OFF",
            usableLabels = prefs.getInt(KEY_USABLE_LABELS, 0),
            targetLabels = prefs.getInt(KEY_TARGET_LABELS, 10).coerceAtLeast(1),
            yesLabels = prefs.getInt(KEY_YES_LABELS, 0),
            noLabels = prefs.getInt(KEY_NO_LABELS, 0),
            ignoredLabels = prefs.getInt(KEY_IGNORED_LABELS, 0),
            promptsTotal = prefs.getInt(KEY_PROMPTS_TOTAL, 0),
            promptsToday = prefs.getInt(KEY_PROMPTS_TODAY, 0),
            hasEnoughLabels = prefs.getBoolean(KEY_HAS_ENOUGH, false),
            timestampMs = timestampMs,
            trainingStartTimeMs = prefs.getLong(KEY_TRAINING_START_MS, 0L),
            trainingCompletedTimeMs = prefs.getLong(KEY_TRAINING_COMPLETED_MS, 0L),
            lastPromptTimeMs = prefs.getLong(KEY_LAST_PROMPT_MS, 0L),
            lastFeedbackTimeMs = prefs.getLong(KEY_LAST_FEEDBACK_MS, 0L),
            lastFeedbackLabel = prefs.getString(KEY_LAST_FEEDBACK_LABEL, "") ?: ""
        )
    }

    fun write(prefs: SharedPreferences, snapshot: WatchTrainingStateSnapshot) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, snapshot.enabled)
            .putString(KEY_ENGINE_STATE, snapshot.engineState)
            .putString(KEY_UI_STATE, snapshot.uiState)
            .putInt(KEY_USABLE_LABELS, snapshot.usableLabels)
            .putInt(KEY_TARGET_LABELS, snapshot.targetLabels.coerceAtLeast(1))
            .putInt(KEY_YES_LABELS, snapshot.yesLabels)
            .putInt(KEY_NO_LABELS, snapshot.noLabels)
            .putInt(KEY_IGNORED_LABELS, snapshot.ignoredLabels)
            .putInt(KEY_PROMPTS_TOTAL, snapshot.promptsTotal)
            .putInt(KEY_PROMPTS_TODAY, snapshot.promptsToday)
            .putBoolean(KEY_HAS_ENOUGH, snapshot.hasEnoughLabels)
            .putLong(KEY_TIMESTAMP_MS, snapshot.timestampMs)
            .putLong(KEY_TRAINING_START_MS, snapshot.trainingStartTimeMs)
            .putLong(KEY_TRAINING_COMPLETED_MS, snapshot.trainingCompletedTimeMs)
            .putLong(KEY_LAST_PROMPT_MS, snapshot.lastPromptTimeMs)
            .putLong(KEY_LAST_FEEDBACK_MS, snapshot.lastFeedbackTimeMs)
            .putString(KEY_LAST_FEEDBACK_LABEL, snapshot.lastFeedbackLabel)
            .apply()
    }
}

/**
 * DAO for training labels.
 * [P12] getUsableLabels uses @Transaction for consistent snapshot during optimization.
 */
@Dao
interface TrainingLabelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TrainingLabelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TrainingLabelEntity>)

    /**
     * [P12] @Transaction ensures a consistent snapshot when the nightly optimizer
     * reads labels. Without this, concurrent inserts from WatchDataListenerService
     * could produce a dirty read (e.g., half-inserted batch of labels).
     */
    @Transaction
    @Query("SELECT * FROM training_labels WHERE label != 'IGNORE' ORDER BY timestamp DESC")
    suspend fun getUsableLabels(): List<TrainingLabelEntity>

    @Query("SELECT COUNT(*) FROM training_labels WHERE label != 'IGNORE'")
    suspend fun getUsableLabelCount(): Int

    @Query("SELECT COUNT(*) FROM training_labels WHERE label != 'IGNORE'")
    fun observeUsableLabelCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM training_labels WHERE label = 'YES_TREMOR'")
    suspend fun getPositiveLabelCount(): Int

    @Query("SELECT COUNT(*) FROM training_labels WHERE label = 'YES_TREMOR'")
    fun observePositiveLabelCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM training_labels WHERE label = 'NO_ACTIVE'")
    suspend fun getNegativeLabelCount(): Int

    @Query("SELECT COUNT(*) FROM training_labels WHERE label = 'NO_ACTIVE'")
    fun observeNegativeLabelCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM training_labels WHERE label = 'IGNORE'")
    suspend fun getIgnoredLabelCount(): Int

    @Query("SELECT COUNT(*) FROM training_labels WHERE label = 'IGNORE'")
    fun observeIgnoredLabelCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM training_labels")
    suspend fun getTotalLabelCount(): Int

    @Query("SELECT COUNT(*) FROM training_labels")
    fun observeTotalLabelCount(): Flow<Int>

    @Query("SELECT * FROM training_labels ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLabels(limit: Int): List<TrainingLabelEntity>

    @Query("SELECT * FROM training_labels ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentLabels(limit: Int): Flow<List<TrainingLabelEntity>>

    @Query("DELETE FROM training_labels WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM training_labels")
    suspend fun deleteAll()
}
