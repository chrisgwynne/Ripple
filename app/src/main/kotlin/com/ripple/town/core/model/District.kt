package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * The five procedurally generated neighbourhoods that make up the expanded town.
 * Each has a distinct character, land-use mix, and wealth profile — used by
 * EconomySystem catchment calculations, CrimeSystem incidence rates, and the UI
 * district overlay. See [com.ripple.town.core.simulation.MapGenerator].
 */
enum class DistrictType(val label: String) {
    /** Original hand-authored Ashcombe: High Street, civic core, 12 Rowan St homes. */
    TOWN_CENTRE("Town Centre"),
    /** Dense terrace housing south of the original town, along Millbrook Road. */
    SOUTH_QUARTER("South Quarter"),
    /** Mixed residential east of the river, newer build, higher density. */
    EAST_VILLAGE("East Village"),
    /** Institutional zone: fire, police, sports, community buildings. */
    CIVIC_QUARTER("Civic Quarter"),
    /** Industrial north-east: factory, warehouse, workshop, worker housing. */
    MILL_LANE("Mill Lane"),

    // -------- Expansion districts (added with 320×200 map) --------

    /** Historic area: narrow streets, older terraces, old church, cemetery. */
    OLD_TOWN("Old Town"),
    /** Prosperous: larger homes, landscaped streets, low crime, higher values. */
    WEALTHY_QUARTER("Wealthy Quarter"),
    /** Lakeside mix: walking paths, cafés, scenic housing, flood risk. */
    RIVERSIDE("Riverside"),
    /** Heavy industry, warehouses, workshops, recycling depot, lorry yards. */
    INDUSTRIAL_ESTATE("Industrial Estate"),
    /** Offices, technology firms, professional services, landscaped parking. */
    BUSINESS_PARK("Business Park"),
    /** Out-of-town retail: supermarket, cinema, fast food, petrol station. */
    RETAIL_PARK("Retail Park"),
    /** Pubs, bars, nightclub, restaurants, takeaways, taxi rank. */
    NIGHTLIFE_QUARTER("Nightlife Quarter"),
    /** Farms, cottages, woodland trails, scattered houses, future development. */
    RURAL_FRINGE("Rural Fringe")
}

/**
 * The evolving social/economic identity of a district — reclassified weekly by
 * [com.ripple.town.core.simulation.DistrictCharacterSystem] from vacancy rate,
 * wealth index, crime rate, and employment figures.
 */
enum class DistrictCharacter(val label: String) {
    STABLE("Stable"),
    PROSPEROUS("Prosperous"),
    DEVELOPING("Developing"),
    OVERCROWDED("Overcrowded"),
    DECLINING("Declining"),
    DERELICT("Derelict"),
    REGENERATING("Regenerating"),
    INDUSTRIAL("Industrial"),
    HIGH_CRIME("High crime"),
    GENTRIFYING("Gentrifying"),
    FAMILY_SUBURB("Family suburb")
}

/**
 * A single named district within the town — a rectangular tile region with a
 * distinct type and aggregate social/economic character. Persisted in [WorldState].
 *
 * Bounds are inclusive: tiles (originX, originY) through (originX+width-1, originY+height-1).
 */
@Serializable
data class District(
    val id: Long,
    val name: String,
    val type: DistrictType,
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    /** Rolling average resident population; updated daily by LifecycleSystem. */
    var population: Int = 0,
    /** Wealth multiplier relative to town average (1.0 = average). */
    var wealthIndex: Double = 1.0,
    /** Normalised daily crime incidence rate 0..1, fed into CrimeSystem probability. */
    var crimeRate: Double = 0.5,
    /** Evolving social character, reclassified weekly by DistrictCharacterSystem. */
    var character: DistrictCharacter = DistrictCharacter.STABLE,
    /** Fraction of buildings with no active occupants (0..1), updated weekly. */
    var vacancyRate: Double = 0.0,
    /** 0..100 composite wealth/employment score for this district; 50 = town average. */
    var prosperityIndex: Double = 50.0
) {
    fun containsTile(x: Int, y: Int): Boolean =
        x in originX until originX + width && y in originY until originY + height
}
