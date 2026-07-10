package com.ripple.town.core.simulation

import com.ripple.town.core.model.EventType

/**
 * Historical importance: a base score by type, scaled by severity and how many
 * lives it touched. Events gain further importance later when other events
 * cite them as causes (see [TickContext.importanceBoosts]).
 */
object ImportanceScorer {

    /** Events at or above this score appear on the History timeline. */
    const val HISTORY_THRESHOLD = 30.0

    fun baseImportance(type: EventType, severity: Double, involvedCount: Int): Double {
        val base = when (type) {
            EventType.PERSON_DIED -> 55.0
            EventType.PERSON_BORN -> 40.0
            EventType.MARRIAGE -> 42.0
            EventType.DIVORCE -> 40.0
            EventType.SEPARATION -> 30.0
            EventType.BUSINESS_OPENED -> 45.0
            EventType.BUSINESS_CLOSED -> 50.0
            EventType.BUSINESS_EXPANDED -> 28.0
            EventType.ELECTION_WON -> 55.0
            EventType.ELECTION_CALLED -> 30.0
            EventType.RESIDENT_ARRIVED -> 35.0
            EventType.RESIDENT_LEFT_TOWN -> 32.0
            EventType.CRIME_COMMITTED -> 30.0
            EventType.CRIME_REPORTED -> 22.0
            EventType.WEATHER_DAMAGE -> 26.0
            EventType.BUILDING_CONSTRUCTED -> 38.0
            EventType.BUILDING_ABANDONED -> 25.0
            EventType.ILLNESS_DIAGNOSED -> 18.0
            EventType.JOB_LOST -> 16.0
            EventType.JOB_STARTED -> 12.0
            EventType.ENGAGEMENT -> 25.0
            EventType.AFFAIR_DISCOVERED -> 40.0
            EventType.TOWN_MILESTONE -> 60.0
            EventType.INTERVENTION_APPLIED -> 8.0
            EventType.COMMUNITY_EVENT -> 15.0
            EventType.SECRET_REVEALED -> 24.0
            EventType.RUMOUR_SPREAD -> 16.0
            else -> 8.0
        }
        val severityFactor = 0.6 + severity * 0.8
        val reachFactor = 1.0 + (involvedCount - 1).coerceAtMost(8) * 0.08
        return base * severityFactor * reachFactor
    }
}
