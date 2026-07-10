package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.ActiveEmotion
import com.ripple.town.core.model.EmotionType
import com.ripple.town.core.simulation.ActionKind
import com.ripple.town.core.simulation.DecisionSystem
import com.ripple.town.core.simulation.ScoredAction
import org.junit.Test

/**
 * Covers the panic/impulse override step added to `DecisionSystem` — see
 * docs/simulation-rules.md#panic-impulse-override. The override is a POST-PROCESSING step run
 * after `candidateActions()`/`chooseBest()`; these tests exercise it directly via
 * `DecisionSystem.panicOverrideProbability` and `DecisionSystem.applyPanicOverride`, rather than
 * relying on any specific candidate set from `candidateActions()`, so they stay valid even if the
 * scoring blocks (owned by other in-flight work this session) change shape.
 */
class PanicOverrideTest {

    private fun action(kind: ActionKind, score: Double): ScoredAction = ScoredAction(
        kind = kind, targetBuildingId = null, activity = com.ripple.town.core.model.Activity.IDLE,
        durationMinutes = 10, reason = "test",
        needPressure = score, personalityFit = 1.0, expectedReward = 1.0, confidence = 1.0,
        socialInfluence = 1.0, opportunity = 1.0, risk = 0.0, cost = 0.0, effort = 0.0,
        moralResistance = 0.0
    )

    /** Calm, disciplined, patient, non-impulsive resident: stress low, no shock, no negative
     *  active emotions — probability formula should floor at (or very near) 0.0. */
    private fun calmResident(state: com.ripple.town.core.model.WorldState): com.ripple.town.core.model.Resident {
        val r = TestWorld.resident(state, "Mara Vale")
        r.needs.stress = 0.0
        r.activeEmotions.clear()
        return r
    }

    // Extreme resident: high stress, low discipline/patience, high impulsiveness, active fear +
    // anger. `personality` itself (the birth baseline) is a `val`-field data class and shouldn't
    // be mutated directly, but `personalityModifiers` (the drift layer `effectivePersonality()`
    // composes on top of it — see Resident.effectivePersonality) is exactly the sanctioned,
    // mutable way to move a resident's *effective* traits for a test, regardless of whatever
    // birth-baseline values WorldGenerator happened to roll for this seed. Driving the modifiers
    // to their full -1.0 range and letting effectivePersonality's own clamp settle them at 0.0
    // guarantees a deterministic, worst-case-proof "extreme" resident.
    private fun extremeResident(state: com.ripple.town.core.model.WorldState): com.ripple.town.core.model.Resident {
        val r = TestWorld.resident(state, "Milo Marsh")
        r.needs.stress = 95.0
        r.personalityModifiers.discipline = -1.0
        r.personalityModifiers.patience = -1.0
        r.personalityModifiers.impulsiveness = 1.0
        check(r.effectivePersonality().discipline == 0.0)
        check(r.effectivePersonality().patience == 0.0)
        check(r.effectivePersonality().impulsiveness == 1.0)
        r.activeEmotions.clear()
        r.activeEmotions += ActiveEmotion(
            type = EmotionType.FEAR, intensity = 90.0, createdAt = state.time,
            lastTriggeredAt = state.time, decayRate = 1.0
        )
        r.activeEmotions += ActiveEmotion(
            type = EmotionType.ANGER, intensity = 80.0, createdAt = state.time,
            lastTriggeredAt = state.time, decayRate = 1.0
        )
        return r
    }

