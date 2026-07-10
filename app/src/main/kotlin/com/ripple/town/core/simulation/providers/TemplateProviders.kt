package com.ripple.town.core.simulation.providers

import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.Weather
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/**
 * Phase 4 backlog item: `NarrativeTextProvider`/`DialogueProvider` — "an LLM narrative layer
 * that writes flavour prose and dialogue *from* facts, never creating facts."
 *
 * This is the template-based **default** implementation of both seams — deliberately NOT
 * LLM-backed. A real LLM-backed provider needs an API key/budget/model decision that is the
 * user's call, not something to build blind (see docs/backlog.md, Phase 4). The whole point of
 * these interfaces already existing in [FutureProviders.kt] is that a real network/LLM
 * implementation can be swapped in later via [com.ripple.town.di.AppModule]'s `@Provides`
 * functions with **zero call-site changes** — every place that calls `elaborate()`/`lineFor()`
 * only ever depends on the interface, never on which implementation is bound.
 *
 * Same convention as [com.ripple.town.data.ChronicleBuilder] (this file's closest sibling) and
 * [com.ripple.town.core.simulation.NewspaperGenerator]: every sentence is a fixed template
 * filled from real structured data, never generated prose. Contract preserved exactly —
 * "describe the world, never mutate it": both functions here are pure reads over
 * [WorldEvent]/[WorldState]/[com.ripple.town.core.model.Resident], returning `String?`, and
 * never write back into simulation state.
 *
 * Determinism discipline: this is presentation-layer text, not simulation state, so it doesn't
 * need to replay identically bit-for-bit across app runs. But repeated events of the same type
 * reading identically every single time would feel obviously templated, so template variation
 * is still picked *deterministically* (by `event.id`/`residentId`, modulo the template list
 * size) rather than via `Math.random()`/`kotlin.random.Random` — the same "no unseeded
 * randomness" discipline the rest of this codebase applies everywhere else, kept here even
 * though nothing downstream depends on it for replay correctness.
 */
class TemplateNarrativeTextProvider : NarrativeTextProvider {

    override suspend fun elaborate(event: WorldEvent, state: WorldState): String? {
        val templates = TEMPLATES[event.type] ?: GENERIC_TEMPLATES
        val template = pickTemplate(templates, event.id)

        val source = event.sourceResidentId?.let { state.resident(it) }
        val targets = event.targetResidentIds.mapNotNull { state.resident(it) }
        val target = targets.firstOrNull()

        val sourceName = source?.fullName ?: "someone in town"
        val sourceOccupation = source?.occupation?.takeIf { it.isNotBlank() && it != "Unemployed" }
        val targetName = target?.fullName ?: "another resident"

        val severityWord = severityWord(event.severity)
        val timeOfDay = SimTime.timeOfDay(event.time).label.lowercase()
        val weatherWord = weatherWord(state.weather)

        var text = template
            .replace("{source}", sourceName)
            .replace("{sourceOcc}", sourceOccupation?.lowercase() ?: "resident")
            .replace("{target}", targetName)
            .replace("{severity}", severityWord)
            .replace("{timeOfDay}", timeOfDay)
            .replace("{weather}", weatherWord)
            .replace("{town}", state.townName)

        // If the event has a recorded cause, name-check it — a real elaboration, not just the
        // description restated, and never a fact this function invents itself: it only ever
        // reads causeIds that the engine already recorded.
        val cause = event.causeIds.firstOrNull()
        if (cause != null) {
            text += " " + CAUSE_SUFFIXES[pickIndex(CAUSE_SUFFIXES.size, event.id + 1)]
        }

        return text.trim()
    }

    private fun severityWord(severity: Double): String = when {
        severity >= 0.75 -> "profoundly"
        severity >= 0.5 -> "deeply"
        severity >= 0.25 -> "quietly"
        else -> "barely"
    }

    private fun weatherWord(w: Weather): String = when (w) {
        Weather.CLEAR -> "under clear skies"
        Weather.CLOUDY -> "under a grey sky"
        Weather.RAIN -> "in the rain"
        Weather.STORM -> "as the storm rolled in"
        Weather.FOG -> "through the fog"
        Weather.SNOW -> "with snow falling"
    }

    private fun pickIndex(size: Int, seed: Long): Int {
        if (size <= 0) return 0
        val m = seed % size
        return if (m < 0) (m + size).toInt() else m.toInt()
    }

    private fun pickTemplate(templates: List<String>, seed: Long): String =
        templates[pickIndex(templates.size, seed)]

