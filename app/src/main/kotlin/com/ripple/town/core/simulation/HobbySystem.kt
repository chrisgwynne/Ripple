package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.HobbyEngagement
import com.ripple.town.core.model.HobbyType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Resident

/**
 * Residents develop hobbies from their personality and childhood environment.
 * Hobbies give social bonds (residents with the same hobby warm faster) and
 * slow skill gains in the linked skill type.
 */
object HobbySystem {

    private const val MAX_HOBBIES = 3
    private const val NEW_HOBBY_DAILY_CHANCE = 0.008

    fun updateDaily(ctx: TickContext) {
        val residents = ctx.state.detailedResidents().filter { it.inTown }
        for (r in residents) {
            maybeAcquireHobby(ctx, r)
            practiseHobbies(ctx, r)
        }
        // Social bonds: residents in the same building who share a hobby
        formHobbyBonds(ctx)
    }

    private fun maybeAcquireHobby(ctx: TickContext, r: Resident) {
        if (r.hobbies.size >= MAX_HOBBIES) return
        if (!ctx.rng.nextBoolean(NEW_HOBBY_DAILY_CHANCE)) return
        val p = r.effectivePersonality()
        val existing = r.hobbies.map { it.type }.toSet()
        val candidates = mutableListOf<HobbyType>()
        if (HobbyType.READING !in existing && p.curiosity > 0.5) candidates += HobbyType.READING
        if (HobbyType.GARDENING !in existing && p.patience > 0.5) candidates += HobbyType.GARDENING
        if (HobbyType.COOKING !in existing && p.kindness > 0.5) candidates += HobbyType.COOKING
        if (HobbyType.SPORT !in existing && (r.skills.entries.none() || p.discipline > 0.5)) candidates += HobbyType.SPORT
        if (HobbyType.MUSIC !in existing && p.curiosity > 0.6) candidates += HobbyType.MUSIC
        if (HobbyType.CARPENTRY !in existing && p.discipline > 0.6) candidates += HobbyType.CARPENTRY
        if (HobbyType.FISHING !in existing && p.patience > 0.65) candidates += HobbyType.FISHING
        if (HobbyType.SOCIALISING !in existing && p.sociability > 0.6) candidates += HobbyType.SOCIALISING
        if (HobbyType.VOLUNTEERING !in existing && p.empathy > 0.65) candidates += HobbyType.VOLUNTEERING
        if (HobbyType.COLLECTING !in existing && p.curiosity > 0.65 && p.impulsiveness < 0.4) candidates += HobbyType.COLLECTING
        // Younger residents more open to most hobbies
        if (r.lifeStageAt(ctx.now) == LifeStage.CHILD || r.lifeStageAt(ctx.now) == LifeStage.TEEN) {
            for (type in HobbyType.values()) { if (type !in existing) candidates += type }
        }
        if (candidates.isEmpty()) return
        val type = ctx.rng.pick(candidates.distinct())
        r.hobbies += HobbyEngagement(type = type, startedAt = ctx.now, lastPractisedAt = ctx.now)
    }

    private fun practiseHobbies(ctx: TickContext, r: Resident) {
        val buildingType = r.currentBuildingId?.let { ctx.state.building(it)?.type }
        for (hobby in r.hobbies) {
            val relevant = when (hobby.type) {
                HobbyType.SPORT -> buildingType == BuildingType.SPORTS_HALL || buildingType == BuildingType.PARK
                HobbyType.SOCIALISING -> buildingType == BuildingType.PUB || buildingType == BuildingType.CAFE
                HobbyType.READING -> buildingType == BuildingType.BOOKSHOP
                HobbyType.COOKING -> buildingType == BuildingType.BAKERY || buildingType == BuildingType.CAFE
                else -> ctx.rng.nextBoolean(0.05) // practised at home occasionally
            }
            if (!relevant) continue
            hobby.lastPractisedAt = ctx.now
            hobby.sessionsTotal++
            hobby.enthusiasm = (hobby.enthusiasm + 2.0).coerceAtMost(100.0)
            // Slow skill boost via linked skill
            val skill = hobby.type.skillBoost ?: continue
            val current = r.skills[skill] ?: 0.0
            if (current < 70.0) r.skills[skill] = (current + 0.05).coerceAtMost(70.0)
        }
    }

    private fun formHobbyBonds(ctx: TickContext) {
        // Only check small fraction of buildings per tick
        val buildings = ctx.state.buildings.values.toList()
        if (buildings.isEmpty()) return
        val sample = ctx.rng.pick(buildings)
        val occupants = ctx.state.detailedResidents()
            .filter { it.currentBuildingId == sample.id && it.inTown }
        if (occupants.size < 2) return
        for (i in 0 until occupants.size - 1) {
            val a = occupants[i]; val b = occupants[i + 1]
            val sharedHobby = a.hobbies.map { it.type }.intersect(b.hobbies.map { it.type }.toSet())
            if (sharedHobby.isNotEmpty()) {
                val rel = ctx.state.relationshipOrCreate(a.id, b.id)
                rel.familiarity = (rel.familiarity + 0.5).coerceAtMost(100.0)
                rel.affection = (rel.affection + 0.3).coerceAtMost(100.0)
            }
        }
    }
}
