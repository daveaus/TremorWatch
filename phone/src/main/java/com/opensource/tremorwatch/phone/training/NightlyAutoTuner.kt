package com.opensource.tremorwatch.phone.training

import android.content.Context
import androidx.work.*
import com.opensource.tremorwatch.phone.data.TrainingLabelDao
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.time.LocalTime
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based nightly optimization worker.
 * Runs between 2-4 AM when the phone is charging and idle.
 *
 * [P10] Constructor injection via TrainingWorkerFactory — WorkManager's default
 * reflection-based instantiation cannot handle extra ctor parameters.
 * [P15] Uses ExistingPeriodicWorkPolicy.UPDATE instead of KEEP so the
 * schedule refreshes correctly after device reboots.
 */
class NightlyAutoTuner(
    context: Context,
    params: WorkerParameters,
    private val labelDao: TrainingLabelDao,
    private val configManager: TremorConfigManager
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "nightly_auto_tuner"
        const val CONFIG_FILE = "personalized_config.json"
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        /**
         * Schedule the nightly optimization run.
         * [P15] Uses UPDATE policy to refresh initial delay after reboots.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<NightlyAutoTuner>(
                24, TimeUnit.HOURS,
                2, TimeUnit.HOURS  // Flex window: 2AM-4AM
            )
                .setConstraints(constraints)
                .setInitialDelay(calculateDelayUntil2AM(), TimeUnit.MILLISECONDS)
                .build()

            // [P15] UPDATE instead of KEEP — KEEP ignores rescheduling after
            // device reboots, leaving a stale schedule. UPDATE refreshes the
            // initial delay calculation so the next run targets 2 AM correctly.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Timber.i("Nightly auto-tuner scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("Nightly auto-tuner cancelled")
        }

        private fun calculateDelayUntil2AM(): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 2)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Timber.i("NightlyAutoTuner starting optimization run")

            val labels = labelDao.getUsableLabels()
            val currentConfig = configManager.getCurrentConfig()
            val optimizer = TrainingParameterOptimizer()

            val result = optimizer.optimize(currentConfig, labels)

            if (result.applied) {
                // Save optimized config
                val configJson = json.encodeToString(result.optimizedConfig)
                File(applicationContext.filesDir, CONFIG_FILE).writeText(configJson)
                configManager.applyConfig(result.optimizedConfig)

                Timber.i(
                    "Optimization applied: J %.3f → %.3f (%d samples)",
                    result.beforeJ, result.afterJ, result.samplesUsed
                )
            } else {
                Timber.i("Optimization skipped: ${result.reason}")
            }

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "NightlyAutoTuner failed")
            Result.retry()
        }
    }
}

/**
 * Interface for managing the active TremorDetectionConfig.
 * Implement this in your phone app's data layer to bridge
 * between the optimizer and the config sync mechanism.
 */
interface TremorConfigManager {
    fun getCurrentConfig(): TremorDetectionConfig
    fun applyConfig(config: TremorDetectionConfig)
}
