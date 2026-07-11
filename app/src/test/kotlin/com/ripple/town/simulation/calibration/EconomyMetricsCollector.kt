package com.ripple.town.simulation.calibration

import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.WorldEvent
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

    /**
     * A single economy-relevant event captured verbatim from a tick's `TickResult.events`
     * (Economy Calibration Gate Phase 3, added 2026-07-11) — the runner accumulates these
     * across a whole seed run so this collector can report real per-sector recoveries/
     * expansions/closures/failure-reasons instead of only pooled totals. [businessType] is
     * resolved by the runner at the moment the event fires (from the live `Business` record,
     * since a closed business's `type` field is still readable — `Business.type` is a `val`
     * that's never mutated by closure, only `open`/`closedAt` change) so it survives even past
     * the point where a business might later be removed from `state.businesses` (see
     * `attemptRestructureOrRelocate`'s recovery-ladder businessId churn — restructuring events
     * carry the OLD business's type at the moment they fired, which is what actually happened).
     */
    data class EconomyEvent(
        val dayIndex: Long,
        val type: EventType,
        val businessId: Long?,
        val businessType: BusinessType?,
        val payload: Map<String, String>
    )

    /** Extracts the subset of a tick's raw events this collector cares about, resolving each
     *  event's business sector at the moment it fires. Called by the runner once per tick so
     *  short-lived businesses (e.g. one that closes and is immediately replaced) are still
     *  correctly attributed. */
    fun extractEconomyEvents(events: List<WorldEvent>, state: WorldState, dayIndex: Long): List<EconomyEvent> =
        events.filter { it.type in TRACKED_EVENT_TYPES }.map { e ->
            val type = e.businessId?.let { state.businesses[it]?.type }
            EconomyEvent(dayIndex, e.type, e.businessId, type, e.payload)
        }

    private val TRACKED_EVENT_TYPES = setOf(
        EventType.BUSINESS_CLOSED, EventType.BUSINESS_RECOVERED, EventType.BUSINESS_EXPANDED,
        EventType.BUSINESS_OPENED, EventType.CONTRACT_WON
    )

    data class ResidentSnapshot(
        val id: Long,
        val wealth: Double,
        val debt: Double,
        val employed: Boolean
    )

    data class BusinessSnapshot(
        val id: Long,
        val type: BusinessType,
        val balance: Double,
        val daysInTrouble: Int,
        val revenueToday: Double,
        val expensesToday: Double,
        val demand: Double,
        val reputation: Double,
        val priceLevel: Double,
        val open: Boolean,
        /** `Business.employeeCapacity`/live staff count at capture (Phase 3, 2026-07-11) — backs
         *  the brief's "staffing" per-sector column and the "businesses starting overstaffed"
         *  flag check (a fresh `GoalSystem.openBusiness` starts at capacity=1; the 7 hand-authored
         *  `WorldGenerator` shops deliberately start at 2 — see docs/simulation-rules.md "Staffing
         *  ramp"). */
        val employeeCapacity: Int,
        val staffCount: Int,
        /** Economy Calibration Gate Phase 3 (2026-07-11) — `Business.openedAt`/`closedAt` are
         *  real, already-tracked fields (see `Business` model), surfaced here so per-sector
         *  lifespan and age-at-closure/age-bucketed closure-rate reporting can use the genuine
         *  in-game-minute values rather than approximating from snapshot deltas. */
        val openedAt: Long,
        val closedAt: Long?,
        /** `EconomySystem.reserveRunway(biz)` at the moment of capture — real trailing-window
         *  runway, not re-derived from a single day's balance. Phase 3 (2026-07-11). */
        val reserveRunwayDays: Double,
        /** Most recent entry of `Business.recentNetDaily` (real revenue-after-COGS minus rent/
         *  utilities/tax/wages, what `dailySettlement` actually applied to `balance`) — NOT
         *  `revenueToday`/`expensesToday`, which `settleBusinessDay` zeroes out at the end of
         *  every day, so at a between-day snapshot instant they'd almost always read 0.0 and
         *  silently misreport profit as zero. Phase 3 (2026-07-11). Empty history (day-0 capture,
         *  before any settlement has run) reads as `null`. */
        val lastNetDaily: Double?
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
                    type = b.type,
                    balance = b.balance,
                    daysInTrouble = b.daysInTrouble,
                    revenueToday = b.revenueToday,
                    expensesToday = b.expensesToday,
                    demand = b.demand,
                    reputation = b.reputation,
                    priceLevel = b.priceLevel,
                    open = b.open,
                    employeeCapacity = b.employeeCapacity,
                    staffCount = state.employeesOf(b.id).size,
                    openedAt = b.openedAt,
                    closedAt = b.closedAt,
                    reserveRunwayDays = EconomySystem.reserveRunway(b),
                    lastNetDaily = b.recentNetDaily.lastOrNull()
                )
            }
        return Snapshot(dayIndex, residents, businesses, closuresSoFar)
    }

    // ============================================================
    // Per-sector aggregation (Economy Calibration Gate, Phase 3, added 2026-07-11) — the brief's
    // explicit "report BY SECTOR" ask, not just pooled totals. Pure functions over data the
    // runner already collected (final snapshots + the accumulated `EconomyEvent` ledger) — no
    // new simulation reads, this is reporting-layer aggregation only.
    // ============================================================

    /** Every real [BusinessType] this collector ever reports on — excludes `PUBLIC_SERVICES`
     *  (CLINIC/SCHOOL/TOWN_HALL), same exclusion [capture] already applies to `businesses`. */
    val REPORTED_SECTORS: List<BusinessType> = BusinessType.entries.filter { it !in EconomySystem.PUBLIC_SERVICES }

    data class SectorReport(
        val type: BusinessType,
        val everOpened: Int,
        val stillOpen: Int,
        val closed: Int,
        val closureRatePct: Double,
        val recoveries: Int,
        val expansions: Int,
        val contractsWon: Int,
        val lifespanYearsClosed: Distribution,
        val dailyNetProfitDistribution: Distribution,
        val reserveRunwayDaysOpen: Distribution,
        val demandOpen: Distribution,
        val staffingOpen: Distribution,
        val failureReasons: Map<String, Int>
    )

    /**
     * Builds one [SectorReport] per [REPORTED_SECTORS] entry from a pool of final-snapshot
     * [BusinessSnapshot]s (across all seeds — "ever opened" reads every business ID the final
     * snapshot still knows about, whether open or closed) and the full accumulated event ledger
     * ([events], across all seeds, all days). A business that was closed and had its slot
     * reused (restructuring, succession) still shows up once per distinct `id` in the final
     * snapshot — restructuring specifically mints a NEW `Business.id` (see
     * `attemptRestructureOrRelocate`/`recoveryEventAsRestructure`), so the old and new sector
     * identities are correctly counted as two separate lifecycle records, not double-counted
     * under one id.
     */
    fun buildSectorReports(
        finalBusinesses: List<BusinessSnapshot>,
        events: List<EconomyEvent>
    ): List<SectorReport> = REPORTED_SECTORS.map { type ->
        val bizzes = finalBusinesses.filter { it.type == type }
        val closed = bizzes.filter { !it.open }
        val open = bizzes.filter { it.open }
        val everOpened = bizzes.size
        val closedCount = closed.size
        val closureRate = if (everOpened == 0) 0.0 else 100.0 * closedCount / everOpened

        val sectorEvents = events.filter { it.businessType == type }
        val recoveries = sectorEvents.count { it.type == EventType.BUSINESS_RECOVERED }
        val expansions = sectorEvents.count { it.type == EventType.BUSINESS_EXPANDED }
        val contracts = sectorEvents.count { it.type == EventType.CONTRACT_WON }

        // Lifespan (years) for closed businesses only — real `openedAt`/`closedAt` minute deltas,
        // converted via `SimTime.MINUTES_PER_YEAR` (same conversion `NewspaperGenerator`'s own
        // `BUSINESS_CLOSED` headline already uses for "closes after N years", confirming this is
        // the established, correct conversion rather than an invented one).
        val lifespans = closed.mapNotNull { b ->
            val closedAt = b.closedAt ?: return@mapNotNull null
            (closedAt - b.openedAt).toDouble() / com.ripple.town.core.model.SimTime.MINUTES_PER_YEAR
        }

        val failureReasons = sectorEvents
            .filter { it.type == EventType.BUSINESS_CLOSED }
            .mapNotNull { it.payload["immediate_cause"] }
            .groupingBy { it }.eachCount()

        SectorReport(
            type = type,
            everOpened = everOpened,
            stillOpen = open.size,
            closed = closedCount,
            closureRatePct = closureRate,
            recoveries = recoveries,
            expansions = expansions,
            contractsWon = contracts,
            lifespanYearsClosed = Distribution.of(lifespans),
            dailyNetProfitDistribution = Distribution.of(open.mapNotNull { it.lastNetDaily }),
            reserveRunwayDaysOpen = Distribution.of(
                open.map { if (it.reserveRunwayDays.isFinite()) it.reserveRunwayDays else 9999.0 }
            ),
            demandOpen = Distribution.of(open.map { it.demand }),
            staffingOpen = Distribution.of(open.map { it.staffCount.toDouble() }),
            failureReasons = failureReasons
        )
    }
}
