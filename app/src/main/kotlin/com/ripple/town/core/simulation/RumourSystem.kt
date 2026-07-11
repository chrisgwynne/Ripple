package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldEvent

/**
 * Private events can leak: gossip travels along high-familiarity relationship
 * edges, sometimes accurately, sometimes not. A leak becomes its own new
 * PUBLIC event, so it can be picked up by [NewspaperGenerator] like anything
 * else — the town's public understanding is a query over rumour events, not
 * over the private facts themselves. Distorted rumours never carry
 * [WorldEvent.causeIds] back to the true event, so the cause viewer (reading
 * only known history) never shows a false lineage for something that never
 * really happened that way.
 *
 * Knowledge-gated (2026-07-10): a resident directly involved in an event
 * (source/target) already knows it the moment it happens — [markInvolvedAsKnowing]
 * records that on [Resident.knownFacts]. A leak only actually "tells" the
 * bystanders who don't already know; if everyone with a high-familiarity edge to
 * those involved already knows, there is nobody left to surprise and the leak is
 * skipped. This is what stops the same rumour from theoretically "breaking the
 * news" a second time to someone who was there for the original event.
 *
 * Bounded: at most [MAX_RUMOURS_PER_TICK] leaks per tick.
 */
object RumourSystem {

    const val MAX_RUMOURS_PER_TICK = 2

    /** Only sufficiently newsworthy private events are worth gossiping about. */
    private val GOSSIP_WORTHY = setOf(
        EventType.ARGUMENT, EventType.AFFAIR_DISCOVERED, EventType.BREAK_UP,
        EventType.SEPARATION, EventType.RELATIONSHIP_STARTED, EventType.ENGAGEMENT,
        EventType.RIVALRY_FORMED, EventType.SECRET_REVEALED, EventType.HOURS_REDUCED,
        EventType.JOB_QUIT, EventType.FRIENDSHIP_ENDED
    )

    /**
     * HIDDEN events that are genuinely "secrets getting out" candidates. Deliberately a
     * short, curated list (not every HIDDEN event — e.g. a hidden health condition a
     * resident hasn't even self-diagnosed yet has no gossip channel) and leaked at a much
     * lower rate than PRIVATE events via [HIDDEN_LEAK_CHANCE_FACTOR], since these are
     * meant to stay secret far longer than an argument stays quiet.
     */
    private val HIDDEN_GOSSIP_WORTHY = setOf(EventType.AFFAIR_BEGAN)

    /** HIDDEN secrets leak far more rarely than PRIVATE gossip — this scales [shouldLeak]'s
     *  usual chance down heavily so an affair beginning doesn't routinely out itself before
     *  [InteractionSystem.progressAffair]'s own discovery mechanic ever gets a say. */
    private const val HIDDEN_LEAK_CHANCE_FACTOR = 0.15

    fun update(ctx: TickContext) {
        // Anyone directly involved in a new event already knows it happened — this has to
        // run for *every* new event (not just gossip-worthy ones), since it's what lets the
        // leak-eligibility check below tell "already knew" apart from "just heard".
        for (event in ctx.newEvents) {
            markInvolvedAsKnowing(ctx, event)
        }

        var budget = MAX_RUMOURS_PER_TICK
        // ctx.newEvents grows as this loop runs (leaks append to it); snapshot first
        // so a leak is never itself scanned as a leak candidate in the same pass.
        val candidates = ctx.newEvents
            .filter {
                (it.visibility == EventVisibility.PRIVATE && it.type in GOSSIP_WORTHY) ||
                    (it.visibility == EventVisibility.HIDDEN && it.type in HIDDEN_GOSSIP_WORTHY)
            }
            .sortedBy { it.id }
        for (event in candidates) {
            if (budget <= 0) break
            val recipients = leakRecipients(ctx, event)
            if (recipients.isEmpty()) continue
            if (!shouldLeak(ctx, event)) continue
            leak(ctx, event, recipients)
            budget--
        }

        // Second pass: inaccurate rumours can mutate and re-spread up to 3 hops from the original.
        // RUMOUR_SPREAD is intentionally reused here (rather than a dedicated RUMOUR_MUTATED type)
        // because mutated rumours are indistinguishable from ordinary leaks from the town's
        // perspective — both are public gossip events, both are scored by ImportanceScorer and
        // picked up by NewspaperGenerator the same way. The generationDepth payload field is the
        // only internal distinction.
        val spreadableRumours = ctx.newEvents
            .filter {
                it.type == EventType.RUMOUR_SPREAD &&
                    it.payload["accurate"] == "false" &&
                    (it.payload["generationDepth"]?.toInt() ?: 0) < 3
            }
            .sortedBy { it.id }
        for (rumour in spreadableRumours) {
            if (!ctx.rng.nextBoolean(0.08)) continue
            val depth = (rumour.payload["generationDepth"]?.toInt() ?: 0) + 1
            val recipients = leakRecipients(ctx, rumour)
            if (recipients.isEmpty()) continue
            leakMutated(ctx, rumour, recipients, depth)
        }
    }

    private fun markInvolvedAsKnowing(ctx: TickContext, event: WorldEvent) {
        for (id in event.involvedResidentIds()) {
            ctx.state.resident(id)?.learn(event.id)
        }
    }

