package com.ripple.town.data

import androidx.compose.runtime.Immutable
import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.AspirationStatus
import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EmergenceRecord
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Mood
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.PolicyStatus
import com.ripple.town.core.model.SimSpeed
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SpriteConfig
import com.ripple.town.core.model.TimeOfDay
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.Weather
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.WorldState

/**
 * Immutable, UI-facing snapshot of the world, rebuilt after each simulation
 * batch. Compose reads only these stable models — never the mutable engine
 * state — so recomposition stays cheap and the engine stays isolated.
 */
@Immutable
data class WorldUi(
    val worldSeed: Long,
    val townName: String,
    val time: Long,
    val dateLabel: String,
    val clockLabel: String,
    val timeOfDay: TimeOfDay,
    val weather: Weather,
    val population: Int,
    val nudges: Int,
    val maxNudges: Int,
    val nudgeRegenProgressMinutes: Long = 0L,
    val lastInterventionAt: Map<Long, Long> = emptyMap(),
    val followedResidentId: Long?,
    val favouriteIds: List<Long>,
    val discoveredIds: List<Long>,
    val mayorId: Long?,
    val councillorIds: List<Long>,
    val residents: List<ResidentUi>,
    val buildings: List<BuildingUi>,
    val map: TownMap,
    val emergenceRecords: List<EmergenceRecord> = emptyList(),
    /** Sorted list of district names + their approximate map centre. */
    val districtNav: List<DistrictNavEntry> = emptyList(),
    /** District character panel data — sorted by name. */
    val districts: List<DistrictSummaryUi> = emptyList(),
    /** Liveliness score from the last NarrativePlausibilityReport (0=robotic, 100=alive).
     *  Null until the engine has produced at least one report (~first month of sim time). */
    val plausibilityScore: Double? = null,
    /** Short human-readable descriptions of the most severe plausibility issues found,
     *  capped at 3 so the UI stays scannable. Empty when none were flagged. */
    val plausibilityIssues: List<String> = emptyList(),
    /** Political snapshot for the Town Overview "Politics" tab. Null until the
     *  simulation has elected its first mayor (mayorId non-null). */
    val politics: PoliticsSummaryUi? = null,
    /**
     * Last 3 distinct culture-description strings from [WorldState.townCultureHistory],
     * newest first — used by [TownSheets] to render a "Town character" trajectory block.
     * Empty until [TownCultureSystem] has run at least once. Safe default (empty list).
     */
    val townCharacterHistory: List<String> = emptyList(),
    /**
     * Top families by reputation score, filtered to those with at least one living member.
     * Capped at 8 entries. Empty until [FamilyLegacySystem] has run at least once.
     */
    val families: List<FamilyLegacyUi> = emptyList(),
    /** Active community groups, sorted by member count descending, capped at 6. */
    val communityGroups: List<CommunityGroupUi> = emptyList()
) {
    val residentsById: Map<Long, ResidentUi> by lazy { residents.associateBy { it.id } }
    val buildingsById: Map<Long, BuildingUi> by lazy { buildings.associateBy { it.id } }
    fun resident(id: Long?): ResidentUi? = id?.let { residentsById[it] }
    fun building(id: Long?): BuildingUi? = id?.let { buildingsById[it] }

    /**
     * Town-level stats for the overview overlay. Derived client-side from the
     * living, in-town resident list already carried on this snapshot — the
     * simulation core has no dedicated town-statistics tracker, so this is
     * everything that can be shown without inventing new simulation data.
     */
    val townStats: TownStatsUi by lazy {
        val livingInTown = residents.filter { it.alive && it.inTown }
        TownStatsUi(
            population = population,
            averageWellbeing = livingInTown.map { 100.0 - it.stress }.average0(),
            averageHealth = livingInTown.map { it.health }.average0(),
            averageWealth = livingInTown.map { it.wealth }.average0(),
            employedCount = livingInTown.count { it.occupation.isNotBlank() && it.employerName != null }
        )
    }
}

private fun List<Double>.average0(): Double = if (isEmpty()) 0.0 else average()

