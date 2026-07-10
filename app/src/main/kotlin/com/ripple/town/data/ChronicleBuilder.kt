package com.ripple.town.data

import com.ripple.town.core.model.SimTime
import com.ripple.town.core.simulation.ImportanceScorer

/**
 * Phase 4 backlog item: "Shareable town chronicles: export a family's saga as text/images."
 *
 * Builds a readable, multi-generation text narrative for one resident — their own life plus
 * traceable ancestors (parents, grandparents) and descendants (children, grandchildren) — from
 * existing structured data only (memories, events, family-id fields already on [ResidentUi]/
 * [WorldUi]). Deliberately template-based, NOT free-form LLM prose: this is explicitly the
 * scoped-down sibling of the still-open `NarrativeTextProvider`/`DialogueProvider` backlog item
 * (see docs/backlog.md, Phase 4), which owns actual generated prose. Every sentence here is a
 * fixed template filled from real fields, the same convention `NewspaperGenerator` and
 * `WorldRepository.buildEraSummary` already use to turn structured events into readable text.
 *
 * Family traversal mirrors `feature/people/FamilyTreeScreen.kt`'s existing generation-walk
 * (grandparents/parents/children/grandchildren via `motherId`/`fatherId`/`childIds`, capped at
 * two generations each way) rather than reinventing it — this file only adds the "turn a person
 * into a paragraph" step on top of that same shape. Per-person quoted memories are capped at 4,
 * matching `EraSummary.definingMemories`'s existing cap.
 *
 * This builder is pure UI-layer: it reads only the already-loaded [WorldUi] snapshot plus a
 * caller-supplied map of per-resident "notable witnessed public events" (sourced from the event
 * log by [WorldRepository.buildChronicle], since that requires a DB query `WorldUi` doesn't
 * carry) — it does not touch the engine-confined `WorldState` itself.
 */
object ChronicleBuilder {

    private const val MAX_QUOTED_MEMORIES = 4
    private const val MAX_WITNESSED_EVENTS = 3

    /** One person's contribution to the saga: bounded facts, nothing invented. */
    data class PersonFacts(
        val resident: ResidentUi,
        val roleLabel: String,
        /** Notable PUBLIC events witnessed in this person's lifetime, most important first. */
        val witnessed: List<String> = emptyList()
    )

    /**
     * Builds the full chronicle text for [subjectId]. [witnessedByResidentId] supplies the
     * already-queried notable-event descriptions per resident (empty list if none/unknown) —
     * kept as a plain parameter so this object stays free of suspend/DB concerns.
     */
    fun build(
        world: WorldUi,
        subjectId: Long,
        witnessedByResidentId: Map<Long, List<String>>
    ): String? {
        val subject = world.resident(subjectId) ?: return null

        val maternalGrandmother = world.resident(subject.motherId)?.let { world.resident(it.motherId) }
        val maternalGrandfather = world.resident(subject.motherId)?.let { world.resident(it.fatherId) }
        val paternalGrandmother = world.resident(subject.fatherId)?.let { world.resident(it.motherId) }
        val paternalGrandfather = world.resident(subject.fatherId)?.let { world.resident(it.fatherId) }
        val mother = world.resident(subject.motherId)
        val father = world.resident(subject.fatherId)
        val children = subject.childIds.mapNotNull { world.resident(it) }
        val grandchildren = children.flatMap { c -> c.childIds.mapNotNull { world.resident(it) } }
            .distinctBy { it.id }

        fun facts(r: ResidentUi?, role: String): PersonFacts? =
            r?.let { PersonFacts(it, role, witnessedByResidentId[it.id].orEmpty()) }

        val grandparents = listOfNotNull(
            facts(paternalGrandfather, "Grandfather"),
            facts(paternalGrandmother, "Grandmother"),
            facts(maternalGrandfather, "Grandfather"),
            facts(maternalGrandmother, "Grandmother")
        )
        val parents = listOfNotNull(facts(father, "Father"), facts(mother, "Mother"))
        val childFacts = children.map { PersonFacts(it, "Child", witnessedByResidentId[it.id].orEmpty()) }
        val grandchildFacts = grandchildren.map { PersonFacts(it, "Grandchild", witnessedByResidentId[it.id].orEmpty()) }

        return buildString {
            appendLine("THE CHRONICLE OF THE ${subject.surname.uppercase()} FAMILY")
            appendLine("as remembered by ${world.townName}, ${SimTime.formatDate(world.time)}")
            appendLine()
            appendLine("=".repeat(48))
            appendLine()

            if (grandparents.isNotEmpty()) {
                appendLine("ANCESTORS")
                appendLine()
                grandparents.forEach { appendLine(paragraphFor(it, world)); appendLine() }
            }

            if (parents.isNotEmpty()) {
                appendLine("PARENTS")
                appendLine()
                parents.forEach { appendLine(paragraphFor(it, world)); appendLine() }
            }

            appendLine("THIS LIFE: ${subject.name.uppercase()}")
            appendLine()
            appendLine(paragraphFor(PersonFacts(subject, "—", witnessedByResidentId[subject.id].orEmpty()), world, isSubject = true))
            appendLine()

            if (childFacts.isNotEmpty()) {
                appendLine("CHILDREN")
                appendLine()
                childFacts.forEach { appendLine(paragraphFor(it, world)); appendLine() }
            }

            if (grandchildFacts.isNotEmpty()) {
                appendLine("GRANDCHILDREN")
                appendLine()
                grandchildFacts.forEach { appendLine(paragraphFor(it, world)); appendLine() }
            }

            appendLine("-".repeat(48))
            append("Generated by Ripple from ${world.townName}'s own recorded history.")
        }
    }

    private fun paragraphFor(facts: PersonFacts, world: WorldUi, isSubject: Boolean = false): String {
        val r = facts.resident
        return buildString {
            append(r.name)
            if (facts.roleLabel != "—") append(" (${facts.roleLabel})")
            append(". ")

            append(
                if (!r.alive) "Lived to ${r.age}, and died"
                else if (isSubject) "Currently ${r.age} years old, living"
                else "Now ${r.age}, living"
            )
            if (!r.alive && !r.causeOfDeath.isNullOrBlank()) append(" — ${r.causeOfDeath}")
            append(" in ${world.townName}. ")

            if (r.occupation.isNotBlank() && r.occupation != "Unemployed") {
                append(if (!r.alive) "Worked as ${r.occupation.lowercase()}. " else "Works as ${r.occupation.lowercase()}. ")
            }

            if (r.childIds.isNotEmpty()) {
                append("Parent of ${r.childIds.size} child${if (r.childIds.size == 1) "" else "ren"}. ")
            }
            if (r.relationshipStatusLabel.isNotBlank() && r.relationshipStatusLabel != "Single") {
                append("${r.relationshipStatusLabel} at last count. ")
            }

            val memories = r.memories.take(MAX_QUOTED_MEMORIES)
            if (memories.isNotEmpty()) {
                append("Remembered for: ")
                append(memories.joinToString("; ") { it.description })
                append(". ")
            }

            if (facts.witnessed.isNotEmpty()) {
                append("Lived through: ")
                append(facts.witnessed.take(MAX_WITNESSED_EVENTS).joinToString("; "))
                append(". ")
            }
        }.trim()
    }
}
