package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.DevelopmentProject
import com.ripple.town.core.model.DevelopmentStage
import com.ripple.town.core.model.DevelopmentType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.ServicePressure
import com.ripple.town.core.model.ServiceType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.isHome

/**
 * Runs once per in-game month: measures demand vs capacity for each [ServiceType],
 * updates [WorldState.servicePressures], and creates [DevelopmentProject] proposals
 * when a service is chronically under capacity.
 *
 * A proposal is only created if:
 * - No active project of the same category is already in the pipeline.
 * - The municipal budget has at least £5,000 (floor check; the real cost is paid
 *   later when DevelopmentSystem moves the project to FUNDED).
 * - The deficit is large enough to warrant action (threshold per service type).
 */
object TownNeedsPlanner {

    const val UPDATE_INTERVAL_DAYS = 30L
    private const val MIN_BUDGET_TO_PROPOSE = 5_000.0

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val pop = state.livingResidents().count { it.inTown }
        if (pop == 0) return

        val children = state.livingResidents().count { it.inTown && it.lifeStageAt(state.time) == LifeStage.CHILD }
        val teens = state.livingResidents().count { it.inTown && it.lifeStageAt(state.time) == LifeStage.TEEN }
        val adults = state.livingResidents().count { it.inTown && it.lifeStageAt(state.time) == LifeStage.ADULT }
        val seniors = state.livingResidents().count { it.inTown && it.lifeStageAt(state.time) == LifeStage.ELDER }

        val homes = state.buildings.values.filter { it.type.isHome && !it.abandoned }
        val homeCapacity = homes.sumOf { it.capacity }
        val householdsInTown = state.households.values.count { hh -> hh.memberIds.isNotEmpty() }

        val schoolBuildings = state.buildings.values.filter { it.type == BuildingType.SCHOOL }
        val schoolCapacity = schoolBuildings.sumOf { it.capacity * 10 }
        val schoolDemand = children + teens

        val clinicBuildings = state.buildings.values.filter { it.type == BuildingType.CLINIC }
        val clinicCapacity = clinicBuildings.sumOf { it.capacity * 15 }

        val policeBuildings = state.buildings.values.filter { it.type == BuildingType.POLICE_STATION }
        val policeCapacity = policeBuildings.size * 120

        val fireBuildings = state.buildings.values.filter { it.type == BuildingType.FIRE_STATION }
        val fireCapacity = fireBuildings.size * 200

        val openJobs = state.businesses.values
            .filter { it.open }
            .sumOf { biz -> (biz.employeeCapacity - state.employeesOf(biz.id).size).coerceAtLeast(0) }
        val unemployedAdults = (adults - state.employments.values.count { it.active &&
            state.resident(it.residentId)?.inTown == true }).coerceAtLeast(0)

        val retailBizCount = state.businesses.values.count { it.open &&
            it.type !in setOf(BusinessType.CLINIC, BusinessType.SCHOOL, BusinessType.TOWN_HALL,
                BusinessType.FIRE_STATION, BusinessType.POLICE_STATION,
                BusinessType.SPORTS_HALL, BusinessType.COMMUNITY_CENTRE) }
        val retailDemand = pop / 20  // 1 retail slot per 20 residents

        val parkBuildings = state.buildings.values.filter { it.type == BuildingType.PARK }
        val parkCapacity = parkBuildings.size * 50

        fun pressure(service: ServiceType, demand: Int, capacity: Int): ServicePressure {
            val sat = if (demand == 0) 1.0 else (capacity.toDouble() / demand.toDouble()).coerceIn(0.0, 1.5)
            return ServicePressure(service, demand, capacity, sat)
        }

        val pressures = mapOf(
            ServiceType.HOUSING     to pressure(ServiceType.HOUSING,    householdsInTown, homeCapacity),
            ServiceType.SCHOOL      to pressure(ServiceType.SCHOOL,     schoolDemand,     schoolCapacity),
            ServiceType.HEALTHCARE  to pressure(ServiceType.HEALTHCARE, pop,              clinicCapacity),
            ServiceType.POLICE      to pressure(ServiceType.POLICE,     pop,              policeCapacity),
            ServiceType.FIRE        to pressure(ServiceType.FIRE,       pop,              fireCapacity),
            ServiceType.EMPLOYMENT  to pressure(ServiceType.EMPLOYMENT, unemployedAdults, openJobs),
            ServiceType.RETAIL      to pressure(ServiceType.RETAIL,     retailDemand,     retailBizCount),
            ServiceType.PARKS       to pressure(ServiceType.PARKS,      pop,              parkCapacity)
        )

        for ((k, p) in pressures) state.servicePressures[k.name] = p

