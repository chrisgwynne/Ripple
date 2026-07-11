package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Gender
import com.ripple.town.core.model.IdentityFacet
import com.ripple.town.core.model.IdentityLabel
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.WorldEvent

object IdentitySystem {

    /** Called per-tick for each involved resident of every new event. */
    fun onLifeEvent(ctx: TickContext, resident: Resident, event: WorldEvent) {
        if (resident.id !in event.involvedResidentIds()) return
        val toAcquire = mutableListOf<IdentityLabel>()
        when (event.type) {
            EventType.PERSON_BORN -> {
                // Parents are in targetResidentIds; the newborn is sourceResidentId
                if (resident.id != event.sourceResidentId && resident.id in event.targetResidentIds) {
                    toAcquire += when (resident.gender) {
                        Gender.FEMALE, Gender.NONBINARY -> IdentityLabel.MOTHER
                        Gender.MALE -> IdentityLabel.FATHER
                    }
                }
            }
            EventType.PERSON_DIED -> {
                // Anyone in targetResidentIds was bereaved; widowed residents are already flagged
                if (resident.id in event.targetResidentIds) {
                    toAcquire += IdentityLabel.BEREAVED
                    if (resident.relationshipStatus == RelationshipStatus.WIDOWED) {
                        toAcquire += IdentityLabel.WIDOWED
                    }
                }
            }
            EventType.BUSINESS_OPENED -> if (resident.id == event.sourceResidentId) toAcquire += IdentityLabel.BUSINESS_OWNER
            EventType.ELECTION_WON -> if (resident.id == event.sourceResidentId) toAcquire += IdentityLabel.COMMUNITY_LEADER
            EventType.MARRIAGE -> toAcquire += IdentityLabel.MARRIED
            EventType.RESIDENT_ARRIVED -> toAcquire += IdentityLabel.NEWCOMER
            EventType.BURGLARY, EventType.ARSON_ATTEMPT, EventType.FRAUD, EventType.SHOPLIFTING, EventType.MUGGING ->
                if (resident.id == event.sourceResidentId) toAcquire += IdentityLabel.CRIMINAL
            EventType.ILLNESS_RECOVERED ->
                if (resident.conditions.size >= 2) toAcquire += IdentityLabel.SURVIVOR
            EventType.SKILL_MILESTONE -> {
                if (resident.skills.any { it.key in listOf(SkillType.CARPENTRY, SkillType.COOKING, SkillType.CREATIVITY) && it.value >= 70.0 })
                    toAcquire += IdentityLabel.CRAFTSPERSON
                if ((resident.skills[SkillType.TEACHING] ?: 0.0) >= 70.0)
                    toAcquire += IdentityLabel.SCHOLAR
            }
            else -> {}
        }
        for (label in toAcquire) acquireFacet(resident, label, ctx.now, event.id)
        // Age-based ELDER — check on any event involving this resident
        if (resident.lifeStageAt(ctx.now) == LifeStage.ELDER && resident.identityFacets.none { it.label == IdentityLabel.ELDER }) {
            acquireFacet(resident, IdentityLabel.ELDER, ctx.now, null)
        }
    }

    fun updateDaily(ctx: TickContext) {
        // Sweep for residents who became ELDER since last checked
        for (r in ctx.state.detailedResidents()) {
            if (!r.inTown) continue
            if (r.lifeStageAt(ctx.now) == LifeStage.ELDER && r.identityFacets.none { it.label == IdentityLabel.ELDER }) {
                acquireFacet(r, IdentityLabel.ELDER, ctx.now, null)
            }
        }
    }

    private fun acquireFacet(resident: Resident, label: IdentityLabel, now: Long, eventId: Long?) {
        if (resident.identityFacets.any { it.label == label }) return
        resident.identityFacets += IdentityFacet(label = label, acquiredAt = now, sourceEventId = eventId)
    }
}
