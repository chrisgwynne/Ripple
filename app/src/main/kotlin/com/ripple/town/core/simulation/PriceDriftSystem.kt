package com.ripple.town.core.simulation

import com.ripple.town.core.model.Business
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.SimTime

/**
 * Economy v2 slice: "prices that move" — slow, town-wide price drift,
 * independent of the same-type price/demand competition already owned by
 * `BusinessRivalrySystem`. That system shifts `demand` between competing
 * pairs; this system nudges `priceLevel` itself, town-wide, one business at a
 * time, and never touches `demand` — the two mechanics operate on separate
 * axes and never double-count each other. The property market and further
 * business succession remain separate, still-open backlog items — see
 * `docs/backlog.md`.
 *
 * No macro "inflation index" exists yet anywhere in `WorldState` to drive
 * this deterministically from aggregate conditions, so — same as the rest of
 * this codebase's precedent (e.g. `EconomySystem`'s footfall roll,
 * `SeasonalEventSystem`'s flood chance) — this is a small bounded random walk
 * through `ctx.rng`, never `Math.random()`. Every open, non-public-service
 * business gets a tiny, independent daily nudge to `priceLevel`; most days
 * most businesses don't move at all (`DRIFT_CHANCE`), and when they do the
 * step is tiny (`DRIFT_STEP`), clamped to `PRICE_LEVEL_MIN`/`PRICE_LEVEL_MAX` — the same
 * multiplier-on-base-prices field `EconomySystem.hourlyFootfall` already
 * reads (`spendEach = baseSpend(type) * priceLevel`) and
 * `BusinessRivalrySystem.standing` already factors into competition. A
 * business under sustained financial pressure (in trouble, or a low balance)
 * is nudged to drift down more than up — a struggling trader cuts prices to
 * chase custom rather than raising them — while a healthy, prosperous
 * business is nudged to drift up more than down, mirroring ordinary retail
 * behaviour without inventing a new tracked variable.
 */
object PriceDriftSystem {

    /** Daily chance any single open business's price drifts at all — most days, most don't. */
    const val DRIFT_CHANCE = 0.12

    /** Size of a single drift step on `priceLevel` (a ~2% nudge). */
    const val DRIFT_STEP = 0.02

    /** Bounds on `priceLevel`, matching the range `EconomySystem`/`BusinessRivalrySystem`
     *  already treat as sane (a business can never become free or extortionate). */
    const val PRICE_LEVEL_MIN = 0.7
    const val PRICE_LEVEL_MAX = 1.4

    /** Chance a struggling business's drift goes down rather than up (discounting to chase trade). */
    const val STRUGGLING_DOWN_BIAS = 0.75

    /** Chance a healthy, prosperous business's drift goes up rather than down. */
    const val PROSPEROUS_UP_BIAS = 0.65

    /** A swing at least this large across a single day is newsworthy. */
    const val NEWSWORTHY_SWING = 0.10

    /** Days window within which two rival businesses both cutting prices triggers a PRICE_WAR. */
    const val PRICE_WAR_WINDOW_DAYS = 7L

    /** Bounded, like every other daily system: never more than this many businesses processed per day. */
    const val MAX_BUSINESSES_PER_DAY = 60

    /** Balance above which a business counts as "prosperous" for drift-direction purposes,
     *  matching `EconomySystem.EXPANSION_BALANCE`'s notion of healthy trade. */
    const val PROSPEROUS_BALANCE = EconomySystem.EXPANSION_BALANCE

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        val candidates = ctx.rng.shuffled(
            state.businesses.values.filter { it.open && it.type !in EconomySystem.PUBLIC_SERVICES }
        ).take(MAX_BUSINESSES_PER_DAY)

