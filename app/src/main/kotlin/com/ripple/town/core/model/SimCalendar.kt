package com.ripple.town.core.model

/**
 * Real-world calendar interpretation of [SimTime]'s abstract minute counter.
 *
 * The simulation uses 12 months of 30 days (360 days/year) for clean arithmetic.
 * This object maps those months to January–December, assigns seasons, models UK
 * school terms, and records cultural dates — so every system can reason about
 * "is it Christmas?" or "is today a school day?" from one consistent source.
 *
 * Day-of-week convention (inherited from [SimTime.dayOfWeek]):
 *   0 = Monday, 1 = Tuesday, … 4 = Friday, 5 = Saturday, 6 = Sunday.
 */
object SimCalendar {

    // ---- Seasons ---------------------------------------------------------------------------

    enum class Season(val label: String, val adjective: String) {
        WINTER("Winter", "wintry"),
        SPRING("Spring", "spring"),
        SUMMER("Summer", "summer"),
        AUTUMN("Autumn", "autumnal")
    }

    /** UK meteorological seasons by month index (0=Jan … 11=Dec). */
    fun season(minutes: Long): Season = when (SimTime.monthIndex(minutes)) {
        11, 0, 1 -> Season.WINTER    // Dec, Jan, Feb
        2, 3, 4  -> Season.SPRING    // Mar, Apr, May
        5, 6, 7  -> Season.SUMMER    // Jun, Jul, Aug
        else     -> Season.AUTUMN    // Sep, Oct, Nov
    }

    // ---- Day of week -----------------------------------------------------------------------

    enum class DayOfWeek(val shortLabel: String, val fullLabel: String, val isWeekend: Boolean) {
        MONDAY   ("Mon", "Monday",    false),
        TUESDAY  ("Tue", "Tuesday",   false),
        WEDNESDAY("Wed", "Wednesday", false),
        THURSDAY ("Thu", "Thursday",  false),
        FRIDAY   ("Fri", "Friday",    false),
        SATURDAY ("Sat", "Saturday",  true),
        SUNDAY   ("Sun", "Sunday",    true)
    }

    fun dayOfWeek(minutes: Long): DayOfWeek = DayOfWeek.entries[SimTime.dayOfWeek(minutes)]

    fun isWeekend(minutes: Long): Boolean = SimTime.dayOfWeek(minutes) >= 5

    fun isWeekday(minutes: Long): Boolean = !isWeekend(minutes)

    /** True on Thursdays, Fridays, and Saturdays — when nightlife begins building. */
    fun isNightlifeNight(minutes: Long): Boolean = SimTime.dayOfWeek(minutes) in 3..5

    // ---- School terms (UK-style, 30-day months) -------------------------------------------
    //
    // Three main terms with holidays between them:
    //
    //   Autumn term   : 1 Sep  – 14 Dec   (months 8–11 partial)
    //   Christmas hol : 15 Dec – 6 Jan    (month 11 partial, month 0 partial)
    //   Spring term   : 7 Jan  – 25 Mar   (month 0 partial, months 1–2 partial)
    //   Easter hol    : 26 Mar – 9 Apr    (months 2–3 partial)
    //   Summer term   : 10 Apr – 15 Jul   (months 3–6 partial)
    //   Summer hol    : 16 Jul – 31 Aug   (months 6–7)
    //
    //   Half-terms (one week each):
    //     Oct:  21–25 Oct  (month 9)
    //     Feb:  15–19 Feb  (month 1)
    //     May:  25–29 May  (month 4)

    fun isSchoolHoliday(minutes: Long): Boolean {
        val month = SimTime.monthIndex(minutes)
        val day   = SimTime.dayOfMonth(minutes)
        return when {
            // Summer holiday: Jul 16 – Aug 30
            month == 6 && day >= 16              -> true
            month == 7                            -> true
            // Christmas holiday: Dec 15 – Jan 6
            month == 11 && day >= 15             -> true
            month == 0 && day <= 6               -> true
            // Easter holiday: Mar 26 – Apr 9
            month == 2 && day >= 26              -> true
            month == 3 && day <= 9               -> true
            // Oct half-term: 21–25 Oct
            month == 9 && day in 21..25          -> true
            // Feb half-term: 15–19 Feb
            month == 1 && day in 15..19          -> true
            // May half-term: 25–29 May
            month == 4 && day in 25..29          -> true
            else                                  -> false
        }
    }

