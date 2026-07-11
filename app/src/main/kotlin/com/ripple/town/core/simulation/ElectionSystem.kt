package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.Candidacy
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.FamilyReputationType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime

/**
 * Council seats and campaign-driven elections, extending the grassroots petitions in
 * [PetitionSystem]. Deliberately layers on top of the pre-existing, unmodified
 * `LifecycleSystem.election()` rather than replacing it: that function already reads
 * `nextElectionAt`/`mayorId` and already factors `reputation` into who wins, so this
 * system's whole job is to make the days *before* that vote mean something — a real,
 * contested campaign that nudges candidates' `reputation` (the same field the vote already
 * reads) — plus a genuinely new piece the vote never had: council seats, filled from the
 * runners-up, and a small bounded town-wide policy effect while a term lasts.
 *
 * Sequencing (called daily, right after `LifecycleSystem.updateDaily` so it sees whatever
 * `nextElectionAt`/`mayorId` that call just set):
 *   1. [callElection] — opens a fixed campaign window once an election is imminent.
 *   2. [runCampaigns] — each declared candidate gets a bounded chance per day to campaign.
 *   3. [fillCouncil] — the day the vote lands (`mayorId` just changed under us), seat the
 *      runners-up as councillors and clear the spent campaign state.
 */
object ElectionSystem {

    /** How far ahead of the vote `ELECTION_CALLED` fires and campaigning opens. */
    const val CAMPAIGN_WINDOW_DAYS = 20.0

    /** Never more than this many declared candidates in one race (mirrors LifecycleSystem's own `.take(3)`). */
    const val MAX_CANDIDATES = 3

    /** Runners-up who don't win the mayoralty fill up to this many council seats. */
    const val COUNCIL_SEATS = 2

    /** A declared candidate's bounded daily chance of getting a campaigning day in. */
    const val DAILY_CAMPAIGN_CHANCE = 0.30

    /** No candidate campaigns more than this many times in one race — a campaign is a short push, not a grind. */
    const val MAX_CAMPAIGN_ACTIONS = 10

    /** Reputation gained per successful campaign day, split between the candidate and (smaller) their standing in town. */
    const val CAMPAIGN_REPUTATION_GAIN = 1.5
    const val CAMPAIGN_SUPPORT_GAIN_BASE = 4.0

    /** Familiarity with voters and a track record of resolved petitions both make a campaign land harder. */
    const val TRACK_RECORD_BONUS_PER_SUCCESS = 2.0
    const val MAX_TRACK_RECORD_BONUS = 8.0
    const val FAMILIARITY_SUPPORT_DIVISOR = 25.0

    /** Bounded town-wide policy effect while a mayor holds office: repairs get funded a little more readily. */
    const val MAYORAL_REPAIR_CHANCE_BONUS = 0.04

    /** Campaign boost added to a candidate's support gain when they belong to a POLITICAL_DYNASTY
     *  family with at least two living members — name recognition and machine organisation translate
     *  into a real, bounded edge on the campaign trail. */
    const val DYNASTY_CAMPAIGN_BOOST = 5.0

    /** Minimum living family members for a dynasty legacy to confer the campaign boost — a lone
     *  surviving member doesn't bring the same machine with them. */
    const val DYNASTY_LEGACY_MIN_MEMBERS = 2

    fun updateDaily(ctx: TickContext) {
        callElection(ctx)
        runCampaigns(ctx)
        fillCouncil(ctx)
    }

    // -------------------------------------------------------------- calling