// ── Community group UI model ──────────────────────────────────────────────────

@Immutable
data class CommunityGroupUi(
    val name: String,
    val type: String,
    val memberCount: Int,
    val reputation: Int,
    val leaderName: String
)

// ── Politics UI models ────────────────────────────────────────────────────────

/** One party's standing in the current council snapshot. */
@Immutable
data class PartyStandingUi(
    val name: String,
    val seats: Int,
    val approval: Int,
    val isGoverning: Boolean
)

/**
 * Political snapshot surfaced in the Town Overview "Politics" tab.
 * Built once per snapshot from the mutable engine state — the UI never
 * reads WorldState directly.
 */
@Immutable
data class PoliticsSummaryUi(
    val mayorName: String,
    val mayorApproval: Int,             // 0–100
    val isElectionActive: Boolean,
    val daysUntilNextElection: Int,
    val parties: List<PartyStandingUi>,
    val recentPolicies: List<String>,   // last ≤3 enacted policy titles
    val currentAdminSummary: String     // e.g. "Year 4 of the Smith administration"
)

// ─────────────────────────────────────────────────────────────────────────────

/** Town-wide aggregate figures for the town-overview overlay. */
@Immutable
data class TownStatsUi(
    val population: Int,
    val averageWellbeing: Double,
    val averageHealth: Double,
    val averageWealth: Double,
    val employedCount: Int
)

@Immutable
data class ResidentUi(
    val id: Long,
    val name: String,
    val firstName: String,
    val surname: String,
    val age: Int,
    val lifeStage: LifeStage,
    val genderLabel: String,
    val alive: Boolean,
    val inTown: Boolean,
    val diedAt: Long?,
    val causeOfDeath: String?,
    val detailed: Boolean,
    val occupation: String,
    val employerName: String?,
    val activity: Activity,
    val activityReason: String,
    val mood: Mood,
    val sprite: SpriteConfig,
    /** Tile-space position (fractional while travelling). */
    val x: Float,
    val y: Float,
    val visibleOnMap: Boolean,
    val homeBuildingId: Long?,
    val homeName: String?,
    val householdId: Long?,
    val currentBuildingId: Long?,
    val partnerId: Long?,
    val motherId: Long?,
    val fatherId: Long?,
    val childIds: List<Long>,
    val wealth: Double,
    val debt: Double,
    val relationshipStatusLabel: String,
    // Needs 0..100 for the sheet
    val hunger: Double,
    val energy: Double,
    val health: Double,
    val social: Double,
    val stress: Double,
    val purposeNeed: Double,
    val comfort: Double,
    val safety: Double,
    val financialSecurity: Double,
    val skills: Map<String, Double>,
    /**
     * Premium profile redesign (2026-07-10): the Personality tab needs the resident's own
     * trait values, which — despite already existing on the engine-side [Resident] model —
     * were never carried onto the UI-facing snapshot before. Exposed as-is (0.0..1.0 per
     * trait), no new simulation fields invented.
     */
    val personality: Personality,
    val activeGoalLabels: List<String>,
    val conditionLabels: List<String>,
    val memories: List<MemoryUi>,
    val relationships: List<RelationUi>,
    /** Aspirations: list of (typeLabel, statusLabel, progress 0-100) triples. */
    val aspirations: List<Triple<String, String, Double>> = emptyList(),
    /** Identity facets the resident has accumulated (IdentityLabel.label strings). */
    val identityFacetLabels: List<String> = emptyList(),
    /** Life-satisfaction overall score (0-100). */
    val lifeSatisfactionScore: Double = 50.0,
    /** Life-satisfaction breakdown: list of (dimension, value) pairs. */
    val lifeSatisfactionBreakdown: List<Pair<String, Double>> = emptyList()
)

@Immutable
data class MemoryUi(
    val description: String,
    val intensity: Double,
    val createdAt: Long,
    val typeLabel: String
)

