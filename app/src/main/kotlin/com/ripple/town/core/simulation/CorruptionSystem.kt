package com.ripple.town.core.simulation

import com.ripple.town.core.model.*

/**
 * Models political corruption as the product of three factors: motive (personal
 * financial pressure, low honesty), opportunity (holding power), and weak oversight
 * (low institutional trust in the town). All three must align — corruption is never
 * automatic, never impossible, and never the inevitable result of any one factor.
 * Separate from CrimeSystem's street crime. Good governance — high honesty, strong
 * institutional trust — makes corruption rare without making it impossible.
 */
object CorruptionSystem {

    const val UPDATE_INTERVAL_DAYS = 30L
    /** Base per-month probability that a power-holder's motive × opportunity tips into an act
     *  of corruption, before oversight scaling. At full motive + opportunity with no oversight
     *  this gives a ~0.84 monthly chance; with strong oversight it drops to ~0.34. */
    const val BASE_CORRUPTION_PROBABILITY = 0.012

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        // Who holds power: mayor + any council members
        val powerHolders = buildList {
            state.residents[state.mayorId]?.let { add(it) }
            state.councillorIds.mapNotNull { id -> state.residents[id] }.forEach { add(it) }
        }.filter { it.inTown && it.alive }.distinctBy { it.id }
        // Oversight strength: average institutional trust across all residents (-1..1 → 0..1)
        val avgTrust = state.livingResidents()
            .mapNotNull { r -> r.beliefs[BeliefTopic.INSTITUTIONAL_TRUST]?.position }
            .let { vals -> if (vals.isEmpty()) 0.0 else vals.average() }
        val oversightStrength = ((avgTrust + 1.0) / 2.0).coerceIn(0.0, 1.0)  // 0 = weak, 1 = strong
        powerHolders.forEach { r -> evaluateCorruption(ctx, r, oversightStrength) }
        // Clean up resolved incidents older than 2 years
        state.corruptionIncidents.removeAll { inc ->
            inc.resolvedAt != null &&
            (state.time - inc.resolvedAt!!) > 2 * SimTime.MINUTES_PER_YEAR
        }
    }

    private fun evaluateCorruption(ctx: TickContext, r: Resident, oversightStrength: Double) {
        val state = ctx.state
        // Check if already running an ongoing incident
        val ongoing = state.corruptionIncidents.firstOrNull {
            it.perpetratorId == r.id && it.status == CorruptionStatus.ONGOING
        }
        if (ongoing != null) {
            checkDiscovery(ctx, ongoing, r, oversightStrength)
            return
        }
        // Motive: financial pressure + dishonesty
        val p = r.effectivePersonality()
        val financialPressure = ((35.0 - r.needs.financialSecurity).coerceAtLeast(0.0) / 35.0) * 0.4 +
            (r.debt / 3_000.0).coerceIn(0.0, 0.3)
        val dishonesty = (0.6 - p.honesty).coerceAtLeast(0.0) * 0.5
        val motive = (financialPressure + dishonesty).coerceIn(0.0, 1.0)
        // Opportunity: mayor has more than councillor
        val opportunity = if (r.id == state.mayorId) 0.7 else 0.4
        // Weak oversight amplifies risk; strong oversight suppresses it
        val oversightFactor = 1.2 - oversightStrength * 0.8  // 0.4..1.2
        val corruptionRisk = motive * opportunity * oversightFactor * BASE_CORRUPTION_PROBABILITY
        if (!ctx.rng.nextBoolean(corruptionRisk)) return
        // Corruption emerges
        val type = ctx.rng.pick(CorruptionType.values().toList())
        val severity = (motive * 0.75 + ctx.rng.nextDouble(0.0, 0.25)).coerceIn(0.1, 0.9)
        val money = severity * 4_000.0 * (if (r.id == state.mayorId) 2.0 else 1.0)
        state.corruptionIncidents += CorruptionIncident(
            id = state.nextCorruptionId++,
            type = type,
            perpetratorId = r.id,
            startedAt = state.time,
            severity = severity,
            description = buildDescription(type, r),
            moneyInvolved = money
        )
        state.municipalBudget.balance -= money
        r.wealth += money * 0.65
    }

    private fun checkDiscovery(
        ctx: TickContext,
        incident: CorruptionIncident,
        perpetrator: Resident,
        oversightStrength: Double
    ) {
        val state = ctx.state
        // Strong oversight = higher discovery chance; weak oversight = lower
        val discoveryChance = incident.severity * 0.035 * (0.5 + oversightStrength)
        if (!ctx.rng.nextBoolean(discoveryChance)) return
        incident.status = CorruptionStatus.INVESTIGATED
        incident.discoveredAt = state.time
        ctx.emit(
            EventType.POLITICAL_SCANDAL,
            "${perpetrator.fullName} is under investigation for " +
                incident.type.label.lowercase(),
            sourceResidentId = perpetrator.id,
            severity = incident.severity * 0.8 + 0.2
        )
        // Public approval drops; corruption flag set on government record.
        // Only apply the immediate approval drop — PublicOpinionSystem recomputes per-resident
        // satisfaction weekly and already includes an active-corruption penalty. Direct replaceAll
        // bypassed that weekly clamp and double-hit every resident.
        val hit = 8.0 + incident.severity * 10.0
        state.publicOpinionData.approvalRating = (state.publicOpinionData.approvalRating - hit).coerceAtLeast(0.0)
        state.currentGovernmentId?.let { gid ->
            state.governmentRecords.find { it.id == gid }?.corruption = true
        }
        // Trust in government falls for all residents
        state.livingResidents().filter { it.inTown }.forEach { r ->
            r.beliefs[BeliefTopic.TRUST_IN_GOVERNMENT]?.also { b ->
                b.position = (b.position - 0.12 * incident.severity).coerceAtLeast(-1.0)
                b.confidence = (b.confidence + 0.04).coerceAtMost(1.0)
                b.clamp()
            }
        }
        // Severe cases become public scandals; milder ones get covered up
        val goesPublic = incident.severity > 0.55 && ctx.rng.nextBoolean(0.55 + oversightStrength * 0.2)
        if (goesPublic) {
            incident.status = CorruptionStatus.EXPOSED
            incident.resolvedAt = state.time
            ctx.emit(
                EventType.POLITICAL_SCANDAL,
                "${perpetrator.fullName}'s corruption has been exposed — " +
                    "${incident.type.label.lowercase()} confirmed",
                sourceResidentId = perpetrator.id,
                severity = 0.85
            )
        } else {
            incident.status = CorruptionStatus.COVERED_UP
            incident.resolvedAt = state.time
        }
    }

    private fun buildDescription(type: CorruptionType, perpetrator: Resident): String = when (type) {
        CorruptionType.BRIBERY ->
            "${perpetrator.fullName} accepted payments in exchange for council decisions"
        CorruptionType.CONTRACT_FRAUD ->
            "${perpetrator.fullName} awarded contracts to associates at inflated prices"
        CorruptionType.EMBEZZLEMENT ->
            "${perpetrator.fullName} redirected public funds for personal use"
        CorruptionType.PLANNING_CORRUPTION ->
            "${perpetrator.fullName} approved planning applications for undisclosed payments"
        CorruptionType.NEPOTISM ->
            "${perpetrator.fullName} appointed friends and family to council positions"
    }
}
