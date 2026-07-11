package com.ripple.town.core.simulation

import com.ripple.town.core.model.AnomalyRecord
import com.ripple.town.core.model.AnomalyType
import com.ripple.town.core.model.Gender
import com.ripple.town.core.model.GoalStatus
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.LegendSubject
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.WorldState

/**
 * Monthly scan of world state for statistically unusual patterns.
 *
 * The detector recognises anomalies; it never manufactures them.  Every check looks at real
 * state — resident ages, relationship counts, occupation inheritance, business lifespans — and
 * records what it finds.  Deduplication prevents the same anomaly being re-recorded for the
 * same subject each month.
 *
 * After decades, [WorldState.anomalyRecords] accumulates a quiet archive: the lifelong
 * bachelor, the business that outlasted everyone, the family whose children all took up
 * the same trade.  Most records will never be explicitly surfaced to the player; they are
 * the substrate from which the town's identity emerges.  The newspaper surfaces a
 * "curious observation" once in a while.
 */
object AnomalyDetector {

    const val CHECK_INTERVAL_DAYS = 30L

    fun updateMonthly(ctx: TickContext) {
        if (SimTime.dayIndex(ctx.now) % CHECK_INTERVAL_DAYS != 0L) return
        checkNeverMarried(ctx)
        checkGenerationalBusiness(ctx)
        checkMiraculousSurvivor(ctx)
        checkLongevityCluster(ctx)
        checkUniversalFriend(ctx)
        checkOmnipresentWitness(ctx)
        checkFamilyDominance(ctx)
        checkGenerationDynasty(ctx)
        checkUnluckyLocation(ctx)
        checkProlificDoctor(ctx)
    }

    // ---- Detection checks ----------------------------------------------------------------------

    private fun checkNeverMarried(ctx: TickContext) {
        for (r in ctx.state.livingResidents()) {
            if (r.ageAt(ctx.now) < 65) continue
            if (r.relationshipStatus != RelationshipStatus.SINGLE) continue
            if (r.partnerId != null) continue
            // Never had a partner — check no prior DIVORCED/WIDOWED state exists
            if (r.goals.none { it.type == GoalType.FIND_PARTNER && it.status == GoalStatus.COMPLETED }) {
                record(ctx, AnomalyType.NEVER_MARRIED,
                    "${r.fullName} has reached the age of ${r.ageAt(ctx.now)} without ever taking a partner — by circumstance or by choice, nobody quite knows.",
                    listOf(r.id), emptyList(), r.ageAt(ctx.now).toDouble())
                break
            }
        }
    }

