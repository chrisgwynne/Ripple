package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingState
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.Business
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.EconomySystem
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.PriceDriftSystem
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import org.junit.Test

/**
 * Audit item #49 — PriceDriftSystem unit tests.
 *
 * Uses both the full [TestWorld] pattern (for integration-style seeds) and a lightweight
 * [emptyState] helper (mirrors PriceDriftAndWeatherTest for isolated, fast assertions).
 * No Math.random() or kotlin.random.Random used anywhere.
 */
class PriceDriftSystemTest {

    // ---------------------------------------------------------------- helpers

    private fun emptyState(timeMins: Long = SimTime.MINUTES_PER_YEAR): WorldState {
        val state = WorldState(
            seed = 77L,
            townName = "Driftville",
            createdAtRealMs = 0L,
            map = TownMap(5, 5, List(25) { TileType.GRASS })
        )
        state.time = timeMins
        return state
    }

    private fun addBuilding(state: WorldState, id: Long): Building {
        val b = Building(
            id = id, name = "Shop $id", type = BuildingType.BAKERY,
            origin = Tile(0, 0), width = 1, height = 1, door = Tile(0, 0),
            buildingState = BuildingState.OCCUPIED
        )
        state.buildings[id] = b
        return b
    }

    private fun addBusiness(
        state: WorldState,
        buildingId: Long,
        id: Long = buildingId,
        open: Boolean = true
    ): Business {
        val biz = Business(id = id, buildingId = buildingId, name = "Biz $id", type = BusinessType.BAKERY)
        biz.open = open
        state.businesses[id] = biz
        return biz
    }

    private fun ctx(state: WorldState, salt: Long = 0L): TickContext =
        TickContext(state, SimRandom(state.seed, SimTime.tickOf(state.time), salt), InMemoryEventIndex())

    /**
     * Run [PriceDriftSystem.updateDaily] for [days] days, incrementing state.time each day and
     * using the day index as the salt so each day gets a distinct RNG stream.
     */
    private fun runDays(state: WorldState, days: Int) {
        repeat(days) { i ->
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * i
            PriceDriftSystem.updateDaily(ctx(state, salt = i.toLong()))
        }
    }

    // ---------------------------------------------------------------- tests

    /**
     * A struggling business (daysInTrouble > 0, negative balance) should drift downward
     * more often than upward across many independent trials — the STRUGGLING_DOWN_BIAS is 0.75.
     */
    @Test
    fun `struggling business drifts lower over many ticks`() {
        var downCount = 0
        var upCount = 0
        repeat(200) { trial ->
            val state = emptyState(SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * trial)
            addBuilding(state, 1L)
            val biz = addBusiness(state, 1L).also {
                it.priceLevel = 1.0
                it.daysInTrouble = 5
                it.balance = -200.0
            }
            val before = biz.priceLevel
            // Run enough update calls per trial to overcome the 12% DRIFT_CHANCE per call.
            repeat(25) {
                PriceDriftSystem.updateDaily(
                    TickContext(state, SimRandom(trial.toLong(), SimTime.tickOf(state.time), trial.toLong()), InMemoryEventIndex())
                )
            }
            when {
                biz.priceLevel < before -> downCount++
                biz.priceLevel > before -> upCount++
            }
        }
        assertThat(downCount).isGreaterThan(upCount)
    }

    /**
     * A prosperous business (balance well above PROSPEROUS_BALANCE) should drift upward
     * more often than downward — the PROSPEROUS_UP_BIAS is 0.65.
     */
    @Test
    fun `prosperous business drifts higher over many ticks`() {
        var upCount = 0
        var downCount = 0
        repeat(200) { trial ->
            val state = emptyState(SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * trial)
            addBuilding(state, 1L)
            val biz = addBusiness(state, 1L).also {
                it.priceLevel = 1.0
                it.daysInTrouble = 0
                it.balance = PriceDriftSystem.PROSPEROUS_BALANCE + 1000.0
            }
            val before = biz.priceLevel
            repeat(25) {
                PriceDriftSystem.updateDaily(
                    TickContext(state, SimRandom(trial.toLong(), SimTime.tickOf(state.time), trial.toLong()), InMemoryEventIndex())
                )
            }
            when {
                biz.priceLevel > before -> upCount++
                biz.priceLevel < before -> downCount++
            }
        }
        assertThat(upCount).isGreaterThan(downCount)
    }

    /**
     * Prices must never go below PRICE_LEVEL_MIN regardless of how many downward drift steps
     * a struggling business accumulates.
     */
    @Test
    fun `priceLevel never falls below PRICE_LEVEL_MIN`() {
        val state = emptyState()
        addBuilding(state, 1L)
        val biz = addBusiness(state, 1L).also {
            it.priceLevel = PriceDriftSystem.PRICE_LEVEL_MIN
            it.daysInTrouble = 99
            it.balance = -9999.0
        }
        runDays(state, 300)
        assertThat(biz.priceLevel).isAtLeast(PriceDriftSystem.PRICE_LEVEL_MIN)
    }

    /**
     * Prices must never exceed PRICE_LEVEL_MAX regardless of how many upward drift steps
     * a prosperous business accumulates.
     */
    @Test
    fun `priceLevel never exceeds PRICE_LEVEL_MAX`() {
        val state = emptyState()
        addBuilding(state, 1L)
        val biz = addBusiness(state, 1L).also {
            it.priceLevel = PriceDriftSystem.PRICE_LEVEL_MAX
            it.daysInTrouble = 0
            it.balance = PriceDriftSystem.PROSPEROUS_BALANCE + 50_000.0
        }
        runDays(state, 300)
        assertThat(biz.priceLevel).isAtMost(PriceDriftSystem.PRICE_LEVEL_MAX)
    }

    /**
     * A closed business must never see its priceLevel change — PriceDriftSystem only considers
     * businesses where `it.open` is true.
     */
    @Test
    fun `closed business priceLevel is unchanged`() {
        val state = emptyState()
        addBuilding(state, 2L)
        val biz = addBusiness(state, 2L, open = false).also {
            it.priceLevel = 1.0
        }
        runDays(state, 300)
        assertThat(biz.priceLevel).isEqualTo(1.0)
    }

    /**
     * Determinism: identical initial state + same salt sequence yields identical priceLevel.
     */
    @Test
    fun `determinism - same seed and salt produce identical drift outcome`() {
        fun runOnce(): Double {
            val state = emptyState()
            addBuilding(state, 1L)
            val biz = addBusiness(state, 1L).also {
                it.priceLevel = 1.0
                it.daysInTrouble = 0
                it.balance = PriceDriftSystem.PROSPEROUS_BALANCE + 500.0
            }
            runDays(state, 100)
            return biz.priceLevel
        }
        assertThat(runOnce()).isEqualTo(runOnce())
    }

    /**
     * Using the full TestWorld: a business inside a fully generated world respects the same
     * bounds — confirms no interaction with other WorldState fields breaks clamping.
     */
    @Test
    fun `full TestWorld business priceLevel stays within documented bounds`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.firstOrNull { it.open } ?: return
        biz.priceLevel = 1.0
        biz.balance = PriceDriftSystem.PROSPEROUS_BALANCE + 500.0
        biz.daysInTrouble = 0

        for (i in 0 until 200) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * i
            PriceDriftSystem.updateDaily(TestWorld.contextFor(state, salt = i.toLong()))
        }
        assertThat(biz.priceLevel).isAtLeast(PriceDriftSystem.PRICE_LEVEL_MIN)
        assertThat(biz.priceLevel).isAtMost(PriceDriftSystem.PRICE_LEVEL_MAX)
    }
}
