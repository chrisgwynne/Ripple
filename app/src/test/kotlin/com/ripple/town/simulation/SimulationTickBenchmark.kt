package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

/**
 * Honest, JVM-only timing harness for [com.ripple.town.core.simulation.SimulationCoordinator.tick] —
 * NOT a rigorous microbenchmark.
 *
 * Scoping note (see the "Benchmarks in CI" backlog item in `docs/backlog.md`):
 * the official approach for this would be a JMH build (`me.champeau.jmh`) or an
 * `androidx.benchmark:benchmark-junit4` `:microbenchmark` module running on a
 * connected device. This environment has neither a device/emulator nor an
 * established JMH setup, and this session was already warned off adding a new
 * Gradle module given prior instability with the existing test setup. So this
 * is deliberately the simplest thing that is actually true: a plain
 * `System.nanoTime()` warmup+measure loop, run as a normal fast JVM unit test
 * in the existing `test` source set (no new module, no new plugin, no new
 * Gradle configuration).
 *
 * What this does NOT claim to be:
 * - Not JMH — no fork isolation, no dead-code-elimination blackholing, no JIT
 *   compilation-mode control, no statistically rigorous confidence intervals.
 * - Not run on Android — this executes on the host JVM under Robolectric-free
 *   plain JUnit, not on an ART runtime, a real device's CPU, or with any of
 *   the thermal/scheduling variance a real device sees. Numbers here are
 *   directional (engine complexity trending up/down across changes on *this*
 *   machine), not a substitute for on-device profiling.
 * - Not gated as a special "slow" CI job — bounded warmup/measure counts keep
 *   this in the same tens-of-milliseconds ballpark as the rest of the unit
 *   test suite, so it runs on every `./gradlew test` like any other test.
 *
 * What it IS good for: a cheap regression tripwire. `tick()` is the one
 * function every single frame of simulated time passes through — its cost
 * directly bounds how large a population/town this engine can support before
 * a tick starts taking noticeably long wall-clock time (relevant to the
 * "residents at scale" engineering-debt concern). If a future change makes
 * `tick()` dramatically slower, this test's generous ceiling assertion will
 * catch it long before anyone notices frame drops on a device.
 */
class SimulationTickBenchmark {

    @Test
    fun `tick() timing on a freshly generated town`() {
        val result = benchmarkTicks(seed = 909_090L, warmupTicks = 200, measuredTicks = 500)
        report("fresh town (default WorldGenerator population)", result)

        // Generous ceiling, not a tight performance budget: this exists to catch a
        // gross regression (an accidentally O(n^2) system, an infinite-ish loop,
        // a new per-tick allocation storm) — not to enforce a specific target
        // frame budget, which would need real device numbers this environment
        // cannot produce.
        assertThat(result.meanMs).isLessThan(50.0)
    }

    @Test
    fun `tick() timing after the town has run for a while (more history, more state)`() {
        // A coordinator that has already run several in-game days accumulates
        // relationships, memories, events, businesses trading, etc. — a more
        // representative "mid-game" tick than a freshly generated town's first
        // tick, since several per-tick systems scale with accumulated state
        // (relationship graphs, event index, memory lists) rather than pure
        // population count.
        val coordinator = TestWorld.newCoordinator(seed = 909_090L)
        TestWorld.runDays(coordinator, 10)

        val warmup = timeTicks(coordinator, 100)
        val measured = timeTicks(coordinator, 500)
        val result = BenchmarkResult(measured)
        report("10 in-game days elapsed (warmup discarded: ${warmup.size} ticks)", result)

        assertThat(result.meanMs).isLessThan(75.0)
    }

    // --- harness -------------------------------------------------------

    private data class BenchmarkResult(val samplesMs: List<Double>) {
        val meanMs: Double = samplesMs.average()
        val minMs: Double = samplesMs.min()
        val maxMs: Double = samplesMs.max()
        val stdDevMs: Double = run {
            val m = meanMs
            sqrt(samplesMs.sumOf { (it - m) * (it - m) } / samplesMs.size)
        }
    }

    private fun benchmarkTicks(seed: Long, warmupTicks: Int, measuredTicks: Int): BenchmarkResult {
        val coordinator = TestWorld.newCoordinator(seed = seed)
        timeTicks(coordinator, warmupTicks) // JIT warmup, discarded
        return BenchmarkResult(timeTicks(coordinator, measuredTicks))
    }

    private fun timeTicks(coordinator: com.ripple.town.core.simulation.SimulationCoordinator, count: Int): List<Double> {
        val samples = ArrayList<Double>(count)
        repeat(count) {
            val start = System.nanoTime()
            coordinator.tick()
            val elapsedNs = System.nanoTime() - start
            samples += elapsedNs / 1_000_000.0
        }
        return samples
    }

    /** Prints to stdout so the numbers show up in local/CI test logs without a new reporting pipeline. */
    private fun report(label: String, r: BenchmarkResult) {
        println(
            "[SimulationTickBenchmark] $label — " +
                "mean=%.3fms min=%.3fms max=%.3fms stddev=%.3fms (n=%d, host JVM, not device-representative)"
                    .format(r.meanMs, r.minMs, r.maxMs, r.stdDevMs, r.samplesMs.size)
        )
    }
}
