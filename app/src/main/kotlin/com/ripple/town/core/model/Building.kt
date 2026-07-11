package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class BuildingType(val label: String) {
    HOUSE("House"),
    COTTAGE("Cottage"),
    TERRACE("Terraced house"),
    BAKERY("Bakery"),
    CAFE("Café"),
    PUB("Pub"),
    GROCER("Grocer"),
    HARDWARE("Hardware shop"),
    BOOKSHOP("Bookshop"),
    TAILOR("Tailor"),
    WORKSHOP("Workshop"),
    SCHOOL("School"),
    CLINIC("Clinic"),
    TOWN_HALL("Town hall"),
    PARK("Park"),
    FACTORY("Small factory"),
    CEMETERY("Cemetery"),
    VACANT("Vacant building")
}

val BuildingType.isHome: Boolean
    get() = this == BuildingType.HOUSE || this == BuildingType.COTTAGE || this == BuildingType.TERRACE

val BuildingType.isPublicSpace: Boolean
    get() = this == BuildingType.PARK || this == BuildingType.PUB || this == BuildingType.CAFE

@Serializable
data class Tile(val x: Int, val y: Int) {
    fun manhattan(other: Tile): Int = kotlin.math.abs(x - other.x) + kotlin.math.abs(y - other.y)
}

@Serializable
data class Building(
    val id: Long,
    var name: String,
    var type: BuildingType,
    val origin: Tile,               // top-left tile of footprint
    val width: Int,
    val height: Int,
    val door: Tile,                 // road-adjacent tile used for entry/exit
    var ownerId: Long? = null,      // resident owner if any
    var condition: Double = 80.0,   // 0..100
    var noise: Double = 10.0,       // 0..100
    var value: Double = 40_000.0,
    var capacity: Int = 6,
    val constructedAt: Long = 0L,
    var upgradeLevel: Int = 0,
    var abandoned: Boolean = false,
    /** Short strings describing visible changes over time ("Extension added", "Sign repainted"). */
    val visibleChanges: MutableList<String> = mutableListOf()
) {
    fun containsTile(t: Tile): Boolean =
        t.x >= origin.x && t.x < origin.x + width && t.y >= origin.y && t.y < origin.y + height

    fun centre(): Tile = Tile(origin.x + width / 2, origin.y + height / 2)
}

enum class BusinessType(val label: String) {
    BAKERY("Bakery"), CAFE("Café"), PUB("Pub"), GROCER("Grocer"), HARDWARE("Hardware shop"),
    BOOKSHOP("Bookshop"), TAILOR("Tailor"), WORKSHOP("Furniture workshop"), FACTORY("Factory"),
    CLINIC("Clinic"), SCHOOL("School"), TOWN_HALL("Town hall")
}

@Serializable
data class Business(
    val id: Long,
    val buildingId: Long,
    var name: String,
    val type: BusinessType,
    var ownerId: Long? = null,
    var balance: Double = 2_000.0,
    var reputation: Double = 55.0,      // 0..100
    var demand: Double = 50.0,          // 0..100, how much custom the town gives it
    var priceLevel: Double = 1.0,       // multiplier on base prices
    var employeeCapacity: Int = 3,
    var open: Boolean = true,
    var closedAt: Long? = null,
    var openedAt: Long = 0L,
    var customersToday: Int = 0,
    var revenueToday: Double = 0.0,
    var expensesToday: Double = 0.0,
    /** Consecutive days the balance has been below zero. */
    var daysInTrouble: Int = 0,
    /**
     * Bounded trailing window of daily net cash flow (revenue-after-COGS minus rent/utilities/
     * tax/wages, i.e. what `dailySettlement` actually did to `balance` that day), most-recent
     * last — see `EconomySystem.NET_DAILY_HISTORY_WINDOW`/`recordNetDaily`/`reserveRunway`.
     * Economy Calibration Gate Phase 1 (2026-07-11, see docs/simulation-rules.md "Unit economics
     * + catchment demand"). A new, safe-default field (empty list) so existing checkpoints
     * deserialize unchanged; `reserveRunway` degrades gracefully to a single-day reading until
     * this fills up.
     */
    val recentNetDaily: MutableList<Double> = mutableListOf()
)

/**
 * Staged business distress, derived purely from a live [Business.daysInTrouble] reading against
 * `EconomySystem.CLOSURE_DAYS` — never a persisted field (same "derive, don't duplicate"
 * discipline the concurrent debt-state work on `Resident`/`EconomySystem` uses this session).
 * Computed by `EconomySystem.healthStateOf`. `INSOLVENT`/actually-closed is deliberately not a
 * member here: that's what `Business.open == false` / `Building.abandoned` already represent —
 * no parallel bookkeeping for a state that already exists.
 *
 * Bands (`CLOSURE_DAYS` = 18, `STRUGGLE_NOTICE_DAYS` = 5 as of 2026-07-11):
 * - [HEALTHY]   — `daysInTrouble == 0`, balance non-negative.
 * - [PRESSURED] — `1..<STRUGGLE_NOTICE_DAYS` days in the red; a bad patch, not yet newsworthy.
 * - [AT_RISK]   — `STRUGGLE_NOTICE_DAYS..<CLOSURE_DAYS/2`; the same point `BUSINESS_STRUGGLING`
 *   already fires at, through `CLOSURE_DAYS/2`. Recovery actions (see `EconomySystem
 *   .maybeAttemptRecovery`) become possible from here onward.
 * - [STRUGGLING] — `CLOSURE_DAYS/2..<CLOSURE_DAYS - 2`; deep trouble, recovery still possible but
 *   the runway is visibly shortening.
 * - [CRITICAL]  — the final 1-2 days before `closeBusiness` actually fires.
 */
enum class BusinessHealthState { HEALTHY, PRESSURED, AT_RISK, STRUGGLING, CRITICAL }

@Serializable
data class Employment(
    val id: Long,
    val residentId: Long,
    val businessId: Long,
    var role: String,
    var dailySalary: Double,
    val startedAt: Long,
    var endedAt: Long? = null,
    var shiftStartHour: Int = 9,
    var shiftEndHour: Int = 17,
    var reducedHours: Boolean = false
) {
    val active: Boolean get() = endedAt == null
}

enum class Weather(val label: String) {
    CLEAR("Clear"), CLOUDY("Cloudy"), RAIN("Rain"), STORM("Storm"), FOG("Fog"), SNOW("Snow")
}

enum class TileType { GRASS, ROAD, PATH, WATER, TREE, FLOWERS, PLAZA }

@Serializable
data class TownMap(
    val width: Int,
    val height: Int,
    /** Row-major tile types. */
    val tiles: List<TileType>
) {
    fun tileAt(x: Int, y: Int): TileType =
        if (x in 0 until width && y in 0 until height) tiles[y * width + x] else TileType.GRASS

    fun isWalkable(x: Int, y: Int): Boolean {
        val t = tileAt(x, y)
        return t == TileType.ROAD || t == TileType.PATH || t == TileType.PLAZA || t == TileType.GRASS
    }
}
