package com.ripple.town.core.simulation

import com.ripple.town.core.model.Belief
import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime

/**
 * Bounded daily pass forming and drifting each detailed resident's [Belief]s — same overall shape
 * as `PersonalityDevelopmentSystem`: small deltas, cooldown-gated via the [Resident.awareness]
 * flag-list trick, every applied change causally linked back to a real triggering event/memory
 * and reported via a low-severity `PRIVATE` [EventType.BELIEF_SHIFTED]. See
 * `docs/simulation-rules.md` "Beliefs" for the full topic list and trigger writeup.
 *
 * Two formation shapes:
 *   1. **Inheritance from a parent** — a teen with no beliefs yet, living with an in-town parent
 *      who already holds some, seeds 1-2 of them as a noisy, lower-confidence copy (see
 *      [maybeInheritFromParent]). Deliberately simpler than a real life-stage-transition hook (no
 *      such hook exists yet anywhere in the codebase — `LifecycleSystem`/`PersonalityDevelopmentSystem`
 *      both just check `lifeStageAt(now)` fresh each call) — "TEEN, no beliefs yet, in-town parent
 *      with beliefs" is itself a fine proxy for "just old enough to start forming a worldview".
 *   2. **Drift from real lived experience** — four wired triggers, each reading real, already-
 *      simulated state (never a flat daily dice roll): crime victimisation, unemployment, personal
 *      achievement, and a resolved petition/flood recovery. See each `evaluate*` function below.
 *
 * Every drift is small ([DRIFT_MIN]..[DRIFT_MAX] on the `-1..1` position scale, scaled down
 * further by [Belief.confidence] — a firmly-held belief moves slower than a shaky one — see
 * [applyDrift]), and [Belief.position]/[Belief.confidence] both naturally clamp to their
 * documented ranges every time they're touched, so nothing here can runaway.
 */
object BeliefSystem {

    // ---- Formation from parents -----------------------------------------------------------

    /** How much a parent's position is trusted vs. randomised away when a teen inherits it. */
    const val INHERITANCE_NOISE_SPREAD = 0.35

    /** A freshly-inherited belief starts far less confident than the parent's own — the teen
     *  hasn't earned this view through their own lived experience yet. */
    const val INHERITED_CONFIDENCE_FACTOR = 0.4

    /** Never seed more than this many beliefs from a parent in one go. */
    const val MAX_INHERITED_BELIEFS = 2

    // ---- Drift from lived experience -------------------------------------------------------

    /** Small per-trigger delta band on the `-1..1` position scale, before confidence scaling. */
    const val DRIFT_MIN = 0.02
    const val DRIFT_MAX = 0.05

    /** How much a confident belief resists movement — at confidence 1.0, drift is scaled by this
     *  floor rather than all the way to zero, so even a firmly-held belief can still (slowly)
     *  move given enough real experience. */
    const val CONFIDENCE_RESISTANCE_FLOOR = 0.35

    /** Confidence itself creeps up a little with every real reinforcing trigger — a belief formed
     *  from repeated lived experience becomes more settled over time. */
    const val CONFIDENCE_GAIN_PER_TRIGGER = 0.03

    /** Same-topic-and-reason re-triggering is cooldown-gated, mirroring
     *  `PersonalityDevelopmentSystem.SAME_TRIGGER_COOLDOWN_DAYS`. */
    const val SAME_TRIGGER_COOLDOWN_DAYS = 14L

    /** How many consecutive days of unemployment before it's treated as "sustained" enough to
     *  shift economic/institutional beliefs, when no JOB_LOST event is available in the window. */
    const val SUSTAINED_UNEMPLOYMENT_DAYS = 14L

    fun updateDaily(ctx: TickContext) {
        for (r in ctx.state.detailedResidents().sortedBy { it.id }) {
            if (!r.inTown) continue
            maybeInheritFromParent(ctx, r)
            evaluateCrimeVictimisation(ctx, r)
            evaluateUnemployment(ctx, r)
            evaluatePersonalSuccess(ctx, r)
            evaluateCommunityResponse(ctx, r)
        }
    }

