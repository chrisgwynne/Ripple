package com.ripple.town.simulation

import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.SimulationCoordinator
import com.ripple.town.core.simulation.TickContext
import com.ripple.town.core.simulation.WorldGenerator

/** Shared helpers for engine tests. */
object TestWorld {
    const val SEED = 424242L
    const val CREATED_AT = 1_700_000_000_000L

    fun newState(seed: Long = SEED): WorldState =
        WorldGenerator(seed, "Testholme").generate(CREATED_AT)

    fun newCoordinator(seed: Long = SEED): SimulationCoordinator =
        SimulationCoordinator(newState(seed))

    fun contextFor(state: WorldState, salt: Long = 0L): TickContext =
        TickContext(state, SimRandom(state.seed, SimTime.tickOf(state.time), salt), InMemoryEventIndex())

    fun runDays(coordinator: SimulationCoordinator, days: Int): List<com.ripple.town.core.model.WorldEvent> {
        val events = mutableListOf<com.ripple.town.core.model.WorldEvent>()
        repeat(days * SimTime.TICKS_PER_DAY) {
            events += coordinator.tick().events
        }
        return events
    }

    fun resident(state: WorldState, name: String) =
        state.residents.values.first { it.fullName == name }
}
