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
    /** National tax-rate pressure rising — the "national layer" addition, mapped to a small
     *  hit on resident `financialSecurity` at daily settlement. See
     *  [com.ripple.town.core.simulation.WorldPressureMechanicMapper]. */
    TAX_RATE_RISES,
    /** The same pressure easing back off — a small relief on resident `financialSecurity`. */
    TAX_RATE_EASES,
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
 * A short, read-only record of a pressure that has already started (and, once resolved, when
 * it ended) — the "trends" half of the national-layer addition. Deliberately minimal: just
 * enough for a town to have a sense of "how things have been going nationally" (a rolling
 * history, surfaced later by UI/newspaper work) without duplicating [ExternalPressure] itself
 * as the live/authoritative record. See [com.ripple.town.core.simulation.CuratedWorldPressureFeed].
 */
@Serializable
data class PressureHistoryEntry(
    val kind: ExternalPressureKind,
    val startedAt: Long,   // sim minutes
    val endsAt: Long?      // sim minutes — null while still active
)

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
 * Persistent, town-wide aggregate mood — distinct from any one resident's [Belief]s (an
 * individual's settled worldview) or the live `TownStatsUi.averageWellbeing` (a per-tick
 * average of current `Needs.stress`, recomputed fresh every read, never stored). This is the
 * town's own slow-moving emotional weather: it accumulates from real aggregated events over
 * days, decays gently back toward neutral when nothing is actively pushing it, and is read back
 * by other systems as a small, bounded behavioural input. See
 * `com.ripple.town.core.simulation.TownSentimentSystem` and `docs/simulation-rules.md`
 * "Town sentiment".
 *
 * Six dimensions, each `0.0..100.0` with `50.0` as the neutral baseline (mirrors `Needs`'
 * own 0..100 scale rather than `Belief`'s signed -1..1 — sentiment is a *level*, not a
 * position on a for/against axis):
 *
 * - [trust] — faith in the town's institutions/governance. Deliberately NOT a duplicate of
 *   `BeliefTopic.TRUST_IN_GOVERNMENT`: that's one resident's personal, settled opinion; this is
 *   a town-wide readout partly *sourced from* the mean of those opinions (see
 *   `TownSentimentSystem.applyBeliefReadout`) but also independently moved by aggregate events
 *   (petitions, crime) no single belief trigger captures at the town level.
 * - [fear] — how anxious the town feels about crime/safety right now.
 * - [optimism] — how good the near future looks, echoing (partly reading from) the town-wide
 *   mean `BeliefTopic.ECONOMIC_OPTIMISM`, the same "partly an aggregate readout" relationship as
 *   [trust].
 * - [civicPride] — how good the town feels about itself and its own recent conduct (crisis
 *   response, successful petitions) — deliberately kept distinct from [trust] (pride is about
 *   the town's *character*, trust is about whether its institutions can be relied on) rather
 *   than folded into a single "institutional confidence" number.
 * - [safety] — the practical, crime-driven cousin of [fear] (kept as its own dimension rather
 *   than collapsed into `1 - fear`, since the brief explicitly lists both and they can
 *   plausibly diverge — a town can feel unsettled/fearful in the abstract while its actual
 *   recent safety record stays fine, or vice versa).
 * - [cohesion] — how much the town pulls together, fed by petition/community outcomes,
 *   distinct from [civicPride] (cohesion is "do we act as one", pride is "do we feel good about
 *   ourselves").
 *
 * Deliberately excluded from the brief's fuller list: "political tension" (would just be the
 * inverse of [cohesion]/[trust]); "grief" (already exists per-resident as
 * `MemoryType`/`EmotionType.GRIEF` — no town-wide aggregate need identified this pass);
 * "economic confidence" (== [optimism], just a synonym); "police legitimacy" (== [trust]
 * narrowed to the constable specifically, which `BeliefTopic.TRUST_IN_POLICE` already covers
 * per-resident with no town-wide consumer needed yet).
 */
@Serializable
data class TownSentiment(
    var trust: Double = 50.0,
    var fear: Double = 50.0,
    var optimism: Double = 50.0,
    var civicPride: Double = 50.0,
    var safety: Double = 50.0,
    var cohesion: Double = 50.0
)

/**
 * A `Business.priceLevel` surcharge `PressureBridgeSystem` owes back once its recovery window
 * closes — see `WorldState.pendingPriceEasing`'s own doc comment.
 */
@Serializable
data class PendingPriceEase(
    val amount: Double,
    val easeAt: Long   // sim minutes
)

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
    val districts: MutableMap<Long, District> = mutableMapOf(),
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

    /**
     * residentId -> sim time this resident was last the subject (victim, culprit, or reported
     * party — whichever the specific incident check cares about) of an incident-severity-system
     * event (`CrimeSystem`'s new incident types plus `IncidentSystem`). Same shape as
     * `lastInterventionAt`, kept separate since the two cooldowns are conceptually unrelated and
     * shouldn't share a key space. Read/written by each incident's own `COOLDOWN_DAYS` check —
     * see `docs/simulation-rules.md`'s "Incidents" section.
     */
    val lastIncidentAt: MutableMap<Long, Long> = mutableMapOf(),

    /**
     * Residents currently reported missing (`IncidentSystem.updateMissingPerson`), a small
     * transient roster distinct from `lastIncidentAt` — a resident stays listed here for the
     * whole window between `MISSING_PERSON_REPORTED` and `MISSING_PERSON_FOUND`, not just a
     * cooldown timestamp. Kept small and self-cleaning: entries are removed the day
     * `IncidentSystem.resolveMissingPersons` finds them.
     */
    val missingResidentIds: MutableList<Long> = mutableListOf(),
    /** residentId -> sim time `IncidentSystem.resolveMissingPersons` should bring them home. */
    val missingResolveAt: MutableMap<Long, Long> = mutableMapOf(),
    /** residentId -> the `MISSING_PERSON_REPORTED` event id, so `MISSING_PERSON_FOUND` can causeIds-link back to it. */
    val missingPersonEventId: MutableMap<Long, Long> = mutableMapOf(),

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
    /**
     * A short rolling history of past national pressures (most recent last), capped at
     * [com.ripple.town.core.simulation.CuratedWorldPressureFeed.PRESSURE_HISTORY_LIMIT] entries —
     * the "trends" half of the national-layer backlog item. Gives the town a sense of "how
     * things have been going nationally" beyond just the single live pressure slot above; not
     * yet surfaced anywhere in UI/newspaper, just modelled and maintained here.
     */
    val pressureHistory: MutableList<PressureHistoryEntry> = mutableListOf(),
    /**
     * A simple national tax-rate multiplier, nudged slowly by [ExternalPressureKind.TAX_RATE_RISES]/
     * [ExternalPressureKind.TAX_RATE_EASES] pressures — the "taxes" half of the national-layer
     * backlog item. `1.0` is neutral; bounded to
     * `WorldPressureMechanicMapper.NATIONAL_TAX_RATE_MIN`..`NATIONAL_TAX_RATE_MAX` (0.9–1.1).
     * Read by [com.ripple.town.core.simulation.WorldPressureMechanicMapper] and applied through
     * [com.ripple.town.core.simulation.EconomySystem]'s existing daily settlement — never a second,
     * parallel settlement path.
     */
    var nationalTaxRate: Double = 1.0,

    /**
     * The town's slow-moving aggregate mood — see [TownSentiment]'s own doc comment and
     * `com.ripple.town.core.simulation.TownSentimentSystem`. Safe default (all dimensions at
     * the neutral `50.0` baseline), no migration needed.
     */
    var townSentiment: TownSentiment = TownSentiment(),
    /**
     * Bounded de-dup log of one-shot `TownSentimentSystem` trigger reason-keys already applied
     * (e.g. `"petition:<eventId>"`) — stops a resolved petition sitting inside the recent-events
     * window from nudging trust/cohesion again every day it stays in view. Same bounded-list-of-
     * strings shape `Resident.awareness` uses for cooldowns, capped at
     * `TownSentimentSystem.MAX_APPLIED_REASONS`, scoped to the world (rather than any one
     * resident) since sentiment itself is world-scoped.
     */
    val townSentimentAppliedReasons: MutableList<String> = mutableListOf(),

    // Id counters (all state needed for deterministic continuation)
    var nextDistrictId: Long = 1L,
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
    val recentEventIds: MutableList<Long> = mutableListOf(),

    /**
     * How many in-game days in a row `weather` has been RAIN/STORM/SNOW/FOG, right up to and
     * including today — updated once daily by `PressureBridgeSystem.updateDaily` (cross-system
     * pressure bridges, see `docs/simulation-rules.md`). Resets to 0 the moment weather turns
     * CLEAR/CLOUDY again. A new, safe-default field (0) so existing checkpoints deserialize
     * unchanged.
     */
    var consecutivePoorWeatherDays: Int = 0,

    /**
     * residentId -> sim time `PressureBridgeSystem.onSustainedFinancialTrouble` last nudged that
     * resident's partner relationship for sustained debt-crisis strain — a cooldown so a
     * weeks-long crisis doesn't compound the relationship hit daily. Same shape as
     * `lastIncidentAt`, kept separate since the two cooldowns are conceptually unrelated. A new,
     * safe-default field (empty map) so existing checkpoints deserialize unchanged.
     */
    val lastFinancialStrainNudgeAt: MutableMap<Long, Long> = mutableMapOf(),

    /**
     * businessId -> (the `priceLevel` surcharge still owed back, sim time it should be eased).
     * `PressureBridgeSystem.applyTemporaryDemandPenalty`'s flood/storm price-bump leg (Bridge 2)
     * bumps `Business.priceLevel` directly and records the debt here so
     * `PressureBridgeSystem.easePriceBumps` can hand it back once the recovery window closes —
     * kept on `WorldState` (not a static/in-memory map) specifically so it survives a checkpoint
     * reload exactly like every other pending consequence in this codebase. Small and
     * self-cleaning: entries are removed the moment they're eased. A new, safe-default field
     * (empty map) so existing checkpoints deserialize unchanged.
     */
    val pendingPriceEasing: MutableMap<Long, PendingPriceEase> = mutableMapOf(),

    // --- Dynamic town growth & decline (added 2026-07-11) ---
    /**
     * Active and historical development projects — from TownNeedsPlanner proposals
     * through construction to completion. Safe default (empty map) so existing
     * checkpoints deserialize unchanged.
     */
    val developmentProjects: MutableMap<Long, DevelopmentProject> = mutableMapOf(),
    /**
     * The town's municipal finances: tax/rate income, service expenses, construction
     * funding. Safe default (starting reserves £50k). See [MunicipalBudget].
     */
    var municipalBudget: MunicipalBudget = MunicipalBudget(),
    /**
     * Latest demand-vs-capacity snapshot for each [ServiceType], keyed by
     * `ServiceType.name`. Recomputed monthly by [TownNeedsPlanner].
     */
    val servicePressures: MutableMap<String, ServicePressure> = mutableMapOf(),
    var nextProjectId: Long = 1L,
    /**
     * Town-wide event-cadence cooldowns keyed by [com.ripple.town.core.simulation.HumanScheduler]
     * activity names. Records the sim-time each activity type last fired, so the scheduler can
     * prevent clusters ("three weddings this week"). Written by
     * [com.ripple.town.core.simulation.HumanScheduler.recordFired].
     */
    val activityCooldowns: MutableMap<String, Long> = mutableMapOf(),

    // --- Anomalies, legends & mysteries (2026-07-11) ---
    /** Local legends that have emerged from coincidence and spread by word of mouth.
     *  Managed by [com.ripple.town.core.simulation.LegendSystem]. */
    val localLegends: MutableMap<Long, LocalLegend> = mutableMapOf(),
    /** Crimes and incidents whose investigation went cold.
     *  Managed by [com.ripple.town.core.simulation.UnsolvedCaseSystem]. */
    val unsolvedCases: MutableMap<Long, UnsolvedCase> = mutableMapOf(),
    /** Statistical anomalies detected by [com.ripple.town.core.simulation.AnomalyDetector]. */
    val anomalyRecords: MutableList<AnomalyRecord> = mutableListOf(),
    /** Defining historical eras the town collectively remembers.
     *  Managed by [com.ripple.town.core.simulation.TownEraSystem]. */
    val townEras: MutableList<TownEra> = mutableListOf(),
    var nextLegendId: Long = 1L,
    var nextCaseId: Long = 1L,
    var nextEvidenceId: Long = 1L,
    var nextEraId: Long = 1L,
    var nextAnomalyId: Long = 1L,

    // --- Human society evolution (2026-07-11) ---
    val legacyRecords: MutableList<LegacyRecord> = mutableListOf(),
    var townCulture: TownCulture = TownCulture(),
    var nextLegacyId: Long = 1L,
    val institutionRecords: MutableMap<Long, InstitutionRecord> = mutableMapOf(),
    val communityGroups: MutableMap<Long, CommunityGroup> = mutableMapOf(),
    var nextInstitutionId: Long = 1L,
    var nextGroupId: Long = 1L
) {
    fun district(id: Long): District? = districts[id]

    fun districtAt(x: Int, y: Int): District? = districts.values.firstOrNull { it.containsTile(x, y) }

    fun developmentProject(id: Long): DevelopmentProject? = developmentProjects[id]

    fun servicePressure(service: ServiceType): ServicePressure? = servicePressures[service.name]

    fun activeDevelopmentProjects(): List<DevelopmentProject> =
        developmentProjects.values.filter {
            it.stage != DevelopmentStage.COMPLETE &&
            it.stage != DevelopmentStage.REJECTED &&
            it.stage != DevelopmentStage.CANCELLED
        }

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
