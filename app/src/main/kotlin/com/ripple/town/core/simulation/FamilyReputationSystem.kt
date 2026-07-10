package com.ripple.town.core.simulation

import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldState

/**
 * Family reputation: a lineage-level standing distinct from an individual's own
 * [Resident.reputation], built from a family's collective deeds across generations
 * rather than any one person's. Deliberately **not** a new persisted running total —
 * `Resident.reputation` already exists and already reacts to the individual
 * consequences that should feed a family's name (petitions won/lost, crime,
 * business success/failure, affairs discovered all already move it up or down via
 * `ConsequenceEngine`/`PetitionSystem`/`CrimeSystem` etc.). Rather than duplicate
 * that bookkeeping in a second running total that could drift out of sync, family
 * reputation is a lightweight aggregate **computed at read time** from the
 * relevant residents' existing `reputation` values — always consistent with the
 * individual number it's built from, no new field to migrate or decay by hand.
 *
 * Lineage is read two ways, matching how the rest of the simulation already tracks
 * family — a household (which already persists and merges across a marriage, see
 * `ConsequenceEngine`'s "households merge" rule under `EventType.MARRIAGE`) plus
 * direct ancestors (`motherId`/`fatherId`), so a name carries weight even for
 * someone who has moved into a different household.
 */
object FamilyReputationSystem {

    /** How many generations of direct ancestors are considered. */
    private const val MAX_ANCESTOR_GENERATIONS = 2

    /** A dead ancestor's reputation still counts, but fades with generational distance. */
    private const val ANCESTOR_DECAY_PER_GENERATION = 0.4

    /** Living household members (siblings, partner) count almost as much as the resident themself. */
    private const val HOUSEHOLD_WEIGHT = 0.7

    /** The resident's own reputation always anchors the family figure most strongly. */
    private const val SELF_WEIGHT = 1.0

    /**
     * The family's reputation as it bears on [resident] right now: a weighted mean of
     * their own `reputation`, their living household's, and up to two generations of
     * ancestors' (decayed), falling back to the town-wide default (50.0, matching
     * `Resident.reputation`'s own default) when a resident has no traceable family at
     * all — a founding/arrived resident with no ancestors in town.
     */
    fun familyReputationOf(state: WorldState, resident: Resident): Double {
        val weighted = mutableListOf<Pair<Double, Double>>() // value to weight
        weighted += resident.reputation to SELF_WEIGHT

        resident.householdId?.let { hid ->
            state.household(hid)?.memberIds
                ?.mapNotNull { state.resident(it) }
                ?.filter { it.id != resident.id && it.alive }
                ?.forEach { weighted += it.reputation to HOUSEHOLD_WEIGHT }
        }

        var generation = listOfNotNull(resident.motherId, resident.fatherId).mapNotNull { state.resident(it) }
        var decay = 1.0
        repeat(MAX_ANCESTOR_GENERATIONS) {
            if (generation.isEmpty()) return@repeat
            decay *= ANCESTOR_DECAY_PER_GENERATION
            for (ancestor in generation) weighted += ancestor.reputation to decay
            generation = generation.flatMap { listOfNotNull(it.motherId, it.fatherId) }.mapNotNull { state.resident(it) }
        }

        val totalWeight = weighted.sumOf { it.second }
        if (totalWeight <= 0.0) return 50.0
        return weighted.sumOf { it.first * it.second } / totalWeight
    }

    /**
     * A small, bounded modifier derived from family reputation, centred on 0 at the
     * 50.0 town-wide default reputation and clamped so it can never swing a result by
     * more than [maxSwing] in either direction. Composable into any existing
     * probability/roll without risk of a single well- or poorly-regarded family
     * dominating the outcome.
     */
    fun standingModifier(state: WorldState, resident: Resident, maxSwing: Double): Double {
        val rep = familyReputationOf(state, resident)
        return (((rep - 50.0) / 50.0) * maxSwing).coerceIn(-maxSwing, maxSwing)
    }
}
