package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.DelayedEffect
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.EffectCondition
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.isPublicSpace

/**
 * Processes consequences whose time has come. An effect fires somewhere inside
 * its [earliestAt, latestAt] window if its condition still holds; its strength
 * decays while it waits; if the window closes it lapses silently. Nothing here
 * guarantees an outcome — effects raise or lower chances, the engine decides.
 */
object DelayedEffectSystem {

    const val MAX_APPLIED_PER_TICK = 6

    fun update(ctx: TickContext) {
        var budget = MAX_APPLIED_PER_TICK
        val due = ctx.state.delayedEffects
            .filter { !it.applied && !it.cancelled && ctx.now >= it.earliestAt }
            .sortedBy { it.id }
        for (effect in due) {
            if (budget <= 0) break
            if (ctx.now > effect.latestAt) {
                effect.cancelled = true
                continue
            }
            // Decay while dormant
            if (effect.decayPerDay > 0) {
                val daysWaiting = (ctx.now - effect.earliestAt).toDouble() / SimTime.MINUTES_PER_DAY
                effect.strength = (effect.strength - effect.decayPerDay * daysWaiting).coerceAtLeast(0.0)
                if (effect.strength <= 0.01) { effect.cancelled = true; continue }
            }
            if (!conditionHolds(ctx, effect)) {
                // Condition not met right now; effect stays dormant until window closes.
                continue
            }
            // Windowed chance: roughly effect.strength spread over the window.
            val windowTicks = ((effect.latestAt - effect.earliestAt) / SimTime.MINUTES_PER_TICK).coerceAtLeast(1)
            val perTickChance = (effect.strength / windowTicks * 8.0).coerceIn(0.0005, 0.6)
            if (!ctx.rng.nextBoolean(perTickChance)) continue

            effect.applied = true
            budget--
            apply(ctx, effect)
        }
        // Prune long-dead effects so the list stays bounded.
        if (ctx.state.delayedEffects.size > 400) {
            ctx.state.delayedEffects.removeAll { it.applied || it.cancelled }
        }
    }

    private fun conditionHolds(ctx: TickContext, e: DelayedEffect): Boolean {
        val state = ctx.state
        val target = e.targetResidentId?.let { state.resident(it) }
        return when (e.condition) {
            EffectCondition.NONE -> true
            EffectCondition.STILL_POOR -> (target?.needs?.financialSecurity ?: 100.0) < 35.0
            EffectCondition.STILL_STRESSED -> (target?.needs?.stress ?: 0.0) > 60.0
            EffectCondition.STILL_RESENTFUL -> {
                val other = e.secondaryResidentId ?: return false
                val rel = target?.let { state.relationship(it.id, other) } ?: return false
                rel.resentment > 40.0
            }
            EffectCondition.STILL_UNEMPLOYED -> target != null && state.employmentOf(target) == null
            EffectCondition.BOTH_ALIVE ->
                target?.alive == true && (e.secondaryResidentId?.let { state.resident(it)?.alive } ?: true)
        }
    }

