package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.DelayedEffect
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.EffectCondition
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.simulation.DelayedEffectSystem
import org.junit.Test

class DelayedEffectTest {

    @Test
    fun `an effect does not fire before its window opens`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        val stressBefore = mara.needs.stress
        state.delayedEffects += DelayedEffect(
            id = state.nextEffectId++, sourceEventId = 0,
            targetResidentId = mara.id, type = DelayedEffectType.STRESS_RISE,
            strength = 1.0,
            earliestAt = state.time + 10 * SimTime.MINUTES_PER_DAY,
            latestAt = state.time + 20 * SimTime.MINUTES_PER_DAY
        )
        repeat(50) { DelayedEffectSystem.update(TestWorld.contextFor(state, salt = it.toLong())) }
        assertThat(mara.needs.stress).isEqualTo(stressBefore)
        assertThat(state.delayedEffects.last().applied).isFalse()
    }

    @Test
    fun `an effect in its window eventually fires`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        val stressBefore = mara.needs.stress
        val effect = DelayedEffect(
            id = state.nextEffectId++, sourceEventId = 0,
            targetResidentId = mara.id, type = DelayedEffectType.STRESS_RISE,
            strength = 1.0,
            earliestAt = state.time, // window already open
            latestAt = state.time + SimTime.MINUTES_PER_DAY
        )
        state.delayedEffects += effect
        var salt = 0L
        while (!effect.applied && salt < 500) {
            DelayedEffectSystem.update(TestWorld.contextFor(state, salt = salt))
            salt++
        }
        assertThat(effect.applied).isTrue()
        assertThat(mara.needs.stress).isGreaterThan(stressBefore)
    }

    @Test
    fun `an expired window lapses silently`() {
        val state = TestWorld.newState()
        val effect = DelayedEffect(
            id = state.nextEffectId++, sourceEventId = 0,
            targetResidentId = TestWorld.resident(state, "Mara Vale").id,
            type = DelayedEffectType.STRESS_RISE, strength = 1.0,
            earliestAt = state.time - 10_000, latestAt = state.time - 5_000
        )
        state.delayedEffects += effect
        DelayedEffectSystem.update(TestWorld.contextFor(state))
        assertThat(effect.cancelled).isTrue()
        assertThat(effect.applied).isFalse()
    }

    @Test
    fun `conditions gate dormant effects`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        mara.needs.financialSecurity = 90.0 // comfortably off
        val effect = DelayedEffect(
            id = state.nextEffectId++, sourceEventId = 0,
            targetResidentId = mara.id, type = DelayedEffectType.CRIME_TEMPTATION,
            strength = 1.0, earliestAt = state.time,
            latestAt = state.time + SimTime.MINUTES_PER_DAY,
            condition = EffectCondition.STILL_POOR
        )
        state.delayedEffects += effect
        repeat(200) { DelayedEffectSystem.update(TestWorld.contextFor(state, salt = it.toLong())) }
        // The condition never held, so it never fired.
        assertThat(effect.applied).isFalse()
    }

    @Test
    fun `decaying effects lose strength while dormant`() {
        val state = TestWorld.newState()
        val effect = DelayedEffect(
            id = state.nextEffectId++, sourceEventId = 0,
            targetResidentId = TestWorld.resident(state, "Mara Vale").id,
            type = DelayedEffectType.STRESS_RISE, strength = 0.05,
            earliestAt = state.time - 10 * SimTime.MINUTES_PER_DAY,
            latestAt = state.time + 10 * SimTime.MINUTES_PER_DAY,
            decayPerDay = 0.02
        )
        state.delayedEffects += effect
        DelayedEffectSystem.update(TestWorld.contextFor(state))
        // 10 days of decay at 0.02/day exceeds 0.05 → cancelled outright.
        assertThat(effect.cancelled).isTrue()
    }
}
