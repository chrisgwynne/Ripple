package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.Business
import com.ripple.town.core.model.BusinessHealthState
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.DelayedEffect
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.Weather
import com.ripple.town.core.model.WorldState

/**
 * Businesses earn from footfall, pay wages daily, and can struggle, shrink or
 * close. Residents pay living costs daily and rent monthly. Everything routes
 * through events so consequences can chain.
 */
object EconomySystem {

    fun update(ctx: TickContext) {
        hourlyFootfall(ctx)
        // Daily settlement shortly before midnight.
        if (SimTime.hourOfDay(ctx.now) == 23 && SimTime.minuteOfDay(ctx.now) >= (23 * 60 + 50)) {
            dailySettlement(ctx)
        }
    }

    private fun hourlyFootfall(ctx: TickContext) {
        if (SimTime.minuteOfDay(ctx.now) % 60 != 0) return
        val hour = SimTime.hourOfDay(ctx.now)
        if (hour < 8 || hour > 21) return
        val dayOfWeek = SimTime.dayOfWeek(ctx.now)
        // Background residents produce abstract footfall.
        for (biz in ctx.state.businesses.values.sortedBy { it.id }) {
            if (!biz.open || biz.type in PUBLIC_SERVICES) continue
            val sectorMultiplier = hourlyDemandMultiplier(biz.type, hour, dayOfWeek, ctx.state.weather)
            // `biz.demand` (see `catchmentDemand`/`settleBusinessDay`) is now itself catchment-
            // derived rather than a free-floating reputation-only drift target, so this hourly
            // conversion into an actual customer count is unchanged in shape — only what feeds
            // `demand` changed, not how `demand` becomes footfall.
            val expected = (biz.demand / 100.0) * sectorMultiplier * 2.2
            val customers = ctx.rng.nextGaussianLike(expected, 1.2, 0.0, 6.0).toInt()
            if (customers > 0) {
                val spendEach = baseSpend(biz.type) * biz.priceLevel
                val revenue = customers * spendEach
                val cogs = revenue * cogsFraction(biz.type)
                biz.customersToday += customers
                biz.revenueToday += revenue
                // COGS lands the moment the sale happens (supplier cost of the goods actually
                // sold), same as revenue — rent/utilities/tax/wages remain end-of-day fixed costs,
                // settled once in `dailySettlement`/`settleBusinessDay` rather than hourly.
                biz.expensesToday += cogs
                biz.balance += revenue - cogs
            }
        }
    }

    // ============================================================
    // Sector demand shaping (2026-07-11) — see docs/simulation-rules.md
    // "Sector demand shaping". Replaces the old flat, type-agnostic `weatherFactor` with a
    // bounded per-BusinessType multiplier composed into `hourlyFootfall`'s existing `expected`
    // formula as a straight multiplicative replacement of that old factor — this function now
    // owns ALL of time-of-day shape, weekend effect, and weather sensitivity for a given type,
    // so weather is intentionally NOT applied a second time anywhere else in this file.
    // ============================================================

    /** Overall bounds every [hourlyDemandMultiplier] result is guaranteed to fall within, across
     *  the full 8..21 hour range, all 7 `SimTime.dayOfWeek` values, and all six [Weather] states.
     *  Kept loose enough that each type's own time/weekend/weather shape has real room to move,
     *  but tight enough that no type can ever swing to an absurd multiple of the old flat `2.2`
     *  baseline (previously `weatherFactor` alone ranged just 0.35..1.0). */
    const val DEMAND_MULTIPLIER_MIN = 0.3
    const val DEMAND_MULTIPLIER_MAX = 2.0

    /**
     * Bounded (see [DEMAND_MULTIPLIER_MIN]/[DEMAND_MULTIPLIER_MAX]) per-[BusinessType] demand
     * shape, composed of three independent factors multiplied together then clamped once at the
     * end:
     * - **Time-of-day** ([timeOfDayFactor]) — when this trade's custom actually arrives.
     * - **Weekend** ([weekendFactor]) — Friday/Saturday (`dayOfWeek` 5-6, per `SimTime`'s own
     *   "5-6 = weekend" comment) uplift or flattening.
     * - **Weather** ([weatherSensitivity]) — how exposed this trade is to bad weather, replacing
     *   the old one-size-fits-all `weatherFactor`; an indoor/destination trade like a PUB is far
     *   less dented by rain than an open GROCER queue or a BAKERY's outdoor-ish morning rush.
     *
     * Pure function of its four inputs — no `ctx.rng`, no hidden state — so it needs no
     * determinism test beyond the sweep in `SectorDemandProfileTest` (documented there too).
     */
    fun hourlyDemandMultiplier(type: BusinessType, hour: Int, dayOfWeek: Int, weather: Weather): Double {
        val isWeekend = dayOfWeek == 5 || dayOfWeek == 6
        val raw = timeOfDayFactor(type, hour) * weekendFactor(type, isWeekend) * weatherSensitivity(type, weather)
        return raw.coerceIn(DEMAND_MULTIPLIER_MIN, DEMAND_MULTIPLIER_MAX)
    }

    /**
     * Time-of-day shape per type, each centred so a "typical" hour lands near 1.0:
     * - **BAKERY/CAFE** — morning-peaked (breakfast/coffee trade): strong 8-10, tapering through
     *   the afternoon, quiet by evening.
     * - **PUB** — the mirror image: quiet by day, ramping hard from 17:00, peaking 19-21
     *   (evening/night trade).
     * - **GROCER/HARDWARE** — flat-ish all day, the "errand" trades — a small midday bump, no
     *   sharp peak either direction.
     * - **BOOKSHOP** — trades on leisure time, not footfall volume: gentle afternoon lean (people
     *   browsing on time off), never spikes as hard as a peak-driven trade.
     * - **TAILOR** — closer to office hours, mild midday/afternoon lean (appointment-driven).
     * - **FACTORY/WORKSHOP** — contract/order-driven, not retail footfall at all: flat 1.0
     *   regardless of hour, deliberately un-shaped.
     */
    private fun timeOfDayFactor(type: BusinessType, hour: Int): Double = when (type) {
        BusinessType.BAKERY -> when (hour) {
            in 8..9 -> 1.7
            in 10..11 -> 1.3
            in 12..14 -> 0.9
            in 15..17 -> 0.6
            else -> 0.4
        }
        BusinessType.CAFE -> when (hour) {
            in 8..10 -> 1.6
            in 11..13 -> 1.2
            in 14..16 -> 0.9
            in 17..18 -> 0.8
            else -> 0.5
        }
        BusinessType.PUB -> when (hour) {
            in 8..15 -> 0.4
            in 16..17 -> 0.7
            in 18..19 -> 1.4
            in 20..21 -> 1.8
            else -> 0.5
        }
        BusinessType.GROCER, BusinessType.HARDWARE -> when (hour) {
            in 12..14 -> 1.15
            else -> 1.0
        }
        BusinessType.BOOKSHOP -> when (hour) {
            in 13..17 -> 1.2
            in 18..19 -> 1.05
            else -> 0.85
        }
        BusinessType.TAILOR -> when (hour) {
            in 10..16 -> 1.15
            else -> 0.8
        }
        BusinessType.WORKSHOP, BusinessType.FACTORY -> 1.0
        else -> 1.0
    }

    /**
     * Weekend uplift/flattening per type (`isWeekend` = `SimTime.dayOfWeek` 5 or 6):
     * - **PUB/CAFE** — up on weekends (leisure/social trade concentrates there).
     * - **BAKERY/BOOKSHOP** — mild weekend uplift (weekend errands/browsing), smaller than
     *   pub/cafe's since it's not their defining trade.
     * - **GROCER/HARDWARE** — flatter: people still need groceries/hardware on a Tuesday, so the
     *   weekend bump is small.
     * - **TAILOR/FACTORY/WORKSHOP** — no weekend effect: appointment/contract-driven trades don't
     *   follow a leisure weekend pattern.
     */
    private fun weekendFactor(type: BusinessType, isWeekend: Boolean): Double {
        if (!isWeekend) return 1.0
        return when (type) {
            BusinessType.PUB -> 1.35
            BusinessType.CAFE -> 1.25
            BusinessType.BAKERY -> 1.15
            BusinessType.BOOKSHOP -> 1.15
            BusinessType.GROCER, BusinessType.HARDWARE -> 1.05
            BusinessType.TAILOR, BusinessType.WORKSHOP, BusinessType.FACTORY -> 1.0
            else -> 1.0
        }
    }

    /**
     * Weather sensitivity per type — replaces the old flat `weatherFactor` (which ranged
     * 0.35..1.0 identically for every type). Ranked from most to least exposed:
     * - **BAKERY/CAFE/GROCER** — most exposed: morning-rush/queue trades where customers are
     *   often walking or queuing outdoors; bad weather visibly suppresses the trip.
     * - **HARDWARE/BOOKSHOP/TAILOR** — moderately exposed: a real but smaller dent, these are
     *   more deliberate trips than a daily bakery/grocer run.
     * - **PUB** — the least weather-sensitive retail trade: destination/evening trade, people
     *   still go to the pub in the rain (arguably more so), so the floor here is much shallower
     *   than the shared old `weatherFactor`'s STORM=0.35.
     * - **FACTORY/WORKSHOP** — contract/order-driven, not footfall at all: essentially weather-
     *   insensitive (deliveries/orders don't care about drizzle), flat 1.0 across all weather.
     */
    private fun weatherSensitivity(type: BusinessType, weather: Weather): Double = when (type) {
        BusinessType.BAKERY, BusinessType.CAFE, BusinessType.GROCER -> when (weather) {
            Weather.CLEAR -> 1.05
            Weather.CLOUDY -> 0.95
            Weather.FOG -> 0.8
            Weather.RAIN -> 0.65
            Weather.SNOW -> 0.55
            Weather.STORM -> 0.35
        }
        BusinessType.HARDWARE, BusinessType.BOOKSHOP, BusinessType.TAILOR -> when (weather) {
            Weather.CLEAR -> 1.05
            Weather.CLOUDY -> 1.0
            Weather.FOG -> 0.9
            Weather.RAIN -> 0.8
            Weather.SNOW -> 0.7
            Weather.STORM -> 0.5
        }
        BusinessType.PUB -> when (weather) {
            Weather.CLEAR -> 1.05
            Weather.CLOUDY -> 1.0
            Weather.FOG -> 0.95
            Weather.RAIN -> 0.9
            Weather.SNOW -> 0.8
            Weather.STORM -> 0.65
        }
        BusinessType.WORKSHOP, BusinessType.FACTORY -> 1.0
        else -> when (weather) {
            Weather.CLEAR -> 1.0
            Weather.CLOUDY -> 0.9
            Weather.FOG -> 0.75
            Weather.RAIN -> 0.65
            Weather.SNOW -> 0.55
            Weather.STORM -> 0.35
        }
    }

