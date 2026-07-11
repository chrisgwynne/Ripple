package com.ripple.town.core.simulation

import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.TownArchetype
import com.ripple.town.core.model.TownEraType

/**
 * Evolves the town's emergent personality ([com.ripple.town.core.model.TownDna]) from objective
 * signals — what businesses operate, who lives here, what eras have passed.
 *
 * Every 30 days, each archetype is scored 0–100 from the live world state, then smoothed:
 *   `newScore = currentScore * 0.85 + rawScore * 0.15`
 * so identity drifts slowly, reflecting decades of accumulated character rather than reacting
 * to single-month fluctuations.
 *
 * Emits [EventType.TOWN_MILESTONE] when the dominant archetype changes, so the newspaper and
 * history can record "Ashcombe is now primarily an Academic town."
 */
object TownDnaSystem {

    const val UPDATE_INTERVAL_DAYS = 30L
    private const val SMOOTH_RETAIN = 0.85
    private const val SMOOTH_NEW    = 0.15

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val dna = state.townDna
        val residents = state.livingResidents()
        val adults = residents.filter { it.ageAt(ctx.now) >= 18 }
        val openBiz = state.businesses.values.filter { it.open }
        val activeEras = state.townEras.filter { it.endedAt == null }
        val year = SimTime.year(ctx.now)

        if (dna.foundingYear == 0) dna.foundingYear = year

        // ─── Score each archetype from live signals ────────────────────────
        val scores = mutableMapOf<TownArchetype, Double>()

        val totalBiz = openBiz.size.coerceAtLeast(1)
        val factoryCount = openBiz.count { it.type == BusinessType.FACTORY || it.type == BusinessType.WORKSHOP }
        scores[TownArchetype.INDUSTRIAL] = (factoryCount.toDouble() / totalBiz * 100.0).coerceIn(0.0, 100.0)

        val pubCount = openBiz.count { it.type == BusinessType.PUB || it.type == BusinessType.CAFE }
        scores[TownArchetype.NIGHTLIFE] = (pubCount.toDouble() / totalBiz * 100.0).coerceIn(0.0, 100.0)

        val schools = state.buildings.values.count { it.type == BuildingType.SCHOOL }
        val avgTeaching = if (adults.isEmpty()) 0.0 else adults.map { it.skill(SkillType.TEACHING) }.average()
        scores[TownArchetype.ACADEMIC] = ((schools * 20.0) + (avgTeaching * 5.0)).coerceIn(0.0, 100.0)

        val bookshops = openBiz.count { it.type == BusinessType.BOOKSHOP }
        val avgCreativity = if (adults.isEmpty()) 0.0 else
            adults.map { it.personality.curiosity }.average()
        scores[TownArchetype.CREATIVE] = ((bookshops * 15.0) + (avgCreativity * 60.0)).coerceIn(0.0, 100.0)

        val avgReligious = if (residents.isEmpty()) 20.0 else
            residents.mapNotNull { it.beliefs[BeliefTopic.INSTITUTIONAL_TRUST]?.position }
                .map { (it + 1.0) * 10.0 }.average()
        scores[TownArchetype.RELIGIOUS] = avgReligious.coerceIn(0.0, 100.0)

        scores[TownArchetype.WORKING_CLASS] = (factoryCount.toDouble() / totalBiz * 80.0).coerceIn(0.0, 100.0)

        val hasCollapseEra = activeEras.any { it.type == TownEraType.ECONOMIC_COLLAPSE }
        val vacantFraction = if (state.buildings.isEmpty()) 0.0 else
            state.buildings.values.count { it.condition < 30.0 }.toDouble() / state.buildings.size
        scores[TownArchetype.DECLINING] = if (hasCollapseEra) 80.0 else
            (vacantFraction * 100.0).coerceIn(0.0, 100.0)

        val hasGoldenEra = activeEras.any { it.type == TownEraType.GOLDEN_AGE }
        val populationGrowthScore = if (state.townState.yearlyPopulationHistory.size >= 2) {
            val prev = state.townState.yearlyPopulationHistory[state.townState.yearlyPopulationHistory.size - 2]
            val curr = state.townState.population
            if (prev > 0) ((curr - prev).toDouble() / prev * 200.0).coerceIn(0.0, 100.0) else 0.0
        } else 0.0
        scores[TownArchetype.GROWING] = if (hasGoldenEra) 75.0 else populationGrowthScore

