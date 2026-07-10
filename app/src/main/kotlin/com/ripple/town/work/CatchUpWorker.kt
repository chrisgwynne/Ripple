package com.ripple.town.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ripple.town.data.WorldRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Bounded offline catch-up via WorkManager. There is no continuous background
 * simulation: this runs once when enqueued (on app open), restores the world
 * and lets the repository perform its capped catch-up, then checkpoints.
 */
@HiltWorker
class CatchUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val worldRepository: WorldRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // restoreIfPresent performs the capped catch-up internally.
            worldRepository.restoreIfPresent()
            worldRepository.saveCheckpoint()
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "ripple-catch-up"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CatchUpWorker>().build()
            )
        }
    }
}
