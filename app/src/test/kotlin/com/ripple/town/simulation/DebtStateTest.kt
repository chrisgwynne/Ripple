package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Household
import com.ripple.town.core.simulation.DebtState
import com.ripple.town.core.simulation.DebtSystem
import org.junit.Test

/**
 * Covers `DebtSystem.classify` — see `docs/simulation-rules.md` "Debt states" for the full
 * boundary write-up this test mirrors. Uses a real resident pulled from `TestWorld.newState()`
 * (same pattern as `GoalAndEconomyTest`) so every field the classifier reads (childIds,
 * employmentId, lifeStageAt) is a genuine, fully-initialised Resident rather than a hand-rolled
 * partial stub.
 */
class DebtStateTest {

    private fun freshResident(): com.ripple.town.core.model.Resident {
        val state = TestWorld.newState()
        return TestWorld.resident(state, "Ash Thistle")
    }

    @Test
    fun `zero or negative debt is NONE`() {
        val r = freshResident()
        r.debt = 0.0
        assertThat(DebtSystem.classify(r, null)).isEqualTo(DebtState.NONE)
    }

    @Test
    fun `trivial debt is MANAGEABLE regardless of wealth`() {
        val r = freshResident()
        r.debt = 10.0
        r.wealth = 0.0
        assertThat(DebtSystem.classify(r, null)).isEqualTo(DebtState.MANAGEABLE)
    }

    @Test
    fun `same debt classifies differently for high vs low wealth`() {
        val rich = freshResident()
        rich.debt = 1500.0
        rich.wealth = 10_000.0
        val richState = DebtSystem.classify(rich, null)

        val poor = freshResident()
        poor.debt = 1500.0
        poor.wealth = 200.0
        poor.employmentId = null
        val poorState = DebtSystem.classify(poor, null)

        // Same raw debt, very different means -> must not classify the same way.
        assertThat(richState).isNotEqualTo(poorState)
        assertThat(richState).isEqualTo(DebtState.MANAGEABLE)
        assertThat(poorState.ordinal).isAtLeast(DebtState.STRAINED.ordinal)
    }

    @Test
    fun `moderate ratio without hardship signals stays ELEVATED not STRAINED`() {
        val r = freshResident()
        r.debt = 250.0
        r.wealth = 100.0 // ratio 2.5, above ELEVATED_RATIO
        r.employmentId = 99L // has income
        r.childIds.clear()
        val household = Household(id = 1L, name = "Test House", homeBuildingId = null, savings = 5_000.0)
        assertThat(DebtSystem.classify(r, household)).isEqualTo(DebtState.ELEVATED)
    }

    @Test
    fun `moderate ratio with no income becomes STRAINED`() {
        val r = freshResident()
        r.debt = 250.0
        r.wealth = 100.0 // ratio 2.5
        r.employmentId = null
        r.childIds.clear()
        val household = Household(id = 1L, name = "Test House", homeBuildingId = null, savings = 5_000.0)
        assertThat(DebtSystem.classify(r, household)).isEqualTo(DebtState.STRAINED)
    }

    @Test
    fun `moderate ratio with many dependants becomes STRAINED`() {
        val r = freshResident()
        r.debt = 250.0
        r.wealth = 100.0
        r.employmentId = 99L
        r.childIds.clear()
        r.childIds += listOf(1L, 2L)
        assertThat(DebtSystem.classify(r, null)).isEqualTo(DebtState.STRAINED)
    }

    @Test
    fun `moderate ratio with thin household savings becomes STRAINED`() {
        val r = freshResident()
        r.debt = 250.0
        r.wealth = 100.0
        r.employmentId = 99L
        r.childIds.clear()
        val household = Household(id = 1L, name = "Test House", homeBuildingId = null, savings = 10.0)
        assertThat(DebtSystem.classify(r, household)).isEqualTo(DebtState.STRAINED)
    }