    // ------------------------------------------------------------ formation from parents

    /** A teen with no beliefs of their own yet, whose mother or father is in town and already
     *  holds at least one belief, seeds 1-2 of them here — noisy copies via
     *  [SimRandom.nextGaussianLike] (same helper `LifecycleSystem.inheritPersonality` uses for
     *  personality inheritance), at [INHERITED_CONFIDENCE_FACTOR] the parent's own confidence. */
    private fun maybeInheritFromParent(ctx: TickContext, r: Resident) {
        if (r.lifeStageAt(ctx.now) != LifeStage.TEEN) return
        if (r.beliefs.isNotEmpty()) return
        val state = ctx.state
        val parent = listOfNotNull(r.motherId?.let { state.resident(it) }, r.fatherId?.let { state.resident(it) })
            .firstOrNull { it.inTown && it.beliefs.isNotEmpty() } ?: return

        val topics = parent.beliefs.keys.sortedBy { it.ordinal }.take(MAX_INHERITED_BELIEFS)
        for (topic in topics) {
            val parentBelief = parent.beliefs[topic] ?: continue
            val noisyPosition = ctx.rng.nextGaussianLike(
                parentBelief.position, INHERITANCE_NOISE_SPREAD, -1.0, 1.0
            )
            r.beliefs[topic] = Belief(
                topic = topic,
                position = noisyPosition,
                confidence = (parentBelief.confidence * INHERITED_CONFIDENCE_FACTOR).coerceIn(0.0, 1.0),
                emotionalAttachment = parentBelief.emotionalAttachment * INHERITED_CONFIDENCE_FACTOR,
                sourceEventIds = mutableListOf(),
                lastUpdatedAt = ctx.now
            )
        }
        if (topics.isEmpty()) return
        ctx.emit(
            EventType.BELIEF_SHIFTED,
            "${r.fullName} is starting to see things the way their family does.",
            sourceResidentId = r.id,
            severity = 0.1,
            visibility = EventVisibility.PRIVATE,
            payload = mapOf(
                "reason" to "inherited_from_parent",
                "topics" to topics.joinToString(",") { it.name }
            )
        )
    }

    // ------------------------------------------------------------ trigger 1: crime victimisation

    /** A resident who was the victim of a `CRIME_REPORTED` outcome drifts [BeliefTopic.TRUST_IN_POLICE]:
     *  down if the constable's report was inaccurate (payload `accurate == "false"` and this
     *  resident isn't the one accused — i.e. they were the crime's actual victim watching the
     *  wrong person get blamed), up (small) if it was accurate — the system worked for them. Reads
     *  `WorldState.recentEventIds` the same bounded-window way `CrimeSystem.mostRecentDesperationCause`
     *  does, never re-scanning the full event log. */
    private fun evaluateCrimeVictimisation(ctx: TickContext, r: Resident) {
        val report = ctx.state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .firstOrNull { event ->
                event.type == EventType.CRIME_REPORTED &&
                    event.causeIds.isNotEmpty() &&
                    isVictimOf(ctx, r, event.causeIds.first())
            } ?: return

        val reasonKey = "crime_victim:${report.id}"
        if (onCooldown(ctx, r, reasonKey)) return

        val accurate = report.payload["accurate"] == "true"
        val delta = if (accurate) DRIFT_MIN else -DRIFT_MAX
        val reason = if (accurate) {
            "The constable actually got it right — small comfort, but comfort all the same."
        } else {
            "Being wronged and watching the wrong name get blamed hasn't done much for their faith in the constable."
        }
        applyDrift(ctx, r, BeliefTopic.TRUST_IN_POLICE, delta, reasonKey, reason, listOf(report.id))
    }

