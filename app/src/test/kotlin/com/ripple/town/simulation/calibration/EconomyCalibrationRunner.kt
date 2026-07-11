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
 *
 * ## Multi-year scoping (Economy Calibration Gate, Phase 3, added 2026-07-11)
 *
 * [EconomyValidationReport] needs real 1/5/10-simulated-year measurements per the brief's
 * "Validation" section (which itself asks for 100 seeds x 1/5/10 years — acknowledged as
 * unrunnable in this environment, same reasoning as above, scaled per-year instead). Real,
 * measured-not-guessed per-seed-year wall-clock cost, via a throwaway timing probe run this
 * session (`TimingProbe{1,5,10}YearTest.kt`, deleted after use, numbers preserved here and in
 * `docs/backlog.md`'s Phase 3 entry):
 *
 * | Config probed | Wall clock | ms/seed-year |
 * |---|---|---|
 * | 5 seeds x 1 year  | 31,674ms total (~6.3s/seed)   | ~6,334 |
 * | 3 seeds x 5 years  | 178,244ms total (~59.4s/seed) | ~11,882 |
 * | 2 seeds x 10 years | 438,002ms total (~219s/seed)  | ~21,900 |
 *
 * Cost per simulated year roughly TRIPLES from year 1 to year 10 (6.3s -> 11.9s -> 21.9s per
 * seed-year) — expected, since the town's population and business count both genuinely grow over
 * simulated time (see Phase 2's "business count grew ~+12% over the simulated year" finding), so
 * later years tick more entities per day than earlier ones, not a flat per-year cost.
 *
 * From these real measurements, [EconomyValidationReport] uses three separately-scoped configs
 * (each its own `@Test` so no single run risks the ~8-10 minute per-invocation ceiling):
 * - **Wide**: [WIDE_SEEDS] = 15 seeds x 1 year (~95s projected, closest to the brief's 100-seed
 *   ask this environment can actually run within about a minute and a half).
 * - **Medium**: [MEDIUM_SEEDS] = 5 seeds x 5 years (~297s / ~5min projected).
 * - **Narrow**: [NARROW_SEEDS] = 2 seeds x 10 years (~438s / ~7.3min projected, matching the
 *   probe exactly since 2 seeds was also the probed size) — honestly scoped down from an
 *   originally-considered 3 seeds (~657s / ~11min, over the ceiling).
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

    /** Wide (1-year) validation config seeds — 15 fixed seeds, see this object's doc comment
     *  "Multi-year scoping" for the timing evidence behind this count. Extends [DEFAULT_SEEDS]
     *  with 5 more of the same arbitrary literal pattern rather than picking an unrelated set. */
    val WIDE_SEEDS: List<Long> = DEFAULT_SEEDS + listOf(11_011L, 12_012L, 13_013L, 14_014L, 15_015L)

    /** Medium (5-year) validation config seeds — 5 fixed seeds, see "Multi-year scoping". */
    val MEDIUM_SEEDS: List<Long> = listOf(1_001L, 2_002L, 3_003L, 4_004L, 5_005L)

    /** Narrow (10-year) validation config seeds — 2 fixed seeds, see "Multi-year scoping". */
    val NARROW_SEEDS: List<Long> = listOf(1_001L, 2_002L)

    data class SeedRun(
        val seed: Long,
        val snapshots: List<EconomyMetricsCollector.Snapshot>,
        /** Full accumulated ledger of tracked economy events (Economy Calibration Gate Phase 3,
         *  2026-07-11) — every `BUSINESS_CLOSED`/`BUSINESS_RECOVERED`/`BUSINESS_EXPANDED`/
         *  `BUSINESS_OPENED`/`CONTRACT_WON` event fired across the WHOLE run, not just what a
         *  30-day-interval snapshot would catch (a business that recovers then closes 20 days
         *  later, both inside one snapshot interval, would otherwise vanish from the record). See
         *  `EconomyMetricsCollector.extractEconomyEvents`. */
        val events: List<EconomyMetricsCollector.EconomyEvent>
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
        val allEvents = mutableListOf<EconomyMetricsCollector.EconomyEvent>()
        var closuresSoFar = 0

        // Day-0 snapshot before any simulated time passes — the true starting point.
        snapshots += EconomyMetricsCollector.capture(coordinator.state, dayIndex = 0L, closuresSoFar = 0)

        var dayIndex = 0L
        while (dayIndex < totalDays) {
            val daysThisChunk = minOf(config.snapshotIntervalDays, totalDays - dayIndex)
            repeat((daysThisChunk * SimTime.TICKS_PER_DAY).toInt()) {
                val result = coordinator.tick()
                closuresSoFar += result.events.count { it.type == EventType.BUSINESS_CLOSED }
                // Full-run event ledger (Phase 3, 2026-07-11) — captured every tick, not just at
                // snapshot boundaries, so nothing that happens between two 30-day snapshots is
                // lost. Resolved against `coordinator.state` at the moment each tick's events
                // fired, so a business's sector is correctly attributed even if it's later
                // restructured into a different `BusinessType`/`Business.id`.
                if (result.events.isNotEmpty()) {
                    allEvents += EconomyMetricsCollector.extractEconomyEvents(result.events, coordinator.state, dayIndex)
                }
            }
            dayIndex += daysThisChunk
            snapshots += EconomyMetricsCollector.capture(coordinator.state, dayIndex = dayIndex, closuresSoFar = closuresSoFar)
        }
        return SeedRun(seed, snapshots, allEvents)
    }
}
