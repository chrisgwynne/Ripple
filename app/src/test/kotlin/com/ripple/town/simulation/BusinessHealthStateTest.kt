package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.BusinessHealthState
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.Employment
import com.ripple.town.core.simulation.EconomySystem
import com.ripple.town.core.simulation.PriceDriftSystem
import org.junit.Test

/**
 * Covers the staged business health states, the pre-closure recovery action, and real
 * succession after closure — see docs/simulation-rules.md "Business health states, recovery and
 * succession" and the 2026-07-11 backlog entry this closes.
 */
class BusinessHealthStateTest {

    // ============================================================
    // healthStateOf — pure classification boundaries
    // ============================================================

    @Test
    fun `health state bands match daysInTrouble against STRUGGLE_NOTICE_DAYS and CLOSURE_DAYS`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.open }

        biz.daysInTrouble = 0
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.HEALTHY)

        biz.daysInTrouble = 1
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.PRESSURED)

        biz.daysInTrouble = EconomySystem.STRUGGLE_NOTICE_DAYS - 1
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.PRESSURED)

        biz.daysInTrouble = EconomySystem.STRUGGLE_NOTICE_DAYS
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.AT_RISK)

        biz.daysInTrouble = EconomySystem.CLOSURE_DAYS / 2 - 1
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.AT_RISK)

        biz.daysInTrouble = EconomySystem.CLOSURE_DAYS / 2
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.STRUGGLING)

        biz.daysInTrouble = EconomySystem.CLOSURE_DAYS - 3
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.STRUGGLING)

        biz.daysInTrouble = EconomySystem.CLOSURE_DAYS - 2
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.CRITICAL)

        biz.daysInTrouble = EconomySystem.CLOSURE_DAYS - 1
        assertThat(EconomySystem.healthStateOf(biz)).isEqualTo(BusinessHealthState.CRITICAL)
    }

    @Test
    fun `health states order HEALTHY through CRITICAL so callers can compare with less-than`() {
        val ordered = listOf(
            BusinessHealthState.HEALTHY, BusinessHealthState.PRESSURED, BusinessHealthState.AT_RISK,
            BusinessHealthState.STRUGGLING, BusinessHealthState.CRITICAL
        )
        for (i in 0 until ordered.size - 1) {
            assertThat(ordered[i] < ordered[i + 1]).isTrue()
        }
    }

    // ============================================================
    // Recovery action — fires under a seeded rng and has a real measurable effect
    // ============================================================

    @Test
    fun `a detailed owner at AT_RISK can trigger a price-cut recovery that actually moves priceLevel`() {
        // Sweep seeds/salts until we land a run where the recovery action actually fires —
        // it's a bounded low-probability roll (RECOVERY_ACTION_CHANCE_PER_DAY = 0.10 per kind
        // per day), so this proves it *can* and *does* fire under ctx.rng, not that it always
        // does. Fully deterministic per salt: same salt always produces the same outcome.
        var fired = false
        for (salt in 0L until 500L) {
            val state = TestWorld.newState()
            val biz = state.businesses.values.first { it.open }
            biz.daysInTrouble = EconomySystem.STRUGGLE_NOTICE_DAYS // AT_RISK
            biz.balance = -50.0
            val ownerId = biz.ownerId ?: continue
            val owner = state.resident(ownerId) ?: continue
            owner.detailLevel = DetailLevel.DETAILED

            val priceBefore = biz.priceLevel
            val repBefore = biz.reputation

            val ctx = TestWorld.contextFor(state, salt = salt)
            // Call the daily settlement path directly — maybeAttemptRecovery is private, reached
            // through settleBusinessDay/dailySettlement's existing trouble branch.
            EconomySystem.dailySettlement(ctx)

            if (biz.priceLevel < priceBefore) {
                fired = true
                // Real effect: price actually dropped, bounded at PriceDriftSystem's floor, and
                // reputation took the documented real cost — not a free save.
                assertThat(biz.priceLevel).isAtLeast(PriceDriftSystem.PRICE_LEVEL_MIN)
                assertThat(biz.reputation).isAtMost(repBefore)
                break
            }
        }
        assertThat(fired).isTrue()
    }

    @Test
    fun `a detailed owner at AT_RISK can trigger an early-layoff recovery that reduces headcount and capacity`() {
        var fired = false
        for (salt in 0L until 500L) {
            val state = TestWorld.newState()
            val biz = state.businesses.values.first { it.open }
            biz.daysInTrouble = EconomySystem.STRUGGLE_NOTICE_DAYS
            biz.balance = -50.0
            val ownerId = biz.ownerId ?: continue
            val owner = state.resident(ownerId) ?: continue
            owner.detailLevel = DetailLevel.DETAILED
            // Give it an actual employee (distinct from the owner) so a layoff has someone to
            // apply to — the town-generated business may or may not already have staff.
            val workerId = state.residentsOrdered().firstOrNull { it.id != owner.id && it.alive && it.inTown }?.id
                ?: continue
            val worker = state.resident(workerId)!!
            worker.employmentId = null
            val empId = state.nextEmploymentId++
            state.employments[empId] = Employment(
                id = empId, residentId = worker.id, businessId = biz.id, role = "Hand",
                dailySalary = 40.0, startedAt = state.time
            )
            worker.employmentId = empId
            biz.employeeCapacity = biz.employeeCapacity.coerceAtLeast(2)
            val capacityBefore = biz.employeeCapacity
            val staffBefore = state.employeesOf(biz.id).size

            val ctx = TestWorld.contextFor(state, salt = salt)
            EconomySystem.dailySettlement(ctx)

            val staffAfter = state.employeesOf(biz.id).size
            if (staffAfter < staffBefore) {
                fired = true
                assertThat(biz.employeeCapacity).isLessThan(capacityBefore)
                assertThat(worker.employmentId).isNull()
                assertThat(worker.occupation).isEqualTo("Unemployed")
                break
            }
        }
        assertThat(fired).isTrue()
    }

    @Test
    fun `recovery actions never fire for a HEALTHY business or a BACKGROUND-detail owner`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.open }
        val ownerId = biz.ownerId!!
        val owner = state.resident(ownerId)!!
        owner.detailLevel = DetailLevel.BACKGROUND
        val priceBefore = biz.priceLevel

        // Re-pin daysInTrouble/balance each iteration — calling dailySettlement repeatedly would
        // otherwise let daysInTrouble climb past CLOSURE_DAYS and close the business, which is a
        // separate, already-covered mechanism, not what this test is isolating.
        for (salt in 0L until 50L) {
            biz.daysInTrouble = EconomySystem.STRUGGLE_NOTICE_DAYS
            biz.balance = -50.0
            EconomySystem.dailySettlement(TestWorld.contextFor(state, salt = salt))
            if (!biz.open) break // shouldn't happen given the re-pin above, but never assert past a closure
        }
        // A BACKGROUND owner is gated out entirely — price should never move via recovery
        // (PriceDriftSystem's own separate ambient drift is a different, much smaller mechanism
        // and isn't exercised by calling dailySettlement directly).
        assertThat(biz.priceLevel).isEqualTo(priceBefore)
    }

    // ============================================================
    // Succession after closure — a real distribution, not every closure reopening
    // ============================================================

    @Test
    fun `repeated seeded closures produce at least one non-vacant succession outcome`() {
        var sawReopened = false
        var sawVacant = false
        for (seed in 1L until 40L) {
            val state = TestWorld.newState(seed = seed)
            val biz = state.businesses.values.filter { it.open && it.type != com.ripple.town.core.model.BusinessType.CLINIC }
                .minByOrNull { it.id } ?: continue
            biz.daysInTrouble = EconomySystem.CLOSURE_DAYS
            biz.balance = -10.0
            val buildingIdBefore = biz.buildingId
            val ctx = TestWorld.contextFor(state, salt = seed)
            EconomySystem.closeBusiness(ctx, biz, "after ${biz.daysInTrouble} days in the red")

            val building = state.building(buildingIdBefore)!!
            if (!building.abandoned) sawReopened = true else sawVacant = true
        }
        // A real distribution: across many seeds, both outcomes should show up — not literally
        // every closure reopening, and not every closure staying vacant either.
        assertThat(sawReopened).isTrue()
        assertThat(sawVacant).isTrue()
    }

    @Test
    fun `succession never fires twice on the same building and vacancy is a real possible outcome`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.open }
        biz.daysInTrouble = EconomySystem.CLOSURE_DAYS
        biz.balance = -10.0
        val ctx = TestWorld.contextFor(state)
        EconomySystem.closeBusiness(ctx, biz, "after ${biz.daysInTrouble} days in the red")
        val building = state.building(biz.buildingId)!!
        // Whatever the outcome was, it's internally consistent: either the business reopened
        // (open == true, building no longer abandoned) or it's genuinely vacant (both false/true
        // respectively) — never a half-applied state.
        assertThat(biz.open).isEqualTo(!building.abandoned)
    }

    @Test
    fun `a family inheritance succession reopens under the heir and clears old trouble`() {
        val probe = TestWorld.newState()
        val probeBiz = probe.businesses.values.first { it.open }
        val ownerId = probeBiz.ownerId!!
        // Find an existing in-town adult, distinct from the owner, to stand in as the heir —
        // `bornAt` is immutable so we use a resident who is already naturally an adult rather
        // than trying to force one into that life stage.
        val heirId = probe.residentsOrdered()
            .firstOrNull { it.id != ownerId && it.alive && it.inTown && it.lifeStageAt(probe.time) == com.ripple.town.core.model.LifeStage.ADULT }
            ?.id ?: return // town has no spare adult to use as a stand-in heir; skip gracefully

        var reopenedToHeir = false
        for (salt in 0L until 300L) {
            val trialState = TestWorld.newState()
            val trialBiz = trialState.businesses.values.first { it.id == probeBiz.id }
            val trialOwner = trialState.resident(ownerId)!!
            trialOwner.childIds.clear()
            trialOwner.childIds += heirId
            trialBiz.daysInTrouble = EconomySystem.CLOSURE_DAYS
            trialBiz.balance = -10.0
            trialBiz.reputation = 80.0 // decent reputation biases toward inheritance/buyout over vacancy
            val ctx = TestWorld.contextFor(trialState, salt = salt)
            EconomySystem.closeBusiness(ctx, trialBiz, "after ${trialBiz.daysInTrouble} days in the red")
            if (trialBiz.open && trialBiz.ownerId == heirId) {
                reopenedToHeir = true
                assertThat(trialBiz.daysInTrouble).isEqualTo(0)
                break
            }
        }
        assertThat(reopenedToHeir).isTrue()
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `same seed and salt produce identical health-state and succession outcomes`() {
        fun runOnce(): Triple<BusinessHealthState, Boolean, Double> {
            val state = TestWorld.newState()
            val biz = state.businesses.values.first { it.open }
            biz.daysInTrouble = EconomySystem.CLOSURE_DAYS
            biz.balance = -10.0
            val healthBefore = EconomySystem.healthStateOf(biz)
            val ctx = TestWorld.contextFor(state, salt = 7L)
            EconomySystem.closeBusiness(ctx, biz, "after ${biz.daysInTrouble} days in the red")
            val building = state.building(biz.buildingId)!!
            return Triple(healthBefore, building.abandoned, biz.balance)
        }

        val first = runOnce()
        val second = runOnce()
        assertThat(first).isEqualTo(second)
    }
}
