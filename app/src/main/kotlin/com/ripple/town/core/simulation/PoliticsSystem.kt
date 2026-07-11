package com.ripple.town.core.simulation

import com.ripple.town.core.model.*
import kotlin.math.abs

/**
 * Manages political parties: procedural generation from ideology archetypes,
 * member recruitment, leader replacement, and new-party formation. All party
 * names and manifestos are generated from simulation state — no real-world
 * party is ever hard-coded. Politics must never produce "good vs bad" outcomes;
 * every ideology creates winners, losers, supporters and critics.
 */
object PoliticsSystem {

    const val UPDATE_INTERVAL_DAYS = 30L

    // ─────────────────────────────────────────────────────────────────
    // Party archetypes — seeds for procedural generation.
    // Each is a centroid in policy-space; actual parties receive up to
    // ±8 points of random variation per axis, producing differentiated
    // but ideologically coherent parties each playthrough.
    // ─────────────────────────────────────────────────────────────────

    private data class Archetype(
        val namePrefix: String,
        val tagline: String,
        val positions: Map<PolicyArea, Double>
    )

    private val ARCHETYPES = listOf(
        Archetype("Progressive", "A fairer town for all", mapOf(
            PolicyArea.TAXATION to 75.0, PolicyArea.PUBLIC_SPENDING to 80.0,
            PolicyArea.SOCIAL_CARE to 80.0, PolicyArea.EDUCATION to 75.0,
            PolicyArea.HEALTHCARE to 75.0, PolicyArea.HOUSING to 70.0,
            PolicyArea.ENVIRONMENT to 70.0, PolicyArea.POLICING to 50.0,
            PolicyArea.BUSINESS_REGULATION to 65.0, PolicyArea.CIVIL_LIBERTIES to 70.0,
            PolicyArea.LAW_AND_ORDER to 40.0, PolicyArea.TRANSPORT to 60.0,
            PolicyArea.PLANNING to 60.0, PolicyArea.PUBLIC_OWNERSHIP to 65.0,
            PolicyArea.INNOVATION to 60.0
        )),
        Archetype("Conservative", "Stability and prosperity", mapOf(
            PolicyArea.TAXATION to 30.0, PolicyArea.PUBLIC_SPENDING to 35.0,
            PolicyArea.SOCIAL_CARE to 35.0, PolicyArea.EDUCATION to 50.0,
            PolicyArea.HEALTHCARE to 50.0, PolicyArea.HOUSING to 40.0,
            PolicyArea.ENVIRONMENT to 40.0, PolicyArea.POLICING to 75.0,
            PolicyArea.BUSINESS_REGULATION to 25.0, PolicyArea.CIVIL_LIBERTIES to 40.0,
            PolicyArea.LAW_AND_ORDER to 75.0, PolicyArea.TRANSPORT to 55.0,
            PolicyArea.PLANNING to 35.0, PolicyArea.PUBLIC_OWNERSHIP to 35.0,
            PolicyArea.INNOVATION to 50.0
        )),
        Archetype("Green", "People and planet first", mapOf(
            PolicyArea.ENVIRONMENT to 95.0, PolicyArea.TAXATION to 60.0,
            PolicyArea.PUBLIC_SPENDING to 70.0, PolicyArea.TRANSPORT to 80.0,
            PolicyArea.SOCIAL_CARE to 65.0, PolicyArea.BUSINESS_REGULATION to 70.0,
            PolicyArea.POLICING to 45.0, PolicyArea.INNOVATION to 75.0,
            PolicyArea.HOUSING to 65.0, PolicyArea.EDUCATION to 70.0,
            PolicyArea.HEALTHCARE to 70.0, PolicyArea.CIVIL_LIBERTIES to 65.0,
            PolicyArea.LAW_AND_ORDER to 40.0, PolicyArea.PLANNING to 55.0,
            PolicyArea.PUBLIC_OWNERSHIP to 60.0
        )),
        Archetype("Liberal", "Freedom and opportunity", mapOf(
            PolicyArea.CIVIL_LIBERTIES to 90.0, PolicyArea.TAXATION to 40.0,
            PolicyArea.PUBLIC_SPENDING to 55.0, PolicyArea.BUSINESS_REGULATION to 35.0,
            PolicyArea.SOCIAL_CARE to 55.0, PolicyArea.ENVIRONMENT to 60.0,
            PolicyArea.POLICING to 45.0, PolicyArea.INNOVATION to 80.0,
            PolicyArea.HOUSING to 55.0, PolicyArea.EDUCATION to 60.0,
            PolicyArea.HEALTHCARE to 60.0, PolicyArea.LAW_AND_ORDER to 45.0,
            PolicyArea.TRANSPORT to 55.0, PolicyArea.PLANNING to 50.0,
            PolicyArea.PUBLIC_OWNERSHIP to 40.0
        )),
        Archetype("Residents", "Local people, local decisions", mapOf(
            PolicyArea.HOUSING to 60.0, PolicyArea.PLANNING to 35.0,
            PolicyArea.TAXATION to 45.0, PolicyArea.PUBLIC_SPENDING to 60.0,
            PolicyArea.POLICING to 65.0, PolicyArea.TRANSPORT to 70.0,
            PolicyArea.BUSINESS_REGULATION to 50.0, PolicyArea.SOCIAL_CARE to 55.0,
            PolicyArea.ENVIRONMENT to 55.0, PolicyArea.EDUCATION to 60.0,
            PolicyArea.HEALTHCARE to 60.0, PolicyArea.CIVIL_LIBERTIES to 55.0,
            PolicyArea.LAW_AND_ORDER to 60.0, PolicyArea.PUBLIC_OWNERSHIP to 50.0,
            PolicyArea.INNOVATION to 50.0
        ))
    )

