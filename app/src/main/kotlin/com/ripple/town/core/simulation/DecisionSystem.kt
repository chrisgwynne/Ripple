package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EmotionType
import com.ripple.town.core.model.GoalStatus
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.model.isPublicSpace
import com.ripple.town.core.simulation.MemoryRecallSystem.ChildhoodSituation

/**
 * One concrete thing a resident could do next, with the utility terms that
 * scored it. The terms are kept so the UI can answer "Why this action?".
 */
data class ScoredAction(
    val kind: ActionKind,
    val targetBuildingId: Long?,
    val targetResidentId: Long? = null,
    val activity: Activity,
    val durationMinutes: Long,
    val reason: String,
    val needPressure: Double,
    val personalityFit: Double,
    val expectedReward: Double,
    val confidence: Double,
    val socialInfluence: Double,
    val opportunity: Double,
    val risk: Double,
    val cost: Double,
    val effort: Double,
    val moralResistance: Double
) {
    val score: Double =
        (needPressure * personalityFit * expectedReward * confidence * socialInfluence * opportunity) -
            risk - cost - effort - moralResistance
}

enum class ActionKind {
    EAT_HOME, EAT_OUT, SLEEP, GO_TO_WORK, GO_TO_SCHOOL, VISIT_FRIEND, SOCIALISE_PUBLIC,
    SHOP, EXERCISE, LEARN_SKILL, RELAX_HOME, REST_ILL, VISIT_CLINIC, WORK_ON_GOAL, WANDER
}

/**
 * Record of a single meaningful decide()-call's deliberation, kept only for the duration of
 * the call that produced it (transient — not persisted to [com.ripple.town.core.model.WorldState]
 * or checkpoints, same scoping as any other local computation in `DecisionSystem`). Exists so the
 * panic/impulse override step (see [DecisionSystem.applyPanicOverride]) has a small, explicit,
 * testable place to record *what was actually considered* and *whether the final pick deviated
 * from the top-scored option* — the same "small, bounded, causally explainable" shape as
 * `PersonalityDevelopmentSystem`'s applied-drift bookkeeping, just scoped to one decision instead
 * of a lifetime. The human-readable [overrideReason] (non-null only when [wasOverridden] is true)
 * is folded directly into [ScoredAction.reason]/`Resident.activityReason` at the point the action
 * is executed — see `docs/simulation-rules.md#panic-impulse-override`.
 */
data class DecisionDeliberation(
    val consideredTopActions: List<ScoredAction>,
    val topScored: ScoredAction,
    val chosen: ScoredAction,
    val wasOverridden: Boolean,
    val overrideReason: String? = null
)

object DecisionSystem {

    /** Bounded shock-period multipliers on `personalityFit` — see EconomySystem.isInShock and
     *  docs/simulation-rules.md "Shock period after major personal loss". Applied the same way
     *  every existing personality trait already scales personalityFit; never zeroes an action
     *  out, never overrides `chooseBest`'s ranking on its own. +15% for low-key
     *  actions (sleep, visiting/socialising, relaxing at home), -25% for effortful/ambitious
     *  ones (working on a goal, exercise) while a resident is in shock. */
    const val SHOCK_LOW_KEY_BOOST = 1.15
    const val SHOCK_EFFORTFUL_DAMPEN = 0.75

    /** Town-sentiment feedback (2026-07-11) — see `TownSentimentSystem`'s own doc comment and
     *  `docs/simulation-rules.md` "Town sentiment". Never below this floor, however fearful/
     *  unsafe the town's mood has gotten — same "never zeroes an action out" convention as the
     *  shock/emotion multipliers above. */
    const val TOWN_FEAR_SOCIAL_MULTIPLIER_FLOOR = 0.82

