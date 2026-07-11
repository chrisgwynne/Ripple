package com.ripple.town.core.simulation

import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.Gender
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.Needs
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Relationship
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SpriteConfig
import com.ripple.town.core.model.WorldState

/**
 * Procedural background population: real, connected households (parents, partners, kids —
 * not isolated individuals) assigned into whatever spare capacity the hand-authored 12 Rowan
 * Street homes have left over `WorldGenerator.buildDetailedResidents`' ~30 named residents.
 *
 * Deliberately a *sibling* file to `WorldGenerator.kt`, not an edit to it: `buildDetailedResidents`,
 * `slots()` and `buildBuildings()` are load-bearing for concurrent visual/UI work this session and
 * must not be touched (see this session's brief). `WorldGenerator.generate()` calls
 * [PopulationGenerator.buildProceduralPopulation] in place of the old flat
 * `buildBackgroundResidents` — same DetailLevel.BACKGROUND cost profile (see
 * `NeedsSystem.update`'s `DetailLevel.BACKGROUND` branch and the various `DetailLevel.DETAILED`
 * gates across `DecisionSystem`/`EconomySystem`/`HealthSystem`/`EmotionSystem`/`LifecycleSystem` —
 * background residents are cheap on every one of those), but with real family/household
 * structure so the town doesn't read as a crowd of strangers.
 *
 * Population ceiling is real, not aspirational: `Building.capacity` per home (4-8 depending on
 * type) summed across all residential lots in the 320×200 map gives several thousand slots —
 * far more than needed for 1,000 residents. [targetCount] is therefore achievable in practice;
 * the generator stops at capacity only if an unusually small world is loaded.
 */
object PopulationGenerator {

    // ---------------------------------------------------------- age pyramid

    /** Inclusive age-bucket bounds and the (unnormalised) weight of an individual landing in it. */
    private data class AgeBucket(val minAge: Int, val maxAge: Int, val weight: Double)

    /**
     * Roughly: children/teens ~20-25%, working-age adults ~55-62%, elderly ~15-22% — see the
     * task brief and `docs/simulation-rules.md`. Five buckets rather than three so the shape
     * inside "adult" isn't flat either (a real population pyramid tapers gradually, not in three
     * hard steps).
     */
    private val AGE_BUCKETS = listOf(
        AgeBucket(0, 12, 12.0),     // children
        AgeBucket(13, 17, 9.0),     // teens                => children+teens = 21%
        AgeBucket(18, 34, 30.0),    // young adults
        AgeBucket(35, 64, 29.0),    // established adults   => working-age = 59%
        AgeBucket(65, 90, 20.0)     // elderly               => elderly = 20%
    )
    private val AGE_WEIGHT_TOTAL = AGE_BUCKETS.sumOf { it.weight }

    private fun sampleAge(rng: SimRandom, minAge: Int = 0, maxAge: Int = 90): Int {
        val buckets = AGE_BUCKETS.filter { it.maxAge >= minAge && it.minAge <= maxAge }
        val total = buckets.sumOf { it.weight }
        var roll = rng.nextDouble(0.0, total)
        for (b in buckets) {
            if (roll < b.weight) {
                val lo = maxOf(b.minAge, minAge)
                val hi = minOf(b.maxAge, maxAge)
                return if (hi > lo) rng.nextInt(lo, hi + 1) else lo
            }
            roll -= b.weight
        }
        return buckets.last().maxAge
    }

    // ------------------------------------------------------ household templates

    enum class HouseholdTemplate(val weight: Double) {
        COUPLE_NO_KIDS(18.0),
        COUPLE_WITH_KIDS(26.0),
        SINGLE_PARENT(10.0),
        SINGLE_ADULT(20.0),
        ELDERLY_COUPLE(12.0),
        ELDERLY_ALONE(10.0),
        MULTIGENERATIONAL(4.0)
    }

    private val TEMPLATE_WEIGHT_TOTAL = HouseholdTemplate.entries.sumOf { it.weight }

    private fun sampleTemplate(rng: SimRandom): HouseholdTemplate {
        var roll = rng.nextDouble(0.0, TEMPLATE_WEIGHT_TOTAL)
        for (t in HouseholdTemplate.entries) {
            if (roll < t.weight) return t
            roll -= t.weight
        }
        return HouseholdTemplate.SINGLE_ADULT
    }