    companion object {
        private val CAUSE_SUFFIXES = listOf(
            "It didn't come from nowhere.",
            "Anyone who'd been paying attention saw it coming.",
            "The town had been talking about it for a while.",
            "It was, in hindsight, only a matter of time."
        )

        private val GENERIC_TEMPLATES = listOf(
            "{source} was {severity} affected, {weather}, {timeOfDay}.",
            "Word of it moved through {town} {timeOfDay}, {weather}.",
            "It left its mark on {source}, {severity} so."
        )

        /**
         * One entry per [EventType] that already gets special treatment in
         * [com.ripple.town.core.simulation.NewspaperGenerator] and/or
         * [com.ripple.town.core.simulation.ImportanceScorer] — used as the prioritisation
         * signal the task brief pointed at, since those are the event types the rest of the
         * codebase already treats as narratively significant. Everything else falls back to
         * [GENERIC_TEMPLATES].
         */
        private val TEMPLATES: Map<EventType, List<String>> = mapOf(
            EventType.PERSON_DIED to listOf(
                "{source} passed {timeOfDay}, {weather}, and {town} felt the loss {severity}.",
                "News of {source}'s death moved quietly through {town}, {weather}.",
                "{town} said goodbye to {source} {timeOfDay} — a life {severity} felt by those who knew them."
            ),
            EventType.PERSON_BORN to listOf(
                "A new arrival, {timeOfDay}, {weather} — {town} welcomed them warmly.",
                "The family's newest member drew their first breath {timeOfDay}, {weather}."
            ),
            EventType.MARRIAGE to listOf(
                "{source} and {target} were married {timeOfDay}, {weather} — a day {town} won't soon forget.",
                "Vows were exchanged {timeOfDay}, {weather}; {source} and {target} began their life together."
            ),
            EventType.DIVORCE to listOf(
                "{source} and {target}'s marriage ended {timeOfDay}, {severity} felt on both sides.",
                "What had been {source} and {target} came apart {timeOfDay}, {weather}."
            ),
            EventType.SEPARATION to listOf(
                "{source} and {target} chose to part ways, {severity} weighed but decided all the same.",
                "{source} and {target} began living apart {timeOfDay}, {weather}."
            ),
            EventType.BUSINESS_OPENED to listOf(
                "{source} opened their doors {timeOfDay}, {weather} — a fresh start for {town}.",
                "A new venture from {source} began trading {timeOfDay}, {severity} hopeful about what's ahead."
            ),
            EventType.BUSINESS_CLOSED to listOf(
                "{source}'s shutters came down for the last time {timeOfDay}, {weather}.",
                "{town} lost a familiar storefront {timeOfDay} — {source} closed up, {severity} reluctantly."
            ),
            EventType.BUSINESS_EXPANDED to listOf(
                "{source}'s business grew {timeOfDay}, {weather} — a sign of better days.",
                "Word spread {timeOfDay} that {source} had taken on more than before."
            ),
            EventType.ELECTION_WON to listOf(
                "{source} was carried to office {timeOfDay}, {weather} — {town}'s new voice at the town hall.",
                "The votes were counted {timeOfDay}: {source} will lead {town} next."
            ),
            EventType.ELECTION_CALLED to listOf(
                "{town} readied itself for an election, called {timeOfDay}, {weather}.",
                "Talk of the coming vote spread through {town} {timeOfDay}."
            ),
            EventType.RESIDENT_ARRIVED to listOf(
                "{source} arrived in {town} {timeOfDay}, {weather}, looking for a fresh start.",
                "A new face turned up in {town} {timeOfDay} — {source}, {severity} hopeful about settling in."
            ),
            EventType.RESIDENT_LEFT_TOWN to listOf(
                "{source} left {town} {timeOfDay}, {weather} — {severity} missed already.",
                "{town} said its goodbyes to {source} {timeOfDay}."
            ),
            EventType.CRIME_COMMITTED to listOf(
                "Something went wrong {timeOfDay}, {weather} — {source} was at the centre of it, {severity} so.",
                "{town} felt a little less settled after what happened {timeOfDay}."
            ),
            EventType.CRIME_REPORTED to listOf(
                "A report reached the constable {timeOfDay}, {weather}, naming {source}.",
                "Word of trouble involving {source} reached the authorities {timeOfDay}."
            ),
            EventType.WEATHER_DAMAGE to listOf(
                "The {weather} left its mark on {town} {timeOfDay}, {severity} felt across the streets.",
                "{town} spent the {timeOfDay} counting the damage {weather}."
            ),
            EventType.BUILDING_CONSTRUCTED to listOf(
                "New walls rose in {town} {timeOfDay}, {weather} — {severity} a milestone for the street.",
                "Construction finished {timeOfDay}: one more building for {town}."
            ),
            EventType.BUILDING_ABANDONED to listOf(
                "Another building stood empty in {town} as of {timeOfDay}, {weather}.",
                "{town} watched a once-busy building fall quiet {timeOfDay}."
            ),
            EventType.ILLNESS_DIAGNOSED to listOf(
                "{source} received a diagnosis {timeOfDay}, {severity} taken to heart.",
                "News of {source}'s health reached {target} {timeOfDay}, {weather}."
            ),
            EventType.JOB_LOST to listOf(
                "{source} lost their position {timeOfDay}, {weather} — {severity} a hard blow to take.",
                "Work dried up for {source} {timeOfDay}; {town} took note."
            ),
            EventType.JOB_STARTED to listOf(
                "{source} started as {sourceOcc} {timeOfDay}, {weather}.",
                "A new chapter for {source}, who took up work as {sourceOcc} {timeOfDay}."
            ),
            EventType.ENGAGEMENT to listOf(
                "{source} and {target} announced their engagement {timeOfDay}, {weather} — {town} is delighted.",
                "A ring, a question, and a yes: {source} and {target} are engaged as of {timeOfDay}."
            ),
            EventType.AFFAIR_DISCOVERED to listOf(
                "What {source} had kept quiet came to light {timeOfDay}, {severity} felt by all involved.",
                "{town} learned {timeOfDay} what {source} had been hiding, {weather}."
            ),
            EventType.TOWN_MILESTONE to listOf(
                "{town} marked a milestone {timeOfDay}, {weather} — one for the record books.",
                "A moment {town} will remember: {timeOfDay}, {severity} felt by everyone watching."
            ),
            EventType.INTERVENTION_APPLIED to listOf(
                "A quiet nudge found {source} {timeOfDay}, {weather} — nothing anyone could quite put a finger on.",
                "{source} felt, {timeOfDay}, that something had gently shifted."
            ),
            EventType.COMMUNITY_EVENT to listOf(
                "{town} gathered {timeOfDay}, {weather} — the kind of day that gets talked about after.",
                "A community turnout {timeOfDay} brought {town} together, {severity} warmly."
            ),
            EventType.SECRET_REVEALED to listOf(
                "What had stayed hidden came out {timeOfDay}, {severity} shaking those closest to it.",
                "{town} learned the truth {timeOfDay}, {weather} — some things don't stay buried."
            ),
            EventType.RUMOUR_SPREAD to listOf(
                "Talk moved fast through {town} {timeOfDay}, {weather}, whether or not it was true.",
                "By {timeOfDay}, half of {town} had heard some version of the story."
            ),
            EventType.PETITION_STARTED to listOf(
                "Names began gathering on a petition {timeOfDay}, {weather} — {town} had something to say.",
                "{source} started a petition {timeOfDay}; {town} is watching to see where it goes."
            ),
            EventType.PETITION_RESOLVED to listOf(
                "The petition reached its conclusion {timeOfDay}, {severity} felt by those who'd signed it.",
                "{town} learned the outcome {timeOfDay}, {weather} — the matter is settled, for now."
            ),
            EventType.NATIONAL_PRESSURE to listOf(
                "Talk of wider troubles reached {town} {timeOfDay}, {weather} — the kind of news that travels from further afield.",
                "{town} felt the ripple of something bigger {timeOfDay}, {severity} so."
            )
        )
    }
}