@Immutable
data class RelationUi(
    val otherId: Long,
    val otherName: String,
    val kindLabel: String,
    val warmth: Double,
    val trust: Double,
    val affection: Double,
    val resentment: Double,
    val familiarity: Double
)

/** A district entry for the map navigation bar — name + approximate tile centre. */
@Immutable
data class DistrictNavEntry(val name: String, val cx: Float, val cy: Float)

/** UI-facing summary of a single district's character and key statistics. */
@Immutable
data class DistrictSummaryUi(
    val name: String,
    /** e.g. "GENTRIFYING", "STABLE", "DECLINING" — the DistrictCharacter label uppercased. */
    val character: String,
    /** 0..100 composite wealth/employment score; 50 = town average. */
    val prosperityIndex: Double,
    /** Normalised daily crime incidence rate 0..1. */
    val crimeRate: Double,
    /** Rolling resident population. */
    val population: Int,
    /** Fraction of buildings with no active occupants (0..1). */
    val vacancyRate: Double
)

/** UI-facing summary of a single family dynasty for the Town Overview "Families" section. */
@Immutable
data class FamilyLegacyUi(
    val surname: String,
    /** [FamilyReputationType] name, e.g. "POLITICAL_DYNASTY". */
    val reputationType: String,
    val generations: Int,
    val livingMembers: Int,
    val mayorships: Int,
    val businesses: Int,
    val reputationScore: Int,
    /** True if this family stands out — non-ORDINARY type, any mayorship, or 3+ businesses. */
    val isNoteworthy: Boolean
)

@Immutable
data class BuildingUi(
    val id: Long,
    val name: String,
    val type: BuildingType,
    val typeLabel: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val doorX: Int,
    val doorY: Int,
    val condition: Double,
    val noise: Double,
    val value: Double,
    val capacity: Int,
    val abandoned: Boolean,
    val upgradeLevel: Int,
    val ownerName: String?,
    val occupantIds: List<Long>,
    val businessName: String?,
    val businessOpen: Boolean?,
    val businessBalance: Double?,
    val businessReputation: Double?,
    val employeeNames: List<String>,
    val visibleChanges: List<String>,
    /**
     * Building sheet redesign (2026-07-10): fields already present on the engine-side
     * [Building]/[Business] models but never previously carried onto the UI snapshot.
     * Exposed as-is, no new simulation data invented — see `BuildingSheetContent` in
     * `TownSheets.kt` for what reads these.
     */
    val buildingState: BuildingState = BuildingState.OCCUPIED,
    val ownerId: Long? = null,
    val businessDaysInTrouble: Int? = null,
    val businessDemand: Double? = null,
    val businessPriceLevel: Double? = null,
    val businessCustomersToday: Int? = null,
    val businessRevenueToday: Double? = null,
    val businessExpensesToday: Double? = null,
    val businessEmployeeCapacity: Int? = null,
    val businessEmployeeIds: List<Long> = emptyList(),
    val businessClosedAt: Long? = null,
    val districtName: String? = null,
    val districtCharacter: String? = null,
    val constructedAt: Long? = null,
    /** Ids of residents who previously lived/worked here and have since departed. */
    val tenantHistory: List<Long> = emptyList(),
    /** Name of the rival business (same type, owner has RIVAL relationship with this business's owner), if any. */
    val businessRivalName: String? = null
)

/** Simulation status shown while catching up after reopening the app. */
@Immutable
data class CatchUpProgress(
    val running: Boolean,
    val totalTicks: Int,
    val doneTicks: Int,
    val summary: String? = null
)

object SnapshotBuilder {

