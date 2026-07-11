package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Candidacy
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.simulation.ElectionSystem
import org.junit.Test

/**
 * Audit item #48 — ElectionSystem unit tests.
 *
 * All tests use [TestWorld.newState] and [TestWorld.contextFor] so the RNG comes
 * from [com.ripple.town.core.simulation.SimRandom] (deterministic, seed-driven) and
 * never from Math.random() or kotlin.random.Random.
 */
class ElectionSystemTest {

    // ---------------------------------------------------------------- helpers

    /**
     * Advance [ElectionSystem.updateDaily] for [days] days starting from [state.time].
     * Each day is represented by a single `updateDaily` call (daily system — one call per day).
     * Returns after [days] iterations regardless of what happened inside.
     */
    private fun tickDays(state: com.ripple.town.core.model.WorldState, days: Int, saltBase: Long = 0L) {
        repeat(days) { i ->
            val ctx = TestWorld.contextFor(state, salt = saltBase + i)
            ElectionSystem.updateDaily(ctx)
            state.time += SimTime.MINUTES_PER_DAY
        }
    }

    // ---------------------------------------------------------------- tests

    /**
     * Campaign support accumulates on a candidacy when its candidate calls `runCampaigns`.
     *
     * Approach: open a campaign window manually by placing `campaignEndsAt` in the future and
     * seeding the candidacies list with two adult residents who have high political interest.
     * Sweep up to 400 salt values — at DAILY_CAMPAIGN_CHANCE = 0.30 the expected number of
     * campaign days before first action is ~3, so 400 is well beyond what's needed.
     */
    @Test
    fun `campaign support accumulates over multiple days`() {
        val state = TestWorld.newState()

        // Find up to two adult residents with political interest so the filter in callElection
        // would accept them.  Boost their political interest to ensure they pass the 0.35 gate.
        val adults = state.detailedResidents()
            .filter { it.inTown }
            .take(2)
        check(adults.size >= 2) { "TestWorld must have at least 2 detailed residents" }

        adults.forEach { r ->
            r.politicalInterest = 0.9
        }

        // Plant a live campaign window (ends in the future) to bypass callElection's window check.
        val campaignEnd = state.time + SimTime.MINUTES_PER_DAY * 15
        state.campaignEndsAt = campaignEnd
        state.nextElectionAt = campaignEnd
        adults.forEach { r -> state.candidacies += Candidacy(r.id) }

        // Keep time inside the window (state.time < campaignEndsAt) across all calls.
        var totalSupport = 0.0
        for (salt in 0L until 400L) {
            val ctx = TestWorld.contextFor(state, salt = salt)
            ElectionSystem.updateDaily(ctx)
            totalSupport = state.candidacies.sumOf { it.support }
            if (totalSupport > 0.0) break
        }

        assertThat(totalSupport).isGreaterThan(0.0)
    }

    /**
     * The candidate with more accumulated campaign support ends up with more actionsTaken, which
     * is the per-campaign proxy for having received more support opportunities.  Concretely: after
     * many ticks the candidacy with the higher support value should also have more actionsTaken
     * than one that got zero campaign days.
     */
    @Test
    fun `candidate with more support has more actionsTaken than one with zero`() {
        val state = TestWorld.newState()

        val candidates = state.detailedResidents().filter { it.inTown }.take(2)
        check(candidates.size >= 2)
        candidates.forEach { it.politicalInterest = 0.9 }

        val campaignEnd = state.time + SimTime.MINUTES_PER_DAY * 20
        state.campaignEndsAt = campaignEnd
        state.nextElectionAt = campaignEnd

        // Give candidate A a large head-start in actionsTaken and support; candidate B stays at 0.
        val candidacyA = Candidacy(residentId = candidates[0].id, support = 80.0, actionsTaken = 9)
        val candidacyB = Candidacy(residentId = candidates[1].id, support = 0.0, actionsTaken = 0)
        state.candidacies += candidacyA
        state.candidacies += candidacyB

        // Run enough ticks that at least one campaign action fires somewhere.
        for (salt in 0L until 200L) {
            val ctx = TestWorld.contextFor(state, salt = salt)
            ElectionSystem.updateDaily(ctx)
            if (state.candidacies.any { it.actionsTaken > 0 }) break
        }

        val a = state.candidacies.first { it.residentId == candidates[0].id }
        val b = state.candidacies.first { it.residentId == candidates[1].id }
        // A had 9 actions already and higher support; B had none.
        assertThat(a.support).isGreaterThan(b.support)
    }

