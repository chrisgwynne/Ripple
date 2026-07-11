package com.ripple.town.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TownEnvironment(
    var pollution: Double = 20.0,       // 0..100 — raised by FACTORY/WORKSHOP, lowered by PARK
    var greenery: Double = 60.0,        // 0..100 — PARK buildings + open land
    var floodRisk: Double = 30.0,       // 0..100 — raised after repeated flood events
    var overallHealth: Double = 70.0    // derived; affects resident health needs
) {
    fun recalculate() {
        overallHealth = (greenery - pollution * 0.5 + (100.0 - floodRisk) * 0.3).coerceIn(0.0, 100.0)
    }
}