    fun build(state: WorldState): WorldUi {
        // Pre-index businesses by buildingId so buildingUi() is O(1) instead of O(businesses).
        val bizByBuilding = state.businesses.values.groupBy { it.buildingId }
        val discoveredIds = state.discoveredResidentIds.toHashSet()
        val residents = state.residentsOrdered().map { r -> residentUi(state, r, discoveredIds) }
        // Pre-sort by painter's order (y + height) so TownRenderer draws in the right order
        // without paying a per-frame sort across the full list.
        val buildings = state.buildings.values.sortedBy { it.origin.y + it.height }
            .map { b -> buildingUi(state, b, bizByBuilding) }
        // District nav: average building centre per districtId, sorted by name.
        val districtNav = state.buildings.values
            .filter { it.districtId != null }
            .groupBy { it.districtId!! }
            .mapNotNull { (distId, bldgs) ->
                val name = state.districts[distId]?.name ?: return@mapNotNull null
                val cx = bldgs.map { it.centre().x.toFloat() }.average().toFloat()
                val cy = bldgs.map { it.centre().y.toFloat() }.average().toFloat()
                DistrictNavEntry(name, cx, cy)
            }
            .sortedBy { it.name }
        val districts = state.districts.values
            .sortedBy { it.name }
            .map { d ->
                DistrictSummaryUi(
                    name = d.name,
                    character = d.character.label.uppercase(),
                    prosperityIndex = d.prosperityIndex,
                    crimeRate = d.crimeRate,
                    population = d.population,
                    vacancyRate = d.vacancyRate
                )
            }
        return WorldUi(
            worldSeed = state.seed,
            townName = state.townName,
            time = state.time,
            dateLabel = SimTime.formatDate(state.time),
            clockLabel = SimTime.formatClock(state.time),
            timeOfDay = SimTime.timeOfDay(state.time),
            weather = state.weather,
            population = state.population(),
            nudges = state.nudges,
            maxNudges = state.maxNudges,
            nudgeRegenProgressMinutes = state.nudgeRegenProgressMinutes,
            lastInterventionAt = state.lastInterventionAt.toMap(),
            followedResidentId = state.followedResidentId,
            favouriteIds = state.favouriteResidentIds.toList(),
            discoveredIds = state.discoveredResidentIds.toList(),
            mayorId = state.mayorId,
            councillorIds = state.councillorIds.toList(),
            residents = residents,
            buildings = buildings,
            map = state.map,
            districtNav = districtNav,
            districts = districts,
            emergenceRecords = state.plausibilityData.emergenceRecords
                .sortedByDescending { it.surpriseScore }.take(10),
            plausibilityScore = state.lastNarrativeReport?.overallScore,
            plausibilityIssues = state.lastNarrativeReport?.issues
                ?.sortedByDescending { it.severity }
                ?.take(3)
                ?.map { it.description }
                .orEmpty(),
            politics = buildPoliticsUi(state),
            townCharacterHistory = state.townCultureHistory
                .asReversed()
                .distinctBy { it.description }
                .take(3)
                .map { it.description },
            families = state.familyLegacies.values
                .filter { it.livingMembers > 0 }
                .sortedByDescending { it.reputation }
                .take(8)
                .map { f ->
                    FamilyLegacyUi(
                        surname = f.surname,
                        reputationType = f.reputationType,
                        generations = f.generations,
                        livingMembers = f.livingMembers,
                        mayorships = f.mayorships,
                        businesses = f.businessesOwned,
                        reputationScore = f.reputation.toInt(),
                        isNoteworthy = f.reputationType != com.ripple.town.core.model.FamilyReputationType.ORDINARY.name
                            || f.mayorships > 0
                            || f.businessesOwned > 2
                    )
                },
            communityGroups = state.communityGroups.values
                .filter { it.active && it.memberIds.size >= 2 }
                .sortedByDescending { it.memberIds.size }
                .take(6)
                .map { g ->
                    val leader = state.resident(g.founderResidentId)
                    CommunityGroupUi(
                        name = g.name,
                        type = g.type.label,
                        memberCount = g.memberIds.size,
                        reputation = g.reputation.toInt(),
                        leaderName = leader?.fullName ?: "Unknown"
                    )
                }
        )
    }

