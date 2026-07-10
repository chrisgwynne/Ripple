package com.ripple.town.core.simulation

import com.ripple.town.core.model.Business
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.EmotionType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.PendingPriceEase
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Weather
import com.ripple.town.core.model.WorldEvent

/**
 * The connective tissue BETWEEN this session's individually-mature systems (economy, crime,
 * weather, emotion) — see the Simulation Reality Review finding this file closes: *"A flood
 * damaging a building doesn't yet ripple into that building's business losing customers for
 * weeks; a crime near a business doesn't measurably depress demand for a period."*
 *
 * Four bridges, each reusing an existing mechanism rather than inventing a parallel one:
 *
 * - **Bridge 1 — crime near a business dents its demand** ([onCrimeNearBusiness]). Fear changes
 *   routes: word of a crime at or very near a shop makes people quietly avoid it for a couple of
 *   weeks. No pathfinding/route-avoidance is built — the bounded `demand` dip already stands in
 *   for reduced footfall, exactly as `EconomySystem.hourlyFootfall` already converts `demand`
 *   into customers.
 * - **Bridge 2 — flood/weather damage disrupts a business housed in the damaged building**
 *   ([onBuildingWeatherDamaged]). Beyond the building's own `condition` hit (which
 *   `SeasonalEventSystem`/`NeedsSystem.updateWeather` already apply), the business itself takes a
 *   real, bounded operational hit: demand dips (disrupted supply, a shaken storefront) and prices
 *   creep up temporarily (higher costs from disrupted supply), both recovering over a bounded
 *   window.
 * - **Bridge 3 — prolonged poor weather wears residents down** ([updateDaily]'s consecutive-day
 *   tracking and mood-effect pass, plus [onSevereWeatherNearResidents] for the sharp end).
 *   A run of grim weather is a slow grind (a low-key ANXIETY nudge for anyone outdoors/exposed
 *   once it crosses a threshold); a storm/flood that actually damages something near a resident
 *   is a genuine fright (a real FEAR memory, not just a generic need hit).
 * - **Bridge 4 — sustained financial trouble strains a partnership**
 *   ([onSustainedFinancialTrouble]). Reuses `EconomySystem.DEBT_CRISIS_THRESHOLD` — the exact bar
 *   this codebase already treats as "serious financial trouble" — rather than inventing a new
 *   one; nudges the specific PARTNER/SPOUSE relationship's `resentment`/`dependency`, not just a
 *   generic stress bump.
 *
 * **Shared mechanism (Bridges 1 & 2).** Both are "a business's demand takes a bounded, temporary
 * hit that recovers over N days" — implemented as a single [applyTemporaryDemandPenalty] helper
 * that schedules a paired dip-then-recovery through the *existing*
 * [DelayedEffectType.DEMAND_SHIFT] mechanism (already read by `DelayedEffectSystem.apply` and
 * already used by `ConsequenceEngine`'s `BUSINESS_CLOSED` → rival-demand-shift rule) — a negative
 * `DEMAND_SHIFT` fires almost immediately (the dip), a matching positive `DEMAND_SHIFT` fires
 * `recoverAfterDays` later (the recovery). No new `DelayedEffectType`, no new persisted state on
 * `WorldState`: the two scheduled effects themselves are the entire bounded, self-cleaning
 * mechanism, exactly like every other `DelayedEffect` in this codebase.
 *
 * Every bridge here is silent/mechanical by design — no new [EventType] is added. `DEMAND_SHIFT`-
 * flavoured nudges were already judged not newsworthy on their own (see
 * `ConsequenceEngine`'s `BUSINESS_CLOSED` rule, which schedules the same effect type without
 * emitting a fresh event); the *triggering* event (`CRIME_COMMITTED`/`SHOPLIFTING`/etc,
 * `WEATHER_DAMAGE`) is already real town news on its own and already scored by
 * [ImportanceScorer]. Wired daily from `SimulationCoordinator`; the crime/flood hooks are called
 * directly from `CrimeSystem`/`SeasonalEventSystem`/`NeedsSystem` at the moment of the triggering
 * event, same shape as `EconomySystem.scheduleShock` being called from `closeBusiness`.
 */
object PressureBridgeSystem {

