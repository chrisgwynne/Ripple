package com.ripple.town.simulation

import com.google.common.truth.Truth.assertThat
import com.ripple.town.core.model.Building
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.WorldState
import com.ripple.town.core.model.isHome
import com.ripple.town.core.simulation.PopulationGenerator
import com.ripple.town.core.simulation.SimRandom
import com.ripple.town.core.simulation.WorldGenerator
import org.junit.Test

/**
 * Covers [PopulationGenerator.buildProceduralPopulation]: real connected households (not
 * isolated individuals), a real age pyramid, determinism, and that every generated resident
 * lands in a real, valid, non-overcrowded home-type building.
 *
 * Uses the same entry point production code uses — `WorldGenerator(seed).generate(...)` already
 * calls `PopulationGenerator.buildProceduralPopulation` in place of the old flat background
 * generator (see WorldGenerator.kt's `generate()`), so these tests exercise the real generated
 * world, not a hand-rolled test-only setup.
 */
class PopulationGeneratorTest {

    private fun freshState(seed: Long = 909_090L): WorldState =
        WorldGenerator(seed, "Testholme").generate(1_700_000_000_000L)

    // ------------------------------------------------------------- connectedness

    @Test
    fun `background residents are genuinely connected into households, not isolated individuals`() {
        val state = freshState()
        val background = state.residents.values.filter { it.detailLevel == DetailLevel.BACKGROUND }
        assertThat(background).isNotEmpty()

        // Every background resident has a household.
        val withoutHousehold = background.filter { it.householdId == null }
        assertThat(withoutHousehold).isEmpty()

        // Every background resident has at least one real family/partner link: a parent, a
        // child, or a partnerId — i.e. they are not a friendless singleton dropped into a home.
        val withNoFamilyLink = background.filter {
            it.motherId == null && it.fatherId == null && it.childIds.isEmpty() && it.partnerId == null
        }
        // Genuine single adults/elderly-alone households are allowed to have no *family* link,
        // but they must still have a real relationship recorded in state.relationships (their
        // household is why they're placed at all) OR — more importantly — the population as a
        // whole must be dominated by connected residents, not singletons.
        val fractionUnlinked = withNoFamilyLink.size.toDouble() / background.size
        assertThat(fractionUnlinked).isLessThan(0.35) // SINGLE_ADULT + ELDERLY_ALONE templates combined are ~30% weight

        // At least some households have >1 member (couples/families actually exist).
        val multiMemberHouseholds = state.households.values.filter { it.memberIds.size > 1 }
        assertThat(multiMemberHouseholds).isNotEmpty()

        // Spot-check: every childId recorded on a background parent resolves to a real resident
        // whose motherId/fatherId points back at the parent.
        for (r in background) {
            for (childId in r.childIds) {
                val child = state.resident(childId)
                assertThat(child).isNotNull()
                assertThat(child!!.motherId == r.id || child.fatherId == r.id).isTrue()
            }
            val partnerId = r.partnerId
            if (partnerId != null) {
                val partner = state.resident(partnerId)
                assertThat(partner).isNotNull()
                assertThat(partner!!.partnerId).isEqualTo(r.id)
            }
        }
    }

    // ------------------------------------------------------------- age pyramid

    @Test
    fun `age distribution roughly matches an intended pyramid shape`() {
        val state = freshState()
        val background = state.residents.values.filter { it.detailLevel == DetailLevel.BACKGROUND }
        assertThat(background.size).isGreaterThan(20) // enough samples for a bucket-count check to mean anything

        val now = state.time
        var children = 0; var workingAge = 0; var elderly = 0
        for (r in background) {
            val age = r.ageAt(now)
            when {
                age <= 17 -> children++
                age <= 64 -> workingAge++
                else -> elderly++
            }
        }
        val total = background.size.toDouble()
        val childFrac = children / total
        val workingFrac = workingAge / total
        val elderlyFrac = elderly / total

        // Generous bands around the brief's target shape (children/teens ~20-25%, working-age
        // ~55-62%, elderly ~15-22%) — a statistical check, not exact equality, since this is a
        // stochastic sample built from household templates rather than a direct per-resident roll.
        assertThat(childFrac).isIn(com.google.common.collect.Range.closed(0.10, 0.40))
        assertThat(workingFrac).isIn(com.google.common.collect.Range.closed(0.40, 0.75))
        assertThat(elderlyFrac).isIn(com.google.common.collect.Range.closed(0.05, 0.35))

        // Not flat/uniform: a real pyramid should not have every bucket within a few percent of
        // each other's share given the 20/59/20-ish target split.
        assertThat(workingFrac).isGreaterThan(childFrac)
        assertThat(workingFrac).isGreaterThan(elderlyFrac)
    }

