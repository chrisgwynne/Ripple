package com.ripple.town

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.data.SettingsRepository
import com.ripple.town.data.WorldRepository
import com.ripple.town.notifications.FollowedResidentNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AppState {
    data object Loading : AppState()
    data object NeedsOnboarding : AppState()
    data object Ready : AppState()
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val worldRepository: WorldRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: FollowedResidentNotifier
) : ViewModel() {

    private val _appState = MutableStateFlow<AppState>(AppState.Loading)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    /** The one free demo nudge offered right after the town is born. */
    val showIntro: StateFlow<Boolean> = combine(
        settingsRepository.settings,
        worldRepository.worldUi,
        _appState
    ) { settings, world, state ->
        state == AppState.Ready && world != null && !settings.freeNudgeUsed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            val restored = worldRepository.restoreIfPresent()
            _appState.value = if (restored) AppState.Ready else AppState.NeedsOnboarding
            // Delivery mechanism (a) from the backlog item: a check-and-notify pass on
            // app open, after the world (and any offline catch-up) has finished
            // restoring so this sees events from time that just got caught up on, not
            // just the pre-close state. No-ops cheaply if the user hasn't opted in.
            if (restored) notifier.checkAndNotify()
        }
    }

    fun onOnboardingFinished() {
        _appState.value = AppState.Ready
    }

    /** Demo intervention: delay the followed resident by a few minutes. Free. */
    fun introDelayChoice() {
        viewModelScope.launch {
            val followedId = worldRepository.worldUi.value?.followedResidentId
            worldRepository.applyIntervention(
                InterventionVerb.DELAY, followedId, free = true
            )
            settingsRepository.setFreeNudgeUsed()
        }
    }

    fun introDoNothing() {
        viewModelScope.launch { settingsRepository.setFreeNudgeUsed() }
    }

    fun setForeground(fg: Boolean) = worldRepository.setForeground(fg)
}
