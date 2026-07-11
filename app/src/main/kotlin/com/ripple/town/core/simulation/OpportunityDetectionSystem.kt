package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.Opportunity
import com.ripple.town.core.model.OpportunityStatus
import com.ripple.town.core.model.OpportunityType
import com.ripple.town.core.model.ServiceType
import com.ripple.town.core.model.SimTime

/**
 * Monthly scan: reads [WorldState.servicePressures] for severe shortfalls
 * (satisfactionScore < 0.5) and converts them into [Opportunity] records that
 * entrepreneurs can claim and act on. Also flags long-idle vacant commercial
 * buildings as VACANT_PREMISES_REUSE opportunities.
 *
 * Called at the end of [TownNeedsPlanner.updateMonthly] so the pressures it reads
 * are always freshly computed in the same monthly tick.
 */
object OpportunityDetectionSystem {

    private const val SHORTAGE_THRESHOLD = 0.5
    private const val MAX_OPEN_OPPORTUNITIES = 20
    private const val VACANT_IDLE_DAYS = 30L
    private const val OPPORTUNITY_LIFE_DAYS = 90L   // how long an opportunity stays OPEN

    // Mapping from ServiceType to OpportunityType for shortage detection
    private val serviceToOpportunity: Map<ServiceType, OpportunityType> = mapOf(
        ServiceType.FOOD_RETAIL          to OpportunityType.FOOD_RETAIL_SHORTAGE,
        ServiceType.CONVENIENCE_RETAIL   to OpportunityType.CONVENIENCE_SHORTAGE,
        ServiceType.CAFE_DINING          to OpportunityType.CAFE_SHORTAGE,
        ServiceType.RESTAURANT_DINING    to OpportunityType.RESTAURANT_SHORTAGE,
        ServiceType.PHARMACY_RETAIL      to OpportunityType.PHARMACY_SHORTAGE,
        ServiceType.HARDWARE_RETAIL      to OpportunityType.HARDWARE_SHORTAGE,
        ServiceType.CHILDCARE            to OpportunityType.CHILDCARE_SHORTAGE,
        ServiceType.HEALTHCARE           to OpportunityType.HEALTHCARE_SHORTAGE,
        ServiceType.ELDERLY_CARE         to OpportunityType.ELDERLY_CARE_SHORTAGE,
        ServiceType.SCHOOL               to OpportunityType.SCHOOL_SHORTAGE,
        ServiceType.COMMUNITY_FACILITIES to OpportunityType.COMMUNITY_SPACE_SHORTAGE,
        ServiceType.INDUSTRIAL_SPACE     to OpportunityType.INDUSTRIAL_DEMAND,
        ServiceType.OFFICE_SPACE         to OpportunityType.OFFICE_DEMAND,
        ServiceType.TRADES_SERVICES      to OpportunityType.TRADES_DEMAND
    )

    // Rough estimated capital required per opportunity type
    private val capitalEstimate: Map<OpportunityType, Double> = mapOf(
        OpportunityType.FOOD_RETAIL_SHORTAGE      to 15_000.0,
        OpportunityType.CONVENIENCE_SHORTAGE      to  8_000.0,
        OpportunityType.CAFE_SHORTAGE             to  8_000.0,
        OpportunityType.RESTAURANT_SHORTAGE       to 12_000.0,
        OpportunityType.PHARMACY_SHORTAGE         to 12_000.0,
        OpportunityType.HARDWARE_SHORTAGE         to 12_000.0,
        OpportunityType.CHILDCARE_SHORTAGE        to 20_000.0,
        OpportunityType.HEALTHCARE_SHORTAGE       to 30_000.0,
        OpportunityType.ELDERLY_CARE_SHORTAGE     to 35_000.0,
        OpportunityType.SCHOOL_SHORTAGE           to 80_000.0,
        OpportunityType.COMMUNITY_SPACE_SHORTAGE  to 30_000.0,
        OpportunityType.INDUSTRIAL_DEMAND         to 25_000.0,
        OpportunityType.OFFICE_DEMAND             to 18_000.0,
        OpportunityType.TRADES_DEMAND             to 10_000.0,
        OpportunityType.VACANT_PREMISES_REUSE     to  5_000.0
    )

