package com.ripple.town.core.model

import kotlinx.serialization.Serializable

enum class RelationshipKind(val label: String) {
    STRANGER("Stranger"),
    ACQUAINTANCE("Acquaintance"),
    FRIEND("Friend"),
    CLOSE_FRIEND("Close friend"),
    RIVAL("Rival"),
    PARTNER("Partner"),
    SPOUSE("Spouse"),
    FORMER_PARTNER("Former partner"),
    FAMILY("Family"),
    ESTRANGED_FAMILY("Estranged family")
}

/**
 * A single relationship between two residents. Stored once per unordered pair;
 * [aId] is always the smaller resident id.
 */
@Serializable
data class Relationship(
    val aId: Long,
    val bId: Long,
    var kind: RelationshipKind = RelationshipKind.STRANGER,
    var familiarity: Double = 0.0,   // 0..100
    var trust: Double = 30.0,        // 0..100
    var affection: Double = 20.0,    // 0..100
    var attraction: Double = 0.0,    // 0..100
    var respect: Double = 40.0,      // 0..100
    var resentment: Double = 0.0,    // 0..100
    var dependency: Double = 0.0,    // 0..100
    var sharedHistory: Double = 0.0, // 0..100, grows slowly, barely decays
    var lastInteractionAt: Long = 0L
) {
    init {
        require(aId < bId) { "Relationship ids must be ordered: $aId >= $bId" }
    }

    fun involves(id: Long): Boolean = id == aId || id == bId
    fun other(id: Long): Long = if (id == aId) bId else aId

    fun clampAll() {
        familiarity = familiarity.coerceIn(0.0, 100.0)
        trust = trust.coerceIn(0.0, 100.0)
        affection = affection.coerceIn(0.0, 100.0)
        attraction = attraction.coerceIn(0.0, 100.0)
        respect = respect.coerceIn(0.0, 100.0)
        resentment = resentment.coerceIn(0.0, 100.0)
        dependency = dependency.coerceIn(0.0, 100.0)
        sharedHistory = sharedHistory.coerceIn(0.0, 100.0)
    }

    /** Overall warmth used by the decision system when choosing who to spend time with. */
    fun warmth(): Double = (affection * 0.4 + trust * 0.3 + respect * 0.2 + familiarity * 0.1) - resentment * 0.5

    companion object {
        fun keyOf(x: Long, y: Long): Long {
            val lo = minOf(x, y)
            val hi = maxOf(x, y)
            return lo * 1_000_000L + hi
        }

        fun create(x: Long, y: Long, kind: RelationshipKind = RelationshipKind.STRANGER): Relationship {
            val lo = minOf(x, y)
            val hi = maxOf(x, y)
            return Relationship(aId = lo, bId = hi, kind = kind)
        }
    }
}

@Serializable
data class Household(
    val id: Long,
    var name: String,
    var homeBuildingId: Long?,
    val memberIds: MutableList<Long> = mutableListOf(),
    var savings: Double = 0.0,
    var monthlyRent: Double = 300.0
)
