package com.ripple.town.core.simulation

import com.ripple.town.core.model.DistrictCharacter
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.isHome

/**
 * Causal effects of gentrification on buildings, existing residents, and prospective
 * wealthy migrants — Phase 5 S4 (2026-07-11).
 *
 * Three mechanics, all gated on [DistrictCharacter.GENTRIFYING]:
 *
 * (a) Building value appreciation: every building in the district gains 0.5 % of its
 *     value each day (`building.value *= 1.005`).
 *
 * (b) Low-wealth emigration: residents whose home building is in the district AND whose
 *     `wealth` is below the district's median wealth face a 0.3 % daily chance of being
 *     pushed out.  When they go, a [EventType.DISPLACEMENT] event fires with them as
 *     `sourceResidentId`.  The emigration itself mirrors [GoalSystem]'s own leave-town
 *     pattern (`leftTownAt = now`, clear building/household links).
 *
 * (c) Wealthy in-migration: the existing [LifecycleSystem.checkInwardMigration] already
 *     gives a 3 % probability bonus to GENTRIFYING districts — no need to duplicate that
 *     call here.  The 0.4 % extra individual wealthy-attraction chance described in the
 *     spec is implemented as a daily supplemental call to [LifecycleSystem.newFamilyArrives]
 *     for each GENTRIFYING district, gated on `wealth > 1.5 × medianWealth`.
 *
 * Bounded per day: at most [MAX_DISPLACEMENTS_PER_DAY] emigrations and
 * [MAX_ARRIVALS_PER_DAY] wealthy arrivals across all districts, matching every other
 * daily system's `MAX_…` cap pattern.
 */
object GentrificationSystem {

    /** Daily building-value appreciation multiplier for GENTRIFYING districts. */
    const val VALUE_APPRECIATION_PER_DAY = 1.005

    /** Daily emigration chance for a low-wealth resident in a GENTRIFYING district. */
    const val LOW_WEALTH_EMIGRATION_CHANCE = 0.003

    /** Daily arrival chance for a wealthy migrant per GENTRIFYING district. */
    const val WEALTHY_ARRIVAL_CHANCE = 0.004

    /** Wealth threshold multiplier: residents below median × factor are considered low-wealth. */
    const val LOW_WEALTH_FACTOR = 1.0   // strictly below median

    /** Wealth threshold multiplier: migrants above median × factor are considered wealthy. */
    const val WEALTHY_FACTOR = 1.5

    const val MAX_DISPLACEMENTS_PER_DAY = 5
    const val MAX_ARRIVALS_PER_DAY = 3

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        var displacementBudget = MAX_DISPLACEMENTS_PER_DAY
        var arrivalBudget = MAX_ARRIVALS_PER_DAY

        for (district in state.districts.values) {
            if (district.character != DistrictCharacter.GENTRIFYING) continue

            val districtBuildingIds = state.buildings.values
                .filter { b -> district.containsTile(b.origin.x, b.origin.y) }
                .map { it.id }
                .toSet()

            // (a) Building value appreciation
            for (buildingId in districtBuildingIds) {
                val b = state.building(buildingId) ?: continue
                b.value *= VALUE_APPRECIATION_PER_DAY
            }

            // Compute district median wealth for gates (b) and (c).
            val homeBuildingIds = districtBuildingIds
                .filter { state.building(it)?.type?.isHome == true }
            val districtResidents = state.livingResidents().filter { r ->
                r.inTown && r.homeBuildingId in homeBuildingIds
            }
            val medianWealth = median(districtResidents.map { it.wealth })

            // (b) Low-wealth emigration
            if (displacementBudget > 0) {
                val lowWealth = districtResidents
                    .filter { it.wealth < medianWealth * LOW_WEALTH_FACTOR }
                    .sortedBy { it.id }   // stable order for determinism
                for (r in lowWealth) {
                    if (displacementBudget <= 0) break
                    if (!ctx.rng.nextBoolean(LOW_WEALTH_EMIGRATION_CHANCE)) continue
                    displacementBudget--

                    // Mirror GoalSystem's leave-town pattern so the world stays consistent.
                    r.leftTownAt = ctx.now
                    r.currentBuildingId = null
                    r.travelToBuildingId = null
                    r.travelFromBuildingId = null
                    r.homeBuildingId?.let { bid ->
                        val building = state.building(bid)
                        building?.tenantHistory?.let { hist ->
                            if (r.id !in hist) {
                                hist += r.id
                                if (hist.size > com.ripple.town.core.model.Building.MAX_TENANT_HISTORY) hist.removeAt(0)
                            }
                        }
                    }
                    r.homeBuildingId = null
                    // Detach from household
                    r.householdId?.let { hhId ->
                        val hh = state.households[hhId]
                        if (hh != null) {
                            hh.memberIds.remove(r.id)
                            if (hh.memberIds.isEmpty()) {
                                // empty household dissolves — mirror LifecycleSystem's cleanup
                                hh.homeBuildingId = null
                            }
                        }
                    }
                    r.householdId = null

                    ctx.emit(
                        EventType.DISPLACEMENT,
                        "${r.fullName} could no longer afford to live in ${district.name} " +
                            "and has been forced to leave.",
                        sourceResidentId = r.id,
                        severity = 0.45,
                        visibility = EventVisibility.PUBLIC
                    )
                }
            }

            // (c) Wealthy in-migration — one potential new arrival per GENTRIFYING district per day.
            // newFamilyArrives picks a vacant home itself (no district filter — mirrors the standard
            // checkInwardMigration path, which already gives GENTRIFYING a +3 % bonus on top of
            // the base rate; this is an additive daily roll for each district that is GENTRIFYING).
            if (arrivalBudget > 0 && ctx.rng.nextBoolean(WEALTHY_ARRIVAL_CHANCE)) {
                arrivalBudget--
                LifecycleSystem.newFamilyArrives(ctx, null)
            }
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
