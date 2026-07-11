package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class BuildingType(val label: String) {
    // Residential
    HOUSE("House"),
    COTTAGE("Cottage"),
    TERRACE("Terraced house"),
    FLAT("Flats"),
    // Food & drink
    BAKERY("Bakery"),
    CAFE("Café"),
    PUB("Pub"),
    RESTAURANT("Restaurant"),
    TAKEAWAY("Takeaway"),
    // Retail
    GROCER("Grocer"),
    SUPERMARKET("Supermarket"),
    HARDWARE("Hardware shop"),
    BOOKSHOP("Bookshop"),
    TAILOR("Tailor"),
    PHARMACY("Pharmacy"),
    // Services / commercial
    WORKSHOP("Workshop"),
    OFFICE("Office"),
    HOTEL("Hotel"),
    GARAGE("Garage"),
    // Civic / public
    SCHOOL("School"),
    NURSERY("Nursery"),
    LIBRARY("Library"),
    CLINIC("Clinic"),
    HOSPITAL("Hospital"),
    TOWN_HALL("Town hall"),
    FIRE_STATION("Fire station"),
    POLICE_STATION("Police station"),
    SPORTS_HALL("Sports hall"),
    SWIMMING_POOL("Swimming pool"),
    COMMUNITY_CENTRE("Community centre"),
    CINEMA("Cinema"),
    NIGHTCLUB("Nightclub"),
    // Outdoor / infrastructure
    PARK("Park"),
    FACTORY("Small factory"),
    WAREHOUSE("Warehouse"),
    CEMETERY("Cemetery"),
    VACANT("Vacant building"),
}

/** Core 7 civic BuildingTypes that receive public funding and don't decay via VacancySystem.
 *  Must stay in sync with [CIVIC_BUSINESS_TYPES]. */
val CIVIC_BUILDING_TYPES: Set<BuildingType> = setOf(
    BuildingType.FIRE_STATION, BuildingType.POLICE_STATION,
    BuildingType.SPORTS_HALL, BuildingType.COMMUNITY_CENTRE,
    BuildingType.SCHOOL, BuildingType.CLINIC, BuildingType.TOWN_HALL
)

/** Core 7 civic BusinessTypes subject to the municipal service-running-cost levy in BudgetSystem.
 *  Must stay in sync with [CIVIC_BUILDING_TYPES]. */
val CIVIC_BUSINESS_TYPES: Set<BusinessType> = setOf(
    BusinessType.FIRE_STATION, BusinessType.POLICE_STATION,
    BusinessType.SPORTS_HALL, BusinessType.COMMUNITY_CENTRE,
    BusinessType.SCHOOL, BusinessType.CLINIC, BusinessType.TOWN_HALL
)

val BuildingType.isHome: Boolean
    get() = this == BuildingType.HOUSE || this == BuildingType.COTTAGE ||
            this == BuildingType.TERRACE || this == BuildingType.FLAT

val BuildingType.isPublicSpace: Boolean
    get() = this == BuildingType.PARK || this == BuildingType.PUB || this == BuildingType.CAFE

