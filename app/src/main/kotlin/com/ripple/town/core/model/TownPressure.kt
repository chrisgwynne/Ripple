package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * The eight dimensions of municipal service that [TownNeedsPlanner] tracks.
 * Each maps to one or more [BuildingType]s that supply capacity for that service.
 */
enum class ServiceType(val label: String) {
    HOUSING("Housing"),
    SCHOOL("Schools"),
    HEALTHCARE("Healthcare"),
    POLICE("Police"),
    FIRE("Fire service"),
    EMPLOYMENT("Employment"),
    RETAIL("Retail"),
    PARKS("Parks & leisure")
}

/**
 * Demand vs capacity snapshot for a single [ServiceType], recomputed monthly by
 * [com.ripple.town.core.simulation.TownNeedsPlanner]. A [satisfactionScore] below 0.7
 * flags the service as under pressure and may trigger a [DevelopmentProject] proposal.
 */
@Serializable
data class ServicePressure(
    val service: ServiceType,
    var demandUnits: Int,
    var capacityUnits: Int,
    var satisfactionScore: Double = 1.0
) {
    val deficit: Int get() = maxOf(0, demandUnits - capacityUnits)
    val isUnderPressure: Boolean get() = satisfactionScore < 0.7
}
