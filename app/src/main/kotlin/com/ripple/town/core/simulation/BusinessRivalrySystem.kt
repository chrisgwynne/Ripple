package com.ripple.town.core.simulation

import com.ripple.town.core.model.Business
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.RelationshipKind

/**
 * Economy v2 slice: same-type businesses that keep drawing from the same pool
 * of trade quietly compete for it, and if that competition stays close and
 * sustained long enough, their owners' relationship curdles into a real
 * `RelationshipKind.RIVAL`. Deliberately narrow: general price inflation
 * ("prices that move"), the property market and further business succession
 * work are separate, still-open backlog items — see `docs/backlog.md`.
 *
 * Bounded and gentle by design: nudges are small daily rolls (matching every
 * other system's `MAX_...` cap pattern), so rivalry is a slow drift over
 * weeks, not a dramatic swing, and most same-type pairs never become bitter
 * enemies — only ones that stay closely, persistently matched.
 */
object BusinessRivalrySystem {

    /** Daily demand nudge applied to the pair leader/laggard in close competition. */
    const val DEMAND_SHIFT_PER_DAY = 2.0

    /** How close price/reputation standing must be for two businesses to be
     *  considered "closely" competing (as opposed to one simply dominating). */
    const val CLOSE_COMPETITION_THRESHOLD = 20.0

    /** Daily relationship nudges between owners while their businesses are in
     *  close, sustained competition. Small — rivalry should take weeks to form. */
    const val RESENTMENT_PER_DAY = 0.6
    const val AFFECTION_DECAY_PER_DAY = 0.3

    /** Thresholds mirroring `InteractionSystem.updateKind`'s RIVAL transition. */
    const val RIVAL_RESENTMENT_THRESHOLD = 55.0
    const val RIVAL_AFFECTION_CEILING = 30.0

    /** Never more than this many competing pairs processed per day (bounded, like every other system). */
    const val MAX_PAIRS_PER_DAY = 40

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        val byType = state.businesses.values
            .filter { it.open && it.type !in EconomySystem.PUBLIC_SERVICES }
            .groupBy { it.type }

        var pairsProcessed = 0
        for ((_, group) in byType.entries.sortedBy { it.key.ordinal }) {
            if (pairsProcessed >= MAX_PAIRS_PER_DAY) break
            val sorted = group.sortedBy { it.id }
            for (i in sorted.indices) {
                if (pairsProcessed >= MAX_PAIRS_PER_DAY) break
                for (j in i + 1 until sorted.size) {
                    if (pairsProcessed >= MAX_PAIRS_PER_DAY) break
                    processPair(ctx, sorted[i], sorted[j])
                    pairsProcessed++
                }
            }
        }
    }

    private fun processPair(ctx: TickContext, a: Business, b: Business) {
        val state = ctx.state

        // 1. Price/demand competition — the one with the better price/reputation
        // combination gently draws demand away from the other, every day both
        // are open and trading the same goods. "Better" = cheaper for similar
        // reputation, or better-reputed for similar price.
        val standingA = standing(a)
        val standingB = standing(b)
        val gap = standingA - standingB
        if (gap > 0.01) {
            a.demand = (a.demand + DEMAND_SHIFT_PER_DAY).coerceIn(5.0, 95.0)
            b.demand = (b.demand - DEMAND_SHIFT_PER_DAY).coerceIn(5.0, 95.0)
        } else if (gap < -0.01) {
            b.demand = (b.demand + DEMAND_SHIFT_PER_DAY).coerceIn(5.0, 95.0)
            a.demand = (a.demand - DEMAND_SHIFT_PER_DAY).coerceIn(5.0, 95.0)
        }

        // 2. Owner rivalry — only when standing is genuinely close (neither
        // business is simply outclassing the other); a runaway leader and a
        // struggling laggard aren't "competing", they've already settled.
        if (kotlin.math.abs(gap) > CLOSE_COMPETITION_THRESHOLD) return
        val ownerAId = a.ownerId ?: return
        val ownerBId = b.ownerId ?: return
        if (ownerAId == ownerBId) return // same person owning both — no rivalry with yourself
        val ownerA = state.resident(ownerAId) ?: return
        val ownerB = state.resident(ownerBId) ?: return
        if (!ownerA.inTown || !ownerB.inTown) return

        val rel = state.relationshipOrCreate(ownerA.id, ownerB.id)
        if (rel.kind in FIXED_KINDS_FOR_RIVALRY) return // never overwrite family/romance ties

        rel.resentment = (rel.resentment + RESENTMENT_PER_DAY).coerceIn(0.0, 100.0)
        rel.affection = (rel.affection - AFFECTION_DECAY_PER_DAY).coerceIn(0.0, 100.0)

        // Same threshold check as InteractionSystem.updateKind's RIVAL transition,
        // applied explicitly here since these two owners may never actually be
        // co-located to trigger it through the ordinary interaction path.
        if (rel.kind != RelationshipKind.RIVAL &&
            rel.resentment > RIVAL_RESENTMENT_THRESHOLD && rel.affection < RIVAL_AFFECTION_CEILING
        ) {
            rel.kind = RelationshipKind.RIVAL
            ctx.emit(
                EventType.RIVALRY_FORMED,
                "There is bad blood between ${ownerA.fullName} and ${ownerB.fullName} now — " +
                    "${a.name} and ${b.name} have been fighting over the same trade for weeks.",
                sourceResidentId = ownerA.id, targetResidentIds = listOf(ownerB.id),
                severity = 0.3, visibility = EventVisibility.PRIVATE
            )
        }
        rel.clampAll()
    }

    /** Higher standing = the business residents would rather patronise: cheaper
     *  prices and stronger reputation both help. */
    private fun standing(biz: Business): Double = biz.reputation - (biz.priceLevel - 1.0) * 40.0

    /** Never let business competition force a rivalry onto a relationship kind
     *  that means something else entirely (mirrors `InteractionSystem.FIXED_KINDS`,
     *  but RIVAL itself is allowed through since that's exactly what we're setting). */
    private val FIXED_KINDS_FOR_RIVALRY = setOf(
        RelationshipKind.FAMILY, RelationshipKind.ESTRANGED_FAMILY, RelationshipKind.PARTNER,
        RelationshipKind.SPOUSE, RelationshipKind.FORMER_PARTNER, RelationshipKind.AFFAIR
    )
}