fun BuildingType.toBusinessType(): BusinessType? = when (this) {
    BuildingType.BAKERY           -> BusinessType.BAKERY
    BuildingType.CAFE             -> BusinessType.CAFE
    BuildingType.PUB              -> BusinessType.PUB
    BuildingType.RESTAURANT       -> BusinessType.RESTAURANT
    BuildingType.TAKEAWAY         -> BusinessType.TAKEAWAY
    BuildingType.GROCER           -> BusinessType.GROCER
    BuildingType.SUPERMARKET      -> BusinessType.SUPERMARKET
    BuildingType.HARDWARE         -> BusinessType.HARDWARE
    BuildingType.BOOKSHOP         -> BusinessType.BOOKSHOP
    BuildingType.TAILOR           -> BusinessType.TAILOR
    BuildingType.PHARMACY         -> BusinessType.PHARMACY
    BuildingType.WORKSHOP         -> BusinessType.WORKSHOP
    BuildingType.OFFICE           -> BusinessType.OFFICE
    BuildingType.HOTEL            -> BusinessType.HOTEL
    BuildingType.GARAGE           -> BusinessType.GARAGE
    BuildingType.SCHOOL           -> BusinessType.SCHOOL
    BuildingType.NURSERY          -> BusinessType.NURSERY
    BuildingType.LIBRARY          -> BusinessType.LIBRARY
    BuildingType.CLINIC           -> BusinessType.CLINIC
    BuildingType.HOSPITAL         -> BusinessType.HOSPITAL
    BuildingType.TOWN_HALL        -> BusinessType.TOWN_HALL
    BuildingType.FACTORY          -> BusinessType.FACTORY
    BuildingType.WAREHOUSE        -> BusinessType.WAREHOUSE
    BuildingType.FIRE_STATION     -> BusinessType.FIRE_STATION
    BuildingType.POLICE_STATION   -> BusinessType.POLICE_STATION
    BuildingType.SPORTS_HALL      -> BusinessType.SPORTS_HALL
    BuildingType.SWIMMING_POOL    -> BusinessType.SWIMMING_POOL
    BuildingType.COMMUNITY_CENTRE -> BusinessType.COMMUNITY_CENTRE
    BuildingType.CINEMA           -> BusinessType.CINEMA
    BuildingType.NIGHTCLUB        -> BusinessType.NIGHTCLUB
    // PARK, HOUSE, COTTAGE, TERRACE, FLAT, CEMETERY, VACANT — no business entity
    else -> null
}

enum class BuildingState(val label: String) {
    PLANNED("Planned"),
    UNDER_CONSTRUCTION("Under construction"),
    OCCUPIED("Occupied"),
    VACANT("Vacant"),
    DERELICT("Derelict"),
    CONDEMNED("Condemned"),
    DEMOLISHED("Demolished")
}

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
    /** District this building belongs to; null for legacy/unassigned buildings. */
    var districtId: Long? = null,
    /** Physical lifecycle state — drives visual rendering and vacancy progression. */
    var buildingState: BuildingState = BuildingState.OCCUPIED,
    /** Sim-minute timestamp when this building first had zero occupants/active business. */
    var vacantSinceAt: Long? = null,
    /** Sim-minute timestamp when construction is scheduled to complete (UNDER_CONSTRUCTION only). */
    var constructionCompletesAt: Long? = null,
    /** DevelopmentProject that created this building, if any. */
    var developmentProjectId: Long? = null,
    /** Short strings describing visible changes over time ("Extension added", "Sign repainted"). */
    val visibleChanges: MutableList<String> = mutableListOf(),
    /**
     * Ids of residents who have previously lived or worked here and have since departed.
     * Populated by [GoalSystem] when a resident moves out or leaves town.
     * Capped at [MAX_TENANT_HISTORY] — older entries evicted first.
     */
    val tenantHistory: MutableList<Long> = mutableListOf()
) {
    companion object {
        /** Maximum number of entries kept in [visibleChanges]. Older entries are evicted first. */
        const val MAX_VISIBLE_CHANGES = 6
        /** Maximum number of previous-tenant ids kept in [tenantHistory]. */
        const val MAX_TENANT_HISTORY = 20
    }

    fun containsTile(t: Tile): Boolean =
        t.x >= origin.x && t.x < origin.x + width && t.y >= origin.y && t.y < origin.y + height

    fun centre(): Tile = Tile(origin.x + width / 2, origin.y + height / 2)
}

