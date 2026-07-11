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
import com.ripple.town.core.model.Weather
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.NeedsSystem
import com.ripple.town.core.simulation.PriceDriftSystem
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import org.junit.Test

/**
 * Unit tests for PriceDriftSystem and the weather-transition logic in NeedsSystem.
 */
class PriceDriftAndWeatherTest {

    // --------------------------------------------------------- helpers

    private fun emptyState(timeMins: Long = SimTime.MINUTES_PER_YEAR): WorldState {
        val state = WorldState(
            seed = 99L,
            townName = "Pricebury",
            createdAtRealMs = 0L,
            map = TownMap(5, 5, List(25) { TileType.GRASS })
        )
        state.time = timeMins
        return state
    }

    private fun ctx(state: WorldState): TickContext =
        TickContext(state, SimRandom(state.seed, SimTime.tickOf(state.time), 0L), InMemoryEventIndex())

    private fun addBuilding(state: WorldState, id: Long): Building {
        val b = Building(
            id = id, name = "Shop $id", type = BuildingType.BAKERY,
            origin = Tile(0, 0), width = 1, height = 1, door = Tile(0, 0),
            buildingState = BuildingState.OCCUPIED
        )
        state.buildings[id] = b
        return b
    }

    private fun addBusiness(state: WorldState, buildingId: Long, id: Long = buildingId): Business {
        val biz = Business(id = id, buildingId = buildingId, name = "Biz $id", type = BusinessType.BAKERY)
        state.businesses[id] = biz
        return biz
    }

    // ============================================================
    // PriceDriftSystem
    // ============================================================

    @Test fun `price drifts by exactly DRIFT_STEP per action`() {
        val state = emptyState()
        addBuilding(state, 1L)
        val biz = addBusiness(state, 1L).also { it.priceLevel = 1.0 }
        val before = biz.priceLevel

        // Force a drift by running many days with a deterministic RNG until it triggers
        var drifted = false
        for (day in 0..100) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * day
            PriceDriftSystem.updateDaily(ctx(state))
            if (biz.priceLevel != before) { drifted = true; break }
        }