    private fun callElection(ctx: TickContext) {
        val state = ctx.state
        if (state.campaignEndsAt != null) return // already running
        if (state.nextElectionAt <= 0) return
        val windowStart = state.nextElectionAt - (CAMPAIGN_WINDOW_DAYS * SimTime.MINUTES_PER_DAY).toLong()
        if (ctx.now < windowStart || ctx.now >= state.nextElectionAt) return

        // Same candidate pool shape LifecycleSystem.election() will independently re-derive at
        // the vote — declaring it now just gives it a name and a campaign, not a different outcome rule.
        val candidates = state.detailedResidents()
            .filter { it.inTown && it.politicalInterest > 0.35 && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .sortedByDescending { it.politicalInterest * 50 + it.reputation + it.skill(com.ripple.town.core.model.SkillType.POLITICS) }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return

        state.campaignEndsAt = state.nextElectionAt
        state.candidacies.clear()
        state.candidacies += candidates.map { Candidacy(it.id) }

        val event = ctx.emit(
            EventType.ELECTION_CALLED,
            if (candidates.size == 1) {
                "An election has been called; ${candidates.first().fullName} is standing unopposed so far."
            } else {
                "An election has been called. Standing: " +
                    candidates.joinToString(", ") { it.fullName } + "."
            },
            targetResidentIds = candidates.map { it.id },
            severity = 0.35
        )
        for (c in candidates) {
            ctx.addMemory(c, MemoryType.ACHIEVEMENT, "I'm putting myself forward for the town.", 55.0, event.id)
        }
    }

    // ------------------------------------------------------------ campaigns

    /**
     * A campaign "moment": a bounded daily roll per candidate, not a sub-simulation. Support
     * gained scales with the candidate's existing standing tools — reputation, a track record
     * of resolved petitions (genuine local-politics history, not just personality), and how
     * familiar the town already is with them (mean relationship familiarity across whoever
     * they already know) — so an established, well-liked organiser has a real edge over a
     * newcomer riding personality alone, without the vote becoming a pure popularity coin flip.
     */
    private fun runCampaigns(ctx: TickContext) {
        val state = ctx.state
        if (state.campaignEndsAt == null || state.candidacies.isEmpty()) return
        if (ctx.now >= (state.campaignEndsAt ?: return)) return

        for (candidacy in state.candidacies.sortedBy { it.residentId }) {
            if (candidacy.actionsTaken >= MAX_CAMPAIGN_ACTIONS) continue
            val candidate = state.resident(candidacy.residentId)?.takeIf { it.inTown } ?: continue
            if (!ctx.rng.nextBoolean(DAILY_CAMPAIGN_CHANCE)) continue

            val trackRecord = (petitionSuccesses(state, candidate.id) * TRACK_RECORD_BONUS_PER_SUCCESS)
                .coerceAtMost(MAX_TRACK_RECORD_BONUS)
            val familiarity = averageFamiliarity(state, candidate.id) / FAMILIARITY_SUPPORT_DIVISOR
            val traits = ctx.state.leadershipTraits[candidate.id]
            val traitBonus = (traits?.charisma ?: 0.5) * 2.0 + (traits?.communication ?: 0.5) * 1.5
            val dynastyBoost = dynastyCampaignBoost(ctx, candidate.surname)
            val gain = CAMPAIGN_SUPPORT_GAIN_BASE + trackRecord + familiarity + traitBonus + dynastyBoost + ctx.rng.nextDouble(-1.0, 2.0)
            candidacy.support = (candidacy.support + gain).coerceAtLeast(0.0)
            candidacy.actionsTaken++

            // A campaigning day nudges the candidate's own reputation up — the same field
            // LifecycleSystem.election() reads at the vote, so this composes with the existing
            // outcome logic instead of introducing a parallel one.
            candidate.reputation = (candidate.reputation + CAMPAIGN_REPUTATION_GAIN).coerceAtMost(100.0)
            candidate.needs.purpose = (candidate.needs.purpose + 3.0).coerceAtMost(100.0)

            val townHall = state.buildings.values.firstOrNull { it.type == BuildingType.TOWN_HALL }
            if (townHall != null && candidate.currentBuildingId != townHall.id) {
                ctx.sendTo(candidate, townHall.id, com.ripple.town.core.model.Activity.COMMUNITY, 90L, "Campaigning at the town hall")
            }
        }
    }

    /** [DYNASTY_CAMPAIGN_BOOST] when the candidate's family is a POLITICAL_DYNASTY with enough
     *  living members to bring real organisational weight to a campaign, 0.0 otherwise. */
    private fun dynastyCampaignBoost(ctx: TickContext, surname: String): Double {
        val legacy = ctx.state.familyLegacies[surname] ?: return 0.0
        if (legacy.reputationType != FamilyReputationType.POLITICAL_DYNASTY.name) return 0.0
        if (legacy.livingMembers < DYNASTY_LEGACY_MIN_MEMBERS) return 0.0
        return DYNASTY_CAMPAIGN_BOOST
    }

    /** Resolved petitions this resident started and won — genuine local-politics standing, not personality. */
    private fun petitionSuccesses(state: com.ripple.town.core.model.WorldState, residentId: Long): Int =
        state.petitions.count {
            it.starterId == residentId && it.status == com.ripple.town.core.model.PetitionStatus.SUCCEEDED
        }

    /** How well the town already knows this candidate, averaged across whoever they have a relationship with. */
    private fun averageFamiliarity(state: com.ripple.town.core.model.WorldState, residentId: Long): Double {
        val rels = state.relationshipsOf(residentId)
        if (rels.isEmpty()) return 0.0
        return rels.sumOf { it.familiarity } / rels.size
    }

    // --------------------------------------------------------------- result

    /**
     * The vote itself still happens entirely inside the untouched `LifecycleSystem.election()`
     * (owned by another agent this round). This just watches for `mayorId` having changed since
     * the campaign opened and, when it has, converts the runners-up's accumulated `support`
     * into council seats and closes out the campaign bookkeeping.
     */
    private fun fillCouncil(ctx: TickContext) {
        val state = ctx.state
        val campaignEndsAt = state.campaignEndsAt ?: return
        if (ctx.now < campaignEndsAt) return
        // LifecycleSystem.election() runs earlier in the same daily pass (see SimulationCoordinator's
        // ordering) and, once nextElectionAt has passed, immediately re-rolls it forward — so by the
        // time we get here on the vote's day, nextElectionAt has already moved past `campaignEndsAt`.
        if (state.nextElectionAt <= campaignEndsAt) return // vote hasn't actually landed yet

        val winnerId = state.mayorId
        val runnersUp = state.candidacies
            .filter { it.residentId != winnerId }
            .sortedWith(compareByDescending<Candidacy> { it.support }.thenBy { it.residentId })
            .take(COUNCIL_SEATS)
            .mapNotNull { state.resident(it.residentId)?.takeIf { r -> r.inTown } }

        state.councillorIds.clear()
        state.councillorIds += runnersUp.map { it.id }
        for (r in runnersUp) {
            if (r.occupation == "Unemployed") r.occupation = "Councillor"
            r.reputation = (r.reputation + 4.0).coerceAtMost(100.0)
            r.needs.purpose = (r.needs.purpose + 8.0).coerceAtMost(100.0)
        }
        if (runnersUp.isNotEmpty()) {
            val e = ctx.emit(
                EventType.TOWN_MILESTONE,
                "The council seats have been filled: " + runnersUp.joinToString(", ") { it.fullName } + ".",
                targetResidentIds = runnersUp.map { it.id },
                severity = 0.3
            )
            for (r in runnersUp) {
                ctx.addMemory(r, MemoryType.ACHIEVEMENT, "I didn't win, but I've a seat on the council.", 50.0, e.id)
            }
        }

        state.campaignEndsAt = null
        state.candidacies.clear()
    }

    // ----------------------------------------------------------- policy hook

    /**
     * A term in office has one small, bounded, real consequence rather than none: while a
     * mayor holds office, the town is a little quicker to fund building repairs — read by
     * `BuildingLifecycleSystem` as an addition to its own per-building repair-chance roll,
     * composing with (not duplicating or overriding) that system's existing affordability
     * gate and the family-standing modifier already applied there.
     */
    fun repairChanceBonus(state: com.ripple.town.core.model.WorldState): Double =
        if (state.mayorId != null) MAYORAL_REPAIR_CHANCE_BONUS else 0.0
}
