package com.ripple.town.core.simulation

import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.Candidacy
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldState

/**
 * The genuine, belief-aware voter tally consumed by `LifecycleSystem.election()` at the moment
 * the vote is decided. Extracted into its own file (rather than living as private helpers on
 * `LifecycleSystem`) because it is a self-contained, independently testable piece of logic with
 * its own constants and no other coupling to the rest of `LifecycleSystem`.
 *
 * Replaces the old single-aggregate-formula scoring (`reputation + skill*0.6 + flat random noise`)
 * with real per-voter turnout and choice — closing the Simulation Reality Review's finding that
 * *"Elections are a stat calculation; no belief/opinion substrate for any resident to actually
 * hold a position... A 'politician gives a speech' cannot currently move a single voter's mind."*
 * `ElectionSystem`'s campaign layer (candidate selection, `Candidacy.support` accumulation,
 * council seats, the mayoral policy bonus) is entirely unchanged and untouched by this file — this
 * only replaces the final scoring/winner step inside the existing, unmodified vote timing/
 * candidate-selection logic in `LifecycleSystem.election()`.
 *
 * See `docs/simulation-rules.md` "Local politics: elections" → "The vote itself: a belief-aware
 * tally" for the full design writeup.
 */
object VotingSystem {

    /**
     * A candidate's implicit "platform" is just their own genuinely-held [BeliefSystem] positions
     * — no separate policy-position data structure. These three topics are the ones picked as
     * electorally salient for a small town election:
     *   - [BeliefTopic.TRUST_IN_GOVERNMENT] — literally "do you trust the town's institutions to
     *     run things", the single most directly on-topic belief for a mayoral race.
     *   - [BeliefTopic.ECONOMIC_OPTIMISM] — jobs/money is the thing every resident's daily life
     *     (via `EconomySystem`) and `BeliefSystem`'s own unemployment trigger already tie together;
     *     a bread-and-butter election issue.
     *   - [BeliefTopic.COMMUNITY_LOYALTY] — how much a voter feels the town pulling together
     *     matters (petitions, flood recovery — see `BeliefSystem.evaluateCommunityResponse`)
     *     translates naturally into "does this candidate feel like one of us, working for us".
     * Deliberately excludes the more personal/apolitical topics (`RISK_TOLERANCE`,
     * `INDIVIDUALISM_VS_COLLECTIVISM`, `SOCIAL_OPENNESS`, `ENVIRONMENTAL_CONCERN`,
     * `TRUST_IN_POLICE`, `INSTITUTIONAL_TRUST`) — a bounded, defensible subset, not an attempt to
     * cover the whole belief taxonomy in one election.
     */
    val SALIENT_TOPICS = listOf(
        BeliefTopic.TRUST_IN_GOVERNMENT,
        BeliefTopic.ECONOMIC_OPTIMISM,
        BeliefTopic.COMMUNITY_LOYALTY
    )

    /** Turnout never drops below this, however distrustful/disinterested a resident is — some
     *  bounded chance of showing up regardless, matching this session's general "never 0%, never
     *  100%" bounded-roll convention (see e.g. `DecisionSystem.PANIC_OVERRIDE_MAX_PROBABILITY`). */
    const val MIN_TURNOUT_CHANCE = 0.20

    /** Turnout never exceeds this — even the most engaged, trusting resident might not vote. */
    const val MAX_TURNOUT_CHANCE = 0.85

    /** How much `politicalInterest` (0..1) contributes to the turnout roll on its own. */
    const val TURNOUT_INTEREST_WEIGHT = 0.45

    /** How much government/institutional trust belief (-1..1, rescaled to 0..1) contributes. */
    const val TURNOUT_TRUST_WEIGHT = 0.20

    /** Baseline turnout chance before interest/trust are added, so a totally neutral resident
     *  (0 interest, 0 trust) still lands mid-band rather than at the floor. */
    const val TURNOUT_BASE = 0.20

    /** Per-topic alignment score is `1.0 - abs(voterPosition - candidatePosition)`, so the max
     *  possible alignment contribution across all salient topics is this many points. */
    private val MAX_ALIGNMENT_SCORE = SALIENT_TOPICS.size.toDouble()

    /** How much a voter's personal relationship with a candidate (trust/familiarity, "family and
     *  workplace influence") can contribute, independent of belief alignment — scaled so it can
     *  matter but never single-handedly swamp genuine policy alignment for a well-aligned rival. */
    const val RELATIONSHIP_WEIGHT = 1.5

    /** How much a candidate's accumulated campaign `Candidacy.support` contributes per voter —
     *  divided down since `support` accumulates unbounded over a whole campaign (see
     *  `ElectionSystem.CAMPAIGN_SUPPORT_GAIN_BASE`/`MAX_CAMPAIGN_ACTIONS`, roughly 0..120 in
     *  practice), while alignment/relationship terms are both small bounded numbers. */
    const val CAMPAIGN_SUPPORT_DIVISOR = 40.0

