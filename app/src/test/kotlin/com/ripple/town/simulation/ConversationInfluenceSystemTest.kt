package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Belief
import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Resident
import com.ripple.town.core.simulation.ConversationInfluenceSystem
import com.ripple.town.core.simulation.ConversationTopic
import org.junit.Test

/**
 * Covers `ConversationInfluenceSystem` — the mechanical belief-side consequence of a
 * conversation `InteractionSystem` already sampled. See
 * `docs/simulation-rules.md` "Conversation influence" for the full gating/formula writeup.
 */
class ConversationInfluenceSystemTest {

    /** An adult resident who is a confident, settled speaker on ECONOMIC_OPTIMISM at [position]. */
    private fun confidentSpeaker(
        state: com.ripple.town.core.model.WorldState, name: String, position: Double, now: Long
    ): Resident {
        val r = TestWorld.resident(state, name)
        r.detailLevel = DetailLevel.DETAILED
        r.beliefs[BeliefTopic.ECONOMIC_OPTIMISM] = Belief(
            topic = BeliefTopic.ECONOMIC_OPTIMISM,
            position = position,
            confidence = 0.85,
            lastUpdatedAt = now
        )
        return r
    }

    /** An open, curious/low-discipline, low-confidence-on-the-topic adult listener. */
    private fun openListener(state: com.ripple.town.core.model.WorldState, name: String): Resident {
        val r = TestWorld.resident(state, name)
        r.detailLevel = DetailLevel.DETAILED
        return r
    }

    private fun makeAdult(r: Resident, now: Long) {
        // Ensure the resident's birth date reads as ADULT at `now` for lifeStageAt checks.
        // TestWorld-seeded residents are already adults in the default generated world, but we
        // guard explicitly since this system gates on life stage.
        assertThat(r.lifeStageAt(now)).isAnyOf(LifeStage.ADULT, LifeStage.TEEN)
    }

    private fun highOpennessPersonality() = Personality(
        kindness = 0.5, ambition = 0.5, curiosity = 0.9, sociability = 0.6,
        patience = 0.5, honesty = 0.5, courage = 0.5, discipline = 0.1, empathy = 0.5,
        impulsiveness = 0.5
    )

    @Test
    fun `a trusted, confident speaker measurably shifts an open, low-confidence listener's belief toward their own position`() {
        val state = TestWorld.newState()
        val now = state.time
        val speaker = confidentSpeaker(state, "Wren Oakes", position = 0.8, now = now)
        val listener = openListener(state, "Noa Fenwick")
        makeAdult(speaker, now)
        makeAdult(listener, now)
        // Give the listener a receptive personality (high curiosity, low discipline) so the
        // "listener openness" gate is unambiguously satisfied regardless of seeded personality.
        listener.personalityModifiers.curiosity = (highOpennessPersonality().curiosity - listener.personality.curiosity)
        listener.personalityModifiers.discipline = (highOpennessPersonality().discipline - listener.personality.discipline)

        val rel = state.relationshipOrCreate(speaker.id, listener.id)
        rel.trust = 80.0
        rel.respect = 70.0

        assertThat(listener.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]).isNull()

        var landed = false
        repeat(300) {
            val ctx = TestWorld.contextFor(state, salt = it.toLong())
            val result = ConversationInfluenceSystem.maybeInfluence(ctx, speaker, listener, ConversationTopic.MONEY, rel)
            if (result) landed = true
        }

