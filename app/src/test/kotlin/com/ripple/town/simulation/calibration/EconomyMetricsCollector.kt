package com.ripple.town.simulation.calibration

import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.simulation.EconomySystem

/**
 * Pure data-collection: given a [WorldState] at some point in simulated time, extracts a
 * snapshot of resident wealth/debt and business health, plus honestly-computed distribution
 * statistics (real sort+index percentiles, not average-only reporting).
 *
 * Deliberately has zero side effects on the world — this only reads state, never mutates it,
 * so collecting a snapshot can never perturb the very simulation being measured.
 */
object EconomyMetricsCollector {

    data class ResidentSnapshot(
        val id: Long,
        val wealth: Double,
        val debt: Double,
        val employed: Boolean
    )

    data class BusinessSnapshot(
        val id: Long,
        val balance: Double,
        val daysInTrouble: Int,
        val revenueToday: Double,
        val expensesToday: Double,
        val demand: Double,
        val reputation: Double,
        val priceLevel: Double,
        val open: Boolean
    )

    /** Real percentile stats over a numeric sample — sorted + indexed, never faked from mean/stddev. */
    data class Distribution(
        val n: Int,
        val min: Double,
        val p10: Double,
        val p25: Double,
        val median: Double,
        val p75: Double,
        val p90: Double,
        val max: Double,
        val mean: Double
    ) {
        override fun toString(): String =
            "n=$n min=%.1f p10=%.1f p25=%.1f median=%.1f p75=%.1f p90=%.1f max=%.1f mean=%.1f"
                .format(min, p10, p25, median, p75, p90, max, mean)

        companion object {
            /** Nearest-rank percentile on a sorted copy of [values]. Returns an all-zero
             *  distribution for an empty sample rather than throwing — callers may snapshot
             *  a world with zero eligible businesses/residents early on. */
            fun of(values: List<Double>): Distribution {
                if (values.isEmpty()) return Distribution(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
                val sorted = values.sorted()
                fun pct(p: Double): Double {
                    val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
                    return sorted[idx]
                }
                return Distribution(
                    n = sorted.size,
                    min = sorted.first(),
                    p10 = pct(0.10),
                    p25 = pct(0.25),
                    median = pct(0.50),
                    p75 = pct(0.75),
                    p90 = pct(0.90),
                    max = sorted.last(),
                    mean = sorted.average()
                )
            }
        }
    }

    data class Snapshot(
        val dayIndex: Long,
        val residents: List<ResidentSnapshot>,
        val businesses: List<BusinessSnapshot>,
        val closuresSoFar: Int
    ) {
        val wealthDistribution: Distribution get() = Distribution.of(residents.map { it.wealth })
        val debtDistribution: Distribution get() = Distribution.of(residents.filter { it.debt > 0 }.map { it.debt })
        val businessBalanceDistribution: Distribution get() = Distribution.of(businesses.filter { it.open }.map { it.balance })

        val businessCount: Int get() = businesses.size
        val openBusinessCount: Int get() = businesses.count { it.open }
        val pctBusinessesInTrouble: Double get() =
            if (openBusinessCount == 0) 0.0
            else 100.0 * businesses.count { it.open && it.daysInTrouble > 0 } / openBusinessCount
        val pctBusinessesNearClosure: Double get() =
            // "Near closure" = within 3 days of EconomySystem.CLOSURE_DAYS — hovering at the cliff edge.
            if (openBusinessCount == 0) 0.0
            else 100.0 * businesses.count { it.open && it.daysInTrouble >= EconomySystem.CLOSURE_DAYS - 3 } / openBusinessCount
        val pctClosedOfEverOpened: Double get() =
            if (businesses.isEmpty()) 0.0 else 100.0 * closuresSoFar / businesses.size

        val employmentRate: Double get() =
            if (residents.isEmpty()) 0.0 else 100.0 * residents.count { it.employed } / residents.size
        val pctResidentsInDebt: Double get() =
            if (residents.isEmpty()) 0.0 else 100.0 * residents.count { it.debt > 0 } / residents.size
        val pctResidentsInDebtCrisis: Double get() =
            if (residents.isEmpty()) 0.0
            else 100.0 * residents.count { it.debt > EconomySystem.DEBT_CRISIS_THRESHOLD } / residents.size
    }

    /**
     * Captures a snapshot at the current simulated instant. [dayIndex] is caller-supplied (the
     * runner tracks elapsed simulated days) since [WorldState] doesn't expose an absolute day
     * counter beyond raw minutes — kept separate so this class stays a pure state reader.
     *
     * Only DETAILED adult residents are included in resident-level stats — background residents
     * carry no real wealth/debt simulation (see `EconomySystem.dailySettlement`'s own
     * `detailLevel != DETAILED` skip), so including them would silently dilute every percentile
     * with residents the economy never actually simulates.
     *
     * [closuresSoFar] is a running total the runner accumulates from `BUSINESS_CLOSED` events
     * across the whole run (a snapshot alone can't distinguish "never opened" from "opened then
     * closed" without external bookkeeping, since a closed business still has a Business record).
     */
    fun capture(state: WorldState, dayIndex: Long, closuresSoFar: Int): Snapshot {
        val residents = state.residentsOrdered()
            .filter { it.inTown && it.detailLevel == DetailLevel.DETAILED && it.lifeStageAt(state.time) != LifeStage.CHILD }
            .map { r ->
                ResidentSnapshot(
                    id = r.id,
                    wealth = r.wealth,
                    debt = r.debt,
                    employed = r.employmentId != null
                )
            }
        val businesses = state.businesses.values.sortedBy { it.id }
            .filter { it.type !in EconomySystem.PUBLIC_SERVICES }
            .map { b ->
                BusinessSnapshot(
                    id = b.id,
                    balance = b.balance,
                    daysInTrouble = b.daysInTrouble,
                    revenueToday = b.revenueToday,
                    expensesToday = b.expensesToday,
                    demand = b.demand,
                    reputation = b.reputation,
                    priceLevel = b.priceLevel,
                    open = b.open
                )
            }
        return Snapshot(dayIndex, residents, businesses, closuresSoFar)
    }
}
