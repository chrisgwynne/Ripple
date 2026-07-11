package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Candidacy
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.Gender
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.ElectionSystem
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import org.junit.Test

/**
 * Unit tests for `ElectionSystem` — campaign accumulation mechanics.
 *
 * Exercises: callElection fires within window, runCampaigns accumulates support,
 * MAX_CAMPAIGN_ACTIONS cap, fillCouncil seats runners-up, repairChanceBonus,
 * idempotent callElection guard.
 */
class ElectionCampaignTest {

    // --------------------------------------------------------- helpers

    private fun emptyState(): WorldState = WorldState(
        seed = 1L,
        townName = "Testville",
        createdAtRealMs = 0L,
        map = TownMap(10, 10, List(100) { TileType.GRASS })
    )

    private fun addAdult(state: WorldState, id: Long, name: String, politicalInterest: Double = 0.6): Resident {
        val r = Resident(
            id = id,
            firstName = name,
            surname = "Test",
            gender = Gender.NONBINARY,
            bornAt = state.time - 30 * SimTime.MINUTES_PER_YEAR,
            homeBuildingId = null,
            householdId = null,
            detailLevel = DetailLevel.DETAILED
        )
        r.politicalInterest = politicalInterest
        r.reputation = 50.0
        state.residents[id] = r
        return r
    }

    private fun contextAt(state: WorldState, time: Long): TickContext {
        state.time = time
        return TickContext(state, SimRandom(state.seed, SimTime.tickOf(time), 0L), InMemoryEventIndex())
    }

    private fun stateWithAdults(): WorldState {
        val state = emptyState()
        state.time = SimTime.MINUTES_PER_YEAR * 2
        addAdult(state, 1L, "Alice")
        addAdult(state, 2L, "Bob")
        addAdult(state, 3L, "Charlie")
        return state
    }

    // --------------------------------------------------------- tests

    @Test fun `callElection populates candidacies within campaign window`() {
        val state = stateWithAdults()
        val voteAt = SimTime.MINUTES_PER_YEAR * 2 + SimTime.MINUTES_PER_DAY * 30
        state.nextElectionAt = voteAt
        val windowStart = voteAt - (ElectionSystem.CAMPAIGN_WINDOW_DAYS * SimTime.MINUTES_PER_DAY).toLong()
        val ctx = contextAt(state, windowStart + SimTime.MINUTES_PER_DAY)
        ElectionSystem.updateDaily(ctx)

        assertThat(state.campaignEndsAt).isNotNull()
        assertThat(state.candidacies).isNotEmpty()
        assertThat(state.candidacies.size).isAtMost(ElectionSystem.MAX_CANDIDATES)
    }

    @Test fun `callElection does not fire outside campaign window`() {
        val state = stateWithAdults()
        val voteAt = SimTime.MINUTES_PER_YEAR * 2 + SimTime.MINUTES_PER_DAY * 60
        state.nextElectionAt = voteAt
        val ctx = contextAt(state, voteAt - SimTime.MINUTES_PER_DAY * 40)
        ElectionSystem.updateDaily(ctx)

        assertThat(state.campaignEndsAt).isNull()
        assertThat(state.candidacies).isEmpty()
    }

    @Test fun `runCampaigns accumulates support across the campaign window`() {
        val state = stateWithAdults()
        val now = SimTime.MINUTES_PER_YEAR * 2L
        val voteAt = now + SimTime.MINUTES_PER_DAY * 20
        state.nextElectionAt = voteAt
        state.campaignEndsAt = voteAt
        val candidacy = Candidacy(residentId = 1L)
        state.candidacies += candidacy

        repeat(20) { day ->
            val ctx = contextAt(state, now + SimTime.MINUTES_PER_DAY * day + SimTime.MINUTES_PER_HOUR)
            ElectionSystem.updateDaily(ctx)
        }

        assertThat(candidacy.support).isGreaterThan(0.0)
        assertThat(state.residents[1L]!!.reputation).isAtLeast(50.0)
    }

    @Test fun `campaign actions capped at MAX_CAMPAIGN_ACTIONS`() {
        val state = stateWithAdults()
        val now = SimTime.MINUTES_PER_YEAR * 2L
        val voteAt = now + SimTime.MINUTES_PER_DAY * 100
        state.nextElectionAt = voteAt
        state.campaignEndsAt = voteAt
        val candidacy = Candidacy(residentId = 1L, actionsTaken = ElectionSystem.MAX_CAMPAIGN_ACTIONS)
        state.candidacies += candidacy

        repeat(30) { day ->
            val ctx = contextAt(state, now + SimTime.MINUTES_PER_DAY * day)
            ElectionSystem.updateDaily(ctx)
        }

        assertThat(candidacy.actionsTaken).isEqualTo(ElectionSystem.MAX_CAMPAIGN_ACTIONS)
    }

    @Test fun `fillCouncil seats runners-up by support after vote`() {
        val state = stateWithAdults()
        val now = SimTime.MINUTES_PER_YEAR * 2L
        val voteAt = now + SimTime.MINUTES_PER_DAY
        state.nextElectionAt = voteAt
        state.campaignEndsAt = voteAt
        state.candidacies += listOf(
            Candidacy(residentId = 1L, support = 100.0),
            Candidacy(residentId = 2L, support = 80.0),
            Candidacy(residentId = 3L, support = 60.0)
        )
        state.mayorId = 1L
        state.nextElectionAt = voteAt + SimTime.MINUTES_PER_DAY * 365

        val ctx = contextAt(state, voteAt + SimTime.MINUTES_PER_HOUR)
        ElectionSystem.updateDaily(ctx)

        assertThat(state.councillorIds).hasSize(ElectionSystem.COUNCIL_SEATS)
        assertThat(state.councillorIds).doesNotContain(1L)
        assertThat(state.councillorIds).containsAtLeast(2L, 3L)
        assertThat(state.campaignEndsAt).isNull()
        assertThat(state.candidacies).isEmpty()
    }

    @Test fun `repairChanceBonus is zero without mayor, nonzero with mayor`() {
        val state = emptyState()
        state.mayorId = null
        assertThat(ElectionSystem.repairChanceBonus(state)).isEqualTo(0.0)

        state.mayorId = 1L
        assertThat(ElectionSystem.repairChanceBonus(state)).isEqualTo(ElectionSystem.MAYORAL_REPAIR_CHANCE_BONUS)
    }

    @Test fun `callElection is idempotent — does not reset running campaign`() {
        val state = stateWithAdults()
        val now = SimTime.MINUTES_PER_YEAR * 2L
        val voteAt = now + SimTime.MINUTES_PER_DAY * 20
        state.nextElectionAt = voteAt
        state.campaignEndsAt = voteAt
        state.candidacies += Candidacy(residentId = 1L, support = 42.0)

        val ctx = contextAt(state, voteAt - SimTime.MINUTES_PER_DAY * 5)
        ElectionSystem.updateDaily(ctx)

        assertThat(state.candidacies).hasSize(1)
        assertThat(state.candidacies.first().support).isEqualTo(42.0)
    }
}
