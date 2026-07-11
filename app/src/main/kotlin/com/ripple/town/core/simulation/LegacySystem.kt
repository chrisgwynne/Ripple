package com.ripple.town.core.simulation

import com.ripple.town.core.model.IdentityLabel
import com.ripple.town.core.model.LegacyRecord
import com.ripple.town.core.model.LegacyType
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

object LegacySystem {

    fun onDeath(ctx: TickContext, deceased: Resident, deathEvent: WorldEvent) {
        val state = ctx.state
        val now = ctx.now

        // Children lineage
        if (deceased.childIds.isNotEmpty()) {
            val names = deceased.childIds.mapNotNull { state.resident(it)?.fullName }.take(3)
            val survived = if (names.isEmpty()) "${deceased.fullName} leaves children behind."
            else "${deceased.fullName} is survived by ${names.joinToString(", ")}."
            addRecord(state, LegacyType.CHILDREN, deceased.fullName, survived, deceased, now,
                relatedIds = deceased.childIds.toList())
        }

        // Business dynasty — businesses they still owned at death
        for (biz in state.businesses.values.filter { it.ownerId == deceased.id && it.open }) {
            addRecord(state, LegacyType.BUSINESS_DYNASTY, biz.name,
                "${biz.name} was founded and run by ${deceased.fullName}.",
                deceased, now, relatedBuildingId = biz.buildingId)
        }

        // Community reform — was ever a leader
        if (deceased.identityFacets.any { it.label == IdentityLabel.COMMUNITY_LEADER }) {
            addRecord(state, LegacyType.COMMUNITY_REFORM, state.townName,
                "${deceased.fullName} served as a leader of ${state.townName}.",
                deceased, now)
        }

        // Skill mastery
        val mastered = deceased.skills.filter { it.value >= 80.0 }
        if (mastered.isNotEmpty()) {
            val names2 = mastered.keys.joinToString(", ") { it.label }
            addRecord(state, LegacyType.CRAFT_MASTERY, names2,
                "${deceased.fullName} was renowned for their skill in $names2.",
                deceased, now)
        }

        // Town reputation
        if (deceased.reputation >= 75.0) {
            addRecord(state, LegacyType.TOWN_REPUTATION, deceased.fullName,
                "${deceased.fullName} was widely respected in ${state.townName}.",
                deceased, now)
        }

        // Debt left behind
        if (deceased.debt > 200.0) {
            addRecord(state, LegacyType.DEBT_LEFT, "Unpaid debts",
                "${deceased.fullName} left unpaid debts of £${deceased.debt.toInt()} on their death.",
                deceased, now)
        }

        // Property
        val home = deceased.homeBuildingId?.let { state.building(it) }
        if (home != null) {
            addRecord(state, LegacyType.PROPERTY, home.name,
                "${deceased.fullName} owned ${home.name} at the time of their death.",
                deceased, now, relatedBuildingId = home.id)
        }

        // Add bereavement memories to surviving children
        for (childId in deceased.childIds) {
            val child = state.resident(childId) ?: continue
            if (!child.inTown) continue
            ctx.addMemory(child, MemoryType.LOSS,
                "My parent, ${deceased.fullName}, passed away.",
                intensity = 85.0, eventId = deathEvent.id, associated = listOf(deceased.id))
        }
    }

    fun updateDaily(ctx: TickContext) {
        // Legacy fades imperceptibly slowly
        val ratePerDay = 0.5 / SimTime.DAYS_PER_YEAR  // halves ~every 200 years
        for (rec in ctx.state.legacyRecords) {
            if (rec.strength <= 5.0) continue
            rec.strength = (rec.strength - ratePerDay).coerceAtLeast(0.0)
        }
    }

    /** Returns a legacy record at a round anniversary (10/25/50/100 yr) for newspaper use. */
    fun anniversaryLegacy(state: WorldState): LegacyRecord? {
        val now = state.time
        return state.legacyRecords
            .filter { it.strength > 20.0 }
            .firstOrNull { rec ->
                val years = SimTime.ageYears(rec.createdAt, now)
                years > 0 && (years == 10 || years == 25 || years == 50 || years == 100)
            }
    }

    private fun addRecord(
        state: WorldState,
        type: LegacyType,
        subjectName: String,
        description: String,
        deceased: Resident,
        now: Long,
        relatedIds: List<Long> = emptyList(),
        relatedBuildingId: Long? = null
    ) {
        state.legacyRecords += LegacyRecord(
            id = state.nextLegacyId++,
            type = type,
            subjectName = subjectName,
            description = description,
            originResidentId = deceased.id,
            originResidentName = deceased.fullName,
            createdAt = now,
            relatedResidentIds = relatedIds,
            relatedBuildingId = relatedBuildingId
        )
    }
}
