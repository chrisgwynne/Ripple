package com.ripple.town.core.simulation

import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.CommunityGroupType
import com.ripple.town.core.model.CulturalDimension
import com.ripple.town.core.model.IdentityLabel
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.TownCultureRecord

/**
 * Monthly scan that derives the town's cultural identity from what is actually
 * happening in the simulation — business mix, occupation spread, migration,
 * relationship density, and crime/sentiment. No external inputs; purely emergent.
 */
object TownCultureSystem {

    const val UPDATE_INTERVAL_DAYS = 30L

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val culture = state.townCulture
        culture.dimensions.clear()
        culture.lastUpdatedAt = ctx.now

        val living = state.livingResidents()
        val population = living.size.coerceAtLeast(1)

        // Business density → ENTREPRENEURIAL
        val openBusinesses = state.businesses.values.filter { it.open }
        val businessDensity = openBusinesses.size.toDouble() / population
        if (businessDensity > 0.15) culture.dimensions += CulturalDimension.ENTREPRENEURIAL

        // Manual-trade workers → WORKING_CLASS
        val manualTypes = setOf(BusinessType.WORKSHOP, BusinessType.FACTORY, BusinessType.HARDWARE)
        val manualWorkers = state.residents.values.count { r ->
            r.alive && state.businesses.values.any { b -> b.ownerId == r.id && b.type in manualTypes }
        }
        if (manualWorkers.toDouble() / population > 0.06) culture.dimensions += CulturalDimension.WORKING_CLASS

        // Teaching skill → ACADEMIC
        val scholars = living.count { (it.skills[SkillType.TEACHING] ?: 0.0) >= 50.0 }
        if (scholars.toDouble() / population > 0.05) culture.dimensions += CulturalDimension.ACADEMIC

        // Creativity skill → ARTISTIC
        val artists = living.count { (it.skills[SkillType.CREATIVITY] ?: 0.0) >= 50.0 }
        if (artists.toDouble() / population > 0.05) culture.dimensions += CulturalDimension.ARTISTIC

        // High political interest → POLITICAL
        val politicians = living.count { it.politicalInterest > 0.6 }
        if (politicians.toDouble() / population > 0.10) culture.dimensions += CulturalDimension.POLITICAL

        // Newcomers → DIVERSE
        val newcomers = living.count { r -> r.identityFacets.any { it.label == IdentityLabel.NEWCOMER } }
        if (newcomers.toDouble() / population > 0.20) culture.dimensions += CulturalDimension.DIVERSE

        // Average relationship familiarity → TIGHT_KNIT
        val rels = state.relationships.values
        val avgFamiliarity = if (rels.isEmpty()) 0.0 else rels.sumOf { it.familiarity } / rels.size
        if (avgFamiliarity > 55.0) culture.dimensions += CulturalDimension.TIGHT_KNIT

        // Sentiment → TROUBLED or SAFE (mutually exclusive)
        val safety = state.townSentiment.safety
        when {
            safety < 35.0 -> culture.dimensions += CulturalDimension.TROUBLED
            safety > 70.0 -> culture.dimensions += CulturalDimension.SAFE
        }

        // Community-group activity nudges cultural identity (CE1).
        // Active groups with ≥5 members and reputation ≥60 push the town toward the
        // dimension most associated with their type.  Each qualifying group contributes
        // a +0.5 / +0.3 point to a running tally; once the tally clears the threshold
        // the dimension is added even if the base metrics didn't reach it on their own.
        var tightKnitBonus = 0.0
        var safeBonus = 0.0
        for (group in state.communityGroups.values) {
            if (!group.active) continue
            if (group.memberIds.size < 5) continue
            if (group.reputation < 60.0) continue
            when (group.type) {
                CommunityGroupType.SPORTS_CLUB  -> tightKnitBonus += 0.5
                CommunityGroupType.FAITH_GROUP  -> tightKnitBonus += 0.5
                CommunityGroupType.CHARITY      -> safeBonus      += 0.3
                else                            -> Unit
            }
        }
        // A single qualifying group (bonus ≥ threshold) is enough to add the dimension.
        if (tightKnitBonus >= 0.5) culture.dimensions += CulturalDimension.TIGHT_KNIT
        if (safeBonus      >= 0.3) culture.dimensions += CulturalDimension.SAFE

        // Append a snapshot to the persistent trajectory so the town accumulates an
        // identity over decades. Cap at 120 entries (~10 sim years) to bound memory.
        state.townCultureHistory += TownCultureRecord(
            tick = ctx.now,
            dimensions = culture.dimensions.toSet(),
            description = culture.describe()
        )
        if (state.townCultureHistory.size > 120) state.townCultureHistory.removeAt(0)
    }
}
