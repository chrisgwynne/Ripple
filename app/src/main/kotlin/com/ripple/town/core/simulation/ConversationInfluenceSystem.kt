package com.ripple.town.core.simulation

import com.ripple.town.core.model.Belief
import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.EmotionType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Relationship
import com.ripple.town.core.model.Resident

/**
 * The mechanical, belief-side consequence of a conversation [InteractionSystem] has already
 * sampled — see that file's `interact()`, which calls [maybeInfluence] once per sampled pair
 * alongside its existing pleasant-exchange logic, right after `topicFor` picks what the chat was
 * about. Not a parallel sampling pass: this rides the exact pair [InteractionSystem.update]
 * already grouped by building and budgeted to `MAX_INTERACTIONS_PER_TICK`, the same way
 * `IdeaDiffusionSystem` rides it for idea spread.
 *
 * Deliberately narrow scope alongside the two systems it composes with: this is the *belief*
 * side of "a conversation can change what you think" — [IdeaDiffusionSystem] already owns
 * transferring [com.ripple.town.core.model.ResidentIdeaState] (abstract, ownable ideas); this
 * system never duplicates that transfer logic, it only reads `advocacyStrength` as one of two
 * ways a speaker can be "worth listening to" (see [hasSomethingWorthSaying]).
 *
 * Bounded to [MAX_MEANINGFUL_PER_TICK] mechanically-effectful conversations per tick — well under
 * `InteractionSystem.MAX_INTERACTIONS_PER_TICK` (8) — so most sampled interactions stay flavour-
 * only, matching the brief's "every MEANINGFUL conversation", not every conversation.
 *
 * ### Speaker/listener selection
 * The **speaker** is whichever of the pair actually has something worth saying (a confident
 * belief, or a strongly-advocated idea) — see [hasSomethingWorthSaying]. If both do, the more
 * sociable+confident one speaks (`effectivePersonality().sociability + curiosity`, a stable proxy
 * for "the one more inclined to hold forth"), with resident id as a final deterministic tiebreak.
 * The other resident is always the listener. If neither has anything worth saying, there is
 * nothing to influence and the call is a cheap no-op.
 *
 * ### Gating (all three must hold before any roll happens)
 * 1. **Relationship threshold** — `rel.trust >= TRUST_THRESHOLD` (a stranger's opinion shouldn't
 *    move you) OR `rel.respect >= RESPECT_THRESHOLD` (you can respect someone's view without
 *    fully trusting them yet) — either is sufficient, matching `InteractionSystem`'s own
 *    `rel.warmth()`-style "any sufficiently strong dimension counts" convention.
 * 2. **Speaker has something worth saying** — see [hasSomethingWorthSaying]: a settled belief
 *    (`confidence >= SPEAKER_BELIEF_CONFIDENCE_BAR`) on some topic, or a strongly-advocated idea
 *    (`advocacyStrength >= SPEAKER_ADVOCACY_BAR`, reusing `IdeaDiffusionSystem`'s own field,
 *    never its transfer logic) whose [com.ripple.town.core.model.IdeaTemplate.baseAppealTraits]
 *    maps to a belief-relevant topic.
 * 3. **Listener is open** — low existing confidence on the same topic
 *    (`< LISTENER_CONFIDENCE_BAR`) OR a personality profile that skews receptive
 *    (`effectivePersonality().curiosity - discipline >= LISTENER_OPENNESS_BAR`) — either
 *    qualifies, matching the brief's "listener openness" as an either/or gate rather than a
 *    strict AND.
 *
 * ### Effect when gated conditions are met
 * A small bounded roll (`ctx.rng`, [INFLUENCE_CHANCE]) decides whether the conversation actually
 * lands. On success:
 * - The listener's belief on the speaker's topic nudges toward the speaker's `position` by
 *   [POSITION_SHIFT_MIN]..[POSITION_SHIFT_MAX] (mirrors `BeliefSystem.applyDrift`'s exact
 *   clamp-to-`[-1,1]` shape, scaled the same "harder to move a confident listener" way) and gets
 *   a small confidence bump ([LISTENER_CONFIDENCE_BUMP]) — they've now heard a real person argue
 *   for something, even if not fully convinced.
 * - **Trust/relationship nudge** — being persuaded, or persuading someone, deepens a relationship
 *   a little: `rel.trust`/`rel.respect` both tick up by [PERSUASION_RELATIONSHIP_BUMP] on the
 *   existing [Relationship] dimensions `InteractionSystem` already reads/writes elsewhere.
 * - **Emotional effect** — a MONEY/WORK-topic conversation where the speaker's position is
 *   markedly *more pessimistic* than the listener's spawns a small `ANXIETY` in the listener
 *   (a discouraging word about money/work lands as worry); a markedly more *optimistic* one on
 *   the same topics spawns `HOPE` (reusing `EmotionSystem.spawnEmotion`, never a parallel
 *   emotion mechanic).
 * - A low-severity `PRIVATE` [EventType.BELIEF_SHIFTED] is emitted with `causeIds` back to
 *   nothing new (there is no prior event to point to — this *is* the origin, same as
 *   `BeliefSystem`'s own parent-inheritance emission) and a `payload` marking the source as a
 *   conversation, so the timeline stays legible without inventing a second event type (verified
 *   safe against `ImportanceScorer`'s `else -> 8.0` and `NewspaperGenerator`'s `else ->`
 *   fallbacks, same as `BeliefSystem`'s own `BELIEF_SHIFTED` emission).
 *
 * Goal influence (the third "at least 2-3 effects" item from the brief) is deliberately **not**
 * duplicated here: `InteractionSystem.maybeSeedOpportunity` already covers "a warm, on-topic
 * conversation nudges `ideaSeeds`" for WORK/MONEY/GOSSIP topics, gated on `rel.warmth() > 40` and
 * the sharer's kindness+sociability — functionally the same shape this system would add, so this
 * system extends it only by confirmation (see `docs/simulation-rules.md` "Conversation
 * influence"), not a second parallel seed path.
 */
