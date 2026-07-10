package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Resident
import com.ripple.town.core.simulation.RelationshipInterpretationSystem
import org.junit.Test

/**
 * Covers `RelationshipInterpretationSystem` — the personality-shaped replacement for
 * `InteractionSystem`'s previously-flat relationship-kind thresholds (rival, partner
 * break-up, spousal separation, divorce). See `docs/simulation-rules.md#relationships`
 * for the formula writeup and the "less-tolerant-party-governs" rule this exercises.
 */
class RelationshipInterpretationSystemTest {

    /** Builds a resident whose *effective* personality is exactly [personality] — this test
     *  cares about effectivePersonality() (birth + drift), so it's simplest to set the birth
     *  baseline directly and leave modifiers at their zero default. */
    private fun residentWith(id: Long, personality: Personality): Resident =
        TestWorld.newState().let { state ->
            val template = state.residents.values.first()
            Resident(
                id = id,
                firstName = "Test",
                surname = "Resident$id",
                gender = template.gender,
                bornAt = template.bornAt,
                homeBuildingId = template.homeBuildingId,
                householdId = template.householdId,
                personality = personality
            )
        }

    private val forgiving = Personality(
        empathy = 0.95, patience = 0.95, impulsiveness = 0.05, honesty = 0.95,
        kindness = 0.5, ambition = 0.5, curiosity = 0.5, sociability = 0.5, courage = 0.5, discipline = 0.5
    )

    private val volatile = Personality(
        empathy = 0.05, patience = 0.05, impulsiveness = 0.95, honesty = 0.05,
        kindness = 0.5, ambition = 0.5, curiosity = 0.5, sociability = 0.5, courage = 0.5, discipline = 0.5
    )

    private val neutral = Personality() // all defaults, 0.5

    @Test
    fun `a high-empathy high-patience resident has a measurably higher resentment tolerance`() {
        val patient = residentWith(1, forgiving)
        val impulsive = residentWith(2, volatile)
        val patientPeer = residentWith(3, forgiving)
        val impulsivePeer = residentWith(4, volatile)

        // Same-kind pairs isolate each side's own tolerance (neither party is the
        // "less tolerant" outlier dragging the pair threshold down/up).
        val patientPairThreshold = RelationshipInterpretationSystem.rivalResentmentThresholdFor(patient, patientPeer)
        val impulsivePairThreshold = RelationshipInterpretationSystem.rivalResentmentThresholdFor(impulsive, impulsivePeer)

        assertThat(patientPairThreshold).isGreaterThan(impulsivePairThreshold)
    }

    @Test
    fun `a high-empathy high-patience resident has a measurably lower affection floor`() {
        val patient = residentWith(1, forgiving)
        val patientPeer = residentWith(2, forgiving)
        val impulsive = residentWith(3, volatile)
        val impulsivePeer = residentWith(4, volatile)

        val patientFloor = RelationshipInterpretationSystem.rivalAffectionFloorFor(patient, patientPeer)
        val impulsiveFloor = RelationshipInterpretationSystem.rivalAffectionFloorFor(impulsive, impulsivePeer)

        // A more tolerant pair's affection has to fall further (lower floor) before it reads
        // as "too low to save" than a volatile pair's (higher floor, crossed sooner).
        assertThat(patientFloor).isLessThan(impulsiveFloor)
    }

    @Test
    fun `the less tolerant of the pair governs the rival resentment threshold`() {
        val patient = residentWith(1, forgiving)
        val impulsive = residentWith(2, volatile)

        val mixedThreshold = RelationshipInterpretationSystem.rivalResentmentThresholdFor(patient, impulsive)
        val impulsiveSoloThreshold = RelationshipInterpretationSystem.rivalResentmentThresholdFor(impulsive, impulsive)

        // The mixed pair's threshold should match the impulsive resident's own (lower) threshold,
        // not be dragged upward by the patient party.
        assertThat(mixedThreshold).isWithin(1e-9).of(impulsiveSoloThreshold)
    }

    @Test
    fun `the less tolerant of the pair governs the rival affection floor`() {
        val patient = residentWith(1, forgiving)
        val impulsive = residentWith(2, volatile)

        val mixedFloor = RelationshipInterpretationSystem.rivalAffectionFloorFor(patient, impulsive)
        val impulsiveSoloFloor = RelationshipInterpretationSystem.rivalAffectionFloorFor(impulsive, impulsive)

        // A higher affection floor is crossed sooner by falling affection, so the volatile
        // resident's (higher) floor should govern the mixed pair, not the patient one.
        assertThat(mixedFloor).isWithin(1e-9).of(impulsiveSoloFloor)
    }

