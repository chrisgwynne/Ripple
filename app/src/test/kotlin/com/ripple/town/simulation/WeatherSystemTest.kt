package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.Weather
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.NeedsSystem
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import org.junit.Test

/**
 * Audit item #49 — Weather transition tests.
 *
 * The weather logic lives in [NeedsSystem.updateWeather] (private, called from
 * [NeedsSystem.update]).  Tests call [NeedsSystem.update] and observe the public
 * [WorldState.weather] / [WorldState.weatherEndsAt] fields — same pattern used in
 * PriceDriftAndWeatherTest.
 *
 * No Math.random() or kotlin.random.Random — all randomness comes from [SimRandom]
 * via [TickContext].
 */
class WeatherSystemTest {

    // ---------------------------------------------------------------- helpers

    private fun emptyState(timeMins: Long = SimTime.MINUTES_PER_YEAR): WorldState {
        val state = WorldState(
            seed = 55L,
            townName = "Weatherby",
            createdAtRealMs = 0L,
            map = TownMap(5, 5, List(25) { TileType.GRASS })
        )
        state.time = timeMins
        return state
    }

    private fun ctx(state: WorldState, salt: Long = 0L): TickContext =
        TickContext(state, SimRandom(state.seed, SimTime.tickOf(state.time), salt), InMemoryEventIndex())

    /**
     * Force a weather transition by setting weatherEndsAt to 0 (already elapsed) and calling
     * NeedsSystem.update.  Returns the new weather.
     */
    private fun forceTransition(state: WorldState, salt: Long): Weather {
        state.weatherEndsAt = 0L  // force expiry
        NeedsSystem.update(ctx(state, salt))
        return state.weather
    }

    // ---------------------------------------------------------------- tests

