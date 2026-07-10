package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.IdeaLibrary
import com.ripple.town.core.model.IdeaTemplate
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.ResidentIdeaState
import com.ripple.town.core.model.SimTime

/**
 * Abstract, ownable ideas that spread, mutate and die through the social graph — distinct from
 * [RumourSystem] (which propagates facts about *specific events*) and from [Resident.ideaSeeds]
 * (a single narrow string hint [GoalSystem] consumes once for `START_BUSINESS` formation, left
 * completely untouched by this system). See `docs/simulation-rules.md` "Idea diffusion" and
 * [IdeaLibrary] for the fixed, hand-authored library.
 *
 * Deliberately reuses [InteractionSystem]'s existing co-located/sociable sampling shape rather
 * than a parallel social-graph walker: ideas only spread as a side effect of two residents who
 * are already interacting (see [update]'s per-building loop, mirroring
 * [InteractionSystem.update]'s `byBuilding`/`pairCount` pattern almost exactly).
 *
 * Four responsibilities, run in this order each call to [update]/[updateDaily]:
 * - **Spawn** ([maybeSpawnOrigin]) — a small daily chance an idea "originates" with one
 *   plausible resident, bounded to [MAX_TOWN_ACTIVE_IDEAS] concurrent town-wide idea origins.
 * - **Spread** ([maybeTransfer]) — piggybacks on [InteractionSystem]'s sampled pairs; an
 *   advocate can pass awareness/interest to a receptive listener.
 * - **Mutation** — a small chance a transferred copy starts life [ResidentIdeaState.distorted],
 *   with a lower belief starting point than a pure copy — lightweight, no text-mutation system.
 * - **Decay/death** ([updateDaily]) — unreinforced ideas fade and are pruned once negligible.
 */
object IdeaDiffusionSystem {

    /** Bounded like `activeEmotions`/`memories`: a resident only actively juggles a handful of ideas. */
    const val MAX_ACTIVE_IDEAS = 5

    /** At most this many distinct idea templates may be actively "originating" (held by anyone
     *  in town at all) at once — keeps the town's idea landscape sparse and legible rather than
     *  every template circulating simultaneously. */
    const val MAX_TOWN_ACTIVE_IDEAS = 2

    /** Daily chance, per eligible template, that it originates with a single plausible resident. */
    private const val ORIGIN_CHANCE = 0.03

    /** A speaker's advocacy must clear this before they'll pass an idea on at all. */
    private const val ADVOCACY_SHARE_THRESHOLD = 30.0

    /** [ResidentIdeaState.beliefStrength] a resident must cross to count as having "adopted" an idea. */
    const val ADOPTION_THRESHOLD = 65.0

    /** Below this, an idea is negligible and pruned from [Resident.activeIdeas]. */
    private const val NEGLIGIBLE_STRENGTH = 1.0

    /** Interest/belief/advocacy lost per in-world day without reinforcement. */
    private const val DAILY_DECAY = 2.5

    /** Chance a transferred copy starts life distorted, and how much lower its initial belief lands. */
    private const val MUTATION_CHANCE = 0.25
    private const val MUTATION_BELIEF_PENALTY = 0.6

    /**
     * Per-tick pass: rides along [InteractionSystem]'s own sociable/co-located sampling rather
     * than re-walking the social graph. Cheap and bounded the same way that system already is —
     * this only considers pairs already grouped by building, capped by the same kind of budget.
     */
    fun update(ctx: TickContext) {
        var budget = MAX_TRANSFERS_PER_TICK
        val byBuilding = ctx.state.detailedResidents()
            .filter { it.inTown && it.currentBuildingId != null && it.activity in SOCIABLE_ACTIVITIES }
            .groupBy { it.currentBuildingId!! }
            .toSortedMap()

        for ((_, present) in byBuilding) {
            if (budget <= 0) break
            if (present.size < 2) continue
            val sorted = present.sortedBy { it.id }
            val pairCount = minOf(2, sorted.size / 2, budget)
            repeat(pairCount) {
                val a = sorted[ctx.rng.nextInt(sorted.size)]
                val b = sorted[ctx.rng.nextInt(sorted.size)]
                if (a.id != b.id) {
                    if (maybeTransfer(ctx, a, b)) budget--
                }
            }
        }
    }

