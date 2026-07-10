package com.ripple.town.feature.town

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ripple.town.core.model.Activity
import com.ripple.town.core.model.TileType
import com.ripple.town.core.model.TimeOfDay
import com.ripple.town.core.model.TownMap
import com.ripple.town.core.model.Weather
import com.ripple.town.core.ui.Pose
import com.ripple.town.core.ui.ProceduralSpriteProvider
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.core.ui.SPRITE_H
import com.ripple.town.core.ui.SPRITE_W
import com.ripple.town.core.ui.SpriteProvider
import com.ripple.town.core.ui.TILE_PX
import com.ripple.town.data.BuildingUi
import com.ripple.town.data.ResidentUi
import com.ripple.town.data.WorldUi

sealed class TownTap {
    data class OnResident(val id: Long) : TownTap()
    data class OnBuilding(val id: Long) : TownTap()
    data object OnGround : TownTap()
}

class TownCamera {
    var scale by mutableFloatStateOf(3.2f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    /** True while the camera should gently track the followed resident; a manual pan/zoom clears it. */
    var isFollowing by mutableStateOf(true)

    fun centreOn(tileX: Float, tileY: Float, canvasW: Float, canvasH: Float) {
        offsetX = canvasW / 2f - tileX * TILE_PX * scale
        offsetY = canvasH / 2f - tileY * TILE_PX * scale
    }

    /** Nudges the camera a fraction of the way towards centring on (tileX, tileY) — smooth tracking, never a snap. */
    fun easeToward(tileX: Float, tileY: Float, canvasW: Float, canvasH: Float, factor: Float = 0.05f) {
        val targetX = canvasW / 2f - tileX * TILE_PX * scale
        val targetY = canvasH / 2f - tileY * TILE_PX * scale
        offsetX += (targetX - offsetX) * factor
        offsetY += (targetY - offsetY) * factor
    }
}

/**
 * The living town. Pure rendering: simulation state arrives as immutable
 * [WorldUi] snapshots; a frame clock advances walk cycles and eases sprite
 * positions between snapshots so the sim can tick slowly while the view
 * stays alive.
 */
@Composable
fun TownRenderer(
    world: WorldUi,
    camera: TownCamera,
    sprites: SpriteProvider,
    modifier: Modifier = Modifier,
    onTap: (TownTap) -> Unit = {}
) {
    val groundBitmap = remember(world.map) { renderGround(world.map, world.worldSeed) }
    var frame by remember { mutableLongStateOf(0L) }
    // Visual-only eased positions, separate from simulation state.
    val eased = remember { HashMap<Long, Offset>() }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last > 90_000_000L) { // ~11 fps animation clock
                    frame++
                    last = now
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // Any manual gesture takes over from automatic tracking.
                    if (pan != Offset.Zero || zoom != 1f) camera.isFollowing = false
                    val newScale = (camera.scale * zoom).coerceIn(1.4f, 9f)
                    // Zoom about the gesture centroid.
                    val scaleChange = newScale / camera.scale
                    camera.offsetX = centroid.x - (centroid.x - camera.offsetX) * scaleChange + pan.x
                    camera.offsetY = centroid.y - (centroid.y - camera.offsetY) * scaleChange + pan.y
                    camera.scale = newScale
                }
            }
            .pointerInput(world.residents.size, world.buildings.size) {
                detectTapGestures { tap ->
                    val tileX = (tap.x - camera.offsetX) / (TILE_PX * camera.scale)
                    val tileY = (tap.y - camera.offsetY) / (TILE_PX * camera.scale)
                    // Residents first (small targets get priority).
                    val hitResident = world.residents
                        .filter { it.visibleOnMap }
                        .minByOrNull { r ->
                            val p = eased[r.id] ?: Offset(r.x, r.y)
                            val dx = p.x + 0.5f - tileX
                            val dy = p.y - 0.4f - tileY
                            dx * dx + dy * dy
                        }
                        ?.takeIf { r ->
                            val p = eased[r.id] ?: Offset(r.x, r.y)
                            val dx = p.x + 0.5f - tileX
                            val dy = p.y - 0.4f - tileY
                            dx * dx + dy * dy < 1.1f
                        }
                    if (hitResident != null) {
                        onTap(TownTap.OnResident(hitResident.id)); return@detectTapGestures
                    }
                    val hitBuilding = world.buildings.firstOrNull { b ->
                        tileX >= b.x && tileX < b.x + b.w && tileY >= b.y - 0.8f && tileY < b.y + b.h
                    }
                    if (hitBuilding != null) {
                        onTap(TownTap.OnBuilding(hitBuilding.id)); return@detectTapGestures
                    }
                    onTap(TownTap.OnGround)
                }
            }
    ) {
        @Suppress("UNUSED_EXPRESSION") frame // invalidate each animation frame
        val s = camera.scale
        val px = TILE_PX * s

        drawIntoCanvas { canvas ->
            val paint = Paint().apply { filterQuality = FilterQuality.None }
            // Ground
            canvas.save()
            canvas.translate(camera.offsetX, camera.offsetY)
            canvas.scale(s, s)
            canvas.drawImageRect(
                groundBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(groundBitmap.width, groundBitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(groundBitmap.width, groundBitmap.height),
                paint = paint
            )

            // Buildings, painter's order by bottom edge.
            for (b in world.buildings.sortedBy { it.y + it.h }) {
                val bmp = sprites.building(b.type, b.upgradeLevel, b.abandoned, b.id, b.condition)
                val topY = b.y * TILE_PX - (bmp.height - b.h * TILE_PX)
                canvas.drawImageRect(
                    bmp,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset((b.x * TILE_PX), topY),
                    dstSize = IntSize(bmp.width, bmp.height),
                    paint = paint
                )
                // Town rhythm, part 1: a closed shop reads as visibly "shut" — a flat
                // dusk-toned wash over its own footprint, using data already on
                // BuildingUi (businessOpen is null for non-businesses/homes, so this
                // only ever touches shops/pubs/clinics etc. that actually trade).
                if (b.businessOpen == false) {
                    val closedWash = Paint().apply { color = Color(0x552B3350) }
                    canvas.drawRect(
                        Rect(
                            (b.x * TILE_PX).toFloat(), topY.toFloat(),
                            (b.x * TILE_PX + bmp.width).toFloat(), (topY + bmp.height).toFloat()
                        ),
                        closedWash
                    )
                }
            }

            // Residents (eased towards their simulated positions).
            val followId = world.followedResidentId
            for (r in world.residents.filter { it.visibleOnMap }.sortedBy { it.y }) {
                val target = Offset(r.x, r.y)
                val prev = eased[r.id] ?: target
                val next = Offset(
                    prev.x + (target.x - prev.x) * 0.18f,
                    prev.y + (target.y - prev.y) * 0.18f
                )
                eased[r.id] = next
                val pose = poseFor(r)
                // Idle "breathing" cue: a slow 2-frame sway on anyone standing still,
                // driven only by the existing animation clock + resident id (no new
                // per-frame randomness) so a crowd doesn't animate in lockstep.
                val animFrame = when (pose) {
                    Pose.WALK -> (frame + r.id).toInt()
                    Pose.STAND -> ((frame + r.id) / 6).toInt() // much slower than walk
                    else -> 0
                }
                val bmp = sprites.resident(r.sprite, pose, animFrame, r.lifeStage, r.occupation)
                val drawX = next.x * TILE_PX + (TILE_PX - SPRITE_W) / 2f
                val drawY = next.y * TILE_PX - SPRITE_H + 3f
                if (r.id == followId) {
                    val ring = Paint().apply { color = RippleColors.Gold.copy(alpha = 0.55f) }
                    canvas.drawCircle(Offset(next.x * TILE_PX + TILE_PX / 2f, next.y * TILE_PX + 1f), 6.5f, ring)
                    val ring2 = Paint().apply { color = RippleColors.Cream.copy(alpha = 0.8f) }
                    canvas.drawCircle(Offset(next.x * TILE_PX + TILE_PX / 2f, next.y * TILE_PX + 1f), 5.2f, ring2)
                }
                canvas.drawImageRect(
                    bmp,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(drawX.toInt(), drawY.toInt()),
                    dstSize = IntSize(SPRITE_W, SPRITE_H),
                    paint = paint
                )
            }
            canvas.restore()

            // Gentle continuous tracking: nudge next frame's offset towards the followed
            // resident rather than snapping, so the camera drifts smoothly with them.
            if (camera.isFollowing && followId != null) {
                eased[followId]?.let { pos -> camera.easeToward(pos.x, pos.y, size.width, size.height) }
            }
        }

        // Time-of-day & weather washes (screen space, cheap).
        val tint = when (world.timeOfDay) {
            TimeOfDay.DAWN -> Color(0x22E8A87C)
            TimeOfDay.MORNING -> Color.Transparent
            TimeOfDay.AFTERNOON -> Color(0x11F6D67C)
            TimeOfDay.EVENING -> Color(0x33B2593F)
            TimeOfDay.NIGHT -> Color(0x662B3350)
        }
        if (tint != Color.Transparent) drawRect(tint)
        when (world.weather) {
            Weather.RAIN, Weather.STORM -> {
                val drops = if (world.weather == Weather.STORM) 90 else 45
                val seedBase = frame * 31
                repeat(drops) { i ->
                    val hx = ((seedBase + i * 733) % 1000) / 1000f * size.width
                    val hy = ((seedBase * 3 + i * 971) % 1000) / 1000f * size.height
                    drawLine(
                        Color(0x557FA9BC), Offset(hx, hy), Offset(hx - 2f, hy + 9f), strokeWidth = 1.5f
                    )
                }
                if (world.weather == Weather.STORM) drawRect(Color(0x22374357))
            }
            Weather.FOG -> drawRect(Color(0x44DDD8CB))
            Weather.SNOW -> {
                repeat(40) { i ->
                    val hx = ((frame * 13 + i * 733) % 1000) / 1000f * size.width
                    val hy = ((frame * 7 + i * 971) % 1000) / 1000f * size.height
                    drawCircle(Color(0xAAF5F2E8), radius = 1.8f, center = Offset(hx, hy))
                }
            }
            else -> {}
        }
    }
}