    // ============================================================
    // Catchment/preference-based demand (Economy Calibration Gate, Phase 1, added 2026-07-11) —
    // see docs/simulation-rules.md "Unit economics + catchment demand". Real, computed each time
    // `settleBusinessDay` drifts `biz.demand` — not a persisted field, same "derive, don't
    // duplicate" discipline as `reserveRunway`/`BusinessHealthState`/`DebtState`.
    //
    // Deliberately scoped per the brief's own carve-outs for this pass: FACTORY/WORKSHOP get no
    // residential catchment at all (flat, small, contract-shaped placeholder — Phase 2 owns real
    // external/contract demand for these, see `CATCHMENT_RADIUS_TILES`'s doc). Opening-hours
    // variation beyond the existing hourlyFootfall 8-21 window is left to Phase 2 (not modelled
    // here — every open business is "open" for catchment purposes, matching hourlyFootfall's own
    // current all-hours-equal-eligibility assumption before this pass).
    // ============================================================

    /**
     * Sector-appropriate catchment radius in Manhattan tiles (reusing `Tile.manhattan`, the same
     * proximity pattern `PressureBridgeSystem.CRIME_PROXIMITY_TILES`/
     * `SeasonalEventSystem.FLOOD_PROXIMITY_TILES` already establish) — how far a household's home
     * building can be from this business and still plausibly be "local trade" for it.
     *
     * These are deliberately much larger than `CRIME_PROXIMITY_TILES`/`FLOOD_PROXIMITY_TILES`
     * (3 tiles): those measure "is this building basically next door to the incident", whereas a
     * shopping catchment has to span the actual town. `WorldGenerator`'s hand-authored map (44x34
     * tiles) puts every home on Rowan Street (y in 19..29) and every shop on High Street (y in
     * 6..11) — real measured Manhattan distances from a High Street shop to the nearest/farthest
     * Rowan Street homes run roughly 20..45 tiles, not single digits. A 3-10 tile radius (this
     * constant's first-draft value, before being checked against the real map) would exclude
     * essentially every household in town and starve every sector's catchment demand to near-
     * zero — exactly what the first `EconomyCalibrationReport` re-run after this change surfaced
     * (closure rate spiked to 158.5%, up from the 53.7% baseline, see docs/backlog.md's Phase 1
     * entry). Retuned against the map's real geometry rather than the smaller proximity-check
     * precedent, then re-verified by rerunning the calibration report.
     *
     * GROCER/BAKERY (daily-errand trades) get the tightest radius: real local convenience
     * shopping, but "tight" on this map's scale means "most of town, not the far edge".
     * CAFE/PUB (moderate — a deliberate but still local trip) sit in the middle. HARDWARE/
     * BOOKSHOP/TAILOR (occasional, considered trips) get the widest residential radius — people
     * will cross the whole town for these. WORKSHOP/FACTORY get 0: per the brief, these sectors
     * "should rely on contracts, not walk-in residents" — that's explicitly Phase 2's external/
     * contract-demand work, so Phase 1 does not fabricate a residential catchment for them here
     * (see [catchmentDemand]'s flat floor for these two types instead).
     */
    private fun catchmentRadiusTiles(type: BusinessType): Int = when (type) {
        BusinessType.GROCER, BusinessType.BAKERY -> 40
        BusinessType.CAFE, BusinessType.PUB -> 48
        BusinessType.HARDWARE, BusinessType.BOOKSHOP, BusinessType.TAILOR -> 60
        else -> 0
    }

    /** Minimum age (inclusive) for a resident to plausibly count as demand for a PUB — children
     *  and young teens aren't pub custom. A light filter, not a rigid rule system, per the brief. */
    private const val PUB_MIN_AGE = 18

    /** Rough per-resident spending-capacity/eligibility weight for this business's sector — the
     *  "age/eligibility filter" and "household income as spending-capacity proxy" the brief asks
     *  for, combined per-resident rather than per-household so a household's contribution scales
     *  with how many of its actual members are plausible customers. Returns 0.0 for a resident
     *  who plainly isn't this sector's customer (e.g. a child for a PUB) rather than a token
     *  fraction — a real filter, not a soft nudge, but never a rigid life-stage rule system beyond
     *  this one exclusion. */
    private fun residentEligibilityWeight(type: BusinessType, resident: Resident, now: Long): Double {
        val stage = resident.lifeStageAt(now)
        if (stage == LifeStage.CHILD) {
            // Children aren't independent customers for any sector modelled here — school-age
            // demand for BOOKSHOP/HARDWARE is real in the brief's list but "light", so it's
            // represented by TEENs (below) carrying a reduced, not zero, weight instead of
            // inventing a separate school-activity signal this pass.
            return 0.0
        }
        if (type == BusinessType.PUB && resident.ageAt(now) < PUB_MIN_AGE) return 0.0
        if (stage == LifeStage.TEEN) {
            // Teens are light, plausible customers for everyday/leisure trades (bookshop,
            // bakery, cafe) but not for a PUB (excluded above) or the "considered adult purchase"
            // trades — a gentle, sensible filter rather than a hard yes/no per sector.
            return when (type) {
                BusinessType.BOOKSHOP, BusinessType.BAKERY, BusinessType.CAFE -> 0.6
                BusinessType.GROCER -> 0.4
                else -> 0.25
            }
        }
        return 1.0
    }

    /**
     * Real catchment-derived demand target for [biz], replacing the old pure-reputation drift
     * target in `settleBusinessDay` (see that function's doc for how the two compose). Combines:
     * 1. **Nearby household count**, filtered by [residentEligibilityWeight] and distance-decayed
     *    within [catchmentRadiusTiles] (closer households contribute more, via [distanceWeight]).
     * 2. **Household wealth/savings** as a spending-capacity proxy — scaled in via
     *    [wealthWeight], so a HARDWARE/TAILOR business genuinely reads richer nearby households as
     *    more demand, not just more heads.
     * 3. **This business's own price/reputation standing** vs. sector-typical — reuses
     *    `BusinessRivalrySystem.standing`'s exact price-vs-reputation shape (cheaper-for-similar-
     *    reputation or better-reputed-for-similar-price both help) so the two systems agree on
     *    what "better" means, converted to a 0..1-ish multiplier via [standingMultiplier].
     * 4. **Same-sector competition split** ([competitionShare]) — when other open same-type
     *    businesses have overlapping catchments for a given household, this business only claims
     *    its distance/reputation/price-weighted share of that household, not the full amount.
     *
     * WORKSHOP/FACTORY (zero catchment radius, see [catchmentRadiusTiles]) get a flat, modest
     * baseline instead of a zero — Phase 2 owns real contract/external demand for these; a hard
     * zero here would starve them entirely before that lands, which isn't this pass's job to
     * cause. Result is scaled and clamped into the same 5..95 band `biz.demand` has always used.
     */
    fun catchmentDemand(ctx: TickContext, biz: Business): Double {
        val state = ctx.state
        val radius = catchmentRadiusTiles(biz.type)
        if (radius <= 0) return CONTRACT_SECTOR_BASELINE_DEMAND // WORKSHOP/FACTORY — see doc above

        val bizBuilding = state.building(biz.buildingId) ?: return biz.demand
        val bizCentre = bizBuilding.centre()
        val standingMult = standingMultiplier(biz)

        var weightedScore = 0.0
        for (hh in state.households.values) {
            val homeId = hh.homeBuildingId ?: continue
            val home = state.building(homeId) ?: continue
            val dist = home.centre().manhattan(bizCentre)
            if (dist > radius) continue
            val dWeight = distanceWeight(dist, radius)
            val eligibleResidents = hh.memberIds.mapNotNull { state.resident(it) }
                .filter { it.alive && it.inTown }
            if (eligibleResidents.isEmpty()) continue
            val hhEligibility = eligibleResidents.sumOf { residentEligibilityWeight(biz.type, it, ctx.now) }
            if (hhEligibility <= 0.0) continue
            val wWeight = wealthWeight(hh)
            val share = competitionShare(ctx, biz, hh, home, bizCentre)
            weightedScore += hhEligibility * dWeight * wWeight * share
        }

        val target = (weightedScore * CATCHMENT_SCORE_TO_DEMAND) * standingMult
        return target.coerceIn(5.0, 95.0)
    }

    /** Linear distance decay within the catchment radius — a household right at the business's
     *  doorstep (`dist` 0) counts fully, one at the radius edge counts at the floor
     *  [MIN_DISTANCE_WEIGHT] rather than dropping to exactly zero (a real catchment fades out, it
     *  doesn't have a hard cliff one tile before the boundary). */
    private fun distanceWeight(dist: Int, radius: Int): Double {
        if (radius <= 0) return 0.0
        val t = dist.toDouble() / radius.toDouble()
        return (1.0 - t).coerceIn(MIN_DISTANCE_WEIGHT, 1.0)
    }
    private const val MIN_DISTANCE_WEIGHT = 0.15