    @Test
    fun `probability is zero for a calm resident with no stress, shock, or negative emotion`() {
        val state = TestWorld.newState()
        val calm = calmResident(state)
        val probability = DecisionSystem.panicOverrideProbability(state, calm, state.time)
        assertThat(probability).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `probability never exceeds the 15 percent cap even under maximal inputs`() {
        val state = TestWorld.newState()
        val extreme = extremeResident(state)
        // Even before considering shock, sum of raw increases (stress 0.05 + fear/anger 0.04)
        // amplified up to 1.5x is well under the cap; assert the documented ceiling regardless.
        val probability = DecisionSystem.panicOverrideProbability(state, extreme, state.time)
        assertThat(probability).isAtMost(DecisionSystem.PANIC_OVERRIDE_MAX_PROBABILITY)
        assertThat(probability).isAtLeast(0.0)
    }

    @Test
    fun `probability is measurably higher for the extreme resident than the calm one`() {
        val state = TestWorld.newState()
        val calm = calmResident(state)
        val extreme = extremeResident(state)
        val calmP = DecisionSystem.panicOverrideProbability(state, calm, state.time)
        val extremeP = DecisionSystem.panicOverrideProbability(state, extreme, state.time)
        assertThat(extremeP).isGreaterThan(calmP)
        // With discipline/patience driven to 0.0 and impulsiveness to 1.0 (see extremeResident),
        // stress=95 + active FEAR(90)/ANGER(80) nets to ~0.122 by the documented formula — well
        // clear of a conservative 0.08 floor.
        assertThat(extremeP).isGreaterThan(0.08)
    }

    @Test
    fun `with probability forced to zero the top-scored action is always chosen`() {
        val state = TestWorld.newState()
        val calm = calmResident(state)
        val strong = action(ActionKind.SLEEP, score = 2.0)
        val weak = action(ActionKind.SHOP, score = 0.4)
        repeat(100) { i ->
            val ctx = TestWorld.contextFor(state, salt = i.toLong())
            val deliberation = DecisionSystem.applyPanicOverride(ctx, calm, listOf(strong, weak), strong)
            assertThat(deliberation.wasOverridden).isFalse()
            assertThat(deliberation.chosen.kind).isEqualTo(ActionKind.SLEEP)
        }
    }

    @Test
    fun `override never picks an action outside the real candidate list`() {
        val state = TestWorld.newState()
        val extreme = extremeResident(state)
        val candidates = listOf(
            action(ActionKind.SLEEP, score = 2.0),
            action(ActionKind.SHOP, score = 1.5),
            action(ActionKind.RELAX_HOME, score = 0.9)
        )
        val best = candidates.maxByOrNull { it.score }!!
        repeat(200) { i ->
            val ctx = TestWorld.contextFor(state, salt = i.toLong())
            val deliberation = DecisionSystem.applyPanicOverride(ctx, extreme, candidates, best)
            assertThat(candidates).contains(deliberation.chosen)
            if (deliberation.wasOverridden) {
                // The override always lands on the second-ranked real option, never an
                // arbitrary/nonsensical pick.
                assertThat(deliberation.chosen.kind).isEqualTo(ActionKind.SHOP)
                assertThat(deliberation.overrideReason).isNotNull()
                assertThat(deliberation.overrideReason).contains("panic overwhelmed normal judgement")
            }
        }
    }

    @Test
    fun `override fires at a measurably higher rate for an extreme resident across many ticks`() {
        val state = TestWorld.newState()
        val calm = calmResident(state)
        val extreme = extremeResident(state)
        val candidates = listOf(
            action(ActionKind.SLEEP, score = 2.0),
            action(ActionKind.SHOP, score = 1.5)
        )
        val best = candidates.maxByOrNull { it.score }!!

        val n = 4000
        var calmOverrides = 0
        var extremeOverrides = 0
        for (i in 0 until n) {
            val calmCtx = TestWorld.contextFor(state, salt = i.toLong() * 2)
            if (DecisionSystem.applyPanicOverride(calmCtx, calm, candidates, best).wasOverridden) calmOverrides++
            val extremeCtx = TestWorld.contextFor(state, salt = i.toLong() * 2 + 1)
            if (DecisionSystem.applyPanicOverride(extremeCtx, extreme, candidates, best).wasOverridden) extremeOverrides++
        }

        // Calm resident: probability is exactly 0.0, so literally zero overrides should fire.
        assertThat(calmOverrides).isEqualTo(0)

        // Extreme resident: expected rate is panicOverrideProbability(extreme). Assert the
        // observed rate over N=4000 draws lands within a generous statistical band of the
        // expected probability (loose enough to avoid flakiness, tight enough to catch a broken
        // formula or an unwired RNG roll).
        val expectedP = DecisionSystem.panicOverrideProbability(state, extreme, state.time)
        val observedRate = extremeOverrides.toDouble() / n
        assertThat(expectedP).isGreaterThan(0.0)
        assertThat(observedRate).isWithin(0.03).of(expectedP)
    }

    @Test
    fun `same seed reproduces the exact same override-or-not sequence on re-run`() {
        val state = TestWorld.newState()
        val extreme = extremeResident(state)
        val candidates = listOf(
            action(ActionKind.SLEEP, score = 2.0),
            action(ActionKind.SHOP, score = 1.5)
        )
        val best = candidates.maxByOrNull { it.score }!!

        fun sequence(): List<Boolean> = (0 until 300).map { i ->
            val ctx = TestWorld.contextFor(state, salt = i.toLong())
            DecisionSystem.applyPanicOverride(ctx, extreme, candidates, best).wasOverridden
        }

        val runA = sequence()
        val runB = sequence()
        assertThat(runB).isEqualTo(runA)
        // Sanity: the sequence isn't trivially all-false (i.e. the RNG roll is actually wired).
        assertThat(runA.any { it }).isTrue()
    }
}
