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
    PETITION_RESOLVED("Petition resolved"),
    PRICES_SHIFTED("Prices shifting"),
    BUSINESS_SUCCESSION("Business handed down"),
    /** A struggling business's owner takes a real recovery action beyond a price cut/layoff —
     *  seeking finance, an owner capital injection, or restructuring/relocating. Economy
     *  Calibration Gate Phase 2 (2026-07-11), see docs/simulation-rules.md "Recovery ladder". */
    BUSINESS_RECOVERY_ACTION("Fighting to stay open"),
    /** A business genuinely recovers out of trouble following a recovery-ladder action —
     *  `daysInTrouble` returns to 0 from a real AT_RISK-or-worse state, not just never having
     *  been in trouble. Economy Calibration Gate Phase 2 (2026-07-11). */
    BUSINESS_RECOVERED("Business recovered"),
    /** A periodic contract won by a WORKSHOP/FACTORY — external/institutional demand, not
     *  resident footfall. Economy Calibration Gate Phase 2 (2026-07-11), see
     *  docs/simulation-rules.md "External/contract demand". */
    CONTRACT_WON("Contract won"),
    HOME_PURCHASED("Home purchased"),
    /** A curated, abstract national-scale pressure starting or resolving. See
     *  `ExternalWorldEventProvider`. Deliberately background/town-wide news, not personal. */
    NATIONAL_PRESSURE("National pressure"),

    // --- Incident severity system (added 2026-07-10) — see docs/simulation-rules.md
    // "Incidents: severity-graded texture". ARGUMENT (Level 1) already existed above and is
    // reused as-is for "verbal argument"; these are the genuinely new incident flavours.
    /** Level 1 — shoplifting caught or suspected at a business. Routed through `CrimeSystem`. */
    SHOPLIFTING("Shoplifting"),
    /** Level 1 — a building or public space is deliberately damaged, short of arson. */
    VANDALISM("Vandalism"),
    /** Level 2 — a resident goes missing for a period, prompting a town search. */
    MISSING_PERSON_REPORTED("Missing person"),
    /** Level 2 — a missing resident is found (safe, or otherwise). */
    MISSING_PERSON_FOUND("Missing person found"),
    /** Level 2 — a home is broken into while unoccupied. Routed through `CrimeSystem`. */
    BURGLARY("Burglary"),
    /** Level 2 — a resident is confronted and robbed in a public space. Routed through `CrimeSystem`. */
    MUGGING("Mugging"),
    /** Level 2 — a household relationship's strain boils over into a reported disturbance. */
    DOMESTIC_DISTURBANCE("Domestic disturbance"),
    /** Level 2 — should-tier: a vehicle-equivalent (cart/bicycle) is stolen. Routed through `CrimeSystem`. */
    VEHICLE_THEFT("Vehicle theft"),
    /** Level 2 — should-tier: a business owner falsifies books or claims for money. Routed through `CrimeSystem`. */
    FRAUD("Fraud"),
    /** Level 2 — should-tier: a deliberate fire-setting attempt at a business. Routed through `CrimeSystem`. */
    ARSON_ATTEMPT("Arson attempt"),
    /** Level 2 — should-tier: a workplace accident significant enough to injure a worker. */
    WORKPLACE_ACCIDENT("Workplace accident"),
    /** Level 2 — should-tier: a petition's supporters turn a resolution into a public disruption. */
    PROTEST_DISRUPTION("Protest disruption"),

    /** A resident's lived experience has nudged one trait of their [effectivePersonality]
     *  slightly, permanently, within the lifetime drift cap. See `PersonalityDevelopmentSystem`
     *  and `docs/simulation-rules.md` "Personality drift from lived experience". Internal
     *  character-development texture, not town news — always `PRIVATE`, low severity. */
    PERSONALITY_SHIFTED("Personality shift"),

    /** A past [com.ripple.town.core.model.Memory] has resurfaced — matching location, company,
     *  recurring event type, or a current active emotion echoing an old one. See
     *  `MemoryRecallSystem` and `docs/simulation-rules.md` "Memory recall". Internal texture for
     *  the cause chain, essentially never newsworthy — always `PRIVATE`, very low severity. */
    MEMORY_RECALLED("A memory resurfaces"),

    /** A resident's [com.ripple.town.core.model.ResidentIdeaState.beliefStrength] for some
     *  [com.ripple.town.core.model.IdeaTemplate] has crossed the adoption threshold — they've
     *  genuinely come to hold the idea, not just heard of it. See `IdeaDiffusionSystem` and
     *  `docs/simulation-rules.md` "Idea diffusion". Low severity, `PRIVATE`: a personal shift in
     *  outlook, not town news the way a `RUMOUR_SPREAD` event is. */
    IDEA_ADOPTED("Idea adopted"),

    /** A resident's real lived experience (or inheritance from a parent) has nudged one
     *  [com.ripple.town.core.model.BeliefTopic] position slightly. See `BeliefSystem` and
     *  `docs/simulation-rules.md` "Beliefs". Internal character-development texture, not town
     *  news — always `PRIVATE`, low severity. Falls through every exhaustive-`when`'s `else ->`
     *  branch safely (verified against [com.ripple.town.core.simulation.ImportanceScorer] and
     *  [com.ripple.town.core.simulation.NewspaperGenerator] before adding). */
    BELIEF_SHIFTED("Belief shift")
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
    MOOD_LIFT,
    /**
     * Marks a bounded "in shock" window after sudden personal loss (job loss, business
     * closure, bereavement) — see `docs/simulation-rules.md` "Shock period after major
     * personal loss". Unlike every other type here, this one does nothing when it fires:
     * its entire purpose is to *exist* as a queryable record while `earliestAt..latestAt`
     * contains "now". `EconomySystem.isInShock`/`DelayedEffectSystem.conditionHolds` read the
     * presence of an un-applied, un-cancelled effect of this type directly rather than waiting for
     * [DelayedEffectSystem] to probabilistically fire it — see that object's `apply()` for
     * the no-op case. The existing lapse-on-window-close logic still cleans it up for free.
     */
    SHOCK_PERIOD
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