    private fun residentUi(state: WorldState, r: com.ripple.town.core.model.Resident, discoveredIds: Set<Long> = emptySet()): ResidentUi {
        val (x, y, visible) = positionOf(state, r)
        val employment = state.employmentOf(r)
        val employer = employment?.let { state.businesses[it.businessId]?.name }
        // Background residents never accumulate relationships/memories/conditions/skills —
        // skip the expensive collection traversals entirely for them.
        val isBackground = r.detailLevel == DetailLevel.BACKGROUND
        val relationships = if (isBackground) emptyList() else state.relationshipsOf(r.id)
            .filter { it.familiarity > 5 }
            .sortedByDescending { it.warmth() }
            .take(12)
            .mapNotNull { rel ->
                val other = state.resident(rel.other(r.id)) ?: return@mapNotNull null
                RelationUi(
                    otherId = other.id, otherName = other.fullName, kindLabel = rel.kind.label,
                    warmth = rel.warmth(), trust = rel.trust, affection = rel.affection,
                    resentment = rel.resentment, familiarity = rel.familiarity
                )
            }
        return ResidentUi(
            id = r.id,
            name = r.fullName,
            firstName = r.firstName,
            surname = r.surname,
            age = r.ageAt(state.time),
            lifeStage = r.lifeStageAt(state.time),
            genderLabel = when (r.gender) {
                com.ripple.town.core.model.Gender.FEMALE -> "Woman"
                com.ripple.town.core.model.Gender.MALE -> "Man"
                com.ripple.town.core.model.Gender.NONBINARY -> "Nonbinary"
            },
            alive = r.alive,
            inTown = r.inTown,
            diedAt = r.diedAt,
            causeOfDeath = r.causeOfDeath,
            detailed = r.id in discoveredIds,
            occupation = r.occupation,
            employerName = employer,
            activity = r.activity,
            activityReason = r.activityReason,
            mood = r.mood(),
            sprite = r.sprite,
            x = x, y = y, visibleOnMap = visible,
            homeBuildingId = r.homeBuildingId,
            homeName = r.homeBuildingId?.let { state.building(it)?.name },
            householdId = r.householdId,
            currentBuildingId = r.currentBuildingId,
            partnerId = r.partnerId,
            motherId = r.motherId,
            fatherId = r.fatherId,
            childIds = r.childIds.toList(),
            wealth = r.wealth,
            debt = r.debt,
            relationshipStatusLabel = r.relationshipStatus.name.lowercase().replaceFirstChar { it.uppercase() },
            hunger = r.needs.hunger,
            energy = r.needs.energy,
            health = r.needs.health,
            social = r.needs.social,
            stress = r.needs.stress,
            purposeNeed = r.needs.purpose,
            comfort = r.needs.comfort,
            safety = r.needs.safety,
            financialSecurity = r.needs.financialSecurity,
            skills = if (isBackground) emptyMap() else r.skills.entries.associate { (k, v) -> k.label to v },
            personality = r.personality,
            activeGoalLabels = if (isBackground) emptyList() else r.goals
                .filter { it.status == com.ripple.town.core.model.GoalStatus.ACTIVE }
                .sortedByDescending { it.progress }
                .map { g ->
                    val pct = (g.progress * 100).toInt()
                    "${g.type.label} (${pct}%) — ${g.motivation}"
                },
            conditionLabels = if (isBackground) emptyList() else r.activeConditions().filter { !it.hidden }.map { it.type.label },
            memories = if (isBackground) emptyList() else r.memories.sortedByDescending { it.importance }.take(10).map {
                MemoryUi(it.description, it.emotionalIntensity, it.createdAt, it.type.label)
            },
            relationships = relationships,
            aspirations = if (isBackground) emptyList() else r.aspirations
                .filter { it.status != AspirationStatus.ABANDONED }
                .sortedWith(compareBy({ it.status == AspirationStatus.FULFILLED }, { -it.progress }))
                .take(5)
                .map { Triple(it.type.label, it.status.name.lowercase().replaceFirstChar { c -> c.uppercase() }, it.progress) },
            identityFacetLabels = if (isBackground) emptyList() else r.identityFacets
                .sortedByDescending { it.strength }
                .map { it.label.label },
            lifeSatisfactionScore = r.lifeSatisfaction.overall(),
            lifeSatisfactionBreakdown = if (isBackground) emptyList() else listOf(
                "Family" to r.lifeSatisfaction.family,
                "Career" to r.lifeSatisfaction.career,
                "Purpose" to r.lifeSatisfaction.purpose,
                "Community" to r.lifeSatisfaction.community,
                "Legacy" to r.lifeSatisfaction.legacy,
                "Health" to r.lifeSatisfaction.health
            )
        )
    }

