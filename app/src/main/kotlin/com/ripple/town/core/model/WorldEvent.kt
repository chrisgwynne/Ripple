package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class EventType(val label: String) {
    PERSON_BORN("Birth"),
    PERSON_DIED("Death"),
    RESIDENT_MOVED("Moved home"),
    RESIDENT_ARRIVED("New arrival"),
    RESIDENT_LEFT_TOWN("Left town"),
    JOB_STARTED("New job"),
    JOB_LOST("Job lost"),
    JOB_QUIT("Quit job"),
    HOURS_REDUCED("Hours reduced"),
    BUSINESS_OPENED("Business opened"),
    BUSINESS_CLOSED("Business closed"),
    BUSINESS_STRUGGLING("Business struggling"),
    BUSINESS_EXPANDED("Business expanded"),
    RELATIONSHIP_STARTED("New romance"),
    FRIENDSHIP_FORMED("New friendship"),
    FRIENDSHIP_ENDED("Friendship ended"),
    RIVALRY_FORMED("Rivalry"),
    ENGAGEMENT("Engagement"),
    MARRIAGE("Marriage"),
    AFFAIR_BEGAN("A closeness kept quiet"),
    AFFAIR_DISCOVERED("Affair discovered"),
    SEPARATION("Separation"),
    DIVORCE("Divorce"),
    RECONCILIATION("Reconciliation"),
    BREAK_UP("Break-up"),
    ARGUMENT("Argument"),
    APOLOGY("Apology"),
    ILLNESS_STARTED("Illness"),
    ILLNESS_DIAGNOSED("Diagnosis"),
    ILLNESS_RECOVERED("Recovery"),
    INJURY("Injury"),
    CRIME_COMMITTED("Crime"),
    CRIME_REPORTED("Crime reported"),
    BUILDING_DAMAGED("Building damaged"),
    BUILDING_REPAIRED("Building repaired"),
    BUILDING_EXPANDED("Building extended"),
    BUILDING_ABANDONED("Building abandoned"),
    BUILDING_CONSTRUCTED("New building"),
    ELECTION_CALLED("Election called"),
    ELECTION_WON("Election won"),
    GOAL_FORMED("New ambition"),
    GOAL_COMPLETED("Ambition achieved"),
    GOAL_ABANDONED("Ambition abandoned"),
    SKILL_MILESTONE("Skill milestone"),
    DEBT_CRISIS("Debt crisis"),
    FINANCIAL_RELIEF("Financial relief"),
    WEATHER_DAMAGE("Weather damage"),
    COMMUNITY_EVENT("Community event"),
    INTERVENTION_APPLIED("A quiet nudge"),
    MEETING("Chance meeting"),
    SECRET_REVEALED("Secret revealed"),
    RUMOUR_SPREAD("Rumour"),
    TOWN_MILESTONE("Town milestone"),
    PETITION_STARTED("Petition started"),
    PETITION_RESOLVED("Petition resolved")
}

enum class EventVisibility {
    /** Everyone in town knows; can appear in the newspaper. */
    PUBLIC,
    /** Only those involved know. */
    PRIVATE,
    /** Nobody knows yet (hidden conditions, secret interventions). */
    HIDDEN
}

/**
 * A single meaningful change in the world. Event-sourced: everything the History
 * screen, News screen and cause viewer show is derived from these.
 */
@Serializable
data class WorldEvent(
    val id: Long,
    val worldId: Long = 1L,
    val time: Long,                           // sim minutes
    val type: EventType,
    val sourceResidentId: Long? = null,
    val targetResidentIds: List<Long> = emptyList(),
    val buildingId: Long? = null,
    val businessId: Long? = null,
    val severity: Double = 0.3,               // 0..1
    val visibility: EventVisibility = EventVisibility.PUBLIC,
    val description: String,
    val payload: Map<String, String> = emptyMap(),
    /** Ids of the events that directly caused this one. */
    val causeIds: List<Long> = emptyList(),
    /** How many causal steps from a root cause this event sits at. */
    val consequenceDepth: Int = 0,
    var importance: Double = 0.0              // historical importance, recalculated over time
) {
    fun involvedResidentIds(): List<Long> =
        (listOfNotNull(sourceResidentId) + targetResidentIds).distinct()
}

enum class DelayedEffectType {
    /** Target resident's resentment towards payload resident grows. */
    RESENTMENT_GROWTH,
    /** Target resident considers leaving their job. */
    CONSIDER_QUITTING,
    /** Target resident's stress rises. */
    STRESS_RISE,
    /** Target relationship comes under pressure (affection/trust decay). */
    RELATIONSHIP_PRESSURE,
    /** Target resident becomes tempted by a minor crime if finances stay poor. */
    CRIME_TEMPTATION,
    /** Target resident's health erodes. */
    HEALTH_EROSION,
    /** Two residents get a raised chance of meeting. */
    MEETING_CHANCE,
    /** A hidden fact about the target may surface. */
    REVELATION_CHANCE,
    /** Target resident may form a particular goal. */
    GOAL_SEED,
    /** Target resident may forgive payload resident, reducing resentment. */
    FORGIVENESS,
    /** Target resident may move home if pressure persists. */
    CONSIDER_MOVING,
    /** Target business demand shifts. */
    DEMAND_SHIFT,
    /** Target resident receives a mood lift (community support, good news). */
    MOOD_LIFT
}

enum class EffectCondition {
    NONE,
    /** Only fires if target's financial security is still below 35. */
    STILL_POOR,
    /** Only fires if target's stress is above 60. */
    STILL_STRESSED,
    /** Only fires if the relationship in payload still has resentment above 40. */
    STILL_RESENTFUL,
    /** Only fires if target is unemployed. */
    STILL_UNEMPLOYED,
    /** Only fires if both residents are alive. */
    BOTH_ALIVE
}

/**
 * A consequence waiting to happen. Created by consequence rules and interventions;
 * processed each tick once its window opens, if its condition holds.
 */
@Serializable
data class DelayedEffect(
    val id: Long,
    val sourceEventId: Long,
    val targetResidentId: Long? = null,
    val secondaryResidentId: Long? = null,
    val targetBusinessId: Long? = null,
    val type: DelayedEffectType,
    var strength: Double,                 // 0..1
    val earliestAt: Long,                 // sim minutes
    val latestAt: Long,
    val condition: EffectCondition = EffectCondition.NONE,
    val decayPerDay: Double = 0.0,        // strength lost per in-game day after earliestAt
    var applied: Boolean = false,
    var cancelled: Boolean = false,
    val note: String = ""
)
