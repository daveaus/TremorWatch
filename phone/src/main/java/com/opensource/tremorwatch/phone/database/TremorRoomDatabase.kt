package com.opensource.tremorwatch.phone.database

import android.content.Context
import com.opensource.tremorwatch.phone.data.TrainingLabelEntity
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for tremor data.
 * Provides fast indexed queries to replace slow JSONL file reading.
 */
@Database(
    entities = [
        TremorSample::class,
        SubjectiveRatingEntity::class,
        CalibrationDataEntity::class,
        MedicationIngestionEntity::class,
        TrainingLabelEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class TremorRoomDatabase : RoomDatabase() {
    
    abstract fun tremorDao(): TremorDao
    abstract fun trainingLabelDao(): com.opensource.tremorwatch.phone.data.TrainingLabelDao
    
    companion object {
        @Volatile
        private var INSTANCE: TremorRoomDatabase? = null
        
        /**
         * Migration from v1 to v2: adds subjective_ratings and calibration_data tables.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create subjective_ratings table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS subjective_ratings (
                        id TEXT PRIMARY KEY NOT NULL,
                        timestamp INTEGER NOT NULL,
                        rating INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        watchId TEXT,
                        detectedSeverity REAL,
                        detectedConfidence REAL,
                        detectedFrequency REAL,
                        calibrationModeEnabled INTEGER NOT NULL,
                        calibrationDurationSeconds INTEGER NOT NULL,
                        notes TEXT,
                        schemaVersion INTEGER NOT NULL DEFAULT 1
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_subjective_ratings_timestamp ON subjective_ratings(timestamp)")
                
                // Create calibration_data table with FK constraint
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS calibration_data (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ratingId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        magnitude REAL NOT NULL,
                        dominantFrequency REAL NOT NULL,
                        tremorBandPower REAL NOT NULL,
                        totalPower REAL NOT NULL,
                        bandRatio REAL NOT NULL,
                        peakProminence REAL NOT NULL,
                        confidence REAL NOT NULL,
                        severity REAL NOT NULL,
                        isWorn INTEGER NOT NULL,
                        isCharging INTEGER NOT NULL,
                        FOREIGN KEY (ratingId) REFERENCES subjective_ratings(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_calibration_data_ratingId ON calibration_data(ratingId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_calibration_data_timestamp ON calibration_data(timestamp)")
            }
        }
        
        /**
         * Migration from v2 to v3: adds unique constraint on tremor_samples.timestamp.
         * This deduplicates existing data and creates a unique index.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // First, delete duplicate entries keeping only the first one
                database.execSQL("""
                    DELETE FROM tremor_samples 
                    WHERE id NOT IN (
                        SELECT MIN(id) FROM tremor_samples GROUP BY timestamp
                    )
                """)
                
                // Drop the old non-unique index
                database.execSQL("DROP INDEX IF EXISTS index_tremor_samples_timestamp")
                
                // Create new unique index on timestamp
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tremor_samples_timestamp ON tremor_samples(timestamp)")
            }
        }
        
        /**
         * Migration from v3 to v4: adds metadataJson column to calibration_data.
         * Stores extended fields (tremor type, activity context, accelerometer) as JSON.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calibration_data ADD COLUMN metadataJson TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v4 to v5: adds objectiveContextJson column to subjective_ratings.
         * Stores windowed objective summaries captured at rating time (10s/60s/5m/15m, etc.).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE subjective_ratings ADD COLUMN objectiveContextJson TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v5 to v6: adds medication_ingestions table.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS medication_ingestions (
                        id TEXT PRIMARY KEY NOT NULL,
                        timestamp INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        watchId TEXT,
                        notes TEXT,
                        payloadJson TEXT
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medication_ingestions_timestamp ON medication_ingestions(timestamp)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medication_ingestions_source ON medication_ingestions(source)"
                )
            }
        }

        fun getDatabase(context: Context): TremorRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                TremorRoomDatabase::class.java,
                "tremor_data.db"
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8
                )
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Migration from v6 to v7: adds training_labels table for Active Learning.
         * No DEFAULT clauses — columns match TrainingLabelEntity's expected schema exactly.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS training_labels (
                        sampleId TEXT PRIMARY KEY NOT NULL,
                        timestamp INTEGER NOT NULL,
                        feedback TEXT NOT NULL,
                        feedbackTimestamp INTEGER,
                        responseLatencyMs INTEGER,
                        dominantFrequency REAL NOT NULL,
                        bandRatio REAL NOT NULL,
                        confidence REAL NOT NULL,
                        calibratedConfidence REAL NOT NULL,
                        totalPower REAL NOT NULL,
                        tremorBandPower REAL NOT NULL,
                        spectralEntropy REAL NOT NULL,
                        harmonicRatio REAL NOT NULL,
                        peakProminence REAL NOT NULL,
                        crossSensorSupport REAL NOT NULL,
                        frequencyStability REAL NOT NULL,
                        magnitude REAL NOT NULL,
                        accelMagnitude REAL NOT NULL,
                        activityType TEXT NOT NULL,
                        activityConfidence REAL NOT NULL,
                        isResting INTEGER NOT NULL,
                        productionIsTremor INTEGER NOT NULL,
                        shadowIsTremor INTEGER NOT NULL,
                        triggerReason TEXT NOT NULL,
                        label TEXT NOT NULL
                    )
                """)
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_training_labels_timestamp ON training_labels(timestamp)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_training_labels_label ON training_labels(label)"
                )
            }
        }

        /**
         * Migration from v7 to v8: fixes training_labels schema mismatch.
         * v7 MIGRATION_6_7 created columns with DEFAULT clauses (e.g., DEFAULT 0, DEFAULT ''),
         * but TrainingLabelEntity has no @ColumnInfo(defaultValue=...) annotations, so Room
         * expects defaultValue='undefined' for all columns. This recreates the table correctly.
         * Training data is empty at this stage (module just installed), so drop-recreate is safe.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS training_labels")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS training_labels (
                        sampleId TEXT PRIMARY KEY NOT NULL,
                        timestamp INTEGER NOT NULL,
                        feedback TEXT NOT NULL,
                        feedbackTimestamp INTEGER,
                        responseLatencyMs INTEGER,
                        dominantFrequency REAL NOT NULL,
                        bandRatio REAL NOT NULL,
                        confidence REAL NOT NULL,
                        calibratedConfidence REAL NOT NULL,
                        totalPower REAL NOT NULL,
                        tremorBandPower REAL NOT NULL,
                        spectralEntropy REAL NOT NULL,
                        harmonicRatio REAL NOT NULL,
                        peakProminence REAL NOT NULL,
                        crossSensorSupport REAL NOT NULL,
                        frequencyStability REAL NOT NULL,
                        magnitude REAL NOT NULL,
                        accelMagnitude REAL NOT NULL,
                        activityType TEXT NOT NULL,
                        activityConfidence REAL NOT NULL,
                        isResting INTEGER NOT NULL,
                        productionIsTremor INTEGER NOT NULL,
                        shadowIsTremor INTEGER NOT NULL,
                        triggerReason TEXT NOT NULL,
                        label TEXT NOT NULL
                    )
                """)
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_training_labels_timestamp ON training_labels(timestamp)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_training_labels_label ON training_labels(label)"
                )
            }
        }
    }
}