        assertThat(landed).isTrue()
        val belief = listener.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]
        assertThat(belief).isNotNull()
        // Moved toward the speaker's strongly positive position, i.e. upward from neutral.
        assertThat(belief!!.position).isGreaterThan(0.0)
        // Never overshoots past the speaker's own position.
        assertThat(belief.position).isAtMost(0.8)
        assertThat(belief.confidence).isGreaterThan(0.0)
    }

    @Test
    fun `a stranger with no trust or respect has no mechanical effect`() {
        val state = TestWorld.newState()
        val now = state.time
        val speaker = confidentSpeaker(state, "Wren Oakes", position = 0.8, now = now)
        val listener = openListener(state, "Noa Fenwick")
        makeAdult(speaker, now)
        makeAdult(listener, now)

        val rel = state.relationshipOrCreate(speaker.id, listener.id)
        rel.trust = 10.0
        rel.respect = 10.0 // both well below either gating threshold

        var landed = false
        repeat(300) {
            val ctx = TestWorld.contextFor(state, salt = it.toLong())
            val result = ConversationInfluenceSystem.maybeInfluence(ctx, speaker, listener, ConversationTopic.MONEY, rel)
            if (result) landed = true
        }

        assertThat(landed).isFalse()
        assertThat(listener.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]).isNull()
    }

    @Test
    fun `the shift is small and bounded, never a snap conversion in a single conversation`() {
        val state = TestWorld.newState()
        val now = state.time
        val speaker = confidentSpeaker(state, "Wren Oakes", position = 1.0, now = now)
        val listener = openListener(state, "Noa Fenwick")
        makeAdult(speaker, now)
        makeAdult(listener, now)
        listener.personalityModifiers.curiosity = (highOpennessPersonality().curiosity - listener.personality.curiosity)
        listener.personalityModifiers.discipline = (highOpennessPersonality().discipline - listener.personality.discipline)

        val rel = state.relationshipOrCreate(speaker.id, listener.id)
        rel.trust = 90.0
        rel.respect = 90.0

        // Find the first tick where influence lands, then check the single-shot delta.
        var deltaApplied: Double? = null
        var i = 0
        while (deltaApplied == null && i < 500) {
            val before = listener.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]?.position ?: 0.0
            val ctx = TestWorld.contextFor(state, salt = i.toLong())
            val result = ConversationInfluenceSystem.maybeInfluence(ctx, speaker, listener, ConversationTopic.MONEY, rel)
            if (result) {
                val after = listener.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]!!.position
                deltaApplied = after - before
            }
            i++
        }

        assertThat(deltaApplied).isNotNull()
        // A single landed conversation should never move position anywhere close to a full
        // conversion (e.g. from 0.0 straight to 1.0) — bounded to a small nudge.
        assertThat(kotlin.math.abs(deltaApplied!!)).isLessThan(0.2)
    }

    @Test
    fun `same seed produces the same conversation-influence timeline`() {
        val state1 = TestWorld.newState()
        val state2 = TestWorld.newState()
        val now1 = state1.time
        val now2 = state2.time
        val speaker1 = confidentSpeaker(state1, "Wren Oakes", position = 0.8, now = now1)
        val listener1 = openListener(state1, "Noa Fenwick")
        val speaker2 = confidentSpeaker(state2, "Wren Oakes", position = 0.8, now = now2)
        val listener2 = openListener(state2, "Noa Fenwick")

        for ((listener, template) in listOf(listener1 to highOpennessPersonality(), listener2 to highOpennessPersonality())) {
            listener.personalityModifiers.curiosity = template.curiosity - listener.personality.curiosity
            listener.personalityModifiers.discipline = template.discipline - listener.personality.discipline
        }

        val rel1 = state1.relationshipOrCreate(speaker1.id, listener1.id)
        rel1.trust = 80.0; rel1.respect = 70.0
        val rel2 = state2.relationshipOrCreate(speaker2.id, listener2.id)
        rel2.trust = 80.0; rel2.respect = 70.0

        repeat(100) {
            val ctx1 = TestWorld.contextFor(state1, salt = it.toLong())
            val ctx2 = TestWorld.contextFor(state2, salt = it.toLong())
            ConversationInfluenceSystem.maybeInfluence(ctx1, speaker1, listener1, ConversationTopic.MONEY, rel1)
            ConversationInfluenceSystem.maybeInfluence(ctx2, speaker2, listener2, ConversationTopic.MONEY, rel2)
        }

        val b1 = listener1.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]
        val b2 = listener2.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]
        assertThat(b1 != null).isEqualTo(b2 != null)
        if (b1 != null && b2 != null) {
            assertThat(b1.position).isEqualTo(b2.position)
            assertThat(b1.confidence).isEqualTo(b2.confidence)
        }
    }

    @Test
    fun `the per-tick influence budget bounds how many conversations can mechanically land in one tick`() {
        val state = TestWorld.newState()
        val now = state.time
        val speaker = confidentSpeaker(state, "Wren Oakes", position = 0.9, now = now)
        val listener = openListener(state, "Noa Fenwick")
        listener.personalityModifiers.curiosity = highOpennessPersonality().curiosity - listener.personality.curiosity
        listener.personalityModifiers.discipline = highOpennessPersonality().discipline - listener.personality.discipline

        val rel = state.relationshipOrCreate(speaker.id, listener.id)
        rel.trust = 95.0
        rel.respect = 95.0

        // Within a single TickContext (one shared budget), repeated calls can land at most
        // ConversationInfluenceSystem.MAX_MEANINGFUL_PER_TICK times.
        val ctx = TestWorld.contextFor(state, salt = 1L)
        var landedCount = 0
        repeat(50) {
            if (ConversationInfluenceSystem.maybeInfluence(ctx, speaker, listener, ConversationTopic.MONEY, rel)) {
                landedCount++
            }
        }
        assertThat(landedCount).isAtMost(ConversationInfluenceSystem.MAX_MEANINGFUL_PER_TICK)
    }

    @Test
    fun `wiring from InteractionSystem's sampled pairs does not throw and stays bounded`() {
        val coordinator = TestWorld.newCoordinator()
        // Smoke-test the real call path: InteractionSystem.update -> ConversationInfluenceSystem
        // .maybeInfluence, over several ticks, purely checking it runs without throwing and that
        // no belief position ever leaves its documented range.
        repeat(50) { coordinator.tick() }
        for (r in coordinator.state.residents.values) {
            val belief = r.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]
            if (belief != null) {
                assertThat(belief.position).isAtLeast(-1.0)
                assertThat(belief.position).isAtMost(1.0)
                assertThat(belief.confidence).isAtLeast(0.0)
                assertThat(belief.confidence).isAtMost(1.0)
            }
        }
    }
}