    /**
     * Bystanders who could plausibly hear this from a high-familiarity edge to someone
     * involved, and who don't already know. An empty result means either nobody's close
     * enough to have heard it, or everyone who could have heard it already knows — either
     * way, there's no news left to spread.
     */
    private fun leakRecipients(ctx: TickContext, event: WorldEvent): List<Resident> {
        val involved = event.involvedResidentIds()
        return involved
            .flatMap { id -> ctx.state.relationshipsOf(id).filter { it.familiarity > 60.0 } }
            .map { rel -> if (rel.aId in involved) rel.bId else rel.aId }
            .distinct()
            .mapNotNull { ctx.state.resident(it) }
            .filter { it.inTown && it.id !in involved && !it.knows(event.id) }
    }

    private fun shouldLeak(ctx: TickContext, event: WorldEvent): Boolean {
        // High-familiarity edges around those involved: more gossiping neighbours,
        // more chance it gets out.
        val exposure = event.involvedResidentIds()
            .sumOf { id -> ctx.state.relationshipsOf(id).count { it.familiarity > 60.0 } }
            .coerceAtMost(6)
        var chance = (event.severity * 0.12 + exposure * 0.02).coerceIn(0.0, 0.3)
        if (event.visibility == EventVisibility.HIDDEN) chance *= HIDDEN_LEAK_CHANCE_FACTOR
        return ctx.rng.nextBoolean(chance)
    }

    private fun leak(ctx: TickContext, event: WorldEvent, recipients: List<Resident>) {
        val state = ctx.state
        val accurate = ctx.rng.nextBoolean(0.55)
        val subject = event.sourceResidentId?.let { state.resident(it) }
        val description = if (accurate) {
            ctx.rng.pick(TRUE_OPENERS) + event.description.replaceFirstChar { it.lowercase() }
        } else {
            distortedDescription(ctx, event, subject)
        }
        val rumourEvent = ctx.emit(
            EventType.RUMOUR_SPREAD,
            description,
            sourceResidentId = event.sourceResidentId,
            targetResidentIds = event.targetResidentIds,
            buildingId = event.buildingId,
            severity = (event.severity * ctx.rng.nextDouble(0.4, 0.75)).coerceIn(0.05, 1.0),
            visibility = EventVisibility.PUBLIC,
            payload = mapOf("accurate" to accurate.toString(), "sourceEventId" to event.id.toString()),
            causeIds = if (accurate) listOf(event.id) else emptyList()
        )
        // The bystanders who actually heard it now know it too — accurate or distorted,
        // they're no longer in the dark, so they won't be "told" this one again.
        for (r in recipients) r.learn(rumourEvent.id)
    }

    private fun distortedDescription(ctx: TickContext, event: WorldEvent, subject: Resident?): String {
        val name = subject?.fullName ?: "someone in town"
        val bystanders = ctx.state.detailedResidents()
            .filter { it.id != event.sourceResidentId && it.id !in event.targetResidentIds }
            .sortedBy { it.id }
        val bystander = ctx.rng.pickOrNull(bystanders)
        val templates = mutableListOf(
            "Talk on the high street has it that $name is mixed up in something worse than folk are saying.",
            "Some reckon it was all a misunderstanding blown out of proportion. Others aren't so sure.",
            "The story going round barely resembles what actually happened, by all honest accounts."
        )
        if (bystander != null) {
            templates += "Rumour has dragged ${bystander.fullName} into it too, fairly or not."
        }
        return ctx.rng.pick(templates)
    }

    private fun leakMutated(ctx: TickContext, original: WorldEvent, recipients: List<Resident>, depth: Int) {
        val mutatedDesc = mutateForGeneration(original.description, depth, ctx.rng)
        ctx.emit(
            EventType.RUMOUR_SPREAD,
            mutatedDesc,
            sourceResidentId = original.sourceResidentId,
            targetResidentIds = original.targetResidentIds,
            severity = (original.severity * 0.8).coerceIn(0.05, 1.0),
            visibility = EventVisibility.PUBLIC,
            payload = mapOf(
                "accurate" to "false",
                "sourceEventId" to (original.payload["sourceEventId"] ?: original.id.toString()),
                "generationDepth" to depth.toString()
            )
        )
        for (r in recipients) r.learn(original.id)
    }

    private fun mutateForGeneration(description: String, depth: Int, rng: SimRandom): String {
        val openers = when (depth) {
            1 -> listOf("A version going round has it that ", "Some are saying now that ")
            2 -> listOf("By this telling, ", "It's got so garbled by now that some believe ")
            else -> listOf("Nobody quite knows the original story, but the version circulating claims that ", "The tale has a life of its own now: ")
        }
        val suffixes = when (depth) {
            1 -> listOf(" — though details are hazy.", " — accounts differ.")
            2 -> listOf(" Nobody seems to remember how it started.", " The original event is long since lost in the telling.")
            else -> listOf(" It may bear no relation to what actually happened.", " The truth, if there ever was one, is long buried.")
        }
        val snippet = description.take(80).trimEnd { it == '.' || it == ' ' }
        return rng.pick(openers) + snippet.replaceFirstChar { it.lowercase() } + rng.pick(suffixes)
    }

    private val TRUE_OPENERS = listOf(
        "Word has quietly got out that ",
        "It's no longer much of a secret that ",
        "Whispers around town confirm that ",
        "It's being said, accurately as it happens, that "
    )
}
