package com.ripple.town.core.simulation

import com.ripple.town.core.model.ActiveEmotion
import com.ripple.town.core.model.EmotionType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime

/**
 * Distinct, actively-felt emotional states, layered ON TOP of the [com.ripple.town.core.model.Needs]
 * sliders — see `Resident.activeEmotions`/[ActiveEmotion] and `docs/simulation-rules.md`
 * "Active emotions". Two responsibilities, split the same way `EconomySystem.scheduleShock` /
 * `EconomySystem.isInShock` split spawn vs. read:
 *
 * - [spawnEmotion] — called by other systems at emotionally significant moments (bereavement,
 *   job loss, a wedding...) to add a new, decaying reaction. Purely additive alongside whatever
 *   instant need-delta code already lives at that call site (e.g. `owner.needs.stress += 18.0`
 *   in `EconomySystem.closeBusiness` stays exactly as-is).
 * - [updateDaily] — the bounded daily decay pass, wired into `SimulationCoordinator`'s
 *   `if (newDay)` block alongside every other `updateDaily` system.
 * - [behaviourModifier] — a small, bounded multiplier `DecisionSystem` composes into a candidate
 *   action's `personalityFit` term, the same shape as `DecisionSystem`'s existing
 *   `SHOCK_LOW_KEY_BOOST`/`SHOCK_EFFORTFUL_DAMPEN` shock-period nudge, just keyed off active
 *   emotions instead of the shock flag.
 */
object EmotionSystem {

    /** Max concurrent active emotions per resident — mirrors `resident.memories`' own 40-entry
     *  cap (`TickContext.addMemory`) and `resident.knownFacts`' 60-entry cap, just smaller since
     *  emotions are meant to be a handful of *currently* live reactions, not a lifetime log. */
    const val MAX_ACTIVE_EMOTIONS = 6

    /** Below this intensity a decaying emotion is considered negligible and removed outright. */
    const val NEGLIGIBLE_INTENSITY = 1.0

    /**
     * Adds a new active emotion of [type] at [intensity] (0..100, clamped) to [resident],
     * bounded to [MAX_ACTIVE_EMOTIONS]. If the resident already has a live emotion of the same
     * [type], it is refreshed in place (`lastTriggeredAt` bumped, intensity raised towards the
     * new value rather than stacking a second copy) — a fresh bereavement while already grieving
     * should deepen the grief, not spawn a second, independently-decaying GRIEF entry.
     *
     * Only spawns for [com.ripple.town.core.model.DetailLevel.DETAILED] residents — background
     * residents don't get full per-tick simulation (see `NeedsSystem.updateBackground`), and an
     * emotion nobody ever reads back is just wasted state.
     *
     * [decayRate] defaults to a sensible per-type pace (see [defaultDecayRate]) but callers may
     * pass their own for an event they judge unusually mild/severe.
     */
    fun spawnEmotion(
        ctx: TickContext,
        resident: Resident,
        type: EmotionType,
        intensity: Double,
        sourceEventId: Long? = null,
        relatedResidentId: Long? = null,
        decayRate: Double = defaultDecayRate(type)
    ) {
        if (resident.detailLevel != com.ripple.town.core.model.DetailLevel.DETAILED) return
        val clamped = intensity.coerceIn(0.0, 100.0)
        val existing = resident.activeEmotions.firstOrNull { it.type == type }
        if (existing != null) {
            existing.intensity = (existing.intensity + clamped * 0.5).coerceIn(0.0, 100.0)
            existing.lastTriggeredAt = ctx.now
            return
        }
        resident.activeEmotions += ActiveEmotion(
            type = type,
            intensity = clamped,
            sourceEventId = sourceEventId,
            relatedResidentId = relatedResidentId,
            createdAt = ctx.now,
            lastTriggeredAt = ctx.now,
            decayRate = decayRate
        )
        // Bounded like memories: evict the oldest/weakest once over the cap, never grow unbounded.
        if (resident.activeEmotions.size > MAX_ACTIVE_EMOTIONS) {
            val weakest = resident.activeEmotions.minByOrNull { it.intensity }
            if (weakest != null) resident.activeEmotions.remove(weakest)
        }
    }

