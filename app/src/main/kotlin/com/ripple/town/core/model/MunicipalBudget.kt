package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * The town's municipal finances — income from resident taxes and business rates,
 * expenses from running public services and funding [DevelopmentProject]s.
 * Updated daily by [com.ripple.town.core.simulation.BudgetSystem].
 *
 * Starting balance of £50,000 represents the town's existing reserves at game start.
 * All values are cumulative within the current financial year (reset each in-game year).
 */
@Serializable
data class MunicipalBudget(
    /** Available unreserved funds. May go negative (deficit spending). */
    var balance: Double = 50_000.0,
    var taxRevenueThisYear: Double = 0.0,
    var businessRateRevenueThisYear: Double = 0.0,
    var serviceExpensesThisYear: Double = 0.0,
    var constructionExpensesThisYear: Double = 0.0,
    /** Outstanding municipal loan taken to fund approved projects when cash-poor. */
    var debt: Double = 0.0,
    /** Sim-minute timestamp when the current financial year started. */
    var yearStartedAt: Long = 0L
)
