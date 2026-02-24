package com.opensource.tremorwatch.phone.training

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.opensource.tremorwatch.phone.data.TrainingLabelDao

/**
 * [P10] Custom WorkerFactory for NightlyAutoTuner.
 *
 * WorkManager's default reflection-based instantiation cannot construct
 * NightlyAutoTuner because it requires extra constructor parameters
 * (labelDao, configManager) that aren't part of the standard Worker signature.
 *
 * Register in Application.onCreate():
 *   val config = Configuration.Builder()
 *       .setWorkerFactory(TrainingWorkerFactory(labelDao, configManager))
 *       .build()
 *   WorkManager.initialize(this, config)
 */
class TrainingWorkerFactory(
    private val labelDao: TrainingLabelDao,
    private val configManager: TremorConfigManager
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            NightlyAutoTuner::class.java.name ->
                NightlyAutoTuner(appContext, workerParameters, labelDao, configManager)
            else -> null  // Delegate to default factory for other workers
        }
    }
}
