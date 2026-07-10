package com.ripple.town.core.simulation

import com.ripple.town.core.model.Building
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.Business
import com.ripple.town.core.model.BusinessType
import com.ripple.town.core.model.DelayedEffect
import com.ripple.town.core.model.DelayedEffectType
import com.ripple.town.core.model.DetailLevel
import com.ripple.town.core.model.EffectCondition
import com.ripple.town.core.model.Employment
import com.ripple.town.core.model.Gender
import com.ripple.town.core.model.Goal
import com.ripple.town.core.model.GoalType
import com.ripple.town.core.model.HealthCondition
import com.ripple.town.core.model.HealthConditionType
import com.ripple.town.core.model.Household
import com.ripple.town.core.model.Needs
import com.ripple.town.core.model.Personality
import com.ripple.town.core.model.Relationship
import com.ripple.town.core.model.RelationshipKind
import com.ripple.town.core.model.RelationshipStatus
import com.ripple.town.core.model.Resident
import com.ripple.town.core.model.SimTime
import com.ripple.town.core.model.SkillType
import com.ripple.town.core.model.SpriteConfig
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.WorldState

/**
 * Builds the initial town deterministically from a seed. The street plan is
 * hand-authored (stable and readable); the people, their looks, their exact
 * stats and the background population vary with the seed.
 */
class WorldGenerator(private val seed: Long, private val townName: String = "Ashcombe") {

    fun generate(createdAtRealMs: Long): WorldState {
        val rng = SimRandom(seed, tick = -1L)
        val state = WorldState(
            seed = seed,
            townName = townName,
            createdAtRealMs = createdAtRealMs,
            map = buildMap(rng)
        )
        // Start mid-morning on a spring day in year 12 of the town's own history.
        state.time = 11L * SimTime.MINUTES_PER_YEAR + 62L * SimTime.MINUTES_PER_DAY + 9L * 60L
        state.nextElectionAt = state.time + 180L * SimTime.MINUTES_PER_DAY

        buildBuildings(state)
        buildDetailedResidents(state, rng)
        buildBusinessesAndJobs(state)
        buildBackgroundResidents(state, rng)
        buildRelationships(state, rng)
        seedScenarios(state)

        state.followedResidentId = state.residents.values
            .first { it.firstName == "Mara" && it.surname == "Vale" }.id
        state.discoveredResidentIds += state.followedResidentId!!
        return state
    }

    // ------------------------------------------------------------------ map

