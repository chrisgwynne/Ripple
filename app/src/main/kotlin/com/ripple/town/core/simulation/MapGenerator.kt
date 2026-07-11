package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.District
import com.ripple.town.core.model.DistrictType
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.TownMap

/**
 * Procedural map generator for the expanded 160×100 tile town. Produces:
 * - A [TownMap] with a full road grid, river, bridges, terrain decoration, and district
 *   ground-cover variation (denser trees in South Quarter, plazas in Civic Quarter).
 * - Five [District] regions whose tile bounds cover the whole map without overlap.
 * - [BuildingLot] lists for all procedurally placed homes and civic buildings; the
 *   hand-authored Ashcombe buildings in [WorldGenerator] are placed separately.
 *
 * Layout (tile coordinates):
 *
 *   x=0..41        x=42..43  x=44..99             x=100..159
 *   ┌─────────────┬────────┬──────────────────────┬──────────────────────┐
 *   │ TOWN_CENTRE │ River  │ EAST_VILLAGE         │ MILL_LANE            │ y=0..43
 *   │ (Ashcombe)  │        │ (residential)        │ (industrial+housing) │
 *   ├─────────────┤        ├──────────────────────┤                      │
 *   │ SOUTH_QUARTER        │ CIVIC_QUARTER        │                      │ y=44..99
 *   │ (terrace housing)    │ (civic/institutional)│                      │
 *   └──────────────────────┴──────────────────────┴──────────────────────┘
 *
 * Road grid:
 *   West side (x 0..41):
 *     Horizontal: y=10 (High St), y=22 (Rowan St), y=44,56,68,80,92 (South Quarter streets)
 *     Vertical:   x=10,22,34 (continue full map height through South Quarter)
 *   River:        x=42..43, full height; bridges at all horizontal roads
 *   East side (x 44..159):
 *     Horizontal: y=10,22,35 (East Village), y=44,56,68,80,92 (shared with south)
 *     Vertical:   x=55,70,85,99 (East/Civic dividers), x=115,130,145,157 (Mill Lane)
 */
object MapGenerator {

    const val MAP_W = 160
    const val MAP_H = 100

    // River column indices (same x as legacy MAP_W-2 / MAP_W-1 in the old 44-wide map)
    private const val RIVER_X1 = 42
    private const val RIVER_X2 = 43

    // West-side (Ashcombe + South Quarter) vertical road x positions
    private val WEST_VERTICALS = intArrayOf(10, 22, 34)

    // East-of-river vertical road x positions
    private val EAST_VILLAGE_VERTICALS = intArrayOf(55, 70, 85, 99)
    private val MILL_VERTICALS = intArrayOf(115, 130, 145, 157)

    // Horizontal roads: west side (South Quarter streets)
    private val SOUTH_QUARTER_H_ROADS = intArrayOf(44, 56, 68, 80, 92)

    // Horizontal roads: east side only
    private const val EV_LOCAL_ROAD_Y = 35  // East Village local street

    // Horizontal roads shared across both sides (bridges span river)
    private val SHARED_H_ROADS = intArrayOf(10, 22, 44, 56, 68, 80, 92)

    // ------------------------------------------------------------------ model

    /**
     * A positioned slot for a building the world generator should create.
     * Homes use [streetName] as the address prefix ("1 Maple Road").
     * Civic lots use it as the full building name.
     */
    data class BuildingLot(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val doorX: Int,
        val doorY: Int,
        val type: BuildingType,
        val districtType: DistrictType,
        /** Address street name (homes) or full building name (civic). */
        val streetName: String,
        val noise: Double = 10.0,
        val capacity: Int = 6,
        val value: Double = 40_000.0
    )

    data class GenerationResult(
        val map: TownMap,
        val districts: List<District>,
        val homeLots: List<BuildingLot>,
        val civicLots: List<BuildingLot>
    )

    // ----------------------------------------------------------------- public

