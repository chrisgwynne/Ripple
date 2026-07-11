package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class LegacyType(val label: String) {
    CHILDREN("Family lineage"),
    PROPERTY("Property ownership"),
    BUSINESS_DYNASTY("Business legacy"),
    COMMUNITY_REFORM("Community reform"),
    BELIEF_PASSED_ON("Belief passed on"),
    DEBT_LEFT("Debt left behind"),
    TOWN_REPUTATION("Town reputation"),
    CRAFT_MASTERY("Skill mastery")
}

@Serializable
data class LegacyRecord(
    val id: Long,
    val type: LegacyType,
    val subjectName: String,
    val description: String,
    val originResidentId: Long,
    val originResidentName: String,
    val createdAt: Long,
    var strength: Double = 100.0,
    val relatedResidentIds: List<Long> = emptyList(),
    val relatedBuildingId: Long? = null
)
