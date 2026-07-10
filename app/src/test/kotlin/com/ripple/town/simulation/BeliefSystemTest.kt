package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Belief
import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Memory
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.BeliefSystem
import com.ripple.town.core.simulation.InMemoryEventIndex
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.TickContext
import org.junit.Test

/**
 * Covers `BeliefSystem`: belief inheritance from a parent (noisy, lower-confidence copy),
 * a real trigger (crime victimisation) measurably shifting the relevant belief, bounded drift,
 * and determinism. See `docs/simulation-rules.md` "Beliefs" for the full design writeup.
 *
 * Unlike `TestWorld.contextFor` (which builds a fresh, empty `InMemoryEventIndex` every call —
 * fine for tests that only touch `resident.memories` directly), several tests here emit a crime
 * in one tick and need `BeliefSystem` to find it via `ctx.eventIndex` on a *later* tick, the same
 * way `SimulationCoordinator`'s real tick loop shares one `InMemoryEventIndex` across ticks. So
 * this file keeps its own shared index per test and builds `TickContext` directly.
 */
class BeliefSystemTest {

    private fun contextWith(state: WorldState, index: InMemoryEventIndex, salt: Long = 0L): TickContext =
        TickContext(state, SimRandom(state.seed, SimTime.tickOf(state.time), salt), index)

    private fun addMemory(
        state: WorldState,
        resident: com.ripple.town.core.model.Resident,
        type: MemoryType,
        description: String,
        importance: Double,
        createdAt: Long = state.time
    ): Memory {
        val m = Memory(
            id = state.nextMemoryId++,
            residentId = resident.id,
            eventId = null,
            type = type,
            description = description,
            emotionalIntensity = importance,
            importance = importance,
            createdAt = createdAt,
            lastRecalledAt = createdAt
        )
        resident.memories += m
        return m
    }

    // ------------------------------------------------------------ inheritance from a parent

    @Test
    fun `a teen inherits a noisy, lower-confidence version of a parent's belief`() {
        val state = TestWorld.newState()
        val parent = TestWorld.resident(state, "Mara Vale")
        parent.detailLevel = DetailLevel.DETAILED
        parent.beliefs[BeliefTopic.TRUST_IN_POLICE] = Belief(
            topic = BeliefTopic.TRUST_IN_POLICE,
            position = 0.6,
            confidence = 0.8,
            lastUpdatedAt = state.time
        )

        val teen = com.ripple.town.core.model.Resident(
            id = 90001L,
            firstName = "Robin",
            surname = "Vale",
            gender = parent.gender,
            bornAt = state.time - 15 * SimTime.MINUTES_PER_YEAR, // age 15 -> TEEN
            homeBuildingId = parent.homeBuildingId,
            householdId = parent.householdId,
            detailLevel = DetailLevel.DETAILED,
            motherId = if (parent.gender == com.ripple.town.core.model.Gender.FEMALE) parent.id else null,
            fatherId = if (parent.gender == com.ripple.town.core.model.Gender.MALE) parent.id else null
        )
        state.residents[teen.id] = teen

        assertThat(teen.beliefs).isEmpty()

        val ctx = contextWith(state, InMemoryEventIndex())
        BeliefSystem.updateDaily(ctx)

        val inherited = teen.beliefs[BeliefTopic.TRUST_IN_POLICE]
        assertThat(inherited).isNotNull()
        inherited!!
        // Lower confidence than the parent's own — the teen hasn't earned this view yet.
        assertThat(inherited.confidence).isLessThan(0.8)
        assertThat(inherited.confidence).isWithin(1e-9).of(0.8 * BeliefSystem.INHERITED_CONFIDENCE_FACTOR)
        // Position stays within the belief's bounded range even after inheritance noise.
        assertThat(inherited.position).isAtLeast(-1.0)
        assertThat(inherited.position).isAtMost(1.0)
        assertThat(ctx.newEvents.map { it.type }).contains(EventType.BELIEF_SHIFTED)
    }

    @Test
    fun `a teen with a parent who has no beliefs yet inherits nothing`() {
        val state = TestWorld.newState()
        val parent = TestWorld.resident(state, "Mara Vale")
        parent.detailLevel = DetailLevel.DETAILED
        // parent.beliefs left empty deliberately

        val teen = com.ripple.town.core.model.Resident(
            id = 90002L,
            firstName = "Robin",
            surname = "Vale",
            gender = parent.gender,
            bornAt = state.time - 15 * SimTime.MINUTES_PER_YEAR,
            homeBuildingId = parent.homeBuildingId,
            householdId = parent.householdId,
            detailLevel = DetailLevel.DETAILED,
            motherId = if (parent.gender == com.ripple.town.core.model.Gender.FEMALE) parent.id else null,
            fatherId = if (parent.gender == com.ripple.town.core.model.Gender.MALE) parent.id else null
        )
        state.residents[teen.id] = teen

        val ctx = contextWith(state, InMemoryEventIndex())
        BeliefSystem.updateDaily(ctx)

        assertThat(teen.beliefs).isEmpty()
    }

