package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class TownEraType(val label: String) {
    GREAT_FLOOD("The Great Flood"),
    EPIDEMIC("The Sickness Year"),
    CRIME_WAVE("The Troubled Times"),
    ECONOMIC_COLLAPSE("The Hard Years"),
    GOLDEN_AGE("A Golden Age"),
    GREAT_FIRE("The Fire"),
    POLITICAL_UPHEAVAL("The Political Storm"),
    FOUNDING_ERA("The Early Years")
}

/**
 * A defining period in the town's collective memory — a time so significant that generations
 * of residents still reference it.  Eras begin from triggering events and persist long after
 * they end, their [collectiveMemoryStrength] fading slowly as decades pass and the last
 * living witnesses die.
 *
 * Unlike individual [com.ripple.town.core.model.LocalLegend]s (which are beliefs about
 * specific places or people), an era is something the whole town *shares*: "everyone
 * remembers the great flood" is a different kind of story from "don't go near the mill at
 * night."  Both are emergent; neither is scripted.
 *
 * Managed by [com.ripple.town.core.simulation.TownEraSystem].
 */
@Serializable
data class TownEra(
    val id: Long,
    val type: TownEraType,
    val name: String,               // e.g. "The Great Flood of Year 12"
    val description: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    val triggerEventId: Long,
    /** 0..100. Decays after the era ends as living memory fades. */
    var collectiveMemoryStrength: Double = 100.0
)