        // Propose development if under pressure and budget allows.
        if (state.municipalBudget.balance < MIN_BUDGET_TO_PROPOSE) return

        val activeTypes = state.activeDevelopmentProjects().map { it.type }.toSet()

        for ((serviceType, p) in pressures) {
            if (!p.isUnderPressure) continue
            val devType = serviceTypeToDevelopment(serviceType) ?: continue
            if (devType in activeTypes) continue
            propose(ctx, devType, p.deficit)
        }
    }

    private fun propose(ctx: TickContext, type: DevelopmentType, deficit: Int) {
        val state = ctx.state
        val (buildingType, capacity, cost) = developmentSpec(type)
        val proj = DevelopmentProject(
            id = state.nextProjectId++,
            type = type,
            districtId = null,          // placed by DevelopmentSystem when funded
            tileX = -1, tileY = -1,     // set when construction starts
            buildingType = buildingType,
            capacity = capacity,
            estimatedCost = cost,
            stage = DevelopmentStage.PROPOSED,
            createdAt = state.time,
            stageChangedAt = state.time,
            note = "Deficit: $deficit units"
        )
        state.developmentProjects[proj.id] = proj
        ctx.emit(
            EventType.DEVELOPMENT_PROPOSED,
            "A new ${buildingType.label.lowercase()} has been proposed to address ${type.label.lowercase()} shortfall.",
            severity = 0.4, visibility = EventVisibility.PUBLIC
        )
    }

    private fun serviceTypeToDevelopment(s: ServiceType): DevelopmentType? = when (s) {
        ServiceType.HOUSING    -> DevelopmentType.HOUSING_RESIDENTIAL
        ServiceType.SCHOOL     -> DevelopmentType.SCHOOL
        ServiceType.HEALTHCARE -> DevelopmentType.HEALTHCARE_CLINIC
        ServiceType.POLICE     -> DevelopmentType.POLICE_STATION
        ServiceType.FIRE       -> DevelopmentType.FIRE_STATION
        ServiceType.EMPLOYMENT -> DevelopmentType.INDUSTRIAL
        ServiceType.RETAIL     -> DevelopmentType.COMMERCIAL_RETAIL
        ServiceType.PARKS      -> DevelopmentType.PARK
        // Phase 6 service types — no automated DevelopmentType mapping yet;
        // OpportunityDetectionSystem handles these via the Opportunity model instead.
        ServiceType.CHILDCARE,
        ServiceType.ELDERLY_CARE,
        ServiceType.FOOD_RETAIL,
        ServiceType.CONVENIENCE_RETAIL,
        ServiceType.CAFE_DINING,
        ServiceType.RESTAURANT_DINING,
        ServiceType.NIGHTLIFE,
        ServiceType.PHARMACY_RETAIL,
        ServiceType.HARDWARE_RETAIL,
        ServiceType.OFFICE_SPACE,
        ServiceType.INDUSTRIAL_SPACE,
        ServiceType.COMMUNITY_FACILITIES,
        ServiceType.TRANSPORT,
        ServiceType.LEISURE_SPORTS,
        ServiceType.TRADES_SERVICES -> null
    }

    private data class DevSpec(val buildingType: BuildingType, val capacity: Int, val cost: Double)

    private fun developmentSpec(type: DevelopmentType): DevSpec = when (type) {
        DevelopmentType.HOUSING_RESIDENTIAL -> DevSpec(BuildingType.TERRACE,          6,  25_000.0)
        DevelopmentType.HOUSING_FLATS       -> DevSpec(BuildingType.FLAT,            18,  60_000.0)
        DevelopmentType.COMMERCIAL_RETAIL   -> DevSpec(BuildingType.GROCER,           3,  20_000.0)
        DevelopmentType.INDUSTRIAL          -> DevSpec(BuildingType.WORKSHOP,         4,  30_000.0)
        DevelopmentType.SCHOOL              -> DevSpec(BuildingType.SCHOOL,           4,  80_000.0)
        DevelopmentType.HEALTHCARE_CLINIC   -> DevSpec(BuildingType.CLINIC,           4,  70_000.0)
        DevelopmentType.POLICE_STATION      -> DevSpec(BuildingType.POLICE_STATION,   6,  55_000.0)
        DevelopmentType.FIRE_STATION        -> DevSpec(BuildingType.FIRE_STATION,     6,  50_000.0)
        DevelopmentType.COMMUNITY_CENTRE    -> DevSpec(BuildingType.COMMUNITY_CENTRE, 4,  35_000.0)
        DevelopmentType.SPORTS_HALL         -> DevSpec(BuildingType.SPORTS_HALL,      4,  45_000.0)
        DevelopmentType.PARK                -> DevSpec(BuildingType.PARK,            50,  10_000.0)
    }
}
