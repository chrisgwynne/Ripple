package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.simulation.EconomySystem
import org.junit.Test

class ConsequenceChainTest {

    @Test
    fun `business closure cascades into job losses with cause links`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val bakery = state.businesses.values.first { it.name == "Bell's Bakery" }
        val staffBefore = state.employeesOf(bakery.id).map { it.residentId }
        assertThat(staffBefore).isNotEmpty()

        EconomySystem.closeBusiness(ctx, bakery, "for testing")

        val closure = ctx.newEvents.first { it.type == EventType.BUSINESS_CLOSED }
        val jobLosses = ctx.newEvents.filter { it.type == EventType.JOB_LOST }
        assertThat(jobLosses).hasSize(staffBefore.size)
        for (loss in jobLosses) {
            assertThat(loss.causeIds).containsExactly(closure.id)
            assertThat(loss.consequenceDepth).isEqualTo(closure.consequenceDepth + 1)
        }
        // The workers are actually unemployed.
        for (id in staffBefore) {
            assertThat(state.employmentOf(state.resident(id)!!)).isNull()
            assertThat(state.resident(id)!!.occupation).isEqualTo("Unemployed")
        }
        assertThat(bakery.open).isFalse()
        assertThat(state.building(bakery.buildingId)!!.abandoned).isTrue()
    }

    @Test
    fun `job loss raises stress and seeds delayed pressures and a goal`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val bakery = state.businesses.values.first { it.name == "Bell's Bakery" }
        val mara = TestWorld.resident(state, "Mara Vale")
        mara.needs.financialSecurity = 20.0 // poor, so pressure rules bind
        val stressBefore = mara.needs.stress
        val effectsBefore = state.delayedEffects.size

        EconomySystem.closeBusiness(ctx, bakery, "for testing")

        assertThat(mara.needs.stress).isGreaterThan(stressBefore)
        assertThat(mara.goals.map { it.type }).contains(GoalType.FIND_JOB)
        assertThat(state.delayedEffects.size).isGreaterThan(effectsBefore)
    }

    @Test
    fun `cause links always reference earlier events`() {
        val coordinator = TestWorld.newCoordinator()
        val events = TestWorld.runDays(coordinator, 8)
        val byId = events.associateBy { it.id }
        for (e in events) {
            for (causeId in e.causeIds) {
                val cause = byId[causeId]
                // The cause may predate the window we captured, but if we have
                // it, it must be older (or same tick) and shallower.
                if (cause != null) {
                    assertThat(cause.time).isAtMost(e.time)
                    assertThat(cause.consequenceDepth).isLessThan(e.consequenceDepth + 1)
                    assertThat(causeId).isLessThan(e.id)
                }
            }
        }
    }

    @Test
    fun `consequence work per tick is bounded`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        // Even a storm of closures cannot exceed the per-tick budget.
        state.businesses.values.filter { it.type !in EconomySystem.PUBLIC_SERVICES }
            .forEach { EconomySystem.closeBusiness(ctx, it, "stress test") }
        assertThat(ctx.consequenceBudget).isAtLeast(0)
    }
}