    /** Tile-space location; travelling residents move along an L-shaped path. */
    private fun positionOf(state: WorldState, r: com.ripple.town.core.model.Resident): Triple<Float, Float, Boolean> {
        if (!r.alive || !r.inTown) return Triple(0f, 0f, false)
        val current = r.currentBuildingId?.let { state.building(it) }
        if (current != null) {
            // Stand near the door, deterministically fanned out by id so crowds read.
            val slot = (r.id % 5).toInt()
            val dx = (slot % 3) - 1
            val dy = (slot / 3)
            val inside = r.activity != Activity.IDLE
            return if (inside) {
                val c = current.centre()
                Triple(c.x + dx * 0.4f, c.y + dy * 0.4f, true)
            } else {
                Triple(current.door.x + dx * 0.5f, current.door.y + dy * 0.5f + 0.2f, true)
            }
        }
        val from = r.travelFromBuildingId?.let { state.building(it) }
        val to = r.travelToBuildingId?.let { state.building(it) }
        if (to != null) {
            val a = from?.door ?: to.door
            val b = to.door
            val total = (r.travelArrivesAt - r.travelStartedAt).coerceAtLeast(1)
            val t = ((state.time - r.travelStartedAt).toFloat() / total).coerceIn(0f, 1f)
            // L-path: walk x first, then y.
            val dxLen = kotlin.math.abs(b.x - a.x).toFloat()
            val dyLen = kotlin.math.abs(b.y - a.y).toFloat()
            val len = (dxLen + dyLen).coerceAtLeast(1f)
            val walked = t * len
            return if (walked <= dxLen) {
                val dir = if (b.x >= a.x) 1f else -1f
                Triple(a.x + dir * walked, a.y.toFloat(), true)
            } else {
                val dir = if (b.y >= a.y) 1f else -1f
                Triple(b.x.toFloat(), a.y + dir * (walked - dxLen), true)
            }
        }
        // Background resident with no map home: not drawn.
        return Triple(0f, 0f, false)
    }

    private fun buildPoliticsUi(state: WorldState): PoliticsSummaryUi? {
        val mayorId = state.mayorId ?: return null
        val mayor = state.residents[mayorId] ?: return null

        // ── Approval ────────────────────────────────────────────────────────
        val approval = state.publicOpinionData.approvalRating.toInt().coerceIn(0, 100)

        // ── Election countdown ──────────────────────────────────────────────
        val isElectionActive = state.campaignEndsAt != null
        val daysUntilNextElection = if (state.nextElectionAt > 0) {
            ((state.nextElectionAt - state.time) / SimTime.MINUTES_PER_DAY).toInt().coerceAtLeast(0)
        } else 0

        // ── Party standings ─────────────────────────────────────────────────
        // Governing party = the mayor's party (if any)
        val governingPartyId = mayor.partyId
        // Seat count: councillors + mayor, attributed by partyId
        val allPoliticalIds = (state.councillorIds + mayorId).distinct()
        val seatsByParty = allPoliticalIds
            .mapNotNull { id -> state.residents[id]?.partyId }
            .groupingBy { it }
            .eachCount()

        val parties = state.politicalParties.values
            .filter { it.dissolvedAt == null }
            .sortedByDescending { seatsByParty.getOrDefault(it.id, 0) * 100 + it.reputation }
            .take(5)
            .map { party ->
                PartyStandingUi(
                    name = party.name,
                    seats = seatsByParty.getOrDefault(party.id, 0),
                    approval = party.reputation.toInt().coerceIn(0, 100),
                    isGoverning = party.id == governingPartyId
                )
            }

        // ── Recent enacted policies ─────────────────────────────────────────
        val recentPolicies = state.activePolicies.values
            .filter { it.status == PolicyStatus.PASSED }
            .sortedByDescending { it.passedAt ?: 0L }
            .take(3)
            .map { it.title }

        // ── Administration summary ──────────────────────────────────────────
        val currentAdmin = state.governmentRecords.lastOrNull { it.id == state.currentGovernmentId }
            ?: state.governmentRecords.lastOrNull()
        val adminSummary = if (currentAdmin != null) {
            val yearStarted = SimTime.year(currentAdmin.startedAt)
            val yearNow = SimTime.year(state.time)
            val adminYears = (yearNow - yearStarted + 1).coerceAtLeast(1)
            val surname = currentAdmin.leaderName.substringAfterLast(' ')
            "Year $adminYears of the $surname administration"
        } else {
            "No administration on record"
        }

        return PoliticsSummaryUi(
            mayorName = mayor.fullName,
            mayorApproval = approval,
            isElectionActive = isElectionActive,
            daysUntilNextElection = daysUntilNextElection,
            parties = parties,
            recentPolicies = recentPolicies,
            currentAdminSummary = adminSummary
        )
    }

