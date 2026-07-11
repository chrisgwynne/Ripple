package com.ripple.town.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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

        /**
         * v1 → v2: adds a nullable `tagged_at` column to `world_events` that records when a
         * player last manually tagged (bookmarked) an event for later review — a new UI feature.
         * NULL for all existing events (never tagged). The column is read as nullable `Long?`
         * in [WorldEventEntity] and always NULL for rows written before this migration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE world_events ADD COLUMN tagged_at INTEGER")
            }
        }
    }
}
