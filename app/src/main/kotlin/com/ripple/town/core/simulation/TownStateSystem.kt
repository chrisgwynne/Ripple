package com.ripple.town.core.simulation

import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.CivilisationSnapshot
import com.ripple.town.core.model.CorruptionStatus
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType

/**
 * Computes the weekly aggregate [com.ripple.town.core.model.TownState] from live world data.
 * This is a derived summary, not an authoritative source — the truth lives in residents,
 * businesses, and districts. Other systems read TownState for cheap aggregate reads without
 * re-scanning everything.
 *
 * Wired in [SimulationCoordinator] at the start of the daily block, before systems that
 * consume town-level metrics.
 */
object TownStateSystem {

    const val UPDATE_INTERVAL_DAYS = 7L
    private const val MAX_HISTORY_ENTRIES = 200
    private const val SNAPSHOT_INTERVAL_DAYS = 360L

    fun updateWeekly(ctx: TickContext) {
        val state = ctx.state
        val ts = state.townState
        val residents = state.livingResidents()
        val adults = residents.filter { it.ageAt(ctx.now) >= 18 }
        val dayIndex = SimTime.dayIndex(ctx.now)

        // ─── Demographics ───────────────────────────────────────────────────
        ts.population = residents.size
        if (ts.population > ts.peakPopulation) ts.peakPopulation = ts.population

        // ─── Economy ────────────────────────────────────────────────────────
        if (adults.isNotEmpty()) {
            val employed = adults.count { r -> state.employmentOf(r) != null }
            ts.employmentRate = employed.toDouble() / adults.size
        }

        val openBusinesses = state.businesses.values.filter { it.open }
        ts.businessConfidence = if (openBusinesses.isEmpty()) 50.0 else
            openBusinesses.map { it.reputation }.average().coerceIn(0.0, 100.0)

        val allWealth = adults.map { it.wealth }
        if (allWealth.isNotEmpty()) {
            val mean = allWealth.average()
            val sorted = allWealth.sorted()
            val top10sum = sorted.takeLast((sorted.size / 10).coerceAtLeast(1)).sum()
            val bottom50sum = sorted.take((sorted.size / 2).coerceAtLeast(1)).sum()
            val total = sorted.sum().coerceAtLeast(1.0)
            ts.incomeInequality = if (total > 0) (top10sum - bottom50sum) / total else 0.3
            ts.incomeInequality = ts.incomeInequality.coerceIn(0.0, 1.0)
            ts.prosperityIndex = (mean / 500.0 * 50.0).coerceIn(0.0, 100.0)
        }

        val homes = state.homes()
        val occupiedHomes = homes.count { h ->
            state.residents.values.any { r -> r.alive && r.homeBuildingId == h.id }
        }
        ts.housingPressure = if (homes.isEmpty()) 0.3 else
            occupiedHomes.toDouble() / homes.size
        ts.housingAffordability = ((1.0 - ts.housingPressure) * 100.0).coerceIn(0.0, 100.0)

        // ─── Society ────────────────────────────────────────────────────────
        ts.crimeIndex = if (state.districts.isEmpty()) 10.0 else
            state.districts.values.map { it.crimeRate }.average() * 100.0

        ts.educationLevel = if (adults.isEmpty()) 50.0 else
            adults.map { it.skill(SkillType.TEACHING) * 8.0 }.average().coerceIn(0.0, 100.0)

        ts.healthIndex = if (residents.isEmpty()) 70.0 else
            residents.map { it.needs.health }.average().coerceIn(0.0, 100.0)

        // Community spirit: aggregate from COMMUNITY_LOYALTY belief positions
        val communityPositions = residents.mapNotNull {
            it.beliefs[BeliefTopic.COMMUNITY_LOYALTY]?.position
        }
        ts.communitySpirit = if (communityPositions.isEmpty()) 50.0 else
            ((communityPositions.average() + 1.0) * 50.0).coerceIn(0.0, 100.0)

        // ─── Governance ─────────────────────────────────────────────────────
        val trustPositions = residents.mapNotNull {
            it.beliefs[BeliefTopic.INSTITUTIONAL_TRUST]?.position
        }
        ts.institutionalTrust = if (trustPositions.isEmpty()) 50.0 else
            ((trustPositions.average() + 1.0) * 50.0).coerceIn(0.0, 100.0)

        val activeCorruption = state.corruptionIncidents.count { it.status == CorruptionStatus.ONGOING || it.status == CorruptionStatus.INVESTIGATED }
        ts.corruptionLevel = (activeCorruption * 12.0).coerceIn(0.0, 100.0)

        // ─── Environment & infrastructure ───────────────────────────────────
        val env = state.townEnvironment
        ts.environmentalQuality =
            ((env.greenery + (100.0 - env.pollution) + (100.0 - env.floodRisk)) / 3.0)
                .coerceIn(0.0, 100.0)

        ts.infrastructureQuality = if (state.buildings.isEmpty()) 60.0 else
            state.buildings.values.map { it.condition }.average().coerceIn(0.0, 100.0)

        ts.innovationIndex = ((state.techLevel.productivityBonus - 1.0) * 200.0).coerceIn(0.0, 100.0)

        // ─── Wellbeing ──────────────────────────────────────────────────────
        if (residents.isNotEmpty()) {
            val happiness = residents.map { r ->
                val n = r.needs
                (n.health + n.social + n.energy + n.purpose + n.financialSecurity + (100.0 - n.stress)) / 6.0
            }.average()
            ts.populationHappiness = happiness.coerceIn(0.0, 100.0)
        }

        val hopePositions = residents.mapNotNull {
            it.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]?.position
        }
        ts.hope = if (hopePositions.isEmpty()) 50.0 else
            ((hopePositions.average() + 1.0) * 50.0).coerceIn(0.0, 100.0)

        // ─── Annual snapshot ─────────────────────────────────────────────────
        if (dayIndex % SNAPSHOT_INTERVAL_DAYS == 0L) {
            takeAnnualSnapshot(ctx)
        }
    }

    private fun takeAnnualSnapshot(ctx: TickContext) {
        val state = ctx.state
        val ts = state.townState
        val year = SimTime.year(ctx.now)

        ts.yearlyPopulationHistory += ts.population
        ts.yearlyProsperityHistory += ts.prosperityIndex
        if (ts.yearlyPopulationHistory.size > MAX_HISTORY_ENTRIES) {
            ts.yearlyPopulationHistory.removeAt(0)
        }
        if (ts.yearlyProsperityHistory.size > MAX_HISTORY_ENTRIES) {
            ts.yearlyProsperityHistory.removeAt(0)
        }

        val snapshot = CivilisationSnapshot(
            id = state.nextSnapshotId++,
            takenAt = ctx.now,
            simYear = year,
            population = ts.population,
            prosperityIndex = ts.prosperityIndex,
            crimeIndex = ts.crimeIndex,
            educationLevel = ts.educationLevel,
            politicalStability = ts.politicalStability,
            communitySpirit = ts.communitySpirit,
            employmentRate = ts.employmentRate,
            incomeInequality = ts.incomeInequality,
            dominantArchetype = state.townDna.dominantArchetype,
            activeBusinessCount = state.businesses.values.count { it.open },
            institutionCount = state.institutionRecords.size
        )
        state.civilisationSnapshots += snapshot
        if (state.civilisationSnapshots.size > MAX_HISTORY_ENTRIES) {
            state.civilisationSnapshots.removeAt(0)
        }
    }
}
