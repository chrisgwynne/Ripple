package com.ripple.town.core.simulation

import com.ripple.town.core.model.SimCalendar
import com.ripple.town.core.model.SimTime

/**
 * The Human Scheduler: every activity that should feel like something a real person
 * would do at a believable time of day and day of week.
 *
 * Two uses:
 *
 * 1. **[realisticTimeToday]** — called from daily systems that fire at midnight
 *    (`updateDaily`), so events they emit carry a human-believable timestamp in the
 *    news and event log rather than "00:00 on 14 Jan".
 *
 * 2. **[isValidNow]** / **[nextValidSlot]** — gates used by systems that should only
 *    fire during certain windows (burglaries at night, council meetings in business
 *    hours, weddings on weekends).
 *
 * 3. **[isOnCooldown]** / **[recordFired]** — town-wide cadence control so the same
 *    type of event cannot cluster ("three weddings this week").
 *
 * Hour windows use a 0..23 convention. Windows that cross midnight (e.g. burglaries:
 * start=22, end=5) are handled by [isInHourWindow].
 */
enum class ScheduledActivity(
    val label: String,
    val startHour: Int,           // inclusive
    val endHour: Int,             // exclusive; if < startHour, window crosses midnight
    val weekendAllowed: Boolean,
    val weekdayAllowed: Boolean,
    val townCooldownDays: Int,    // 0 = no town-wide cadence limit
    val residentCooldownDays: Int // 0 = no per-resident cooldown
) {
    // Life events
    WEDDING          ("Wedding",           10, 18, weekendAllowed = true,  weekdayAllowed = false, 7,  90),
    FUNERAL          ("Funeral",            9, 15, weekendAllowed = false, weekdayAllowed = true,  3,  0),
    BIRTH            ("Birth",              6, 22, weekendAllowed = true,  weekdayAllowed = true,  0,  0),
    ENGAGEMENT       ("Engagement",        10, 20, weekendAllowed = true,  weekdayAllowed = true,  14, 60),
    // Business lifecycle
    BUSINESS_OPEN    ("Business opening",   8, 11, weekendAllowed = false, weekdayAllowed = true,  5,  60),
    BUSINESS_CLOSE   ("Business closing",  17, 21, weekendAllowed = true,  weekdayAllowed = true,  2,  0),
    // Crime
    CRIME_PETTY      ("Petty theft",        9, 21, weekendAllowed = true,  weekdayAllowed = true,  0,  0),
    CRIME_BURGLARY   ("Burglary",          22,  5, weekendAllowed = true,  weekdayAllowed = true,  0,  0),
    CRIME_VIOLENT    ("Violent crime",     20,  3, weekendAllowed = true,  weekdayAllowed = true,  0,  0),
    NOISE_COMPLAINT  ("Noise complaint",   22,  3, weekendAllowed = true,  weekdayAllowed = true,  0,  0),
    CRIME_FRAUD      ("Fraud",              9, 18, weekendAllowed = false, weekdayAllowed = true,  0,  0),
    // Civic
    COUNCIL_MEETING  ("Council meeting",    9, 17, weekendAllowed = false, weekdayAllowed = true,  7,  0),
    POLITICAL_RALLY  ("Political rally",   12, 19, weekendAllowed = true,  weekdayAllowed = false, 14, 0),
    ELECTION         ("Election day",       7, 21, weekendAllowed = false, weekdayAllowed = true,  0,  0),
    // Community
    SPORTS_MATCH     ("Sports match",      12, 18, weekendAllowed = true,  weekdayAllowed = false, 7,  0),
    COMMUNITY_EVENT  ("Community event",   10, 19, weekendAllowed = true,  weekdayAllowed = true,  14, 0),
    FESTIVAL         ("Festival",          10, 22, weekendAllowed = true,  weekdayAllowed = false, 21, 0),
    MARKET           ("Market",             8, 15, weekendAllowed = true,  weekdayAllowed = false, 7,  0),
    // Work & services
    CONSTRUCTION     ("Construction",       7, 18, weekendAllowed = false, weekdayAllowed = true,  0,  0),
    MEDICAL          ("Medical appointment",8, 17, weekendAllowed = false, weekdayAllowed = true,  0,  0),
    GRADUATION       ("Graduation",        10, 16, weekendAllowed = false, weekdayAllowed = true,  14, 0),
    SCHOOL_DAY       ("School",             8, 16, weekendAllowed = false, weekdayAllowed = true,  0,  0),
    // Social
    SOCIAL_EVENING   ("Social gathering",  18, 23, weekendAllowed = true,  weekdayAllowed = true,  0,  0),
    SHOPPING         ("Shopping",           9, 20, weekendAllowed = true,  weekdayAllowed = true,  0,  0),
    NIGHTLIFE        ("Nightlife",         21,  2, weekendAllowed = true,  weekdayAllowed = false, 0,  0),
    // Default for anything that should simply not happen at night
    DAYTIME_GENERAL  ("General daytime",    8, 21, weekendAllowed = true,  weekdayAllowed = true,  0,  0),
}

