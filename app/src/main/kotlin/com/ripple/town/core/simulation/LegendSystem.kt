package com.ripple.town.core.simulation

import com.ripple.town.core.model.BeliefTopic
import com.ripple.town.core.model.EventType
import com.ripple.town.core.model.EventVisibility
import com.ripple.town.core.model.LegendSubject
import com.ripple.town.core.model.LocalLegend
import com.ripple.town.core.model.MemoryType
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.WorldEvent
import com.ripple.town.core.model.WorldState

/**
 * Manages local legends: folk-beliefs that emerge from coincidence, spread by word of mouth,
 * and slowly fade unless reinforced.
 *
 * Legends are never announced.  They appear in the newspaper only as "word around town" asides.
 * No achievement fires.  No meter fills.  The belief just... exists, held with varying
 * conviction by residents who were never told they were part of a mystery system.
 *
 * Spawn triggers (called inline from the systems that produce relevant events):
 *   ARSON_ATTEMPT       → always (dramatic; always legend-worthy)
 *   BURGLARY            → 20 % chance
 *   WEATHER_DAMAGE      → 30 % chance (cursed land / flood-prone spot)
 *   MISSING_PERSON_REPORTED → 50 % chance
 *   PERSON_DIED (young) → 12 % chance
 *
 * Spread model: once per day, each active legend has a chance (scaled by strength) of
 * travelling one hop along a high-familiarity relationship edge.  Strength rises when
 * reinforced by a related event; falls 0.08/day when uneventful.  A legend dies once
 * strength drops below 4.0.
 *
 * Long-running businesses get a passive "lucky place" legend at 30 years.
 */
object LegendSystem {

    private const val DECAY_PER_DAY = 0.08
    private const val DEATH_THRESHOLD = 4.0
    private const val REINFORCE_BOOST = 15.0
    private const val LONGEVITY_YEARS = 30L
    private const val MAX_ACTIVE_LEGENDS = 20
    /** Fraction of detailed residents who must believe a legend before it colours their worldview. */
    private const val WIDE_BELIEF_THRESHOLD = 0.30
    /** Annual community-loyalty drift for believers of a widely-held legend (on the -1..1 scale). */
    private const val LEGEND_BELIEF_BOOST = 0.02

    // ---- Spawn ---------------------------------------------------------------------------------

    /**
     * Called inline from CrimeSystem, SeasonalEventSystem, LifecycleSystem, and IncidentSystem
     * after their relevant events fire.  Spawns a legend probabilistically from the event, or
     * reinforces an existing one for the same subject.
     */
    fun considerSpawn(ctx: TickContext, event: WorldEvent) {
        val active = ctx.state.localLegends.values.count { it.decayedAt == null }
        if (active >= MAX_ACTIVE_LEGENDS) return

        val state = ctx.state
        val subject: LegendSubject
        val subjectId: Long?
        val subjectName: String
        val text: String
        val strength: Double

        when (event.type) {
            EventType.ARSON_ATTEMPT -> {
                val building = event.buildingId?.let { state.buildings[it] } ?: return
                subject = LegendSubject.PLACE; subjectId = building.id; subjectName = building.name
                text = "There's something sour about ${building.name}. Whatever happened there, it left a mark."
                strength = 35.0
            }
            EventType.BURGLARY -> {
                if (!ctx.rng.nextBoolean(0.20)) return
                val building = event.buildingId?.let { state.buildings[it] } ?: return
                subject = LegendSubject.PLACE; subjectId = building.id; subjectName = building.name
                text = "People have started avoiding ${building.name} after dark. Something's happened there."
                strength = 20.0
            }
            EventType.WEATHER_DAMAGE -> {
                if (!ctx.rng.nextBoolean(0.30)) return
                val building = event.buildingId?.let { state.buildings[it] } ?: return
                subject = LegendSubject.PLACE; subjectId = building.id; subjectName = building.name
                text = "That stretch by ${building.name} floods every time. The land was never right for building on."
                strength = 25.0
            }
            EventType.MISSING_PERSON_REPORTED -> {
                if (!ctx.rng.nextBoolean(0.50)) return
                val resident = event.sourceResidentId?.let { state.resident(it) } ?: return
                subject = LegendSubject.PERSON; subjectId = resident.id
                subjectName = resident.fullName
                text = "Nobody really knows what happened to ${resident.firstName} ${resident.surname}. The official account never quite added up."
                strength = 30.0
            }
            EventType.PERSON_DIED -> {
                val resident = event.sourceResidentId?.let { state.resident(it) } ?: return
                val age = SimTime.ageYears(resident.bornAt, event.time)
                if (age > 55) return
                if (!ctx.rng.nextBoolean(0.12)) return
                subject = LegendSubject.PERSON; subjectId = resident.id
                subjectName = resident.fullName
                text = "They still talk about ${resident.firstName} ${resident.surname}. Gone too soon, and nobody's ever been sure exactly why."
                strength = 22.0
            }
            else -> return
        }

        spawnOrReinforce(ctx, subject, subjectId, subjectName, text, event.id, strength)
    }

