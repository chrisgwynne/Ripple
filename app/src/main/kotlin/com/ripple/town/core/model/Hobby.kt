package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class HobbyType(val label: String, val skillBoost: SkillType?) {
    READING("Reading", SkillType.CREATIVITY),
    GARDENING("Gardening", null),
    COOKING("Cooking", SkillType.COOKING),
    SPORT("Sport", SkillType.FITNESS),
    MUSIC("Music", SkillType.CREATIVITY),
    CARPENTRY("Carpentry", SkillType.CARPENTRY),
    FISHING("Fishing", null),
    SOCIALISING("Socialising", SkillType.SOCIAL),
    VOLUNTEERING("Volunteering", SkillType.SOCIAL),
    COLLECTING("Collecting curiosities", null)
}

@Serializable
data class HobbyEngagement(
    val type: HobbyType,
    var enthusiasm: Double = 50.0,    // 0..100
    val startedAt: Long,
    var lastPractisedAt: Long,
    var sessionsTotal: Int = 0
)
