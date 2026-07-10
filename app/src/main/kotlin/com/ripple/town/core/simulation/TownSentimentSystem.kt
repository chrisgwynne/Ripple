package com.ripple.town.core.simulation

import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.TownSentiment
import com.ripple.town.core.model.WorldEvent

/**
 * Daily update for [TownSentiment] — the town's own slow-moving emotional weather, distinct
 * from any one resident's [BeliefSystem] position or the live per-tick `TownStatsUi.averageWellbeing`
 * (see [TownSentiment]'s doc comment on `WorldState.kt` for the full "why these six, why not
 * more" writeup). See `docs/simulation-rules.md` "Town sentiment" for the design writeup this
 * mirrors.
 *
 * Every dimension moves only through this object — no other system writes to
 * `WorldState.townSentiment` directly, same "one place mutates it" convention `BeliefSystem`
 * uses for `Resident.beliefs`. Three real, already-simulated sources are read each day:
 *
 * 1. **Repeated unsolved crime** ([evaluateCrimeRun]) — recent `CRIME_REPORTED` events in the
 *    town-wide recent-events window, counted by `payload["accurate"]` — a run of *inaccurate*
 *    reports (the wrong person named, the real culprit still out there) is genuinely scarier
 *    than an equal number of accurate ones, so it's weighted higher.
 * 2. **Crisis response** ([evaluateCrisisResponse]) — a `WEATHER_DAMAGE` flood followed by a
 *    `BUILDING_REPAIRED` for the same building within the recent window reads as "the town
 *    responded and fixed it" — civic pride and trust both rise.
 * 3. **Resolved petitions** ([evaluatePetitions]) — `PETITION_RESOLVED` events move trust/
 *    cohesion up on success, down on failure — same payload shape `BeliefSystem.
 *    evaluateCommunityResponse` already reads.
 * 4. **Aggregate belief readout** ([applyBeliefReadout]) — a cheap mean, across every detailed
 *    in-town resident, of [BeliefTopic.TRUST_IN_GOVERNMENT] and [BeliefTopic.ECONOMIC_OPTIMISM]
 *    (rescaled from `-1..1` to `0..100`), nudging [TownSentiment.trust]/[TownSentiment.optimism]
 *    a small step toward that mean each day — town sentiment is legitimately *partly* an
 *    aggregate readout of individual beliefs (per the brief), not a fully separate number with
 *    no connection to what residents actually think, but it's a slow nudge alongside the event-
 *    driven deltas above, never a hard overwrite.
 *
 * Every delta here is small and bounded ([EVENT_DELTA_MIN]/[EVENT_DELTA_MAX], same shape as
 * `BeliefSystem.DRIFT_MIN`/`DRIFT_MAX`), and [decayTowardBaseline] pulls every dimension a small
 * step back toward the neutral `50.0` baseline every day regardless of what else fired — so a
 * quiet stretch with nothing happening gently forgets old spikes, matching the brief's "should
 * decay slowly".
 */
object TownSentimentSystem {

    // ---- Bounds ----------------------------------------------------------------------------
    const val MIN = 0.0
    const val MAX = 100.0
    const val BASELINE = 50.0

    /** How far a single day's decay pulls each dimension back toward [BASELINE]. */
    const val DECAY_RATE = 0.015

    /** Per-trigger delta band, mirrors `BeliefSystem.DRIFT_MIN`/`DRIFT_MAX` but on the 0..100
     *  scale (roughly 4x, since this scale is 100 points wide vs beliefs' 2). */
    const val EVENT_DELTA_MIN = 0.6
    const val EVENT_DELTA_MAX = 2.2

    /** How far a single day's belief-mean readout can nudge trust/optimism — deliberately much
     *  smaller than a direct event trigger, since this is a slow background pull, not a real
     *  discrete happening. */
    const val BELIEF_READOUT_PULL = 0.05

    /** How many recent `CRIME_REPORTED` events (within the window) count as "a run" worth
     *  reacting to — one or two isolated incidents shouldn't move the whole town's mood. */
    const val CRIME_RUN_THRESHOLD = 3

