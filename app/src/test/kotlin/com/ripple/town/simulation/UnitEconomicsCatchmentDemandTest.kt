package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.Business
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.Tile
import com.ripple.town.core.simulation.EconomySystem
import org.junit.Test

/**
 * Covers the Economy Calibration Gate, Phase 1 work (added 2026-07-11) — real per-sector unit
 * economics (COGS/rent/utilities/tax actually deducted, not just computed) and catchment/
 * preference-based demand. See docs/simulation-rules.md "Unit economics + catchment demand" and
 * the matching docs/backlog.md entry.
 */
class UnitEconomicsCatchmentDemandTest {

    // ============================================================
    // COGS/rent/utilities/tax are real deductions, not just diagnostic numbers
    // ============================================================

    @Test
    fun `hourly footfall deducts COGS from balance and revenue, not just revenue`() {
        // Walk the clock to a guaranteed footfall hour (10am, well inside 8..21) with a fresh
        // deterministic context each attempt, calling the same real entry point
        // `SimulationCoordinator` uses (`EconomySystem.update`) — sweeping salts since the
        // customer draw is a genuine `ctx.rng` roll, same pattern `BusinessHealthStateTest` uses
        // for its own probabilistic actions.
        var sawSale = false
        for (salt in 0L until 200L) {
            val trial = TestWorld.newState()
            val trialBiz = trial.businesses.values.first { it.type == BusinessType.GROCER }
            trialBiz.demand = 95.0 // force a near-certain customer draw this hour
            val balanceBefore = trialBiz.balance
            trial.time = trial.time - (trial.time % com.ripple.town.core.model.SimTime.MINUTES_PER_DAY) +
                10L * 60L // 10:00 on the current day
            val ctx = TestWorld.contextFor(trial, salt = salt)
            EconomySystem.update(ctx)
            if (trialBiz.customersToday > 0) {
                sawSale = true
                val expectedCogs = trialBiz.revenueToday * EconomySystem.cogsFraction(BusinessType.GROCER)
                // Balance grew by revenue-minus-COGS, not by full revenue — COGS is a real
                // deduction at the point of sale, not a diagnostic-only number.
                assertThat(trialBiz.balance).isWithin(0.01).of(balanceBefore + trialBiz.revenueToday - expectedCogs)
                assertThat(trialBiz.expensesToday).isWithin(0.01).of(expectedCogs)
                break
            }
        }
        assertThat(sawSale).isTrue()
    }

