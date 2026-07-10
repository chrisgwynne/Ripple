package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class PetitionSubject {
    /** Against a specific noisy building; succeeds by mandating a quiet-hours-style noise cut. */
    NOISE,
    /** For rent relief; succeeds by trimming rent (a household's, or a town-wide easing). */
    RENT
}

enum class PetitionStatus { ACTIVE, SUCCEEDED, FAILED }

/**
 * A single curated, abstract "outside world" pressure kind. Deliberately fictional and
 * generic — no real place names, companies or current events. Each has a matched pair
 * (a rise and its later easing) so the mapper always knows which mechanical direction to
 * nudge. See [com.ripple.town.core.simulation.CuratedWorldPressureFeed] and
 * [com.ripple.town.core.simulation.WorldPressureMechanicMapper] (the concrete,
 * deterministic Phase 4 system — named distinctly from the pre-existing
 * `core.simulation.providers.ExternalWorldEventProvider`/`WorldPressureMapper`
 * placeholder interfaces reserved for a later real/async feed).
 */
enum class ExternalPressureKind {
    /** Delivery/overhead cost pressure rising — the one kind currently mapped to a mechanical effect. */
    FUEL_PRICES_RISE,
    /** The same pressure easing back off. */
    FUEL_PRICES_EASE,
    /** Flavour-only pressures: currently recorded and reported on, but not yet mapped to any
     *  mechanical effect — deliberately scoped down, see `docs/simulation-rules.md`. */
    POOR_HARVEST,
    STRONG_HARVEST,
    TRADE_ROUTES_DISRUPTED,
    TRADE_FLOURISHING,
    CONFIDENCE_DIPS,
    CONFIDENCE_RISES
}

/**
 * One active (or just-resolved) national-scale pressure, town-wide, at most one at a time
 * by deliberate scoped-down design. See [com.ripple.town.core.simulation.CuratedWorldPressureFeed].
 */
@Serializable
data class ExternalPressure(
    val kind: ExternalPressureKind,
    val startedAt: Long,          // sim minutes
    val endsAt: Long,             // sim minutes — resolves automatically once reached
    val startEventId: Long = 0L
)

/**
 * A candidate's standing during a live campaign window, tracked between
 * `ELECTION_CALLED` and the vote itself. See
 * [com.ripple.town.core.simulation.ElectionSystem].
 */
@Serializable
data class Candidacy(
    val residentId: Long,
    /** Accumulated campaign support built up day by day; the deciding factor at the vote. */
    var support: Double = 0.0,
    /** How many campaigning actions have landed — bounded per campaign, see MAX_CAMPAIGN_ACTIONS. */
    var actionsTaken: Int = 0
)

/**
 * Grassroots local politics, short of a council seat: a resident personally affected by
 * noise or rent burden starts a petition, sympathetic townsfolk sign it daily, and it
 * resolves — with a real, bounded policy effect — once it hits its signature threshold or
 * its deadline lapses. See [com.ripple.town.core.simulation.PetitionSystem].
 */
@Serializable
data class Petition(
    val id: Long,
    val subject: PetitionSubject,
    val starterId: Long,
    /** Building targeted for a NOISE petition, or null. */
    val targetBuildingId: Long? = null,
    /** Household targeted for a RENT petition, or null. */
    val targetHouseholdId: Long? = null,
    val startedAt: Long,                     // sim minutes
    val deadlineAt: Long,                    // sim minutes
    val signatureThreshold: Int,
    val signatureIds: MutableList<Long> = mutableListOf(),
    var status: PetitionStatus = PetitionStatus.ACTIVE,
    val startEventId: Long = 0L
) {
    val signatureCount: Int get() = signatureIds.size
}

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
    /** The resident currently serving as constable; kept up by [CrimeSystem.ensureConstable]. */
    var constableResidentId: Long? = null,
    /**
     * Council seats: up to `ElectionSystem.COUNCIL_SEATS` residents (runners-up from the
     * same election that decided the mayor) who hold a lesser civic role alongside them.
     * Distinct from `mayorId` — the mayor is never also listed here.
     */
    val councillorIds: MutableList<Long> = mutableListOf(),
    /**
     * Live campaign, if one is running: set when `ELECTION_CALLED` fires
     * (`ElectionSystem.callElection`), cleared once the vote resolves. `null` between elections.
     */
    var campaignEndsAt: Long? = null,
    val candidacies: MutableList<Candidacy> = mutableListOf(),

    // Local politics
    /** Active/recently resolved petitions; run by `PetitionSystem`. Bounded, see its MAX_ACTIVE_PETITIONS. */
    val petitions: MutableList<Petition> = mutableListOf(),
    var nextPetitionId: Long = 1L,

    // The outside world (Phase 4)
    /**
     * At most one active "national" pressure at a time — a deliberate scoped-down MVP, see
     * `com.ripple.town.core.simulation.CuratedWorldPressureFeed`. `null` most of the time.
     */
    var externalPressure: ExternalPressure? = null,

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