    /**
     * Bounded per-resident chance of turning out to vote: scales with [Resident.politicalInterest]
     * and the resident's [BeliefTopic.TRUST_IN_GOVERNMENT] position (rescaled from -1..1 to 0..1,
     * so a distrustful resident's chance is pulled down, a trusting one's pulled up), clamped to
     * [MIN_TURNOUT_CHANCE]..[MAX_TURNOUT_CHANCE] — never guaranteed either way.
     */
    fun turnoutChance(voter: Resident): Double {
        val trustRescaled = (BeliefSystem.positionOn(voter, BeliefTopic.TRUST_IN_GOVERNMENT) + 1.0) / 2.0 // 0..1
        val raw = TURNOUT_BASE +
            voter.politicalInterest.coerceIn(0.0, 1.0) * TURNOUT_INTEREST_WEIGHT +
            trustRescaled * TURNOUT_TRUST_WEIGHT
        return raw.coerceIn(MIN_TURNOUT_CHANCE, MAX_TURNOUT_CHANCE)
    }

    /** How much `WorldState.townSentiment` (trust/civicPride, rescaled 0..100 -> a small signed
     *  term) can shift turnout beyond the single-resident [turnoutChance] above — see
     *  `TownSentimentSystem`'s own doc comment. Deliberately small: a town that doesn't trust its
     *  institutions votes a little less, never a lot less — this is flavour on top of the
     *  already-tested per-resident formula, not a replacement for it. */
    const val TOWN_SENTIMENT_TURNOUT_WEIGHT = 0.08

    /**
     * [turnoutChance] plus a small additive term from [com.ripple.town.core.model.TownSentiment]
     * (mean of `trust`/`civicPride`, rescaled from `0..100` to a `-1..1` signed offset around the
     * `50.0` neutral baseline, then scaled by [TOWN_SENTIMENT_TURNOUT_WEIGHT]) — a town that
     * doesn't trust its institutions turns out a little less; a proud, trusting one turns out a
     * little more. Kept as a separate overload rather than changing [turnoutChance]'s own
     * signature, so every existing caller/test of the single-resident formula is completely
     * unaffected; only `tally` below uses this richer version, added 2026-07-11.
     */
    fun turnoutChance(voter: Resident, state: WorldState): Double {
        val sentiment = state.townSentiment
        val civicMean = (sentiment.trust + sentiment.civicPride) / 2.0
        val signedOffset = (civicMean - 50.0) / 50.0 // -1..1
        val adjusted = turnoutChance(voter) + signedOffset * TOWN_SENTIMENT_TURNOUT_WEIGHT
        return adjusted.coerceIn(MIN_TURNOUT_CHANCE, MAX_TURNOUT_CHANCE)
    }

    /**
     * How strongly [voter] favours [candidate]: belief alignment across [SALIENT_TOPICS] (each
     * topic contributes `1.0 - abs(voterPosition - candidatePosition)`, so 0..[MAX_ALIGNMENT_SCORE]
     * total) plus a relationship term (existing `Relationship.trust`/`familiarity` with this
     * specific candidate, if any — "family and workplace influence", independent of policy) plus a
     * small contribution from the candidate's accumulated campaign `Candidacy.support` (the
     * campaign's real, cumulative effect). Purely deterministic given the voter/candidate/state —
     * no rng consumed here; the only per-voter randomness is the turnout roll itself.
     */
    fun voterScoreFor(state: WorldState, voter: Resident, candidate: Resident, candidacy: Candidacy?): Double {
        val alignment = SALIENT_TOPICS.sumOf { topic ->
            1.0 - kotlin.math.abs(BeliefSystem.positionOn(voter, topic) - BeliefSystem.positionOn(candidate, topic))
        }
        val rel = state.relationships[com.ripple.town.core.model.Relationship.keyOf(voter.id, candidate.id)]
        val relationshipTerm = if (rel != null) {
            ((rel.trust + rel.familiarity) / 200.0) * RELATIONSHIP_WEIGHT
        } else 0.0
        val campaignTerm = (candidacy?.support ?: 0.0) / CAMPAIGN_SUPPORT_DIVISOR
        return alignment + relationshipTerm + campaignTerm
    }

    /**
     * Casts every eligible voter's ballot and returns the per-candidate vote tally. Eligible
     * voters: every in-town `DETAILED` adult resident who is not themselves one of [candidates].
     * Bounded cost: a single pass over
     * `state.detailedResidents()` (already a small, capped cast — same list `BeliefSystem`/
     * `PersonalityDevelopmentSystem` scan daily), scoring against at most `MAX_CANDIDATES` (3)
     * candidates across [SALIENT_TOPICS] (3) — genuinely O(residents × 3 × 3), cheap even before
     * accounting for how rare this call is (elections land roughly once every 720 sim days).
     */
    fun tally(
        ctx: TickContext,
        candidates: List<Resident>,
        candidacies: List<Candidacy>
    ): Map<Long, Int> {
        val state = ctx.state
        val candidateIds = candidates.map { it.id }.toSet()
        val candidacyByResident = candidacies.associateBy { it.residentId }
        val votes = mutableMapOf<Long, Int>().apply { candidates.forEach { put(it.id, 0) } }

        val voters = state.detailedResidents()
            .filter { it.inTown && it.id !in candidateIds && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .sortedBy { it.id } // deterministic iteration order regardless of map internals

        for (voter in voters) {
            if (!ctx.rng.nextBoolean(turnoutChance(voter, state))) continue
            val choice = candidates.maxByOrNull { candidate ->
                voterScoreFor(state, voter, candidate, candidacyByResident[candidate.id])
            } ?: continue
            votes[choice.id] = (votes[choice.id] ?: 0) + 1
        }
        return votes
    }
}
