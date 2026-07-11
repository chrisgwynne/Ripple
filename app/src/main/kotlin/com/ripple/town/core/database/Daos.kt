package com.ripple.town.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorld(world: WorldEntity)

    @Query("SELECT * FROM worlds WHERE id = :id")
    suspend fun world(id: Long): WorldEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTown(town: TownEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: SimulationCheckpointEntity): Long

    @Query("SELECT * FROM simulation_checkpoints WHERE worldId = :worldId ORDER BY simTimeMinutes DESC LIMIT 1")
    suspend fun latestCheckpoint(worldId: Long): SimulationCheckpointEntity?

    @Query("DELETE FROM simulation_checkpoints WHERE worldId = :worldId AND id NOT IN (SELECT id FROM simulation_checkpoints WHERE worldId = :worldId ORDER BY simTimeMinutes DESC LIMIT :keep)")
    suspend fun pruneCheckpoints(worldId: Long, keep: Int = 4)

    @Query("DELETE FROM worlds")
    suspend fun deleteAllWorlds()
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<WorldEventEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCauses(causes: List<EventCauseEntity>)

    @Query("SELECT * FROM world_events WHERE id = :id")
    suspend fun event(id: Long): WorldEventEntity?

    @Query("SELECT * FROM world_events WHERE id IN (:ids)")
    suspend fun events(ids: List<Long>): List<WorldEventEntity>

    @Query("SELECT causeEventId FROM event_causes WHERE eventId = :eventId")
    suspend fun causeIdsOf(eventId: Long): List<Long>

    @Query("SELECT eventId FROM event_causes WHERE causeEventId = :eventId")
    suspend fun consequenceIdsOf(eventId: Long): List<Long>

    @Query("SELECT * FROM world_events ORDER BY time DESC, id DESC LIMIT :limit")
    fun latestEvents(limit: Int): Flow<List<WorldEventEntity>>

    @Query("SELECT * FROM world_events WHERE importance >= :minImportance AND visibility != 'HIDDEN' ORDER BY time DESC, id DESC LIMIT :limit")
    fun importantEvents(minImportance: Double, limit: Int): Flow<List<WorldEventEntity>>

    @Query("SELECT * FROM world_events WHERE (sourceResidentId = :residentId OR targetResidentIdsCsv LIKE '%,' || :residentId || ',%') ORDER BY time DESC LIMIT :limit")
    suspend fun eventsForResident(residentId: Long, limit: Int): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE buildingId = :buildingId ORDER BY time DESC LIMIT :limit")
    suspend fun eventsForBuilding(buildingId: Long, limit: Int): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE time >= :fromTime AND time < :toTime ORDER BY time ASC")
    suspend fun eventsBetween(fromTime: Long, toTime: Long): List<WorldEventEntity>

    /**
     * One-shot (non-Flow) lookup for background/worker contexts that shouldn't hold a
     * live collector open — e.g. [com.ripple.town.work.NotificationCheckWorker]'s
     * lightweight DB-only pass. Mirrors [importantEvents]'s bar and ordering.
     */
    @Query("SELECT * FROM world_events WHERE importance >= :minImportance AND visibility != 'HIDDEN' AND id > :sinceId ORDER BY time ASC LIMIT :limit")
    suspend fun notableEventsSince(minImportance: Double, sinceId: Long, limit: Int): List<WorldEventEntity>

    @Query("UPDATE world_events SET importance = importance + :delta WHERE id = :id")
    suspend fun boostImportance(id: Long, delta: Double)

    @Query("SELECT COUNT(*) FROM world_events")
    suspend fun count(): Int

    @Transaction
    suspend fun boostAll(boosts: Map<Long, Double>) {
        for ((id, delta) in boosts) boostImportance(id, delta)
    }
}

@Dao
interface NewspaperDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIssue(issue: NewspaperIssueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStories(stories: List<NewspaperStoryEntity>)

    @Query("SELECT * FROM newspaper_issues ORDER BY publishedAt DESC")
    fun issues(): Flow<List<NewspaperIssueEntity>>

    @Query("SELECT * FROM newspaper_stories WHERE issueId = :issueId ORDER BY orderInIssue ASC")
    suspend fun storiesOf(issueId: Long): List<NewspaperStoryEntity>

    @Query("SELECT COUNT(*) FROM newspaper_issues")
    suspend fun issueCount(): Int
}

@Dao
interface InterventionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(intervention: InterventionEntity)

    @Query("SELECT * FROM interventions ORDER BY appliedAt DESC")
    fun all(): Flow<List<InterventionEntity>>

    @Query("SELECT * FROM interventions WHERE targetResidentId = :residentId ORDER BY appliedAt DESC LIMIT 10")
    fun forResident(residentId: Long): Flow<List<InterventionEntity>>
}

@Dao
interface StatisticsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: TownStatisticEntity)

    @Query("SELECT * FROM town_statistics ORDER BY dayIndex DESC LIMIT :limit")
    fun latest(limit: Int): Flow<List<TownStatisticEntity>>
}

@Dao
interface FollowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(follow: FollowedResidentEntity)

    @Query("DELETE FROM followed_residents WHERE residentId = :residentId")
    suspend fun remove(residentId: Long)

    @Query("UPDATE followed_residents SET isPrimary = 0 WHERE residentId != :residentId")
    suspend fun clearPrimaryExcept(residentId: Long)

    @Query("SELECT * FROM followed_residents")
    fun all(): Flow<List<FollowedResidentEntity>>

    /** One-shot snapshot for background/worker contexts — see [EventDao.notableEventsSince]. */
    @Query("SELECT * FROM followed_residents")
    suspend fun allOnce(): List<FollowedResidentEntity>
}

/**
 * Mirror snapshot: replaces the queryable copies of in-memory state at
 * checkpoint time. All-in-one transaction to stay consistent.
 */
@Dao
interface MirrorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResidents(rows: List<ResidentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHouseholds(rows: List<HouseholdEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelationships(rows: List<RelationshipEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuildings(rows: List<BuildingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBusinesses(rows: List<BusinessEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployments(rows: List<EmploymentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealthConditions(rows: List<HealthConditionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemories(rows: List<MemoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoals(rows: List<GoalEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkillCatalog(rows: List<SkillEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResidentSkills(rows: List<ResidentSkillEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDelayedEffects(rows: List<DelayedEffectEntity>)

    @Query("SELECT COUNT(*) FROM residents")
    suspend fun residentCount(): Int

    @Query("SELECT * FROM residents WHERE id = :id")
    suspend fun resident(id: Long): ResidentEntity?
}
