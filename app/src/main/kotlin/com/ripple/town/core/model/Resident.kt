package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class Gender { FEMALE, MALE, NONBINARY }

enum class DetailLevel { DETAILED, BACKGROUND }

enum class LifeStage { CHILD, TEEN, ADULT, ELDER }

enum class Activity(val label: String) {
    IDLE("Idle"),
    SLEEPING("Sleeping"),
    EATING("Eating"),
    WORKING("Working"),
    AT_SCHOOL("At school"),
    SOCIALISING("Socialising"),
    VISITING("Visiting"),
    SHOPPING("Shopping"),
    EXERCISING("Exercising"),
    LEARNING("Learning"),
    RESTING_ILL("Resting while ill"),
    AT_CLINIC("At the clinic"),
    RELAXING("Relaxing"),
    ARGUING("Arguing"),
    CELEBRATING("Celebrating"),
    MOURNING("Mourning"),
    TRAVELLING("On the way"),
    COMMUNITY("Community activity")
}

enum class Mood(val label: String) {
    DESPAIRING("Despairing"), LOW("Low"), FLAT("Flat"), CONTENT("Content"), HAPPY("Happy"), JOYFUL("Joyful");

    companion object {
        fun fromScore(score: Double): Mood = when {
            score < 15 -> DESPAIRING
            score < 32 -> LOW
            score < 48 -> FLAT
            score < 65 -> CONTENT
            score < 82 -> HAPPY
            else -> JOYFUL
        }
    }
}

/** All needs range 0..100. Higher is better, except stress where higher is worse. */
@Serializable
data class Needs(
    var hunger: Double = 70.0,        // satiation: 0 = starving, 100 = full
    var energy: Double = 70.0,
    var health: Double = 85.0,
    var safety: Double = 80.0,
    var social: Double = 60.0,
    var comfort: Double = 65.0,
    var purpose: Double = 55.0,
    var stress: Double = 25.0,        // higher = worse
    var financialSecurity: Double = 50.0
) {
    fun clampAll() {
        hunger = hunger.coerceIn(0.0, 100.0)
        energy = energy.coerceIn(0.0, 100.0)
        health = health.coerceIn(0.0, 100.0)
        safety = safety.coerceIn(0.0, 100.0)
        social = social.coerceIn(0.0, 100.0)
        comfort = comfort.coerceIn(0.0, 100.0)
        purpose = purpose.coerceIn(0.0, 100.0)
        stress = stress.coerceIn(0.0, 100.0)
        financialSecurity = financialSecurity.coerceIn(0.0, 100.0)
    }

    /** Overall wellbeing 0..100 used for mood. */
    fun wellbeing(): Double =
        (hunger + energy + health + safety + social + comfort + purpose + financialSecurity) / 8.0 -
            (stress * 0.25)
}

/** Continuous personality traits, 0.0..1.0. */
@Serializable
data class Personality(
    val kindness: Double = 0.5,
    val ambition: Double = 0.5,
    val curiosity: Double = 0.5,
    val sociability: Double = 0.5,
    val patience: Double = 0.5,
    val honesty: Double = 0.5,
    val courage: Double = 0.5,
    val discipline: Double = 0.5,
    val empathy: Double = 0.5,
    val impulsiveness: Double = 0.5
)

enum class SkillType(val label: String) {
    COOKING("Cooking"), CARPENTRY("Carpentry"), REPAIR("Repair"), TEACHING("Teaching"),
    MEDICINE("Medicine"), BUSINESS("Business"), POLITICS("Politics"), SOCIAL("Social ability"),
    FITNESS("Fitness"), CREATIVITY("Creativity")
}

enum class HealthConditionType(val label: String, val serious: Boolean, val chronic: Boolean) {
    COLD("A heavy cold", serious = false, chronic = false),
    FLU("Winter flu", serious = false, chronic = false),
    INJURY("An injury", serious = false, chronic = false),
    EXHAUSTION("Exhaustion", serious = false, chronic = false),
    BACK_TROUBLE("Back trouble", serious = false, chronic = true),
    WEAK_HEART("A weak heart", serious = true, chronic = true),
    LUNG_ILLNESS("A lung illness", serious = true, chronic = false),
    FRAILTY("Frailty of age", serious = true, chronic = true)
}

@Serializable
data class HealthCondition(
    val id: Long,
    val residentId: Long,
    val type: HealthConditionType,
    var severity: Double,           // 0..100
    val startedAt: Long,            // sim minutes
    var hidden: Boolean = false,    // resident/world does not know yet
    var diagnosedAt: Long? = null,
    var recoveredAt: Long? = null
) {
    val active: Boolean get() = recoveredAt == null
}

