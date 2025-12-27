package com.opensource.tremorwatch.phone.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for tremor data.
 * Provides fast indexed queries to replace slow JSONL file reading.
 */
@Database(
    entities = [TremorSample::class],
    version = 1,
    exportSchema = false
)
abstract class TremorRoomDatabase : RoomDatabase() {
    
    abstract fun tremorDao(): TremorDao
    
    companion object {
        @Volatile
        private var INSTANCE: TremorRoomDatabase? = null
        
        fun getDatabase(context: Context): TremorRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TremorRoomDatabase::class.java,
                    "tremor_data.db"
                )
                .fallbackToDestructiveMigration()  // For now, OK to lose data on schema changes
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