    private val SUFFIXES = listOf("Party", "Alliance", "Movement", "Coalition", "Forum", "Association")

    // ─────────────────────────────────────────────────────────────────
    // Seeding
    // ─────────────────────────────────────────────────────────────────

    /** Generate 2–3 parties at world start if none exist yet. */
    fun seedParties(ctx: TickContext) {
        if (ctx.state.politicalParties.isNotEmpty()) return
        val count = if (ctx.rng.nextBoolean(0.4)) 3 else 2
        val available = (0 until ARCHETYPES.size).toMutableList()
        val chosen = mutableListOf<Int>()
        repeat(count) {
            if (available.isEmpty()) return@repeat
            val pick = ctx.rng.nextInt(available.size)
            chosen += available[pick]
            available.removeAt(pick)
        }
        chosen.forEach { idx -> createParty(ctx, ARCHETYPES[idx]) }
    }

    private fun createParty(ctx: TickContext, archetype: Archetype): PoliticalParty {
        val state = ctx.state
        val id = state.nextPartyId++
        val party = PoliticalParty(
            id = id,
            name = "${archetype.namePrefix} ${ctx.rng.pick(SUFFIXES)}",
            tagline = archetype.tagline,
            foundedAt = state.time
        )
        for (area in PolicyArea.values()) {
            val base = archetype.positions[area] ?: 50.0
            party.manifesto[area.name] = (base + ctx.rng.nextDouble(-8.0, 8.0)).coerceIn(10.0, 90.0)
        }
        // Priority policies: those whose ideology score closely matches the party's manifesto position
        PolicyType.values()
            .filter { pt -> abs((party.manifesto[pt.area.name] ?: 50.0) - pt.ideologyScore) < 20.0 }
            .take(5)
            .forEach { party.priorityPolicies += it.name }

        val candidates = state.livingResidents()
            .filter { r -> r.inTown && r.lifeStageAt(state.time) == LifeStage.ADULT
                && r.politicalInterest > 0.4 && r.partyId == null }
        val leader = ctx.rng.pickOrNull(candidates)
        if (leader != null) {
            assignLeader(ctx, party, leader)
            // Recruit initial members from best-aligned adults
            state.livingResidents()
                .filter { r -> r.inTown && r.lifeStageAt(state.time) == LifeStage.ADULT && r.partyId == null }
                .sortedByDescending { r -> alignmentScore(r, party, state) }
                .take(ctx.rng.nextInt(4) + 2)
                .forEach { r -> enroll(r, party) }
        }
        state.politicalParties[id] = party
        return party
    }

    private fun enroll(resident: Resident, party: PoliticalParty) {
        resident.partyId = party.id
        if (!party.memberIds.contains(resident.id)) party.memberIds += resident.id
    }

    // ─────────────────────────────────────────────────────────────────
    // Belief → policy position mapping
    // ─────────────────────────────────────────────────────────────────

