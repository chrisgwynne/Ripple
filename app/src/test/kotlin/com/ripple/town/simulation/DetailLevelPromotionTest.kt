package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.Gender
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.LifecycleSystem
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import org.junit.Test

/**
 * Tests for DetailLevel promotion: a BACKGROUND resident discovered during play
 * should become DETAILED, be added to discoveredResidentIds, and get a home if homeless.
 */
class DetailLevelPromotionTest {

    private fun emptyState(): WorldState = WorldState(
        seed = 7L,
        townName = "Promotionville",
        createdAtRealMs = 0L,
        map = TownMap(5, 5, List(25) { TileType.GRASS })
    ).also { it.time = SimTime.MINUTES_PER_YEAR * 5 }

    private fun ctx(state: WorldState): TickContext =
        TickContext(state, SimRandom(state.seed, SimTime.tickOf(state.time), 0L), InMemoryEventIndex())

    private fun addBackground(state: WorldState, id: Long): Resident {
        val r = Resident(
            id = id,
            firstName = "Person$id",
            surname = "Background",
            gender = Gender.NONBINARY,
            bornAt = state.time - 25 * SimTime.MINUTES_PER_YEAR,
            homeBuildingId = null,
            householdId = null,
            detailLevel = DetailLevel.BACKGROUND
        )
        state.residents[id] = r
        return r
    }

    private fun addHome(state: WorldState, id: Long): Building {
        val b = Building(
            id = id, name = "Home $id", type = BuildingType.HOUSE,
            origin = Tile(0, 0), width = 1, height = 1, door = Tile(0, 0),
            buildingState = BuildingState.OCCUPIED
        )
        state.buildings[id] = b
        return b
    }

    // --------------------------------------------------------- tests

    @Test fun `BACKGROUND resident is promoted to DETAILED`() {
        val state = emptyState()
        val r = addBackground(state, 1L)

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "interacted with player")

        assertThat(r.detailLevel).isEqualTo(DetailLevel.DETAILED)
    }

    @Test fun `promoted resident is added to discoveredResidentIds`() {
        val state = emptyState()
        val r = addBackground(state, 2L)
        assertThat(state.discoveredResidentIds).doesNotContain(2L)

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "test discovery")

        assertThat(state.discoveredResidentIds).contains(2L)
    }

    @Test fun `already-DETAILED resident is unchanged by promoteIfNeeded`() {
        val state = emptyState()
        val r = addBackground(state, 3L).also { it.detailLevel = DetailLevel.DETAILED }
        val discoveredBefore = state.discoveredResidentIds.toList()

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "should be no-op")

        assertThat(r.detailLevel).isEqualTo(DetailLevel.DETAILED)
        // discoveredResidentIds should not change for an already-detailed resident
        assertThat(state.discoveredResidentIds).containsExactlyElementsIn(discoveredBefore)
    }

    @Test fun `homeless resident gets a home assigned on promotion`() {
        val state = emptyState()
        addHome(state, 100L)
        val r = addBackground(state, 4L)
        assertThat(r.homeBuildingId).isNull()

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "moved in")

        assertThat(r.homeBuildingId).isNotNull()
        assertThat(r.homeBuildingId).isEqualTo(100L)
        assertThat(r.householdId).isNotNull()
        assertThat(r.currentBuildingId).isEqualTo(100L)
    }

    @Test fun `resident who already has a home keeps it on promotion`() {
        val state = emptyState()
        addHome(state, 200L)
        val r = addBackground(state, 5L).also {
            it.homeBuildingId = 200L
            it.currentBuildingId = 200L
        }

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "has home already")

        assertThat(r.homeBuildingId).isEqualTo(200L)
    }

    @Test fun `promotion is idempotent — calling twice does not double-add to discoveredResidentIds`() {
        val state = emptyState()
        val r = addBackground(state, 6L)

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "first call")
        LifecycleSystem.promoteIfNeeded(ctx(state), r, "second call — no-op")

        assertThat(state.discoveredResidentIds.count { it == 6L }).isEqualTo(1)
    }
}
