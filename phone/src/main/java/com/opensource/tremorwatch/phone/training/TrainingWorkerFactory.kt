package com.opensource.tremorwatch.phone.training

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.opensource.tremorwatch.phone.data.TrainingLabelDao

/**
 * Custom WorkerFactory for NightlyAutoTuner.
 */
class TrainingWorkerFactory(
    private val labelDao: TrainingLabelDao,
    private val configBridge: TrainingConfigBridge
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            NightlyAutoTuner::class.java.name ->
                NightlyAutoTuner(appContext, workerParameters, labelDao, configBridge)
            else -> null
        }
    }
}
