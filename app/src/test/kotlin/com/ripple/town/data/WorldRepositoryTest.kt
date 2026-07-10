package com.ripple.town.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.database.RippleDatabase
import com.ripple.town.core.model.InterventionVerb
import com.ripple.town.core.model.SimSpeed
import com.ripple.town.core.simulation.InterventionResult
import com.ripple.town.core.simulation.providers.NoOpDialogueProvider
import com.ripple.town.core.simulation.providers.NoOpNarrativeTextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests over the real repository + in-memory Room + DataStore.
 * Kept in a single class so the process-wide DataStore is shared safely.
 */
@RunWith(RobolectricTestRunner::class)
class WorldRepositoryTest {

    private lateinit var db: RippleDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: WorldRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RippleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob())
        settings = SettingsRepository(context)
        repository = WorldRepository(db, settings, scope, NoOpNarrativeTextProvider(), NoOpDialogueProvider())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `createWorld publishes a snapshot and persists everything`() = runBlocking {
        repository.createWorld("Testholme", SimSpeed.NORMAL, seed = 11L)

        val ui = repository.worldUi.value!!
        assertThat(ui.townName).isEqualTo("Testholme")
        assertThat(ui.population).isGreaterThan(80)
        assertThat(ui.followedResidentId).isNotNull()

        assertThat(db.worldDao().world(WorldRepository.WORLD_ID)!!.seed).isEqualTo(11L)
        assertThat(db.worldDao().latestCheckpoint(WorldRepository.WORLD_ID)).isNotNull()
        assertThat(db.mirrorDao().residentCount()).isEqualTo(90)
        assertThat(settings.current().onboarded).isTrue()
    }

    @Test
    fun `a closed and reopened app restores the same world`() = runBlocking {
        repository.createWorld("Testholme", SimSpeed.NORMAL, seed = 12L)
        val followedBefore = repository.worldUi.value!!.followedResidentId
        val timeBefore = repository.worldUi.value!!.time

        // Simulate process death: a brand-new repository over the same storage.
        val second = WorldRepository(db, settings, scope, NoOpNarrativeTextProvider(), NoOpDialogueProvider())
        val restored = second.restoreIfPresent()
        assertThat(restored).isTrue()
        val ui = second.worldUi.value!!
        assertThat(ui.townName).isEqualTo("Testholme")
        assertThat(ui.followedResidentId).isEqualTo(followedBefore)
        // Time may have caught up a little, but never backwards and never past the cap.
        assertThat(ui.time).isAtLeast(timeBefore)
    }

    @Test
    fun `switching the followed resident persists`() = runBlocking {
        repository.createWorld("Testholme", SimSpeed.NORMAL, seed = 13L)
        val ui = repository.worldUi.value!!
        val someoneElse = ui.residents.first { it.detailed && it.id != ui.followedResidentId && it.alive }
        repository.setPrimaryFollow(someoneElse.id)
        assertThat(repository.worldUi.value!!.followedResidentId).isEqualTo(someoneElse.id)
        val follows = db.followDao().all().first()
        assertThat(follows.single { it.isPrimary }.residentId).isEqualTo(someoneElse.id)
    }

    @Test
    fun `favourites toggle on and off`() = runBlocking {
        repository.createWorld("Testholme", SimSpeed.NORMAL, seed = 14L)
        val ui = repository.worldUi.value!!
        val someone = ui.residents.first { it.detailed && it.alive }
        repository.toggleFavourite(someone.id)
        assertThat(repository.worldUi.value!!.favouriteIds).contains(someone.id)
        repository.toggleFavourite(someone.id)
        assertThat(repository.worldUi.value!!.favouriteIds).doesNotContain(someone.id)
    }

    @Test
    fun `interventions are recorded permanently and consume influence`() = runBlocking {
        repository.createWorld("Testholme", SimSpeed.NORMAL, seed = 15L)
        val ui = repository.worldUi.value!!
        val target = ui.resident(ui.followedResidentId)!!
        val result = repository.applyIntervention(InterventionVerb.DELAY, target.id)
        assertThat(result).isInstanceOf(InterventionResult.Applied::class.java)
        assertThat(repository.worldUi.value!!.nudges).isEqualTo(ui.nudges - 1)
        val recorded = db.interventionDao().all().first()
        assertThat(recorded).hasSize(1)
        assertThat(recorded.single().verb).isEqualTo("DELAY")
    }

    @Test
    fun `cause chains can be walked from the database`() = runBlocking {
        repository.createWorld("Testholme", SimSpeed.NORMAL, seed = 16L)
        // Insert a small synthetic chain through the DAO layer.
        val dao = db.eventDao()
        dao.insertEvents(
            listOf(
                com.ripple.town.core.database.WorldEventEntity(
                    id = 9_001, worldId = 1, time = 1, type = "JOB_LOST", sourceResidentId = null,
                    targetResidentIdsCsv = "", buildingId = null, businessId = null, severity = 0.5,
                    visibility = "PUBLIC", description = "root", payloadJson = "{}",
                    consequenceDepth = 0, importance = 50.0
                ),
                com.ripple.town.core.database.WorldEventEntity(
                    id = 9_002, worldId = 1, time = 2, type = "ARGUMENT", sourceResidentId = null,
                    targetResidentIdsCsv = "", buildingId = null, businessId = null, severity = 0.5,
                    visibility = "PUBLIC", description = "middle", payloadJson = "{}",
                    consequenceDepth = 1, importance = 30.0
                ),
                com.ripple.town.core.database.WorldEventEntity(
                    id = 9_003, worldId = 1, time = 3, type = "SEPARATION", sourceResidentId = null,
                    targetResidentIdsCsv = "", buildingId = null, businessId = null, severity = 0.6,
                    visibility = "PUBLIC", description = "leaf", payloadJson = "{}",
                    consequenceDepth = 2, importance = 45.0
                )
            )
        )
        dao.insertCauses(
            listOf(
                com.ripple.town.core.database.EventCauseEntity(9_003, 9_002),
                com.ripple.town.core.database.EventCauseEntity(9_002, 9_001)
            )
        )
        val chain = repository.causeChain(9_003)
        assertThat(chain).hasSize(3)
        assertThat(chain[0].single().description).isEqualTo("leaf")
        assertThat(chain[1].single().description).isEqualTo("middle")
        assertThat(chain[2].single().description).isEqualTo("root")
    }
}
