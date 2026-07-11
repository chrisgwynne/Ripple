package com.ripple.town.core.simulation

import com.ripple.town.core.model.Belief
import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.FamilyReputationType
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
            EventType.BUSINESS_OPENED -> if (resident.id == event.sourceResidentId) {
                toAcquire += IdentityLabel.BUSINESS_OWNER
                checkDynastyOrSelfMade(ctx, resident, event.id, isNewMayor = false)
            }
            EventType.ELECTION_WON -> if (resident.id == event.sourceResidentId) {
                toAcquire += IdentityLabel.COMMUNITY_LEADER
                checkDynastyOrSelfMade(ctx, resident, event.id, isNewMayor = true)
            }
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

    /**
     * Checks whether a resident who has just achieved something notable (opened a business or
     * won an election) should receive [IdentityLabel.DYNASTY_HEIR] or [IdentityLabel.SELF_MADE].
     * The two labels are mutually exclusive; DYNASTY_HEIR takes priority.
     *
     * DYNASTY_HEIR: the family already has a prior achievement of the same kind
     *   (businessesOwned >= 1 for a new business owner; mayorships >= 1 for a new mayor).
     * SELF_MADE: the family's reputation type is ORDINARY or below AND this is the family's
     *   first achievement of that kind (businessesOwned == 0 for a business; mayorships == 0
     *   for a mayor), meaning the resident broke new ground for their surname.
     *
     * Belief drift is applied once, at the moment of assignment only.
     */
    private fun checkDynastyOrSelfMade(
        ctx: TickContext,
        resident: Resident,
        eventId: Long,
        isNewMayor: Boolean
    ) {
        // Skip if both labels already held (nothing to do)
        val alreadyHeir = resident.identityFacets.any { it.label == IdentityLabel.DYNASTY_HEIR }
        val alreadySelfMade = resident.identityFacets.any { it.label == IdentityLabel.SELF_MADE }
        if (alreadyHeir && alreadySelfMade) return

        val legacy = ctx.state.familyLegacies[resident.surname] ?: return

        val familyHasPriorAchievement = if (isNewMayor) legacy.mayorships >= 1 else legacy.businessesOwned >= 1
        val familyHasNoAchievement = if (isNewMayor) legacy.mayorships == 0 else legacy.businessesOwned == 0

        val baselineReputations = setOf(
            FamilyReputationType.ORDINARY.name,
            FamilyReputationType.FALLEN.name
        )
        val isBaselineFamily = legacy.reputationType in baselineReputations

        when {
            !alreadyHeir && familyHasPriorAchievement -> {
                acquireFacet(resident, IdentityLabel.DYNASTY_HEIR, ctx.now, eventId)
                applyBeliefDrift(resident, BeliefTopic.INSTITUTIONAL_TRUST, +0.02, ctx.now)
            }
            !alreadySelfMade && isBaselineFamily && familyHasNoAchievement -> {
                acquireFacet(resident, IdentityLabel.SELF_MADE, ctx.now, eventId)
                applyBeliefDrift(resident, BeliefTopic.ECONOMIC_OPTIMISM, +0.02, ctx.now)
            }
        }
    }

    /**
     * Applies a one-time [delta] drift to [resident]'s [topic] belief position, initialising the
     * belief entry if it does not yet exist. Clamps position to -1.0..1.0 after application.
     */
    private fun applyBeliefDrift(resident: Resident, topic: BeliefTopic, delta: Double, now: Long) {
        val belief = resident.beliefs.getOrPut(topic) {
            Belief(topic = topic, position = 0.0, confidence = 0.1, lastUpdatedAt = now)
        }
        belief.position = (belief.position + delta).coerceIn(-1.0, 1.0)
        belief.lastUpdatedAt = now
    }

    private fun acquireFacet(resident: Resident, label: IdentityLabel, now: Long, eventId: Long?) {
        if (resident.identityFacets.any { it.label == label }) return
        resident.identityFacets += IdentityFacet(label = label, acquiredAt = now, sourceEventId = eventId)
    }
}
