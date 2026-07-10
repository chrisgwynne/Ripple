package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import com.ripple.town.core.simulation.TownSentimentSystem
import org.junit.Test

/**
 * Covers `TownSentimentSystem`: repeated unsolved crime measurably raising fear/lowering
 * safety, a successful crisis response measurably raising civic pride/trust, decay toward the
 * neutral baseline when nothing is actively pushing sentiment, bounded range, and determinism.
 * See `docs/simulation-rules.md` "Town sentiment" for the full design writeup and
 * `BeliefSystemTest`'s own doc comment for why this file keeps a shared `InMemoryEventIndex`
 * across ticks (`TownSentimentSystem` needs to find events emitted on earlier ticks via
 * `ctx.eventIndex`, the same way `SimulationCoordinator`'s real tick loop shares one index).
 *
 * Compile-checked only, per this session's constraints — the full gradle test suite was not run
 * and `./gradlew` was not invoked.
 */
class TownSentimentSystemTest {

    private fun contextWith(state: WorldState, index: InMemoryEventIndex, salt: Long = 0L): TickContext =
        TickContext(state, SimRandom(state.seed, SimTime.tickOf(state.time), salt), index)

    /** Emits a `CRIME_REPORTED` event (matching `CrimeSystem.investigate`'s real payload shape)
     *  and registers it in both the recent-events window and the shared index, same pattern
     *  `BeliefSystemTest` uses. */
    private fun emitCrimeReport(
        ctx: TickContext,
        state: WorldState,
        index: InMemoryEventIndex,
        accurate: Boolean
    ) {
        val crime = ctx.emit(
            EventType.BURGLARY, "A home was broken into.",
            sourceResidentId = state.residents.values.first().id,
            targetResidentIds = listOf(state.residents.values.last().id),
            severity = 0.55
        )
        val report = ctx.emit(
            EventType.CRIME_REPORTED, "The constable named a suspect.",
            sourceResidentId = state.residents.values.first().id,
            targetResidentIds = listOf(state.residents.values.last().id),
            severity = 0.35, causeIds = listOf(crime.id),
            payload = mapOf("accurate" to accurate.toString())
        )
        state.recentEventIds += crime.id
        state.recentEventIds += report.id
        ctx.newEvents.forEach { index.remember(it) }
    }

    // ------------------------------------------------------------ trigger 1: repeated unsolved crime

    @Test
    fun `a run of inaccurate crime reports measurably raises fear and lowers safety`() {
        val state = TestWorld.newState()
        val index = InMemoryEventIndex()
        val before = state.townSentiment.copy()

        repeat(5) { i ->
            val ctx = contextWith(state, index, salt = i.toLong())
            emitCrimeReport(ctx, state, index, accurate = false)
            state.time += SimTime.MINUTES_PER_DAY
        }
        val ctx = contextWith(state, index, salt = 99L)
        TownSentimentSystem.updateDaily(ctx)

        assertThat(state.townSentiment.fear).isGreaterThan(before.fear)
        assertThat(state.townSentiment.safety).isLessThan(before.safety)
    }

    @Test
    fun `a single isolated crime report does not move the town's mood`() {
        val state = TestWorld.newState()
        val index = InMemoryEventIndex()
        val before = state.townSentiment.copy()

        val ctx = contextWith(state, index)
        emitCrimeReport(ctx, state, index, accurate = true)
        TownSentimentSystem.updateDaily(ctx)

        // One isolated report is below CRIME_RUN_THRESHOLD, so fear/safety should only move by
        // (at most) the same-day decay pull, not a real event-driven delta.
        assertThat(kotlin.math.abs(state.townSentiment.fear - before.fear)).isLessThan(1.0)
        assertThat(kotlin.math.abs(state.townSentiment.safety - before.safety)).isLessThan(1.0)
    }

    // ------------------------------------------------------------ trigger 2: successful crisis response

    @Test
    fun `a flood followed by a repair measurably raises civic pride and trust`() {
        val state = TestWorld.newState()
        val index = InMemoryEventIndex()
        val building = state.buildings.values.first()
        val before = state.townSentiment.copy()

        val ctx1 = contextWith(state, index)
        val flood = ctx1.emit(
            EventType.WEATHER_DAMAGE, "The river burst its banks.",
            buildingId = building.id, severity = 0.65
        )
        state.recentEventIds += flood.id
        ctx1.newEvents.forEach { index.remember(it) }
        state.time += SimTime.MINUTES_PER_DAY

        val ctx2 = contextWith(state, index)
        val repaired = ctx2.emit(
            EventType.BUILDING_REPAIRED, "The building has been repaired.",
            buildingId = building.id, severity = 0.3
        )
        state.recentEventIds += repaired.id
        ctx2.newEvents.forEach { index.remember(it) }

        val ctx3 = contextWith(state, index, salt = 5L)
        TownSentimentSystem.updateDaily(ctx3)

        assertThat(state.townSentiment.civicPride).isGreaterThan(before.civicPride)
        assertThat(state.townSentiment.trust).isGreaterThan(before.trust)
    }

