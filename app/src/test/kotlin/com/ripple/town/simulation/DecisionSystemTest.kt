package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.simulation.ActionKind
import com.ripple.town.core.simulation.DecisionSystem
import com.ripple.town.core.simulation.ScoredAction
import com.ripple.town.core.simulation.SimRandom
import org.junit.Test

class DecisionSystemTest {

    private fun atHour(state: com.ripple.town.core.model.WorldState, hour: Int) {
        val dayStart = state.time - (state.time % SimTime.MINUTES_PER_DAY)
        state.time = dayStart + hour * 60L
    }

    @Test
    fun `a starving resident chooses to eat`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        atHour(state, 18)
        mara.needs.hunger = 3.0
        mara.needs.energy = 80.0
        val actions = DecisionSystem.candidateActions(state, mara, state.time)
        val best = DecisionSystem.chooseBest(actions, SimRandom(1L))
        assertThat(best.kind).isAnyOf(ActionKind.EAT_HOME, ActionKind.EAT_OUT)
    }

    @Test
    fun `an exhausted resident sleeps at night`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        atHour(state, 23)
        mara.needs.energy = 5.0
        mara.needs.hunger = 90.0
        val actions = DecisionSystem.candidateActions(state, mara, state.time)
        val best = DecisionSystem.chooseBest(actions, SimRandom(1L))
        assertThat(best.kind).isEqualTo(ActionKind.SLEEP)
    }

    @Test
    fun `an employed resident goes to work during shift hours on a weekday`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        // Advance to a weekday at 09:00 (day-of-week < 5).
        var dayStart = state.time - (state.time % SimTime.MINUTES_PER_DAY)
        while (SimTime.dayOfWeek(dayStart) >= 5) dayStart += SimTime.MINUTES_PER_DAY
        state.time = dayStart + 9 * 60L
        mara.needs.hunger = 80.0
        mara.needs.energy = 80.0
        val actions = DecisionSystem.candidateActions(state, mara, state.time)
        val best = DecisionSystem.chooseBest(actions, SimRandom(1L))
        assertThat(best.kind).isEqualTo(ActionKind.GO_TO_WORK)
    }

    @Test
    fun `children attend school not jobs`() {
        val state = TestWorld.newState()
        val milo = TestWorld.resident(state, "Milo Marsh")
        var dayStart = state.time - (state.time % SimTime.MINUTES_PER_DAY)
        while (SimTime.dayOfWeek(dayStart) >= 5) dayStart += SimTime.MINUTES_PER_DAY
        state.time = dayStart + 9 * 60L
        val actions = DecisionSystem.candidateActions(state, milo, state.time)
        assertThat(actions.map { it.kind }).contains(ActionKind.GO_TO_SCHOOL)
        assertThat(actions.map { it.kind }).doesNotContain(ActionKind.GO_TO_WORK)
    }

    @Test
    fun `randomness only breaks near ties`() {
        val strong = action(ActionKind.SLEEP, score = 2.0)
        val weak = action(ActionKind.SHOP, score = 0.4)
        // A clear winner is never displaced, whatever the RNG says.
        repeat(50) { i ->
            val best = DecisionSystem.chooseBest(listOf(weak, strong), SimRandom(i.toLong()))
            assertThat(best.kind).isEqualTo(ActionKind.SLEEP)
        }
    }

    @Test
    fun `utility terms combine as documented`() {
        val a = action(ActionKind.SHOP, score = 0.0)
        // score = need*fit*reward*confidence*social*opportunity - risk - cost - effort - moral
        val expected = 0.8 * 1.0 * 1.0 * 1.0 * 1.0 * 1.0 - 0.1 - 0.05 - 0.05 - 0.0
        assertThat(a.copy(needPressure = 0.8, risk = 0.1, cost = 0.05, effort = 0.05).score)
            .isWithin(1e-9).of(expected)
    }

    private fun action(kind: ActionKind, score: Double): ScoredAction = ScoredAction(
        kind = kind, targetBuildingId = null, activity = com.ripple.town.core.model.Activity.IDLE,
        durationMinutes = 10, reason = "test",
        needPressure = score, personalityFit = 1.0, expectedReward = 1.0, confidence = 1.0,
        socialInfluence = 1.0, opportunity = 1.0, risk = 0.0, cost = 0.0, effort = 0.0,
        moralResistance = 0.0
    )
}