        for (biz in candidates) {
            if (!ctx.rng.nextBoolean(DRIFT_CHANCE)) continue
            driftOne(ctx, biz)
        }
    }

    private fun driftOne(ctx: TickContext, biz: Business) {
        val struggling = biz.daysInTrouble > 0 || biz.balance < 0
        val prosperous = !struggling && biz.balance > PROSPEROUS_BALANCE
        val upChance = when {
            struggling -> 1.0 - STRUGGLING_DOWN_BIAS
            prosperous -> PROSPEROUS_UP_BIAS
            else -> 0.5
        }
        val goesUp = ctx.rng.nextBoolean(upChance)
        val before = biz.priceLevel
        val after = (if (goesUp) before + DRIFT_STEP else before - DRIFT_STEP)
            .coerceIn(PRICE_LEVEL_MIN, PRICE_LEVEL_MAX)
        if (after == before) return
        biz.priceLevel = after

        // Phase 5 C2: record the day of a price cut and check for a price war with a rival.
        if (after < before) {
            val today = SimTime.dayIndex(ctx.now)
            biz.lastPriceCutDay = today
            maybeEmitPriceWar(ctx, biz, today)
        }

        // Only a swing that has accumulated to a genuinely noticeable size is worth a
        // headline — most individual drift steps are too small to be newsworthy on
        // their own, matching every other daily system's "small nudges, rare events" shape.
        val direction = if (after > before) "risen" else "fallen"
        if (kotlin.math.abs(after - 1.0) >= NEWSWORTHY_SWING && kotlin.math.abs(before - 1.0) < NEWSWORTHY_SWING) {
            ctx.emit(
                EventType.PRICES_SHIFTED,
                "Prices at ${biz.name} have $direction noticeably.",
                sourceResidentId = biz.ownerId, businessId = biz.id,
                buildingId = biz.buildingId, severity = 0.25, visibility = EventVisibility.PUBLIC
            )
        }
    }

    /**
     * C2: check whether [biz]'s owner has a [RelationshipKind.RIVAL] relationship with another
     * business owner, and if that rival's business also cut its price within the last
     * [PRICE_WAR_WINDOW_DAYS]. If so, emit a single [EventType.PRICE_WAR] event (de-duped:
     * the event is only emitted once — when biz's cut is the *later* of the two).
     */
    private fun maybeEmitPriceWar(ctx: TickContext, biz: Business, today: Long) {
        val state = ctx.state
        val ownerId = biz.ownerId ?: return
        val owner = state.resident(ownerId) ?: return
        if (!owner.inTown) return

        // Find RIVAL relationships for this owner.
        val rivalRels = state.relationshipsOf(ownerId)
            .filter { it.kind == RelationshipKind.RIVAL }
        for (rel in rivalRels) {
            val rivalId = if (rel.aId == ownerId) rel.bId else rel.aId
            val rival = state.resident(rivalId) ?: continue
            if (!rival.inTown) continue
            // Find the rival's business.
            val rivalBiz = state.businesses.values.firstOrNull { it.open && it.ownerId == rivalId } ?: continue
            // Check if the rival also cut prices within the 7-day window.
            if (rivalBiz.lastPriceCutDay <= 0L) continue
            val daysSinceRivalCut = today - rivalBiz.lastPriceCutDay
            if (daysSinceRivalCut < 0 || daysSinceRivalCut > PRICE_WAR_WINDOW_DAYS) continue
            // Only emit when biz's cut is the later one (rival cut first or same day with lower
            // business id) to avoid a duplicate from the other side.
            if (biz.lastPriceCutDay < rivalBiz.lastPriceCutDay) continue
            if (biz.lastPriceCutDay == rivalBiz.lastPriceCutDay && biz.id > rivalBiz.id) continue

            ctx.emit(
                EventType.PRICE_WAR,
                "A price war has broken out between ${biz.name} and ${rivalBiz.name} — " +
                    "both have cut their prices within the same week.",
                sourceResidentId = ownerId,
                targetResidentIds = listOf(rivalId),
                businessId = biz.id,
                severity = 0.35,
                visibility = EventVisibility.PUBLIC
            )
        }
    }
}