    private fun buildingUi(state: WorldState, b: Building, bizByBuilding: Map<Long, List<com.ripple.town.core.model.Business>>): BuildingUi {
        val biz = bizByBuilding[b.id]?.firstOrNull()
        val occupants = state.residentsIn(b.id).map { it.id }
        // Rivalry: find the rival business name by looking at RIVAL relationships of this
        // business's owner and checking if the rival owner runs a same-type business.
        val rivalBizName: String? = if (biz != null && biz.open) {
            val ownerId = biz.ownerId
            if (ownerId != null) {
                state.relationshipsOf(ownerId)
                    .filter { it.kind == RelationshipKind.RIVAL }
                    .sortedByDescending { it.resentment }
                    .firstNotNullOfOrNull { rel ->
                        val rivalId = rel.other(ownerId)
                        state.businesses.values.firstOrNull { rb ->
                            rb.ownerId == rivalId && rb.open && rb.type == biz.type && rb.id != biz.id
                        }?.name
                    }
            } else null
        } else null
        return BuildingUi(
            id = b.id, name = b.name, type = b.type, typeLabel = b.type.label,
            x = b.origin.x, y = b.origin.y, w = b.width, h = b.height,
            doorX = b.door.x, doorY = b.door.y,
            condition = b.condition, noise = b.noise, value = b.value,
            capacity = b.capacity, abandoned = b.abandoned, upgradeLevel = b.upgradeLevel,
            ownerName = b.ownerId?.let { state.resident(it)?.fullName },
            occupantIds = occupants,
            businessName = biz?.name,
            businessOpen = biz?.open,
            businessBalance = biz?.balance,
            businessReputation = biz?.reputation,
            employeeNames = biz?.let { bz ->
                state.employeesOf(bz.id).mapNotNull { state.resident(it.residentId)?.fullName }
            } ?: emptyList(),
            visibleChanges = b.visibleChanges.takeLast(5),
            buildingState = b.buildingState,
            ownerId = b.ownerId,
            businessDaysInTrouble = biz?.daysInTrouble,
            businessDemand = biz?.demand,
            businessPriceLevel = biz?.priceLevel,
            businessCustomersToday = biz?.customersToday,
            businessRevenueToday = biz?.revenueToday,
            businessExpensesToday = biz?.expensesToday,
            businessEmployeeCapacity = biz?.employeeCapacity,
            businessEmployeeIds = biz?.let { bz -> state.employeesOf(bz.id).map { it.residentId } } ?: emptyList(),
            businessClosedAt = biz?.closedAt,
            districtName = b.districtId?.let { state.district(it)?.name },
            districtCharacter = b.districtId?.let { state.district(it)?.character?.label },
            constructedAt = if (b.constructedAt > 0L) b.constructedAt else null,
            tenantHistory = b.tenantHistory.toList(),
            businessRivalName = rivalBizName
        )
    }
}
