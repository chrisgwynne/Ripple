package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.TownEra
import com.ripple.town.core.model.TownEraType
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/**
 * Declares and manages town-wide historical eras — defining periods that residents and
 * the newspaper continue to reference long after they end.
 *
 * Eras are triggered inline from the systems that produce their key events:
 *   WEATHER_DAMAGE (high severity) → GREAT_FLOOD
 *   ARSON_ATTEMPT (high severity)  → GREAT_FIRE
 *   ELECTION_WON (low trust)       → POLITICAL_UPHEAVAL
 *   Multiple simultaneous deaths   → EPIDEMIC (checked daily)
 *   Business closures dominating   → ECONOMIC_COLLAPSE (checked daily)
 *
 * Only three eras may be active simultaneously.  [collectiveMemoryStrength] decays slowly
 * after an era ends, reaching a floor of 10 so old eras are never entirely forgotten.
 */
object TownEraSystem {

    private const val MAX_ACTIVE_ERAS = 3
    private const val MEMORY_FLOOR = 10.0
    private const val MEMORY_DECAY_PER_YEAR = 1.5   // strength units lost per simulated year after close

    // ---- Inline triggers -----------------------------------------------------------------------

    fun considerEra(ctx: TickContext, event: WorldEvent) {
        when (event.type) {
            EventType.WEATHER_DAMAGE -> {
                if (event.severity >= 0.65) {
                    val yearLabel = SimTime.year(ctx.now)
                    maybeSpawnEra(ctx, TownEraType.GREAT_FLOOD,
                        "The Great Flood of Year $yearLabel",
                        "The year the river overwhelmed the town. Nothing was quite the same after.",
                        event.id)
                }
            }
            EventType.ARSON_ATTEMPT -> {
                if (event.severity >= 0.65) {
                    val yearLabel = SimTime.year(ctx.now)
                    maybeSpawnEra(ctx, TownEraType.GREAT_FIRE,
                        "The Fire of Year $yearLabel",
                        "The night the flames came. People woke to smoke and shouting in the street.",
                        event.id)
                }
            }
            EventType.ELECTION_WON -> {
                val trust = ctx.state.townSentiment.trust
                if (trust < 28.0) {
                    val mayor = event.sourceResidentId?.let { ctx.state.resident(it) }
                    val surnamePart = if (mayor != null) "the ${mayor.surname}" else "the disputed"
                    maybeSpawnEra(ctx, TownEraType.POLITICAL_UPHEAVAL,
                        "The ${mayor?.surname ?: "Disputed"} Years",
                        "The election that divided the town. $surnamePart administration did not begin quietly.",
                        event.id)
                }
            }
            else -> Unit
        }
    }

    private fun maybeSpawnEra(
        ctx: TickContext, type: TownEraType, name: String, description: String, triggerEventId: Long
    ) {
        val state = ctx.state
        // Don't duplicate an already-open era of the same type
        if (state.townEras.any { it.type == type && it.endedAt == null }) return
        // Cap active eras
        if (state.townEras.count { it.endedAt == null } >= MAX_ACTIVE_ERAS) return
        state.townEras += TownEra(
            id = state.nextEraId++, type = type, name = name, description = description,
            startedAt = ctx.now, triggerEventId = triggerEventId
        )
    }

    // ---- Daily update --------------------------------------------------------------------------

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state

        // Decay collective memory of closed eras
        for (era in state.townEras) {
            val ended = era.endedAt ?: continue
            val yearsSinceEnd = (ctx.now - ended).toDouble() / SimTime.MINUTES_PER_YEAR
            era.collectiveMemoryStrength = (100.0 - yearsSinceEnd * MEMORY_DECAY_PER_YEAR).coerceAtLeast(MEMORY_FLOOR)
        }

        // Close a CRIME_WAVE era when safety improves
        for (era in state.townEras.filter { it.type == TownEraType.CRIME_WAVE && it.endedAt == null }) {
            if (state.townSentiment.safety > 62.0) era.endedAt = ctx.now
        }

        // Close an ECONOMIC_COLLAPSE era when optimism recovers
        for (era in state.townEras.filter { it.type == TownEraType.ECONOMIC_COLLAPSE && it.endedAt == null }) {
            if (state.townSentiment.optimism > 55.0) era.endedAt = ctx.now
        }

        // Close an EPIDEMIC era when general health recovers
        for (era in state.townEras.filter { it.type == TownEraType.EPIDEMIC && it.endedAt == null }) {
            if (state.townState.healthIndex > 65.0) era.endedAt = ctx.now
        }

        // Close a DYNASTY era when the ruling family no longer holds the mayoralty
        for (era in state.townEras.filter { it.type == TownEraType.DYNASTY && it.endedAt == null }) {
            val dynastySurname = era.name.removePrefix("The ").removeSuffix(" Dynasty")
            val currentSurname = state.residents[state.mayorId]?.surname
            if (currentSurname != dynastySurname) era.endedAt = ctx.now
        }

        // Detect widespread illness → epidemic
        val avgHealth = if (state.residents.isEmpty()) 100.0 else
            state.livingResidents().map { it.needs.health }.average()
        if (avgHealth < 45.0) {
            val yearLabel = SimTime.year(ctx.now)
            maybeSpawnEra(ctx, TownEraType.EPIDEMIC,
                "The Sickness Year $yearLabel",
                "Disease spread from house to house. Those who could help did; those who couldn't stayed indoors and hoped.",
                state.recentEventIds.lastOrNull() ?: 0L)
        }

        // Detect ruling dynasty (same family holds mayoralty across 2+ terms)
        val mayorSurname = state.residents[state.mayorId]?.surname
        if (mayorSurname != null) {
            val legacy = state.familyLegacies[mayorSurname]
            if (legacy != null && legacy.mayorships >= 2) {
                maybeSpawnEra(ctx, TownEraType.DYNASTY,
                    "The $mayorSurname Dynasty",
                    "The ${mayorSurname} family has cast a long shadow over ${state.townName}'s politics — mayor after mayor bearing the same name.",
                    state.recentEventIds.lastOrNull() ?: 0L)
            }
        }

        // Detect sustained crime wave
        if (state.townSentiment.safety < 25.0) {
            maybeSpawnEra(ctx, TownEraType.CRIME_WAVE,
                "The Troubled Times",
                "A period when crime had risen so far that residents stopped feeling safe in their own streets.",
                state.recentEventIds.lastOrNull() ?: 0L)
        }

        // Detect economic collapse
        val openBusinessCount = state.businesses.values.count { it.open }
        val recentClosures = state.businesses.values.count { !it.open && it.closedAt != null &&
            state.time - (it.closedAt ?: 0L) < 90L * SimTime.MINUTES_PER_DAY }
        if (openBusinessCount > 0 && recentClosures.toDouble() / (openBusinessCount + recentClosures) > 0.4) {
            maybeSpawnEra(ctx, TownEraType.ECONOMIC_COLLAPSE,
                "The Hard Years",
                "A time when the high street emptied and familiar names closed their doors one by one.",
                state.recentEventIds.lastOrNull() ?: 0L)
        }
    }

    /** The most recent active era, for newspaper and chronicle reference. */
    fun currentEra(state: WorldState): TownEra? =
        state.townEras.filter { it.endedAt == null }.maxByOrNull { it.startedAt }

    /** The most strongly-remembered closed era, for historical references. */
    fun mostMemorableEra(state: WorldState): TownEra? =
        state.townEras.filter { it.endedAt != null }.maxByOrNull { it.collectiveMemoryStrength }
}
