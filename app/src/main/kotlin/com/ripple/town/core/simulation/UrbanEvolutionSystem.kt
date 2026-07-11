package com.ripple.town.core.simulation

import com.ripple.town.core.model.DevelopmentStage
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.isHome
import com.ripple.town.core.model.HistoricalMilestoneType
import com.ripple.town.core.model.SimTime

/**
 * Detects urban-scale growth and decline signals and triggers appropriate responses.
 *
 * Growth path: when housing pressure exceeds 88%, proposes new development if there
 * is no active housing project in the pipeline.
 *
 * Decline path: when a large fraction of buildings are derelict or a cluster of
 * businesses close in quick succession, emits a "hollowing out" warning event so
 * the player and newspaper can see the town contracting.
 *
 * Population surge: when the town grows more than 25% over 5 years, mints a
 * [com.ripple.town.core.model.HistoricalMilestone].
 */
object UrbanEvolutionSystem {

    const val UPDATE_INTERVAL_DAYS = 30L
    private const val HOUSING_PRESSURE_THRESHOLD = 0.88
    private const val SURGE_FRACTION = 0.25
    private const val SURGE_WINDOW_YEARS = 5
    private const val DERELICT_FRACTION_WARN = 0.25
    private const val HOLLOWING_BUSINESS_CLOSURES = 4
    private const val HOLLOWING_OPEN_THRESHOLD = 3

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val ts = state.townState
        val year = SimTime.year(ctx.now)

        checkHousingPressure(ctx)
        checkPopulationSurge(ctx, year)
        checkUrbanDecline(ctx, year)
    }

    private fun checkHousingPressure(ctx: TickContext) {
        val ts = ctx.state.townState
        if (ts.housingPressure <= HOUSING_PRESSURE_THRESHOLD) return

        // Only propose if there is no active housing development in the pipeline
        val hasActiveHousingProject = ctx.state.developmentProjects.values.any { proj ->
            proj.stage != DevelopmentStage.COMPLETE && proj.stage != DevelopmentStage.CANCELLED &&
                proj.stage != DevelopmentStage.REJECTED && proj.buildingType.isHome
        }
        if (!hasActiveHousingProject) {
            ctx.emit(
                type = EventType.DEVELOPMENT_PROPOSED,
                description = "Housing demand is critical — the town urgently needs new homes.",
                buildingId = null
            )
        }
    }

    private fun checkPopulationSurge(ctx: TickContext, year: Int) {
        val ts = ctx.state.townState
        val history = ts.yearlyPopulationHistory
        if (history.size < SURGE_WINDOW_YEARS) return

        val oldPop = history[history.size - SURGE_WINDOW_YEARS]
        if (oldPop <= 0) return
        val growth = (ts.population - oldPop).toDouble() / oldPop

        if (growth > SURGE_FRACTION) {
            val alreadyRecorded = ctx.state.historicalMilestones.any { m ->
                m.type == HistoricalMilestoneType.POPULATION_SURGE.name &&
                    ctx.now - m.occurredAt < SimTime.MINUTES_PER_YEAR * 6
            }
            if (!alreadyRecorded) {
                val milestone = com.ripple.town.core.model.HistoricalMilestone(
                    id = ctx.state.nextMilestoneId++,
                    title = "The Great Arrival, Year $year",
                    description = "The population swelled by more than a quarter in five years. New streets, new faces, new life.",
                    type = HistoricalMilestoneType.POPULATION_SURGE.name,
                    occurredAt = ctx.now,
                    impactScore = 0.7
                )
                ctx.state.historicalMilestones += milestone
                ctx.emit(
                    type = EventType.TOWN_MILESTONE,
                    description = milestone.description
                )
            }
        }
    }

    private fun checkUrbanDecline(ctx: TickContext, year: Int) {
        val state = ctx.state
        val buildings = state.buildings.values
        if (buildings.isEmpty()) return

        // Derelict fraction warning
        val derelictFraction = buildings.count { it.condition < 20.0 }.toDouble() / buildings.size
        if (derelictFraction > DERELICT_FRACTION_WARN) {
            ctx.emit(
                type = EventType.TOWN_MILESTONE,
                description = "A quarter of the town's buildings stand abandoned or near-collapsed."
            )
        }

        // Business hollowing-out: many closures, few open
        val openCount = state.businesses.values.count { it.open }
        val recentClosures = ctx.newEvents.count {
            it.type == EventType.BUSINESS_CLOSED
        }
        if (recentClosures >= HOLLOWING_BUSINESS_CLOSURES && openCount < HOLLOWING_OPEN_THRESHOLD) {
            ctx.emit(
                type = EventType.TOWN_MILESTONE,
                description = "The high street is emptying out — businesses are closing faster than they open."
            )
        }
    }
}