    @Test
    fun `debt exactly at the crisis threshold is not yet CRISIS`() {
        val r = freshResident()
        r.debt = com.ripple.town.core.simulation.EconomySystem.DEBT_CRISIS_THRESHOLD
        r.wealth = 500.0
        // classify() uses a strict > against the threshold, matching the old flat-check semantics.
        assertThat(DebtSystem.classify(r, null)).isNotEqualTo(DebtState.CRISIS)
    }

    @Test
    fun `debt just past the crisis threshold with modest means is CRISIS`() {
        val r = freshResident()
        r.debt = com.ripple.town.core.simulation.EconomySystem.DEBT_CRISIS_THRESHOLD + 100.0
        r.wealth = 1_000.0
        val household = Household(id = 1L, name = "Test House", homeBuildingId = null, savings = 500.0)
        assertThat(DebtSystem.classify(r, household)).isEqualTo(DebtState.CRISIS)
    }

    @Test
    fun `debt past the insolvent floor is INSOLVENT even with some wealth`() {
        val r = freshResident()
        r.debt = DebtSystem.INSOLVENT_DEBT_FLOOR + 500.0
        r.wealth = 3_000.0
        assertThat(DebtSystem.classify(r, null)).isEqualTo(DebtState.INSOLVENT)
    }

    @Test
    fun `crisis-tier debt that dwarfs total means is INSOLVENT even below the flat floor`() {
        val r = freshResident()
        r.debt = com.ripple.town.core.simulation.EconomySystem.DEBT_CRISIS_THRESHOLD + 200.0 // below INSOLVENT_DEBT_FLOOR
        r.wealth = 10.0
        val household = Household(id = 1L, name = "Test House", homeBuildingId = null, savings = 0.0)
        assertThat(DebtSystem.classify(r, household)).isEqualTo(DebtState.INSOLVENT)
    }

    @Test
    fun `crisis-tier debt with strong means stays CRISIS not INSOLVENT`() {
        val r = freshResident()
        r.debt = com.ripple.town.core.simulation.EconomySystem.DEBT_CRISIS_THRESHOLD + 200.0
        r.wealth = 5_000.0
        val household = Household(id = 1L, name = "Test House", homeBuildingId = null, savings = 2_000.0)
        assertThat(DebtSystem.classify(r, household)).isEqualTo(DebtState.CRISIS)
    }

    @Test
    fun `every state is deterministic across repeated calls`() {
        val r = freshResident()
        r.debt = 3_500.0
        r.wealth = 400.0
        val household = Household(id = 1L, name = "Test House", homeBuildingId = null, savings = 100.0)
        val first = DebtSystem.classify(r, household)
        repeat(20) {
            assertThat(DebtSystem.classify(r, household)).isEqualTo(first)
        }
    }

    @Test
    fun `edge cases never throw - zero wealth zero debt no household`() {
        val r = freshResident()
        r.debt = 0.0
        r.wealth = 0.0
        assertThat(DebtSystem.classify(r, null)).isEqualTo(DebtState.NONE)

        r.debt = 100_000.0
        r.wealth = 0.0
        assertThat(DebtSystem.classify(r, null)).isEqualTo(DebtState.INSOLVENT)

        r.debt = 500.0
        r.wealth = -50.0 // shouldn't naturally happen (EconomySystem clamps to 0) but must not throw
        DebtSystem.classify(r, null)

        r.wealth = 500.0
        r.debt = -20.0 // also shouldn't naturally happen but must not throw and must read as NONE
        assertThat(DebtSystem.classify(r, null)).isEqualTo(DebtState.NONE)
    }

    @Test
    fun `no household defaults means to personal wealth alone`() {
        val r = freshResident()
        r.debt = DebtSystem.INSOLVENT_DEBT_FLOOR - 1.0
        r.wealth = 0.0
        // Crisis-tier debt, no wealth, no household -> means is zero -> INSOLVENT via the
        // means-multiple test even though raw debt is just under the flat floor.
        assertThat(DebtSystem.classify(r, null)).isEqualTo(DebtState.INSOLVENT)
    }
}