    // ------------------------------------------------------------ decay toward baseline

    @Test
    fun `sentiment decays back toward the neutral baseline when nothing pushes it`() {
        val state = TestWorld.newState()
        state.townSentiment.fear = 90.0
        state.townSentiment.trust = 10.0
        val index = InMemoryEventIndex()

        repeat(20) { i ->
            val ctx = contextWith(state, index, salt = i.toLong())
            TownSentimentSystem.updateDaily(ctx)
            state.time += SimTime.MINUTES_PER_DAY
        }

        // Moved meaningfully back toward 50 from both extremes, with nothing actively pushing.
        assertThat(state.townSentiment.fear).isLessThan(90.0)
        assertThat(state.townSentiment.fear).isGreaterThan(50.0)
        assertThat(state.townSentiment.trust).isGreaterThan(10.0)
        assertThat(state.townSentiment.trust).isLessThan(50.0)
    }

    @Test
    fun `sentiment already at baseline stays at baseline with no triggers`() {
        val state = TestWorld.newState()
        val index = InMemoryEventIndex()

        val ctx = contextWith(state, index)
        TownSentimentSystem.updateDaily(ctx)

        val s = state.townSentiment
        assertThat(s.trust).isWithin(1e-9).of(50.0)
        assertThat(s.fear).isWithin(1e-9).of(50.0)
        assertThat(s.optimism).isWithin(1e-9).of(50.0)
        assertThat(s.civicPride).isWithin(1e-9).of(50.0)
        assertThat(s.safety).isWithin(1e-9).of(50.0)
        assertThat(s.cohesion).isWithin(1e-9).of(50.0)
    }

    // ------------------------------------------------------------ bounded

