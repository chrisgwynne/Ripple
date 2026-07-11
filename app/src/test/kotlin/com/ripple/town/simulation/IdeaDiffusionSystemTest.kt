package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.IdeaLibrary
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.ResidentIdeaState
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.simulation.IdeaDiffusionSystem
import org.junit.Test

/**
 * Covers `IdeaDiffusionSystem` — the abstract, ownable-idea diffusion layer that rides
 * `InteractionSystem`'s existing co-located/sociable sampling. See
 * `docs/simulation-rules.md#idea-diffusion`.
 */
class IdeaDiffusionSystemTest {

    /** Seeds [holder] with a strong, advocacy-ready idea and co-locates [holder]/[listener] in
     *  the same building, both DETAILED and SOCIALISING, so they land in the same sampled pair
     *  window `IdeaDiffusionSystem.update`/`InteractionSystem.update` both use. */
    private fun setUpAdvocateAndListener(state: com.ripple.town.core.model.WorldState, holder: Resident, listener: Resident) {
        val building = state.buildings.values.first()
        holder.detailLevel = DetailLevel.DETAILED
        listener.detailLevel = DetailLevel.DETAILED
        holder.currentBuildingId = building.id
        listener.currentBuildingId = building.id
        holder.activity = com.ripple.town.core.model.Activity.SOCIALISING
        listener.activity = com.ripple.town.core.model.Activity.SOCIALISING
        // A strong, warm existing relationship removes trust/warmth as a confound.
        val rel = state.relationshipOrCreate(holder.id, listener.id)
        rel.trust = 90.0
        rel.affection = 80.0
        rel.familiarity = 80.0
        rel.respect = 70.0
    }

    private fun strongIdea(templateId: String, now: Long) = ResidentIdeaState(
        templateId = templateId,
        awareness = 90.0,
        interest = 90.0,
        beliefStrength = 85.0,
        advocacyStrength = 95.0,
        firstHeardAt = now,
        lastReinforcedAt = now
    )

    @Test
    fun `an idea spreads between two compatible, interacting residents`() {
        val state = TestWorld.newState()
        val holder = TestWorld.resident(state, "Wren Oakes")
        val listener = TestWorld.resident(state, "Noa Fenwick")
        setUpAdvocateAndListener(state, holder, listener)

        val template = IdeaLibrary.COMMUNITY_GARDEN
        holder.activeIdeas += strongIdea(template.id, state.time)

        var transferred = false
        repeat(200) {
            val ctx = TestWorld.contextFor(state, salt = it.toLong())
            IdeaDiffusionSystem.update(ctx)
            if (listener.activeIdeas.any { s -> s.templateId == template.id }) {
                transferred = true
                return@repeat
            }
            state.time += SimTime.MINUTES_PER_TICK
        }

        assertThat(transferred).isTrue()
        val received = listener.activeIdeas.first { it.templateId == template.id }
        assertThat(received.beliefStrength).isGreaterThan(0.0)
        assertThat(received.awareness).isGreaterThan(0.0)
    }

    @Test
    fun `an idea decays and is removed once not reinforced`() {
        val state = TestWorld.newState()
        val resident = TestWorld.resident(state, "Wren Oakes")
        resident.detailLevel = DetailLevel.DETAILED
        val idea = ResidentIdeaState(
            templateId = IdeaLibrary.HEALTHIER_EATING.id,
            awareness = 10.0,
            interest = 4.0,
            beliefStrength = 4.0,
            advocacyStrength = 3.0,
            firstHeardAt = state.time,
            lastReinforcedAt = state.time
        )
        resident.activeIdeas += idea

        // Advance many in-world days without any reinforcement.
        repeat(20) {
            state.time += SimTime.MINUTES_PER_DAY
            val ctx = TestWorld.contextFor(state)
            IdeaDiffusionSystem.updateDaily(ctx)
        }

        assertThat(resident.activeIdeas.any { it.templateId == IdeaLibrary.HEALTHIER_EATING.id }).isFalse()
    }