object ConversationInfluenceSystem {

    /** Hard cap on mechanically-effectful conversations processed per tick — well under
     *  `InteractionSystem.MAX_INTERACTIONS_PER_TICK` (8), so most sampled chats stay flavour-only. */
    const val MAX_MEANINGFUL_PER_TICK = 3

    // ---- Gating thresholds -----------------------------------------------------------------

    const val TRUST_THRESHOLD = 55.0
    const val RESPECT_THRESHOLD = 60.0

    const val SPEAKER_BELIEF_CONFIDENCE_BAR = 0.55
    const val SPEAKER_ADVOCACY_BAR = 60.0

    const val LISTENER_CONFIDENCE_BAR = 0.35
    const val LISTENER_OPENNESS_BAR = 0.15

    // ---- Effect magnitudes -------------------------------------------------------------------

    /** Bounded chance, once every gate above is cleared, that the conversation actually lands. */
    const val INFLUENCE_CHANCE = 0.25

    /** Small bounded belief-position nudge toward the speaker, mirroring `BeliefSystem
     *  .DRIFT_MIN..DRIFT_MAX`'s own band before confidence-resistance scaling. */
    const val POSITION_SHIFT_MIN = 0.03
    const val POSITION_SHIFT_MAX = 0.08

    /** How much a confident listener resists movement — identical shape to
     *  `BeliefSystem.CONFIDENCE_RESISTANCE_FLOOR`, kept as this system's own constant since the
     *  two systems are allowed to tune independently even though they currently agree. */
    const val LISTENER_RESISTANCE_FLOOR = 0.35

    const val LISTENER_CONFIDENCE_BUMP = 0.04

    /** Both `trust` and `respect` tick up by this much on a landed influence — small next to
     *  `InteractionSystem.interact`'s own per-conversation `rel.trust += 0.8 + compatibility`. */
    const val PERSUASION_RELATIONSHIP_BUMP = 1.5

    /** How far apart speaker/listener positions on a MONEY/WORK topic must be before the
     *  discouraging/encouraging emotional effect fires. */
    const val EMOTIONAL_EFFECT_GAP = 0.3
    const val EMOTIONAL_EFFECT_INTENSITY = 18.0

    private val CONVERSATION_TOPICS_FOR_MONEY_WORK =
        setOf(ConversationTopic.MONEY, ConversationTopic.WORK)

    /** Maps a [ConversationTopic] to the [BeliefTopic] it's actually relevant to influencing —
     *  most conversation topics (weather, hobbies...) have no belief-relevant counterpart at all,
     *  which is itself part of the "not every conversation" gate: those topics simply never reach
     *  a speaker/listener match in [relevantBeliefTopic]. */
    private fun relevantBeliefTopic(topic: ConversationTopic): BeliefTopic? = when (topic) {
        ConversationTopic.MONEY -> BeliefTopic.ECONOMIC_OPTIMISM
        ConversationTopic.WORK -> BeliefTopic.ECONOMIC_OPTIMISM
        ConversationTopic.LOCAL_NEWS -> BeliefTopic.TRUST_IN_GOVERNMENT
        ConversationTopic.GOSSIP -> BeliefTopic.COMMUNITY_LOYALTY
        ConversationTopic.HEALTH -> BeliefTopic.INSTITUTIONAL_TRUST
        ConversationTopic.RELATIONSHIP -> BeliefTopic.SOCIAL_OPENNESS
        ConversationTopic.WEATHER, ConversationTopic.FAMILY, ConversationTopic.HOBBIES -> null
    }