    /** Household wealth/savings as a rough spending-capacity proxy, scaled to a gentle
     *  0.6x-1.6x multiplier around [WEALTH_WEIGHT_MIDPOINT] (a household at the midpoint reads as
     *  1.0x — sector-typical spending capacity) so richer catchments genuinely mean more demand
     *  for a HARDWARE/TAILOR type business without one wealthy household dwarfing everyone else's
     *  contribution. Uses `Household.savings` plus a light per-member `Resident.wealth` pool as
     *  the two real income/wealth signals actually on these models. */
    private fun wealthWeight(hh: Household): Double {
        val pooled = hh.savings
        val ratio = pooled / WEALTH_WEIGHT_MIDPOINT
        return (0.6 + ratio * 0.5).coerceIn(0.6, 1.6)
    }
    private const val WEALTH_WEIGHT_MIDPOINT = 1_500.0

    /** This business's own price/reputation standing vs. sector-typical, converted from
     *  `BusinessRivalrySystem.standing`'s raw (reputation minus a price-deviation penalty) scale
     *  into a gentle 0.7x-1.3x demand multiplier — reuses that exact shape (see its doc: cheaper
     *  for similar reputation, or better-reputed for similar price, both help) so this system and
     *  business-vs-business rivalry agree on what "a better business" means, rather than each
     *  inventing its own separate notion of quality. */
    private fun standingMultiplier(biz: Business): Double {
        val standing = biz.reputation - (biz.priceLevel - 1.0) * 40.0 // BusinessRivalrySystem.standing's shape
        val normalised = (standing - 50.0) / 100.0 // 50 = perfectly average reputation, price at 1.0x
        return (1.0 + normalised * 0.6).coerceIn(0.7, 1.3)
    }

    /**
     * This business's distance/reputation/price-weighted share of household [hh]'s custom for
     * its sector — the "two cafés on opposite sides of town shouldn't split every customer
     * equally" mechanic. Finds every other OPEN same-type business whose catchment also reaches
     * [home] (i.e. real overlapping competition for this specific household, not a townwide
     * flat split), scores each candidate (this business included) by
     * `distanceWeight(dist-to-that-business, radius) * standingMultiplier(thatBusiness)`, and
     * returns this business's normalised fraction of that score. A lone business with no
     * same-type overlap for this household gets `1.0` (the full amount) — competition only
     * dilutes when there's a real nearby rival for this exact household, never as a townwide
     * blanket discount.
     */
    private fun competitionShare(ctx: TickContext, biz: Business, hh: Household, home: Building, bizCentre: Tile): Double {
        val state = ctx.state
        val radius = catchmentRadiusTiles(biz.type)
        val rivals = state.businesses.values.filter {
            it.open && it.type == biz.type && it.id != biz.id
        }
        if (rivals.isEmpty()) return 1.0

        val bizScore = distanceWeight(home.centre().manhattan(bizCentre), radius) * standingMultiplier(biz)
        var totalScore = bizScore
        var anyOverlap = false
        for (rival in rivals) {
            val rivalBuilding = state.building(rival.buildingId) ?: continue
            val rivalCentre = rivalBuilding.centre()
            val dist = home.centre().manhattan(rivalCentre)
            if (dist > radius) continue // this rival's catchment doesn't even reach this household
            anyOverlap = true
            totalScore += distanceWeight(dist, radius) * standingMultiplier(rival)
        }
        if (!anyOverlap || totalScore <= 0.0) return 1.0
        return (bizScore / totalScore).coerceIn(0.0, 1.0)
    }

    /** Scales the raw weighted household score into the same 5..95 `biz.demand` band — a single
     *  tuning knob, picked so a well-placed GROCER/BAKERY with a healthy nearby population and
     *  decent standing lands in the 55-75 "doing well" range rather than pegged at the ceiling,
     *  leaving room for [standingMultiplier]/competition to still move it meaningfully either way. */
    private const val CATCHMENT_SCORE_TO_DEMAND = 5.0

    /** Flat placeholder demand for WORKSHOP/FACTORY (zero residential catchment radius, see
     *  [catchmentRadiusTiles]) — a modest, sector-typical floor so these sectors aren't starved
     *  to nothing before Phase 2's real contract/external-demand model lands for them. Chosen at
     *  the same level `GoalSystem.openBusiness`/`succeedViaNewEntrepreneur` already start a new
     *  WORKSHOP's `demand`/`reputation` at (45.0) — a deliberately neutral, unexciting number,
     *  not tuned up or down to mask the fact that this sector's real demand model isn't built
     *  yet. */
    const val CONTRACT_SECTOR_BASELINE_DEMAND = 45.0

    /** How fast `biz.demand` drifts toward its [catchmentDemand] target each day — same 0.04
     *  rate the old pure-reputation drift used, kept unchanged so the *pace* of demand change
     *  isn't part of what this pass is retuning, only *what it drifts toward*. */
    private const val DEMAND_DRIFT_RATE = 0.04

    fun dailySettlement(ctx: TickContext) {
        val state = ctx.state
        // Wages and business expenses. `expenses` here is FIXED daily cost — overhead (a small
        // residual catch-all, see `overheads`), rent, utilities and wages. COGS was already
        // deducted hourly at the point of sale in `hourlyFootfall`, and tax is computed and
        // deducted last, on the day's actual profit (revenue-after-COGS minus everything above),
        // not on revenue — see docs/simulation-rules.md "Unit economics + catchment demand".
        for (biz in state.businesses.values.sortedBy { it.id }) {
            if (!biz.open) continue
            val staff = state.employeesOf(biz.id).sortedBy { it.id }
            val building = state.building(biz.buildingId)
            var expenses = overheads(biz.type) * WorldPressureMechanicMapper.overheadMultiplier(ctx.state)
            if (biz.type !in PUBLIC_SERVICES && building != null) {
                expenses += rentPerDay(building) + utilitiesPerDay(building, biz.type)
            }
            for (emp in staff) {
                val worker = state.resident(emp.residentId) ?: continue
                val pay = if (emp.reducedHours) emp.dailySalary * 0.6 else emp.dailySalary
                if (biz.type in PUBLIC_SERVICES || biz.balance > pay) {
                    worker.wealth += pay
                    if (biz.type !in PUBLIC_SERVICES) expenses += pay
                } else {
                    // Can't make payroll — pressure builds
                    worker.needs.stress += 6.0
                    worker.needs.financialSecurity -= 4.0
                }
            }
            biz.expensesToday += expenses
            biz.balance -= expenses
            if (biz.type !in PUBLIC_SERVICES) {
                // Tax last, on today's real profit only — never on a loss-making day (no tax
                // rebate mechanic here, just no tax charged), see `TAX_RATE`.
                val profitToday = biz.revenueToday - biz.expensesToday
                if (profitToday > 0.0) {
                    val tax = profitToday * TAX_RATE
                    biz.expensesToday += tax
                    biz.balance -= tax
                }
                recordNetDaily(biz, biz.revenueToday - biz.expensesToday)
            }
            settleBusinessDay(ctx, biz)
        }

        // Residents: living costs, debt interest, rent on the 1st
        val firstOfMonth = SimTime.dayOfMonth(state.time) == 1
        for (r in state.residentsOrdered()) {
            if (!r.inTown || r.detailLevel != com.ripple.town.core.model.DetailLevel.DETAILED) continue
            val stage = r.lifeStageAt(state.time)
            if (stage == com.ripple.town.core.model.LifeStage.CHILD) continue
            // Snapshot the classified DebtState *before* today's debt arithmetic touches wealth/
            // debt, so the transition check below compares "how serious was this yesterday" vs.
            // "how serious is this now" — see docs/simulation-rules.md "Debt states". The
            // underlying interest/repayment/wealth math immediately below is completely untouched
            // from before this feature — only how the *result* is read/communicated changed.
            val household = state.householdOf(r)
            val stateBefore = DebtSystem.classify(r, household)
            // National-layer tax pressure (Phase 4): a bounded 0.9x-1.1x multiplier on daily
            // living costs, the one clean hook `WorldPressureMechanicMapper` maps
            // TAX_RATE_RISES/TAX_RATE_EASES pressures onto — see docs/simulation-rules.md.
            r.wealth -= LIVING_COST_PER_DAY * WorldPressureMechanicMapper.livingCostMultiplier(state)
            if (r.debt > 0) {
                r.debt *= 1.0005 // gentle daily interest
                val repayment = minOf(r.debt, maxOf(0.0, (r.wealth - 100.0) * 0.05))
                r.wealth -= repayment
                r.debt -= repayment
                if (r.debt < 1.0) {
                    r.debt = 0.0
                    val e = ctx.emit(
                        EventType.FINANCIAL_RELIEF,
                        "${r.fullName} has finally cleared their debts.",
                        sourceResidentId = r.id, severity = 0.3, visibility = EventVisibility.PRIVATE
                    )
                    ConsequenceEngine.onEvent(ctx, e)
                }
            }
            if (r.wealth < 0) {
                r.debt += -r.wealth
                r.wealth = 0.0
            }
            // State-transition-aware crisis/relief signalling (replaces the old single flat-
            // threshold check): reuses the exact same EventType.DEBT_CRISIS / EventType
            // .FINANCIAL_RELIEF pair — both already safely handled with `else` fallbacks in
            // ImportanceScorer/NewspaperGenerator, confirmed before adding this — but now fires on
            // any tier-worsening/tier-improving transition, not just crossing the old flat
            // DEBT_CRISIS_THRESHOLD line once. The exact-zero "debts cleared" message above is left
            // as its own, more specific event and is not duplicated here.
            val stateAfter = DebtSystem.classify(r, household)
            if (stateAfter != stateBefore) {
                if (stateAfter.ordinal > stateBefore.ordinal &&
                    stateAfter.ordinal >= DebtState.CRISIS.ordinal &&
                    !r.awareness.contains("debt_crisis")
                ) {
                    // Worsened into CRISIS/INSOLVENT territory for the first time — same
                    // "debt_crisis" awareness marker as before, so PressureBridgeSystem's
                    // partner-strain bridge (which reads this exact string) keeps working
                    // unchanged.
                    r.awareness += "debt_crisis"
                    val description = if (stateAfter == DebtState.INSOLVENT) {
                        "${r.fullName} is insolvent — the debt has grown beyond any realistic way back."
                    } else {
                        "${r.fullName} is drowning in debt."
                    }
                    val e = ctx.emit(
                        EventType.DEBT_CRISIS, description,
                        sourceResidentId = r.id, severity = if (stateAfter == DebtState.INSOLVENT) 0.65 else 0.5,
                        visibility = EventVisibility.PRIVATE
                    )
                    ConsequenceEngine.onEvent(ctx, e)
                } else if (stateAfter.ordinal < stateBefore.ordinal &&
                    stateBefore.ordinal >= DebtState.CRISIS.ordinal &&
                    stateAfter.ordinal < DebtState.CRISIS.ordinal
                ) {
                    // Improved out of CRISIS/INSOLVENT into something more manageable — a genuine
                    // tier recovery, not just "cleared to exactly zero" (that path already has its
                    // own FINANCIAL_RELIEF event above). Clears the awareness marker so a future
                    // worsening back into CRISIS can fire again.
                    r.awareness.remove("debt_crisis")
                    val e = ctx.emit(
                        EventType.FINANCIAL_RELIEF,
                        "${r.fullName} has clawed their way back from the brink of financial ruin.",
                        sourceResidentId = r.id, severity = 0.35, visibility = EventVisibility.PRIVATE
                    )
                    ConsequenceEngine.onEvent(ctx, e)
                }
            }
        }
        if (firstOfMonth) {
            for (hh in state.households.values.sortedBy { it.id }) {
                val adults = hh.memberIds.mapNotNull { state.resident(it) }
                    .filter { it.inTown && it.lifeStageAt(state.time) != com.ripple.town.core.model.LifeStage.CHILD }
                if (adults.isEmpty()) continue
                val share = hh.monthlyRent / adults.size
                for (a in adults) {
                    a.wealth -= share
                    if (a.wealth < 0) { a.debt += -a.wealth; a.wealth = 0.0 }
                }
            }
        }
    }

