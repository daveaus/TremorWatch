package com.opensource.tremorwatch.phone.training

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opensource.tremorwatch.phone.data.TrainingLabelDao
import com.opensource.tremorwatch.shared.models.TrainingThresholds
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit

interface TrainingConfigBridge {
    suspend fun isTrainingModeEnabled(): Boolean
    suspend fun getCurrentConfig(): TremorDetectionConfig
    suspend fun applyConfig(config: TremorDetectionConfig): ApplyConfigResult
}

data class ApplyConfigResult(
    val appliedLocally: Boolean,
    val syncedToWatch: Boolean,
    val hasRollbackSnapshot: Boolean,
    val detail: String,
    val retriableFailure: Boolean = false
)

class NightlyAutoTuner(
    context: Context,
    params: WorkerParameters,
    private val labelDao: TrainingLabelDao,
    private val configBridge: TrainingConfigBridge
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME_PERIODIC = "nightly_auto_tuner_periodic"
        const val WORK_NAME_IMMEDIATE = "nightly_auto_tuner_immediate"
        const val INPUT_RUN_REASON = "run_reason"

        private const val DEFAULT_REASON_PERIODIC = "periodic_nightly"
        private const val MIN_DELTA_J = 0.015f
        private const val IMMEDIATE_DEBOUNCE_MS = 10 * 60 * 1000L

        private val runMutex = Mutex()

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<NightlyAutoTuner>(
                24, TimeUnit.HOURS,
                2, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInitialDelay(calculateDelayUntil2AM(), TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder()
                        .putString(INPUT_RUN_REASON, DEFAULT_REASON_PERIODIC)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            TrainingTuneStateStore.markScheduler(context, true)
            Timber.i("Nightly auto-tuner periodic schedule enabled")
        }

        /**
         * Returns true when immediate work was enqueued, false when debounced.
         */
        fun enqueueImmediate(context: Context, reason: String): Boolean {
            val now = System.currentTimeMillis()
            val snapshot = TrainingTuneStateStore.read(context)
            if ((now - snapshot.lastImmediateEnqueueMs) < IMMEDIATE_DEBOUNCE_MS) {
                Timber.d("Skipping immediate auto-tune enqueue: debounce window active")
                return false
            }
            if ((now - snapshot.lastRunTimeMs) < IMMEDIATE_DEBOUNCE_MS) {
                Timber.d("Skipping immediate auto-tune enqueue: recent run already executed")
                return false
            }

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = OneTimeWorkRequestBuilder<NightlyAutoTuner>()
                .setConstraints(constraints)
                .setInputData(
                    Data.Builder()
                        .putString(INPUT_RUN_REASON, reason)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.MINUTES
                )
                .build()

            TrainingTuneStateStore.markImmediateEnqueue(context, now)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.KEEP,
                request
            )
            return true
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
            TrainingTuneStateStore.markScheduler(context, false)
            Timber.i("Nightly auto-tuner work cancelled")
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
        val runReason = inputData.getString(INPUT_RUN_REASON) ?: DEFAULT_REASON_PERIODIC

        return runMutex.withLock {
            try {
                val now = System.currentTimeMillis()
                val tuneState = TrainingTuneStateStore.read(applicationContext)
                if (tuneState.autoApplyBlockedUntilMs > now) {
                    TrainingTuneStateStore.markRunResult(
                        context = applicationContext,
                        reason = runReason,
                        outcome = TrainingTuneOutcome.SKIPPED,
                        message = "Skipped: auto-apply blocked after rollback",
                        samplesUsed = 0,
                        beforeJ = 0f,
                        afterJ = 0f,
                        trainedProfileActive = false,
                        syncedToWatch = false,
                        hasRollbackSnapshot = tuneState.hasRollbackSnapshot,
                        appliedNow = false
                    )
                    return@withLock Result.success()
                }

                if (!configBridge.isTrainingModeEnabled()) {
                    TrainingTuneStateStore.markRunResult(
                        context = applicationContext,
                        reason = runReason,
                        outcome = TrainingTuneOutcome.SKIPPED,
                        message = "Skipped: training mode is off",
                        samplesUsed = 0,
                        beforeJ = 0f,
                        afterJ = 0f,
                        trainedProfileActive = false,
                        syncedToWatch = false,
                        hasRollbackSnapshot = tuneState.hasRollbackSnapshot,
                        appliedNow = false
                    )
                    return@withLock Result.success()
                }

                val labels = labelDao.getUsableLabels()
                val minRequired = TrainingThresholds.MIN_USABLE_LABELS_FOR_PERSONALIZATION
                if (labels.size < minRequired) {
                    TrainingTuneStateStore.markRunResult(
                        context = applicationContext,
                        reason = runReason,
                        outcome = TrainingTuneOutcome.SKIPPED,
                        message = "Skipped: ${labels.size}/$minRequired usable labels",
                        samplesUsed = labels.size,
                        beforeJ = 0f,
                        afterJ = 0f,
                        trainedProfileActive = false,
                        syncedToWatch = false,
                        hasRollbackSnapshot = tuneState.hasRollbackSnapshot,
                        appliedNow = false
                    )
                    return@withLock Result.success()
                }

                val currentConfig = configBridge.getCurrentConfig()
                val optimizer = TrainingParameterOptimizer()
                val optimization = optimizer.optimize(currentConfig, labels)

                if (!optimization.applied) {
                    TrainingTuneStateStore.markRunResult(
                        context = applicationContext,
                        reason = runReason,
                        outcome = TrainingTuneOutcome.REJECTED,
                        message = optimization.reason,
                        samplesUsed = optimization.samplesUsed,
                        beforeJ = optimization.beforeJ,
                        afterJ = optimization.afterJ,
                        trainedProfileActive = false,
                        syncedToWatch = false,
                        hasRollbackSnapshot = tuneState.hasRollbackSnapshot,
                        appliedNow = false
                    )
                    return@withLock Result.success()
                }

                val deltaJ = optimization.afterJ - optimization.beforeJ
                if (deltaJ < MIN_DELTA_J) {
                    TrainingTuneStateStore.markRunResult(
                        context = applicationContext,
                        reason = runReason,
                        outcome = TrainingTuneOutcome.REJECTED,
                        message = "Rejected: delta J ${"%.3f".format(deltaJ)} < ${"%.3f".format(MIN_DELTA_J)}",
                        samplesUsed = optimization.samplesUsed,
                        beforeJ = optimization.beforeJ,
                        afterJ = optimization.afterJ,
                        trainedProfileActive = false,
                        syncedToWatch = false,
                        hasRollbackSnapshot = tuneState.hasRollbackSnapshot,
                        appliedNow = false
                    )
                    return@withLock Result.success()
                }

                val trainedConfig = buildTrainedProfile(optimization.optimizedConfig, optimization.samplesUsed)
                val applyResult = configBridge.applyConfig(trainedConfig)
                if (!applyResult.appliedLocally) {
                    TrainingTuneStateStore.markRunResult(
                        context = applicationContext,
                        reason = runReason,
                        outcome = TrainingTuneOutcome.APPLY_FAILED,
                        message = applyResult.detail,
                        samplesUsed = optimization.samplesUsed,
                        beforeJ = optimization.beforeJ,
                        afterJ = optimization.afterJ,
                        trainedProfileActive = false,
                        syncedToWatch = false,
                        hasRollbackSnapshot = applyResult.hasRollbackSnapshot,
                        appliedNow = false
                    )
                    return@withLock if (applyResult.retriableFailure) Result.retry() else Result.failure()
                }

                TrainingTuneStateStore.markRunResult(
                    context = applicationContext,
                    reason = runReason,
                    outcome = TrainingTuneOutcome.APPLIED,
                    message = applyResult.detail,
                    samplesUsed = optimization.samplesUsed,
                    beforeJ = optimization.beforeJ,
                    afterJ = optimization.afterJ,
                    trainedProfileActive = true,
                    syncedToWatch = applyResult.syncedToWatch,
                    hasRollbackSnapshot = applyResult.hasRollbackSnapshot,
                    appliedNow = true
                )

                Timber.i(
                    "Auto-tune applied: J %.3f -> %.3f, samples=%d, sync=%s",
                    optimization.beforeJ,
                    optimization.afterJ,
                    optimization.samplesUsed,
                    applyResult.syncedToWatch
                )
                Result.success()
            } catch (e: Exception) {
                Timber.e(e, "NightlyAutoTuner failed")
                TrainingTuneStateStore.markRunResult(
                    context = applicationContext,
                    reason = runReason,
                    outcome = TrainingTuneOutcome.APPLY_FAILED,
                    message = "Exception: ${e.message}",
                    samplesUsed = 0,
                    beforeJ = 0f,
                    afterJ = 0f,
                    trainedProfileActive = false,
                    syncedToWatch = false,
                    hasRollbackSnapshot = TrainingTuneStateStore.read(applicationContext).hasRollbackSnapshot,
                    appliedNow = false
                )
                if (isTransient(e)) Result.retry() else Result.failure()
            }
        }
    }

    private fun buildTrainedProfile(base: TremorDetectionConfig, samplesUsed: Int): TremorDetectionConfig {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        return base.copy(
            profileName = "Trained",
            profileDescription = "Auto-tuned from $samplesUsed labels on $date"
        )
    }

    private fun isTransient(e: Exception): Boolean {
        return e is IOException || e is SQLiteException
    }
}