    /** Convert a resident's beliefs to an approximate position (0..100) on a policy area.
     *  Belief.position is -1..1; (position+1)/2*100 maps to 0..100. */
    fun residentPosition(resident: Resident, area: PolicyArea): Double {
        val b = resident.beliefs
        fun pos(topic: BeliefTopic): Double = ((b[topic]?.position ?: 0.0) + 1.0) / 2.0 * 100.0
        return when (area) {
            PolicyArea.POLICING, PolicyArea.LAW_AND_ORDER -> pos(BeliefTopic.TRUST_IN_POLICE)
            PolicyArea.TAXATION -> 100.0 - pos(BeliefTopic.INDIVIDUALISM_VS_COLLECTIVISM)
            PolicyArea.PUBLIC_SPENDING, PolicyArea.SOCIAL_CARE, PolicyArea.PUBLIC_OWNERSHIP ->
                100.0 - pos(BeliefTopic.INDIVIDUALISM_VS_COLLECTIVISM)
            PolicyArea.BUSINESS_REGULATION -> 100.0 - pos(BeliefTopic.ECONOMIC_OPTIMISM)
            PolicyArea.ENVIRONMENT -> pos(BeliefTopic.ENVIRONMENTAL_CONCERN)
            PolicyArea.CIVIL_LIBERTIES -> pos(BeliefTopic.SOCIAL_OPENNESS)
            PolicyArea.HOUSING, PolicyArea.PLANNING -> pos(BeliefTopic.COMMUNITY_LOYALTY)
            PolicyArea.EDUCATION, PolicyArea.HEALTHCARE -> pos(BeliefTopic.INSTITUTIONAL_TRUST)
            PolicyArea.TRANSPORT ->
                (pos(BeliefTopic.COMMUNITY_LOYALTY) + pos(BeliefTopic.ECONOMIC_OPTIMISM)) / 2.0
            PolicyArea.INNOVATION -> pos(BeliefTopic.RISK_TOLERANCE)
        }
    }

    /** 0..100 alignment score: how closely a resident's beliefs match a party's manifesto. */
    fun alignmentScore(resident: Resident, party: PoliticalParty, state: WorldState): Double {
        var total = 0.0
        var n = 0
        for (area in PolicyArea.values()) {
            val partyPos = party.manifesto[area.name] ?: 50.0
            total += 100.0 - abs(partyPos - residentPosition(resident, area))
            n++
        }
        return if (n == 0) 50.0 else total / n
    }

    // ─────────────────────────────────────────────────────────────────
    // Leader assignment
    // ─────────────────────────────────────────────────────────────────

