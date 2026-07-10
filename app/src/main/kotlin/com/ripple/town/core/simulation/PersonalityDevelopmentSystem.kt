package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Memory
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.PersonalityModifiers
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime

/**
 * Daily system: nudges each detailed resident's [PersonalityModifiers] — the drift layered on
 * top of the untouched birth-baseline [com.ripple.town.core.model.Personality] — from *patterns*
 * in lived experience, never from a single ordinary event. See
 * `docs/simulation-rules.md` "Personality drift from lived experience" for the full design
 * write-up and the brief's trait-mapping decisions.
 *
 * Two trigger shapes, both required by the brief:
 *   1. **Repeated pattern** — [PATTERN_THRESHOLD] or more recent significant memories of a
 *      matching flavour within [PATTERN_WINDOW_DAYS] in-game days.
 *   2. **One-off, very-high-importance** — a single memory whose `importance >=
 *      [SOLO_TRIGGER_IMPORTANCE]` is "big enough on its own", no repetition required.
 *
 * Every application is small ([DELTA_MIN]..[DELTA_MAX] before life-stage scaling), and the
 * *lifetime* accumulated modifier per trait is hard-capped at ±[MAX_LIFETIME_DRIFT] — see
 * [applyDrift]. Nothing here ever recomputes or overwrites the birth baseline; it only ever
 * nudges [Resident.personalityModifiers].
 */
object PersonalityDevelopmentSystem {

    /** Cap on accumulated lifetime drift per trait, in either direction. Per the brief's
     *  0.20-0.30 band; 0.25 keeps a resident recognisably themselves even after a very eventful
     *  life while still allowing a meaningfully different effective trait. */
    const val MAX_LIFETIME_DRIFT = 0.25

    /** Small per-trigger delta band (pre life-stage scaling). Reaching the lifetime cap from
     *  these alone takes on the order of a dozen-plus real triggers, matching "should take many
     *  real events, not one or two". */
    const val DELTA_MIN = 0.01
    const val DELTA_MAX = 0.03

    /** How far back (in-game days) the repeated-pattern scan looks for matching memories. */
    const val PATTERN_WINDOW_DAYS = 45L

    /** Minimum matching memories within the window before a repeated-pattern trigger fires. */
    const val PATTERN_THRESHOLD = 2

    /** A single memory at or above this importance (0..100) is "big enough on its own" and
     *  triggers a one-off shift without needing repetition. */
    const val SOLO_TRIGGER_IMPORTANCE = 80.0

    /** More malleable in youth, more set in their ways as adults — per the brief. */
    const val CHILD_TEEN_MULTIPLIER = 1.6
    const val ADULT_ELDER_MULTIPLIER = 1.0

    /** Never re-trigger the *same* trait+reason combination for a resident more than once per
     *  this many in-game days — keeps a single lingering pattern from re-firing every tick it's
     *  still true, matching how one lived event should read as one causal beat, not a drip. */
    const val SAME_TRIGGER_COOLDOWN_DAYS = 20L

    fun updateDaily(ctx: TickContext) {
        for (r in ctx.state.detailedResidents().sortedBy { it.id }) {
            if (!r.inTown) continue
            evaluate(ctx, r)
            evaluateLeadership(ctx, r)
        }
    }

