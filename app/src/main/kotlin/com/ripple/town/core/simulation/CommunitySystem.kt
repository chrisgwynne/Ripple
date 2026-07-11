package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.CommunityGroup
import com.ripple.town.core.model.CommunityGroupType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.HobbyType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.SimTime

/**
 * Community groups (sports clubs, choirs, charities, faith groups) emerge
 * organically from residents who share hobbies or values. Members get social
 * and purpose boosts; groups grow, plateau, or quietly dissolve.
 */
object CommunitySystem {

    const val UPDATE_INTERVAL_DAYS = 14L
    private const val MAX_GROUPS = 12
    private const val FOUNDING_CHANCE = 0.06

    fun updateFortnightly(ctx: TickContext) {
        maybeFoundGroup(ctx)
        updateGroups(ctx)
        processGroupRivalry(ctx)
        updateSharedMemories(ctx)
    }

    /**
     * Finds pairs of active groups that share a [CommunityGroupType] and tracks membership
     * rivalry between them. For each pair (both with ≥2 members), residents who are in neither
     * group but share the relevant hobby are identified as potential recruits. If there are ≥3
     * such shared candidates, each candidate "leans" toward the group whose existing members
     * they have higher average affection toward. When a candidate prefers the rival group,
     * [CommunityGroup.rivalGroupId] is set on the losing group so downstream systems can read
     * the tension — no actual recruitment happens here.
     */
    private fun processGroupRivalry(ctx: TickContext) {
        val state = ctx.state
        val activeGroups = state.communityGroups.values.filter { it.active && it.memberIds.size >= 2 }
        if (activeGroups.size < 2) return

        // Build pairs that share the same CommunityGroupType
        for (i in activeGroups.indices) {
            for (j in i + 1 until activeGroups.size) {
                val groupA = activeGroups[i]
                val groupB = activeGroups[j]
                if (groupA.type != groupB.type) continue

                val memberSetA = groupA.memberIds.toSet()
                val memberSetB = groupB.memberIds.toSet()
                val allMembers = memberSetA + memberSetB

                // Potential recruits: in town, have matching hobby, not already in either group
                val candidates = state.detailedResidents().filter { r ->
                    r.inTown &&
                        r.id !in allMembers &&
                        r.hobbies.any { hobbyToGroupType(it.type) == groupA.type }
                }.sortedBy { it.id }  // stable deterministic order

                if (candidates.size < 3) continue

                // For each candidate, compute average affection toward each group's members
                for (candidate in candidates) {
                    val avgAffectionA = averageAffectionToward(state, candidate.id, memberSetA)
                    val avgAffectionB = averageAffectionToward(state, candidate.id, memberSetB)

                    val prefersA = when {
                        avgAffectionA > avgAffectionB -> true
                        avgAffectionB > avgAffectionA -> false
                        else -> ctx.rng.nextBoolean(0.5)  // tie-break deterministically
                    }

                    // The group that loses this candidate to the rival marks the rival
                    if (prefersA) {
                        // candidate prefers A → B is the losing group
                        groupB.rivalGroupId = groupA.id
                    } else {
                        // candidate prefers B → A is the losing group
                        groupA.rivalGroupId = groupB.id
                    }
                }
            }
        }
    }

    /**
     * Returns the mean [Relationship.affection] from [residentId] toward the members in
     * [targetMemberIds], or 0.0 if there are no known relationships.
     */
    private fun averageAffectionToward(
        state: com.ripple.town.core.model.WorldState,
        residentId: Long,
        targetMemberIds: Set<Long>
    ): Double {
        var total = 0.0
        var count = 0
        for (memberId in targetMemberIds) {
            val rel = state.relationship(residentId, memberId) ?: continue
            total += rel.affection
            count++
        }
        return if (count > 0) total / count else 0.0
    }

