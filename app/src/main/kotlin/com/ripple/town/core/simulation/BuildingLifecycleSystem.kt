package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.isHome

/**
 * Buildings don't just sit there: storm damage (see [NeedsSystem.updateWeather])
 * eventually gets fixed by whoever has a stake in the place, if they can
 * afford it — a business from its takings, a resident from personal wealth.
 * Bounded and probabilistic: not every damaged building gets seen to at once,
 * and nothing is fixed instantly.
 */
object BuildingLifecycleSystem {

    const val REPAIR_THRESHOLD = 55.0
    const val COST_PER_CONDITION_POINT = 9.0
    const val MAX_REPAIRS_PER_DAY = 3

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        var budget = MAX_REPAIRS_PER_DAY
        val damaged = state.buildings.values
            .filter { !it.abandoned && it.condition < REPAIR_THRESHOLD }
            .sortedBy { it.id }
        for (building in damaged) {
            if (budget <= 0) break
            val payer = payerFor(ctx, building) ?: continue
            val cost = (100.0 - building.condition) * COST_PER_CONDITION_POINT
            if (payer.funds < cost) continue
            // They've had the means to fix it for a while; whether they get round to it today is another matter.
            if (!ctx.rng.nextBoolean(0.15)) continue
            payer.spend(cost)
            val restored = ctx.rng.nextDouble(25.0, 45.0)
            building.condition = (building.condition + restored).coerceAtMost(100.0)
            building.visibleChanges.removeAll { it == "Storm damage" }
            building.visibleChanges += "Freshly repaired"
            if (building.visibleChanges.size > 6) building.visibleChanges.removeAt(0)
            ctx.emit(
                EventType.BUILDING_REPAIRED,
                "${building.name} has had some much-needed repairs done.",
                buildingId = building.id, severity = 0.15
            )
            budget--
        }
    }

    private class Payer(val funds: Double, val spend: (Double) -> Unit)

    /** Whoever has a stake in the building and could plausibly pay for repairs; null if nobody can. */
    private fun payerFor(ctx: TickContext, building: Building): Payer? {
        val state = ctx.state
        val biz = state.businesses.values.firstOrNull { it.buildingId == building.id && it.open }
        if (biz != null) {
            return Payer(biz.balance) { cost -> biz.balance -= cost }
        }
        if (building.type.isHome) {
            val resident = state.livingResidents()
                .filter { it.homeBuildingId == building.id && it.wealth > 200.0 }
                .maxByOrNull { it.wealth } ?: return null
            return Payer(resident.wealth) { cost -> resident.wealth -= cost }
        }
        return null
    }
}