    /** True if [r] appears as a target (never the accused, source) of the underlying crime event
     *  the report describes — i.e. they were the one it happened *to*. */
    private fun isVictimOf(ctx: TickContext, r: Resident, crimeEventId: Long): Boolean {
        val crime = ctx.newEvents.firstOrNull { it.id == crimeEventId } ?: ctx.eventIndex.get(crimeEventId) ?: return false
        return r.id in crime.targetResidentIds
    }

    // ------------------------------------------------------------ trigger 2: unemployment

    /** A `JOB_LOST` event in the recent window, or sustained unemployment
     *  ([SUSTAINED_UNEMPLOYMENT_DAYS]+ with no recorded employment) drifts
     *  [BeliefTopic.ECONOMIC_OPTIMISM] down, and — a smaller, secondary knock — [BeliefTopic.TRUST_IN_GOVERNMENT]
     *  and [BeliefTopic.INSTITUTIONAL_TRUST] down too: struggling to find work reads, fairly or not,
     *  as the town/institutions not having done right by them. */
    private fun evaluateUnemployment(ctx: TickContext, r: Resident) {
        if (r.lifeStageAt(ctx.now) == LifeStage.CHILD) return
        val state = ctx.state
        val employed = state.employmentOf(r) != null
        if (employed) return

        val jobLostEvent = state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .firstOrNull { it.type == EventType.JOB_LOST && it.involvedResidentIds().contains(r.id) }

        val sustainedUnemployment = r.awareness
            .firstOrNull { it.startsWith(UNEMPLOYED_SINCE_PREFIX) }
            ?.substringAfterLast('@')?.toLongOrNull()
            ?.let { since -> ctx.now - since >= SUSTAINED_UNEMPLOYMENT_DAYS * SimTime.MINUTES_PER_DAY }
            ?: false

        markUnemployedSince(ctx, r)
        if (jobLostEvent == null && !sustainedUnemployment) return

        val reasonKey = "unemployment:${jobLostEvent?.id ?: "sustained"}"
        if (onCooldown(ctx, r, reasonKey)) return

        val causeIds = listOfNotNull(jobLostEvent?.id)
        applyDrift(ctx, r, BeliefTopic.ECONOMIC_OPTIMISM, -DRIFT_MAX, reasonKey,
            "Being out of work has worn down their sense that things are looking up.", causeIds)
        applyDrift(ctx, r, BeliefTopic.TRUST_IN_GOVERNMENT, -DRIFT_MIN, reasonKey,
            "Being out of work has worn down their sense that things are looking up.", causeIds)
        applyDrift(ctx, r, BeliefTopic.INSTITUTIONAL_TRUST, -DRIFT_MIN, reasonKey,
            "Being out of work has worn down their sense that things are looking up.", causeIds)
        markCooldown(ctx, r, reasonKey)
    }

    /** Records (once) when this resident first became unemployed, via the same
     *  [Resident.awareness] namespaced-flag trick `PersonalityDevelopmentSystem` uses for
     *  cooldowns — cleared the moment they're employed again. */
    private fun markUnemployedSince(ctx: TickContext, r: Resident) {
        if (ctx.state.employmentOf(r) != null) {
            r.awareness.removeAll { it.startsWith(UNEMPLOYED_SINCE_PREFIX) }
            return
        }
        if (r.awareness.any { it.startsWith(UNEMPLOYED_SINCE_PREFIX) }) return
        r.awareness += "$UNEMPLOYED_SINCE_PREFIX@${ctx.now}"
    }

    // ------------------------------------------------------------ trigger 3: personal success

