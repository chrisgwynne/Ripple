package com.ripple.town.core.simulation

import com.ripple.town.core.model.AspirationType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.GoalStatus
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Opportunity
import com.ripple.town.core.model.OpportunityStatus
import com.ripple.town.core.model.OpportunityType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SkillType

/**
 * Monthly scan: for each OPEN commercial opportunity, find an eligible entrepreneur
 * and nudge them toward opening a business. Does not force business creation —
 * the existing GoalSystem + EconomySystem viability gates still apply.
 *
 * Part of Phase 6F (opportunity response). Called from SimulationCoordinator on the
 * same 30-day cadence as TownNeedsPlanner/OpportunityDetectionSystem so entrepreneurs
 * respond within a month of an opportunity being flagged.
 */
object OpportunityResponderSystem {

    const val UPDATE_INTERVAL_DAYS = 30L

    /** Minimum wealth to be considered a viable founder (matches GoalSystem.STARTUP_CAPITAL with headroom). */
    private const val ENTREPRENEUR_WEALTH_MIN = GoalSystem.STARTUP_CAPITAL * 1.5  // 600.0

    /** Probability that a given OPEN opportunity gets a response this month. */
    private const val RESPONSE_PROBABILITY = 0.35

    /** At most this many opportunities are acted on per monthly pass (avoids a wave of
     *  simultaneous business openings when many opportunities are flagged at once). */
    private const val MAX_RESPONSES_PER_MONTH = 2

    fun updateMonthly(ctx: TickContext) {
        val openOpportunities = ctx.state.opportunities.values
            .filter { it.status == OpportunityStatus.OPEN }
            .sortedBy { it.detectedAt }   // oldest first — longest-standing gaps get priority
            .take(MAX_RESPONSES_PER_MONTH)

        for (opp in openOpportunities) {
            if (!ctx.rng.nextBoolean(RESPONSE_PROBABILITY)) continue

            val candidate = findEligibleEntrepreneur(ctx, opp) ?: continue

            nudgeTowardBusiness(ctx, candidate, opp)

            // Mark as CLAIMED so other residents (and the next monthly pass) don't double-respond.
            ctx.state.opportunities[opp.id] = opp.copy(
                status = OpportunityStatus.CLAIMED,
                claimedByResidentId = candidate.id
            )
        }
    }

    // -------------------------------------------------------------------------
    // Candidate selection
    // -------------------------------------------------------------------------

    private fun findEligibleEntrepreneur(ctx: TickContext, opp: Opportunity): Resident? {
        val relevantSkill = opportunityToSkill(opp.type)

        return ctx.state.residents.values
            .filter { r ->
                r.inTown &&
                r.detailLevel != DetailLevel.BACKGROUND &&
                r.lifeStageAt(ctx.now) == LifeStage.ADULT &&
                r.ageAt(ctx.now) in 18..60 &&
                r.wealth >= ENTREPRENEUR_WEALTH_MIN &&
                !ownsOpenBusiness(ctx, r) &&
                // Not already chasing an open START_BUSINESS goal
                r.goals.none { it.type == GoalType.START_BUSINESS && it.status == GoalStatus.ACTIVE }
            }
            .filter { r ->
                // Accept residents with a relevant skill OR an entrepreneurial aspiration;
                // if no skill is associated with this opportunity type, any resident qualifies.
                relevantSkill == null ||
                r.skill(relevantSkill) >= 30.0 ||
                hasEntrepreneurialAspiration(r)
            }
            .sortedByDescending { r ->
                // Score so the most promising candidate rises to the top.
                r.wealth +
                (if (hasEntrepreneurialAspiration(r)) 5_000.0 else 0.0) +
                (if (r.detailLevel == DetailLevel.DETAILED) 2_000.0 else 0.0) +
                (if (relevantSkill != null) r.skill(relevantSkill) * 20.0 else 0.0) +
                // Prefer residents who live in the same district as the opportunity.
                (if (sameDistrictAs(ctx, r, opp)) 3_000.0 else 0.0) +
                // High ambition tilts toward action.
                r.effectivePersonality().ambition * 1_000.0
            }
            .firstOrNull()
    }

    private fun hasEntrepreneurialAspiration(r: Resident): Boolean =
        r.aspirations.any {
            it.type == AspirationType.OWN_A_BUSINESS ||
            it.type == AspirationType.LEAVE_A_LEGACY ||
            it.type == AspirationType.MASTER_A_CRAFT
        }

