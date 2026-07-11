package com.ripple.town.core.model

/**
 * Simulation time is measured in whole in-game minutes since the world epoch.
 *
 * Calendar simplification: 12 months of 30 days, 360 days per year.
 * The world epoch is 1 Spring (month 1), Year 1, 00:00.
 */
object SimTime {
    const val MINUTES_PER_HOUR = 60L
    const val HOURS_PER_DAY = 24L
    const val MINUTES_PER_DAY = MINUTES_PER_HOUR * HOURS_PER_DAY
    const val DAYS_PER_MONTH = 30L
    const val MONTHS_PER_YEAR = 12L
    const val DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR
    const val MINUTES_PER_YEAR = MINUTES_PER_DAY * DAYS_PER_YEAR

    /** One simulation tick advances this many in-game minutes. */
    const val MINUTES_PER_TICK = 10L
    const val TICKS_PER_HOUR = (MINUTES_PER_HOUR / MINUTES_PER_TICK).toInt()
    const val TICKS_PER_DAY = (MINUTES_PER_DAY / MINUTES_PER_TICK).toInt()

    /** At 1x speed one real second is one in-game minute (1 real minute = 1 in-game hour). */
    const val GAME_MINUTES_PER_REAL_SECOND_AT_1X = 1.0

    /** Full month names — January through December. */
    val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
    /** Abbreviated month names for compact display. */
    val MONTH_SHORT = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    fun tickOf(minutes: Long): Long = minutes / MINUTES_PER_TICK
    fun minuteOfDay(minutes: Long): Int = (minutes % MINUTES_PER_DAY).toInt()
    fun hourOfDay(minutes: Long): Int = ((minutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR).toInt()
    fun dayIndex(minutes: Long): Long = minutes / MINUTES_PER_DAY
    fun dayOfMonth(minutes: Long): Int = ((dayIndex(minutes) % DAYS_PER_MONTH) + 1).toInt()
    fun monthIndex(minutes: Long): Int = ((dayIndex(minutes) / DAYS_PER_MONTH) % MONTHS_PER_YEAR).toInt()
    fun year(minutes: Long): Int = (dayIndex(minutes) / DAYS_PER_YEAR).toInt() + 1
    fun dayOfWeek(minutes: Long): Int = (dayIndex(minutes) % 7).toInt() // 0=Mon..6=Sun; 5-6 = weekend

    fun ageYears(birthMinutes: Long, now: Long): Int =
        ((now - birthMinutes) / MINUTES_PER_YEAR).toInt().coerceAtLeast(0)

    /** Compact date: "14 Jan • Year 3" */
    fun formatDate(minutes: Long): String =
        "${dayOfMonth(minutes)} ${MONTH_SHORT[monthIndex(minutes)]} • Year ${year(minutes)}"

    /** Long-form date: "14 January, Year 3" */
    fun formatDateLong(minutes: Long): String =
        "${dayOfMonth(minutes)} ${MONTH_NAMES[monthIndex(minutes)]}, Year ${year(minutes)}"

    fun formatClock(minutes: Long): String {
        val h = hourOfDay(minutes)
        val m = (minutes % MINUTES_PER_HOUR).toInt()
        return "%02d:%02d".format(h, m)
    }

    fun formatDateTime(minutes: Long): String = "${formatDate(minutes)} · ${formatClock(minutes)}"

    // Time-of-day hour boundaries
    const val DAWN_START_HOUR      = 5
    const val DAWN_END_HOUR        = 7
    const val MORNING_START_HOUR   = 8
    const val MORNING_END_HOUR     = 11
    const val AFTERNOON_START_HOUR = 12
    const val AFTERNOON_END_HOUR   = 16
    const val EVENING_START_HOUR   = 17
    const val EVENING_END_HOUR     = 20

    fun timeOfDay(minutes: Long): TimeOfDay {
        return when (hourOfDay(minutes)) {
            in DAWN_START_HOUR..DAWN_END_HOUR           -> TimeOfDay.DAWN
            in MORNING_START_HOUR..MORNING_END_HOUR     -> TimeOfDay.MORNING
            in AFTERNOON_START_HOUR..AFTERNOON_END_HOUR -> TimeOfDay.AFTERNOON
            in EVENING_START_HOUR..EVENING_END_HOUR     -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }
}

enum class TimeOfDay(val label: String) {
    DAWN("Dawn"), MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening"), NIGHT("Night")
}

enum class SimSpeed(val multiplier: Double, val label: String) {
    PAUSED(0.0, "⏸"),
    NORMAL(1.0, "1×"),
    FAST(3.0, "3×"),
    VERY_FAST(10.0, "10×")
}
