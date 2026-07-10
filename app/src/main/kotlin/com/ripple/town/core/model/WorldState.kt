package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * The complete factual state of the simulated world at a moment in time.
 *
 * This object is what the engine mutates each tick, what checkpoints serialise,
 * and what the UI reads (as an immutable snapshot). It contains no Android or
 * Compose types. Events are append-only and are NOT stored here (they live in
 * the event log); [recentEventIds] is only a convenience window for the UI.
 */
@Serializable
data class WorldState(
    val worldId: Long = 1L,
    val seed: Long,
    var townName: String,
    val createdAtRealMs: Long,
    var time: Long = 0L,                      // sim minutes since epoch
    var weather: Weather = Weather.CLEAR,
    var weatherEndsAt: Long = 0L,

    val map: TownMap,
    val residents: MutableMap<Long, Resident> = mutableMapOf(),
    val households: MutableMap<Long, Household> = mutableMapOf(),
    val buildings: MutableMap<Long, Building> = mutableMapOf(),
    val businesses: MutableMap<Long, Business> = mutableMapOf(),
    val employments: MutableMap<Long, Employment> = mutableMapOf(),
    /** Keyed by [Relationship.keyOf]. */
    val relationships: MutableMap<Long, Relationship> = mutableMapOf(),
    val delayedEffects: MutableList<DelayedEffect> = mutableListOf(),

    // Follow system
    var followedResidentId: Long? = null,
    val favouriteResidentIds: MutableList<Long> = mutableListOf(),
    val discoveredResidentIds: MutableList<Long> = mutableListOf(),

    // Intervention wallet
    var nudges: Int = 3,
    val maxNudges: Int = 3,
    var nudgeRegenProgressMinutes: Long = 0L,
    /** residentId -> sim time an intervention was last applied to them (cooldown). */
    val lastInterventionAt: MutableMap<Long, Long> = mutableMapOf(),

    // Election
    var nextElectionAt: Long = 0L,
    var mayorId: Long? = null,

    // Id counters (all state needed for deterministic continuation)
    var nextResidentId: Long = 1L,
    var nextHouseholdId: Long = 1L,
    var nextBuildingId: Long = 1L,
    var nextBusinessId: Long = 1L,
    var nextEmploymentId: Long = 1L,
    var nextEventId: Long = 1L,
    var nextEffectId: Long = 1L,
    var nextMemoryId: Long = 1L,
    var nextGoalId: Long = 1L,
    var nextConditionId: Long = 1L,
    var nextInterventionId: Long = 1L,
    var nextIssueId: Long = 1L,
    var nextStoryId: Long = 1L,
    var issuesPublished: Int = 0,
    var lastNewspaperAt: Long = -1L,
    var lastStatisticDay: Long = -1L,
    var birthsToday: Int = 0,
    var deathsToday: Int = 0,

    /** Sliding window of recent event ids for the live ticker. */
    val recentEventIds: MutableList<Long> = mutableListOf()
) {
    fun resident(id: Long): Resident? = residents[id]

    fun requireResident(id: Long): Resident =
        residents[id] ?: error("No resident with id $id")

    fun building(id: Long): Building? = buildings[id]

    fun business(id: Long): Business? = businesses[id]

    fun relationship(x: Long, y: Long): Relationship? =
        relationships[Relationship.keyOf(x, y)]

    fun relationshipOrCreate(x: Long, y: Long): Relationship =
        relationships.getOrPut(Relationship.keyOf(x, y)) { Relationship.create(x, y) }

    fun relationshipsOf(id: Long): List<Relationship> =
        relationships.values.filter { it.involves(id) }

    fun household(id: Long): Household? = households[id]

    fun householdOf(resident: Resident): Household? =
        resident.householdId?.let { households[it] }

    fun employmentOf(resident: Resident): Employment? =
        resident.employmentId?.let { employments[it] }?.takeIf { it.active }

    fun employeesOf(businessId: Long): List<Employment> =
        employments.values.filter { it.businessId == businessId && it.active }

    fun livingResidents(): List<Resident> = residents.values.filter { it.alive }

    fun detailedResidents(): List<Resident> =
        residents.values.filter { it.alive && it.detailLevel == DetailLevel.DETAILED }

    fun residentsIn(buildingId: Long): List<Resident> =
        residents.values.filter { it.alive && it.currentBuildingId == buildingId }

    fun homes(): List<Building> = buildings.values.filter { it.type.isHome }

    fun population(): Int = residents.values.count { it.alive }

    /** Stable iteration order for determinism. */
    fun residentsOrdered(): List<Resident> = residents.values.sortedBy { it.id }
}