enum class BusinessType(val label: String) {
    // Food & drink
    BAKERY("Bakery"), CAFE("Café"), PUB("Pub"), RESTAURANT("Restaurant"), TAKEAWAY("Takeaway"),
    // Retail
    GROCER("Grocer"), SUPERMARKET("Supermarket"), HARDWARE("Hardware shop"),
    BOOKSHOP("Bookshop"), TAILOR("Tailor"), PHARMACY("Pharmacy"),
    // Services / commercial
    WORKSHOP("Furniture workshop"), OFFICE("Offices"), HOTEL("Hotel"), GARAGE("Garage"),
    // Industry
    FACTORY("Factory"), WAREHOUSE("Warehouse"),
    // Civic
    SCHOOL("School"), NURSERY("Nursery"), LIBRARY("Library"),
    CLINIC("Clinic"), HOSPITAL("Hospital"), TOWN_HALL("Town hall"),
    FIRE_STATION("Fire station"), POLICE_STATION("Police station"),
    SPORTS_HALL("Sports hall"), SWIMMING_POOL("Swimming pool"),
    COMMUNITY_CENTRE("Community centre"), CINEMA("Cinema"), NIGHTCLUB("Nightclub"),
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
    // Default 1: a newly opened business is sole-trader only; capacity grows via
    // EconomySystem.expandBusiness. Callers that create established or civic businesses
    // (WorldGenerator, DevelopmentSystem) pass an explicit value and are unaffected.
    var employeeCapacity: Int = 1,
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
    val recentNetDaily: MutableList<Double> = mutableListOf(),
    /**
     * Consecutive days ending `daysInTrouble == 0` AND `demand` above
     * `EconomySystem.SUSTAINED_DEMAND_HIRING_THRESHOLD` — i.e. real, sustained trade, not one
     * lucky day. Economy Calibration Gate Phase 2 (2026-07-11, see docs/simulation-rules.md
     * "Staffing ramp, recovery ladder, formation gate, contract demand"). Drives the staffing-ramp
     * hiring gate in `EconomySystem.settleBusinessDay` — a new business (or one recovering from
     * trouble) must clear this streak before it's allowed to hire its next employee, rather than
     * hiring off a single good day. Safe-default 0 so existing checkpoints deserialize unchanged.
     */
    var consecutiveHealthyDemandDays: Int = 0,
    /**
     * Per-lever last-fired in-game-day index for the recovery ladder (`EconomySystem
     * .maybeAttemptRecovery`), keyed by a short lever name (e.g. "price_cut", "seek_finance") —
     * cooldown-gates each lever independently so the same response doesn't fire every single day
     * once a business is in trouble. Safe-default empty map. Economy Calibration Gate Phase 2
     * (2026-07-11).
     */
    val recoveryLeverLastFiredDay: MutableMap<String, Long> = mutableMapOf(),
    /**
     * Bounded, temporary debt taken on via the recovery ladder's "seek finance" lever — a genuine
     * business-side loan, separate from `Resident.debt` (owner-personal debt). Repaid automatically
     * out of `balance` in `dailySettlement`, same gentle-interest shape `Resident.debt` already
     * uses in that same function, reusing the pattern rather than inventing a parallel one. Safe-
     * default 0.0. Economy Calibration Gate Phase 2 (2026-07-11).
     */
    var loanBalance: Double = 0.0
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

enum class TileType {
    GRASS, ROAD, PATH, WATER, TREE, FLOWERS, PLAZA,
    /** Agricultural land — light, open feel on rural fringe and farm plots. */
    FARMLAND,
    /** Shallow inland water (lake, pond) — distinct from deep river WATER. */
    SHALLOW_WATER,
    /** Sand/shingle on lakeside or riverside banks. */
    SAND,
    /** Dense woodland — impassable, decorative tree cover on rural edges. */
    WOODLAND,
    /** Industrial hard-standing (tarmac yard, loading area). */
    HARDSTANDING
}

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
        return t == TileType.ROAD || t == TileType.PATH || t == TileType.PLAZA ||
               t == TileType.GRASS || t == TileType.FARMLAND || t == TileType.SAND ||
               t == TileType.HARDSTANDING
    }
}
