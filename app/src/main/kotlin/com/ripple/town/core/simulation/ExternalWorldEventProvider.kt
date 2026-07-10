package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.ExternalPressure
import com.ripple.town.core.model.ExternalPressureKind
import com.ripple.town.core.model.PressureHistoryEntry
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldState

/**
 * Phase 4's opening item: a small, hand-curated, deliberately abstract feed of
 * "outside world" pressures — the seam the backlog describes as "fuel prices
 * rise -> delivery costs rise -> the chain the prototype already models".
 *
 * **Naming note.** The backlog names this item `ExternalWorldEventProvider` /
 * `WorldPressureMapper`, but those exact identifiers were already claimed by
 * pre-existing placeholder interfaces in `core/simulation/providers/
 * FutureProviders.kt` (`suspend fun pendingPressures(...)`, wired for DI in
 * `AppModule.kt`) — a seam intentionally reserved for a *later*, real
 * external/async feed (network- or LLM-backed). This task is explicitly the
 * opposite of that: a small, curated, fully deterministic, `ctx.rng`-driven
 * system that lives *inside* the tick pipeline like every other daily
 * system, not an injected async dependency. To avoid a confusing
 * same-name-different-package collision (and any risk of an accidental wrong
 * import down the line), the concrete engine-internal types here are named
 * [CuratedWorldPressureFeed] and [WorldPressureMechanicMapper] — same
 * responsibilities the backlog item describes, distinct identifiers from the
 * unrelated future-architecture placeholder. When that placeholder is ever
 * actually implemented (a real feed), it would plug in as an *upstream*
 * input to something like this system, not replace it.
 *
 * This is explicitly **not** the `NarrativeTextProvider`/`DialogueProvider` LLM
 * layer or shareable town chronicles — both remain separate, unattempted Phase
 * 4 items (see `docs/backlog.md`). The **national layer** ("lightweight
 * country context — taxes, trends — as pressures") is a small, additive
 * extension built directly on top of this same feed, added 2026-07-10: a new
 * `TAX_RATE_RISES`/`TAX_RATE_EASES` curated pressure pair (mapped to
 * `WorldState.nationalTaxRate`, a bounded 0.9x–1.1x multiplier applied through
 * `EconomySystem`'s existing daily settlement — see [WorldPressureMechanicMapper]
 * below), plus a short rolling history of past pressures
 * (`WorldState.pressureHistory`) so the town has a sense of "how things have
 * been going nationally," not just the single live pressure slot. This is
 * still not a real-world news feed of any kind: every pressure kind is
 * entirely fictional/abstract framing, matching the rest of Ripple's fictional
 * town — no real place names, no real companies, no real politics or current
 * events, ever.
 *
 * Deliberately scoped down to **at most one active pressure at a time**,
 * town-wide — no overlapping/stacking pressures, no per-business or
 * per-resident targeting. That single active pressure is genuinely
 * newsworthy but framed as background/abstract town-wide news (a new,
 * narrowly-scoped `EventType.NATIONAL_PRESSURE`), never a personal event.
 *
 * Strict separation of concerns, as the backlog brief asks for: this object
 * only ever decides *whether a pressure starts or resolves* and picks *which
 * abstract kind*. It never touches any mechanical simulation field directly —
 * that strict, traceable translation is [WorldPressureMechanicMapper]'s job alone.
 */
object CuratedWorldPressureFeed {

    /** Bounded daily chance a brand new pressure begins, when none is currently active. */
    const val START_CHANCE_PER_DAY = 0.02

    /** How long a pressure lasts once it starts, in in-game days. A slow, background rhythm —
     *  not a daily flicker — matching how a real cost pressure would actually play out. */
    const val PRESSURE_MIN_DAYS = 14
    const val PRESSURE_MAX_DAYS = 45

    /**
     * The curated pressure list. Each entry is entirely fictional/abstract — no real names,
     * companies or current events — and comes in matched rise/ease pairs so a resolving
     * pressure always has an obvious opposite-flavoured successor possibility later. Includes
     * the `TAX_RATE_RISES`/`TAX_RATE_EASES` national-layer pair added 2026-07-10.
     */
    private val CURATED_KINDS = ExternalPressureKind.entries.toList()

