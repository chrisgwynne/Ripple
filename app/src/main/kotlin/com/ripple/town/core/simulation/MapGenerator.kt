package com.ripple.town.core.simulation

import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.District
import com.ripple.town.core.model.DistrictType
import com.ripple.town.core.model.Tile
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.TownMap

/**
 * Procedural map generator for the expanded 320×200 tile town of Ashcombe.
 *
 * ## District layout
 *
 *   x=0..41   │river│ x=44..99  │ x=100..159 │ x=160..199 │ x=200..239 │ x=240..319
 *   TOWN_CTR  │42-43│ EAST_VLG  │  MILL_LANE │  INDUSTRL  │ BUS_PARK   │ NIGHTLIFE  y=0..43
 *   SOUTH_Q   │     │ CIVIC_Q   │  MILL_LANE │  INDUSTRL  │ BUS_PARK   │ NIGHTLIFE  y=44..99
 *   OLD_TOWN  │     │ WEALTHY_Q │  WEALTHY_Q │  INDUSTRL  │ RETAIL_PRK │ RURAL_FRG  y=100..139
 *   RIVERSIDE (full width x=0..239)               │  RETAIL_PRK │ RURAL_FRG  y=140..199
 *
 * River: x=42..43 (full height). Lake: roughly x=60..150, y=150..190 (SHALLOW_WATER).
 * Bridges over river at every shared horizontal road, plus three extra at y=110,120,130.
 * Bridge over lake at x=100..101 for y=170 road.
 */
object MapGenerator {

    const val MAP_W = 320
    const val MAP_H = 200

    // River
    private const val RIVER_X1 = 42
    private const val RIVER_X2 = 43

    // District x-boundaries
    private const val EV_START   = 44    // East Village / Civic Quarter start
    private const val ML_START   = 100   // Mill Lane start
    private const val IE_START   = 160   // Industrial Estate start
    private const val BP_START   = 200   // Business Park / Retail Park start
    private const val NQ_START   = 240   // Nightlife Quarter / Rural Fringe start

    // District y-boundaries
    private const val TC_END     = 44    // Town Centre / East Village / Mill Lane south edge (y<44)
    private const val SQ_END     = 100   // South Quarter / Civic Quarter south edge (y<100)
    private const val WQ_END     = 140   // Old Town / Wealthy Quarter south edge (y<140)
    // Riverside: y=140..199

    // Horizontal roads shared across both river banks (bridges span river here)
    private val SHARED_H_ROADS = intArrayOf(10, 22, 44, 56, 68, 80, 92)

    // Additional bridges at the new y=100..139 crossing band
    private val NEW_BRIDGE_H = intArrayOf(110, 120, 130)

    // West-side (Ashcombe) vertical roads
    private val WEST_VERTICALS = intArrayOf(10, 22, 34)

    // East Village + Civic Quarter verticals
    private val EV_VERTICALS = intArrayOf(55, 70, 85, 99)

    // Mill Lane verticals
    private val ML_VERTICALS = intArrayOf(115, 130, 145, 157)

    // Industrial Estate verticals
    private val IE_VERTICALS = intArrayOf(175, 192)

    // Business Park / Retail Park verticals (east side)
    private val BP_VERTICALS = intArrayOf(215, 230)

    // Nightlife / Rural Fringe verticals
    private val NQ_VERTICALS = intArrayOf(255, 275, 295, 312)

    // East Village local road (below y=44 east side)
    private const val EV_LOCAL_ROAD_Y = 35

    // South Quarter horizontal streets
    private val SQ_H_ROADS = intArrayOf(44, 56, 68, 80, 92)

    // Old Town / Wealthy Quarter / RIVERSIDE horizontal roads
    private val OT_H_ROADS    = intArrayOf(110, 120, 130)
    private val RS_H_ROADS    = intArrayOf(145, 158, 170, 182, 194)