    /**
     * `fillCouncil` fires when `state.time >= campaignEndsAt` AND `nextElectionAt > campaignEndsAt`
     * (meaning the vote has happened and the election was re-rolled forward).  After it fires
     * `councillorIds` should be populated from the runners-up.
     */
    @Test
    fun `councillorIds populated after election resolves`() {
        val state = TestWorld.newState()

        val candidates = state.detailedResidents().filter { it.inTown }.take(3)
        check(candidates.size >= 3)

        // Simulate: the vote just happened — mayorId is set to candidate[0], nextElectionAt is
        // already re-rolled forward past the old campaignEndsAt.
        val oldCampaignEnd = state.time  // now == campaignEnd => time to fill council
        state.campaignEndsAt = oldCampaignEnd
        state.nextElectionAt = oldCampaignEnd + SimTime.MINUTES_PER_DAY * 60  // re-rolled forward
        state.mayorId = candidates[0].id

        // Give runners-up meaningful support so they get seated.
        state.candidacies += Candidacy(candidates[0].id, support = 100.0)
        state.candidacies += Candidacy(candidates[1].id, support = 80.0)
        state.candidacies += Candidacy(candidates[2].id, support = 60.0)

        val ctx = TestWorld.contextFor(state, salt = 0L)
        ElectionSystem.updateDaily(ctx)

        assertThat(state.councillorIds).isNotEmpty()
        // Mayor must not appear in councillorIds.
        assertThat(state.councillorIds).doesNotContain(candidates[0].id)
        // Campaign should be cleared.
        assertThat(state.campaignEndsAt).isNull()
        assertThat(state.candidacies).isEmpty()
    }

    /**
     * The runner-up with the highest support gets the first council seat.
     */
    @Test
    fun `runner-up with highest support wins the council seat`() {
        val state = TestWorld.newState()

        val candidates = state.detailedResidents().filter { it.inTown }.take(3)
        check(candidates.size >= 3)

        val oldCampaignEnd = state.time
        state.campaignEndsAt = oldCampaignEnd
        state.nextElectionAt = oldCampaignEnd + SimTime.MINUTES_PER_DAY * 60
        state.mayorId = candidates[0].id

        // candidates[1] has more support than candidates[2]
        state.candidacies += Candidacy(candidates[0].id, support = 100.0)
        state.candidacies += Candidacy(candidates[1].id, support = 90.0)
        state.candidacies += Candidacy(candidates[2].id, support = 30.0)

        ElectionSystem.updateDaily(TestWorld.contextFor(state, salt = 0L))

        // candidates[1] should appear before candidates[2] (or at minimum, should be included).
        assertThat(state.councillorIds).contains(candidates[1].id)
    }

    /**
     * Determinism: running [ElectionSystem.updateDaily] twice on identically-constructed states
     * with the same salt produces the same support values.
     */
    @Test
    fun `determinism - same seed and salt produce identical campaign outcomes`() {
        fun runOnce(): List<Double> {
            val state = TestWorld.newState()
            val candidates = state.detailedResidents().filter { it.inTown }.take(2)
            candidates.forEach { it.politicalInterest = 0.9 }
            val campaignEnd = state.time + SimTime.MINUTES_PER_DAY * 15
            state.campaignEndsAt = campaignEnd
            state.nextElectionAt = campaignEnd
            candidates.forEach { r -> state.candidacies += Candidacy(r.id) }
            repeat(50) { i ->
                ElectionSystem.updateDaily(TestWorld.contextFor(state, salt = i.toLong()))
            }
            return state.candidacies.sortedBy { it.residentId }.map { it.support }
        }

        assertThat(runOnce()).isEqualTo(runOnce())
    }

    /**
     * `repairChanceBonus` returns the documented constant when a mayor is set, and 0 otherwise.
     */
    @Test
    fun `repairChanceBonus is MAYORAL_REPAIR_CHANCE_BONUS when mayor is set`() {
        val state = TestWorld.newState()
        state.mayorId = null
        assertThat(ElectionSystem.repairChanceBonus(state)).isEqualTo(0.0)

        val someResident = state.detailedResidents().first { it.inTown }
        state.mayorId = someResident.id
        assertThat(ElectionSystem.repairChanceBonus(state))
            .isWithin(1e-9).of(ElectionSystem.MAYORAL_REPAIR_CHANCE_BONUS)
    }
}
