package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.SimTime

/**
 * Tracks the town's environmental health — pollution, greenery, flood risk.
 * Environmental quality feeds back into resident health needs monthly.
 */
object EnvironmentSystem {

    const val UPDATE_INTERVAL_DAYS = 30L

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val env = state.townEnvironment

        // Pollution from industrial buildings
        val factories = state.buildings.values.count {
            it.type == BuildingType.FACTORY || it.type == BuildingType.WORKSHOP
        }
        val parks = state.buildings.values.count { it.type == BuildingType.PARK }
        val targetPollution = (factories * 8.0 - parks * 3.0).coerceIn(0.0, 100.0)
        env.pollution += (targetPollution - env.pollution) * 0.1

        // Greenery from parks and low density
        val living = state.livingResidents().size.coerceAtLeast(1)
        val density = state.buildings.size.toDouble() / living
        val targetGreenery = (parks * 15.0 + (1.0 - density.coerceAtMost(1.0)) * 40.0).coerceIn(0.0, 100.0)
        env.greenery += (targetGreenery - env.greenery) * 0.08

        // Flood risk accumulates from weather damage events; slow natural recovery
        val floodEvents = ctx.newEvents.count { it.type == EventType.WEATHER_DAMAGE }
        env.floodRisk = (env.floodRisk + floodEvents * 5.0).coerceAtMost(100.0)
        env.floodRisk -= 0.5 / (SimTime.DAYS_PER_YEAR / UPDATE_INTERVAL_DAYS)  // slow recovery
        env.floodRisk = env.floodRisk.coerceAtLeast(0.0)

        env.recalculate()

        // Apply environmental health to all residents (small nudge)
        val healthImpact = (env.overallHealth - 50.0) * 0.02
        for (r in state.detailedResidents()) {
            if (!r.inTown) continue
            r.needs.health = (r.needs.health + healthImpact).coerceIn(0.0, 100.0)
        }
    }
}