    /** Reuses `PersonalityDevelopmentSystem`'s own "repeated ACHIEVEMENT memories in a recent
     *  window" pattern density check (same window/threshold constants) — a real run of successes,
     *  not a single lucky memory, drifts [BeliefTopic.ECONOMIC_OPTIMISM] and [BeliefTopic.RISK_TOLERANCE] up. */
    private fun evaluatePersonalSuccess(ctx: TickContext, r: Resident) {
        val windowStart = ctx.now - PersonalityDevelopmentSystem.PATTERN_WINDOW_DAYS * SimTime.MINUTES_PER_DAY
        val achievements = r.memories.filter {
            it.createdAt >= windowStart && it.importance >= 45.0 && it.type == MemoryType.ACHIEVEMENT
        }
        if (achievements.size < PersonalityDevelopmentSystem.PATTERN_THRESHOLD) return

        val reasonKey = "personal_success"
        if (onCooldown(ctx, r, reasonKey)) return
        val causeIds = achievements.sortedByDescending { it.createdAt }.take(4).mapNotNull { it.eventId }
        applyDrift(ctx, r, BeliefTopic.ECONOMIC_OPTIMISM, DRIFT_MIN, reasonKey,
            "A real run of things going right has them feeling like the future's theirs to shape.", causeIds)
        applyDrift(ctx, r, BeliefTopic.RISK_TOLERANCE, DRIFT_MIN, reasonKey,
            "A real run of things going right has them feeling like the future's theirs to shape.", causeIds)
        markCooldown(ctx, r, reasonKey)
    }

    // ------------------------------------------------------------ trigger 4: community response

    /** A resolved petition ([EventType.PETITION_RESOLVED], `outcome == "succeeded"`) drifts
     *  [BeliefTopic.TRUST_IN_GOVERNMENT]/[BeliefTopic.COMMUNITY_LOYALTY] up for signatories and the
     *  starter — the town actually listened. A `WEATHER_DAMAGE` flood event that this resident
     *  lived through and whose building has since been repaired (`BUILDING_REPAIRED` with a
     *  matching `buildingId`, found in the recent window) reads the same way: the town pulled
     *  together and things got fixed. Both are genuinely public, town-level good news, so this is
     *  the one trigger that can touch residents who weren't personally the one who acted. */
    private fun evaluateCommunityResponse(ctx: TickContext, r: Resident) {
        val state = ctx.state
        val recentEvents = state.recentEventIds.asReversed().mapNotNull { ctx.eventIndex.get(it) }

        val petitionWin = recentEvents.firstOrNull { event ->
            event.type == EventType.PETITION_RESOLVED &&
                event.payload["outcome"] == "succeeded" &&
                (event.sourceResidentId == r.id || r.id in event.targetResidentIds)
        }
        if (petitionWin != null) {
            val reasonKey = "petition_win:${petitionWin.id}"
            if (!onCooldown(ctx, r, reasonKey)) {
                applyDrift(ctx, r, BeliefTopic.TRUST_IN_GOVERNMENT, DRIFT_MIN, reasonKey,
                    "The town actually listened, for once — that counts for something.", listOf(petitionWin.id))
                applyDrift(ctx, r, BeliefTopic.COMMUNITY_LOYALTY, DRIFT_MIN, reasonKey,
                    "The town actually listened, for once — that counts for something.", listOf(petitionWin.id))
                markCooldown(ctx, r, reasonKey)
            }
        }

        val homeId = r.homeBuildingId ?: return
        val floodedHere = recentEvents.firstOrNull {
            it.type == EventType.WEATHER_DAMAGE && it.buildingId == homeId
        } ?: return
        val repaired = recentEvents.firstOrNull {
            it.type == EventType.BUILDING_REPAIRED && it.buildingId == homeId && it.time > floodedHere.time
        } ?: return
        val reasonKey = "flood_recovery:${floodedHere.id}"
        if (onCooldown(ctx, r, reasonKey)) return
        applyDrift(ctx, r, BeliefTopic.COMMUNITY_LOYALTY, DRIFT_MIN, reasonKey,
            "Seeing the town pull together to put things right afterwards meant a lot.", listOf(floodedHere.id, repaired.id))
        markCooldown(ctx, r, reasonKey)
    }

    // ------------------------------------------------------------ public read helpers

    /** [Belief.position] for [topic], or the neutral `0.0` default if this resident has no
     *  formed view yet. Safe for any other system (a future conversation-influence/election-
     *  voting system) to read without a null check. */
    fun positionOn(resident: Resident, topic: BeliefTopic): Double = resident.beliefs[topic]?.position ?: 0.0

