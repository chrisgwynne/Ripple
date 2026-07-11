package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * A named moment in the town's history — the kind of turning point residents reference
 * for decades: "The Mill Closure", "The Housing Boom", "The Corruption Scandal".
 *
 * Distinct from [TownEra] (which tracks ongoing state-transition periods like GOLDEN_AGE);
 * these are specific, titled, memorable events that accumulate into collective memory and
 * decay slowly as living witnesses die off.
 *
 * Created and maintained by [com.ripple.town.core.simulation.CivilisationHistorySystem].
 */
@Serializable
data class HistoricalMilestone(
    val id: Long,
    val title: String,
    val description: String,
    /** [HistoricalMilestoneType] name. Stored as String for forward-compat. */
    val type: String,
    val occurredAt: Long,           // sim minutes
    /** Subjective importance 0–1. Used to weight which events rise into the town's narrative. */
    val impactScore: Double,
    val relatedResidentIds: List<Long> = emptyList(),
    val relatedBuildingId: Long? = null,
    val relatedDistrictId: Long? = null,
    val causeEventId: Long? = null,
    /**
     * Starts at 100; decays [MEMORY_DECAY_PER_YEAR] points per sim-year as living witnesses
     * die off, bottoming at 5 (history is never fully forgotten — it becomes legend).
     */
    var collectiveMemoryStrength: Double = 100.0,
    /** True if this milestone is now referenced as part of town identity (high impact + old). */
    var isLandmark: Boolean = false
) {
    companion object {
        const val MEMORY_DECAY_PER_YEAR = 3.0
        const val MEMORY_FLOOR = 5.0
    }
}

enum class HistoricalMilestoneType(val label: String) {
    NATURAL_DISASTER("Natural Disaster"),
    INDUSTRIAL_COLLAPSE("Industrial Collapse"),
    ECONOMIC_BOOM("Economic Boom"),
    ECONOMIC_COLLAPSE("Economic Collapse"),
    POLITICAL_SCANDAL("Political Scandal"),
    GOLDEN_PERIOD("Golden Period"),
    DARK_PERIOD("Dark Period"),
    INSTITUTIONAL_FOUNDING("Institutional Founding"),
    MAJOR_DEVELOPMENT("Major Development"),
    POPULATION_SURGE("Population Surge"),
    POPULATION_DECLINE("Population Decline"),
    CULTURAL_SHIFT("Cultural Shift"),
    CRISIS_OVERCOME("Crisis Overcome"),
    FAMOUS_PERSON("Famous Person"),
    DEFINING_CRIME("Defining Crime")
}
