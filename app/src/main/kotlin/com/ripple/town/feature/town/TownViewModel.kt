package com.ripple.town.feature.town

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.core.model.SimSpeed
import com.ripple.town.core.simulation.InterventionResult
import com.ripple.town.data.CatchUpProgress
import com.ripple.town.data.DeathSummary
import com.ripple.town.data.EventUi
import com.ripple.town.data.WorldRepository
import com.ripple.town.data.WorldUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What is currently open above the town. */
sealed class TownSheet {
    data class ResidentSheet(val residentId: Long) : TownSheet()
    data class BuildingSheet(val buildingId: Long) : TownSheet()
    data class EventSheet(val eventId: Long) : TownSheet()
    data class InterventionSheet(val residentId: Long) : TownSheet()
    object TownOverviewSheet : TownSheet()
}

@HiltViewModel
class TownViewModel @Inject constructor(
    private val repository: WorldRepository
) : ViewModel() {

    val world: StateFlow<WorldUi?> = repository.worldUi
    val speed: StateFlow<SimSpeed> = repository.speed
    val catchUp: StateFlow<CatchUpProgress?> = repository.catchUpProgress
    val followedDeath: StateFlow<DeathSummary?> = repository.followedDeath
    val alerts = repository.alerts

    val recentEvents: StateFlow<List<EventUi>> = repository.latestEvents(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sheet = MutableStateFlow<TownSheet?>(null)
    val sheet: StateFlow<TownSheet?> = _sheet.asStateFlow()

    private val _residentEvents = MutableStateFlow<List<EventUi>>(emptyList())
    val residentEvents: StateFlow<List<EventUi>> = _residentEvents.asStateFlow()

    private val _buildingEvents = MutableStateFlow<List<EventUi>>(emptyList())
    val buildingEvents: StateFlow<List<EventUi>> = _buildingEvents.asStateFlow()

    private val _causeChain = MutableStateFlow<List<List<EventUi>>>(emptyList())
    val causeChain: StateFlow<List<List<EventUi>>> = _causeChain.asStateFlow()

    private val _interventionMessage = MutableStateFlow<String?>(null)
    val interventionMessage: StateFlow<String?> = _interventionMessage.asStateFlow()

    /** Camera jump requests (tile coords), consumed by the screen. */
    private val _jumpTo = MutableStateFlow<Pair<Float, Float>?>(null)
    val jumpTo: StateFlow<Pair<Float, Float>?> = _jumpTo.asStateFlow()

    fun onTap(tap: TownTap) {
        when (tap) {
            is TownTap.OnResident -> openResident(tap.id)
            is TownTap.OnBuilding -> _sheet.value = TownSheet.BuildingSheet(tap.id).also { loadBuildingEvents(tap.id) }
            TownTap.OnGround -> _sheet.value = null
        }
    }

    fun openResident(id: Long) {
        _sheet.value = TownSheet.ResidentSheet(id)
        viewModelScope.launch { _residentEvents.value = repository.eventsForResident(id) }
    }

    fun openBuilding(id: Long) {
        _sheet.value = TownSheet.BuildingSheet(id)
        loadBuildingEvents(id)
    }

    private fun loadBuildingEvents(id: Long) {
        viewModelScope.launch { _buildingEvents.value = repository.eventsForBuilding(id) }
    }

    fun openEvent(eventId: Long) {
        _sheet.value = TownSheet.EventSheet(eventId)
        viewModelScope.launch { _causeChain.value = repository.causeChain(eventId) }
    }

    fun openIntervention(residentId: Long) {
        _sheet.value = TownSheet.InterventionSheet(residentId)
        _interventionMessage.value = null
    }

    fun openTownOverview() {
        _sheet.value = TownSheet.TownOverviewSheet
    }

    fun closeSheet() {
        _sheet.value = null
        _interventionMessage.value = null
    }

    fun setSpeed(speed: SimSpeed) {
        viewModelScope.launch { repository.setSpeed(speed) }
    }

    fun follow(residentId: Long) {
        viewModelScope.launch {
            repository.setPrimaryFollow(residentId)
            jumpToResident(residentId)
        }
    }

    fun toggleFavourite(residentId: Long) {
        viewModelScope.launch { repository.toggleFavourite(residentId) }
    }

    fun jumpToResident(residentId: Long) {
        val r = world.value?.resident(residentId) ?: return
        if (r.visibleOnMap) _jumpTo.value = r.x to r.y
    }

    fun consumeJump() { _jumpTo.value = null }

    fun applyIntervention(
        verb: InterventionVerb,
        targetResidentId: Long?,
        secondaryResidentId: Long? = null,
        free: Boolean = false
    ) {
        viewModelScope.launch {
            when (val result = repository.applyIntervention(verb, targetResidentId, secondaryResidentId, free = free)) {
                is InterventionResult.Applied -> {
                    _interventionMessage.value = result.flavour
                }
                is InterventionResult.Rejected -> _interventionMessage.value = result.reason
            }
        }
    }

    fun dismissCatchUp() = repository.dismissCatchUp()
    fun dismissFollowedDeath() = repository.dismissFollowedDeath()
}
