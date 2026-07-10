package com.ripple.town.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ripple.town.core.model.SimSpeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "ripple_settings")

data class Settings(
    val onboarded: Boolean = false,
    val townName: String = "Ashcombe",
    val worldSeed: Long = 0L,
    val speed: SimSpeed = SimSpeed.NORMAL,
    val notificationsEnabled: Boolean = true,
    val freeNudgeUsed: Boolean = false,
    /**
     * The opt-in for real system (push) notifications — deliberately separate from
     * [notificationsEnabled], which only gates the existing in-app alert banners
     * (see [com.ripple.town.data.WorldRepository.notifyIfRelevant]). Defaults to
     * false: a system notification requires an explicit, deliberate opt-in, not a
     * pre-ticked box. Turning this on is what triggers the POST_NOTIFICATIONS
     * runtime-permission prompt from the settings toggle handler.
     */
    val pushNotificationsEnabled: Boolean = false,
    /** Highest [com.ripple.town.core.database.WorldEventEntity.id] already notified about. */
    val lastNotifiedEventId: Long = 0L
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val TOWN_NAME = stringPreferencesKey("town_name")
        val WORLD_SEED = longPreferencesKey("world_seed")
        val SPEED = stringPreferencesKey("speed")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val FREE_NUDGE_USED = booleanPreferencesKey("free_nudge_used")
        val PUSH_NOTIFICATIONS = booleanPreferencesKey("push_notifications_enabled")
        val LAST_NOTIFIED_EVENT_ID = longPreferencesKey("last_notified_event_id")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            onboarded = p[Keys.ONBOARDED] ?: false,
            townName = p[Keys.TOWN_NAME] ?: "Ashcombe",
            worldSeed = p[Keys.WORLD_SEED] ?: 0L,
            speed = runCatching { SimSpeed.valueOf(p[Keys.SPEED] ?: "NORMAL") }.getOrDefault(SimSpeed.NORMAL),
            notificationsEnabled = p[Keys.NOTIFICATIONS] ?: true,
            freeNudgeUsed = p[Keys.FREE_NUDGE_USED] ?: false,
            pushNotificationsEnabled = p[Keys.PUSH_NOTIFICATIONS] ?: false,
            lastNotifiedEventId = p[Keys.LAST_NOTIFIED_EVENT_ID] ?: 0L
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setOnboarded(townName: String, seed: Long, speed: SimSpeed) {
        context.dataStore.edit {
            it[Keys.ONBOARDED] = true
            it[Keys.TOWN_NAME] = townName
            it[Keys.WORLD_SEED] = seed
            it[Keys.SPEED] = speed.name
        }
    }

    suspend fun setSpeed(speed: SimSpeed) {
        context.dataStore.edit { it[Keys.SPEED] = speed.name }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setFreeNudgeUsed() {
        context.dataStore.edit { it[Keys.FREE_NUDGE_USED] = true }
    }

    /**
     * Sets the user's opt-in for real system notifications. This only records the
     * preference — it does not itself request the runtime permission or touch
     * WorkManager; the caller (the settings toggle handler) is responsible for
     * driving the [android.Manifest.permission.POST_NOTIFICATIONS] launcher and
     * enqueue/cancel of [com.ripple.town.work.NotificationCheckWorker] around this call.
     */
    suspend fun setPushNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PUSH_NOTIFICATIONS] = enabled }
    }

    suspend fun setLastNotifiedEventId(id: Long) {
        context.dataStore.edit { current ->
            // Never move the cursor backwards (e.g. a slower concurrent caller finishing last).
            val existing = current[Keys.LAST_NOTIFIED_EVENT_ID] ?: 0L
            if (id > existing) current[Keys.LAST_NOTIFIED_EVENT_ID] = id
        }
    }
}
