package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.database.DbJson
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.SimulationCoordinator
import org.junit.Test

class DeterminismTest {

    @Test
    fun `same seed evolves identically`() {
        val a = TestWorld.newCoordinator(seed = 99L)
        val b = TestWorld.newCoordinator(seed = 99L)
        val eventsA = TestWorld.runDays(a, 3)
        val eventsB = TestWorld.runDays(b, 3)
        assertThat(eventsA.map { it.description }).isEqualTo(eventsB.map { it.description })
        assertThat(stateJson(a.state)).isEqualTo(stateJson(b.state))
    }

    @Test
    fun `different seeds diverge`() {
        val a = TestWorld.newCoordinator(seed = 5L)
        val b = TestWorld.newCoordinator(seed = 6L)
        TestWorld.runDays(a, 3)
        TestWorld.runDays(b, 3)
        assertThat(stateJson(a.state)).isNotEqualTo(stateJson(b.state))
    }

    @Test
    fun `checkpoint restore continues the exact same future`() {
        val original = TestWorld.newCoordinator(seed = 321L)
        TestWorld.runDays(original, 2)
        // Serialise mid-flight, restore into a fresh coordinator.
        val checkpoint = stateJson(original.state)
        val restoredState = DbJson.json.decodeFromString(WorldState.serializer(), checkpoint)
        val restored = SimulationCoordinator(restoredState)

        val futureA = TestWorld.runDays(original, 2).map { it.id to it.description }
        val futureB = TestWorld.runDays(restored, 2).map { it.id to it.description }
        assertThat(futureB).isEqualTo(futureA)
        assertThat(stateJson(restored.state)).isEqualTo(stateJson(original.state))
    }

    @Test
    fun `no duplicate event ids are ever produced`() {
        val coordinator = TestWorld.newCoordinator()
        val events = TestWorld.runDays(coordinator, 6)
        assertThat(events.map { it.id }.toSet()).hasSize(events.size)
    }

    @Test
    fun `time advances one tick at a time`() {
        val coordinator = TestWorld.newCoordinator()
        val start = coordinator.state.time
        coordinator.tick()
        assertThat(coordinator.state.time).isEqualTo(start + SimTime.MINUTES_PER_TICK)
    }

    private fun stateJson(state: WorldState): String =
        DbJson.json.encodeToString(WorldState.serializer(), state)
}