    /** A sentiment dimension crossing one of these bands (calm/anxious-style zones) is
     *  significant enough to report — matches [TownSentiment]'s 0..100 scale, thirds. */
    const val LOW_BAND = 35.0
    const val HIGH_BAND = 65.0

    fun updateDaily(ctx: TickContext) {
        val sentiment = ctx.state.townSentiment
        val before = sentiment.copy()

        evaluateCrimeRun(ctx, sentiment)
        evaluateCrisisResponse(ctx, sentiment)
        evaluatePetitions(ctx, sentiment)
        applyBeliefReadout(ctx, sentiment)
        decayTowardBaseline(sentiment)
        clampAll(sentiment)

        reportSignificantShifts(ctx, before, sentiment)
    }

    // ------------------------------------------------------------ trigger 1: unsolved crime

    /** Recent `CRIME_REPORTED` events in the bounded recent-events window (same window
     *  `CrimeSystem`/`BeliefSystem` already read, never a fresh full-log scan). Inaccurate
     *  reports (the wrong person named — see `CrimeSystem.investigate`) count double: an unsolved
     *  crime with a wrongly-accused resident is the town's own experience of "nobody's actually
     *  safe, and the process doesn't work" — the specific fear this dimension exists to track. */
    private fun evaluateCrimeRun(ctx: TickContext, sentiment: TownSentiment) {
        val recentReports = ctx.state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .filter { it.type == EventType.CRIME_REPORTED && withinWindow(ctx, it) }
        if (recentReports.isEmpty()) return

        val accurateCount = recentReports.count { it.payload["accurate"] == "true" }
        val inaccurateCount = recentReports.size - accurateCount
        val weightedRun = accurateCount * 1.0 + inaccurateCount * 2.0
        if (weightedRun < CRIME_RUN_THRESHOLD) return

        val intensity = (weightedRun / (CRIME_RUN_THRESHOLD * 3.0)).coerceIn(0.0, 1.0)
        val delta = EVENT_DELTA_MIN + (EVENT_DELTA_MAX - EVENT_DELTA_MIN) * intensity
        sentiment.fear = (sentiment.fear + delta).coerceIn(MIN, MAX)
        sentiment.safety = (sentiment.safety - delta).coerceIn(MIN, MAX)
    }

    // ------------------------------------------------------------ trigger 2: crisis response

    /** A `WEATHER_DAMAGE` flood followed by a `BUILDING_REPAIRED` for the same building within
     *  the recent window — same causal pairing `BeliefSystem.evaluateCommunityResponse` already
     *  reads per-resident, read here town-wide instead: the town noticed and pulled together. */
    private fun evaluateCrisisResponse(ctx: TickContext, sentiment: TownSentiment) {
        val recent = ctx.state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .filter { withinWindow(ctx, it) }
        val floods = recent.filter { it.type == EventType.WEATHER_DAMAGE }
        if (floods.isEmpty()) return

        var responded = 0
        for (flood in floods) {
            val repaired = recent.any {
                it.type == EventType.BUILDING_REPAIRED && it.buildingId == flood.buildingId && it.time > flood.time
            }
            if (repaired) responded++
        }
        if (responded == 0) return

        val delta = EVENT_DELTA_MIN + (EVENT_DELTA_MAX - EVENT_DELTA_MIN) *
            (responded.toDouble() / floods.size.toDouble().coerceAtLeast(1.0))
        sentiment.civicPride = (sentiment.civicPride + delta).coerceIn(MIN, MAX)
        sentiment.trust = (sentiment.trust + delta * 0.6).coerceIn(MIN, MAX)
    }

    // ------------------------------------------------------------ trigger 3: petitions