    /**
     * Called once per sampled pair from `InteractionSystem.interact`, after the pleasant-exchange
     * branch already picked [topic] via `topicFor`. Bounded to [MAX_MEANINGFUL_PER_TICK] per tick
     * via [TickContext.conversationInfluenceBudget] (reset to [MAX_MEANINGFUL_PER_TICK] on every
     * fresh [TickContext], mirroring [TickContext.consequenceBudget]'s own shape) — the very
     * first check in this function, before any gating work happens.
     *
     * Returns true if a mechanical influence actually landed (and the budget was spent), false if
     * the budget was already exhausted, gating failed, or the roll missed.
     */
    fun maybeInfluence(ctx: TickContext, a: Resident, b: Resident, topic: ConversationTopic, rel: Relationship): Boolean {
        if (ctx.conversationInfluenceBudget <= 0) return false
        val beliefTopic = relevantBeliefTopic(topic) ?: return false
        if (a.lifeStageAt(ctx.now) != LifeStage.ADULT && a.lifeStageAt(ctx.now) != LifeStage.TEEN) return false
        if (b.lifeStageAt(ctx.now) != LifeStage.ADULT && b.lifeStageAt(ctx.now) != LifeStage.TEEN) return false

        // Gate 1: relationship threshold — either dimension is sufficient.
        if (rel.trust < TRUST_THRESHOLD && rel.respect < RESPECT_THRESHOLD) return false

        // Speaker/listener selection: whichever has something worth saying speaks; if both do,
        // the more sociable+curious one wins, id as a stable final tiebreak.
        val aCanSpeak = hasSomethingWorthSaying(a, beliefTopic)
        val bCanSpeak = hasSomethingWorthSaying(b, beliefTopic)
        if (!aCanSpeak && !bCanSpeak) return false
        val (speaker, listener) = when {
            aCanSpeak && !bCanSpeak -> a to b
            bCanSpeak && !aCanSpeak -> b to a
            else -> {
                val aScore = a.effectivePersonality().let { it.sociability + it.curiosity }
                val bScore = b.effectivePersonality().let { it.sociability + it.curiosity }
                when {
                    aScore > bScore -> a to b
                    bScore > aScore -> b to a
                    else -> if (a.id < b.id) a to b else b to a
                }
            }
        }

        // Gate 3: listener openness — low confidence on the topic, or a receptive personality.
        val listenerConfidence = BeliefSystem.confidenceOn(listener, beliefTopic)
        val listenerPersonality = listener.effectivePersonality()
        val open = listenerConfidence < LISTENER_CONFIDENCE_BAR ||
            (listenerPersonality.curiosity - listenerPersonality.discipline) >= LISTENER_OPENNESS_BAR
        if (!open) return false

        if (!ctx.rng.nextBoolean(INFLUENCE_CHANCE)) return false

        val speakerPosition = speakerPositionOn(speaker, beliefTopic)
        applyBeliefShift(ctx, listener, beliefTopic, speakerPosition, speaker)
        applyPersuasionRelationshipBump(rel)
        maybeSpawnEmotionalEffect(ctx, topic, speaker, listener, speakerPosition)
        ctx.conversationInfluenceBudget--
        return true
    }

    /**
     * A speaker has something worth saying if they hold a confident [Belief] on [beliefTopic]
     * directly, OR hold a strongly-advocated [com.ripple.town.core.model.ResidentIdeaState] whose
     * template's tone maps onto the same topic's direction — the belief-side complement to
     * `IdeaDiffusionSystem`'s own advocacy-gated transfer, read-only here (never mutated).
     */
    private fun hasSomethingWorthSaying(resident: Resident, beliefTopic: BeliefTopic): Boolean {
        val belief = resident.beliefs[beliefTopic]
        if (belief != null && belief.confidence >= SPEAKER_BELIEF_CONFIDENCE_BAR) return true
        return resident.activeIdeas.any { it.advocacyStrength >= SPEAKER_ADVOCACY_BAR }
    }

    /** The position the speaker is effectively arguing from: their own belief on [beliefTopic] if
     *  they hold one, else a mild signed lean derived from their strongest idea's tone (positive
     *  ideas read as a mild optimistic lean, negative as pessimistic) — never fabricates a strong
     *  opinion out of nothing. */
    private fun speakerPositionOn(speaker: Resident, beliefTopic: BeliefTopic): Double {
        val belief = speaker.beliefs[beliefTopic]
        if (belief != null && belief.confidence >= SPEAKER_BELIEF_CONFIDENCE_BAR) return belief.position
        val strongestIdea = speaker.activeIdeas.filter { it.advocacyStrength >= SPEAKER_ADVOCACY_BAR }
            .maxByOrNull { it.advocacyStrength }
        val template = strongestIdea?.let { com.ripple.town.core.model.IdeaLibrary.byId(it.templateId) }
        return when (template?.tone) {
            com.ripple.town.core.model.IdeaTone.POSITIVE -> 0.4
            com.ripple.town.core.model.IdeaTone.NEGATIVE -> -0.4
            else -> 0.0
        }
    }