    /**
     * A small, bounded multiplier on `VISIT_FRIEND`/`SOCIALISE_PUBLIC`'s `personalityFit` —
     * composed multiplicatively alongside the shock/emotion multipliers already applied at
     * those two call sites, never replacing either. Reads `WorldState.townSentiment.fear` and
     * `.safety` (see [TownSentiment]): when the town's mood is genuinely more fearful/less safe
     * than the neutral baseline, evening-out-and-about actions read slightly less appealing —
     * residents venture out a little less when the town feels unsafe, without ever forbidding it
     * outright. At the neutral baseline (both dimensions at 50) this returns exactly `1.0`, so a
     * town that has never had `TownSentimentSystem.updateDaily` move its sentiment away from
     * default behaves identically to before this wiring existed.
     */
    private fun townFearSocialMultiplier(state: WorldState): Double {
        val sentiment = state.townSentiment
        val fearAboveBaseline = (sentiment.fear - TownSentimentSystem.BASELINE).coerceAtLeast(0.0)
        val unsafetyBelowBaseline = (TownSentimentSystem.BASELINE - sentiment.safety).coerceAtLeast(0.0)
        val concern = ((fearAboveBaseline + unsafetyBelowBaseline) / 2.0) / TownSentimentSystem.BASELINE // 0..1
        val multiplier = 1.0 - concern * (1.0 - TOWN_FEAR_SOCIAL_MULTIPLIER_FLOOR)
        return multiplier.coerceIn(TOWN_FEAR_SOCIAL_MULTIPLIER_FLOOR, 1.0)
    }

    /** Evaluate residents whose current activity has finished and choose their next action. */
    fun update(ctx: TickContext) {
        for (r in ctx.state.residentsOrdered()) {
            if (!r.inTown || r.detailLevel != DetailLevel.DETAILED) continue
            if (r.activity == Activity.TRAVELLING) continue
            if (ctx.now < r.activityEndsAt) continue
            decide(ctx, r)
        }
    }

    fun decide(ctx: TickContext, r: Resident) {
        // Under-5s are cared for — no autonomous decision-making.
        if (r.detailedLifeStageAt(ctx.now).needsCaregiver) {
            val home = r.homeBuildingId
            if (home != null) {
                ctx.sendTo(r, home, Activity.BEING_CARED_FOR, 4 * 60, "Being looked after at home")
            } else {
                ctx.beginActivity(r, Activity.BEING_CARED_FOR, 4 * 60, "Being looked after")
            }
            return
        }
        val actions = candidateActions(ctx.state, r, ctx.now)
        if (actions.isEmpty()) {
            ctx.beginActivity(r, Activity.IDLE, 30, "Nothing pressing")
            return
        }
        val best = chooseBest(actions, ctx.rng)
        val deliberation = applyPanicOverride(ctx, r, actions, best)
        execute(ctx, r, deliberation.chosen, deliberation.overrideReason)
    }

    /**
     * Deterministic choice: highest score wins; randomness only breaks near-ties
     * (within 5% of the best score) and never drives a major decision alone.
     */
    fun chooseBest(actions: List<ScoredAction>, rng: SimRandom): ScoredAction {
        val sorted = actions.sortedByDescending { it.score }
        val best = sorted.first()
        val nearTies = sorted.filter { it.score >= best.score * 0.95 && best.score > 0 }
        return if (nearTies.size > 1) nearTies[rng.nextInt(nearTies.size)] else best
    }

    // ------------------------------------------------------------------ panic/impulse override
    //
    // A rare, bounded, explainable deviation from the utility-optimal choice — see
    // docs/simulation-rules.md#panic-impulse-override for the full write-up and the Simulation
    // Reality Review finding this addresses ("residents cannot make a genuinely irrational,
    // out-of-character choice"). Deliberately implemented as a POST-PROCESSING step that runs
    // strictly after `candidateActions()`'s scoring and `chooseBest`'s normal ranking/tie-break —
    // it never touches the scoring formula, `ScoredAction.score`, or any of the per-action
    // multiplier terms (those belong to the shock/emotion-multiplier work earlier in this file).
    // In the large majority of ticks (>=85% for any resident, >=92%+ for a calm/disciplined one)
    // this step is a no-op: `topScored` is returned unchanged as `chosen`.

    /** Hard ceiling on the override roll — never exceeded regardless of stress/shock/emotion/
     *  personality inputs. Per the brief's stated 0-8% normal / up to 15% exceptional-crisis
     *  range. */
    const val PANIC_OVERRIDE_MAX_PROBABILITY = 0.15

