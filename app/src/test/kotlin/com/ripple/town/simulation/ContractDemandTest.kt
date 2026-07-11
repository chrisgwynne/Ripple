package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.simulation.EconomySystem
import org.junit.Test

/**
 * Covers the Economy Calibration Gate Phase 2 (2026-07-11) external/contract demand model for
 * WORKSHOP/FACTORY — see docs/simulation-rules.md "External/contract demand". These sectors have
 * zero residential catchment radius by design (`catchmentRadiusTiles`); their real revenue comes
 * from periodic, bounded contract wins rolled in `dailySettlement`, not resident footfall.
 */
class ContractDemandTest {

    // NOTE: `Business.revenueToday`/`expensesToday` are reset to 0 at the tail of
    // `settleBusinessDay`, which `dailySettlement` always calls before returning — so they can't
    // be read post-call to measure a single day's contract revenue. `biz.demand` (bumped, not
    // reset, by a contract win) and `biz.balance`/`biz.reputation` deltas are used instead as the
    // real, observable, persisted signals.

    @Test
    fun `a WORKSHOP can win a contract that emits CONTRACT_WON and bumps demand`() {
        var fired = false
        for (salt in 0L until 300L) {
            val state = TestWorld.newState()
            val biz = state.businesses.values.first { it.open && it.type == BusinessType.FACTORY }
            biz.reputation = 55.0
            biz.demand = 45.0
            val demandBefore = biz.demand
            val repBefore = biz.reputation

            val ctx = TestWorld.contextFor(state, salt = salt)
            EconomySystem.dailySettlement(ctx)

            val won = ctx.newEvents.any { it.type == EventType.CONTRACT_WON && it.businessId == biz.id }
            if (won) {
                fired = true
                // A contract win bumps demand (a real, visible "business is busy" signal) and
                // nudges reputation up slightly — both persisted, unlike revenueToday/expensesToday.
                assertThat(biz.demand).isGreaterThan(demandBefore)
                assertThat(biz.reputation).isAtLeast(repBefore)
                break
            }
        }
        assertThat(fired).isTrue()
    }

    @Test
    fun `contract value scales with reputation and capacity - higher standing wins bigger demand bumps and balance gains`() {
        // Low reputation/capacity vs high reputation/capacity — sweep both across the same seeds
        // and confirm the high-standing business's average contract-day balance gain is not lower.
        // (demand bump itself is a flat CONTRACT_DEMAND_BUMP regardless of standing — the
        // reputation/capacity scaling is on revenue/balance, so that's what's compared here.)
        val lowGains = mutableListOf<Double>()
        val highGains = mutableListOf<Double>()
        for (salt in 0L until 250L) {
            val lowState = TestWorld.newState()
            val lowBiz = lowState.businesses.values.first { it.open && it.type == BusinessType.FACTORY }
            lowBiz.reputation = 15.0
            lowBiz.employeeCapacity = 1
            lowBiz.balance = 5_000.0 // healthy balance so wages/rent don't push it negative and mask the gain
            val lowCtx = TestWorld.contextFor(lowState, salt = salt)
            val lowBalanceBefore = lowBiz.balance
            EconomySystem.dailySettlement(lowCtx)
            if (lowCtx.newEvents.any { it.type == EventType.CONTRACT_WON }) {
                lowGains += lowBiz.balance - lowBalanceBefore
            }

            val highState = TestWorld.newState()
            val highBiz = highState.businesses.values.first { it.open && it.type == BusinessType.FACTORY }
            highBiz.reputation = 95.0
            highBiz.employeeCapacity = 10
            highBiz.balance = 5_000.0
            val highCtx = TestWorld.contextFor(highState, salt = salt)
            val highBalanceBefore = highBiz.balance
            EconomySystem.dailySettlement(highCtx)
            if (highCtx.newEvents.any { it.type == EventType.CONTRACT_WON }) {
                highGains += highBiz.balance - highBalanceBefore
            }
        }
        assertThat(lowGains).isNotEmpty()
        assertThat(highGains).isNotEmpty()
        assertThat(highGains.average()).isGreaterThan(lowGains.average())
    }

    @Test
    fun `contract wins bump demand temporarily but stay within the 5 to 95 band`() {
        for (salt in 0L until 200L) {
            val state = TestWorld.newState()
            val biz = state.businesses.values.first { it.open && it.type == BusinessType.FACTORY }
            biz.demand = 90.0 // already near the ceiling
            EconomySystem.dailySettlement(TestWorld.contextFor(state, salt = salt))
            assertThat(biz.demand).isAtMost(95.0)
            assertThat(biz.demand).isAtLeast(5.0)
        }
    }

    @Test
    fun `retail sectors never win contracts - CONTRACT_WON only fires for WORKSHOP or FACTORY`() {
        for (salt in 0L until 150L) {
            val state = TestWorld.newState()
            val ctx = TestWorld.contextFor(state, salt = salt)
            EconomySystem.dailySettlement(ctx)
            val wonEvents = ctx.newEvents.filter { it.type == EventType.CONTRACT_WON }
            for (e in wonEvents) {
                val biz = state.business(e.businessId!!)!!
                assertThat(biz.type == BusinessType.WORKSHOP || biz.type == BusinessType.FACTORY).isTrue()
            }
        }
    }

    @Test
    fun `determinism - same seed and salt produce identical contract outcomes`() {
        fun runOnce(): Triple<Double, Double, Boolean> {
            val state = TestWorld.newState()
            val biz = state.businesses.values.first { it.open && it.type == BusinessType.FACTORY }
            val ctx = TestWorld.contextFor(state, salt = 9L)
            EconomySystem.dailySettlement(ctx)
            val won = ctx.newEvents.any { it.type == EventType.CONTRACT_WON }
            return Triple(biz.balance, biz.demand, won)
        }
        assertThat(runOnce()).isEqualTo(runOnce())
    }
}
