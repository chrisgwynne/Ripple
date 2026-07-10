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
    val freeNudgeUsed: Boolean = false
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
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            onboarded = p[Keys.ONBOARDED] ?: false,
            townName = p[Keys.TOWN_NAME] ?: "Ashcombe",
            worldSeed = p[Keys.WORLD_SEED] ?: 0L,
            speed = runCatching { SimSpeed.valueOf(p[Keys.SPEED] ?: "NORMAL") }.getOrDefault(SimSpeed.NORMAL),
            notificationsEnabled = p[Keys.NOTIFICATIONS] ?: true,
            freeNudgeUsed = p[Keys.FREE_NUDGE_USED] ?: false
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
}
