package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.CommunityGroup
import com.ripple.town.core.model.CommunityGroupType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.SimCalendar
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.Weather

/**
 * The calendar isn't just a clock — a couple of fixed dates and one weather
 * condition give the town its own rhythm on top of the daily grind:
 *
 * - **Harvest fair** (`HARVEST_FAIR_MONTH`/`HARVEST_FAIR_DAY`): a calendar-fixed
 *   autumn date. Detailed residents get a social/purpose/stress lift, open
 *   food-and-drink businesses see a demand bump, and a `COMMUNITY_EVENT` fires
 *   at the park (if one exists).
 * - **Winter market** (`WINTER_MARKET_MONTH`/`WINTER_MARKET_DAY`): the same
 *   shape, smaller and comfort-flavoured, anchored at the town hall, boosting
 *   a different set of businesses.
 * - **River floods**: during `RAIN`/`STORM`, a small daily chance hits one
 *   building near a `WATER` tile with harsher, water-specific damage than the
 *   generic storm-damage roll in [NeedsSystem.updateWeather] — plus a safety/
 *   comfort hit for anyone currently inside.
 *
 * All three are deliberately bounded: fixed one-shot dates for the fairs, and
 * a low daily probability capped at one flood per day for the river.
 */
object SeasonalEventSystem {

    // Harvest fair — an autumn date, ahead of the winter months used elsewhere
    // (`month == 11 || month == 0 || month == 1`).
    const val HARVEST_FAIR_MONTH = 8
    const val HARVEST_FAIR_DAY = 15

    // Winter market — squarely in the winter stretch.
    const val WINTER_MARKET_MONTH = 11
    const val WINTER_MARKET_DAY = 10

    const val HARVEST_SOCIAL_BOOST = 8.0
    const val HARVEST_STRESS_RELIEF = 6.0
    const val HARVEST_PURPOSE_BOOST = 4.0
    const val HARVEST_DEMAND_BOOST = 14.0

    const val WINTER_COMFORT_BOOST = 5.0
    const val WINTER_STRESS_RELIEF = 3.0
    const val WINTER_SOCIAL_BOOST = 4.0
    const val WINTER_DEMAND_BOOST = 9.0

    /** CE2 — reputation boost applied to a community group that organises a seasonal event. */
    const val GROUP_REPUTATION_BOOST = 10.0

    private val HARVEST_BUSINESS_TYPES = setOf(BusinessType.BAKERY, BusinessType.GROCER, BusinessType.PUB)
    private val WINTER_BUSINESS_TYPES = setOf(BusinessType.CAFE, BusinessType.HARDWARE, BusinessType.TAILOR)
    private val OUTDOOR_SUMMER_TYPES = setOf(BusinessType.CAFE, BusinessType.GROCER, BusinessType.PUB)

    /** Daily chance of a flood while it's raining/storming, and how far from water it can reach. */
    const val FLOOD_CHANCE_RAIN = 0.05
    const val FLOOD_CHANCE_STORM = 0.08
    const val FLOOD_PROXIMITY_TILES = 3
    const val FLOOD_CONDITION_MIN = 18.0
    const val FLOOD_CONDITION_MAX = 32.0
    const val FLOOD_RESIDENT_SAFETY_HIT = 12.0
    const val FLOOD_RESIDENT_COMFORT_HIT = 10.0
    const val MAX_FLOODS_PER_DAY = 1

    fun updateDaily(ctx: TickContext) {
        val month = SimTime.monthIndex(ctx.now)
        val day = SimTime.dayOfMonth(ctx.now)

        if (month == HARVEST_FAIR_MONTH && day == HARVEST_FAIR_DAY) {
            runHarvestFair(ctx)
        }
        if (month == WINTER_MARKET_MONTH && day == WINTER_MARKET_DAY) {
            runWinterMarket(ctx)
        }

        applySeasonalDailyEffects(ctx)
        maybeFlood(ctx)
    }

