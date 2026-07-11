package com.ripple.town.core.simulation

import com.ripple.town.core.model.EmotionType
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LifeStage
import com.ripple.town.core.model.Memory
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime

/**
 * Daily system: memory stops being write-only. A resident's *current* context — where they are,
 * who they're with, what kind of thing just happened to them, what they're currently feeling —
 * is checked against their own [Resident.memories] for a match, and a genuine match "resurfaces"
 * it: a small, causally-linked, bounded nudge, not a re-lived event. See
 * `docs/simulation-rules.md` "Memory recall" for the full write-up.
 *
 * **Bounded-vs-duplicate design choice:** resurfacing does **not** create a new `Memory` row.
 * `Memory.lastRecalledAt` already exists on the model specifically for this ("last recalled",
 * not just "created") and, before this system, was only ever set once at creation
 * (`TickContext.addMemory`) and never read or updated again — dead weight on the data class. A
 * resurfacing event bumps that field to `ctx.now` and applies a small, capped bump to
 * `importance`/`emotionalIntensity` on the *original* memory. This was chosen over spawning a
 * new "echo" memory for three reasons: (1) it needs no new `MemoryType` or schema change: the
 * field to hold "this got recalled" already exists and was unused; (2) it respects
 * `TickContext.addMemory`'s existing 40-memory cap *for free* — recalling a memory can never by
 * itself grow the list, whereas a new echo-memory-per-resurfacing would eventually crowd out
 * older memories with generated "remembering the old bakery" filler; (3) it matches this
 * session's established tone (`PersonalityDevelopmentSystem`, `EmotionSystem`) of "small bounded
 * modifier on existing state", not "new parallel entity type". A resurfaced memory otherwise
 * stays exactly the memory it was — same id, same description, same belief — just freshened and
 * very slightly more vivid, which is a more honest model of what recall actually is (an older
 * memory brought back to mind) than an ever-growing log of separate recollection events.
 *
 * The corresponding real emotion is spawned via [EmotionSystem.spawnEmotion] at a reduced
 * intensity (`RESURFACE_INTENSITY_FACTOR`) of the *original* memory's `emotionalIntensity` — an
 * echo, not a re-living — and a low-severity, `PRIVATE` [EventType.MEMORY_RECALLED] event is
 * emitted for the cause chain, never newsworthy.
 *
 * Bounded frequency: a per-(resident, memory) cooldown reuses the exact `Resident.awareness`
 * string-ledger pattern `PersonalityDevelopmentSystem.onCooldown`/`markCooldown` already
 * established, so the same memory can resurface again later but never every single day it
 * happens to still match a trigger.
 */
object MemoryRecallSystem {

    /** Only memories at or above this importance are eligible to resurface — mirrors
     *  `PersonalityDevelopmentSystem`'s "recent significant memories" bar (45.0), reused here so
     *  a passing, low-importance memory never resurfaces. */
    const val MIN_RESURFACE_IMPORTANCE = 40.0

    /** The spawned echo emotion's intensity as a fraction of the original memory's
     *  `emotionalIntensity` — an echo, not a re-living. Per the brief's own worked example. */
    const val RESURFACE_INTENSITY_FACTOR = 0.4

    /** Small, capped bump applied to the original memory's `importance`/`emotionalIntensity` on
     *  resurfacing — a memory that keeps mattering stays a little more vivid, but this can never
     *  run away: both fields stay within [Memory]'s existing 0..100 convention, and the bump
     *  itself is tiny relative to that range. */
    const val IMPORTANCE_BUMP = 1.5
    const val INTENSITY_BUMP = 1.0

    /** Never resurface the *same* memory for the *same* resident more than once per this many
     *  in-game days — same cooldown shape and window as
     *  `PersonalityDevelopmentSystem.SAME_TRIGGER_COOLDOWN_DAYS`. */
    const val SAME_MEMORY_COOLDOWN_DAYS = 14L

    fun updateDaily(ctx: TickContext) {
        for (r in ctx.state.detailedResidents().sortedBy { it.id }) {
            if (!r.inTown || r.memories.isEmpty()) continue
            evaluate(ctx, r)
        }
    }

