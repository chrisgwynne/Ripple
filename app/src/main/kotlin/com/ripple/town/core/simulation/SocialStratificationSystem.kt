package com.ripple.town.core.simulation

import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SocialClass

/**
 * Measures cross-generational social mobility once per sim-year and updates
 * [com.ripple.town.core.model.TownState.socialMobility].
 *
 * Social class is computed dynamically from wealth via [SocialClass.of] — it is never stored
 * directly on a resident. Mobility is the fraction of adults whose class is higher than both
 * parents' class at a comparable life stage.
 *
 * Also updates town DNA traits (knownFor / stigmas) when the town shows extreme patterns:
 * a town where the rich stay rich and the poor stay poor acquires a "stratified society"
 * stigma; one with genuine upward mobility earns "land of opportunity."
 */
object SocialStratificationSystem {

    const val UPDATE_INTERVAL_DAYS = 360L

    fun updateAnnually(ctx: TickContext) {
        val state = ctx.state
        val ts = state.townState
        val now = state.time
        val adults = state.livingResidents().filter { it.ageAt(now) >= 18 }

        if (adults.isEmpty()) return

        // ─── Mobility: how many adults surpassed their parents' class? ────
        var upwardCount = 0
        var comparableCount = 0

        for (resident in adults) {
            val residentClass = SocialClass.of(resident.wealth)
            val mother = resident.motherId?.let { state.resident(it) }
            val father = resident.fatherId?.let { state.resident(it) }
            val motherClass = mother?.let { SocialClass.of(it.wealth) }
            val fatherClass = father?.let { SocialClass.of(it.wealth) }
            val parentClass: SocialClass? = when {
                motherClass != null && fatherClass != null ->
                    if (motherClass.ordinal >= fatherClass.ordinal) motherClass else fatherClass
                motherClass != null -> motherClass
                fatherClass != null -> fatherClass
                else -> null
            }
            if (parentClass != null) {
                comparableCount++
                if (residentClass.ordinal > parentClass.ordinal) upwardCount++
            }
        }

        if (comparableCount > 0) {
            ts.socialMobility = upwardCount.toDouble() / comparableCount
        }

        // ─── Detect rags-to-riches (optional future milestone hook) ───────
        // Kept as a stub — CivilisationHistorySystem can be expanded here.

        // ─── Update town DNA reputation ───────────────────────────────────
        val dna = state.townDna
        when {
            ts.socialMobility < 0.1 && !dna.stigmas.contains("stratified society") -> {
                dna.stigmas += "stratified society"
                dna.knownFor.remove("land of opportunity")
            }
            ts.socialMobility > 0.4 && !dna.knownFor.contains("land of opportunity") -> {
                dna.knownFor += "land of opportunity"
                dna.stigmas.remove("stratified society")
            }
        }

        // ─── Gini-style inequality into DNA ───────────────────────────────
        if (ts.incomeInequality > 0.6 && !dna.stigmas.contains("unequal town")) {
            dna.stigmas += "unequal town"
        } else if (ts.incomeInequality < 0.2 && !dna.knownFor.contains("egalitarian")) {
            dna.knownFor += "egalitarian"
        }

        // Trim lists
        if (dna.knownFor.size > 10) dna.knownFor.removeAt(0)
        if (dna.stigmas.size > 10) dna.stigmas.removeAt(0)
    }
}