    // ------------------------------------------------------------ real trigger: crime victimisation

    @Test
    fun `crime victimisation with an inaccurate report measurably lowers trust in police`() {
        val state = TestWorld.newState()
        val victim = TestWorld.resident(state, "Mara Vale")
        victim.detailLevel = DetailLevel.DETAILED
        val innocent = state.residents.values.first { it.id != victim.id }
        val index = InMemoryEventIndex()

        val ctx = contextWith(state, index)
        val crime = ctx.emit(
            EventType.BURGLARY,
            "A home was broken into.",
            sourceResidentId = innocent.id,
            targetResidentIds = listOf(victim.id),
            severity = 0.55
        )
        val report = ctx.emit(
            EventType.CRIME_REPORTED,
            "Someone else was wrongly named.",
            sourceResidentId = state.residents.values.first().id,
            targetResidentIds = listOf(state.residents.values.last().id),
            severity = 0.35,
            causeIds = listOf(crime.id),
            payload = mapOf("accurate" to "false")
        )
        state.recentEventIds += crime.id
        state.recentEventIds += report.id
        ctx.newEvents.forEach { index.remember(it) }

        assertThat(BeliefSystem.positionOn(victim, BeliefTopic.TRUST_IN_POLICE)).isEqualTo(0.0)

        val ctx2 = contextWith(state, index)
        BeliefSystem.updateDaily(ctx2)

        val belief = victim.beliefs[BeliefTopic.TRUST_IN_POLICE]
        assertThat(belief).isNotNull()
        assertThat(belief!!.position).isLessThan(0.0)
        assertThat(ctx2.newEvents.any { it.type == EventType.BELIEF_SHIFTED && it.causeIds.contains(report.id) }).isTrue()
    }

    @Test
    fun `an accurate crime report keeps trust in police from dropping, or lifts it slightly`() {
        val state = TestWorld.newState()
        val victim = TestWorld.resident(state, "Mara Vale")
        victim.detailLevel = DetailLevel.DETAILED
        val culprit = state.residents.values.first { it.id != victim.id }
        val index = InMemoryEventIndex()

        val ctx = contextWith(state, index)
        val crime = ctx.emit(
            EventType.BURGLARY, "A home was broken into.",
            sourceResidentId = culprit.id, targetResidentIds = listOf(victim.id), severity = 0.55
        )
        val report = ctx.emit(
            EventType.CRIME_REPORTED, "The constable named the right person.",
            sourceResidentId = state.residents.values.first().id,
            targetResidentIds = listOf(culprit.id),
            severity = 0.35, causeIds = listOf(crime.id),
            payload = mapOf("accurate" to "true")
        )
        state.recentEventIds += crime.id
        state.recentEventIds += report.id
        ctx.newEvents.forEach { index.remember(it) }

        val ctx2 = contextWith(state, index)
        BeliefSystem.updateDaily(ctx2)

        val belief = victim.beliefs[BeliefTopic.TRUST_IN_POLICE]
        assertThat(belief).isNotNull()
        assertThat(belief!!.position).isGreaterThan(0.0)
    }

    // ------------------------------------------------------------ bounded drift

    @Test
    fun `belief position never leaves the -1 to 1 range no matter how many triggers fire`() {
        val state = TestWorld.newState()
        val victim = TestWorld.resident(state, "Mara Vale")
        victim.detailLevel = DetailLevel.DETAILED
        val culprit = state.residents.values.first { it.id != victim.id }
        val index = InMemoryEventIndex()

        repeat(60) { i ->
            val ctx = contextWith(state, index, salt = i.toLong())
            val crime = ctx.emit(
                EventType.BURGLARY, "A home was broken into.",
                sourceResidentId = culprit.id, targetResidentIds = listOf(victim.id), severity = 0.55
            )
            val report = ctx.emit(
                EventType.CRIME_REPORTED, "Wrongly accused, again.",
                sourceResidentId = state.residents.values.first().id,
                targetResidentIds = listOf(state.residents.values.last().id),
                severity = 0.35, causeIds = listOf(crime.id),
                payload = mapOf("accurate" to "false")
            )
            state.recentEventIds += crime.id
            state.recentEventIds += report.id
            ctx.newEvents.forEach { index.remember(it) }
            BeliefSystem.updateDaily(ctx)
            state.time += SimTime.MINUTES_PER_DAY
        }

        val belief = victim.beliefs[BeliefTopic.TRUST_IN_POLICE]
        assertThat(belief).isNotNull()
        assertThat(belief!!.position).isAtLeast(-1.0)
        assertThat(belief.position).isAtMost(1.0)
        assertThat(belief.confidence).isAtLeast(0.0)
        assertThat(belief.confidence).isAtMost(1.0)
    }

