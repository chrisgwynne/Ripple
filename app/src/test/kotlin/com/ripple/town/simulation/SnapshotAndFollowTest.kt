package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.data.SnapshotBuilder
import org.junit.Test

class SnapshotAndFollowTest {

    @Test
    fun `snapshot exposes the followed resident and town vitals`() {
        val state = TestWorld.newState()
        val ui = SnapshotBuilder.build(state)
        assertThat(ui.followedResidentId).isEqualTo(state.followedResidentId)
        assertThat(ui.population).isEqualTo(state.population())
        assertThat(ui.townName).isEqualTo("Testholme")
        val mara = ui.resident(ui.followedResidentId)!!
        assertThat(mara.name).isEqualTo("Mara Vale")
        assertThat(mara.visibleOnMap).isTrue()
    }

    @Test
    fun `switching the followed resident works and survives snapshots`() {
        val state = TestWorld.newState()
        val noa = TestWorld.resident(state, "Noa Fenwick")
        state.followedResidentId = noa.id
        state.discoveredResidentIds += noa.id
        val ui = SnapshotBuilder.build(state)
        assertThat(ui.resident(ui.followedResidentId)!!.name).isEqualTo("Noa Fenwick")
    }

    @Test
    fun `dead or departed residents are not drawn on the map`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val arthur = TestWorld.resident(state, "Arthur Pemberton")
        com.ripple.town.core.simulation.LifecycleSystem.die(ctx, arthur, "test")
        val kit = TestWorld.resident(state, "Kit Hartley")
        kit.leftTownAt = state.time
        val ui = SnapshotBuilder.build(state)
        assertThat(ui.resident(arthur.id)!!.visibleOnMap).isFalse()
        assertThat(ui.resident(kit.id)!!.visibleOnMap).isFalse()
    }

    @Test
    fun `travelling residents move along the path between buildings`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val mara = TestWorld.resident(state, "Mara Vale")
        val pub = state.buildings.values.first { it.name == "The Old Lantern" }
        ctx.sendTo(mara, pub.id, com.ripple.town.core.model.Activity.SOCIALISING, 60, "test")
        assertThat(mara.activity).isEqualTo(com.ripple.town.core.model.Activity.TRAVELLING)
        val uiStart = SnapshotBuilder.build(state)
        val posStart = uiStart.resident(mara.id)!!.let { it.x to it.y }
        // Halfway through the journey the position must have changed.
        state.time = (mara.travelStartedAt + mara.travelArrivesAt) / 2
        val uiMid = SnapshotBuilder.build(state)
        val posMid = uiMid.resident(mara.id)!!.let { it.x to it.y }
        assertThat(posMid).isNotEqualTo(posStart)
    }

    @Test
    fun `tick performance stays within budget`() {
        val coordinator = TestWorld.newCoordinator()
        // Warm-up
        repeat(200) { coordinator.tick() }
        val start = System.nanoTime()
        val ticks = 1_000
        repeat(ticks) { coordinator.tick() }
        val perTickMicros = (System.nanoTime() - start) / 1_000.0 / ticks
        // A tick must stay far under one frame; allow generous CI headroom.
        assertThat(perTickMicros).isLessThan(16_000.0)
    }
}
