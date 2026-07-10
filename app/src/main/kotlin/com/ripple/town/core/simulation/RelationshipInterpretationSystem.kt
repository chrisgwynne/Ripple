package com.ripple.town.core.simulation

import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Resident

/**
 * Turns `InteractionSystem`'s relationship-kind transition thresholds from flat constants
 * (identical for every resident, regardless of who they are) into a per-pair, personality-
 * shaped tolerance. Built in response to this session's Simulation Reality Review finding:
 * *"Relationship kind thresholds are universal, not personality-inflected... flat, identical
 * for every resident regardless of empathy/forgiveness-adjacent traits."*
 *
 * Reads [Resident.effectivePersonality] (birth baseline + lifetime
 * [com.ripple.town.core.model.PersonalityModifiers] drift), not the raw baseline `personality`
 * field — a resident's tolerance for a curdling relationship should reflect who they've
 * *become*, same convention `PersonalityDevelopmentSystem`'s docs establish for
 * `DecisionSystem`/`InteractionSystem` compatibility reads.
 *
 * ### Formula shape
 * For a resentment-side threshold (the value resentment must exceed to trigger a negative
 * transition):
 * ```
 * effective = BASE + (empathy + patience - 1.0) * SPREAD - (impulsiveness + (1.0 - honesty) - 1.0) * SPREAD
 * ```
 * clamped to `[BASE - MAX_SWING, BASE + MAX_SWING]`.
 *
 * Reasoning per term:
 * - **empathy + patience** (forgiveness-adjacent traits): higher values push the threshold
 *   *up* — a patient, empathetic resident absorbs more resentment before giving up on someone.
 *   Centred on 1.0 (both at their 0.5 midpoint) so an "average" resident sees zero shift.
 * - **impulsiveness + (1 − honesty)**: higher impulsiveness and lower honesty both push the
 *   threshold *down* — an impulsive resident acts on resentment sooner, and (per this session's
 *   established honesty↔trust-in-others mapping, see `docs/simulation-rules.md#personality-
 *   drift-from-lived-experience`) a less honest resident is also less trusting of others,
 *   reads ambiguous friction more suspiciously, and gives up sooner. Also centred on 1.0.
 * - Both terms share the same `SPREAD` scale and are subtracted from each other, so a resident
 *   who is both very patient *and* very impulsive (contradictory but not impossible after
 *   enough drift) nets out close to neutral rather than both swings stacking unboundedly.
 *
 * For an affection-side threshold (the *floor* affection must drop below), the same personality
 * combination pushes the *opposite* direction: a more tolerant resident's affection floor sits
 * *lower* (affection has to drop further before it's "too low to save"), so the swing sign is
 * flipped relative to the resentment-side formula.
 *
 * ### Whose tolerance governs
 * `Relationship` stores one shared `kind`/dimension set per unordered pair — there is no
 * per-direction record. A relationship curdling only takes one party giving up (you don't need
 * mutual agreement to become someone's rival, or to be the one who finally can't take a
 * marriage anymore), so **the less tolerant of the two residents' effective thresholds
 * governs**: the resentment threshold that is easier to cross (`min` of the two), and the
 * affection floor that is easier to cross (`max` of the two, since a *higher* floor is crossed
 * sooner by a falling affection value). This mirrors real relationships: it only takes one
 * person deciding they've had enough.
 *
 * ### Bounded & deterministic
 * Every function here is a pure function of the two residents' existing, already-deterministic
 * state (`effectivePersonality()` derives from birth personality + drift, both already
 * deterministic). No new `ctx.rng` calls — personality itself is the source of variation, so
 * none is needed. Every returned threshold is clamped to stay within `±MAX_SWING` of the
 * original flat constant it replaces, so this is a bounded reinterpretation of the existing
 * design, never an unbounded rewrite: the most tolerant possible resident pair still curdles
 * eventually, and the least tolerant possible pair still needs *some* real friction first.
 */
object RelationshipInterpretationSystem {

    /** Shared personality-swing scale for every threshold below — see class doc for reasoning. */
    private const val SPREAD = 12.0

    /** No threshold may move further than this from its original flat constant. */
    private const val MAX_SWING = 18.0

    // ---- RIVAL formation (InteractionSystem.updateKind) ----------------------------------

    private const val RIVAL_RESENTMENT_BASE = 55.0
    private const val RIVAL_AFFECTION_BASE = 30.0

    /**
     * Resentment value the pair's resentment must exceed to curdle into [RelationshipKind.RIVAL].
     * Governed by the less tolerant resident (lower effective threshold wins).
     */
    fun rivalResentmentThresholdFor(a: Resident, b: Resident): Double =
        minOf(
            resentmentGiveUpThreshold(RIVAL_RESENTMENT_BASE, a.effectivePersonality()),
            resentmentGiveUpThreshold(RIVAL_RESENTMENT_BASE, b.effectivePersonality())
        )