@Serializable
data class SpriteConfig(
    val skinTone: Int = 0,
    val hairStyle: Int = 0,
    val hairColor: Int = 0,
    val shirtColor: Int = 0,
    val trouserColor: Int = 0
)

enum class RelationshipStatus { SINGLE, DATING, ENGAGED, MARRIED, SEPARATED, DIVORCED, WIDOWED }

@Serializable
data class Resident(
    val id: Long,
    var firstName: String,
    var surname: String,
    val gender: Gender,
    val bornAt: Long,                       // sim minutes (may be negative: before epoch)
    var homeBuildingId: Long?,
    var householdId: Long?,
    var alive: Boolean = true,
    var diedAt: Long? = null,
    var causeOfDeath: String? = null,
    var detailLevel: DetailLevel = DetailLevel.BACKGROUND,
    val sprite: SpriteConfig = SpriteConfig(),

    // Life state
    var activity: Activity = Activity.IDLE,
    var activityEndsAt: Long = 0L,
    var activityReason: String = "",
    var currentBuildingId: Long? = null,    // null while travelling
    var travelFromBuildingId: Long? = null,
    var travelToBuildingId: Long? = null,
    var travelStartedAt: Long = 0L,
    var travelArrivesAt: Long = 0L,
    /** Activity to start once travel completes. */
    var plannedActivity: Activity? = null,
    var plannedActivityMinutes: Long = 0L,
    var plannedActivityReason: String = "",
    var leftTownAt: Long? = null,

    var occupation: String = "Unemployed",
    var employmentId: Long? = null,
    var relationshipStatus: RelationshipStatus = RelationshipStatus.SINGLE,
    var partnerId: Long? = null,

    var wealth: Double = 500.0,
    var debt: Double = 0.0,
    var reputation: Double = 50.0,          // 0..100
    var politicalInterest: Double = 0.2,    // 0..1

    val needs: Needs = Needs(),
    val personality: Personality = Personality(),
    val skills: MutableMap<SkillType, Double> = mutableMapOf(),
    val conditions: MutableList<HealthCondition> = mutableListOf(),
    val memories: MutableList<Memory> = mutableListOf(),
    val goals: MutableList<Goal> = mutableListOf(),

    // Family
    var motherId: Long? = null,
    var fatherId: Long? = null,
    val childIds: MutableList<Long> = mutableListOf(),

    // Transient idea seeds planted by Inspire interventions or memories; consumed by goal generation.
    val ideaSeeds: MutableList<String> = mutableListOf(),
    // Awareness flags planted by Warn interventions; read by the decision system.
    val awareness: MutableList<String> = mutableListOf(),
    // Ids of WorldEvents this resident is personally aware of — either because they were
    // involved (source/target, set automatically) or because a rumour reached them
    // (RumourSystem). Deliberately just event ids, not a richer "KnownFact" type: the
    // event itself already carries description/accuracy/cause-chain, so a resident's
    // knowledge state only needs to answer "have they heard this one yet?" — which is all
    // RumourSystem's leak-eligibility check and any future dialogue/reaction logic need.
    // Bounded like memories so it can't grow without limit over a long-running world.
    val knownFacts: MutableList<Long> = mutableListOf()
) {
    val fullName: String get() = "$firstName $surname"

    fun ageAt(now: Long): Int = SimTime.ageYears(bornAt, now)

    fun lifeStageAt(now: Long): LifeStage = when (ageAt(now)) {
        in 0..12 -> LifeStage.CHILD
        in 13..17 -> LifeStage.TEEN
        in 18..64 -> LifeStage.ADULT
        else -> LifeStage.ELDER
    }

    fun mood(): Mood = Mood.fromScore(needs.wellbeing())

    fun skill(type: SkillType): Double = skills[type] ?: 5.0

    fun activeConditions(): List<HealthCondition> = conditions.filter { it.active }

    fun hasSeriousCondition(): Boolean = activeConditions().any { it.type.serious }

    /** Effective location for interaction purposes: building the resident is in, or is heading to. */
    fun locationBuildingId(): Long? = currentBuildingId ?: travelToBuildingId

    val inTown: Boolean get() = alive && leftTownAt == null

    fun knows(eventId: Long): Boolean = eventId in knownFacts

    /** Records that this resident has learned of [eventId], bounded so a long-running world's
     *  list can't grow forever (mirrors the memories list's own cap in [com.ripple.town.core.simulation.TickContext.addMemory]). */
    fun learn(eventId: Long) {
        if (eventId in knownFacts) return
        knownFacts += eventId
        if (knownFacts.size > MAX_KNOWN_FACTS) knownFacts.removeAt(0)
    }

    companion object {
        private const val MAX_KNOWN_FACTS = 60
    }
}
