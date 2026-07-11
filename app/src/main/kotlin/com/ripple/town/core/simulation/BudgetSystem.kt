package com.ripple.town.core.simulation

import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.SimTime

/**
 * The town's municipal finances — runs once per in-game day and does four things:
 *
 * 1. Collects resident tax: a small flat daily contribution scaled by wealth.
 * 2. Collects business rates: open commercial businesses pay a small daily levy.
 * 3. Pays service running costs: each active civic building draws from the balance.
 * 4. Applies debt interest and resets year-counters at the annual boundary.
 *
 * The budget is a soft constraint on [DevelopmentSystem] approvals — projects can
 * still go ahead via municipal borrowing, but debt accumulates interest.
 */
object BudgetSystem {

    /** Daily resident tax per £1 of wealth above subsistence (£1,000). */
    private const val RESIDENT_TAX_RATE = 0.00015
    /** Flat daily contribution from each adult resident in town (minimum council tax). */
    private const val RESIDENT_DAILY_BASE_TAX = 0.25
    /** Daily business rate per £ of balance, for open commercial businesses. */
    private const val BUSINESS_RATE = 0.0008
    /** Daily running cost per civic building (fire, police, school, clinic, etc.). */
    private const val CIVIC_BUILDING_DAILY_COST = 18.0
    /** Annual interest rate on municipal debt. */
    private const val DEBT_INTEREST_RATE = 0.04
    /** Threshold below which a BUDGET_SHORTFALL event is emitted (once per 30-day window). */
    private const val SHORTFALL_THRESHOLD = -10_000.0
    private const val SHORTFALL_COOLDOWN_DAYS = 30L

    private val CIVIC_BUSINESS_TYPES = setOf(
        BusinessType.FIRE_STATION, BusinessType.POLICE_STATION,
        BusinessType.SPORTS_HALL, BusinessType.COMMUNITY_CENTRE,
        BusinessType.SCHOOL, BusinessType.CLINIC, BusinessType.TOWN_HALL
    )

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        val budget = state.municipalBudget

        // Annual reset at the year boundary.
        val annualMinutes = SimTime.MINUTES_PER_DAY * 365L
        if (budget.yearStartedAt == 0L) budget.yearStartedAt = state.time
        if (state.time - budget.yearStartedAt >= annualMinutes) {
            budget.taxRevenueThisYear = 0.0
            budget.businessRateRevenueThisYear = 0.0
            budget.serviceExpensesThisYear = 0.0
            budget.constructionExpensesThisYear = 0.0
            budget.yearStartedAt = state.time
        }

        // 1. Resident tax.
        var taxIncome = 0.0
        for (r in state.livingResidents()) {
            if (!r.inTown) continue
            if (r.lifeStageAt(state.time) == LifeStage.CHILD) continue
            val wealthAboveBase = maxOf(0.0, r.wealth - 1_000.0)
            taxIncome += (RESIDENT_DAILY_BASE_TAX + wealthAboveBase * RESIDENT_TAX_RATE) *
                state.policyModifiers.councilTaxMultiplier
        }
        budget.balance += taxIncome
        budget.taxRevenueThisYear += taxIncome

        // 2. Business rates from commercial (non-civic) businesses.
        var ratesIncome = 0.0
        for (biz in state.businesses.values) {
            if (!biz.open) continue
            if (biz.type in CIVIC_BUSINESS_TYPES) continue
            ratesIncome += biz.balance.coerceAtLeast(0.0) * BUSINESS_RATE *
                state.policyModifiers.businessRatesMultiplier
        }
        budget.balance += ratesIncome
        budget.businessRateRevenueThisYear += ratesIncome

        // 3. Service running costs: each civic building draws a daily maintenance cost.
        var serviceExpenses = 0.0
        for (biz in state.businesses.values) {
            if (biz.type !in CIVIC_BUSINESS_TYPES) continue
            serviceExpenses += CIVIC_BUILDING_DAILY_COST
        }
        budget.balance -= serviceExpenses
        budget.serviceExpensesThisYear += serviceExpenses

        // 4. Debt interest (applied daily at annual rate / 365).
        if (budget.debt > 0.0) {
            val dailyInterest = budget.debt * DEBT_INTEREST_RATE / 365.0
            budget.balance -= dailyInterest
            budget.serviceExpensesThisYear += dailyInterest
        }

        // Emit shortfall event if balance dipped below threshold (with cooldown).
        if (budget.balance < SHORTFALL_THRESHOLD) {
            val cooldown = SimTime.MINUTES_PER_DAY * SHORTFALL_COOLDOWN_DAYS
            val lastShortfall = state.recentEventIds
                .mapNotNull { ctx.eventIndex.get(it) }
                .lastOrNull { it.type == EventType.BUDGET_SHORTFALL }
            if (lastShortfall == null || state.time - lastShortfall.time > cooldown) {
                ctx.emit(
                    EventType.BUDGET_SHORTFALL,
                    "${state.townName}'s municipal budget is in deficit (£${-budget.balance.toInt()} overdrawn).",
                    severity = 0.5, visibility = EventVisibility.PUBLIC
                )
            }
        }
    }
}