    /** Daily bounded pass: spawn new origins, then decay/prune every resident's held ideas. */
    fun updateDaily(ctx: TickContext) {
        maybeSpawnOrigin(ctx)
        for (r in ctx.state.residentsOrdered()) {
            if (!r.inTown || r.activeIdeas.isEmpty()) continue
            decayAndPrune(ctx, r)
        }
    }

    // --- Spawn ---------------------------------------------------------------------------

    private fun maybeSpawnOrigin(ctx: TickContext) {
        val state = ctx.state
        val activeTemplateIds = state.detailedResidents()
            .flatMap { it.activeIdeas }
            .map { it.templateId }
            .toSet()
        if (activeTemplateIds.size >= MAX_TOWN_ACTIVE_IDEAS) return

        val candidates = IdeaLibrary.ALL.filter { it.id !in activeTemplateIds }.sortedBy { it.id }
        val template = ctx.rng.pickOrNull(candidates) ?: return
        if (!ctx.rng.nextBoolean(ORIGIN_CHANCE)) return

        val adults = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .sortedBy { it.id }
        if (adults.isEmpty()) return

        // Weighted pick towards residents whose effective personality fits the template, without
        // ever hard-picking only the single best match — origin should feel plausible, not scripted.
        val originator = adults.maxByOrNull { traitAffinity(it, template) * (0.5 + ctx.rng.nextDouble() * 0.5) }
            ?: return
        if (traitAffinity(originator, template) < 0.35) return

        adopt(
            ctx, originator, template,
            awareness = ctx.rng.nextDouble(55.0, 80.0),
            interest = ctx.rng.nextDouble(40.0, 70.0),
            belief = ctx.rng.nextDouble(25.0, 45.0),
            advocacy = ctx.rng.nextDouble(20.0, 40.0),
            distorted = false
        )
        ctx.addMemory(
            originator, MemoryType.INSPIRATION,
            "The idea to ${template.label} took hold, unprompted.",
            intensity = 25.0
        )
    }

    // --- Spread --------------------------------------------------------------------------

    /**
     * Attempts to pass on whichever of [a]'s or [b]'s ideas is best-positioned to transfer to
     * the other — at most one idea, one direction, per call. Returns true if a transfer (or
     * reinforcement of an idea the listener already holds) happened, so [update] can spend its
     * bounded budget accordingly.
     */
    private fun maybeTransfer(ctx: TickContext, a: Resident, b: Resident): Boolean {
        val rel = ctx.state.relationship(a.id, b.id) ?: return false
        // Try both directions; whichever speaker actually has a shareable idea wins. Order by id
        // for determinism when both could plausibly speak.
        val (speaker, listener) = when {
            bestAdvocacy(a) >= ADVOCACY_SHARE_THRESHOLD && bestAdvocacy(a) >= bestAdvocacy(b) -> a to b
            bestAdvocacy(b) >= ADVOCACY_SHARE_THRESHOLD -> b to a
            else -> return false
        }
        val idea = speaker.activeIdeas
            .filter { it.advocacyStrength >= ADVOCACY_SHARE_THRESHOLD }
            .maxByOrNull { it.advocacyStrength }
            ?: return false
        val template = IdeaLibrary.byId(idea.templateId) ?: return false
        if (listener.lifeStageAt(ctx.now) != LifeStage.ADULT) return false

        val existing = listener.activeIdeas.firstOrNull { it.templateId == idea.templateId }
        val chance = transferChance(ctx, speaker, listener, idea, template, rel.trust, rel.warmth())
        if (!ctx.rng.nextBoolean(chance)) return false

        if (existing != null) {
            // Already aware: reinforcement, not a fresh mutation-eligible copy.
            existing.awareness = (existing.awareness + 10.0).coerceAtMost(100.0)
            existing.interest = (existing.interest + 6.0).coerceAtMost(100.0)
            existing.lastReinforcedAt = ctx.now
            checkAdoption(ctx, listener, existing, template)
            return true
        }

        val distorted = ctx.rng.nextBoolean(MUTATION_CHANCE)
        val beliefStart = (idea.beliefStrength * 0.5 * (if (distorted) MUTATION_BELIEF_PENALTY else 1.0))
            .coerceIn(5.0, 60.0)
        adopt(
            ctx, listener, template,
            awareness = ctx.rng.nextDouble(30.0, 55.0),
            interest = ctx.rng.nextDouble(20.0, 45.0),
            belief = beliefStart,
            advocacy = beliefStart * 0.4,
            distorted = distorted
        )
        return true
    }

