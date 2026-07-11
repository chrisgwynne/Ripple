package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.MemoryType

/**
 * Economy v2 slice: business succession — voluntary, in-life handoffs, as
 * distinct from the (pre-existing, unchanged) silent ownership transfer that
 * already happens in [LifecycleSystem.die] when an owner dies. That path
 * remains the "no chosen heir was ready" fallback; this system is the
 * "the owner chose to step back while alive" path, and is the last open
 * piece of the Economy v2 backlog item — see `docs/backlog.md`.
 *
 * Deliberately narrow: only one shape of succession is modelled — an
 * elderly owner with a working, employed adult child at their own business
 * hands the business over and retires. Founding a *new* business, or a
 * non-family sale to an outside buyer, are not attempted here.
 */
object BusinessSuccessionSystem {

    /** An owner at or past this age is eligible to voluntarily retire and hand over. */
    const val RETIREMENT_AGE = 68

    /** Daily chance an eligible owner-with-ready-heir actually goes through with it — a slow,
     *  considered decision, not a snap one, matching every other daily system's gentle pacing. */
    const val SUCCESSION_CHANCE_PER_DAY = 0.06

    /** Never process more than this many businesses per day (bounded, like every other system). */
    const val MAX_BUSINESSES_PER_DAY = 40

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        val candidates = state.businesses.values
            .filter { it.open && it.ownerId != null }
            .sortedBy { it.id }
            .take(MAX_BUSINESSES_PER_DAY)

        for (biz in candidates) {
            val owner = state.resident(biz.ownerId!!) ?: continue
            if (!owner.alive || !owner.inTown) continue
            if (owner.ageAt(ctx.now) < RETIREMENT_AGE) continue

            val heir = readyHeir(ctx, owner, biz.id) ?: continue
            if (!ctx.rng.nextBoolean(SUCCESSION_CHANCE_PER_DAY)) continue

            handOver(ctx, biz.id, owner.id, heir.id)
        }
    }

    /** An adult child, alive and in town, currently employed *at this specific business* —
     *  they already know the trade, so this is a natural handoff, not a random pick. */
    private fun readyHeir(ctx: TickContext, owner: com.ripple.town.core.model.Resident, businessId: Long) =
        owner.childIds.mapNotNull { ctx.state.resident(it) }
            .filter { it.alive && it.inTown && it.lifeStageAt(ctx.now) == LifeStage.ADULT }
            .firstOrNull { child -> ctx.state.employmentOf(child)?.businessId == businessId }

    private fun handOver(ctx: TickContext, businessId: Long, ownerId: Long, heirId: Long) {
        val state = ctx.state
        val biz = state.business(businessId) ?: return
        val owner = state.resident(ownerId) ?: return
        val heir = state.resident(heirId) ?: return

        biz.ownerId = heir.id
        // The heir was working there as an employee; they now run the place, so that
        // employment record ends the same way any change of role would.
        // Guard: only null out employmentId when a real record was found and terminated —
        // nulling it unconditionally would orphan the field if the record is already missing.
        val heirEmp = state.employmentOf(heir)
        if (heirEmp != null) {
            heirEmp.endedAt = ctx.now
            heir.employmentId = null
        }

        // The retiring owner steps back — no longer chasing income from this business,
        // but not unemployed in the "needs a job" sense either; a settled retirement.
        owner.goals.firstOrNull { it.type == GoalType.RETIRE_WELL && it.status == com.ripple.town.core.model.GoalStatus.ACTIVE }
            ?.let { it.status = com.ripple.town.core.model.GoalStatus.COMPLETED }

        val e = ctx.emit(
            EventType.BUSINESS_SUCCESSION,
            "${owner.fullName} has handed ${biz.name} down to ${heir.fullName} and stepped back to retire.",
            sourceResidentId = owner.id, targetResidentIds = listOf(heir.id),
            businessId = biz.id, buildingId = biz.buildingId,
            severity = 0.35, visibility = EventVisibility.PUBLIC
        )
        ctx.addMemory(owner, MemoryType.ACHIEVEMENT, "The day I handed ${biz.name} to ${heir.firstName}.", 80.0, e.id, listOf(heir.id))
        ctx.addMemory(heir, MemoryType.ACHIEVEMENT, "${owner.firstName} trusted me with ${biz.name}.", 85.0, e.id, listOf(owner.id))
        ConsequenceEngine.onEvent(ctx, e)
    }
}
