package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.ActiveEmotion
import com.ripple.town.core.model.EmotionType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Memory
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.simulation.MemoryRecallSystem
import org.junit.Test

class MemoryRecallSystemTest {

    private fun addMemory(
        state: com.ripple.town.core.model.WorldState,
        resident: com.ripple.town.core.model.Resident,
        type: MemoryType,
        description: String,
        importance: Double,
        intensity: Double = importance,
        createdAt: Long = state.time,
        associated: List<Long> = emptyList()
    ): Memory {
        val m = Memory(
            id = state.nextMemoryId++,
            residentId = resident.id,
            eventId = null,
            type = type,
            description = description,
            emotionalIntensity = intensity,
            importance = importance,
            createdAt = createdAt,
            lastRecalledAt = createdAt,
            associatedResidentIds = associated
        )
        resident.memories += m
        return m
    }

    @Test
    fun `a matching emotional echo trigger resurfaces the memory at reduced intensity`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        val memory = addMemory(state, mara, MemoryType.LOSS, "The day the old shop closed for good.", importance = 60.0, intensity = 80.0)
        val originalRecalledAt = memory.lastRecalledAt

        // Active GRIEF emotion should echo-match the LOSS memory (echoEmotionFor(LOSS) == GRIEF).
        mara.activeEmotions += ActiveEmotion(
            type = EmotionType.GRIEF, intensity = 50.0, createdAt = state.time,
            lastTriggeredAt = state.time, decayRate = 3.0
        )

        val ctx = TestWorld.contextFor(state)
        MemoryRecallSystem.updateDaily(ctx)

        assertThat(memory.lastRecalledAt).isGreaterThan(originalRecalledAt)
        assertThat(ctx.newEvents.map { it.type }).contains(EventType.MEMORY_RECALLED)

        // A real echo emotion was spawned/deepened, at (roughly) the reduced-intensity factor of
        // the *original* memory's emotionalIntensity — never a full re-living.
        val grief = mara.activeEmotions.first { it.type == EmotionType.GRIEF }
        val expectedEcho = 80.0 * MemoryRecallSystem.RESURFACE_INTENSITY_FACTOR
        assertThat(grief.intensity).isLessThan(80.0)
        // spawnEmotion refreshes an existing same-type emotion via (existing + new*0.5), so the
        // result should sit somewhere between the pre-existing 50.0 and a full new spawn.
        assertThat(grief.intensity).isAtLeast(50.0)
        assertThat(grief.intensity).isAtMost(50.0 + expectedEcho)
    }

    @Test
    fun `the same memory does not re-trigger within its cooldown window`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        addMemory(state, mara, MemoryType.LOSS, "The day the old shop closed for good.", importance = 60.0, intensity = 80.0)
        mara.activeEmotions += ActiveEmotion(
            type = EmotionType.GRIEF, intensity = 50.0, createdAt = state.time,
            lastTriggeredAt = state.time, decayRate = 3.0
        )

        val ctx1 = TestWorld.contextFor(state)
        MemoryRecallSystem.updateDaily(ctx1)
        assertThat(ctx1.newEvents.map { it.type }).contains(EventType.MEMORY_RECALLED)
        val recalledAtAfterFirst = mara.memories.first().lastRecalledAt

        // Advance a small number of in-game days, well inside the cooldown window, and try again.
        state.time += 3 * com.ripple.town.core.model.SimTime.MINUTES_PER_DAY
        val ctx2 = TestWorld.contextFor(state)
        MemoryRecallSystem.updateDaily(ctx2)

        assertThat(ctx2.newEvents.map { it.type }).doesNotContain(EventType.MEMORY_RECALLED)
        assertThat(mara.memories.first().lastRecalledAt).isEqualTo(recalledAtAfterFirst)
    }

    @Test
    fun `resurfacing never grows the memory list`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        addMemory(state, mara, MemoryType.LOSS, "The day the old shop closed for good.", importance = 60.0, intensity = 80.0)
        mara.activeEmotions += ActiveEmotion(
            type = EmotionType.GRIEF, intensity = 50.0, createdAt = state.time,
            lastTriggeredAt = state.time, decayRate = 3.0
        )
        val sizeBefore = mara.memories.size

        val ctx = TestWorld.contextFor(state)
        MemoryRecallSystem.updateDaily(ctx)

        // Resurfacing updates the existing Memory row in place; it never appends a new one.
        assertThat(mara.memories.size).isEqualTo(sizeBefore)
    }

    @Test
    fun `same seed produces the same recall timeline`() {
        val state1 = TestWorld.newState()
        val state2 = TestWorld.newState()
        val r1 = TestWorld.resident(state1, "Mara Vale")
        val r2 = TestWorld.resident(state2, "Mara Vale")
        addMemory(state1, r1, MemoryType.LOSS, "The day the old shop closed for good.", importance = 60.0, intensity = 80.0)
        addMemory(state2, r2, MemoryType.LOSS, "The day the old shop closed for good.", importance = 60.0, intensity = 80.0)
        r1.activeEmotions += ActiveEmotion(EmotionType.GRIEF, 50.0, createdAt = state1.time, lastTriggeredAt = state1.time, decayRate = 3.0)
        r2.activeEmotions += ActiveEmotion(EmotionType.GRIEF, 50.0, createdAt = state2.time, lastTriggeredAt = state2.time, decayRate = 3.0)

        repeat(20) {
            MemoryRecallSystem.updateDaily(TestWorld.contextFor(state1, salt = it.toLong()))
            MemoryRecallSystem.updateDaily(TestWorld.contextFor(state2, salt = it.toLong()))
            state1.time += com.ripple.town.core.model.SimTime.MINUTES_PER_DAY
            state2.time += com.ripple.town.core.model.SimTime.MINUTES_PER_DAY
        }

        assertThat(r1.memories.first().lastRecalledAt).isEqualTo(r2.memories.first().lastRecalledAt)
        assertThat(r1.memories.first().importance).isEqualTo(r2.memories.first().importance)
        assertThat(r1.activeEmotions.firstOrNull()?.intensity).isEqualTo(r2.activeEmotions.firstOrNull()?.intensity)
    }

    @Test
    fun `childhood influence modifier is a no-op without a matching childhood memory`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        assertThat(
            MemoryRecallSystem.childhoodInfluenceModifier(mara, MemoryRecallSystem.ChildhoodSituation.BUSINESS_FAILURE)
        ).isEqualTo(1.0)
        assertThat(
            MemoryRecallSystem.childhoodInfluenceModifier(mara, MemoryRecallSystem.ChildhoodSituation.FINANCIAL_HARDSHIP)
        ).isEqualTo(1.0)
    }

    @Test
    fun `a significant childhood business-failure memory dampens the modifier`() {
        val state = TestWorld.newState()
        val mara = TestWorld.resident(state, "Mara Vale")
        // Born well before "now" so createdAt lands in childhood.
        addMemory(
            state, mara, MemoryType.LOSS, "Watched the family business fail when I was young.",
            importance = 70.0, createdAt = mara.bornAt + 5 * com.ripple.town.core.model.SimTime.MINUTES_PER_YEAR
        )
        val modifier = MemoryRecallSystem.childhoodInfluenceModifier(mara, MemoryRecallSystem.ChildhoodSituation.BUSINESS_FAILURE)
        assertThat(modifier).isLessThan(1.0)
        assertThat(modifier).isAtLeast(0.9)
    }
}