    // ============================================================
    // Shared mechanism — Bridges 1 & 2
    // ============================================================

    /**
     * Schedules a bounded, temporary demand penalty on [biz]: an almost-immediate dip of
     * [amount] (positive magnitude; applied as a negative [DelayedEffectType.DEMAND_SHIFT]) that
     * recovers back over [recoverAfterDays] via a matching positive shift. Both legs reuse
     * `DemandShift`'s existing `biz.demand += 8.0 * strength` formula in `DelayedEffectSystem
     * .apply` — `strength` here is deliberately chosen so `8.0 * strength ≈ amount`, keeping this
     * helper's `amount` parameter in the same "roughly how many demand points" units every other
     * call site of this bridge reasons in. [causeIds] links the scheduled dip back to whatever
     * triggered it (a `CRIME_COMMITTED`-flavoured event, a `WEATHER_DAMAGE` event) purely for
     * documentation at the call site — the underlying `DelayedEffect.sourceEventId` already
     * carries this; `causeIds` is accepted for symmetry with every other bridge helper in this
     * codebase but is not separately persisted (DelayedEffect has no `causeIds` field).
     */
    fun applyTemporaryDemandPenalty(
        ctx: TickContext,
        biz: Business,
        amount: Double,
        recoverAfterDays: Double,
        sourceEvent: WorldEvent,
        priceBump: Double = 0.0
    ) {
        val strength = (amount / 8.0).coerceIn(0.05, 1.0)
        val day = SimTime.MINUTES_PER_DAY
        // The dip — fires within the first day, essentially immediately.
        ctx.state.delayedEffects += com.ripple.town.core.model.DelayedEffect(
            id = ctx.state.nextEffectId++,
            sourceEventId = sourceEvent.id,
            targetBusinessId = biz.id,
            type = DelayedEffectType.DEMAND_SHIFT,
            strength = -strength,
            earliestAt = ctx.now,
            latestAt = ctx.now + (0.6 * day).toLong(),
            note = "Bridge dip"
        )
        // The recovery — fires once fear/disruption has had time to fade, matching this
        // session's established decay conventions (bounded window, never open-ended).
        ctx.state.delayedEffects += com.ripple.town.core.model.DelayedEffect(
            id = ctx.state.nextEffectId++,
            sourceEventId = sourceEvent.id,
            targetBusinessId = biz.id,
            type = DelayedEffectType.DEMAND_SHIFT,
            strength = strength,
            earliestAt = ctx.now + ((recoverAfterDays - 1.0).coerceAtLeast(0.5) * day).toLong(),
            latestAt = ctx.now + (recoverAfterDays * day).toLong(),
            note = "Bridge recovery"
        )
        // Supply disruption also shows up as a temporary price bump (higher costs passed on) —
        // applied immediately and eased back down the same way `priceLevel` is nudged elsewhere
        // in EconomySystem (a plain bounded field, no dedicated DelayedEffectType for it), so this
        // one leg is a direct, bounded field mutation rather than a scheduled effect. Bounded to
        // MAX_PRICE_LEVEL and always eased back by [easePriceBumps] on the same recovery schedule,
        // tracked on `WorldState.pendingPriceEasing` so it survives a checkpoint reload.
        if (priceBump > 0.0) {
            biz.priceLevel = (biz.priceLevel + priceBump).coerceAtMost(MAX_PRICE_LEVEL)
            val existing = ctx.state.pendingPriceEasing[biz.id]
            val totalOwed = (existing?.amount ?: 0.0) + priceBump
            ctx.state.pendingPriceEasing[biz.id] = PendingPriceEase(
                amount = totalOwed,
                easeAt = ctx.now + (recoverAfterDays * day).toLong()
            )
        }
    }

    /** Hands back every price bump whose recovery window has closed — see
     *  `WorldState.pendingPriceEasing`'s own doc comment. Called once daily from [updateDaily],
     *  bounded to however many are actually due (never an unbounded scan of business history). */
    private fun easePriceBumps(ctx: TickContext) {
        val state = ctx.state
        if (state.pendingPriceEasing.isEmpty()) return
        val due = state.pendingPriceEasing.filter { it.value.easeAt <= ctx.now }.keys.toList()
        for (bizId in due) {
            val ease = state.pendingPriceEasing.remove(bizId) ?: continue
            val biz = state.businesses[bizId] ?: continue
            biz.priceLevel = (biz.priceLevel - ease.amount).coerceAtLeast(MIN_PRICE_LEVEL)
        }
    }