/**
 * Derives a resident's pose purely from already-known UI state (their
 * [Activity], plus [ResidentUi.conditionLabels] for the injured/ill split).
 * Deliberately data-driven and cheap — no per-frame randomness, safe to call
 * every frame for every visible resident. See `poseFor(Activity)` overload
 * below for the base activity→pose mapping this extends.
 */
fun poseFor(resident: ResidentUi): Pose {
    // Injured is a distinct, already-computed condition (HealthConditionType.INJURY,
    // surfaced via activeConditions()/conditionLabels) from generic illness. Both
    // still route through the same resting/at-clinic activities, so this is a
    // pose-level split (Pose.INJURED vs Pose.ILL), not a new Activity.
    if (resident.activity == Activity.RESTING_ILL || resident.activity == Activity.AT_CLINIC) {
        val injured = resident.conditionLabels.any { "injury" in it.lowercase() }
        return if (injured) Pose.INJURED else Pose.ILL
    }
    return poseFor(resident.activity)
}

fun poseFor(activity: Activity): Pose = when (activity) {
    Activity.SLEEPING -> Pose.SLEEP
    Activity.TRAVELLING -> Pose.WALK
    Activity.WORKING, Activity.AT_SCHOOL, Activity.LEARNING -> Pose.WORK
    Activity.SOCIALISING, Activity.VISITING, Activity.COMMUNITY -> Pose.TALK
    Activity.EATING, Activity.RELAXING, Activity.SHOPPING -> Pose.SIT
    Activity.RESTING_ILL, Activity.AT_CLINIC -> Pose.ILL
    Activity.ARGUING -> Pose.ARGUE
    Activity.CELEBRATING -> Pose.CELEBRATE
    Activity.MOURNING -> Pose.MOURN
    Activity.EXERCISING -> Pose.WALK
    else -> Pose.STAND
}