    private fun spawnOrReinforce(
        ctx: TickContext, subject: LegendSubject, subjectId: Long?,
        subjectName: String, text: String, bornFromEventId: Long?, strength: Double
    ) {
        val state = ctx.state
        val existing = if (subjectId != null) {
            state.localLegends.values.firstOrNull {
                it.subject == subject && it.subjectId == subjectId && it.decayedAt == null
            }
        } else null

        if (existing != null) {
            existing.strength = (existing.strength + REINFORCE_BOOST).coerceAtMost(100.0)
            return
        }

        // The first "believer" is whoever witnessed the event that spawned the legend.
        // We don't have that resident here, so the set starts empty and fills on first spread.
        val legend = LocalLegend(
            id = state.nextLegendId++,
            subject = subject, subjectId = subjectId, subjectName = subjectName,
            text = text, strength = strength,
            bornFromEventId = bornFromEventId, createdAt = ctx.now, lastSpreadAt = ctx.now,
            believerCount = 1
        )
        state.localLegends[legend.id] = legend
    }

    // ---- Daily update --------------------------------------------------------------------------

    fun updateDaily(ctx: TickContext) {
        val state = ctx.state
        val residents = state.detailedResidents()

        for (legend in state.localLegends.values) {
            if (legend.decayedAt != null) continue

            // Passive decay
            legend.strength -= DECAY_PER_DAY

            // Long-running businesses reinforce their own legend by simply existing
            if (legend.subject == LegendSubject.BUSINESS) {
                val biz = legend.subjectId?.let { state.businesses[it] }
                if (biz != null && biz.open) {
                    val ageYears = (ctx.now - biz.openedAt) / SimTime.MINUTES_PER_YEAR
                    if (ageYears >= LONGEVITY_YEARS) {
                        legend.strength = (legend.strength + 0.02).coerceAtMost(100.0)
                    }
                }
            }

            // Spread: a chance each day scaled by strength
            val spreadChance = 0.04 + (legend.strength / 100.0) * 0.12
            if (residents.isNotEmpty() && ctx.rng.nextBoolean(spreadChance)) {
                spreadLegend(ctx, legend, residents)
            }

            // When a legend reaches wide believership (>30% of detailed residents), it subtly
            // reinforces the community-loyalty belief of each believer — the legend has become
            // shared folk-identity, not just gossip.  Small drift applied at most once per year
            // per believer (gated by the same awareness-flag cooldown BeliefSystem uses).
            val detailedCount = residents.size
            if (detailedCount > 0 && legend.believers.size.toDouble() / detailedCount > WIDE_BELIEF_THRESHOLD) {
                for (believerId in legend.believers) {
                    val believer = state.resident(believerId) ?: continue
                    if (!believer.inTown) continue
                    val cooldownKey = "legend_belief:${legend.id}"
                    val alreadyCooling = believer.awareness.any { it.startsWith("$cooldownKey@") }
                    if (alreadyCooling) {
                        val ts = believer.awareness.firstOrNull { it.startsWith("$cooldownKey@") }
                            ?.substringAfterLast('@')?.toLongOrNull()
                        if (ts != null && ctx.now - ts < SimTime.MINUTES_PER_YEAR) continue
                        believer.awareness.removeAll { it.startsWith("$cooldownKey@") }
                    }
                    BeliefSystem.applyLegendBelief(ctx, believer, BeliefTopic.COMMUNITY_LOYALTY, LEGEND_BELIEF_BOOST, legend.id)
                    believer.awareness += "$cooldownKey@${ctx.now}"
                }
            }

            if (legend.strength < DEATH_THRESHOLD) {
                legend.decayedAt = ctx.now
            }
        }

        checkBusinessLongevityLegends(ctx)
    }

    private fun spreadLegend(ctx: TickContext, legend: LocalLegend, residents: List<com.ripple.town.core.model.Resident>) {
        // Pick a random believer (proxy: any resident) as teller; find someone in their network
        val teller = residents[ctx.rng.nextInt(residents.size)]
        val possibleListeners = ctx.state.relationshipsOf(teller.id)
            .filter { it.familiarity > 45.0 }
            .mapNotNull { rel ->
                val otherId = if (rel.aId == teller.id) rel.bId else rel.aId
                ctx.state.resident(otherId)?.takeIf { it.inTown }
            }
        if (possibleListeners.isEmpty()) return
        val listener = possibleListeners[ctx.rng.nextInt(possibleListeners.size)]

        // Each successful spread ticks up believer count and records belief on the listener
        if (ctx.rng.nextBoolean(0.4) && legend.id !in listener.knownLegendIds) {
            legend.believerCount++
            legend.believers += listener.id
            legend.strength = (legend.strength + 0.5).coerceAtMost(100.0)
            legend.lastSpreadAt = ctx.now
            listener.knownLegendIds += legend.id
            ctx.addMemory(
                listener,
                MemoryType.INSPIRATION,
                "${legend.subjectName}: ${legend.text}",
                intensity = 20.0
            )
        }
    }

    private fun checkBusinessLongevityLegends(ctx: TickContext) {
        for (biz in ctx.state.businesses.values) {
            if (!biz.open) continue
            val ageYears = (ctx.now - biz.openedAt) / SimTime.MINUTES_PER_YEAR
            if (ageYears < LONGEVITY_YEARS) continue
            // Only spawn once: check no active legend exists for this business
            val alreadyHas = ctx.state.localLegends.values.any {
                it.subject == LegendSubject.BUSINESS && it.subjectId == biz.id && it.decayedAt == null
            }
            if (!alreadyHas) {
                spawnOrReinforce(
                    ctx, LegendSubject.BUSINESS, biz.id, biz.name,
                    "${biz.name} has been here longer than anyone can remember. There must be something right about the place.",
                    null, strength = 40.0
                )
            }
        }
    }

    /** Return the strongest active legend, for the newspaper to quote. */
    fun strongestActive(state: WorldState): LocalLegend? =
        state.localLegends.values
            .filter { it.decayedAt == null && it.strength >= 20.0 }
            .maxByOrNull { it.strength }
}