    const val MAX_PRICE_LEVEL = 2.2
    const val MIN_PRICE_LEVEL = 0.5

    // ============================================================
    // Bridge 1 — crime near a business dents its demand
    // ============================================================

    /** How many building-manhattan tiles counts as "very near" a business for the fear-driven
     *  demand dip — deliberately the same radius `SeasonalEventSystem.FLOOD_PROXIMITY_TILES`
     *  uses for "near water", a proven proximity scale in this codebase. */
    const val CRIME_PROXIMITY_TILES = 3
    const val CRIME_DEMAND_DIP_MIN = 3.0
    const val CRIME_DEMAND_DIP_MAX = 8.0
    const val CRIME_RECOVERY_DAYS_MIN = 7.0
    const val CRIME_RECOVERY_DAYS_MAX = 14.0

    /**
     * Called at the moment a crime-flavoured event lands at or very near a business — from
     * `CrimeSystem`'s shoplifting/burglary/mugging/vehicle-theft/arson checks, right after each
     * emits its event. If [crime] already carries a `businessId` (shoplifting, fraud, arson —
     * "at" the business) that business is hit directly; otherwise (burglary, mugging, vehicle
     * theft — no business involved at all) the nearest open business to [crime]'s `buildingId`
     * within [CRIME_PROXIMITY_TILES] takes the dip instead — "fear changes routes" even when the
     * crime itself wasn't a shop theft. A flat `-3..-8` demand hit, recovering over 7-14 days
     * (bounded, matches this session's established decay windows — e.g.
     * `EmotionSystem`'s FEAR decay of 10/day means a spawned fear itself fades faster than the
     * demand dip, which is deliberate: the *emotion* fades quicker than the town's *habit* of
     * avoiding the block). Silent — no new event, this is a mechanical nudge exactly like
     * `ConsequenceEngine`'s existing `BUSINESS_CLOSED` → rival-demand-shift rule.
     */
    fun onCrimeNearBusiness(ctx: TickContext, crime: WorldEvent) {
        val state = ctx.state
        val direct = crime.businessId?.let { state.businesses[it] }
        val target = direct ?: run {
            val buildingId = crime.buildingId ?: return
            val hitBuilding = state.building(buildingId) ?: return
            state.businesses.values
                .filter { it.open && it.id != crime.businessId }
                .filter { biz ->
                    val bizBuilding = state.building(biz.buildingId) ?: return@filter false
                    bizBuilding.centre().manhattan(hitBuilding.centre()) <= CRIME_PROXIMITY_TILES
                }
                .minByOrNull { biz -> state.building(biz.buildingId)!!.centre().manhattan(hitBuilding.centre()) }
        } ?: return
        if (!target.open) return

        val dip = ctx.rng.nextDouble(CRIME_DEMAND_DIP_MIN, CRIME_DEMAND_DIP_MAX)
        val recoverDays = ctx.rng.nextDouble(CRIME_RECOVERY_DAYS_MIN, CRIME_RECOVERY_DAYS_MAX)
        applyTemporaryDemandPenalty(ctx, target, dip, recoverDays, crime)
    }

    // ============================================================
    // Bridge 2 — flood/weather damage disrupts a housed business
    // ============================================================

    const val FLOOD_DEMAND_DIP_MIN = 6.0
    const val FLOOD_DEMAND_DIP_MAX = 14.0
    const val FLOOD_RECOVERY_DAYS_MIN = 10.0
    const val FLOOD_RECOVERY_DAYS_MAX = 21.0
    const val FLOOD_PRICE_BUMP = 0.12

