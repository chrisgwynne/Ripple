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

    fun update(ctx: TickContext) {
        var budget = MAX_RUMOURS_PER_TICK
        // ctx.newEvents grows as this loop runs (leaks append to it); snapshot first
        // so a leak is never itself scanned as a leak candidate in the same pass.
        val candidates = ctx.newEvents
            .filter { it.visibility == EventVisibility.PRIVATE && it.type in GOSSIP_WORTHY }
            .sortedBy { it.id }
        for (event in candidates) {
            if (budget <= 0) break
            if (!shouldLeak(ctx, event)) continue
            leak(ctx, event)
            budget--
        }
    }

    private fun shouldLeak(ctx: TickContext, event: WorldEvent): Boolean {
        // High-familiarity edges around those involved: more gossiping neighbours,
        // more chance it gets out.
        val exposure = event.involvedResidentIds()
            .sumOf { id -> ctx.state.relationshipsOf(id).count { it.familiarity > 60.0 } }
            .coerceAtMost(6)
        val chance = (event.severity * 0.12 + exposure * 0.02).coerceIn(0.0, 0.3)
        return ctx.rng.nextBoolean(chance)
    }

    private fun leak(ctx: TickContext, event: WorldEvent) {
        val state = ctx.state
        val accurate = ctx.rng.nextBoolean(0.55)
        val subject = event.sourceResidentId?.let { state.resident(it) }
        val description = if (accurate) {
            ctx.rng.pick(TRUE_OPENERS) + event.description.replaceFirstChar { it.lowercase() }
        } else {
            distortedDescription(ctx, event, subject)
        }
        ctx.emit(
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

    private val TRUE_OPENERS = listOf(
        "Word has quietly got out that ",
        "It's no longer much of a secret that ",
        "Whispers around town confirm that ",
        "It's being said, accurately as it happens, that "
    )
}
