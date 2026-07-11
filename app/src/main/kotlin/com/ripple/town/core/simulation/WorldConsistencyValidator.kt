package com.ripple.town.core.simulation

import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.WorldState

/**
 * Post-tick sanity layer.  Runs after every day tick and checks the world state for
 * combinations that would never occur in real life and indicate a simulation bug.
 *
 * Design philosophy
 * -----------------
 * - Every check is O(residents) — no nested loops, no per-building sweeps.
 * - Checks are *observable impossibilities*: things a player could notice on the map
 *   ("why is that newborn at school?") rather than internal invariants.
 * - Non-halting by default.  [validate] returns a list of violations; callers decide
 *   whether to log, emit an event, or in debug builds call [assertValid] to halt.
 *
 * Called from [SimulationCoordinator.tick] inside the `if (newDay)` block, after all
 * other daily systems have run.
 */
object WorldConsistencyValidator {

    data class Violation(val residentId: Long, val description: String)

    fun validate(state: WorldState): List<Violation> {
        val violations = mutableListOf<Violation>()
        val now = state.time
        val nurseryCovers = CaregiverSystem.hasNursery(state)

        for (r in state.livingResidents()) {
            if (!r.inTown || r.detailLevel != DetailLevel.DETAILED) continue
            val age = r.ageAt(now)
            val ds = r.detailedLifeStageAt(now)

            // A child under school age must never appear as "At school".
            if (age < 5 && r.activity == Activity.AT_SCHOOL) {
                violations += Violation(
                    r.id,
                    "${r.fullName} (age $age) is shown '${Activity.AT_SCHOOL.label}' " +
                        "but is under school age (5)."
                )
            }

            // No one under 16 may be shown as "Working".
            if (age < 16 && r.activity == Activity.WORKING) {
                violations += Violation(
                    r.id,
                    "${r.fullName} (age $age) is shown '${Activity.WORKING.label}' " +
                        "but is under working age (16)."
                )
            }

            // Every under-5 child must have an assigned caregiver OR a nursery in town.
            if (ds.needsCaregiver && r.caregiverId == null && !nurseryCovers) {
                violations += Violation(
                    r.id,
                    "${r.fullName} (age $age) has no assigned caregiver and town has no nursery."
                )
            }

            // An under-5 child's assigned caregiver must not simultaneously be at work
            // unless a nursery exists.
            if (ds.needsCaregiver && !nurseryCovers) {
                val caregiver = r.caregiverId?.let { state.resident(it) }
                if (caregiver != null && caregiver.activity == Activity.WORKING) {
                    violations += Violation(
                        r.id,
                        "${r.fullName}'s caregiver ${caregiver.fullName} is at work " +
                            "with no nursery to cover."
                    )
                }
            }
        }

        return violations
    }

    /**
     * Debug-only assertion: throws [AssertionError] on the first run with violations.
     * Call from debug builds; in production prefer [validate] and handle gracefully.
     */
    fun assertValid(state: WorldState) {
        val violations = validate(state)
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "WorldConsistencyValidator: ${violations.size} violation(s):\n" +
                    violations.joinToString("\n") { "  • ${it.description}" }
            )
        }
    }
}
