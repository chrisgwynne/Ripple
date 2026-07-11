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
import com.ripple.town.core.simulation.ActivationSystem
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.LifecycleSystem
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import org.junit.Test

/**
 * Tests for the two-step DetailLevel promotion ladder:
 *   BACKGROUND → CONNECTED (first call to promoteIfNeeded)
 *   CONNECTED   → DETAILED  (second call — assigns home + adds to discoveredResidentIds)
 *
 * promoteIfNeeded() is deliberately single-step so ActivationSystem can rate-gate
 * CONNECTED→DETAILED.  Callers that need a resident fully DETAILED immediately
 * (e.g. a new business owner) call it twice.
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

    // --------------------------------------------------------- ladder tests

    @Test fun `first promoteIfNeeded call advances BACKGROUND to CONNECTED`() {
        val state = emptyState()
        val r = addBackground(state, 1L)

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "interacted with player")

        assertThat(r.detailLevel).isEqualTo(DetailLevel.CONNECTED)
    }

    @Test fun `second promoteIfNeeded call advances CONNECTED to DETAILED`() {
        val state = emptyState()
        val r = addBackground(state, 2L)

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 1")
        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 2")

        assertThat(r.detailLevel).isEqualTo(DetailLevel.DETAILED)
    }

    @Test fun `CONNECTED step does not add to discoveredResidentIds`() {
        val state = emptyState()
        val r = addBackground(state, 3L)

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 1")

        assertThat(state.discoveredResidentIds).doesNotContain(3L)
        assertThat(r.detailLevel).isEqualTo(DetailLevel.CONNECTED)
    }

    @Test fun `DETAILED step adds to discoveredResidentIds`() {
        val state = emptyState()
        val r = addBackground(state, 3L)

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 1")
        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 2")

        assertThat(state.discoveredResidentIds).contains(3L)
    }

    @Test fun `already-DETAILED resident is unchanged by promoteIfNeeded`() {
        val state = emptyState()
        val r = addBackground(state, 4L).also {
            it.detailLevel = DetailLevel.DETAILED
            state.discoveredResidentIds += it.id
        }
        val discoveredBefore = state.discoveredResidentIds.toList()

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "should be no-op")

        assertThat(r.detailLevel).isEqualTo(DetailLevel.DETAILED)
        assertThat(state.discoveredResidentIds).containsExactlyElementsIn(discoveredBefore)
    }

    @Test fun `home is assigned on DETAILED promotion, not CONNECTED`() {
        val state = emptyState()
        addHome(state, 100L)
        val r = addBackground(state, 5L)
        assertThat(r.homeBuildingId).isNull()

        // First call (CONNECTED): no home assigned yet.
        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 1")
        assertThat(r.detailLevel).isEqualTo(DetailLevel.CONNECTED)
        assertThat(r.homeBuildingId).isNull()

        // Second call (DETAILED): home assigned here.
        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 2")
        assertThat(r.detailLevel).isEqualTo(DetailLevel.DETAILED)
        assertThat(r.homeBuildingId).isEqualTo(100L)
        assertThat(r.householdId).isNotNull()
        assertThat(r.currentBuildingId).isEqualTo(100L)
    }

    @Test fun `resident who already has a home keeps it on DETAILED promotion`() {
        val state = emptyState()
        addHome(state, 200L)
        val r = addBackground(state, 6L).also {
            it.homeBuildingId = 200L
            it.currentBuildingId = 200L
            it.detailLevel = DetailLevel.CONNECTED
        }

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "already has home")

        assertThat(r.homeBuildingId).isEqualTo(200L)
        assertThat(r.detailLevel).isEqualTo(DetailLevel.DETAILED)
    }

    @Test fun `discoveredResidentIds has no duplicate entries after repeated calls`() {
        val state = emptyState()
        val r = addBackground(state, 7L)

        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 1")
        LifecycleSystem.promoteIfNeeded(ctx(state), r, "step 2")
        LifecycleSystem.promoteIfNeeded(ctx(state), r, "no-op third call")

        assertThat(state.discoveredResidentIds.count { it == 7L }).isEqualTo(1)
    }

    // --------------------------------------------------------- ActivationSystem caps

    @Test fun `ActivationSystem does not exceed DETAIL_CAP`() {
        val state = emptyState()
        addHome(state, 999L)
        // Pre-fill DETAIL_CAP DETAILED residents.
        repeat(ActivationSystem.DETAIL_CAP) { i ->
            val r = Resident(
                id = (i + 100).toLong(), firstName = "D$i", surname = "Cap",
                gender = Gender.NONBINARY,
                bornAt = state.time - 25 * SimTime.MINUTES_PER_YEAR,
                homeBuildingId = null, householdId = null,
                detailLevel = DetailLevel.DETAILED
            )
            state.residents[r.id] = r
        }
        // Add a CONNECTED candidate that is the followed resident (maximises its score).
        val candidate = Resident(
            id = 9999L, firstName = "Hopeful", surname = "Cap",
            gender = Gender.NONBINARY,
            bornAt = state.time - 25 * SimTime.MINUTES_PER_YEAR,
            homeBuildingId = null, householdId = null,
            detailLevel = DetailLevel.CONNECTED
        )
        state.residents[candidate.id] = candidate
        state.followedResidentId = candidate.id

        ActivationSystem.updateTick(ctx(state))

        // Cap enforced — candidate must stay CONNECTED, not be promoted to DETAILED.
        assertThat(candidate.detailLevel).isEqualTo(DetailLevel.CONNECTED)
        val detailedCount = state.residents.values.count { it.detailLevel == DetailLevel.DETAILED }
        assertThat(detailedCount).isAtMost(ActivationSystem.DETAIL_CAP)
    }
}