    private fun settleBusinessDay(ctx: TickContext, biz: Business) {
        val state = ctx.state
        // Reputation follows served customers and owner condition.
        val served = biz.customersToday
        biz.reputation += when {
            served >= 8 -> 0.4
            served <= 1 -> -0.5
            else -> 0.0
        }
        biz.reputation = biz.reputation.coerceIn(5.0, 95.0)
        // Demand drifts towards a catchment-derived target (2026-07-11, Economy Calibration Gate
        // Phase 1 — see docs/simulation-rules.md "Unit economics + catchment demand"). This
        // REPLACES the old pure-reputation drift target (`biz.reputation`) as the thing `demand`
        // chases, rather than composing two separately-writing mechanisms: `catchmentDemand`
        // already weaves `reputation` (and `priceLevel`) into its own score, so reputation isn't
        // lost, it's now one ingredient of a real target instead of being the whole target.
        // `BusinessRivalrySystem`'s pairwise nudge and `PressureBridgeSystem`'s temporary demand
        // shifts still write to `biz.demand` directly afterwards, same as before — smaller
        // perturbations layered on top of this catchment-driven baseline, not replaced by it.
        if (biz.type !in PUBLIC_SERVICES) {
            val target = catchmentDemand(ctx, biz)
            biz.demand += (target - biz.demand) * DEMAND_DRIFT_RATE
            biz.demand = biz.demand.coerceIn(5.0, 95.0)
        }

        if (biz.type !in PUBLIC_SERVICES) {
            if (biz.balance < 0) {
                biz.daysInTrouble += 1
                if (biz.daysInTrouble == STRUGGLE_NOTICE_DAYS) {
                    val e = ctx.emit(
                        EventType.BUSINESS_STRUGGLING,
                        "${biz.name} is struggling to stay afloat.",
                        sourceResidentId = biz.ownerId, businessId = biz.id,
                        buildingId = biz.buildingId, severity = 0.45
                    )
                    ConsequenceEngine.onEvent(ctx, e)
                }
                if (biz.daysInTrouble >= CLOSURE_DAYS) {
                    closeBusiness(ctx, biz, "after ${biz.daysInTrouble} days in the red")
                } else {
                    // Staged health + real recovery action (2026-07-11, see docs/simulation-
                    // rules.md "Business health states"): additive, called after the existing
                    // trouble-escalation checks above rather than woven into them, so the
                    // pre-existing daysInTrouble/STRUGGLE_NOTICE_DAYS/CLOSURE_DAYS escalation
                    // logic above is untouched.
                    maybeAttemptRecovery(ctx, biz)
                }
            } else {
                biz.daysInTrouble = 0
                // Prosperous businesses may expand.
                if (biz.balance > EXPANSION_BALANCE && ctx.rng.nextBoolean(0.04)) {
                    expandBusiness(ctx, biz)
                }
                // Hiring when busy and under capacity.
                val staff = state.employeesOf(biz.id).size
                if (biz.demand > 62 && staff < biz.employeeCapacity && biz.balance > 1_500 && ctx.rng.nextBoolean(0.3)) {
                    hireSomeone(ctx, biz)
                }
            }
        }
        biz.customersToday = 0
        biz.revenueToday = 0.0
        biz.expensesToday = 0.0
    }

    fun closeBusiness(ctx: TickContext, biz: Business, why: String, causeIds: List<Long> = emptyList()) {
        val state = ctx.state
        biz.open = false
        biz.closedAt = ctx.now
        val building = state.building(biz.buildingId)
        building?.abandoned = true
        building?.visibleChanges?.add("Shutters down — closed")
        // Cause payload (added 2026-07-10, see docs/simulation-rules.md "Events, causes,
        // importance"): "immediate" is just `why` — already the daysInTrouble-based reason
        // string this function has always taken, genuinely descriptive on its own.
        // "underlying" only gets set when something *specific* and real explains the trouble
        // in the first place — a recent weather-damage hit to this building, a rivalry the
        // owner is party to, or the national fuel-price pressure being active while the books
        // were already bad. Never a placeholder: if none of those are on record, the key is
        // simply omitted rather than filled with invented text.
        val underlying = underlyingClosureCause(ctx, biz)
        val closure = ctx.emit(
            EventType.BUSINESS_CLOSED,
            "${biz.name} has closed its doors $why.",
            sourceResidentId = biz.ownerId, businessId = biz.id, buildingId = biz.buildingId,
            severity = 0.7, causeIds = causeIds,
            payload = buildMap {
                put("immediate_cause", why)
                if (underlying != null) put("underlying_cause", underlying)
            }
        )
        // Everyone employed there loses their job — direct causal children.
        for (emp in state.employeesOf(biz.id).sortedBy { it.id }) {
            emp.endedAt = ctx.now
            val worker = state.resident(emp.residentId) ?: continue
            worker.employmentId = null
            worker.occupation = "Unemployed"
            val jobLost = ctx.emit(
                EventType.JOB_LOST,
                "${worker.fullName} lost their job when ${biz.name} closed.",
                sourceResidentId = worker.id, businessId = biz.id,
                severity = 0.55, causeIds = listOf(closure.id)
            )
            ctx.addMemory(worker, MemoryType.LOSS, "The day ${biz.name} closed.", 55.0, jobLost.id)
            EmotionSystem.spawnEmotion(ctx, worker, com.ripple.town.core.model.EmotionType.ANXIETY, 60.0, jobLost.id)
            scheduleShock(ctx, worker, jobLost.id)
            ConsequenceEngine.onEvent(ctx, jobLost)
        }
        // Owner takes a financial and emotional hit.
        val owner = biz.ownerId?.let { state.resident(it) }
        if (owner != null) {
            owner.needs.stress += 18.0
            owner.needs.purpose -= 15.0
            owner.reputation -= 6.0
            if (biz.balance < 0) owner.debt += -biz.balance
            ctx.addMemory(owner, MemoryType.LOSS, "Losing ${biz.name} broke something in me.", 80.0, closure.id)
            EmotionSystem.spawnEmotion(ctx, owner, com.ripple.town.core.model.EmotionType.GRIEF, 70.0, closure.id)
            scheduleShock(ctx, owner, closure.id)
        }
        ConsequenceEngine.onEvent(ctx, closure)
        // Real succession, not permanent vacancy (2026-07-11, see docs/simulation-rules.md
        // "Business succession after closure") — additive tail call, the entire body above is
        // untouched. Rolls a weighted outcome now (not via DelayedEffectSystem: closeBusiness
        // already reads everything it needs — pre-closure employees, owner's family — at the
        // moment of closure; waiting days would mean tracking who used to work here separately,
        // duplicating what's already on hand right now).
        maybeAttemptSuccession(ctx, biz, closure.id)
    }

    /**
     * Schedules a bounded "in shock" window after sudden personal loss (job loss, business
     * closure — bereavement is scheduled the same way from `LifecycleSystem.die`). A raw
     * [DelayedEffect] of type [DelayedEffectType.SHOCK_PERIOD], deliberately never fired
     * meaningfully by [DelayedEffectSystem] (see that type's doc) — its presence in
     * `ctx.state.delayedEffects` for the window is the entire mechanism, read back via
     * [isInShock]. `earliestAt = now` so the window is "active" from the moment of loss, not
     * some days later; `latestAt` is the deterministic 3-7 day shock length, rolled once here
     * via `ctx.rng` so replays with the same seed always produce the same window. Composes
     * with `GoalSystem`'s job-loss `FIND_JOB` delay (task 2) and `DecisionSystem`'s low-key
     * activity nudge (task 1b) purely by both reading [isInShock] — no shared state beyond
     * this one record.
     */
    fun scheduleShock(ctx: TickContext, r: Resident, sourceEventId: Long) {
        val days = ctx.rng.nextDouble(SHOCK_MIN_DAYS, SHOCK_MAX_DAYS)
        ctx.state.delayedEffects += DelayedEffect(
            id = ctx.state.nextEffectId++,
            sourceEventId = sourceEventId,
            targetResidentId = r.id,
            type = DelayedEffectType.SHOCK_PERIOD,
            strength = 1.0,
            earliestAt = ctx.now,
            latestAt = ctx.now + (days * SimTime.MINUTES_PER_DAY).toLong()
        )
    }