    /** `PETITION_RESOLVED` events in the recent window: success lifts trust/cohesion, failure
     *  dampens them a smaller amount — same asymmetric shape `PetitionSystem`'s own per-resident
     *  reputation/stress consequences already use (a bigger reward for success than the penalty
     *  for failure). */
    private fun evaluatePetitions(ctx: TickContext, sentiment: TownSentiment) {
        val recentPetitions = ctx.state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .filter { it.type == EventType.PETITION_RESOLVED && withinWindow(ctx, it) }
        if (recentPetitions.isEmpty()) return

        for (event in recentPetitions) {
            val reasonKey = "petition:${event.id}"
            if (ctx.state.townSentimentAppliedReasons.contains(reasonKey)) continue
            markApplied(ctx, reasonKey)
            if (event.payload["outcome"] == "succeeded") {
                sentiment.trust = (sentiment.trust + EVENT_DELTA_MAX * 0.7).coerceIn(MIN, MAX)
                sentiment.cohesion = (sentiment.cohesion + EVENT_DELTA_MAX * 0.7).coerceIn(MIN, MAX)
            } else {
                sentiment.trust = (sentiment.trust - EVENT_DELTA_MIN).coerceIn(MIN, MAX)
                sentiment.cohesion = (sentiment.cohesion - EVENT_DELTA_MIN).coerceIn(MIN, MAX)
            }
        }
    }

    // ------------------------------------------------------------ trigger 4: aggregate belief readout

    /** A cheap mean, across every detailed in-town resident with a formed view, of
     *  [BeliefTopic.TRUST_IN_GOVERNMENT] and [BeliefTopic.ECONOMIC_OPTIMISM] — rescaled from the
     *  belief scale (`-1..1`) to this scale (`0..100`) and nudged toward by [BELIEF_READOUT_PULL]
     *  per day, never a hard overwrite. Skips entirely (no nudge either direction) once nobody in
     *  town has formed a view yet, rather than pulling toward a meaningless mean of zero. */
    private fun applyBeliefReadout(ctx: TickContext, sentiment: TownSentiment) {
        val residents = ctx.state.detailedResidents().filter { it.inTown }
        if (residents.isEmpty()) return

        val trustViews = residents.filter { it.beliefs.containsKey(BeliefTopic.TRUST_IN_GOVERNMENT) }
        if (trustViews.isNotEmpty()) {
            val meanTrust = trustViews.sumOf { BeliefSystem.positionOn(it, BeliefTopic.TRUST_IN_GOVERNMENT) } / trustViews.size
            val target = (meanTrust + 1.0) / 2.0 * MAX // rescale -1..1 -> 0..100
            sentiment.trust = (sentiment.trust + (target - sentiment.trust) * BELIEF_READOUT_PULL).coerceIn(MIN, MAX)
        }

        val optimismViews = residents.filter { it.beliefs.containsKey(BeliefTopic.ECONOMIC_OPTIMISM) }
        if (optimismViews.isNotEmpty()) {
            val meanOptimism = optimismViews.sumOf { BeliefSystem.positionOn(it, BeliefTopic.ECONOMIC_OPTIMISM) } / optimismViews.size
            val target = (meanOptimism + 1.0) / 2.0 * MAX
            sentiment.optimism = (sentiment.optimism + (target - sentiment.optimism) * BELIEF_READOUT_PULL).coerceIn(MIN, MAX)
        }
    }

    // ------------------------------------------------------------ decay

    /** Every dimension creeps a small step back toward [BASELINE] each day — the "should decay
     *  slowly" half of the brief. Applied after every event-driven trigger above, so a day with
     *  a real event still nets a real move (the trigger deltas are larger than one day's decay). */
    private fun decayTowardBaseline(sentiment: TownSentiment) {
        sentiment.trust += (BASELINE - sentiment.trust) * DECAY_RATE
        sentiment.fear += (BASELINE - sentiment.fear) * DECAY_RATE
        sentiment.optimism += (BASELINE - sentiment.optimism) * DECAY_RATE
        sentiment.civicPride += (BASELINE - sentiment.civicPride) * DECAY_RATE
        sentiment.safety += (BASELINE - sentiment.safety) * DECAY_RATE
        sentiment.cohesion += (BASELINE - sentiment.cohesion) * DECAY_RATE
    }

