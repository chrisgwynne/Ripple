package com.ripple.town.core.simulation

/**
 * Deterministic random source built on SplitMix64.
 *
 * The engine derives one instance per tick from (worldSeed, tick) and consumes
 * draws in a fixed iteration order, so a world with the same seed and the same
 * event history always evolves identically.
 */
class SimRandom(seed: Long) {

    private var state: Long = seed

    constructor(worldSeed: Long, tick: Long, salt: Long = 0L) :
        this(mix(mix(worldSeed, tick), salt))

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // golden gamma
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform double in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    fun nextDouble(from: Double, until: Double): Double = from + nextDouble() * (until - from)

    /** Uniform int in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound).toInt()
    }

    fun nextInt(from: Int, until: Int): Int = from + nextInt(until - from)

    fun nextLong(from: Long, until: Long): Long {
        require(until > from)
        return from + ((nextLong() ushr 1) % (until - from))
    }

    fun nextBoolean(probability: Double = 0.5): Boolean = nextDouble() < probability

    /** Roughly normal-distributed value via averaging, clamped to [min, max]. */
    fun nextGaussianLike(mean: Double, spread: Double, min: Double, max: Double): Double {
        val u = (nextDouble() + nextDouble() + nextDouble()) / 3.0 // bell-ish in [0,1]
        return (mean + (u - 0.5) * 2.0 * spread).coerceIn(min, max)
    }

    fun <T> pick(list: List<T>): T {
        require(list.isNotEmpty())
        return list[nextInt(list.size)]
    }

    fun <T> pickOrNull(list: List<T>): T? = if (list.isEmpty()) null else pick(list)

    /** Fisher-Yates shuffle — returns a new list in a deterministic-but-unpredictable order.
     *  Use instead of `sortedBy { it.id }` wherever a budget cap means only the first N items
     *  are processed, so all entities get a fair chance across ticks. */
    fun <T> shuffled(list: List<T>): List<T> {
        val result = list.toMutableList()
        for (i in result.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val tmp = result[i]; result[i] = result[j]; result[j] = tmp
        }
        return result
    }

    companion object {
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)

        fun mix(a: Long, b: Long): Long {
            var z = a + -0x61c8864680b583ebL * (b + 1)
            z = (z xor (z ushr 33)) * -0xae502812aa7333L
            z = (z xor (z ushr 28)) * -0x3b314601e57a13adL
            return z xor (z ushr 32)
        }
    }
}