/**
 * Template dialogue lines, personality-flavoured. Deterministic by `residentId` (not random),
 * matching [TemplateNarrativeTextProvider]'s discipline.
 *
 * Closed set of supported `situation` strings — there is no existing call site for this
 * interface anywhere in the app yet (confirmed by grep before this pass), so this defines and
 * documents the vocabulary itself rather than guessing at one:
 *  - "grieving"   — a death or major loss touched this resident.
 *  - "celebrating"— a wedding, birth, achievement, or milestone.
 *  - "working"    — currently on the job ([com.ripple.town.core.model.Activity.WORKING]).
 *  - "arguing"    — mid-conflict ([com.ripple.town.core.model.Activity.ARGUING]).
 *  - "socialising"— chatting/visiting ([com.ripple.town.core.model.Activity.SOCIALISING]/`VISITING`).
 *  - "worried"    — under financial or health strain.
 *  - "idle"       — the generic fallback for anything else / an unrecognised situation string.
 */
class TemplateDialogueProvider : DialogueProvider {

    override suspend fun lineFor(residentId: Long, situation: String): String? {
        val lines = LINES[situation.lowercase()] ?: LINES.getValue("idle")
        // Deterministic on the pair of (resident, situation) so the same resident doesn't say
        // the exact same line every time they're in the same situation, but still reproducibly
        // picks the same line for the same inputs (no Math.random()/kotlin.random.Random).
        val seed = residentId + situation.lowercase().hashCode()
        val index = ((seed % lines.size) + lines.size) % lines.size
        return lines[index.toInt()]
    }

