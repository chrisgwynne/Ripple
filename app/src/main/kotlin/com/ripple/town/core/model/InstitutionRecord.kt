package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class InstitutionType(val label: String) {
    SCHOOL("School"),
    CLINIC("Clinic"),
    TOWN_HALL("Town hall"),
    SPORTS_HALL("Sports hall"),
    COMMUNITY_CENTRE("Community centre"),
    POLICE_STATION("Police station"),
    FIRE_STATION("Fire station")
}

@Serializable
data class InstitutionRecord(
    val id: Long,
    val type: InstitutionType,
    val buildingId: Long,
    val name: String,
    val foundedAt: Long,
    var reputation: Double = 50.0,          // 0..100
    var staffCount: Int = 0,               // current employed staff (updated monthly)
    var pupilsServed: Int = 0,              // cumulative count (schools)
    var patientsServed: Int = 0,            // cumulative count (clinic)
    var leaderId: Long? = null,             // current head teacher / doctor / captain
    val pastLeaderIds: MutableList<Long> = mutableListOf(),
    val notableEvents: MutableList<String> = mutableListOf()
)