    private fun evaluate(ctx: TickContext, r: Resident) {
        val eligible = r.memories.filter { it.importance >= MIN_RESURFACE_IMPORTANCE }
        if (eligible.isEmpty()) return

        // --- Trigger 1: same location. Memory.description doesn't carry a direct building
        // reference (see Memory's fields), so — same practical proxy TownSheets.priorMemoryEcho
        // already uses at the UI layer — this matches the memory's description text against the
        // name of the building the resident is currently in.
        val here = r.currentBuildingId?.let { ctx.state.building(it) }
        if (here != null) {
            val match = eligible.firstOrNull { it.description.contains(here.name, ignoreCase = true) }
            if (match != null) resurface(ctx, r, match, "Being back at ${here.name} brought it all back.")
        }

        // --- Trigger 2: same person. Resident is now co-located with someone named in an old
        // memory's associatedResidentIds.
        val companyIds = ctx.state.residentsIn(r.currentBuildingId ?: -1L).map { it.id }.toSet() - r.id
        if (companyIds.isNotEmpty()) {
            val match = eligible.firstOrNull { it.associatedResidentIds.any { id -> id in companyIds } }
            if (match != null) {
                val who = match.associatedResidentIds.firstOrNull { it in companyIds }
                    ?.let { ctx.state.resident(it) }
                resurface(ctx, r, match, "Seeing ${who?.firstName ?: "them"} again stirred up an old memory.")
            }
        }

        // --- Trigger 3: emotional-state echo. Cheapest, most mechanical: an old memory whose
        // flavour matches a currently active emotion.
        if (r.activeEmotions.isNotEmpty()) {
            val activeTypes = r.activeEmotions.map { it.type }.toSet()
            val match = eligible.firstOrNull { echoEmotionFor(it.type) in activeTypes }
            if (match != null) resurface(ctx, r, match, "The way they're feeling right now echoes something from before.")
        }
    }

    /** Applies one resurfacing: cooldown check, `lastRecalledAt`/small bump on the *original*
     *  memory (never a new row — see class doc), a real echo emotion, and a private event. */
    private fun resurface(ctx: TickContext, r: Resident, memory: Memory, note: String) {
        if (onCooldown(ctx, r, memory)) return

        memory.lastRecalledAt = ctx.now
        memory.importance = (memory.importance + IMPORTANCE_BUMP).coerceAtMost(100.0)
        memory.emotionalIntensity = (memory.emotionalIntensity + INTENSITY_BUMP).coerceAtMost(100.0)

        val emotionType = echoEmotionFor(memory.type)
        if (emotionType != null) {
            EmotionSystem.spawnEmotion(
                ctx, r, emotionType,
                intensity = memory.emotionalIntensity * RESURFACE_INTENSITY_FACTOR,
                sourceEventId = memory.eventId,
                relatedResidentId = memory.associatedResidentIds.firstOrNull()
            )
        }

        ctx.emit(
            EventType.MEMORY_RECALLED,
            "${r.fullName}: $note",
            sourceResidentId = r.id,
            targetResidentIds = memory.associatedResidentIds,
            severity = 0.08,
            visibility = EventVisibility.PRIVATE,
            payload = mapOf("memoryId" to memory.id.toString(), "memoryType" to memory.type.name),
            causeIds = listOfNotNull(memory.eventId)
        )

        markCooldown(ctx, r, memory)
    }

    /** Nearest existing [EmotionType] flavour for a given [MemoryType], reused both to pick
     *  which active emotion counts as an "echo" match (trigger 3) and which emotion a
     *  resurfacing spawns. Deliberately not exhaustive fan-out to every [EmotionType] — only the
     *  flavours with an honest, direct memory-type analogue get one; memory types with no clean
     *  emotional analogue (e.g. [MemoryType.CHILDHOOD], generic in flavour) resurface (bump
     *  `lastRecalledAt`/importance) without spawning an emotion. */
    private fun echoEmotionFor(type: MemoryType): EmotionType? = when (type) {
        MemoryType.LOSS -> EmotionType.GRIEF
        MemoryType.FEAR -> EmotionType.FEAR
        MemoryType.BETRAYAL -> EmotionType.ANGER
        MemoryType.HUMILIATION -> EmotionType.SHAME
        MemoryType.ARGUMENT -> EmotionType.ANGER
        MemoryType.NEGLECT -> EmotionType.LONELINESS
        MemoryType.ACHIEVEMENT -> EmotionType.PRIDE
        MemoryType.INSPIRATION -> EmotionType.HOPE
        MemoryType.ROMANCE -> EmotionType.HOPE
        MemoryType.KINDNESS_RECEIVED -> EmotionType.RELIEF
        MemoryType.KINDNESS_GIVEN, MemoryType.HARDSHIP_SHARED, MemoryType.CHILDHOOD -> null
    }

    // ------------------------------------------------------------- cooldown (mirrors
    // PersonalityDevelopmentSystem.onCooldown/markCooldown exactly: same Resident.awareness
    // string-ledger convention, own namespaced prefix so entries can't collide with either that
    // system's or any other's flags stored in the same list.)

