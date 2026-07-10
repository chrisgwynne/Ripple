package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.data.SnapshotBuilder
import com.ripple.town.data.WorldUi
import org.junit.Test

/**
 * Honest scoping note (see the "Benchmarks in CI" backlog item in
 * `docs/backlog.md` and [SimulationTickBenchmark]'s own header for the fuller
 * rationale): a real Compose macrobenchmark for [com.ripple.town.feature.town.TownRenderer]
 * needs `androidx.benchmark.macro` running on a connected physical/virtual
 * device, to capture actual `Canvas` draw-call cost, frame timing, and
 * jank stats (`FrameTimingMetric`). No device or emulator exists in this
 * environment, and none can be verified from here — so this test does NOT
 * claim to be that macrobenchmark. A real `:macrobenchmark` module skeleton
 * is a separate, clearly-labelled follow-up (see `docs/backlog.md`), not
 * built here, since inert unrun Gradle-module scaffolding without any local
 * way to sanity-check it is lower value than a benchmark that actually runs
 * and reports a real number in this environment.
 *
 * What this DOES measure, and it is a real, testable bottleneck contributor:
 * the cost of building the immutable [WorldUi] snapshot
 * ([SnapshotBuilder.build]) that [com.ripple.town.feature.town.TownRenderer]
 * consumes every time the simulation layer publishes a new frame of state.
 * This runs entirely on the JVM, requires no device, and directly bounds how
 * large a population/building count the UI layer can push through before
 * snapshot-construction itself becomes the bottleneck — upstream of, and
 * distinct from, the actual `Canvas.drawImageRect` calls inside
 * `TownRenderer`, which remain unmeasured here.
 */
class TownRenderingDataBenchmark {

    @Test
    fun `WorldUi snapshot construction timing for a freshly generated town`() {
        val state = TestWorld.newState(seed = 55_5555L)
        val result = benchmark(warmup = 50, measured = 300) { SnapshotBuilder.build(state) }
        report("fresh town (pop=${state.population()})", result)

        // Generous ceiling — a regression tripwire, not a tuned frame budget
        // (no device numbers exist here to derive one honestly).
        assertThat(result.meanMs).isLessThan(20.0)
    }

    @Test
    fun `WorldUi snapshot construction timing after the town has grown (more residents, more buildings, more relationships)`() {
        val coordinator = TestWorld.newCoordinator(seed = 55_5555L)
        // Run enough in-game time for population growth, relationships, and
        // event history to accumulate — SnapshotBuilder walks residents'
        // relationships and buildings each call, so this is more
        // representative of a mature save than a freshly generated one.
        TestWorld.runDays(coordinator, 30)
        val state = coordinator.state

        val result = benchmark(warmup = 50, measured = 300) { SnapshotBuilder.build(state) }
        report("after 30 in-game days (pop=${state.population()})", result)

        assertThat(result.meanMs).isLessThan(30.0)
    }

    // --- harness (same honest System.nanoTime() approach as SimulationTickBenchmark,
    // not JMH, not device-representative — see that file's header for the full caveat) ---

    private data class BenchmarkResult(val samplesMs: List<Double>) {
        val meanMs: Double = samplesMs.average()
        val minMs: Double = samplesMs.min()
        val maxMs: Double = samplesMs.max()
    }

    private fun benchmark(warmup: Int, measured: Int, block: () -> WorldUi): BenchmarkResult {
        repeat(warmup) { block() }
        val samples = ArrayList<Double>(measured)
        repeat(measured) {
            val start = System.nanoTime()
            block()
            samples += (System.nanoTime() - start) / 1_000_000.0
        }
        return BenchmarkResult(samples)
    }

    private fun report(label: String, r: BenchmarkResult) {
        println(
            "[TownRenderingDataBenchmark] $label — WorldUi build: " +
                "mean=%.3fms min=%.3fms max=%.3fms (n=%d, host JVM data-layer only — Canvas draw cost NOT measured)"
                    .format(r.meanMs, r.minMs, r.maxMs, r.samplesMs.size)
        )
    }
}