    /** Assign resident as party leader and generate their LeadershipTraits. */
    fun assignLeader(ctx: TickContext, party: PoliticalParty, resident: Resident) {
        party.leaderId = resident.id
        enroll(resident, party)
        val p = resident.effectivePersonality()
        val politics = resident.skill(SkillType.POLITICS) / 100.0
        val social = resident.skill(SkillType.SOCIAL) / 100.0
        fun jitter() = ctx.rng.nextDouble(-0.08, 0.08)
        ctx.state.leadershipTraits[resident.id] = LeadershipTraits(
            competence      = (politics * 0.6 + p.discipline * 0.4 + jitter()).coerceIn(0.05, 0.98),
            charisma        = (social * 0.5 + p.sociability * 0.4 + p.kindness * 0.1 + jitter()).coerceIn(0.05, 0.98),
            honesty         = (p.honesty * 0.85 + jitter()).coerceIn(0.05, 0.99),
            ambition        = (p.ambition * 0.8 + jitter()).coerceIn(0.05, 0.99),
            communication   = (social * 0.5 + p.empathy * 0.3 + p.sociability * 0.2 + jitter()).coerceIn(0.05, 0.98),
            vision          = (p.curiosity * 0.5 + p.ambition * 0.3 + politics * 0.2 + jitter()).coerceIn(0.05, 0.98),
            crisisManagement = (p.courage * 0.5 + p.discipline * 0.4 + p.patience * 0.1 + jitter()).coerceIn(0.05, 0.98),
            organisation    = (p.discipline * 0.7 + p.patience * 0.2 + jitter()).coerceIn(0.05, 0.98)
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Monthly update
    // ─────────────────────────────────────────────────────────────────

    fun updateMonthly(ctx: TickContext) {
        if (ctx.state.politicalParties.isEmpty()) { seedParties(ctx); return }
        pruneMembers(ctx.state)
        recruitUnaligned(ctx)
        replaceLeaders(ctx)
        maybeFormNewParty(ctx)
    }

    private fun pruneMembers(state: WorldState) {
        state.politicalParties.values.forEach { party ->
            party.memberIds.removeAll { id ->
                val r = state.residents[id]
                r == null || !r.inTown || !r.alive
            }
        }
    }

    private fun recruitUnaligned(ctx: TickContext) {
        val state = ctx.state
        val active = state.politicalParties.values.filter { it.dissolvedAt == null }
        state.livingResidents()
            .filter { r -> r.inTown && r.lifeStageAt(state.time) == LifeStage.ADULT
                && r.politicalInterest > 0.35 && r.partyId == null }
            .forEach { r ->
                val best = active.maxByOrNull { alignmentScore(r, it, state) } ?: return@forEach
                if (alignmentScore(r, best, state) > 58.0) enroll(r, best)
            }
    }

    private fun replaceLeaders(ctx: TickContext) {
        val state = ctx.state
        state.politicalParties.values
            .filter { party ->
                party.dissolvedAt == null &&
                (party.leaderId == null ||
                    state.residents[party.leaderId]?.let { !it.inTown || !it.alive } == true)
            }
            .forEach { party ->
                val successor = party.memberIds.mapNotNull { state.residents[it] }
                    .filter { it.inTown && it.alive && it.lifeStageAt(state.time) == LifeStage.ADULT }
                    .maxByOrNull { it.politicalInterest + it.skill(SkillType.POLITICS) / 100.0 }
                if (successor != null) {
                    assignLeader(ctx, party, successor)
                    party.history += "${successor.fullName} became party leader"
                } else {
                    party.dissolvedAt = state.time
                    party.history += "Party dissolved — no members remaining"
                }
            }
    }

    private fun maybeFormNewParty(ctx: TickContext) {
        val state = ctx.state
        val active = state.politicalParties.values.filter { it.dissolvedAt == null }
        if (active.size >= 4 || !ctx.rng.nextBoolean(0.04)) return
        val unaligned = state.livingResidents().filter { r ->
            r.inTown && r.lifeStageAt(state.time) == LifeStage.ADULT && r.politicalInterest > 0.5 &&
            (r.partyId == null ||
                active.firstOrNull { p -> p.id == r.partyId }
                    ?.let { alignmentScore(r, it, state) < 42.0 } == true)
        }
        if (unaligned.size < 3) return
        val founder = unaligned.maxByOrNull { it.politicalInterest + ctx.rng.nextDouble(0.0, 0.15) } ?: return
        val archetype = ARCHETYPES.maxByOrNull { arc ->
            var diff = 0.0
            arc.positions.forEach { (area, pos) ->
                val avgExisting = active.map { p -> p.manifesto[area.name] ?: 50.0 }.average()
                diff += abs(residentPosition(founder, area) - avgExisting)
            }
            diff
        } ?: ARCHETYPES[0]
        val newParty = createParty(ctx, archetype)
        ctx.emit(
            EventType.TOWN_MILESTONE,
            "${founder.fullName} founded ${newParty.name} — a new political party in ${state.townName}",
            sourceResidentId = founder.id,
            severity = 0.5
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Election result hook — called from SimulationCoordinator when
    // state.mayorId changes after LifecycleSystem runs an election.
    // ─────────────────────────────────────────────────────────────────

    fun onElectionResult(ctx: TickContext, winnerId: Long) {
        val state = ctx.state
        val winner = state.residents[winnerId] ?: return
        val party = winner.partyId?.let { state.politicalParties[it] }
        // Close previous government
        state.currentGovernmentId?.let { prevId ->
            state.governmentRecords.find { it.id == prevId }?.also { prev ->
                if (prev.endedAt == null) {
                    prev.endedAt = state.time
                    prev.finalApproval = state.publicOpinionData.approvalRating
                    if (prev.legacyStatement.isEmpty()) prev.legacyStatement = PoliticalHistorySystem.buildLegacy(prev, state)
                }
            }
        }
        // New government record
        val govId = state.nextGovernmentId++
        state.governmentRecords += GovernmentRecord(
            id = govId,
            partyId = party?.id,
            leaderId = winnerId,
            leaderName = winner.fullName,
            partyName = party?.name ?: "Independent",
            startedAt = state.time,
            peakApproval = state.publicOpinionData.approvalRating,
            lowestApproval = state.publicOpinionData.approvalRating,
            finalApproval = state.publicOpinionData.approvalRating
        )
        state.currentGovernmentId = govId
        if (state.governmentRecords.size > 200) state.governmentRecords.removeAt(0)
        if (party != null) PolicyEngine.enactManifestoPolicies(ctx, party)
    }

}
