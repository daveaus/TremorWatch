package com.opensource.tremorwatch.phone.data

import androidx.room.*

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

    // Detection outcomes at prompt time
    val productionIsTremor: Boolean = false,
    val shadowIsTremor: Boolean = false,
    val triggerReason: String = "",

    // Metadata
    val label: String = feedback   // Denormalized for optimizer queries
)

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

    @Query("SELECT COUNT(*) FROM training_labels WHERE label = 'YES_TREMOR'")
    suspend fun getPositiveLabelCount(): Int

    @Query("SELECT COUNT(*) FROM training_labels WHERE label = 'NO_ACTIVE'")
    suspend fun getNegativeLabelCount(): Int

    @Query("SELECT * FROM training_labels ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLabels(limit: Int): List<TrainingLabelEntity>

    @Query("DELETE FROM training_labels WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM training_labels")
    suspend fun deleteAll()
}
