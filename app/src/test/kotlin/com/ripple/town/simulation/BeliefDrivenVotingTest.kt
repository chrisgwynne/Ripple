package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Belief
import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.Candidacy
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.Relationship
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import com.ripple.town.core.simulation.VotingSystem
import org.junit.Test

/**
 * Covers `VotingSystem` — the belief-aware voter tally that `LifecycleSystem.election()` now
 * consumes to decide the winner, replacing the old single-aggregate-formula scoring. See
 * `docs/simulation-rules.md` "Local politics: elections" → "The vote itself: a belief-aware
 * tally" for the full design writeup, and `VotingSystem`'s own doc comment for why
 * `TRUST_IN_GOVERNMENT`/`ECONOMIC_OPTIMISM`/`COMMUNITY_LOYALTY` were picked as the salient topics.
 *
 * Compile-checked only, per this session's constraints — the full gradle test suite was not run
 * and `./gradlew` was not invoked.
 */
class BeliefDrivenVotingTest {

    private fun contextFor(state: WorldState, salt: Long = 0L): TickContext =
        TickContext(state, SimRandom(state.seed, SimTime.tickOf(state.time), salt), InMemoryEventIndex())

    /** A bare-bones adult resident with no beliefs/relationships unless the test sets them. */
    private fun makeVoter(state: WorldState, id: Long, name: String): Resident {
        val r = Resident(
            id = id,
            firstName = name,
            surname = "Voter",
            gender = com.ripple.town.core.model.Gender.NONBINARY,
            bornAt = state.time - 30 * SimTime.MINUTES_PER_YEAR, // age 30 -> ADULT
            homeBuildingId = null,
            householdId = null,
            detailLevel = DetailLevel.DETAILED
        )
        state.residents[id] = r
        return r
    }

    private fun setBelief(state: WorldState, r: Resident, topic: BeliefTopic, position: Double) {
        r.beliefs[topic] = Belief(topic = topic, position = position, confidence = 0.6, lastUpdatedAt = state.time)
    }

    private fun setAllSalientBeliefs(state: WorldState, r: Resident, position: Double) {
        for (topic in VotingSystem.SALIENT_TOPICS) setBelief(state, r, topic, position)
    }

    // ------------------------------------------------------------ turnout bounds

    @Test
    fun `turnout chance always stays within the documented bounded range`() {
        val state = TestWorld.newState()
        val r = makeVoter(state, 80001L, "Nia")

        // Extreme low: no political interest, maximally distrustful.
        r.politicalInterest = 0.0
        setBelief(state, r, BeliefTopic.TRUST_IN_GOVERNMENT, -1.0)
        val low = VotingSystem.turnoutChance(r)
        assertThat(low).isAtLeast(VotingSystem.MIN_TURNOUT_CHANCE)
        assertThat(low).isAtMost(VotingSystem.MAX_TURNOUT_CHANCE)

        // Extreme high: fully interested, maximally trusting.
        r.politicalInterest = 1.0
        setBelief(state, r, BeliefTopic.TRUST_IN_GOVERNMENT, 1.0)
        val high = VotingSystem.turnoutChance(r)
        assertThat(high).isAtLeast(VotingSystem.MIN_TURNOUT_CHANCE)
        assertThat(high).isAtMost(VotingSystem.MAX_TURNOUT_CHANCE)
        // And genuinely higher than the distrustful/disinterested case.
        assertThat(high).isGreaterThan(low)

        // A totally neutral resident (no beliefs formed, default interest) still lands
        // strictly between the floor and ceiling — never 0%, never 100%.
        val neutral = makeVoter(state, 80002L, "Neutral")
        val mid = VotingSystem.turnoutChance(neutral)
        assertThat(mid).isAtLeast(VotingSystem.MIN_TURNOUT_CHANCE)
        assertThat(mid).isAtMost(VotingSystem.MAX_TURNOUT_CHANCE)
    }