    @Test
    fun `personal success drift stays within the small per-trigger delta band`() {
        val state = TestWorld.newState()
        val achiever = TestWorld.resident(state, "Mara Vale")
        achiever.detailLevel = DetailLevel.DETAILED
        addMemory(state, achiever, MemoryType.ACHIEVEMENT, "Finished the big project.", importance = 60.0)
        addMemory(state, achiever, MemoryType.ACHIEVEMENT, "Won recognition at work.", importance = 55.0)

        val ctx = contextWith(state, InMemoryEventIndex())
        BeliefSystem.updateDaily(ctx)

        val belief = achiever.beliefs[BeliefTopic.ECONOMIC_OPTIMISM]
        assertThat(belief).isNotNull()
        // Confidence starts at 0 for a brand-new belief, so resistance is at its loosest —
        // the applied delta should still not exceed the raw DRIFT_MAX band.
        assertThat(belief!!.position).isAtMost(BeliefSystem.DRIFT_MAX + 1e-9)
        assertThat(belief.position).isGreaterThan(0.0)
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `same seed produces the same belief timeline`() {
        val state1 = TestWorld.newState()
        val state2 = TestWorld.newState()
        val r1 = TestWorld.resident(state1, "Mara Vale")
        val r2 = TestWorld.resident(state2, "Mara Vale")
        r1.detailLevel = DetailLevel.DETAILED
        r2.detailLevel = DetailLevel.DETAILED
        val index1 = InMemoryEventIndex()
        val index2 = InMemoryEventIndex()

        repeat(10) { i ->
            val ctx1 = contextWith(state1, index1, salt = i.toLong())
            val culprit1 = state1.residents.values.first { it.id != r1.id }
            val crime1 = ctx1.emit(
                EventType.BURGLARY, "A home was broken into.",
                sourceResidentId = culprit1.id, targetResidentIds = listOf(r1.id), severity = 0.55
            )
            val report1 = ctx1.emit(
                EventType.CRIME_REPORTED, "Wrongly accused.",
                sourceResidentId = state1.residents.values.first().id,
                targetResidentIds = listOf(state1.residents.values.last().id),
                severity = 0.35, causeIds = listOf(crime1.id), payload = mapOf("accurate" to "false")
            )
            state1.recentEventIds += crime1.id
            state1.recentEventIds += report1.id
            ctx1.newEvents.forEach { index1.remember(it) }
            BeliefSystem.updateDaily(ctx1)

            val ctx2 = contextWith(state2, index2, salt = i.toLong())
            val culprit2 = state2.residents.values.first { it.id != r2.id }
            val crime2 = ctx2.emit(
                EventType.BURGLARY, "A home was broken into.",
                sourceResidentId = culprit2.id, targetResidentIds = listOf(r2.id), severity = 0.55
            )
            val report2 = ctx2.emit(
                EventType.CRIME_REPORTED, "Wrongly accused.",
                sourceResidentId = state2.residents.values.first().id,
                targetResidentIds = listOf(state2.residents.values.last().id),
                severity = 0.35, causeIds = listOf(crime2.id), payload = mapOf("accurate" to "false")
            )
            state2.recentEventIds += crime2.id
            state2.recentEventIds += report2.id
            ctx2.newEvents.forEach { index2.remember(it) }
            BeliefSystem.updateDaily(ctx2)

            state1.time += SimTime.MINUTES_PER_DAY
            state2.time += SimTime.MINUTES_PER_DAY
        }

        val b1 = r1.beliefs[BeliefTopic.TRUST_IN_POLICE]
        val b2 = r2.beliefs[BeliefTopic.TRUST_IN_POLICE]
        assertThat(b1).isNotNull()
        assertThat(b2).isNotNull()
        assertThat(b1!!.position).isEqualTo(b2!!.position)
        assertThat(b1.confidence).isEqualTo(b2.confidence)
    }

    // ------------------------------------------------------------ helper reads

    @Test
    fun `positionOn and confidenceOn return neutral defaults when a resident has no view yet`() {
        val state = TestWorld.newState()
        val r = TestWorld.resident(state, "Mara Vale")
        assertThat(BeliefSystem.positionOn(r, BeliefTopic.SOCIAL_OPENNESS)).isEqualTo(0.0)
        assertThat(BeliefSystem.confidenceOn(r, BeliefTopic.SOCIAL_OPENNESS)).isEqualTo(0.0)
    }
}