    /** [Belief.confidence] for [topic], or `0.0` ("no strong opinion yet") if absent. */
    fun confidenceOn(resident: Resident, topic: BeliefTopic): Double = resident.beliefs[topic]?.confidence ?: 0.0

    // ------------------------------------------------------------ internals

    /** The one place [Belief.position]/[Belief.confidence] are ever mutated by a real trigger.
     *  [delta] is scaled down by existing confidence (a firmly-held belief resists movement, down
     *  to [CONFIDENCE_RESISTANCE_FLOOR] of the raw delta at confidence 1.0) before being applied
     *  and clamped into range inline (equivalent to [Belief.clamp], which callers constructing a
     *  [Belief] by hand — e.g. inheritance — use instead). Confidence itself ticks up a little on
     *  every genuine trigger. Creates the [Belief] entry at the neutral default if this is the
     *  resident's first view on [topic]. Emits [EventType.BELIEF_SHIFTED] with the real, post-
     *  clamp delta so an already-saturated belief correctly reports a near-zero shift. */
    private fun applyDrift(
        ctx: TickContext, r: Resident, topic: BeliefTopic, delta: Double, reasonKey: String, reason: String,
        causeIds: List<Long>
    ) {
        val belief = r.beliefs.getOrPut(topic) {
            Belief(topic = topic, position = 0.0, confidence = 0.0, lastUpdatedAt = ctx.now)
        }
        val resistance = CONFIDENCE_RESISTANCE_FLOOR + (1.0 - CONFIDENCE_RESISTANCE_FLOOR) * (1.0 - belief.confidence)
        val before = belief.position
        belief.position = (before + delta * resistance).coerceIn(-1.0, 1.0)
        val applied = belief.position - before
        belief.confidence = (belief.confidence + CONFIDENCE_GAIN_PER_TRIGGER).coerceIn(0.0, 1.0)
        belief.lastUpdatedAt = ctx.now
        belief.sourceEventIds += causeIds
        while (belief.sourceEventIds.size > MAX_SOURCE_EVENTS) belief.sourceEventIds.removeAt(0)

        if (kotlin.math.abs(applied) < 1e-6) return
        ctx.emit(
            EventType.BELIEF_SHIFTED,
            "${r.fullName}: $reason",
            sourceResidentId = r.id,
            severity = 0.1,
            visibility = EventVisibility.PRIVATE,
            payload = mapOf(
                "topic" to topic.name,
                "delta" to "%.4f".format(applied),
                "reason" to reason
            ),
            causeIds = causeIds
        )
    }

    /** Reuses [Resident.awareness], namespaced `"belief_cooldown:<reasonKey>@<simMinutes>"` — same
     *  trick `PersonalityDevelopmentSystem.onCooldown`/`markCooldown` use, distinct prefix so the
     *  two systems' cooldown entries can never collide. */
    private fun onCooldown(ctx: TickContext, r: Resident, reasonKey: String): Boolean {
        val last = r.awareness.firstOrNull { it.startsWith("$COOLDOWN_PREFIX$reasonKey@") } ?: return false
        val ts = last.substringAfterLast('@').toLongOrNull() ?: return false
        return ctx.now - ts < SAME_TRIGGER_COOLDOWN_DAYS * SimTime.MINUTES_PER_DAY
    }

    private fun markCooldown(ctx: TickContext, r: Resident, reasonKey: String) {
        r.awareness.removeAll { it.startsWith("$COOLDOWN_PREFIX$reasonKey@") }
        r.awareness += "$COOLDOWN_PREFIX$reasonKey@${ctx.now}"
    }

    private const val COOLDOWN_PREFIX = "belief_cooldown:"
    private const val UNEMPLOYED_SINCE_PREFIX = "belief_unemployed_since:"
    private const val MAX_SOURCE_EVENTS = 6
}
