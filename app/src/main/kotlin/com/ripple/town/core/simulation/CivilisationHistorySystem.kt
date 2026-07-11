package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.HistoricalMilestone
import com.ripple.town.core.model.HistoricalMilestoneType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.CorruptionStatus
import com.ripple.town.core.model.TownEraType

/**
 * Detects significant moments in the town's civilisation arc and mints [HistoricalMilestone]
 * records — named events that the town will remember long after they pass.
 *
 * Distinct from [TownEraSystem] (which tracks ongoing states like GOLDEN_AGE) and
 * [LegacySystem] (which records individual deaths). CivilisationHistorySystem produces
 * specific, titled, permanently-dated turning points: "The Mill Closure", "The Great Boom
 * of Year 12", "The Corruption Scandal of Mayor Alderton."
 *
 * Memory decay: each milestone loses [HistoricalMilestone.MEMORY_DECAY_PER_YEAR] strength
 * per sim-year, flooring at [HistoricalMilestone.MEMORY_FLOOR] — nothing is ever fully
 * forgotten once it rises to legend status.
 */
object CivilisationHistorySystem {

    const val UPDATE_INTERVAL_DAYS = 30L
    private const val DEDUP_WINDOW_YEARS = 5
    private const val MAX_MILESTONES = 200

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val ts = state.townState
        val year = SimTime.year(ctx.now)

        // ─── Memory decay (annual pass) ───────────────────────────────────
        val isAnnual = (SimTime.dayIndex(ctx.now) % 360L) < UPDATE_INTERVAL_DAYS
        if (isAnnual) {
            for (m in state.historicalMilestones) {
                m.collectiveMemoryStrength =
                    (m.collectiveMemoryStrength - HistoricalMilestone.MEMORY_DECAY_PER_YEAR)
                        .coerceAtLeast(HistoricalMilestone.MEMORY_FLOOR)
                // Graduate to landmark status after 25+ years with high impact
                if (!m.isLandmark && m.impactScore >= 0.7) {
                    val yearsOld = year - SimTime.year(m.occurredAt)
                    if (yearsOld >= 25) m.isLandmark = true
                }
            }
        }

        // ─── Detect civilisation-scale events ────────────────────────────
        detectEconomicCollapse(ctx, year)
        detectEconomicBoom(ctx, year)
        detectPoliticalScandal(ctx, year)
        detectPopulationDecline(ctx, year)
        detectGoldenPeriod(ctx, year)
        detectMajorFlood(ctx, year)

        // ─── Cap milestone list ───────────────────────────────────────────
        if (state.historicalMilestones.size > MAX_MILESTONES) {
            state.historicalMilestones.sortByDescending { it.collectiveMemoryStrength }
            while (state.historicalMilestones.size > MAX_MILESTONES) {
                state.historicalMilestones.removeLast()
            }
        }
    }

    private fun recordMilestone(
        ctx: TickContext,
        year: Int,
        title: String,
        description: String,
        type: HistoricalMilestoneType,
        impact: Double,
        residentIds: List<Long> = emptyList()
    ) {
        val state = ctx.state
        if (recentlyRecorded(state.historicalMilestones, type, ctx.now)) return

        val milestone = HistoricalMilestone(
            id = state.nextMilestoneId++,
            title = title,
            description = description,
            type = type.name,
            occurredAt = ctx.now,
            impactScore = impact,
            relatedResidentIds = residentIds
        )
        state.historicalMilestones += milestone

        ctx.emit(
            type = EventType.TOWN_MILESTONE,
            description = "$title: $description"
        )
    }

    private fun recentlyRecorded(
        milestones: List<HistoricalMilestone>,
        type: HistoricalMilestoneType,
        now: Long
    ): Boolean {
        val windowMinutes = DEDUP_WINDOW_YEARS * SimTime.MINUTES_PER_YEAR
        return milestones.any { m ->
            m.type == type.name && (now - m.occurredAt) < windowMinutes
        }
    }

    // ─── Individual detectors ─────────────────────────────────────────────

    private fun detectEconomicCollapse(ctx: TickContext, year: Int) {
        val ts = ctx.state.townState
        if (ts.prosperityIndex < 20.0) {
            recordMilestone(ctx, year,
                title = "The Hard Times of Year $year",
                description = "Poverty swept the town. Few households had enough.",
                type = HistoricalMilestoneType.ECONOMIC_COLLAPSE,
                impact = 0.8
            )
        }
    }

    private fun detectEconomicBoom(ctx: TickContext, year: Int) {
        val ts = ctx.state.townState
        if (ts.prosperityIndex > 80.0) {
            recordMilestone(ctx, year,
                title = "The Boom of Year $year",
                description = "Prosperity touched nearly every family in town.",
                type = HistoricalMilestoneType.ECONOMIC_BOOM,
                impact = 0.7
            )
        }
    }

    private fun detectPoliticalScandal(ctx: TickContext, year: Int) {
        val recentExposed = ctx.state.corruptionIncidents.count { inc ->
            inc.status == CorruptionStatus.EXPOSED.name || inc.status == CorruptionStatus.PROSECUTED.name
                && inc.discoveredAt != null && ctx.now - inc.discoveredAt!! < SimTime.MINUTES_PER_YEAR
        }
        if (recentExposed >= 2) {
            val mayor = ctx.state.governmentRecords.lastOrNull()
            val name = mayor?.leaderName ?: "the council"
            recordMilestone(ctx, year,
                title = "The Scandal of Year $year",
                description = "Corruption was exposed in $name's administration.",
                type = HistoricalMilestoneType.POLITICAL_SCANDAL,
                impact = 0.75,
                residentIds = listOf(mayor?.leaderId ?: 0L).filter { it > 0L }
            )
        }
    }

    private fun detectPopulationDecline(ctx: TickContext, year: Int) {
        val ts = ctx.state.townState
        if (ts.peakPopulation > 0) {
            val declineFraction = (ts.peakPopulation - ts.population).toDouble() / ts.peakPopulation
            if (declineFraction > 0.2) {
                recordMilestone(ctx, year,
                    title = "The Exodus of Year $year",
                    description = "More than a fifth of the population left, never to return.",
                    type = HistoricalMilestoneType.POPULATION_DECLINE,
                    impact = 0.65
                )
            }
        }
    }

    private fun detectGoldenPeriod(ctx: TickContext, year: Int) {
        val ts = ctx.state.townState
        if (ts.prosperityIndex > 70.0 && ts.communitySpirit > 65.0 && ts.crimeIndex < 15.0) {
            recordMilestone(ctx, year,
                title = "A Golden Year: $year",
                description = "Prosperity, community and safety came together as rarely before.",
                type = HistoricalMilestoneType.GOLDEN_PERIOD,
                impact = 0.9
            )
        }
    }

    private fun detectMajorFlood(ctx: TickContext, year: Int) {
        val activeEras = ctx.state.townEras.filter { it.endedAt == null }
        val inFlood = activeEras.any { it.type == TownEraType.GREAT_FLOOD }
        if (inFlood) {
            recordMilestone(ctx, year,
                title = "The Flood of Year $year",
                description = "The river broke its banks and the low streets were under water.",
                type = HistoricalMilestoneType.NATURAL_DISASTER,
                impact = 0.7
            )
        }
    }
}
