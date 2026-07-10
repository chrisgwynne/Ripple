package com.ripple.town.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.geometry.Rect
import com.ripple.town.core.model.BuildingType
import com.ripple.town.core.model.SpriteConfig

/**
 * Asset seam: the renderer only asks a [SpriteProvider] for bitmaps, so the
 * procedural placeholder art can be swapped for hand-drawn sprite atlases
 * later without touching the town renderer.
 */
interface SpriteProvider {
    fun resident(config: SpriteConfig, pose: Pose, frame: Int): ImageBitmap
    fun building(type: BuildingType, level: Int, abandoned: Boolean, seed: Long): ImageBitmap
}

enum class Pose { STAND, WALK, SIT, SLEEP, WORK, TALK, ILL, ARGUE, CELEBRATE, MOURN }

/** Pixel dimensions of a resident sprite (before scaling). */
const val SPRITE_W = 10
const val SPRITE_H = 14
/** Pixels per map tile in pre-rendered art. */
const val TILE_PX = 8

/**
 * Draws small readable pixel people and buildings into cached ImageBitmaps.
 * Everything nearest-neighbour scales, so it reads as crisp pixel art.
 */
class ProceduralSpriteProvider : SpriteProvider {

    private val residentCache = HashMap<Long, ImageBitmap>()
    private val buildingCache = HashMap<Long, ImageBitmap>()

    private val skinTones = listOf(
        Color(0xFFF3D3B3), Color(0xFFE0B088), Color(0xFFB07B4F), Color(0xFF7C5233)
    )
    private val hairColors = listOf(
        Color(0xFF3B2E23), Color(0xFF6E4A2A), Color(0xFFB98A4E), Color(0xFFC9C2B5), Color(0xFF8A3A2A)
    )
    private val shirtColors = listOf(
        Color(0xFF7C9B62), Color(0xFFB2593F), Color(0xFF8FB6C9), Color(0xFFD9A648),
        Color(0xFF9B7FB0), Color(0xFFC98BA4), Color(0xFF8A6F52), Color(0xFF5E7F8A)
    )
    private val trouserColors = listOf(
        Color(0xFF5C4934), Color(0xFF44506B), Color(0xFF6B5F4F), Color(0xFF3F5749), Color(0xFF74563F)
    )

    override fun resident(config: SpriteConfig, pose: Pose, frame: Int): ImageBitmap {
        val key = (config.hashCode().toLong() shl 20) xor (pose.ordinal.toLong() shl 4) xor (frame % 2).toLong()
        return residentCache.getOrPut(key) { drawResident(config, pose, frame % 2) }
    }

