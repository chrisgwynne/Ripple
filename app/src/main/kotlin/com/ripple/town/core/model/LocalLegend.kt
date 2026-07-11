package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class LegendSubject(val label: String) {
    PLACE("place"),
    PERSON("person"),
    BUSINESS("business"),
    DISTRICT("district")
}

/**
 * A piece of local folklore — a belief about a place, person, or institution that spreads
 * through the population by word of mouth, fades if left unreinforced, and may never
 * perfectly reflect the event that spawned it.  Managed by [com.ripple.town.core.simulation.LegendSystem].
 *
 * Legends begin through coincidence: a crime at a building, a death too young, a business
 * that just keeps going.  They are not scripted, not supernatural, not quests.  They are
 * the town's collective memory of unexplained or repeated patterns, expressed as belief.
 */
@Serializable
data class LocalLegend(
    val id: Long,
    val subject: LegendSubject,
    /** The building/resident/business/district this legend is about; null if the subject
     *  no longer exists (demolished, dead, closed). */
    val subjectId: Long?,
    /** Persists even after the subject is gone, so old legends survive building demolition. */
    val subjectName: String,
    /** The text residents pass on — deliberately imprecise, as folk-memory always is. */
    var text: String,
    /** 0..100. Rises when reinforced by related events; decays daily when uneventful. */
    var strength: Double,
    val bornFromEventId: Long?,
    val createdAt: Long,
    var lastSpreadAt: Long,
    /** Rough count of current believers — used to scale spread probability. */
    var believerCount: Int = 0,
    /** Set when strength drops below the death threshold; used to skip this legend in updates. */
    var decayedAt: Long? = null
)