    private fun evaluate(ctx: TickContext, r: Resident) {
        val stage = r.lifeStageAt(ctx.now)
        val scale = when (stage) {
            LifeStage.CHILD, LifeStage.TEEN -> CHILD_TEEN_MULTIPLIER
            LifeStage.ADULT, LifeStage.ELDER -> ADULT_ELDER_MULTIPLIER
        }
        val windowStart = ctx.now - PATTERN_WINDOW_DAYS * SimTime.MINUTES_PER_DAY
        val recentSignificant = r.memories.filter { it.createdAt >= windowStart && it.importance >= 45.0 }

        // --- Repeated success (achievement pattern): brief's "confidence" -> courage,
        // brief's "optimism" -> ambition (a forward-looking drive trait is the closest existing
        // analogue to "expects good things ahead"). Mapping documented in simulation-rules.md.
        val achievements = recentSignificant.filter { it.type == MemoryType.ACHIEVEMENT }
        if (achievements.size >= PATTERN_THRESHOLD) {
            triggerPattern(
                ctx, r, achievements, "repeated_success", scale,
                traitDeltas = mapOf(TRAIT_COURAGE to +1, TRAIT_AMBITION to +1, TRAIT_DISCIPLINE to +1),
                reason = "A run of real successes has built quiet confidence."
            )
        }

        // --- Repeated failure / job loss (LOSS memories tied to job/business language). We
        // don't have a dedicated MemoryType for job loss specifically (it's recorded as LOSS —
        // see LifecycleSystem.die/EconomySystem business-closure path and GoalSystem), so this
        // reuses LOSS as the closest existing flavour, same as everywhere else in the codebase
        // maps described effects onto the nearest tracked type rather than inventing a new one.
        val losses = recentSignificant.filter { it.type == MemoryType.LOSS }
        if (losses.size >= PATTERN_THRESHOLD) {
            triggerPattern(
                ctx, r, losses, "repeated_setback", scale,
                traitDeltas = mapOf(TRAIT_DISCIPLINE to -1, TRAIT_COURAGE to -1),
                reason = "One setback after another has worn down their nerve."
            )
        }

        // --- Trauma: high-importance LOSS/FEAR/BETRAYAL, pattern OR solo trigger.
        val traumaTypes = setOf(MemoryType.LOSS, MemoryType.FEAR, MemoryType.BETRAYAL)
        val trauma = recentSignificant.filter { it.type in traumaTypes }
        if (trauma.size >= PATTERN_THRESHOLD) {
            triggerPattern(
                ctx, r, trauma, "trauma_pattern", scale,
                traitDeltas = mapOf(TRAIT_COURAGE to -1, TRAIT_EMPATHY to +1),
                reason = "Repeated hard knocks have left them warier, but more understanding of others' pain."
            )
        }
        r.memories.filter { it.type in traumaTypes && it.importance >= SOLO_TRIGGER_IMPORTANCE }
            .forEach { m -> triggerSolo(ctx, r, m, "trauma_solo", scale,
                traitDeltas = mapOf(TRAIT_COURAGE to -1, TRAIT_EMPATHY to +1),
                reason = "Something that big doesn't leave you the same.") }

        // --- Betrayal specifically: brief calls out trust/honesty-adjacent effects distinct
        // from general trauma. Nearest existing trait for "trust in others" is honesty (how they
        // themselves deal straight) — documented explicitly as an imperfect but closest mapping.
        val betrayals = recentSignificant.filter { it.type == MemoryType.BETRAYAL }
        if (betrayals.size >= PATTERN_THRESHOLD) {
            triggerPattern(
                ctx, r, betrayals, "betrayal_pattern", scale,
                traitDeltas = mapOf(TRAIT_HONESTY to -1, TRAIT_SOCIABILITY to -1),
                reason = "Being let down more than once has made them guarded."
            )
        }

        // --- Any single very-high-importance memory not already covered above (achievement or
        // romance-flavoured triumphs, etc.) still gets a one-off "this was big enough on its
        // own" nudge, generically flavoured towards ambition/courage — the two traits the brief
        // most associates with life-defining moments.
        r.memories.filter {
            it.importance >= SOLO_TRIGGER_IMPORTANCE && it.type !in traumaTypes && it.type != MemoryType.ACHIEVEMENT
        }.forEach { m ->
            triggerSolo(ctx, r, m, "defining_moment", scale,
                traitDeltas = mapOf(TRAIT_AMBITION to +1),
                reason = "A moment big enough to leave a mark.")
        }
    }

    /** Called once, right after a business-success-flavoured pattern would also naturally cover
     *  it via ACHIEVEMENT memories — leadership itself (mayor/councillor/business owner) is a
     *  *standing state*, not a memory, so it's driven from real-time role checks rather than the
     *  memory scan above. Bounded to fire at most once per [SAME_TRIGGER_COOLDOWN_DAYS]. */
    fun evaluateLeadership(ctx: TickContext, r: Resident) {
        val state = ctx.state
        val isMayor = state.mayorId == r.id
        val isCouncillor = r.id in state.councillorIds
        val ownsBusiness = state.businesses.values.any { it.ownerId == r.id && it.open }
        if (!isMayor && !isCouncillor && !ownsBusiness) return
        val stage = r.lifeStageAt(ctx.now)
        val scale = if (stage == LifeStage.CHILD || stage == LifeStage.TEEN) CHILD_TEEN_MULTIPLIER else ADULT_ELDER_MULTIPLIER
        val reasonKey = "leadership"
        if (onCooldown(ctx, r, reasonKey)) return
        applyDrift(ctx, r, TRAIT_COURAGE, DELTA_MIN * scale, reasonKey,
            "Carrying real responsibility for others has been steadying.", emptyList())
        applyDrift(ctx, r, TRAIT_AMBITION, DELTA_MIN * scale, reasonKey,
            "Carrying real responsibility for others has been steadying.", emptyList())
        markCooldown(ctx, r, reasonKey)
    }

