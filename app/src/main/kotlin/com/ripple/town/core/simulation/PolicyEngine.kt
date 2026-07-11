package com.ripple.town.core.simulation

import com.ripple.town.core.model.*

/**
 * Enacts manifesto policies when a party wins power, tracks active policy
 * records, recomputes PolicyModifiers whenever policy status changes, and
 * handles delayed effects (school investment takes years to show results).
 * Downstream systems read state.policyModifiers directly — policies nudge
 * probabilities at the margins without overriding causality.
 */
object PolicyEngine {

    // ─────────────────────────────────────────────────────────────────
    // Policy enactment
    // ─────────────────────────────────────────────────────────────────

    /** Enact the winning party's priority policies. Repeals competing policies
     *  in the same areas from the previous administration. */
    fun enactManifestoPolicies(ctx: TickContext, party: PoliticalParty) {
        val state = ctx.state
        val dayIndex = SimTime.dayIndex(state.time)
        val priorityTypes = party.priorityPolicies
            .mapNotNull { name -> PolicyType.values().firstOrNull { it.name == name } }
            .take(3)
        val areasReplaced = priorityTypes.map { it.area }.toSet()
        // Repeal same-area policies from a previous administration
        state.activePolicies.values
            .filter { rec ->
                rec.status == PolicyStatus.PASSED.name &&
                PolicyType.values().firstOrNull { it.name == rec.policyType }?.area in areasReplaced
            }
            .forEach { rec ->
                rec.status = PolicyStatus.REPEALED.name
                rec.repealedAt = state.time
            }
        // Tally real council votes for this party's manifesto using councillor party alignment.
        // Governing-party councillors vote for with 85% reliability; opposition cross the floor
        // at 15% (personality.agreeableness nudges both thresholds slightly).
        val councillors = state.councillorIds.mapNotNull { state.resident(it) }
        val (votesFor, votesAgainst) = tallyCouncilVotes(ctx, party, councillors)

        // Pass priority policies
        priorityTypes.forEach { type ->
            val id = state.nextPolicyId++
            val activates = if (type.delayDays > 0) dayIndex + type.delayDays else null
            state.activePolicies[id] = PolicyRecord(
                id = id,
                policyType = type.name,
                title = type.label,
                proposedByPartyId = party.id,
                proposedByResidentId = party.leaderId,
                proposedAt = state.time,
                status = if (votesFor > votesAgainst) PolicyStatus.PASSED.name else PolicyStatus.REJECTED.name,
                passedAt = if (votesFor > votesAgainst) state.time else null,
                votesFor = votesFor, votesAgainst = votesAgainst,
                annualCost = type.annualCost,
                delayDays = type.delayDays,
                activatesAtDay = activates
            )
            state.currentGovernmentId?.let { gid ->
                state.governmentRecords.find { it.id == gid }?.policiesPassed?.add(id)
            }
        }
        recomputeModifiers(state)
        val passed = priorityTypes.size
        val rejected = state.activePolicies.values.count {
            it.status == PolicyStatus.REJECTED.name && it.proposedByPartyId == party.id &&
            it.proposedAt == state.time
        }
        if (passed > 0 || rejected > 0) {
            val detail = buildString {
                if (passed > 0) append("${party.name} passed $passed polic${if (passed == 1) "y" else "ies"}")
                if (rejected > 0) {
                    if (passed > 0) append(", rejected $rejected")
                    else append("${party.name} failed to pass $rejected polic${if (rejected == 1) "y" else "ies"} ($votesFor–$votesAgainst)")
                }
                append(" ($votesFor–$votesAgainst)")
            }
            ctx.emit(
                EventType.TOWN_MILESTONE,
                detail,
                sourceResidentId = party.leaderId,
                severity = if (passed > 0) 0.5 else 0.4
            )
        }
    }

    private fun tallyCouncilVotes(
        ctx: TickContext,
        governingParty: PoliticalParty,
        councillors: List<com.ripple.town.core.model.Resident>
    ): Pair<Int, Int> {
        var votesFor = 0
        var votesAgainst = 0
        // Mayor always casts a vote for the governing party's policies
        ctx.state.mayorId?.let { ctx.state.resident(it) }?.let { mayor ->
            if (mayor.partyId == governingParty.id || mayor.id == governingParty.leaderId) votesFor++
            else votesAgainst++
        }
        for (councillor in councillors) {
            val loyaltyBase = if (councillor.partyId == governingParty.id) 0.85 else 0.15
            // Patience/kindness nudges loyalty ±5% — a more pragmatic councillor is likelier to cross
            val temperament = (councillor.personality.patience + councillor.personality.kindness) / 2.0
            val pFor = (loyaltyBase + (temperament - 0.5) * 0.10).coerceIn(0.05, 0.95)
            if (ctx.rng.nextBoolean(pFor)) votesFor++ else votesAgainst++
        }
        // Ensure at least 1 vote for from an uncontested governing party
        if (votesFor == 0) votesFor = 1
        return votesFor to votesAgainst
    }

    // ─────────────────────────────────────────────────────────────────
    // Modifier computation
    // ─────────────────────────────────────────────────────────────────