    /**
     * Probability [0.0, PANIC_OVERRIDE_MAX_PROBABILITY] that this decision's actual chosen
     * action will be drawn from the second-ranked candidate instead of the top-scored one.
     *
     * Composed as a capped sum of independent bounded contributions — deliberately additive
     * (not multiplicative) so each factor's contribution is easy to reason about and tune in
     * isolation, matching the plain, auditable arithmetic `EmotionSystem.behaviourModifier` and
     * `PersonalityDevelopmentSystem`'s delta bands already use elsewhere in this codebase:
     *
     * Increases (raise the odds of an irrational/impulsive pick):
     *  - `stress` pressure: up to **+0.05** at stress=100, scaled linearly (stress/100 * 0.05).
     *    Stress is the single biggest normal-life driver of "not thinking straight".
     *  - Active shock (`EconomySystem.isInShock`): flat **+0.03** bump. A discrete, recent,
     *    identifiable personal loss — deserves a flat add, not a scaled one, same treatment
     *    `SHOCK_LOW_KEY_BOOST`/`SHOCK_EFFORTFUL_DAMPEN` give it elsewhere in this file.
     *  - Active high-arousal negative emotions (FEAR, ANGER, ANXIETY only — the "panic" flavours;
     *    GRIEF/LONELINESS/etc. are subdued, not impulsive, so they don't contribute here): each
     *    contributes up to **+0.02** scaled by its own `intensity / 100.0`, summed across however
     *    many of the three types are currently active (so a resident who is simultaneously
     *    afraid AND angry stacks both contributions — genuinely more volatile than either alone).
     *  - `impulsiveness` (0..1) raises the ceiling on everything above: the whole increasing sum
     *    is scaled by `(0.5 + impulsiveness)`, i.e. a maximally-impulsive resident (1.0) gets 1.5x
     *    the raw increases, a maximally-composed one (0.0) gets 0.5x — impulsiveness amplifies
     *    how much stress/shock/emotion actually translates into acting on it, rather than adding
     *    its own flat slice.
     *
     * Decreases (temper the odds — a disciplined, patient resident holds it together better):
     *  - `discipline` (0..1): subtracts up to **-0.04** at discipline=1.0.
     *  - `patience` (0..1): subtracts up to **-0.03** at patience=1.0.
     *
     * Final result is clamped to `[0.0, PANIC_OVERRIDE_MAX_PROBABILITY]` — the clamp is the only
     * thing enforcing the 15% ceiling; every term above is individually bounded but the sum could
     * otherwise exceed it in a genuine multi-factor crisis (high stress + shock + fear + anger +
     * high impulsiveness), which is precisely the "exceptional crisis" case the brief calls out.
     */
    fun panicOverrideProbability(state: WorldState, r: Resident, now: Long): Double {
        val p = r.effectivePersonality()
        val n = r.needs

        val stressTerm = (n.stress / 100.0).coerceIn(0.0, 1.0) * 0.05
        val shockTerm = if (EconomySystem.isInShock(state, r, now)) 0.03 else 0.0
        val emotionTerm = r.activeEmotions
            .filter { it.type == EmotionType.FEAR || it.type == EmotionType.ANGER || it.type == EmotionType.ANXIETY }
            .sumOf { (it.intensity / 100.0).coerceIn(0.0, 1.0) * 0.02 }

        val impulsivenessAmplifier = 0.5 + p.impulsiveness.coerceIn(0.0, 1.0) // 0.5x .. 1.5x
        val increase = (stressTerm + shockTerm + emotionTerm) * impulsivenessAmplifier

        val decrease = p.discipline.coerceIn(0.0, 1.0) * 0.04 + p.patience.coerceIn(0.0, 1.0) * 0.03

        return (increase - decrease).coerceIn(0.0, PANIC_OVERRIDE_MAX_PROBABILITY)
    }

    /**
     * Post-selection override step. Rolls [panicOverrideProbability]; if it fires AND there are
     * at least 2 candidate actions, the second-ranked (by score) real candidate is chosen instead
     * of the top-scored one — never an arbitrary/nonsensical action, always a genuine, already-
     * scored, plausible option that just wasn't the optimal one. Runs strictly after
     * [chooseBest]'s own ranking/near-tie logic, so it does not alter or interact with the 5%
     * near-tie tie-break: [best] is whatever `chooseBest` already decided (tie-break included);
     * this step only asks "do we deviate from that pick, to the next-best real alternative?".
     */
    fun applyPanicOverride(
        ctx: TickContext,
        r: Resident,
        actions: List<ScoredAction>,
        best: ScoredAction
    ): DecisionDeliberation {
        val sorted = actions.sortedByDescending { it.score }
        val top3 = sorted.take(3)
        val probability = panicOverrideProbability(ctx.state, r, ctx.now)
        val fires = ctx.rng.nextBoolean(probability)
        if (!fires || sorted.size < 2) {
            return DecisionDeliberation(top3, best, best, wasOverridden = false)
        }
        val second = sorted[1]
        val emotionNote = r.activeEmotions
            .filter { it.type == EmotionType.FEAR || it.type == EmotionType.ANGER || it.type == EmotionType.ANXIETY }
            .maxByOrNull { it.intensity }
        val stressNote = when {
            r.needs.stress >= 70.0 -> "stress: high"
            r.needs.stress >= 45.0 -> "stress: elevated"
            else -> null
        }
        val causeParts = listOfNotNull(
            stressNote,
            if (EconomySystem.isInShock(ctx.state, r, ctx.now)) "recent shock" else null,
            emotionNote?.let { "active ${it.type.label.lowercase()}" }
        )
        val causeText = if (causeParts.isEmpty()) "a sudden loss of composure" else causeParts.joinToString(", ")
        val overrideReason = "Went with ${describeAction(second)} instead of ${describeAction(best)} — " +
            "panic overwhelmed normal judgement ($causeText)."
        return DecisionDeliberation(top3, best, second, wasOverridden = true, overrideReason = overrideReason)
    }

