package com.ripple.town.database

import android.content.Context
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
 * Baseline migration coverage for schema v1. When v2 arrives, add a
 * `migrate1To2` test using `helper.runMigrationsAndValidate`.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RippleDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `schema v1 matches the exported schema and opens cleanly`() {
        // Creates the database from the exported 1.json and validates it.
        helper.createDatabase(DB_NAME, 1).close()

        // Opening with the current entities must not require any migration.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, RippleDatabase::class.java, DB_NAME).build()
        runBlocking {
            assertThat(db.eventDao().count()).isEqualTo(0)
            assertThat(db.worldDao().world(1L)).isNull()
        }
        db.close()
    }

    companion object {
        private const val DB_NAME = "migration-test.db"
    }
}
