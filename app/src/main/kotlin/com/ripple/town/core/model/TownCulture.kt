package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class CulturalDimension(val label: String) {
    ENTREPRENEURIAL("entrepreneurial"),
    WORKING_CLASS("working-class"),
    ACADEMIC("academic"),
    ARTISTIC("artistic"),
    POLITICAL("politically active"),
    DIVERSE("diverse"),
    TIGHT_KNIT("tight-knit"),
    TROUBLED("troubled"),
    SAFE("safe and settled")
}

@Serializable
data class TownCulture(
    val dimensions: MutableSet<CulturalDimension> = mutableSetOf(),
    var lastUpdatedAt: Long = 0L
) {
    fun has(dim: CulturalDimension): Boolean = dim in dimensions

    fun describe(): String = when {
        dimensions.isEmpty() -> "a quiet, unremarkable place"
        dimensions.size == 1 -> "a ${dimensions.first().label} community"
        else -> dimensions.take(3).joinToString(", ") { it.label } + " — a community with a distinct character"
    }
}