    /** Cap on [WorldState.pressureHistory] — enough for a short "how things have been going
     *  nationally" sense (last few pressures) without growing an unbounded list forever. */
    const val PRESSURE_HISTORY_LIMIT = 5

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        WorldPressureMechanicMapper.nudgeNationalTaxRate(state)
        val active = state.externalPressure
        if (active != null) {
            if (ctx.now >= active.endsAt) resolve(ctx, active)
            return // at most one active pressure at a time — deliberate scoped-down MVP
        }
        if (!ctx.rng.nextBoolean(START_CHANCE_PER_DAY)) return
        start(ctx)
    }

    private fun start(ctx: TickContext) {
        val kind = ctx.rng.pick(CURATED_KINDS)
        val days = ctx.rng.nextInt(PRESSURE_MIN_DAYS, PRESSURE_MAX_DAYS + 1)
        val endsAt = ctx.now + days.toLong() * SimTime.MINUTES_PER_DAY
        val event = ctx.emit(
            EventType.NATIONAL_PRESSURE,
            startDescription(kind),
            severity = 0.3,
            visibility = EventVisibility.PUBLIC,
            payload = mapOf("kind" to kind.name, "phase" to "started")
        )
        ctx.state.externalPressure = ExternalPressure(
            kind = kind,
            startedAt = ctx.now,
            endsAt = endsAt,
            startEventId = event.id
        )
        recordHistory(ctx.state, PressureHistoryEntry(kind = kind, startedAt = ctx.now, endsAt = null))
    }

    private fun resolve(ctx: TickContext, pressure: ExternalPressure) {
        ctx.emit(
            EventType.NATIONAL_PRESSURE,
            resolveDescription(pressure.kind),
            severity = 0.2,
            visibility = EventVisibility.PUBLIC,
            payload = mapOf("kind" to pressure.kind.name, "phase" to "resolved"),
            causeIds = listOf(pressure.startEventId)
        )
        ctx.state.externalPressure = null
        // Close out the matching in-progress history entry (started, not yet ended) rather than
        // appending a second row for the same pressure — the trend record is one entry per
        // pressure, start-to-end, not a start row and a separate end row.
        val idx = ctx.state.pressureHistory.indexOfLast { it.startedAt == pressure.startedAt && it.endsAt == null }
        if (idx >= 0) {
            ctx.state.pressureHistory[idx] = ctx.state.pressureHistory[idx].copy(endsAt = ctx.now)
        }
    }

    /** Appends to the rolling trend history, trimming from the front once past
     *  [PRESSURE_HISTORY_LIMIT] — oldest entries drop off first. */
    private fun recordHistory(state: WorldState, entry: PressureHistoryEntry) {
        state.pressureHistory.add(entry)
        while (state.pressureHistory.size > PRESSURE_HISTORY_LIMIT) {
            state.pressureHistory.removeAt(0)
        }
    }

    private fun startDescription(kind: ExternalPressureKind): String = when (kind) {
        ExternalPressureKind.FUEL_PRICES_RISE ->
            "Word from beyond the town is that fuel prices are rising — deliveries are costing more."
        ExternalPressureKind.FUEL_PRICES_EASE ->
            "Fuel prices are said to be easing beyond the town — deliveries should get a little cheaper."
        ExternalPressureKind.POOR_HARVEST ->
            "News of a poor harvest nationally has reached the town."
        ExternalPressureKind.STRONG_HARVEST ->
            "Word of a strong national harvest has reached the town."
        ExternalPressureKind.TRADE_ROUTES_DISRUPTED ->
            "Trade routes further afield are said to be disrupted."
        ExternalPressureKind.TRADE_FLOURISHING ->
            "Trade beyond the town is said to be flourishing."
        ExternalPressureKind.CONFIDENCE_DIPS ->
            "There is talk of economic confidence dipping nationally."
        ExternalPressureKind.CONFIDENCE_RISES ->
            "There is talk of economic confidence rising nationally."
        ExternalPressureKind.TAX_RATE_RISES ->
            "Word has reached the town that national taxes are rising."
        ExternalPressureKind.TAX_RATE_EASES ->
            "There is talk of national taxes easing back."
    }

    private fun resolveDescription(kind: ExternalPressureKind): String = when (kind) {
        ExternalPressureKind.FUEL_PRICES_RISE -> "Fuel prices are said to have settled again."
        ExternalPressureKind.FUEL_PRICES_EASE -> "The talk of cheaper fuel has passed."
        ExternalPressureKind.POOR_HARVEST -> "The news of the poor harvest has moved on."
        ExternalPressureKind.STRONG_HARVEST -> "The talk of the strong harvest has moved on."
        ExternalPressureKind.TRADE_ROUTES_DISRUPTED -> "Word is the trade routes have reopened."
        ExternalPressureKind.TRADE_FLOURISHING -> "The talk of flourishing trade has moved on."
        ExternalPressureKind.CONFIDENCE_DIPS -> "Confidence is said to have steadied again."
        ExternalPressureKind.CONFIDENCE_RISES -> "The talk of rising confidence has moved on."
        ExternalPressureKind.TAX_RATE_RISES -> "The talk of rising taxes has settled down."
        ExternalPressureKind.TAX_RATE_EASES -> "The talk of easing taxes has passed."
    }
}

