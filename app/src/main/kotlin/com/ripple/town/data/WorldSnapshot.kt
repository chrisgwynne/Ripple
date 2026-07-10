package com.ripple.town.data

import androidx.compose.runtime.Immutable
import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Mood
import com.ripple.town.core.model.SimSpeed
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SpriteConfig
import com.ripple.town.core.model.TimeOfDay
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.Weather
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
    val followedResidentId: Long?,
    val favouriteIds: List<Long>,
    val discoveredIds: List<Long>,
    val mayorId: Long?,
    val residents: List<ResidentUi>,
    val buildings: List<BuildingUi>,
    val map: TownMap
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
    val activeGoalLabels: List<String>,
    val conditionLabels: List<String>,
    val memories: List<MemoryUi>,
    val relationships: List<RelationUi>
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
    val visibleChanges: List<String>
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
        val residents = state.residentsOrdered().map { r -> residentUi(state, r) }
        val buildings = state.buildings.values.sortedBy { it.id }.map { b -> buildingUi(state, b) }
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
            followedResidentId = state.followedResidentId,
            favouriteIds = state.favouriteResidentIds.toList(),
            discoveredIds = state.discoveredResidentIds.toList(),
            mayorId = state.mayorId,
            residents = residents,
            buildings = buildings,
            map = state.map
        )
    }

    private fun residentUi(state: WorldState, r: com.ripple.town.core.model.Resident): ResidentUi {
        val (x, y, visible) = positionOf(state, r)
        val employment = state.employmentOf(r)
        val employer = employment?.let { state.businesses[it.businessId]?.name }
        val relationships = state.relationshipsOf(r.id)
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
            detailed = r.detailLevel == DetailLevel.DETAILED,
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
            skills = r.skills.entries.associate { (k, v) -> k.label to v },
            activeGoalLabels = r.goals
                .filter { it.status == com.ripple.town.core.model.GoalStatus.ACTIVE }
                .map { "${it.type.label} — ${it.motivation}" },
            conditionLabels = r.activeConditions().filter { !it.hidden }.map { it.type.label },
            memories = r.memories.sortedByDescending { it.importance }.take(10).map {
                MemoryUi(it.description, it.emotionalIntensity, it.createdAt, it.type.label)
            },
            relationships = relationships
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

    private fun buildingUi(state: WorldState, b: Building): BuildingUi {
        val biz = state.businesses.values.firstOrNull { it.buildingId == b.id }
        val occupants = state.residentsIn(b.id).map { it.id }
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
            visibleChanges = b.visibleChanges.takeLast(5)
        )
    }
}