    /**
     * Called whenever a `WEATHER_DAMAGE` event lands on a building that houses an open business
     * — from `SeasonalEventSystem.maybeFlood` (the river-flood mechanic) and
     * `NeedsSystem.updateWeather`'s storm-damage roll, both of which already hit
     * `building.condition` directly; this adds the operational half the building-condition hit
     * alone doesn't cover. A real, bounded demand dip (customers can't easily get to a
     * storm-battered shopfront, stock may be spoiled) plus a smaller, temporary price bump
     * (disrupted supply costs more) — both eased back via the same
     * [applyTemporaryDemandPenalty]/[easePriceBumps] mechanism Bridge 1 uses, just a harsher
     * dip and a longer recovery window than a single crime incident (structural damage takes
     * longer to shrug off than a scare).
     */
    fun onBuildingWeatherDamaged(ctx: TickContext, damageEvent: WorldEvent) {
        val buildingId = damageEvent.buildingId ?: return
        val biz = ctx.state.businesses.values.firstOrNull { it.open && it.buildingId == buildingId } ?: return
        val dip = ctx.rng.nextDouble(FLOOD_DEMAND_DIP_MIN, FLOOD_DEMAND_DIP_MAX)
        val recoverDays = ctx.rng.nextDouble(FLOOD_RECOVERY_DAYS_MIN, FLOOD_RECOVERY_DAYS_MAX)
        applyTemporaryDemandPenalty(ctx, biz, dip, recoverDays, damageEvent, priceBump = FLOOD_PRICE_BUMP)
    }

    // ============================================================
    // Bridge 3 — weather -> psychology
    // ============================================================

    /** Weather kinds counted as "poor" for the consecutive-day streak below. */
    private val POOR_WEATHER = setOf(Weather.RAIN, Weather.STORM, Weather.SNOW, Weather.FOG)

    /** Consecutive poor-weather days needed before the low-key weariness nudge kicks in. */
    const val POOR_WEATHER_THRESHOLD_DAYS = 4
    const val WEATHER_WEARINESS_INTENSITY = 22.0
    const val WEATHER_COMFORT_NUDGE = -3.0

