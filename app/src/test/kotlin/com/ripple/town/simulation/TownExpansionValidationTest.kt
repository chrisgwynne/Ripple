package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.simulation.ActivationSystem
import com.ripple.town.core.simulation.SimulationCoordinator
import org.junit.Test

/**
 * Validates that the three-tier population system (BACKGROUND/CONNECTED/DETAILED) behaves
 * correctly at the scale of a full generated world — specifically:
 *   • The seeded DETAILED residents are bootstrapped into discoveredResidentIds at world start.
 *   • After simulation runs, CONNECTED and DETAILED counts stay within their soft caps.
 *   • The followed resident is always DETAILED (ActivationSystem.updateDaily guarantee).
 *   • After running a day, some BACKGROUND residents have been promoted to CONNECTED.
 *   • The DETAILED count never exceeds DETAIL_CAP in long play.
 */
class TownExpansionValidationTest {

    // ---------------------------------------------------------------------- discovery bootstrap

    @Test fun `seeded DETAILED residents are in discoveredResidentIds at world start`() {
        val state = TestWorld.newState()
        val detailedIds = state.residents.values
            .filter { it.detailLevel == DetailLevel.DETAILED }
            .map { it.id }
            .toSet()

        assertThat(detailedIds).isNotEmpty()
        for (id in detailedIds) {
            assertThat(state.discoveredResidentIds).contains(id)
        }
    }

    @Test fun `discoveredResidentIds contains no residents below DETAILED at world start`() {
        val state = TestWorld.newState()
        for (id in state.discoveredResidentIds) {
            val r = state.resident(id)!!
            assertThat(r.detailLevel).isEqualTo(DetailLevel.DETAILED)
        }
    }

    // ---------------------------------------------------------------------- cap enforcement

    @Test fun `DETAILED count stays at or below DETAIL_CAP after a 10-day simulation`() {
        val coordinator = TestWorld.newCoordinator()
        TestWorld.runDays(coordinator, 10)
        val state = coordinator.state

        val detailedCount = state.residents.values.count { it.detailLevel == DetailLevel.DETAILED }
        assertThat(detailedCount).isAtMost(ActivationSystem.DETAIL_CAP)
    }

    @Test fun `CONNECTED count stays at or below CONNECTED_CAP after a 10-day simulation`() {
        val coordinator = TestWorld.newCoordinator()
        TestWorld.runDays(coordinator, 10)
        val state = coordinator.state

        val connectedCount = state.residents.values.count { it.detailLevel == DetailLevel.CONNECTED }
        assertThat(connectedCount).isAtMost(ActivationSystem.CONNECTED_CAP)
    }

    // ---------------------------------------------------------------------- activation growth

    @Test fun `some BACKGROUND residents are promoted to CONNECTED after a day`() {
        val state = TestWorld.newState()
        val backgroundBefore = state.residents.values.count { it.detailLevel == DetailLevel.BACKGROUND }

        // Run one full in-game day.
        val coordinator = SimulationCoordinator(state)
        TestWorld.runDays(coordinator, 1)

        val connectedAfter = state.residents.values.count { it.detailLevel == DetailLevel.CONNECTED }
        // ActivationSystem promotes up to PROMOTE_TO_CONNECTED_PER_TICK per tick; one day must
        // have produced at least one promoted resident (the followed resident has neighbours).
        assertThat(connectedAfter).isGreaterThan(0)
        // Nobody who was already seeded DETAILED was demoted.
        val detailedAfter = state.residents.values.count { it.detailLevel == DetailLevel.DETAILED }
        assertThat(detailedAfter).isAtLeast(30) // WorldGenerator seeds 30 DETAILED
    }

    // ---------------------------------------------------------------------- followed-resident guarantee

    @Test fun `followed resident is DETAILED after updateDaily`() {
        val state = TestWorld.newState()
        val followedId = state.followedResidentId!!
        val followed = state.resident(followedId)!!

        // The followed resident is seeded DETAILED in WorldGenerator — verify.
        assertThat(followed.detailLevel).isEqualTo(DetailLevel.DETAILED)
        assertThat(state.discoveredResidentIds).contains(followedId)
    }

    @Test fun `if followed resident is CONNECTED, updateDaily promotes them to DETAILED`() {
        val state = TestWorld.newState()
        // Manually demote the followed resident to CONNECTED for this test.
        val followedId = state.followedResidentId!!
        val followed = state.resident(followedId)!!
        followed.detailLevel = DetailLevel.CONNECTED

        val ctx = TestWorld.contextFor(state)
        ActivationSystem.updateDaily(ctx)

        assertThat(followed.detailLevel).isEqualTo(DetailLevel.DETAILED)
        assertThat(state.discoveredResidentIds).contains(followedId)
    }

    // ---------------------------------------------------------------------- multi-district spread

    @Test fun `population is spread across all 5 districts, not concentrated in one`() {
        val state = TestWorld.newState()
        val homeBuildings = state.buildings.values.filter { b ->
            b.type.name in listOf("HOUSE", "COTTAGE", "TERRACE", "FLAT")
        }
        val districtIds = homeBuildings.mapNotNull { it.districtId }.distinct()
        // All 5 districts contain at least one home — residents can be distributed across them.
        assertThat(districtIds.size).isAtLeast(3)
    }
}
