package com.ripple.town.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ripple.town.notifications.FollowedResidentNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Delivery mechanism (b) from the backlog item: a periodic, bounded "catch-up
 * summary" style check, independent of the app being open. Genuinely no continuous
 * background work — WorkManager's own minimum interval (15 minutes) is used as-is,
 * no attempt to run more often, no wake locks, no foreground service.
 *
 * **Design choice — DB-only, not the full simulation.** This deliberately does NOT
 * run [com.ripple.town.core.simulation.SimulationCoordinator]/
 * [com.ripple.town.data.WorldRepository]'s offline catch-up from this Worker. Reasons:
 * 1. `WorldRepository`'s engine (`coordinator`) is a singleton confined to its own
 *    single-threaded dispatcher and is only ever created via `restoreIfPresent()`/
 *    `createWorld()`; a background Worker process is a separate execution context
 *    (Hilt's `SingletonComponent` is process-scoped, so a periodic Worker running
 *    while the app's `Application` is alive *could* in principle reach the same
 *    instance — but WorkManager offers no guarantee the app process is warm when
 *    the Worker fires; on a cold background run it would need to reconstruct the
 *    whole restore/catch-up/checkpoint lifecycle itself, duplicating
 *    `WorldRepository.restoreIfPresent`/`runCatchUp`/`saveCheckpoint`).
 * 2. Running the full catch-up simulation on a background schedule the user isn't
 *    watching would also silently advance game time without the bounded,
 *    UI-visible `CatchUpProgress` flow `TownScreen` already shows on real app open
 *    — i.e. it would change *when* offline time gets consumed, a bigger behavioural
 *    change than "send a notification" should require.
 * 3. The actual goal — "did anything notable happen to someone I follow" — doesn't
 *    need a fresh simulation tick at all. Everything required is already sitting in
 *    the `world_events` table from the last time the app *was* open (ticks only run
 *    in [com.ripple.town.data.WorldRepository.startRunnerIfNeeded], which only runs
 *    in the foreground) or from the last offline catch-up that already ran on the
 *    most recent app open. So this Worker reuses exactly the same
 *    [FollowedResidentNotifier.checkAndNotify] the app-open path uses — a plain,
 *    cheap Room read plus DataStore, no engine involvement, safe to run cold.
 *
 * Net effect: if the app hasn't been opened in a while, this periodic check won't
 * surface anything *new* beyond what was already simulated as of the last open —
 * it's a delivery mechanism for already-known notable events, not a way to keep the
 * town moving in the background. That's an honest, explicit scope-down consistent
 * with "still no continuous background work."
 */
@HiltWorker
class NotificationCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notifier: FollowedResidentNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            notifier.checkAndNotify()
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "ripple-notification-check"
        private val MIN_INTERVAL = 15L to TimeUnit.MINUTES

        /** Enqueues the periodic check. Call only while the user has opted in. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationCheckWorker>(
                MIN_INTERVAL.first, MIN_INTERVAL.second
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Cancels the periodic check. Call when the user opts out. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