    private fun sameDistrictAs(ctx: TickContext, r: Resident, opp: Opportunity): Boolean {
        if (opp.districtId == null) return false
        val homeDistrict = r.homeBuildingId?.let { ctx.state.buildings[it]?.districtId }
        return homeDistrict == opp.districtId
    }

    private fun ownsOpenBusiness(ctx: TickContext, r: Resident): Boolean =
        ctx.state.businesses.values.any { it.ownerId == r.id && it.open }

    // -------------------------------------------------------------------------
    // Nudge
    // -------------------------------------------------------------------------

    private fun nudgeTowardBusiness(ctx: TickContext, resident: Resident, opp: Opportunity) {
        // Plant an idea seed so the existing GoalSystem circumstance check finds it
        // on its next daily pass and seeds a START_BUSINESS goal naturally.
        val hint = ideaSeedHint(opp.type)
        if (resident.ideaSeeds.size < 5) {   // bounded — mirrors the pattern used elsewhere
            resident.ideaSeeds += hint
        }

        // Also ensure the resident is aware of the specific opportunity — if a suitable
        // vacant building is listed, note it so they have a concrete target to investigate.
        if (opp.suitableBuildingIds.isNotEmpty()) {
            val buildingNote = "opportunity:${opp.id}:building:${opp.suitableBuildingIds.first()}"
            if (buildingNote !in resident.awareness) {
                resident.awareness += buildingNote
            }
        }

        // Emit a low-severity event so the newspaper can cover local entrepreneurial intent.
        ctx.emit(
            type = EventType.OPPORTUNITY_DETECTED,
            description = "${resident.fullName} is considering opening a business to fill the gap: ${opp.evidence.ifBlank { opp.type.name.lowercase().replace('_', ' ') }}.",
            sourceResidentId = resident.id,
            severity = 0.3
        )
    }

    // -------------------------------------------------------------------------
    // Opportunity-type mappings
    // -------------------------------------------------------------------------

    private fun opportunityToSkill(type: OpportunityType): SkillType? = when (type) {
        OpportunityType.CAFE_SHORTAGE,
        OpportunityType.RESTAURANT_SHORTAGE    -> SkillType.COOKING
        OpportunityType.PHARMACY_SHORTAGE,
        OpportunityType.HEALTHCARE_SHORTAGE    -> SkillType.MEDICINE
        OpportunityType.CHILDCARE_SHORTAGE,
        OpportunityType.SCHOOL_SHORTAGE        -> SkillType.TEACHING
        OpportunityType.HARDWARE_SHORTAGE,
        OpportunityType.TRADES_DEMAND          -> SkillType.CARPENTRY
        OpportunityType.FOOD_RETAIL_SHORTAGE,
        OpportunityType.CONVENIENCE_SHORTAGE,
        OpportunityType.INDUSTRIAL_DEMAND,
        OpportunityType.OFFICE_DEMAND          -> SkillType.BUSINESS
        else                                   -> null
    }

    private fun ideaSeedHint(type: OpportunityType): String = when (type) {
        OpportunityType.CAFE_SHORTAGE          -> "The town could use a good café."
        OpportunityType.RESTAURANT_SHORTAGE    -> "There's nowhere decent to eat around here."
        OpportunityType.FOOD_RETAIL_SHORTAGE,
        OpportunityType.CONVENIENCE_SHORTAGE   -> "People need somewhere to buy their basics."
        OpportunityType.PHARMACY_SHORTAGE      -> "A pharmacy would be a real asset to this place."
        OpportunityType.HARDWARE_SHORTAGE      -> "There's always demand for a decent hardware shop."
        OpportunityType.CHILDCARE_SHORTAGE     -> "Parents in this town are struggling to find childcare."
        OpportunityType.TRADES_DEMAND          -> "There's work here for a skilled tradesperson."
        OpportunityType.INDUSTRIAL_DEMAND      -> "Local industry could use a workshop."
        OpportunityType.OFFICE_DEMAND          -> "There's room for a small business hub here."
        OpportunityType.VACANT_PREMISES_REUSE,
        OpportunityType.CONVERSION_CANDIDATE   -> "That empty building could be turned into something worthwhile."
        else                                   -> "There's a gap in this town that someone should fill."
    }
}