    /** Recovery: a resident whose shock period ended cleanly (never fired into a crisis) gets a
     *  small resilience-flavoured uptick — see `EconomySystem.isInShock`/`scheduleShock`. Called
     *  from `DelayedEffectSystem`-adjacent daily bookkeeping the day a shock window closes. */
    fun evaluateRecovery(ctx: TickContext, r: Resident, sourceEventId: Long?) {
        val stage = r.lifeStageAt(ctx.now)
        val scale = if (stage == LifeStage.CHILD || stage == LifeStage.TEEN) CHILD_TEEN_MULTIPLIER else ADULT_ELDER_MULTIPLIER
        val reasonKey = "recovery"
        if (onCooldown(ctx, r, reasonKey)) return
        applyDrift(ctx, r, TRAIT_COURAGE, DELTA_MIN * scale, reasonKey,
            "Came through a hard patch and kept going.", listOfNotNull(sourceEventId))
        markCooldown(ctx, r, reasonKey)
    }

    /** Parenthood: a small, one-time-per-birth nudge. Called from `LifecycleSystem.bear`. */
    fun evaluateParenthood(ctx: TickContext, parent: Resident, birthEventId: Long) {
        val stage = parent.lifeStageAt(ctx.now)
        val scale = if (stage == LifeStage.CHILD || stage == LifeStage.TEEN) CHILD_TEEN_MULTIPLIER else ADULT_ELDER_MULTIPLIER
        applyDrift(ctx, parent, TRAIT_PATIENCE, DELTA_MIN * scale, "parenthood:$birthEventId",
            "Becoming a parent again has taught patience.", listOf(birthEventId))
        applyDrift(ctx, parent, TRAIT_EMPATHY, DELTA_MIN * scale, "parenthood:$birthEventId",
            "Becoming a parent again has taught patience.", listOf(birthEventId))
    }

    /** Crime/punishment: called from `CrimeSystem.investigate` once accuracy is known. True
     *  culprits drift towards discipline/honesty (having gotten away with it, or been caught,
     *  either way it's a beat about their own conduct); the wrongly accused drift on
     *  courage/trust (honesty, the nearest "trust in others" trait) from feeling wronged by the
     *  town, not a morality shift of their own. */
    fun evaluateCrimeOutcome(ctx: TickContext, r: Resident, wasCulprit: Boolean, reportEventId: Long) {
        val stage = r.lifeStageAt(ctx.now)
        val scale = if (stage == LifeStage.CHILD || stage == LifeStage.TEEN) CHILD_TEEN_MULTIPLIER else ADULT_ELDER_MULTIPLIER
        val reasonKey = "crime_outcome:$reportEventId"
        if (wasCulprit) {
            applyDrift(ctx, r, TRAIT_DISCIPLINE, -DELTA_MAX * scale, reasonKey,
                "Getting caught out has left a mark on how they see themselves.", listOf(reportEventId))
            applyDrift(ctx, r, TRAIT_HONESTY, -DELTA_MIN * scale, reasonKey,
                "Getting caught out has left a mark on how they see themselves.", listOf(reportEventId))
        } else {
            applyDrift(ctx, r, TRAIT_COURAGE, -DELTA_MIN * scale, reasonKey,
                "Being wrongly accused has made them warier of the town's judgement.", listOf(reportEventId))
            applyDrift(ctx, r, TRAIT_HONESTY, -DELTA_MIN * scale, reasonKey,
                "Being wrongly accused has made them warier of the town's judgement.", listOf(reportEventId))
        }
    }

    // ------------------------------------------------------------------ internals

    private fun triggerPattern(
        ctx: TickContext, r: Resident, matches: List<Memory>, reasonKey: String, scale: Double,
        traitDeltas: Map<String, Int>, reason: String
    ) {
        if (onCooldown(ctx, r, reasonKey)) return
        val causeIds = matches.sortedByDescending { it.createdAt }.take(4).mapNotNull { it.eventId }
        for ((trait, sign) in traitDeltas) {
            applyDrift(ctx, r, trait, sign * DELTA_MIN * scale, reasonKey, reason, causeIds)
        }
        markCooldown(ctx, r, reasonKey)
    }

    private fun triggerSolo(
        ctx: TickContext, r: Resident, memory: Memory, reasonPrefix: String, scale: Double,
        traitDeltas: Map<String, Int>, reason: String
    ) {
        val reasonKey = "$reasonPrefix:${memory.id}"
        if (onCooldown(ctx, r, reasonKey)) return
        val causeIds = listOfNotNull(memory.eventId)
        for ((trait, sign) in traitDeltas) {
            applyDrift(ctx, r, trait, sign * DELTA_MAX * scale, reasonKey, reason, causeIds)
        }
        markCooldown(ctx, r, reasonKey)
    }

