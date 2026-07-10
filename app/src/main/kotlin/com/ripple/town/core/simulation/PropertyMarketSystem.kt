package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.MemoryType

/**
 * Economy v2's last open slice: the property market — residents actually buying homes,
 * as distinct from the pre-existing, unchanged free-relocation path
 * ([GoalSystem]'s `MOVE_HOME` goal, which simply walks a household into any vacant
 * home with no money changing hands and no ownership recorded). This system is the
 * "you can only live here because you bought it" path that sits alongside it, and is
 * the last genuinely separate item under the Economy v2 backlog bullet — see
 * `docs/backlog.md`.
 *
 * Deliberately a scoped-down MVP, not a real-estate sim:
 * - **Cash purchase only.** A household buys a home outright against its own
 *   pooled adult [Household.memberIds] wealth (see [buyerFor]) — no mortgages,
 *   no loans, no instalments.
 * - **No negotiation.** The asking price is exactly [com.ripple.town.core.model.Building.value];
 *   there is no haggling, and a household either can afford it or it waits.
 * - **No competing bidders.** The first eligible household found each day (stable,
 *   deterministic order) takes the home; there is no auction or multi-household contest
 *   for the same property.
 * - **Rental status is not modelled as a separate concept.** A home with
 *   `Building.ownerId == null` is read as "not yet owned" (nobody has ever bought it —
 *   it was seeded, inherited into, or moved into for free via `MOVE_HOME`); buying it
 *   simply records `Building.ownerId` going forward. There's no separate landlord/tenant
 *   relationship, no eviction, and buying a home a household already lives in doesn't
 *   change anything about their day-to-day life other than who legally owns it (chiefly:
 *   it makes them ineligible to buy again, and a small `HOME_PURCHASED` news moment).
 */
object PropertyMarketSystem {

    /** Never process more than this many households per day (bounded, like every other system). */
    const val MAX_HOUSEHOLDS_PER_DAY = 40

    /** A household must have at least this much left over after the purchase, so buying a home
     *  never strips a family down to nothing — matches the spirit of `EconomySystem`'s existing
     *  debt/rent affordability checks rather than a bare "can just barely afford it" rule. */
    const val MIN_RESERVE_AFTER_PURCHASE = 200.0

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state

        val candidates = state.households.values
            .filter { it.homeBuildingId != null && it.memberIds.isNotEmpty() }
            .sortedBy { it.id }
            .take(MAX_HOUSEHOLDS_PER_DAY)

        for (hh in candidates) {
            val homeId = hh.homeBuildingId ?: continue
            val home = state.building(homeId) ?: continue
            if (home.ownerId != null) continue // already bought by this or an earlier household
            if (home.abandoned) continue

            val buyer = buyerFor(ctx, hh, home.value) ?: continue
            purchase(ctx, hh, home.id, buyer.id)
        }
    }

    /** Pools every adult member's wealth — this is a household decision, not one person's
     *  savings account, matching how rent is already "split across household adults" in
     *  [EconomySystem]. Returns the household's highest-wealth adult as the nominal buyer
     *  (whose name appears on the purchase) if the pooled total clears the price plus reserve. */
    private fun buyerFor(ctx: TickContext, hh: Household, price: Double): com.ripple.town.core.model.Resident? {
        val adults = hh.memberIds.mapNotNull { ctx.state.resident(it) }
            .filter { it.alive && it.inTown && it.lifeStageAt(ctx.now) == com.ripple.town.core.model.LifeStage.ADULT }
        if (adults.isEmpty()) return null
        val pooled = adults.sumOf { it.wealth }
        if (pooled < price + MIN_RESERVE_AFTER_PURCHASE) return null
        return adults.maxByOrNull { it.wealth }
    }

    private fun purchase(ctx: TickContext, hh: Household, homeId: Long, buyerId: Long) {
        val state = ctx.state
        val home = state.building(homeId) ?: return
        val buyer = state.resident(buyerId) ?: return

        // Draw the price from the buyer first, then any other adult members in wealth order,
        // so the household's collective savings actually leave the household, not just one
        // person's balance going arbitrarily negative.
        var remaining = home.value
        val payers = (listOf(buyer) + hh.memberIds.mapNotNull { state.resident(it) }
            .filter { it.id != buyer.id && it.alive && it.inTown && it.lifeStageAt(ctx.now) == com.ripple.town.core.model.LifeStage.ADULT })
            .sortedByDescending { it.wealth }
        for (payer in payers) {
            if (remaining <= 0.0) break
            val take = minOf(payer.wealth, remaining)
            payer.wealth -= take
            remaining -= take
        }

        home.ownerId = buyer.id

        val e = ctx.emit(
            EventType.HOME_PURCHASED,
            "${buyer.fullName} has bought ${home.name} for the ${hh.name}.",
            sourceResidentId = buyer.id,
            targetResidentIds = hh.memberIds.filter { it != buyer.id },
            buildingId = home.id,
            severity = 0.3, visibility = EventVisibility.PUBLIC
        )
        ctx.addMemory(buyer, MemoryType.ACHIEVEMENT, "The day I bought ${home.name}.", 75.0, e.id)
        ConsequenceEngine.onEvent(ctx, e)
    }
}