    private fun bestAdvocacy(r: Resident): Double = r.activeIdeas.maxOfOrNull { it.advocacyStrength } ?: 0.0

    /**
     * Bounded 0..[MAX_TRANSFER_CHANCE] roll: scaled by the listener's trait-affinity match, the
     * speaker's advocacy, relationship trust/warmth, and dampened by the idea's own complexity
     * (harder ideas spread slower, per [IdeaTemplate.complexity]).
     */
    private fun transferChance(
        ctx: TickContext, speaker: Resident, listener: Resident, idea: ResidentIdeaState,
        template: IdeaTemplate, trust: Double, warmth: Double
    ): Double {
        val affinity = traitAffinity(listener, template)
        val advocacyTerm = (idea.advocacyStrength / 100.0).coerceIn(0.0, 1.0)
        val relTerm = ((trust.coerceIn(0.0, 100.0) / 100.0) * 0.5 + (warmth.coerceIn(-50.0, 100.0) / 100.0) * 0.5)
            .coerceIn(0.0, 1.0)
        val raw = affinity * 0.35 + advocacyTerm * 0.35 + relTerm * 0.3
        val complexityDamped = raw * (1.0 - template.complexity * 0.5)
        return complexityDamped.coerceIn(0.0, MAX_TRANSFER_CHANCE)
    }

    /** Average of [effectivePersonality]'s named traits in [IdeaTemplate.baseAppealTraits],
     *  0..1. Matched by field name (not a typed enum) to avoid a second trait taxonomy. */
    fun traitAffinity(resident: Resident, template: IdeaTemplate): Double {
        val p = resident.effectivePersonality()
        if (template.baseAppealTraits.isEmpty()) return 0.5
        val values = template.baseAppealTraits.mapNotNull { traitValue(p, it) }
        if (values.isEmpty()) return 0.5
        return values.average().coerceIn(0.0, 1.0)
    }

    private fun traitValue(p: Personality, name: String): Double? = when (name) {
        "kindness" -> p.kindness
        "ambition" -> p.ambition
        "curiosity" -> p.curiosity
        "sociability" -> p.sociability
        "patience" -> p.patience
        "honesty" -> p.honesty
        "courage" -> p.courage
        "discipline" -> p.discipline
        "empathy" -> p.empathy
        "impulsiveness" -> p.impulsiveness
        else -> null
    }

