package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DevelopmentProject
import com.ripple.town.core.model.DevelopmentStage
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.model.isHome
import com.ripple.town.core.model.toBusinessType

/**
 * Advances development projects through the pipeline one stage per day:
 *
 * PROPOSED  -(5 days, 85% chance)→  APPROVED | REJECTED
 * APPROVED  -(budget check)→         FUNDED
 * FUNDED    -(tile placement)→        CONSTRUCTION  (creates Building entity in PLANNED state)
 * CONSTRUCTION -(days based on cost)→ COMPLETE      (sets Building to OCCUPIED, creates Business)
 *
 * Budget: FUNDED deducts the full cost from [MunicipalBudget.balance]; if the balance would go
 * below −£20,000 the project is deferred (not rejected) until funds improve. Projects can
 * also take on debt — if the balance is negative but debt headroom exists, the shortfall
 * is recorded as municipal debt.
 */
object DevelopmentSystem {

    const val APPROVAL_DELAY_DAYS = 5L
    const val APPROVAL_CHANCE = 0.85
    /** Minimum balance before a project can be funded (allows some deficit). */
    const val FUNDING_BALANCE_FLOOR = -20_000.0
    /** Days per £10,000 of construction cost (£40k = 4 days, £80k = 8 days). */
    const val DAYS_PER_10K = 1.0
    /** Maximum municipal debt load before new projects are deferred. */
    const val MAX_DEBT = 150_000.0

    fun updateDaily(ctx: TickContext) {
        val projects = ctx.state.developmentProjects.values.toList()
        for (proj in projects) {
            when (proj.stage) {
                DevelopmentStage.PROPOSED    -> advanceProposed(ctx, proj)
                DevelopmentStage.APPROVED    -> advanceFunding(ctx, proj)
                DevelopmentStage.FUNDED      -> startConstruction(ctx, proj)
                DevelopmentStage.CONSTRUCTION -> advanceConstruction(ctx, proj)
                else -> {}
            }
        }
    }

    private fun advanceProposed(ctx: TickContext, proj: DevelopmentProject) {
        val daysInStage = SimTime.dayIndex(ctx.now) - SimTime.dayIndex(proj.stageChangedAt)
        if (daysInStage < APPROVAL_DELAY_DAYS) return
        val approved = ctx.rng.nextBoolean(APPROVAL_CHANCE)
        if (approved) {
            proj.stage = DevelopmentStage.APPROVED
            proj.stageChangedAt = ctx.now
            ctx.emit(
                EventType.DEVELOPMENT_APPROVED,
                "Planning committee has approved the new ${proj.buildingType.label.lowercase()}.",
                severity = 0.45, visibility = EventVisibility.PUBLIC
            )
        } else {
            proj.stage = DevelopmentStage.REJECTED
            proj.stageChangedAt = ctx.now
        }
    }

    private fun advanceFunding(ctx: TickContext, proj: DevelopmentProject) {
        val budget = ctx.state.municipalBudget
        if (budget.balance < FUNDING_BALANCE_FLOOR) return
        if (budget.debt >= MAX_DEBT) return
        val cost = proj.estimatedCost
        if (budget.balance >= cost) {
            budget.balance -= cost
            budget.constructionExpensesThisYear += cost
        } else {
            // Deficit-fund: record shortfall as municipal debt.
            val shortfall = cost - budget.balance.coerceAtLeast(0.0)
            budget.debt += shortfall
            budget.balance -= cost
            budget.constructionExpensesThisYear += cost
        }
        proj.fundedAmount = cost
        proj.stage = DevelopmentStage.FUNDED
        proj.stageChangedAt = ctx.now
    }

    private fun startConstruction(ctx: TickContext, proj: DevelopmentProject) {
        val state = ctx.state
        val (bType, w, h) = footprintFor(proj.buildingType)
        val slot = findBuildingSlot(state, proj.districtId, w, h) ?: return
        val constructionDays = (proj.estimatedCost / 10_000.0 * DAYS_PER_10K).toLong().coerceAtLeast(1L)
        val completesAt = ctx.now + constructionDays * SimTime.MINUTES_PER_DAY
        val building = Building(
            id = state.nextBuildingId++,
            name = "New ${bType.label}",
            type = proj.buildingType,
            origin = Tile(slot.first, slot.second),
            width = w, height = h,
            door = Tile(slot.first + w / 2, slot.second + h),
            capacity = proj.capacity,
            constructedAt = ctx.now,
            districtId = proj.districtId ?: state.districtAt(slot.first, slot.second)?.id,
            buildingState = BuildingState.UNDER_CONSTRUCTION,
            constructionCompletesAt = completesAt,
            developmentProjectId = proj.id
        )
        state.buildings[building.id] = building
        proj.buildingId = building.id
        proj.tileX = slot.first
        proj.tileY = slot.second
        proj.stage = DevelopmentStage.CONSTRUCTION
        proj.stageChangedAt = ctx.now
    }

