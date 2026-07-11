package com.ripple.town.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.database.RippleDatabase
import com.ripple.town.core.model.SimSpeed
import com.ripple.town.core.simulation.providers.NoOpDialogueProvider
import com.ripple.town.core.simulation.providers.NoOpNarrativeTextProvider
import com.ripple.town.data.SettingsRepository
import com.ripple.town.data.WorldRepository
import com.ripple.town.feature.town.TownSheet
import com.ripple.town.feature.town.TownTap
import com.ripple.town.feature.town.TownViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TownViewModelTest {

    private lateinit var db: RippleDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: WorldRepository
    private lateinit var viewModel: TownViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RippleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob())
        repository = WorldRepository(
            db, SettingsRepository(context), scope,
            NoOpNarrativeTextProvider(), NoOpDialogueProvider()
        )
        runBlocking { repository.createWorld("Testholme", SimSpeed.NORMAL, seed = 21L) }
        viewModel = TownViewModel(repository)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** Awaits work dispatched onto the repository's engine dispatcher using coroutine delay. */
    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        runBlocking {
            withTimeout(timeoutMs) {
                while (!condition()) delay(20)
            }
        }
        assertThat(condition()).isTrue()
    }

    @Test
    fun `tapping a resident opens their sheet`() {
        val world = viewModel.world.value!!
        val resident = world.residents.first { it.detailed && it.alive }
        viewModel.onTap(TownTap.OnResident(resident.id))
        val sheet = viewModel.sheet.value
        assertThat(sheet).isInstanceOf(TownSheet.ResidentSheet::class.java)
        assertThat((sheet as TownSheet.ResidentSheet).residentId).isEqualTo(resident.id)
    }

    @Test
    fun `tapping a building opens the building sheet and ground closes it`() {
        val world = viewModel.world.value!!
        val building = world.buildings.first()
        viewModel.onTap(TownTap.OnBuilding(building.id))
        assertThat(viewModel.sheet.value).isInstanceOf(TownSheet.BuildingSheet::class.java)
        viewModel.onTap(TownTap.OnGround)
        assertThat(viewModel.sheet.value).isNull()
    }

    @Test
    fun `follow switches the primary resident and requests a camera jump`() {
        val world = viewModel.world.value!!
        val other = world.residents.first {
            it.detailed && it.alive && it.inTown && it.id != world.followedResidentId
        }
        viewModel.follow(other.id)
        waitFor { viewModel.world.value!!.followedResidentId == other.id }
        waitFor { viewModel.jumpTo.value != null }
        viewModel.consumeJump()
        assertThat(viewModel.jumpTo.value).isNull()
    }

    @Test
    fun `speed changes flow through`() {
        viewModel.setSpeed(SimSpeed.VERY_FAST)
        waitFor { viewModel.speed.value == SimSpeed.VERY_FAST }
        viewModel.setSpeed(SimSpeed.PAUSED)
        waitFor { viewModel.speed.value == SimSpeed.PAUSED }
    }
}