    private fun maybeFoundGroup(ctx: TickContext) {
        val state = ctx.state
        if (state.communityGroups.size >= MAX_GROUPS) return
        if (!ctx.rng.nextBoolean(FOUNDING_CHANCE)) return
        // Need a sociable, adult resident with a relevant hobby
        val founders = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT && it.hobbies.isNotEmpty() }
            .sortedBy { it.id }
        if (founders.isEmpty()) return
        val founder = ctx.rng.pick(founders)
        val hobby = ctx.rng.pick(founder.hobbies)
        val type = hobbyToGroupType(hobby.type)
        // Avoid founding a duplicate type
        if (state.communityGroups.values.any { it.type == type && it.active }) return
        val meetingBuilding = findMeetingBuilding(ctx, type)
        val name = generateGroupName(ctx, type, state.townName)
        val id = state.nextGroupId++
        val group = CommunityGroup(
            id = id,
            type = type,
            name = name,
            foundedAt = ctx.now,
            founderResidentId = founder.id,
            meetingBuildingId = meetingBuilding,
            memberIds = mutableListOf(founder.id)
        )
        state.communityGroups[id] = group
        ctx.emit(
            EventType.COMMUNITY_FORMED,
            "${group.name} has been founded by ${founder.firstName} ${founder.surname}.",
            sourceResidentId = founder.id,
            buildingId = meetingBuilding,
            severity = 0.3
        )
    }

    private fun updateGroups(ctx: TickContext) {
        val state = ctx.state
        for (group in state.communityGroups.values.filter { it.active }) {
            // Remove deceased members
            group.memberIds.removeAll { state.resident(it)?.alive != true }
            // Recruit residents with matching hobbies
            if (group.memberIds.size < 12) {
                val matching = state.detailedResidents().filter { r ->
                    r.inTown && r.id !in group.memberIds &&
                        r.hobbies.any { hobbyToGroupType(it.type) == group.type }
                }
                for (candidate in matching.take(2)) {
                    if (ctx.rng.nextBoolean(0.15)) group.memberIds += candidate.id
                }
            }
            // Social and purpose boost for members
            for (memberId in group.memberIds) {
                val r = state.resident(memberId) ?: continue
                if (!r.inTown) continue
                r.needs.social = (r.needs.social + 0.8).coerceAtMost(100.0)
                r.needs.purpose = (r.needs.purpose + 0.5).coerceAtMost(100.0)
            }
            // Reputation drifts with size
            val sizeFactor = (group.memberIds.size.toDouble() / 8.0).coerceAtMost(1.0)
            group.reputation += (sizeFactor * 60.0 + 40.0 - group.reputation) * 0.05
            // Dissolve if too small and been running >1 year
            val ageYears = SimTime.ageYears(group.foundedAt, ctx.now)
            if (group.memberIds.size < 2 && ageYears > 1) {
                group.active = false
                ctx.emit(
                    EventType.COMMUNITY_DISBANDED,
                    "${group.name} has quietly dissolved — not enough members left to carry on.",
                    buildingId = group.meetingBuildingId,
                    severity = 0.2
                )
            }
        }
    }

    /**
     * Records significant town events (severity ≥ 0.6) in each active group's
     * [CommunityGroup.sharedMemories] when ≥3 members live in the same district as the event's
     * building. Uses the bounded [WorldState.recentEventIds] window (last ~60 events) via
     * [EventIndex.get]. Capped at 10 entries per group; oldest evicted when full.
     */
    private fun updateSharedMemories(ctx: TickContext) {
        val state = ctx.state
        val cutoff = ctx.now - SimTime.MINUTES_PER_DAY * 30
        val significantEvents = state.recentEventIds
            .mapNotNull { ctx.eventIndex.get(it) }
            .filter { it.severity >= 0.6 && it.time >= cutoff && it.buildingId != null }
        if (significantEvents.isEmpty()) return

        for (group in state.communityGroups.values.filter { it.active }) {
            for (event in significantEvents) {
                // Already recorded?
                if (event.id in group.sharedMemories) continue
                val eventDistrictId = state.buildings[event.buildingId]?.districtId ?: continue
                // Count members whose home building is in the same district
                val witnessCount = group.memberIds.count { memberId ->
                    val homeId = state.resident(memberId)?.homeBuildingId ?: return@count false
                    state.buildings[homeId]?.districtId == eventDistrictId
                }
                if (witnessCount >= 3) {
                    if (group.sharedMemories.size >= 10) group.sharedMemories.removeAt(0)
                    group.sharedMemories += event.id
                }
            }
        }
    }

    private fun hobbyToGroupType(hobby: HobbyType): CommunityGroupType = when (hobby) {
        HobbyType.SPORT -> CommunityGroupType.SPORTS_CLUB
        HobbyType.MUSIC -> CommunityGroupType.CHOIR_OR_BAND
        HobbyType.READING -> CommunityGroupType.BOOK_CIRCLE
        HobbyType.VOLUNTEERING -> CommunityGroupType.CHARITY
        HobbyType.GARDENING -> CommunityGroupType.GARDEN_SOCIETY
        HobbyType.COOKING -> CommunityGroupType.TRADE_GUILD
        HobbyType.CARPENTRY -> CommunityGroupType.TRADE_GUILD
        HobbyType.COLLECTING -> CommunityGroupType.BOOK_CIRCLE
        HobbyType.SOCIALISING -> CommunityGroupType.NEIGHBOURHOOD_WATCH
        HobbyType.FISHING -> CommunityGroupType.GARDEN_SOCIETY
    }

    private fun findMeetingBuilding(ctx: TickContext, type: CommunityGroupType): Long? {
        val preferred = when (type) {
            CommunityGroupType.SPORTS_CLUB -> BuildingType.SPORTS_HALL
            CommunityGroupType.FAITH_GROUP -> BuildingType.COMMUNITY_CENTRE
            CommunityGroupType.BOOK_CIRCLE, CommunityGroupType.GARDEN_SOCIETY -> BuildingType.BOOKSHOP
            else -> BuildingType.COMMUNITY_CENTRE
        }
        return ctx.state.buildings.values.firstOrNull { it.type == preferred }?.id
            ?: ctx.state.buildings.values.firstOrNull { it.type == BuildingType.PUB }?.id
    }

    private fun generateGroupName(ctx: TickContext, type: CommunityGroupType, townName: String): String {
        return when (type) {
            CommunityGroupType.SPORTS_CLUB -> "$townName Athletic Club"
            CommunityGroupType.CHOIR_OR_BAND -> "$townName Choral Society"
            CommunityGroupType.BOOK_CIRCLE -> "$townName Readers' Circle"
            CommunityGroupType.CHARITY -> "$townName Benevolent Fund"
            CommunityGroupType.NEIGHBOURHOOD_WATCH -> "$townName Neighbourhood Watch"
            CommunityGroupType.FAITH_GROUP -> "$townName Fellowship"
            CommunityGroupType.TRADE_GUILD -> "$townName Craftsmen's Guild"
            CommunityGroupType.GARDEN_SOCIETY -> "$townName Horticultural Society"
        }
    }
}
