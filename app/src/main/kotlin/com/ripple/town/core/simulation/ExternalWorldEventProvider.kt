package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.ExternalPressure
import com.ripple.town.core.model.ExternalPressureKind
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
 * layer, the richer "national layer" context, or shareable town chronicles —
 * all three remain separate, unattempted Phase 4 items (see `docs/backlog.md`).
 * This is also not a real-world news feed of any kind: every pressure kind is
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
     * pressure always has an obvious opposite-flavoured successor possibility later.
     */
    private val CURATED_KINDS = ExternalPressureKind.entries.toList()

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
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
}
