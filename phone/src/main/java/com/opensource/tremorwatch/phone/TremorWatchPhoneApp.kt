package com.opensource.tremorwatch.phone

import android.app.Application
import androidx.work.Configuration
import com.opensource.tremorwatch.phone.config.TremorConfigManager
import com.opensource.tremorwatch.phone.database.TremorRoomDatabase
import com.opensource.tremorwatch.phone.training.TrainingConfigBridge
import com.opensource.tremorwatch.phone.training.TrainingWorkerFactory
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import timber.log.Timber

/**
 * Custom Application class for TremorWatch Phone app.
 * Implements Configuration.Provider to supply custom WorkerFactory.
 */
class TremorWatchPhoneApp : Application(), Configuration.Provider {

    private val roomDb by lazy { TremorRoomDatabase.getDatabase(this) }
    private val configManager by lazy { TremorConfigManager(this) }

    private val configBridge by lazy {
        object : TrainingConfigBridge {
            override suspend fun isTrainingModeEnabled(): Boolean {
                return configManager.isTrainingModeEnabled()
            }

            override suspend fun getCurrentConfig(): TremorDetectionConfig {
                return configManager.getActiveConfig()
            }

            override suspend fun applyConfig(config: TremorDetectionConfig): com.opensource.tremorwatch.phone.training.ApplyConfigResult {
                return configManager.applyAutoTunedConfig(config)
            }
        }
    }

    private val workManagerConfig by lazy {
        val factory = TrainingWorkerFactory(
            labelDao = roomDb.trainingLabelDao(),
            configBridge = configBridge
        )
        Configuration.Builder()
            .setWorkerFactory(factory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("TremorWatch Phone App initialized (Debug build)")
        } else {
            Timber.plant(ReleaseTree())
            Timber.i("TremorWatch Phone App initialized (Release build)")
        }
    }

    override val workManagerConfiguration: Configuration
        get() = workManagerConfig

    /**
     * Release build tree - only logs warnings and errors.
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= android.util.Log.WARN) {
                if (t != null) {
                    android.util.Log.println(priority, tag ?: "TremorWatch", "$message\n${android.util.Log.getStackTraceString(t)}")
                } else {
                    android.util.Log.println(priority, tag ?: "TremorWatch", message)
                }
            }
        }
    }
}