    private fun drawResident(config: SpriteConfig, pose: Pose, frame: Int): ImageBitmap {
        val bmp = ImageBitmap(SPRITE_W, SPRITE_H)
        val canvas = ComposeCanvas(bmp)
        val paint = Paint()
        fun px(x: Int, y: Int, c: Color) {
            if (x < 0 || y < 0 || x >= SPRITE_W || y >= SPRITE_H) return
            paint.color = c
            canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
        }
        fun rect(x0: Int, y0: Int, w: Int, h: Int, c: Color) {
            paint.color = c
            canvas.drawRect(Rect(x0.toFloat(), y0.toFloat(), (x0 + w).toFloat(), (y0 + h).toFloat()), paint)
        }

        val skin = skinTones[config.skinTone % skinTones.size]
        val hair = hairColors[config.hairColor % hairColors.size]
        val shirt = shirtColors[config.shirtColor % shirtColors.size]
        val trousers = trouserColors[config.trouserColor % trouserColors.size]
        val ink = Color(0xFF2E241B)

        if (pose == Pose.SLEEP) {
            // Lying down: a simple horizontal figure with a blanket.
            rect(1, 9, 8, 3, shirt)
            rect(1, 8, 3, 2, skin)
            rect(1, 7, 3, 1, hair)
            px(8, 6, Color(0xFFBFD3DD)); px(9, 4, Color(0xFFBFD3DD)) // zZ
            return bmp
        }

        // Head
        rect(3, 1, 4, 4, skin)
        // Hair styles: 0 short, 1 side-part, 2 long, 3 hat
        when (config.hairStyle % 4) {
            0 -> rect(3, 0, 4, 2, hair)
            1 -> { rect(3, 0, 4, 1, hair); px(3, 1, hair); px(6, 1, hair) }
            2 -> { rect(3, 0, 4, 2, hair); rect(2, 2, 1, 4, hair); rect(7, 2, 1, 4, hair) }
            3 -> { rect(2, 0, 6, 1, RippleColorsHat); rect(3, 1, 4, 1, RippleColorsHat) }
        }
        // Eyes
        px(4, 3, ink); px(6, 3, ink)
        // Body
        rect(3, 5, 4, 4, shirt)
        // Arms
        when (pose) {
            Pose.CELEBRATE -> { px(2, 3, skin); px(7, 3, skin); px(2, 4, shirt); px(7, 4, shirt) }
            Pose.WORK -> { rect(2, 6, 1, 2, skin); rect(7, 7, 1, 2, skin) }
            Pose.ARGUE -> { rect(2, 4, 1, 2, skin); rect(7, 6, 1, 2, skin) }
            else -> { rect(2, 5, 1, 3, shirt); rect(7, 5, 1, 3, shirt); px(2, 8, skin); px(7, 8, skin) }
        }
        // Legs
        if (pose == Pose.SIT) {
            rect(3, 9, 4, 2, trousers)
            rect(3, 11, 1, 1, trousers); rect(6, 11, 1, 1, trousers)
        } else if (pose == Pose.WALK && frame == 1) {
            rect(3, 9, 1, 4, trousers); rect(6, 9, 1, 3, trousers)
            px(3, 13, ink); px(6, 12, ink)
        } else {
            rect(3, 9, 1, 4, trousers); rect(6, 9, 1, 4, trousers)
            px(3, 13, ink); px(6, 13, ink)
        }
        // Status marks
        when (pose) {
            Pose.ILL -> { px(8, 1, Color(0xFF9BC08A)); px(9, 0, Color(0xFF9BC08A)) }
            Pose.TALK -> { px(8, 1, Color.White); px(9, 0, Color.White) }
            Pose.ARGUE -> { px(8, 0, Color(0xFFB2593F)); px(9, 1, Color(0xFFB2593F)) }
            Pose.MOURN -> { px(4, 4, Color(0xFF8FB6C9)) }
            Pose.CELEBRATE -> { px(1, 1, Color(0xFFD9A648)); px(8, 1, Color(0xFFD9A648)) }
            else -> {}
        }
        return bmp
    }

    override fun building(type: BuildingType, level: Int, abandoned: Boolean, seed: Long): ImageBitmap {
        val key = (type.ordinal.toLong() shl 16) xor (level.toLong() shl 8) xor
            (if (abandoned) 1L shl 7 else 0L) xor (seed and 0x7F)
        return buildingCache.getOrPut(key) { drawBuilding(type, level, abandoned, seed) }
    }

    private fun drawBuilding(type: BuildingType, level: Int, abandoned: Boolean, seed: Long): ImageBitmap {
        val (tw, th) = footprintOf(type)
        val w = tw * TILE_PX
        val bodyH = th * TILE_PX
        val roofH = TILE_PX - 2
        val h = bodyH + roofH
        val bmp = ImageBitmap(w, h)
        val canvas = ComposeCanvas(bmp)
        val paint = Paint()
        fun rect(x0: Int, y0: Int, ww: Int, hh: Int, c: Color) {
            paint.color = c
            canvas.drawRect(Rect(x0.toFloat(), y0.toFloat(), (x0 + ww).toFloat(), (y0 + hh).toFloat()), paint)
        }

        if (type == BuildingType.PARK) {
            // Parks are ground features: hedged lawn with a pond.
            rect(0, roofH, w, bodyH, Color(0xFF86A75F))
            rect(0, roofH, w, 2, Color(0xFF5E7F45))
            rect(0, h - 2, w, 2, Color(0xFF5E7F45))
            rect(w / 3, roofH + bodyH / 3, w / 3, bodyH / 3, Color(0xFF7FA9BC))
            return bmp
        }
        if (type == BuildingType.CEMETERY) {
            rect(0, roofH, w, bodyH, Color(0xFF93A876))
            var x = 3
            while (x < w - 4) {
                rect(x, roofH + 4, 3, 5, Color(0xFFB9B2A2))
                rect(x + 1, roofH + 3, 1, 1, Color(0xFFB9B2A2))
                x += 8
            }
            return bmp
        }

        val wall = if (abandoned) Color(0xFFA99F8C) else wallColorOf(type, seed)
        val wallShade = wall.darken(0.85f)
        val roof = if (abandoned) Color(0xFF7E766A) else roofColorOf(type, seed)
        val trim = Color(0xFF5C4934)
        val window = if (abandoned) Color(0xFF6B6B60) else Color(0xFFF6E7B2)

        // Roof (slightly overhanging)
        rect(0, 0, w, roofH, roof)
        rect(0, roofH - 1, w, 1, roof.darken(0.8f))
        // Walls
        rect(1, roofH, w - 2, bodyH, wall)
        rect(1, roofH, w - 2, 2, wallShade)
        // Door
        val doorW = 4
        rect(w / 2 - doorW / 2, h - 6, doorW, 6, trim)
        // Windows
        var wx = 4
        while (wx < w - 6) {
            if (wx < w / 2 - doorW || wx > w / 2 + doorW) {
                rect(wx, roofH + 4, 4, 4, window)
                rect(wx, roofH + 4, 4, 1, trim.copy(alpha = 0.5f))
            }
            wx += 8
        }
        // Shop sign band
        if (type !in listOf(BuildingType.HOUSE, BuildingType.COTTAGE, BuildingType.TERRACE, BuildingType.FACTORY)) {
            rect(2, roofH + 1, w - 4, 2, signColorOf(type))
        }
        // Factory chimney
        if (type == BuildingType.FACTORY) {
            rect(w - 8, 0, 4, roofH + 2, Color(0xFF7A6A57))
        }
        // Upgrade badge: an extension strip per level
        repeat(level.coerceAtMost(2)) { i ->
            rect(2 + i * 3, roofH - 3, 2, 3, roof.darken(0.75f))
        }
        // Abandoned: boarded door + cracked window
        if (abandoned) {
            rect(w / 2 - doorW / 2, h - 6, doorW, 2, Color(0xFF8A7B63))
            rect(w / 2 - doorW / 2, h - 3, doorW, 1, Color(0xFF8A7B63))
        }
        return bmp
    }

