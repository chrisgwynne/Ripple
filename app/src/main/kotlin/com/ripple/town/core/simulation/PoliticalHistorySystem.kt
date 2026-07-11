package com.ripple.town.core.simulation

import com.ripple.town.core.model.*

/**
 * Maintains the town's political memory: finalises legacy statements for
 * completed governments, surfaces historical context for the newspaper,
 * and provides query helpers for "the investment made thirty years ago
 * is why this district is thriving today" style narratives.
 */
object PoliticalHistorySystem {

    const val UPDATE_INTERVAL_DAYS = 360L  // annual

    fun updateAnnually(ctx: TickContext) {
        val state = ctx.state
        // Finalise legacy statements for any completed governments that don't have one
        state.governmentRecords
            .filter { it.endedAt != null && it.legacyStatement.isEmpty() }
            .forEach { rec -> rec.legacyStatement = buildLegacy(rec, state) }
        // Note delayed-effect policies that have activated in the government record
        state.currentGovernmentId?.let { gid ->
            val rec = state.governmentRecords.find { it.id == gid } ?: return@let
            val dayIndex = SimTime.dayIndex(state.time)
            state.activePolicies.values
                .filter { pol ->
                    pol.proposedByPartyId == rec.partyId &&
                    pol.activatesAtDay != null && pol.activatesAtDay!! <= dayIndex &&
                    !rec.policiesPassed.contains(pol.id)
                }
                .forEach { pol -> rec.policiesPassed += pol.id }
        }
    }

    internal fun buildLegacy(record: GovernmentRecord, state: WorldState): String {
        val yrs = ((record.endedAt ?: state.time) - record.startedAt) / SimTime.MINUTES_PER_YEAR
        val delayed = record.policiesPassed.mapNotNull { id -> state.activePolicies[id] }
            .filter { it.delayDays > 0 }
        return when {
            record.corruption ->
                "${record.leaderName}'s time in office was defined by scandal — a period ${state.townName} would rather forget"
            record.finalApproval > 72 && record.policiesPassed.size >= 3 ->
                "${record.leaderName} is remembered as one of ${state.townName}'s most effective mayors, " +
                    "having passed ${record.policiesPassed.size} major policies and left office with broad popular support"
            record.finalApproval < 30 ->
                "${record.leaderName}'s government was deeply unpopular — residents blamed ${record.partyName} " +
                    "for the direction the town took during those years"
            delayed.isNotEmpty() ->
                "${record.leaderName}'s long-term investment in ${delayed.first().title.lowercase()} " +
                    "would not be felt for years, but it shaped ${state.townName}'s future"
            record.policiesPassed.isEmpty() ->
                "${record.leaderName} served ${yrs.coerceAtLeast(1)} year(s) as mayor " +
                    "without passing major policy — a period of watchful stability"
            else ->
                "${record.leaderName} led ${record.partyName} through ${yrs.coerceAtLeast(1)} year(s) " +
                    "of steady governance in ${state.townName}"
        }
    }

    /** Most surprising legacy (highest absolute approval deviation) — for newspaper use. */
    fun mostMemorableLegacy(state: WorldState): String? =
        state.governmentRecords
            .filter { it.endedAt != null && it.legacyStatement.isNotEmpty() }
            .maxByOrNull { kotlin.math.abs(it.finalApproval - 50.0) }
            ?.legacyStatement

    /** Summaries of the last N completed governments — for the History screen. */
    fun recentLegacies(state: WorldState, count: Int = 3): List<String> =
        state.governmentRecords
            .filter { it.endedAt != null }
            .takeLast(count)
            .map { rec -> rec.legacyStatement.ifEmpty { buildLegacy(rec, state) } }
}
