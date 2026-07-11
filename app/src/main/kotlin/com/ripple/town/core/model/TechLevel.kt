package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class TechEraName(val label: String) {
    EARLY("Early days"),
    ESTABLISHED("Established town"),
    MECHANISED("Age of industry"),
    MODERN("Modern era")
}

@Serializable
data class TechLevel(
    var era: TechEraName = TechEraName.EARLY,
    var innovationPoints: Double = 0.0,   // accumulate from high-skill residents
    var productivityBonus: Double = 1.0,  // multiplier on business revenue
    var lastAdvancedAt: Long = 0L
)