/** Pre-renders the entire ground layer once per map. */
private fun renderGround(map: TownMap, seed: Long): ImageBitmap {
    val bmp = ImageBitmap(map.width * TILE_PX, map.height * TILE_PX)
    val canvas = ComposeCanvas(bmp)
    val paint = Paint()
    fun rect(x0: Float, y0: Float, w: Float, h: Float, c: Color) {
        paint.color = c
        canvas.drawRect(Rect(x0, y0, x0 + w, y0 + h), paint)
    }
    for (y in 0 until map.height) {
        for (x in 0 until map.width) {
            val gx = x * TILE_PX.toFloat()
            val gy = y * TILE_PX.toFloat()
            val checker = (x + y) % 2 == 0
            when (map.tileAt(x, y)) {
                TileType.GRASS -> {
                    rect(gx, gy, TILE_PX.toFloat(), TILE_PX.toFloat(), if (checker) RippleColors.Grass else RippleColors.GrassDark)
                    if ((x * 7 + y * 13 + seed).mod(11L) == 0L) rect(gx + 3, gy + 4, 1f, 1f, RippleColors.DeepGreen)
                }
                TileType.ROAD -> {
                    rect(gx, gy, TILE_PX.toFloat(), TILE_PX.toFloat(), RippleColors.Road)
                    rect(gx, gy, TILE_PX.toFloat(), 1f, RippleColors.RoadEdge)
                }
                TileType.PATH -> rect(gx, gy, TILE_PX.toFloat(), TILE_PX.toFloat(), RippleColors.Path)
                TileType.PLAZA -> {
                    rect(gx, gy, TILE_PX.toFloat(), TILE_PX.toFloat(), RippleColors.Plaza)
                    rect(gx, gy, 1f, TILE_PX.toFloat(), RippleColors.RoadEdge.copy(alpha = 0.5f))
                }
                TileType.WATER -> {
                    rect(gx, gy, TILE_PX.toFloat(), TILE_PX.toFloat(), if (checker) RippleColors.Water else RippleColors.WaterDeep)
                }
                TileType.TREE -> {
                    rect(gx, gy, TILE_PX.toFloat(), TILE_PX.toFloat(), if (checker) RippleColors.Grass else RippleColors.GrassDark)
                    rect(gx + 3, gy + 5, 2f, 3f, RippleColors.TreeTrunk)
                    rect(gx + 1, gy + 1, 6f, 5f, RippleColors.TreeGreen)
                    rect(gx + 2, gy, 4f, 1f, RippleColors.TreeGreen)
                }
                TileType.FLOWERS -> {
                    rect(gx, gy, TILE_PX.toFloat(), TILE_PX.toFloat(), if (checker) RippleColors.Grass else RippleColors.GrassDark)
                    rect(gx + 2, gy + 2, 1f, 1f, RippleColors.Flowers)
                    rect(gx + 5, gy + 4, 1f, 1f, Color(0xFFE9D96F))
                    rect(gx + 3, gy + 6, 1f, 1f, RippleColors.Flowers)
                }
            }
        }
    }
    return bmp
}