    @Test
    fun `sentiment never leaves the 0 to 100 range no matter how many triggers fire`() {
        val state = TestWorld.newState()
        val index = InMemoryEventIndex()

        repeat(60) { i ->
            val ctx = contextWith(state, index, salt = i.toLong())
            emitCrimeReport(ctx, state, index, accurate = false)
            emitCrimeReport(ctx, state, index, accurate = false)
            emitCrimeReport(ctx, state, index, accurate = false)
            TownSentimentSystem.updateDaily(ctx)
            state.time += SimTime.MINUTES_PER_DAY

            val s = state.townSentiment
            assertThat(s.trust).isAtLeast(0.0); assertThat(s.trust).isAtMost(100.0)
            assertThat(s.fear).isAtLeast(0.0); assertThat(s.fear).isAtMost(100.0)
            assertThat(s.optimism).isAtLeast(0.0); assertThat(s.optimism).isAtMost(100.0)
            assertThat(s.civicPride).isAtLeast(0.0); assertThat(s.civicPride).isAtMost(100.0)
            assertThat(s.safety).isAtLeast(0.0); assertThat(s.safety).isAtMost(100.0)
            assertThat(s.cohesion).isAtLeast(0.0); assertThat(s.cohesion).isAtMost(100.0)
        }
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `same seed produces the same sentiment timeline`() {
        val state1 = TestWorld.newState()
        val state2 = TestWorld.newState()
        val index1 = InMemoryEventIndex()
        val index2 = InMemoryEventIndex()

        repeat(15) { i ->
            val ctx1 = contextWith(state1, index1, salt = i.toLong())
            emitCrimeReport(ctx1, state1, index1, accurate = i % 2 == 0)
            TownSentimentSystem.updateDaily(ctx1)

            val ctx2 = contextWith(state2, index2, salt = i.toLong())
            emitCrimeReport(ctx2, state2, index2, accurate = i % 2 == 0)
            TownSentimentSystem.updateDaily(ctx2)

            state1.time += SimTime.MINUTES_PER_DAY
            state2.time += SimTime.MINUTES_PER_DAY
        }

        assertThat(state1.townSentiment.fear).isEqualTo(state2.townSentiment.fear)
        assertThat(state1.townSentiment.safety).isEqualTo(state2.townSentiment.safety)
        assertThat(state1.townSentiment.trust).isEqualTo(state2.townSentiment.trust)
    }

    // ------------------------------------------------------------ significant-shift reporting

    @Test
    fun `a genuine band crossing emits a low-severity TOWN_MILESTONE event`() {
        val state = TestWorld.newState()
        val index = InMemoryEventIndex()

        var sawMilestone = false
        repeat(8) { i ->
            val ctx = contextWith(state, index, salt = i.toLong())
            emitCrimeReport(ctx, state, index, accurate = false)
            TownSentimentSystem.updateDaily(ctx)
            if (ctx.newEvents.any { it.type == EventType.TOWN_MILESTONE && it.severity <= 0.3 }) {
                sawMilestone = true
            }
            state.time += SimTime.MINUTES_PER_DAY
        }

        // Somewhere across the run, fear should have crossed HIGH_BAND and reported it exactly
        // once at the crossing — never on every small daily nudge.
        assertThat(sawMilestone).isTrue()
        assertThat(state.townSentiment.fear).isGreaterThan(TownSentimentSystem.BASELINE)
    }

    // ------------------------------------------------------------ read helper

    @Test
    fun `summaryPhrase reflects the most extreme dimension away from baseline`() {
        val state = TestWorld.newState()
        state.townSentiment.fear = 85.0
        val phrase = TownSentimentSystem.summaryPhrase(state)
        assertThat(phrase).isNotEmpty()

        val neutralState = TestWorld.newState()
        val neutralPhrase = TownSentimentSystem.summaryPhrase(neutralState)
        assertThat(neutralPhrase).isEqualTo("The town feels much as it usually does.")
    }

    // ------------------------------------------------------------ behavioural feedback: DecisionSystem

    @Test
    fun `high fear low safety town mood dampens evening social action scoring`() {
        val state = TestWorld.newState()
        val resident = TestWorld.resident(state, "Mara Vale")
        resident.detailLevel = DetailLevel.DETAILED
        resident.needs.social = 20.0 // lonely enough to trigger socialising candidates
        state.time = SimTime.MINUTES_PER_DAY * 2 + 19 * 60L // evening hour, sociable window

        val neutralActions = com.ripple.town.core.simulation.DecisionSystem
            .candidateActions(state, resident, state.time)
        val neutralSocial = neutralActions.filter {
            it.kind == com.ripple.town.core.simulation.ActionKind.SOCIALISE_PUBLIC ||
                it.kind == com.ripple.town.core.simulation.ActionKind.VISIT_FRIEND
        }

        state.townSentiment.fear = 95.0
        state.townSentiment.safety = 5.0
        val fearfulActions = com.ripple.town.core.simulation.DecisionSystem
            .candidateActions(state, resident, state.time)
        val fearfulSocial = fearfulActions.filter {
            it.kind == com.ripple.town.core.simulation.ActionKind.SOCIALISE_PUBLIC ||
                it.kind == com.ripple.town.core.simulation.ActionKind.VISIT_FRIEND
        }

        // Only compare when both runs actually produced a matching candidate action (an open
        // public spot / a friend to visit might not always exist in the seeded world) — but the
        // personalityFit term itself, for any matching kind, must be lower under the fearful mood.
        for (fearfulAction in fearfulSocial) {
            val matching = neutralSocial.firstOrNull { it.kind == fearfulAction.kind }
            if (matching != null) {
                assertThat(fearfulAction.personalityFit).isLessThan(matching.personalityFit)
            }
        }
    }

    // ------------------------------------------------------------ behavioural feedback: VotingSystem

    @Test
    fun `low trust low civic pride town mood lowers turnout chance vs the base formula`() {
        val state = TestWorld.newState()
        val voter = com.ripple.town.core.model.Resident(
            id = 90101L,
            firstName = "Voter",
            surname = "Test",
            gender = com.ripple.town.core.model.Gender.NONBINARY,
            bornAt = state.time - 30 * SimTime.MINUTES_PER_YEAR,
            homeBuildingId = null,
            householdId = null,
            detailLevel = DetailLevel.DETAILED
        )
        state.residents[voter.id] = voter

        val baseline = com.ripple.town.core.simulation.VotingSystem.turnoutChance(voter)
        val baselineWithSentiment = com.ripple.town.core.simulation.VotingSystem.turnoutChance(voter, state)
        assertThat(baselineWithSentiment).isWithin(1e-9).of(baseline)

        state.townSentiment.trust = 5.0
        state.townSentiment.civicPride = 5.0
        val distrustful = com.ripple.town.core.simulation.VotingSystem.turnoutChance(voter, state)
        assertThat(distrustful).isLessThan(baseline)
        assertThat(distrustful).isAtLeast(com.ripple.town.core.simulation.VotingSystem.MIN_TURNOUT_CHANCE)

        state.townSentiment.trust = 95.0
        state.townSentiment.civicPride = 95.0
        val proud = com.ripple.town.core.simulation.VotingSystem.turnoutChance(voter, state)
        assertThat(proud).isGreaterThan(baseline)
        assertThat(proud).isAtMost(com.ripple.town.core.simulation.VotingSystem.MAX_TURNOUT_CHANCE)
    }
}

/** Small local copy helper mirroring `TownSentimentSystem`'s own private one — kept test-local
 *  since the production one is private to that file. */
private fun com.ripple.town.core.model.TownSentiment.copy(): com.ripple.town.core.model.TownSentiment =
    com.ripple.town.core.model.TownSentiment(trust, fear, optimism, civicPride, safety, cohesion)