    /** Personality-flavoured variant: picks by dominant trait first, then deterministically within it. */
    fun lineFor(residentId: Long, situation: String, dominantTrait: String): String {
        val byTrait = TRAIT_LINES[situation.lowercase()]?.get(dominantTrait.lowercase())
        val pool = byTrait ?: LINES[situation.lowercase()] ?: LINES.getValue("idle")
        val seed = residentId + situation.lowercase().hashCode()
        val index = ((seed % pool.size) + pool.size) % pool.size
        return pool[index.toInt()]
    }

    /**
     * Convenience overload: derives the dominant trait from [personality] itself (the highest
     * of the ten 0..1 continuous traits, ties broken by declaration order) and flavours the
     * line accordingly. The natural way UI call sites reach for this — they already have a
     * [Personality] on hand from [com.ripple.town.data.ResidentUi]/`Resident`, not a bare
     * trait-name string.
     */
    fun lineFor(residentId: Long, situation: String, personality: Personality): String =
        lineFor(residentId, situation, dominantTraitOf(personality))

    private fun dominantTraitOf(p: Personality): String {
        val traits = listOf(
            "kindness" to p.kindness,
            "ambition" to p.ambition,
            "curiosity" to p.curiosity,
            "sociability" to p.sociability,
            "patience" to p.patience,
            "honesty" to p.honesty,
            "courage" to p.courage,
            "discipline" to p.discipline,
            "empathy" to p.empathy,
            "impulsiveness" to p.impulsiveness
        )
        return traits.maxByOrNull { it.second }?.first ?: "kindness"
    }

    companion object {
        private val LINES: Map<String, List<String>> = mapOf(
            "grieving" to listOf(
                "It doesn't feel real yet.",
                "I keep expecting them to walk in.",
                "We'll manage. We have to."
            ),
            "celebrating" to listOf(
                "Can you believe it? Today, of all days!",
                "I don't think I've stopped smiling all morning.",
                "This calls for something special."
            ),
            "working" to listOf(
                "Busy one today — no time to chat.",
                "Just getting through the list, one thing at a time.",
                "Ask me again after this next bit."
            ),
            "arguing" to listOf(
                "This isn't over.",
                "I don't want to hear it right now.",
                "We are NOT doing this again."
            ),
            "socialising" to listOf(
                "It's good to see a friendly face.",
                "Tell me everything — I've got time.",
                "You'll never guess what I heard."
            ),
            "worried" to listOf(
                "I've had better weeks, honestly.",
                "Trying not to think about it too much.",
                "It'll sort itself out. Probably."
            ),
            "idle" to listOf(
                "Lovely day for it, isn't it?",
                "Can't complain, really.",
                "Same as ever around here."
            )
        )

        /**
         * A sparse override table: only situations where personality visibly changes the tone
         * are filled in (grieving and arguing show the clearest split). Traits not covered here
         * fall back to the plain [LINES] pool for that situation.
         */
        private val TRAIT_LINES: Map<String, Map<String, List<String>>> = mapOf(
            "grieving" to mapOf(
                "courage" to listOf(
                    "We'll get through this. We always do.",
                    "I'm not going to fall apart. Not now."
                ),
                "empathy" to listOf(
                    "I just keep thinking about everyone else who loved them.",
                    "I want to be there for the family. That's what matters now."
                ),
                "impulsiveness" to listOf(
                    "I need to do something, anything, right now.",
                    "I can't just sit here."
                )
            ),
            "arguing" to mapOf(
                "courage" to listOf(
                    "Say that again. I dare you.",
                    "I'm not backing down on this."
                ),
                "patience" to listOf(
                    "Let's not say things we'll regret.",
                    "Can we come back to this when we've both cooled off?"
                ),
                "kindness" to listOf(
                    "I don't want to fight. I just want you to understand.",
                    "This isn't how I wanted this to go."
                )
            ),
            "celebrating" to mapOf(
                "sociability" to listOf(
                    "Everyone needs to hear about this!",
                    "Let's get the whole street involved."
                ),
                "ambition" to listOf(
                    "This is just the start of it, you'll see.",
                    "Onwards — this is only the beginning."
                )
            )
        )
    }
}
