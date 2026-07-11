package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * The emergent personality and identity of the town — its accumulated character fingerprint.
 * Two towns that start identically will slowly become different places because their residents
 * make different choices, different disasters strike, and different families rise or fall.
 *
 * Evolved monthly by [com.ripple.town.core.simulation.TownDnaSystem] from objective signals:
 * what businesses operate here, what eras have passed, who lives here and how. Never set
 * directly — it drifts slowly from what actually happens.
 */
@Serializable
data class TownDna(
    // ─── Archetype ──────────────────────────────────────────────────────────
    /** Affinity score (0–100) for each TownArchetype name. Smoothed with 0.85/0.15 each month. */
    val archetypeScores: MutableMap<String, Double> = mutableMapOf(),

    /** The single strongest archetype — the town's dominant character label. */
    var dominantArchetype: String? = null,

    /** A secondary character that colours the dominant. */
    var secondaryArchetype: String? = null,

    /** Previous dominant archetypes, in chronological order. */
    val archetypeHistory: MutableList<String> = mutableListOf(),

    // ─── Cultural dimensions (0–100 each) ───────────────────────────────────
    var workEthic: Double = 50.0,
    var entrepreneurialSpirit: Double = 30.0,
    var academicCulture: Double = 30.0,
    var religiousTradition: Double = 20.0,
    var creativeCulture: Double = 30.0,
    /** How tightly the community bonds together (vs atomised individuality). */
    var communityBinds: Double = 50.0,
    /** Social openness and tolerance toward outsiders and new ideas. */
    var toleranceScore: Double = 50.0,
    var localPride: Double = 50.0,
    var trustInOthers: Double = 50.0,
    var riskAppetite: Double = 40.0,

    // ─── Reputation ─────────────────────────────────────────────────────────
    /** Positive traits the town is known for. Updated annually by SocialStratificationSystem. */
    val knownFor: MutableList<String> = mutableListOf(),

    /** Negative reputations the town has accumulated. */
    val stigmas: MutableList<String> = mutableListOf(),

    var foundingYear: Int = 0
)

/**
 * The character archetypes a town can grow into. Scored continuously; the dominant
 * archetype is whichever score is highest. A town can shift archetype across decades
 * if its character genuinely changes.
 */
enum class TownArchetype(val label: String, val description: String) {
    INDUSTRIAL("Industrial", "Built on manufacturing and trade"),
    CREATIVE("Creative", "Home to artists, makers and independent thinkers"),
    ACADEMIC("Academic", "Education and learning are central to life here"),
    TOURIST("Tourist", "Visitors and their needs shape the town"),
    RELIGIOUS("Religious", "Faith and community tradition run deep"),
    FINANCIAL("Financial", "Commerce and money-making dominate"),
    TECHNOLOGY("Technology", "Innovation and invention define the future"),
    AGRICULTURAL("Agricultural", "The land and its seasons set the rhythm"),
    HISTORIC("Historic", "The past lives in every building and family"),
    FAMILY_ORIENTED("Family-Oriented", "Children, care and community are everything"),
    NIGHTLIFE("Nightlife", "The town comes alive after dark"),
    WORKING_CLASS("Working Class", "Hard work, solidarity and everyday life"),
    DECLINING("Declining", "The town is losing what made it"),
    GROWING("Growing", "Expansion, arrivals and new beginnings"),
    RESILIENT("Resilient", "This town has endured and endures still")
}