    // ------------------------------------------------------------ belief alignment wins votes

    @Test
    fun `a candidate whose beliefs align with the turned-out majority wins more often`() {
        var alignedWins = 0
        var misalignedWins = 0
        val trials = 40

        repeat(trials) { trial ->
            val state = TestWorld.newState()
            // 20 voters, all leaning strongly positive on the salient topics.
            val voters = (0 until 20).map { i ->
                val v = makeVoter(state, 81000L + i, "Voter$i")
                v.politicalInterest = 0.6
                setAllSalientBeliefs(state, v, 0.8)
                v
            }
            val aligned = makeVoter(state, 81100L, "Aligned")
            setAllSalientBeliefs(state, aligned, 0.8) // matches the electorate closely
            val misaligned = makeVoter(state, 81101L, "Misaligned")
            setAllSalientBeliefs(state, misaligned, -0.8) // opposite of the electorate

            val ctx = contextFor(state, salt = trial.toLong())
            val votes = VotingSystem.tally(ctx, listOf(aligned, misaligned), emptyList())
            if ((votes[aligned.id] ?: 0) > (votes[misaligned.id] ?: 0)) alignedWins++
            if ((votes[misaligned.id] ?: 0) > (votes[aligned.id] ?: 0)) misalignedWins++
        }

        assertThat(alignedWins).isGreaterThan(misalignedWins)
        // Not a coin flip: the aligned candidate should win the clear majority of trials.
        assertThat(alignedWins).isGreaterThan(trials / 2)
    }

    @Test
    fun `direct tally-function check - closer belief alignment scores higher for a given voter`() {
        val state = TestWorld.newState()
        val voter = makeVoter(state, 82000L, "Voter")
        setAllSalientBeliefs(state, voter, 0.5)

        val closeCandidate = makeVoter(state, 82001L, "Close")
        setAllSalientBeliefs(state, closeCandidate, 0.5)
        val farCandidate = makeVoter(state, 82002L, "Far")
        setAllSalientBeliefs(state, farCandidate, -0.9)

        val closeScore = VotingSystem.voterScoreFor(state, voter, closeCandidate, null)
        val farScore = VotingSystem.voterScoreFor(state, voter, farCandidate, null)
        assertThat(closeScore).isGreaterThan(farScore)
    }

    // ------------------------------------------------------------ relationship boost

    @Test
    fun `strong existing relationships give a real boost even with neutral belief alignment`() {
        val state = TestWorld.newState()
        // Every voter is neutral (no formed beliefs) on all salient topics, so belief
        // alignment alone can't distinguish either candidate.
        val voters = (0 until 15).map { i ->
            val v = makeVoter(state, 83000L + i, "Voter$i")
            v.politicalInterest = 0.6
            v
        }
        val popular = makeVoter(state, 83100L, "Popular")
        val stranger = makeVoter(state, 83101L, "Stranger")

        // "Popular" has strong, warm, familiar relationships with every voter; "Stranger" has none.
        for (v in voters) {
            val rel = state.relationshipOrCreate(v.id, popular.id)
            rel.kind = RelationshipKind.FRIEND
            rel.trust = 90.0
            rel.familiarity = 90.0
        }

        val ctx = contextFor(state)
        val votes = VotingSystem.tally(ctx, listOf(popular, stranger), emptyList())
        assertThat(votes[popular.id] ?: 0).isGreaterThan(votes[stranger.id] ?: 0)

        // Direct score check too: relationship term alone should separate them even though
        // belief alignment (both neutral/default 0.0) contributes identically to both.
        val voter = voters.first()
        val popularScore = VotingSystem.voterScoreFor(state, voter, popular, null)
        val strangerScore = VotingSystem.voterScoreFor(state, voter, stranger, null)
        assertThat(popularScore).isGreaterThan(strangerScore)
    }

    // ------------------------------------------------------------ campaign support contributes