    private fun describeAction(action: ScoredAction): String =
        action.activity.label.lowercase()

    fun candidateActions(state: WorldState, r: Resident, now: Long): List<ScoredAction> {
        val out = mutableListOf<ScoredAction>()
        val n = r.needs
        val p = r.personality
        val stage = r.lifeStageAt(now)
        val age = r.ageAt(now)
        val hour = SimTime.hourOfDay(now)
        val weekend = SimTime.dayOfWeek(now) >= 5
        val home = r.homeBuildingId
        val employment = state.employmentOf(r)
        val poor = r.wealth < 60.0
        // Shock period after sudden personal loss (job loss, business closure, bereavement —
        // see EconomySystem.scheduleShock/docs/simulation-rules.md). A small, bounded
        // personalityFit-style multiplier, applied the same way p.* traits already scale
        // personalityFit below: low-key actions (SLEEP, VISIT_FRIEND, SOCIALISE_PUBLIC,
        // RELAX_HOME) read slightly more appealing, effortful/ambitious ones (WORK_ON_GOAL,
        // EXERCISE) read slightly less. Never zeroes an action out or overrides the ranking on
        // its own — it nudges the same personalityFit term every other trait already leans on.
        val inShock = EconomySystem.isInShock(state, r, now)

        fun pressure(v: Double) = ((100.0 - v) / 100.0).coerceIn(0.0, 1.0)

        // --- Sleep
        val sleepy = pressure(n.energy)
        val night = hour >= 22 || hour < 6
        if (home != null && (sleepy > 0.55 || night)) {
            out += ScoredAction(
                ActionKind.SLEEP, home, null, Activity.SLEEPING,
                durationMinutes = if (night) (7 * 60L) else 90L,
                reason = if (night) "It's late and the day is done" else "Running on empty",
                needPressure = (sleepy + if (night) 0.7 else 0.0).coerceIn(0.05, 1.6),
                personalityFit = 1.0 * (if (inShock) SHOCK_LOW_KEY_BOOST else 1.0) *
                    EmotionSystem.behaviourModifier(r, EmotionSystem.ActionCategory.LOW_KEY), expectedReward = 1.2, confidence = 1.0,
                socialInfluence = 1.0, opportunity = 1.0,
                risk = 0.0, cost = 0.0, effort = 0.02, moralResistance = 0.0
            )
        }

        // --- Eat
        val hungry = pressure(n.hunger)
        if (hungry > 0.35) {
            if (home != null) {
                out += ScoredAction(
                    ActionKind.EAT_HOME, home, null, Activity.EATING, 40L,
                    "Hungry — there's food at home",
                    needPressure = hungry * 1.3, personalityFit = 1.0,
                    expectedReward = 1.0, confidence = 1.0, socialInfluence = 1.0,
                    opportunity = 1.0, risk = 0.0, cost = 0.02, effort = 0.05, moralResistance = 0.0
                )
            }
            val eatery = openEatery(state, hour)
            if (eatery != null && !poor && stage != LifeStage.CHILD) {
                out += ScoredAction(
                    ActionKind.EAT_OUT, state.businesses[eatery]!!.buildingId, null, Activity.EATING, 50L,
                    "Hungry — a bite at ${state.businesses[eatery]!!.name}",
                    needPressure = hungry * 1.15 + pressure(n.social) * 0.3,
                    personalityFit = 0.7 + p.sociability * 0.6,
                    expectedReward = 1.05, confidence = 1.0, socialInfluence = 1.0,
                    opportunity = 1.0, risk = 0.0,
                    cost = 0.12 + if (r.wealth < 200) 0.25 else 0.0,
                    effort = 0.1, moralResistance = 0.0
                )
            }
        }

        // --- Work (16+ only; designated caregivers with no nursery cover are blocked)
        val isSoleCaregiverOnDuty = CaregiverSystem.isDesignatedCaregiver(state, r) &&
            !CaregiverSystem.hasNursery(state)
        if (employment != null && !weekend && age >= 16 && !isSoleCaregiverOnDuty) {
            val biz = state.businesses[employment.businessId]
            val withinShift = hour >= employment.shiftStartHour && hour < employment.shiftEndHour
            if (biz != null && biz.open && withinShift && r.activity != Activity.WORKING) {
                val minutesLeft = (employment.shiftEndHour - hour).toLong() * 60L
                out += ScoredAction(
                    ActionKind.GO_TO_WORK, biz.buildingId, null, Activity.WORKING, minutesLeft,
                    "Shift at ${biz.name}",
                    needPressure = 0.85 + pressure(n.financialSecurity) * 0.5,
                    personalityFit = 0.75 + p.discipline * 0.5,
                    expectedReward = 1.15, confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.0, cost = 0.0,
                    effort = 0.1 + (pressure(n.energy) * 0.25) + (pressure(n.health) * 0.3),
                    moralResistance = 0.0
                )
            }
        }

        // --- School for children aged 5–17 on weekdays
        if (age in 5..17 && !weekend && hour in 8..14) {
            val school = state.buildings.values.firstOrNull { it.type == BuildingType.SCHOOL }
            if (school != null && r.activity != Activity.AT_SCHOOL) {
                out += ScoredAction(
                    ActionKind.GO_TO_SCHOOL, school.id, null, Activity.AT_SCHOOL,
                    ((15 - hour).coerceAtLeast(1)).toLong() * 60L,
                    "School day",
                    needPressure = 1.1, personalityFit = 0.8 + p.discipline * 0.3,
                    expectedReward = 1.0, confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.0, cost = 0.0, effort = 0.15, moralResistance = 0.0
                )
            }
        }

        // --- Rest / clinic when unwell
        val conditions = r.activeConditions()
        if (conditions.isNotEmpty()) {
            val worst = conditions.maxOf { it.severity }
            if (home != null) {
                out += ScoredAction(
                    ActionKind.REST_ILL, home, null, Activity.RESTING_ILL, 4 * 60L,
                    "Feeling unwell — best to rest",
                    needPressure = (worst / 100.0) * 1.4 + pressure(n.health) * 0.5,
                    personalityFit = 0.7 + p.patience * 0.4,
                    expectedReward = 1.0, confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.0, cost = 0.0, effort = 0.02,
                    moralResistance = if (state.employmentOf(r) != null) 0.15 else 0.0
                )
            }
            val clinic = state.buildings.values.firstOrNull { it.type == BuildingType.CLINIC }
            val knowsIll = conditions.any { !it.hidden }
            val warned = r.awareness.contains("health_risk")
            if (clinic != null && (knowsIll || warned) && hour in 8..17) {
                out += ScoredAction(
                    ActionKind.VISIT_CLINIC, clinic.id, null, Activity.AT_CLINIC, 90L,
                    if (warned && !knowsIll) "That warning has been playing on their mind"
                    else "This isn't shifting on its own — clinic time",
                    needPressure = (worst / 100.0) * 1.5 + (if (warned) 0.5 else 0.0),
                    personalityFit = 0.6 + p.discipline * 0.3 + (1.0 - p.courage) * 0.2,
                    expectedReward = 1.2, confidence = 0.9, socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.05, cost = 0.08, effort = 0.15,
                    moralResistance = 0.0
                )
            }
        }

        // --- Socialising
        val lonely = pressure(n.social)
        if (lonely > 0.3 && stage != LifeStage.CHILD) {
            val friend = bestFriendToVisit(state, r)
            if (friend != null) {
                val friendHome = friend.homeBuildingId
                if (friendHome != null) {
                    // Audit #47: childhood-influence modifier — a resident who lost a parent in
                    // childhood seeks connection a little more readily as an adult (PARENTAL_LOSS
                    // returns 1.1 if matched, 1.0 otherwise — bounded, never a hard block).
                    val visitFriendConfidence = 0.9 *
                        MemoryRecallSystem.childhoodInfluenceModifier(r, ChildhoodSituation.PARENTAL_LOSS)
                            .coerceIn(0.9, 1.1)
                    out += ScoredAction(
                        ActionKind.VISIT_FRIEND, friendHome, friend.id, Activity.VISITING, 90L,
                        "Calling on ${friend.firstName}",
                        needPressure = lonely * 1.2,
                        personalityFit = (0.5 + p.sociability * 0.8) * (if (inShock) SHOCK_LOW_KEY_BOOST else 1.0) *
                            EmotionSystem.behaviourModifier(r, EmotionSystem.ActionCategory.SOCIAL) *
                            townFearSocialMultiplier(state),
                        expectedReward = 0.8 + (state.relationship(r.id, friend.id)?.warmth() ?: 0.0) / 100.0,
                        confidence = visitFriendConfidence, socialInfluence = 1.1, opportunity = 1.0,
                        risk = 0.02, cost = 0.02, effort = 0.15, moralResistance = 0.0
                    )
                }
            }
            val spot = openPublicSpot(state, hour)
            if (spot != null) {
                out += ScoredAction(
                    ActionKind.SOCIALISE_PUBLIC, spot.id, null, Activity.SOCIALISING, 80L,
                    "Some company at ${spot.name}",
                    needPressure = lonely,
                    personalityFit = (0.4 + p.sociability * 0.9) * (if (inShock) SHOCK_LOW_KEY_BOOST else 1.0) *
                        EmotionSystem.behaviourModifier(r, EmotionSystem.ActionCategory.SOCIAL) *
                        townFearSocialMultiplier(state),
                    expectedReward = 0.9, confidence = 0.9, socialInfluence = 1.15, opportunity = 1.0,
                    risk = 0.02, cost = if (spot.type == BuildingType.PARK) 0.0 else 0.1,
                    effort = 0.12, moralResistance = 0.0
                )
            }
        }

        // --- Shopping
        if (n.comfort < 55 && !poor && hour in 9..17 && stage == LifeStage.ADULT) {
            val shop = state.businesses.values
                .filter { it.open && it.type in SHOP_TYPES }
                .minByOrNull { it.id }
            if (shop != null) {
                out += ScoredAction(
                    ActionKind.SHOP, shop.buildingId, null, Activity.SHOPPING, 45L,
                    "Errands at ${shop.name}",
                    needPressure = pressure(n.comfort) * 0.9 + pressure(n.hunger) * 0.2,
                    personalityFit = 0.8, expectedReward = 0.85, confidence = 1.0,
                    socialInfluence = 1.0, opportunity = 1.0,
                    risk = 0.0, cost = 0.15, effort = 0.1, moralResistance = 0.0
                )
            }
        }

        // --- Exercise
        val park = state.buildings.values.firstOrNull { it.type == BuildingType.PARK }
        if (park != null && n.energy > 40 && hour in 6..19 && stage != LifeStage.ELDER) {
            out += ScoredAction(
                ActionKind.EXERCISE, park.id, null, Activity.EXERCISING, 60L,
                "A run around the park",
                needPressure = 0.25 + (n.stress / 100.0) * 0.5,
                personalityFit = (0.3 + p.discipline * 0.5 + (r.skill(SkillType.FITNESS) / 200.0)) *
                    (if (inShock) SHOCK_EFFORTFUL_DAMPEN else 1.0) *
                    EmotionSystem.behaviourModifier(r, EmotionSystem.ActionCategory.EFFORTFUL),
                expectedReward = 0.8, confidence = 0.95, socialInfluence = 1.0, opportunity = 1.0,
                risk = 0.02, cost = 0.0, effort = 0.3, moralResistance = 0.0
            )
        }

        // --- Learning / goal work
        val activeGoal = r.goals.firstOrNull { it.status == GoalStatus.ACTIVE }
        if (activeGoal != null && home != null && hour in 8..21) {
            out += ScoredAction(
                ActionKind.WORK_ON_GOAL, goalVenue(state, r, activeGoal.type) ?: home, null,
                Activity.LEARNING, 80L,
                goalReason(activeGoal.type),
                needPressure = 0.4 + pressure(n.purpose) * 0.8,
                personalityFit = (0.4 + p.ambition * 0.7 + p.discipline * 0.2) *
                    (if (inShock) SHOCK_EFFORTFUL_DAMPEN else 1.0) *
                    EmotionSystem.behaviourModifier(r, EmotionSystem.ActionCategory.GOAL_PURSUING),
                expectedReward = 1.0 - activeGoal.risk * 0.3,
                confidence = 0.7 + activeGoal.progress * 0.3,
                socialInfluence = 1.0, opportunity = 1.0,
                risk = activeGoal.risk * 0.15, cost = 0.05, effort = 0.25, moralResistance = 0.0
            )
        }

        // --- Relax at home (always available fallback)
        if (home != null) {
            out += ScoredAction(
                ActionKind.RELAX_HOME, home, null, Activity.RELAXING, 60L,
                "A quiet hour at home",
                needPressure = 0.3 + (n.stress / 100.0) * 0.6,
                personalityFit = (0.6 + (1.0 - p.sociability) * 0.3) * (if (inShock) SHOCK_LOW_KEY_BOOST else 1.0) *
                    EmotionSystem.behaviourModifier(r, EmotionSystem.ActionCategory.LOW_KEY),
                expectedReward = 0.75, confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                risk = 0.0, cost = 0.0, effort = 0.02, moralResistance = 0.0
            )
        } else {
            val spot = openPublicSpot(state, hour)
                ?: state.buildings.values.firstOrNull { it.type == BuildingType.PARK }
                ?: return out
            out += ScoredAction(
                ActionKind.WANDER, spot.id, null, Activity.IDLE, 45L,
                "Passing the time",
                needPressure = 0.25, personalityFit = 1.0, expectedReward = 0.5,
                confidence = 1.0, socialInfluence = 1.0, opportunity = 1.0,
                risk = 0.0, cost = 0.0, effort = 0.05, moralResistance = 0.0
            )
        }

        // routineVariance: when NarrativePlausibilityEngine detects overly synchronised schedules
        // it raises this value. We apply a very small multiplicative jitter to each scored action's
        // needPressure term so that otherwise-identical residents make slightly different choices —
        // deliberately tiny (variance ≤ 0.05 max spread) so it never overrides meaningful
        // differences in need pressure or personality fit.
        val rv = state.narrativeTuning.routineVariance
        if (rv > 0.0) {
            // smallNoise: a stable [-1, 1] value derived from resident id and current day so the
            // jitter is reproducible across identical ticks (no external random call needed here —
            // id + day already gives sufficient spread across the resident population).
            val dayIndex = SimTime.dayIndex(now)
            val smallNoise = ((r.id * 2654435761L + dayIndex * 40503L) and 0xFFFFL).toDouble() / 0xFFFFL * 2.0 - 1.0
            val jitter = 1.0 + rv * smallNoise  // range: [1 - rv .. 1 + rv], rv ≤ 0.3 → at most ±0.3
            for (i in out.indices) {
                val a = out[i]
                out[i] = a.copy(needPressure = (a.needPressure * jitter).coerceAtLeast(0.01))
            }
        }

        return out
    }