object HumanScheduler {

    /**
     * True if [now] falls within [activity]'s allowed day-of-week and hour window.
     * Does NOT check town-wide cooldowns — call [isOnCooldown] separately if needed.
     */
    fun isValidNow(activity: ScheduledActivity, now: Long): Boolean {
        val weekend = SimCalendar.isWeekend(now)
        if (weekend && !activity.weekendAllowed) return false
        if (!weekend && !activity.weekdayAllowed) return false
        return isInHourWindow(SimTime.hourOfDay(now).toInt(), activity.startHour, activity.endHour)
    }

    /**
     * Returns a sim-time within [activity]'s hour window for *today*, used so events
     * emitted during `updateDaily` (which fires at midnight) carry a human-believable
     * timestamp instead of "00:00".
     *
     * [dayStartAt] = `now - SimTime.minuteOfDay(now)` (the midnight of the current day).
     * [rng] must be the tick's own RNG so the result is deterministic.
     */
    fun realisticTimeToday(activity: ScheduledActivity, dayStartAt: Long, rng: SimRandom): Long {
        val start = activity.startHour
        // For cross-midnight windows use the start side (night events → emit in the evening)
        val end   = if (activity.endHour > activity.startHour) activity.endHour else activity.startHour + 2
        val windowMinutes = ((end - start) * SimTime.MINUTES_PER_HOUR).coerceAtLeast(1L)
        val offset = rng.nextInt(0, windowMinutes.toInt())
        return dayStartAt + start * SimTime.MINUTES_PER_HOUR + offset
    }

    /**
     * Next sim-time at or after [fromTime] when [activity] is allowed.
     * Searches up to two weeks ahead; returns tomorrow if nothing found (shouldn't happen).
     */
    fun nextValidSlot(activity: ScheduledActivity, fromTime: Long): Long {
        var t = fromTime
        val limit = fromTime + 14 * SimTime.MINUTES_PER_DAY
        while (t < limit) {
            if (isValidNow(activity, t)) return t
            t += SimTime.MINUTES_PER_TICK
        }
        return fromTime + SimTime.MINUTES_PER_DAY
    }

    // ---- Town cadence / cooldowns ----------------------------------------------------------

    private fun cooldownKey(activity: ScheduledActivity): String = "hsc:${activity.name}"

    /**
     * True if [activity]'s town-wide cooldown has not yet elapsed since the last
     * occurrence. Pass [WorldState.activityCooldowns].
     */
    fun isOnCooldown(activity: ScheduledActivity, now: Long, cooldowns: Map<String, Long>): Boolean {
        if (activity.townCooldownDays == 0) return false
        val last = cooldowns[cooldownKey(activity)] ?: return false
        return now - last < activity.townCooldownDays * SimTime.MINUTES_PER_DAY
    }

    /** Record that [activity] fired at [now]; updates [cooldowns] in-place. */
    fun recordFired(activity: ScheduledActivity, now: Long, cooldowns: MutableMap<String, Long>) {
        if (activity.townCooldownDays > 0) cooldowns[cooldownKey(activity)] = now
    }

    // ---- Internal helpers ------------------------------------------------------------------

    private fun isInHourWindow(hour: Int, start: Int, end: Int): Boolean =
        if (end > start) hour in start until end
        else             hour >= start || hour < end  // crosses midnight
}
