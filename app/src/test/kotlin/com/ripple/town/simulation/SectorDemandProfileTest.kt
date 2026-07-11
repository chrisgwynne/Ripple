package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.Weather
import com.ripple.town.core.simulation.EconomySystem
import org.junit.Test

/**
 * Covers `EconomySystem.hourlyDemandMultiplier` — see `docs/simulation-rules.md` "Sector demand
 * shaping" for the full per-type write-up this test mirrors. Pure function of
 * (type, hour, dayOfWeek, weather) with no `ctx.rng` involved, so no separate determinism test is
 * needed here — same inputs always produce the same output by construction; the exhaustive sweep
 * below effectively double-checks that too (every call is independently re-derived, never cached).
 */
class SectorDemandProfileTest {

    private val hours = 8..21
    private val daysOfWeek = 0..6
    private val allWeather = Weather.values().toList()

    private fun peakEvening(type: BusinessType, weather: Weather = Weather.CLEAR): Double =
        EconomySystem.hourlyDemandMultiplier(type, 20, dayOfWeek = 2, weather = weather)

    private fun peakMorning(type: BusinessType, weather: Weather = Weather.CLEAR): Double =
        EconomySystem.hourlyDemandMultiplier(type, 9, dayOfWeek = 2, weather = weather)

    @Test
    fun `PUB evening multiplier exceeds its morning multiplier`() {
        val evening = peakEvening(BusinessType.PUB)
        val morning = peakMorning(BusinessType.PUB)
        assertThat(evening).isGreaterThan(morning)
    }

    @Test
    fun `BAKERY morning multiplier exceeds its evening multiplier`() {
        val morning = peakMorning(BusinessType.BAKERY)
        val evening = peakEvening(BusinessType.BAKERY)
        assertThat(morning).isGreaterThan(evening)
    }

    @Test
    fun `FACTORY weather sensitivity is lower than GROCER's`() {
        // Compare the spread between best-case and worst-case weather at a fixed hour/day —
        // a smaller spread means less weather sensitivity.
        val factoryBest = EconomySystem.hourlyDemandMultiplier(BusinessType.FACTORY, 12, 2, Weather.CLEAR)
        val factoryWorst = EconomySystem.hourlyDemandMultiplier(BusinessType.FACTORY, 12, 2, Weather.STORM)
        val grocerBest = EconomySystem.hourlyDemandMultiplier(BusinessType.GROCER, 12, 2, Weather.CLEAR)
        val grocerWorst = EconomySystem.hourlyDemandMultiplier(BusinessType.GROCER, 12, 2, Weather.STORM)

        val factorySpread = factoryBest - factoryWorst
        val grocerSpread = grocerBest - grocerWorst
        assertThat(factorySpread).isLessThan(grocerSpread)
        // FACTORY is documented as flatly weather-insensitive: exactly zero spread.
        assertThat(factorySpread).isEqualTo(0.0)
    }

    @Test
    fun `WORKSHOP is also flatly weather- and time-insensitive like FACTORY`() {
        val values = hours.flatMap { h -> allWeather.map { w -> EconomySystem.hourlyDemandMultiplier(BusinessType.WORKSHOP, h, 2, w) } }
        assertThat(values.distinct()).containsExactly(1.0)
    }

    @Test
    fun `PUB weekend evening exceeds weekday evening`() {
        val weekendEvening = EconomySystem.hourlyDemandMultiplier(BusinessType.PUB, 20, dayOfWeek = 5, weather = Weather.CLEAR)
        val weekdayEvening = EconomySystem.hourlyDemandMultiplier(BusinessType.PUB, 20, dayOfWeek = 2, weather = Weather.CLEAR)
        assertThat(weekendEvening).isGreaterThan(weekdayEvening)
    }

    @Test
    fun `GROCER and HARDWARE stay flatter across the day than BAKERY or PUB`() {
        fun spreadAcrossHours(type: BusinessType): Double {
            val values = hours.map { h -> EconomySystem.hourlyDemandMultiplier(type, h, dayOfWeek = 2, weather = Weather.CLEAR) }
            return values.max() - values.min()
        }
        val grocerSpread = spreadAcrossHours(BusinessType.GROCER)
        val hardwareSpread = spreadAcrossHours(BusinessType.HARDWARE)
        val bakerySpread = spreadAcrossHours(BusinessType.BAKERY)
        val pubSpread = spreadAcrossHours(BusinessType.PUB)

        assertThat(grocerSpread).isLessThan(bakerySpread)
        assertThat(grocerSpread).isLessThan(pubSpread)
        assertThat(hardwareSpread).isLessThan(bakerySpread)
        assertThat(hardwareSpread).isLessThan(pubSpread)
    }

    @Test
    fun `PUB is less weather-sensitive than BAKERY and GROCER, at each type's own peak hour`() {
        // Compare at each type's own peak hour (evening for PUB, morning for BAKERY/GROCER) so
        // the comparison isn't distorted by an off-peak time-of-day factor pushing one type's
        // values down against the DEMAND_MULTIPLIER_MIN floor before the weather spread can show.
        // This directly reflects weatherSensitivity's own raw coefficient spread (PUB
        // CLEAR=1.05..STORM=0.65 vs BAKERY/GROCER CLEAR=1.05..STORM=0.35), which is what the
        // per-type doc comments in EconomySystem claim.
        fun weatherSpread(type: BusinessType, hour: Int): Double {
            val values = allWeather.map { w -> EconomySystem.hourlyDemandMultiplier(type, hour = hour, dayOfWeek = 2, weather = w) }
            return values.max() - values.min()
        }
        val pubSpread = weatherSpread(BusinessType.PUB, hour = 20)
        val bakerySpread = weatherSpread(BusinessType.BAKERY, hour = 8)
        val grocerSpread = weatherSpread(BusinessType.GROCER, hour = 12)
        assertThat(pubSpread).isLessThan(bakerySpread)
        assertThat(pubSpread).isLessThan(grocerSpread)
    }

    @Test
    fun `exhaustive sweep across all types, hours, days and weather stays within documented bounds`() {
        val allTypes = BusinessType.values().toList()
        var count = 0
        for (type in allTypes) {
            for (hour in hours) {
                for (day in daysOfWeek) {
                    for (weather in allWeather) {
                        val multiplier = EconomySystem.hourlyDemandMultiplier(type, hour, day, weather)
                        assertThat(multiplier).isAtLeast(EconomySystem.DEMAND_MULTIPLIER_MIN)
                        assertThat(multiplier).isAtMost(EconomySystem.DEMAND_MULTIPLIER_MAX)
                        count++
                    }
                }
            }
        }
        // Sanity: actually swept every combination (12 types x 14 hours x 7 days x 6 weather).
        assertThat(count).isEqualTo(allTypes.size * hours.count() * daysOfWeek.count() * allWeather.size)
    }

    @Test
    fun `same inputs always produce the same output (pure function, no rng)`() {
        val first = EconomySystem.hourlyDemandMultiplier(BusinessType.PUB, 20, 5, Weather.STORM)
        repeat(20) {
            val again = EconomySystem.hourlyDemandMultiplier(BusinessType.PUB, 20, 5, Weather.STORM)
            assertThat(again).isEqualTo(first)
        }
    }
}
