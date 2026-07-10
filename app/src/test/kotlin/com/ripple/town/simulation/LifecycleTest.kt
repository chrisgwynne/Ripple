package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.simulation.LifecycleSystem
import org.junit.Test

class LifecycleTest {

    @Test
    fun `death ends employment widows the partner and updates the household`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val tom = TestWorld.resident(state, "Tom Bell")
        val edie = TestWorld.resident(state, "Edie Bell")
        val household = state.households[tom.householdId]!!
        assertThat(state.employmentOf(tom)).isNotNull()

        LifecycleSystem.die(ctx, tom, "a weak heart")

        assertThat(tom.alive).isFalse()
        assertThat(tom.causeOfDeath).isEqualTo("a weak heart")
        assertThat(state.employmentOf(tom)).isNull()
        assertThat(edie.relationshipStatus).isEqualTo(RelationshipStatus.WIDOWED)
        assertThat(edie.partnerId).isNull()
        assertThat(household.memberIds).doesNotContain(tom.id)
        assertThat(ctx.newEvents.map { it.type }).contains(EventType.PERSON_DIED)
    }

    @Test
    fun `no resident works after death over a long simulation`() {
        val coordinator = TestWorld.newCoordinator()
        TestWorld.runDays(coordinator, 10)
        val state = coordinator.state
        for (r in state.residents.values.filter { !it.alive }) {
            assertThat(state.employmentOf(r)).isNull()
            assertThat(r.activity).isNotEqualTo(com.ripple.town.core.model.Activity.WORKING)
        }
        // And nobody employed is dead, from the other side.
        for (emp in state.employments.values.filter { it.active }) {
            assertThat(state.resident(emp.residentId)!!.alive).isTrue()
        }
    }

    @Test
    fun `children never hold adult occupations`() {
        val coordinator = TestWorld.newCoordinator()
        TestWorld.runDays(coordinator, 10)
        val state = coordinator.state
        for (r in state.residents.values.filter { it.alive && it.ageAt(state.time) < 16 }) {
            assertThat(state.employmentOf(r)).isNull()
        }
    }

    @Test
    fun `no business employs more staff than capacity`() {
        val coordinator = TestWorld.newCoordinator()
        TestWorld.runDays(coordinator, 10)
        val state = coordinator.state
        for (biz in state.businesses.values) {
            assertThat(state.employeesOf(biz.id).size).isAtMost(biz.employeeCapacity)
        }
    }

    @Test
    fun `inherited personality is a tendency not a copy`() {
        val rng = com.ripple.town.core.simulation.SimRandom(42L)
        val a = com.ripple.town.core.model.Personality(kindness = 0.9, ambition = 0.1)
        val b = com.ripple.town.core.model.Personality(kindness = 0.7, ambition = 0.3)
        val kids = (0 until 30).map { LifecycleSystem.inheritPersonality(rng, a, b) }
        // Centred between parents…
        val meanKindness = kids.sumOf { it.kindness } / kids.size
        assertThat(meanKindness).isGreaterThan(0.55)
        assertThat(meanKindness).isLessThan(0.95)
        // …but with real variation.
        assertThat(kids.map { it.kindness }.distinct().size).isGreaterThan(5)
    }

    @Test
    fun `the estate passes to the family`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val vernon = TestWorld.resident(state, "Vernon Silverstone")
        val maud = TestWorld.resident(state, "Maud Silverstone")
        vernon.wealth = 1_000.0
        vernon.debt = 0.0
        val maudBefore = maud.wealth
        LifecycleSystem.die(ctx, vernon, "old age")
        assertThat(maud.wealth).isGreaterThan(maudBefore)
        assertThat(vernon.wealth).isEqualTo(0.0)
    }
}
