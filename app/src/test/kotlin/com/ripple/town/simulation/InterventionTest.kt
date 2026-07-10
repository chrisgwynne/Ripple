package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.simulation.InterventionEngine
import com.ripple.town.core.simulation.InterventionResult
import org.junit.Test

class InterventionTest {

    @Test
    fun `delay holds a resident up and spends a nudge`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val mara = TestWorld.resident(state, "Mara Vale")
        val endsBefore = mara.activityEndsAt
        val nudgesBefore = state.nudges

        val result = InterventionEngine.apply(ctx, InterventionVerb.DELAY, mara.id)

        assertThat(result).isInstanceOf(InterventionResult.Applied::class.java)
        assertThat(mara.activityEndsAt).isGreaterThan(endsBefore)
        assertThat(state.nudges).isEqualTo(nudgesBefore - 1)
        // Recorded permanently as a hidden event.
        val event = ctx.newEvents.single()
        assertThat(event.visibility).isEqualTo(com.ripple.town.core.model.EventVisibility.HIDDEN)
    }

    @Test
    fun `introduce raises a meeting chance but guarantees nothing`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val wren = TestWorld.resident(state, "Wren Oakes")
        val gil = TestWorld.resident(state, "Gil Dunmore")
        val kindBefore = state.relationship(wren.id, gil.id)?.kind

        val result = InterventionEngine.apply(ctx, InterventionVerb.INTRODUCE, wren.id, gil.id)

        assertThat(result).isInstanceOf(InterventionResult.Applied::class.java)
        // A chance was created…
        assertThat(state.delayedEffects.last().type).isEqualTo(DelayedEffectType.MEETING_CHANCE)
        // …but no relationship changed on the spot.
        assertThat(state.relationship(wren.id, gil.id)?.kind).isEqualTo(kindBefore)
    }

    @Test
    fun `no nudges left means no intervention`() {
        val state = TestWorld.newState()
        state.nudges = 0
        val ctx = TestWorld.contextFor(state)
        val result = InterventionEngine.apply(ctx, InterventionVerb.DELAY, TestWorld.resident(state, "Mara Vale").id)
        assertThat(result).isInstanceOf(InterventionResult.Rejected::class.java)
        assertThat(ctx.newEvents).isEmpty()
    }

    @Test
    fun `the same person cannot be nudged twice within the cooldown`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val mara = TestWorld.resident(state, "Mara Vale")
        val first = InterventionEngine.apply(ctx, InterventionVerb.DELAY, mara.id)
        assertThat(first).isInstanceOf(InterventionResult.Applied::class.java)
        val second = InterventionEngine.apply(ctx, InterventionVerb.DISTRACT, mara.id)
        assertThat(second).isInstanceOf(InterventionResult.Rejected::class.java)
        assertThat(state.nudges).isEqualTo(state.maxNudges - 1)
    }

    @Test
    fun `influence regenerates through observation up to the cap`() {
        val state = TestWorld.newState()
        state.nudges = 0
        InterventionEngine.regenerate(state, InterventionEngine.REGEN_HOURS * SimTime.MINUTES_PER_HOUR)
        assertThat(state.nudges).isEqualTo(1)
        // Far more observation cannot exceed the cap.
        InterventionEngine.regenerate(state, 100L * SimTime.MINUTES_PER_DAY)
        assertThat(state.nudges).isEqualTo(state.maxNudges)
    }

    @Test
    fun `free demo intervention does not consume influence`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val before = state.nudges
        val result = InterventionEngine.apply(
            ctx, InterventionVerb.DELAY, TestWorld.resident(state, "Mara Vale").id, free = true
        )
        assertThat(result).isInstanceOf(InterventionResult.Applied::class.java)
        assertThat(state.nudges).isEqualTo(before)
    }

    @Test
    fun `an inspired idea may or may not grow - engine decides`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val felix = TestWorld.resident(state, "Felix Ingram")
        val goalsBefore = felix.goals.size
        InterventionEngine.apply(ctx, InterventionVerb.INSPIRE, felix.id)
        // The seed is planted…
        assertThat(felix.ideaSeeds).isNotEmpty()
        // …but no goal is forced into existence by the intervention itself.
        assertThat(felix.goals.size).isEqualTo(goalsBefore)
    }
}