        assertThat(drifted).isTrue()
        val delta = kotlin.math.abs(biz.priceLevel - before)
        // Each drift step should be exactly DRIFT_STEP (0.02) modulo floating-point
        assertThat(delta).isWithin(1e-9).of(PriceDriftSystem.DRIFT_STEP)
    }

    @Test fun `priceLevel is clamped to documented bounds`() {
        val state = emptyState()
        addBuilding(state, 2L)
        val biz = addBusiness(state, 2L)

        biz.priceLevel = PriceDriftSystem.PRICE_LEVEL_MAX
        for (day in 0..500) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * day
            PriceDriftSystem.updateDaily(ctx(state))
        }
        assertThat(biz.priceLevel).isAtMost(PriceDriftSystem.PRICE_LEVEL_MAX)

        biz.priceLevel = PriceDriftSystem.PRICE_LEVEL_MIN
        for (day in 0..500) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * day
            PriceDriftSystem.updateDaily(ctx(state))
        }
        assertThat(biz.priceLevel).isAtLeast(PriceDriftSystem.PRICE_LEVEL_MIN)
    }

    @Test fun `struggling business drifts downward more often than up`() {
        var downCount = 0
        var upCount = 0
        repeat(200) { trial ->
            val state = emptyState()
            addBuilding(state, 1L)
            val biz = addBusiness(state, 1L).also {
                it.priceLevel = 1.0
                it.daysInTrouble = 5   // marks as struggling
                it.balance = -200.0
            }
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * trial
            val ctx = TickContext(state, SimRandom(trial.toLong(), SimTime.tickOf(state.time), trial.toLong()), InMemoryEventIndex())
            val before = biz.priceLevel
            // Force the drift to happen by injecting a very high DRIFT_CHANCE via many iterations
            repeat(20) { PriceDriftSystem.updateDaily(ctx) }
            when {
                biz.priceLevel < before -> downCount++
                biz.priceLevel > before -> upCount++
            }
        }
        // Struggling bias: ~75% down — we just check down > up
        assertThat(downCount).isGreaterThan(upCount)
    }

    @Test fun `prosperous business drifts upward more often than down`() {
        var upCount = 0
        var downCount = 0
        repeat(200) { trial ->
            val state = emptyState()
            addBuilding(state, 1L)
            val biz = addBusiness(state, 1L).also {
                it.priceLevel = 1.0
                it.daysInTrouble = 0
                it.balance = PriceDriftSystem.PROSPEROUS_BALANCE + 500.0
            }
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * trial
            val ctx = TickContext(state, SimRandom(trial.toLong(), SimTime.tickOf(state.time), trial.toLong()), InMemoryEventIndex())
            val before = biz.priceLevel
            repeat(20) { PriceDriftSystem.updateDaily(ctx) }
            when {
                biz.priceLevel > before -> upCount++
                biz.priceLevel < before -> downCount++
            }
        }
        assertThat(upCount).isGreaterThan(downCount)
    }

    @Test fun `closed business is not considered for drift`() {
        val state = emptyState()
        addBuilding(state, 3L)
        val biz = addBusiness(state, 3L).also {
            it.open = false
            it.priceLevel = 1.0
        }
        for (day in 0..200) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * day
            PriceDriftSystem.updateDaily(ctx(state))
        }
        assertThat(biz.priceLevel).isEqualTo(1.0)
    }

    // ============================================================
    // WeatherSystem (NeedsSystem.updateWeather via update())
    // ============================================================

    @Test fun `weather does not change while weatherEndsAt has not elapsed`() {
        val state = emptyState()
        state.weather = Weather.CLEAR
        state.weatherEndsAt = state.time + SimTime.MINUTES_PER_DAY * 100

        NeedsSystem.update(ctx(state))

        assertThat(state.weather).isEqualTo(Weather.CLEAR)
    }

    @Test fun `weather transitions to a new value when weatherEndsAt elapses`() {
        val state = emptyState()
        state.weather = Weather.CLEAR
        state.weatherEndsAt = 0L   // already elapsed

        NeedsSystem.update(ctx(state))

        // After update, weatherEndsAt should be in the future (a new window was set)
        assertThat(state.weatherEndsAt).isGreaterThan(state.time)
    }

    @Test fun `weatherEndsAt window stays within documented 4–16 hour range`() {
        val outcomes = mutableListOf<Long>()
        repeat(50) { trial ->
            val state = emptyState(SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * trial)
            state.weather = Weather.CLOUDY
            state.weatherEndsAt = 0L
            val ctx = TickContext(state, SimRandom(trial.toLong(), SimTime.tickOf(state.time), trial.toLong()), InMemoryEventIndex())
            NeedsSystem.update(ctx)
            outcomes += state.weatherEndsAt - state.time
        }
        val minHours = 4 * 60L
        val maxHours = 16 * 60L
        assertThat(outcomes.all { it in minHours..maxHours }).isTrue()
    }

    @Test fun `SNOW only appears in winter months`() {
        // Month 11 (December) is winter; force enough trials to get a SNOW reading
        var snowCount = 0
        var summerSnowCount = 0
        val snowyMonth = 11 // December -> winter
        val summerMonth = 6 // July -> summer
        repeat(200) { trial ->
            val winterTime = SimTime.MINUTES_PER_YEAR + (snowyMonth * SimTime.MINUTES_PER_DAY * 30) + SimTime.MINUTES_PER_DAY * trial
            val state = emptyState(winterTime)
            state.weatherEndsAt = 0L
            val ctx = TickContext(state, SimRandom(trial.toLong() + 1000, SimTime.tickOf(winterTime), trial.toLong()), InMemoryEventIndex())
            NeedsSystem.update(ctx)
            if (state.weather == Weather.SNOW) snowCount++

            val summerTime = SimTime.MINUTES_PER_YEAR + (summerMonth * SimTime.MINUTES_PER_DAY * 30) + SimTime.MINUTES_PER_DAY * trial
            val state2 = emptyState(summerTime)
            state2.weatherEndsAt = 0L
            val ctx2 = TickContext(state2, SimRandom(trial.toLong() + 2000, SimTime.tickOf(summerTime), trial.toLong()), InMemoryEventIndex())
            NeedsSystem.update(ctx2)
            if (state2.weather == Weather.SNOW) summerSnowCount++
        }
        // Snow should appear in winter but never in summer (12% winter, 0% summer)
        assertThat(snowCount).isGreaterThan(0)
        assertThat(summerSnowCount).isEqualTo(0)
    }
}
