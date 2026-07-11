package com.ripple.town.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LifeSatisfaction(
    var family: Double = 50.0,
    var career: Double = 50.0,
    var purpose: Double = 50.0,
    var community: Double = 50.0,
    var legacy: Double = 50.0,
    var health: Double = 50.0
) {
    fun overall(): Double = (family + career + purpose + community + legacy + health) / 6.0

    fun clampAll() {
        family = family.coerceIn(0.0, 100.0)
        career = career.coerceIn(0.0, 100.0)
        purpose = purpose.coerceIn(0.0, 100.0)
        community = community.coerceIn(0.0, 100.0)
        legacy = legacy.coerceIn(0.0, 100.0)
        health = health.coerceIn(0.0, 100.0)
    }
}