    /** True while [r] has a live (un-applied, un-cancelled, in-window) shock record from
     *  [scheduleShock]. Cheap: bounded per-resident scan of `delayedEffects`, same cost shape
     *  as `DelayedEffectSystem`'s own per-tick filter. */
    fun isInShock(state: WorldState, r: Resident, now: Long): Boolean =
        state.delayedEffects.any {
            it.type == DelayedEffectType.SHOCK_PERIOD && it.targetResidentId == r.id &&
                !it.applied && !it.cancelled && now in it.earliestAt..it.latestAt
        }

    const val SHOCK_MIN_DAYS = 3.0
    const val SHOCK_MAX_DAYS = 7.0

    /**
     * A real, traceable underlying reason for a closure, when one is genuinely on record —
     * never invented. Checked in order of how directly each would plausibly explain a
     * business's books going bad: a recent weather-damage hit to this exact building, an
     * active rivalry the owner is party to (see `BusinessRivalrySystem`/`RIVALRY_FORMED`),
     * then the national fuel-price pressure if it's been pushing overheads up while this
     * business was already in trouble. Returns null — not a placeholder string — if none
     * apply, same discipline `CrimeSystem.mostRecentDesperationCause` already established.
     */
    private fun underlyingClosureCause(ctx: TickContext, biz: Business): String? {
        val state = ctx.state
        val recentWeatherHit = mostRecentBuildingEventOfType(ctx, EventType.WEATHER_DAMAGE, biz.buildingId)
        if (recentWeatherHit != null) return "storm damage to the building never fully recovered from"

        val ownerId = biz.ownerId
        if (ownerId != null) {
            val rivalry = state.relationships.values.firstOrNull {
                it.kind == com.ripple.town.core.model.RelationshipKind.RIVAL &&
                    (it.aId == ownerId || it.bId == ownerId)
            }
            if (rivalry != null) return "a long-running rivalry that never let up"
        }

        if (state.externalPressure?.kind == com.ripple.town.core.model.ExternalPressureKind.FUEL_PRICES_RISE) {
            return "rising fuel and delivery costs from beyond the town"
        }
        return null
    }

    /** The most recent event of a given type at a specific building, from this tick or the
     *  recent-events window. Mirrors `CrimeSystem.mostRecentEventOfType` — same bounded
     *  lookback, same "never invents a cause" discipline, kept local since `EconomySystem`
     *  has no crime-shaped context to share it with. */
    private fun mostRecentBuildingEventOfType(ctx: TickContext, type: EventType, buildingId: Long): com.ripple.town.core.model.WorldEvent? {
        ctx.newEvents.lastOrNull { it.type == type && it.buildingId == buildingId }?.let { return it }
        return ctx.state.recentEventIds.asReversed()
            .mapNotNull { ctx.eventIndex.get(it) }
            .firstOrNull { it.type == type && it.buildingId == buildingId }
    }