    // ------------------------------------------------------------- determinism

    @Test
    fun `same seed produces the same generated population`() {
        val stateA = freshState(seed = 55_555L)
        val stateB = freshState(seed = 55_555L)

        fun fingerprint(state: WorldState): List<String> =
            state.residents.values
                .filter { it.detailLevel == DetailLevel.BACKGROUND }
                .sortedBy { it.id }
                .map { "${it.id}|${it.firstName}|${it.surname}|${it.gender}|${it.bornAt}|${it.householdId}|${it.homeBuildingId}|${it.motherId}|${it.fatherId}|${it.partnerId}" }

        assertThat(fingerprint(stateA)).isEqualTo(fingerprint(stateB))

        // Households themselves must match too (names, home assignment, member lists).
        val hhA = stateA.households.values.sortedBy { it.id }
            .map { "${it.id}|${it.name}|${it.homeBuildingId}|${it.memberIds.sorted()}" }
        val hhB = stateB.households.values.sortedBy { it.id }
            .map { "${it.id}|${it.name}|${it.homeBuildingId}|${it.memberIds.sorted()}" }
        assertThat(hhA).isEqualTo(hhB)
    }

    @Test
    fun `different seeds produce different populations`() {
        val stateA = freshState(seed = 1L)
        val stateB = freshState(seed = 2L)
        val namesA = stateA.residents.values.filter { it.detailLevel == DetailLevel.BACKGROUND }.map { it.fullName }
        val namesB = stateB.residents.values.filter { it.detailLevel == DetailLevel.BACKGROUND }.map { it.fullName }
        assertThat(namesA).isNotEqualTo(namesB)
    }

    // ------------------------------------------------------------- valid building assignment

    @Test
    fun `every generated resident is assigned to a real home-type building within capacity`() {
        val state = freshState()

        val homeIds = state.buildings.values.filter { it.type.isHome }.map { it.id }.toSet()
        assertThat(homeIds).isNotEmpty()

        // Every resident with a homeBuildingId points at a real, existing, home-type building.
        for (r in state.residents.values) {
            val homeId = r.homeBuildingId ?: continue
            assertThat(state.buildings.containsKey(homeId)).isTrue()
            assertThat(state.buildings.getValue(homeId).type.isHome).isTrue()
        }

        // Every household's homeBuildingId is a real home-type building too.
        for (hh in state.households.values) {
            val homeId = hh.homeBuildingId ?: continue
            assertThat(state.buildings.containsKey(homeId)).isTrue()
            assertThat(state.buildings.getValue(homeId).type.isHome).isTrue()
        }

        // No home building is asked to hold more residents than its capacity.
        val occupancy = HashMap<Long, Int>()
        for (r in state.residents.values) {
            val homeId = r.homeBuildingId ?: continue
            if (homeId in homeIds) occupancy[homeId] = (occupancy[homeId] ?: 0) + 1
        }
        for (home in state.buildings.values.filter { it.type.isHome }) {
            val used = occupancy[home.id] ?: 0
            assertThat(used).isAtMost(home.capacity)
        }
    }

    // ------------------------------------------------------------- ceiling behaviour

    @Test
    fun `targetCount is a ceiling, not a promise -- generation stops once home capacity runs out`() {
        val state = freshState()
        val before = state.residents.size

        // Ask for a huge target directly against a state that already has no spare capacity
        // left (freshState() already ran generation once) — must not throw, overcrowd, or loop
        // forever; it should simply add nothing more.
        val rng = SimRandom(state.seed, tick = -2L)
        PopulationGenerator.buildProceduralPopulation(state, rng, targetCount = 10_000)

        val after = state.residents.size
        assertThat(after).isEqualTo(before) // no spare capacity left, so nothing new fits

        // Building occupancy still respects capacity after the no-op extra call.
        for (home in state.buildings.values.filter { it.type.isHome }) {
            val used = state.residents.values.count { it.homeBuildingId == home.id }
            assertThat(used).isAtMost(home.capacity)
        }
    }
}
