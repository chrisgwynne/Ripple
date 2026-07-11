package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class ArrivalReason {
    JOB_OFFER, AVAILABLE_HOUSING, FAMILY_REUNIFICATION, EDUCATION,
    BUSINESS_OPPORTUNITY, RETIREMENT, SAFETY, AFFORDABILITY,
    HEALTHCARE, RELATIONSHIP, DISPLACEMENT, GOVERNMENT_PROGRAMME
}

enum class DepartureReason {
    UNEMPLOYMENT, UNAFFORDABLE_HOUSING, CRIME, EDUCATION_ELSEWHERE,
    POOR_SERVICES, BUSINESS_FAILURE, RETIREMENT_MIGRATION,
    DISASTER, BETTER_OPPORTUNITIES, FAMILY_ELSEWHERE, RELATIONSHIP
}

enum class HouseholdArrivalType {
    SINGLE_WORKER, YOUNG_COUPLE, COUPLE_WITH_CHILDREN, SINGLE_PARENT,
    RETIRED_COUPLE, MULTIGENERATIONAL, STUDENT_HOUSEHOLD,
    PROFESSIONAL_HOUSEHOLD, ENTREPRENEUR_FAMILY, DISPLACED_FAMILY
}

@Serializable
data class MigrationRecord(
    val tick: Long,
    val householdId: Long,
    val arrivalType: HouseholdArrivalType? = null,   // null for departures
    val arrivalReason: ArrivalReason? = null,
    val departureReason: DepartureReason? = null,
    val districtId: Long? = null,
    val memberCount: Int = 1,
    val isArrival: Boolean = true
)
