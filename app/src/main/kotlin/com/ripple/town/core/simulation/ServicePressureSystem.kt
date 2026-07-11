package com.ripple.town.core.simulation

import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.ServiceType

/**
 * Translates [ServicePressure] satisfaction scores into small daily nudges on resident
 * [Needs], making under-resourced services physically felt by the people who depend on them.
 *
 * Runs once per week (same cadence as [DistrictCharacterSystem]) to avoid per-tick noise.
 * Only detailed residents are affected — background residents are too lightweight to
 * carry individual need states, and this would break their fast-path optimisation.
 *
 * Connections:
 * - Low school satisfaction → children/teens lose purpose, parents gain stress
 * - Low healthcare → all residents lose a small health point daily (poor care)
 * - Low police → all residents lose safety; high-stress residents gain fear
 * - Low retail → hunger need degrades slightly (poor access to food)
 * - Low employment → unemployed adults lose financial security faster
 */
object ServicePressureSystem {

    const val UPDATE_INTERVAL_DAYS = 7L

    fun updateWeekly(ctx: TickContext) {
        val state = ctx.state
        val pressures = state.servicePressures

        fun sat(service: ServiceType): Double =
            pressures[service.name]?.satisfactionScore ?: 1.0

        val schoolSat    = sat(ServiceType.SCHOOL)
        val healthSat    = sat(ServiceType.HEALTHCARE)
        val policeSat    = sat(ServiceType.POLICE)
        val retailSat    = sat(ServiceType.RETAIL)
        val employmentSat = sat(ServiceType.EMPLOYMENT)

        for (r in state.residents.values) {
            if (!r.alive || !r.inTown) continue
            if (r.detailLevel != DetailLevel.DETAILED) continue
            val stage = r.lifeStageAt(state.time)
            val n = r.needs

            // Healthcare shortfall: everyone loses small health buffer.
            if (healthSat < 0.8) {
                val penalty = (0.8 - healthSat) * 0.5
                n.health = (n.health - penalty).coerceAtLeast(0.0)
            }

            // Police shortfall: safety need erodes.
            if (policeSat < 0.7) {
                val penalty = (0.7 - policeSat) * 1.5
                n.safety = (n.safety - penalty).coerceAtLeast(0.0)
            }

            // Retail shortfall: food access degrades.
            if (retailSat < 0.6) {
                val penalty = (0.6 - retailSat) * 0.8
                n.hunger = (n.hunger - penalty).coerceAtLeast(0.0)
            }

            // School shortfall: children/teens lose purpose, adults with children gain stress.
            if (schoolSat < 0.7 && (stage == LifeStage.CHILD || stage == LifeStage.TEEN)) {
                val penalty = (0.7 - schoolSat) * 2.0
                n.purpose = (n.purpose - penalty).coerceAtLeast(0.0)
            }
            if (schoolSat < 0.7 && stage == LifeStage.ADULT) {
                val hasChild = r.childIds.any { state.resident(it)?.inTown == true }
                if (hasChild) n.stress = (n.stress + (0.7 - schoolSat) * 1.5).coerceAtMost(100.0)
            }

            // Employment shortfall: unemployed adults lose financial security faster.
            if (employmentSat < 0.5 && stage == LifeStage.ADULT &&
                state.employmentOf(r) == null && r.ageAt(state.time) < 66) {
                val penalty = (0.5 - employmentSat) * 2.5
                n.financialSecurity = (n.financialSecurity - penalty).coerceAtLeast(0.0)
            }
        }
    }
}
