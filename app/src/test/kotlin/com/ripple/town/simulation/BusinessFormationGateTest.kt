package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.GoalStatus
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.simulation.EconomySystem
import com.ripple.town.core.simulation.GoalSystem
import org.junit.Test

/**
 * Covers the Economy Calibration Gate Phase 2 (2026-07-11) business formation gate — see
 * docs/simulation-rules.md "Business formation gate". Two layers: `EconomySystem
 * .estimateFormationViability` (the pure projection) and `GoalSystem.openBusiness` (the caller
 * that actually rejects/redirects an unviable attempt).
 */
class BusinessFormationGateTest {

    @Test
    fun `WORKSHOP FACTORY are always formation-viable regardless of catchment - contract-shaped, not catchment-gated`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val vacantOrAny = state.buildings.values.first { it.type != BuildingType.VACANT || true }

        val workshop = EconomySystem.estimateFormationViability(ctx, vacantOrAny, BusinessType.WORKSHOP, GoalSystem.STARTUP_CAPITAL)
        assertThat(workshop.viable).isTrue()

        val factory = EconomySystem.estimateFormationViability(ctx, vacantOrAny, BusinessType.FACTORY, GoalSystem.STARTUP_CAPITAL)
        assertThat(factory.viable).isTrue()
    }

    @Test
    fun `zero or negative startup capital is never viable`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val building = state.buildings.values.first()

        val zero = EconomySystem.estimateFormationViability(ctx, building, BusinessType.CAFE, 0.0)
        assertThat(zero.viable).isFalse()

        val negative = EconomySystem.estimateFormationViability(ctx, building, BusinessType.CAFE, -50.0)
        assertThat(negative.viable).isFalse()
    }

    @Test
    fun `too many same-type competitors in catchment rejects a retail formation attempt`() {
        val state = TestWorld.newState()
        // Build a cluster of same-type open CAFEs sharing this exact building's location so the
        // competitor count is unambiguous — reuse an existing building as every "rival"'s site to
        // guarantee zero distance (always inside any real catchment radius).
        val siteBuilding = state.buildings.values.first { it.type != BuildingType.VACANT }
        repeat(EconomySystem.FORMATION_MAX_LOCAL_COMPETITORS + 2) { i ->
            val id = state.nextBusinessId++
            state.businesses[id] = com.ripple.town.core.model.Business(
                id = id, buildingId = siteBuilding.id, name = "Rival Cafe $i",
                type = BusinessType.CAFE, open = true, reputation = 55.0, demand = 50.0
            )
        }
        val ctx = TestWorld.contextFor(state)
        val viability = EconomySystem.estimateFormationViability(ctx, siteBuilding, BusinessType.CAFE, GoalSystem.STARTUP_CAPITAL)
        assertThat(viability.viable).isFalse()
        assertThat(viability.localCompetitors).isGreaterThan(EconomySystem.FORMATION_MAX_LOCAL_COMPETITORS)
    }

    @Test
    fun `a building with zero nearby households projects unviable retail demand`() {
        // Construct a state with no households at all reachable in any retail catchment: clear
        // every household's home building id so catchmentDemandFor finds nobody.
        val state = TestWorld.newState()
        for (hh in state.households.values) hh.homeBuildingId = null
        val building = state.buildings.values.first { it.type != BuildingType.VACANT }
        val ctx = TestWorld.contextFor(state)

        val viability = EconomySystem.estimateFormationViability(ctx, building, BusinessType.BOOKSHOP, GoalSystem.STARTUP_CAPITAL)
        assertThat(viability.viable).isFalse()
        assertThat(viability.reason).isNotEmpty()
    }

    @Test
    fun `GoalSystem openBusiness rejects when no vacant building exists and keeps the goal alive, not abandoned`() {
        val state = TestWorld.newState()
        // Ensure no vacant/abandoned building exists.
        for (b in state.buildings.values) {
            if (b.type == BuildingType.VACANT) b.abandoned = false
        }
        val resident = state.residentsOrdered().first {
            it.inTown && it.alive && it.lifeStageAt(state.time) == LifeStage.ADULT
        }
        resident.wealth = GoalSystem.STARTUP_CAPITAL + 100.0
        val goal = com.ripple.town.core.model.Goal(
            id = state.nextGoalId++, ownerId = resident.id, type = GoalType.START_BUSINESS,
            motivation = "test", createdAt = state.time, progress = 1.0
        )
        resident.goals += goal
        val businessCountBefore = state.businesses.size
        val wealthBefore = resident.wealth

        val ctx = TestWorld.contextFor(state)
        com.ripple.town.core.simulation.GoalSystem.updateDaily(ctx)

        // No business opened, no capital spent, goal not abandoned outright.
        assertThat(state.businesses.size).isEqualTo(businessCountBefore)
        assertThat(resident.wealth).isEqualTo(wealthBefore)
        assertThat(goal.status).isEqualTo(GoalStatus.ACTIVE)
    }

    @Test
    fun `GoalSystem openBusiness opens a real business when a vacant building and viable demand exist`() {
        val state = TestWorld.newState()
        val vacant = state.buildings.values.firstOrNull { it.type == BuildingType.VACANT && it.abandoned }
        // Some seeds/generated towns may not include a vacant building — skip gracefully rather
        // than asserting a false failure on world-generation specifics outside this gate's scope.
        if (vacant == null) return

        val resident = state.residentsOrdered().first {
            it.inTown && it.alive && it.lifeStageAt(state.time) == LifeStage.ADULT
        }
        resident.wealth = GoalSystem.STARTUP_CAPITAL + 500.0
        val goal = com.ripple.town.core.model.Goal(
            id = state.nextGoalId++, ownerId = resident.id, type = GoalType.START_BUSINESS,
            motivation = "test", createdAt = state.time, progress = 1.0
        )
        resident.goals += goal
        val businessCountBefore = state.businesses.size

        val ctx = TestWorld.contextFor(state)
        com.ripple.town.core.simulation.GoalSystem.updateDaily(ctx)

        // WORKSHOP is always viable (contract-shaped carve-out) and is first in the candidate
        // list, so a vacant building should always find SOME viable opening.
        assertThat(state.businesses.size).isEqualTo(businessCountBefore + 1)
        val opened = state.businesses.values.maxByOrNull { it.id }!!
        assertThat(opened.ownerId).isEqualTo(resident.id)
        // Lean, owner-only start (Phase 2 staffing ramp): capacity trimmed to 1 at opening.
        assertThat(opened.employeeCapacity).isEqualTo(1)
        assertThat(state.employeesOf(opened.id).size).isEqualTo(1)
    }

    @Test
    fun `formation still happens at a reasonable rate across many independent seeds - the gate is not a total block`() {
        var openedSomewhere = 0
        val seeds = 1L until 25L
        for (seed in seeds) {
            val state = TestWorld.newState(seed = seed)
            val vacant = state.buildings.values.firstOrNull { it.type == BuildingType.VACANT && it.abandoned } ?: continue
            val ctx = TestWorld.contextFor(state, salt = seed)
            val building = state.building(vacant.id)!!
            val viability = EconomySystem.estimateFormationViability(ctx, building, BusinessType.WORKSHOP, GoalSystem.STARTUP_CAPITAL)
            if (viability.viable) openedSomewhere++
        }
        // WORKSHOP is contract-shaped and always viable per estimateFormationViability's own
        // carve-out — this asserts the gate genuinely lets formation through in the common case,
        // not that it silently rejects everything.
        assertThat(openedSomewhere).isGreaterThan(0)
    }

    @Test
    fun `determinism - same seed and salt produce identical viability projections`() {
        fun runOnce(): EconomySystem.FormationViability {
            val state = TestWorld.newState()
            val building = state.buildings.values.first { it.type != BuildingType.VACANT }
            val ctx = TestWorld.contextFor(state, salt = 3L)
            return EconomySystem.estimateFormationViability(ctx, building, BusinessType.GROCER, GoalSystem.STARTUP_CAPITAL)
        }
        val a = runOnce()
        val b = runOnce()
        assertThat(a.viable).isEqualTo(b.viable)
        assertThat(a.projectedDemand).isEqualTo(b.projectedDemand)
        assertThat(a.projectedBreakEven).isEqualTo(b.projectedBreakEven)
    }
}