    private fun goalVenue(state: WorldState, r: Resident, type: GoalType): Long? = when (type) {
        GoalType.START_BUSINESS -> state.buildings.values.firstOrNull { it.type == BuildingType.VACANT }?.id
        GoalType.FIND_JOB -> state.buildings.values.firstOrNull { it.type == BuildingType.TOWN_HALL }?.id
        GoalType.LEAVE_FOR_EDUCATION -> r.homeBuildingId
        GoalType.LEARN_SKILL -> state.buildings.values.firstOrNull { it.type == BuildingType.BOOKSHOP }?.id
        else -> r.homeBuildingId
    }

    private fun goalReason(type: GoalType): String = when (type) {
        GoalType.FIND_JOB -> "Asking around about work"
        GoalType.START_BUSINESS -> "Working on the business plan"
        GoalType.LEARN_SKILL -> "Practising and studying"
        GoalType.FIND_PARTNER -> "Making an effort to get out more"
        GoalType.LEAVE_FOR_EDUCATION -> "Reading course brochures again"
        GoalType.PAY_OFF_DEBT -> "Going over the sums"
        GoalType.GET_HEALTHY -> "Taking recovery seriously"
        GoalType.REPAIR_RELATIONSHIP -> "Thinking about how to make things right"
        GoalType.MOVE_HOME -> "Scanning the housing notices"
        GoalType.RUN_FOR_OFFICE -> "Drafting ideas for the town"
        GoalType.RETIRE_WELL -> "Putting affairs in order"
        GoalType.LEAVE_TOWN -> "Thinking about moving away"
    }

