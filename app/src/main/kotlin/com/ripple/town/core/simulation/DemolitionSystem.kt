package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.SimTime

/**
 * Advances condemned buildings through the demolition pipeline:
 *   CONDEMNED → (scheduled, [Building.constructionCompletesAt] set) → DEMOLISHED
 *
 * Each monthly pass:
 *   1. For every CONDEMNED building that has not yet been scheduled, roll to decide
 *      whether an owner (private) or the council demolishes it.
 *   2. Complete any scheduled demolitions whose timer has expired.
 *
 * A demolition clears all occupants, closes any attached business, removes the
 * building from the business index, marks it DEMOLISHED, and emits
 * [EventType.BUILDING_DEMOLISHED].
 *
 * Phase 6D — autonomous town evolution.
 */
class DemolitionSystem {

    companion object {
        private const val PRIVATE_DEMOLITION_BASE_PROB = 0.008   // ~1% per month for condemned
        private const val COUNCIL_DEMOLITION_BASE_PROB = 0.005   // council is slower
        private const val DEMOLITION_DURATION_DAYS_PER_TILE = 2  // days per building tile (width*height)
        private const val MAX_DEMOLITIONS_PER_MONTH = 2

        /** Run once per in-game month (every 30 days). */
        const val UPDATE_INTERVAL_DAYS = 30L
    }

    fun updateMonthly(ctx: TickContext) {
        var demolitionsThisMonth = 0

        for (building in ctx.state.buildings.values.toList()) {
            if (demolitionsThisMonth >= MAX_DEMOLITIONS_PER_MONTH) break
            if (building.buildingState != BuildingState.CONDEMNED) continue

            // Skip if demolition is already scheduled (constructionCompletesAt is repurposed here).
            if (building.constructionCompletesAt != null) continue

            if (shouldDecideDemolish(ctx, building)) {
                scheduleDemolition(ctx, building)
                demolitionsThisMonth++
            }
        }

        // Complete any in-progress demolitions whose timer has expired.
        completeDemolitions(ctx)
    }

    private fun shouldDecideDemolish(ctx: TickContext, building: com.ripple.town.core.model.Building): Boolean {
        val owner = building.ownerId?.let { ctx.state.residents[it] }
        return if (owner != null && owner.alive) {
            // Owner decides: low wealth increases demolition probability.
            val wealthModifier = if (owner.wealth < 500) 1.5 else 1.0
            ctx.rng.nextBoolean(PRIVATE_DEMOLITION_BASE_PROB * wealthModifier)
        } else {
            // Council decides: more likely when municipal budget is healthy.
            val budgetModifier = if (ctx.state.municipalBudget.balance > 20_000) 1.8 else 0.6
            ctx.rng.nextBoolean(COUNCIL_DEMOLITION_BASE_PROB * budgetModifier)
        }
    }

    private fun scheduleDemolition(ctx: TickContext, building: com.ripple.town.core.model.Building) {
        val durationDays = maxOf(3, building.width * building.height * DEMOLITION_DURATION_DAYS_PER_TILE)
        building.constructionCompletesAt = ctx.now + durationDays * SimTime.MINUTES_PER_DAY
        building.visibleChanges.add("Demolition scheduled")
        if (building.visibleChanges.size > com.ripple.town.core.model.Building.MAX_VISIBLE_CHANGES) {
            building.visibleChanges.removeAt(0)
        }
    }

    private fun completeDemolitions(ctx: TickContext) {
        for (building in ctx.state.buildings.values.toList()) {
            if (building.buildingState != BuildingState.CONDEMNED) continue
            val completesAt = building.constructionCompletesAt ?: continue
            if (ctx.now < completesAt) continue

            executeDemolition(ctx, building)
        }
    }

    private fun executeDemolition(ctx: TickContext, building: com.ripple.town.core.model.Building) {
        // 1. Evict any remaining occupants and clear their household home pointer.
        val occupants = ctx.state.residents.values.filter {
            it.homeBuildingId == building.id && it.alive
        }
        occupants.forEach { resident ->
            resident.homeBuildingId = null
            val hh = resident.householdId?.let { ctx.state.households[it] }
            if (hh != null && hh.homeBuildingId == building.id) {
                hh.homeBuildingId = null
            }
        }

        // 2. Close any business attached to this building.
        ctx.state.businessAt(building.id)?.let { biz ->
            if (biz.open) {
                biz.open = false
                biz.closedAt = ctx.now
            }
        }

        // 3. Mark the building as demolished.
        building.buildingState = BuildingState.DEMOLISHED
        building.abandoned = true
        building.constructionCompletesAt = null
        building.ownerId = null

        // 4. Remove from the building → business index.
        ctx.state.buildingToBusinessId.remove(building.id)

        // 5. Emit event so the newspaper, history screen, and cause-chain viewers all see it.
        ctx.emit(
            type = EventType.BUILDING_DEMOLISHED,
            description = "The ${building.name} has been demolished.",
            buildingId = building.id,
            severity = 0.5,
            visibility = EventVisibility.PUBLIC
        )
    }
}
