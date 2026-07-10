package com.ripple.town.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema. The event log, newspaper archive, interventions, statistics and
 * checkpoints are the durable source of truth and are written incrementally.
 * Entity mirrors (residents, buildings, …) are refreshed at checkpoint time so
 * the world remains queryable at rest; live UI reads from the in-memory state.
 */

@Entity(tableName = "worlds")
data class WorldEntity(
    @PrimaryKey val id: Long,
    val seed: Long,
    val name: String,
    val createdAtRealMs: Long,
    val simTimeMinutes: Long,
    val lastSavedRealMs: Long,
    val speedName: String
)

@Entity(
    tableName = "towns",
    foreignKeys = [ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("worldId")]
)
data class TownEntity(
    @PrimaryKey val id: Long,
    val worldId: Long,
    val name: String,
    val mapWidth: Int,
    val mapHeight: Int,
    /** Serialised tile list. */
    val mapJson: String
)

@Entity(
    tableName = "residents",
    foreignKeys = [ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("worldId"), Index("surname"), Index("alive")]
)
data class ResidentEntity(
    @PrimaryKey val id: Long,
    val worldId: Long,
    val firstName: String,
    val surname: String,
    val gender: String,
    val bornAt: Long,
    val alive: Boolean,
    val diedAt: Long?,
    val causeOfDeath: String?,
    val leftTownAt: Long?,
    val detailLevel: String,
    val homeBuildingId: Long?,
    val householdId: Long?,
    val occupation: String,
    val employmentId: Long?,
    val relationshipStatus: String,
    val partnerId: Long?,
    val motherId: Long?,
    val fatherId: Long?,
    val wealth: Double,
    val debt: Double,
    val reputation: Double,
    val politicalInterest: Double,
    /** JSON blobs for the composite parts. */
    val needsJson: String,
    val personalityJson: String,
    val spriteJson: String,
    val childIdsCsv: String
)

@Entity(
    tableName = "households",
    foreignKeys = [ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("worldId")]
)
data class HouseholdEntity(
    @PrimaryKey val id: Long,
    val worldId: Long,
    val name: String,
    val homeBuildingId: Long?,
    val savings: Double,
    val monthlyRent: Double,
    val memberIdsCsv: String
)

@Entity(
    tableName = "relationships",
    primaryKeys = ["aId", "bId"],
    indices = [Index("aId"), Index("bId")]
)
data class RelationshipEntity(
    val aId: Long,
    val bId: Long,
    val kind: String,
    val familiarity: Double,
    val trust: Double,
    val affection: Double,
    val attraction: Double,
    val respect: Double,
    val resentment: Double,
    val dependency: Double,
    val sharedHistory: Double,
    val lastInteractionAt: Long
)