    private fun advanceConstruction(ctx: TickContext, proj: DevelopmentProject) {
        val state = ctx.state
        val building = proj.buildingId?.let { state.buildings[it] } ?: return
        val completesAt = building.constructionCompletesAt ?: return
        if (ctx.now < completesAt) return

        building.buildingState = BuildingState.OCCUPIED
        building.constructionCompletesAt = null
        building.name = defaultName(proj.buildingType, ctx)

        // Wire a Business entity for non-residential buildings.
        val bizType = proj.buildingType.toBusinessType()
        if (bizType != null) {
            val biz = com.ripple.town.core.model.Business(
                id = state.nextBusinessId++,
                buildingId = building.id,
                name = building.name,
                type = bizType,
                balance = 5_000.0,
                reputation = 50.0,
                demand = 40.0,
                employeeCapacity = proj.capacity,
                openedAt = ctx.now
            )
            state.businesses[biz.id] = biz
        }

        proj.stage = DevelopmentStage.COMPLETE
        proj.stageChangedAt = ctx.now
        ctx.emit(
            EventType.DEVELOPMENT_COMPLETE,
            "The new ${proj.buildingType.label.lowercase()} has opened.",
            buildingId = building.id, severity = 0.55, visibility = EventVisibility.PUBLIC
        )
    }

    private data class Footprint(val type: BuildingType, val w: Int, val h: Int)

    private fun footprintFor(type: BuildingType): Footprint = when (type) {
        BuildingType.FLAT            -> Footprint(type, 5, 4)
        BuildingType.SCHOOL          -> Footprint(type, 5, 4)
        BuildingType.CLINIC          -> Footprint(type, 4, 3)
        BuildingType.POLICE_STATION  -> Footprint(type, 4, 3)
        BuildingType.FIRE_STATION    -> Footprint(type, 4, 3)
        BuildingType.COMMUNITY_CENTRE-> Footprint(type, 6, 4)
        BuildingType.SPORTS_HALL     -> Footprint(type, 7, 5)
        BuildingType.PARK            -> Footprint(type, 5, 4)
        BuildingType.WORKSHOP        -> Footprint(type, 4, 3)
        BuildingType.GROCER          -> Footprint(type, 3, 2)
        else                         -> Footprint(type, 3, 3)
    }

    /**
     * Scan for a clear rectangular slot inside the named district (or any district
     * if districtId is null). "Clear" means: no existing building overlaps it and
     * all tiles under the footprint are GRASS (not WATER or ROAD).
     */
    private fun findBuildingSlot(state: WorldState, districtId: Long?, w: Int, h: Int): Pair<Int, Int>? {
        val district = districtId?.let { state.districts[it] }
        val scanX0 = district?.originX?.plus(2) ?: 2
        val scanY0 = district?.originY?.plus(2) ?: 2
        val scanX1 = district?.let { it.originX + it.width - w - 2 } ?: (state.map.width - w - 2)
        val scanY1 = district?.let { it.originY + it.height - h - 2 } ?: (state.map.height - h - 2)

        // Pre-build a set of all occupied tiles.
        val occupied = mutableSetOf<Pair<Int, Int>>()
        for (b in state.buildings.values) {
            if (b.buildingState == BuildingState.DEMOLISHED) continue
            for (bx in b.origin.x until b.origin.x + b.width)
                for (by in b.origin.y until b.origin.y + b.height)
                    occupied += bx to by
        }

        for (y in scanY0..scanY1) {
            for (x in scanX0..scanX1) {
                if (fits(state, x, y, w, h, occupied)) return x to y
            }
        }
        return null
    }

    private fun fits(state: WorldState, x: Int, y: Int, w: Int, h: Int, occupied: Set<Pair<Int, Int>>): Boolean {
        for (bx in x until x + w) {
            for (by in y until y + h) {
                if (bx to by in occupied) return false
                val tile = state.map.tileAt(bx, by)
                if (tile == TileType.WATER || tile == TileType.ROAD) return false
            }
        }
        return true
    }

    private fun defaultName(type: BuildingType, ctx: TickContext): String = when (type) {
        BuildingType.SCHOOL          -> "New ${ctx.state.townName} School"
        BuildingType.CLINIC          -> "New Medical Centre"
        BuildingType.POLICE_STATION  -> "New Police Station"
        BuildingType.FIRE_STATION    -> "New Fire Station"
        BuildingType.COMMUNITY_CENTRE-> "New Community Centre"
        BuildingType.SPORTS_HALL     -> "New Sports Hall"
        BuildingType.PARK            -> "New Public Park"
        BuildingType.WORKSHOP        -> "New Workshop"
        BuildingType.GROCER          -> "New Grocer"
        BuildingType.FLAT            -> "New Apartment Block"
        BuildingType.TERRACE         -> "New Terraced House"
        else                         -> "New ${type.label}"
    }
}
