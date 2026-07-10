package com.ripple.town.simulation.calibration

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.SimTime
import com.ripple.town.simulation.TestWorld

/**
 * Runs fresh, deterministic [com.ripple.town.core.simulation.SimulationCoordinator] simulations
 * across multiple seeds, collecting [EconomyMetricsCollector.Snapshot]s at regular in-game-day
 * intervals, for the economy-calibration audit (see `docs/economy-calibration-report.md`).
 *
 * ## Scoping — read before changing the defaults
 *
 * The originating brief suggested ~100 seeds x 5-10 simulated years. That is not realistic for a
 * single JVM test invocation in this environment:
 *
 * - One simulated day is [SimTime.TICKS_PER_DAY] = 144 full `tick()` calls, each running the
 *   entire system pipeline (needs, decisions, economy, relationships, health, etc.) over a whole
 *   town's population — not a cheap loop iteration.
 * - [com.ripple.town.simulation.SimulationTickBenchmark] (this session, same package) already
 *   measured a single `tick()` at a *generous ceiling* of 50-75ms on this host JVM. Even taking a
 *   far more realistic few-ms-per-tick actual cost, 100 seeds x 10 years x 144 x 3600 ticks is
 *   many hours of wall clock — completely unrunnable as a `./gradlew test --tests` invocation
 *   that a human or CI is waiting on.
 *
 * So this runner defaults to **[DEFAULT_SEEDS] = 10 seeds x [DEFAULT_YEARS] = 1 simulated year**
 * each, snapshotting every [DEFAULT_SNAPSHOT_INTERVAL_DAYS] = 30 in-game days (13 snapshots per
 * seed run: day 0, 30, 60, ... 360). That is 10 x 360 = 3,600 simulated days = 3,600 x 144 =
 * 518,400 ticks total. This was measured to actually complete in this environment (see the
 * calibration report for the real wall-clock time observed) — a genuinely small run that produces
 * genuinely real numbers, rather than a large run that produces nothing because it never finishes.
 *
 * If a future session has more time/confidence budget, [DEFAULT_SEEDS] and [DEFAULT_YEARS] can be
 * raised — the runner itself is not hardcoded to this scope, only its defaults are.
 */
object EconomyCalibrationRunner {

    const val DEFAULT_SEEDS_COUNT = 10
    const val DEFAULT_YEARS = 1
    const val DEFAULT_SNAPSHOT_INTERVAL_DAYS = 30L

    /** Ten arbitrary, fixed seeds — deterministic across runs (same rule as every other seeded
     *  test in this codebase: a literal list, not `Random()`-picked, so results are reproducible). */
    val DEFAULT_SEEDS: List<Long> = listOf(
        1_001L, 2_002L, 3_003L, 4_004L, 5_005L,
        6_006L, 7_007L, 8_008L, 9_009L, 10_010L
    )

    data class SeedRun(
        val seed: Long,
        val snapshots: List<EconomyMetricsCollector.Snapshot>
    )

    data class RunConfig(
        val seeds: List<Long> = DEFAULT_SEEDS,
        val years: Int = DEFAULT_YEARS,
        val snapshotIntervalDays: Long = DEFAULT_SNAPSHOT_INTERVAL_DAYS
    )

    /** Runs one fresh, isolated simulation per seed for [RunConfig.years] simulated years,
     *  snapshotting at day 0 and every [RunConfig.snapshotIntervalDays] thereafter. Each seed's
     *  world is generated and ticked completely independently — no shared mutable state between
     *  seed runs, matching the rest of this codebase's per-seed determinism guarantee. */
    fun run(config: RunConfig = RunConfig()): List<SeedRun> =
        config.seeds.map { seed -> runOneSeed(seed, config) }

    private fun runOneSeed(seed: Long, config: RunConfig): SeedRun {
        val coordinator = TestWorld.newCoordinator(seed = seed)
        val totalDays = config.years * SimTime.DAYS_PER_YEAR.toInt()
        val snapshots = mutableListOf<EconomyMetricsCollector.Snapshot>()
        var closuresSoFar = 0

        // Day-0 snapshot before any simulated time passes — the true starting point.
        snapshots += EconomyMetricsCollector.capture(coordinator.state, dayIndex = 0L, closuresSoFar = 0)

        var dayIndex = 0L
        while (dayIndex < totalDays) {
            val daysThisChunk = minOf(config.snapshotIntervalDays, totalDays - dayIndex)
            repeat((daysThisChunk * SimTime.TICKS_PER_DAY).toInt()) {
                val result = coordinator.tick()
                closuresSoFar += result.events.count { it.type == EventType.BUSINESS_CLOSED }
            }
            dayIndex += daysThisChunk
            snapshots += EconomyMetricsCollector.capture(coordinator.state, dayIndex = dayIndex, closuresSoFar = closuresSoFar)
        }
        return SeedRun(seed, snapshots)
    }
}