@Entity(
    tableName = "memories",
    foreignKeys = [ForeignKey(ResidentEntity::class, ["id"], ["residentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("residentId")]
)
data class MemoryEntity(
    @PrimaryKey val id: Long,
    val residentId: Long,
    val eventId: Long?,
    val type: String,
    val description: String,
    val emotionalIntensity: Double,
    val accuracy: Double,
    val importance: Double,
    val createdAt: Long,
    val lastRecalledAt: Long,
    val decayPerYear: Double,
    val associatedResidentIdsCsv: String,
    val beliefFormed: String?
)

/** Skill catalogue (static rows, one per skill type). */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val name: String,
    val label: String
)

@Entity(
    tableName = "resident_skills",
    primaryKeys = ["residentId", "skillName"],
    foreignKeys = [ForeignKey(ResidentEntity::class, ["id"], ["residentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("residentId")]
)
data class ResidentSkillEntity(
    val residentId: Long,
    val skillName: String,
    val value: Double
)

@Entity(
    tableName = "goals",
    foreignKeys = [ForeignKey(ResidentEntity::class, ["id"], ["ownerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ownerId"), Index("status")]
)
data class GoalEntity(
    @PrimaryKey val id: Long,
    val ownerId: Long,
    val type: String,
    val motivation: String,
    val createdAt: Long,
    val progress: Double,
    val risk: Double,
    val status: String,
    val resolvedAt: Long?,
    val targetResidentId: Long?,
    val targetSkill: String?,
    val causeEventId: Long?
)

@Entity(
    tableName = "buildings",
    foreignKeys = [ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("worldId"), Index("type")]
)
data class BuildingEntity(
    @PrimaryKey val id: Long,
    val worldId: Long,
    val name: String,
    val type: String,
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    val doorX: Int,
    val doorY: Int,
    val ownerId: Long?,
    val condition: Double,
    val noise: Double,
    val value: Double,
    val capacity: Int,
    val constructedAt: Long,
    val upgradeLevel: Int,
    val abandoned: Boolean,
    val visibleChangesJson: String
)

@Entity(
    tableName = "businesses",
    foreignKeys = [ForeignKey(BuildingEntity::class, ["id"], ["buildingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("buildingId"), Index("open")]
)
data class BusinessEntity(
    @PrimaryKey val id: Long,
    val buildingId: Long,
    val name: String,
    val type: String,
    val ownerId: Long?,
    val balance: Double,
    val reputation: Double,
    val demand: Double,
    val priceLevel: Double,
    val employeeCapacity: Int,
    val open: Boolean,
    val openedAt: Long,
    val closedAt: Long?,
    val daysInTrouble: Int
)

@Entity(
    tableName = "employments",
    foreignKeys = [
        ForeignKey(ResidentEntity::class, ["id"], ["residentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(BusinessEntity::class, ["id"], ["businessId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("residentId"), Index("businessId")]
)
data class EmploymentEntity(
    @PrimaryKey val id: Long,
    val residentId: Long,
    val businessId: Long,
    val role: String,
    val dailySalary: Double,
    val startedAt: Long,
    val endedAt: Long?,
    val shiftStartHour: Int,
    val shiftEndHour: Int,
    val reducedHours: Boolean
)

@Entity(
    tableName = "health_conditions",
    foreignKeys = [ForeignKey(ResidentEntity::class, ["id"], ["residentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("residentId")]
)
data class HealthConditionEntity(
    @PrimaryKey val id: Long,
    val residentId: Long,
    val type: String,
    val severity: Double,
    val startedAt: Long,
    val hidden: Boolean,
    val diagnosedAt: Long?,
    val recoveredAt: Long?
)

@Entity(
    tableName = "world_events",
    indices = [Index("time"), Index("type"), Index("importance"), Index("sourceResidentId")]
)
data class WorldEventEntity(
    @PrimaryKey val id: Long,
    val worldId: Long,
    val time: Long,
    val type: String,
    val sourceResidentId: Long?,
    val targetResidentIdsCsv: String,
    val buildingId: Long?,
    val businessId: Long?,
    val severity: Double,
    val visibility: String,
    val description: String,
    val payloadJson: String,
    val consequenceDepth: Int,
    val importance: Double
)

@Entity(
    tableName = "event_causes",
    primaryKeys = ["eventId", "causeEventId"],
    foreignKeys = [ForeignKey(WorldEventEntity::class, ["id"], ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("eventId"), Index("causeEventId")]
)
data class EventCauseEntity(
    val eventId: Long,
    val causeEventId: Long
)

@Entity(
    tableName = "delayed_effects",
    indices = [Index("applied"), Index("earliestAt")]
)
data class DelayedEffectEntity(
    @PrimaryKey val id: Long,
    val sourceEventId: Long,
    val targetResidentId: Long?,
    val secondaryResidentId: Long?,
    val targetBusinessId: Long?,
    val type: String,
    val strength: Double,
    val earliestAt: Long,
    val latestAt: Long,
    val condition: String,
    val decayPerDay: Double,
    val applied: Boolean,
    val cancelled: Boolean,
    val note: String
)

@Entity(
    tableName = "interventions",
    indices = [Index("appliedAt")]
)
data class InterventionEntity(
    @PrimaryKey val id: Long,
    val verb: String,
    val targetResidentId: Long?,
    val secondaryResidentId: Long?,
    val targetBuildingId: Long?,
    val appliedAt: Long,
    val note: String,
    val eventId: Long
)

@Entity(
    tableName = "newspaper_issues",
    indices = [Index("publishedAt")]
)
data class NewspaperIssueEntity(
    @PrimaryKey val id: Long,
    val issueNumber: Int,
    val publishedAt: Long,
    val masthead: String
)

@Entity(
    tableName = "newspaper_stories",
    foreignKeys = [ForeignKey(NewspaperIssueEntity::class, ["id"], ["issueId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("issueId")]
)
data class NewspaperStoryEntity(
    @PrimaryKey val id: Long,
    val issueId: Long,
    val category: String,
    val headline: String,
    val body: String,
    val eventId: Long?,
    val orderInIssue: Int
)

@Entity(tableName = "followed_residents")
data class FollowedResidentEntity(
    @PrimaryKey val residentId: Long,
    val isPrimary: Boolean,
    val followedSinceSimTime: Long
)

@Entity(tableName = "town_statistics")
data class TownStatisticEntity(
    @PrimaryKey val dayIndex: Long,
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

@Entity(
    tableName = "simulation_checkpoints",
    indices = [Index("simTimeMinutes")]
)
data class SimulationCheckpointEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    val worldId: Long,
    val simTimeMinutes: Long,
    val savedAtRealMs: Long,
    /** Complete serialised WorldState. */
    val stateJson: String
)