    // Lake bounds (SHALLOW_WATER)
    private const val LAKE_X1 = 60;  private const val LAKE_X2 = 155
    private const val LAKE_Y1 = 148; private const val LAKE_Y2 = 192

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
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, t: TileType) {
            for (y in y0..y1) for (x in x0..x1) set(x, y, t)
        }

        // ---- River (full height)
        for (y in 0 until MAP_H) { set(RIVER_X1, y, TileType.WATER); set(RIVER_X2, y, TileType.WATER) }

        // ---- Legacy Ashcombe roads + decoration (preserved exactly)
        for (x in 0 until RIVER_X1) { set(x, 10, TileType.ROAD); set(x, 22, TileType.ROAD) }
        for (y in 0 until WQ_END) { set(10, y, TileType.ROAD); set(22, y, TileType.ROAD); set(34, y, TileType.ROAD) }
        // Plaza in front of town hall
        for (x in 13..19) for (y in 11..12) { if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.PLAZA) }
        // Ashcombe park greenery
        for (x in 12..19) for (y in 23..29) {
            val t = when { rng.nextBoolean(0.18) -> TileType.TREE; rng.nextBoolean(0.25) -> TileType.FLOWERS; else -> TileType.GRASS }
            set(x, y, t)
        }
        for (x in 14..17) set(x, 26, TileType.PATH)
        // Legacy scattered trees
        repeat(46) {
            val x = rng.nextInt(RIVER_X1); val y = rng.nextInt(34)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }

        // ---- Bridges over river at shared horizontal roads
        for (roadY in SHARED_H_ROADS) { set(RIVER_X1, roadY, TileType.ROAD); set(RIVER_X2, roadY, TileType.ROAD) }
        set(RIVER_X1, EV_LOCAL_ROAD_Y, TileType.ROAD); set(RIVER_X2, EV_LOCAL_ROAD_Y, TileType.ROAD)
        // New bridges at y=110,120,130 for Old Town ↔ Wealthy Quarter
        for (roadY in NEW_BRIDGE_H) { set(RIVER_X1, roadY, TileType.ROAD); set(RIVER_X2, roadY, TileType.ROAD) }

        // ---- South Quarter horizontal roads (west side)
        for (roadY in SQ_H_ROADS) for (x in 0 until RIVER_X1) set(x, roadY, TileType.ROAD)

        // ---- East of river: shared horizontal roads
        val eastStart = RIVER_X2 + 1
        val allEastHTop = (SHARED_H_ROADS.toList() + EV_LOCAL_ROAD_Y).distinct().sorted()
        for (roadY in allEastHTop) for (x in eastStart until MAP_W) set(x, roadY, TileType.ROAD)

        // ---- Old Town + Wealthy Quarter horizontal roads (y=100..139, west + east)
        for (roadY in OT_H_ROADS) {
            for (x in 0 until RIVER_X1) set(x, roadY, TileType.ROAD)   // Old Town
            for (x in eastStart until BP_START) set(x, roadY, TileType.ROAD) // Wealthy Quarter
        }

        // ---- Riverside + lake area roads
        for (roadY in RS_H_ROADS) for (x in 0 until BP_START) set(x, roadY, TileType.ROAD)
        // Lake bridge at y=170 (road crosses lake)
        for (x in LAKE_X1..LAKE_X2) {
            if (tiles[170 * MAP_W + x] == TileType.SHALLOW_WATER || tiles[170 * MAP_W + x] == TileType.SAND) {
                set(x, 170, TileType.ROAD)
            }
        }

        // ---- Business Park / Retail Park roads
        for (roadY in intArrayOf(44, 56, 68, 80, 92, 110, 120, 130)) {
            for (x in BP_START until NQ_START) set(x, roadY, TileType.ROAD)
        }
        // Nightlife / Rural Fringe roads
        for (roadY in intArrayOf(10, 22, 44, 56, 68, 80, 92, 110, 120, 130, 145, 158, 170)) {
            for (x in NQ_START until MAP_W) set(x, roadY, TileType.ROAD)
        }

        // ---- Vertical roads (all columns, full height or partial)
        // West side: 0..WQ_END already set above; extend to Riverside
        for (vx in WEST_VERTICALS) for (y in 0 until WQ_END) set(vx, y, TileType.ROAD)
        for (vx in intArrayOf(10, 22)) for (y in WQ_END until MAP_H) set(vx, y, TileType.ROAD) // lakeside lanes
        // East Village + Civic + Wealthy Quarter
        for (vx in EV_VERTICALS) for (y in 0 until WQ_END) set(vx, y, TileType.ROAD)
        // Mill Lane
        for (vx in ML_VERTICALS) for (y in 0 until SQ_END) set(vx, y, TileType.ROAD)
        // Industrial Estate
        for (vx in IE_VERTICALS) for (y in 0 until WQ_END) set(vx, y, TileType.ROAD)
        // Business Park / Retail Park
        for (vx in BP_VERTICALS) for (y in 0 until WQ_END) set(vx, y, TileType.ROAD)
        // Nightlife / Rural Fringe
        for (vx in NQ_VERTICALS) for (y in 0 until MAP_H) set(vx, y, TileType.ROAD)

        // ---- Lake (SHALLOW_WATER oval in Riverside district)
        for (y in LAKE_Y1..LAKE_Y2) {
            for (x in LAKE_X1..LAKE_X2) {
                // Oval mask: skip corners so the lake looks rounded
                val rx = (x - (LAKE_X1 + LAKE_X2) / 2).toFloat() / ((LAKE_X2 - LAKE_X1) / 2f)
                val ry = (y - (LAKE_Y1 + LAKE_Y2) / 2).toFloat() / ((LAKE_Y2 - LAKE_Y1) / 2f)
                if (rx * rx + ry * ry <= 1.0f && tiles[y * MAP_W + x] != TileType.ROAD) {
                    set(x, y, TileType.SHALLOW_WATER)
                }
            }
        }
        // Sand/shingle fringe around the lake (1-2 tile border)
        for (y in (LAKE_Y1 - 2)..(LAKE_Y2 + 2)) {
            for (x in (LAKE_X1 - 2)..(LAKE_X2 + 2)) {
                if (x !in 0 until MAP_W || y !in 0 until MAP_H) continue
                if (tiles[y * MAP_W + x] == TileType.GRASS) {
                    val rx = (x - (LAKE_X1 + LAKE_X2) / 2).toFloat() / ((LAKE_X2 - LAKE_X1) / 2f + 2f)
                    val ry = (y - (LAKE_Y1 + LAKE_Y2) / 2).toFloat() / ((LAKE_Y2 - LAKE_Y1) / 2f + 2f)
                    if (rx * rx + ry * ry <= 1.0f) set(x, y, TileType.SAND)
                }
            }
        }

        // ---- Farmland in Rural Fringe (x=240..319, y=140..199)
        fill(NQ_START, WQ_END + 10, MAP_W - 1, MAP_H - 1, TileType.FARMLAND)
        // Restore roads over farmland
        for (vx in NQ_VERTICALS) for (y in WQ_END until MAP_H) set(vx, y, TileType.ROAD)
        for (roadY in intArrayOf(145, 158, 170)) for (x in NQ_START until MAP_W) set(x, roadY, TileType.ROAD)

        // ---- Dense woodland in Rural Fringe north (x=240..319, y=0..99) — trees between roads
        repeat(200) {
            val x = NQ_START + rng.nextInt(MAP_W - NQ_START)
            val y = rng.nextInt(SQ_END)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.WOODLAND)
        }
        // Woodland cluster near y=140..199 rural fringe top edge
        repeat(120) {
            val x = NQ_START + rng.nextInt(60)
            val y = WQ_END + rng.nextInt(40)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.WOODLAND)
        }

        // ---- Industrial Estate hard-standing
        fill(IE_START, 0, BP_START - 1, SQ_END - 1, TileType.HARDSTANDING)
        // Restore roads over hardstanding
        for (vx in IE_VERTICALS) for (y in 0 until SQ_END) set(vx, y, TileType.ROAD)
        for (roadY in intArrayOf(10, 22, 44, 56, 68, 80, 92)) for (x in IE_START until BP_START) set(x, roadY, TileType.ROAD)
        // Some grass breaks in the industrial estate
        repeat(80) {
            val x = IE_START + rng.nextInt(BP_START - IE_START)
            val y = rng.nextInt(SQ_END)
            if (tiles[y * MAP_W + x] == TileType.HARDSTANDING) set(x, y, TileType.GRASS)
        }

        // ---- District terrain decoration (existing districts kept unchanged)
        // South Quarter: scattered hedgerows
        repeat(90) {
            val x = rng.nextInt(RIVER_X1); val y = 34 + rng.nextInt(SQ_END - 34)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, if (rng.nextBoolean(0.4)) TileType.TREE else TileType.FLOWERS)
        }
        // East Village: lighter tree cover
        repeat(60) {
            val x = eastStart + rng.nextInt(56); val y = rng.nextInt(TC_END)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }
        // Civic Quarter: plazas near civic buildings
        for (x in 47..97) for (y in 47..50) {
            if (tiles[y * MAP_W + x] == TileType.GRASS && rng.nextBoolean(0.15)) set(x, y, TileType.PLAZA)
        }
        repeat(40) {
            val x = eastStart + rng.nextInt(56); val y = TC_END + rng.nextInt(SQ_END - TC_END)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }
        // Mill Lane: industrial scrub
        repeat(50) {
            val x = ML_START + rng.nextInt(IE_START - ML_START); val y = rng.nextInt(SQ_END)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.TREE)
        }
        // Old Town: tighter streets, some flowers/hedges
        repeat(50) {
            val x = rng.nextInt(RIVER_X1); val y = SQ_END + rng.nextInt(WQ_END - SQ_END)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, if (rng.nextBoolean(0.5)) TileType.FLOWERS else TileType.TREE)
        }
        // Wealthy Quarter: landscaped trees
        repeat(80) {
            val x = eastStart + rng.nextInt(ML_START - eastStart); val y = SQ_END + rng.nextInt(WQ_END - SQ_END)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, if (rng.nextBoolean(0.3)) TileType.FLOWERS else TileType.TREE)
        }
        // Riverside: lush greenery + flowers along lake shore
        repeat(100) {
            val x = rng.nextInt(NQ_START); val y = WQ_END + rng.nextInt(MAP_H - WQ_END)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, if (rng.nextBoolean(0.4)) TileType.FLOWERS else TileType.TREE)
        }
        // Business Park: manicured lawns (flowers)
        repeat(60) {
            val x = BP_START + rng.nextInt(NQ_START - BP_START); val y = rng.nextInt(WQ_END)
            if (tiles[y * MAP_W + x] == TileType.GRASS) set(x, y, TileType.FLOWERS)
        }

        // ---- Districts
        var nextId = 1L
        val districts = listOf(
            // Existing 5 (unchanged positions)
            District(nextId++, "Ashcombe",        DistrictType.TOWN_CENTRE,        0,        0,  42,       TC_END),
            District(nextId++, "South Quarter",   DistrictType.SOUTH_QUARTER,      0,    TC_END,  42,  SQ_END - TC_END),
            District(nextId++, "East Village",    DistrictType.EAST_VILLAGE,  EV_START,       0,  56,       TC_END),
            District(nextId++, "Civic Quarter",   DistrictType.CIVIC_QUARTER, EV_START,   TC_END,  56,  SQ_END - TC_END),
            District(nextId++, "Mill Lane",       DistrictType.MILL_LANE,     ML_START,       0,  IE_START - ML_START, SQ_END),
            // New expansion districts
            District(nextId++, "Old Town",        DistrictType.OLD_TOWN,           0,    SQ_END,  42,  WQ_END - SQ_END),
            District(nextId++, "Wealthy Quarter", DistrictType.WEALTHY_QUARTER, EV_START, SQ_END, ML_START - EV_START, WQ_END - SQ_END),
            District(nextId++, "Industrial Estate", DistrictType.INDUSTRIAL_ESTATE, IE_START, 0, BP_START - IE_START, WQ_END),
            District(nextId++, "Riverside",       DistrictType.RIVERSIDE,          0,    WQ_END, BP_START,  MAP_H - WQ_END),
            District(nextId++, "Business Park",   DistrictType.BUSINESS_PARK,  BP_START,       0, NQ_START - BP_START, WQ_END),
            District(nextId++, "Retail Park",     DistrictType.RETAIL_PARK,    BP_START,   WQ_END, NQ_START - BP_START, MAP_H - WQ_END),
            District(nextId++, "Nightlife Quarter", DistrictType.NIGHTLIFE_QUARTER, NQ_START, 0, MAP_W - NQ_START, WQ_END),
            District(nextId++, "Rural Fringe",    DistrictType.RURAL_FRINGE,   NQ_START,  WQ_END, MAP_W - NQ_START, MAP_H - WQ_END)
        )

        // ---- Building lots
        val homeLots   = mutableListOf<BuildingLot>()
        val civicLots  = mutableListOf<BuildingLot>()

        placeSouthQuarterHomes(homeLots, rng)
        placeEastVillageHomes(homeLots, rng)
        placeMillLaneHomes(homeLots, rng)
        placeOldTownHomes(homeLots, rng)
        placeWealthyQuarterHomes(homeLots, rng)
        placeRiversideHomes(homeLots, rng)
        placeBusinessParkHomes(homeLots, rng)
        placeNightlifeHomes(homeLots, rng)
        placeRuralFringeHomes(homeLots, rng)

        placeCivicBuildings(civicLots)

        return GenerationResult(TownMap(MAP_W, MAP_H, tiles), districts, homeLots, civicLots)
    }

    // ----------------------------------------------------------------- home placement helpers

    private fun placeHomeRow(
        lots: MutableList<BuildingLot>,
        rng: SimRandom,
        xBlocks: List<IntRange>,
        roadY: Int,
        homesOnNorth: Boolean,
        districtType: DistrictType,
        streetName: String,
        homeType: BuildingType = BuildingType.TERRACE,
        homeTypeAlt: BuildingType = BuildingType.HOUSE,
        altProb: Double = 0.3,
        capacityOverride: Int = 6,
        valueOverride: Double = 40_000.0
    ) {
        val bldgY = if (homesOnNorth) roadY - 3 else roadY + 1
        for (range in xBlocks) {
            var x = range.first
            while (x + 2 <= range.last) {
                lots += BuildingLot(
                    x = x, y = bldgY, w = 3, h = 3,
                    doorX = x + 1, doorY = roadY,
                    type = if (rng.nextBoolean(altProb)) homeType else homeTypeAlt,
                    districtType = districtType,
                    streetName = streetName,
                    capacity = capacityOverride,
                    value = valueOverride
                )
                x += 4
            }
        }
    }

    private fun placeSouthQuarterHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        val xBlocks = listOf(0..9, 11..21, 23..33, 35..41)
        val streetNames = mapOf(44 to "Millbrook Road", 56 to "Chapel Street", 68 to "Orchard Lane", 80 to "Ferndale Road", 92 to "Brook Street")
        for (roadY in SQ_H_ROADS) {
            val name = streetNames[roadY] ?: "South Quarter"
            placeHomeRow(lots, rng, xBlocks, roadY, false, DistrictType.SOUTH_QUARTER, name)
            if (roadY - 3 >= 45) placeHomeRow(lots, rng, xBlocks, roadY, true, DistrictType.SOUTH_QUARTER, name)
        }
    }

    private fun placeEastVillageHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        val xBlocks = listOf(44..54, 56..69, 71..84, 86..98)
        val eastVillageRoads = intArrayOf(10, 22, EV_LOCAL_ROAD_Y, 44)
        val streetNames = mapOf(10 to "River Road", 22 to "Station Road", EV_LOCAL_ROAD_Y to "Oak Avenue", 44 to "The Vale")
        for (roadY in eastVillageRoads) {
            val name = streetNames[roadY] ?: "East Village"
            if (roadY + 3 < TC_END) placeHomeRow(lots, rng, xBlocks, roadY, false, DistrictType.EAST_VILLAGE, name)
            if (roadY - 3 >= 0 && roadY <= 43) placeHomeRow(lots, rng, xBlocks, roadY, true, DistrictType.EAST_VILLAGE, name)
        }
    }

    private fun placeMillLaneHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        val xBlocks = listOf(100..114, 116..129, 131..144, 146..156)
        val millRoads = intArrayOf(10, 22, EV_LOCAL_ROAD_Y)
        val streetNames = mapOf(10 to "Mill Lane North", 22 to "Foundry Road", EV_LOCAL_ROAD_Y to "Cinder Terrace")
        for (roadY in millRoads) {
            val name = streetNames[roadY] ?: "Mill Lane"
            if (roadY + 3 < TC_END) placeHomeRow(lots, rng, xBlocks, roadY, false, DistrictType.MILL_LANE, name)
            if (roadY - 3 >= 0) placeHomeRow(lots, rng, xBlocks, roadY, true, DistrictType.MILL_LANE, name)
        }
    }

    private fun placeOldTownHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // Old Town: narrow terraces along 3 horizontal streets in x=0..41
        val xBlocks = listOf(0..9, 11..21, 23..33, 35..41)
        val streetNames = mapOf(110 to "Church Lane", 120 to "Market Street", 130 to "Priory Road")
        for (roadY in OT_H_ROADS) {
            val name = streetNames[roadY] ?: "Old Town"
            placeHomeRow(lots, rng, xBlocks, roadY, false, DistrictType.OLD_TOWN, name,
                homeType = BuildingType.TERRACE, homeTypeAlt = BuildingType.COTTAGE, altProb = 0.35)
            if (roadY - 3 >= SQ_END + 1) placeHomeRow(lots, rng, xBlocks, roadY, true, DistrictType.OLD_TOWN, name,
                homeType = BuildingType.TERRACE, homeTypeAlt = BuildingType.COTTAGE, altProb = 0.35)
        }
    }

    private fun placeWealthyQuarterHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // Wealthy Quarter: wider blocks, larger houses with bigger footprint (4×3)
        val xBlocks = listOf(44..60, 62..78, 80..96, 98..115, 117..135, 137..155)
        val streetNames = mapOf(110 to "Lakeview Avenue", 120 to "Millstream Road", 130 to "The Crescent")
        for (roadY in OT_H_ROADS) {
            val name = streetNames[roadY] ?: "Wealthy Quarter"
            val bldgY = roadY + 1
            // Place larger 4×3 houses with 5-tile spacing (spacious lots)
            for (range in xBlocks) {
                var x = range.first
                while (x + 3 <= range.last) {
                    lots += BuildingLot(
                        x = x, y = bldgY, w = 4, h = 3,
                        doorX = x + 2, doorY = roadY,
                        type = if (rng.nextBoolean(0.6)) BuildingType.HOUSE else BuildingType.COTTAGE,
                        districtType = DistrictType.WEALTHY_QUARTER, streetName = name,
                        capacity = 5, value = 180_000.0
                    )
                    x += 6
                }
            }
            // Also north side where space allows
            val bldgYN = roadY - 3
            if (bldgYN >= SQ_END + 1) {
                for (range in xBlocks) {
                    var x = range.first
                    while (x + 3 <= range.last) {
                        lots += BuildingLot(
                            x = x, y = bldgYN, w = 4, h = 3,
                            doorX = x + 2, doorY = roadY,
                            type = if (rng.nextBoolean(0.6)) BuildingType.HOUSE else BuildingType.COTTAGE,
                            districtType = DistrictType.WEALTHY_QUARTER, streetName = name,
                            capacity = 5, value = 180_000.0
                        )
                        x += 6
                    }
                }
            }
        }
    }

    private fun placeRiversideHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // Riverside: homes along lakeside roads, avoiding the lake footprint
        val riverRoads = intArrayOf(145, 158, 194)
        val streetNames = mapOf(145 to "Lake Road", 158 to "Waterside Close", 194 to "Weir Lane")
        for (roadY in riverRoads) {
            val bldgY = roadY + 1
            if (bldgY + 3 >= MAP_H) continue
            // x blocks avoiding the lake (LAKE_X1..LAKE_X2 at y=148..192)
            val xBlocks = mutableListOf<IntRange>()
            xBlocks += 0..15; xBlocks += 17..35; xBlocks += 37..RIVER_X1 - 1
            if (roadY >= LAKE_Y2 + 5 || roadY + 4 < LAKE_Y1) {
                // Full east side too when not obstructed by lake
                xBlocks += (RIVER_X2 + 1)..(LAKE_X1 - 3)
                xBlocks += (LAKE_X2 + 3)..(BP_START - 3)
            } else {
                xBlocks += (RIVER_X2 + 1)..(LAKE_X1 - 5)
                xBlocks += (LAKE_X2 + 5)..(BP_START - 3)
            }
            val name = streetNames[roadY] ?: "Riverside"
            for (range in xBlocks.filter { !it.isEmpty() }) {
                var x = range.first
                while (x + 2 <= range.last) {
                    lots += BuildingLot(
                        x = x, y = bldgY, w = 3, h = 3,
                        doorX = x + 1, doorY = roadY,
                        type = if (rng.nextBoolean(0.4)) BuildingType.HOUSE else BuildingType.TERRACE,
                        districtType = DistrictType.RIVERSIDE, streetName = name,
                        capacity = 5, value = 120_000.0
                    )
                    x += 4
                }
            }
        }
    }

    private fun placeBusinessParkHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // Retail Park: a handful of flats above shops
        val xBlocks = listOf(202..214, 217..228)
        for (range in xBlocks) {
            var x = range.first
            while (x + 3 <= range.last) {
                lots += BuildingLot(
                    x = x, y = WQ_END + 3, w = 4, h = 3,
                    doorX = x + 2, doorY = WQ_END + 3 + 3,
                    type = BuildingType.FLAT,
                    districtType = DistrictType.RETAIL_PARK, streetName = "Retail Park Flats",
                    capacity = 8, value = 90_000.0
                )
                x += 6
            }
        }
    }

    private fun placeNightlifeHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // Nightlife Quarter: terraced houses on side streets
        val xBlocks = listOf(241..253, 258..272, 277..292, 297..310)
        val streets = listOf("Dock Street", "Brew Lane", "Night Court", "Canal Road")
        for ((i, roadY) in intArrayOf(22, 56, 80).withIndex()) {
            val name = streets.getOrElse(i) { "Nightlife Quarter" }
            for (range in xBlocks) {
                var x = range.first
                while (x + 2 <= range.last) {
                    lots += BuildingLot(
                        x = x, y = roadY + 1, w = 3, h = 3,
                        doorX = x + 1, doorY = roadY,
                        type = BuildingType.TERRACE,
                        districtType = DistrictType.NIGHTLIFE_QUARTER, streetName = name,
                        capacity = 4, value = 60_000.0
                    )
                    x += 4
                }
            }
        }
    }

    private fun placeRuralFringeHomes(lots: MutableList<BuildingLot>, rng: SimRandom) {
        // Rural Fringe: scattered cottages and farmhouses on country lanes
        val lanes = intArrayOf(158, 170)
        val xBlocks = listOf(242..253, 260..273, 278..290, 296..310)
        for (roadY in lanes) {
            val name = if (roadY == 158) "Meadow Lane" else "Farm Track"
            for (range in xBlocks) {
                var x = range.first
                // Sparse — only 1 home per 8 tiles
                while (x + 2 <= range.last) {
                    if (rng.nextBoolean(0.6)) {
                        lots += BuildingLot(
                            x = x, y = roadY + 1, w = 3, h = 3,
                            doorX = x + 1, doorY = roadY,
                            type = if (rng.nextBoolean(0.7)) BuildingType.COTTAGE else BuildingType.HOUSE,
                            districtType = DistrictType.RURAL_FRINGE, streetName = name,
                            capacity = 4, value = 95_000.0
                        )
                    }
                    x += 8
                }
            }
        }
    }

    // ----------------------------------------------------------------- civic buildings

    private data class CivicSpec(
        val name: String, val type: BuildingType, val x: Int, val y: Int,
        val w: Int, val h: Int, val doorX: Int, val doorY: Int,
        val noise: Double = 15.0, val capacity: Int = 20, val value: Double = 80_000.0,
        val districtType: DistrictType = DistrictType.CIVIC_QUARTER
    )

    private fun placeCivicBuildings(lots: MutableList<BuildingLot>) {
        val specs = listOf(
            // ---- Civic Quarter (existing) ----
            CivicSpec("East Village Fire Station",     BuildingType.FIRE_STATION,
                x=45, y=45, w=4, h=3, doorX=47, doorY=44, noise=30.0, capacity=12, value=100_000.0),
            CivicSpec("East Village Police Station",   BuildingType.POLICE_STATION,
                x=50, y=45, w=4, h=3, doorX=52, doorY=44, noise=15.0, capacity=12, value=100_000.0),
            CivicSpec("East Village Community Centre", BuildingType.COMMUNITY_CENTRE,
                x=57, y=45, w=6, h=4, doorX=60, doorY=44, noise=22.0, capacity=80, value=120_000.0),
            CivicSpec("East Village Sports Hall",      BuildingType.SPORTS_HALL,
                x=64, y=45, w=7, h=5, doorX=67, doorY=44, noise=28.0, capacity=60, value=150_000.0),
            CivicSpec("East Village Clinic",           BuildingType.CLINIC,
                x=72, y=45, w=4, h=3, doorX=74, doorY=44, noise=10.0, capacity=10, value=90_000.0),
            CivicSpec("East Village Library",          BuildingType.LIBRARY,
                x=77, y=45, w=4, h=3, doorX=79, doorY=44, noise=5.0,  capacity=30, value=80_000.0),
            CivicSpec("Ashcombe Secondary School",     BuildingType.SCHOOL,
                x=45, y=57, w=8, h=5, doorX=49, doorY=56, noise=30.0, capacity=60, value=180_000.0),
            CivicSpec("Riverside Recreation Ground",   BuildingType.PARK,
                x=55, y=57, w=8, h=6, doorX=59, doorY=56, noise=18.0, capacity=80, value=50_000.0),

            // ---- Mill Lane (existing) ----
            CivicSpec("Mill Lane Works",     BuildingType.FACTORY,
                x=101, y=45, w=6, h=5, doorX=104, doorY=44, noise=65.0, capacity=18, value=95_000.0,
                districtType=DistrictType.MILL_LANE),
            CivicSpec("Riverside Warehouse", BuildingType.WAREHOUSE,
                x=108, y=45, w=5, h=4, doorX=110, doorY=44, noise=30.0, capacity=10, value=60_000.0,
                districtType=DistrictType.MILL_LANE),
            CivicSpec("The Riverside Arms",  BuildingType.PUB,
                x=114, y=45, w=4, h=3, doorX=116, doorY=44, noise=45.0, capacity=14, value=55_000.0,
                districtType=DistrictType.MILL_LANE),
            CivicSpec("Corner Shop",         BuildingType.GROCER,
                x=119, y=45, w=3, h=3, doorX=120, doorY=44, noise=12.0, capacity=8,  value=45_000.0,
                districtType=DistrictType.MILL_LANE),

            // ---- Old Town (new) ----
            CivicSpec("St Cuthbert's Church",   BuildingType.COMMUNITY_CENTRE,
                x=3, y=101, w=5, h=4, doorX=5, doorY=110, noise=5.0, capacity=60, value=200_000.0,
                districtType=DistrictType.OLD_TOWN),
            CivicSpec("Old Town Cemetery",      BuildingType.CEMETERY,
                x=25, y=101, w=5, h=4, doorX=27, doorY=110, noise=0.0, capacity=40, value=10_000.0,
                districtType=DistrictType.OLD_TOWN),
            CivicSpec("The King's Head",        BuildingType.PUB,
                x=10, y=111, w=4, h=3, doorX=12, doorY=110, noise=40.0, capacity=12, value=70_000.0,
                districtType=DistrictType.OLD_TOWN),
            CivicSpec("Old Town Bakery",        BuildingType.BAKERY,
                x=15, y=111, w=3, h=3, doorX=16, doorY=110, noise=15.0, capacity=8, value=45_000.0,
                districtType=DistrictType.OLD_TOWN),
            CivicSpec("Old Town Nursery",       BuildingType.NURSERY,
                x=20, y=111, w=4, h=3, doorX=22, doorY=110, noise=20.0, capacity=20, value=60_000.0,
                districtType=DistrictType.OLD_TOWN),

            // ---- Wealthy Quarter (new) ----
            CivicSpec("Waterside Park",         BuildingType.PARK,
                x=48, y=101, w=8, h=5, doorX=52, doorY=110, noise=8.0, capacity=60, value=40_000.0,
                districtType=DistrictType.WEALTHY_QUARTER),
            CivicSpec("Millbrook Primary School", BuildingType.SCHOOL,
                x=88, y=101, w=5, h=4, doorX=90, doorY=110, noise=25.0, capacity=40, value=120_000.0,
                districtType=DistrictType.WEALTHY_QUARTER),

            // ---- Industrial Estate (new) ----
            CivicSpec("Ashcombe Industrial Works", BuildingType.FACTORY,
                x=162, y=5, w=6, h=5, doorX=165, doorY=10, noise=70.0, capacity=20, value=120_000.0,
                districtType=DistrictType.INDUSTRIAL_ESTATE),
            CivicSpec("Northern Warehouse",       BuildingType.WAREHOUSE,
                x=170, y=5, w=5, h=4, doorX=172, doorY=10, noise=35.0, capacity=12, value=70_000.0,
                districtType=DistrictType.INDUSTRIAL_ESTATE),
            CivicSpec("Recycling Centre",         BuildingType.VACANT,
                x=180, y=5, w=4, h=3, doorX=182, doorY=10, noise=25.0, capacity=6, value=30_000.0,
                districtType=DistrictType.INDUSTRIAL_ESTATE),
            CivicSpec("Industrial Fire Station",  BuildingType.FIRE_STATION,
                x=162, y=23, w=4, h=3, doorX=164, doorY=22, noise=30.0, capacity=8, value=90_000.0,
                districtType=DistrictType.INDUSTRIAL_ESTATE),
            CivicSpec("Southern Works",           BuildingType.FACTORY,
                x=162, y=57, w=7, h=5, doorX=165, doorY=56, noise=68.0, capacity=18, value=100_000.0,
                districtType=DistrictType.INDUSTRIAL_ESTATE),
            CivicSpec("Trade Counter",            BuildingType.HARDWARE,
                x=172, y=57, w=4, h=3, doorX=174, doorY=56, noise=20.0, capacity=8, value=55_000.0,
                districtType=DistrictType.INDUSTRIAL_ESTATE),
            CivicSpec("Ashcombe Motor Works",     BuildingType.GARAGE,
                x=178, y=57, w=4, h=3, doorX=180, doorY=56, noise=30.0, capacity=6, value=60_000.0,
                districtType=DistrictType.INDUSTRIAL_ESTATE),

            // ---- Business Park (new) ----
            CivicSpec("Ashcombe Technology Park",  BuildingType.OFFICE,
                x=202, y=5, w=6, h=4, doorX=205, doorY=10, noise=10.0, capacity=30, value=200_000.0,
                districtType=DistrictType.BUSINESS_PARK),
            CivicSpec("Business Park Café",        BuildingType.CAFE,
                x=210, y=5, w=3, h=3, doorX=211, doorY=10, noise=15.0, capacity=10, value=50_000.0,
                districtType=DistrictType.BUSINESS_PARK),
            CivicSpec("Ashcombe Hotel",            BuildingType.HOTEL,
                x=215, y=5, w=5, h=4, doorX=217, doorY=10, noise=18.0, capacity=20, value=160_000.0,
                districtType=DistrictType.BUSINESS_PARK),
            CivicSpec("Ashcombe Offices North",    BuildingType.OFFICE,
                x=202, y=23, w=5, h=4, doorX=204, doorY=22, noise=8.0, capacity=16, value=150_000.0,
                districtType=DistrictType.BUSINESS_PARK),
            CivicSpec("Business Park Pharmacy",    BuildingType.PHARMACY,
                x=209, y=23, w=3, h=3, doorX=210, doorY=22, noise=8.0, capacity=6, value=70_000.0,
                districtType=DistrictType.BUSINESS_PARK),
            CivicSpec("Business Park Clinic",      BuildingType.CLINIC,
                x=214, y=23, w=3, h=3, doorX=215, doorY=22, noise=8.0, capacity=8, value=80_000.0,
                districtType=DistrictType.BUSINESS_PARK),
            CivicSpec("Ashcombe Hospital",         BuildingType.HOSPITAL,
                x=222, y=5, w=8, h=7, doorX=226, doorY=10, noise=25.0, capacity=40, value=400_000.0,
                districtType=DistrictType.BUSINESS_PARK),

            // ---- Retail Park (new) ----
            CivicSpec("Ashcombe Supermarket",     BuildingType.SUPERMARKET,
                x=202, y=145, w=7, h=5, doorX=205, doorY=144, noise=20.0, capacity=40, value=250_000.0,
                districtType=DistrictType.RETAIL_PARK),
            CivicSpec("Retail Park Cinema",       BuildingType.CINEMA,
                x=212, y=145, w=7, h=5, doorX=215, doorY=144, noise=28.0, capacity=50, value=200_000.0,
                districtType=DistrictType.RETAIL_PARK),
            CivicSpec("Retail Park Café",         BuildingType.CAFE,
                x=221, y=145, w=3, h=3, doorX=222, doorY=144, noise=15.0, capacity=10, value=50_000.0,
                districtType=DistrictType.RETAIL_PARK),
            CivicSpec("Retail Park Pharmacy",     BuildingType.PHARMACY,
                x=226, y=145, w=3, h=3, doorX=227, doorY=144, noise=8.0, capacity=6, value=65_000.0,
                districtType=DistrictType.RETAIL_PARK),
            CivicSpec("Retail Park Restaurant",   BuildingType.RESTAURANT,
                x=231, y=145, w=4, h=3, doorX=233, doorY=144, noise=22.0, capacity=16, value=80_000.0,
                districtType=DistrictType.RETAIL_PARK),
            CivicSpec("Retail Takeaway",          BuildingType.TAKEAWAY,
                x=237, y=145, w=3, h=3, doorX=238, doorY=144, noise=20.0, capacity=8, value=35_000.0,
                districtType=DistrictType.RETAIL_PARK),

            // ---- Nightlife Quarter (new) ----
            CivicSpec("The Grand Pub",            BuildingType.PUB,
                x=243, y=5, w=5, h=4, doorX=245, doorY=10, noise=60.0, capacity=30, value=90_000.0,
                districtType=DistrictType.NIGHTLIFE_QUARTER),
            CivicSpec("Velvet Nightclub",         BuildingType.NIGHTCLUB,
                x=250, y=5, w=5, h=4, doorX=252, doorY=10, noise=80.0, capacity=60, value=110_000.0,
                districtType=DistrictType.NIGHTLIFE_QUARTER),
            CivicSpec("The Anchor Bar",           BuildingType.PUB,
                x=258, y=5, w=4, h=3, doorX=260, doorY=10, noise=55.0, capacity=20, value=70_000.0,
                districtType=DistrictType.NIGHTLIFE_QUARTER),
            CivicSpec("Nightlife Police Post",    BuildingType.POLICE_STATION,
                x=264, y=5, w=3, h=3, doorX=265, doorY=10, noise=15.0, capacity=6, value=60_000.0,
                districtType=DistrictType.NIGHTLIFE_QUARTER),
            CivicSpec("Canal Café",               BuildingType.CAFE,
                x=243, y=23, w=3, h=3, doorX=244, doorY=22, noise=15.0, capacity=8, value=45_000.0,
                districtType=DistrictType.NIGHTLIFE_QUARTER),
            CivicSpec("Spice House Restaurant",   BuildingType.RESTAURANT,
                x=248, y=23, w=4, h=3, doorX=250, doorY=22, noise=25.0, capacity=14, value=65_000.0,
                districtType=DistrictType.NIGHTLIFE_QUARTER),

            // ---- Riverside (new) ----
            CivicSpec("Lakeside Park",            BuildingType.PARK,
                x=20, y=143, w=8, h=5, doorX=24, doorY=145, noise=8.0, capacity=80, value=30_000.0,
                districtType=DistrictType.RIVERSIDE),
            CivicSpec("Lakeside Café",            BuildingType.CAFE,
                x=30, y=159, w=3, h=3, doorX=31, doorY=158, noise=15.0, capacity=10, value=55_000.0,
                districtType=DistrictType.RIVERSIDE),
            CivicSpec("Riverside Community Hall", BuildingType.COMMUNITY_CENTRE,
                x=4, y=159, w=5, h=4, doorX=6, doorY=158, noise=18.0, capacity=40, value=80_000.0,
                districtType=DistrictType.RIVERSIDE),
            CivicSpec("Lake View Surgery",        BuildingType.CLINIC,
                x=10, y=171, w=4, h=3, doorX=12, doorY=170, noise=8.0, capacity=10, value=90_000.0,
                districtType=DistrictType.RIVERSIDE),
            CivicSpec("Riverside Lido",           BuildingType.SWIMMING_POOL,
                x=38, y=171, w=6, h=5, doorX=41, doorY=170, noise=22.0, capacity=40, value=120_000.0,
                districtType=DistrictType.RIVERSIDE),

            // ---- Rural Fringe (new) ----
            CivicSpec("Meadow Farm",              BuildingType.FACTORY,
                x=248, y=150, w=5, h=4, doorX=250, doorY=158, noise=20.0, capacity=8, value=80_000.0,
                districtType=DistrictType.RURAL_FRINGE),
            CivicSpec("The Ploughman Inn",        BuildingType.PUB,
                x=262, y=159, w=4, h=3, doorX=264, doorY=158, noise=30.0, capacity=10, value=50_000.0,
                districtType=DistrictType.RURAL_FRINGE),
            CivicSpec("Rural Fringe Park",        BuildingType.PARK,
                x=280, y=150, w=8, h=6, doorX=284, doorY=158, noise=5.0, capacity=30, value=20_000.0,
                districtType=DistrictType.RURAL_FRINGE),
        )
        for (s in specs) {
            lots += BuildingLot(
                x=s.x, y=s.y, w=s.w, h=s.h, doorX=s.doorX, doorY=s.doorY,
                type=s.type, districtType=s.districtType, streetName=s.name,
                noise=s.noise, capacity=s.capacity, value=s.value
            )
        }
    }
}