    /**
     * Daily bounded decay pass: every active emotion loses `decayRate` intensity, removed once
     * it drops to [NEGLIGIBLE_INTENSITY] or below. Mirrors `NeedsSystem.traumaRecoveryDamping`'s
     * "small bounded modifier, never a new open-ended mechanic" tone — this only ever moves
     * `intensity` downward, never resurfaces or re-triggers an emotion on its own.
     */
    fun updateDaily(ctx: TickContext) {
        for (r in ctx.state.residentsOrdered()) {
            if (!r.inTown || r.activeEmotions.isEmpty()) continue
            val it = r.activeEmotions.iterator()
            while (it.hasNext()) {
                val emotion = it.next()
                emotion.intensity = (emotion.intensity - emotion.decayRate).coerceAtLeast(0.0)
                if (emotion.intensity <= NEGLIGIBLE_INTENSITY) it.remove()
            }
        }
    }

    /** Per-type default daily decay — grief/loneliness linger longest, sharp reactive states
     *  like fear/anger/relief fade fastest. All bounded to a handful of days to a few weeks. */
    fun defaultDecayRate(type: EmotionType): Double = when (type) {
        EmotionType.GRIEF -> 3.0
        EmotionType.LONELINESS -> 4.0
        EmotionType.REGRET -> 4.0
        EmotionType.SHAME -> 5.0
        EmotionType.GUILT -> 5.0
        EmotionType.ANXIETY -> 6.0
        EmotionType.JEALOUSY -> 7.0
        EmotionType.HOPE -> 8.0
        EmotionType.PRIDE -> 8.0
        EmotionType.FEAR -> 10.0
        EmotionType.ANGER -> 12.0
        EmotionType.RELIEF -> 14.0
    }

    /** Broad behavioural buckets `DecisionSystem`'s candidate actions fall into, for
     *  [behaviourModifier] to key off — deliberately coarse (mirrors `ActionKind`'s own
     *  granularity) rather than one rule per `ActionKind`. */
    enum class ActionCategory { LOW_KEY, EFFORTFUL, AVOIDANT, GOAL_PURSUING, SOCIAL }

    /**
     * Small, bounded multiplier (never below 0.5, never above 1.5) on a candidate action's
     * `personalityFit`-style term, based on [resident]'s currently active emotions — composed
     * multiplicatively alongside `DecisionSystem`'s existing `SHOCK_LOW_KEY_BOOST`/
     * `SHOCK_EFFORTFUL_DAMPEN` shock nudge and every personality trait term, never replacing any
     * of them. Fresh/strong emotions swing further than faint/decayed ones (scaled by
     * `intensity / 100.0`).
     *
     * - GRIEF / LONELINESS nudge low-key and social actions up, effortful ones down (someone
     *   grieving wants quiet company or rest, not to chase a promotion).
     * - FEAR / ANXIETY nudge avoidant/low-key actions up (staying home, resting) and effortful
     *   ones down.
     * - HOPE nudges goal-pursuing actions up — a hopeful resident is more likely to act on their
     *   ambitions.
     * - PRIDE nudges goal-pursuing actions up slightly (riding the high of a recent win).
     */
    fun behaviourModifier(resident: Resident, category: ActionCategory): Double {
        if (resident.activeEmotions.isEmpty()) return 1.0
        var modifier = 1.0
        for (emotion in resident.activeEmotions) {
            val weight = (emotion.intensity / 100.0).coerceIn(0.0, 1.0)
            modifier *= when (emotion.type) {
                EmotionType.GRIEF, EmotionType.LONELINESS -> when (category) {
                    ActionCategory.LOW_KEY, ActionCategory.SOCIAL -> 1.0 + 0.2 * weight
                    ActionCategory.EFFORTFUL, ActionCategory.GOAL_PURSUING -> 1.0 - 0.25 * weight
                    ActionCategory.AVOIDANT -> 1.0
                }
                EmotionType.FEAR, EmotionType.ANXIETY -> when (category) {
                    ActionCategory.AVOIDANT, ActionCategory.LOW_KEY -> 1.0 + 0.2 * weight
                    ActionCategory.EFFORTFUL, ActionCategory.SOCIAL -> 1.0 - 0.2 * weight
                    ActionCategory.GOAL_PURSUING -> 1.0
                }
                EmotionType.HOPE -> when (category) {
                    ActionCategory.GOAL_PURSUING -> 1.0 + 0.3 * weight
                    else -> 1.0
                }
                EmotionType.PRIDE -> when (category) {
                    ActionCategory.GOAL_PURSUING -> 1.0 + 0.15 * weight
                    else -> 1.0
                }
                else -> 1.0
            }
        }
        return modifier.coerceIn(0.5, 1.5)
    }

    /** In-world days worth of decay a given number of ticks represents — unused by the engine
     *  itself (decay is driven purely by `updateDaily`'s once-per-day call), kept only as a
     *  documented conversion for tests that want to reason in ticks. */
    fun daysOf(minutes: Long): Double = minutes.toDouble() / SimTime.MINUTES_PER_DAY
}
