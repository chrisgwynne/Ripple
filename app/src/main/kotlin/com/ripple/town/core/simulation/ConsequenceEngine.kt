package com.ripple.town.core.simulation

import com.ripple.town.core.model.DelayedEffect
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.EffectCondition
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldEvent

/**
 * Reusable cause→effect rules. Rules are small and compose into chains:
 * a job loss raises stress and money pressure now, and *may* lead to crime
 * temptation, relationship strain or moving house later. No storyline is
 * hard-coded; each rule only knows its own step.
 */
data class ConsequenceRule(
    val causeType: EventType,
    val name: String,
    val probability: Double = 1.0,
    val maxDepth: Int = 6,
    val apply: (TickContext, WorldEvent) -> Unit
)

object ConsequenceEngine {

    /** Feed one event through the rule table (bounded per tick). */
    fun onEvent(ctx: TickContext, event: WorldEvent) {
        if (ctx.consequenceBudget <= 0) return
        if (event.consequenceDepth >= MAX_CHAIN_DEPTH) return
        val rules = RULES[event.type] ?: return
        for (rule in rules) {
            if (ctx.consequenceBudget <= 0) break
            if (event.consequenceDepth >= rule.maxDepth) continue
            if (!ctx.rng.nextBoolean(rule.probability)) continue
            ctx.consequenceBudget--
            rule.apply(ctx, event)
        }
    }

    const val MAX_CHAIN_DEPTH = 10

    private fun delayed(
        ctx: TickContext,
        source: WorldEvent,
        type: DelayedEffectType,
        strength: Double,
        earliestDays: Double,
        latestDays: Double,
        targetResidentId: Long? = null,
        secondaryResidentId: Long? = null,
        targetBusinessId: Long? = null,
        condition: EffectCondition = EffectCondition.NONE,
        decayPerDay: Double = 0.0,
        note: String = ""
    ) {
        val day = SimTime.MINUTES_PER_DAY
        ctx.state.delayedEffects += DelayedEffect(
            id = ctx.state.nextEffectId++,
            sourceEventId = source.id,
            targetResidentId = targetResidentId,
            secondaryResidentId = secondaryResidentId,
            targetBusinessId = targetBusinessId,
            type = type,
            strength = strength,
            earliestAt = ctx.now + (earliestDays * day).toLong(),
            latestAt = ctx.now + (latestDays * day).toLong(),
            condition = condition,
            decayPerDay = decayPerDay,
            note = note
        )
    }

