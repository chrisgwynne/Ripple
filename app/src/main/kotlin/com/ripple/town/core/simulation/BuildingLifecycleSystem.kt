package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.SimTime
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
    const val BASE_REPAIR_CHANCE = 0.15

    /**
     * A well-regarded family gets tradespeople to their door faster (and a
     * poorly-regarded one waits longer) — a small, bounded nudge on top of the base
     * daily repair roll, sourced from `FamilyReputationSystem` rather than a new
     * mechanic of its own. Capped well short of ever making repairs certain either way.
     */
    const val FAMILY_REPUTATION_CHANCE_SWING = 0.05

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
            // A sitting mayor's term makes the town a little quicker to fund repairs town-wide — see
            // ElectionSystem.repairChanceBonus. Zero when the office is vacant, so this stays a no-op
            // until the first election actually lands; composes additively with the family-standing
            // nudge above rather than replacing it.
            val repairChance = BASE_REPAIR_CHANCE + (payer.reputationResident?.let {
                FamilyReputationSystem.standingModifier(state, it, FAMILY_REPUTATION_CHANCE_SWING)
            } ?: 0.0) + ElectionSystem.repairChanceBonus(state)
            if (!ctx.rng.nextBoolean(repairChance.coerceIn(0.02, 0.35))) continue
            payer.spend(cost)
            val restored = ctx.rng.nextDouble(25.0, 45.0)
            building.condition = (building.condition + restored).coerceAtMost(100.0)
            building.visibleChanges.removeAll { it.endsWith("Storm damage") }
            building.visibleChanges += "${SimTime.formatDate(ctx.now)} — Freshly repaired"
            if (building.visibleChanges.size > 6) building.visibleChanges.removeAt(0)
            ctx.emit(
                EventType.BUILDING_REPAIRED,
                "${building.name} has had some much-needed repairs done.",
                buildingId = building.id, severity = 0.15
            )
            budget--
        }
    }

    /** [reputationResident] is null for a business payer — family standing only applies to homes. */
    private class Payer(val funds: Double, val reputationResident: com.ripple.town.core.model.Resident?, val spend: (Double) -> Unit)

    /** Whoever has a stake in the building and could plausibly pay for repairs; null if nobody can. */
    private fun payerFor(ctx: TickContext, building: Building): Payer? {
        val state = ctx.state
        val biz = state.businesses.values.firstOrNull { it.buildingId == building.id && it.open }
        if (biz != null) {
            return Payer(biz.balance, reputationResident = null) { cost -> biz.balance -= cost }
        }
        if (building.type.isHome) {
            val resident = state.livingResidents()
                .filter { it.homeBuildingId == building.id && it.wealth > 200.0 }
                .maxByOrNull { it.wealth } ?: return null
            return Payer(resident.wealth, reputationResident = resident) { cost -> resident.wealth -= cost }
        }
        return null
    }
}