    private fun adopt(
        ctx: TickContext, resident: Resident, template: IdeaTemplate,
        awareness: Double, interest: Double, belief: Double, advocacy: Double, distorted: Boolean
    ) {
        val existing = resident.activeIdeas.firstOrNull { it.templateId == template.id }
        if (existing != null) {
            existing.awareness = existing.awareness.coerceAtLeast(awareness)
            existing.interest = existing.interest.coerceAtLeast(interest)
            existing.lastReinforcedAt = ctx.now
            checkAdoption(ctx, resident, existing, template)
            return
        }
        val state = ResidentIdeaState(
            templateId = template.id,
            awareness = awareness.coerceIn(0.0, 100.0),
            interest = interest.coerceIn(0.0, 100.0),
            beliefStrength = belief.coerceIn(0.0, 100.0),
            advocacyStrength = advocacy.coerceIn(0.0, 100.0),
            firstHeardAt = ctx.now,
            lastReinforcedAt = ctx.now,
            distorted = distorted
        )
        resident.activeIdeas += state
        // Bounded like activeEmotions: evict the weakest-believed once over the cap.
        if (resident.activeIdeas.size > MAX_ACTIVE_IDEAS) {
            val weakest = resident.activeIdeas.minByOrNull { it.beliefStrength }
            if (weakest != null) resident.activeIdeas.remove(weakest)
        }
        checkAdoption(ctx, resident, state, template)
    }

    /**
     * Fires [EventType.IDEA_ADOPTED] once [ResidentIdeaState.beliefStrength] crosses
     * [ADOPTION_THRESHOLD], and — only for [IdeaLibrary.NEW_BUSINESS_CONCEPT] — feeds a clean,
     * small `ideaSeeds` entry as a downstream effect, reusing exactly the same
     * `ideaSeeds` -> `GoalSystem.START_BUSINESS` plumbing [InteractionSystem.maybeSeedOpportunity]
     * already uses. Never fires twice for the same idea (guarded by a payload marker check).
     */
    private const val ADOPTED_MARKER_PREFIX = "idea_adopted:"

    private fun checkAdoption(ctx: TickContext, resident: Resident, idea: ResidentIdeaState, template: IdeaTemplate) {
        if (idea.beliefStrength < ADOPTION_THRESHOLD) return
        val marker = ADOPTED_MARKER_PREFIX + template.id
        if (marker in resident.awareness) return
        resident.awareness += marker

        ctx.emit(
            EventType.IDEA_ADOPTED,
            "${resident.fullName} has genuinely come round to the idea: ${template.label}.",
            sourceResidentId = resident.id,
            severity = 0.15,
            visibility = EventVisibility.PRIVATE
        )

        if (template.id == IdeaLibrary.NEW_BUSINESS_CONCEPT.id && resident.ideaSeeds.size < MAX_IDEA_SEEDS_FROM_IDEAS) {
            resident.ideaSeeds += "idea_diffusion:${template.id}"
        }
    }

    // --- Decay/death -----------------------------------------------------------------------

    private fun decayAndPrune(ctx: TickContext, resident: Resident) {
        val it = resident.activeIdeas.iterator()
        while (it.hasNext()) {
            val idea = it.next()
            val idleDays = SimTime.dayIndex(ctx.now) - SimTime.dayIndex(idea.lastReinforcedAt)
            if (idleDays <= 0) continue // reinforced today; no decay yet
            idea.interest = (idea.interest - DAILY_DECAY).coerceAtLeast(0.0)
            idea.advocacyStrength = (idea.advocacyStrength - DAILY_DECAY).coerceAtLeast(0.0)
            idea.beliefStrength = (idea.beliefStrength - DAILY_DECAY * 0.6).coerceAtLeast(0.0)
            idea.awareness = (idea.awareness - DAILY_DECAY * 0.3).coerceAtLeast(0.0)
            if (idea.interest <= NEGLIGIBLE_STRENGTH && idea.beliefStrength <= NEGLIGIBLE_STRENGTH) {
                it.remove()
            }
        }
    }

    private const val MAX_TRANSFERS_PER_TICK = 4
    private const val MAX_TRANSFER_CHANCE = 0.35
    private const val MAX_IDEA_SEEDS_FROM_IDEAS = 3

    private val SOCIABLE_ACTIVITIES = setOf(
        Activity.SOCIALISING, Activity.VISITING, Activity.EATING, Activity.SHOPPING,
        Activity.COMMUNITY, Activity.WORKING, Activity.RELAXING, Activity.IDLE, Activity.CELEBRATING
    )
}