        val avgSocialOpenness = if (residents.isEmpty()) 50.0 else
            residents.mapNotNull { it.beliefs[BeliefTopic.COMMUNITY_LOYALTY]?.position }
                .map { (it + 1.0) * 50.0 }.average()
        scores[TownArchetype.FAMILY_ORIENTED] = ((100.0 - avgSocialOpenness) * 0.5).coerceIn(0.0, 100.0)

        scores[TownArchetype.AGRICULTURAL] = 20.0  // no agricultural era type yet; base score

        val milestoneCount = state.historicalMilestones.size
        scores[TownArchetype.HISTORIC] = (milestoneCount * 5.0).coerceIn(0.0, 100.0)

        val avgOptimism = if (residents.isEmpty()) 50.0 else
            residents.mapNotNull { it.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]?.position }
                .map { (it + 1.0) * 50.0 }.average()
        val crisisResolved = state.historicalMilestones.count { m ->
            m.type == com.ripple.town.core.model.HistoricalMilestoneType.CRISIS_OVERCOME.name
        }
        scores[TownArchetype.RESILIENT] = ((crisisResolved * 10.0) + avgOptimism * 0.3).coerceIn(0.0, 100.0)

        scores[TownArchetype.TECHNOLOGY] = ((state.techLevel.productivityBonus - 1.0) * 200.0).coerceIn(0.0, 100.0)

        val grocer = openBiz.count { it.type == BusinessType.GROCER || it.type == BusinessType.TAILOR }
        scores[TownArchetype.FINANCIAL] = (grocer.toDouble() / totalBiz * 100.0).coerceIn(0.0, 100.0)

        scores[TownArchetype.TOURIST] = 5.0  // placeholder: no tourist metric yet

        // ─── Smooth and write ──────────────────────────────────────────────
        for ((archetype, rawScore) in scores) {
            val current = dna.archetypeScores[archetype.name] ?: 0.0
            dna.archetypeScores[archetype.name] = current * SMOOTH_RETAIN + rawScore * SMOOTH_NEW
        }

        // ─── Dominant / secondary archetypes ──────────────────────────────
        val sorted = dna.archetypeScores.entries.sortedByDescending { it.value }
        val prevDominant = dna.dominantArchetype
        dna.dominantArchetype = sorted.firstOrNull()?.key
        dna.secondaryArchetype = sorted.getOrNull(1)?.key

        if (dna.dominantArchetype != null && dna.dominantArchetype != prevDominant && prevDominant != null) {
            dna.archetypeHistory += prevDominant
            if (dna.archetypeHistory.size > 20) dna.archetypeHistory.removeAt(0)
            ctx.emit(
                type = EventType.TOWN_MILESTONE,
                description = "Ashcombe's character has shifted: it is now defined as " +
                    "${TownArchetype.values().firstOrNull { it.name == dna.dominantArchetype }?.label ?: dna.dominantArchetype}."
            )
        }

        // ─── Cultural dimensions from resident aggregates ──────────────────
        if (adults.isNotEmpty()) {
            val avgAmbition = adults.map { it.personality.ambition }.average()
            val target = avgAmbition * 100.0
            dna.workEthic = dna.workEthic * SMOOTH_RETAIN + target * SMOOTH_NEW

            val avgRisk = adults.mapNotNull { it.beliefs[BeliefTopic.RISK_TOLERANCE]?.position }
                .map { (it + 1.0) * 50.0 }.average().let { if (it.isNaN()) 40.0 else it }
            dna.riskAppetite = dna.riskAppetite * SMOOTH_RETAIN + avgRisk * SMOOTH_NEW

            val avgTrust = adults.mapNotNull { it.beliefs[BeliefTopic.TRUST_IN_GOVERNMENT]?.position }
                .map { (it + 1.0) * 50.0 }.average().let { if (it.isNaN()) 50.0 else it }
            dna.trustInOthers = dna.trustInOthers * SMOOTH_RETAIN + avgTrust * SMOOTH_NEW

            val communityTarget = state.townState.communitySpirit
            dna.communityBinds = dna.communityBinds * SMOOTH_RETAIN + communityTarget * SMOOTH_NEW

            val openTarget = adults.mapNotNull { it.beliefs[BeliefTopic.SOCIAL_OPENNESS]?.position }
                .map { (it + 1.0) * 50.0 }.average().let { if (it.isNaN()) 50.0 else it }
            dna.toleranceScore = dna.toleranceScore * SMOOTH_RETAIN + openTarget * SMOOTH_NEW
        }
    }
}
