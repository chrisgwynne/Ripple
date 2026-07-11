package com.ripple.town.core.model

import kotlinx.serialization.Serializable

/**
 * The cumulative story of a family across generations. Created the first time a
 * surname appears in the town and updated as members achieve, fail, or transform the
 * family name. Keyed by surname in [WorldState.familyLegacies].
 *
 * Tracked by [com.ripple.town.core.simulation.FamilyLegacySystem].
 */
@Serializable
data class FamilyLegacy(
    val id: Long,
    val surname: String,
    val foundingResidentId: Long,
    val foundedAt: Long,            // sim minutes

    // ─── Membership ─────────────────────────────────────────────────────────
    var totalMembersEver: Int = 1,
    var generations: Int = 1,
    var livingMembers: Int = 0,

    // ─── Achievements ───────────────────────────────────────────────────────
    var mayorships: Int = 0,
    var councillorships: Int = 0,
    var businessesOwned: Int = 0,
    var businessesClosed: Int = 0,
    var institutionLeaderRoles: Int = 0,
    var criminalConvictions: Int = 0,
    var degreesAttained: Int = 0,

    // ─── Wealth arc ─────────────────────────────────────────────────────────
    var peakWealth: Double = 0.0,
    var currentTotalWealth: Double = 0.0,
    var startingWealth: Double = 0.0,

    // ─── Reputation ─────────────────────────────────────────────────────────
    /** 0–100. Decays gently once all members leave or die. */
    var reputation: Double = 50.0,
    /** [FamilyReputationType] name. */
    var reputationType: String = FamilyReputationType.ORDINARY.name,

    val milestones: MutableList<String> = mutableListOf(),
    var lastUpdatedAt: Long = 0L
)

enum class FamilyReputationType(val label: String) {
    ORDINARY("Ordinary"),
    RESPECTED("Respected Family"),
    INFLUENTIAL("Influential Family"),
    POLITICAL_DYNASTY("Political Dynasty"),
    BUSINESS_EMPIRE("Business Empire"),
    ACADEMIC_FAMILY("Academic Family"),
    MEDICAL_FAMILY("Medical Family"),
    NOTORIOUS("Notorious"),
    CRIMINAL("Criminal Family"),
    PHILANTHROPIC("Philanthropic"),
    FALLEN("Fallen from Grace"),
    FOUNDING_FAMILY("Founding Family")
}