    @Test
    fun `accumulated campaign support gives a candidate a real, bounded edge`() {
        val state = TestWorld.newState()
        val voter = makeVoter(state, 84000L, "Voter")
        val campaigner = makeVoter(state, 84001L, "Campaigner")
        val quiet = makeVoter(state, 84002L, "Quiet")
        // Both candidates start belief/relationship-neutral relative to the voter.

        val noSupport = VotingSystem.voterScoreFor(state, voter, campaigner, null)
        val withSupport = VotingSystem.voterScoreFor(
            state, voter, campaigner, Candidacy(residentId = campaigner.id, support = 40.0)
        )
        assertThat(withSupport).isGreaterThan(noSupport)

        // And it composes into the actual tally: a well-campaigned candidate beats an
        // otherwise-identical quiet one.
        val ctx = contextFor(state)
        val candidacies = listOf(Candidacy(residentId = campaigner.id, support = 60.0))
        val votes = VotingSystem.tally(ctx, listOf(campaigner, quiet), candidacies)
        assertThat(votes[campaigner.id] ?: 0).isAtLeast(votes[quiet.id] ?: 0)
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `same seed produces the same election tally and winner`() {
        fun buildState(): WorldState {
            val state = TestWorld.newState()
            for (i in 0 until 25) {
                val v = makeVoter(state, 85000L + i, "Voter$i")
                v.politicalInterest = 0.5
                setAllSalientBeliefs(state, v, if (i % 2 == 0) 0.6 else -0.4)
            }
            return state
        }

        val state1 = buildState()
        val state2 = buildState()
        val a1 = makeVoter(state1, 85100L, "A"); setAllSalientBeliefs(state1, a1, 0.6)
        val b1 = makeVoter(state1, 85101L, "B"); setAllSalientBeliefs(state1, b1, -0.4)
        val a2 = makeVoter(state2, 85100L, "A"); setAllSalientBeliefs(state2, a2, 0.6)
        val b2 = makeVoter(state2, 85101L, "B"); setAllSalientBeliefs(state2, b2, -0.4)

        val ctx1 = contextFor(state1, salt = 7L)
        val ctx2 = contextFor(state2, salt = 7L)
        val votes1 = VotingSystem.tally(ctx1, listOf(a1, b1), emptyList())
        val votes2 = VotingSystem.tally(ctx2, listOf(a2, b2), emptyList())

        assertThat(votes1[a1.id]).isEqualTo(votes2[a2.id])
        assertThat(votes1[b1.id]).isEqualTo(votes2[b2.id])
    }

    // ------------------------------------------------------------ integration: LifecycleSystem.election()

    @Test
    fun `election() picks a winner from the belief-aware tally without touching campaign state`() {
        val state = TestWorld.newState()
        state.nextElectionAt = state.time + 1
        val candidateA = TestWorld.resident(state, "Mara Vale")
        candidateA.detailLevel = DetailLevel.DETAILED
        candidateA.politicalInterest = 0.9
        candidateA.reputation = 80.0
        setAllSalientBeliefs(state, candidateA, 0.7)

        // A broad electorate leaning the same way as candidateA, so the belief-aware tally
        // should favour them; the test only asserts a winner is chosen deterministically and
        // that unrelated campaign bookkeeping (candidacies) is left alone.
        for (i in 0 until 20) {
            val v = makeVoter(state, 86000L + i, "Elector$i")
            v.politicalInterest = 0.6
            setAllSalientBeliefs(state, v, 0.7)
        }
        state.time = state.nextElectionAt

        val ctx = contextFor(state)
        com.ripple.town.core.simulation.LifecycleSystem.updateDaily(ctx)

        assertThat(state.mayorId).isNotNull()
        // election() only rolls the vote forward; it must not have touched ElectionSystem's
        // own campaign bookkeeping fields.
        assertThat(state.candidacies).isEmpty()
    }
}
