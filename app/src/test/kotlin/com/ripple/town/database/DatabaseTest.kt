package com.ripple.town.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.database.EventCauseEntity
import com.ripple.town.core.database.FollowedResidentEntity
import com.ripple.town.core.database.NewspaperIssueEntity
import com.ripple.town.core.database.NewspaperStoryEntity
import com.ripple.town.core.database.RippleDatabase
import com.ripple.town.core.database.SimulationCheckpointEntity
import com.ripple.town.core.database.WorldEventEntity
import com.ripple.town.core.database.csvToLongs
import com.ripple.town.core.database.toCsv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseTest {

    private lateinit var db: RippleDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RippleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(id: Long, time: Long, description: String = "e$id") = WorldEventEntity(
        id = id, worldId = 1L, time = time, type = "ARGUMENT", sourceResidentId = 1L,
        targetResidentIdsCsv = listOf(2L).toCsv(), buildingId = null, businessId = null,
        severity = 0.4, visibility = "PUBLIC", description = description, payloadJson = "{}",
        consequenceDepth = 0, importance = 40.0
    )

    @Test
    fun `event log stores and links causes`() = runBlocking {
        db.eventDao().insertEvents(listOf(event(1, 100), event(2, 200), event(3, 300)))
        db.eventDao().insertCauses(
            listOf(EventCauseEntity(eventId = 3, causeEventId = 2), EventCauseEntity(eventId = 2, causeEventId = 1))
        )
        assertThat(db.eventDao().causeIdsOf(3)).containsExactly(2L)
        assertThat(db.eventDao().causeIdsOf(2)).containsExactly(1L)
        assertThat(db.eventDao().causeIdsOf(1)).isEmpty()
        // Duplicate event processing is impossible: same id inserts are ignored.
        db.eventDao().insertEvents(listOf(event(3, 999, description = "tampered")))
        assertThat(db.eventDao().event(3)!!.description).isEqualTo("e3")
        assertThat(db.eventDao().count()).isEqualTo(3)
    }

    @Test
    fun `resident csv matching finds targets`() = runBlocking {
        db.eventDao().insertEvents(
            listOf(
                event(1, 100).copy(sourceResidentId = 7L, targetResidentIdsCsv = listOf(8L, 9L).toCsv()),
                event(2, 200).copy(sourceResidentId = 9L, targetResidentIdsCsv = "")
            )
        )
        val for9 = db.eventDao().eventsForResident(9L, 10)
        assertThat(for9.map { it.id }).containsExactly(1L, 2L)
        val for8 = db.eventDao().eventsForResident(8L, 10)
        assertThat(for8.map { it.id }).containsExactly(1L)
        // "1" must not match "91"
        val for1 = db.eventDao().eventsForResident(1L, 10)
        assertThat(for1).isEmpty()
    }

    @Test
    fun `checkpoints keep only the newest few`() = runBlocking {
        repeat(8) { i ->
            db.worldDao().insertCheckpoint(
                SimulationCheckpointEntity(worldId = 1, simTimeMinutes = i * 100L, savedAtRealMs = i.toLong(), stateJson = "{}")
            )
        }
        db.worldDao().pruneCheckpoints(1, keep = 4)
        val latest = db.worldDao().latestCheckpoint(1)!!
        assertThat(latest.simTimeMinutes).isEqualTo(700L)
    }

    @Test
    fun `newspaper archive persists issues and stories in order`() = runBlocking {
        db.newspaperDao().insertIssue(NewspaperIssueEntity(1, 1, 1000, "The Argus"))
        db.newspaperDao().insertIssue(NewspaperIssueEntity(2, 2, 2000, "The Argus"))
        db.newspaperDao().insertStories(
            listOf(
                NewspaperStoryEntity(2, 1, "NOTICES", "Second", "b", null, 1),
                NewspaperStoryEntity(1, 1, "HEADLINE", "First", "a", null, 0)
            )
        )
        val issues = db.newspaperDao().issues().first()
        assertThat(issues.map { it.issueNumber }).containsExactly(2, 1).inOrder()
        val stories = db.newspaperDao().storiesOf(1)
        assertThat(stories.map { it.headline }).containsExactly("First", "Second").inOrder()
    }

    @Test
    fun `followed residents support primary switching`() = runBlocking {
        db.followDao().upsert(FollowedResidentEntity(1, isPrimary = true, followedSinceSimTime = 0))
        db.followDao().upsert(FollowedResidentEntity(2, isPrimary = true, followedSinceSimTime = 10))
        db.followDao().clearPrimaryExcept(2)
        val all = db.followDao().all().first()
        assertThat(all.single { it.isPrimary }.residentId).isEqualTo(2L)
    }

    @Test
    fun `csv helpers round-trip`() {
        assertThat(listOf(1L, 22L, 333L).toCsv().csvToLongs()).containsExactly(1L, 22L, 333L).inOrder()
        assertThat(emptyList<Long>().toCsv()).isEmpty()
        assertThat("".csvToLongs()).isEmpty()
    }
}
