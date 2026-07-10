package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.Memory
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/**
 * Lets the engine resolve the consequence depth of events from earlier ticks
 * without keeping the entire log in memory.
 */
interface EventIndex {
    fun depthOf(eventId: Long): Int
    fun get(eventId: Long): WorldEvent?
}

class InMemoryEventIndex : EventIndex {
    private val depths = HashMap<Long, Int>()
    private val recent = HashMap<Long, WorldEvent>()

    fun remember(event: WorldEvent) {
        depths[event.id] = event.consequenceDepth
        recent[event.id] = event
        if (recent.size > 4000) {
            // keep depths (tiny) but drop old bodies
            val cutoff = event.id - 2000
            recent.keys.removeAll { it < cutoff }
        }
    }

    override fun depthOf(eventId: Long): Int = depths[eventId] ?: 0
    override fun get(eventId: Long): WorldEvent? = recent[eventId]
}

/**
 * Per-tick working context: the mutable world, the tick's deterministic RNG,
 * and the buffer of events recorded during this tick.
 */
class TickContext(
    val state: WorldState,
    val rng: SimRandom,
    val eventIndex: EventIndex
) {
    val now: Long get() = state.time
    val newEvents = mutableListOf<WorldEvent>()

    /** importance points to add to earlier events that gained consequences this tick */
    val importanceBoosts = mutableMapOf<Long, Double>()

    /** Bounded number of consequence-rule applications per tick. */
    var consequenceBudget = MAX_CONSEQUENCES_PER_TICK

    /** Bounded number of mechanically-effectful conversations (belief/relationship/emotion
     *  shifts) [ConversationInfluenceSystem] may land per tick — see that object's doc comment.
     *  Deliberately much smaller than [consequenceBudget]: most sampled conversations
     *  `InteractionSystem` sees should stay flavour-only. */
    var conversationInfluenceBudget = ConversationInfluenceSystem.MAX_MEANINGFUL_PER_TICK

    fun emit(
        type: EventType,
        description: String,
        sourceResidentId: Long? = null,
        targetResidentIds: List<Long> = emptyList(),
        buildingId: Long? = null,
        businessId: Long? = null,
        severity: Double = 0.3,
        visibility: EventVisibility = EventVisibility.PUBLIC,
        payload: Map<String, String> = emptyMap(),
        causeIds: List<Long> = emptyList()
    ): WorldEvent {
        val depth = if (causeIds.isEmpty()) 0 else causeIds.maxOf { causeDepth(it) } + 1
        val event = WorldEvent(
            id = state.nextEventId++,
            time = now,
            type = type,
            sourceResidentId = sourceResidentId,
            targetResidentIds = targetResidentIds,
            buildingId = buildingId,
            businessId = businessId,
            severity = severity,
            visibility = visibility,
            description = description,
            payload = payload,
            causeIds = causeIds.filter { it > 0L },
            consequenceDepth = depth,
            importance = ImportanceScorer.baseImportance(type, severity, targetResidentIds.size + 1)
        )
        newEvents += event
        state.recentEventIds += event.id
        while (state.recentEventIds.size > 60) state.recentEventIds.removeAt(0)
        // Events that cause things become more historically important.
        for (c in event.causeIds) {
            importanceBoosts[c] = (importanceBoosts[c] ?: 0.0) + CAUSED_EVENT_BOOST
        }
        return event
    }

    private fun causeDepth(id: Long): Int {
        newEvents.find { it.id == id }?.let { return it.consequenceDepth }
        return eventIndex.depthOf(id)
    }

    fun addMemory(
        resident: Resident,
        type: MemoryType,
        description: String,
        intensity: Double,
        eventId: Long? = null,
        associated: List<Long> = emptyList(),
        belief: String? = null
    ) {
        if (resident.detailLevel != com.ripple.town.core.model.DetailLevel.DETAILED) return
        resident.memories += Memory(
            id = state.nextMemoryId++,
            residentId = resident.id,
            eventId = eventId,
            type = type,
            description = description,
            emotionalIntensity = intensity.coerceIn(0.0, 100.0),
            importance = (intensity * 0.9).coerceIn(0.0, 100.0),
            createdAt = now,
            lastRecalledAt = now,
            associatedResidentIds = associated
        )
        // Minor memories are summarised away so the list stays bounded.
        if (resident.memories.size > 40) {
            val removable = resident.memories.filter { it.importance < 35.0 }
                .minByOrNull { it.importance }
            if (removable != null) resident.memories.remove(removable)
            else resident.memories.removeAt(0)
        }
    }

    /**
     * Send a resident towards a building; on arrival they begin [activity].
     * If they are already there the activity starts at once.
     */
    fun sendTo(
        resident: Resident,
        buildingId: Long,
        activity: Activity,
        durationMinutes: Long,
        reason: String
    ) {
        if (!resident.inTown) return
        if (resident.currentBuildingId == buildingId) {
            beginActivity(resident, activity, durationMinutes, reason)
            return
        }
        val from = resident.currentBuildingId?.let { state.building(it) }
        val to = state.building(buildingId) ?: return
        val fromTile = from?.door ?: to.door
        val travelMinutes = (fromTile.manhattan(to.door) * TRAVEL_MINUTES_PER_TILE)
            .toLong().coerceIn(5L, 90L)
        resident.travelFromBuildingId = resident.currentBuildingId
        resident.travelToBuildingId = buildingId
        resident.travelStartedAt = now
        resident.travelArrivesAt = now + travelMinutes
        resident.currentBuildingId = null
        resident.activity = Activity.TRAVELLING
        resident.activityEndsAt = resident.travelArrivesAt
        resident.activityReason = reason
        resident.plannedActivity = activity
        resident.plannedActivityMinutes = durationMinutes
        resident.plannedActivityReason = reason
    }

    fun beginActivity(resident: Resident, activity: Activity, durationMinutes: Long, reason: String) {
        resident.activity = activity
        resident.activityEndsAt = now + durationMinutes
        resident.activityReason = reason
        resident.plannedActivity = null
    }

    companion object {
        const val TRAVEL_MINUTES_PER_TILE = 0.8
        const val MAX_CONSEQUENCES_PER_TICK = 24
        const val CAUSED_EVENT_BOOST = 4.0
    }
}