    /**
     * Affection value the pair's affection must drop below to curdle into [RelationshipKind.RIVAL].
     * Governed by the less tolerant resident (higher effective floor is crossed sooner).
     */
    fun rivalAffectionFloorFor(a: Resident, b: Resident): Double =
        maxOf(
            affectionFloor(RIVAL_AFFECTION_BASE, a.effectivePersonality()),
            affectionFloor(RIVAL_AFFECTION_BASE, b.effectivePersonality())
        )

    // ---- PARTNER -> estranged/separation (InteractionSystem.romanticArcs) ----------------

    private const val PARTNER_BREAKUP_RESENTMENT_BASE = 60.0
    private const val PARTNER_BREAKUP_AFFECTION_BASE = 30.0

    fun partnerBreakupResentmentThresholdFor(a: Resident, b: Resident): Double =
        minOf(
            resentmentGiveUpThreshold(PARTNER_BREAKUP_RESENTMENT_BASE, a.effectivePersonality()),
            resentmentGiveUpThreshold(PARTNER_BREAKUP_RESENTMENT_BASE, b.effectivePersonality())
        )

    fun partnerBreakupAffectionFloorFor(a: Resident, b: Resident): Double =
        maxOf(
            affectionFloor(PARTNER_BREAKUP_AFFECTION_BASE, a.effectivePersonality()),
            affectionFloor(PARTNER_BREAKUP_AFFECTION_BASE, b.effectivePersonality())
        )

    // ---- SPOUSE -> separation (InteractionSystem.romanticArcs) ---------------------------

    private const val SPOUSE_SEPARATION_RESENTMENT_BASE = 72.0
    private const val SPOUSE_SEPARATION_AFFECTION_BASE = 25.0

    fun spouseSeparationResentmentThresholdFor(a: Resident, b: Resident): Double =
        minOf(
            resentmentGiveUpThreshold(SPOUSE_SEPARATION_RESENTMENT_BASE, a.effectivePersonality()),
            resentmentGiveUpThreshold(SPOUSE_SEPARATION_RESENTMENT_BASE, b.effectivePersonality())
        )

    fun spouseSeparationAffectionFloorFor(a: Resident, b: Resident): Double =
        maxOf(
            affectionFloor(SPOUSE_SEPARATION_AFFECTION_BASE, a.effectivePersonality()),
            affectionFloor(SPOUSE_SEPARATION_AFFECTION_BASE, b.effectivePersonality())
        )

    // ---- SPOUSE -> divorce (InteractionSystem.processSeparations) ------------------------

    private const val DIVORCE_RESENTMENT_BASE = 60.0

    /**
     * Resentment value that must be exceeded for an already-separated marriage to progress to
     * divorce. Both residents are already estranged by this point, so the same "less tolerant
     * governs" rule applies — whichever of the two is quicker to let a grudge finalise things.
     */
    fun divorceResentmentThresholdFor(a: Resident, b: Resident): Double =
        minOf(
            resentmentGiveUpThreshold(DIVORCE_RESENTMENT_BASE, a.effectivePersonality()),
            resentmentGiveUpThreshold(DIVORCE_RESENTMENT_BASE, b.effectivePersonality())
        )

    // ---- Core formulas ---------------------------------------------------------------------

    /**
     * A resentment threshold shifted by one resident's forgiveness-adjacent traits. Higher
     * empathy+patience raises it (harder to trigger); higher impulsiveness and lower honesty
     * lower it (easier to trigger). See class doc for the full reasoning.
     */
    private fun resentmentGiveUpThreshold(base: Double, p: Personality): Double {
        val forgiveness = (p.empathy + p.patience - 1.0) * SPREAD
        val volatility = (p.impulsiveness + (1.0 - p.honesty) - 1.0) * SPREAD
        val raw = base + forgiveness - volatility
        return raw.coerceIn(base - MAX_SWING, base + MAX_SWING)
    }

    /**
     * An affection floor shifted by one resident's forgiveness-adjacent traits, in the opposite
     * direction from [resentmentGiveUpThreshold]: a more tolerant resident's floor sits lower
     * (affection has to fall further before it reads as "too low to save").
     */
    private fun affectionFloor(base: Double, p: Personality): Double {
        val forgiveness = (p.empathy + p.patience - 1.0) * SPREAD
        val volatility = (p.impulsiveness + (1.0 - p.honesty) - 1.0) * SPREAD
        val raw = base - forgiveness + volatility
        return raw.coerceIn(base - MAX_SWING, base + MAX_SWING)
    }
}