    fun generate(rng: SimRandom): GenerationResult {
        val tiles = MutableList(MAP_W * MAP_H) { TileType.GRASS }

        fun set(x: Int, y: Int, t: TileType) {
            if (x in 0 until MAP_W && y in 0 until MAP_H) tiles[y * MAP_W + x] = t
        }

        // ---- River (full height)
        for (y in 0 until MAP_H) {
            set(RIVER_X1, y, TileType.WATER)
            set(RIVER_X2, y, TileType.WATER)
        }

        // ---- Reproduce legacy Ashcombe road + decoration exactly (preserves hand-authored town)
        for (x in 0 until RIVER_X1) {
            set(x, 10, TileType.ROAD)
            set(x, 22, TileType.ROAD)
        }
        for (y in 0 until MAP_H) {
            set(10, y, TileType.ROAD)
            set(22, y, TileType.ROAD)
            set(34, y, TileType.ROAD)
        }
        // Plaza in front of town hall (unchanged from legacy WorldGenerator.buildMap)
        for (x in 13..19) for (y in 11..12) {
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.PLAZA)
        }
        // Park greenery (same rng sequence as legacy buildMap so park looks consistent)
        for (x in 12..19) for (y in 23..29) {
            val t = when {
                rng.nextBoolean(0.18) -> TileType.TREE
                rng.nextBoolean(0.25) -> TileType.FLOWERS
                else -> TileType.GRASS
            }
            set(x, y, t)
        }
        for (x in 14..17) set(x, 26, TileType.PATH)
        // Scattered Ashcombe trees (same count as legacy)
        repeat(46) {
            val x = rng.nextInt(RIVER_X1)
            val y = rng.nextInt(34)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }

        // ---- Bridges: overwrite river tiles on every shared horizontal road
        for (roadY in SHARED_H_ROADS) {
            set(RIVER_X1, roadY, TileType.ROAD)
            set(RIVER_X2, roadY, TileType.ROAD)
        }
        // Also bridge the East Village local road if it crosses the river boundary area
        set(RIVER_X1, EV_LOCAL_ROAD_Y, TileType.ROAD)
        set(RIVER_X2, EV_LOCAL_ROAD_Y, TileType.ROAD)

        // ---- South Quarter horizontal roads (west, y=44..92)
        for (roadY in SOUTH_QUARTER_H_ROADS) {
            for (x in 0 until RIVER_X1) set(x, roadY, TileType.ROAD)
        }

        // ---- East of river: horizontal roads
        val eastStart = RIVER_X2 + 1 // = 44
        val allEastHRoads = (SHARED_H_ROADS.toList() + EV_LOCAL_ROAD_Y).distinct().sorted()
        for (roadY in allEastHRoads) {
            for (x in eastStart until MAP_W) set(x, roadY, TileType.ROAD)
        }

        // ---- East vertical roads
        for (vx in EAST_VILLAGE_VERTICALS + MILL_VERTICALS) {
            for (y in 0 until MAP_H) set(vx, y, TileType.ROAD)
        }

        // ---- District terrain decoration
        // South Quarter: scattered hedgerows + trees between streets
        repeat(90) {
            val x = rng.nextInt(RIVER_X1)
            val y = 34 + rng.nextInt(MAP_H - 34)
            if (tiles[y * MAP_W + x] == TileType.GRASS) {
                set(x, y, if (rng.nextBoolean(0.4)) TileType.TREE else TileType.FLOWERS)
            }
        }
        // East Village: lighter tree cover
        repeat(60) {
            val x = eastStart + rng.nextInt(56)
            val y = rng.nextInt(44)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }
        // Civic Quarter: plazas near civic buildings, some trees
        for (x in 47..97) for (y in 47..50) {
            if (tiles[y * MAP_W + x] == TileType.GRASS && rng.nextBoolean(0.15)) {
                set(x, y, TileType.PLAZA)
            }
        }
        repeat(40) {
            val x = eastStart + rng.nextInt(56)
            val y = 44 + rng.nextInt(MAP_H - 44)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }
        // Mill Lane: industrial scrub
        repeat(50) {
            val x = 100 + rng.nextInt(60)
            val y = rng.nextInt(MAP_H)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }

        // ---- Districts (five non-overlapping rectangles covering the whole map)
        var nextId = 1L
        val districts = listOf(
            District(nextId++, "Ashcombe",      DistrictType.TOWN_CENTRE,   0,        0,  42,       44),
            District(nextId++, "South Quarter", DistrictType.SOUTH_QUARTER, 0,       44,  42,  MAP_H - 44),
            District(nextId++, "East Village",  DistrictType.EAST_VILLAGE,  44,       0,  56,       44),
            District(nextId++, "Civic Quarter", DistrictType.CIVIC_QUARTER, 44,      44,  56,  MAP_H - 44),
            District(nextId++, "Mill Lane",     DistrictType.MILL_LANE,    100,       0,  60,      MAP_H)
        )

