package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.RouteCondition
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.TransportRoute

/**
 * Road and path connectivity between buildings. Routes degrade over time
 * and are repaired when the municipal budget allows. Poor routes reduce
 * business footfall for the connected premises.
 */
object TransportSystem {

    const val UPDATE_INTERVAL_DAYS = 30L
    private val CONDITION_VALUES = RouteCondition.values()

    fun updateMonthly(ctx: TickContext) {
        ensureRoutes(ctx)
        val state = ctx.state
        val yearFraction = UPDATE_INTERVAL_DAYS.toDouble() / SimTime.DAYS_PER_YEAR

        for (route in state.transportRoutes.values) {
            // Degrade
            val ordinal = route.condition.ordinal
            val degradeChance = route.degradeRate * yearFraction
            if (ctx.rng.nextBoolean(degradeChance) && ordinal < CONDITION_VALUES.lastIndex) {
                route.condition = CONDITION_VALUES[ordinal + 1]
            }
            // Flood damage worsens routes
            val hadFlood = ctx.newEvents.any { it.type == EventType.WEATHER_DAMAGE }
            if (hadFlood && ctx.rng.nextBoolean(0.3) && ordinal < CONDITION_VALUES.lastIndex) {
                route.condition = CONDITION_VALUES[ordinal + 1]
            }
            // Municipal repairs (if budget is healthy)
            val budget = state.municipalBudget
            if (route.condition.ordinal >= RouteCondition.POOR.ordinal &&
                budget.balance > 500.0 &&
                ctx.rng.nextBoolean(0.25)
            ) {
                budget.balance -= 200.0
                route.condition = RouteCondition.FAIR
                route.lastRepairedAt = ctx.now
            }
            // Footfall multiplier from route condition
            route.footfallMultiplier = when (route.condition) {
                RouteCondition.EXCELLENT -> 1.10
                RouteCondition.GOOD -> 1.00
                RouteCondition.FAIR -> 0.92
                RouteCondition.POOR -> 0.80
                RouteCondition.IMPASSABLE -> 0.55
            }
        }
    }

    private fun ensureRoutes(ctx: TickContext) {
        val state = ctx.state
        val homeTypes = setOf(BuildingType.HOUSE, BuildingType.COTTAGE, BuildingType.TERRACE, BuildingType.FLAT)
        val commercial = state.buildings.values.filter {
            it.type !in homeTypes && it.type != BuildingType.PARK && it.type != BuildingType.CEMETERY && it.type != BuildingType.VACANT
        }
        if (commercial.size < 2) return
        val alreadyLinked = state.transportRoutes.values
            .flatMap { listOf(it.fromBuildingId to it.toBuildingId) }.toSet()
        // Connect each commercial building to nearest neighbour if not already linked
        for (a in commercial) {
            val nearest = commercial.filter { it.id != a.id }
                .minByOrNull { a.origin.manhattan(it.origin) } ?: continue
            val key = if (a.id < nearest.id) a.id to nearest.id else nearest.id to a.id
            if (key in alreadyLinked) continue
            val id = state.nextRouteId++
            state.transportRoutes[id] = TransportRoute(
                id = id,
                fromBuildingId = key.first,
                toBuildingId = key.second
            )
        }
    }
}