    fun footprintOf(type: BuildingType): Pair<Int, Int> = when (type) {
        BuildingType.TOWN_HALL -> 5 to 4
        BuildingType.SCHOOL -> 5 to 4
        BuildingType.FACTORY -> 5 to 4
        BuildingType.PARK -> 8 to 7
        BuildingType.CEMETERY -> 5 to 4
        BuildingType.BAKERY, BuildingType.HARDWARE, BuildingType.PUB,
        BuildingType.CLINIC, BuildingType.VACANT, BuildingType.WORKSHOP -> 4 to 3
        else -> 3 to 3
    }

    private fun wallColorOf(type: BuildingType, seed: Long): Color = when (type) {
        BuildingType.HOUSE, BuildingType.COTTAGE, BuildingType.TERRACE ->
            listOf(Color(0xFFE8D9B8), Color(0xFFDCC7A5), Color(0xFFD9CBB5), Color(0xFFE3D2AE))[(seed % 4).toInt()]
        BuildingType.TOWN_HALL -> Color(0xFFDED3BC)
        BuildingType.CLINIC -> Color(0xFFE9E2D2)
        BuildingType.SCHOOL -> Color(0xFFD9C7A2)
        BuildingType.FACTORY -> Color(0xFFB3A48E)
        BuildingType.PUB -> Color(0xFFCDA97E)
        else -> Color(0xFFE0CCA8)
    }

    private fun roofColorOf(type: BuildingType, seed: Long): Color = when (type) {
        BuildingType.HOUSE, BuildingType.COTTAGE, BuildingType.TERRACE ->
            listOf(Color(0xFFA85B44), Color(0xFF96543F), Color(0xFF8E6B4A), Color(0xFFA0654B))[(seed % 4).toInt()]
        BuildingType.TOWN_HALL -> Color(0xFF7D8FA3)
        BuildingType.CLINIC -> Color(0xFF8FB6C9)
        BuildingType.SCHOOL -> Color(0xFF97694C)
        BuildingType.FACTORY -> Color(0xFF6E6A5F)
        else -> Color(0xFFA85B44)
    }

    private fun signColorOf(type: BuildingType): Color = when (type) {
        BuildingType.BAKERY -> Color(0xFFD9A648)
        BuildingType.CAFE -> Color(0xFFC98BA4)
        BuildingType.PUB -> Color(0xFF55713F)
        BuildingType.GROCER -> Color(0xFF7C9B62)
        BuildingType.BOOKSHOP -> Color(0xFF44506B)
        BuildingType.TAILOR -> Color(0xFF9B7FB0)
        BuildingType.HARDWARE -> Color(0xFF8A6F52)
        BuildingType.CLINIC -> Color(0xFFB2593F)
        BuildingType.WORKSHOP -> Color(0xFF74563F)
        else -> Color(0xFF8A6F52)
    }

}

private val RippleColorsHat = Color(0xFF6B5F4F)

private fun Color.darken(factor: Float): Color =
    Color(red * factor, green * factor, blue * factor, alpha)
