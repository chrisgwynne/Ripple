package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DevelopmentProject
import com.ripple.town.core.model.DevelopmentStage
import com.ripple.town.core.model.DevelopmentType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.ServiceType

/**
 * Scans vacant buildings monthly and proposes conversion projects when a service gap
 * matches an eligible target use for the building's structural type.
 *
 * Conversion compatibility: the physical shell of a building constrains what it can
 * become — a pub kitchen adapts to a café or restaurant; warehouse floor-plates suit
 * workshops or factories; residential hotels become flats. The system checks whether
 * a ServiceType shortage exists (satisfactionScore < 0.7) that the target use would
 * address, then creates a PROPOSED [DevelopmentProject] with the building already
 * pre-assigned via [DevelopmentProject.buildingId].
 *
 * Capped at 2 proposals per month to avoid flooding the pipeline.
 *
 * Completion hook: [DevelopmentSystem.advanceConstruction] detects projects whose note
 * starts with "Conversion" and updates [Building.type] plus closes the old business.
 */
class BuildingConversionSystem {

    companion object {
        /** Conversion compatibility: source BuildingType → eligible target BuildingTypes. */
        private val VALID_CONVERSIONS: Map<BuildingType, List<BuildingType>> = mapOf(
            BuildingType.PUB        to listOf(BuildingType.RESTAURANT, BuildingType.CAFE),
            BuildingType.OFFICE     to listOf(BuildingType.FLAT,       BuildingType.WORKSHOP),
            BuildingType.WAREHOUSE  to listOf(BuildingType.WORKSHOP,   BuildingType.FACTORY,  BuildingType.COMMUNITY_CENTRE),
            BuildingType.WORKSHOP   to listOf(BuildingType.CAFE,       BuildingType.OFFICE),
            BuildingType.GROCER     to listOf(BuildingType.PHARMACY,   BuildingType.CAFE,     BuildingType.BAKERY),
            BuildingType.HOTEL      to listOf(BuildingType.FLAT,       BuildingType.COMMUNITY_CENTRE),
            BuildingType.SCHOOL     to listOf(BuildingType.COMMUNITY_CENTRE, BuildingType.LIBRARY),
            BuildingType.CAFE       to listOf(BuildingType.RESTAURANT, BuildingType.BAKERY),
            BuildingType.BOOKSHOP   to listOf(BuildingType.CAFE,       BuildingType.OFFICE),
        )

        private const val CONVERSION_COST_PER_CAPACITY = 2_000.0
        private const val MIN_BUILDING_CONDITION_FOR_CONVERSION = 35.0
        private const val MAX_PROPOSALS_PER_MONTH = 2
    }

    fun updateMonthly(ctx: TickContext) {
        var proposalsCreated = 0

        for (building in ctx.state.buildings.values) {
            if (proposalsCreated >= MAX_PROPOSALS_PER_MONTH) break

            if (building.buildingState != BuildingState.VACANT) continue
            if (building.condition < MIN_BUILDING_CONDITION_FOR_CONVERSION) continue

            val eligibleTargets = VALID_CONVERSIONS[building.type] ?: continue

            // Skip if an active project already references this building.
            val alreadyClaimed = ctx.state.developmentProjects.values.any { p ->
                p.buildingId == building.id &&
                p.stage != DevelopmentStage.COMPLETE &&
                p.stage != DevelopmentStage.REJECTED &&
                p.stage != DevelopmentStage.CANCELLED
            }
            if (alreadyClaimed) continue

            for (targetType in eligibleTargets) {
                if (proposalsCreated >= MAX_PROPOSALS_PER_MONTH) break

                // Is there a real shortage for the service this target type supplies?
                val serviceType = buildingTypeToServiceType(targetType) ?: continue
                val pressure = ctx.state.servicePressures[serviceType.name] ?: continue
                if (pressure.satisfactionScore >= 0.7) continue

                val devType = buildingTypeToDevelopmentType(targetType) ?: continue
                val estimatedCost = (building.capacity * CONVERSION_COST_PER_CAPACITY)
                    .coerceAtLeast(5_000.0)

                val proj = DevelopmentProject(
                    id = ctx.state.nextProjectId++,
                    type = devType,
                    districtId = building.districtId,
                    tileX = building.origin.x,
                    tileY = building.origin.y,
                    buildingType = targetType,
                    capacity = building.capacity,
                    estimatedCost = estimatedCost,
                    stage = DevelopmentStage.PROPOSED,
                    createdAt = ctx.now,
                    stageChangedAt = ctx.now,
                    buildingId = building.id,
                    note = "Conversion of vacant ${building.type.label} to ${targetType.label}"
                )
                ctx.state.developmentProjects[proj.id] = proj

                ctx.emit(
                    EventType.DEVELOPMENT_PROPOSED,
                    "A proposal has been made to convert the vacant ${building.type.label.lowercase()} into a ${targetType.label.lowercase()}.",
                    buildingId = building.id,
                    severity = 0.4,
                    visibility = EventVisibility.PUBLIC
                )

                proposalsCreated++
                break  // one conversion proposal per building per month
            }
        }
    }

    /**
     * Maps a target [BuildingType] to the [ServiceType] that captures whether that use
     * is in shortage. Only the 8 service types actively tracked by [TownNeedsPlanner]
     * (and stored in [WorldState.servicePressures]) are tested — Phase 6 extended types
     * are not yet measured and would always return null, producing no conversions.
     */
    private fun buildingTypeToServiceType(type: BuildingType): ServiceType? = when (type) {
        BuildingType.FLAT,
        BuildingType.TERRACE,
        BuildingType.HOUSE          -> ServiceType.HOUSING
        BuildingType.SCHOOL         -> ServiceType.SCHOOL
        BuildingType.CLINIC         -> ServiceType.HEALTHCARE
        BuildingType.COMMUNITY_CENTRE,
        BuildingType.LIBRARY        -> ServiceType.PARKS       // civic leisure pressure proxy
        BuildingType.CAFE,
        BuildingType.RESTAURANT,
        BuildingType.BAKERY,
        BuildingType.GROCER,
        BuildingType.PHARMACY       -> ServiceType.RETAIL
        BuildingType.WORKSHOP,
        BuildingType.FACTORY,
        BuildingType.OFFICE         -> ServiceType.EMPLOYMENT
        else                        -> null
    }

    /**
     * Maps a target [BuildingType] to the nearest [DevelopmentType] so the project
     * can flow through [DevelopmentSystem]'s standard pipeline.
     */
    private fun buildingTypeToDevelopmentType(type: BuildingType): DevelopmentType? = when (type) {
        BuildingType.FLAT,
        BuildingType.TERRACE,
        BuildingType.HOUSE          -> DevelopmentType.HOUSING_RESIDENTIAL
        BuildingType.SCHOOL         -> DevelopmentType.SCHOOL
        BuildingType.CLINIC         -> DevelopmentType.HEALTHCARE_CLINIC
        BuildingType.COMMUNITY_CENTRE,
        BuildingType.LIBRARY        -> DevelopmentType.COMMUNITY_CENTRE
        BuildingType.WORKSHOP,
        BuildingType.FACTORY        -> DevelopmentType.INDUSTRIAL
        BuildingType.CAFE,
        BuildingType.RESTAURANT,
        BuildingType.BAKERY,
        BuildingType.GROCER,
        BuildingType.PHARMACY,
        BuildingType.OFFICE         -> DevelopmentType.COMMERCIAL_RETAIL
        else                        -> null
    }
}
