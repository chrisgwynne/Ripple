package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class GoalType(val label: String) {
    FIND_JOB("Find a job"),
    START_BUSINESS("Start a small business"),
    LEARN_SKILL("Learn a skill"),
    FIND_PARTNER("Find companionship"),
    REPAIR_RELATIONSHIP("Repair a relationship"),
    GET_HEALTHY("Get healthy again"),
    PAY_OFF_DEBT("Pay off debt"),
    MOVE_HOME("Find a new home"),
    LEAVE_FOR_EDUCATION("Leave town for education"),
    RUN_FOR_OFFICE("Run for local office"),
    RETIRE_WELL("Wind down towards retirement")
}

enum class GoalStatus { ACTIVE, COMPLETED, ABANDONED }

@Serializable
data class Goal(
    val id: Long,
    val ownerId: Long,
    val type: GoalType,
    val motivation: String,               // human-readable reason the goal formed
    val createdAt: Long,
    var progress: Double = 0.0,           // 0..1
    var risk: Double = 0.2,               // 0..1
    var status: GoalStatus = GoalStatus.ACTIVE,
    var resolvedAt: Long? = null,
    val targetResidentId: Long? = null,
    val targetSkill: SkillType? = null,
    val causeEventId: Long? = null        // event that seeded this goal, for cause chains
)

enum class MemoryType(val label: String) {
    KINDNESS_RECEIVED("Someone was kind to me"),
    KINDNESS_GIVEN("I helped someone"),
    BETRAYAL("I was betrayed"),
    HUMILIATION("I was humiliated"),
    LOSS("I lost someone"),
    ACHIEVEMENT("I achieved something"),
    HARDSHIP_SHARED("We got through it together"),
    ROMANCE("A romantic moment"),
    ARGUMENT("A bad argument"),
    CHILDHOOD("A childhood memory"),
    INSPIRATION("Something inspired me"),
    FEAR("Something frightened me"),
    NEGLECT("I was let down")
}

@Serializable
data class Memory(
    val id: Long,
    val residentId: Long,
    val eventId: Long?,
    val type: MemoryType,
    val description: String,
    var emotionalIntensity: Double,       // 0..100
    var accuracy: Double = 90.0,          // 0..100, drifts down as it decays
    var importance: Double,               // 0..100
    val createdAt: Long,
    var lastRecalledAt: Long,
    var decayPerYear: Double = 8.0,
    val associatedResidentIds: List<Long> = emptyList(),
    val beliefFormed: String? = null      // e.g. "The clinic saved my life"
)

enum class InterventionVerb(val label: String, val description: String) {
    DELAY("Delay", "Hold someone up for a little while"),
    DIVERT("Divert", "Send someone a slightly different way"),
    REVEAL("Reveal", "Raise the chance a hidden fact surfaces"),
    CONCEAL("Conceal", "Lower the chance a fact surfaces for now"),
    INTRODUCE("Introduce", "Make it likelier two people cross paths"),
    MISPLACE("Misplace", "An ordinary object ends up somewhere else"),
    ENCOURAGE("Encourage", "A small push towards what they're weighing up"),
    DISTRACT("Distract", "Take their mind off what they're about to do"),
    INSPIRE("Inspire", "Plant the seed of an idea"),
    WARN("Warn", "Make someone aware of a possible risk")
}

@Serializable
data class Intervention(
    val id: Long,
    val verb: InterventionVerb,
    val targetResidentId: Long?,
    val secondaryResidentId: Long? = null,
    val targetBuildingId: Long? = null,
    val appliedAt: Long,
    val note: String,
    val eventId: Long                      // the INTERVENTION_APPLIED event
)

enum class StoryCategory(val label: String) {
    HEADLINE("Front page"),
    TOWN_NEWS("Town news"),
    BUSINESS("Business"),
    BIRTHS("Births"),
    DEATHS("Deaths"),
    WEDDINGS("Weddings"),
    CRIME("Crime & order"),
    HEALTH("Health"),
    WEATHER("Weather"),
    NOTICES("Public notices"),
    HUMAN_INTEREST("Around town")
}

@Serializable
data class NewspaperStory(
    val id: Long,
    val issueId: Long,
    val category: StoryCategory,
    val headline: String,
    val body: String,
    val eventId: Long? = null,
    val orderInIssue: Int = 0
)

@Serializable
data class NewspaperIssue(
    val id: Long,
    val issueNumber: Int,
    val publishedAt: Long,                // sim minutes
    val masthead: String,
    val stories: MutableList<NewspaperStory> = mutableListOf()
)

@Serializable
data class TownStatistic(
    val dayIndex: Long,
    val population: Int,
    val detailedPopulation: Int,
    val employedAdults: Int,
    val adultCount: Int,
    val openBusinesses: Int,
    val averageWellbeing: Double,
    val averageWealth: Double,
    val births: Int,
    val deaths: Int
)
