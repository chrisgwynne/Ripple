package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.DelayedEffect
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.EffectCondition
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.Intervention
import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldState

sealed class InterventionResult {
    data class Applied(val intervention: Intervention, val flavour: String) : InterventionResult()
    data class Rejected(val reason: String) : InterventionResult()
}

/**
 * The player's only lever on the world: small, indirect nudges that alter
 * circumstances. Nothing here guarantees an outcome — every verb only shifts
 * probabilities or timings that the ordinary engine then resolves.
 */
object InterventionEngine {

    /** In-game hours of observation to regain one nudge. */
    const val REGEN_HOURS = 12L
    /** Cooldown before the same person can be nudged again (in-game hours). */
    const val PER_PERSON_COOLDOWN_HOURS = 24L

    fun regenerate(state: WorldState, minutesAdvanced: Long) {
        if (state.nudges >= state.maxNudges) {
            state.nudgeRegenProgressMinutes = 0
            return
        }
        state.nudgeRegenProgressMinutes += minutesAdvanced
        val needed = REGEN_HOURS * SimTime.MINUTES_PER_HOUR
        while (state.nudgeRegenProgressMinutes >= needed && state.nudges < state.maxNudges) {
            state.nudgeRegenProgressMinutes -= needed
            state.nudges += 1
        }
        if (state.nudges >= state.maxNudges) state.nudgeRegenProgressMinutes = 0
    }

    fun apply(
        ctx: TickContext,
        verb: InterventionVerb,
        targetResidentId: Long?,
        secondaryResidentId: Long? = null,
        targetBuildingId: Long? = null,
        free: Boolean = false
    ): InterventionResult {
        val state = ctx.state
        if (!free && state.nudges <= 0) return InterventionResult.Rejected("No influence left. Watch a while longer.")
        val target = targetResidentId?.let { state.resident(it) }
        if (targetResidentId != null && (target == null || !target.inTown)) {
            return InterventionResult.Rejected("They are beyond reach.")
        }
        if (target != null) {
            val last = state.lastInterventionAt[target.id]
            val cooldown = PER_PERSON_COOLDOWN_HOURS * SimTime.MINUTES_PER_HOUR
            if (last != null && ctx.now - last < cooldown) {
                return InterventionResult.Rejected("${target.firstName} was nudged too recently. The world resists heavy hands.")
            }
        }

        val flavour = applyVerb(ctx, verb, targetResidentId, secondaryResidentId, targetBuildingId)
            ?: return InterventionResult.Rejected("There is nothing to work with there right now.")

        if (!free) state.nudges -= 1
        if (target != null) state.lastInterventionAt[target.id] = ctx.now

        val event = ctx.emit(
            EventType.INTERVENTION_APPLIED,
            flavour,
            sourceResidentId = targetResidentId,
            targetResidentIds = listOfNotNull(secondaryResidentId),
            buildingId = targetBuildingId,
            severity = 0.15,
            visibility = EventVisibility.HIDDEN,
            payload = mapOf("verb" to verb.name)
        )
        val intervention = Intervention(
            id = state.nextInterventionId++,
            verb = verb,
            targetResidentId = targetResidentId,
            secondaryResidentId = secondaryResidentId,
            targetBuildingId = targetBuildingId,
            appliedAt = ctx.now,
            note = flavour,
            eventId = event.id
        )
        return InterventionResult.Applied(intervention, flavour)
    }

