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
    }

    private fun maybeFoundGroup(ctx: TickContext) {
        val state = ctx.state
        if (state.communityGroups.size >= MAX_GROUPS) return
        if (!ctx.rng.nextBoolean(FOUNDING_CHANCE)) return
        // Need a sociable, adult resident with a relevant hobby
        val founders = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT && it.hobbies.isNotEmpty() }
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
            EventType.COMMUNITY_EVENT,
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
                    EventType.COMMUNITY_EVENT,
                    "${group.name} has quietly dissolved — not enough members left to carry on.",
                    buildingId = group.meetingBuildingId,
                    severity = 0.2
                )
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
