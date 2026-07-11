package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class OpportunityType {
    // Commercial shortfalls
    FOOD_RETAIL_SHORTAGE, CONVENIENCE_SHORTAGE, CAFE_SHORTAGE,
    RESTAURANT_SHORTAGE, PHARMACY_SHORTAGE, HARDWARE_SHORTAGE,
    // Civic/service shortfalls
    CHILDCARE_SHORTAGE, HEALTHCARE_SHORTAGE, ELDERLY_CARE_SHORTAGE,
    SCHOOL_SHORTAGE, COMMUNITY_SPACE_SHORTAGE,
    // Industrial/employment
    INDUSTRIAL_DEMAND, OFFICE_DEMAND, TRADES_DEMAND,
    // Housing
    FAMILY_HOUSING_SHORTAGE, AFFORDABLE_HOUSING_SHORTAGE, ELDERLY_HOUSING_SHORTAGE,
    // Surplus/oversupply
    COMMERCIAL_OVERSUPPLY, RETAIL_OVERSUPPLY,
    // Reuse
    VACANT_PREMISES_REUSE, CONVERSION_CANDIDATE,
    // Regeneration
    DERELICTION_REGENERATION
}

enum class OpportunityStatus { OPEN, CLAIMED, EXPIRED, FULFILLED }

@Serializable
data class Opportunity(
    val id: Long,
    val type: OpportunityType,
    val districtId: Long? = null,
    val evidence: String = "",
    val expectedWeeklyDemand: Int = 0,
    val estimatedAnnualRevenue: Double = 0.0,
    val estimatedCapitalRequired: Double = 0.0,
    val suitableBuildingIds: List<Long> = emptyList(),   // vacant buildings that could serve
    val risks: String = "",
    val detectedAt: Long = 0L,
    val expiresAt: Long = Long.MAX_VALUE,
    val status: OpportunityStatus = OpportunityStatus.OPEN,
    val claimedByResidentId: Long? = null
)
