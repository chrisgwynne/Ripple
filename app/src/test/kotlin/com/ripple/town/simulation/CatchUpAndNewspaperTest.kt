package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.StoryCategory
import com.ripple.town.core.simulation.NewspaperGenerator
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.SimulationCoordinator
import org.junit.Test

class CatchUpAndNewspaperTest {

    @Test
    fun `offline catch-up is capped at thirty in-game days`() {
        val coordinator = TestWorld.newCoordinator()
        val start = coordinator.state.time
        // Pretend the app was closed for a year of real time.
        val yearMs = 365L * 24 * 60 * 60 * 1000
        val summary = coordinator.catchUp(yearMs)
        assertThat(summary.capped).isTrue()
        val advancedDays = (coordinator.state.time - start) / SimTime.MINUTES_PER_DAY
        assertThat(advancedDays).isAtMost(SimulationCoordinator.MAX_OFFLINE_DAYS)
        assertThat(summary.ticksRun).isEqualTo(
            (SimulationCoordinator.MAX_OFFLINE_DAYS * SimTime.TICKS_PER_DAY).toInt()
        )
    }

    @Test
    fun `short absences advance the exact elapsed time`() {
        val coordinator = TestWorld.newCoordinator()
        val start = coordinator.state.time
        // 2 real hours at 1x = 2 in-game hours = 12 ticks.
        val summary = coordinator.catchUp(2L * 60 * 60 * 1000)
        assertThat(summary.capped).isFalse()
        assertThat(coordinator.state.time - start).isEqualTo(2 * SimTime.MINUTES_PER_HOUR)
    }

    @Test
    fun `catch-up work per call is bounded`() {
        val coordinator = TestWorld.newCoordinator()
        val summary = coordinator.catchUp(30L * 24 * 60 * 60 * 1000, maxTicksPerCall = 100)
        assertThat(summary.ticksRun).isEqualTo(100)
    }

    @Test
    fun `newspapers appear over time and stay archived`() {
        val coordinator = TestWorld.newCoordinator()
        val issues = mutableListOf<com.ripple.town.core.model.NewspaperIssue>()
        repeat(9 * SimTime.TICKS_PER_DAY) {
            coordinator.tick().newIssue?.let { issues += it }
        }
        assertThat(issues.size).isAtLeast(2)
        assertThat(issues.map { it.issueNumber }).isInOrder()
        val first = issues.first()
        assertThat(first.masthead).contains("Argus")
        assertThat(first.stories.map { it.category }).contains(StoryCategory.HEADLINE)
        assertThat(first.stories.map { it.category }).contains(StoryCategory.WEATHER)
    }

    @Test
    fun `newspaper generation is deterministic and uses only public events`() {
        val stateA = TestWorld.newState(seed = 88L)
        val stateB = TestWorld.newState(seed = 88L)
        val ctxA = TestWorld.contextFor(stateA)
        val ctxB = TestWorld.contextFor(stateB)
        // One public, one private, one hidden event in each world.
        listOf(ctxA, ctxB).forEach { ctx ->
            ctx.emit(com.ripple.town.core.model.EventType.BUSINESS_EXPANDED, "The grocer built an extension.", severity = 0.5)
            ctx.emit(
                com.ripple.town.core.model.EventType.ARGUMENT, "A private row.",
                visibility = com.ripple.town.core.model.EventVisibility.PRIVATE
            )
            ctx.emit(
                com.ripple.town.core.model.EventType.CRIME_COMMITTED, "A secret theft.",
                visibility = com.ripple.town.core.model.EventVisibility.HIDDEN
            )
        }
        val issueA = NewspaperGenerator.generate(stateA, ctxA.newEvents, SimRandom(1L))
        val issueB = NewspaperGenerator.generate(stateB, ctxB.newEvents, SimRandom(1L))
        assertThat(issueA.stories.map { it.headline }).isEqualTo(issueB.stories.map { it.headline })
        val allText = issueA.stories.joinToString { it.headline + it.body }
        assertThat(allText).doesNotContain("private row")
        assertThat(allText).doesNotContain("secret theft")
    }
}
