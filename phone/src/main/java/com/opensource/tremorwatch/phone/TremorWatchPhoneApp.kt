package com.opensource.tremorwatch.phone

import android.app.Application
import androidx.work.Configuration
import com.opensource.tremorwatch.phone.database.TremorRoomDatabase
import com.opensource.tremorwatch.phone.training.TrainingWorkerFactory
import timber.log.Timber

/**
 * Custom Application class for TremorWatch Phone app.
 * Initializes logging and other app-wide configurations.
 * Implements Configuration.Provider to supply custom WorkerFactory for
 * dependency injection into NightlyAutoTuner.
 */
class TremorWatchPhoneApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            // Debug: Plant a DebugTree that logs with class name as tag
            Timber.plant(Timber.DebugTree())
            Timber.d("TremorWatch Phone App initialized (Debug build)")
        } else {
            // Release: Plant a tree that doesn't log to logcat
            // In production, you could plant a crash reporting tree here
            Timber.plant(ReleaseTree())
            Timber.i("TremorWatch Phone App initialized (Release build)")
        }
    }

    /**
     * Provide WorkManager configuration with custom WorkerFactory.
     * This allows NightlyAutoTuner to receive its dependencies (DAO, ConfigManager)
     * via constructor injection instead of manual creation inside doWork().
     */
    override val workManagerConfiguration: Configuration
        get() {
            val db = TremorRoomDatabase.getDatabase(this)
            val trainingLabelDao = db.trainingLabelDao()
            // TremorConfigManager implementation is provided by NightlyAutoTuner's companion
            // or by the phone's settings layer. Using a default no-op manager for now until
            // the settings UI is wired up.
            val configManager = object : com.opensource.tremorwatch.phone.training.TremorConfigManager {
                override fun getCurrentConfig(): com.opensource.tremorwatch.shared.models.TremorDetectionConfig {
                    return com.opensource.tremorwatch.shared.models.TremorDetectionConfig()
                }
                override fun applyConfig(config: com.opensource.tremorwatch.shared.models.TremorDetectionConfig) {
                    Timber.i("Auto-tuned config ready — will sync to watch on next connection")
                    // TODO: Send updated config to watch via MessageClient
                }
            }
            val factory = TrainingWorkerFactory(trainingLabelDao, configManager)
            return Configuration.Builder()
                .setWorkerFactory(factory)
                .build()
        }

    /**
     * Release build tree - only logs warnings and errors
     * Does not log debug/info messages to protect user privacy
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Only log warnings and errors in release builds
            if (priority >= android.util.Log.WARN) {
                // In a production app, you could send these to a crash reporting service
                android.util.Log.println(priority, tag ?: "TremorWatch", message)
            }
        }
    }
}
