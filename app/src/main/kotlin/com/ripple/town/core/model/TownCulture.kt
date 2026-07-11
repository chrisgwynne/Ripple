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

/**
 * A single snapshot of the town's cultural identity at a point in time — stored in
 * [WorldState.townCultureHistory] so the town accumulates a persistent character trajectory
 * over decades rather than only ever showing the current moment.
 *
 * [tick] is the sim-minute timestamp of the snapshot. [dimensions] is the full set of
 * [CulturalDimension] values active at that moment. [description] is the human-readable
 * sentence produced by [TownCulture.describe()] at snapshot time, cached here so the UI
 * never has to re-derive it from an archived set.
 */
@Serializable
data class TownCultureRecord(
    val tick: Long,
    val dimensions: Set<CulturalDimension>,
    val description: String
)