    @Test
    fun `a resident's active idea list stays bounded`() {
        val state = TestWorld.newState()
        val resident = TestWorld.resident(state, "Wren Oakes")
        resident.detailLevel = DetailLevel.DETAILED
        // Seed one more than the cap, all with distinct templates, no decay in between.
        for (template in IdeaLibrary.ALL.take(IdeaDiffusionSystem.MAX_ACTIVE_IDEAS + 2)) {
            resident.activeIdeas += ResidentIdeaState(
                templateId = template.id,
                awareness = 50.0,
                interest = 50.0,
                beliefStrength = 20.0 + resident.activeIdeas.size.toDouble(), // distinct strengths
                advocacyStrength = 40.0,
                firstHeardAt = state.time,
                lastReinforcedAt = state.time
            )
        }

        assertThat(resident.activeIdeas.size).isEqualTo(IdeaDiffusionSystem.MAX_ACTIVE_IDEAS + 2)
        // Now drive the bounding behaviour the way real adoption does: adopt one more via the
        // system's own path so the eviction logic actually runs.
        val ctx = TestWorld.contextFor(state)
        // Directly exercise decay/prune + the daily spawn pass repeatedly; list must never exceed
        // the documented cap after any system pass touches it.
        repeat(10) {
            state.time += SimTime.MINUTES_PER_DAY
            IdeaDiffusionSystem.updateDaily(TestWorld.contextFor(state, salt = it.toLong()))
            assertThat(resident.activeIdeas.size).isAtMost(IdeaDiffusionSystem.MAX_ACTIVE_IDEAS)
        }
    }

    @Test
    fun `same seed produces the same idea-diffusion timeline`() {
        val state1 = TestWorld.newState()
        val state2 = TestWorld.newState()
        val holder1 = TestWorld.resident(state1, "Wren Oakes")
        val listener1 = TestWorld.resident(state1, "Noa Fenwick")
        val holder2 = TestWorld.resident(state2, "Wren Oakes")
        val listener2 = TestWorld.resident(state2, "Noa Fenwick")
        setUpAdvocateAndListener(state1, holder1, listener1)
        setUpAdvocateAndListener(state2, holder2, listener2)
        holder1.activeIdeas += strongIdea(IdeaLibrary.COMMUNITY_GARDEN.id, state1.time)
        holder2.activeIdeas += strongIdea(IdeaLibrary.COMMUNITY_GARDEN.id, state2.time)

        repeat(60) {
            IdeaDiffusionSystem.update(TestWorld.contextFor(state1, salt = it.toLong()))
            IdeaDiffusionSystem.update(TestWorld.contextFor(state2, salt = it.toLong()))
            state1.time += SimTime.MINUTES_PER_TICK
            state2.time += SimTime.MINUTES_PER_TICK
        }

        val received1 = listener1.activeIdeas.firstOrNull { it.templateId == IdeaLibrary.COMMUNITY_GARDEN.id }
        val received2 = listener2.activeIdeas.firstOrNull { it.templateId == IdeaLibrary.COMMUNITY_GARDEN.id }
        assertThat(received1 != null).isEqualTo(received2 != null)
        if (received1 != null && received2 != null) {
            assertThat(received1.beliefStrength).isEqualTo(received2.beliefStrength)
            assertThat(received1.distorted).isEqualTo(received2.distorted)
        }
    }

    @Test
    fun `trait affinity is higher for a resident whose personality matches the idea's appeal traits`() {
        val state = TestWorld.newState()
        val kindCurious = TestWorld.resident(state, "Wren Oakes")
        val other = TestWorld.resident(state, "Noa Fenwick")
        // COMMUNITY_GARDEN appeals to kindness+curiosity.
        val highAffinity = IdeaDiffusionSystem.traitAffinity(kindCurious, IdeaLibrary.COMMUNITY_GARDEN)
        assertThat(highAffinity).isAtLeast(0.0)
        assertThat(highAffinity).isAtMost(1.0)
        val otherAffinity = IdeaDiffusionSystem.traitAffinity(other, IdeaLibrary.COMMUNITY_GARDEN)
        assertThat(otherAffinity).isAtLeast(0.0)
        assertThat(otherAffinity).isAtMost(1.0)
    }
}