    @Test
    fun `a profitable day actually deducts rent, utilities and tax from balance via dailySettlement`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.type == BusinessType.TAILOR }
        val building = state.building(biz.buildingId)!!
        // Force a clean, predictable profitable day: healthy revenue booked, no staff to
        // complicate the wages line beyond what's already there.
        biz.revenueToday = 1_000.0
        biz.expensesToday = 0.0
        biz.balance = 5_000.0
        val balanceBefore = biz.balance

        val ctx = TestWorld.contextFor(state)
        EconomySystem.dailySettlement(ctx)

        val expectedRent = EconomySystem.rentPerDay(building)
        val expectedUtilities = EconomySystem.utilitiesPerDay(building, biz.type)
        // `settleBusinessDay` (called at the end of `dailySettlement`'s per-business block)
        // resets `expensesToday`/`revenueToday`/`customersToday` back to 0 for the next day — a
        // pre-existing behaviour this Phase 1 pass doesn't touch — so the real, durable evidence
        // of a deduction having happened is `balance` itself, and `recentNetDaily` (recorded
        // BEFORE that reset, see `recordNetDaily`'s call site in `dailySettlement`), not
        // `expensesToday` read back after the call returns.
        val bookedRevenue = 1_000.0 // captured before dailySettlement resets biz.revenueToday to 0
        val realDeduction = balanceBefore + bookedRevenue - biz.balance
        assertThat(realDeduction).isAtLeast(expectedRent + expectedUtilities)
        assertThat(biz.balance).isLessThan(balanceBefore + bookedRevenue)
        assertThat(biz.recentNetDaily).isNotEmpty()
        assertThat(biz.recentNetDaily.last()).isLessThan(bookedRevenue) // real costs bit into the raw revenue figure
    }

    @Test
    fun `recordNetDaily populates recentNetDaily so reserveRunway is not just a single noisy day`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.open }
        assertThat(biz.recentNetDaily).isEmpty()

        biz.revenueToday = 500.0
        biz.expensesToday = 100.0
        biz.balance = 1_000.0
        val ctx = TestWorld.contextFor(state)
        EconomySystem.dailySettlement(ctx)

        if (biz.type !in EconomySystem.PUBLIC_SERVICES) {
            assertThat(biz.recentNetDaily).isNotEmpty()
        }
    }

    // ============================================================
    // breakEvenCustomers — sane, positive per sector
    // ============================================================

    @Test
    fun `breakEvenCustomers is a sane positive number for every real sector`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        for (biz in state.businesses.values.filter { it.type !in EconomySystem.PUBLIC_SERVICES }) {
            val be = EconomySystem.breakEvenCustomers(ctx, biz)
            assertThat(be).isAtLeast(0)
            // Not an absurd, unreachable number — well under a theoretical maximum of ~30
            // customers/hour x 14 open hours; this is a loose sanity ceiling, not a tuned target.
            assertThat(be).isLessThan(420)
        }
    }

    @Test
    fun `breakEvenCustomers is zero for PUBLIC_SERVICES sectors`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        for (biz in state.businesses.values.filter { it.type in EconomySystem.PUBLIC_SERVICES }) {
            assertThat(EconomySystem.breakEvenCustomers(ctx, biz)).isEqualTo(0)
        }
    }

    @Test
    fun `a business with a lower price level has a higher breakEvenCustomers than one with a higher price level`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.type == BusinessType.GROCER }
        val ctx = TestWorld.contextFor(state)

        biz.priceLevel = 0.75
        val beCheap = EconomySystem.breakEvenCustomers(ctx, biz)
        biz.priceLevel = 1.35
        val beExpensive = EconomySystem.breakEvenCustomers(ctx, biz)

        assertThat(beCheap).isGreaterThan(beExpensive)
    }

    // ============================================================
    // catchmentDemand — distance, wealth, competition
    // ============================================================

    private fun freshState(): com.ripple.town.core.model.WorldState = TestWorld.newState()

    private fun addHousehold(
        state: com.ripple.town.core.model.WorldState,
        homeId: Long,
        savings: Double,
        memberCount: Int = 2,
        adultAge: Boolean = true
    ): Household {
        val hhId = state.nextHouseholdId++
        val hh = Household(id = hhId, name = "Test HH $hhId", homeBuildingId = homeId, savings = savings)
        state.households[hhId] = hh
        repeat(memberCount) {
            val rId = state.nextResidentId++
            val bornAt = if (adultAge) state.time - 30L * com.ripple.town.core.model.SimTime.MINUTES_PER_YEAR
                else state.time - 5L * com.ripple.town.core.model.SimTime.MINUTES_PER_YEAR
            val resident = Resident(
                id = rId, firstName = "Test", surname = "Resident$rId",
                gender = com.ripple.town.core.model.Gender.FEMALE, bornAt = bornAt,
                homeBuildingId = homeId, householdId = hhId
            )
            resident.currentBuildingId = homeId
            state.residents[rId] = resident
            hh.memberIds += rId
        }
        return hh
    }

    private fun addBusinessAt(
        state: com.ripple.town.core.model.WorldState,
        type: BusinessType,
        buildingId: Long,
        demand: Double = 50.0,
        reputation: Double = 55.0,
        priceLevel: Double = 1.0
    ): Business {
        val bizId = state.nextBusinessId++
        val biz = Business(
            id = bizId, buildingId = buildingId, name = "Test Biz $bizId", type = type,
            demand = demand, reputation = reputation, priceLevel = priceLevel
        )
        state.businesses[bizId] = biz
        return biz
    }

    private fun addBuildingAt(state: com.ripple.town.core.model.WorldState, x: Int, y: Int, type: BuildingType = BuildingType.HOUSE): Building {
        val id = state.nextBuildingId++
        val b = Building(
            id = id, name = "Test Building $id", type = type, origin = Tile(x, y),
            width = 2, height = 2, door = Tile(x, y)
        )
        state.buildings[id] = b
        return b
    }

    @Test
    fun `catchmentDemand gives closer households more weight than farther ones`() {
        val state = freshState()
        val shopBuilding = addBuildingAt(state, x = 0, y = 0, type = BuildingType.GROCER)
        val biz = addBusinessAt(state, BusinessType.GROCER, shopBuilding.id)

        // Scenario A: one household very close.
        val closeHome = addBuildingAt(state, x = 1, y = 1)
        addHousehold(state, closeHome.id, savings = 1_500.0)
        val ctx = TestWorld.contextFor(state)
        val demandClose = EconomySystem.catchmentDemand(ctx, biz)

        // Scenario B: same household, but far away (near the edge of/outside the catchment radius).
        val state2 = freshState()
        val shopBuilding2 = addBuildingAt(state2, x = 0, y = 0, type = BuildingType.GROCER)
        val biz2 = addBusinessAt(state2, BusinessType.GROCER, shopBuilding2.id)
        val farHome = addBuildingAt(state2, x = 38, y = 0)
        addHousehold(state2, farHome.id, savings = 1_500.0)
        val ctx2 = TestWorld.contextFor(state2)
        val demandFar = EconomySystem.catchmentDemand(ctx2, biz2)

        assertThat(demandClose).isGreaterThan(demandFar)
    }

    @Test
    fun `catchmentDemand responds to household wealth for a considered-purchase sector`() {
        val state = freshState()
        val shopBuilding = addBuildingAt(state, x = 0, y = 0, type = BuildingType.HARDWARE)
        val biz = addBusinessAt(state, BusinessType.HARDWARE, shopBuilding.id)
        val home = addBuildingAt(state, x = 2, y = 2)
        addHousehold(state, home.id, savings = 200.0) // poor household
        val ctx = TestWorld.contextFor(state)
        val demandPoor = EconomySystem.catchmentDemand(ctx, biz)

        val state2 = freshState()
        val shopBuilding2 = addBuildingAt(state2, x = 0, y = 0, type = BuildingType.HARDWARE)
        val biz2 = addBusinessAt(state2, BusinessType.HARDWARE, shopBuilding2.id)
        val home2 = addBuildingAt(state2, x = 2, y = 2)
        addHousehold(state2, home2.id, savings = 8_000.0) // rich household
        val ctx2 = TestWorld.contextFor(state2)
        val demandRich = EconomySystem.catchmentDemand(ctx2, biz2)

        assertThat(demandRich).isGreaterThan(demandPoor)
    }

    @Test
    fun `two same-sector businesses in overlapping catchments split a household, neither gets the full amount`() {
        // A fully empty, hand-built state (not TestWorld.newState()) so the only same-sector
        // businesses in play are the two this test explicitly creates — the full town already
        // has its own CAFE (The Willow Café), which would otherwise silently become a third,
        // asymmetric competitor and make this comparison meaningless.
        val state = com.ripple.town.core.model.WorldState(seed = 1L, townName = "Empty Test Town", createdAtRealMs = 0L, map = com.ripple.town.core.model.TownMap(50, 50, List(2500) { com.ripple.town.core.model.TileType.GRASS }))
        val home = addBuildingAt(state, x = 5, y = 5)
        addHousehold(state, home.id, savings = 1_500.0, memberCount = 3)

        val shopA = addBuildingAt(state, x = 4, y = 4, type = BuildingType.CAFE)
        val bizA = addBusinessAt(state, BusinessType.CAFE, shopA.id, demand = 50.0, reputation = 55.0)
        // Solo scenario: only bizA exists.
        val ctxSolo = TestWorld.contextFor(state)
        val demandSolo = EconomySystem.catchmentDemand(ctxSolo, bizA)

        // Now add a second, equally-matched CAFE, placed symmetrically opposite bizA around the
        // household — overlapping catchment, identical distance/standing, for the same household.
        val shopB = addBuildingAt(state, x = 6, y = 6, type = BuildingType.CAFE)
        val bizB = addBusinessAt(state, BusinessType.CAFE, shopB.id, demand = 50.0, reputation = 55.0)
        val ctxCompeting = TestWorld.contextFor(state)
        val demandACompeting = EconomySystem.catchmentDemand(ctxCompeting, bizA)
        val demandBCompeting = EconomySystem.catchmentDemand(ctxCompeting, bizB)

        // Competition should reduce bizA's demand relative to the solo case (some of the same
        // household's custom now goes to bizB), and — since they're evenly matched by distance/
        // standing — bizB should land close to bizA's competing-case value, not near zero (i.e.
        // an even split, not one business quietly claiming the whole thing).
        assertThat(demandACompeting).isLessThan(demandSolo)
        assertThat(demandBCompeting).isWithin(2.0).of(demandACompeting)
    }

    @Test
    fun `WORKSHOP and FACTORY get a flat contract-sector baseline, not a zero-catchment collapse`() {
        val state = freshState()
        val shopBuilding = addBuildingAt(state, x = 0, y = 0, type = BuildingType.WORKSHOP)
        val biz = addBusinessAt(state, BusinessType.WORKSHOP, shopBuilding.id)
        // No households at all in this minimal state beyond whatever TestWorld.newState() seeded —
        // catchmentDemand must not divide by zero or blow up, and must return the documented flat
        // baseline rather than 0.
        val ctx = TestWorld.contextFor(state)
        val demand = EconomySystem.catchmentDemand(ctx, biz)
        assertThat(demand).isEqualTo(EconomySystem.CONTRACT_SECTOR_BASELINE_DEMAND)
    }

    @Test
    fun `catchmentDemand result always stays within the 5 to 95 demand band`() {
        val state = TestWorld.newState()
        val ctx = TestWorld.contextFor(state)
        for (biz in state.businesses.values.filter { it.type !in EconomySystem.PUBLIC_SERVICES }) {
            val d = EconomySystem.catchmentDemand(ctx, biz)
            assertThat(d).isAtLeast(5.0)
            assertThat(d).isAtMost(95.0)
        }
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `catchmentDemand is deterministic for the same state and same tick`() {
        val state = TestWorld.newState()
        val biz = state.businesses.values.first { it.type == BusinessType.GROCER }
        val ctx1 = TestWorld.contextFor(state, salt = 3L)
        val ctx2 = TestWorld.contextFor(state, salt = 3L)
        assertThat(EconomySystem.catchmentDemand(ctx1, biz)).isEqualTo(EconomySystem.catchmentDemand(ctx2, biz))
    }

    @Test
    fun `same seed produces identical dailySettlement cost outcomes`() {
        fun runOnce(): Triple<Double, Double, List<Double>> {
            val state = TestWorld.newState()
            val biz = state.businesses.values.first { it.type == BusinessType.CAFE }
            biz.revenueToday = 300.0
            biz.expensesToday = 0.0
            val ctx = TestWorld.contextFor(state, salt = 11L)
            EconomySystem.dailySettlement(ctx)
            return Triple(biz.balance, biz.expensesToday, biz.recentNetDaily.toList())
        }
        val first = runOnce()
        val second = runOnce()
        assertThat(first).isEqualTo(second)
    }
}