    /** A school day is any weekday during term time. */
    fun isSchoolDay(minutes: Long): Boolean = isWeekday(minutes) && !isSchoolHoliday(minutes)

    // ---- UK cultural events ----------------------------------------------------------------

    enum class CulturalEvent(val label: String, val isPublicHoliday: Boolean = false) {
        NEW_YEAR_DAY      ("New Year's Day",          true),
        VALENTINE_DAY     ("Valentine's Day",          false),
        EASTER_SUNDAY     ("Easter Sunday",            true),
        MAY_DAY           ("May Day",                  true),
        SUMMER_BANK_HOL   ("Summer Bank Holiday",      true),
        HALLOWEEN         ("Halloween",                false),
        BONFIRE_NIGHT     ("Bonfire Night",            false),
        CHRISTMAS_EVE     ("Christmas Eve",            false),
        CHRISTMAS_DAY     ("Christmas Day",            true),
        BOXING_DAY        ("Boxing Day",               true),
        NEW_YEAR_EVE      ("New Year's Eve",           false)
    }

    /** The cultural event occurring on the day of [minutes], or null. */
    fun culturalEvent(minutes: Long): CulturalEvent? {
        val month = SimTime.monthIndex(minutes)
        val day   = SimTime.dayOfMonth(minutes)
        return when {
            month == 0  && day == 1  -> CulturalEvent.NEW_YEAR_DAY
            month == 1  && day == 14 -> CulturalEvent.VALENTINE_DAY
            month == 3  && day == 2  -> CulturalEvent.EASTER_SUNDAY    // fixed approximate
            month == 4  && day == 1  -> CulturalEvent.MAY_DAY
            month == 7  && day == 28 -> CulturalEvent.SUMMER_BANK_HOL
            month == 9  && day == 30 -> CulturalEvent.HALLOWEEN
            month == 10 && day == 5  -> CulturalEvent.BONFIRE_NIGHT
            month == 11 && day == 24 -> CulturalEvent.CHRISTMAS_EVE
            month == 11 && day == 25 -> CulturalEvent.CHRISTMAS_DAY
            month == 11 && day == 26 -> CulturalEvent.BOXING_DAY
            month == 11 && day == 30 -> CulturalEvent.NEW_YEAR_EVE
            else                     -> null
        }
    }

    /** Dec 20 – Jan 6: the full Christmas and New Year period. */
    fun isChristmasPeriod(minutes: Long): Boolean {
        val month = SimTime.monthIndex(minutes)
        val day   = SimTime.dayOfMonth(minutes)
        return (month == 11 && day >= 20) || (month == 0 && day <= 6)
    }

    /** Jul 16 – Aug 30: the main summer holiday stretch. */
    fun isSummerHoliday(minutes: Long): Boolean {
        val month = SimTime.monthIndex(minutes)
        val day   = SimTime.dayOfMonth(minutes)
        return month == 7 || (month == 6 && day >= 16)
    }

    // ---- Date formatting -------------------------------------------------------------------

    /** Compact: "14 Jan • Year 3" */
    fun formatDate(minutes: Long): String = SimTime.formatDate(minutes)

    /** Long-form: "14 January, Year 3" */
    fun formatDateLong(minutes: Long): String = SimTime.formatDateLong(minutes)

    /** With day-of-week prefix: "Mon 14 Jan • Year 3" */
    fun formatDateWithDay(minutes: Long): String {
        val dow = dayOfWeek(minutes).shortLabel
        val d   = SimTime.dayOfMonth(minutes)
        val m   = SimTime.MONTH_SHORT[SimTime.monthIndex(minutes)]
        val y   = SimTime.year(minutes)
        return "$dow $d $m • Year $y"
    }

    /** "Monday" */
    fun dayName(minutes: Long): String = dayOfWeek(minutes).fullLabel

    /** "Monday 14 January" */
    fun dayAndDateFull(minutes: Long): String =
        "${dayOfWeek(minutes).fullLabel} ${SimTime.dayOfMonth(minutes)} ${SimTime.MONTH_NAMES[SimTime.monthIndex(minutes)]}"
}
