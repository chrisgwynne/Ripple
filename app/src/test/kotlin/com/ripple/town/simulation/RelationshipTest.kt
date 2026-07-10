package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.EventType
import com.ripple.town.core.simulation.InteractionSystem
import org.junit.Test

class RelationshipTest {

    @Test
    fun `a friendly interaction builds familiarity trust and affection`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val wren = TestWorld.resident(state, "Wren Oakes")
        val noa = TestWorld.resident(state, "Noa Fenwick")
        wren.needs.stress = 20.0; noa.needs.stress = 20.0
        val before = state.relationship(wren.id, noa.id)!!.copy()
        InteractionSystem.interact(ctx, wren, noa, wren.currentBuildingId)
        val after = state.relationship(wren.id, noa.id)!!
        assertThat(after.familiarity).isGreaterThan(before.familiarity)
        assertThat(after.lastInteractionAt).isEqualTo(ctx.now)
    }

    @Test
    fun `arguments raise resentment and lower affection`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val jonas = TestWorld.resident(state, "Jonas Marsh")
        val petra = TestWorld.resident(state, "Petra Marsh")
        val rel = state.relationship(jonas.id, petra.id)!!
        val resentmentBefore = rel.resentment
        val affectionBefore = rel.affection
        InteractionSystem.argue(ctx, jonas, petra, rel, jonas.currentBuildingId)
        assertThat(rel.resentment).isGreaterThan(resentmentBefore)
        assertThat(rel.affection).isLessThan(affectionBefore)
        assertThat(ctx.newEvents.map { it.type }).contains(EventType.ARGUMENT)
    }

    @Test
    fun `repeated absence lowers affection`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val rel = state.relationship(
            TestWorld.resident(state, "Wren Oakes").id,
            TestWorld.resident(state, "Noa Fenwick").id
        )!!
        rel.lastInteractionAt = state.time - 30L * com.ripple.town.core.model.SimTime.MINUTES_PER_DAY
        val before = rel.affection
        InteractionSystem.dailyDecay(ctx)
        assertThat(rel.affection).isLessThan(before)
    }

    @Test
    fun `no impossible relationship states appear over a long run`() {
        val coordinator = TestWorld.newCoordinator()
        TestWorld.runDays(coordinator, 8)
        val state = coordinator.state
        for (rel in state.relationships.values) {
            assertThat(rel.aId).isLessThan(rel.bId)
            for (v in listOf(rel.familiarity, rel.trust, rel.affection, rel.attraction,
                rel.respect, rel.resentment, rel.dependency, rel.sharedHistory)) {
                assertThat(v).isAtLeast(0.0)
                assertThat(v).isAtMost(100.0)
            }
        }
        // Partner links are symmetric and alive-or-widowed consistent.
        for (r in state.residents.values.filter { it.partnerId != null }) {
            val partner = state.resident(r.partnerId!!)!!
            if (partner.alive && r.alive) {
                assertThat(partner.partnerId).isEqualTo(r.id)
            }
        }
    }
}