    private fun apply(ctx: TickContext, e: DelayedEffect) {
        val state = ctx.state
        val target = e.targetResidentId?.let { state.resident(it) }
        when (e.type) {
            DelayedEffectType.RESENTMENT_GROWTH -> {
                val other = e.secondaryResidentId ?: return
                val rel = target?.let { state.relationshipOrCreate(it.id, other) } ?: return
                rel.resentment += 12.0 * e.strength * 2
                rel.affection -= 6.0 * e.strength
                rel.clampAll()
            }
            DelayedEffectType.FORGIVENESS -> {
                val other = e.secondaryResidentId ?: return
                val rel = target?.let { state.relationshipOrCreate(it.id, other) } ?: return
                rel.resentment -= 20.0 * e.strength * 2
                rel.trust += 5.0
                rel.clampAll()
                val otherR = state.resident(other)
                if (target.inTown && otherR != null && otherR.inTown) {
                    ctx.emit(
                        EventType.APOLOGY,
                        "${target.fullName} and ${otherR.fullName} have patched things up.",
                        sourceResidentId = target.id, targetResidentIds = listOf(other),
                        severity = 0.2, visibility = EventVisibility.PRIVATE,
                        causeIds = listOf(e.sourceEventId)
                    )
                }
            }
            DelayedEffectType.STRESS_RISE -> target?.let { it.needs.stress += 15.0 * e.strength }
            DelayedEffectType.MOOD_LIFT -> target?.let {
                it.needs.stress -= 18.0 * e.strength
                it.needs.purpose += 8.0 * e.strength
            }
            DelayedEffectType.HEALTH_EROSION -> target?.let { it.needs.health -= 12.0 * e.strength }
            DelayedEffectType.RELATIONSHIP_PRESSURE -> {
                val other = e.secondaryResidentId ?: return
                val rel = target?.let { state.relationshipOrCreate(it.id, other) } ?: return
                rel.resentment += 10.0 * e.strength * 2
                rel.affection -= 5.0 * e.strength
                rel.trust -= 3.0 * e.strength
                rel.clampAll()
                // Pressure this strong often boils over.
                val otherR = state.resident(other)
                if (otherR != null && target.inTown && otherR.inTown && ctx.rng.nextBoolean(0.5)) {
                    InteractionSystem.argue(ctx, target, otherR, rel, target.currentBuildingId)
                }
            }
            DelayedEffectType.CRIME_TEMPTATION -> {
                if (target == null || !target.inTown) return
                // The engine decides: honesty and fear push back even now.
                val yieldChance = (e.strength * (1.0 - target.personality.honesty) *
                    (0.5 + target.personality.impulsiveness * 0.5)).coerceIn(0.0, 0.8)
                if (ctx.rng.nextBoolean(yieldChance)) {
                    val victim = state.businesses.values.filter { it.open }.minByOrNull { it.id }
                    val amount = ctx.rng.nextDouble(20.0, 60.0)
                    target.wealth += amount
                    victim?.let { it.balance -= amount }
                    target.needs.safety -= 10.0
                    val crime = ctx.emit(
                        EventType.CRIME_COMMITTED,
                        "Money has gone missing from ${victim?.name ?: "a till on the high street"}.",
                        sourceResidentId = target.id, businessId = victim?.id,
                        severity = 0.5, visibility = EventVisibility.HIDDEN,
                        causeIds = listOf(e.sourceEventId)
                    )
                    ConsequenceEngine.onEvent(ctx, crime)
                }
            }
            DelayedEffectType.MEETING_CHANCE -> {
                val a = target ?: return
                val b = e.secondaryResidentId?.let { state.resident(it) } ?: return
                if (!a.inTown || !b.inTown) return
                val spot = state.buildings.values
                    .filter { it.type.isPublicSpace && !it.abandoned }
                    .minByOrNull { it.id } ?: return
                ctx.sendTo(a, spot.id, Activity.SOCIALISING, 60, "Felt like getting out")
                ctx.sendTo(b, spot.id, Activity.SOCIALISING, 60, "Felt like getting out")
                // If both make it, InteractionSystem takes over — no guaranteed friendship.
            }
            DelayedEffectType.REVELATION_CHANCE -> {
                val r = target ?: return
                val hidden = r.activeConditions().firstOrNull { it.hidden }
                if (hidden != null) {
                    hidden.hidden = false
                    val ev = ctx.emit(
                        EventType.SECRET_REVEALED,
                        "${r.fullName}'s health trouble has come to light.",
                        sourceResidentId = r.id, severity = 0.4,
                        causeIds = listOf(e.sourceEventId)
                    )
                    ConsequenceEngine.onEvent(ctx, ev)
                }
            }
            DelayedEffectType.GOAL_SEED -> {
                if (e.note == WorldGenerator.NEW_FAMILY_NOTE) {
                    LifecycleSystem.newFamilyArrives(ctx, e.sourceEventId.takeIf { it > 0 })
                    return
                }
                val r = target ?: return
                val type = runCatching { GoalType.valueOf(e.note) }.getOrNull() ?: return
                GoalSystem.seedGoal(ctx, r, type, "An idea that wouldn't let go.", e.sourceEventId.takeIf { it > 0 })
            }
            DelayedEffectType.CONSIDER_MOVING -> {
                val r = target ?: return
                GoalSystem.seedGoal(ctx, r, GoalType.MOVE_HOME, "This town has stopped working for them.", e.sourceEventId.takeIf { it > 0 })
            }
            DelayedEffectType.CONSIDER_QUITTING -> {
                val r = target ?: return
                val emp = state.employmentOf(r) ?: return
                if (ctx.rng.nextBoolean(e.strength)) {
                    emp.endedAt = ctx.now
                    r.employmentId = null
                    r.occupation = "Unemployed"
                    val quit = ctx.emit(
                        EventType.JOB_QUIT,
                        "${r.fullName} has quit their job at ${state.businesses[emp.businessId]?.name ?: "work"}.",
                        sourceResidentId = r.id, businessId = emp.businessId,
                        severity = 0.4, causeIds = listOf(e.sourceEventId)
                    )
                    ConsequenceEngine.onEvent(ctx, quit)
                }
            }
            DelayedEffectType.DEMAND_SHIFT -> {
                val biz = e.targetBusinessId?.let { state.businesses[it] } ?: return
                biz.demand = (biz.demand + 8.0 * e.strength).coerceIn(5.0, 95.0)
            }
        }
    }
}