    /** How many residents a template needs, and roughly how big a home it wants. */
    private fun templateSize(rng: SimRandom, template: HouseholdTemplate): Int = when (template) {
        HouseholdTemplate.COUPLE_NO_KIDS -> 2
        HouseholdTemplate.COUPLE_WITH_KIDS -> 3 + rng.nextInt(0, 2) // 2 adults + 1-2 kids
        HouseholdTemplate.SINGLE_PARENT -> 2 + rng.nextInt(0, 2)    // 1 adult + 1-2 kids
        HouseholdTemplate.SINGLE_ADULT -> 1
        HouseholdTemplate.ELDERLY_COUPLE -> 2
        HouseholdTemplate.ELDERLY_ALONE -> 1
        HouseholdTemplate.MULTIGENERATIONAL -> 5 + rng.nextInt(0, 2) // grandparent(s)+parents+kid(s)
    }

    // --------------------------------------------------------------- entry point

    /**
     * Builds procedurally-generated, genuinely connected households and assigns them into
     * whatever real spare capacity is left in `state.homes()` after the hand-authored
     * households have claimed theirs. Stops as soon as capacity runs out — [targetCount] is a
     * ceiling, not a promise. All randomness flows through [rng] (deterministic, checkpoint-safe).
     */
    fun buildProceduralPopulation(state: WorldState, rng: SimRandom, targetCount: Int = 500) {
        val occupancy = HashMap<Long, Int>()
        for (home in state.homes()) occupancy[home.id] = 0
        // Count residents (detailed + any already-generated background) already living in each home.
        for (r in state.residents.values) {
            val homeId = r.homeBuildingId ?: continue
            if (homeId in occupancy) occupancy[homeId] = occupancy.getValue(homeId) + 1
        }

        val homesByFreeSpace = state.homes()
            .filter { (occupancy[it.id] ?: 0) < it.capacity }
            .sortedBy { it.id } // stable, deterministic iteration order

        var generated = 0
        var homeIdx = 0

        fun nextHomeWithSpace(needed: Int): Long? {
            // Scan forward from where we left off (round-robin-ish but monotonic — deterministic,
            // and naturally spreads households across all 12 homes rather than filling one first).
            var scanned = 0
            while (scanned < homesByFreeSpace.size) {
                val home = homesByFreeSpace[homeIdx % homesByFreeSpace.size]
                val used = occupancy.getValue(home.id)
                val free = home.capacity - used
                if (free >= needed) {
                    return home.id
                }
                homeIdx++
                scanned++
            }
            // No single home has enough free space for this household size as a whole; caller
            // will shrink the template or give up.
            return null
        }

        val allHomesFull: () -> Boolean = {
            homesByFreeSpace.all { (occupancy[it.id] ?: 0) >= it.capacity }
        }

        while (generated < targetCount && !allHomesFull()) {
            val template = sampleTemplate(rng)
            var size = templateSize(rng, template)

            var homeId = nextHomeWithSpace(size)
            if (homeId == null) {
                // Shrink to whatever the biggest remaining pocket of space is rather than
                // skipping the tick entirely — keeps generation honest about real capacity
                // instead of wasting a household's worth of remaining budget.
                val best = homesByFreeSpace.maxByOrNull { it.capacity - occupancy.getValue(it.id) }
                val bestFree = best?.let { it.capacity - occupancy.getValue(it.id) } ?: 0
                if (best == null || bestFree <= 0) break
                size = minOf(size, bestFree)
                homeId = best.id
            }
            if (size <= 0) break

            val household = buildHousehold(state, rng, template, size, homeId)
            occupancy[homeId] = occupancy.getValue(homeId) + household.memberIds.size
            generated += household.memberIds.size
            homeIdx++
        }
    }

    // ------------------------------------------------------------ household build