    // Which BuildingTypes are suitable for each opportunity type
    private val suitableBuildings: Map<OpportunityType, Set<BuildingType>> = mapOf(
        OpportunityType.FOOD_RETAIL_SHORTAGE      to setOf(BuildingType.GROCER, BuildingType.SUPERMARKET, BuildingType.VACANT),
        OpportunityType.CONVENIENCE_SHORTAGE      to setOf(BuildingType.GROCER, BuildingType.PHARMACY, BuildingType.VACANT),
        OpportunityType.CAFE_SHORTAGE             to setOf(BuildingType.CAFE, BuildingType.BAKERY, BuildingType.VACANT),
        OpportunityType.RESTAURANT_SHORTAGE       to setOf(BuildingType.RESTAURANT, BuildingType.PUB, BuildingType.TAKEAWAY, BuildingType.VACANT),
        OpportunityType.PHARMACY_SHORTAGE         to setOf(BuildingType.PHARMACY, BuildingType.VACANT),
        OpportunityType.HARDWARE_SHORTAGE         to setOf(BuildingType.HARDWARE, BuildingType.VACANT),
        OpportunityType.CHILDCARE_SHORTAGE        to setOf(BuildingType.NURSERY, BuildingType.VACANT),
        OpportunityType.HEALTHCARE_SHORTAGE       to setOf(BuildingType.CLINIC, BuildingType.HOSPITAL, BuildingType.VACANT),
        OpportunityType.ELDERLY_CARE_SHORTAGE     to setOf(BuildingType.CLINIC, BuildingType.HOSPITAL, BuildingType.VACANT),
        OpportunityType.SCHOOL_SHORTAGE           to setOf(BuildingType.SCHOOL, BuildingType.VACANT),
        OpportunityType.COMMUNITY_SPACE_SHORTAGE  to setOf(BuildingType.COMMUNITY_CENTRE, BuildingType.SPORTS_HALL, BuildingType.LIBRARY, BuildingType.VACANT),
        OpportunityType.INDUSTRIAL_DEMAND         to setOf(BuildingType.FACTORY, BuildingType.WAREHOUSE, BuildingType.WORKSHOP, BuildingType.VACANT),
        OpportunityType.OFFICE_DEMAND             to setOf(BuildingType.OFFICE, BuildingType.VACANT),
        OpportunityType.TRADES_DEMAND             to setOf(BuildingType.WORKSHOP, BuildingType.GARAGE, BuildingType.VACANT)
    )

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val now = ctx.now

        // 1. Expire and clean up stale opportunities
        val toRemove = state.opportunities.entries
            .filter { (_, opp) ->
                opp.expiresAt < now || opp.status != OpportunityStatus.OPEN
            }
            .map { it.key }
        toRemove.forEach { state.opportunities.remove(it) }

        // Snapshot of currently open opportunity types to avoid duplicates
        val openTypes = state.opportunities.values
            .filter { it.status == OpportunityStatus.OPEN }
            .map { it.type }
            .toSet()

        val expiresAt = now + OPPORTUNITY_LIFE_DAYS * SimTime.MINUTES_PER_DAY

        // 2. Scan service pressures for severe shortfalls
        for ((serviceType, oppType) in serviceToOpportunity) {
            val pressure = state.servicePressures[serviceType.name] ?: continue
            if (pressure.satisfactionScore >= SHORTAGE_THRESHOLD) continue
            if (oppType in openTypes) continue
            if (state.opportunities.size >= MAX_OPEN_OPPORTUNITIES) break

            // Find suitable vacant buildings
            val suitable = suitableBuildings[oppType] ?: emptySet()
            val vacantBuildings = state.buildings.values.filter { b ->
                b.buildingState == BuildingState.VACANT &&
                b.type in suitable &&
                !b.abandoned
            }

            val deficit = pressure.deficit
            val capital = capitalEstimate[oppType] ?: 10_000.0
            val weeklyDemand = ((pressure.demandUnits - pressure.capacityUnits).coerceAtLeast(0))
            val annualRevenue = capital * 0.4  // rough 40% annual return estimate

            val districtId = vacantBuildings.firstNotNullOfOrNull { it.districtId }
                ?: state.buildings.values.firstOrNull()?.districtId

            val districtName = districtId?.let { state.districts[it]?.name } ?: state.townName

            val opp = Opportunity(
                id = state.nextOpportunityId,
                type = oppType,
                districtId = districtId,
                evidence = "Service satisfaction at ${(pressure.satisfactionScore * 100).toInt()}% — deficit of $deficit units.",
                expectedWeeklyDemand = weeklyDemand,
                estimatedAnnualRevenue = annualRevenue,
                estimatedCapitalRequired = capital,
                suitableBuildingIds = vacantBuildings.map { it.id },
                risks = "Demand may not sustain a new business if population falls.",
                detectedAt = now,
                expiresAt = expiresAt,
                status = OpportunityStatus.OPEN
            )
            state.opportunities[state.nextOpportunityId++] = opp

            val serviceName = serviceType.label.lowercase()
            ctx.emit(
                EventType.OPPORTUNITY_DETECTED,
                "Shortage of $serviceName detected in $districtName — an opportunity for a new business.",
                severity = 0.35,
                visibility = EventVisibility.PUBLIC
            )
        }

