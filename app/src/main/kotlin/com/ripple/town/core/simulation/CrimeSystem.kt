package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldEvent

/**
 * The town constable investigates reported crimes with imperfect information.
 * Suspicion is drawn from plausible *motive* — poor finances, low honesty,
 * resentment towards the victim — not certainty, so the constable can end up
 * publicly naming the wrong person while the real culprit gets away with it.
 * `CRIME_REPORTED` only ever carries what the constable believes, never the
 * truth the engine actually knows — same principle as [RumourSystem].
 */
object CrimeSystem {

    /** Keeps a constable appointed: the most honest, courageous adult in town. */
    fun ensureConstable(ctx: TickContext) {
        val state = ctx.state
        val current = state.constableResidentId?.let { state.resident(it) }
        if (current != null && current.inTown && current.lifeStageAt(ctx.now) == LifeStage.ADULT) return
        val best = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .maxByOrNull { it.personality.honesty * 0.6 + it.personality.courage * 0.4 }
        state.constableResidentId = best?.id
    }

    /** Investigate a `CRIME_COMMITTED` event; may correctly or wrongly name a suspect. */
    fun investigate(ctx: TickContext, crime: WorldEvent) {
        val state = ctx.state
        val culprit = crime.sourceResidentId?.let { state.resident(it) } ?: return
        ensureConstable(ctx)
        val constable = state.constableResidentId?.let { state.resident(it) }

        val others = state.detailedResidents()
            .filter { it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT && it.id != constable?.id && it.id != culprit.id }
        val pool = (others + culprit).sortedBy { it.id }
        if (pool.isEmpty()) return

        val victimOwnerId = crime.businessId?.let { state.businesses[it]?.ownerId }
        fun suspicion(r: Resident): Double {
            val dishonesty = 1.0 - r.personality.honesty
            val poor = if (r.needs.financialSecurity < 35.0 || r.debt > 500.0) 0.35 else 0.0
            val grudge = victimOwnerId?.let { state.relationship(r.id, it)?.resentment ?: 0.0 } ?: 0.0
            return (0.1 + dishonesty * 0.5 + poor + grudge / 150.0).coerceAtLeast(0.02)
        }

        // Weighted pick among suspects, always including the true culprit in the pool.
        val weights = pool.map { it to suspicion(it) }
        val total = weights.sumOf { it.second }
        var roll = ctx.rng.nextDouble(0.0, total)
        var accused = pool.last()
        for ((r, w) in weights) {
            if (roll < w) { accused = r; break }
            roll -= w
        }

        val accurate = accused.id == culprit.id
        val description = if (accurate) {
            "${accused.fullName} has been named by the constable over the recent theft."
        } else {
            "${accused.fullName} has been accused of the recent theft — though the truth of it is far from settled."
        }
        val report = ctx.emit(
            EventType.CRIME_REPORTED, description,
            sourceResidentId = constable?.id ?: accused.id,
            targetResidentIds = listOf(accused.id),
            severity = 0.35, causeIds = listOf(crime.id),
            payload = mapOf("accurate" to accurate.toString(), "accusedId" to accused.id.toString())
        )
        accused.needs.stress += if (accurate) 10.0 else 16.0
        accused.reputation -= if (accurate) 8.0 else 12.0
        if (!accurate) {
            ctx.addMemory(accused, MemoryType.HUMILIATION, "Accused of something I never did.", 75.0, report.id)
            culprit.needs.stress += 6.0 // some quiet unease, never made public
            if (constable != null) {
                val rel = state.relationshipOrCreate(accused.id, constable.id)
                rel.resentment += 15.0
                rel.trust -= 10.0
                rel.clampAll()
            }
        }
        ConsequenceEngine.onEvent(ctx, report)
    }
}
