package com.opensource.tremorwatch.phone.database

import android.content.Context
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
    entities = [TremorSample::class, SubjectiveRatingEntity::class, CalibrationDataEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TremorRoomDatabase : RoomDatabase() {
    
    abstract fun tremorDao(): TremorDao
    
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
        
        fun getDatabase(context: Context): TremorRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TremorRoomDatabase::class.java,
                    "tremor_data.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