        // ---- Home lots
        val homeLots = mutableListOf<BuildingLot>()
        placeSouthQuarterHomes(homeLots, rng)
        placeEastVillageHomes(homeLots, rng)
        placeMillLaneHomes(homeLots, rng)

        // ---- Civic lots
        val civicLots = mutableListOf<BuildingLot>()
        placeCivicBuildings(civicLots)

        return GenerationResult(TownMap(MAP_W, MAP_H, tiles), districts, homeLots, civicLots)
    }

    // ----------------------------------------------------------------- homes

    /**
     * Place a single row of 3×3 homes in x-blocks along [roadY], on the north
     * or south side (controlled by [homesOnNorth]).
     */
    private fun placeHomeRow(
        lots: MutableList<BuildingLot>,
        rng: SimRandom,
        xBlocks: List<IntRange>,
        roadY: Int,
        homesOnNorth: Boolean,
        districtType: DistrictType,
        streetName: String
    ) {
        val bldgY = if (homesOnNorth) roadY - 3 else roadY + 1
        for (range in xBlocks) {
            var x = range.first
            while (x + 2 <= range.last) {
                lots += BuildingLot(
                    x = x, y = bldgY, w = 3, h = 3,
                    doorX = x + 1, doorY = roadY,
                    type = if (rng.nextBoolean(0.3)) BuildingType.HOUSE else BuildingType.TERRACE,
                    districtType = districtType,
                    streetName = streetName
                )
                x += 4
            }
        }
    }

    private fun placeSouthQuarterHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // x-blocks between the vertical roads (x=10,22,34) and map edges within x=0..41
        val xBlocks = listOf(0..9, 11..21, 23..33, 35..41)
        val streetNames = mapOf(
            44 to "Millbrook Road",
            56 to "Chapel Street",
            68 to "Orchard Lane",
            80 to "Ferndale Road",
            92 to "Brook Street"
        )
        for (roadY in SOUTH_QUARTER_H_ROADS) {
            val name = streetNames[roadY] ?: "South Quarter"
            // Homes facing road from south (below road)
            placeHomeRow(lots, rng, xBlocks, roadY, homesOnNorth = false, DistrictType.SOUTH_QUARTER, name)
            // Homes facing road from north (above road) — ensure building fits inside South Quarter (y>=44)
            // bldgY = roadY - 3; only place if roadY - 3 >= 45 (leaves gap from previous road's south row)
            if (roadY - 3 >= 45) {
                placeHomeRow(lots, rng, xBlocks, roadY, homesOnNorth = true, DistrictType.SOUTH_QUARTER, name)
            }
        }
    }

    private fun placeEastVillageHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // x-blocks: gaps between east verticals (44-54, 56-69, 71-84, 86-98)
        val xBlocks = listOf(44..54, 56..69, 71..84, 86..98)
        val eastVillageRoads = intArrayOf(10, 22, EV_LOCAL_ROAD_Y, 44)
        val streetNames = mapOf(
            10 to "River Road",
            22 to "Station Road",
            EV_LOCAL_ROAD_Y to "Oak Avenue",
            44 to "The Vale"
        )
        for (roadY in eastVillageRoads) {
            val name = streetNames[roadY] ?: "East Village"
            // South face (homes below road) — ensure building fits in East Village (y < 44)
            if (roadY + 3 < 44) {
                placeHomeRow(lots, rng, xBlocks, roadY, homesOnNorth = false, DistrictType.EAST_VILLAGE, name)
            }
            // North face (homes above road) — ensure building fits (y >= 0) and not above map
            if (roadY - 3 >= 0 && roadY <= 43) {
                placeHomeRow(lots, rng, xBlocks, roadY, homesOnNorth = true, DistrictType.EAST_VILLAGE, name)
            }
        }
    }

    private fun placeMillLaneHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // Worker housing in the northern half of Mill Lane (y=0..43)
        // x-blocks: between Mill Lane verticals (100-114, 116-129, 131-144, 146-156)
        val xBlocks = listOf(100..114, 116..129, 131..144, 146..156)
        val millRoads = intArrayOf(10, 22, EV_LOCAL_ROAD_Y)
        val streetNames = mapOf(
            10 to "Mill Lane North",
            22 to "Foundry Road",
            EV_LOCAL_ROAD_Y to "Cinder Terrace"
        )
        for (roadY in millRoads) {
            val name = streetNames[roadY] ?: "Mill Lane"
            if (roadY + 3 < 44) {
                placeHomeRow(lots, rng, xBlocks, roadY, homesOnNorth = false, DistrictType.MILL_LANE, name)
            }
            if (roadY - 3 >= 0) {
                placeHomeRow(lots, rng, xBlocks, roadY, homesOnNorth = true, DistrictType.MILL_LANE, name)
            }
        }
    }

    // ----------------------------------------------------------------- civic

    private data class CivicSpec(
        val name: String, val type: BuildingType, val x: Int, val y: Int,
        val w: Int, val h: Int, val doorX: Int, val doorY: Int,
        val noise: Double = 15.0, val capacity: Int = 20, val value: Double = 80_000.0,
        val districtType: DistrictType = DistrictType.CIVIC_QUARTER
    )

    private fun placeCivicBuildings(lots: MutableList<BuildingLot>) {
        val specs = listOf(
            // Along y=44 road (north face of Civic Quarter, facing East Village)
            CivicSpec("East Village Fire Station",    BuildingType.FIRE_STATION,
                x=45, y=45, w=4, h=3, doorX=47, doorY=44, noise=30.0, capacity=12, value=100_000.0),
            CivicSpec("East Village Police Station",  BuildingType.POLICE_STATION,
                x=50, y=45, w=4, h=3, doorX=52, doorY=44, noise=15.0, capacity=12, value=100_000.0),
            CivicSpec("East Village Community Centre", BuildingType.COMMUNITY_CENTRE,
                x=57, y=45, w=6, h=4, doorX=60, doorY=44, noise=22.0, capacity=80, value=120_000.0),
            CivicSpec("East Village Sports Hall",     BuildingType.SPORTS_HALL,
                x=64, y=45, w=7, h=5, doorX=67, doorY=44, noise=28.0, capacity=60, value=150_000.0),
            CivicSpec("East Village Clinic",          BuildingType.CLINIC,
                x=72, y=45, w=4, h=3, doorX=74, doorY=44, noise=10.0, capacity=10, value=90_000.0),
            CivicSpec("East Village Library",         BuildingType.BOOKSHOP,
                x=77, y=45, w=4, h=3, doorX=79, doorY=44, noise=5.0,  capacity=30, value=80_000.0),
            // Along y=56 road
            CivicSpec("Ashcombe Secondary School",    BuildingType.SCHOOL,
                x=45, y=57, w=8, h=5, doorX=49, doorY=56, noise=30.0, capacity=60, value=180_000.0),
            CivicSpec("Riverside Recreation Ground",  BuildingType.PARK,
                x=55, y=57, w=8, h=6, doorX=59, doorY=56, noise=18.0, capacity=80, value=50_000.0),
            // Mill Lane industrial zone (south of y=44)
            CivicSpec("Mill Lane Works",              BuildingType.FACTORY,
                x=101, y=45, w=6, h=5, doorX=104, doorY=44, noise=65.0, capacity=18, value=95_000.0,
                districtType = DistrictType.MILL_LANE),
            CivicSpec("Riverside Warehouse",          BuildingType.WORKSHOP,
                x=108, y=45, w=5, h=4, doorX=110, doorY=44, noise=30.0, capacity=10, value=60_000.0,
                districtType = DistrictType.MILL_LANE),
            CivicSpec("The Riverside Arms",           BuildingType.PUB,
                x=114, y=45, w=4, h=3, doorX=116, doorY=44, noise=45.0, capacity=14, value=55_000.0,
                districtType = DistrictType.MILL_LANE),
            CivicSpec("Corner Shop",                  BuildingType.GROCER,
                x=119, y=45, w=3, h=3, doorX=120, doorY=44, noise=12.0, capacity=8,  value=45_000.0,
                districtType = DistrictType.MILL_LANE)
        )
        for (s in specs) {
            lots += BuildingLot(
                x = s.x, y = s.y, w = s.w, h = s.h,
                doorX = s.doorX, doorY = s.doorY,
                type = s.type, districtType = s.districtType,
                streetName = s.name,
                noise = s.noise, capacity = s.capacity, value = s.value
            )
        }
    }
}