    private fun applySeasonalDailyEffects(ctx: TickContext) {
        when (SimCalendar.season(ctx.now)) {
            SimCalendar.Season.WINTER -> {
                // Cold weather drains comfort (heating costs) and health resilience
                for (r in ctx.state.detailedResidents()) {
                    if (!r.inTown) continue
                    r.needs.comfort = (r.needs.comfort - 0.4).coerceAtLeast(0.0)
                    // Slight daily health drain in winter: cold suppresses resilience
                    val drain = if (r.lifeStageAt(ctx.now) == LifeStage.ELDER) 0.15 else 0.05
                    r.needs.health = (r.needs.health - drain).coerceAtLeast(0.0)
                }
            }
            SimCalendar.Season.SUMMER -> {
                // Long days lift outdoor business demand
                for (biz in ctx.state.businesses.values) {
                    if (biz.open && biz.type in OUTDOOR_SUMMER_TYPES) {
                        biz.demand = (biz.demand + 0.3).coerceAtMost(100.0)
                    }
                }
            }
            SimCalendar.Season.AUTUMN -> {
                // School restart: parents of school-age children get a small social lift
                // (community re-engagement) on the first school day of the autumn term
                if (SimCalendar.isSchoolDay(ctx.now) && !SimCalendar.isSchoolDay(ctx.now - SimTime.MINUTES_PER_DAY)) {
                    for (r in ctx.state.detailedResidents()) {
                        if (!r.inTown) continue
                        val hasSchoolChild = r.childIds.any { cid ->
                            ctx.state.resident(cid)?.lifeStageAt(ctx.now) == LifeStage.CHILD
                        }
                        if (hasSchoolChild) r.needs.social = (r.needs.social + 3.0).coerceAtMost(100.0)
                    }
                }
            }
            SimCalendar.Season.SPRING -> Unit // spring birth boost handled in LifecycleSystem
        }
    }

    private fun runHarvestFair(ctx: TickContext) {
        val state = ctx.state
        for (r in state.detailedResidents()) {
            if (!r.inTown) continue
            r.needs.social = (r.needs.social + HARVEST_SOCIAL_BOOST).coerceAtMost(100.0)
            r.needs.purpose = (r.needs.purpose + HARVEST_PURPOSE_BOOST).coerceAtMost(100.0)
            r.needs.stress = (r.needs.stress - HARVEST_STRESS_RELIEF).coerceAtLeast(0.0)
        }
        for (biz in state.businesses.values) {
            if (biz.open && biz.type in HARVEST_BUSINESS_TYPES) {
                biz.demand = (biz.demand + HARVEST_DEMAND_BOOST).coerceAtMost(100.0)
            }
        }
        val park = state.buildings.values.firstOrNull { it.type == BuildingType.PARK }

        // CE2 — find the most relevant organising group for the Harvest Fair
        val harvestGroup = findHarvestGroup(ctx)
        val description = if (harvestGroup != null) {
            val leaderName = state.resident(harvestGroup.founderResidentId)?.fullName
            if (leaderName != null) {
                "The ${harvestGroup.name}, led by $leaderName, organises this year's Harvest Fair."
            } else {
                "The ${harvestGroup.name} organises this year's Harvest Fair."
            }
        } else {
            "The harvest fair filled the town with music, stalls and the smell of fresh baking."
        }
        ctx.emit(
            EventType.COMMUNITY_EVENT,
            description,
            buildingId = park?.id, severity = 0.35
        )
        if (harvestGroup != null) {
            harvestGroup.reputation = (harvestGroup.reputation + GROUP_REPUTATION_BOOST).coerceAtMost(100.0)
        }
    }

    private fun runWinterMarket(ctx: TickContext) {
        val state = ctx.state
        for (r in state.detailedResidents()) {
            if (!r.inTown) continue
            r.needs.comfort = (r.needs.comfort + WINTER_COMFORT_BOOST).coerceAtMost(100.0)
            r.needs.social = (r.needs.social + WINTER_SOCIAL_BOOST).coerceAtMost(100.0)
            r.needs.stress = (r.needs.stress - WINTER_STRESS_RELIEF).coerceAtLeast(0.0)
        }
        for (biz in state.businesses.values) {
            if (biz.open && biz.type in WINTER_BUSINESS_TYPES) {
                biz.demand = (biz.demand + WINTER_DEMAND_BOOST).coerceAtMost(100.0)
            }
        }
        val townHall = state.buildings.values.firstOrNull { it.type == BuildingType.TOWN_HALL }

        // CE2 — find the largest active group to organise the Winter Market
        val winterGroup = findWinterGroup(ctx)
        val description = if (winterGroup != null) {
            val leaderName = state.resident(winterGroup.founderResidentId)?.fullName
            if (leaderName != null) {
                "The ${winterGroup.name}, led by $leaderName, organises this year's Winter Market."
            } else {
                "The ${winterGroup.name} organises this year's Winter Market."
            }
        } else {
            "Stalls of mulled cider, woollens and winter fare lined the square for the winter market."
        }
        ctx.emit(
            EventType.COMMUNITY_EVENT,
            description,
            buildingId = townHall?.id, severity = 0.25
        )
        if (winterGroup != null) {
            winterGroup.reputation = (winterGroup.reputation + GROUP_REPUTATION_BOOST).coerceAtMost(100.0)
        }
    }