        // 3. Scan for long-idle vacant commercial buildings
        val vacantIdleThreshold = now - VACANT_IDLE_DAYS * SimTime.MINUTES_PER_DAY
        val commercialTypes = setOf(
            BuildingType.CAFE, BuildingType.RESTAURANT, BuildingType.PUB, BuildingType.TAKEAWAY,
            BuildingType.GROCER, BuildingType.SUPERMARKET, BuildingType.HARDWARE,
            BuildingType.BOOKSHOP, BuildingType.TAILOR, BuildingType.PHARMACY,
            BuildingType.WORKSHOP, BuildingType.OFFICE, BuildingType.HOTEL, BuildingType.GARAGE,
            BuildingType.FACTORY, BuildingType.WAREHOUSE, BuildingType.NIGHTCLUB, BuildingType.BAKERY,
            BuildingType.CINEMA
        )

        for (building in state.buildings.values) {
            if (state.opportunities.size >= MAX_OPEN_OPPORTUNITIES) break
            if (building.buildingState != BuildingState.VACANT) continue
            if (building.type !in commercialTypes) continue
            if (building.condition <= 50.0) continue
            val vacantSince = building.vacantSinceAt ?: continue
            if (vacantSince > vacantIdleThreshold) continue  // not idle long enough

            // Skip if we already have an open VACANT_PREMISES_REUSE opp for this building
            val alreadyCovered = state.opportunities.values.any { opp ->
                opp.type == OpportunityType.VACANT_PREMISES_REUSE &&
                opp.status == OpportunityStatus.OPEN &&
                building.id in opp.suitableBuildingIds
            }
            if (alreadyCovered) continue

            val districtName = building.districtId?.let { state.districts[it]?.name } ?: state.townName

            val opp = Opportunity(
                id = state.nextOpportunityId,
                type = OpportunityType.VACANT_PREMISES_REUSE,
                districtId = building.districtId,
                evidence = "${building.name} has been vacant for over $VACANT_IDLE_DAYS days and is in good condition.",
                expectedWeeklyDemand = 0,
                estimatedAnnualRevenue = 0.0,
                estimatedCapitalRequired = capitalEstimate[OpportunityType.VACANT_PREMISES_REUSE] ?: 5_000.0,
                suitableBuildingIds = listOf(building.id),
                risks = "Requires refitting for a new business type.",
                detectedAt = now,
                expiresAt = expiresAt,
                status = OpportunityStatus.OPEN
            )
            state.opportunities[state.nextOpportunityId++] = opp

            ctx.emit(
                EventType.OPPORTUNITY_DETECTED,
                "${building.name} in $districtName has been empty for over $VACANT_IDLE_DAYS days — a ready-to-use commercial premises.",
                severity = 0.25,
                visibility = EventVisibility.PUBLIC
            )
        }

        // 4. Cap total open opportunities at MAX_OPEN_OPPORTUNITIES
        val openOpportunities = state.opportunities.values
            .filter { it.status == OpportunityStatus.OPEN }
            .sortedBy { it.detectedAt }   // oldest first = lowest priority

        if (openOpportunities.size > MAX_OPEN_OPPORTUNITIES) {
            val excess = openOpportunities.take(openOpportunities.size - MAX_OPEN_OPPORTUNITIES)
            excess.forEach { state.opportunities.remove(it.id) }
        }
    }
}