    /** Re-derive PolicyModifiers from all active, fully-activated policies.
     *  Called after any policy status change. O(activePolicies). */
    fun recomputeModifiers(state: WorldState) {
        val mods = PolicyModifiers()
        val dayIndex = SimTime.dayIndex(state.time)
        state.activePolicies.values
            .filter { rec ->
                rec.status == PolicyStatus.PASSED.name &&
                (rec.activatesAtDay == null || dayIndex >= rec.activatesAtDay!!)
            }
            .forEach { rec ->
                PolicyType.values().firstOrNull { it.name == rec.policyType }
                    ?.let { applyEffect(it, mods) }
            }
        state.policyModifiers = mods
    }

    private fun applyEffect(type: PolicyType, m: PolicyModifiers) {
        when (type) {
            PolicyType.INCREASE_POLICE_FUNDING -> { m.crimeMultiplier *= 0.80; m.civicWellbeingBonus += 1.0 }
            PolicyType.REDUCE_POLICE_FUNDING   ->   m.crimeMultiplier *= 1.20
            PolicyType.COMMUNITY_POLICING      -> { m.crimeMultiplier *= 0.90; m.civicWellbeingBonus += 0.5 }
            PolicyType.RAISE_COUNCIL_TAX       ->   m.councilTaxMultiplier *= 1.25
            PolicyType.CUT_COUNCIL_TAX         ->   m.councilTaxMultiplier *= 0.80
            PolicyType.BUSINESS_RATE_REDUCTION -> { m.businessRatesMultiplier *= 0.75; m.businessFormationBonus += 0.04 }
            PolicyType.BUSINESS_RATE_INCREASE  -> { m.businessRatesMultiplier *= 1.30; m.businessFormationBonus -= 0.02 }
            PolicyType.HOUSING_DEVELOPMENT     ->   m.housingDevelopmentBonus += 0.15
            PolicyType.RESTRICT_DEVELOPMENT    ->   m.housingDevelopmentBonus -= 0.08
            PolicyType.TENANT_PROTECTIONS      ->   m.civicWellbeingBonus += 1.5
            PolicyType.SCHOOL_INVESTMENT       -> { m.educationQualityBonus += 15.0; m.civicWellbeingBonus += 1.0 }
            PolicyType.CUT_SCHOOL_BUDGET       ->   m.educationQualityBonus -= 10.0
            PolicyType.CLINIC_EXPANSION        -> { m.healthcareBonus += 0.15; m.civicWellbeingBonus += 1.0 }
            PolicyType.CUT_HEALTHCARE          ->   m.healthcareBonus -= 0.10
            PolicyType.ROAD_INVESTMENT         -> { m.transportRepairBonus += 0.20; m.civicWellbeingBonus += 0.5 }
            PolicyType.CUT_TRANSPORT           ->   m.transportRepairBonus -= 0.10
            PolicyType.GREEN_INITIATIVE        -> { m.environmentBonus += 0.20; m.civicWellbeingBonus += 0.5 }
            PolicyType.INDUSTRY_PRIORITY       -> { m.environmentBonus -= 0.10; m.businessFormationBonus += 0.02 }
            PolicyType.WELFARE_EXPANSION       -> { m.welfareBonus += 1.5; m.civicWellbeingBonus += 1.0 }
            PolicyType.WELFARE_REDUCTION       ->   m.welfareBonus -= 0.5
            PolicyType.CURFEW_POWERS           ->   m.crimeMultiplier *= 0.88
            PolicyType.CIVIL_RIGHTS_CHARTER    ->   m.civicWellbeingBonus += 2.0
            PolicyType.TECH_INVESTMENT         -> { m.businessFormationBonus += 0.03; m.civicWellbeingBonus += 0.5 }
            PolicyType.TRADITIONAL_ECONOMY     ->   m.businessFormationBonus += 0.01
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Daily update
    // ─────────────────────────────────────────────────────────────────

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        val dayIndex = SimTime.dayIndex(state.time)
        // Activate delayed policies that have reached their day
        var recompute = false
        state.activePolicies.values
            .filter { rec ->
                rec.status == PolicyStatus.PASSED.name &&
                rec.activatesAtDay != null && dayIndex == rec.activatesAtDay
            }
            .forEach { rec ->
                recompute = true
                val type = PolicyType.values().firstOrNull { it.name == rec.policyType }
                if (type != null) {
                    ctx.emit(
                        EventType.TOWN_MILESTONE,
                        "The effects of '${rec.title}' are now being felt across ${state.townName}",
                        severity = 0.4
                    )
                }
            }
        if (recompute) recomputeModifiers(state)
        // Deduct daily share of annual policy costs from municipal budget
        val dailyCost = state.activePolicies.values
            .filter { it.status == PolicyStatus.PASSED.name }
            .sumOf { rec -> PolicyType.values().firstOrNull { it.name == rec.policyType }?.annualCost ?: 0.0 } / 360.0
        if (dailyCost != 0.0) {
            state.municipalBudget.balance -= dailyCost
            if (dailyCost > 0) state.municipalBudget.serviceExpensesThisYear += dailyCost
        }
        // Welfare payments to unemployed adults
        val welfare = state.policyModifiers.welfareBonus
        if (welfare > 0.0) {
            state.livingResidents()
                .filter { r -> r.inTown && r.lifeStageAt(state.time) == LifeStage.ADULT && state.employmentOf(r) == null }
                .forEach { r ->
                    r.wealth += welfare
                    state.municipalBudget.balance -= welfare
                }
        }
    }
}
