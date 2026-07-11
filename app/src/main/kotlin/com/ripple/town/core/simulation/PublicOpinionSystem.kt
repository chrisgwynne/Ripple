package com.ripple.town.core.simulation

import com.ripple.town.core.model.*

/**
 * Tracks per-resident government satisfaction, derived from personal experience
 * rather than abstract ideology — residents who have been burgled care about
 * policing; residents who lost a business care about economic policy; residents
 * who struggle to pay rent care about housing. The rolling approvalRating is
 * what the newspaper prints and what opposition parties campaign against.
 */
object PublicOpinionSystem {

    const val UPDATE_INTERVAL_DAYS = 7L

    fun updateWeekly(ctx: TickContext) {
        val state = ctx.state
        val data = state.publicOpinionData
        val detailed = state.detailedResidents().filter { it.inTown }
        if (detailed.isEmpty()) return
        data.lastUpdateDay = SimTime.dayIndex(state.time)
        val mods = state.policyModifiers
        val mayorPartyId = state.residents[state.mayorId]?.partyId
        val activeCorruption = state.corruptionIncidents.any { inc ->
            inc.status == CorruptionStatus.INVESTIGATED.name ||
            inc.status == CorruptionStatus.EXPOSED.name
        }
        detailed.forEach { r ->
            val current = data.residentSatisfaction.getOrDefault(r.id, 50.0)
            val delta = computeDelta(r, state, mods, mayorPartyId, activeCorruption)
            data.residentSatisfaction[r.id] = (current + delta).coerceIn(0.0, 100.0)
        }
        // Remove satisfaction entries for residents who have left or died
        data.residentSatisfaction.keys.retainAll { id -> state.residents[id]?.inTown == true }
        // Rolling approval rating = mean satisfaction
        data.approvalRating = if (data.residentSatisfaction.isEmpty()) 50.0
            else data.residentSatisfaction.values.average()
        // Update current government record peaks
        state.currentGovernmentId?.let { gid ->
            state.governmentRecords.find { it.id == gid }?.also { rec ->
                if (data.approvalRating > rec.peakApproval) rec.peakApproval = data.approvalRating
                if (data.approvalRating < rec.lowestApproval) rec.lowestApproval = data.approvalRating
            }
        }
    }

    private fun computeDelta(
        resident: Resident,
        state: WorldState,
        mods: PolicyModifiers,
        mayorPartyId: Long?,
        activeCorruption: Boolean
    ): Double {
        var delta = 0.0
        val beliefs = resident.beliefs
        // Police investment: residents who trust police approve; those who distrust are indifferent
        val policeTrust = beliefs[BeliefTopic.TRUST_IN_POLICE]?.position ?: 0.0
        if (mods.crimeMultiplier < 0.95) delta += policeTrust * 0.6
        if (mods.crimeMultiplier > 1.05) delta += policeTrust * -0.6
        // Spending policies: collectivists approve high spending; individualists disapprove
        val collectivism = -(beliefs[BeliefTopic.INDIVIDUALISM_VS_COLLECTIVISM]?.position ?: 0.0)
        val highSpend = state.activePolicies.values.count { rec ->
            rec.status == PolicyStatus.PASSED.name &&
            PolicyType.values().firstOrNull { it.name == rec.policyType }?.annualCost?.let { c -> c > 4_000 } == true
        }
        delta += collectivism * highSpend * 0.15
        // Environmental policy approval from environmentally-concerned residents
        val envConcern = beliefs[BeliefTopic.ENVIRONMENTAL_CONCERN]?.position ?: 0.0
        if (mods.environmentBonus > 0.0) delta += envConcern * 0.4
        // Corruption: trust falls for everyone; more so for high-institutional-trust residents
        if (activeCorruption) {
            val instTrust = beliefs[BeliefTopic.INSTITUTIONAL_TRUST]?.position ?: 0.0
            delta += -1.2 - instTrust * 0.6
        }
        // Same party as mayor → modest satisfaction boost
        if (resident.partyId != null && resident.partyId == mayorPartyId) delta += 0.4
        // Budget deficit → economic anxiety bleeds into satisfaction
        if (state.municipalBudget.balance < -5_000) delta -= 0.8
        if (state.municipalBudget.balance > 15_000) delta += 0.3
        // Personal wellbeing: happier residents rate the government better
        delta += (resident.needs.wellbeing() - 50.0) * 0.015
        return delta.coerceIn(-4.0, 4.0)
    }
}