    private fun buildHousehold(
        state: WorldState,
        rng: SimRandom,
        template: HouseholdTemplate,
        size: Int,
        homeId: Long
    ): Household {
        val hhId = state.nextHouseholdId++
        val surname = rng.pick(NameData.SURNAMES)
        val household = Household(
            id = hhId,
            name = "The $surname household",
            homeBuildingId = homeId,
            savings = rng.nextDouble(50.0, 3_000.0),
            monthlyRent = rng.nextDouble(180.0, 380.0)
        )
        state.households[hhId] = household

        val members = when (template) {
            HouseholdTemplate.COUPLE_NO_KIDS -> buildCouple(state, rng, surname, hhId, homeId, minAge = 20, maxAge = 64)
            HouseholdTemplate.ELDERLY_COUPLE -> buildCouple(state, rng, surname, hhId, homeId, minAge = 65, maxAge = 88)
            HouseholdTemplate.SINGLE_ADULT -> listOf(
                buildAdult(state, rng, surname, hhId, homeId, minAge = 18, maxAge = 64)
            )
            HouseholdTemplate.ELDERLY_ALONE -> listOf(
                buildAdult(state, rng, surname, hhId, homeId, minAge = 65, maxAge = 90)
            )
            HouseholdTemplate.COUPLE_WITH_KIDS -> {
                val parents = buildCouple(state, rng, surname, hhId, homeId, minAge = 24, maxAge = 55)
                val kidCount = (size - 2).coerceAtLeast(1)
                val kids = buildChildren(state, rng, surname, hhId, homeId, parents[0], parents[1], kidCount)
                parents + kids
            }
            HouseholdTemplate.SINGLE_PARENT -> {
                val parent = buildAdult(state, rng, surname, hhId, homeId, minAge = 22, maxAge = 55)
                val kidCount = (size - 1).coerceAtLeast(1)
                val kids = buildChildren(state, rng, surname, hhId, homeId, parent, null, kidCount)
                listOf(parent) + kids
            }
            HouseholdTemplate.MULTIGENERATIONAL -> {
                val grandparents = buildCouple(state, rng, surname, hhId, homeId, minAge = 65, maxAge = 85)
                val parents = buildCouple(state, rng, surname, hhId, homeId, minAge = 28, maxAge = 50)
                // Link one parent as a child of the grandparents (real cross-generation family link).
                linkParentChild(state, grandparents[0], parents[0])
                linkParentChild(state, grandparents[1], parents[0])
                val kidCount = (size - 4).coerceAtLeast(1)
                val kids = buildChildren(state, rng, surname, hhId, homeId, parents[0], parents[1], kidCount)
                grandparents + parents + kids
            }
        }

        // Cross-link siblings within the same household so "connected household" checks see
        // more than just parent-child edges.
        val kidsOfHousehold = members.filter { it.motherId != null || it.fatherId != null }
        for (i in kidsOfHousehold.indices) {
            for (j in i + 1 until kidsOfHousehold.size) {
                linkSiblings(state, kidsOfHousehold[i], kidsOfHousehold[j])
            }
        }

        return household
    }

    private fun buildCouple(
        state: WorldState, rng: SimRandom, surname: String, hhId: Long, homeId: Long, minAge: Int, maxAge: Int
    ): List<Resident> {
        val baseAge = sampleAge(rng, minAge, maxAge)
        val ageGap = rng.nextInt(-4, 5)
        val ageA = baseAge.coerceIn(minAge, maxAge)
        val ageB = (baseAge + ageGap).coerceIn(minAge, maxAge)

        val genderA = randomAdultGender(rng)
        val genderB = randomAdultGender(rng)
        val sameSurname = rng.nextBoolean(0.6)

        val a = buildResident(state, rng, surname, hhId, homeId, ageA, genderA)
        val b = buildResident(
            state, rng, if (sameSurname) surname else rng.pick(NameData.SURNAMES), hhId, homeId, ageB, genderB
        )

        a.relationshipStatus = RelationshipStatus.MARRIED; a.partnerId = b.id
        b.relationshipStatus = RelationshipStatus.MARRIED; b.partnerId = a.id
        val rel = Relationship.create(a.id, b.id, RelationshipKind.SPOUSE)
        rel.familiarity = rng.nextDouble(60.0, 92.0)
        rel.trust = rng.nextDouble(50.0, 85.0)
        rel.affection = rng.nextDouble(45.0, 80.0)
        rel.respect = rng.nextDouble(45.0, 75.0)
        rel.sharedHistory = rng.nextDouble(30.0, 85.0)
        rel.clampAll()
        state.relationships[Relationship.keyOf(a.id, b.id)] = rel

        return listOf(a, b)
    }

    private fun buildAdult(
        state: WorldState, rng: SimRandom, surname: String, hhId: Long, homeId: Long, minAge: Int, maxAge: Int
    ): Resident {
        val age = sampleAge(rng, minAge, maxAge)
        return buildResident(state, rng, surname, hhId, homeId, age, randomAdultGender(rng))
    }

    private fun buildChildren(
        state: WorldState, rng: SimRandom, surname: String, hhId: Long, homeId: Long,
        parentA: Resident, parentB: Resident?, count: Int
    ): List<Resident> {
        val parentAge = parentA.let { SimTime.ageYears(it.bornAt, state.time) }
        val maxKidAge = (parentAge - 16).coerceIn(0, 25)
        val kids = (0 until count).map {
            val age = sampleAge(rng, 0, maxKidAge)
            val kid = buildResident(state, rng, surname, hhId, homeId, age, randomChildGender(rng))
            linkParentChild(state, parentA, kid)
            if (parentB != null) linkParentChild(state, parentB, kid)
            kid
        }
        return kids
    }

