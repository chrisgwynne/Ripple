package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.HealthCondition
import com.ripple.town.core.model.HealthConditionType
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime

/**
 * Lightweight but consequential health model. Illness onset is driven by age,
 * stress, energy and existing conditions — never a bare dice roll. A diagnosis
 * ripples into work, income, stress and relationships via the consequence engine.
 */
object HealthSystem {

    /** Urgent per-tick checks: clinic treatment progress and collapse of the gravely ill. */
    fun updateUrgent(ctx: TickContext) {
        for (r in ctx.state.residentsOrdered()) {
            if (!r.inTown || r.detailLevel != DetailLevel.DETAILED) continue
            if (r.activity == Activity.AT_CLINIC) {
                treatAtClinic(ctx, r)
            }
        }
    }

    /** Daily pass: onset, progression, recovery, mortality. */
    fun updateDaily(ctx: TickContext) {
        for (r in ctx.state.residentsOrdered()) {
            if (!r.alive || !r.inTown) continue
            if (r.detailLevel == DetailLevel.DETAILED) {
                progressConditions(ctx, r)
                maybeStartCondition(ctx, r)
            }
            checkMortality(ctx, r)
        }
    }

    private fun treatAtClinic(ctx: TickContext, r: Resident) {
        val visible = r.activeConditions().filter { !it.hidden }
        for (c in visible) {
            if (c.diagnosedAt == null) diagnose(ctx, r, c, atClinic = true)
            c.severity -= if (c.type.serious) 1.2 else 2.5
            if (c.severity <= 0) recover(ctx, r, c)
        }
        // A check-up can catch a hidden condition — this is where a Warn nudge pays off.
        val hidden = r.activeConditions().firstOrNull { it.hidden }
        if (hidden != null && ctx.rng.nextBoolean(0.35)) {
            hidden.hidden = false
            diagnose(ctx, r, hidden, atClinic = true)
        }
    }

