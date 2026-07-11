package com.ripple.town.simulation

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.database.RippleDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real v1 → v2 migration test.
 *
 * Creates a database at schema v1, inserts a row into `world_events`, runs
 * [RippleDatabase.MIGRATION_1_2], then verifies:
 *  1. The migration completes without throwing.
 *  2. A `tagged_at` column now exists in `world_events` (added by the migration).
 *  3. Pre-existing rows have NULL in `tagged_at` (they were never tagged).
 *  4. The migrated database opens cleanly with the current Room entities (no further
 *     migration needed and DAOs work).
 */
@RunWith(RobolectricTestRunner::class)
class DbMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RippleDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `migration 1 to 2 adds tagged_at column and preserves existing rows`() {
        // ── Step 1: create the v1 database and insert a test row ──
        val db1 = helper.createDatabase(DB_NAME, 1)
        db1.execSQL(
            """INSERT INTO world_events
               (id, worldId, time, type, sourceResidentId, targetResidentIdsCsv,
                buildingId, businessId, severity, visibility, description,
                payloadJson, consequenceDepth, importance)
               VALUES (1, 1, 100, 'PERSON_BORN', NULL, '', NULL, NULL,
                       0.5, 'PUBLIC', 'Test event', '{}', 0, 10.0)"""
        )
        db1.close()

        // ── Step 2: run the migration ──
        val db2 = helper.runMigrationsAndValidate(DB_NAME, 2, true, RippleDatabase.MIGRATION_1_2)

        // ── Step 3: verify column exists and pre-existing row has NULL ──
        val cursor: Cursor = db2.query("SELECT tagged_at FROM world_events WHERE id = 1")
        assertThat(cursor.moveToFirst()).isTrue()
        // Column index 0 is tagged_at; isNull() must be true for pre-migration rows.
        assertThat(cursor.isNull(0)).isTrue()
        cursor.close()
        db2.close()

        // ── Step 4: open via Room to confirm entities + DAOs work with v2 ──
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roomDb = Room.databaseBuilder(context, RippleDatabase::class.java, DB_NAME)
            .addMigrations(RippleDatabase.MIGRATION_1_2)
            .build()
        runBlocking {
            // Sanity-check that the DAO works — the migrated row is visible.
            assertThat(roomDb.eventDao().count()).isEqualTo(1)
        }
        roomDb.close()
    }

    companion object {
        private const val DB_NAME = "db-migration-v1-v2-test.db"
    }
}