    private fun expandBusiness(ctx: TickContext, biz: Business) {
        biz.employeeCapacity += 1
        biz.balance -= 800.0
        val building = ctx.state.building(biz.buildingId)
        building?.upgradeLevel = (building?.upgradeLevel ?: 0) + 1
        building?.visibleChanges?.add("Extension added")
        building?.value = (building?.value ?: 0.0) + 6_000.0
        val e = ctx.emit(
            EventType.BUSINESS_EXPANDED,
            "${biz.name} is expanding — trade has been good.",
            sourceResidentId = biz.ownerId, businessId = biz.id, buildingId = biz.buildingId,
            severity = 0.4
        )
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun hireSomeone(ctx: TickContext, biz: Business) {
        val state = ctx.state
        // Prefer detailed unemployed adults actively looking; else promote a background resident.
        val candidate = state.residentsOrdered()
            .filter {
                it.inTown && it.employmentId == null &&
                    it.lifeStageAt(state.time) == com.ripple.town.core.model.LifeStage.ADULT &&
                    it.ageAt(state.time) < 66
            }
            .sortedByDescending { r ->
                (if (r.detailLevel == com.ripple.town.core.model.DetailLevel.DETAILED) 10.0 else 0.0) +
                    (if (r.goals.any { it.type == com.ripple.town.core.model.GoalType.FIND_JOB && it.status == com.ripple.town.core.model.GoalStatus.ACTIVE }) 20.0 else 0.0) +
                    r.skill(relevantSkillFor(biz.type)) / 10.0
            }
            .firstOrNull() ?: return
        hire(ctx, candidate, biz, roleFor(biz.type), causeIds = emptyList())
    }

    fun hire(ctx: TickContext, worker: com.ripple.town.core.model.Resident, biz: Business, role: String, causeIds: List<Long>) {
        val state = ctx.state
        if (state.employeesOf(biz.id).size >= biz.employeeCapacity) return
        // No prior EventType.JOB_STARTED memory is recorded anywhere else in the codebase (job
        // loss gets one via MemoryType.LOSS below in this file; this closes the matching gap for
        // a resident's very first job — a genuine personal milestone the brief calls out by
        // name). Captured before worker.employmentId is overwritten below.
        val isFirstJob = worker.employmentId == null
        val id = state.nextEmploymentId++
        state.employments[id] = com.ripple.town.core.model.Employment(
            id = id, residentId = worker.id, businessId = biz.id, role = role,
            dailySalary = salaryFor(biz.type), startedAt = ctx.now
        )
        worker.employmentId = id
        worker.occupation = role
        LifecycleSystem.promoteIfNeeded(ctx, worker, "hired at ${biz.name}")
        val started = ctx.emit(
            EventType.JOB_STARTED,
            "${worker.fullName} has started work at ${biz.name} as ${role.lowercase()}.",
            sourceResidentId = worker.id, businessId = biz.id, severity = 0.35, causeIds = causeIds
        )
        if (isFirstJob) {
            ctx.addMemory(
                worker, MemoryType.ACHIEVEMENT,
                "My first day at ${biz.name}, as ${role.lowercase()}.", 65.0, started.id
            )
        }
        worker.goals.filter { it.type == com.ripple.town.core.model.GoalType.FIND_JOB && it.status == com.ripple.town.core.model.GoalStatus.ACTIVE }
            .forEach { it.status = com.ripple.town.core.model.GoalStatus.COMPLETED; it.resolvedAt = ctx.now; it.progress = 1.0 }
        worker.needs.purpose += 15.0
        worker.needs.financialSecurity += 10.0
        ConsequenceEngine.onEvent(ctx, started)
    }

    fun baseSpend(type: BusinessType): Double = when (type) {
        BusinessType.BAKERY -> 4.5
        BusinessType.CAFE -> 6.0
        BusinessType.PUB -> 9.0
        BusinessType.GROCER -> 11.0
        BusinessType.HARDWARE -> 14.0
        BusinessType.BOOKSHOP -> 8.0
        BusinessType.TAILOR -> 18.0
        BusinessType.WORKSHOP -> 25.0
        BusinessType.FACTORY -> 40.0
        else -> 0.0
    }

    // ============================================================
    // Real unit economics (Economy Calibration Gate, Phase 1, added 2026-07-11) — see
    // docs/simulation-rules.md "Unit economics + catchment demand". Everything below this line
    // through `breakEvenCustomers` replaces "revenue is 100% margin" with a genuine cost
    // breakdown — COGS, rent, utilities, tax — that actually deducts from `Business.balance`
    // (wired in `hourlyFootfall`/`dailySettlement` above), not just a diagnostic number.
    // ============================================================

    /**
     * `overheads(type)` PRE-Phase-1 was the entire non-wage daily cost for a business (rent,
     * utilities, supplies, everything) folded into one flat number per sector. Now that rent,
     * utilities and COGS are all real, separately-modelled deductions (see [rentPerDay],
     * [utilitiesPerDay], [cogsFraction]), this is shrunk down to what it should honestly
     * represent: a small residual catch-all for insurance, licensing, admin and sundries that
     * doesn't cleanly belong to any of the other three — deliberately much smaller than before,
     * not zeroed out, since a real business does carry some cost bucket like this.
     */
    private fun overheads(type: BusinessType): Double = when (type) {
        BusinessType.FACTORY -> 12.0
        BusinessType.PUB -> 5.0
        BusinessType.CAFE -> 4.0
        BusinessType.GROCER -> 5.0
        BusinessType.BAKERY -> 4.0
        BusinessType.HARDWARE -> 4.0
        BusinessType.BOOKSHOP -> 3.0
        BusinessType.TAILOR -> 3.0
        BusinessType.WORKSHOP -> 4.0
        else -> 0.0
    }

    /**
     * Fraction of revenue that goes straight back out as supplier/material cost of the goods
     * actually sold — deducted hourly, at the point of sale, in `hourlyFootfall` (real COGS
     * tracks the sale, not the day). Real-world-plausible bands per the brief: food/drink trades
     * with perishable stock run higher (a café/bakery/pub/grocer genuinely spends 35-55% of
     * revenue on ingredients/stock), a workshop/factory has real material costs too, and
     * bookshop/tailor/hardware sit at a wholesale-goods 40-60% band. `PUBLIC_SERVICES`
     * (CLINIC/SCHOOL/TOWN_HALL) are untouched — never called for them, see `hourlyFootfall`'s
     * `biz.type in PUBLIC_SERVICES` skip which happens before `baseSpend`/COGS are ever computed.
     */
    // NOTE ON CALIBRATION (2026-07-11): the COGS fractions below were first drafted at the
    // brief's stated 30-55%/40-60% bands taken literally at the high end (BAKERY 0.45, GROCER/
    // HARDWARE 0.55, etc). A break-even sanity check against this game's OTHER pre-existing,
    // out-of-scope constants — `salaryFor`'s ~40-60/day per staff role and `WorldGenerator`'s
    // hand-authored 2-employee shops — showed that combination pushed `breakEvenCustomers` to
    // 25-56 customers/DAY for several sectors, against a realistic achievable ceiling of roughly
    // 17-22 customers/day at a healthy demand=60-75 (`hourlyFootfall`'s own
    // `(demand/100)*sectorMultiplier*2.2` shape, averaged over the 8-21 trading window) — i.e.
    // structurally unreachable break-even for most of the town, which is exactly what the first
    // full calibration re-run after adding COGS/rent/utilities/tax measured (closure rate spiked
    // to 158.5%, then 96.0% after widening catchment radii alone). Retuned to the LOW end of the
    // brief's own stated ranges (still genuine COGS, never zero) specifically because wages — the
    // single biggest fixed-cost line — are fixed, tuned, resident-side constants this Phase 1
    // pass does not own or revisit (the brief's own audit confirmed wages/living-costs were
    // healthy, not broken). Re-verified by rerunning the calibration report after this retune.
    fun cogsFraction(type: BusinessType): Double = when (type) {
        BusinessType.BAKERY -> 0.30   // flour, dairy, ingredients — bottom of the brief's 30-55% band
        BusinessType.CAFE -> 0.30     // beans, milk, food stock
        BusinessType.PUB -> 0.30      // drink stock — real-world pubs run some of the thinner COGS of any food/drink trade
        BusinessType.GROCER -> 0.35   // thin-margin resale, but not the ceiling of the 30-55% band
        BusinessType.HARDWARE -> 0.40 // wholesale cost of stocked goods, bottom of the 40-60% band
        BusinessType.BOOKSHOP -> 0.35 // wholesale cost of stock
        BusinessType.TAILOR -> 0.30   // fabric/materials, more labour-value-add than a pure reseller
        BusinessType.WORKSHOP -> 0.35 // timber/materials
        BusinessType.FACTORY -> 0.40  // raw materials at production scale
        else -> 0.0
    }

    /**
     * Daily rent, derived from the owning [Building]'s existing `value` — a small fraction per
     * day rather than an unrelated flat number, so a bigger/more valuable premises genuinely
     * costs more to occupy. [RENT_RATE_OF_VALUE_PER_DAY] = 0.00006/day is ~2.2%/year of the
     * building's `value`. NOTE ON CALIBRATION (2026-07-11): first drafted at 0.00035/day
     * (~12.8%/year, a textbook-plausible commercial yield in isolation) but retuned down after
     * the same break-even sanity check described in [cogsFraction]'s doc comment — with wages
     * fixed and out of scope, rent/utilities/COGS together have to fit under a much tighter
     * ceiling than a real-world "what's a plausible commercial yield" estimate alone would
     * suggest, or break-even becomes structurally unreachable. A typical unlisted business
     * building defaults to `Building.value` = 40,000.0 (see `Building`'s own default), giving a
     * baseline rent of ~2.4/day; the Joinery Works' explicit 70,000 value gives ~4.2/day.
     */
    const val RENT_RATE_OF_VALUE_PER_DAY = 0.00006
    fun rentPerDay(building: Building): Double = building.value * RENT_RATE_OF_VALUE_PER_DAY

    /**
     * Daily utilities, scaled by floor area (`width * height`, the footprint every [Building]
     * already carries) and a per-sector energy-intensity factor — a FACTORY/BAKERY genuinely
     * burns more power/heat per tile than a BOOKSHOP. [UTILITY_RATE_PER_TILE] is the base
     * cost-per-floor-tile-per-day before the sector multiplier. NOTE ON CALIBRATION
     * (2026-07-11): first drafted at 0.9/tile, retuned down to 0.2/tile for the same reason
     * documented on [RENT_RATE_OF_VALUE_PER_DAY] and [cogsFraction] — see those doc comments. A
     * typical 9-12 tile shop (matches `WorldGenerator`'s 3x3/4x3 slots) at a mid-intensity sector
     * now lands in the low single digits per day.
     */
    const val UTILITY_RATE_PER_TILE = 0.2
    fun utilitiesPerDay(building: Building, type: BusinessType): Double =
        building.width * building.height * UTILITY_RATE_PER_TILE * energyIntensity(type)

    /** Sector energy-intensity multiplier for [utilitiesPerDay] — ovens/kilns and production
     *  machinery (BAKERY, FACTORY, WORKSHOP) run hottest; PUB (fridges, cellar cooling, longer
     *  opening hours into the evening) is next; everyday retail (BOOKSHOP, TAILOR) is lowest. */
    private fun energyIntensity(type: BusinessType): Double = when (type) {
        BusinessType.FACTORY -> 2.2
        BusinessType.BAKERY -> 2.0
        BusinessType.WORKSHOP -> 1.6
        BusinessType.PUB -> 1.4
        BusinessType.CAFE -> 1.2
        BusinessType.GROCER -> 1.1
        BusinessType.HARDWARE -> 0.9
        BusinessType.TAILOR -> 0.8
        BusinessType.BOOKSHOP -> 0.7
        else -> 1.0
    }

    /** Flat percentage of the day's real profit (revenue after COGS, minus overhead/rent/
     *  utilities/wages) — deliberately simple per the brief ("this game doesn't need a real tax
     *  code"), never charged on a loss-making day (see `dailySettlement`'s `profitToday > 0.0`
     *  guard). Owner drawings are the existing "Owner" `Employment.dailySalary` (see `hire`'s
     *  `role = "Owner"` path in `GoalSystem.openBusiness`/`EconomySystem.succeedViaNewEntrepreneur`)
     *  — already deducted above in the wages loop as an ordinary salary line, not a second
     *  parallel mechanic; documented here so the "five real costs" list in the brief maps
     *  one-to-one onto actual code rather than needing an invented duplicate field. */
    const val TAX_RATE = 0.15

    /**
     * Bounded trailing-window history of `revenueToday - expensesToday` (i.e. what
     * `dailySettlement` actually applied to `balance` that day, after COGS/rent/utilities/tax/
     * wages), most-recent last, capped at [NET_DAILY_HISTORY_WINDOW] days — see
     * `Business.recentNetDaily`. Called once per business per day from `dailySettlement`, right
     * after tax is deducted and before `settleBusinessDay`'s troubled-day bookkeeping runs.
     */
    const val NET_DAILY_HISTORY_WINDOW = 14
    private fun recordNetDaily(biz: Business, net: Double) {
        biz.recentNetDaily += net
        while (biz.recentNetDaily.size > NET_DAILY_HISTORY_WINDOW) biz.recentNetDaily.removeAt(0)
    }

    /**
     * `balance / averageDailyNetBurn`, using the trailing window `Business.recentNetDaily`
     * (populated by [recordNetDaily]) rather than a single noisy day — a pure, always-current
     * computed value, not a persisted field (same "derive, don't duplicate" discipline
     * `BusinessHealthState`/`DebtState` already use this session). Semantics:
     * - No history yet (brand-new business, first day) → `Double.POSITIVE_INFINITY` if balance is
     *   non-negative (nothing burning yet, so no meaningful "days until zero"), or `0.0` if
     *   balance is already negative (no runway left to measure).
     * - Average net is break-even-or-better (>= 0) → `Double.POSITIVE_INFINITY`: at the current
     *   trend this business is not burning cash at all, so "days until it runs out" is undefined
     *   in the literal sense — reported as infinite runway rather than a misleadingly huge number.
     * - Otherwise → `balance / -averageNet`, clamped at 0 (a business already underwater doesn't
     *   get negative runway, it gets zero).
     */
    fun reserveRunway(biz: Business): Double {
        if (biz.recentNetDaily.isEmpty()) {
            return if (biz.balance >= 0.0) Double.POSITIVE_INFINITY else 0.0
        }
        val avgNet = biz.recentNetDaily.average()
        if (avgNet >= 0.0) return Double.POSITIVE_INFINITY
        return (biz.balance / -avgNet).coerceAtLeast(0.0)
    }

    /**
     * The customer count at which a day's revenue-after-COGS covers that day's fixed costs
     * (overhead + rent + utilities + tax-adjusted-for-profitability + wages) — a real, useful
     * diagnostic other systems (and Phase 2's formation gate) can read, using this sector's real
     * [baseSpend]/`biz.priceLevel`/[cogsFraction]. Tax is deliberately excluded from the
     * break-even fixed-cost sum: tax only applies to a profitable day (see `dailySettlement`'s
     * `profitToday > 0.0` guard), so "the point where profit turns positive" is exactly the
     * pre-tax break-even point by construction — including tax in the target would be circular
     * (how much tax is owed depends on how far past break-even the business already is).
     * Public-service businesses have no real customer-revenue model (see `PUBLIC_SERVICES`) so
     * this returns 0 for them rather than a meaningless divide.
     */
    fun breakEvenCustomers(ctx: TickContext, biz: Business): Int {
        if (biz.type in PUBLIC_SERVICES) return 0
        val building = ctx.state.building(biz.buildingId) ?: return 0
        val staff = ctx.state.employeesOf(biz.id)
        val wages = staff.sumOf { if (it.reducedHours) it.dailySalary * 0.6 else it.dailySalary }
        val fixedCosts = overheads(biz.type) * WorldPressureMechanicMapper.overheadMultiplier(ctx.state) +
            rentPerDay(building) + utilitiesPerDay(building, biz.type) + wages
        val marginPerCustomer = baseSpend(biz.type) * biz.priceLevel * (1.0 - cogsFraction(biz.type))
        if (marginPerCustomer <= 0.0) return Int.MAX_VALUE
        return kotlin.math.ceil(fixedCosts / marginPerCustomer).toInt().coerceAtLeast(0)
    }

    fun salaryFor(type: BusinessType): Double = when (type) {
        BusinessType.FACTORY -> 46.0
        BusinessType.CLINIC -> 52.0
        BusinessType.SCHOOL -> 54.0
        BusinessType.TOWN_HALL -> 50.0
        else -> 40.0
    }

    fun roleFor(type: BusinessType): String = when (type) {
        BusinessType.BAKERY -> "Bakery assistant"
        BusinessType.CAFE -> "Café worker"
        BusinessType.PUB -> "Bar worker"
        BusinessType.GROCER -> "Grocery assistant"
        BusinessType.HARDWARE -> "Shop assistant"
        BusinessType.BOOKSHOP -> "Bookseller"
        BusinessType.TAILOR -> "Seamster"
        BusinessType.WORKSHOP -> "Workshop hand"
        BusinessType.FACTORY -> "Joinery worker"
        BusinessType.CLINIC -> "Clinic assistant"
        BusinessType.SCHOOL -> "Classroom assistant"
        BusinessType.TOWN_HALL -> "Clerk"
    }

    private fun relevantSkillFor(type: BusinessType): com.ripple.town.core.model.SkillType = when (type) {
        BusinessType.BAKERY, BusinessType.CAFE -> com.ripple.town.core.model.SkillType.COOKING
        BusinessType.WORKSHOP, BusinessType.FACTORY -> com.ripple.town.core.model.SkillType.CARPENTRY
        BusinessType.HARDWARE -> com.ripple.town.core.model.SkillType.REPAIR
        BusinessType.CLINIC -> com.ripple.town.core.model.SkillType.MEDICINE
        BusinessType.SCHOOL -> com.ripple.town.core.model.SkillType.TEACHING
        BusinessType.TOWN_HALL -> com.ripple.town.core.model.SkillType.POLITICS
        else -> com.ripple.town.core.model.SkillType.SOCIAL
    }

    // ============================================================
    // Business health states (2026-07-11) — see docs/simulation-rules.md
    // "Business health states, recovery and succession".
    // ============================================================

    /**
     * Pure classification of [biz]'s live distress, derived from `daysInTrouble` against the
     * existing `STRUGGLE_NOTICE_DAYS`/`CLOSURE_DAYS` constants — no new persisted field, same
     * "derive, don't duplicate" approach the concurrent debt-state work takes for residents. A
     * closed business (`!biz.open`) has no meaningful health state to report here — callers
     * should check `biz.open` themselves first, matching how `Building.abandoned` is read
     * directly elsewhere rather than through this enum.
     */
    fun healthStateOf(biz: Business): BusinessHealthState {
        val d = biz.daysInTrouble
        return when {
            d <= 0 -> BusinessHealthState.HEALTHY
            d < STRUGGLE_NOTICE_DAYS -> BusinessHealthState.PRESSURED
            d < CLOSURE_DAYS / 2 -> BusinessHealthState.AT_RISK
            d < CLOSURE_DAYS - 2 -> BusinessHealthState.STRUGGLING
            else -> BusinessHealthState.CRITICAL
        }
    }

    /** Daily chance a DETAILED owner at AT_RISK-or-worse actually takes a recovery action —
     *  bounded and low, matching every other daily system's gentle pacing (e.g.
     *  `BusinessSuccessionSystem.SUCCESSION_CHANCE_PER_DAY`). Rolled independently for the two
     *  action kinds below, so an owner isn't stuck picking exactly one forever. */
    const val RECOVERY_ACTION_CHANCE_PER_DAY = 0.10

    /** How much a price-cut recovery action reduces `priceLevel` by, and the floor it respects —
     *  reuses `PriceDriftSystem.PRICE_LEVEL_MIN` rather than inventing a second bound. */
    const val RECOVERY_PRICE_CUT = 0.08

    /** A real trade-off, not a free save: cutting prices this deliberately (as opposed to
     *  `PriceDriftSystem`'s small ambient drift) also costs some reputation — a "why's it so
     *  cheap" wobble — recovering naturally over time via `settleBusinessDay`'s own reputation
     *  drift once trouble passes. */
    const val RECOVERY_PRICE_CUT_REPUTATION_COST = 3.0

    /**
     * A bounded, low-probability-per-day recovery action for a DETAILED owner whose business has
     * reached [BusinessHealthState.AT_RISK] or worse — see docs/simulation-rules.md "Business
     * health states". Two real mechanical options, each a genuine trade-off:
     * - **Price cut**: `priceLevel` drops by [RECOVERY_PRICE_CUT] (floored at
     *   `PriceDriftSystem.PRICE_LEVEL_MIN`) to chase demand — cheaper goods draw more customers
     *   (`hourlyFootfall`'s `spendEach = baseSpend * priceLevel` directly rewards this), but at a
     *   real cost: lower revenue per customer, and a small reputation hit for looking desperate.
     * - **Early layoff**: the most recently hired employee is let go now, before the business is
     *   forced to close outright — cuts daily wage overhead immediately (this business's single
     *   biggest controllable expense per `dailySettlement`'s wages loop), at the real cost of lost
     *   `employeeCapacity` headroom and a demand hit (fewer staff, worse service) plus the laid-
     *   off worker's own job loss. Reuses the same `JOB_LOST`/memory/emotion/shock shape
     *   `closeBusiness`'s worker-loss loop already establishes — one real code path for "a job
     *   here ended", not a second parallel one.
     * Only one action fires per business per day at most (price cut is tried first; if it
     * doesn't fire, layoff gets an independent roll) — never both, so a single bad day doesn't
     * get double-mitigated.
     */
    private fun maybeAttemptRecovery(ctx: TickContext, biz: Business) {
        val state = ctx.state
        if (healthStateOf(biz) < BusinessHealthState.AT_RISK) return
        val owner = biz.ownerId?.let { state.resident(it) } ?: return
        if (!owner.alive || !owner.inTown || owner.detailLevel != DetailLevel.DETAILED) return

        if (ctx.rng.nextBoolean(RECOVERY_ACTION_CHANCE_PER_DAY)) {
            attemptPriceCutRecovery(ctx, biz, owner)
            return
        }
        if (ctx.rng.nextBoolean(RECOVERY_ACTION_CHANCE_PER_DAY)) {
            attemptLayoffRecovery(ctx, biz, owner)
        }
    }

    private fun attemptPriceCutRecovery(ctx: TickContext, biz: Business, owner: Resident) {
        val before = biz.priceLevel
        val after = (before - RECOVERY_PRICE_CUT).coerceAtLeast(PriceDriftSystem.PRICE_LEVEL_MIN)
        if (after >= before) return // already at the floor — nothing real to do
        biz.priceLevel = after
        biz.reputation = (biz.reputation - RECOVERY_PRICE_CUT_REPUTATION_COST).coerceIn(5.0, 95.0)
        owner.needs.stress += 4.0 // a deliberate, anxious call, not a free lever
        val e = ctx.emit(
            EventType.PRICES_SHIFTED,
            "${biz.name} has slashed prices, trying to chase back some trade.",
            sourceResidentId = owner.id, businessId = biz.id, buildingId = biz.buildingId,
            severity = 0.3, visibility = EventVisibility.PUBLIC
        )
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun attemptLayoffRecovery(ctx: TickContext, biz: Business, owner: Resident) {
        val state = ctx.state
        val staff = state.employeesOf(biz.id).sortedByDescending { it.startedAt }
        val toLayOff = staff.firstOrNull { it.residentId != owner.id } ?: return
        val worker = state.resident(toLayOff.residentId) ?: return

        toLayOff.endedAt = ctx.now
        worker.employmentId = null
        worker.occupation = "Unemployed"
        biz.employeeCapacity = (biz.employeeCapacity - 1).coerceAtLeast(1)
        biz.demand = (biz.demand - 4.0).coerceIn(5.0, 95.0)

        val jobLost = ctx.emit(
            EventType.JOB_LOST,
            "${worker.fullName} was let go early as ${biz.name} tries to cut costs and stay open.",
            sourceResidentId = worker.id, businessId = biz.id,
            severity = 0.5
        )
        ctx.addMemory(worker, MemoryType.LOSS, "Let go from ${biz.name} before it even closed.", 50.0, jobLost.id)
        EmotionSystem.spawnEmotion(ctx, worker, com.ripple.town.core.model.EmotionType.ANXIETY, 55.0, jobLost.id)
        scheduleShock(ctx, worker, jobLost.id)
        ConsequenceEngine.onEvent(ctx, jobLost)
    }

    // ============================================================
    // Succession after closure (2026-07-11) — see docs/simulation-rules.md
    // "Business succession after closure". A second, closure-triggered succession path
    // alongside `BusinessSuccessionSystem`'s voluntary-retirement handoff; deliberately kept
    // local here rather than merged into that object, since the trigger (forced closure vs. a
    // living owner choosing to step back) and the outcome shape (four weighted possibilities
    // including "stays vacant" vs. one guaranteed heir handoff) are genuinely different.
    // ============================================================

    /** Weight floor/ceiling knobs, not literal probabilities — see [successionWeights] for how
     *  they combine per closure. Kept small and named so the weighting logic reads plainly. */
    private const val BASE_VACANT_WEIGHT = 1.0
    private const val BASE_INHERIT_WEIGHT = 0.6
    private const val BASE_EMPLOYEE_BUYOUT_WEIGHT = 0.5
    private const val BASE_NEW_ENTREPRENEUR_WEIGHT = 0.4

    private enum class SuccessionOutcome { FAMILY_INHERITANCE, EMPLOYEE_BUYOUT, NEW_ENTREPRENEUR, STAYS_VACANT }

    /**
     * Rolls a real succession outcome immediately after [biz] closes — the mechanism that stops
     * the town accumulating permanently-dead buildings over a long run (previously only
     * `GoalSystem.openBusiness` ever un-abandoned a building, and only by chance if a resident
     * happened to pursue `START_BUSINESS` and randomly picked this exact vacant building).
     *
     * Four weighted outcomes, plausibility-weighted from what's actually on record for this
     * closure — never a flat/uniform roll:
     * - **Family inheritance**: an in-town adult child or partner of the outgoing owner takes it
     *   over — mirrors `BusinessSuccessionSystem.readyHeir`'s family-first pattern, but does not
     *   require the heir to already be employed here (a business that just closed has no active
     *   staff to check that against).
     * - **Employee buyout**: someone who worked here before it closed (from this closure's own
     *   `JOB_LOST` batch, read via `state.employeesOf` *before* those employments end, so kept as
     *   a local snapshot passed in) buys it.
     * - **New entrepreneur**: a fresh resident opens something new here — reuses
     *   `GoalSystem.STARTUP_CAPITAL`/`BuildingType.WORKSHOP` conventions but is deliberately a
     *   lighter direct call, not a re-invocation of `GoalSystem.openBusiness` itself (that
     *   function is keyed off a specific resident's in-progress `START_BUSINESS` goal, which
     *   doesn't exist for a bystander picking up a freshly-closed shop).
     * - **Stays vacant**: the pre-existing permanent-abandonment behaviour — kept as a real,
     *   still-likely outcome, not eliminated.
     *
     * Weighting: a business that closed quickly with decent reputation intact reads as "the
     * trade was fine, the specific run of it wasn't" — family/employee takeover more plausible.
     * One that limped along at CRITICAL for a long time before finally closing reads as "this
     * spot doesn't work" — vacancy weighted higher. `daysInTrouble` at the moment of closure
     * (already sitting at `>= CLOSURE_DAYS`) and `biz.reputation` are the two real signals used;
     * nothing here is invented beyond what's already on the business/owner.
     */
    private fun maybeAttemptSuccession(ctx: TickContext, biz: Business, closureEventId: Long) {
        val state = ctx.state
        val building = state.building(biz.buildingId) ?: return
        if (!building.abandoned) return // something else already reused the building this tick

        // Snapshot pre-closure staff before deciding — closeBusiness already ended their
        // employments by the time this runs, but their Employment records (and endedAt == now)
        // are still queryable, so "worked here right up until it closed" is a real, cheap check.
        val formerEmployeeIds = state.employments.values
            .filter { it.businessId == biz.id && it.endedAt == ctx.now }
            .sortedBy { it.id }
            .map { it.residentId }

        val ownerId = biz.ownerId
        val heir = ownerId?.let { findClosureHeir(ctx, it) }
        val buyer = formerEmployeeIds.mapNotNull { state.resident(it) }
            .firstOrNull { it.alive && it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }

        val longTroubled = biz.daysInTrouble >= CLOSURE_DAYS + 10 // limped along well past the cutoff
        val repDecent = biz.reputation >= 45.0

        val weights = linkedMapOf(
            SuccessionOutcome.FAMILY_INHERITANCE to (if (heir != null) BASE_INHERIT_WEIGHT * (if (repDecent) 1.4 else 1.0) else 0.0),
            SuccessionOutcome.EMPLOYEE_BUYOUT to (if (buyer != null) BASE_EMPLOYEE_BUYOUT_WEIGHT * (if (repDecent) 1.3 else 1.0) else 0.0),
            SuccessionOutcome.NEW_ENTREPRENEUR to BASE_NEW_ENTREPRENEUR_WEIGHT * (if (longTroubled) 0.5 else 1.0),
            SuccessionOutcome.STAYS_VACANT to BASE_VACANT_WEIGHT * (if (longTroubled) 1.8 else 1.0)
        )
        val total = weights.values.sum()
        if (total <= 0.0) return
        var roll = ctx.rng.nextDouble(0.0, total)
        var chosen = SuccessionOutcome.STAYS_VACANT
        for ((outcome, weight) in weights) {
            if (weight <= 0.0) continue
            if (roll < weight) { chosen = outcome; break }
            roll -= weight
        }

        when (chosen) {
            SuccessionOutcome.FAMILY_INHERITANCE -> heir?.let { succeedViaInheritance(ctx, biz, building, it, closureEventId) }
            SuccessionOutcome.EMPLOYEE_BUYOUT -> buyer?.let { succeedViaEmployeeBuyout(ctx, biz, building, it, closureEventId) }
            SuccessionOutcome.NEW_ENTREPRENEUR -> succeedViaNewEntrepreneur(ctx, building, closureEventId)
            SuccessionOutcome.STAYS_VACANT -> Unit // the pre-existing behaviour — genuinely do nothing
        }
    }

    /** An in-town, alive adult child, or otherwise the in-town adult partner, of the outgoing
     *  owner — same family-first order `BusinessSuccessionSystem.readyHeir` uses, but without
     *  requiring prior employment at this specific business (there's no staff left to check
     *  that against once it's already closed). */
    private fun findClosureHeir(ctx: TickContext, ownerId: Long): Resident? {
        val state = ctx.state
        val owner = state.resident(ownerId) ?: return null
        owner.childIds.mapNotNull { state.resident(it) }
            .firstOrNull { it.alive && it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            ?.let { return it }
        val partnerId = owner.partnerId ?: return null
        val partner = state.resident(partnerId) ?: return null
        return partner.takeIf { it.alive && it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
    }

    private fun succeedViaInheritance(ctx: TickContext, biz: Business, building: Building, heir: Resident, closureEventId: Long) {
        reopenBusiness(ctx, biz, building, heir)
        val e = ctx.emit(
            EventType.BUSINESS_SUCCESSION,
            "${heir.fullName} has reopened ${biz.name}, picking up where family left off.",
            sourceResidentId = heir.id, businessId = biz.id, buildingId = building.id,
            severity = 0.4, causeIds = listOf(closureEventId), visibility = EventVisibility.PUBLIC
        )
        ctx.addMemory(heir, MemoryType.ACHIEVEMENT, "The day I reopened ${biz.name}.", 75.0, e.id)
        ConsequenceEngine.onEvent(ctx, e)
    }

    private fun succeedViaEmployeeBuyout(ctx: TickContext, biz: Business, building: Building, buyer: Resident, closureEventId: Long) {
        reopenBusiness(ctx, biz, building, buyer)
        val e = ctx.emit(
            EventType.BUSINESS_SUCCESSION,
            "${buyer.fullName}, who used to work there, has bought and reopened ${biz.name}.",
            sourceResidentId = buyer.id, businessId = biz.id, buildingId = building.id,
            severity = 0.4, causeIds = listOf(closureEventId), visibility = EventVisibility.PUBLIC
        )
        ctx.addMemory(buyer, MemoryType.ACHIEVEMENT, "Buying ${biz.name} myself — I always knew this trade.", 75.0, e.id)
        ConsequenceEngine.onEvent(ctx, e)
    }

    /** Shared reopening mechanics for both family-inheritance and employee-buyout: the same
     *  [Business] record and building are reused (name, type, history all carry over — this is a
     *  handoff, not a fresh start), balance is reset to a modest working float so the new owner
     *  isn't inheriting the old debt, and trouble/price distortions from the old run are cleared. */
    private fun reopenBusiness(ctx: TickContext, biz: Business, building: Building, newOwner: Resident) {
        biz.ownerId = newOwner.id
        biz.open = true
        biz.closedAt = null
        biz.openedAt = ctx.now
        biz.balance = biz.balance.coerceAtLeast(0.0).coerceAtMost(HANDOFF_STARTING_BALANCE) + HANDOFF_STARTING_BALANCE * 0.5
        biz.daysInTrouble = 0
        biz.priceLevel = 1.0
        biz.reputation = biz.reputation.coerceIn(30.0, 60.0) // some standing carries over, but softened
        building.abandoned = false
        building.ownerId = newOwner.id
        building.visibleChanges += "Reopened under new ownership"
    }

    /** A fresh resident opens something new in the shell of a business that stayed shut long
     *  enough nobody close to it wanted it — reuses `GoalSystem.STARTUP_CAPITAL`/
     *  `BuildingType.WORKSHOP` conventions rather than inventing new ones, but is a direct,
     *  lighter call: no `START_BUSINESS` goal exists for a bystander picking up someone else's
     *  old shop, so this does not (and should not) re-invoke `GoalSystem.openBusiness`. */
    private fun succeedViaNewEntrepreneur(ctx: TickContext, building: Building, closureEventId: Long) {
        val state = ctx.state
        val founder = state.residentsOrdered()
            .filter {
                it.inTown && it.alive && it.detailLevel == DetailLevel.DETAILED &&
                    it.employmentId == null && it.lifeStageAt(ctx.now) == LifeStage.ADULT &&
                    it.ageAt(ctx.now) < 66 && it.wealth >= GoalSystem.STARTUP_CAPITAL &&
                    it.personality.ambition > 0.5
            }
            .sortedByDescending { it.skill(com.ripple.town.core.model.SkillType.BUSINESS) }
            .firstOrNull() ?: return

        founder.wealth -= GoalSystem.STARTUP_CAPITAL
        building.type = BuildingType.WORKSHOP
        building.abandoned = false
        building.ownerId = founder.id
        building.condition = 65.0
        building.visibleChanges += "New owner, doors open again"

        val biz = Business(
            id = state.nextBusinessId++,
            buildingId = building.id,
            name = "${founder.surname}'s Workshop",
            type = BusinessType.WORKSHOP,
            ownerId = founder.id,
            balance = GoalSystem.STARTUP_CAPITAL,
            demand = 45.0,
            reputation = 45.0,
            employeeCapacity = 2,
            openedAt = ctx.now
        )
        state.businesses[biz.id] = biz
        val emp = com.ripple.town.core.model.Employment(
            id = state.nextEmploymentId++, residentId = founder.id, businessId = biz.id,
            role = "Owner", dailySalary = 45.0, startedAt = ctx.now
        )
        state.employments[emp.id] = emp
        founder.employmentId = emp.id
        founder.occupation = "Owner, ${biz.name}"

        val e = ctx.emit(
            EventType.BUSINESS_OPENED,
            "${founder.fullName} has taken over the old premises and opened ${biz.name}.",
            sourceResidentId = founder.id, businessId = biz.id, buildingId = building.id,
            severity = 0.4, causeIds = listOf(closureEventId), visibility = EventVisibility.PUBLIC
        )
        ctx.addMemory(founder, MemoryType.ACHIEVEMENT, "The day I opened ${biz.name}.", 80.0, e.id)
        ConsequenceEngine.onEvent(ctx, e)
    }

    /** Working float a family-inheritance/employee-buyout handoff starts with, softening (not
     *  erasing) the old run's debt — smaller than `GoalSystem.STARTUP_CAPITAL` since the
     *  building/reputation/customer base already exist, unlike a from-scratch opening. */
    const val HANDOFF_STARTING_BALANCE = 250.0

    const val LIVING_COST_PER_DAY = 9.0
    const val DEBT_CRISIS_THRESHOLD = 2_000.0
    const val STRUGGLE_NOTICE_DAYS = 5
    const val CLOSURE_DAYS = 18
    const val EXPANSION_BALANCE = 9_000.0
    val PUBLIC_SERVICES = setOf(BusinessType.CLINIC, BusinessType.SCHOOL, BusinessType.TOWN_HALL)
}
