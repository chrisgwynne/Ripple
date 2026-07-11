package com.ripple.town.core.simulation

import com.ripple.town.core.model.EvidenceFragment
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.UnsolvedCase
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/**
 * Manages cold cases: crimes and incidents whose investigations went nowhere.
 *
 * When [CrimeSystem.investigate] decides a case goes cold (18 % of hidden crimes), this
 * system creates the [UnsolvedCase] with physical and testimonial evidence fragments, each
 * of which has a [EvidenceFragment.survivesUntil] timestamp after which it is effectively
 * lost.  Witnesses — the residents involved in the original event — are tracked; as they
 * die or leave town, the case becomes less recoverable.
 *
 * [updateDaily] checks whether all evidence has expired AND all witnesses are gone, at
 * which point the case is flagged [UnsolvedCase.isHopeless] and a quiet public notice fires
 * ("the trail has gone cold").  The case record itself is never deleted: the fact that truth
 * was once almost-recoverable and is now gone is itself part of the town's history.
 *
 * Theories accumulate via [addTheory], called from [RumourSystem] when a distorted rumour
 * of the original event has spread far enough that residents start connecting dots.
 */
object UnsolvedCaseSystem {

    // Evidence lifetime — random draw between these bounds, in days
    private const val EVIDENCE_MIN_DAYS = 365L    // 1 year
    private const val EVIDENCE_MAX_DAYS = 3650L   // 10 years

    // ---- Case creation -------------------------------------------------------------------------

    fun createCase(ctx: TickContext, triggerEvent: WorldEvent): UnsolvedCase {
        val state = ctx.state
        val witnesses = buildWitnessList(state, triggerEvent)
        val evidence = buildEvidence(ctx, triggerEvent)
        val case = UnsolvedCase(
            id = state.nextCaseId++,
            originalEventId = triggerEvent.id,
            eventTypeName = triggerEvent.type.name,
            description = triggerEvent.description,
            occurredAt = triggerEvent.time,
            witnessIds = witnesses.toMutableList(),
            evidence = evidence.toMutableList()
        )
        state.unsolvedCases[case.id] = case
        return case
    }

    private fun buildWitnessList(state: WorldState, event: WorldEvent): List<Long> =
        (listOfNotNull(event.sourceResidentId) + event.targetResidentIds)
            .filter { id -> state.resident(id)?.inTown == true }

    private fun buildEvidence(ctx: TickContext, event: WorldEvent): List<EvidenceFragment> {
        val fragments = mutableListOf<EvidenceFragment>()
        fun frag(desc: String, maxDays: Long = EVIDENCE_MAX_DAYS) {
            val surviveDays = ctx.rng.nextLong(EVIDENCE_MIN_DAYS, maxDays.coerceAtLeast(EVIDENCE_MIN_DAYS + 1L))
            fragments += EvidenceFragment(
                id = ctx.state.nextEvidenceId++, description = desc,
                foundAt = ctx.now, survivesUntil = ctx.now + surviveDays * SimTime.MINUTES_PER_DAY
            )
        }
        when (event.type) {
            EventType.BURGLARY -> {
                frag("Marks on the door frame", 730L)
                frag("A conflicting witness account")
            }
            EventType.ARSON_ATTEMPT -> {
                frag("Scorch pattern analysis", 730L)
                frag("An unsigned note found near the scene")
            }
            EventType.MISSING_PERSON_REPORTED -> {
                frag("Last known location and time")
                frag("Personal effects found at the river bank", 2000L)
                frag("Inconsistent witness statements", 1825L)
            }
            EventType.MUGGING -> {
                frag("Victim's description (recalled imperfectly)", 365L)
                frag("A dropped item at the scene", 730L)
            }
            else -> frag("Circumstantial evidence on record")
        }
        return fragments
    }

    // ---- Daily update --------------------------------------------------------------------------

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        for (case in state.unsolvedCases.values) {
            if (case.isHopeless) continue

            val livingEvidence = case.evidence.count { it.survivesUntil > ctx.now }
            val livingWitnesses = case.witnessIds.count { wid ->
                state.resident(wid)?.let { it.alive && it.inTown } == true
            }

            if (livingEvidence == 0 && livingWitnesses == 0) {
                case.isHopeless = true
                val snippet = case.description.take(55).trimEnd { it == ' ' || it == ',' }
                ctx.emit(
                    EventType.COLD_CASE_ARCHIVED,
                    "The last trace of evidence in the matter of \"$snippet...\" has been lost. The witnesses are gone. Whatever happened, it may never be known.",
                    severity = 0.2, visibility = EventVisibility.PUBLIC,
                    causeIds = listOf(case.originalEventId)
                )
            }
        }
    }

    // ---- Theory accumulation -------------------------------------------------------------------

    /** Record a resident's theory about an unsolved case (max five distinct theories). */
    fun addTheory(case: UnsolvedCase, theory: String) {
        if (case.theories.size >= 5) return
        if (theory !in case.theories) case.theories += theory
    }

    // ---- Newspaper helpers ---------------------------------------------------------------------

    /** Find a case that has just passed a round-number anniversary (1, 5, 10, 20 years). */
    fun anniversaryCase(state: WorldState): UnsolvedCase? {
        val year = SimTime.MINUTES_PER_YEAR
        val issueWindow = 7L * SimTime.MINUTES_PER_DAY
        return state.unsolvedCases.values
            .filter { !it.isHopeless }
            .firstOrNull { case ->
                val elapsed = state.time - case.occurredAt
                val years = elapsed / year
                years in listOf(1L, 5L, 10L, 20L) &&
                    (elapsed % year) < issueWindow
            }
    }

    fun yearsOpen(case: UnsolvedCase, now: Long): Long =
        (now - case.occurredAt) / SimTime.MINUTES_PER_YEAR
}