    private fun checkGenerationalBusiness(ctx: TickContext) {
        for (biz in ctx.state.businesses.values) {
            if (!biz.open) continue
            val ageYears = (ctx.now - biz.openedAt) / SimTime.MINUTES_PER_YEAR
            if (ageYears < 40) continue
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.GENERATIONAL_BUSINESS && biz.buildingId in it.relatedBuildingIds
            }
            if (!alreadyRecorded) {
                val owner = biz.ownerId?.let { ctx.state.resident(it) }
                val ownerNote = if (owner != null) " The ${owner.surname} family is now its custodian." else ""
                record(ctx, AnomalyType.GENERATIONAL_BUSINESS,
                    "${biz.name} has now been trading for $ageYears years.$ownerNote Few businesses last so long.",
                    listOfNotNull(biz.ownerId), listOf(biz.buildingId), ageYears.toDouble())
            }
        }
    }

    private fun checkMiraculousSurvivor(ctx: TickContext) {
        for (r in ctx.state.livingResidents()) {
            if (r.ageAt(ctx.now) < 45) continue
            // Proxy: has accumulated multiple health conditions and is still living
            val conditionCount = r.conditions.size
            if (conditionCount < 2) continue
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.MIRACULOUS_SURVIVOR && r.id in it.relatedResidentIds
            }
            if (!alreadyRecorded) {
                record(ctx, AnomalyType.MIRACULOUS_SURVIVOR,
                    "${r.fullName} has survived multiple serious ailments and is still very much here. Some say they simply refuse to give in.",
                    listOf(r.id), emptyList(), conditionCount.toDouble())
            }
        }
    }

    private fun checkLongevityCluster(ctx: TickContext) {
        for ((_, hh) in ctx.state.households) {
            if (hh.memberIds.isEmpty()) continue
            val elderMembers = hh.memberIds.mapNotNull { ctx.state.residents[it] }
                .filter { r ->
                    r.alive && r.ageAt(ctx.now) >= 80 ||
                        (!r.alive && r.diedAt != null && SimTime.ageYears(r.bornAt, r.diedAt!!) >= 80)
                }
            if (elderMembers.size < 2) continue
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.LONGEVITY_CLUSTER && elderMembers.any { r -> r.id in it.relatedResidentIds }
            }
            if (!alreadyRecorded) {
                val surname = elderMembers.firstOrNull()?.surname ?: "this household"
                record(ctx, AnomalyType.LONGEVITY_CLUSTER,
                    "The $surname household has produced an unusual number of very long-lived residents. People wonder what they put in the stew.",
                    elderMembers.map { it.id }, listOfNotNull(hh.homeBuildingId), elderMembers.size.toDouble())
            }
        }
    }

    private fun checkUniversalFriend(ctx: TickContext) {
        val allLiving = ctx.state.livingResidents()
        if (allLiving.size < 8) return
        val threshold = (allLiving.size * 0.45).toInt().coerceAtLeast(5)
        for (r in allLiving) {
            val friendCount = ctx.state.relationshipsOf(r.id)
                .count { it.affection > 55.0 && it.familiarity > 50.0 }
            if (friendCount < threshold) continue
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.UNIVERSAL_FRIEND && r.id in it.relatedResidentIds
            }
            if (!alreadyRecorded) {
                record(ctx, AnomalyType.UNIVERSAL_FRIEND,
                    "${r.fullName} seems to be on good terms with almost everyone in ${ctx.state.townName}. People just seem to trust them.",
                    listOf(r.id), emptyList(), friendCount.toDouble())
            }
        }
    }

    private fun checkOmnipresentWitness(ctx: TickContext) {
        for (r in ctx.state.detailedResidents()) {
            if (!r.inTown || r.ageAt(ctx.now) < 35) continue
            val significantMemories = r.memories.count { it.importance > 50.0 }
            if (significantMemories < 12) continue
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.OMNIPRESENT_WITNESS && r.id in it.relatedResidentIds
            }
            if (!alreadyRecorded) {
                record(ctx, AnomalyType.OMNIPRESENT_WITNESS,
                    "${r.fullName} has been present at more of this town's significant moments than seems likely by chance. They were just always... there.",
                    listOf(r.id), emptyList(), significantMemories.toDouble())
            }
        }
    }

    private fun checkFamilyDominance(ctx: TickContext) {
        val political = ctx.state.residents.values.filter { r ->
            r.skill(SkillType.POLITICS) > 40.0 &&
                r.goals.any { g -> g.type == GoalType.RUN_FOR_OFFICE && g.status == GoalStatus.COMPLETED }
        }
        val bySurname = political.groupBy { it.surname }
        for ((surname, members) in bySurname) {
            if (members.size < 2) continue
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.FAMILY_DOMINANCE && it.description.contains(surname)
            }
            if (!alreadyRecorded) {
                record(ctx, AnomalyType.FAMILY_DOMINANCE,
                    "The $surname family has produced ${members.size} political figures in ${ctx.state.townName}. It seems to run in the blood.",
                    members.map { it.id }, emptyList(), members.size.toDouble())
            }
        }
    }

    private fun checkGenerationDynasty(ctx: TickContext) {
        for (r in ctx.state.detailedResidents()) {
            if (!r.inTown || r.occupation.isBlank()) continue
            val parentWithSameOccupation = listOfNotNull(
                r.motherId?.let { ctx.state.resident(it) },
                r.fatherId?.let { ctx.state.resident(it) }
            ).firstOrNull { it.occupation == r.occupation }
            if (parentWithSameOccupation == null) continue
            val pair = listOf(r.id, parentWithSameOccupation.id).sorted()
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.GENERATION_DYNASTY && pair.all { id -> id in it.relatedResidentIds }
            }
            if (!alreadyRecorded) {
                val relation = if (parentWithSameOccupation.gender == Gender.FEMALE) "mother" else "father"
                record(ctx, AnomalyType.GENERATION_DYNASTY,
                    "${r.fullName} works as a ${r.occupation.lowercase()}, just as their $relation did before them. Some things simply pass down.",
                    pair, emptyList(), 2.0)
            }
        }
    }

    private fun checkUnluckyLocation(ctx: TickContext) {
        // Proxy: buildings that appear repeatedly in local legends or unsolved cases
        val scores = mutableMapOf<Long, Int>()
        for (legend in ctx.state.localLegends.values) {
            if (legend.subject == LegendSubject.PLACE && legend.subjectId != null) {
                scores[legend.subjectId] = (scores[legend.subjectId] ?: 0) + 2
            }
        }
        for (case in ctx.state.unsolvedCases.values) {
            case.witnessIds.forEach { wid ->
                val home = ctx.state.resident(wid)?.homeBuildingId ?: return@forEach
                scores[home] = (scores[home] ?: 0) + 1
            }
        }
        for ((buildingId, score) in scores) {
            if (score < 4) continue
            val building = ctx.state.buildings[buildingId] ?: continue
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.UNLUCKY_LOCATION && buildingId in it.relatedBuildingIds
            }
            if (!alreadyRecorded) {
                record(ctx, AnomalyType.UNLUCKY_LOCATION,
                    "${building.name} has seen more than its share of trouble over the years. Residents have started to notice.",
                    emptyList(), listOf(buildingId), score.toDouble())
            }
        }
    }

    private fun checkProlificDoctor(ctx: TickContext) {
        for (r in ctx.state.detailedResidents()) {
            if (!r.inTown || r.skill(SkillType.MEDICINE) < 65.0) continue
            // Proxy: a trusted healer is well-known and trusted by many residents
            val trustedBy = ctx.state.relationshipsOf(r.id)
                .count { it.trust > 60.0 && it.familiarity > 50.0 }
            if (trustedBy < 5) continue
            val alreadyRecorded = ctx.state.anomalyRecords.any {
                it.type == AnomalyType.PROLIFIC_DOCTOR && r.id in it.relatedResidentIds
            }
            if (!alreadyRecorded) {
                record(ctx, AnomalyType.PROLIFIC_DOCTOR,
                    "${r.fullName} is the closest thing ${ctx.state.townName} has to a proper doctor. Half the town has reason to be grateful.",
                    listOf(r.id), emptyList(), trustedBy.toDouble())
            }
        }
    }

    // ---- Shared helper -------------------------------------------------------------------------

    private fun record(
        ctx: TickContext, type: AnomalyType, description: String,
        residents: List<Long>, buildings: List<Long>, metric: Double
    ) {
        ctx.state.anomalyRecords += AnomalyRecord(
            id = ctx.state.nextAnomalyId++, type = type, description = description,
            detectedAt = ctx.now, relatedResidentIds = residents,
            relatedBuildingIds = buildings, metric = metric
        )
        if (ctx.state.anomalyRecords.size > 200) ctx.state.anomalyRecords.removeAt(0)
    }

    /** Return a recently-detected anomaly (within 90 days) for the newspaper. */
    fun recentAnomaly(state: WorldState): AnomalyRecord? {
        val window = 90L * SimTime.MINUTES_PER_DAY
        return state.anomalyRecords
            .filter { state.time - it.detectedAt < window }
            .lastOrNull()
    }
}
