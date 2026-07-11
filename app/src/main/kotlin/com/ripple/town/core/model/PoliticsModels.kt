package com.ripple.town.core.model

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────
// Policy areas — what parties hold positions on. Each area maps to
// simulation effects through PolicyModifiers. Party positions are
// stored as Double 0..100 (0 = minimal intervention, 100 = maximum).
// ─────────────────────────────────────────────────────────────────

enum class PolicyArea(val label: String) {
    POLICING("Policing"),
    TAXATION("Taxation"),
    PUBLIC_SPENDING("Public spending"),
    BUSINESS_REGULATION("Business regulation"),
    HOUSING("Housing"),
    PLANNING("Planning"),
    EDUCATION("Education"),
    HEALTHCARE("Healthcare"),
    TRANSPORT("Transport"),
    ENVIRONMENT("Environment"),
    SOCIAL_CARE("Social care"),
    CIVIL_LIBERTIES("Civil liberties"),
    LAW_AND_ORDER("Law and order"),
    PUBLIC_OWNERSHIP("Public ownership"),
    INNOVATION("Innovation & technology")
}

// ─────────────────────────────────────────────────────────────────
// Concrete policies a council can pass. annualCost > 0 = council
// spends; < 0 = council gains revenue. delayDays = how long before
// the full effect is felt (school investment takes years to show).
// ─────────────────────────────────────────────────────────────────

enum class PolicyType(
    val label: String,
    val area: PolicyArea,
    val annualCost: Double,
    val ideologyScore: Double,  // position on the area axis 0..100
    val delayDays: Int = 0
) {
    INCREASE_POLICE_FUNDING("Increase police funding", PolicyArea.POLICING, 8_000.0, 80.0),
    REDUCE_POLICE_FUNDING("Reduce police patrols", PolicyArea.POLICING, -3_000.0, 20.0),
    COMMUNITY_POLICING("Community policing programme", PolicyArea.POLICING, 5_000.0, 60.0, 30),
    RAISE_COUNCIL_TAX("Raise council tax", PolicyArea.TAXATION, -6_000.0, 70.0),
    CUT_COUNCIL_TAX("Cut council tax", PolicyArea.TAXATION, 4_000.0, 30.0),
    BUSINESS_RATE_REDUCTION("Business rate reduction", PolicyArea.BUSINESS_REGULATION, 3_000.0, 25.0),
    BUSINESS_RATE_INCREASE("Business rate increase", PolicyArea.BUSINESS_REGULATION, -4_000.0, 75.0),
    HOUSING_DEVELOPMENT("Housing development scheme", PolicyArea.HOUSING, 12_000.0, 75.0, 90),
    RESTRICT_DEVELOPMENT("Restrict new development", PolicyArea.PLANNING, -1_000.0, 30.0),
    TENANT_PROTECTIONS("Introduce tenant protections", PolicyArea.HOUSING, 2_000.0, 65.0, 60),
    SCHOOL_INVESTMENT("School investment programme", PolicyArea.EDUCATION, 10_000.0, 80.0, 720),
    CUT_SCHOOL_BUDGET("Cut education budget", PolicyArea.EDUCATION, -5_000.0, 25.0),
    CLINIC_EXPANSION("Expand local clinic", PolicyArea.HEALTHCARE, 8_000.0, 75.0, 180),
    CUT_HEALTHCARE("Reduce healthcare funding", PolicyArea.HEALTHCARE, -4_000.0, 25.0),
    ROAD_INVESTMENT("Road improvement programme", PolicyArea.TRANSPORT, 7_000.0, 70.0, 60),
    CUT_TRANSPORT("Cut transport maintenance", PolicyArea.TRANSPORT, -3_000.0, 30.0),
    GREEN_INITIATIVE("Green environmental initiative", PolicyArea.ENVIRONMENT, 5_000.0, 80.0, 90),
    INDUSTRY_PRIORITY("Prioritise industry over environment", PolicyArea.ENVIRONMENT, -2_000.0, 20.0),
    WELFARE_EXPANSION("Expand social welfare", PolicyArea.SOCIAL_CARE, 9_000.0, 80.0, 30),
    WELFARE_REDUCTION("Reduce welfare spending", PolicyArea.SOCIAL_CARE, -5_000.0, 20.0),
    CURFEW_POWERS("Public order powers", PolicyArea.LAW_AND_ORDER, 1_000.0, 70.0),
    CIVIL_RIGHTS_CHARTER("Civil rights charter", PolicyArea.CIVIL_LIBERTIES, 1_500.0, 70.0),
    TECH_INVESTMENT("Technology & innovation fund", PolicyArea.INNOVATION, 6_000.0, 70.0, 360),
    TRADITIONAL_ECONOMY("Protect traditional industries", PolicyArea.INNOVATION, 2_000.0, 35.0)
}

// ─────────────────────────────────────────────────────────────────
// Aggregate runtime effect of all currently active policies.
// Re-computed by PolicyEngine after any policy change.
// Downstream systems read these multipliers to tune probabilities —
// policies push causality at the margins, they never override it.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class PolicyModifiers(
    var crimeMultiplier: Double = 1.0,           // < 1 = less crime (police investment)
    var businessFormationBonus: Double = 0.0,     // added to business opening probability
    var educationQualityBonus: Double = 0.0,      // +N points to school institution quality
    var transportRepairBonus: Double = 0.0,       // +N to road repair probability
    var environmentBonus: Double = 0.0,           // pollution reduction bonus
    var healthcareBonus: Double = 0.0,            // clinic capacity bonus
    var housingDevelopmentBonus: Double = 0.0,    // housing project approval probability bonus
    var councilTaxMultiplier: Double = 1.0,       // > 1 = higher council tax income
    var businessRatesMultiplier: Double = 1.0,    // > 1 = higher business rates income
    var welfareBonus: Double = 0.0,               // daily payment to unemployed residents
    var civicWellbeingBonus: Double = 0.0         // wellbeing bonus from civic investment
)

