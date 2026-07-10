package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.GoalStatus
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.simulation.GoalSystem
import org.junit.Test

class GoalAndEconomyTest {

    @Test
    fun `goals form from combined circumstances not randomness`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val ash = TestWorld.resident(state, "Ash Thistle")
        // Ash: carpentry skill + vacant granary + idea seed + thin wallet.
        assertThat(ash.ideaSeeds).isNotEmpty()
        GoalSystem.updateDaily(ctx)
        assertThat(ash.goals.filter { it.status == GoalStatus.ACTIVE }.map { it.type })
            .contains(GoalType.START_BUSINESS)
    }

    @Test
    fun `a funded ready business plan opens in the vacant building`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val ash = TestWorld.resident(state, "Ash Thistle")
        GoalSystem.seedGoal(ctx, ash, GoalType.START_BUSINESS, "test", null)
        val goal = ash.goals.first { it.type == GoalType.START_BUSINESS }
        goal.progress = 0.999
        ash.wealth = GoalSystem.STARTUP_CAPITAL + 100.0
        GoalSystem.updateDaily(ctx)

        assertThat(goal.status).isEqualTo(GoalStatus.COMPLETED)
        assertThat(ctx.newEvents.map { it.type }).contains(EventType.BUSINESS_OPENED)
        val granary = state.buildings.values.first { it.name == "The Old Granary" }
        assertThat(granary.type).isEqualTo(BuildingType.WORKSHOP)
        assertThat(granary.abandoned).isFalse()
        assertThat(state.businesses.values.any { it.name.contains("Thistle") }).isTrue()
        assertThat(ash.occupation).isEqualTo("Workshop owner")
    }

    @Test
    fun `businesses gain and lose money over time`() {
        val coordinator = TestWorld.newCoordinator()
        val before = coordinator.state.businesses.values.associate { it.id to it.balance }
        TestWorld.runDays(coordinator, 6)
        val after = coordinator.state.businesses.values.associate { it.id to it.balance }
        // Money moved somewhere: at least one business changed balance.
        assertThat(before).isNotEqualTo(after)
    }

    @Test
    fun `a business deep in the red eventually closes and the chain is recorded`() {
        val coordinator = TestWorld.newCoordinator()
        val state = coordinator.state
        val bakery = state.businesses.values.first { it.name == "Bell's Bakery" }
        bakery.balance = -3_000.0
        bakery.demand = 6.0
        bakery.daysInTrouble = EconomyClosureDays - 1
        val events = TestWorld.runDays(coordinator, 3)
        val closure = events.firstOrNull { it.type == EventType.BUSINESS_CLOSED }
        assertThat(closure).isNotNull()
        val losses = events.filter { it.type == EventType.JOB_LOST }
        assertThat(losses).isNotEmpty()
        assertThat(losses.all { closure!!.id in it.causeIds }).isTrue()
    }

    @Test
    fun `historical importance grows when events cause other events`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val bakery = state.businesses.values.first { it.name == "Bell's Bakery" }
        com.ripple.town.core.simulation.EconomySystem.closeBusiness(ctx, bakery, "for testing")
        val closure = ctx.newEvents.first { it.type == EventType.BUSINESS_CLOSED }
        // Each caused job loss boosted the closure's importance.
        assertThat(ctx.importanceBoosts[closure.id]).isNotNull()
        assertThat(ctx.importanceBoosts[closure.id]!!).isGreaterThan(0.0)
    }

    companion object {
        private const val EconomyClosureDays = com.ripple.town.core.simulation.EconomySystem.CLOSURE_DAYS
    }
}