    private fun clampAll(sentiment: TownSentiment) {
        sentiment.trust = sentiment.trust.coerceIn(MIN, MAX)
        sentiment.fear = sentiment.fear.coerceIn(MIN, MAX)
        sentiment.optimism = sentiment.optimism.coerceIn(MIN, MAX)
        sentiment.civicPride = sentiment.civicPride.coerceIn(MIN, MAX)
        sentiment.safety = sentiment.safety.coerceIn(MIN, MAX)
        sentiment.cohesion = sentiment.cohesion.coerceIn(MIN, MAX)
    }

    // ------------------------------------------------------------ event window helper

    private fun withinWindow(ctx: TickContext, event: WorldEvent): Boolean =
        ctx.now - event.time <= RECENT_TRIGGER_WINDOW_DAYS * SimTime.MINUTES_PER_DAY

    /** How far back (in sim days) an event can still count toward a trigger here — bounds the
     *  cost of the `recentEventIds` scan and stops a very old flood/crime from re-counting
     *  forever. */
    const val RECENT_TRIGGER_WINDOW_DAYS = 10L

    /** `WorldState.townSentimentAppliedReasons`-style de-dup for triggers (like petitions) that
     *  would otherwise re-apply every day an event stays inside the recent window — reuses the
     *  same bounded-list-of-strings shape `Resident.awareness` uses for cooldowns, scoped to the
     *  world rather than a resident since sentiment itself is world-scoped. */
    private fun markApplied(ctx: TickContext, reasonKey: String) {
        val list = ctx.state.townSentimentAppliedReasons
        list += reasonKey
        while (list.size > MAX_APPLIED_REASONS) list.removeAt(0)
    }

    private const val MAX_APPLIED_REASONS = 40

    // ------------------------------------------------------------ significant-shift reporting

    /** Emits one low-severity `TOWN_MILESTONE` event only when a dimension crosses from one
     *  named band ("anxious"/"calm"/"content", etc — [LOW_BAND]/[HIGH_BAND] thirds) into another
     *  — never on every small daily nudge, which would be noise. At most one emitted per call
     *  (the single most notable crossing, if several happened the same day) to keep this genuinely
     *  rare town news rather than a chatty status ticker. */
    private fun reportSignificantShifts(ctx: TickContext, before: TownSentiment, after: TownSentiment) {
        val crossings = listOfNotNull(
            crossing("fear", before.fear, after.fear, risingIsBad = true),
            crossing("safety", before.safety, after.safety, risingIsBad = false),
            crossing("trust", before.trust, after.trust, risingIsBad = false),
            crossing("civicPride", before.civicPride, after.civicPride, risingIsBad = false),
            crossing("cohesion", before.cohesion, after.cohesion, risingIsBad = false),
            crossing("optimism", before.optimism, after.optimism, risingIsBad = false)
        )
        val chosen = crossings.maxByOrNull { it.second } ?: return
        ctx.emit(
            EventType.TOWN_MILESTONE,
            chosen.first,
            severity = 0.2,
            visibility = EventVisibility.PUBLIC,
            payload = mapOf("kind" to "town_sentiment_shift")
        )
    }

    /** Returns a (description, magnitude) pair when [name] crossed [LOW_BAND] or [HIGH_BAND]
     *  between [beforeValue] and [afterValue], else null. [risingIsBad] flips the phrasing for
     *  dimensions where higher is the concerning direction (fear). */
    private fun crossing(name: String, beforeValue: Double, afterValue: Double, risingIsBad: Boolean): Pair<String, Double>? {
        val crossedDown = beforeValue >= LOW_BAND && afterValue < LOW_BAND
        val crossedUp = beforeValue <= HIGH_BAND && afterValue > HIGH_BAND
        if (!crossedDown && !crossedUp) return null
        val magnitude = kotlin.math.abs(afterValue - beforeValue)
        val phrase = describeCrossing(name, crossedUp, risingIsBad)
        return phrase to magnitude
    }