    private fun onCooldown(ctx: TickContext, r: Resident, memory: Memory): Boolean {
        val key = "$COOLDOWN_PREFIX${memory.id}@"
        val last = r.awareness.firstOrNull { it.startsWith(key) } ?: return false
        val ts = last.substringAfterLast('@').toLongOrNull() ?: return false
        return ctx.now - ts < SAME_MEMORY_COOLDOWN_DAYS * SimTime.MINUTES_PER_DAY
    }

    private fun markCooldown(ctx: TickContext, r: Resident, memory: Memory) {
        val key = "$COOLDOWN_PREFIX${memory.id}@"
        r.awareness.removeAll { it.startsWith(key) }
        r.awareness += "$key${ctx.now}"
    }

    private const val COOLDOWN_PREFIX = "memory_recall_cooldown:"

    // ------------------------------------------------------- childhood-to-adulthood influence

    /**
     * Small, bounded (`0.9..1.1`) multiplier reflecting how a significant [MemoryType.FEAR] or
     * [MemoryType.LOSS] memory formed in [LifeStage.CHILD]/[LifeStage.TEEN] quietly shades an
     * *adult's* decision-making in a similar situation, per the brief's "childhood-to-adulthood
     * effect". Deliberately keyed off real recorded data — a resident's actual `bornAt` and
     * actual childhood memories — never a hard-coded "destiny" flag. Returns exactly `1.0`
     * (no effect) when the resident has no matching childhood memory, so every call site that
     * doesn't wire this in is unaffected by construction.
     *
     * Four [ChildhoodSituation] cases are wired:
     * - `BUSINESS_FAILURE` → GoalSystem START_BUSINESS ambition bar (dampened)
     * - `FINANCIAL_HARDSHIP` → GoalSystem FIND_JOB urgency threshold (elevated)
     * - `CRIME_VICTIM` → CrimeSystem shoplifting honesty bar (raised, less likely to steal)
     * - `PARENTAL_LOSS` → GoalSystem FIND_PARTNER social threshold (lowered, seeking connection)
     */
    enum class ChildhoodSituation { BUSINESS_FAILURE, FINANCIAL_HARDSHIP, CRIME_VICTIM, PARENTAL_LOSS }

    fun childhoodInfluenceModifier(resident: Resident, situation: ChildhoodSituation): Double {
        val (types, keyword) = when (situation) {
            ChildhoodSituation.BUSINESS_FAILURE -> setOf(MemoryType.LOSS, MemoryType.FEAR) to "business"
            ChildhoodSituation.FINANCIAL_HARDSHIP -> setOf(MemoryType.LOSS, MemoryType.HARDSHIP_SHARED) to "money"
            ChildhoodSituation.CRIME_VICTIM -> setOf(MemoryType.FEAR, MemoryType.LOSS) to "stolen"
            ChildhoodSituation.PARENTAL_LOSS -> setOf(MemoryType.LOSS) to "died"
        }
        val hit = resident.memories.any { m ->
            m.type in types &&
                m.importance >= MIN_RESURFACE_IMPORTANCE &&
                childhoodStageAt(resident, m.createdAt) &&
                m.description.contains(keyword, ignoreCase = true)
        }
        if (!hit) return 1.0
        return when (situation) {
            // A childhood memory of a family business failing slightly dampens confidence in
            // starting one's own — DecisionSystem/GoalSystem read this as a multiplier on
            // START_BUSINESS-related confidence, never a hard block.
            ChildhoodSituation.BUSINESS_FAILURE -> 0.9
            // A childhood memory of family financial hardship slightly raises urgency around
            // finding paid work — read as a multiplier that lowers the effective threshold
            // (i.e. the resident is more readily nudged towards FIND_JOB), not a courage/risk term.
            ChildhoodSituation.FINANCIAL_HARDSHIP -> 1.1
            // A childhood theft or crime memory slightly raises the effective honesty bar needed
            // to proceed — the resident has seen the cost from the victim's side, not the thief's.
            // Used as a multiplier on the honesty filter in CrimeSystem (0.9 → harder to qualify).
            ChildhoodSituation.CRIME_VICTIM -> 0.9
            // A childhood bereavement lowers the threshold to seek a partner — loss early in life
            // creates a slightly stronger pull toward connection. Multiplied onto the social need
            // floor in GoalSystem (1.1 → slightly looser trigger).
            ChildhoodSituation.PARENTAL_LOSS -> 1.1
        }
    }

    private fun childhoodStageAt(resident: Resident, atTime: Long): Boolean {
        val stage = resident.lifeStageAt(atTime)
        return stage == LifeStage.CHILD || stage == LifeStage.TEEN
    }
}
