package com.ripple.town.core.simulation

import com.ripple.town.core.model.Aspiration
import com.ripple.town.core.model.AspirationStatus
import com.ripple.town.core.model.AspirationType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldEvent

object AspirationSystem {

    fun onLifeEvent(ctx: TickContext, resident: Resident, event: WorldEvent) {
        updateProgressForEvent(resident, event, ctx.now)
        considerFormAspiration(ctx, resident, event)
    }

    fun updateDaily(ctx: TickContext) {
        // Inherit aspirations from recently deceased parents
        for (event in ctx.newEvents) {
            if (event.type != EventType.PERSON_DIED) continue
            val deceased = event.sourceResidentId?.let { ctx.state.resident(it) } ?: continue
            passDownAspirations(ctx, deceased)
        }
        // Periodically form aspirations for adults who have none active
        if (!ctx.rng.nextBoolean(0.05)) return
        val candidates = ctx.state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) in listOf(LifeStage.TEEN, LifeStage.ADULT) }
        if (candidates.isEmpty()) return
        val r = ctx.rng.pick(candidates)
        if (r.aspirations.none { it.status == AspirationStatus.ACTIVE }) {
            maybeFormAspiration(ctx, r)
        }
    }

    private fun updateProgressForEvent(resident: Resident, event: WorldEvent, now: Long) {
        if (resident.id !in event.involvedResidentIds()) return
        for (asp in resident.aspirations.filter { it.status == AspirationStatus.ACTIVE || it.status == AspirationStatus.INHERITED }) {
            val boost = when {
                asp.type == AspirationType.OWN_A_BUSINESS && event.type == EventType.BUSINESS_OPENED -> 80.0
                asp.type == AspirationType.RAISE_A_FAMILY && event.type == EventType.PERSON_BORN -> 60.0
                asp.type == AspirationType.RAISE_A_FAMILY && event.type in listOf(EventType.MARRIAGE, EventType.ENGAGEMENT) -> 30.0
                asp.type == AspirationType.BECOME_A_LEADER && event.type == EventType.ELECTION_WON -> 90.0
                asp.type == AspirationType.FIND_TRUE_LOVE && event.type == EventType.MARRIAGE -> 100.0
                asp.type == AspirationType.FIND_TRUE_LOVE && event.type == EventType.RELATIONSHIP_STARTED -> 40.0
                asp.type == AspirationType.MASTER_A_CRAFT && event.type == EventType.SKILL_MILESTONE -> 30.0
                asp.type == AspirationType.BUILD_A_HOME && event.type == EventType.HOME_PURCHASED -> 80.0
                asp.type == AspirationType.OVERCOME_HARDSHIP && event.type == EventType.ILLNESS_RECOVERED -> 50.0
                asp.type == AspirationType.OVERCOME_HARDSHIP && event.type == EventType.FINANCIAL_RELIEF -> 40.0
                asp.type == AspirationType.LEAVE_A_LEGACY && event.type == EventType.BUSINESS_SUCCESSION -> 50.0
                asp.type == AspirationType.CARRY_ON_A_TRADITION && event.type == EventType.BUSINESS_SUCCESSION -> 60.0
                else -> 0.0
            }
            if (boost <= 0.0) continue
            asp.progress = (asp.progress + boost).coerceAtMost(100.0)
            asp.status = AspirationStatus.ACTIVE  // inherited progressing → active
            if (asp.progress >= 100.0) {
                asp.status = AspirationStatus.FULFILLED
                asp.fulfilledAt = now
            }
        }
    }

    private fun considerFormAspiration(ctx: TickContext, resident: Resident, event: WorldEvent) {
        if (resident.id !in event.involvedResidentIds()) return
        val typeToForm: AspirationType? = when (event.type) {
            EventType.PERSON_BORN -> if (resident.aspirations.none { it.type == AspirationType.RAISE_A_FAMILY }) AspirationType.RAISE_A_FAMILY else null
            EventType.BUSINESS_CLOSED, EventType.JOB_LOST -> if (ctx.rng.nextBoolean(0.3)) AspirationType.OVERCOME_HARDSHIP else null
            EventType.MARRIAGE -> if (resident.aspirations.none { it.type == AspirationType.RAISE_A_FAMILY } && ctx.rng.nextBoolean(0.5)) AspirationType.RAISE_A_FAMILY else null
            EventType.DEBT_CRISIS -> if (ctx.rng.nextBoolean(0.4)) AspirationType.OVERCOME_HARDSHIP else null
            else -> null
        }
        if (typeToForm != null && resident.aspirations.none { it.type == typeToForm && it.status in listOf(AspirationStatus.ACTIVE, AspirationStatus.INHERITED) }) {
            resident.aspirations += Aspiration(type = typeToForm, formedAt = ctx.now)
        }
    }

    private fun maybeFormAspiration(ctx: TickContext, r: Resident) {
        val p = r.effectivePersonality()
        val existing = r.aspirations.map { it.type }.toSet()
        val candidates = mutableListOf<AspirationType>()
        if (AspirationType.OWN_A_BUSINESS !in existing && p.ambition > 0.6) candidates += AspirationType.OWN_A_BUSINESS
        if (AspirationType.FIND_TRUE_LOVE !in existing && r.relationshipStatus == RelationshipStatus.SINGLE && p.sociability > 0.5) candidates += AspirationType.FIND_TRUE_LOVE
        if (AspirationType.RAISE_A_FAMILY !in existing && p.kindness > 0.6 && p.empathy > 0.5) candidates += AspirationType.RAISE_A_FAMILY
        if (AspirationType.MASTER_A_CRAFT !in existing && p.discipline > 0.6 && p.curiosity > 0.5) candidates += AspirationType.MASTER_A_CRAFT
        if (AspirationType.BECOME_A_LEADER !in existing && p.ambition > 0.7 && p.sociability > 0.6) candidates += AspirationType.BECOME_A_LEADER
        if (AspirationType.OVERCOME_HARDSHIP !in existing && r.needs.health < 40.0) candidates += AspirationType.OVERCOME_HARDSHIP
        if (AspirationType.BUILD_A_HOME !in existing && r.homeBuildingId == null) candidates += AspirationType.BUILD_A_HOME
        if (AspirationType.LEAVE_A_LEGACY !in existing && r.lifeStageAt(ctx.now) == LifeStage.ELDER) candidates += AspirationType.LEAVE_A_LEGACY
        if (AspirationType.CARRY_ON_A_TRADITION !in existing && ctx.rng.nextBoolean(0.15)) candidates += AspirationType.CARRY_ON_A_TRADITION
        if (AspirationType.EXPLORE_THE_WORLD !in existing && p.curiosity > 0.7) candidates += AspirationType.EXPLORE_THE_WORLD
        if (candidates.isEmpty()) return
        r.aspirations += Aspiration(type = ctx.rng.pick(candidates), formedAt = ctx.now)
    }

    private fun passDownAspirations(ctx: TickContext, deceased: Resident) {
        val inheritable = setOf(
            AspirationType.OWN_A_BUSINESS, AspirationType.BECOME_A_LEADER, AspirationType.MASTER_A_CRAFT,
            AspirationType.BUILD_A_HOME, AspirationType.CARRY_ON_A_TRADITION, AspirationType.LEAVE_A_LEGACY
        )
        val passable = deceased.aspirations.filter {
            (it.status == AspirationStatus.ACTIVE || it.status == AspirationStatus.DORMANT) && it.type in inheritable
        }
        if (passable.isEmpty()) return
        for (childId in deceased.childIds) {
            val child = ctx.state.resident(childId) ?: continue
            if (!child.inTown) continue
            for (asp in passable) {
                if (child.aspirations.none { it.type == asp.type }) {
                    child.aspirations += Aspiration(
                        type = asp.type,
                        status = AspirationStatus.INHERITED,
                        formedAt = ctx.now,
                        inheritedFromId = deceased.id
                    )
                }
            }
        }
    }
}
