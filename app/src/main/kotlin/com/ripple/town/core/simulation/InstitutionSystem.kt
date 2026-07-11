package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.InstitutionRecord
import com.ripple.town.core.model.InstitutionType
import com.ripple.town.core.model.SimTime

/**
 * Tracks the history and reputation arc of civic buildings — school, clinic,
 * town hall, sports hall, community centre. Records leader transitions,
 * cumulative service counts, and notable events. Purely additive; no needs
 * or decisions are driven from here.
 */
object InstitutionSystem {

    const val UPDATE_INTERVAL_DAYS = 30L

    fun updateMonthly(ctx: TickContext) {
        ensureRecords(ctx)
        val state = ctx.state
        for (rec in state.institutionRecords.values) {
            val biz = state.businesses[rec.buildingId]
                ?: state.businesses.values.firstOrNull { it.buildingId == rec.buildingId }
                ?: continue
            // Reputation tracks business reputation with slight smoothing
            rec.reputation += (biz.reputation - rec.reputation) * 0.15
            rec.reputation = rec.reputation.coerceIn(0.0, 100.0)
            // Leader transition
            val currentLeader = biz.ownerId
            if (currentLeader != null && currentLeader != rec.leaderId) {
                rec.leaderId?.let { rec.pastLeaderIds += it }
                rec.leaderId = currentLeader
                val newLeader = state.resident(currentLeader)
                if (newLeader != null) {
                    val role = when (rec.type) {
                        InstitutionType.SCHOOL -> "head teacher"
                        InstitutionType.CLINIC -> "doctor"
                        InstitutionType.TOWN_HALL -> "elected leader"
                        InstitutionType.POLICE_STATION -> "constable"
                        InstitutionType.FIRE_STATION -> "fire captain"
                        else -> "director"
                    }
                    rec.notableEvents += "Year ${SimTime.year(ctx.now)}: ${newLeader.fullName} became $role."
                    if (rec.notableEvents.size > 20) rec.notableEvents.removeAt(0)
                }
            }
        }
        // Count school pupils and clinic patients from this month's events
        for (event in ctx.newEvents) {
            when (event.type) {
                EventType.PERSON_BORN -> {
                    val clinic = state.institutionRecords.values.firstOrNull { it.type == InstitutionType.CLINIC }
                    clinic?.patientsServed = (clinic?.patientsServed ?: 0) + 1
                }
                EventType.JOB_STARTED -> {
                    val bld = event.buildingId?.let { state.building(it) } ?: continue
                    if (bld.type == BuildingType.SCHOOL) {
                        val school = state.institutionRecords.values.firstOrNull { it.type == InstitutionType.SCHOOL }
                        school?.pupilsServed = (school?.pupilsServed ?: 0) + 1
                    }
                }
                EventType.ILLNESS_RECOVERED -> {
                    val clinic = state.institutionRecords.values.firstOrNull { it.type == InstitutionType.CLINIC }
                    clinic?.patientsServed = (clinic?.patientsServed ?: 0) + 1
                }
                else -> {}
            }
        }
    }

    private fun ensureRecords(ctx: TickContext) {
        val state = ctx.state
        val institutionBuildings = mapOf(
            BuildingType.SCHOOL to InstitutionType.SCHOOL,
            BuildingType.CLINIC to InstitutionType.CLINIC,
            BuildingType.TOWN_HALL to InstitutionType.TOWN_HALL,
            BuildingType.SPORTS_HALL to InstitutionType.SPORTS_HALL,
            BuildingType.COMMUNITY_CENTRE to InstitutionType.COMMUNITY_CENTRE,
            BuildingType.POLICE_STATION to InstitutionType.POLICE_STATION,
            BuildingType.FIRE_STATION to InstitutionType.FIRE_STATION
        )
        val alreadyTracked = state.institutionRecords.values.map { it.buildingId }.toSet()
        for (bld in state.buildings.values) {
            val instType = institutionBuildings[bld.type] ?: continue
            if (bld.id in alreadyTracked) continue
            val biz = state.businesses.values.firstOrNull { it.buildingId == bld.id } ?: continue
            state.institutionRecords[state.nextInstitutionId] = InstitutionRecord(
                id = state.nextInstitutionId++,
                type = instType,
                buildingId = bld.id,
                name = bld.name,
                foundedAt = ctx.now,
                leaderId = biz.ownerId
            )
        }
    }
}
