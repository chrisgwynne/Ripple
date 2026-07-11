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
    COMMUNITY("Community activity"),
    BEING_CARED_FOR("Being cared for")
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

/**
 * Lifetime accumulated drift on top of a resident's inherited-at-birth
 * [Personality] baseline — see `PersonalityDevelopmentSystem` and
 * `docs/simulation-rules.md` "Personality drift from lived experience".
 * Deliberately a *sibling* field, never folded into [Personality] itself:
 * dozens of call sites read `resident.personality.kindness` etc. directly
 * and must keep compiling and returning the untouched birth baseline.
 * Each field is a signed delta, one per [Personality] trait, independently
 * capped to `±PersonalityDevelopmentSystem.MAX_LIFETIME_DRIFT` by
 * [Resident.applyPersonalityDrift] — never written to directly by
 * simulation code outside that helper. `var` (not `val`) because these
 * mutate in place tick over tick, same convention as [Needs]' fields.
 */
@Serializable
data class PersonalityModifiers(
    var kindness: Double = 0.0,
    var ambition: Double = 0.0,
    var curiosity: Double = 0.0,
    var sociability: Double = 0.0,
    var patience: Double = 0.0,
    var honesty: Double = 0.0,
    var courage: Double = 0.0,
    var discipline: Double = 0.0,
    var empathy: Double = 0.0,
    var impulsiveness: Double = 0.0
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

/**
 * Distinct, actively-felt emotional states — layered ON TOP of the [Needs] sliders, not a
 * replacement for them. Needs are slow-drifting background pressures ("how full is my life");
 * emotions are sharper, shorter-lived reactions to specific events, spawned by
 * `EmotionSystem.spawnEmotion` and decayed by `EmotionSystem.updateDaily`. See
 * `docs/simulation-rules.md` "Active emotions" for spawn triggers and behavioural effects.
 *
 * Deliberately a practical 12, not the full textbook set: `EMBARRASSMENT` is skipped because it
 * would just duplicate `HUMILIATION` (already a distinct [MemoryType] with its own
 * `traumaRecoveryDamping` handling in `NeedsSystem`); `CONFIDENCE` is skipped because it would
 * duplicate the existing `courage`/`ambition` personality traits (continuous, not event-driven,
 * and owned by the personality-drift work); `AFFECTION` is skipped because relationship warmth
 * already has a dedicated, richer continuous model (`Relationship.affection`) that this system
 * would only shadow, not improve.
 */
enum class EmotionType(val label: String) {
    GRIEF("Grief"),
    FEAR("Fear"),
    SHAME("Shame"),
    JEALOUSY("Jealousy"),
    ANGER("Anger"),
    RELIEF("Relief"),
    HOPE("Hope"),
    REGRET("Regret"),
    LONELINESS("Loneliness"),
    PRIDE("Pride"),
    ANXIETY("Anxiety"),
    GUILT("Guilt")
}

/**
 * One active, decaying emotional reaction. `intensity` follows the same 0..100 convention as
 * [Memory.emotionalIntensity]. `createdAt`/`lastTriggeredAt` are sim minutes, matching every
 * other time field on [Resident]. `decayRate` is intensity lost per in-world day, applied by
 * `EmotionSystem.updateDaily`; the emotion is removed once its intensity is negligible.
 */
@Serializable
data class ActiveEmotion(
    val type: EmotionType,
    var intensity: Double,
    val sourceEventId: Long? = null,
    val relatedResidentId: Long? = null,
    val createdAt: Long,
    var lastTriggeredAt: Long,
    val decayRate: Double
)

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
    /**
     * Breathing space: sim-time before which this resident will not be given another
     * major life goal (new business, move home, marriage search). Set after completing or
     * abandoning a major goal so life events don't cascade unrealistically fast.
     * Default 0 = no cooldown (existing saves are unaffected).
     */
    var majorEventCooldownUntil: Long = 0L,

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
    /** Lifetime drift accumulated from lived experience, on top of [personality]'s
     *  untouched birth baseline. Safe default so existing checkpoints deserialize
     *  unchanged. See [PersonalityModifiers] and [effectivePersonality]. */
    val personalityModifiers: PersonalityModifiers = PersonalityModifiers(),
    val skills: MutableMap<SkillType, Double> = mutableMapOf(),
    val conditions: MutableList<HealthCondition> = mutableListOf(),
    val memories: MutableList<Memory> = mutableListOf(),
    val goals: MutableList<Goal> = mutableListOf(),
    /** Currently-felt emotional reactions — see [ActiveEmotion]/`EmotionSystem`. A new, safe-
     *  default field (empty list) so existing checkpoints deserialize unchanged. Bounded to
     *  `EmotionSystem.MAX_ACTIVE_EMOTIONS`, oldest/weakest evicted first, same convention as
     *  [memories]' own cap in `TickContext.addMemory`. */
    val activeEmotions: MutableList<ActiveEmotion> = mutableListOf(),

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
    val knownFacts: MutableList<Long> = mutableListOf(),
    // Ids of LocalLegends this resident has heard and believes (or has been told about). Stored
    // here rather than on LocalLegend.believerIds to keep legend data small and checkpoint-compat.
    val knownLegendIds: MutableList<Long> = mutableListOf(),

    // Abstract, ownable ideas this resident currently holds (any strength) — see [IdeaTemplate]/
    // [ResidentIdeaState] and `IdeaDiffusionSystem`. Distinct from [ideaSeeds] above: ideaSeeds is
    // a single narrow string hint consumed once by GoalSystem's START_BUSINESS check; this is a
    // real spreading/mutating/decaying social phenomenon with per-resident strength. Safe default
    // (empty list) so existing checkpoints deserialize unchanged. Bounded like [activeEmotions],
    // see `IdeaDiffusionSystem.MAX_ACTIVE_IDEAS`.
    val activeIdeas: MutableList<ResidentIdeaState> = mutableListOf(),

    /** This resident's formed views on [BeliefTopic]s — see [Belief]/`BeliefSystem`. A new,
     *  safe-default field (empty map) so existing checkpoints deserialize unchanged. A resident
     *  only has an entry for a topic they've actually formed a view on; a missing entry means
     *  "no strong opinion yet" — read via `BeliefSystem.positionOn`/`confidenceOn` rather than
     *  this map directly. Not to be confused with [Memory.beliefFormed] (a short quoted-saying
     *  string passed down at death, see `LifecycleSystem.passDownBeliefs`) — a different, older,
     *  unrelated concept despite the similar name. */
    val beliefs: MutableMap<BeliefTopic, Belief> = mutableMapOf(),

    // --- Human society evolution (2026-07-11) ---
    val aspirations: MutableList<Aspiration> = mutableListOf(),
    val identityFacets: MutableList<IdentityFacet> = mutableListOf(),
    var lifeSatisfaction: LifeSatisfaction = LifeSatisfaction(),
    val hobbies: MutableList<HobbyEngagement> = mutableListOf(),
    // --- Caregiver system (2026-07-11): resident responsible for this child when needsCaregiver ---
    var caregiverId: Long? = null,
    // --- Government, Politics & Public Policy (2026-07-11) ---
    var partyId: Long? = null
) {
    val fullName: String get() = "$firstName $surname"

    fun ageAt(now: Long): Int = SimTime.ageYears(bornAt, now)

    fun lifeStageAt(now: Long): LifeStage = when (ageAt(now)) {
        in 0..12 -> LifeStage.CHILD
        in 13..17 -> LifeStage.TEEN
        in 18..64 -> LifeStage.ADULT
        else -> LifeStage.ELDER
    }

    fun detailedLifeStageAt(now: Long): DetailedLifeStage = when (ageAt(now)) {
        0 -> DetailedLifeStage.NEWBORN
        1, 2 -> DetailedLifeStage.INFANT
        3, 4 -> DetailedLifeStage.TODDLER
        in 5..11 -> DetailedLifeStage.CHILD
        in 12..17 -> DetailedLifeStage.TEENAGER
        in 18..25 -> DetailedLifeStage.YOUNG_ADULT
        in 26..39 -> DetailedLifeStage.ADULT
        in 40..59 -> DetailedLifeStage.MIDDLE_AGE
        in 60..74 -> DetailedLifeStage.SENIOR
        else -> DetailedLifeStage.ELDERLY
    }

    fun mood(): Mood = Mood.fromScore(needs.wellbeing())

    fun skill(type: SkillType): Double = skills[type] ?: 5.0

    fun activeConditions(): List<HealthCondition> = conditions.filter { it.active }

    fun hasSeriousCondition(): Boolean = activeConditions().any { it.type.serious }

    /** Effective location for interaction purposes: building the resident is in, or is heading to. */
    fun locationBuildingId(): Long? = currentBuildingId ?: travelToBuildingId

    val inTown: Boolean get() = alive && leftTownAt == null

    /**
     * The personality this resident actually acts on: birth [personality] baseline plus
     * accumulated [personalityModifiers] drift, each trait individually clamped back into
     * [Personality]'s own 0.0..1.0 range. A fresh [Personality] instance each call — never
     * mutates [personality] itself, so the birth baseline stays exactly as
     * `LifecycleSystem.inheritPersonality` produced it, unmodified, forever (checkpoint- and
     * inheritance-stable). Call sites that care about "who this resident has become" (decision
     * scoring, compatibility, goal gates) should read this; call sites that care about "who a
     * parent innately was" (breeding the next generation) should keep reading [personality].
     */
    fun effectivePersonality(): Personality {
        val m = personalityModifiers
        fun eff(base: Double, delta: Double) = (base + delta).coerceIn(0.0, 1.0)
        return Personality(
            kindness = eff(personality.kindness, m.kindness),
            ambition = eff(personality.ambition, m.ambition),
            curiosity = eff(personality.curiosity, m.curiosity),
            sociability = eff(personality.sociability, m.sociability),
            patience = eff(personality.patience, m.patience),
            honesty = eff(personality.honesty, m.honesty),
            courage = eff(personality.courage, m.courage),
            discipline = eff(personality.discipline, m.discipline),
            empathy = eff(personality.empathy, m.empathy),
            impulsiveness = eff(personality.impulsiveness, m.impulsiveness)
        )
    }

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
