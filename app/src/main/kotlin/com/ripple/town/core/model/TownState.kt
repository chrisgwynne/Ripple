package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * The town as a living civilisation entity — an aggregate of what its residents,
 * businesses, districts and institutions collectively produce. Updated weekly by
 * [com.ripple.town.core.simulation.TownStateSystem]; consumed by other systems as
 * a lightweight, pre-computed read. Values are 0.0–100.0 unless documented otherwise.
 *
 * This is a *derived* entity, not an authoritative one. The truth lives in residents,
 * buildings, and businesses; TownState is a rolling summary of that truth at civilisation
 * scale, available without re-scanning everything on every read.
 */
@Serializable
data class TownState(
    // ─── Demographics ───────────────────────────────────────────────────────
    var population: Int = 0,
    var peakPopulation: Int = 0,
    var birthsLastYear: Int = 0,
    var deathsLastYear: Int = 0,
    var arrivalsLastYear: Int = 0,
    var departuresLastYear: Int = 0,

    // ─── Economy ────────────────────────────────────────────────────────────
    /** Average-wealth-derived prosperity index. 0 = destitute; 100 = thriving. */
    var prosperityIndex: Double = 50.0,
    /** Fraction of adults in employment (0–1). */
    var employmentRate: Double = 0.75,
    /** Health-of-open-businesses score (0–100). */
    var businessConfidence: Double = 50.0,
    /** Gini-like income inequality index (0 = equal; 1 = maximally unequal). */
    var incomeInequality: Double = 0.3,
    /** 100 = fully affordable; 0 = no homes available. */
    var housingAffordability: Double = 50.0,
    /** Occupied-homes fraction of all homes (0–1). High = pressure to build. */
    var housingPressure: Double = 0.3,

    // ─── Society ────────────────────────────────────────────────────────────
    /** Aggregate crime activity index from district crime rates (0–100). */
    var crimeIndex: Double = 10.0,
    /** Average educational attainment proxy (0–100). */
    var educationLevel: Double = 50.0,
    /** Average health-need satisfaction (0–100). */
    var healthIndex: Double = 70.0,
    /** Community loyalty and cohesion, derived from beliefs and sentiment (0–100). */
    var communitySpirit: Double = 50.0,
    /** Fraction of adults engaged in purposeful/community activity (0–1). */
    var volunteerRate: Double = 0.1,
    /** Cross-generational upward mobility rate (0 = no mobility; 1 = high mobility). */
    var socialMobility: Double = 0.5,

    // ─── Governance ─────────────────────────────────────────────────────────
    /** Aggregate INSTITUTIONAL_TRUST belief position mapped to 0–100. */
    var institutionalTrust: Double = 50.0,
    /** Stability of the political environment; blends approval rating history. */
    var politicalStability: Double = 50.0,
    /** Active/investigated corruption incidents as a severity score (0–100). */
    var corruptionLevel: Double = 0.0,

    // ─── Infrastructure & environment ───────────────────────────────────────
    /** Composite of town greenery, clean air and flood safety (0–100). */
    var environmentalQuality: Double = 60.0,
    /** Average building condition across all structures (0–100). */
    var infrastructureQuality: Double = 60.0,
    /** Technology productivity bonus mapped to 0–100. */
    var innovationIndex: Double = 0.0,

    // ─── Wellbeing ──────────────────────────────────────────────────────────
    /** Composite needs satisfaction (0–100). */
    var populationHappiness: Double = 50.0,
    /** ECONOMIC_OPTIMISM belief aggregate mapped to 0–100. */
    var hope: Double = 50.0,
    /** Estimated average years a resident lives; updated annually. */
    var lifeExpectancy: Double = 72.0,

    // ─── Longitudinal tracking ───────────────────────────────────────────────
    /** Population at the end of each sim year — for trend detection. Capped at 200 entries. */
    val yearlyPopulationHistory: MutableList<Int> = mutableListOf(),
    /** Prosperity index at the end of each sim year. Capped at 200 entries. */
    val yearlyProsperityHistory: MutableList<Double> = mutableListOf()
)
