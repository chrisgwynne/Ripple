package com.ripple.town.core.simulation

import com.ripple.town.core.model.AspirationStatus
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldEvent

object LifeSatisfactionSystem {

    fun onLifeEvent(ctx: TickContext, resident: Resident, event: WorldEvent) {
        if (resident.id !in event.involvedResidentIds()) return
        val ls = resident.lifeSatisfaction
        when (event.type) {
            EventType.MARRIAGE, EventType.ENGAGEMENT -> ls.family += 15.0
            EventType.PERSON_BORN -> ls.family += 20.0
            EventType.SEPARATION, EventType.DIVORCE -> ls.family -= 20.0
            EventType.BREAK_UP -> ls.family -= 10.0
            EventType.FRIENDSHIP_FORMED -> ls.community += 8.0
            EventType.FRIENDSHIP_ENDED -> ls.community -= 8.0
            EventType.JOB_STARTED -> ls.career += 12.0
            EventType.JOB_LOST -> ls.career -= 18.0
            EventType.BUSINESS_OPENED -> ls.career += 25.0
            EventType.BUSINESS_CLOSED -> { ls.career -= 20.0; ls.purpose -= 15.0 }
            EventType.GOAL_COMPLETED -> { ls.purpose += 20.0; ls.career += 10.0 }
            EventType.GOAL_ABANDONED -> ls.purpose -= 12.0
            EventType.SKILL_MILESTONE -> { ls.career += 8.0; ls.purpose += 5.0 }
            EventType.ELECTION_WON -> { ls.community += 20.0; ls.legacy += 15.0 }
            EventType.PETITION_RESOLVED -> ls.community += 10.0
            EventType.COMMUNITY_EVENT -> ls.community += 5.0
            EventType.ILLNESS_STARTED -> ls.health -= 15.0
            EventType.ILLNESS_RECOVERED -> ls.health += 12.0
            EventType.INJURY -> ls.health -= 10.0
            EventType.BUSINESS_SUCCESSION -> ls.legacy += 25.0
            EventType.HOME_PURCHASED -> ls.legacy += 15.0
            EventType.DEBT_CRISIS -> { ls.career -= 15.0; ls.family -= 5.0 }
            EventType.FINANCIAL_RELIEF -> ls.career += 10.0
            EventType.BURGLARY, EventType.ARSON_ATTEMPT, EventType.CRIME_COMMITTED -> {
                ls.community -= 12.0; ls.career -= 8.0
            }
            EventType.PERSON_DIED -> ls.family -= 15.0
            else -> {}
        }
        ls.clampAll()
    }

    fun updateDaily(ctx: TickContext) {
        for (r in ctx.state.detailedResidents()) {
            if (!r.inTown) continue
            val ls = r.lifeSatisfaction
            val w = r.needs.wellbeing()
            // Slow convergence toward current needs state (0.2% per day)
            ls.health += (w - ls.health) * 0.002
            ls.family += (r.needs.social - ls.family) * 0.001
            ls.purpose += (r.needs.purpose - ls.purpose) * 0.001
            // Fulfilled aspirations lift purpose
            val fulfilledCount = r.aspirations.count { it.status == AspirationStatus.FULFILLED }.toDouble()
            if (fulfilledCount > 0.0) ls.purpose = (ls.purpose + 0.05 * fulfilledCount).coerceAtMost(100.0)
            ls.clampAll()
        }
    }
}