/**
 * Strict, narrow translation of the single active [ExternalPressure] (if any) into a small,
 * bounded, already-existing mechanical effect — never a vague global multiplier sprinkled
 * across many systems. Per the backlog brief, exactly **one** clean mechanical hook is picked
 * so the effect stays traceable: a fuel-price pressure nudges [EconomySystem]'s per-business
 * daily overhead expense (the literal "delivery costs rise" chain the backlog names), and
 * nothing else. `PriceDriftSystem`'s struggling-bias and `BusinessRivalrySystem`'s standing
 * calc are deliberately left untouched by this pass — composing a second hook onto either
 * would blur which system actually caused what.
 *
 * The other six curated pressure kinds (harvest, trade routes, confidence — both directions)
 * are recorded and reported on by [CuratedWorldPressureFeed] but deliberately carry **no**
 * mechanical effect yet — flavour-only background news, honestly scoped as such rather than
 * bolted onto an unrelated system just to give every kind "something to do".
 */
object WorldPressureMechanicMapper {

    /** How much a `FUEL_PRICES_RISE` pressure raises per-business daily overheads — a genuine
     *  but gentle nudge, not a shock (roughly a sixth on top of the base overhead figure). */
    const val FUEL_RISE_OVERHEAD_MULTIPLIER = 1.15

    /** How much a `FUEL_PRICES_EASE` pressure lowers them — the opposite-flavoured relief. */
    const val FUEL_EASE_OVERHEAD_MULTIPLIER = 0.92

    /** The multiplier `EconomySystem.dailySettlement` composes into its overhead-expense
     *  calculation. `1.0` (no effect) whenever there's no active pressure, or the active
     *  pressure isn't one of the two fuel-price kinds this mapper is scoped to. */
    fun overheadMultiplier(state: WorldState): Double = when (state.externalPressure?.kind) {
        ExternalPressureKind.FUEL_PRICES_RISE -> FUEL_RISE_OVERHEAD_MULTIPLIER
        ExternalPressureKind.FUEL_PRICES_EASE -> FUEL_EASE_OVERHEAD_MULTIPLIER
        else -> 1.0
    }

    // --- National layer: tax rate (added 2026-07-10) ---------------------------------------

    /** Genuinely bounded, per the brief — a national tax rate never swings more than ±10%
     *  from neutral, however long a rise/ease pressure runs. */
    const val NATIONAL_TAX_RATE_MIN = 0.9
    const val NATIONAL_TAX_RATE_MAX = 1.1

    /** How far `nationalTaxRate` moves per day while a `TAX_RATE_RISES`/`TAX_RATE_EASES`
     *  pressure is active — a slow multi-week drift towards the bound, not an instant jump,
     *  matching the "slow, background rhythm" every other pressure in this feed already uses. */
    const val TAX_RATE_STEP_PER_DAY = 0.004

    /** Daily nudge of `WorldState.nationalTaxRate`, called once per day from
     *  `CuratedWorldPressureFeed.updateDaily` regardless of which (if any) pressure kind is
     *  currently active — a tax pressure walks the rate away from 1.0 towards its bound; any
     *  other pressure (or no pressure at all) lets it drift back towards 1.0, so the rate
     *  doesn't stay stuck at an old high/low forever after the pressure that caused it resolves. */
    fun nudgeNationalTaxRate(state: WorldState) {
        val target = when (state.externalPressure?.kind) {
            ExternalPressureKind.TAX_RATE_RISES -> NATIONAL_TAX_RATE_MAX
            ExternalPressureKind.TAX_RATE_EASES -> NATIONAL_TAX_RATE_MIN
            else -> 1.0
        }
        val rate = state.nationalTaxRate
        state.nationalTaxRate = when {
            rate < target -> minOf(target, rate + TAX_RATE_STEP_PER_DAY)
            rate > target -> maxOf(target, rate - TAX_RATE_STEP_PER_DAY)
            else -> rate
        }.coerceIn(NATIONAL_TAX_RATE_MIN, NATIONAL_TAX_RATE_MAX)
    }

    /** The multiplier `EconomySystem.dailySettlement` composes into each detailed adult
     *  resident's daily living-cost expense — the "taxes" half of the national-layer addition,
     *  landing on the one place in the codebase that already models a resident's unavoidable
     *  daily outgoings (`EconomySystem.LIVING_COST_PER_DAY`), the same "one clean traceable
     *  hook" principle the fuel-price/overhead mapping above already established. Simply
     *  `WorldState.nationalTaxRate` itself — already bounded 0.9x-1.1x by [nudgeNationalTaxRate] —
     *  read here rather than duplicated. */
    fun livingCostMultiplier(state: WorldState): Double = state.nationalTaxRate
}
