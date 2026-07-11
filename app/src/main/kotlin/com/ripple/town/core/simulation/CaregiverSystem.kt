package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.DetailedLifeStage
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldState

/**
 * Ensures every under-5 child has a named caregiver at all times.
 *
 * Assignment priority: mother → father → adult sibling (≥16) in same household →
 * any other adult household member. When a nursery building is present in town the
 * caregiverId may be null — the nursery is implicitly covering the child.
 *
 * [assignCaregiver] is called immediately after birth in LifecycleSystem.bear().
 * [updateDaily] re-validates assignments and updates occupation labels as children age.
 *
 * DecisionSystem reads [isDesignatedCaregiver] to suppress the GO_TO_WORK action for
 * the resident currently on duty — guaranteeing the child always has someone home.
 */
object CaregiverSystem {

    // ---------------------------------------------------------------- public API

    /** Call immediately after a newborn is added to WorldState. */
    fun assignCaregiver(state: WorldState, child: Resident) {
        child.caregiverId = pickCaregiver(state, child)
    }

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        for (r in state.livingResidents()) {
            if (!r.inTown) continue
            val ds = r.detailedLifeStageAt(ctx.now)

            // Sync the displayed occupation with the child's current developmental stage.
            val label = occupationLabel(ds)
            if (label != null && r.occupation != label) r.occupation = label

            if (!ds.needsCaregiver) {
                // Clear stale caregiverId once the child is old enough to be independent.
                if (r.caregiverId != null) r.caregiverId = null
                continue
            }

            // Re-validate: is the assigned caregiver still alive and in town?
            val existing = r.caregiverId?.let { state.resident(it) }
            if (existing == null || !existing.alive || !existing.inTown) {
                val newCaregiver = pickCaregiver(state, r)
                r.caregiverId = newCaregiver
                if (newCaregiver == null && !hasNursery(state)) {
                    ctx.emit(
                        EventType.CHILD_WELFARE_CONCERN,
                        "${r.firstName} (age ${r.ageAt(ctx.now)}) has no caregiver — " +
                            "no parent or responsible adult is available.",
                        sourceResidentId = r.id,
                        severity = 0.6
                    )
                }
            }
        }
    }

    /**
     * Returns true if [resident] is the assigned caregiver for at least one under-5 child
     * AND the town has no nursery that could cover instead.
     *
     * Used by DecisionSystem to block the GO_TO_WORK candidate action.
     */
    fun isDesignatedCaregiver(state: WorldState, resident: Resident): Boolean {
        if (hasNursery(state)) return false
        // Fast-path: check the resident's own children first.
        if (resident.childIds.any { cid ->
            val child = state.resident(cid) ?: return@any false
            child.alive && child.inTown && child.caregiverId == resident.id &&
                child.detailedLifeStageAt(state.time).needsCaregiver
        }) return true
        // Slower path: handle non-parent caregivers (siblings, household members).
        return state.livingResidents().any { child ->
            child.caregiverId == resident.id &&
                child.detailedLifeStageAt(state.time).needsCaregiver &&
                child.inTown
        }
    }

    fun hasNursery(state: WorldState): Boolean =
        state.buildings.values.any { it.type == BuildingType.NURSERY && !it.abandoned }

    // ---------------------------------------------------------------- internals

    private fun pickCaregiver(state: WorldState, child: Resident): Long? {
        // 1. Mother
        val mother = child.motherId?.let { state.resident(it) }
            ?.takeIf { it.alive && it.inTown }
        if (mother != null) return mother.id

        // 2. Father
        val father = child.fatherId?.let { state.resident(it) }
            ?.takeIf { it.alive && it.inTown }
        if (father != null) return father.id

        // 3. Adult sibling (≥16) — scan siblings via each parent's childIds
        val parentIds = listOfNotNull(child.motherId, child.fatherId)
        for (pid in parentIds) {
            val parent = state.resident(pid) ?: continue
            val sibling = parent.childIds
                .filter { it != child.id }
                .mapNotNull { state.resident(it) }
                .firstOrNull { it.alive && it.inTown && it.ageAt(state.time) >= 16 }
            if (sibling != null) return sibling.id
        }

        // 4. Any other adult (≥16) in the same household
        val hhId = child.householdId ?: return null
        val hh = state.households[hhId] ?: return null
        return hh.memberIds
            .filter { it != child.id }
            .mapNotNull { state.resident(it) }
            .firstOrNull { it.alive && it.inTown && it.ageAt(state.time) >= 16 }
            ?.id
    }

    private fun occupationLabel(ds: DetailedLifeStage): String? = when (ds) {
        DetailedLifeStage.NEWBORN, DetailedLifeStage.INFANT -> "Infant"
        DetailedLifeStage.TODDLER -> "Toddler"
        DetailedLifeStage.CHILD -> "Pupil"
        DetailedLifeStage.TEENAGER -> "Student"
        else -> null
    }
}