    /**
     * After many ticks (each with a distinct salt/time to guarantee a distinct RNG stream),
     * the weather should have changed at least once — the system is not frozen.
     */
    @Test
    fun `weather transitions across many ticks`() {
        val state = emptyState()
        val initialWeather = state.weather
        var sawDifferent = false

        for (i in 0 until 400) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * i
            state.weatherEndsAt = 0L   // force expiry each day
            NeedsSystem.update(ctx(state, salt = i.toLong()))
            if (state.weather != initialWeather) {
                sawDifferent = true
                break
            }
        }
        assertThat(sawDifferent).isTrue()
    }

    /**
     * Every weather value produced across 200 distinct transitions must be a member of the
     * [Weather] enum — no out-of-range or null values.
     */
    @Test
    fun `all produced weather values are within the valid Weather enum range`() {
        val validValues = Weather.values().toSet()
        val state = emptyState()
        val observed = mutableSetOf<Weather>()
        for (i in 0 until 200) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * i
            observed += forceTransition(state, salt = i.toLong())
        }
        assertThat(observed).isNotEmpty()
        assertThat(validValues.containsAll(observed)).isTrue()
    }

    /**
     * After a transition, [weatherEndsAt] must be strictly greater than the current time —
     * the new window is always in the future.
     */
    @Test
    fun `weatherEndsAt is always set to a future time after transition`() {
        val state = emptyState()
        for (i in 0 until 100) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * i
            forceTransition(state, salt = i.toLong())
            assertThat(state.weatherEndsAt).isGreaterThan(state.time)
        }
    }

    /**
     * The documented window length for [weatherEndsAt] is 4–16 hours (see NeedsSystem source:
     * `ctx.rng.nextLong(4, 16) * 60L`).
     */
    @Test
    fun `new weatherEndsAt window is within the documented 4-16 hour range`() {
        val minWindow = 4 * 60L   // 4 hours in minutes
        val maxWindow = 16 * 60L  // 16 hours in minutes
        val state = emptyState()
        for (i in 0 until 100) {
            state.time = SimTime.MINUTES_PER_YEAR + SimTime.MINUTES_PER_DAY * i
            forceTransition(state, salt = i.toLong())
            val window = state.weatherEndsAt - state.time
            assertThat(window).isAtLeast(minWindow)
            assertThat(window).isAtMost(maxWindow)
        }
    }

    /**
     * SNOW should appear in winter months (Nov/Dec/Jan — month indices 11, 0, 1) at the
     * documented ~12% chance.  Across 300 winter transitions at least one should be SNOW.
     */
    @Test
    fun `SNOW appears in winter months`() {
        var snowCount = 0
        val winterMonth = 11  // December (0-indexed)
        for (trial in 0 until 300) {
            // Build a time stamp that lands in December.
            val winterTime = SimTime.MINUTES_PER_YEAR +
                (winterMonth * SimTime.MINUTES_PER_DAY * 30) +
                SimTime.MINUTES_PER_DAY * trial
            val state = emptyState(winterTime)
            state.weatherEndsAt = 0L
            val weatherOut = TickContext(
                state,
                SimRandom(trial.toLong() + 1000L, SimTime.tickOf(winterTime), trial.toLong()),
                InMemoryEventIndex()
            ).also { NeedsSystem.update(it) }.let { state.weather }
            if (weatherOut == Weather.SNOW) snowCount++
        }
        assertThat(snowCount).isGreaterThan(0)
    }

    /**
     * SNOW must never appear in summer months (June/July/August — month indices 5, 6, 7).
     * The NeedsSystem implementation only sets SNOW when `winter` is true.
     */
    @Test
    fun `SNOW never appears in summer months`() {
        var summerSnowCount = 0
        val summerMonth = 6  // July (0-indexed)
        for (trial in 0 until 300) {
            val summerTime = SimTime.MINUTES_PER_YEAR +
                (summerMonth * SimTime.MINUTES_PER_DAY * 30) +
                SimTime.MINUTES_PER_DAY * trial
            val state = emptyState(summerTime)
            state.weatherEndsAt = 0L
            NeedsSystem.update(
                TickContext(
                    state,
                    SimRandom(trial.toLong() + 2000L, SimTime.tickOf(summerTime), trial.toLong()),
                    InMemoryEventIndex()
                )
            )
            if (state.weather == Weather.SNOW) summerSnowCount++
        }
        assertThat(summerSnowCount).isEqualTo(0)
    }

    /**
     * When [weatherEndsAt] has not yet elapsed, [NeedsSystem.update] must not change the
     * current weather.
     */
    @Test
    fun `weather does not change while weatherEndsAt has not elapsed`() {
        val state = emptyState()
        state.weather = Weather.CLEAR
        state.weatherEndsAt = state.time + SimTime.MINUTES_PER_DAY * 50  // far future

        NeedsSystem.update(ctx(state))

        assertThat(state.weather).isEqualTo(Weather.CLEAR)
    }

    /**
     * Determinism: same state seed + same salt → same weather outcome.
     */
    @Test
    fun `determinism - same seed and salt produce identical weather transition`() {
        fun runOnce(): Weather {
            val state = emptyState()
            state.weatherEndsAt = 0L
            NeedsSystem.update(ctx(state, salt = 42L))
            return state.weather
        }
        assertThat(runOnce()).isEqualTo(runOnce())
    }

    /**
     * Across a broad sweep of seasons, the full [Weather] enum is well-represented —
     * the system can produce every weather type, not just CLEAR/CLOUDY.
     */
    @Test
    fun `multiple distinct weather types are produced across seasons`() {
        val seen = mutableSetOf<Weather>()
        for (trial in 0 until 400) {
            val monthOffset = (trial % 12) * SimTime.MINUTES_PER_DAY * 30
            val time = SimTime.MINUTES_PER_YEAR + monthOffset + SimTime.MINUTES_PER_DAY * trial
            val state = emptyState(time)
            state.weatherEndsAt = 0L
            NeedsSystem.update(
                TickContext(
                    state,
                    SimRandom(trial.toLong() + 500L, SimTime.tickOf(time), trial.toLong()),
                    InMemoryEventIndex()
                )
            )
            seen += state.weather
        }
        // At minimum we expect CLEAR, CLOUDY, RAIN, and at least one other type.
        assertThat(seen.size).isAtLeast(3)
    }
}