    private fun bestFriendToVisit(state: WorldState, r: Resident): Resident? {
        return state.relationshipsOf(r.id)
            .filter { it.warmth() > 25 }
            .sortedByDescending { it.warmth() }
            .asSequence()
            .mapNotNull { state.resident(it.other(r.id)) }
            .firstOrNull { it.inTown && it.detailLevel == DetailLevel.DETAILED && it.activity != Activity.SLEEPING && it.activity != Activity.WORKING }
    }

    private fun openEatery(state: WorldState, hour: Int): Long? {
        val types = if (hour >= 17) listOf(com.ripple.town.core.model.BusinessType.PUB, com.ripple.town.core.model.BusinessType.CAFE)
        else listOf(com.ripple.town.core.model.BusinessType.CAFE, com.ripple.town.core.model.BusinessType.BAKERY, com.ripple.town.core.model.BusinessType.PUB)
        return state.businesses.values
            .filter { it.open && it.type in types }
            .minByOrNull { it.id }?.id
    }

    private fun openPublicSpot(state: WorldState, hour: Int): com.ripple.town.core.model.Building? {
        val candidates = state.buildings.values
            .filter { it.type.isPublicSpace && !it.abandoned }
            .sortedBy { it.id }
        return when {
            hour in 17..22 -> candidates.firstOrNull { it.type == BuildingType.PUB } ?: candidates.firstOrNull()
            hour in 8..17 -> candidates.firstOrNull { it.type == BuildingType.CAFE }
                ?: candidates.firstOrNull { it.type == BuildingType.PARK }
            else -> candidates.firstOrNull { it.type == BuildingType.PARK }
        }
    }