    private fun buildMap(rng: SimRandom): TownMap {
        val w = MAP_W
        val h = MAP_H
        val tiles = MutableList(w * h) { TileType.GRASS }
        fun set(x: Int, y: Int, t: TileType) {
            if (x in 0 until w && y in 0 until h) tiles[y * w + x] = t
        }
        // River down the east edge
        for (y in 0 until h) {
            set(w - 1, y, TileType.WATER); set(w - 2, y, TileType.WATER)
        }
        // Roads: two horizontal, three vertical
        for (x in 0 until w - 2) { set(x, HIGH_ST_Y, TileType.ROAD); set(x, ROWAN_ST_Y, TileType.ROAD) }
        for (y in 0 until h) { set(10, y, TileType.ROAD); set(22, y, TileType.ROAD); set(34, y, TileType.ROAD) }
        // Plaza in front of the town hall
        for (x in 13..19) for (y in 11..12) if (tiles[y * w + x] == TileType.GRASS) set(x, y, TileType.PLAZA)
        // Park greenery
        for (x in 12..19) for (y in 23..29) {
            val t = when {
                rng.nextBoolean(0.18) -> TileType.TREE
                rng.nextBoolean(0.25) -> TileType.FLOWERS
                else -> TileType.GRASS
            }
            set(x, y, t)
        }
        for (x in 14..17) set(x, 26, TileType.PATH)
        // Scattered trees elsewhere
        repeat(46) {
            val x = rng.nextInt(w - 2)
            val y = rng.nextInt(h)
            if (tiles[y * w + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }
        return TownMap(w, h, tiles)
    }

    // ------------------------------------------------------------- buildings

    private data class Slot(
        val name: String, val type: BuildingType, val x: Int, val y: Int,
        val w: Int, val h: Int, val doorX: Int, val doorY: Int,
        val noise: Double = 10.0, val capacity: Int = 8, val value: Double = 40_000.0
    )

    private fun slots(): List<Slot> = listOf(
        // High Street, north side
        Slot("Bell's Bakery", BuildingType.BAKERY, 4, 6, 4, 3, 6, 10, noise = 18.0),
        Slot("Garrow's Grocery", BuildingType.GROCER, 11, 6, 3, 3, 12, 10, noise = 14.0),
        Slot("The Willow Café", BuildingType.CAFE, 14, 6, 3, 3, 15, 10, noise = 20.0, capacity = 10),
        Slot("Quince & Daughter Books", BuildingType.BOOKSHOP, 18, 6, 3, 3, 19, 10, noise = 6.0),
        Slot("Ludlow Hardware", BuildingType.HARDWARE, 24, 6, 4, 3, 26, 10, noise = 16.0),
        Slot("Fenwick Tailoring", BuildingType.TAILOR, 29, 6, 3, 3, 30, 10, noise = 8.0),
        // High Street, south side
        Slot("The Old Lantern", BuildingType.PUB, 4, 11, 4, 3, 6, 10, noise = 42.0, capacity = 14),
        Slot("Ashcombe Town Hall", BuildingType.TOWN_HALL, 14, 13, 5, 4, 16, 12, noise = 10.0, value = 120_000.0),
        Slot("Ashcombe Clinic", BuildingType.CLINIC, 24, 11, 4, 3, 26, 10, noise = 10.0, capacity = 10),
        Slot("The Old Granary", BuildingType.VACANT, 30, 11, 4, 3, 32, 10, noise = 0.0, value = 18_000.0),
        // School west of centre
        Slot("Ashcombe School", BuildingType.SCHOOL, 2, 12, 5, 4, 4, 16, noise = 24.0, capacity = 30, value = 90_000.0),
        // Factory close to the north-side Rowan Street homes — deliberately noisy
        Slot("Ashcombe Joinery Works", BuildingType.FACTORY, 36, 16, 5, 4, 35, 18, noise = 62.0, capacity = 12, value = 70_000.0),
        // Park & cemetery
        Slot("Ashcombe Park", BuildingType.PARK, 12, 23, 8, 7, 15, 22, noise = 8.0, capacity = 40, value = 30_000.0),
        Slot("St Meadow's Rest", BuildingType.CEMETERY, 36, 29, 5, 4, 35, 30, noise = 0.0, capacity = 40, value = 10_000.0),
        // Homes: Rowan Street north side (numbers 1..6)
        Slot("1 Rowan Street", BuildingType.TERRACE, 12, 19, 3, 3, 13, 22),
        Slot("2 Rowan Street", BuildingType.TERRACE, 16, 19, 3, 3, 17, 22),
        Slot("3 Rowan Street", BuildingType.HOUSE, 24, 19, 3, 3, 25, 22),
        Slot("4 Rowan Street", BuildingType.HOUSE, 28, 19, 3, 3, 29, 22),
        Slot("5 Rowan Street", BuildingType.TERRACE, 31, 19, 3, 3, 32, 22, noise = 12.0),
        Slot("6 Rowan Street", BuildingType.COTTAGE, 36, 23, 3, 3, 35, 23),
        // Rowan Street south side (7..12)
        Slot("7 Rowan Street", BuildingType.COTTAGE, 2, 23, 3, 3, 3, 22),
        Slot("8 Rowan Street", BuildingType.HOUSE, 6, 23, 3, 3, 7, 22),
        Slot("9 Rowan Street", BuildingType.HOUSE, 24, 23, 3, 3, 25, 22),
        Slot("10 Rowan Street", BuildingType.TERRACE, 28, 23, 3, 3, 29, 22),
        Slot("11 Rowan Street", BuildingType.TERRACE, 31, 23, 3, 3, 32, 22),
        Slot("12 Rowan Street", BuildingType.COTTAGE, 2, 27, 3, 3, 3, 26)
    )

    private fun buildBuildings(state: WorldState) {
        for (slot in slots()) {
            val id = state.nextBuildingId++
            state.buildings[id] = Building(
                id = id,
                name = slot.name,
                type = slot.type,
                origin = Tile(slot.x, slot.y),
                width = slot.w,
                height = slot.h,
                door = Tile(slot.doorX, slot.doorY),
                condition = if (slot.type == BuildingType.VACANT) 45.0 else 75.0,
                noise = slot.noise,
                value = slot.value,
                capacity = slot.capacity,
                constructedAt = 0L,
                abandoned = slot.type == BuildingType.VACANT
            )
        }
    }

    private fun buildingByName(state: WorldState, name: String): Building =
        state.buildings.values.first { it.name == name }

    // ------------------------------------------------------------- residents

    private class Spec(
        val first: String, val sur: String, val gender: Gender, val age: Int,
        val occupation: String,
        val kindness: Double = 0.5, val ambition: Double = 0.5, val curiosity: Double = 0.5,
        val sociability: Double = 0.5, val patience: Double = 0.5, val honesty: Double = 0.6,
        val courage: Double = 0.5, val discipline: Double = 0.5, val empathy: Double = 0.55,
        val impulsiveness: Double = 0.4,
        val skills: Map<SkillType, Double> = emptyMap(),
        val wealth: Double = 800.0, val debt: Double = 0.0,
        val politicalInterest: Double = 0.15
    )

    private fun makeResident(state: WorldState, rng: SimRandom, spec: Spec, householdId: Long, homeId: Long): Resident {
        val id = state.nextResidentId++
        val bornAt = state.time - spec.age.toLong() * SimTime.MINUTES_PER_YEAR -
            rng.nextLong(0, SimTime.MINUTES_PER_YEAR)
        val r = Resident(
            id = id,
            firstName = spec.first,
            surname = spec.sur,
            gender = spec.gender,
            bornAt = bornAt,
            homeBuildingId = homeId,
            householdId = householdId,
            detailLevel = DetailLevel.DETAILED,
            sprite = SpriteConfig(
                skinTone = rng.nextInt(4),
                hairStyle = rng.nextInt(4),
                hairColor = rng.nextInt(5),
                shirtColor = rng.nextInt(8),
                trouserColor = rng.nextInt(5)
            ),
            occupation = spec.occupation,
            wealth = spec.wealth,
            debt = spec.debt,
            politicalInterest = spec.politicalInterest,
            personality = Personality(
                kindness = jitter(rng, spec.kindness), ambition = jitter(rng, spec.ambition),
                curiosity = jitter(rng, spec.curiosity), sociability = jitter(rng, spec.sociability),
                patience = jitter(rng, spec.patience), honesty = jitter(rng, spec.honesty),
                courage = jitter(rng, spec.courage), discipline = jitter(rng, spec.discipline),
                empathy = jitter(rng, spec.empathy), impulsiveness = jitter(rng, spec.impulsiveness)
            ),
            needs = Needs(
                hunger = rng.nextDouble(55.0, 85.0),
                energy = rng.nextDouble(55.0, 85.0),
                health = rng.nextDouble(75.0, 95.0),
                social = rng.nextDouble(45.0, 75.0),
                comfort = rng.nextDouble(50.0, 75.0),
                purpose = rng.nextDouble(40.0, 70.0),
                stress = rng.nextDouble(15.0, 40.0),
                financialSecurity = (100.0 - spec.debt / 30.0).coerceIn(20.0, 80.0)
            ),
            currentBuildingId = homeId
        )
        for ((k, v) in spec.skills) r.skills[k] = v
        state.residents[id] = r
        state.households[householdId]?.memberIds?.add(id)
        return r
    }

    private fun jitter(rng: SimRandom, v: Double): Double =
        (v + rng.nextDouble(-0.08, 0.08)).coerceIn(0.02, 0.98)

    private fun buildDetailedResidents(state: WorldState, rng: SimRandom) {
        fun household(name: String, homeName: String, rent: Double = 280.0, savings: Double = 400.0): Household {
            val id = state.nextHouseholdId++
            val home = buildingByName(state, homeName)
            val hh = Household(id = id, name = name, homeBuildingId = home.id, savings = savings, monthlyRent = rent)
            state.households[id] = hh
            return hh
        }

        // HH1 — the Vales, 8 Rowan Street. Mara is the initial followed resident.
        val hhVale = household("The Vale household", "8 Rowan Street", savings = 900.0)
        makeResident(state, rng, Spec(
            "Mara", "Vale", Gender.FEMALE, 24, "Bakery assistant",
            kindness = 0.72, ambition = 0.6, curiosity = 0.65, sociability = 0.6, empathy = 0.7,
            skills = mapOf(SkillType.COOKING to 55.0, SkillType.SOCIAL to 45.0, SkillType.CREATIVITY to 40.0),
            wealth = 420.0
        ), hhVale.id, hhVale.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Wilf", "Vale", Gender.MALE, 58, "Shop assistant",
            patience = 0.7, discipline = 0.65, sociability = 0.4,
            skills = mapOf(SkillType.CARPENTRY to 70.0, SkillType.REPAIR to 65.0), wealth = 1_500.0
        ), hhVale.id, hhVale.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Rosa", "Vale", Gender.FEMALE, 55, "Teacher",
            kindness = 0.68, ambition = 0.55, patience = 0.75, honesty = 0.8, empathy = 0.72,
            skills = mapOf(SkillType.TEACHING to 78.0, SkillType.POLITICS to 35.0, SkillType.SOCIAL to 60.0),
            wealth = 2_100.0, politicalInterest = 0.55
        ), hhVale.id, hhVale.homeBuildingId!!)

        // HH2 — the Bells. Tom owns the struggling bakery.
        val hhBell = household("The Bell household", "3 Rowan Street", savings = 250.0)
        makeResident(state, rng, Spec(
            "Tom", "Bell", Gender.MALE, 52, "Baker & owner",
            discipline = 0.7, patience = 0.55, ambition = 0.5, sociability = 0.45,
            skills = mapOf(SkillType.COOKING to 82.0, SkillType.BUSINESS to 38.0),
            wealth = 600.0, debt = 900.0
        ), hhBell.id, hhBell.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Edie", "Bell", Gender.FEMALE, 50, "Grocery assistant",
            kindness = 0.66, patience = 0.6, sociability = 0.6,
            skills = mapOf(SkillType.SOCIAL to 55.0, SkillType.BUSINESS to 30.0), wealth = 700.0
        ), hhBell.id, hhBell.homeBuildingId!!)