    /**
     * Daily bookkeeping + the low-key mood consequence of a prolonged run of poor weather.
     * Nothing in the engine previously tracked a weather *streak* (each day's roll in
     * `NeedsSystem.updateWeather` is independent) — [com.ripple.town.core.model.WorldState
     * .consecutivePoorWeatherDays] is the minimal counter this bridge adds, updated once daily
     * here from whatever `state.weather` happens to be at the moment this runs (called from
     * `SimulationCoordinator`'s `if (newDay)` block, same as every other daily system).
     *
     * Once the streak crosses [POOR_WEATHER_THRESHOLD_DAYS], every detailed resident currently
     * outdoors/exposed (no `currentBuildingId` — genuinely between buildings, or travelling) gets
     * a small, bounded ANXIETY emotion via `EmotionSystem.spawnEmotion` (deliberately reusing
     * ANXIETY rather than inventing a new "weary" flavour — see `EmotionType`'s own doc on why
     * this session keeps the emotion set to a practical, non-duplicating twelve) plus a small
     * `comfort` nudge — never both a huge emotion AND an unbounded need hit, matching every other
     * bridge's "small, bounded" discipline. The streak resets to 0 the moment weather turns fair
     * again, so this never accumulates across unrelated bad-weather spells.
     */
    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        state.consecutivePoorWeatherDays = if (state.weather in POOR_WEATHER) {
            state.consecutivePoorWeatherDays + 1
        } else {
            0
        }
        if (state.consecutivePoorWeatherDays >= POOR_WEATHER_THRESHOLD_DAYS) {
            for (r in state.detailedResidents()) {
                if (!r.inTown) continue
                if (r.currentBuildingId != null) continue // sheltered — not exposed
                EmotionSystem.spawnEmotion(ctx, r, EmotionType.ANXIETY, WEATHER_WEARINESS_INTENSITY)
                r.needs.comfort = (r.needs.comfort + WEATHER_COMFORT_NUDGE).coerceIn(0.0, 100.0)
            }
        }
        easePriceBumps(ctx)
    }

    const val SEVERE_FEAR_INTENSITY = 55.0

    /**
     * Called from the *severe* weather-damage hooks (`SeasonalEventSystem.maybeFlood`,
     * `NeedsSystem.updateWeather`'s storm-damage roll) once a building has actually been
     * damaged — anyone currently inside it lives through something genuinely frightening, not
     * just a generic safety/comfort need hit (which `SeasonalEventSystem`/`NeedsSystem` already
     * apply directly). Gives each occupant a real `MemoryType.FEAR` memory plus a spawned FEAR
     * emotion, the same `ctx.addMemory` + `EmotionSystem.spawnEmotion(FEAR)` pairing
     * `CrimeSystem.updateBurglary`/`updateMugging` already use for their victims — no new
     * mechanism, just extending the same "severe fright leaves a real memory" pattern to weather.
     */
    fun onSevereWeatherNearResidents(ctx: TickContext, damageEvent: WorldEvent, description: String) {
        val buildingId = damageEvent.buildingId ?: return
        val occupants = ctx.state.residentsIn(buildingId)
        for (r in occupants) {
            if (r.detailLevel != com.ripple.town.core.model.DetailLevel.DETAILED) continue
            ctx.addMemory(r, MemoryType.FEAR, description, 65.0, damageEvent.id)
            EmotionSystem.spawnEmotion(ctx, r, EmotionType.FEAR, SEVERE_FEAR_INTENSITY, damageEvent.id)
        }
    }

    // ============================================================
    // Bridge 4 — sustained financial trouble strains a partnership
    // ============================================================

    const val PARTNER_RESENTMENT_NUDGE = 4.0
    const val PARTNER_DEPENDENCY_NUDGE = 3.0
    const val PARTNER_AFFECTION_NUDGE = -2.0
    /** Never nudge the same couple more than once in this many days — a slow, sustained-pressure
     *  drift, not a daily pile-on (mirrors `BusinessRivalrySystem`'s once-daily-per-pair drift,
     *  but slower — financial strain is a bigger, less frequent nudge). */
    const val PARTNER_NUDGE_COOLDOWN_DAYS = 5L

    /**
     * Called daily (from [updateDaily]'s caller, `SimulationCoordinator`) for every resident
     * already flagged `debt_crisis` (see `EconomySystem.dailySettlement` — `r.debt >
     * EconomySystem.DEBT_CRISIS_THRESHOLD`, the exact existing bar for "serious financial
     * trouble" reused here rather than a new one) who is *still* over that threshold and has a
     * live partner/spouse. The brief's "unemployment increases household tension" — currently
     * only a generic stress bump exists on the affected resident themselves
     * (`EconomySystem.dailySettlement`'s `worker.needs.stress += 6.0` on missed payroll,
     * `ConsequenceEngine`'s `JOB_LOST` rule), never a targeted relationship-dimension effect. This
     * adds exactly that: a small, bounded `resentment`/`dependency` shift on the couple's
     * `Relationship`, gated to at most once per [PARTNER_NUDGE_COOLDOWN_DAYS] days per couple via
     * `state.lastIncidentAt`-style tracking (reusing the resident's own crisis-onset timestamp
     * bucket rather than adding a new cooldown map) so this never compounds daily while a crisis
     * drags on for weeks.
     */
    fun onSustainedFinancialTrouble(ctx: TickContext) {
        val state = ctx.state
        for (r in state.detailedResidents()) {
            if (!r.inTown) continue
            if (r.debt <= EconomySystem.DEBT_CRISIS_THRESHOLD) continue
            if (!r.awareness.contains("debt_crisis")) continue
            val partnerId = r.partnerId ?: continue
            val partner = state.resident(partnerId) ?: continue
            if (!partner.inTown) continue
            val rel = state.relationshipOrCreate(r.id, partnerId)
            if (rel.kind != com.ripple.town.core.model.RelationshipKind.PARTNER &&
                rel.kind != com.ripple.town.core.model.RelationshipKind.SPOUSE
            ) continue
            val last = state.lastFinancialStrainNudgeAt[r.id] ?: 0L
            if (ctx.now - last < PARTNER_NUDGE_COOLDOWN_DAYS * SimTime.MINUTES_PER_DAY) continue
            state.lastFinancialStrainNudgeAt[r.id] = ctx.now

            rel.resentment = (rel.resentment + PARTNER_RESENTMENT_NUDGE).coerceIn(0.0, 100.0)
            rel.dependency = (rel.dependency + PARTNER_DEPENDENCY_NUDGE).coerceIn(0.0, 100.0)
            rel.affection = (rel.affection + PARTNER_AFFECTION_NUDGE).coerceIn(0.0, 100.0)
            rel.clampAll()
        }
    }
}