    private val RULES: Map<EventType, List<ConsequenceRule>> = buildMap {
        put(EventType.JOB_LOST, listOf(
            ConsequenceRule(EventType.JOB_LOST, "immediate strain") { ctx, e ->
                val r = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                r.needs.stress += 16.0
                r.needs.purpose -= 14.0
                r.needs.financialSecurity -= 18.0
                GoalSystem.seedGoal(ctx, r, GoalType.FIND_JOB, "The bills won't wait.", e.id)
            },
            ConsequenceRule(EventType.JOB_LOST, "partner pressure", probability = 0.8) { ctx, e ->
                val r = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                val partner = r.partnerId ?: return@ConsequenceRule
                delayed(ctx, e, DelayedEffectType.RELATIONSHIP_PRESSURE, 0.5, 2.0, 18.0,
                    targetResidentId = r.id, secondaryResidentId = partner,
                    condition = EffectCondition.STILL_POOR, note = "Money strain after losing work")
            },
            ConsequenceRule(EventType.JOB_LOST, "crime temptation", probability = 0.5) { ctx, e ->
                val r = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                if (r.personality.honesty > 0.75) return@ConsequenceRule
                delayed(ctx, e, DelayedEffectType.CRIME_TEMPTATION,
                    0.35 + r.personality.impulsiveness * 0.3, 5.0, 40.0,
                    targetResidentId = r.id, condition = EffectCondition.STILL_POOR,
                    decayPerDay = 0.01, note = "Desperation creeping in")
            },
            ConsequenceRule(EventType.JOB_LOST, "may move away", probability = 0.4) { ctx, e ->
                delayed(ctx, e, DelayedEffectType.CONSIDER_MOVING, 0.4, 20.0, 70.0,
                    targetResidentId = e.sourceResidentId,
                    condition = EffectCondition.STILL_UNEMPLOYED, note = "Nothing here without work")
            },
            ConsequenceRule(EventType.JOB_LOST, "health erosion", probability = 0.5) { ctx, e ->
                delayed(ctx, e, DelayedEffectType.HEALTH_EROSION, 0.3, 10.0, 45.0,
                    targetResidentId = e.sourceResidentId,
                    condition = EffectCondition.STILL_STRESSED, note = "Worry wearing them down")
            }
        ))

        put(EventType.BUSINESS_CLOSED, listOf(
            ConsequenceRule(EventType.BUSINESS_CLOSED, "trade shifts elsewhere") { ctx, e ->
                val closed = e.businessId?.let { ctx.state.businesses[it] } ?: return@ConsequenceRule
                val rivals = ctx.state.businesses.values
                    .filter { it.open && it.type == closed.type && it.id != closed.id }
                for (rv in rivals) {
                    delayed(ctx, e, DelayedEffectType.DEMAND_SHIFT, 0.4, 1.0, 7.0,
                        targetBusinessId = rv.id, note = "Custom moves to ${rv.name}")
                }
            }
        ))

        put(EventType.BUSINESS_STRUGGLING, listOf(
            ConsequenceRule(EventType.BUSINESS_STRUGGLING, "owner strain") { ctx, e ->
                val owner = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                owner.needs.stress += 10.0
                delayed(ctx, e, DelayedEffectType.STRESS_RISE, 0.4, 2.0, 12.0,
                    targetResidentId = owner.id, note = "Sleepless nights over the books")
            },
            ConsequenceRule(EventType.BUSINESS_STRUGGLING, "staff hours cut", probability = 0.6) { ctx, e ->
                val biz = e.businessId?.let { ctx.state.businesses[it] } ?: return@ConsequenceRule
                val emp = ctx.state.employeesOf(biz.id)
                    .filter { it.residentId != biz.ownerId && !it.reducedHours }
                    .minByOrNull { it.id } ?: return@ConsequenceRule
                emp.reducedHours = true
                val worker = ctx.state.resident(emp.residentId) ?: return@ConsequenceRule
                val cut = ctx.emit(
                    EventType.HOURS_REDUCED,
                    "${worker.fullName}'s hours at ${biz.name} have been cut.",
                    sourceResidentId = worker.id, businessId = biz.id,
                    severity = 0.35, causeIds = listOf(e.id)
                )
                worker.needs.financialSecurity -= 10.0
                worker.needs.stress += 8.0
                onEvent(ctx, cut)
            }
        ))

        put(EventType.ARGUMENT, listOf(
            ConsequenceRule(EventType.ARGUMENT, "grudge or forgiveness", probability = 0.9) { ctx, e ->
                val a = e.sourceResidentId ?: return@ConsequenceRule
                val b = e.targetResidentIds.firstOrNull() ?: return@ConsequenceRule
                val ra = ctx.state.resident(a) ?: return@ConsequenceRule
                if (ra.personality.patience > 0.6 && ctx.rng.nextBoolean(0.5)) {
                    delayed(ctx, e, DelayedEffectType.FORGIVENESS, 0.5, 1.0, 8.0,
                        targetResidentId = a, secondaryResidentId = b,
                        condition = EffectCondition.BOTH_ALIVE, note = "Time to cool off")
                } else {
                    delayed(ctx, e, DelayedEffectType.RESENTMENT_GROWTH, 0.4, 1.0, 10.0,
                        targetResidentId = a, secondaryResidentId = b,
                        condition = EffectCondition.STILL_RESENTFUL, note = "The words keep replaying")
                }
            }
        ))

        put(EventType.SEPARATION, listOf(
            ConsequenceRule(EventType.SEPARATION, "one parent moves out") { ctx, e ->
                val a = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                GoalSystem.seedGoal(ctx, a, GoalType.MOVE_HOME, "Can't stay in that house right now.", e.id)
                a.needs.stress += 15.0
                e.targetResidentIds.firstOrNull()?.let { ctx.state.resident(it) }?.let { it.needs.stress += 15.0 }
            },
            ConsequenceRule(EventType.SEPARATION, "children carry it", probability = 0.9) { ctx, e ->
                val a = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                for (cid in a.childIds) {
                    val child = ctx.state.resident(cid) ?: continue
                    if (!child.alive || child.lifeStageAt(ctx.now) == com.ripple.town.core.model.LifeStage.ADULT) continue
                    child.needs.stress += 12.0
                    child.needs.safety -= 10.0
                    ctx.addMemory(child, com.ripple.town.core.model.MemoryType.NEGLECT,
                        "The house went quiet when they split.", 70.0, e.id, listOf(a.id))
                }
            }
        ))

        put(EventType.ILLNESS_DIAGNOSED, listOf(
            ConsequenceRule(EventType.ILLNESS_DIAGNOSED, "life rearranges") { ctx, e ->
                val r = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                val serious = e.payload["condition"]?.let {
                    runCatching { com.ripple.town.core.model.HealthConditionType.valueOf(it).serious }.getOrDefault(false)
                } ?: false
                r.needs.stress += if (serious) 18.0 else 6.0
                if (serious) {
                    GoalSystem.seedGoal(ctx, r, GoalType.GET_HEALTHY, "Nothing else matters if health goes.", e.id)
                    // Work takes a back seat.
                    val emp = ctx.state.employmentOf(r)
                    if (emp != null && !emp.reducedHours) {
                        emp.reducedHours = true
                        val cut = ctx.emit(
                            EventType.HOURS_REDUCED,
                            "${r.fullName} has cut back working hours for health reasons.",
                            sourceResidentId = r.id, businessId = emp.businessId,
                            severity = 0.3, causeIds = listOf(e.id),
                            visibility = com.ripple.town.core.model.EventVisibility.PRIVATE
                        )
                        onEvent(ctx, cut)
                    }
                    // A partner shoulders care and worry.
                    val partner = r.partnerId?.let { ctx.state.resident(it) }
                    if (partner != null) {
                        partner.needs.stress += 12.0
                        val rel = ctx.state.relationshipOrCreate(r.id, partner.id)
                        rel.dependency += 12.0
                    }
                }
            }
        ))

        put(EventType.CRIME_COMMITTED, listOf(
            ConsequenceRule(EventType.CRIME_COMMITTED, "town feels less safe") { ctx, e ->
                for (r in ctx.state.detailedResidents()) r.needs.safety -= 4.0
            },
            ConsequenceRule(EventType.CRIME_COMMITTED, "may be reported", probability = 0.6) { ctx, e ->
                val culprit = e.sourceResidentId?.let { ctx.state.resident(it) }
                val reporter = ctx.state.detailedResidents()
                    .filter { it.id != e.sourceResidentId && it.personality.honesty > 0.6 }
                    .minByOrNull { it.id } ?: return@ConsequenceRule
                val report = ctx.emit(
                    EventType.CRIME_REPORTED,
                    "The recent theft has been reported to the town constable.",
                    sourceResidentId = reporter.id, severity = 0.3, causeIds = listOf(e.id)
                )
                culprit?.let { it.needs.stress += 10.0; it.reputation -= 8.0 }
                onEvent(ctx, report)
            }
        ))

        put(EventType.PERSON_DIED, listOf(
            ConsequenceRule(EventType.PERSON_DIED, "grief settles slowly") { ctx, e ->
                for (tid in e.targetResidentIds) {
                    delayed(ctx, e, DelayedEffectType.MOOD_LIFT, 0.4, 30.0, 90.0,
                        targetResidentId = tid, note = "Grief slowly easing")
                }
            },
            ConsequenceRule(EventType.PERSON_DIED, "community gathers", probability = 0.7) { ctx, e ->
                val park = ctx.state.buildings.values.firstOrNull { it.type == com.ripple.town.core.model.BuildingType.PARK }
                ctx.emit(
                    EventType.COMMUNITY_EVENT,
                    "Neighbours gathered to remember ${e.payload["name"] ?: "the departed"}.",
                    buildingId = park?.id, severity = 0.3, causeIds = listOf(e.id)
                )
            }
        ))

        put(EventType.MARRIAGE, listOf(
            ConsequenceRule(EventType.MARRIAGE, "newlywed glow") { ctx, e ->
                for (id in e.involvedResidentIds()) {
                    ctx.state.resident(id)?.let {
                        it.needs.stress -= 12.0
                        it.needs.social += 15.0
                        it.needs.purpose += 8.0
                    }
                }
            },
            ConsequenceRule(EventType.MARRIAGE, "households merge", probability = 1.0) { ctx, e ->
                val a = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                val b = e.targetResidentIds.firstOrNull()?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                if (a.householdId != b.householdId) {
                    val from = b.householdId?.let { ctx.state.households[it] }
                    val to = a.householdId?.let { ctx.state.households[it] }
                    if (from != null && to != null) {
                        from.memberIds.remove(b.id)
                        to.memberIds.add(b.id)
                        b.householdId = to.id
                        b.homeBuildingId = to.homeBuildingId
                        val moved = ctx.emit(
                            EventType.RESIDENT_MOVED,
                            "${b.fullName} has moved in with ${a.fullName}.",
                            sourceResidentId = b.id, buildingId = to.homeBuildingId,
                            severity = 0.25, causeIds = listOf(e.id)
                        )
                        onEvent(ctx, moved)
                    }
                }
            }
        ))

        put(EventType.AFFAIR_DISCOVERED, listOf(
            ConsequenceRule(EventType.AFFAIR_DISCOVERED, "trust shatters") { ctx, e ->
                val cheater = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                val spouse = e.targetResidentIds.getOrNull(0)?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                val rel = ctx.state.relationship(cheater.id, spouse.id) ?: return@ConsequenceRule
                rel.trust -= 45.0
                rel.affection -= 35.0
                rel.resentment += 40.0
                rel.clampAll()
                cheater.needs.stress += 20.0
                cheater.reputation -= 15.0
                spouse.needs.stress += 25.0
                delayed(ctx, e, DelayedEffectType.RELATIONSHIP_PRESSURE, 0.9, 0.5, 6.0,
                    targetResidentId = spouse.id, secondaryResidentId = cheater.id,
                    condition = EffectCondition.BOTH_ALIVE, note = "The betrayal is still raw")
            },
            ConsequenceRule(EventType.AFFAIR_DISCOVERED, "the marriage may not survive", probability = 0.55) { ctx, e ->
                val cheater = e.sourceResidentId?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                val spouse = e.targetResidentIds.getOrNull(0)?.let { ctx.state.resident(it) } ?: return@ConsequenceRule
                if (!cheater.inTown || !spouse.inTown) return@ConsequenceRule
                val rel = ctx.state.relationship(cheater.id, spouse.id) ?: return@ConsequenceRule
                if (rel.kind != com.ripple.town.core.model.RelationshipKind.SPOUSE &&
                    rel.kind != com.ripple.town.core.model.RelationshipKind.PARTNER
                ) return@ConsequenceRule
                InteractionSystem.endPartnership(ctx, cheater, spouse, rel, married = rel.kind == com.ripple.town.core.model.RelationshipKind.SPOUSE)
            }
        ))

        put(EventType.SECRET_REVEALED, listOf(
            ConsequenceRule(EventType.SECRET_REVEALED, "trust shaken") { ctx, e ->
                val about = e.sourceResidentId ?: return@ConsequenceRule
                for (rel in ctx.state.relationshipsOf(about)) {
                    rel.trust -= 8.0
                    rel.clampAll()
                }
            }
        ))

        put(EventType.INTERVENTION_APPLIED, emptyList())
    }
}
