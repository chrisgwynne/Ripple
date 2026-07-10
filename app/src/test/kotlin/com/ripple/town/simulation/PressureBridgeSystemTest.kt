package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EmotionType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Weather
import com.ripple.town.core.simulation.DelayedEffectSystem
import com.ripple.town.core.simulation.EconomySystem
import com.ripple.town.core.simulation.PressureBridgeSystem
import org.junit.Test

/**
 * Covers the cross-system pressure bridges wiring crime, weather and economy into each other's
 * consequences — see `docs/simulation-rules.md` "Cross-system pressure bridges" and the
 * Simulation Reality Review finding this file addresses.
 */
class PressureBridgeSystemTest {

    // ============================================================
    // Bridge 1 — crime near a business dents its demand, temporarily
    // ============================================================

    @Test
    fun `a crime at a business schedules a bounded temporary demand dip`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val biz = state.businesses.values.first { it.open }
        val demandBefore = biz.demand

        val crime = ctx.emit(
            EventType.SHOPLIFTING,
            "Something has gone missing.",
            businessId = biz.id, severity = 0.3, visibility = EventVisibility.HIDDEN
        )
        PressureBridgeSystem.onCrimeNearBusiness(ctx, crime)

        // Two DEMAND_SHIFT effects scheduled against this business: a dip, then a later recovery.
        val scheduled = state.delayedEffects.filter {
            it.type == DelayedEffectType.DEMAND_SHIFT && it.targetBusinessId == biz.id
        }
        assertThat(scheduled).hasSize(2)
        val dip = scheduled.first { it.strength < 0 }
        val recovery = scheduled.first { it.strength > 0 }
        assertThat(dip.earliestAt).isLessThan(recovery.earliestAt)
        // Bounded: the recovery leg fully cancels the dip leg's magnitude.
        assertThat(recovery.strength).isEqualTo(-dip.strength)
        assertThat(demandBefore).isEqualTo(biz.demand) // nothing applied synchronously yet
    }

    @Test
    fun `the demand dip actually lands and later recovers`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.open }
        biz.demand = 60.0
        val ctx = TestWorld.contextFor(state)
        val crime = ctx.emit(
            EventType.SHOPLIFTING, "Theft.", businessId = biz.id,
            severity = 0.3, visibility = EventVisibility.HIDDEN
        )
        PressureBridgeSystem.onCrimeNearBusiness(ctx, crime)
        val demandAfterCrime = biz.demand

        // Run the dip's window until it fires.
        var salt = 0L
        val dipEffect = state.delayedEffects.first { it.type == DelayedEffectType.DEMAND_SHIFT && it.strength < 0 }
        while (!dipEffect.applied && !dipEffect.cancelled && salt < 2000) {
            DelayedEffectSystem.update(TestWorld.contextFor(state, salt = salt))
            salt++
        }
        assertThat(dipEffect.applied).isTrue()
        assertThat(biz.demand).isLessThan(demandAfterCrime)
        val dippedDemand = biz.demand

        // Fast-forward the clock past the recovery window and run it too.
        state.time += 25 * SimTime.MINUTES_PER_DAY
        val recoveryEffect = state.delayedEffects.first { it.type == DelayedEffectType.DEMAND_SHIFT && it.strength > 0 }
        var salt2 = 0L
        while (!recoveryEffect.applied && !recoveryEffect.cancelled && salt2 < 2000) {
            DelayedEffectSystem.update(TestWorld.contextFor(state, salt = salt2))
            salt2++
        }
        assertThat(recoveryEffect.applied).isTrue()
        assertThat(biz.demand).isGreaterThan(dippedDemand)
    }

    @Test
    fun `a crime near a business but not at it still hits the nearest open business`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val biz = state.businesses.values.first { it.open }
        val building = state.building(biz.buildingId)!!

        // A burglary-shaped event with no businessId, but a buildingId right on top of the shop.
        val crime = ctx.emit(
            EventType.BURGLARY, "A home was broken into.",
            buildingId = building.id, severity = 0.55, visibility = EventVisibility.HIDDEN
        )
        PressureBridgeSystem.onCrimeNearBusiness(ctx, crime)

        val scheduled = state.delayedEffects.filter {
            it.type == DelayedEffectType.DEMAND_SHIFT && it.targetBusinessId == biz.id
        }
        assertThat(scheduled).isNotEmpty()
    }

    // ============================================================
    // Bridge 2 — flood/weather damage disrupts a housed business
    // ============================================================

    @Test
    fun `weather damage to a building housing a business schedules a demand dip and price bump`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val biz = state.businesses.values.first { it.open }
        val priceBefore = biz.priceLevel

        val damage = ctx.emit(
            EventType.WEATHER_DAMAGE, "The river burst its banks.",
            buildingId = biz.buildingId, severity = 0.65
        )
        PressureBridgeSystem.onBuildingWeatherDamaged(ctx, damage)

        val scheduled = state.delayedEffects.filter {
            it.type == DelayedEffectType.DEMAND_SHIFT && it.targetBusinessId == biz.id
        }
        assertThat(scheduled).hasSize(2)
        // Price bump applied immediately, bounded.
        assertThat(biz.priceLevel).isGreaterThan(priceBefore)
        assertThat(biz.priceLevel).isAtMost(PressureBridgeSystem.MAX_PRICE_LEVEL)
        assertThat(state.pendingPriceEasing).containsKey(biz.id)
    }

    @Test
    fun `a flood price bump is eased back after its window closes`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.open }
        val ctx = TestWorld.contextFor(state)
        val priceBefore = biz.priceLevel
        val damage = ctx.emit(
            EventType.WEATHER_DAMAGE, "Flood.", buildingId = biz.buildingId, severity = 0.65
        )
        PressureBridgeSystem.onBuildingWeatherDamaged(ctx, damage)
        assertThat(biz.priceLevel).isGreaterThan(priceBefore)

        // Fast-forward well past the longest possible flood recovery window.
        state.time += 25 * SimTime.MINUTES_PER_DAY
        PressureBridgeSystem.updateDaily(TestWorld.contextFor(state))

        assertThat(state.pendingPriceEasing).doesNotContainKey(biz.id)
        assertThat(biz.priceLevel).isWithin(0.001).of(priceBefore)
    }

    @Test
    fun `a building with no business housed is a no-op`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val home = state.homes().first()
        val before = state.delayedEffects.size
        val damage = ctx.emit(EventType.WEATHER_DAMAGE, "Storm.", buildingId = home.id, severity = 0.4)
        PressureBridgeSystem.onBuildingWeatherDamaged(ctx, damage)
        assertThat(state.delayedEffects.size).isEqualTo(before)
    }

    // ============================================================
    // Bridge 3 — prolonged poor weather affects mood; severe weather leaves real fear
    // ============================================================

    @Test
    fun `a short spell of poor weather does not yet trigger the weariness nudge`() {
        val state = TestWorld.newState()
        val resident = TestWorld.resident(state, "Ash Thistle")
        resident.detailLevel = DetailLevel.DETAILED
        resident.currentBuildingId = null // exposed
        state.weather = Weather.RAIN

        repeat(PressureBridgeSystem.POOR_WEATHER_THRESHOLD_DAYS - 1) {
            PressureBridgeSystem.updateDaily(TestWorld.contextFor(state, salt = it.toLong()))
        }
        assertThat(state.consecutivePoorWeatherDays).isEqualTo(PressureBridgeSystem.POOR_WEATHER_THRESHOLD_DAYS - 1)
        assertThat(resident.activeEmotions.any { it.type == EmotionType.ANXIETY }).isFalse()
    }

    @Test
    fun `crossing the poor weather threshold spawns a bounded anxiety nudge for exposed residents`() {
        val state = TestWorld.newState()
        val resident = TestWorld.resident(state, "Ash Thistle")
        resident.detailLevel = DetailLevel.DETAILED
        resident.currentBuildingId = null // exposed, not sheltered
        state.weather = Weather.STORM
        val comfortBefore = resident.needs.comfort

        repeat(PressureBridgeSystem.POOR_WEATHER_THRESHOLD_DAYS) {
            PressureBridgeSystem.updateDaily(TestWorld.contextFor(state, salt = it.toLong()))
        }

        assertThat(state.consecutivePoorWeatherDays).isEqualTo(PressureBridgeSystem.POOR_WEATHER_THRESHOLD_DAYS)
        val anxiety = resident.activeEmotions.firstOrNull { it.type == EmotionType.ANXIETY }
        assertThat(anxiety).isNotNull()
        assertThat(anxiety!!.intensity).isAtMost(100.0)
        assertThat(resident.needs.comfort).isLessThan(comfortBefore)
    }

    @Test
    fun `a sheltered resident is untouched by the weather weariness nudge`() {
        val state = TestWorld.newState()
        val resident = TestWorld.resident(state, "Ash Thistle")
        resident.detailLevel = DetailLevel.DETAILED
        resident.currentBuildingId = resident.homeBuildingId // indoors
        state.weather = Weather.STORM

        repeat(PressureBridgeSystem.POOR_WEATHER_THRESHOLD_DAYS) {
            PressureBridgeSystem.updateDaily(TestWorld.contextFor(state, salt = it.toLong()))
        }
        assertThat(resident.activeEmotions.any { it.type == EmotionType.ANXIETY }).isFalse()
    }

    @Test
    fun `clear weather resets the consecutive poor weather streak`() {
        val state = TestWorld.newState()
        state.weather = Weather.RAIN
        repeat(3) { PressureBridgeSystem.updateDaily(TestWorld.contextFor(state, salt = it.toLong())) }
        assertThat(state.consecutivePoorWeatherDays).isEqualTo(3)
        state.weather = Weather.CLEAR
        PressureBridgeSystem.updateDaily(TestWorld.contextFor(state))
        assertThat(state.consecutivePoorWeatherDays).isEqualTo(0)
    }

    @Test
    fun `severe weather damage near a resident leaves a real fear memory and emotion`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        val resident = TestWorld.resident(state, "Ash Thistle")
        resident.detailLevel = DetailLevel.DETAILED
        val home = state.building(resident.homeBuildingId!!)!!
        resident.currentBuildingId = home.id
        val memoriesBefore = resident.memories.size

        val damage = ctx.emit(EventType.WEATHER_DAMAGE, "Flood tore through.", buildingId = home.id, severity = 0.65)
        PressureBridgeSystem.onSevereWeatherNearResidents(ctx, damage, "The flood tore through while we were still inside.")

        assertThat(resident.memories.size).isGreaterThan(memoriesBefore)
        assertThat(resident.memories.last().type).isEqualTo(MemoryType.FEAR)
        val fear = resident.activeEmotions.firstOrNull { it.type == EmotionType.FEAR }
        assertThat(fear).isNotNull()
        assertThat(fear!!.intensity).isAtMost(100.0)
    }

    // ============================================================
    // Bridge 4 — sustained debt crisis strains the partner relationship
    // ============================================================

    @Test
    fun `sustained debt crisis nudges resentment and dependency on the partner relationship`() {
        val state = TestWorld.newState()
        val jonas = TestWorld.resident(state, "Jonas Marsh")
        val petra = TestWorld.resident(state, "Petra Marsh")
        jonas.debt = EconomySystem.DEBT_CRISIS_THRESHOLD + 500.0
        jonas.awareness += "debt_crisis"
        jonas.partnerId = petra.id
        petra.partnerId = jonas.id
        val rel = state.relationshipOrCreate(jonas.id, petra.id)
        rel.kind = RelationshipKind.SPOUSE
        val resentmentBefore = rel.resentment
        val dependencyBefore = rel.dependency
        val affectionBefore = rel.affection

        PressureBridgeSystem.onSustainedFinancialTrouble(TestWorld.contextFor(state))

        assertThat(rel.resentment).isGreaterThan(resentmentBefore)
        assertThat(rel.dependency).isGreaterThan(dependencyBefore)
        assertThat(rel.affection).isLessThan(affectionBefore)
    }

    @Test
    fun `the partner nudge is cooled down so it never compounds daily`() {
        val state = TestWorld.newState()
        val jonas = TestWorld.resident(state, "Jonas Marsh")
        val petra = TestWorld.resident(state, "Petra Marsh")
        jonas.debt = EconomySystem.DEBT_CRISIS_THRESHOLD + 500.0
        jonas.awareness += "debt_crisis"
        jonas.partnerId = petra.id
        petra.partnerId = jonas.id
        val rel = state.relationshipOrCreate(jonas.id, petra.id)
        rel.kind = RelationshipKind.SPOUSE

        PressureBridgeSystem.onSustainedFinancialTrouble(TestWorld.contextFor(state))
        val resentmentAfterFirst = rel.resentment
        // Same day, called again — should be cooled down, no further change.
        PressureBridgeSystem.onSustainedFinancialTrouble(TestWorld.contextFor(state))
        assertThat(rel.resentment).isEqualTo(resentmentAfterFirst)
    }

    @Test
    fun `no partner means no nudge and no crash`() {
        val state = TestWorld.newState()
        val jonas = TestWorld.resident(state, "Ash Thistle")
        jonas.debt = EconomySystem.DEBT_CRISIS_THRESHOLD + 500.0
        jonas.awareness += "debt_crisis"
        jonas.partnerId = null
        // Should simply skip this resident, no exception.
        PressureBridgeSystem.onSustainedFinancialTrouble(TestWorld.contextFor(state))
    }

    @Test
    fun `debt below the crisis threshold never nudges the relationship`() {
        val state = TestWorld.newState()
        val jonas = TestWorld.resident(state, "Jonas Marsh")
        val petra = TestWorld.resident(state, "Petra Marsh")
        jonas.debt = 50.0 // nowhere near DEBT_CRISIS_THRESHOLD
        jonas.partnerId = petra.id
        petra.partnerId = jonas.id
        val rel = state.relationshipOrCreate(jonas.id, petra.id)
        rel.kind = RelationshipKind.SPOUSE
        val resentmentBefore = rel.resentment

        PressureBridgeSystem.onSustainedFinancialTrouble(TestWorld.contextFor(state))
        assertThat(rel.resentment).isEqualTo(resentmentBefore)
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `bridges evolve identically for the same seed`() {
        val a = TestWorld.newCoordinator(seed = 777L)
        val b = TestWorld.newCoordinator(seed = 777L)
        // Push both towards stormy weather territory by running enough days for the bridges'
        // daily hooks (weather streak, financial-trouble scan) to exercise repeatedly.
        val eventsA = TestWorld.runDays(a, 10)
        val eventsB = TestWorld.runDays(b, 10)
        assertThat(eventsA.map { it.description }).isEqualTo(eventsB.map { it.description })
        assertThat(a.state.consecutivePoorWeatherDays).isEqualTo(b.state.consecutivePoorWeatherDays)
        assertThat(a.state.pendingPriceEasing.keys).isEqualTo(b.state.pendingPriceEasing.keys)
    }
}
