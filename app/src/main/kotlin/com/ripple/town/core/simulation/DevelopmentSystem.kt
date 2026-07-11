package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DevelopmentProject
import com.ripple.town.core.model.DevelopmentStage
import com.ripple.town.core.model.DevelopmentType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.model.isHome
import com.ripple.town.core.model.toBusinessType
import kotlin.math.absoluteValue

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
    /** Base approval chance when trust = 50 and no mayor bonus. */
    const val APPROVAL_BASE = 0.72
    /** Minimum and maximum politically-modulated approval probability. */
    const val APPROVAL_MIN = 0.45
    const val APPROVAL_MAX = 0.95
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
        checkRegenerationOpportunities(ctx)
    }

    /**
     * Regeneration: condemned buildings with very low value attract opportunistic renovation.
     * Low land value = cheap entry; a small daily probability triggers a new HOUSING_RESIDENTIAL
     * or COMMERCIAL_RETAIL project targeted at the condemned site's tile position, skipping the
     * normal need-planner route. The original condemned building is left in place until
     * construction completes, at which point VacancySystem detects it as occupied.
     *
     * This closes the decline loop: derelict → condemned → cheap land → renovation → regeneration.
     */
    private fun checkRegenerationOpportunities(ctx: TickContext) {
        val state = ctx.state
        val condemned = state.buildings.values.filter {
            it.buildingState == BuildingState.CONDEMNED &&
            state.developmentProjects.values.none { p ->
                p.buildingId == it.id || (p.tileX == it.origin.x && p.tileY == it.origin.y)
            }
        }
        for (building in condemned) {
            // Only renovate when land value is genuinely depressed.
            if (building.value > 15_000.0) continue
            if (!ctx.rng.nextBoolean(0.008)) continue
            val devType = if (building.type.isHome) DevelopmentType.HOUSING_RESIDENTIAL
                          else DevelopmentType.COMMERCIAL_RETAIL
            val spec = when (devType) {
                DevelopmentType.HOUSING_RESIDENTIAL -> Triple(BuildingType.TERRACE,  6,  20_000.0)
                else                                 -> Triple(BuildingType.GROCER,  3,  15_000.0)
            }
            val proj = DevelopmentProject(
                id = state.nextProjectId++,
                type = devType,
                districtId = building.districtId,
                tileX = building.origin.x, tileY = building.origin.y,
                buildingType = spec.first, capacity = spec.second,
                estimatedCost = spec.third,
                stage = DevelopmentStage.APPROVED,   // skip public consultation for opportunistic renovation
                createdAt = ctx.now, stageChangedAt = ctx.now,
                note = "Opportunistic renovation of condemned site"
            )
            state.developmentProjects[proj.id] = proj
            building.visibleChanges += "${SimTime.formatDate(ctx.now)} — Renovation interest"
        }
    }

    private fun advanceProposed(ctx: TickContext, proj: DevelopmentProject) {
        val daysInStage = SimTime.dayIndex(ctx.now) - SimTime.dayIndex(proj.stageChangedAt)
        if (daysInStage < APPROVAL_DELAY_DAYS) return
        val chance = politicalApprovalChance(ctx, proj)
        if (ctx.rng.nextBoolean(chance)) {
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

    /**
     * Computes the probability that a proposed [DevelopmentProject] is approved.
     *
     * Three signals feed the decision:
     *
     * 1. **Town sentiment trust** (0..100) — public faith in institutions; scaled to a ±0.18 swing
     *    around [APPROVAL_BASE]. High trust = planning process seen as legitimate = committees vote
     *    yes more readily. Low trust = NIMBY opposition and obstruction.
     *
     * 2. **Mayor's ambition** (0..1) — an ambitious mayor champions growth and pushes projects
     *    through; a cautious incumbent lets things stall. Adds up to +0.10.
     *
     * 3. **Development type** — parks, housing (when housing is short), and civic buildings enjoy
     *    community goodwill; industrial and commercial face more opposition unless the town is
     *    economically optimistic.
     *
     * Result is clamped to [[APPROVAL_MIN]..[APPROVAL_MAX]] so no political condition can produce
     * a certainty of approval or an outright ban on development.
     */
    private fun politicalApprovalChance(ctx: TickContext, proj: DevelopmentProject): Double {
        val sentiment = ctx.state.townSentiment

        // 1. Trust signal: ±0.18 around base
        val trustNorm = sentiment.trust / 100.0   // 0..1
        val trustDelta = (trustNorm - 0.5) * 0.36 // -0.18..+0.18

        // 2. Mayor ambition: +0.10 for maximally ambitious mayor
        val mayorBonus = ctx.state.mayorId
            ?.let { ctx.state.residents[it] }
            ?.effectivePersonality()
            ?.ambition
            ?.times(0.10)
            ?: 0.0

        // 3. Development type affinity (public goodwill vs community pushback)
        val typeBonus = when (proj.type) {
            DevelopmentType.PARK,
            DevelopmentType.SCHOOL,
            DevelopmentType.HEALTHCARE_CLINIC   -> +0.08
            DevelopmentType.HOUSING_RESIDENTIAL,
            DevelopmentType.HOUSING_FLATS       -> if (housingShortfall(ctx)) +0.06 else 0.0
            DevelopmentType.POLICE_STATION,
            DevelopmentType.FIRE_STATION        -> if (sentiment.safety < 40.0) +0.05 else 0.0
            DevelopmentType.INDUSTRIAL,
            DevelopmentType.COMMERCIAL_RETAIL   -> if (sentiment.optimism > 60.0) 0.0 else -0.06
            else                                -> 0.0
        }

        return (APPROVAL_BASE + trustDelta + mayorBonus + typeBonus)
            .coerceIn(APPROVAL_MIN, APPROVAL_MAX)
    }

    private fun housingShortfall(ctx: TickContext): Boolean {
        val state = ctx.state
        val press = state.servicePressures["HOUSING"]
        return press != null && press.satisfactionScore < 0.8
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