    private fun describeCrossing(name: String, crossedUp: Boolean, risingIsBad: Boolean): String {
        val goodDirection = if (risingIsBad) !crossedUp else crossedUp
        return when (name) {
            "fear" -> if (crossedUp) "An uneasy mood has settled over the town after a run of trouble."
                else "The town feels calmer than it has in a while."
            "safety" -> if (crossedUp) "People are starting to feel safer around town again."
                else "There's a growing sense that the town isn't as safe as it was."
            "trust" -> if (crossedUp) "Faith in the town's institutions is on the rise."
                else "Trust in the town's institutions has taken a knock."
            "civicPride" -> if (crossedUp) "There's a real sense of pride in the town right now."
                else "The town's usual sense of pride has dimmed a little."
            "cohesion" -> if (crossedUp) "The town feels like it's pulling together lately."
                else "The town feels a little more fractured than it used to."
            "optimism" -> if (crossedUp) "There's a growing sense that things are looking up."
                else "The town's outlook has grown noticeably gloomier."
            else -> if (goodDirection) "The town's mood has lifted." else "The town's mood has dipped."
        }
    }

    // ------------------------------------------------------------ public read helpers

    /**
     * A short, human-readable one-line description of the town's overall mood, derived from
     * [TownSentiment]'s dimensions — for a future UI surface (not wired into any screen this
     * pass, same "modelled and available, ready for a later read" convention `WorldState
     * .pressureHistory` used before any newspaper/town-sheet work read it). Picks off the single
     * most extreme dimension from baseline so the phrase reflects whatever's most notable right
     * now, rather than trying to summarise all six at once.
     */
    fun summaryPhrase(sentiment: TownSentiment): String {
        val deviations = listOf(
            Triple("fear", sentiment.fear, sentiment.fear - BASELINE),
            Triple("safety", sentiment.safety, sentiment.safety - BASELINE),
            Triple("trust", sentiment.trust, sentiment.trust - BASELINE),
            Triple("civicPride", sentiment.civicPride, sentiment.civicPride - BASELINE),
            Triple("cohesion", sentiment.cohesion, sentiment.cohesion - BASELINE),
            Triple("optimism", sentiment.optimism, sentiment.optimism - BASELINE)
        )
        val mostExtreme = deviations.maxByOrNull { kotlin.math.abs(it.third) } ?: return "The town feels much as it usually does."
        val (name, _, deviation) = mostExtreme
        if (kotlin.math.abs(deviation) < 6.0) return "The town feels much as it usually does."
        val positive = deviation > 0.0
        return when (name) {
            "fear" -> if (positive) "There's real unease in the air across town." else "The town feels unusually calm and at ease."
            "safety" -> if (positive) "People generally feel safe around town." else "The town feels uneasy about its own safety."
            "trust" -> if (positive) "Folk here generally trust the people running things." else "There's a real lack of faith in the town's institutions."
            "civicPride" -> if (positive) "Spirits are generally high — the town's proud of itself." else "The town's pride in itself has taken a dent."
            "cohesion" -> if (positive) "The town feels like it pulls together." else "The town feels somewhat divided lately."
            "optimism" -> if (positive) "There's a hopeful feeling about what's ahead." else "The mood about the future is fairly gloomy."
            else -> "The town feels much as it usually does."
        }
    }

    /** Overload reading straight off a `WorldState`, the more convenient call site for future UI
     *  work. */
    fun summaryPhrase(state: com.ripple.town.core.model.WorldState): String = summaryPhrase(state.townSentiment)
}

/** Small extension so `TownSentiment` can be shallow-copied for the before/after significant-
 *  shift comparison in [TownSentimentSystem.updateDaily] without hand-listing every field twice. */
private fun TownSentiment.copy(): TownSentiment = TownSentiment(trust, fear, optimism, civicPride, safety, cohesion)