    // ------------------------------------------------- CE2 group helpers

    /** Harvest Fair organiser: the largest active SPORTS_CLUB or CHARITY group. */
    private fun findHarvestGroup(ctx: TickContext): CommunityGroup? =
        ctx.state.communityGroups.values
            .filter { it.active && (it.type == CommunityGroupType.SPORTS_CLUB || it.type == CommunityGroupType.CHARITY) }
            .maxByOrNull { it.memberIds.size }

    /** Winter Market organiser: the largest active group by member count across all types. */
    private fun findWinterGroup(ctx: TickContext): CommunityGroup? =
        ctx.state.communityGroups.values
            .filter { it.active }
            .maxByOrNull { it.memberIds.size }

    private fun maybeFlood(ctx: TickContext) {
        val state = ctx.state
        val weather = state.weather
        val chance = when (weather) {
            Weather.STORM -> FLOOD_CHANCE_STORM
            Weather.RAIN -> FLOOD_CHANCE_RAIN
            else -> return
        }
        // MAX_FLOODS_PER_DAY is 1: a single daily roll already enforces the cap.
        if (!ctx.rng.nextBoolean(chance)) return

        val candidates = state.buildings.values
            .filter { !it.abandoned && it.condition > 15.0 && isNearWater(state.map, it) }
            .sortedBy { it.id }
        val hit = ctx.rng.pickOrNull(candidates) ?: return

        hit.condition = (hit.condition - ctx.rng.nextDouble(FLOOD_CONDITION_MIN, FLOOD_CONDITION_MAX))
            .coerceAtLeast(5.0)
        hit.visibleChanges += "${SimTime.formatDate(ctx.now)} — Flood damage"
        if (hit.visibleChanges.size >= Building.MAX_VISIBLE_CHANGES) hit.visibleChanges.removeAt(0)

        val occupants = state.residentsIn(hit.id)
        for (r in occupants) {
            r.needs.safety = (r.needs.safety - FLOOD_RESIDENT_SAFETY_HIT).coerceAtLeast(0.0)
            r.needs.comfort = (r.needs.comfort - FLOOD_RESIDENT_COMFORT_HIT).coerceAtLeast(0.0)
        }

        val event = ctx.emit(
            EventType.WEATHER_DAMAGE,
            "The river burst its banks and floodwater tore through ${hit.name}, far worse than the usual storm battering.",
            buildingId = hit.id,
            severity = 0.65
        )
        ConsequenceEngine.onEvent(ctx, event)
        // Bridge 2 — a business housed in this building takes a real operational hit beyond the
        // building's own condition drop; Bridge 3's severe end gives anyone inside a genuine
        // FEAR memory rather than just the safety/comfort need hit already applied above.
        PressureBridgeSystem.onBuildingWeatherDamaged(ctx, event)
        PressureBridgeSystem.onSevereWeatherNearResidents(
            ctx, event, "The flood tore through while we were still inside — I won't forget that fear."
        )
        LegendSystem.considerSpawn(ctx, event)
        TownEraSystem.considerEra(ctx, event)
    }

    /** True if any tile within [FLOOD_PROXIMITY_TILES] of the building's footprint is water. */
    private fun isNearWater(map: TownMap, building: Building): Boolean {
        val minX = building.origin.x - FLOOD_PROXIMITY_TILES
        val maxX = building.origin.x + building.width - 1 + FLOOD_PROXIMITY_TILES
        val minY = building.origin.y - FLOOD_PROXIMITY_TILES
        val maxY = building.origin.y + building.height - 1 + FLOOD_PROXIMITY_TILES
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (building.containsTile(Tile(x, y))) continue
                if (map.tileAt(x, y) == TileType.WATER) return true
            }
        }
        return false
    }
}