// ─────────────────────────────────────────────────────────────────
// Leadership traits — generated for politically-active residents.
// Stored in WorldState.leadershipTraits to avoid bloating Resident.
// electionScore() feeds campaign support calculations in ElectionSystem.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class LeadershipTraits(
    val competence: Double = 0.5,
    val charisma: Double = 0.5,
    val honesty: Double = 0.5,
    val ambition: Double = 0.5,
    val communication: Double = 0.5,
    val vision: Double = 0.5,
    val crisisManagement: Double = 0.5,
    val organisation: Double = 0.5
) {
    fun electionScore(): Double =
        charisma * 0.35 + competence * 0.25 + communication * 0.2 + vision * 0.2
}

// ─────────────────────────────────────────────────────────────────
// Political parties — generated from ideology positions, never
// hard-coded to real-world parties. Name and tagline emerge from
// the party's dominant policy positions.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class PoliticalParty(
    val id: Long,
    var name: String,
    var tagline: String,
    val foundedAt: Long,
    var dissolvedAt: Long? = null,
    var leaderId: Long? = null,
    val memberIds: MutableList<Long> = mutableListOf(),
    var funding: Double = 5_000.0,
    var reputation: Double = 50.0,
    /** PolicyArea.name → 0..100 position (0 = minimal intervention, 100 = maximum). */
    val manifesto: MutableMap<String, Double> = mutableMapOf(),
    /** PolicyType.name list — policies this party enacts when in power. */
    val priorityPolicies: MutableList<String> = mutableListOf(),
    val history: MutableList<String> = mutableListOf()
)

// ─────────────────────────────────────────────────────────────────
// Policy records — proposals the council has considered or passed.
// ─────────────────────────────────────────────────────────────────

enum class PolicyStatus { PROPOSED, PASSED, REJECTED, REPEALED }

@Serializable
data class PolicyRecord(
    val id: Long,
    val policyType: PolicyType,
    val title: String,
    val proposedByPartyId: Long?,
    val proposedByResidentId: Long?,
    val proposedAt: Long,
    var status: PolicyStatus = PolicyStatus.PROPOSED,
    var passedAt: Long? = null,
    var repealedAt: Long? = null,
    var votesFor: Int = 0,
    var votesAgainst: Int = 0,
    val annualCost: Double,
    val delayDays: Int = 0,
    /** Day index when delayed effects fully activate. Null = immediate. */
    var activatesAtDay: Long? = null
)

// ─────────────────────────────────────────────────────────────────
// Government records — the political-history memory of the town.
// Every administration is archived here. The player can trace
// "the town never recovered from that administration" back to these.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class GovernmentRecord(
    val id: Long,
    val partyId: Long?,
    val leaderId: Long,
    val leaderName: String,
    val partyName: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    val policiesPassed: MutableList<Long> = mutableListOf(),
    val policiesRejected: MutableList<Long> = mutableListOf(),
    var peakApproval: Double = 50.0,
    var lowestApproval: Double = 50.0,
    var finalApproval: Double = 50.0,
    val crisisNotes: MutableList<String> = mutableListOf(),
    var legacyStatement: String = "",
    var corruption: Boolean = false
)

// ─────────────────────────────────────────────────────────────────
// Corruption — possible when motive + opportunity + weak oversight
// align. Never guaranteed; never the inevitable outcome of holding
// power. Separate from street crime in CrimeSystem.
// ─────────────────────────────────────────────────────────────────

enum class CorruptionType(val label: String) {
    BRIBERY("Bribery"),
    CONTRACT_FRAUD("Contract fraud"),
    EMBEZZLEMENT("Embezzlement"),
    PLANNING_CORRUPTION("Planning corruption"),
    NEPOTISM("Nepotism in appointments")
}

enum class CorruptionStatus { ONGOING, INVESTIGATED, EXPOSED, PROSECUTED, COVERED_UP }

@Serializable
data class CorruptionIncident(
    val id: Long,
    val type: CorruptionType,
    val perpetratorId: Long,
    val beneficiaryId: Long? = null,
    val startedAt: Long,
    var status: CorruptionStatus = CorruptionStatus.ONGOING,
    var discoveredAt: Long? = null,
    var resolvedAt: Long? = null,
    val severity: Double,           // 0..1
    val description: String,
    val moneyInvolved: Double = 0.0
)

// ─────────────────────────────────────────────────────────────────
// Public opinion — per-resident government satisfaction. Stored in
// WorldState rather than on Resident to avoid bloating the Resident
// constructor. The approvalRating is the number the newspaper prints.
// ─────────────────────────────────────────────────────────────────

@Serializable
data class PublicOpinionData(
    /** residentId → government satisfaction 0..100. */
    val residentSatisfaction: MutableMap<Long, Double> = mutableMapOf(),
    var lastUpdateDay: Long = 0L,
    var approvalRating: Double = 50.0
)
