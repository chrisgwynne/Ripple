package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class RouteCondition(val label: String) {
    EXCELLENT("Excellent"), GOOD("Good"), FAIR("Fair"), POOR("Poor"), IMPASSABLE("Impassable")
}

@Serializable
data class TransportRoute(
    val id: Long,
    val fromBuildingId: Long,
    val toBuildingId: Long,
    var condition: RouteCondition = RouteCondition.GOOD,
    var degradeRate: Double = 0.1,     // condition points lost per year
    var lastRepairedAt: Long = 0L,
    var footfallMultiplier: Double = 1.0   // applied to connected business demand
)
