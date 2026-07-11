package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * A category of statistically unusual pattern that [com.ripple.town.core.simulation.AnomalyDetector]
 * monitors.  Anomalies are recognised, never manufactured: the detector scans world state
 * monthly and records what it finds.  It never generates the conditions it's looking for.
 */
enum class AnomalyType(val label: String) {
    NEVER_MARRIED("Lifelong single"),
    GENERATIONAL_BUSINESS("Business dynasty"),
    MIRACULOUS_SURVIVOR("Unlikely survivor"),
    LONGEVITY_CLUSTER("Long-lived household"),
    UNIVERSAL_FRIEND("Known to everyone"),
    OMNIPRESENT_WITNESS("Always present"),
    FAMILY_DOMINANCE("Governing family"),
    GENERATION_DYNASTY("Inherited calling"),
    UNLUCKY_LOCATION("Troubled address"),
    PROLIFIC_DOCTOR("The town's healer")
}

/**
 * A single detected anomaly: the type, a plain-language description suitable for the
 * newspaper, and which residents/buildings are involved.  The [metric] is the underlying
 * number that triggered detection (age, count, years, etc.) — preserved for display
 * without being surfaced noisily in-game.
 *
 * Anomaly records are append-only in [WorldState.anomalyRecords]; the detector's
 * deduplication logic prevents re-recording the same anomaly for the same subject.
 * The result, over decades, is a quiet archive of the unusual: half the town glanced
 * at, filed, and mostly forgotten — exactly as real local history works.
 */
@Serializable
data class AnomalyRecord(
    val id: Long,
    val type: AnomalyType,
    val description: String,
    val detectedAt: Long,
    val relatedResidentIds: List<Long> = emptyList(),
    val relatedBuildingIds: List<Long> = emptyList(),
    /** The underlying statistic that triggered detection. */
    val metric: Double = 0.0
)