    @Test
    fun `thresholds stay within the bounded clamp range even at extreme personality values`() {
        val extremeForgiving = Personality(
            empathy = 1.0, patience = 1.0, impulsiveness = 0.0, honesty = 1.0,
            kindness = 1.0, ambition = 1.0, curiosity = 1.0, sociability = 1.0, courage = 1.0, discipline = 1.0
        )
        val extremeVolatile = Personality(
            empathy = 0.0, patience = 0.0, impulsiveness = 1.0, honesty = 0.0,
            kindness = 0.0, ambition = 0.0, curiosity = 0.0, sociability = 0.0, courage = 0.0, discipline = 0.0
        )
        val a = residentWith(1, extremeForgiving)
        val b = residentWith(2, extremeVolatile)

        val maxSwing = 18.0

        fun assertWithinSwing(value: Double, base: Double) {
            assertThat(value).isAtLeast(base - maxSwing)
            assertThat(value).isAtMost(base + maxSwing)
        }

        assertWithinSwing(RelationshipInterpretationSystem.rivalResentmentThresholdFor(a, a), 55.0)
        assertWithinSwing(RelationshipInterpretationSystem.rivalAffectionFloorFor(a, b), 30.0)
        assertWithinSwing(RelationshipInterpretationSystem.partnerBreakupResentmentThresholdFor(a, b), 60.0)
        assertWithinSwing(RelationshipInterpretationSystem.partnerBreakupAffectionFloorFor(a, b), 30.0)
        assertWithinSwing(RelationshipInterpretationSystem.spouseSeparationResentmentThresholdFor(a, b), 72.0)
        assertWithinSwing(RelationshipInterpretationSystem.spouseSeparationAffectionFloorFor(a, b), 25.0)
        assertWithinSwing(RelationshipInterpretationSystem.divorceResentmentThresholdFor(a, b), 60.0)
    }

    @Test
    fun `neutral default personalities reproduce a threshold close to the original flat constant`() {
        val a = residentWith(1, neutral)
        val b = residentWith(2, neutral)

        // At exactly the 0.5 midpoint for every trait, both the forgiveness and volatility
        // terms are zero, so the result should equal the original flat base value exactly.
        assertThat(RelationshipInterpretationSystem.rivalResentmentThresholdFor(a, b)).isWithin(1e-9).of(55.0)
        assertThat(RelationshipInterpretationSystem.rivalAffectionFloorFor(a, b)).isWithin(1e-9).of(30.0)
        assertThat(RelationshipInterpretationSystem.partnerBreakupResentmentThresholdFor(a, b)).isWithin(1e-9).of(60.0)
        assertThat(RelationshipInterpretationSystem.partnerBreakupAffectionFloorFor(a, b)).isWithin(1e-9).of(30.0)
        assertThat(RelationshipInterpretationSystem.spouseSeparationResentmentThresholdFor(a, b)).isWithin(1e-9).of(72.0)
        assertThat(RelationshipInterpretationSystem.spouseSeparationAffectionFloorFor(a, b)).isWithin(1e-9).of(25.0)
        assertThat(RelationshipInterpretationSystem.divorceResentmentThresholdFor(a, b)).isWithin(1e-9).of(60.0)
    }

    @Test
    fun `threshold computation is deterministic across repeated calls`() {
        val a = residentWith(1, forgiving)
        val b = residentWith(2, volatile)

        val first = RelationshipInterpretationSystem.rivalResentmentThresholdFor(a, b)
        val second = RelationshipInterpretationSystem.rivalResentmentThresholdFor(a, b)
        val third = RelationshipInterpretationSystem.rivalResentmentThresholdFor(a, b)

        assertThat(first).isEqualTo(second)
        assertThat(second).isEqualTo(third)

        // Also deterministic across a freshly constructed but identical resident pair —
        // proves this is a pure function of personality state, not tied to object identity
        // or any hidden mutable/random state.
        val aAgain = residentWith(1, forgiving)
        val bAgain = residentWith(2, volatile)
        val recomputed = RelationshipInterpretationSystem.rivalResentmentThresholdFor(aAgain, bAgain)
        assertThat(recomputed).isEqualTo(first)
    }
}
