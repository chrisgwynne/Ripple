package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.InstitutionRecord
import com.ripple.town.core.model.InstitutionType
import com.ripple.town.core.model.SimTime

/**
 * Tracks the history and reputation arc of civic buildings — school, clinic, town hall,
 * sports hall, community centre, police station, fire station.
 *
 * This is a complete rewrite of the original that fixed two confirmed correctness bugs:
 *
 * 1. **`pupilsServed`** previously counted `JOB_STARTED` events (staff hiring) — now counts
 *    residents currently `AT_SCHOOL` in the school building. Still a cumulative count;
 *    updated monthly.
 *
 * 2. **`patientsServed`** previously counted every `PERSON_BORN` and `ILLNESS_RECOVERED`
 *    town-wide regardless of clinic involvement — now counts residents currently `AT_CLINIC`
 *    or `RESTING_ILL` in the clinic building.
 *
 * 3. The dead primary business lookup (`businesses.firstOrNull{}`) was the only lookup and
 *    always fell through because it compared `biz.buildingId == rec.buildingId` but skipped
 *    the direct `businesses[rec.buildingId]` — fixed with a direct keyed lookup.
 *
 * Addresses audit finding #19: "Fix InstitutionSystem dead business lookup + correct
 * pupilsServed/patientsServed."
 */
object InstitutionSystem {

    const val UPDATE_INTERVAL_DAYS = 30L

    private val INSTITUTION_BUILDING_MAP = mapOf(
        BuildingType.SCHOOL          to InstitutionType.SCHOOL,
        BuildingType.CLINIC          to InstitutionType.CLINIC,
        BuildingType.TOWN_HALL       to InstitutionType.TOWN_HALL,
        BuildingType.SPORTS_HALL     to InstitutionType.SPORTS_HALL,
        BuildingType.COMMUNITY_CENTRE to InstitutionType.COMMUNITY_CENTRE,
        BuildingType.POLICE_STATION  to InstitutionType.POLICE_STATION,
        BuildingType.FIRE_STATION    to InstitutionType.FIRE_STATION
    )

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        ensureInstitutionRecords(ctx)
        val residents = state.livingResidents()

        for (rec in state.institutionRecords.values) {
            val bld = state.building(rec.buildingId) ?: continue
            val biz = state.businessAt(rec.buildingId) ?: continue

            // ─── Staff count ────────────────────────────────────────────────
            rec.staffCount = state.employeesOf(biz.id).size

            // ─── Reputation: condition * 40 + staff * 30 + trust * 30 ───��──
            val conditionFactor = bld.condition / 100.0
            val staffFactor = (rec.staffCount.toDouble() / 3.0).coerceIn(0.0, 1.0)
            val trustFactor = state.townState.institutionalTrust / 100.0
            val targetReputation = conditionFactor * 40.0 + staffFactor * 30.0 + trustFactor * 30.0
            rec.reputation = (rec.reputation * 0.85 + targetReputation * 0.15).coerceIn(0.0, 100.0)

            // ─── Leader transition ──────────────────────────────────────────
            val currentLeaderId = biz.ownerId
            if (currentLeaderId != null && currentLeaderId != rec.leaderId) {
                rec.leaderId?.let { rec.pastLeaderIds += it }
                rec.leaderId = currentLeaderId
                val newLeader = state.resident(currentLeaderId)
                if (newLeader != null) {
                    val roleLabel = when (rec.type) {
                        InstitutionType.SCHOOL         -> "head teacher"
                        InstitutionType.CLINIC         -> "doctor"
                        InstitutionType.TOWN_HALL      -> "elected leader"
                        InstitutionType.POLICE_STATION -> "constable"
                        InstitutionType.FIRE_STATION   -> "fire captain"
                        else                           -> "director"
                    }
                    rec.notableEvents += "Year ${SimTime.year(ctx.now)}: ${newLeader.fullName} became $roleLabel."
                    if (rec.notableEvents.size > 20) rec.notableEvents.removeAt(0)
                }
            }

            // ─── Service counting — real residents, correct activities ──────
            updateServiceCounts(ctx, rec, bld.id, residents)

            // ─── Milestones ─────────────────────────────────────────────────
            checkMilestones(ctx, rec)
        }
    }

    private fun updateServiceCounts(
        ctx: TickContext,
        rec: InstitutionRecord,
        buildingId: Long,
        residents: List<com.ripple.town.core.model.Resident>
    ) {
        when (rec.type) {
            InstitutionType.SCHOOL -> {
                // Count residents currently attending school in this specific building
                val pupils = residents.count { r ->
                    r.activity == Activity.AT_SCHOOL && r.currentBuildingId == buildingId
                }
                rec.pupilsServed += pupils
            }
            InstitutionType.CLINIC -> {
                // Count residents being treated at this specific clinic
                val patients = residents.count { r ->
                    (r.activity == Activity.AT_CLINIC || r.activity == Activity.RESTING_ILL) &&
                        r.currentBuildingId == buildingId
                }
                rec.patientsServed += patients
            }
            else -> Unit
        }
    }

    private fun checkMilestones(ctx: TickContext, rec: InstitutionRecord) {
        val year = SimTime.year(ctx.now)
        val ageYears = (ctx.now - rec.foundedAt).toDouble() / SimTime.MINUTES_PER_YEAR

        // Reputation milestone
        if (rec.reputation > 85.0 && !rec.notableEvents.any { it.contains("widely respected") }) {
            rec.notableEvents += "Year $year: ${rec.name} is widely respected in the community."
        }

        // Anniversary milestones: 10, 25, 50, 100 years
        for (anniversary in listOf(10, 25, 50, 100)) {
            val key = "${anniversary}-year"
            if (ageYears >= anniversary && !rec.notableEvents.any { it.contains(key) }) {
                rec.notableEvents += "Year $year: ${rec.name} marks its $anniversary-year anniversary."
                if (rec.notableEvents.size > 20) rec.notableEvents.removeAt(0)
                break
            }
        }
    }

    private fun ensureInstitutionRecords(ctx: TickContext) {
        val state = ctx.state
        val alreadyTracked = state.institutionRecords.values.map { it.buildingId }.toSet()
        for (bld in state.buildings.values) {
            val instType = INSTITUTION_BUILDING_MAP[bld.type] ?: continue
            if (bld.id in alreadyTracked) continue
            // Only create a record if there is an operating business in this building
            val biz = state.businessAt(bld.id) ?: continue
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