    private fun linkParentChild(state: WorldState, parent: Resident, child: Resident) {
        if (parent.gender == Gender.MALE) child.fatherId = parent.id else child.motherId = parent.id
        if (child.id !in parent.childIds) parent.childIds += child.id
        val rel = Relationship.create(parent.id, child.id, RelationshipKind.FAMILY)
        rel.familiarity = 85.0; rel.trust = 72.0; rel.affection = 68.0; rel.respect = 58.0; rel.sharedHistory = 70.0
        rel.clampAll()
        state.relationships[Relationship.keyOf(parent.id, child.id)] = rel
    }

    private fun linkSiblings(state: WorldState, a: Resident, b: Resident) {
        if (a.id == b.id) return
        if (state.relationship(a.id, b.id) != null) return
        val rel = Relationship.create(a.id, b.id, RelationshipKind.FAMILY)
        rel.familiarity = 70.0; rel.trust = 55.0; rel.affection = 50.0; rel.respect = 45.0; rel.sharedHistory = 55.0
        rel.clampAll()
        state.relationships[Relationship.keyOf(a.id, b.id)] = rel
    }

    private fun randomAdultGender(rng: SimRandom): Gender = when (rng.nextInt(20)) {
        in 0..9 -> Gender.FEMALE
        in 10..18 -> Gender.MALE
        else -> Gender.NONBINARY
    }

    private fun randomChildGender(rng: SimRandom): Gender = when (rng.nextInt(20)) {
        in 0..9 -> Gender.FEMALE
        in 10..18 -> Gender.MALE
        else -> Gender.NONBINARY
    }

    private val OCCUPATIONS = listOf(
        "Farm worker", "Delivery driver", "Seamstress", "Gardener", "Cleaner", "Fisher",
        "Postal worker", "Labourer", "Home help", "Clerk", "Carter", "Retired", "Between jobs"
    )

    private fun occupationFor(rng: SimRandom, age: Int): String = when {
        age < 5 -> "Infant"
        age < 13 -> "Pupil"
        age < 18 -> "Student"
        age >= 66 -> if (rng.nextBoolean(0.75)) "Retired" else rng.pick(OCCUPATIONS)
        else -> rng.pick(OCCUPATIONS)
    }

    private fun buildResident(
        state: WorldState, rng: SimRandom, surname: String, hhId: Long, homeId: Long, age: Int, gender: Gender
    ): Resident {
        val id = state.nextResidentId++
        val bornAt = state.time - age.toLong() * SimTime.MINUTES_PER_YEAR - rng.nextLong(0, SimTime.MINUTES_PER_YEAR)
        val first = when (gender) {
            Gender.FEMALE -> rng.pick(NameData.FEMALE_FIRST)
            Gender.MALE -> rng.pick(NameData.MALE_FIRST)
            Gender.NONBINARY -> rng.pick(NameData.NEUTRAL_FIRST)
        }
        val r = Resident(
            id = id,
            firstName = first,
            surname = surname,
            gender = gender,
            bornAt = bornAt,
            homeBuildingId = homeId,
            householdId = hhId,
            detailLevel = DetailLevel.BACKGROUND,
            sprite = SpriteConfig(
                skinTone = rng.nextInt(4), hairStyle = rng.nextInt(4), hairColor = rng.nextInt(5),
                shirtColor = rng.nextInt(8), trouserColor = rng.nextInt(5)
            ),
            occupation = occupationFor(rng, age),
            wealth = if (age < 18) rng.nextDouble(0.0, 60.0) else rng.nextDouble(50.0, 2_500.0),
            needs = Needs(
                hunger = rng.nextDouble(50.0, 90.0), energy = rng.nextDouble(50.0, 90.0),
                health = rng.nextDouble(60.0, 95.0), stress = rng.nextDouble(10.0, 50.0)
            ),
            // Varied per resident, not identical: independently sampled rather than a shared
            // template instance — each call draws its own rng values.
            personality = Personality(
                kindness = rng.nextDouble(0.15, 0.85), ambition = rng.nextDouble(0.15, 0.85),
                curiosity = rng.nextDouble(0.15, 0.85), sociability = rng.nextDouble(0.15, 0.85),
                patience = rng.nextDouble(0.15, 0.85), honesty = rng.nextDouble(0.25, 0.9),
                courage = rng.nextDouble(0.15, 0.85), discipline = rng.nextDouble(0.15, 0.85),
                empathy = rng.nextDouble(0.15, 0.85), impulsiveness = rng.nextDouble(0.15, 0.85)
            ),
            currentBuildingId = homeId
        )
        state.residents[id] = r
        state.households[hhId]?.memberIds?.add(id)
        return r
    }
}
