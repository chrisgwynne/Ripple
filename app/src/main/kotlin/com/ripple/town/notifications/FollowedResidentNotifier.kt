package com.ripple.town.notifications

import android.content.Context
import com.ripple.town.core.database.RippleDatabase
import com.ripple.town.core.database.WorldEventEntity
import com.ripple.town.core.simulation.ImportanceScorer
import com.ripple.town.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared "what's worth a system notification right now" check, used by both
 * delivery mechanisms described in the backlog item:
 *  (a) a check-and-notify pass on app open/resume ([com.ripple.town.MainViewModel]), and
 *  (b) [com.ripple.town.work.NotificationCheckWorker]'s periodic WorkManager pass.
 *
 * Deliberately DB-only — it never touches [com.ripple.town.core.simulation.SimulationCoordinator]
 * or the live in-memory engine. Two reasons: the engine is confined to
 * [com.ripple.town.data.WorldRepository]'s single-threaded dispatcher and is only ever
 * constructed there (see `restoreIfPresent`/`createWorld`), and running it from a background
 * Worker would mean either duplicating that restore/checkpoint lifecycle in a second place or
 * reaching into the repository's private state from a process that may be a fresh cold start —
 * both riskier than this task's actual goal, which is just "tell me if something notable
 * happened to someone I follow." The already-persisted `world_events` table (written every tick
 * by `WorldRepository.persistTickResult`) has everything needed for that: the notability bar
 * ([ImportanceScorer.HISTORY_THRESHOLD], same one History/era-summary/Follow-moments use) and
 * who was involved. No offline catch-up is triggered here — that remains
 * [com.ripple.town.data.WorldRepository.restoreIfPresent]'s job, unchanged, run
 * separately (and earlier) on app open.
 */
@Singleton
class FollowedResidentNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: RippleDatabase,
    private val settingsRepository: SettingsRepository
) {

    /**
     * Looks for notable events involving followed/favourite residents since the last
     * notified event id, posts up to [MAX_NOTIFICATIONS_PER_CHECK] notifications, and
     * advances the cursor past everything it looked at (whether or not it notified about
     * each one) so a quiet check doesn't re-scan the same old events forever.
     *
     * Safe to call from any context with database + DataStore access — no engine dispatcher
     * involved. No-ops early and cheaply if the user hasn't opted in or the OS permission
     * isn't granted, so calling it unconditionally on every app open is fine.
     */
    suspend fun checkAndNotify() {
        val settings = settingsRepository.current()
        if (!settings.pushNotificationsEnabled) return
        if (!NotificationHelper.canPostNotifications(context)) return

        val followed = db.followDao().allOnce().map { it.residentId }.toSet()
        if (followed.isEmpty()) {
            // Nothing to watch, but still advance the cursor so a later follow doesn't
            // suddenly surface a backlog of old events.
            advanceCursorToLatest()
            return
        }

        val candidates = db.eventDao().notableEventsSince(
            minImportance = ImportanceScorer.HISTORY_THRESHOLD,
            sinceId = settings.lastNotifiedEventId,
            limit = CANDIDATE_SCAN_LIMIT
        )
        if (candidates.isEmpty()) return

        var posted = 0
        var highestSeenId = settings.lastNotifiedEventId
        for (event in candidates) {
            highestSeenId = maxOf(highestSeenId, event.id)
            if (posted >= MAX_NOTIFICATIONS_PER_CHECK) continue
            if (!involvesFollowed(event, followed)) continue
            NotificationHelper.notify(
                context = context,
                notificationId = event.id.toInt(),
                title = "Ripple",
                text = event.description
            )
            posted++
        }
        settingsRepository.setLastNotifiedEventId(highestSeenId)
    }

    private suspend fun advanceCursorToLatest() {
        val latest = db.eventDao().notableEventsSince(
            minImportance = ImportanceScorer.HISTORY_THRESHOLD,
            sinceId = settingsRepository.current().lastNotifiedEventId,
            limit = CANDIDATE_SCAN_LIMIT
        )
        val maxId = latest.maxOfOrNull { it.id } ?: return
        settingsRepository.setLastNotifiedEventId(maxId)
    }

    private fun involvesFollowed(event: WorldEventEntity, followed: Set<Long>): Boolean {
        if (event.sourceResidentId != null && event.sourceResidentId in followed) return true
        return event.targetResidentIdsCsv.split(',')
            .filter { it.isNotBlank() }
            .any { it.toLongOrNull() in followed }
    }

    companion object {
        /** Caps a notification storm from one check, per the backlog item's explicit bound. */
        const val MAX_NOTIFICATIONS_PER_CHECK = 3

        /** Bounds a single scan pass regardless of how long the app was closed. */
        private const val CANDIDATE_SCAN_LIMIT = 200
    }
}