    /** Mirrors `BeliefSystem.applyDrift`'s exact clamp shape: the raw shift is scaled down by the
     *  listener's existing confidence (a firmer listener resists movement, down to
     *  [LISTENER_RESISTANCE_FLOOR] of the raw shift), applied toward — not onto — the speaker's
     *  position, then clamped to `[-1, 1]`. Confidence itself ticks up a small, fixed amount:
     *  they've now heard a real argument, whether or not they're convinced. */
    private fun applyBeliefShift(
        ctx: TickContext, listener: Resident, topic: BeliefTopic, speakerPosition: Double, speaker: Resident
    ) {
        val belief = listener.beliefs.getOrPut(topic) {
            Belief(topic = topic, position = 0.0, confidence = 0.0, lastUpdatedAt = ctx.now)
        }
        val resistance = LISTENER_RESISTANCE_FLOOR + (1.0 - LISTENER_RESISTANCE_FLOOR) * (1.0 - belief.confidence)
        val direction = if (speakerPosition >= belief.position) 1.0 else -1.0
        val rawShift = ctx.rng.nextDouble(POSITION_SHIFT_MIN, POSITION_SHIFT_MAX) * direction
        val before = belief.position
        // Never overshoot past the speaker's own position.
        val target = (before + rawShift * resistance).coerceIn(-1.0, 1.0)
        belief.position = if (direction > 0) minOf(target, speakerPosition) else maxOf(target, speakerPosition)
        belief.confidence = (belief.confidence + LISTENER_CONFIDENCE_BUMP).coerceIn(0.0, 1.0)
        belief.lastUpdatedAt = ctx.now
        val applied = belief.position - before
        if (kotlin.math.abs(applied) < 1e-6) return

        ctx.emit(
            EventType.BELIEF_SHIFTED,
            "${listener.fullName}: a conversation with ${speaker.firstName} gave them something to think about.",
            sourceResidentId = listener.id,
            targetResidentIds = listOf(speaker.id),
            severity = 0.1,
            visibility = EventVisibility.PRIVATE,
            payload = mapOf(
                "topic" to topic.name,
                "delta" to "%.4f".format(applied),
                "reason" to "conversation"
            )
        )
        ctx.addMemory(
            listener, MemoryType.INSPIRATION,
            "${speaker.firstName} made a real case about ${topic.label.lowercase()}.",
            intensity = 20.0, associated = listOf(speaker.id)
        )
    }

    /** Small, bounded relationship nudge on the existing [Relationship] dimensions
     *  `InteractionSystem` already reads/writes: being genuinely persuaded, or persuading
     *  someone, deepens a relationship a little. */
    private fun applyPersuasionRelationshipBump(rel: Relationship) {
        rel.trust += PERSUASION_RELATIONSHIP_BUMP
        rel.respect += PERSUASION_RELATIONSHIP_BUMP
        rel.clampAll()
    }

    /** A MONEY/WORK conversation where the speaker's position reads markedly more pessimistic
     *  than the listener's spawns a small `ANXIETY` in the listener; markedly more optimistic
     *  spawns `HOPE`. Reuses `EmotionSystem.spawnEmotion` verbatim — no parallel mechanic. */
    private fun maybeSpawnEmotionalEffect(
        ctx: TickContext, topic: ConversationTopic, speaker: Resident, listener: Resident, speakerPosition: Double
    ) {
        if (topic !in CONVERSATION_TOPICS_FOR_MONEY_WORK) return
        val listenerPosition = BeliefSystem.positionOn(listener, BeliefTopic.ECONOMIC_OPTIMISM)
        val gap = speakerPosition - listenerPosition
        when {
            gap <= -EMOTIONAL_EFFECT_GAP -> EmotionSystem.spawnEmotion(
                ctx, listener, EmotionType.ANXIETY, EMOTIONAL_EFFECT_INTENSITY, relatedResidentId = speaker.id
            )
            gap >= EMOTIONAL_EFFECT_GAP -> EmotionSystem.spawnEmotion(
                ctx, listener, EmotionType.HOPE, EMOTIONAL_EFFECT_INTENSITY, relatedResidentId = speaker.id
            )
        }
    }
}
