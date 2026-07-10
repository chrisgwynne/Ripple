package com.ripple.town.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorldEntity::class,
        TownEntity::class,
        ResidentEntity::class,
        HouseholdEntity::class,
        RelationshipEntity::class,
        MemoryEntity::class,
        SkillEntity::class,
        ResidentSkillEntity::class,
        GoalEntity::class,
        BuildingEntity::class,
        BusinessEntity::class,
        EmploymentEntity::class,
        HealthConditionEntity::class,
        WorldEventEntity::class,
        EventCauseEntity::class,
        DelayedEffectEntity::class,
        InterventionEntity::class,
        NewspaperIssueEntity::class,
        NewspaperStoryEntity::class,
        FollowedResidentEntity::class,
        TownStatisticEntity::class,
        SimulationCheckpointEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class RippleDatabase : RoomDatabase() {
    abstract fun worldDao(): WorldDao
    abstract fun eventDao(): EventDao
    abstract fun newspaperDao(): NewspaperDao
    abstract fun interventionDao(): InterventionDao
    abstract fun statisticsDao(): StatisticsDao
    abstract fun followDao(): FollowDao
    abstract fun mirrorDao(): MirrorDao

    companion object {
        const val NAME = "ripple.db"
    }
}