    /** Returns human-readable flavour text, or null if the verb had no purchase. */
    private fun applyVerb(
        ctx: TickContext,
        verb: InterventionVerb,
        targetResidentId: Long?,
        secondaryResidentId: Long?,
        targetBuildingId: Long?
    ): String? {
        val state = ctx.state
        val r = targetResidentId?.let { state.resident(it) }
        val day = SimTime.MINUTES_PER_DAY
        return when (verb) {
            InterventionVerb.DELAY -> {
                if (r == null) return null
                // Hold them where they are a little longer.
                r.activityEndsAt += 30
                if (r.activity == Activity.TRAVELLING) r.travelArrivesAt += 30
                "${r.firstName} was held up — a dropped glove, a chatty neighbour, five slow minutes."
            }
            InterventionVerb.DIVERT -> {
                if (r == null) return null
                val diversions = state.buildings.values.filter {
                    it.type == com.ripple.town.core.model.BuildingType.PARK || it.type == com.ripple.town.core.model.BuildingType.CAFE
                }
                val currentOrigin = r.currentBuildingId?.let { state.building(it)?.origin }
                val spot = if (currentOrigin != null)
                    diversions.minByOrNull { currentOrigin.manhattan(it.origin) }
                else
                    ctx.rng.pickOrNull(diversions)
                spot ?: return null
                ctx.sendTo(r, spot.id, Activity.IDLE, 40, "Took the long way round")
                "${r.firstName} took a different route today, past ${spot.name}."
            }
            InterventionVerb.REVEAL -> {
                if (r == null) return null
                val hasSecret = r.activeConditions().any { it.hidden } ||
                    state.relationshipsOf(r.id).any { it.kind == com.ripple.town.core.model.RelationshipKind.AFFAIR }
                if (!hasSecret) return null
                state.delayedEffects += DelayedEffect(
                    id = state.nextEffectId++, sourceEventId = state.nextEventId, // links to the event emitted just after
                    targetResidentId = r.id, type = DelayedEffectType.REVELATION_CHANCE,
                    strength = 0.7, earliestAt = ctx.now + day / 2, latestAt = ctx.now + 14 * day,
                    note = "A loose thread, gently pulled"
                )
                "A loose thread around ${r.firstName} has been left where someone might pull it."
            }
            InterventionVerb.CONCEAL -> {
                if (r == null) return null
                val pending = state.delayedEffects.filter {
                    !it.applied && !it.cancelled && it.targetResidentId == r.id &&
                        it.type == DelayedEffectType.REVELATION_CHANCE
                }
                if (pending.isEmpty()) return null
                pending.forEach { it.strength = (it.strength * 0.3) }
                "What might have surfaced about ${r.firstName} has been tucked deeper into the dark, for now."
            }
            InterventionVerb.INTRODUCE -> {
                if (r == null || secondaryResidentId == null) return null
                val other = state.resident(secondaryResidentId) ?: return null
                state.delayedEffects += DelayedEffect(
                    id = state.nextEffectId++, sourceEventId = state.nextEventId,
                    targetResidentId = r.id, secondaryResidentId = other.id,
                    type = DelayedEffectType.MEETING_CHANCE,
                    strength = 0.8, earliestAt = ctx.now + 60, latestAt = ctx.now + 5 * day,
                    condition = EffectCondition.BOTH_ALIVE,
                    note = "Paths quietly bent towards each other"
                )
                "The paths of ${r.firstName} and ${other.firstName} have been bent, very slightly, towards each other."
            }
            InterventionVerb.MISPLACE -> {
                val b = targetBuildingId?.let { state.building(it) } ?: r?.currentBuildingId?.let { state.building(it) } ?: return null
                // A small object out of place: someone will linger looking for it.
                val nearby = ctx.rng.pickOrNull(state.residentsIn(b.id))
                nearby?.let { it.activityEndsAt += 45 }
                "Something ordinary at ${b.name} is not where it was left."
            }
            InterventionVerb.ENCOURAGE -> {
                if (r == null) return null
                val goal = r.goals.firstOrNull { it.status == com.ripple.town.core.model.GoalStatus.ACTIVE } ?: return null
                goal.progress = (goal.progress + 0.15).coerceAtMost(0.99)
                r.needs.purpose += 8.0
                "${r.firstName} woke with uncommon resolve about ${goal.type.label.lowercase()}."
            }
            InterventionVerb.DISTRACT -> {
                if (r == null) return null
                r.needs.stress -= 6.0
                ctx.beginActivity(r, Activity.IDLE, 45, "Mind wandered somewhere pleasant")
                "${r.firstName} lost the thread of what they were about to do."
            }
            InterventionVerb.INSPIRE -> {
                if (r == null) return null
                r.ideaSeeds += "inspired"
                ctx.addMemory(
                    r, com.ripple.town.core.model.MemoryType.INSPIRATION,
                    "An idea arrived out of nowhere, fully formed.",
                    intensity = 55.0
                )
                "A seed of an idea has been planted in ${r.firstName}'s mind. Whether it grows is up to them."
            }
            InterventionVerb.WARN -> {
                if (r == null) return null
                if ("health_risk" !in r.awareness) r.awareness += "health_risk"
                r.needs.safety -= 4.0
                "${r.firstName} has a nagging feeling something shouldn't be ignored."
            }
        }
    }
}
