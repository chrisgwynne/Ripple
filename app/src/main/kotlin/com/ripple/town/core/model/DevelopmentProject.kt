package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * The pipeline stage a [DevelopmentProject] is currently in.
 * Advances deterministically via [com.ripple.town.core.simulation.DevelopmentSystem].
 */
enum class DevelopmentStage(val label: String) {
    PROPOSED("Proposed"),
    APPROVED("Approved"),
    REJECTED("Rejected"),
    FUNDED("Funded"),
    CONSTRUCTION("Under construction"),
    COMPLETE("Complete"),
    CANCELLED("Cancelled")
}

/**
 * The category of development — used by [TownNeedsPlanner] to match a service
 * deficit to an appropriate [BuildingType] and cost estimate.
 */
enum class DevelopmentType(val label: String) {
    HOUSING_RESIDENTIAL("Residential housing"),
    HOUSING_FLATS("Apartment block"),
    COMMERCIAL_RETAIL("Retail premises"),
    INDUSTRIAL("Industrial building"),
    SCHOOL("New school"),
    HEALTHCARE_CLINIC("Medical clinic"),
    POLICE_STATION("Police station"),
    FIRE_STATION("Fire station"),
    COMMUNITY_CENTRE("Community centre"),
    SPORTS_HALL("Sports hall"),
    PARK("Public park")
}

/**
 * A single entry in the town's development pipeline — from a need being identified
 * through planning, funding, construction, and eventual operation. Created by
 * [com.ripple.town.core.simulation.TownNeedsPlanner]; advanced by
 * [com.ripple.town.core.simulation.DevelopmentSystem].
 */
@Serializable
data class DevelopmentProject(
    val id: Long,
    val type: DevelopmentType,
    val districtId: Long?,
    /** Tile-space position of the top-left corner — set when FUNDED moves to CONSTRUCTION. */
    var tileX: Int,
    var tileY: Int,
    val buildingType: BuildingType,
    val capacity: Int,
    val estimatedCost: Double,
    var stage: DevelopmentStage,
    val createdAt: Long,
    var stageChangedAt: Long,
    var fundedAmount: Double = 0.0,
    /** The Building entity created once construction starts. */
    var buildingId: Long? = null,
    val note: String = ""
)
