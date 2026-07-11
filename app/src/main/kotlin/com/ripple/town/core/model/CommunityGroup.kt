package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class CommunityGroupType(val label: String) {
    SPORTS_CLUB("Sports club"),
    CHOIR_OR_BAND("Choir or band"),
    BOOK_CIRCLE("Book circle"),
    CHARITY("Charity"),
    NEIGHBOURHOOD_WATCH("Neighbourhood watch"),
    FAITH_GROUP("Faith group"),
    TRADE_GUILD("Trade guild"),
    GARDEN_SOCIETY("Garden society")
}

@Serializable
data class CommunityGroup(
    val id: Long,
    val type: CommunityGroupType,
    var name: String,
    val foundedAt: Long,
    val founderResidentId: Long,
    val meetingBuildingId: Long?,
    val memberIds: MutableList<Long> = mutableListOf(),
    var active: Boolean = true,
    var reputation: Double = 50.0   // 0..100
)
