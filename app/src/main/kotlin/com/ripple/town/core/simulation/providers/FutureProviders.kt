package com.ripple.town.core.simulation.providers

import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/**
 * Placeholder seams for systems that arrive in later phases. The prototype
 * ships local no-op implementations; the engine only ever talks to the
 * interfaces so a backend, narrative model or news feed can slot in without
 * touching simulation code.
 *
 * Contract for all of these: they may DESCRIBE the world, they must never
 * MUTATE factual world state directly. Anything they suggest re-enters the
 * simulation as ordinary events/pressures that the engine validates.
 */

/** Supplies external (real-world) happenings mapped to abstract pressures. */
interface ExternalWorldEventProvider {
    suspend fun pendingPressures(sinceRealMs: Long): List<WorldPressure>
}

data class WorldPressure(val kind: String, val strength: Double, val note: String)

/** Turns structured events into richer prose. Text only — never facts. */
interface NarrativeTextProvider {
    suspend fun elaborate(event: WorldEvent, state: WorldState): String?
}

/** Generates character dialogue lines. Text only — never facts. */
interface DialogueProvider {
    suspend fun lineFor(residentId: Long, situation: String): String?
}

/** Maps national/world pressures onto town-level modifiers. */
interface WorldPressureMapper {
    fun map(pressure: WorldPressure): List<TownModifier>
}

data class TownModifier(val target: String, val delta: Double)

/** Cloud persistence seam. */
interface CloudSaveRepository {
    suspend fun upload(worldId: Long, checkpointJson: String): Boolean
    suspend fun download(worldId: Long): String?
}

// ---------------------------------------------------------------- no-ops

class NoOpExternalWorldEventProvider : ExternalWorldEventProvider {
    override suspend fun pendingPressures(sinceRealMs: Long): List<WorldPressure> = emptyList()
}

class NoOpNarrativeTextProvider : NarrativeTextProvider {
    override suspend fun elaborate(event: WorldEvent, state: WorldState): String? = null
}

class NoOpDialogueProvider : DialogueProvider {
    override suspend fun lineFor(residentId: Long, situation: String): String? = null
}

class NoOpWorldPressureMapper : WorldPressureMapper {
    override fun map(pressure: WorldPressure): List<TownModifier> = emptyList()
}

class NoOpCloudSaveRepository : CloudSaveRepository {
    override suspend fun upload(worldId: Long, checkpointJson: String): Boolean = false
    override suspend fun download(worldId: Long): String? = null
}