    private fun diagnose(ctx: TickContext, r: Resident, c: HealthCondition, atClinic: Boolean) {
        c.diagnosedAt = ctx.now
        val e = ctx.emit(
            EventType.ILLNESS_DIAGNOSED,
            "${r.fullName} has been diagnosed with ${c.type.label.lowercase()}.",
            sourceResidentId = r.id,
            severity = if (c.type.serious) 0.65 else 0.3,
            visibility = if (c.type.serious) EventVisibility.PUBLIC else EventVisibility.PRIVATE,
            payload = mapOf("condition" to c.type.name)
        )
        if (c.type.serious) {
            ctx.addMemory(
                r, MemoryType.FEAR, "The day the doctor went quiet before speaking.",
                intensity = 75.0, eventId = e.id,
                belief = if (atClinic) "The clinic caught it in time" else null
            )
        }
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun progressConditions(ctx: TickContext, r: Resident) {
        for (c in r.activeConditions()) {
            val resting = r.activity == Activity.RESTING_ILL || r.activity == Activity.SLEEPING
            val stressFactor = r.needs.stress / 100.0
            val delta = when {
                c.type.serious -> -0.3 + stressFactor * 1.0 + (if (resting) -0.6 else 0.4)
                c.type.chronic -> -0.2 + stressFactor * 0.6 + (if (resting) -0.4 else 0.1)
                else -> -1.6 + stressFactor * 0.8 + (if (resting) -1.2 else 0.6)
            }
            c.severity += delta + ctx.rng.nextDouble(-0.4, 0.4)
            if (c.severity <= 0) {
                recover(ctx, r, c)
            } else if (c.severity > 60 && c.hidden) {
                // A hidden condition this bad makes itself known.
                c.hidden = false
                diagnose(ctx, r, c, atClinic = false)
            }
            c.severity = c.severity.coerceAtMost(100.0)
        }
    }

    private fun recover(ctx: TickContext, r: Resident, c: HealthCondition) {
        if (c.type.chronic && c.severity <= 0) {
            // Chronic conditions quieten rather than vanish.
            c.severity = 5.0
            return
        }
        c.recoveredAt = ctx.now
        c.severity = 0.0
        val e = ctx.emit(
            EventType.ILLNESS_RECOVERED,
            "${r.fullName} has recovered from ${c.type.label.lowercase()}.",
            sourceResidentId = r.id, severity = 0.25,
            visibility = if (c.type.serious) EventVisibility.PUBLIC else EventVisibility.PRIVATE
        )
        r.needs.stress -= 10.0
        r.goals.filter { it.type == com.ripple.town.core.model.GoalType.GET_HEALTHY && it.status == com.ripple.town.core.model.GoalStatus.ACTIVE }
            .forEach { it.status = com.ripple.town.core.model.GoalStatus.COMPLETED; it.resolvedAt = ctx.now }
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun maybeStartCondition(ctx: TickContext, r: Resident) {
        if (r.activeConditions().size >= 2) return
        val age = r.ageAt(ctx.now)
        val n = r.needs
        // Controlled biological risk shaped by circumstance.
        var risk = 0.002
        risk += (n.stress / 100.0) * 0.006
        risk += ((100.0 - n.energy) / 100.0) * 0.004
        risk += ((100.0 - n.health) / 100.0) * 0.006
        if (age > 60) risk += 0.004
        if (age > 75) risk += 0.008
        if (ctx.state.weather == com.ripple.town.core.model.Weather.SNOW) risk += 0.002
        val employment = ctx.state.employmentOf(r)
        val heavyWork = employment != null && ctx.state.businesses[employment.businessId]?.type == com.ripple.town.core.model.BusinessType.FACTORY
        if (heavyWork) risk += 0.002

        if (!ctx.rng.nextBoolean(risk)) return

        val type = when {
            n.stress > 75 && n.energy < 35 -> HealthConditionType.EXHAUSTION
            heavyWork && ctx.rng.nextBoolean(0.4) -> if (ctx.rng.nextBoolean(0.5)) HealthConditionType.INJURY else HealthConditionType.BACK_TROUBLE
            age > 65 && ctx.rng.nextBoolean(0.3) -> HealthConditionType.WEAK_HEART
            age > 55 && ctx.rng.nextBoolean(0.15) -> HealthConditionType.LUNG_ILLNESS
            SimTime.monthIndex(ctx.now) in listOf(10, 11, 0) -> HealthConditionType.FLU
            else -> HealthConditionType.COLD
        }
        val condition = HealthCondition(
            id = ctx.state.nextConditionId++,
            residentId = r.id,
            type = type,
            severity = ctx.rng.nextDouble(15.0, 40.0),
            startedAt = ctx.now,
            hidden = type.serious && ctx.rng.nextBoolean(0.5)
        )
        r.conditions += condition
        if (!condition.hidden) {
            val e = ctx.emit(
                if (type == HealthConditionType.INJURY) EventType.INJURY else EventType.ILLNESS_STARTED,
                "${r.fullName} has come down with ${type.label.lowercase()}.",
                sourceResidentId = r.id,
                severity = if (type.serious) 0.55 else 0.2,
                visibility = EventVisibility.PRIVATE,
                payload = mapOf("condition" to type.name)
            )
            ConsequenceEngine.onEvent(ctx, e)
        }
    }

    private fun checkMortality(ctx: TickContext, r: Resident) {
        val age = r.ageAt(ctx.now)
        var risk = 0.0
        for (c in r.activeConditions()) {
            if (c.type.serious && c.severity > 70) risk += 0.010 + (c.severity - 70) * 0.0012
        }
        if (age > 78) risk += (age - 78) * 0.0006
        if (r.needs.health < 12) risk += 0.004
        if (risk <= 0.0) return
        if (ctx.rng.nextBoolean(risk)) {
            val fatalCondition = r.activeConditions().filter { it.type.serious && it.severity > 60 }
                .maxByOrNull { it.severity }
            val cause = fatalCondition?.type?.label
                ?: if (age > 78) "old age" else "a sudden decline"
            // Underlying cause (added 2026-07-10, see docs/simulation-rules.md "Events, causes,
            // importance"): when death traces to a specific diagnosed condition, link back to
            // that diagnosis event — real history already on record, never invented — so the
            // cause chain shows "diagnosed X" -> "died of X" rather than death appearing to come
            // from nowhere. Old-age/sudden-decline deaths genuinely have no such event, so they
            // correctly carry no extra causeId.
            val diagnosisEvent = fatalCondition?.let { diagnosisEventFor(ctx, r, it) }
            LifecycleSystem.die(ctx, r, cause, causeIds = listOfNotNull(diagnosisEvent?.id))
        }
    }

    /** The most recent `ILLNESS_DIAGNOSED` event for this resident matching [condition]'s type,
     *  from this tick or the bounded recent-events window — mirrors
     *  `CrimeSystem.mostRecentDesperationCause`'s "never invent a cause" discipline. */
    private fun diagnosisEventFor(ctx: TickContext, r: Resident, condition: HealthCondition): com.ripple.town.core.model.WorldEvent? {
        val matches: (com.ripple.town.core.model.WorldEvent) -> Boolean = {
            it.type == EventType.ILLNESS_DIAGNOSED &&
                it.sourceResidentId == r.id &&
                it.payload["condition"] == condition.type.name
        }
        ctx.newEvents.lastOrNull(matches)?.let { return it }
        return ctx.state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .firstOrNull(matches)
    }
}