    private fun execute(ctx: TickContext, r: Resident, action: ScoredAction, overrideReason: String? = null) {
        // Purchases happen up front so money flows are deterministic.
        when (action.kind) {
            ActionKind.EAT_OUT -> spend(ctx, r, action.targetBuildingId, 6.0)
            ActionKind.SHOP -> spend(ctx, r, action.targetBuildingId, 12.0)
            ActionKind.SOCIALISE_PUBLIC -> {
                val b = action.targetBuildingId?.let { ctx.state.building(it) }
                if (b != null && b.type != BuildingType.PARK) spend(ctx, r, action.targetBuildingId, 5.0)
            }
            ActionKind.LEARN_SKILL, ActionKind.WORK_ON_GOAL -> {
                // Practice slowly improves the most relevant skill.
                val goal = r.goals.firstOrNull { it.status == GoalStatus.ACTIVE }
                val skill = goal?.targetSkill ?: relevantSkill(goal?.type)
                if (skill != null) {
                    r.skills[skill] = (r.skill(skill) + 0.4).coerceAtMost(100.0)
                }
            }
            else -> {}
        }
        val reason = overrideReason ?: action.reason
        val target = action.targetBuildingId
        if (target != null) {
            ctx.sendTo(r, target, action.activity, action.durationMinutes, reason)
        } else {
            ctx.beginActivity(r, action.activity, action.durationMinutes, reason)
        }
    }

    fun relevantSkill(type: GoalType?): SkillType? = when (type) {
        GoalType.START_BUSINESS -> SkillType.BUSINESS
        GoalType.LEARN_SKILL -> SkillType.CREATIVITY
        GoalType.RUN_FOR_OFFICE -> SkillType.POLITICS
        GoalType.LEAVE_FOR_EDUCATION -> SkillType.TEACHING
        else -> null
    }

    private fun spend(ctx: TickContext, r: Resident, buildingId: Long?, amount: Double) {
        if (r.wealth < amount) return
        r.wealth -= amount
        val biz = ctx.state.businesses.values.firstOrNull { it.buildingId == buildingId && it.open }
        if (biz != null) {
            biz.customersToday += 1
            biz.revenueToday += amount
            biz.balance += amount
        }
    }

    private val SHOP_TYPES = setOf(
        com.ripple.town.core.model.BusinessType.GROCER,
        com.ripple.town.core.model.BusinessType.HARDWARE,
        com.ripple.town.core.model.BusinessType.BOOKSHOP,
        com.ripple.town.core.model.BusinessType.TAILOR,
        com.ripple.town.core.model.BusinessType.BAKERY
    )
}
