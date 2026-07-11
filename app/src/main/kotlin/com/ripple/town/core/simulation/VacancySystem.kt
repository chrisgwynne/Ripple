package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.isHome

/**
 * Tracks buildings that have lost their occupants and advances them through the
 * decay ladder: OCCUPIED → VACANT → DERELICT → CONDEMNED.
 *
 * Residential buildings become vacant when no household has a home there.
 * Commercial buildings become vacant when the associated business has closed.
 * Civic and park buildings are excluded — they don't decay this way.
 *
 * A building that regains occupants (new household or new business) is immediately
 * reset to OCCUPIED and its [Building.vacantSinceAt] is cleared.
 */
object VacancySystem {

    /** Days vacant before a building becomes DERELICT. */
    const val DAYS_TO_DERELICT = 60L
    /** Days vacant before a DERELICT building becomes CONDEMNED. */
    const val DAYS_TO_CONDEMNED = 120L

    private val CIVIC_TYPES = setOf(
        BuildingType.FIRE_STATION, BuildingType.POLICE_STATION,
        BuildingType.SPORTS_HALL, BuildingType.COMMUNITY_CENTRE,
        BuildingType.SCHOOL, BuildingType.CLINIC, BuildingType.TOWN_HALL,
        BuildingType.PARK, BuildingType.CEMETERY
    )

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        for (building in state.buildings.values) {
            if (building.buildingState == BuildingState.DEMOLISHED) continue
            if (building.buildingState == BuildingState.PLANNED ||
                building.buildingState == BuildingState.UNDER_CONSTRUCTION) continue
            if (building.type in CIVIC_TYPES) continue

            val isOccupied = if (building.type.isHome) {
                state.households.values.any { hh ->
                    hh.homeBuildingId == building.id && hh.memberIds.isNotEmpty()
                }
            } else {
                val biz = state.businesses.values.firstOrNull { it.buildingId == building.id }
                biz != null && biz.open
            }

            if (isOccupied) {
                if (building.buildingState != BuildingState.OCCUPIED) {
                    building.buildingState = BuildingState.OCCUPIED
                    building.vacantSinceAt = null
                    building.abandoned = false
                }
                continue
            }

            // Building has no occupant.
            if (building.buildingState == BuildingState.OCCUPIED) {
                building.buildingState = BuildingState.VACANT
                building.vacantSinceAt = ctx.now
                building.abandoned = true
                ctx.emit(
                    EventType.BUILDING_VACANT,
                    "${building.name} has stood empty.",
                    buildingId = building.id, severity = 0.25, visibility = EventVisibility.PUBLIC
                )
                continue
            }

            val vacantSince = building.vacantSinceAt ?: continue
            val daysVacant = (ctx.now - vacantSince) / SimTime.MINUTES_PER_DAY

            if (building.buildingState == BuildingState.VACANT && daysVacant >= DAYS_TO_DERELICT) {
                building.buildingState = BuildingState.DERELICT
                building.condition = (building.condition - 15.0).coerceAtLeast(0.0)
                ctx.emit(
                    EventType.BUILDING_DERELICT,
                    "${building.name} has fallen into disrepair.",
                    buildingId = building.id, severity = 0.4, visibility = EventVisibility.PUBLIC
                )
            } else if (building.buildingState == BuildingState.DERELICT &&
                daysVacant >= DAYS_TO_DERELICT + DAYS_TO_CONDEMNED) {
                building.buildingState = BuildingState.CONDEMNED
                building.condition = (building.condition - 25.0).coerceAtLeast(0.0)
                building.value = (building.value * 0.3).coerceAtLeast(0.0)
                ctx.emit(
                    EventType.BUILDING_CONDEMNED,
                    "${building.name} has been condemned as structurally unsafe.",
                    buildingId = building.id, severity = 0.55, visibility = EventVisibility.PUBLIC
                )
            }
        }
    }
}
