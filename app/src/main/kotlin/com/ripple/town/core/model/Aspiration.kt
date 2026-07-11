package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class AspirationType(val label: String) {
    OWN_A_BUSINESS("Own a business"),
    RAISE_A_FAMILY("Raise a family"),
    BECOME_A_LEADER("Become a community leader"),
    MASTER_A_CRAFT("Master a craft"),
    FIND_TRUE_LOVE("Find true love"),
    LEAVE_A_LEGACY("Leave a lasting legacy"),
    EXPLORE_THE_WORLD("See the world beyond this town"),
    BUILD_A_HOME("Build a home of their own"),
    OVERCOME_HARDSHIP("Rise above hardship"),
    CARRY_ON_A_TRADITION("Carry on a family tradition")
}

enum class AspirationStatus { ACTIVE, FULFILLED, DORMANT, ABANDONED, INHERITED }

@Serializable
data class Aspiration(
    val type: AspirationType,
    var status: AspirationStatus = AspirationStatus.ACTIVE,
    var progress: Double = 0.0,       // 0..100
    val formedAt: Long,
    var fulfilledAt: Long? = null,
    var abandonedAt: Long? = null,
    val inheritedFromId: Long? = null // residentId of parent
)
