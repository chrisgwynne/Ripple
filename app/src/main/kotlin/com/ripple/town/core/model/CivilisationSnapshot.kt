package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * An annual snapshot of the town's civilisation-level metrics. Taken once per sim year
 * by [com.ripple.town.core.simulation.TownStateSystem]. Stored in
 * [WorldState.civilisationSnapshots] capped at 200 entries.
 *
 * Used for trend lines, historical comparisons, and the player's "100-year chronicle" view.
 */
@Serializable
data class CivilisationSnapshot(
    val id: Long,
    val takenAt: Long,          // sim minutes
    val simYear: Int,
    val population: Int,
    val prosperityIndex: Double,
    val crimeIndex: Double,
    val educationLevel: Double,
    val politicalStability: Double,
    val communitySpirit: Double,
    val employmentRate: Double,
    val incomeInequality: Double,
    val dominantArchetype: String?,
    val activeBusinessCount: Int,
    val institutionCount: Int,
    /** Short summaries of notable events that occurred during this year. */
    val notableEventSummaries: List<String> = emptyList()
)
