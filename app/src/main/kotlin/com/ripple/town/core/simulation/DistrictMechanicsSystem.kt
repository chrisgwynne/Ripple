package com.ripple.town.core.simulation

import com.ripple.town.core.model.DistrictCharacter
import com.ripple.town.core.model.SimTime

/**
 * Translates district character labels into mechanical effects on residents and businesses.
 *
 * Prior to this system, [DistrictCharacterSystem] classified districts but the label was read-only —
 * a DECLINING district looked different from a PROSPEROUS one but produced identical outcomes.
 * This system closes that loop: character now has real, bounded consequences for people who live
 * and trade within it.
 *
 * Effects are small per-tick nudges (not hard overrides) so district character shapes outcomes
 * without deterministically scripting them.
 *
 * Addresses audit finding #39: "Make district character consumable — DECLINING/GENTRIFYING
 * affects rent, crime propensity, formation, migration."
 */
object DistrictMechanicsSystem {

    const val UPDATE_INTERVAL_DAYS = 7L

    fun updateWeekly(ctx: TickContext) {
        val state = ctx.state
        val dayIndex = SimTime.dayIndex(ctx.now)

        for (district in state.districts.values) {
            val char = district.character

            // ─── Resident effects ──────────────────────────────────────────────
            // Get residents whose home building is in this district
            val residents = state.livingResidents().filter { r ->
                r.homeBuildingId?.let { state.building(it)?.districtId == district.id } == true
            }

            for (r in residents) {
                val n = r.needs
                when (char) {
                    DistrictCharacter.HIGH_CRIME -> {
                        n.stress = (n.stress + 0.3).coerceAtMost(100.0)
                        n.safety = (n.safety - 0.2).coerceAtLeast(0.0)
                    }
                    DistrictCharacter.DECLINING -> {
                        n.financialSecurity = (n.financialSecurity - 0.15).coerceAtLeast(0.0)
                    }
                    DistrictCharacter.PROSPEROUS -> {
                        n.comfort = (n.comfort + 0.1).coerceAtMost(100.0)
                    }
                    DistrictCharacter.DERELICT -> {
                        n.stress = (n.stress + 0.5).coerceAtMost(100.0)
                        n.safety = (n.safety - 0.3).coerceAtLeast(0.0)
                        n.comfort = (n.comfort - 0.2).coerceAtLeast(0.0)
                    }
                    DistrictCharacter.REGENERATING -> {
                        n.purpose = (n.purpose + 0.1).coerceAtMost(100.0)
                    }
                    DistrictCharacter.FAMILY_SUBURB -> {
                        n.social = (n.social + 0.1).coerceAtMost(100.0)
                    }
                    else -> Unit
                }
            }

            // ─── Business demand nudges ────────────────────────────────────────
            val businesses = state.businesses.values.filter { biz ->
                biz.open && state.building(biz.buildingId)?.districtId == district.id
            }

            val demandNudge = when (char) {
                DistrictCharacter.PROSPEROUS   ->  0.3
                DistrictCharacter.GENTRIFYING  ->  0.15
                DistrictCharacter.REGENERATING ->  0.05
                DistrictCharacter.DECLINING    -> -0.4
                DistrictCharacter.DERELICT     -> -0.8
                DistrictCharacter.HIGH_CRIME   -> -0.3
                else -> 0.0
            }

            if (demandNudge != 0.0) {
                for (biz in businesses) {
                    biz.demand = (biz.demand + demandNudge).coerceIn(0.0, 100.0)
                }
            }
        }
    }
}
