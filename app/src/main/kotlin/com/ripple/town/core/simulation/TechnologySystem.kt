package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.TechEraName

/**
 * Tracks the town's technological era. High-skill residents accumulate
 * innovation points that eventually push the town into successive eras.
 * Each advance gives a small but permanent productivity bonus that
 * EconomySystem can read from `WorldState.techLevel.productivityBonus`.
 */
object TechnologySystem {

    const val UPDATE_INTERVAL_DAYS = 30L
    private val ERA_THRESHOLDS = mapOf(
        TechEraName.ESTABLISHED to 50.0,
        TechEraName.MECHANISED to 200.0,
        TechEraName.MODERN to 500.0
    )

    fun updateMonthly(ctx: TickContext) {
        val state = ctx.state
        val tech = state.techLevel

        // Innovators: adults with meaningful combined skill in business/creativity/teaching.
        // Gate lowered from >120 (near-impossible in a small town) to >50 so at least some
        // residents contribute once they've been active for a few years.
        val innovators = state.detailedResidents().filter { r ->
            r.inTown && r.lifeStageAt(ctx.now) == LifeStage.ADULT &&
                (r.skills[SkillType.BUSINESS] ?: 0.0) + (r.skills[SkillType.CREATIVITY] ?: 0.0) +
                    (r.skills[SkillType.TEACHING] ?: 0.0) > 50.0
        }
        val monthlyPoints = innovators.size * 0.5
        tech.innovationPoints += monthlyPoints

        // Check for era advance
        val nextEra = TechEraName.values().firstOrNull { era ->
            tech.era.ordinal < era.ordinal && (tech.innovationPoints >= (ERA_THRESHOLDS[era] ?: Double.MAX_VALUE))
        }
        if (nextEra != null) {
            tech.era = nextEra
            tech.productivityBonus = 1.0 + nextEra.ordinal * 0.05
            tech.lastAdvancedAt = ctx.now
            ctx.emit(
                EventType.TOWN_MILESTONE,
                "${state.townName} enters the ${nextEra.label} era — ${innovators.size} innovative minds drove the change.",
                severity = 0.6
            )
        }
    }
}
