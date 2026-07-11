package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class IdentityLabel(val label: String) {
    MOTHER("Mother"),
    FATHER("Father"),
    BUSINESS_OWNER("Business owner"),
    COMMUNITY_LEADER("Community leader"),
    CRIMINAL("Criminal past"),
    WIDOWED("Widowed"),
    SURVIVOR("Survivor"),
    ELDER("Elder"),
    NEWCOMER("Newcomer"),
    BEREAVED("Bereaved"),
    MARRIED("Married"),
    CRAFTSPERSON("Craftsperson"),
    SCHOLAR("Scholar"),
    DYNASTY_HEIR("Dynasty heir"),
    SELF_MADE("Self-made")
}

@Serializable
data class IdentityFacet(
    val label: IdentityLabel,
    val acquiredAt: Long,
    val sourceEventId: Long? = null,
    var strength: Double = 100.0    // 0..100
)