        // HH3 — the Marshes: young family under financial strain.
        val hhMarsh = household("The Marsh household", "10 Rowan Street", rent = 320.0, savings = 60.0)
        makeResident(state, rng, Spec(
            "Jonas", "Marsh", Gender.MALE, 34, "Joinery worker",
            patience = 0.4, discipline = 0.55, impulsiveness = 0.55, courage = 0.55,
            skills = mapOf(SkillType.CARPENTRY to 58.0, SkillType.REPAIR to 44.0),
            wealth = 180.0, debt = 1_400.0
        ), hhMarsh.id, hhMarsh.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Petra", "Marsh", Gender.FEMALE, 33, "Café worker",
            kindness = 0.6, patience = 0.5, empathy = 0.65, sociability = 0.62,
            skills = mapOf(SkillType.COOKING to 48.0, SkillType.SOCIAL to 58.0),
            wealth = 140.0, debt = 300.0
        ), hhMarsh.id, hhMarsh.homeBuildingId!!)
        makeResident(state, rng, Spec("Milo", "Marsh", Gender.MALE, 8, "Pupil", curiosity = 0.7), hhMarsh.id, hhMarsh.homeBuildingId!!)
        makeResident(state, rng, Spec("Ivy", "Marsh", Gender.FEMALE, 5, "Pupil", sociability = 0.65), hhMarsh.id, hhMarsh.homeBuildingId!!)

        // HH4 — the Hartleys: Kit is thinking about leaving for study.
        val hhHartley = household("The Hartley household", "4 Rowan Street", savings = 1_200.0)
        makeResident(state, rng, Spec(
            "Hugh", "Hartley", Gender.MALE, 46, "Joinery worker",
            discipline = 0.6, patience = 0.6, skills = mapOf(SkillType.REPAIR to 52.0), wealth = 1_100.0
        ), hhHartley.id, hhHartley.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Tansy", "Hartley", Gender.FEMALE, 44, "Clinic receptionist",
            kindness = 0.65, sociability = 0.58, empathy = 0.66,
            skills = mapOf(SkillType.SOCIAL to 62.0, SkillType.MEDICINE to 22.0), wealth = 900.0
        ), hhHartley.id, hhHartley.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Kit", "Hartley", Gender.NONBINARY, 17, "Student",
            curiosity = 0.85, ambition = 0.75, courage = 0.6, impulsiveness = 0.5,
            skills = mapOf(SkillType.CREATIVITY to 55.0, SkillType.TEACHING to 25.0), wealth = 90.0
        ), hhHartley.id, hhHartley.homeBuildingId!!)

        // HH5 — the Cranes: Sylvie is the town doctor, running on fumes.
        val hhCrane = household("The Crane household", "2 Rowan Street", savings = 2_400.0)
        makeResident(state, rng, Spec(
            "Sylvie", "Crane", Gender.FEMALE, 41, "Doctor",
            kindness = 0.7, discipline = 0.75, empathy = 0.78, patience = 0.55, ambition = 0.6,
            skills = mapOf(SkillType.MEDICINE to 85.0, SkillType.SOCIAL to 50.0),
            wealth = 3_000.0
        ), hhCrane.id, hhCrane.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Bram", "Crane", Gender.MALE, 43, "Bookshop assistant",
            curiosity = 0.75, sociability = 0.4, patience = 0.7,
            skills = mapOf(SkillType.CREATIVITY to 60.0, SkillType.TEACHING to 40.0), wealth = 1_200.0
        ), hhCrane.id, hhCrane.homeBuildingId!!)

        // HH6 — Arthur Pemberton (hidden heart condition) and his lodger Wren.
        val hhPemberton = household("The Pemberton household", "7 Rowan Street", savings = 3_800.0)
        makeResident(state, rng, Spec(
            "Arthur", "Pemberton", Gender.MALE, 67, "Retired",
            patience = 0.75, kindness = 0.62, sociability = 0.45, honesty = 0.75,
            skills = mapOf(SkillType.CARPENTRY to 55.0, SkillType.POLITICS to 40.0),
            wealth = 5_200.0, politicalInterest = 0.4
        ), hhPemberton.id, hhPemberton.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Wren", "Oakes", Gender.FEMALE, 28, "Bookseller",
            curiosity = 0.8, sociability = 0.55, kindness = 0.66, empathy = 0.68,
            skills = mapOf(SkillType.CREATIVITY to 58.0, SkillType.BUSINESS to 30.0, SkillType.SOCIAL to 48.0),
            wealth = 520.0
        ), hhPemberton.id, hhPemberton.homeBuildingId!!)

        // HH7 — Noa Fenwick, tailor, lives alone. Slowly becoming friends with Wren.
        val hhFenwick = household("Noa Fenwick", "12 Rowan Street", savings = 700.0)
        makeResident(state, rng, Spec(
            "Noa", "Fenwick", Gender.NONBINARY, 29, "Tailor & owner",
            curiosity = 0.6, discipline = 0.7, sociability = 0.42, patience = 0.68,
            skills = mapOf(SkillType.CREATIVITY to 72.0, SkillType.BUSINESS to 42.0),
            wealth = 950.0
        ), hhFenwick.id, hhFenwick.homeBuildingId!!)

        // HH8 — the Dunmores: pub landlords.
        val hhDunmore = household("The Dunmore household", "1 Rowan Street", savings = 1_800.0)
        makeResident(state, rng, Spec(
            "Gil", "Dunmore", Gender.MALE, 45, "Publican",
            sociability = 0.82, ambition = 0.65, impulsiveness = 0.5, courage = 0.6,
            skills = mapOf(SkillType.SOCIAL to 75.0, SkillType.BUSINESS to 55.0, SkillType.POLITICS to 45.0),
            wealth = 2_200.0, politicalInterest = 0.6
        ), hhDunmore.id, hhDunmore.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Lottie", "Dunmore", Gender.FEMALE, 43, "Bar worker",
            sociability = 0.7, kindness = 0.6, skills = mapOf(SkillType.SOCIAL to 66.0), wealth = 800.0
        ), hhDunmore.id, hhDunmore.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Perry", "Dunmore", Gender.MALE, 15, "Student",
            sociability = 0.6, impulsiveness = 0.6, skills = mapOf(SkillType.FITNESS to 40.0), wealth = 40.0
        ), hhDunmore.id, hhDunmore.homeBuildingId!!)

        // HH9 — the Silverstones: Vernon is the ageing mayor.
        val hhSilverstone = household("The Silverstone household", "6 Rowan Street", savings = 6_000.0)
        val vernon = makeResident(state, rng, Spec(
            "Vernon", "Silverstone", Gender.MALE, 66, "Mayor",
            patience = 0.6, honesty = 0.6, ambition = 0.45, sociability = 0.55,
            skills = mapOf(SkillType.POLITICS to 72.0, SkillType.SOCIAL to 60.0),
            wealth = 7_500.0, politicalInterest = 0.85
        ), hhSilverstone.id, hhSilverstone.homeBuildingId!!)
        state.mayorId = vernon.id
        makeResident(state, rng, Spec(
            "Maud", "Silverstone", Gender.FEMALE, 64, "Retired",
            kindness = 0.7, patience = 0.7, skills = mapOf(SkillType.COOKING to 60.0), wealth = 2_000.0
        ), hhSilverstone.id, hhSilverstone.homeBuildingId!!)

        // HH10 — the Garrows: grocers; Fern runs the café.
        val hhGarrow = household("The Garrow household", "5 Rowan Street", savings = 2_600.0)
        makeResident(state, rng, Spec(
            "Oren", "Garrow", Gender.MALE, 48, "Grocer & owner",
            discipline = 0.68, honesty = 0.7, ambition = 0.55,
            skills = mapOf(SkillType.BUSINESS to 60.0, SkillType.SOCIAL to 50.0), wealth = 3_200.0
        ), hhGarrow.id, hhGarrow.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Fern", "Garrow", Gender.FEMALE, 46, "Café owner",
            sociability = 0.7, ambition = 0.6, kindness = 0.6,
            skills = mapOf(SkillType.COOKING to 62.0, SkillType.BUSINESS to 58.0, SkillType.SOCIAL to 64.0),
            wealth = 2_800.0
        ), hhGarrow.id, hhGarrow.homeBuildingId!!)

        // HH11 — flatshare at 11 Rowan Street. Ash is unemployed with real carpentry talent.
        val hhFlat = household("11 Rowan Street flatshare", "11 Rowan Street", rent = 360.0, savings = 150.0)
        makeResident(state, rng, Spec(
            "Felix", "Ingram", Gender.MALE, 26, "Joinery worker",
            sociability = 0.66, impulsiveness = 0.55, courage = 0.6,
            skills = mapOf(SkillType.FITNESS to 55.0, SkillType.REPAIR to 40.0), wealth = 380.0
        ), hhFlat.id, hhFlat.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Juno", "Larkin", Gender.FEMALE, 25, "Nurse",
            kindness = 0.74, empathy = 0.76, discipline = 0.6,
            skills = mapOf(SkillType.MEDICINE to 58.0, SkillType.SOCIAL to 52.0), wealth = 610.0
        ), hhFlat.id, hhFlat.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Ash", "Thistle", Gender.NONBINARY, 27, "Unemployed",
            curiosity = 0.7, ambition = 0.62, courage = 0.5, discipline = 0.55,
            skills = mapOf(SkillType.CARPENTRY to 66.0, SkillType.REPAIR to 58.0, SkillType.CREATIVITY to 50.0),
            wealth = 120.0, debt = 250.0
        ), hhFlat.id, hhFlat.homeBuildingId!!)

        // HH12 — the Ludlows: hardware shop family.
        val hhLudlow = household("The Ludlow household", "9 Rowan Street", savings = 2_000.0)
        makeResident(state, rng, Spec(
            "Ned", "Ludlow", Gender.MALE, 39, "Hardware owner",
            discipline = 0.66, honesty = 0.72, patience = 0.6,
            skills = mapOf(SkillType.BUSINESS to 55.0, SkillType.REPAIR to 62.0), wealth = 2_400.0
        ), hhLudlow.id, hhLudlow.homeBuildingId!!)
        makeResident(state, rng, Spec(
            "Willa", "Ludlow", Gender.FEMALE, 37, "Teacher",
            kindness = 0.68, patience = 0.72, empathy = 0.7,
            skills = mapOf(SkillType.TEACHING to 65.0, SkillType.SOCIAL to 55.0), wealth = 1_300.0
        ), hhLudlow.id, hhLudlow.homeBuildingId!!)
        makeResident(state, rng, Spec("Robin", "Ludlow", Gender.NONBINARY, 10, "Pupil", curiosity = 0.75), hhLudlow.id, hhLudlow.homeBuildingId!!)
    }

    // ------------------------------------------------------ businesses/jobs

    private fun buildBusinessesAndJobs(state: WorldState) {
        fun res(name: String): Resident = state.residents.values.first { "${it.firstName} ${it.surname}" == name }

        fun business(
            buildingName: String, bizName: String, type: BusinessType, owner: Resident?,
            balance: Double, demand: Double, capacity: Int
        ): Business {
            val building = buildingByName(state, buildingName)
            val id = state.nextBusinessId++
            val b = Business(
                id = id, buildingId = building.id, name = bizName, type = type,
                ownerId = owner?.id, balance = balance, demand = demand,
                employeeCapacity = capacity, openedAt = 0L
            )
            state.businesses[id] = b
            if (owner != null) building.ownerId = owner.id
            return b
        }

        fun employ(r: Resident, biz: Business, role: String, salary: Double, startH: Int = 9, endH: Int = 17) {
            val id = state.nextEmploymentId++
            state.employments[id] = Employment(
                id = id, residentId = r.id, businessId = biz.id, role = role,
                dailySalary = salary, startedAt = 0L, shiftStartHour = startH, shiftEndHour = endH
            )
            r.employmentId = id
        }

        val bakery = business("Bell's Bakery", "Bell's Bakery", BusinessType.BAKERY, res("Tom Bell"),
            balance = 260.0, demand = 38.0, capacity = 3)
        bakery.reputation = 62.0
        bakery.daysInTrouble = 2
        employ(res("Tom Bell"), bakery, "Owner-baker", 55.0, 5, 14)
        employ(res("Mara Vale"), bakery, "Bakery assistant", 42.0, 6, 14)

        val cafe = business("The Willow Café", "The Willow Café", BusinessType.CAFE, res("Fern Garrow"),
            balance = 2_400.0, demand = 60.0, capacity = 3)
        employ(res("Fern Garrow"), cafe, "Owner", 60.0, 8, 17)
        employ(res("Petra Marsh"), cafe, "Café worker", 40.0, 8, 16)

        val pub = business("The Old Lantern", "The Old Lantern", BusinessType.PUB, res("Gil Dunmore"),
            balance = 3_100.0, demand = 66.0, capacity = 4)
        employ(res("Gil Dunmore"), pub, "Landlord", 62.0, 12, 23)
        employ(res("Lottie Dunmore"), pub, "Bar worker", 38.0, 16, 23)

        val grocer = business("Garrow's Grocery", "Garrow's Grocery", BusinessType.GROCER, res("Oren Garrow"),
            balance = 3_800.0, demand = 70.0, capacity = 3)
        employ(res("Oren Garrow"), grocer, "Owner", 58.0, 8, 18)
        employ(res("Edie Bell"), grocer, "Grocery assistant", 40.0, 9, 17)

        val hardware = business("Ludlow Hardware", "Ludlow Hardware", BusinessType.HARDWARE, res("Ned Ludlow"),
            balance = 2_900.0, demand = 52.0, capacity = 3)
        employ(res("Ned Ludlow"), hardware, "Owner", 56.0, 9, 17)
        employ(res("Wilf Vale"), hardware, "Shop assistant", 44.0, 9, 17)

        val books = business("Quince & Daughter Books", "Quince & Daughter Books", BusinessType.BOOKSHOP, null,
            balance = 1_500.0, demand = 40.0, capacity = 2)
        employ(res("Wren Oakes"), books, "Bookseller", 40.0, 9, 17)
        employ(res("Bram Crane"), books, "Bookshop assistant", 38.0, 10, 17)

        val tailor = business("Fenwick Tailoring", "Fenwick Tailoring", BusinessType.TAILOR, res("Noa Fenwick"),
            balance = 1_700.0, demand = 42.0, capacity = 2)
        employ(res("Noa Fenwick"), tailor, "Tailor", 52.0, 9, 17)

        val factory = business("Ashcombe Joinery Works", "Ashcombe Joinery Works", BusinessType.FACTORY, null,
            balance = 9_000.0, demand = 62.0, capacity = 6)
        employ(res("Jonas Marsh"), factory, "Joinery worker", 46.0, 8, 17)
        employ(res("Felix Ingram"), factory, "Joinery worker", 46.0, 8, 17)
        employ(res("Hugh Hartley"), factory, "Machinist", 50.0, 8, 17)

        val clinic = business("Ashcombe Clinic", "Ashcombe Clinic", BusinessType.CLINIC, null,
            balance = 12_000.0, demand = 55.0, capacity = 4)
        employ(res("Sylvie Crane"), clinic, "Doctor", 85.0, 8, 18)
        employ(res("Juno Larkin"), clinic, "Nurse", 52.0, 8, 17)
        employ(res("Tansy Hartley"), clinic, "Receptionist", 40.0, 9, 16)

        val school = business("Ashcombe School", "Ashcombe School", BusinessType.SCHOOL, null,
            balance = 10_000.0, demand = 50.0, capacity = 4)
        employ(res("Rosa Vale"), school, "Teacher", 58.0, 8, 16)
        employ(res("Willa Ludlow"), school, "Teacher", 56.0, 8, 16)

        val hall = business("Ashcombe Town Hall", "Ashcombe Town Hall", BusinessType.TOWN_HALL, null,
            balance = 30_000.0, demand = 30.0, capacity = 3)
        employ(res("Vernon Silverstone"), hall, "Mayor", 70.0, 9, 16)
    }

    // -------------------------------------------------- background residents

    private fun buildBackgroundResidents(state: WorldState, rng: SimRandom) {
        val occupations = listOf(
            "Farm worker", "Delivery driver", "Seamstress", "Gardener", "Cleaner", "Fisher",
            "Postal worker", "Labourer", "Home help", "Clerk", "Carter", "Retired", "Between jobs"
        )
        repeat(BACKGROUND_COUNT) {
            val id = state.nextResidentId++
            val gender = when (rng.nextInt(10)) {
                in 0..4 -> Gender.FEMALE
                in 5..9 -> Gender.MALE
                else -> Gender.NONBINARY
            }
            val genderPick = if (rng.nextBoolean(0.06)) Gender.NONBINARY else gender
            val first = when (genderPick) {
                Gender.FEMALE -> rng.pick(NameData.FEMALE_FIRST)
                Gender.MALE -> rng.pick(NameData.MALE_FIRST)
                Gender.NONBINARY -> rng.pick(NameData.NEUTRAL_FIRST)
            }
            val age = rng.nextInt(18, 76)
            val r = Resident(
                id = id,
                firstName = first,
                surname = rng.pick(NameData.SURNAMES),
                gender = genderPick,
                bornAt = state.time - age.toLong() * SimTime.MINUTES_PER_YEAR - rng.nextLong(0, SimTime.MINUTES_PER_YEAR),
                homeBuildingId = null, // lives in outer Ashcombe, off the detailed map
                householdId = null,
                detailLevel = DetailLevel.BACKGROUND,
                sprite = SpriteConfig(
                    skinTone = rng.nextInt(4), hairStyle = rng.nextInt(4), hairColor = rng.nextInt(5),
                    shirtColor = rng.nextInt(8), trouserColor = rng.nextInt(5)
                ),
                occupation = rng.pick(occupations),
                wealth = rng.nextDouble(50.0, 2_500.0),
                needs = Needs(
                    hunger = rng.nextDouble(50.0, 90.0), energy = rng.nextDouble(50.0, 90.0),
                    health = rng.nextDouble(60.0, 95.0), stress = rng.nextDouble(10.0, 50.0)
                ),
                personality = Personality(
                    kindness = rng.nextDouble(0.2, 0.8), ambition = rng.nextDouble(0.2, 0.8),
                    curiosity = rng.nextDouble(0.2, 0.8), sociability = rng.nextDouble(0.2, 0.8),
                    patience = rng.nextDouble(0.2, 0.8), honesty = rng.nextDouble(0.3, 0.9),
                    courage = rng.nextDouble(0.2, 0.8), discipline = rng.nextDouble(0.2, 0.8),
                    empathy = rng.nextDouble(0.2, 0.8), impulsiveness = rng.nextDouble(0.2, 0.8)
                )
            )
            state.residents[id] = r
        }
    }

    // ---------------------------------------------------------- relationships

    private fun buildRelationships(state: WorldState, rng: SimRandom) {
        fun res(name: String): Resident = state.residents.values.first { "${it.firstName} ${it.surname}" == name }

        fun rel(a: Resident, b: Resident, kind: RelationshipKind, block: Relationship.() -> Unit = {}) {
            val r = Relationship.create(a.id, b.id, kind)
            r.familiarity = 60.0; r.trust = 60.0; r.affection = 55.0; r.respect = 55.0
            r.sharedHistory = 40.0
            r.block()
            r.clampAll()
            state.relationships[Relationship.keyOf(a.id, b.id)] = r
        }

        fun marry(a: Resident, b: Resident, strain: Double = 0.0) {
            a.relationshipStatus = RelationshipStatus.MARRIED; a.partnerId = b.id
            b.relationshipStatus = RelationshipStatus.MARRIED; b.partnerId = a.id
            rel(a, b, RelationshipKind.SPOUSE) {
                familiarity = 92.0; trust = (78.0 - strain); affection = (74.0 - strain)
                attraction = 55.0; respect = 68.0; resentment = strain; dependency = 50.0
                sharedHistory = 85.0
            }
        }

        fun family(a: Resident, b: Resident) {
            rel(a, b, RelationshipKind.FAMILY) {
                familiarity = 88.0; trust = 74.0; affection = 70.0; respect = 60.0; sharedHistory = 75.0
            }
        }

        fun parentOf(parent: Resident, child: Resident) {
            child.apply { if (parent.gender == Gender.MALE) fatherId = parent.id else motherId = parent.id }
            parent.childIds += child.id
            family(parent, child)
        }

        // Vales
        marry(res("Wilf Vale"), res("Rosa Vale"))
        parentOf(res("Wilf Vale"), res("Mara Vale")); parentOf(res("Rosa Vale"), res("Mara Vale"))
        // Bells
        marry(res("Tom Bell"), res("Edie Bell"), strain = 6.0)
        // Marshes — money worries are already biting
        marry(res("Jonas Marsh"), res("Petra Marsh"), strain = 18.0)
        parentOf(res("Jonas Marsh"), res("Milo Marsh")); parentOf(res("Petra Marsh"), res("Milo Marsh"))
        parentOf(res("Jonas Marsh"), res("Ivy Marsh")); parentOf(res("Petra Marsh"), res("Ivy Marsh"))
        family(res("Milo Marsh"), res("Ivy Marsh"))
        // Hartleys
        marry(res("Hugh Hartley"), res("Tansy Hartley"))
        parentOf(res("Hugh Hartley"), res("Kit Hartley")); parentOf(res("Tansy Hartley"), res("Kit Hartley"))
        // Cranes
        marry(res("Sylvie Crane"), res("Bram Crane"))
        // Dunmores
        marry(res("Gil Dunmore"), res("Lottie Dunmore"))
        parentOf(res("Gil Dunmore"), res("Perry Dunmore")); parentOf(res("Lottie Dunmore"), res("Perry Dunmore"))
        // Silverstones
        marry(res("Vernon Silverstone"), res("Maud Silverstone"))
        // Garrows
        marry(res("Oren Garrow"), res("Fern Garrow"))
        // Ludlows
        marry(res("Ned Ludlow"), res("Willa Ludlow"))
        parentOf(res("Ned Ludlow"), res("Robin Ludlow")); parentOf(res("Willa Ludlow"), res("Robin Ludlow"))

        // Landlord & lodger
        rel(res("Arthur Pemberton"), res("Wren Oakes"), RelationshipKind.FRIEND) {
            familiarity = 55.0; trust = 58.0; affection = 45.0; respect = 60.0; sharedHistory = 20.0
        }
        // The budding friendship (scenario 2)
        rel(res("Wren Oakes"), res("Noa Fenwick"), RelationshipKind.ACQUAINTANCE) {
            familiarity = 28.0; trust = 44.0; affection = 38.0; respect = 50.0; sharedHistory = 8.0
        }
        // Colleagues & neighbours
        rel(res("Mara Vale"), res("Tom Bell"), RelationshipKind.FRIEND) {
            familiarity = 62.0; trust = 60.0; affection = 48.0; respect = 62.0; sharedHistory = 30.0
        }
        rel(res("Jonas Marsh"), res("Felix Ingram"), RelationshipKind.FRIEND) {
            familiarity = 55.0; trust = 52.0; affection = 45.0
        }
        rel(res("Sylvie Crane"), res("Juno Larkin"), RelationshipKind.FRIEND) {
            familiarity = 58.0; trust = 62.0; respect = 66.0
        }
        rel(res("Mara Vale"), res("Juno Larkin"), RelationshipKind.FRIEND) {
            familiarity = 60.0; trust = 58.0; affection = 55.0; sharedHistory = 35.0
        }
        rel(res("Kit Hartley"), res("Perry Dunmore"), RelationshipKind.CLOSE_FRIEND) {
            familiarity = 74.0; trust = 68.0; affection = 62.0; sharedHistory = 45.0
        }
        // A little grit: publican and mayor never saw eye to eye (election is coming)
        rel(res("Gil Dunmore"), res("Vernon Silverstone"), RelationshipKind.RIVAL) {
            familiarity = 58.0; trust = 30.0; affection = 15.0; respect = 38.0; resentment = 42.0
        }
        // Bakery vs café: friendly-ish competition with an edge
        rel(res("Tom Bell"), res("Fern Garrow"), RelationshipKind.ACQUAINTANCE) {
            familiarity = 44.0; trust = 40.0; affection = 25.0; respect = 45.0; resentment = 22.0
        }

        // A few seeded acquaintances between detailed residents so the town isn't cliquey
        val detailed = state.detailedResidents().filter { it.ageAt(state.time) >= 13 }
        repeat(14) {
            val a = rng.pick(detailed); val b = rng.pick(detailed)
            if (a.id != b.id && state.relationship(a.id, b.id) == null) {
                val r = Relationship.create(a.id, b.id, RelationshipKind.ACQUAINTANCE)
                r.familiarity = rng.nextDouble(10.0, 35.0)
                r.trust = rng.nextDouble(30.0, 50.0)
                r.affection = rng.nextDouble(15.0, 40.0)
                state.relationships[Relationship.keyOf(a.id, b.id)] = r
            }
        }
    }

    // ---------------------------------------------------------------- seeds

    private fun seedScenarios(state: WorldState) {
        fun res(name: String): Resident = state.residents.values.first { "${it.firstName} ${it.surname}" == name }
        val now = state.time
        val day = SimTime.MINUTES_PER_DAY

        // 8. Arthur's hidden heart condition
        val arthur = res("Arthur Pemberton")
        arthur.conditions += HealthCondition(
            id = state.nextConditionId++, residentId = arthur.id,
            type = HealthConditionType.WEAK_HEART, severity = 24.0, startedAt = now - 40 * day, hidden = true
        )

        // 5. Sylvie is exhausted
        val sylvie = res("Sylvie Crane")
        sylvie.needs.stress = 72.0
        sylvie.needs.energy = 38.0

        // 4. Kit wants to leave for education
        val kit = res("Kit Hartley")
        kit.goals += Goal(
            id = state.nextGoalId++, ownerId = kit.id, type = GoalType.LEAVE_FOR_EDUCATION,
            motivation = "Ashcombe feels small; there's a college two valleys over.",
            createdAt = now - 10 * day, progress = 0.2, risk = 0.35
        )

        // 6. Ash dreams of turning the Old Granary into a workshop
        val ash = res("Ash Thistle")
        ash.ideaSeeds += "furniture_workshop"
        ash.goals += Goal(
            id = state.nextGoalId++, ownerId = ash.id, type = GoalType.FIND_JOB,
            motivation = "Rent is due and the flatshare can't carry a third person for free.",
            createdAt = now - 5 * day, progress = 0.1, risk = 0.2
        )

        // 3. The Marshes' money strain will keep pressing on the marriage
        val jonas = res("Jonas Marsh")
        state.delayedEffects += DelayedEffect(
            id = state.nextEffectId++, sourceEventId = 0L,
            targetResidentId = jonas.id, secondaryResidentId = res("Petra Marsh").id,
            type = DelayedEffectType.RELATIONSHIP_PRESSURE, strength = 0.5,
            earliestAt = now + 3 * day, latestAt = now + 20 * day,
            condition = EffectCondition.STILL_POOR, note = "Money worries at home"
        )

        // 10. A new family is weighing up a move to Ashcombe
        state.delayedEffects += DelayedEffect(
            id = state.nextEffectId++, sourceEventId = 0L,
            type = DelayedEffectType.GOAL_SEED, strength = 0.8,
            earliestAt = now + 18 * day, latestAt = now + 55 * day,
            note = NEW_FAMILY_NOTE
        )

        // 1. The bakery is already wobbling — nudge demand a touch lower to start
        state.businesses.values.first { it.type == BusinessType.BAKERY }.demand = 36.0
    }

    companion object {
        const val MAP_W = 44
        const val MAP_H = 34
        const val HIGH_ST_Y = 10
        const val ROWAN_ST_Y = 22
        const val BACKGROUND_COUNT = 60
        const val NEW_FAMILY_NOTE = "new_family_arrival"
    }
}