    /** The one place a [PersonalityModifiers] field is ever mutated. Clamps the *result* of
     *  `current + delta` into `[-MAX_LIFETIME_DRIFT, +MAX_LIFETIME_DRIFT]` — not the effective
     *  trait, the modifier itself — so lifetime drift structurally cannot exceed the cap no
     *  matter how many triggers fire. Emits `PERSONALITY_SHIFTED` with the real delta actually
     *  applied (post-clamp), so a resident already at the cap correctly reports a ~0 delta
     *  rather than a misleading full-size one. Skips emitting when the clamp leaves nothing to
     *  report (already pinned at the cap) — no empty-effect noise events. */
    private fun applyDrift(
        ctx: TickContext, r: Resident, trait: String, delta: Double, reasonKey: String, reason: String,
        causeIds: List<Long>
    ) {
        val m = r.personalityModifiers
        val before = traitValue(m, trait)
        val after = (before + delta).coerceIn(-MAX_LIFETIME_DRIFT, MAX_LIFETIME_DRIFT)
        val applied = after - before
        setTraitValue(m, trait, after)
        if (kotlin.math.abs(applied) < 1e-6) return
        ctx.emit(
            EventType.PERSONALITY_SHIFTED,
            "${r.fullName}: $reason",
            sourceResidentId = r.id,
            severity = 0.12,
            visibility = EventVisibility.PRIVATE,
            payload = mapOf(
                "trait" to trait,
                "delta" to "%.4f".format(applied),
                "reason" to reason
            ),
            causeIds = causeIds
        )
    }

    /** Reuses the existing [Resident.awareness] flag list (already used by [EconomySystem]/
     *  [InterventionEngine] for one-off flags) rather than adding a bespoke cooldown ledger to
     *  [Resident] — entries are namespaced `"personality_cooldown:<reasonKey>@<simMinutes>"` so
     *  they can't collide with the plain string flags those other systems store there. */
    private fun onCooldown(ctx: TickContext, r: Resident, reasonKey: String): Boolean {
        val last = r.awareness.firstOrNull { it.startsWith("$COOLDOWN_PREFIX$reasonKey@") } ?: return false
        val ts = last.substringAfterLast('@').toLongOrNull() ?: return false
        return ctx.now - ts < SAME_TRIGGER_COOLDOWN_DAYS * SimTime.MINUTES_PER_DAY
    }

    private fun markCooldown(ctx: TickContext, r: Resident, reasonKey: String) {
        r.awareness.removeAll { it.startsWith("$COOLDOWN_PREFIX$reasonKey@") }
        r.awareness += "$COOLDOWN_PREFIX$reasonKey@${ctx.now}"
    }

    private const val COOLDOWN_PREFIX = "personality_cooldown:"

    const val TRAIT_KINDNESS = "kindness"
    const val TRAIT_AMBITION = "ambition"
    const val TRAIT_CURIOSITY = "curiosity"
    const val TRAIT_SOCIABILITY = "sociability"
    const val TRAIT_PATIENCE = "patience"
    const val TRAIT_HONESTY = "honesty"
    const val TRAIT_COURAGE = "courage"
    const val TRAIT_DISCIPLINE = "discipline"
    const val TRAIT_EMPATHY = "empathy"
    const val TRAIT_IMPULSIVENESS = "impulsiveness"

    private fun traitValue(m: PersonalityModifiers, trait: String): Double = when (trait) {
        TRAIT_KINDNESS -> m.kindness
        TRAIT_AMBITION -> m.ambition
        TRAIT_CURIOSITY -> m.curiosity
        TRAIT_SOCIABILITY -> m.sociability
        TRAIT_PATIENCE -> m.patience
        TRAIT_HONESTY -> m.honesty
        TRAIT_COURAGE -> m.courage
        TRAIT_DISCIPLINE -> m.discipline
        TRAIT_EMPATHY -> m.empathy
        TRAIT_IMPULSIVENESS -> m.impulsiveness
        else -> 0.0
    }

    private fun setTraitValue(m: PersonalityModifiers, trait: String, value: Double) {
        when (trait) {
            TRAIT_KINDNESS -> m.kindness = value
            TRAIT_AMBITION -> m.ambition = value
            TRAIT_CURIOSITY -> m.curiosity = value
            TRAIT_SOCIABILITY -> m.sociability = value
            TRAIT_PATIENCE -> m.patience = value
            TRAIT_HONESTY -> m.honesty = value
            TRAIT_COURAGE -> m.courage = value
            TRAIT_DISCIPLINE -> m.discipline = value
            TRAIT_EMPATHY -> m.empathy = value
            TRAIT_IMPULSIVENESS -> m.impulsiveness = value
        }
    }
}
